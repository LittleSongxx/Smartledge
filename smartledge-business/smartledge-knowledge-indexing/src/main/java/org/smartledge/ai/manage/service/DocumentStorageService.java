package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.support.StoredObjectInfo;
import org.smartledge.ai.manage.support.StoredObjectMetadata;

import java.util.List;

/**
 * @description: 服务层
 * @author: Song
 **/

public interface DocumentStorageService {

    StoredObjectInfo uploadOriginalFile(Long documentId, String originalFileName, byte[] bytes, String contentType);

    String uploadParsedText(Long documentId, String parsedText);

    String uploadParseArtifact(Long documentId, Long taskId, String fileName, byte[] bytes, String contentType);

    byte[] downloadObject(String objectName);

    String downloadText(String objectName);

    StoredObjectMetadata getObjectMetadata(String objectName);

    void deleteObjects(List<String> objectNameList);
}
