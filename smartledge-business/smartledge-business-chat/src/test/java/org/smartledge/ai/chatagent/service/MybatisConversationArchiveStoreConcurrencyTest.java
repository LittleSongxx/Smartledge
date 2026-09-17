package org.smartledge.ai.chatagent.service;

import com.baidu.fsg.uid.UidGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.chatagent.data.SuperAgentChatDialogue;
import org.smartledge.ai.chatagent.mapper.SuperAgentChatDialogueMapper;
import org.smartledge.ai.chatagent.mapper.SuperAgentChatExchangeMapper;
import org.smartledge.ai.manage.support.MybatisLambdaCacheTestSupport;
import org.smartledge.database.tenant.TenantContext;
import org.smartledge.enums.ChatQueryMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同租户并发插入同一 dialogue_code 时不能靠 ORDER BY id DESC 抢主，
 * 必须走唯一约束冲突后的重选更新。
 */
@ExtendWith(MockitoExtension.class)
class MybatisConversationArchiveStoreConcurrencyTest {

    @Mock
    private SuperAgentChatDialogueMapper dialogueMapper;

    @Mock
    private SuperAgentChatExchangeMapper exchangeMapper;

    @Mock
    private UidGenerator uidGenerator;

    @BeforeAll
    static void initMybatisMetadata() {
        MybatisLambdaCacheTestSupport.initialize(SuperAgentChatDialogue.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("并发插入同一 dialogue_code 时只保留唯一约束胜出的那一行")
    void duplicateInsertRetriesExistingRow() {
        TenantContext.set(1L);
        SuperAgentChatDialogue winner = new SuperAgentChatDialogue();
        winner.setId(99L);
        winner.setConversationId("same-code");
        winner.setTenantId(1L);
        winner.setSessionStatus(org.smartledge.enums.ChatSessionStatus.RUNNING.getCode());
        winner.setChatMode(ChatQueryMode.AUTO_DOCUMENT.getCode());

        when(dialogueMapper.selectOne(any()))
            .thenReturn(null)
            .thenReturn(winner);
        when(dialogueMapper.insert(any(SuperAgentChatDialogue.class)))
            .thenThrow(new DuplicateKeyException("uk_tenant_dialogue_code"));

        MybatisConversationArchiveStore store = new MybatisConversationArchiveStore(
            dialogueMapper, exchangeMapper, new ObjectMapper());
        ReflectionTestUtils.setField(store, "uidGenerator", uidGenerator);
        store.refreshSessionScope("same-code", ChatQueryMode.AUTO_DOCUMENT, null, null, null);

        verify(dialogueMapper, times(1)).insert(any(SuperAgentChatDialogue.class));
        verify(dialogueMapper, times(2)).selectOne(any());
        verify(exchangeMapper, never()).insert(org.mockito.ArgumentMatchers.any(org.smartledge.ai.chatagent.data.SuperAgentChatExchange.class));
    }

    @Test
    @DisplayName("existsInOtherTenant 在系统作用域下探测他租户占用")
    void existsInOtherTenantUsesSystemScope() {
        SuperAgentChatDialogue foreign = new SuperAgentChatDialogue();
        foreign.setId(7L);
        foreign.setTenantId(2L);
        when(dialogueMapper.selectOne(any())).thenReturn(foreign);

        MybatisConversationArchiveStore store = new MybatisConversationArchiveStore(
            dialogueMapper, exchangeMapper, new ObjectMapper());

        org.assertj.core.api.Assertions.assertThat(store.existsInOtherTenant("shared", 1L)).isTrue();
        verify(dialogueMapper).selectOne(any());
    }
}
