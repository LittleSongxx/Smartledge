package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** A single sub-question and the exact query submitted to retrieval. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalExecutionQuery {

    private int index;

    private String sourceQuestion;

    private String normalizedQuery;

    private String executionQuery;

    @Builder.Default
    private List<String> contextHints = new ArrayList<>();
}
