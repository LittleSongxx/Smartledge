import unittest
from unittest.mock import patch

from fastapi import HTTPException

from rag_tools.raptor_build import CLUSTER_METHOD, _cluster_items
from rag_tools.raptor_build import _TreeItem


class RaptorSklearnClusterTest(unittest.TestCase):
    def test_cluster_method_is_sklearn(self) -> None:
        self.assertEqual("sklearn_agglomerative_average_cosine_v1", CLUSTER_METHOD)

    def test_similar_vectors_share_a_cluster(self) -> None:
        items = [
            _TreeItem("a", "A", "alpha", "", "", [1], [], ["a"], [], []),
            _TreeItem("b", "B", "beta", "", "", [2], [], ["b"], [], []),
            _TreeItem("c", "C", "gamma", "", "", [3], [], ["c"], [], []),
            _TreeItem("d", "D", "delta", "", "", [4], [], ["d"], [], []),
        ]
        vectors = [
            [1.0, 0.0],
            [0.99, 0.01],
            [0.0, 1.0],
            [0.01, 0.99],
        ]
        with patch("rag_tools.raptor_build.embed_texts", return_value=vectors):
            result = _cluster_items(items, max_cluster_size=2)

        self.assertEqual(2, len(result.clusters))
        grouped = {tuple(sorted(item.item_id for item in cluster)) for cluster in result.clusters}
        self.assertIn(("a", "b"), grouped)
        self.assertIn(("c", "d"), grouped)

    def test_missing_sklearn_is_typed_unavailable_not_500(self) -> None:
        items = [
            _TreeItem("a", "A", "alpha", "", "", [1], [], ["a"], [], []),
            _TreeItem("b", "B", "beta", "", "", [2], [], ["b"], [], []),
            _TreeItem("c", "C", "gamma", "", "", [3], [], ["c"], [], []),
            _TreeItem("d", "D", "delta", "", "", [4], [], ["d"], [], []),
        ]
        vectors = [[1.0, 0.0], [0.99, 0.01], [0.0, 1.0], [0.01, 0.99]]
        with patch("rag_tools.raptor_build.embed_texts", return_value=vectors):
            with patch(
                "rag_tools.raptor_build._sklearn_agglomerative_clusters",
                side_effect=ImportError("No module named 'numpy'"),
            ):
                with self.assertRaises(HTTPException) as context:
                    _cluster_items(items, max_cluster_size=2)
        self.assertEqual(503, context.exception.status_code)
        self.assertIn("scikit-learn", str(context.exception.detail))
