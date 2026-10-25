package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.AiTrace;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记忆窗口（记忆窗口批）：<b>token 预算为主 + 条数兜底 + 旧工具结果优先清除</b>。
 *
 * <h2>它补的是哪一段</h2>
 * 此前窗口只有条数一个维度（{@code MessageWindowChatMemory#maxMessages=40}）。条数在
 * "短消息为主"的会话里够用，在"长工具结果为主"的会话里却是失真的：40 条可以在一次
 * {@code read_sheet_summary} 之后就吃掉 3 万 token，也可以在纯寒暄里只占 2 千 token ——
 * 同一个数字，两种体量。本类把窗口的主判据换成 <b>token 预算</b>，条数退化为兜底（上限 + 下限）。
 *
 * <h2>与压缩谱系（L1/L2/L3）的分工</h2>
 * 两者都动历史，但判据与时机不同，<b>不是同一件事的两种写法</b>：
 * <ul>
 * <li>{@link ContextCompactor} 判的是"<b>下一次请求会不会撞到模型上限</b>"（对
 * {@code limit − reserve} 取比，70/80/95%），是所有上下文（含 system、tools）的<b>安全网</b>；</li>
 * <li>本类判的是"<b>从记忆里读出来的历史本身该有多长</b>"（绝对 token 预算，与模型上限无关），
 * 目的是让长会话不管跑在 32K 还是 128K 的模型上，历史体量都稳定、可预期；</li>
 * <li>清除机制<b>复用</b> L1 的 {@link ToolResultClearing}（同一个占位符、同一个幂等判据、
 * 同一套 HITL 保护）—— 两套文案会让"已清除"识别失效、同一结果被清两次并重复记账。</li>
 * </ul>
 * 顺序上本类先跑（读入即在预算内），压缩谱系后跑（按占比兜底）。二者都不做摘要
 * （摘要留给 L2；本批不引入）。
 *
 * <h2>三条硬约束</h2>
 * <ol>
 * <li><b>先清后删</b>：越预算时先替换最旧的工具结果（不删消息、不动配对骨架），
 * 够用就<b>一条消息都不删</b>；</li>
 * <li><b>按 turn 整轮淘汰</b>：切口永远落在 {@code UserMessage} 上，{@code tool_call} ↔
 * {@code tool_result} 配对不得被拆散（上游 400 的经典成因）；最后一轮一刀不剪；</li>
 * <li><b>条数下限不击穿</b>：预算再紧也不淘汰到 {@code min-messages} 以下 —— 预算把窗口压到
 * "只剩最后一轮"是失忆，不是省 token；淘汰到下限仍越线就<b>如实上报</b>
 * （{@link Plan#stillOverBudget()}），交给压缩谱系与上游，绝不静默继续砍。</li>
 * </ol>
 *
 * <h2>纯函数</h2>
 * 本类不写任何存储：{@link #plan} 只算"该长什么样"，写回由调用方决定（首轮写回 {@code ChatMemory}
 * 是必须的 —— 官方 advisor 从记忆里读历史，不写回就等于"快照是短的、模型看到的是长的"）。
 * 同一个输入永远得到同一个输出，故窗口淘汰<b>不落库、不归档</b>（区别于压缩：压缩改写内容，
 * 必须归档原文）。
 */
public final class MemoryWindow {

	/** 未越预算（含"预算与条数上限都关闭"）。 */
	public static final String REASON_UNDER_BUDGET = "under_budget";

	/** 越预算/越条数，但没有可动的东西（首段即最后一轮 / 全是受保护项）。 */
	public static final String REASON_NOTHING_TO_TRIM = "nothing_to_trim";

	/** 只清了工具结果（未删任何消息）。 */
	public static final String REASON_TOOL_RESULT_CLEARING = "tool_result_clearing";

	/** 按 turn 整轮淘汰了最旧的若干轮（可能同时清了工具结果）。 */
	public static final String REASON_TURN_TRIM = "turn_trim";

	/**
	 * 窗口预算。
	 *
	 * @param tokenBudget    历史 token 预算（{@code 0} = 关闭，只按条数；负值启动期即失败，见
	 *                       {@code AiProperties#verifyMemory}）
	 * @param minMessages    条数下限（预算淘汰不得击穿它）
	 * @param maxMessages    条数上限（{@code <=0} = 不限；与官方窗口同一个数）
	 * @param keepToolResults 最近多少个工具结果不清理（与 {@code app.ai.context.keep-tool-pairs}
	 *                       同源：同一套 pin 窗口，避免"窗口清一半、L1 清一半"的两套口径）
	 */
	public record Budget(int tokenBudget, int minMessages, int maxMessages, int keepToolResults) {

		/** 从配置装配（唯一取数点：{@code app.ai.memory.*} + {@code app.ai.context.keep-tool-pairs}）。 */
		public static Budget from(AiProperties properties) {
			AiProperties.Memory memory = properties.getMemory();
			AiProperties.Context context = properties.getContext();
			int keep = context == null ? 5 : context.getKeepToolPairs();
			return new Budget(memory.getTokenBudget(), memory.getMinMessages(), memory.getMaxMessages(), keep);
		}

	}

	/**
	 * 一次窗口规划。
	 *
	 * @param messages           规划后的历史（{@link #changed()} 为 false 时与入参同引用）
	 * @param beforeTokens       规划前历史估算
	 * @param afterTokens        规划后历史估算
	 * @param beforeCount        规划前消息条数
	 * @param afterCount         规划后消息条数
	 * @param clearedToolResults 被清除的工具结果条数（占位符替换，条数不变）
	 * @param droppedTurns       被整轮淘汰的轮数
	 * @param stillOverBudget    淘汰到下限后仍越预算（如实上报，供压缩谱系接手的观测面）
	 * @param reason             执行/跳过原因（见本类常量）
	 */
	public record Plan(List<Message> messages, int beforeTokens, int afterTokens, int beforeCount, int afterCount,
			int clearedToolResults, int droppedTurns, boolean stillOverBudget, String reason) {

		public boolean changed() {
			return this.clearedToolResults > 0 || this.droppedTurns > 0;
		}

		/** 对外载荷（续跑响应体 / 事件同一份键）。 */
		public Map<String, Object> summary() {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("before", this.beforeCount);
			body.put("after", this.afterCount);
			body.put("tokensBefore", this.beforeTokens);
			body.put("tokensAfter", this.afterTokens);
			body.put("clearedToolResults", this.clearedToolResults);
			body.put("droppedTurns", this.droppedTurns);
			body.put("stillOverBudget", this.stillOverBudget);
			body.put("reason", this.reason);
			return body;
		}

	}

	private MemoryWindow() {
	}

	/**
	 * 规划一次窗口裁剪（<b>纯函数</b>：不写任何存储，调用方负责写回/记账）。
	 *
	 * @param messages             当前历史（原消息对象不被修改）
	 * @param budget               窗口预算（null = 缺省配置，防御性）
	 * @param protectedToolCallIds 未决 HITL 条目的 toolCallId（工具结果永不清除；可为 null）
	 */
	public static Plan plan(List<Message> messages, Budget budget, Set<String> protectedToolCallIds) {
		List<Message> original = messages == null ? List.of() : messages;
		int beforeTokens = ContextEstimator.estimateMessages(original);
		int beforeCount = original.size();
		if (original.isEmpty()) {
			return new Plan(original, 0, 0, 0, 0, 0, 0, false, REASON_NOTHING_TO_TRIM);
		}
		Budget config = budget == null ? new Budget(0, 1, 0, 5) : budget;
		boolean overBudget = config.tokenBudget() > 0 && beforeTokens > config.tokenBudget();
		boolean overCount = config.maxMessages() > 0 && beforeCount > config.maxMessages();
		if (!overBudget && !overCount) {
			return new Plan(original, beforeTokens, beforeTokens, beforeCount, beforeCount, 0, 0, false,
					REASON_UNDER_BUDGET);
		}
		// 续跑保 system 批：SYSTEM 不是可裁剪历史 —— 只对系统段之后的 body 做清除/淘汰，
		// 最后原样复位到最前。预算仍按整份历史计（SYSTEM 的 token 不能被当成"可以省掉的量"）。
		List<Message> system = SystemMessages.leading(original);
		List<Message> body = SystemMessages.body(original);

		// ① 先清旧工具结果（越预算才动它：没越线时清掉等于白丢信息）
		List<Message> current = body;
		int cleared = 0;
		if (overBudget) {
			ToolResultClearing.Plan clear = ToolResultClearing.plan(body, config.keepToolResults(),
					protectedToolCallIds);
			if (clear.changed()) {
				current = clear.messages();
				cleared = clear.cleared().size();
			}
		}

		// ② 仍越预算 ⇒ 按 turn 整轮淘汰最旧（不击穿条数下限、不动最后一轮）
		int dropped = 0;
		int tokens = ContextEstimator.estimateMessages(current);
		if (config.tokenBudget() > 0 && tokens > config.tokenBudget()) {
			Drop drop = dropOldestTurns(current, ContextEstimator::estimateMessages,
					measure -> measure > config.tokenBudget(), Math.max(1, config.minMessages()));
			current = drop.messages();
			dropped += drop.turns();
			tokens = ContextEstimator.estimateMessages(current);
		}

		// ③ 条数上限（官方窗口的兜底仍在）：同样按 turn 整轮淘汰，不拆配对
		if (config.maxMessages() > 0 && current.size() > config.maxMessages()) {
			Drop drop = dropOldestTurns(current, List::size, measure -> measure > config.maxMessages(), 1);
			current = drop.messages();
			dropped += drop.turns();
			tokens = ContextEstimator.estimateMessages(current);
		}

		List<Message> planned = SystemMessages.reattach(system, current);
		boolean stillOver = config.tokenBudget() > 0 && ContextEstimator.estimateMessages(planned) > config.tokenBudget();
		String reason = dropped > 0 ? REASON_TURN_TRIM
				: (cleared > 0 ? REASON_TOOL_RESULT_CLEARING : REASON_NOTHING_TO_TRIM);
		return new Plan(planned, beforeTokens, ContextEstimator.estimateMessages(planned), beforeCount,
				planned.size(), cleared, dropped, stillOver, reason);
	}

	/**
	 * 记一行 {@code event=memory_window}（只在真改动时发）。
	 *
	 * <p>与 {@code event=compaction} 同族但不同事件名：一个回答"读进来的历史为什么变短了"，
	 * 一个回答"模型上下文占比到了哪一级"。合成一条会让排障时把"窗口本来就这么长"误读成
	 * "压缩把历史吃掉了"。字段全是元数据（计数/原因/预算），不含任何正文。
	 */
	public static void emit(String runId, String sessionId, Plan plan, Budget budget) {
		if (plan == null || !plan.changed()) {
			return;
		}
		int tokensBudget = budget == null ? 0 : budget.tokenBudget();
		AiTrace.eventOn(runId, sessionId, "memory_window", "reason", plan.reason(), "before", plan.beforeCount(),
				"after", plan.afterCount(), "tokensBefore", plan.beforeTokens(), "tokensAfter", plan.afterTokens(),
				"budget", tokensBudget, "droppedTurns", plan.droppedTurns(), "clearedToolResults",
				plan.clearedToolResults(), "stillOver", plan.stillOverBudget());
	}

	/**
	 * 从前往后整轮淘汰，直到"淘汰后已不再越线"或"再淘汰会击穿条数下限"或"只剩最后一轮"。
	 *
	 * <p>判据总是对<b>淘汰后的列表</b>重新度量（而不是从总量里做减法）：token 与条数是两个口径，
	 * 减法式在"同一条消息被两次计量"时会漂移；重算一次的量级是窗口大小，代价可忽略。
	 *
	 * @param measure 度量函数（token 或条数）
	 * @param stillOver 该度量的越线判据
	 */
	private static Drop dropOldestTurns(List<Message> messages, java.util.function.ToIntFunction<List<Message>> measure,
			java.util.function.IntPredicate stillOver, int floorMessages) {
		List<ConversationTurns.Segment> segments = ConversationTurns.split(messages);
		if (segments.size() <= 1) {
			return new Drop(messages, 0);
		}
		int cut = 0;
		while (cut < segments.size() - 1) {
			ConversationTurns.Segment next = segments.get(cut);
			int kept = messages.size() - next.startIndex() - next.size();
			if (kept < floorMessages) {
				break;
			}
			cut++;
			int from = segments.get(cut).startIndex();
			if (!stillOver.test(measure.applyAsInt(messages.subList(from, messages.size())))) {
				break;
			}
		}
		if (cut == 0) {
			return new Drop(messages, 0);
		}
		int from = segments.get(cut).startIndex();
		return new Drop(new ArrayList<>(messages.subList(from, messages.size())), cut);
	}

	private record Drop(List<Message> messages, int turns) {
	}

}
