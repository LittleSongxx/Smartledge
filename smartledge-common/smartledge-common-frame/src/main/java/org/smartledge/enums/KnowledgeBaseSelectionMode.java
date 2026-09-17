package org.smartledge.enums;

import lombok.Getter;

@Getter
public enum KnowledgeBaseSelectionMode {

    NONE("不使用知识库检索"),

    ALL("使用全部启用知识库"),

    SELECTED("使用显式选择知识库");

    private final String label;

    KnowledgeBaseSelectionMode(String label) {
        this.label = label;
    }

    public static KnowledgeBaseSelectionMode fromName(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        for (KnowledgeBaseSelectionMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("未知的知识库选择模式: " + value);
    }
}
