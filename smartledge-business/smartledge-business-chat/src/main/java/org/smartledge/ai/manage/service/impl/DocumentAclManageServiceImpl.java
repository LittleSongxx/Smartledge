package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.smartledge.ai.auth.data.AuthRole;
import org.smartledge.ai.auth.data.AuthUserAccount;
import org.smartledge.ai.auth.mapper.AuthRoleMapper;
import org.smartledge.ai.auth.mapper.AuthUserAccountMapper;
import org.smartledge.ai.auth.support.AuthFailureException;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentDocumentAcl;
import org.smartledge.ai.manage.dto.DocumentAclGrantDto;
import org.smartledge.ai.manage.dto.DocumentAclQueryDto;
import org.smartledge.ai.manage.dto.DocumentAclRevokeDto;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.service.DocumentAclManageService;
import org.smartledge.ai.manage.service.DocumentAclStore;
import org.smartledge.ai.manage.vo.DocumentAclEntryVo;
import org.smartledge.ai.manage.vo.DocumentAclPrincipalVo;
import org.smartledge.ai.manage.vo.DocumentAclViewVo;
import org.smartledge.database.tenant.RequestIdentity;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.BaseCode;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 文档授权的查询与变更实现（S23-B3）。
 *
 * <p>作用域与身份只来自 {@link TenantContext}（请求进入时由认证主体写入）：接口不接收租户参数，
 * 跨租户的文档 id 在本实现里等同于"不存在"。</p>
 */
@Service
public class DocumentAclManageServiceImpl implements DocumentAclManageService {

    private static final Set<String> PRINCIPAL_TYPES = Set.of(
        SuperAgentDocumentAcl.PRINCIPAL_TYPE_USER, SuperAgentDocumentAcl.PRINCIPAL_TYPE_ROLE);

    private static final Map<String, Integer> PERMISSION_RANKS = Map.of(
        SuperAgentDocumentAcl.PERMISSION_READ, 1,
        SuperAgentDocumentAcl.PERMISSION_WRITE, 2,
        SuperAgentDocumentAcl.PERMISSION_MANAGE, 3);

    private final DocumentAclStore documentAclStore;

    private final SuperAgentDocumentMapper documentMapper;

    private final AuthUserAccountMapper userAccountMapper;

    private final AuthRoleMapper roleMapper;

