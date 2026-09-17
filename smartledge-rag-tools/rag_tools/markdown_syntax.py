import hashlib
import re
from bisect import bisect_right
from dataclasses import dataclass
from typing import Any

from markdown_it import MarkdownIt
from markdown_it.token import Token

from rag_tools.schemas.document_parse import (
    MARKDOWN_SYNTAX_SCHEMA_VERSION,
    DocumentBlock,
    MarkdownSourceSpan,
    MarkdownSyntaxDocument,
    MarkdownSyntaxNode,
    json_metadata,
)


_OPEN_NODE_TYPES = {
    "heading_open": "HEADING",
    "paragraph_open": "PARAGRAPH",
    "ordered_list_open": "ORDERED_LIST",
    "bullet_list_open": "UNORDERED_LIST",
    "list_item_open": "LIST_ITEM",
    "blockquote_open": "BLOCKQUOTE",
    "table_open": "TABLE",
    "thead_open": "TABLE_HEAD",
    "tbody_open": "TABLE_BODY",
    "tr_open": "TABLE_ROW",
    "th_open": "TABLE_CELL",
    "td_open": "TABLE_CELL",
}

_CLOSE_NODE_TYPES = {
    token_type.replace("_open", "_close"): node_type
    for token_type, node_type in _OPEN_NODE_TYPES.items()
}

_LEAF_NODE_TYPES = {
    "fence": "CODE_BLOCK",
    "code_block": "CODE_BLOCK",
    "hr": "THEMATIC_BREAK",
    "html_block": "HTML_BLOCK",
}


@dataclass(frozen=True)
class _SourceIndex:
    source_text: str
    line_starts: list[int]
    byte_offsets: list[int]

    @classmethod
    def build(cls, source_text: str) -> "_SourceIndex":
        line_starts = [0]
        for match in re.finditer(r"\r\n|\n|\r", source_text):
            line_starts.append(match.end())
        byte_offsets = [0]
        byte_total = 0
        for character in source_text:
            byte_total += len(character.encode("utf-8"))
            byte_offsets.append(byte_total)
        return cls(source_text, line_starts, byte_offsets)

    def character_span(self, line_map: list[int] | None) -> tuple[int, int] | None:
        if not line_map or len(line_map) != 2:
            return None
        start_line, end_line = line_map
        if start_line < 0 or end_line < start_line:
            raise ValueError(f"invalid markdown token line map: {line_map}")
        start = self.line_starts[start_line] if start_line < len(self.line_starts) else len(self.source_text)
        end = self.line_starts[end_line] if end_line < len(self.line_starts) else len(self.source_text)
        return start, end

    def source_span(self, character_span: tuple[int, int]) -> MarkdownSourceSpan:
        start, end = character_span
        start_line, start_column = self.line_column(start)
        end_line, end_column = self.line_column(end)
        return MarkdownSourceSpan(
            startByte=self.byte_offsets[start],
            endByte=self.byte_offsets[end],
            startLine=start_line,
            startColumn=start_column,
            endLine=end_line,
            endColumn=end_column,
        )

    def line_column(self, character_offset: int) -> tuple[int, int]:
        line_index = max(0, bisect_right(self.line_starts, character_offset) - 1)
        return line_index + 1, character_offset - self.line_starts[line_index] + 1

    def table_cell_span(self, row_span: tuple[int, int], column_index: int) -> tuple[int, int]:
        row_start, row_end = row_span
        row_text = self.source_text[row_start:row_end]
        row_text = row_text.removesuffix("\r\n").removesuffix("\n").removesuffix("\r")
        left_trimmed = row_text.lstrip()
        content_start = row_start + len(row_text) - len(left_trimmed)
        content = left_trimmed.rstrip()
        content_end = content_start + len(content)

        segments = []
        segment_start = 0
        escaped = False
        for index, character in enumerate(content):
            if character == "|" and not escaped:
                segments.append((segment_start, index))
                segment_start = index + 1
            escaped = character == "\\"
        segments.append((segment_start, len(content)))

        if segments and segments[0] == (0, 0):
            segments.pop(0)
        if segments and segments[-1] == (len(content), len(content)):
            segments.pop()
        if column_index >= len(segments):
            return content_end, content_end

        start, end = segments[column_index]
        cell_text = content[start:end]
        trimmed_left = cell_text.lstrip()
        start += len(cell_text) - len(trimmed_left)
        end = start + len(trimmed_left.rstrip())
        return content_start + start, content_start + end


