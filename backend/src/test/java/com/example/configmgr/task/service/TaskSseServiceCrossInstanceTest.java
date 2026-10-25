package com.example.configmgr.task.service;

import com.example.configmgr.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TaskSseService} ↔ {@link TaskEventBus} 的<b>连线</b>用例（复审必修项 2）。
 *
 * <p>此前只用 {@code inProcess()} 空实现测了本机扇出，跨实例这条线（发布不早退、首订阅注册、
 * 末订阅退订、注册失败 10s 退避重试、远端帧落到本机订阅者）没有任何断言。本类用
 * {@link RecordingTaskEventBus}（录制型 fake）逐条钉住，并额外锁住并发退订/订阅的终态不变量。
 */
class TaskSseServiceCrossInstanceTest {

    private static final long TASK_ID = 31L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 1) 本机没有 emitter 也必须发到总线（作业在 A、连接在 B 是常态）──────────

    @Test
    void publishWithoutLocalEmitterStillPublishesToBus() {
        RecordingTaskEventBus bus = new RecordingTaskEventBus();
        TaskSseService service = new TaskSseService(MAPPER, new AppProperties(), bus);

        service.publish(TASK_ID, "JOB_PROGRESS", Map.of("processed", 3, "total", 10));

        assertThat(bus.published).as("本机无订阅者时不得早退").hasSize(1);
        assertThat(bus.published.get(0)).contains("\"type\":\"JOB_PROGRESS\"").contains("\"processed\":3");
    }

    // ── 2) 首订阅注册 / 末订阅退订 / 重复订阅幂等 ────────────────────────────

    @Test
    void firstSubscribeRegistersLastUnsubscribeReleasesAndDuplicateSubscribeIsIdempotent() throws Exception {
        RecordingTaskEventBus bus = new RecordingTaskEventBus();
        TaskSseService service = new TaskSseService(MAPPER, new AppProperties(), bus);

        SseEmitter first = service.subscribe(TASK_ID);
        assertThat(bus.subscribeCalls.get()).as("首订阅注册一次").isEqualTo(1);
        assertThat(bus.listeners).containsKey(TASK_ID);

        SseEmitter second = service.subscribe(TASK_ID);
        assertThat(bus.subscribeCalls.get()).as("第二订阅不得重复注册（幂等）").isEqualTo(1);
        assertThat(bus.listeners).containsKey(TASK_ID);

        service.remove(TASK_ID, first);
        assertThat(bus.unsubscribeCalls.get()).as("仍有订阅者：不得退订").isZero();
        assertThat(bus.listeners).containsKey(TASK_ID);

        service.remove(TASK_ID, second);
        assertThat(bus.unsubscribeCalls.get()).as("末订阅退订一次").isEqualTo(1);
        assertThat(bus.listeners).doesNotContainKey(TASK_ID);
        assertThat(subscriptionLocksOf(service))
                .as("空闲任务不得永久保留订阅锁（否则 map 无界增长）").isEmpty();
    }

    // ── 3) 注册失败 → 10s 退避后由 publish 重试（可控时间入口）────────────────

    @Test
    void failedRegistrationRetriesOnlyAfterTenSecondBackoff() {
        RecordingTaskEventBus bus = new RecordingTaskEventBus();
        bus.subscribeResult = false;
        AtomicLong nanos = new AtomicLong();
        LongSupplier retryClock = nanos::get;
        TaskSseService service = new TaskSseService(MAPPER, new AppProperties(), bus, retryClock);

        service.subscribe(TASK_ID);
        assertThat(bus.subscribeCalls.get()).as("首订阅尝试一次（失败）").isEqualTo(1);

        service.subscribe(TASK_ID);
        assertThat(bus.subscribeCalls.get()).as("退避期内第二个订阅者不得触发重试").isEqualTo(1);

        nanos.addAndGet(TimeUnit.SECONDS.toNanos(9));
        service.publish(TASK_ID, "JOB_PROGRESS", Map.of("processed", 1));
        assertThat(bus.subscribeCalls.get()).as("9s：仍在退避窗口内").isEqualTo(1);

        bus.subscribeResult = true;
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(2));
        service.publish(TASK_ID, "JOB_PROGRESS", Map.of("processed", 2));
        assertThat(bus.subscribeCalls.get()).as("11s：publish 路径重试注册").isEqualTo(2);
        assertThat(bus.listeners).as("重试成功后本实例恢复跨实例接收").containsKey(TASK_ID);
    }

    // ── 4) 远端实例的帧落到本机订阅者（复用写预算/帧形态）────────────────────

    @Test
    void remoteFrameFromAnotherInstanceIsDeliveredToLocalEmitters() throws Exception {
        RecordingTaskEventBus bus = new RecordingTaskEventBus();
        TaskSseService service = new TaskSseService(MAPPER, new AppProperties(), bus);
        service.subscribe(TASK_ID); // 注册跨实例订阅
        RecordingEmitter browser = new RecordingEmitter();
        emittersOf(service).get(TASK_ID).add(browser);

        bus.deliverToLocal(TASK_ID, "{\"type\":\"JOB_DONE\",\"data\":{\"taskId\":31}}");

        assertThat(browser.awaitPayloads(1, 5L)).isTrue();
        assertThat(browser.payloads().get(0)).contains("\"type\":\"JOB_DONE\"");
    }

    // ── 竞态：退订在途时新订阅到达，终态必须仍是"已订阅"（不能错过退订后的重注册）──

    @Test
    void subscribeDuringInFlightUnsubscribeEndsSubscribed() throws Exception {
        RecordingTaskEventBus bus = new RecordingTaskEventBus();
        bus.blockUnsubscribe = new CountDownLatch(1);
        bus.unsubscribeGate = new CountDownLatch(1);
        TaskSseService service = new TaskSseService(MAPPER, new AppProperties(), bus);

        SseEmitter first = service.subscribe(TASK_ID);
        assertThat(bus.subscribeCalls.get()).isEqualTo(1);

        Thread remover = new Thread(() -> service.remove(TASK_ID, first), "t-last-unsubscribe");
        remover.setDaemon(true);
        remover.start();
        assertThat(bus.blockUnsubscribe.await(5, TimeUnit.SECONDS))
                .as("T1 已进入退订（持订阅锁）").isTrue();

        Thread subscriber = new Thread(() -> service.subscribe(TASK_ID), "t-new-subscribe");
        subscriber.setDaemon(true);
        subscriber.start();
        Thread.sleep(80L);
        assertThat(bus.subscribeCalls.get()).as("T2 必须等同任务锁，不得与退订并发进入").isEqualTo(1);

        bus.unsubscribeGate.countDown();
        remover.join(5_000L);
        subscriber.join(5_000L);

        assertThat(remover.isAlive()).isFalse();
        assertThat(subscriber.isAlive()).isFalse();
        assertThat(bus.subscribeCalls.get()).as("退订完成后新订阅者重新注册").isEqualTo(2);
        assertThat(bus.listeners).as("终态必须仍订阅（不能停在被退订的旧状态）").containsKey(TASK_ID);
    }

    // ── 5) 注册失败：没有 publish 时由有界延迟重试兜底（复审边界 1）───────────

    @Test
    void failedRegistrationIsRetriedByDelayedTickEvenWithoutPublish() {
        RecordingTaskEventBus bus = new RecordingTaskEventBus();
        bus.subscribeResult = false;
        AtomicLong nanos = new AtomicLong();
        List<Runnable> ticks = new CopyOnWriteArrayList<>();
        ScheduledExecutorService scheduler = tickScheduler(ticks);
        TaskSseService service = new TaskSseService(MAPPER, new AppProperties(), bus, nanos::get, scheduler);

        service.subscribe(TASK_ID);
        assertThat(bus.subscribeCalls.get()).isEqualTo(1);
        assertThat(ticks).as("注册失败后必须挂一次性延迟重试").hasSize(1);

        ticks.get(0).run(); // 早到：退避未到，不得订阅
        assertThat(bus.subscribeCalls.get()).as("退避未到不得 subscribe").isEqualTo(1);
        assertThat(ticks).as("早到只补排剩余退避（一次性，不是每 tick 无条件 subscribe）").hasSize(2);

        bus.subscribeResult = true;
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(11));
        ticks.get(1).run();

        assertThat(bus.subscribeCalls.get()).as("到期 tick 触发第二次订阅").isEqualTo(2);
        assertThat(bus.listeners).as("remoteSubscribed 恢复：本实例重新接收跨实例帧").containsKey(TASK_ID);
        assertThat(ticks).as("成功后不再排重试").hasSize(2);
    }

    @Test
    void noDelayedRetryAfterLastSubscriberLeaves() throws Exception {
        RecordingTaskEventBus bus = new RecordingTaskEventBus();
        bus.subscribeResult = false;
        AtomicLong nanos = new AtomicLong();
        List<Runnable> ticks = new CopyOnWriteArrayList<>();
        ScheduledExecutorService scheduler = tickScheduler(ticks);
        TaskSseService service = new TaskSseService(MAPPER, new AppProperties(), bus, nanos::get, scheduler);

        SseEmitter emitter = service.subscribe(TASK_ID);
        assertThat(ticks).hasSize(1);

        service.remove(TASK_ID, emitter);
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(11));
        ticks.get(0).run();

        assertThat(bus.subscribeCalls.get()).as("末订阅后不得再重试").isEqualTo(1);
        assertThat(subscriptionLocksOf(service)).as("空闲任务不残留锁").isEmpty();
    }

    // ───────────────────────── 白盒/辅助 ─────────────────────────

    /** 可控调度器：捕获被提交的一次性重试，由用例按需触发（确定性，不 sleep）。 */
    private static ScheduledExecutorService tickScheduler(List<Runnable> ticks) {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    ticks.add(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        return scheduler;
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, List<SseEmitter>> emittersOf(TaskSseService service) throws Exception {
        Field field = TaskSseService.class.getDeclaredField("emitters");
        field.setAccessible(true);
        return (Map<Long, List<SseEmitter>>) field.get(service);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, ?> subscriptionLocksOf(TaskSseService service) throws Exception {
        Field field = TaskSseService.class.getDeclaredField("subscriptionLocks");
        field.setAccessible(true);
        return (Map<Long, ?>) field.get(service);
    }

    /** 录制型总线：记录 publish/subscribe/unsubscribe，可注入失败与"退订阻塞"。 */
    static final class RecordingTaskEventBus implements TaskEventBus {

        final List<String> published = new CopyOnWriteArrayList<>();

        final Map<Long, Consumer<String>> listeners = new ConcurrentHashMap<>();

        final AtomicInteger subscribeCalls = new AtomicInteger();

        final AtomicInteger unsubscribeCalls = new AtomicInteger();

        volatile boolean subscribeResult = true;

        /** 非 null 时 unsubscribe 先 countDown 再等 gate（制造"退订在途"窗口）。 */
        volatile CountDownLatch blockUnsubscribe;

        volatile CountDownLatch unsubscribeGate;

        @Override
        public boolean publish(Long taskId, String frameJson) {
            this.published.add(frameJson);
            return true;
        }

        @Override
        public boolean subscribe(Long taskId, Consumer<String> onFrame) {
            this.subscribeCalls.incrementAndGet();
            if (!this.subscribeResult) {
                return false;
            }
            this.listeners.put(taskId, onFrame);
            return true;
        }

        @Override
        public void unsubscribe(Long taskId) {
            CountDownLatch entered = this.blockUnsubscribe;
            CountDownLatch gate = this.unsubscribeGate;
            if (entered != null && gate != null) {
                entered.countDown();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            this.unsubscribeCalls.incrementAndGet();
            this.listeners.remove(taskId);
        }

        /** 模拟"另一个实例发布、Redis 投递到本实例"。 */
        void deliverToLocal(Long taskId, String frameJson) {
            Consumer<String> listener = this.listeners.get(taskId);
            if (listener != null) {
                listener.accept(frameJson);
            }
        }
    }

    /** 记录型订阅者桩（生产实际调用的 {@code send(SseEventBuilder)} 重载）。 */
    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> payloads = new CopyOnWriteArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
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
}
