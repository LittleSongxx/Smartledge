package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRagPageOverlayIndexVo {

    private Long documentId;

    private Long parseTaskId;

    private Integer totalPages;

    private List<PageItem> pages;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageItem {

        private Integer pageNo;

        private String displayPageNo;

        private Long pageImageArtifactId;

        private String pageImageObjectName;

        private Boolean hasOverlay;
    }
}