    public DocumentAclManageServiceImpl(DocumentAclStore documentAclStore,
                                        SuperAgentDocumentMapper documentMapper,
                                        AuthUserAccountMapper userAccountMapper,
                                        AuthRoleMapper roleMapper) {
        this.documentAclStore = documentAclStore;
        this.documentMapper = documentMapper;
        this.userAccountMapper = userAccountMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    public DocumentAclViewVo query(DocumentAclQueryDto dto) {
        RequestIdentity identity = requireIdentity();
        Long documentId = parseRequiredLong(dto == null ? null : dto.getDocumentId(), "文档id");
        SuperAgentDocument document = requireManageableDocument(documentId, identity);
        return view(document, identity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentAclViewVo grant(DocumentAclGrantDto dto) {
        RequestIdentity identity = requireIdentity();
        Long documentId = parseRequiredLong(dto == null ? null : dto.getDocumentId(), "文档id");
        SuperAgentDocument document = requireManageableDocument(documentId, identity);
        String principalType = parsePrincipalType(dto == null ? null : dto.getPrincipalType());
        Long principalId = parseRequiredLong(dto == null ? null : dto.getPrincipalId(), "主体id");
        String permission = parsePermission(dto == null ? null : dto.getPermission());
        // 主体必须真实存在于本租户：不存在的 id 只会产生一行永远匹配不到任何身份的 ACL，
        // 而"授权看起来成功了"比"授权被拒绝"更难发现。
        requirePrincipalExists(identity, principalType, principalId);

        documentAclStore.grant(documentId, identity, principalType, principalId, permission, identity.userId());
        return view(document, identity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentAclViewVo revoke(DocumentAclRevokeDto dto) {
        RequestIdentity identity = requireIdentity();
        Long documentId = parseRequiredLong(dto == null ? null : dto.getDocumentId(), "文档id");
        SuperAgentDocument document = requireManageableDocument(documentId, identity);
        String principalType = parsePrincipalType(dto == null ? null : dto.getPrincipalType());
        Long principalId = parseRequiredLong(dto == null ? null : dto.getPrincipalId(), "主体id");

        boolean revoked = documentAclStore.revoke(documentId, principalType, principalId, identity);
        if (!revoked) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "该主体在这份文档上没有有效授权。");
        }
        // 撤销后重新解析一次：如果调用者因此失去了对这份文档的 MANAGE，整个事务回滚（抛异常）。
        // 没有这道闸门，一次误操作就能把文档变成"谁都管不了"，而恢复它只能靠直接改库。
        Set<Long> stillManageable = documentAclStore.manageableDocumentIds(List.of(documentId), identity);
        if (!stillManageable.contains(documentId)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                "该撤销会让你失去这份文档的管理权限，已拒绝（请先给其他主体授予管理权限）。");
        }
        return view(document, identity);
    }

    @Override
    public DocumentAclPrincipalVo listAssignablePrincipals() {
        RequestIdentity identity = requireIdentity();
        return TenantContext.callWith(identity.tenantId(), () -> {
            List<DocumentAclPrincipalVo.PrincipalItem> users = userAccountMapper.selectList(
                    new LambdaQueryWrapper<AuthUserAccount>()
                        .eq(AuthUserAccount::getTenantId, identity.tenantId())
                        .eq(AuthUserAccount::getStatus, 1)
                        .orderByAsc(AuthUserAccount::getId))
                .stream()
                .map(account -> new DocumentAclPrincipalVo.PrincipalItem(
                    account.getId(),
                    SuperAgentDocumentAcl.PRINCIPAL_TYPE_USER,
                    StrUtil.blankToDefault(account.getUsername(), ""),
                    StrUtil.blankToDefault(account.getDisplayName(), account.getUsername())))
                .toList();
            List<DocumentAclPrincipalVo.PrincipalItem> roles = roleMapper.selectList(new LambdaQueryWrapper<AuthRole>()
                    .eq(AuthRole::getTenantId, identity.tenantId())
                    .eq(AuthRole::getStatus, 1)
                    .orderByAsc(AuthRole::getId))
                .stream()
                .map(role -> new DocumentAclPrincipalVo.PrincipalItem(
                    role.getId(),
                    SuperAgentDocumentAcl.PRINCIPAL_TYPE_ROLE,
                    StrUtil.blankToDefault(role.getRoleCode(), ""),
                    StrUtil.blankToDefault(role.getRoleName(), role.getRoleCode())))
                .toList();
            return new DocumentAclPrincipalVo(users, roles);
        });
    }

    private DocumentAclViewVo view(SuperAgentDocument document, RequestIdentity identity) {
        List<DocumentAclStore.DocumentAclRecord> records =
            TenantContext.callWith(identity.tenantId(), () -> documentAclStore.listByDocument(document.getId(), identity));
        Map<String, PrincipalLabel> labels = resolvePrincipals(identity, records);
        List<DocumentAclEntryVo> entries = records.stream()
            .map(record -> {
                PrincipalLabel label = labels.get(nameKey(record.principalType(), record.principalId()));
                return new DocumentAclEntryVo(
                    record.principalType(),
                    record.principalId(),
                    label == null ? "" : label.name(),
                    label == null ? "" : label.ref(),
                    record.permission(),
                    record.grantedBy(),
                    record.enabled());
            })
            .toList();
        return new DocumentAclViewVo(document.getId(), document.getDocumentName(), callerPermission(document, identity), entries);
    }

    /**
     * 调用者对这份文档的有效权限。
     *
     * <p>复用 store 的三次投影（同一批读取），因此界面显示的"我有什么权限"与实际判定同源，
     * 不存在"界面说有、后端拒绝"的第二套解释。</p>
     */
    private String callerPermission(SuperAgentDocument document, RequestIdentity identity) {
        return TenantContext.callWith(identity.tenantId(), () -> {
            List<Long> single = List.of(document.getId());
            if (documentAclStore.manageableDocumentIds(single, identity).contains(document.getId())) {
                return SuperAgentDocumentAcl.PERMISSION_MANAGE;
            }
            if (documentAclStore.writableDocumentIds(single, identity).contains(document.getId())) {
                return SuperAgentDocumentAcl.PERMISSION_WRITE;
            }
            if (documentAclStore.visibleDocumentIds(single, identity).contains(document.getId())) {
                return SuperAgentDocumentAcl.PERMISSION_READ;
            }
            return "";
        });
    }

    /** 文档必须存在并且调用者对它持有 MANAGE（否则与不存在同义，不泄漏存在性）。 */
    private SuperAgentDocument requireManageableDocument(Long documentId, RequestIdentity identity) {
        SuperAgentDocument document = TenantContext.callWith(identity.tenantId(), () -> documentMapper.selectOne(
            new LambdaQueryWrapper<SuperAgentDocument>()
                .eq(SuperAgentDocument::getId, documentId)
                .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
                .last("LIMIT 1")));
        if (document == null) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "文档不存在。");
        }
        boolean manageable = TenantContext.callWith(identity.tenantId(),
            () -> documentAclStore.manageableDocumentIds(List.of(documentId), identity)).contains(documentId);
        if (!manageable) {
            throw new AuthFailureException(403, "当前账号没有该文档的授权管理权限");
        }
        return document;
    }

