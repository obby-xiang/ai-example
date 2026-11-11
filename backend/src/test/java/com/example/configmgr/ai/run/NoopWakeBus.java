package com.example.configmgr.ai.run;

import java.util.function.LongConsumer;

/**
 * 默认空唤醒总线：普通帧序/控制器用例不关心跨实例唤醒，避免把"同步唤醒"当成每条用例的隐式前提。
 *
 * <p>唤醒专项用例请显式使用 {@link FakeWakeBus}（或 {@link TestRunRegistryFactory#createWithSynchronousWake}）。
 */
final class NoopWakeBus implements FrameWakeBus {

	@Override
	public void publish(String runId, long upperBoundSeq, boolean terminal) {
		// 空实现：不跨实例唤醒，也不影响本地出帧
	}

	@Override
	public Subscription subscribeHandle(String runId, long generation, LongConsumer onWake) {
		return () -> {
		};
	}
}
