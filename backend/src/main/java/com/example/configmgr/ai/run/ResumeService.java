package com.example.configmgr.ai.run;

import com.example.configmgr.common.ErrorBodyFactory;
import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.context.ContextCompactor;
import com.example.configmgr.ai.context.ContextEstimator;
import com.example.configmgr.ai.context.ContextState;
import com.example.configmgr.ai.context.MemoryWindow;
import com.example.configmgr.ai.context.SystemMessages;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.history.AiChatHistoryRecorder;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

/**
 * 续跑服务（S4.2 §2 {@code run/ResumeService}，SM-04「重启续跑全链路」的本体）。
 *
 * <h2>硬规范②：必须重建完整历史</h2>
 * 重建的输入一律是 {@link RunSnapshot#getMessageJson()}（模型当时真正看到的那串消息：
 * system ×2 + user + 历轮 {@code assistant(tool_calls)}/{@code tool}），再加上
 * <b>挂起那一轮</b>的 {@code assistant(tool_calls)}（取自快照 in-flight + 待决条目的 id/type/name/arguments）
 * 与按同一顺序拼出的 {@code ToolResponseMessage}。绝不复用官方循环自己那份"只有 assistant+tool"的续跑请求。
 *
 * <h2>补执行恰好一次</h2>
 * 逐条目结算（{@link SpToolCallingManager#resolvePending}）：
 * <ul>
 * <li>台账里已有该 {@code toolCallId} → 复用结果，不再执行（跨进程防重放）；</li>
 * <li>{@code APPROVED 且 executed=false}（SP-02 E2 的"已放行未执行"窗口，例如放行后进程死亡）
 * → 本次<b>补执行</b>，认领与台账都记在新实例名下；</li>
 * <li>{@code executed=true} → 说明上一实例已完成，直接复用其 {@code resultText}；</li>
 * <li>其余一旦有结论的条目（{@code REJECTED/TIMEOUT/FRONTEND_RESULT/FRONTEND_CANCELLED}、
 * 入参被安全闸拒绝的 {@code REJECTED_ARGUMENTS}、越 scope 的 {@code BLOCKED}）
 * → 以"未执行/已回灌"语义回填，<b>不执行</b>。</li>
 * </ul>
 *
 * <h2>准入与互斥</h2>
 * <ul>
 * <li>状态 {@code DONE} → {@code ALREADY_DONE}（重复续跑不发模型请求，幂等）；</li>
 * <li>状态 {@code RUNNING} → 409（无进程在跑却停在 RUNNING = 僵尸轮，见【待裁决】）；</li>
 * <li>仍有 {@code PENDING} 的<b>外部</b>挂起项（确认门/前端工具）→ 409 {@code PENDING_UNRESOLVED}，
 * 不改任何状态、不发模型请求；</li>
 * <li><b>续跑权租约</b>（B23L1 批②b，取代 {@code ai:resume:lock:}）：{@code ai:lease:run:<runId>}
 * 的持有者才是本轮的续跑主人。旧签名自抢（抢不到 ⇒ {@code LEASE_HELD_ELSEWHERE}）；带租约重载
 * 只校验归属（调用方就是持有者）—— 见 {@link #resume(String, boolean, LeaseService.Lease)}。
 * 进程内 {@code resuming} 表保留：同进程并发续跑仍然只有一个能进（{@code IN_PROGRESS}）。</li>
 * <li><b>写权限权威判定</b>（续租 fencing 收口批）：入口先问
 * {@link RunStore#runLeaseAuthority(String)}。{@code LOST}（确定丢租）/ {@code UNAVAILABLE}
 * （设施不可确认）⇒ 只读、<b>不写 RUNNING、不压缩、不派发</b> —— 否则会跑一轮"答得出、存不下"
 * 的幽灵续跑（写口已按同一判据拒绝）。只有 {@code HELD}（fence 写）与 {@code NEVER_HELD}
 * （无租约语义且设施正常）才推进。</li>
 * <li><b>会话门</b>（park 批）：三处入口（本类 / 启动扫描 / 死卡兜底）都汇入本类，在写 RUNNING
 * <b>之前</b>按一次性 token acquire；取不到 ⇒ {@code SESSION_BUSY}，派发执行器零调用。</li>
 * <li><b>SYSTEM 恢复</b>（续跑保 system 批）：重建历史后先把 SYSTEM 归位/重建（{@link #restoreSystem}），
 * 窗口与压缩只处理非 SYSTEM 部分。</li>
 * </ul>
 */
@Slf4j
@Service
public class ResumeService {

	/** 续跑原因（日志面口径，{@code turn_start.resumeReason}）：启动扫描（快照 SUSPENDED）。 */
	public static final String RESUME_STARTUP = "startup";

	/** 续跑原因：运维/用户显式调 {@code POST /api/ai/runs/{runId}/resume}。 */
	public static final String RESUME_MANUAL = "manual";

	/** 续跑原因：启动/扫描期接管"状态仍 RUNNING 但已超龄"的死卡（僵尸轮）。 */
	public static final String RESUME_DEADCARD = "deadcard";

	/** 续跑原因：其余续跑入口（决策回灌、挂起重挂、调试直驱）—— "未标注原因"的兜底口径。 */
	public static final String RESUME_REATTACH = "reattach";

	/**
	 * 续跑异常的<b>用户面</b>短句（R-08 白名单）。
	 *
	 * <p>原异常消息（上游 body / 客户端栈里带出的内部信息）只进日志；SSE {@code error} 帧、
	 * 快照/界面能看到的只有这一句。与 {@code ResilientChatService} 的 {@code AI_RESUME_FAILED}
	 * 白名单原文一致（同一码同一句，不另造第二套文案）。
	 */
	public static final String AI_RESUME_FAILED_MESSAGE = "续跑失败，请重试。";

	/** en 白名单兜底（{@code messages_en} 缺失时仍不裸露原文）。 */
	public static final String AI_RESUME_FAILED_MESSAGE_EN = "Could not resume the previous response. Please try again.";

	/**
	 * {@code PROVIDER_GONE} 的用户面短句：与 409 响应体的 {@code message} 同一句，
	 * 终帧 {@code code=AI_PROVIDER_GONE} 的文案也取它（一处文案，两处出口）。
	 */
	public static final String PROVIDER_GONE_MESSAGE = "该轮使用的模型端点已被删除或停用，无法按原模型续跑；请重新发送一轮。";

	/** en 白名单兜底（{@code messages_en} 缺失时仍不裸露内部信息）。 */
	public static final String PROVIDER_GONE_MESSAGE_EN = "The model endpoint this turn used has been removed or disabled, "
			+ "so it cannot be resumed with the same model. Please send a new message.";

	private final RunStore store;

	private final RunRegistry registry;

	private final MessageJsonCodec codec;

	private final ResilientChatService chatService;

	private final SpToolCallingManager toolCallingManager;

	private final ThreadPoolTaskExecutor suspendExecutor;

	/**
	 * <b>保留仅为构造器兼容，锁已摘除</b>（M5）：{@code ai:resume:lock:} 在批②b 整体并入续跑权租约
	 * （GET-比对-DEL 那条非原子释放会删掉别人的锁）；本字段已无任何读取者，留着是为了不动 10 参
	 * 兼容构造器（{@code ResumeSkipCascadeTest}/{@code ResumeEnvelopeShapeTest} 等既有用例零改动）。
	 * 下一次可以改签名的窗口应直接删掉它。
	 */
	private final StringRedisTemplate redis;

	private final AiProperties properties;

	private final CancellationRegistry cancellations;

	/** T2：重建历史的 {@code role:tool} 也要过同一份模型侧上限（口径与首轮一致，不因续跑而变）。 */
	private final ToolResultLimiter limiter;

	/**
	 * 上下文压缩统一入口（上下文统计与压缩专项批①）。
	 *
	 * <p><b>字段注入</b>：{@link #ResumeService} 的构造器被四份既有用例直接 {@code new}
	 * （{@code ResumeServiceLeaseTest} / {@code ResumeEnvelopeShapeTest} 等），改签名等于要求它们跟改。
	 * 未注入时续跑不压缩（"未装配"而不是"永远压不动"）。
	 */
	@Autowired(required = false)
	private ContextCompactor contextCompactor;

	/**
	 * 模型配置快照（批③ 的 {@code PROVIDER_GONE} 判据）。
	 *
	 * <p>同为<b>字段注入</b>：四份既有用例直接 {@code new ResumeService(...)}，改构造器签名等于要求
	 * 它们跟改。未注入时不做 provider 存在性判定（"没有配置设施"→ 走默认口径，与批③ 之前等价）。
	 */
	@Autowired(required = false)
	private AiConfigService aiConfigService;

	/**
	 * 同 sessionId 单值门（人工等待 park 批）。
	 *
	 * <p>park 时首轮任务结束，{@code AiController} 的 finally 会释放会话门 —— 停等期间门是空的；
	 * 恢复方（本类）必须<b>重新 acquire</b>，否则"用户在停等期间又发了一轮"会与本轮并发。
	 * 抢不到（被另一轮占着）⇒ {@link Outcome#SESSION_BUSY}，由派发方有界重派发，绝不静默丢决策。
	 *
	 * <p>字段注入而非构造器参数：本类构造器被多份既有用例直接 {@code new}。
	 */
	@Autowired(required = false)
	private SessionGate sessionGate;

	/**
	 * 用户面白名单文案的 locale 来源（I-5）。
	 *
	 * <p>字段注入与既有可选依赖同姿态（不改 10 参构造器）。未注入（直接 {@code new} 的单测）时，
	 * en 回落 {@link #AI_RESUME_FAILED_MESSAGE_EN}，仍不暴露异常原文。
	 */
	@Autowired(required = false)
	private MessageSource messageSource;

	/**
	 * 完整对话历史写入适配器（第二阶段）。字段注入保持 10 参构造器与既有直接 new 的用例零改动；
	 * 缺席时只跳过历史补充，续跑语义不变。
	 */
	@Autowired(required = false)
	private AiChatHistoryRecorder historyRecorder;

	/** 进程内续跑互斥（同进程并发续跑的唯一闸；租约管的是跨实例）。 */
	private final Map<String, Boolean> resuming = new ConcurrentHashMap<>();

	public ResumeService(RunStore store, RunRegistry registry, MessageJsonCodec codec, ResilientChatService chatService,
			SpToolCallingManager toolCallingManager, @Qualifier("aiRunExecutor") ThreadPoolTaskExecutor suspendExecutor,
			StringRedisTemplate redis, AiProperties properties, CancellationRegistry cancellations,
			ToolResultLimiter limiter) {
		this.store = store;
		this.registry = registry;
		this.codec = codec;
		this.chatService = chatService;
		this.toolCallingManager = toolCallingManager;
		this.suspendExecutor = suspendExecutor;
		this.redis = redis;
		this.properties = properties;
		this.cancellations = cancellations;
		this.limiter = limiter;
	}

	public enum Outcome {

		ACCEPTED, NOT_FOUND, ALREADY_DONE, ALREADY_RUNNING, PENDING_UNRESOLVED, IN_PROGRESS, POOL_SATURATED,
		ALREADY_CANCELLED,

		/**
		 * C2：取消状态读不出来（Redis 故障/退避）。续跑是"开始执行"的副作用入口，按 fail-closed
		 * 拒绝，但必须是 typed 结局（对外 503），不能再以 RuntimeException 穿透。
		 */
		CANCEL_STATE_UNKNOWN,

		/** C2：待决条目本体读不出来（不是"没有未决"）；续跑是副作用入口，按未知拒绝。 */
		STATE_UNKNOWN,

		/**
		 * B23L1 批②b：本轮的续跑权租约在<b>别的实例</b>手里（或调用方给的租约已过期）。
		 *
		 * <p>它不是错误：说明那一轮已经有主（在等卡 / 在驱动 / 正在收尾），本实例什么都不该做。
		 * 对外统一 409（{@code AiController#resume} 的 switch）。
		 */
		LEASE_HELD_ELSEWHERE,

		/**
		 * 批③：快照里的 provider 已不可用（删除/停用/密钥不可解/模型已不在 allowlist）。
		 *
		 * <p>它不是"没找到"（轮次还在），也不是 503：这一轮<b>没法按原样</b>继续 —— 换模型续跑会让
		 * 历史与 {@code done.model} 说谎，故对外 409 + 稳定码 {@code PROVIDER_GONE}。
		 */
		PROVIDER_GONE,

