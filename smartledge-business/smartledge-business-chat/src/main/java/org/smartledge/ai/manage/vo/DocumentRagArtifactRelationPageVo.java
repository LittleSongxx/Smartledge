package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRagArtifactRelationPageVo {

    private Long documentId;

    private Long parseTaskId;

    private Long indexTaskId;

    private String nodeId;

    private String direction;

    private Integer pageNo;

    private Integer pageSize;

    private Long total;

    private List<RelationItem> records;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelationItem {

        private DocumentRagSnapshotVo.ArtifactGraphEdgeItem edge;

        private DocumentRagSnapshotVo.ArtifactGraphNodeItem node;
    }
}
