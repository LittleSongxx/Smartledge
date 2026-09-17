package org.smartledge.ai.model.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.knowledge.indexing.port.EmbeddingPort;
import org.smartledge.ai.model.http.ModelHttpClient;
import org.smartledge.ai.model.http.ModelHttpSettings;
import org.smartledge.ai.rag.runtime.model.ChatCallOptions;
import org.smartledge.ai.rag.runtime.observe.OtelGenAiChatModelPort;
import org.smartledge.ai.rag.runtime.port.ChatModelPort;
import org.smartledge.ai.ragtools.adapter.RagToolsEmbeddingAdapter;
import org.smartledge.ai.ragtools.client.RagToolsClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiModelProperties.class)
public class AiModelConfiguration {
    @Bean(destroyMethod = "close")
    ModelHttpClient modelHttpClient(AiModelProperties properties, ObjectMapper mapper) {
        var chat = properties.getChat();
        var transport = properties.getTransport();
        var retry = properties.getRetry();
        return new ModelHttpClient(new ModelHttpSettings(endpoint(chat), endpoint(properties.getEmbedding()),
            ChatCallOptions.builder().maxTokens(chat.getMaxTokens()).temperature(chat.getTemperature())
                .topP(chat.getTopP()).thinking(chat.getThinking()).reasoningEffort(chat.getReasoningEffort())
                .verbosity(chat.getVerbosity()).build(),
            chat.getExtensions(), chat.getThinkingField(), properties.getEmbedding().getDimensions(),
            transport.getConnectTimeout(), transport.getRequestTimeout(), transport.getReadIdleTimeout(),
            transport.getMaxResponseBytes(), transport.getMaxToolArgumentBytes(), retry.getChatMaxAttempts(),
            retry.getEmbeddingMaxAttempts(), retry.getBackoff(), transport.getWorkerThreads()), mapper);
    }
    @Bean ChatModelPort chatModelPort(ModelHttpClient client) {
        return new OtelGenAiChatModelPort(client.chat());
    }

    /**
     * 向量化实现。
     *
     * <p>本地推理与远端接口是同一个 {@link EmbeddingPort} 的两种实现，这里按配置恰好产出一个 bean。
     * 适配器刻意不标注 {@code @Component}，否则容器里会同时存在两个 EmbeddingPort 而让注入点歧义。</p>
     */
    @Bean EmbeddingPort embeddingPort(AiModelProperties properties, ModelHttpClient client,
                                     ObjectProvider<RagToolsClient> ragToolsClientProvider) {
        var embedding = properties.getEmbedding();
        if (!embedding.usesRagTools()) {
            return client.embedding();
        }
        RagToolsClient ragToolsClient = ragToolsClientProvider.getIfAvailable();
        if (ragToolsClient == null) {
            throw new IllegalStateException(
                "app.ai.embedding.provider=rag-tools 需要 smartledge-rag-tools-http 的 RagToolsClient");
        }
        return new RagToolsEmbeddingAdapter(ragToolsClient, embedding.getDimensions(), embedding.getModel());
    }

    private ModelHttpSettings.Endpoint endpoint(AiModelProperties.Endpoint endpoint) {
        if (endpoint.getBaseUrl() == null || endpoint.getPath() == null || endpoint.getPath().isBlank()) {
            throw new IllegalArgumentException("app.ai endpoint base-url and path are required");
        }
        // Append the configured path; preserve a provider prefix such as /compatible-mode/.
        String base = endpoint.getBaseUrl().replaceAll("/+$", "");
        String path = endpoint.getPath().replaceAll("^/+", "");
        return new ModelHttpSettings.Endpoint(URI.create(base + "/" + path), endpoint.getApiKey(), endpoint.getModel());
    }
}
