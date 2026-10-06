package com.example.spike.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内的“唤醒开关”。注意：它<strong>不是</strong>挂起态本体——挂起态在 Redis 里；
 * 本对象只负责把“外部输入已写入 Redis”这件事通知给当前进程里那条阻塞中的线程。
 * 进程重启后它一定为空，而续跑路径完全不依赖它（只读 Redis）。
 */
public final class Gate<T> {

	private final String key;

	private final CountDownLatch latch = new CountDownLatch(1);

	private final AtomicReference<T> value = new AtomicReference<>();

	private final long createdAt = System.currentTimeMillis();

	private volatile long releasedAt;

	public Gate(String key) {
		this.key = key;
	}

	public String key() {
		return this.key;
	}

	/** 返回 null 表示超时。 */
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
