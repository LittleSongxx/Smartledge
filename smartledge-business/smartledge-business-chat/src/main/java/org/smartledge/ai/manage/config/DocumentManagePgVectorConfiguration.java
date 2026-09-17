package org.smartledge.ai.manage.config;

import cn.hutool.core.util.StrUtil;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.smartledge.ai.manage.support.PgVectorTenantOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @description: 配置类
 * @author: Song
 **/

@Configuration
@EnableConfigurationProperties(DocumentManageProperties.class)
@ConditionalOnProperty(prefix = "app.manage.pgvector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentManagePgVectorConfiguration {

    @Bean(name = "documentManagePgVectorJdbcSupport")
    public DocumentManagePgVectorJdbcSupport documentManagePgVectorJdbcSupport(DocumentManageProperties properties) {
        DocumentManageProperties.PgVector pg = properties.getPgVector();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setJdbcUrl(buildJdbcUrl(pg));
        dataSource.setUsername(pg.getUsername());
        dataSource.setPassword(pg.getPassword());
        dataSource.setPoolName(pg.getPoolName());
        dataSource.setMaximumPoolSize(pg.getMaximumPoolSize());
        dataSource.setMinimumIdle(pg.getMinimumIdle());
        return new DocumentManagePgVectorJdbcSupport(dataSource);
    }

    @Bean(name = "documentManagePgVectorJdbcTemplate")
    public JdbcTemplate documentManagePgVectorJdbcTemplate(
        @Qualifier("documentManagePgVectorJdbcSupport") DocumentManagePgVectorJdbcSupport jdbcSupport) {
        return jdbcSupport.getJdbcTemplate();
    }

    /**
     * 向量语句的租户作用域入口。
     *
     * <p>与 JdbcTemplate 在同一个配置类里声明，装配顺序确定：不能在组件扫描期用条件装配，
     * 那会依赖"数据源 Bean 已注册"的时序假设。</p>
     */
    @Bean
    public PgVectorTenantOperations pgVectorTenantOperations(
        @Qualifier("documentManagePgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        return new PgVectorTenantOperations(jdbcTemplate);
    }

    private String buildJdbcUrl(DocumentManageProperties.PgVector pg) {
        StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://")
            .append(pg.getHost())
            .append(":")
            .append(pg.getPort())
            .append("/")
            .append(pg.getDatabase())
            .append("?stringtype=unspecified");
        if (StrUtil.isNotBlank(pg.getSchema())) {
            jdbcUrl.append("&currentSchema=").append(pg.getSchema());
        }
        return jdbcUrl.toString();
    }

    public static class DocumentManagePgVectorJdbcSupport implements DisposableBean {

        private final HikariDataSource dataSource;

        private final JdbcTemplate jdbcTemplate;

        public DocumentManagePgVectorJdbcSupport(HikariDataSource dataSource) {
            this.dataSource = dataSource;
            this.jdbcTemplate = new JdbcTemplate(dataSource);
        }

        public JdbcTemplate getJdbcTemplate() {
            return jdbcTemplate;
        }

        @Override
        public void destroy() {
            dataSource.close();
        }
    }
}
