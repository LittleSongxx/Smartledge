import base64
import hashlib
import html
import json
import math
import re
import time
from dataclasses import dataclass, field
from html.parser import HTMLParser
from io import BytesIO
from typing import Any

from fastapi import HTTPException

from rag_tools.config import config_int, config_value
from rag_tools.markdown_syntax import markdown_syntax_to_blocks, parse_markdown_syntax
from rag_tools.schemas.document_parse import (
    DocumentBlock,
    DocumentParseRequest,
    DocumentParseResponse,
    MarkdownSyntaxDocument,
    ParseArtifact,
    json_metadata,
)

ALIYUN_DOCMIND_PARSER_NAME = "aliyun_docmind"
NATIVE_TEXT_PARSER_NAME = "native_text"
DOCLING_PARSER_NAME = "docling"
PARSER_VERSION = "0.2.0"

ALIYUN_DOCMIND_FILE_TYPES = {"PDF", "DOCX", "XLSX", "PNG", "JPG", "JPEG", "BMP", "GIF"}
NATIVE_TEXT_FILE_TYPES = {"TXT", "MD", "HTML"}
SUPPORTED_FILE_TYPES = ALIYUN_DOCMIND_FILE_TYPES | NATIVE_TEXT_FILE_TYPES
ALIYUN_DOCMIND_CAPABILITIES = [
    "cloud-document-parser",
    "ocr",
    "layout",
    "reading-order",
    "table",
    "figure",
    "markdown",
    "layout-json",
    "visual-layout-info",
    "html-table",
]
NATIVE_TEXT_CAPABILITIES = [
    "native-text",
    "text",
    "markdown",
    "html",
    "structure",
    "commonmark",
    "gfm-table",
    "markdown-syntax-v1",
]
DOCLING_CAPABILITIES = [
    "local-pdf",
    "docling",
    "markdown",
    "markdown-syntax-v1",
]


@dataclass
class DocMindParseResult:
    blocks: list[DocumentBlock]
    markdown_syntax: MarkdownSyntaxDocument | None = None
    artifacts: list[ParseArtifact] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)


