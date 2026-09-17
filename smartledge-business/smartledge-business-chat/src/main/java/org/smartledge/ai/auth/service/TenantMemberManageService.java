package org.smartledge.ai.auth.service;

import org.smartledge.ai.auth.dto.TenantMemberPageQueryDto;
import org.smartledge.ai.auth.dto.TenantMemberSaveDto;
import org.smartledge.ai.auth.dto.TenantMemberStatusUpdateDto;
import org.smartledge.ai.auth.vo.TenantMemberItemVo;
import org.smartledge.ai.auth.vo.TenantMemberPageQueryVo;
import org.smartledge.ai.auth.vo.TenantRoleItemVo;

import java.util.List;

/**
 * 租户内成员与角色管理（S23-B2）。
 *
 * <p>作用域由**认证主体**决定，不由请求参数决定：所有读写都落在调用者自己的租户里，
 * 接口不接收 tenantId。跨租户的成员 id 在本接口里等同于"不存在"。</p>
 *
 * <p>本接口只做**角色分配**，不创建角色、不改角色的权限集合：租户能拥有的能力集合由平台
 * 定义（`smartledge_permission` 是全局字典），让租户管理员新建角色等于让租户自定义能力，
 * 那是平台端的事（见 S23 阶段文件 §2.4）。</p>
 */
public interface TenantMemberManageService {

    /** 成员分页（按登录名或显示名模糊匹配）。 */
    TenantMemberPageQueryVo queryPage(TenantMemberPageQueryDto dto);

    /** 新建（{@code id} 为空）或编辑成员；返回变更后的成员。 */
    TenantMemberItemVo save(TenantMemberSaveDto dto);

    /** 启用/停用成员；返回变更后的成员。 */
    TenantMemberItemVo updateStatus(TenantMemberStatusUpdateDto dto);

    /** 当前租户可分配的角色（含权限编码）。 */
    List<TenantRoleItemVo> listAssignableRoles();
}
