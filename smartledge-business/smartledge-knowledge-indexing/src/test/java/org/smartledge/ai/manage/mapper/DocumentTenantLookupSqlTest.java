package org.smartledge.ai.manage.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档租户查询的语句语义测试。
 *
 * <p>对应线上缺陷（具身机器人手册建库时发现）：删除链路 {@code deleteDocument} 在同一事务里
 * 先把文档软删（status 1&rarr;0），再解析租户写 PGVector 墓碑；而 {@code selectTenantIdById}
 * 曾带 {@code AND status = 1} 过滤，软删后的行查不到租户 &rarr; 墓碑抛
 * 「无法确定文档所属租户」&rarr; 整个删除回滚。后果是**任何**文档删除都在墓碑一步失败。</p>
 *
 * <p>租户权威是 {@code smartledge_document.tenant_id} 本身：软删不改变文档的归属，
 * 清理派生数据（向量墓碑/删除、图谱、RAPTOR）恰恰发生在软删之后。因此这里的契约是：
 * 单文档租户查询不得按 status 过滤；文档行不存在时才返回 null。</p>
 */
class DocumentTenantLookupSqlTest {

    @Test
    @DisplayName("单文档租户查询不得按 status 过滤（软删后的墓碑/删除链路仍需租户）")
    void tenantSelectMustNotFilterByStatus() throws Exception {
        String statement = selectStatement("selectTenantIdById");

        assertThat(statement)
            .as("删除链路在同一事务先软删再解析租户，按 status 过滤会让所有文档删除失败")
            .doesNotContainIgnoringCase("status");
    }

    private static String selectStatement(String methodName) throws Exception {
        for (Method method : SuperAgentDocumentMapper.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                Select select = method.getAnnotation(Select.class);
                if (select != null) {
                    return String.join(" ", select.value()).trim();
                }
            }
        }
        throw new IllegalStateException("method not found or missing @Select: " + methodName);
    }
}
