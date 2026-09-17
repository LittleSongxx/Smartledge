package org.smartledge.enums;

import lombok.Getter;

/**
 * @description: 枚举定义
 * @author: Song
 **/

public enum RetrievalChannelEnum {

    KEYWORD(1, "keyword","关键词检索"),

    VECTOR(2, "vector","向量检索"),

    RERANK(3,"rerank","重排序"),

    TABLE(4, "table", "结构化表格检索"),

    GRAPH_RAG(5, "graph-rag", "GraphRAG 图谱检索"),

    RAPTOR(6, "raptor", "RAPTOR 层级摘要检索"),

    STRUCTURE(7, "structure", "文档结构图检索");

    @Getter
    private final int code;
    @Getter
    private final String name;
    @Getter
    private final String desc;

    RetrievalChannelEnum(int code, String name, String desc) {
        this.code = code;
        this.name = name;
        this.desc = desc;
    }

    public static RetrievalChannelEnum fromCode(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("检索通道类型 code 不能为空");
        }
        for (RetrievalChannelEnum status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的检索通道类型 code: " + code);
    }
}
