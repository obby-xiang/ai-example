package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 挂起态 TTL 的<b>读侧续期</b>唯一入口 {@link RunStore#touchActiveRun}（B23L1 批②a · 方案第 10 条）。
 *
 * <h2>为什么要有这个入口</h2>
 * 批②b 起，挂起线程不再被假设一定活着（多实例下挂起在 A、读取/决策落在 B）。"谁把卡的后台数据
 * 养着"因此从"挂起线程的心跳"改为"任何实例的读/决策都 touch 一次"。入口必须<b>自带终态判据</b>：
 * 已终态（或快照已消失）的轮绝不续期 —— 否则删掉的轮会被读侧重新养出一堆孤儿键。
 *
 * <p>红线：心跳本体 {@link RunStore#renewSuspensionTtl} 不动（{@code RunStoreSlidingTtlTest}
 * 锁着它 exactly 5 次 EXPIRE），本方法在它之上再叠会话索引与新键族。
 */
class RunStoreTouchTest {

	private static final String RUN_ID = "run-touch";

	private static final String SESSION_ID = "s-touch";

	private static final String RUN_KEY = RunStore.RUN_PREFIX + RUN_ID;

	private static final String SESSION_KEY = RunStore.SESSION_CURRENT_PREFIX + SESSION_ID;

	private static final Duration SESSION_TTL = Duration.ofHours(24);

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final LeaseService lease = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-a");

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties, this.lease);

	@BeforeEach
	void seed() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId(SESSION_ID);
		snapshot.setStatus(RunSnapshot.RUNNING);
		this.store.save(snapshot);
	}

	/** T1'：挂起态 touch ⇒ 快照键族 + 会话索引 + 契约位键族全部续满一个 session-ttl。 */
	@Test
	void touchingASuspendedRunRenewsBothKeyFamilies() {
		RunSnapshot snapshot = this.store.get(RUN_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		this.store.save(snapshot);
		// 待决条目、req 位、decided guard 都已在（挂起期的真实形态）
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-1");
		pending.setName("start_import");
		this.store.putPending(RUN_ID, pending);
		this.store.markRequestEmitted(RUN_ID, "call-1", 1_000L);
		this.store.tryRecordDecision(RUN_ID, "call-2", PendingToolCall.APPROVED);
		// fence 键（M1）：它若先于快照过期，下一次 fence 写会把号"推回去"，fencing 的单调前提就破了
		this.fake.strings.put(this.store.fenceKey(RUN_ID), "7");
		// 把所有 TTL 打回 1s：之后只要被续期就会回到 24h
		this.fake.expiries.replaceAll((key, value) -> Duration.ofSeconds(1));

		assertThat(this.store.touchActiveRun(RUN_ID)).as("挂起态 ⇒ 续期").isTrue();

		assertThat(this.fake.expiry(RUN_KEY)).as("快照").isEqualTo(SESSION_TTL);
		assertThat(this.fake.expiry(this.store.pendingKey(RUN_ID))).as("待决条目").isEqualTo(SESSION_TTL);
		assertThat(this.fake.expiry(this.store.eventsKey(RUN_ID))).as("帧归档（兼容期）").isEqualTo(SESSION_TTL);
		assertThat(this.fake.expiry(this.store.seqKey(RUN_ID))).as("已发放序号").isEqualTo(SESSION_TTL);
		assertThat(this.fake.expiry(this.store.xeventsKey(RUN_ID))).as("事件日志 Stream").isEqualTo(SESSION_TTL);
		assertThat(this.fake.expiry(SESSION_KEY)).as("session → current 索引").isEqualTo(SESSION_TTL);
		assertThat(this.fake.expiry(this.store.reqKey(RUN_ID))).as("req 位（L0 契约位）").isEqualTo(SESSION_TTL);
		assertThat(this.fake.expiry(this.store.decidedKey(RUN_ID))).as("decided guard").isEqualTo(SESSION_TTL);
		assertThat(this.fake.expiry(this.store.fenceKey(RUN_ID))).as("fence（M1：与快照同寿）")
			.isEqualTo(SESSION_TTL);
	}

	/** T4：终态或快照缺失 ⇒ 不续期（读侧不得复活已收尾的轮）。 */
	@Test
	void terminalOrMissingRunIsNotRenewed() {
		RunSnapshot snapshot = this.store.get(RUN_ID);
		snapshot.setStatus(RunSnapshot.DONE);
		this.store.save(snapshot);
		this.fake.expiries.replaceAll((key, value) -> Duration.ofSeconds(5));

		assertThat(this.store.touchActiveRun(RUN_ID)).as("终态 ⇒ 不续").isFalse();
		assertThat(this.fake.expiry(RUN_KEY)).as("终态的快照 TTL 不被读侧续期").isEqualTo(Duration.ofSeconds(5));
		assertThat(this.fake.expiry(this.store.pendingKey(RUN_ID))).isNull();

		assertThat(this.store.touchActiveRun("run-touch-missing")).as("快照缺失 ⇒ 不续").isFalse();
	}

	/** T4 重载：传快照的入口省掉一次快照读（调用方刚从 Redis 读到它）。 */
	@Test
	void snapshotOverloadSkipsTheSnapshotRead() {
		RunSnapshot snapshot = this.store.get(RUN_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		this.store.save(snapshot);
		this.fake.resetCounters();

		assertThat(this.store.touchActiveRun(snapshot)).isTrue();

		assertThat(this.fake.getCount(RUN_KEY)).as("重载入口不得再读一次快照").isZero();
		assertThat(this.fake.expiry(RUN_KEY)).isEqualTo(SESSION_TTL);
	}

}
