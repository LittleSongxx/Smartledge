package org.smartledge.ai.ragtools.adapter;

import org.smartledge.ai.ragtools.client.RagToolsClient;
import org.smartledge.ai.ragtools.model.RagToolsEmbedRequest;
import org.smartledge.ai.ragtools.model.RagToolsEmbedResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本地向量化适配器的不变量测试。
 *
 * <p>对应 S20：Java 的 EmbeddingPort 增加本地实现。维度是跨语言契约——
 * Java 严格校验、pgvector 列已归一化为 vector(1024)——因此维度不符必须失败，
 * 绝不能写入不可比的向量。</p>
 */
@ExtendWith(MockitoExtension.class)
class RagToolsEmbeddingAdapterTest {

    private static final int DIMENSIONS = 1024;

    @Mock
    private RagToolsClient client;

    private RagToolsEmbeddingAdapter adapter() {
        return new RagToolsEmbeddingAdapter(client, DIMENSIONS, "BAAI/bge-m3");
    }

    private static List<Float> vector(float value, int dimensions) {
        List<Float> vector = new ArrayList<>(dimensions);
        for (int index = 0; index < dimensions; index++) {
            vector.add(value);
        }
        return vector;
    }

    @Test
    @DisplayName("按输入顺序返回向量，并用服务端声明的模型名更新标签")
    void returnsOrderedVectorsAndTakesServiceDeclaredModel() {
        when(client.embed(any())).thenReturn(new RagToolsEmbedResponse("BAAI/bge-m3", DIMENSIONS,
            List.of(vector(0.5f, DIMENSIONS), vector(0.25f, DIMENSIONS))));
        RagToolsEmbeddingAdapter adapter = adapter();

        List<float[]> vectors = adapter.embed(List.of("甲", "乙"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).hasSize(DIMENSIONS);
        assertThat(vectors.get(0)[0]).isEqualTo(0.5f);
        assertThat(vectors.get(1)[0]).isEqualTo(0.25f);
        assertThat(adapter.model()).isEqualTo("BAAI/bge-m3");

        ArgumentCaptor<RagToolsEmbedRequest> captor = ArgumentCaptor.forClass(RagToolsEmbedRequest.class);
        verify(client).embed(captor.capture());
        assertThat(captor.getValue().getTexts()).containsExactly("甲", "乙");
    }

    @Test
    @DisplayName("空输入不发起调用")
    void emptyInputSkipsTransport() {
        assertThat(adapter().embed(List.of())).isEmpty();

        verify(client, never()).embed(any());
    }

    @Test
    @DisplayName("服务端声明的维度与配置不一致时失败")
    void declaredDimensionMismatchFails() {
        when(client.embed(any())).thenReturn(new RagToolsEmbedResponse("m", 512,
            List.of(vector(0.5f, 512))));

        assertThatThrownBy(() -> adapter().embed(List.of("甲")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("维度");
    }

    @Test
    @DisplayName("返回数量与请求不一致时失败")
    void countMismatchFails() {
        when(client.embed(any())).thenReturn(new RagToolsEmbedResponse("m", DIMENSIONS,
            List.of(vector(0.5f, DIMENSIONS))));

        assertThatThrownBy(() -> adapter().embed(List.of("甲", "乙")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("数量");
    }

    @Test
    @DisplayName("单条向量维度不足时失败")
    void shortVectorFails() {
        when(client.embed(any())).thenReturn(new RagToolsEmbedResponse("m", DIMENSIONS,
            List.of(vector(0.5f, DIMENSIONS), vector(0.5f, 8))));

        assertThatThrownBy(() -> adapter().embed(List.of("甲", "乙")))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("空响应与空向量列表都判为失败，不产生部分结果")
    void nullAndEmptyResponsesFail() {
        when(client.embed(any())).thenReturn(null);
        assertThatThrownBy(() -> adapter().embed(List.of("甲"))).isInstanceOf(IllegalStateException.class);

        when(client.embed(any())).thenReturn(new RagToolsEmbedResponse("m", DIMENSIONS, null));
        assertThatThrownBy(() -> adapter().embed(List.of("甲"))).isInstanceOf(IllegalStateException.class);

        when(client.embed(any())).thenReturn(new RagToolsEmbedResponse("m", DIMENSIONS, Collections.emptyList()));
        assertThatThrownBy(() -> adapter().embed(List.of("甲"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("响应缺少维度声明时仍以配置维度校验每条向量")
    void missingDeclaredDimensionStillValidatesVectors() {
        when(client.embed(any())).thenReturn(new RagToolsEmbedResponse("BAAI/bge-m3", null,
            List.of(vector(0.5f, DIMENSIONS))));

        List<float[]> vectors = adapter().embed(List.of("甲"));

        assertThat(vectors.get(0)).hasSize(DIMENSIONS);
    }

    @Test
    @DisplayName("构造参数不合法时立即失败")
    void invalidConstructionFails() {
        assertThatThrownBy(() -> new RagToolsEmbeddingAdapter(null, DIMENSIONS, "m"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagToolsEmbeddingAdapter(client, 0, "m"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
