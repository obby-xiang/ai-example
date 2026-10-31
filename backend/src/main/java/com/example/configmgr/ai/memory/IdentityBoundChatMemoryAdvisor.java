package com.example.configmgr.ai.memory;

import java.util.List;
import java.util.Map;

import com.example.configmgr.ai.gate.ToolExecutionEnvelope;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

/**
 * 官方 {@link MessageChatMemoryAdvisor} 的<b>身份绑定装饰器</b>：把本轮请求/记录里的租户身份
 * 显式绑到记忆读写上，使 {@code before}/{@code after} 无论跑在哪条线程都按身份键读写。
 *
 * <h2>被钉住的缺陷</h2>
 * 官方 advisor 的<b>读</b>在 {@code before}（{@code publishOn(boundedElastic)}）、<b>写</b>在
 * {@code after}（上游发元素的线程）。这两条线程都<b>没有请求身份</b>，而 {@link RedisChatMemoryRepository}
 * 的键由<b>线程</b>身份（{@link com.example.configmgr.ai.tenant.TenantKeyScope}）拼出：
 * <ul>
 * <li>无身份 ⇒ 键退化为无身份上下文的旧键形 {@code chat:mem:{<sessionId>}}（legacy 命名空间；迁移期
 * 裸键 {@code chat:mem:<sessionId>} 同族），索引成员退化为裸 {@code <sessionId>}；</li>
 * <li>同一 sessionId 在别的租户/用户下会读到这段正文（跨身份泄露）；</li>
 * <li>会话销毁按身份拼键 ⇒ 删不到 legacy 键，{@code complete=true} 之后正文仍留在 Redis；</li>
 * <li>下一轮在正常线程（有身份）上读到身份键（空窗）⇒ 用户看到"上下文丢了"。</li>
 * </ul>
 *
 * <h2>为什么是装饰器 + 请求绑定，而不是线程继承</h2>
 * <ol>
 * <li><b>身份来自本轮请求/记录</b>：优先取随请求下发的 {@link ToolExecutionEnvelope}（请求边界由
 * {@code ResilientChatService} 冻结的权威记录事实，客户端无法伪造）；信封缺失/无身份（{@code 0/0}
 * 旧快照）时取<b>发起调用那一拍</b>的线程身份（请求线程/本轮交棒线程，不是回调线程）；</li>
 * <li><b>显式绑定</b>：在 {@link #adviseStream}/{@link #adviseCall} 入口解析一次身份，构造一个
 * <b>本次调用专用</b>的官方 advisor，其 {@link ChatMemory} 由 {@link IdentityBoundChatMemory}
 * 包住 —— 身份是对象字段，读（{@code before}）与写（{@code after}）落在哪个线程都一致；</li>
 * <li><b>不用默认租户、不靠回调线程碰巧继承</b>：两处都取不到身份时<b>失败关闭</b>（读返回空、写丢弃
 * + WARN），绝不回落到无归属 legacy 命名空间 —— 那条路会写出销毁删不掉、别的身份读得到的正文。</li>
 * </ol>
 *
 * <h2>不接管官方语义</h2>
 * 读写时机、system 置顶仍全部由官方 {@link MessageChatMemoryAdvisor} 完成（每个请求 new 一个官方
 * 实例，只是换掉它持有的 {@link ChatMemory}）；本类只负责"用谁的身份、算哪一轮"。
 *
 * <h2>写入口：按轮合并（挂起轮 A + 新轮 B 互删）</h2>
 * 官方 advisor 的 {@code before}/{@code after} 是<b>增量写</b>（本轮用户消息 + 本轮助手回答）。
 * 若让它落到 {@code MessageWindowChatMemory#add}（读整窗 → 追加 → 整窗 {@code saveAll}），它会把
 * "读到的旧窗 + 自己两条"整窗覆盖：同一会话里另一条 run（挂起轮续跑 + 用户新开一轮）的整轮就从
 * {@code /history}（唯一读源）消失。因此本类把<b>本轮归属</b>（{@link MemoryTurn}：runId + 本轮起点）
 * 一并绑定到记忆句柄上，写落到 {@link RunScopedChatMemory#add(String, MemoryTurn, List)} ——
 * 与人工收敛/压缩写回同一个按轮合并 + 会话级 CAS 的写入口。
 */
