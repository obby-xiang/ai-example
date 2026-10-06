package com.example.spike.core;

/** 续跑时挂起项仍未落定（前端/人还没给输入）——调用方必须把状态留在 Redis 并退出，不得改动循环。 */
public class PendingUnresolvedException extends RuntimeException {

	private final PendingToolCall pending;

	public PendingUnresolvedException(PendingToolCall pending) {
		super("pending tool call unresolved: " + pending.toolCallId + " (" + pending.name + ")");
		this.pending = pending;
	}

	public PendingToolCall pending() {
		return this.pending;
	}

}
