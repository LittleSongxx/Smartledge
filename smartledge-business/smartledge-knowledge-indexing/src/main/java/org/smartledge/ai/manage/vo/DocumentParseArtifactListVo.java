package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseArtifactListVo {

    private Long documentId;

    private Long taskId;

    private List<DocumentParseArtifactItemVo> artifacts;
}
