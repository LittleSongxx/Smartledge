package org.smartledge.ai.manage.vo;

import java.util.List;

/**
 * 某份文档的授权视图（S23-B3）。
 */
public class DocumentAclViewVo {

    private final Long documentId;

    private final String documentName;

    /** 当前登录身份对该文档的有效权限（{@code MANAGE}/{@code WRITE}/{@code READ}/空）。 */
    private final String callerPermission;

    private final List<DocumentAclEntryVo> entries;

    public DocumentAclViewVo(Long documentId, String documentName, String callerPermission, List<DocumentAclEntryVo> entries) {
        this.documentId = documentId;
        this.documentName = documentName;
        this.callerPermission = callerPermission;
        this.entries = entries;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public String getDocumentName() {
        return documentName;
    }

    public String getCallerPermission() {
        return callerPermission;
    }

    public List<DocumentAclEntryVo> getEntries() {
        return entries;
    }
}
