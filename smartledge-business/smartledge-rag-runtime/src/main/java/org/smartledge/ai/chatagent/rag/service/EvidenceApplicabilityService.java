package org.smartledge.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.chatagent.rag.model.EvidenceApplicabilityPlan;
import org.smartledge.ai.chatagent.rag.model.EvidenceApplicabilityResult;
import org.smartledge.ai.rag.runtime.support.DocumentKnowledgeMetadataKeys;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 判断候选证据是否适用于 RetrievalPlan 已解释完成的目标实体边界。
 */
@Service
public class EvidenceApplicabilityService {

    public EvidenceApplicabilityResult evaluate(EvidenceApplicabilityPlan plan, RetrievalDocument document) {
        if (plan == null || document == null) {
            return EvidenceApplicabilityResult.unknown("missing evidence applicability plan or evidence");
        }
        if (!plan.allowsExclusion()) {
            return EvidenceApplicabilityResult.unknown("entity applicability suggestions are advisory");
        }
        List<String> targets = normalizedTerms(plan.targetEntities());
        List<String> excluded = normalizedTerms(plan.excludedEntities());
        if (targets.isEmpty() || excluded.isEmpty() || targets.stream().anyMatch(excluded::contains)) {
            return EvidenceApplicabilityResult.unknown("authorized entity applicability boundary is invalid");
        }
        String evidenceText = normalizedEvidenceText(document);
        boolean targetSupported = targets.stream().anyMatch(evidenceText::contains);
        if (targetSupported) {
            return EvidenceApplicabilityResult.applicable("target entity supported by evidence");
        }

        boolean excludedOnly = excluded.stream().anyMatch(evidenceText::contains);
        if (excludedOnly) {
            return EvidenceApplicabilityResult.unknown("advisory: evidence mentions excluded entity only");
        }

        return EvidenceApplicabilityResult.unknown("target entity not found in evidence");
    }

    private List<String> normalizedTerms(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        return terms.stream()
            .map(this::normalize)
            .filter(term -> term.length() >= 2)
            .distinct()
            .limit(8)
            .toList();
    }

    private String normalizedEvidenceText(RetrievalDocument document) {
        List<String> values = new ArrayList<>();
        if (document.getMetadata() != null) {
            Map<String, Object> metadata = document.getMetadata();
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME));
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME));
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_NAME));
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ENTITIES));
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.TITLE));
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.SECTION_PATH));
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.CANONICAL_PATH));
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME));
        }
        add(values, document.getText());
        return normalize(String.join(" ", values));
    }

    private void add(List<String> values, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (StrUtil.isNotBlank(text)) {
            values.add(text);
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]{}]+", "")
            .toLowerCase(Locale.ROOT)
            .trim();
    }
}
