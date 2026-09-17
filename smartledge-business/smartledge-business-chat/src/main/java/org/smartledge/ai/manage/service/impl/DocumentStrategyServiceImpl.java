package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.config.DocumentManageProperties;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentBlock;
import org.smartledge.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.smartledge.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.smartledge.ai.manage.data.SuperAgentDocumentStructureNode;
import org.smartledge.ai.manage.model.KnowledgeBaseIndexingOptions;
import org.smartledge.ai.manage.service.DocumentStrategyService;
import org.smartledge.ai.manage.service.DocumentStructureNodeService;
import org.smartledge.ai.manage.support.ChunkCandidate;
import org.smartledge.ai.manage.support.ChunkingContract;
import org.smartledge.ai.manage.support.ChunkingProfileException;
import org.smartledge.ai.manage.support.ChunkSourceProvenance;
import org.smartledge.ai.manage.support.DocumentChunkingProfiles;
import org.smartledge.ai.manage.support.SourceTextSplitter;
import org.smartledge.ai.manage.support.DocumentAnalysisResult;
import org.smartledge.ai.manage.support.DocumentStrategyPlanDraft;
import org.smartledge.ai.manage.support.DocumentStrategyStepDraft;
import org.smartledge.ai.manage.support.KnowledgeBaseIndexingConfigResolver;
import org.smartledge.ai.manage.support.ParentBlockCandidate;
import org.smartledge.ai.prompt.PromptTemplateNames;
import org.smartledge.ai.prompt.PromptTemplateService;
import org.smartledge.enums.DocumentChunkSourceTypeEnum;
import org.smartledge.enums.DocumentContentQualityLevelEnum;
import org.smartledge.enums.DocumentFileTypeEnum;
import org.smartledge.enums.DocumentStrategyExecuteStatusEnum;
import org.smartledge.enums.DocumentStrategyPipelineTypeEnum;
import org.smartledge.enums.DocumentStrategyRoleEnum;
import org.smartledge.enums.DocumentStrategySourceTypeEnum;
import org.smartledge.enums.DocumentStrategyTypeEnum;
import org.smartledge.enums.DocumentStructureLevelEnum;
import org.smartledge.ai.rag.runtime.port.ObservedChatModelPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @description: 服务实现层
 * @author: Song
 **/

@Slf4j
@AllArgsConstructor
@Service
public class DocumentStrategyServiceImpl implements DocumentStrategyService {

    private static final Pattern ENGLISH_WORD_PATTERN = Pattern.compile("[A-Za-z0-9]{2,}");
    private static final Pattern CHINESE_KEYWORD_PATTERN = Pattern.compile("[\\p{IsHan}]{2,12}");

    private final DocumentManageProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ObservedChatModelPort> chatModelProvider;
    private final DocumentStructureNodeService structureNodeService;
    private final PromptTemplateService promptTemplateService;
    private final KnowledgeBaseIndexingConfigResolver indexingConfigResolver;
    private final DocumentChunkingProfiles chunkingProfiles;

    @Override
    public DocumentStrategyPlanDraft recommendStrategy(SuperAgentDocument document, DocumentAnalysisResult analysisResult) {

        List<String> reasonList = new ArrayList<>();
        DocumentFileTypeEnum fileType = DocumentFileTypeEnum.getRc(document.getFileType());
        KnowledgeBaseIndexingOptions indexingOptions = indexingConfigResolver.resolve(document);

        var chunk = indexingOptions.getChunk();
        ChunkingContract contract = chunkingProfiles.recommend(document.getId(), document.getLastParseTaskId(),
            analysisResult.getMarkdownSyntax(), new ChunkingContract.Budget(chunk.getParentBlockMaxChars(),
                chunk.getChildRecursiveMaxChars(), chunk.getChildRecursiveOverlapChars(), properties.getChunk().getQaMaxInputBytes()));
        if (contract.qa()) {
            var parents = buildDraftSteps(DocumentStrategyPipelineTypeEnum.PARENT,
                List.of(DocumentStrategyTypeEnum.STRUCTURE.getCode()), Map.of(1, contract.reason()));
            var children = buildDraftSteps(DocumentStrategyPipelineTypeEnum.CHILD,
                List.of(DocumentStrategyTypeEnum.RECURSIVE.getCode()), Map.of(2, contract.reason()));
            return new DocumentStrategyPlanDraft(buildCombinedStrategySnapshot(parents, children), contract.reason(),
                parents, children, contract.json(objectMapper));
        }

        boolean structureRecommended = shouldUseStructure(fileType, analysisResult);
        boolean recursiveRecommended = shouldUseRecursive(analysisResult, indexingOptions);
        boolean semanticRecommended = shouldUseSemantic(analysisResult, indexingOptions);
        boolean llmRecommended = shouldUseLlm(analysisResult, indexingOptions);

        List<Integer> parentStrategyTypes = new ArrayList<>();
        Map<Integer, String> parentReasonMap = new LinkedHashMap<>();
        if (structureRecommended) {
            parentStrategyTypes.add(DocumentStrategyTypeEnum.STRUCTURE.getCode());
            parentReasonMap.put(DocumentStrategyTypeEnum.STRUCTURE.getCode(),
                "检测到文档具有较明显的标题或章节结构，父块优先保留天然章节边界。");
            reasonList.add("父块流水线优先采用基于文档结构切块，保留回答阶段需要的大语义单元。");
        }
        else {
            parentStrategyTypes.add(DocumentStrategyTypeEnum.RECURSIVE.getCode());
            parentReasonMap.put(DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                "未识别出稳定结构时，父块先使用较大粒度的递归分块作为稳定回答单元。");
            reasonList.add("父块流水线未命中明显结构信号，默认使用较大粒度递归分块作为回答单元。");
        }

        List<Integer> childStrategyTypes = new ArrayList<>();
        Map<Integer, String> childReasonMap = new LinkedHashMap<>();
        if (llmRecommended) {
            childStrategyTypes.add(DocumentStrategyTypeEnum.LLM.getCode());
            childReasonMap.put(DocumentStrategyTypeEnum.LLM.getCode(),
                "文档质量偏低或结构识别不稳定，子块先使用大模型智能切块增强复杂场景。");
            reasonList.add("子块流水线追加大模型智能切块，处理低质量或结构不稳定文本。");
        }
        else if (semanticRecommended) {
            childStrategyTypes.add(DocumentStrategyTypeEnum.SEMANTIC.getCode());
            childReasonMap.put(DocumentStrategyTypeEnum.SEMANTIC.getCode(),
                "文本主题边界相对明确，子块先使用语义分块优化召回边界。");
            reasonList.add("子块流水线优先采用语义分块，优化召回边界和主题完整性。");
        }

        if (recursiveRecommended || llmRecommended || childStrategyTypes.isEmpty()) {
            childStrategyTypes.add(DocumentStrategyTypeEnum.RECURSIVE.getCode());
            childReasonMap.put(DocumentStrategyTypeEnum.RECURSIVE.getCode(),
                "文档整体较长、存在超长段落，或需要在增强切块后追加长度控制步骤。");
            reasonList.add("子块流水线追加递归分块，用于控制召回单元长度。");
        }

        List<DocumentStrategyStepDraft> parentSteps = buildDraftSteps(
            DocumentStrategyPipelineTypeEnum.PARENT, parentStrategyTypes, parentReasonMap
        );
        List<DocumentStrategyStepDraft> childSteps = buildDraftSteps(
            DocumentStrategyPipelineTypeEnum.CHILD, childStrategyTypes, childReasonMap
        );

        String strategySnapshot = buildCombinedStrategySnapshot(parentSteps, childSteps);
        return new DocumentStrategyPlanDraft(strategySnapshot, String.join("；", reasonList), parentSteps, childSteps,
            contract.json(objectMapper));
    }

