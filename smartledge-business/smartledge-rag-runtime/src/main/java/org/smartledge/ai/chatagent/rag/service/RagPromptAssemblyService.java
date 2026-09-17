package org.smartledge.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;
import org.smartledge.ai.chatagent.rag.model.AnswerShapePlan;
import org.smartledge.ai.chatagent.rag.model.AnswerShapeRequirement;
import org.smartledge.ai.chatagent.rag.model.AnswerHistoryContext;
import org.smartledge.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.smartledge.ai.chatagent.rag.model.EvidenceApplicabilityResult;
import org.smartledge.ai.chatagent.rag.model.EvidenceIdentity;
import org.smartledge.ai.chatagent.rag.model.EvidenceKind;
import org.smartledge.ai.chatagent.rag.model.PromptReferenceDecision;
import org.smartledge.ai.chatagent.rag.model.PromptReferenceDisposition;
import org.smartledge.ai.chatagent.rag.model.PromptRenderedSourceEvidence;
import org.smartledge.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.smartledge.ai.chatagent.rag.model.RagPromptAssemblyResult;
import org.smartledge.ai.chatagent.rag.model.RagRetrievalContext;
import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;
import org.smartledge.ai.chatagent.rag.model.SubQuestionEvidence;
import org.smartledge.ai.chatagent.rag.support.EvidenceCandidateNormalizer;
import org.smartledge.ai.chatagent.rag.support.EvidenceIdentityResolver;
import org.smartledge.ai.chatagent.rag.support.SearchReferenceMapper;
import org.smartledge.ai.prompt.PromptTemplateNames;
import org.smartledge.ai.prompt.PromptTemplateService;
import org.smartledge.ai.rag.runtime.model.RetrievalDocument;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @description: 服务层
 * @author: Song
 **/

@Service
public class RagPromptAssemblyService {

    private final ChatRagProperties properties;
    private final PromptTemplateService promptTemplateService;

    public RagPromptAssemblyService(ChatRagProperties properties,
                                    PromptTemplateService promptTemplateService) {
        this.properties = properties;
        this.promptTemplateService = promptTemplateService;
    }

