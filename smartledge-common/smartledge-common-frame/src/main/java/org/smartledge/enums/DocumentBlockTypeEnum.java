package org.smartledge.enums;

/**
 * @description: 枚举定义
 * @author: Song
 **/

public enum DocumentBlockTypeEnum {
    TEXT("TEXT", "正文"),
    TITLE("TITLE", "标题"),
    TABLE("TABLE", "表格"),
    IMAGE("IMAGE", "图片"),
    FIGURE("FIGURE", "图示"),
    FORMULA("FORMULA", "公式"),
    CODE("CODE", "代码"),
    HEADER("HEADER", "页眉"),
    FOOTER("FOOTER", "页脚");

    private final String code;

    private final String msg;

    DocumentBlockTypeEnum(String code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getCode() {
        return code;
    }

    public String getMsg() {
        return msg == null ? "" : msg;
    }

    public static DocumentBlockTypeEnum getRc(String code) {
        if (code == null) {
            return null;
        }
        for (DocumentBlockTypeEnum item : values()) {
            if (item.code.equalsIgnoreCase(code)) {
                return item;
            }
        }
        return null;
    }
}