class AliyunDocMindParser:
    provider_name = ALIYUN_DOCMIND_PARSER_NAME
    provider_version = PARSER_VERSION
    supported_file_types = ALIYUN_DOCMIND_FILE_TYPES
    capabilities = ALIYUN_DOCMIND_CAPABILITIES

    def is_available(self) -> bool:
        return self._sdk_available() and bool(self._access_key_id()) and bool(self._access_key_secret())

    def unavailable_reason(self) -> str:
        if not self._sdk_available():
            return "未安装阿里云 Document Mind SDK，请执行 pip install -r requirements.txt。"
        missing = []
        if not self._access_key_id():
            missing.append("ALIBABA_CLOUD_ACCESS_KEY_ID")
        if not self._access_key_secret():
            missing.append("ALIBABA_CLOUD_ACCESS_KEY_SECRET")
        if missing:
            return "缺少阿里云访问凭证: " + ", ".join(missing)
        return ""

    def status(self) -> dict[str, Any]:
        available = self.is_available()
        return {
            "providerName": self.provider_name,
            "providerVersion": self.provider_version,
            "available": available,
            "failedReason": "" if available else self.unavailable_reason(),
            "supportedFileTypes": sorted(item.lower() for item in self.supported_file_types),
            "capabilities": list(self.capabilities),
            "endpoint": self._endpoint(),
            "llmEnhancement": self._llm_enhancement(),
            "enhancementMode": self._enhancement_mode(),
            "formulaEnhancement": self._formula_enhancement(),
            "outputHtmlTable": self._output_html_table(),
            "outputFormats": self._output_formats(),
            "credentialConfigured": bool(self._access_key_id()) and bool(self._access_key_secret()),
        }

    def parse(self, content: bytes, file_type: str, request: DocumentParseRequest) -> DocMindParseResult:
        if file_type not in self.supported_file_types:
            raise HTTPException(status_code=422, detail=f"阿里云 Document Mind 当前不支持文件类型: {file_type or 'UNKNOWN'}")
        if not self.is_available():
            raise HTTPException(status_code=503, detail=f"阿里云 Document Mind 不可用: {self.unavailable_reason()}")

        trace: dict[str, Any] = {
            "providerName": self.provider_name,
            "providerVersion": self.provider_version,
            "fileType": file_type,
            "fileName": request.file_name,
            "fileSizeBytes": len(content or b""),
        }
        client = self._client()
        submit_started = time.perf_counter()
        job_id = self._submit_job(client, content, request.file_name, file_type)
        trace["jobId"] = job_id
        trace["submitElapsedMs"] = _elapsed_ms(submit_started)
        wait_started = time.perf_counter()
        status_payload, poll_count = self._wait_until_finished(client, job_id)
        trace["pollElapsedMs"] = _elapsed_ms(wait_started)
        trace["pollCount"] = poll_count
        result_started = time.perf_counter()
        result_payload, result_batch_count, page_warnings = self._fetch_result_pages(client, job_id)
        trace["resultFetchElapsedMs"] = _elapsed_ms(result_started)
        trace["resultBatchCount"] = result_batch_count
        response_payload = {
            "jobId": job_id,
            "status": status_payload,
            "result": result_payload,
        }
        normalize_started = time.perf_counter()
        blocks, markdown_syntax, warnings = self._result_to_blocks(result_payload)
        warnings = [*page_warnings, *warnings]
        trace["standardizeElapsedMs"] = _elapsed_ms(normalize_started)
        trace.update(_parse_trace_metadata(
            blocks,
            warnings,
            status_payload=status_payload,
            result_payload=result_payload,
            job_id=job_id,
            parser_name=self.provider_name,
            parser_version=self.provider_version,
        ))
        response_payload["traceMetadata"] = trace
        artifacts = [
            _artifact(
                "ALIYUN_DOCMIND_JSON",
                f"{_base_name(request.file_name)}.aliyun-docmind.json",
                "application/json;charset=UTF-8",
                json.dumps(response_payload, ensure_ascii=False, indent=2).encode("utf-8"),
                parser_name=self.provider_name,
                parser_version=self.provider_version,
            )
        ]
        return DocMindParseResult(
            blocks=blocks,
            markdown_syntax=markdown_syntax,
            artifacts=artifacts,
            warnings=warnings,
            metadata=trace,
        )

    def _sdk_available(self) -> bool:
        try:
            from alibabacloud_docmind_api20220711.client import Client as _Client  # noqa: F401
            from alibabacloud_docmind_api20220711 import models as _models  # noqa: F401
            from alibabacloud_tea_openapi import models as _open_api_models  # noqa: F401
            from alibabacloud_tea_util import models as _util_models  # noqa: F401
            return True
        except Exception:
            return False

    def _client(self):
        try:
            from alibabacloud_docmind_api20220711.client import Client as DocMindClient
            from alibabacloud_tea_openapi import models as open_api_models
        except Exception as exception:
            raise HTTPException(status_code=503, detail=self.unavailable_reason()) from exception
        config = open_api_models.Config(
            access_key_id=self._access_key_id(),
            access_key_secret=self._access_key_secret(),
        )
        config.endpoint = self._endpoint()
        return DocMindClient(config)

    def _submit_job(self, client, content: bytes, file_name: str, file_type: str) -> str:
        try:
            from alibabacloud_docmind_api20220711 import models as docmind_models
            from alibabacloud_tea_util import models as util_models
        except Exception as exception:
            raise HTTPException(status_code=503, detail=self.unavailable_reason()) from exception

        safe_file_name = file_name or f"document.{file_type.lower()}"
        with BytesIO(content) as file_stream:
            submit_request = docmind_models.SubmitDocParserJobAdvanceRequest(
                file_url_object=file_stream,
                file_name=safe_file_name,
                file_name_extension=self._file_extension(safe_file_name, file_type),
                formula_enhancement=self._formula_enhancement(),
                llm_enhancement=self._llm_enhancement(),
                enhancement_mode=self._enhancement_mode() if self._llm_enhancement() else None,
                output_format=self._output_formats(),
                output_html_table=self._output_html_table(),
                need_header_footer=self._need_header_footer(),
                page_index=self._page_index() or None,
            )
            try:
                response = client.submit_doc_parser_job_advance(submit_request, self._runtime_options(util_models))
            except Exception as exception:
                raise HTTPException(status_code=503, detail=f"阿里云 Document Mind 提交解析任务失败: {exception}") from exception

        body = _tea_model_to_map(getattr(response, "body", response))
        code = str(body.get("Code") or body.get("code") or "")
        if code and code not in {"200", "OK", "Success", "success"}:
            raise HTTPException(status_code=502, detail=f"阿里云 Document Mind 提交失败: {body.get('Message') or body.get('message') or code}")
        job_id = _deep_get(body, "Data", "Id") or _deep_get(body, "data", "id")
        if not job_id:
            raise HTTPException(status_code=502, detail="阿里云 Document Mind 提交成功但未返回 jobId。")
        return str(job_id)

    def _wait_until_finished(self, client, job_id: str) -> tuple[dict[str, Any], int]:
        try:
            from alibabacloud_docmind_api20220711 import models as docmind_models
        except Exception as exception:
            raise HTTPException(status_code=503, detail=self.unavailable_reason()) from exception

        deadline = time.monotonic() + self._timeout_seconds()
        last_payload: dict[str, Any] = {}
        poll_count = 0
        while time.monotonic() < deadline:
            try:
                response = client.query_doc_parser_status(docmind_models.QueryDocParserStatusRequest(id=job_id))
            except Exception as exception:
                raise HTTPException(status_code=503, detail=f"阿里云 Document Mind 查询解析状态失败: {exception}") from exception
            poll_count += 1
            payload = _tea_model_to_map(getattr(response, "body", response))
            last_payload = payload
            status = str(_deep_get(payload, "Data", "Status") or _deep_get(payload, "data", "status") or "").lower()
            code = str(payload.get("Code") or payload.get("code") or "")
            if code and code not in {"200", "OK", "Success", "success"}:
                raise HTTPException(status_code=502, detail=f"阿里云 Document Mind 状态查询失败: {payload.get('Message') or payload.get('message') or code}")
            if status in {"success", "finished", "finish", "succeeded", "completed", "complete"}:
                return payload, poll_count
            if status in {"fail", "failed", "error"}:
                raise HTTPException(status_code=502, detail=f"阿里云 Document Mind 解析失败: {payload.get('Message') or payload.get('message') or status}")
            time.sleep(self._poll_interval_seconds())
        raise HTTPException(status_code=504, detail=f"阿里云 Document Mind 解析超时: jobId={job_id}, lastStatus={last_payload}")

    def _fetch_result_pages(self, client, job_id: str) -> tuple[dict[str, Any], int, list[str]]:
        try:
            from alibabacloud_docmind_api20220711 import models as docmind_models
        except Exception as exception:
            raise HTTPException(status_code=503, detail=self.unavailable_reason()) from exception

        all_layouts: list[dict[str, Any]] = []
        first_payload: dict[str, Any] | None = None
        step_size = self._layout_step_size()
        result_batch_count = 0
        page_warnings: list[str] = []
        for layout_num in range(0, self._max_result_pages() * step_size, step_size):
            get_request = docmind_models.GetDocParserResultRequest(
                id=job_id,
                layout_num=layout_num,
                layout_step_size=step_size,
            )
            try:
                response = client.get_doc_parser_result(get_request)
            except Exception as exception:
                raise HTTPException(status_code=503, detail=f"阿里云 Document Mind 拉取解析结果失败: {exception}") from exception
            payload = _tea_model_to_map(getattr(response, "body", response))
            if first_payload is None:
                first_payload = payload
            code = str(payload.get("Code") or payload.get("code") or "")
            if code and code not in {"200", "OK", "Success", "success"}:
                raise HTTPException(status_code=502, detail=f"阿里云 Document Mind 结果查询失败: {payload.get('Message') or payload.get('message') or code}")
            data = _docmind_data(payload)
            layouts = _extract_docmind_layouts(data)
            if not layouts:
                page_warnings.append("阿里云 Document Mind 空 layout 窗口，按分页结束处理。")
                break
            result_batch_count += 1
            all_layouts.extend(layouts)
            if len(layouts) < step_size:
                break
        merged = first_payload or {}
        data = _docmind_data(merged)
        if isinstance(data, dict):
            data["layouts"] = all_layouts
            data["Layouts"] = all_layouts
        return merged, result_batch_count, page_warnings

    def _result_to_blocks(self, result_payload: dict[str, Any]) -> tuple[list[DocumentBlock], MarkdownSyntaxDocument | None, list[str]]:
        data = _docmind_data(result_payload)
        markdown = _extract_docmind_markdown(data)
        layouts = _extract_docmind_layouts(data)
        warnings: list[str] = []
        if not layouts and markdown:
            syntax = parse_markdown_syntax(markdown, "PROVIDER_MARKDOWN")
            return markdown_syntax_to_blocks(syntax, self.provider_name), syntax, warnings
        blocks: list[DocumentBlock] = []
        dropped_empty = 0
        for index, layout in enumerate(layouts, start=1):
            block = _docmind_layout_to_block(layout, index)
            if block is not None:
                blocks.append(block)
            else:
                dropped_empty += 1
        if dropped_empty:
            warnings.append(f"阿里云 Document Mind 丢弃 {dropped_empty} 个空 layout 块。")
        if blocks:
            return blocks, None, warnings
        if markdown:
            warnings.append("阿里云 Document Mind 未返回可映射 layout，已使用 markdown 生成基础 block。")
            syntax = parse_markdown_syntax(markdown, "PROVIDER_MARKDOWN")
            return markdown_syntax_to_blocks(syntax, self.provider_name), syntax, warnings
        raise HTTPException(status_code=422, detail="阿里云 Document Mind 未返回可用文本或 layout。")

    def _runtime_options(self, util_models):
        timeout_ms = max(1000, self._timeout_seconds() * 1000)
        return util_models.RuntimeOptions(
            connect_timeout=min(timeout_ms, 30000),
            read_timeout=timeout_ms,
            autoretry=True,
            max_attempts=2,
        )

    def _endpoint(self) -> str:
        return config_value(
            "ragTools.documentParser.aliyunDocMind.endpoint",
            "RAG_TOOLS_ALIYUN_DOCMIND_ENDPOINT",
            "docmind-api.cn-hangzhou.aliyuncs.com",
        ) or "docmind-api.cn-hangzhou.aliyuncs.com"

    def _access_key_id(self) -> str:
        return config_value(
            "ragTools.documentParser.aliyunDocMind.accessKeyId",
            "ALIBABA_CLOUD_ACCESS_KEY_ID",
            "",
        )

    def _access_key_secret(self) -> str:
        return config_value(
            "ragTools.documentParser.aliyunDocMind.accessKeySecret",
            "ALIBABA_CLOUD_ACCESS_KEY_SECRET",
            "",
        )

    def _timeout_seconds(self) -> int:
        return config_int(
            "ragTools.documentParser.aliyunDocMind.timeoutSeconds",
            "RAG_TOOLS_ALIYUN_DOCMIND_TIMEOUT_SECONDS",
            600,
            min_value=30,
            max_value=7200,
        )

    def _poll_interval_seconds(self) -> int:
        return config_int(
            "ragTools.documentParser.aliyunDocMind.pollIntervalSeconds",
            "RAG_TOOLS_ALIYUN_DOCMIND_POLL_INTERVAL_SECONDS",
            3,
            min_value=1,
            max_value=60,
        )

    def _layout_step_size(self) -> int:
        return config_int(
            "ragTools.documentParser.aliyunDocMind.layoutStepSize",
            "RAG_TOOLS_ALIYUN_DOCMIND_LAYOUT_STEP_SIZE",
            100,
            min_value=1,
            max_value=1000,
        )

    def _max_result_pages(self) -> int:
        return config_int(
            "ragTools.documentParser.aliyunDocMind.maxResultPages",
            "RAG_TOOLS_ALIYUN_DOCMIND_MAX_RESULT_PAGES",
            200,
            min_value=1,
            max_value=10000,
        )

    def _llm_enhancement(self) -> bool:
        return _config_bool(
            "ragTools.documentParser.aliyunDocMind.llmEnhancement",
            "RAG_TOOLS_ALIYUN_DOCMIND_LLM_ENHANCEMENT",
            True,
        )

    def _formula_enhancement(self) -> bool:
        return _config_bool(
            "ragTools.documentParser.aliyunDocMind.formulaEnhancement",
            "RAG_TOOLS_ALIYUN_DOCMIND_FORMULA_ENHANCEMENT",
            True,
        )

    def _output_html_table(self) -> bool:
        return _config_bool(
            "ragTools.documentParser.aliyunDocMind.outputHtmlTable",
            "RAG_TOOLS_ALIYUN_DOCMIND_OUTPUT_HTML_TABLE",
            True,
        )

    def _need_header_footer(self) -> bool:
        return _config_bool(
            "ragTools.documentParser.aliyunDocMind.needHeaderFooter",
            "RAG_TOOLS_ALIYUN_DOCMIND_NEED_HEADER_FOOTER",
            False,
        )

    def _enhancement_mode(self) -> str:
        return config_value(
            "ragTools.documentParser.aliyunDocMind.enhancementMode",
            "RAG_TOOLS_ALIYUN_DOCMIND_ENHANCEMENT_MODE",
            "VLM",
        )

    def _output_formats(self) -> list[str]:
        raw = config_value(
            "ragTools.documentParser.aliyunDocMind.outputFormats",
            "RAG_TOOLS_ALIYUN_DOCMIND_OUTPUT_FORMATS",
            "markdown,visualLayoutInfo",
        )
        values = [item.strip() for item in str(raw or "").split(",") if item.strip()]
        return values or ["markdown", "visualLayoutInfo"]

    def _page_index(self) -> str:
        return config_value(
            "ragTools.documentParser.aliyunDocMind.pageIndex",
            "RAG_TOOLS_ALIYUN_DOCMIND_PAGE_INDEX",
            "",
        )

    def _file_extension(self, file_name: str, file_type: str) -> str:
        if "." in (file_name or ""):
            return file_name.rsplit(".", 1)[-1].lower()
        return (file_type or "").lower()


