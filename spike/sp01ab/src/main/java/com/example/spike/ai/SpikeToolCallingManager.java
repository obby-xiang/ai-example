package com.example.spike.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.spike.bus.EventHub;
import com.example.spike.config.SpikeProperties;
import com.example.spike.hitl.ConfirmDecision;
import com.example.spike.hitl.Gate;
import com.example.spike.hitl.SessionState;
import com.example.spike.tools.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 核心构件：包装官方 {@code DefaultToolCallingManager}，在官方循环的工具执行扩展点上做两类挂起。
 *
 * <p>协议、循环、Schema 全部由 Spring AI 负责（OpenAiChatModel 内部 isToolExecutionRequired →
 * executeToolCalls → 回填 → 再请求模型）；本类只在每一次工具执行处插入：
 * <ul>
 * <li>BACKEND  ：原样委托官方管理器执行；</li>
 * <li>FRONTEND ：不委托，向 SSE 下发 frontend_tool_request 并阻塞等待
 * HTTP 端点 POST 回灌结果，再把结果作为官方 ToolResponse 交回循环；</li>
 * <li>SENSITIVE：不委托，向 SSE 下发 confirm_request 并阻塞等人工决策，
 * 批准才委托执行；拒绝/超时以“未执行”文本回填，循环继续。</li>
 * </ul>
 */
public class SpikeToolCallingManager implements ToolCallingManager {

	private static final Logger log = LoggerFactory.getLogger(SpikeToolCallingManager.class);

	private final ToolCallingManager delegate;

	private final EventHub hub;

	private final SpikeProperties properties;

	private final ObjectMapper mapper;

	public SpikeToolCallingManager(ToolCallingManager delegate, EventHub hub, SpikeProperties properties,
			ObjectMapper mapper) {
		this.delegate = delegate;
		this.hub = hub;
		this.properties = properties;
		this.mapper = mapper;
	}

