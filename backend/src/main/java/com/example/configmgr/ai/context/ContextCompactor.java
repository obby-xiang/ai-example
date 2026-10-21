package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 压缩统一入口（上下文统计与压缩专项批①；设计稿 §3.5 / §6.4）。
 *
 * <p>
 * <b>一处入口、两个调用点同源</b>：首轮（{@code ResilientChatService#chat} 取记忆之后）与续跑
 * （{@code ResumeService#resume} 重建历史之后）都必须经本类 —— 否则首轮/续跑的口径会分叉，
 * 表现为"同一会话换个入口进来就不压了"。
 *
 * <h2>本批范围（批① = 计量 + 展示 + L1）</h2>
 * 只实现 <b>L1 工具结果清除</b>。L2（按 Turn 滑窗 + 递归摘要）与 L3（硬截断兜底）属于批③，
 * 本类<b>刻意不做</b>：
 * <ul>
 * <li>{@link #MAX_LEVEL} 封顶 L1 —— 占比到 80%/95% 时也只升到 L1（"每次最多升一级"的第一格），
 * 不允许出现"实现没做、帧里却报 L2"的假账；</li>
 * <li>熔断（{@code fails/circuit}）由批③ 写入；本类只透传状态里的 {@code circuit}，
 * 失败路径保证<b>不改变历史</b>（本轮可能偏大，但不失真）。</li>
 * </ul>
 *
 * <h2>原子性（失败即回滚）</h2>
 * 本类内部的固定顺序：<b>先归档压缩前原文</b> → 再写 {@code ai:ctx:} 真值/台账 → 最后返回新消息列表。
 * 任一步抛错 ⇒ 记 ERROR、返回<b>原消息列表</b>、本轮照常发请求（对齐调研 Q3"绝不静默截断"：
 * 宁可这一轮偏大，也不让"模型看到的"和"留下的"不一致）。
 *
 * <p>
 * <b>与设计稿 §3.5 的差异（如实点名）</b>：设计稿写的是 {@code 归档 → RunStore.save → chatMemory.clear+add}
 * （快照在前、记忆在后）。实际落地是<b>「记忆 → 快照」</b>（首轮路径），理由是失败后的残留组合：
 * 记忆先写、快照后建 ⇒ 若 {@code store.create} 失败，残留是"记忆已压、快照未建"（旧快照根本不存在，
 * 下一步重试会重新压一次，幂等）；反序则残留"快照已压、记忆未压"—— 这一轮模型看到压缩后的历史、
 * 记忆窗口却还是原文，续跑与下一轮各自再压一次，两次的 pin 窗口不同 ⇒ 同一会话两条历史线
 * （SM-01 同族分叉）。设计稿的措辞未改（那是设计原文），差异在此登记。
 */
@Slf4j
public final class ContextCompactor {

	/** 批① 实现的最高级别（批③ 把它升到 {@link ContextTrigger.Level#L3}）。 */
	public static final ContextTrigger.Level MAX_LEVEL = ContextTrigger.Level.L1;

	/** 跳过原因：占比未达 L1。 */
	public static final String REASON_UNDER_THRESHOLD = "under_threshold";

	/** 跳过原因：滞回锁未解锁（压缩完成但真值仍 ≥ 60%）。 */
	public static final String REASON_HYSTERESIS_LOCKED = "hysteresis_locked";

	/** 跳过原因：上限未知（降级态，不做比例判定）。 */
	public static final String REASON_LIMIT_UNKNOWN = "limit_unknown";

	/** 跳过原因：没有可清的工具结果（全部在 pin 窗口内/已清/受保护）。 */
	public static final String REASON_NOTHING_TO_CLEAR = "nothing_to_clear";

	/** 跳过原因：归档或记账失败（历史保持原样）。 */
	public static final String REASON_FAILED = "compaction_failed";

	/**
	 * 一次压缩请求。
	 *
	 * @param sessionId               会话 id（真值/台账/归档键的一维）
	 * @param runId                   本轮 runId（台账回取路径）
	 * @param model                   本轮模型名（解析分母）
	 * @param providerId              本轮 provider id（解析分母；null = 未指定）
	 * @param messages                当前历史（原对象不被修改）
	 * @param messageJsonBefore       压缩前原文（归档用；为空则不归档）
	 * @param usedTokens              上次成功请求的真值（无则 0）
	 * @param completionTokens        上次成功请求的输出（nextEst 的一项）
	 * @param inputTokens             本轮输入估算
	 * @param toolResultsSinceMeasure 上次真值之后新增的工具结果估算
	 * @param systemTokens            人设 + 工作区快照估算
	 * @param toolsTokens             本轮披露 schema 估算
	 * @param protectedToolCallIds    未决 HITL 条目（永不清除）
	 */
	public record Request(String sessionId, String runId, String model, Long providerId, List<Message> messages,
			List<String> messageJsonBefore, int usedTokens, int completionTokens, int inputTokens,
			int toolResultsSinceMeasure, int systemTokens, int toolsTokens, Set<String> protectedToolCallIds) {
	}

	/**
	 * 一次压缩结局。
	 *
	 * @param level            实际应用的级别（{@code none}/{@code L1}）
	 * @param messages         压缩后（未压缩时 = 原始列表）
	 * @param before           压缩前历史估算
	 * @param after            压缩后历史估算
	 * @param savedTokens      省下的估算 token
	 * @param clearedPairs     被清除的工具结果条数
	 * @param clearedToolCallIds 被清除的调用 id（台账/日志可追溯）
	 * @param reason           跳过/执行原因
	 * @param limitTokens      分母（0 = 未知）
	 * @param usedTokens       压缩后基线（{@code est=true} 时为估算）
	 * @param est              基线是否估算
	 * @param circuit          熔断态（批③ 写入，批① 只透传）
	 * @param ratio            触发占比
	 * @param nextEst          触发用预估
	 */
	public record Outcome(ContextTrigger.Level level, List<Message> messages, int before, int after, int savedTokens,
			int clearedPairs, List<String> clearedToolCallIds, String reason, int limitTokens, int usedTokens,
			boolean est, String circuit, double ratio, int nextEst) {

		public boolean changed() {
			return this.level != ContextTrigger.Level.NONE;
		}

		public String levelWire() {
			return this.level.wire();
		}

	}

	private final AiProperties properties;

	private final ContextLimitResolver resolver;

	private final ContextStateStore store;

	public ContextCompactor(AiProperties properties, ContextLimitResolver resolver, ContextStateStore store) {
		this.properties = properties;
		this.resolver = resolver;
		this.store = store;
	}

	/** 解析本轮分母（端点与健康检查也走这里，保证"分母只有一个取数点"）。 */
	public int limitTokens(Long providerId, String model) {
		return this.resolver.resolve(providerId, model);
	}

	/**
	 * {@code context} 帧的载荷（<b>两个调用点共用这一份</b>：首轮 {@code chat()} 与续跑
	 * {@code ResumeService}）。字段与设计稿 §3.6 逐键对应；
	 * {@code turns/lostTokens} 恒 0 是 L1 的如实值（只清结果、不折叠轮次、不丢 token）。
	 */
	public static Map<String, Object> framePayload(Outcome outcome) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("level", outcome.levelWire());
		payload.put("before", outcome.before());
		payload.put("after", outcome.after());
		payload.put("turns", 0);
		payload.put("lostTokens", 0);
		payload.put("reason", outcome.reason());
		payload.put("est", false);
		payload.put("savedTokens", outcome.savedTokens());
		payload.put("clearedPairs", outcome.clearedPairs());
		return payload;
	}

	/**
	 * 压缩判定 + 执行（L1）。
	 *
	 * <p>本方法<b>不写</b>快照与记忆：它只返回新列表，调用方按"记忆 → 快照"顺序落库
	 * （两处必须是同一份，否则续跑与下一轮的"模型看到的"会分叉；为何是这个方向、
	 * 以及续跑路径的反序，理由见类注「与设计稿 §3.5 的差异」段）。
	 */
	public Outcome compact(Request request) {
		ContextState state = readState(request.sessionId());
		int limit = limitTokens(request.providerId(), request.model());
		int before = ContextEstimator.estimateMessages(request.messages());
		ContextTrigger trigger = new ContextTrigger(this.properties.getContext());
		if (limit <= 0) {
			return noop(request, before, ContextTrigger.Level.fromWire(state.level()), 0, 0d, 0,
					REASON_LIMIT_UNKNOWN, state.circuit());
		}
		boolean armed = trigger.hysteresisUnlocked(state.usedTokens(), limit, state.everCompacted());
		ContextTrigger.Decision decision = trigger.decide(request.usedTokens(), request.completionTokens(),
				request.inputTokens(), request.toolResultsSinceMeasure(), armed);
		if (!decision.triggered()) {
			return noop(request, before, ContextTrigger.Level.fromWire(state.level()), limit, decision.ratio(),
					decision.nextEst(), armed ? REASON_UNDER_THRESHOLD : REASON_HYSTERESIS_LOCKED, state.circuit());
		}
		// 每次最多升一级 + 本批封顶 L1（见类注"本批范围"）
		ContextTrigger.Level target = ContextTrigger.Level.fromWire(state.level()).advanceTo(decision.level());
		ContextTrigger.Level action = target.ordinal() > MAX_LEVEL.ordinal() ? MAX_LEVEL : target;
		ToolResultClearing.Plan plan = ToolResultClearing.plan(request.messages(),
				this.properties.getContext().getKeepToolPairs(), request.protectedToolCallIds(), request.runId());
		if (!plan.changed()) {
			return noop(request, before, ContextTrigger.Level.fromWire(state.level()), limit, decision.ratio(),
					decision.nextEst(), REASON_NOTHING_TO_CLEAR, state.circuit());
		}
		int after = ContextEstimator.estimateMessages(plan.messages());
		long now = System.currentTimeMillis();
		try {
			// ① 先归档压缩前原文（原文可回溯 —— 覆盖写的历史里被改掉的那一份只能从这里回取）
			if (request.messageJsonBefore() != null && !request.messageJsonBefore().isEmpty()) {
				this.store.archive(request.sessionId(), state.turnNo(), request.messageJsonBefore());
			}
			// ② 再写真值：压缩成功后基线置为"压缩后规模估算"，等下一次真值回填
			ContextState compacted = state.withCompaction(action, 0, 0, "l1_tool_result_clearing", now)
				.withUsage(after, 0, state.turnNo(), true, now, request.model(), request.systemTokens(),
						request.toolsTokens(), request.inputTokens());
			this.store.write(request.sessionId(), compacted);
			// ③ 台账事件（与日志、context 帧三处同一组 (level, before, after, turns, lostTokens)）
			this.store.appendLedger(request.sessionId(), ledgerEvent(action, before, after, plan, request, now));
			log.info("上下文压缩 sessionId={} runId={} level={} before={} after={} turns={} lostTokens={} "
					+ "clearedPairs={} savedTokens={} reason={} ratio={}", request.sessionId(), request.runId(),
					action.wire(), before, after, 0, 0, plan.cleared().size(), plan.savedTokens(),
					"l1_tool_result_clearing", String.format("%.3f", decision.ratio()));
			return new Outcome(action, plan.messages(), before, after, plan.savedTokens(), plan.cleared().size(),
					plan.clearedIds(), "l1_tool_result_clearing", limit, after, true, state.circuit(),
					decision.ratio(), decision.nextEst());
		}
		catch (RuntimeException ex) {
			// 任一步失败 ⇒ 记 ERROR + 保持原状继续本轮请求（不改历史、不改真值）
			log.error("上下文压缩失败 sessionId={} runId={} level={}：{}（历史保持原样，本轮照常发送）",
					request.sessionId(), request.runId(), action.wire(), ex.getMessage());
			return noop(request, before, ContextTrigger.Level.fromWire(state.level()), limit, decision.ratio(),
					decision.nextEst(), REASON_FAILED, state.circuit());
		}
	}

	/**
	 * 轮末记账（真值回填）：{@code used/completion/turnNo/est/model} + 三个分项锚点。
	 *
	 * <p>写失败只 WARN：记账是观测面，不能让一轮已成功的对话因 Redis 抖动变成失败。
	 */
	public void recordTurn(String sessionId, String model, int usedTokens, int completionTokens, int turnNo,
			boolean estimated, int systemTokens, int toolsTokens, int inputTokens) {
		try {
			ContextState state = readState(sessionId);
			long now = System.currentTimeMillis();
			this.store.write(sessionId,
					state.withUsage(usedTokens, completionTokens, turnNo, estimated, now, model, systemTokens,
							toolsTokens, inputTokens));
		}
		catch (RuntimeException ex) {
			log.warn("上下文记账写入失败 sessionId={}：{}", sessionId, ex.getMessage());
		}
	}

	/** 当前会话状态（端点读；异常降级为空态）。 */
	public ContextState readState(String sessionId) {
		try {
			ContextState state = this.store.read(sessionId);
			return state == null ? ContextState.EMPTY : state;
		}
		catch (RuntimeException ex) {
			log.warn("上下文状态读取失败 sessionId={}：{}（按空态处理）", sessionId, ex.getMessage());
			return ContextState.EMPTY;
		}
	}

	private Outcome noop(Request request, int before, ContextTrigger.Level level, int limit, double ratio, int nextEst,
			String reason, String circuit) {
		log.debug("上下文压缩跳过 sessionId={} runId={} reason={} limit={} ratio={}", request.sessionId(),
				request.runId(), reason, limit, String.format("%.3f", ratio));
		return new Outcome(ContextTrigger.Level.NONE, request.messages(), before, before, 0, 0, List.of(), reason, limit,
				request.usedTokens(), false, circuit, ratio, nextEst);
	}

	private Map<String, Object> ledgerEvent(ContextTrigger.Level level, int before, int after,
			ToolResultClearing.Plan plan, Request request, long now) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("level", level.wire());
		event.put("before", before);
		event.put("after", after);
		event.put("turns", 0);
		event.put("lostTokens", 0);
		event.put("summaryChars", 0);
		event.put("model", request.model());
		event.put("est", false);
		event.put("atMs", now);
		event.put("reason", "l1_tool_result_clearing");
		event.put("clearedPairs", plan.cleared().size());
		event.put("clearedToolCallIds", plan.clearedIds());
		return event;
	}

}
