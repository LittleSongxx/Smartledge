package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 受控 GraphRAG 意图，来自 QueryUnderstanding advisor 建议。
 *
 * <p>P1 仅承载建议，不参与通道硬判；GraphRAG query profile、context/citation 分离在 P7 落地。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphIntent {

    private boolean requested;

    @Builder.Default
    private List<String> entities = new ArrayList<>();

    @Builder.Default
    private List<String> targetEntities = new ArrayList<>();

    private int maxHops;

    private String source;
}