def parse_markdown_syntax(source_text: str, source_origin: str) -> MarkdownSyntaxDocument:
    parser = _markdown_parser()
    tokens = parser.parse(source_text)
    source_index = _SourceIndex.build(source_text)
    drafts: list[dict[str, Any]] = []
    drafts_by_id: dict[str, dict[str, Any]] = {}
    stack: list[str] = []
    row_count_by_table: dict[str, int] = {}
    column_count_by_row: dict[str, int] = {}

    def append_node(node_type: str,
                    parent_node_id: str | None,
                    character_span: tuple[int, int] | None,
                    **fields) -> dict[str, Any]:
        order = len(drafts)
        node_id = f"md-{order:06d}"
        draft = {
            "order": order,
            "nodeId": node_id,
            "parentNodeId": parent_node_id,
            "nodeType": node_type,
            "origin": source_origin,
            "characterSpan": character_span,
            "text": "",
            "level": None,
            "marker": None,
            "ordinal": None,
            "header": None,
            "alignment": None,
            "rowIndex": None,
            "columnIndex": None,
            "info": None,
        }
        draft.update(fields)
        drafts.append(draft)
        drafts_by_id[node_id] = draft
        return draft

    root = append_node("DOCUMENT", None, (0, len(source_text)))
    stack.append(root["nodeId"])

    for token in tokens:
        if token.type in _OPEN_NODE_TYPES and token.nesting == 1:
            node_type = _OPEN_NODE_TYPES[token.type]
            parent_id = stack[-1]
            fields = _opening_fields(token, node_type, parent_id, drafts_by_id,
                                     row_count_by_table, column_count_by_row, source_index)
            character_span = fields.pop("_characterSpan", source_index.character_span(token.map))
            draft = append_node(node_type, parent_id, character_span, **fields)
            stack.append(draft["nodeId"])
            continue

        if token.type == "inline":
            if len(stack) <= 1:
                raise ValueError("markdown inline token has no structural parent")
            draft = drafts_by_id[stack[-1]]
            draft["text"] = _inline_text(token)
            if draft["characterSpan"] is None:
                draft["characterSpan"] = source_index.character_span(token.map)
            continue

        if token.type in _CLOSE_NODE_TYPES and token.nesting == -1:
            expected_type = _CLOSE_NODE_TYPES[token.type]
            if len(stack) <= 1 or drafts_by_id[stack[-1]]["nodeType"] != expected_type:
                raise ValueError(f"unbalanced markdown token: {token.type}")
            stack.pop()
            continue

        if token.type in _LEAF_NODE_TYPES:
            node_type = _LEAF_NODE_TYPES[token.type]
            append_node(
                node_type,
                stack[-1],
                source_index.character_span(token.map),
                text=token.content.rstrip("\n"),
                marker=token.markup or None,
                info=(token.info or "").strip() or None,
            )

    if stack != [root["nodeId"]]:
        raise ValueError("markdown token stream ended with unclosed structural nodes")

    _fill_missing_spans(drafts, drafts_by_id)
    _fill_container_text(drafts)

    nodes = []
    for draft in drafts:
        character_span = draft.pop("characterSpan")
        draft.pop("_rowIndex", None)
        if character_span is None:
            raise ValueError(f"markdown node {draft['nodeId']} has no source span")
        draft["sourceSpan"] = source_index.source_span(character_span)
        nodes.append(MarkdownSyntaxNode.model_validate(draft))

    source_bytes = source_text.encode("utf-8")
    return MarkdownSyntaxDocument(
        schemaVersion=MARKDOWN_SYNTAX_SCHEMA_VERSION,
        sourceOrigin=source_origin,
        sourceText=source_text,
        sourceLengthBytes=len(source_bytes),
        sourceSha256=hashlib.sha256(source_bytes).hexdigest(),
        nodes=nodes,
    )


