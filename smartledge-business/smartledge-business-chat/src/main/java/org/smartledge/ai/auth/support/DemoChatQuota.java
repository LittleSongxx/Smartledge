package org.smartledge.ai.auth.support;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.smartledge.enums.BaseCode;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.stereotype.Component;

/**
 * 试用账号的提问配额（进程内、按用户）。
 *
 * <p>nginx 已按 IP 限制对话接口；这里再按账号限制，避免换 IP 刷模型额度。
 * 进程重启后计数清零，这是有意的：额度是防护而不是计费账本。</p>
 */
@Component
public class DemoChatQuota {

    static final int HOURLY_LIMIT = 20;

    static final int DAILY_LIMIT = 80;

    private final ConcurrentHashMap<Long, WindowCounter> hourly = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, WindowCounter> daily = new ConcurrentHashMap<>();

    public void consumeOrThrow(long userId) {
        WindowCounter hour = hourly.computeIfAbsent(userId, ignored -> new WindowCounter(Duration.ofHours(1)));
        WindowCounter day = daily.computeIfAbsent(userId, ignored -> new WindowCounter(Duration.ofDays(1)));
        synchronized (hour) {
            synchronized (day) {
                if (hour.count() >= HOURLY_LIMIT || day.count() >= DAILY_LIMIT) {
                    throw new SuperAgentFrameException(
                        BaseCode.PARAMETER_ERROR.getCode(),
                        PortfolioPermissions.QUOTA_MESSAGE
                    );
                }
                hour.increment();
                day.increment();
            }
        }
    }

    static final class WindowCounter {

        private final Duration window;

        private Instant startedAt = Instant.now();

        private int count;

        WindowCounter(Duration window) {
            this.window = window;
        }

        synchronized int count() {
            rewindIfExpired();
            return count;
        }

        synchronized void increment() {
            rewindIfExpired();
            count++;
        }

        private void rewindIfExpired() {
            Instant now = Instant.now();
            if (startedAt.plus(window).isBefore(now) || startedAt.plus(window).equals(now)) {
                startedAt = now;
                count = 0;
            }
        }
    }
}
