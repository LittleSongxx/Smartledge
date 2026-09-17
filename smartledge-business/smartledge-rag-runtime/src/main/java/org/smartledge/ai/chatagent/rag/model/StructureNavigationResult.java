package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.smartledge.ai.rag.runtime.model.DocumentStructureNode;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructureNavigationResult {

    private Long documentId;

    private Long parseTaskId;

    private Long anchorNodeId;

    private DocumentStructureNode current;

    private DocumentStructureNode parent;

    private DocumentStructureNode previousSibling;

    private DocumentStructureNode nextSibling;

    @Builder.Default
    private List<DocumentStructureNode> directChildren = new ArrayList<>();

    private boolean deterministic;

    private String missReason;
}
