package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * <b>取消收尾的进程内状态按「身份 + runId」分条</b>（在飞去重 / 重试预算 / 原子段监视器）。
 *
 * <h2>缺陷形态</h2>
 * 旧实现三张表都是裸 runId：
 * <ul>
 * <li>{@code inFlight}：A 已经交棒收尾 X 时，B 的同名轮次交棒被<b>静默拒绝</b>（"已在交棒中"），
 *     B 的取消事实于是没人收尾 —— 终帧与终态都不落；</li>
 * <li>{@code attempts}：两个身份共用一份重试预算（A 的失败次数让 B 提前耗尽）；</li>
 * <li>{@code finalizeMonitor}：A、B 的同名轮次落进同一个监视器条带（串行化，最坏是互相拖慢；
 *     条带按身份拼进键后，只有哈希碰撞才会重叠，且碰撞只损失并行度）。</li>
 * </ul>
 *
 * <p>本类钉住：同身份重复交棒仍去重；换一个身份不得被挡；监视器按身份分条。
 */
class CancelledRunFinalizerIdentityIsolationTest {

	private static final String RUN_ID = "run-shared-finalize";

	private static final TenantUser A = new TenantUser(1L, 101L);

	private static final TenantUser B = new TenantUser(2L, 202L);

	private final AiProperties properties = new AiProperties();

	/** 只捕获不执行：交棒位因此一直持有（模拟执行体还在飞）。 */
	private final List<Runnable> submitted = new ArrayList<>();

	private final Executor capture = this.submitted::add;

	private final CancelledRunFinalizer.Scheduler scheduler = (task, delay) -> {
		this.submitted.add(task);
		return () -> {
		};
	};

	private final CancelledRunFinalizer finalizer = new CancelledRunFinalizer(provider(), this.capture,
			this.properties, this.scheduler);

	@Test
	void inFlightDedupAndFinalizeMonitorAreScopedToIdentityPlusRunId() {
		assertThat(as(A, () -> this.finalizer.handOff(RUN_ID, CancelledRunFinalizer.CAUSE_PARK_EXIT)))
			.as("A 的交棒受理")
			.isTrue();
		assertThat(as(A, () -> this.finalizer.handOff(RUN_ID, CancelledRunFinalizer.CAUSE_PARK_EXIT)))
			.as("同身份重复交棒仍被在飞去重（不重复派发）")
			.isFalse();
		assertThat(as(B, () -> this.finalizer.handOff(RUN_ID, CancelledRunFinalizer.CAUSE_PARK_EXIT)))
			.as("另一身份的同名轮次不得被 A 的在飞去重挡住")
			.isTrue();

		Object monitorA = as(A, () -> this.finalizer.finalizeMonitor(RUN_ID));
		Object monitorB = as(B, () -> this.finalizer.finalizeMonitor(RUN_ID));
		assertThat(monitorA).as("同身份同 runId 恒返回同一把监视器")
			.isSameAs(as(A, () -> this.finalizer.finalizeMonitor(RUN_ID)));
		assertThat(monitorA).as("监视器条带按身份分（不是裸 runId）").isNotSameAs(monitorB);

		// 终态清理也只摘本身份的条目：A 释放后 B 仍在飞的那一任不受影响
		as(A, () -> {
			this.finalizer.forget(RUN_ID);
			return null;
		});
		assertThat(as(A, () -> this.finalizer.handOff(RUN_ID, CancelledRunFinalizer.CAUSE_PARK_EXIT)))
			.as("A 的终态清理释放了 A 的交棒位")
			.isTrue();
		assertThat(as(B, () -> this.finalizer.handOff(RUN_ID, CancelledRunFinalizer.CAUSE_PARK_EXIT)))
			.as("A 的清理不得摘掉 B 的在飞位")
			.isFalse();
	}

	private static ObjectProvider<ResumeService> provider() {
		@SuppressWarnings("unchecked")
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		return provider;
	}

	private static <T> T as(TenantUser identity, Supplier<T> body) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return body.get();
		}
	}

}
