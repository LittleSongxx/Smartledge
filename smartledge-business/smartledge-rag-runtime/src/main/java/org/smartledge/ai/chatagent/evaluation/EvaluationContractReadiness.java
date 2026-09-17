package org.smartledge.ai.chatagent.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exchange snapshot readiness is a deterministic contract checklist.
 * Family names must not impersonate retrieval relevance, answer relevance,
 * citation support, or faithfulness.
 */
public final class EvaluationContractReadiness {

    public static final String DETERMINISTIC_CONTRACT = "DETERMINISTIC_CONTRACT";
    public static final String RETRIEVAL_TRACE_READY = "RETRIEVAL_TRACE_READY";
    public static final String ANSWER_PRESENT = "ANSWER_PRESENT";
    public static final String CONTRACT_READY = "CONTRACT_READY";
    public static final String CITATION_BINDING_READY = "CITATION_BINDING_READY";

    private EvaluationContractReadiness() {
    }

    public record Request(
        List<String> factErrors,
        boolean hasCreatedAt,
        boolean hasTerminalAt,
        String conservationStatus,
        List<String> conservationReasons,
        boolean hasCodeCommit,
        boolean hasPromptVersion,
        boolean hasActualModels,
        boolean hasEffectiveConfig,
        boolean hasDocumentContentVersions,
        boolean hasRetrievalPlan,
        boolean hasChannelExecution,
        boolean graphHintProjectedButNotConsumed,
        String terminalStatus,
        boolean hasQuestion,
        boolean hasAnswer,
        boolean hasPromptManifest,
        boolean hasRenderedSource,
        boolean hasCitationBindingStage,
        boolean hasEligibleIdentities
    ) {
        public Request {
            factErrors = factErrors == null ? List.of() : List.copyOf(factErrors);
            conservationStatus = conservationStatus == null ? "" : conservationStatus;
            conservationReasons = conservationReasons == null ? List.of() : List.copyOf(conservationReasons);
            terminalStatus = terminalStatus == null ? "" : terminalStatus;
        }
    }

    public static List<EvaluationExchangeSnapshot.Readiness> evaluate(Request request) {
        Objects.requireNonNull(request, "request");

        List<String> deterministic = new ArrayList<>(request.factErrors());
        if (!request.hasCreatedAt()) {
            deterministic.add("MISSING_EXCHANGE_CREATED_AT");
        }
        if (!request.hasTerminalAt()) {
            deterministic.add("MISSING_EXCHANGE_TERMINAL_AT");
        }
        if (!"CONSERVED".equals(request.conservationStatus())) {
            deterministic.addAll(request.conservationReasons());
        }
        if (!request.hasCodeCommit()) {
            deterministic.add("MISSING_CODE_COMMIT");
        }
        if (!request.hasPromptVersion()) {
            deterministic.add("MISSING_PROMPT_VERSION");
        }
        if (!request.hasActualModels()) {
            deterministic.add("MISSING_ACTUAL_MODEL_IDENTITY");
        }
        if (!request.hasEffectiveConfig()) {
            deterministic.add("MISSING_EFFECTIVE_CONFIG");
        }
        if (!request.hasDocumentContentVersions()) {
            deterministic.add("MISSING_DOCUMENT_CONTENT_VERSION");
        }

        List<String> retrieval = new ArrayList<>();
        if (!request.hasRetrievalPlan()) {
            retrieval.add("MISSING_RETRIEVAL_PLAN");
        }
        if (!request.hasChannelExecution()) {
            retrieval.add("MISSING_CHANNEL_EXECUTION");
        }
        if (request.graphHintProjectedButNotConsumed()) {
            retrieval.add("MISSING_GRAPH_RAG_HINT_CONSUMPTION_FACT");
        }

        List<String> answer = terminalReasons(request);
        List<String> contractReady = new ArrayList<>(answer);
        if (!request.hasPromptManifest()) {
            contractReady.add("MISSING_PROMPT_MANIFEST");
        }
        if (!request.hasRenderedSource()) {
            contractReady.add("NO_RENDERED_SOURCE_EVIDENCE");
        }

        List<String> citation = new ArrayList<>(answer);
        if (!request.hasCitationBindingStage()) {
            citation.add("MISSING_CITATION_BINDING");
        }
        if (!request.hasEligibleIdentities()) {
            citation.add("NO_CITATION_ELIGIBLE_SOURCE");
        }
        if ("VIOLATED".equals(request.conservationStatus())) {
            citation.add("IDENTITY_CONSERVATION_VIOLATED");
        }

        return List.of(
            ready(DETERMINISTIC_CONTRACT, deterministic),
            ready(RETRIEVAL_TRACE_READY, retrieval),
            ready(ANSWER_PRESENT, answer),
            ready(CONTRACT_READY, contractReady),
            ready(CITATION_BINDING_READY, citation)
        );
    }

    private static List<String> terminalReasons(Request request) {
        List<String> reasons = new ArrayList<>();
        if (!"COMPLETED".equals(request.terminalStatus())) {
            reasons.add("TERMINAL_STATUS_" + (request.terminalStatus().isBlank() ? "UNKNOWN" : request.terminalStatus()));
        }
        if (!request.hasQuestion()) {
            reasons.add("MISSING_ORIGINAL_QUESTION");
        }
        if (!request.hasAnswer()) {
            reasons.add("MISSING_FINAL_ANSWER");
        }
        return reasons;
    }

    private static EvaluationExchangeSnapshot.Readiness ready(String family, List<String> reasons) {
        List<String> unique = reasons.stream().distinct().toList();
        return new EvaluationExchangeSnapshot.Readiness(family, unique.isEmpty(), unique);
    }
}
