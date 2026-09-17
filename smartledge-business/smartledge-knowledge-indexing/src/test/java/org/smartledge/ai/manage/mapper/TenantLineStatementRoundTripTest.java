package org.smartledge.ai.manage.mapper;

import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.apache.ibatis.annotations.Select;
import org.smartledge.database.tenant.SmartledgeTenantLineHandler;
import org.smartledge.database.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户拦截器 SQL 往返的语句安全测试。
 *
 * <p>对应缺陷 S21-N：租户拦截器对每条语句做「解析 -&gt; 重写 -&gt; 重新序列化」，
 * 而运行时的 JSqlParser 4.9 在重新序列化时把 {@code FOR UPDATE} 提到 {@code ORDER BY} 之前，
 * 于是原本合法的 {@code ... ORDER BY id FOR UPDATE} 变成 MySQL 无法解析的
 * {@code ... FOR UPDATE ORDER BY id}（1064 语法错误）。实测后果：跨文档图谱投影的全量刷新失败，
 * GraphRAG 构建任务停在「待修复」，文档卡在构建中。</p>
 *
 * <p>由于这条破坏发生在解析器层而不是业务层，只测业务逻辑无法发现它，因此这里直接对
 * 拦截器做往返验证，并把「旧写法会被重排」冻结成 characterization 断言，
 * 同时要求生产语句在开启租户改写后仍然是合法顺序。</p>
 */
class TenantLineStatementRoundTripTest {

    private final TenantLineInnerInterceptor interceptor =
        new TenantLineInnerInterceptor(new SmartledgeTenantLineHandler());

    @Test
    @DisplayName("往返会把 ORDER BY ... FOR UPDATE 重排成非法顺序（缺陷冻结）")
    void roundTripReordersOrderByBeforeForUpdate() {
        TenantContext.setSystem();
        try {
            String rewritten = interceptor.parserSingle(
                "SELECT * FROM smartledge_document WHERE status = 1 ORDER BY id FOR UPDATE", null);

            assertThat(rewritten).containsIgnoringCase("FOR UPDATE ORDER BY");
        }
        finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("跨文档图谱投影的锁语句在系统上下文下原样通过")
    void graphProjectionLockStatementSurvivesSystemContext() throws Exception {
        String statement = selectStatement(SuperAgentDocumentMapper.class, "selectActiveForGraphProjection");

        TenantContext.setSystem();
        try {
            String rewritten = interceptor.parserSingle(statement, null);

            assertThat(rewritten).doesNotContainIgnoringCase("FOR UPDATE ORDER BY");
            assertThat(rewritten).isEqualTo(statement);
        }
        finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("跨文档图谱投影的锁语句在租户上下文下注入租户条件且保持合法顺序")
    void graphProjectionLockStatementKeepsValidOrderWithTenantScope() throws Exception {
        String statement = selectStatement(SuperAgentDocumentMapper.class, "selectActiveForGraphProjection");

        TenantContext.set(1L);
        try {
            String rewritten = interceptor.parserSingle(statement, null);

            assertThat(rewritten).doesNotContainIgnoringCase("FOR UPDATE ORDER BY");
            assertThat(rewritten).contains("tenant_id");
            assertThat(rewritten).containsIgnoringCase("FOR UPDATE");
        }
        finally {
            TenantContext.clear();
        }
    }

    private String selectStatement(Class<?> mapperType, String methodName) throws Exception {
        Method method = mapperType.getMethod(methodName);
        return method.getAnnotation(Select.class).value()[0];
    }
}