public final class IdentityBoundChatMemoryAdvisor implements BaseChatMemoryAdvisor {

	private static final Logger log = LoggerFactory.getLogger(IdentityBoundChatMemoryAdvisor.class);

	private final ChatMemory chatMemory;

	private final int order;

	private final Scheduler scheduler;

	/** 与官方默认装配等价：同一优先级、同一流式调度器。 */
	public IdentityBoundChatMemoryAdvisor(ChatMemory chatMemory) {
		this(chatMemory, Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER, BaseAdvisor.DEFAULT_SCHEDULER);
	}

	IdentityBoundChatMemoryAdvisor(ChatMemory chatMemory, int order, Scheduler scheduler) {
		this.chatMemory = chatMemory;
		this.order = order;
		this.scheduler = scheduler;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public Scheduler getScheduler() {
		return this.scheduler;
	}

	/**
	 * 流式：入口（订阅线程 = 发起本轮的那一拍）解析身份，交给本次调用专用的官方 advisor。
	 *
	 * <p>官方的 {@code before} 会在 {@code boundedElastic} 上跑、{@code after} 在聚合完成时跑，
	 * 但身份已经固定在 {@link IdentityBoundChatMemory} 里，不依赖那两条线程的任何上下文。
	 */
	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
			StreamAdvisorChain streamAdvisorChain) {
		return official(resolveIdentity(chatClientRequest), resolveTurn(chatClientRequest)).adviseStream(chatClientRequest,
				streamAdvisorChain);
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
		return official(resolveIdentity(chatClientRequest), resolveTurn(chatClientRequest)).adviseCall(chatClientRequest,
				callAdvisorChain);
	}

