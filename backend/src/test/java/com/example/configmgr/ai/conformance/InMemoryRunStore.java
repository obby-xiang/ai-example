package com.example.configmgr.ai.conformance;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内 {@link RunStore} 替身（帧序一致性用例的外置状态面）。
 *
 * <h2>为什么不用 {@code mock(RunStore.class)}</h2>
 * 帧协议的一半事实活在<b>外置状态</b>里：{@code appendEvent} 跳过心跳、{@code lastEventSeq}
 * 取自归档末帧（跨进程续号的锚点）、{@code pending}/{@code claimExecution} 的幂等语义。
 * 用逐方法打桩的 mock 会把这几条"存的语义"变成"我在测试里写的假设"，于是断言就只是自证。
 * 本类按真实实现的语义重写这些方法（心跳不落档、末帧取号、认领先于执行、台账即重放判据），
 * 让 {@link com.example.configmgr.ai.run.SseChatEmitter}、{@code ConfirmGate}、
 * {@code SpToolCallingManager} 跑在<b>同一份</b>语义上。
 *
 * <h2>与 Redis 实现的<b>有意</b>差异（断言前必须知道）</h2>
 * <ol>
 * <li><b>对象同一性</b>：真实 {@code pending()} 每次从 Redis JSON 反序列化出一个新对象，
 * 本替身默认返回<b>同一个</b>对象引用（既有帧序用例依赖这种引用可见性）。需要锁住
 * "结算写的是新副本、等待前那份仍是旧值"的实现时，用 {@link #setDeepCopyPending(boolean)}
 * （或构造参数）打开往返模式，{@code pending()} 即按真实语义返回深拷贝；</li>
 * <li><b>无 TTL、无 3000 帧窗口裁剪</b>：用例里都不触及这两个边界；</li>
 * <li><b>messageJson 不落</b>：本替身不调 {@code MessageJsonCodec}（用例断言的是帧，不是历史条数）。</li>
 * </ol>
 *
 * <h2>T3-2 语义同步</h2>
 * {@code appendEvent} 与真实实现同口径：{@code heartbeat} 跳过归档（只留活动戳），
 * {@code delta} <b>入档</b>（B1 2026-10-20 起 —— 挂起即断后的差量补帧依赖它）；
 * {@code ai:seq:<runId>} 语义由 {@link #lastIssuedSeq}/{@link #recordIssuedSeq} 的进程内表复刻
 * （发放即登记、跨实例续号可读），{@code deleteRun} 一并清表。
 */
public class InMemoryRunStore extends RunStore {

	private final Map<String, RunSnapshot> runs = new ConcurrentHashMap<>();

	private final Map<String, Map<String, PendingToolCall>> pendings = new ConcurrentHashMap<>();

	private final Map<String, Map<String, String>> claims = new ConcurrentHashMap<>();

	private final Map<String, List<Map<String, Object>>> ledger = new ConcurrentHashMap<>();

	/** B23L1 批③a：{@code ai:idem:<runId>} 的进程内替身（toolCallId → 台账下标）。 */
	private final Map<String, Map<String, Integer>> idemIndex = new ConcurrentHashMap<>();

	private final Map<String, List<Map<String, Object>>> events = new ConcurrentHashMap<>();

	private final Map<String, Long> beats = new ConcurrentHashMap<>();

	/** R1-09：{@code ai:activity:<runId>} 的进程内替身（最后一条非心跳业务帧的 atMs）。 */
	private final Map<String, Long> businessActivity = new ConcurrentHashMap<>();

	/** session → 当前挂起轮（T2b-D#1 索引的进程内替身；无 TTL）。 */
	private final Map<String, String> sessionCurrent = new ConcurrentHashMap<>();

	/** T3-2：{@code ai:seq:<runId>} 的进程内替身（已发放最大业务号）。 */
	private final Map<String, Long> issuedSeqs = new ConcurrentHashMap<>();

	/**
	 * 批次2（AR-05）：{@code ai:run:<runId>} 上"写过取消态"的进程内事实 —— 真实实现由取消优先
	 * CAS 在 Lua 里 {@code cjson} 复读当前快照得出；替身没有 Lua，故用这张表表达同一事实。
	 */
	private final Set<String> cancelledMarks = ConcurrentHashMap.newKeySet();

	/** B23L1 批②a：{@code ai:req:<runId>}（field=toolCallId, value=置位时刻）。 */
	private final Map<String, Map<String, String>> reqMarks = new ConcurrentHashMap<>();

	/** B23L1 批②a：{@code ai:decided:<runId>}（field=toolCallId, value={@code status|atMs|instanceId}）。 */
	private final Map<String, Map<String, String>> decided = new ConcurrentHashMap<>();

	/** B23L1 批②a：续跑权租约的进程内替身（key = {@code ai:lease:run:<runId>} → epoch）。 */
	private final Map<String, Long> heldEpochs = new ConcurrentHashMap<>();

	/** B23L1 批②a：fence（键 = runId → 已落库的最大 epoch）。 */
	private final Map<String, Long> fenceEpochs = new ConcurrentHashMap<>();

	/** 取号器（与真实的 per-run 计数器 {@code INCR ai:lease:epoch:{run:<runId>}} 同口径：只增不减）。 */
	private final java.util.concurrent.atomic.AtomicLong epochSeq = new java.util.concurrent.atomic.AtomicLong();

	/** 与真实 Redis 对齐的往返模式（见类注"对象同一性"）：开启后 {@link #pending} 返回深拷贝。 */
	private volatile boolean deepCopyPending;

	/**
	 * B23L1 批②b：可选的<b>真实</b>租约设施（{@link com.example.configmgr.ai.run.LeaseService}）。
	 *
	 * <p>未设置时沿用批②a 的进程内替身（永远抢得到、epoch 自增、无 TTL）：既有用例的语义不变。
	 * 设成真实设施之后，本替身的租约薄口（抢/放/heldEpoch/fence 写）全部交给它，
	 * 于是"两个实例共享一份 Redis"的 fencing 用例可以跑在同一套语义上。
	 */
	private volatile com.example.configmgr.ai.run.LeaseService leaseFacility;

	/** 装配真实租约设施（{@code leaseService()} 与三个薄口随之改走它）。 */
	public void useLeaseFacility(com.example.configmgr.ai.run.LeaseService leaseService) {
		this.leaseFacility = leaseService;
	}

	/**
	 * B23L1 批③b（I5 语料）：直接铺"本进程仍持有的续跑权 epoch"。
	 *
	 * <p>真实实现里 epoch 来自 {@link com.example.configmgr.ai.run.LeaseService}；本替身没有
	 * leaseFacility 时用进程内表顶替。用例靠它把"持租但已不是主人"这个窗口<b>确定化</b>
	 * （而不必先抢租再等别人接管）。
	 */
	public void seedHeldRunEpoch(String runId, long epoch) {
		this.heldEpochs.put(runLeaseKey(runId), epoch);
	}

	/** B23L1 批③b（I5 语料）：直接铺已落库的 fence（{@code ai:fence:run:<runId>} 的替身）。 */
	public void seedFence(String runId, long fence) {
		this.fenceEpochs.put(runId, fence);
	}

	@Override
	public com.example.configmgr.ai.run.LeaseService leaseService() {
		return this.leaseFacility;
	}

	private static final ObjectMapper JSON = new ObjectMapper();

	public InMemoryRunStore() {
		super(null, new ObjectMapper(), new AiProperties());
	}

	/**
	 * 往返模式：{@code pending()} 每次返回经 JSON 往返回来的<b>新对象</b>（真实
	 * {@code RunStore} 从 Redis 反序列化的语义）。默认关闭以保持既有用例的引用可见性断言；
	 * 凡"改一份是否影响另一份"不成立的实现（如 P0-1 曾用等待前的旧 pending 取信封）都靠它揭穿。
	 */
	public InMemoryRunStore(boolean deepCopyPending) {
		this();
		this.deepCopyPending = deepCopyPending;
	}

	public void setDeepCopyPending(boolean deepCopyPending) {
		this.deepCopyPending = deepCopyPending;
	}

	public boolean isDeepCopyPending() {
		return this.deepCopyPending;
	}

	// ── 标识与键名（与真实实现的键结构同形） ─────────────────────────────────

	@Override
	public String instanceId() {
		return "instance-conformance";
	}

	@Override
	public String runKey(String runId) {
		return RUN_PREFIX + runId;
	}

	@Override
	public String pendingKey(String runId) {
		return PENDING_PREFIX + runId;
	}

	@Override
	public String claimKey(String runId) {
		return CLAIM_PREFIX + runId;
	}

	@Override
	public String ledgerKey(String runId) {
		return LEDGER_PREFIX + runId;
	}

	@Override
	public String eventsKey(String runId) {
		return EVENTS_PREFIX + runId;
	}

	@Override
	public String beatKey(String runId) {
		return BEAT_PREFIX + runId;
	}

	@Override
	public String seqKey(String runId) {
		return SEQ_PREFIX + runId;
	}

	// ── 快照 ────────────────────────────────────────────────────────────────

	@Override
	public RunSnapshot create(String runId, String sessionId, AiContext context, List<Message> history,
			MessageJsonCodec codec, Long providerId, String modelId) {
		return create(runId, sessionId, context, history, codec, providerId, modelId, null);
	}

	@Override
	public RunSnapshot create(String runId, String sessionId, AiContext context, List<Message> history,
			MessageJsonCodec codec, Long providerId, String modelId, RunSnapshot.FrozenParams frozen) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(sessionId);
		// 租户隔离：与真实 RunStore 同口径 —— 首轮创建时从上下文固定身份；缺失保持 0（不伪造）。
		com.example.configmgr.ai.tenant.TenantUser identity =
				com.example.configmgr.ai.tenant.RequestContextUtils.currentOrNull();
		if (identity != null) {
			snapshot.setTenantId(identity.tenantId());
			snapshot.setUserId(identity.userId());
		}
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setContext(context == null ? AiContext.empty().toMap() : context.toMap());
		// 模型配置专项批①：与真实实现同口径固化模型身份（additive 字段）
		snapshot.setProviderId(providerId);
		snapshot.setModelId(modelId);
		// L-26：与真实实现同口径冻结生效参数（null FrozenParams = 不冻结 = 旧签名语义）
		snapshot.freezeParams(frozen);
		snapshot.setCreatedBy(instanceId());
		snapshot.setCreatedAtMs(System.currentTimeMillis());
		save(snapshot);
		return snapshot;
	}

	@Override
	public void save(RunSnapshot snapshot) {
		snapshot.setUpdatedAtMs(System.currentTimeMillis());
		// 续租 fencing 收口批：与真实 RunStore 同口径 —— LOST/UNAVAILABLE ⇒ 只读，不得覆盖新主人的终态。
		com.example.configmgr.ai.run.LeaseService.LeaseAuthority authority = runLeaseAuthority(snapshot.getRunId());
		if (authority.readOnly()) {
			return;
		}
		// B23L1 批②a：与真实 RunStore 同语义 —— 持租走 fence 写（陈旧 epoch 拒写、fence 推进），
		// 不持租保持 plain 写（E3 红线：无租约不拒写）。
		long epoch = heldRunEpoch(snapshot.getRunId());
		if (epoch > 0L) {
			long fence = this.fenceEpochs.getOrDefault(snapshot.getRunId(), 0L);
			if (fence > epoch) {
				return;
			}
			snapshot.setRunEpoch(epoch);
			this.fenceEpochs.put(snapshot.getRunId(), epoch);
		}
		// 批次2：与真实实现的"任何非取消状态不得从 CANCELLED 转出"同一不变量
		// （真实实现由 Lua 的 cjson 复读当前快照；替身用写入过取消态的事实表）。
		boolean incomingCancelled = RunSnapshot.CANCELLED.equals(snapshot.getStatus()) || snapshot.isCancelled();
		if (!incomingCancelled && this.cancelledMarks.contains(snapshot.getRunId())) {
			return;
		}
		this.runs.put(snapshot.getRunId(), snapshot);
		if (incomingCancelled) {
			this.cancelledMarks.add(snapshot.getRunId());
		}
		// 与真实实现同一联动口径（T2b-D#1）：SUSPENDED 建索引、终态清索引、RUNNING 保留
		String status = snapshot.getStatus();
		if (RunSnapshot.SUSPENDED.equals(status)) {
			markSessionCurrent(snapshot.getSessionId(), snapshot.getRunId());
		}
		else if (RunSnapshot.isTerminal(status)) {
			clearSessionCurrent(snapshot.getSessionId(), snapshot.getRunId());
			releaseRunLease(snapshot.getRunId());
		}
	}

	/**
	 * 取消优先终态守卫（进程内复刻；批次2 / AR-05）：写入过取消态的轮，其后任何 FAILED/DONE 写一律被拒。
	 *
	 * <p>与真实 {@link RunStore#saveTerminalUnlessCancelled} 同一可判定契约：被拒 ⇒ {@code false}，
	 * 调用方（{@code fail()} / {@code emptyReply()} / 环外异常）据此不发自己的失败终帧。
	 */
	@Override
	public boolean saveTerminalUnlessCancelled(RunSnapshot snapshot) {
		return saveStatusUnlessCancelled(snapshot);
	}

	/**
	 * 任意状态写入的取消优先守卫（进程内复刻）：已经写过取消态的轮，其后任何非取消状态写一律被拒。
	 *
	 * <p>与真实 {@link RunStore#saveStatusUnlessCancelled} 同一可判定契约：被拒 ⇒ {@code false}。
	 * 重跑/续跑的状态转换靠它判断"取消已先到 ⇒ 不得驱动"。
	 */
	@Override
	public boolean saveStatusUnlessCancelled(RunSnapshot snapshot) {
		boolean incomingCancelled = RunSnapshot.CANCELLED.equals(snapshot.getStatus()) || snapshot.isCancelled();
		if (!incomingCancelled && this.cancelledMarks.contains(snapshot.getRunId())) {
			return false;
		}
		save(snapshot);
		return true;
	}

	/**
	 * 条件创建（与真实现 {@code RunStore#createIfAbsent} 同语义）：只有快照键不存在时才写；
	 * 已存在 ⇒ {@code false} 且<b>不改动</b>现有快照。
	 *
	 * <p>为什么替身必须复刻它：它是 {@code SpToolCallingManager#recoverSnapshot} 的恢复写口。不覆写就会
	 * 落到生产实现（Lua 脚本 + 真实 Redis），而替身的 {@code redis} 为 null ⇒ 恢复被判失败 ——
	 * 用例看到的"不可恢复"就不再是被测语义。<b>存在性判定与写在同一段代码内</b>（单进程替身即原子）。
	 */
	@Override
	public boolean createIfAbsent(RunSnapshot snapshot) {
		snapshot.setUpdatedAtMs(System.currentTimeMillis());
		// 与 save 同一写权限口径：丢租/设施不可确认 ⇒ 不写（这一笔不是创建，也不该有副作用）。
		if (runLeaseAuthority(snapshot.getRunId()).readOnly()) {
			return false;
		}
		if (this.runs.putIfAbsent(snapshot.getRunId(), snapshot) != null) {
			return false;
		}
		// 与 save 同一联动口径：RUNNING/SUSPENDED 建会话索引、终态清索引并释放租约。
		String status = snapshot.getStatus();
		if (RunSnapshot.SUSPENDED.equals(status) || RunSnapshot.RUNNING.equals(status)) {
			markSessionCurrent(snapshot.getSessionId(), snapshot.getRunId());
		}
		else if (RunSnapshot.isTerminal(status)) {
			clearSessionCurrent(snapshot.getSessionId(), snapshot.getRunId());
			releaseRunLease(snapshot.getRunId());
		}
		return true;
	}

	/** 轮内预算 armed 的进程内持久化（AR-10 第 3 条）：直接挂在快照对象上，语义与真实实现一致。 */
	@Override
	public void markTurnBudget(String runId, long armedAtMs, int skippedBatches) {
		RunSnapshot snapshot = this.runs.get(runId);
		if (snapshot == null) {
			return;
		}
		snapshot.setBudgetArmedAtMs(Math.max(snapshot.getBudgetArmedAtMs(), armedAtMs));
		snapshot.setBudgetSkippedBatches(Math.max(snapshot.getBudgetSkippedBatches(), skippedBatches));
	}

	// ── B23L1 批②a：租约 / req 位 / 决策 guard 的进程内替身 ────────────────────

	@Override
	public java.util.Optional<com.example.configmgr.ai.run.LeaseService.Lease> tryAcquireRunLease(String runId) {
		String key = runLeaseKey(runId);
		if (this.leaseFacility != null) {
			// 真实设施的 TTL 用它的配置（ttl=null ⇒ 走 app.ai.lease.ttl），与生产同口径
			return this.leaseFacility.tryAcquire(key, null);
		}
		Long existing = this.heldEpochs.get(key);
		long epoch = existing != null ? existing : this.epochSeq.incrementAndGet();
		this.heldEpochs.put(key, epoch);
		return java.util.Optional.of(new com.example.configmgr.ai.run.LeaseService.Lease(key, epoch, instanceId()));
	}

	@Override
	public com.example.configmgr.ai.run.LeaseService.AcquireResult acquireRunLease(String runId) {
		if (this.leaseFacility != null) {
			return this.leaseFacility.acquire(runLeaseKey(runId), null);
		}
		// 进程内替身 = 单实例：抢租永远成功（没有"别人持有"这回事）。
		// created 与真实实现同口径：视图里本来就有 ⇒ 重入（没有释放权）。
		String key = runLeaseKey(runId);
		boolean existed = this.heldEpochs.containsKey(key);
		return new com.example.configmgr.ai.run.LeaseService.AcquireResult(
				com.example.configmgr.ai.run.LeaseService.AcquireOutcome.ACQUIRED,
				tryAcquireRunLease(runId).orElse(null), !existed);
	}

	@Override
	public void releaseRunLease(String runId) {
		if (this.leaseFacility != null) {
			this.leaseFacility.releaseIfHeld(runLeaseKey(runId));
			return;
		}
		this.heldEpochs.remove(runLeaseKey(runId));
	}

	@Override
	public long heldRunEpoch(String runId) {
		if (this.leaseFacility != null) {
			return this.leaseFacility.heldEpoch(runLeaseKey(runId));
		}
		return this.heldEpochs.getOrDefault(runLeaseKey(runId), 0L);
	}

	/**
	 * fence 的进程内替身（{@code ai:fence:run:<runId>}）。
	 *
	 * <p>生产实现从 Redis 读同一个键；本替身的该键是 {@link #fenceEpochs} —— 由 {@link #save}
	 * 在持租时推进（epoch 取自 {@link #heldRunEpoch}，装配 {@code leaseFacility} 时即设施语义）、
	 * {@link #seedFence} 铺语料。不覆写会落到生产读口：本类的 {@code redis} 为 null ⇒
	 * {@code opsForValue()} NPE，被写门的 fail-closed 分支当成"fence 读不出来" ⇒ 帧被丢弃的
	 * <b>理由</b>就从"fence 越过本进程 epoch"变成"替身读口坏了"，缺陷被掩盖。
	 *
	 * <p>与 {@link #tryClaimExecution} 读同一张表（认领与写门同源判据）。跨实例的 fence 推进
	 * 由生产 {@code RunStore} 用例覆盖（{@code LeaseLossReadOnlyTest} /
	 * {@code RunStoreFenceAheadWriteGateTest}）：本替身的 {@code fenceEpochs} 不跨实例共享。
	 */
	@Override
	public long fenceEpoch(String runId) {
		return this.fenceEpochs.getOrDefault(runId, 0L);
	}

	@Override
	public PendingToolCall markRequestEmitted(String runId, String toolCallId, long atMs) {
		PendingToolCall pending = pending(runId, toolCallId);
		if (pending == null || !PendingToolCall.PENDING.equals(pending.getStatus())) {
			return pending;
		}
		reqTable(runId).putIfAbsent(toolCallId, String.valueOf(atMs));
		return pending(runId, toolCallId);
	}

	@Override
	public DecisionClaim tryRecordDecision(String runId, String toolCallId, String status) {
		String value = status + "|" + System.currentTimeMillis() + "|" + instanceId();
		String winner = decidedTable(runId).putIfAbsent(toolCallId, value);
		return winner == null ? new DecisionClaim(true, value) : new DecisionClaim(false, winner);
	}

	@Override
	public String decisionOf(String runId, String toolCallId) {
		return decidedTable(runId).get(toolCallId);
	}

	@Override
	public String decidedStatus(String runId, String toolCallId) {
		String raw = decisionOf(runId, toolCallId);
		if (raw == null) {
			return null;
		}
		int sep = raw.indexOf('|');
		return sep < 0 ? raw : raw.substring(0, sep);
	}

	@Override
	public boolean touchActiveRun(String runId) {
		return touchActiveRun(get(runId));
	}

	@Override
	public boolean touchActiveRun(RunSnapshot snapshot) {
		if (snapshot == null || snapshot.getRunId() == null) {
			return false;
		}
		if (RunSnapshot.isTerminal(snapshot.getStatus())) {
			return false;
		}
		// 无 TTL 可续：语义等价于"值匹配才续期"（不属于自己的轮不动作）
		renewSessionCurrent(snapshot.getSessionId(), snapshot.getRunId());
		return true;
	}

	@Override
	public RunSnapshot get(String runId) {
		return this.runs.get(runId);
	}

	/**
	 * 显式身份读口（与真实现同语义）：记录身份必须等于给定身份，否则按不存在处理。
	 *
	 * <p>替身没有键，但"归属复核"这一层是产品契约的一部分（真实实现先按身份拼键、再核记录），
	 * 因此这里也照做 —— 否则用例会以为"读到别人那一轮"是允许的。
	 */
	@Override
	public RunSnapshot get(String runId, long tenantId, long userId) {
		RunSnapshot snapshot = this.runs.get(runId);
		if (snapshot == null || snapshot.getTenantId() != tenantId || snapshot.getUserId() != userId) {
			return null;
		}
		return snapshot;
	}

	/**
	 * 全局索引的进程内替身：只返回<b>带身份</b>的记录（与真实现"无归属成员不返回"同口径；
	 * 0/0 旧轮由 {@code listRuns} 的 legacy 分支按旧键形发现）。
	 */
	@Override
	public List<com.example.configmgr.ai.tenant.RunKeyScope.IndexRef> allRunRecords() {
		List<com.example.configmgr.ai.tenant.RunKeyScope.IndexRef> out = new ArrayList<>();
		for (RunSnapshot snapshot : this.runs.values()) {
			if (com.example.configmgr.ai.tenant.RunIdentityScope.usable(snapshot.getTenantId(),
					snapshot.getUserId())) {
				out.add(new com.example.configmgr.ai.tenant.RunKeyScope.IndexRef(snapshot.getTenantId(),
						snapshot.getUserId(), snapshot.getRunId()));
			}
		}
		out.sort((left, right) -> left.runId().compareTo(right.runId()));
		return out;
	}

	@Override
	public void deleteRun(String runId) {
		this.runs.remove(runId);
		this.pendings.remove(runId);
		this.claims.remove(runId);
		this.ledger.remove(runId);
		this.idemIndex.remove(runId);
		this.events.remove(runId);
		this.issuedSeqs.remove(runId);
		// R1-09：ai:activity / ai:beat 与生产一致 —— TTL 型锚点不随 deleteRun 删除（按 session-ttl 自清）。
		// B23L1 批②a：租约/fence/req/decided 同属"本轮的全部键"
		this.reqMarks.remove(runId);
		this.decided.remove(runId);
		this.heldEpochs.remove(runLeaseKey(runId));
		this.fenceEpochs.remove(runId);
	}

	/** 显式身份删除（与真实现同语义：身份不符不得静默 no-op）。 */
	@Override
	public void deleteRun(String runId, long tenantId, long userId) {
		RunSnapshot snapshot = this.runs.get(runId);
		if (snapshot != null && (snapshot.getTenantId() != tenantId || snapshot.getUserId() != userId)) {
			throw new IllegalStateException("拒绝以身份 " + tenantId + "/" + userId + " 删除不属于它的轮次 runId="
					+ runId + "（记录归属=" + snapshot.getTenantId() + "/" + snapshot.getUserId() + "）");
		}
		deleteRun(runId);
	}

	@Override
	public void deleteRun(com.example.configmgr.ai.tenant.RunKeyScope.IndexRef ref) {
		if (ref == null) {
			throw new IllegalArgumentException("删除轮次要求索引成员自带身份（不得凭 runId 猜身份）");
		}
		deleteRun(ref.runId(), ref.tenantId(), ref.userId());
	}

	/**
	 * 索引发现面的进程内替身：与真实现同口径按<b>当前身份</b>过滤 —— 有身份只看自己的成员；
	 * 无身份（系统扫描线程）只看 legacy（{@code 0/0}）成员。
	 *
	 * <p>不这样过滤的话，带身份的轮次会同时从 {@code ResumeService.listRuns()} 的复合成员与
	 * legacy 分支被列出两次 —— 真实现里 legacy 分支读的是旧键形，对带身份的轮次恒为 {@code null}。
	 */
	@Override
	public List<String> allRunIds() {
		com.example.configmgr.ai.tenant.TenantUser current =
				com.example.configmgr.ai.tenant.RequestContextUtils.currentOrNull();
		List<String> ids = new ArrayList<>();
		for (RunSnapshot snapshot : this.runs.values()) {
			boolean usable = com.example.configmgr.ai.tenant.RunIdentityScope.usable(snapshot.getTenantId(),
					snapshot.getUserId());
			boolean visible = current == null ? !usable
					: usable && current.tenantId() == snapshot.getTenantId()
							&& current.userId() == snapshot.getUserId();
			if (visible) {
				ids.add(snapshot.getRunId());
			}
		}
		Collections.sort(ids);
		return ids;
	}

	// ── 挂起 ────────────────────────────────────────────────────────────────

	@Override
	public void saveSuspended(String runId, String assistantContent, List<PendingToolCall> items,
			List<Message> history, MessageJsonCodec codec) {
		saveSuspended(runId, assistantContent, items, history, codec, null);
	}

	@Override
	public void saveSuspended(String runId, String assistantContent, List<PendingToolCall> items,
			List<Message> history, MessageJsonCodec codec, java.util.Map<String, Object> assistantMetadata) {
		for (PendingToolCall pending : items) {
			// 与真实 RunStore 同口径：已结算条目（如预检失败）不改写 requestedAtMs，避免时间倒挂
			if (!PendingToolCall.PENDING.equals(pending.getStatus())) {
				continue;
			}
			pending.setRequestedAtMs(System.currentTimeMillis());
			putPending(runId, pending);
		}
		RunSnapshot snapshot = get(runId);
		if (snapshot == null) {
			throw new IllegalStateException("挂起前找不到轮次快照 " + runId);
		}
		snapshot.setAssistantContent(assistantContent);
		// 推理元数据：与真实实现同口径——整份不透明 map 原样保存（null = 没有）
		snapshot.setAssistantMetadata(assistantMetadata == null ? null : new java.util.LinkedHashMap<>(assistantMetadata));
		snapshot.setInFlightToolCalls(new ArrayList<>(items));
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		save(snapshot);
	}

	@Override
	public void putPending(String runId, PendingToolCall pending) {
		pendingTable(runId).put(pending.getToolCallId(), pending);
	}

	@Override
	public List<PendingToolCall> pendings(String runId) {
		List<PendingToolCall> out = new ArrayList<>();
		for (PendingToolCall item : pendingTable(runId).values()) {
			applyRequestMarker(runId, item);
			applyDecisionMarker(runId, item);
			out.add(item);
		}
		return out;
	}

	@Override
	public List<PendingToolCall> pendingsLenient(String runId) {
		// 进程内替身没有"guard 读失败"这一态；非抛读口与强一致读口同语义。
		return pendings(runId);
	}

	// ── session → current 轮索引（T2b-D#1 的进程内替身，语义与真实实现一致） ──

	@Override
	public void markSessionCurrent(String sessionId, String runId) {
		if (sessionId != null && runId != null) {
			this.sessionCurrent.put(sessionId, runId);
		}
	}

	@Override
	public void renewSessionCurrent(String sessionId, String runId) {
		// 无 TTL 可续；值匹配校验保留（语义对齐：不属于自己的轮不动作）
		if (runId != null && runId.equals(this.sessionCurrent.get(sessionId))) {
			this.sessionCurrent.put(sessionId, runId);
		}
	}

	@Override
	public void clearSessionCurrent(String sessionId, String runId) {
		if (runId != null && runId.equals(this.sessionCurrent.get(sessionId))) {
			this.sessionCurrent.remove(sessionId, runId);
		}
	}

	@Override
	public String sessionCurrentRunId(String sessionId) {
		return sessionId == null ? null : this.sessionCurrent.get(sessionId);
	}

	/**
	 * I2①：与生产同口径的读侧合并（只并"可重建"的三种结论；grant-only）。
	 * 本替身默认返回同一对象引用，故合并会"就地"改到表里那份 —— 与真实实现（每次反序列化新副本）
	 * 的差异只体现在"是否持久化"，测试断言的是"读侧看得见结论"，两侧一致。
	 */
	private void applyDecisionMarker(String runId, PendingToolCall pending) {
		String guard = this.decided.getOrDefault(runId, java.util.Map.of()).get(pending.getToolCallId());
		if (guard == null || pending == null || !PendingToolCall.PENDING.equals(pending.getStatus())) {
			return;
		}
		int sep = guard.indexOf('|');
		String status = sep < 0 ? guard : guard.substring(0, sep);
		if (!RunStore.mergeableDecisionStatus(status)) {
			return;
		}
		pending.setStatus(status);
		pending.setExecuted(false);
		if (PendingToolCall.REJECTED.equals(status)) {
			pending.setOutcome(com.example.configmgr.ai.tool.ToolOutcome.REJECTED.wire());
		}
		else if (PendingToolCall.FRONTEND_CANCELLED.equals(status)) {
			pending.setOutcome(com.example.configmgr.ai.tool.ToolOutcome.CANCELLED.wire());
			pending.setCode(com.example.configmgr.ai.tool.ToolOutcome.CODE_FRONTEND_CANCELLED);
		}
		else if (PendingToolCall.CANCELLED.equals(status)) {
			pending.setOutcome(com.example.configmgr.ai.tool.ToolOutcome.CANCELLED.wire());
			pending.setCode(com.example.configmgr.ai.tool.ToolOutcome.CODE_RUN_CANCELLED);
		}
	}

	@Override
	public PendingToolCall pendingRaw(String runId, String toolCallId) {
		PendingToolCall stored = pendingTable(runId).get(toolCallId);
		if (stored == null || !this.deepCopyPending) {
			return stored;
		}
		try {
			return JSON.readValue(JSON.writeValueAsString(stored), PendingToolCall.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("待决条目往返序列化失败：" + toolCallId, ex);
		}
	}

	@Override
	public PendingToolCall pending(String runId, String toolCallId) {
		PendingToolCall stored = pendingTable(runId).get(toolCallId);
		if (stored == null) {
			return null;
		}
		PendingToolCall view = stored;
		if (this.deepCopyPending) {
			try {
				view = JSON.readValue(JSON.writeValueAsString(stored), PendingToolCall.class);
			}
			catch (Exception ex) {
				throw new IllegalStateException("待决条目往返序列化失败：" + toolCallId, ex);
			}
		}
		applyRequestMarker(runId, view);
		applyDecisionMarker(runId, view);
		return view;
	}

	@Override
	public PendingToolCall pendingLenient(String runId, String toolCallId) {
		return pending(runId, toolCallId);
	}

	/** B23L1 批②a：req 位来自独立表（与 {@code ai:req:} 同口径：只授予、不清除）。 */
	private void applyRequestMarker(String runId, PendingToolCall pending) {
		String marker = reqTable(runId).get(pending.getToolCallId());
		if (marker == null) {
			return;
		}
		pending.setReq(Boolean.TRUE);
		try {
			pending.setReqAtMs(Long.parseLong(marker));
		}
		catch (NumberFormatException ex) {
			// 坏值：位成立、时刻缺席（与真实实现同口径）
		}
	}

	// ── 认领与台账 ──────────────────────────────────────────────────────────

	@Override
	public boolean claimExecution(String runId, String toolCallId, String executedBy) {
		// B23L1 批③a：布尔口保持（既有用例零改动），三态走 tryClaimExecution；
		// LOST 与 STALE_FENCE 在布尔面上同为 false（"不得执行"这一点相同）。
		return tryClaimExecution(runId, toolCallId, executedBy).won();
	}

	@Override
	public ClaimResult tryClaimExecution(String runId, String toolCallId, String executedBy) {
		// 续租 fencing 收口批：丢租/设施不可确认 ⇒ 不得执行新工具（与真实 RunStore 同口径）。
		com.example.configmgr.ai.run.LeaseService.LeaseAuthority authority = runLeaseAuthority(runId);
		if (authority.readOnly()) {
			return new ClaimResult(ClaimOutcome.STALE_FENCE, heldRunEpoch(runId), 0L);
		}
		// B23L1 批②a/③a：值与真实实现同形（<epoch>|<instanceId>），claimedBy 剥前缀；
		// 持租时先对 fence 复核（与真实实现同口径：fence > epoch ⇒ STALE_FENCE，
		// 无租约 epoch == 0 不看 fence —— E3 红线）。
		long epoch = heldRunEpoch(runId);
		long fence = 0L;
		if (epoch > 0L) {
			fence = fenceEpochs.getOrDefault(runId, 0L);
			if (fence > epoch) {
				return new ClaimResult(ClaimOutcome.STALE_FENCE, epoch, fence);
			}
		}
		String previous = claimTable(runId).putIfAbsent(toolCallId, epoch + "|" + executedBy);
		if (previous != null) {
			return new ClaimResult(ClaimOutcome.LOST, epoch, fence);
		}
		PendingToolCall pending = pending(runId, toolCallId);
		if (pending != null) {
			pending.setClaimedAtMs(System.currentTimeMillis());
			pending.setClaimedEpoch(epoch);
			pending.setExecuted(true);
			pending.setExecutedBy(executedBy);
			putPending(runId, pending);
		}
		return new ClaimResult(ClaimOutcome.WON, epoch, fence);
	}

	@Override
	public String claimedBy(String runId, String toolCallId) {
		String value = claimTable(runId).get(toolCallId);
		return value == null ? null : stripEpochPrefix(value);
	}

	@Override
	public void appendLedger(String runId, PendingToolCall pending, String resultText) {
		if (runLeaseAuthority(runId).readOnly()) {
			return;
		}
		String idemField = idemField(runId, pending.getToolCallId());
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("atMs", System.currentTimeMillis());
		record.put("executedBy", pending.getExecutedBy() == null ? instanceId() : pending.getExecutedBy());
		record.put("toolCallId", pending.getToolCallId());
		record.put("name", pending.getName());
		record.put("kind", pending.getKind());
		record.put("outcome", pending.effectiveOutcome().wire());
		record.put("code", pending.effectiveCode());
		// 与真实 RunStore#appendLedger 同构（F5 批准绑定：预检指纹随执行台账留档）
		record.put("preflightHash", pending.getPreflight() == null ? null
				: pending.getPreflight().get("reportHash"));
		record.put("epoch", pending.getClaimedEpoch() > 0L ? pending.getClaimedEpoch() : heldRunEpoch(runId));
		// B23L1 批③a：幂等索引 field（additive），与真实实现同形
		record.put("idemKey", idemField);
		record.put("resultText", resultText);
		List<Map<String, Object>> records = ledgerList(runId);
		synchronized (records) {
			int index = records.size();
			records.add(record);
			// HSETNX 语义：首次执行即建索引，重放不得改写"第一次是哪条记录"
			idemTable(runId).putIfAbsent(pending.getToolCallId(), index);
		}
	}

	@Override
	public Map<String, Object> idemFor(String runId, String toolCallId) {
		if (toolCallId == null) {
			return null;
		}
		Integer index = idemTable(runId).get(toolCallId);
		if (index != null) {
			List<Map<String, Object>> records = ledgerList(runId);
			if (index >= 0 && index < records.size()) {
				return records.get(index);
			}
		}
		// 未命中/取不回 ⇒ 回落扫描（与真实实现同口径：索引是加速面，台账才是判据）
		return ledgerFor(runId, toolCallId);
	}

	@Override
	public List<Map<String, Object>> ledger(String runId) {
		return new ArrayList<>(ledgerList(runId));
	}

	@Override
	public Map<String, Object> ledgerFor(String runId, String toolCallId) {
		if (toolCallId == null) {
			return null;
		}
		for (Map<String, Object> record : ledgerList(runId)) {
			if (toolCallId.equals(record.get("toolCallId"))) {
				return record;
			}
		}
		return null;
	}

	@Override
	public Map<String, Integer> ledgerCounts(String runId) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Map<String, Object> record : ledgerList(runId)) {
			out.merge(String.valueOf(record.get("name")), 1, Integer::sum);
		}
		return out;
	}

	@Override
	public int ledgerCountSince(String runId, long sinceMs) {
		int count = 0;
		for (Map<String, Object> record : ledgerList(runId)) {
			Object atMs = record.get("atMs");
			if (atMs instanceof Number number && number.longValue() >= sinceMs) {
				count++;
			}
		}
		return count;
	}

	@Override
	public int cancelPendings(String runId) {
		int changed = 0;
		for (PendingToolCall pending : pendings(runId)) {
			// 与生产同判据（R1）：PENDING，或 APPROVED 且可证明未认领未执行。
			if (!cancellableBeforeExecution(runId, pending)) {
				continue;
			}
			pending.setStatus(PendingToolCall.CANCELLED);
			pending.setReason("RUN_CANCELLED");
			pending.setExecuted(false);
			pending.setResolvedAtMs(System.currentTimeMillis());
			pending.setResultText("本轮对话已被用户取消，该工具未执行。");
			putPending(runId, pending);
			changed++;
		}
		return changed;
	}

	// ── 帧归档（心跳不落档 + 末帧取号 + 发放序号登记，与真实实现同语义） ──

	@Override
	public void appendEvent(String runId, Map<String, Object> frame) {
		String type = String.valueOf(frame.get("type"));
		if (HEARTBEAT_TYPE.equals(type)) {
			// T7：心跳只留"进程还活着"的戳，不是业务活动（R1-09）。
			touchActivity(runId);
			return;
		}
		Map<String, Object> copy = new LinkedHashMap<>(frame);
		// T4-3 后半：atMs 主赋值在 SseChatEmitter#frame（帧构造时刻），此处 putIfAbsent 仅为
		// 与真实 RunStore#appendEvent 的兜底语义保持镜像（直塞帧才走这里）。
		copy.putIfAbsent("atMs", System.currentTimeMillis());
		// R1-09：与真实实现同口径 —— 任何非心跳帧都推进业务活动锚点。
		Object atMs = copy.get("atMs");
		touchBusinessActivity(runId, atMs instanceof Number number ? number.longValue()
				: System.currentTimeMillis());
		eventList(runId).add(copy);
	}

	@Override
	public void touchActivity(String runId) {
		this.beats.put(runId, System.currentTimeMillis());
	}

	@Override
	public void touchBusinessActivity(String runId, long atMs) {
		this.businessActivity.merge(runId, atMs > 0L ? atMs : System.currentTimeMillis(), Math::max);
	}

	@Override
	public long lastActivityAtMs(String runId) {
		long last = this.businessActivity.getOrDefault(runId, 0L);
		for (Map<String, Object> frame : eventList(runId)) {
			if (HEARTBEAT_TYPE.equals(String.valueOf(frame.get("type")))) {
				continue;
			}
			Object atMs = frame.get("atMs");
			if (atMs instanceof Number number) {
				last = Math.max(last, number.longValue());
			}
		}
		return last;
	}

	@Override
	public long lastEventAtMs(String runId) {
		return Math.max(lastActivityAtMs(runId), lastBeatAtMs(runId));
	}

	@Override
	public long lastBeatAtMs(String runId) {
		return this.beats.getOrDefault(runId, 0L);
	}

	@Override
	public long lastEventSeq(String runId) {
		List<Map<String, Object>> archived = eventList(runId);
		if (archived.isEmpty()) {
			return 0L;
		}
		Object seq = archived.get(archived.size() - 1).get("seq");
		return seq instanceof Number number ? number.longValue() : 0L;
	}

	@Override
	public List<Map<String, Object>> events(String runId) {
		return new ArrayList<>(eventList(runId));
	}

	@Override
	public long lastIssuedSeq(String runId) {
		return this.issuedSeqs.getOrDefault(runId, 0L);
	}

	@Override
	public void recordIssuedSeq(String runId, long seq) {
		if (runLeaseAuthority(runId).readOnly()) {
			return;
		}
		this.issuedSeqs.merge(runId, seq, Math::max);
	}

	@Override
	public void ensureIssuedSeqAtLeast(String runId, long floor) {
		if (runId == null || floor <= 0L) {
			return;
		}
		if (runLeaseAuthority(runId).readOnly()) {
			return;
		}
		this.issuedSeqs.merge(runId, floor, Math::max);
	}

	// ── 取证辅助（只给用例用，不属于 RunStore 契约） ─────────────────────────

	/** 归档帧的 seq（心跳不占业务号也不入档；只有落档失败才在归档里留洞）。 */
	public List<Long> archivedSeqs(String runId) {
		List<Long> out = new ArrayList<>();
		for (Map<String, Object> frame : eventList(runId)) {
			Object seq = frame.get("seq");
			if (seq instanceof Number number) {
				out.add(number.longValue());
			}
		}
		return out;
	}

	/** 归档帧的类型序列。 */
	public List<String> archivedTypes(String runId) {
		List<String> out = new ArrayList<>();
		for (Map<String, Object> frame : eventList(runId)) {
			out.add(String.valueOf(frame.get("type")));
		}
		return out;
	}

	/** 直接铺一段归档（重挂/续号用例的语料）。 */
	public void seedArchive(String runId, Map<String, Object>... frames) {
		for (Map<String, Object> frame : frames) {
			eventList(runId).add(new LinkedHashMap<>(frame));
		}
	}

	/** 直接铺一条待决条目（跨进程/续跑语料的骨架）。 */
	public void seedPending(String runId, PendingToolCall pending) {
		putPending(runId, pending);
	}

	/** 直接铺一条台账记录（跨进程"已执行过"的判据）。 */
	public void seedLedger(String runId, String toolCallId, String name, String resultText) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName(name);
		appendLedger(runId, pending, resultText);
	}

	/** 铺一个轮次快照（运行中）。 */
	public RunSnapshot seedRun(String runId, String sessionId) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(sessionId);
		snapshot.setStatus(RunSnapshot.RUNNING);
		save(snapshot);
		return snapshot;
	}

	// ── 内部表 ──────────────────────────────────────────────────────────────

	/**
	 * 安全台账（{@code ai:safety:<sessionId>}）的进程内替身（AI 表格工具治理批）。
	 *
	 * <p>
	 * 父类实现走 Redis（测试里 {@code redis} 为 null ⇒ 被自身 try/catch 吞掉、只剩一条 WARN），
	 * 于是「采样事实真的落进安全台账了吗」在无 Redis 的用例里**无法断言**。这里按真实语义复刻：
	 * 新→旧、截断到 {@link RunStore#SAFETY_LEDGER_MAX} 条。只增测试侧保真度，不改生产语义。
	 */
	private final Map<String, List<Map<String, Object>>> safety = new ConcurrentHashMap<>();

	@Override
	public void appendSafetyEvent(String sessionId, Map<String, Object> event) {
		if (sessionId == null || sessionId.isBlank() || event == null) {
			return;
		}
		List<Map<String, Object>> list = this.safety.computeIfAbsent(sessionId,
				key -> new CopyOnWriteArrayList<>());
		list.add(0, new LinkedHashMap<>(event));
		while (list.size() > RunStore.SAFETY_LEDGER_MAX) {
			list.remove(list.size() - 1);
		}
	}

	@Override
	public List<Map<String, Object>> safetyLedger(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			return List.of();
		}
		return List.copyOf(this.safety.getOrDefault(sessionId, List.of()));
	}

	private Map<String, PendingToolCall> pendingTable(String runId) {
		return this.pendings.computeIfAbsent(runId, key -> new ConcurrentHashMap<>());
	}

	private Map<String, String> claimTable(String runId) {
		return this.claims.computeIfAbsent(runId, key -> new ConcurrentHashMap<>());
	}

	/**
	 * <b>只写认领表、不回写条目</b>（测试缝）：复现跨实例认领的中间窗口 —— 对方
	 * {@code tryClaimExecution} 的 {@code HSETNX} 已落，而 {@code ai:pending} 的
	 * {@code executed/claimedAt} 还没写回。取消收尾必须据此**不**把条目伪造成"未执行"。
	 */
	public void seedClaimOnly(String runId, String toolCallId, String claimValue) {
		claimTable(runId).put(toolCallId, claimValue);
	}

	private Map<String, String> reqTable(String runId) {
		return this.reqMarks.computeIfAbsent(runId, key -> new ConcurrentHashMap<>());
	}

	private Map<String, String> decidedTable(String runId) {
		return this.decided.computeIfAbsent(runId, key -> new ConcurrentHashMap<>());
	}

	private List<Map<String, Object>> ledgerList(String runId) {
		return this.ledger.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>());
	}

	/** B23L1 批③a：{@code ai:idem:<runId>} 的进程内表（toolCallId → 台账下标）。 */
	private Map<String, Integer> idemTable(String runId) {
		return this.idemIndex.computeIfAbsent(runId, key -> new ConcurrentHashMap<>());
	}

	private List<Map<String, Object>> eventList(String runId) {
		return this.events.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>());
	}

}
