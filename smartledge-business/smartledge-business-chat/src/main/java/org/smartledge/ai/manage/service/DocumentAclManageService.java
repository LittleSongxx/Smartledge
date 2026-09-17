package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.dto.DocumentAclGrantDto;
import org.smartledge.ai.manage.dto.DocumentAclQueryDto;
import org.smartledge.ai.manage.dto.DocumentAclRevokeDto;
import org.smartledge.ai.manage.vo.DocumentAclPrincipalVo;
import org.smartledge.ai.manage.vo.DocumentAclViewVo;

/**
 * 文档授权的查询与变更（S23-B3）。
 *
 * <h2>两层资格，缺一不可</h2>
 *
 * <p>权限编码 {@code document:acl:manage} 回答"能不能做<b>授权管理</b>这类操作"（由控制器上的
 * {@code @RequiresPermission} 判定），文档 ACL 的 MANAGE 回答"能不能对<b>这份</b>文档授权"。
 * 两者是两道独立门槛：只满足前者就能给任意文档授权，等于把租户级权限放大成"看得见的每份文档都能改"。
 * 这与 S21 B3 的写路径判定口径完全一致（{@code DocumentManageServiceImpl.requireDocumentAccess}）。</p>
 *
 * <h2>不做的事</h2>
 *
 * <p>不重新解释可见性：本接口只读/写 ACL 行，可见性仍由作用域边界解析一次
 * （{@code 只解析一次、下游只消费}）。也不把 ACL 写进 chunk / 向量 / 索引元数据 ——
 * 授权变更因此**立即生效且无需重建索引**。</p>
 */
public interface DocumentAclManageService {

    /** 查询某份文档的授权列表（含已撤销行）。 */
    DocumentAclViewVo query(DocumentAclQueryDto dto);

    /** 授予或改权限；返回变更后的授权列表。 */
    DocumentAclViewVo grant(DocumentAclGrantDto dto);

    /** 撤销授权（软删）；返回变更后的授权列表。 */
    DocumentAclViewVo revoke(DocumentAclRevokeDto dto);

    /**
     * 可授权的用户与角色清单。
     *
     * <p>由 {@code document:acl:manage} 保护，而**不是** {@code user:manage}：能管理文档授权的人
     * 必须能看到"能授权给谁"，这是同一个业务动作的两半。返回字段限于区分主体所必需的最小集合。</p>
     */
    DocumentAclPrincipalVo listAssignablePrincipals();
}
