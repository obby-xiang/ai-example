package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.AiChatService;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * AI 运行时 HTTP 入口（S4.2 §2 web/AiController）。
 *
 * <ul>
 * <li>{@code POST /api/ai/chat}：SSE 流式一轮对话，首包 {@code start}，终帧 {@code done}/{@code error}；</li>
 * <li>{@code GET /api/ai/history/{sessionId}}：读取该会话记忆窗口（Redis 原文经官方 ChatMemory 读回）。</li>
 * </ul>
 *
 * <p>
 * 本棒只落这两个端点；{@code /confirm}、{@code /frontend-tool-result}、{@code /events/{runId}}
 * 属第二、三棒的确认门与重挂收流，届时在本类补齐。
 *
 * <p>
 * Q2：无 key 时模型侧 bean 不存在，{@code /chat} 在<b>开流之前</b>返回 503 {@code AI_UNAVAILABLE}，
 * 业务 API 不受影响。运行线程取自专用有界平台线程池（DC-12），池饱和返回 503 {@code AI_BUSY}。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
public class AiController {

	private final AiAvailability availability;

	private final ObjectProvider<AiChatService> chatService;

	private final ChatMemory chatMemory;

	private final ThreadPoolTaskExecutor aiRunExecutor;

	private final ObjectMapper objectMapper;

	private final AiProperties properties;

	public AiController(AiAvailability availability, ObjectProvider<AiChatService> chatService, ChatMemory chatMemory,
			@Qualifier("aiRunExecutor") ThreadPoolTaskExecutor aiRunExecutor, ObjectMapper objectMapper,
			AiProperties properties) {
		this.availability = availability;
		this.chatService = chatService;
		this.chatMemory = chatMemory;
		this.aiRunExecutor = aiRunExecutor;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	@PostMapping(value = "/chat", produces = { MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE })
	public ResponseEntity<?> chat(@RequestBody(required = false) AiChatRequest request) {
		AiChatService service = this.chatService.getIfAvailable();
		if (!this.availability.isAvailable() || service == null) {
			return json(HttpStatus.SERVICE_UNAVAILABLE,
					Map.of("code", this.availability.code(), "message", this.availability.reason()));
		}
		if (request == null || !StringUtils.hasText(request.sessionId()) || !StringUtils.hasText(request.message())) {
			return json(HttpStatus.BAD_REQUEST, Map.of("code", "BAD_REQUEST", "message", "sessionId 与 message 均为必填"));
		}

		SseEmitter emitter = new SseEmitter(this.properties.getResilience().getTotalBudget().toMillis());
		SseChatEmitter out = new SseChatEmitter(emitter, this.objectMapper);
		try {
			this.aiRunExecutor.execute(() -> {
				try {
					service.chat(request, out);
				}
				catch (Exception ex) {
					log.error("AI 轮次未捕获异常 sessionId={}", request.sessionId(), ex);
					out.error("AI_RUN_FAILED", ex.getMessage());
				}
				finally {
					out.complete();
				}
			});
		}
		catch (RejectedExecutionException ex) {
			log.warn("AI 运行线程池饱和，拒绝 sessionId={}", request.sessionId());
			return json(HttpStatus.SERVICE_UNAVAILABLE, Map.of("code", "AI_BUSY", "message", "AI 运行线程池已饱和，请稍后重试"));
		}
		return ResponseEntity.ok(emitter);
	}

	/**
	 * 该 mapping 声明了两种 produces（SSE 与 JSON），出错分支必须显式指定 JSON 内容类型，
	 * 否则响应体走 SSE 转换器选择并抛 "No converter"（实测 500）。
	 */
	private ResponseEntity<Map<String, Object>> json(HttpStatus status, Map<String, Object> body) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
	}

	@GetMapping("/history/{sessionId}")
	public ApiResponse<Map<String, Object>> history(@PathVariable String sessionId) {
		List<Map<String, Object>> items = new ArrayList<>();
		for (Message message : this.chatMemory.get(sessionId)) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("type", message.getMessageType().name());
			item.put("text", message.getText());
			if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				item.put("toolCalls", assistant.getToolCalls());
			}
			if (message instanceof ToolResponseMessage toolResponse) {
				item.put("toolResponses", toolResponse.getResponses());
			}
			Object timestamp = message.getMetadata().get(MessageJsonCodec.FIELD_TIMESTAMP);
			if (timestamp != null) {
				item.put("timestamp", timestamp);
			}
			items.add(item);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sessionId", sessionId);
		body.put("count", items.size());
		body.put("messages", items);
		return ApiResponse.ok(body);
	}

}
