import base64
import hashlib
import json
import unittest

from pydantic import ValidationError

from rag_tools import document_parser
from rag_tools.document_parser import parse_document
from rag_tools.schemas.document_parse import DocumentParseRequest, MarkdownSyntaxDocument


class MarkdownDocumentParserTest(unittest.TestCase):
    def test_shared_syntax_preserves_headings_lists_adjacent_text_and_source_spans(self) -> None:
        source = (
            "# 操作指南\n"
            "## 第十章 运行规则\n"
            "### 10.1 启动\n"
            "1. 准备环境\n"
            "2. 启动服务\n"
            "\n"
            "相邻正文。\n"
            "### 10.2 停止\n"
            "- 停止入口\n"
            "- 核对状态\n"
            "#### 相邻标题\n"
            "##### 下一标题\n"
            "正文结束。\n"
        )

        response = self._parse(source)
        syntax = response.markdown_syntax

        self.assertIsNotNone(syntax)
        self.assertEqual("markdown-syntax.v1", syntax.schema_version)
        self.assertEqual("SOURCE_MARKDOWN", syntax.source_origin)
        self.assertEqual(source, syntax.source_text)
        self.assertEqual(len(source.encode("utf-8")), syntax.source_length_bytes)
        self.assertEqual(hashlib.sha256(source.encode("utf-8")).hexdigest(), syntax.source_sha256)

        root = syntax.nodes[0]
        self.assertEqual("DOCUMENT", root.node_type)
        self.assertIsNone(root.parent_node_id)
        top_level = [node for node in syntax.nodes if node.parent_node_id == root.node_id]
        self.assertEqual(
            [
                "HEADING", "HEADING", "HEADING", "ORDERED_LIST", "PARAGRAPH",
                "HEADING", "UNORDERED_LIST", "HEADING", "HEADING", "PARAGRAPH",
            ],
            [node.node_type for node in top_level],
        )
        self.assertEqual([1, 2, 3, 3, 4, 5], [node.level for node in syntax.nodes if node.node_type == "HEADING"])
        self.assertEqual(
            ["操作指南", "第十章 运行规则", "10.1 启动", "10.2 停止", "相邻标题", "下一标题"],
            [node.text for node in syntax.nodes if node.node_type == "HEADING"],
        )

        ordered_list = next(node for node in syntax.nodes if node.node_type == "ORDERED_LIST")
        ordered_items = [node for node in syntax.nodes if node.parent_node_id == ordered_list.node_id]
        self.assertEqual(["1.", "2."], [node.marker for node in ordered_items])
        self.assertEqual([1, 2], [node.ordinal for node in ordered_items])
        self.assertEqual(["LIST_ITEM", "LIST_ITEM"], [node.node_type for node in ordered_items])

        source_bytes = source.encode("utf-8")
        self.assertEqual(list(range(len(syntax.nodes))), [node.order for node in syntax.nodes])
        order_by_id = {node.node_id: node.order for node in syntax.nodes}
        for node in syntax.nodes:
            span = node.source_span
            raw_text = source_bytes[span.start_byte:span.end_byte].decode("utf-8")
            self.assertTrue(raw_text or node.node_type in {"DOCUMENT", "TABLE_CELL"})
            self.assertEqual("SOURCE_MARKDOWN", node.origin)
            if node.parent_node_id is not None:
                self.assertLess(order_by_id[node.parent_node_id], node.order)

        list_block = next(block for block in response.blocks if "准备环境" in block.text)
        self.assertEqual("LIST", list_block.block_type)
        self.assertEqual("10.1 启动", list_block.section_path)
        self.assertFalse(any(block.block_type == "TITLE" and "准备环境" in block.text for block in response.blocks))

    def test_gfm_table_preserves_escaped_pipe_empty_cells_and_alignment(self) -> None:
        source = (
            "# 数据表\n"
            "\n"
            "| 键 | 转义值 | 空值 |\n"
            "| :--- | :---: | ---: |\n"
            "| alpha | A \\| B | |\n"
            "| beta | | tail |\n"
        )

        response = self._parse(source)
        syntax = response.markdown_syntax
        cells = [node for node in syntax.nodes if node.node_type == "TABLE_CELL"]

        self.assertEqual(1, sum(node.node_type == "TABLE" for node in syntax.nodes))
        self.assertEqual(1, sum(node.node_type == "TABLE_HEAD" for node in syntax.nodes))
        self.assertEqual(1, sum(node.node_type == "TABLE_BODY" for node in syntax.nodes))
        self.assertEqual(3, sum(node.node_type == "TABLE_ROW" for node in syntax.nodes))
        self.assertEqual(9, len(cells))

        self.assertEqual(
            ["键", "转义值", "空值", "alpha", "A | B", "", "beta", "", "tail"],
            [node.text for node in cells],
        )
        self.assertEqual(["LEFT", "CENTER", "RIGHT"], [node.alignment for node in cells[:3]])
        self.assertTrue(all(node.header is True for node in cells[:3]))
        self.assertTrue(all(node.header is False for node in cells[3:]))
        self.assertEqual([0, 1, 2, 0, 1, 2, 0, 1, 2], [node.column_index for node in cells])
        self.assertEqual([0, 0, 0, 1, 1, 1, 2, 2, 2], [node.row_index for node in cells])
        source_bytes = source.encode("utf-8")
        self.assertEqual(
            ["键", "转义值", "空值", "alpha", "A \\| B", "", "beta", "", "tail"],
            [
                source_bytes[node.source_span.start_byte:node.source_span.end_byte].decode("utf-8")
                for node in cells
            ],
        )

        table_block = next(block for block in response.blocks if block.block_type == "TABLE")
        self.assertEqual("数据表", table_block.section_path)
        self.assertEqual(
            [["键", "转义值", "空值"], ["alpha", "A | B", ""], ["beta", "", "tail"]],
            table_block.table_rows,
        )
        self.assertIn("<table", table_block.table_html)

    def test_ordered_list_keeps_nonsequential_original_markers(self) -> None:
        syntax = self._parse("0. zero\n2. two\n").markdown_syntax
        items = [node for node in syntax.nodes if node.node_type == "LIST_ITEM"]

        self.assertEqual(["0.", "2."], [node.marker for node in items])
        self.assertEqual([0, 2], [node.ordinal for node in items])

    def test_source_spans_preserve_crlf_and_lone_cr_line_endings(self) -> None:
        for source in ("# 标题\r\n\r\n正文。\r\n", "# 标题\r\r正文。\r"):
            with self.subTest(source=repr(source)):
                syntax = self._parse(source).markdown_syntax
                source_bytes = source.encode("utf-8")
                self.assertEqual(len(source_bytes), syntax.nodes[0].source_span.end_byte)
                self.assertEqual(4, syntax.nodes[0].source_span.end_line)
                for node in syntax.nodes:
                    span = node.source_span
                    source_bytes[span.start_byte:span.end_byte].decode("utf-8")

    def test_contract_json_round_trips_same_versioned_schema(self) -> None:
        source = "# 标题\n\n正文。\n"
        syntax = self._parse(source).markdown_syntax
        payload = json.loads(syntax.model_dump_json(by_alias=True, exclude_none=True))
        restored = MarkdownSyntaxDocument.model_validate(payload)

        self.assertEqual(syntax, restored)
        self.assertEqual("markdown-syntax.v1", payload["schemaVersion"])
        self.assertEqual(source, payload["sourceText"])

    def test_invalid_source_hash_fails_fast(self) -> None:
        syntax = self._parse("# 标题\n").markdown_syntax
        payload = syntax.model_dump(by_alias=True)
        payload["sourceSha256"] = "0" * 64

        with self.assertRaises(ValidationError) as context:
            MarkdownSyntaxDocument.model_validate(payload)

        self.assertIn("sourceSha256", str(context.exception))

    def test_orphan_syntax_node_fails_fast(self) -> None:
        syntax = self._parse("# 标题\n").markdown_syntax
        payload = syntax.model_dump(by_alias=True)
        payload["nodes"][1]["parentNodeId"] = None

        with self.assertRaises(ValidationError) as context:
            MarkdownSyntaxDocument.model_validate(payload)

        self.assertIn("must belong to the DOCUMENT root", str(context.exception))

    def test_malformed_table_hierarchy_and_header_flag_fail_fast(self) -> None:
        source = (
            "| 键 | 值 |\n"
            "| --- | --- |\n"
            "| alpha | beta |\n"
        )
        syntax = self._parse(source).markdown_syntax

        invalid_parent = syntax.model_dump(by_alias=True)
        table_head = next(node for node in invalid_parent["nodes"] if node["nodeType"] == "TABLE_HEAD")
        table_head["parentNodeId"] = invalid_parent["nodes"][0]["nodeId"]
        with self.assertRaises(ValidationError) as parent_context:
            MarkdownSyntaxDocument.model_validate(invalid_parent)
        self.assertIn("TABLE_HEAD parent must be TABLE", str(parent_context.exception))

        invalid_header = syntax.model_dump(by_alias=True)
        header_cell = next(
            node
            for node in invalid_header["nodes"]
            if node["nodeType"] == "TABLE_CELL" and node["rowIndex"] == 0
        )
        header_cell["header"] = False
        with self.assertRaises(ValidationError) as header_context:
            MarkdownSyntaxDocument.model_validate(invalid_header)
        self.assertIn("TABLE_HEAD cell header flag mismatch", str(header_context.exception))

    def test_docmind_markdown_fallback_records_provider_origin(self) -> None:
        parser = document_parser.AliyunDocMindParser()

        blocks, syntax, warnings = parser._result_to_blocks({
            "Data": {
                "markdownContent": "# Provider 标题\n\n1. Provider 列表项\n",
                "layouts": [],
            }
        })

        self.assertEqual([], warnings)
        self.assertEqual("PROVIDER_MARKDOWN", syntax.source_origin)
        self.assertTrue(all(node.origin == "PROVIDER_MARKDOWN" for node in syntax.nodes))
        self.assertEqual(["TITLE", "LIST"], [block.block_type for block in blocks])

    def _parse(self, source: str):
        return parse_document(DocumentParseRequest(
            fileName="通用操作说明.md",
            mimeType="text/markdown",
            fileType="MD",
            contentBase64=base64.b64encode(source.encode("utf-8")).decode("ascii"),
        ))


if __name__ == "__main__":
    unittest.main()
