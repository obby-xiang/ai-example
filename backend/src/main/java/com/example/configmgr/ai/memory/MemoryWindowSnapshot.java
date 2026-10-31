package com.example.configmgr.ai.memory;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

/**
 * 记忆窗口的一次<b>快照</b>：读到的窗口 + 当时的会话级计数器（版本 / 清空代次）。
 *
 * <h2>为什么压缩写回需要它</h2>
 * 压缩与记忆窗口是<b>跨 run 的整窗改写</b>：L1 把旧工具结果换成占位符、L2 用摘要折叠旧轮、L3 丢掉
 * 最旧的轮次 —— 被改写的大多属于别的 run 的块，必须<b>整窗</b>落盘。整窗写回不能用"重读再合并"
 * （那会把别的写者在这期间新增的消息丢掉，或用旧内容覆盖），只能用读到的这一份做基线做一次 CAS：
 * 版本/代次对得上 ⇒ 写入；对不上 ⇒ <b>放弃</b>（不重试、不拿旧内容重算），下一轮重新压缩。
 *
 * @param version    合并版本（{@code 0} = 尚未写过；成功写入会 +1）
 * @param generation 清空代次（{@code 0} = 从未清空；清空/销毁会 +1）
 * @param messages   读到的那一刻的窗口内容
 */
public record MemoryWindowSnapshot(long version, long generation, List<Message> messages) {

	public MemoryWindowSnapshot {
		messages = messages == null ? List.of() : List.copyOf(messages);
	}

}
