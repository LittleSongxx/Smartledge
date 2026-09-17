from rag_tools.graph_candidates import handle
from rag_tools.schemas.graph_extract import GraphExtractRequest, GraphExtractResponse


def extract_graph(request: GraphExtractRequest) -> GraphExtractResponse:
    return handle(request)
