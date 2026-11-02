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
 *
 * <p>
 * 订阅用<b>精确句柄</b>：{@link Subscription#close()} 只摘掉本句柄那一条监听器，
 * 所以旧桶晚到的补偿退订不会碰到继任订阅 —— 先后判定由句柄本身承载，总线不必再保存代次。
 */
public interface FrameWakeBus {

	/**
	 * 唤醒频道前缀：{@code ai:wake:<runId>}；生产实现（{@code RedisFrameWakeBus}）在 runId 前加
	 * <b>身份段</b> {@code ai:wake:<tenantId>:<userId>:<runId>} —— 同 runId 的两个租户不得互相唤醒。
	 */
	String CHANNEL_PREFIX = "ai:wake:";

	/** 一次订阅的精确句柄：{@link #close()} 只摘自己这一条监听器，重复 close 无副作用。 */
	interface Subscription extends AutoCloseable {

		@Override
		void close();

	}

	/** 发布一次唤醒（载荷 = 上界 seq）。失败只记日志 —— 唤醒可丢。 */
	void publish(String runId, long upperBoundSeq);

	/**
	 * 建立一次订阅并返回<b>精确句柄</b>。
	 *
	 * <p>句柄捕获的就是本次安装到容器上的监听器对象，退订按对象摘 —— 与"频道当前是哪一条"无关，
	 * 因此迟到的 close 不会误退继任订阅。
	 *
	 * <p>{@code generation} 只被<b>默认实现</b>用于兼容无句柄的旧实现/测试替身。生产实现
	 * （{@code RedisFrameWakeBus}）覆写本方法并<b>忽略代次</b>：它返回捕获监听器的句柄，
	 * 不做代次比较，也不提供"旧代次不得顶掉新订阅"的保护 —— 精确退订由句柄承担。
	 *
	 * @return null = 降级（无连接工厂 / 容器起不来 / Redis 抖动）。调用方（{@link FrameWakeListener#register}）
	 *         据此<b>回滚</b>登记位并等下一次重试 —— 静默吞掉失败会让该实例的跨实例唤醒在整段
	 *         连接期内缺席且不可观测
	 */
	default Subscription subscribeHandle(String runId, long generation, LongConsumer onWake) {
		return subscribe(runId, generation, onWake) ? () -> unsubscribe(runId, generation) : null;
	}

	/**
	 * 兼容口：无句柄订阅。
	 *
	 * @return true = 订阅已生效；false = 降级。新代码用 {@link #subscribeHandle} 拿句柄
	 */
	boolean subscribe(String runId, LongConsumer onWake);

	/** 兼容口：无句柄退订（调用方拿不到精确句柄时按频道退订）。 */
	void unsubscribe(String runId);

	/**
	 * 兼容口（代次口径）：<b>仅供无句柄的旧实现/测试替身</b>，默认退化为无代次版本。
	 *
	 * <p>生产路径是 {@link #subscribeHandle}，不是本方法：{@code RedisFrameWakeBus} 不按代次比较，
	 * 也不提供"旧代次不得顶掉新订阅"的保护；它的继任隔离来自句柄精确退订。
	 * 这里的代次口径只服务于仍无句柄的测试替身。
	 */
	default boolean subscribe(String runId, long generation, LongConsumer onWake) {
		return subscribe(runId, onWake);
	}

	/** 兼容口（代次口径）：默认退化为无句柄退订；生产实现用 {@link Subscription#close()}，不按代次判定。 */
	default void unsubscribe(String runId, long generation) {
		unsubscribe(runId);
	}

}
