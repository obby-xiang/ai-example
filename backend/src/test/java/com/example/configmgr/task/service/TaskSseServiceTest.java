package com.example.configmgr.task.service;

import com.example.configmgr.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包②：{@link TaskSseService} 写侧超时预算与"锁内只快照、锁外写出"的单测。
 *
 * <p>
 * 缺陷形态（本机 18330 实测坐实）：{@code publish} 在 {@code synchronized(list)} 内直接
 * {@code emitter.send(...)}，而该写是阻塞式 socket 写，写侧只有容器兜底那条超时 ——
 * 由 {@code server.tomcat.connection-timeout} 派生（本配置 120s，实测 ≈116s 才释放；
 * 2026-10-10 复核按 tomcat-embed-core 10.1.54 字节码更正口径，早期"无写超时"的说法已作废）。
 * 一条"不读也不断开"的
 * 客户端会让该任务的 publish 连同调用线程（作业线程 / 取消接口）一起停住，同任务的第二个 publish
 * 则 {@code BLOCKED (on object monitor)} 等同一把锁（实测 jstack：写线程持
 * {@code SynchronizedRandomAccessList} 且栈上方压着 {@code TransactionInterceptor}）。
 *
 * <ul>
 * <li>{@code subscribeUsesNonZeroEmitterTimeout}：{@code SseEmitter(0L)} 已改为非 0
 * （0 = 该 emitter 没有生命周期超时，<b>≠</b>"连接没有释放手段"：上面那条 120s 容器写超时仍在）；</li>
 * <li>{@code aStuckEmitterDoesNotBlockOtherPublishesForTheSameTask}：一条卡住的写不得让同任务的
 * 其它 publish 排队（修复前第二个 publish 会一直等锁）；</li>
 * <li>{@code writeTimeoutRemovesTheStuckEmitterAndPrunesTheEmptyEntry}：写超时 ⇒ 摘除该 emitter，
 * 且空表条目一并清理（原实现只删元素不删 key）；</li>
 * <li>{@code aFailedFirstFrameRemovesTheEmitterFromThePushTable}：订阅首帧写出失败 ⇒ 立即摘除该
 * emitter（{@code sendEvent} 消费 {@code sendFrame} 返回值，与 publish 同口径；S3-2 收口），
 * 而首帧正常的订阅者不受影响；</li>
 * <li>{@code taskFrameFormatIsUnchanged}：帧原文形态 {@code {"type":..,"data":..}} 不变（前端契约）。</li>
 * </ul>
 */
class TaskSseServiceTest {

    private static final long TASK_ID = 7L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 非 0 生命周期超时 ────────────────────────────────────────────────────

    @Test
    void subscribeUsesNonZeroEmitterTimeout() {
        AppProperties properties = new AppProperties();
        long configured = properties.getTask().getSse().getEmitterTimeout().toMillis();
        TaskSseService service = new TaskSseService(MAPPER, properties);

        SseEmitter emitter = service.subscribe(TASK_ID);

        assertThat(emitter.getTimeout()).as("SseEmitter(0L) = 容器永不超时（修复前）")
                .isNotNull()
                .isEqualTo(configured)
                .isGreaterThan(0L);
    }

    // ── 锁内只快照：一条慢连接不得让同任务的其它 publish 排队 ─────────────────

    @Test
    void aStuckEmitterDoesNotBlockOtherPublishesForTheSameTask() throws Exception {
        TaskSseService service = new TaskSseService(MAPPER, propertiesWithWriteTimeout(400L));
        BlockingEmitter stuck = new BlockingEmitter();
        RecordingEmitter healthy = new RecordingEmitter();
        List<SseEmitter> list = subscribersOf(service, TASK_ID);
        list.add(stuck);
        list.add(healthy);

        Thread first = new Thread(() -> service.publish(TASK_ID, "JOB_PROGRESS", Map.of("pct", 1)));
        first.setDaemon(true);
        first.start();
        assertThat(stuck.awaitFirstSend(5L)).as("第一条 publish 已卡在慢 emitter 的写出里").isTrue();

        // 第二条 publish：修复前它排在 synchronized(list) 上等第一条的写结束（慢连接 = 永不结束）；
        // 修复后锁内只快照，它在自己的写预算内返回。
        Thread second = new Thread(() -> service.publish(TASK_ID, "JOB_PROGRESS", Map.of("pct", 2)));
        second.setDaemon(true);
        long before = System.nanoTime();
        second.start();
        second.join(5_000L);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        assertThat(second.isAlive()).as("同任务的第二条 publish 不得被慢连接挡在锁上（修复前必挂）").isFalse();
        assertThat(elapsedMillis).as("第二条 publish 的耗时落在自己的写预算量级内").isLessThan(4_000L);
        assertThat(healthy.payloads()).as("健康订阅者照常收到帧").isNotEmpty();

        first.join(5_000L);
        stuck.release();
        assertThat(awaitListSize(list, 1, 3L)).as("卡住的 emitter 已被摘除（前端 EventSource 重连）").isTrue();
    }

