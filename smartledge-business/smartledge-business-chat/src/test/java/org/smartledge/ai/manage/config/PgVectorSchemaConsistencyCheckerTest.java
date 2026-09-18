package org.smartledge.ai.manage.config;

import org.smartledge.ai.model.config.AiModelProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 启动期 pgvector 结构一致性闸门的行为锁定：
 * 维度漂移、表缺失、HNSW 缺失、配置缺失都必须拒绝启动，且报错要指向可执行的修复动作。
 */
class PgVectorSchemaConsistencyCheckerTest {

    private static final String DOCUMENT_TABLE = "smartledge_document_embedding";
    private static final String RAPTOR_TABLE = "smartledge_raptor_embedding";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private AiModelProperties properties(int dimensions) {
        AiModelProperties properties = new AiModelProperties();
        properties.getEmbedding().setDimensions(dimensions);
        return properties;
    }

    private void mockTable(String tableName, String columnType, int hnswIndexes) {
        when(jdbcTemplate.queryForObject(PgVectorSchemaConsistencyChecker.COLUMN_TYPE_SQL, String.class, tableName))
            .thenReturn(columnType);
        when(jdbcTemplate.queryForObject(PgVectorSchemaConsistencyChecker.HNSW_INDEX_SQL, Integer.class, tableName))
            .thenReturn(hnswIndexes);
    }

    @Test
    @DisplayName("两张表都是 vector(1024) 且存在 HNSW 余弦索引时启动通过")
    void passesWhenSchemaMatchesConfig() {
        mockTable(DOCUMENT_TABLE, "vector(1024)", 1);
        mockTable(RAPTOR_TABLE, "vector(1024)", 2);

        assertThatCode(() -> new PgVectorSchemaConsistencyChecker(jdbcTemplate, properties(1024)).run(null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("列维度与配置不一致时拒绝启动并指向 S19 迁移")
    void rejectsDimensionDrift() {
        mockTable(DOCUMENT_TABLE, "vector", 1);

        assertThatThrownBy(() -> new PgVectorSchemaConsistencyChecker(jdbcTemplate, properties(1024)).run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("vector")
            .hasMessageContaining("1024")
            .hasMessageContaining("S19");
    }

    @Test
    @DisplayName("raptor 表维度漂移同样拒绝启动")
    void rejectsRaptorDimensionDrift() {
        mockTable(DOCUMENT_TABLE, "vector(1024)", 1);
        mockTable(RAPTOR_TABLE, "vector(1536)", 1);

        assertThatThrownBy(() -> new PgVectorSchemaConsistencyChecker(jdbcTemplate, properties(1024)).run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(RAPTOR_TABLE);
    }

    @Test
    @DisplayName("表或 embedding 列缺失时拒绝启动并指向基线 DDL")
    void rejectsMissingTable() {
        when(jdbcTemplate.queryForObject(PgVectorSchemaConsistencyChecker.COLUMN_TYPE_SQL, String.class, DOCUMENT_TABLE))
            .thenReturn(null);

        assertThatThrownBy(() -> new PgVectorSchemaConsistencyChecker(jdbcTemplate, properties(1024)).run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("create_table_postgres_sql.sql");
    }

    @Test
    @DisplayName("缺少 HNSW 余弦索引时拒绝启动（检索会退化为精确扫描）")
    void rejectsMissingHnswIndex() {
        mockTable(DOCUMENT_TABLE, "vector(1024)", 0);

        assertThatThrownBy(() -> new PgVectorSchemaConsistencyChecker(jdbcTemplate, properties(1024)).run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("HNSW");
    }

    @Test
    @DisplayName("app.ai.embedding.dimensions 未配置时拒绝启动")
    void rejectsUnconfiguredDimensions() {
        assertThatThrownBy(() -> new PgVectorSchemaConsistencyChecker(jdbcTemplate, properties(0)).run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.ai.embedding.dimensions");
    }
}
