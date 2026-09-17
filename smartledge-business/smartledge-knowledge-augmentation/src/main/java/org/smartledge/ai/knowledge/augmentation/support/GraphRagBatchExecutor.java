package org.smartledge.ai.knowledge.augmentation.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.smartledge.ai.knowledge.augmentation.config.GraphRagExecutionProperties;
import org.smartledge.database.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** One bounded pool per application instance; cancelled remote calls keep their slot until they exit. */
@Component
public final class GraphRagBatchExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final GraphRagExecutionProperties limits;
    private final LongSupplier monotonicNanos;

    @Autowired
    public GraphRagBatchExecutor(GraphRagExecutionProperties properties) {
        this(properties, System::nanoTime);
    }

    /** Injectable monotonic time keeps deadline tests independent of model and machine speed. */
    public GraphRagBatchExecutor(GraphRagExecutionProperties properties, LongSupplier monotonicNanos) {
        properties.validate();
        this.monotonicNanos = monotonicNanos;
        limits = new ObjectMapper().convertValue(properties, GraphRagExecutionProperties.class);
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(limits.getWorkerThreads(), limits.getWorkerThreads(), 0,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(limits.getQueueCapacity()), runnable -> {
                    Thread thread = new Thread(runnable, "graph-rag-batch-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public GraphRagExecutionProperties limits() {
        return new ObjectMapper().convertValue(limits, GraphRagExecutionProperties.class);
    }

    public long nanoTime() {
        return monotonicNanos.getAsLong();
    }

    public <T> FutureTask<T> submit(Callable<T> action) {
        // 批次任务跑在共享池上：在提交线程捕获租户并随任务携带。
        // 池本身不能装饰，因为取消逻辑依赖 FutureTask 的对象同一性（remove）。
        FutureTask<T> task = new FutureTask<>(TenantContext.propagating(TenantContext.get(), action));
        executor.execute(task);
        return task;
    }

    public void cancel(FutureTask<?> task) {
        task.cancel(true);
        executor.remove(task);
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow().forEach(task -> {
            if (task instanceof Future<?> future) {
                future.cancel(true);
            }
        });
    }
}
