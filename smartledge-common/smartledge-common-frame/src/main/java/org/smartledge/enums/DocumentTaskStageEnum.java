package org.smartledge.enums;

/**
 * @description: 枚举定义
 * @author: Song
 **/

public enum DocumentTaskStageEnum {
    FILE_UPLOAD(1, "文件上传"),
    CONTENT_PARSE(2, "内容解析"),
    STRATEGY_ROUTE(3, "策略路由"),
    STRATEGY_CONFIRM(4, "策略确认"),
    CHUNK_EXECUTE(5, "切块执行"),
    CHUNK_POST_PROCESS(6, "切块后处理"),
    VECTORIZE(7, "向量化"),
    STORE_COMPLETE(8, "入库完成"),
    KEYWORD_INDEX(9, "关键词索引"),
    GRAPH_RAG(10, "GraphRAG"),
    GRAPH_TYPED_INDEX(11, "GraphRAG 派生索引"),
    RAPTOR(12, "RAPTOR");

    private final Integer code;

    private final String msg;

    DocumentTaskStageEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public Integer getCode() {
        return code;
    }

    public String getMsg() {
        return msg == null ? "" : msg;
    }

    public static DocumentTaskStageEnum getRc(Integer code) {
        for (DocumentTaskStageEnum item : DocumentTaskStageEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }
}
