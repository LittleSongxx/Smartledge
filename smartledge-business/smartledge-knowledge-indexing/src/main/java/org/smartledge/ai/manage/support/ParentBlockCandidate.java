package org.smartledge.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @description: 支撑组件
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParentBlockCandidate {

    private String sectionPath;

    private Long structureNodeId;

    private Integer structureNodeType;

    private String canonicalPath;

    private Integer itemIndex;

    private String text;

    private Integer sourceType;

    private List<ChunkCandidate> childChunks = new ArrayList<>();

    private String pageRange;

    private String sourceBlockIds;

    private ChunkSourceProvenance sourceProvenance;

    public ParentBlockCandidate(String sectionPath, Long structureNodeId, Integer structureNodeType,
                                 String canonicalPath, Integer itemIndex, String text, Integer sourceType,
                                 List<ChunkCandidate> childChunks, String pageRange, String sourceBlockIds) {
        this(sectionPath, structureNodeId, structureNodeType, canonicalPath, itemIndex, text, sourceType,
            childChunks, pageRange, sourceBlockIds, null);
    }

    public ParentBlockCandidate(String sectionPath,
                                Long structureNodeId,
                                Integer structureNodeType,
                                String canonicalPath,
                                Integer itemIndex,
                                String text,
                                Integer sourceType,
                                List<ChunkCandidate> childChunks) {
        this(sectionPath, structureNodeId, structureNodeType, canonicalPath, itemIndex, text, sourceType, childChunks, null, null);
    }

    public ParentBlockCandidate(String sectionPath,
                                String text,
                                Integer sourceType,
                                List<ChunkCandidate> childChunks) {
        this(sectionPath, null, null, "", null, text, sourceType, childChunks, null, null);
    }
}
