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
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tenant.LocalRunKey;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.RunKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
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
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

	/**
	 * 接管改判"<b>重挂租约</b>"的 body 键（返修 R2，{@code true} = 本次没有新起驱动）。
	 *
	 * <p>含义：本进程仍登记着这一轮的驱动方（{@code isDriving=true}）且本次已抢到租约 —— 同实例两个驱动方
	 * 共享同一份进程内租约视图，新起驱动会让旧驱动的写照样放行（双驱动），"拒绝+还租"又会把它围栏在外
	 * 且无人驱动。故本次把租约<b>重挂给旧驱动续租</b>，调用方（{@link #resumeWithReason}）据此
	 * <b>不得</b> {@code releaseRunLease}，改为 {@code markDriving(lease, 旧驱动存活判据)}。
	 */
	public static final String LEASE_REARMED_KEY = "leaseRearmed";

	/**
	 * 仅模型步重跑被前置条件拒绝的用户面短句（409 响应体的 {@code message}）。
	 *
	 * <p>与 {@link #PROVIDER_GONE_MESSAGE} 同一形态：<b>一句话说清"为什么不能这么做 + 该怎么做"</b>，
	 * 精确到哪一条没满足由响应体的 {@code failedCheck} 承载（前端按枚举出更细的提示）。
	 * 不新增码表项（{@code messages_*}/{@link com.example.configmgr.common.ErrorCodes#ALL} 的完备性
	 * 约束由既有契约测试锁定），因此这句中文原文与 en 译文各一份放在本类。
	 */
	public static final String RETRY_DENIED_MESSAGE = "本轮不满足「仅模型步重跑」的条件（需已失败、无在途执行、"
			+ "工具已全部结算且入出参配对、无半截正文或思考、同一模型、同一份固定历史），请新开一轮。";

	/** en 白名单兜底（不裸露内部信息）。 */
	public static final String RETRY_DENIED_MESSAGE_EN = "This turn does not qualify for a model-step-only retry "
			+ "(it must be failed, with no execution in flight, all tool calls settled and paired, no partial content "
			+ "or reasoning, the same model and the same fixed history). Please start a new turn.";

	/**
	 * 前端"选择动作"摘要的前缀（{@code extra.recentActions} 条目形如
	 * {@code "已选择配置项：CURRENCY（来源：界面）"}）。
	 *
	 * <p><b>跨端字面量</b>：来源是 {@code frontend/src/stores/workspace.ts#setSelectedDefs}
	 * （选中与清空共用这一个写入口，清空时摘要为「已选择配置项：（空）」）。只用于
	 * {@link #selectionActionRecorded} 的"有没有后续改选/清空"判定；前端改文案时这里必须同改。
	 */
	static final String SELECTION_ACTION_SUMMARY_PREFIX = "已选择配置项";

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
	 * 批间运行上下文刷新器（多步工作流批的续跑侧入口）。
	 *
	 * <p>为什么续跑侧必须有它：生产 {@code app.ai.park.enabled=true} 时 {@code create_task} /
	 * {@code navigate_to} / {@code confirm_step} 都在 {@code SpToolCallingManager} 执行循环的
	 * <b>唯一刷新点之前</b>挂起，刷新一次都跑不到 —— 续跑随后拿"请求边界"的旧 {@link AiContext}
	 * 构造下一跳，步骤工具永远不披露。字段注入保持 10 参构造器与既有直接 new 的用例零改动；
	 * 缺席时行为与改造前一致（不刷新），但会留一条 WARN。
	 */
	@Autowired(required = false)
	private RunContextRefresher contextRefresher;

	/** 可选注入（生产装配同一个刷新器；轻装配面不注入 = 不刷新）。 */
	@Autowired(required = false)
	public void setContextRefresher(RunContextRefresher refresher) {
		this.contextRefresher = refresher;
	}

	/**
	 * 任务槽位解析器（可选）：续跑交棒前按<b>最新</b>上下文重算 {@code extra[taskSlots]} ——
	 * 挂起期用户在向导里改了步骤/勾选后，旧槽位不得继续把模型引回旧选择（显式空仍是权威空）。
	 *
	 * <p>字段注入保持 10 参构造器与既有直接 new 的用例零改动；未注入时只跳过重算（旧槽位原样保留）。
	 */
	private com.example.configmgr.ai.slot.TaskSlotResolver taskSlotResolver;

	/** 可选注入（槽位重算；缺席 = 不重算）。 */
	@Autowired(required = false)
	public void setTaskSlotResolver(com.example.configmgr.ai.slot.TaskSlotResolver resolver) {
		this.taskSlotResolver = resolver;
	}

	/**
	 * 宿主确定性定义选择表单（新建任务 → 导航到选择步骤 → 配置未知 ⇒ 宿主自己挂起平铺多选）。
	 *
	 * <p>为什么挂在续跑侧：{@code navigate_to} 是前端工具，生产 {@code park=true} 时它在首轮的
	 * 等待处就结束了执行，真正的结算与"下一跳上下文"都在本类 —— 于是"导航成功"之后、
	 * 模型再次被问之前的<b>唯一</b>确定点就在这里（既不改前端，也不新增中断路径）。
	 *
	 * <p>字段注入与既有可选依赖同姿态（不改 10 参构造器）；缺席 = 不挂起，行为与改造前一致。
	 */
	private com.example.configmgr.ai.form.DefinitionSelectionForm definitionSelectionForm;

	/** 可选注入（缺席 = 不生成宿主表单，模型侧既有路径不变）。 */
	@Autowired(required = false)
	public void setDefinitionSelectionForm(com.example.configmgr.ai.form.DefinitionSelectionForm form) {
		this.definitionSelectionForm = form;
	}

	/**
	 * 宿主确定性筛选条件文本框（进入 {@code QUERY_COND} + 条件未给 + 主动任务链 ⇒ 宿主自己挂起）。
	 *
	 * <p>为什么也挂在续跑侧：{@code confirm_step} 是前端工具 —— 把它推进到 {@code QUERY_COND} 的那一批
	 * 在 park 下同样以续跑收尾，于是"刚进入条件步、模型还没被问"的确定点也在这里。判据里的步骤与
	 * 槽位读的是<b>交棒那一份</b>（批间刷新 + 权威同步之后），因此已过条件步（{@code EXPORT} 及之后）
	 * 不会再弹。
	 *
	 * <p>字段注入与既有可选依赖同姿态（不改 10 参构造器）；缺席 = 不挂起。
	 */
	private com.example.configmgr.ai.form.ConditionsForm conditionsForm;

	/** 可选注入（缺席 = 不弹条件框，模型侧既有路径不变）。 */
	@Autowired(required = false)
	public void setConditionsForm(com.example.configmgr.ai.form.ConditionsForm form) {
		this.conditionsForm = form;
	}

	/**
	 * 进程内续跑互斥（同进程并发续跑的唯一闸；租约管的是跨实例）。
	 *
	 * <p>键 = <b>身份 + runId</b>（{@link LocalRunKey}）：runId 不是跨身份唯一定性，A 在续跑 X 时
	 * B 的同名轮次必须能照常续跑（否则 B 会被永久报成 {@code IN_PROGRESS}）。无身份 = legacy 桶，
	 * 不伪造默认身份。键在入口定一次、全程沿用（交棒任务与 finally 都用那把键摘除）。
	 */
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
		SESSION_BUSY,

		/**
		 * 批次2 / AR-05：<b>仅模型步重跑</b>的前置条件不满足（七条件里有任一条为假）。
		 *
		 * <p>它不是"没找到"也不是"服务不可用"：轮次在、服务在，只是<b>这一轮不允许</b>用窄入口重跑
		 * （已取消 / 未失败 / 还有待决工具 / 有半截正文或思考 / 工具配对不完整 / 模型身份变了）。
		 * 对外 409 + 既有稳定码 {@code ILLEGAL_STATE}（"当前状态不允许该操作"，不新增码表项）
		 * + {@code failedCheck}，前端据此在三种失败形态间选动作（重跑 / 中性未确认视图 / 新开一轮）。
		 */
		RETRY_PRECONDITION_FAILED;

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
					|| this == SESSION_BUSY
					// 批次2（AR-05）：前置条件不满足 = 本拍没有任何执行者接手这一轮的驱动权 ⇒ 还租
					|| this == RETRY_PRECONDITION_FAILED;
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

	/**
	 * 发现通道：全部仍存活的轮次（重启后"ResumeService 发现 pending"的入口）。
	 *
	 * <h2>两条口径（按调用线程有没有身份分）</h2>
	 * <ul>
	 * <li><b>系统恢复面</b>（启动扫描 / 运行期扫描，线程无身份）：{@code ai:runs} 的复合成员自带
	 *     {@code tenantId/userId}，逐条 {@link RunIdentityScope#open(long, long)} 后再读 —— <b>不需要</b>
	 *     扫描租户列表，也不需要"先读快照再猜身份"（快照键本身由身份决定，先读必然读不到）。
	 *     条目里带上 {@code tenantId/userId}，续跑侧据此直接进入该轮身份，
	 *     不再出现"定时线程上 {@code store.get} 恒为 {@code null}"的假死链；</li>
	 * <li><b>请求面</b>（HTTP 列表，线程有身份）：仍走 {@link RunStore#allRunIds()}（只返回<b>当前身份</b>
	 *     的轮次），绝不把别人的轮次列进来；</li>
	 * <li>两者都不丢：pre-deploy 的裸成员（无归属）只在<b>无身份</b>线程上按旧键形发现（过渡期口径）。</li>
	 * </ul>
	 */
	public List<Map<String, Object>> listRuns() {
		List<Map<String, Object>> out = new ArrayList<>();
		java.util.Set<String> seen = new java.util.LinkedHashSet<>();
		for (RunKeyScope.IndexRef ref : systemRunRecords()) {
			String key = ref.tenantId() + "/" + ref.userId() + "/" + ref.runId();
			if (!seen.add(key)) {
				continue;
			}
			try (RunIdentityScope.Scope ignored = RunIdentityScope.open(ref.tenantId(), ref.userId())) {
				Map<String, Object> item = listRun(ref.runId(), ref.tenantId(), ref.userId());
				if (item != null) {
					out.add(item);
				}
			}
		}
		// 当前身份（请求面）或过渡期的 legacy 裸成员：键由线程身份决定，交给 store 的既有过滤
		TenantUser current = RequestContextUtils.currentOrNull();
		for (String runId : this.store.allRunIds()) {
			String key = (current == null ? "" : current.tenantId() + "/" + current.userId()) + "/" + runId;
			if (!seen.add(key)) {
				continue;
			}
			Map<String, Object> item = listRun(runId, current == null ? 0L : current.tenantId(),
					current == null ? 0L : current.userId());
			if (item != null) {
				out.add(item);
			}
		}
		return out;
	}

	/**
	 * 系统恢复面的候选（复合成员）：<b>无身份线程</b>上返回全部带身份成员；有身份（请求面）时返回空 ——
	 * 列表端点只走 {@link RunStore#allRunIds()}（只看自己身份），<b>不</b>对别人的成员做活/死判定
	 * （那会读别人的键，既是隔离问题也是 O(全索引) 的额外往返）。
	 */
	private List<RunKeyScope.IndexRef> systemRunRecords() {
		if (RequestContextUtils.currentOrNull() != null) {
			return List.of();
		}
		return this.store.allRunRecords();
	}

	/** 单条列表项（在<b>该条记录身份</b>的作用域内构造）。 */
	private Map<String, Object> listRun(String runId, long tenantId, long userId) {
		RunSnapshot snapshot = tenantId > 0L && userId != 0L ? this.store.get(runId, tenantId, userId)
				: this.store.get(runId);
		if (snapshot == null) {
			return null;
		}
		// C2：续跑入口的清单用非抛读口；guard 读失败时仍能拿到原始条目并按 PENDING 保守处理。
		List<PendingToolCall> pendings = this.store.pendingsLenient(runId);
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("runId", runId);
		// 身份随条目交给扫描方：续跑入口据此 useContext（不读快照猜身份、不扫描租户列表）
		item.put("tenantId", snapshot.getTenantId());
		item.put("userId", snapshot.getUserId());
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
				&& System.currentTimeMillis() - activeAtMs > this.properties.getResume().getZombieAge().toMillis();
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
		return item;
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
		List<Message> history = this.codec.deserializeAll(snapshot.getMessageJson());
		List<String> roles = history.stream()
			.map(message -> message.getMessageType().name())
			.collect(Collectors.toList());
		List<PendingToolCall> pendings = this.store.pendingsLenient(runId);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("exists", true);
		out.put("snapshot", snapshot.asMap());
		out.put("context", snapshot.getContext());
		out.put("historyRoles", roles);
		out.put("inFlight", snapshot.getInFlightToolCalls().stream().map(PendingToolCall::asMap).toList());
		out.put("pending", pendings.stream().map(PendingToolCall::asMap).toList());
		out.put("ledger", this.store.ledger(runId));
		out.put("ledgerCounts", this.store.ledgerCounts(runId));
		out.put("instanceId", this.store.instanceId());
		// 批次2（AR-05 / AR-09）：**失败现场**与"三种失败形态该给哪个动作"的只读判据。
		// 全部来自 Redis（快照 + 台账 + 待决条目），不新增 DB 读写；前端据此在
		// "仅模型步重跑 / 中性未确认视图 + 新开一轮"之间选，不需要自己反解历史。
		out.put("failureScene", failureScene(snapshot, pendings, history));
		ModelStepRetryCheck retryCheck = checkModelStepRetry(snapshot, pendings, history, reasoningVisible());
		out.put("canRetryModelStep", retryCheck.allowed());
		out.put("retryModelStepDeniedBy", retryCheck.failedCheck());
		out.put("keys", describeKeys(runId, snapshot));
		return out;
	}

	/**
	 * 失败现场（只读、只来自 Redis）：{@code code/message/partialChars/toolCallsSettled/ledgerCount}。
	 *
	 * <p><b>边界（AR-01）</b>：失败现场<b>不</b>依赖 DB 新增列/写入 —— 快照的 {@code error} 与帧归档
	 * 就是现场本身；超过 24h（Redis 运行态到期）后不可恢复，这是如实登记的限制。
	 */
	private Map<String, Object> failureScene(RunSnapshot snapshot, List<PendingToolCall> pendings,
			List<Message> history) {
		Map<String, Object> scene = new LinkedHashMap<>();
		scene.put("status", snapshot.getStatus());
		scene.put("error", snapshot.getError());
		scene.put("finalText", snapshot.getFinalText());
		scene.put("partialChars", snapshot.getAssistantContent() == null ? 0 : snapshot.getAssistantContent().length());
		scene.put("toolCallsSettled", pendings.stream()
			.noneMatch(pending -> PendingToolCall.PENDING.equals(pending.getStatus())
					&& !PendingToolCall.KIND_BACKEND.equals(pending.getKind())));
		scene.put("toolPairsComplete", toolPairsComplete(history));
		scene.put("attempts", snapshot.getAttempts());
		return scene;
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
		// ===== 身份作用域：抢租 / 还租 / 续租判据都必须落在<b>记录身份</b>下 =====
		// 租约键（{@code ai:lease:run:{<t>:<u>:run:<id>}}）由身份拼出：抢租发生在读快照之前，
		// 若用调用线程身份（定时线程 = 无身份）拼键，抢到的是一把 legacy 租约 —— 真实那一轮既没被围栏，
		// 又会被另一实例当成"无人持有"再次接管（双驱动）。因此身份必须在<b>进入本方法时</b>就定下来：
		// 调用方（启动扫描 = 索引成员身份 / 确认门 = 交棒时的记录身份 / HTTP = 请求身份）负责给出。
		TenantUser identity = RequestContextUtils.currentOrNull();
		// 进程内互斥的本地键（身份 + runId）：同 runId 不同身份是两轮，不得互相判成 IN_PROGRESS。
		// 键在<b>入口</b>定一次并一路沿用 —— 中途的 RunIdentityScope 会按记录身份覆盖线程身份
		// （旧记录 0/0 时甚至是清空），若在 finally 里现取，摘除键会与登记键不同（互斥位永久占住）。
		String resumeKey = LocalRunKey.of(identity, runId);
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			// ===== 接管前初检（M-1）=====
			// 抢租会把 fence 推到新 epoch（旧驱动从此只读），所以"驱动还活着"的判定必须在<b>还没夺权</b>时做。
			// 否则"先抢租 → 复核发现驱动还活 → 拒绝并把租约还掉"的净结果是
			// 「fence 已推进 + 租约被删 + 无人驱动」：原驱动被围栏在外，这一轮再也没人推。
			if (forceTakeover) {
				ResumeResult refused = takeoverPreCheck(runId);
				if (refused != null) {
					return refused;
				}
			}
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
				result = resumeInternal(runId, resumeKey, forceTakeover, lease, fresh, resumeReason, replyLocale);
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
					// 判据在续租线程上执行：身份随租约携带（不是"那一拍线程碰巧是谁"）；
					// 互斥键也用<b>本拍登记的那一把</b>（续租线程没有身份，现取会落到 legacy 桶）。
					leases.markDriving(lease,
							() -> this.resuming.containsKey(resumeKey) && snapshotNotTerminal(runId, identity),
							identity);
				}
				else if (Boolean.TRUE.equals(result.body() == null ? null : result.body().get(LEASE_REARMED_KEY))) {
					// ===== 同实例收口（返修 R2）：租约重挂给仍在跑的旧驱动，<b>不得还租</b> =====
					// 还租会让旧驱动被 fence 围栏在外、且这一轮无人驱动；新起驱动又会共享同一份进程内租约视图
					// （旧驱动的写照样放行）⇒ 双驱动。故把这一任交给旧驱动续租：
					// 先摘掉可能仍是旧 epoch 的续租任务（它会在下一拍判 LOST 自行停，留着会让新任务登记不上），
					// 再按"本进程仍有该轮驱动登记 && 快照非终态"起续租 —— 驱动收尾后下一拍即停续租并还租。
					leases.stopDriving(lease.key(), lease.owner());
					leases.markDriving(lease, () -> this.chatService != null && this.chatService.isDriving(runId)
							&& snapshotNotTerminal(runId, identity), identity);
					log.info("续跑权已重挂给本进程仍在运行的驱动 runId={} epoch={}（不新起驱动、不还租）", runId,
							lease.epoch());
				}
				else if (result.outcome().releasesLease()) {
					// L-19：只有"确认无人会接手"的结局才还租。IN_PROGRESS = 本进程已有胜方在跑 ——
					// 创建者输掉 resuming 互斥时，它手里这份租约仍是胜者落库前用的那一任，
					// 还了就把胜方的 fencing 写打回 plain SET（fence 键不再推进）。
					this.store.releaseRunLease(runId);
				}
			}
			return result;
		}
	}

	/**
	 * <b>接管前初检</b>（M-1）：zombie 接管（{@code forceTakeover=true}）在<b>抢租之前</b>先判一次
	 * "驱动是否还活着"，活着就<b>什么都不动</b>地拒绝（不抢租 ⇒ fence 不推进、不还租）。
	 *
	 * <p>两条证据（与抢租后的复核同源，只是提前）：
	 * <ol>
	 * <li><b>本进程的驱动方仍持有效租约</b>（{@code isDriving} && 进程内 epoch &gt; 0）——
	 *     登记本身只说明"线程还在"，租约还在才说明"它仍是这一轮的主人"；</li>
	 * <li><b>业务活动锚点仍在僵尸窗口内</b>（快照 RUNNING 且 {@code activeAtMs} 距今 ≤
	 *     {@code app.ai.resume.zombie-age}）—— 判活方那一拍之后业务又前进了。</li>
	 * </ol>
	 *
	 * <p>返回 {@code null} = 通过初检（继续抢租，按 fresh 决定后续）；不通过 = {@code ALREADY_RUNNING}。
	 */
	private ResumeResult takeoverPreCheck(String runId) {
		if (this.chatService != null && this.chatService.isDriving(runId) && this.store.heldRunEpoch(runId) > 0L) {
			return refuseTakeover(runId, "DRIVER_ALIVE_IN_PROCESS", null, null);
		}
		// 租约此刻已被某方持有（别人 / 或本进程别的驱动方）：不再做活动初检，把结论交给抢租与抢租后的复核 ——
		// 别人持有 ⇒ LEASE_HELD_ELSEWHERE（比"活动还新"更具体、也是既有对外语义）；
		// 本进程重入（created=false）⇒ 抢租后按 !fresh 拒绝（不动租约）。
		LeaseService facility = this.store.leaseService();
		if (facility != null) {
			boolean leaseHeld;
			try {
				leaseHeld = facility.isHeld(this.store.runLeaseKey(runId));
			}
			catch (RuntimeException ex) {
				// 探针读不出来 ≠ 没人持有：按"有人持有"处理，把结论交给抢租脚本（它会给出权威答案）
				log.debug("接管前初检读租约失败 runId={}（交给抢租判定）：{}", runId, ex.getMessage());
				leaseHeld = true;
			}
			if (leaseHeld) {
				return null;
			}
		}
		RunSnapshot snapshot;
		try {
			snapshot = this.store.get(runId);
		}
		catch (RuntimeException ex) {
			// 读不出来不是"可以接管"：交给常规入口的 STATE_UNKNOWN 等既有分支处理（这里不改变方向）
			log.debug("接管前初检读快照失败 runId={}（交给常规入口）：{}", runId, ex.getMessage());
			return null;
		}
		if (snapshot == null || !RunSnapshot.RUNNING.equals(snapshot.getStatus())) {
			return null;
		}
		long activeAtMs = activeAtMs(snapshot);
		long zombieAgeMs = this.properties.getResume().getZombieAge().toMillis();
		long activeAgeMs = System.currentTimeMillis() - activeAtMs;
		if (activeAgeMs <= zombieAgeMs) {
			return refuseTakeover(runId, "ACTIVITY_ADVANCED", activeAtMs, activeAgeMs);
		}
		return null;
	}

	/** 接管否决的统一出口（初检与抢租后复核共用；形态 = {@code ALREADY_RUNNING} + 具名 reason）。 */
	private ResumeResult refuseTakeover(String runId, String reason, Long activeAtMs, Long activeAgeMs) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("runId", runId);
		body.put("resumed", false);
		body.put("reason", reason);
		if ("DRIVER_ALIVE_IN_PROCESS".equals(reason)) {
			body.put("driverAlive", true);
			log.warn("僵尸接管否决 runId={}：本进程的驱动方仍持有这一轮的租约（不抢租、不还租，驱动不受影响）",
					runId);
		}
		else {
			body.put("activeAtMs", activeAtMs);
			body.put("activeAgeMs", activeAgeMs);
			log.warn("僵尸接管否决 runId={}：业务活动仍在僵尸窗口内（{}ms ≤ 阈值 {}ms）—— 不抢租，让给原驱动",
					runId, activeAgeMs, this.properties.getResume().getZombieAge().toMillis());
		}
		body.put("instanceId", this.store.instanceId());
		return new ResumeResult(Outcome.ALREADY_RUNNING, body);
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
		// 决策回灌/挂起重挂等入口：未显式标注原因时按 reattach 记（"其余续跑入口"的兜底口径）。
		// fresh=false：租约由调用方持有（本方法不抢也不还），抢租后的复核因此可以安全否决
		// （拒绝不动租约 ⇒ 不会出现"fence 已推进 + 无人驱动"，见 {@link #takeoverPreCheck}）。
		return resumeInternal(runId, LocalRunKey.of(runId), forceTakeover, lease, false, RESUME_REATTACH,
				ErrorBodyFactory.requestLocale());
	}

	/**
	 * <b>仅模型步重跑</b>的窄入口（AR-05 / 批次2 Task 4 Step 5）—— 前端"重试本轮"按钮的后端面。
	 *
	 * <h2>它做什么</h2>
	 * 对一个<b>已失败</b>的轮，只重跑<b>模型步</b>：以快照里<b>固定的那份历史</b>直接再发一次请求
	 * （{@code driveResumed}），随后按既有终局路径落定（DONE/FAILED/取消）。它<b>不做</b>：
	 * 不重建工具轮、不重执行任何工具、不追加用户消息、不换模型、不改历史切片（不压缩、不重算记忆窗口、
	 * 不重建 system）。
	 *
	 * <h2>七条前置（全满足才允许）</h2>
	 * <ol>
	 * <li>该轮<b>已失败</b>（{@code status=FAILED}）；DONE / CANCELLED / 运行中一律不重跑；</li>
	 * <li><b>无在途执行</b>：取到本轮续跑权租约（别的实例持有 ⇒ {@code LEASE_HELD_ELSEWHERE}）
	 *     且进程内互斥为空（{@code IN_PROGRESS}）；</li>
	 * <li>待决工具<b>全结算</b>（仍有外部 PENDING ⇒ {@code PENDING_UNRESOLVED}，复用既有结局）；</li>
	 * <li>工具<b>入出参配对完整</b>（history 里的 {@code tool_calls} 与 {@code tool} 响应 id 集合相等）；</li>
	 * <li><b>无半截正文/半截思考</b>（{@code assistantContent} 为空、无 {@code stopped} 半截、
	 *     {@code finalText} 为空、快照 metadata 无思考全文）；</li>
	 * <li><b>同模型</b>：快照的 provider/model 此刻仍可解析成<b>同一个</b>模型（下架 ⇒
	 *     {@code PROVIDER_GONE}；解析到别的模型 ⇒ 前置失败 {@code MODEL_CHANGED}）；</li>
	 * <li><b>同固定历史</b>：直接拿 {@link RunSnapshot#getMessageJson()}，不做任何改写。</li>
	 * </ol>
	 *
	 * <h2>为什么不做"失效替换"</h2>
	 * 有半截正文/思考时（第 5 条不满足）<b>不</b>给自动"把半截换成新结果"的路径 —— 那需要新的帧序
	 * 协议（旧的半截怎么作废、seq 怎么续、重连怎么对齐），收益远小于风险。此时只给
	 * <b>中性"状态未确认"视图 + 手动入口</b>（重新载入 / 新开一轮），由前端按 409 的
	 * {@code failedCheck=PARTIAL_CONTENT} 呈现。
	 *
	 * <h2>取消优先（批次2）</h2>
	 * 入口检查不是原子的：取消可能恰在检查之后、状态写 RUNNING 之前落库。因此状态转换必须走
	 * {@link RunStore#saveStatusUnlessCancelled(RunSnapshot)}（<b>不是</b>普通 {@code save}）——
	 * 当前快照已是取消态时这一笔被拒写，本方法返回 {@link Outcome#ALREADY_CANCELLED}
	 * （{@code retried=false / reason=ALREADY_CANCELLED}）且<b>不交棒线程池</b>。
	 *
	 * @return 202（{@code ACCEPTED}）/ 200（已收尾，幂等不动作）/ 409（在途或前置失败）/ 404 / 503
	 */
	public ResumeResult retryModelStep(String runId) {
		return retryModelStep(runId, ErrorBodyFactory.requestLocale());
	}

	/** 仅模型步重跑（带请求 locale；回答语言与错误文案都按它取）。 */
	public ResumeResult retryModelStep(String runId, Locale replyLocale) {
		// 进程内互斥的本地键（身份 + runId）：与续跑同一张表、同一口径。
		String resumeKey = LocalRunKey.of(runId);
		LeaseService leases = this.store.leaseService();
		boolean fresh = false;
		LeaseService.Lease lease = null;
		if (leases != null) {
			LeaseService.AcquireResult acquired = this.store.acquireRunLease(runId);
			if (acquired.outcome() == LeaseService.AcquireOutcome.HELD_ELSEWHERE) {
				return new ResumeResult(Outcome.LEASE_HELD_ELSEWHERE, Map.of("runId", runId, "resumed", false,
						"retried", false, "reason", "LEASE_HELD_ELSEWHERE", "instanceId", this.store.instanceId()));
			}
			lease = acquired.lease();
			fresh = acquired.created();
		}
		ResumeResult result;
		try {
			result = retryModelStepInternal(runId, resumeKey, lease, replyLocale);
		}
		catch (RuntimeException ex) {
			if (fresh) {
				this.store.releaseRunLease(runId);
			}
			throw ex;
		}
		if (fresh) {
			if (result.outcome() == Outcome.ACCEPTED) {
				TenantUser identity = RequestContextUtils.currentOrNull();
				leases.markDriving(lease,
						() -> this.resuming.containsKey(resumeKey) && snapshotNotTerminal(runId, identity),
						identity);
			}
			else if (result.outcome().releasesLease()) {
				this.store.releaseRunLease(runId);
			}
		}
		return result;
	}

	private ResumeResult retryModelStepInternal(String runId, String resumeKey, LeaseService.Lease lease,
			Locale replyLocale) {
		if (lease != null) {
			LeaseService leases = this.store.leaseService();
			LeaseService.Hold hold = leases == null ? LeaseService.Hold.UNAVAILABLE
					: leases.verifyHeld(lease.key(), lease.epoch(), lease.owner());
			if (hold == LeaseService.Hold.LOST) {
				return new ResumeResult(Outcome.LEASE_HELD_ELSEWHERE, Map.of("runId", runId, "resumed", false,
						"retried", false, "reason", "LEASE_HELD_ELSEWHERE", "instanceId", this.store.instanceId()));
			}
		}
		if (this.resuming.putIfAbsent(resumeKey, Boolean.TRUE) != null) {
			return new ResumeResult(Outcome.IN_PROGRESS, Map.of("runId", runId, "resumed", false, "retried", false,
					"reason", "RESUME_IN_PROGRESS", "instanceId", this.store.instanceId()));
		}
		boolean handedOff = false;
		RunIdentityScope.Scope identityScope = null;
		java.util.concurrent.atomic.AtomicBoolean gateHeld = new java.util.concurrent.atomic.AtomicBoolean();
		String gateSessionId = null;
		String gateToken = UUID.randomUUID().toString();
		try {
			RunSnapshot snapshot = this.store.get(runId);
			if (snapshot == null) {
				return new ResumeResult(Outcome.NOT_FOUND, Map.of("runId", runId, "resumed", false, "retried", false,
						"reason", "UNKNOWN_RUN", "instanceId", this.store.instanceId()));
			}
			// 仅模型步重跑同样是恢复型入口：身份只从本轮记录恢复。
			identityScope = RunIdentityScope.open(snapshot.getTenantId(), snapshot.getUserId());
			String sessionId = snapshot.getSessionId();
			AiTrace.bind(runId, sessionId);
			AiTrace.markResumeReason(RESUME_MANUAL);
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("runId", runId);
			body.put("sessionId", sessionId);
			body.put("retried", false);
			body.put("modelStepOnly", true);
			body.put("statusBeforeRetry", snapshot.getStatus());
			body.put("instanceId", this.store.instanceId());

			// 取消优先：取消事实成立 ⇒ 不重跑（沿用既有结局与文案）
			ResumeResult cancelAtEntry = cancelStateOutcome(runId, body);
			if (cancelAtEntry != null) {
				return cancelAtEntry;
			}
			List<PendingToolCall> pendings;
			try {
				pendings = this.store.pendings(runId);
			}
			catch (RuntimeException ex) {
				log.warn("仅模型步重跑读取待决条目失败 runId={}（按未知拒绝，不驱动）", runId, ex);
				body.put("reason", "STATE_UNKNOWN");
				body.put("code", ErrorCodes.AI_REDIS_UNAVAILABLE);
				return new ResumeResult(Outcome.STATE_UNKNOWN, body);
			}
			List<Message> fixedHistory = new ArrayList<>(this.codec.deserializeAll(snapshot.getMessageJson()));
			// ①②③④⑤⑦：状态/待决/配对/半截/历史形状（②的跨实例面已由租约+verifyHeld 证明）
			ModelStepRetryCheck check = checkModelStepRetry(snapshot, pendings, fixedHistory, reasoningVisible());
			if (!check.allowed()) {
				if ("PENDING_TOOLS".equals(check.failedCheck())) {
					body.put("reason", "PENDING_UNRESOLVED");
					body.put("unresolved", pendings.stream()
						.filter(pending -> PendingToolCall.PENDING.equals(pending.getStatus()))
						.filter(pending -> !PendingToolCall.KIND_BACKEND.equals(pending.getKind()))
						.map(PendingToolCall::asMap)
						.toList());
					return new ResumeResult(Outcome.PENDING_UNRESOLVED, body);
				}
				return retryPreconditionDenied(body, check);
			}
			// ⑥ 同模型：此刻仍可解析成<b>同一个</b> provider/模型（下架 ⇒ PROVIDER_GONE）
			String identityProblem = sameModelProblem(runId, snapshot);
			if ("GONE".equals(identityProblem)) {
				return providerGone(runId, snapshot, "该 provider/模型此刻不可用");
			}
			if ("UNKNOWN".equals(identityProblem)) {
				return stateUnknown(runId, snapshot, "同一模型此刻不可确认");
			}
			if (identityProblem != null) {
				return retryPreconditionDenied(body,
						new ModelStepRetryCheck(false, "MODEL_CHANGED", identityProblem));
			}
			// 会话门（与续跑同源：同 sessionId 单值门；park 期间可能被另一轮占着）
			if (this.sessionGate != null) {
				SessionGate.Busy busy = this.sessionGate.acquire(sessionId, runId, gateToken);
				if (busy != null && !runId.equals(busy.runId())) {
					body.put("reason", "SESSION_BUSY");
					body.put("heldBy", busy.runId());
					body.put("message", "该会话当前有另一轮在跑；本轮的重跑已拒绝，请稍后再试。");
					return new ResumeResult(Outcome.SESSION_BUSY, body);
				}
				gateHeld.set(true);
				gateSessionId = sessionId;
			}
			LeaseService.LeaseAuthority authority = this.store.runLeaseAuthority(runId);
			if (authority != null && authority.readOnly()) {
				body.put("reason", authority == LeaseService.LeaseAuthority.LOST ? "LEASE_HELD_ELSEWHERE"
						: "LEASE_UNAVAILABLE");
				body.put("code", ErrorCodes.AI_REDIS_UNAVAILABLE);
				return new ResumeResult(authority == LeaseService.LeaseAuthority.LOST ? Outcome.LEASE_HELD_ELSEWHERE
						: Outcome.STATE_UNKNOWN, body);
			}
			// ===== 只重跑模型步：固定历史原样交给 driveResumed（不压缩、不重算窗口、不重建 system）=====
			// 交棒前与正常续跑同口径：按任务权威值同步 type/step/status，重算槽位并重写工作区段 ——
			// 挂起/失败期用户手动改的步骤不产生任何"刷新类工具结算"，这个入口也没有客户端 context 可补。
			final AiContext handoffContext = syncAuthorityForHandoff(runId,
					AiContext.fromMap(snapshot.getContext()), fixedHistory);
			final List<Message> handoffHistory = rewriteWorkspaceState(new ArrayList<>(fixedHistory),
					handoffContext, runId);
			// 状态转换必须走取消优先的原子守卫（不能用普通 save）：入口的 cancelStateOutcome 与本笔
			// 记账之间没有原子性 —— 取消恰在这段窗口落库时，普通 save 会把 CANCELLED 改回 RUNNING，
			// 随后还会交棒线程池把一条"已被用户停止"的轮重新驱动起来（前端也会重挂到它上面）。
			// saveStatusUnlessCancelled 把"这一笔有没有落库"变成可分支的事实：false ⇒ 取消已先到，
			// 返回取消语义并<b>不</b>驱动。
			snapshot.setStatus(RunSnapshot.RUNNING);
			snapshot.setContext(handoffContext.toMap());
			if (!handoffHistory.equals(fixedHistory)) {
				// 工作区段已按权威值重写：与上下文同一笔落库，避免"模型看到新的、快照还是旧的"
				snapshot.setMessageJson(this.codec.serializeAll(handoffHistory));
			}
			snapshot.setAssistantContent(null);
			snapshot.setInFlightToolCalls(new ArrayList<>());
			snapshot.setFinalText(null);
			snapshot.setError(null);
			snapshot.getResumedBy().add(this.store.instanceId());
			if (!this.store.saveStatusUnlessCancelled(snapshot)) {
				log.info("仅模型步重跑的状态转换被写门拒绝 runId={}（复核取消态/续跑权后返回 typed 结局）", runId);
				return modelStepStatusWriteRefused(runId, body);
			}
			// 交棒前再看一次取消事实：状态写成功 ≠ 取消没发生（取消可能恰好落在"写 RUNNING"之后）。
			// 取消已成立 ⇒ 返回取消语义、绝不交棒驱动（窗口更窄，但不声称是分布式事务；
			// 交棒之后的取消由驱动侧逐条 cancelStateOutcome 与 RunStore 的状态守卫兜住）。
			ResumeResult cancelBeforeHandoff = cancelStateOutcome(runId, body);
			if (cancelBeforeHandoff != null) {
				return cancelBeforeHandoff;
			}

			body.put("retried", true);
			body.put("resumed", true);
			body.put("reason", "MODEL_STEP_RETRY_ACCEPTED");
			body.put("retryOfStatus", RunSnapshot.FAILED);
			body.put("reusedToolLedger", true);
			body.put("appendedUserMessages", 0);
			body.put("promptMessageCount", fixedHistory.size());
			body.put("promptRoles",
					fixedHistory.stream().map(message -> message.getMessageType().name()).toList());
			body.put("resumedBy", this.store.instanceId());

			SseChatEmitter out = this.registry == null ? null : this.registry.of(runId);
			String sessionForTask = sessionId;
			try {
				this.suspendExecutor.execute(() -> withRunIdentity(snapshot, () -> {
					if (replyLocale == null) {
						LocaleContextHolder.resetLocaleContext();
					}
					else {
						LocaleContextHolder.setLocale(replyLocale);
					}
					try {
						if (snapshot.getProviderId() == null) {
							this.chatService.driveResumed(runId, sessionForTask, handoffContext, handoffHistory, out);
						}
						else {
							this.chatService.driveResumed(runId, sessionForTask, handoffContext, handoffHistory,
									snapshot.getProviderId(), snapshot.getModelId(), out);
						}
					}
					catch (Exception ex) {
						log.error("仅模型步重跑未捕获异常 runId={}：{}", runId, String.valueOf(ex.getMessage()), ex);
						// N2：失败终态与失败终帧由同一个写口在监视器内完成 —— 取消已先到时两者都不发。
						// 本批：发 error 之前先问归档有没有终帧（另一驱动方/取消收尾已落终帧时不得再补一条）。
						markDriveFailure(snapshot, ex, () -> {
							if (out != null) {
								emitFailureErrorUnlessArchived(out, ErrorCodes.AI_RESUME_FAILED, resumeFailedMessage());
							}
						});
					}
					finally {
						LocaleContextHolder.resetLocaleContext();
						this.resuming.remove(resumeKey);
						if (this.registry != null) {
							this.registry.close(runId);
						}
						if (this.sessionGate != null && gateHeld.get()) {
							this.sessionGate.release(sessionForTask, runId, gateToken);
							gateHeld.set(false);
						}
					}
				}));
				handedOff = true;
			}
			catch (RejectedExecutionException ex) {
				body.put("retried", false);
				body.put("resumed", false);
				body.put("reason", "SUSPEND_POOL_SATURATED");
				snapshot.setStatus(RunSnapshot.SUSPENDED);
				// 同一取消守卫：池饱和落 SUSPENDED 也不得把并发取消翻案（false ⇒ 复核后给 typed 结局，
				// 不把"已取消"报成"池满可重试"——那会让用户反复点一个不该再跑的入口）。
				if (!this.store.saveStatusUnlessCancelled(snapshot)) {
					return modelStepStatusWriteRefused(runId, body);
				}
				return new ResumeResult(Outcome.POOL_SATURATED, body);
			}
			return new ResumeResult(Outcome.ACCEPTED, body);
		}
		finally {
			if (!handedOff) {
				this.resuming.remove(resumeKey);
			}
			if (!handedOff && this.sessionGate != null && gateHeld.getAndSet(false) && gateSessionId != null) {
				this.sessionGate.release(gateSessionId, runId, gateToken);
			}
			AiTrace.clear();
			if (identityScope != null) {
				identityScope.close();
			}
		}
	}

	/**
	 * 仅模型步重跑的<b>状态写被拒</b>后的 typed 结局：区分"取消已先到"与"续跑权已丢/不可确认"。
	 *
	 * <p>为什么必须再读一次：{@link RunStore#saveStatusUnlessCancelled} 的 {@code false} 同时覆盖
	 * 取消守卫与只读写门（丢租 / 设施不可用 / fence 已被接管）—— 把后者也报成"已取消"会误导用户
	 * 以为是自己停止的轮。取消 ⇒ {@code ALREADY_CANCELLED}（200）；否则按续跑权/状态未知处理
	 * （409/503），两者都不驱动。
	 */
	private ResumeResult modelStepStatusWriteRefused(String runId, Map<String, Object> body) {
		body.put("retried", false);
		body.put("resumed", false);
		RunSnapshot latest;
		try {
			latest = this.store.get(runId);
		}
		catch (RuntimeException ex) {
			log.warn("仅模型步重跑：状态写被拒后复核快照失败 runId={}（按状态未知收尾，不驱动）：{}", runId,
					ex.getMessage());
			body.put("reason", "STATE_UNKNOWN");
			body.put("code", ErrorCodes.AI_REDIS_UNAVAILABLE);
			return new ResumeResult(Outcome.STATE_UNKNOWN, body);
		}
		if (latest != null && (RunSnapshot.CANCELLED.equals(latest.getStatus()) || latest.isCancelled())) {
			body.put("reason", "ALREADY_CANCELLED");
			return new ResumeResult(Outcome.ALREADY_CANCELLED, body);
		}
		body.put("reason", "LEASE_HELD_ELSEWHERE");
		return new ResumeResult(Outcome.LEASE_HELD_ELSEWHERE, body);
	}

	/**
	 * 续跑的<b>状态写被拒</b>后的 typed 结局（N4）：区分"取消已先到"与"续跑权已丢/不可确认"。
	 *
	 * <p>为什么必须再读一次：{@link RunStore#saveStatusUnlessCancelled} 的 {@code false} 同时覆盖取消守卫
	 * 与只读写门（丢租 / 设施不可用 / fence 已被接管）。取消 ⇒ {@code cancelStateOutcome} 的既有取消
	 * 收尾（{@code ALREADY_CANCELLED} / {@code CANCEL_STATE_UNKNOWN}）；否则按状态未知收口，
	 * <b>两者都不驱动</b>。
	 */
	private ResumeResult resumeStatusWriteRefused(String runId, Map<String, Object> body) {
		ResumeResult cancelled = cancelStateOutcome(runId, body);
		if (cancelled != null) {
			return cancelled;
		}
		body.put("resumed", false);
		body.put("reason", "STATE_UNKNOWN");
		body.put("code", ErrorCodes.AI_REDIS_UNAVAILABLE);
		return new ResumeResult(Outcome.STATE_UNKNOWN, body);
	}

	/** 前置失败的 typed 结局（对外 409 + 稳定码 + 具体是哪一条没满足）。 */
	private ResumeResult retryPreconditionDenied(Map<String, Object> body, ModelStepRetryCheck check) {
		body.put("resumed", false);
		body.put("retried", false);
		body.put("reason", "RETRY_PRECONDITION_FAILED");
		// 码用既有登记码（ILLEGAL_STATE = "当前状态不允许该操作"）：不新增码表项，
		// 前端镜像与 messages_* 的完备性约束因此不受影响；精确原因走 failedCheck。
		body.put("code", ErrorCodes.ILLEGAL_STATE);
		body.put("failedCheck", check.failedCheck());
		body.put("message", retryDeniedMessage());
		log.info("仅模型步重跑被前置条件拒绝 runId={} failedCheck={}（{}）", body.get("runId"), check.failedCheck(),
				check.reason());
		return new ResumeResult(Outcome.RETRY_PRECONDITION_FAILED, body);
	}

	/** 用户面短句（zh 原文 / en 译文各自一份，与 {@code PROVIDER_GONE_MESSAGE} 同形，不入 messages 键表）。 */
	private static String retryDeniedMessage() {
		return ReplyLanguage.current().english() ? RETRY_DENIED_MESSAGE_EN : RETRY_DENIED_MESSAGE;
	}

	/**
	 * 仅模型步重跑的<b>纯判定</b>（七条件里可在无副作用前提下静态判定的五条）—— 供入口与
	 * {@link #describe(String)}（{@code canRetryModelStep}）共用，也便于单测直接覆盖。
	 *
	 * <p>三参重载 = 兼容旧调用口径：思考按"用户可见"（最保守）处理。
	 *
	 * @param snapshot 当前快照（非 null）
	 * @param pendings 待决条目（强一致读口的结果）
	 * @param history  快照历史（原样）
	 */
	public static ModelStepRetryCheck checkModelStepRetry(RunSnapshot snapshot, List<PendingToolCall> pendings,
			List<Message> history) {
		return checkModelStepRetry(snapshot, pendings, history, true);
	}

	/**
	 * 仅模型步重跑的纯判定（<b>思考展示口径显式化</b>）。
	 *
	 * @param reasoningVisible {@code true} = 这一轮的思考已经以 {@code reasoning} 帧给用户看过
	 *                         （半截思考因此不可被静默替换）；{@code false} = 隐藏思考从未上屏，
	 *                         它不构成"用户可见的半截产出"，不阻断重跑
	 */
	public static ModelStepRetryCheck checkModelStepRetry(RunSnapshot snapshot, List<PendingToolCall> pendings,
			List<Message> history, boolean reasoningVisible) {
		if (snapshot == null) {
			return new ModelStepRetryCheck(false, "NOT_FAILED", "轮次不存在");
		}
		String status = snapshot.getStatus();
		if (RunSnapshot.CANCELLED.equals(status) || snapshot.isCancelled()) {
			return new ModelStepRetryCheck(false, "CANCELLED", "该轮已取消：取消优先，不重跑");
		}
		if (!RunSnapshot.FAILED.equals(status)) {
			return new ModelStepRetryCheck(false, "NOT_FAILED", "只有已失败的轮才允许仅模型步重跑（当前 " + status + "）");
		}
		// ③ 待决工具全结算
		List<PendingToolCall> unsettled = pendings == null ? List.of()
				: pendings.stream()
					.filter(pending -> PendingToolCall.PENDING.equals(pending.getStatus()))
					.filter(pending -> !PendingToolCall.KIND_BACKEND.equals(pending.getKind()))
					.toList();
		if (!unsettled.isEmpty()) {
			return new ModelStepRetryCheck(false, "PENDING_TOOLS",
					"仍有未结算的待决工具 " + unsettled.size() + " 条");
		}
		List<Message> messages = history == null ? List.of() : history;
		// ⑤ 无半截正文 / 半截可见思考
		if (StringUtils.hasText(snapshot.getAssistantContent())) {
			return new ModelStepRetryCheck(false, "PARTIAL_CONTENT", "本轮留有半截正文（不可自动替换）");
		}
		if (StringUtils.hasText(snapshot.getFinalText())) {
			return new ModelStepRetryCheck(false, "PARTIAL_CONTENT", "本轮留有最终正文（已被写成完整答案）");
		}
		// 思考只在"上过屏"时才算半截产出：展示关闭的隐藏思考用户从未看到，重跑不会出现两段思考。
		Map<String, Object> assistantMetadata = snapshot.getAssistantMetadata();
		if (reasoningVisible && assistantMetadata != null && StringUtils.hasText(
				assistantMetadata.get(ResilientChatService.REASONING_METADATA_KEY) == null ? null
						: String.valueOf(assistantMetadata.get(ResilientChatService.REASONING_METADATA_KEY)))) {
			return new ModelStepRetryCheck(false, "PARTIAL_CONTENT", "本轮留有半截思考");
		}
		for (Message message : messages) {
			if (message instanceof AssistantMessage assistant && assistant.getMetadata() != null
					&& Boolean.TRUE.equals(assistant.getMetadata().get(ResilientChatService.STOPPED_KEY))) {
				return new ModelStepRetryCheck(false, "PARTIAL_CONTENT", "历史里有被停止的半截 assistant");
			}
		}
		// ④ 工具入出参配对完整
		if (!toolPairsComplete(messages)) {
			return new ModelStepRetryCheck(false, "TOOL_PAIRING_INCOMPLETE",
					"工具调用与工具响应不配对（悬空 tool_calls 会被上游拒绝）");
		}
		return new ModelStepRetryCheck(true, null, null);
	}

	/**
	 * 这一轮（以及此刻的配置）思考是否曾以 {@code reasoning} 帧上屏：
	 * {@code app.ai.reasoning.display-enabled} 是全局开关，关闭（默认）时思考只进记忆、不上屏，
	 * 因此快照里的 {@code reasoningContent} 不构成"用户可见的半截产出"。
	 *
	 * <p>口径边界：判定用<b>当前</b>配置。开关在一轮生成与重跑之间被翻转不是支持场景
	 * （翻转后重跑会按新口径重问模型）；本判据只回答"用户端现在有没有看到过这段思考"。
	 */
	private boolean reasoningVisible() {
		return this.properties != null && this.properties.getReasoning() != null
				&& this.properties.getReasoning().isDisplayEnabled();
	}

	/** 历史里的 {@code tool_calls} 与 {@code tool} 响应 id 集合必须相等（配对完整）。 */
	static boolean toolPairsComplete(List<Message> history) {
		Set<String> calls = new LinkedHashSet<>();
		Set<String> responses = new LinkedHashSet<>();
		for (Message message : history == null ? List.<Message>of() : history) {
			if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
					if (call.id() != null) {
						calls.add(call.id());
					}
				}
			}
			if (message instanceof ToolResponseMessage toolResponse && toolResponse.getResponses() != null) {
				for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
					if (response.id() != null) {
						responses.add(response.id());
					}
				}
			}
		}
		return calls.equals(responses);
	}

	/**
	 * ⑥ 同模型：快照的 provider/model 此刻仍可解析成<b>同一个</b>模型。
	 *
	 * <p>与续跑的 PROVIDER_GONE 判据<b>同一个解析口</b>（{@link #resolveForResume}：命中即用、否则重载一次、
	 * 再看行级状态），因此"续跑会 409 的情形"与"重跑会 409 的情形"不会分叉。
	 *
	 * @return {@code null} = 同一模型可用；{@code "GONE"} = 端点/模型已不可用（409 {@code PROVIDER_GONE}）；
	 *         {@code "UNKNOWN"} = 配置此刻不可确认（503，不判死）；其它字符串 = 解析到了不同模型
	 *         （前置失败 {@code MODEL_CHANGED}）
	 */
	private String sameModelProblem(String runId, RunSnapshot snapshot) {
		String modelId = snapshot.getModelId();
		if (!StringUtils.hasText(modelId)) {
			// 旧快照没有模型身份：无法证明"同模型"，按前置失败拒绝（宁可让用户新开一轮，也不静默换模型）
			return "该轮快照没有模型身份，无法证明按同一模型重跑";
		}
		if (this.aiConfigService == null) {
			return null;
		}
		ResolvedIdentity identity = resolveForResume(runId,
				() -> this.aiConfigService.resolveChatTarget(snapshot.getProviderId(), modelId),
				() -> this.aiConfigService.bindingState(snapshot.getProviderId(), modelId));
		if (identity.unknown()) {
			return "UNKNOWN";
		}
		if (identity.target() == null) {
			return "GONE";
		}
		String resolvedModel = identity.target().modelId();
		if (resolvedModel == null || !modelId.equalsIgnoreCase(resolvedModel)) {
			return "该模型当前解析到 " + resolvedModel + "（与快照 " + modelId + " 不同）";
		}
		return null;
	}

	/**
	 * 仅模型步重跑的前置判定结果（七条件里可静态判定的那几条）。
	 *
	 * @param allowed    是否允许重跑
	 * @param failedCheck 失败的是哪一条（{@code NOT_FAILED} / {@code CANCELLED} / {@code PENDING_TOOLS} /
	 *                    {@code PARTIAL_CONTENT} / {@code TOOL_PAIRING_INCOMPLETE} / {@code MODEL_CHANGED}）；
	 *                    通过时 {@code null}
	 * @param reason     人类可读的拒绝原因（日志用；用户面走 {@code messages_*} 的完整码文案）
	 */
	public record ModelStepRetryCheck(boolean allowed, String failedCheck, String reason) {
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

	private ResumeResult resumeInternal(String runId, String resumeKey, boolean forceTakeover,
			LeaseService.Lease lease, boolean freshLease, String resumeReason, Locale replyLocale) {
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
		if (this.resuming.putIfAbsent(resumeKey, Boolean.TRUE) != null) {
			return new ResumeResult(Outcome.IN_PROGRESS, Map.of("runId", runId, "resumed", false,
					"reason", "RESUME_IN_PROGRESS", "instanceId", this.store.instanceId()));
		}
		boolean handedOff = false;
		// 恢复型入口的身份作用域：快照读出来之后建立、本方法返回前关闭（同一线程）。
		// 启动/运行期扫描线程没有请求身份，续跑内部的一切数据操作都必须落在<b>这一轮的身份</b>下。
		RunIdentityScope.Scope identityScope = null;
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
			// 身份只从<b>这一轮的记录</b>恢复（不读当前线程、不扫描租户列表、不回落到默认租户）。
			identityScope = RunIdentityScope.open(snapshot.getTenantId(), snapshot.getUserId());
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
				// ===== 抢租后的复核（2026-10-29；返修 R1 起与 fresh 绑定，R2 起同实例收口）=====
				// 常态下"驱动还活着"已在 {@link #takeoverPreCheck} 里拦下（那时还没抢租）。
				// 走到这里仍要复核，是关掉"初检之后、抢租之前驱动又活过来"的窗口。
				long activeAtMs = activeAtMs(snapshot);
				long zombieAgeMs = this.properties.getResume().getZombieAge().toMillis();
				long activeAgeMs = System.currentTimeMillis() - activeAtMs;
				boolean driverAlive = this.chatService != null && this.chatService.isDriving(runId);
				boolean activityAlive = activeAgeMs <= zombieAgeMs;
				if (driverAlive && lease != null) {
					// ===== 同实例收口（返修 R2）=====
					// 本进程里两个驱动方共享同一个 {@code LeaseService} 的租约视图（按 key+owner 记账）：
					// 新起的接管驱动一抢租，视图就指向新 epoch ⇒ <b>旧驱动后续的写照样放行</b>，
					// "接管"于是等于双驱动。也不能"拒绝 + 还租"（那是 R1 前的死路：fence 已推进 +
					// 租约被删 + 无人驱动）。故这里<b>不新起驱动</b>：把这一任租约重挂给仍在跑的旧驱动
					// （后处理 stop + markDriving，存活判据 = 本进程仍有该轮驱动登记 && 快照非终态），
					// 返回 ALREADY_RUNNING + leaseRearmed=true，且<b>不得还租</b>。
					// 跨实例（isDriving=false）不受影响：仍按"fresh 安全接管"（fence 能真正隔开两个进程）。
					body.put("resumed", false);
					body.put("reason", "DRIVER_ALIVE_IN_PROCESS");
					body.put("driverAlive", true);
					body.put(LEASE_REARMED_KEY, true);
					log.warn("僵尸接管改判『重挂租约』runId={} sessionId={}：本进程仍登记着这一轮的驱动方"
							+ "（同实例共享租约视图 ⇒ 新起驱动会双驱动）—— 不新起驱动，把租约重挂给旧驱动"
							+ "（fresh={}）", runId, sessionId, freshLease);
					return new ResumeResult(Outcome.ALREADY_RUNNING, body);
				}
				if (driverAlive || activityAlive) {
					if (!freshLease) {
						if (driverAlive) {
							body.put("resumed", false);
							body.put("reason", "DRIVER_ALIVE_IN_PROCESS");
							body.put("driverAlive", true);
							log.warn("僵尸接管复核拒绝 runId={} sessionId={}：本进程仍登记着这一轮的驱动方"
									+ "（无租约可重挂：lease=null；拒绝不改任何状态）—— 不接管", runId, sessionId);
							return new ResumeResult(Outcome.ALREADY_RUNNING, body);
						}
						body.put("resumed", false);
						body.put("reason", "ACTIVITY_ADVANCED");
						body.put("activeAtMs", activeAtMs);
						body.put("activeAgeMs", activeAgeMs);
						log.warn("僵尸接管复核拒绝 runId={} sessionId={}：业务活动在接管判定之后又前进"
								+ "（{}ms ≤ 阈值 {}ms：原驱动还活着）—— 不接管", runId, sessionId, activeAgeMs,
								zombieAgeMs);
						return new ResumeResult(Outcome.ALREADY_RUNNING, body);
					}
					log.warn("僵尸接管复核：仍见活驱动迹象（driverAlive={} activeAgeMs={}），但本次是 fresh 抢租"
							+ "（已推进 fence）且本进程没有该轮驱动登记（跨实例）—— 继续接管 runId={} sessionId={}"
							+ "（旧驱动的写将被 fence 挡住）", driverAlive, activeAgeMs, runId, sessionId);
				}
				// 僵尸接管（N3/R3）：上一进程死在模型调用中间，状态永远停在 RUNNING。
				// 判据由调用方（启动扫描/运维扫描）给出：本实例刚启动 + 业务活动已超龄；
				// 活跃时刻 = max(快照 updatedAtMs, 最后一条非心跳业务帧 atMs)，因此"还在跑长工具/
				// 流式吐字"的活轮不会被接管，心跳也不会把卡死的轮养年轻（R1-09）。
				log.warn("僵尸轮接管 runId={} sessionId={} 活跃时刻距今 {}ms（快照 {}ms / 末条业务帧 {}ms，阈值 {}）",
						runId, sessionId, System.currentTimeMillis() - activeAtMs,
						System.currentTimeMillis() - snapshot.getUpdatedAtMs(),
						System.currentTimeMillis() - this.store.lastActivityAtMs(runId),
						this.properties.getResume().getZombieAge());
				body.put("zombieTakeover", true);
				body.put("activeAtMs", activeAtMs);
				body.put("activeAgeMs", activeAgeMs);
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
			// 本轮的运行上下文有两份，用途不同、不得混用：
			//  - context = 请求边界冻结的那一份：本批<b>结算判据</b>（scope 复查/预检都按它，批内不扩权）；
			//  - nextHopContext = 整批结算后按真实结果刷新的那一份：只用于<b>交棒给模型</b>
			//    （下一跳的披露面 + 工作区状态）。无在飞工具时两者相同。
			AiContext nextHopContext = context;
			// 本批<b>已结算</b>条目（结算后回读的真值）：整批结算完再统一折算上下文，
			// 不在循环里逐条刷新（逐条刷新会让同批后面的条目拿到"刚刚才解锁"的披露面 = 批内扩权）。
			// 声明在分支之外：无在飞工具时为空列表，"宿主定义选择表单"的判据（本批是否刚导航成功）
			// 因此对两个分支是同一份输入。
			List<PendingToolCall> settledInBatch = new ArrayList<>(snapshot.getInFlightToolCalls().size());

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
					settledInBatch.add(source);
					if (this.toolCallingManager.haltsBatch(source)) {
						haltedBy = source;
						log.warn("续跑批策略首错即停 runId={} 上游工具={} outcome={} code={}，剩余条目将置 SKIPPED",
								runId, source.getName(), source.effectiveOutcome().wire(), source.effectiveCode());
					}
				}
				// ===== 批间上下文刷新（续跑侧的唯一刷新点）=====
				// 生产 park=true 时首轮的刷新点（SpToolCallingManager 执行循环尾部）根本走不到：
				// 工具在挂起处就结束了这一轮。续跑在<b>整批结算之后</b>补上同一次折算，然后<b>重读快照</b>
				// 拿下一跳上下文 —— 逐条刷新会让同批后面的条目提前看到刚解锁的工具（批内扩权）。
				nextHopContext = refreshRunContextAfterBatch(runId, snapshot, settledInBatch);
				// 挂起期用户可能已在向导里手动改了步骤（本批没有刷新类工具结算，上面的刷新点什么都不做）：
				// 交棒前按任务权威值同步 type/step/status，并按最新页面事实重算槽位。
				// 批内结算已全部完成 —— 这里只影响下一跳（批内 context 仍冻结，不扩权）。
				nextHopContext = syncAuthorityForHandoff(runId, nextHopContext, history);
				// 刷新后的权威工作区状态跟着交棒：历史里那条 SYSTEM 的工作区段是"模型以为的现场"，
				// 留着旧页面/旧步骤等于让模型按过期地图继续走（只换段内文本，不动消息序列）。
				history = rewriteWorkspaceState(history, nextHopContext, runId);
				ToolResponseMessage toolMessage = ToolResponseMessage.builder().responses(responses).build();
				history.add(toolMessage);
				// 上下文专项批①：本次重建出来的工具响应是"上次真值之后"的真增量（续跑侧的 nextEst 一项）
				rebuiltToolTokens = responses.stream()
					.mapToInt(response -> ContextEstimator.estimate(response.responseData()))
					.sum();
				snapshot.setMessageJson(this.codec.serializeAll(history));
				snapshot.setContext(nextHopContext.toMap());
				snapshot.setInFlightToolCalls(new ArrayList<>());
				snapshot.setAssistantContent(null);
				snapshot.setStatus(RunSnapshot.RUNNING);
				snapshot.getResumedBy().add(this.store.instanceId());
				// N4：状态转换走取消优先的原子守卫（不是普通 save）。本笔被拒 ⇒ 取消已先到/续跑权已丢，
				// 复核后给 typed 结局，**不得**继续压缩、认领或交棒驱动这一轮。
				if (!this.store.saveStatusUnlessCancelled(snapshot)) {
					log.info("续跑重建后的 RUNNING 状态转换被写门拒绝 runId={}（复核取消态/续跑权后收口，不驱动）",
							runId);
					return resumeStatusWriteRefused(runId, body);
				}
			}
			else {
				// 无在飞工具（重跑/手动续跑）：同样在交棒前对齐任务权威步骤与槽位 ——
				// 挂起期的手动改步骤不会产生任何"刷新类工具结算"，这里没有其它刷新点。
				nextHopContext = syncAuthorityForHandoff(runId, nextHopContext, history);
				List<Message> beforeRewrite = history;
				history = rewriteWorkspaceState(history, nextHopContext, runId);
				snapshot.setContext(nextHopContext.toMap());
				if (history != beforeRewrite) {
					snapshot.setMessageJson(this.codec.serializeAll(history));
				}
				snapshot.getResumedBy().add(this.store.instanceId());
				snapshot.setStatus(RunSnapshot.RUNNING);
				if (!this.store.saveStatusUnlessCancelled(snapshot)) {
					log.info("续跑 RUNNING 状态转换被写门拒绝 runId={}（复核取消态/续跑权后收口，不驱动）", runId);
					return resumeStatusWriteRefused(runId, body);
				}
			}

			// ===== 宿主确定性定义选择表单（新建任务 → 导航到选择步骤 → 配置未知）=====
			// 位置刻意在"整批结算 + 权威同步 + 工作区段重写"之后、记忆窗口/压缩与交棒之前：
			//   · 判据读的是**下一跳**那份上下文（批间刷新后的页面/任务/步骤 + 重算后的槽位），
			//     不是请求边界那份，于是"刚导航到新任务"这件事在判据里成立；
			//   · 挂起用既有面（saveSuspended → suspended 帧 → 请求帧 → 收流），与模型自己下发的
			//     generative_form 完全同一条路；用户的提交/取消走既有回灌与既有续跑；
			//   · 判据只看"本批是否刚成功导航到本轮新建的那个任务"，因此表单回灌后的下一次续跑
			//     （settledBatch 里没有那次 navigate_to）不会重复问同一件事。
			if (this.definitionSelectionForm != null
					&& this.definitionSelectionForm.parkIfDue(runId, nextHopContext, settledInBatch, history)) {
				body.put("resumed", false);
				body.put("reason", "DEFINITION_FORM_PARKED");
				body.put("taskId", nextHopContext.getTaskId());
				log.info("续跑改为等人填配置项表单（本拍不驱动模型）runId={} taskId={}", runId,
						nextHopContext.getTaskId());
				return new ResumeResult(Outcome.ACCEPTED, body);
			}

			// ===== 宿主确定性筛选条件文本框（进入 QUERY_COND + 条件未给 + 主动任务链）=====
			// 与上面同一处交棒点、同一份下一跳上下文：判据是权威步骤（只有 QUERY_COND 允许）与
			// 重算后的槽位（条件已给 ⇒ 不弹，模型直接 set_condition）。EXPORT 及之后由步骤门挡住，
			// 因此"EXPORT 后不得再弹"不是靠调用顺序，而是判据本身。
			if (this.conditionsForm != null
					&& this.conditionsForm.parkIfDue(runId, nextHopContext, settledInBatch, history)) {
				body.put("resumed", false);
				body.put("reason", "CONDITIONS_FORM_PARKED");
				body.put("taskId", nextHopContext.getTaskId());
				log.info("续跑改为等人填筛选条件（本拍不驱动模型）runId={} taskId={}", runId,
						nextHopContext.getTaskId());
				return new ResumeResult(Outcome.ACCEPTED, body);
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
				// ===== AR-06 / Task 5 Step 3 + 2026-10-29 压缩阶段信号批 =====
				// 与首轮同构：初始相位是 streaming（普通续跑不出现任何压缩/准备阶段条）；压缩调用自身
				// 一帧不发；只有真实 L2 模型摘要开始时才由 onSummaryStart 切到 compacting，摘要结束立刻
				// 切回 streaming。压缩期的节拍只由 TurnKeepalive 的独立定时器产生（不依赖上游事件）。
				// 保活句柄在压缩段结束处（finally）交出：交棒之后由驱动侧自己排一枚（driveResumed）。
				TurnKeepalive keepalive = this.chatService == null || this.registry == null ? null
						: this.chatService.startKeepalive(runId, this.registry.of(runId),
								TurnKeepalive.PHASE_STREAMING, true);
				try {
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
					Runnable onSummaryStart = keepalive == null ? null
							: () -> keepalive.phase(TurnKeepalive.PHASE_COMPACTING);
					compaction = this.contextCompactor.compact(new ContextCompactor.Request(sessionId,
							runId, snapshot.getModelId(), snapshot.getProviderId(), history, this.codec.serializeAll(history),
							state.usedTokens(), state.completionTokens(), 0, rebuiltToolTokens, systemTokens,
							// 披露面的额度必须按<b>交棒那一份</b>上下文算：刷新后工具集变了，用旧的会算错
							this.chatService.disclosedToolTokens(nextHopContext), protectedIds, compactionRuntime),
							// R1-09 M-1：L2 是 N 次串行摘要（每次最长 60s）。这个回调只刷新<b>业务活动锚点</b>
							// （避免长压缩被别的实例判成僵尸并接管），它<b>不发帧</b> —— 压缩期的可见性由上面
							// 那枚独立定时器的 stage 心跳承担（"压缩不伪称持续发帧"）。
							() -> this.store.touchBusinessActivity(runId, System.currentTimeMillis()),
							onSummaryStart);
					// 摘要结束（或压根没压）：立即恢复工作中，前端阶段条随即退场。
					if (keepalive != null) {
						keepalive.phase(TurnKeepalive.PHASE_STREAMING);
					}
				}
				finally {
					if (keepalive != null) {
						keepalive.close();
					}
				}
				if (compaction.changed()) {
					history = compaction.messages();
					snapshot.setMessageJson(this.codec.serializeAll(history));
					// N4：压缩是长窗口（L2 = N 次串行摘要），结束时取消可能已经先到 —— 这一笔同样
					// 不得把 CANCELLED 翻回 RUNNING；被拒 ⇒ 不写 context 交接位、不交棒驱动。
					if (!this.store.saveStatusUnlessCancelled(snapshot)) {
						log.info("续跑压缩后的快照写被写门拒绝 runId={}（复核取消态/续跑权后收口，不驱动）", runId);
						return resumeStatusWriteRefused(runId, body);
					}
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
			// N4：交棒前最后一次复查取消事实 —— 压缩/窗口收口期间用户按下停止，绝不能再把这一轮
			// 交出去驱动（取消已成立时由取消收尾负责终态与终帧；UNKNOWN 走既有 typed 结局）。
			ResumeResult cancelBeforeHandoff = cancelStateOutcome(runId, body);
			if (cancelBeforeHandoff != null) {
				return cancelBeforeHandoff;
			}
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
			// 交棒上下文 = 刷新后的那一份（上面的 lambda 捕获的是本 final 副本）
			final AiContext handoffContext = nextHopContext;
			try {
				this.suspendExecutor.execute(() -> withRunIdentity(snapshot, () -> {
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
							this.chatService.driveResumed(runId, sessionId, handoffContext, resumeHistory, out);
						}
						else {
							this.chatService.driveResumed(runId, sessionId, handoffContext, resumeHistory,
									snapshot.getProviderId(), snapshot.getModelId(), out);
						}
					}
					catch (Exception ex) {
						// R-08：用户面只给白名单短句；原始异常（上游 body / 内部提示）只进日志。
						log.error("续跑未捕获异常 runId={}：{}", runId, String.valueOf(ex.getMessage()), ex);
						// 交棒后的失败必须落终态，不能只留下错误帧和无人推进的 RUNNING 快照。
						// N2：失败终态与失败终帧在同一个监视器内完成（取消已先到 ⇒ 两者都不发）。
						// 本批（返修后按<b>每次驱动</b>判）：只跳过"本次驱动已落的终帧"，上一次驱动的 error
						// 不得吞掉本次失败；跨实例并发的"恰好一条"不在本批保证内（见 emitFailureErrorUnlessArchived）。
						markDriveFailure(snapshot, ex,
								() -> emitFailureErrorUnlessArchived(out, ErrorCodes.AI_RESUME_FAILED,
										resumeFailedMessage()));
					}
					finally {
						LocaleContextHolder.resetLocaleContext();
						// 轮终态：完成订阅者 + 摘掉进程内互斥（ADR-5 的"轮终态主动释放"同族语义）。
						// 续跑权租约不在这里还：终态写进 RunStore.save 时 best-effort 释放（同一个
						// 唯一写口），此处再还一次只会在"写终态前"制造一个别人可接管的窗口。
						// 摘的是<b>本拍登记的那把键</b>（身份 + runId）：换一个身份的同名轮次不受影响，
						// 池化线程上现取身份会落到 legacy 桶、把互斥位永久占住。
						this.resuming.remove(resumeKey);
						this.registry.close(runId);
						// park 批：恢复执行取回的会话门到这里归还（二次 park 也一样 —— 任务结束即释放，
						// 下一次恢复重新 acquire；看门狗随 release 停掉，不会无限续期）。
						// I2：按本次 acquire 的 token 释放 —— 同 runId 的另一任持有者不受影响。
						if (this.sessionGate != null && gateHeld.get()) {
							this.sessionGate.release(sessionId, runId, gateToken);
							gateHeld.set(false);
						}
					}
				}));
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
				// N4（settle 同型）：池饱和落 SUSPENDED 也不得把并发取消翻案；被拒 ⇒ 复核后给 typed 结局，
				// 不把"已取消"报成"池满可重试"。
				if (!this.store.saveStatusUnlessCancelled(snapshot)) {
					return resumeStatusWriteRefused(runId, body);
				}
				return new ResumeResult(Outcome.POOL_SATURATED, body);
			}
			return new ResumeResult(Outcome.ACCEPTED, body);
		}
		finally {
			if (!handedOff) {
				this.resuming.remove(resumeKey);
			}
			// park 批：<b>只有没交棒</b>（池饱和/会话被占/异常）才由本线程归还会话门。
			// 交棒之后门归驱动任务所有（它在自己的 finally 按同一 token 释放）—— 这里若也释放，
			// 驱动期间门是空的，"同会话并发第二轮"就能进来（本行是 I2 批实测抓到的真缺陷）。
			if (!handedOff && this.sessionGate != null && gateHeld.getAndSet(false) && gateSessionId != null) {
				this.sessionGate.release(gateSessionId, runId, gateToken);
			}
			// 驱动线程（挂起池）在 driveResumed 入口自己绑锚点 —— 本线程到此结束，一律摘掉
			AiTrace.clear();
			// 身份作用域最后关闭：上面的租约/会话门清理也要在"这一轮的身份"下完成；
			// 无旧值时清空，池化线程不会把身份带给下一位调用者。
			if (identityScope != null) {
				identityScope.close();
			}
		}
	}

	/**
	 * <b>续跑侧的批间上下文刷新</b>（本类唯一的刷新点）。
	 *
	 * <h2>为什么必须在续跑侧补这一刀</h2>
	 * 首轮的刷新点在 {@code SpToolCallingManager} 执行循环的尾部。生产 {@code app.ai.park.enabled=true}
	 * 时 {@code create_task} / {@code navigate_to} / {@code confirm_step} 都在<b>那个点之前</b>就挂起
	 * （{@code RunParkedException} 直接结束这一轮），刷新一次都跑不到 —— 续跑随后拿请求边界的旧
	 * {@link AiContext} 构造下一跳，于是步骤工具（{@code select_definitions} / {@code set_condition} /
	 * {@code start_export}）永远不披露。
	 *
	 * <h2>三条口径</h2>
	 * <ol>
	 * <li><b>整批结算之后才刷新</b>：入参是本批<b>已结算</b>的条目（结算后回读的真值）。逐条刷新会让
	 * 同批后面的条目提前看到"刚刚才解锁"的披露面 = 批内扩权；</li>
	 * <li><b>只认真实成功</b>：判据（工具名 + {@code outcome=OK}）全在
	 * {@link RunContextRefresher#next}，本方法只负责"读快照 → 折 → 写回 → 重读"；</li>
	 * <li><b>失败不打断</b>：任何异常只 WARN 并保留旧上下文 —— 刷新是"让下一跳更准"的增强，
	 * 不是这一轮的判定面，绝不能因为一次读库失败让续跑失败。</li>
	 * </ol>
	 *
	 * <p>写回之后<b>重新读快照</b>：下一跳用的必须是外置真值（与 {@code RunSnapshot#getContext()} 同一份），
	 * 而不是本方法内存里那份推算结果 —— 写门拒写时两者会分叉，此处如实反映"存下去的"。
	 *
	 * <p><b>写回/重读失败的 fail-safe</b>：两步都单独捕获 {@code RuntimeException}（并且重读为空也算失败）：
	 * 只 WARN、把 {@code snapshot} 的内存上下文<b>显式回滚到 before</b>、返回 {@code before}。理由是
	 * 未经核实的写不得扩披露面（宁可下一跳不刷新），而 {@code setContext} 已经动过内存，不回滚就会
	 * 出现"内存是新值、Redis 是旧值"的分叉（后续状态写还会顺手把新值写进去）。
	 *
	 * @param settled 本批已结算条目（含已终态复用、被跳过、被拒绝的；非刷新工具由判据自动忽略）
	 * @return 下一跳应使用的上下文；无变化时返回旧值（同一份事实、不写库）
	 */
	private AiContext refreshRunContextAfterBatch(String runId, RunSnapshot snapshot, List<PendingToolCall> settled) {
		AiContext before = AiContext.fromMap(snapshot.getContext());
		if (settled == null || settled.isEmpty()) {
			return before;
		}
		RunContextRefresher refresher = this.contextRefresher;
		if (refresher == null) {
			// 可观测性（不回退、不猜根因）：本批确有"能推进上下文"的结果，但装配里没有刷新器
			if (settled.stream().anyMatch(entry -> RunContextRefresher.supports(entry.getName()))) {
				log.warn("续跑批无法刷新运行上下文：未注入 RunContextRefresher runId={}"
						+ "（下一跳沿用请求边界的旧上下文，步骤工具可能不披露）", runId);
			}
			return before;
		}
		AiContext current = before;
		for (PendingToolCall entry : settled) {
			if (!RunContextRefresher.supports(entry.getName())) {
				continue;
			}
			try {
				Optional<AiContext> next = refresher.next(current, entry);
				if (next != null && next.isPresent()) {
					current = next.get();
				}
			}
			catch (RuntimeException ex) {
				// 保留旧上下文继续折下一条：一条读库失败不代表整批结果不可用
				log.warn("续跑批后刷新运行上下文失败，保留旧上下文（不阻断续跑）runId={} tool={}：{}", runId,
						entry.getName(), ex.getMessage());
			}
		}
		if (current.equals(before)) {
			return before;
		}
		// ===== 写回 + 重读：任一步失败都只 WARN，并用<b>批首</b>上下文交棒 =====
		// fail-safe：<b>未经核实的写不得扩披露面</b>。刷新只是"让下一跳更准"的增强，若它能让续跑
		// 崩掉（Redis 抖一下整轮就死），或用一份没落成的上下文去披露新工具，都比"这一步没刷新"更糟。
		// snapshot 与落库是<b>同一份内存对象</b>：setContext 会先改内存，写失败后若不显式回滚，
		// 内存与持久态就各说一套（后续状态写还会把这份没落成的上下文顺手写进去）。
		Map<String, Object> beforeMap = before.toMap();
		try {
			snapshot.setContext(current.toMap());
			this.store.save(snapshot);
		}
		catch (RuntimeException ex) {
			snapshot.setContext(beforeMap);
			log.warn("续跑批刷新运行上下文写回失败，保留旧上下文（不阻断续跑）runId={}：{}", runId, ex.getMessage());
			return before;
		}
		RunSnapshot fresh;
		try {
			fresh = this.store.get(runId);
		}
		catch (RuntimeException ex) {
			// 写可能成了、也可能没有（例如写门静默拒写）：读不到真值就不认这笔刷新
			snapshot.setContext(beforeMap);
			log.warn("续跑批刷新运行上下文重读失败，按批首上下文交棒（不阻断续跑）runId={}：{}", runId,
					ex.getMessage());
			return before;
		}
		if (fresh == null) {
			snapshot.setContext(beforeMap);
			log.warn("续跑批刷新运行上下文重读为空，按批首上下文交棒（不阻断续跑）runId={}", runId);
			return before;
		}
		AiContext reread = AiContext.fromMap(fresh.getContext());
		// 以重读真值为准回写内存：写门静默拒写时它等于 before（不扩披露面），内存也随之与持久态一致。
		snapshot.setContext(reread.toMap());
		log.info("续跑批结算后已刷新运行上下文 runId={} page={} taskId={} taskType={} step={} status={}", runId,
				reread.getPage(), reread.getTaskId(), reread.getTaskType(), reread.getStep(), reread.getTaskStatus());
		return reread;
	}

	/**
	 * 续跑交棒前的<b>权威同步</b>（只作用于下一跳；批内结算用的 {@code context} 保持冻结）：
	 * <ol>
	 * <li>按 {@code taskId} 读 {@code TaskService} 的权威 {@code type / currentStep / status} ——
	 * 挂起期内用户可以在向导里手动改步骤，而这类改动不产生任何"刷新类工具结算"；</li>
	 * <li>用<b>最新</b>上下文重算 {@code extra[taskSlots]} —— 当前文本按权威保护决定：页面已有非空勾选、
	 * 或这条消息之后已有改选/清空动作时传空（权威值说话）；只有"页面还是初始默认空、且没有任何后续
	 * 选择动作"时才用历史里<b>最近一条真实用户原文</b>（跳过合成消息）补齐初始槽位。</li>
	 * </ol>
	 *
	 * <p>任一步失败只 WARN 并保留旧值：这是"让下一跳更准"的增强，不是这一轮的判定面
	 * （与 {@link #refreshRunContextAfterBatch} 同一 fail-safe 口径）。
	 */
	private AiContext syncAuthorityForHandoff(String runId, AiContext context, List<Message> history) {
		return recomputeSlotsBeforeHandoff(runId, syncTaskBeforeHandoff(runId, context), history);
	}

	/** 任务权威值的交棒前同步（读失败 = 保留旧值 + WARN，不阻断续跑）。 */
	private AiContext syncTaskBeforeHandoff(String runId, AiContext context) {
		if (this.contextRefresher == null) {
			return context;
		}
		try {
			return this.contextRefresher.syncTask(context).orElse(context);
		}
		catch (RuntimeException ex) {
			log.warn("续跑交棒前任务权威同步失败，保留旧上下文（不阻断续跑）runId={}：{}", runId, ex.getMessage());
			return context;
		}
	}

	/**
	 * 槽位重算（未注入解析器 = 不重算；异常 = 保留旧槽位 + WARN）。
	 *
	 * <p>当前文本按<b>权威保护</b>决定（见 {@link #currentTextForSlotRecompute}）：只有"页面还是初始
	 * 默认空、且这条消息之后没有任何选择动作"时，才用用户原话补齐初始槽位；页面给出了非空勾选、或
	 * 已有选择/清空动作时，当前文本传空 —— 原话不得覆盖用户随后的改选与清空。
	 */
	private AiContext recomputeSlotsBeforeHandoff(String runId, AiContext context, List<Message> history) {
		if (this.taskSlotResolver == null) {
			return context;
		}
		try {
			String currentText = currentTextForSlotRecompute(context, history);
			AiContext enriched = this.taskSlotResolver.enrich(context, currentText, history);
			return enriched == null ? context : enriched;
		}
		catch (RuntimeException ex) {
			log.warn("续跑交棒前任务槽位重算失败，保留旧槽位（不阻断续跑）runId={}：{}", runId, ex.getMessage());
			return context;
		}
	}

	/**
	 * 槽位重算用的"当前文本"——<b>权威保护</b>下的最小判据。
	 *
	 * <h2>为什么不能永远用用户原话（返修）</h2>
	 * 解析器把"当前文本明确给出的值"当作对前值的<b>替换</b>。若交棒时无条件把最近一条用户原文交进去，
	 * 用户随后在页面上<b>改选</b>或<b>清空</b>的结果会被那句旧话覆盖（"导出CNY货币字典"重新战胜
	 * 用户刚点的 FX_RATE 或刚清空的勾选）。所以先问"页面/动作时间线里有没有更新的事实"。
	 *
	 * <h2>{@code recentActions} 的实际形态</h2>
	 * 前端 {@code workspace.buildContext()} 注入的 {@code extra.recentActions} 是
	 * {@code List<String>}，每条 = {@code "${record.summary}（来源：${record.source}）"}；每发一次消息由
	 * {@code clearRecentActions()} 清空 ⇒ 列表里只有<b>这条用户消息之后</b>发生的动作。选择动作的唯一
	 * 写入口 {@code workspace.setSelectedDefs} 的摘要固定以「已选择配置项：」开头（选中与清空都走它，
	 * 清空时是「已选择配置项：（空）」）。因此"有没有后续选择动作"的最小可靠判据就是：时间线里存在
	 * 以该前缀开头的条目 —— 不需要解析更多语义，也不会把"进入/绑定任务/切步骤"误判成改选。
	 *
	 * <h2>判据（按顺序，第一条命中即生效）</h2>
	 * <ol>
	 * <li>时间线里已有选择动作（改选或清空）⇒ 传空：让权威值（页面勾选/任务条目）说话，原话不得覆盖；</li>
	 * <li>页面给出了<b>非空</b>勾选 ⇒ 传空：页面为准（改选到别的配置后，原话里的旧配置不得回来）；</li>
	 * <li>其余（页面是初始默认空、页面尚未同步）⇒ 用历史里最近一条真实用户原文（跳过合成消息）
	 * 补齐初始槽位 —— 这正是"导出CNY货币字典"在新建任务导航后仍被认作已知的场景。</li>
	 * </ol>
	 *
	 * <p>条件不受影响：{@code code=CNY} 在 case 3 由当前文本解析得到；在 case 1/2 由历史条件解析
	 * （{@code TaskSlotResolver} 的 {@code ABSENT} 分支回落到历史条件）保留，并仍被已选配置项过滤。
	 */
	private String currentTextForSlotRecompute(AiContext context, List<Message> history) {
		if (selectionActionRecorded(context)) {
			return "";
		}
		List<String> pageCodes = this.taskSlotResolver == null ? null
				: this.taskSlotResolver.pageSelectedDefCodes(context);
		if (pageCodes != null && !pageCodes.isEmpty()) {
			return "";
		}
		String userText = com.example.configmgr.ai.slot.TaskIntent.lastRealUserText(history);
		return userText == null ? "" : userText;
	}

	/**
	 * 本轮用户消息之后是否发生过<b>选择动作</b>（改选或清空）。
	 *
	 * <p>判据与形态说明见 {@link #currentTextForSlotRecompute}：{@code recentActions} 是
	 * {@code List<String>}，选择动作的摘要前缀固定。读不出来（缺失/形状不符）= 没有记录（fail-open：
	 * 按"初始默认空"处理，与本判据引入之前的行为一致）。
	 */
	private static boolean selectionActionRecorded(AiContext context) {
		if (context == null || context.getExtra() == null) {
			return false;
		}
		if (!(context.getExtra().get("recentActions") instanceof List<?> actions)) {
			return false;
		}
		for (Object action : actions) {
			if (action != null && String.valueOf(action).startsWith(SELECTION_ACTION_SUMMARY_PREFIX)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 把<b>刷新后的权威工作区状态</b>换进交棒历史的系统段（工作区段的唯一重写口）。
	 *
	 * <h2>为什么换段，而不是新增一条消息</h2>
	 * <ul>
	 * <li><b>不新增消息</b>：多一条 SYSTEM 会被 {@link SystemMessages#reattach} 在下次续跑时移到最前，
	 * 与首轮那条人设并列成"两条系统消息"，其中旧的一条还会被当成现役状态；多一条 USER 则会被
	 * 记忆/历史当成用户又发了一句。两者都会污染后续轮次；</li>
	 * <li><b>只换段内文本</b>：人设 / 知识库 / 回答语言这些<b>历史内容</b>一字不动（不拿当前配置覆盖
	 * 历史），{@code tool_use}/{@code tool_result} 的配对与顺序也不动 —— 换的只是"模型以为的现场"。</li>
	 * </ul>
	 *
	 * <p>渲染走 {@link ResilientChatService#workspaceStateText}（与首轮同一个 {@code ContextBuilder}），
	 * 段头/闭标签的锚点也用同一份常量：两侧各写一份就会在"首轮怎么描述工作区"上静默分叉。
	 *
	 * <p>旧快照里没有工作区段（更早的格式）⇒ 追加到系统消息末尾（模型至少拿到一次权威状态）；
	 * 渲染失败 ⇒ 只 WARN 并返回原历史 —— 交棒不能因为"状态说明"而失败。
	 */
	private List<Message> rewriteWorkspaceState(List<Message> history, AiContext context, String runId) {
		int leading = SystemMessages.leadingCount(history);
		if (leading == 0 || this.chatService == null || context == null) {
			return history;
		}
		String fresh;
		try {
			fresh = this.chatService.workspaceStateText(context);
		}
		catch (RuntimeException ex) {
			log.warn("续跑刷新工作区状态文本失败（沿用历史里的那份）runId={}：{}", runId, ex.getMessage());
			return history;
		}
		if (!StringUtils.hasText(fresh)) {
			return history;
		}
		List<Message> out = new ArrayList<>(history);
		boolean changed = false;
		for (int index = 0; index < leading; index++) {
			String text = history.get(index).getText();
			String rewritten = replaceWorkspaceSection(text, fresh);
			if (rewritten != null && !rewritten.equals(text)) {
				out.set(index, new SystemMessage(rewritten));
				changed = true;
			}
		}
		if (changed) {
			log.info("续跑交棒前已把工作区状态换成刷新后的权威值 runId={} page={} taskId={} step={}", runId,
					context.getPage(), context.getTaskId(), context.getStep());
		}
		return changed ? out : history;
	}

	/**
	 * 段级替换：从 {@link ContextBuilder#WORKSPACE_SECTION_HEADING} 到 {@code </workspace_state>}
	 * （含其后一个换行）整段换成新渲染的工作区段；没有段头时追加到末尾。
	 *
	 * <p>包内可见以便单点验证"只换这一段、别的文本一字不动"。
	 */
	static String replaceWorkspaceSection(String systemText, String freshSection) {
		if (systemText == null || freshSection == null || freshSection.isBlank()) {
			return systemText;
		}
		String section = freshSection.endsWith("\n") ? freshSection : freshSection + "\n";
		int start = systemText.indexOf(ContextBuilder.WORKSPACE_SECTION_HEADING);
		if (start < 0) {
			return systemText.endsWith("\n") ? systemText + section : systemText + "\n" + section;
		}
		int close = systemText.indexOf(ContextBuilder.WORKSPACE_STATE_CLOSE, start);
		int end = close < 0 ? systemText.length() : close + ContextBuilder.WORKSPACE_STATE_CLOSE.length();
		if (end < systemText.length() && systemText.charAt(end) == '\n') {
			end++;
		}
		return systemText.substring(0, start) + section + systemText.substring(end);
	}

	/**
	 * 驱动任务失败时落成 FAILED 终态，并清空未完成的工具调用。
	 * 已经是终态的快照不覆盖；写终态失败只留 WARN，不把异常抛给已发出的终帧。
	 *
	 * <p><b>N2（返修）</b>：整段"读快照 → 判取消 → 写 FAILED → 发 error"在<b>同一把</b> per-run 监视器内，
	 * 取消已先到（快照取消态 / 守卫拒写）时连失败终帧也不发 —— 否则一轮会同时出现
	 * {@code error} 与 {@code done{cancelled:true}}。只在写快照时加锁不够：锁外发 error 仍可被
	 * 取消的 done 插到前面。
	 *
	 * @param emitError 失败终帧的发送动作（在监视器内执行；调用方按旧口径决定是否发）
	 */
	private void markDriveFailure(RunSnapshot snapshot, Exception ex, Runnable emitError) {
		if (snapshot == null || snapshot.getRunId() == null) {
			if (emitError != null) {
				emitError.run();
			}
			return;
		}
		String runId = snapshot.getRunId();
		Object monitor = this.chatService == null ? null : this.chatService.finalizeMonitor(runId);
		if (monitor == null) {
			markDriveFailureLocked(snapshot, ex, emitError);
			return;
		}
		synchronized (monitor) {
			markDriveFailureLocked(snapshot, ex, emitError);
		}
	}

	private void markDriveFailureLocked(RunSnapshot snapshot, Exception ex, Runnable emitError) {
		try {
			RunSnapshot latest = this.store.get(snapshot.getRunId());
			RunSnapshot target = latest == null ? snapshot : latest;
			if (RunSnapshot.isTerminal(target.getStatus())) {
				// 取消是终态里唯一"终帧归别人发"的一种；DONE/FAILED 维持旧口径（写终态失败照发 error）。
				boolean cancelled = RunSnapshot.CANCELLED.equals(target.getStatus()) || target.isCancelled();
				if (!cancelled && emitError != null) {
					emitError.run();
				}
				return;
			}
			boolean providerGone = ex instanceof ProviderGoneException;
			target.setStatus(RunSnapshot.FAILED);
			target.setError((providerGone ? "PROVIDER_GONE：该轮使用的模型端点此刻不可用，无法按原模型续跑"
					: com.example.configmgr.common.ErrorCodes.AI_RESUME_FAILED + "：续跑执行失败")
					+ "（" + ex.getClass().getSimpleName() + "）");
			target.setInFlightToolCalls(new ArrayList<>());
			if (!this.store.saveTerminalUnlessCancelled(target)) {
				log.info("续跑失败终态被取消优先守卫拒写 runId={}（不发 error 终帧；取消收尾负责终态与终帧）",
						snapshot.getRunId());
				return;
			}
			// F：终态出口的进程内清理（与 cancel/finish 同一个统一出口；幂等）。
			if (this.chatService != null) {
				this.chatService.clearTerminalRunState(snapshot.getRunId());
			}
			if (emitError != null) {
				emitError.run();
			}
		}
		catch (RuntimeException writeEx) {
			log.warn("续跑失败落终态写回失败 runId={}（终帧照发，恢复侧按快照/租约兜底）：{}",
					snapshot.getRunId(), writeEx.getMessage());
			if (emitError != null) {
				emitError.run();
			}
		}
	}

	/**
	 * 续跑侧失败出口的<b>终帧幂等</b>（2026-10-29；返修后按<b>每次驱动</b>判）。
	 *
	 * <p>判据 = {@link SseChatEmitter#terminalFrameArchivedSinceDriveStart()}（本次驱动入口之后的
	 * done/error），入口下界由 {@code ResilientChatService#driveResumed} 在驱动开始时记一次。
	 * 这样"上一次驱动（{@code retry-model-step}）留下的 error"不会把本次的失败终帧吞掉。
	 *
	 * <p><b>边界</b>：这是"读 → 判 → 发"的顺序判据，只覆盖<b>同一驱动</b>（含同进程重复收尾）与
	 * <b>同一 runId 的相邻驱动</b>；跨实例并发的"恰好一条终帧"需要原子终帧位（本批未做，不声称根治）。
	 * 终态写已还租，写门对这一笔终帧不设防，故这里不把"写门会再拦一次"当兜底。
	 *
	 * <p>判据不可知（{@code null}）⇒ <b>照发</b>：宁可多一条，也不能吞掉本次驱动唯一的失败终帧。
	 */
	private void emitFailureErrorUnlessArchived(SseChatEmitter out, String code, String message) {
		Boolean archived = out.terminalFrameArchivedSinceDriveStart();
		if (Boolean.TRUE.equals(archived)) {
			log.info("本次驱动的失败终帧已落档，跳过重复 error code={}（每次驱动至多一条终帧）", code);
			return;
		}
		out.error(code, message);
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
		// 与续跑共用同一张进程内互斥表，键同口径（身份 + runId）：A 的取消收尾不得把 B 的同名轮次
		// 报成 IN_PROGRESS（那会让 B 的交棒链一直重试到预算耗尽）。
		String resumeKey = LocalRunKey.of(runId);
		if (this.resuming.putIfAbsent(resumeKey, Boolean.TRUE) != null) {
			log.info("取消收尾暂缓：本进程已有执行体在推进这一轮 runId={}（交棒侧有界重试）", runId);
			return CancelFinalize.IN_PROGRESS;
		}
		boolean gateHeld = false;
		RunIdentityScope.Scope identityScope = null;
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
			// 取消收尾由独立执行体（触发池/定时重试线程）接管：身份只从本轮记录恢复。
			identityScope = RunIdentityScope.open(snapshot.getTenantId(), snapshot.getUserId());
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
			this.resuming.remove(resumeKey);
			if (identityScope != null) {
				identityScope.close();
			}
		}
	}

	/**
	 * 在<b>交棒线程</b>上按本轮记录身份建立作用域（快照 0/0 = 空作用域，不伪造默认租户）。
	 *
	 * <p>执行器的 TTL 装饰只是第一层；恢复型任务的身份第一来源永远是记录本身。
	 */
	private static void withRunIdentity(RunSnapshot snapshot, Runnable action) {
		long tenantId = snapshot == null ? 0L : snapshot.getTenantId();
		long userId = snapshot == null ? 0L : snapshot.getUserId();
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(tenantId, userId)) {
			action.run();
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
		// N2：与取消收尾共用同一把 per-run 监视器 —— "读快照判终态/取消 → 写 FAILED → 出 error 终帧"
		// 必须是一个原子段，否则取消可以落在判定与写终态之间，一轮出现两条终帧。
		Object monitor = this.chatService == null ? null : this.chatService.finalizeMonitor(runId);
		if (monitor == null) {
			convergeProviderGoneLocked(runId, snapshot);
			return;
		}
		synchronized (monitor) {
			convergeProviderGoneLocked(runId, snapshot);
		}
	}

	private void convergeProviderGoneLocked(String runId, RunSnapshot snapshot) {
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
			// 取消优先：取消已先到 ⇒ 不写 FAILED、不发 error（取消侧发 done{cancelled:true}）。
			if (!this.store.saveTerminalUnlessCancelled(target)) {
				log.info("续跑硬否定让位取消 runId={}（不写 FAILED、不发 error 终帧）", runId);
				return;
			}
			// F：终态出口的进程内清理（幂等；失败只影响内存卫生）。
			if (this.chatService != null) {
				this.chatService.clearTerminalRunState(runId);
			}
			SseChatEmitter out = this.registry == null ? null : this.registry.of(runId);
			if (out == null) {
				return;
			}
			// 本批：终帧幂等（与 fail/emptyReply/环外异常同一判据）—— 归档里已有终帧时不再补一条。
			emitFailureErrorUnlessArchived(out, ErrorCodes.AI_PROVIDER_GONE, providerGoneMessage());
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
	 *
	 * <p><b>身份</b>：本判据在<b>续租线程</b>上执行（那里没有请求身份），因此必须按记录身份走
	 * {@link RunStore#get(String, long, long)}；用线程身份读只会命中 legacy 键 ⇒ 恒 {@code null}
	 * ⇒ 首拍即"驱动者已不在" ⇒ 主动还租、fence 不再推进（双驱动窗口）。
	 */
	private boolean snapshotNotTerminal(String runId, TenantUser identity) {
		try {
			RunSnapshot snapshot = identity == null ? this.store.get(runId)
					: this.store.get(runId, identity.tenantId(), identity.userId());
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
