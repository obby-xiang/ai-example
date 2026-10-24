package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双决策原子化设施（B23L1 批②a / 方案第 6 条）：{@code ai:decided:<runId>}
 * （field=toolCallId，value={@code <status>|<atMs>|<instanceId>}，{@code HSETNX} 首写者胜）。
 *
 * <p>本批<b>只建设施</b>：{@code RunStore.tryRecordDecision} 是"抢决策"的原子口，
 * ConfirmGate 侧的接入归批②b。语义分两层：
 * <ul>
 * <li><b>guard</b>：同一条目全集群只有一次决策能抢到（D1/D2）；</li>
 * <li><b>WAL</b>：决策结论先落 decided 再改条目 —— 若进程死在两步之间，读侧可据 decided
 *     把"PENDING 条目 + 已落定的结论"收敛成已决（D5）。</li>
 * </ul>
 */
class RunStoreDecisionGuardTest {

	private static final String RUN_ID = "run-decided";

	private static final String CALL_ID = "call-1";

	private static final String DECIDED_KEY = RunStore.runScopedKey(RunStore.DECIDED_PREFIX, RUN_ID);

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final LeaseService lease = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-a");

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties, this.lease);

	@BeforeEach
	void seedWaitingEntry() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		this.store.putPending(RUN_ID, pending);
	}

	/** D1：首写抢到，值 = {@code <status>|<atMs>|<instanceId>}。 */
	@Test
	void firstDecisionWinsTheGuard() {
		RunStore.DecisionClaim claim = this.store.tryRecordDecision(RUN_ID, CALL_ID, PendingToolCall.APPROVED);

		assertThat(claim.won()).as("首写抢到").isTrue();
		assertThat(claim.recorded()).matches("APPROVED\\|\\d+\\|inst-a");
		assertThat(this.fake.table(DECIDED_KEY).get(CALL_ID)).as("guard 落库（WAL）").startsWith("APPROVED|")
				.endsWith("|inst-a");
	}

	/** D2：二写拿不到，返回现值（且不覆盖首写者）。 */
	@Test
	void secondDecisionSeesTheWinner() {
		RunStore.DecisionClaim first = this.store.tryRecordDecision(RUN_ID, CALL_ID, PendingToolCall.APPROVED);

		RunStore.DecisionClaim second = this.store.tryRecordDecision(RUN_ID, CALL_ID, PendingToolCall.REJECTED);

		assertThat(second.won()).as("二写抢不到").isFalse();
		assertThat(second.recorded()).as("返回的是首写者的现值").isEqualTo(first.recorded());
		assertThat(this.fake.table(DECIDED_KEY).get(CALL_ID)).as("首写者胜：条目不得被覆盖")
				.isEqualTo(first.recorded());
	}

	/** D5：WAL —— guard 已写、条目仍 PENDING 时，读侧可据 guard 收敛出结论。 */
	@Test
	void guardCarriesTheConclusionEvenWhenTheEntryIsStillPending() {
		this.store.tryRecordDecision(RUN_ID, CALL_ID, PendingToolCall.APPROVED);

		assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus()).as("条目还没改（模拟进程死在两步之间）")
				.isEqualTo(PendingToolCall.PENDING);
		assertThat(this.store.decisionOf(RUN_ID, CALL_ID)).as("guard 原文可读").startsWith("APPROVED|");
		assertThat(this.store.decidedStatus(RUN_ID, CALL_ID)).as("读侧据 guard 收敛出结论")
				.isEqualTo(PendingToolCall.APPROVED);
		assertThat(this.store.decidedStatus(RUN_ID, "call-unknown")).as("没有 guard ⇒ null").isNull();
	}

}
