package org.smartledge.ai.model.config;

import org.smartledge.ai.knowledge.indexing.port.EmbeddingPort;
import org.smartledge.ai.model.http.ModelHttpClient;
import org.smartledge.ai.ragtools.adapter.RagToolsEmbeddingAdapter;
import org.smartledge.ai.ragtools.client.RagToolsClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 向量化实现的装配权威测试。
 *
 * <p>对应 S20：本地推理与远端接口是同一个 EmbeddingPort 的两种实现，
 * 必须按配置恰好产出一个 bean；默认必须保持远端行为，不能因为新增本地实现
 * 就静默切换向量空间。</p>
 */
@ExtendWith(MockitoExtension.class)
class AiModelConfigurationEmbeddingSelectionTest {

    @Mock
    private ModelHttpClient modelHttpClient;

    @Mock
    private ObjectProvider<RagToolsClient> ragToolsClientProvider;

    private AiModelConfiguration configuration() {
        return new AiModelConfiguration();
    }

    private AiModelProperties propertiesWithProvider(String provider) {
        AiModelProperties properties = new AiModelProperties();
        properties.getEmbedding().setProvider(provider);
        properties.getEmbedding().setDimensions(1024);
        properties.getEmbedding().setModel("BAAI/bge-m3");
        return properties;
    }

    @Test
    @DisplayName("provider=rag-tools 时装配本地适配器，标签取自配置的模型名")
    void ragToolsProviderSelectsLocalAdapter() {
        when(ragToolsClientProvider.getIfAvailable()).thenReturn(org.mockito.Mockito.mock(RagToolsClient.class));

        EmbeddingPort port = configuration().embeddingPort(propertiesWithProvider("rag-tools"),
            modelHttpClient, ragToolsClientProvider);

        assertThat(port).isInstanceOf(RagToolsEmbeddingAdapter.class);
        assertThat(port.model()).isEqualTo("BAAI/bge-m3");
    }

    @Test
    @DisplayName("默认 provider 保持远端实现，不静默切换向量空间")
    void defaultProviderKeepsRemoteEmbedding() {
        AiModelProperties properties = new AiModelProperties();
        assertThat(properties.getEmbedding().getProvider()).isEqualTo("openai-compatible");
        when(modelHttpClient.embedding()).thenReturn(new EmbeddingPort() {
            @Override public java.util.List<float[]> embed(java.util.List<String> texts) { return java.util.List.of(); }
            @Override public String model() { return "text-embedding-v4"; }
        });

        EmbeddingPort port = configuration().embeddingPort(properties, modelHttpClient, ragToolsClientProvider);

        assertThat(port).isNotInstanceOf(RagToolsEmbeddingAdapter.class);
        assertThat(port.model()).isEqualTo("text-embedding-v4");
    }

    @Test
    @DisplayName("provider 值大小写与空白不敏感")
    void providerMatchingIsLenient() {
        when(ragToolsClientProvider.getIfAvailable()).thenReturn(org.mockito.Mockito.mock(RagToolsClient.class));

        assertThat(configuration().embeddingPort(propertiesWithProvider("  RAG-TOOLS  "),
            modelHttpClient, ragToolsClientProvider)).isInstanceOf(RagToolsEmbeddingAdapter.class);
    }

    @Test
    @DisplayName("provider=rag-tools 但客户端缺失时立即失败，不退回远端")
    void missingClientFailsInsteadOfFallingBack() {
        when(ragToolsClientProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> configuration().embeddingPort(propertiesWithProvider("rag-tools"),
            modelHttpClient, ragToolsClientProvider))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("rag-tools");
    }
}
