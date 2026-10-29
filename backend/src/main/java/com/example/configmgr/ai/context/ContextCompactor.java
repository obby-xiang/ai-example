package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.observe.AiTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 压缩统一入口（上下文统计与压缩专项批①/②/③；设计稿 §3.5 / §6.4）。
 *
 * <p>
 * <b>一处入口、两个调用点同源</b>：首轮（{@code ResilientChatService#chat} 取记忆之后）与续跑
 * （{@code ResumeService#resume} 重建历史之后）都必须经本类 —— 否则首轮/续跑的口径会分叉，
 * 表现为"同一会话换个入口进来就不压了"。
 *
 * <h2>谱系（每次最多升一级）</h2>
 * <ul>
 * <li><b>L1</b> {@link ToolResultClearing}：只清工具结果（不删消息、不动配对骨架）；</li>
 * <li><b>L2</b> {@link RollingSummarizer}：按 Turn 滑窗 + 递归摘要（六项保留清单）；</li>
 * <li><b>L3</b> {@link HardTrim}：按 Turn 丢最旧；丢完仍超就照发，让上游如实报错。</li>
 * </ul>
 * 升级一格而不是一步到 L3：每级都会改写前缀、让上游缓存整段 miss（调研 Q3），
 * 而"升一级后够不够"只有下一轮的真值才能回答。
 *
 * <h2>熔断（{@link CompactionFuse}）</h2>
 * 只被 L2/L3 的摘要尝试驱动：连续失败达阈值 ⇒ 冷却窗内只留 L1；到点半开一次；成功清零。
 * 有未决 HITL 条目（挂起期）时按 P7 也只允许 L1。
 *
 * <h2>原子性（失败即回滚）</h2>
 * 本类内部的固定顺序：<b>先归档压缩前原文</b> → 再写 {@code ai:ctx:} 真值/台账 → 最后返回新消息列表。
 * 任一步抛错 ⇒ 记 ERROR、返回<b>原消息列表</b>、本轮照常发请求（对齐调研 Q3"绝不静默截断"：
 * 宁可这一轮偏大，也不让"模型看到的"和"留下的"不一致）。L2/L3 的失败连 L1 那一步也不落地 ——
 * 失败路径上的"部分生效"同样会让两套历史分叉。
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

	/** 已实现的最高级别（批③ 升到 L3；配置 l1/l2/l3-percent 与它对齐）。 */
	public static final ContextTrigger.Level MAX_LEVEL = ContextTrigger.Level.L3;

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

	/** 摘要失败（L2/L3；历史逐字不变，熔断计数 +1）。 */
	public static final String REASON_SUMMARY_FAILED = RollingSummarizer.REASON_SUMMARY_FAILED;

	/** 摘要设施不可用（未装配摘要器 / provider 缺失）；与摘要失败同一失败路径。 */
	public static final String REASON_SUMMARY_UNAVAILABLE = "summary_unavailable";

	/** 跳过原因：熔断打开期间不允许 L2/L3（只留 L1）。 */
	public static final String REASON_CIRCUIT_OPEN = "circuit_open";

	/**
	 * 跳过原因：本轮 system + tools 已占到 L2 触发线以上（{@code prefix_dominated}）。
	 *
	 * <p>L2/L3 只能压缩<b>历史</b>，压不动不可压缩的固定前缀：此时再压只会把还能用的旧轮丢掉
	 * （甚至每轮丢一次空操作），换不来任何降幅。故不做，并如实报这个原因（便于运维区分
	 * "没到线" / "该压的都压了" / "是前缀太大"——最后一种只能换更大窗口的模型或精简工具集）。
	 */
	public static final String REASON_PREFIX_DOMINATED = "prefix_dominated";

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
	 * @param runtime                 本轮运行态（摘要的 provider/model/options 唯一来源；null = 摘要不可用）
	 */
	public record Request(String sessionId, String runId, String model, Long providerId, List<Message> messages,
			List<String> messageJsonBefore, int usedTokens, int completionTokens, int inputTokens,
			int toolResultsSinceMeasure, int systemTokens, int toolsTokens, Set<String> protectedToolCallIds,
			ProviderRuntime runtime) {
	}

	/**
	 * 一次压缩结局。
	 *
	 * @param level            实际应用的级别（{@code none}/{@code L1}/{@code L2}/{@code L3}）
	 * @param messages         压缩后（未压缩时 = 原始列表）
	 * @param before           压缩前历史估算
	 * @param after            压缩后历史估算
	 * @param savedTokens      省下的估算 token
	 * @param clearedPairs     被清除的工具结果条数
	 * @param clearedToolCallIds 被清除的调用 id（台账/日志可追溯）
	 * @param reason           跳过/执行原因
	 * @param limitTokens      分母（0 = 未知）
	 * @param usedTokens       压缩后基线（{@code est=true} 时为估算；<b>含 system + tools</b>，与上游
	 *                         {@code usage.promptTokens} 同口径 —— 见 {@code compact} 里的 baseline 注释）
	 * @param est              基线是否估算
	 * @param circuit          熔断态（由 {@code CompactionFuse} 维护）
	 * @param ratio            触发占比
	 * @param nextEst          触发用预估
	 * @param turns            本次折叠/丢弃的 Turn 数（L1 恒 0）
	 * @param lostTokens       本次从模型可见上下文里移除的 token 估算（L1 恒 0）
	 * @param summaryChars     摘要正文字符数（L1 恒 0）
	 */
	public record Outcome(ContextTrigger.Level level, List<Message> messages, int before, int after, int savedTokens,
			int clearedPairs, List<String> clearedToolCallIds, String reason, int limitTokens, int usedTokens,
			boolean est, String circuit, double ratio, int nextEst, int turns, int lostTokens, int summaryChars) {

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

	private final RollingSummarizer.Summarizer summarizer;

	public ContextCompactor(AiProperties properties, ContextLimitResolver resolver, ContextStateStore store) {
		this(properties, resolver, store, null);
	}

	/**
	 * @param summarizer L2/L3 的摘要模型（null = 摘要设施未装配 ⇒ L2/L3 走失败路径并计入熔断）
	 */
	public ContextCompactor(AiProperties properties, ContextLimitResolver resolver, ContextStateStore store,
			RollingSummarizer.Summarizer summarizer) {
		this.properties = properties;
		this.resolver = resolver;
		this.store = store;
		this.summarizer = summarizer;
	}

	/**
	 * 解析本轮分母（触发判定、端点、健康检查、压缩日志都走这里，保证"分母只有一个取数点"）。
	 *
	 * <p>L-89：{@link ContextTrigger#decide} 的分母也由 {@link #compact} 从这里取后<b>注入</b> ——
	 * 触发判定不得再自行读 {@code app.ai.context.limit-tokens}，否则 DB {@code ai_model.max_context}
	 * 与过渡键漂移时，"显示的百分比"与"触发百分比"会是两套口径。
	 */
	public int limitTokens(Long providerId, String model) {
		return this.resolver.resolve(providerId, model);
	}

	/**
	 * {@code context} 帧的载荷（<b>两个调用点共用这一份</b>：首轮 {@code chat()} 与续跑
	 * {@code ResumeService}）。字段与设计稿 §3.6 逐键对应，<b>键集自批① 起冻结</b>（帧契约）：
	 * {@code turns/lostTokens} 在 L1 如实为 0，在 L2/L3 为折叠/丢弃的轮数与移除的 token 估算。
	 */
	public static Map<String, Object> framePayload(Outcome outcome) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("level", outcome.levelWire());
		payload.put("before", outcome.before());
		payload.put("after", outcome.after());
		payload.put("turns", outcome.turns());
		payload.put("lostTokens", outcome.lostTokens());
		payload.put("reason", outcome.reason());
		payload.put("est", false);
		payload.put("savedTokens", outcome.savedTokens());
		payload.put("clearedPairs", outcome.clearedPairs());
		return payload;
	}

	/**
	 * 压缩判定 + 执行（L1 → L2 → L3，每次最多升一级）。
	 *
	 * <p>本方法<b>不写</b>快照与记忆：它只返回新列表，调用方按"记忆 → 快照"顺序落库
	 * （两处必须是同一份，否则续跑与下一轮的"模型看到的"会分叉；为何是这个方向、
	 * 以及续跑路径的反序，理由见类注「与设计稿 §3.5 的差异」段）。
	 *
	 * <p>
	 * <b>原子性</b>：L1/L2/L3 的全部改写先在内存里做完（纯函数），只有全部成功才归档 → 写真值 →
	 * 写台账。任一 L2/L3 步骤失败 ⇒ 返回<b>原消息列表</b>（连 L1 那一步也不落地），fuse 计数 +1。
	 */
	public Outcome compact(Request request) {
		return compact(request, null);
	}

	/**
	 * 压缩（带"每次摘要完成"进度回调；R1-09 M-1）。
	 *
	 * <p>
	 * <b>压缩自身不发任何帧</b>（Task 5 / AR-06 的口径："不伪称持续发帧"）：压缩期的可见性由调用方
	 * 在进入本次同步调用前发出的阶段帧（{@code heartbeat{phase=compacting}}）与一枚<b>独立定时器</b>
	 * （{@code TurnKeepalive}）承担，与压缩内部进度无关。
	 *
	 * <p>
	 * 本回调仍只做一件事：刷新<b>业务活动锚点</b>。L2 的 N 次串行摘要每次最长 60s，累计可能超过
	 * 僵尸窗口（租约不可用/丢租时会被别的实例接管）；回调在<b>每次</b> {@code summarize} 返回/抛出后
	 * 执行一次（失败也算"这一轮还在前进"），把单次静默压回摘要超时上限。
	 *
	 * @param onSummaryProgress 每次摘要尝试结束后的回调（null = 不回调；<b>不得</b>用它发帧）
	 */
	public Outcome compact(Request request, Runnable onSummaryProgress) {
		List<Message> original = request.messages() == null ? List.of() : request.messages();
		ContextState state = readState(request.sessionId());
		int limit = limitTokens(request.providerId(), request.model());
		int before = ContextEstimator.estimateMessages(original);
		AiProperties.Context config = this.properties.getContext();
		ContextTrigger trigger = new ContextTrigger(config);
		if (limit <= 0) {
			return noop(request, before, ContextTrigger.Level.fromWire(state.level()), 0, 0d, 0,
					REASON_LIMIT_UNKNOWN, state.circuit());
		}
		// C13 / I3：滞回判"可压缩历史"，先扣掉本轮不可压缩的 system + tools（否则大前缀永久锁死 L2/L3）
		int fixedPrefix = Math.max(0, request.systemTokens()) + Math.max(0, request.toolsTokens());
		boolean armed = trigger.hysteresisUnlocked(state.usedTokens(), fixedPrefix, limit, state.everCompacted());
		// C13：无真值时用整包估算判（system + tools + 历史 + 本轮输入）—— 轮首/压缩后基线没有
		// 上游 truth，只算"新输入"会让 system 与工具 schema 在小窗口模型上完全不计入判据。
		int wholeRequest = before + Math.max(0, request.systemTokens()) + Math.max(0, request.toolsTokens())
				+ Math.max(0, request.inputTokens()) + Math.max(0, request.toolResultsSinceMeasure());
		ContextTrigger.Decision decision = trigger.decide(limit, request.usedTokens(), request.completionTokens(),
				request.inputTokens(), request.toolResultsSinceMeasure(), wholeRequest, armed);
		if (!decision.triggered()) {
			return noop(request, before, ContextTrigger.Level.fromWire(state.level()), limit, decision.ratio(),
					decision.nextEst(), armed ? REASON_UNDER_THRESHOLD : REASON_HYSTERESIS_LOCKED, state.circuit());
		}
		long now = System.currentTimeMillis();
		CompactionFuse fuse = new CompactionFuse(config);
		// 每次最多升一级（缓存打穿的代价随级别上升，见 ContextTrigger 类注）
		ContextTrigger.Level target = ContextTrigger.Level.fromWire(state.level()).advanceTo(decision.level());
		if (target.ordinal() > MAX_LEVEL.ordinal()) {
			target = MAX_LEVEL;
		}
		// P7：有未决 HITL 条目（挂起期）只允许 L1 —— 用户还要在卡片上决策，摘要会改写卡片上下文
		boolean suspended = request.protectedToolCallIds() != null && !request.protectedToolCallIds().isEmpty();
		if (suspended && target.ordinal() > ContextTrigger.Level.L1.ordinal()) {
			target = ContextTrigger.Level.L1;
		}
		if (target.ordinal() >= ContextTrigger.Level.L2.ordinal()) {
			if (!fuse.allowsSummary(state, now)) {
				// 熔断打开且未到冷却点：只留 L1（可用性优先）
				target = ContextTrigger.Level.L1;
			}
			else if (CompactionFuse.OPEN.equals(state.circuit())) {
				// 冷却到点：半开，只放这一次摘要尝试（成功/失败都会立刻改写熔断态）
				state = fuse.halfOpen(state, now);
			}
		}
		// N3：前缀主导守卫 —— system + tools 本身就过 L2 线时，L2/L3 压不动它（只能丢历史），
		// 而触发线看整条请求 ⇒ 会每轮判 L3、每轮空截一次。此时只留 L1。
		int prefixTokens = Math.max(0, request.systemTokens()) + Math.max(0, request.toolsTokens());
		int l2 = config.getL2Percent() <= 0 ? 80 : config.getL2Percent();
		long denominator = Math.max(1L,
				(long) limit - ContextTrigger.effectiveReserve(limit, config.getReserveTokens()));
		boolean prefixDominated = prefixTokens > 0 && prefixTokens * 100d / denominator >= l2;
		if (prefixDominated && target.ordinal() >= ContextTrigger.Level.L2.ordinal()) {
			target = ContextTrigger.Level.L1;
		}
		ToolResultClearing.Plan clear = ToolResultClearing.plan(original, config.getKeepToolPairs(),
				request.protectedToolCallIds());
		List<Message> current = clear.messages();
		ContextTrigger.Level action = target;
		int turns = 0;
		int summaryChars = 0;
		String reason = "l1_tool_result_clearing";
		try {
			if (target == ContextTrigger.Level.L2) {
				reason = RollingSummarizer.REASON_SUMMARY;
				RollingSummarizer.Summarizer summarizer = withProgress(this.summarizer, onSummaryProgress);
				if (summarizer == null || request.runtime() == null) {
					// 摘要只使用本轮 provider/model/options：没有本轮 runtime 就不压，绝不回落全局默认
					return failSummary(request, state, fuse, now, before, limit, decision, REASON_SUMMARY_UNAVAILABLE);
				}
				RollingSummarizer.Plan summary = RollingSummarizer.plan(current, config.getKeepRecentTurns(),
						config.getChunkTokens(), request.protectedToolCallIds(), request.runtime(), summarizer, now);
				if (!summary.changed()) {
					if (!RollingSummarizer.REASON_NOTHING_TO_SUMMARIZE.equals(summary.reason())) {
						return failSummary(request, state, fuse, now, before, limit, decision, REASON_SUMMARY_FAILED);
					}
					// 没有可摘要的旧轮（全在 keep 窗口）：本节只做了 L1 的活，如实报 L1
					action = ContextTrigger.Level.L1;
					reason = "l1_tool_result_clearing";
				}
				else {
					current = summary.messages();
					turns = summary.summarizedTurns();
					summaryChars = summary.summaryChars();
				}
			}
			else if (target == ContextTrigger.Level.L3) {
				reason = HardTrim.REASON_HARD_TRIM;
				HardTrim.Plan trim = HardTrim.plan(current, config.getKeepRecentTurns(),
						request.protectedToolCallIds());
				if (trim.changed()) {
					current = trim.messages();
					turns = trim.droppedTurns();
				}
				else {
					// 全在 pin 集里：这一刻没有可丢的，别把"只清了结果"报成 L3
					action = ContextTrigger.Level.L1;
					reason = "l1_tool_result_clearing";
				}
			}
		}
		catch (RuntimeException ex) {
			return failSummary(request, state, fuse, now, before, limit, decision, REASON_SUMMARY_FAILED);
		}
		if (!clear.changed() && turns == 0) {
			return noop(request, before, ContextTrigger.Level.fromWire(state.level()), limit, decision.ratio(),
					decision.nextEst(), prefixDominated ? REASON_PREFIX_DOMINATED : REASON_NOTHING_TO_CLEAR,
					state.circuit());
		}
		int after = ContextEstimator.estimateMessages(current);
		int saved = Math.max(0, before - after);
		// lostTokens 的口径 = "丢掉的轮次里的 token"（L1 不折叠轮次 ⇒ 0，省下的量看 savedTokens）；
		// 这是批① 既有 `context` 帧断言的语义（只升不降），L2/L3 折叠/丢弃整轮才计 lost。
		int lost = action.ordinal() >= ContextTrigger.Level.L2.ordinal() ? saved : 0;
		boolean wasOpen = state.circuit() != null && !CompactionFuse.CLOSED.equals(state.circuit());
		try {
			// ① 先归档压缩前原文（原文可回溯 —— 覆盖写的历史里被改掉的那一份只能从这里回取）
			if (request.messageJsonBefore() != null && !request.messageJsonBefore().isEmpty()) {
				this.store.archive(request.sessionId(), state.turnNo(), request.messageJsonBefore());
			}
			// ② 再写真值：压缩成功后基线置为"压缩后规模估算"，等下一次真值回填。
			// C13：这条基线必须<b>含 system + tools</b> —— 上游真值（promptTokens）本就包含这两项，
			// 基线只写历史估算会让下一轮 nextEst 永远少算前缀，小窗口上"看着没满、一发就 400"。
			// 台账/帧的 before/after 仍是消息口径（与轮首同账），只有基线补齐前缀。
			int baseline = after + Math.max(0, request.systemTokens()) + Math.max(0, request.toolsTokens());
			ContextState compacted = state.withCompaction(action, turns, lost, reason, now)
				.withUsage(baseline, 0, state.turnNo(), true, now, request.model(), request.systemTokens(),
						request.toolsTokens(), request.inputTokens());
			if (action.ordinal() >= ContextTrigger.Level.L2.ordinal()) {
				compacted = fuse.onSuccess(compacted, now);
			}
			this.store.write(request.sessionId(), compacted);
			// ③ 台账事件（与日志、context 帧三处同一组 (level, before, after, turns, lostTokens)）
			this.store.appendLedger(request.sessionId(),
					ledgerEvent(action, before, after, turns, lost, summaryChars, reason, clear, request, now,
							compacted.circuit()));
			if (wasOpen && action.ordinal() >= ContextTrigger.Level.L2.ordinal()) {
				log.info("上下文摘要熔断恢复 sessionId={} level={} fails=0", request.sessionId(), action.wire());
				this.store.appendLedger(request.sessionId(),
						fuseEvent("circuit_closed", compacted, request, now, before));
			}
			log.info("上下文压缩 sessionId={} runId={} level={} before={} after={} turns={} lostTokens={} "
					+ "clearedPairs={} summaryChars={} reason={} ratio={}", request.sessionId(), request.runId(),
					action.wire(), before, after, turns, lost, clear.cleared().size(), summaryChars, reason,
					String.format(Locale.ROOT, "%.3f", decision.ratio()));
			// 日志链路批：压缩是"这一轮为什么变了/省了多少"的唯一解释项（与上面那行同源，字段可聚合）
			AiTrace.eventOn(request.runId(), request.sessionId(), "compaction", "level", action.wire(), "reason",
					reason, "before", before, "after", after, "savedTokens", saved, "lostTokens", lost, "turns",
					turns, "clearedPairs", clear.cleared().size(), "summaryChars", summaryChars, "limit", limit);
			return new Outcome(action, current, before, after, saved, clear.cleared().size(), clear.clearedIds(),
					reason, limit, baseline, true, compacted.circuit(), decision.ratio(), decision.nextEst(), turns, lost,
					summaryChars);
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
				request.runId(), reason, limit, String.format(Locale.ROOT, "%.3f", ratio));
		return new Outcome(ContextTrigger.Level.NONE, request.messages(), before, before, 0, 0, List.of(), reason, limit,
				request.usedTokens(), false, circuit, ratio, nextEst, 0, 0, 0);
	}

	private Map<String, Object> ledgerEvent(ContextTrigger.Level level, int before, int after, int turns, int lostTokens,
			int summaryChars, String reason, ToolResultClearing.Plan plan, Request request, long now, String circuit) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("level", level.wire());
		event.put("before", before);
		event.put("after", after);
		event.put("turns", turns);
		event.put("lostTokens", lostTokens);
		event.put("summaryChars", summaryChars);
		event.put("model", request.model());
		event.put("est", false);
		event.put("atMs", now);
		event.put("reason", reason);
		event.put("clearedPairs", plan.cleared().size());
		event.put("clearedToolCallIds", plan.clearedIds());
		event.put("circuit", circuit == null ? CompactionFuse.CLOSED : circuit);
		return event;
	}

	/**
	 * 摘要失败：<b>历史保持原样</b>，只推进熔断计数（并写一行台账事件）。
	 *
	 * <p>
	 * 刻意不在这里退回 L1/硬截断：一旦"失败就换个更狠的招"，同一份历史会在两个实例、两个时刻
	 * 走出不同分支，而且用户看到的是"压缩成功"。失败就是失败 —— 本轮照常发请求。
	 */
	private Outcome failSummary(Request request, ContextState state, CompactionFuse fuse, long now, int before,
			int limit, ContextTrigger.Decision decision, String reason) {
		ContextState failed = fuse.onFailure(state, now);
		try {
			this.store.write(request.sessionId(), failed);
			this.store.appendLedger(request.sessionId(), fuseEvent(reason, failed, request, now, before));
		}
		catch (RuntimeException ex) {
			log.warn("熔断计数写入失败 sessionId={}：{}（按未计数继续）", request.sessionId(), ex.getMessage());
		}
		if (CompactionFuse.OPEN.equals(failed.circuit())) {
			log.warn("上下文摘要熔断打开 sessionId={} fails={} openUntil={}（此后只做 L1）", request.sessionId(),
					failed.fuseFails(), failed.openUntilMs());
		}
		log.error("上下文摘要失败 sessionId={} runId={} reason={}（历史保持原样，本轮照常发送）", request.sessionId(),
				request.runId(), reason);
		return new Outcome(ContextTrigger.Level.NONE, request.messages(), before, before, 0, 0, List.of(), reason, limit,
				request.usedTokens(), false, failed.circuit(), decision.ratio(), decision.nextEst(), 0, 0, 0);
	}

	/** 熔断/恢复的台账事件（与压缩事件同键族；{@code level=none} 表示"没压成"）。 */
	private Map<String, Object> fuseEvent(String reason, ContextState state, Request request, long now, int before) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("level", ContextTrigger.Level.NONE.wire());
		event.put("before", before);
		event.put("after", before);
		event.put("turns", 0);
		event.put("lostTokens", 0);
		event.put("summaryChars", 0);
		event.put("model", request.model());
		event.put("est", false);
		event.put("atMs", now);
		event.put("reason", reason);
		event.put("circuit", state.circuit() == null ? CompactionFuse.CLOSED : state.circuit());
		event.put("fails", state.fuseFails());
		return event;
	}

	/**
	 * 给摘要器包一层"每次尝试结束"回调（成功/失败都算一次前进）。
	 *
	 * <p>回调本身必须极轻（续跑路径传的是 {@code touchBusinessActivity}：一条单键 Lua）；
	 * 回调抛异常也不能改写摘要结果 —— 用 {@code finally} 执行并吃掉回调节点的异常。
	 */
	private static RollingSummarizer.Summarizer withProgress(RollingSummarizer.Summarizer delegate, Runnable hook) {
		if (delegate == null || hook == null) {
			return delegate;
		}
		return (systemPrompt, userPrompt, runtime) -> {
			try {
				return delegate.summarize(systemPrompt, userPrompt, runtime);
			}
			finally {
				try {
					hook.run();
				}
				catch (RuntimeException ex) {
					log.debug("摘要进度回调失败（忽略，不影响压缩）：{}", ex.getMessage());
				}
			}
		};
	}

}