    @Override
    public List<SuperAgentDocumentStrategyStep> normalizeSteps(SuperAgentDocumentStrategyPlan basePlan,
                                                               List<SuperAgentDocumentStrategyStep> baseSteps,
                                                               List<Integer> requestParentStrategyTypes,
                                                               List<Integer> requestChildStrategyTypes,
                                                               Long documentId) {

        List<Integer> normalizedParentTypes = normalizePipelineTypes(requestParentStrategyTypes);
        List<Integer> normalizedChildTypes = normalizePipelineTypes(requestChildStrategyTypes);

        Map<String, Map<Integer, SuperAgentDocumentStrategyStep>> baseStepMap = new LinkedHashMap<>();
        for (SuperAgentDocumentStrategyStep baseStep : baseSteps) {
            String pipelineType = baseStep.getPipelineType();
            if (StrUtil.isBlank(pipelineType)) {
                pipelineType = DocumentStrategyPipelineTypeEnum.CHILD.getCode();
            }
            baseStepMap.computeIfAbsent(pipelineType, ignored -> new LinkedHashMap<>())
                .put(baseStep.getStrategyType(), baseStep);
        }

        List<SuperAgentDocumentStrategyStep> normalizedStepList = new ArrayList<>();
        normalizedStepList.addAll(buildNormalizedSteps(
            DocumentStrategyPipelineTypeEnum.PARENT,
            normalizedParentTypes,
            baseStepMap.getOrDefault(DocumentStrategyPipelineTypeEnum.PARENT.getCode(), Map.of()),
            documentId
        ));
        normalizedStepList.addAll(buildNormalizedSteps(
            DocumentStrategyPipelineTypeEnum.CHILD,
            normalizedChildTypes,
            baseStepMap.getOrDefault(DocumentStrategyPipelineTypeEnum.CHILD.getCode(), Map.of()),
            documentId
        ));
        return normalizedStepList;
    }

    @Override
    public List<ParentBlockCandidate> buildParentBlocks(SuperAgentDocument document,
                                                        SuperAgentDocumentStrategyPlan plan,
                                                        List<SuperAgentDocumentStrategyStep> steps,
                                                        List<SuperAgentDocumentBlock> documentBlocks) {
        ChunkingContract contract = ChunkingContract.read(plan.getChunkingContractJson(), objectMapper);
        if (contract != null && contract.qa()) {
            if (!contract.confirmed()) { throw new ChunkingProfileException("PROFILE_NOT_CONFIRMED", null); }
            return buildQaParents(document, steps, documentBlocks, contract);
        }
        List<SuperAgentDocumentStrategyStep> parentSteps = sortPipelineSteps(steps, DocumentStrategyPipelineTypeEnum.PARENT);
        List<SuperAgentDocumentStrategyStep> childSteps = sortPipelineSteps(steps, DocumentStrategyPipelineTypeEnum.CHILD);
        if (parentSteps.isEmpty()) {
            throw new IllegalStateException("当前方案缺少父块流水线，无法生成 Parent-Child 结构。");
        }
        if (childSteps.isEmpty()) {
            throw new IllegalStateException("当前方案缺少子块流水线，无法生成 Parent-Child 结构。");
        }

        List<SuperAgentDocumentBlock> orderedBlocks = cleanupDocumentBlocks(documentBlocks);
        if (orderedBlocks.isEmpty()) {
            throw new IllegalStateException("当前文档没有可用于切块的结构化 blocks。");
        }
        Map<Long, SuperAgentDocumentBlock> blockMap = toBlockMap(orderedBlocks);
        List<SuperAgentDocumentStructureNode> structureNodes = structureNodeService.listDocumentNodes(
            document == null ? null : document.getId(),
            document == null ? null : document.getLastParseTaskId()
        );
        KnowledgeBaseIndexingOptions indexingOptions = indexingConfigResolver.resolve(document);
        List<ChunkCandidate> parentSeedList = buildParentSeedList(orderedBlocks, parentSteps, structureNodes, indexingOptions);
        List<ParentBlockCandidate> parentBlockList = new ArrayList<>();
        for (ChunkCandidate parentSeed : cleanupChunkList(parentSeedList)) {
            if (parentSeed == null || StrUtil.isBlank(parentSeed.getText())) {
                continue;
            }
            List<ChunkCandidate> childSeedList = buildChildSeedList(parentSeed, childSteps, blockMap, indexingOptions);
            List<ChunkCandidate> finalChildren = cleanupChunkList(childSeedList);
            if (finalChildren.isEmpty()) {
                finalChildren = List.of(cloneChunkCandidate(parentSeed, parentSeed.getText().trim()));
            }

            parentBlockList.add(new ParentBlockCandidate(
                parentSeed.getSectionPath(),
                parentSeed.getStructureNodeId(),
                parentSeed.getStructureNodeType(),
                parentSeed.getCanonicalPath(),
                parentSeed.getItemIndex(),
                parentSeed.getText().trim(),
                parentSeed.getSourceType(),
                finalChildren,
                parentSeed.getPageRange(),
                parentSeed.getSourceBlockIds()
            ));
        }
        return cleanupParentBlockList(parentBlockList);
    }

    private List<ParentBlockCandidate> buildQaParents(SuperAgentDocument document,
                                                     List<SuperAgentDocumentStrategyStep> steps,
                                                     List<SuperAgentDocumentBlock> blocks,
                                                     ChunkingContract contract) {
        contract.requireStrategies(steps);
        var facts = chunkingProfiles.load(document.getId(), contract, blocks);
        List<ParentBlockCandidate> parents = new ArrayList<>();
        for (var unit : facts.units()) {
            var questionBlock = facts.blocks().get(unit.questionNode().getNodeId());
            var question = new ChunkSourceProvenance.Source(questionBlock.getId(), unit.questionNode().getNodeId(),
                unit.questionNode().getSourceSpan().getStartByte(), unit.questionNode().getSourceSpan().getEndByte());
            String prefix = "Q: " + unit.question() + "\n\n";
            int maxChars = Math.min(contract.budget().childMaxChars(), contract.budget().parentMaxChars()) - prefix.length();
            // Four UTF-8 bytes per UTF-16 code unit is a conservative application byte bound.
            maxChars = Math.min(maxChars, (contract.budget().maxInputBytes() - prefix.getBytes(StandardCharsets.UTF_8).length) / 4);
            if (maxChars < 2) { throw new ChunkingProfileException("PROFILE_UNIT_TOO_LARGE", unit.id()); }
            List<ChunkCandidate> children = new ArrayList<>();
            for (var answer : unit.answers()) {
                var block = facts.blocks().get(answer.getNodeId());
                String raw = DocumentChunkingProfiles.sourceText(facts.syntax(), answer);
                boolean divisible = "PARAGRAPH".equals(answer.getNodeType());
                if (!divisible && (raw.length() > maxChars)) {
                    throw new ChunkingProfileException("PROFILE_UNIT_TOO_LARGE", unit.id());
                }
                var fragments = SourceTextSplitter.split(raw, divisible ? maxChars : Math.max(1, raw.length()),
                    divisible ? Math.min(contract.budget().overlapChars(), maxChars - 1) : 0);
                for (var fragment : fragments) {
                    int start = answer.getSourceSpan().getStartByte()
                        + raw.substring(0, fragment.start()).getBytes(StandardCharsets.UTF_8).length;
                    int end = start + fragment.text().getBytes(StandardCharsets.UTF_8).length;
                    var provenance = new ChunkSourceProvenance("chunk-source.v1", unit.id(), contract.sourceParseTaskId(),
                        contract.sourceSha256(), contract.ruleVersion(), question,
                        List.of(new ChunkSourceProvenance.Source(block.getId(), answer.getNodeId(), start, end)));
                    ChunkCandidate child = new ChunkCandidate(questionBlock.getSectionPath(), prefix + fragment.text(),
                        DocumentChunkSourceTypeEnum.ORIGINAL.getCode());
                    child.setCanonicalPath(block.getCanonicalPath());
                    child.setChunkType(block.getBlockType());
                    child.setTitle(unit.question());
                    child.setContentWithWeight(child.getText());
                    child.setSourceBlockIds("[" + questionBlock.getId() + "," + block.getId() + "]");
                    child.setSourceProvenance(provenance);
                    children.add(child);
                }
            }
            if (children.isEmpty()) { throw new ChunkingProfileException("QA_ANSWER_MISSING", unit.id()); }
            List<ChunkCandidate> group = new ArrayList<>();
            int size = 0;
            for (var child : children) {
                if (!group.isEmpty() && size + child.getText().length() + 2 > contract.budget().parentMaxChars()) {
                    parents.add(qaParent(group));
                    group = new ArrayList<>();
                    size = 0;
                }
                group.add(child);
                size += child.getText().length() + 2;
            }
            if (!group.isEmpty()) { parents.add(qaParent(group)); }
        }
        return List.copyOf(parents);
    }

    private ParentBlockCandidate qaParent(List<ChunkCandidate> children) {
        var first = children.get(0);
        var source = first.getSourceProvenance();
        var answers = children.stream().flatMap(child -> child.getSourceProvenance().answers().stream()).toList();
        var provenance = new ChunkSourceProvenance(source.schemaVersion(), source.pairId(), source.sourceParseTaskId(),
            source.sourceSha256(), source.ruleVersion(), source.question(), answers);
        var ids = new LinkedHashSet<Long>();
        ids.add(source.question().blockId());
        answers.forEach(answer -> ids.add(answer.blockId()));
        ParentBlockCandidate parent = new ParentBlockCandidate(first.getSectionPath(),
            children.stream().map(ChunkCandidate::getText).collect(Collectors.joining("\n\n")),
            first.getSourceType(), List.copyOf(children));
        parent.setSourceBlockIds(ids.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]")));
        parent.setSourceProvenance(provenance);
        parent.setCanonicalPath(first.getCanonicalPath());
        return parent;
    }

