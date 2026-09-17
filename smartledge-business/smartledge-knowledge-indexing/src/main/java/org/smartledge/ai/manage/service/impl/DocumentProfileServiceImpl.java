package org.smartledge.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentProfile;
import org.smartledge.ai.manage.data.SuperAgentDocumentStructureNode;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentProfileMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentStructureNodeMapper;
import org.smartledge.ai.manage.service.DocumentProfileService;
import org.smartledge.ai.manage.service.DocumentStorageService;
import org.smartledge.ai.manage.support.DocumentAnalysisResult;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @description: 服务实现层
 * @author: Song
 **/
@Slf4j
@AllArgsConstructor
@Service
public class DocumentProfileServiceImpl implements DocumentProfileService {

    private static final int PROFILE_STATUS_SUCCESS = 2;

    private final SuperAgentDocumentMapper documentMapper;
    private final SuperAgentDocumentProfileMapper documentProfileMapper;
    private final SuperAgentDocumentStructureNodeMapper structureNodeMapper;
    private final DocumentStorageService storageService;
    private final UidGenerator uidGenerator;

    @Override
    public SuperAgentDocumentProfile generateProfile(Long documentId,
                                                     DocumentAnalysisResult analysisResult,
                                                     List<SuperAgentDocumentStructureNode> structureNodes) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        SuperAgentDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在: " + documentId);
        }
        String parsedText = analysisResult == null ? "" : StrUtil.blankToDefault(analysisResult.getParsedText(), "");
        List<SuperAgentDocumentStructureNode> safeNodes = structureNodes == null ? List.of() : structureNodes;
        DocumentProfileDraft draft = buildDraft(document, parsedText, safeNodes);

        SuperAgentDocumentProfile profile = documentProfileMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
            .eq(SuperAgentDocumentProfile::getDocumentId, documentId)
            .eq(SuperAgentDocumentProfile::getStatus, BusinessStatus.YES.getCode())
            .last("LIMIT 1"));
        boolean creating = profile == null;
        if (creating) {
            profile = new SuperAgentDocumentProfile();
            profile.setId(uidGenerator.getUid());
            profile.setDocumentId(documentId);
            profile.setProfileVersion(1);
            profile.setStatus(BusinessStatus.YES.getCode());
        }
        else {
            profile.setProfileVersion(Optional.ofNullable(profile.getProfileVersion()).orElse(0) + 1);
        }
        profile.setDocumentSummary(draft.documentSummary());
        profile.setDocumentType(draft.documentType());
        profile.setCoreTopics(joinJsonLikeArray(draft.coreTopics()));
        profile.setExampleQuestions(joinJsonLikeArray(draft.exampleQuestions()));
        profile.setGraphFriendly(draft.graphFriendly() ? 1 : 0);
        profile.setSupportsGraphOutline(draft.supportsGraphOutline() ? 1 : 0);
        profile.setSupportsItemLookup(draft.supportsItemLookup() ? 1 : 0);
        profile.setSupportsGraphAssist(draft.supportsGraphAssist() ? 1 : 0);
        profile.setProfileSource("auto");
        profile.setProfileStatus(PROFILE_STATUS_SUCCESS);
        profile.setErrorMsg(null);
        if (creating) {
            documentProfileMapper.insert(profile);
        }
        else {
            documentProfileMapper.updateById(profile);
        }

        log.info("文档画像生成完成: documentId={}, documentType={}, graphFriendly={}, supportsItemLookup={}",
            documentId,
            draft.documentType(),
            draft.graphFriendly(),
            draft.supportsItemLookup());
        return profile;
    }

    @Override
    public Optional<SuperAgentDocumentProfile> getByDocumentId(Long documentId) {
        if (documentId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(documentProfileMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
            .eq(SuperAgentDocumentProfile::getDocumentId, documentId)
            .eq(SuperAgentDocumentProfile::getStatus, BusinessStatus.YES.getCode())
            .last("LIMIT 1")));
    }

    @Override
    public SuperAgentDocumentProfile regenerateProfile(Long documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        SuperAgentDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在: " + documentId);
        }
        String parsedText = StrUtil.isBlank(document.getParseTextPath()) ? "" : storageService.downloadText(document.getParseTextPath());
        List<SuperAgentDocumentStructureNode> structureNodes = structureNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentStructureNode>()
            .eq(SuperAgentDocumentStructureNode::getDocumentId, documentId)
            .eq(SuperAgentDocumentStructureNode::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentStructureNode::getNodeNo, SuperAgentDocumentStructureNode::getId));
        DocumentAnalysisResult analysisResult = new DocumentAnalysisResult();
        analysisResult.setParsedText(parsedText);
        return generateProfile(documentId, analysisResult, structureNodes);
    }

    @Override
    public List<SuperAgentDocumentProfile> batchRegenerateProfiles(Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        List<SuperAgentDocumentProfile> profiles = new ArrayList<>();
        for (Long documentId : documentIds) {
            if (documentId == null) {
                continue;
            }
            profiles.add(regenerateProfile(documentId));
        }
        return profiles;
    }

    private DocumentProfileDraft buildDraft(SuperAgentDocument document,
                                            String parsedText,
                                            List<SuperAgentDocumentStructureNode> structureNodes) {
        List<String> sectionTitles = extractSectionTitles(structureNodes);
        boolean supportsItemLookup = structureNodes.stream().anyMatch(node -> node != null
            && (DocumentStructureNodeTypeEnum.STEP.getCode().equals(node.getNodeType())
            || DocumentStructureNodeTypeEnum.LIST_ITEM.getCode().equals(node.getNodeType())));
        boolean supportsGraphOutline = sectionTitles.size() >= 2;
        boolean graphFriendly = supportsItemLookup || supportsGraphOutline;
        String documentType = resolveStructuralDocumentType(sectionTitles, supportsItemLookup, supportsGraphOutline);
        List<String> coreTopics = buildCoreTopics(document, sectionTitles);
        List<String> exampleQuestions = buildExampleQuestions(coreTopics);
        String summary = buildSummary(document, sectionTitles, parsedText);
        return new DocumentProfileDraft(
            summary,
            documentType,
            coreTopics,
            exampleQuestions,
            graphFriendly,
            supportsGraphOutline,
            supportsItemLookup,
            true
        );
    }

    private List<String> extractSectionTitles(List<SuperAgentDocumentStructureNode> structureNodes) {
        if (CollUtil.isEmpty(structureNodes)) {
            return List.of();
        }
        return structureNodes.stream()
            .filter(node -> node != null && DocumentStructureNodeTypeEnum.SECTION.getCode().equals(node.getNodeType()))
            .map(SuperAgentDocumentStructureNode::getTitle)
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .distinct()
            .limit(8)
            .toList();
    }

    private String resolveStructuralDocumentType(List<String> sectionTitles,
                                                 boolean supportsItemLookup,
                                                 boolean supportsGraphOutline) {
        if (supportsItemLookup) {
            return "structured_items";
        }
        if (supportsGraphOutline) {
            return "structured_outline";
        }
        if (sectionTitles != null && !sectionTitles.isEmpty()) {
            return "structured_section";
        }
        return "plain_text";
    }

    private List<String> buildCoreTopics(SuperAgentDocument document, List<String> sectionTitles) {
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        sectionTitles.stream().limit(6).forEach(title -> addTopic(topics, stripSectionCode(title)));
        addTopic(topics, stripFileExtension(document.getDocumentName()));
        return new ArrayList<>(topics).stream().filter(StrUtil::isNotBlank).limit(6).toList();
    }

    private void addTopic(Set<String> topics, String topic) {
        String normalized = StrUtil.blankToDefault(topic, "").trim();
        if (normalized.isBlank()) {
            return;
        }
        topics.add(normalized);
    }

    private List<String> buildExampleQuestions(List<String> coreTopics) {
        return coreTopics.stream()
            .map(topic -> topic + "包含哪些内容？")
            .distinct()
            .limit(6)
            .toList();
    }

    private String buildSummary(SuperAgentDocument document, List<String> sectionTitles, String parsedText) {
        StringBuilder builder = new StringBuilder();
        builder.append("文档《").append(StrUtil.blankToDefault(document.getDocumentName(), "未命名文档")).append("》");
        if (!sectionTitles.isEmpty()) {
            builder.append("主要涵盖：").append(String.join("、", sectionTitles.stream().limit(4).toList())).append("。");
        }
        String excerpt = StrUtil.blankToDefault(parsedText, "").replaceAll("\\s+", " ").trim();
        if (excerpt.length() > 180) {
            excerpt = excerpt.substring(0, 180);
        }
        if (StrUtil.isNotBlank(excerpt)) {
            builder.append("摘要：").append(excerpt);
        }
        return builder.toString().trim();
    }

    private String stripSectionCode(String title) {
        String normalized = StrUtil.blankToDefault(title, "").trim();
        return normalized.replaceFirst("^(第[一二三四五六七八九十百0-9]+[章节条部分]\\s*)|(\\d+(?:\\.\\d+)+\\s*)", "").trim();
    }

    private String stripFileExtension(String fileName) {
        String normalized = StrUtil.blankToDefault(fileName, "").trim();
        int index = normalized.lastIndexOf('.');
        return index > 0 ? normalized.substring(0, index) : normalized;
    }

    private String joinJsonLikeArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
            .map(value -> "\"" + value.replace("\"", "\\\"") + "\"")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private record DocumentProfileDraft(
        String documentSummary,
        String documentType,
        List<String> coreTopics,
        List<String> exampleQuestions,
        boolean graphFriendly,
        boolean supportsGraphOutline,
        boolean supportsItemLookup,
        boolean supportsGraphAssist
    ) {
    }
}
