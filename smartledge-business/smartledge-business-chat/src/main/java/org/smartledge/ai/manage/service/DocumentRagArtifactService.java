package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.dto.DocumentRagArtifactNodeDetailQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagArtifactNodePageQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagArtifactGraphWindowQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagArtifactRelationPageQueryDto;
import org.smartledge.ai.manage.dto.DocumentRagArtifactTableWindowQueryDto;
import org.smartledge.ai.manage.vo.DocumentRagArtifactNodeDetailVo;
import org.smartledge.ai.manage.vo.DocumentRagArtifactNodePageVo;
import org.smartledge.ai.manage.vo.DocumentRagArtifactGraphWindowVo;
import org.smartledge.ai.manage.vo.DocumentRagArtifactRelationPageVo;
import org.smartledge.ai.manage.vo.DocumentRagArtifactTableWindowVo;

public interface DocumentRagArtifactService {

    DocumentRagArtifactNodePageVo queryNodePage(DocumentRagArtifactNodePageQueryDto dto);

    DocumentRagArtifactNodeDetailVo queryNodeDetail(DocumentRagArtifactNodeDetailQueryDto dto);

    DocumentRagArtifactGraphWindowVo queryGraphWindow(DocumentRagArtifactGraphWindowQueryDto dto);

    DocumentRagArtifactRelationPageVo queryRelationPage(DocumentRagArtifactRelationPageQueryDto dto);

    DocumentRagArtifactTableWindowVo queryTableWindow(DocumentRagArtifactTableWindowQueryDto dto);
}
