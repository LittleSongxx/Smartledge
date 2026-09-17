package org.smartledge.ai.chatagent.service;

import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.smartledge.ai.chatagent.data.SuperAgentLongTermMemory;
import org.smartledge.ai.chatagent.mapper.SuperAgentLongTermMemoryMapper;
import org.smartledge.ai.chatagent.model.memory.LongTermMemoryFact;
import org.smartledge.ai.rag.runtime.config.ChatRagProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersistentLongTermMemoryStoreTest {

    @Test
    @DisplayName("同键新值 SUPERSEDE 旧 ACTIVE，不原地覆盖")
    void supersedesInsteadOfOverwrite() {
        SuperAgentLongTermMemoryMapper mapper = mock(SuperAgentLongTermMemoryMapper.class);
        List<SuperAgentLongTermMemory> rows = new ArrayList<>();
        when(mapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> rows.stream()
            .filter(row -> LongTermMemoryFact.LIFECYCLE_ACTIVE.equals(row.getLifecycle()))
            .findFirst()
            .orElse(null));
        when(mapper.updateById(any(SuperAgentLongTermMemory.class))).thenAnswer(invocation -> {
            SuperAgentLongTermMemory patch = invocation.getArgument(0);
            rows.stream()
                .filter(row -> Objects.equals(row.getId(), patch.getId()))
                .forEach(row -> row.setLifecycle(patch.getLifecycle()));
            return 1;
        });
        when(mapper.insert(any(SuperAgentLongTermMemory.class))).thenAnswer(invocation -> {
            SuperAgentLongTermMemory row = invocation.getArgument(0);
            rows.add(row);
            return 1;
        });

        PersistentLongTermMemoryStore store = new PersistentLongTermMemoryStore(mapper, new ChatRagProperties());
        UidGenerator uidGenerator = mock(UidGenerator.class);
        AtomicLong sequence = new AtomicLong(1000L);
        when(uidGenerator.getUid()).thenAnswer(invocation -> sequence.incrementAndGet());
        ReflectionTestUtils.setField(store, "uidGenerator", uidGenerator);

        LongTermMemoryFact first = store.rememberExplicit("conv", 1L, "preferred_format", "用表格", 1L);
        LongTermMemoryFact second = store.rememberExplicit("conv", 1L, "preferred_format", "用条目", 2L);

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.lifecycle()).isEqualTo(LongTermMemoryFact.LIFECYCLE_ACTIVE);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getLifecycle()).isEqualTo(LongTermMemoryFact.LIFECYCLE_SUPERSEDED);
        assertThat(rows.get(0).getFactText()).isEqualTo("用表格");
        assertThat(rows.get(1).getFactText()).isEqualTo("用条目");
    }
}
