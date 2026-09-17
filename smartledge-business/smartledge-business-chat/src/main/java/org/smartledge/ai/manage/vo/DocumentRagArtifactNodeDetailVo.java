package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRagArtifactNodeDetailVo {

    private Long documentId;

    private Long parseTaskId;

    private Long indexTaskId;

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem node;

    private String content;

    private List<AttributeItem> attributes;

    private PresentationItem presentation;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttributeItem {

        private String label;

        private String value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PresentationItem {

        private String kind;

        private String scopeLabel;

        private List<String> keywords;

        private List<String> questions;

        private List<RelatedGroupItem> relatedGroups;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelatedGroupItem {

        private String key;

        private String label;

        private String description;

        private Integer totalCount;

        private List<DocumentRagSnapshotVo.ArtifactGraphNodeItem> nodes;
    }
}
