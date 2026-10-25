package com.example.configmgr.ai.context;

/**
 * 轮内（工具回环）上下文预算判定（工具轮内上下文预算批）。
 *
 * <h2>它补的是哪一段</h2>
 * 轮首压缩（{@link ContextCompactor}）只在"这一次请求发出前"判一次；一次带工具的轮次里，
 * 官方循环会连发 N 跳请求（每批工具结果落定后各发一次），而轮首的判定看不到后续批次。
 * 于是"这一轮 70% 起步、第 4 批工具结果后越过窗口"只能等上游 400 才知道 —— 而那一发
 * 已经注定失败、还白白多等一个 RTT。本类就是那一跳之前的<b>本地</b>判定：
 * 每批工具结束、下一次上游调用前，按"当前请求 + 本批新增结果"估算，给出四档动作。
 *
 * <h2>四档动作（与既有 L1/L3 阈值同源）</h2>
 * <ul>
 * <li>{@link Action#PROCEED}：未达 L1 线，原样发下一跳；</li>
 * <li>{@link Action#CLEAR_TOOL_RESULTS}：达 L1 线（{@code app.ai.context.l1-percent}，默认 70）。
 * <b>只清旧工具结果</b>（复用 {@link ToolResultClearing}：不删消息、不动配对骨架、未决 HITL
 * 与 pin 窗口受保护）；轮内<b>不做</b> L2/L3 摘要调用（摘要是一条旁路模型请求，在工具循环里
 * 追加它会把"一段失败拖垮整轮"的风险带进来，且改写前缀的代价在轮内无法用下一次真值验证）；</li>
 * <li>{@link Action#WRAP_UP}：达收尾线（{@code app.ai.context.l3-percent}，默认 95）。
 * 追加一条收尾指令让模型用已有信息作答，并<b>武装</b>（armed）本轮：此后模型再请求工具时
 * <b>一律不执行</b>（副作用到此为止），直到本轮结束；</li>
 * <li>{@link Action#OVERFLOW}：估算已达<b>硬上限</b>（{@code nextEst >= limitTokens}）——
 * 这一发请求的上游 400 是可预见的，不发；调用方先落定已执行的台账/快照，再报
 * {@code CONTEXT_OVERFLOW}。</li>
 * </ul>
 *
 * <h2>真值优先（与 {@link ContextTrigger} 同一口径）</h2>
 * 本轮最近一次上游响应带了有效 {@code usage} 时，用
 * {@code usedPrompt + lastCompletion + 本批新增}；没有（或只有 0/0/0 空帧）才退化为
 * "system + tools + 历史"的整包估算。真值优先的理由与计量面一致：估算只负责兜底，
 * 真值是账单。
 *
 * <h2>纯函数</h2>
 * 本类不读配置、不写状态、不发帧：分母与阈值全部由调用方注入（{@link ContextLimitResolver}
 * 与 {@code AiProperties.Context} 的唯一取数点仍在调用方），因此判定可单测、可重放。
 * 同一输入永远得到同一输出（幂等）—— "清一次还是两次"由调用方按动作语义收敛。
 */
public final class TurnBudgetPlan {

	/** 判定动作（线上/日志取 {@link #name()}）。 */
	public enum Action {

		/** 未达阈值：原样发下一跳。 */
		PROCEED,

		/** 达 L1 线：只清旧工具结果（本地动作，无模型调用）。 */
		CLEAR_TOOL_RESULTS,

		/** 达收尾线：追加收尾指令 + 武装本轮（此后工具不再执行）。 */
		WRAP_UP,

		/** 估算已达硬上限：先落定已执行记录，再以 {@code CONTEXT_OVERFLOW} 收尾，不发这一发请求。 */
		OVERFLOW

	}

	/** 跳过原因：上限未知（降级态，不做比例判定）。 */
	public static final String REASON_LIMIT_UNKNOWN = "turn_limit_unknown";

	/** 未达 L1。 */
	public static final String REASON_UNDER_THRESHOLD = "turn_under_threshold";

	/** 达 L1：只清旧工具结果。 */
	public static final String REASON_L1 = "turn_l1_tool_result_clearing";

	/** 达收尾线：让模型收尾并停止新副作用。 */
	public static final String REASON_WRAP_UP = "turn_wrap_up";

	/** 估算越过硬上限。 */
	public static final String REASON_OVERFLOW = "turn_overflow";

	/** 收尾指令被连续无视（armed 后仍反复请求工具），按硬停处理。 */
	public static final String REASON_WRAP_UP_IGNORED = "turn_wrap_up_ignored";

	/**
	 * 武装之后模型仍连续请求工具、被拦下的批次数上限。
	 *
	 * <p>收尾指令是"请模型配合"的软约束：模型可以不理。若只靠它，一个不配合的模型可以在
	 * 同一轮里把"请求工具 → 不执行 → 再请求"循环到 300s 看门狗才停。故给一个硬上限：
	 * 第 {@value} 批仍未收尾就按 {@link Action#OVERFLOW} 处理（拒绝再发注定失败/无产出的请求，
	 * 先落定再报错），让失败是确定性的、可解释的，而不是一次看门狗超时。
	 *
	 * <p><b>与本批估算高低无关</b>：清过结果之后估算可能又掉回阈值以下，但"armed 了还在要工具"
	 * 这件事本身就说明模型没有在收尾 —— 上界只数批次数，不看占比。
	 */
	public static final int MAX_ARMED_BATCHES = 2;

