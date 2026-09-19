"""Pure source planning and one bounded inference batch. Java owns scheduling/publication."""
from collections import Counter
import hashlib
import json
import math
import re
import time
import urllib.error
import urllib.request

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator
from pydantic_core import PydanticCustomError
from tokenizers import Tokenizer, models, pre_tokenizers

from rag_tools.config import config_value
from rag_tools.graph_entity_llm import GraphEntityLlmError
from rag_tools.prompt_loader import load_prompt
from rag_tools.schemas.graph_extract import GraphExtractRequest, GraphExtractResponse

VERSION = "graph-candidates.v3"
TOKENIZER = "tokenizers.byte-level-unmerged.estimate.v1"
INPUT_BUDGET_PARAMETER_KEYS = [
    "graphRag.extraction.inputTokenBudget",
    "graphRag.model.modelContextTokens",
    "graphRag.model.outputReserve",
    "graphRag.model.promptReserve",
]
_byte_alphabet = sorted(pre_tokenizers.ByteLevel.alphabet())
_tokenizer = Tokenizer(models.BPE(vocab={c: i for i, c in enumerate(_byte_alphabet)}, merges=[]))
_tokenizer.pre_tokenizer = pre_tokenizers.ByteLevel(add_prefix_space=False, use_regex=False)

# 供应商侧结构约束：`json_object` 只约束 JSON 语法，"合法但缺数组"的响应仍会被返回，
# 只能由本地契约拒绝并让整次构建 NO_COMMIT。下面的 schema 与本地 Entity/Relation/Evidence
# 契约逐字段对齐（tests 里有防漂移断言）；strict 模式要求每个对象 additionalProperties=false
# 且所有属性都在 required 里，因此带默认值的字段也必须显式声明。
# supportMode 的枚举权威是存储级支持模式常量（Java 侧同一对常量在 GraphRagRelationAuthority）。
_ENTITY_SCHEMA = {
    "type": "object", "additionalProperties": False,
    "required": ["id", "sourceId", "name", "aliases", "type", "confidence"],
    "properties": {
        "id": {"type": "string", "minLength": 1},
        "sourceId": {"type": "string"},
        "name": {"type": "string", "minLength": 1},
        "aliases": {"type": "array", "items": {"type": "string"}},
        "type": {"type": "string"},
        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
    },
}
_RELATION_SCHEMA = {
    "type": "object", "additionalProperties": False,
    "required": ["id", "sourceEntityId", "targetEntityId", "relationType", "supportMode",
                 "predicateQuoteText", "evidenceIds", "description", "confidence",
                 "tableId", "rowNo", "sourceColumnNo", "targetColumnNo"],
    "properties": {
        "id": {"type": "string", "minLength": 1},
        "sourceEntityId": {"type": "string"},
        "targetEntityId": {"type": "string"},
        "relationType": {"type": "string"},
        "supportMode": {"type": "string", "enum": ["EXPLICIT_ACTION", "STRUCTURED_ROW"]},
        "predicateQuoteText": {"type": "string"},
        "evidenceIds": {"type": "array", "items": {"type": "string"}, "minItems": 1},
        "description": {"type": "string"},
        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
        # 表格坐标只在 STRUCTURED_ROW 下有意义，其余模式必须为空；strict 模式要求属性齐全，
        # 因此可空类型是表达能力与"必须显式声明"之间的唯一折中。
        "tableId": {"type": ["integer", "null"]},
        "rowNo": {"type": ["integer", "null"], "minimum": 1},
        "sourceColumnNo": {"type": ["integer", "null"], "minimum": 1},
        "targetColumnNo": {"type": ["integer", "null"], "minimum": 1},
    },
}
_EVIDENCE_SCHEMA = {
    "type": "object", "additionalProperties": False,
    "required": ["id", "sourceId", "entityId", "relationId", "quoteText", "confidence"],
    "properties": {
        "id": {"type": "string", "minLength": 1},
        "sourceId": {"type": "string"},
        "entityId": {"type": "string"},
        "relationId": {"type": "string"},
        "quoteText": {"type": "string"},
        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
    },
}
CANDIDATE_SCHEMA = {
    "type": "object", "additionalProperties": False,
    "required": ["entities", "relations", "evidences"],
    "properties": {
        "entities": {"type": "array", "items": _ENTITY_SCHEMA},
        "relations": {"type": "array", "items": _RELATION_SCHEMA},
        "evidences": {"type": "array", "items": _EVIDENCE_SCHEMA},
    },
}
# 响应格式按供应商能力选择：默认 json_schema（供应商侧严格约束）；
# DeepSeek 等 OpenAI 兼容端点不支持 json_schema（HTTP 400 invalid_request_error），
# 此时用 ragTools.llm.responseFormat=json_object 降级——只约束 JSON 语法，
# 结构正确性完全交给本地契约（parse + isolate_candidates 逐字段校验后仍按 source_failed 拒绝）。
_RESPONSE_FORMAT_MODE = config_value("ragTools.llm.responseFormat", "RAG_TOOLS_LLM_RESPONSE_FORMAT",
                                     "json_schema").strip().lower()
