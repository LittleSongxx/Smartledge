package org.smartledge.ai.manage.service.impl;

import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.data.SuperAgentDocumentAcl;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentAclMapper;
import org.smartledge.ai.manage.service.DocumentAclStore;
import org.smartledge.database.tenant.RequestIdentity;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 文档 ACL 的 MyBatis 实现。
 *
 * <p>每个候选批次只查一次 ACL 表，然后在内存里按权限等级筛出可读/可写/可管理三个集合。
 * 等级单调（MANAGE ⇒ WRITE ⇒ READ），所以三个集合是同一次读取的三种投影，
 * 不会出现"两次查询结果不一致"的窗口。</p>
 *
 * <p>所有失败路径（无身份、无租户、SQL 异常）都返回空集合而不是抛出：
 * 门禁调用点需要的是"可见集合"，异常会让调用方更容易写出"出错就放行"的兜底。</p>
 */
@Slf4j
@Service
public class MybatisDocumentAclStore implements DocumentAclStore {

    private static final int ENABLED = 1;

    private static final int DISABLED = 0;

    private static final int RANK_READ = 1;

    private static final int RANK_WRITE = 2;

    private static final int RANK_MANAGE = 3;

    private final SuperAgentDocumentAclMapper documentAclMapper;

    private final UidGenerator uidGenerator;

    public MybatisDocumentAclStore(SuperAgentDocumentAclMapper documentAclMapper, UidGenerator uidGenerator) {
        this.documentAclMapper = documentAclMapper;
        this.uidGenerator = uidGenerator;
    }

    @Override
    public Set<Long> visibleDocumentIds(Collection<Long> documentIds, RequestIdentity identity) {
        return documentIdsAtLeast(documentIds, identity, RANK_READ);
    }

    @Override
    public Set<Long> writableDocumentIds(Collection<Long> documentIds, RequestIdentity identity) {
        return documentIdsAtLeast(documentIds, identity, RANK_WRITE);
    }

    @Override
    public Set<Long> manageableDocumentIds(Collection<Long> documentIds, RequestIdentity identity) {
        return documentIdsAtLeast(documentIds, identity, RANK_MANAGE);
    }

