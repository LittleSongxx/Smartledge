package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.dto.DocumentRagPageOverlayDetailQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagParseWorkbenchQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagSnapshotQueryDto;
import org.smartledge.ai.manage.vo.DocumentRagPageOverlayDetailVo;
import org.smartledge.ai.manage.vo.DocumentRagPageOverlayIndexVo;
import org.smartledge.ai.manage.vo.DocumentRagParserDiagnosticVo;
import org.smartledge.ai.manage.vo.DocumentRagSnapshotVo;

public interface DocumentRagSnapshotService {

    DocumentRagSnapshotVo querySnapshot(DocumentRagSnapshotQueryDto dto);

    DocumentRagParserDiagnosticVo queryParserDiagnostic(DocumentRagParseWorkbenchQueryDto dto);

    DocumentRagPageOverlayIndexVo queryPageOverlayIndex(DocumentRagParseWorkbenchQueryDto dto);

    DocumentRagPageOverlayDetailVo queryPageOverlayDetail(DocumentRagPageOverlayDetailQueryDto dto);
}
