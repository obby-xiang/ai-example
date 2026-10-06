package com.example.spike.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.spike.core.PendingToolCall;
import com.example.spike.core.RunSnapshot;
import com.example.spike.core.RunStore;
import com.example.spike.core.RuntimeRegistry;
import com.example.spike.core.ToolCallExecutor;
import com.example.spike.memory.MessageJsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 官方循环内的工具执行扩展点：<b>“阻塞挂起 + 挂起态外置”</b>形态（ADR-2 的生产形态）。
 *
 * <p>
 * 与 SP-01ab 的关键差别：挂起前先把状态写进 Redis（{@code sp02:run:<runId>} 的 in-flight 段 +
 * {@code sp02:hitl:<runId>}），进程内的 {@code Gate} 只当唤醒开关。因此进程死亡后，
 * 新实例凭 Redis 就能重建 {@code assistant(tool_calls)} + {@code role:tool} 并续跑。
 */
public class Sp02ToolCallingManager implements ToolCallingManager {

	private static final Logger log = LoggerFactory.getLogger(Sp02ToolCallingManager.class);

	private final ToolCallingManager delegate;

	private final RunStore store;

	private final RuntimeRegistry registry;

	private final ToolCallExecutor executor;

	private final MessageJsonCodec codec;

	public Sp02ToolCallingManager(ToolCallingManager delegate, RunStore store, RuntimeRegistry registry,
			ToolCallExecutor executor, MessageJsonCodec codec) {
		this.delegate = delegate;
		this.store = store;
		this.registry = registry;
		this.executor = executor;
		this.codec = codec;
	}

	@Override
	public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
		return this.delegate.resolveToolDefinitions(chatOptions);
	}

	@Override
	public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
		AssistantMessage assistant = chatResponse.getResult() == null ? null : chatResponse.getResult().getOutput();
		String runId = runIdOf(prompt);
		if (assistant == null || !assistant.hasToolCalls() || runId == null) {
			if (assistant != null && assistant.hasToolCalls()) {
				log.warn("[SP02] 拿不到 runId，回落官方默认执行（挂起态无法外置）");
			}
			return this.delegate.executeToolCalls(prompt, chatResponse);
		}

		RunSnapshot snap = this.store.get(runId);
		if (snap == null) {
			log.warn("[SP02] Redis 中没有 runId={} 的状态，回落官方默认执行", runId);
			return this.delegate.executeToolCalls(prompt, chatResponse);
		}
		this.registry.runtime(runId); // 确保运行时存在

		// ===== 挂起态外置点：工具执行之前，把 assistant(tool_calls) 与每个待办条目写进 Redis =====
		List<PendingToolCall> pendings = ToolCallExecutor.newPendings(assistant, runId);
		for (PendingToolCall item : pendings) {
			this.store.putPending(runId, item);
		}
		snap.assistantContent = assistant.getText();
		snap.inFlightToolCalls = pendings;
		snap.round = snap.round + 1;
		snap.status = RunSnapshot.SUSPENDED;
		this.store.save(snap);

		Map<String, Object> suspended = new LinkedHashMap<>();
		suspended.put("runId", runId);
		suspended.put("assistantContent", assistant.getText());
		List<Map<String, Object>> calls = new ArrayList<>();
		for (PendingToolCall item : pendings) {
			calls.add(item.asMap());
		}
		suspended.put("toolCalls", calls);
		suspended.put("stateKey", this.store.runKey(runId));
		suspended.put("pendingKey", this.store.hitlKey(runId));
		suspended.put("writtenBy", this.store.instanceId());
		this.registry.runtime(runId).emit("suspend_persisted", suspended);

		// ===== 逐个结算（当前进程内阻塞等待外部输入） =====
		List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
		for (PendingToolCall item : pendings) {
			responses.add(this.executor.executeBlocking(runId, prompt, item));
		}

		// ===== 落定本轮：把 assistant(tool_calls) 与 role:tool 追加进已完成上下文 =====
		ToolResponseMessage toolMessage = ToolResponseMessage.builder().responses(responses).build();
		snap = this.store.get(runId);
		snap.messageJson.add(this.codec.serialize(assistant));
		snap.messageJson.add(this.codec.serialize(toolMessage));
		snap.inFlightToolCalls = new ArrayList<>();
		snap.assistantContent = null;
		snap.status = RunSnapshot.RUNNING;
		this.store.save(snap);

		Map<String, Object> settled = new LinkedHashMap<>();
		settled.put("runId", runId);
		settled.put("round", snap.round);
		settled.put("messageCount", snap.messageJson.size());
		settled.put("toolResponses", responses.stream()
			.map(response -> Map.of("id", response.id(), "name", response.name()))
			.toList());
		this.registry.runtime(runId).emit("round_settled", settled);

		return ToolExecutionResult.builder().conversationHistory(List.of(assistant, toolMessage)).build();
	}

	private String runIdOf(Prompt prompt) {
		if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolContext() != null) {
			Object runId = options.getToolContext().get("runId");
			return runId == null ? null : String.valueOf(runId);
		}
		return null;
	}

}
