package com.example.configmgr.ai.session;

import com.example.configmgr.file.FileStorageMultiInstanceGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.LockSupport;
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
 * <li><b>实例位</b>：多实例在同一毫秒同时取号时区分来源。实例位只来自显式配置
 * {@code app.instance-slot} / 环境变量 {@code APP_INSTANCE_SLOT}（{@code 0..999}）：
 * <b>单实例默认 0</b>；多实例/集群 profile 下未配置即启动失败，不允许用随机实例标识散列兜底
 * （散列不可复现、不可审计，碰撞时表现为两条会话静默合并）。启动日志会打印生效实例位。</li>
 * </ul>
 *
 * <p>
 * 容量：时间戳上限 {@code (Long.MAX_VALUE - 999999) / 10^6} ms ≈ 公元 2262 年，不存在近期溢出。
 * 单实例单毫秒最多 1000 个号。
 *
 * <h2>运行期边界（不占锁自旋）</h2>
 * 取号锁只覆盖"读时钟 + 改序列"这几条语句；等待全部发生在锁外：
 * <ul>
 * <li><b>小幅时钟回拨</b>（≤ {@value #MAX_BACKWARD_WAIT_MS}ms）：让出线程等待时钟追平；</li>
 * <li><b>大幅回拨</b>或等待超窗：抛 {@link UnavailableException}（HTTP 层映射 503，不重试成 500）；</li>
 * <li><b>单毫秒序列耗尽</b>：锁外短睡等待下一毫秒，有限次后抛 {@link UnavailableException}。</li>
 * </ul>
 * 任何失败都不会把发号路径整体阻塞 1 秒。
 *
 * <p>
 * <b>唯一性不是隔离</b>：即使发号全局不重复，数据归属仍必须按 {@code tenantId/userId} 判定
 * （设计稿「ID 与归属约束」）。
 */
@Slf4j
@Component
public class SessionIdGenerator {

	/** 实例位取值范围。 */
	static final long INSTANCE_SLOTS = 1000L;

	/** 单实例单毫秒序列上限。 */
	static final long SEQUENCE_CAP = 1000L;

	/** 时钟回拨的容忍窗口（等待追平的上限）。 */
	static final long MAX_BACKWARD_WAIT_MS = 1_000L;

	private static final long MAX_BACKWARD_WAIT_NANOS = MAX_BACKWARD_WAIT_MS * 1_000_000L;

	/** 序列耗尽后等待下一毫秒的次数上限（每次 1ms，约 100ms 后失败，而不是占锁 1s）。 */
	private static final int MAX_EXHAUSTION_RETRIES = 100;

	private static final long IDS_PER_MS = INSTANCE_SLOTS * SEQUENCE_CAP;

	private static final long MAX_TIMESTAMP_MS = (Long.MAX_VALUE - IDS_PER_MS + 1L) / IDS_PER_MS;

	private final LongSupplier clock;

	private final long instanceSlot;

	private final Object lock = new Object();

	/** 上一次发号的时间戳；序列位只在 time==last 时递增（全部在 {@link #lock} 内读写）。 */
	private long lastTimestampMs;

	private long sequence;

	public SessionIdGenerator() {
		this(System::currentTimeMillis, 0L);
	}

	/**
	 * Spring 装配入口：解析并审计实例位（多实例/集群 profile 下必须显式配置）。
	 */
	@Autowired
	public SessionIdGenerator(Environment environment) {
		this(System::currentTimeMillis, resolveInstanceSlot(environment));
		log.info("会话发号实例位 instanceSlot={} multiInstance={}（配置项 app.instance-slot / APP_INSTANCE_SLOT；"
				+ "多实例部署各实例必须不同）", this.instanceSlot, multiInstanceReason(environment));
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
	public long next() {
		long backwardDeadlineNanos = 0L;
		int exhaustionRetries = 0;
		for (;;) {
			long now = this.clock.getAsLong();
			if (now > MAX_TIMESTAMP_MS) {
				throw new UnavailableException("时间戳超出会话发号容量：" + now, false);
			}
			long waitMs;
			synchronized (this.lock) {
				if (now < this.lastTimestampMs) {
					waitMs = this.lastTimestampMs - now;
					if (waitMs > MAX_BACKWARD_WAIT_MS) {
						throw new UnavailableException("时钟回拨超过容忍窗口（last=" + this.lastTimestampMs + " now=" + now
								+ "）：拒绝发号", false);
					}
				}
				else if (now == this.lastTimestampMs) {
					if (this.sequence + 1L < SEQUENCE_CAP) {
						this.sequence++;
						return assemble(now, this.sequence);
					}
					waitMs = -1L;
				}
				else {
					this.lastTimestampMs = now;
					this.sequence = 0L;
					return assemble(now, 0L);
				}
			}
			// 以下等待全部在锁外：发号路径不持有监视器睡觉。
			if (waitMs < 0L) {
				if (++exhaustionRetries > MAX_EXHAUSTION_RETRIES) {
					throw new UnavailableException("同一毫秒内会话发号容量已用尽且时钟未前进：" + now, true);
				}
				LockSupport.parkNanos(1_000_000L);
				continue;
			}
			if (backwardDeadlineNanos == 0L) {
				backwardDeadlineNanos = System.nanoTime() + MAX_BACKWARD_WAIT_NANOS;
			}
			if (System.nanoTime() > backwardDeadlineNanos) {
				throw new UnavailableException("时钟回拨未在 " + MAX_BACKWARD_WAIT_MS + "ms 内追平，拒绝发号", false);
			}
			LockSupport.parkNanos(waitMs * 1_000_000L + 1_000_000L);
		}
	}

	private long assemble(long timestampMs, long sequenceInMillisecond) {
		return timestampMs * IDS_PER_MS + this.instanceSlot * SEQUENCE_CAP + sequenceInMillisecond;
	}

	/** 实例位（审计/启动日志用）。 */
	public long instanceSlot() {
		return this.instanceSlot;
	}

	/**
	 * 解析实例位：只接受显式配置（{@code app.instance-slot} / {@code APP_INSTANCE_SLOT}），
	 * 单实例默认 0，多实例/集群未配置即失败。
	 */
	static long resolveInstanceSlot(Environment environment) {
		String configured = environment == null ? null : environment.getProperty("app.instance-slot");
		if (configured == null || configured.isBlank()) {
			if (multiInstanceReason(environment) != null) {
				throw new IllegalStateException("多实例/集群部署缺少实例位 app.instance-slot（环境变量 "
						+ "APP_INSTANCE_SLOT，取值 0..999 且各实例不同）：判据=" + multiInstanceReason(environment));
			}
			return 0L;
		}
		long slot;
		try {
			slot = Long.parseLong(configured.trim());
		}
		catch (NumberFormatException ex) {
			throw new IllegalStateException("app.instance-slot 必须是整数：" + configured, ex);
		}
		if (slot < 0L || slot >= INSTANCE_SLOTS) {
			throw new IllegalStateException("app.instance-slot 必须在 0..999：" + configured);
		}
		return slot;
	}

	/** 多实例判据（复用文件存储守卫的 profile 集合；null = 单实例）。 */
	static String multiInstanceReason(Environment environment) {
		if (environment == null) {
			return null;
		}
		for (String profile : environment.getActiveProfiles()) {
			if (FileStorageMultiInstanceGuard.MULTI_INSTANCE_PROFILES.contains(profile)) {
				return "profile=" + profile;
			}
		}
		return null;
	}

	/**
	 * 发号暂不可用（时钟回拨超窗 / 单毫秒容量耗尽 / 时间戳越界）。
	 *
	 * <p>
	 * 专用类型而非 {@link IllegalStateException}：HTTP 层需要把它映射为 <b>503</b>
	 * （客户端可重试），而不是 500。
	 */
	public static class UnavailableException extends RuntimeException {

		private final boolean retryable;

		public UnavailableException(String message, boolean retryable) {
			super(message);
			this.retryable = retryable;
		}

		public boolean retryable() {
			return this.retryable;
		}

	}

}
