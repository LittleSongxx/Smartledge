package org.smartledge.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.AllArgsConstructor;
import org.smartledge.ai.manage.config.SystemConfigRegistry;
import org.smartledge.ai.manage.config.SystemConfigValidationException;
import org.smartledge.ai.manage.data.SuperAgentSystemConfig;
import org.smartledge.ai.manage.data.SuperAgentSystemConfigCategory;
import org.smartledge.ai.manage.data.SuperAgentSystemConfigGroup;
import org.smartledge.ai.manage.data.SuperAgentSystemConfigHistory;
import org.smartledge.ai.manage.dto.SystemConfigHistoryDetailQueryDto;
import org.smartledge.ai.manage.dto.SystemConfigHistoryPageQueryDto;
import org.smartledge.ai.manage.dto.SystemConfigHistoryRestoreDto;
import org.smartledge.ai.manage.dto.SystemConfigItemUpdateDto;
import org.smartledge.ai.manage.mapper.SuperAgentSystemConfigHistoryMapper;
import org.smartledge.ai.manage.mapper.SuperAgentSystemConfigMapper;
import org.smartledge.ai.manage.mapper.SuperAgentSystemConfigCategoryMapper;
import org.smartledge.ai.manage.mapper.SuperAgentSystemConfigGroupMapper;
import org.smartledge.ai.manage.model.SystemConfigSnapshot;
import org.smartledge.ai.manage.service.SystemConfigService;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.smartledge.ai.manage.support.SystemConfigSnapshotCodec;
import org.smartledge.ai.manage.vo.SystemConfigChangeItemVo;
import org.smartledge.ai.manage.vo.SystemConfigCurrentVo;
import org.smartledge.ai.manage.vo.SystemConfigHistoryItemVo;
import org.smartledge.ai.manage.vo.SystemConfigHistoryPageVo;
import org.smartledge.enums.BaseCode;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@AllArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigService {

    private static final long CURRENT_CONFIG_ID = 1L;
    private static final int ENABLED = 1;
    private static final String UNCLASSIFIED_GROUP_KEY = "unclassified";
    private static final String UNCLASSIFIED_GROUP_LABEL = "分类未初始化";
    private static final String UNCLASSIFIED_DESCRIPTION = "以下配置缺少数据库分类元数据，请重新执行系统配置初始化脚本。";

    private final SuperAgentSystemConfigMapper configMapper;
    private final SuperAgentSystemConfigHistoryMapper historyMapper;
    private final SuperAgentSystemConfigGroupMapper groupMapper;
    private final SuperAgentSystemConfigCategoryMapper categoryMapper;
    private final UidGenerator uidGenerator;
    private final SystemConfigRegistry registry;
    private final SystemConfigSnapshotCodec codec;
    private final SystemConfigProvider systemConfigProvider;

    @Override
    public SystemConfigCurrentVo current() {
        SuperAgentSystemConfig row = enabledCurrentRow();
        if (row == null) {
            throw new SuperAgentFrameException(BaseCode.SYSTEM_ERROR.getCode(),
                "数据库系统配置未初始化或未启用，请清空旧配置数据后执行初始化脚本。");
        }
        return toCurrent(codec.deserialize(row.getConfigJson()),
            row.getConfigVersion(), "DATABASE", row.getEditTime());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SystemConfigCurrentVo updateItem(SystemConfigItemUpdateDto dto, String operatorName) {
        SuperAgentSystemConfig current = enabledCurrentRow();
        assertExpectedVersion(current, dto.getExpectedVersion(), dto.getConfigKey());
        SystemConfigSnapshot before = snapshotFrom(current, dto.getConfigKey());
        SystemConfigSnapshot after = registry.apply(before, dto.getConfigKey(), dto.getValue());
        return persistVersion(current, before, after, dto.getExpectedVersion(), dto.getChangeNote(), operatorName,
            "MANUAL", null, dto.getConfigKey());
    }

    @Override
    public SystemConfigHistoryPageVo queryHistory(SystemConfigHistoryPageQueryDto dto) {
        int pageNo = dto == null || dto.getPageNo() == null ? 1 : dto.getPageNo();
        int pageSize = dto == null || dto.getPageSize() == null ? 10 : dto.getPageSize();
        Page<SuperAgentSystemConfigHistory> page = new Page<>(pageNo, pageSize);
        IPage<SuperAgentSystemConfigHistory> result = historyMapper.selectPage(page,
            new LambdaQueryWrapper<SuperAgentSystemConfigHistory>()
                .eq(SuperAgentSystemConfigHistory::getStatus, ENABLED)
                .orderByDesc(SuperAgentSystemConfigHistory::getCreateTime)
                .orderByDesc(SuperAgentSystemConfigHistory::getId));
        List<SystemConfigHistoryItemVo> records = result.getRecords().stream().map(this::toHistoryItem).toList();
        long totalPages = pageSize == 0 ? 0 : (result.getTotal() + pageSize - 1) / pageSize;
        return new SystemConfigHistoryPageVo(pageNo, pageSize, result.getTotal(), totalPages, records);
    }

    @Override
    public SystemConfigHistoryItemVo queryHistoryDetail(SystemConfigHistoryDetailQueryDto dto) {
        return toHistoryItem(requireHistory(dto.getHistoryId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SystemConfigCurrentVo restore(SystemConfigHistoryRestoreDto dto, String operatorName) {
        SuperAgentSystemConfig current = enabledCurrentRow();
        assertExpectedVersion(current, dto.getExpectedVersion(), null);
        SuperAgentSystemConfigHistory source = requireHistory(dto.getHistoryId());
        SystemConfigSnapshot before = snapshotFrom(current, null);
        SystemConfigSnapshot restored = codec.deserialize(source.getAfterConfigJson());
        String note = StrUtil.isBlank(dto.getChangeNote())
            ? "恢复至版本 " + source.getAfterVersion()
            : dto.getChangeNote();
        return persistVersion(current, before, restored, dto.getExpectedVersion(), note, operatorName,
            "RESTORE", source.getId(), null);
    }

    private SystemConfigCurrentVo persistVersion(SuperAgentSystemConfig current,
                                                 SystemConfigSnapshot before,
                                                 SystemConfigSnapshot after,
                                                 int expectedVersion,
                                                 String changeNote,
                                                 String operatorName,
                                                 String sourceType,
                                                 Long restoreFromHistoryId,
                                                 String attemptedConfigKey) {
        int nextVersion = expectedVersion + 1;
        String beforeJson = codec.serialize(before);
        String afterJson = codec.serialize(after);
        if (current == null) {
            SuperAgentSystemConfig row = new SuperAgentSystemConfig();
            row.setId(CURRENT_CONFIG_ID);
            row.setConfigVersion(nextVersion);
            row.setConfigJson(afterJson);
            row.setStatus(ENABLED);
            try {
                if (configMapper.insert(row) != 1) {
                    throw versionConflict(attemptedConfigKey, expectedVersion, null);
                }
            }
            catch (DuplicateKeyException exception) {
                throw versionConflict(attemptedConfigKey, expectedVersion, null);
            }
        }
        else {
            int updated = configMapper.update(null, new LambdaUpdateWrapper<SuperAgentSystemConfig>()
                .eq(SuperAgentSystemConfig::getId, CURRENT_CONFIG_ID)
                .eq(SuperAgentSystemConfig::getConfigVersion, expectedVersion)
                .eq(SuperAgentSystemConfig::getStatus, ENABLED)
                .set(SuperAgentSystemConfig::getConfigVersion, nextVersion)
                .set(SuperAgentSystemConfig::getConfigJson, afterJson));
            if (updated != 1) {
                throw versionConflict(attemptedConfigKey, expectedVersion, null);
            }
        }

        SuperAgentSystemConfigHistory history = new SuperAgentSystemConfigHistory();
        history.setId(uidGenerator.getUid());
        history.setBeforeVersion(expectedVersion);
        history.setAfterVersion(nextVersion);
        history.setBeforeConfigJson(beforeJson);
        history.setAfterConfigJson(afterJson);
        history.setChangeNote(StrUtil.trim(changeNote));
        history.setOperatorName(StrUtil.blankToDefault(StrUtil.trim(operatorName), "admin"));
        history.setSourceType(sourceType);
        history.setRestoreFromHistoryId(restoreFromHistoryId);
        history.setStatus(ENABLED);
        if (historyMapper.insert(history) != 1) {
            throw new SuperAgentFrameException(BaseCode.SYSTEM_ERROR.getCode(), "系统配置历史写入失败");
        }
        systemConfigProvider.invalidate();
        return toCurrent(after, nextVersion, "DATABASE", new Date());
    }

    private void assertExpectedVersion(SuperAgentSystemConfig current, Integer expectedVersion,
            String attemptedConfigKey) {
        int actual = current == null ? 0 : current.getConfigVersion();
        if (expectedVersion == null || expectedVersion != actual) {
            throw versionConflict(attemptedConfigKey, expectedVersion, actual);
        }
    }

    private SystemConfigSnapshot snapshotFrom(SuperAgentSystemConfig row, String attemptedConfigKey) {
        if (row == null) {
            List<String> keys = StrUtil.isBlank(attemptedConfigKey) ? List.of() : List.of(attemptedConfigKey);
            throw new SystemConfigValidationException(BaseCode.SYSTEM_ERROR.getCode(),
                "SYSTEM_CONFIG_NOT_INITIALIZED",
                "数据库系统配置未初始化，不能基于内置默认值写入。", keys, Map.of(),
                "写入前必须存在数据库系统配置快照和对应参数目录。",
                List.of("执行项目规定的系统配置初始化或迁移流程，核对配置版本后再重试；不要基于内置默认值直接写入。"));
        }
        return codec.deserialize(row.getConfigJson());
    }

    private SuperAgentSystemConfig enabledCurrentRow() {
        SuperAgentSystemConfig row = configMapper.selectById(CURRENT_CONFIG_ID);
        return row != null && Objects.equals(row.getStatus(), ENABLED) ? row : null;
    }

    private SuperAgentSystemConfigHistory requireHistory(Long historyId) {
        SuperAgentSystemConfigHistory history = historyId == null ? null : historyMapper.selectById(historyId);
        if (history == null || !Objects.equals(history.getStatus(), ENABLED)) {
            throw parameterError("配置历史不存在。");
        }
        return history;
    }

    private SystemConfigCurrentVo toCurrent(SystemConfigSnapshot snapshot,
                                            Integer version,
                                            String sourceType,
                                            Date lastModifiedAt) {
        SystemConfigSnapshot effectiveSnapshot = systemConfigProvider.currentSnapshot();
        if (effectiveSnapshot == null) {
            effectiveSnapshot = snapshot;
        }
        Integer instanceStartVersion = systemConfigProvider.instanceStartVersion();
        LinkedHashMap<String, List<SystemConfigCurrentVo.Item>> itemsByCategory = new LinkedHashMap<>();
        int pendingRestartCount = 0;
        for (SystemConfigRegistry.ConfigDefinition definition : registry.definitions()) {
            Object savedValue = registry.read(snapshot, definition);
            Object effectiveValue = registry.read(effectiveSnapshot, definition);
            boolean pendingRestart = SystemConfigRegistry.RESTART_REQUIRED.equals(definition.effectiveMode())
                && !Objects.equals(savedValue, effectiveValue);
            if (pendingRestart) {
                pendingRestartCount++;
            }
            itemsByCategory.computeIfAbsent(definition.categoryKey(), ignored -> new ArrayList<>())
                .add(new SystemConfigCurrentVo.Item(
                    definition.key(), definition.label(), definition.description(), definition.relationHint(),
                    definition.relatedConfigKeys(),
                    displayValue(definition, savedValue), displayValue(definition, effectiveValue), pendingRestart,
                    definition.valueType(), definition.controlType(), definition.minValue(), definition.maxValue(),
                    definition.step(), definition.displayScale(), definition.unit(), definition.maxLength(),
                    definition.effectiveMode(), effectiveModeLabel(definition.effectiveMode())
                ));
        }
        List<SystemConfigCurrentVo.Category> categories = categoriesFromDatabase(itemsByCategory);
        return new SystemConfigCurrentVo(version, instanceStartVersion, pendingRestartCount,
            sourceType, sourceTypeLabel(sourceType), lastModifiedAt, categories);
    }

    private List<SystemConfigCurrentVo.Category> categoriesFromDatabase(
        LinkedHashMap<String, List<SystemConfigCurrentVo.Item>> itemsByCategory) {
        List<SuperAgentSystemConfigGroup> groups = groupMapper.selectList(
            new LambdaQueryWrapper<SuperAgentSystemConfigGroup>()
                .eq(SuperAgentSystemConfigGroup::getStatus, ENABLED)
                .orderByAsc(SuperAgentSystemConfigGroup::getSortOrder, SuperAgentSystemConfigGroup::getId));
        List<SuperAgentSystemConfigCategory> categoryRows = categoryMapper.selectList(
            new LambdaQueryWrapper<SuperAgentSystemConfigCategory>()
                .eq(SuperAgentSystemConfigCategory::getStatus, ENABLED)
                .orderByAsc(SuperAgentSystemConfigCategory::getSortOrder, SuperAgentSystemConfigCategory::getId));

        LinkedHashMap<String, List<SuperAgentSystemConfigCategory>> categoriesByGroup = new LinkedHashMap<>();
        for (SuperAgentSystemConfigCategory category : categoryRows) {
            categoriesByGroup.computeIfAbsent(category.getGroupKey(), ignored -> new ArrayList<>()).add(category);
        }

        List<SystemConfigCurrentVo.Category> result = new ArrayList<>();
        Set<String> classifiedCategoryKeys = new HashSet<>();
        for (SuperAgentSystemConfigGroup group : groups) {
            for (SuperAgentSystemConfigCategory category : categoriesByGroup.getOrDefault(group.getGroupKey(), List.of())) {
                List<SystemConfigCurrentVo.Item> items = itemsByCategory.get(category.getCategoryKey());
                if (items == null || items.isEmpty()) {
                    continue;
                }
                result.add(new SystemConfigCurrentVo.Category(
                    group.getGroupKey(), group.getGroupLabel(), group.getGroupDescription(),
                    category.getCategoryKey(), category.getCategoryLabel(), category.getCategoryDescription(), items
                ));
                classifiedCategoryKeys.add(category.getCategoryKey());
            }
        }

        itemsByCategory.forEach((categoryKey, items) -> {
            if (!classifiedCategoryKeys.contains(categoryKey)) {
                result.add(new SystemConfigCurrentVo.Category(
                    UNCLASSIFIED_GROUP_KEY, UNCLASSIFIED_GROUP_LABEL, UNCLASSIFIED_DESCRIPTION,
                    categoryKey, categoryKey, UNCLASSIFIED_DESCRIPTION, items
                ));
            }
        });
        return result;
    }

    private SystemConfigHistoryItemVo toHistoryItem(SuperAgentSystemConfigHistory history) {
        SystemConfigSnapshot before = codec.deserialize(history.getBeforeConfigJson());
        SystemConfigSnapshot after = codec.deserialize(history.getAfterConfigJson());
        List<SystemConfigChangeItemVo> changes = registry.definitions().stream()
            .filter(definition -> !Objects.equals(registry.read(before, definition), registry.read(after, definition)))
            .map(definition -> new SystemConfigChangeItemVo(
                definition.key(), definition.label(), displayValue(definition, registry.read(before, definition)), displayValue(definition, registry.read(after, definition)),
                definition.valueType(), definition.controlType(), definition.displayScale(), definition.unit()
            ))
            .toList();
        return new SystemConfigHistoryItemVo(
            String.valueOf(history.getId()), history.getBeforeVersion(), history.getAfterVersion(), history.getSourceType(),
            sourceTypeLabel(history.getSourceType()), history.getChangeNote(), history.getOperatorName(), history.getCreateTime(),
            changes.size(), changes,
            history.getRestoreFromHistoryId() == null ? "" : String.valueOf(history.getRestoreFromHistoryId())
        );
    }

    private String sourceTypeLabel(String sourceType) {
        return switch (StrUtil.blankToDefault(sourceType, "")) {
            case "BUILT_IN_FALLBACK" -> "内置应急默认值（请执行初始化脚本）";
            case "DATABASE" -> "数据库配置";
            case "RESTORE" -> "历史恢复";
            case "INIT" -> "初始化";
            default -> "手动修改";
        };
    }

    private Object displayValue(SystemConfigRegistry.ConfigDefinition definition, Object value) {
        if (!definition.sensitive()) {
            return value;
        }
        return value == null || String.valueOf(value).isBlank() ? "未配置" : "已配置";
    }

    private String effectiveModeLabel(String effectiveMode) {
        return switch (StrUtil.blankToDefault(effectiveMode, "")) {
            case SystemConfigRegistry.NEW_BUILD_TASK -> "新构建任务生效";
            case SystemConfigRegistry.RESTART_REQUIRED -> "重启应用后生效";
            default -> "新会话生效";
        };
    }

    private SystemConfigValidationException versionConflict(String attemptedConfigKey,
            Integer expectedVersion, Integer actualVersion) {
        List<String> keys = StrUtil.isBlank(attemptedConfigKey) ? List.of() : List.of(attemptedConfigKey);
        String constraint = actualVersion == null
            ? "提交版本写入前已发生变化，当前版本需要重新读取。"
            : "提交时的期望版本 " + expectedVersion + " 与当前版本 " + actualVersion + " 不一致。";
        return new SystemConfigValidationException(409, "SYSTEM_CONFIG_VERSION_CONFLICT",
            "配置已被其他操作更新，请刷新后重试。", keys, Map.of(), constraint,
            List.of("刷新当前配置，核对差异后重新提交；不要覆盖其他管理员的修改。"));
    }

    private SuperAgentFrameException parameterError(String message) {
        return new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), message);
    }

}
