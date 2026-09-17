package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Stable typed identity inherited from a previous final-evidence anchor. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalContextAnchor {

    private Long documentId;

    private String sectionPath;

    private Long structureNodeId;

    private Long parentBlockId;

    private Long chunkId;

    @Builder.Default
    private String source = "FINAL_EVIDENCE_ANCHOR";
}