    public String buildSystemPrompt() {
        return promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_SYSTEM, Map.of());
    }

    public String buildUserPrompt(ConversationExecutionPlan plan, RagRetrievalContext context) {
        return assemble(plan, context).getUserPrompt();
    }

    public RagPromptAssemblyResult assemble(ConversationExecutionPlan plan, RagRetrievalContext context) {
        PromptAssemblyState assemblyState = new PromptAssemblyState(
            Math.max(0, properties.getTotalEvidenceMaxChars()),
            Math.max(0, properties.getPerSubQuestionEvidenceMaxChars())
        );
        String evidenceBlocks = buildEvidenceBlocks(context, assemblyState);
        String userPrompt = promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_USER, Map.of(
            "currentDate", StrUtil.blankToDefault(plan.getCurrentDateText(), ""),
            "originalQuestion", StrUtil.blankToDefault(plan.getOriginalQuestion(), ""),
            "hasRetrievalQuestion", hasRetrievalQuestion(plan),
            "retrievalQuestion", retrievalQuestion(plan),
            "hasHistoryContext", hasHistoryContext(plan),
            "historyContext", buildHistoryContext(plan),
            "hasSubQuestions", hasSubQuestions(plan),
            "subQuestions", buildSubQuestions(plan),
            "evidenceBlocks", evidenceBlocks
        ));
        List<AnswerShapeRequirement> answerShapeRequirements = resolveAnswerShapeRequirements(
            plan,
            assemblyState.renderedSourceEvidence
        );
        String answerShapeInstruction = buildAnswerShapeInstruction(plan, context, answerShapeRequirements);
        if (StrUtil.isNotBlank(answerShapeInstruction)) {
            userPrompt = "【答案组织 Answer Shape】\n" + answerShapeInstruction + "\n\n" + userPrompt;
        }
        return new RagPromptAssemblyResult(
            buildSystemPrompt(),
            userPrompt,
            assemblyState.promptBudget.totalBudget,
            assemblyState.promptBudget.perSubQuestionBudget,
            assemblyState.nextInputOrdinal,
            assemblyState.referenceManifest,
            assemblyState.renderedSourceEvidence,
            answerShapeRequirements
        );
    }

    private boolean hasRetrievalQuestion(ConversationExecutionPlan plan) {
        String retrievalQuestion = retrievalQuestion(plan);
        return StrUtil.isNotBlank(retrievalQuestion) && !retrievalQuestion.equals(plan.getOriginalQuestion());
    }

    private boolean hasHistoryContext(ConversationExecutionPlan plan) {
        AnswerHistoryContext answerHistoryContext = plan.getAnswerHistoryContext();
        return answerHistoryContext != null && !answerHistoryContext.isEmpty();
    }

    private String buildHistoryContext(ConversationExecutionPlan plan) {
        return hasHistoryContext(plan) ? plan.getAnswerHistoryContext().getRenderedText().trim() : "";
    }

    private boolean hasSubQuestions(ConversationExecutionPlan plan) {
        return retrievalQuestions(plan).size() > 1;
    }

    private String buildSubQuestions(ConversationExecutionPlan plan) {
        if (!hasSubQuestions(plan)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        List<String> retrievalQuestions = retrievalQuestions(plan);
        for (int index = 0; index < retrievalQuestions.size(); index++) {
            builder.append(index + 1).append(". ").append(retrievalQuestions.get(index)).append("\n");
        }
        return builder.toString().trim();
    }

    private String retrievalQuestion(ConversationExecutionPlan plan) {
        RetrievalPlan retrievalPlan = plan == null ? null : plan.getRetrievalPlan();
        return retrievalPlan == null ? "" : retrievalPlan.normalizedQuery();
    }

    private List<String> retrievalQuestions(ConversationExecutionPlan plan) {
        RetrievalPlan retrievalPlan = plan == null ? null : plan.getRetrievalPlan();
        return retrievalPlan == null ? List.of() : retrievalPlan.executionQueryTexts();
    }

    private String buildEvidenceBlocks(RagRetrievalContext context, PromptAssemblyState assemblyState) {
        StringBuilder builder = new StringBuilder();
        if (context == null || context.getSubQuestionEvidenceList() == null) {
            return "";
        }
        resetGenerationVisibility(context);
        for (SubQuestionEvidence evidence : context.getSubQuestionEvidenceList()) {
            if (evidence == null) {
                continue;
            }
            StringBuilder referenceBuilder = new StringBuilder();
            appendEvidence(
                referenceBuilder,
                evidence.getSubQuestionIndex(),
                evidence.getSubQuestion(),
                evidence.getReferences(),
                evidence.getContextDocuments(),
                assemblyState
            );
            builder.append(promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_SUB_QUESTION_EVIDENCE, Map.of(
                "subQuestionIndex", evidence.getSubQuestionIndex(),
                "subQuestion", StrUtil.blankToDefault(evidence.getSubQuestion(), ""),
                "references", referenceBuilder.toString().trim()
            ))).append("\n\n");
        }
        return builder.toString().trim();
    }

    private void appendEvidence(StringBuilder builder,
                                int subQuestionIndex,
                                String subQuestion,
                                List<SearchReference> sourceReferences,
                                List<RetrievalDocument> contextDocuments,
                                PromptAssemblyState assemblyState) {
        boolean sourceEmpty = sourceReferences == null || sourceReferences.isEmpty();
        boolean contextEmpty = contextDocuments == null || contextDocuments.isEmpty();
        if (sourceEmpty && contextEmpty) {
            builder.append(promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_NO_EVIDENCE, Map.of())).append('\n');
            return;
        }
        assemblyState.promptBudget.resetSubQuestionBudget();
        List<InputReference> sourceEvidence = new ArrayList<>();
        List<InputReference> contextOnlyEvidence = new ArrayList<>();
        if (sourceReferences != null) {
            for (SearchReference reference : sourceReferences) {
                int inputOrdinal = assemblyState.nextInputOrdinal++;
                String identity = reference == null ? "" : resolveIdentity(reference, false);
                InputReference inputReference = new InputReference(
                    inputOrdinal,
                    subQuestionIndex,
                    identity,
                    reference
                );
                if (isNotApplicable(reference)) {
                    assemblyState.recordDecision(
                        inputReference,
                        "",
                        PromptReferenceDisposition.OMITTED_NOT_APPLICABLE,
                        0
                    );
                }
                else if (reference == null
                    || identity.isBlank()
                    || isContextOnlyReference(reference)
                    || !isGenerationVisibleSource(reference)) {
                    assemblyState.recordDecision(
                        inputReference,
                        "",
                        PromptReferenceDisposition.OMITTED_INVALID_IDENTITY,
                        0
                    );
                }
                else {
                    sourceEvidence.add(inputReference);
                }
            }
        }
        if (contextDocuments != null) {
            for (RetrievalDocument document : contextDocuments) {
                int inputOrdinal = assemblyState.nextInputOrdinal++;
                SearchReference reference = toContextReference(document, subQuestionIndex, subQuestion);
                String identity = reference == null ? "" : resolveIdentity(reference, true);
                InputReference inputReference = new InputReference(
                    inputOrdinal,
                    subQuestionIndex,
                    identity,
                    reference
                );
                if (reference == null || identity.isBlank() || !isContextOnlyReference(reference)) {
                    assemblyState.recordDecision(
                        inputReference,
                        "",
                        PromptReferenceDisposition.OMITTED_INVALID_IDENTITY,
                        0
                    );
                }
                else {
                    contextOnlyEvidence.add(inputReference);
                }
            }
        }

        boolean budgetOmitted = false;
        if (!sourceEvidence.isEmpty()) {
            StringBuilder sourceBuilder = new StringBuilder();
            budgetOmitted = appendSourceReferences(sourceBuilder, sourceEvidence, assemblyState);
            if (sourceBuilder.length() > 0) {
                builder.append("【可引用证据 Source Evidence】\n");
                builder.append(sourceBuilder);
            }
        }
        if (!contextOnlyEvidence.isEmpty()) {
            StringBuilder contextBuilder = new StringBuilder();
            if (appendContextReferences(contextBuilder, contextOnlyEvidence, assemblyState)) {
                budgetOmitted = true;
            }
            if (contextBuilder.length() > 0) {
                builder.append("【背景上下文 Context Only（无稳定 identity 的导航装饰，不可引用）】\n");
                builder.append(contextBuilder);
            }
        }
        if (budgetOmitted) {
            builder.append(promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_OMITTED_EVIDENCE, Map.of())).append('\n');
        }
    }

    private boolean appendSourceReferences(StringBuilder builder,
                                           List<InputReference> references,
                                           PromptAssemblyState assemblyState) {
        boolean budgetOmitted = false;
        for (InputReference inputReference : references) {
            SearchReference reference = inputReference.reference();
            if (assemblyState.renderedSourceReferenceIds.containsKey(inputReference.identity())) {
                String canonicalReferenceId = assemblyState.renderedSourceReferenceIds.get(inputReference.identity());
                String reuseLine = promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_REUSE_REFERENCE, Map.of(
                    "referenceId", canonicalReferenceId
                )) + "\n";
                if (assemblyState.promptBudget.tryConsume(reuseLine.length())) {
                    builder.append(reuseLine);
                    assemblyState.recordDecision(
                        inputReference,
                        canonicalReferenceId,
                        PromptReferenceDisposition.PROMPT_REUSED_SOURCE,
                        reuseLine.length()
                    );
                } else {
                    assemblyState.recordDecision(
                        inputReference,
                        "",
                        PromptReferenceDisposition.OMITTED_BUDGET,
                        0
                    );
                    budgetOmitted = true;
                }
                continue;
            }

            String block = isWebReference(reference)
                ? buildWebReferenceBlock(reference)
                : buildDocumentReferenceBlock(reference);
            if (assemblyState.promptBudget.tryConsume(block.length())) {
                builder.append(block);
                String renderedReferenceId = StrUtil.blankToDefault(reference.getReferenceId(), "");
                assemblyState.renderedSourceReferenceIds.put(inputReference.identity(), renderedReferenceId);
                assemblyState.renderedSourceEvidence.add(
                    new PromptRenderedSourceEvidence(inputReference.identity(), reference, block)
                );
                reference.setGenerationVisible(true);
                assemblyState.recordDecision(
                    inputReference,
                    renderedReferenceId,
                    PromptReferenceDisposition.PROMPT_RENDERED_SOURCE,
                    block.length()
                );
            } else {
                assemblyState.recordDecision(
                    inputReference,
                    "",
                    PromptReferenceDisposition.OMITTED_BUDGET,
                    0
                );
                budgetOmitted = true;
            }
        }
        return budgetOmitted;
    }

    private boolean appendContextReferences(StringBuilder builder,
                                            List<InputReference> references,
                                            PromptAssemblyState assemblyState) {
        boolean budgetOmitted = false;
        for (InputReference inputReference : references) {
            SearchReference reference = inputReference.reference();
            if (assemblyState.renderedContextIdentities.contains(inputReference.identity())) {
                assemblyState.recordDecision(
                    inputReference,
                    "",
                    PromptReferenceDisposition.OMITTED_DUPLICATE,
                    0
                );
                continue;
            }
            String block = buildContextBlock(reference);
            if (assemblyState.promptBudget.tryConsume(block.length())) {
                builder.append(block);
                assemblyState.renderedContextIdentities.add(inputReference.identity());
                assemblyState.recordDecision(
                    inputReference,
                    "",
                    PromptReferenceDisposition.PROMPT_RENDERED_CONTEXT,
                    block.length()
                );
            } else {
                assemblyState.recordDecision(
                    inputReference,
                    "",
                    PromptReferenceDisposition.OMITTED_BUDGET,
                    0
                );
                budgetOmitted = true;
            }
        }
        return budgetOmitted;
    }

    private String resolveIdentity(SearchReference reference, boolean contextOnly) {
        if (contextOnly) {
            EvidenceIdentity identity = EvidenceIdentityResolver.contextIdentity(reference);
            return identity == null || !identity.present() ? "" : identity.value();
        }
        if (isWebReference(reference)) {
            return StrUtil.isBlank(reference.getUrl()) ? "" : "WEB:" + reference.getUrl().trim();
        }
        EvidenceIdentity identity = EvidenceIdentityResolver.citationIdentity(reference);
        return identity == null || !identity.present() ? "" : identity.value();
    }

    private SearchReference toContextReference(RetrievalDocument document, int subQuestionIndex, String subQuestion) {
        if (document == null) {
            return null;
        }
        EvidenceCandidateNormalizer.enrichIdentity(document);
        SearchReference reference = SearchReferenceMapper.fromDocument(document, subQuestionIndex, subQuestion, 0);
        reference.setReferenceId("");
        return reference;
    }

    /**
     * Context Only 只留给无稳定 identity 的装饰壳。SUMMARY/PARENT/TOOL/WEB 只要有 identity 就可引用。
     */
    private boolean isContextOnlyReference(SearchReference reference) {
        if (isWebReference(reference)) {
            return false;
        }
        return reference == null
            || EvidenceIdentityResolver.isContextOnly(reference);
    }

    private boolean isGenerationVisibleSource(SearchReference reference) {
        if (reference == null || StrUtil.isBlank(reference.getSnippet())) {
            return false;
        }
        if (isWebReference(reference)) {
            return StrUtil.isNotBlank(reference.getUrl());
        }
        return reference.isSourceEvidenceResolved();
    }

    private boolean isWebReference(SearchReference reference) {
        return reference != null
            && "WEB".equalsIgnoreCase(StrUtil.blankToDefault(reference.getSourceType(), "").trim());
    }

    private void resetGenerationVisibility(RagRetrievalContext context) {
        for (SubQuestionEvidence evidence : context.getSubQuestionEvidenceList()) {
            if (evidence == null || evidence.getReferences() == null) {
                continue;
            }
            evidence.getReferences().stream()
                .filter(reference -> reference != null)
                .forEach(reference -> reference.setGenerationVisible(false));
        }
    }

    private String buildWebReferenceBlock(SearchReference reference) {
        return promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_WEB_REFERENCE, Map.of(
            "referenceId", StrUtil.blankToDefault(reference.getReferenceId(), ""),
            "title", StrUtil.blankToDefault(reference.getTitle(), "网页来源"),
            "url", StrUtil.blankToDefault(reference.getUrl(), "未知"),
            "snippet", trimSnippet(reference.getSnippet(), 900)
        )) + "\n\n";
    }

    private String buildDocumentReferenceBlock(SearchReference reference) {
        String snippet = trimSnippet(reference.getSnippet(), 1100);
        String kind = resolveEvidenceKindLabel(reference);
        if (isNotApplicable(reference)) {
            snippet = "【证据适用性】这条证据不适用于当前目标对象，只能作为相似但不适用的线索。原因："
                + StrUtil.blankToDefault(reference.getEvidenceApplicabilityReason(), "-")
                + "\n"
                + snippet;
        }
        if ("SUMMARY".equals(kind) && StrUtil.isBlank(reference.getQuoteText())) {
            snippet = "【综述】没有原文 span，按综述引用，不要写成原文摘录。\n" + snippet;
        }
        return promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_DOCUMENT_REFERENCE, Map.of(
            "referenceId", StrUtil.blankToDefault(reference.getReferenceId(), ""),
            "evidenceKind", kind,
            "documentName", StrUtil.blankToDefault(
                StrUtil.blankToDefault(reference.getDocumentName(), reference.getTitle()),
                "文档来源"
            ),
            "sectionPath", StrUtil.blankToDefault(reference.getSectionPath(), "未识别"),
            "snippet", snippet
        )) + "\n\n";
    }

    private String buildContextBlock(SearchReference reference) {
        String snippet = trimSnippet(reference.getSnippet(), 1100);
        return promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_CONTEXT, Map.of(
            "documentName", StrUtil.blankToDefault(
                StrUtil.blankToDefault(reference.getDocumentName(), reference.getTitle()),
                "文档背景"
            ),
            "sectionPath", StrUtil.blankToDefault(reference.getSectionPath(), "未识别"),
            "snippet", snippet
        )) + "\n\n";
    }

    private String resolveEvidenceKindLabel(SearchReference reference) {
        String kind = StrUtil.blankToDefault(reference.getEvidenceKind(), "").trim().toUpperCase();
        if (!kind.isBlank()) {
            return kind;
        }
        EvidenceIdentity identity = EvidenceIdentityResolver.citationIdentity(reference);
        EvidenceKind resolved = identity == null ? null : EvidenceKind.from(identity.type());
        return resolved == null ? "CHUNK" : resolved.name();
    }

    private List<AnswerShapeRequirement> resolveAnswerShapeRequirements(
        ConversationExecutionPlan plan,
        List<PromptRenderedSourceEvidence> renderedSourceEvidence) {
        QueryUnderstandingResult understanding = plan == null ? null : plan.getQueryUnderstanding();
        AnswerShapePlan answerShapePlan = understanding == null || understanding.getAnswerShapePlan() == null
            ? AnswerShapePlan.empty()
            : understanding.getAnswerShapePlan();
        boolean hasRenderedTableSource = renderedSourceEvidence != null && renderedSourceEvidence.stream()
            .map(PromptRenderedSourceEvidence::reference)
            .anyMatch(this::isRenderedTableSource);
        return answerShapePlan.requirements().stream()
            .filter(requirement -> requirement != AnswerShapeRequirement.CATEGORY_TABLE_ROW_COVERAGE
                || hasRenderedTableSource)
            .toList();
    }

    private boolean isRenderedTableSource(SearchReference reference) {
        if (reference == null) {
            return false;
        }
        String sourceType = StrUtil.blankToDefault(reference.getSourceType(), "").trim();
        String evidenceType = StrUtil.blankToDefault(reference.getCitationEvidenceType(), "").trim();
        return reference.getTableId() != null
            || "DOCUMENT_TABLE".equalsIgnoreCase(sourceType)
            || "TABLE_CELL_OR_ROW".equalsIgnoreCase(evidenceType);
    }

    private String buildAnswerShapeInstruction(ConversationExecutionPlan plan,
                                               RagRetrievalContext context,
                                               List<AnswerShapeRequirement> requirements) {
        QueryUnderstandingResult understanding = plan == null ? null : plan.getQueryUnderstanding();
        List<AnswerShapeRequirement> controlledRequirements = requirements == null ? List.of() : requirements;
        boolean hasNotApplicableEvidence = hasNotApplicableEvidence(context);
        if (controlledRequirements.isEmpty() && !hasNotApplicableEvidence) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("以下要求只组织已经进入本轮 Prompt 的可引用 Source，不得重新召回、过滤、重排或补造证据：");
        if (controlledRequirements.contains(AnswerShapeRequirement.COMPARE)) {
            builder.append("\n- 比较：逐项比较问题要求的每个对象，并在相同维度下给出 Source 支持的异同，不得静默漏掉被比较对象。");
        }
        if (controlledRequirements.contains(AnswerShapeRequirement.LIST)) {
            builder.append("\n- 列表：完整列出问题要求且由 Source 支持的相关项目；不得把无关项目加入答案。");
        }
        if (controlledRequirements.contains(AnswerShapeRequirement.STEPS)) {
            builder.append("\n- 步骤：按 Source 中的原始顺序完整列出问题要求的步骤，不得颠倒、合并或补写动作。");
        }
        if (controlledRequirements.contains(AnswerShapeRequirement.CATEGORY_TABLE_ROW_COVERAGE)) {
            builder.append("\n- 互斥分类与 table 行覆盖：当问题要求多个相关互斥分类，且这些分类已出现在本轮可引用 table Source 中时，必须覆盖所有问题相关分类及其值，包括位于其间的相关分类；不得无条件枚举整张表，不得加入与问题无关的行，也不得补写 Source 中不存在的分类或值。");
        }
        if (controlledRequirements.contains(AnswerShapeRequirement.NEGATIVE_BOUNDARY) || hasNotApplicableEvidence) {
            builder.append("\n- 负边界：如果证据只支持相似对象或被用户排除的对象，而没有支持当前目标对象，必须回答文档没有明确给出，不得把相似对象的步骤、原因或结论套用到当前目标对象。");
            String targetText = understanding == null || understanding.getTargetEntities() == null || understanding.getTargetEntities().isEmpty()
                ? ""
                : String.join("、", understanding.getTargetEntities());
            String excludedText = understanding == null || understanding.getExcludedEntities() == null || understanding.getExcludedEntities().isEmpty()
                ? ""
                : String.join("、", understanding.getExcludedEntities());
            if (StrUtil.isNotBlank(targetText)) {
                builder.append("\n  当前目标对象：").append(targetText);
            }
            if (StrUtil.isNotBlank(excludedText)) {
                builder.append("\n  用户排除对象：").append(excludedText);
            }
        }
        builder.append("\nAnswer Shape 不解析、补写、替换或重排引用编号；引用仍只按现有 citation rules 生成。");
        return builder.toString();
    }

    private boolean hasNotApplicableEvidence(RagRetrievalContext context) {
        if (context == null || context.getSubQuestionEvidenceList() == null) {
            return false;
        }
        return context.getSubQuestionEvidenceList().stream()
            .filter(evidence -> evidence != null && evidence.getReferences() != null)
            .flatMap(evidence -> evidence.getReferences().stream())
            .anyMatch(this::isNotApplicable);
    }

    private boolean isNotApplicable(SearchReference reference) {
        return reference != null
            && EvidenceApplicabilityResult.NOT_APPLICABLE.equalsIgnoreCase(
                StrUtil.blankToDefault(reference.getEvidenceApplicabilityStatus(), "").trim()
            );
    }

    private String trimSnippet(String snippet, int maxChars) {
        if (StrUtil.isBlank(snippet)) {
            return "";
        }

        return snippet.length() <= maxChars ? snippet : snippet.substring(0, maxChars) + "...";
    }

    private static final class PromptBudget {

        private final int totalBudget;
        private final int perSubQuestionBudget;
        private int remainingTotal;
        private int remainingSubQuestion;

        private PromptBudget(int totalBudget, int perSubQuestionBudget) {
            this.totalBudget = totalBudget;
            this.perSubQuestionBudget = perSubQuestionBudget;
            this.remainingTotal = totalBudget;
            this.remainingSubQuestion = perSubQuestionBudget;
        }

        private void resetSubQuestionBudget() {
            this.remainingSubQuestion = perSubQuestionBudget;
        }

        private boolean tryConsume(int size) {
            if (totalBudget <= 0 || perSubQuestionBudget <= 0) {
                return false;
            }
            if (size > remainingTotal || size > remainingSubQuestion) {
                return false;
            }
            remainingTotal -= size;
            remainingSubQuestion -= size;
            return true;
        }
    }

    private static final class PromptAssemblyState {

        private final PromptBudget promptBudget;
        private final Map<String, String> renderedSourceReferenceIds = new LinkedHashMap<>();
        private final Set<String> renderedContextIdentities = new LinkedHashSet<>();
        private final List<PromptReferenceDecision> referenceManifest = new ArrayList<>();
        private final List<PromptRenderedSourceEvidence> renderedSourceEvidence = new ArrayList<>();
        private int nextInputOrdinal;

        private PromptAssemblyState(int totalBudget, int perSubQuestionBudget) {
            this.promptBudget = new PromptBudget(totalBudget, perSubQuestionBudget);
        }

        private void recordDecision(InputReference inputReference,
                                    String renderedReferenceId,
                                    PromptReferenceDisposition disposition,
                                    int consumedChars) {
            SearchReference reference = inputReference.reference();
            referenceManifest.add(new PromptReferenceDecision(
                inputReference.inputOrdinal(),
                inputReference.subQuestionIndex(),
                reference == null ? "" : reference.getReferenceId(),
                renderedReferenceId,
                reference == null ? "" : reference.getSourceType(),
                inputReference.identity(),
                disposition,
                consumedChars
            ));
        }
    }

    private record InputReference(
        int inputOrdinal,
        int subQuestionIndex,
        String identity,
        SearchReference reference
    ) {
    }
}
