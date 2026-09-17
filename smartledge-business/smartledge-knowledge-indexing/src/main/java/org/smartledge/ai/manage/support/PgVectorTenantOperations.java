package org.smartledge.ai.manage.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/**
 * pgvector 语句的唯一执行入口：每条语句都在**带租户会话变量的事务**里执行。
 *
 * <p>这是 RLS 生效的第二个前提（第一个是应用使用非超级用户连接，第三个是向量检索 over-fetch）。
 * 三件事必须同时成立：超级用户绕过 RLS；会话级 {@code set_config} 会在 Hikari 复用连接时串租户；
 * 而 ANN 索引遍历不参与策略过滤，只靠 RLS 过滤会在大规模下显著变慢。</p>
 *
 * <p>契约：</p>
 * <ul>
 *   <li>{@code set_config(name, value, true)} 的第三个参数是 {@code is_local = true} —— 事务级，
 *       提交或回滚后自动失效，连接还池时不会把租户带给下一个请求；</li>
 *   <li>调用方必须给出**明确租户**：{@code null}、系统标记（负数）都直接拒绝。
 *       系统上下文里读不到租户，静默改用默认租户会让 RLS 形同虚设；</li>
 *   <li>所有语句都走同一事务模板，因此 set_config 与业务语句必然在同一个连接上。</li>
 * </ul>
 *
 * <p>本类**不在组件扫描期装配**（没有 {@code @Component}/{@code @ConditionalOnBean}）：
 * 扫描期的条件装配依赖"pgvector 数据源 Bean 已注册"这一时序假设，顺序一变就会在启动时失败。
 * 它由 {@code DocumentManagePgVectorConfiguration} 与 JdbcTemplate 在同一个配置类里显式声明，顺序确定。</p>
 */
@Slf4j
public class PgVectorTenantOperations {

    /** 与 RLS 策略里读取的会话变量名一致（sql/表结构/迁移/S21-向量表租户化-PostgresSql.sql）。 */
    public static final String TENANT_SETTING = "app.tenant_id";

    private static final String SET_TENANT_SQL = "SELECT set_config('" + TENANT_SETTING + "', ?, true)";

    private final JdbcTemplate pgVectorJdbcTemplate;

    private final TransactionTemplate transactionTemplate;

    public PgVectorTenantOperations(@Qualifier("documentManagePgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate) {
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(
            Objects.requireNonNull(pgVectorJdbcTemplate.getDataSource(),
                "pgvector JdbcTemplate 必须绑定数据源，否则无法建立事务级租户作用域")));
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /** 查询（结果顺序由 SQL 决定，调用方不要依赖本方法做截断）。 */
    public <T> List<T> query(Long tenantId, String sql, RowMapper<T> rowMapper, Object... arguments) {
        return inTenantScope(tenantId, () -> pgVectorJdbcTemplate.query(sql, rowMapper, arguments));
    }

    /** 更新/删除。 */
    public int update(Long tenantId, String sql, Object... arguments) {
        return inTenantScope(tenantId, () -> pgVectorJdbcTemplate.update(sql, arguments));
    }

    /** 批量写入。 */
    public int[] batchUpdate(Long tenantId, String sql, BatchPreparedStatementSetter setter) {
        return inTenantScope(tenantId, () -> pgVectorJdbcTemplate.batchUpdate(sql, setter));
    }

    private <T> T inTenantScope(Long tenantId, java.util.function.Supplier<T> action) {
        Long resolvedTenant = requireTenant(tenantId);
        return transactionTemplate.execute(status -> {
            // 事务级设置：同一事务内的后续语句共享它，事务结束即失效。
            pgVectorJdbcTemplate.queryForObject(SET_TENANT_SQL, String.class, String.valueOf(resolvedTenant));
            return action.get();
        });
    }

    /**
     * 租户校验：只接受真实租户 id。
     *
     * <p>包级可见便于同包测试直接锁定该契约（这是"不静默用默认租户"的唯一实现点）。</p>
     */
    static Long requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("向量库操作缺少租户：调用方必须显式给出租户，不能用默认租户兜底");
        }
        if (tenantId <= 0L) {
            throw new IllegalStateException("向量库操作需要真实租户，收到的是系统或非法标记：" + tenantId);
        }
        return tenantId;
    }
}
