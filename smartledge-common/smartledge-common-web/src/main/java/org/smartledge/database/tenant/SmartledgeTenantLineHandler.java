package org.smartledge.database.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

import java.util.Set;

/**
 * MyBatis-Plus 租户条件处理器。
 *
 * <p>它是 SQL 重写层的拦截器，因此**覆盖 SELECT / INSERT / UPDATE / DELETE**。
 * 这一点是本项目刻意选择的方案：Hibernate 的 {@code @TenantId} 只保证 SELECT 带租户条件，
 * 默认的 UPDATE / DELETE 不带 —— 也就是 {@code deleteById} 只按主键走，跨租户同主键会误删。
 * 本项目有大量 {@code updateById} 与 {@code update(null, wrapper)}，用 {@code @TenantId}
 * 会形成"已隔离"的假象，因此隔离以 SQL 重写为准。</p>
 *
 * <p>采用**显式登记表清单**而不是"排除少数表"：未登记的表保持原语义，
 * 新增表不会被静默纳入租户过滤，避免出现"表没有 tenant_id 列却被注入租户条件"的运行时失败。</p>
 */
public class SmartledgeTenantLineHandler implements TenantLineHandler {

    /** 已登记 tenant_id 的业务表。与 S21 迁移及后续新增租户表（含 S24 长期记忆）保持一致。 */
    public static final Set<String> TENANT_TABLES = Set.of(
        "smartledge_knowledge_base",
        "smartledge_knowledge_scope_node",
        "smartledge_knowledge_topic_node",
        "smartledge_topic_document_relation",
        "smartledge_document",
        "smartledge_document_profile",
        "smartledge_document_task",
        "smartledge_document_parent_block",
        "smartledge_document_chunk",
        "smartledge_document_structure_node",
        "smartledge_document_block",
        "smartledge_kg_entity",
        "smartledge_kg_relation",
        "smartledge_kg_evidence",
        "smartledge_raptor_node",
        "smartledge_chat_dialogue",
        "smartledge_chat_exchange",
        "smartledge_chat_retrieval_result",
        "smartledge_chat_channel_execution",
        "smartledge_chat_memory_summary",
        "smartledge_chat_exchange_feedback",
        "smartledge_long_term_memory",
        "smartledge_document_acl",
        "smartledge_user",
        "smartledge_role",
        "smartledge_user_role",
        "smartledge_role_permission",
        "smartledge_knowledge_route_trace",
        "smartledge_document_strategy_plan",
        "smartledge_document_strategy_step",
        "smartledge_document_parse_artifact",
        "smartledge_document_task_log"
    );

    @Override
    public Expression getTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            // fail closed：拿不到租户上下文就不允许构造查询。
            // 给默认租户会让所有未声明上下文的入口静默只操作租户 1，是最危险的失败模式。
            throw new IllegalStateException(
                "缺少租户上下文：请求入口必须设置 TenantContext，系统级任务必须显式声明 TenantContext.setSystem()");
        }
        return new LongValue(tenantId);
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        // 系统上下文：跳过租户收窄（对账、构建调度等跨租户工作）
        if (TenantContext.isSystem()) {
            return true;
        }
        return tableName == null || !TENANT_TABLES.contains(tableName.toLowerCase());
    }
}
