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
				usedTokens, 0, 0, 0, 3000, 4000, Set.of());
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
		assertThat(state.usedTokens()).isEqualTo(outcome.after());
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

}
