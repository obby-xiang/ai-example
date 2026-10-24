package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 的 {@code req} 置位必须与"决策落库"<b>互不覆写</b>（B23L1 批②a / M-2）。
 *
 * <h2>为什么原实现会丢决策</h2>
 * {@link RunStore#markRequestEmitted} 是"读整条 JSON → 改 req 位 → 把整条写回"：
 * 读时还是 {@code PENDING}、写前他实例（或本进程另一线程）落了决策 ——
 * 回写的那份<b>旧副本</b>把 {@code APPROVED/REJECTED/CANCELLED} 覆盖回 {@code PENDING}
 * （决策丢失，且条目重新变成"待人工输入"）。
 *
 * <p>本批把 req 位拆成独立 HASH {@code ai:req:<runId>}（{@code HSETNX} 首写者胜），
 * 置位路径<b>不再触碰条目本身</b> ⇒ 决策永不丢、{@code req} 仍然置位。
 *
 * <p>四条用例分别锁：R1 决策不被覆盖（M-2 复现）；R2 首写者胜（重复置位不改时刻）；
 * R3 已决条目不再置位；R6 取消优先（迟到的置位不得把取消复活成 PENDING）。
 */
class RunStoreRequestEmittedAtomicTest {

	private static final String RUN_ID = "run-req-atomic";

	private static final String CALL_ID = "call-1";

	private static final String REQ_KEY = RunStore.runScopedKey(RunStore.REQ_PREFIX, RUN_ID);

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, new AiProperties());

	@BeforeEach
	void seedWaitingEntry() {
		seedPending(PendingToolCall.PENDING);
	}

	/** R1（M-2 复现）：置位的读与写之间他方落决策 ⇒ 终态仍是 APPROVED，且 req 仍置位。 */
	@Test
	void decisionLandedBetweenReadAndWriteSurvivesAndRequestBitIsStillSet() {
		AtomicBoolean fired = new AtomicBoolean();
		this.fake.afterPendingGet = (key, field) -> {
			if (key.equals(this.store.pendingKey(RUN_ID)) && CALL_ID.equals(field) && fired.compareAndSet(false, true)) {
				// 并发窗口：他实例的 submitDecision 落库（整条换成 APPROVED）
				seedPending(PendingToolCall.APPROVED);
			}
		};

		this.store.markRequestEmitted(RUN_ID, CALL_ID, 1_000L);

		PendingToolCall stored = this.store.pending(RUN_ID, CALL_ID);
		assertThat(stored.getStatus()).as("置位不得把并发落库的决策覆盖回 PENDING（M-2）")
				.isEqualTo(PendingToolCall.APPROVED);
		assertThat(stored.getReq()).as("请求帧确实发过 ⇒ req 仍置位").isTrue();
		assertThat(stored.getReqAtMs()).isEqualTo(1_000L);
	}

	/** R2：req 幂等、首写者胜（重复置位不改首次时刻）。 */
	@Test
	void repeatedMarkKeepsTheFirstEmissionTime() {
		this.store.markRequestEmitted(RUN_ID, CALL_ID, 1_000L);
		this.store.markRequestEmitted(RUN_ID, CALL_ID, 2_000L);

		PendingToolCall stored = this.store.pending(RUN_ID, CALL_ID);
		assertThat(stored.getReqAtMs()).as("首写者胜：后到的置位不得改写已有时刻").isEqualTo(1_000L);
		assertThat(this.fake.table(REQ_KEY)).as("一个条目一个 req 字段").containsOnlyKeys(CALL_ID);
	}

	/** R3：已决条目不再获得 req 位（前端不为已决条目建卡，位没有消费者）。 */
	@Test
	void decidedEntryIsNotMarked() {
		seedPending(PendingToolCall.APPROVED);

		PendingToolCall returned = this.store.markRequestEmitted(RUN_ID, CALL_ID, 1_000L);

		assertThat(returned.getStatus()).isEqualTo(PendingToolCall.APPROVED);
		assertThat(returned.getReq()).as("已决 ⇒ 不置位").isNull();
		assertThat(this.fake.hashTables.get(REQ_KEY)).as("已决 ⇒ 没有 req 字段落库").satisfiesAnyOf(
				table -> assertThat(table).isNull(),
				table -> assertThat(table).isEmpty());
	}

	/** R6：取消优先 —— 迟到的置位不得把 CANCELLED 复活成 PENDING。 */
	@Test
	void cancelWinsOverLateRequestMark() {
		AtomicBoolean fired = new AtomicBoolean();
		this.fake.afterPendingGet = (key, field) -> {
			if (key.equals(this.store.pendingKey(RUN_ID)) && CALL_ID.equals(field) && fired.compareAndSet(false, true)) {
				// 并发窗口：cancelPendings 先落地
				seedPending(PendingToolCall.CANCELLED);
			}
		};

		this.store.markRequestEmitted(RUN_ID, CALL_ID, 1_000L);

		PendingToolCall stored = this.store.pending(RUN_ID, CALL_ID);
		assertThat(stored.getStatus()).as("取消优先：迟到的置位不得复活条目").isEqualTo(PendingToolCall.CANCELLED);
		assertThat(stored.isExecuted()).isFalse();
	}

	// ── 语料 ────────────────────────────────────────────────────────────────

	private void seedPending(String status) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{\"taskId\":7}");
		pending.setStatus(status);
		if (PendingToolCall.CANCELLED.equals(status)) {
			pending.setReason("RUN_CANCELLED");
			pending.setResolvedAtMs(900L);
			pending.setResultText("本轮对话已被用户取消，该工具未执行。");
		}
		try {
			this.fake.table(this.store.pendingKey(RUN_ID)).put(CALL_ID, this.mapper.writeValueAsString(pending));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
