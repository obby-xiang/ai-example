package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F8 用例：{@link ConfirmGate#arm}/{@link ConfirmGate#disarm} —— 闸门预登记先于 {@code suspended} 帧，
 * 以及未进入等待的预登记必须可回收。
 *
 * <ol>
 * <li>arm 立即登记闸门（{@code hasActiveLatch} 为真），重复 arm 幂等（同 key 只有一把）；</li>
 * <li>disarm 幂等：二次回收、回收从未 arm 的键都不报错，回收后 {@code hasActiveLatch} 为假；</li>
 * <li>{@code await} 复用 arm 预登记的闸门（同一实例）而不是覆盖 —— 覆盖会丢掉"帧发出后、
 *     等待进入前"已经发生的唤醒；</li>
 * <li>端到端直证：arm → 直接 {@code submitDecision} 批准（命中预登记闸门、决策落库）→
 *     {@code awaitDecision} <b>立即</b>返回 APPROVED，不进入心跳轮询循环。</li>
 * </ol>
 */
class ConfirmGateArmTest {

    private static final String RUN_ID = "run-arm";

    private static final String TOOL_CALL_ID = "call-arm-1";

    private final RunStore store = mock(RunStore.class);

	{
		// C2：生产读侧已改为 pendingLenient/pendingsLenient；Mockito 桩只需把非抛读口委托给原桩。
		when(this.store.pendingLenient(org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString()))
			.thenAnswer(invocation -> this.store.pending((String) invocation.getArgument(0),
					(String) invocation.getArgument(1)));
		when(this.store.pendingsLenient(org.mockito.ArgumentMatchers.anyString()))
			.thenAnswer(invocation -> this.store.pendings((String) invocation.getArgument(0)));
	}


    private final RunRegistry registry = mock(RunRegistry.class);

    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

    private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, new AiProperties(),
            this.cancellations, this.beacon, List.of());

    @Test
    void armRegistersGateBeforeAnyAwait() {
        this.gate.arm(RUN_ID, List.of(TOOL_CALL_ID));

        assertThat(this.gate.hasActiveLatch(RUN_ID)).as("帧发出之前闸门就已登记").isTrue();
        assertThat(this.gate.pendingGates()).isEqualTo(1);

        this.gate.arm(RUN_ID, List.of(TOOL_CALL_ID));

        assertThat(this.gate.pendingGates()).as("重复 arm 复用同一把闸门").isEqualTo(1);
    }

    @Test
    void disarmIsIdempotentAndClearsGate() {
        this.gate.arm(RUN_ID, List.of(TOOL_CALL_ID, "call-arm-2"));
        assertThat(this.gate.pendingGates()).isEqualTo(2);

        this.gate.disarm(RUN_ID, List.of(TOOL_CALL_ID, "call-arm-2"));

        assertThat(this.gate.hasActiveLatch(RUN_ID)).isFalse();
        // 幂等：二次回收 / 回收从未 arm 的键都不报错，也不改变状态
        this.gate.disarm(RUN_ID, List.of(TOOL_CALL_ID));
        this.gate.disarm(RUN_ID, List.of("never-armed"));
        assertThat(this.gate.pendingGates()).isZero();
    }

    @Test
    void awaitReusesArmedLatchInsteadOfOverwritingIt() throws Exception {
        // 有状态的最小 store：putPending 后等待方读得到"已决"状态（对齐真实 RunStore 的反序列化副本）
        Map<String, PendingToolCall> state = new ConcurrentHashMap<>();
        state.put(TOOL_CALL_ID, pending());
        CountDownLatch firstRead = new CountDownLatch(1);
        when(this.store.pending(RUN_ID, TOOL_CALL_ID)).thenAnswer(invocation -> {
            firstRead.countDown();
            return copyOf(state.get(TOOL_CALL_ID));
        });
        doAnswer(invocation -> {
            PendingToolCall saved = invocation.getArgument(1);
            state.put(saved.getToolCallId(), copyOf(saved));
            return null;
        }).when(this.store).putPending(eq(RUN_ID), any(PendingToolCall.class));
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
        when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
        });

        this.gate.arm(RUN_ID, List.of(TOOL_CALL_ID));
        Object armed = latchOf(TOOL_CALL_ID);

        AtomicReference<PendingToolCall> awaited = new AtomicReference<>();
        Thread waiter = new Thread(() -> awaited.set(this.gate.awaitDecision(RUN_ID, pending())), "f8-arm-await");
        waiter.setDaemon(true);
        waiter.start();
        try {
            assertThat(firstRead.await(5, TimeUnit.SECONDS)).as("等待方完成首次 store.pending 读").isTrue();
            awaitThreadWaiting(waiter);
            assertThat(latchOf(TOOL_CALL_ID))
                    .as("await 复用 arm 预登记的闸门实例（覆盖会让已发生的唤醒丢失）")
                    .isSameAs(armed);
        }
        finally {
            waiter.interrupt();
            waiter.join(2000);
        }
    }

    /**
     * M4：端到端直证 arm → submitDecision(批准) → awaitDecision 的时序。
     *
     * <p>三个断言方向：
     * <ol>
     * <li>signal 命中 arm 预登记的闸门（{@code woke=true}），说明预登记不是摆设；</li>
     * <li>await 的首次读库就看到 APPROVED，<b>只读一次</b>且未发心跳/活动脉冲 ——
     *     即没有进入 2s 心跳节拍的等待循环，而不是"碰巧很快被下一拍唤醒"；</li>
     * <li>await 期间 {@code latches} 里仍是 arm 登记的那一把实例（finally 回收前可观测）。</li>
     * </ol>
     */
    @Test
    void awaitAfterDirectSubmissionReturnsApprovedImmediately() throws Exception {
        Map<String, PendingToolCall> state = new ConcurrentHashMap<>();
        state.put(TOOL_CALL_ID, pending());
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<Object> latchSeenByAwait = new AtomicReference<>();
        when(this.store.pending(RUN_ID, TOOL_CALL_ID)).thenAnswer(invocation -> {
            // #1 = submitDecision 的读；#2 = awaitDecision 进入循环后的首次读
            if (reads.incrementAndGet() == 2) {
                latchSeenByAwait.set(latchOf(TOOL_CALL_ID));
            }
            return copyOf(state.get(TOOL_CALL_ID));
        });
        doAnswer(invocation -> {
            PendingToolCall saved = invocation.getArgument(1);
            state.put(saved.getToolCallId(), copyOf(saved));
            return null;
        }).when(this.store).putPending(eq(RUN_ID), any(PendingToolCall.class));
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
        when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
        });

        this.gate.arm(RUN_ID, List.of(TOOL_CALL_ID));
        Object armed = latchOf(TOOL_CALL_ID);

        ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "批准执行");
        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        assertThat(submission.woke()).as("signal 命中了 arm 预登记的闸门").isTrue();
        assertThat(state.get(TOOL_CALL_ID).getStatus())
                .as("决策已落库（不是只唤醒了内存闸门）")
                .isEqualTo(PendingToolCall.APPROVED);

        long startedAt = System.nanoTime();
        PendingToolCall resolved = this.gate.awaitDecision(RUN_ID, pending());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(resolved.getStatus()).isEqualTo(PendingToolCall.APPROVED);
        assertThat(elapsedMs)
                .as("立即返回：远低于 %ss 心跳节拍", ConfirmGate.HEARTBEAT_SECONDS)
                .isLessThan(TimeUnit.SECONDS.toMillis(ConfirmGate.HEARTBEAT_SECONDS));
        assertThat(reads).as("提交读 1 次 + 等待读 1 次 = 未进入轮询循环").hasValue(2);
        verify(this.emitter, never()).heartbeat(any());
        verify(this.beacon, never()).pulse(RUN_ID);
        assertThat(latchSeenByAwait.get())
                .as("await 复用 arm 的同一把预登记闸门")
                .isSameAs(armed);
        assertThat(this.gate.hasActiveLatch(RUN_ID)).as("等待结束即回收闸门").isFalse();
    }

    // ───────────────────────── helpers ─────────────────────────

    @SuppressWarnings("unchecked")
    private Object latchOf(String toolCallId) throws Exception {
        Field field = ConfirmGate.class.getDeclaredField("latches");
        field.setAccessible(true);
        Map<String, Object> latches = (Map<String, Object>) field.get(this.gate);
        return latches.get(RUN_ID + "|" + toolCallId);
    }

    private static void awaitThreadWaiting(Thread thread) throws InterruptedException {
        for (int i = 0; i < 500 && thread.getState() != Thread.State.TIMED_WAITING; i++) {
            Thread.sleep(5);
        }
        assertThat(thread.getState()).as("等待方已阻塞在闸门上").isEqualTo(Thread.State.TIMED_WAITING);
    }

    private static PendingToolCall pending() {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(TOOL_CALL_ID);
        pending.setName("start_publish");
        pending.setKind(PendingToolCall.KIND_CONFIRM);
        pending.setStatus(PendingToolCall.PENDING);
        return pending;
    }

    private static PendingToolCall copyOf(PendingToolCall template) {
        PendingToolCall copy = new PendingToolCall();
        copy.setToolCallId(template.getToolCallId());
        copy.setName(template.getName());
        copy.setKind(template.getKind());
        copy.setStatus(template.getStatus());
        copy.setReason(template.getReason());
        copy.setResultText(template.getResultText());
        copy.setExecuted(template.isExecuted());
        return copy;
    }
}
