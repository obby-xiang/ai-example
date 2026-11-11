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
 *
 * <p>当前 {@link FrameWakeBus} 契约：{@code publish(runId, upperBoundSeq, terminal)} 与
 * {@code subscribeHandle(runId, generation, onWake) -> Subscription}。本替身只服务唤醒专项用例；
 * 普通帧序用例走 {@link TestRunRegistryFactory} 的默认空总线，不无差别启用同步唤醒。
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
	public void publish(String runId, long upperBoundSeq, boolean terminal) {
		if (this.publishFails) {
			throw new IllegalStateException("PUBLISH 失败（模拟 Redis 抖动）");
		}
		this.published.add(runId + "@" + upperBoundSeq + (terminal ? ":terminal" : ""));
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
	public Subscription subscribeHandle(String runId, long generation, LongConsumer onWake) {
		this.subscribes++;
		if (!this.subscribeSucceeds) {
			// 与生产实现同口径：订阅失败 = 返回 null（监听器据此回滚整桶）。
			return null;
		}
		this.subscribers.put(runId, onWake);
		return () -> {
			this.unsubscribes++;
			this.subscribers.remove(runId);
		};
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

	/** 模拟"另一实例/旧连接把频道订阅撤掉"（不经本实例的 Subscription 句柄）。 */
	void unsubscribe(String runId) {
		if (this.subscribers.remove(runId) != null) {
			this.unsubscribes++;
		}
	}
}
