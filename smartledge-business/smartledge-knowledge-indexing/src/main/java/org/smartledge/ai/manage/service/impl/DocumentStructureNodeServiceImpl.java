package org.smartledge.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentStructureNode;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentStructureNodeMapper;
import org.smartledge.ai.manage.service.DocumentStructureNodeService;
import org.smartledge.ai.manage.support.DocumentMarkdownSourceSpanCandidate;
import org.smartledge.ai.manage.support.DocumentStructureNodeCandidate;
import org.smartledge.ai.manage.support.DocumentStructureSyntaxProvenanceCandidate;
import org.smartledge.enums.BusinessStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @description: 服务实现层
 * @author: Song
 **/

@AllArgsConstructor
@Service
public class DocumentStructureNodeServiceImpl implements DocumentStructureNodeService {

    private final SuperAgentDocumentStructureNodeMapper structureNodeMapper;
    private final SuperAgentDocumentMapper documentMapper;
    private final UidGenerator uidGenerator;

    @Override
    public Long resolveCurrentParseTaskId(Long documentId) {
        if (documentId == null) {
            return null;
        }
        SuperAgentDocument document = documentMapper.selectById(documentId);
        return document == null ? null : document.getLastParseTaskId();
    }

    @Override
    public List<SuperAgentDocumentStructureNode> replaceDocumentNodes(Long documentId,
                                                                      Long parseTaskId,
                                                                      List<DocumentStructureNodeCandidate> candidates) {
        deleteByDocumentId(documentId);
        if (documentId == null || parseTaskId == null || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<Integer, Long> nodeIdMap = new LinkedHashMap<>();
        List<SuperAgentDocumentStructureNode> entities = new ArrayList<>();
        for (DocumentStructureNodeCandidate candidate : candidates) {
            if (candidate == null || candidate.getNodeNo() == null) {
                continue;
            }
            long id = uidGenerator.getUid();
            nodeIdMap.put(candidate.getNodeNo(), id);
        }
        for (DocumentStructureNodeCandidate candidate : candidates) {
            if (candidate == null || candidate.getNodeNo() == null) {
                continue;
            }
            SuperAgentDocumentStructureNode entity = new SuperAgentDocumentStructureNode();
            entity.setId(nodeIdMap.get(candidate.getNodeNo()));
            entity.setDocumentId(documentId);
            entity.setParseTaskId(parseTaskId);
            entity.setNodeNo(candidate.getNodeNo());
            entity.setNodeType(candidate.getNodeType());
            entity.setParentNodeId(candidate.getParentNodeNo() == null ? null : nodeIdMap.get(candidate.getParentNodeNo()));
            entity.setPrevSiblingNodeId(candidate.getPrevSiblingNodeNo() == null ? null : nodeIdMap.get(candidate.getPrevSiblingNodeNo()));
            entity.setNextSiblingNodeId(candidate.getNextSiblingNodeNo() == null ? null : nodeIdMap.get(candidate.getNextSiblingNodeNo()));
            entity.setDepth(candidate.getDepth());
            entity.setNodeCode(candidate.getNodeCode());
            entity.setTitle(candidate.getTitle());
            entity.setAnchorText(candidate.getAnchorText());
            entity.setCanonicalPath(candidate.getCanonicalPath());
            entity.setSectionPath(candidate.getSectionPath());
            entity.setContentText(candidate.getContentText());
            entity.setItemIndex(candidate.getItemIndex());
            applySyntaxProvenance(entity, candidate.getSyntaxProvenance());
            entity.setStatus(BusinessStatus.YES.getCode());
            structureNodeMapper.insert(entity);
            entities.add(entity);
        }
        return entities;
    }

    private void applySyntaxProvenance(SuperAgentDocumentStructureNode entity,
                                       DocumentStructureSyntaxProvenanceCandidate provenance) {
        if (entity == null || provenance == null) {
            return;
        }
        entity.setSyntaxSchemaVersion(provenance.getSchemaVersion());
        entity.setSyntaxSourceSha256(provenance.getSourceSha256());
        entity.setSyntaxNodeId(provenance.getSyntaxNodeId());
        entity.setSyntaxNodeType(provenance.getSyntaxNodeType());
        entity.setSyntaxSourceOrigin(provenance.getSourceOrigin());
        DocumentMarkdownSourceSpanCandidate span = provenance.getSourceSpan();
        if (span == null) {
            return;
        }
        entity.setSourceStartByte(span.getStartByte());
        entity.setSourceEndByte(span.getEndByte());
        entity.setSourceStartLine(span.getStartLine());
        entity.setSourceStartColumn(span.getStartColumn());
        entity.setSourceEndLine(span.getEndLine());
        entity.setSourceEndColumn(span.getEndColumn());
    }

    @Override
    public List<SuperAgentDocumentStructureNode> listDocumentNodes(Long documentId, Long parseTaskId) {
        if (documentId == null) {
            return List.of();
        }
        LambdaQueryWrapper<SuperAgentDocumentStructureNode> wrapper = new LambdaQueryWrapper<SuperAgentDocumentStructureNode>()
            .eq(SuperAgentDocumentStructureNode::getDocumentId, documentId)
            .eq(SuperAgentDocumentStructureNode::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentStructureNode::getNodeNo, SuperAgentDocumentStructureNode::getId);
        if (parseTaskId != null) {
            wrapper.eq(SuperAgentDocumentStructureNode::getParseTaskId, parseTaskId);
        }
        return structureNodeMapper.selectList(wrapper);
    }

    @Override
    public Map<Long, SuperAgentDocumentStructureNode> nodeMap(Long documentId, Long parseTaskId) {
        Map<Long, SuperAgentDocumentStructureNode> result = new LinkedHashMap<>();
        for (SuperAgentDocumentStructureNode node : listDocumentNodes(documentId, parseTaskId)) {
            result.put(node.getId(), node);
        }
        return result;
    }

    @Override
    public List<SuperAgentDocumentStructureNode> listChildren(Long documentId, Long parseTaskId, Long parentNodeId) {
        if (documentId == null || parentNodeId == null) {
            return List.of();
        }
        LambdaQueryWrapper<SuperAgentDocumentStructureNode> wrapper = new LambdaQueryWrapper<SuperAgentDocumentStructureNode>()
            .eq(SuperAgentDocumentStructureNode::getDocumentId, documentId)
            .eq(SuperAgentDocumentStructureNode::getParentNodeId, parentNodeId)
            .eq(SuperAgentDocumentStructureNode::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentStructureNode::getNodeNo, SuperAgentDocumentStructureNode::getId);
        if (parseTaskId != null) {
            wrapper.eq(SuperAgentDocumentStructureNode::getParseTaskId, parseTaskId);
        }
        return structureNodeMapper.selectList(wrapper);
    }

    @Override
    public SuperAgentDocumentStructureNode findById(Long documentId, Long parseTaskId, Long nodeId) {
        if (documentId == null || nodeId == null) {
            return null;
        }
        LambdaQueryWrapper<SuperAgentDocumentStructureNode> wrapper = new LambdaQueryWrapper<SuperAgentDocumentStructureNode>()
            .eq(SuperAgentDocumentStructureNode::getDocumentId, documentId)
            .eq(SuperAgentDocumentStructureNode::getId, nodeId)
            .eq(SuperAgentDocumentStructureNode::getStatus, BusinessStatus.YES.getCode());
        if (parseTaskId != null) {
            wrapper.eq(SuperAgentDocumentStructureNode::getParseTaskId, parseTaskId);
        }
        return structureNodeMapper.selectOne(wrapper);
    }

    @Override
    public SuperAgentDocumentStructureNode findPreviousSibling(Long documentId, Long parseTaskId, Long nodeId) {
        SuperAgentDocumentStructureNode node = findById(documentId, parseTaskId, nodeId);
        return node == null ? null : findById(documentId, parseTaskId, node.getPrevSiblingNodeId());
    }

    @Override
    public SuperAgentDocumentStructureNode findNextSibling(Long documentId, Long parseTaskId, Long nodeId) {
        SuperAgentDocumentStructureNode node = findById(documentId, parseTaskId, nodeId);
        return node == null ? null : findById(documentId, parseTaskId, node.getNextSiblingNodeId());
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        structureNodeMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentStructureNode>()
            .eq(SuperAgentDocumentStructureNode::getDocumentId, documentId));
    }
}
