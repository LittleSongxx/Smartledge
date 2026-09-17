"""Cross-document community detection. Java still decides what becomes Source."""

from __future__ import annotations

import logging
from collections import defaultdict

import networkx as nx

from rag_tools.schemas.graph_communities import (
    GraphCommunityEdge,
    GraphCommunityRequest,
    GraphCommunityResponse,
)

logger = logging.getLogger(__name__)

LEIDEN_METHOD = "leiden"
LOUVAIN_FALLBACK_METHOD = "networkx_louvain_fallback"
ISOLATED_NODES_METHOD = "isolated_nodes"


def detect_communities(request: GraphCommunityRequest) -> GraphCommunityResponse:
    nodes = [node for node in request.nodes if node]
    if not nodes:
        return GraphCommunityResponse(method=ISOLATED_NODES_METHOD, membership={})
    graph = nx.Graph()
    graph.add_nodes_from(nodes)
    for edge in request.edges or []:
        if _usable_edge(edge, graph):
            graph.add_edge(edge.source, edge.target)
    if graph.number_of_edges() == 0:
        return GraphCommunityResponse(
            method=ISOLATED_NODES_METHOD,
            membership={node: f"community-{index}" for index, node in enumerate(nodes)},
        )
    membership, method = _partition(graph)
    return GraphCommunityResponse(method=method, membership=membership)


def _usable_edge(edge: GraphCommunityEdge, graph: nx.Graph) -> bool:
    return bool(edge and edge.source and edge.target and edge.source in graph and edge.target in graph)


def _partition(graph: nx.Graph) -> tuple[dict[str, str], str]:
    try:
        return _leiden(graph), LEIDEN_METHOD
    except Exception as exception:
        logger.info("Leiden unavailable, falling back to Louvain: %s", exception)
        return _louvain(graph), LOUVAIN_FALLBACK_METHOD


def _leiden(graph: nx.Graph) -> dict[str, str]:
    try:
        import igraph
        import leidenalg
    except ImportError:
        raise
    nodes = list(graph.nodes())
    index = {node: position for position, node in enumerate(nodes)}
    edges = [(index[source], index[target]) for source, target in graph.edges()]
    igraph_graph = igraph.Graph(n=len(nodes), edges=edges, directed=False)
    partition = leidenalg.find_partition(igraph_graph, leidenalg.RBConfigurationVertexPartition)
    membership: dict[str, str] = {}
    for community_no, members in enumerate(partition):
        community_id = f"leiden-{community_no}"
        for vertex in members:
            membership[nodes[vertex]] = community_id
    return membership


def _louvain(graph: nx.Graph) -> dict[str, str]:
    communities = nx.community.louvain_communities(graph, seed=0)
    membership: dict[str, str] = {}
    for community_no, members in enumerate(communities):
        community_id = f"louvain-{community_no}"
        for node in members:
            membership[str(node)] = community_id
    if len(membership) != graph.number_of_nodes():
        leftovers = defaultdict(int)
        for node in graph.nodes():
            if str(node) not in membership:
                leftovers[str(node)] = 1
                membership[str(node)] = f"louvain-singleton-{len(leftovers)}"
    return membership
