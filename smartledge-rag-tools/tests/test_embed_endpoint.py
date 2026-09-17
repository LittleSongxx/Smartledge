"""本地向量化端点的契约测试。

对应 S20：Python 侧新增 /embed，作为 Java EmbeddingPort 的本地实现。
这里只验证协议与校验逻辑，不加载真实模型（模型加载由端到端人工验证覆盖）。
"""

import json
import unittest
from unittest.mock import patch

from fastapi import HTTPException

from rag_tools.main import app, embed
from rag_tools.schemas.embed import EmbedRequest
from rag_tools.semantic_model import SemanticModelUnavailable


class EmbedEndpointTest(unittest.TestCase):

    def test_returns_service_declared_model_and_dimensions(self):
        vectors = [[0.1] * 1024, [0.2] * 1024]
        with patch('rag_tools.main.embed_texts', return_value=vectors), \
                patch('rag_tools.main.embedding_model_name', return_value='BAAI/bge-m3'):
            response = embed(EmbedRequest(texts=['甲', '乙']))

        self.assertEqual(response.model, 'BAAI/bge-m3')
        self.assertEqual(response.dimensions, 1024)
        self.assertEqual(len(response.embeddings), 2)
        self.assertEqual(len(response.embeddings[0]), 1024)

    def test_empty_input_does_not_touch_the_model(self):
        with patch('rag_tools.main.embed_texts', side_effect=AssertionError('空输入不得调用模型')), \
                patch('rag_tools.main.embedding_model_name', return_value='BAAI/bge-m3'):
            response = embed(EmbedRequest(texts=[]))

        self.assertEqual(response.embeddings, [])
        self.assertEqual(response.dimensions, 0)
        self.assertEqual(response.model, 'BAAI/bge-m3')

    def test_model_unavailable_maps_to_503(self):
        with patch('rag_tools.main.embed_texts',
                   side_effect=SemanticModelUnavailable('加载 embedding 模型失败')):
            with self.assertRaises(HTTPException) as raised:
                embed(EmbedRequest(texts=['甲']))

        self.assertEqual(raised.exception.status_code, 503)

    def test_inconsistent_dimensions_are_rejected(self):
        vectors = [[0.1] * 1024, [0.2] * 512]
        with patch('rag_tools.main.embed_texts', return_value=vectors), \
                patch('rag_tools.main.embedding_model_name', return_value='BAAI/bge-m3'):
            with self.assertRaises(HTTPException) as raised:
                embed(EmbedRequest(texts=['甲', '乙']))

        self.assertEqual(raised.exception.status_code, 502)

    def test_route_is_reachable_over_asgi_wire(self):
        vectors = [[0.5] * 1024]

        async def post(payload):
            body = json.dumps(payload).encode()
            sent = []
            received = False

            async def receive():
                nonlocal received
                if not received:
                    received = True
                    return {'type': 'http.request', 'body': body, 'more_body': False}
                return {'type': 'http.disconnect'}

            async def send(message):
                sent.append(message)

            await app({'type': 'http', 'asgi': {'version': '3.0'}, 'http_version': '1.1', 'method': 'POST',
                'scheme': 'http', 'path': '/embed', 'raw_path': b'/embed', 'query_string': b'',
                'headers': [(b'content-type', b'application/json')], 'client': ('127.0.0.1', 1),
                'server': ('test', 80)}, receive, send)
            return sent

        import asyncio
        with patch('rag_tools.main.embed_texts', return_value=vectors), \
                patch('rag_tools.main.embedding_model_name', return_value='BAAI/bge-m3'):
            sent = asyncio.run(post({'texts': ['甲']}))

        start = next(message for message in sent if message['type'] == 'http.response.start')
        self.assertEqual(start['status'], 200)
        body = b''.join(message.get('body', b'') for message in sent if message['type'] == 'http.response.body')
        payload = json.loads(body)
        self.assertEqual(payload['model'], 'BAAI/bge-m3')
        self.assertEqual(payload['dimensions'], 1024)
        self.assertEqual(len(payload['embeddings'][0]), 1024)


class EmbeddingModelDefaultTest(unittest.TestCase):

    def test_default_embedding_model_is_the_1024_dimension_bge_model(self):
        from rag_tools.semantic_model import (
            DEFAULT_EMBEDDING_DIMENSIONS,
            DEFAULT_EMBEDDING_MODEL,
            DEFAULT_RERANK_MODEL,
        )

        # 维度是跨语言契约：Java 严格校验、pgvector 列已归一化为 vector(1024)。
        self.assertEqual(DEFAULT_EMBEDDING_DIMENSIONS, 1024)
        self.assertEqual(DEFAULT_EMBEDDING_MODEL, 'BAAI/bge-m3')
        self.assertEqual(DEFAULT_RERANK_MODEL, 'BAAI/bge-reranker-v2-m3')


if __name__ == '__main__':
    unittest.main()
