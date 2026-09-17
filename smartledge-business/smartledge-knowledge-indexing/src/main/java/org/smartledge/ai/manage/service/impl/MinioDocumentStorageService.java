package org.smartledge.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.smartledge.ai.knowledge.indexing.port.DocumentStorageConfigurationPort;
import org.smartledge.ai.manage.service.DocumentStorageService;
import org.smartledge.ai.manage.support.StoredObjectInfo;
import org.smartledge.ai.manage.support.StoredObjectMetadata;
import org.smartledge.enums.DocumentManageCode;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @description: 服务实现层
 * @author: Song
 **/

@AllArgsConstructor
@Service
public class MinioDocumentStorageService implements DocumentStorageService {

    private final MinioClient minioClient;

    private final DocumentStorageConfigurationPort storageConfiguration;

    @Override
    public StoredObjectInfo uploadOriginalFile(Long documentId, String originalFileName, byte[] bytes, String contentType) {

        // originalFileName 仍然单独保存到文档记录，用于展示和解析器识别；这里清洗的是 MinIO 对象键的最后一段。
        // 把路径分隔符替换掉，避免客户端传入带路径的文件名后改变对象键的层级结构。
        String safeFileName = StrUtil.blankToDefault(originalFileName, "document.bin")
            .replace("/", "-")
            .replace("\\", "-");
        String objectName = storageConfiguration.objectPrefix()
            + "/" + documentId
            + "/" + System.currentTimeMillis()
            + "-" + safeFileName;
        upload(objectName, bytes, contentType);
        return new StoredObjectInfo(storageConfiguration.bucketName(), objectName, buildObjectUrl(objectName));
    }

    @Override
    public String uploadParsedText(Long documentId, String parsedText) {

        String objectName = storageConfiguration.parsedTextPrefix() + "/" + documentId + ".txt";
        upload(objectName, parsedText.getBytes(StandardCharsets.UTF_8), "text/plain;charset=UTF-8");
        return objectName;
    }

    @Override
    public String uploadParseArtifact(Long documentId, Long taskId, String fileName, byte[] bytes, String contentType) {
        String safeFileName = StrUtil.blankToDefault(fileName, "artifact.bin")
            .replace("/", "-")
            .replace("\\", "-");
        String objectName = storageConfiguration.parseArtifactPrefix()
            + "/" + documentId
            + "/" + taskId
            + "/" + System.currentTimeMillis()
            + "-" + safeFileName;
        upload(objectName, bytes == null ? new byte[0] : bytes, contentType);
        return objectName;
    }

    @Override
    public byte[] downloadObject(String objectName) {
        try (InputStream inputStream = minioClient.getObject(
            GetObjectArgs.builder()
                .bucket(storageConfiguration.bucketName())
                .object(objectName)
                .build())) {

            return inputStream.readAllBytes();
        }
        catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STORAGE_FAILED.getCode(),
                "下载 MinIO 文件失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public String downloadText(String objectName) {

        return new String(downloadObject(objectName), StandardCharsets.UTF_8);
    }

    @Override
    public StoredObjectMetadata getObjectMetadata(String objectName) {
        try {
            StatObjectResponse response = minioClient.statObject(
                StatObjectArgs.builder()
                    .bucket(storageConfiguration.bucketName())
                    .object(objectName)
                    .build());
            return new StoredObjectMetadata(objectName, response.size(), response.contentType());
        }
        catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STORAGE_FAILED.getCode(),
                "读取 MinIO 文件元数据失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public void deleteObjects(List<String> objectNameList) {
        if (CollUtil.isEmpty(objectNameList)) {
            return;
        }

        List<String> validObjectNameList = objectNameList.stream()
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .distinct()
            .toList();
        if (validObjectNameList.isEmpty()) {
            return;
        }

        try {

            if (!bucketExists()) {
                return;
            }

            for (String objectName : validObjectNameList) {
                minioClient.removeObject(
                    RemoveObjectArgs.builder()
                        .bucket(storageConfiguration.bucketName())
                        .object(objectName)
                        .build()
                );
            }
        }
        catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STORAGE_FAILED.getCode(),
                "删除 MinIO 文件失败: " + exception.getMessage(), exception);
        }
    }

    private void upload(String objectName, byte[] bytes, String contentType) {
        try {

            ensureBucketExists();
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(storageConfiguration.bucketName())
                    .object(objectName)
                    .contentType(StrUtil.isNotBlank(contentType) ? contentType : "application/octet-stream")
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .build()
            );
        }
        catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_STORAGE_FAILED.getCode(),
                "上传 MinIO 文件失败: " + exception.getMessage(), exception);
        }
    }

    private void ensureBucketExists() throws Exception {
        if (!bucketExists()) {

            minioClient.makeBucket(MakeBucketArgs.builder().bucket(storageConfiguration.bucketName()).build());
        }
    }

    private boolean bucketExists() throws Exception {
        String bucketName = storageConfiguration.bucketName();
        return minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
    }

    private String buildObjectUrl(String objectName) {
        String endpoint = storageConfiguration.endpoint();
        if (endpoint.endsWith("/")) {

            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        return endpoint + "/" + storageConfiguration.bucketName() + "/" + objectName;
    }
}
