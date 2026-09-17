package org.smartledge.ai.manage.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * @description: 配置类
 * @author: Song
 **/

@Configuration
@ConditionalOnProperty(prefix = "app.manage.neo4j", name = "enabled", havingValue = "true")
public class DocumentManageNeo4jConfiguration {

    @Bean(destroyMethod = "close")
    public Driver documentManageNeo4jDriver(DocumentManageProperties properties) {
        DocumentManageProperties.Neo4j neo4j = properties.getNeo4j();
        // 这里只设置连接超时。语句级超时由 Neo4jDocumentStructureGraphService 通过
        // TransactionConfig 施加，此前把查询超时绑到连接超时是错误映射，Cypher 实际没有上限。
        Config config = Config.builder()
            .withConnectionTimeout(neo4j.getConnectionTimeoutSeconds(), TimeUnit.SECONDS)
            .build();
        return GraphDatabase.driver(
            neo4j.getUri(),
            AuthTokens.basic(neo4j.getUsername(), neo4j.getPassword()),
            config
        );
    }
}
