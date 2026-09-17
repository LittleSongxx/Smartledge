import importlib.util
import json
import math
import os
import random
import time
import urllib.error
import urllib.request
from threading import BoundedSemaphore, Lock

from .config import config_float, config_int, config_value


DEFAULT_RERANK_MODEL = "BAAI/bge-reranker-v2-m3"
DEFAULT_EMBEDDING_MODEL = "BAAI/bge-m3"

# 重排 provider：local = 本地 cross-encoder 推理；dashscope = 阿里云百炼云端重排。
# 契约不变：两种 provider 都返回与输入等长、已归一化到 [0,1] 的分数数组。
PROVIDER_LOCAL = "local"
PROVIDER_DASHSCOPE = "dashscope"
DEFAULT_RERANK_PROVIDER = PROVIDER_LOCAL
DEFAULT_DASHSCOPE_RERANK_MODEL = "gte-rerank-v2"
DEFAULT_DASHSCOPE_RERANK_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"

# 云端调用的可用性参数：有界重试 + 指数退避 + 抖动，以及进程内并发上限。
# 并发上限是"客户端限流"：批量重排会同时发多批请求，不限并发容易自造 429。
DEFAULT_RERANK_MAX_ATTEMPTS = 3
DEFAULT_RERANK_BACKOFF_SECONDS = 0.8
DEFAULT_RERANK_MAX_CONCURRENCY = 4
# 可重试的 HTTP 状态：限流与瞬时服务端错误；4xx 里的参数/鉴权错误重试无意义。
RETRYABLE_STATUS = {408, 425, 429, 500, 502, 503, 504}

# 向量维度是跨语言契约：Java 侧 ModelHttpClient 会严格校验返回维度，
# pgvector 列也已归一化为 vector(1024)。换模型必须同时满足这个维度。
DEFAULT_EMBEDDING_DIMENSIONS = 1024


class SemanticModelUnavailable(RuntimeError):
    pass


_model_lock = Lock()
_models: dict[str, object] = {}
_rerank_semaphore_lock = Lock()
_rerank_semaphore: BoundedSemaphore | None = None
_rerank_semaphore_limit = 0


def semantic_model_status() -> dict[str, object]:
    return {
        "sentenceTransformersAvailable": importlib.util.find_spec("sentence_transformers") is not None,
        "rerankProvider": _rerank_provider(),
        "rerankModel": rerank_model_name(),
        "embeddingProvider": _embedding_provider(),
        "embeddingModel": _embedding_model_name(),
        "loadedModels": sorted(_models.keys()),
        "rerankResilience": {
            "maxAttempts": _rerank_max_attempts(),
            "backoffSeconds": _rerank_backoff(),
            "maxConcurrency": config_int("ragTools.rerank.maxConcurrency", "RAG_TOOLS_RERANK_MAX_CONCURRENCY",
                                         DEFAULT_RERANK_MAX_CONCURRENCY, 1, 32),
            "timeoutSeconds": _dashscope_timeout(),
        },
    }


def score_rerank_pairs(query: str, texts: list[str]) -> list[float]:
    if not texts:
        return []
    if _rerank_provider() == PROVIDER_DASHSCOPE:
        return _dashscope_rerank_scores(query, texts)
    return _score_pairs(_rerank_model_name(), [(query or "", text or "") for text in texts])