class NativeTextParser:
    provider_name = NATIVE_TEXT_PARSER_NAME
    provider_version = PARSER_VERSION
    supported_file_types = NATIVE_TEXT_FILE_TYPES
    capabilities = NATIVE_TEXT_CAPABILITIES

    def status(self) -> dict[str, Any]:
        return {
            "providerName": self.provider_name,
            "providerVersion": self.provider_version,
            "available": True,
            "failedReason": "",
            "supportedFileTypes": sorted(item.lower() for item in self.supported_file_types),
            "capabilities": list(self.capabilities),
        }

    def parse(self, content: bytes, file_type: str, request: DocumentParseRequest) -> DocMindParseResult:
        if file_type == "MD":
            syntax = parse_markdown_syntax(_decode_text(content), "SOURCE_MARKDOWN")
            blocks = markdown_syntax_to_blocks(syntax, self.provider_name)
            return DocMindParseResult(blocks=blocks, markdown_syntax=syntax)
        elif file_type == "TXT":
            blocks = _parse_plain_text(content, parser_name=self.provider_name)
        elif file_type == "HTML":
            blocks = _parse_html(content)
        else:
            raise HTTPException(status_code=422, detail=f"native_text 当前不支持文件类型: {file_type or 'UNKNOWN'}")
        return DocMindParseResult(blocks=blocks)


class DoclingPdfParser:
    provider_name = DOCLING_PARSER_NAME
    provider_version = PARSER_VERSION
    supported_file_types = {"PDF"}
    capabilities = DOCLING_CAPABILITIES

    def is_available(self) -> bool:
        try:
            import docling.document_converter  # noqa: F401
            return True
        except ImportError:
            return False

    def unavailable_reason(self) -> str:
        if self.is_available():
            return ""
        return "未安装 Docling，请执行 pip install -r requirements-optional.txt。"

    def status(self) -> dict[str, Any]:
        available = self.is_available()
        return {
            "providerName": self.provider_name,
            "providerVersion": self.provider_version,
            "available": available,
            "failedReason": "" if available else self.unavailable_reason(),
            "supportedFileTypes": sorted(item.lower() for item in self.supported_file_types),
            "capabilities": list(self.capabilities),
            "fallbackEnabled": _docling_pdf_fallback_enabled(),
        }

    def parse(self, content: bytes, file_type: str, request: DocumentParseRequest) -> DocMindParseResult:
        if file_type != "PDF":
            raise HTTPException(status_code=422, detail=f"Docling 当前只作本地 PDF 第二解析器，不支持: {file_type or 'UNKNOWN'}")
        if not self.is_available():
            raise HTTPException(status_code=503, detail=self.unavailable_reason())
        markdown = self._export_markdown(content, request.file_name)
        syntax = parse_markdown_syntax(markdown, "SOURCE_MARKDOWN")
        blocks = markdown_syntax_to_blocks(syntax, self.provider_name)
        return DocMindParseResult(
            blocks=blocks,
            markdown_syntax=syntax,
            metadata={"localPdfParser": DOCLING_PARSER_NAME},
        )

    def _export_markdown(self, content: bytes, file_name: str) -> str:
        import tempfile
        from pathlib import Path

        from docling.document_converter import DocumentConverter

        suffix = ".pdf"
        if file_name and "." in file_name:
            suffix = "." + file_name.rsplit(".", 1)[-1]
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=True) as handle:
            handle.write(content or b"")
            handle.flush()
            result = DocumentConverter().convert(Path(handle.name))
        document = getattr(result, "document", None)
        if document is None or not hasattr(document, "export_to_markdown"):
            raise HTTPException(status_code=502, detail="Docling 未返回可导出的文档。")
        markdown = document.export_to_markdown() or ""
        if not markdown.strip():
            raise HTTPException(status_code=422, detail="Docling 解析结果为空。")
        return markdown


def parse_document(request: DocumentParseRequest) -> DocumentParseResponse:
    started = time.perf_counter()
    content = _decode_content(request.content_base64)
    file_type = _resolve_file_type(request.file_name, request.file_type, request.mime_type)

    if file_type == "XLS":
        raise HTTPException(status_code=422, detail="XLS 解析未启用，请先转为 XLSX 后上传。")
    if file_type == "DOC":
        raise HTTPException(status_code=422, detail="DOC 解析未启用，请先转为 DOCX 后上传。")
    parser = _parser_for_file_type(file_type)
    if parser is None:
        raise HTTPException(status_code=422, detail=f"不支持的文件类型: {file_type or 'UNKNOWN'}")

    parser_result = parser.parse(content, file_type, request)
    markdown_syntax = parser_result.markdown_syntax
    normalized_blocks = _normalize_blocks(parser_result.blocks)
    _stamp_parser_metadata(normalized_blocks, parser)
    _apply_table_context(normalized_blocks)
    parsed_text = _render_parsed_text(normalized_blocks)
    paragraph_list = _paragraphs(parsed_text)
    heading_count = sum(1 for block in normalized_blocks if block.block_type == "TITLE")
    trace_metadata = _parse_trace_metadata(
        normalized_blocks,
        parser_result.warnings,
        base_metadata=parser_result.metadata,
        parser_name=parser.provider_name,
        parser_version=parser.provider_version,
        elapsed_ms=int((time.perf_counter() - started) * 1000),
    )
    if markdown_syntax is not None:
        trace_metadata.update({
            "markdownSyntaxSchemaVersion": markdown_syntax.schema_version,
            "markdownSyntaxNodeCount": len(markdown_syntax.nodes),
            "markdownSourceOrigin": markdown_syntax.source_origin,
            "markdownSourceSha256": markdown_syntax.source_sha256,
        })
    warning_list = list(parser_result.warnings or [])
    artifacts = _build_artifacts(
        request.file_name,
        parsed_text,
        normalized_blocks,
        parser,
        content,
        file_type,
        parser_result.artifacts,
        trace_metadata,
        warning_list,
    )
    if warning_list != list(parser_result.warnings or []):
        trace_metadata = _parse_trace_metadata(
            normalized_blocks,
            warning_list,
            base_metadata=trace_metadata,
            parser_name=parser.provider_name,
            parser_version=parser.provider_version,
            elapsed_ms=int((time.perf_counter() - started) * 1000),
        )
    capabilities = _response_capabilities(parser, artifacts, markdown_syntax)

    return DocumentParseResponse(
        parsedText=parsed_text,
        charCount=len(parsed_text),
        tokenCount=_estimate_token_count(parsed_text),
        structureLevel=_structure_level(heading_count, len(paragraph_list)),
        contentQualityLevel=_content_quality_level(parsed_text),
        headingCount=heading_count,
        paragraphCount=len(paragraph_list),
        maxParagraphLength=max((len(item) for item in paragraph_list), default=0),
        artifacts=artifacts,
        blocks=normalized_blocks,
        providerName=parser.provider_name,
        providerVersion=parser.provider_version,
        capabilities=capabilities,
        elapsedMs=trace_metadata.get("elapsedMs", int((time.perf_counter() - started) * 1000)),
        warnings=warning_list,
        failedReason="",
        traceMetadata=trace_metadata,
        markdownSyntax=markdown_syntax,
    )


def document_parser_status() -> dict[str, Any]:
    native_parser = _native_text_parser()
    docmind_parser = _parser()
    docling_parser = _docling_parser()
    return {
        "defaultProvider": "type_routed",
        "routes": [
            {
                "providerName": native_parser.provider_name,
                "fileTypes": sorted(item.lower() for item in native_parser.supported_file_types),
                "description": "TXT、Markdown、HTML 使用本地确定性结构解析，不依赖 OCR 或云端 Document Mind。",
            },
            {
                "providerName": docmind_parser.provider_name,
                "fileTypes": sorted(item.lower() for item in docmind_parser.supported_file_types),
                "description": "PDF、DOCX、XLSX、PNG、JPG/JPEG、BMP、GIF 使用阿里云 Document Mind 处理 OCR、layout、reading order、表格和图片结构。",
            },
            {
                "providerName": docling_parser.provider_name,
                "fileTypes": ["pdf"],
                "description": "仅当开启 SMARTLEDGE_DOCLING_PDF_FALLBACK 且 Document Mind 不可用时，本地 PDF 才走 Docling。",
            },
        ],
        "providers": [native_parser.status(), docmind_parser.status(), docling_parser.status()],
    }


def _parser() -> AliyunDocMindParser:
    return AliyunDocMindParser()


def _native_text_parser() -> NativeTextParser:
    return NativeTextParser()


def _docling_parser() -> DoclingPdfParser:
    return DoclingPdfParser()


def _docling_pdf_fallback_enabled() -> bool:
    value = config_value(
        "ragTools.documentParser.docling.pdfFallback",
        "SMARTLEDGE_DOCLING_PDF_FALLBACK",
        "false",
    )
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _parser_for_file_type(file_type: str):
    if file_type in NATIVE_TEXT_FILE_TYPES:
        return _native_text_parser()
    if file_type in ALIYUN_DOCMIND_FILE_TYPES:
        docmind = _parser()
        if (
            file_type == "PDF"
            and not docmind.is_available()
            and _docling_pdf_fallback_enabled()
            and _docling_parser().is_available()
        ):
            return _docling_parser()
        return docmind
    return None


def _decode_content(content_base64: str) -> bytes:
    if not content_base64:
        raise HTTPException(status_code=422, detail="contentBase64 不能为空。")
    try:
        return base64.b64decode(content_base64, validate=True)
    except Exception as exception:
        raise HTTPException(status_code=422, detail="contentBase64 不是合法 Base64。") from exception