# 推理型模型的思维链开关：DeepSeek 的 thinking 模型不关推理时会把输出预算全部烧在
# reasoning_content 上（实测 max_tokens=4096 被 13k 字符思维链耗尽、content 为空）。
# 默认不发该参数（qwen 等网关不认识多余字段会 400）；deepseek 场景配 disabled。
_LLM_THINKING_MODE = config_value("ragTools.llm.thinkingMode", "RAG_TOOLS_LLM_THINKING", "").strip().lower()
if _RESPONSE_FORMAT_MODE == "json_object":
    CANDIDATE_RESPONSE_FORMAT = {"type": "json_object"}
else:
    CANDIDATE_RESPONSE_FORMAT = {"type": "json_schema",
                                 "json_schema": {"name": "graph_candidates", "strict": True,
                                                 "schema": CANDIDATE_SCHEMA}}
# 估算口径里的结构信封占位：只保证"来源预算"一侧的口径与既有基线一致，不代表真实请求。
_STRUCTURAL_ENVELOPE = {"type": "json_object"}


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def serial(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


# 结构约束是协议的固定常量：字节级估算法把它算成 1893 个 token（punctuation 密集的 JSON），
# 而 inputTokenBudget 只有 4600 —— 把它算进来源预算会让一个 17 字的来源都放不下。
# 因此它不进来源预算（见 prompt_tokens），而是整份计入 contextTokens 余量（见 fits），
# 并在 plan 的 inference 里单独可见（responseFormatTokensEstimate）。
RESPONSE_FORMAT_TOKENS_ESTIMATE = len(_tokenizer.encode(serial(CANDIDATE_RESPONSE_FORMAT)).ids)
# 计划段数/批次数与 batchChunkLimit 解耦，并与 Java maxBatches 上限 4096 对齐。
# 旧公式 1024 * batchChunkLimit 在默认 1 段/批时会把百页手册直接打成资源上限。
MAX_PLAN_SEGMENTS = 4096
MAX_PLAN_BATCHES = 4096


def fail(reason, status=422, **diagnostics):
    category = {"GRAPH_LLM_CONFIG_MISSING": "CONFIGURATION", "GRAPH_LLM_BUDGET_INVALID": "CONFIGURATION",
                "GRAPH_CONFIGURATION_DRIFT": "CONFIGURATION", "GRAPH_INVALID_OPTION": "CONFIGURATION",
                "GRAPH_CONFIG_MIGRATION_REQUIRED": "CONFIGURATION", "GRAPH_BATCH_BUDGET_EXCEEDED": "CONTEXT_LIMIT",
                "GRAPH_LLM_TIMEOUT": "READ_TIMEOUT", "GRAPH_PLAN_RESOURCE_LIMIT": "RESOURCE_LIMIT",
                "GRAPH_DOCUMENT_RESOURCE_LIMIT": "RESOURCE_LIMIT", "GRAPH_CANDIDATE_RESOURCE_LIMIT": "RESOURCE_LIMIT",
                "GRAPH_LLM_RESPONSE_TOO_LARGE": "RESOURCE_LIMIT",
                "GRAPH_LLM_OUTPUT_TRUNCATED": "OUTPUT_TRUNCATED"}.get(reason.split(":")[0], "PROTOCOL")
    raise GraphEntityLlmError(reason, status, category, **diagnostics)


def settings(options):
    if "enabled" in options or "batchTokenLimit" in options:
        fail("GRAPH_CONFIG_MIGRATION_REQUIRED: retired GraphRAG option")
    limits = {"batchChunkLimit": (1, 20), "inputTokenBudget": (256, 32000),
              "maxQuoteChars": (20, 2000),
              "maxReasonChars": (20, 2000), "maxUnitTextChars": (50, 8000),
              "modelContextTokens": (1024, 200000), "outputReserve": (1, 100000),
              "promptReserve": (1, 100000), "modelResponseMaxBytes": (1, 67108864),
              "maxEntityNameChars": (1, 10000), "maxDocumentBytes": (1, 64000000)}
    for key, (low, high) in limits.items():
        value = options.get(key)
        if type(value) is not int or not low <= value <= high:
            fail("GRAPH_INVALID_OPTION: " + key)
    base = config_value("ragTools.llm.baseUrl", "RAG_TOOLS_LLM_BASE_URL", "").rstrip("/")
    key = config_value("ragTools.llm.apiKey", "RAG_TOOLS_LLM_API_KEY", "")
    model = config_value("ragTools.llm.model", "RAG_TOOLS_LLM_MODEL", "")
    if not base or not key or not model:
        fail("GRAPH_LLM_CONFIG_MISSING", 503)
    try:
        timeout = float(config_value("ragTools.llm.timeoutSeconds", "RAG_TOOLS_LLM_TIMEOUT_SECONDS"))
        budgets = {"contextTokens": options["modelContextTokens"],
                   "outputReserve": options["outputReserve"],
                   "promptReserve": options["promptReserve"],
                   "maxResponseBytes": options["modelResponseMaxBytes"],
                   "maxEntityNameChars": options["maxEntityNameChars"]}
    except (KeyError, TypeError, ValueError):
        fail("GRAPH_LLM_BUDGET_INVALID")
    if (not math.isfinite(timeout) or timeout <= 0 or any(value <= 0 for value in budgets.values())
            or budgets["contextTokens"] <= budgets["outputReserve"] + budgets["promptReserve"]):
        fail("GRAPH_LLM_BUDGET_INVALID")
    if base.endswith("/compatible-mode"):
        base += "/v1"
    endpoint = base if base.endswith("/chat/completions") else base + "/chat/completions"
    prompt = load_prompt("graph-candidates-system.txt")
    public = {"model": model, "promptFingerprint": digest(prompt), "tokenizer": TOKENIZER,
              # 结构约束决定模型输出的形状，因此属于请求身份：契约一变指纹就变，
              # 旧的 plan 与新的抽取实现配对时会显式 DRIFT 失败，而不是静默换协议。
              "responseFormatFingerprint": digest(serial(CANDIDATE_RESPONSE_FORMAT)),
              "responseFormatTokensEstimate": RESPONSE_FORMAT_TOKENS_ESTIMATE,
              **budgets,
              "timeoutSeconds": int(timeout), "options": options, "endpointFingerprint": digest(endpoint),
              "version": VERSION}
    public["effectiveTimeoutSeconds"] = int(timeout)
    return endpoint, key, prompt, public["effectiveTimeoutSeconds"], public, digest(serial(public))


def messages(prompt, segments, options):
    return [{"role": "system", "content": prompt},
            {"role": "user", "content": serial({"limits": options, "sources": segments})}]


def request_body(prompt, segments, public):
    """抽取请求体的唯一构造点：真实调用与估算都从这里取形状。"""
    body = {"model": public["model"], "temperature": 0, "max_tokens": public["outputReserve"],
            "response_format": CANDIDATE_RESPONSE_FORMAT,
            "messages": messages(prompt, segments, public["options"])}
    if _LLM_THINKING_MODE in ("disabled", "enabled"):
        body["thinking"] = {"type": _LLM_THINKING_MODE}
    return body


def prompt_tokens(prompt, segments, public):
    # Unmerged byte BPE is a conservative estimate, not the provider's exact tokenizer.
    # 只估算**随来源变化**的部分：模型与预算字段、limits、sources、system prompt。
    # 结构常量换成一个最小信封占位，它的实际大小在 fits() 的 context 侧整份计入。
    envelope = request_body(prompt, segments, public)
    envelope["response_format"] = _STRUCTURAL_ENVELOPE
    return len(_tokenizer.encode(serial(envelope)).ids) + 64


def handle(request: GraphExtractRequest) -> GraphExtractResponse:
    started = time.monotonic()
    endpoint, key, prompt, timeout, public, fingerprint = settings(request.options)
    tool_deadline = started + min(timeout, request.budget_millis / 1000)
    chunks = {}
    for chunk in request.chunks:
        if chunk.chunk_id is None or chunk.chunk_id in chunks or not chunk.text.strip():
            fail("GRAPH_SOURCE_IDENTITY_INVALID")
        chunks[chunk.chunk_id] = chunk
    if sum(len(c.text.encode("utf-8")) for c in chunks.values()) > request.options["maxDocumentBytes"]:
        fail("GRAPH_DOCUMENT_RESOURCE_LIMIT")
    if request.operation == "plan":
        segments, batches = [], []
        for chunk in chunks.values():
            start = 0
            while start < len(chunk.text):
                if time.monotonic() >= tool_deadline:
                    fail("GRAPH_PLAN_RESOURCE_LIMIT")
                end = min(len(chunk.text), start + request.options["maxUnitTextChars"])
                if end < len(chunk.text):
                    boundaries = list(re.finditer(r"[。！？.!?；;\n]", chunk.text[start:end]))
                    if boundaries:
                        end = start + boundaries[-1].end()
                while True:
                    text = chunk.text[start:end]
                    segment = {"sourceId": f"s{len(segments) + 1}", "chunkId": chunk.chunk_id,
                               "start": start, "end": end, "text": text, "contentFingerprint": digest(text),
                               "chunkType": chunk.chunk_type,
                               "structuredTables": visible_structured_tables(chunk.structured_tables, text)}
                    cost = prompt_tokens(prompt, [segment], public)
                    if fits(cost, public):
                        break
                    if end - start <= 1:
                        fail("GRAPH_SINGLE_UNIT_BUDGET_EXCEEDED")
                    end = start + (end - start) // 2
                segments.append(segment)
                start = end
                if len(segments) > MAX_PLAN_SEGMENTS:
                    fail("GRAPH_DOCUMENT_RESOURCE_LIMIT")
        current = []
        for segment in segments:
            if time.monotonic() >= tool_deadline:
                fail("GRAPH_PLAN_RESOURCE_LIMIT")
            trial = current + [segment]
            if current and (len(trial) > request.options["batchChunkLimit"] or
                            not fits(prompt_tokens(prompt, trial, public), public)):
                batches.append(batch(current, len(batches), prompt, public))
                current = []
            current.append(segment)
        if current:
            batches.append(batch(current, len(batches), prompt, public))
        if len(batches) > MAX_PLAN_BATCHES or time.monotonic() >= tool_deadline:
            fail("GRAPH_PLAN_RESOURCE_LIMIT")
        plan_id = digest(request.input_fingerprint + fingerprint + serial(batches))
        return GraphExtractResponse(metadata={"schemaVersion": VERSION, "operation": "plan",
            "inputFingerprint": request.input_fingerprint, "configurationFingerprint": fingerprint,
            "planFingerprint": plan_id, "offsetUnit": "unicode-code-point", "batches": batches,
            "inference": public})
    if fingerprint != request.configuration_fingerprint:
        fail("GRAPH_CONFIGURATION_DRIFT")
    if not request.plan_fingerprint or not request.batch_id or not request.segments:
        fail("GRAPH_BATCH_IDENTITY_INVALID")
    seen = set()
    for segment in request.segments:
        try:
            chunk = chunks[segment["chunkId"]]
            start, end = segment["start"], segment["end"]
            if type(start) is not int or type(end) is not int or not 0 <= start < end <= len(chunk.text):
                fail("GRAPH_SOURCE_RANGE_INVALID")
            if segment["sourceId"] in seen or segment["text"] != chunk.text[start:end] or digest(segment["text"]) != segment["contentFingerprint"]:
                fail("GRAPH_SOURCE_CONTENT_INVALID")
            seen.add(segment["sourceId"])
        except (KeyError, TypeError):
            fail("GRAPH_SOURCE_IDENTITY_INVALID")
    if len(request.segments) > request.options["batchChunkLimit"] or not fits(prompt_tokens(prompt, request.segments, public), public):
        fail("GRAPH_BATCH_BUDGET_EXCEEDED", resource="inputTokens",
             actualLength=prompt_tokens(prompt, request.segments, public),
             configuredLimit=min(request.options["inputTokenBudget"],
                                 public["contextTokens"] - public["outputReserve"] - public["promptReserve"]),
             measurementKind="ESTIMATE", actualAvailability="ESTIMATED",
             parameterKeys=INPUT_BUDGET_PARAMETER_KEYS)
    model_segments = [segment for segment in request.segments if structurally_eligible(segment)]
    if not model_segments:
        return GraphExtractResponse(metadata={"schemaVersion": VERSION, "operation": "extract",
            "inputFingerprint": request.input_fingerprint, "configurationFingerprint": fingerprint,
            "planFingerprint": request.plan_fingerprint, "batchId": request.batch_id,
            "completedSourceIds": [segment["sourceId"] for segment in request.segments], "status": "completed",
            "usage": {}, "promptTokensEstimate": 0,
            "eligibilitySkippedSourceIds": [segment["sourceId"] for segment in request.segments],
            "candidateRejections": [], "candidateRejectionCount": 0,
            "candidateRejectionReasonCounts": {},
            "candidateRejectionsTruncated": False,
            "extractionOutcome": "STRUCTURALLY_INELIGIBLE_SOURCES"})
    payload = request_body(prompt, model_segments, public)
    deadline = started + min(timeout, request.budget_millis / 1000)
    body = invoke(endpoint, key, payload, deadline, public["maxResponseBytes"])
    parsed, usage, rejected = parse(body, public["outputReserve"])
    sources = {s["sourceId"]: s for s in model_segments}
    parsed = isolate_candidates(parsed, sources, public, request.options, rejected)
    entities, relations, evidences = [], [], []
    for entity in parsed.entities:
        source = sources[entity.sourceId]
        entities.append({"id": entity.id, "name": entity.name, "normalizedName": entity.name,
                         "aliases": entity.aliases, "type": entity.type, "confidence": entity.confidence,
                         "sourceChunkIds": [source["chunkId"]], "metadata": {"sourceId": entity.sourceId}})
    for relation in parsed.relations:
        relations.append({**relation.model_dump(),
                          "metadata": {"supportMode": relation.supportMode,
                                       "predicateQuoteText": relation.predicateQuoteText,
                                       "tableId": relation.tableId,
                                       "rowNo": relation.rowNo,
                                       "sourceColumnNo": relation.sourceColumnNo,
                                       "targetColumnNo": relation.targetColumnNo}})
    for evidence in parsed.evidences:
        source = sources[evidence.sourceId]
        chunk = chunks[source["chunkId"]]
        evidences.append({"id": evidence.id, "entityId": evidence.entityId, "relationId": evidence.relationId,
                          "chunkId": chunk.chunk_id, "quoteText": evidence.quoteText,
                          "parentBlockId": chunk.parent_block_id, "pageNo": chunk.page_no,
                          "pageRange": chunk.page_range, "bboxJson": chunk.bbox_json, "sectionPath": chunk.section_path,
                          "metadata": {"confidence": evidence.confidence, "sourceId": evidence.sourceId,
                                       "start": source["start"], "end": source["end"]}})
    return GraphExtractResponse(entities=entities, relations=relations, evidences=evidences,
        metadata={"schemaVersion": VERSION, "operation": "extract", "inputFingerprint": request.input_fingerprint,
                  "configurationFingerprint": fingerprint, "planFingerprint": request.plan_fingerprint,
                  "batchId": request.batch_id,
                  "completedSourceIds": [segment["sourceId"] for segment in request.segments], "status": "completed",
                  "usage": usage, "promptTokensEstimate": prompt_tokens(prompt, model_segments, public),
                  "eligibilitySkippedSourceIds": [segment["sourceId"] for segment in request.segments
                                                   if segment not in model_segments],
                  "candidateRejections": rejected.samples, "candidateRejectionCount": rejected.count,
                  "candidateRejectionReasonCounts": dict(rejected.reason_counts),
                  "candidateRejectionsTruncated": rejected.count > len(rejected.samples),
                  "extractionOutcome": ("CANDIDATES_ACCEPTED" if entities or relations or evidences else
                                        "ALL_CANDIDATES_REJECTED" if rejected.count else "MODEL_EMPTY_CANDIDATES")})


def structurally_eligible(segment):
    text = str(segment.get("text", "")).strip()
    chunk_type = str(segment.get("chunkType", "")).strip().upper()
    if chunk_type == "THEMATIC_BREAK":
        return False
    return re.fullmatch(r"(?:[-*_]\s*){3,}", text) is None


def visible_structured_tables(tables, text):
    """Carry only cells that can be grounded in this exact segment; oversized split rows disappear safely."""
    projected = []
    for table in tables:
        visible_rows = []
        visible_column_nos = set()
        for row in table.rows:
            cells = []
            row_column_nos = set()
            for cell in row.cells:
                cell_text = cell.text.strip()
                if (2 <= len(cell_text) <= 120 and cell_text in text
                        and re.fullmatch(r"[\s\S]*[^。！？.!?；;\s]", cell_text)):
                    cells.append(cell.model_dump(by_alias=True))
                    row_column_nos.add(cell.column_no)
            if len(cells) >= 2:
                visible_column_nos.update(row_column_nos)
                row_text = row.row_text.strip()
                visible_rows.append({"rowNo": row.row_no,
                                     "rowText": row_text if len(row_text) <= 500 and row_text in text else "",
                                     "cells": cells})
        columns = [column.model_dump(by_alias=True) for column in table.columns
                   if column.column_no in visible_column_nos]
        if len(columns) < 2 or not visible_rows:
            continue
        value = table.model_dump(by_alias=True)
        value["columns"] = columns
        value["rows"] = visible_rows
        projected.append(value)
    return projected


def fits(cost, public):
    # 来源预算只比来源；上下文余量必须把结构常量算进去，否则声明的容量会比真实请求乐观。
    return (cost <= public["options"]["inputTokenBudget"]
            and cost + RESPONSE_FORMAT_TOKENS_ESTIMATE + public["outputReserve"] + public["promptReserve"]
            <= public["contextTokens"])


def batch(segments, index, prompt, public):
    return {"batchId": f"b{index + 1}", "segments": segments,
            "promptTokensEstimate": prompt_tokens(prompt, segments, public)}


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)


