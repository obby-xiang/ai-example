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
 * <li><b>emitter 生命周期超时</b>：约束"整条流"的寿命（任务通道 = {@code app.task.sse.emitter-timeout}；
 * AI 通道<b>不设</b>传输层死线 —— {@code new SseEmitter(-1L)}，因为确认门/表单等人工输入没有上限），
 * 它<b>不是</b>写侧的释放手段 —— 任务通道的 5m 生命周期<b>晚于</b>上面那条 120s
 * 写超时，卡住的写一般先由 {@code SocketTimeoutException} 收场；AI 通道则只剩客户端断开、
 * 120s 容器写超时与本类 15s 预算三条释放路径。</li>
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
 * 每次写出占用的是<b>本类的写出池</b>（{@link #sharedElasticWriterPool()}，有界弹性、守护、进程级）
 * 而不是调用线程所在的有界池：调用线程（投递池 / 作业线程 / HTTP 线程）最多被占用
 * {@code timeoutMillis}，池饱和度因此<b>不再由"慢连接数"决定</b>（修复前：{@code M ≥ 池容量}
 * 即全进程停投）。代价是"被卡住的那一次写"仍占 1 个写出池线程（上界 = 每条订阅者 1 个，
 * 订阅者的 drain 是串行的），最终由客户端断开、容器写超时（{@code connection-timeout}=120s）、
 * emitter 生命周期超时（任务通道 5m，通常最晚；AI 通道无此层）三者先到者释放。
 *
 * <p><b>资源修复批</b>：上述"上界"的推导依赖"并发订阅者数"，而它有<b>没有硬界</b>的外部来源
 * （多实例 + 多连接 + 客户端不读也不断开）。因此本类的写出池再补一条<b>硬上限</b>
 * {@link #DEFAULT_MAX_WRITER_THREADS}：线程峰值 = {@code min(需要写出的订阅者数, 硬上限)}；
 * 达上限后提交被拒 ⇒ {@link Outcome#SATURATED} ⇒ 调用方摘除<b>触发本次写出的</b>订阅者
 * （牺牲"这一条新写"，不牺牲整机内存）。
 *
 * <h2>跨通道（评审 I-1：如实登记，本批不消除）</h2>
 * 本池<b>是两条通道共用的</b>：AI 通道（{@code ai/run/SseChatEmitter}）与任务通道
 * （{@code task/service/TaskSseService}）都调 {@link #sharedElasticWriterPool()}。于是饱和时的
 * 误伤面比"本通道的慢连接"更宽：
 * <ul>
 * <li><b>被摘者可能健康</b>：池满时无法判断谁慢 —— 占满线程的可能是别人的慢连接，而本次写出的
 * 调用方只是"来晚了"；它的连接因此被主动断开（前端重挂，帧从归档/台账补回）；</li>
 * <li><b>被摘者可能跨通道</b>：任务通道的慢连接把 256 条写线程占满时，AI 通道的健康订阅者
 * 也会被摘（反之亦然）。</li>
 * </ul>
 * <b>为什么本批不做隔离</b>（三个选项的成本/前提）：
 * <ol>
 * <li><b>按通道各建一池</b>：代码只需几行（各通道各取一个池实例），但必须先把 256 的预算在两条
 * 通道间切分 —— 切分比例需要真实并发/慢连接分布的数据（本仓无），拍一个比例等于把"资源上限"
 * 变成未经测量的猜测；且两池合计线程数若仍是 256，只是把误伤限制在同通道内（同通道内仍会误伤健康者）。</li>
 * <li><b>按通道预留额度</b>：需要池级配额/信号量，改动比"硬上限"大一个量级，且饱和判定变成
 * "通道配额 vs 全局配额"两维，观测面与排障成本同步上升。</li>
 * <li><b>饱和时短等/重试</b>：只是把概率挪小（且要在投递池线程上睡眠，反而伤其它 drain），
 * 不能消除误伤，且会把"确定性拒绝"变成"时序相关"。</li>
 * </ol>
 * 结论：本批保持<b>有界安全</b>（线程数硬上限、饱和必拒、调用方摘除 + 可重挂），并明确
 * <b>不宣称</b>"只摘慢订阅者"；跨通道隔离列为本层后续项（需要先有容量数据再定切分）。
 */
@Slf4j
public final class SseWriteBudget {

    /**
     * 单帧写出的缺省预算（毫秒，<b>单一事实源</b>）。
     *
     * <p>
     * 取值 15s 的依据：单帧上界是 T3-10 的 64KB（{@code app.ai.result-budget.ui.max-frame-bytes}），
     * T3-5 压测的本机状态帧 p99 写出成本 1.15ms，15s 是它的四个数量级；换算成带宽，
     * 即便按"每帧都是 64KB 上限帧"计，15s 仍容许约 4.4KB/s 的持续链路（真实 delta 帧多在
     * 百字节量级，余量更大），因此它筛掉的是"停读/挂起"的客户端，而不是"慢但仍在推进"的客户端。
     * 取值与任务通道对齐（{@code app.task.sse.write-timeout} 取同一缺省）。
     */
    public static final long DEFAULT_TIMEOUT_MILLIS = 15_000L;

    /** 累计写超时次数（进程级，供 {@code GET /api/ai/health} 的 sseDelivery 段）。 */
    private static final AtomicLong WRITE_TIMEOUTS = new AtomicLong();

    /**
     * 累计写池饱和次数（进程级：饱和 = 写线程已全部占用，<b>触发本次写出的订阅者</b>被摘除）。
     *
     * <p>注意语义边界（评审 I-1）：被摘者是"<b>最后拿不到写线程</b>"的那一条，
     * 它<b>可能完全健康</b>（它自己一次都没卡住，只是来晚了），也可能来自<b>另一条通道</b>
     * （见 {@link #sharedElasticWriterPool()} 的"跨通道"段）。本计数器统计的是"这种误伤发生了多少次"。
     */
    private static final AtomicLong WRITE_SATURATIONS = new AtomicLong();

    /**
     * 写出线程的<b>硬上限</b>（资源修复批）。
     *
     * <p>旧池的最大线程数是 {@link Integer#MAX_VALUE}：一条"不读也不断开"的连接会让写出线程卡到
     * 客户端断开 / 容器 120s 写超时 / 15s 预算先到，而并发订阅者数没有硬界 ⇒ 线程数可被外部拉高
     * （每个线程默认 1MB 栈，几百条慢连接就是几百 MB 常驻）。本值把"弹性"收成"有界弹性"：
     *
     * <ul>
     * <li>取值 256 = 投递池缺省（{@code app.ai.sse.delivery-pool-size} = 8）的 32 倍：
     * 正常负载下"同时在写的订阅者"远小于它（写是毫秒级，15s 预算只用来筛停读客户端）；</li>
     * <li>饱和时<b>不扩容</b>：本次写出直接判 {@link Outcome#SATURATED}，由调用方摘除
     * <b>触发本次写出的</b>订阅者。策略是「<b>最后无法获得写线程者</b>」——<b>不是</b>"只摘慢订阅者"：
     * 池满时谁也分不清哪条连接慢（占满线程的可能是别人的慢连接），被摘者可能是健康连接。
     * 代价是一次<b>重挂</b>（AI 通道带 {@code lastSeq} 差量补发、任务通道 EventSource 重连 + 快照轮询），
     * <b>不是丢帧</b>：帧都在归档/台账里。牺牲"这一条新写"而不是"整机内存"，是这一层的取舍。</li>
     * <li><b>跨通道</b>（评审 I-1）：本池<b>由 AI 通道与任务通道共用</b>（{@code SseChatEmitter} 与
     * {@code TaskSseService} 都取 {@link #sharedElasticWriterPool()}）。因此任务通道的慢连接占满写线程时，
     * 被摘的还可能是<b>另一条通道</b>的订阅者。消除这一类误伤需要"按通道隔离池"或"按通道预留额度"
     * （见类注"跨通道"段的评估）；本批<b>不做</b>，如实登记为已知代价。</li>
     * </ul>
     */
    public static final int DEFAULT_MAX_WRITER_THREADS = 256;

    /** 写出线程编号（{@code sse-write-N}）。 */
    private static final AtomicLong WRITER_NO = new AtomicLong();

    /**
     * 进程级<b>有界弹性</b>写出池（幂等装配；一次 {@link #sharedElasticWriterPool()} 后复用）。
     *
     * <p>形状：{@code corePoolSize = 0}（空闲即回收，线程 60s 超时）+ {@code maximumPoolSize =
     * } 硬上限（{@link #DEFAULT_MAX_WRITER_THREADS}）+ {@code SynchronousQueue}（不排队：排队的写
     * 等于"卡住的写排队继续占调用线程"，与本类的目的相反）+ {@code AbortPolicy}
     * （饱和立刻拒绝 ⇒ {@link Outcome#SATURATED}）。
     *
     * <p>为什么"弹性"仍是刻意的：有界<b>常驻</b>池会把"慢连接数"重新变成"停投阈值"。
     * 现在的上界结构是：每个订阅者同时至多一次在飞写出（drain 独占），
     * 因此线程峰值 = min(并发慢订阅者数, 硬上限)。
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
        return sharedWriterPool(DEFAULT_MAX_WRITER_THREADS);
    }

    /**
     * 进程级有界弹性写出池（幂等；首次调用者决定上限，之后复用同一个池）。
     *
     * @param maxThreads 硬上限（{@code <= 0} ⇒ {@link #DEFAULT_MAX_WRITER_THREADS}）
     */
    public static synchronized ExecutorService sharedWriterPool(int maxThreads) {
        int cap = maxThreads > 0 ? maxThreads : DEFAULT_MAX_WRITER_THREADS;
        if (sharedWriterPool == null) {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(0, cap, 60L, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), runnable -> {
                        Thread thread = new Thread(runnable, "sse-write-" + WRITER_NO.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            pool.allowCoreThreadTimeOut(true);
            sharedWriterPool = pool;
        }
        return sharedWriterPool;
    }

    /** 共享写出池的线程硬上限（未装配 ⇒ 缺省值；供 {@code /api/ai/health} 与断言）。 */
    public static int sharedWriterPoolMaxThreads() {
        ThreadPoolExecutor pool = sharedWriterPool;
        return pool == null ? DEFAULT_MAX_WRITER_THREADS : pool.getMaximumPoolSize();
    }

    /** 累计写超时次数（进程级）。 */
    public static long writeTimeouts() {
        return WRITE_TIMEOUTS.get();
    }

    /** 累计写池饱和次数（进程级）。 */
    public static long saturations() {
        return WRITE_SATURATIONS.get();
    }

    /** 本预算是否真的约束写出（false = 直执）。 */
    public boolean budgeted() {
        return this.executor != null;
    }

    /** 单帧写出的预算（毫秒）。 */
    public long timeoutMillis() {
        return this.timeoutMillis;
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
     * <li>{@link Outcome#SATURATED}：写出池已达硬上限（{@link #DEFAULT_MAX_WRITER_THREADS}）且无处排队 ——
     * 本次写出<b>没有发生、也不会发生</b>，调用方据此摘除触发本次写出的订阅者（慢订阅者摘除）；</li>
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
            // 硬上限 + SynchronousQueue + AbortPolicy ⇒ 池满即拒。这**不是**"客户端断开"
            // （旧实现把它折成 FAILED，语义与日志都会指向错误方向），而是"这条写挤不进来"：
            // 计数 + WARN，交回调用方摘除触发本次写出的那个订阅者。
            // 被摘者可能是<b>健康</b>连接、也可能来自<b>另一条通道</b>（池两通道共用）——
            // 策略是"最后无法获得写线程者"，不是"只摘慢订阅者"（见类注"跨通道"段）。
            WRITE_SATURATIONS.incrementAndGet();
            log.warn("SSE 写出池饱和（线程已达上限 {}，无法再接纳一条写出）：由调用方摘除本次订阅者"
                    + "（被摘者可能是健康连接或另一通道，代价是重挂而非丢帧）", sharedWriterPoolMaxThreads());
            return Result.saturated();
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

    /** 写出结果：成功 / 失败（带原因）/ 超时 / 池饱和 / 调用线程被中断。 */
    public enum Outcome {
        SENT, FAILED, TIMED_OUT, SATURATED, INTERRUPTED
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

        static Result saturated() {
            return new Result(Outcome.SATURATED, null);
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
