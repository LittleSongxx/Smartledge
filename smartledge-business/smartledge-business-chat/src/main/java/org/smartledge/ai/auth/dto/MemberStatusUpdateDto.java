package org.smartledge.ai.auth.dto;

import lombok.Data;

/**
 * 成员启用/停用（S23-B2）。
 */
@Data
public class TenantMemberStatusUpdateDto {

    private String id;

    /** 1=启用，0=停用；其它值一律拒绝。 */
    private String status;
}
