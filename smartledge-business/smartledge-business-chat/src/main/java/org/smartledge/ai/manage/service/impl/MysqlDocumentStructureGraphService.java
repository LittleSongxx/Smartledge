package org.smartledge.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.manage.data.SuperAgentDocumentStructureNode;
import org.smartledge.ai.manage.model.graph.GraphItem;
import org.smartledge.ai.manage.model.graph.GraphSection;
import org.smartledge.ai.manage.service.DocumentStructureGraphService;
import org.smartledge.ai.manage.service.DocumentStructureNodeService;
import org.smartledge.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * @description: 服务实现层
 * @author: Song
 **/

@AllArgsConstructor
@Service("mysqlDocumentStructureGraphService")
public class MysqlDocumentStructureGraphService implements DocumentStructureGraphService {

    private final DocumentStructureNodeService documentStructureNodeService;

    @Override
    public GraphSection findSectionById(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        return loadSectionMap(documentId).get(sectionNodeId);
    }

    @Override
    public GraphSection findSectionByCode(Long documentId, String nodeCode) {
        if (documentId == null || StrUtil.isBlank(nodeCode)) {
            return null;
        }
        return listSections(documentId).stream()
            .filter(section -> nodeCode.trim().equals(section.getNodeCode()))
            .findFirst()
            .orElse(null);
    }

    @Override
    public GraphSection findSectionByTitle(Long documentId, String title) {
        if (documentId == null || StrUtil.isBlank(title)) {
            return null;
        }
        String normalized = normalize(title);
        return listSections(documentId).stream()
            .filter(section -> normalized.equals(normalize(section.getTitle()))
                || normalized.equals(normalize(section.getAnchorText()))
                || normalized.equals(normalize(section.getSectionPath())))
            .findFirst()
            .orElse(null);
    }

    @Override
    public GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath) {
        if (documentId == null || StrUtil.isBlank(canonicalPath)) {
            return null;
        }
        return listSections(documentId).stream()
            .filter(section -> canonicalPath.trim().equals(section.getCanonicalPath()))
            .findFirst()
            .orElse(null);
    }

    @Override
    public List<GraphSection> listSections(Long documentId) {
        return loadSections(documentId);
    }

    @Override
    public List<GraphSection> listChildren(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        return listSections(documentId).stream()
            .filter(section -> Objects.equals(sectionNodeId, section.getParentNodeId()))
            .sorted(Comparator.comparing(GraphSection::getNodeNo, Comparator.nullsLast(Integer::compareTo)))
            .toList();
    }

    @Override
    public GraphSection parentSection(Long documentId, Long sectionNodeId) {
        GraphSection section = findSectionById(documentId, sectionNodeId);
        if (section == null || section.getParentNodeId() == null) {
            return null;
        }
        return findSectionById(documentId, section.getParentNodeId());
    }

    @Override
    public GraphSection previousSibling(Long documentId, Long sectionNodeId) {
        GraphSection section = findSectionById(documentId, sectionNodeId);
        if (section == null || section.getPrevSiblingNodeId() == null) {
            return null;
        }
        return findSectionById(documentId, section.getPrevSiblingNodeId());
    }

    @Override
    public GraphSection nextSibling(Long documentId, Long sectionNodeId) {
        GraphSection section = findSectionById(documentId, sectionNodeId);
        if (section == null || section.getNextSiblingNodeId() == null) {
            return null;
        }
        return findSectionById(documentId, section.getNextSiblingNodeId());
    }

    @Override
    public GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex) {
        if (documentId == null || sectionNodeId == null || itemIndex == null) {
            return null;
        }
        return listItems(documentId, sectionNodeId).stream()
            .filter(item -> Objects.equals(itemIndex, item.getItemIndex()))
            .findFirst()
            .orElse(null);
    }

    @Override
    public List<GraphItem> listItems(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        return loadItems(documentId).stream()
            .filter(item -> Objects.equals(sectionNodeId, item.getSectionNodeId()))
            .sorted(Comparator.comparing(GraphItem::getNodeNo, Comparator.nullsLast(Integer::compareTo)))
            .toList();
    }

    private Map<Long, GraphSection> loadSectionMap(Long documentId) {
        Map<Long, GraphSection> sectionMap = new LinkedHashMap<>();
        for (GraphSection section : loadSections(documentId)) {
            sectionMap.put(section.getNodeId(), section);
        }
        return sectionMap;
    }

    private List<GraphSection> loadSections(Long documentId) {
        if (documentId == null) {
            return List.of();
        }
        return documentStructureNodeService.listDocumentNodes(documentId, null).stream()
            .filter(node -> node != null && Objects.equals(DocumentStructureNodeTypeEnum.SECTION.getCode(), node.getNodeType()))
            .sorted(Comparator.comparing(SuperAgentDocumentStructureNode::getNodeNo, Comparator.nullsLast(Integer::compareTo)))
            .map(this::toGraphSection)
            .toList();
    }

    private List<GraphItem> loadItems(Long documentId) {
        if (documentId == null) {
            return List.of();
        }
        return documentStructureNodeService.listDocumentNodes(documentId, null).stream()
            .filter(node -> node != null
                && (Objects.equals(DocumentStructureNodeTypeEnum.STEP.getCode(), node.getNodeType())
                || Objects.equals(DocumentStructureNodeTypeEnum.LIST_ITEM.getCode(), node.getNodeType())))
            .sorted(Comparator.comparing(SuperAgentDocumentStructureNode::getNodeNo, Comparator.nullsLast(Integer::compareTo)))
            .map(this::toGraphItem)
            .toList();
    }

    private GraphSection toGraphSection(SuperAgentDocumentStructureNode node) {
        return GraphSection.builder()
            .nodeId(node.getId())
            .documentId(node.getDocumentId())
            .parseTaskId(node.getParseTaskId())
            .nodeNo(node.getNodeNo())
            .depth(node.getDepth())
            .parentNodeId(node.getParentNodeId())
            .prevSiblingNodeId(node.getPrevSiblingNodeId())
            .nextSiblingNodeId(node.getNextSiblingNodeId())
            .nodeCode(safeText(node.getNodeCode()))
            .title(safeText(node.getTitle()))
            .anchorText(safeText(node.getAnchorText()))
            .sectionPath(safeText(node.getSectionPath()))
            .canonicalPath(safeText(node.getCanonicalPath()))
            .contentText(safeText(node.getContentText()))
            .build();
    }

    private GraphItem toGraphItem(SuperAgentDocumentStructureNode node) {
        DocumentStructureNodeTypeEnum nodeTypeEnum = DocumentStructureNodeTypeEnum.getRc(node.getNodeType());
        return GraphItem.builder()
            .nodeId(node.getId())
            .documentId(node.getDocumentId())
            .parseTaskId(node.getParseTaskId())
            .nodeNo(node.getNodeNo())
            .nodeType(nodeTypeEnum == null ? "" : nodeTypeEnum.name())
            .sectionNodeId(node.getParentNodeId())
            .prevSiblingNodeId(node.getPrevSiblingNodeId())
            .nextSiblingNodeId(node.getNextSiblingNodeId())
            .title(safeText(node.getTitle()))
            .anchorText(safeText(node.getAnchorText()))
            .sectionPath(safeText(node.getSectionPath()))
            .canonicalPath(safeText(node.getCanonicalPath()))
            .contentText(safeText(node.getContentText()))
            .itemIndex(node.getItemIndex())
            .build();
    }

    private String normalize(String text) {
        return safeText(text)
            .replaceAll("[\\s>`*#_\\-]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