class Entity(Strict):
    id: str = Field(min_length=1)
    sourceId: str
    name: str = Field(min_length=1)
    aliases: list[str] = Field(default_factory=list)
    type: str
    confidence: float = Field(ge=0, le=1)


class Relation(Strict):
    id: str = Field(min_length=1)
    sourceEntityId: str
    targetEntityId: str
    relationType: str = ""
    supportMode: str
    predicateQuoteText: str
    evidenceIds: list[str] = Field(min_length=1)
    description: str = ""
    confidence: float = Field(ge=0, le=1)
    tableId: int | None = None
    rowNo: int | None = Field(default=None, gt=0)
    sourceColumnNo: int | None = Field(default=None, gt=0)
    targetColumnNo: int | None = Field(default=None, gt=0)

    @model_validator(mode="after")
    def validate_support_coordinates(self):
        coordinates = (self.tableId, self.rowNo, self.sourceColumnNo, self.targetColumnNo)
        if self.supportMode == "STRUCTURED_ROW":
            if any(value is None for value in coordinates):
                raise ValueError("STRUCTURED_ROW requires table and row/column coordinates")
            if self.sourceColumnNo == self.targetColumnNo:
                raise ValueError("STRUCTURED_ROW endpoints must use different columns")
        elif any(value is not None for value in coordinates):
            raise ValueError("table coordinates are only valid for STRUCTURED_ROW")
        return self


