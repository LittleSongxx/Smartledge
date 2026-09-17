import unittest
from unittest.mock import patch

from fastapi import HTTPException

from rag_tools.raptor_build import _cluster_items, build_raptor
from rag_tools.raptor_build import _TreeItem
from rag_tools.schemas.raptor_build import RaptorBuildRequest, RaptorChunk


class RaptorBudgetAuthorityTest(unittest.TestCase):
    def test_out_of_range_cluster_size_is_rejected(self) -> None:
        request = RaptorBuildRequest(
            maxClusterSize=51,
            maxLevels=3,
            chunks=[RaptorChunk(chunkId=1, text="hello world")],
        )
        with self.assertRaises(HTTPException) as context:
            build_raptor(request)
        self.assertEqual(422, context.exception.status_code)
        self.assertIn("maxClusterSize", str(context.exception.detail))

    def test_out_of_range_levels_are_rejected(self) -> None:
        request = RaptorBuildRequest(
            maxClusterSize=6,
            maxLevels=9,
            chunks=[RaptorChunk(chunkId=1, text="hello world")],
        )
        with self.assertRaises(HTTPException) as context:
            build_raptor(request)
        self.assertEqual(422, context.exception.status_code)

    def test_java_upper_bounds_are_accepted(self) -> None:
        request = RaptorBuildRequest(
            maxClusterSize=50,
            maxLevels=8,
            chunks=[RaptorChunk(chunkId=1, text="hello world")],
        )
        with patch("rag_tools.raptor_build.embed_texts", return_value=[[1.0, 0.0]]):
            result = build_raptor(request)
        self.assertTrue(result.nodes)

    def test_unnormalized_vectors_cluster_by_cosine(self) -> None:
        items = [
            _TreeItem("a", "A", "alpha", "", "", [1], [], ["a"], [], []),
            _TreeItem("b", "B", "beta", "", "", [2], [], ["b"], [], []),
            _TreeItem("c", "C", "gamma", "", "", [3], [], ["c"], [], []),
            _TreeItem("d", "D", "delta", "", "", [4], [], ["d"], [], []),
        ]
        vectors = [
            [100.0, 0.0],
            [1.0, 0.0],
            [0.0, 50.0],
            [0.0, 1.0],
        ]
        with patch("rag_tools.raptor_build.embed_texts", return_value=vectors):
            result = _cluster_items(items, max_cluster_size=2)
        grouped = {tuple(sorted(item.item_id for item in cluster)) for cluster in result.clusters}
        self.assertIn(("a", "b"), grouped)
        self.assertIn(("c", "d"), grouped)


if __name__ == "__main__":
    unittest.main()
