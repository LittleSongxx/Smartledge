package org.smartledge.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.model.graph.GraphItem;
import org.smartledge.ai.manage.model.graph.GraphSection;
import org.smartledge.ai.manage.service.DocumentStructureGraphService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @description: 服务实现层
 * @author: Song
 **/

@Slf4j
@AllArgsConstructor
@Primary
@Service
public class CompositeDocumentStructureGraphService implements DocumentStructureGraphService {

    private final MysqlDocumentStructureGraphService mysqlGraphService;
    private final ObjectProvider<Neo4jDocumentStructureGraphService> neo4jGraphServiceProvider;

    @Override
    public boolean isGraphAvailable(Long documentId) {
        return delegate(documentId).isGraphAvailable(documentId);
    }

    @Override
    public GraphSection findSectionById(Long documentId, Long sectionNodeId) {
        return delegate(documentId).findSectionById(documentId, sectionNodeId);
    }

    @Override
    public GraphSection findSectionByCode(Long documentId, String nodeCode) {
        return delegate(documentId).findSectionByCode(documentId, nodeCode);
    }

    @Override
    public GraphSection findSectionByTitle(Long documentId, String title) {
        return delegate(documentId).findSectionByTitle(documentId, title);
    }

    @Override
    public GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath) {
        return delegate(documentId).findSectionByCanonicalPath(documentId, canonicalPath);
    }

    @Override
    public List<GraphSection> listSections(Long documentId) {
        return delegate(documentId).listSections(documentId);
    }

    @Override
    public List<GraphSection> listChildren(Long documentId, Long sectionNodeId) {
        return delegate(documentId).listChildren(documentId, sectionNodeId);
    }

    @Override
    public GraphSection parentSection(Long documentId, Long sectionNodeId) {
        return delegate(documentId).parentSection(documentId, sectionNodeId);
    }

    @Override
    public GraphSection previousSibling(Long documentId, Long sectionNodeId) {
        return delegate(documentId).previousSibling(documentId, sectionNodeId);
    }

    @Override
    public GraphSection nextSibling(Long documentId, Long sectionNodeId) {
        return delegate(documentId).nextSibling(documentId, sectionNodeId);
    }

    @Override
    public GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex) {
        return delegate(documentId).findItemByIndex(documentId, sectionNodeId, itemIndex);
    }

    @Override
    public List<GraphItem> listItems(Long documentId, Long sectionNodeId) {
        return delegate(documentId).listItems(documentId, sectionNodeId);
    }

    private DocumentStructureGraphService delegate(Long documentId) {
        Neo4jDocumentStructureGraphService neo4jGraphService = neo4jGraphServiceProvider.getIfAvailable();
        if (neo4jGraphService != null && neo4jGraphService.isGraphAvailable(documentId)) {
            log.info("结构图服务选择 Neo4j: documentId={}", documentId);
            return neo4jGraphService;
        }
        log.info("结构图服务选择 MySQL 权威结构节点表: documentId={}, neo4jBeanAvailable={}", documentId, neo4jGraphService != null);
        return mysqlGraphService;
    }
}