		/**
		 * 人工等待 park 批：同一会话当前有<b>另一轮</b>在跑（park 停等期间用户又发了一轮）。
		 *
		 * <p>决策已经落库、回执帧已发 —— 只差"驱动权"。本结局表示<b>本拍不驱动</b>（不得并发同会话两轮），
		 * 由派发方按 {@code app.ai.park.max-dispatch-retries} <b>有界重派发</b>；
		 * 对 HTTP 面是 409（{@code SESSION_BUSY}，与"会话已有一轮"同一语义）。
		 */
		SESSION_BUSY;

		/**
		 * 该结局下，<b>自抢租约的调用方</b>是否可以归还租约（<b>归还白名单</b>，默认不还）。
		 *
		 * <h2>判据</h2>
		 * 调用方已经通过 {@code created} 证明了一件事：<b>抢租那一刻全集群没有人持有这一轮的租约</b>
		 * （否则它只会是重入命中、{@code created=false}，那条路径根本不还租）。所以"还有别人在管"只可能
		 * 来自<b>本进程内后到的并发调用方</b> —— 而那种情形下本线程的结局必然是
		 * {@link #IN_PROGRESS}（{@code resuming} 互斥被占）。
		 *
		 * <ul>
		 * <li>{@link #IN_PROGRESS}：<b>本进程已有胜方在跑</b>，它用的是<b>同一 token</b> ——
		 *     还租就是删它的续跑权（L-19：创建者输掉互斥时，"创建者"身份不等于"可以删"）⇒ <b>不还</b>；</li>
		 * <li>{@link #LEASE_HELD_ELSEWHERE}：租约在别人手里（本方法只可能是 verifyHeld 判丢后返回它）⇒ 更不该碰；</li>
		 * <li>{@link #NOT_FOUND} / {@link #ALREADY_DONE} / {@link #ALREADY_CANCELLED}：轮不存在或已收尾，
		 *     没有任何执行者 ⇒ 还；</li>
		 * <li>{@link #ALREADY_RUNNING} / {@link #PENDING_UNRESOLVED}：快照停在 RUNNING（僵尸轮）或
		 *     外部卡未决 —— {@code created=true} 已说明<b>现在没有别的实例在驱动/等待</b>，
		 *     这批租约没有接管者 ⇒ 还（否则要白等一个 TTL，启动扫描/重试才拿得回来）；</li>
		 * <li>{@link #POOL_SATURATED}：池饱和 = 派发失败 = 无人接管（与 {@code ConfirmGate} 的
		 *     {@code EXECUTOR_SATURATED} 必须还租同一条红线）⇒ 还；</li>
		 * <li>{@link #ACCEPTED}：调用方把租约<b>交续租调度器</b>（不是"还"）⇒ 本方法返回 false。</li>
		 * </ul>
		 *
		 * <p>白名单（默认不还）而不是黑名单：将来新增结局时，默认落在"不还"一侧 —— 漏还是一个 TTL 的
		 * 可用性问题，错还是一次双驱动/丢 fencing 的正确性问题。
		 */
		public boolean releasesLease() {
			return this == NOT_FOUND || this == ALREADY_DONE || this == ALREADY_CANCELLED
					|| this == ALREADY_RUNNING || this == PENDING_UNRESOLVED || this == POOL_SATURATED
					|| this == PROVIDER_GONE || this == CANCEL_STATE_UNKNOWN || this == STATE_UNKNOWN
					// park 批：会话被别的轮占着 ⇒ 本拍没有驱动者，租约还给派发方（重试时再抢）
					|| this == SESSION_BUSY;
		}

	}

	public record ResumeResult(Outcome outcome, Map<String, Object> body) {
	}

	/**
	 * <b>取消收尾窄入口</b>（{@link #cancelConfirmed(String)}）的三态以上结局。
	 *
	 * <p>与 {@link Outcome} 分开：那条枚举是"续跑/驱动"的用户面结局（HTTP 状态码映射在
	 * {@code AiController} 的 switch 上）。本枚举只服务"已确认取消的收尾"，多出来的
	 * {@link #PERSIST_DENIED} / {@link #UNAVAILABLE} 的含义是"这拍没落定，稍后再来"，
	 * 绝不能混进续跑结局表（否则一次抖动会被对外报成终局）。
	 */
	public enum CancelFinalize {

		/** 本次把终态与终帧补齐（取消事实成立，收尾已落定）。 */
		FINALIZED,

		/**
		 * 终态轮次无可收尾：<b>DONE/FAILED</b> 直接判终态（绝不改写）；<b>CANCELLED</b> 仅在
		 * 终帧已归档时幂等跳过（归档里没有终帧 ⇒ 仍需补齐）。
		 *
		 * <p><b>DONE/FAILED 也走这里</b>：延迟交棒绝不能把已经完成的轮次改写成 CANCELLED
		 * （{@code RunStore#save} 只查租约写权限、不查原状态）。
		 */
		ALREADY_TERMINAL,

		/**
		 * 取消事实此刻不成立（{@code ai:cancel} 看不到）而快照仍非终态：<b>不可知</b>，
		 * 交棒按有界重试处理；<b>绝不</b>因此驱动这一轮（不重建、不发起模型/工具）。
		 */
		CANCEL_FACT_UNCONFIRMED,

		/** 取消状态读不出来（Redis 故障/退避）：这拍不推进，交给有界重试。 */
		CANCEL_UNKNOWN,

		/** 轮不存在（已删除）：没有可收尾的对象。 */
		NO_RUN,

		/** 续跑租约在别的实例手里（或已过期）：不抢、不覆盖。 */
		LEASE_HELD_ELSEWHERE,

		/** 租约设施不可用：无法取得写权限，这拍不写。 */
		UNAVAILABLE,

		/** 本进程已有续跑/取消收尾在推进这一轮（{@code resuming} 互斥被占）：这拍不并发，稍后重试。 */
		IN_PROGRESS,

		/** 同 sessionId 的单值门被另一轮占着：这拍不推进，稍后重试。 */
		SESSION_BUSY,

		/** 收尾跑了但终帧仍未归档（写门仍拒写/读不可知）：等恢复后重试。 */
		PERSIST_DENIED;

	}

