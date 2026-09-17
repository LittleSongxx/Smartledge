package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.data.SuperAgentDocumentBlock;
import org.smartledge.ai.manage.data.SuperAgentDocumentParseArtifact;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentBlockMapper;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentParseArtifactMapper;
import org.smartledge.ai.manage.service.DocumentParseArtifactService;
import org.smartledge.ai.manage.service.DocumentStorageService;
import org.smartledge.ai.manage.service.DocumentTableStructureService;
import org.smartledge.ai.manage.support.DocumentTableCandidate;
import org.smartledge.enums.BusinessStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * @description: 服务实现层
 * @author: Song
 **/

@Slf4j
@AllArgsConstructor
@Service
public class DocumentParseArtifactServiceImpl implements DocumentParseArtifactService {

    private final SuperAgentDocumentParseArtifactMapper artifactMapper;

    private final SuperAgentDocumentBlockMapper blockMapper;

    private final DocumentStorageService storageService;

    private final DocumentTableStructureService tableStructureService;

    private final UidGenerator uidGenerator;

    private final PlatformTransactionManager transactionManager;

    /**
     * 替换某个解析任务的产物。
     *
     * <p>对象存储的删除不能在数据库事务内执行：那是不可逆的外部副作用，数据库回滚时对象已经没了，
     * 而库里的行仍然指向它们。这里改为「先记录旧对象名 → 短事务内替换数据库记录 → 提交后再删除旧对象」。</p>
     *
     * <p>产物对象名包含写入时间戳，因此提交后删除的旧名字不会命中本次新写入的对象。</p>
     */
    @Override
    public void replaceTaskArtifacts(Long documentId,
                                     Long taskId,
                                     List<SuperAgentDocumentParseArtifact> artifactList,
                                     List<SuperAgentDocumentBlock> blockList,
                                     List<DocumentTableCandidate> tableCandidates) {
        List<String> staleObjectNames = listObjectNames(documentId, taskId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            deleteByTask(documentId, taskId);
            saveArtifacts(documentId, taskId, artifactList);
            saveBlocks(documentId, taskId, blockList);
            tableStructureService.replaceTaskTables(documentId, taskId, blockList, tableCandidates);
        });
        deleteStaleObjects(staleObjectNames);
    }

    /**
     * 删除已被替换掉的对象。
     *
     * <p>失败只记录不抛出：数据库记录已经指向新对象，残留的旧对象只是存储垃圾，
     * 不应让一次成功的解析因为清理失败而变成失败。</p>
     */
    private void deleteStaleObjects(List<String> staleObjectNames) {
        if (CollUtil.isEmpty(staleObjectNames)) {
            return;
        }
        try {
            storageService.deleteObjects(staleObjectNames);
        }
        catch (Exception exception) {
            log.error("清理被替换的解析产物对象失败，残留对象数={}", staleObjectNames.size(), exception);
        }
    }

    @Override
    public void saveArtifacts(Long documentId, Long taskId, List<SuperAgentDocumentParseArtifact> artifactList) {
        if (documentId == null || taskId == null || CollUtil.isEmpty(artifactList)) {
            return;
        }

        for (SuperAgentDocumentParseArtifact artifact : artifactList) {
            if (artifact == null) {
                continue;
            }
            if (artifact.getId() == null) {
                artifact.setId(uidGenerator.getUid());
            }
            artifact.setDocumentId(documentId);
            artifact.setTaskId(taskId);
            if (artifact.getStatus() == null) {
                artifact.setStatus(BusinessStatus.YES.getCode());
            }
            artifactMapper.insert(artifact);
        }
    }

    @Override
    public void saveBlocks(Long documentId, Long taskId, List<SuperAgentDocumentBlock> blockList) {
        if (documentId == null || taskId == null || CollUtil.isEmpty(blockList)) {
            return;
        }

        for (SuperAgentDocumentBlock block : blockList) {
            if (block == null) {
                continue;
            }
            if (block.getId() == null) {
                block.setId(uidGenerator.getUid());
            }
            block.setDocumentId(documentId);
            block.setTaskId(taskId);
            if (block.getStatus() == null) {
                block.setStatus(BusinessStatus.YES.getCode());
            }
            blockMapper.insert(block);
        }
    }

    @Override
    public List<SuperAgentDocumentParseArtifact> listArtifacts(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return List.of();
        }
        return artifactMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentParseArtifact>()
            .eq(SuperAgentDocumentParseArtifact::getDocumentId, documentId)
            .eq(SuperAgentDocumentParseArtifact::getTaskId, taskId)
            .eq(SuperAgentDocumentParseArtifact::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentParseArtifact::getId));
    }

    @Override
    public List<SuperAgentDocumentBlock> listBlocks(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return List.of();
        }
        return blockMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentBlock>()
            .eq(SuperAgentDocumentBlock::getDocumentId, documentId)
            .eq(SuperAgentDocumentBlock::getTaskId, taskId)
            .eq(SuperAgentDocumentBlock::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentBlock::getBlockNo)
            .orderByAsc(SuperAgentDocumentBlock::getId));
    }

    @Override
    public List<String> listObjectNamesByDocumentId(Long documentId) {
        if (documentId == null) {
            return List.of();
        }
        return artifactMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentParseArtifact>()
                .eq(SuperAgentDocumentParseArtifact::getDocumentId, documentId))
            .stream()
            .map(SuperAgentDocumentParseArtifact::getObjectName)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
    }

    @Override
    public void deleteByTask(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return;
        }
        tableStructureService.deleteByTask(documentId, taskId);
        blockMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentBlock>()
            .eq(SuperAgentDocumentBlock::getDocumentId, documentId)
            .eq(SuperAgentDocumentBlock::getTaskId, taskId));
        artifactMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentParseArtifact>()
            .eq(SuperAgentDocumentParseArtifact::getDocumentId, documentId)
            .eq(SuperAgentDocumentParseArtifact::getTaskId, taskId));
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        tableStructureService.deleteByDocumentId(documentId);
        blockMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentBlock>()
            .eq(SuperAgentDocumentBlock::getDocumentId, documentId));
        artifactMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentParseArtifact>()
            .eq(SuperAgentDocumentParseArtifact::getDocumentId, documentId));
    }

    private List<String> listObjectNames(Long documentId, Long taskId) {
        return listArtifacts(documentId, taskId).stream()
            .map(SuperAgentDocumentParseArtifact::getObjectName)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
    }
}
