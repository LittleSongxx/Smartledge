package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRagParserDiagnosticVo {

    private Long documentId;

    private Long parseTaskId;

    private DocumentRagSnapshotVo.ParserTraceItem trace;
}
