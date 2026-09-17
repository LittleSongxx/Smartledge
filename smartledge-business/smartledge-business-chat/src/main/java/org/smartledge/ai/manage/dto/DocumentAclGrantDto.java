package org.smartledge.ai.manage.dto;

import lombok.Data;

/**
 * 文档授权授予/改权限（S23-B3）。
 *
 * <p>"授予"与"改权限"是同一个调用：唯一键 {@code (document_id, principal_type, principal_id)}
 * 决定了同一主体在一份文档上只有一行。</p>
 */
@Data
public class DocumentAclGrantDto {

    private String documentId;

    /** {@code USER} 或 {@code ROLE}。 */
    private String principalType;

    /** 用户 id 或角色 id。 */
    private String principalId;

    /** {@code READ} / {@code WRITE} / {@code MANAGE}。 */
    private String permission;
}
