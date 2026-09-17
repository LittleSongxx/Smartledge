package org.smartledge.ai.manage.support;

import org.smartledge.database.tenant.TenantContext;

import java.util.function.Supplier;

/**
 * 按文档派生写入的租户作用域（S22 批次 2 的唯一入口）。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>解析与索引构建链路在<b>系统上下文</b>下运行（跨租户的构建调度、对账、跨文档投影），租户拦截器
 * 按设计跳过收窄。派生表（{@code document_chunk} / {@code document_parent_block} /
 * {@code document_structure_node} / {@code document_block} / {@code kg_entity|relation|evidence|community} /
 * {@code raptor_node}）都登记在 {@code SmartledgeTenantLineHandler.TENANT_TABLES} 里，因此系统上下文下插入的行
 * 只能取列默认值：文档属于租户 2 时，它的派生行会被错标为默认租户，检索按 {@code tenant_id} 收窄后读不到
 * 自己的内容，表现为"构建成功但检索为空"——一种看起来像隔离、实际是错位的隐蔽失败。</p>
 *
 * <p>本类把"这段派生写入属于哪个租户"收敛成一个入口：租户只来自<b>父文档</b>（与
 * {@link DocumentTenantLookup} 同一权威：{@code smartledge_document.tenant_id}），调用方不得自行决定，
 * 也不得用默认租户兜底。</p>
 *
 * <p>跨租户/全局操作（跨文档投影与社区重建、对账）必须显式声明 {@link #runCrossTenant}：让"这一段看得到
 * 全部租户"成为代码里可读的事实，而不是继承来的默认状态。两者可以安全嵌套，退出后恢复调用前的上下文。</p>
 */
public final class DerivedRowTenantScope {

    private DerivedRowTenantScope() {
    }

    /**
     * 在父文档租户作用域内执行按文档的派生写入。
     *
     * @param documentTenantId 父文档租户；缺失（{@code null}）或非正数（含 {@link TenantContext#SYSTEM}）一律
     *                         拒绝，绝不退化成默认租户
     */
    public static void runPerDocument(Long documentTenantId, Runnable action) {
        TenantContext.runWith(requireDocumentTenant(documentTenantId), action);
    }

    /** 在父文档租户作用域内执行并返回结果，用于需要拿回返回值的按文档写路径。 */
    public static <T> T callPerDocument(Long documentTenantId, Supplier<T> action) {
        return TenantContext.callWith(requireDocumentTenant(documentTenantId), action);
    }

    /** 显式声明跨租户/全局操作（整个租户表清单都不收窄）。 */
    public static void runCrossTenant(Runnable action) {
        TenantContext.runAsSystem(action);
    }

    /** 显式声明跨租户/全局操作并返回结果。 */
    public static <T> T callCrossTenant(Supplier<T> action) {
        return TenantContext.callAsSystem(action);
    }

    /**
     * 校验父文档租户。
     *
     * <p>这里刻意不做"没有租户就当作默认租户"的降级：默认租户兜底正是本问题（S22-E）的成因，
     * 也是 S21 系列缺陷的共同形态（S21-L/M/N/P）。</p>
     */
    public static Long requireDocumentTenant(Long documentTenantId) {
        if (documentTenantId == null || documentTenantId <= 0) {
            throw new IllegalStateException("缺少父文档租户：按文档的派生写入拒绝在系统上下文或默认租户下执行，documentTenantId="
                    + documentTenantId + "（系统上下文标记为 " + TenantContext.SYSTEM + "）");
        }
        return documentTenantId;
    }
}
