package org.smartledge.ai.manage.vo;

import java.util.List;

/**
 * 可以做文档授权的主体清单（S23-B3）。
 *
 * <p>**只为"选人/选角色"服务**：字段限于能区分主体所必需的最小集合（用户：id/登录名/显示名；
 * 角色：id/编码/名称）。不含锁定状态、邮箱、最近登录等与授权无关的信息。</p>
 */
public class DocumentAclPrincipalVo {

    private final List<PrincipalItem> users;

    private final List<PrincipalItem> roles;

    public DocumentAclPrincipalVo(List<PrincipalItem> users, List<PrincipalItem> roles) {
        this.users = users;
        this.roles = roles;
    }

    public List<PrincipalItem> getUsers() {
        return users;
    }

    public List<PrincipalItem> getRoles() {
        return roles;
    }

    /**
     * @param type {@code USER} 或 {@code ROLE}
     * @param ref  登录名或角色编码（稳定标识，界面用它做二次确认）
     */
    public record PrincipalItem(Long id, String type, String ref, String name) {
    }
}