    private List<ChunkCandidate> buildParentSeedList(List<SuperAgentDocumentBlock> documentBlocks,
                                                     List<SuperAgentDocumentStrategyStep> parentSteps,
                                                     List<SuperAgentDocumentStructureNode> structureNodes,
                                                     KnowledgeBaseIndexingOptions indexingOptions) {
        if (containsStructureStep(parentSteps)) {
            List<ChunkCandidate> structureSeeds = buildBlockSectionParentSeeds(documentBlocks, structureNodes, indexingOptions);
            List<SuperAgentDocumentStrategyStep> remainingSteps = stripStructureSteps(parentSteps);
            if (remainingSteps.isEmpty()) {
                return structureSeeds;
            }
            return executePipeline(structureSeeds, remainingSteps, DocumentStrategyPipelineTypeEnum.PARENT, indexingOptions);
        }
        List<ChunkCandidate> parentSeeds = buildBlockWindowParentSeeds(documentBlocks,
            resolveRecursiveMaxChars(DocumentStrategyPipelineTypeEnum.PARENT, indexingOptions));
        List<SuperAgentDocumentStrategyStep> remainingSteps = stripRecursiveSteps(parentSteps);
        return remainingSteps.isEmpty()
            ? parentSeeds
            : executePipeline(parentSeeds, remainingSteps, DocumentStrategyPipelineTypeEnum.PARENT, indexingOptions);
    }

    private List<ChunkCandidate> buildChildSeedList(ChunkCandidate parentSeed,
                                                    List<SuperAgentDocumentStrategyStep> childSteps,
                                                    Map<Long, SuperAgentDocumentBlock> blockMap,
                                                    KnowledgeBaseIndexingOptions indexingOptions) {
        List<ChunkCandidate> blockSeeds = buildBlockChildSeeds(parentSeed, blockMap);
        if (blockSeeds.isEmpty()) {
            blockSeeds = List.of(cloneChunkCandidate(parentSeed, parentSeed.getText()));
        }
        List<SuperAgentDocumentStrategyStep> remainingSteps = stripStructureSteps(childSteps);
        return remainingSteps.isEmpty()
            ? blockSeeds
            : executePipeline(blockSeeds, remainingSteps, DocumentStrategyPipelineTypeEnum.CHILD, indexingOptions);
    }

    private boolean containsStructureStep(List<SuperAgentDocumentStrategyStep> steps) {
        return steps != null && steps.stream().anyMatch(step -> DocumentStrategyTypeEnum.STRUCTURE.getCode().equals(step.getStrategyType()));
    }

    private List<SuperAgentDocumentStrategyStep> stripStructureSteps(List<SuperAgentDocumentStrategyStep> steps) {
        return steps == null ? List.of() : steps.stream()
            .filter(step -> !DocumentStrategyTypeEnum.STRUCTURE.getCode().equals(step.getStrategyType()))
            .toList();
    }

    private List<SuperAgentDocumentStrategyStep> stripRecursiveSteps(List<SuperAgentDocumentStrategyStep> steps) {
        return steps == null ? List.of() : steps.stream()
            .filter(step -> !DocumentStrategyTypeEnum.RECURSIVE.getCode().equals(step.getStrategyType()))
            .toList();
    }

    private List<SuperAgentDocumentBlock> cleanupDocumentBlocks(List<SuperAgentDocumentBlock> documentBlocks) {
        if (documentBlocks == null || documentBlocks.isEmpty()) {
            return List.of();
        }
        return documentBlocks.stream()
            .filter(this::hasBlockContent)
            .sorted(Comparator
                .comparingInt((SuperAgentDocumentBlock block) -> block.getBlockNo() == null ? Integer.MAX_VALUE : block.getBlockNo())
                .thenComparingLong(block -> block.getId() == null ? Long.MAX_VALUE : block.getId()))
            .toList();
    }

    private Map<Long, SuperAgentDocumentBlock> toBlockMap(List<SuperAgentDocumentBlock> documentBlocks) {
        Map<Long, SuperAgentDocumentBlock> blockMap = new LinkedHashMap<>();
        for (SuperAgentDocumentBlock block : documentBlocks) {
            if (block != null && block.getId() != null) {
                blockMap.put(block.getId(), block);
            }
        }
        return blockMap;
    }

    private List<ChunkCandidate> buildBlockSectionParentSeeds(List<SuperAgentDocumentBlock> documentBlocks,
                                                              List<SuperAgentDocumentStructureNode> structureNodes,
                                                              KnowledgeBaseIndexingOptions indexingOptions) {
        List<ChunkCandidate> seeds = new ArrayList<>();
        List<SuperAgentDocumentBlock> currentGroup = new ArrayList<>();
        String currentSectionKey = "";
        for (SuperAgentDocumentBlock block : documentBlocks) {
            String sectionKey = sectionKey(block);
            boolean sectionChanged = !currentGroup.isEmpty() && !StrUtil.equals(currentSectionKey, sectionKey);
            boolean startsNewTitleSection = isTitleBlock(block) && sectionChanged;
            if (sectionChanged || startsNewTitleSection) {
                appendParentSeedsFromBlockGroup(seeds, currentGroup, structureNodes, indexingOptions);
                currentGroup = new ArrayList<>();
            }
            if (currentGroup.isEmpty()) {
                currentSectionKey = sectionKey;
            }
            currentGroup.add(block);
        }
        appendParentSeedsFromBlockGroup(seeds, currentGroup, structureNodes, indexingOptions);
        return seeds.isEmpty()
            ? buildBlockWindowParentSeeds(documentBlocks, resolveRecursiveMaxChars(DocumentStrategyPipelineTypeEnum.PARENT, indexingOptions), structureNodes)
            : seeds;
    }

    private void appendParentSeedsFromBlockGroup(List<ChunkCandidate> seeds,
                                                 List<SuperAgentDocumentBlock> blockGroup,
                                                 List<SuperAgentDocumentStructureNode> structureNodes,
                                                 KnowledgeBaseIndexingOptions indexingOptions) {
        if (blockGroup == null || blockGroup.isEmpty()) {
            return;
        }
        String text = joinBlockTexts(blockGroup);
        int parentMaxChars = resolveRecursiveMaxChars(DocumentStrategyPipelineTypeEnum.PARENT, indexingOptions);
        if (text.length() <= parentMaxChars) {
            seeds.add(toParentSeed(blockGroup, structureNodes));
            return;
        }
        seeds.addAll(buildBlockWindowParentSeeds(blockGroup, parentMaxChars, structureNodes));
    }

    private List<ChunkCandidate> buildBlockWindowParentSeeds(List<SuperAgentDocumentBlock> documentBlocks, int maxChars) {
        return buildBlockWindowParentSeeds(documentBlocks, maxChars, List.of());
    }

    private List<ChunkCandidate> buildBlockWindowParentSeeds(List<SuperAgentDocumentBlock> documentBlocks,
                                                             int maxChars,
                                                             List<SuperAgentDocumentStructureNode> structureNodes) {
        List<ChunkCandidate> seeds = new ArrayList<>();
        List<SuperAgentDocumentBlock> currentBlocks = new ArrayList<>();
        int currentChars = 0;
        for (SuperAgentDocumentBlock block : documentBlocks) {
            String blockText = renderBlockContent(block);
            if (StrUtil.isBlank(blockText)) {
                continue;
            }
            if (blockText.length() > maxChars) {
                if (!currentBlocks.isEmpty()) {
                    seeds.add(toParentSeed(currentBlocks, structureNodes));
                }
                currentBlocks = new ArrayList<>();
                currentChars = 0;
                for (String splitText : recursiveSplit(blockText, maxChars, 0)) {
                    seeds.add(toSplitBlockSeed(block, splitText, structureNodes));
                }
                continue;
            }
            if (!currentBlocks.isEmpty() && currentChars + blockText.length() + 2 > maxChars) {
                seeds.add(toParentSeed(currentBlocks, structureNodes));
                currentBlocks = new ArrayList<>();
                currentChars = 0;
            }
            currentBlocks.add(block);
            currentChars += blockText.length() + 2;
        }
        if (!currentBlocks.isEmpty()) {
            seeds.add(toParentSeed(currentBlocks, structureNodes));
        }
        return seeds;
    }

