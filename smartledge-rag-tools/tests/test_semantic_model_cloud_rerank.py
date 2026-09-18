"""云端重排 provider 的契约与可用性行为。

覆盖三件事：
1. 云端返回的分数按输入位置回填（未返回的候选补 0），Java 侧的窗口排序契约不受影响；
2. 可重试错误（429/5xx/超时/网络）按配置重试，重试耗尽抛 SemanticModelUnavailable；
3. 不可重试错误（如 400 参数错误）不重试，避免把配额浪费在确定性失败上。
"""
import unittest
from unittest import mock

from rag_tools import semantic_model
from rag_tools.semantic_model import SemanticModelUnavailable, score_rerank_pairs


class CloudRerankTest(unittest.TestCase):
    def setUp(self) -> None:
        patcher = mock.patch.dict(
            "os.environ",
            {"RAG_TOOLS_RERANK_PROVIDER": "dashscope", "ALI_BAI_LIAN_API_KEY": "test-key",
             "RAG_TOOLS_RERANK_BACKOFF_SECONDS": "0"},
        )
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_scores_are_mapped_back_to_input_positions(self) -> None:
        body = {"output": {"results": [{"index": 2, "relevance_score": 0.9},
                                       {"index": 0, "relevance_score": 0.4}]}}
        with mock.patch.object(semantic_model, "_post_json", return_value=body):
            scores = score_rerank_pairs("查询", ["第一段", "第二段", "第三段"])
        # 接口只返回 top_n：未出现在结果里的候选补 0，位置对应关系必须与输入一致。
        self.assertEqual([0.4, 0.0, 0.9], scores)

    def test_retryable_failure_is_retried_then_succeeds(self) -> None:
        calls = {"count": 0}

        def flaky(*_args, **_kwargs):
            calls["count"] += 1
            if calls["count"] == 1:
                raise semantic_model._RetryableCloudError("HTTP 429 rate limited")
            return {"output": {"results": [{"index": 0, "relevance_score": 0.5},
                                           {"index": 1, "relevance_score": 0.1}]}}

        with mock.patch.object(semantic_model, "_post_json", side_effect=flaky):
            scores = score_rerank_pairs("查询", ["甲", "乙"])
        self.assertEqual(2, calls["count"])
        self.assertEqual([0.5, 0.1], scores)

    def test_retry_exhaustion_raises_unavailable(self) -> None:
        with mock.patch.object(semantic_model, "_post_json",
                               side_effect=semantic_model._RetryableCloudError("HTTP 503")):
            with self.assertRaises(SemanticModelUnavailable) as context:
                score_rerank_pairs("查询", ["甲"])
        self.assertIn("已尝试", str(context.exception))

    def test_non_retryable_failure_is_not_retried(self) -> None:
        calls = {"count": 0}

        def rejected(*_args, **_kwargs):
            calls["count"] += 1
            raise SemanticModelUnavailable("云端重排请求被拒绝: HTTP 400 bad request")

        with mock.patch.object(semantic_model, "_post_json", side_effect=rejected):
            with self.assertRaises(SemanticModelUnavailable):
                score_rerank_pairs("查询", ["甲"])
        self.assertEqual(1, calls["count"])

    def test_missing_api_key_reports_actionable_error(self) -> None:
        with mock.patch.object(semantic_model, "_dashscope_api_key", return_value=""):
            with self.assertRaises(SemanticModelUnavailable) as context:
                score_rerank_pairs("查询", ["甲"])
        self.assertIn("API Key", str(context.exception))

    def test_empty_input_short_circuits(self) -> None:
        with mock.patch.object(semantic_model, "_post_json") as post:
            self.assertEqual([], score_rerank_pairs("查询", []))
        post.assert_not_called()

    def test_cloud_rerank_splits_over_provider_batch_limit(self) -> None:
        calls = []

        def one_batch(_url, payload, *_args, **_kwargs):
            documents = payload["input"]["documents"]
            calls.append(len(documents))
            self.assertLessEqual(len(documents), semantic_model.DEFAULT_CLOUD_RERANK_BATCH_SIZE)
            return {"output": {"results": [
                {"index": index, "relevance_score": 0.1} for index in range(len(documents))
            ]}}

        with mock.patch.object(semantic_model, "_post_json", side_effect=one_batch):
            scores = score_rerank_pairs("查询", [f"段{index}" for index in range(45)])
        self.assertEqual(45, len(scores))
        self.assertEqual([20, 20, 5], calls)

    def test_local_provider_still_uses_cross_encoder(self) -> None:
        with mock.patch.dict("os.environ", {"RAG_TOOLS_RERANK_PROVIDER": "local"}, clear=False):
            with mock.patch.object(semantic_model, "_score_pairs", return_value=[0.2, 0.8]) as local:
                scores = score_rerank_pairs("查询", ["甲", "乙"])
        local.assert_called_once()
        self.assertEqual([0.2, 0.8], scores)

    def test_status_reports_provider_and_resilience_settings(self) -> None:
        status = semantic_model.semantic_model_status()
        self.assertEqual("dashscope", status["rerankProvider"])
        resilience = status["rerankResilience"]
        self.assertGreaterEqual(resilience["maxAttempts"], 1)
        self.assertGreaterEqual(resilience["maxConcurrency"], 1)


if __name__ == "__main__":
    unittest.main()
