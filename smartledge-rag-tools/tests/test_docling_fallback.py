import base64
import os
import unittest
from unittest.mock import patch

from rag_tools import document_parser
from rag_tools.document_parser import parse_document
from rag_tools.schemas.document_parse import DocumentParseRequest


class DoclingFallbackTest(unittest.TestCase):
    def test_default_still_fails_when_docmind_missing(self) -> None:
        request = DocumentParseRequest(
            fileName="sample.pdf",
            fileType="PDF",
            contentBase64=base64.b64encode(b"%PDF-1.4\n%%EOF").decode("ascii"),
        )
        with patch.object(document_parser.AliyunDocMindParser, "_sdk_available", return_value=True), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_id", return_value=""), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_secret", return_value=""):
            with self.assertRaises(Exception) as context:
                parse_document(request)
        self.assertIn("阿里云 Document Mind", str(context.exception.detail))

    def test_fallback_uses_docling_when_enabled(self) -> None:
        request = DocumentParseRequest(
            fileName="sample.pdf",
            fileType="PDF",
            contentBase64=base64.b64encode(b"%PDF-1.4\n%%EOF").decode("ascii"),
        )

        class FakeParser(document_parser.DoclingPdfParser):
            def is_available(self) -> bool:
                return True

            def parse(self, content, file_type, request):
                from rag_tools.markdown_syntax import markdown_syntax_to_blocks, parse_markdown_syntax
                syntax = parse_markdown_syntax("# 本地 PDF\n\n正文", "SOURCE_MARKDOWN")
                blocks = markdown_syntax_to_blocks(syntax, self.provider_name)
                return document_parser.DocMindParseResult(blocks=blocks, markdown_syntax=syntax)

        with patch.dict(os.environ, {"SMARTLEDGE_DOCLING_PDF_FALLBACK": "true"}), \
                patch.object(document_parser.AliyunDocMindParser, "_sdk_available", return_value=True), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_id", return_value=""), \
                patch.object(document_parser.AliyunDocMindParser, "_access_key_secret", return_value=""), \
                patch.object(document_parser, "_docling_parser", return_value=FakeParser()):
            response = parse_document(request)

        self.assertEqual("docling", response.provider_name)
        self.assertIn("本地 PDF", response.parsed_text)
