package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认领值升级为 {@code <epoch>|<instanceId>}（B23L1 批②a / 方案第 3 条）。
 *
 * <p>动机：认领（{@code ai:claim:}，HSETNX）只回答"谁抢到"，不回答"以哪一任租约抢到的"。
 * 续跑权带 epoch 之后，执行认领必须能与之对账（换主后的旧认领不得被当成有效执行权），
 * 因此值里前置 epoch；<b>HSETNX 的原子性一点不改</b>，只是值多一段前缀。
 *
 * <p>兼容红线：{@code claimedBy()} 必须剥前缀 —— 既有断言
 * {@code ToolFrameSequenceConformanceTest:180} 拿它跟 {@code instanceId()} 比，
 * 零改动是硬要求；同时"旧裸值"（本批之前写入的 Redis 数据）也要原样返回。
 */
class RunStoreClaimEpochTest {

	private static final String RUN_ID = "run-claim-epoch";

	private static final String CALL_ID = "call-1";

	private static final String CLAIM_KEY = RunStore.runScopedKey(RunStore.CLAIM_PREFIX, RUN_ID);

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final LeaseService lease = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-a");

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties, this.lease);

	/** E5a：无租约时认领值为 {@code 0|<instanceId>}，{@code claimedBy()} 剥前缀后仍是实例标识。 */
	@Test
	void claimValueCarriesTheEpochPrefix() {
		assertThat(this.store.claimExecution(RUN_ID, CALL_ID, this.store.instanceId())).isTrue();

		String raw = this.fake.table(CLAIM_KEY).get(CALL_ID);
		assertThat(raw).as("认领值 = <epoch>|<instanceId>（无租约 ⇒ epoch=0）")
				.matches("0\\|" + Pattern.quote(this.store.instanceId()));
		assertThat(this.store.claimedBy(RUN_ID, CALL_ID)).as("claimedBy 必须剥前缀（既有断言零改动）")
				.isEqualTo(this.store.instanceId());
	}

	/** E5b：持租时认领值带上该租约 epoch，并回写到条目的 {@code claimedEpoch}。 */
	@Test
	void claimValueCarriesTheHeldLeaseEpoch() {
		this.fake.seedEpoch(this.store.runLeaseEpochKey(RUN_ID), 4L);
		LeaseService.Lease runLease = this.store.tryAcquireRunLease(RUN_ID).orElseThrow();
		assertThat(runLease.epoch()).as("per-run 计数器从铺好的 4 起 +1").isEqualTo(5L);
		seedPending();

		assertThat(this.store.claimExecution(RUN_ID, CALL_ID, this.store.instanceId())).isTrue();

		assertThat(this.fake.table(CLAIM_KEY).get(CALL_ID))
				.isEqualTo("5|" + this.store.instanceId());
		assertThat(this.store.claimedBy(RUN_ID, CALL_ID)).isEqualTo(this.store.instanceId());
		PendingToolCall claimed = this.store.pending(RUN_ID, CALL_ID);
		assertThat(claimed.getClaimedEpoch()).as("条目记下认领所用 epoch（additive）").isEqualTo(5L);
		assertThat(claimed.isExecuted()).isTrue();
	}

	/** E5 兼容：旧数据里是裸 instanceId（没有 {@code |}），{@code claimedBy()} 原样返回。 */
	@Test
	void legacyBareClaimValueIsStillReadable() {
		this.fake.table(CLAIM_KEY).put(CALL_ID, "instance-legacy-host-pid1-abc123");

		assertThat(this.store.claimedBy(RUN_ID, CALL_ID)).isEqualTo("instance-legacy-host-pid1-abc123");
	}

	/** claim 键仍按 session-ttl 滑动续期（既有口径不变）。 */
	@Test
	void claimKeyKeepsTheSessionTtl() {
		this.store.claimExecution(RUN_ID, CALL_ID, this.store.instanceId());

		assertThat(this.fake.expiry(CLAIM_KEY)).as("claim 键 TTL 随 session-ttl")
				.isEqualTo(Duration.ofHours(24));
	}

	private void seedPending() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_BACKEND);
		pending.setStatus(PendingToolCall.APPROVED);
		this.store.putPending(RUN_ID, pending);
	}

}
