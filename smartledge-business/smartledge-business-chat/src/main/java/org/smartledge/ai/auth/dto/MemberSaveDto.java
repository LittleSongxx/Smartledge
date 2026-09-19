package org.smartledge.ai.auth.dto;

import lombok.Data;

import java.util.List;

/**
 * 成员新建/编辑（S23-B2）。
 *
 * <p>{@code id} 为空表示新建（此时 {@code username} 与 {@code password} 必填）；
 * 非空表示编辑（{@code username} 与 {@code password} 不参与修改，成员身份不改名不改密）。
 * 角色分配只接受**本租户已有角色**的 id，本接口不创建角色。</p>
 */
@Data
public class TenantMemberSaveDto {

    private String id;

    private String username;

    private String displayName;

    private String password;

    /** 要授予的角色 id 列表。 */
    private List<String> roleIds;
}
