package org.smartledge.ai.model.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiSdkTransportTest {

    @Test
    @DisplayName("SDK baseUrl 去掉 chat/completions 与 embeddings 路径")
    void stripsOwnedPaths() {
        assertThat(OpenAiSdkTransport.sdkBaseUrl(URI.create(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")))
            .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(OpenAiSdkTransport.sdkBaseUrl(URI.create(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings")))
            .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
    }
}
