import unittest

from rag_tools.graph_communities import LOUVAIN_FALLBACK_METHOD, detect_communities
from rag_tools.schemas.graph_communities import GraphCommunityEdge, GraphCommunityRequest


class GraphCommunitiesTest(unittest.TestCase):
    def test_two_components_are_separated(self) -> None:
        response = detect_communities(
            GraphCommunityRequest(
                nodes=["a", "b", "c", "d"],
                edges=[
                    GraphCommunityEdge(source="a", target="b"),
                    GraphCommunityEdge(source="c", target="d"),
                ],
            )
        )

        self.assertIn(response.method, {"leiden", LOUVAIN_FALLBACK_METHOD})
        self.assertEqual(response.membership["a"], response.membership["b"])
        self.assertEqual(response.membership["c"], response.membership["d"])
        self.assertNotEqual(response.membership["a"], response.membership["c"])

    def test_edgeless_graph_does_not_claim_leiden(self) -> None:
        response = detect_communities(GraphCommunityRequest(nodes=["a", "b"], edges=[]))
        self.assertEqual("isolated_nodes", response.method)
        self.assertEqual(2, len(response.membership))