    // ── 写超时：摘除 + 空表条目清理 ──────────────────────────────────────────

    @Test
    void writeTimeoutRemovesTheStuckEmitterAndPrunesTheEmptyEntry() throws Exception {
        TaskSseService service = new TaskSseService(MAPPER, propertiesWithWriteTimeout(300L));
        BlockingEmitter stuck = new BlockingEmitter();
        subscribersOf(service, TASK_ID).add(stuck);

        long before = System.nanoTime();
        service.publish(TASK_ID, "JOB_PROGRESS", Map.of("pct", 1));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        assertThat(elapsedMillis).as("调用线程按写预算脱身（不随阻塞写无限期挂住）").isLessThan(3_000L);
        assertThat(emittersOf(service)).as("写超时摘除最后一个订阅者后，空表条目一并清理（原实现只删元素）")
                .doesNotContainKey(TASK_ID);
        stuck.release();
    }

    // ── 首帧失败：sendEvent 消费返回值并摘除（2026-10-10 复核 S3-2 收口） ───────

    /**
     * 订阅首帧写出失败 ⇒ 该订阅者立即从推送表摘除（修复前 {@code sendEvent} 丢弃
     * {@code sendFrame} 的返回值，坏 emitter 会滞留到下次 publish 重试或 emitter 生命周期超时，
     * 期间每次 publish 都要陪跑它一份写预算等待）。
     *
     * <p>
     * 判据拆两半，防"守卫过严"与"守卫失效"两个方向：失败的摘除、正常的留下（对照组走公开路径
     * {@link TaskSseService#subscribe}，证明正常首帧确实不摘除）。首帧失败本身只能靠桩件复现
     * —— 真实 {@code SseEmitter} 在容器 {@code initialize(handler)} 之前把帧入
     * {@code earlySendAttempts} 缓冲、不会失败。
     */
    @Test
    void aFailedFirstFrameRemovesTheEmitterFromThePushTable() throws Exception {
        TaskSseService service = new TaskSseService(MAPPER, propertiesWithWriteTimeout(2_000L));

        SseEmitter subscribed = service.subscribe(TASK_ID);
        FailingEmitter unreachable = new FailingEmitter();
        subscribersOf(service, TASK_ID).add(unreachable);
        assertThat(subscribersOf(service, TASK_ID)).as("桩件已挂上推送表（前置条件）").hasSize(2);

        service.sendEvent(TASK_ID, unreachable, "HEARTBEAT", Map.of());

        assertThat(awaitListSize(subscribersOf(service, TASK_ID), 1, 3L))
                .as("首帧失败的 emitter 已摘除（修复前：返回值被丢弃 ⇒ 滞留推送表）").isTrue();
        assertThat(subscribersOf(service, TASK_ID)).as("首帧正常的订阅者不受影响（守卫不得过严）")
                .containsExactly(subscribed);
    }

    // ── 帧形态不变（前端契约） ───────────────────────────────────────────────

    @Test
    void taskFrameFormatIsUnchanged() throws Exception {
        TaskSseService service = new TaskSseService(MAPPER, propertiesWithWriteTimeout(2_000L));
        RecordingEmitter healthy = new RecordingEmitter();
        subscribersOf(service, TASK_ID).add(healthy);

        service.publish(TASK_ID, "TASK_CHANGED", Map.of("taskId", TASK_ID, "status", "ACTIVE"));

        assertThat(healthy.awaitPayloads(1, 5L)).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = MAPPER.readValue(healthy.payloads().get(0), Map.class);
        assertThat(parsed).as("帧体形态不变：{\"type\":..,\"data\":..}").containsEntry("type", "TASK_CHANGED");
        assertThat(parsed.get("data"))
                .as("data 原样透传（前端契约）")
                .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) parsed.get("data");
        assertThat(data).containsEntry("taskId", 7).containsEntry("status", "ACTIVE");
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private static AppProperties propertiesWithWriteTimeout(long writeTimeoutMillis) {
        AppProperties properties = new AppProperties();
        properties.getTask().getSse().setWriteTimeout(Duration.ofMillis(writeTimeoutMillis));
        return properties;
    }

