package org.smartledge.ai.chatagent.rag.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * @description: RAG Prompt 装配结果
 * @author: Song
 **/

public final class RagPromptAssemblyResult {

    private final String systemPrompt;

    private final String userPrompt;

    private final int totalBudget;

    private final int perSubQuestionBudget;

    private final int inputReferenceCount;

    private final List<PromptReferenceDecision> referenceManifest;

    private final List<PromptRenderedSourceEvidence> renderedSourceEvidence;

    private final List<String> renderedSourceIdentities;

    private final List<String> renderedContextIdentities;

    private final List<AnswerShapeRequirement> answerShapeRequirements;

    public RagPromptAssemblyResult(String systemPrompt,
                                   String userPrompt,
                                   int totalBudget,
                                   int perSubQuestionBudget,
                                   int inputReferenceCount,
                                   List<PromptReferenceDecision> referenceManifest,
                                   List<PromptRenderedSourceEvidence> renderedSourceEvidence,
                                   List<AnswerShapeRequirement> answerShapeRequirements) {
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.userPrompt = userPrompt == null ? "" : userPrompt;
        this.totalBudget = totalBudget;
        this.perSubQuestionBudget = perSubQuestionBudget;
        this.inputReferenceCount = inputReferenceCount;

        List<PromptReferenceDecision> manifestSnapshot = List.copyOf(referenceManifest);
        if (manifestSnapshot.size() != inputReferenceCount) {
            throw new IllegalArgumentException("reference manifest size must equal input reference count");
        }
        Set<Integer> inputOrdinals = new HashSet<>();
        for (PromptReferenceDecision decision : manifestSnapshot) {
            if (!inputOrdinals.add(decision.inputOrdinal())) {
                throw new IllegalArgumentException("reference manifest inputOrdinal must be unique");
            }
        }
        List<PromptReferenceDecision> orderedManifest = new ArrayList<>(manifestSnapshot);
        orderedManifest.sort(Comparator.comparingInt(PromptReferenceDecision::inputOrdinal));
        for (int expectedOrdinal = 0; expectedOrdinal < orderedManifest.size(); expectedOrdinal++) {
            if (orderedManifest.get(expectedOrdinal).inputOrdinal() != expectedOrdinal) {
                throw new IllegalArgumentException(
                    "reference manifest inputOrdinal must cover [0, inputReferenceCount)"
                );
            }
        }
        this.referenceManifest = List.copyOf(orderedManifest);

        List<PromptRenderedSourceEvidence> sourceEvidenceSnapshot = List.copyOf(renderedSourceEvidence);
        LinkedHashSet<String> sourceIdentitySet = new LinkedHashSet<>();
        for (PromptRenderedSourceEvidence evidence : sourceEvidenceSnapshot) {
            if (!sourceIdentitySet.add(evidence.identity())) {
                throw new IllegalArgumentException("rendered source evidence identity must be unique");
            }
        }
        List<String> manifestSourceIdentities = projectIdentities(
            this.referenceManifest,
            PromptReferenceDisposition.PROMPT_RENDERED_SOURCE
        );
        List<String> sourceIdentities = List.copyOf(sourceIdentitySet);
        if (!manifestSourceIdentities.equals(sourceIdentities)) {
            throw new IllegalArgumentException("rendered source evidence must match the reference manifest");
        }
        this.renderedSourceEvidence = sourceEvidenceSnapshot;
        this.renderedSourceIdentities = sourceIdentities;
        this.renderedContextIdentities = projectIdentities(
            this.referenceManifest,
            PromptReferenceDisposition.PROMPT_RENDERED_CONTEXT
        );
        LinkedHashSet<AnswerShapeRequirement> requirementSet = new LinkedHashSet<>();
        if (answerShapeRequirements != null) {
            answerShapeRequirements.stream()
                .filter(requirement -> requirement != null)
                .forEach(requirementSet::add);
        }
        this.answerShapeRequirements = List.copyOf(requirementSet);
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public int getTotalBudget() {
        return totalBudget;
    }

    public int getPerSubQuestionBudget() {
        return perSubQuestionBudget;
    }

    public int getInputReferenceCount() {
        return inputReferenceCount;
    }

    public List<PromptReferenceDecision> getReferenceManifest() {
        return referenceManifest;
    }

    public List<PromptRenderedSourceEvidence> getRenderedSourceEvidence() {
        return renderedSourceEvidence;
    }

    public List<String> getRenderedSourceIdentities() {
        return renderedSourceIdentities;
    }

    public List<String> getRenderedContextIdentities() {
        return renderedContextIdentities;
    }

    public List<AnswerShapeRequirement> getAnswerShapeRequirements() {
        return answerShapeRequirements;
    }

    private static List<String> projectIdentities(List<PromptReferenceDecision> manifest,
                                                  PromptReferenceDisposition disposition) {
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        for (PromptReferenceDecision decision : manifest) {
            if (decision.disposition() == disposition && !decision.identity().isBlank()) {
                identities.add(decision.identity());
            }
        }
        return List.copyOf(identities);
    }
}
