package com.example.configmgr.ai.session;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>会话门的两个"假成功"口子</b>（RT-34 / RT-35，FINAL-AUDIT 的 V1 形态）。
 *
 * <h2>RT-35：诊断 GET 失败不得把 {@code Busy.runId} 填成调用方自己</h2>
 * 脚本判定"另一轮持有"之后，本类会再读一次锁值来填 {@code heldBy}/{@code reattach}。旧实现
 * 读失败/键消失时<b>回退成调用方自己的 runId</b>，于是：
 * <ul>
 * <li>{@code ResumeService} 的判据 {@code !runId.equals(busy.runId())} 把"没取到门"读成"取到了"
 *     ⇒ 在<b>没有跨实例会话门</b>的情况下推进续跑（同会话并发两轮）；</li>
 * <li>{@code AiController} 拿它去判死轮 + 强释放 + 给一个指向不存在流的 {@code reattach}。</li>
 * </ul>
 * 本批口径：读不到就是<b>不可知</b>（{@code runId = null}、{@code degraded = true}），
 * 两个消费方都会把它当"被占用"，且强释放遇到空 runId 直接放弃（不猜着删锁）。
 *
 * <h2>RT-34：续期返回 0 必须记降级</h2>
 * 续期脚本返回 0 = "锁已失效/换主"的确定结论。旧实现只在<b>异常</b>分支置 {@code degraded}，
 * 返回 0 只 WARN 一句就停续期 ⇒ 跨实例互斥已经失效，但对外一切如常（类注承诺"不静默"未兑现）。
 */
class SessionGateBusyUnknownTest {

	private static final String SESSION = "s-busy-unknown";

	private static final String LOCK_KEY = SessionGate.LOCK_PREFIX + SESSION;

	private static final String MY_RUN = "run-mine";

	private final AiProperties properties = new AiProperties();

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final ValueOperations<String, String> values = mock(ValueOperations.class);

	/**
	 * 取门脚本一律返回 0（= 另一轮持有），诊断 GET 抛错（Redis 抖动/连接断）。
	 *
	 * <p>这就是 {@code ResumeService} 面对的真实组合：脚本说"有人"，再问"是谁"时问不出来。
	 */
	@SuppressWarnings("unchecked")
	private void lockHeldBySomebodyButHolderUnreadable() {
		when(this.redis.opsForValue()).thenReturn(this.values);
		when(this.values.get(anyString())).thenThrow(new RedisConnectionFailureException("诊断 GET 失败（模拟）"));
		when(this.redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
	}

	@Test
	void unknownHolderIsNeverReportedAsTheCallersOwnRun() {
		lockHeldBySomebodyButHolderUnreadable();
		SessionGate gate = new SessionGate(this.redis, this.properties);

		SessionGate.Busy busy = gate.acquire(SESSION, MY_RUN);

		assertThat(busy).as("另一轮持有 ⇒ 必须有 Busy").isNotNull();
		assertThat(busy.runId())
			.as("RT-35：持有者不可知时不得回退成调用方自己的 runId（否则 ResumeService 会当成取门成功）")
			.isNotEqualTo(MY_RUN);
		assertThat(busy.degraded()).as("不可知的跨实例视图必须如实报降级").isTrue();
		assertThat(gate.heldSessions()).as("没取到门 ⇒ 不得占住进程内门").isZero();
	}

	@Test
	void strongReleaseWithoutAKnownRunIdTouchesNoLock() {
		lockHeldBySomebodyButHolderUnreadable();
		SessionGate gate = new SessionGate(this.redis, this.properties);

		gate.releaseDeadHeld(SESSION, null);

		// 断言面：没有发出任何脚本（没有任何可匹配的持有者，猜一个 runId 去删锁就是静默抢门）。
		org.mockito.Mockito.verify(this.redis, org.mockito.Mockito.never())
			.execute(any(RedisScript.class), anyList(), any(Object[].class));
		assertThat(gate.heldSessions()).isZero();
	}

	@Test
	void renewReturningZeroMarksTheGateDegraded() {
		AtomicLong acquireCalls = new AtomicLong();
		when(this.redis.opsForValue()).thenReturn(this.values);
		when(this.values.get(anyString())).thenReturn(null);
		// 第一次取门成功（1），之后所有续期返回 0（锁已被换主）。
		when(this.redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
			.thenAnswer(invocation -> acquireCalls.getAndIncrement() == 0 ? 1L : 0L);
		this.properties.getSession().getLock().setWatchdog(Duration.ofMillis(10));
		SessionGate gate = new SessionGate(this.redis, this.properties);
		assertThat(gate.acquire(SESSION, MY_RUN)).as("取门成功").isNull();

		SessionGate.Busy busy = awaitDegraded(gate);

		assertThat(busy).as("另一轮尝试取门时必须拿到 Busy").isNotNull();
		assertThat(busy.degraded()).as("RT-34：续期返回 0 ⇒ 跨实例互斥已失效，必须如实报降级").isTrue();
	}

	/** 等 watchdog 拍到一次续期失败（轮询而不是裸 sleep，失败立即断言失败）。 */
	private SessionGate.Busy awaitDegraded(SessionGate gate) {
		SessionGate.Busy busy = null;
		for (int i = 0; i < 200; i++) {
			busy = gate.acquire(SESSION, "run-other");
			if (busy != null && busy.degraded()) {
				return busy;
			}
			try {
				Thread.sleep(10L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return busy;
	}

}
