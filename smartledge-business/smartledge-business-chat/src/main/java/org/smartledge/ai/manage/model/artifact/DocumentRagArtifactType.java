package org.smartledge.ai.manage.model.artifact;

import java.util.Arrays;

public enum DocumentRagArtifactType {

    DOCUMENT("DOCUMENT", "原始文档", "document-"),
    STRUCTURE_NODE("STRUCTURE_NODE", "文档结构", "structure-"),
    PARSE_BLOCK("PARSE_BLOCK", "解析块", "block-"),
    PARENT_BLOCK("PARENT_BLOCK", "父级块", "parent-"),
    TABLE("TABLE", "表格", "table-"),
    CHILD_CHUNK("CHILD_CHUNK", "检索子块", "chunk-"),
    KG_ENTITY("KG_ENTITY", "图谱实体", "kg-entity-"),
    KG_COMMUNITY("KG_COMMUNITY", "图谱社区", "kg-community-"),
    KG_EVIDENCE("KG_EVIDENCE", "图谱证据", "kg-evidence-"),
    RAPTOR_NODE("RAPTOR_NODE", "层级摘要", "raptor-");

    private final String code;

    private final String label;

    private final String nodeIdPrefix;

    DocumentRagArtifactType(String code, String label, String nodeIdPrefix) {
        this.code = code;
        this.label = label;
        this.nodeIdPrefix = nodeIdPrefix;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public String nodeId(Long sourceId) {
        return sourceId == null ? "" : nodeIdPrefix + sourceId;
    }

    public Long sourceId(String nodeId) {
        if (nodeId == null || !nodeId.startsWith(nodeIdPrefix)) {
            return null;
        }
        try {
            return Long.valueOf(nodeId.substring(nodeIdPrefix.length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static DocumentRagArtifactType fromCode(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
            .filter(item -> item.code.equalsIgnoreCase(code.trim()))
            .findFirst()
            .orElse(null);
    }

    public static DocumentRagArtifactType fromNodeId(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        return Arrays.stream(values())
            .filter(item -> nodeId.startsWith(item.nodeIdPrefix))
            .findFirst()
            .orElse(null);
    }
}
