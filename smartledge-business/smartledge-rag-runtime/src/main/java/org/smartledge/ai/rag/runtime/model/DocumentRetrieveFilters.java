package org.smartledge.ai.rag.runtime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @description: 文档检索过滤提示
 * @author: Song
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRetrieveFilters {

    @Builder.Default
    private List<String> documentNameHints = new ArrayList<>();

    @Builder.Default
    private List<String> sectionPathHints = new ArrayList<>();

    @Builder.Default
    private List<String> canonicalPathHints = new ArrayList<>();

    @Builder.Default
    private List<Long> structureNodeIdHints = new ArrayList<>();

    @Builder.Default
    private List<Integer> itemIndexHints = new ArrayList<>();

    @Builder.Default
    private List<String> yearHints = new ArrayList<>();

    @Builder.Default
    private java.util.Map<String, Object> userMetadataEquals = new java.util.LinkedHashMap<>();

    public boolean isEmpty() {
        return documentNameHints.isEmpty()
            && sectionPathHints.isEmpty()
            && canonicalPathHints.isEmpty()
            && structureNodeIdHints.isEmpty()
            && itemIndexHints.isEmpty()
            && yearHints.isEmpty()
            && userMetadataEquals.isEmpty();
    }
}