def _dashscope_rerank_scores(query: str, texts: list[str]) -> list[float]:
    """云端重排：调用百炼 text-rerank 接口，返回与输入等长的归一化分数。

    接口只返回 top_n 条结果且按分数降序，因此未出现在结果里的候选补 0 分，
    保持"输入顺序 -> 分数"的位置对应关系，Java 侧的窗口排序契约不受影响。

    可用性：进程内并发上限 + 有界重试（指数退避 + 抖动，只重试限流与瞬时服务端错误）。
    重试耗尽仍失败时抛 SemanticModelUnavailable，由调用方决定降级（本项目由 Java 侧
    把重排失败记为 ledger 的 UNUSABLE 并退回融合序，不影响证据与引用的合法性）。
    """
    api_key = _dashscope_api_key()
    if not api_key:
        raise SemanticModelUnavailable(
            "重排 provider=dashscope 需要 API Key：请配置 ALI_BAI_LIAN_API_KEY 或 RAG_TOOLS_RERANK_DASHSCOPE_API_KEY。"
        )
    payload = {
        "model": _dashscope_rerank_model(),
        "input": {"query": query or "", "documents": [text or "" for text in texts]},
        "parameters": {"top_n": len(texts), "return_documents": False},
    }
    url = _dashscope_rerank_url()
    attempts = _rerank_max_attempts()
    backoff = _rerank_backoff()
    last_error = ""
    with _rerank_concurrency_slot():
        for attempt in range(1, attempts + 1):
            try:
                body = _post_json(url, payload, api_key, _dashscope_timeout())
                return _scores_from_rerank_body(body, len(texts))
            except SemanticModelUnavailable as failure:
                raise
            except _RetryableCloudError as failure:
                last_error = str(failure)
                if attempt >= attempts:
                    break
                # 指数退避 + 抖动：避免多进程/多批次同时重试造成二次拥塞。
                delay = backoff * (2 ** (attempt - 1)) + random.uniform(0, backoff * 0.25)
                time.sleep(delay)
            except Exception as failure:  # noqa: BLE001 - 网络/解析等一律按可重试处理
                last_error = str(failure)
                if attempt >= attempts:
                    break
                delay = backoff * (2 ** (attempt - 1)) + random.uniform(0, backoff * 0.25)
                time.sleep(delay)
    raise SemanticModelUnavailable(f"云端重排失败（已尝试 {attempts} 次）: {last_error}")


class _RetryableCloudError(RuntimeError):
    """可重试的云端错误（限流、瞬时服务端错误、网络抖动）。"""


def _post_json(url: str, payload: dict, api_key: str, timeout: float) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exception:
        detail = ""
        try:
            detail = exception.read().decode("utf-8", "replace")[:300]
        except Exception:  # noqa: BLE001 - 读取错误信息失败不影响判定
            detail = ""
        message = f"HTTP {exception.code} {detail}"
        if exception.code in RETRYABLE_STATUS:
            raise _RetryableCloudError(message) from exception
        raise SemanticModelUnavailable(f"云端重排请求被拒绝: {message}") from exception
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError) as exception:
        raise _RetryableCloudError(str(exception)) from exception


def _scores_from_rerank_body(body: dict, size: int) -> list[float]:
    scores = [0.0] * size
    for item in (body.get("output") or {}).get("results") or []:
        try:
            index = int(item.get("index"))
            score = float(item.get("relevance_score"))
        except (TypeError, ValueError):
            continue
        if 0 <= index < size:
            scores[index] = _normalize_score(score)
    return scores


def embed_texts(texts: list[str]) -> list[list[float]]:
    if not texts:
        return []
    if _embedding_provider() == PROVIDER_OPENAI_COMPATIBLE:
        return _openai_compatible_embeddings(texts)
    model_name = _embedding_model_name()
    model = _load_embedding_model(model_name)
    try:
        vectors = model.encode(texts, normalize_embeddings=True)
    except Exception as exception:
        raise SemanticModelUnavailable(f"语义向量模型推理失败: {exception}") from exception
    return [list(map(float, vector)) for vector in vectors]


PROVIDER_OPENAI_COMPATIBLE = "openai-compatible"
DEFAULT_EMBEDDING_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/"
DEFAULT_EMBEDDING_PATH = "v1/embeddings"


