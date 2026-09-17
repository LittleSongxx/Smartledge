package org.smartledge.ai.chatagent.service;

import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.smartledge.ai.chatagent.data.SuperAgentChatDialogue;
import org.smartledge.ai.chatagent.data.SuperAgentChatExchange;
import org.smartledge.ai.chatagent.mapper.SuperAgentChatDialogueMapper;
import org.smartledge.ai.chatagent.mapper.SuperAgentChatExchangeMapper;
import org.smartledge.ai.chatagent.model.ConversationExchangeView;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.model.debug.ChatDebugTrace;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.enums.BusinessStatus;
import org.smartledge.enums.ChatQueryMode;
import org.smartledge.enums.KnowledgeBaseSelectionMode;
import org.smartledge.enums.ChatSessionStatus;
import org.smartledge.enums.ChatTurnStatus;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.util.DateUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/**
 * @description: 服务层
 * @author: Song
 **/

@Repository
public class MybatisConversationArchiveStore implements ConversationArchiveStore {

    /** 无归属历史会话的归属值，与迁移脚本的 DEFAULT 0 一致。 */
    private static final long NO_OWNER_USER_ID = 0L;

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<SearchReference>> REFERENCE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<ChatDebugTrace> DEBUG_TRACE_TYPE = new TypeReference<>() {
    };

    private final SuperAgentChatDialogueMapper dialogueMapper;
    private final SuperAgentChatExchangeMapper exchangeMapper;
    private final ObjectMapper objectMapper;

    @Resource
    private UidGenerator uidGenerator;

