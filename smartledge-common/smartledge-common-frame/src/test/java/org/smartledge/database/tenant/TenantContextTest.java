package org.smartledge.database.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户上下文传播原语的不变量测试。
 *
 * <p>对应缺陷 S21-L：租户上下文是 {@link ThreadLocal}，而 RAG 主链路必然跨线程
 * （Reactor {@code boundedElastic}、{@code chat-rag-executor} 系列线程池、索引构建线程池）。
 * 开关打开后第一个真实对话就抛 {@code MyBatisSystemException} ——
 * 上下文在第一个异步边界丢失，fail closed 生效。</p>
 *
 * <p>本测试锁定传播侧的三条不变量：作用域退出必须恢复调用前状态；
 * 提交点没有上下文时不得凭空造出租户；线程池装饰器必须覆盖 submit 与 execute 两种提交方式。</p>
 */
class TenantContextTest {

    @Test
    @DisplayName("作用域退出后恢复调用前的租户上下文，可以嵌套")
    void scopeRestoresPreviousContext() {
        TenantContext.set(7L);
        try {
            assertThat(TenantContext.callWith(9L, TenantContext::get)).isEqualTo(9L);
            assertThat(TenantContext.get()).isEqualTo(7L);

            TenantContext.runWith(TenantContext.SYSTEM, () ->
                assertThat(TenantContext.callWith(11L, TenantContext::get)).isEqualTo(11L));
            assertThat(TenantContext.isSystem()).isFalse();
            assertThat(TenantContext.get()).isEqualTo(7L);
        }
        finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("作用域内没有租户时，退出后不能把调用者的租户泄漏给下游")
    void scopeWithNullTenantHidesCallerContext() {
        TenantContext.set(7L);
        try {
            // 强转选择"不带租户"的重载：两个重载在 null 字面量上无法自动区分。
            assertThat(TenantContext.callWith((Long) null, TenantContext::get)).isNull();
            assertThat(TenantContext.get()).isEqualTo(7L);
        }
        finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("装饰后的线程池在提交点捕获租户，并在任务结束后不污染工作线程")
    void decoratedExecutorCarriesTenantFromSubmittingThread() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        ExecutorService executor = TenantContext.propagating(delegate);
        try {
            TenantContext.set(42L);
            assertThat(executor.submit(TenantContext::get).get()).isEqualTo(42L);
            assertThat(executor.invokeAll(List.<Callable<Long>>of(TenantContext::get)).get(0).get()).isEqualTo(42L);

            TenantContext.clear();
            // 同一个工作线程复用：提交点没有上下文时任务内必须没有上下文，不能残留上一个租户。
            assertThat(executor.submit(TenantContext::get).get()).isNull();
            assertThat(executor.submit(TenantContext::isPresent).get()).isFalse();
        }
        finally {
            TenantContext.clear();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("装饰后的线程池覆盖 execute 提交，且系统上下文按原值传播")
    void decoratedExecutorCarriesSystemContextOnExecute() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        ExecutorService executor = TenantContext.propagating(delegate);
        CountDownLatch captured = new CountDownLatch(1);
        AtomicReference<Long> observed = new AtomicReference<>();
        try {
            TenantContext.setSystem();
            executor.execute(() -> {
                observed.set(TenantContext.get());
                captured.countDown();
            });
            assertThat(captured.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(observed.get()).isEqualTo(TenantContext.SYSTEM);
        }
        finally {
            TenantContext.clear();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("显式声明的租户作用域优先于提交点传播的租户")
    void explicitScopeWinsOverPropagatedTenant() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        ExecutorService executor = TenantContext.propagating(delegate);
        AtomicReference<Long> observed = new AtomicReference<>();
        try {
            TenantContext.set(5L);
            executor.execute(TenantContext.propagating(11L, () -> observed.set(TenantContext.get())));
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(observed.get()).isEqualTo(11L);
        }
        finally {
            TenantContext.clear();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Callable 包装保留受检异常，不在传播层吞掉")
    void propagatingCallableKeepsCheckedException() {
        TenantContext.set(3L);
        try {
            Callable<Long> failing = TenantContext.propagating(3L, () -> {
                throw new InterruptedException("cancelled");
            });
            assertThatThrownBy(failing::call).isInstanceOf(InterruptedException.class);
        }
        finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("装饰器把 shutdown 等生命周期调用原样委托给被装饰线程池")
    void decoratorDelegatesLifecycleCalls() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        ExecutorService executor = TenantContext.propagating(delegate);
        assertThat(executor.isShutdown()).isFalse();
        executor.shutdown();
        assertThat(executor.isShutdown()).isTrue();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.isTerminated()).isTrue();
        assertThat(executor.shutdownNow()).isEmpty();
    }

    @Test
    @DisplayName("未设置租户的线程读取上下文返回 null，而不是默认租户")
    void unsetContextIsNull() {
        TenantContext.clear();
        assertThat(TenantContext.get()).isNull();
        assertThat(TenantContext.isPresent()).isFalse();
        assertThat(TenantContext.isSystem()).isFalse();
        assertThat(TenantContext.getIdentity()).isNull();
    }

    @Test
    @DisplayName("装饰后的线程池在提交点同时捕获租户与认证主体")
    void decoratedExecutorCarriesIdentityFromSubmittingThread() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        ExecutorService executor = TenantContext.propagating(delegate);
        RequestIdentity identity = identity(42L, 7L);
        try {
            TenantContext.setIdentity(identity);
            assertThat(executor.submit(TenantContext::getIdentity).get()).isEqualTo(identity);
            assertThat(executor.submit(TenantContext::get).get()).isEqualTo(42L);

            CountDownLatch captured = new CountDownLatch(1);
            AtomicReference<RequestIdentity> executed = new AtomicReference<>();
            executor.execute(() -> {
                executed.set(TenantContext.getIdentity());
                captured.countDown();
            });
            assertThat(captured.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executed.get()).isEqualTo(identity);

            // 同一工作线程复用：提交点没有主体时任务内不得残留上一个请求的主体。
            TenantContext.clear();
            assertThat(executor.submit(TenantContext::getIdentity).get()).isNull();
        }
        finally {
            TenantContext.clear();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("只声明租户的作用域内没有认证主体，退出后恢复调用者的主体")
    void tenantOnlyScopeHidesIdentity() {
        RequestIdentity identity = identity(7L, 3L);
        TenantContext.setIdentity(identity);
        try {
            assertThat(TenantContext.callWith(7L, TenantContext::getIdentity)).isNull();
            assertThat(TenantContext.callWith(9L, TenantContext::get)).isEqualTo(9L);
            assertThat(TenantContext.getIdentity()).isEqualTo(identity);
            assertThat(TenantContext.get()).isEqualTo(7L);
        }
        finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("主体作用域把租户取自主体，退出后同时恢复租户与主体")
    void identityScopeCarriesItsOwnTenant() {
        TenantContext.set(5L);
        RequestIdentity identity = identity(11L, 4L);
        try {
            TenantContext.runWith(identity, () -> {
                assertThat(TenantContext.get()).isEqualTo(11L);
                assertThat(TenantContext.getIdentity()).isEqualTo(identity);
            });
            assertThat(TenantContext.get()).isEqualTo(5L);
            assertThat(TenantContext.getIdentity()).isNull();
        }
        finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("显式包装的主体传播任务在无上下文的提交点也能带上身份")
    void propagatingIdentityTaskKeepsIdentityWhenSubmitterHasNone() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        ExecutorService executor = TenantContext.propagating(delegate);
        RequestIdentity identity = identity(21L, 8L);
        try {
            TenantContext.clear();
            executor.execute(TenantContext.propagating(identity, () -> {
                assertThat(TenantContext.getIdentity()).isEqualTo(identity);
                assertThat(TenantContext.get()).isEqualTo(21L);
            }));
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        finally {
            TenantContext.clear();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("系统上下文不带认证主体，主体也不会凭空继承给系统任务")
    void systemScopeHasNoIdentity() {
        RequestIdentity identity = identity(7L, 3L);
        TenantContext.setIdentity(identity);
        try {
            TenantContext.runAsSystem(() -> {
                assertThat(TenantContext.isSystem()).isTrue();
                assertThat(TenantContext.getIdentity()).isNull();
            });
            assertThat(TenantContext.getIdentity()).isEqualTo(identity);
        }
        finally {
            TenantContext.clear();
        }
    }

    private static RequestIdentity identity(Long tenantId, Long userId) {
        return new RequestIdentity(tenantId, userId, "user-" + userId,
            java.util.Set.of(1L, 2L), java.util.Set.of("chat:use", "document:read"));
    }
}
