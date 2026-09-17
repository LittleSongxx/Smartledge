import base64
import json
import unittest
from io import BytesIO
from unittest.mock import patch

from fastapi import HTTPException

from rag_tools import document_parser
from rag_tools.document_parser import parse_document
from rag_tools.schemas.document_parse import DocumentParseRequest


class AliyunDocMindDocumentParserTest(unittest.TestCase):
    def test_document_parser_status_exposes_fixed_type_routes(self) -> None:
        status = document_parser.document_parser_status()

        self.assertEqual("type_routed", status["defaultProvider"])
        self.assertEqual(2, len(status["providers"]))
        providers = {provider["providerName"]: provider for provider in status["providers"]}
        self.assertIn("native_text", providers)
        self.assertIn("aliyun_docmind", providers)
        self.assertIn("md", providers["native_text"]["supportedFileTypes"])
        self.assertIn("txt", providers["native_text"]["supportedFileTypes"])
        self.assertIn("html", providers["native_text"]["supportedFileTypes"])
        self.assertIn("pdf", providers["aliyun_docmind"]["supportedFileTypes"])
        self.assertIn("png", providers["aliyun_docmind"]["supportedFileTypes"])
        self.assertIn("jpg", providers["aliyun_docmind"]["supportedFileTypes"])
        self.assertIn("jpeg", providers["aliyun_docmind"]["supportedFileTypes"])
        self.assertIn("ocr", providers["aliyun_docmind"]["capabilities"])
        self.assertIn("layout", providers["aliyun_docmind"]["capabilities"])
        self.assertIn("table", providers["aliyun_docmind"]["capabilities"])
        self.assertIn("figure", providers["aliyun_docmind"]["capabilities"])

    def test_markdown_uses_native_text_parser_without_docmind_credentials(self) -> None:
        request = DocumentParseRequest(
            fileName="星联智服全渠道客服平台上线与运营管理手册.md",
            fileType="MD",
            contentBase64=base64.b64encode(
                "# 上线手册\n\n"
                "## 灰度发布\n\n"
                "蓝桥订单 7391 需要在 AuditTrail 中留痕。\n\n"
                "| 项目 | 负责人 |\n"
                "| --- | --- |\n"
                "| 支付回调延迟 | SRE 值班长 |\n".encode("utf-8")
            ).decode("ascii"),
        )

        with patch.object(document_parser.AliyunDocMindParser, "parse", side_effect=AssertionError("Document Mind should not parse Markdown")):
            response = parse_document(request)

        self.assertEqual("native_text", response.provider_name)
        self.assertIn("markdown", response.capabilities)
        self.assertIn("蓝桥订单 7391", response.parsed_text)
        self.assertIn("AuditTrail", response.parsed_text)
        self.assertNotIn("structureNodes", response.model_dump(by_alias=True, exclude_none=True))
        artifact_types = {artifact.artifact_type for artifact in response.artifacts}
        self.assertNotIn("ALIYUN_DOCMIND_JSON", artifact_types)
        self.assertNotIn("LAYOUT_JSON", artifact_types)
        self.assertIn("MARKDOWN", artifact_types)
        self.assertIn("JSON", artifact_types)

    def test_missing_credential_fails_without_fallback(self) -> None:
        request = DocumentParseRequest(
            fileName="sample.pdf",
            fileType="PDF",
            contentBase64=base64.b64encode(b"%PDF-1.4\n%%EOF").decode("ascii"),
        )

        with patch.object(document_parser.AliyunDocMindParser, "_sdk_available", return_value=True), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_id", return_value=""), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_secret", return_value=""):
            with self.assertRaises(HTTPException) as context:
                parse_document(request)

        self.assertEqual(503, context.exception.status_code)
        self.assertIn("阿里云 Document Mind", str(context.exception.detail))

    def test_image_uses_docmind_without_fallback(self) -> None:
        request = DocumentParseRequest(
            fileName="scan.png",
            fileType="PNG",
            contentBase64=base64.b64encode(b"\x89PNG\r\n\x1a\n").decode("ascii"),
        )

        with patch.object(document_parser.AliyunDocMindParser, "_sdk_available", return_value=True), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_id", return_value=""), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_secret", return_value=""):
            with self.assertRaises(HTTPException) as context:
                parse_document(request)

        self.assertEqual(503, context.exception.status_code)
        self.assertIn("阿里云 Document Mind", str(context.exception.detail))

    def test_docmind_maps_layouts_to_standard_blocks(self) -> None:
        request = DocumentParseRequest(
            fileName="docmind.pdf",
            fileType="PDF",
            contentBase64=base64.b64encode(_tiny_pdf_bytes()).decode("ascii"),
        )
        fake_client = _FakeDocMindClient()

        with patch.object(document_parser.AliyunDocMindParser, "_sdk_available", return_value=True), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_id", return_value="ak"), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_secret", return_value="sk"), \
                patch.object(document_parser.AliyunDocMindParser, "_client", return_value=fake_client), \
                patch.object(document_parser.AliyunDocMindParser, "_runtime_options", return_value=None):
            response = parse_document(request)

        self.assertEqual("aliyun_docmind", response.provider_name)
        self.assertIn("ocr", response.capabilities)
        self.assertIn("layout", response.capabilities)
        self.assertEqual(["TITLE", "TABLE", "FIGURE"], [block.block_type for block in response.blocks])
        self.assertEqual("年度经营摘要", response.blocks[0].text)
        self.assertEqual([["指标", "数值"], ["收入", "100"]], response.blocks[1].table_rows)
        self.assertIn("收入", response.blocks[1].text)
        self.assertEqual(1, response.blocks[1].page_no)
        self.assertTrue(response.blocks[1].bbox_json)

        table_metadata = json.loads(response.blocks[1].metadata_json)
        self.assertEqual("table", table_metadata.get("layoutType"))
        self.assertEqual("aliyun_docmind", table_metadata.get("providerName"))
        self.assertEqual("pos", table_metadata.get("bboxSource"))
        self.assertTrue(table_metadata.get("tableCellMetadata"))
        self.assertTrue(table_metadata["tableCellMetadata"][0].get("bboxJson"))
        self.assertEqual("pos", table_metadata["tableCellMetadata"][0].get("bboxSource"))
        self.assertEqual(1, response.trace_metadata.get("pageCount"))
        self.assertEqual(3, response.trace_metadata.get("blockCount"))
        self.assertEqual(3, response.trace_metadata.get("rawLayoutCount"))
        self.assertEqual(3, response.trace_metadata.get("bboxBlockCount"))
        self.assertEqual(4, response.trace_metadata.get("tableCellBboxCount"))
        self.assertEqual(1.0, response.trace_metadata.get("tableCellBboxCoverage"))
        self.assertEqual("docmind-job-1", response.trace_metadata.get("jobId"))

        artifact_types = {artifact.artifact_type for artifact in response.artifacts}
        self.assertIn("ALIYUN_DOCMIND_JSON", artifact_types)
        self.assertIn("LAYOUT_JSON", artifact_types)
        self.assertIn("JSON", artifact_types)
        self.assertIn("MARKDOWN", artifact_types)
        self.assertIn("PAGE_IMAGE", artifact_types)
        self.assertIn("TABLE_IMAGE", artifact_types)
        self.assertIn("FIGURE_IMAGE", artifact_types)
        page_image = self._artifact(response.artifacts, "PAGE_IMAGE")
        self.assertEqual("image/png", page_image.content_type)
        self.assertTrue(page_image.file_name.endswith(".page-1.png"))
        page_width, page_height = _png_size(page_image)
        self.assertGreaterEqual(page_height, 1260)
        table_image = self._artifact(response.artifacts, "TABLE_IMAGE")
        self.assertEqual("image/png", table_image.content_type)
        self.assertIn(".block-2.", table_image.file_name)
        figure_image = self._artifact(response.artifacts, "FIGURE_IMAGE")
        self.assertEqual("image/png", figure_image.content_type)
        self.assertIn(".block-3.", figure_image.file_name)
        raw_payload = self._artifact_payload(response.artifacts, "ALIYUN_DOCMIND_JSON")
        self.assertEqual("docmind-job-1", raw_payload.get("jobId"))
        self.assertEqual(4, raw_payload.get("traceMetadata", {}).get("tableCellBboxCount"))
        blocks_payload = self._artifact_payload(response.artifacts, "JSON")
        self.assertEqual(3, blocks_payload.get("traceMetadata", {}).get("rawLayoutCount"))
        self.assertEqual(3, blocks_payload.get("traceMetadata", {}).get("bboxBlockCount"))
        layout_payload = self._artifact_payload(response.artifacts, "LAYOUT_JSON")
        self.assertEqual(1, layout_payload.get("traceMetadata", {}).get("pageCount"))

    def test_docmind_image_input_emits_page_image_artifact(self) -> None:
        request = DocumentParseRequest(
            fileName="scan.png",
            fileType="PNG",
            contentBase64=base64.b64encode(_tiny_png_bytes()).decode("ascii"),
        )
        fake_client = _FakeDocMindClient()

        with patch.object(document_parser.AliyunDocMindParser, "_sdk_available", return_value=True), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_id", return_value="ak"), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_secret", return_value="sk"), \
                patch.object(document_parser.AliyunDocMindParser, "_client", return_value=fake_client), \
                patch.object(document_parser.AliyunDocMindParser, "_runtime_options", return_value=None):
            response = parse_document(request)

        page_images = [artifact for artifact in response.artifacts if artifact.artifact_type == "PAGE_IMAGE"]
        self.assertEqual(1, len(page_images))
        self.assertEqual("image/png", page_images[0].content_type)
        self.assertTrue(page_images[0].file_name.endswith(".page-1.png"))

    def test_docmind_zero_based_page_numbers_still_emit_page_and_crop_images(self) -> None:
        request = DocumentParseRequest(
            fileName="zero-based.pdf",
            fileType="PDF",
            contentBase64=base64.b64encode(_tiny_pdf_bytes()).decode("ascii"),
        )
        fake_client = _FakeDocMindClient(page_no=0)

        with patch.object(document_parser.AliyunDocMindParser, "_sdk_available", return_value=True), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_id", return_value="ak"), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_secret", return_value="sk"), \
                patch.object(document_parser.AliyunDocMindParser, "_client", return_value=fake_client), \
                patch.object(document_parser.AliyunDocMindParser, "_runtime_options", return_value=None):
            response = parse_document(request)

        artifact_types = {artifact.artifact_type for artifact in response.artifacts}
        self.assertIn("PAGE_IMAGE", artifact_types)
        self.assertIn("TABLE_IMAGE", artifact_types)
        self.assertIn("FIGURE_IMAGE", artifact_types)
        page_image = self._artifact(response.artifacts, "PAGE_IMAGE")
        self.assertTrue(page_image.file_name.endswith(".page-1.png"))
        self.assertEqual([0], response.trace_metadata.get("pageNumbers"))
        self.assertEqual(1, response.trace_metadata.get("pageCount"))

    def _artifact(self, artifacts, artifact_type: str):
        matches = [artifact for artifact in artifacts if artifact.artifact_type == artifact_type]
        self.assertEqual(1, len(matches))
        return matches[0]

    def _artifact_payload(self, artifacts, artifact_type: str) -> dict:
        matches = [artifact for artifact in artifacts if artifact.artifact_type == artifact_type]
        self.assertEqual(1, len(matches))
        return json.loads(base64.b64decode(matches[0].content_base64).decode("utf-8"))


