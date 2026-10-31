package com.example.configmgr.ai.memory;

import java.util.List;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

/**
 * {@link RunScopedChatMemory} 的生产实现：读/清空委托官方窗口记忆，{@code add} 落到按轮合并写入口。
 *
 * <p>{@link #add(String, List)}（{@link ChatMemory} 契约，不带轮次）用
 * {@link MemoryTurn#UNATTRIBUTED} 追加 —— 它只覆盖"无归属"这一个块，<b>不会</b>整窗覆盖别的 run。
 * 也就是说：即便调用方没解析出本轮，退化的也只是"谁的块"，不是"别人的历史活不活"。
 */
public final class PerRunChatMemory implements RunScopedChatMemory {

	private final ChatMemory delegate;

	private final RunUpsertChatMemoryRepository repository;

	private final int maxMessages;

	public PerRunChatMemory(ChatMemory delegate, RunUpsertChatMemoryRepository repository, int maxMessages) {
		this.delegate = delegate;
		this.repository = repository;
		this.maxMessages = maxMessages;
	}

	@Override
	public void add(String conversationId, List<Message> messages) {
		add(conversationId, MemoryTurn.UNATTRIBUTED, messages);
	}

	@Override
	public void add(String conversationId, MemoryTurn turn, List<Message> messages) {
		this.repository.upsertRun(conversationId, turn == null ? MemoryTurn.UNATTRIBUTED : turn, messages,
				this.maxMessages, false);
	}

	@Override
	public List<Message> get(String conversationId) {
		return this.delegate.get(conversationId);
	}

	@Override
	public void clear(String conversationId) {
		this.delegate.clear(conversationId);
	}

}