class Evidence(Strict):
    id: str = Field(min_length=1)
    sourceId: str
    entityId: str = ""
    relationId: str = ""
    quoteText: str
    confidence: float = Field(ge=0, le=1)

    @model_validator(mode="after")
    def validate_single_owner(self):
        if not self.entityId and not self.relationId:
            raise PydanticCustomError("EVIDENCE_OWNER_MISSING", "evidence requires one owner")
        if self.entityId and self.relationId:
            raise PydanticCustomError("EVIDENCE_OWNER_MULTIPLE", "evidence cannot have two owners")
        return self


class Candidates(Strict):
    entities: list[Entity]
    relations: list[Relation]
    evidences: list[Evidence]


class CandidateRejections:
    """Bound only diagnostic samples; count and examine every candidate."""
    def __init__(self):
        self.count = 0
        self.reason_counts = Counter()
        self.samples = []
        self.sample_bytes = 2

    def append(self, record):
        self.count += 1
        reason = record.get("reason")
        if not reason and record.get("errors"):
            reason = "SCHEMA_INVALID"
        self.reason_counts[reason or "UNKNOWN"] += 1
        if "candidateId" in record:
            record["candidateId"] = record["candidateId"][:128]
        if "errors" in record:
            for error in record["errors"]:
                error["location"] = [part[:64] if isinstance(part, str) else part
                                     for part in error["location"][:8]]
        size = len(serial(record).encode("utf-8")) + 1
        if len(self.samples) < 64 and self.sample_bytes + size <= 16384:
            self.samples.append(record)
            self.sample_bytes += size


