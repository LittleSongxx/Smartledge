package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRagArtifactNodePageVo {

    private Long documentId;

    private Long parseTaskId;

    private Long indexTaskId;

    private String nodeType;

    private Integer pageNo;

    private Integer pageSize;

    private Long total;

    private List<DocumentRagSnapshotVo.ArtifactGraphNodeItem> records;
}