def _embedding_provider() -> str:
    provider = config_value(
        "ragTools.embedding.provider",
        "RAG_TOOLS_EMBEDDING_PROVIDER",
        os.getenv("SMARTLEDGE_EMBEDDING_PROVIDER", "local"),
    ).strip().lower()
    if provider in {PROVIDER_OPENAI_COMPATIBLE, PROVIDER_DASHSCOPE, "cloud"}:
        return PROVIDER_OPENAI_COMPATIBLE
    return "local"


def _openai_compatible_embeddings(texts: list[str]) -> list[list[float]]:
    """与 Java ModelHttpClient 同一条 openai-compatible embeddings 契约。

    RAPTOR 聚类只需要向量，不需要本地 sentence-transformers。云端模式下
    缺少该依赖时必须走这条路径，否则 /raptor/build 会 503。
    """
    api_key = _embedding_api_key()
    if not api_key:
        raise SemanticModelUnavailable(
            "embedding provider=openai-compatible 需要 API Key：请配置 "
            "SMARTLEDGE_EMBEDDING_API_KEY 或 ALI_BAI_LIAN_API_KEY。"
        )
    url = _embedding_url()
    payload = {"model": _embedding_model_name(), "input": [text or "" for text in texts]}
    dimensions = _embedding_dimensions()
    if dimensions > 0:
        payload["dimensions"] = dimensions
    attempts = _rerank_max_attempts()
    backoff = _rerank_backoff()
    last_error = ""
    with _rerank_concurrency_slot():
        for attempt in range(1, attempts + 1):
            try:
                body = _post_json(url, payload, api_key, _dashscope_timeout())
                return _vectors_from_embedding_body(body, len(texts), dimensions)
            except SemanticModelUnavailable:
                raise
            except _RetryableCloudError as failure:
                last_error = str(failure)
                if attempt >= attempts:
                    break
                delay = backoff * (2 ** (attempt - 1)) + random.uniform(0, backoff * 0.25)
                time.sleep(delay)
            except Exception as failure:  # noqa: BLE001 - 网络/解析等一律按可重试处理
                last_error = str(failure)
                if attempt >= attempts:
                    break
                delay = backoff * (2 ** (attempt - 1)) + random.uniform(0, backoff * 0.25)
                time.sleep(delay)
    raise SemanticModelUnavailable(f"云端向量化失败（已尝试 {attempts} 次）: {last_error}")


def _vectors_from_embedding_body(body: dict, size: int, expected_dimensions: int) -> list[list[float]]:
    rows = list(body.get("data") or [])
    vectors: list[list[float] | None] = [None] * size
    for item in rows:
        try:
            index = int(item.get("index"))
            raw = item.get("embedding") or []
            vector = [float(value) for value in raw]
        except (TypeError, ValueError) as exception:
            raise SemanticModelUnavailable(f"云端向量响应无法解析: {exception}") from exception
        if index < 0 or index >= size:
            continue
        if expected_dimensions > 0 and len(vector) != expected_dimensions:
            raise SemanticModelUnavailable(
                f"云端向量维度不匹配: expected={expected_dimensions}, actual={len(vector)}"
            )
        vectors[index] = vector
    if any(item is None for item in vectors):
        raise SemanticModelUnavailable("云端向量响应条数与输入不一致。")
    return l2_normalize_vectors([item for item in vectors if item is not None])


def l2_normalize_vectors(vectors: list[list[float]]) -> list[list[float]]:
    normalized: list[list[float]] = []
    for vector in vectors:
        norm = math.sqrt(sum(value * value for value in vector))
        if norm <= 0:
            normalized.append(list(vector))
        else:
            normalized.append([value / norm for value in vector])
    return normalized


def _embedding_api_key() -> str:
    configured = config_value(
        "ragTools.embedding.apiKey",
        "SMARTLEDGE_EMBEDDING_API_KEY",
        "",
    ).strip()
    return configured or _dashscope_api_key()