def isolate_candidates(parsed, sources, public, options, rejected):
    def keep(field, values, reason_for):
        retained = []
        counts = Counter(value.id for value in values)
        for index, value in enumerate(values):
            reason = "DUPLICATE_CANDIDATE_ID" if counts[value.id] > 1 else reason_for(value)
            if reason:
                rejected.append({"field": field, "index": index, "candidateId": value.id, "reason": reason})
            else:
                retained.append(value)
        return retained

    def entity_reason(entity):
        if len(entity.name) > public["maxEntityNameChars"]:
            return "ENTITY_NAME_RESOURCE_LIMIT"
        if entity.sourceId not in sources:
            return "UNKNOWN_MODEL_SOURCE"
        return None

    entities = keep("entities", parsed.entities, entity_reason)
    entity_ids = {entity.id for entity in entities}

    def relation_reason(relation):
        if len(relation.description) > options["maxReasonChars"]:
            return "RELATION_REASON_RESOURCE_LIMIT"
        if relation.sourceEntityId not in entity_ids or relation.targetEntityId not in entity_ids:
            return "RELATION_ENDPOINT_UNKNOWN"
        return None

    relations = keep("relations", parsed.relations, relation_reason)
    relation_by_id = {relation.id: relation for relation in relations}

    def evidence_reason(evidence):
        if evidence.sourceId not in sources:
            return "UNKNOWN_MODEL_SOURCE"
        if len(evidence.quoteText) > options["maxQuoteChars"]:
            return "QUOTE_RESOURCE_LIMIT"
        if not evidence.quoteText.strip() or evidence.quoteText not in sources[evidence.sourceId]["text"]:
            return "QUOTE_NOT_IN_SOURCE"
        if evidence.entityId:
            return None if evidence.entityId in entity_ids else "EVIDENCE_OWNER_UNKNOWN"
        owner = relation_by_id.get(evidence.relationId)
        if owner is None:
            return "EVIDENCE_OWNER_UNKNOWN"
        if evidence.id not in owner.evidenceIds:
            return "EVIDENCE_REFERENCE_UNBOUND"
        return None

    evidences = keep("evidences", parsed.evidences, evidence_reason)
    evidence_by_id = {evidence.id: evidence for evidence in evidences}
    retained_relations = []
    for relation in relations:
        bindings = [identity for identity in relation.evidenceIds if identity in evidence_by_id
                    and evidence_by_id[identity].relationId == relation.id]
        if not bindings:
            rejected.append({"field": "relations", "candidateId": relation.id,
                             "reason": "RELATION_EVIDENCE_CONTRACT_INVALID"})
        else:
            relation.evidenceIds = bindings
            retained_relations.append(relation)
    return Candidates(entities=entities, relations=retained_relations, evidences=evidences)


