package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentKnowledgeBase;
import org.smartledge.ai.manage.dto.KnowledgeBaseConfigUpdateDto;
import org.smartledge.ai.manage.dto.KnowledgeBaseDeleteDto;
import org.smartledge.ai.manage.dto.KnowledgeBaseDetailDto;
import org.smartledge.ai.manage.dto.KnowledgeBaseSaveDto;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.mapper.SuperAgentKnowledgeBaseMapper;
import org.smartledge.ai.manage.service.KnowledgeBaseManageService;
import org.smartledge.ai.manage.vo.KnowledgeBaseItemVo;
import org.smartledge.ai.manage.vo.KnowledgeBaseOptionVo;
import org.smartledge.enums.BaseCode;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.DocumentIndexStatusEnum;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class KnowledgeBaseManageServiceImpl implements KnowledgeBaseManageService {

    private final SuperAgentKnowledgeBaseMapper knowledgeBaseMapper;
    private final SuperAgentDocumentMapper documentMapper;
    private final UidGenerator uidGenerator;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseItemVo save(KnowledgeBaseSaveDto dto) {
        validateSave(dto);
        Long id = parseOptionalLong(dto.getId());
        SuperAgentKnowledgeBase entity = id == null ? null : knowledgeBaseMapper.selectById(id);
        if (id != null && (entity == null || !Objects.equals(entity.getStatus(), BusinessStatus.YES.getCode()))) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "知识库不存在或已停用。");
        }
        ensureUniqueBaseName(id, safeText(dto.getBaseName()));
        if (entity == null) {
            entity = new SuperAgentKnowledgeBase();
            entity.setId(uidGenerator.getUid());
            entity.setStatus(BusinessStatus.YES.getCode());
        }
        entity.setBaseName(safeText(dto.getBaseName()));
        entity.setDescription(safeText(dto.getDescription()));
        entity.setRetrievalConfigJson(validateJson(dto.getRetrievalConfigJson(), "retrievalConfigJson"));
        entity.setGraphRagConfigJson(validateJson(dto.getGraphRagConfigJson(), "graphRagConfigJson"));
        entity.setRaptorConfigJson(validateJson(dto.getRaptorConfigJson(), "raptorConfigJson"));
        entity.setMetadataFilterJson(validateJson(dto.getMetadataFilterJson(), "metadataFilterJson"));
        entity.setIsDefault(parseInteger(dto.getIsDefault(), 0));
        entity.setSortOrder(parseInteger(dto.getSortOrder(), 0));
        if (Objects.equals(entity.getIsDefault(), 1)) {
            clearOtherDefaults(entity.getId());
        }
        if (entity.getCreateTime() == null) {
            knowledgeBaseMapper.insert(entity);
        }
        else {
            knowledgeBaseMapper.updateById(entity);
        }
        return toItemVo(entity, countDocumentsByBaseId(List.of(entity.getId())), countRetrievableDocumentsByBaseId(List.of(entity.getId())));
    }

    @Override
    public boolean delete(KnowledgeBaseDeleteDto dto) {
        Long id = parseRequiredLong(dto == null ? null : dto.getId(), "id");
        return knowledgeBaseMapper.update(null, new LambdaUpdateWrapper<SuperAgentKnowledgeBase>()
            .eq(SuperAgentKnowledgeBase::getId, id)
            .eq(SuperAgentKnowledgeBase::getStatus, BusinessStatus.YES.getCode())
            .set(SuperAgentKnowledgeBase::getStatus, BusinessStatus.NO.getCode())) > 0;
    }

    @Override
    public List<KnowledgeBaseItemVo> list() {
        List<SuperAgentKnowledgeBase> bases = knowledgeBaseMapper.selectList(baseListWrapper());
        Map<Long, Long> documentCounts = countDocumentsByBaseId(bases.stream().map(SuperAgentKnowledgeBase::getId).toList());
        Map<Long, Long> retrievableCounts = countRetrievableDocumentsByBaseId(bases.stream().map(SuperAgentKnowledgeBase::getId).toList());
        return bases.stream()
            .map(base -> toItemVo(base, documentCounts, retrievableCounts))
            .toList();
    }

    @Override
    public KnowledgeBaseItemVo detail(KnowledgeBaseDetailDto dto) {
        SuperAgentKnowledgeBase entity = requireEnabled(parseRequiredLong(dto == null ? null : dto.getId(), "id"));
        return toItemVo(entity, countDocumentsByBaseId(List.of(entity.getId())), countRetrievableDocumentsByBaseId(List.of(entity.getId())));
    }

    @Override
    public KnowledgeBaseItemVo updateConfig(KnowledgeBaseConfigUpdateDto dto) {
        Long id = parseRequiredLong(dto == null ? null : dto.getId(), "id");
        requireEnabled(id);
        String retrievalConfigJson = validateJson(dto.getRetrievalConfigJson(), "retrievalConfigJson");
        String graphRagConfigJson = validateJson(dto.getGraphRagConfigJson(), "graphRagConfigJson");
        String raptorConfigJson = validateJson(dto.getRaptorConfigJson(), "raptorConfigJson");
        String metadataFilterJson = validateJson(dto.getMetadataFilterJson(), "metadataFilterJson");
        // 必须用显式 set 更新，不能用 updateById：空串会被 validateJson 归一为 null，
        // 而 MyBatis-Plus 默认的 NOT_NULL 策略会把 null 字段排除在 UPDATE SET 之外，
        // 结果是接口返回已清空、数据库仍保留旧配置，前端与后端对作用域的理解静默分叉。
        // 知识库的四个 JSON 配置都必须可被清空（清空即回落到继承默认）。
        knowledgeBaseMapper.update(null, new LambdaUpdateWrapper<SuperAgentKnowledgeBase>()
            .eq(SuperAgentKnowledgeBase::getId, id)
            .set(SuperAgentKnowledgeBase::getRetrievalConfigJson, retrievalConfigJson)
            .set(SuperAgentKnowledgeBase::getGraphRagConfigJson, graphRagConfigJson)
            .set(SuperAgentKnowledgeBase::getRaptorConfigJson, raptorConfigJson)
            .set(SuperAgentKnowledgeBase::getMetadataFilterJson, metadataFilterJson));
        // 回读持久化结果再返回，保证响应与数据库一致，而不是与内存实体一致。
        SuperAgentKnowledgeBase persisted = requireEnabled(id);
        return toItemVo(persisted, countDocumentsByBaseId(List.of(id)), countRetrievableDocumentsByBaseId(List.of(id)));
    }

    @Override
    public List<KnowledgeBaseOptionVo> listOptions() {
        List<SuperAgentKnowledgeBase> bases = knowledgeBaseMapper.selectList(baseListWrapper());
        Map<Long, Long> retrievableCounts = countRetrievableDocumentsByBaseId(bases.stream().map(SuperAgentKnowledgeBase::getId).toList());
        return bases.stream()
            .map(base -> new KnowledgeBaseOptionVo(
                String.valueOf(base.getId()),
                safeText(base.getBaseName()),
                safeText(base.getDescription()),
                String.valueOf(nullToZero(base.getIsDefault())),
                String.valueOf(retrievableCounts.getOrDefault(base.getId(), 0L))
            ))
            .toList();
    }

    @Override
    public List<SuperAgentKnowledgeBase> listEnabledByIds(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return List.of();
        }
        return knowledgeBaseMapper.selectList(new LambdaQueryWrapper<SuperAgentKnowledgeBase>()
            .in(SuperAgentKnowledgeBase::getId, ids.stream().filter(Objects::nonNull).toList())
            .eq(SuperAgentKnowledgeBase::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentKnowledgeBase::getSortOrder, SuperAgentKnowledgeBase::getId));
    }

    @Override
    public List<SuperAgentKnowledgeBase> listAllEnabled() {
        return knowledgeBaseMapper.selectList(baseListWrapper());
    }

    @Override
    public SuperAgentKnowledgeBase requireEnabled(Long id) {
        if (id == null || id <= 0) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "knowledgeBaseId 不能为空。");
        }
        SuperAgentKnowledgeBase entity = knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<SuperAgentKnowledgeBase>()
            .eq(SuperAgentKnowledgeBase::getId, id)
            .eq(SuperAgentKnowledgeBase::getStatus, BusinessStatus.YES.getCode())
            .last("LIMIT 1"));
        if (entity == null) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "知识库不存在或已停用。");
        }
        return entity;
    }

    private LambdaQueryWrapper<SuperAgentKnowledgeBase> baseListWrapper() {
        return new LambdaQueryWrapper<SuperAgentKnowledgeBase>()
            .eq(SuperAgentKnowledgeBase::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentKnowledgeBase::getSortOrder, SuperAgentKnowledgeBase::getId);
    }

    private void clearOtherDefaults(Long currentId) {
        knowledgeBaseMapper.update(null, new LambdaUpdateWrapper<SuperAgentKnowledgeBase>()
            .ne(SuperAgentKnowledgeBase::getId, currentId)
            .eq(SuperAgentKnowledgeBase::getStatus, BusinessStatus.YES.getCode())
            .set(SuperAgentKnowledgeBase::getIsDefault, 0));
    }

    private Map<Long, Long> countDocumentsByBaseId(List<Long> baseIds) {
        if (CollUtil.isEmpty(baseIds)) {
            return Map.of();
        }
        return documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
                .in(SuperAgentDocument::getKnowledgeBaseId, baseIds)
                .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .collect(Collectors.groupingBy(
                SuperAgentDocument::getKnowledgeBaseId,
                LinkedHashMap::new,
                Collectors.counting()
            ));
    }

    private Map<Long, Long> countRetrievableDocumentsByBaseId(List<Long> baseIds) {
        if (CollUtil.isEmpty(baseIds)) {
            return Map.of();
        }
        return documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
                .in(SuperAgentDocument::getKnowledgeBaseId, baseIds)
                .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
                .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
                .isNotNull(SuperAgentDocument::getLastIndexTaskId))
            .stream()
            .collect(Collectors.groupingBy(
                SuperAgentDocument::getKnowledgeBaseId,
                LinkedHashMap::new,
                Collectors.counting()
            ));
    }

    private KnowledgeBaseItemVo toItemVo(SuperAgentKnowledgeBase entity,
                                         Map<Long, Long> documentCounts,
                                         Map<Long, Long> retrievableCounts) {
        return new KnowledgeBaseItemVo(
            String.valueOf(entity.getId()),
            safeText(entity.getBaseName()),
            safeText(entity.getDescription()),
            safeText(entity.getRetrievalConfigJson()),
            safeText(entity.getGraphRagConfigJson()),
            safeText(entity.getRaptorConfigJson()),
            safeText(entity.getMetadataFilterJson()),
            String.valueOf(nullToZero(entity.getIsDefault())),
            String.valueOf(nullToZero(entity.getSortOrder())),
            String.valueOf(documentCounts.getOrDefault(entity.getId(), 0L)),
            String.valueOf(retrievableCounts.getOrDefault(entity.getId(), 0L)),
            entity.getEditTime() == null ? "" : String.valueOf(entity.getEditTime().getTime())
        );
    }

    private void validateSave(KnowledgeBaseSaveDto dto) {
        if (dto == null || safeText(dto.getBaseName()).isBlank()) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "baseName 不能为空。");
        }
    }

    private void ensureUniqueBaseName(Long currentId, String baseName) {
        SuperAgentKnowledgeBase sameName = knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<SuperAgentKnowledgeBase>()
            .eq(SuperAgentKnowledgeBase::getBaseName, baseName)
            .eq(SuperAgentKnowledgeBase::getStatus, BusinessStatus.YES.getCode())
            .last("LIMIT 1"));
        if (sameName != null && !Objects.equals(sameName.getId(), currentId)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "知识库名称已存在。");
        }
    }

    private String validateJson(String rawJson, String fieldName) {
        String text = safeText(rawJson);
        if (text.isBlank()) {
            return null;
        }
        try {
            objectMapper.readTree(text);
            return text;
        }
        catch (JsonProcessingException | RuntimeException exception) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + " 不是合法 JSON。");
        }
    }

    private Long parseRequiredLong(String rawValue, String fieldName) {
        Long value = parseOptionalLong(rawValue);
        if (value == null || value <= 0) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + "不能为空。");
        }
        return value;
    }

    private Long parseOptionalLong(String rawValue) {
        if (StrUtil.isBlank(rawValue)) {
            return null;
        }
        try {
            return Long.valueOf(rawValue.trim());
        }
        catch (NumberFormatException exception) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "id 格式非法。");
        }
    }

    private Integer parseInteger(String rawValue, Integer fallback) {
        if (StrUtil.isBlank(rawValue)) {
            return fallback;
        }
        try {
            return Integer.valueOf(rawValue.trim());
        }
        catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