	/**
	 * 一次判定的输入。
	 *
	 * @param limitTokens            模型上下文上限（{@code ContextLimitResolver} 的取数点；{@code <=0} = 未知）
	 * @param reserveTokens          预留缓冲（{@code app.ai.context.reserve-tokens}）
	 * @param l1Percent              L1（工具结果清除）触发线（{@code app.ai.context.l1-percent}）
	 * @param wrapUpPercent          收尾线（{@code app.ai.context.l3-percent}：轮内没有摘要能力，
	 *                               95 档只能收尾）
	 * @param usageMeasured          本跳上游是否给出有效 {@code usage}
	 * @param usedPromptTokens       真值 {@code usage.promptTokens}（{@code usageMeasured=false} 时不参与）
	 * @param lastCompletionTokens   真值 {@code usage.completionTokens}（下一跳还会带上本跳的输出）
	 * @param estimateTokens         估算口径的整包（system + tools + 历史含本批）
	 * @param deltaSinceUsageTokens  真值口径下"发出上一跳之后新增"的部分（本批工具结果 + 追加指令）
	 * @param armed                  本轮是否已进入收尾（armed）
	 * @param armedBatches           本轮 armed 之后已被拦下的工具批次数
	 */
	public record Request(int limitTokens, int reserveTokens, int l1Percent, int wrapUpPercent,
			boolean usageMeasured, int usedPromptTokens, int lastCompletionTokens, int estimateTokens,
			int deltaSinceUsageTokens, boolean armed, int armedBatches) {

		/**
		 * 下一跳请求的 prompt 估算（判据值）。
		 *
		 * <p>真值优先，但<b>要求 prompt 真值本身可用</b>：只有 completion 有效（prompt=0）的帧
		 * 不能当"空请求"，否则会把越窗判成未触发 —— 这种帧退化为估算。
		 */
		public int nextEstimate() {
			if (this.usageMeasured && this.usedPromptTokens > 0) {
				return Math.max(0, this.usedPromptTokens) + Math.max(0, this.lastCompletionTokens)
						+ Math.max(0, this.deltaSinceUsageTokens);
			}
			return Math.max(0, this.estimateTokens);
		}

	}

	/**
	 * 一次判定结果。
	 *
	 * @param action      动作
	 * @param ratio       {@code nextEst / (limit − reserve)}（{@code limit <= 0} 时恒 0）
	 * @param nextEst     下一跳请求的估算（判据值）
	 * @param limitTokens 分母（{@code <=0} = 未知）
	 * @param reserveTokens 预留缓冲
	 * @param degraded    上限未知（调用方据此不做比例判定、不发百分比）
	 * @param reason      原因（见本类常量）
	 */
	public record Decision(Action action, double ratio, int nextEst, int limitTokens, int reserveTokens,
			boolean degraded, String reason) {

		public boolean triggered() {
			return this.action != Action.PROCEED;
		}

	}

	private TurnBudgetPlan() {
	}

	/** 判定（纯函数；{@code request == null} 按"上限未知"降级）。 */
	public static Decision plan(Request request) {
		if (request == null) {
			return new Decision(Action.PROCEED, 0d, 0, 0, 0, true, REASON_LIMIT_UNKNOWN);
		}
		int limit = Math.max(0, request.limitTokens());
		int reserve = Math.max(0, request.reserveTokens());
		int nextEst = request.nextEstimate();
		if (limit <= 0) {
			return new Decision(Action.PROCEED, 0d, nextEst, 0, reserve, true, REASON_LIMIT_UNKNOWN);
		}
		// 硬上限：prompt 估算已经吃掉整个窗口 —— 这一发的 400 是可预见的（连输出空间都没有）
		if (nextEst >= limit) {
			return new Decision(Action.OVERFLOW, ratioOf(nextEst, limit, reserve), nextEst, limit, reserve, false,
					REASON_OVERFLOW);
		}
		// armed 后的硬上界：收尾指令被连续无视 ⇒ 停止再发（与本批估算高低无关 ——
		// 否则"模型反复请求工具、每批都跳过"会靠低估算一直拖到看门狗）
		if (request.armed() && request.armedBatches() >= MAX_ARMED_BATCHES) {
			return new Decision(Action.OVERFLOW, ratioOf(nextEst, limit, reserve), nextEst, limit, reserve, false,
					REASON_WRAP_UP_IGNORED);
		}
		double ratio = ratioOf(nextEst, limit, reserve);
		int percent = request.wrapUpPercent() <= 0 ? 95 : request.wrapUpPercent();
		if (ratio * 100d >= percent) {
			return new Decision(Action.WRAP_UP, ratio, nextEst, limit, reserve, false, REASON_WRAP_UP);
		}
		int l1 = request.l1Percent() <= 0 ? 70 : request.l1Percent();
		if (ratio * 100d >= l1) {
			return new Decision(Action.CLEAR_TOOL_RESULTS, ratio, nextEst, limit, reserve, false, REASON_L1);
		}
		return new Decision(Action.PROCEED, ratio, nextEst, limit, reserve, false, REASON_UNDER_THRESHOLD);
	}

	private static double ratioOf(int nextEst, int limit, int reserve) {
		long denominator = Math.max(1L, (long) limit - reserve);
		return (double) nextEst / denominator;
	}

}
