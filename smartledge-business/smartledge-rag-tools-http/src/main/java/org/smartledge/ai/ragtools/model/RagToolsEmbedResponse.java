package org.smartledge.ai.ragtools.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Python 侧向量化响应。模型名与维度由服务端声明，调用方只做校验。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagToolsEmbedResponse {

    private String model;

    private Integer dimensions;

    private List<List<Float>> embeddings;
}
