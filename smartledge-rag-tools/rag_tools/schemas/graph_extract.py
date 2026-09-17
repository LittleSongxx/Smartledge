from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class GraphStructuredTableCell(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    column_no: int = Field(gt=0, alias="columnNo")
    text: str


class GraphStructuredTableRow(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    row_no: int = Field(gt=0, alias="rowNo")
    row_text: str = Field(alias="rowText")
    cells: list[GraphStructuredTableCell] = Field(min_length=2)


class GraphStructuredTableColumn(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    column_no: int = Field(gt=0, alias="columnNo")
    column_name: str = Field(min_length=1, alias="columnName")


class GraphStructuredTable(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    table_id: int = Field(alias="tableId")
    block_id: int = Field(alias="blockId")
    table_no: int | None = Field(default=None, alias="tableNo")
    title: str = ""
    columns: list[GraphStructuredTableColumn] = Field(min_length=2)
    rows: list[GraphStructuredTableRow] = Field(min_length=1)


class GraphChunk(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    chunk_id: int | None = Field(default=None, alias="chunkId")
    parent_block_id: int | None = Field(default=None, alias="parentBlockId")
    chunk_no: int | None = Field(default=None, alias="chunkNo")
    chunk_type: str = Field(default="", alias="chunkType")
    title: str = ""
    section_path: str = Field(default="", alias="sectionPath")
    page_no: int | None = Field(default=None, alias="pageNo")
    page_range: str = Field(default="", alias="pageRange")
    bbox_json: str = Field(default="", alias="bboxJson")
    text: str = ""
    content_with_weight: str = Field(default="", alias="contentWithWeight")
    source_block_ids: str = Field(default="", alias="sourceBlockIds")
    structured_tables: list[GraphStructuredTable] = Field(default_factory=list, alias="structuredTables")
    metadata: dict[str, Any] = Field(default_factory=dict)


class GraphExtractRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    schema_version: Literal["graph-candidates.v3"] = Field(alias="schemaVersion")
    operation: Literal["plan", "extract"]
    source_parse_task_id: int = Field(alias="sourceParseTaskId")
    plan_id: int | None = Field(default=None, alias="planId")
    excluded_blank_chunk_ids: list[int] = Field(default_factory=list, alias="excludedBlankChunkIds")
    input_fingerprint: str = Field(min_length=64, max_length=64, alias="inputFingerprint")
    configuration_fingerprint: str | None = Field(default=None, alias="configurationFingerprint")
    plan_fingerprint: str | None = Field(default=None, alias="planFingerprint")
    batch_id: str | None = Field(default=None, alias="batchId")
    budget_millis: int = Field(gt=0, alias="budgetMillis")
    options: dict[str, Any]
    segments: list[dict[str, Any]] = Field(default_factory=list)

    document_id: int | None = Field(default=None, alias="documentId")
    task_id: int | None = Field(default=None, alias="taskId")
    chunks: list[GraphChunk] = Field(default_factory=list)


class GraphEntity(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    name: str
    normalized_name: str = Field(alias="normalizedName")
    aliases: list[str] = Field(default_factory=list)
    type: str = "CONCEPT"
    description: str = ""
    confidence: float = 0.0
    source_chunk_ids: list[int] = Field(default_factory=list, alias="sourceChunkIds")
    evidence_ids: list[str] = Field(default_factory=list, alias="evidenceIds")
    metadata: dict[str, Any] = Field(default_factory=dict)


class GraphEvidence(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    entity_id: str = Field(default="", alias="entityId")
    relation_id: str = Field(default="", alias="relationId")
    chunk_id: int | None = Field(default=None, alias="chunkId")
    parent_block_id: int | None = Field(default=None, alias="parentBlockId")
    quote_text: str = Field(default="", alias="quoteText")
    page_no: int | None = Field(default=None, alias="pageNo")
    page_range: str = Field(default="", alias="pageRange")
    bbox_json: str = Field(default="", alias="bboxJson")
    section_path: str = Field(default="", alias="sectionPath")
    metadata: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_single_owner(self):
        if bool(self.entity_id) == bool(self.relation_id):
            raise ValueError("evidence must have exactly one owner: entityId or relationId")
        return self


class GraphRelation(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    source_entity_id: str = Field(alias="sourceEntityId")
    target_entity_id: str = Field(alias="targetEntityId")
    relation_type: str = Field(alias="relationType")
    support_mode: str = Field(alias="supportMode")
    predicate_quote_text: str = Field(alias="predicateQuoteText")
    description: str = ""
    confidence: float = 0.0
    weight: float = 1.0
    evidence_ids: list[str] = Field(min_length=1, alias="evidenceIds")
    metadata: dict[str, Any] = Field(default_factory=dict)


class GraphCommunity(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    title: str
    summary: str = ""
    entity_ids: list[str] = Field(default_factory=list, alias="entityIds")
    relation_ids: list[str] = Field(default_factory=list, alias="relationIds")
    evidence_ids: list[str] = Field(default_factory=list, alias="evidenceIds")
    metadata: dict[str, Any] = Field(default_factory=dict)


class GraphExtractResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    entities: list[GraphEntity] = Field(default_factory=list)
    relations: list[GraphRelation] = Field(default_factory=list)
    evidences: list[GraphEvidence] = Field(default_factory=list)
    communities: list[GraphCommunity] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)
