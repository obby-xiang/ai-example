package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.AiTrace;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记忆窗口（记忆窗口批）：<b>token 预算为主 + 条数兜底</b>。
 *
 * <h2>它补的是哪一段</h2>
 * 此前窗口只有条数一个维度（{@code MessageWindowChatMemory#maxMessages=40}）。条数在
 * "短消息为主"的会话里够用，在"长工具结果为主"的会话里却是失真的：40 条可以在一次
 * {@code read_sheet_summary} 之后就吃掉 3 万 token，也可以在纯寒暄里只占 2 千 token ——
 * 同一个数字，两种体量。本类把窗口的主判据换成 <b>token 预算</b>，条数退化为兜底（上限 + 下限）。
 *
 * <h2>与压缩谱系（L1/L2/L3）的分工（B1：工具结果清理只有一个 mutator）</h2>
 * 两者都动历史，但判据与时机不同，<b>不是同一件事的两种写法</b>：
 * <ul>
 * <li>{@link ContextCompactor} 判的是"<b>下一次请求会不会撞到模型上限</b>"（对
 * {@code limit − reserve} 取比，70/80/95%），是所有上下文（含 system、tools）的<b>安全网</b>；
 * 轮首/续跑的 L1 工具结果清除<b>只由它执行</b>（含 pin 窗口、HITL、活跃 run 保护与记账/归档；
 * 轮内工具回环有 {@code SpToolCallingManager} 自己的入口与同一套清除函数）；</li>
 * <li>本类判的是"<b>从记忆里读出来的历史本身该有多长</b>"（绝对 token 预算，与模型上限无关），
 * 只做<b>按 turn 整轮淘汰</b>，不改写任何消息内容；越预算且淘汰到下限仍不够时只
 * {@link Plan#stillOverBudget() 如实上报}，由调用方把 {@code l1Due} 交给 {@link ContextCompactor}
 * 做一次 L1 —— 同一份工具结果不会被两个 mutator 各清一次、各记一次账；</li>
 * </ul>
 * 顺序上本类先跑（读入即在预算内），压缩谱系后跑（按占比兜底）。二者都不做摘要
 * （摘要留给 L2）。
 *
 * <h2>三条硬约束</h2>
 * <ol>
 * <li><b>按 turn 整轮淘汰</b>：切口永远落在 {@code UserMessage} 上，{@code tool_call} ↔
 * {@code tool_result} 配对不得被拆散（上游 400 的经典成因）；最后一轮一刀不剪；</li>
 * <li><b>条数下限不击穿</b>：预算再紧也不淘汰到 {@code min-messages} 以下 —— 预算把窗口压到
 * "只剩最后一轮"是失忆，不是省 token；淘汰到下限仍越线就<b>如实上报</b>
 * （{@link Plan#stillOverBudget()}），交给压缩谱系与上游，绝不静默继续砍；</li>
 * <li><b>不动消息内容</b>：本类不替换文本（工具结果清除归 {@link ContextCompactor} 与轮内预算的
 * 各自入口），因此窗口结果始终是"原文或已清除的原文"，不存在第三种形态。</li>
 * </ol>
 *
 * <p><b>预算口径 = 历史口径</b>：{@link ContextEstimator#estimateMessages} 跳过 {@code SYSTEM}
 * （人设 + 工作区快照在装配点单列），所以本类的 token 预算也只量 {@code SYSTEM} 之后的 body；
 * 不把不可裁剪的 SYSTEM 计进预算，否则固定前缀会让窗口永久呈现 over-budget。
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

	/** 按 turn 整轮淘汰了最旧的若干轮。 */
	public static final String REASON_TURN_TRIM = "turn_trim";

	/**
	 * 窗口预算。
	 *
	 * @param tokenBudget    历史 token 预算（{@code 0} = 关闭，只按条数；负值启动期即失败，见
	 *                       {@code AiProperties#verifyMemory}）
	 * @param minMessages    条数下限（预算淘汰不得击穿它）
	 * @param maxMessages    条数上限（{@code <=0} = 不限；与官方窗口同一个数）
	 * @param keepToolResults <b>本类已不再使用</b>（B1：工具结果清理的唯一 mutator 是
	 *                        {@link ContextCompactor}）；字段保留只为装配签名稳定，pin 窗口由
	 *                        {@code app.ai.context.keep-tool-pairs} 在 L1 处读取
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
	 * @param droppedTurns       被整轮淘汰的轮数
	 * @param stillOverBudget    淘汰到下限后仍越预算（调用方据此给 {@link ContextCompactor} 传
	 *                           {@code l1Due}；L1 执行与记账只发生在那一个 mutator 里）
	 * @param reason             执行/跳过原因（见本类常量）
	 */
	public record Plan(List<Message> messages, int beforeTokens, int afterTokens, int beforeCount, int afterCount,
			int droppedTurns, boolean stillOverBudget, String reason) {

		public boolean changed() {
			return this.droppedTurns > 0;
		}

		/** 对外载荷（续跑响应体 / 事件同一份键）。 */
		public Map<String, Object> summary() {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("before", this.beforeCount);
			body.put("after", this.afterCount);
			body.put("tokensBefore", this.beforeTokens);
			body.put("tokensAfter", this.afterTokens);
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
	 * @param protectedToolCallIds <b>已不由本类消费</b>（B1：HITL 保护在 L1 的唯一 mutator 处执行）；
	 *                             保留参数只为调用点签名稳定
	 */
	public static Plan plan(List<Message> messages, Budget budget, Set<String> protectedToolCallIds) {
		return plan(messages, budget, protectedToolCallIds, Set.of());
	}

	public static Plan plan(List<Message> messages, Budget budget, Set<String> protectedToolCallIds,
			Set<String> protectedRunIds) {
		List<Message> original = ObjectUtils.defaultIfNull(messages, List.of());
		int beforeTokens = ContextEstimator.estimateMessages(original);
		int beforeCount = original.size();
		if (original.isEmpty()) {
			return new Plan(original, 0, 0, 0, 0, 0, false, REASON_NOTHING_TO_TRIM);
		}
		Budget config = ObjectUtils.getIfNull(budget, () -> new Budget(0, 1, 0, 5));
		boolean overBudget = config.tokenBudget() > 0 && beforeTokens > config.tokenBudget();
		boolean overCount = config.maxMessages() > 0 && beforeCount > config.maxMessages();
		if (!overBudget && !overCount) {
			return new Plan(original, beforeTokens, beforeTokens, beforeCount, beforeCount, 0, false,
					REASON_UNDER_BUDGET);
		}
		// 续跑保 system 批：SYSTEM 不是可裁剪历史 —— 只对系统段之后的 body 做淘汰，
		// 最后原样复位到最前。预算只量 body（ContextEstimator#estimateMessages 跳过 SYSTEM）。
		List<Message> system = SystemMessages.leading(original);
		List<Message> body = SystemMessages.body(original);

		// B1：本类不再替换工具结果 —— 越预算只做整轮淘汰；淘汰到下限仍越线时如实上报，
		// 由调用方把 l1Due 交给 ContextCompactor（工具结果清理的唯一 mutator）。
		List<Message> current = body;

		// ① 按 turn 整轮淘汰最旧（不击穿条数下限、不动最后一轮）
		int dropped = 0;
		int tokens = ContextEstimator.estimateMessages(current);
		if (config.tokenBudget() > 0 && tokens > config.tokenBudget()) {
			Drop drop = dropOldestTurns(current, ContextEstimator::estimateMessages,
					measure -> measure > config.tokenBudget(), Math.max(1, config.minMessages()), protectedRunIds);
			current = drop.messages();
			dropped += drop.turns();
			tokens = ContextEstimator.estimateMessages(current);
		}

		// ② 条数上限（官方窗口的兜底仍在）：同样按 turn 整轮淘汰，不拆配对
		if (config.maxMessages() > 0 && current.size() > config.maxMessages()) {
			Drop drop = dropOldestTurns(current, List::size, measure -> measure > config.maxMessages(), 1,
					protectedRunIds);
			current = drop.messages();
			dropped += drop.turns();
			tokens = ContextEstimator.estimateMessages(current);
		}

		List<Message> planned = SystemMessages.reattach(system, current);
		boolean stillOver = config.tokenBudget() > 0 && ContextEstimator.estimateMessages(planned) > config.tokenBudget();
		String reason = dropped > 0 ? REASON_TURN_TRIM : REASON_NOTHING_TO_TRIM;
		return new Plan(planned, beforeTokens, ContextEstimator.estimateMessages(planned), beforeCount,
				planned.size(), dropped, stillOver, reason);
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
				"budget", tokensBudget, "droppedTurns", plan.droppedTurns(), "stillOver", plan.stillOverBudget());
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
			java.util.function.IntPredicate stillOver, int floorMessages, Set<String> protectedRunIds) {
		List<ConversationTurns.Segment> segments = ConversationTurns.split(messages);
		if (segments.size() <= 1) {
			return new Drop(messages, 0);
		}
		Set<String> activeRuns = ObjectUtils.defaultIfNull(protectedRunIds, Set.of());
		int cut = 0;
		while (cut < segments.size() - 1) {
			ConversationTurns.Segment next = segments.get(cut);
			if (ConversationTurns.containsRun(messages, next, activeRuns)) {
				break;
			}
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
