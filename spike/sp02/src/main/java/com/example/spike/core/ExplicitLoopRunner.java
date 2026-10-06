package com.example.spike.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.spike.config.SpikeProperties;
import com.example.spike.memory.MessageJsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import reactor.core.scheduler.Schedulers;

/**
 * 续跑驱动器：把一段“已完成的对话历史 + 挂起轮的重建消息”交给官方模型 API，继续跑循环。
 *
 * <p>
 * 三种形态（同一份代码，只差请求选项与 predicate 开关）：
 * <ul>
 * <li>{@link Form#BLOCKING}：官方内部工具执行打开（自定义 {@code ToolCallingManager} 在循环内阻塞挂起）
 * —— 一轮调用即可，循环由官方驱动；</li>
 * <li>{@link Form#EXPLICIT}：{@code internalToolExecutionEnabled=false}，循环由调用方驱动（V-d2 形态）；</li>
 * <li>{@link Form#PREDICATE}：内部执行打开但自定义 predicate 对“含外部工具的本轮”返回 false，
 * 官方把 {@code assistant(tool_calls)} 交回调用方，循环同样由调用方驱动（方案 B 形态）。</li>
 * </ul>
 */
public class ExplicitLoopRunner {

	public enum Form {

		BLOCKING, EXPLICIT, PREDICATE

	}

	private static final Logger log = LoggerFactory.getLogger(ExplicitLoopRunner.class);

	private final OpenAiChatModel chatModel;

	private final List<ToolCallback> callbacks;

	private final RunStore store;

	private final RuntimeRegistry registry;

	private final ToolCallExecutor executor;

	private final MessageJsonCodec codec;

	private final SpikeProperties properties;

	public ExplicitLoopRunner(OpenAiChatModel chatModel, List<ToolCallback> callbacks, RunStore store,
			RuntimeRegistry registry, ToolCallExecutor executor, MessageJsonCodec codec, SpikeProperties properties) {
		this.chatModel = chatModel;
		this.callbacks = callbacks;
		this.store = store;
		this.registry = registry;
		this.executor = executor;
		this.codec = codec;
		this.properties = properties;
	}

	/** 续跑结果（也是实验断言的口径）。 */
	public static final class Outcome {

		public String status;

		public String finalText = "";

		public List<String> pendingIds = List.of();

		public int modelRequests;

		public String finishReason;

		public Map<String, Object> asMap() {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("status", this.status);
			out.put("finalText", this.finalText);
			out.put("pendingIds", this.pendingIds);
			out.put("modelRequests", this.modelRequests);
			out.put("finishReason", this.finishReason);
			return out;
		}

	}

	public Outcome run(String runId, List<Message> history, Form form) {
		Outcome outcome = new Outcome();
		RunRuntime runtime = this.registry.runtime(runId);
		boolean callerDriven = form != Form.BLOCKING;

		List<Message> messages = new ArrayList<>(history);
		while (true) {
			RunSnapshot snap = this.store.get(runId);
			if (snap.round >= this.properties.getMaxRounds()) {
				snap.status = RunSnapshot.FAILED;
				snap.error = "ROUND_LIMIT";
				this.store.save(snap);
				outcome.status = RunSnapshot.FAILED;
				return outcome;
			}
			snap.round = snap.round + 1;
			snap.status = RunSnapshot.RUNNING;
			this.store.save(snap);

			Round round = callRound(runId, messages, form);
			outcome.modelRequests++;
			outcome.finishReason = round.finishReason;

			if (round.toolCalls.isEmpty()) {
				messages.add(AssistantMessage.builder().content(round.text).build());
				snap = this.store.get(runId);
				snap.messageJson = this.codec.serializeAll(messages);
				snap.finalText = round.text;
				snap.emittedChars = snap.emittedChars + round.visibleChars;
				snap.status = RunSnapshot.DONE;
				this.store.save(snap);
				Map<String, Object> doneEvent = new LinkedHashMap<>();
				doneEvent.put("runId", runId);
				doneEvent.put("round", snap.round);
				doneEvent.put("finalText", round.text);
				doneEvent.put("modelRequests", outcome.modelRequests);
				doneEvent.put("instanceId", this.store.instanceId());
				runtime.emit("resume_done", doneEvent);
				outcome.status = RunSnapshot.DONE;
				outcome.finalText = round.text;
				return outcome;
			}

			// ===== 新一轮挂起：先把 assistant(tool_calls) 外置，再逐个结算 =====
			List<PendingToolCall> pendings = ToolCallExecutor.newPendings(round.assistant, runId);
			for (PendingToolCall item : pendings) {
				this.store.putPending(runId, item);
			}
			snap = this.store.get(runId);
			snap.assistantContent = round.assistant.getText();
			snap.inFlightToolCalls = pendings;
			snap.messageJson = this.codec.serializeAll(messages);
			snap.status = RunSnapshot.SUSPENDED;
			this.store.save(snap);
			runtime.emit("suspend_persisted",
					suspendEvent(runId, form, round.assistant.getText(), pendings));

			List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
			Prompt prompt = new Prompt(messages, options(runId, form));
			try {
				for (PendingToolCall item : pendings) {
					// 调用方驱动式同样要公告挂起项（前端/确认门据此知道要回灌什么），但不阻塞等待
					this.executor.announcePending(runId, item);
					responses.add(this.executor.resolve(runId, prompt, item, form.name().toLowerCase()));
				}
			}
			catch (PendingUnresolvedException ex) {
				snap = this.store.get(runId);
				snap.status = RunSnapshot.SUSPENDED;
				this.store.save(snap);
				runtime.emit("run_suspended", Map.of("runId", runId, "form", form.name(), "pending",
						List.of(ex.pending().asMap()), "instanceId", this.store.instanceId()));
				outcome.status = RunSnapshot.SUSPENDED;
				outcome.pendingIds = List.of(ex.pending().toolCallId);
				outcome.finalText = round.text;
				return outcome;
			}

			ToolResponseMessage toolMessage = ToolResponseMessage.builder().responses(responses).build();
			messages.add(round.assistant);
			messages.add(toolMessage);

			snap = this.store.get(runId);
			snap.messageJson = this.codec.serializeAll(messages);
			snap.inFlightToolCalls = new ArrayList<>();
			snap.assistantContent = null;
			snap.status = RunSnapshot.RUNNING;
			this.store.save(snap);
			runtime.emit("round_settled", Map.of("runId", runId, "form", form.name(), "round", snap.round,
					"messageCount", snap.messageJson.size(),
					"toolResponses", responses.stream().map(r -> Map.of("id", r.id(), "name", r.name())).toList()));

			if (!callerDriven) {
				// BLOCKING 形态下，循环由官方驱动，这里不应再看到 tool_calls
				log.warn("[SP02] BLOCKING 形态下调用方意外拿到 tool_calls，round={}", snap.round);
			}
		}
	}

