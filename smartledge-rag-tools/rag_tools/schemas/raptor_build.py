from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class RaptorChunk(BaseModel):
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
    metadata: dict[str, Any] = Field(default_factory=dict)


class RaptorBuildRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    document_id: int | None = Field(default=None, alias="documentId")
    task_id: int | None = Field(default=None, alias="taskId")
    max_cluster_size: int = Field(default=6, alias="maxClusterSize")
    max_levels: int = Field(default=3, alias="maxLevels")
    llm_summary_enabled: bool = Field(default=False, alias="llmSummaryEnabled")
    llm_concurrency: int | None = Field(default=None, alias="llmConcurrency")
    chunks: list[RaptorChunk] = Field(default_factory=list)


class RaptorNode(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    parent_id: str = Field(default="", alias="parentId")
    level: int
    node_no: int = Field(alias="nodeNo")
    title: str = ""
    summary: str = ""
    summary_with_weight: str = Field(default="", alias="summaryWithWeight")
    child_node_ids: list[str] = Field(default_factory=list, alias="childNodeIds")
    source_chunk_ids: list[int] = Field(default_factory=list, alias="sourceChunkIds")
    source_parent_block_ids: list[int] = Field(default_factory=list, alias="sourceParentBlockIds")
    section_path: str = Field(default="", alias="sectionPath")
    page_range: str = Field(default="", alias="pageRange")
    keywords: list[str] = Field(default_factory=list)
    questions: list[str] = Field(default_factory=list)
    quality_score: float = Field(default=0.0, alias="qualityScore")
    metadata: dict[str, Any] = Field(default_factory=dict)


class RaptorBuildResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    nodes: list[RaptorNode] = Field(default_factory=list)
