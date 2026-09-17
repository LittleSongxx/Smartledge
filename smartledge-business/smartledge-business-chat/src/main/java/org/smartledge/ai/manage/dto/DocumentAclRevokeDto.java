package org.smartledge.ai.manage.dto;

import lombok.Data;

/**
 * 文档授权撤销（S23-B3）。
 */
@Data
public class DocumentAclRevokeDto {

    private String documentId;

    private String principalType;

    private String principalId;
}
