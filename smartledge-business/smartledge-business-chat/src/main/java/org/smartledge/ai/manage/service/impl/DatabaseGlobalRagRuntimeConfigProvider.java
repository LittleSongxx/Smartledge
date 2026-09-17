package org.smartledge.ai.manage.service.impl;

import org.smartledge.ai.rag.runtime.model.RagRuntimeOptions;
import org.smartledge.ai.rag.runtime.config.ChatAgentProperties;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;
import org.smartledge.ai.rag.runtime.port.ChatRuntimeConfigProvider;
import org.smartledge.ai.rag.runtime.port.GlobalRagRuntimeConfigProvider;
import org.smartledge.ai.manage.data.SuperAgentSystemConfig;
import org.smartledge.ai.manage.config.SystemConfigRegistry;
import org.smartledge.ai.manage.mapper.SuperAgentSystemConfigMapper;
import org.smartledge.ai.manage.model.SystemConfigSnapshot;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.smartledge.ai.manage.support.SystemConfigSnapshotCodec;
import org.smartledge.enums.BaseCode;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseGlobalRagRuntimeConfigProvider implements GlobalRagRuntimeConfigProvider, SystemConfigProvider, ChatRuntimeConfigProvider {

    private static final long CURRENT_CONFIG_ID = 1L;
    private final SuperAgentSystemConfigMapper configMapper;
    private final SystemConfigSnapshotCodec codec;
    private final SystemConfigRegistry registry;
    private volatile SystemConfigSnapshot cachedSnapshot;
    private volatile Integer cachedVersion;
    private volatile SystemConfigSnapshot instanceStartSnapshot;
    private volatile Integer instanceStartVersion;

    public DatabaseGlobalRagRuntimeConfigProvider(SuperAgentSystemConfigMapper configMapper,
                                                  SystemConfigSnapshotCodec codec,
                                                  SystemConfigRegistry registry) {
        this.configMapper = configMapper;
        this.codec = codec;
        this.registry = registry;
    }

    @Override
    public RagRuntimeOptions currentOptions() {
        return currentSnapshot().getRagRuntime().deepCopy();
    }

    @Override
    public ChatAgentProperties currentChat() {
        return currentSnapshot().getChat();
    }

    @Override
    public ChatRagProperties currentRag() {
        return currentSnapshot().getRag();
    }

    @Override
    public SystemConfigSnapshot currentSnapshot() {
        LoadedSnapshot latest = loadLatest();
        initializeInstanceStart(latest);
        return registry.useEffectiveModeValues(latest.snapshot(), instanceStartSnapshot,
            SystemConfigRegistry.RESTART_REQUIRED);
    }

    @Override
    public Integer instanceStartVersion() {
        LoadedSnapshot latest = loadLatest();
        initializeInstanceStart(latest);
        return instanceStartVersion;
    }

    private synchronized LoadedSnapshot loadLatest() {
        try {
            SuperAgentSystemConfig row = configMapper.selectById(CURRENT_CONFIG_ID);
            if (row == null || row.getStatus() == null || row.getStatus() != 1) {
                throw configurationUnavailable("数据库中没有启用的系统配置，请清空旧配置数据后执行初始化脚本。", null);
            }
            if (cachedSnapshot == null || !java.util.Objects.equals(cachedVersion, row.getConfigVersion())) {
                cachedSnapshot = codec.deserialize(row.getConfigJson());
                cachedVersion = row.getConfigVersion();
            }
            return new LoadedSnapshot(cachedVersion, cachedSnapshot);
        }
        catch (RuntimeException exception) {
            if (exception instanceof SuperAgentFrameException) {
                throw exception;
            }
            throw configurationUnavailable("数据库系统配置不可用，拒绝使用内置默认值。", exception);
        }
    }

    @Override
    public void invalidate() {
        cachedSnapshot = null;
        cachedVersion = null;
    }

    private synchronized void initializeInstanceStart(LoadedSnapshot latest) {
        if (instanceStartSnapshot == null) {
            instanceStartSnapshot = registry.copy(latest.snapshot());
            instanceStartVersion = latest.version();
        }
    }

    private SuperAgentFrameException configurationUnavailable(String message, RuntimeException cause) {
        return new SuperAgentFrameException(BaseCode.SYSTEM_ERROR.getCode(), message, cause);
    }

    private record LoadedSnapshot(Integer version, SystemConfigSnapshot snapshot) {
    }
}
