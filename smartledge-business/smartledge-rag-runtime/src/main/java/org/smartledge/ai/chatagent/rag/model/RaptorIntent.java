package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 受控 RAPTOR 意图，来自 QueryUnderstanding advisor 建议。
 *
 * <p>P1 仅承载建议，不参与通道硬判；RAPTOR summary/source 分离在 P8 落地。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RaptorIntent {

    private boolean requested;

    private boolean summaryRequested;

    private int sourceChunkTopK;

    private String source;
}
