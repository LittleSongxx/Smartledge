package org.smartledge.ai.manage.config;

import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.support.DocumentPgVectorConstants;
import org.smartledge.ai.model.config.AiModelProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 启动期 pgvector 结构一致性闸门。
 *
 * <p>向量列维度、HNSW 余弦索引与 {@code app.ai.embedding.dimensions} 是同一契约的三份投影：
 * 任何一份漂移都会让检索退化为精确扫描，或让写入在运行期才报错。启动时用系统目录对账，
 * 不一致即拒绝启动，问题留在部署阶段而不是第一次问答。</p>
 */
@Slf4j
public class PgVectorSchemaConsistencyChecker implements ApplicationRunner {

    static final String COLUMN_TYPE_SQL = """
        SELECT format_type(a.atttypid, a.atttypmod)
          FROM pg_attribute a
          JOIN pg_class c ON a.attrelid = c.oid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relname = ? AND a.attname = 'embedding'
        """;

    static final String HNSW_INDEX_SQL = """
        SELECT count(*)
          FROM pg_indexes
         WHERE schemaname = 'public' AND tablename = ?
           AND indexdef ILIKE '%USING hnsw%' AND indexdef ILIKE '%vector_cosine_ops%'
        """;

    private final JdbcTemplate jdbcTemplate;

    private final int expectedDimensions;

    public PgVectorSchemaConsistencyChecker(@Qualifier("documentManagePgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
                                            AiModelProperties aiModelProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.expectedDimensions = aiModelProperties.getEmbedding().getDimensions();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (expectedDimensions < 1) {
            throw new IllegalStateException("app.ai.embedding.dimensions 未配置（当前=" + expectedDimensions
                + "），无法校验 pgvector 向量维度契约。");
        }
        check(DocumentPgVectorConstants.EMBEDDING_TABLE_NAME);
        check(DocumentPgVectorConstants.RAPTOR_EMBEDDING_TABLE_NAME);
        log.info("pgvector 结构一致性校验通过：两张向量表均为 vector({}) 且存在 HNSW 余弦索引。", expectedDimensions);
    }

    private void check(String qualifiedTableName) {
        String tableName = qualifiedTableName.substring(qualifiedTableName.indexOf('.') + 1);
        String columnType = jdbcTemplate.queryForObject(COLUMN_TYPE_SQL, String.class, tableName);
        if (columnType == null) {
            throw new IllegalStateException("pgvector 表 " + qualifiedTableName
                + " 不存在或缺少 embedding 列，请先执行 sql/表结构/PostgresSql/create_table_postgres_sql.sql。");
        }
        String expectedType = "vector(" + expectedDimensions + ")";
        if (!expectedType.equals(columnType)) {
            throw new IllegalStateException("pgvector 表 " + qualifiedTableName + " 的 embedding 列类型为 " + columnType
                + "，与应用配置 app.ai.embedding.dimensions=" + expectedDimensions
                + " 不一致；请同步修改两者并执行 sql/表结构/迁移/S19-附加索引与向量维度-PostgresSql.sql。");
        }
        Integer hnswIndexes = jdbcTemplate.queryForObject(HNSW_INDEX_SQL, Integer.class, tableName);
        if (hnswIndexes == null || hnswIndexes < 1) {
            throw new IllegalStateException("pgvector 表 " + qualifiedTableName
                + " 缺少 HNSW 余弦索引（vector_cosine_ops），向量检索会退化为精确扫描；"
                + "请执行 sql/表结构/迁移/S19-附加索引与向量维度-PostgresSql.sql。");
        }
    }
}
