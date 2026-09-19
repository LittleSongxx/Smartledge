package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.enums.ChatQueryMode;
import org.smartledge.enums.KnowledgeBaseSelectionMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Complete retrieval execution contract assembled once before channel execution.
 * S04 makes the contract complete and serializable; S05 migrates every channel to consume it directly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalPlan {

    private RetrievalQuestionPlan questionPlan;

    @Builder.Default
    private ChatQueryMode chatMode = ChatQueryMode.DOCUMENT;

    @Builder.Default
    private RetrievalIntent primaryIntent = RetrievalIntent.GENERAL;

    @Builder.Default
    private List<RetrievalIntent> suggestedIntents = new ArrayList<>();

    @Builder.Default
    private KnowledgeBaseSelectionMode scopeMode = KnowledgeBaseSelectionMode.NONE;

    @Builder.Default
    private List<Long> knowledgeBaseIds = new ArrayList<>();

    @Builder.Default
    private List<Long> allowedDocumentScope = new ArrayList<>();

    @Builder.Default
    private List<Long> documentScope = new ArrayList<>();

    @Builder.Default
    private List<Long> taskScope = new ArrayList<>();

    private RetrievalMetadataFilters metadataFilters;

    @Builder.Default
    private EvidenceApplicabilityPlan evidenceApplicabilityPlan = EvidenceApplicabilityPlan.advisory(
        List.of(),
        List.of(),
        "NONE",
        "No entity applicability suggestion"
    );

    @Builder.Default
    private List<RetrievalChannelPlan> channels = new ArrayList<>();

    private StructureNavigationIntent structureNavigation;

    private DocumentNavigationAction navigationAction;

    private StructureNavigationResult structureNavigationResult;

    private ConversationStructureAnchor structureAnchor;

    private ConversationItemAnchor itemAnchor;

    private TableIntent tableIntent;

    private GraphIntent graphIntent;

    private RaptorIntent raptorIntent;

    private RetrievalRoutePlan routePlan;

    private RankFeatureBundle rankFeatures;

    private int candidateWindow;

    private int rerankWindow;

    private boolean rerankRequested;

    private int finalEvidenceBudget;

    /** 最终证据最低置信度（rerank 相关性分口径；0=关闭）。 */
    private double minEvidenceConfidence;

    private long subQuestionTimeoutMs;

    @Builder.Default
    private List<String> reasons = new ArrayList<>();

    private String source;

    public boolean hasChannelSelection() {
        return channels != null && !channels.isEmpty();
    }

    public RetrievalChannelPlan requireChannel(String channelName) {
        return channels == null
            ? missingChannel(channelName)
            : channels.stream()
                .filter(Objects::nonNull)
                .filter(channel -> Objects.equals(channelName, channel.getChannelName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("RetrievalPlan channel is absent: " + channelName));
    }

    public boolean isAutomaticDocumentScope() {
        return chatMode == ChatQueryMode.AUTO_DOCUMENT;
    }

    public String normalizedQuery() {
        return questionPlan == null ? "" : Objects.toString(questionPlan.getNormalizedQuery(), "");
    }

    public List<String> executionQueryTexts() {
        if (questionPlan == null || questionPlan.getExecutionQueries() == null) {
            return List.of();
        }
        return questionPlan.getExecutionQueries().stream()
            .filter(Objects::nonNull)
            .map(RetrievalExecutionQuery::getExecutionQuery)
            .filter(Objects::nonNull)
            .toList();
    }

    public Long recommendedDocumentHintId() {
        return routePlan == null ? null : routePlan.getTopDocumentHintId();
    }

    public Long recommendedTaskHintId() {
        return routePlan == null ? null : routePlan.getTopTaskHintId();
    }

    public String recommendedDocumentHintName() {
        RetrievalRouteCandidate candidate = recommendedDocumentCandidate();
        return candidate == null ? "" : Objects.toString(candidate.getDisplayName(), "");
    }

    public Long explicitlyAuthorizedDocumentId() {
        return hasExplicitDocumentAuthorization() ? documentScope.get(0) : null;
    }

    public Long explicitlyAuthorizedTaskId() {
        return hasExplicitDocumentAuthorization() ? taskScope.get(0) : null;
    }

    public String explicitlyAuthorizedDocumentName() {
        return hasExplicitDocumentAuthorization() ? recommendedDocumentHintName() : "";
    }

    private boolean hasExplicitDocumentAuthorization() {
        return chatMode == ChatQueryMode.DOCUMENT
            && routePlan != null
            && routePlan.getAuthorizationMode() == RouteScopeAuthorizationMode.EXPLICIT_DOCUMENT
            && documentScope != null
            && taskScope != null
            && documentScope.size() == 1
            && taskScope.size() == 1
            && Objects.equals(routePlan.getAuthorizedDocumentIds(), documentScope)
            && Objects.equals(routePlan.getAuthorizedTaskIds(), taskScope)
            && Objects.equals(routePlan.getTopDocumentHintId(), documentScope.get(0))
            && Objects.equals(routePlan.getTopTaskHintId(), taskScope.get(0));
    }

    private RetrievalRouteCandidate recommendedDocumentCandidate() {
        Long documentId = recommendedDocumentHintId();
        if (documentId == null || routePlan == null || routePlan.getCandidates() == null) {
            return null;
        }
        return routePlan.getCandidates().stream()
            .filter(Objects::nonNull)
            .filter(candidate -> Objects.equals(documentId, candidate.getDocumentId()))
            .findFirst()
            .orElse(null);
    }

    public boolean isChannelEnabled(String channelName) {
        if (channelName == null || channels == null) {
            return false;
        }
        return channels.stream()
            .anyMatch(channel -> channel != null && channel.isEnabled() && channelName.equals(channel.getChannelName()));
    }

    public List<String> enabledChannelNames() {
        if (channels == null) {
            return List.of();
        }
        return channels.stream()
            .filter(channel -> channel != null && channel.isEnabled())
            .map(RetrievalChannelPlan::getChannelName)
            .filter(Objects::nonNull)
            .toList();
    }

    public void validateForExecution() {
        if (questionPlan == null || isBlank(questionPlan.getNormalizedQuery())) {
            throw new IllegalArgumentException("RetrievalPlan normalized query is required");
        }
        if (questionPlan.getExecutionQueries() == null || questionPlan.getExecutionQueries().isEmpty()
            || questionPlan.getExecutionQueries().stream().anyMatch(query -> query == null || isBlank(query.getExecutionQuery()))) {
            throw new IllegalArgumentException("RetrievalPlan execution query is required for every sub-question");
        }
        requirePositiveIds(knowledgeBaseIds, "knowledge base scope");
        requirePositiveIds(allowedDocumentScope, "allowed document scope");
        requirePositiveIds(documentScope, "document scope");
        requirePositiveIds(taskScope, "task scope");
        if (scopeMode == null || scopeMode == KnowledgeBaseSelectionMode.NONE) {
            throw new IllegalArgumentException("RetrievalPlan knowledge base scope mode is required");
        }
        if (!allowedDocumentScope.containsAll(documentScope)) {
            throw new IllegalArgumentException("RetrievalPlan document scope must stay inside allowed document scope");
        }
        if (documentScope.size() != taskScope.size()) {
            throw new IllegalArgumentException("RetrievalPlan document scope and task scope must have the same size");
        }
        if (chatMode == null || primaryIntent == null || metadataFilters == null || evidenceApplicabilityPlan == null
            || routePlan == null || rankFeatures == null
            || tableIntent == null || graphIntent == null || raptorIntent == null) {
            throw new IllegalArgumentException("RetrievalPlan controlled filters, applicability, route, intents and rank features are required");
        }
        validateRouteAuthorization();
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("RetrievalPlan channel plans are required");
        }
        Set<String> channelNames = new HashSet<>();
        for (RetrievalChannelPlan channel : channels) {
            if (channel == null || isBlank(channel.getChannelName()) || !channelNames.add(channel.getChannelName())) {
                throw new IllegalArgumentException("RetrievalPlan channel names must be non-blank and unique");
            }
            if (channel.getTopK() <= 0 || channel.getBudget() <= 0 || channel.getTimeoutMs() <= 0L) {
                throw new IllegalArgumentException("RetrievalPlan channel topK, budget and timeout must be positive");
            }
            if (!Double.isFinite(channel.getWeight()) || channel.getWeight() < 0D) {
                throw new IllegalArgumentException("RetrievalPlan channel weight must be finite and non-negative");
            }
            if (!Double.isFinite(channel.getMinimumScore()) || channel.getMinimumScore() < 0D
                || !Double.isFinite(channel.getRelativeScoreFloor()) || channel.getRelativeScoreFloor() < 0D) {
                throw new IllegalArgumentException("RetrievalPlan channel score thresholds must be finite and non-negative");
            }
        }
        if (candidateWindow <= 0) {
            throw new IllegalArgumentException("RetrievalPlan candidate window must be positive");
        }
        if (rerankWindow <= 0 || rerankWindow > candidateWindow) {
            throw new IllegalArgumentException("RetrievalPlan rerank window must be positive and no larger than candidate window");
        }
        if (finalEvidenceBudget <= 0 || finalEvidenceBudget > rerankWindow) {
            throw new IllegalArgumentException("RetrievalPlan final evidence budget must be positive and no larger than rerank window");
        }
        if (subQuestionTimeoutMs <= 0L) {
            throw new IllegalArgumentException("RetrievalPlan sub-question timeout must be positive");
        }
    }

    private void requirePositiveIds(List<Long> values, String field) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(value -> value == null || value <= 0L)) {
            throw new IllegalArgumentException("RetrievalPlan " + field + " must contain positive IDs");
        }
    }

    private void validateRouteAuthorization() {
        RouteScopeAuthorizationMode mode = routePlan.getAuthorizationMode();
        List<Long> authorizedDocuments = routePlan.getAuthorizedDocumentIds();
        List<Long> authorizedTasks = routePlan.getAuthorizedTaskIds();
        if (mode == null || isBlank(routePlan.getScopeAuthorizationReason())
            || authorizedDocuments == null || authorizedTasks == null) {
            throw new IllegalArgumentException("RetrievalPlan route authorization is required");
        }
        if (!Objects.equals(documentScope, authorizedDocuments) || !Objects.equals(taskScope, authorizedTasks)) {
            throw new IllegalArgumentException("RetrievalPlan route authorization scope must equal execution scope");
        }
        Long topDocumentHintId = routePlan.getTopDocumentHintId();
        Long topTaskHintId = routePlan.getTopTaskHintId();
        if ((topDocumentHintId == null) != (topTaskHintId == null)) {
            throw new IllegalArgumentException("RetrievalPlan route recommendation document/task hint must be paired");
        }
        if (topDocumentHintId != null) {
            int hintIndex = documentScope.indexOf(topDocumentHintId);
            if (hintIndex < 0 || hintIndex >= taskScope.size() || !Objects.equals(taskScope.get(hintIndex), topTaskHintId)) {
                throw new IllegalArgumentException("RetrievalPlan route recommendation hint must match an authorized document/task pair");
            }
        }
        switch (mode) {
            case EXPLICIT_DOCUMENT -> {
                if (chatMode != ChatQueryMode.DOCUMENT || documentScope.size() != 1
                    || !Objects.equals(topDocumentHintId, documentScope.get(0))) {
                    throw new IllegalArgumentException("Explicit document authorization requires one matching DOCUMENT scope");
                }
            }
            case KNOWLEDGE_BASE_ALLOWED_SCOPE -> {
                if (chatMode != ChatQueryMode.AUTO_DOCUMENT || !Objects.equals(documentScope, allowedDocumentScope)) {
                    throw new IllegalArgumentException("Knowledge-base authorization requires the complete AUTO_DOCUMENT allowed scope");
                }
            }
            case CLARIFICATION_REQUIRED ->
                throw new IllegalArgumentException("Clarification-required route authorization is not executable");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private RetrievalChannelPlan missingChannel(String channelName) {
        throw new IllegalArgumentException("RetrievalPlan channel is absent: " + channelName);
    }
}
