package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.dto.DocumentIndexBuildDto;
import org.smartledge.ai.manage.dto.DocumentChunkQueryDto;
import org.smartledge.ai.manage.dto.DocumentChunkDetailQueryDto;
import org.smartledge.ai.manage.dto.DocumentDetailQueryDto;
import org.smartledge.ai.manage.dto.DocumentDeleteDto;
import org.smartledge.ai.manage.dto.DocumentPageQueryDto;
import org.smartledge.ai.manage.dto.DocumentIndexBuildProgressQueryDto;
import org.smartledge.ai.manage.dto.DocumentParseArtifactContentQueryDto;
import org.smartledge.ai.manage.dto.DocumentParseArtifactQueryDto;
import org.smartledge.ai.manage.dto.DocumentParseRouteProgressQueryDto;
import org.smartledge.ai.manage.dto.DocumentStrategyConfirmDto;
import org.smartledge.ai.manage.dto.DocumentStrategyPlanQueryDto;
import org.smartledge.ai.manage.dto.DocumentTaskLogQueryDto;
import org.smartledge.ai.manage.dto.DocumentUploadDto;
import org.smartledge.ai.manage.vo.DocumentIndexBuildVo;
import org.smartledge.ai.manage.vo.DocumentChunkQueryVo;
import org.smartledge.ai.manage.vo.DocumentChunkDetailVo;
import org.smartledge.ai.manage.vo.DocumentDeleteVo;
import org.smartledge.ai.manage.vo.DocumentListItemVo;
import org.smartledge.ai.manage.vo.DocumentPageQueryVo;
import org.smartledge.ai.manage.vo.DocumentIndexBuildProgressVo;
import org.smartledge.ai.manage.vo.DocumentParseArtifactContentVo;
import org.smartledge.ai.manage.vo.DocumentParseArtifactDownloadVo;
import org.smartledge.ai.manage.vo.DocumentParseArtifactListVo;
import org.smartledge.ai.manage.vo.DocumentParseRouteProgressVo;
import org.smartledge.ai.manage.vo.DocumentStrategyConfirmVo;
import org.smartledge.ai.manage.vo.DocumentStrategyPlanQueryVo;
import org.smartledge.ai.manage.vo.DocumentTaskLogQueryVo;
import org.smartledge.ai.manage.vo.DocumentUploadVo;
import org.springframework.web.multipart.MultipartFile;

/**
 * @description: 服务层
 * @author: Song
 **/

public interface DocumentManageService {

    DocumentUploadVo upload(MultipartFile file, DocumentUploadDto dto);

    DocumentPageQueryVo queryDocumentPage(DocumentPageQueryDto dto);

    DocumentListItemVo queryDocumentDetail(DocumentDetailQueryDto dto);

    DocumentDeleteVo deleteDocument(DocumentDeleteDto dto);

    DocumentStrategyPlanQueryVo queryStrategyPlan(DocumentStrategyPlanQueryDto dto);

    DocumentStrategyConfirmVo confirmStrategy(DocumentStrategyConfirmDto dto);

    DocumentIndexBuildVo buildIndex(DocumentIndexBuildDto dto);

    DocumentIndexBuildProgressVo queryIndexBuildProgress(DocumentIndexBuildProgressQueryDto dto);

    DocumentParseRouteProgressVo queryParseRouteProgress(DocumentParseRouteProgressQueryDto dto);

    DocumentParseArtifactListVo queryParseArtifacts(DocumentParseArtifactQueryDto dto);

    DocumentParseArtifactContentVo queryParseArtifactContent(DocumentParseArtifactContentQueryDto dto);

    DocumentParseArtifactDownloadVo downloadParseArtifact(DocumentParseArtifactContentQueryDto dto);

    DocumentChunkQueryVo queryDocumentChunks(DocumentChunkQueryDto dto);

    DocumentChunkDetailVo queryDocumentChunkDetail(DocumentChunkDetailQueryDto dto);

    DocumentTaskLogQueryVo queryTaskLogs(DocumentTaskLogQueryDto dto);
}
