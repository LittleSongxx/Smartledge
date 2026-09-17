package org.smartledge.ai.manage.support;

import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.model.DocumentTenantRef;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文档 → 租户 的唯一查询入口（向量库写入需要真实租户）。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>索引构建链路在**系统上下文**下运行（跨租户的构建调度、对账、跨文档投影），
 * 而向量表启用了 RLS：写入的行必须与事务里的 {@code app.tenant_id} 一致，否则 WITH CHECK 拒绝。
 * 系统上下文里没有租户，因此写入侧必须从"这份数据属于哪个文档"回推租户。</p>
 *
 * <p>租户以 {@code smartledge_document.tenant_id} 为唯一权威：向量、图谱、RAPTOR 都是文档的派生内容，
 * 它们的租户必须等于父文档的租户，不由调用方另行决定。</p>
 */
@Component
public class DocumentTenantLookup {

    private final SuperAgentDocumentMapper documentMapper;

    public DocumentTenantLookup(SuperAgentDocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    /** 文档所属租户；文档不存在时返回 {@code null}（调用方必须按 fail closed 处理）。 */
    public Long tenantOfDocument(Long documentId) {
        if (documentId == null) {
            return null;
        }
        return documentMapper.selectTenantIdById(documentId);
    }

    /**
     * 批量解析文档租户；文档不存在或没有租户的行不会出现在结果里。
     */
    public Map<Long, Long> tenantsOfDocuments(Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctIds = documentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> tenants = new LinkedHashMap<>();
        for (DocumentTenantRef reference : documentMapper.selectTenantIdsByIds(distinctIds)) {
            if (reference.getId() != null && reference.getTenantId() != null) {
                tenants.put(reference.getId(), reference.getTenantId());
            }
        }
        return tenants;
    }
}
