package com.example.configmgr.ai.run;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;

/**
 * 唤醒总线替身（B23L1 批① 单测）：{@code publish} <b>同步</b>回调本地订阅者
 * —— 把"跨实例唤醒"压成单进程内的确定性调用链；同时记录 subscribe/unsubscribe 次数，
 * 让"断连即退订、无泄漏"成为可断言事实。
 */
final class FakeWakeBus implements FrameWakeBus {

	private final Map<String, LongConsumer> subscribers = new ConcurrentHashMap<>();

	final List<String> published = new CopyOnWriteArrayList<>();

	final List<String> journal;

	int subscribes;

	int unsubscribes;

	FakeWakeBus() {
		this(new CopyOnWriteArrayList<>());
	}

	FakeWakeBus(List<String> journal) {
		this.journal = journal;
	}

	@Override
	public void publish(String runId, long upperBoundSeq) {
		if (this.publishFails) {
			throw new IllegalStateException("PUBLISH 失败（模拟 Redis 抖动）");
		}
		this.published.add(runId + "@" + upperBoundSeq);
		this.journal.add("publish:" + runId);
		LongConsumer listener = this.subscribers.get(runId);
		if (listener != null) {
			listener.accept(upperBoundSeq);
		}
	}

	/** 置 false = 模拟"Redis 抖动 / 容器起不来"的订阅降级（I-3 用例）。 */
	volatile boolean subscribeSucceeds = true;

	/** 置 true = 模拟 PUBLISH 失败（M-4 降级用例：唤醒可丢、本地扇出不受影响）。 */
	volatile boolean publishFails;

	@Override
	public boolean subscribe(String runId, LongConsumer onWake) {
		this.subscribes++;
		if (!this.subscribeSucceeds) {
			return false;
		}
		this.subscribers.put(runId, onWake);
		return true;
	}

	@Override
	public void unsubscribe(String runId) {
		this.unsubscribes++;
		this.subscribers.remove(runId);
	}

	boolean subscribed(String runId) {
		return this.subscribers.containsKey(runId);
	}

	/** 模拟"另一个实例发来的唤醒"（不经本实例的 publish）。 */
	void inject(String runId, long upperBoundSeq) {
		LongConsumer listener = this.subscribers.get(runId);
		if (listener != null) {
			listener.accept(upperBoundSeq);
		}
	}

}
