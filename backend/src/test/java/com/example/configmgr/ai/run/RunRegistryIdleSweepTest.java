package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * <b>零订阅条目的空闲回收</b>：{@code events}/{@code confirm} 这类入口会为"当前没有连接"的轮次惰性
 * 建条目（零订阅是常态），只靠终态钩子/{@code close} 收不干净 —— 长跑实例上就是每轮一条、只增不减。
 *
 * <p>本类钉两件事：超过空闲窗口且零订阅的条目<b>会被</b>回收（含 {@code of()} 上的摊还扫描）；
 * 有订阅的条目<b>绝不</b>被回收（回收一个还有连接的写出器会丢帧）。
 */
class RunRegistryIdleSweepTest {

	private static final String RUN_ID = "run-idle-sweep";

	private static final String OTHER_RUN_ID = "run-idle-sweep-other";

	private static final TenantUser A = new TenantUser(1L, 101L);

	private static final TenantUser B = new TenantUser(2L, 202L);

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, new ObjectMapper(), null);

	@Test
	void zeroSubscriberEntriesAreRecycledAfterTheIdleWindow() {
		this.registry.setIdleRecycleAfter(Duration.ofMinutes(10));
		// 摊还扫描关到很远的将来：本用例只看显式扫描，排除 of() 的副作用
		this.registry.setSweepInterval(Duration.ofHours(1));
		SseChatEmitter emitter = as(A, () -> this.registry.of(RUN_ID));
		long nowMs = System.currentTimeMillis();

		assertThat(this.registry.sweepIdle(nowMs + Duration.ofMinutes(5).toMillis()))
			.as("窗口内（刚建好/刚被 of() 触达）不得回收")
			.isZero();
		assertThat(as(A, () -> this.registry.find(RUN_ID))).isSameAs(emitter);

		assertThat(this.registry.sweepIdle(nowMs + Duration.ofMinutes(11).toMillis()))
			.as("超过空闲窗口且零订阅 ⇒ 回收")
			.isEqualTo(1);
		assertThat(as(A, () -> this.registry.find(RUN_ID))).as("条目已摘除（不永久驻留）").isNull();
	}

	@Test
	void sweepKeepsEntriesThatStillHaveSubscribers() {
		this.registry.setIdleRecycleAfter(Duration.ofMinutes(10));
		this.registry.setSweepInterval(Duration.ofHours(1));
		SseChatEmitter subscribed = as(A, () -> this.registry.of(RUN_ID));
		subscribed.attach(mock(SseEmitter.class));
		as(B, () -> this.registry.of(OTHER_RUN_ID));
		long nowMs = System.currentTimeMillis();

		assertThat(this.registry.sweepIdle(nowMs + Duration.ofMinutes(11).toMillis()))
			.as("只回收零订阅的那一条")
			.isEqualTo(1);
		assertThat(as(A, () -> this.registry.find(RUN_ID))).as("有活连接的条目不得被空闲扫描摘掉")
			.isSameAs(subscribed);
		assertThat(as(B, () -> this.registry.find(OTHER_RUN_ID))).isNull();
	}

	@Test
	void ofRunsTheAmortizedSweepSoTheTableDoesNotGrowUnbounded() {
		// 窗口压到 1ms：先前的条目"过期"，本次 of() 刚触达的条目(0ms)仍在窗口内 —— 这正是
		// 生产口径（窗口不为零 ⇒ 刚建好/刚取用的条目不会被同一次扫描摘掉）
		this.registry.setIdleRecycleAfter(Duration.ofMillis(1));
		this.registry.setSweepInterval(Duration.ofMillis(1));
		as(A, () -> this.registry.of(RUN_ID));
		sleep(20L);

		as(B, () -> this.registry.of(OTHER_RUN_ID));

		assertThat(as(A, () -> this.registry.find(RUN_ID)))
			.as("下一次 of() 摊还扫描即摘掉过期的零订阅条目（不依赖外部定时器）")
			.isNull();
		assertThat(as(B, () -> this.registry.find(OTHER_RUN_ID))).as("本次取用的条目仍在").isNotNull();
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static <T> T as(TenantUser identity, Supplier<T> body) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return body.get();
		}
	}

}
