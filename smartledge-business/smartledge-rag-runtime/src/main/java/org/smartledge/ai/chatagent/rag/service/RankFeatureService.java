package org.smartledge.ai.chatagent.rag.service;

import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;
import org.smartledge.ai.chatagent.rag.support.EvidenceQualityFeatures;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * rank feature 计算与观测。集中 build-time 索引/结构化 rank feature（chunk 类型 boost、KG 图谱质量/rank/pagerank）
 * 的 metadata boost 计算，以及 O9 观测 rank feature 展示串。
 *
 * <p>这些 build-time rank feature 只作内部弱排序特征，不来自问题词面 contains，
 * 也不反向参与是否可引用或是否过滤；rank feature 观测串只用于展示。</p>
 */
public class RankFeatureService {

    /**
     * fusion metadata boost：只来自 build-time 索引/结构化 rank feature（chunk 类型、KG 图谱质量/rank），
     * 不再用问题词面 contains 候选 metadata 计算临时 boost——该 contains 打分与 BM25/vector 主匹配重复，属关键词补丁。
     */
    public double calculateMetadataBoost(RetrievalDocument document, RetrievalPlan plan) {
        if (document == null || document.getMetadata() == null) {
            return 0D;
        }
        double boost = chunkTypeBoost(document) + EvidenceQualityFeatures.resolve(document).rankMetadataBoost();
        return Math.min(boost, hybridMaxMetadataBoost(plan));
    }

    private double chunkTypeBoost(RetrievalDocument document) {
        String chunkType = normalizeBoostText(safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_TYPE)));
        if (chunkType.isBlank()) {
            return 0D;
        }
        if ("table".equals(chunkType)) {
            return 0.03D;
        }
        if ("image".equals(chunkType) || "figure".equals(chunkType)) {
            return 0.02D;
        }
        return 0D;
    }

    /**
     * O9 rank feature 观测串：把只作内部弱排序特征的分量（fusion metadataBoost、KG 质量/pagerank、
     * RAPTOR 词面 boost 等）以可读文本汇总，供观测显式展示。仅观测，不反向参与排序或过滤（P11）。
     */
    public String resolveObservationRankFeature(Map<String, Object> scoreMetadata, Map<String, Object> rerankMetadata) {
        List<String> parts = new ArrayList<>();
        appendRankFeaturePart(parts, "metadataBoost", scoreMetadata.get(DocumentKnowledgeMetadataKeys.METADATA_BOOST));
        appendRankFeaturePart(parts, "kgQuality", scoreMetadata.get(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE));
        appendRankFeaturePart(parts, "kgPagerank", scoreMetadata.get(DocumentKnowledgeMetadataKeys.KG_PAGERANK));
        appendRankFeaturePart(parts, "kgRankBoost", scoreMetadata.get(DocumentKnowledgeMetadataKeys.KG_RANK_BOOST));
        appendTextFeaturePart(parts, "sourceCapability", scoreMetadata.get(DocumentKnowledgeMetadataKeys.EVIDENCE_SOURCE_CAPABILITY));
        appendPresentNumberPart(parts, "groundingConfidence", scoreMetadata.get(DocumentKnowledgeMetadataKeys.EVIDENCE_GROUNDING_CONFIDENCE));
        appendPresentNumberPart(parts, "quoteCoverage", scoreMetadata.get(DocumentKnowledgeMetadataKeys.EVIDENCE_QUOTE_COVERAGE));
        appendTextFeaturePart(parts, "sourceKind", scoreMetadata.get(DocumentKnowledgeMetadataKeys.EVIDENCE_SOURCE_KIND));
        appendTextFeaturePart(parts, "qualitySignals", scoreMetadata.get(DocumentKnowledgeMetadataKeys.EVIDENCE_QUALITY_SIGNALS));
        appendTextFeaturePart(parts, "qualitySignalSource", scoreMetadata.get(DocumentKnowledgeMetadataKeys.EVIDENCE_QUALITY_SIGNAL_SOURCE));
        String raptorRankFeature = safeText(scoreMetadata.get(DocumentKnowledgeMetadataKeys.RANK_FEATURE));
        if (raptorRankFeature.isBlank()) {
            raptorRankFeature = safeText(rerankMetadata.get(DocumentKnowledgeMetadataKeys.RANK_FEATURE));
        }
        if (!raptorRankFeature.isBlank()) {
            parts.add(raptorRankFeature);
        }
        return String.join("; ", parts);
    }

    private void appendRankFeaturePart(List<String> parts, String label, Object value) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (numeric != 0D) {
                parts.add(String.format("%s=%.4f", label, numeric));
            }
        }
    }

    private void appendPresentNumberPart(List<String> parts, String label, Object value) {
        if (value instanceof Number number) {
            parts.add(String.format("%s=%.4f", label, number.doubleValue()));
        }
    }

    private void appendTextFeaturePart(List<String> parts, String label, Object value) {
        String text = safeText(value);
        if (!text.isBlank()) {
            parts.add(label + "=" + text);
        }
    }

    private double hybridMaxMetadataBoost(RetrievalPlan plan) {
        return plan == null || plan.getRankFeatures() == null
            ? 1D
            : Math.max(0D, plan.getRankFeatures().getMaxMetadataBoost());
    }

    private String normalizeBoostText(String value) {
        return safeText(value)
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]{}]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
