package org.smartledge.ai.manage.service;

import org.smartledge.enums.DocumentFileTypeEnum;
import org.smartledge.ai.manage.support.DocumentAnalysisResult;

/**
 * @description: 服务层
 * @author: Song
 **/

public interface DocumentParserService {

    DocumentAnalysisResult parse(byte[] bytes, String originalFileName, String mimeType, DocumentFileTypeEnum fileType);
}