def _resolve_file_type(file_name: str, file_type: str, mime_type: str) -> str:
    explicit = (file_type or "").strip().upper()
    if explicit:
        return explicit
    suffix = (file_name or "").rsplit(".", 1)[-1].lower() if "." in (file_name or "") else ""
    if suffix == "pdf":
        return "PDF"
    if suffix == "docx":
        return "DOCX"
    if suffix == "doc":
        return "DOC"
    if suffix == "xlsx":
        return "XLSX"
    if suffix == "xls":
        return "XLS"
    if suffix in {"png", "jpg", "jpeg", "bmp", "gif"}:
        return suffix.upper()
    if suffix in {"md", "markdown"}:
        return "MD"
    if suffix in {"html", "htm"}:
        return "HTML"
    if suffix == "txt" or (mime_type or "").startswith("text/"):
        return "TXT"
    return explicit


def _config_bool(path: str, env_name: str, default: bool) -> bool:
    raw = config_value(path, env_name, "true" if default else "false")
    return str(raw or "").strip().lower() in {"1", "true", "yes", "on"}


def _tea_model_to_map(value: Any) -> dict[str, Any]:
    if value is None:
        return {}
    if isinstance(value, dict):
        return value
    if hasattr(value, "to_map"):
        mapped = value.to_map()
        return mapped if isinstance(mapped, dict) else {}
    return {}


def _deep_get(value: Any, *path: str) -> Any:
    current = value
    for part in path:
        if not isinstance(current, dict):
            return None
        current = current.get(part)
    return current


