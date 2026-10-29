package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩编排（上下文专项批①：L1 的判定 / 归档 / 记账 / 失败回滚）。
 *
 * <p>
 * 本组锁的是"编排层"的四条契约（纯规则在 {@link ToolResultClearingTest} / {@link ContextTriggerTest} 里）：
 * <ol>
 * <li><b>未达线不动手</b>：不开归档、不写台账（归档只在真压缩时写，否则一个长会话会把
 * Redis 内存按轮数吃光）；</li>
 * <li><b>先归档再改真值</b>：原文可回溯是"压缩"与"静默截断"的分界线；</li>
 * <li><b>滞回锁</b>：压缩完真值仍 ≥60% 时不许再来一次（否则每轮改写前缀、缓存全灭）；</li>
 * <li><b>失败保持原状</b>：归档/记账抛错时返回<b>原消息列表</b>，本轮照常发请求。</li>
 * </ol>
 */
class ContextCompactorTest {

	private static final String SESSION_ID = "s-compact";

	private static final String RUN_ID = "run-compact";

	private final AiProperties properties = new AiProperties();

	private final InMemoryContextStateStore store = new InMemoryContextStateStore();

	private final ContextLimitResolver resolver = new ContextLimitResolver(this.properties);

	private final ContextCompactor compactor = new ContextCompactor(this.properties, this.resolver, this.store);

