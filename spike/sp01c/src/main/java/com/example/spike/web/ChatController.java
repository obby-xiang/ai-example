package com.example.spike.web;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.spike.memory.MessageJsonCodec;
import com.example.spike.memory.RedisChatMemoryRepository;
import com.example.spike.tools.SpikeTools;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SP-01c 实验接口。{@code /api/dev/**} 仅供实验取证使用，不属于生产契约。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

	private final ChatClient chatClient;

	private final ChatMemory chatMemory;

	private final RedisChatMemoryRepository repository;

	private final MessageJsonCodec codec;

	private final SpikeTools tools;

	private final ToolCallbackProvider toolCallbackProvider;

	private final StringRedisTemplate redis;

	private final String port;

	public ChatController(ChatClient chatClient, ChatMemory chatMemory, RedisChatMemoryRepository repository,
			MessageJsonCodec codec, SpikeTools tools, ToolCallbackProvider toolCallbackProvider,
			StringRedisTemplate redis, @Value("${server.port}") String port) {
		this.chatClient = chatClient;
		this.chatMemory = chatMemory;
		this.repository = repository;
		this.codec = codec;
		this.tools = tools;
		this.toolCallbackProvider = toolCallbackProvider;
		this.redis = redis;
		this.port = port;
	}

	@PostMapping("/chat")
	public ChatReply chat(@RequestBody ChatRequest request) {
		var spec = this.chatClient.prompt().user(request.message());
		if (request.system() != null && !request.system().isBlank()) {
			spec = spec.system(request.system());
		}
		ChatResponse response = spec.toolCallbacks(this.toolCallbackProvider)
			.advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, request.sessionId()))
			.call()
			.chatResponse();

		String model = response.getMetadata() != null ? String.valueOf(response.getMetadata().getModel()) : "unknown";
		return new ChatReply(request.sessionId(), response.getResult().getOutput().getText(), model,
				this.chatMemory.get(request.sessionId()).size(), this.tools.totalCalls());
	}

	@GetMapping("/chat/{sessionId}/history")
	public List<Map<String, Object>> history(@PathVariable String sessionId) {
		List<Map<String, Object>> out = new ArrayList<>();
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
			out.add(item);
		}
		return out;
	}

	@DeleteMapping("/chat/{sessionId}")
	public Map<String, Object> delete(@PathVariable String sessionId) {
		this.chatMemory.clear(sessionId);
		return Map.of("sessionId", sessionId, "keyStillExists",
				Boolean.TRUE.equals(this.redis.hasKey(this.repository.keyFor(sessionId))));
	}

	@GetMapping("/dev/info")
	public Map<String, Object> info() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("port", this.port);
		out.put("pid", ProcessHandle.current().pid());
		out.put("ttlSeconds", this.repository.getTtl().toSeconds());
		out.put("redisKeyPattern", RedisChatMemoryRepository.KEY_PREFIX + "<conversationId>");
		out.put("conversationIds", this.repository.findConversationIds());
		out.put("toolCalls", this.tools.totalCalls());
		return out;
	}

	@PostMapping("/dev/ttl")
	public Map<String, Object> setTtl(@RequestParam long seconds) {
		this.repository.setTtl(Duration.ofSeconds(seconds));
		return Map.of("ttlSeconds", this.repository.getTtl().toSeconds());
	}

	@GetMapping("/dev/tool-stats")
	public Map<String, Object> toolStats() {
		return Map.of("getServerTime", this.tools.getServerTimeCalls(), "calculate", this.tools.calculateCalls(), "total",
				this.tools.totalCalls());
	}

	/**
	 * 工具消息往返自检：构造 用户消息 + 带 toolCalls 的 AssistantMessage + ToolResponseMessage +
	 * 收尾 AssistantMessage，整体经官方 ChatMemory 写入 Redis 再读回，逐元素比较序列化结果。
	 */
	@PostMapping("/dev/roundtrip")
	public Map<String, Object> roundtrip(@RequestParam String sessionId) {
		List<Message> original = List.of(new UserMessage("往返自检：请先查时间再算 6*7"),
				AssistantMessage.builder()
					.content("")
					.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "getServerTime", "{}"),
							new AssistantMessage.ToolCall("call_2", "function", "calculate",
									"{\"a\":6,\"operator\":\"multiply\",\"b\":7}")))
					.build(),
				ToolResponseMessage.builder()
					.responses(List.of(
							new ToolResponseMessage.ToolResponse("call_1", "getServerTime", "2026-10-06 12:00:00"),
							new ToolResponseMessage.ToolResponse("call_2", "calculate", "6 multiply 7 = 42")))
					.build(),
				new AssistantMessage("现在是 2026-10-06 12:00:00，6 × 7 = 42。"));

		this.chatMemory.clear(sessionId);
		this.chatMemory.add(sessionId, original);
		List<Message> readBack = this.chatMemory.get(sessionId);

		List<String> originalJson = this.codec.serializeAll(original);
		List<String> readBackJson = this.codec.serializeAll(readBack);
		List<String> differences = new ArrayList<>();
		if (originalJson.size() != readBackJson.size()) {
			differences.add("size: original=%d readBack=%d".formatted(originalJson.size(), readBackJson.size()));
		}
		int common = Math.min(originalJson.size(), readBackJson.size());
		for (int i = 0; i < common; i++) {
			String originalCanonical = this.codec.canonicalJson(original.get(i));
			String readBackCanonical = this.codec.canonicalJson(readBack.get(i));
			if (!originalCanonical.equals(readBackCanonical)) {
				differences.add("index %d%n  original = %s%n  readBack = %s".formatted(i, originalCanonical,
						readBackCanonical));
			}
		}
		boolean identical = differences.isEmpty();

		Message assistant = readBack.size() > 1 ? readBack.get(1) : null;
		Message toolResponse = readBack.size() > 2 ? readBack.get(2) : null;
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("identical", identical);
		result.put("differences", differences);
		result.put("originalSize", originalJson.size());
		result.put("readBackSize", readBackJson.size());
		result.put("assistantToolCalls", assistant instanceof AssistantMessage assistantMessage
				? assistantMessage.getToolCalls() : "NOT_ASSISTANT");
		result.put("toolResponses", toolResponse instanceof ToolResponseMessage toolResponseMessage
				? toolResponseMessage.getResponses() : "NOT_TOOL_RESPONSE");
		result.put("originalJson", originalJson);
		result.put("readBackJson", readBackJson);
		result.put("redisKey", this.repository.keyFor(sessionId));
		return result;
	}

	/** 绕过 codec 直接返回 Redis 原始元素，供键结构取证。 */
	@GetMapping("/dev/raw/{sessionId}")
	public Map<String, Object> raw(@PathVariable String sessionId) {
		String redisKey = this.repository.keyFor(sessionId);
		List<String> rawValues = this.redis.opsForList().range(redisKey, 0, -1);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("key", redisKey);
		out.put("type", this.redis.type(redisKey));
		out.put("ttlSeconds", this.redis.getExpire(redisKey));
		out.put("length", rawValues == null ? 0 : rawValues.size());
		out.put("values", rawValues == null ? List.of() : rawValues);
		return out;
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handle(Exception ex) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", ex.getClass().getName());
		body.put("message", ex.getMessage());
		Throwable cause = ex.getCause();
		body.put("cause", cause == null ? null : cause.getClass().getName() + ": " + cause.getMessage());
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
	}

	public record ChatRequest(String sessionId, String message, String system) {
	}

	public record ChatReply(String sessionId, String reply, String model, int historySize, int toolCallsSoFar) {
	}

}
