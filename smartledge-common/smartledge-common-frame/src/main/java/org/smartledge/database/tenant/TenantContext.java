package org.smartledge.database.tenant;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 当前请求/任务的租户上下文与认证主体快照。
 *
 * <p>由认证层在请求进入时写入，在 {@code finally} 中清理。用途有三：</p>
 * <ol>
 *   <li>MyBatis-Plus 的 {@code TenantLineInnerInterceptor} 从这里取租户 id 并重写 SQL；</li>
 *   <li>向量库的 RLS 会话变量也从这里取值；</li>
 *   <li>文档可见性解析从这里取认证主体（{@link RequestIdentity}），用于文档级 ACL 判定。</li>
 * </ol>
 *
 * <p>两种上下文必须区分：普通请求**必须**有租户，缺失即抛错（fail closed）；
 * 系统级任务（对账、构建调度等跨租户工作）显式声明 {@link #SYSTEM}，
 * 由拦截器跳过租户收窄。不区分的话只能给个默认租户，那等于所有后台任务都静默只操作默认租户。</p>
 *
 * <h2>为什么租户与身份放在同一个载体里</h2>
 *
 * <p>它们的传播语义完全相同：都是 {@link ThreadLocal}，都不跨线程，都需要在异步边界的提交点
 * 显式交给下一个线程。分开两个 ThreadLocal 会出现"租户传过去了、身份没传过去"的半个上下文，
 * 而那种状态的症状是可见性解析静默失败（fail closed 变成空集合），很难定位。因此本类只维护
 * **一个作用域**：{@code (tenantId, identity)}，所有原语一次搬运两者。</p>
 *
 * <h2>为什么不跨线程自动生效</h2>
 *
 * <p>{@link ThreadLocal} 不跨线程传播。RAG 主链路必然跨线程（Reactor 的
 * {@code boundedElastic}、{@code chat-rag-executor} 系列线程池、索引构建线程池），
 * 因此每个异步边界的**提交点**必须显式把当前上下文交给下一个线程，否则下游查询会因
 * 缺少上下文而失败（这是刻意的：拿不到租户就不允许构造查询，绝不用默认租户兜底）。</p>
 *
 * <p>本类提供三个层次的传播原语，全仓库只此一处实现：</p>
 * <ul>
 *   <li>{@link #runWith(Long, Runnable)} / {@link #callWith(Long, Supplier)}：在当前线程进入指定租户作用域，
 *       退出时恢复调用前的上下文，可安全嵌套。只声明租户时**身份被清除**：只有租户不足以做身份判定，
 *       沿用调用者身份会造成越权。</li>
 *   <li>{@link #runWith(RequestIdentity, Runnable)} / {@link #callWith(RequestIdentity, Supplier)}：进入指定主体
 *       作用域（租户取自该主体），用于「本轮请求的身份已经确定，下游要按它判定可见性」。</li>
 *   <li>{@link #propagating(Long, Runnable)} / {@link #propagating(Long, Callable)} /
 *       {@link #propagating(RequestIdentity, Runnable)} / {@link #propagating(RequestIdentity, Callable)}：
 *       把一个任务包装成「在指定作用域内执行」，供稍后交给线程池执行。</li>
 *   <li>{@link #propagating(ExecutorService)}：装饰线程池，在**提交点**捕获调用线程的上下文并在**任务内**恢复。
 *       必须装饰线程池而不是只在当前调用点包一次，因为线程池是共享的：任务实际执行的线程与提交线程无关。</li>
 * </ul>
 *
 * <p>传入 {@code null} 租户表示「明确不带上下文」：作用域内会清除上下文，让下游 fail closed，
 * 而不是悄悄沿用上一个请求的租户。</p>
 */
public final class TenantContext {

    /** 系统上下文标记：明确表示"不分租户收窄"，与"未设置"是两回事。 */
    public static final long SYSTEM = -1L;

    /** 默认租户 id，与迁移脚本的 DEFAULT 1 一致，供无租户语义的初始化路径使用。 */
    public static final long DEFAULT_TENANT_ID = 1L;

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        apply(tenantId, null);
    }

    public static void setSystem() {
        apply(SYSTEM, null);
    }

    /**
     * 写入认证主体，同时把租户设为该主体的租户。
     *
     * <p>租户只有这一个来源：主体自带租户，因此不存在"主体与租户不一致"的中间状态。</p>
     */
    public static void setIdentity(RequestIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        apply(identity.tenantId(), identity);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Long get() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.tenantId();
    }

    /** 当前认证主体；未认证（或只声明了租户）时返回 {@code null}。 */
    public static RequestIdentity getIdentity() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.identity();
    }

    public static boolean isSystem() {
        Long current = get();
        return current != null && current == SYSTEM;
    }

    public static boolean isPresent() {
        return CURRENT.get() != null;
    }

    /**
     * 在指定租户作用域内执行一段逻辑，退出时恢复调用前的上下文（可嵌套）。
     *
     * <p>作用域内不带认证主体：只有租户值时无法表达"是谁"，下游需要身份的地方会 fail closed。</p>
     *
     * @param tenantId 租户 id；{@code null} 表示作用域内不带租户上下文
     */
    public static void runWith(Long tenantId, Runnable action) {
        Objects.requireNonNull(action, "action");
        Scope previous = CURRENT.get();
        try {
            apply(tenantId, null);
            action.run();
        }
        finally {
            restore(previous);
        }
    }

    /**
     * 在指定租户作用域内执行一段逻辑并返回结果，退出时恢复调用前的上下文（可嵌套）。
     *
     * @param tenantId 租户 id；{@code null} 表示作用域内不带租户上下文
     */
    public static <T> T callWith(Long tenantId, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        Scope previous = CURRENT.get();
        try {
            apply(tenantId, null);
            return action.get();
        }
        finally {
            restore(previous);
        }
    }

    /**
     * 在指定认证主体作用域内执行一段逻辑，退出时恢复调用前的上下文（可嵌套）。
     *
     * <p>租户取自该主体，因此调用方不需要（也不允许）单独再传租户。</p>
     */
    public static void runWith(RequestIdentity identity, Runnable action) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(action, "action");
        Scope previous = CURRENT.get();
        try {
            apply(identity.tenantId(), identity);
            action.run();
        }
        finally {
            restore(previous);
        }
    }

    /** 在指定认证主体作用域内执行并返回结果，退出时恢复调用前的上下文（可嵌套）。 */
    public static <T> T callWith(RequestIdentity identity, Supplier<T> action) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(action, "action");
        Scope previous = CURRENT.get();
        try {
            apply(identity.tenantId(), identity);
            return action.get();
        }
        finally {
            restore(previous);
        }
    }

    /**
     * 包装成「在指定租户作用域内执行」的任务，供稍后提交给线程池。
     *
     * <p>与 {@link #runWith(Long, Runnable)} 的区别：这里是**先包装、后执行**，
     * 因此不需要在提交点额外包一层。</p>
     */
    public static Runnable propagating(Long tenantId, Runnable action) {
        Objects.requireNonNull(action, "action");
        return () -> runWith(tenantId, action);
    }

    /**
     * 包装成「在指定租户作用域内执行」的调用，供稍后提交给线程池；保留受检异常语义。
     */
    public static <T> Callable<T> propagating(Long tenantId, Callable<T> action) {
        Objects.requireNonNull(action, "action");
        return () -> {
            Scope previous = CURRENT.get();
            try {
                apply(tenantId, null);
                return action.call();
            }
            finally {
                restore(previous);
            }
        };
    }

    /**
     * 包装成「在指定认证主体作用域内执行」的任务，供稍后提交给线程池。
     *
     * <p>需要按身份判定可见性（文档 ACL、会话归属）的异步工作必须用这个重载，
     * 只传租户会让身份丢失并 fail closed。</p>
     */
    public static Runnable propagating(RequestIdentity identity, Runnable action) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(action, "action");
        return () -> runWith(identity, action);
    }

    /** 包装成「在指定认证主体作用域内执行」的调用，供稍后提交给线程池；保留受检异常语义。 */
    public static <T> Callable<T> propagating(RequestIdentity identity, Callable<T> action) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(action, "action");
        return () -> {
            Scope previous = CURRENT.get();
            try {
                apply(identity.tenantId(), identity);
                return action.call();
            }
            finally {
                restore(previous);
            }
        };
    }

    /**
     * 装饰线程池：提交点捕获调用线程的上下文（租户与身份），任务执行时恢复，任务结束后清除。
     *
     * <p>提交点是唯一正确的捕获时机：任务可能被任意工作线程、在任意延迟之后执行，
     * 而"这次提交属于哪个租户与主体"只有提交线程知道。提交线程没有上下文时不注入任何值，
     * 任务仍按 fail closed 处理。</p>
     */
    public static ExecutorService propagating(ExecutorService delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new PropagatingExecutorService(delegate);
    }

    /** 在系统上下文下执行一段逻辑。会保存并恢复调用前的上下文，因此可以安全嵌套。 */
    public static <T> T callAsSystem(Supplier<T> action) {
        return callWith(SYSTEM, action);
    }

    /** 在系统上下文下执行，无返回值。 */
    public static void runAsSystem(Runnable action) {
        runWith(SYSTEM, action);
    }

    private static void apply(Long tenantId, RequestIdentity identity) {
        if (tenantId == null && identity == null) {
            CURRENT.remove();
        }
        else {
            CURRENT.set(new Scope(tenantId, identity));
        }
    }

    private static void restore(Scope previous) {
        if (previous == null) {
            CURRENT.remove();
        }
        else {
            CURRENT.set(previous);
        }
    }

    /** 一次请求/任务的作用域：租户 + 认证主体，两者一起传播、一起恢复。 */
    private record Scope(Long tenantId, RequestIdentity identity) {
    }

    /**
     * 提交点捕获、任务内恢复的线程池装饰器。
     *
     * <p>只重写 {@code execute}：{@code AbstractExecutorService} 的 {@code submit} /
     * {@code invokeAll} / {@code invokeAny} 都经由 {@code execute} 落到线程池，
     * 因此一个入口即可覆盖全部提交方式。</p>
     */
    private static final class PropagatingExecutorService extends AbstractExecutorService {

        private final ExecutorService delegate;

        private PropagatingExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            Scope captured = CURRENT.get();
            delegate.execute(() -> {
                Scope previous = CURRENT.get();
                try {
                    restore(captured);
                    command.run();
                }
                finally {
                    restore(previous);
                }
            });
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
