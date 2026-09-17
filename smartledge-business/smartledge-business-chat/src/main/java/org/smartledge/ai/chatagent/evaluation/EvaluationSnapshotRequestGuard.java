package org.smartledge.ai.chatagent.evaluation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-node fixed-window guard for the read-only snapshot export.
 *
 * <p>限流分桶按**已认证主体**计算。该接口不再是匿名可调用的：它需要管理端身份与
 * {@code observe:read} 权限，因此这里没有 anonymous 桶 —— 拿不到主体就拒绝，
 * 而不是把所有匿名调用合并进同一个桶。</p>
 */
@Component
public class EvaluationSnapshotRequestGuard {

    private static final long WINDOW_MILLIS = 60_000L;

    private final EvaluationSnapshotProperties properties;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    @Autowired
    public EvaluationSnapshotRequestGuard(EvaluationSnapshotProperties properties) {
        this(properties, Clock.systemUTC());
    }

    EvaluationSnapshotRequestGuard(EvaluationSnapshotProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new EvaluationSnapshotQueryException(
                "ENDPOINT_DISABLED",
                "Evaluation snapshot endpoint is disabled"
            );
        }
    }

    public synchronized void acquire(String administrator) {
        if (administrator == null || administrator.isBlank()) {
            // fail closed：没有可归属的主体就不放行，避免匿名请求共用同一个额度桶。
            throw new EvaluationSnapshotQueryException(
                "IDENTITY_REQUIRED",
                "Evaluation snapshot requires an authenticated administrator"
            );
        }
        String identity = administrator;
        long now = clock.millis();
        Window current = windows.get(identity);
        if (current == null || now - current.startedAt() >= WINDOW_MILLIS) {
            windows.put(identity, new Window(now, 1));
            return;
        }
        int limit = Math.max(1, properties.getRequestsPerMinute());
        if (current.count() >= limit) {
            throw new EvaluationSnapshotQueryException(
                "RATE_LIMIT_EXCEEDED",
                "Evaluation snapshot request rate limit exceeded"
            );
        }
        windows.put(identity, new Window(current.startedAt(), current.count() + 1));
    }

    private record Window(long startedAt, int count) {
    }
}