def markdown_syntax_to_blocks(syntax: MarkdownSyntaxDocument, parser_name: str) -> list[DocumentBlock]:
    root = syntax.nodes[0]
    top_level = [node for node in syntax.nodes if node.parent_node_id == root.node_id]
    children_by_parent: dict[str, list[MarkdownSyntaxNode]] = {}
    for node in syntax.nodes:
        if node.parent_node_id is not None:
            children_by_parent.setdefault(node.parent_node_id, []).append(node)

    blocks = []
    for node in top_level:
        raw_text = _node_source_text(syntax, node)
        block_type, text = _legacy_block_content(node, raw_text)
        if not text and block_type != "TABLE":
            continue
        table_rows = _table_rows(node, children_by_parent) if node.node_type == "TABLE" else []
        if block_type == "TABLE" and not text:
            text = "\n".join(" | ".join(row) for row in table_rows)
        table_html = _markdown_parser().render(raw_text) if block_type == "TABLE" else ""
        metadata = {
            "parser": parser_name,
            "syntaxSchemaVersion": syntax.schema_version,
            "syntaxNodeId": node.node_id,
            "syntaxNodeType": node.node_type,
            "sourceOrigin": node.origin,
            "sourceSpan": node.source_span.model_dump(by_alias=True),
        }
        if node.level is not None:
            metadata["headingLevel"] = node.level
        if node.marker:
            metadata["originalMarker"] = node.marker
        blocks.append(DocumentBlock(
            blockNo=len(blocks) + 1,
            blockType=block_type,
            text=text,
            tableHtml=table_html,
            tableRows=table_rows,
            metadataJson=json_metadata(metadata),
        ))
    return blocks


def _markdown_parser() -> MarkdownIt:
    return MarkdownIt("commonmark", {"html": True}).enable("table")


def _opening_fields(token: Token,
                    node_type: str,
                    parent_id: str,
                    drafts_by_id: dict[str, dict[str, Any]],
                    row_count_by_table: dict[str, int],
                    column_count_by_row: dict[str, int],
                    source_index: _SourceIndex) -> dict[str, Any]:
    fields: dict[str, Any] = {}
    if node_type == "HEADING":
        fields["level"] = int(token.tag[1:])
        fields["marker"] = token.markup
    elif node_type == "ORDERED_LIST":
        start = token.attrGet("start")
        if start is not None:
            fields["ordinal"] = int(start)
    elif node_type == "LIST_ITEM":
        parent_type = drafts_by_id[parent_id]["nodeType"]
        if parent_type == "ORDERED_LIST":
            fields["ordinal"] = int(token.info)
            fields["marker"] = f"{token.info}{token.markup}"
        else:
            fields["marker"] = token.markup
    elif node_type == "TABLE_ROW":
        table_id = _ancestor_id(parent_id, "TABLE", drafts_by_id)
        row_index = row_count_by_table.get(table_id, 0)
        row_count_by_table[table_id] = row_index + 1
        fields["_rowIndex"] = row_index
    elif node_type == "TABLE_CELL":
        row_id = _ancestor_id(parent_id, "TABLE_ROW", drafts_by_id)
        row = drafts_by_id[row_id]
        column_index = column_count_by_row.get(row_id, 0)
        column_count_by_row[row_id] = column_index + 1
        fields.update({
            "_characterSpan": source_index.table_cell_span(row["characterSpan"], column_index),
            "header": token.type == "th_open",
            "alignment": _table_alignment(token.attrGet("style")),
            "rowIndex": row["_rowIndex"],
            "columnIndex": column_index,
        })
    return fields


def _ancestor_id(node_id: str,
                 node_type: str,
                 drafts_by_id: dict[str, dict[str, Any]]) -> str:
    current_id: str | None = node_id
    while current_id is not None:
        current = drafts_by_id[current_id]
        if current["nodeType"] == node_type:
            return current_id
        current_id = current["parentNodeId"]
    raise ValueError(f"markdown {node_type} ancestor is missing")


