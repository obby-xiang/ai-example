package com.example.configmgr.ai.memory;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

/**
 * {@link RunScopedChatMemory} 的生产实现：读/清空委托官方窗口记忆，{@code add} 落到按轮合并写入口。
 *
 * <p>不带轮次归属的 {@link #add(String, List)}（{@link ChatMemory} 契约）<b>拒绝写入</b>：
 * 当前协议下每条消息都必须盖本轮 {@code runId}，无归属写入会把历史混进无法归属的块。
 */
public final class PerRunChatMemory implements RunScopedChatMemory {

	private static final Logger log = LoggerFactory.getLogger(PerRunChatMemory.class);

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
		log.warn("memory add without turn ownership refused conversationId={} (every write must carry runId)",
				conversationId);
	}

	@Override
	public void add(String conversationId, MemoryTurn turn, List<Message> messages) {
		if (turn == null) {
			log.warn("memory add without turn ownership refused conversationId={} (every write must carry runId)",
					conversationId);
			return;
		}
		this.repository.upsertRun(conversationId, turn, messages, this.maxMessages, false);
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
