package com.example.spike.hitl;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 挂起门：由官方循环内的工具执行线程 await，由 HTTP 端点 complete。
 * 语义与参照实现 AiSession.ConfirmGate 一致（latch 阻塞 + 超时），
 * 但把“前端工具结果回灌”与“HITL 确认”抽象为同一构件（value 泛型）。
 */
public final class Gate<T> {

	private final String toolCallId;

	private final String toolName;

	private final CountDownLatch latch = new CountDownLatch(1);

	private final AtomicReference<T> value = new AtomicReference<>();

	private final long createdAt = System.currentTimeMillis();

	private volatile long releasedAt;

	public Gate(String toolCallId, String toolName) {
		this.toolCallId = toolCallId;
		this.toolName = toolName;
	}

	public String toolCallId() {
		return this.toolCallId;
	}

	public String toolName() {
		return this.toolName;
	}

	/** 阻塞等待：返回 null 表示超时（调用方按各自语义处理）。 */
	public T await(int seconds) {
		try {
			if (!this.latch.await(seconds, TimeUnit.SECONDS)) {
				return null;
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return null;
		}
		return this.value.get();
	}

	/** 释放门；重复调用返回 false。 */
	public boolean complete(T result) {
		if (this.latch.getCount() == 0L) {
			return false;
		}
		this.value.set(result);
		this.releasedAt = System.currentTimeMillis();
		this.latch.countDown();
		return true;
	}

	public boolean isDone() {
		return this.latch.getCount() == 0L;
	}

	public long waitedMs() {
		long end = this.releasedAt == 0L ? System.currentTimeMillis() : this.releasedAt;
		return end - this.createdAt;
	}

}
