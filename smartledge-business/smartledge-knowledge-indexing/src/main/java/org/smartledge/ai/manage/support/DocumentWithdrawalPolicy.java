package org.smartledge.ai.manage.support;

import java.time.Instant;
import java.util.List;

/**
 * 文档下线顺序：先让检索看不见，再动对象存储和硬删。
 */
public final class DocumentWithdrawalPolicy {

    public enum Phase {
        TOMBSTONE_SEARCHABLE_STORE,
        DELETE_OBJECT_BYTES,
        PURGE_ROWS
    }

    private DocumentWithdrawalPolicy() {
    }

    public static List<Phase> requiredOrder() {
        return List.of(
            Phase.TOMBSTONE_SEARCHABLE_STORE,
            Phase.DELETE_OBJECT_BYTES,
            Phase.PURGE_ROWS
        );
    }

    /**
     * 崩溃窗口：对象已经不在、行还在时，只要已墓碑就不得召回。
     */
    public static boolean stillRetrievable(Integer status, Instant expiresAt, Instant now, boolean objectBytesPresent) {
        return IndexRetrievalFilter.currentAndNotExpired(status, expiresAt, now);
    }
}