	/** 发现通道：全部仍存活的轮次（重启后"ResumeService 发现 pending"的入口）。 */
	public List<Map<String, Object>> listRuns() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (String runId : this.store.allRunIds()) {
			RunSnapshot snapshot = this.store.get(runId);
			if (snapshot == null) {
				continue;
			}
			// C2：续跑入口的清单用非抛读口；guard 读失败时仍能拿到原始条目并按 PENDING 保守处理。
			List<PendingToolCall> pendings = this.store.pendingsLenient(runId);
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("runId", runId);
			item.put("sessionId", snapshot.getSessionId());
			item.put("status", snapshot.getStatus());
			item.put("round", snapshot.getRound());
			item.put("messageCount", snapshot.getMessageJson().size());
			item.put("createdBy", snapshot.getCreatedBy());
			item.put("resumedBy", snapshot.getResumedBy());
			item.put("updatedAtMs", snapshot.getUpdatedAtMs());
			item.put("pending", pendings.stream().map(PendingToolCall::asMap).toList());
			item.put("pendingUnresolved", pendings.stream()
				.filter(pending -> PendingToolCall.PENDING.equals(pending.getStatus()))
				.map(PendingToolCall::getToolCallId)
				.toList());
			item.put("ledgerCounts", this.store.ledgerCounts(runId));
			// 发现态的"可续跑性"判定（启动自动续跑与运维端点共用同一口径）：
			// 取消标志（Redis 为准）> 终态 > 未决外部输入 > 僵尸 RUNNING（超龄）。
			// C2：发现/列表面 UNKNOWN 按"未取消 + 计数继续"（这里只是展示可续跑性，没有副作用）。
			CancellationRegistry.CancelState cancelState = this.cancellations.cancelState(runId);
			boolean cancelled = cancelState == CancellationRegistry.CancelState.CANCELLED;
			if (cancelState == CancellationRegistry.CancelState.UNKNOWN) {
				log.debug("发现态取消状态未知，按未取消继续展示 runId={}", runId);
			}
			long activeAtMs = activeAtMs(snapshot);
			boolean zombie = RunSnapshot.RUNNING.equals(snapshot.getStatus())
					&& System.currentTimeMillis() - activeAtMs > this.properties.getResume().getZombieAge()
						.toMillis();
			// I-4 / M-3：SUSPENDED 等人期间没有业务帧，心跳才是"还在等"的证据。
			// 对外展示的 activeAgeMs 按状态取口径：RUNNING = 业务年龄（僵尸判据同源），
			// SUSPENDED = max(业务, 心跳) 的年龄 —— 否则"挂起三天"会被读成"三天没动"。
			long heartbeatAtMs = this.store.lastBeatAtMs(runId);
			long livenessAtMs = RunSnapshot.SUSPENDED.equals(snapshot.getStatus())
					? Math.max(activeAtMs, heartbeatAtMs) : activeAtMs;
			item.put("activeAtMs", activeAtMs);
			item.put("activeAgeMs", System.currentTimeMillis() - livenessAtMs);
			item.put("lastHeartbeatAtMs", heartbeatAtMs);
			item.put("zombieAgeThresholdMs", this.properties.getResume().getZombieAge().toMillis());
			List<String> unresolvedExternal = pendings.stream()
				.filter(pending -> PendingToolCall.PENDING.equals(pending.getStatus()))
				.filter(pending -> !PendingToolCall.KIND_BACKEND.equals(pending.getKind()))
				.map(PendingToolCall::getToolCallId)
				.toList();
			item.put("cancelled", cancelled);
			item.put("zombieRunning", zombie);
			// T3-7（A14）：可续跑性组合改用统一终态判定（含 REJECTED 归一），不再散点比对三个字面量
			item.put("resumeable", !cancelled && unresolvedExternal.isEmpty()
					&& !RunSnapshot.isTerminal(snapshot.getStatus())
					&& (zombie || !RunSnapshot.RUNNING.equals(snapshot.getStatus())));
			item.put("unresolvedExternal", unresolvedExternal);
			out.add(item);
		}
		return out;
	}

	/**
	 * 该轮的"活跃时刻"（R3 裁决 / R1-09）：取<b>较新者</b> ——
	 * 快照 {@code updatedAtMs}（工具落定/挂起/终态写入的时刻）与
	 * {@link RunStore#lastActivityAtMs(String)}（最后一条<b>非心跳</b>业务帧的时刻）。
	 *
	 * <p>
	 * 为什么必须双条件：快照只在"轮次结构变化"时刷新，<b>长流式轮次</b>（一次模型调用吐几十秒正文、
	 * 中途不落定任何工具）会让 {@code updatedAtMs} 长时间不动 —— 只看它就会把正在流式产出的活轮
	 * 判成僵尸并强行接管（第二次执行同一轮）。业务帧时间戳覆盖了流式与工具帧，两者取较新即
	 * "只要有业务前进就不算僵尸"。
	 *
	 * <p>
	 * <b>为什么不用 {@link RunStore#lastEventAtMs(String)}</b>：那个口径包含心跳（"进程还活着"），
	 * 而僵尸判据问的是"<b>业务</b>是否还在前进"。心跳一拍就刷新会让卡死的轮永远年轻（R1-09 的
	 * "心跳掩盖卡死"）。挂起等人的轮状态是 {@code SUSPENDED}，本来就不在本判据的 RUNNING 分支里。
	 */
	public long activeAtMs(RunSnapshot snapshot) {
		long lastEvent = this.store.lastActivityAtMs(snapshot.getRunId());
		return Math.max(snapshot.getUpdatedAtMs(), lastEvent);
	}

	/** 单轮明细（台账 + 待决条目 + 历史角色序列）。 */
	public Map<String, Object> describe(String runId) {
		RunSnapshot snapshot = this.store.get(runId);
		if (snapshot == null) {
			return Map.of("exists", false);
		}
		List<String> roles = this.codec.deserializeAll(snapshot.getMessageJson())
			.stream()
			.map(message -> message.getMessageType().name())
			.collect(Collectors.toList());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("exists", true);
		out.put("snapshot", snapshot.asMap());
		out.put("context", snapshot.getContext());
		out.put("historyRoles", roles);
		out.put("inFlight", snapshot.getInFlightToolCalls().stream().map(PendingToolCall::asMap).toList());
		out.put("pending", this.store.pendingsLenient(runId).stream().map(PendingToolCall::asMap).toList());
		out.put("ledger", this.store.ledger(runId));
		out.put("ledgerCounts", this.store.ledgerCounts(runId));
		out.put("instanceId", this.store.instanceId());
		out.put("keys", describeKeys(runId, snapshot));
		return out;
	}

	/**
	 * 运维面键表（同一个入口拼键，不另拼字符串）。
	 *
	 * <p>批③a 增 {@code lockTask} / {@code lockJob}：资源互斥锁（{@link LeaseKeys}）按 taskId 记，
	 * 与 runId 记的 {@code lease}/{@code fence} <b>不是同一族键</b>。上下文里没有 taskId（页面轮）时
	 * 两个值都是 null（键仍在，便于前端/运维面按固定键面取值）；有 taskId 但无 step/taskType 时
	 * 只有 {@code lockJob} 为 null。
	 */
	private Map<String, Object> describeKeys(String runId, RunSnapshot snapshot) {
		Map<String, Object> keys = new LinkedHashMap<>();
		keys.put("state", this.store.runKey(runId));
		keys.put("pending", this.store.pendingKey(runId));
		keys.put("ledger", this.store.ledgerKey(runId));
		keys.put("events", this.store.eventsKey(runId));
		keys.put("claim", this.store.claimKey(runId));
		// B23L1 批②b：fencing 的两个契约键也要能在运维面上直接对上
		keys.put("lease", this.store.runLeaseKey(runId));
		keys.put("fence", this.store.fenceKey(runId));
		// B23L1 批③a：L1 资源互斥的两把锁（按 taskId 记，随 TTL 自清，轮次回收不清）
		Long taskId = contextTaskId(snapshot);
		String jobType = contextJobType(snapshot);
		keys.put("lockTask", taskId == null || taskId <= 0L ? null : LeaseKeys.task(taskId));
		keys.put("lockJob", taskId == null || taskId <= 0L || jobType == null ? null : LeaseKeys.job(taskId, jobType));
		return keys;
	}

	/** 快照上下文里的 taskId（null = 页面轮/旧数据）。 */
	private static Long contextTaskId(RunSnapshot snapshot) {
		Object value = snapshot.getContext().get("taskId");
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return value == null ? null : Long.valueOf(String.valueOf(value));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * 快照上下文里的作业类型（JOBTYPE 的<b>来源</b>）：取 {@code step}，缺省取 {@code taskType}。
	 *
	 * <p>两者在既有上下文里同义（{@code AiContext.of(page, taskType, step, taskId, extra)}：
	 * 导出页 {@code taskType=EXPORT, step=EXPORT}）；大写归一交给 {@link LeaseKeys#normalizeJobType}。
	 */
	private static String contextJobType(RunSnapshot snapshot) {
		for (String field : List.of("step", "taskType")) {
			Object value = snapshot.getContext().get(field);
			if (value != null && !String.valueOf(value).isBlank()) {
				return String.valueOf(value);
			}
		}
		return null;
	}

	public ResumeResult resume(String runId) {
		// 调用线程已用 AiTrace#markResumeReason 标注过的，尊重它；未标注（运维端点 / 单测直调）按 manual 记。
		String marked = AiTrace.currentResumeReason();
		return resumeWithReason(runId, false, marked == null || marked.isBlank() ? RESUME_MANUAL : marked,
				ErrorBodyFactory.requestLocale());
	}

	/**
	 * 续跑（带<b>请求 locale</b>）：HTTP 入口在提交任务前取好 {@link ErrorBodyFactory#requestLocale()}，
	 * 由驱动线程 set/reset —— 工作线程不继承请求上下文，否则 en 用户的续跑错误/回答语言恒为中文。
	 *
	 * @param replyLocale 请求 locale；{@code null} = 无请求上下文（启动扫描/单测），不设置线程 locale
	 */
	public ResumeResult resume(String runId, Locale replyLocale) {
		String marked = AiTrace.currentResumeReason();
		return resumeWithReason(runId, false, marked == null || marked.isBlank() ? RESUME_MANUAL : marked,
				replyLocale);
	}

	/**
	 * 续跑（带<b>续跑原因</b>）：原因只影响日志面（{@code turn_start.resumeReason}），不改变续跑判据。
	 *
	 * <p>为什么不重载 {@code resume(...)}：第三个参数写成 {@code String} 会让既有的
	 * {@code resume(runId, false, null)} 变成"String 与 Lease 都匹配"的歧义调用（编译红）。
	 * 四个取值见 {@link #RESUME_STARTUP} / {@link #RESUME_MANUAL} / {@link #RESUME_DEADCARD} /
	 * {@link #RESUME_REATTACH}。
	 */
	public ResumeResult resumeWithReason(String runId, String resumeReason) {
		return resumeWithReason(runId, false, resumeReason, ErrorBodyFactory.requestLocale());
	}

	/**
	 * 续跑（幂等 + 互斥 + 取消优先 + 可选僵尸接管）—— <b>旧签名</b>：自抢续跑权租约。
	 *
	 * <p>既有调用方（{@code StartupResumeRunner} 的启动/僵尸扫描、{@code AiController} 的运维端点）
	 * <b>零改动</b>即获得 fencing：
	 * <ol>
	 * <li>抢 {@code ai:lease:run:<runId>}；抢不到（且本仓有租约设施）⇒ {@code LEASE_HELD_ELSEWHERE}，
	 * 什么都不动；无租约设施（单测/降级装配）⇒ 走原路径，由进程内互斥兜底；</li>
	 * <li>走带租约重载 {@link #resume(String, boolean, LeaseService.Lease)}；</li>
	 * <li>非 {@code ACCEPTED} 且结局在<b>归还白名单</b>内（{@link Outcome#releasesLease()}，
	 * {@code IN_PROGRESS} 与 {@code LEASE_HELD_ELSEWHERE} 不在其中 = 有人正在用这一任）
	 * ⇒ 当场还租；{@code ACCEPTED} ⇒ 交续租调度器（终态由 {@code RunStore.save} 释放）。
	 * <b>两个条件都必要</b>：
	 * <ul>
	 * <li><b>只还自己创建的</b>（{@code AcquireResult#created()}）—— 可重入命中的租约属于等待方
	 * 或另一个并发调用方；</li>
	 * <li><b>只在无人接管的结局下还</b>（L-19）—— 创建者也可能输掉 {@code resuming} 互斥
	 * （{@code IN_PROGRESS}），此时它手里的租约正被胜方使用，"创建者"身份并不等于"可以删"。</li>
	 * </ul>
	 * 合起来才是完整口径：<b>租约跟"这一轮还有没有人管"走，不跟"是谁建的"走</b>。</li>
	 * </ol>
	 *
	 * @param forceTakeover 仅由<b>启动自动续跑</b>使用的僵尸接管开关（N3）：为 true 时允许
	 * 接管"状态仍是 {@code RUNNING} 但已超龄"的轮次（上一进程死在模型调用中间留下的僵尸轮）。
	 * 运维端点不传这个开关（{@code false}），保持"RUNNING 一律 409"的保守语义。
	 */
	public ResumeResult resume(String runId, boolean forceTakeover) {
		// 调用方只有启动扫描：僵尸接管 = 死卡（deadcard），常规启动扫描 = startup
		return resumeWithReason(runId, forceTakeover, forceTakeover ? RESUME_DEADCARD : RESUME_STARTUP);
	}

	/** 续跑（原因 + 僵尸接管开关）：自抢续跑权租约，其余与 {@link #resume(String, boolean, LeaseService.Lease)} 同构。 */
	public ResumeResult resumeWithReason(String runId, boolean forceTakeover, String resumeReason) {
		return resumeWithReason(runId, forceTakeover, resumeReason, ErrorBodyFactory.requestLocale());
	}

	private ResumeResult resumeWithReason(String runId, boolean forceTakeover, String resumeReason,
			Locale replyLocale) {
		LeaseService leases = this.store.leaseService();
		boolean fresh = false;
		LeaseService.Lease lease = null;
		if (leases != null) {
			LeaseService.AcquireResult acquired = this.store.acquireRunLease(runId);
			if (acquired.outcome() == LeaseService.AcquireOutcome.HELD_ELSEWHERE) {
				return new ResumeResult(Outcome.LEASE_HELD_ELSEWHERE, Map.of("runId", runId, "resumed", false,
						"reason", "LEASE_HELD_ELSEWHERE", "instanceId", this.store.instanceId()));
			}
			// I4：设施不可用（{@code UNAVAILABLE}）⇒ 走"无租约降级"这条既有路径（进程内互斥放行），
			// 与 leaseService == null 完全同义。把"我不知道有没有主"报成"别人持有"会让一次 Redis 抖动
			// 把启动续跑/运维续跑全部 409 掉。
			// 所有权口径：<b>谁创建谁释放</b>。只有真正建出这一任的调用方才允许交调度器/还租；
			// 命中现值（同主重入）时 created=false —— 用"抢占前 epoch"推断是错的：并发同主双抢时
			// 两边读到同一个前置状态，于是败方（撞 resuming 互斥 → IN_PROGRESS）会去还胜方的租约，
			// 把胜方的 fencing 写打回 plain SET（harness lease-scripts 实锤）。
			lease = acquired.lease();
			fresh = acquired.created();
		}
		ResumeResult result;
		try {
			result = resumeInternal(runId, forceTakeover, lease, resumeReason, replyLocale);
		}
		catch (RuntimeException ex) {
			if (fresh) {
				this.store.releaseRunLease(runId);
			}
			throw ex;
		}
		if (fresh) {
			if (result.outcome() == Outcome.ACCEPTED) {
				// I7：续租带上存活判据 —— 本进程仍在续跑（resuming 表里有它）且快照非终态；
				// 驱动任务收尾（finally 里摘除 resuming）后下一拍即停续租并还租，
				// 于是"驱动线程异常退出、快照停在 RUNNING"不会留下永远续租的僵尸租约。
				leases.markDriving(lease, () -> this.resuming.containsKey(runId) && snapshotNotTerminal(runId));
			}
			else if (result.outcome().releasesLease()) {
				// L-19：只有"确认无人会接手"的结局才还租。IN_PROGRESS = 本进程已有胜方在跑 ——
				// 创建者输掉 resuming 互斥时，它手里这份租约仍是胜方在用的那一任，
				// 还了就把胜方的 fencing 写打回 plain SET（fence 键不再推进）。
				this.store.releaseRunLease(runId);
			}
		}
		return result;
	}

	/**
	 * 续跑（<b>带租约重载</b>，B23L1 批②b）：调用方已经是续跑权的主人，本方法<b>不再自抢</b>，
	 * 只校验归属 —— {@code LeaseService#verifyHeld(key, epoch)}（<b>对着 Redis 真值</b>，一次往返）。
	 *
	 * <p>为什么不用"抢"来校验：确认门在等待期就持租（交棒后继续持有），自抢会走可重入短路
	 * （同一个 epoch、恒成功），于是"拿着过期 epoch 的陈旧驱动方"也能进来 —— 那正是 fencing 要拦的。
	 * 为什么也不只比对 {@code heldRunEpoch}（进程内视图）：TTL 到期被别人接管后，视图要等下一次
	 * 续租失败才会清，这个窗口里视图会说谎，而本方法正是"要不要驱动这一轮"的入口。
	 * 对真值校验才能让"租约已被别的实例接管"在入口处变成 {@link Outcome#LEASE_HELD_ELSEWHERE}；
	 * 设施不可用（{@code Hold.UNAVAILABLE}）按无租约降级放行（I4 同口径）。
	 *
	 * <p>租约的收尾职责在<b>调用方</b>：本方法不 {@code markDriving}、不 {@code release}
	 * （确认门在 {@code runResume} 里做；旧签名在自己那一层做）。
	 *
	 * @param lease 调用方持有的租约；{@code null} = 无租约降级（单测/无 Redis 装配），
	 * 此时只由进程内互斥排他 —— 与批②b 之前的语义一致
	 */
	public ResumeResult resume(String runId, boolean forceTakeover, LeaseService.Lease lease) {
		// 决策回灌/挂起重挂等入口：未显式标注原因时按 reattach 记（"其余续跑入口"的兜底口径）
		return resumeInternal(runId, forceTakeover, lease, RESUME_REATTACH, ErrorBodyFactory.requestLocale());
	}

	/**
	 * 续跑失败的用户面短句（R-08 + 4B）：按当前线程 locale 取 {@code messages_en}；未注入
	 * MessageSource 时走 en 常量，绝不回落到异常原文。
	 */
	String resumeFailedMessage() {
		if (!ReplyLanguage.current().english()) {
			return AI_RESUME_FAILED_MESSAGE;
		}
		if (this.messageSource != null) {
			try {
				return this.messageSource.getMessage("error." + ErrorCodes.AI_RESUME_FAILED, null, Locale.ENGLISH);
			}
			catch (NoSuchMessageException ex) {
				log.debug("messages_en 缺 error.{}，回落 en 白名单短句", ErrorCodes.AI_RESUME_FAILED);
			}
		}
		return AI_RESUME_FAILED_MESSAGE_EN;
	}

	private ResumeResult resumeInternal(String runId, boolean forceTakeover, LeaseService.Lease lease,
			String resumeReason, Locale replyLocale) {
		if (lease != null) {
			// I3：归属校验对着 Redis 真值做（一次往返），不用进程内视图 —— 视图在"TTL 已到、续租还没失败"
			// 的窗口里会说谎，而这个判定正是"要不要驱动这一轮"的入口。
			LeaseService leases = this.store.leaseService();
			LeaseService.Hold hold = leases == null ? LeaseService.Hold.UNAVAILABLE
					// 批③a：按租约<b>自己的 owner</b> 校验（租约不再隐含 instanceId —— 同一个键上
					// 可能并存不同持有者的租约，用错 owner 会把自己"校验失败"掉）
					: leases.verifyHeld(lease.key(), lease.epoch(), lease.owner());
			if (hold == LeaseService.Hold.LOST) {
				return new ResumeResult(Outcome.LEASE_HELD_ELSEWHERE, Map.of("runId", runId, "resumed", false,
						// heldEpoch 是进程内视图（可能在"TTL 已到、续租未失败"窗口里滞后），只作排障参考
						"reason", "LEASE_HELD_ELSEWHERE", "staleEpoch", lease.epoch(),
						"heldEpoch", this.store.heldRunEpoch(runId), "instanceId", this.store.instanceId()));
			}
			// UNAVAILABLE：设施不可用 ⇒ 与 I4 同口径放行（本方法仍是"调用方已持租"的语义，
			// 只是这一拍校验不了；后面的杀招是 fence 写与 claimExecution 的 STALE_EPOCH）。
		}
		if (this.resuming.putIfAbsent(runId, Boolean.TRUE) != null) {
			return new ResumeResult(Outcome.IN_PROGRESS, Map.of("runId", runId, "resumed", false,
					"reason", "RESUME_IN_PROGRESS", "instanceId", this.store.instanceId()));
		}
		boolean handedOff = false;
		// 会话门（park 批）：恢复执行必须重新拿回同 sessionId 的单值门（park 时首轮任务已释放）。
		// 用 AtomicBoolean 记录"本次是否持有"，外层 finally 统一释放（task 内也释放一次，幂等）。
		java.util.concurrent.atomic.AtomicBoolean gateHeld = new java.util.concurrent.atomic.AtomicBoolean();
		String gateSessionId = null;
		// 会话门的归属 token（I2）：续跑与首轮可能共用同一个 runId，只按 runId 释放会让首轮那条
		// 迟到的 finally 删掉续跑方的锁。每次 acquire 用一次性 token，释放/续期都按它精确匹配。
		String gateToken = UUID.randomUUID().toString();
		try {
			RunSnapshot snapshot = this.store.get(runId);
			if (snapshot == null) {
				return new ResumeResult(Outcome.NOT_FOUND,
						Map.of("runId", runId, "resumed", false, "reason", "UNKNOWN_RUN", "instanceId",
								this.store.instanceId()));
			}
			String sessionId = snapshot.getSessionId();
			// 日志链路批：续跑可能落在 ResumeService 自己的调用线程上（启动扫描 / 死卡兜底触发池），
			// 入口先装锚点；真正的驱动线程由 driveResumed 再绑一次。
			AiTrace.bind(runId, sessionId);
			// 续跑原因经 MDC 过线程边界（驱动任务在下面提交，TaskDecorator 在提交时刻抓快照）
			AiTrace.markResumeReason(resumeReason);
			// C2：续跑入口的清单用非抛读口；guard 读失败时仍能拿到原始条目并按 PENDING 保守处理。
			List<PendingToolCall> pendings;
			try {
				// C2：续跑入口必须用强一致读口。整表读失败不能当成"没有未决条目"而贸然驱动。
				pendings = this.store.pendings(runId);
			}
			catch (RuntimeException ex) {
				log.warn("续跑入口读取待决条目失败 runId={}（按未知拒绝，不驱动）", runId, ex);
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("runId", runId);
				body.put("resumed", false);
				body.put("reason", "STATE_UNKNOWN");
				body.put("code", com.example.configmgr.common.ErrorCodes.AI_REDIS_UNAVAILABLE);
				return new ResumeResult(Outcome.STATE_UNKNOWN, body);
			}
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("runId", runId);
			body.put("sessionId", sessionId);
			body.put("statusBeforeResume", snapshot.getStatus());
			body.put("instanceId", this.store.instanceId());

			// 取消优先（ADR-8 修正③）：权威取消标志在 Redis，跨进程同样拦得住续跑 ——
			// 否则"用户已取消"的轮次会被重启后的自动续跑重新拾起并执行掉。
			// 位置在统一终态判定<b>之前</b>：即便快照已落 CANCELLED，也要把残留的 PENDING 条目收敛掉。
			ResumeResult cancelAtEntry = cancelStateOutcome(runId, body);
			if (cancelAtEntry != null) {
				return cancelAtEntry;
			}
			// 统一终态判定（T3-7 / A14）：DONE/FAILED/CANCELLED（+ REJECTED 归一）一律不再续跑。
			// 结局码保持既有对外语义：取消 → ALREADY_CANCELLED；完成 → ALREADY_DONE（reason ALREADY_DONE）；
			// 失败 → ALREADY_DONE（reason ALREADY_FAILED，属既有口径，不动）。
			if (RunSnapshot.isTerminal(snapshot.getStatus())) {
				boolean cancelledStatus = RunSnapshot.CANCELLED.equals(snapshot.getStatus());
				boolean doneStatus = RunSnapshot.DONE.equals(snapshot.getStatus());
				body.put("resumed", false);
				body.put("reason", cancelledStatus ? "ALREADY_CANCELLED"
						: (doneStatus ? "ALREADY_DONE" : "ALREADY_FAILED"));
				return new ResumeResult(cancelledStatus ? Outcome.ALREADY_CANCELLED : Outcome.ALREADY_DONE, body);
			}
			if (RunSnapshot.RUNNING.equals(snapshot.getStatus())) {
				if (!forceTakeover) {
					body.put("resumed", false);
					body.put("reason", "ALREADY_RUNNING");
					return new ResumeResult(Outcome.ALREADY_RUNNING, body);
				}
				// 僵尸接管（N3/R3）：上一进程死在模型调用中间，状态永远停在 RUNNING。
				// 判据由调用方（启动扫描/运维扫描）给出：本实例刚启动 + 业务活动已超龄；
				// 活跃时刻 = max(快照 updatedAtMs, 最后一条非心跳业务帧 atMs)，因此"还在跑长工具/
				// 流式吐字"的活轮不会被接管，心跳也不会把卡死的轮养年轻（R1-09）。
				long activeAtMs = activeAtMs(snapshot);
				log.warn("僵尸轮接管 runId={} sessionId={} 活跃时刻距今 {}ms（快照 {}ms / 末条业务帧 {}ms，阈值 {}）",
						runId, sessionId, System.currentTimeMillis() - activeAtMs,
						System.currentTimeMillis() - snapshot.getUpdatedAtMs(),
						System.currentTimeMillis() - this.store.lastActivityAtMs(runId),
						this.properties.getResume().getZombieAge());
				body.put("zombieTakeover", true);
				body.put("activeAtMs", activeAtMs);
				body.put("activeAgeMs", System.currentTimeMillis() - activeAtMs);
			}
			// 批③ PROVIDER_GONE：快照的模型身份此刻必须仍可用。判据 = 能解析出<b>同一个</b> provider
			// 与该模型（enabled + key 可解 + 模型在 allowlist）。缺一 ⇒ 409，不静默换模型续跑。
			// I3：<b>有 providerId 时必须按 (providerId, modelId) 精确解析</b> —— 只按模型名解析会
			// 在"同名模型挂两家"时判到别家（既可能误判 GONE，也可能把 B 的一轮放行到 A）。
			// 旧快照（providerId=null 且未冻结）不判 —— 它们本来就没有身份可校验，走默认 + WARN（兼容）。
			// L-26：<b>已冻结</b>的快照即使 providerId=null（env 合成行）也要判"模型此刻还在不在"
			// —— 冻结的语义就是"按这一轮当时的模型续跑"，模型下架时静默换默认同样是说谎。
			// 位置在 PENDING_UNRESOLVED <b>之前</b>：端点没了是比"还有人没决策"更硬的前置条件。
			boolean frozenIdentity = snapshot.paramsFrozen()
					&& snapshot.getModelId() != null && !snapshot.getModelId().isBlank();
			if (this.aiConfigService != null && snapshot.getProviderId() != null) {
				ResolvedIdentity identity = resolveForResume(runId,
						() -> this.aiConfigService.resolveChatTarget(snapshot.getProviderId(), snapshot.getModelId()),
						() -> this.aiConfigService.bindingState(snapshot.getProviderId(), snapshot.getModelId()));
				if (identity.unknown()) {
					return stateUnknown(runId, snapshot, identity.detail());
				}
				if (identity.target() == null) {
					return providerGone(runId, snapshot, "该 provider/模型此刻不可用");
				}
			}
			else if (this.aiConfigService != null && frozenIdentity) {
				// env 合成行（无 providerId）没有稳定身份可比：退化为"这个模型名此刻是否仍可解析"
				ResolvedIdentity identity = resolveForResume(runId,
						() -> this.aiConfigService.resolveChatTarget(snapshot.getModelId()),
						() -> this.aiConfigService.bindingState(null, snapshot.getModelId()));
				if (identity.unknown()) {
					return stateUnknown(runId, snapshot, identity.detail());
				}
				if (identity.target() == null
						|| !snapshot.getModelId().equalsIgnoreCase(identity.target().modelId())) {
					return providerGone(runId, snapshot, "该模型此刻已不可解析");
				}
			}
			List<PendingToolCall> unresolved = pendings.stream()
				.filter(pending -> PendingToolCall.PENDING.equals(pending.getStatus()))
				.filter(pending -> !PendingToolCall.KIND_BACKEND.equals(pending.getKind()))
				.toList();
			if (!unresolved.isEmpty()) {
				body.put("resumed", false);
				body.put("reason", "PENDING_UNRESOLVED");
				body.put("unresolved", unresolved.stream().map(PendingToolCall::asMap).toList());
				return new ResumeResult(Outcome.PENDING_UNRESOLVED, body);
			}

			// ===== 人工等待 park 批：恢复执行前重新 acquire 会话门 =====
			// park 时首轮任务已 return（AiController 的 finally 释放了门），于是"同 sessionId 单值门"
			// 在停等期间是空的。恢复方必须重新取回它，否则"用户在 park 期间又发了一轮"会与本轮并发。
			// I2：用一次性 token 取门 —— 同 runId 的首轮迟到 finally（token = runId）不得误删本持有者。
			if (this.sessionGate != null) {
				SessionGate.Busy busy = this.sessionGate.acquire(sessionId, runId, gateToken);
				if (busy != null && !runId.equals(busy.runId())) {
					body.put("resumed", false);
					body.put("reason", "SESSION_BUSY");
					body.put("heldBy", busy.runId());
					body.put("message", "该会话当前有另一轮在跑（park 期间用户又发起了一轮）；"
							+ "本轮决策已落库，稍后自动重试续跑。");
					log.info("续跑暂缓：会话门被另一轮占着 runId={} heldBy={}（决策已落库，由派发方有界重试）",
							runId, busy.runId());
					return new ResumeResult(Outcome.SESSION_BUSY, body);
				}
				gateHeld.set(true);
				gateSessionId = sessionId;
			}

			AiContext context = AiContext.fromMap(snapshot.getContext());
			// 续租 fencing 收口批：入口也要先问一次写权限（权威判定，不看 epoch==0）。
			// 丢租（LOST）或设施不可确认（UNAVAILABLE）⇒ 只读、不推进：不写 RUNNING、不压缩、不派发。
			// 这一条与 RunStore.save / 认领 / 台账 / 出帧的拦截同源 —— 入口放行而写口拒绝会跑一轮
			// "答得出、存不下"的幽灵续跑。
			LeaseService.LeaseAuthority authority = this.store.runLeaseAuthority(runId);
			if (authority != null && authority.readOnly()) {
				body.put("resumed", false);
				body.put("reason", authority == LeaseService.LeaseAuthority.LOST ? "LEASE_HELD_ELSEWHERE"
						: "LEASE_UNAVAILABLE");
				body.put("code", ErrorCodes.AI_REDIS_UNAVAILABLE);
				log.warn("续跑拒绝：写权限为 {} runId={}（只读恢复，不推进这一轮）", authority, runId);
				return new ResumeResult(authority == LeaseService.LeaseAuthority.LOST
						? Outcome.LEASE_HELD_ELSEWHERE : Outcome.STATE_UNKNOWN, body);
			}
			// 续跑保 system 批：先恢复/重建 SYSTEM，再做窗口/压缩 —— 三步都只处理非 SYSTEM 部分。
			List<Message> loaded = new ArrayList<>(this.codec.deserializeAll(snapshot.getMessageJson()));
			boolean hadSystem = SystemMessages.leadingCount(loaded) > 0;
			List<Message> history = restoreSystem(runId, sessionId, loaded, context, replyLocale);
			boolean systemRestored = !hadSystem && SystemMessages.leadingCount(history) > 0;
			body.put("systemRestored", systemRestored);
			if (history != loaded) {
				// 重建/归位出来的 SYSTEM 写回快照：下一轮 / 再次续跑不必再猜，也让"模型看到的"与落库的一致。
				snapshot.setMessageJson(this.codec.serializeAll(history));
			}
			List<String> rebuilt = new ArrayList<>();
			int rebuiltToolTokens = 0;

			if (!snapshot.getInFlightToolCalls().isEmpty()) {
				// ===== 硬规范②：把挂起那一轮的 assistant(tool_calls) 重新拼回完整历史 =====
				AssistantMessage assistant = rebuildAssistant(snapshot);
				history.add(assistant);
				List<Message> requestHistory = new ArrayList<>(history);
				Prompt prompt = this.chatService.buildResumedPrompt(runId, sessionId, context, requestHistory);
				List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
				// P1-10：续跑与首轮同一批策略 —— W/J/P 确定性失败后，剩余未执行项补 SKIPPED。
				PendingToolCall haltedBy = null;
				for (PendingToolCall item : snapshot.getInFlightToolCalls()) {
					// 以 Redis 的待决条目为准（快照里的 in-flight 副本停留在"挂起那一刻"）
					PendingToolCall fresh = this.store.pending(runId, item.getToolCallId());
					PendingToolCall effective = fresh == null ? item : fresh;
					if (haltedBy != null && isUnsettled(effective)) {
						responses.add(this.toolCallingManager.skipAsUpstreamFailed(runId, effective, haltedBy));
						rebuilt.add(effective.getToolCallId() + ":" + PendingToolCall.SKIPPED + ":not-executed");
						continue;
					}
					// 入口 kind 校验批（续跑假批准）：APPROVED+FRONTEND 是"人没点过、前端也没执行过"的
					// 历史数据。结算由 resolvePending 显式落成 blocked（不执行后端桩、不写台账）；
					// 这里先留一条可 grep 的痕 —— 否则"这张卡续跑后为什么是 blocked"只能靠翻半小时日志猜。
					if (isKindMisapproved(effective)) {
						log.warn("续跑发现 APPROVED+FRONTEND 历史假批准 runId={} toolCallId={} name={}"
								+ "（将显式 blocked、不执行后端桩、不写台账）", runId, effective.getToolCallId(),
								effective.getName());
					}
					ResumeResult cancelBeforeTool = cancelStateOutcome(runId, body);
					if (cancelBeforeTool != null) {
						return cancelBeforeTool;
					}
					// ===== UNKNOWN 的翻译（取消状态在重建/认领窗口抖动）=====
					// 认领前复核为 fail-closed：读到 UNKNOWN 会抛既有 STATE_UNAVAILABLE（inline 路径的既有语义）。
					// 它若直接穿出 resume()，HTTP 会把"取消状态不可知"变成 500 —— 违反已批准的 UNKNOWN/503 契约。
					// 这里的职责只是把**同一个既有异常**翻译成**同一个既有 typed 结局**（与首次 unknown
					// 逐字同形，见 cancelStateOutcome）；不重新读取消状态、不放行、不落终态。
					String text;
					try {
						text = this.toolCallingManager.resolvePending(runId, prompt, effective);
					}
					catch (StreamViolationException ex) {
						if (!StreamViolationException.STATE_UNAVAILABLE.equals(ex.getCode())) {
							throw ex;
						}
						log.warn("续跑重建期间取消状态不可知，按既有 UNKNOWN 结局收口 runId={} toolCallId={}：{}",
								runId, effective.getToolCallId(), ex.getMessage());
						return cancelUnknownOutcome(body);
					}
					// ===== R1：认领前复核命中取消 ⇒ 这一轮必须走取消终态 =====
					// resolvePending 内部会在真正认领前复核取消（命中即放弃执行、条目收敛为 CANCELLED），
					// 但执行方没有"结束这一轮"的能力（它不持有 chat/终态面）。若在这里继续重建历史并
					// 交棒给模型，用户已经按下的停止会被一条 CANCELLED 工具结果"吞掉"：模型照常作答、
					// 轮次以 done 收尾。故紧接着复用同一个入口（它会 chatService.cancelParked → 取消终态
					// + 补裁决帧 + 结算行），"取消优先"因此覆盖到认领窗口。
					ResumeResult cancelAfterTool = cancelStateOutcome(runId, body);
					if (cancelAfterTool != null) {
						return cancelAfterTool;
					}
					// R6 裁决：标签必须在**结算之后**回读 Redis 才算准 ——
					// executeOnce 的认领/完成是写在 Redis 里的新副本上，传入的 effective 可能仍是旧值
					// （旧实现因此在"本次刚执行完"时显示 reused(null)）。
					PendingToolCall settled = this.store.pending(runId, effective.getToolCallId());
					PendingToolCall source = settled == null ? effective : settled;
					// T2 + P0-1：续跑重建的 role:tool 与首轮同形 —— 统一信封 JSON，message 先裁剪再组装
					// P1-10 对账措辞已由 executeOnce 写进 resultText，此处不再二次加工（重放同形）。
					responses.add(new ToolResponseMessage.ToolResponse(effective.getToolCallId(),
							effective.getName(),
							ToolResultEnvelope.of(source.effectiveOutcome(), source.effectiveCode(), text,
									effective.getArguments()).toModelJson(this.limiter, effective.getName())));
					rebuilt.add(settlementLabel(source));
					if (this.toolCallingManager.haltsBatch(source)) {
						haltedBy = source;
						log.warn("续跑批策略首错即停 runId={} 上游工具={} outcome={} code={}，剩余条目将置 SKIPPED",
								runId, source.getName(), source.effectiveOutcome().wire(), source.effectiveCode());
					}
				}
				ToolResponseMessage toolMessage = ToolResponseMessage.builder().responses(responses).build();
				history.add(toolMessage);
				// 上下文专项批①：本次重建出来的工具响应是"上次真值之后"的真增量（续跑侧的 nextEst 一项）
				rebuiltToolTokens = responses.stream()
					.mapToInt(response -> ContextEstimator.estimate(response.responseData()))
					.sum();
				// 完整历史：续跑重建的工具轮按同一 messageId 幂等补写；首轮已写则命中返回，不重复。
				// 先写历史再清 in-flight/存快照：崩溃在两者之间时下次 resume 仍会重建并幂等命中。
				if (this.historyRecorder != null) {
					this.historyRecorder.recordToolRound(sessionId, runId, assistant, toolMessage);
				}
				snapshot.setMessageJson(this.codec.serializeAll(history));
				snapshot.setInFlightToolCalls(new ArrayList<>());
				snapshot.setAssistantContent(null);
				snapshot.setStatus(RunSnapshot.RUNNING);
				snapshot.getResumedBy().add(this.store.instanceId());
				this.store.save(snapshot);
			}
			else {
				snapshot.getResumedBy().add(this.store.instanceId());
				snapshot.setStatus(RunSnapshot.RUNNING);
				this.store.save(snapshot);
			}

			// ── 记忆窗口批：续跑重建出来的历史也按同一个 token 预算收口（与首轮同源：同一个 MemoryWindow） ──
			// 为什么续跑更需要它：这里是完整 run 历史（挂起期越长越大），没有条数窗口兜着 ——
			// 只按模型上限占比判的话，"32K 模型 + 40 条大工具结果"会先撞上游 400 才发现。
			// 只改"这一轮交棒给模型的视图"：快照保留完整历史（审计/后续续跑的原文），窗口是确定性
			// 纯函数（同输入同输出），故不落库、不归档（区别于压缩：压缩改写内容且必须归档原文）。
			Set<String> protectedIds = new LinkedHashSet<>();
			for (PendingToolCall pending : this.store.pendingsLenient(runId)) {
				if (PendingToolCall.PENDING.equals(pending.getStatus())
						|| (PendingToolCall.APPROVED.equals(pending.getStatus()) && !pending.isExecuted())) {
					protectedIds.add(pending.getToolCallId());
				}
			}
			MemoryWindow.Budget windowBudget = MemoryWindow.Budget.from(this.properties);
			MemoryWindow.Plan window = MemoryWindow.plan(history, windowBudget, protectedIds);
			MemoryWindow.emit(runId, sessionId, window, windowBudget);
			history = window.messages();
			body.put("memoryWindowTrimmed", window.changed());
			if (window.changed()) {
				body.put("memoryWindow", window.summary());
			}

			// ── 上下文专项批①：续跑前压缩（与首轮同源：同一个 ContextCompactor） ──
			// 位置刻意在"历史重建之后、交棒之前"：重建出来的工具响应已经进 history，
			// 于是 L1 能清到它们（挂在挂起期的结果往往正是最大的一块）。
			ContextCompactor.Outcome compaction = null;
			if (this.contextCompactor != null && !history.isEmpty()) {
				ContextState state = this.contextCompactor.readState(sessionId);
				int systemTokens = 0;
				for (Message message : history) {
					if (message.getMessageType() == MessageType.SYSTEM) {
						systemTokens += ContextEstimator.estimate(message.getText());
					}
				}
				// 摘要只使用本轮 runtime（与 driveResumed 同一派生口径）；取不到 ⇒ null，L2 按不可用失败、历史不改
				ProviderRuntime compactionRuntime = this.chatService == null ? null
						: this.chatService.runtimeForCompaction(runId, snapshot.getProviderId(),
								snapshot.getModelId());
				compaction = this.contextCompactor.compact(new ContextCompactor.Request(sessionId,
						runId, snapshot.getModelId(), snapshot.getProviderId(), history, this.codec.serializeAll(history),
						state.usedTokens(), state.completionTokens(), 0, rebuiltToolTokens, systemTokens,
						this.chatService.disclosedToolTokens(context), protectedIds, compactionRuntime),
						// R1-09 M-1：L2 是 N 次串行摘要（每次最长 60s），期间无业务帧/心跳。
						// 每完成一次摘要就刷新业务锚点，避免长压缩被别的实例判成僵尸并接管。
						() -> this.store.touchBusinessActivity(runId, System.currentTimeMillis()));
				if (compaction.changed()) {
					history = compaction.messages();
					snapshot.setMessageJson(this.codec.serializeAll(history));
					this.store.save(snapshot);
					body.put("contextCompaction", compaction.levelWire());
					// 落库顺序（本路径）= 归档 → 快照 → 记忆（轮末 converge 时）。与首轮路径的
					// "记忆 → 快照"不同，原因是本类拿不到 ChatMemory：轮末 convergeMemory 会以快照为准
					// 整体覆盖写回，故偏差窗口的方向是"记忆更旧"（不会多算术、不会丢配对），
					// 也不会出现首轮路径要防的"快照已压、记忆未压"长期分叉。
				}
				else {
					body.put("contextCompaction", "none");
					body.put("contextCompactionReason", compaction.reason());
				}
			}

			body.put("resumed", true);
			body.put("rebuiltTools", rebuilt);
			body.put("promptMessageCount", history.size());
			body.put("promptRoles", history.stream().map(message -> message.getMessageType().name()).toList());
			body.put("resumedBy", this.store.instanceId());

			SseChatEmitter out = this.registry.of(runId);
			// ── I-1：续跑侧压缩也要即时可见（与首轮同一份 outcome 载荷）──
			// 位置：start 已由原轮发出（归档里），本帧在此处取号 ⇒ 必然晚于 start、且早于交棒后的
			// 首个 delta（交棒在下面 execute 里，本帧在提交之前同步发出）。
			if (compaction != null && compaction.changed()) {
				// 发帧是观测面，不决定续跑成败：写失败（客户端已断/emitter 已关闭/序列化异常）
				// 只降级为 WARN —— 级别交接与交棒都必须照常进行，否则"看得见"反噬"跑得成"。
				try {
					out.context(ContextCompactor.framePayload(compaction));
				}
				catch (RuntimeException ex) {
					log.warn("续跑 context 帧发送失败（已忽略，不影响本轮续跑）runId={}：{}", runId, ex.getMessage());
				}
				// 级别显式交接给驱动侧：续跑轮的 done.context.level 不能靠时刻反推（压完才交棒）
				this.chatService.noteCompaction(runId, compaction.levelWire());
			}
			List<Message> resumeHistory = history;
			try {
				this.suspendExecutor.execute(() -> {
					// I-5 / 4B：locale 不能靠线程池继承（工作线程没有请求上下文）。
					// HTTP 入口在提交前把 locale 传进来；这里 set，finally 一律 reset（池线程复用）。
					if (replyLocale == null) {
						LocaleContextHolder.resetLocaleContext();
					}
					else {
						LocaleContextHolder.setLocale(replyLocale);
					}
					try {
						// 批③：有身份的快照按<b>快照身份</b>续跑（上面的 PROVIDER_GONE 检查已保证它此刻可用）。
						// 旧快照（无 providerId）仍走五参旧签名 —— 语义等价（按默认目标续跑 + WARN），
						// 也让既有用例的 verify(driveResumed(...5 参)) 不必跟改（回归零改动）。
						if (snapshot.getProviderId() == null) {
							this.chatService.driveResumed(runId, sessionId, context, resumeHistory, out);
						}
						else {
							this.chatService.driveResumed(runId, sessionId, context, resumeHistory,
									snapshot.getProviderId(), snapshot.getModelId(), out);
						}
					}
					catch (Exception ex) {
						// R-08：用户面只给白名单短句；原始异常（上游 body / 内部提示）只进日志。
						log.error("续跑未捕获异常 runId={}：{}", runId, String.valueOf(ex.getMessage()), ex);
						// 交棒后的失败必须落终态，不能只留下错误帧和无人推进的 RUNNING 快照。
						markDriveFailure(snapshot, ex);
						out.error(ErrorCodes.AI_RESUME_FAILED, resumeFailedMessage());
					}
					finally {
						LocaleContextHolder.resetLocaleContext();
						// 轮终态：完成订阅者 + 摘掉进程内互斥（ADR-5 的"轮终态主动释放"同族语义）。
						// 续跑权租约不在这里还：终态写进 RunStore.save 时 best-effort 释放（同一个
						// 唯一写口），此处再还一次只会在"写终态前"制造一个别人可接管的窗口。
						this.resuming.remove(runId);
						this.registry.close(runId);
						// park 批：恢复执行取回的会话门到这里归还（二次 park 也一样 —— 任务结束即释放，
						// 下一次恢复重新 acquire；看门狗随 release 停掉，不会无限续期）。
						// I2：按本次 acquire 的 token 释放 —— 同 runId 的另一任持有者不受影响。
						if (this.sessionGate != null && gateHeld.get()) {
							this.sessionGate.release(sessionId, runId, gateToken);
							gateHeld.set(false);
						}
					}
				});
				handedOff = true;
			}
			catch (RejectedExecutionException ex) {
				body.put("resumed", false);
				body.put("reason", "SUSPEND_POOL_SATURATED");
				// 没交棒这一轮就不会有人来读那个一次性交接位：就地清掉，别让它活到本轮结束
				// （runId 不复用，留着不致命，但会让"读后即弃"的容量论证失真、也让排障时
				// 看到一条"压过 L1 但其实没跑"的幽灵记录）。
				this.chatService.clearCompaction(runId);
				snapshot.setStatus(RunSnapshot.SUSPENDED);
				this.store.save(snapshot);
				return new ResumeResult(Outcome.POOL_SATURATED, body);
			}
			return new ResumeResult(Outcome.ACCEPTED, body);
		}
		finally {
			if (!handedOff) {
				this.resuming.remove(runId);
			}
			// park 批：<b>只有没交棒</b>（池饱和/会话被占/异常）才由本线程归还会话门。
			// 交棒之后门归驱动任务所有（它在自己的 finally 按同一 token 释放）—— 这里若也释放，
			// 驱动期间门是空的，"同会话并发第二轮"就能进来（本行是 I2 批实测抓到的真缺陷）。
			if (!handedOff && this.sessionGate != null && gateHeld.getAndSet(false) && gateSessionId != null) {
				this.sessionGate.release(gateSessionId, runId, gateToken);
			}
			// 驱动线程（挂起池）在 driveResumed 入口自己绑锚点 —— 本线程到此结束，一律摘掉
			AiTrace.clear();
		}
	}

	/**
	 * 驱动任务失败时落成 FAILED 终态，并清空未完成的工具调用。
	 * 已经是终态的快照不覆盖；写终态失败只留 WARN，不把异常抛给已发出的终帧。
	 */
	private void markDriveFailure(RunSnapshot snapshot, Exception ex) {
		if (snapshot == null || snapshot.getRunId() == null) {
			return;
		}
		try {
			RunSnapshot latest = this.store.get(snapshot.getRunId());
			RunSnapshot target = latest == null ? snapshot : latest;
			if (RunSnapshot.isTerminal(target.getStatus())) {
				return;
			}
			boolean providerGone = ex instanceof ProviderGoneException;
			target.setStatus(RunSnapshot.FAILED);
			target.setError((providerGone ? "PROVIDER_GONE：该轮使用的模型端点此刻不可用，无法按原模型续跑"
					: com.example.configmgr.common.ErrorCodes.AI_RESUME_FAILED + "：续跑执行失败")
					+ "（" + ex.getClass().getSimpleName() + "）");
			target.setInFlightToolCalls(new ArrayList<>());
			this.store.save(target);
		}
		catch (RuntimeException writeEx) {
			log.warn("续跑失败落终态写回失败 runId={}（终帧已发出，恢复侧按快照/租约兜底）：{}",
					snapshot.getRunId(), writeEx.getMessage());
		}
	}

	/**
	 * 续跑前的 <b>SYSTEM 恢复/重建</b>（续跑保 system 批）。
	 *
	 * <h2>为什么续跑必须先做这一步</h2>
	 * 快照里的历史是"模型当时看到的那串消息"，但旧实现的两条裁剪路径（窗口按 turn 淘汰、
	 * 压缩按 turn 摘要/硬截断）会把<b>首段连同 SYSTEM 一起</b>吃掉：{@code ConversationTurns}
	 * 把"第一条 user 之前的残留"归入第 0 段，而第 0 段正是系统消息所在。丢了 SYSTEM 之后续跑
	 * 仍会成功发请求，只是人设、工作区约束与"外部文本是数据不是指令"的声明全部消失 ——
	 * 静默降智，不是报错。
	 *
	 * <h2>三条规则</h2>
	 * <ol>
	 * <li>历史里已有 SYSTEM ⇒ <b>原样保留</b>（不拿当前配置覆盖历史；模型当时看到的就是这份）；</li>
	 * <li>SYSTEM 不在最前（例如旧数据把它夹在中间）⇒ 按原顺序移到最前，其余历史顺序不变；</li>
	 * <li>历史里完全没有 ⇒ 用 {@link ResilientChatService#systemPromptText(AiContext)} 重建
	 *     （与首轮同一个构造点）；重建不可用（未装配/异常）时如实 WARN 并放行 —— 续跑本身
	 *     不该因为"没有提示词设施"而整条失败，但"没有 system"这件事必须留痕。</li>
	 * </ol>
	 *
	 * @return 恢复后的历史；无需改动时返回入参本身（保持"未改动即同引用"）
	 */
	private List<Message> restoreSystem(String runId, String sessionId, List<Message> loaded, AiContext context,
			Locale replyLocale) {
		List<Message> systems = new ArrayList<>();
		List<Message> rest = new ArrayList<>(loaded.size());
		for (Message message : loaded) {
			if (SystemMessages.isSystem(message)) {
				systems.add(message);
			}
			else {
				rest.add(message);
			}
		}
		boolean wasLeading = systems.size() == SystemMessages.leadingCount(loaded);
		boolean rebuilt = false;
		if (systems.isEmpty()) {
			String rebuiltText = null;
			// 重建要读"本轮回答语言"（ReplyLanguage 走线程 locale）：HTTP 续跑传入的请求 locale
			// 在驱动任务里才 set；这里临时借一下，结束后原样还回（调用线程可能已有自己的 locale）。
			LocaleContext previousLocale = null;
			if (replyLocale != null) {
				previousLocale = LocaleContextHolder.getLocaleContext();
				LocaleContextHolder.setLocale(replyLocale);
			}
			try {
				rebuiltText = this.chatService == null ? null : this.chatService.systemPromptText(context);
			}
			catch (RuntimeException ex) {
				log.warn("续跑重建 SYSTEM 失败 runId={} sessionId={}（按原历史续跑，不伪装已恢复）：{}", runId,
						sessionId, ex.getMessage());
			}
			finally {
				if (replyLocale != null) {
					if (previousLocale == null) {
						LocaleContextHolder.resetLocaleContext();
					}
					else {
						LocaleContextHolder.setLocaleContext(previousLocale);
					}
				}
			}
			if (rebuiltText == null || rebuiltText.isBlank()) {
				log.warn("续跑快照缺少 SYSTEM 且无法重建 runId={} sessionId={}（续跑将没有人设/工作区约束，"
						+ "请排查快照写入路径）", runId, sessionId);
				return loaded;
			}
			systems.add(new SystemMessage(rebuiltText));
			rebuilt = true;
			log.warn("续跑快照缺少 SYSTEM，已按当前配置重建 runId={} sessionId={}（旧窗口/压缩可能丢过系统段）",
					runId, sessionId);
		}
		if (wasLeading && !rebuilt) {
			return loaded;
		}
		return SystemMessages.reattach(systems, rest);
	}

	/**
	 * <b>取消收尾的窄入口</b>（写权限不可确认时的交棒目标，见 {@link CancelledRunFinalizer}）：
	 * <b>只</b>把"已确认取消"的一轮收敛成终态，绝不发起模型/工具。
	 *
	 * <h2>与 {@code resumeInternal} 同一套准入（I-3）</h2>
	 * <ol>
	 * <li><b>进程内互斥</b>：与续跑共用 {@code resuming} 表 —— 同进程已有执行体在推进这一轮
	 *     ⇒ {@link CancelFinalize#IN_PROGRESS}（交棒侧有界重试），绝不并发写/并发驱动；</li>
	 * <li><b>会话门</b>：同 sessionId 单值门，带<b>一次性 token</b> 取、按同一 token 精确释放
	 *     （与 {@code resumeInternal} 同形；首轮/交棒都不得误删别人的锁）。被别的轮占着 ⇒
	 *     {@link CancelFinalize#SESSION_BUSY}；</li>
	 * <li><b>跨实例续跑权</b>：{@code acquireRunLease}（生产 = 三键 {@code acquireFenced}）+
	 *     {@code verifyHeld} 对 Redis 真值校验；别人持有/确定丢租 ⇒
	 *     {@link CancelFinalize#LEASE_HELD_ELSEWHERE}，设施不可确认 ⇒ {@link CancelFinalize#UNAVAILABLE}。
	 *     <b>不换 owner、不强行推进新 epoch、不释放调用方（原驱动）的租约</b>：{@code fresh=false}
	 *     一律不释放。这里也不声称"隔离了原驱动"——同 owner 重入拿的是同一 epoch，隔离靠的是
	 *     上面的 {@code resuming} 互斥与本进程的取消收尾监视器。</li>
	 * </ol>
	 *
	 * <h2>终态与幂等（I-1 / I-2）</h2>
	 * <ul>
	 * <li>快照已是 <b>DONE/FAILED</b> ⇒ {@link CancelFinalize#ALREADY_TERMINAL}，<b>绝不改写</b>；</li>
	 * <li>取消事实不成立而快照非终态 ⇒ {@link CancelFinalize#CANCEL_FACT_UNCONFIRMED}
	 *     （不可知：交棒按有界重试，仍不驱动）；</li>
	 * <li>幂等判据 = <b>归档里有没有终帧</b>（{@code cancelTerminalArchived}），不是快照状态：
	 *     已归档 ⇒ {@code ALREADY_TERMINAL}（不重写、不重发）；未归档 ⇒ 调
	 *     {@link ResilientChatService#cancelParked(String)} 补齐（快照已 CANCELLED 但 done 缺失的
	 *     边界因此可恢复），收尾后再复核归档。</li>
	 * </ul>
	 */
	public CancelFinalize cancelConfirmed(String runId) {
		if (this.resuming.putIfAbsent(runId, Boolean.TRUE) != null) {
			log.info("取消收尾暂缓：本进程已有执行体在推进这一轮 runId={}（交棒侧有界重试）", runId);
			return CancelFinalize.IN_PROGRESS;
		}
		boolean gateHeld = false;
		String gateSessionId = null;
		// 一次性 token：与续跑同纪律（首轮那条迟到 finally 的 token=runId 不会误删本持有者）。
		String gateToken = UUID.randomUUID().toString();
		LeaseService leases = this.store.leaseService();
		LeaseService.Lease lease = null;
		boolean fresh = false;
		try {
			RunSnapshot snapshot = this.store.get(runId);
			if (snapshot == null) {
				return CancelFinalize.NO_RUN;
			}
			if (RunSnapshot.isTerminal(snapshot.getStatus())
					&& !RunSnapshot.CANCELLED.equals(snapshot.getStatus())) {
				// I-1：延迟交棒绝不能把 DONE/FAILED 改写成 CANCELLED
				return CancelFinalize.ALREADY_TERMINAL;
			}
			CancellationRegistry.CancelState state = this.cancellations.cancelState(runId);
			if (state == CancellationRegistry.CancelState.UNKNOWN) {
				return CancelFinalize.CANCEL_UNKNOWN;
			}
			if (state != CancellationRegistry.CancelState.CANCELLED) {
				// M-6：取消事实此刻不可见 ⇒ 不可知。快照终态无可收尾（上面已返回），非终态交回
				// 交棒侧有界重试 —— 绝不因为"读到 NOT_CANCELLED"就驱动这一轮。
				return CancelFinalize.CANCEL_FACT_UNCONFIRMED;
			}
			// 幂等判据 = 归档里有没有终帧（不是快照状态：save 与 done 是两个存储）
			Boolean archived = this.chatService.cancelTerminalArchived(runId);
			if (Boolean.TRUE.equals(archived)) {
				return CancelFinalize.ALREADY_TERMINAL;
			}
			// 会话门（与 resumeInternal 同形：抢不到就不是这一拍的事）
			if (this.sessionGate != null) {
				SessionGate.Busy busy = this.sessionGate.acquire(snapshot.getSessionId(), runId, gateToken);
				if (busy != null && !runId.equals(busy.runId())) {
					log.info("取消收尾暂缓：会话门被另一轮占着 runId={} heldBy={}（交棒侧有界重试）", runId,
							busy.runId());
					return CancelFinalize.SESSION_BUSY;
				}
				gateHeld = true;
				gateSessionId = snapshot.getSessionId();
			}
			if (leases != null) {
				LeaseService.AcquireResult acquired = this.store.acquireRunLease(runId);
				if (acquired.outcome() == LeaseService.AcquireOutcome.HELD_ELSEWHERE) {
					return CancelFinalize.LEASE_HELD_ELSEWHERE;
				}
				if (acquired.outcome() == LeaseService.AcquireOutcome.UNAVAILABLE) {
					return CancelFinalize.UNAVAILABLE;
				}
				lease = acquired.lease();
				fresh = acquired.created();
				if (lease != null) {
					LeaseService.Hold hold = leases.verifyHeld(lease.key(), lease.epoch(), lease.owner());
					if (hold == LeaseService.Hold.LOST) {
						return CancelFinalize.LEASE_HELD_ELSEWHERE;
					}
					// hold == UNAVAILABLE：这一拍证明不了"我仍是主人"；继续也只是让写门再拒一次，
					// 收尾后的复核会把它如实报成 PERSIST_DENIED（不伪装成功）。
				}
			}
			this.chatService.cancelParked(runId);
			Boolean after = this.chatService.cancelTerminalArchived(runId);
			if (Boolean.TRUE.equals(after)) {
				return CancelFinalize.FINALIZED;
			}
			RunSnapshot latest = this.store.get(runId);
			if (latest != null && RunSnapshot.isTerminal(latest.getStatus())
					&& !RunSnapshot.CANCELLED.equals(latest.getStatus())) {
				// 并发方把它收成了 DONE/FAILED（合法终态）：没有终帧可补，也绝不改写
				return CancelFinalize.ALREADY_TERMINAL;
			}
			return CancelFinalize.PERSIST_DENIED;
		}
		catch (RuntimeException ex) {
			log.warn("取消收尾执行异常 runId={}（按未落定处理，交给有界重试）：{}", runId, ex.getMessage());
			return CancelFinalize.PERSIST_DENIED;
		}
		finally {
			if (gateHeld && gateSessionId != null && this.sessionGate != null) {
				try {
					this.sessionGate.release(gateSessionId, runId, gateToken);
				}
				catch (RuntimeException ex) {
					log.debug("取消收尾释放会话门失败 sessionId={} runId={}：{}", gateSessionId, runId,
							ex.getMessage());
				}
			}
			// 只还"本次创建"的租约（fresh=false = 原驱动/等待方在用那一任，绝不释放）
			if (fresh && lease != null) {
				releaseQuietly(leases, lease);
			}
			this.resuming.remove(runId);
		}
	}

	private static void releaseQuietly(LeaseService leases, LeaseService.Lease lease) {
		try {
			leases.release(lease);
		}
		catch (RuntimeException ex) {
			log.debug("取消收尾还租失败 key={} epoch={}：{}", lease.key(), lease.epoch(), ex.getMessage());
		}
	}

	/** 续跑入口与每次工具派发前共用的取消门；null = 可以继续。 */
	private ResumeResult cancelStateOutcome(String runId, Map<String, Object> body) {
		CancellationRegistry.CancelState state = this.cancellations.cancelState(runId);
		if (state == CancellationRegistry.CancelState.UNKNOWN) {
			return cancelUnknownOutcome(body);
		}
		if (state == CancellationRegistry.CancelState.CANCELLED) {
			this.chatService.cancelParked(runId);
			body.put("resumed", false);
			body.put("reason", "ALREADY_CANCELLED");
			return new ResumeResult(Outcome.ALREADY_CANCELLED, body);
		}
		return null;
	}

	/**
	 * {@code UNKNOWN}（取消状态不可知）的<b>唯一</b> typed 结局（既有契约，未新增码）：
	 * {@code CANCEL_STATE_UNKNOWN} + {@code reason=CANCEL_STATE_UNKNOWN} + {@code AI_REDIS_UNAVAILABLE}。
	 *
	 * <p>两个使用点共用同一份口径：①入口/逐条检查首次读到 {@code UNKNOWN}（{@link #cancelStateOutcome}）；
	 * ②<b>认领前复核</b>读到 {@code UNKNOWN} 后抛出的既有 {@code STATE_UNAVAILABLE}（在重建循环里翻译）。
	 * 两处必须逐字一致 —— 否则同一个"Redis 读抖动"会因落在窗口前/后而给出两种对外结局。
	 */
	private ResumeResult cancelUnknownOutcome(Map<String, Object> body) {
		body.put("resumed", false);
		body.put("reason", "CANCEL_STATE_UNKNOWN");
		body.put("code", ErrorCodes.AI_REDIS_UNAVAILABLE);
		return new ResumeResult(Outcome.CANCEL_STATE_UNKNOWN, body);
	}

	/**
	 * 续跑身份判据的<b>三态</b>：确定可用 / 确定不可用 / 此刻不可知。
	 *
	 * <p>
	 * 第三态是 B2 修正的核心：配置读不到、或行在但 key 解不开，都属于"此刻不知道"，
	 * 既不能放行（会让"答得出、存不下"的幽灵续跑跑起来），也<b>不能判死</b>
	 * （一次读库抖动 ⇒ 永久 FAILED）。
	 */
	private record ResolvedIdentity(AiConfigService.ChatTarget target, boolean unknown, String detail) {

		static ResolvedIdentity present(AiConfigService.ChatTarget target) {
			return new ResolvedIdentity(target, false, null);
		}

		static ResolvedIdentity absent() {
			return new ResolvedIdentity(null, false, null);
		}

		static ResolvedIdentity unknown(String detail) {
			return new ResolvedIdentity(null, true, detail);
		}

	}

	/**
	 * 续跑身份解析：<b>硬否定（{@code PROVIDER_GONE}）之前必须先强制重载一次配置，且只有
	 * "重载成功 + 仍为空 + 行级声明也没有"才算确定不可用</b>。
	 *
	 * <h2>为什么先刷新</h2>
	 * {@code resolveChatTarget} 读的是<b>配置快照</b>，而快照只在版本变化时重载。跨实例场景里"另一个
	 * 实例刚把 provider 写进共享库"与"本实例的快照还没看到"完全可能同时成立（run 40182796512 的
	 * B/C 就是快照永久停在空列表）。此时按旧快照判 {@code PROVIDER_GONE} 是<b>把缓存陈旧当成端点消失</b>。
	 *
	 * <h2>为什么"刷新失败"不能算否</h2>
	 * {@link AiConfigService#load()} 会吞掉读库异常（{@code /health}、{@code /chat} 不该因此 500），
	 * 于是 {@code reload()} 在读库故障时<u>既不抛也不报</u>：调用方只看到"快照仍是旧的/空的"。
	 * 如果据此判死，一次 Hikari 超时 / 迁移锁 / 只读切换就会把这一轮变成<b>永久 FAILED</b>
	 * （本批把硬否定升级成了终态收敛，所以这条区分从"更好"变成"必需"）。
	 * 因此这里用 {@link AiConfigService#reloadWithOutcome()} 拿"这次到底刷新成功没有"：
	 * 不成功 ⇒ 第三态（{@code STATE_UNKNOWN}，快照保持原样，稍后重试）。
	 *
	 * <h2>为什么"行在但 key 解不开"也不能算否</h2>
	 * {@code resolveChatTarget} 要求 key 可解析；KEK 缺失/轮换/密文损坏时它同样返回空，但端点并没有
	 * 被删 —— 那种情形由 {@code binding}（{@link AiConfigService#bindingState(Long, String)} 的
	 * {@code KEY_UNREADABLE}）识别为第三态。只有"行确实没了/停用了/模型下架/从未声明 key"
	 * （{@code ABSENT}）才落 {@link #providerGone}。
	 */
	private ResolvedIdentity resolveForResume(String runId,
			java.util.function.Supplier<java.util.Optional<AiConfigService.ChatTarget>> lookup,
			java.util.function.Supplier<AiConfigService.BindingState> binding) {
		java.util.Optional<AiConfigService.ChatTarget> cached = lookup.get();
		if (cached.isPresent()) {
			return ResolvedIdentity.present(cached.get());
		}
		AiConfigService.ReloadResult reload;
		try {
			reload = this.aiConfigService.reloadWithOutcome();
		}
		catch (RuntimeException ex) {
			log.warn("续跑身份判据重载配置抛错 runId={}（按不可知处理，不判死不写终态）：{}", runId, ex.getMessage());
			return ResolvedIdentity.unknown("配置重载抛错");
		}
		if (reload == null || !reload.ok()) {
			// reload == null = 替身没打桩/实现缺失：同样按"不可知"处理（宁可不判死）。
			log.warn("续跑身份判据重载未成功 runId={}（读库不可用；按不可知处理，不判死不写终态）", runId);
			return ResolvedIdentity.unknown("配置快照刷新未成功");
		}
		java.util.Optional<AiConfigService.ChatTarget> refreshed = lookup.get();
		if (refreshed.isPresent()) {
			return ResolvedIdentity.present(refreshed.get());
		}
		AiConfigService.BindingState state;
		try {
			state = binding.get();
		}
		catch (RuntimeException ex) {
			log.warn("续跑身份判据读取行级状态失败 runId={}（按不可知处理）：{}", runId, ex.getMessage());
			return ResolvedIdentity.unknown("行级判据不可读");
		}
		if (state == AiConfigService.BindingState.KEY_UNREADABLE) {
			return ResolvedIdentity.unknown("provider/模型仍在配置里，但 key 此刻不可用");
		}
		if (state == AiConfigService.BindingState.RESOLVED) {
			// 不应发生（行/模型/key 都就绪时解析不会为空）。宁可当"不可知"也不判死。
			log.warn("续跑身份判据自相矛盾 runId={}（行级状态=RESOLVED 但解析为空）—— 按不可知处理", runId);
			return ResolvedIdentity.unknown("行级判据与解析结果不一致");
		}
		return ResolvedIdentity.absent();
	}

	/**
	 * {@code STATE_UNKNOWN} 结局（B2 修正）：配置此刻<b>不可确认</b>，这一轮既不判死也不放行。
	 *
	 * <p>
	 * 对外 503 + {@code reason=STATE_UNKNOWN, code=AI_REDIS_UNAVAILABLE}（与"待决条目读失败"、
	 * "取消状态未知"同口径）；快照保持 {@code SUSPENDED}、<b>不写终态、不出业务终帧</b>
	 * （出终帧就是判死）；只把"没人会再推进"的当前订阅收掉 —— 否则重挂会挂上一条永远不会再有帧的
	 * 连接（服务端 emitter 无传输超时），客户端只能等自己的超时。
	 */
	private ResumeResult stateUnknown(String runId, RunSnapshot snapshot, String detail) {
		log.warn("续跑暂缓：配置状态不可确认 runId={} providerId={} model={}（{}）—— 不判死、不写终态、不出业务帧，"
				+ "快照保持 {}，由派发方/重挂稍后重试", runId, snapshot.getProviderId(), snapshot.getModelId(),
				detail, snapshot.getStatus());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("runId", runId);
		body.put("resumed", false);
		body.put("reason", "STATE_UNKNOWN");
		body.put("code", ErrorCodes.AI_REDIS_UNAVAILABLE);
		body.put("providerId", snapshot.getProviderId());
		body.put("modelId", snapshot.getModelId());
		releaseSubscriptionsWithoutFrame(runId);
		return new ResumeResult(Outcome.STATE_UNKNOWN, body);
	}

	/**
	 * 只收掉这一轮<b>当下的订阅</b>（不置终态标志、不写终帧、不摘注册表条目）：
	 * 与 park 的"挂起即断"同一条语义 —— 连接不再悬挂，归档里的帧不受影响，重挂仍可回放并重试。
	 */
	private void releaseSubscriptionsWithoutFrame(String runId) {
		try {
			SseChatEmitter out = this.registry == null ? null : this.registry.find(runId);
			if (out != null) {
				out.completeSubscriptions();
			}
		}
		catch (RuntimeException ex) {
			log.debug("续跑暂缓收流失败 runId={}：{}", runId, ex.getMessage());
		}
	}

	/**
	 * {@code PROVIDER_GONE} 结局（409 语义）：快照的 provider/model 此刻<b>确定</b>不可用，<b>不跨
	 * provider、不换模型</b>续跑。
	 *
	 * <p>I3 起判据是<b>精确绑定</b>（{@code resolveChatTarget(providerId, modelId)}）：同名模型挂在
	 * 别家不算数；env 合成行（无 providerId）退化为"该模型名是否仍可解析"。
	 *
	 * <p><b>只有"确定"才走到这里</b>（B2 修正）：调用方已确认①配置刷新成功、②行级状态为
	 * {@code ABSENT}（行/模型不在、停用，或从未声明 key）。"刷新失败"或"key 解不开"
	 * （{@code KEY_UNREADABLE}）走 {@link #stateUnknown}。
	 *
	 * <p><b>B1（run 40182796512）</b>：判到硬否定就必须收敛 —— 快照落 {@code FAILED} + 机器可读的
	 * 终帧，而不是把这一轮留在 {@code SUSPENDED} 且"无人驱动、无帧可等"（客户端只能等到超时）。
	 */
	private ResumeResult providerGone(String runId, RunSnapshot snapshot, String detail) {
		log.warn("续跑拒绝：快照的 provider/模型已不可用 runId={} providerId={} model={}（{}）", runId,
				snapshot.getProviderId(), snapshot.getModelId(), detail);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("resumed", false);
		body.put("reason", "PROVIDER_GONE");
		body.put("providerId", snapshot.getProviderId());
		body.put("modelId", snapshot.getModelId());
		body.put("message", PROVIDER_GONE_MESSAGE);
		convergeProviderGone(runId, snapshot);
		return new ResumeResult(Outcome.PROVIDER_GONE, body);
	}

	/**
	 * {@code PROVIDER_GONE} 的终态收敛（B1）：<b>快照 FAILED + 机器可读终帧 + 收流</b>。
	 *
	 * <h2>为什么必须做（而不是只回 409）</h2>
	 * 409 只回答<b>这一次</b>调用方；这一轮本身仍停在 {@code SUSPENDED}，条目已全部结算却没有任何
	 * 驱动方 —— 后续重挂会挂上一条"不会再有帧"的连接（服务端用无传输超时的 emitter），客户端要么
	 * 永久等待，要么等到自己的超时（实测 120s）。{@link ProviderGoneException} 的类注早已明确这条
	 * 红线："必须落成明确终态，而不是只发一帧 error 就把快照留在非终态"。
	 *
	 * <p>
	 * <b>只对"确定不可用"生效</b>：调用点只有本类的身份预检（精确绑定解析为空）。租约不可读 /
	 * 状态未知 / 另一驱动方在跑，都<b>不</b>走这里 —— 那些是"此刻不知道"，不是"确定没了"：
	 * 写终态与出终帧都会被 {@link RunStore#save} / {@code SseChatEmitter#emit} 的只读判据拦下
	 * （见 {@code LeaseService.LeaseAuthority#readOnly()}），这里先显式跳过，避免"连接被收了但既无
	 * 终帧也无终态"的半死形态。
	 */
	private void convergeProviderGone(String runId, RunSnapshot snapshot) {
		if (runId == null) {
			return;
		}
		try {
			LeaseService.LeaseAuthority authority = this.store.runLeaseAuthority(runId);
			if (authority != null && authority.readOnly()) {
				log.warn("续跑硬否定未收敛 runId={}：写权限 {}（丢租/不可确认）—— 不写终态、不出终帧，"
						+ "由新主人按同一判据收尾", runId, authority);
				return;
			}
			RunSnapshot latest = this.store.get(runId);
			RunSnapshot target = latest == null ? snapshot : latest;
			if (target == null || RunSnapshot.isTerminal(target.getStatus())) {
				return;
			}
			target.setStatus(RunSnapshot.FAILED);
			target.setError("PROVIDER_GONE：" + PROVIDER_GONE_MESSAGE);
			target.setInFlightToolCalls(new ArrayList<>());
			this.store.save(target);
			SseChatEmitter out = this.registry == null ? null : this.registry.of(runId);
			if (out == null) {
				return;
			}
			out.error(ErrorCodes.AI_PROVIDER_GONE, providerGoneMessage());
			this.registry.close(runId);
			log.info("续跑硬否定已收敛 runId={} status=FAILED code={}（重挂会回放该终帧后收流）", runId,
					ErrorCodes.AI_PROVIDER_GONE);
		}
		catch (RuntimeException ex) {
			log.warn("续跑硬否定收敛失败 runId={}（快照/终帧未落地，由后续重挂或启动扫描再收敛）：{}", runId,
					ex.getMessage());
		}
	}

	/** 用户面文案：与 409 同一句；en 走 {@code messages_en}，绝不回落到内部信息。 */
	String providerGoneMessage() {
		if (!ReplyLanguage.current().english()) {
			return PROVIDER_GONE_MESSAGE;
		}
		if (this.messageSource != null) {
			try {
				return this.messageSource.getMessage("error." + ErrorCodes.AI_PROVIDER_GONE, null, Locale.ENGLISH);
			}
			catch (NoSuchMessageException ex) {
				log.debug("messages_en 缺 error.{}，回落 en 白名单短句", ErrorCodes.AI_PROVIDER_GONE);
			}
		}
		return PROVIDER_GONE_MESSAGE_EN;
	}

	/**
	 * 交棒后的存活判据（I7）：该轮快照仍存在且<b>非终态</b>。
	 *
	 * <p>与 {@code resuming} 表合用 = "本进程还有续跑任务在跑，且这一轮还没收尾"。读快照失败
	 * （Redis 抖动）按"仍活着"处理：误判为假会提前放掉一个正在跑的轮的续跑权。
	 */
	private boolean snapshotNotTerminal(String runId) {
		try {
			RunSnapshot snapshot = this.store.get(runId);
			return snapshot != null && !RunSnapshot.isTerminal(snapshot.getStatus());
		}
		catch (Exception ex) {
			return true;
		}
	}

	/**
	 * 结算标签（R6 裁决）：{@code <toolCallId>:<status>:<execution>}，其中 execution 取
	 * {@code executed-here}（本次续跑真的执行了）、{@code reused(<实例>) }（复用他实例的执行结果）、
	 * {@code not-executed}（未执行：放行前被取消/拒绝/超时/前端未回灌等）。
	 */
	private String settlementLabel(PendingToolCall pending) {
		String execution;
		if (!pending.isExecuted()) {
			execution = "not-executed";
		}
		else if (this.store.instanceId().equals(pending.getExecutedBy())) {
			execution = "executed-here";
		}
		else {
			execution = "reused(" + pending.getExecutedBy() + ")";
		}
		return pending.getToolCallId() + ":" + pending.getStatus() + ":" + execution;
	}

	/**
	 * 入口 kind 校验批（续跑假批准）：{@code APPROVED + FRONTEND} = 前端条目被写成了"已批准"
	 * （旧代码不校验入口时留下的数据）。结算由 {@link SpToolCallingManager#resolvePending} 落成
	 * blocked；本谓词只用于在续跑日志里点名这一现场。
	 */
	private static boolean isKindMisapproved(PendingToolCall pending) {
		return pending != null && PendingToolCall.APPROVED.equals(pending.getStatus())
				&& PendingToolCall.KIND_FRONTEND.equals(pending.getKind());
	}

	/**
	 * 停走级联时"仍未结算"的条目：未执行且状态仍是 {@code PENDING}（等外部输入/后端待执行）
	 * 或 {@code APPROVED}（已放行未执行）。已执行/已有结论的条目不得被覆盖成 SKIPPED。
	 */
	private static boolean isUnsettled(PendingToolCall pending) {
		if (pending == null || pending.isExecuted()) {
			return false;
		}
		String status = pending.getStatus();
		return PendingToolCall.PENDING.equals(status) || PendingToolCall.APPROVED.equals(status);
	}

	/**
	 * 从快照重建挂起那一轮的 {@code assistant(tool_calls)}（顺序与 id/type/name/arguments 原样）。
	 *
	 * <p><b>推理元数据（通用协议）</b>：{@code assistantMetadata} 整份挂回消息 —— 它是挂起时上游
	 * assistant 的 metadata 原文（{@code reasoningContent} 之外还可能有 {@code encrypted_content} /
	 * {@code signature} / {@code thinking} 等厂商私有键）。这里<b>不解析、不筛选、不截断</b>：
	 * 未知键必须一起过桥，否则"续跑"只在支持某一种推理字段的 provider 上成立。
	 * {@code null} = 挂起时没有 metadata（旧快照/旧调用方）⇒ 不写 metadata 键。
	 *
	 * <p>包级可见 + static：本方法不依赖任何字段，直接断言"metadata 与 tool_calls 全保真"比走完
	 * 整条 resume 链更精确（整条链另有 {@code ResumeReasoningMetadataTest} 覆盖）。
	 */
	static AssistantMessage rebuildAssistant(RunSnapshot snapshot) {
		List<AssistantMessage.ToolCall> calls = new ArrayList<>();
		for (PendingToolCall item : snapshot.getInFlightToolCalls()) {
			calls.add(new AssistantMessage.ToolCall(item.getToolCallId(),
					item.getType() == null ? "function" : item.getType(), item.getName(), item.getArguments()));
		}
		AssistantMessage.Builder builder = AssistantMessage.builder()
			.content(snapshot.getAssistantContent() == null ? "" : snapshot.getAssistantContent());
		Map<String, Object> metadata = snapshot.getAssistantMetadata();
		if (metadata != null) {
			builder.properties(metadata);
		}
		return builder.toolCalls(calls).build();
	}

	// ── 互斥：进程内表 + 续跑权租约（B23L1 批②b 起不再用 ai:resume:lock:） ──────
	//
	// 原实现 acquireResumeLock/releaseResumeLock 用 SETNX 拿一把 ai:resume:lock:<runId>，
	// 释放是"GET → Java 比对 → DEL"：比对与删除之间锁可能已过期并被别的实例重抢，于是
	// 删掉别人的锁（同一缺陷族见 LeaseService 类注 L6）。互斥语义整体并入续跑权租约：
	//   · 跨实例 = ai:lease:run:<runId>（epoch 比对在 Lua 里原子完成）；
	//   · 同进程 = 上面这张 resuming 表（租约可重入，挡不住同进程的两次并发续跑）。
	// 全仓 grep 确认 ai:resume:lock: 无外部读者后才摘除（运维文档里的历史叙述保留）。

}
