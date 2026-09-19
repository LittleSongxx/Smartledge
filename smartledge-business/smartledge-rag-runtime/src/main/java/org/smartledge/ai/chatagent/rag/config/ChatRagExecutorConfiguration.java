package org.smartledge.ai.chatagent.rag.config;

import org.smartledge.database.identity.IdentityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @description: 配置类
 * @author: Song
 **/

@Configuration
public class ChatRagExecutorConfiguration {

    /**
     * 三个线程池都由 {@link IdentityContext#propagating(ExecutorService)} 装饰。
     *
     * <p>租户上下文是 ThreadLocal，任务实际执行的线程与提交线程无关，因此传播只能在
     * 提交点捕获、在任务内恢复。装饰线程池而不是逐个提交点包装：这些池是共享的，
     * 任何一处新增提交都自动继承提交线程的租户；提交线程没有上下文时不注入任何值，
     * 下游仍然 fail closed。</p>
     */
    @Bean(name = "chatRagExecutorService", destroyMethod = "shutdown")
    public ExecutorService chatRagExecutorService() {

        return IdentityContext.propagating(newFixedThreadPool("chat-rag-executor-", 8, 256));
    }

    @Bean(name = "chatMemorySummaryExecutorService", destroyMethod = "shutdown")
    public ExecutorService chatMemorySummaryExecutorService() {

        return IdentityContext.propagating(newFixedThreadPool("chat-memory-summary-", 2, 32));
    }

    @Bean(name = "chatPostProcessExecutorService", destroyMethod = "shutdown")
    public ExecutorService chatPostProcessExecutorService() {
        return IdentityContext.propagating(newFixedThreadPool("chat-post-process-", 2, 64));
    }

    private ExecutorService newFixedThreadPool(String threadNamePrefix, int poolSize, int queueCapacity) {
        AtomicInteger threadCounter = new AtomicInteger(1);

        return new ThreadPoolExecutor(
            poolSize,
            poolSize,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName(threadNamePrefix + threadCounter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
