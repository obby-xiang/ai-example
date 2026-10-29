package com.example.configmgr.ai.run;

import java.util.List;
import java.util.Map;

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
 * <b>RT-21：HELD-but-fence-ahead 也是"没有续跑权"，帧/台账/取号一律拒写</b>。
 *
 * <h2>缺陷形态</h2>
 * {@code authority} 只回答"本实例的租约视图还在不在"。租约 TTL 到期、新实例接管并写过快照之后，
 * 旧实例的续租还没到点（续租间隔内）仍是 {@code HELD} —— 于是它继续落帧、写台账、取业务号，
 * 与新主人<b>同时推进同一轮</b>（前端会收到两份回答、台账里出现两条"已执行"）。
 * 判据必须补一条：{@code fence <= 本进程 epoch}（与 {@code tryClaimExecution} 的 I5 复核同源）。
 *
 * <h2>本批口径</h2>
 * {@code appendEvent}（兼容写口/直塞帧的第二道闸）、{@code appendLedger}、{@code recordIssuedSeq}
 * 三处改为统一的 {@code writableNow}：只读（LOST/UNAVAILABLE）<b>或</b> fence 已越过本进程 epoch
 * ⇒ 拒写；{@code epoch == 0}（无租约语义）保持 E3 的普通写；fence 读不出来按"无法确认"拒写。
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

	/** run 级键由身份拼出：直调真实 {@link RunStore} 必须显式进入身份作用域（否则 create fail-closed）。 */
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
	void fenceAheadDriverCannotWriteLedgerFramesOrSeq() {
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

		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-fence-ahead");
		pending.setName("start_export");
		pending.setExecutedBy("inst-a");

		this.storeA.appendLedger(RUN_ID, pending, "stale-result");
		this.storeA.appendEvent(RUN_ID, frame("旧驱动的半截正文"));
		this.storeA.recordIssuedSeq(RUN_ID, 99L);

		assertThat(this.storeA.ledger(RUN_ID)).as("fence 已过本实例 epoch ⇒ 不得写台账").isEmpty();
		assertThat(this.storeA.events(RUN_ID)).as("fence 已过本实例 epoch ⇒ 不得落帧").isEmpty();
		assertThat(this.storeA.lastIssuedSeq(RUN_ID)).as("fence 已过本实例 epoch ⇒ 不得登记业务号").isZero();
	}

	@Test
	void heldAndFenceNotAheadStillWritesEverything() {
		// 回归护栏：正常持租（fence == epoch）时三处都必须照常写 —— 修复不得把普通路径一起关掉。
		seed();
		this.storeA.tryAcquireRunLease(RUN_ID).orElseThrow();
		RunSnapshot mine = this.storeA.get(RUN_ID);
		mine.setStatus(RunSnapshot.RUNNING);
		this.storeA.save(mine);
		assertThat(this.storeA.runLeaseAuthority(RUN_ID)).isEqualTo(LeaseService.LeaseAuthority.HELD);

		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-ok");
		pending.setName("start_export");
		pending.setExecutedBy("inst-a");
		this.storeA.appendLedger(RUN_ID, pending, "ok");
		this.storeA.appendEvent(RUN_ID, frame("正常帧"));
		this.storeA.recordIssuedSeq(RUN_ID, 7L);

		assertThat(this.storeA.ledger(RUN_ID)).hasSize(1);
		assertThat(this.storeA.events(RUN_ID)).hasSize(1);
		assertThat(this.storeA.lastIssuedSeq(RUN_ID)).isEqualTo(7L);
	}

	@Test
	void storeWithoutLeaseFacilityKeepsThePlainWritePath() {
		// E3 红线：无租约设施（单测/降级装配）不读 fence、不拒写。
		RunStore noFacility = new RunStore(this.fake.redis, this.mapper, this.properties);
		noFacility.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("hi")), this.codec, null,
				null);
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-nofacility");
		pending.setName("start_export");
		pending.setExecutedBy("inst-x");
		noFacility.appendLedger(RUN_ID, pending, "ok");
		noFacility.appendEvent(RUN_ID, frame("无租约帧"));
		noFacility.recordIssuedSeq(RUN_ID, 3L);

		assertThat(noFacility.ledger(RUN_ID)).hasSize(1);
		assertThat(noFacility.events(RUN_ID)).hasSize(1);
		assertThat(noFacility.lastIssuedSeq(RUN_ID)).isEqualTo(3L);
	}

	/** 帧体必须可变：{@code appendEvent} 会 {@code putIfAbsent("atMs")}（{@code Map.of} 不可变）。 */
	private static Map<String, Object> frame(String delta) {
		Map<String, Object> frame = new java.util.LinkedHashMap<>();
		frame.put("type", "text_delta");
		frame.put("delta", delta);
		return frame;
	}

	private void seed() {
		this.storeA.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("hi")), this.codec, null,
				null);
	}

}