def _table_alignment(style: str | None) -> str | None:
    if not style:
        return None
    match = re.search(r"text-align\s*:\s*(left|center|right)", style, re.IGNORECASE)
    return match.group(1).upper() if match else None


def _inline_text(token: Token) -> str:
    parts = []
    for child in token.children or []:
        if child.type in {"text", "code_inline", "html_inline"}:
            parts.append(child.content)
        elif child.type in {"softbreak", "hardbreak"}:
            parts.append("\n")
        elif child.type == "image":
            parts.append(child.content)
    return "".join(parts)


def _fill_missing_spans(drafts: list[dict[str, Any]], drafts_by_id: dict[str, dict[str, Any]]) -> None:
    children_by_parent: dict[str, list[dict[str, Any]]] = {}
    for draft in drafts:
        parent_id = draft["parentNodeId"]
        if parent_id is not None:
            children_by_parent.setdefault(parent_id, []).append(draft)
    for draft in reversed(drafts):
        if draft["characterSpan"] is not None:
            continue
        children = [child for child in children_by_parent.get(draft["nodeId"], []) if child["characterSpan"]]
        if children:
            draft["characterSpan"] = (
                min(child["characterSpan"][0] for child in children),
                max(child["characterSpan"][1] for child in children),
            )
            continue
        parent_id = draft["parentNodeId"]
        if parent_id is not None:
            draft["characterSpan"] = drafts_by_id[parent_id]["characterSpan"]


def _fill_container_text(drafts: list[dict[str, Any]]) -> None:
    children_by_parent: dict[str, list[dict[str, Any]]] = {}
    for draft in drafts:
        parent_id = draft["parentNodeId"]
        if parent_id is not None:
            children_by_parent.setdefault(parent_id, []).append(draft)
    for draft in reversed(drafts):
        if draft["text"] or draft["nodeType"] == "DOCUMENT":
            continue
        children = children_by_parent.get(draft["nodeId"], [])
        texts = [child["text"] for child in children if child["text"]]
        if draft["nodeType"] == "TABLE_ROW":
            draft["text"] = " | ".join(child["text"] for child in children if child["nodeType"] == "TABLE_CELL")
        elif texts:
            draft["text"] = "\n".join(texts)


def _legacy_block_content(node: MarkdownSyntaxNode, source_text: str) -> tuple[str, str]:
    raw_text = source_text.strip()
    if node.node_type == "HEADING":
        return "TITLE", node.text.strip()
    if node.node_type in {"ORDERED_LIST", "UNORDERED_LIST"}:
        return "LIST", raw_text
    if node.node_type == "TABLE":
        return "TABLE", node.text.strip()
    if node.node_type == "CODE_BLOCK":
        return "CODE", raw_text
    if node.node_type == "BLOCKQUOTE":
        return "BLOCKQUOTE", raw_text
    if node.node_type == "THEMATIC_BREAK":
        return "THEMATIC_BREAK", raw_text
    if node.node_type == "HTML_BLOCK":
        return "HTML", raw_text
    return "TEXT", raw_text


def _node_source_text(syntax: MarkdownSyntaxDocument, node: MarkdownSyntaxNode) -> str:
    source_bytes = syntax.source_text.encode("utf-8")
    return source_bytes[node.source_span.start_byte:node.source_span.end_byte].decode("utf-8")


def _table_rows(table: MarkdownSyntaxNode,
                children_by_parent: dict[str, list[MarkdownSyntaxNode]]) -> list[list[str]]:
    descendants = []
    pending = list(children_by_parent.get(table.node_id, []))
    while pending:
        node = pending.pop(0)
        descendants.append(node)
        pending[0:0] = children_by_parent.get(node.node_id, [])
    cells = [node for node in descendants if node.node_type == "TABLE_CELL"]
    if not cells:
        return []
    row_count = max(cell.row_index or 0 for cell in cells) + 1
    column_count = max(cell.column_index or 0 for cell in cells) + 1
    rows = [["" for _ in range(column_count)] for _ in range(row_count)]
    for cell in cells:
        rows[cell.row_index][cell.column_index] = cell.text
    return rows
