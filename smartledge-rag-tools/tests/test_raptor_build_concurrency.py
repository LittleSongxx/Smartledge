import threading
import time
import unittest
from unittest.mock import patch

from rag_tools import raptor_build
from rag_tools.raptor_build import _ClusterResult, _TreeItem, build_raptor
from rag_tools.schemas.raptor_build import RaptorBuildRequest, RaptorChunk, RaptorNode


class RaptorBuildConcurrencyTest(unittest.TestCase):
    def test_build_raptor_builds_level_nodes_concurrently_and_keeps_order(self) -> None:
        active_count = 0
        max_active_count = 0
        lock = threading.Lock()

        def fake_cluster_items(items, max_cluster_size):
            return _ClusterResult([[item] for item in items], {"clusterMethod": "fake"})

        def slow_build_node(cluster, level, node_no, llm_summary_enabled, cluster_signals):
            nonlocal active_count, max_active_count
            with lock:
                active_count += 1
                max_active_count = max(max_active_count, active_count)
            time.sleep(0.05)
            with lock:
                active_count -= 1
            return RaptorNode(
                id=f"raptor-l{level}-{node_no}",
                level=level,
                nodeNo=node_no,
                title=f"node-{node_no}",
                summary=f"summary-{node_no}",
                summaryWithWeight=f"summary-{node_no}",
                childNodeIds=[],
                sourceChunkIds=[cluster[0].chunk_ids[0]],
                sourceParentBlockIds=[],
                sectionPath="",
                pageRange="",
                keywords=[],
                questions=[],
                qualityScore=0.8,
                metadata={},
            )

        request = RaptorBuildRequest(
            documentId=1,
            taskId=2,
            maxClusterSize=2,
            maxLevels=1,
            llmSummaryEnabled=True,
            llmConcurrency=3,
            chunks=[
                RaptorChunk(chunkId=101, chunkNo=1, text="alpha " * 20),
                RaptorChunk(chunkId=102, chunkNo=2, text="beta " * 20),
                RaptorChunk(chunkId=103, chunkNo=3, text="gamma " * 20),
            ],
        )

        with patch.object(raptor_build, "_cluster_items", side_effect=fake_cluster_items):
            with patch.object(raptor_build, "_build_node", side_effect=slow_build_node):
                response = build_raptor(request)

        self.assertGreaterEqual(max_active_count, 2)
        self.assertEqual([1, 2, 3], [node.node_no for node in response.nodes])
        self.assertEqual([101, 102, 103], [node.source_chunk_ids[0] for node in response.nodes])


class RaptorRequestConfigurationTest(unittest.TestCase):
    def test_missing_request_concurrency_is_rejected_when_summaries_are_enabled(self) -> None:
        request = RaptorBuildRequest(
            maxClusterSize=2,
            maxLevels=1,
            llmSummaryEnabled=True,
            chunks=[RaptorChunk(chunkId=101, chunkNo=1, text="alpha")],
        )
        with self.assertRaisesRegex(Exception, "llmConcurrency"):
            build_raptor(request)
