package com.example.configmgr.task.service;

import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.task.config.TaskEventDispatchConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * {@link TaskEventBus} 的 Redis pub/sub 实现：{@code PUBLISH task:events:<taskId> <信封>} +
 * 按需启动的 {@link RedisMessageListenerContainer}。
 *
 * <h2>信封与回声（防重复投递）</h2>
 * 发布载荷 = {@code {"origin":"<本实例>","frame":"<帧原文>"}}。Redis pub/sub 会把消息投给
 * <b>包括发布者自己</b>的所有订阅连接，而本实例的本地订阅者在 {@code publish} 里已经直投过一次，
 * 因此收侧按 {@code origin} 丢弃自己的回声 —— 一条连接只会收到一帧。
 *
 * <h2>懒启动</h2>
 * 容器在第一次 {@link #subscribe} 时才建并启动：没有任务订阅者的进程不建立订阅连接，
 * 也就不会因为 Redis 不可用而拖慢启动（与 {@code RedisFrameWakeBus} 同口径）。
 *
 * <h2>失败口径（明确降级，不丢状态）</h2>
 * publish 失败 ⇒ 本机投递已完成，远端订阅者少一帧：帧是状态投影，前端靠 EventSource 重连 +
 * 3s 快照轮询自愈；失败计数进 {@link #metrics()}，日志 WARN 一次一条，可观测但不抛。
 * subscribe 失败 ⇒ 本实例降级为"仅本机订阅"，{@link TaskSseService} 会挂<b>有界延迟重试</b>
 * （10/20/40/60s 退避，最多 5 次；只对仍有本机 emitter 的任务；publish 路径同样可提前成功），
 * 不再只依赖 publish。容器自身带重连，Redis 恢复后订阅自动重建。
 *
 * <h2>已知不可用：零命令快速降级（复审边界 3）</h2>
 * 复用 {@link RedisAvailability} 的缓存可用性结论：已知 DOWN 时 {@code publish}/{@code subscribe}
 * <b>直接失败降级</b>（计入 {@code publishFailures}/{@code subscribeFailures}，连
 * {@code convertAndSend} 与订阅容器启动都不做），作业事务线程不再按帧等命令超时；
 * 已知 DOWN 期间每 {@link RedisAvailability#NEGATIVE_TTL_MS} 才真探活一次以感知恢复。
 * 没有注入 {@code RedisAvailability} 的老装配（直接 {@code new} 的三参构造）行为不变。
 *
 * <h2>模式</h2>
 * {@code app.task.sse.cross-instance-store=redis}（生产默认）或 {@code memory}
 * （单实例/单测：publish 为成功空操作，subscribe 只登记）。memory 模式<b>不触碰 Redis</b>。
 */
@Slf4j
@Component
public class RedisTaskEventBus implements TaskEventBus {

    private static final String ORIGIN_FIELD = "origin";
    private static final String FRAME_FIELD = "frame";

    /** 执行器拒绝日志的 WARN 间隔（首次必打，其余按此节流；计数另见 {@link #metrics()}）。 */
    private static final long REJECTION_WARN_EVERY = 100L;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean redisMode;

    /** 本实例标识（信封 origin）：同 JVM 起两个总线实例也能区分（单测双实例语料）。 */
    private final String instanceId;

    /** 任务 → 本机订阅者（跨实例帧的落点；首订阅写、末退订删）。 */
    private final Map<Long, Consumer<String>> subscribers = new ConcurrentHashMap<>();

    /** 任务 → 已注册到容器的监听器（退订时要按实例移除）。 */
    private final Map<Long, MessageListener> listeners = new ConcurrentHashMap<>();

    private final AtomicLong publishedFrames = new AtomicLong();
    private final AtomicLong publishFailures = new AtomicLong();
    private final AtomicLong receivedFrames = new AtomicLong();
    private final AtomicLong subscribeFailures = new AtomicLong();
    private final AtomicLong dispatchFailures = new AtomicLong();
    /** 分发池拒绝（丢帧）累计；与 {@code dispatchFailures} 分开，便于区分"投递失败"与"池饱和"。 */
    private final AtomicLong dispatchRejections = new AtomicLong();
    /** 订阅池拒绝（已改为调用线程内联执行，不丢订阅）累计。 */
    private final AtomicLong subscriptionRejections = new AtomicLong();

    private volatile RedisMessageListenerContainer container;

    /**
     * Redis 可用性（<b>复用 {@code ai.config.RedisAvailability} 的缓存结论</b>）。
     * 允许为 null（老的单测/手工装配）：此时不做快速降级，行为与原来一致。
     */
    private final RedisAvailability redisAvailability;

    /** 本地已知 DOWN 窗口（来自 {@link RedisAvailability#isKnownUnavailable()} 或本类失败）。 */
    private volatile long knownDownUntilMs;

    /** 最近一次真探活的时刻（已知 DOWN 期间每 {@link RedisAvailability#NEGATIVE_TTL_MS} 才探一次）。 */
    private volatile long lastAvailabilityProbeAtMs;

    /**
     * 跨实例帧的分发执行器（<b>必须是有界池</b>）。
     *
     * <p>容器缺省的 {@code SimpleAsyncTaskExecutor} 是"每条消息一条非守护平台线程、无上限"，
     * 而分发体里是一次带 15s 预算的 SSE 写出 —— 慢连接一多就是无界线程创建。这里注入
     * {@code taskEventDispatchExecutor}（{@code TaskEventDispatchConfig}）。
     *
     * <p>手工装配（单测/非 Spring）传 {@code null}：此时容器启动前会自建一个<b>同样有界</b>的
     * 守护池（{@link #ownedDispatchExecutor}），不让任何路径落回无界缺省。
     */
    private final Executor dispatchExecutor;

    /**
     * 订阅/退订执行器（<b>必须与分发池分开，且自身有界</b>）。
     *
     * <p>容器缺省同样给 {@code subscriptionExecutor} 配 {@code SimpleAsyncTaskExecutor}
     * （每任务一条新线程）；而<strong>与分发池共用</strong>会把 subscribe/unsubscribe 排在
     * 15s 的慢写出后面，重连风暴下越重连越订阅不上。这里注入
     * {@code taskEventSubscriptionExecutor}（{@code TaskEventDispatchConfig}）。
     *
     * <p>手工装配传 {@code null}：容器启动前自建等价的有界守护池
     * （{@link #ownedSubscriptionExecutor}），不落回无界缺省、也不与派发池共用。
     */
    private final Executor subscriptionExecutor;

    /** 未注入 bean 时自有的有界池（容器首次启动时创建，{@link #stop()} 关闭）。 */
    private ThreadPoolExecutor ownedDispatchExecutor;

    /** 未注入 bean 时自有的有界订阅池（与派发池分开，{@link #stop()} 关闭）。 */
    private ThreadPoolExecutor ownedSubscriptionExecutor;

    /** 老的单测/手工装配（无可用性判定）。 */
    public RedisTaskEventBus(StringRedisTemplate redis, ObjectMapper objectMapper, String storeMode) {
        this(redis, objectMapper, storeMode, null, null, null);
    }

    /** 单测/手工装配（无 Spring 注入的执行器：启动时自建等价的有界守护池）。 */
    public RedisTaskEventBus(StringRedisTemplate redis, ObjectMapper objectMapper,
                             String storeMode, RedisAvailability redisAvailability) {
        this(redis, objectMapper, storeMode, redisAvailability, null, null);
    }

    @Autowired
    public RedisTaskEventBus(StringRedisTemplate redis, ObjectMapper objectMapper,
                             @Value("${app.task.sse.cross-instance-store:redis}") String storeMode,
                             RedisAvailability redisAvailability,
                             @Qualifier(TaskEventDispatchConfig.DISPATCH_EXECUTOR_BEAN) Executor dispatchExecutor,
                             @Qualifier(TaskEventDispatchConfig.SUBSCRIPTION_EXECUTOR_BEAN)
                             Executor subscriptionExecutor) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.redisMode = !"memory".equalsIgnoreCase(storeMode);
        this.redisAvailability = redisAvailability;
        this.dispatchExecutor = dispatchExecutor;
        this.subscriptionExecutor = subscriptionExecutor;
        this.instanceId = "taskbus-" + hostName() + "-pid" + ProcessHandle.current().pid()
                + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    @Override
    public boolean publish(Long taskId, String frameJson) {
        if (!redisMode) {
            return true; // 单实例：本机扇出已完成，没有远端可发
        }
        if (this.redis == null) {
            this.publishFailures.incrementAndGet();
            log.warn("Task SSE cross-instance publish failed, local delivery unaffected taskId={} reason=redis-template-missing", taskId);
            return false;
        }
        if (redisKnownDown()) {
            // 已知不可用：不碰 Redis 命令，作业事务线程不再按帧等命令超时
            this.publishFailures.incrementAndGet();
            log.debug("Task SSE cross-instance publish skipped, Redis known down taskId={}", taskId);
            return false;
        }
        try {
            String envelope = this.objectMapper.writeValueAsString(
                    Map.of(ORIGIN_FIELD, this.instanceId, FRAME_FIELD, frameJson));
            this.redis.convertAndSend(CHANNEL_PREFIX + taskId, envelope);
            this.publishedFrames.incrementAndGet();
            return true;
        } catch (Exception e) {
            this.publishFailures.incrementAndGet();
            markKnownDown();
            log.warn("Task SSE cross-instance publish failed, local delivery unaffected and remote falls back to snapshot polling taskId={}",
                    taskId, e);
            return false;
        }
    }

    @Override
    public boolean subscribe(Long taskId, Consumer<String> onFrame) {
        this.subscribers.put(taskId, onFrame);
        if (!redisMode) {
            return true;
        }
        if (redisKnownDown()) {
            // 已知不可用：不建/不启订阅容器（容器启动同样会等连接超时）
            this.subscribers.remove(taskId, onFrame);
            this.subscribeFailures.incrementAndGet();
            log.warn("Task SSE cross-instance subscribe skipped, Redis known down and local-only subscribe kept taskId={}",
                    taskId);
            return false;
        }
        RedisMessageListenerContainer target = container();
        if (target == null) {
            this.subscribers.remove(taskId, onFrame);
            this.subscribeFailures.incrementAndGet();
            markKnownDown();
            log.warn("Task SSE cross-instance subscribe failed (no Redis connection or container start failure), degraded to local-only taskId={}",
                    taskId);
            return false;
        }
        MessageListener listener = (Message message, byte[] pattern) ->
                handleMessage(taskId, message == null ? null : message.getBody());
        MessageListener previous = this.listeners.put(taskId, listener);
        try {
            if (previous != null) {
                target.removeMessageListener(previous);
            }
            target.addMessageListener(listener, new ChannelTopic(CHANNEL_PREFIX + taskId));
            return true;
        } catch (RuntimeException e) {
            this.listeners.remove(taskId, listener);
            this.subscribers.remove(taskId, onFrame);
            this.subscribeFailures.incrementAndGet();
            markKnownDown();
            log.warn("Task SSE cross-instance subscribe failed, degraded to local-only taskId={}",
                    taskId, e);
            return false;
        }
    }

    @Override
    public void unsubscribe(Long taskId) {
        this.subscribers.remove(taskId);
        MessageListener listener = this.listeners.remove(taskId);
        RedisMessageListenerContainer target = this.container;
        if (listener == null || target == null) {
            return;
        }
        try {
            target.removeMessageListener(listener);
        } catch (RuntimeException e) {
            log.debug("Task SSE cross-instance unsubscribe failed taskId={}", taskId, e);
        }
    }

    /**
     * 收到一帧（容器回调的纯逻辑）：解析信封 → 丢弃本实例回声 → 交给本机订阅者。
     * <b>包内可见</b>供单测直呼（单测不起真 Redis；断言对象是分发语义，容器只是搬运工）。
     */
    void handleMessage(Long taskId, byte[] body) {
        if (ArrayUtils.isEmpty(body)) {
            return;
        }
        final String frame;
        try {
            JsonNode node = this.objectMapper.readTree(body);
            String origin = node.path(ORIGIN_FIELD).asText("");
            if (this.instanceId.equals(origin)) {
                return; // 自己发的回声：本地已经直投过
            }
            JsonNode frameNode = node.get(FRAME_FIELD);
            if (frameNode == null || frameNode.isNull()) {
                log.warn("Task SSE cross-instance envelope missing frame field, frame dropped taskId={}", taskId);
                return;
            }
            frame = frameNode.asText();
        } catch (Exception e) {
            log.warn("Task SSE cross-instance frame unparseable, frame dropped taskId={}",
                    taskId, e);
            return;
        }
        Consumer<String> consumer = this.subscribers.get(taskId);
        if (consumer == null) {
            return; // 本实例最后一个订阅者刚断开
        }
        try {
            consumer.accept(frame);
            this.receivedFrames.incrementAndGet();
        } catch (RuntimeException e) {
            this.dispatchFailures.incrementAndGet();
            log.warn("Task SSE cross-instance frame dispatch failed taskId={}", taskId, e);
        }
    }

    /** 当前本机登记的任务订阅数（诊断/断言用）。 */
    int subscribedTasks() {
        return this.subscribers.size();
    }

    /** 本实例标识（信封 origin；诊断/断言用）。 */
    String origin() {
        return this.instanceId;
    }

    /** 降级可观测面：发布/订阅失败、收帧/投递失败计数。 */
    public Map<String, Object> metrics() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", this.redisMode ? "redis" : "memory");
        body.put("publishedFrames", this.publishedFrames.get());
        body.put("publishFailures", this.publishFailures.get());
        body.put("receivedFrames", this.receivedFrames.get());
        body.put("subscribeFailures", this.subscribeFailures.get());
        body.put("dispatchFailures", this.dispatchFailures.get());
        body.put("dispatchRejections", this.dispatchRejections.get());
        body.put("subscriptionRejections", this.subscriptionRejections.get());
        body.put("subscribedTasks", this.subscribers.size());
        return body;
    }

    /** 应用关闭时停掉按需启动的订阅容器与自有的两个执行器（守护线程不拖住 JVM 退出）。 */
    @PreDestroy
    void stop() {
        RedisMessageListenerContainer current = this.container;
        this.container = null;
        if (current != null) {
            try {
                current.destroy();
            } catch (Exception e) {
                log.debug("Task SSE cross-instance container shutdown failed", e);
            }
        }
        ThreadPoolExecutor ownedDispatch;
        ThreadPoolExecutor ownedSubscription;
        synchronized (this) {
            ownedDispatch = this.ownedDispatchExecutor;
            this.ownedDispatchExecutor = null;
            ownedSubscription = this.ownedSubscriptionExecutor;
            this.ownedSubscriptionExecutor = null;
        }
        if (ownedDispatch != null) {
            ownedDispatch.shutdownNow();
        }
        if (ownedSubscription != null) {
            ownedSubscription.shutdownNow();
        }
    }

    /**
     * 已知 Redis 不可用（快速降级，<b>不发命令</b>）：先看本地失败窗口，再看
     * {@link RedisAvailability#isKnownUnavailable()} 的缓存结论。
     *
     * <p>恢复感知：已知 DOWN 时每 {@link RedisAvailability#NEGATIVE_TTL_MS} 才真探活一次
     * （{@code isAvailable()}；在其负缓存窗口内本身也不会发命令），避免每帧探活/每帧等超时。
     */
    private boolean redisKnownDown() {
        RedisAvailability availability = this.redisAvailability;
        if (availability == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < this.knownDownUntilMs) {
            return true;
        }
        if (!availability.isKnownUnavailable()) {
            return false;
        }
        if (now - this.lastAvailabilityProbeAtMs < RedisAvailability.NEGATIVE_TTL_MS) {
            return true;
        }
        this.lastAvailabilityProbeAtMs = now;
        if (availability.isAvailable()) {
            this.knownDownUntilMs = 0L;
            return false;
        }
        this.knownDownUntilMs = now + RedisAvailability.NEGATIVE_TTL_MS;
        return true;
    }

    /** 本类最近一次命令失败：本地也记一个 DOWN 窗口，后续帧不再逐帧等超时。 */
    private void markKnownDown() {
        if (this.redisAvailability == null) {
            return;
        }
        this.knownDownUntilMs = System.currentTimeMillis() + RedisAvailability.NEGATIVE_TTL_MS;
    }

    private RedisMessageListenerContainer container() {
        RedisMessageListenerContainer current = this.container;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (this.container == null) {
                this.container = startContainer();
            }
            return this.container;
        }
    }

    private RedisMessageListenerContainer startContainer() {
        RedisConnectionFactory factory = this.redis == null ? null : this.redis.getConnectionFactory();
        if (factory == null) {
            log.debug("No Redis connection factory, cross-instance task SSE subscribe disabled");
            return null;
        }
        try {
            RedisMessageListenerContainer target = new RedisMessageListenerContainer();
            target.setConnectionFactory(factory);
            // 两个执行器各管一段、都显式有界（缺省两者都是 SimpleAsyncTaskExecutor：每任务一条
            // 无上限的平台线程）：
            //   taskExecutor         → 只跑消息分发（分发体会阻塞在 15s 的 SSE 写预算上）；
            //   subscriptionExecutor → 只跑 subscribe/unsubscribe（毫秒级），不能被慢写出饿死。
            target.setTaskExecutor(dispatchExecutor());
            target.setSubscriptionExecutor(subscriptionExecutor());
            target.afterPropertiesSet();
            target.start();
            return target;
        } catch (Exception e) {
            log.warn("Task SSE cross-instance container start failed, local-only subscribe", e);
            return null;
        }
    }

    /**
     * 分发执行器：优先用 Spring 注入的有界池；手工装配时自建等价的守护池。
     *
     * <p>套一层"拒绝即丢帧"的壳：队列满时不能把拒绝异常抛回 Redis 订阅线程（那会打断同连接上
     * 其它任务的投递）。丢帧计数 + 首次/每 100 次 WARN，不静默。
     */
    private Executor dispatchExecutor() {
        Executor injected = this.dispatchExecutor;
        if (injected != null) {
            return new DropFrameExecutor(injected, this.dispatchRejections, this.dispatchFailures);
        }
        synchronized (this) {
            if (this.ownedDispatchExecutor == null) {
                this.ownedDispatchExecutor = new ThreadPoolExecutor(2, 2, 60L, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(256),
                        runnable -> {
                            Thread thread = new Thread(runnable, "task-event-dispatch-own");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
            }
            return new DropFrameExecutor(this.ownedDispatchExecutor, this.dispatchRejections,
                    this.dispatchFailures);
        }
    }

    /**
     * 订阅/退订执行器：与分发池分开，注入的 bean 或自建守护池都保持有界。
     *
     * <p>拒绝时改为<b>调用线程内联执行</b>并计数/告警，不静默丢弃 subscribe/unsubscribe ——
     * 订阅任务被丢会让容器内部的等待悬挂，重连后也补不回来。
     */
    private Executor subscriptionExecutor() {
        Executor injected = this.subscriptionExecutor;
        if (injected != null) {
            return new InlineOnRejectExecutor(injected, this.subscriptionRejections);
        }
        synchronized (this) {
            if (this.ownedSubscriptionExecutor == null) {
                this.ownedSubscriptionExecutor = new ThreadPoolExecutor(1, 2, 60L, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(64),
                        runnable -> {
                            Thread thread = new Thread(runnable, "task-event-subscribe-own");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
            }
            return new InlineOnRejectExecutor(this.ownedSubscriptionExecutor, this.subscriptionRejections);
        }
    }

    /**
     * 有界分发池的壳：队列满 ⇒ 记一次拒绝（并计入 {@code dispatchFailures}）后丢帧。
     *
     * <p>丢帧不丢状态：帧是 at-most-once 的状态投影，前端 EventSource 重连 + 3s 快照轮询兜底。
     * 抛异常则会沿着容器回调打回 Redis 订阅线程，影响同连接上其它任务的帧。拒绝不静默：
     * 首次与每 {@value #REJECTION_WARN_EVERY} 次打 WARN，其余 DEBUG（计数始终可从 metrics 读到）。
     */
    private static final class DropFrameExecutor implements Executor {

        private final Executor delegate;
        private final AtomicLong rejections;
        private final AtomicLong failures;

        private DropFrameExecutor(Executor delegate, AtomicLong rejections, AtomicLong failures) {
            this.delegate = delegate;
            this.rejections = rejections;
            this.failures = failures;
        }

        @Override
        public void execute(Runnable task) {
            try {
                this.delegate.execute(task);
            } catch (RejectedExecutionException e) {
                // TaskRejectedException（ThreadPoolTaskExecutor）也继承自 RejectedExecutionException。
                long total = this.rejections.incrementAndGet();
                this.failures.incrementAndGet();
                if (total == 1 || total % REJECTION_WARN_EVERY == 0) {
                    log.warn("Task SSE dispatch pool saturated, frame dropped and remote replays by snapshot polling "
                            + "dispatchRejections={}", total, e);
                } else {
                    log.debug("Task SSE dispatch pool saturated, frame dropped dispatchRejections={}", total, e);
                }
            }
        }
    }

    /**
     * 有界订阅池的壳：队列满 ⇒ 计数/告警后在<b>调用线程内联执行</b>，不丢订阅操作。
     *
     * <p>不用 {@code CallerRunsPolicy}：它在执行器已关闭时会静默丢弃任务，订阅/退订被丢会让
     * 调用方（可能已持有容器内部锁或等待 future）悬挂；内联执行至少保证这次操作真的做完。
     */
    private static final class InlineOnRejectExecutor implements Executor {

        private final Executor delegate;
        private final AtomicLong rejections;

        private InlineOnRejectExecutor(Executor delegate, AtomicLong rejections) {
            this.delegate = delegate;
            this.rejections = rejections;
        }

        @Override
        public void execute(Runnable task) {
            try {
                this.delegate.execute(task);
            } catch (RejectedExecutionException e) {
                // TaskRejectedException（ThreadPoolTaskExecutor）也继承自 RejectedExecutionException。
                long total = this.rejections.incrementAndGet();
                if (total == 1 || total % REJECTION_WARN_EVERY == 0) {
                    log.warn("Task SSE subscription pool saturated, running subscribe/unsubscribe inline on caller "
                            + "subscriptionRejections={}", total, e);
                } else {
                    log.debug("Task SSE subscription pool saturated, running inline subscriptionRejections={}", total, e);
                }
                task.run();
            }
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
