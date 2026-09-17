package org.smartledge.ai.knowledge.augmentation.support;

import cn.hutool.core.util.StrUtil;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps source-grounded relation facts separate from optional retrieval labels.
 */
public final class GraphRagRelationAuthority {

    public static final String SUPPORT_MODE_EXPLICIT_ACTION = "EXPLICIT_ACTION";
    public static final String SUPPORT_MODE_STRUCTURED_ROW = "STRUCTURED_ROW";
    public static final String STORAGE_TYPE_GROUNDED_ACTION = "GROUNDED_ACTION";
    public static final String STORAGE_TYPE_ASSOCIATED_WITH = "ASSOCIATED_WITH";
    public static final String FACT_AUTHORITY_GROUNDED = "grounded_predicate_and_quote";
    public static final String FACT_AUTHORITY_STRUCTURED_ROW = "grounded_table_row_and_column";
    public static final String TYPE_AUTHORITY_LABEL_ONLY = "non_authoritative_retrieval_label";
    public static final String MAPPING_STATUS_PROVIDED_LABEL_ONLY = "provided_label_only";
    public static final String MAPPING_STATUS_DEFAULTED_LABEL_ONLY = "defaulted_label_only";

    private static final String PRESENTATION_FALLBACK = "related";
    private static final int MAX_PRESENTATION_PREDICATE_CHARS = 120;

    private GraphRagRelationAuthority() {
    }

    public static String effectiveStorageType(String supportMode) {
        return SUPPORT_MODE_EXPLICIT_ACTION.equals(normalizeType(supportMode))
            ? STORAGE_TYPE_GROUNDED_ACTION
            : STORAGE_TYPE_ASSOCIATED_WITH;
    }

    public static String mappingStatus(String requestedType) {
        return StrUtil.isBlank(requestedType)
            ? MAPPING_STATUS_DEFAULTED_LABEL_ONLY
            : MAPPING_STATUS_PROVIDED_LABEL_ONLY;
    }

    public static String mappingReason(String requestedType) {
        return StrUtil.isBlank(requestedType)
            ? "no advisor relation label provided; grounded predicate and quote remain the fact authority"
            : "advisor relation label retained as non-authoritative metadata; grounded predicate and quote remain the fact authority";
    }

    public static String groundedDescription(String sourceName,
                                             String predicateQuoteText,
                                             String targetName,
                                             String fallbackDescription) {
        String predicate = presentationPredicate(predicateQuoteText);
        if (StrUtil.isNotBlank(sourceName) && StrUtil.isNotBlank(predicate) && StrUtil.isNotBlank(targetName)) {
            return sourceName.trim() + " " + predicate + " " + targetName.trim();
        }
        return StrUtil.blankToDefault(fallbackDescription, "").trim();
    }

    public static String presentationLabel(Map<String, Object> metadata) {
        List<String> predicates = groundedPredicates(metadata);
        return predicates.isEmpty() ? PRESENTATION_FALLBACK : String.join(" / ", predicates);
    }

    public static List<String> groundedPredicates(Map<String, Object> metadata) {
        LinkedHashSet<String> predicates = new LinkedHashSet<>();
        collectGroundedPredicates(metadata, predicates, 0);
        return List.copyOf(predicates);
    }

    private static void collectGroundedPredicates(Object value,
                                                   LinkedHashSet<String> predicates,
                                                   int depth) {
        if (value == null || depth > 6) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectGroundedPredicates(item, predicates, depth + 1);
            }
            return;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }

        String authority = text(map.get("relationFactAuthority"));
        if (FACT_AUTHORITY_GROUNDED.equals(authority) || FACT_AUTHORITY_STRUCTURED_ROW.equals(authority)) {
            addPredicate(predicates, map.get("predicateQuoteText"));
            Object aggregatedPredicates = map.get("groundedPredicateTexts");
            if (aggregatedPredicates instanceof Collection<?> collection) {
                collection.forEach(item -> addPredicate(predicates, item));
            }
        }
        collectGroundedPredicates(map.get("sourceMetadata"), predicates, depth + 1);
    }

    private static void addPredicate(LinkedHashSet<String> predicates, Object value) {
        String predicate = presentationPredicate(text(value));
        if (StrUtil.isNotBlank(predicate)) {
            predicates.add(predicate);
        }
    }

    private static String presentationPredicate(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return StrUtil.maxLength(normalized, MAX_PRESENTATION_PREDICATE_CHARS);
    }

    private static String normalizeType(String value) {
        return StrUtil.blankToDefault(value, "").trim().toUpperCase(Locale.ROOT);
    }

    public static String factAuthority(String supportMode) {
        return SUPPORT_MODE_STRUCTURED_ROW.equals(normalizeType(supportMode))
                ? FACT_AUTHORITY_STRUCTURED_ROW : FACT_AUTHORITY_GROUNDED;
    }

    private static String text(Object value) {
        return value instanceof CharSequence sequence ? sequence.toString().trim() : "";
    }
}
