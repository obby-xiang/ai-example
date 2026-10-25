package com.example.configmgr.ai.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史里 <b>SYSTEM 前缀</b>的切分与复位（续跑保 system 批的唯一工具点）。
 *
 * <h2>为什么需要它</h2>
 * 系统消息（人设 + 回答语言 + 工作区快照）不是"可裁剪的历史"：它没有 turn 语义、也不该被
 * 摘要成一段合成消息。而历史裁剪的三个单位都是 turn（{@link ConversationTurns}），首段又
 * 刻意把"第一条 user 之前的残留"一起包住 —— 于是"淘汰最旧一轮"会顺手把 SYSTEM 丢掉。
 *
 * <h2>口径</h2>
 * <ul>
 * <li>只认<b>前缀</b>：历史开头的连续 {@code SYSTEM} 属于系统段（与首轮构造
 *     {@code [system] + memory + [user]} 同形）；</li>
 * <li>裁剪/摘要/硬截断都只处理其后的 body，最后把系统段原样复位到最前；</li>
 * <li>没有系统段时行为与改造前完全一致（body 即全量，返回同一引用）。</li>
 * </ul>
 */
public final class SystemMessages {

	private SystemMessages() {
	}

	/** 历史开头的连续 SYSTEM 条数（0 = 没有系统段）。 */
	public static int leadingCount(List<Message> messages) {
		if (messages == null) {
			return 0;
		}
		int count = 0;
		while (count < messages.size() && isSystem(messages.get(count))) {
			count++;
		}
		return count;
	}

	/** 历史开头的系统段（不可变视图；没有则空表）。 */
	public static List<Message> leading(List<Message> messages) {
		return messages == null ? List.of() : messages.subList(0, leadingCount(messages));
	}

	/** 系统段之后的可裁剪历史（没有系统段时返回入参本身 —— 保持"未改动即同引用"）。 */
	public static List<Message> body(List<Message> messages) {
		int count = leadingCount(messages);
		return count == 0 ? messages : messages.subList(count, messages.size());
	}

	/** 把系统段复位到 body 之前；系统段为空时返回 body 本身（同引用）。 */
	public static List<Message> reattach(List<Message> system, List<Message> body) {
		if (system == null || system.isEmpty()) {
			return body;
		}
		List<Message> out = new ArrayList<>(system.size() + body.size());
		out.addAll(system);
		out.addAll(body);
		return out;
	}

	public static boolean isSystem(Message message) {
		return message != null && message.getMessageType() == MessageType.SYSTEM;
	}

}
