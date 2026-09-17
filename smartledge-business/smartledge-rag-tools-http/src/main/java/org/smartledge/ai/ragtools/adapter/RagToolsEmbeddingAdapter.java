package org.smartledge.ai.ragtools.adapter;

import org.smartledge.ai.knowledge.indexing.port.EmbeddingPort;
import org.smartledge.ai.ragtools.client.RagToolsClient;
import org.smartledge.ai.ragtools.model.RagToolsEmbedRequest;
import org.smartledge.ai.ragtools.model.RagToolsEmbedResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 把索引侧拥有的 {@link EmbeddingPort} 映射到 Python rag-tools 的 {@code /embed} 协议。
 *
 * <p>本地推理与远端 embedding 接口是同一个端口的两种实现，因此本类<b>不能</b>标注
 * {@code @Component}：那会让容器里同时存在两个 {@code EmbeddingPort} bean，
 * 注入点就会歧义。它由 {@code AiModelConfiguration} 按 {@code app.ai.embedding.provider}
 * 选择后构造。</p>
 *
 * <p>维度是跨语言契约：服务端声明维度，这里严格校验，绝不因维度不符而写入不可比的向量。</p>
 */
public class RagToolsEmbeddingAdapter implements EmbeddingPort {

    private final RagToolsClient client;

    private final int expectedDimensions;

    private volatile String modelLabel;

    public RagToolsEmbeddingAdapter(RagToolsClient client, int expectedDimensions, String fallbackModelLabel) {
        if (client == null) {
            throw new IllegalArgumentException("RagTools embedding adapter requires a client");
        }
        if (expectedDimensions < 1) {
            throw new IllegalArgumentException("RagTools embedding adapter requires a positive expected dimension");
        }
        this.client = client;
        this.expectedDimensions = expectedDimensions;
        this.modelLabel = fallbackModelLabel == null || fallbackModelLabel.isBlank()
            ? "rag-tools"
            : fallbackModelLabel;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        RagToolsEmbedResponse response = client.embed(new RagToolsEmbedRequest(List.copyOf(texts)));
        return validate(response, texts.size());
    }

    @Override
    public String model() {
        return modelLabel;
    }

    private List<float[]> validate(RagToolsEmbedResponse response, int expectedCount) {
        if (response == null) {
            throw new IllegalStateException("Python 向量化接口返回为空。");
        }
        List<List<Float>> embeddings = response.getEmbeddings();
        if (embeddings == null || embeddings.size() != expectedCount) {
            throw new IllegalStateException("Python 向量化返回数量与请求不一致：请求 " + expectedCount
                + "，返回 " + (embeddings == null ? 0 : embeddings.size()));
        }
        if (response.getDimensions() != null && response.getDimensions() != expectedDimensions) {
            throw new IllegalStateException("Python 向量化维度与配置不一致：服务端 " + response.getDimensions()
                + "，期望 " + expectedDimensions);
        }
        List<float[]> vectors = new ArrayList<>(embeddings.size());
        for (int index = 0; index < embeddings.size(); index++) {
            List<Float> vector = embeddings.get(index);
            if (vector == null || vector.size() != expectedDimensions) {
                throw new IllegalStateException("Python 向量化第 " + index + " 条维度不合法："
                    + (vector == null ? "null" : vector.size()) + "，期望 " + expectedDimensions);
            }
            float[] converted = new float[vector.size()];
            for (int position = 0; position < vector.size(); position++) {
                Float value = vector.get(position);
                if (value == null) {
                    throw new IllegalStateException("Python 向量化第 " + index + " 条第 " + position + " 维为空。");
                }
                converted[position] = value;
            }
            vectors.add(converted);
        }
        // 服务端声明的模型名是权威标签，用于索引侧记录 embedding_model。
        if (response.getModel() != null && !response.getModel().isBlank()) {
            modelLabel = response.getModel();
        }
        return vectors;
    }
}
