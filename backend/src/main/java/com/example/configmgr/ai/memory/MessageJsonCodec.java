package com.example.configmgr.ai.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@link Message} 与 JSON 的互转（Redis LIST 的元素格式）。
 *
 * <p>
 * 覆盖 Spring AI 1.1.8 在 {@code OpenAiChatModel.createRequest} 中真正回放到请求体的字段：
 * {@code messageType}、{@code text}、{@code AssistantMessage.toolCalls}、
 * {@code ToolResponseMessage.toolResponses}，外加 {@code metadata}（元数据不进入请求体，
 * 但 {@code reasoningContent} 等模型侧信息存在其中，一并保真以避免信息丢失）。
 *
 * <p>
 * 时间戳存于 metadata 的 {@value #FIELD_TIMESTAMP} 键，同时在 JSON 顶层冗余输出一份便于
 * 人工阅读 LRANGE 原文。反序列化只读 metadata，因此序列化结果对同一消息是幂等的。
 */
public final class MessageJsonCodec {

	/** 时间戳键名，置于 metadata 中。 */
	public static final String FIELD_TIMESTAMP = "spikeTimestamp";

	private final ObjectMapper mapper;

	public MessageJsonCodec(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	public List<String> serializeAll(List<Message> messages) {
		List<String> out = new ArrayList<>(messages.size());
		for (Message message : messages) {
			out.add(serialize(message));
		}
		return out;
	}

	public List<Message> deserializeAll(List<String> rawMessages) {
		List<Message> out = new ArrayList<>(rawMessages.size());
		for (String raw : rawMessages) {
			out.add(deserialize(raw));
		}
		return out;
	}

	public String serialize(Message message) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		if (message.getMetadata() != null) {
			metadata.putAll(message.getMetadata());
		}
		String timestamp = metadata.get(FIELD_TIMESTAMP) instanceof String existing ? existing
				: Instant.now().toString();
		metadata.put(FIELD_TIMESTAMP, timestamp);

		ObjectNode node = toNode(message, metadata);
		node.put("timestamp", timestamp);
		return write(node, message);
	}

	/**
	 * 与 {@link #serialize} 结构相同，但去掉时间戳，用于"往返是否保真"的逐字段比较。
	 *
	 * <p>
	 * 时间戳必须排除：{@link #serialize} 不修改入参消息，因此对同一个内存对象重复序列化会生成新的
	 * 时间戳；而 Redis 中存下的副本会携带首次写入时的时间戳。生产路径上时间戳是按消息固定的
	 * （旧消息读回后原样回写），只有"对未落库的内存对象再序列化一次"才会出现这种差异。
	 */
	public String canonicalJson(Message message) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		if (message.getMetadata() != null) {
			metadata.putAll(message.getMetadata());
		}
		metadata.remove(FIELD_TIMESTAMP);
		ObjectNode node = toNode(message, metadata);
		node.remove("timestamp");
		return write(node, message);
	}

	private ObjectNode toNode(Message message, Map<String, Object> metadata) {
		ObjectNode node = mapper.createObjectNode();
		node.put("type", message.getMessageType().name());
		node.put("content", message.getText());
		node.set("metadata", mapper.valueToTree(metadata));

		if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
			ArrayNode toolCalls = node.putArray("toolCalls");
			for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
				ObjectNode item = toolCalls.addObject();
				item.put("id", toolCall.id());
				item.put("type", toolCall.type());
				item.put("name", toolCall.name());
				item.put("arguments", toolCall.arguments());
			}
		}

		if (message instanceof ToolResponseMessage toolResponse) {
			ArrayNode responses = node.putArray("toolResponses");
			for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
				ObjectNode item = responses.addObject();
				item.put("id", response.id());
				item.put("name", response.name());
				item.put("responseData", response.responseData());
			}
		}
		return node;
	}

	private String write(ObjectNode node, Message message) {
		try {
			return mapper.writeValueAsString(node);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException("Cannot serialize message of type " + message.getMessageType(), ex);
		}
	}

	public Message deserialize(String json) {
		JsonNode node;
		try {
			node = mapper.readTree(json);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException("Cannot parse stored chat message: " + json, ex);
		}

		MessageType type = MessageType.valueOf(node.path("type").asText());
		String content = node.path("content").asText("");
		Map<String, Object> metadata = node.hasNonNull("metadata")
				? mapper.convertValue(node.get("metadata"), new TypeReference<Map<String, Object>>() {
				}) : Map.of();

		return switch (type) {
			case SYSTEM -> SystemMessage.builder().text(content).metadata(metadata).build();
			case USER -> UserMessage.builder().text(content).metadata(metadata).build();
			case ASSISTANT -> AssistantMessage.builder()
				.content(content)
				.properties(metadata)
				.toolCalls(readToolCalls(node))
				.build();
			case TOOL -> ToolResponseMessage.builder().responses(readToolResponses(node)).metadata(metadata).build();
		};
	}

	private List<AssistantMessage.ToolCall> readToolCalls(JsonNode node) {
		List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
		for (JsonNode item : node.path("toolCalls")) {
			toolCalls.add(new AssistantMessage.ToolCall(item.path("id").asText(), item.path("type").asText(),
					item.path("name").asText(), item.path("arguments").asText()));
		}
		return toolCalls;
	}

	private List<ToolResponseMessage.ToolResponse> readToolResponses(JsonNode node) {
		List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
		for (JsonNode item : node.path("toolResponses")) {
			responses.add(new ToolResponseMessage.ToolResponse(item.path("id").asText(), item.path("name").asText(),
					item.path("responseData").asText()));
		}
		return responses;
	}

}
