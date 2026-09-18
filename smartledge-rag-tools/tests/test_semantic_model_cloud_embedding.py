"""云端 embedding provider：RAPTOR 聚类在无 sentence-transformers 时必须能走通。"""
import unittest
from unittest import mock

from rag_tools import semantic_model
from rag_tools.semantic_model import SemanticModelUnavailable, embed_texts


class CloudEmbeddingTest(unittest.TestCase):
    def setUp(self) -> None:
        patcher = mock.patch.dict(
            "os.environ",
            {
                "SMARTLEDGE_EMBEDDING_PROVIDER": "openai-compatible",
                "SMARTLEDGE_EMBEDDING_MODEL": "qwen3.7-text-embedding",
                "SMARTLEDGE_EMBEDDING_DIMENSIONS": "4",
                "ALI_BAI_LIAN_API_KEY": "test-key",
                "RAG_TOOLS_RERANK_BACKOFF_SECONDS": "0",
            },
        )
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_vectors_are_mapped_back_to_input_positions(self) -> None:
        body = {"data": [
            {"index": 1, "embedding": [0.0, 1.0, 0.0, 0.0]},
            {"index": 0, "embedding": [1.0, 0.0, 0.0, 0.0]},
        ]}
        with mock.patch.object(semantic_model, "_post_json", return_value=body):
            vectors = embed_texts(["甲", "乙"])
        self.assertEqual([[1.0, 0.0, 0.0, 0.0], [0.0, 1.0, 0.0, 0.0]], vectors)

    def test_cloud_vectors_are_l2_normalized(self) -> None:
        body = {"data": [{"index": 0, "embedding": [3.0, 4.0, 0.0, 0.0]}]}
        with mock.patch.object(semantic_model, "_post_json", return_value=body):
            vectors = embed_texts(["甲"])
        self.assertEqual([[0.6, 0.8, 0.0, 0.0]], [[round(value, 6) for value in vectors[0]]])

    def test_dimension_mismatch_fails_closed(self) -> None:
        body = {"data": [{"index": 0, "embedding": [1.0, 0.0]}]}
        with mock.patch.object(semantic_model, "_post_json", return_value=body):
            with self.assertRaises(SemanticModelUnavailable) as context:
                embed_texts(["甲"])
        self.assertIn("维度不匹配", str(context.exception))

    def test_retryable_failure_is_retried_then_succeeds(self) -> None:
        calls = {"count": 0}

        def flaky(*_args, **_kwargs):
            calls["count"] += 1
            if calls["count"] == 1:
                raise semantic_model._RetryableCloudError("HTTP 429")
            return {"data": [{"index": 0, "embedding": [0.0, 1.0, 0.0, 0.0]}]}

        with mock.patch.object(semantic_model, "_post_json", side_effect=flaky):
            vectors = embed_texts(["甲"])
        self.assertEqual(2, calls["count"])
        self.assertEqual([[0.0, 1.0, 0.0, 0.0]], vectors)

    def test_missing_api_key_reports_actionable_error(self) -> None:
        with mock.patch.object(semantic_model, "_embedding_api_key", return_value=""):
            with self.assertRaises(SemanticModelUnavailable) as context:
                embed_texts(["甲"])
        self.assertIn("API Key", str(context.exception))

    def test_empty_input_short_circuits(self) -> None:
        with mock.patch.object(semantic_model, "_post_json") as post:
            self.assertEqual([], embed_texts([]))
        post.assert_not_called()

    def test_cloud_embedding_splits_over_provider_batch_limit(self) -> None:
        calls = []

        def one_batch(_url, payload, *_args, **_kwargs):
            texts = payload["input"]
            calls.append(len(texts))
            self.assertLessEqual(len(texts), semantic_model.DEFAULT_CLOUD_EMBED_BATCH_SIZE)
            return {"data": [{"index": index, "embedding": [1.0, 0.0, 0.0, 0.0]} for index in range(len(texts))]}

        with mock.patch.object(semantic_model, "_post_json", side_effect=one_batch):
            vectors = embed_texts([f"段{index}" for index in range(25)])
        self.assertEqual(25, len(vectors))
        self.assertEqual([10, 10, 5], calls)

    def test_local_provider_still_uses_sentence_transformer(self) -> None:
        with mock.patch.dict("os.environ", {"SMARTLEDGE_EMBEDDING_PROVIDER": "local"}, clear=False):
            fake = mock.Mock()
            fake.encode.return_value = [[0.2, 0.8]]
            with mock.patch.object(semantic_model, "_load_embedding_model", return_value=fake) as loader:
                vectors = embed_texts(["甲"])
        loader.assert_called_once()
        self.assertEqual([[0.2, 0.8]], vectors)

    def test_status_reports_cloud_embedding_provider(self) -> None:
        status = semantic_model.semantic_model_status()
        self.assertEqual("openai-compatible", status["embeddingProvider"])
        self.assertEqual("qwen3.7-text-embedding", status["embeddingModel"])


if __name__ == "__main__":
    unittest.main()