    private List<ChunkCandidate> buildBlockChildSeeds(ChunkCandidate parentSeed,
                                                      Map<Long, SuperAgentDocumentBlock> blockMap) {
        if (parentSeed == null || blockMap == null || blockMap.isEmpty()) {
            return List.of();
        }
        List<ChunkCandidate> seeds = new ArrayList<>();
        for (Long blockId : parseSourceBlockIds(parentSeed.getSourceBlockIds())) {
            SuperAgentDocumentBlock block = blockMap.get(blockId);
            if (block == null) {
                continue;
            }
            ChunkCandidate child = toBlockChunkCandidate(block);
            child.setSectionPath(StrUtil.blankToDefault(child.getSectionPath(), parentSeed.getSectionPath()));
            child.setStructureNodeId(parentSeed.getStructureNodeId());
            child.setStructureNodeType(parentSeed.getStructureNodeType());
            child.setCanonicalPath(StrUtil.blankToDefault(child.getCanonicalPath(), parentSeed.getCanonicalPath()));
            child.setItemIndex(parentSeed.getItemIndex());
            seeds.add(child);
        }
        return seeds;
    }

    private ChunkCandidate toParentSeed(List<SuperAgentDocumentBlock> blocks,
                                        List<SuperAgentDocumentStructureNode> structureNodes) {
        String sectionPath = commonSectionPath(blocks);
        String canonicalPath = firstNonBlank(blocks.stream().map(SuperAgentDocumentBlock::getCanonicalPath).toList());
        SuperAgentDocumentStructureNode structureNode = findStructureNode(sectionPath, canonicalPath, structureNodes);
        String text = joinBlockTexts(blocks);
        String title = resolveTitle(blocks, sectionPath);
        String chunkType = resolveChunkType(blocks);
        String keywords = buildKeywords(title, sectionPath, text);
        String questions = buildQuestions(title, chunkType, keywords);
        return new ChunkCandidate(
            sectionPath,
            structureNode == null ? null : structureNode.getId(),
            structureNode == null ? null : structureNode.getNodeType(),
            structureNode == null ? canonicalPath : StrUtil.blankToDefault(structureNode.getCanonicalPath(), canonicalPath),
            structureNode == null ? null : structureNode.getItemIndex(),
            text,
            buildContentWithWeight(text, sectionPath, title, chunkType, keywords, questions, joinBlockWeightedContents(blocks)),
            chunkType,
            title,
            keywords,
            questions,
            DocumentChunkSourceTypeEnum.ORIGINAL.getCode(),
            firstPageNo(blocks),
            resolvePageRange(blocks),
            blocks.size() == 1 ? blocks.get(0).getBboxJson() : null,
            toSourceBlockIds(blocks)
        );
    }

    private ChunkCandidate toSplitBlockSeed(SuperAgentDocumentBlock block,
                                            String splitText,
                                            List<SuperAgentDocumentStructureNode> structureNodes) {
        SuperAgentDocumentStructureNode structureNode = findStructureNode(block.getSectionPath(), block.getCanonicalPath(), structureNodes);
        String title = resolveTitle(List.of(block), block.getSectionPath());
        String chunkType = resolveChunkType(List.of(block));
        String keywords = buildKeywords(title, block.getSectionPath(), splitText);
        String questions = buildQuestions(title, chunkType, keywords);
        return new ChunkCandidate(
            block.getSectionPath(),
            structureNode == null ? null : structureNode.getId(),
            structureNode == null ? null : structureNode.getNodeType(),
            structureNode == null ? block.getCanonicalPath() : StrUtil.blankToDefault(structureNode.getCanonicalPath(), block.getCanonicalPath()),
            structureNode == null ? null : structureNode.getItemIndex(),
            splitText,
            buildContentWithWeight(splitText, block.getSectionPath(), title, chunkType, keywords, questions, null),
            chunkType,
            title,
            keywords,
            questions,
            DocumentChunkSourceTypeEnum.ORIGINAL.getCode(),
            block.getPageNo(),
            resolvePageRange(List.of(block)),
            block.getBboxJson(),
            toSourceBlockIds(List.of(block))
        );
    }

    private ChunkCandidate toBlockChunkCandidate(SuperAgentDocumentBlock block) {
        String text = renderBlockContent(block);
        String title = resolveTitle(List.of(block), block.getSectionPath());
        String chunkType = resolveChunkType(List.of(block));
        String keywords = buildKeywords(title, block.getSectionPath(), text);
        String questions = buildQuestions(title, chunkType, keywords);
        return new ChunkCandidate(
            block.getSectionPath(),
            null,
            null,
            block.getCanonicalPath(),
            null,
            text,
            buildContentWithWeight(text, block.getSectionPath(), title, chunkType, keywords, questions, renderBlockWeightedContent(block)),
            chunkType,
            title,
            keywords,
            questions,
            DocumentChunkSourceTypeEnum.ORIGINAL.getCode(),
            block.getPageNo(),
            resolvePageRange(List.of(block)),
            block.getBboxJson(),
            toSourceBlockIds(List.of(block))
        );
    }

    private SuperAgentDocumentStructureNode findStructureNode(String sectionPath,
                                                             String canonicalPath,
                                                             List<SuperAgentDocumentStructureNode> structureNodes) {
        if (structureNodes == null || structureNodes.isEmpty()) {
            return null;
        }
        String normalizedSection = StrUtil.blankToDefault(sectionPath, "").trim();
        String normalizedCanonical = StrUtil.blankToDefault(canonicalPath, "").trim();
        for (SuperAgentDocumentStructureNode node : structureNodes) {
            if (node == null) {
                continue;
            }
            if (StrUtil.isNotBlank(normalizedCanonical)
                && normalizedCanonical.equals(StrUtil.blankToDefault(node.getCanonicalPath(), "").trim())) {
                return node;
            }
            if (StrUtil.isNotBlank(normalizedSection)
                && (normalizedSection.equals(StrUtil.blankToDefault(node.getSectionPath(), "").trim())
                || normalizedSection.equals(StrUtil.blankToDefault(node.getTitle(), "").trim()))) {
                return node;
            }
        }
        return null;
    }

    private boolean hasBlockContent(SuperAgentDocumentBlock block) {
        return block != null && StrUtil.isNotBlank(renderBlockContent(block));
    }

    private boolean isTitleBlock(SuperAgentDocumentBlock block) {
        return block != null && "TITLE".equalsIgnoreCase(StrUtil.blankToDefault(block.getBlockType(), ""));
    }

    private String sectionKey(SuperAgentDocumentBlock block) {
        if (block == null) {
            return "";
        }
        return StrUtil.blankToDefault(block.getSectionPath(), "").trim();
    }

    private String commonSectionPath(List<SuperAgentDocumentBlock> blocks) {
        return firstNonBlank(blocks.stream()
            .map(SuperAgentDocumentBlock::getSectionPath)
            .distinct()
            .toList());
    }

