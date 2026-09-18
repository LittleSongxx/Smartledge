import re
import json
import logging
import os
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

from fastapi import HTTPException

from rag_tools.config import config_float, config_value
from rag_tools.prompt_loader import load_prompt, render_prompt
from rag_tools.semantic_model import SemanticModelUnavailable, embed_texts, l2_normalize_vectors
from rag_tools.schemas.raptor_build import RaptorBuildRequest, RaptorBuildResponse, RaptorChunk, RaptorNode

logger = logging.getLogger(__name__)

MAX_SUMMARY_CHARS = 900
MAX_WEIGHTED_CHARS = 1200
MAX_KEYWORDS = 12
MAX_QUESTIONS = 6
MIN_QUALITY_SCORE = 0.35
LLM_CONTEXT_CHARS = 6500
CLUSTER_METHOD = "sklearn_agglomerative_average_cosine_v1"
TREE_BUILDER_METHOD = "balanced_hierarchical_v1"
MAX_LLM_CONCURRENCY = 5
AUTHORIZED_MAX_CLUSTER_SIZE = (2, 50)
AUTHORIZED_MAX_LEVELS = (1, 8)

CHINESE_STOP_TERMS = {
    "这个", "那个", "以及", "如果", "进行", "可以", "需要", "相关", "当前", "主要", "包括", "通过",
    "使用", "根据", "支持", "实现", "文档", "内容", "问题", "答案", "用户", "系统",
}


def build_raptor(request: RaptorBuildRequest) -> RaptorBuildResponse:
    chunks = [chunk for chunk in request.chunks if chunk and chunk.chunk_id and _text_of(chunk)]
    if not chunks:
        return RaptorBuildResponse(nodes=[])

    max_cluster_size = _authorized_budget(
        request.max_cluster_size, 6, AUTHORIZED_MAX_CLUSTER_SIZE, "maxClusterSize"
    )
    max_levels = _authorized_budget(
        request.max_levels, 3, AUTHORIZED_MAX_LEVELS, "maxLevels"
    )

    nodes: list[RaptorNode] = []
    current_items: list[_TreeItem] = [_TreeItem.from_chunk(chunk) for chunk in chunks]
    level = 1

    while current_items and level <= max_levels:
        cluster_result = _cluster_items(current_items, max_cluster_size)
        clusters = cluster_result.clusters
        level_nodes = _build_level_nodes(
            clusters, level, request.llm_summary_enabled, cluster_result.signals, request
        )
        nodes.extend(level_nodes)

        if len(level_nodes) <= 1:
            break

        current_items = [_TreeItem.from_node(node) for node in level_nodes]
        level += 1

    parent_by_child: dict[str, str] = {}
    level_map = defaultdict(list)
    for node in nodes:
        level_map[node.level].append(node)
    for parent_level in sorted(level_map.keys()):
        child_level = parent_level - 1
        if child_level < 1:
            continue
        for parent in level_map[parent_level]:
            for child_id in parent.child_node_ids:
                parent_by_child[child_id] = parent.id
    for node in nodes:
        node.parent_id = parent_by_child.get(node.id, "")

    return RaptorBuildResponse(nodes=nodes)


def _build_level_nodes(
    clusters: list[list["_TreeItem"]],
    level: int,
    llm_summary_enabled: bool,
    cluster_signals: dict[str, object],
    request: RaptorBuildRequest,
) -> list[RaptorNode]:
    if not clusters:
        return []
    concurrency = _llm_summary_concurrency(request) if llm_summary_enabled else 1
    if concurrency <= 1 or len(clusters) <= 1:
        return [
            _build_node(cluster, level, index, llm_summary_enabled, cluster_signals)
            for index, cluster in enumerate(clusters, start=1)
        ]
    workers = min(concurrency, len(clusters))
    logger.info(
        "RAPTOR level nodes build with bounded LLM concurrency: level=%s clusterCount=%s workers=%s",
        level,
        len(clusters),
        workers,
    )
    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="raptor-llm") as executor:
        return list(executor.map(
            lambda indexed_cluster: _build_node(
                indexed_cluster[1],
                level,
                indexed_cluster[0],
                llm_summary_enabled,
                cluster_signals,
            ),
            enumerate(clusters, start=1),
        ))


