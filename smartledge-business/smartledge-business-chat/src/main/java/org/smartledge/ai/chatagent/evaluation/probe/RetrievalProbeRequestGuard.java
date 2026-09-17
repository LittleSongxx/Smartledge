package org.smartledge.ai.chatagent.evaluation.probe;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

@Component
public class RetrievalProbeRequestGuard {

    private static final long WINDOW_MILLIS = 60_000L;

    private final RetrievalProbeProperties properties;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    @Autowired
    public RetrievalProbeRequestGuard(RetrievalProbeProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RetrievalProbeRequestGuard(RetrievalProbeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new RetrievalProbeException("ENDPOINT_DISABLED", "Retrieval probe endpoint is disabled");
        }
    }

    public synchronized void acquire(String administrator) {
        String identity = administrator == null || administrator.isBlank() ? "anonymous" : administrator;
        long now = clock.millis();
        Window current = windows.get(identity);
        if (current == null || now - current.startedAt() >= WINDOW_MILLIS) {
            windows.put(identity, new Window(now, 1));
            return;
        }
        int limit = Math.max(1, properties.getRequestsPerMinute());
        if (current.count() >= limit) {
            throw new RetrievalProbeException("RATE_LIMIT_EXCEEDED", "Retrieval probe request rate limit exceeded");
        }
        windows.put(identity, new Window(current.startedAt(), current.count() + 1));
    }

    private record Window(long startedAt, int count) {
    }
}
