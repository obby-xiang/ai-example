package com.example.configmgr.ai.history;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * synthetic 消息的统一元数据判据。内部指令使用明确的 origin、kind 和 messageId；
 * 正文只承载模型指令，不参与来源识别。压缩摘要使用当前生产的 compaction metadata。
 */
public final class SyntheticMessages {

	/** RollingSummarizer 写在摘要消息 metadata 上的键。 */
	public static final String COMPACTED_FROM = "compactedFrom";

	/** 消息/转录条目的内部来源键，不进入公开历史载荷。 */
	public static final String ORIGIN = "origin";

	/** 合成消息的内部来源值。 */
	public static final String ORIGIN_SYNTHETIC = "SYNTHETIC";

	/** 上下文压缩摘要的内部来源值。 */
	public static final String ORIGIN_COMPACTION_SUMMARY = "COMPACTION_SUMMARY";

	/** 合成消息类别键。 */
	public static final String KIND = "kind";

	/** 合成消息的稳定标识键。 */
	public static final String MESSAGE_ID = "messageId";

	/** 导出收尾一轮纠正的指令前缀；只用于构造正文，不参与来源判断。 */
	public static final String EXPORT_CORRECTION_PREFIX = "（内部纠正，不要向用户复述本段）";

	/** 合成提示的明确类别（additive 诊断；前端不据类别补造展示节点）。 */
	public enum Kind {

		COMPACTION("compaction"),
		STEP_LIMIT("step_limit"),
		TURN_BUDGET("turn_budget"),
		CORRECTION("correction");

		private final String wire;

		Kind(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return this.wire;
		}

	}

	private SyntheticMessages() {
	}

	public static UserMessage internalUser(String text, Kind kind, String messageId) {
		if (kind == null || StringUtils.isBlank(messageId)) {
			throw new IllegalArgumentException("Synthetic message kind and messageId are required");
		}
		return UserMessage.builder().text(text).metadata(Map.of(
				ORIGIN, ORIGIN_SYNTHETIC,
				KIND, kind.wire(),
				MESSAGE_ID, messageId)).build();
	}

	/** 只按明确内部来源识别压缩摘要，不读取正文、标题或固定话术。 */
	public static boolean isCompactionSummary(Message message) {
		Map<String, Object> metadata = message == null ? null : message.getMetadata();
		return metadata != null && ORIGIN_COMPACTION_SUMMARY.equals(metadata.get(ORIGIN));
	}

	/** 返回明确标记的合成类别；缺少任一来源字段或只有相同正文的消息都不是新合成事实。 */
	public static Kind kindOf(Message message) {
		Map<String, Object> metadata = message == null ? null : message.getMetadata();
		if (metadata == null) {
			return null;
		}
		if (isCompactionSummary(message) || metadata.containsKey(COMPACTED_FROM)) {
			return Kind.COMPACTION;
		}
		if (!ORIGIN_SYNTHETIC.equals(metadata.get(ORIGIN))
				|| !(metadata.get(MESSAGE_ID) instanceof String messageId) || StringUtils.isBlank(messageId)) {
			return null;
		}
		Object wire = metadata.get(KIND);
		for (Kind kind : Kind.values()) {
			if (kind.wire.equals(wire)) {
				return kind;
			}
		}
		return null;
	}

	public static boolean isSynthetic(Message message) {
		return kindOf(message) != null;
	}

	/** 写快照/记忆前只去掉明确标记的 correction 指令，其他模型历史保持不变。 */
	public static List<Message> withoutCorrectionInstruction(List<Message> messages) {
		if (CollectionUtils.isEmpty(messages)) {
			return List.of();
		}
		List<Message> out = new ArrayList<>(messages.size());
		for (Message message : messages) {
			if (kindOf(message) != Kind.CORRECTION) {
				out.add(message);
			}
		}
		return out.isEmpty() ? List.of() : out;
	}
}
