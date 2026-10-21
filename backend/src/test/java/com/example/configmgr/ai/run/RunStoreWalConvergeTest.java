package com.example.configmgr.ai.run;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WAL 的<b>读侧</b>收敛与 E3 写回的旁路观测（B23L1 批②b 复审收口 I1 / I2① / M2）。
 *
 * <h2>读侧收敛（I2①）</h2>
 * 决策 guard（{@code ai:decided:}）先于待决条目落库；写者死在两步之间时，条目仍是 PENDING。
 * 读侧（{@link RunStore#pending} / {@link RunStore#pendings}）据 guard 把结论合进返回值 ——
 * <b>只读不写</b>（存储里仍是 PENDING，由写者/WAL 补齐路径持久化）。
 *
 * <h2>只合并"可重建"的结论（I1）</h2>
 * {@code REJECTED} / {@code FRONTEND_CANCELLED} / {@code CANCELLED} 是纯终态：结论即全部信息。
 * {@code FRONTEND_RESULT} 必须带用户填的 {@code resultText}、{@code APPROVED} 需要执行侧字段
 * （{@code executed=false} 等）—— <b>不合并</b>：宁可让卡继续显示为待处理，也不让用户看到
 * 一张"已决但没有内容"的卡。
 */
class RunStoreWalConvergeTest {

	private static final String RUN_ID = "run-wal";

	private static final String CALL_ID = "c-wal";

	private static final String FENCE_KEY = RunStore.FENCE_RUN_PREFIX + RUN_ID;

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final LeaseService lease = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-a");

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties, this.lease);

	/** 无租约设施的 store（E3 分支：plain SET）。 */
	private final RunStore plain = new RunStore(this.fake.redis, this.mapper, this.properties, null);

	private ListAppender<ILoggingEvent> appender;

	private Logger runStoreLogger;

	private Level previousLevel;

	@BeforeEach
	void setUp() {
		this.appender = new ListAppender<>();
		this.appender.start();
		this.runStoreLogger = (Logger) LoggerFactory.getLogger(RunStore.class);
		this.previousLevel = this.runStoreLogger.getLevel();
		// R6 的第二次写回故意降级为 DEBUG，必须让 appender 收得到
		this.runStoreLogger.setLevel(Level.DEBUG);
		this.runStoreLogger.addAppender(this.appender);
		this.appender.list.clear();
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		this.store.putPending(RUN_ID, pending);
	}

	@AfterEach
	void detach() {
		this.runStoreLogger.detachAppender(this.appender);
		this.runStoreLogger.setLevel(this.previousLevel);
	}

	@Test
	void readSideMergesRejectableConclusionsWithoutWritingThem() {
		this.store.tryRecordDecision(RUN_ID, CALL_ID, PendingToolCall.REJECTED);

		PendingToolCall merged = this.store.pending(RUN_ID, CALL_ID);

		assertThat(merged.getStatus()).as("guard 说已决 ⇒ 读侧收敛").isEqualTo(PendingToolCall.REJECTED);
		assertThat(merged.effectiveOutcome()).isEqualTo(com.example.configmgr.ai.tool.ToolOutcome.REJECTED);
		assertThat(merged.getResultText()).as("拒绝对应的文案可重建").contains("拒绝了该操作");
		// 只读不写：存储里仍是 PENDING（持久化由 WAL 补齐路径负责）
		assertThat(this.store.pendingRaw(RUN_ID, CALL_ID).getStatus())
			.as("读路径不得改存储（否则'读'就成了一次隐形写）").isEqualTo(PendingToolCall.PENDING);
	}

	@Test
	void readSideMergesForTheBatchReadToo() {
		this.store.tryRecordDecision(RUN_ID, CALL_ID, PendingToolCall.CANCELLED);

		List<PendingToolCall> all = this.store.pendings(RUN_ID);

		assertThat(all).hasSize(1);
		assertThat(all.get(0).getStatus()).isEqualTo(PendingToolCall.CANCELLED);
		assertThat(all.get(0).effectiveCode()).isEqualTo(com.example.configmgr.ai.tool.ToolOutcome.CODE_RUN_CANCELLED);
	}

	@Test
	void readSideNeverMergesResultsOrApprovals() {
		this.store.tryRecordDecision(RUN_ID, CALL_ID, PendingToolCall.FRONTEND_RESULT);
		assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus())
			.as("FRONTEND_RESULT 需要 resultText，裸状态会让卡'已决但没内容'")
			.isEqualTo(PendingToolCall.PENDING);

		this.fake.table(RunStore.DECIDED_PREFIX + RUN_ID).put(CALL_ID, "APPROVED|1|inst-x");
		assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus())
			.as("APPROVED 需要执行侧字段（executed=false 等），同样不合并")
			.isEqualTo(PendingToolCall.PENDING);
	}

	@Test
	void plainWriteWithAFenceAheadLogsButStillWrites() {
		// fence 已被别的实例推进（本实例没有租约设施 ⇒ 无持租写回）
		this.fake.strings.put(FENCE_KEY, "9");

		this.plain.save(snapshot(RunSnapshot.SUSPENDED));

		assertThat(this.plain.get(RUN_ID)).as("E3 红线：无租约不拒写").isNotNull();
		assertThat(this.appender.list.stream().map(ILoggingEvent::getFormattedMessage)
			.anyMatch(message -> message.contains("无租约写回") && message.contains("fence 已推进"))).isTrue();
		assertThat(this.appender.list.stream()
			.anyMatch(event -> event.getLevel() == Level.WARN && event.getFormattedMessage().contains("无租约写回")))
			.as("至少 WARN 级（否则现场看不出这笔写没有续跑权语义）").isTrue();

		// R6 节流：同一个 runId 的后续写回不再 WARN（降为 DEBUG），日志不被"被推进过 fence 的轮"淹掉
		this.appender.list.clear();
		this.plain.save(snapshot(RunSnapshot.SUSPENDED));
		assertThat(this.appender.list.stream()
			.anyMatch(event -> event.getLevel() == Level.WARN && event.getFormattedMessage().contains("无租约写回")))
			.as("第二次写回不得再 WARN").isFalse();
		assertThat(this.appender.list.stream()
			.anyMatch(event -> event.getLevel() == Level.DEBUG && event.getFormattedMessage().contains("无租约写回")))
			.as("降为 DEBUG（保留可追溯性）").isTrue();
	}

	private RunSnapshot snapshot(String status) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId("s-wal");
		snapshot.setStatus(status);
		return snapshot;
	}

}
