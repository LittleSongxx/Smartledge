package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 受控表格意图，来自 QueryUnderstanding advisor 建议。
 *
 * <p>P1 仅承载建议，不参与通道硬判；结构化表格 query plan 与 schema 校验在 P9 落地。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableIntent {

    private boolean requested;

    @Builder.Default
    private List<String> tableOps = new ArrayList<>();

    private String source;
}
