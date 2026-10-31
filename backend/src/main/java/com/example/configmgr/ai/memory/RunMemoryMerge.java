package com.example.configmgr.ai.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 会话记忆窗口的<b>按轮有序合并</b>（纯函数：同一输入永远得到同一个窗口）。
 *
 * <h2>窗口的形状</h2>
 * 窗口 = 若干<b>run 块</b>，每个块的成员是带同一个 {@code metadata.runId} 的消息；块间按
 * {@code metadata.turnStartedAtMs}（本轮起点，续跑不漂移）升序，同毫秒按 {@code runId} 定序 ——
 * 于是"谁先提交"不影响最终顺序，A 收敛两次、B 收敛一次也只会得到同一个窗口。
 *
 * <h2>写入语义</h2>
 * <ul>
 * <li><b>替换本块</b>（{@code replaceRunBlock=true}，轮末收敛/压缩写回）：本 run 的块整块换成
 *     本轮历史；<b>别的 run 的块原样保留</b>（以当前窗口为准 —— 手里那份旧副本不得截断它）；</li>
 * <li><b>追加到本块</b>（{@code replaceRunBlock=false}，记忆 advisor 的 before/after）：增量接在
 *     本 run 块尾；</li>
 * <li><b>无归属</b>的消息（迁移期旧消息）在替换写里被丢弃 —— 它们已经在本次历史里（会被重新盖章），
 *     留着就会与历史重复；在追加写里保留（增量历史里没有它们，丢就是丢历史）；</li>
 * <li>历史里带着<b>别的 run</b> 的旧副本时直接忽略：它们的权威版本在窗口里由它们自己的写者维护。</li>
 * </ul>
 *
 * <h2>裁剪</h2>
 * 合并后再按 {@code maxMessages} 裁：只从头部丢，且丢弃位置向后推到第一个非 {@code TOOL} 消息 ——
 * 窗口首条不得是孤立的工具结果（上游会 400）。
 */
public final class RunMemoryMerge {

	/** 消息 metadata 里的轮次归属键。 */
	public static final String RUN_ID_KEY = "runId";

	/** 消息 metadata 里的本轮起点键。 */
	public static final String TURN_STARTED_AT_KEY = "turnStartedAtMs";

	private RunMemoryMerge() {
	}

	/** 消息所属的 run（无归属 = {@code null}）。 */
	public static String runIdOf(Message message) {
		Object value = message == null ? null : message.getMetadata().get(RUN_ID_KEY);
		return value instanceof String text && !text.isBlank() ? text : null;
	}