    private String joinBlockTexts(List<SuperAgentDocumentBlock> blocks) {
        return blocks.stream()
            .map(this::renderBlockContent)
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.joining("\n\n"));
    }

    private String joinBlockWeightedContents(List<SuperAgentDocumentBlock> blocks) {
        return blocks.stream()
            .map(this::renderBlockWeightedContent)
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.joining("\n\n"));
    }

    private String renderBlockContent(SuperAgentDocumentBlock block) {
        if (block == null) {
            return "";
        }
        String blockType = StrUtil.blankToDefault(block.getBlockType(), "").trim().toUpperCase(Locale.ROOT);
        String text = firstNonBlank(Arrays.asList(block.getText(), block.getImageCaption()));
        if (StrUtil.isBlank(text) && StrUtil.isNotBlank(block.getTableHtml())) {
            text = block.getTableHtml();
        }
        if (StrUtil.isBlank(text)) {
            return "";
        }
        if ("TITLE".equals(blockType) && !text.startsWith("#")) {
            return "# " + text;
        }
        if ("TABLE".equals(blockType) && StrUtil.isNotBlank(block.getTableHtml())
            && !text.contains(block.getTableHtml())) {
            return "[TABLE]\n" + text + "\n\n" + block.getTableHtml();
        }
        if (("IMAGE".equals(blockType) || "FIGURE".equals(blockType)) && StrUtil.isNotBlank(block.getImageCaption())) {
            return "[IMAGE]\n" + block.getImageCaption();
        }
        return text;
    }

    private String renderBlockWeightedContent(SuperAgentDocumentBlock block) {
        if (block == null) {
            return "";
        }
        if (StrUtil.isNotBlank(block.getContentWithWeight())) {
            return block.getContentWithWeight().trim();
        }
        String text = renderBlockContent(block);
        String title = resolveTitle(List.of(block), block.getSectionPath());
        String chunkType = resolveChunkType(List.of(block));
        String keywords = buildKeywords(title, block.getSectionPath(), text);
        String questions = buildQuestions(title, chunkType, keywords);
        return buildContentWithWeight(text, block.getSectionPath(), title, chunkType, keywords, questions, null);
    }

    private String buildContentWithWeight(String text,
                                          String sectionPath,
                                          String title,
                                          String chunkType,
                                          String keywords,
                                          String questions,
                                          String parserWeightedContent) {
        List<String> parts = new ArrayList<>();
        if (StrUtil.isNotBlank(title)) {
            parts.add("[TITLE]\n" + title.trim());
        }
        if (StrUtil.isNotBlank(sectionPath)) {
            parts.add("[SECTION]\n" + sectionPath.trim());
        }
        if (StrUtil.isNotBlank(chunkType)) {
            parts.add("[CHUNK_TYPE]\n" + chunkType.trim());
        }
        String keywordText = jsonArrayToDisplayText(keywords);
        if (StrUtil.isNotBlank(keywordText)) {
            parts.add("[KEYWORDS]\n" + keywordText);
        }
        String questionText = jsonArrayToDisplayText(questions);
        if (StrUtil.isNotBlank(questionText)) {
            parts.add("[QUESTIONS]\n" + questionText);
        }

        String weightedBody = StrUtil.blankToDefault(parserWeightedContent, "").trim();
        if (StrUtil.isBlank(weightedBody)) {
            weightedBody = StrUtil.blankToDefault(text, "").trim();
        }
        if (StrUtil.isNotBlank(weightedBody)) {
            parts.add("[CONTENT]\n" + weightedBody);
        }
        return String.join("\n\n", parts).trim();
    }

    @Override
    public String rebuildContentWithWeight(ChunkCandidate candidate, String keywordsJson, String questionsJson) {
        if (candidate == null) {
            return "";
        }
        String parserWeightedContent = extractContentSection(candidate.getContentWithWeight());
        return buildContentWithWeight(
            candidate.getText(),
            candidate.getSectionPath(),
            candidate.getTitle(),
            candidate.getChunkType(),
            keywordsJson,
            questionsJson,
            parserWeightedContent
        );
    }

    /**
     * 从既有 contentWithWeight 里取回 {@code [CONTENT]} 段正文（可能是 parser 加权正文），供关键词与问题增强覆盖 keywords/questions
     * 后重算时保留同一份正文；取不到时返回 null，由 {@code buildContentWithWeight} 回落到 chunk 原文。
     */
    private String extractContentSection(String contentWithWeight) {
        if (StrUtil.isBlank(contentWithWeight)) {
            return null;
        }
        int marker = contentWithWeight.lastIndexOf("[CONTENT]\n");
        if (marker < 0) {
            return null;
        }
        String body = contentWithWeight.substring(marker + "[CONTENT]\n".length()).trim();
        return StrUtil.isBlank(body) ? null : body;
    }

    private String resolveChunkType(List<SuperAgentDocumentBlock> blocks) {
        List<String> blockTypes = blocks.stream()
            .map(SuperAgentDocumentBlock::getBlockType)
            .filter(StrUtil::isNotBlank)
            .map(type -> type.trim().toUpperCase(Locale.ROOT))
            .distinct()
            .toList();
        if (blockTypes.isEmpty()) {
            return "TEXT";
        }
        return blockTypes.size() == 1 ? blockTypes.get(0) : "MIXED";
    }

    private String resolveTitle(List<SuperAgentDocumentBlock> blocks, String sectionPath) {
        for (SuperAgentDocumentBlock block : blocks == null ? List.<SuperAgentDocumentBlock>of() : blocks) {
            if (isTitleBlock(block) && StrUtil.isNotBlank(block.getText())) {
                return normalizeTitle(block.getText());
            }
        }
        return normalizeTitle(lastSectionSegment(sectionPath));
    }

    private String normalizeTitle(String title) {
        String normalized = StrUtil.blankToDefault(title, "").trim();
        while (normalized.startsWith("#")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }

    private String lastSectionSegment(String sectionPath) {
        if (StrUtil.isBlank(sectionPath)) {
            return "";
        }
        String[] parts = sectionPath.split("[>/|]");
        for (int index = parts.length - 1; index >= 0; index--) {
            if (StrUtil.isNotBlank(parts[index])) {
                return parts[index].trim();
            }
        }
        return sectionPath.trim();
    }

    private String buildKeywords(String title, String sectionPath, String text) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        addKeyword(keywords, title);
        if (StrUtil.isNotBlank(sectionPath)) {
            for (String sectionPart : sectionPath.split("[>/|]")) {
                addKeyword(keywords, sectionPart);
            }
        }

        String sourceText = StrUtil.blankToDefault(text, "");
        Matcher englishMatcher = ENGLISH_WORD_PATTERN.matcher(sourceText.toLowerCase(Locale.ROOT));
        while (englishMatcher.find() && keywords.size() < 12) {
            addKeyword(keywords, englishMatcher.group());
        }

        Matcher chineseMatcher = CHINESE_KEYWORD_PATTERN.matcher(sourceText);
        while (chineseMatcher.find() && keywords.size() < 12) {
            String keyword = chineseMatcher.group();
            if (keyword.length() > 8) {
                keyword = keyword.substring(0, 8);
            }
            addKeyword(keywords, keyword);
        }
        return toJsonArray(new ArrayList<>(keywords).stream().limit(12).toList());
    }

    private void addKeyword(Set<String> keywords, String keyword) {
        String normalized = StrUtil.blankToDefault(keyword, "").trim();
        if (normalized.length() >= 2) {
            keywords.add(normalized);
        }
    }

    private String buildQuestions(String title, String chunkType, String keywords) {
        LinkedHashSet<String> questions = new LinkedHashSet<>();
        String topic = StrUtil.blankToDefault(title, "").trim();
        if (StrUtil.isBlank(topic)) {
            List<String> keywordList = parseJsonArray(keywords);
            topic = keywordList.isEmpty() ? "" : keywordList.get(0);
        }
        if (StrUtil.isNotBlank(topic)) {
            questions.add("关于" + topic + "的核心内容是什么？");
            questions.add(topic + "有哪些要求或注意事项？");
        }
        if ("TABLE".equalsIgnoreCase(chunkType)) {
            questions.add("这个表格说明了什么？");
        }
        if ("IMAGE".equalsIgnoreCase(chunkType) || "FIGURE".equalsIgnoreCase(chunkType)) {
            questions.add("这张图片说明了什么？");
        }
        return toJsonArray(new ArrayList<>(questions).stream().limit(4).toList());
    }

    private List<String> parseJsonArray(String jsonArray) {
        if (StrUtil.isBlank(jsonArray)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(jsonArray, new TypeReference<List<String>>() {
            }).stream().filter(StrUtil::isNotBlank).map(String::trim).toList();
        }
        catch (Exception ignored) {
            String normalized = jsonArray.replace("[", "").replace("]", "").replace("\"", "");
            return Arrays.stream(normalized.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .toList();
        }
    }

    private String jsonArrayToDisplayText(String jsonArray) {
        List<String> values = parseJsonArray(jsonArray);
        return values.isEmpty() ? "" : String.join("；", values);
    }

    private String toJsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        }
        catch (Exception exception) {
            throw new IllegalStateException("序列化 chunk 检索增强字段失败。", exception);
        }
    }

    private Integer firstPageNo(List<SuperAgentDocumentBlock> blocks) {
        return blocks.stream()
            .map(SuperAgentDocumentBlock::getPageNo)
            .filter(pageNo -> pageNo != null && pageNo > 0)
            .findFirst()
            .orElse(null);
    }

    private String resolvePageRange(List<SuperAgentDocumentBlock> blocks) {
        List<Integer> pages = blocks.stream()
            .map(SuperAgentDocumentBlock::getPageNo)
            .filter(pageNo -> pageNo != null && pageNo > 0)
            .distinct()
            .sorted()
            .toList();
        if (!pages.isEmpty()) {
            int first = pages.get(0);
            int last = pages.get(pages.size() - 1);
            return first == last ? String.valueOf(first) : first + "-" + last;
        }
        return firstNonBlank(blocks.stream()
            .map(SuperAgentDocumentBlock::getPageRange)
            .distinct()
            .toList());
    }

    private String toSourceBlockIds(List<SuperAgentDocumentBlock> blocks) {
        List<Long> ids = blocks.stream()
            .map(SuperAgentDocumentBlock::getId)
            .filter(id -> id != null && id > 0)
            .distinct()
            .toList();
        return ids.isEmpty()
            ? "[]"
            : ids.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    private List<Long> parseSourceBlockIds(String sourceBlockIds) {
        if (StrUtil.isBlank(sourceBlockIds)) {
            return List.of();
        }
        String normalized = sourceBlockIds.replace("[", "").replace("]", "").trim();
        if (StrUtil.isBlank(normalized)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String rawId : normalized.split(",")) {
            String trimmed = rawId.trim();
            if (StrUtil.isBlank(trimmed)) {
                continue;
            }
            try {
                ids.add(Long.parseLong(trimmed));
            }
            catch (NumberFormatException ignored) {
                log.warn("忽略非法 sourceBlockId: {}", trimmed);
            }
        }
        return ids;
    }

    private String firstNonBlank(List<String> values) {
        for (String value : values == null ? List.<String>of() : values) {
            if (StrUtil.isNotBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private List<DocumentStrategyStepDraft> buildDraftSteps(DocumentStrategyPipelineTypeEnum pipelineType,
                                                            List<Integer> strategyTypes,
                                                            Map<Integer, String> reasonMap) {
        List<DocumentStrategyStepDraft> draftList = new ArrayList<>();
        for (int index = 0; index < strategyTypes.size(); index++) {
            Integer strategyType = strategyTypes.get(index);
            draftList.add(new DocumentStrategyStepDraft(
                pipelineType.getCode(),
                strategyType,
                resolveRole(index, strategyType),
                DocumentStrategySourceTypeEnum.SYSTEM_RECOMMEND.getCode(),
                reasonMap.getOrDefault(strategyType, "系统为当前流水线生成的推荐步骤。")
            ));
        }
        return draftList;
    }

    private List<Integer> normalizePipelineTypes(List<Integer> requestStrategyTypes) {
        LinkedHashSet<Integer> requestTypeSet = new LinkedHashSet<>();
        for (Integer strategyType : requestStrategyTypes == null ? List.<Integer>of() : requestStrategyTypes) {
            if (DocumentStrategyTypeEnum.getRc(strategyType) != null) {
                requestTypeSet.add(strategyType);
            }
        }
        return new ArrayList<>(requestTypeSet);
    }

    private List<SuperAgentDocumentStrategyStep> buildNormalizedSteps(DocumentStrategyPipelineTypeEnum pipelineType,
                                                                      List<Integer> normalizedTypes,
                                                                      Map<Integer, SuperAgentDocumentStrategyStep> baseStepMap,
                                                                      Long documentId) {
        List<SuperAgentDocumentStrategyStep> normalizedStepList = new ArrayList<>();
        for (int index = 0; index < normalizedTypes.size(); index++) {
            Integer strategyType = normalizedTypes.get(index);
            SuperAgentDocumentStrategyStep baseStep = baseStepMap.get(strategyType);
            SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
            step.setDocumentId(documentId);
            step.setPipelineType(pipelineType.getCode());
            step.setStepNo(index + 1);
            step.setStrategyType(strategyType);
            step.setStrategyRole(resolveRole(index, strategyType));
            step.setSourceType(baseStep == null
                ? DocumentStrategySourceTypeEnum.USER_ADD.getCode()
                : DocumentStrategySourceTypeEnum.USER_KEEP.getCode());
            step.setExecuteStatus(DocumentStrategyExecuteStatusEnum.WAIT_EXECUTE.getCode());
            step.setRecommendReason(baseStep == null ? "用户手动追加该策略。" : baseStep.getRecommendReason());
            normalizedStepList.add(step);
        }
        return normalizedStepList;
    }

    private List<SuperAgentDocumentStrategyStep> sortPipelineSteps(List<SuperAgentDocumentStrategyStep> steps,
                                                                   DocumentStrategyPipelineTypeEnum pipelineType) {
        return steps.stream()
            .filter(step -> pipelineType.getCode().equalsIgnoreCase(
                StrUtil.blankToDefault(step.getPipelineType(), DocumentStrategyPipelineTypeEnum.CHILD.getCode())
            ))
            .sorted(Comparator.comparingInt(SuperAgentDocumentStrategyStep::getStepNo))
            .toList();
    }

    private List<ChunkCandidate> executePipeline(List<ChunkCandidate> sourceList,
                                                 List<SuperAgentDocumentStrategyStep> orderedSteps,
                                                 DocumentStrategyPipelineTypeEnum pipelineType,
                                                 KnowledgeBaseIndexingOptions indexingOptions) {
        List<ChunkCandidate> currentChunks = cleanupChunkList(sourceList);
        for (SuperAgentDocumentStrategyStep step : orderedSteps) {
            DocumentStrategyTypeEnum strategyType = DocumentStrategyTypeEnum.getRc(step.getStrategyType());
            if (strategyType == null) {
                continue;
            }
            currentChunks = switch (strategyType) {
                case STRUCTURE -> currentChunks;
                case RECURSIVE -> applyRecursiveChunking(currentChunks, pipelineType, indexingOptions);
                case SEMANTIC -> applySemanticChunking(currentChunks, pipelineType, indexingOptions);
                case LLM -> applyLlmChunking(currentChunks, pipelineType, indexingOptions);
            };
            currentChunks = cleanupChunkList(currentChunks);
        }
        return cleanupChunkList(currentChunks);
    }

    private String buildCombinedStrategySnapshot(List<DocumentStrategyStepDraft> parentSteps,
                                                 List<DocumentStrategyStepDraft> childSteps) {
        String parentSnapshot = buildPipelineSnapshot(parentSteps.stream()
            .map(DocumentStrategyStepDraft::getStrategyType)
            .toList());
        String childSnapshot = buildPipelineSnapshot(childSteps.stream()
            .map(DocumentStrategyStepDraft::getStrategyType)
            .toList());
        return "PARENT:" + parentSnapshot + ";CHILD:" + childSnapshot;
    }

    private String buildPipelineSnapshot(List<Integer> strategyTypes) {
        return strategyTypes.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(","));
    }

    private boolean shouldUseStructure(DocumentFileTypeEnum fileType, DocumentAnalysisResult analysisResult) {

        boolean suitableType = fileType == DocumentFileTypeEnum.PDF
            || fileType == DocumentFileTypeEnum.DOC
            || fileType == DocumentFileTypeEnum.DOCX
            || fileType == DocumentFileTypeEnum.MD
            || fileType == DocumentFileTypeEnum.HTML
            || fileType == DocumentFileTypeEnum.XLSX
            || fileType == DocumentFileTypeEnum.PNG
            || fileType == DocumentFileTypeEnum.JPG
            || fileType == DocumentFileTypeEnum.JPEG
            || fileType == DocumentFileTypeEnum.BMP
            || fileType == DocumentFileTypeEnum.GIF;
        if (fileType == DocumentFileTypeEnum.XLSX) {
            return true;
        }
        return suitableType && (analysisResult.getStructureLevel() >= DocumentStructureLevelEnum.MEDIUM.getCode()
            || analysisResult.getHeadingCount() >= 2);
    }

    private boolean shouldUseRecursive(DocumentAnalysisResult analysisResult,
                                       KnowledgeBaseIndexingOptions indexingOptions) {

        int recursiveMaxChars = resolveRecursiveMaxChars(DocumentStrategyPipelineTypeEnum.CHILD, indexingOptions);
        return analysisResult.getCharCount() >= recursiveMaxChars
            || analysisResult.getMaxParagraphLength() >= recursiveMaxChars;
    }

    private boolean shouldUseSemantic(DocumentAnalysisResult analysisResult,
                                      KnowledgeBaseIndexingOptions indexingOptions) {

        return analysisResult.getCharCount() >= resolveSemanticMinChars(DocumentStrategyPipelineTypeEnum.CHILD, indexingOptions)
            && analysisResult.getParagraphCount() >= 3
            && analysisResult.getContentQualityLevel() >= DocumentContentQualityLevelEnum.MEDIUM.getCode();
    }

    private boolean shouldUseLlm(DocumentAnalysisResult analysisResult,
                                 KnowledgeBaseIndexingOptions indexingOptions) {

        return Boolean.TRUE.equals(properties.getChunk().getRecommendLlmWhenLowQuality())
            && Boolean.TRUE.equals(properties.getChunk().getLlmEnabled())
            && chatModelProvider.getIfAvailable() != null
            && analysisResult.getContentQualityLevel().equals(DocumentContentQualityLevelEnum.LOW.getCode())
            && analysisResult.getCharCount() >= resolveSemanticMinChars(DocumentStrategyPipelineTypeEnum.CHILD, indexingOptions);
    }

    private List<ChunkCandidate> applyRecursiveChunking(List<ChunkCandidate> sourceList,
                                                        DocumentStrategyPipelineTypeEnum pipelineType,
                                                        KnowledgeBaseIndexingOptions indexingOptions) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        int maxChars = resolveRecursiveMaxChars(pipelineType, indexingOptions);
        int overlapChars = resolveRecursiveOverlap(maxChars, pipelineType, indexingOptions);
        for (ChunkCandidate candidate : sourceList) {

            List<String> splitTextList = recursiveSplit(candidate.getText(), maxChars, overlapChars);
            for (String splitText : splitTextList) {
                resultList.add(cloneChunkCandidate(candidate, splitText));
            }
        }
        return resultList;
    }

    private List<ChunkCandidate> applySemanticChunking(List<ChunkCandidate> sourceList,
                                                       DocumentStrategyPipelineTypeEnum pipelineType,
                                                       KnowledgeBaseIndexingOptions indexingOptions) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        int semanticMinChars = resolveSemanticMinChars(pipelineType, indexingOptions);
        for (ChunkCandidate candidate : sourceList) {
            if (StrUtil.isBlank(candidate.getText())
                || candidate.getText().length() <= semanticMinChars) {

                resultList.add(candidate);
                continue;
            }

            resultList.addAll(semanticSplit(candidate, pipelineType, indexingOptions));
        }
        return resultList;
    }

    private List<ChunkCandidate> applyLlmChunking(List<ChunkCandidate> sourceList,
                                                  DocumentStrategyPipelineTypeEnum pipelineType,
                                                  KnowledgeBaseIndexingOptions indexingOptions) {
        ObservedChatModelPort chatModel = chatModelProvider.getIfAvailable();
        if (!Boolean.TRUE.equals(properties.getChunk().getLlmEnabled()) || chatModel == null) {
            throw new IllegalStateException("当前切块方案包含 LLM 策略，但 LLM 切块未启用或 ObservedChatModelPort 不可用。");
        }

        List<ChunkCandidate> resultList = new ArrayList<>();
        for (ChunkCandidate candidate : sourceList) {
            if (StrUtil.isBlank(candidate.getText())) {
                continue;
            }

            int llmMaxChars = resolveLlmMaxChars(pipelineType, indexingOptions);
            List<String> sourceTextList = candidate.getText().length() > llmMaxChars
                ? recursiveSplit(candidate.getText(), llmMaxChars, 0)
                : List.of(candidate.getText());

            for (String sourceText : sourceTextList) {
                List<String> llmChunkList = llmSplit(chatModel, sourceText);
                if (llmChunkList.isEmpty()) {
                    throw new IllegalStateException("LLM 切块没有返回有效片段。");
                }
                for (String llmChunk : llmChunkList) {
                    resultList.add(cloneChunkCandidate(candidate, llmChunk));
                }
            }
        }
        return resultList;
    }

    private List<ChunkCandidate> semanticSplit(ChunkCandidate candidate,
                                               DocumentStrategyPipelineTypeEnum pipelineType,
                                               KnowledgeBaseIndexingOptions indexingOptions) {
        List<ChunkCandidate> resultList = new ArrayList<>();
        List<String> sentenceList = splitSentences(candidate.getText());
        if (sentenceList.size() <= 1) {

            resultList.add(candidate);
            return resultList;
        }

        StringBuilder currentChunk = new StringBuilder();
        Set<String> currentTokenSet = new LinkedHashSet<>();
        int semanticMinChars = resolveSemanticMinChars(pipelineType, indexingOptions);
        int semanticMaxChars = resolveSemanticMaxChars(pipelineType, indexingOptions);
        double similarityThreshold = resolveSemanticSimilarityThreshold(indexingOptions);

        for (String sentence : sentenceList) {

            Set<String> sentenceTokenSet = extractTokens(sentence);

            boolean exceedMaxChars = currentChunk.length() + sentence.length() > semanticMaxChars;
            double similarity = currentTokenSet.isEmpty() ? 1D : lexicalSimilarity(currentTokenSet, sentenceTokenSet);
            boolean lexicalBreak = currentChunk.length() >= semanticMinChars
                && similarity < similarityThreshold;

            if (currentChunk.length() > 0 && (exceedMaxChars || lexicalBreak)) {

                resultList.add(cloneChunkCandidate(candidate, currentChunk.toString().trim()));
                currentChunk.setLength(0);
                currentTokenSet.clear();
            }

            currentChunk.append(sentence);
            currentTokenSet.addAll(sentenceTokenSet);
        }

        if (currentChunk.length() > 0) {
            resultList.add(cloneChunkCandidate(candidate, currentChunk.toString().trim()));
        }
        return resultList;
    }

    private List<String> recursiveSplit(String text, int maxChars, int overlapChars) {
        String trimmed = text == null ? "" : text.trim();
        if (StrUtil.isBlank(trimmed)) {
            return List.of();
        }
        if (trimmed.length() <= maxChars) {

            return List.of(trimmed);
        }

        List<String> paragraphList = splitByRegex(trimmed, "\\n\\s*\\n");
        if (paragraphList.size() > 1) {
            return mergeAndSplit(paragraphList, maxChars, overlapChars);
        }

        List<String> lineList = splitByRegex(trimmed, "\\n");
        if (lineList.size() > 1) {
            return mergeAndSplit(lineList, maxChars, overlapChars);
        }

        List<String> sentenceList = splitSentences(trimmed);
        if (sentenceList.size() > 1) {
            return mergeAndSplit(sentenceList, maxChars, overlapChars);
        }

        List<String> fixedWindowList = new ArrayList<>();
        int start = 0;
        int step = Math.max(1, maxChars - overlapChars);
        while (start < trimmed.length()) {

            int end = Math.min(trimmed.length(), start + maxChars);
            fixedWindowList.add(trimmed.substring(start, end).trim());
            if (end >= trimmed.length()) {
                break;
            }

            start += step;
        }
        return fixedWindowList;
    }

    private List<String> mergeAndSplit(List<String> segmentList, int maxChars, int overlapChars) {
        List<String> rawResultList = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String segment : segmentList) {
            String trimmed = segment.trim();
            if (StrUtil.isBlank(trimmed)) {
                continue;
            }

            if (trimmed.length() > maxChars) {

                if (current.length() > 0) {
                    rawResultList.add(current.toString().trim());
                    current.setLength(0);
                }
                rawResultList.addAll(recursiveSplit(trimmed, maxChars, overlapChars));
                continue;
            }

            if (current.length() + trimmed.length() + 1 > maxChars) {

                rawResultList.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(trimmed).append('\n');
        }

        if (current.length() > 0) {
            rawResultList.add(current.toString().trim());
        }
        return applyOverlap(rawResultList, maxChars, overlapChars);
    }

    private List<String> applyOverlap(List<String> rawChunkList, int maxChars, int overlapChars) {
        if (rawChunkList.isEmpty() || overlapChars <= 0) {
            return rawChunkList;
        }

        List<String> overlappedChunkList = new ArrayList<>(rawChunkList.size());
        for (int index = 0; index < rawChunkList.size(); index++) {
            String current = rawChunkList.get(index);
            if (StrUtil.isBlank(current)) {
                continue;
            }
            if (index == 0) {

                overlappedChunkList.add(current);
                continue;
            }

            String previous = rawChunkList.get(index - 1);
            String overlapPrefix = buildOverlapPrefix(previous, current, maxChars, overlapChars);
            if (StrUtil.isNotBlank(overlapPrefix)) {
                overlappedChunkList.add(overlapPrefix + "\n" + current);
            }
            else {
                overlappedChunkList.add(current);
            }
        }
        return overlappedChunkList;
    }

    private String buildOverlapPrefix(String previous, String current, int maxChars, int overlapChars) {
        if (StrUtil.isBlank(previous) || StrUtil.isBlank(current)) {
            return "";
        }

        int allowedChars = Math.min(overlapChars, Math.max(0, maxChars - current.length() - 1));
        if (allowedChars <= 0) {
            return "";
        }

        String suffix = previous.length() <= allowedChars
            ? previous
            : previous.substring(previous.length() - allowedChars);
        return suffix.trim();
    }

    private int resolveRecursiveOverlap(int maxChars,
                                        DocumentStrategyPipelineTypeEnum pipelineType,
                                        KnowledgeBaseIndexingOptions indexingOptions) {
        if (pipelineType == DocumentStrategyPipelineTypeEnum.PARENT) {
            return Math.min(indexingOptions.getChunk().getParentBlockOverlapChars(), Math.max(0, maxChars - 1));
        }
        Integer configuredOverlap = indexingOptions.getChunk().getChildRecursiveOverlapChars();
        if (configuredOverlap == null || configuredOverlap <= 0) {
            return 0;
        }

        return Math.min(configuredOverlap, Math.max(0, maxChars - 1));
    }

    private int resolveRecursiveMaxChars(DocumentStrategyPipelineTypeEnum pipelineType,
                                         KnowledgeBaseIndexingOptions indexingOptions) {
        return pipelineType == DocumentStrategyPipelineTypeEnum.PARENT
            ? indexingOptions.getChunk().getParentBlockMaxChars()
            : indexingOptions.getChunk().getChildRecursiveMaxChars();
    }

    private int resolveSemanticMaxChars(DocumentStrategyPipelineTypeEnum pipelineType,
                                        KnowledgeBaseIndexingOptions indexingOptions) {
        return pipelineType == DocumentStrategyPipelineTypeEnum.PARENT
            ? indexingOptions.getChunk().getParentSemanticMaxChars()
            : indexingOptions.getChunk().getChildSemanticMaxChars();
    }

    private int resolveSemanticMinChars(DocumentStrategyPipelineTypeEnum pipelineType,
                                        KnowledgeBaseIndexingOptions indexingOptions) {
        return pipelineType == DocumentStrategyPipelineTypeEnum.PARENT
            ? indexingOptions.getChunk().getParentSemanticMinChars()
            : indexingOptions.getChunk().getChildSemanticMinChars();
    }

    private double resolveSemanticSimilarityThreshold(KnowledgeBaseIndexingOptions indexingOptions) {
        return indexingOptions.getChunk().getChildSemanticSimilarityThreshold();
    }

    private int resolveLlmMaxChars(DocumentStrategyPipelineTypeEnum pipelineType,
                                   KnowledgeBaseIndexingOptions indexingOptions) {
        return pipelineType == DocumentStrategyPipelineTypeEnum.PARENT
            ? Math.max(properties.getChunk().getLlmMaxChars(), indexingOptions.getChunk().getParentBlockMaxChars())
            : properties.getChunk().getLlmMaxChars();
    }

    private List<String> splitByRegex(String text, String regex) {
        return Arrays.stream(text.split(regex))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    private List<String> splitSentences(String text) {
        return Arrays.stream(text.split("(?<=[。！？!?；;\\.])"))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    private Set<String> extractTokens(String text) {
        LinkedHashSet<String> tokenSet = new LinkedHashSet<>();
        Matcher matcher = ENGLISH_WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {

            tokenSet.add(matcher.group());
        }
        for (char current : text.toCharArray()) {

            if (String.valueOf(current).matches("[\\u4e00-\\u9fa5]")) {
                tokenSet.add(String.valueOf(current));
            }
        }

        return tokenSet;
    }

    /** Lexical token overlap. This is not embedding semantic chunking. */
    private double lexicalSimilarity(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0D;
        }

        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        return union.isEmpty() ? 0D : (double) intersection.size() / (double) union.size();
    }

    private List<String> llmSplit(ObservedChatModelPort chatModel, String sourceText) {
        String prompt = promptTemplateService.render(PromptTemplateNames.DOCUMENT_LLM_SPLIT, Map.of(
            "sourceText", StrUtil.blankToDefault(sourceText, "")
        ));

        try {

            String content = chatModel.callText("document-llm-split", null, prompt);

            if (StrUtil.isBlank(content)) {
                return List.of();
            }
            String jsonArray = extractJsonArray(content);
            if (StrUtil.isBlank(jsonArray)) {
                return List.of();
            }

            List<String> resultList = objectMapper.readValue(jsonArray, new TypeReference<List<String>>() {
            });
            return resultList.stream().filter(StrUtil::isNotBlank).map(String::trim).toList();
        }
        catch (Exception exception) {
            throw new IllegalStateException("大模型智能切块失败。", exception);
        }
    }

    private String extractJsonArray(String content) {

        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return content.substring(start, end + 1);
    }

    private List<ChunkCandidate> cleanupChunkList(List<ChunkCandidate> sourceList) {
        Map<String, ChunkCandidate> uniqueMap = new LinkedHashMap<>();
        for (ChunkCandidate candidate : sourceList) {
            if (candidate == null || StrUtil.isBlank(candidate.getText())) {
                continue;
            }
            String normalizedText = candidate.getText().trim();

            String uniqueKey = StrUtil.blankToDefault(candidate.getCanonicalPath(), candidate.getSectionPath())
                + "||" + candidate.getItemIndex()
                + "||" + normalizedText;
            uniqueMap.putIfAbsent(uniqueKey, cloneChunkCandidate(candidate, normalizedText));
        }
        return new ArrayList<>(uniqueMap.values());
    }

    private List<ParentBlockCandidate> cleanupParentBlockList(List<ParentBlockCandidate> sourceList) {
        Map<String, ParentBlockCandidate> uniqueMap = new LinkedHashMap<>();
        for (ParentBlockCandidate candidate : sourceList) {
            if (candidate == null || StrUtil.isBlank(candidate.getText())) {
                continue;
            }
            String normalizedText = candidate.getText().trim();
            String uniqueKey = StrUtil.blankToDefault(candidate.getCanonicalPath(), candidate.getSectionPath())
                + "||" + candidate.getItemIndex()
                + "||" + normalizedText;
            uniqueMap.putIfAbsent(uniqueKey, cloneParentBlockCandidate(candidate, normalizedText,
                candidate.getChildChunks() == null ? List.of() : new ArrayList<>(candidate.getChildChunks())));
        }
        return new ArrayList<>(uniqueMap.values());
    }

    private ChunkCandidate cloneChunkCandidate(ChunkCandidate source, String text) {
        if (source == null) {
            return new ChunkCandidate("", text, DocumentChunkSourceTypeEnum.ORIGINAL.getCode());
        }
        String normalizedText = StrUtil.blankToDefault(text, "").trim();
        String keywords = StrUtil.isNotBlank(source.getKeywords())
            ? source.getKeywords()
            : buildKeywords(source.getTitle(), source.getSectionPath(), normalizedText);
        String questions = StrUtil.isNotBlank(source.getQuestions())
            ? source.getQuestions()
            : buildQuestions(source.getTitle(), source.getChunkType(), keywords);
        boolean sameText = StrUtil.equals(normalizedText, StrUtil.blankToDefault(source.getText(), "").trim());
        String contentWithWeight = sameText && StrUtil.isNotBlank(source.getContentWithWeight())
            ? source.getContentWithWeight().trim()
            : buildContentWithWeight(normalizedText,
            source.getSectionPath(),
            source.getTitle(),
            source.getChunkType(),
            keywords,
            questions,
            null);
        return new ChunkCandidate(
            source.getSectionPath(),
            source.getStructureNodeId(),
            source.getStructureNodeType(),
            StrUtil.blankToDefault(source.getCanonicalPath(), ""),
            source.getItemIndex(),
            normalizedText,
            contentWithWeight,
            source.getChunkType(),
            source.getTitle(),
            keywords,
            questions,
            source.getSourceType(),
            source.getPageNo(),
            source.getPageRange(),
            source.getBboxJson(),
            source.getSourceBlockIds()
        );
    }

    private ParentBlockCandidate cloneParentBlockCandidate(ParentBlockCandidate source,
                                                           String text,
                                                           List<ChunkCandidate> childChunks) {
        if (source == null) {
            return new ParentBlockCandidate("", text, DocumentChunkSourceTypeEnum.ORIGINAL.getCode(), childChunks);
        }
        return new ParentBlockCandidate(
            source.getSectionPath(),
            source.getStructureNodeId(),
            source.getStructureNodeType(),
            StrUtil.blankToDefault(source.getCanonicalPath(), ""),
            source.getItemIndex(),
            text,
            source.getSourceType(),
            childChunks,
            source.getPageRange(),
            source.getSourceBlockIds()
        );
    }

    private Integer resolveRole(int index, Integer strategyType) {
        if (index == 0) {
            return DocumentStrategyRoleEnum.PRIMARY.getCode();
        }
        if (DocumentStrategyTypeEnum.RECURSIVE.getCode().equals(strategyType)) {
            return DocumentStrategyRoleEnum.LENGTH_CONTROL.getCode();
        }
        if (DocumentStrategyTypeEnum.SEMANTIC.getCode().equals(strategyType)) {
            return DocumentStrategyRoleEnum.OPTIMIZE.getCode();
        }
        if (DocumentStrategyTypeEnum.LLM.getCode().equals(strategyType)) {
            return DocumentStrategyRoleEnum.ENHANCE.getCode();
        }
        return DocumentStrategyRoleEnum.OPTIMIZE.getCode();
    }

}
