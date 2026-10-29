package com.example.configmgr.task.service;

import com.example.configmgr.common.sse.SseWriteBudget;
import com.example.configmgr.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 任务级别的 SSE 事件推送服务。
 *
 * <h2>包②：SSE 写侧超时预算（锁内只快照、锁外带预算写出）</h2>
 * 修复前的形态（本机 18330 实测坐实）：{@code publish} 在 {@code synchronized(list)} 内直接
 * {@code emitter.send(...)}，而该写是阻塞式 socket 写，写侧超时<b>只有容器兜底的一条</b> ——
 * 由 {@code server.tomcat.connection-timeout}（本配置 120s）派生（钉住版本 tomcat-embed-core
 * 10.1.54 的 {@code NioEndpoint#setSocketOptions} 会 {@code setWriteTimeout(getConnectionTimeout())}，
 * 字节码实测；实测表现：不读也不断开的客户端 ≈116s 才释放）。120s 是 15s 预算的 ≈8 倍宽，
 * 且随连接配置漂移。一条"不读也不断开"的客户端
 * 即可让该任务的 publish <b>连同调用线程一起停住</b>（实测：请求线程停在 send 且持
 * {@code SynchronizedRandomAccessList} 监视器；同任务的第二个 publish {@code BLOCKED (on object
 * monitor)} 等同一把锁；该线程上方还压着 {@code TransactionInterceptor} = 事务与 DB 连接一并占住）。
 *
 * <p>
 * 现形态：
 * <ol>
 * <li><b>锁内只快照</b>：{@code synchronized(list)} 内只 copy 一份 emitter 快照（外加一个
 * "空表即丢弃条目"的原子收尾），任何网络写都不在锁内 —— 单条慢连接不再让同任务的所有推送排队；</li>
 * <li><b>锁外带预算写出</b>：逐 emitter 经 {@link SseWriteBudget}（弹性写出池 + 15s 预算）写出，
 * 调用线程（作业线程 / 取消接口的 HTTP 线程）最多被占用一个预算就会脱身；超时/失败即摘除该
 * emitter（不再投递）并记日志；</li>
 * <li><b>非 0 生命周期超时</b>：{@code SseEmitter(0L)} → {@code app.task.sse.emitter-timeout}
 * （缺省 5m，任务通道自己的流寿命口径；AI 通道自 P1 收口起不设传输层死线 —— 确认门/表单等人
 * 没有上限；<b>启动期校验必须为正</b>，见
 * {@link AppProperties}）。0 的语义是"该 emitter 没有生命周期超时"，<b>不等于"没有任何释放手段"</b>
 * —— 上面那条 120s 容器写超时仍在，只是它随连接配置漂移、且通常比生命周期超时<b>更早</b>到；
 * 前端用 {@code EventSource}（断线自愈 + {@code job-watch} 快照轮询兜底），周期性重连可接受。</li>
 * </ol>
 *
 * <p>
 * 超时后<b>不</b>在调用线程 {@code emitter.complete()}：{@code send}/{@code complete} 同为
 * {@code synchronized}，被卡住的写仍持有 emitter 监视器，就地 complete 会把调用线程再挂住。
 * 该 emitter 已移出推送表，由它自己的生命周期（客户端断开 / 120s 写超时先把那次写打断 /
 * 5m 容器超时 → onError/onTimeout）收尾 —— 回调里的 {@link #remove} 幂等。
 *
 * <h2>多实例 job 接管批：跨实例扇出（{@link TaskEventBus}）</h2>
 * 作业跑在 A、浏览器连在 B 是常态，因此 {@code publish} 有两条路：
 * <ol>
 * <li><b>本机直投</b>：{@link #deliverJson}（写预算/摘除口径与包②完全一致）；</li>
 * <li><b>跨实例</b>：{@link TaskEventBus#publish} → 别的实例收到后<b>复用同一个</b>
 *     {@link #deliverJson} 落到它的订阅者。总线只搬"已序列化的帧原文"，是 at-most-once 通知；
 *     权威状态仍在数据库（前端 3s 快照轮询兜底），所以 Redis 抖动丢一帧<b>不丢状态</b>。</li>
 * </ol>
 * 订阅生命周期：<b>首订阅注册</b>（{@link TaskEventBus#subscribe}）、<b>末订阅退订</b>
 * （{@link TaskEventBus#unsubscribe}）；订阅失败时本机投递照常（明确降级），并由
 * <b>一次性延迟重试</b>（10s 退避，复用仓内受控调度器）与 publish 路径双向兜底，Redis 恢复后
 * 自动补上 —— 只对仍有本机 emitter 的任务重试，末订阅即取消，不残留任务。
 */
@Slf4j
@Service
public class TaskSseService {

    /** 跨实例订阅失败后的重试退避（publish 路径轻量重试，避免每帧都打 Redis）。 */
    private static final long REMOTE_SUBSCRIBE_RETRY_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final ObjectMapper objectMapper;
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** 单帧写出的超时预算（包②；{@code app.task.sse.write-timeout}）。 */
    private final SseWriteBudget writeBudget;

    /** 订阅者生命周期超时（必须非 0；{@code app.task.sse.emitter-timeout}）。 */
    private final long emitterTimeoutMillis;

    /** 跨实例传输（多实例 job 接管批）：本机直投之外的那条路。 */
    private final TaskEventBus eventBus;

    /** 已完成跨实例注册的任务（首订阅加入、末订阅移除）。 */
    private final Set<Long> remoteSubscribed = ConcurrentHashMap.newKeySet();

    /** 每个任务的订阅状态机锁（首订阅/末退订与回调并发时只做一次注册/退订）。 */
    private final ConcurrentMap<Long, SubscriptionLock> subscriptionLocks = new ConcurrentHashMap<>();

    /** 任务 → 下次允许重试跨实例订阅的时刻（可注入的纳秒时钟，生产 = {@code System.nanoTime}）。 */
    private final ConcurrentMap<Long, Long> remoteRetryAtNanos = new ConcurrentHashMap<>();

    /** 已挂起一次性延迟重试的任务（防重复排期；末订阅/成功即撤销）。 */
    private final Set<Long> retryPending = ConcurrentHashMap.newKeySet();

    /** 任务 → 已排期的重试句柄（末订阅时可取消，不残留任务）。 */
    private final ConcurrentMap<Long, ScheduledFuture<?>> retryFutures = new ConcurrentHashMap<>();

    /**
     * 退避判据的时钟（<b>最小测试接缝</b>）：生产 = {@link System#nanoTime()}；
     * 单测注入可控时钟，把"10s 退避"从真实等待里解放出来。
     */
    private final LongSupplier retryNanoClock;

    /**
     * 延迟重试的调度器（<b>复用仓内已有受控调度器</b>：{@code aiStreamWatchdog} 的 2 条守护线程，
     * 不新增线程、不新造无限线程池）。
     *
     * <p>为什么需要它：作业在 A、浏览器只订阅 B 时，<b>B 从不 publish</b>，原先"失败只在
     * publish 时重试"的路径永不发生，跨实例进度只剩快照轮询兜底。这里每个失败的注册挂<b>一次</b>
     * 一次性重试（10s 退避，失败再排下一次），只对仍有 emitter 的任务执行；末订阅即取消。
     * 直接 {@code new TaskSseService(...)} 的单测/非 Spring 装配传 {@code null}（保持 publish 重试口径）。
     */
    private final ScheduledExecutorService retryScheduler;

    /** 单测/非 Spring 装配：无跨实例传输（本机扇出照常）。 */
    public TaskSseService(ObjectMapper objectMapper, AppProperties appProperties) {
        this(objectMapper, appProperties, TaskEventBus.inProcess(), System::nanoTime, null);
    }

    /** 单测/非 Spring 装配：无延迟重试调度器（由 publish 路径兜底重试）。 */
    public TaskSseService(ObjectMapper objectMapper, AppProperties appProperties, TaskEventBus eventBus) {
        this(objectMapper, appProperties, eventBus, System::nanoTime, null);
    }

    /** Spring 生产装配：注入仓内既有受控调度器（一次性轻量重试，不新增线程）。 */
    @Autowired
    public TaskSseService(ObjectMapper objectMapper, AppProperties appProperties, TaskEventBus eventBus,
                          @Qualifier("aiStreamWatchdog") ScheduledExecutorService retryScheduler) {
        this(objectMapper, appProperties, eventBus, System::nanoTime, retryScheduler);
    }

    /** 包内可见：仅供单测注入退避时钟（不新增模块、不暴露配置）。 */
    TaskSseService(ObjectMapper objectMapper, AppProperties appProperties, TaskEventBus eventBus,
                   LongSupplier retryNanoClock) {
        this(objectMapper, appProperties, eventBus, retryNanoClock, null);
    }

    TaskSseService(ObjectMapper objectMapper, AppProperties appProperties, TaskEventBus eventBus,
                   LongSupplier retryNanoClock, ScheduledExecutorService retryScheduler) {
        this.objectMapper = objectMapper;
        this.eventBus = eventBus;
        this.retryNanoClock = retryNanoClock;
        this.retryScheduler = retryScheduler;
        AppProperties.Task.Sse sse = appProperties.getTask().getSse();
        this.writeBudget = SseWriteBudget.on(SseWriteBudget.sharedElasticWriterPool(),
                sse.getWriteTimeout().toMillis());
        this.emitterTimeoutMillis = sse.getEmitterTimeout().toMillis();
    }

    public SseEmitter subscribe(Long taskId) {
        SseEmitter emitter = new SseEmitter(this.emitterTimeoutMillis);
        // 同一 taskId 的挂/摘必须原子：computeIfPresent 的"空表即摘条目"与 computeIfAbsent 的挂载
        // 若各走各的，会出现"条目被摘掉的瞬间新订阅者挂在已摘除的 list 上"（订阅者永久失联）。
        emitters.compute(taskId, (key, list) -> {
            List<SseEmitter> target = list != null ? list : Collections.synchronizedList(new ArrayList<>());
            target.add(emitter);
            return target;
        });

        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(e -> remove(taskId, emitter));

        // 首订阅注册跨实例频道（已有订阅则幂等；失败只降级，不影响本机）。
        reconcileRemoteSubscription(taskId);

        // Send initial heartbeat
        sendEvent(taskId, emitter, "HEARTBEAT", Map.of());
        return emitter;
    }

    /**
     * 推送一条任务级事件（包②：锁内只快照、锁外带预算写出；多实例：本机 + 跨实例两条路）。
     *
     * <p>
     * 序列化只做一次（修复前在"每个 emitter 的循环体内"各序列化一次，N 订阅者 = N 次 JSON 编码），
     * 本机直投与跨实例发布共用这一份帧原文。
     *
     * <p>
     * <b>本机没有订阅者也必须发到总线</b>（多实例接管批）：作业在本实例、浏览器连在别的实例是
     * 常态，早退会让跨实例进度永远到不了。
     */
    public void publish(Long taskId, String type, Object data) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(Map.of("type", type, "data", data));
        } catch (Exception e) {
            log.warn("SSE 帧序列化失败 taskId={} type={}（本帧未推送）: {}", taskId, type, e.getMessage());
            return;
        }

        deliverJson(taskId, json, "taskId=" + taskId + " type=" + type);
        publishCrossInstance(taskId, json);
    }

    /**
     * 订阅时补发首帧（HEARTBEAT）：{@link #subscribe} 在挂表之后立即调一次。
     *
     * <p>
     * <b>消费 {@link #sendFrame} 的返回值</b>（2026-10-10 复核 S3-2 收口）：该方法的契约是
     * "false = 该 emitter 已不可用，应从推送表摘除"，{@link #publish} 照着做，而本方法原来把返回值
     * 丢掉了 —— 首帧写超时/失败不摘除时，该 emitter 会滞留推送表，其后每次 {@code publish} 都要
     * 陪跑它一份写预算等待，最长活到下一次重试摘除或 emitter 生命周期超时。现按同一口径摘除
     * （{@link #remove}，与 {@code onError}/{@code onTimeout} 同一出口，幂等）。
     *
     * <p>
     * <b>可达性诚实声明</b>：真实 {@code SseEmitter} 在容器
     * {@code initialize(handler)} 之前，{@code send} 只把帧放进 Spring 的 {@code earlySendAttempts}
     * 缓冲（字节码实测 {@code ResponseBodyEmitter#sendInternal}：{@code handler == null} 时只入集合），
     * 故生产路径上首帧 HEARTBEAT 通常在本方法内就是 {@code SENT}、不摘除；本处收口的是
     * "契约要求摘除、调用方却丢弃结论"这一口径缺口（写出真的失败/超时，或该 emitter 已被并发收尾时，
     * 按契约摘除并交由其自身生命周期收尾）。
     *
     * <p>
     * <b>包内可见（原 private）</b>：首帧失败这一判据无法从外部经 {@link #subscribe} 复现（见上，
     * 真实 emitter 首帧不失败），单测只能用桩件 emitter 直呼本方法，故不另开测试专用入口。
     *
     * <p>
     * 序列化失败仍只记 DEBUG、不动推送表：口径同 {@link #publish}（那是服务端自身的数据问题，
     * 不是某个订阅者的连接问题）。
     */
    void sendEvent(Long taskId, SseEmitter emitter, String type, Object data) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("type", type, "data", data));
            if (!sendFrame(emitter, json, "订阅首帧 HEARTBEAT taskId=" + taskId)) {
                remove(taskId, emitter);
            }
        } catch (Exception e) {
            log.debug("Failed to send SSE: {}", e.getMessage());
        }
    }

    /**
     * 把一帧原文扇出到本任务的本地订阅者（<b>本机直投与跨实例接收共用</b>）。
     *
     * <p>
     * 锁内只快照，锁外逐个带预算写出；写超时/失败的 emitter 在锁外批量摘除（同键原子，
     * 空表条目一并清理）。序列化已在调用方完成 —— 跨实例路径拿到的就是线上帧原文，
     * 不再二次编码（帧形态 {@code {"type":..,"data":..}} 不变）。
     */
    private void deliverJson(Long taskId, String json, String context) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list == null) {
            return;
        }

        // 锁内只快照：此后所有网络写都在锁外，单条慢连接不再把同任务的其它推送挡在锁上。
        List<SseEmitter> snapshot;
        synchronized (list) {
            snapshot = new ArrayList<>(list);
        }
        if (snapshot.isEmpty()) {
            return;
        }

        List<SseEmitter> dead = null;
        for (SseEmitter emitter : snapshot) {
            if (!sendFrame(emitter, json, context)) {
                if (dead == null) {
                    dead = new ArrayList<>();
                }
                dead.add(emitter);
            }
        }
        if (dead != null) {
            // 与 subscribe/remove 同键原子：摘元素的同时清掉空表条目（修复前只删元素不删 key，
            // 每个 taskId 永久留一个空 list）。
            List<SseEmitter> deadList = dead;
            emitters.computeIfPresent(taskId, (key, current) -> {
                current.removeAll(deadList);
                return current.isEmpty() ? null : current;
            });
            // 订阅者可能因写超时/失败全部摘除 ⇒ 该任务的跨实例频道要跟着退订。
            reconcileRemoteSubscription(taskId);
        }
    }

    /** 本机直投之外的跨实例发布；订阅曾降级时按退避重试注册（Redis 恢复后自动补上）。 */
    private void publishCrossInstance(Long taskId, String json) {
        if (emitters.containsKey(taskId) && !this.remoteSubscribed.contains(taskId)) {
            reconcileRemoteSubscription(taskId);
        }
        this.eventBus.publish(taskId, json);
    }

    /**
     * 订阅状态机：<b>首订阅注册、末订阅退订</b>（幂等、按任务串行）。
     *
     * <p>以"本地是否还有订阅者"为唯一意图来源，每次挂载/摘除后对齐一次 —— 挂/摘与回调并发时
     * 只会得到"已订阅"或"已退订"两种稳态，不会出现"退订把刚挂上的订阅者踢掉"。
     * 注册失败时记 {@code remoteRetryAtNanos} 退避，并挂一次<b>一次性延迟重试</b>
     * （{@link #scheduleRemoteRetry}，复用受控调度器；publish 路径仍可提前成功）。
     *
     * <h2>锁的回收（复审必修项 2 的竞态修正）</h2>
     * 旧实现在 {@code synchronized(lock)} <b>内部</b>就 {@code subscriptionLocks.remove(taskId, lock)}：
     * 锁从 map 上摘掉后，另一线程会立刻 {@code computeIfAbsent} 出一个<b>新锁</b>并与仍持旧锁的
     * 临界区并发进入 —— 退订/订阅可能交错，"刚注册的订阅"被在途退订吞掉。
     *
     * <p>现在改为<b>引用计数 + 临界区外回收</b>：进入前在 CHM {@code compute} 里把 {@code holders+1}
     * （与回收判定同一原子步），临界区结束、<b>退出 synchronized 之后</b>才 {@code holders-1}，
     * 只有 {@code holders == 0} 时摘锁。于是：
     * <ul>
     * <li>任何持有/等待该锁的线程都使 {@code holders > 0} ⇒ 锁不会被摘掉；</li>
     * <li>摘锁只发生在没有线程使用该锁时 ⇒ 新旧锁不可能同时进入临界区；</li>
     * <li>任务空闲后锁即回收（不是"永久保留所有 task 锁"）。</li>
     * </ul>
     */
    private void reconcileRemoteSubscription(Long taskId) {
        SubscriptionLock lock = this.subscriptionLocks.compute(taskId, (key, existing) -> {
            SubscriptionLock target = existing != null ? existing : new SubscriptionLock();
            target.holders++;
            return target;
        });
        try {
            synchronized (lock) {
                reconcileLocked(taskId);
            }
        } finally {
            this.subscriptionLocks.compute(taskId, (key, existing) -> {
                if (existing != lock) {
                    return existing;
                }
                lock.holders--;
                return lock.holders == 0 ? null : existing;
            });
        }
    }

    /** 调用方必须持有该任务的 {@link SubscriptionLock}。 */
    private void reconcileLocked(Long taskId) {
        boolean want = this.emitters.containsKey(taskId);
        boolean have = this.remoteSubscribed.contains(taskId);
        if (want && !have) {
            Long retryAt = this.remoteRetryAtNanos.get(taskId);
            if (retryAt != null && this.retryNanoClock.getAsLong() < retryAt) {
                return;
            }
            boolean subscribed = this.eventBus.subscribe(taskId,
                    frame -> deliverJson(taskId, frame, "跨实例 taskId=" + taskId));
            if (subscribed) {
                this.remoteSubscribed.add(taskId);
                this.remoteRetryAtNanos.remove(taskId);
                cancelRemoteRetry(taskId);
            } else {
                this.remoteRetryAtNanos.put(taskId,
                        this.retryNanoClock.getAsLong() + REMOTE_SUBSCRIBE_RETRY_NANOS);
                // 有界延迟重试：作业在 A、浏览器只订阅 B 时 B 不会 publish，必须由调度器兜底重试。
                scheduleRemoteRetry(taskId, REMOTE_SUBSCRIBE_RETRY_NANOS);
            }
        } else if (!want && have) {
            this.eventBus.unsubscribe(taskId);
            this.remoteSubscribed.remove(taskId);
            this.remoteRetryAtNanos.remove(taskId);
            cancelRemoteRetry(taskId);
        } else if (!want) {
            this.remoteRetryAtNanos.remove(taskId);
            cancelRemoteRetry(taskId);
        }
    }

    /**
     * 挂一次<b>一次性</b>延迟重试（已有待触发则不重复排期）。
     *
     * <p>只由注册失败触发；到期后只对"仍本机有 emitter"的任务执行一次
     * {@link #reconcileRemoteSubscription}（失败会再排下一次，节奏 ≥ 退避窗）。
     * 无调度器（直接构造）时退化为原有的 publish 路径重试。
     */
    private void scheduleRemoteRetry(Long taskId, long delayNanos) {
        if (this.retryScheduler == null) {
            return;
        }
        if (!this.retryPending.add(taskId)) {
            return;
        }
        try {
            ScheduledFuture<?> future = this.retryScheduler.schedule(
                    () -> runRemoteRetry(taskId), Math.max(0L, delayNanos), TimeUnit.NANOSECONDS);
            this.retryFutures.put(taskId, future);
        } catch (RuntimeException e) {
            // 调度器拒绝（关停中）：撤销排期标记，后续 publish 仍能重试
            this.retryPending.remove(taskId);
            log.debug("任务 SSE 跨实例注册重试排期失败 taskId={}：{}", taskId, e.getMessage());
        }
    }

    /** 延迟重试到期：只对仍有本机 emitter 的任务走一次既有状态机（末订阅后不残留）。 */
    private void runRemoteRetry(Long taskId) {
        this.retryPending.remove(taskId);
        this.retryFutures.remove(taskId);
        if (!this.emitters.containsKey(taskId)) {
            return; // 末订阅已断开：不重试、不留任务
        }
        if (this.remoteSubscribed.contains(taskId)) {
            return;
        }
        Long retryAt = this.remoteRetryAtNanos.get(taskId);
        long now = this.retryNanoClock.getAsLong();
        if (retryAt != null && now < retryAt) {
            // 早到（调度抖动/测试注入）：只补足剩余退避，不无条件 subscribe
            scheduleRemoteRetry(taskId, retryAt - now);
            return;
        }
        reconcileRemoteSubscription(taskId);
    }

    /** 撤销待触发的重试（订阅成功 / 末订阅断开）。 */
    private void cancelRemoteRetry(Long taskId) {
        this.retryPending.remove(taskId);
        ScheduledFuture<?> future = this.retryFutures.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /** 任务级订阅锁（{@code holders} = 持有/等待该锁的线程数；只在 CHM {@code compute} 内改）。 */
    private static final class SubscriptionLock {
        private int holders;
    }

    /**
     * 单 emitter 写出（包②写预算的唯一落点）。返回 false = 该 emitter 已不可用，应从推送表摘除。
     *
     * <p>
     * 两个调用点都按本契约处置：{@link #deliverJson} 收集 {@code dead} 批量摘除（锁外、同键原子），
     * {@link #sendEvent} 首帧失败即 {@link #remove}。
     *
     * <p>
     * 超时（{@link SseWriteBudget.Outcome#TIMED_OUT}）与写出失败同判"不可用"并摘除：任务级帧
     * （JOB_PROGRESS / JOB_DONE）都是"状态投影"，前端 EventSource 重连后会重新拉
     * 快照（{@code job-watch} 的 3s 兜底轮询）与订阅（订阅时补发 HEARTBEAT），因此摘除是可自愈的；
     * 而把调用线程拖在这里（作业线程 + 事务 + DB 连接）是不可自愈的。
     *
     * <p>
     * 中断（{@link SseWriteBudget.Outcome#INTERRUPTED}）不算不可用：那是<b>调用方</b>（作业线程被取消）
     * 的状态，与这条连接无关，保留订阅者。
     */
    private boolean sendFrame(SseEmitter emitter, String json, String context) {
        SseWriteBudget.Result result = this.writeBudget.write(() -> emitter.send(SseEmitter.event().data(json)));
        return switch (result.outcome()) {
            case SENT -> true;
            case FAILED -> {
                log.debug("Failed to send SSE（{}）: {}", context,
                        result.failure() == null ? "写出失败" : result.failure().getMessage());
                yield false;
            }
            case TIMED_OUT -> {
                log.warn("任务 SSE 写超时（>{}ms）摘除该订阅者（{}；前端 EventSource 重连 + 快照轮询自愈）",
                        this.writeBudget.timeoutMillis(), context);
                yield false;
            }
            // 写池饱和（硬上限，资源修复批）：本次写没发生，该连接这一帧挤不进来 ⇒ 同"不可用"处置
            // （摘除；前端 EventSource 重连 + 快照轮询自愈）。与超时分开记日志，便于区分
            // "这条连接自己卡住"与"写线程已被别的连接占满"。
            // 被摘者可能是健康连接、也可能来自 AI 通道（写池两通道共用，见 SseWriteBudget 类注"跨通道"段）。
            case SATURATED -> {
                log.warn("任务 SSE 写出池饱和，摘除该订阅者（{}；并发写出已达硬上限，被摘者未必是慢连接）", context);
                yield false;
            }
            case INTERRUPTED -> true;
        };
    }

    /**
     * 摘除一个订阅者（回调与首帧失败共用出口，幂等），并同步跨实例订阅状态
     * （末订阅 ⇒ 退订频道）。
     *
     * <p><b>包内可见</b>供单测驱动订阅生命周期（真实 emitter 的收尾回调不可从外部确定性触发）。
     */
    void remove(Long taskId, SseEmitter emitter) {
        // 原子收尾：元素摘掉后若表为空则连条目一起摘（修复前只删元素不删 key ⇒ 每个 taskId 永久留一个
        // 空 list）。与 subscribe 的 compute 同键互斥，故不会摘掉"刚挂上新订阅者"的表。
        emitters.computeIfPresent(taskId, (key, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
        reconcileRemoteSubscription(taskId);
    }
}
