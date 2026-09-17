package org.smartledge.ai.manage.config;

import org.smartledge.database.tenant.TenantContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文档管理后台任务线程池。消息监听器只负责触发任务，长耗时 RAG 构建在这里执行。
 *
 * <p>两个线程池都由 {@link TenantContext#propagating(ExecutorService)} 装饰：索引构建链路
 * 会在这里继续向下扇出（向量化批次、关键词增强、GraphRAG 批次），任务线程拿不到提交线程的
 * 租户上下文就会在查询业务表时 fail closed。构建任务自身仍显式声明
 * {@code TenantContext.setSystem()}（跨租户的后台工作），装饰器只负责让声明能传下去。</p>
 */
@Configuration
public class DocumentManageExecutorConfiguration {

    @Bean(name = "documentIndexBuildExecutorService", destroyMethod = "shutdown")
    public ExecutorService documentIndexBuildExecutorService(DocumentManageProperties properties) {
        AtomicInteger threadCounter = new AtomicInteger(1);
        DocumentManageProperties.IndexBuild indexBuild = properties.getIndexBuild();
        int poolSize = positiveOrDefault(indexBuild.getExecutorPoolSize(), 2);
        int queueCapacity = positiveOrDefault(indexBuild.getExecutorQueueCapacity(), 32);

        return TenantContext.propagating(new ThreadPoolExecutor(
            poolSize,
            poolSize,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("document-index-build-" + threadCounter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        ));
    }

    @Bean(name = "documentDatasetRaptorExecutorService", destroyMethod = "shutdown")
    public ExecutorService documentDatasetRaptorExecutorService() {
        AtomicInteger threadCounter = new AtomicInteger(1);
        return TenantContext.propagating(new ThreadPoolExecutor(
            1,
            1,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(16),
            runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("document-dataset-raptor-" + threadCounter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        ));
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }
}