	/**
	 * 兜底钩子（正常链路不会走到：{@link #adviseCall}/{@link #adviseStream} 已整段接管）。
	 *
	 * <p>这里只能取线程身份与线程锚点 —— 没有请求可读。取不到时 {@link IdentityBoundChatMemory} 失败关闭，
	 * 同样不会写出无归属正文。
	 */
	@Override
	public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
		return official(RequestContextUtils.currentOrNull(), threadTurn()).before(chatClientRequest, advisorChain);
	}

	@Override
	public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
		return official(RequestContextUtils.currentOrNull(), threadTurn()).after(chatClientResponse, advisorChain);
	}

	/** 本次调用专用的官方 advisor（读写时机与系统提示置顶逐字沿用官方实现；窗口裁剪由按轮合并保证）。 */
	private MessageChatMemoryAdvisor official(TenantUser identity, MemoryTurn turn) {
		return MessageChatMemoryAdvisor.builder(new IdentityBoundChatMemory(this.chatMemory, identity, turn))
			.order(this.order)
			.scheduler(this.scheduler)
			.build();
	}

	/**
	 * 本轮归属：<b>请求里的记录事实优先</b>（{@link ToolExecutionEnvelope} 的 runId + 创建时刻），
	 * 其次发起调用这一拍的日志锚点 {@link AiTrace#currentRunId()}；都没有 ⇒ {@code null}
	 * （记忆按"无归属"块追加，不整窗覆盖别的 run）。
	 *
	 * <p>起点用信封的 {@code createdAtMs}（= 快照创建时刻）：续跑沿用<b>块里已有的</b>起点，
	 * 不会因为"续跑是后来才发生的"把这一轮挪到新轮后面。
	 */
	static MemoryTurn resolveTurn(ChatClientRequest chatClientRequest) {
		ToolExecutionEnvelope envelope = envelopeOf(chatClientRequest);
		if (envelope != null) {
			MemoryTurn fromRecord = MemoryTurn.of(envelope.runId(), envelope.createdAtMs());
			if (fromRecord != null) {
				return fromRecord;
			}
		}
		return threadTurn();
	}

	private static MemoryTurn threadTurn() {
		return MemoryTurn.of(AiTrace.currentRunId(), 0L);
	}

	/**
	 * 本轮身份：<b>请求里的记录事实优先</b>，其次发起调用这一拍的线程身份；都没有 ⇒ {@code null}
	 * （调用方失败关闭，不伪造默认租户）。
	 */
	private static TenantUser resolveIdentity(ChatClientRequest chatClientRequest) {
		ToolExecutionEnvelope envelope = envelopeOf(chatClientRequest);
		TenantUser fromRecord = envelope != null && RunIdentityScope.usable(envelope.tenantId(), envelope.userId())
				? new TenantUser(envelope.tenantId(), envelope.userId()) : null;
		return fromRecord != null ? fromRecord : RequestContextUtils.currentOrNull();
	}

	/**
	 * 请求携带的<b>记录事实</b>（{@link ToolExecutionEnvelope}）。
	 *
	 * <p>信封由本轮请求边界冻结（{@code ResilientChatService#toolContext}），随
	 * {@code ToolCallingChatOptions.toolContext} 进请求；它不是客户端可填的字段，因此可作身份与
	 * 轮次归属的来源。旧快照的 {@code 0/0} 信封<b>没有身份</b>（返回的信封仍可用于 runId，
	 * 身份侧由 {@link RunIdentityScope#usable} 判定）。
	 */
	private static ToolExecutionEnvelope envelopeOf(ChatClientRequest chatClientRequest) {
		Map<String, Object> toolContext = toolContextOf(chatClientRequest);
		if (toolContext == null) {
			return null;
		}
		return ToolExecutionEnvelope.fromToolContext(toolContext);
	}

	private static Map<String, Object> toolContextOf(ChatClientRequest chatClientRequest) {
		if (chatClientRequest == null || chatClientRequest.prompt() == null) {
			return null;
		}
		ChatOptions options = chatClientRequest.prompt().getOptions();
		return options instanceof ToolCallingChatOptions toolCalling ? toolCalling.getToolContext() : null;
	}

	/**
	 * 一次调用内<b>绑定身份与轮次</b>的记忆句柄：每次读写都在显式身份作用域里执行，让仓储按身份拼键；
	 * 写走 {@link RunScopedChatMemory#add(String, MemoryTurn, List)}（按轮合并），其余 run 的块不受影响。
	 *
	 * <p>身份为 {@code null} 时失败关闭：读返回空、写丢弃（+ WARN），<b>不</b>回落到无归属 legacy
	 * 命名空间 —— 那正是本类要消灭的"销毁删不掉、别的身份读得到"的正文。
	 *
	 * <p>轮次为 {@code null}（旧快照且没有日志锚点）时回落到官方 {@code add}：那是"没有本轮"的
	 * 兼容形态，只在无信封的旧请求上出现。
	 */
	static final class IdentityBoundChatMemory implements ChatMemory {

		private final ChatMemory delegate;

		/** {@code null} = 本轮没有可用身份（失败关闭）。 */
		private final TenantUser identity;

		/** {@code null} = 本轮没有可用轮次（回落到官方写入口）。 */
		private final MemoryTurn turn;

		IdentityBoundChatMemory(ChatMemory delegate, TenantUser identity, MemoryTurn turn) {
			this.delegate = delegate;
			this.identity = identity;
			this.turn = turn;
		}

		@Override
		public void add(String conversationId, List<Message> messages) {
			if (denied("写", conversationId)) {
				return;
			}
			try (RunIdentityScope.Scope ignored = RunIdentityScope.open(this.identity)) {
				if (this.turn != null && this.delegate instanceof RunScopedChatMemory scoped) {
					scoped.add(conversationId, this.turn, messages);
					return;
				}
				this.delegate.add(conversationId, messages);
			}
		}

		@Override
		public List<Message> get(String conversationId) {
			if (denied("读", conversationId)) {
				return List.of();
			}
			try (RunIdentityScope.Scope ignored = RunIdentityScope.open(this.identity)) {
				return this.delegate.get(conversationId);
			}
		}

		@Override
		public void clear(String conversationId) {
			if (denied("清空", conversationId)) {
				return;
			}
			try (RunIdentityScope.Scope ignored = RunIdentityScope.open(this.identity)) {
				this.delegate.clear(conversationId);
			}
		}

		private boolean denied(String action, String conversationId) {
			if (this.identity != null) {
				return false;
			}
			log.warn("本轮没有可绑定的租户/用户身份，拒绝{}记忆 conversationId={}（失败关闭：不写不读无归属 legacy 命名空间）",
					action, conversationId);
			return true;
		}

	}

}