    @Override
    public List<DocumentAclRecord> listByDocument(Long documentId, RequestIdentity identity) {
        if (documentId == null || identity == null || identity.tenantId() == null) {
            return List.of();
        }
        try {
            return documentAclMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentAcl>()
                    .eq(SuperAgentDocumentAcl::getTenantId, identity.tenantId())
                    .eq(SuperAgentDocumentAcl::getDocumentId, documentId)
                    .orderByAsc(SuperAgentDocumentAcl::getPrincipalType)
                    .orderByAsc(SuperAgentDocumentAcl::getPrincipalId))
                .stream()
                .map(row -> new DocumentAclRecord(
                    row.getId(),
                    row.getPrincipalType(),
                    row.getPrincipalId(),
                    row.getPermission(),
                    row.getGrantedBy(),
                    Objects.equals(row.getStatus(), ENABLED)))
                .toList();
        }
        catch (RuntimeException exception) {
            // 与可见性解析同一口径：读不到就当作没有，绝不返回"看起来有权限"的结果。
            log.warn("文档授权行读取失败，按空集合处理，tenantId={}, documentId={}",
                identity.tenantId(), documentId, exception);
            return List.of();
        }
    }

    @Override
    public void grant(Long documentId,
                      RequestIdentity identity,
                      String principalType,
                      Long principalId,
                      String permission,
                      Long grantedBy) {
        if (documentId == null || identity == null || principalId == null
            || principalType == null || permission == null) {
            return;
        }
        // upsert：唯一键是 (document_id, principal_type, principal_id)，因此"再次授予"只能是改权限，
        // 不能是插入第二行；已撤销的行在这里被重新启用。
        SuperAgentDocumentAcl existing = documentAclMapper.selectOne(
            new LambdaQueryWrapper<SuperAgentDocumentAcl>()
                .eq(SuperAgentDocumentAcl::getTenantId, identity.tenantId())
                .eq(SuperAgentDocumentAcl::getDocumentId, documentId)
                .eq(SuperAgentDocumentAcl::getPrincipalType, principalType)
                .eq(SuperAgentDocumentAcl::getPrincipalId, principalId)
                .last("LIMIT 1"));
        if (existing != null) {
            documentAclMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocumentAcl>()
                .eq(SuperAgentDocumentAcl::getTenantId, identity.tenantId())
                .eq(SuperAgentDocumentAcl::getId, existing.getId())
                .set(SuperAgentDocumentAcl::getPermission, permission)
                .set(SuperAgentDocumentAcl::getGrantedBy, grantedBy)
                .set(SuperAgentDocumentAcl::getStatus, ENABLED));
            return;
        }
        SuperAgentDocumentAcl acl = new SuperAgentDocumentAcl();
        acl.setId(uidGenerator.getUid());
        acl.setTenantId(identity.tenantId());
        acl.setDocumentId(documentId);
        acl.setPrincipalType(principalType);
        acl.setPrincipalId(principalId);
        acl.setPermission(permission);
        acl.setGrantedBy(grantedBy);
        acl.setStatus(ENABLED);
        documentAclMapper.insert(acl);
    }

    @Override
    public boolean revoke(Long documentId, String principalType, Long principalId, RequestIdentity identity) {
        if (documentId == null || principalType == null || principalId == null
            || identity == null || identity.tenantId() == null) {
            return false;
        }
        return documentAclMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocumentAcl>()
            .eq(SuperAgentDocumentAcl::getTenantId, identity.tenantId())
            .eq(SuperAgentDocumentAcl::getDocumentId, documentId)
            .eq(SuperAgentDocumentAcl::getPrincipalType, principalType)
            .eq(SuperAgentDocumentAcl::getPrincipalId, principalId)
            .eq(SuperAgentDocumentAcl::getStatus, ENABLED)
            .set(SuperAgentDocumentAcl::getStatus, DISABLED)) > 0;
    }

    private Set<Long> documentIdsAtLeast(Collection<Long> documentIds, RequestIdentity identity, int requiredRank) {
        if (identity == null || identity.tenantId() == null || documentIds == null || documentIds.isEmpty()) {
            return Set.of();
        }
        List<Long> candidates = documentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (candidates.isEmpty()) {
            return Set.of();
        }
        Map<Long, Integer> bestRankByDocument;
        try {
            bestRankByDocument = bestRankByDocument(candidates, identity);
        }
        catch (RuntimeException exception) {
            // 权限判定失败必须收窄：读不到 ACL 就等于没有授权。
            log.warn("文档 ACL 解析失败，本次按不可见处理，tenantId={}, documentCount={}",
                identity.tenantId(), candidates.size(), exception);
            return Set.of();
        }
        Set<Long> allowed = new LinkedHashSet<>();
        bestRankByDocument.forEach((documentId, rank) -> {
            if (rank >= requiredRank) {
                allowed.add(documentId);
            }
        });
        return Set.copyOf(allowed);
    }

    private Map<Long, Integer> bestRankByDocument(List<Long> candidates, RequestIdentity identity) {
        LambdaQueryWrapper<SuperAgentDocumentAcl> query = new LambdaQueryWrapper<SuperAgentDocumentAcl>()
            .select(SuperAgentDocumentAcl::getDocumentId, SuperAgentDocumentAcl::getPermission)
            .eq(SuperAgentDocumentAcl::getTenantId, identity.tenantId())
            .in(SuperAgentDocumentAcl::getDocumentId, candidates)
            .eq(SuperAgentDocumentAcl::getStatus, ENABLED);

        Set<Long> roleIds = identity.roleIds();
        Long userId = identity.userId();
        query.and(scope -> {
            scope.eq(SuperAgentDocumentAcl::getPrincipalType, SuperAgentDocumentAcl.PRINCIPAL_TYPE_USER)
                .eq(SuperAgentDocumentAcl::getPrincipalId, userId);
            if (roleIds != null && !roleIds.isEmpty()) {
                scope.or(role -> role
                    .eq(SuperAgentDocumentAcl::getPrincipalType, SuperAgentDocumentAcl.PRINCIPAL_TYPE_ROLE)
                    .in(SuperAgentDocumentAcl::getPrincipalId, roleIds));
            }
        });

        Map<Long, Integer> bestRankByDocument = new HashMap<>();
        for (SuperAgentDocumentAcl acl : documentAclMapper.selectList(query)) {
            if (acl.getDocumentId() == null) {
                continue;
            }
            int rank = rank(acl.getPermission());
            bestRankByDocument.merge(acl.getDocumentId(), rank, Math::max);
        }
        return bestRankByDocument;
    }

    private int rank(String permission) {
        if (permission == null) {
            return 0;
        }
        return switch (permission.trim().toUpperCase()) {
            case SuperAgentDocumentAcl.PERMISSION_MANAGE -> RANK_MANAGE;
            case SuperAgentDocumentAcl.PERMISSION_WRITE -> RANK_WRITE;
            case SuperAgentDocumentAcl.PERMISSION_READ -> RANK_READ;
            default -> 0;
        };
    }
}