def _docmind_data(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {}
    data = payload.get("Data") if "Data" in payload else payload.get("data")
    if isinstance(data, dict):
        return data
    return payload


def _extract_docmind_layouts(payload: Any) -> list[dict[str, Any]]:
    data = _docmind_data(payload)
    for key in ("layouts", "Layouts", "layout", "Layout"):
        layouts = data.get(key)
        if isinstance(layouts, list):
            return [item for item in layouts if isinstance(item, dict)]
    output_format_result = data.get("OutputFormatResult") or data.get("outputFormatResult")
    if isinstance(output_format_result, list):
        for item in output_format_result:
            if not isinstance(item, dict):
                continue
            layouts = item.get("layouts") or item.get("Layouts") or item.get("layout") or item.get("Layout")
            if isinstance(layouts, list):
                return [layout for layout in layouts if isinstance(layout, dict)]
    return []


def _extract_docmind_markdown(payload: Any) -> str:
    data = _docmind_data(payload)
    for key in ("markdownContent", "MarkdownContent", "markdown", "Markdown", "mdContent", "MdContent"):
        text = _cleanup_text(str(data.get(key) or ""))
        if text:
            return text
    output_format_result = data.get("OutputFormatResult") or data.get("outputFormatResult")
    if isinstance(output_format_result, list):
        parts = []
        for item in output_format_result:
            if not isinstance(item, dict):
                continue
            for key in ("markdownContent", "MarkdownContent", "markdown", "Markdown", "content", "Content"):
                text = _cleanup_text(str(item.get(key) or ""))
                if text:
                    parts.append(text)
                    break
        if parts:
            return "\n\n".join(parts)
    return ""


def _docmind_layout_to_block(layout: dict[str, Any], block_no: int) -> DocumentBlock | None:
    text = _docmind_layout_text(layout)
    layout_type = _cleanup_text(str(_first_present(layout, "type", "Type", "subType", "SubType", "layoutType", "LayoutType") or "text")).lower()
    block_type = _docmind_block_type(layout_type, text)
    table_html = _docmind_table_html(layout)
    table_rows = _table_rows_from_html(table_html) if table_html else _docmind_table_rows(layout)
    if block_type == "TABLE" and table_rows and not table_html:
        table_html = _table_html(table_rows)
    if block_type == "TABLE" and not text and table_rows:
        text = _table_text(table_rows)
    if block_type in {"IMAGE", "FIGURE"} and not text:
        text = _cleanup_text(str(_first_present(layout, "caption", "Caption", "imageCaption", "ImageCaption") or ""))
    if not text and not table_html and not table_rows:
        return None
    page_no = _docmind_page_no(layout)
    bbox = _docmind_bbox(layout)
    metadata = {
        "parser": ALIYUN_DOCMIND_PARSER_NAME,
        "layoutType": layout_type or block_type.lower(),
        "docmindIndex": _first_present(layout, "index", "Index"),
        "docmindSubtype": _first_present(layout, "subType", "SubType"),
        "layoutConfidence": _float_or_none(_first_present(layout, "layoutConf", "LayoutConf", "confidence", "Confidence")),
        "docmindRawType": _first_present(layout, "type", "Type"),
    }
    if bbox:
        metadata["bboxSource"] = _docmind_bbox_source(layout) or "unknown"
    table_cell_metadata = _docmind_table_cell_metadata(layout, table_rows, page_no)
    if table_cell_metadata:
        metadata["tableCellMetadata"] = table_cell_metadata
    image_caption = text if block_type in {"IMAGE", "FIGURE"} else ""
    return _block(
        block_no,
        block_type,
        text,
        page_no=page_no,
        bbox_json=_bbox_json(bbox) if bbox else "",
        table_html=table_html,
        table_rows=table_rows,
        image_caption=image_caption,
        metadata={key: value for key, value in metadata.items() if value not in (None, "")},
    )


def _docmind_layout_text(layout: dict[str, Any]) -> str:
    for key in (
        "text",
        "Text",
        "markdownContent",
        "MarkdownContent",
        "content",
        "Content",
        "llmResult",
        "LlmResult",
    ):
        value = layout.get(key)
        if isinstance(value, str) and _cleanup_text(value):
            if key.lower() == "llmresult" and "<table" in value.lower():
                continue
            return _cleanup_text(value)
    return ""


def _docmind_table_html(layout: dict[str, Any]) -> str:
    for key in ("tableHtml", "TableHtml", "html", "Html", "llmResult", "LlmResult"):
        value = layout.get(key)
        if isinstance(value, str) and "<table" in value.lower():
            return value
    return ""


def _docmind_table_rows(layout: dict[str, Any]) -> list[list[str]]:
    for key in ("tableRows", "TableRows", "cells", "Cells"):
        value = layout.get(key)
        rows = _docmind_rows_from_value(value)
        if rows:
            return rows
    return []


def _docmind_rows_from_value(value: Any) -> list[list[str]]:
    if not isinstance(value, list):
        return []
    if value and all(isinstance(row, list) for row in value):
        return _normalize_table_rows(value)
    if value and all(isinstance(item, dict) for item in value):
        placements: list[tuple[int, int, int, int, str]] = []
        max_row = 0
        max_col = 0
        for item in value:
            row_no = _to_int(item.get("rowIndex") or item.get("row") or item.get("rowNo")) or 0
            col_no = _to_int(item.get("colIndex") or item.get("column") or item.get("columnNo") or item.get("col")) or 0
            if row_no <= 0 or col_no <= 0:
                continue
            row_span = max(1, _to_int(item.get("rowSpan") or item.get("rowspan")) or 1)
            col_span = max(1, _to_int(item.get("colSpan") or item.get("colspan") or item.get("columnSpan")) or 1)
            text = _cleanup_text(str(item.get("text") or item.get("value") or item.get("content") or ""))
            placements.append((row_no, col_no, row_span, col_span, text))
            max_row = max(max_row, row_no + row_span - 1)
            max_col = max(max_col, col_no + col_span - 1)
        if max_row <= 0 or max_col <= 0:
            return []
        rows = [["" for _ in range(max_col)] for _ in range(max_row)]
        for row_no, col_no, row_span, col_span, text in placements:
            for row_offset in range(row_span):
                for col_offset in range(col_span):
                    target = rows[row_no - 1 + row_offset][col_no - 1 + col_offset]
                    if not target or (row_offset == 0 and col_offset == 0):
                        rows[row_no - 1 + row_offset][col_no - 1 + col_offset] = text
        return _trim_table_rows(rows)
    return []


def _docmind_table_cell_metadata(layout: dict[str, Any], rows: list[list[str]], page_no: int | None) -> list[dict[str, Any]]:
    cells = layout.get("cells") or layout.get("Cells") or layout.get("tableCells") or layout.get("TableCells")
    metadata: list[dict[str, Any]] = []
    if isinstance(cells, list) and all(isinstance(item, dict) for item in cells):
        for cell in cells:
            row_no = _to_int(cell.get("rowIndex") or cell.get("row") or cell.get("rowNo")) or 0
            column_no = _to_int(cell.get("colIndex") or cell.get("column") or cell.get("columnNo") or cell.get("col")) or 0
            if row_no <= 0 or column_no <= 0:
                continue
            item = {
                "rowNo": row_no,
                "columnNo": column_no,
                "sourceRowNo": row_no,
                "sourceColumnNo": column_no,
                "cellCoordinate": f"R{row_no}C{column_no}",
                "pageNo": page_no,
                "value": _cleanup_text(str(cell.get("text") or cell.get("value") or cell.get("content") or "")),
                "rowSpan": _to_int(cell.get("rowSpan") or cell.get("rowspan")),
                "columnSpan": _to_int(cell.get("colSpan") or cell.get("colspan") or cell.get("columnSpan")),
            }
            bbox = _docmind_bbox(cell)
            if bbox:
                item["bboxJson"] = _bbox_json(bbox)
                item["bboxSource"] = _docmind_bbox_source(cell) or "unknown"
            metadata.append({key: value for key, value in item.items() if value not in (None, "", 0)})
    if metadata:
        return metadata
    for row_index, row in enumerate(rows or [], start=1):
        for column_index, cell_text in enumerate(row, start=1):
            metadata.append({
                "rowNo": row_index,
                "columnNo": column_index,
                "sourceRowNo": row_index,
                "sourceColumnNo": column_index,
                "cellCoordinate": f"R{row_index}C{column_index}",
                "pageNo": page_no,
                "value": cell_text,
            })
    return metadata


def _docmind_page_no(layout: dict[str, Any]) -> int | None:
    value = _first_present(layout, "pageNum", "PageNum", "pageNo", "PageNo", "page", "Page")
    return _to_int(value)


def _docmind_bbox(layout: dict[str, Any]) -> tuple[float, float, float, float] | None:
    for key in (
        "pos",
        "Pos",
        "bbox",
        "Bbox",
        "BBox",
        "box",
        "Box",
        "rect",
        "Rect",
        "region",
        "Region",
        "position",
        "Position",
        "coordinates",
        "Coordinates",
        "BoundingBox",
        "boundingBox",
    ):
        bbox = _coerce_bbox(layout.get(key))
        if bbox:
            return bbox
    bbox = _coerce_bbox(layout)
    if bbox:
        return bbox
    for key in ("polygon", "Polygon", "points", "Points", "quad", "Quad"):
        bbox = _coerce_polygon_bbox(layout.get(key))
        if bbox:
            return bbox
    return None


def _docmind_bbox_source(layout: dict[str, Any]) -> str:
    for key in (
        "pos",
        "Pos",
        "bbox",
        "Bbox",
        "BBox",
        "box",
        "Box",
        "rect",
        "Rect",
        "region",
        "Region",
        "position",
        "Position",
        "coordinates",
        "Coordinates",
        "BoundingBox",
        "boundingBox",
    ):
        if _coerce_bbox(layout.get(key)):
            return key
    if _coerce_bbox(layout):
        return "inline"
    for key in ("polygon", "Polygon", "points", "Points", "quad", "Quad"):
        if _coerce_polygon_bbox(layout.get(key)):
            return key
    return ""


def _docmind_block_type(layout_type: str, text: str) -> str:
    normalized = (layout_type or "").lower()
    if any(item in normalized for item in ("title", "heading", "header1", "header2")):
        return "TITLE"
    if "table" in normalized:
        return "TABLE"
    if any(item in normalized for item in ("figure", "chart")):
        return "FIGURE"
    if "image" in normalized or "picture" in normalized:
        return "IMAGE"
    if "formula" in normalized or "equation" in normalized:
        return "FORMULA"
    if "code" in normalized:
        return "CODE"
    if "footer" in normalized:
        return "FOOTER"
    if "header" in normalized:
        return "HEADER"
    return _classify_text_block(text)


def _first_present(value: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in value and value.get(key) is not None:
            return value.get(key)
    return None


def _float_or_none(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _parse_plain_text(content: bytes, parser_name: str = NATIVE_TEXT_PARSER_NAME) -> list[DocumentBlock]:
    text = _decode_text(content)
    blocks = []
    block_no = 1
    for paragraph in _paragraphs(text):
        block_type = _classify_text_block(paragraph)
        clean_text = re.sub(r"^#{1,6}\s+", "", paragraph).strip()
        blocks.append(_block(block_no, block_type, clean_text, metadata={"parser": parser_name}))
        block_no += 1
    return blocks


def _parse_html(content: bytes) -> list[DocumentBlock]:
    parser = _HtmlBlockParser()
    parser.feed(_decode_text(content))
    parser.close()
    blocks: list[DocumentBlock] = []
    for block_type, text, table_html, table_rows in parser.blocks:
        if not text.strip() and not table_html:
            continue
        blocks.append(_block(
            len(blocks) + 1,
            block_type,
            text,
            table_html=table_html,
            table_rows=table_rows,
            metadata={"parser": NATIVE_TEXT_PARSER_NAME},
        ))
    return blocks


def _normalize_blocks(blocks: list[DocumentBlock]) -> list[DocumentBlock]:
    normalized = []
    current_section = ""
    current_section_path = ""
    for block in blocks:
        text = _cleanup_text(block.text)
        if not text and not block.table_html and not block.image_caption:
            continue
        block.block_no = len(normalized) + 1
        block.text = text
        if block.block_type == "TITLE":
            current_section = text
            current_section_path = text
        block.section_path = block.section_path or current_section_path
        block.canonical_path = block.canonical_path or _canonical_path(block.section_path, block.block_no)
        block.content_with_weight = block.content_with_weight or _content_with_weight(block, current_section)
        normalized.append(block)
    return normalized


def _apply_table_context(blocks: list[DocumentBlock]) -> None:
    for index, block in enumerate(blocks):
        if block.block_type != "TABLE":
            continue
        before = _nearby_context(blocks, index, -1)
        after = _nearby_context(blocks, index, 1)
        context_parts = []
        if block.section_path:
            context_parts.append(f"section: {block.section_path}")
        if before:
            context_parts.append(f"before: {before}")
        if after:
            context_parts.append(f"after: {after}")
        context_text = "\n".join(context_parts)
        if not context_text:
            continue
        _merge_block_metadata(block, {
            "tableContext": {
                "sectionPath": block.section_path,
                "before": before,
                "after": after,
            },
            "tableContextText": context_text,
        })
        if context_text not in block.content_with_weight:
            block.content_with_weight = "\n".join(part for part in [
                block.content_with_weight,
                "tableContext:",
                context_text,
            ] if part)


def _nearby_context(blocks: list[DocumentBlock], table_index: int, direction: int) -> str:
    items: list[str] = []
    index = table_index + direction
    while 0 <= index < len(blocks) and len(items) < 2:
        block = blocks[index]
        if block.block_type == "TABLE":
            break
        text = _cleanup_text(block.image_caption if block.block_type in {"IMAGE", "FIGURE"} else block.text)
        if text:
            items.append(_clip_text(text, 240))
        index += direction
    if direction < 0:
        items.reverse()
    return " / ".join(items)


def _merge_block_metadata(block: DocumentBlock, extra: dict) -> None:
    metadata = _read_metadata(block.metadata_json)
    metadata.update(extra)
    block.metadata_json = json_metadata(metadata)


def _stamp_parser_metadata(blocks: list[DocumentBlock], parser) -> None:
    for index, block in enumerate(blocks, start=1):
        _merge_block_metadata(block, {
            "providerName": parser.provider_name,
            "providerVersion": parser.provider_version,
            "providerCapabilities": parser.capabilities,
            "readingOrder": index,
        })


def _response_capabilities(parser,
                           artifacts: list[ParseArtifact],
                           markdown_syntax: MarkdownSyntaxDocument | None = None) -> list[str]:
    capabilities = list(parser.capabilities)
    artifact_types = {artifact.artifact_type for artifact in artifacts or []}
    if "ALIYUN_DOCMIND_JSON" in artifact_types:
        capabilities.extend(["aliyun-docmind", "cloud-document-parser"])
    if markdown_syntax is not None:
        capabilities.append("markdown-syntax-v1")
    return list(dict.fromkeys(capabilities))


def _read_metadata(metadata_json: str) -> dict[str, Any]:
    if not metadata_json:
        return {}
    try:
        metadata = json.loads(metadata_json)
        if isinstance(metadata, dict):
            return metadata
        return {"parserMetadata": metadata}
    except Exception:
        return {"parserMetadata": metadata_json}


def _read_json_object(json_text: str) -> dict[str, Any]:
    if not json_text:
        return {}
    try:
        value = json.loads(json_text)
        return value if isinstance(value, dict) else {}
    except Exception:
        return {}


def _clip_text(text: str, max_length: int) -> str:
    cleaned = _cleanup_text(text)
    if len(cleaned) <= max_length:
        return cleaned
    return cleaned[:max_length].rstrip() + "..."


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


def _parse_trace_metadata(blocks: list[DocumentBlock],
                          warnings: list[str] | None,
                          *,
                          base_metadata: dict[str, Any] | None = None,
                          status_payload: dict[str, Any] | None = None,
                          result_payload: dict[str, Any] | None = None,
                          job_id: str = "",
                          parser_name: str = "",
                          parser_version: str = "",
                          elapsed_ms: int | None = None) -> dict[str, Any]:
    trace: dict[str, Any] = dict(base_metadata or {})
    if parser_name:
        trace.setdefault("providerName", parser_name)
    if parser_version:
        trace.setdefault("providerVersion", parser_version)
    if job_id:
        trace.setdefault("jobId", job_id)
    if elapsed_ms is not None:
        trace["elapsedMs"] = elapsed_ms

    block_type_counts: dict[str, int] = {}
    page_numbers: set[int] = set()
    bbox_block_count = 0
    table_cell_count = 0
    table_cell_bbox_count = 0
    caption_count = 0
    for block in blocks or []:
        block_type = (block.block_type or "UNKNOWN").upper()
        block_type_counts[block_type] = block_type_counts.get(block_type, 0) + 1
        if block.page_no is not None:
            page_numbers.add(block.page_no)
        if block.bbox_json:
            bbox_block_count += 1
        if block.image_caption:
            caption_count += 1
        metadata = _read_metadata(block.metadata_json)
        table_cell_metadata = metadata.get("tableCellMetadata")
        if isinstance(table_cell_metadata, list):
            table_cell_count += len([item for item in table_cell_metadata if isinstance(item, dict)])
            table_cell_bbox_count += len([
                item for item in table_cell_metadata
                if isinstance(item, dict) and item.get("bboxJson")
            ])

    raw_layout_count = _to_int(trace.get("rawLayoutCount")) or 0
    if result_payload is not None:
        raw_layout_count = len(_extract_docmind_layouts(result_payload))

    status_page_count = _to_int(_deep_get(status_payload or {}, "Data", "PageCountEstimate")
                                or _deep_get(status_payload or {}, "data", "pageCountEstimate"))
    page_count = _page_count(page_numbers, status_page_count)

    trace.update({
        "pageCount": page_count,
        "pageNumbers": sorted(page_numbers),
        "ocrPageCount": _ocr_page_count(page_numbers, blocks),
        "blockCount": len(blocks or []),
        "rawLayoutCount": raw_layout_count,
        "blockTypeCounts": block_type_counts,
        "tableCount": block_type_counts.get("TABLE", 0),
        "figureCount": block_type_counts.get("FIGURE", 0) + block_type_counts.get("IMAGE", 0),
        "captionCount": caption_count,
        "bboxBlockCount": bbox_block_count,
        "bboxBlockCoverage": _ratio(bbox_block_count, len(blocks or [])),
        "tableCellCount": table_cell_count,
        "tableCellBboxCount": table_cell_bbox_count,
        "tableCellBboxCoverage": _ratio(table_cell_bbox_count, table_cell_count),
        "warningCount": len(warnings or []),
    })
    if warnings:
        trace["warnings"] = warnings
    if status_page_count is not None:
        trace["pageCountEstimate"] = status_page_count
    return {key: value for key, value in trace.items() if value not in (None, "")}


def _page_count(page_numbers: set[int], fallback: int | None = None) -> int:
    if page_numbers:
        min_page = min(page_numbers)
        max_page = max(page_numbers)
        if min_page == 0:
            return max_page + 1
        return max_page
    return fallback or 0


def _ocr_page_count(page_numbers: set[int], blocks: list[DocumentBlock] | None) -> int:
    pages_with_content = {
        block.page_no
        for block in (blocks or [])
        if block.page_no is not None and (block.text or block.table_rows or block.image_caption)
    }
    return len(pages_with_content or page_numbers)


def _ratio(numerator: int, denominator: int) -> float:
    if denominator <= 0:
        return 0.0
    return round(float(numerator) / float(denominator), 4)


def _build_artifacts(file_name: str,
                     parsed_text: str,
                     blocks: list[DocumentBlock],
                     parser,
                     source_content: bytes | None = None,
                     file_type: str = "",
                     parser_artifacts: list[ParseArtifact] | None = None,
                     trace_metadata: dict[str, Any] | None = None,
                     warnings: list[str] | None = None) -> list[ParseArtifact]:
    base_name = _base_name(file_name)
    markdown_bytes = parsed_text.encode("utf-8")
    blocks_payload = {
        "providerName": parser.provider_name,
        "providerVersion": parser.provider_version,
        "capabilities": parser.capabilities,
        "blockCount": len(blocks),
        "traceMetadata": trace_metadata or {},
        "blocks": [block.model_dump(by_alias=True) for block in blocks],
    }
    blocks_json_bytes = json.dumps(blocks_payload, ensure_ascii=False, indent=2).encode("utf-8")
    artifacts = [
        _artifact(
            "MARKDOWN",
            f"{base_name}.md",
            "text/markdown;charset=UTF-8",
            markdown_bytes,
            parser_name=parser.provider_name,
            parser_version=parser.provider_version,
        ),
        _artifact(
            "JSON",
            f"{base_name}.blocks.json",
            "application/json;charset=UTF-8",
            blocks_json_bytes,
            parser_name=parser.provider_name,
            parser_version=parser.provider_version,
        ),
    ]
    layout_payload = _layout_payload(blocks, parser, trace_metadata)
    if layout_payload["blocks"]:
        artifacts.append(_artifact(
            "LAYOUT_JSON",
            f"{base_name}.layout.json",
            "application/json;charset=UTF-8",
            json.dumps(layout_payload, ensure_ascii=False, indent=2).encode("utf-8"),
            parser_name=parser.provider_name,
            parser_version=parser.provider_version,
        ))
    artifacts.extend(parser_artifacts or [])
    artifacts.extend(_build_image_artifacts(
        file_name,
        source_content or b"",
        file_type,
        blocks,
        parser,
        warnings,
    ))
    return artifacts


def _artifact(artifact_type: str,
              file_name: str,
              content_type: str,
              content: bytes,
              *,
              parser_name: str = ALIYUN_DOCMIND_PARSER_NAME,
              parser_version: str = PARSER_VERSION) -> ParseArtifact:
    return ParseArtifact(
        artifactType=artifact_type,
        fileName=file_name,
        contentType=content_type,
        contentBase64=base64.b64encode(content).decode("ascii"),
        contentHash=hashlib.sha256(content).hexdigest(),
        parserName=parser_name,
        parserVersion=parser_version,
    )


def _layout_payload(blocks: list[DocumentBlock], parser, trace_metadata: dict[str, Any] | None = None) -> dict[str, Any]:
    if parser.provider_name == NATIVE_TEXT_PARSER_NAME:
        return {
            "providerName": parser.provider_name,
            "providerVersion": parser.provider_version,
            "pageCount": 0,
            "blockCount": 0,
            "traceMetadata": trace_metadata or {},
            "blocks": [],
        }
    layout_blocks = []
    page_numbers = set()
    for block in blocks:
        metadata = _read_metadata(block.metadata_json)
        layout_type = metadata.get("layoutType") or block.block_type.lower()
        if block.page_no is not None:
            page_numbers.add(block.page_no)
        if not block.bbox_json and block.page_no is None and not layout_type:
            continue
        layout_blocks.append({
            "blockNo": block.block_no,
            "blockType": block.block_type,
            "layoutType": layout_type,
            "pageNo": block.page_no,
            "pageRange": block.page_range,
            "bbox": _read_json_object(block.bbox_json),
            "confidence": metadata.get("layoutConfidence"),
            "readingOrder": metadata.get("readingOrder"),
            "columnIndex": metadata.get("columnIndex"),
            "relatedBlockIds": metadata.get("relatedBlockIds"),
            "text": _clip_text(block.image_caption or block.text, 500),
        })
    return {
        "providerName": parser.provider_name,
        "providerVersion": parser.provider_version,
        "pageCount": _page_count(page_numbers),
        "blockCount": len(layout_blocks),
        "traceMetadata": trace_metadata or {},
        "blocks": layout_blocks,
    }


def _build_image_artifacts(file_name: str,
                           content: bytes,
                           file_type: str,
                           blocks: list[DocumentBlock],
                           parser,
                           warnings: list[str] | None = None) -> list[ParseArtifact]:
    if parser.provider_name != ALIYUN_DOCMIND_PARSER_NAME or not content:
        return []
    page_images = _render_page_images(content, file_type, blocks, warnings)
    if not page_images:
        return []

    base_name = _base_name(file_name)
    zero_based_page_numbers = _uses_zero_based_page_numbers(_artifact_page_numbers(blocks))
    artifacts: list[ParseArtifact] = []
    for page_no in sorted(page_images):
        image_bytes, _, _ = page_images[page_no]
        artifacts.append(_artifact(
            "PAGE_IMAGE",
            f"{base_name}.page-{_display_page_no(page_no, zero_based_page_numbers)}.png",
            "image/png",
            image_bytes,
            parser_name=parser.provider_name,
            parser_version=parser.provider_version,
        ))

    for block in blocks or []:
        block_type = (block.block_type or "").upper()
        if block_type not in {"TABLE", "FIGURE", "IMAGE"} or block.page_no is None or not block.bbox_json:
            continue
        page_image = page_images.get(block.page_no)
        if page_image is None:
            continue
        crop_bytes = _crop_page_image(page_image, _read_json_object(block.bbox_json))
        if not crop_bytes:
            continue
        artifact_type = "TABLE_IMAGE" if block_type == "TABLE" else "FIGURE_IMAGE"
        suffix = "table" if artifact_type == "TABLE_IMAGE" else "figure"
        artifacts.append(_artifact(
            artifact_type,
            f"{base_name}.page-{_display_page_no(block.page_no, zero_based_page_numbers)}.block-{block.block_no}.{suffix}.png",
            "image/png",
            crop_bytes,
            parser_name=parser.provider_name,
            parser_version=parser.provider_version,
        ))
    return artifacts


def _render_page_images(content: bytes,
                        file_type: str,
                        blocks: list[DocumentBlock],
                        warnings: list[str] | None = None) -> dict[int, tuple[bytes, int, int]]:
    normalized_type = (file_type or "").upper()
    if normalized_type == "PDF":
        return _render_pdf_page_images(content, blocks, warnings)
    if normalized_type in {"PNG", "JPG", "JPEG", "BMP", "GIF"}:
        return _render_input_image_page(content, blocks, warnings)
    return {}


def _render_pdf_page_images(content: bytes,
                            blocks: list[DocumentBlock],
                            warnings: list[str] | None = None) -> dict[int, tuple[bytes, int, int]]:
    try:
        import fitz
    except Exception as exception:
        _append_warning(warnings, f"PAGE_IMAGE 生成跳过：缺少 PyMuPDF，{exception}")
        return {}

    page_numbers = _artifact_page_numbers(blocks)
    if not page_numbers:
        page_numbers = {1}
    zero_based_page_numbers = _uses_zero_based_page_numbers(page_numbers)
    rendered: dict[int, tuple[bytes, int, int]] = {}
    document = None
    try:
        document = fitz.open(stream=content, filetype="pdf")
        for page_no in sorted(page_numbers):
            page_index = _pdf_page_index(page_no, zero_based_page_numbers)
            if page_index >= document.page_count:
                _append_warning(warnings, f"PAGE_IMAGE 生成跳过页 {page_no}：超出 PDF 页数 {document.page_count}")
                continue
            page = document.load_page(page_index)
            scale = _pdf_render_scale(page.rect.width, page.rect.height, _page_bboxes(blocks, page_no))
            pixmap = page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
            rendered[page_no] = (pixmap.tobytes("png"), pixmap.width, pixmap.height)
    except Exception as exception:
        _append_warning(warnings, f"PAGE_IMAGE 生成失败：{exception}")
        return rendered
    finally:
        if document is not None:
            document.close()
    return rendered


def _render_input_image_page(content: bytes,
                             blocks: list[DocumentBlock],
                             warnings: list[str] | None = None) -> dict[int, tuple[bytes, int, int]]:
    try:
        from PIL import Image
    except Exception as exception:
        _append_warning(warnings, f"PAGE_IMAGE 生成跳过：缺少 Pillow，{exception}")
        return {}
    try:
        image = Image.open(BytesIO(content))
        image.load()
        if getattr(image, "is_animated", False):
            image.seek(0)
        rgb_image = image.convert("RGB")
        output = BytesIO()
        rgb_image.save(output, format="PNG")
        page_no = min(_artifact_page_numbers(blocks) or {1})
        return {page_no: (output.getvalue(), rgb_image.width, rgb_image.height)}
    except Exception as exception:
        _append_warning(warnings, f"PAGE_IMAGE 生成失败：{exception}")
        return {}


def _crop_page_image(page_image: tuple[bytes, int, int], bbox: dict[str, Any]) -> bytes:
    try:
        from PIL import Image
    except Exception:
        return b""
    image_bytes, width, height = page_image
    crop_box = _image_crop_box(bbox, width, height)
    if crop_box is None:
        return b""
    try:
        image = Image.open(BytesIO(image_bytes))
        cropped = image.crop(crop_box)
        output = BytesIO()
        cropped.save(output, format="PNG")
        return output.getvalue()
    except Exception:
        return b""


def _image_crop_box(bbox: dict[str, Any], width: int, height: int) -> tuple[int, int, int, int] | None:
    raw_bbox = _coerce_bbox(bbox)
    if not raw_bbox:
        return None
    x0, y0, x1, y1 = raw_bbox
    if width <= 0 or height <= 0:
        return None
    max_coordinate = max(abs(x0), abs(y0), abs(x1), abs(y1))
    if 0 < max_coordinate <= 1.5:
        x0, x1 = x0 * width, x1 * width
        y0, y1 = y0 * height, y1 * height
    left = max(0, min(width, math.floor(min(x0, x1))))
    top = max(0, min(height, math.floor(min(y0, y1))))
    right = max(0, min(width, math.ceil(max(x0, x1))))
    bottom = max(0, min(height, math.ceil(max(y0, y1))))
    if right <= left or bottom <= top:
        return None
    return left, top, right, bottom


def _page_bboxes(blocks: list[DocumentBlock], page_no: int) -> list[tuple[float, float, float, float]]:
    bboxes: list[tuple[float, float, float, float]] = []
    for block in blocks or []:
        if block.page_no != page_no or not block.bbox_json:
            continue
        bbox = _coerce_bbox(_read_json_object(block.bbox_json))
        if bbox:
            bboxes.append(bbox)
    return bboxes


def _pdf_render_scale(page_width: float, page_height: float, bboxes: list[tuple[float, float, float, float]]) -> float:
    if page_width <= 0 or page_height <= 0 or not bboxes:
        return 1.0
    max_x = max(max(abs(bbox[0]), abs(bbox[2])) for bbox in bboxes)
    max_y = max(max(abs(bbox[1]), abs(bbox[3])) for bbox in bboxes)
    if max(max_x, max_y) <= 1.5:
        return 1.0
    required = max(max_x / float(page_width), max_y / float(page_height), 1.0)
    if required <= 1.05:
        return 1.0
    return min(4.0, float(math.ceil(required)))


def _artifact_page_numbers(blocks: list[DocumentBlock]) -> set[int]:
    return {
        block.page_no
        for block in (blocks or [])
        if block.page_no is not None and block.page_no >= 0
    }


def _pdf_page_index(page_no: int, zero_based_page_numbers: bool) -> int:
    if zero_based_page_numbers:
        return max(0, page_no)
    return max(0, page_no - 1)


def _display_page_no(page_no: int | None, zero_based_page_numbers: bool = False) -> int:
    if page_no is None:
        return 1
    if zero_based_page_numbers:
        return page_no + 1
    return page_no


def _uses_zero_based_page_numbers(page_numbers: set[int]) -> bool:
    return bool(page_numbers) and min(page_numbers) == 0


def _append_warning(warnings: list[str] | None, message: str) -> None:
    if warnings is not None and message:
        warnings.append(message)


def _block(
    block_no: int,
    block_type: str,
    text: str,
    *,
    page_no: int | None = None,
    bbox_json: str = "",
    table_html: str = "",
    table_rows: list[list[str]] | None = None,
    image_file_name: str = "",
    image_content_base64: str = "",
    image_caption: str = "",
    metadata: dict | None = None,
) -> DocumentBlock:
    return DocumentBlock(
        blockNo=block_no,
        blockType=block_type,
        pageNo=page_no,
        pageRange=str(page_no) if page_no is not None else "",
        bboxJson=bbox_json,
        text=text,
        tableHtml=table_html,
        tableRows=table_rows or [],
        imageFileName=image_file_name,
        imageContentBase64=image_content_base64,
        imageCaption=image_caption,
        metadataJson=json_metadata(metadata or {"parser": NATIVE_TEXT_PARSER_NAME}),
    )


def _render_parsed_text(blocks: list[DocumentBlock]) -> str:
    parts = []
    for block in blocks:
        if block.block_type == "TITLE":
            parts.append(f"# {block.text}")
        elif block.block_type == "TABLE" and block.table_html:
            parts.append(block.text)
        elif block.block_type in {"IMAGE", "FIGURE"} and block.image_caption:
            parts.append(block.image_caption)
        else:
            parts.append(block.text)
    return _cleanup_text("\n\n".join(part for part in parts if part))


def _content_with_weight(block: DocumentBlock, current_section: str) -> str:
    # Java owns weighted projection. Python only emits body/caption.
    parts = []
    if block.text:
        parts.append(block.text)
    if block.image_caption and block.image_caption != block.text:
        parts.append(block.image_caption)
    return "\n".join(parts)


def _canonical_path(section_path: str, block_no: int) -> str:
    section = re.sub(r"[^0-9a-zA-Z\u4e00-\u9fff]+", "-", section_path or "root").strip("-")
    return f"/{section or 'root'}/{block_no}"


def _base_name(file_name: str) -> str:
    return (file_name or "document").rsplit(".", 1)[0] or "document"


def _bbox_json(bbox: tuple[float, float, float, float]) -> str:
    return json_metadata({
        "x0": round(float(bbox[0]), 2),
        "y0": round(float(bbox[1]), 2),
        "x1": round(float(bbox[2]), 2),
        "y1": round(float(bbox[3]), 2),
    })


def _coerce_bbox(value) -> tuple[float, float, float, float] | None:
    if value is None:
        return None
    if isinstance(value, dict):
        keys = ("x0", "y0", "x1", "y1")
        if all(key in value for key in keys):
            try:
                return tuple(float(value[key]) for key in keys)
            except (TypeError, ValueError):
                return None
        keys = ("left", "top", "right", "bottom")
        if all(key in value for key in keys):
            try:
                return tuple(float(value[key]) for key in keys)
            except (TypeError, ValueError):
                return None
        if all(key in value for key in ("x", "y", "width", "height")):
            try:
                x = float(value["x"])
                y = float(value["y"])
                width = float(value["width"])
                height = float(value["height"])
                return x, y, x + width, y + height
            except (TypeError, ValueError):
                return None
        return None
    if hasattr(value, "x0") and hasattr(value, "y0") and hasattr(value, "x1") and hasattr(value, "y1"):
        try:
            return float(value.x0), float(value.y0), float(value.x1), float(value.y1)
        except (TypeError, ValueError):
            return None
    if isinstance(value, (list, tuple)) and len(value) >= 4:
        try:
            return float(value[0]), float(value[1]), float(value[2]), float(value[3])
        except (TypeError, ValueError):
            return _coerce_polygon_bbox(value)
    return None


def _coerce_polygon_bbox(value) -> tuple[float, float, float, float] | None:
    if not value:
        return None
    points: list[tuple[float, float]] = []
    if isinstance(value, dict):
        for key in ("points", "Points", "polygon", "Polygon"):
            candidate = _coerce_polygon_bbox(value.get(key))
            if candidate:
                return candidate
        return None
    if isinstance(value, (list, tuple)):
        if len(value) >= 8 and all(isinstance(item, (int, float, str)) for item in value):
            numeric_values: list[float] = []
            for item in value:
                try:
                    numeric_values.append(float(item))
                except (TypeError, ValueError):
                    return None
            for index in range(0, len(numeric_values) - 1, 2):
                points.append((numeric_values[index], numeric_values[index + 1]))
        else:
            for item in value:
                point = _coerce_point(item)
                if point:
                    points.append(point)
    if len(points) < 2:
        return None
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    return min(xs), min(ys), max(xs), max(ys)


def _coerce_point(value) -> tuple[float, float] | None:
    if isinstance(value, dict):
        x = value.get("x") if "x" in value else value.get("X")
        y = value.get("y") if "y" in value else value.get("Y")
        try:
            return float(x), float(y)
        except (TypeError, ValueError):
            return None
    if isinstance(value, (list, tuple)) and len(value) >= 2:
        try:
            return float(value[0]), float(value[1])
        except (TypeError, ValueError):
            return None
    return None


def _normalize_table_rows(rows) -> list[list[str]]:
    return _trim_table_rows([
        [_cell_text(cell) for cell in row]
        for row in (rows or [])
    ])


def _trim_table_rows(rows: list[list[str]]) -> list[list[str]]:
    cleaned = [[_cleanup_text(cell) for cell in row] for row in rows]
    cleaned = [row for row in cleaned if any(cell for cell in row)]
    if not cleaned:
        return []
    max_columns = max(len(row) for row in cleaned)
    padded = [row + [""] * (max_columns - len(row)) for row in cleaned]
    non_empty_columns = [
        index
        for index in range(max_columns)
        if any(row[index] for row in padded)
    ]
    if not non_empty_columns:
        return []
    first_column = min(non_empty_columns)
    last_column = max(non_empty_columns)
    return [row[first_column:last_column + 1] for row in padded]


def _table_rows_from_html(table_html: str) -> list[list[str]]:
    if not table_html:
        return []
    parser = _TableHtmlParser()
    parser.feed(table_html)
    return _trim_table_rows(parser.rows)


def _table_text(rows: list[list[str]]) -> str:
    return "\n".join(" | ".join(cell for cell in row if cell) for row in rows if any(row))


def _cell_text(value) -> str:
    if value is None:
        return ""
    return _cleanup_text(str(value))


def _to_int(value) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _decode_text(content: bytes) -> str:
    for encoding in ("utf-8", "utf-8-sig", "gb18030"):
        try:
            return content.decode(encoding)
        except UnicodeDecodeError:
            continue
    return content.decode("utf-8", errors="replace")


def _cleanup_text(text: str) -> str:
    if not text:
        return ""
    return (
        text.replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\x00", " ")
        .replace("\t", " ")
        .strip()
    )


def _paragraphs(text: str) -> list[str]:
    cleaned = _cleanup_text(text)
    if not cleaned:
        return []
    return [item.strip() for item in re.split(r"\n\s*\n+", cleaned) if item.strip()]


def _classify_text_block(text: str) -> str:
    stripped = text.strip()
    if not stripped:
        return "TEXT"
    if len(stripped) <= 80 and (
        re.match(r"^([一二三四五六七八九十]+、|第[一二三四五六七八九十0-9]+[章节条]|[0-9]+(\.[0-9]+)*[、.])", stripped)
        or stripped.endswith(("章", "节", "：", ":"))
    ):
        return "TITLE"
    return "TEXT"


def _estimate_token_count(text: str) -> int:
    if not text:
        return 0
    chinese_count = len(re.findall(r"[\u4e00-\u9fff]", text))
    english_count = len(re.findall(r"[A-Za-z]+", text))
    return chinese_count + english_count + max(1, (len(text) - chinese_count) // 4)


def _structure_level(heading_count: int, paragraph_count: int) -> int:
    if heading_count >= 5:
        return 3
    if heading_count >= 2:
        return 2
    if paragraph_count >= 3:
        return 1
    return 0


def _content_quality_level(text: str) -> int:
    if not text or not text.strip():
        return 1
    broken_ratio = text.count("�") / max(len(text), 1)
    if broken_ratio > 0.02:
        return 1
    if broken_ratio > 0.005 or len(text) < 500:
        return 2
    return 3


def _table_html(rows: list[list[str]]) -> str:
    body = []
    for row in rows:
        cells = "".join(f"<td>{html.escape(cell)}</td>" for cell in row)
        body.append(f"<tr>{cells}</tr>")
    return "<table><tbody>" + "".join(body) + "</tbody></table>"


class _HtmlBlockParser(HTMLParser):
    _HEADINGS = {"h1", "h2", "h3", "h4", "h5", "h6"}
    _TEXT_BLOCKS = {"p", "li", "pre", "blockquote"}
    _SKIP = {"script", "style", "noscript"}

    def __init__(self) -> None:
        super().__init__()
        self.blocks: list[tuple[str, str, str, list[list[str]]]] = []
        self._skip_depth = 0
        self._stack: list[str] = []
        self._text: list[str] = []
        self._loose_text: list[str] = []
        self._table_depth = 0
        self._table_html: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in self._SKIP:
            self._skip_depth += 1
            return
        if self._skip_depth:
            return
        if tag == "table":
            self._flush_text()
            self._table_depth += 1
            self._table_html.append(self._serialize_start(tag, attrs) if self._table_depth > 1 else "<table>")
            return
        if self._table_depth:
            self._table_html.append(self._serialize_start(tag, attrs))
            return
        if tag in self._HEADINGS:
            self._flush_text()
            self._stack.append("TITLE")
            self._text = []
        elif tag in self._TEXT_BLOCKS:
            self._flush_text()
            self._stack.append("TEXT")
            self._text = []
        elif tag == "br":
            target = self._text if self._stack else self._loose_text
            target.append("\n")
        elif tag == "img":
            alt = _cleanup_text(str(dict(attrs).get("alt") or ""))
            if alt:
                self.blocks.append(("IMAGE", alt, "", []))

    def handle_endtag(self, tag: str) -> None:
        if tag in self._SKIP and self._skip_depth:
            self._skip_depth -= 1
            return
        if self._skip_depth:
            return
        if self._table_depth:
            self._table_html.append(f"</{tag}>")
            if tag == "table":
                self._table_depth -= 1
                if self._table_depth == 0:
                    table_html = "".join(self._table_html)
                    self._table_html = []
                    rows = _table_rows_from_html(table_html)
                    text = _table_text(rows) if rows else _cleanup_text(re.sub(r"<[^>]+>", " ", table_html))
                    if text or rows:
                        self.blocks.append(("TABLE", text, table_html, rows))
            return
        if tag in self._HEADINGS or tag in self._TEXT_BLOCKS:
            self._flush_text()
            if self._stack:
                self._stack.pop()

    def handle_data(self, data: str) -> None:
        if self._skip_depth:
            return
        if self._table_depth:
            self._table_html.append(html.escape(data))
            return
        if self._stack:
            self._text.append(data)
        else:
            self._loose_text.append(data)

    def close(self) -> None:
        self._flush_text()
        super().close()

    def _flush_text(self) -> None:
        if self._stack:
            text = _cleanup_text("".join(self._text))
            if text:
                self.blocks.append((self._stack[-1], text, "", []))
            self._text = []
        else:
            text = _cleanup_text("".join(self._loose_text))
            if text:
                self.blocks.append(("TEXT", text, "", []))
            self._loose_text = []

    def _serialize_start(self, tag: str, attrs: list[tuple[str, str | None]]) -> str:
        parts = [f"<{tag}"]
        for name, value in attrs:
            if value is None:
                parts.append(f" {name}")
            else:
                parts.append(f' {name}="{html.escape(value, quote=True)}"')
        parts.append(">")
        return "".join(parts)


class _TableHtmlParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.rows: list[list[str]] = []
        self._occupancy: set[tuple[int, int]] = set()
        self._row_index = -1
        self._in_row = False
        self._cell_buffer: list[str] | None = None
        self._cell_rowspan = 1
        self._cell_colspan = 1

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attr = {name.lower(): value for name, value in attrs}
        if tag == "tr":
            self._row_index += 1
            self._in_row = True
            self._ensure_row(self._row_index)
        elif tag in {"td", "th"} and self._in_row:
            self._cell_buffer = []
            self._cell_rowspan = max(1, _to_int(attr.get("rowspan")) or 1)
            self._cell_colspan = max(1, _to_int(attr.get("colspan")) or 1)

    def handle_endtag(self, tag: str) -> None:
        if tag in {"td", "th"} and self._cell_buffer is not None:
            text = _cleanup_text("".join(self._cell_buffer))
            column = self._next_free_column(self._row_index)
            for row_offset in range(self._cell_rowspan):
                row_no = self._row_index + row_offset
                self._ensure_row(row_no)
                for col_offset in range(self._cell_colspan):
                    col_no = column + col_offset
                    self._ensure_column(row_no, col_no)
                    if row_offset == 0 and col_offset == 0:
                        self.rows[row_no][col_no] = text
                    elif not self.rows[row_no][col_no]:
                        self.rows[row_no][col_no] = text
                    self._occupancy.add((row_no, col_no))
            self._cell_buffer = None
        elif tag == "tr":
            self._in_row = False

    def handle_data(self, data: str) -> None:
        if self._cell_buffer is not None:
            self._cell_buffer.append(data)

    def _next_free_column(self, row_no: int) -> int:
        column = 0
        while (row_no, column) in self._occupancy:
            column += 1
        return column

    def _ensure_row(self, row_no: int) -> None:
        while len(self.rows) <= row_no:
            self.rows.append([])

    def _ensure_column(self, row_no: int, col_no: int) -> None:
        while len(self.rows[row_no]) <= col_no:
            self.rows[row_no].append("")