	// ------------------------------------------------------------------ 单次模型调用（流式）

	private static final class Round {

		AssistantMessage assistant;

		String text = "";

		String finishReason;

		List<AssistantMessage.ToolCall> toolCalls = List.of();

		int visibleChars;

	}

	private Round callRound(String runId, List<Message> messages, Form form) {
		RunRuntime runtime = this.registry.runtime(runId);
		Round round = new Round();
		List<ChatResponse> elements = this.chatModel.stream(new Prompt(messages, options(runId, form)))
			.subscribeOn(Schedulers.boundedElastic())
			.collectList()
			.block();
		if (elements == null || elements.isEmpty()) {
			throw new IllegalStateException("upstream returned empty stream");
		}

		List<String> texts = new ArrayList<>();
		ChatResponse lastWithTools = null;
		for (ChatResponse element : elements) {
			if (element.getResult() == null || element.getResult().getOutput() == null) {
				continue;
			}
			AssistantMessage output = element.getResult().getOutput();
			if (output.getText() != null && !output.getText().isEmpty()) {
				texts.add(output.getText());
			}
			if (output.hasToolCalls()) {
				lastWithTools = element;
			}
		}

		// 实测（1.1.8 + DeepSeek 流）：下游只收到逐块 delta，没有额外的“聚合整段”元素
		// （见事件 model_round 的 elementCount/textElements 原文），因此本轮文本 = 各 delta 文本的拼接。
		String concat = String.join("", texts);
		round.text = concat;
		round.visibleChars = round.text.length();
		for (String piece : texts) {
			runtime.emit("delta", Map.of("content", piece, "runId", runId, "form", form.name()));
		}

		ChatResponse last = elements.get(elements.size() - 1);
		round.finishReason = last.getResult() == null || last.getResult().getMetadata() == null ? null
				: last.getResult().getMetadata().getFinishReason();
		round.assistant = lastWithTools != null ? lastWithTools.getResult().getOutput()
				: last.getResult().getOutput();
		round.toolCalls = round.assistant.getToolCalls() == null || round.assistant.getToolCalls().isEmpty()
				? List.of() : round.assistant.getToolCalls();

		Map<String, Object> report = new LinkedHashMap<>();
		report.put("runId", runId);
		report.put("form", form.name());
		report.put("elementCount", elements.size());
		report.put("textElements", texts);
		report.put("textJoined", concat);
		report.put("textElementCount", texts.size());
		report.put("roundText", round.text);
		report.put("finishReason", round.finishReason);
		List<Map<String, Object>> callMaps = new ArrayList<>();
		for (AssistantMessage.ToolCall tc : round.toolCalls) {
			Map<String, Object> call = new LinkedHashMap<>();
			call.put("id", tc.id());
			call.put("name", tc.name());
			call.put("arguments", tc.arguments());
			callMaps.add(call);
		}
		report.put("toolCalls", callMaps);
		report.put("calledBy", this.store.instanceId());
		runtime.emit("model_round", report);
		return round;
	}

	private OpenAiChatOptions options(String runId, Form form) {
		OpenAiChatOptions base = (OpenAiChatOptions) this.chatModel.getDefaultOptions();
		OpenAiChatOptions opts = OpenAiChatOptions.fromOptions(base);
		opts.setToolCallbacks(this.callbacks);
		opts.setToolContext(Map.of("runId", runId));
		if (form == Form.EXPLICIT) {
			opts.setInternalToolExecutionEnabled(false);
		}
		return opts;
	}

	private Map<String, Object> suspendEvent(String runId, Form form, String assistantContent,
			List<PendingToolCall> pendings) {
		List<Map<String, Object>> calls = new ArrayList<>();
		for (PendingToolCall item : pendings) {
			calls.add(item.asMap());
		}
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("runId", runId);
		event.put("form", form.name());
		event.put("assistantContent", assistantContent);
		event.put("toolCalls", calls);
		event.put("stateKey", this.store.runKey(runId));
		event.put("pendingKey", this.store.hitlKey(runId));
		event.put("writtenBy", this.store.instanceId());
		return event;
	}

	/** 供续跑重建请求与工具结算使用：与循环内同构的请求选项。 */
	public Prompt promptFor(String runId, List<Message> messages, Form form) {
		return new Prompt(messages, options(runId, form));
	}

}
