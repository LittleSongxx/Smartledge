package org.smartledge.ai.manage.service.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.manage.config.ChunkEnrichmentExecutionProperties;
import org.smartledge.database.tenant.TenantContext;
import org.springframework.stereotype.Component;

/** Shared bounded model workers; only the coordinating thread merges returned values. */
@Slf4j
@Component
public final class ChunkEnrichmentBatchExecutor implements AutoCloseable {
    private final ThreadPoolExecutor pool;
    private final int concurrency;
    private final long batchTimeoutNanos;
    private final long documentBudgetNanos;
    private final long pollMillis;

    public ChunkEnrichmentBatchExecutor(ChunkEnrichmentExecutionProperties properties) {
        properties.validate();
        concurrency = properties.getDocumentConcurrency();
        batchTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(properties.getBatchTimeoutMillis());
        documentBudgetNanos = TimeUnit.MILLISECONDS.toNanos(properties.getDocumentBudgetMillis());
        pollMillis = properties.getPollMillis();
        AtomicInteger sequence = new AtomicInteger();
        pool = new ThreadPoolExecutor(properties.getWorkerThreads(), properties.getWorkerThreads(), 0,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(properties.getQueueCapacity()), action -> {
                    Thread worker = new Thread(action, "chunk-enrichment-" + sequence.incrementAndGet());
                    worker.setDaemon(true);
                    return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public <T, R> List<R> execute(Long documentId, Long taskId, List<T> batches, BatchCall<T, R> action) {
        long started = System.nanoTime();
        long deadline = started + documentBudgetNanos;
        // 批次任务跑在共享池上，池线程与调用线程无关：在协调线程（索引构建线程）上捕获租户，
        // 随每个批次任务携带。池本身不能装饰，因为取消逻辑依赖 FutureTask 的对象同一性（remove）。
        Long tenantId = TenantContext.get();
        List<Work<R>> active = new ArrayList<>();
        Map<Integer, R> accepted = new TreeMap<>();
        int next = 0;
        int finished = 0;
        int failed = 0;
        String stopReason = "completed";
        try {
            while (next < batches.size() || !active.isEmpty()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("chunk enrichment interrupted");
                }
                if (pool.isShutdown() || System.nanoTime() >= deadline) {
                    stopReason = pool.isShutdown() ? "shutdown" : "document_timeout";
                    break;
                }
                for (Iterator<Work<R>> iterator = active.iterator(); iterator.hasNext();) {
                    Work<R> work = iterator.next();
                    long now = System.nanoTime();
                    boolean expired = work.startedNanos > 0 && now - work.startedNanos >= batchTimeoutNanos;
                    if (!work.future.isDone() && !expired) {
                        continue;
                    }
                    iterator.remove();
                    finished++;
                    try {
                        if ((!work.future.isDone() && expired) || (work.finishedNanos > 0
                                && work.finishedNanos - work.startedNanos >= batchTimeoutNanos)) {
                            cancel(work.future);
                            failed++;
                            log.warn("关键词与问题增强批次超时，保留启发式值: documentId={}, taskId={}, batch={}", documentId, taskId,
                                    work.index + 1);
                        }
                        else {
                            R value = work.future.get();
                            if (value != null && System.nanoTime() < deadline && !pool.isShutdown()) {
                                accepted.put(work.index, value);
                            }
                        }
                    }
                    catch (ExecutionException | CancellationException failure) {
                        failed++;
                        log.warn("关键词与问题增强批次执行失败，保留启发式值: documentId={}, taskId={}, batch={}, causeType={}", documentId,
                                taskId, work.index + 1, failure.getCause() == null ? failure.getClass().getSimpleName()
                                        : failure.getCause().getClass().getSimpleName());
                    }
                    log.info("关键词与问题增强批次进度: documentId={}, taskId={}, completed={}/{}, failed={}, active={}, costMillis={}",
                            documentId, taskId, finished, batches.size(), failed, active.size(),
                            TimeUnit.NANOSECONDS.toMillis(now - started));
                }
                while (next < batches.size() && active.size() < concurrency && !pool.isShutdown()
                        && System.nanoTime() < deadline) {
                    T batch = batches.get(next);
                    Work<R> work = new Work<>(next);
                    work.future = new FutureTask<>(TenantContext.propagating(tenantId, () -> {
                        work.startedNanos = System.nanoTime();
                        try {
                            if (work.startedNanos >= deadline || Thread.currentThread().isInterrupted()) {
                                throw new InterruptedException("expired queued batch");
                            }
                            return action.call(batch);
                        }
                        finally {
                            work.finishedNanos = System.nanoTime();
                        }
                    }));
                    try {
                        pool.execute(work.future);
                        active.add(work);
                        next++;
                    }
                    catch (RejectedExecutionException saturated) {
                        // No caller-runs and no retained Future for work that the shared pool rejected.
                        break;
                    }
                }
                if (next < batches.size() || !active.isEmpty()) {
                    TimeUnit.MILLISECONDS.sleep(pollMillis);
                }
            }
        }
        catch (InterruptedException interrupted) {
            stopReason = "cancelled";
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Chunk enrichment cancelled", interrupted);
        }
        finally {
            active.forEach(work -> cancel(work.future));
            if (stopReason.equals("completed") && accepted.size() < batches.size()) {
                stopReason = "completed_with_fallback";
            }
            log.info(
                    "关键词与问题增强批次执行结束: documentId={}, taskId={}, status={}, completed={}/{}, failed={}, unfinished={}, costMillis={}",
                    documentId, taskId, stopReason, finished, batches.size(), failed, batches.size() - finished,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
        return new ArrayList<>(accepted.values());
    }

    private void cancel(FutureTask<?> future) {
        future.cancel(true);
        pool.remove(future);
    }

    @Override
    @PreDestroy
    public void close() {
        for (Runnable queued : pool.shutdownNow()) {
            if (queued instanceof Future<?> future) {
                future.cancel(true);
            }
        }
    }

    @FunctionalInterface
    public interface BatchCall<T, R> {
        R call(T batch) throws Exception;
    }

    private static final class Work<R> {
        private final int index;
        private volatile long startedNanos;
        private volatile long finishedNanos;
        private FutureTask<R> future;

        private Work(int index) {
            this.index = index;
        }
    }
}
