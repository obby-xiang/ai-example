package com.example.configmgr.task.service;

import com.example.configmgr.ai.config.RedisAvailability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * subscribe 失败 ⇒ 本实例降级为"仅本机订阅"，{@link TaskSseService} 会挂<b>一次性延迟重试</b>
 * （10s 退避，只对仍有本机 emitter 的任务；publish 路径同样可提前成功），不再只依赖 publish。
 * 容器自身带重连，Redis 恢复后订阅自动重建。
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

    /** 老的单测/手工装配（无可用性判定）。 */
    public RedisTaskEventBus(StringRedisTemplate redis, ObjectMapper objectMapper, String storeMode) {
        this(redis, objectMapper, storeMode, null);
    }

    @Autowired
    public RedisTaskEventBus(StringRedisTemplate redis, ObjectMapper objectMapper,
                             @Value("${app.task.sse.cross-instance-store:redis}") String storeMode,
                             RedisAvailability redisAvailability) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.redisMode = !"memory".equalsIgnoreCase(storeMode);
        this.redisAvailability = redisAvailability;
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
            log.warn("任务 SSE 跨实例发布失败 taskId={}（Redis 模板未装配；本机投递不受影响）", taskId);
            return false;
        }
        if (redisKnownDown()) {
            // 已知不可用：不碰 Redis 命令，作业事务线程不再按帧等命令超时
            this.publishFailures.incrementAndGet();
            log.debug("任务 SSE 跨实例发布跳过 taskId={}（已知 Redis 不可用，快速降级）", taskId);
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
            log.warn("任务 SSE 跨实例发布失败 taskId={}（本机投递不受影响；远端靠快照轮询兜底）：{}",
                    taskId, e.getMessage());
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
            log.warn("任务 SSE 跨实例订阅跳过 taskId={}（已知 Redis 不可用；本实例降级为仅本机订阅）", taskId);
            return false;
        }
        RedisMessageListenerContainer target = container();
        if (target == null) {
            this.subscribers.remove(taskId, onFrame);
            this.subscribeFailures.incrementAndGet();
            markKnownDown();
            log.warn("任务 SSE 跨实例订阅失败 taskId={}（无 Redis 连接或容器起不来；"
                    + "本实例降级为仅本机订阅，前端快照轮询兜底）", taskId);
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
            log.warn("任务 SSE 跨实例订阅失败 taskId={}（Redis 抖动；本实例降级为仅本机订阅）：{}",
                    taskId, e.getMessage());
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
            log.debug("任务 SSE 跨实例退订失败 taskId={}：{}", taskId, e.getMessage());
        }
    }

    /**
     * 收到一帧（容器回调的纯逻辑）：解析信封 → 丢弃本实例回声 → 交给本机订阅者。
     * <b>包内可见</b>供单测直呼（单测不起真 Redis；断言对象是分发语义，容器只是搬运工）。
     */
    void handleMessage(Long taskId, byte[] body) {
        if (body == null || body.length == 0) {
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
                log.warn("任务 SSE 跨实例信封缺少 frame 字段 taskId={}（丢弃该帧，快照轮询兜底）", taskId);
                return;
            }
            frame = frameNode.asText();
        } catch (Exception e) {
            log.warn("任务 SSE 跨实例帧解析失败 taskId={}（丢弃该帧，快照轮询兜底）：{}",
                    taskId, e.getMessage());
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
            log.warn("任务 SSE 跨实例帧投递给本机订阅者失败 taskId={}：{}", taskId, e.getMessage());
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
        body.put("subscribedTasks", this.subscribers.size());
        return body;
    }

    /** 应用关闭时停掉按需启动的订阅容器（守护线程不拖住 JVM 退出）。 */
    @PreDestroy
    void stop() {
        RedisMessageListenerContainer current = this.container;
        this.container = null;
        if (current != null) {
            try {
                current.destroy();
            } catch (Exception e) {
                log.debug("任务 SSE 跨实例容器关闭失败：{}", e.getMessage());
            }
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
            log.debug("无 Redis 连接工厂：任务 SSE 跨实例订阅退化为不订阅（单实例语义不受影响）");
            return null;
        }
        try {
            RedisMessageListenerContainer target = new RedisMessageListenerContainer();
            target.setConnectionFactory(factory);
            target.afterPropertiesSet();
            target.start();
            return target;
        } catch (Exception e) {
            log.warn("任务 SSE 跨实例容器启动失败（本实例仅本机订阅，前端快照轮询兜底）：{}", e.getMessage());
            return null;
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