def _embedding_url() -> str:
    base = config_value(
        "ragTools.embedding.baseUrl",
        "SMARTLEDGE_EMBEDDING_BASE_URL",
        DEFAULT_EMBEDDING_BASE_URL,
    ).strip()
    path = config_value(
        "ragTools.embedding.path",
        "SMARTLEDGE_EMBEDDING_PATH",
        DEFAULT_EMBEDDING_PATH,
    ).strip()
    if not base.endswith("/"):
        base = base + "/"
    return base + path.lstrip("/")


def _embedding_dimensions() -> int:
    return config_int(
        "ragTools.embedding.dimensions",
        "SMARTLEDGE_EMBEDDING_DIMENSIONS",
        DEFAULT_EMBEDDING_DIMENSIONS,
        0,
        4096,
    )


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
    """当前生效的重排模型名（云端 provider 时返回云端模型名）。"""
    if _rerank_provider() == PROVIDER_DASHSCOPE:
        return _dashscope_rerank_model()
    return _rerank_model_name()


def embedding_model_name() -> str:
    return _embedding_model_name()


def _rerank_provider() -> str:
    provider = config_value("ragTools.rerank.provider", "RAG_TOOLS_RERANK_PROVIDER", DEFAULT_RERANK_PROVIDER).lower()
    return PROVIDER_DASHSCOPE if provider == PROVIDER_DASHSCOPE else PROVIDER_LOCAL


def _dashscope_rerank_model() -> str:
    return config_value("ragTools.rerank.dashscope.model", "RAG_TOOLS_RERANK_DASHSCOPE_MODEL",
                        DEFAULT_DASHSCOPE_RERANK_MODEL).strip()


def _dashscope_rerank_url() -> str:
    return config_value("ragTools.rerank.dashscope.url", "RAG_TOOLS_RERANK_DASHSCOPE_URL",
                        DEFAULT_DASHSCOPE_RERANK_URL).strip()


def _dashscope_api_key() -> str:
    # 与 Java 侧共用同一个百炼密钥：配置了专用键就用专用键，否则回落到 ALI_BAI_LIAN_API_KEY。
    configured = config_value("ragTools.rerank.dashscope.apiKey", "RAG_TOOLS_RERANK_DASHSCOPE_API_KEY", "").strip()
    return configured or os.getenv("ALI_BAI_LIAN_API_KEY", "").strip()


def _dashscope_timeout() -> float:
    return config_float("ragTools.rerank.dashscope.timeoutSeconds", "RAG_TOOLS_RERANK_DASHSCOPE_TIMEOUT", 30.0)


def _rerank_max_attempts() -> int:
    return config_int("ragTools.rerank.maxAttempts", "RAG_TOOLS_RERANK_MAX_ATTEMPTS",
                      DEFAULT_RERANK_MAX_ATTEMPTS, 1, 5)


def _rerank_backoff() -> float:
    return config_float("ragTools.rerank.backoffSeconds", "RAG_TOOLS_RERANK_BACKOFF_SECONDS",
                        DEFAULT_RERANK_BACKOFF_SECONDS)


def _rerank_concurrency_slot():
    """进程内并发闸门（客户端限流）。配置变化时重建闸门，无需重启服务。"""
    global _rerank_semaphore, _rerank_semaphore_limit
    limit = config_int("ragTools.rerank.maxConcurrency", "RAG_TOOLS_RERANK_MAX_CONCURRENCY",
                       DEFAULT_RERANK_MAX_CONCURRENCY, 1, 32)
    with _rerank_semaphore_lock:
        if _rerank_semaphore is None or _rerank_semaphore_limit != limit:
            _rerank_semaphore = BoundedSemaphore(limit)
            _rerank_semaphore_limit = limit
        return _rerank_semaphore


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
    if _embedding_provider() == PROVIDER_OPENAI_COMPATIBLE:
        cloud_name = os.getenv("SMARTLEDGE_EMBEDDING_MODEL", "").strip()
        if cloud_name:
            return cloud_name
    return os.getenv("RAG_TOOLS_EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL).strip()
