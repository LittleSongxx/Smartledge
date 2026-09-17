package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRagPageOverlayDetailVo {

    private Long documentId;

    private Long parseTaskId;

    private DocumentRagSnapshotVo.PageOverlayItem page;
}
