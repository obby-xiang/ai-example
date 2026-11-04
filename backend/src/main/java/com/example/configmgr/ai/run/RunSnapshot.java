package com.example.configmgr.ai.run;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一轮对话的完整外置状态（{@code ai:run:<runId>} 的 JSON 值对象）。
 *
 * <p>
 * 字段划分即"续跑协议"的输入契约（SP-02 §5，两条硬规范）：
 * <ul>
 * <li>{@link #messageJson}：<b>完整请求历史</b>——含 {@code system} 两条与 {@code user}，
 * 以及每一轮落定的 {@code assistant(tool_calls)} 与 {@code role:tool} 配对。
 * 硬规范②（续跑必须重建完整历史）之所以成立，靠的就是这里存的是"模型当时真正看到的
 * 那串消息"，而不是官方循环自己那份"只有 assistant+tool"的续跑请求；</li>
 * <li>{@link #assistantContent} / {@link #inFlightToolCalls}：<b>挂起中的那一轮</b>。
 * 崩溃发生在这一轮中间时，凭这两项 + {@code ai:pending:<runId>} 的条目即可重建
 * {@code assistant(tool_calls)} 与 {@code role:tool} 消息继续跑；</li>
 * <li>{@link #context}：披露上下文（页面/任务/步骤），续跑据此重建同一份工具子集；</li>
 * <li>{@link #assistantMetadata}：挂起中那条 assistant 的<b>完整 metadata</b>（不透明；
 * 推理相关键与厂商私有未知键一并保真，续跑原样挂回）；</li>
 * <li>{@link #temperature}/{@link #topP}/{@link #maxTokens}/{@link #paramsLocked}：
 * <b>生效参数冻结</b>（首笔快照写死；同一 run 续跑不移志，新 run 才读新全局默认）；</li>
 * <li>{@link #status}：RUNNING / SUSPENDED / DONE / FAILED / CANCELLED —— 供重启后的发现与幂等判定。
 *     <b>取消是吸收态</b>：快照写入路径的原子守卫保证任何非取消状态都不得从 CANCELLED 转出
 *     （见 {@code RunStore#saveStatusUnlessCancelled}）。</li>
 * </ul>
 *
 * <p><b>滚动升级兼容</b>：{@code @JsonIgnoreProperties(ignoreUnknown = true)} —— 多实例滚动期新版本
 * 先写入的 additive 字段，旧版本反序列化时直接忽略，不会因为"多了一个键"读不出快照。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RunSnapshot {

	public static final String RUNNING = "RUNNING";

	public static final String SUSPENDED = "SUSPENDED";

	public static final String DONE = "DONE";

	public static final String FAILED = "FAILED";

	/** 被用户取消（终态：不再续跑，终帧为 {@code done{cancelled:true}}）。 */
	public static final String CANCELLED = "CANCELLED";

	/**
	 * 统一终态判定：DONE / FAILED / CANCELLED 为终态。
	 *
	 * <h2>为什么必须唯一函数</h2>
	 * 此前 {@code DONE|FAILED|CANCELLED} 这一组字面量散落在多处
	 * （{@code AiController#isTerminal}、{@code RunStore#save} 的 session→current 索引分支、
	 * {@code ResumeService} 的早退比较）——
	 * 任何一处漏改都会造成"一边认为终态、一边还在续跑"的分叉（例如终态轮被重新发现并驱动）。
	 *
	 * @param status 轮次状态串（可为 null / 未知串 → false）
	 */
	public static boolean isTerminal(String status) {
		return DONE.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
	}

	private String runId;

	private String sessionId;

	/**
	 * 客户端请求 id（停止闭环）：前端在 {@code /chat} 发送前生成并由镜像持久化，
	 * 后端用它把 {@code clientRequestId} 与本轮绑定（见 {@link ClientRequestRegistry}）。
	 *
	 * <p>它只是关联键，<b>不是 runId</b>，也不能用来指定 run。
	 */
	private String clientRequestId;

	/**
	 * 本轮归属的租户（首轮创建时从 {@code RequestContextUtils} 固定）。
	 *
	 * <p><b>只读不重算</b>：续跑/恢复/跨实例接管都发生在原请求结束之后，身份只能从这条记录读回，
	 * 不得按当前线程上下文"重新判定归属"（那会让接管方以自己的身份改写别人的轮次）。
	 */
	private long tenantId;

	/**
	 * 本轮归属的用户（{@code -1} = 系统账号）。
	 *
	 * <p>与 {@link #tenantId} 同源同寿：创建时一起固定，续跑只读。
	 */
	private long userId;

	/** 轮次状态，取值见本类常量。 */
	private String status = RUNNING;

	/** 已完成的工具轮数（挂起即 +1，用于"轮数上限"类判定与取证）。 */
	private int round;

	/** 本轮的尝试次数（韧性棒：&gt;1 说明发生过断流重试）。 */
	private int attempts;

	/**
	 * 本轮已消耗的工具调用步数（超时面治理批② 第 2 项；决策看板 {@code :14}）。
	 *
	 * <p><b>累计、跨挂起不重置</b>（LangGraph 口径）：上限 = {@code app.ai.resilience.max-tool-steps}
	 * （25），到顶后降级「部分完成收尾」。正因为"不重置"，这个计数必须<b>外置</b>在这里 ——
	 * 挂起后的续跑可能落在另一个进程上，进程内计数器会被重置成 0（五段挂起就能刷出 5×25 步）。
	 * {@code 0} = 还没用过工具。
	 */
	private int toolSteps;

	/** 是否由用户取消（终态判据之一，与 {@link #CANCELLED} 同步设置）。 */
	private boolean cancelled;

	/** 完整请求历史（每条一个 {@code MessageJsonCodec} JSON 字符串）。 */
	private List<String> messageJson = new ArrayList<>();

	/** 挂起中那一轮的 assistant 正文（通常为空串：工具轮的 assistant 正文就是空的）。 */
	private String assistantContent;

	/** 挂起中那一轮的工具调用（顺序即模型给出的顺序）。 */
	private List<PendingToolCall> inFlightToolCalls = new ArrayList<>();

	/** 披露上下文快照（{@code AiContext.toMap()}）。 */
	private Map<String, Object> context = new LinkedHashMap<>();

	private String finalText;

	private String error;

	/** 本实例标识：证明某次执行发生在"重启后的新进程"里。 */
	private String createdBy;

	private long createdAtMs;

	private long updatedAtMs;

	/** 续跑过的实例标识（按时间顺序）。 */
	private List<String> resumedBy = new ArrayList<>();

	/**
	 * 本轮使用的 provider（{@code ai_provider.id}）。
	 *
	 * <p>{@code null} = env 合成行（种子尚未落库）。续跑<b>按快照</b>取模型：provider 已删/停用
	 * ⇒ 409 {@code PROVIDER_GONE}，不静默换模型续跑（历史与 {@code done.model} 都不许说谎）。
	 */
	private Long providerId;

	/** 本轮使用的模型名（请求体 {@code model} 的原值）。 */
	private String modelId;

	/** 冻结的生效 temperature（null 也是冻结值：当时没有配置/请求未覆盖）。 */
	private Double temperature;

	/** 冻结的生效 top_p。 */
	private Double topP;

	/** 冻结的生效 max_tokens。 */
	private Integer maxTokens;

	/** 冻结的"参数被策略锁住"标记（续跑轮的 {@code done.paramsLocked} 不许改口）。 */
	private Boolean paramsLocked;

	/**
	 * 冻结的生效参数（L-26）。三值可为 null —— 那是"当时该项没有值"，不是"没冻结"。
	 */
	public record FrozenParams(Double temperature, Double topP, Integer maxTokens, boolean paramsLocked) {
	}

	/**
	 * 挂起中那一轮 assistant 的<b>完整 metadata</b>（不透明结构；Redis 容量/推理恢复批）。
	 *
	 * <p>挂起只外置正文与 tool_calls 时，续跑重建的那条 {@code assistant(tool_calls)} 会丢掉
	 * 推理相关元数据（{@code reasoningContent} 之外还可能有 {@code encrypted_content} /
	 * {@code signature} / {@code thinking} 等厂商私有键）。这里整份 map 原样保存、原样恢复，
	 * <b>不解析、不截断、不按 provider 分支</b>；未知键也因此不会丢。
	 * {@code null} = 挂起时上游没有 metadata。
	 */
	private Map<String, Object> assistantMetadata;

	/**
	 * 写入本条快照的<b>租约 epoch</b>：0 = 无租约写入（单实例/降级路径）。
	 *
	 * <p>它回答"这条状态是哪一任续跑权写的"：与 {@code ai:fence:run:<runId>} 对账即可判定
	 * 一次写回是否已被后来者超越（陈旧写不得覆盖新主人的状态）。0 = 无租约写入。
	 */
	private long runEpoch;

	/**
	 * 本轮上下文预算<b>武装时刻</b>（AR-10 第 3 条 / 批次2：armed <b>跨 park 持久化</b>；0 = 未武装）。
	 *
	 * <p>为什么必须进快照：armed 原先只活在 {@code ToolActivityBeacon}（进程内加速态），而 park 退出与
	 * 续跑入口都会清信标 ⇒ 挂起恢复后"收尾中"的事实被清零，模型可以再要一批工具、再清一次结果，
	 * 甚至再追加一条收尾 user 消息 —— 与"本轮预算"（不是会话预算、不是重置式）的语义相反。
	 *
	 * <p>语义口径：挂起<b>不清零</b>、恢复<b>继续累计</b>（{@link #budgetSkippedBatches} 一并带回），
	 * 恢复后到顶仍按既有 {@code MAX_ARMED_BATCHES} 收尾。它不是新的持久面：键族/TTL 与
	 * {@code ai:run:<runId>} 完全同寿（{@code app.ai.session-ttl}），不新增列、不碰 DB。
	 */
	private long budgetArmedAtMs;

	/**
	 * armed 之后已被拦下的工具批次数（{@code TurnBudgetPlan#MAX_ARMED_BATCHES} 的输入；AR-10 第 3 条）。
	 *
	 * <p>与 {@link #budgetArmedAtMs} 同寿同源：随快照落库 ⇒ 跨 park 不重置，恢复后继续累计。
	 */
	private int budgetSkippedBatches;

	/**
	 * 冻结本轮生效参数：<b>只在首轮创建快照时调用</b>，续跑不得再写。
	 *
	 * <p>不变量：三值与 {@code paramsLocked} 就是这一轮的"当时生效值" —— 包括 null。
	 * 续跑按它派生运行态（{@code ResilientChatService#runtimeForSnapshot}），
	 * 因此新 run 改全局默认不会改到旧 run。
	 *
	 * @throws IllegalArgumentException {@code frozen == null}（首笔快照必须冻结参数）
	 */
	public void freezeParams(FrozenParams frozen) {
		if (frozen == null) {
			throw new IllegalArgumentException("首笔快照必须冻结生效参数");
		}
		this.temperature = frozen.temperature();
		this.topP = frozen.topP();
		this.maxTokens = frozen.maxTokens();
		this.paramsLocked = frozen.paramsLocked();
	}

	/**
	 * 冻结值的取证视图（只读）。
	 *
	 * <p>存在的理由 = 执行信封（{@code ToolExecutionEnvelope.fromSnapshot}）要把"这一轮当时的生效
	 * 参数"原样带走，避免再散点抄四个字段；{@code paramsLocked} 的 null 与 false 在快照里本就不分
	 * （冻结口径把它归一成布尔），这里保持同一口径。
	 */
	public FrozenParams frozenParams() {
		return new FrozenParams(this.temperature, this.topP, this.maxTokens,
				Boolean.TRUE.equals(this.paramsLocked));
	}

	/**
	 * B2：本轮用户的<b>请求原文</b>（建快照时写一次）。
	 *
	 * <p>transcript 终态补写用户条目<b>只认它</b> —— 不从快照历史里挑"最后一条 USER"（那会把记忆窗口
	 * 里别的轮次的提问写成这一轮），也不按消息 metadata 推断归属。建快照时必填（非空白）。
	 */
	private String requestText;

	/**
	 * B2：本轮<b>请求起点</b>（建快照时写一次；= transcript 轮首用户条目的排序键）。
	 *
	 * <p>建快照时必填（&gt; 0）。实际排序键见
	 * {@link #transcriptUserTurnStartedAtMs()}（取请求起点与 {@code createdAtMs} 的较早者，时钟回拨
	 * 时也不会排到工具卡之后）；助手/工具条目<b>始终</b>用 {@link #createdAtMs}、不做任何偏移 ——
	 * 排序只保证"本 run 的用户条目早于本 run 的工具条目"，不给工具键加 1ms（那会让相邻 run 的条目
	 * 在跨轮排序里交错）。
	 */
	private long turnStartedAtMs;

	/** 取证视图（不含 messageJson 正文）。 */
	public Map<String, Object> asMap() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runId", this.runId);
		out.put("sessionId", this.sessionId);
		// 身份归属
		out.put("tenantId", this.tenantId);
		out.put("userId", this.userId);
		out.put("status", this.status);
		out.put("round", this.round);
		out.put("attempts", this.attempts);
		out.put("toolSteps", this.toolSteps);
		out.put("cancelled", this.cancelled);
		out.put("messageCount", this.messageJson.size());
		out.put("inFlight", this.inFlightToolCalls.size());
		out.put("assistantContent", this.assistantContent);
		out.put("finalText", this.finalText);
		out.put("error", this.error);
		out.put("createdBy", this.createdBy);
		out.put("createdAtMs", this.createdAtMs);
		out.put("updatedAtMs", this.updatedAtMs);
		out.put("resumedBy", this.resumedBy);
		out.put("runEpoch", this.runEpoch);
		// 模型身份
		out.put("providerId", this.providerId);
		out.put("modelId", this.modelId);
		// 生效参数：四值是这一轮当时的生效值（null 也是冻结值）
		out.put("temperature", this.temperature);
		out.put("topP", this.topP);
		out.put("maxTokens", this.maxTokens);
		out.put("paramsLocked", this.paramsLocked);
		// AR-10 第 3 条：armed 跨 park 持久化的两个事实；0 = 未武装
		out.put("budgetArmedAtMs", this.budgetArmedAtMs);
		out.put("budgetSkippedBatches", this.budgetSkippedBatches);
		// B2：请求原文只报字符数（取证够用，不回显正文）
		out.put("requestTextChars", this.requestText == null ? 0 : this.requestText.length());
		return out;
	}

	/**
	 * transcript 里本轮<b>用户条目</b>的排序键：请求起点与 {@code createdAtMs} 的<b>较早者</b>。
	 *
	 * <p>为什么取较早者：读侧按 {@code turnStartedAtMs} 稳定排序、同值保插入顺序，而终态补写的用户
	 * 条目是<b>后写</b>的 —— 键一旦不早于工具卡（{@code createdAtMs}）就会排到它们之后。正常时钟下
	 * 请求起点早于建快照，原值不变；<b>时钟回拨</b>（起点晚于创建）或同毫秒时取 {@code createdAtMs - 1}，
	 * 保证补写的用户条目仍在工具卡之前。
	 * 只动用户键、工具键保持 {@link #transcriptToolTurnStartedAtMs()}（= {@code createdAtMs}），
	 * 跨 run 顺序不变。
	 */
	public long transcriptUserTurnStartedAtMs() {
		if (this.createdAtMs <= 0L || this.turnStartedAtMs <= 0L) {
			throw new IllegalStateException("轮次快照缺少 createdAtMs/turnStartedAtMs runId=" + this.runId);
		}
		return Math.min(this.turnStartedAtMs, this.createdAtMs - 1L);
	}

	/**
	 * transcript 里本轮<b>助手/工具条目</b>的排序键 = {@code createdAtMs}（不做任何偏移）。
	 *
	 * <p>用户条目用 {@link #transcriptUserTurnStartedAtMs()}（严格更早），因此"轮首写失败、终态才补写"
	 * 的用户条目不会排到工具卡之后；工具键保持原值，避免给相邻 run 制造 1ms 的交错。
	 */
	public long transcriptToolTurnStartedAtMs() {
		if (this.createdAtMs <= 0L) {
			throw new IllegalStateException("轮次快照缺少 createdAtMs runId=" + this.runId);
		}
		return this.createdAtMs;
	}

}