    /**
     * 白盒挂载/取用推送表。
     *
     * <p>
     * 为什么要白盒：{@code subscribe(...)} 造的是真实 {@code SseEmitter}，其写出要等容器
     * {@code initialize(handler)}（Spring 的包内方法，单测无法调用）才会真正落 socket；
     * 要复现"写出被卡住"只能挂一个可控桩件。断言面（推送表条目生命周期）同样没有对外可观测面 ——
     * 理由与 {@code SseChatEmitterDeliveryTest#fieldOf} 同：为断言另造一条等价实现，等于把断言
     * 写在测试自己的假设上。
     */
    @SuppressWarnings("unchecked")
    private static List<SseEmitter> subscribersOf(TaskSseService service, long taskId) throws Exception {
        return emittersOf(service).computeIfAbsent(taskId,
                key -> Collections.synchronizedList(new ArrayList<>()));
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, List<SseEmitter>> emittersOf(TaskSseService service) throws Exception {
        Field field = TaskSseService.class.getDeclaredField("emitters");
        field.setAccessible(true);
        return (Map<Long, List<SseEmitter>>) field.get(service);
    }

    private static boolean awaitListSize(List<SseEmitter> list, int expected, long seconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (list.size() != expected && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5L);
        }
        return list.size() == expected;
    }

    /**
     * 记录型订阅者桩：捕获帧原文（{@code SseEventBuilder} → {@code DataWithMediaType.getData()}）。
     *
     * <p>
     * {@code send(SseEventBuilder)} 是生产代码实际调用的重载（{@code publish} 走
     * {@code emitter.send(SseEmitter.event().data(json))}），故桩件必须覆写它 —— 只覆写
     * {@code send(Object)} 的桩件收不到任何东西（用例会假绿）。
     */
    private static class RecordingEmitter extends SseEmitter {

        private final List<String> payloads = new CopyOnWriteArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            // SseEventBuilder 的 build() 给出两类项：SSE 字段前缀文本（"data:"，TEXT_PLAIN）
            // 与业务载荷（mediaType=null，即 producer 传进来的 json 串）。这里只记业务载荷 ——
            // 帧体口径的判据是那串 JSON，而不是 SseEmitter 自己拼的线上前缀。
            for (ResponseBodyEmitter.DataWithMediaType entry : builder.build()) {
                if (entry.getMediaType() == null) {
                    this.payloads.add(String.valueOf(entry.getData()));
                }
            }
        }

        List<String> payloads() {
            return List.copyOf(this.payloads);
        }

        boolean awaitPayloads(int size, long seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (this.payloads.size() < size && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(5L);
            }
            return this.payloads.size() >= size;
        }
    }

    /**
     * 首帧写出即失败的订阅者桩（客户端连上即死）：走生产实际调用的
     * {@code send(SseEventBuilder)} 重载，抛 IOException ⇒ 写预算判 {@code FAILED}。
     */
    private static final class FailingEmitter extends SseEmitter {

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            throw new IOException("客户端连上即死（首帧写失败）");
        }
    }

    /**
     * 首帧写出即卡住的订阅者桩（模拟"停读"客户端）。
     *
     * <p>
     * <b>忽略线程中断</b>：真实 Tomcat 阻塞写对中断不敏感（{@code NioEndpoint#doWrite} 捕获
     * {@code InterruptedException} 后继续等），写预算的 {@code cancel(true)} 因此不能作为释放手段；
     * 桩件若一打断就返回，会掩盖"阻塞写期间锁/线程被占住"的判据。
     */
    private static final class BlockingEmitter extends RecordingEmitter {

        private final CountDownLatch firstSendStarted = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        private final AtomicInteger sends = new AtomicInteger();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            if (this.sends.incrementAndGet() == 1) {
                this.firstSendStarted.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
                boolean released = false;
                while (!released && System.nanoTime() < deadline) {
                    try {
                        released = this.release.await(50L, TimeUnit.MILLISECONDS);
                    }
                    catch (InterruptedException ex) {
                        // 忽略：真实阻塞写同样继续等
                    }
                }
                throw new IOException("客户端断开（写超时后连接失败的模拟）");
            }
            super.send(builder);
        }

        boolean awaitFirstSend(long seconds) throws InterruptedException {
            return this.firstSendStarted.await(seconds, TimeUnit.SECONDS);
        }

        void release() {
            this.release.countDown();
        }
    }
}
