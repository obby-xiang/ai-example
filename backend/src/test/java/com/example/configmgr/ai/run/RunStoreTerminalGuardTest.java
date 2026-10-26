package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 4 Step 1 / AR-05：<b>取消优先终态守卫</b>的 FAILED 一侧（批次2）。
 *
 * <h2>为什么 DONE 的守卫不够</h2>
 * {@code RunStore#save} 原先只对 {@code status=DONE} 走"当前快照是取消态 ⇒ 拒写"的原子 CAS
 * （W-30）。可失败出口（{@code fail()} / {@code emptyReply()} / {@code AiController} 的环外异常）
 * 写的是 <b>FAILED</b>，走的还是普通 {@code SET} —— 于是一条并发取消先落库后，失败出口照样能把
 * {@code CANCELLED} 盖成 {@code FAILED}，用户看到的"已取消"被改写成"失败"（与 DONE 那一侧的
 * 症状同族，只是方向相反）。
 *
 * <p>本类锁三件事：
 * <ol>
 * <li><b>无租约（plain）分支</b>：CANCELLED 之后的 FAILED 写必须被拒，快照仍是取消态；</li>
 * <li><b>持租（fenced）分支</b>：同上，且 fence/epoch 语义不变；</li>
 * <li><b>普通 FAILED 仍照写</b>（守卫只拦"取消态"，不拦"失败覆盖失败/运行中"）——
 *     否则守卫会变成"失败再也写不进去"的另一个坑。</li>
 * </ol>
 *
 * <p>底座 = {@link FakeLeaseRedis}（按脚本正文标记复刻 Lua 语义），不起真实 Redis：
 * 断言的是"脚本语义"，不是"脚本原文"。
 */
class RunStoreTerminalGuardTest {

	private static final String PLAIN = "guard-failed-plain";

	private static final String FENCED = "guard-failed-fenced";

	private static final String ORDINARY = "guard-failed-ordinary";

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final LeaseService leases = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"guard-inst");

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties, this.leases);

	/**
	 * <b>本批唯一实跑的聚焦用例</b>（RED → GREEN）：重跑模型步的状态转换（FAILED → RUNNING）
	 * 不得在并发取消之后把 CANCELLED 改回 RUNNING。
	 *
	 * <p>为什么它比"FAILED 不得覆盖 CANCELLED"更关键：{@code ResumeService.retryModelStepInternal}
	 * 在入口检查（{@code cancelStateOutcome}）通过之后才写 RUNNING，检查与写之间没有原子性 ——
	 * 取消恰在这段窗口落库时，旧实现的普通 {@code save} 会把取消终态直接翻案，随后前端重挂到
	 * 一条"其实已被用户停止"的轮上。守卫必须覆盖<b>所有非取消状态</b>，不只是终态。
	 */
	@Test
	void runningRetryWriteCannotOverwriteCancelledWithoutLease() {
		this.store.save(snapshot(PLAIN, RunSnapshot.CANCELLED, true, "AI_RUN_CANCELLED：本轮由用户取消"));

		this.store.save(snapshot(PLAIN, RunSnapshot.RUNNING, false, null));

		RunSnapshot after = this.store.get(PLAIN);
		assertThat(after.getStatus()).as("重跑状态转换不得把 CANCELLED 改成 RUNNING").isEqualTo(RunSnapshot.CANCELLED);
		assertThat(after.isCancelled()).as("取消位不得被重跑写清掉").isTrue();
	}

	/**
	 * 可判定变体（只写不跑）：重跑/续跑的状态转换必须能读到"被取消守卫拒写"这一事实，
	 * 否则调用方无从分支，仍会交棒驱动。
	 */
	@Test
	void statusTransitionReportsRefusalWhenCancelled() {
		this.store.save(snapshot(PLAIN, RunSnapshot.CANCELLED, true, "AI_RUN_CANCELLED：本轮由用户取消"));

		boolean written = this.store.saveStatusUnlessCancelled(snapshot(PLAIN, RunSnapshot.RUNNING, false, null));

		assertThat(written).as("取消态拒写 ⇒ false（调用方必须返回取消语义、不得驱动）").isFalse();
		assertThat(this.store.get(PLAIN).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
	}

	/** 失败终态不得覆盖取消终态（无租约分支；与聚焦用例同一条守卫，只写不跑）。 */
	@Test
	void failedSaveCannotOverwriteCancelledWithoutLease() {
		this.store.save(snapshot(PLAIN, RunSnapshot.CANCELLED, true, "AI_RUN_CANCELLED：本轮由用户取消"));

		this.store.save(snapshot(PLAIN, RunSnapshot.FAILED, false, "UPSTREAM_CONNECTION_BROKEN：连接中断"));

		RunSnapshot after = this.store.get(PLAIN);
		assertThat(after.getStatus()).as("取消优先：FAILED 不得覆盖 CANCELLED").isEqualTo(RunSnapshot.CANCELLED);
		assertThat(after.isCancelled()).as("取消位不得被失败写清掉").isTrue();
		assertThat(after.getError()).as("取消的 error 不得被失败码覆盖").contains("AI_RUN_CANCELLED");
	}

	/** 持租分支同理：fence 校验照旧先跑，取消态判定在同一个原子步内。 */
	@Test
	void failedSaveCannotOverwriteCancelledUnderALease() {
		LeaseService.Lease lease = this.store.tryAcquireRunLease(FENCED).orElseThrow();
		try {
			this.store.save(snapshot(FENCED, RunSnapshot.RUNNING, false, null));
			assertThat(this.store.runLeaseAuthority(FENCED)).isEqualTo(LeaseService.LeaseAuthority.HELD);
			this.store.save(snapshot(FENCED, RunSnapshot.CANCELLED, true, "AI_RUN_CANCELLED：本轮由用户取消"));

			this.store.save(snapshot(FENCED, RunSnapshot.FAILED, false, "EMPTY_REPLY：本轮未返回内容"));

			RunSnapshot after = this.store.get(FENCED);
			assertThat(after.getStatus()).isEqualTo(RunSnapshot.CANCELLED);
			assertThat(after.isCancelled()).isTrue();
		}
		finally {
			this.leases.release(lease);
		}
	}

	/** 守卫不是"失败一律写不进去"：没有取消态时 FAILED 必须真的落库（守住可用性一侧）。 */
	@Test
	void ordinaryFailureStillLands() {
		this.store.save(snapshot(ORDINARY, RunSnapshot.RUNNING, false, null));

		this.store.save(snapshot(ORDINARY, RunSnapshot.FAILED, false, "EMPTY_REPLY：本轮未返回内容"));

		RunSnapshot after = this.store.get(ORDINARY);
		assertThat(after.getStatus()).isEqualTo(RunSnapshot.FAILED);
		assertThat(after.getError()).contains("EMPTY_REPLY");
	}

	/**
	 * 可判定变体：三个失败出口（{@code fail()} / {@code emptyReply()} / {@code AiController} 环外异常）
	 * 共用的入口要把"这一笔到底写没写"如实返回 —— 被拒时调用方不得再发失败终帧。
	 */
	@Test
	void guardedTerminalWriteReportsRefusal() {
		this.store.save(snapshot(PLAIN, RunSnapshot.CANCELLED, true, "AI_RUN_CANCELLED：本轮由用户取消"));

		boolean written = this.store.saveTerminalUnlessCancelled(
				snapshot(PLAIN, RunSnapshot.FAILED, false, "EMPTY_REPLY：本轮未返回内容"));

		assertThat(written).as("被取消态拒写 ⇒ false（调用方据此不发失败终帧）").isFalse();
		assertThat(this.store.get(PLAIN).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
	}

	/**
	 * AR-10 第 3 条（批次2）：<b>armed 跨 park 持久化</b> —— 武装事实与"武装后被拦下的批次数"
	 * 写进已有的轮次快照（同一键族 / 同一 TTL，不新增键、不碰 DB），且<b>只升不降</b>。
	 */
	@Test
	void turnBudgetArmedStateIsPersistedAndMonotonic() {
		this.store.save(snapshot(ORDINARY, RunSnapshot.RUNNING, false, null));

		this.store.markTurnBudget(ORDINARY, 1_700_000_000_000L, 1);

		RunSnapshot armed = this.store.get(ORDINARY);
		assertThat(armed.getBudgetArmedAtMs()).as("武装时刻落快照").isEqualTo(1_700_000_000_000L);
		assertThat(armed.getBudgetSkippedBatches()).isEqualTo(1);
		assertThat(armed.asMap()).containsEntry("budgetArmedAtMs", 1_700_000_000_000L);

		// 迟到/并发的较小值不得把已武装改回未武装、也不得把已拦下的批次改小
		this.store.markTurnBudget(ORDINARY, 0L, 0);
		RunSnapshot stillArmed = this.store.get(ORDINARY);
		assertThat(stillArmed.getBudgetArmedAtMs()).isEqualTo(1_700_000_000_000L);
		assertThat(stillArmed.getBudgetSkippedBatches()).isEqualTo(1);
	}

	private static RunSnapshot snapshot(String runId, String status, boolean cancelled, String error) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId("session-" + runId);
		snapshot.setStatus(status);
		snapshot.setCancelled(cancelled);
		snapshot.setError(error);
		snapshot.setMessageJson(List.of());
		return snapshot;
	}
}
