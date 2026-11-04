package com.example.configmgr.ai.run;

import java.util.function.LongConsumer;

/**
 * 帧唤醒协议；帧体仍以事件日志为事实源，唤醒只携带上界序号。
 *
 * <p><b>频道身份</b>：发布/订阅都必须处在<b>显式 tenant/user 作用域</b>内（生产由
 * {@code SseChatEmitter} / {@code FrameWakeListener} 按本轮记录身份建立）。没有身份作用域时
 * 实现方必须降级为"本拍不发布/不订阅"—— 不存在无身份的裸 {@code runId} 频道。
 */
public interface FrameWakeBus {

	String CHANNEL_PREFIX = "ai:wake:";

	interface Subscription extends AutoCloseable {

		@Override
		void close();

	}

	void publish(String runId, long upperBoundSeq, boolean terminal);

	Subscription subscribeHandle(String runId, long generation, LongConsumer onWake);

}
