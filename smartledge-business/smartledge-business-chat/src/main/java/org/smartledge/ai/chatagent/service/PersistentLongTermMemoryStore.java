package org.smartledge.ai.chatagent.service;

import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.smartledge.ai.chatagent.data.SuperAgentLongTermMemory;
import org.smartledge.ai.chatagent.mapper.SuperAgentLongTermMemoryMapper;
import org.smartledge.ai.chatagent.model.memory.LongTermMemoryFact;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;
import org.smartledge.enums.BusinessStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PersistentLongTermMemoryStore implements LongTermMemoryStore {

    private final SuperAgentLongTermMemoryMapper mapper;
    private final ChatRagProperties properties;

    @Resource
    private UidGenerator uidGenerator;

    public PersistentLongTermMemoryStore(SuperAgentLongTermMemoryMapper mapper, ChatRagProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public List<LongTermMemoryFact> listActiveFacts(String conversationId, int limit) {
        if (StrUtil.isBlank(conversationId)) {
            return List.of();
        }
        int capped = Math.max(1, limit);
        return mapper.selectList(new LambdaQueryWrapper<SuperAgentLongTermMemory>()
                .eq(SuperAgentLongTermMemory::getConversationId, conversationId)
                .eq(SuperAgentLongTermMemory::getLifecycle, LongTermMemoryFact.LIFECYCLE_ACTIVE)
                .eq(SuperAgentLongTermMemory::getStatus, BusinessStatus.YES.getCode())
                .orderByDesc(SuperAgentLongTermMemory::getEditTime)
                .orderByDesc(SuperAgentLongTermMemory::getId)
                .last("LIMIT " + capped))
            .stream()
            .map(this::toFact)
            .toList();
    }

    @Override
    @Transactional
    public LongTermMemoryFact rememberExplicit(String conversationId,
                                               Long userId,
                                               String entityKey,
                                               String factText,
                                               long exchangeId) {
        if (StrUtil.isBlank(conversationId)) {
            throw new IllegalArgumentException("conversationId is required");
        }
        String key = clip(entityKey, 80);
        String text = clip(factText, properties.getLongTermMemory().getMaxFactChars());
        if (key.isBlank() || text.isBlank()) {
            throw new IllegalArgumentException("entityKey and factText are required");
        }

        SuperAgentLongTermMemory current = mapper.selectOne(new LambdaQueryWrapper<SuperAgentLongTermMemory>()
            .eq(SuperAgentLongTermMemory::getConversationId, conversationId)
            .eq(SuperAgentLongTermMemory::getEntityKey, key)
            .eq(SuperAgentLongTermMemory::getLifecycle, LongTermMemoryFact.LIFECYCLE_ACTIVE)
            .eq(SuperAgentLongTermMemory::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentLongTermMemory::getVersion)
            .last("LIMIT 1"));
        if (current != null && text.equals(current.getFactText())) {
            return toFact(current);
        }

        int nextVersion = current == null ? 1 : safeInt(current.getVersion()) + 1;
        if (current != null) {
            SuperAgentLongTermMemory superseded = new SuperAgentLongTermMemory();
            superseded.setId(current.getId());
            superseded.setLifecycle(LongTermMemoryFact.LIFECYCLE_SUPERSEDED);
            mapper.updateById(superseded);
        }

        SuperAgentLongTermMemory inserted = new SuperAgentLongTermMemory();
        inserted.setId(uidGenerator.getUid());
        inserted.setConversationId(conversationId);
        inserted.setUserId(userId);
        inserted.setEntityKey(key);
        inserted.setFactText(text);
        inserted.setSourceKind(LongTermMemoryFact.SOURCE_USER_EXPLICIT);
        inserted.setLifecycle(LongTermMemoryFact.LIFECYCLE_ACTIVE);
        inserted.setProvenanceExchangeId(exchangeId);
        inserted.setVersion(nextVersion);
        inserted.setStatus(BusinessStatus.YES.getCode());
        mapper.insert(inserted);
        return toFact(inserted);
    }

    private LongTermMemoryFact toFact(SuperAgentLongTermMemory row) {
        return new LongTermMemoryFact(
            row.getId(),
            row.getConversationId(),
            row.getUserId(),
            row.getEntityKey(),
            row.getFactText(),
            row.getSourceKind(),
            row.getLifecycle(),
            row.getProvenanceExchangeId() == null ? 0L : row.getProvenanceExchangeId(),
            safeInt(row.getVersion())
        );
    }

    private String clip(String value, int maxChars) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
