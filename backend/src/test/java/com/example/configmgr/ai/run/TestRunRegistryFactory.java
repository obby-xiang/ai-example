package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 轻装配测试的统一 {@link RunRegistry} 工厂。
 *
 * <p>两条纪律：
 * <ol>
 * <li><b>帧存储与用例断言同一实例</b>：FrameStore 固定为 {@code RunStoreFrameStore.of(store)}，
 * 写入落在 {@link com.example.configmgr.ai.conformance.InMemoryRunStore#events(String)} 的同一份归档，
 * 断言对象与真实写入面不分叉；</li>
 * <li><b>普通用例不无差别启用同步唤醒</b>：默认 {@link NoopWakeBus}；唤醒专项显式使用
 * {@link #createWithSynchronousWake}（{@link FakeWakeBus}）。</li>
 * </ol>
 */
public final class TestRunRegistryFactory {

	private TestRunRegistryFactory() {
	}

	/** 默认装配：同一份归档 + 空唤醒总线。 */
	public static RunRegistry create(RunStore store, ObjectMapper mapper, AiProperties properties) {
		FrameStore frames = RunStoreFrameStore.of(store);
		NoopWakeBus wakeBus = new NoopWakeBus();
		return new RunRegistry(store, mapper, properties, frames, wakeBus, new FrameWakeListener(wakeBus), null);
	}

	/** 允许用例自带 FrameStore（共享流水/钩子）时使用；唤醒仍为默认空总线。 */
	public static RunRegistry create(RunStore store, ObjectMapper mapper, AiProperties properties, FrameStore frames) {
		NoopWakeBus wakeBus = new NoopWakeBus();
		return new RunRegistry(store, mapper, properties, frames, wakeBus, new FrameWakeListener(wakeBus), null);
	}

	/** 固定写出器替身：决策侧与续跑侧必须拿到同一个实例时使用。 */
	public static RunRegistry create(RunStore store, ObjectMapper mapper, AiProperties properties,
			SseChatEmitter fixedEmitter) {
		FrameStore frames = RunStoreFrameStore.of(store);
		NoopWakeBus wakeBus = new NoopWakeBus();
		return new RunRegistry(store, mapper, properties, frames, wakeBus, new FrameWakeListener(wakeBus), null) {
			@Override
			public SseChatEmitter of(String runId) {
				return fixedEmitter;
			}
		};
	}

	/** 唤醒专项：同步唤醒总线（{@link FakeWakeBus}）。 */
	public static RunRegistry createWithSynchronousWake(RunStore store, ObjectMapper mapper, AiProperties properties) {
		FrameStore frames = RunStoreFrameStore.of(store);
		FakeWakeBus wakeBus = new FakeWakeBus();
		return new RunRegistry(store, mapper, properties, frames, wakeBus, new FrameWakeListener(wakeBus), null);
	}

	/** 唤醒专项：外部持有总线时使用（订阅/发布次数可断言）。 */
	public static RunRegistry createWithWakeBus(RunStore store, ObjectMapper mapper, AiProperties properties,
			FrameWakeBus wakeBus) {
		FrameStore frames = RunStoreFrameStore.of(store);
		return new RunRegistry(store, mapper, properties, frames, wakeBus, new FrameWakeListener(wakeBus), null);
	}
}