def _llm_summary_concurrency(request: RaptorBuildRequest) -> int:
    value = request.llm_concurrency
    if value is None or value < 1 or value > MAX_LLM_CONCURRENCY:
        raise HTTPException(status_code=422, detail="llmConcurrency must be supplied by the Java request")
    return value


class _TreeItem:
    def __init__(
        self,
        item_id: str,
        title: str,
        text: str,
        section_path: str,
        page_range: str,
        chunk_ids: list[int],
        parent_block_ids: list[int],
        child_node_ids: list[str],
        keywords: list[str],
        questions: list[str],
    ):
        self.item_id = item_id
        self.title = title
        self.text = text
        self.section_path = section_path
        self.page_range = page_range
        self.chunk_ids = chunk_ids
        self.parent_block_ids = parent_block_ids
        self.child_node_ids = child_node_ids
        self.keywords = keywords
        self.questions = questions

    @classmethod
    def from_chunk(cls, chunk: RaptorChunk) -> "_TreeItem":
        title = _first_non_blank(chunk.title, _last_section(chunk.section_path), f"chunk-{chunk.chunk_no or chunk.chunk_id}")
        text = _text_of(chunk)
        return cls(
            item_id=f"chunk-{chunk.chunk_id}",
            title=title,
            text=text,
            section_path=chunk.section_path or "",
            page_range=chunk.page_range or (str(chunk.page_no) if chunk.page_no is not None else ""),
            chunk_ids=[chunk.chunk_id],
            parent_block_ids=[chunk.parent_block_id] if chunk.parent_block_id else [],
            child_node_ids=[],
            keywords=_keywords_from_chunk(chunk, text),
            questions=_questions_from_chunk(chunk),
        )

    @classmethod
    def from_node(cls, node: RaptorNode) -> "_TreeItem":
        return cls(
            item_id=node.id,
            title=node.title,
            text=node.summary_with_weight or node.summary,
            section_path=node.section_path,
            page_range=node.page_range,
            chunk_ids=list(node.source_chunk_ids),
            parent_block_ids=list(node.source_parent_block_ids),
            child_node_ids=[node.id],
            keywords=list(node.keywords),
            questions=list(node.questions),
        )


@dataclass
class _ClusterResult:
    clusters: list[list[_TreeItem]]
    signals: dict[str, object]


def _cluster_items(items: list[_TreeItem], max_cluster_size: int) -> _ClusterResult:
    try:
        vectors = l2_normalize_vectors(embed_texts([_embedding_text(item) for item in items]))
    except SemanticModelUnavailable as exception:
        raise HTTPException(status_code=503, detail=str(exception)) from exception
    if len(items) <= max_cluster_size:
        cluster_indices = [list(range(len(items)))]
        return _ClusterResult(
            [_items_by_indices(items, indices) for indices in cluster_indices],
            _cluster_signals(cluster_indices, vectors, len(items), max_cluster_size),
        )

    similarity_matrix = _similarity_matrix(vectors)
    try:
        clusters = _sklearn_agglomerative_clusters(vectors, max_cluster_size)
    except ImportError as exception:
        raise HTTPException(
            status_code=503,
            detail="RAPTOR 聚类缺少 numpy/scikit-learn。云端模式请安装 requirements-cloud.txt 中的 scikit-learn。",
        ) from exception
    clusters = _merge_singletons(clusters, similarity_matrix, max_cluster_size, items)
    clusters = [sorted(cluster, key=lambda index: _first_chunk_id(items[index])) for cluster in clusters]
    clusters.sort(key=lambda cluster: _first_chunk_id(items[cluster[0]]) if cluster else 0)
    return _ClusterResult(
        [_items_by_indices(items, indices) for indices in clusters],
        _cluster_signals(clusters, vectors, len(items), max_cluster_size),
    )


