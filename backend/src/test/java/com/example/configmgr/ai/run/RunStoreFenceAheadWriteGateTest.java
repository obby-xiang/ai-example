package com.example.configmgr.ai.run;

import java.util.List;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>RT-21：HELD-but-fence-ahead 也是"没有续跑权"</b>。
 *
 * <h2>缺陷形态</h2>
 * {@code authority} 只回答"本实例的租约视图还在不在"。租约 TTL 到期、新实例接管并写过快照之后，
 * 旧实例的续租还没到点（续租间隔内）仍是 {@code HELD} —— 于是它继续写台账、抬业务号，
 * 与新主人<b>同时推进同一轮</b>（台账里出现两条"已执行"、业务号被旧主人推进）。
 *
 * <h2>当前口径</h2>
 * 统一写门 {@link RunStore#isWritePermitted(String, String)}：只读（LOST/UNAVAILABLE）<b>或</b>
 * fence 已越过本进程 epoch ⇒ 拒写；{@code epoch == 0}（无租约语义）保持普通写；fence 读不出来按
 * "无法确认"拒写。台账追加、业务号抬升（以及出帧归档前的写出器判据）都走这一条门。
 */
class RunStoreFenceAheadWriteGateTest {

	private static final String RUN_ID = "run-fence-gate";

	private static final String SESSION_ID = "s-fence-gate";

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final AiProperties properties = new AiProperties();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final LeaseService leaseA = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-a");

	private final LeaseService leaseB = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-b");

	private final RunStore storeA = new RunStore(this.fake.redis, this.mapper, this.properties, this.leaseA);

	private final RunStore storeB = new RunStore(this.fake.redis, this.mapper, this.properties, this.leaseB);

	private com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable tenantScope;

	@BeforeEach
	void enterIdentity() {
		this.tenantScope = com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L);
	}

	@AfterEach
	void leaveIdentity() {
		if (this.tenantScope != null) {
			this.tenantScope.close();
		}
	}

	@Test
	void fenceAheadDriverIsRefusedForLedgerAndSeqFloor() {
		// A 持租并落一次快照（fence=epochA）；随后 A 的租约过期，B 接管并写快照（fence=epochB>epochA）。
		// A 的续租还没到点 ⇒ A 仍是 HELD（这正是 RT-21 的窗口）。
		seed();
		LeaseService.Lease a = this.storeA.tryAcquireRunLease(RUN_ID).orElseThrow();
		RunSnapshot byA = this.storeA.get(RUN_ID);
		byA.setStatus(RunSnapshot.SUSPENDED);
		this.storeA.save(byA);
		this.fake.dropKey(this.storeA.runLeaseKey(RUN_ID));
		this.storeB.tryAcquireRunLease(RUN_ID).orElseThrow();
		RunSnapshot byB = this.storeB.get(RUN_ID);
		byB.setStatus(RunSnapshot.SUSPENDED);
		this.storeB.save(byB);
		assertThat(this.storeA.fenceEpoch(RUN_ID)).as("fence 已被新主人推进").isGreaterThan(a.epoch());
		assertThat(this.storeA.runLeaseAuthority(RUN_ID))
			.as("A 的租约视图还在（续租未到点）⇒ 仍是 HELD，正是本缺陷的窗口")
			.isEqualTo(LeaseService.LeaseAuthority.HELD);

		assertThat(this.storeA.isWritePermitted(RUN_ID, "fence gate"))
			.as("HELD + fence 越过本进程 epoch ⇒ 没有续跑权").isFalse();
		assertThat(this.storeB.isWritePermitted(RUN_ID, "fence gate")).as("新主人在 fence 之内").isTrue();

		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-fence-ahead");
		pending.setName("start_export");
		pending.setExecutedBy("inst-a");

		this.storeA.appendLedger(RUN_ID, pending, "stale-result");
		this.storeA.ensureIssuedSeqAtLeast(RUN_ID, 99L);

		assertThat(this.storeA.ledger(RUN_ID)).as("fence 已过本实例 epoch ⇒ 不得写台账").isEmpty();
		assertThat(this.storeA.lastIssuedSeq(RUN_ID)).as("fence 已过本实例 epoch ⇒ 不得抬业务号").isZero();

		// 新主人照常写：拒绝必须只针对旧 epoch，不能把整条路径关掉。
		this.storeB.appendLedger(RUN_ID, pending, "fresh-result");
		this.storeB.ensureIssuedSeqAtLeast(RUN_ID, 5L);
		assertThat(this.storeB.ledger(RUN_ID)).hasSize(1);
		assertThat(this.storeB.lastIssuedSeq(RUN_ID)).isEqualTo(5L);
	}

	@Test
	void heldAndFenceNotAheadStillWritesEverything() {
		// 回归护栏：正常持租（fence == epoch）时写门与写路径都必须照常放行。
		seed();
		this.storeA.tryAcquireRunLease(RUN_ID).orElseThrow();
		RunSnapshot mine = this.storeA.get(RUN_ID);
		mine.setStatus(RunSnapshot.RUNNING);
		this.storeA.save(mine);
		assertThat(this.storeA.runLeaseAuthority(RUN_ID)).isEqualTo(LeaseService.LeaseAuthority.HELD);
		assertThat(this.storeA.isWritePermitted(RUN_ID, "normal write")).isTrue();

		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-ok");
		pending.setName("start_export");
		pending.setExecutedBy("inst-a");
		this.storeA.appendLedger(RUN_ID, pending, "ok");
		this.storeA.ensureIssuedSeqAtLeast(RUN_ID, 7L);

		assertThat(this.storeA.ledger(RUN_ID)).hasSize(1);
		assertThat(this.storeA.lastIssuedSeq(RUN_ID)).isEqualTo(7L);
	}

	@Test
	void storeWithoutLeaseFacilityKeepsThePlainWritePath() {
		// E3 红线：无租约设施（单测/降级装配）不读 fence、不拒写。
		RunStore noFacility = new RunStore(this.fake.redis, this.mapper, this.properties);
		noFacility.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("hi")), this.codec,
				7L, "model-x", new RunSnapshot.FrozenParams(null, null, null, false), "client-test", "请求文本",
				1_000L);
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-nofacility");
		pending.setName("start_export");
		pending.setExecutedBy("inst-x");
		assertThat(noFacility.isWritePermitted(RUN_ID, "no facility")).isTrue();
		noFacility.appendLedger(RUN_ID, pending, "ok");
		noFacility.ensureIssuedSeqAtLeast(RUN_ID, 3L);

		assertThat(noFacility.ledger(RUN_ID)).hasSize(1);
		assertThat(noFacility.lastIssuedSeq(RUN_ID)).isEqualTo(3L);
	}

	private void seed() {
		this.storeA.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("hi")), this.codec,
				7L, "model-x", new RunSnapshot.FrozenParams(null, null, null, false), "client-test", "请求文本",
				1_000L);
	}

}
