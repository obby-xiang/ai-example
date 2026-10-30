package com.example.configmgr.ai.run;

import java.util.function.LongConsumer;

/**
 * 帧唤醒的发布/订阅面（B23L1 批① / 设计稿 §2.1 的 {@code PUBLISH ai:wake:<runId>}）。
 *
 * <p>
 * 载荷<b>只有上界 seq</b>，不传帧体：唤醒是"有新帧了，自己去事件日志取"的信号，
 * 帧体永远以 {@link FrameStore} 为唯一事实源。于是 pub/sub 丢消息不丢正确性 ——
 * 客户端带 {@code ?lastSeq} 重连时照样从事件日志补回来（设计稿 §6 at-most-once 边界）。
 *
 * <p>
 * 接口只做传输，不做去重也不做进度：进度在 {@link FrameWakeListener} 的订阅者一侧
 * （每个写出器记住"本地已扇出到哪儿"）。
 */
public interface FrameWakeBus {

	/**
	 * 唤醒频道前缀：{@code ai:wake:<runId>}；生产实现（{@code RedisFrameWakeBus}）在 runId 前加
	 * <b>身份段</b> {@code ai:wake:<tenantId>:<userId>:<runId>} —— 同 runId 的两个租户不得互相唤醒。
	 */
	String CHANNEL_PREFIX = "ai:wake:";

	/** 发布一次唤醒（载荷 = 上界 seq）。失败只记日志 —— 唤醒可丢。 */
	void publish(String runId, long upperBoundSeq);

	/**
	 * 按 run 订阅唤醒（同一 run 重复订阅由实现替换旧监听器）。
	 *
	 * @return true = 订阅已生效；false = 降级（无连接工厂 / 容器起不来 / Redis 抖动）。
	 *         调用方（{@link FrameWakeListener#register}）据此<b>回退</b>登记位并等下一次重试 ——
	 *         静默吞掉失败会让该实例的跨实例唤醒在整段连接期内缺席且不可观测。
	 */
	boolean subscribe(String runId, LongConsumer onWake);

	/** 取消该 run 的订阅（最后一个本地订阅者断连时调用）。 */
	void unsubscribe(String runId);

	/**
	 * <b>带订阅代次</b>的订阅：同一个（身份, run）的第 N 次订阅带代次 N。
	 *
	 * <p>为什么要代次：订阅/退订都在锁外做（Redis I/O），"旧代次的 subscribe 晚到"与"旧代次的
	 * unsubscribe 晚到"都可能落在新订阅之后。代次让实现方能判定先后 ——
	 * <ul>
	 * <li>晚到的<b>旧</b> subscribe 不得顶掉更新的订阅（返回 false）；</li>
	 * <li>晚到的<b>旧</b> unsubscribe 不得摘掉更新的订阅（no-op）。</li>
	 * </ul>
	 *
	 * <p>默认实现退化为无代次版本（不区分先后的实现/单测替身用）；生产实现
	 * {@code RedisFrameWakeBus} 覆写为按代次比较。
	 */
	default boolean subscribe(String runId, long generation, LongConsumer onWake) {
		return subscribe(runId, onWake);
	}

	/**
	 * <b>带订阅代次</b>的退订：只有"当前订阅就是这一代"才摘掉。
	 *
	 * <p>这是"旧桶补偿退订不得误退继任订阅"的实现面：退订按<b>代次 + 监听器对象</b>精确匹配，
	 * 绝不按"频道当前监听器"摘。默认实现退化为无代次版本。
	 */
	default void unsubscribe(String runId, long generation) {
		unsubscribe(runId);
	}

}
