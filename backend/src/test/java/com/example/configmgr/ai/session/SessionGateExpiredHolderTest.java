package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>进程内门的过期清扫</b>（防永久 409 的兜底）。
 *
 * <h2>为什么需要</h2>
 * 进程内单值门是 §13.1 降级形态的本体，但它的记录没有 Redis 那样的 TTL：任何一条"取门后
 * 没走到 release"的路径（异步收尾线程身份不符、线程被 Error 打断、Redis 抖动期取门降级）
 * 都会把 holder 永久留在表里，之后同会话请求一律 409，只能重启进程。
 *
 * <h2>安全口径（不能删除仍活跃持有）</h2>
 * 取门<b>冲突时</b>（即将 409）先做一次清扫，判据缺一不可：
 * <ul>
 * <li><b>过期</b>：自"最后一次确认活着的时刻"起超过一个锁 TTL。确认来自取门成功与 watchdog
 *     续期成功；只要续期还在成功，这条记录就一直新鲜，永远不会被判过期；</li>
 * <li><b>watchdog 已停</b>：续期任务还在跑 = 这次持有仍在主动保活，<b>不删</b>；</li>
 * <li><b>不再持有锁</b>：按记录的锁值回读它自己的锁键，值仍相等 ⇒ 它确实还持有 ⇒
 *     <b>不删</b>（读失败同口径：证明不了是残留就不删）。</li>
 * </ul>
 * 从未确认过锁的降级持有无法用锁值证明，改用更保守的 2×TTL 窗口：单轮机器预算（300s）远小于
 * 该窗口，且挂起等人期间门是释放的，因此它只会收敛真正的残留。
 */
class SessionGateExpiredHolderTest {

	private static final String SESSION = "s-expired-holder";

	private static final long TENANT = 1L;

	private static final long USER = 101L;

	private static final long OTHER_TENANT = 2L;

	private static final long OTHER_USER = 202L;

	private final SessionGateLeaseTokenTest.FakeSessionRedis fake = new SessionGateLeaseTokenTest.FakeSessionRedis();

	private final AiProperties properties = new AiProperties();

	private final AtomicLong now = new AtomicLong(1_000_000L);

	private final SessionGate gate = new SessionGate(this.fake.template, this.properties, this.now::get);

	private final long ttlMs = this.properties.getSession().getLock().getTtl().toMillis();

	@Test
	void expiredLeakedHolderStopsBlockingTheSessionWhileAnActivelyHeldOneSurvives() {
		String leakedKey = SessionGate.gateKey(SESSION, TENANT, USER);
		String activeKey = SessionGate.gateKey(SESSION, OTHER_TENANT, OTHER_USER);
		// 泄漏面：这一轮取过门，随后锁过期消失（释放错过、续期再也续不上）——
		// watchdog 一拍后返回 0 并自停，只剩一条没人释放的进程内记录。
		this.properties.getSession().getLock().setWatchdog(Duration.ofMillis(10));
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT, USER)) {
			assertThat(this.gate.acquire(SESSION, "run-leaked", "token-leaked")).isNull();
		}
		// 锁已过期消失（释放错过 + 续期再也续不上）；watchdog 一拍后续期返回 0 并自停。
		this.fake.values.remove(leakedKey);
		this.fake.expireAtMs.remove(leakedKey);
		awaitLockLost(this.gate);
		// 后续取门不要被 10ms 的 watchdog 刷新存活时刻（本用例用可控时钟判过期）
		this.properties.getSession().getLock().setWatchdog(Duration.ofHours(1));
		// 对照：另一个身份下**仍持锁**的活跃门（锁值仍是自己的）
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(OTHER_TENANT, OTHER_USER)) {
			assertThat(this.gate.acquire(SESSION, "run-active", "token-active")).isNull();
		}
		this.now.addAndGet(this.ttlMs + 1L);

		SessionGate.Busy busy;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT, USER)) {
			busy = this.gate.acquire(SESSION, "run-new", "token-new");
		}

		assertThat(busy).as("过期残留不得把会话永久钉在 409").isNull();
		assertThat(this.fake.value(activeKey)).as("仍活跃持有的门不得被清扫")
			.isEqualTo("run-active|token-active");
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(OTHER_TENANT, OTHER_USER)) {
			SessionGate.Busy blocked = this.gate.acquire(SESSION, "run-other-next", "token-other-next");
			assertThat(blocked).as("活跃持有继续互斥（清扫不得放松单值门）").isNotNull();
			assertThat(blocked.runId()).isEqualTo("run-active");
		}
		assertThat(this.gate.heldSessions()).as("泄漏项已清，活跃项与新取的门各占一条").isEqualTo(2);
	}

	/** 等 watchdog 拍到一次续期失败（轮询而不是裸 sleep，超时立即断言失败）。 */
	private static void awaitLockLost(SessionGate gate) {
		for (int i = 0; i < 200; i++) {
			if (gate.degradedSessions() > 0) {
				return;
			}
			try {
				Thread.sleep(10L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		assertThat(gate.degradedSessions()).as("前置：泄漏 holder 的 watchdog 必须已因丢锁自停").isPositive();
	}

	@Test
	void degradedHolderThatNeverConfirmedALockIsSweptAfterTheConservativeGrace() {
		this.fake.failScripts = true;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT, USER)) {
			assertThat(this.gate.acquire(SESSION, "run-degraded", "token-degraded"))
				.as("Redis 不可用 ⇒ 降级为进程内门（没有可核对的锁值）")
				.isNull();
		}
		this.fake.failScripts = false;
		this.now.addAndGet(2 * this.ttlMs + 1L);

		SessionGate.Busy busy;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT, USER)) {
			busy = this.gate.acquire(SESSION, "run-next", "token-next");
		}

		assertThat(busy).as("降级残留超过保守窗口后不得永久 409").isNull();
	}

}