    private void requirePrincipalExists(RequestIdentity identity, String principalType, Long principalId) {
        boolean exists = TenantContext.callWith(identity.tenantId(), () -> {
            if (SuperAgentDocumentAcl.PRINCIPAL_TYPE_ROLE.equals(principalType)) {
                return roleMapper.selectCount(new LambdaQueryWrapper<AuthRole>()
                    .eq(AuthRole::getTenantId, identity.tenantId())
                    .eq(AuthRole::getId, principalId)
                    .eq(AuthRole::getStatus, 1)) > 0;
            }
            return userAccountMapper.selectCount(new LambdaQueryWrapper<AuthUserAccount>()
                .eq(AuthUserAccount::getTenantId, identity.tenantId())
                .eq(AuthUserAccount::getId, principalId)
                .eq(AuthUserAccount::getStatus, 1)) > 0;
        });
        if (!exists) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "被授权的主体不在当前租户内。");
        }
    }

    /**
     * 批量解析主体显示名与稳定标识。
     *
     * <p>两次查询（用户、角色）而不是每行一次：ACL 列表可能有多行，逐行查会让一个纯读取接口
     * 变成 N+1。名字取不到时留空而不是回退成 id，避免界面把内部 id 当成人名展示。</p>
     */
    private Map<String, PrincipalLabel> resolvePrincipals(RequestIdentity identity,
                                                         List<DocumentAclStore.DocumentAclRecord> records) {
        List<Long> userIds = records.stream()
            .filter(record -> SuperAgentDocumentAcl.PRINCIPAL_TYPE_USER.equals(record.principalType()))
            .map(DocumentAclStore.DocumentAclRecord::principalId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        List<Long> roleIds = records.stream()
            .filter(record -> SuperAgentDocumentAcl.PRINCIPAL_TYPE_ROLE.equals(record.principalType()))
            .map(DocumentAclStore.DocumentAclRecord::principalId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        Map<String, PrincipalLabel> labels = new LinkedHashMap<>();
        TenantContext.runWith(identity.tenantId(), () -> {
            if (!userIds.isEmpty()) {
                for (AuthUserAccount account : userAccountMapper.selectList(new LambdaQueryWrapper<AuthUserAccount>()
                    .eq(AuthUserAccount::getTenantId, identity.tenantId())
                    .in(AuthUserAccount::getId, userIds))) {
                    String display = StrUtil.blankToDefault(account.getDisplayName(), account.getUsername());
                    labels.put(nameKey(SuperAgentDocumentAcl.PRINCIPAL_TYPE_USER, account.getId()),
                        new PrincipalLabel(display + "（" + account.getUsername() + "）", account.getUsername()));
                }
            }
            if (!roleIds.isEmpty()) {
                for (AuthRole role : roleMapper.selectList(new LambdaQueryWrapper<AuthRole>()
                    .eq(AuthRole::getTenantId, identity.tenantId())
                    .in(AuthRole::getId, roleIds))) {
                    labels.put(nameKey(SuperAgentDocumentAcl.PRINCIPAL_TYPE_ROLE, role.getId()),
                        new PrincipalLabel(role.getRoleName() + "（角色）", role.getRoleCode()));
                }
            }
        });
        return labels;
    }

    /** 主体的展示名与稳定标识（用户：显示名 + 登录名；角色：角色名 + 角色编码）。 */
    private record PrincipalLabel(String name, String ref) {
    }

    private String nameKey(String principalType, Long principalId) {
        return principalType + "#" + principalId;
    }

    private String parsePrincipalType(String rawValue) {
        String value = StrUtil.trimToEmpty(rawValue).toUpperCase(Locale.ROOT);
        if (!PRINCIPAL_TYPES.contains(value)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "主体类型只能是 USER 或 ROLE。");
        }
        return value;
    }

    private String parsePermission(String rawValue) {
        String value = StrUtil.trimToEmpty(rawValue).toUpperCase(Locale.ROOT);
        if (!PERMISSION_RANKS.containsKey(value)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "权限只能是 READ / WRITE / MANAGE。");
        }
        return value;
    }

    private Long parseRequiredLong(String rawValue, String fieldName) {
        if (StrUtil.isBlank(rawValue)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + "不能为空。");
        }
        try {
            long value = Long.parseLong(rawValue.trim());
            if (value <= 0) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + "不能为空。");
            }
            return value;
        }
        catch (NumberFormatException exception) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + "格式非法。");
        }
    }

    private RequestIdentity requireIdentity() {
        RequestIdentity identity = TenantContext.getIdentity();
        if (identity == null) {
            throw new AuthFailureException(401, "请先登录");
        }
        return identity;
    }
}
