package org.smartledge.ai.auth.vo;

import java.util.List;

/**
 * 可分配角色（S23-B2）。
 *
 * <p>只暴露**权限编码**，不暴露权限显示名：编码是契约（后端 {@code @RequiresPermission} 用的就是它），
 * 而显示名属于可被修的数据。界面按编码解释能力，不依赖翻译表。</p>
 */
public class TenantRoleItemVo {

    private final Long id;

    private final String roleCode;

    private final String roleName;

    private final String description;

    private final List<String> permissionCodes;

    public TenantRoleItemVo(Long id, String roleCode, String roleName, String description, List<String> permissionCodes) {
        this.id = id;
        this.roleCode = roleCode;
        this.roleName = roleName;
        this.description = description;
        this.permissionCodes = permissionCodes;
    }

    public Long getId() {
        return id;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public String getRoleName() {
        return roleName;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getPermissionCodes() {
        return permissionCodes;
    }
}
