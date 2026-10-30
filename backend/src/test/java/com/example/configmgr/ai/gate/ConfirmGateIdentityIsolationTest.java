package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tenant.LocalRunKey;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * <b>确认门的进程内表按「身份 + runId（+ toolCallId）」分条</b>。
 *
 * <h2>缺陷形态</h2>
 * 旧键是裸 {@code runId|toolCallId}（以及裸 runId 的挂起起点/重派发计数）：
 * <ul>
 * <li>A 挂起等卡时，B 的同名轮次被 {@code hasActiveLatch} 判成"本进程有人在等" ⇒ B 的死卡兜底
 *     <b>永不触发</b>（{@code ACTIVE_LATCH_IN_PROCESS}），B 永久停在 SUSPENDED；</li>
 * <li>B 的 {@code disarm}/{@code signal} 会命中 A 的闸门（回收或唤醒别人的等待）；</li>
 * <li>挂起起点与重派发预算共用一份（A 的 {@code suspendMs} 记到 B 头上，A 的失败次数吃光 B 的预算）。</li>
 * </ul>
 *
 * <p>本类钉住：闸门的前缀扫描、预登记回收、唤醒、挂起起点、重派发计数都只在本身份内生效。
 */
class ConfirmGateIdentityIsolationTest {

	private static final String RUN_ID = "run-shared-gate";

	private static final String TOOL_CALL_ID = "call-shared-gate";

	private static final TenantUser A = new TenantUser(1L, 101L);

	private static final TenantUser B = new TenantUser(2L, 202L);

	private final RunStore store = mock(RunStore.class);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, new AiProperties(), this.cancellations,
			this.beacon, List.of());

	@Test
	void latchesAndSuspensionTablesAreScopedToIdentityPlusRunId() {
		as(A, () -> {
			this.gate.arm(RUN_ID, List.of(TOOL_CALL_ID));
			return null;
		});

		assertThat(as(A, () -> this.gate.hasActiveLatch(RUN_ID))).isTrue();
		assertThat(as(B, () -> this.gate.hasActiveLatch(RUN_ID)))
			.as("A 的等待闸门不得让 B 的同名轮次被判成'本进程有人在等'（B 的死卡兜底会被永久挡住）")
			.isFalse();
		assertThat(this.gate.pendingGates()).as("诊断面仍是本进程总闸门数").isEqualTo(1);

		as(B, () -> {
			this.gate.disarm(RUN_ID, List.of(TOOL_CALL_ID));
			return null;
		});
		assertThat(as(A, () -> this.gate.hasActiveLatch(RUN_ID))).as("B 的 disarm 不得回收 A 的预登记闸门")
			.isTrue();
		assertThat(as(B, () -> this.gate.signal(RUN_ID, TOOL_CALL_ID))).as("B 的信号不得命中 A 的闸门")
			.isFalse();

		assertThat(suspensionKeys()).as("A 的挂起起点记在 A 自己的键下").hasSize(1)
			.allSatisfy(key -> assertThat(key).contains("1:101").contains(RUN_ID));
		as(B, () -> {
			this.gate.arm(RUN_ID, List.of(TOOL_CALL_ID));
			return null;
		});
		assertThat(suspensionKeys()).as("B 的挂起起点是另一条，不覆盖 A 的").hasSize(2);
	}

	@Test
	void dispatchRetryBudgetIsClearedOnlyForTheCallingIdentity() {
		@SuppressWarnings("unchecked")
		Map<String, AtomicInteger> retries = (Map<String, AtomicInteger>) internal("dispatchRetries");
		String keyA = LocalRunKey.of(A, RUN_ID);
		retries.put(keyA, new AtomicInteger(3));

		as(B, () -> {
			this.gate.clearRunState(RUN_ID);
			return null;
		});
		assertThat(retries).as("B 的轮终态清理不得清掉 A 的重派发计数").containsKey(keyA);

		as(A, () -> {
			this.gate.clearRunState(RUN_ID);
			return null;
		});
		assertThat(retries).as("A 的清理只摘 A 的那条").doesNotContainKey(keyA);
	}

	@SuppressWarnings("unchecked")
	private java.util.Set<String> suspensionKeys() {
		return ((Map<String, Long>) internal("suspensionStartedAtMs")).keySet();
	}

	private Object internal(String field) {
		try {
			Field declared = ConfirmGate.class.getDeclaredField(field);
			declared.setAccessible(true);
			return declared.get(this.gate);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static <T> T as(TenantUser identity, Supplier<T> body) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return body.get();
		}
	}

}