	private static List<Message> turns(int pairs) {
		List<Message> messages = new ArrayList<>();
		for (int i = 1; i <= pairs; i++) {
			messages.add(new UserMessage("第 " + i + " 个问题"));
			messages.add(AssistantMessage.builder()
				.content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall("c-" + i, "function", "list_tasks", "{}")))
				.build());
			messages.add(ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse("c-" + i, "list_tasks",
						"任务列表内容".repeat(50) + i)))
				.build());
		}
		return messages;
	}

	private ContextCompactor.Request request(List<Message> messages, int usedTokens, List<String> messageJsonBefore) {
		return new ContextCompactor.Request(SESSION_ID, RUN_ID, "deepseek-flash", 1L, messages, messageJsonBefore,
				usedTokens, 0, 0, 0, 3000, 4000, Set.of(), TestProviderRuntime.runtime());
	}

	/** 越线 ⇒ L1 执行：归档 + 台账 + 真值重置为估算基线。 */
	@Test
	void overThresholdClearsToolResultsArchivesAndRecords() {
		List<Message> messages = turns(6);
		ContextCompactor.Outcome outcome = this.compactor.compact(request(messages, 81000, List.of("{\"m\":1}")));

		assertThat(outcome.changed()).isTrue();
		assertThat(outcome.level()).isEqualTo(ContextTrigger.Level.L1);
		assertThat(outcome.clearedPairs()).isEqualTo(1);
		assertThat(outcome.clearedToolCallIds()).containsExactly("c-1");
		assertThat(outcome.messages()).hasSameSizeAs(messages);
		assertThat(outcome.after()).isLessThan(outcome.before());
		assertThat(this.store.archiveCount()).as("先归档：原文必须可回溯").isEqualTo(1);
		assertThat(this.store.archived(SESSION_ID, 0)).containsExactly("{\"m\":1}");
		assertThat(this.store.ledgerCount()).isEqualTo(1);

		ContextState state = this.store.read(SESSION_ID);
		assertThat(state.level()).isEqualTo("L1");
		assertThat(state.est()).as("压缩后基线是估算值，等下一次真值回填").isTrue();
		// C13：基线 = 压缩后历史估算 + system(3000) + tools(4000) —— 上游真值本就含这两项，
		// 只写历史估算会让下一轮 nextEst 永远少算前缀（见 c13CompactionBaselineIncludesSystemAndTools）
		assertThat(state.usedTokens()).isEqualTo(outcome.after() + 3000 + 4000);
	}

	/**
	 * C13：压缩后的估算基线必须<b>含 system + tools</b>（{@code request(...)} 固定 3000 / 4000）。
	 *
	 * <p>缺陷现场：旧实现把基线写成"压缩后历史估算"，于是下一轮 {@code nextEst = 基线 + 完成 + 输入}
	 * 永远少算 system 人设/工作区快照与工具 schema —— 小窗口模型上就是"看着没满、一发就 400"。
	 * 归档/台账/frame 的 {@code before/after} 仍是<b>消息口径</b>（与轮首同账），只有基线补齐前缀。
	 */
	@Test
	void c13CompactionBaselineIncludesSystemAndTools() {
		ContextCompactor.Outcome outcome = this.compactor.compact(request(turns(6), 81000, List.of("{\"m\":1}")));

		assertThat(outcome.changed()).isTrue();
		assertThat(outcome.usedTokens()).isEqualTo(outcome.after() + 3000 + 4000);
		assertThat(this.store.read(SESSION_ID).usedTokens()).isEqualTo(outcome.after() + 3000 + 4000);
	}

	/**
	 * C13：轮首没有真值时按<b>整包估算</b>判（历史 + system + tools + 本轮输入），
	 * 且预留按实际窗口收口 —— 小窗口不再因为"只算新输入"而零预警越窗。
	 */
	@Test
	void c13TurnStartWithoutTruthCountsSystemToolsAndHistory() {
		AiProperties smallWindow = new AiProperties();
		smallWindow.getContext().setLimitTokens(15000);
		ContextCompactor service = new ContextCompactor(smallWindow, new ContextLimitResolver(smallWindow), this.store);

		ContextCompactor.Outcome outcome = service.compact(new ContextCompactor.Request(SESSION_ID, RUN_ID,
				"deepseek-flash", 1L, turns(6), List.of("{}"), 0, 0, 0, 0, 3000, 4000, Set.of(),
				TestProviderRuntime.runtime()));

		// 整包 = 历史估算 + system 3000 + tools 4000 + 本轮输入 0（逐项相加，不是"大约大于 7000"）
		assertThat(outcome.nextEst())
			.isEqualTo(ContextEstimator.estimateMessages(turns(6)) + 3000 + 4000);
		assertThat(outcome.reason()).as("只算新输入（0）会误报 under_threshold")
			.isNotEqualTo(ContextCompactor.REASON_UNDER_THRESHOLD);
		// 分母 = 15000 − min(13000, 7500) = 7500（未收口时会是 15000 − 13000 = 2000）
		assertThat(outcome.ratio()).isCloseTo(outcome.nextEst() / 7500d,
				org.assertj.core.data.Offset.offset(1e-9));
	}

	/** 未达线 ⇒ 什么都不做（不开归档、不写台账、不动消息）。 */
	@Test
	void underThresholdIsANoOp() {
		List<Message> messages = turns(6);
		ContextCompactor.Outcome outcome = this.compactor.compact(request(messages, 1000, List.of("{}")));

		assertThat(outcome.changed()).isFalse();
		assertThat(outcome.reason()).isEqualTo(ContextCompactor.REASON_UNDER_THRESHOLD);
		assertThat(outcome.messages()).isSameAs(messages);
		assertThat(this.store.archiveCount()).isZero();
		assertThat(this.store.ledgerCount()).isZero();
	}

	/**
	 * L-89 回归：过渡键与 DB 值漂移时，<b>触发判定与展示面必须用同一个分母</b>（DB 值）。
	 *
	 * <p>漂移现场（真机 E2E 批③）：过渡键 1500、DB {@code max_context} 128000。此时
	 * 「显示的百分比」按 128000 算，「触发百分比」按 1500（减预留后钳到 1）算 —— 用 1000 token
	 * 的历史就会立刻判满。本用例要求触发侧也读 {@code ContextLimitResolver}：对 128000 而言
	 * 1000 token 远未达线 ⇒ 不压缩，且 ratio 与展示面同一分母。
	 */
	@Test
	void triggerDenominatorFollowsResolverNotTheTransitionalKey() {
		AiProperties drifted = new AiProperties();
		drifted.getContext().setLimitTokens(1500);
		ContextLimitResolver dbBacked = new ContextLimitResolver(drifted, (providerId, modelId) -> 128000);
		ContextCompactor service = new ContextCompactor(drifted, dbBacked, this.store);

		ContextCompactor.Outcome outcome = service.compact(request(turns(6), 1000, List.of("{}")));

		assertThat(outcome.limitTokens()).as("压缩结局里的分母 = DB 值").isEqualTo(128000);
		assertThat(outcome.changed()).as("1000/115000 远未到 L1，不该压").isFalse();
		assertThat(outcome.reason()).isEqualTo(ContextCompactor.REASON_UNDER_THRESHOLD);
		assertThat(outcome.ratio()).isCloseTo(1000d / 115000d, org.assertj.core.data.Offset.offset(1e-9));
		// 展示面同一分母（/context 的 limitTokens 走同一个 resolver）。端点重建分母需要模型身份 ⇒ 先记账一次
		ContextBreakdownService display = new ContextBreakdownService(service, this.store);
		service.recordTurn(SESSION_ID, "deepseek-flash", 1000, 0, 1, false, 0, 0, 0);
		Map<String, Object> snapshot = display.snapshot(SESSION_ID, turns(2), false);
		assertThat(snapshot).containsEntry("limitTokens", 128000);
		assertThat(snapshot).containsEntry("percent", 0.8d);
	}

	/** 上限未知（降级态）⇒ 不做比例判定。 */
	@Test
	void unknownLimitSkipsCompactionEntirely() {
		AiProperties degraded = new AiProperties();
		degraded.getContext().setLimitTokens(0);
		ContextCompactor service = new ContextCompactor(degraded, new ContextLimitResolver(degraded), this.store);

		ContextCompactor.Outcome outcome = service.compact(request(turns(6), 500_000, List.of("{}")));

		assertThat(outcome.changed()).isFalse();
		assertThat(outcome.reason()).isEqualTo(ContextCompactor.REASON_LIMIT_UNKNOWN);
	}

	/** 滞回：压缩已成但真值仍 62% ⇒ 不再触发（reason=hysteresis_locked）。 */
	@Test
	void hysteresisLockPreventsReCompaction() {
		ContextState compacted = ContextState.EMPTY.withUsage(79360, 0, 1, false, System.currentTimeMillis(),
				"deepseek-flash", 0, 0, 0).withCompaction(ContextTrigger.Level.L1, 0, 0, "l1_tool_result_clearing",
						System.currentTimeMillis());
		this.store.write(SESSION_ID, compacted);

		ContextCompactor.Outcome outcome = this.compactor.compact(request(turns(6), 79360, List.of("{}")));

		assertThat(outcome.changed()).isFalse();
		assertThat(outcome.reason()).isEqualTo(ContextCompactor.REASON_HYSTERESIS_LOCKED);
		assertThat(this.store.archiveCount()).isZero();
	}

	/** 没有可清的结果（全在 pin 窗口内）⇒ reason=nothing_to_clear，不归档。 */
	@Test
	void nothingToClearIsReportedAndNotArchived() {
		ContextCompactor.Outcome outcome = this.compactor.compact(request(turns(2), 90000, List.of("{}")));

		assertThat(outcome.changed()).isFalse();
		assertThat(outcome.reason()).isEqualTo(ContextCompactor.REASON_NOTHING_TO_CLEAR);
		assertThat(this.store.archiveCount()).isZero();
	}

	/** 归档失败 ⇒ 返回原消息（本轮照常发请求），reason=compaction_failed。 */
	@Test
	void archiveFailureKeepsHistoryUntouched() {
		InMemoryContextStateStore broken = new InMemoryContextStateStore() {
			@Override
			public void archive(String sessionId, int turnNo, List<String> messageJson) {
				throw new IllegalStateException("Redis 不可用");
			}
		};
		ContextCompactor service = new ContextCompactor(this.properties, this.resolver, broken);
		List<Message> messages = turns(6);

		ContextCompactor.Outcome outcome = service.compact(request(messages, 81000, List.of("{}")));

		assertThat(outcome.changed()).isFalse();
		assertThat(outcome.reason()).isEqualTo(ContextCompactor.REASON_FAILED);
		assertThat(outcome.messages()).isSameAs(messages);
		assertThat(broken.ledgerCount()).isZero();
	}

	/** 每次最多升一级 + 批① 封顶 L1：占比到 L3 也只做 L1（不许报没有实现的级别）。 */
	@Test
	void batchOneNeverReportsAboveL1() {
		ContextCompactor.Outcome outcome = this.compactor.compact(request(turns(6), 120000, List.of("{}")));

		assertThat(outcome.changed()).isTrue();
		assertThat(outcome.level()).isEqualTo(ContextTrigger.Level.L1);
	}

	/** 轮末记账：真值 + 三个分项锚点写入 {@code ai:ctx:}（端点重建的唯一来源）。 */
	@Test
	void recordTurnWritesTruthAndAnchors() {
		this.compactor.recordTurn(SESSION_ID, "deepseek-flash", 42000, 900, 3, false, 3100, 5200, 90);

		ContextState state = this.store.read(SESSION_ID);
		assertThat(state.usedTokens()).isEqualTo(42000);
		assertThat(state.completionTokens()).isEqualTo(900);
		assertThat(state.turnNo()).isEqualTo(3);
		assertThat(state.model()).isEqualTo("deepseek-flash");
		assertThat(state.est()).isFalse();
		assertThat(state.systemTokens()).isEqualTo(3100);
		assertThat(state.toolsTokens()).isEqualTo(5200);
		assertThat(state.inputTokens()).isEqualTo(90);
		assertThat(state.measured()).isTrue();
	}

	/** 记账写失败只 WARN，不抛（观测面不能把一轮已成功的对话变成失败）。 */
	@Test
	void recordTurnSwallowsWriteFailure() {
		InMemoryContextStateStore broken = new InMemoryContextStateStore() {
			@Override
			public void write(String sessionId, ContextState state) {
				throw new IllegalStateException("Redis 不可用");
			}
		};
		ContextCompactor service = new ContextCompactor(this.properties, this.resolver, broken);

		service.recordTurn(SESSION_ID, "m", 1, 0, 1, false, 0, 0, 0);
		// 不抛即通过；读回落空态
		assertThat(service.readState(SESSION_ID).measured()).isFalse();
	}

	/** 分项锚点写入后，端点装配能给出七项（compaction 形状固定）。 */
	@Test
	void breakdownServiceBuildsTheSevenPartPayload() {
		this.compactor.recordTurn(SESSION_ID, "deepseek-flash", 5000, 100, 1, false, 1000, 2000, 50);
		ContextBreakdownService service = new ContextBreakdownService(this.compactor, this.store);

		Map<String, Object> snapshot = service.snapshot(SESSION_ID, turns(2), false);

		assertThat(snapshot).containsEntry("usedTokens", 5000).containsEntry("limitTokens", 128000)
			.containsEntry("sessionId", SESSION_ID).containsEntry("estimated", false);
		assertThat((List<?>) snapshot.get("breakdown")).hasSize(7);
		assertThat(snapshot.get("compaction")).isInstanceOf(Map.class);
		@SuppressWarnings("unchecked")
		Map<String, Object> compaction = (Map<String, Object>) snapshot.get("compaction");
		assertThat(compaction).containsEntry("level", "none").containsEntry("circuit", "closed")
			.containsEntry("fuseFails", 0);
	}

	/** 会话从未记账：端点回"未测"（source=none、percent=null），而不是"用了 0 / 0%"的谎。 */
	@Test
	void unmeasuredSessionReportsNone() {
		ContextBreakdownService service = new ContextBreakdownService(this.compactor, this.store);

		Map<String, Object> snapshot = service.snapshot("s-never", List.of(), false);

		assertThat(snapshot).containsEntry("source", "none").containsEntry("usedTokens", 0)
			.containsEntry("percent", null);
	}

	// ── 批③：L2 / L3 / 熔断（以下为追加用例，不改上方任何断言） ──────────────────

	/** 9 轮历史（每轮 user + assistant），够 L2/L3 的 keep-recent-turns=6 切出 3 轮旧轮。 */
	private static List<Message> longTurns() {
		List<Message> messages = new ArrayList<>();
		for (int i = 1; i <= 9; i++) {
			messages.add(new UserMessage("第 " + i + " 轮问题 " + "问".repeat(40)));
			messages.add(AssistantMessage.builder().content("第 " + i + " 轮回答 " + "答".repeat(40)).build());
		}
		return messages;
	}

	private ContextCompactor.Request requestWith(List<Message> messages, int usedTokens, int completionTokens,
			Set<String> protectedIds) {
		return new ContextCompactor.Request(SESSION_ID, RUN_ID, "deepseek-flash", 1L, messages, List.of("{}"),
				usedTokens, completionTokens, 0, 0, 3000, 4000, protectedIds, TestProviderRuntime.runtime());
	}

	/** N-C 语料：9 轮、每轮 ≈4K token —— 丢最旧 3 轮能换出 ≈12K，够覆盖"输出预留放不下"的缺口。 */
	private static List<Message> hugeTurns() {
		List<Message> messages = new ArrayList<>();
		for (int i = 1; i <= 9; i++) {
			messages.add(new UserMessage("第 " + i + " 轮问题"));
			messages.add(AssistantMessage.builder().content("第 " + i + " 轮回答 " + "答".repeat(6_800)).build());
		}
		return messages;
	}

	private ContextCompactor compactorWith(RollingSummarizer.Summarizer summarizer) {
		return new ContextCompactor(this.properties, this.resolver, this.store, summarizer);
	}

	private void seed(String level, int usedTokens) {
		long now = System.currentTimeMillis();
		this.store.write(SESSION_ID, ContextState.EMPTY
			.withUsage(usedTokens, 0, 1, false, now, "deepseek-flash", 0, 0, 0)
			.withCompaction(ContextTrigger.Level.fromWire(level), 0, 0, "seed", now));
	}

	/**
	 * C13 / N3：本轮 <b>system + tools 本身就占到 L2 线以上</b>时，L2/L3 帮不上忙
	 * （它们只能压缩历史，压不动不可压缩的前缀）⇒ 不做，如实报 {@code prefix_dominated}。
	 *
	 * <p>缺陷现场：滞回只看"可压缩历史"（C13/I3 之后）⇒ 前缀主导时它几乎恒解锁；而触发线看的是
	 * "整条请求 / 分母" ⇒ 每轮都判 L3 ⇒ <b>每轮硬截断</b>，可历史早已被丢到 pin 下限（什么也丢不掉），
	 * 结果是"每轮都归档/记账一次空操作"，模型侧一遍遍丢上下文。
	 */
	@Test
	void c13PrefixDominatedRoundDoesNotRepeatedlyHardTrim() {
		AiProperties smallWindow = new AiProperties();
		smallWindow.getContext().setLimitTokens(15000);
		ContextCompactor service = new ContextCompactor(smallWindow, new ContextLimitResolver(smallWindow),
				this.store);
		// 15000 窗口、预留 13000 ⇒ 有效预留 7500；本轮 prefix 7000（requestWith 固定）⇒ 93% ≥ L2 线。
		// 真值 7000 ⇒ 7000 + 7500 = 14500 ≤ 15000：**连输出预留都放得下**（N-A 之后守卫的前置条件），
		// 这个局面才是"压了也没用、但不压也不会 400"的那一档。
		seed("L2", 7000);

		ContextCompactor.Outcome first = service.compact(requestWith(longTurns(), 7000, 0, Set.of()));

		assertThat(first.level()).as("前缀主导 ⇒ 不得做 L2/L3").isEqualTo(ContextTrigger.Level.NONE);
		assertThat(first.reason()).isEqualTo(ContextCompactor.REASON_PREFIX_DOMINATED);
		assertThat(first.turns()).isZero();
		assertThat(this.store.archiveCount()).as("不做压缩就不归档").isZero();
		assertThat(this.store.read(SESSION_ID).level()).as("谱系不得被空操作推进").isEqualTo("L2");

		ContextCompactor.Outcome second = service.compact(requestWith(longTurns(), 7000, 0, Set.of()));

		assertThat(second.level()).as("第二轮同样不做（不是'每轮再截一次'）").isEqualTo(ContextTrigger.Level.NONE);
		assertThat(second.reason()).isEqualTo(ContextCompactor.REASON_PREFIX_DOMINATED);
		assertThat(this.store.archiveCount()).isZero();
	}

	/**
	 * C13 / R3 C1：前缀主导<b>不等于</b>可以放过真正越窗的那一轮。
	 *
	 * <p>缺陷现场：守卫只看"前缀占比"就把 target 降到 L1 —— 可当 {@code nextEst >= limit}（这一发注定
	 * 400）时，L3 硬截断是唯一能腾出空间的一刀。挡掉它 = 直接拿上游 400 换一次"没压"。
	 * 正确边界：<b>只有 {@code nextEst < limit}（还有余量、L2/L3 只是徒劳）时才降级</b>；
	 * 真越窗必须保留 L3。
	 */
	@Test
	void c13PrefixDominatedOverflowStillHardTrimsInsteadOfSendingAnOverflowingRequest() {
		AiProperties smallWindow = new AiProperties();
		smallWindow.getContext().setLimitTokens(15000);
		ContextCompactor service = new ContextCompactor(smallWindow, new ContextLimitResolver(smallWindow),
				this.store);
		// 谱系已到 L2（这一步的目标是 L3）；真值 5000 让滞回解锁，但续跑重建的工具结果 +12000
		// ⇒ nextEst = 17000 >= 15000：这一发注定 400（真实入口 = ResumeService 的 rebuiltToolTokens）。
		// 语料用"可丢的 3 轮足够大"（每轮 ≈4K token）：N-C 只允许在"裁后能放下"时裁 ——
		// 17000 − 12000 + 7500 = 12500 ≤ 15000，这一刀真的换得出空间。
		seed("L2", 5000);
		ContextCompactor.Request overflow = new ContextCompactor.Request(SESSION_ID, RUN_ID, "deepseek-flash", 1L,
				hugeTurns(), List.of("{}"), 5000, 0, 0, 12000, 3000, 4000, Set.of(), TestProviderRuntime.runtime());

		ContextCompactor.Outcome outcome = service.compact(overflow);

		assertThat(outcome.changed()).as("真越窗必须真的压一次，不能 noop 就发出去").isTrue();
		assertThat(outcome.level()).as("prefix 主导 + 真越窗 ⇒ 仍做 L3 硬截断").isEqualTo(ContextTrigger.Level.L3);
		assertThat(outcome.turns()).isEqualTo(3);
		assertThat(outcome.reason()).isEqualTo(HardTrim.REASON_HARD_TRIM);
		assertThat(this.store.read(SESSION_ID).level()).isEqualTo("L3");
		assertThat(this.store.archiveCount()).as("硬截断先归档（原文可回溯）").isEqualTo(1);
	}

	/**
	 * C13 / R4 N-A（边界一）：{@code nextEst + 有效预留 == limit}——prompt <b>加上输出预留</b>恰好放得下，
	 * 这一发不会 400 ⇒ L2/L3 在这种窗口里确实徒劳（压不动固定前缀），守卫可以降级。
	 */
	@Test
	void c13PrefixDominatedAtTheCompletionReserveBoundaryStillDowngrades() {
		AiProperties smallWindow = new AiProperties();
		smallWindow.getContext().setLimitTokens(15000);
		ContextCompactor service = new ContextCompactor(smallWindow, new ContextLimitResolver(smallWindow),
				this.store);
		// 有效预留 = min(13000, 15000/2) = 7500；nextEst 7500 ⇒ 7500 + 7500 == 15000（刚好）
		seed("L2", 7500);
		ContextCompactor.Request boundary = new ContextCompactor.Request(SESSION_ID, RUN_ID, "deepseek-flash", 1L,
				longTurns(), List.of("{}"), 7500, 0, 0, 0, 3000, 4000, Set.of(), TestProviderRuntime.runtime());

		ContextCompactor.Outcome outcome = service.compact(boundary);

		assertThat(outcome.level()).as("放得下输出预留 ⇒ 不必截断").isEqualTo(ContextTrigger.Level.NONE);
		assertThat(outcome.reason()).isEqualTo(ContextCompactor.REASON_PREFIX_DOMINATED);
		assertThat(outcome.turns()).isZero();
		assertThat(this.store.archiveCount()).isZero();
	}

	/**
	 * C13 / R5 N-C：<b>裁完也装不下</b>时不得丢历史（前缀主导 + pin 下限的精确边界）。
	 *
	 * <p>缺陷现场：R4 只要"prompt + 输出预留放不下"就保留 L3，于是 L3 照做 —— 可这条历史已经压到
	 * `keep-recent-turns` 的下限，丢掉的几轮换不回任何可用空间（after 仍然 > limit − reserve）。
	 * 结果是<b>先丢用户的历史、再发一次注定 400 的请求</b>：净新增的数据丢失。
	 *
	 * <p>正确口径：应用 HardTrim 之前先按"裁后估算"判 ——
	 * {@code after = nextEst − lostTokens}，只有 {@code after + effectiveReserve <= limit} 才允许
	 * 裁剪并记账；否则 noop（保留原历史、不归档、不丢轮），原因如实报 {@code hard_trim_cannot_fit}。
	 */
	@Test
	void c13PrefixDominatedTrimThatStillDoesNotFitIsNotApplied() {
		AiProperties smallWindow = new AiProperties();
		smallWindow.getContext().setLimitTokens(15000);
		ContextCompactor service = new ContextCompactor(smallWindow, new ContextLimitResolver(smallWindow),
				this.store);
		// nextEst 8000（> limit − reserve 7500 ⇒ 输出预留放不下），可丢的 3 轮只有 ~165 token ⇒
		// 裁后 7835 + 7500 = 15335 > 15000：裁完仍放不下
		seed("L2", 8000);
		List<Message> history = longTurns();
		ContextCompactor.Request impossible = new ContextCompactor.Request(SESSION_ID, RUN_ID, "deepseek-flash", 1L,
				history, List.of("{}"), 8000, 0, 0, 0, 3000, 4000, Set.of(), TestProviderRuntime.runtime());

		ContextCompactor.Outcome outcome = service.compact(impossible);

		assertThat(outcome.changed()).as("裁完仍装不下 ⇒ 不许丢历史").isFalse();
		assertThat(outcome.level()).isEqualTo(ContextTrigger.Level.NONE);
		assertThat(outcome.reason()).isEqualTo(ContextCompactor.REASON_TRIM_CANNOT_FIT);
		assertThat(outcome.turns()).isZero();
		assertThat(outcome.lostTokens()).isZero();
		assertThat(outcome.messages()).as("历史原样返回（同一份列表）").isSameAs(history);
		assertThat(this.store.archiveCount()).as("不裁剪就不归档").isZero();
		assertThat(this.store.contextLedger(SESSION_ID)).isEmpty();
		assertThat(this.store.read(SESSION_ID).level()).as("谱系不动").isEqualTo("L2");
	}

	/**
	 * C13 / R4 N-A（边界二）的另一半：输出预留放不下、但<b>裁后能放下</b> ⇒ 仍正常做 L3。
	 *
	 * <p>语料换成"可丢的几轮足够大"（每轮 ≈4K token，丢 3 轮 ≈12K）—— 这正是 N-C 之后
	 * "保留 L3"唯一成立的局面：裁剪真的能换出空间。
	 */
	@Test
	void c13PrefixDominatedAboveTheCompletionReserveKeepsL3() {
		AiProperties smallWindow = new AiProperties();
		smallWindow.getContext().setLimitTokens(15000);
		ContextCompactor service = new ContextCompactor(smallWindow, new ContextLimitResolver(smallWindow),
				this.store);
		// nextEst 8000 < limit 15000，8000 + 7500 = 15500 > 15000（输出预留放不下）；
		// 但可丢的 3 轮 ≈12K ⇒ after ≈ -4000 ⇒ 裁后放得下 ⇒ L3 必须照做
		seed("L2", 8000);
		ContextCompactor.Request tight = new ContextCompactor.Request(SESSION_ID, RUN_ID, "deepseek-flash", 1L,
				hugeTurns(), List.of("{}"), 8000, 0, 0, 0, 3000, 4000, Set.of(), TestProviderRuntime.runtime());

		ContextCompactor.Outcome outcome = service.compact(tight);

		assertThat(outcome.level()).as("裁后能放下 ⇒ 保留 L3").isEqualTo(ContextTrigger.Level.L3);
		assertThat(outcome.turns()).isEqualTo(3);
		assertThat(outcome.reason()).isEqualTo(HardTrim.REASON_HARD_TRIM);
		assertThat(this.store.archiveCount()).isEqualTo(1);
	}

	/**
	 * C13 / R6 N-D：判据必须计入<b>同一次里 L1 清除的收益</b> —— "裁「单独」不够、清 + 裁才够"时要两件都做。
	 *
	 * <p>缺陷现场（可达）：`clear = ToolResultClearing.plan(...)` 先算出零代价的清理（≥0 的 savedTokens），
	 * 可 L3 的闸只减了 `trim.lostTokens()` ⇒ `nextEst − lost + reserve > limit` ⇒ bail，而且 bail 的
	 * `noop` 返回**清除前**的 `request.messages()`：清除的收益既不进判据、也不进返回历史 ⇒
	 * "清掉旧结果就能过"的一轮被误判成"救不了"，直接 400。
	 */
	@Test
	void trimmingToFitCountsThePlannedL1Clear() {
		AiProperties smallWindow = new AiProperties();
		smallWindow.getContext().setLimitTokens(15000);
		// 只 pin 最近 1 条工具结果 ⇒ 首轮那条"大结果"是零代价可清的
		smallWindow.getContext().setKeepToolPairs(1);
		ContextCompactor service = new ContextCompactor(smallWindow, new ContextLimitResolver(smallWindow),
				this.store);
		// nextEst 20000（真值 5000 让滞回解锁，completion 15000 把它顶上去）；有效预留 7500：
		//   只裁：20000 − 7956 + 7500 = 19544 > 15000 ⇒ 裁不动
		//   清 + 裁：20000 − 4990 − 7956 + 7500 = 14554 ≤ 15000 ⇒ 刚好救得回来
		seed("L2", 5000);
		List<Message> history = roundsWithClearableResult(8_500);
		ContextCompactor.Request request = new ContextCompactor.Request(SESSION_ID, RUN_ID, "deepseek-flash", 1L,
				history, List.of("{}"), 5000, 15000, 0, 0, 3000, 4000, Set.of(), TestProviderRuntime.runtime());

		ContextCompactor.Outcome outcome = service.compact(request);

		assertThat(outcome.changed()).as("清 + 裁能救 ⇒ 必须真的压").isTrue();
		assertThat(outcome.level()).isEqualTo(ContextTrigger.Level.L3);
		assertThat(outcome.clearedPairs()).as("旧结果必须被清掉（不是只丢轮）").isEqualTo(1);
		assertThat(outcome.turns()).as("丢最旧 3 轮").isEqualTo(3);
		assertThat(outcome.reason()).isEqualTo(HardTrim.REASON_HARD_TRIM);
		assertThat(this.store.archiveCount()).isEqualTo(1);
		Map<String, Object> event = this.store.contextLedger(SESSION_ID).get(0);
		assertThat(event.get("level")).isEqualTo("L3");
		assertThat(event.get("clearedPairs")).isEqualTo(1);
	}

	/**
	 * C13 / R6 N-D（另一半）：<b>清除本身就能放下</b>、硬裁换不来额外空间时 —— 至少保住这次零代价的
	 * L1 清理，<b>不丢轮次</b>，也不写"硬裁"的归档/台账。
	 */
	@Test
	void clearingThatAlreadyFitsIsKeptAndDropsNoTurns() {
		AiProperties smallWindow = new AiProperties();
		smallWindow.getContext().setLimitTokens(15000);
		smallWindow.getContext().setKeepToolPairs(1);
		ContextCompactor service = new ContextCompactor(smallWindow, new ContextLimitResolver(smallWindow),
				this.store);
		// 大结果 ≈13K token ⇒ 清完 20000 − 13000 = 7000，+7500 = 14500 ≤ 15000：清除已经够了
		seed("L2", 5000);
		List<Message> history = roundsWithClearableResult(22_100);
		ContextCompactor.Request request = new ContextCompactor.Request(SESSION_ID, RUN_ID, "deepseek-flash", 1L,
				history, List.of("{}"), 5000, 15000, 0, 0, 3000, 4000, Set.of(), TestProviderRuntime.runtime());

		ContextCompactor.Outcome outcome = service.compact(request);

		assertThat(outcome.level()).as("清除已够 ⇒ 落 L1，不升到 L3").isEqualTo(ContextTrigger.Level.L1);
		assertThat(outcome.clearedPairs()).as("零代价清理必须落地").isEqualTo(1);
		assertThat(outcome.turns()).as("不丢轮次").isZero();
		assertThat(outcome.lostTokens()).isZero();
		assertThat(outcome.reason()).isEqualTo("l1_tool_result_clearing");
		assertThat(outcome.messages()).as("历史一条没少").hasSameSizeAs(history);
		assertThat(this.store.archiveCount()).as("归档的是这次 L1 清除（不是硬裁）").isEqualTo(1);
		Map<String, Object> event = this.store.contextLedger(SESSION_ID).get(0);
		assertThat(event.get("level")).as("台账记 L1（本次动作），不写硬裁事件").isEqualTo("L1");
		assertThat(event.get("reason")).isEqualTo("l1_tool_result_clearing");
		assertThat(((Number) event.get("lostTokens")).intValue()).isZero();
		// 只做 L1 时"只升不降"：谱系（状态档位）不得被这次 L1 动作回退成 L1
		assertThat(this.store.read(SESSION_ID).level()).as("谱系停在 L2，不回退").isEqualTo("L2");
	}

	/** 语料：9 轮（每轮 ≈2.6K token，丢 3 轮 ≈7.9K）+ 首轮一条可清的大工具结果 + 末轮一条小结果（被 pin）。 */
	private static List<Message> roundsWithClearableResult(int bigResultChars) {
		List<Message> messages = new ArrayList<>();
		for (int i = 1; i <= 9; i++) {
			messages.add(new UserMessage("第 " + i + " 轮问题"));
			if (i == 1) {
				messages.add(AssistantMessage.builder()
					.toolCalls(List.of(new AssistantMessage.ToolCall("old-1", "function", "list_tasks", "{}")))
					.build());
				messages.add(ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("old-1", "list_tasks",
							"大".repeat(bigResultChars))))
					.build());
			}
			messages.add(AssistantMessage.builder().content("第 " + i + " 轮回答 " + "答".repeat(4_500)).build());
			if (i == 9) {
				messages.add(AssistantMessage.builder()
					.toolCalls(List.of(new AssistantMessage.ToolCall("last-1", "function", "list_tasks", "{}")))
					.build());
				messages.add(ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("last-1", "list_tasks", "小结果")))
					.build());
			}
		}
		return messages;
	}

	/** C13 / N3 的另一半：前缀主导时 <b>L1 仍照现有口径处理</b>（清得掉就清、照常记账）。 */
	@Test
	void c13PrefixDominatedRoundStillAllowsL1Clearing() {
		AiProperties smallWindow = new AiProperties();
		smallWindow.getContext().setLimitTokens(15000);
		ContextCompactor service = new ContextCompactor(smallWindow, new ContextLimitResolver(smallWindow),
				this.store);
		seed("L2", 7000);

		ContextCompactor.Outcome outcome = service.compact(requestWith(turns(8), 7000, 0, Set.of()));

		assertThat(outcome.level()).as("L1 不受 prefix 守卫影响").isEqualTo(ContextTrigger.Level.L1);
		assertThat(outcome.reason()).isEqualTo("l1_tool_result_clearing");
		assertThat(outcome.clearedPairs()).isEqualTo(3);
		assertThat(outcome.turns()).isZero();
	}

	/** T-15：每次最多升一级（none → L1 → L2 → L3），且 L2 真的产摘要、L3 真的按 Turn 截。 */
	@Test
	void advancesOneLevelPerCompaction() {
		// 第一棒：none + 70.4% ⇒ L1
		ContextCompactor.Outcome first = this.compactor.compact(request(turns(6), 81000, List.of("{}")));
		assertThat(first.level()).isEqualTo(ContextTrigger.Level.L1);

		// 第二棒：L1 + 82.6% ⇒ L2（摘要 3 轮）
		seed("L1", 70000);
		ContextCompactor.Outcome second = compactorWith((system, user, runtime) -> "摘要正文").compact(requestWith(longTurns(), 70000,
				25000, Set.of()));
		assertThat(second.level()).isEqualTo(ContextTrigger.Level.L2);
		assertThat(second.turns()).isEqualTo(3);
		assertThat(second.messages()).as("L2 只落一条摘要（不再伪造 user+assistant 两件套）").hasSize(1 + 6 * 2);
		assertThat(second.messages().get(0).getText()).startsWith(RollingSummarizer.SUMMARY_PREFIX)
			.contains(RollingSummarizer.SUMMARY_OPEN)
			.contains(RollingSummarizer.SUMMARY_CLOSE);
		assertThat(ContextBreakdown.isCompacted(second.messages().get(0))).isTrue();

		// 第三棒：L2 + 95.7% ⇒ L3（按 Turn 丢最旧 3 轮）
		seed("L2", 70000);
		ContextCompactor.Outcome third = compactorWith((system, user, runtime) -> "摘要正文").compact(requestWith(longTurns(), 70000,
				40000, Set.of()));
		assertThat(third.level()).isEqualTo(ContextTrigger.Level.L3);
		assertThat(third.turns()).isEqualTo(3);
		assertThat(third.messages()).hasSize(6 * 2);
	}

	/** C-30：熔断期只留 L1（摘要坏了不许把对话拖下水）。 */
	@Test
	void circuitOpenKeepsL1Working() {
		long now = System.currentTimeMillis();
		seed("L1", 70000);
		ContextState opened = this.store.read(SESSION_ID)
			.withFuse(CompactionFuse.OPEN, 3, now + java.time.Duration.ofMinutes(10).toMillis());
		this.store.write(SESSION_ID, opened);

		ContextCompactor.Outcome outcome = compactorWith((system, user, runtime) -> {
			throw new IllegalStateException("不该被调用");
		}).compact(requestWith(turns(6), 70000, 25000, Set.of()));

		assertThat(outcome.changed()).isTrue();
		assertThat(outcome.level()).isEqualTo(ContextTrigger.Level.L1);
		assertThat(outcome.clearedPairs()).isPositive();
	}

	/** R1-09 M-1：续跑期 L2 压缩的每次摘要尝试都要回调（成功/失败都算“这一轮还在前进”）。 */
	@Test
	void summaryProgressHookFiresForEverySummaryAttempt() {
		seed("L1", 70000);
		java.util.concurrent.atomic.AtomicInteger progress = new java.util.concurrent.atomic.AtomicInteger();

		ContextCompactor.Outcome outcome = compactorWith((system, user, runtime) -> "摘要正文").compact(
				requestWith(longTurns(), 70000, 25000, Set.of()), progress::incrementAndGet);

		assertThat(outcome.level()).isEqualTo(ContextTrigger.Level.L2);
		assertThat(progress.get()).as("至少一次摘要完成就应刷新一次业务锚点").isPositive();
	}

	/** 摘要失败同样算一次“尝试结束”——否则失败也要等满 60s 才可能被外部判死。 */
	@Test
	void summaryProgressHookFiresEvenWhenTheSummaryFails() {
		seed("L1", 70000);
		java.util.concurrent.atomic.AtomicInteger progress = new java.util.concurrent.atomic.AtomicInteger();

		ContextCompactor.Outcome outcome = compactorWith((system, user, runtime) -> {
			throw new IllegalStateException("摘要失败");
		}).compact(requestWith(longTurns(), 70000, 25000, Set.of()), progress::incrementAndGet);

		assertThat(outcome.changed()).isFalse();
		assertThat(progress.get()).as("失败路径也必须回调（每次尝试结束都算活动）").isPositive();
	}

	/** P7：有未决 HITL 条目时（挂起期）比例到 L2 也只做 L1。 */
	@Test
	void suspendedSessionOnlyAllowsL1() {
		seed("L1", 70000);

		ContextCompactor.Outcome outcome = compactorWith((system, user, runtime) -> "摘要正文")
			.compact(requestWith(turns(6), 70000, 25000, Set.of("pending-x")));

		assertThat(outcome.changed()).isTrue();
		assertThat(outcome.level()).isEqualTo(ContextTrigger.Level.L1);
	}

	/**
	 * L2 的归档 / 台账 / 真值：归档与台账同一组 (level, before, after, turns, lostTokens)；
	 * 状态基线按 C13 补齐前缀（{@code after + system + tools}）—— 归账口径与基线口径不同面。
	 */
	@Test
	void l2ArchivesAndRecordsLedgerWithTurns() {
		seed("L1", 70000);

		ContextCompactor.Outcome outcome = compactorWith((system, user, runtime) -> "摘要正文").compact(requestWith(longTurns(), 70000,
				25000, Set.of()));

		assertThat(outcome.level()).isEqualTo(ContextTrigger.Level.L2);
		assertThat(outcome.lostTokens()).isEqualTo(outcome.before() - outcome.after());
		assertThat(outcome.lostTokens()).isPositive();
		assertThat(outcome.summaryChars()).isEqualTo("摘要正文".length());
		assertThat(this.store.archiveCount()).isEqualTo(1);
		Map<String, Object> event = this.store.contextLedger(SESSION_ID).get(0);
		assertThat(event).containsEntry("level", "L2")
			.containsEntry("turns", 3)
			.containsEntry("lostTokens", outcome.lostTokens())
			.containsEntry("summaryChars", outcome.summaryChars())
			.containsEntry("model", "deepseek-flash")
			.containsEntry("est", false);
		ContextState state = this.store.read(SESSION_ID);
		assertThat(state.level()).isEqualTo("L2");
		// C13：落库基线 = 压缩后历史估算 + system(3000) + tools(4000) —— 上游真值本就含这两项，
		// 只写历史估算会让下一轮 nextEst 永远少算前缀（同 c13CompactionBaselineIncludesSystemAndTools）。
		assertThat(state.usedTokens()).isEqualTo(outcome.after() + 3000 + 4000);
		assertThat(state.est()).isTrue();
	}

	/** C-24 编排面：摘要失败 ⇒ 历史逐字不变、无归档、fails+1（熔断的输入）。 */
	@Test
	void l2FailureKeepsHistoryAndCountsAFuseFailure() {
		seed("L1", 70000);
		List<Message> messages = longTurns();

		ContextCompactor.Outcome outcome = compactorWith((system, user, runtime) -> {
			throw new IllegalStateException("上游 500");
		}).compact(requestWith(messages, 70000, 25000, Set.of()));

		assertThat(outcome.changed()).isFalse();
		assertThat(outcome.reason()).isEqualTo(ContextCompactor.REASON_SUMMARY_FAILED);
		assertThat(outcome.messages()).isSameAs(messages);
		assertThat(this.store.archiveCount()).isZero();
		assertThat(this.store.read(SESSION_ID).fuseFails()).isEqualTo(1);
	}

	/**
	 * 摘要链最小统一：没有本轮 runtime ⇒ L2 按「摘要不可用」失败，<b>历史逐字不变</b>。
	 *
	 * <p>这条锁的是"不回落全局默认"：宁可这一轮不压，也不许拿另一家/另一个模型的全局默认去摘要。
	 */
	@Test
	void l2WithoutTurnRuntimeFailsWithoutReplacingHistory() {
		seed("L1", 70000);
		List<Message> messages = longTurns();
		ContextCompactor.Request request = new ContextCompactor.Request(SESSION_ID, RUN_ID, "deepseek-flash", 1L,
				messages, List.of("{}"), 70000, 25000, 0, 0, 3000, 4000, Set.of(), null);

		ContextCompactor.Outcome outcome = compactorWith((system, user, runtime) -> "不该被调用").compact(request);

		assertThat(outcome.changed()).isFalse();
		assertThat(outcome.reason()).isEqualTo(ContextCompactor.REASON_SUMMARY_UNAVAILABLE);
		assertThat(outcome.messages()).isSameAs(messages);
		assertThat(this.store.archiveCount()).isZero();
		assertThat(this.store.read(SESSION_ID).fuseFails()).isEqualTo(1);
	}

	/** 帧契约冻结：`context` 帧载荷键集不变，turns/lostTokens 从恒 0 改为如实值。 */
	@Test
	void contextFramePayloadKeepsItsFrozenKeySet() {
		seed("L1", 70000);

		ContextCompactor.Outcome outcome = compactorWith((system, user, runtime) -> "摘要正文").compact(requestWith(longTurns(), 70000,
				25000, Set.of()));

		Map<String, Object> payload = ContextCompactor.framePayload(outcome);

		assertThat(payload.keySet()).containsExactlyInAnyOrder("level", "before", "after", "turns", "lostTokens",
				"reason", "est", "savedTokens", "clearedPairs");
		assertThat(payload).containsEntry("level", "L2").containsEntry("turns", 3);
		assertThat((Integer) payload.get("lostTokens")).isPositive();
	}

}
