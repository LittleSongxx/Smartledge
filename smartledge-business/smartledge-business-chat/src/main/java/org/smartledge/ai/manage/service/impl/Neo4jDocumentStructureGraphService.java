package org.smartledge.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.manage.config.DocumentManageProperties;
import org.smartledge.ai.manage.model.graph.GraphItem;
import org.smartledge.ai.manage.model.graph.GraphSection;
import org.smartledge.ai.manage.service.DocumentStructureGraphService;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Query;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.Values;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * @description: 服务实现层
 * @author: Song
 **/

@AllArgsConstructor
@Service
@ConditionalOnBean(name = "documentManageNeo4jDriver")
public class Neo4jDocumentStructureGraphService implements DocumentStructureGraphService {

    private final Driver driver;
    private final DocumentManageProperties properties;

    @Override
    public boolean isGraphAvailable(Long documentId) {
        if (documentId == null) {
            return false;
        }
        try (Session session = openSession()) {
            return session.run(new Query("MATCH (d:Document {documentId: $documentId}) RETURN count(d) > 0 AS available",
                    Values.parameters("documentId", documentId)), queryTimeoutConfig())
                .single()
                .get("available")
                .asBoolean(false);
        }
        catch (Exception exception) {
            return false;
        }
    }

    @Override
    public GraphSection findSectionById(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        try (Session session = openSession()) {
            return session.run(new Query("""
                    MATCH (s:Section {documentId: $documentId, nodeId: $nodeId})
                    RETURN s
                    LIMIT 1
                    """,
                    Values.parameters("documentId", documentId, "nodeId", sectionNodeId)), queryTimeoutConfig())
                .list(record -> toGraphSection(record.get("s").asNode()))
                .stream()
                .findFirst()
                .orElse(null);
        }
    }

    @Override
    public GraphSection findSectionByCode(Long documentId, String nodeCode) {
        if (documentId == null || StrUtil.isBlank(nodeCode)) {
            return null;
        }
        try (Session session = openSession()) {
            return session.run(new Query("""
                    MATCH (s:Section {documentId: $documentId, nodeCode: $nodeCode})
                    RETURN s
                    ORDER BY s.nodeNo ASC
                    LIMIT 1
                    """,
                    Values.parameters("documentId", documentId, "nodeCode", nodeCode.trim())), queryTimeoutConfig())
                .list(record -> toGraphSection(record.get("s").asNode()))
                .stream()
                .findFirst()
                .orElse(null);
        }
    }

    @Override
    public GraphSection findSectionByTitle(Long documentId, String title) {
        if (documentId == null || StrUtil.isBlank(title)) {
            return null;
        }
        String normalized = normalize(title);
        try (Session session = openSession()) {
            return session.run(new Query("""
                    MATCH (s:Section {documentId: $documentId})
                    WHERE s.normalizedTitle = $normalized OR s.normalizedPath = $normalized
                    RETURN s
                    ORDER BY s.nodeNo ASC
                    LIMIT 1
                    """,
                    Values.parameters("documentId", documentId, "normalized", normalized)), queryTimeoutConfig())
                .list(record -> toGraphSection(record.get("s").asNode()))
                .stream()
                .findFirst()
                .orElse(null);
        }
    }

