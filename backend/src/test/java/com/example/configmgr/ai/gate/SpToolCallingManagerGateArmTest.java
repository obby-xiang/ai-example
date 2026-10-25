package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.ReasoningOrigin;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.StreamViolationException;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F8 用例（第 2 步）：{@code SpToolCallingManager} 在 {@code suspended} 帧之前预登记闸门，
 * 并在异常/提前返回路径上回收未被消费的预登记 —— 否则 {@code hasActiveLatch} 永真，
 * 死卡兜底彻底失效。
 *
 * <p>本类用<b>真实</b> {@link ConfirmGate}（{@code arm}/{@code disarm} 与 {@code latches} 是同一份状态），
 * 只把 store/registry 换桩。
 *
 * <ol>
 * <li>{@code saveSuspended} 抛异常：异常照常上抛，预登记闸门被回收；</li>
 * <li>防线③拦截（越 scope，不进入等待）：整轮结束后没有残留闸门；</li>
 * <li>事件边界取消（{@code checkCancelled} 在进入等待前抛出）：同样没有残留闸门。</li>
 * </ol>
 */
class SpToolCallingManagerGateArmTest {

    private static final String RUN_ID = "run-gate-arm";

    private static final String TOOL_CALL_ID = "call-gate-arm-1";

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

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

    private final MessageJsonCodec codec = mock(MessageJsonCodec.class);

