package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话级键族登记表（超时面治理批① 第 11 项）。
 *
 * <p>锁三件事：<b>清点不缺项</b>（run 级 12 + session 级 5）、<b>续期面口径不变</b>
 * （心跳 exactly 5；读侧 = 心跳 5 + 契约位 3 + 取消 1 = 9 个<b>新增</b>面）、
 * <b>取消信号的 TTL 与会话同寿</b>（600s → 24h）。
 */
class SessionKeyRegistryTest {

	private final AiProperties properties = new AiProperties();

	@Test
	void registryCoversRunAndSessionScopesWithoutGaps() {
		assertThat(SessionKeyRegistry.entries()).hasSize(19);
		assertThat(SessionKeyRegistry.entries()).filteredOn(e -> e.scope() == SessionKeyRegistry.Scope.RUN)
			.as("run 级 14 条（含 per-run 取号计数器与业务活动锚点）")
			.hasSize(14);
		assertThat(SessionKeyRegistry.entries()).filteredOn(e -> e.scope() == SessionKeyRegistry.Scope.SESSION)
			.as("session 级 5 条（session:current + ctx 三件套 + safety）")
			.hasSize(5);
		assertThat(SessionKeyRegistry.entries()).extracting(SessionKeyRegistry.Entry::prefix)
			.contains("ai:run:", "ai:pending:", "ai:events:", "ai:seq:", "ai:xevents:", "ai:req:", "ai:decided:",
					"ai:fence:run:", "ai:lease:epoch:", "ai:cancel:", "ai:beat:", "ai:activity:", "ai:ledger:",
					"ai:idem:", "ai:session:current:", "ai:ctx:", "ai:ctx:ledger:", "ai:ctx:archive:", "ai:safety:");
	}

	@Test
	void heartbeatFaceStaysAtExactlyFiveKeys() {
		// 红线：RunStoreSlidingTtlTest 断言 renewSuspensionTtl 的 5 个键都续到
		// （L-139 后逐键 try，不再锁"恰好 5 次调用"的形态）
		assertThat(SessionKeyRegistry.renewalPrefixes(SessionKeyRegistry.Renewal.HEARTBEAT)).containsExactly(
				"ai:run:", "ai:pending:", "ai:events:", "ai:seq:", "ai:xevents:");
		assertThat(SessionKeyRegistry.renewalKeys(SessionKeyRegistry.Renewal.HEARTBEAT)).hasSize(5);
	}

	@Test
	void readFaceAddsTheContractSlotsTheCancelSignalAndTheEpochCounter() {
		assertThat(SessionKeyRegistry.renewalPrefixes(SessionKeyRegistry.Renewal.READ)).containsExactly(
				"ai:req:", "ai:decided:", "ai:fence:run:", "ai:lease:epoch:", "ai:cancel:");
	}

	@Test
	void heartbeatAndReadFacesTogetherCoverTheTenTouchKeys() {
		// touchActiveRun = 心跳 5 + 读侧 5（cancel 之前是 5+3；取号计数器与 fence 同面后是 5+5）
		assertThat(SessionKeyRegistry.renewalKeys(SessionKeyRegistry.Renewal.HEARTBEAT)).hasSize(5);
		assertThat(SessionKeyRegistry.renewalKeys(SessionKeyRegistry.Renewal.READ)).hasSize(5);
	}

	@Test
	void globalAndShortLivedKeysAreExplicitlyOutOfTheRegistry() {
		assertThat(SessionKeyRegistry.entries()).extracting(SessionKeyRegistry.Entry::prefix)
			.as("全局键/租约/锁不进会话级登记表")
			.doesNotContain("ai:cfg:ver", "ai:rate:session:", "ai:rate:ip:", "ai:lease:run:", "ai:lock:", "ai:claim:");
	}

	@Test
	void keyBuildersAgreeWithTheStorePublicKeyMethods() {
		RunStore store = new RunStore(new FakeLeaseRedis().redis, new com.fasterxml.jackson.databind.ObjectMapper(),
				this.properties);
		assertThat(SessionKeyRegistry.renewalKeys(SessionKeyRegistry.Renewal.READ))
			.extracting(builder -> builder.apply("r1"))
			.containsExactly(store.reqKey("r1"), store.decidedKey("r1"), store.fenceKey("r1"),
					store.runLeaseEpochKey("r1"), CancellationRegistry.cancelKey("r1"));
		assertThat(SessionKeyRegistry.renewalKeys(SessionKeyRegistry.Renewal.HEARTBEAT))
			.extracting(builder -> builder.apply("r1"))
			.containsExactly(store.runKey("r1"), store.pendingKey("r1"), store.eventsKey("r1"), store.seqKey("r1"),
					store.xeventsKey("r1"));
	}

	@Test
	void activityAnchorIsRegisteredOnTheWriteFaceOnly() {
		assertThat(SessionKeyRegistry.renewalPrefixes(SessionKeyRegistry.Renewal.WRITE))
			.as("ai:activity 与 ai:beat 同属随写续期，不挤占心跳/读侧续期面")
			.contains("ai:activity:");
		assertThat(SessionKeyRegistry.renewalPrefixes(SessionKeyRegistry.Renewal.HEARTBEAT))
			.doesNotContain("ai:activity:");
		assertThat(SessionKeyRegistry.renewalPrefixes(SessionKeyRegistry.Renewal.READ))
			.doesNotContain("ai:activity:");
	}

	// ── 取消信号：600s → session-ttl（24h） ────────────────────────────────

	@Test
	void cancelFlagTtlEqualsTheSessionTtl() {
		FakeLeaseRedis fake = new FakeLeaseRedis();
		CancellationRegistry registry = new CancellationRegistry(fake.redis, this.properties);

		registry.cancel("run-cancel", "inst-a");

		assertThat(fake.string(CancellationRegistry.CANCEL_PREFIX + "run-cancel")).as("权威标志已写")
			.isEqualTo("inst-a");
		assertThat(fake.expiry(CancellationRegistry.CANCEL_PREFIX + "run-cancel"))
			.as("取消信号随会话共亡（原 600s = total-budget×2）")
			.isEqualTo(Duration.ofHours(24));
	}

	@Test
	void cancelFlagTtlFollowsAConfiguredSessionTtl() {
		AiProperties custom = new AiProperties();
		custom.setSessionTtl(Duration.ofHours(6));
		FakeLeaseRedis fake = new FakeLeaseRedis();
		CancellationRegistry registry = new CancellationRegistry(fake.redis, custom);

		registry.cancel("run-cancel-2", "inst-a");

		assertThat(fake.expiry(CancellationRegistry.CANCEL_PREFIX + "run-cancel-2")).isEqualTo(Duration.ofHours(6));
	}

}
