package com.example.configmgr.task.service;

import com.example.configmgr.common.sse.SseWriteBudget;
import com.example.configmgr.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * （缺省 5m，与 AI 通道的 emitter 生命周期同量级；<b>启动期校验必须为正</b>，见
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
 */
@Slf4j
@Service
public class TaskSseService {

    private final ObjectMapper objectMapper;
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** 单帧写出的超时预算（包②；{@code app.task.sse.write-timeout}）。 */
    private final SseWriteBudget writeBudget;

    /** 订阅者生命周期超时（必须非 0；{@code app.task.sse.emitter-timeout}）。 */
    private final long emitterTimeoutMillis;

    public TaskSseService(ObjectMapper objectMapper, AppProperties appProperties) {
        this.objectMapper = objectMapper;
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

        // Send initial heartbeat
        sendEvent(taskId, emitter, "HEARTBEAT", Map.of());
        return emitter;
    }

    @EventListener
    public void onTaskChanged(TaskChangedEvent event) {
        publish(event.getTask().getId(), "TASK_CHANGED", Map.of(
                "taskId", event.getTask().getId(),
                "version", event.getTask().getVersion(),
                "currentStep", event.getTask().getCurrentStep(),
                "status", event.getTask().getStatus(),
                "summary", event.getSummary()
        ));
    }

    /**
     * 推送一条任务级事件（包②：锁内只快照、锁外带预算写出）。
     *
     * <p>
     * 序列化只做一次（修复前在"每个 emitter 的循环体内"各序列化一次，N 订阅者 = N 次 JSON 编码）。
     * 序列化失败即整体不推（记住口径：这是服务端自己的数据问题，不是某个订阅者的连接问题）。
     */
    public void publish(Long taskId, String type, Object data) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list == null) {
            return;
        }
        final String json;
        try {
            json = objectMapper.writeValueAsString(Map.of("type", type, "data", data));
        } catch (Exception e) {
            log.warn("SSE 帧序列化失败 taskId={} type={}（本帧未推送）: {}", taskId, type, e.getMessage());
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
            if (!sendFrame(emitter, json, "taskId=" + taskId + " type=" + type)) {
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
        }
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
     * 单 emitter 写出（包②写预算的唯一落点）。返回 false = 该 emitter 已不可用，应从推送表摘除。
     *
     * <p>
     * 两个调用点都按本契约处置：{@link #publish} 收集 {@code dead} 批量摘除（锁外、同键原子），
     * {@link #sendEvent} 首帧失败即 {@link #remove}。
     *
     * <p>
     * 超时（{@link SseWriteBudget.Outcome#TIMED_OUT}）与写出失败同判"不可用"并摘除：任务级帧
     * （JOB_PROGRESS / JOB_DONE / TASK_CHANGED）都是"状态投影"，前端 EventSource 重连后会重新拉
     * 快照（{@code job-watch} 的固定间隔轮询）与订阅（订阅时补发 HEARTBEAT），因此摘除是可自愈的；
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
            case INTERRUPTED -> true;
        };
    }

    private void remove(Long taskId, SseEmitter emitter) {
        // 原子收尾：元素摘掉后若表为空则连条目一起摘（修复前只删元素不删 key ⇒ 每个 taskId 永久留一个
        // 空 list）。与 subscribe 的 compute 同键互斥，故不会摘掉"刚挂上新订阅者"的表。
        emitters.computeIfPresent(taskId, (key, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
