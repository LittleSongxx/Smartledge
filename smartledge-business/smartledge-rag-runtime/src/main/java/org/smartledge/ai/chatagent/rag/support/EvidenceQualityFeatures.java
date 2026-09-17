package org.smartledge.ai.chatagent.rag.support;

import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.chatagent.rag.model.CitationEvidenceType;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.ai.rag.runtime.support.EvidenceMetadataPriority;
import org.smartledge.enums.RetrievalChannelEnum;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 通用证据质量特征的唯一解释位置。
 *
 * <p>source capability 和 grounding 只由稳定 evidence identity 与原文绑定事实决定；KG relation type、
 * query-plan 来源、n-hop 标签和 channel 来源都不能把候选升级为高质量 Source。领域构建期提供的数值质量、
 * rank boost 与 PageRank 只作为受限的弱排序信号。</p>
 */
public record EvidenceQualityFeatures(
    String sourceCapability,
    double groundingConfidence,
    double quoteCoverage,
    String sourceKind,
    List<String> qualitySignals,
    List<String> qualitySignalSources,
    Double domainQualityScore,
    Double domainRankBoost,
    Double domainPagerank
) {

    public static final String CITATION_CAPABLE = "CITATION_CAPABLE";
    public static final String CONTEXT_ONLY = "CONTEXT_ONLY";

    public EvidenceQualityFeatures {
        qualitySignals = qualitySignals == null ? List.of() : List.copyOf(qualitySignals);
        qualitySignalSources = qualitySignalSources == null ? List.of() : List.copyOf(qualitySignalSources);
    }

    public static EvidenceQualityFeatures resolve(RetrievalDocument document) {
        Map<String, Object> metadata = document == null ? null : document.getMetadata();
        boolean citationCapable = EvidenceIdentityResolver.isCitationCapable(document);
        double quoteCoverage = quoteCoverage(document, citationCapable);
        Double qualityScore = numericValue(metadata, DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE);
        Double rankBoost = numericValue(metadata, DocumentKnowledgeMetadataKeys.KG_RANK_BOOST);
        Double pagerank = numericValue(metadata, DocumentKnowledgeMetadataKeys.KG_PAGERANK);

        LinkedHashSet<String> signals = new LinkedHashSet<>();
        LinkedHashSet<String> signalSources = new LinkedHashSet<>();
        signalSources.add("EVIDENCE_IDENTITY_RESOLVER");
        signals.add(citationCapable ? "SOURCE_IDENTITY_RESOLVED" : "SOURCE_IDENTITY_UNRESOLVED");
        if (quoteCoverage > 0D) {
            signals.add("QUOTE_BOUND");
        }
        if (hasText(metadata, DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET)) {
            signalSources.add("ORIGINAL_SNIPPET");
        }
        else if (citationCapable && document != null && StrUtil.isNotBlank(document.getText())) {
            signalSources.add("DOCUMENT_TEXT");
        }
        if (qualityScore != null) {
            signals.add("DOMAIN_QUALITY_PRESENT");
            signalSources.add("KG_QUALITY_SCORE");
        }
        if (rankBoost != null) {
            signals.add("DOMAIN_RANK_PRESENT");
            signalSources.add("KG_RANK_BOOST");
        }
        if (pagerank != null) {
            signals.add("DOMAIN_PAGERANK_PRESENT");
            signalSources.add("KG_PAGERANK");
        }

        return new EvidenceQualityFeatures(
            citationCapable ? CITATION_CAPABLE : CONTEXT_ONLY,
            citationCapable && quoteCoverage > 0D ? 1D : 0D,
            quoteCoverage,
            sourceKind(document, citationCapable),
            new ArrayList<>(signals),
            new ArrayList<>(signalSources),
            qualityScore,
            rankBoost,
            pagerank
        );
    }

    public void writeTo(Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        metadata.put(DocumentKnowledgeMetadataKeys.EVIDENCE_SOURCE_CAPABILITY, sourceCapability);
        metadata.put(DocumentKnowledgeMetadataKeys.EVIDENCE_GROUNDING_CONFIDENCE, groundingConfidence);
        metadata.put(DocumentKnowledgeMetadataKeys.EVIDENCE_QUOTE_COVERAGE, quoteCoverage);
        metadata.put(DocumentKnowledgeMetadataKeys.EVIDENCE_SOURCE_KIND, sourceKind);
        metadata.put(DocumentKnowledgeMetadataKeys.EVIDENCE_QUALITY_SIGNALS, String.join(",", qualitySignals));
        metadata.put(DocumentKnowledgeMetadataKeys.EVIDENCE_QUALITY_SIGNAL_SOURCE, String.join(",", qualitySignalSources));
    }

    public boolean citationCapable() {
        return CITATION_CAPABLE.equals(sourceCapability);
    }

    public boolean sourceGrounded() {
        return citationCapable() && groundingConfidence > 0D && quoteCoverage > 0D;
    }

    public boolean meetsDomainQuality(double threshold) {
        return domainQualityScore == null || domainQualityScore >= threshold;
    }

    /**
     * 用于既有 reserve 的候选比较。S08-S09 收口 policy 前，reserve 只能替换通用质量更低的 Source。
     */
    public double selectionPriority(double relevanceScore) {
        return relevanceScore + genericQualityScore() * 0.25D;
    }

    /**
     * 同一候选合并时选择 metadata producer；能力/grounding 优先，数值质量和原始相关性只作次级信号。
     */
    public double metadataPriority(double relevanceScore) {
        return EvidenceMetadataPriority.score(
            citationCapable(),
            groundingConfidence,
            quoteCoverage,
            domainQualityScore,
            domainRankBoost,
            domainPagerank,
            relevanceScore
        );
    }

    /**
     * build-time 数值质量的受限 metadata boost；不读取 relation name、query plan、n-hop 或来源标签。
     */
    public double rankMetadataBoost() {
        return Math.min(0.16D,
            normalized(domainQualityScore) * 0.06D
                + normalized(domainRankBoost) * 0.06D
                + normalized(domainPagerank) * 0.04D);
    }

    private double genericQualityScore() {
        return groundingConfidence * 0.40D
            + quoteCoverage * 0.25D
            + normalized(domainQualityScore) * 0.20D
            + normalized(domainRankBoost) * 0.10D
            + normalized(domainPagerank) * 0.05D;
    }

    private static String sourceKind(RetrievalDocument document, boolean citationCapable) {
        if (citationCapable) {
            CitationEvidenceType type = EvidenceIdentityResolver.citationEvidenceType(document);
            return type.name();
        }
        Map<String, Object> metadata = document == null ? null : document.getMetadata();
        if (isGraphMetadata(metadata)) {
            return isGraphCommunitySummary(metadata) ? "GRAPH_COMMUNITY_SUMMARY" : "GRAPH_CONTEXT";
        }
        String sourceType = text(metadata, DocumentKnowledgeMetadataKeys.SOURCE_TYPE);
        if ("RAPTOR".equalsIgnoreCase(sourceType)) {
            return "RAPTOR_CONTEXT";
        }
        if (metadata != null && metadata.get(DocumentKnowledgeMetadataKeys.TABLE_ID) != null) {
            return "TABLE_CONTEXT";
        }
        return CONTEXT_ONLY;
    }

    private static boolean isGraphMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return false;
        }
        String sourceType = text(metadata, DocumentKnowledgeMetadataKeys.SOURCE_TYPE);
        String channel = text(metadata, DocumentKnowledgeMetadataKeys.CHANNEL);
        return "GRAPH_RAG".equalsIgnoreCase(sourceType)
            || RetrievalChannelEnum.GRAPH_RAG.getName().equalsIgnoreCase(channel)
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID) != null
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID) != null
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID) != null
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_ID) != null
            || hasText(metadata, DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY);
    }

    private static boolean isGraphCommunitySummary(Map<String, Object> metadata) {
        if (metadata == null) {
            return false;
        }
        Object value = metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY_ONLY);
        return Boolean.TRUE.equals(value) || value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static double quoteCoverage(RetrievalDocument document, boolean citationCapable) {
        if (!citationCapable || document == null) {
            return 0D;
        }
        String text = StrUtil.blankToDefault(document.getText(), "").trim();
        String sourceQuote = text(document.getMetadata(), DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET);
        if (sourceQuote.isBlank()) {
            return text.isBlank() ? 0D : 1D;
        }
        if (text.equals(sourceQuote)) {
            return 1D;
        }
        if (text.contains(sourceQuote)) {
            return ratio(sourceQuote.length(), text.length());
        }
        if (sourceQuote.contains(text)) {
            return ratio(text.length(), sourceQuote.length());
        }
        return 0D;
    }

    private static double ratio(int covered, int total) {
        return total <= 0 ? 0D : Math.min(1D, Math.max(0D, (double) covered / total));
    }

    private static Double numericValue(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static double normalized(Double value) {
        return value == null ? 0D : Math.min(1D, Math.max(0D, value));
    }

    private static boolean hasText(Map<String, Object> metadata, String key) {
        return !text(metadata, key).isBlank();
    }

    private static String text(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return "";
        }
        Object value = metadata.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