    @Override
    public GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath) {
        if (documentId == null || StrUtil.isBlank(canonicalPath)) {
            return null;
        }
        try (Session session = openSession()) {
            return session.run(new Query("""
                    MATCH (s:Section {documentId: $documentId, canonicalPath: $canonicalPath})
                    RETURN s
                    LIMIT 1
                    """,
                    Values.parameters("documentId", documentId, "canonicalPath", canonicalPath.trim())), queryTimeoutConfig())
                .list(record -> toGraphSection(record.get("s").asNode()))
                .stream()
                .findFirst()
                .orElse(null);
        }
    }

    @Override
    public List<GraphSection> listSections(Long documentId) {
        if (documentId == null) {
            return List.of();
        }
        try (Session session = openSession()) {
            return session.run(new Query("""
                    MATCH (s:Section {documentId: $documentId})
                    RETURN s
                    ORDER BY s.nodeNo ASC
                    """,
                    Values.parameters("documentId", documentId)), queryTimeoutConfig())
                .list(record -> toGraphSection(record.get("s").asNode()));
        }
    }

    @Override
    public List<GraphSection> listChildren(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        try (Session session = openSession()) {
            return session.run(new Query("""
                    MATCH (:Section {documentId: $documentId, nodeId: $nodeId})-[:HAS_CHILD]->(c:Section {documentId: $documentId})
                    RETURN c
                    ORDER BY c.nodeNo ASC
                    """,
                    Values.parameters("documentId", documentId, "nodeId", sectionNodeId)), queryTimeoutConfig())
                .list(record -> toGraphSection(record.get("c").asNode()));
        }
    }

    @Override
    public GraphSection parentSection(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        try (Session session = openSession()) {
            return session.run(new Query("""
                    MATCH (p:Section {documentId: $documentId})-[:HAS_CHILD]->(:Section {documentId: $documentId, nodeId: $nodeId})
                    RETURN p
                    LIMIT 1
                    """,
                    Values.parameters("documentId", documentId, "nodeId", sectionNodeId)), queryTimeoutConfig())
                .list(record -> toGraphSection(record.get("p").asNode()))
                .stream()
                .findFirst()
                .orElse(null);
        }
    }

    @Override
    public GraphSection previousSibling(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        try (Session session = openSession()) {
            return session.run(new Query("""
                    MATCH (p:Section {documentId: $documentId})-[:NEXT_SIBLING]->(:Section {documentId: $documentId, nodeId: $nodeId})
                    RETURN p
                    LIMIT 1
                    """,
                    Values.parameters("documentId", documentId, "nodeId", sectionNodeId)), queryTimeoutConfig())
                .list(record -> toGraphSection(record.get("p").asNode()))
                .stream()
                .findFirst()
                .orElse(null);
        }
    }

    @Override
    public GraphSection nextSibling(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        try (Session session = openSession()) {
            return session.run(new Query("""
                    MATCH (:Section {documentId: $documentId, nodeId: $nodeId})-[:NEXT_SIBLING]->(n:Section {documentId: $documentId})
                    RETURN n
                    LIMIT 1
                    """,
                    Values.parameters("documentId", documentId, "nodeId", sectionNodeId)), queryTimeoutConfig())
                .list(record -> toGraphSection(record.get("n").asNode()))
                .stream()
                .findFirst()
                .orElse(null);
        }
    }

    @Override
    public GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex) {
        if (documentId == null || sectionNodeId == null || itemIndex == null) {
            return null;
        }
        try (Session session = openSession()) {
            return session.run(new Query("""
                    MATCH (:Section {documentId: $documentId, nodeId: $sectionNodeId})-[:HAS_ITEM]->(i:Item {documentId: $documentId, itemIndex: $itemIndex})
                    RETURN i
                    ORDER BY i.nodeNo ASC
                    LIMIT 1
                    """,
                    Values.parameters("documentId", documentId, "sectionNodeId", sectionNodeId, "itemIndex", itemIndex)), queryTimeoutConfig())
                .list(record -> toGraphItem(record.get("i").asNode()))
                .stream()
                .findFirst()
                .orElse(null);
        }
    }

    @Override
    public List<GraphItem> listItems(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        try (Session session = openSession()) {
            return session.run(new Query("""
                    MATCH (:Section {documentId: $documentId, nodeId: $sectionNodeId})-[:HAS_ITEM]->(i:Item {documentId: $documentId})
                    RETURN i
                    ORDER BY i.nodeNo ASC
                    """,
                    Values.parameters("documentId", documentId, "sectionNodeId", sectionNodeId)), queryTimeoutConfig())
                .list(record -> toGraphItem(record.get("i").asNode()));
        }
    }

    private Session openSession() {
        return driver.session(SessionConfig.forDatabase(properties.getNeo4j().getDatabase()));
    }

    /**
     * 语句级超时。
     *
     * <p>驱动本身没有全局查询超时，只有连接超时；每条 Cypher 都必须显式带上事务配置，
     * 否则慢查询会一直占住调用线程。</p>
     */
    private TransactionConfig queryTimeoutConfig() {
        int seconds = properties.getNeo4j().getQueryTimeoutSeconds() == null
            ? 5
            : Math.max(1, properties.getNeo4j().getQueryTimeoutSeconds());
        return TransactionConfig.builder().withTimeout(Duration.ofSeconds(seconds)).build();
    }

    private GraphSection toGraphSection(org.neo4j.driver.types.Node node) {
        if (node == null) {
            return null;
        }
        return GraphSection.builder()
            .nodeId(asLong(node, "nodeId"))
            .documentId(asLong(node, "documentId"))
            .parseTaskId(asLong(node, "parseTaskId"))
            .nodeNo(asInteger(node, "nodeNo"))
            .depth(asInteger(node, "depth"))
            .parentNodeId(asLong(node, "parentNodeId"))
            .prevSiblingNodeId(asLong(node, "prevSiblingNodeId"))
            .nextSiblingNodeId(asLong(node, "nextSiblingNodeId"))
            .nodeCode(asText(node, "nodeCode"))
            .title(asText(node, "title"))
            .anchorText(asText(node, "anchorText"))
            .sectionPath(asText(node, "sectionPath"))
            .canonicalPath(asText(node, "canonicalPath"))
            .contentText(asText(node, "contentText"))
            .build();
    }

    private GraphItem toGraphItem(org.neo4j.driver.types.Node node) {
        if (node == null) {
            return null;
        }
        return GraphItem.builder()
            .nodeId(asLong(node, "nodeId"))
            .documentId(asLong(node, "documentId"))
            .parseTaskId(asLong(node, "parseTaskId"))
            .nodeNo(asInteger(node, "nodeNo"))
            .nodeType(asText(node, "nodeType"))
            .sectionNodeId(asLong(node, "sectionNodeId"))
            .prevSiblingNodeId(asLong(node, "prevSiblingNodeId"))
            .nextSiblingNodeId(asLong(node, "nextSiblingNodeId"))
            .title(asText(node, "title"))
            .anchorText(asText(node, "anchorText"))
            .sectionPath(asText(node, "sectionPath"))
            .canonicalPath(asText(node, "canonicalPath"))
            .contentText(asText(node, "contentText"))
            .itemIndex(asInteger(node, "itemIndex"))
            .build();
    }

    private Long asLong(org.neo4j.driver.types.Node node, String key) {
        return node.containsKey(key) && !node.get(key).isNull() ? node.get(key).asLong() : null;
    }

    private Integer asInteger(org.neo4j.driver.types.Node node, String key) {
        return node.containsKey(key) && !node.get(key).isNull() ? node.get(key).asInt() : null;
    }

    private String asText(org.neo4j.driver.types.Node node, String key) {
        return node.containsKey(key) && !node.get(key).isNull() ? node.get(key).asString("") : "";
    }

    private String normalize(String text) {
        return StrUtil.blankToDefault(text, "")
            .replaceAll("[\\s>`*#_\\-]+", "")
            .toLowerCase(Locale.ROOT);
    }
}
