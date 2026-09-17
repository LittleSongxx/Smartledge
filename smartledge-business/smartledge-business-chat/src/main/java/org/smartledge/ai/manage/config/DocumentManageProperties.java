package org.smartledge.ai.manage.config;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.AccessLevel;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * @description: 配置属性
 * @author: Song
 **/

@Data
@ConfigurationProperties(prefix = "app.manage")
public class DocumentManageProperties {

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Autowired(required = false)
    private SystemConfigProvider systemConfigProvider;

    private Minio minio = new Minio();

    private Messaging messaging = new Messaging();

    private Chunk chunk = new Chunk();

    private IndexBuild indexBuild = new IndexBuild();

    public Chunk getChunk() {
        return systemConfigProvider == null ? chunk : systemConfigProvider.currentSnapshot().getChunk();
    }

    public IndexBuild getIndexBuild() {
        return systemConfigProvider == null ? indexBuild : systemConfigProvider.currentSnapshot().getIndexBuild();
    }

    private StructureParsing structureParsing = new StructureParsing();

    private PgVector pgVector = new PgVector();

    private Elasticsearch elasticsearch = new Elasticsearch();

    private Neo4j neo4j = new Neo4j();

    @Data
    public static class Minio {
        private String endpoint = "http://127.0.0.1:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucketName = "smartledge-document";
        private String objectPrefix = "rag/document";
        private String parsedTextPrefix = "rag/parsed-text";
        private String parseArtifactPrefix = "rag/parse-artifact";
    }

    /**
     * 文档索引链路的资源基础名。
     *
     * <p>这里<b>不含</b>资源前缀：前缀由 {@code DocumentMessagingTopology} 统一拼接一次。
     * 旧实现把已带前缀的名字放在这里，生产者与消费者又各加一次前缀，导致声明的资源名和实际使用的资源名不一致。</p>
     */
    @Data
    public static class Messaging {
        private String parseRouteQueue = "document-parse-route";
        private String indexBuildQueue = "document-index-build";
    }

    @Data
    public static class Chunk {
        private Integer qaMaxInputBytes = 8192;
        private Integer recursiveMaxChars = 800;
        private Integer recursiveOverlapChars = 120;
        private Integer semanticMaxChars = 700;
        private Integer semanticMinChars = 240;
        private Double semanticSimilarityThreshold = 0.18D;
        private Integer parentBlockMaxChars = 2200;
        private Integer parentBlockOverlapChars = 180;
        private Integer parentSemanticMaxChars = 1600;
        private Integer parentSemanticMinChars = 480;
        private Boolean llmEnabled = Boolean.TRUE;
        private Integer llmMaxChars = 3500;
        private Boolean recommendLlmWhenLowQuality = Boolean.TRUE;
    }

    @Data
    public static class IndexBuild {

        private Integer embeddingBatchSize = 5;

        private Integer embeddingParallelism = 1;

        private Integer embeddingBatchMaxAttempts = 3;

        private Long embeddingBatchRetryBackoffMillis = 1200L;

        private Boolean elasticsearchRefreshWait = Boolean.TRUE;

        private Integer progressLogLimit = 60;

        private Integer executorPoolSize = 2;

        private Integer executorQueueCapacity = 32;
    }

    @Data
    public static class StructureParsing {

        private Boolean llmDisambiguationEnabled = Boolean.TRUE;

        private Integer maxAmbiguousSignalsPerCall = 8;

        private Integer contextWindowLines = 2;

        private Integer maxPlainHeadingChars = 32;

        private Double ambiguityConfidenceFloor = 0.45D;

        private Double ambiguityConfidenceCeil = 0.80D;
    }

    @Data
    public static class PgVector {

        private Boolean enabled = Boolean.TRUE;

        private String host = "127.0.0.1";

        private Integer port = 5432;

        private String database = "smartledge_pgvector";

        private String schema = "public";

        private String username = "postgres";

        private String password = "postgres";

        private String poolName = "smartledge-manage-pgvector-hikari";

        private Integer maximumPoolSize = 5;

        private Integer minimumIdle = 1;
    }

    @Data
    public static class Elasticsearch {

        private Boolean enabled = Boolean.TRUE;

        private List<String> uris = new ArrayList<>(List.of("http://127.0.0.1:9200"));

        private String username = "elastic";

        private String password = "elastic";

        private String indexName = "smartledge-document-keyword";

        private String analyzer = "ik_max_word";

        private String searchAnalyzer = "ik_smart";

        private String navigationIndexName = "smartledge-document-navigation";

        private String routeIndexName = "smartledge-knowledge-route";

        private String raptorSummaryIndexName = "smartledge-raptor-summary";

        private Integer connectTimeoutMillis = 3000;

        private Integer socketTimeoutMillis = 5000;
    }

    @Data
    public static class Neo4j {

        private Boolean enabled = Boolean.FALSE;

        private String uri = "bolt://127.0.0.1:7687";

        private String username = "neo4j";

        private String password = "12345678";

        private String database = "neo4j";

        /**
         * 单条 Cypher 语句的事务超时时间，单位：秒。
         *
         * <p>此前该值被错误地绑定到驱动连接超时，导致 Cypher 语句实际上没有超时上限，
         * 一条慢查询会一直占住检索线程。</p>
         */
        private Integer queryTimeoutSeconds = 5;

        /** 建立 Neo4j 连接的超时时间，单位：秒。 */
        private Integer connectionTimeoutSeconds = 10;
    }
}
