package com.example.configmgr.ai.run;

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
 * <li>{@link #paramsVersion} 与 {@link #temperature}/{@link #topP}/{@link #maxTokens}/{@link #paramsLocked}：
 * <b>L-26 的生效参数冻结</b>（同一 run 续跑不移志；新 run 才读新全局默认）；</li>
 * <li>{@link #status}：RUNNING / SUSPENDED / DONE / FAILED —— 供重启后的发现与幂等判定。</li>
 * </ul>
 */
@Data
public class RunSnapshot {

	public static final String RUNNING = "RUNNING";

	public static final String SUSPENDED = "SUSPENDED";

	public static final String DONE = "DONE";

	public static final String FAILED = "FAILED";

	/** 被用户取消（终态：不再续跑，终帧为 {@code done{cancelled:true}}）。 */
	public static final String CANCELLED = "CANCELLED";

	/**
	 * 终态串的<b>归一别名</b>（T3-7 / A13）：轮次状态本身没有 {@code REJECTED}
	 * （条目级的 {@code PendingToolCall.REJECTED} 与前端 {@code rejected} 语义<b>不动</b>），
	 * 但终端判定函数把该串归入"失败类终态" —— 于是任何来源传进来的 {@code REJECTED}
	 * 都不会被当成"还能续跑的活轮"。
	 */
	private static final String REJECTED_ALIAS = "REJECTED";

	/**
	 * 统一终态判定（T3-7 / A14）：DONE / FAILED / CANCELLED（+ {@code REJECTED} 归一）为终态。
	 *
	 * <h2>为什么必须唯一函数</h2>
	 * 此前 {@code DONE|FAILED|CANCELLED} 这一组字面量散落在三处
	 * （{@code AiController#isTerminal}、{@code RunStore#save} 的 session→current 索引分支、
	 * {@code ResumeService} 的早退比较与 {@code listRuns} 的 resumeable 组合）——
	 * 任何一处漏改都会造成"一边认为终态、一边还在续跑"的分叉（例如终态轮被重新发现并驱动）。
	 *
	 * @param status 轮次状态串（可为 null / 未知串 → false）
	 */
	public static boolean isTerminal(String status) {
		return DONE.equals(status) || FAILED.equals(status) || CANCELLED.equals(status)
				|| REJECTED_ALIAS.equals(status);
	}

	private String runId;

	private String sessionId;

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
	 * 旧快照没有本字段 ⇒ 0（Jackson 缺省），与"还没用过工具"同义。
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
	 * 本轮使用的 provider（模型配置专项批①；{@code ai_provider.id}）。
	 *
	 * <p>
	 * {@code null} = 旧快照（批① 之前写的）或 env 合成行（种子尚未落库）。批③ 的续跑将<b>按快照</b>
	 * 取模型：provider 已删/停用 ⇒ 409 {@code PROVIDER_GONE}，不静默换模型续跑（历史与
	 * {@code done.model} 都不许说谎）。
	 */
	private Long providerId;

	/** 本轮使用的模型名（请求体 {@code model} 的原值；{@code null} = 旧快照）。 */
	private String modelId;

	/** 生效参数冻结的<b>版本号</b>（L-26）：0 = 未冻结的旧快照；1 = 已冻结。 */
	public static final int PARAMS_VERSION_FROZEN = 1;

	/**
	 * 生效参数冻结版本（L-26 / 业界裁决）。
	 *
	 * <p><b>为什么需要版本号而不是"看字段是否为空"</b>：冻结值允<b>为 null</b>（首轮当时就没有
	 * temperature，或 {@code providerId=null} 的 env 合成行）。全局 Jackson 是 {@code non_null}
	 * ⇒ 冻结的 null 在 JSON 里根本不存在，与"旧快照从来没有这些字段"逐字节同形。
	 * 于是"冻结过"只能由本字段表达：{@code 0} = 旧快照（续跑按当前默认 + WARN），
	 * {@code >=1} = 这些字段（含 null）就是当时的生效值，续跑必须照抄。
	 */
	private int paramsVersion;

	/** 冻结的生效 temperature（{@code paramsVersion=1} 时 null 也是冻结值）。 */
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
	 * {@code null} = 挂起时上游没有 metadata（旧快照/旧调用方）。
	 */
	private Map<String, Object> assistantMetadata;

	/**
	 * 写入本条快照的<b>租约 epoch</b>（B23L1 批②a）：0 = 无租约写入（单实例/降级路径/旧数据）。
	 *
	 * <p>它回答"这条状态是哪一任续跑权写的"：与 {@code ai:fence:run:<runId>} 对账即可判定
	 * 一次写回是否已被后来者超越（陈旧写不得覆盖新主人的状态）。旧 JSON 没有本字段 ⇒ 0。
	 */
	private long runEpoch;

	/**
	 * 冻结本轮生效参数（L-26）：<b>只在首轮创建快照时调用</b>，续跑不得再写。
	 *
	 * <p>不变量：{@code paramsVersion = 1} 之后，三值与 {@code paramsLocked} 就是这一轮的
	 * "当时生效值" —— 包括 null。续跑按它派生运行态（{@link #paramsFrozen()} 的消费方：
	 * {@code ResilientChatService#runtimeForSnapshot}），因此新 run 改全局默认不会改到旧 run。
	 */
	public void freezeParams(FrozenParams frozen) {
		if (frozen == null) {
			return;
		}
		this.paramsVersion = PARAMS_VERSION_FROZEN;
		this.temperature = frozen.temperature();
		this.topP = frozen.topP();
		this.maxTokens = frozen.maxTokens();
		this.paramsLocked = frozen.paramsLocked();
	}

	/** 本快照是否冻结过生效参数（{@code false} = L-26 之前的旧快照，续跑走旧行为 + WARN）。 */
	public boolean paramsFrozen() {
		return this.paramsVersion >= PARAMS_VERSION_FROZEN;
	}

	/** 取证视图（不含 messageJson 正文）。 */
	public Map<String, Object> asMap() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runId", this.runId);
		out.put("sessionId", this.sessionId);
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
		// 模型身份（模型配置专项批①，additive）：旧快照为 null，取证口径与 runEpoch 一致
		out.put("providerId", this.providerId);
		out.put("modelId", this.modelId);
		// L-26 生效参数（additive）：paramsVersion=0 = 旧快照（未冻结）；>=1 时四值是当时的生效值
		out.put("paramsVersion", this.paramsVersion);
		out.put("temperature", this.temperature);
		out.put("topP", this.topP);
		out.put("maxTokens", this.maxTokens);
		out.put("paramsLocked", this.paramsLocked);
		return out;
	}

}
