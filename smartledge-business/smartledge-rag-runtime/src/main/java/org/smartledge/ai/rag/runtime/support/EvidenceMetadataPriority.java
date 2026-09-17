package org.smartledge.ai.rag.runtime.support;

import org.smartledge.ai.rag.runtime.model.RetrievalDocument;

import java.util.Map;

/**
 * Stable metadata-producer ordering shared by runtime consumers and GraphRAG providers.
 * Domain quality values remain bounded weak signals; stable source grounding dominates.
 */
public final class EvidenceMetadataPriority {

    private EvidenceMetadataPriority() {
    }

    public static double forGraphMetadata(RetrievalDocument document, double relevanceScore) {
        Map<String, Object> metadata = document == null ? null : document.getMetadata();
        boolean groundedQuote = asLong(metadata, DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID) != null
            && asLong(metadata, DocumentKnowledgeMetadataKeys.CHUNK_ID) != null
            && hasText(metadata, DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET);
        double quoteCoverage = quoteCoverage(document, groundedQuote);
        return score(
            groundedQuote,
            groundedQuote && quoteCoverage > 0D ? 1D : 0D,
            quoteCoverage,
            numericValue(metadata, DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE),
            numericValue(metadata, DocumentKnowledgeMetadataKeys.KG_RANK_BOOST),
            numericValue(metadata, DocumentKnowledgeMetadataKeys.KG_PAGERANK),
            relevanceScore
        );
    }

    public static double score(boolean citationCapable,
                               double groundingConfidence,
                               double quoteCoverage,
                               Double domainQualityScore,
                               Double domainRankBoost,
                               Double domainPagerank,
                               double relevanceScore) {
        return (citationCapable ? 100D : 0D)
            + groundingConfidence * 20D
            + quoteCoverage * 10D
            + normalized(domainQualityScore) * 4D
            + normalized(domainRankBoost) * 3D
            + normalized(domainPagerank) * 2D
            + Math.max(0D, relevanceScore) * 0.01D;
    }

    private static double quoteCoverage(RetrievalDocument document, boolean groundedQuote) {
        if (!groundedQuote || document == null) {
            return 0D;
        }
        String text = text(document.getText());
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

    private static Long asLong(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean hasText(Map<String, Object> metadata, String key) {
        return !text(metadata, key).isBlank();
    }

    private static String text(Map<String, Object> metadata, String key) {
        return metadata == null ? "" : text(metadata.get(key));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double normalized(Double value) {
        return value == null ? 0D : Math.min(1D, Math.max(0D, value));
    }
}