    private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, new AiProperties(),
            this.cancellations, this.beacon, List.of());

    private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
            this.toolRegistry, this.codec, this.beacon, this.cancellations,
            new ToolResultLimiter(new AiProperties()), List.of());

    @Test
    void saveSuspendedFailureDisarmsPreRegisteredGate() {        stubRun();
        when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
        when(this.toolRegistry.matchesContext(eq("start_import"), any(AiContext.class))).thenReturn(true);
        doThrow(new IllegalStateException("redis down"))
            .when(this.store)
            .saveSuspended(anyString(), any(), anyList(), anyList(), any(), any());

        assertThatThrownBy(() -> this.manager.executeToolCalls(prompt("start_import"), response("start_import")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("redis down");

        assertThat(this.gate.hasActiveLatch(RUN_ID)).as("异常路径不留预登记闸门").isFalse();
        assertThat(this.gate.pendingGates()).isZero();
    }

    /**
     * 跨供应方推理隔离（来源标记面）：挂起外置的 assistant metadata 必须带中性 origin。
     *
     * <p>续跑侧原样恢复这份 metadata，请求层再按"origin 与当前供应方是否一致"决定回放 ——
     * 因此"挂起那一刻打标记"是隔离链的起点；漏了它，续跑重建的 assistant 就是"来源未知"，
     * 只能走兼容策略（fail-open）。标记来源 = 快照的冻结身份（providerId/modelId），不依赖当前全局默认。
     */
    @Test
    void suspendedAssistantMetadataCarriesTheNeutralOrigin() {
        stubRun();
        when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
        when(this.toolRegistry.matchesContext(eq("start_import"), any(AiContext.class))).thenReturn(true);
        AtomicReference<Map<String, Object>> metadata = new AtomicReference<>();
        doAnswer(invocation -> {
            metadata.set(invocation.getArgument(5));
            throw new IllegalStateException("stop");
        }).when(this.store).saveSuspended(anyString(), any(), anyList(), anyList(), any(), any());

        assertThatThrownBy(() -> this.manager.executeToolCalls(prompt("start_import"), response("start_import")))
            .isInstanceOf(IllegalStateException.class);

        assertThat(metadata.get()).as("挂起消息的来源标记 = 快照身份（中性，不含密钥/端点）")
            .containsEntry(ReasoningOrigin.KEY_PROVIDER_ID, 7L)
            .containsEntry(ReasoningOrigin.KEY_MODEL_ID, "model-a");
    }

    @Test
    void outOfScopeEntryLeavesNoArmedGate() {
        stubRun();
        when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
        when(this.toolRegistry.matchesContext(eq("start_import"), any(AiContext.class))).thenReturn(false);

        this.manager.executeToolCalls(prompt("start_import"), response("start_import"));

        assertThat(this.gate.hasActiveLatch(RUN_ID)).as("越 scope 不进入等待，预登记须被回收").isFalse();
    }

    @Test
    void unknownCancelStateRejectsAtTheLastGateAsInfrastructureFailure() {
        stubRun();
        when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.UNKNOWN);
        when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
        when(this.toolRegistry.matchesContext(eq("start_import"), any(AiContext.class))).thenReturn(true);

        assertThatThrownBy(() -> this.manager.executeToolCalls(prompt("start_import"), response("start_import")))
            .as("执行副作用前的最后闸：UNKNOWN 必须拒绝，但不得记成用户取消")
            .isInstanceOf(com.example.configmgr.ai.run.StreamViolationException.class)
            .satisfies(ex -> {
                com.example.configmgr.ai.run.StreamViolationException violation =
                        (com.example.configmgr.ai.run.StreamViolationException) ex;
                assertThat(violation.getCode()).isEqualTo(com.example.configmgr.common.ErrorCodes.AI_REDIS_UNAVAILABLE)
                        .isNotEqualTo(com.example.configmgr.common.ErrorCodes.AI_RUN_CANCELLED);
            });
        assertThat(this.gate.hasActiveLatch(RUN_ID)).isFalse();
    }

    @Test
    void cancelledBeforeToolsLeavesNoArmedGate() {
        stubRun();
        when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.CANCELLED);
        when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
        when(this.toolRegistry.matchesContext(eq("start_import"), any(AiContext.class))).thenReturn(true);

        assertThatThrownBy(() -> this.manager.executeToolCalls(prompt("start_import"), response("start_import")))
            .isInstanceOf(StreamViolationException.class);

        assertThat(this.gate.hasActiveLatch(RUN_ID)).as("取消路径不留预登记闸门").isFalse();
    }

    /**
     * 批4-D（用户 2026-10-23 裁决）：不可撤销 = 确认卡 + 复选框 —— 条目上的 {@code irreversible}
     * 由 {@code @ToolCategory(ToolMeta.Category.P)} 决定，且必须在挂起<b>落库之前</b>定型
     * （否则刷新恢复拿不到该位，复选框重建不出来）。
     *
     * <p>用 saveSuspended 抛错截住挂起：本用例只关心"交给 store 的那批条目各自带什么值"。
     */
    @Test
    void suspendedPendingsCarryIrreversibleFlagFromCategoryP() {
        stubRun();
        when(this.toolRegistry.channelOf("start_publish")).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf("start_publish")).thenReturn(ToolMeta.RiskLevel.DANGER);
        when(this.toolRegistry.categoryOf("start_publish")).thenReturn(ToolMeta.Category.P);
        when(this.toolRegistry.matchesContext(eq("start_publish"), any(AiContext.class))).thenReturn(true);
        AtomicReference<List<PendingToolCall>> captured =
            new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(2));
            throw new IllegalStateException("stop");
        }).when(this.store).saveSuspended(anyString(), any(), anyList(), anyList(), any(), any());

        assertThatThrownBy(() -> this.manager.executeToolCalls(prompt("start_publish"), response("start_publish")))
            .isInstanceOf(IllegalStateException.class);

        assertThat(captured.get()).singleElement().satisfies(pending -> {
            assertThat(pending.getName()).isEqualTo("start_publish");
            assertThat(pending.isIrreversible()).as("发布作业 = Category.P ⇒ 不可撤销").isTrue();
        });
    }

    @Test
    void reversibleConfirmPendingsStayNonIrreversible() {
        stubRun();
        when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
        when(this.toolRegistry.categoryOf("start_import")).thenReturn(ToolMeta.Category.J);
        when(this.toolRegistry.matchesContext(eq("start_import"), any(AiContext.class))).thenReturn(true);
        AtomicReference<List<PendingToolCall>> captured =
            new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(2));
            throw new IllegalStateException("stop");
        }).when(this.store).saveSuspended(anyString(), any(), anyList(), anyList(), any(), any());

        assertThatThrownBy(() -> this.manager.executeToolCalls(prompt("start_import"), response("start_import")))
            .isInstanceOf(IllegalStateException.class);

        assertThat(captured.get()).singleElement().satisfies(pending -> {
            assertThat(pending.getName()).isEqualTo("start_import");
            assertThat(pending.isIrreversible()).as("普通确认卡维持现状：不加复选框").isFalse();
        });
    }

    // ───────────────────────── helpers ─────────────────────────

    private void stubRun() {
        when(this.store.get(RUN_ID)).thenReturn(snapshot());
        when(this.store.runKey(RUN_ID)).thenReturn("ai:run:" + RUN_ID);
        when(this.store.pendingKey(RUN_ID)).thenReturn("ai:pending:" + RUN_ID);
        when(this.store.instanceId()).thenReturn("test-instance");
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
    }

    private static RunSnapshot snapshot() {
        RunSnapshot snapshot = new RunSnapshot();
        snapshot.setRunId(RUN_ID);
        snapshot.setSessionId("s-gate-arm");
        snapshot.setStatus(RunSnapshot.RUNNING);
        snapshot.setContext(AiContext.of("import", "IMPORT", "IMPORT", 7L, null).toMap());
        // 跨供应方推理隔离：快照的冻结身份是 assistant 消息 origin 的唯一来源
        snapshot.setProviderId(7L);
        snapshot.setModelId("model-a");
        return snapshot;
    }

    private static Prompt prompt(String tool) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
            .toolContext(Map.of("runId", RUN_ID))
            .build();
        return new Prompt(List.of(new UserMessage("hi")), options);
    }

    private static ChatResponse response(String tool) {
        AssistantMessage assistant = AssistantMessage.builder()
            .toolCalls(List.of(new AssistantMessage.ToolCall(TOOL_CALL_ID, "function", tool, "{\"taskId\":7}")))
            .build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }
}
