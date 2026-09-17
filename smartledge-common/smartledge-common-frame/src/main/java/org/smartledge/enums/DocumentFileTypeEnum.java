package org.smartledge.enums;

/**
 * @description: 枚举定义
 * @author: Song
 **/

public enum DocumentFileTypeEnum {
    PDF(1, "PDF"),
    DOC(2, "DOC"),
    DOCX(3, "DOCX"),
    TXT(4, "TXT"),
    MD(5, "MD"),
    HTML(6, "HTML"),
    XLSX(7, "XLSX"),
    PNG(8, "PNG"),
    JPG(9, "JPG"),
    JPEG(10, "JPEG"),
    BMP(11, "BMP"),
    GIF(12, "GIF");

    private final Integer code;

    private final String msg;

    DocumentFileTypeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public Integer getCode() {
        return code;
    }

    public String getMsg() {
        return msg == null ? "" : msg;
    }

    public static DocumentFileTypeEnum getRc(Integer code) {
        for (DocumentFileTypeEnum item : DocumentFileTypeEnum.values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }

    public static DocumentFileTypeEnum fromFileName(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return null;
        }
        String suffix = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        return switch (suffix) {
            case "pdf" -> PDF;
            case "doc" -> DOC;
            case "docx" -> DOCX;
            case "txt" -> TXT;
            case "md", "markdown" -> MD;
            case "html", "htm" -> HTML;
            case "xlsx" -> XLSX;
            case "png" -> PNG;
            case "jpg" -> JPG;
            case "jpeg" -> JPEG;
            case "bmp" -> BMP;
            case "gif" -> GIF;
            default -> null;
        };
    }
}
