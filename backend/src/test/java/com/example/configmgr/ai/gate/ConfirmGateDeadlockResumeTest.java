package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3-6a 用例（第 4 方案）：<b>死卡兜底</b> —— {@code ConfirmGate} 在
 * {@code woke=false} 且该轮快照仍 {@code SUSPENDED} 时触发 {@link ResumeService#resume(String)}，
 * 把"重启后无人等待的挂起态"收敛为真实续跑。
 *
 * <h2>红队实读的背景（为什么"判死"方案全错）</h2>
 * {@code StartupResumeRunner} 对 {@code resumeable=false} 的轮只 skip（不落终态、不重建等待循环）、
 * {@code ResumeService} 对仍 PENDING 的外部项返回 {@code PENDING_UNRESOLVED} ——
 * 故"SUSPENDED + 无活闸门"是<b>稳态</b>而非死锁；真正能让它动起来的信号就是"外部输入到了"，
 * 而那一刻条目已经不是 PENDING，续跑走正常路径。
 *
 * <h2>八条判据</h2>
 * <ol>
 * <li>{@code woke=true}（同进程活闸门）→ 不触发；</li>
 * <li>{@code woke=false} + SUSPENDED → 触发恰一次；</li>
 * <li>{@code woke=false} + 快照非 SUSPENDED → 不触发；</li>
 * <li>本进程仍有该 runId 的活跃 latch（同轮另一 toolCallId）→ 不触发（防双驱动，A8）；</li>
 * <li>重复提交（{@code DUPLICATE}）→ 不触发；</li>
 * <li>{@code dismiss}（{@code cancelled=true}，woke=false + SUSPENDED）→ 触发（A11）；</li>
 * <li>触发载体饱和 → 不抛异常、不触发、{@code resumeOutcome} 如实回 {@code EXECUTOR_SATURATED}（A9）；</li>
 * <li>HTTP 线程<b>立即返回</b>：动作被派发到执行器而不在本线程内跑（A9 的时序证据）。</li>
 * </ol>
 */
class ConfirmGateDeadlockResumeTest {

    private static final String RUN_ID = "run-deadlock";
    private static final String TOOL_CALL_ID = "call-1";
    private static final String OTHER_TOOL_CALL_ID = "call-other";

    private final RunStore store = mock(RunStore.class);

    private final RunRegistry registry = mock(RunRegistry.class);

    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

    private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    private final ResumeService resumeService = mock(ResumeService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);

    /** 只捕获不执行：既让"触发"可断言，又让"立即返回"成为结构性事实（任务在本线程外排队）。 */
    private final List<Runnable> dispatched = Collections.synchronizedList(new ArrayList<>());

    private final Executor captureExecutor = this.dispatched::add;

    private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, new AiProperties(),
            this.cancellations, this.beacon, List.of(), this.provider, this.captureExecutor);

    @AfterEach
    void drainDispatch() {
        // 让被捕获的触发任务跑掉（避免线程/资源残留），并把"未派发"的用例显式记下来
        synchronized (this.dispatched) {
            this.dispatched.forEach(Runnable::run);
            this.dispatched.clear();
        }
    }

    // ── ② woke=false + SUSPENDED → 触发恰一次 ──

    @Test
    void triggersResumeWhenNotWokenAndSnapshotSuspended() {
        stubCommon();
        when(this.store.get(RUN_ID)).thenReturn(snapshot(RunSnapshot.SUSPENDED));
        when(this.store.pending(RUN_ID, TOOL_CALL_ID)).thenReturn(pending(PendingToolCall.PENDING));

        ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意");

        assertThat(submission.woke()).isFalse();
        assertThat(submission.resumeTrigger().triggered()).as("死卡兜底已派发").isTrue();
        assertThat(submission.resumeTrigger().outcome()).isEqualTo("SCHEDULED");
        assertThat(this.dispatched).as("动作交给轻量执行器，而不是在 HTTP 线程里跑").hasSize(1);

        // drainDispatch 后：续跑恰被调用一次
        drainNow();
        verify(this.resumeService, times(1)).resume(RUN_ID);
    }

    // ── ① woke=true（同进程活闸门）→ 不触发 ──

    @Test
    void doesNotTriggerWhenInProcessGateWoken() throws Exception {
        stubCommon();
        when(this.store.get(RUN_ID)).thenReturn(snapshot(RunSnapshot.SUSPENDED));
        PendingToolCall entry = pending(PendingToolCall.PENDING);
        // R-153 卡②(a)：mock 每次返回语义等价新副本（对齐真实 RunStore.pending 的反序列化行为）——
        // 旧 thenReturn(entry) 让提交/等待两侧共享同一可变对象，锁外改状态对等待方可见 ⇒ 间歇 woke=false。
        CountDownLatch firstRead = new CountDownLatch(1);
        stubPendingFreshCopy(TOOL_CALL_ID, entry, firstRead);
        when(this.cancellations.onCancel(anyString(), any())).thenReturn(() -> {
        });

        Thread waiter = new Thread(() -> this.gate.awaitDecision(RUN_ID, entry), "t3b-await");
        waiter.setDaemon(true);
        waiter.start();
        awaitWaiterInPendingRead(RUN_ID, firstRead);

        ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意");

        assertThat(submission.woke()).as("同进程等待方被唤醒").isTrue();
        assertThat(submission.resumeTrigger().triggered()).isFalse();
        assertThat(submission.resumeTrigger().outcome()).isEqualTo("WOKE_IN_PROCESS_GATE");
        assertThat(this.dispatched).isEmpty();
        waiter.interrupt();
    }

    // ── ③ woke=false + 快照非 SUSPENDED → 不触发 ──

    @Test
    void doesNotTriggerWhenSnapshotIsNotSuspended() {
        stubCommon();
        when(this.store.get(RUN_ID)).thenReturn(snapshot(RunSnapshot.RUNNING));
        when(this.store.pending(RUN_ID, TOOL_CALL_ID)).thenReturn(pending(PendingToolCall.PENDING));

        ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, false, "拒绝");

        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        assertThat(submission.resumeTrigger().triggered()).isFalse();
        assertThat(submission.resumeTrigger().outcome()).isEqualTo("SNAPSHOT_NOT_SUSPENDED");
        assertThat(this.dispatched).isEmpty();
    }

    // ── ④ 本进程仍有该 runId 的活跃 latch（同轮另一 toolCallId）→ 不触发 ──

    @Test
    void doesNotTriggerWhenAnotherLatchForSameRunIsActive() throws Exception {
        stubCommon();
        when(this.store.get(RUN_ID)).thenReturn(snapshot(RunSnapshot.SUSPENDED));
        // R-153 卡②(a)：同款对象同一性收口（本用例与 ① 同构——等待线程 + 共享可变 stub），副本化等价改写
        PendingToolCall main = pending(PendingToolCall.PENDING);
        stubPendingFreshCopy(TOOL_CALL_ID, main, new CountDownLatch(1));
        PendingToolCall other = pendingFor(OTHER_TOOL_CALL_ID, PendingToolCall.PENDING);
        CountDownLatch firstReadOther = new CountDownLatch(1);
        stubPendingFreshCopy(OTHER_TOOL_CALL_ID, other, firstReadOther);
        when(this.cancellations.onCancel(anyString(), any())).thenReturn(() -> {
        });

        Thread otherWaiter = new Thread(() -> this.gate.awaitDecision(RUN_ID, other), "t3b-await-other");
        otherWaiter.setDaemon(true);
        otherWaiter.start();
        awaitWaiterInPendingRead(RUN_ID, firstReadOther);

        ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意");

        assertThat(submission.woke()).as("唤醒的是本 toolCallId 的闸门，另一个不在").isFalse();
        assertThat(submission.resumeTrigger().triggered()).isFalse();
        assertThat(submission.resumeTrigger().outcome())
                .as("同轮仍有活跃等待 ⇒ 触发会双驱动（R4③ 的毫秒窗）")
                .isEqualTo("ACTIVE_LATCH_IN_PROCESS");
        assertThat(this.dispatched).isEmpty();
        otherWaiter.interrupt();
    }

    // ── ⑤ 重复提交（DUPLICATE）→ 不触发 ──

    @Test
    void duplicateSubmissionDoesNotTrigger() {
        stubCommon();
        when(this.store.get(RUN_ID)).thenReturn(snapshot(RunSnapshot.SUSPENDED));
        when(this.store.pending(RUN_ID, TOOL_CALL_ID)).thenReturn(pending(PendingToolCall.APPROVED));

        ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "重复");

        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.DUPLICATE);
        assertThat(submission.resumeTrigger().outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(this.dispatched).isEmpty();
    }

    // ── ⑥ dismiss（cancelled=true，woke=false + SUSPENDED）→ 触发（A11） ──

    @Test
    void dismissPathAlsoTriggersResume() {
        stubCommon();
        when(this.store.get(RUN_ID)).thenReturn(snapshot(RunSnapshot.SUSPENDED));
        when(this.store.pending(RUN_ID, TOOL_CALL_ID))
                .thenReturn(pendingKind(PendingToolCall.KIND_FRONTEND, PendingToolCall.PENDING));

        ConfirmGate.Submission submission = this.gate.submitFrontendResult(RUN_ID, TOOL_CALL_ID, null, "http-post", true);

        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        assertThat(submission.pending().getStatus()).isEqualTo(PendingToolCall.FRONTEND_CANCELLED);
        assertThat(submission.resumeTrigger().triggered()).as("A11：前端放弃同样收尾续跑").isTrue();
        drainNow();
        verify(this.resumeService, times(1)).resume(RUN_ID);
    }

    // ── ⑦ 触发载体饱和 → 不抛异常、如实回报（A9） ──

    @Test
    void saturatedTriggerExecutorIsReportedNotThrown() {
        RejectedExecutionException rejected = new RejectedExecutionException("queue full");
        ConfirmGate saturated = new ConfirmGate(this.store, this.registry, new AiProperties(),
                this.cancellations, this.beacon, List.of(), this.provider, command -> {
                    throw rejected;
                });
        stubCommon();
        when(this.store.get(RUN_ID)).thenReturn(snapshot(RunSnapshot.SUSPENDED));
        when(this.store.pending(RUN_ID, TOOL_CALL_ID)).thenReturn(pending(PendingToolCall.PENDING));

        ConfirmGate.Submission submission = saturated.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意");

        assertThat(submission.outcome()).as("外部输入本身已落库成功，不因续跑没派出去而失败")
                .isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        assertThat(submission.resumeTrigger().triggered()).isFalse();
        assertThat(submission.resumeTrigger().outcome()).isEqualTo("EXECUTOR_SATURATED");
        verify(this.resumeService, never()).resume(anyString());
    }

    // ── ⑧ HTTP 线程立即返回（A9 的时序证据） ──

    @Test
    void confirmReturnsBeforeResumeActuallyRuns() {
        stubCommon();
        when(this.store.get(RUN_ID)).thenReturn(snapshot(RunSnapshot.SUSPENDED));
        when(this.store.pending(RUN_ID, TOOL_CALL_ID)).thenReturn(pending(PendingToolCall.PENDING));

        ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意");

        // 此刻触发任务仍在执行器队列里没跑 —— submitDecision 已返回（HTTP 线程不做续跑）
        assertThat(this.dispatched).hasSize(1);
        verify(this.resumeService, never()).resume(anyString());
        assertThat(submission.resumeTrigger().triggered()).isTrue();
    }

    // ── ⑨ 续跑服务不可用（无 key 降级装配）→ 不触发且不报错 ──

    @Test
    void noResumeServiceSkipsSilently() {
        stubCommon();
        when(this.provider.getIfAvailable()).thenReturn(null);   // 无 key 的降级装配（provider 存在但拿不到 bean）
        when(this.store.get(RUN_ID)).thenReturn(snapshot(RunSnapshot.SUSPENDED));
        when(this.store.pending(RUN_ID, TOOL_CALL_ID)).thenReturn(pending(PendingToolCall.PENDING));

        ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意");

        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        assertThat(submission.resumeTrigger().outcome()).isEqualTo("NO_RESUME_SERVICE");
        assertThat(this.dispatched).isEmpty();
    }

    // ───────────────────────── helpers ─────────────────────────

    private void stubCommon() {
        when(this.provider.getIfAvailable()).thenReturn(this.resumeService);
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        when(this.store.instanceId()).thenReturn("test-instance");
        when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
        when(this.resumeService.resume(RUN_ID)).thenReturn(
                new ResumeService.ResumeResult(ResumeService.Outcome.NOT_FOUND, java.util.Map.of("runId", RUN_ID)));
    }

    /** 立即把已捕获的触发任务跑掉（断言续跑真的被调用）。 */
    private void drainNow() {
        synchronized (this.dispatched) {
            this.dispatched.forEach(Runnable::run);
            this.dispatched.clear();
        }
    }

    private void awaitLatchRegistered(String runId) throws InterruptedException {
        for (int i = 0; i < 200 && !this.gate.hasActiveLatch(runId); i++) {
            Thread.sleep(10);
        }
        assertThat(this.gate.hasActiveLatch(runId)).as("等待侧闸门已登记").isTrue();
    }

    /**
     * R-153 卡②(a)：mock 每次返回语义等价的<b>新副本</b>（对齐真实 {@code RunStore.pending} 的
     * 反序列化行为）——等待方无法绕开 {@code signal} 直接观察到提交侧的决策改写。
     */
    private void stubPendingFreshCopy(String toolCallId, PendingToolCall template, CountDownLatch firstRead) {
        when(this.store.pending(RUN_ID, toolCallId)).thenAnswer(inv -> {
            if (firstRead != null) {
                firstRead.countDown();
            }
            PendingToolCall copy = new PendingToolCall();
            copy.setToolCallId(template.getToolCallId());
            copy.setName(template.getName());
            copy.setKind(template.getKind());
            copy.setStatus(template.getStatus());
            copy.setReason(template.getReason());
            return copy;
        });
    }

    /**
     * R-153 卡②(b)：屏障升级 —— 闸门已登记<b>且</b>等待方已做首次 {@code store.pending} 读
     * （有界 5s，超时显式失败，不得无限等待）。消「提交线程停顿 &gt;2s ⇒ 等待方先超时」残窗。
     */
    private void awaitWaiterInPendingRead(String runId, CountDownLatch firstPendingRead)
            throws InterruptedException {
        awaitLatchRegistered(runId);
        if (!firstPendingRead.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("等待方 5s 内未完成首次 store.pending 读（屏障失效）");
        }
    }

    private static RunSnapshot snapshot(String status) {
        RunSnapshot snapshot = new RunSnapshot();
        snapshot.setRunId(RUN_ID);
        snapshot.setSessionId("s-deadlock");
        snapshot.setStatus(status);
        return snapshot;
    }

    private static PendingToolCall pending(String status) {
        return pendingKind(PendingToolCall.KIND_CONFIRM, status);
    }

    private static PendingToolCall pendingKind(String kind, String status) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(TOOL_CALL_ID);
        pending.setName("start_publish");
        pending.setKind(kind);
        pending.setStatus(status);
        return pending;
    }

    private static PendingToolCall pendingFor(String toolCallId, String status) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(toolCallId);
        pending.setName("start_publish");
        pending.setKind(PendingToolCall.KIND_CONFIRM);
        pending.setStatus(status);
        return pending;
    }
}