class _FakeTeaBody:
    def __init__(self, payload: dict) -> None:
        self.payload = payload

    def to_map(self) -> dict:
        return self.payload


class _FakeTeaResponse:
    def __init__(self, payload: dict) -> None:
        self.body = _FakeTeaBody(payload)


class _FakeDocMindClient:
    def __init__(self, page_no: int = 1):
        self.page_no = page_no

    def submit_doc_parser_job_advance(self, request, runtime):
        return _FakeTeaResponse({
            "Code": "200",
            "Data": {"Id": "docmind-job-1"},
        })

    def query_doc_parser_status(self, request):
        return _FakeTeaResponse({
            "Code": "200",
            "Data": {"Status": "Success", "PageCountEstimate": 1},
        })

    def get_doc_parser_result(self, request):
        layout_num = getattr(request, "layout_num", 0) or 0
        if layout_num > 0:
            return _FakeTeaResponse({"Code": "200", "Data": {"layouts": []}})
        page_no = self.page_no
        return _FakeTeaResponse({
            "Code": "200",
            "Data": {
                "markdownContent": "# 年度经营摘要\n\n| 指标 | 数值 |\n| --- | --- |\n| 收入 | 100 |",
                "layouts": [
                    {
                        "index": 1,
                        "type": "title",
                        "text": "年度经营摘要",
                        "pageNum": page_no,
                        "layoutConf": 0.98,
                        "pos": [{"x": 12, "y": 24}, {"x": 260, "y": 24}, {"x": 260, "y": 60}, {"x": 12, "y": 60}],
                    },
                    {
                        "index": 2,
                        "type": "table",
                        "llmResult": "<table><tr><td>指标</td><td>数值</td></tr><tr><td>收入</td><td>100</td></tr></table>",
                        "pageNum": page_no,
                        "layoutConf": 0.95,
                        "pos": [{"x": 94, "y": 908}, {"x": 1074, "y": 908}, {"x": 1074, "y": 1098}, {"x": 94, "y": 1098}],
                        "cells": [
                            {"rowIndex": 1, "colIndex": 1, "text": "指标", "pos": [{"x": 94, "y": 908}, {"x": 300, "y": 908}, {"x": 300, "y": 950}, {"x": 94, "y": 950}]},
                            {"rowIndex": 1, "colIndex": 2, "text": "数值", "pos": [{"x": 300, "y": 908}, {"x": 520, "y": 908}, {"x": 520, "y": 950}, {"x": 300, "y": 950}]},
                            {"rowIndex": 2, "colIndex": 1, "text": "收入", "pos": [{"x": 94, "y": 950}, {"x": 300, "y": 950}, {"x": 300, "y": 996}, {"x": 94, "y": 996}]},
                            {"rowIndex": 2, "colIndex": 2, "text": "100", "pos": [{"x": 300, "y": 950}, {"x": 520, "y": 950}, {"x": 520, "y": 996}, {"x": 300, "y": 996}]},
                        ],
                    },
                    {
                        "index": 3,
                        "type": "figure",
                        "text": "收入趋势图显示持续增长。",
                        "pageNum": page_no,
                        "pos": [{"x": 20, "y": 220}, {"x": 260, "y": 220}, {"x": 260, "y": 320}, {"x": 20, "y": 320}],
                    },
                ],
            },
        })


def _tiny_png_bytes() -> bytes:
    from PIL import Image

    output = BytesIO()
    Image.new("RGB", (360, 420), "white").save(output, format="PNG")
    return output.getvalue()


def _tiny_pdf_bytes() -> bytes:
    import fitz

    document = fitz.open()
    page = document.new_page(width=360, height=420)
    page.insert_text((20, 40), "docmind test")
    content = document.tobytes()
    document.close()
    return content


def _png_size(artifact) -> tuple[int, int]:
    from PIL import Image

    image = Image.open(BytesIO(base64.b64decode(artifact.content_base64)))
    return image.width, image.height


if __name__ == "__main__":
    unittest.main()
