package org.smartledge.ai.auth.vo;

import java.util.List;

/**
 * 成员列表项（S23-B2）。
 *
 * <p>**不包含**任何口令字段（哈希也不返回）：管理界面需要的是"这个人是谁、什么角色、能不能登录"，
 * 凭据不属于界面数据。</p>
 */
public class TenantMemberItemVo {

    private final Long id;

    private final String username;

    private final String displayName;

    private final Integer status;

    private final List<Long> roleIds;

    private final List<String> roleCodes;

    private final List<String> roleNames;

    /** 最近登录时间（epoch 毫秒）；从未登录为 null。 */
    private final Long lastLoginAt;

    /** 是否处于失败锁定中。 */
    private final Boolean locked;

    public TenantMemberItemVo(Long id,
                              String username,
                              String displayName,
                              Integer status,
                              List<Long> roleIds,
                              List<String> roleCodes,
                              List<String> roleNames,
                              Long lastLoginAt,
                              Boolean locked) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.status = status;
        this.roleIds = roleIds;
        this.roleCodes = roleCodes;
        this.roleNames = roleNames;
        this.lastLoginAt = lastLoginAt;
        this.locked = locked;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Integer getStatus() {
        return status;
    }

    public List<Long> getRoleIds() {
        return roleIds;
    }

    public List<String> getRoleCodes() {
        return roleCodes;
    }

    public List<String> getRoleNames() {
        return roleNames;
    }

    public Long getLastLoginAt() {
        return lastLoginAt;
    }

    public Boolean getLocked() {
        return locked;
    }
}