    public MybatisConversationArchiveStore(SuperAgentChatDialogueMapper dialogueMapper,
                                           SuperAgentChatExchangeMapper exchangeMapper,
                                           ObjectMapper objectMapper) {
        this.dialogueMapper = dialogueMapper;
        this.exchangeMapper = exchangeMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationExchangeView startExchange(String conversationId,
                                                  Long ownerUserId,
                                                  String question,
                                                  ChatQueryMode chatMode,
                                                  Long selectedDocumentId,
                                                  String selectedDocumentName,
                                                  KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        upsertDialogue(conversationId, ownerUserId, ChatSessionStatus.RUNNING, chatMode, selectedDocumentId,
            selectedDocumentName, knowledgeBaseSelection);

        long exchangeId = uidGenerator.getUid();

        SuperAgentChatExchange exchange = new SuperAgentChatExchange();

        exchange.setId(exchangeId);
        exchange.setConversationId(conversationId);
        exchange.setQuestion(question);
        exchange.setAnswer("");
        exchange.setThinkingSteps(writeJson(List.of()));
        exchange.setReferenceList(writeJson(List.of()));
        exchange.setRecommendationList(writeJson(List.of()));
        exchange.setUsedToolList(writeJson(List.of()));
        exchange.setDebugTraceJson(null);
        exchange.setTurnStatus(ChatTurnStatus.RUNNING.getCode());
        exchange.setErrorMessage("");
        exchange.setFirstResponseTimeMs(null);
        exchange.setTotalResponseTimeMs(null);
        exchange.setKnowledgeBaseSelectionMode(selectionModeName(knowledgeBaseSelection));
        exchange.setSelectedKnowledgeBaseIdsJson(writeJson(selectionIds(knowledgeBaseSelection)));
        exchange.setSelectedKnowledgeBaseNamesJson(writeJson(selectionNames(knowledgeBaseSelection)));
        exchange.setRetrievalConfigSnapshotJson(writeNullableJson(knowledgeBaseSelection == null ? null : knowledgeBaseSelection.getRagRuntimeOptions()));
        exchange.setStatus(BusinessStatus.YES.getCode());
        exchangeMapper.insert(exchange);

        return new ConversationExchangeView(
            exchangeId,
            question,
            "",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            ChatTurnStatus.RUNNING,
            "",
            null,
            null,
            selectionModeName(knowledgeBaseSelection),
            selectionIds(knowledgeBaseSelection),
            selectionNames(knowledgeBaseSelection),
            writeNullableJson(knowledgeBaseSelection == null ? null : knowledgeBaseSelection.getRagRuntimeOptions()),
            DateUtils.now(),
            DateUtils.now()
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshSessionScope(String conversationId,
                                    ChatQueryMode chatMode,
                                    Long selectedDocumentId,
                                    String selectedDocumentName,
                                    KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        // 刷新会话范围不改变归属：已存在的会话归属只在新建时写入一次。
        upsertDialogue(conversationId, null, ChatSessionStatus.RUNNING, chatMode, selectedDocumentId,
            selectedDocumentName, knowledgeBaseSelection);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeExchange(String conversationId,
                                 long exchangeId,
                                 String answer,
                                 List<String> thinkingSteps,
                                 List<SearchReference> references,
                                 List<String> recommendations,
                                 List<String> usedTools,
                                 ChatDebugTrace debugTrace,
                                 ChatTurnStatus status,
                                 String errorMessage,
                                 Long firstResponseTimeMs,
                                 Long totalResponseTimeMs) {

        SuperAgentChatExchange existingExchange = exchangeMapper.selectOne(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .eq(SuperAgentChatExchange::getId, exchangeId)
                .eq(SuperAgentChatExchange::getConversationId, conversationId)
                .last("LIMIT 1")
        );
        if (existingExchange == null) {

            return;
        }

        SuperAgentChatExchange updateExchange = new SuperAgentChatExchange();
        updateExchange.setId(exchangeId);
        updateExchange.setAnswer(safeText(answer));
        updateExchange.setThinkingSteps(writeJson(thinkingSteps));
        updateExchange.setReferenceList(writeJson(references));
        updateExchange.setRecommendationList(writeJson(recommendations));
        updateExchange.setUsedToolList(writeJson(usedTools));
        updateExchange.setDebugTraceJson(writeNullableJson(debugTrace));
        updateExchange.setTurnStatus(status.getCode());
        updateExchange.setErrorMessage(safeText(errorMessage));
        updateExchange.setFirstResponseTimeMs(firstResponseTimeMs);
        updateExchange.setTotalResponseTimeMs(totalResponseTimeMs);
        exchangeMapper.updateById(updateExchange);

        dialogueMapper.update(
            null,
            new LambdaUpdateWrapper<SuperAgentChatDialogue>()
                .eq(SuperAgentChatDialogue::getConversationId, conversationId)

                .set(SuperAgentChatDialogue::getSessionStatus, ChatSessionStatus.IDLE.getCode())
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConversationArchiveRecord> getSessionRecord(String conversationId) {

        SuperAgentChatDialogue dialogue = dialogueMapper.selectOne(
            activeDialogueByConversation(conversationId)
                .orderByDesc(SuperAgentChatDialogue::getId)
                .last("LIMIT 1")
        );
        if (dialogue == null) {
            return Optional.empty();
        }

        List<ConversationExchangeView> exchanges = loadExchangeViews(List.of(conversationId))
            .getOrDefault(conversationId, List.of());

        return Optional.of(new ConversationArchiveRecord(
            dialogue.getConversationId(),
            ChatSessionStatus.isRunning(dialogue.getSessionStatus()),
            resolveChatMode(dialogue),
            dialogue.getSelectedDocumentId(),
            safeText(dialogue.getSelectedDocumentName()),
            safeText(dialogue.getKnowledgeBaseSelectionMode()),
            readStringList(dialogue.getSelectedKnowledgeBaseIdsJson()),
            readStringList(dialogue.getSelectedKnowledgeBaseNamesJson()),
            toInstant(dialogue.getCreateTime()),
            toInstant(dialogue.getEditTime()),
            exchanges
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findOwnerUserId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        SuperAgentChatDialogue dialogue = dialogueMapper.selectOne(
            activeDialogueByConversation(conversationId)
                .last("LIMIT 1")
        );
        if (dialogue == null) {
            return Optional.empty();
        }
        return Optional.of(dialogue.getUserId() == null ? NO_OWNER_USER_ID : dialogue.getUserId());
    }

    @Override
    public boolean existsInOtherTenant(String conversationId, Long currentTenantId) {
        if (conversationId == null || conversationId.isBlank() || currentTenantId == null) {
            return false;
        }
        return Boolean.TRUE.equals(TenantContext.callAsSystem(() -> {
            SuperAgentChatDialogue dialogue = dialogueMapper.selectOne(
                new LambdaQueryWrapper<SuperAgentChatDialogue>()
                    .eq(SuperAgentChatDialogue::getConversationId, conversationId)
                    .eq(SuperAgentChatDialogue::getStatus, BusinessStatus.YES.getCode())
                    .ne(SuperAgentChatDialogue::getTenantId, currentTenantId)
                    .last("LIMIT 1"));
            return dialogue != null;
        }));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationExchangeView> listExchanges(String conversationId) {

        return selectConversationExchanges(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .eq(SuperAgentChatExchange::getConversationId, conversationId)
                .orderByAsc(SuperAgentChatExchange::getCreateTime)
                .orderByAsc(SuperAgentChatExchange::getId)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationExchangeView> listExchangesAfter(String conversationId, long afterExchangeId) {

        return selectConversationExchanges(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .eq(SuperAgentChatExchange::getConversationId, conversationId)
                .gt(afterExchangeId > 0, SuperAgentChatExchange::getId, afterExchangeId)
                .orderByAsc(SuperAgentChatExchange::getCreateTime)
                .orderByAsc(SuperAgentChatExchange::getId)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationExchangeView> listRecentExchanges(String conversationId, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        List<SuperAgentChatExchange> exchanges = exchangeMapper.selectList(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .eq(SuperAgentChatExchange::getConversationId, conversationId)
                .orderByDesc(SuperAgentChatExchange::getCreateTime)
                .orderByDesc(SuperAgentChatExchange::getId)
                .last("LIMIT " + limit)
        );
        if (exchanges == null || exchanges.isEmpty()) {
            return List.of();
        }
        List<ConversationExchangeView> views = new ArrayList<>(exchanges.size());
        for (int index = exchanges.size() - 1; index >= 0; index--) {
            views.add(toExchangeView(exchanges.get(index)));
        }
        return views;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationArchiveRecord> listSessionRecords() {
        List<SuperAgentChatDialogue> rawDialogues = dialogueMapper.selectList(
            new LambdaQueryWrapper<SuperAgentChatDialogue>()
                .orderByDesc(SuperAgentChatDialogue::getEditTime)
                .orderByDesc(SuperAgentChatDialogue::getId)
        );
        if (rawDialogues == null || rawDialogues.isEmpty()) {
            return List.of();
        }

        Map<String, SuperAgentChatDialogue> latestDialogues = new LinkedHashMap<>();
        for (SuperAgentChatDialogue dialogue : rawDialogues) {

            latestDialogues.putIfAbsent(dialogue.getConversationId(), dialogue);
        }
        List<SuperAgentChatDialogue> dialogues = new ArrayList<>(latestDialogues.values());

        List<String> conversationIds = dialogues.stream()
            .map(SuperAgentChatDialogue::getConversationId)
            .toList();

        Map<String, List<ConversationExchangeView>> exchangeViewMap = loadExchangeViews(conversationIds);

        List<ConversationArchiveRecord> result = new ArrayList<>(dialogues.size());
        for (SuperAgentChatDialogue dialogue : dialogues) {
            result.add(new ConversationArchiveRecord(
                dialogue.getConversationId(),
                ChatSessionStatus.isRunning(dialogue.getSessionStatus()),
                resolveChatMode(dialogue),
                dialogue.getSelectedDocumentId(),
                safeText(dialogue.getSelectedDocumentName()),
                safeText(dialogue.getKnowledgeBaseSelectionMode()),
                readStringList(dialogue.getSelectedKnowledgeBaseIdsJson()),
                readStringList(dialogue.getSelectedKnowledgeBaseNamesJson()),
                toInstant(dialogue.getCreateTime()),
                toInstant(dialogue.getEditTime()),
                exchangeViewMap.getOrDefault(dialogue.getConversationId(), List.of())
            ));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationArchivePage listSessionRecordPage(int pageNo,
                                                         int pageSize,
                                                         String keyword,
                                                         ChatQueryMode chatMode,
                                                         ChatTurnStatus latestTurnStatus,
                                                         Long ownerUserId) {
        int resolvedPageNo = Math.max(pageNo, 1);
        int resolvedPageSize = Math.max(pageSize, 1);

        LambdaQueryWrapper<SuperAgentChatDialogue> wrapper = new LambdaQueryWrapper<SuperAgentChatDialogue>()
            .orderByDesc(SuperAgentChatDialogue::getEditTime)
            .orderByDesc(SuperAgentChatDialogue::getId);
        applySessionPageFilters(wrapper, keyword, chatMode, latestTurnStatus);
        if (ownerUserId != null) {
            // 归属过滤是硬条件：无归属（0）的历史会话不属于任何用户，因此不会出现在任何人的列表里。
            wrapper.eq(SuperAgentChatDialogue::getUserId, ownerUserId);
        }

        Page<SuperAgentChatDialogue> page = new Page<>(resolvedPageNo, resolvedPageSize);
        IPage<SuperAgentChatDialogue> resultPage = dialogueMapper.selectPage(
            page,
            wrapper
        );

        List<String> conversationIds = resultPage.getRecords().stream()
            .map(SuperAgentChatDialogue::getConversationId)
            .toList();
        Map<String, ConversationExchangeView> latestExchangeMap = loadLatestExchangeMap(conversationIds);

        List<ConversationArchiveRecord> records = resultPage.getRecords().stream()
            .map(dialogue -> new ConversationArchiveRecord(
                dialogue.getConversationId(),
                ChatSessionStatus.isRunning(dialogue.getSessionStatus()),
                resolveChatMode(dialogue),
                dialogue.getSelectedDocumentId(),
                safeText(dialogue.getSelectedDocumentName()),
                safeText(dialogue.getKnowledgeBaseSelectionMode()),
                readStringList(dialogue.getSelectedKnowledgeBaseIdsJson()),
                readStringList(dialogue.getSelectedKnowledgeBaseNamesJson()),
                toInstant(dialogue.getCreateTime()),
                toInstant(dialogue.getEditTime()),
                latestExchangeMap.containsKey(dialogue.getConversationId())
                    ? List.of(latestExchangeMap.get(dialogue.getConversationId()))
                    : List.of()
            ))
            .toList();

        return new ConversationArchivePage(
            resultPage.getCurrent(),
            resultPage.getSize(),
            resultPage.getTotal(),
            records
        );
    }

    @Override
    @Transactional
    public ConversationArchiveStore.ConversationRemovalResult deleteSession(String conversationId) {
        LambdaQueryWrapper<SuperAgentChatExchange> exchangeQuery = exchangesByConversation(conversationId);
        LambdaQueryWrapper<SuperAgentChatDialogue> dialogueQuery = activeDialogueByConversation(conversationId);

        int removedExchangeCount = toInt(exchangeMapper.selectCount(exchangeQuery));
        int removedDialogueCount = toInt(dialogueMapper.selectCount(dialogueQuery));

        if (removedExchangeCount > 0) {

            exchangeMapper.delete(exchangesByConversation(conversationId));
        }
        if (removedDialogueCount > 0) {
            dialogueMapper.delete(activeDialogueByConversation(conversationId));
        }

        return new ConversationArchiveStore.ConversationRemovalResult(removedDialogueCount, removedExchangeCount);
    }

    private void upsertDialogue(String conversationId,
                                Long ownerUserId,
                                ChatSessionStatus dialogueStage,
                                ChatQueryMode chatMode,
                                Long selectedDocumentId,
                                String selectedDocumentName,
                                KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        Objects.requireNonNull(chatMode, "chatMode 不能为空");
        SuperAgentChatDialogue dialogue = dialogueMapper.selectOne(
            activeDialogueByConversation(conversationId)
                .last("LIMIT 1")
        );

        if (dialogue == null) {
            SuperAgentChatDialogue newDialogue = new SuperAgentChatDialogue();
            newDialogue.setId(uidGenerator.getUid());
            newDialogue.setConversationId(conversationId);
            Long tenantId = TenantContext.get();
            if (tenantId != null && tenantId > 0) {
                newDialogue.setTenantId(tenantId);
            }
            // 归属只在新建时写入：0 表示无归属（既不会匹配任何用户，也不会被后来的请求"认领"）。
            newDialogue.setUserId(ownerUserId == null ? NO_OWNER_USER_ID : ownerUserId);
            newDialogue.setSessionStatus(dialogueStage.getCode());
            newDialogue.setChatMode(chatMode.getCode());
            newDialogue.setSelectedDocumentId(selectedDocumentId);
            newDialogue.setSelectedDocumentName(selectedDocumentName);
            newDialogue.setKnowledgeBaseSelectionMode(selectionModeName(knowledgeBaseSelection));
            newDialogue.setSelectedKnowledgeBaseIdsJson(writeJson(selectionIds(knowledgeBaseSelection)));
            newDialogue.setSelectedKnowledgeBaseNamesJson(writeJson(selectionNames(knowledgeBaseSelection)));
            newDialogue.setStatus(BusinessStatus.YES.getCode());

            try {
                dialogueMapper.insert(newDialogue);
                return;
            }
            catch (DuplicateKeyException duplicate) {
                dialogue = dialogueMapper.selectOne(
                    activeDialogueByConversation(conversationId).last("LIMIT 1"));
                if (dialogue == null) {
                    throw duplicate;
                }
            }
        }

        boolean stageChanged = !dialogueStage.equals(ChatSessionStatus.fromCode(dialogue.getSessionStatus()));
        boolean chatModeChanged = !Objects.equals(chatMode.getCode(), dialogue.getChatMode());
        boolean documentScopeChanged = !Objects.equals(selectedDocumentId, dialogue.getSelectedDocumentId())
            || !Objects.equals(safeText(selectedDocumentName), safeText(dialogue.getSelectedDocumentName()));
        boolean knowledgeBaseScopeChanged = !Objects.equals(selectionModeName(knowledgeBaseSelection), safeText(dialogue.getKnowledgeBaseSelectionMode()))
            || !Objects.equals(selectionIds(knowledgeBaseSelection), readStringList(dialogue.getSelectedKnowledgeBaseIdsJson()))
            || !Objects.equals(selectionNames(knowledgeBaseSelection), readStringList(dialogue.getSelectedKnowledgeBaseNamesJson()));

        if (stageChanged || chatModeChanged || documentScopeChanged || knowledgeBaseScopeChanged) {
            SuperAgentChatDialogue updateDialogue = new SuperAgentChatDialogue();
            updateDialogue.setId(dialogue.getId());
            updateDialogue.setSessionStatus(dialogueStage.getCode());
            updateDialogue.setChatMode(chatMode.getCode());
            updateDialogue.setSelectedDocumentId(selectedDocumentId);
            updateDialogue.setSelectedDocumentName(selectedDocumentName);
            updateDialogue.setKnowledgeBaseSelectionMode(selectionModeName(knowledgeBaseSelection));
            updateDialogue.setSelectedKnowledgeBaseIdsJson(writeJson(selectionIds(knowledgeBaseSelection)));
            updateDialogue.setSelectedKnowledgeBaseNamesJson(writeJson(selectionNames(knowledgeBaseSelection)));
            dialogueMapper.updateById(updateDialogue);
        }
    }

    private ChatQueryMode resolveChatMode(SuperAgentChatDialogue dialogue) {
        if (dialogue == null || dialogue.getChatMode() == null) {
            throw new IllegalStateException("会话记录缺少 chatMode，当前教学版项目要求数据库使用最新结构");
        }
        return ChatQueryMode.fromCode(dialogue.getChatMode());
    }

    private Map<String, List<ConversationExchangeView>> loadExchangeViews(List<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Map.of();
        }

        List<SuperAgentChatExchange> exchanges = exchangeMapper.selectList(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .in(SuperAgentChatExchange::getConversationId, conversationIds)

                .orderByAsc(SuperAgentChatExchange::getCreateTime)
                .orderByAsc(SuperAgentChatExchange::getConversationId)
                .orderByAsc(SuperAgentChatExchange::getId)
        );

        Map<String, List<ConversationExchangeView>> exchangeViewsByConversation = new LinkedHashMap<>();
        for (SuperAgentChatExchange exchange : exchanges) {

            exchangeViewsByConversation.computeIfAbsent(exchange.getConversationId(), key -> new ArrayList<>())
                .add(toExchangeView(exchange));
        }
        return exchangeViewsByConversation;
    }

    private void applySessionPageFilters(LambdaQueryWrapper<SuperAgentChatDialogue> wrapper,
                                         String keyword,
                                         ChatQueryMode chatMode,
                                         ChatTurnStatus latestTurnStatus) {
        if (wrapper == null) {
            return;
        }
        if (chatMode != null) {
            wrapper.eq(SuperAgentChatDialogue::getChatMode, chatMode.getCode());
        }
        if (StrUtil.isNotBlank(keyword)) {
            String likeKeyword = "%" + keyword.trim() + "%";
            wrapper.and(query -> query
                .like(SuperAgentChatDialogue::getConversationId, keyword.trim())
                .or()
                .like(SuperAgentChatDialogue::getSelectedDocumentName, keyword.trim())
                .or()
                .apply(
                    "EXISTS (SELECT 1 FROM smartledge_chat_exchange e WHERE e.dialogue_code = smartledge_chat_dialogue.dialogue_code"
                        + " AND (e.user_prompt LIKE {0} OR e.reply_content LIKE {0} OR e.finish_note LIKE {0}))",
                    likeKeyword
                )
            );
        }
        if (latestTurnStatus == null) {
            return;
        }
        if (latestTurnStatus == ChatTurnStatus.RUNNING) {
            wrapper.eq(SuperAgentChatDialogue::getSessionStatus, ChatSessionStatus.RUNNING.getCode());
            return;
        }
        wrapper.eq(SuperAgentChatDialogue::getSessionStatus, ChatSessionStatus.IDLE.getCode());
        wrapper.apply(
            "EXISTS (SELECT 1 FROM smartledge_chat_exchange e"
                + " WHERE e.dialogue_code = smartledge_chat_dialogue.dialogue_code"
                + " AND e.id = (SELECT latest.id FROM smartledge_chat_exchange latest"
                + " WHERE latest.dialogue_code = smartledge_chat_dialogue.dialogue_code"
                + " ORDER BY latest.create_time DESC, latest.id DESC LIMIT 1)"
                + " AND e.exchange_state = {0})",
            latestTurnStatus.getCode()
        );
    }

    private Map<String, ConversationExchangeView> loadLatestExchangeMap(List<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Map.of();
        }
        List<SuperAgentChatExchange> exchanges = exchangeMapper.selectList(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .in(SuperAgentChatExchange::getConversationId, conversationIds)
                .orderByDesc(SuperAgentChatExchange::getCreateTime)
                .orderByDesc(SuperAgentChatExchange::getId)
        );
        if (exchanges == null || exchanges.isEmpty()) {
            return Map.of();
        }

        Map<String, ConversationExchangeView> latestExchangeMap = new LinkedHashMap<>();
        for (SuperAgentChatExchange exchange : exchanges) {
            if (exchange == null || latestExchangeMap.containsKey(exchange.getConversationId())) {
                continue;
            }
            latestExchangeMap.put(exchange.getConversationId(), toExchangeView(exchange));
        }
        return latestExchangeMap;
    }

    private List<ConversationExchangeView> selectConversationExchanges(LambdaQueryWrapper<SuperAgentChatExchange> queryWrapper) {
        List<SuperAgentChatExchange> exchanges = exchangeMapper.selectList(queryWrapper);
        if (exchanges == null || exchanges.isEmpty()) {
            return List.of();
        }
        List<ConversationExchangeView> result = new ArrayList<>(exchanges.size());
        for (SuperAgentChatExchange exchange : exchanges) {
            result.add(toExchangeView(exchange));
        }
        return result;
    }

    private ConversationExchangeView toExchangeView(SuperAgentChatExchange exchange) {

        return new ConversationExchangeView(
            exchange.getId(),
            safeText(exchange.getQuestion()),
            safeText(exchange.getAnswer()),
            readStringList(exchange.getThinkingSteps()),
            readReferenceList(exchange.getReferenceList()),
            readStringList(exchange.getRecommendationList()),
            readStringList(exchange.getUsedToolList()),
            readDebugTrace(exchange.getDebugTraceJson()),
            ChatTurnStatus.fromCode(exchange.getTurnStatus()),
            safeText(exchange.getErrorMessage()),
            exchange.getFirstResponseTimeMs(),
            exchange.getTotalResponseTimeMs(),
            safeText(exchange.getKnowledgeBaseSelectionMode()),
            readStringList(exchange.getSelectedKnowledgeBaseIdsJson()),
            readStringList(exchange.getSelectedKnowledgeBaseNamesJson()),
            safeText(exchange.getRetrievalConfigSnapshotJson()),
            exchange.getCreateTime(),
            exchange.getEditTime()
        );
    }

    private String selectionModeName(KnowledgeBaseSelectionSnapshot snapshot) {
        KnowledgeBaseSelectionMode mode = snapshot == null || snapshot.getSelectionMode() == null
            ? KnowledgeBaseSelectionMode.NONE
            : snapshot.getSelectionMode();
        return mode.name();
    }

    private List<String> selectionIds(KnowledgeBaseSelectionSnapshot snapshot) {
        if (snapshot == null || snapshot.getSelectedKnowledgeBaseIds() == null) {
            return List.of();
        }
        return snapshot.getSelectedKnowledgeBaseIds().stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .toList();
    }

    private List<String> selectionNames(KnowledgeBaseSelectionSnapshot snapshot) {
        if (snapshot == null || snapshot.getSelectedKnowledgeBaseNames() == null) {
            return List.of();
        }
        return snapshot.getSelectedKnowledgeBaseNames().stream()
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .toList();
    }

    private List<String> readStringList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {

            return objectMapper.readValue(json, STRING_LIST_TYPE);
        }
        catch (Exception exception) {
            throw new IllegalStateException("解析字符串列表失败", exception);
        }
    }

    private List<SearchReference> readReferenceList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {

            return objectMapper.readValue(json, REFERENCE_LIST_TYPE);
        }
        catch (Exception exception) {
            throw new IllegalStateException("解析引用来源列表失败", exception);
        }
    }

    private ChatDebugTrace readDebugTrace(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {

            return objectMapper.readValue(json, DEBUG_TRACE_TYPE);
        }
        catch (Exception exception) {
            throw new IllegalStateException("解析调试轨迹失败", exception);
        }
    }

    private String writeJson(Object value) {
        try {

            return objectMapper.writeValueAsString(value != null ? value : List.of());
        }
        catch (Exception exception) {
            throw new IllegalStateException("序列化会话字段失败", exception);
        }
    }

    private String writeNullableJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception exception) {
            throw new IllegalStateException("序列化可空会话字段失败", exception);
        }
    }

    private Instant toInstant(Date date) {

        return date != null ? date.toInstant() : null;
    }

    private String safeText(String text) {

        return text != null ? text : "";
    }

    private LambdaQueryWrapper<SuperAgentChatDialogue> activeDialogueByConversation(String conversationId) {

        return new LambdaQueryWrapper<SuperAgentChatDialogue>()
            .eq(SuperAgentChatDialogue::getConversationId, conversationId);
    }

    private LambdaQueryWrapper<SuperAgentChatExchange> exchangesByConversation(String conversationId) {

        return new LambdaQueryWrapper<SuperAgentChatExchange>()
            .eq(SuperAgentChatExchange::getConversationId, conversationId);
    }

    private int toInt(Long count) {

        return count == null ? 0 : count.intValue();
    }
}