	@Override
	public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
		return this.delegate.resolveToolDefinitions(chatOptions);
	}

	@Override
	public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
		AssistantMessage assistant = chatResponse.getResult() == null ? null : chatResponse.getResult().getOutput();
		if (assistant == null || !assistant.hasToolCalls()) {
			return this.delegate.executeToolCalls(prompt, chatResponse);
		}

		SessionState session = sessionOf(prompt);
		if (session == null) {
			// 兜底失败：没有会话态则无法挂起，如实回落官方行为并留日志
			log.warn("[SPIKE] 工具执行处拿不到会话态（toolContext/ThreadLocal 均缺失），回落官方默认执行");
			return this.delegate.executeToolCalls(prompt, chatResponse);
		}

		List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
		for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
			responses.add(handle(session, prompt, call));
		}

		ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder().responses(responses).build();
		return ToolExecutionResult.builder()
			.conversationHistory(List.of(assistant, toolResponseMessage))
			.build();
	}

	private ToolResponseMessage.ToolResponse handle(SessionState session, Prompt prompt,
			AssistantMessage.ToolCall call) {
		String name = call.name();
		ToolRegistry.Kind kind = ToolRegistry.kindOf(name);
		Map<String, Object> args = parseArgs(call.arguments());
		Map<String, Object> start = data();
		start.put("toolCallId", call.id());
		start.put("name", name);
		start.put("kind", kind.name().toLowerCase());
		start.put("args", args);
		session.events().emit("tool_start", start);

		return switch (kind) {
			case FRONTEND -> handleFrontend(session, call, name, args);
			case SENSITIVE -> handleSensitive(session, prompt, call, name, args);
			case BACKEND -> executeViaDelegate(session, prompt, call, name);
		};
	}

	/** SP-01a：前端工具——透出调用请求，阻塞等前端回灌结果。 */
	private ToolResponseMessage.ToolResponse handleFrontend(SessionState session, AssistantMessage.ToolCall call,
			String name, Map<String, Object> args) {
		int timeout = this.properties.getFrontendToolTimeoutSeconds();
		Gate<Map<String, Object>> gate = new Gate<>(call.id(), name);
		session.registerFrontendGate(gate);

		Map<String, Object> req = data();
		req.put("toolCallId", call.id());
		req.put("name", name);
		req.put("args", args);
		req.put("timeoutSeconds", timeout);
		req.put("callback", "POST /api/frontend-tool-result {sessionId, toolCallId, result}");
		session.events().emit("frontend_tool_request", req);

		Map<String, Object> payload = gate.await(timeout);
		session.clearFrontendGate(gate);

		if (payload == null) {
			String msg = "前端工具 " + name + " 未在 " + timeout + " 秒内回传执行结果（前端超时），本次调用未获得数据。";
			Map<String, Object> res = data();
			res.put("toolCallId", call.id());
			res.put("name", name);
			res.put("ok", false);
			res.put("source", "none");
			res.put("waitedMs", gate.waitedMs());
			res.put("result", msg);
			session.events().emit("frontend_tool_result", res);
			return new ToolResponseMessage.ToolResponse(call.id(), name, msg);
		}

		String result = String.valueOf(payload.getOrDefault("result", ""));
		Map<String, Object> res = data();
		res.put("toolCallId", call.id());
		res.put("name", name);
		res.put("ok", true);
		res.put("source", String.valueOf(payload.getOrDefault("source", "http-post")));
		res.put("waitedMs", gate.waitedMs());
		res.put("result", result);
		session.events().emit("frontend_tool_result", res);
		session.markFrontendToolResult();
		return new ToolResponseMessage.ToolResponse(call.id(), name, result);
	}

	/** SP-01b：敏感工具——执行前挂起等人工确认；approve 才委托执行，reject/超时以未执行语义回填。 */
	private ToolResponseMessage.ToolResponse handleSensitive(SessionState session, Prompt prompt,
			AssistantMessage.ToolCall call, String name, Map<String, Object> args) {
		int timeout = this.properties.getConfirmTimeoutSeconds();
		Gate<ConfirmDecision> gate = new Gate<>(call.id(), name);
		session.registerConfirmGate(gate);

		Map<String, Object> req = data();
		req.put("toolCallId", call.id());
		req.put("name", name);
		req.put("args", args);
		req.put("summary", String.valueOf(args));
		req.put("timeoutSeconds", timeout);
		req.put("callback", "POST /api/confirm {sessionId, toolCallId, approved, reason}");
		session.events().emit("confirm_request", req);

		ConfirmDecision decision = gate.await(timeout);
		session.clearConfirmGate(gate);

		if (decision == null) {
			String msg = "确认等待超过 " + timeout + " 秒，系统已自动取消该操作（工具未执行）。";
			emitDecision(session, call, "timeout", false, msg, "timeout", gate.waitedMs());
			session.markSensitiveBlocked();
			return toolResult(session, call, name, false, false, msg);
		}

		emitDecision(session, call, decision.approved() ? "approve" : "reject", decision.approved(),
				decision.reason(), decision.source(), gate.waitedMs());

		if (!decision.approved()) {
			String msg = "用户拒绝了该操作，工具未执行。拒绝原因：" + decision.reason();
			session.markSensitiveBlocked();
			return toolResult(session, call, name, false, false, msg);
		}

		session.markSensitiveExecuted();
		return executeViaDelegate(session, prompt, call, name);
	}

	private void emitDecision(SessionState session, AssistantMessage.ToolCall call, String decision, boolean approved,
			String reason, String source, long waitedMs) {
		Map<String, Object> d = data();
		d.put("toolCallId", call.id());
		d.put("name", call.name());
		d.put("decision", decision);
		d.put("approved", approved);
		d.put("reason", reason);
		d.put("source", source);
		d.put("waitedMs", waitedMs);
		session.events().emit("confirm_decision", d);
	}

	/** 委托官方 DefaultToolCallingManager 执行单个工具调用（逐个执行以保留顺序与门控）。 */
	private ToolResponseMessage.ToolResponse executeViaDelegate(SessionState session, Prompt prompt,
			AssistantMessage.ToolCall call, String name) {
		try {
			AssistantMessage single = AssistantMessage.builder().toolCalls(List.of(call)).build();
			ToolExecutionResult result = this.delegate.executeToolCalls(prompt,
					new ChatResponse(List.of(new Generation(single))));
			String text = result.conversationHistory()
				.stream()
				.filter(ToolResponseMessage.class::isInstance)
				.map(ToolResponseMessage.class::cast)
				.flatMap(message -> message.getResponses().stream())
				.filter(response -> call.id() != null && call.id().equals(response.id()))
				.map(ToolResponseMessage.ToolResponse::responseData)
				.findFirst()
				.orElse("");
			session.markBackendToolExecuted();
			return toolResult(session, call, name, true, true, text);
		}
		catch (Exception ex) {
			log.warn("[SPIKE] 工具执行异常 name={}", name, ex);
			String failure = "工具执行失败：" + ex.getMessage();
			return toolResult(session, call, name, false, false, failure);
		}
	}

	private ToolResponseMessage.ToolResponse toolResult(SessionState session, AssistantMessage.ToolCall call,
			String name, boolean ok, boolean executed, String text) {
		Map<String, Object> res = data();
		res.put("toolCallId", call.id());
		res.put("name", name);
		res.put("ok", ok);
		res.put("executed", executed);
		res.put("result", truncate(text));
		session.events().emit("tool_result", res);
		return new ToolResponseMessage.ToolResponse(call.id(), name, text);
	}

	private SessionState sessionOf(Prompt prompt) {
		Map<String, Object> context = prompt.getOptions() instanceof ToolCallingChatOptions options
				? options.getToolContext() : null;
		Object sessionId = context == null ? null : context.get("sessionId");
		if (sessionId != null) {
			SessionState session = this.hub.find(String.valueOf(sessionId));
			if (session != null) {
				return session;
			}
		}
		return SessionState.current();
	}

	private Map<String, Object> parseArgs(String argsJson) {
		if (argsJson == null || argsJson.isBlank()) {
			return new LinkedHashMap<>();
		}
		try {
			return this.mapper.readValue(argsJson, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (Exception ex) {
			return Map.of("_raw", argsJson);
		}
	}

	private String truncate(String text) {
		if (text == null) {
			return "";
		}
		return text.length() > 800 ? text.substring(0, 800) + "…" : text;
	}

	private static Map<String, Object> data() {
		return new LinkedHashMap<>();
	}

	/** 供 /api/dev/info 暴露当前是否装配了自定义管理器。 */
	public String describe() {
		return "delegate=" + this.delegate.getClass().getName() + ", frontendToolTimeoutSeconds="
				+ this.properties.getFrontendToolTimeoutSeconds() + ", confirmTimeoutSeconds="
				+ this.properties.getConfirmTimeoutSeconds();
	}

}
