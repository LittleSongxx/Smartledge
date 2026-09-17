package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructureNavigationIntent {

    @Builder.Default
    private List<StructureNavigationOperation> operations = new ArrayList<>();

    private Long anchorStructureNodeId;

    private String anchorSectionPath;

    private String anchorCanonicalPath;

    @Builder.Default
    private List<String> sectionAnchors = new ArrayList<>();

    private double confidence;

    private String source;
}
