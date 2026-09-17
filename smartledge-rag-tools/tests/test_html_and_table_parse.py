import base64
import unittest

from rag_tools.document_parser import (
    _content_quality_level,
    _docmind_rows_from_value,
    _parse_html,
    _table_rows_from_html,
    parse_document,
)
from rag_tools.schemas.document_parse import DocumentParseRequest


class HtmlAndTableParseTest(unittest.TestCase):
    def test_html_parser_emits_heading_paragraph_and_table(self) -> None:
        html = """
        <html><body>
        <h1>产品手册</h1>
        <p>这是一段说明文字。</p>
        <table>
          <tr><td rowspan="2">合并</td><td>右上</td></tr>
          <tr><td>右下</td></tr>
        </table>
        </body></html>
        """
        blocks = _parse_html(html.encode("utf-8"))
        types = [block.block_type for block in blocks]
        self.assertEqual(["TITLE", "TEXT", "TABLE"], types)
        self.assertEqual("产品手册", blocks[0].text)
        self.assertIn("说明文字", blocks[1].text)
        self.assertEqual([["合并", "右上"], ["合并", "右下"]], blocks[2].table_rows)

    def test_native_html_document_route(self) -> None:
        request = DocumentParseRequest(
            fileName="sample.html",
            fileType="HTML",
            contentBase64=base64.b64encode("<h2>条款</h2><p>正文。</p>".encode("utf-8")).decode("ascii"),
        )
        response = parse_document(request)
        self.assertEqual("native_text", response.provider_name)
        self.assertEqual(["TITLE", "TEXT"], [block.block_type for block in response.blocks])

    def test_html_table_colspan_is_expanded(self) -> None:
        rows = _table_rows_from_html(
            "<table><tr><td colspan='2'>宽</td></tr><tr><td>左</td><td>右</td></tr></table>"
        )
        self.assertEqual([["宽", "宽"], ["左", "右"]], rows)

    def test_docmind_cell_rowspan_fills_grid(self) -> None:
        rows = _docmind_rows_from_value([
            {"rowIndex": 1, "colIndex": 1, "text": "A", "rowSpan": 2},
            {"rowIndex": 1, "colIndex": 2, "text": "B"},
            {"rowIndex": 2, "colIndex": 2, "text": "C"},
        ])
        self.assertEqual([["A", "B"], ["A", "C"]], rows)

    def test_short_clean_document_is_medium_not_low(self) -> None:
        self.assertEqual(2, _content_quality_level("短而干净的说明文本。"))
        self.assertEqual(1, _content_quality_level(""))
        self.assertEqual(1, _content_quality_level("坏" + ("�" * 5)))


if __name__ == "__main__":
    unittest.main()
