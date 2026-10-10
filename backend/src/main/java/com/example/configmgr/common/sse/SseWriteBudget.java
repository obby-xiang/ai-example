package com.example.configmgr.common.sse;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 写侧的<b>写超时预算</b>（包②核心机制；AI 通道 {@code ai/run/SseChatEmitter} 与
 * 任务通道 {@code task/service/TaskSseService} 共用同一份）。
 *
 * <h2>为什么需要它（实测坐实，本机 18330 实测）</h2>
 * {@code SseEmitter.send} 最终走到 Servlet 容器的<b>阻塞式</b>输出流写。Tomcat NIO 连接器对
 * HTTP/1.1 的阻塞写取 {@code SocketWrapperBase#getWriteTimeout()}；<b>写侧并非没有超时</b> ——
 * 钉住版本（tomcat-embed-core 10.1.54）的 {@code NioEndpoint#setSocketOptions} 会依次调
 * {@code setReadTimeout(getConnectionTimeout())} 与 {@code setWriteTimeout(getConnectionTimeout())}
 * （字节码实测），故写超时 = {@code server.tomcat.connection-timeout}（本配置 120000ms，
 * {@code application.yml} 第 6 行）：一条<b>由连接配置派生、与业务无关</b>的宽兜底
 * （实测表现：不读也不断开的客户端 ≈116s 后以 {@code SocketTimeoutException} 释放，正是这条
 * 120s 写超时生效）。jstack 只能看到
 * {@code NioSocketWrapper.doWrite(NioEndpoint.java:1450) → Object.wait}，<b>无法区分</b>
 * {@code wait()} 与带参 {@code wait(J)}；早期"落在无参分支 = 无限期阻塞"的判读已由字节码反证纠正。
 * 客户端<b>不读也不断开</b>（暂停的 DevTools / 代理挂起 / 停读页面）时，写线程被挂住，
 * 而调用它的线程可能是：投递池工作线程（AI 通道的 drain）、作业线程（任务通道 JOB_PROGRESS）、
 * 甚至<b>持有 DB 事务与连接</b>的请求线程（实测栈里该线程上方就是
 * {@code TransactionInterceptor.invokeWithinTransaction}）。
 *
 * <p>
 * <b>于是本包的价值不是"补一条不存在的超时"，而是三件事</b>：①把写侧释放预算从 120s（随连接
 * 配置漂移）收紧到 15s，并与业务预算同源；②把网络写挪出<b>锁内</b>（任务通道的
 * {@code synchronized(list)}）—— 单条慢连接不再让同任务的其他推送排队；③把写出挪出<b>投递池与
 * 调用线程</b>（投递池线程 / 作业线程 / 持事务的请求线程），使其占用有明确上界。
 *
 * <h2>为什么不能直接用 {@code SseEmitter(timeout)} 当写超时</h2>
 * {@code ResponseBodyEmitter.timeout} 是<b>整条响应</b>的生命周期上限（一次性 deadline，交给容器
 * {@code AsyncContext#setTimeout}），语义是"这条 SSE 流最多活多久"，不是"单帧最多写多久"——
 * 一条健康的长时间流会被它整条掐断，而一帧写阻塞却不会被它按帧约束。两者的正确分工是：
 * <ul>
 * <li><b>本类（每帧写预算）</b>：约束"单帧写出"的时长 ⇒ 调用线程最多等 {@code timeoutMillis}
 * 就能脱身，慢订阅者被摘除（主动断连）；</li>
 * <li><b>emitter 生命周期超时</b>：约束"整条流"的寿命（AI 通道 = 轮次总预算
 * {@code app.ai.resilience.total-budget}；任务通道 = {@code app.task.sse.emitter-timeout}），
 * 它<b>不是</b>写侧的释放手段 —— 本配置下两条生命周期（300s / 5m）都<b>晚于</b>上面那条 120s
 * 写超时，卡住的写一般先由 {@code SocketTimeoutException} 收场，生命周期超时只兜"流寿命"这一维。</li>
 * </ul>
 *
 * <h2>为什么必须把写出挪到别的线程</h2>
 * {@code ResponseBodyEmitter#send} 与 {@code #complete} <b>同为 {@code synchronized}</b>
 * （实测源码：Spring 6.2 {@code ResponseBodyEmitter.java:186/248}）。于是：
 * <ul>
 * <li>写阻塞时它<b>持有 emitter 监视器</b>；从别的线程 {@code complete()} 只会一起挂住
 * （不是"打断写出"的手段）——本类因此<b>不</b>尝试在超时后 complete，只把"写超时"这一事实
 * 交回调用方，由调用方摘除订阅者（不再投递），收尾则交给"卡住的那次写出返回时"的那一个线程；</li>
 * <li>线程中断对 socket 写无效（Tomcat 阻塞写把 {@code InterruptedException} 直接吞掉继续循环），
 * 故 {@link #write} 的 {@code future.cancel(true)} 只是尽力而为。</li>
 * </ul>
 *
 * <h2>线程占用口径（慢订阅者隔离的第二层）</h2>
 * 每次写出占用的是<b>本类的写出池</b>（{@link #sharedElasticWriterPool()}，弹性、守护、进程级）
 * 而不是调用线程所在的有界池：调用线程（投递池 / 作业线程 / HTTP 线程）最多被占用
 * {@code timeoutMillis}，池饱和度因此<b>不再由"慢连接数"决定</b>（修复前：{@code M ≥ 池容量}
 * 即全进程停投）。代价是"被卡住的那一次写"仍占 1 个写出池线程，上界 = 每条订阅者 1 个
 * （订阅者的 drain 是串行的），最终由客户端断开、容器写超时（{@code connection-timeout}=120s）、
 * emitter 生命周期超时（通常最晚）三者先到者释放。
 */
@Slf4j
public final class SseWriteBudget {

    /**
     * 单帧写出的缺省预算（毫秒，<b>单一事实源</b>）。
     *
     * <p>
     * 取值 15s 的依据：单帧上界是 T3-10 的 64KB（{@code app.ai.frame.max-bytes}），
     * T3-5 压测的本机状态帧 p99 写出成本 1.15ms，15s 是它的四个数量级；换算成带宽，
     * 即便按"每帧都是 64KB 上限帧"计，15s 仍容许约 4.4KB/s 的持续链路（真实 delta 帧多在
     * 百字节量级，余量更大），因此它筛掉的是"停读/挂起"的客户端，而不是"慢但仍在推进"的客户端。
     * 取值与任务通道对齐（{@code app.task.sse.write-timeout} 取同一缺省）。
     */
    public static final long DEFAULT_TIMEOUT_MILLIS = 15_000L;

    /** 累计写超时次数（进程级，供 {@code GET /api/ai/health} 的 sseDelivery 段）。 */
    private static final AtomicLong WRITE_TIMEOUTS = new AtomicLong();

    /** 写出线程编号（{@code sse-write-N}）。 */
    private static final AtomicLong WRITER_NO = new AtomicLong();

    /**
     * 进程级<b>弹性</b>写出池（幂等装配；一次 {@link #sharedElasticWriterPool()} 后复用）。
     *
     * <p>
     * 弹性（{@code corePoolSize=0} + {@code SynchronousQueue}）是刻意的：有界池会把"慢连接数"
     * 重新变成"停投阈值"，正是本包要消除的那条因果。上界由结构保证 —— 每个订阅者同时至多
     * 一次在飞写出（drain 独占），故线程峰值 ≈ 并发订阅者数；线程 60s 空闲即回收。
     */
    private static volatile ThreadPoolExecutor sharedWriterPool;

    /** 写出执行器；{@code null} = 直执（调用线程内写出，无预算 —— 单测/非 Spring 路径）。 */
    private final ExecutorService executor;

    private final long timeoutMillis;

    private SseWriteBudget(ExecutorService executor, long timeoutMillis) {
        this.executor = executor;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 直执预算（本预算不施加超时）：写出发生在调用线程内、异常原样交回调用方 —— 与修复前的行为逐字等价，
     * 供单测与非 Spring 装配路径保持既有确定性（口径同 {@code RunRegistry} 两参构造的"固定同步直执"）。
     */
    public static SseWriteBudget direct() {
        return new SseWriteBudget(null, 0L);
    }

    /** 带预算的写出（{@code executor} 非空；{@code timeoutMillis} 必须为正）。 */
    public static SseWriteBudget on(ExecutorService executor, long timeoutMillis) {
        if (executor == null) {
            return direct();
        }
        return new SseWriteBudget(executor, timeoutMillis > 0 ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS);
    }

    /** 进程级弹性写出池（幂等；装配一次后全进程复用）。 */
    public static synchronized ExecutorService sharedElasticWriterPool() {
        if (sharedWriterPool == null) {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), runnable -> {
                        Thread thread = new Thread(runnable, "sse-write-" + WRITER_NO.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    });
            pool.allowCoreThreadTimeOut(true);
            sharedWriterPool = pool;
        }
        return sharedWriterPool;
    }

    /** 本预算是否真的约束写出（false = 直执）。 */
    public boolean budgeted() {
        return this.executor != null;
    }

    /** 单帧写出的预算（毫秒）。 */
    public long timeoutMillis() {
        return this.timeoutMillis;
    }

    /** 累计写超时次数（进程级）。 */
    public static long writeTimeouts() {
        return WRITE_TIMEOUTS.get();
    }

    /**
     * 在预算内完成一次写出。
     *
     * <p>
     * 直执模式：调用线程内写出，异常即 {@link Outcome#FAILED}（语义与修复前一致）。
     * 预算模式：写出提交到写出池，调用线程最多等 {@code timeoutMillis}：
     * <ul>
     * <li>{@link Outcome#SENT}：写出成功；</li>
     * <li>{@link Outcome#FAILED}：写出抛异常（客户端断开 / 已完成的 emitter），异常交回调用方判处置；</li>
     * <li>{@link Outcome#TIMED_OUT}：超出预算 —— <b>本类不碰 emitter</b>（写线程仍持有其监视器），
     * 计数 + WARN，由调用方摘除维度处置（不再投递该订阅者；写出线程返回时自行收尾）；</li>
     * <li>{@link Outcome#INTERRUPTED}：调用线程被中断（已复位中断位并 {@code cancel(true)}）。</li>
     * </ul>
     */
    public Result write(SendTask task) {
        if (this.executor == null) {
            try {
                task.send();
                return Result.sent();
            }
            catch (Exception ex) {
                return Result.failed(ex);
            }
        }
        Future<?> future;
        try {
            future = this.executor.submit(() -> {
                try {
                    task.send();
                }
                catch (Exception ex) {
                    throw new WriteFailure(ex);
                }
            });
        }
        catch (RejectedExecutionException ex) {
            // 弹性池不设界，理论上到不了这里；留一条明确路径而不是让异常冒泡打断出帧流程。
            return Result.failed(ex);
        }
        try {
            future.get(this.timeoutMillis, TimeUnit.MILLISECONDS);
            return Result.sent();
        }
        catch (TimeoutException ex) {
            boolean cancelled = future.cancel(true);
            WRITE_TIMEOUTS.incrementAndGet();
            log.warn("SSE 写超时（>{}ms，取消任务={}）：写出线程仍卡在该帧，调用线程按预算脱身",
                    this.timeoutMillis, cancelled);
            return Result.timedOut();
        }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof WriteFailure failure) {
                return Result.failed(failure.failure);
            }
            return Result.failed(cause instanceof Exception exception ? exception
                    : new IllegalStateException("SSE 写出失败", cause));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Result.interrupted();
        }
    }

    /** 写出结果：成功 / 失败（带原因）/ 超时 / 调用线程被中断。 */
    public enum Outcome {
        SENT, FAILED, TIMED_OUT, INTERRUPTED
    }

    /**
     * 写出结果。
     *
     * <p>
     * {@code failure} 只在 {@link Outcome#FAILED} 时非空：写出线程抛出的原始异常
     * （{@code IOException} / {@code AsyncRequestNotUsableException} / 已完成 emitter 的
     * {@code IllegalStateException}），供调用方按既有口径记日志（不改变日志文本）。
     */
    public record Result(Outcome outcome, Exception failure) {

        static Result sent() {
            return new Result(Outcome.SENT, null);
        }

        static Result failed(Exception failure) {
            return new Result(Outcome.FAILED, failure);
        }

        static Result timedOut() {
            return new Result(Outcome.TIMED_OUT, null);
        }

        static Result interrupted() {
            return new Result(Outcome.INTERRUPTED, null);
        }
    }

    /** 一次写出任务（可抛受检异常的 {@code Runnable}）。 */
    @FunctionalInterface
    public interface SendTask {

        void send() throws Exception;
    }

    /** 把写出异常从 {@code ExecutionException} 里原样带回调用方（不包成其它类型）。 */
    private static final class WriteFailure extends RuntimeException {

        private final transient Exception failure;

        WriteFailure(Exception failure) {
            super(failure);
            this.failure = failure;
        }
    }
}