	/** 消息携带的本轮起点（缺省 0）。 */
	public static long turnStartedAtOf(Message message) {
		Object value = message == null ? null : message.getMetadata().get(TURN_STARTED_AT_KEY);
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value instanceof String text) {
			try {
				return Long.parseLong(text);
			}
			catch (NumberFormatException ex) {
				return 0L;
			}
		}
		return 0L;
	}

	/**
	 * 给还没有归属的消息盖上本轮标记（{@code runId}+{@code turnStartedAtMs}）。
	 *
	 * <p>已经带 {@code runId} 的消息<b>原样返回</b>：归属一旦落库就不再改写（跨轮携带的历史消息
	 * 仍属于它原来那一轮），否则"谁最后写谁拥有全部历史"，块划分就失去意义。
	 */
	public static Message stamp(Message message, MemoryTurn turn) {
		if (message == null || turn == null || runIdOf(message) != null) {
			return message;
		}
		Map<String, Object> metadata = new LinkedHashMap<>(message.getMetadata());
		metadata.put(RUN_ID_KEY, turn.runId());
		metadata.put(TURN_STARTED_AT_KEY, turn.turnStartedAtMs());
		return rebuild(message, metadata);
	}

	/**
	 * 合并窗口。
	 *
	 * @param current        当前窗口（redis 里那一份）
	 * @param incoming       本 run 的历史（替换）或增量（追加）
	 * @param turn           本轮归属
	 * @param replaceRunBlock {@code true} = 替换本 run 的块；{@code false} = 追加
	 * @param maxMessages    合并后的条数上限（{@code <=0} = 不限）
	 */
	public static List<Message> merge(List<Message> current, List<Message> incoming, MemoryTurn turn,
			boolean replaceRunBlock, int maxMessages) {
		MemoryTurn owner = turn == null ? MemoryTurn.UNATTRIBUTED : turn;
		Map<String, Block> kept = new LinkedHashMap<>();
		Block existingOwn = null;
		for (Block block : blocksOf(current)) {
			if (block.runId == null) {
				if (!replaceRunBlock) {
					kept.put(UNOWNED, block);
				}
				continue;
			}
			if (block.runId.equals(owner.runId())) {
				existingOwn = block;
				continue;
			}
			kept.put(block.runId, block);
		}

		List<Message> own = new ArrayList<>();
		if (!replaceRunBlock && existingOwn != null) {
			own.addAll(existingOwn.messages);
		}
		if (incoming != null) {
			for (Message message : incoming) {
				if (message == null) {
					continue;
				}
				String messageOwner = runIdOf(message);
				if (messageOwner != null && !messageOwner.equals(owner.runId())) {
					continue;
				}
				own.add(stamp(message, owner));
			}
		}
		if (!own.isEmpty()) {
			// 起点：块已存在 ⇒ 沿用（续跑不漂移）；块是新建的 ⇒ 用本轮起点；本轮起点未知（0）
			// 就只能按"现在"算 —— 未知起点当作"最早的"会把它插到所有已归属块之前，那是错的。
			long startedAt = existingOwn != null && existingOwn.turnStartedAtMs > 0L ? existingOwn.turnStartedAtMs
					: owner.turnStartedAtMs() > 0L ? owner.turnStartedAtMs() : System.currentTimeMillis();
			kept.put(owner.runId(), new Block(owner.runId(), startedAt, own));
		}

		List<Block> ordered = new ArrayList<>(kept.values());
		ordered.sort(Comparator.comparingLong((Block block) -> block.turnStartedAtMs)
			.thenComparing(block -> block.runId == null ? "" : block.runId));
		List<Message> merged = new ArrayList<>();
		for (Block block : ordered) {
			merged.addAll(block.messages);
		}
		return trimKeepingToolPairs(merged, maxMessages);
	}

	/**
	 * 窗口裁剪：只从头部丢，且丢弃位置推到第一个非 {@code TOOL} 消息（tool 配对不得被拆开）。
	 */
	public static List<Message> trimKeepingToolPairs(List<Message> messages, int maxMessages) {
		if (maxMessages <= 0 || messages.size() <= maxMessages) {
			return messages;
		}
		int drop = messages.size() - maxMessages;
		while (drop < messages.size() && messages.get(drop).getMessageType() == MessageType.TOOL) {
			drop++;
		}
		return new ArrayList<>(messages.subList(drop, messages.size()));
	}

	// ── 分块 ────────────────────────────────────────────────────────────────

	/** 无归属消息（迁移期旧数据）的块键。 */
	private static final String UNOWNED = "";

	private static List<Block> blocksOf(List<Message> current) {
		Map<String, Block> blocks = new LinkedHashMap<>();
		if (current == null) {
			return List.of();
		}
		for (Message message : current) {
			if (message == null) {
				continue;
			}
			String runId = runIdOf(message);
			String key = runId == null ? UNOWNED : runId;
			Block block = blocks.get(key);
			if (block == null) {
				blocks.put(key, new Block(runId, Math.max(0L, turnStartedAtOf(message)), new ArrayList<>(List.of(message))));
			}
			else {
				block.messages.add(message);
			}
		}
		return new ArrayList<>(blocks.values());
	}

	private static final class Block {

		private final String runId;

		private final long turnStartedAtMs;

		private final List<Message> messages;

		Block(String runId, long turnStartedAtMs, List<Message> messages) {
			this.runId = runId;
			this.turnStartedAtMs = turnStartedAtMs;
			this.messages = messages;
		}

	}

	/** 按消息类型重建（metadata 是唯一要改的东西；正文/工具调用/工具结果原样保留）。 */
	private static Message rebuild(Message message, Map<String, Object> metadata) {
		if (message instanceof AssistantMessage assistant) {
			return AssistantMessage.builder()
				.content(assistant.getText())
				.properties(metadata)
				.toolCalls(assistant.getToolCalls())
				.build();
		}
		if (message instanceof ToolResponseMessage toolResponse) {
			return ToolResponseMessage.builder().responses(toolResponse.getResponses()).metadata(metadata).build();
		}
		if (message instanceof UserMessage) {
			return UserMessage.builder().text(message.getText()).metadata(metadata).build();
		}
		if (message instanceof SystemMessage) {
			return SystemMessage.builder().text(message.getText()).metadata(metadata).build();
		}
		throw new IllegalStateException("不支持的记忆消息类型：" + message.getMessageType());
	}

}
