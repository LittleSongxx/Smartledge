package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Controlled metadata filters extracted once by the plan assembler. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalMetadataFilters {

    @Builder.Default
    private List<String> documentNameHints = new ArrayList<>();

    @Builder.Default
    private List<String> sectionPathHints = new ArrayList<>();

    @Builder.Default
    private List<String> yearHints = new ArrayList<>();

    @Builder.Default
    private List<String> entityHints = new ArrayList<>();
}
