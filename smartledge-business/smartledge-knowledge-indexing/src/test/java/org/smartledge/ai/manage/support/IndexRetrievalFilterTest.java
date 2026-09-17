package org.smartledge.ai.manage.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndexRetrievalFilterTest {

    @Test
    @DisplayName("过期文档不能进入检索范围")
    void expiredDocumentIsNotRetrievable() {
        Instant now = Instant.parse("2026-09-17T15:00:00Z");
        assertThat(IndexRetrievalFilter.currentAndNotExpired(1, Instant.parse("2026-09-17T14:00:00Z"), now)).isFalse();
        assertThat(IndexRetrievalFilter.currentAndNotExpired(1, Instant.parse("2026-09-17T16:00:00Z"), now)).isTrue();
        assertThat(IndexRetrievalFilter.currentAndNotExpired(1, null, now)).isTrue();
        assertThat(IndexRetrievalFilter.currentAndNotExpired(0, null, now)).isFalse();
    }

    @Test
    @DisplayName("用户标量标签能在索引 payload 上挡住不匹配行")
    void userTagFilterBlocksMismatch() {
        Map<String, Object> payload = IndexRetrievalFilter.flattenUserScalars(Map.of(
            "dept", "hr",
            "year", 2024,
            "nested", Map.of("x", 1)
        ));
        assertThat(payload).containsEntry("user.dept", "hr").doesNotContainKey("user.nested");
        assertThat(IndexRetrievalFilter.matchesUserScalars(payload, Map.of("dept", "hr"))).isTrue();
        assertThat(IndexRetrievalFilter.matchesUserScalars(payload, Map.of("dept", "finance"))).isFalse();
        assertThat(IndexRetrievalFilter.matchesUserScalars(payload, Map.of("missing", "x"))).isFalse();
    }
}
