import importlib.util
import math
import os
from threading import Lock


DEFAULT_RERANK_MODEL = "BAAI/bge-reranker-v2-m3"
DEFAULT_EMBEDDING_MODEL = "BAAI/bge-m3"

# 向量维度是跨语言契约：Java 侧 ModelHttpClient 会严格校验返回维度，
# pgvector 列也已归一化为 vector(1024)。换模型必须同时满足这个维度。
DEFAULT_EMBEDDING_DIMENSIONS = 1024


class SemanticModelUnavailable(RuntimeError):
    pass


_model_lock = Lock()
_models: dict[str, object] = {}


def semantic_model_status() -> dict[str, object]:
    return {
        "sentenceTransformersAvailable": importlib.util.find_spec("sentence_transformers") is not None,
        "rerankModel": _rerank_model_name(),
        "embeddingModel": _embedding_model_name(),
        "loadedModels": sorted(_models.keys()),
    }


def score_rerank_pairs(query: str, texts: list[str]) -> list[float]:
    return _score_pairs(_rerank_model_name(), [(query or "", text or "") for text in texts])


def embed_texts(texts: list[str]) -> list[list[float]]:
    if not texts:
        return []
    model_name = _embedding_model_name()
    model = _load_embedding_model(model_name)
    try:
        vectors = model.encode(texts, normalize_embeddings=True)
    except Exception as exception:
        raise SemanticModelUnavailable(f"语义向量模型推理失败: {exception}") from exception
    return [list(map(float, vector)) for vector in vectors]


def _score_pairs(model_name: str, pairs: list[tuple[str, str]]) -> list[float]:
    if not pairs:
        return []
    model = _load_cross_encoder(model_name)
    try:
        raw_scores = model.predict(pairs)
    except Exception as exception:
        raise SemanticModelUnavailable(f"语义模型推理失败: {exception}") from exception
    return [_normalize_score(float(score)) for score in raw_scores]


def _load_cross_encoder(model_name: str):
    if not model_name:
        raise SemanticModelUnavailable("未配置 cross-encoder 模型名称。")
    if importlib.util.find_spec("sentence_transformers") is None:
        raise SemanticModelUnavailable(
            "缺少 sentence-transformers 依赖，请在 rag-tools 环境执行 pip install -r requirements.txt。"
        )
    with _model_lock:
        if model_name in _models:
            return _models[model_name]
        try:
            from sentence_transformers import CrossEncoder

            model = CrossEncoder(model_name)
        except Exception as exception:
            raise SemanticModelUnavailable(
                f"加载 cross-encoder 模型失败: model={model_name}, error={exception}"
            ) from exception
        _models[model_name] = model
        return model


def _load_embedding_model(model_name: str):
    if not model_name:
        raise SemanticModelUnavailable("未配置 embedding 模型名称。")
    if importlib.util.find_spec("sentence_transformers") is None:
        raise SemanticModelUnavailable(
            "缺少 sentence-transformers 依赖，请在 rag-tools 环境执行 pip install -r requirements.txt。"
        )
    cache_key = f"embedding::{model_name}"
    with _model_lock:
        if cache_key in _models:
            return _models[cache_key]
        try:
            from sentence_transformers import SentenceTransformer

            model = SentenceTransformer(model_name)
        except Exception as exception:
            raise SemanticModelUnavailable(
                f"加载 embedding 模型失败: model={model_name}, error={exception}"
            ) from exception
        _models[cache_key] = model
        return model


def rerank_model_name() -> str:
    return _rerank_model_name()


def embedding_model_name() -> str:
    return _embedding_model_name()


def _normalize_score(score: float) -> float:
    if 0.0 <= score <= 1.0:
        return score
    if score >= 50:
        return 1.0
    if score <= -50:
        return 0.0
    return 1.0 / (1.0 + math.exp(-score))


def _rerank_model_name() -> str:
    return os.getenv("RAG_TOOLS_RERANK_MODEL", DEFAULT_RERANK_MODEL).strip()


def _embedding_model_name() -> str:
    return os.getenv("RAG_TOOLS_EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL).strip()
