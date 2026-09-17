import logging
import time
import warnings

from fastapi import FastAPI, HTTPException
from pydantic.warnings import UnsupportedFieldAttributeWarning

from rag_tools.document_parser import document_parser_status, parse_document
from rag_tools.graph_communities import detect_communities
from rag_tools.graph_extract import extract_graph
from rag_tools.graph_entity_llm import GraphEntityLlmError
from rag_tools.raptor_build import build_raptor
from rag_tools.schemas.document_parse import DocumentParseRequest, DocumentParseResponse
from rag_tools.schemas.embed import EmbedRequest, EmbedResponse
from rag_tools.schemas.graph_communities import GraphCommunityRequest, GraphCommunityResponse
from rag_tools.schemas.graph_extract import GraphExtractRequest, GraphExtractResponse
from rag_tools.schemas.raptor_build import RaptorBuildRequest, RaptorBuildResponse
from rag_tools.schemas.rerank import RerankRequest, RerankResponse, RerankResult
from rag_tools.semantic_model import (
    SemanticModelUnavailable,
    embed_texts,
    embedding_model_name,
    score_rerank_pairs,
    semantic_model_status,
)

SERVICE_NAME = "smartledge-rag-tools"
SERVICE_VERSION = "0.1.0"

logger = logging.getLogger(__name__)

warnings.filterwarnings("ignore", category=UnsupportedFieldAttributeWarning)

app = FastAPI(title=SERVICE_NAME, version=SERVICE_VERSION)


@app.get("/health")
def health() -> dict[str, object]:
    return {
        "status": "ok",
        "service": SERVICE_NAME,
        "version": SERVICE_VERSION,
        "semanticModels": semantic_model_status(),
        "documentParsers": document_parser_status(),
    }


@app.post("/rerank", response_model=RerankResponse)
def rerank(request: RerankRequest) -> RerankResponse:
    top_k = max(0, min(request.top_k, len(request.candidates)))
    if top_k == 0:
        return RerankResponse()
    try:
        scores = score_rerank_pairs(request.query, [candidate.text for candidate in request.candidates])
    except SemanticModelUnavailable as exception:
        raise HTTPException(status_code=503, detail=str(exception)) from exception

    scored_candidates = [
        (score, index, candidate)
        for index, (score, candidate) in enumerate(zip(scores, request.candidates))
    ]
    scored_candidates.sort(key=lambda item: (-item[0], item[1]))
    results = [
        RerankResult(
            id=candidate.id,
            score=round(score, 6),
            rank=index + 1,
        )
        for index, (score, _, candidate) in enumerate(scored_candidates[:top_k])
    ]
    return RerankResponse(results=results)


@app.post("/embed", response_model=EmbedResponse)
def embed(request: EmbedRequest) -> EmbedResponse:
    """本地向量化。

    这是 Java 侧 EmbeddingPort 的本地实现端点。模型与维度由本服务声明，
    调用方只做校验，不自行假设模型名或维度。
    """
    started = time.perf_counter()
    if not request.texts:
        return EmbedResponse(model=embedding_model_name())
    try:
        vectors = embed_texts(request.texts)
    except SemanticModelUnavailable as exception:
        raise HTTPException(status_code=503, detail=str(exception)) from exception

    dimensions = len(vectors[0]) if vectors else 0
    if any(len(vector) != dimensions for vector in vectors):
        raise HTTPException(status_code=502, detail="向量化返回的维度不一致")
    logger.info(
        "embed completed text_count=%s dimensions=%s cost_ms=%s",
        len(vectors),
        dimensions,
        int((time.perf_counter() - started) * 1000),
    )
    return EmbedResponse(model=embedding_model_name(), dimensions=dimensions, embeddings=vectors)


@app.post("/document/parse", response_model=DocumentParseResponse, response_model_exclude_none=True)
def document_parse(request: DocumentParseRequest) -> DocumentParseResponse:
    started = time.perf_counter()
    try:
        response = parse_document(request)
        logger.info(
            "document_parse completed file_name=%s file_type=%s parsed_text_length=%s block_count=%s artifact_count=%s cost_ms=%s",
            request.file_name,
            request.file_type,
            len(response.parsed_text or ""),
            len(response.blocks or []),
            len(response.artifacts or []),
            int((time.perf_counter() - started) * 1000),
        )
        return response
    except Exception:
        logger.exception(
            "document_parse failed file_name=%s file_type=%s cost_ms=%s",
            request.file_name,
            request.file_type,
            int((time.perf_counter() - started) * 1000),
        )
        raise


@app.post("/graph/communities", response_model=GraphCommunityResponse)
def graph_communities(request: GraphCommunityRequest) -> GraphCommunityResponse:
    return detect_communities(request)


@app.post("/graph/extract", response_model=GraphExtractResponse)
def graph_extract(request: GraphExtractRequest) -> GraphExtractResponse:
    try:
        return extract_graph(request)
    except GraphEntityLlmError as exception:
        raise HTTPException(status_code=exception.status_code, detail=exception.detail) from None


@app.post("/raptor/build", response_model=RaptorBuildResponse)
def raptor_build(request: RaptorBuildRequest) -> RaptorBuildResponse:
    return build_raptor(request)
