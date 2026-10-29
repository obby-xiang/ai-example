package com.example.configmgr.ai.session;

import com.example.configmgr.ai.run.InstanceId;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 后端会话发号（前端不再生成 sessionId）。
 *
 * <h2>编号结构（十进制拼装，全正 long）</h2>
 * <pre>
 * sessionId = 时间戳(ms) × 10^6 + 实例位(0..999) × 10^3 + 实例内序列(0..999)
 * </pre>
 * 三个来源各解决一件事：
 * <ul>
 * <li><b>时间戳</b>：跨时间不重复，且 id 本身可读（排障时能看出建会话的时刻）；</li>
 * <li><b>实例内序列</b>：同一毫秒内的调用递增，解决"同毫秒取两次号"；</li>
 * <li><b>实例位</b>：多实例在同一毫秒同时取号时区分来源。实例位优先取外部配置
 * （{@code -Dapp.instance-slot} / {@code APP_INSTANCE_SLOT}），未配置时取本进程实例标识的
 * 稳定散列 —— 因此多实例部署应显式配置，散列只有 1/1000 的碰撞概率。</li>
 * </ul>
 *
 * <p>
 * 容量：时间戳上限 {@code (Long.MAX_VALUE - 999999) / 10^6} ms ≈ 公元 2262 年，不存在近期溢出。
 * 单实例单毫秒最多 1000 个号；超出后短暂等待下一毫秒（会话创建频率远低于此）。
 *
 * <p>
 * <b>唯一性不是隔离</b>：即使发号全局不重复，数据归属仍必须按 {@code tenantId/userId} 判定
 * （设计稿「ID 与归属约束」）。
 */
@Component
public class SessionIdGenerator {

	/** 实例位取值范围。 */
	static final long INSTANCE_SLOTS = 1000L;

	/** 单实例单毫秒序列上限。 */
	static final long SEQUENCE_CAP = 1000L;

	private static final long IDS_PER_MS = INSTANCE_SLOTS * SEQUENCE_CAP;

	private static final long MAX_TIMESTAMP_MS = (Long.MAX_VALUE - IDS_PER_MS + 1L) / IDS_PER_MS;

	private static final long MAX_WAIT_NANOS = 1_000_000_000L;

	private final LongSupplier clock;

	private final long instanceSlot;

	private final AtomicLong sequence = new AtomicLong();

	private long lastTimestampMs;

	public SessionIdGenerator() {
		this(System::currentTimeMillis, resolveInstanceSlot());
	}

	/**
	 * @param clock        毫秒时钟（测试可注入固定值）
	 * @param instanceSlot 实例位（{@code 0..999}）
	 */
	public SessionIdGenerator(LongSupplier clock, long instanceSlot) {
		if (instanceSlot < 0L || instanceSlot >= INSTANCE_SLOTS) {
			throw new IllegalArgumentException("instanceSlot 必须在 0..999：" + instanceSlot);
		}
		this.clock = clock;
		this.instanceSlot = instanceSlot;
		this.lastTimestampMs = Long.MIN_VALUE;
	}

	/** 取一个新号（线程安全；同一实例的并发调用不会拿到相同值）。 */
	public synchronized long next() {
		long timestampMs = this.clock.getAsLong();
		if (timestampMs > MAX_TIMESTAMP_MS) {
			throw new IllegalStateException("时间戳超出会话发号容量：" + timestampMs);
		}
		if (timestampMs < this.lastTimestampMs) {
			throw new IllegalStateException("时钟回拨，拒绝发号（last=" + this.lastTimestampMs + " now=" + timestampMs + "）");
		}
		if (timestampMs == this.lastTimestampMs) {
			long nextSequence = this.sequence.incrementAndGet();
			if (nextSequence >= SEQUENCE_CAP) {
				timestampMs = awaitNextMillisecond(timestampMs);
				this.lastTimestampMs = timestampMs;
				this.sequence.set(0L);
			}
		}
		else {
			this.lastTimestampMs = timestampMs;
			this.sequence.set(0L);
		}
		return timestampMs * IDS_PER_MS + this.instanceSlot * SEQUENCE_CAP + this.sequence.get();
	}

	private long awaitNextMillisecond(long stalled) {
		long deadline = System.nanoTime() + MAX_WAIT_NANOS;
		while (true) {
			long now = this.clock.getAsLong();
			if (now > stalled) {
				return now;
			}
			if (System.nanoTime() > deadline) {
				throw new IllegalStateException("同一毫秒内会话发号已用尽且时钟未前进：" + stalled);
			}
			Thread.onSpinWait();
		}
	}

	/** 实例位：外部配置优先，未配置取实例标识的稳定散列。 */
	public static long resolveInstanceSlot() {
		String configured = System.getProperty("app.instance-slot");
		if (configured == null || configured.isBlank()) {
			configured = System.getenv("APP_INSTANCE_SLOT");
		}
		if (configured != null && !configured.isBlank()) {
			try {
				long slot = Long.parseLong(configured.trim());
				if (slot >= 0L && slot < INSTANCE_SLOTS) {
					return slot;
				}
				throw new IllegalStateException("app.instance-slot 必须在 0..999：" + configured);
			}
			catch (NumberFormatException ex) {
				throw new IllegalStateException("app.instance-slot 必须是整数：" + configured, ex);
			}
		}
		return Math.floorMod(InstanceId.get().hashCode(), (int) INSTANCE_SLOTS);
	}

}