def _build_node(
    cluster: list[_TreeItem],
    level: int,
    node_no: int,
    llm_summary_enabled: bool,
    cluster_signals: dict[str, object],
) -> RaptorNode:
    source_chunk_ids = _dedupe_ints(chunk_id for item in cluster for chunk_id in item.chunk_ids)
    source_parent_block_ids = _dedupe_ints(parent_id for item in cluster for parent_id in item.parent_block_ids)
    child_node_ids = _dedupe_strings(child_id for item in cluster for child_id in item.child_node_ids)
    keywords = _merge_keywords(cluster)
    questions = _merge_questions(cluster, keywords)
    section_path = _common_section_path([item.section_path for item in cluster])
    page_range = _merge_page_ranges([item.page_range for item in cluster])
    title = _node_title(cluster, section_path, keywords, level, node_no)
    summary_result = _summarize(cluster, title, keywords, llm_summary_enabled)
    summary = summary_result.text
    summary_with_weight = _limit(
        "\n".join(
            part for part in [
                f"标题：{title}",
                f"章节：{section_path}" if section_path else "",
                f"关键词：{'、'.join(keywords)}" if keywords else "",
                f"摘要：{summary}",
                f"典型问题：{'；'.join(questions)}" if questions else "",
            ]
            if part
        ),
        MAX_WEIGHTED_CHARS,
    )

    return RaptorNode(
        id=f"raptor-l{level}-{node_no}",
        level=level,
        nodeNo=node_no,
        title=title,
        summary=summary,
        summaryWithWeight=summary_with_weight,
        childNodeIds=child_node_ids,
        sourceChunkIds=source_chunk_ids,
        sourceParentBlockIds=source_parent_block_ids,
        sectionPath=section_path,
        pageRange=page_range,
        keywords=keywords,
        questions=questions,
        qualityScore=summary_result.quality_score,
        metadata={
            "itemCount": len(cluster),
            "clusterMethod": CLUSTER_METHOD,
            "treeBuilderMethod": TREE_BUILDER_METHOD,
            "clusterQualitySignals": {
                **cluster_signals,
                "nodeClusterSize": len(cluster),
            },
            "summaryStrategy": summary_result.strategy,
            "summaryQualityScore": summary_result.quality_score,
            "summaryQualitySignals": summary_result.signals,
            "firstChunkId": source_chunk_ids[0] if source_chunk_ids else None,
            "lastChunkId": source_chunk_ids[-1] if source_chunk_ids else None,
        },
    )


@dataclass
class _SummaryResult:
    text: str
    quality_score: float
    strategy: str
    signals: dict[str, object]


@dataclass
class _LlmSummaryResult:
    text: str
    status: str
    error: str = ""


def _text_of(chunk: RaptorChunk) -> str:
    return (chunk.content_with_weight or chunk.text or "").strip()


def _cluster_key(item: _TreeItem) -> str:
    if item.section_path:
        parts = [part.strip() for part in re.split(r"[/>\n]+", item.section_path) if part.strip()]
        if parts:
            return parts[0]
    if item.keywords:
        return item.keywords[0]
    return "default"


def _embedding_text(item: _TreeItem) -> str:
    return "\n".join(part for part in [
        item.title,
        item.section_path,
        "、".join(item.keywords),
        item.text,
    ] if part)


def _items_by_indices(items: list[_TreeItem], indices: list[int]) -> list[_TreeItem]:
    return [items[index] for index in indices]


def _similarity_matrix(vectors: list[list[float]]) -> list[list[float]]:
    matrix: list[list[float]] = []
    for left_index, left_vector in enumerate(vectors):
        row: list[float] = []
        for right_index, right_vector in enumerate(vectors):
            row.append(1.0 if left_index == right_index else _dot(left_vector, right_vector))
        matrix.append(row)
    return matrix


