package org.smartledge.ai.chatagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.chatagent.data.SuperAgentChatExchange;
import org.smartledge.ai.chatagent.mapper.SuperAgentChatDialogueMapper;
import org.smartledge.ai.chatagent.mapper.SuperAgentChatExchangeMapper;
import org.smartledge.ai.manage.support.MybatisLambdaCacheTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 部分答案落库（崩溃安全兜底）的行为锁定：
 * 只在有内容时更新、返回是否命中 RUNNING 行；终态写入方永远随后覆盖。
 */
class MybatisConversationArchiveStoreFlushTest {

    private final SuperAgentChatDialogueMapper dialogueMapper = mock(SuperAgentChatDialogueMapper.class);
    private final SuperAgentChatExchangeMapper exchangeMapper = mock(SuperAgentChatExchangeMapper.class);

    private MybatisConversationArchiveStore store;

    @BeforeAll
    static void initMybatisMetadata() {
        MybatisLambdaCacheTestSupport.initialize(SuperAgentChatExchange.class);
    }

    @BeforeEach
    void setUp() {
        store = new MybatisConversationArchiveStore(dialogueMapper, exchangeMapper, new ObjectMapper());
    }

    @Test
    @DisplayName("命中 RUNNING 行时返回 true")
    void flushUpdatesRunningRow() {
        when(exchangeMapper.update(isNull(), any())).thenReturn(1);

        assertThat(store.flushPartialAnswer("conv-1", 100L, "已生成片段")).isTrue();
        verify(exchangeMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("轮次已终态或不存在时返回 false，调用方不重试")
    void flushSkipsTerminalOrMissingRow() {
        when(exchangeMapper.update(isNull(), any())).thenReturn(0);

        assertThat(store.flushPartialAnswer("conv-1", 100L, "已生成片段")).isFalse();
    }

    @Test
    @DisplayName("空答案或空会话号不产生任何写入")
    void flushIgnoresBlankInput() {
        assertThat(store.flushPartialAnswer("conv-1", 100L, "")).isFalse();
        assertThat(store.flushPartialAnswer(null, 100L, "片段")).isFalse();

        verify(exchangeMapper, never()).update(any(), any());
    }
}
