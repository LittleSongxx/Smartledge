package org.smartledge.ai.model.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deployment settings only. Knowledge-base and stage policy remain with their existing providers. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.ai", ignoreUnknownFields = false)
public class AiModelProperties {
    private Chat chat = new Chat();
    private Embedding embedding = new Embedding();
    private Transport transport = new Transport();
    private Retry retry = new Retry();

    @Getter @Setter
    public static class Endpoint {
        private String baseUrl;
        private String path;
        private String apiKey;
        private String model;
    }
    @Getter @Setter
    public static class Chat extends Endpoint {
        private Integer maxTokens;
        private Double temperature;
        private Double topP;
        private Boolean thinking;
        private String reasoningEffort;
        private String verbosity;
        private String thinkingField = "thinking";
        private Map<String, Object> extensions = new LinkedHashMap<>();
    }
    @Getter @Setter
    public static class Embedding extends Endpoint {
        private int dimensions;

        /**
         * 向量化实现选择。
         *
         * <ul>
         *   <li>{@code openai-compatible}：调用本类 base-url/path 指向的远端兼容接口；</li>
         *   <li>{@code rag-tools}：调用本地 Python rag-tools 的 {@code /embed}（bge-m3 本地推理）。</li>
         * </ul>
         *
         * 默认保持远端行为，只有显式配置才改变来源，避免默认值静默切换向量空间。
         */
        private String provider = "openai-compatible";

        public boolean usesRagTools() {
            return "rag-tools".equalsIgnoreCase(provider == null ? "" : provider.trim());
        }
    }
    @Getter @Setter
    public static class Transport {
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(120);
        private Duration readIdleTimeout = Duration.ofSeconds(30);
        private int maxResponseBytes = 8 * 1024 * 1024;
        private int maxToolArgumentBytes = 64 * 1024;
        private int workerThreads = 8;
    }
    @Getter @Setter
    public static class Retry {
        private int chatMaxAttempts = 3;
        private Duration backoff = Duration.ofMillis(1200);
    }
}