def invoke(endpoint, key, payload, deadline, max_response_bytes):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        fail("GRAPH_LLM_TIMEOUT", 504)
    req = urllib.request.Request(endpoint, data=serial(payload).encode("utf-8"),
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=remaining) as response:
            body = bytearray()
            read = getattr(response, "read1", response.read)
            while len(body) <= max_response_bytes:
                if time.monotonic() >= deadline:
                    fail("GRAPH_LLM_TIMEOUT", 504)
                # urllib's socket timeout is per read; shrink it with the absolute tool deadline.
                sock = getattr(getattr(getattr(response, "fp", None), "raw", None), "_sock", None)
                if sock is not None:
                    sock.settimeout(max(0.001, deadline - time.monotonic()))
                part = read(min(8192, max_response_bytes + 1 - len(body)))
                if not part:
                    break
                body.extend(part)
    except urllib.error.HTTPError as exc:
        category = ("AUTHENTICATION" if exc.code in (401, 403) else "RATE_LIMIT" if exc.code == 429
                    else "UPSTREAM_SERVER" if exc.code in (500, 502, 503, 504) else "PROTOCOL")
        # Only structured provider codes classify context overflow; never parse business/error prose.
        try:
            error = json.loads(exc.read(8192)).get("error", {})
            if error.get("code") in ("context_length_exceeded", "max_context_length_exceeded"):
                category = "CONTEXT_LIMIT"
        except (ValueError, OSError, AttributeError):
            pass
        retry_after = exc.headers.get("Retry-After", "") if exc.headers else ""
        retry_ms = int(retry_after) * 1000 if retry_after.isdigit() and len(retry_after) <= 7 else 0
        diagnostics = dict(upstreamStatus=exc.code, retryAfterMillis=retry_ms, causeType=type(exc).__name__)
        if category == "CONTEXT_LIMIT":
            diagnostics.update(actualAvailability="NOT_REPORTED", measurementKind="UNKNOWN",
                               parameterKeys=INPUT_BUDGET_PARAMETER_KEYS)
        raise GraphEntityLlmError("GRAPH_LLM_HTTP_ERROR", 503 if category in ("RATE_LIMIT", "UPSTREAM_SERVER") else 502,
                                 category, **diagnostics) from None
    except TimeoutError as exc:
        raise GraphEntityLlmError("GRAPH_LLM_READ_TIMEOUT", 504, "READ_TIMEOUT", causeType=type(exc).__name__) from None
    except urllib.error.URLError as exc:
        category = "CONNECT_TIMEOUT" if isinstance(exc.reason, TimeoutError) else "CONNECTION"
        raise GraphEntityLlmError("GRAPH_LLM_CONNECTION_FAILED", 503, category,
                                 causeType=type(exc.reason).__name__) from None
    except OSError as exc:
        raise GraphEntityLlmError("GRAPH_LLM_CONNECTION_FAILED", 503, "CONNECTION", causeType=type(exc).__name__) from None
    except ValueError as exc:
        raise GraphEntityLlmError("GRAPH_LLM_CONFIG_INVALID", 422, "CONFIGURATION", causeType=type(exc).__name__) from None
    if time.monotonic() >= deadline:
        fail("GRAPH_LLM_TIMEOUT", 504)
    if len(body) > max_response_bytes:
        fail("GRAPH_LLM_RESPONSE_TOO_LARGE", 502, resource="responseBytes", actualLength=len(body),
             configuredLimit=max_response_bytes, actualIsLowerBound=True,
             measurementKind="BYTE_MEASURED", actualAvailability="MEASURED",
             parameterKeys=["graphRag.model.modelResponseMaxBytes"])
    return body