def _sklearn_agglomerative_clusters(vectors: list[list[float]], max_cluster_size: int) -> list[list[int]]:
    import numpy as np
    from sklearn.cluster import AgglomerativeClustering

    matrix = np.asarray(vectors, dtype=float)
    count = int(matrix.shape[0]) if matrix.ndim == 2 else 0
    if count <= 1:
        return [list(range(max(count, 0)))]
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    matrix = np.divide(matrix, np.maximum(norms, 1e-12))
    target = max(1, (count + max_cluster_size - 1) // max_cluster_size)
    target = min(target, count)
    if target == count:
        return [[index] for index in range(count)]
    labels = AgglomerativeClustering(n_clusters=target, metric="cosine", linkage="average").fit_predict(matrix)
    buckets: dict[int, list[int]] = {}
    for index, label in enumerate(labels):
        buckets.setdefault(int(label), []).append(index)
    clusters = list(buckets.values())
    return _split_oversized_clusters(clusters, matrix, max_cluster_size)


def _split_oversized_clusters(
    clusters: list[list[int]],
    matrix,
    max_cluster_size: int,
) -> list[list[int]]:
    result: list[list[int]] = []
    for cluster in clusters:
        if len(cluster) <= max_cluster_size:
            result.append(cluster)
            continue
        if len(cluster) <= 2:
            mid = max(1, len(cluster) // 2)
            result.append(cluster[:mid])
            result.append(cluster[mid:])
            continue
        sub_vectors = [matrix[index].tolist() for index in cluster]
        for part in _sklearn_agglomerative_clusters(sub_vectors, max_cluster_size):
            result.append([cluster[index] for index in part])
    return result


def _average_link_similarity(left_cluster: list[int], right_cluster: list[int], similarity_matrix: list[list[float]]) -> float:
    scores = [
        similarity_matrix[left_index][right_index]
        for left_index in left_cluster
        for right_index in right_cluster
    ]
    if not scores:
        return 0.0
    return sum(scores) / len(scores)


def _cluster_centroid_similarity(cluster: list[int], candidate: list[int], similarity_matrix: list[list[float]]) -> float:
    return _average_link_similarity(cluster, candidate, similarity_matrix)


def _merge_singletons(
    clusters: list[list[int]],
    similarity_matrix: list[list[float]],
    max_cluster_size: int,
    items: list[_TreeItem],
) -> list[list[int]]:
    changed = True
    while changed:
        changed = False
        singleton_positions = [
            index for index, cluster in enumerate(clusters)
            if len(cluster) == 1 and len(clusters) > 1
        ]
        for singleton_position in singleton_positions:
            if singleton_position >= len(clusters) or len(clusters[singleton_position]) != 1:
                continue
            singleton = clusters[singleton_position]
            best_position: int | None = None
            best_score = -1.0
            for candidate_position, candidate in enumerate(clusters):
                if candidate_position == singleton_position:
                    continue
                if len(candidate) + len(singleton) > max_cluster_size:
                    continue
                score = _cluster_centroid_similarity(singleton, candidate, similarity_matrix)
                if score > best_score:
                    best_score = score
                    best_position = candidate_position
            if best_position is None:
                continue
            clusters[best_position] = sorted(
                clusters[best_position] + singleton,
                key=lambda index: _first_chunk_id(items[index]),
            )
            del clusters[singleton_position]
            changed = True
            break
    return clusters


def _cluster_signals(
    clusters: list[list[int]],
    vectors: list[list[float]],
    input_item_count: int,
    max_cluster_size: int,
) -> dict[str, object]:
    cluster_sizes = [len(cluster) for cluster in clusters]
    cluster_count = len(clusters)
    singleton_count = sum(1 for size in cluster_sizes if size == 1)
    max_observed = max(cluster_sizes) if cluster_sizes else 0
    avg_size = sum(cluster_sizes) / max(1, cluster_count)
    matrix = _similarity_matrix(vectors) if vectors else []
    intra_scores: list[float] = []
    for cluster in clusters:
        if len(cluster) <= 1:
            continue
        pair_scores = [
            matrix[left][right]
            for left_index, left in enumerate(cluster)
            for right in cluster[left_index + 1:]
        ]
        if pair_scores:
            intra_scores.append(sum(pair_scores) / len(pair_scores))
    avg_intra = sum(intra_scores) / len(intra_scores) if intra_scores else 0.0
    compression_ratio = cluster_count / max(1, input_item_count)
    balance_score = 1.0 - (singleton_count / max(1, cluster_count))
    if max_observed > 0 and max_cluster_size > 0:
        balance_score *= min(1.0, avg_size / max_observed)
    return {
        "clusterMethod": CLUSTER_METHOD,
        "treeBuilderMethod": TREE_BUILDER_METHOD,
        "inputItemCount": input_item_count,
        "clusterCount": cluster_count,
        "avgClusterSize": round(avg_size, 4),
        "maxClusterSizeObserved": max_observed,
        "singletonClusterCount": singleton_count,
        "levelCompressionRatio": round(compression_ratio, 4),
        "avgIntraClusterSimilarity": round(avg_intra, 4),
        "treeBalanceScore": round(max(0.0, min(1.0, balance_score)), 4),
    }


def _authorized_budget(value: int | None, default: int, bounds: tuple[int, int], field_name: str) -> int:
    resolved = default if value is None else value
    low, high = bounds
    if not isinstance(resolved, int) or isinstance(resolved, bool) or not low <= resolved <= high:
        raise HTTPException(
            status_code=422,
            detail=f"{field_name} 必须在 {low}–{high} 之间，禁止静默收缩。收到: {value}",
        )
    return resolved


def _dot(left: list[float], right: list[float]) -> float:
    return sum(a * b for a, b in zip(left, right))


def _first_chunk_id(item: _TreeItem) -> int:
    return item.chunk_ids[0] if item.chunk_ids else 0


def _node_title(cluster: list[_TreeItem], section_path: str, keywords: list[str], level: int, node_no: int) -> str:
    if section_path:
        return _last_section(section_path)
    titles = [item.title for item in cluster if item.title]
    if titles:
        counter = Counter(titles)
        return counter.most_common(1)[0][0]
    if keywords:
        return "、".join(keywords[:3])
    return f"层级摘要 L{level}-{node_no}"


def _summarize(cluster: list[_TreeItem], title: str, keywords: list[str], llm_summary_enabled: bool) -> _SummaryResult:
    source_text = _summary_context(cluster)
    llm_result = _LlmSummaryResult("", "disabled")
    if llm_summary_enabled:
        llm_result = _llm_summarize(title, keywords, source_text)
        if llm_result.text:
            quality = _quality_score(llm_result.text, cluster, source_text, True)
            if quality.quality_score >= MIN_QUALITY_SCORE:
                return _SummaryResult(
                    text=_limit(llm_result.text, MAX_SUMMARY_CHARS),
                    quality_score=quality.quality_score,
                    strategy="llm_abstractive_openai_compatible_v1",
                    signals={
                        **quality.signals,
                        "llmSummaryRequested": True,
                        "llmSummaryStatus": "success",
                    },
                )
            llm_result = _LlmSummaryResult(llm_result.text, "low_quality", f"qualityScore {quality.quality_score} below {MIN_QUALITY_SCORE}")

    candidate_sentences: list[str] = []
    for item in cluster:
        for sentence in _split_sentences(item.text):
            normalized = sentence.strip()
            if 18 <= len(normalized) <= 260:
                candidate_sentences.append(normalized)
    if not candidate_sentences:
        candidate_sentences = [_limit(item.text, 220) for item in cluster if item.text]

    scored: list[tuple[float, int, str]] = []
    keyword_set = set(keywords)
    for index, sentence in enumerate(candidate_sentences):
        score = sum(1 for keyword in keyword_set if keyword and keyword in sentence)
        score += min(len(sentence), 180) / 180.0
        scored.append((score, index, sentence))
    scored.sort(key=lambda item: (-item[0], item[1]))

    selected = [sentence for _, _, sentence in scored[:4]]
    summary = "；".join(selected)
    if not summary:
        summary = f"{title} 相关内容覆盖 {len(cluster)} 个片段。"
    summary = _limit(summary, MAX_SUMMARY_CHARS)
    quality = _quality_score(summary, cluster, source_text, False)
    signals = {
        **quality.signals,
        "llmSummaryRequested": bool(llm_summary_enabled),
        "llmSummaryStatus": llm_result.status,
    }
    if llm_result.error:
        signals["llmSummaryError"] = _limit(llm_result.error, 300)
    return _SummaryResult(
        text=summary,
        quality_score=quality.quality_score,
        strategy="embedding_cluster_extractive_v3",
        signals=signals,
    )


def _summary_context(cluster: list[_TreeItem]) -> str:
    parts: list[str] = []
    for index, item in enumerate(cluster, start=1):
        header = f"[片段{index}] {item.title}".strip()
        body = _limit(item.text, 1400)
        if body:
            parts.append(header + "\n" + body)
    return _limit("\n\n".join(parts), LLM_CONTEXT_CHARS)


def _llm_summarize(title: str, keywords: list[str], source_text: str) -> _LlmSummaryResult:
    base_url = config_value("ragTools.llm.baseUrl", "RAG_TOOLS_LLM_BASE_URL", "").rstrip("/")
    api_key = config_value("ragTools.llm.apiKey", "RAG_TOOLS_LLM_API_KEY", "")
    model = config_value("ragTools.llm.model", "RAG_TOOLS_LLM_MODEL", "")
    if not base_url or not api_key or not model or not source_text:
        logger.info(
            "RAPTOR LLM summary skipped because config is incomplete: baseUrlConfigured=%s apiKeyConfigured=%s modelConfigured=%s sourceTextLength=%s",
            bool(base_url),
            bool(api_key),
            bool(model),
            len(source_text or ""),
        )
        return _LlmSummaryResult("", "config_incomplete", "baseUrl/apiKey/model/sourceText is incomplete")
    endpoint = _chat_completions_endpoint(base_url)
    system_prompt = load_prompt("raptor-summary-system.txt")
    user_prompt = render_prompt("raptor-summary-user.txt", {
        "title": title,
        "keywords": "、".join(keywords[:8]),
        "sourceText": source_text,
    })
    payload = {
        "model": model,
        "temperature": 0.2,
        "max_tokens": 700,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
    }
    request = urllib.request.Request(
        endpoint,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(
            request,
            timeout=config_float("ragTools.llm.timeoutSeconds", "RAG_TOOLS_LLM_TIMEOUT_SECONDS", 30.0),
        ) as response:
            body = response.read().decode("utf-8")
        parsed = json.loads(body)
        content = parsed.get("choices", [{}])[0].get("message", {}).get("content", "")
        summary = _extract_summary_text(content)
        if not summary:
            logger.warning("RAPTOR LLM summary returned empty content: endpoint=%s model=%s responsePreview=%s",
                           endpoint, model, _preview(body))
            return _LlmSummaryResult("", "empty_response", _preview(body))
        return _LlmSummaryResult(summary, "success")
    except Exception as exception:
        logger.warning("RAPTOR LLM summary failed: endpoint=%s model=%s error=%s",
                       endpoint, model, exception)
        return _LlmSummaryResult("", "failed", str(exception))


def _chat_completions_endpoint(base_url: str) -> str:
    normalized = (base_url or "").strip().rstrip("/")
    if normalized.endswith("/chat/completions"):
        return normalized
    if normalized.endswith("/compatible-mode"):
        normalized += "/v1"
    return normalized + "/chat/completions"


def _extract_summary_text(content: str) -> str:
    text = (content or "").strip()
    if not text:
        return ""
    text = re.sub(r"^```(?:json)?", "", text).strip()
    text = re.sub(r"```$", "", text).strip()
    try:
        parsed = json.loads(text)
        if isinstance(parsed, dict):
            return str(parsed.get("summary") or "").strip()
    except Exception:
        pass
    return text


def _quality_score(summary: str, cluster: list[_TreeItem], source_text: str, abstractive: bool) -> _SummaryResult:
    clean_summary = (summary or "").strip()
    source = source_text or ""
    if not clean_summary or not source:
        return _SummaryResult("", 0.0, "", {"reason": "empty"})
    summary_terms = set(_extract_terms(clean_summary))
    source_terms = set(_extract_terms(source))
    coverage = len(summary_terms & source_terms) / max(1, len(summary_terms))
    coverage = max(0.0, min(coverage, 1.0))
    length_score = min(len(clean_summary), MAX_SUMMARY_CHARS) / 260.0
    length_score = max(0.0, min(length_score, 1.0))
    source_sentence_count = sum(len(_split_sentences(item.text)) for item in cluster)
    summary_sentence_count = len(_split_sentences(clean_summary))
    compression = 1.0 if len(clean_summary) < max(240, len(source) * 0.65) else 0.35
    copy_ratio = _copy_ratio(clean_summary, source)
    abstraction_bonus = 0.12 if abstractive else 0.0
    score = 0.42 * coverage + 0.24 * length_score + 0.18 * compression + 0.16 * (1.0 - copy_ratio) + abstraction_bonus
    if summary_sentence_count <= 1 and source_sentence_count >= 4:
        score -= 0.08
    score = max(0.0, min(score, 1.0))
    return _SummaryResult(
        clean_summary,
        round(score, 4),
        "",
        {
            "termCoverage": round(coverage, 4),
            "lengthScore": round(length_score, 4),
            "compressionScore": round(compression, 4),
            "copyRatio": round(copy_ratio, 4),
            "summarySentenceCount": summary_sentence_count,
            "sourceSentenceCount": source_sentence_count,
            "abstractive": abstractive,
        },
    )


def _preview(value: str, limit: int = 300) -> str:
    normalized = re.sub(r"\s+", " ", value or "").strip()
    return normalized if len(normalized) <= limit else normalized[:limit] + "..."


def _copy_ratio(summary: str, source: str) -> float:
    sentences = _split_sentences(summary)
    if not sentences:
        return 1.0
    copied = 0
    for sentence in sentences:
        if len(sentence) >= 24 and sentence in source:
            copied += 1
    return copied / max(1, len(sentences))


def _split_sentences(text: str) -> list[str]:
    return [part.strip() for part in re.split(r"[。！？!?；;\n]+", text or "") if part.strip()]


def _keywords_from_chunk(chunk: RaptorChunk, text: str) -> list[str]:
    metadata_keywords = _parse_metadata_words(chunk.metadata.get("keywords") if chunk.metadata else None)
    inline_keywords = _extract_terms((chunk.title or "") + " " + (chunk.section_path or "") + " " + text)
    return _dedupe_strings([*metadata_keywords, *inline_keywords])[:MAX_KEYWORDS]


def _questions_from_chunk(chunk: RaptorChunk) -> list[str]:
    return _parse_metadata_words(chunk.metadata.get("questions") if chunk.metadata else None)[:MAX_QUESTIONS]


def _merge_keywords(cluster: list[_TreeItem]) -> list[str]:
    counter: Counter[str] = Counter()
    for item in cluster:
        for keyword in item.keywords:
            if keyword:
                counter[keyword] += 1
    return [keyword for keyword, _ in counter.most_common(MAX_KEYWORDS)]


def _merge_questions(cluster: list[_TreeItem], keywords: list[str]) -> list[str]:
    questions = _dedupe_strings(question for item in cluster for question in item.questions)
    if questions:
        return questions[:MAX_QUESTIONS]
    generated = []
    for keyword in keywords[:3]:
        generated.append(f"{keyword} 的核心内容是什么？")
    return generated[:MAX_QUESTIONS]


def _extract_terms(text: str) -> list[str]:
    normalized = (text or "").lower()
    terms = re.findall(r"[a-z][a-z0-9._/-]{2,40}", normalized)
    chinese_terms = re.findall(r"[\u4e00-\u9fff]{2,12}", normalized)
    for term in chinese_terms:
        if term in CHINESE_STOP_TERMS:
            continue
        if len(term) <= 6:
            terms.append(term)
            continue
        terms.extend(term[index:index + 4] for index in range(0, len(term) - 1, 2))
    return _dedupe_strings(terms)


def _parse_metadata_words(value: object) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return _dedupe_strings(str(item).strip() for item in value if str(item).strip())
    text = str(value).strip()
    if not text:
        return []
    text = text.strip("[]")
    return _dedupe_strings(part.strip().strip('"') for part in re.split(r"[,，、;；\n]+", text) if part.strip())


def _common_section_path(paths: list[str]) -> str:
    normalized_paths = [[part.strip() for part in re.split(r"[/>\n]+", path or "") if part.strip()] for path in paths if path]
    if not normalized_paths:
        return ""
    common: list[str] = []
    for index in range(min(len(path) for path in normalized_paths)):
        value = normalized_paths[0][index]
        if all(len(path) > index and path[index] == value for path in normalized_paths):
            common.append(value)
        else:
            break
    return " > ".join(common)


def _merge_page_ranges(page_ranges: list[str]) -> str:
    pages: list[int] = []
    for page_range in page_ranges:
        for number in re.findall(r"\d+", page_range or ""):
            pages.append(int(number))
    if not pages:
        return ""
    return str(min(pages)) if min(pages) == max(pages) else f"{min(pages)}-{max(pages)}"


def _last_section(section_path: str) -> str:
    parts = [part.strip() for part in re.split(r"[/>\n]+", section_path or "") if part.strip()]
    return parts[-1] if parts else ""


def _first_non_blank(*values: str) -> str:
    for value in values:
        if value:
            return value
    return ""


def _dedupe_ints(values) -> list[int]:
    result: list[int] = []
    seen: set[int] = set()
    for value in values:
        if value is None or value in seen:
            continue
        seen.add(value)
        result.append(value)
    return result


def _dedupe_strings(values) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        text = str(value).strip()
        if not text or text in seen:
            continue
        seen.add(text)
        result.append(text)
    return result


def _limit(value: str, max_chars: int) -> str:
    text = (value or "").strip()
    return text if len(text) <= max_chars else text[:max_chars]
