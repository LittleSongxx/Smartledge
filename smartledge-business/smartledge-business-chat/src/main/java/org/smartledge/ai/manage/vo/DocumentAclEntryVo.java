package org.smartledge.ai.manage.vo;

/**
 * 一条文档授权（面向界面）。
 *
 * <p>{@code principalName} 是主体显示名（用户显示名或角色名）；{@code principalRef} 是稳定标识
 * （用户登录名或角色编码），用于界面在改名后仍能对上人。</p>
 */
public class DocumentAclEntryVo {

    private final String principalType;

    private final Long principalId;

    private final String principalName;

    private final String principalRef;

    private final String permission;

    private final Long grantedBy;

    private final Boolean enabled;

    public DocumentAclEntryVo(String principalType,
                              Long principalId,
                              String principalName,
                              String principalRef,
                              String permission,
                              Long grantedBy,
                              Boolean enabled) {
        this.principalType = principalType;
        this.principalId = principalId;
        this.principalName = principalName;
        this.principalRef = principalRef;
        this.permission = permission;
        this.grantedBy = grantedBy;
        this.enabled = enabled;
    }

    public String getPrincipalType() {
        return principalType;
    }

    public Long getPrincipalId() {
        return principalId;
    }

    public String getPrincipalName() {
        return principalName;
    }

    public String getPrincipalRef() {
        return principalRef;
    }

    public String getPermission() {
        return permission;
    }

    public Long getGrantedBy() {
        return grantedBy;
    }

    public Boolean getEnabled() {
        return enabled;
    }
}