def reported_usage(envelope):
    usage = envelope.get("usage")
    if not isinstance(usage, dict):
        return None
    measured = {name: usage[name] for name in ("prompt_tokens", "completion_tokens", "total_tokens")
                if type(usage.get(name)) is int and usage[name] >= 0}
    return measured or None


def parse(body, output_limit):
    response_bytes = len(body)
    try:
        envelope = json.loads(body)
        if not isinstance(envelope, dict):
            fail("GRAPH_LLM_INVALID_RESPONSE", 502, parseStage="envelope", responseBytes=response_bytes)
        choices = envelope.get("choices")
        if not isinstance(choices, list) or not choices:
            fail("GRAPH_LLM_INVALID_RESPONSE", 502, parseStage="choices", responseBytes=response_bytes)
        choice = choices[0]
        if not isinstance(choice, dict):
            fail("GRAPH_LLM_INVALID_RESPONSE", 502, parseStage="choice", responseBytes=response_bytes)
        if choice["finish_reason"] == "length":
            usage = reported_usage(envelope)
            diagnostics = {"usage": usage, "resource": "outputTokens", "configuredLimit": output_limit,
                           "actualAvailability": "REPORTED" if usage and "completion_tokens" in usage else "NOT_REPORTED",
                           "measurementKind": "PROVIDER_REPORTED" if usage and "completion_tokens" in usage else "UNKNOWN",
                           "parameterKeys": ["graphRag.model.outputReserve", "graphRag.extraction.inputTokenBudget",
                                             "graphRag.model.modelContextTokens", "graphRag.model.promptReserve"]}
            if usage and "completion_tokens" in usage:
                diagnostics["actualLength"] = usage["completion_tokens"]
            raise GraphEntityLlmError("GRAPH_LLM_OUTPUT_TRUNCATED", 502, "OUTPUT_TRUNCATED", **diagnostics)
        if choice["finish_reason"] != "stop":
            fail("GRAPH_LLM_INVALID_RESPONSE", 502, parseStage="finishReason",
                 finishReason=str(choice.get("finish_reason", "missing"))[:40], responseBytes=response_bytes)
        message = choice.get("message")
        if not isinstance(message, dict) or not isinstance(message.get("content"), str):
            fail("GRAPH_LLM_INVALID_RESPONSE", 502, parseStage="messageContent", responseBytes=response_bytes)
        content = message["content"]
        raw = json.loads(content)
        if not isinstance(raw, dict):
            fail("GRAPH_LLM_INVALID_RESPONSE", 502, parseStage="candidateEnvelope", responseBytes=response_bytes)
        rejected = CandidateRejections()
        entities = []
        relations = []
        evidences = []
        if any(field not in raw for field in ("entities", "relations", "evidences")):
            fail("GRAPH_LLM_INVALID_RESPONSE", 502, parseStage="candidateArray", responseBytes=response_bytes)
        for field, model, target in (("entities", Entity, entities), ("relations", Relation, relations),
                                     ("evidences", Evidence, evidences)):
            values = raw.get(field, [])
            if not isinstance(values, list):
                fail("GRAPH_LLM_INVALID_RESPONSE", 502, parseStage="candidateArray", field=field,
                     responseBytes=response_bytes)
            for index, value in enumerate(values):
                try:
                    target.append(model.model_validate(value))
                except ValidationError as exc:
                    errors = exc.errors(include_input=False, include_context=False, include_url=False)
                    reason = next((e["type"] for e in errors if e["type"] in {
                        "EVIDENCE_OWNER_MISSING", "EVIDENCE_OWNER_MULTIPLE"}), "SCHEMA_INVALID")
                    rejected.append({"field": field, "index": index, "reason": reason, "errorCount": len(errors),
                                     "errors": [{"type": e["type"], "location": list(e["loc"])}
                                                for e in errors[:8]]})
        result = Candidates(entities=entities, relations=relations, evidences=evidences)
        return result, reported_usage(envelope), rejected
    except GraphEntityLlmError:
        raise
    except json.JSONDecodeError as exc:
        fail("GRAPH_LLM_INVALID_RESPONSE", 502, parseStage="json", errorType="JSONDecodeError",
             errorOffset=exc.pos, responseBytes=response_bytes)
    except ValidationError as exc:
        fail("GRAPH_LLM_INVALID_RESPONSE", 502, parseStage="candidateSchema",
             errorType="ValidationError", errorCount=len(exc.errors()), responseBytes=response_bytes)
    except (ValueError, KeyError, TypeError, IndexError) as exc:
        fail("GRAPH_LLM_INVALID_RESPONSE", 502, parseStage="responseShape",
             errorType=type(exc).__name__, responseBytes=response_bytes)
