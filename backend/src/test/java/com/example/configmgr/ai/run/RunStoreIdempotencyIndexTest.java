package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行台账的<b>幂等索引</b>（B23L1 批③a / 裁决 6；I2 / I3 / I6）。
 *
 * <h2>要解决的问题</h2>
 * "这个 toolCallId 执行过没有"原先靠 {@code LRANGE 0 -1} 全表扫描（{@link RunStore#ledgerFor}）：
 * 一轮几十次工具调用时台账不长，但续跑/重试路径每次都要扫全部记录，而且<b>扫描不是判据的替代品</b> ——
 * 真正的判据是"有没有这条记录"，索引只是把它变成 O(1)。
 *
 * <h2>三条不变量（本类逐条钉住）</h2>
 * <ol>
 * <li><b>I2 索引命中不走扫描</b>：命中 ⇒ 一次 {@code HGET} + 一次 {@code LINDEX}，
 * {@code LRANGE} 次数为 0（用 {@link FakeLeaseRedis#listRangeCalls} / {@code listIndexCalls} 计数）；</li>
 * <li><b>I3 台账与索引同一个原子步</b>：{@code appendLedger} 只走<b>一条</b> Lua
 * （{@code RPUSH} + {@code HSETNX}），脚本外没有第二条推入/写入 —— 两步之间不存在可被中断的
 * 命令边界（{@code templatePushes == 0 && templateHashPuts == 0}），
 * 因此不会留下"台账有、索引无 ⇒ 续跑再执行一遍"的洞；</li>
 * <li><b>I6 旧数据兼容</b>：没有索引的旧记录回落扫描（结果同形）；索引指向坏下标时同样回落 ——
 * 索引坏掉不能把"已执行"报成"没执行"。</li>
 * </ol>
 *
 * <p><b>实例面维度（M8 口径）</b>：本类只跑一个 {@link RunStore}（无租约设施）。索引的跨实例问题
 * 不是"谁写"而是"写没写进去"——同一条 Redis、同一份台账，任何实例读到的索引与记录必须一致；
 * 多实例认领那一面由 {@code RunStoreClaimFenceTest} 覆盖。
 */
class RunStoreIdempotencyIndexTest {

	private static final String RUN_ID = "run-idem";

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	@BeforeEach
	void resetCounters() {
		this.fake.resetCounters();
	}

	// ── I2：O(1) 命中 ───────────────────────────────────────────────────────

	@Test
	void indexHitsWithoutScanningTheLedger() {
		this.store.appendLedger(RUN_ID, entry("c-1"), "结果 1");
		this.store.appendLedger(RUN_ID, entry("c-2"), "结果 2");
		this.store.appendLedger(RUN_ID, entry("c-3"), "结果 3");
		this.fake.resetCounters();

		Map<String, Object> record = this.store.idemFor(RUN_ID, "c-2");

		assertThat(record).as("索引命中：取回原记录").isNotNull();
		assertThat(record).containsEntry("toolCallId", "c-2").containsEntry("resultText", "结果 2");
		assertThat(this.fake.listIndexCalls.get()).as("命中读口 = LINDEX（O(1)）").isEqualTo(1);
		assertThat(this.fake.listRangeCalls.get()).as("命中不得回落 LRANGE 全表扫描").isZero();
	}

	@Test
	void indexPointsAtTheRightRecordWhenIdsShareAPrefix() {
		this.store.appendLedger(RUN_ID, entry("c-1"), "一");
		this.store.appendLedger(RUN_ID, entry("c-10"), "十");
		this.store.appendLedger(RUN_ID, entry("c-11"), "十一");

		assertThat(this.store.idemFor(RUN_ID, "c-10")).containsEntry("resultText", "十");
		assertThat(this.store.idemFor(RUN_ID, "c-1")).containsEntry("resultText", "一");
		assertThat(this.store.idemFor(RUN_ID, "c-11")).containsEntry("resultText", "十一");
	}

	// ── I3：同一条 Lua（原子性） ───────────────────────────────────────────

	@Test
	void ledgerAndIndexAreWrittenInOneScript() {
		this.fake.resetCounters();

		this.store.appendLedger(RUN_ID, entry("c-1"), "已执行");

		assertThat(this.fake.scriptCalls.get()).as("一次脚本调用完成两件事").isEqualTo(1);
		assertThat(this.fake.scriptPushes.get()).as("脚本内一次 RPUSH").isEqualTo(1);
		assertThat(this.fake.templatePushes.get())
			.as("脚本之外不得再有第二条 RPUSH（两步之间没有可中断的命令边界）").isZero();
		assertThat(this.fake.templateHashPuts.get()).as("索引不得在脚本外单独写").isZero();

		String field = RunStore.idemField(RUN_ID, "c-1");
		String indexed = this.fake.table(this.store.idemKey(RUN_ID)).get(field);
		assertThat(indexed).as("索引值 = <listIndex>|<outcome>|<code>|<executedBy>|<atMs>")
			.startsWith("0|" + ToolOutcome.OK.wire() + "||" + this.store.instanceId() + "|");
		assertThat(indexed).doesNotContain("已执行").as("索引不复制 resultText（正文只留在台账里）");
		assertThat(this.fake.list(this.store.ledgerKey(RUN_ID))).hasSize(1);
		// 脚本原文：语义替身按标记分派，改坏正文它照样"通过" —— 契约（一次 Lua 内 RPUSH + HSETNX +
		// 双 PEXPIRE）必须对着脚本正文断言，否则 I3 只证明了"调用了一次脚本"。
		String body = this.fake.lastScriptBody();
		assertThat(body).as("同一条 Lua 里完成台账与索引两步")
			.contains("IDEM_PUSH")
			.contains("'LLEN', KEYS[1]")
			.contains("'RPUSH', KEYS[1]")
			.contains("'HSETNX', KEYS[2]")
			.contains("'PEXPIRE', KEYS[1]")
			.contains("'PEXPIRE', KEYS[2]");
		// M-2（批③b 复审）：下标必须在<b>推入之前</b>取（LLEN 先于 RPUSH）—— 顺序反了拿到的是
		// "含本条记录之后"的长度，索引会整体偏移一位（写进去的下标永远指向别人的记录）。
		assertThat(body.indexOf("'LLEN'")).as("脚本正文里必须出现 LLEN").isGreaterThanOrEqualTo(0);
		assertThat(body.indexOf("'LLEN'")).as("下标先取（LLEN）再推入（RPUSH）")
			.isLessThan(body.indexOf("'RPUSH'"));
		assertThat(this.fake.lastScriptKeys()).as("KEYS 顺序 = [台账键, 索引键]")
			.containsExactly(this.store.ledgerKey(RUN_ID), this.store.idemKey(RUN_ID));
	}

	@Test
	void indexAndLedgerShareTheSessionTtlAndExpireTogether() {
		Duration sessionTtl = this.properties.getSessionTtl();

		this.store.appendLedger(RUN_ID, entry("c-1"), "已执行");

		assertThat(this.fake.expiry(this.store.ledgerKey(RUN_ID))).isEqualTo(sessionTtl);
		assertThat(this.fake.expiry(this.store.idemKey(RUN_ID))).isEqualTo(sessionTtl);

		this.fake.advanceTime(sessionTtl.plusSeconds(1));

		assertThat(this.fake.lists.containsKey(this.store.ledgerKey(RUN_ID))).as("台账随会话过期").isFalse();
		assertThat(this.fake.hashTables.containsKey(this.store.idemKey(RUN_ID))).as("索引同寿").isFalse();
		assertThat(this.store.idemFor(RUN_ID, "c-1")).isNull();
	}

	// ── I6：旧数据 / 坏索引的回落 ──────────────────────────────────────────

	@Test
	void recordsWithoutAnIndexFallBackToTheScan() {
		// 批③a 之前写入的台账：只有 LIST，没有 ai:idem
		this.fake.list(this.store.ledgerKey(RUN_ID)).add(legacyRecord("c-old", "旧结果"));

		Map<String, Object> record = this.store.idemFor(RUN_ID, "c-old");

		assertThat(record).as("未命中索引 ⇒ 回落扫描（旧数据照常判'已执行'）").isNotNull();
		assertThat(record).containsEntry("resultText", "旧结果");
		assertThat(this.fake.listRangeCalls.get()).as("回落路径就是一次全表扫描").isEqualTo(1);
		assertThat(this.fake.listIndexCalls.get()).as("未命中不读 LINDEX").isZero();
	}

	@Test
	void aBrokenIndexFallsBackToTheScanInsteadOfReportingNotExecuted() {
		this.store.appendLedger(RUN_ID, entry("c-1"), "已执行");
		// 索引指向越界下标（台账被裁剪 / 索引被污染）
		this.fake.table(this.store.idemKey(RUN_ID)).put(RunStore.idemField(RUN_ID, "c-1"),
				"99|" + ToolOutcome.OK.wire() + "||" + this.store.instanceId() + "|0");

		Map<String, Object> record = this.store.idemFor(RUN_ID, "c-1");

		assertThat(record).as("索引坏掉不能把'已执行'报成'没执行'（否则重复副作用）").isNotNull();
		assertThat(record).containsEntry("toolCallId", "c-1");
		assertThat(this.fake.listRangeCalls.get()).as("坏索引 ⇒ 回落扫描").isEqualTo(1);
	}

	// ── M-1（批③b 复审）：索引错位不得把别人的结果交出去 ──────────────────

	@Test
	void anIndexPointingAtAnotherCallsRecordFallsBackToTheScan() {
		this.store.appendLedger(RUN_ID, entry("c-1"), "结果 1");
		this.store.appendLedger(RUN_ID, entry("c-2"), "结果 2");
		// 索引错位：c-2 的 field 指向 c-1 的下标 0（下标污染/手工改键/并发写错位）
		this.fake.table(this.store.idemKey(RUN_ID)).put(RunStore.idemField(RUN_ID, "c-2"),
				"0|" + ToolOutcome.OK.wire() + "||" + this.store.instanceId() + "|0");

		Map<String, Object> record = this.store.idemFor(RUN_ID, "c-2");

		assertThat(record).as("错位索引不能把 c-1 的结果当成 c-2 的结果").isNotNull();
		assertThat(record).containsEntry("toolCallId", "c-2").containsEntry("resultText", "结果 2");
		assertThat(this.fake.listRangeCalls.get()).as("错位 ⇒ 回落扫描（由记录自身定身份）").isEqualTo(1);
		assertThat(this.store.idemFor(RUN_ID, "c-1")).as("原记录（c-1）的索引不受影响")
			.containsEntry("toolCallId", "c-1")
			.containsEntry("resultText", "结果 1");
	}

	@Test
	void unknownToolCallIdReturnsNull() {
		this.store.appendLedger(RUN_ID, entry("c-1"), "已执行");

		assertThat(this.store.idemFor(RUN_ID, "never-called")).isNull();
		assertThat(this.store.idemFor(RUN_ID, null)).isNull();
	}

	// ── additive：idemKey 字段 ────────────────────────────────────────────

	@Test
	void recordCarriesAnAdditiveIdemKeyFieldAndLegacyRecordsStayReadable() {
		this.fake.list(this.store.ledgerKey(RUN_ID)).add(legacyRecord("c-old", "旧结果"));

		this.store.appendLedger(RUN_ID, entry("c-new"), "新结果");

		Map<String, Object> fresh = this.store.ledgerFor(RUN_ID, "c-new");
		assertThat(fresh).as("additive：新记录带 idemKey")
			.containsEntry("idemKey", RunStore.idemField(RUN_ID, "c-new"));
		Map<String, Object> legacy = this.store.ledgerFor(RUN_ID, "c-old");
		assertThat(legacy).as("旧记录没有该字段，读侧照常返回").isNotNull().doesNotContainKey("idemKey");
		assertThat(legacy).containsEntry("resultText", "旧结果");
		assertThat(this.store.ledger(RUN_ID)).as("两种形态共存于同一台账").hasSize(2);
	}

	@Test
	void deleteRunLeavesResourceLocksAlone() {
		// 资源锁按 taskId 记（LeaseKeys），与 run 的生死无关：轮次回收不能把别人的业务互斥一起放掉
		this.fake.strings.put(LeaseKeys.task(7L), "1|task-7:import");
		this.store.appendLedger(RUN_ID, entry("c-1"), "已执行");

		this.store.deleteRun(RUN_ID);

		assertThat(this.fake.string(LeaseKeys.task(7L)))
			.as("deleteRun 只删 runId 族的键；ai:lock:* 由 resource-ttl 自清").isNotNull();
	}

	@Test
	void deleteRunClearsTheIdemIndexWithTheLedger() {
		this.store.appendLedger(RUN_ID, entry("c-1"), "已执行");

		this.store.deleteRun(RUN_ID);

		assertThat(this.fake.hashTables.containsKey(this.store.idemKey(RUN_ID))).isFalse();
		assertThat(this.fake.lists.containsKey(this.store.ledgerKey(RUN_ID))).isFalse();
		assertThat(this.store.idemFor(RUN_ID, "c-1")).isNull();
	}

	// ───────────────────────── helpers ─────────────────────────

	private PendingToolCall entry(String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_BACKEND);
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setOutcome(ToolOutcome.OK.wire());
		pending.setExecuted(true);
		pending.setExecutedBy(this.store.instanceId());
		return pending;
	}

	/** 批③a 之前的台账记录形态：没有 idemKey，也没有 ai:idem 索引。 */
	private String legacyRecord(String toolCallId, String resultText) {
		return "{\"atMs\":1,\"toolCallId\":\"" + toolCallId + "\",\"name\":\"start_import\",\"kind\":\"backend\","
				+ "\"outcome\":\"ok\",\"code\":null,\"resultText\":\"" + resultText + "\"}";
	}

}
