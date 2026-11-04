package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.observe.AiTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <h2>B1：规划与提交分开（提交只发生在记忆 CAS 成功之后）</h2>
 * <ul>
 * <li>{@link #plan} 完成判定、L1/L2/L3 内容变换与摘要调用；除冷却到点时用 version CAS
 * 抢占一次 half-open 名额外，不写台账、事件或压缩结果；</li>
 * <li>{@link #commit} 以 version CAS 落状态，再按稳定 compactionId 幂等补写台账和事件。</li>
 * </ul>
 * 调用方必须先 {@code writeBackMemory}（整窗 CAS）成功，再调用 {@link #commit}。这样
 * "CAS 失败"只会丢掉一次计算结果，不会留下"状态说压了、模型看原文"的分叉；两个生产调用点
 * （首轮 / 续跑）都显式分两步，没有"plan + commit 一步到位"的旁路。
 *
 * <h2>谱系（每次最多升一级）</h2>
 * <ul>
 * <li><b>L1</b> {@link ToolResultClearing}：只清工具结果（不删消息、不动配对骨架）；</li>
 * <li><b>L2</b> {@link RollingSummarizer}：按 Turn 滑窗 + 递归摘要（六项保留清单）；</li>
 * <li><b>L3</b> {@link HardTrim}：按 Turn 丢最旧；丢完仍超就照发，让上游如实报错。</li>
 * </ul>
 * 升级一格而不是一步到 L3：每级都会改写前缀、让上游缓存整段 miss（调研 Q3），
 * 而"升一级后够不够"只有下一轮的真值才能回答。B1 额外保证：<b>滞回只锁同级重复</b>
 * （{@link ContextTrigger#upgradeAllowed} / {@link ContextTrigger#hardOverflow} 可逃逸）。
 *
 * <h2>B1：L2 读未清除的工具结果</h2>
 * 旧实现先 L1（把工具结果换成占位符）再摘要 —— 摘要模型看到的是"已清除"的空壳，资源 ID /
 * 参数 / 未决事项全丢。现在 L2 的摘要输入是<b>原历史</b>；L1 只作用于摘要后<b>保留的后缀</b>，
 * 两件事的记账也分开（{@code lostTokens} 只含 L2/L3 的折叠/丢弃量）。
 *
 * <h2>熔断（{@link CompactionFuse}）</h2>
 * 只被<b>真实 L2 摘要尝试</b>驱动：连续失败达阈值 ⇒ 冷却窗内只留 L1；到点半开一次；成功清零。
 * L3（本地硬截断）与 L1 都不碰熔断；摘要设施不可用（未装配摘要器 / 无本轮 runtime）不算一次
 * 失败尝试（不计数）。有未决 HITL 条目（挂起期）时按 P7 也只允许 L1。
 *
 * <h2>原子性与补写</h2>
 * 规划失败返回原消息列表；摘要失败只由 {@code commit} 原子推进熔断计数。压缩提交先 CAS 状态，
 * 再以稳定 compactionId 补写台账和事件；失败时不发 {@code context} 帧，记忆仍保持已 CAS 的压缩结果，
 * 同一 Plan 的重试可收敛缺失事实而不覆盖其它写者。
 */
@Slf4j
public final class ContextCompactor {

	/** 已实现的最高级别（批③ 升到 L3；配置 l1/l2/l3-percent 与它对齐）。 */
	public static final ContextTrigger.Level MAX_LEVEL = ContextTrigger.Level.L3;

	/** L1 动作的线上原因（帧/台账/日志同一字符串）。 */
	public static final String REASON_L1 = "l1_tool_result_clearing";

	/** 跳过原因：占比未达 L1。 */
	public static final String REASON_UNDER_THRESHOLD = "under_threshold";

	/** 跳过原因：滞回锁未解锁（压缩完成但真值仍 ≥ 60%）。 */
	public static final String REASON_HYSTERESIS_LOCKED = "hysteresis_locked";

	/** 跳过原因：上限未知（降级态，不做比例判定）。 */
	public static final String REASON_LIMIT_UNKNOWN = "limit_unknown";

	/** 跳过原因：没有可清的工具结果（全部在 pin 窗口内/已清/受保护）。 */
	public static final String REASON_NOTHING_TO_CLEAR = "nothing_to_clear";

	/** 跳过原因：状态/台账/事件写失败（历史保持原样）。 */
	public static final String REASON_FAILED = "compaction_failed";

	/** 摘要失败（L2/L3；历史逐字不变，熔断计数 +1）。 */
	public static final String REASON_SUMMARY_FAILED = RollingSummarizer.REASON_SUMMARY_FAILED;

	/** 摘要设施不可用（未装配摘要器 / provider 缺失）；不计数熔断（没有发生摘要尝试）。 */
	public static final String REASON_SUMMARY_UNAVAILABLE = "summary_unavailable";

	/** 跳过原因：熔断打开期间不允许 L2/L3（只留 L1）。 */
	public static final String REASON_CIRCUIT_OPEN = "circuit_open";

	/**
	 * 跳过原因：硬截断<b>裁完也装不下</b>（{@code hard_trim_cannot_fit}）。
	 *
	 * <p>前缀主导 + 历史已在 {@code keep-recent-turns} 下限时，丢掉的几轮换不回可用空间
	 * （{@code nextEst − lostTokens + effectiveReserve} 仍 &gt; {@code limit}）。这时裁剪只会
	 * <b>先丢掉用户的历史、再发一次注定 400 的请求</b> —— 净新增数据丢失。故宁可不裁、原样返回：
	 * 上游如实报错，历史留着还能换窗口/换模型重试。
	 */
	public static final String REASON_TRIM_CANNOT_FIT = "hard_trim_cannot_fit";

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
	 * @param sessionId               会话 id（真值/台账/事件键的一维）
	 * @param runId                   本轮 runId（台账回取路径）
	 * @param model                   本轮模型名（解析分母）
	 * @param providerId              本轮 provider id（解析分母；null = 无身份 ⇒ 上限未知）
	 * @param messages                当前历史（原对象不被修改）
	 * @param usedTokens              上次成功请求的真值（无则 0）
	 * @param completionTokens        上次成功请求的输出（nextEst 的一项）
	 * @param inputTokens             本轮输入估算
	 * @param toolResultsSinceMeasure 上次真值之后新增的工具结果估算
	 * @param systemTokens            人设 + 工作区快照估算
	 * @param toolsTokens             本轮披露 schema 估算
	 * @param protectedToolCallIds    未决 HITL 条目（永不清除）
	 * @param runtime                 本轮运行态（摘要的 provider/model/options 唯一来源；null = 摘要不可用）
	 * @param protectedRunIds         活跃 run（L1 不得清其工具结果、L3 不得裁掉，防止迟到写回复活旧块；
	 *                                本 run 由实现自行剔除）
	 * @param maxOutputTokens         本轮请求的生效 {@code max_tokens}（B1：与轮内预算同一预留公式；
	 *                                {@code <=0} = 未声明）
	 * @param l1Due                   {@code MemoryWindow} 在绝对 token 预算下报告"淘汰到下限仍越线" ⇒
	 *                                即使未到模型 L1 线也执行一次 L1（不因此升级到 L2）
	 */
	public record Request(String sessionId, String runId, String model, Long providerId, List<Message> messages,
			int usedTokens, int completionTokens, int inputTokens,
			int toolResultsSinceMeasure, int systemTokens, int toolsTokens, Set<String> protectedToolCallIds,
			ProviderRuntime runtime, Set<String> protectedRunIds, int maxOutputTokens, boolean l1Due,
			String cause, String anchorToolCallId) {
	}

	/**
	 * 一次压缩结局。
	 *
	 * @param level            实际应用的级别（{@code none}/{@code L1}/{@code L2}/{@code L3}）
	 * @param messages         压缩后（未压缩时 = 原始列表）
	 * @param before           压缩前历史估算
	 * @param after            压缩后历史估算
	 * @param savedTokens      省下的估算 token（含 L1；L2 的 lostTokens 只含折叠量）
	 * @param clearedPairs     被清除的工具结果条数
	 * @param clearedToolCallIds 被清除的调用 id（台账/日志可追溯）
	 * @param reason           跳过/执行原因
	 * @param limitTokens      分母（0 = 未知）
	 * @param usedTokens       压缩后基线（{@code est=true} 时为估算；<b>含 system + tools</b>，与上游
	 *                         {@code usage.promptTokens} 同口径 —— 见 {@code commit} 里的 baseline 注释）
	 * @param est              基线是否估算
	 * @param circuit          熔断态（由 {@code CompactionFuse} 维护）
	 * @param ratio            触发占比
	 * @param nextEst          触发用预估
	 * @param turns            本次折叠/丢弃的 Turn 数（L1 恒 0）
	 * @param lostTokens       本次折叠/丢弃掉的 token 估算（L1 恒 0；L2 不含同次 L1 的节省）
	 * @param summaryChars     摘要正文字符数（L1 恒 0）
	 * @param committed        事实是否已落库（false = 状态/台账/事件写失败；调用方据此不发 {@code context} 帧）
	 */
	public record Outcome(ContextTrigger.Level level, List<Message> messages, int before, int after, int savedTokens,
			int clearedPairs, List<String> clearedToolCallIds, String reason, int limitTokens, int usedTokens,
			boolean est, String circuit, double ratio, int nextEst, int turns, int lostTokens, int summaryChars,
			boolean committed, String eventId, String runId, String cause, String anchorToolCallId, long atMs) {

		public boolean changed() {
			return this.level != ContextTrigger.Level.NONE;
		}

		public String levelWire() {
			return this.level.wire();
		}

	}

	/**
	 * 一次压缩规划（{@link #plan} 的产物；<b>不含任何已落库事实</b>）。
	 *
	 * <p>字段全部是"如果提交会写什么"的预备值。{@link #changed()} 为 false 时表示这次不压缩
	 * （no-op / 摘要失败 / 未达线），{@code countFuseFailure} 标记"这次摘要尝试失败、提交时只推进熔断"。
	 */
	public record Plan(ContextState state, ContextTrigger.Level action, List<Message> messages, int before,
			int after, int savedTokens, ToolResultClearing.Plan clear, int turns, int summaryChars, int lostTokens,
			String reason, long atMs, boolean summaryAttempted, boolean summarised, boolean countFuseFailure,
			boolean prefixDominated, ContextTrigger.Decision decision) {

		public boolean changed() {
			return this.action != ContextTrigger.Level.NONE;
		}

		private Plan withState(ContextState newState) {
			return new Plan(newState, this.action, this.messages, this.before, this.after, this.savedTokens, this.clear,
					this.turns, this.summaryChars, this.lostTokens, this.reason, this.atMs, this.summaryAttempted,
					this.summarised, this.countFuseFailure, this.prefixDominated, this.decision);
		}

	}

	private final AiProperties properties;

	private final ContextLimitResolver resolver;

	private final ContextStateStore store;

	private final RollingSummarizer.Summarizer summarizer;

	/**
	 * @param summarizer L2/L3 的摘要模型（null = 摘要设施未装配 ⇒ L2 走"不可用"路径、不计数熔断）
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
	 * <p>{@link ContextTrigger#decide} 的分母也由 {@link #plan} 从这里取后<b>注入</b>：显示、触发、
	 * 端点与健康检查共用同一份 {@code ai_model.max_context}。
	 */
	public int limitTokens(Long providerId, String model) {
		return resolveLimit(providerId, model).limitTokens();
	}

	/** 分母 + 来源（A6：{@code /context}、{@code /health} 的 {@code limitSource} 同一取数点）。 */
	public ContextLimitResolver.ResolveResult resolveLimit(Long providerId, String model) {
		return this.resolver.resolveDetailed(providerId, model);
	}

	/**
	 * {@code context} 帧的载荷（<b>两个调用点共用这一份</b>：首轮 {@code chat()} 与续跑
	 * {@code ResumeService}）。B3 起 additive {@code eventId/committed/cause/anchorToolCallId/runId/atMs}；
	 * B5 移除无消费者的 {@code savedTokens/clearedPairs}，{@code est} 改用真实基线来源。
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
		// B5：est 用真实基线来源（压缩后是估算基线），不再硬编码 false。
		payload.put("est", outcome.est());
		// B5：savedTokens/clearedPairs 无前端消费者，移出 context 帧（台账/日志仍保留）。
		// B3（additive）：稳定事实身份 + 归属/位置；不新增帧型。
		payload.put("eventId", outcome.eventId());
		payload.put("committed", outcome.committed());
		payload.put("cause", outcome.cause());
		payload.put("anchorToolCallId", outcome.anchorToolCallId());
		payload.put("runId", outcome.runId());
		payload.put("atMs", outcome.atMs());
		return payload;
	}

	/**
	 * 规划一次压缩。外部副作用只有 L2 摘要调用，以及冷却到点时对 half-open 名额的 version CAS 占位；
	 * 压缩状态、台账和事件全部留到 {@link #commit}。调用方必须先用返回的 {@link Plan#messages()} 做整窗
	 * 记忆 CAS，成功后再 {@code commit}。
	 *
	 * <p><b>摘要开始回调</b>只在真正调用 L2 摘要模型之前触发一次（多分块也只一次），L1/L3、
	 * 未达线、摘要器缺席、前缀主导都不触发 —— 用户不会在"没有模型摘要"的轮次看到"正在压缩"。
	 *
	 * @param onSummaryProgress 每次摘要尝试结束后的回调（null = 不回调；<b>不得</b>用它发帧）
	 * @param onSummaryStart    第一次真正调用摘要模型之前的回调（null = 不回调）
	 */
	public Plan plan(Request request, Runnable onSummaryProgress, Runnable onSummaryStart) {
		List<Message> original = request.messages() == null ? List.of() : request.messages();
		AiProperties.Context config = this.properties.getContext();
		ContextTrigger trigger = new ContextTrigger(config);
		int limit = limitTokens(request.providerId(), request.model());
		int before = ContextEstimator.estimateMessages(original);
		int maxOutput = Math.max(0, request.maxOutputTokens());
		int reserve = ContextTrigger.effectiveReserve(limit, config.getReserveTokens(), maxOutput);
		ContextState state;
		try {
			// 轮首/续跑是既有的低频恢复入口：每次最多补一笔持久化 pending，不新增后台轮询。
			this.store.reconcileCompaction(request.sessionId());
			state = readStateForUpdate(request.sessionId());
		}
		catch (RuntimeException ex) {
			log.warn("context state strict read failed sessionId={} runId={} error={} (skip compaction; never reset state from an unknown baseline)",
					request.sessionId(), request.runId(), ex.getMessage());
			return noopPlan(request, ContextState.EMPTY, before,
					new ContextTrigger.Decision(ContextTrigger.Level.NONE, 0d, 0, 0, reserve), REASON_FAILED);
		}
		if (limit <= 0) {
			// 降级态保持"不做任何压缩"（含 l1Due）：没有分母就没有比例判定，也不该借窗口预算
			// 在未知上限的模型上改写历史。
			return noopPlan(request, state, before,
					new ContextTrigger.Decision(ContextTrigger.Level.NONE, 0d, 0, 0, reserve),
					REASON_LIMIT_UNKNOWN);
		}
		long now = System.currentTimeMillis();
		ContextTrigger.Level lineage = ContextTrigger.Level.fromWire(state.level());
		ContextTrigger.Decision decision;
		ContextTrigger.Level target;
		boolean prefixDominated = false;
		{
			// C13 / I3：滞回判"可压缩历史"，先扣掉本轮不可压缩的 system + tools（否则大前缀永久锁死 L2/L3）
			int fixedPrefix = Math.max(0, request.systemTokens()) + Math.max(0, request.toolsTokens());
			boolean unlocked = trigger.hysteresisUnlocked(state.usedTokens(), fixedPrefix, limit,
					state.everCompacted(), maxOutput);
			// C13：无真值时用整包估算判（system + tools + 历史 + 本轮输入）—— 轮首/压缩后基线没有
			// 上游 truth，只算"新输入"会让 system 与工具 schema 在小窗口模型上完全不计入判据。
			int wholeRequest = before + Math.max(0, request.systemTokens()) + Math.max(0, request.toolsTokens())
					+ Math.max(0, request.inputTokens()) + Math.max(0, request.toolResultsSinceMeasure());
			// B1 / I1：先算"原始判定"（阈值视为已武装），只用于判断是否允许升级或硬越窗逃逸。
			// 滞回只抑制同级重复：rawLevel 更高 ⇒ 照常升级；nextEst >= limit ⇒ 硬越窗必须逃逸。
			ContextTrigger.Decision raw = trigger.decide(limit, request.usedTokens(), request.completionTokens(),
					request.inputTokens(), request.toolResultsSinceMeasure(), wholeRequest, true, maxOutput);
			boolean escape = ContextTrigger.upgradeAllowed(raw.level(), lineage)
					|| ContextTrigger.hardOverflow(raw.nextEst(), limit);
			boolean armed = unlocked || escape;
			decision = trigger.decide(limit, request.usedTokens(), request.completionTokens(), request.inputTokens(),
					request.toolResultsSinceMeasure(), wholeRequest, armed, maxOutput);
			if (!decision.triggered()) {
				if (!request.l1Due()) {
					return noopPlan(request, state, before, decision,
							armed ? REASON_UNDER_THRESHOLD : REASON_HYSTERESIS_LOCKED);
				}
				// MemoryWindow 的绝对预算越线：只强制 L1，不因 L1 空操作升级到 L2/L3（A1 的最稳默认）
				target = ContextTrigger.Level.L1;
			}
			else {
				// 每次最多升一级；但<b>不得借谱系位置在低占比重复更高一级</b>：
				// 谱系已到 L2/L3、本次占比只要求 L1 时，只做 L1（A1：不在 L2 线以下改写整个前缀）。
				target = decision.level().ordinal() < lineage.ordinal() ? decision.level()
						: lineage.advanceTo(decision.level());
				if (target.ordinal() > MAX_LEVEL.ordinal()) {
					target = MAX_LEVEL;
				}
				// P7：有未决 HITL 条目（挂起期）只允许 L1 —— 用户还要在卡片上决策，摘要会改写卡片上下文
				boolean suspended = request.protectedToolCallIds() != null
						&& !request.protectedToolCallIds().isEmpty();
				if (suspended && target.ordinal() > ContextTrigger.Level.L1.ordinal()) {
					target = ContextTrigger.Level.L1;
				}
				CompactionFuse fuse = new CompactionFuse(config);
				if (target.ordinal() >= ContextTrigger.Level.L2.ordinal() && !fuse.allowsSummary(state, now)) {
					// 熔断打开且未到冷却点：只留 L1（可用性优先）
					target = ContextTrigger.Level.L1;
				}
				// N3：前缀主导守卫 —— system + tools 本身就过 L2 线时，L2/L3 压不动它（只能丢历史），
				// 而触发线看整条请求 ⇒ 会每轮判 L3、每轮空截一次。此时只留 L1。
				int prefixTokens = Math.max(0, request.systemTokens()) + Math.max(0, request.toolsTokens());
				int l2 = config.getL2Percent() <= 0 ? 80 : config.getL2Percent();
				int completionReserve = ContextTrigger.effectiveReserve(limit, config.getReserveTokens(), maxOutput);
				long denominator = Math.max(1L, (long) limit - completionReserve);
				// C1 + R4/N-A：守卫只在"这一发**连同输出预留**都放得下"时成立 —— 那时 L2/L3 确实徒劳
				// （压不动固定前缀）。判据必须带上输出预留：`limit − reserve ≤ nextEst < limit` 这段
				// prompt 看似没满，但加上 max_tokens 就放不下，上游照样 400。故：
				//   放得下 ⇒ 降级（L1）；放不下（含 nextEst >= limit 的真越窗）⇒ 保留 L3。
				boolean fitsWithCompletion = decision.nextEst() + completionReserve <= limit;
				prefixDominated = prefixTokens > 0 && prefixTokens * 100d / denominator >= l2
						&& decision.nextEst() < limit && fitsWithCompletion;
				if (prefixDominated && target.ordinal() >= ContextTrigger.Level.L2.ordinal()) {
					target = ContextTrigger.Level.L1;
				}
			}
		}
		Set<String> otherActiveRuns = otherActiveRuns(request);
		ToolResultClearing.Plan clear = ToolResultClearing.plan(original, config.getKeepToolPairs(),
				request.protectedToolCallIds(), otherActiveRuns);
		List<Message> current = clear.messages();
		ContextTrigger.Level action = target;
		int turns = 0;
		int summaryChars = 0;
		int lost = 0;
		String reason = target == ContextTrigger.Level.L2 ? RollingSummarizer.REASON_SUMMARY
				: (target == ContextTrigger.Level.L3 ? HardTrim.REASON_HARD_TRIM : REASON_L1);
		boolean summaryAttempted = false;
		boolean summarised = false;
		ContextState[] summaryState = { state };
		try {
			if (target == ContextTrigger.Level.L2) {
				// 2026-10-29 压缩阶段信号批：摘要是唯一"真在压缩上下文"的动作 —— 只在第一次真正调用
				// 摘要模型之前公告阶段。L1/L3（本地操作）、摘要器缺席、没有可摘要旧轮都静默。
				RollingSummarizer.Summarizer summarizer = withStart(
						withProgress(this.summarizer, onSummaryProgress), onSummaryStart);
				if (CompactionFuse.OPEN.equals(state.circuit())) {
					summarizer = withHalfOpenReservation(summarizer, request, summaryState, now);
				}
				if (summarizer == null || request.runtime() == null) {
					// 摘要只使用本轮 provider/model/options：没有本轮 runtime 就不压，绝不回落全局默认。
					// B1：这不是一次"摘要尝试"（没有调用模型）⇒ 不推进熔断计数。
					return noopPlan(request, state, before, decision, REASON_SUMMARY_UNAVAILABLE);
				}
				summaryAttempted = true;
				// B1 / I2：摘要输入 = 未清除的工具结果（L1 不能先污染摘要的输入）
				RollingSummarizer.Plan summary = RollingSummarizer.plan(original, config.getKeepRecentTurns(),
						config.getChunkTokens(), request.protectedToolCallIds(), request.runtime(), summarizer, now);
				state = summaryState[0];
				if (!summary.changed()) {
					if (!RollingSummarizer.REASON_NOTHING_TO_SUMMARIZE.equals(summary.reason())) {
						return failurePlan(request, state, before, decision, REASON_SUMMARY_FAILED);
					}
					// 没有可摘要的旧轮（全在 keep 窗口）：本节只做了 L1 的活，如实报 L1
					action = ContextTrigger.Level.L1;
					reason = REASON_L1;
				}
				else {
					summarised = true;
					turns = summary.summarizedTurns();
					summaryChars = summary.summaryChars();
					lost = summary.lostTokens();
					current = summary.messages();
					// B1：L1 只作用于摘要后保留的后缀（折叠掉的旧轮已进摘要，不需要再清）
					ToolResultClearing.Plan suffix = ToolResultClearing.plan(current, config.getKeepToolPairs(),
							request.protectedToolCallIds(), otherActiveRuns);
					clear = suffix;
					current = suffix.messages();
				}
			}
			else if (target == ContextTrigger.Level.L3) {
				HardTrim.Plan trim = HardTrim.plan(current, config.getKeepRecentTurns(),
						request.protectedToolCallIds(), request.protectedRunIds());
				// N-D：`clear` 在本节之前就已算好（零代价、未落任何存储），判据必须计入它的收益 ——
				// 旧实现只减 `trim.lostTokens()`，"清掉旧结果就能过"的一轮被误判成"裁了也没用"。
				int afterClear = decision.nextEst() - clear.savedTokens();
				int completionReserve = ContextTrigger.effectiveReserve(limit, config.getReserveTokens(), maxOutput);
				boolean clearAloneFits = clear.changed() && afterClear + completionReserve <= limit;
				if (clearAloneFits) {
					// 清除本身已经够了 ⇒ 只做 L1、不丢轮次（硬裁换不回额外空间）
					action = ContextTrigger.Level.L1;
					reason = REASON_L1;
				}
				else if (trim.changed()) {
					// N-C：按<b>清 + 裁后</b>估算判 —— 清 + 裁都放不下时，这一刀只丢历史、
					// 换不回可用请求（下一发照样 400），不做。
					int afterTrim = afterClear - trim.lostTokens();
					if (afterTrim + completionReserve > limit) {
						if (clear.changed()) {
							// 至少把这次零代价的 L1 清除落地（不丢轮、不写硬裁台账）
							action = ContextTrigger.Level.L1;
							reason = REASON_L1;
						}
						else {
							return noopPlan(request, state, before, decision, REASON_TRIM_CANNOT_FIT);
						}
					}
					else {
						current = trim.messages();
						turns = trim.droppedTurns();
						lost = trim.lostTokens();
					}
				}
				else {
					// 全在 pin 集里：这一刻没有可丢的，别把"只清了结果"报成 L3
					action = ContextTrigger.Level.L1;
					reason = REASON_L1;
				}
			}
			else {
				// L1：current 已是清除后的列表
				action = ContextTrigger.Level.L1;
				reason = REASON_L1;
			}
		}
		catch (HalfOpenReservationException ex) {
			return noopPlan(request, summaryState[0], before, decision, REASON_CIRCUIT_OPEN);
		}
		catch (RuntimeException ex) {
			state = summaryState[0];
			if (summaryAttempted) {
				return failurePlan(request, state, before, decision, REASON_SUMMARY_FAILED);
			}
			// L1/L3 本地动作抛错：没有摘要尝试 ⇒ 不碰熔断
			log.warn("context compaction planning failed sessionId={} runId={} error={} (history unchanged)", request.sessionId(),
					request.runId(), ex.getMessage());
			return noopPlan(request, state, before, decision, REASON_FAILED);
		}
		if (!clear.changed() && turns == 0) {
			return noopPlan(request, state, before, decision,
					prefixDominated ? REASON_PREFIX_DOMINATED : REASON_NOTHING_TO_CLEAR);
		}
		int after = ContextEstimator.estimateMessages(current);
		int saved = Math.max(0, before - after);
		return new Plan(state, action, current, before, after, saved, clear, turns, summaryChars, lost, reason, now,
				summaryAttempted, summarised, false, prefixDominated, decision);
	}

	/**
	 * 提交一次已规划的压缩（<b>必须在记忆 CAS 成功之后调用</b>）。
	 *
	 * <p>状态以规划时的 version 做 CAS，随后以稳定 compactionId 幂等补写台账与事件。任一步失败返回
	 * {@code committed=false} 且不发帧；持同一 Plan 重试不会重复分配事件身份或覆盖并发状态。
	 *
	 * <p>摘要失败（{@link Plan#countFuseFailure()}）只写熔断计数与一行台账，历史不变。
	 */
	public Outcome commit(Request request, Plan plan) {
		return commit(request, plan, 3);
	}

	private Outcome commit(Request request, Plan plan, int attemptsLeft) {
		if (plan == null) {
			return noopOutcome(request, null, REASON_FAILED);
		}
		if (!plan.changed()) {
			if (plan.countFuseFailure()) {
				return commitFuseFailure(request, plan);
			}
			return outcome(plan, request, plan.state(), true);
		}
		ContextState state = plan.state();
		// B3：事件 atMs = 提交时刻（不是规划时刻；L2 摘要可能耗时很久）
		long now = System.currentTimeMillis();
		String compactionId = compactionId(request, plan);
		try {
			// 压缩成功后基线置为"压缩后规模估算"，等下一次真值回填。
			// C13：这条基线必须<b>含 system + tools</b> —— 上游真值（promptTokens）本就包含这两项，
			// 基线只写历史估算会让下一轮 nextEst 永远少算前缀，小窗口上"看着没满、一发就 400"。
			// 台账/帧的 before/after 仍是消息口径（与轮首同账），只有基线补齐前缀。
			int baseline = plan.after() + Math.max(0, request.systemTokens())
					+ Math.max(0, request.toolsTokens());
			// N-D：本次只做了 L1 的活、而谱系已在 L2/L3 时，"只升不降"必须成立（否则快照档位在这里
			// 回退成 L1，下一轮会重爬 L1→L2）。与轮内 L1 同处置：台账照记这次 L1 事件，
			// 但不改写状态档位（台账的 level 仍如实是本次动作 L1）。
			ContextTrigger.Level lineageNow = ContextTrigger.Level.fromWire(state.level());
			ContextState compacted = plan.action().ordinal() < lineageNow.ordinal() ? state
					: state.withCompaction(plan.action(), plan.turns(), plan.lostTokens(), plan.reason(), now)
						.withUsage(baseline, 0, state.turnNo(), true, now, request.model(), request.providerId(),
								request.systemTokens(), request.toolsTokens(), request.inputTokens());
			// I4：只有真实 L2 摘要成功才关闭摘要熔断；L1/L3（本地动作）不碰熔断
			if (plan.summarised()) {
				compacted = new CompactionFuse(this.properties.getContext()).onSuccess(compacted, now);
			}
			List<Map<String, Object>> ledgerEvents = new ArrayList<>(2);
			Map<String, Object> mainLedger = ledgerEvent(plan.action(), plan.before(), plan.after(), plan.turns(),
					plan.lostTokens(), plan.summaryChars(), plan.reason(), plan.clear(), request, now, compacted.circuit());
			mainLedger.put("commitEntryId", compactionId + ":main");
			ledgerEvents.add(mainLedger);
			boolean wasOpen = state.circuit() != null && !CompactionFuse.CLOSED.equals(state.circuit());
			if (wasOpen && plan.summarised()) {
				log.info("context summary circuit recovered sessionId={} level={} fails=0", request.sessionId(),
						plan.action().wire());
				Map<String, Object> circuitLedger = fuseEvent("circuit_closed", compacted, request, now, plan.before());
				circuitLedger.put("commitEntryId", compactionId + ":circuit-closed");
				ledgerEvents.add(circuitLedger);
			}
			ContextStateStore.CompactionCommitStatus commitStatus = this.store.commitCompaction(request.sessionId(),
					compactionId, compacted, ledgerEvents, compactionEvent(request, plan, compactionId, now));
			if (commitStatus == ContextStateStore.CompactionCommitStatus.CONFLICT) {
				if (attemptsLeft > 1) {
					ContextState latest = readStateForUpdate(request.sessionId());
					log.debug("context compaction state CAS conflict; merging onto latest version sessionId={} runId={} "
							+ "compactionId={} attemptsLeft={}", request.sessionId(), request.runId(), compactionId,
							attemptsLeft - 1);
					return commit(request, plan.withState(latest), attemptsLeft - 1);
				}
				log.warn("context compaction state CAS conflict sessionId={} runId={} compactionId={} "
						+ "(ledger/event not written; committed=false)", request.sessionId(), request.runId(), compactionId);
				return outcome(plan, request, state, false);
			}
			String eventId = compactionId;
			log.info("context compacted sessionId={} runId={} level={} eventId={} cause={} anchor={} before={} after={} "
					+ "turns={} lostTokens={} clearedPairs={} summaryChars={} reason={} ratio={}", request.sessionId(),
					request.runId(), plan.action().wire(), eventId, request.cause(), request.anchorToolCallId(),
					plan.before(), plan.after(), plan.turns(), plan.lostTokens(), plan.clear().cleared().size(),
					plan.summaryChars(), plan.reason(), String.format(Locale.ROOT, "%.3f", plan.decision().ratio()));
			// 日志链路批：压缩是"这一轮为什么变了/省了多少"的唯一解释项（与上面那行同源，字段可聚合）
			AiTrace.eventOn(request.runId(), request.sessionId(), "compaction", "level", plan.action().wire(),
					"reason", plan.reason(), "before", plan.before(), "after", plan.after(), "savedTokens",
					plan.savedTokens(), "lostTokens", plan.lostTokens(), "turns", plan.turns(), "clearedPairs",
					plan.clear().cleared().size(), "summaryChars", plan.summaryChars(), "limit",
					plan.decision().limitTokens());
			return outcome(plan, request, compacted, true, eventId, now);
		}
		catch (RuntimeException ex) {
			log.error("context compaction commit failed sessionId={} runId={} level={} compactionId={} error={} "
					+ "(retry the same Plan to complete state/ledger/facts idempotently; no context frame this turn)",
					request.sessionId(), request.runId(), plan.action().wire(), compactionId, ex.getMessage());
			return outcome(plan, request, state, false);
		}
	}

	private static Map<String, Object> compactionEvent(Request request, Plan plan, String compactionId, long now) {
		String runId = request.runId() == null || request.runId().isBlank() ? "run-unknown" : request.runId();
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("eventId", compactionId);
		event.put("compactionId", compactionId);
		event.put("runId", runId);
		event.put("atMs", now);
		event.put("cause", request.cause() == null ? "unknown" : request.cause());
		event.put("level", plan.action().wire());
		event.put("anchorToolCallId", request.anchorToolCallId());
		event.put("turns", plan.turns());
		event.put("lostTokens", plan.lostTokens());
		return event;
	}

	private static String compactionId(Request request, Plan plan) {
		String runId = request.runId() == null || request.runId().isBlank() ? "run-unknown" : request.runId();
		String material = runId + '\n' + plan.atMs() + '\n' + plan.action().wire() + '\n' + plan.before() + '\n'
				+ plan.after() + '\n' + plan.turns() + '\n' + plan.lostTokens() + '\n' + plan.reason() + '\n'
				+ plan.clear().clearedIds();
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
			return runId + ":c:" + plan.atMs() + ":" + HexFormat.of().formatHex(digest, 0, 8);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	/** 输入历史里最后一条工具结果的 toolCallId（无工具 ⇒ null；B3 锚点）。 */
	public static String lastToolCallId(List<Message> messages) {
		if (messages == null) {
			return null;
		}
		for (int i = messages.size() - 1; i >= 0; i--) {
			if (messages.get(i) instanceof org.springframework.ai.chat.messages.ToolResponseMessage tool) {
				for (int j = tool.getResponses().size() - 1; j >= 0; j--) {
					String id = tool.getResponses().get(j).id();
					if (id != null && !id.isBlank()) {
						return id;
					}
				}
			}
		}
		return null;
	}

	/**
	 * 轮末记账（真值回填）：{@code used/completion/turnNo/est/model/providerId} + 三个分项锚点。
	 *
	 * <p>provider 与 model 必须与本轮实际调用一致（解析分母按这一对精确取行）。
	 *
	 * <p>写失败只 WARN：记账是观测面，不能让一轮已成功的对话因 Redis 抖动变成失败。
	 */
	public void recordTurn(String sessionId, Long providerId, String model, int usedTokens, int completionTokens,
			int turnNo, boolean estimated, int systemTokens, int toolsTokens, int inputTokens) {
		try {
			this.store.recordTurn(sessionId, providerId, model, usedTokens, completionTokens, turnNo, estimated,
					System.currentTimeMillis(), systemTokens, toolsTokens, inputTokens);
		}
		catch (RuntimeException ex) {
			log.warn("context usage record write failed sessionId={} error={}", sessionId, ex.getMessage());
		}
	}

	private ContextState readStateForUpdate(String sessionId) {
		ContextState state = this.store.readStrict(sessionId);
		return state == null ? ContextState.EMPTY : state;
	}

	/** 当前会话状态（端点读；异常降级为空态）。 */
	public ContextState readState(String sessionId) {
		try {
			ContextState state = this.store.read(sessionId);
			return state == null ? ContextState.EMPTY : state;
		}
		catch (RuntimeException ex) {
			log.warn("context state read failed sessionId={} error={} (treating as empty)", sessionId, ex.getMessage());
			return ContextState.EMPTY;
		}
	}

	/** 本 run 之外的活跃 run（L1 不得清它们的工具结果）。 */
	private static Set<String> otherActiveRuns(Request request) {
		Set<String> ids = request.protectedRunIds();
		if (ids == null || ids.isEmpty()) {
			return Set.of();
		}
		Set<String> other = new LinkedHashSet<>(ids);
		if (request.runId() != null) {
			other.remove(request.runId());
		}
		return other;
	}

	private Plan noopPlan(Request request, ContextState state, int before, ContextTrigger.Decision decision,
			String reason) {
		log.debug("context compaction skipped sessionId={} runId={} reason={} limit={} ratio={}", request.sessionId(),
				request.runId(), reason, decision.limitTokens(), String.format(Locale.ROOT, "%.3f",
						decision.ratio()));
		return new Plan(state, ContextTrigger.Level.NONE, request.messages(), before, before, 0,
				new ToolResultClearing.Plan(List.of(), List.of(), 0), 0, 0, 0, reason, System.currentTimeMillis(),
				false, false, false, false, decision);
	}

	/** 摘要失败：不提交任何历史变更，只标记"提交时推进熔断"。 */
	private Plan failurePlan(Request request, ContextState state, int before, ContextTrigger.Decision decision,
			String reason) {
		log.error("context summary failed sessionId={} runId={} reason={} (history unchanged, turn continues)", request.sessionId(),
				request.runId(), reason);
		return new Plan(state, ContextTrigger.Level.NONE, request.messages(), before, before, 0,
				new ToolResultClearing.Plan(List.of(), List.of(), 0), 0, 0, 0, reason, System.currentTimeMillis(),
				true, false, true, false, decision);
	}

	/**
	 * 摘要失败的唯一落库点（由 {@link #commit} 在记忆 CAS 之后调用）：熔断计数 + 一行台账。
	 *
	 * <p>刻意不退回 L1/硬截断：一旦"失败就换个更狠的招"，同一份历史会在两个实例、两个时刻
	 * 走出不同分支，而且用户看到的是"压缩成功"。失败就是失败 —— 本轮照常发请求。
	 */
	private Outcome commitFuseFailure(Request request, Plan plan) {
		long now = plan.atMs();
		CompactionFuse fuse = new CompactionFuse(this.properties.getContext());
		ContextState failed = fuse.onFailure(plan.state(), now);
		boolean committed = true;
		try {
			failed = this.store.recordFuseFailure(request.sessionId(), failed,
					this.properties.getContext().getFuseThreshold(), this.properties.getContext().getFuseCooldown().toMillis(),
					now);
			this.store.appendLedger(request.sessionId(), fuseEvent(plan.reason(), failed, request, now, plan.before()));
		}
		catch (RuntimeException ex) {
			committed = false;
			log.warn("circuit-breaker counter write failed sessionId={} error={} (continuing uncounted)", request.sessionId(), ex.getMessage());
		}
		if (CompactionFuse.OPEN.equals(failed.circuit())) {
			log.warn("context summary circuit opened sessionId={} fails={} openUntil={} (L1 only from now on)", request.sessionId(),
					failed.fuseFails(), failed.openUntilMs());
		}
		return outcome(plan, request, failed, committed);
	}

	private Outcome noopOutcome(Request request, Plan plan, String reason) {
		ContextTrigger.Decision decision = plan == null
				? new ContextTrigger.Decision(ContextTrigger.Level.NONE, 0d, 0, 0, 0) : plan.decision();
		int before = plan == null ? ContextEstimator.estimateMessages(request.messages()) : plan.before();
		return new Outcome(ContextTrigger.Level.NONE, request.messages(), before, before, 0, 0, List.of(), reason,
				decision.limitTokens(), request.usedTokens(), false,
				plan == null ? CompactionFuse.CLOSED : plan.state().circuit(), decision.ratio(), decision.nextEst(),
				0, 0, 0, false, null, request.runId(), request.cause(), request.anchorToolCallId(), 0L);
	}

	private Outcome outcome(Plan plan, Request request, ContextState stateAfter, boolean committed) {
		return outcome(plan, request, stateAfter, committed, null, 0L);
	}

	private Outcome outcome(Plan plan, Request request, ContextState stateAfter, boolean committed, String eventId,
			long atMs) {
		if (!plan.changed()) {
			return new Outcome(ContextTrigger.Level.NONE, request.messages(), plan.before(), plan.before(), 0, 0,
					List.of(), plan.reason(), plan.decision().limitTokens(), request.usedTokens(), false,
					stateAfter == null ? CompactionFuse.CLOSED : stateAfter.circuit(), plan.decision().ratio(),
					plan.decision().nextEst(), 0, 0, 0, committed, null, request.runId(), request.cause(),
					request.anchorToolCallId(), 0L);
		}
		ContextState effective = stateAfter == null ? plan.state() : stateAfter;
		return new Outcome(plan.action(), plan.messages(), plan.before(), plan.after(), plan.savedTokens(),
				plan.clear().cleared().size(), plan.clear().clearedIds(), plan.reason(),
				plan.decision().limitTokens(), effective.usedTokens(), effective.est(), effective.circuit(),
				plan.decision().ratio(), plan.decision().nextEst(), plan.turns(), plan.lostTokens(),
				plan.summaryChars(), committed, eventId, request.runId(), request.cause(),
				request.anchorToolCallId(), atMs);
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

	private RollingSummarizer.Summarizer withHalfOpenReservation(RollingSummarizer.Summarizer delegate, Request request,
			ContextState[] summaryState, long now) {
		if (delegate == null) {
			return null;
		}
		java.util.concurrent.atomic.AtomicBoolean reservedOnce = new java.util.concurrent.atomic.AtomicBoolean();
		return (systemPrompt, userPrompt, runtime) -> {
			if (reservedOnce.compareAndSet(false, true)) {
				ContextState reserved = this.store.compareAndSet(request.sessionId(),
						new CompactionFuse(this.properties.getContext()).halfOpen(summaryState[0], now));
				if (reserved == null) {
					throw new HalfOpenReservationException();
				}
				summaryState[0] = reserved;
			}
			return delegate.summarize(systemPrompt, userPrompt, runtime);
		};
	}

	private static final class HalfOpenReservationException extends RuntimeException {
	}

	/**
	 * 给摘要器包一层"<b>第一次真正摘要之前</b>"回调（多分块摘要只触发一次）。
	 *
	 * <p>包装位置就是判据：只有摘要器真的被调用，回调才可能执行 —— L1/L3、未达阈值、摘要器不可用、
	 * 没有可摘要旧轮等路径根本走不到 {@code summarize}，因此<b>不会</b>误报"正在压缩"。
	 * 回调抛异常同样不改写摘要结果（只记 DEBUG）：它只负责阶段信号，不是判定面。
	 */
	private static RollingSummarizer.Summarizer withStart(RollingSummarizer.Summarizer delegate, Runnable hook) {
		if (delegate == null || hook == null) {
			return delegate;
		}
		java.util.concurrent.atomic.AtomicBoolean announced = new java.util.concurrent.atomic.AtomicBoolean();
		return (systemPrompt, userPrompt, runtime) -> {
			if (announced.compareAndSet(false, true)) {
				try {
					hook.run();
				}
				catch (RuntimeException ex) {
					log.debug("summary start callback failed (ignored, compaction unaffected) error={}", ex.getMessage());
				}
			}
			return delegate.summarize(systemPrompt, userPrompt, runtime);
		};
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
					log.debug("summary progress callback failed (ignored, compaction unaffected) error={}", ex.getMessage());
				}
			}
		};
	}

}
