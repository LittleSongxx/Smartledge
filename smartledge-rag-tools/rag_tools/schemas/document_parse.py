import hashlib
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


MARKDOWN_SYNTAX_SCHEMA_VERSION = "markdown-syntax.v1"
MarkdownSourceOrigin = Literal["SOURCE_MARKDOWN", "PROVIDER_MARKDOWN"]
MarkdownNodeType = Literal[
    "DOCUMENT",
    "HEADING",
    "PARAGRAPH",
    "ORDERED_LIST",
    "UNORDERED_LIST",
    "LIST_ITEM",
    "BLOCKQUOTE",
    "CODE_BLOCK",
    "THEMATIC_BREAK",
    "TABLE",
    "TABLE_HEAD",
    "TABLE_BODY",
    "TABLE_ROW",
    "TABLE_CELL",
    "HTML_BLOCK",
]
MarkdownAlignment = Literal["LEFT", "CENTER", "RIGHT"]


class MarkdownSourceSpan(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    start_byte: int = Field(alias="startByte", ge=0)
    end_byte: int = Field(alias="endByte", ge=0)
    start_line: int = Field(alias="startLine", ge=1)
    start_column: int = Field(alias="startColumn", ge=1)
    end_line: int = Field(alias="endLine", ge=1)
    end_column: int = Field(alias="endColumn", ge=1)

    @model_validator(mode="after")
    def validate_bounds(self):
        if self.end_byte < self.start_byte:
            raise ValueError("endByte must be greater than or equal to startByte")
        if (self.end_line, self.end_column) < (self.start_line, self.start_column):
            raise ValueError("source span end position must not precede its start position")
        return self


class MarkdownSyntaxNode(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    order: int = Field(ge=0)
    node_id: str = Field(alias="nodeId", min_length=1)
    parent_node_id: str | None = Field(default=None, alias="parentNodeId")
    node_type: MarkdownNodeType = Field(alias="nodeType")
    origin: MarkdownSourceOrigin
    source_span: MarkdownSourceSpan = Field(alias="sourceSpan")
    text: str = ""
    level: int | None = Field(default=None, ge=1, le=6)
    marker: str | None = None
    ordinal: int | None = Field(default=None, ge=0)
    header: bool | None = None
    alignment: MarkdownAlignment | None = None
    row_index: int | None = Field(default=None, alias="rowIndex", ge=0)
    column_index: int | None = Field(default=None, alias="columnIndex", ge=0)
    info: str | None = None

    @model_validator(mode="after")
    def validate_type_fields(self):
        if self.node_type == "DOCUMENT" and self.parent_node_id is not None:
            raise ValueError("DOCUMENT node must not have a parentNodeId")
        if self.node_type == "HEADING":
            if self.level is None or not self.marker:
                raise ValueError("HEADING node requires level and marker")
        elif self.level is not None:
            raise ValueError(f"{self.node_type} node must not define level")
        if self.node_type == "LIST_ITEM" and not self.marker:
            raise ValueError("LIST_ITEM node requires its original marker")
        if self.node_type == "TABLE_CELL":
            if self.header is None or self.row_index is None or self.column_index is None:
                raise ValueError("TABLE_CELL node requires header, rowIndex and columnIndex")
        elif any(value is not None for value in (self.header, self.alignment, self.row_index, self.column_index)):
            raise ValueError(f"{self.node_type} node must not define table-cell fields")
        return self


class MarkdownSyntaxDocument(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    schema_version: Literal[MARKDOWN_SYNTAX_SCHEMA_VERSION] = Field(alias="schemaVersion")
    source_origin: MarkdownSourceOrigin = Field(alias="sourceOrigin")
    source_text: str = Field(alias="sourceText")
    source_length_bytes: int = Field(alias="sourceLengthBytes", ge=0)
    source_sha256: str = Field(alias="sourceSha256", pattern=r"^[0-9a-f]{64}$")
    nodes: list[MarkdownSyntaxNode] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_document(self):
        source_bytes = self.source_text.encode("utf-8")
        if self.source_length_bytes != len(source_bytes):
            raise ValueError("sourceLengthBytes does not match UTF-8 sourceText length")
        if self.source_sha256 != hashlib.sha256(source_bytes).hexdigest():
            raise ValueError("sourceSha256 does not match sourceText")

        if [node.order for node in self.nodes] != list(range(len(self.nodes))):
            raise ValueError("nodes must have contiguous preorder values starting at zero")
        if len({node.node_id for node in self.nodes}) != len(self.nodes):
            raise ValueError("nodeId values must be unique")

        root = self.nodes[0]
        if root.node_type != "DOCUMENT" or root.parent_node_id is not None:
            raise ValueError("the first node must be the parentless DOCUMENT root")
        if root.source_span.start_byte != 0 or root.source_span.end_byte != len(source_bytes):
            raise ValueError("DOCUMENT root span must cover the complete UTF-8 source")

        nodes_by_id: dict[str, MarkdownSyntaxNode] = {}
        for node in self.nodes:
            if node.origin != self.source_origin:
                raise ValueError(f"node {node.node_id} origin does not match sourceOrigin")
            span = node.source_span
            if span.end_byte > len(source_bytes):
                raise ValueError(f"node {node.node_id} sourceSpan exceeds sourceText")
            try:
                source_bytes[span.start_byte:span.end_byte].decode("utf-8")
            except UnicodeDecodeError as exception:
                raise ValueError(f"node {node.node_id} sourceSpan splits a UTF-8 code point") from exception

            if node is not root and node.parent_node_id is None:
                raise ValueError(f"node {node.node_id} must belong to the DOCUMENT root")
            if node.parent_node_id is not None:
                parent = nodes_by_id.get(node.parent_node_id)
                if parent is None:
                    raise ValueError(f"node {node.node_id} parent must precede the node")
                if (span.start_byte < parent.source_span.start_byte
                        or span.end_byte > parent.source_span.end_byte):
                    raise ValueError(f"node {node.node_id} sourceSpan must be contained by its parent")
                if parent.node_type == "ORDERED_LIST" and node.node_type == "LIST_ITEM" and node.ordinal is None:
                    raise ValueError("ordered LIST_ITEM requires ordinal")
                if parent.node_type == "UNORDERED_LIST" and node.node_type == "LIST_ITEM" and node.ordinal is not None:
                    raise ValueError("unordered LIST_ITEM must not define ordinal")
            nodes_by_id[node.node_id] = node
        self._validate_table_structure(nodes_by_id)
        return self

    def _validate_table_structure(self, nodes_by_id: dict[str, MarkdownSyntaxNode]) -> None:
        children_by_parent: dict[str, list[MarkdownSyntaxNode]] = {}
        for node in self.nodes:
            if node.parent_node_id is not None:
                children_by_parent.setdefault(node.parent_node_id, []).append(node)
            parent = nodes_by_id.get(node.parent_node_id or "")
            if node.node_type in {"TABLE_HEAD", "TABLE_BODY"}:
                if parent is None or parent.node_type != "TABLE":
                    raise ValueError(f"{node.node_type} parent must be TABLE: {node.node_id}")
            elif node.node_type == "TABLE_ROW":
                if parent is None or parent.node_type not in {"TABLE_HEAD", "TABLE_BODY"}:
                    raise ValueError(f"TABLE_ROW parent must be TABLE_HEAD/TABLE_BODY: {node.node_id}")
            elif node.node_type == "TABLE_CELL":
                if parent is None or parent.node_type != "TABLE_ROW":
                    raise ValueError(f"TABLE_CELL parent must be TABLE_ROW: {node.node_id}")

        for table in (node for node in self.nodes if node.node_type == "TABLE"):
            table_children = children_by_parent.get(table.node_id, [])
            heads = [node for node in table_children if node.node_type == "TABLE_HEAD"]
            bodies = [node for node in table_children if node.node_type == "TABLE_BODY"]
            if len(heads) != 1 or len(bodies) != 1 or len(table_children) != 2:
                raise ValueError(f"TABLE must contain exactly one TABLE_HEAD and TABLE_BODY: {table.node_id}")
            header_rows = children_by_parent.get(heads[0].node_id, [])
            if len(header_rows) != 1 or header_rows[0].node_type != "TABLE_ROW":
                raise ValueError(f"TABLE_HEAD must contain exactly one TABLE_ROW: {table.node_id}")
            body_rows = children_by_parent.get(bodies[0].node_id, [])
            if any(node.node_type != "TABLE_ROW" for node in body_rows):
                raise ValueError(f"TABLE_BODY may only contain TABLE_ROW: {table.node_id}")
            rows = [header_rows[0], *body_rows]
            self._validate_table_rows(table, rows, children_by_parent)

    def _validate_table_rows(self,
                             table: MarkdownSyntaxNode,
                             rows: list[MarkdownSyntaxNode],
                             children_by_parent: dict[str, list[MarkdownSyntaxNode]]) -> None:
        column_count: int | None = None
        header_alignments: list[MarkdownAlignment | None] = []
        for expected_row_index, row in enumerate(rows):
            cells = children_by_parent.get(row.node_id, [])
            if not cells or any(node.node_type != "TABLE_CELL" for node in cells):
                raise ValueError(f"TABLE_ROW must contain TABLE_CELL nodes: {row.node_id}")
            expected_header = expected_row_index == 0
            for expected_column_index, cell in enumerate(cells):
                if cell.row_index != expected_row_index or cell.column_index != expected_column_index:
                    raise ValueError(f"TABLE_CELL rowIndex/columnIndex must be contiguous: {cell.node_id}")
                if cell.header is not expected_header:
                    container = "TABLE_HEAD" if expected_header else "TABLE_BODY"
                    raise ValueError(f"{container} cell header flag mismatch: {cell.node_id}")
            if column_count is None:
                column_count = len(cells)
                header_alignments = [cell.alignment for cell in cells]
            elif len(cells) != column_count:
                raise ValueError(f"TABLE row column count mismatch: {table.node_id}")
            if [cell.alignment for cell in cells] != header_alignments:
                raise ValueError(f"TABLE column alignment mismatch: {row.node_id}")


class DocumentParseRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    file_name: str = Field(default="", alias="fileName")
    mime_type: str = Field(default="", alias="mimeType")
    file_type: str = Field(default="", alias="fileType")
    content_base64: str = Field(default="", alias="contentBase64")


class ParseArtifact(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    artifact_type: str = Field(alias="artifactType")
    file_name: str = Field(alias="fileName")
    content_type: str = Field(default="application/octet-stream", alias="contentType")
    content_base64: str = Field(alias="contentBase64")
    content_hash: str = Field(default="", alias="contentHash")
    parser_name: str = Field(default="", alias="parserName")
    parser_version: str = Field(default="", alias="parserVersion")


class DocumentBlock(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    block_no: int = Field(alias="blockNo")
    block_type: str = Field(alias="blockType")
    parent_block_no: int | None = Field(default=None, alias="parentBlockNo")
    section_path: str = Field(default="", alias="sectionPath")
    canonical_path: str = Field(default="", alias="canonicalPath")
    page_no: int | None = Field(default=None, alias="pageNo")
    page_range: str = Field(default="", alias="pageRange")
    bbox_json: str = Field(default="", alias="bboxJson")
    text: str = ""
    content_with_weight: str = Field(default="", alias="contentWithWeight")
    table_html: str = Field(default="", alias="tableHtml")
    table_rows: list[list[str]] = Field(default_factory=list, alias="tableRows")
    image_file_name: str = Field(default="", alias="imageFileName")
    image_content_base64: str = Field(default="", alias="imageContentBase64")
    image_caption: str = Field(default="", alias="imageCaption")
    metadata_json: str = Field(default="", alias="metadataJson")


class DocumentParseResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    parsed_text: str = Field(default="", alias="parsedText")
    char_count: int = Field(default=0, alias="charCount")
    token_count: int = Field(default=0, alias="tokenCount")
    structure_level: int = Field(default=0, alias="structureLevel")
    content_quality_level: int = Field(default=0, alias="contentQualityLevel")
    heading_count: int = Field(default=0, alias="headingCount")
    paragraph_count: int = Field(default=0, alias="paragraphCount")
    max_paragraph_length: int = Field(default=0, alias="maxParagraphLength")
    artifacts: list[ParseArtifact] = Field(default_factory=list)
    blocks: list[DocumentBlock] = Field(default_factory=list)
    provider_name: str = Field(default="", alias="providerName")
    provider_version: str = Field(default="", alias="providerVersion")
    capabilities: list[str] = Field(default_factory=list)
    elapsed_ms: int = Field(default=0, alias="elapsedMs")
    warnings: list[str] = Field(default_factory=list)
    failed_reason: str = Field(default="", alias="failedReason")
    trace_metadata: dict[str, Any] = Field(default_factory=dict, alias="traceMetadata")
    markdown_syntax: MarkdownSyntaxDocument | None = Field(default=None, alias="markdownSyntax")


def json_metadata(data: dict[str, Any]) -> str:
    import json

    return json.dumps(data, ensure_ascii=False, separators=(",", ":"))
