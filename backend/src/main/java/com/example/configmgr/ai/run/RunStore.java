package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 挂起态与台账的 Redis 外置存储（S4.2 §2 {@code run/RunStore}，移植自 SP-02 同名实现）。
 *
 * <h2>键结构（run 级键<b>全部带滑动 TTL</b> = {@code app.ai.session-ttl}，默认 24h，前缀 {@code ai:}）</h2>
 * <p>
 * <b>无绝对上限</b>（用户裁决 2026-10-20 第 4 条）：每次写键/心跳都把该键重新养满一个 TTL，
 * 挂起等待期间的心跳（2s 一拍）照常续期 —— 会话活着，卡的后台数据就活着。原 T3-8 的
 * "创建时刻 + session-ttl 绝对封顶"已退役。
 * <ul>
 * <li>{@code ai:run:<runId>} STRING（JSON）—— {@link RunSnapshot}：完整请求历史 +
 * 挂起中的 {@code assistant(tool_calls)} + 状态 + 上下文；</li>
 * <li>{@code ai:runs} SET —— runId 索引（重启后"发现待续跑轮次"的入口）；</li>
 * <li>{@code ai:pending:<runId>} HASH（field=toolCallId）—— {@link PendingToolCall}
 * （确认门/前端工具的输入落点，重启后仍可读）；</li>
 * <li>{@code ai:claim:<runId>} HASH（field=toolCallId，value=实例标识）——
 * <b>执行认领</b>：SP-02 §6 遗留的"executed 标记在执行返回后才写 ⇒ 最坏情况重复执行"窗口，
 * 本实现把标记<b>前置</b>为一次原子认领（{@code HSETNX} 语义），执行动作只能由认领成功的实例发起；</li>
 * <li>{@code ai:ledger:<runId>} LIST（JSON）—— 工具执行台账：每次<b>真实执行完成</b>一条，
 * 含 {@code executedBy} 实例标识，是跨进程"不重放"的判据；</li>
 * <li>{@code ai:events:<runId>} LIST（JSON）—— SSE 帧外置副本（崩溃前的帧不随进程消失，
 * 供 {@code GET /api/ai/events/{runId}} 重挂时回放）；每帧带单调 {@code seq}（T6 的差量补发判据），
 * <b>心跳帧不入本归档</b>（T7，改记 {@code ai:beat:<runId>}）；</li>
 * <li>{@code ai:beat:<runId>} STRING —— 心跳活动戳（毫秒时刻，TTL 随心跳续期）：
 * 心跳不占归档窗口，但"这一轮最近有过活动"仍可跨进程判定（僵尸判据的输入之一）；</li>
 * <li>{@code ai:seq:<runId>} STRING —— <b>已发放的最大业务 seq</b>（T3-2）：每个业务帧发放
 * 序号时 {@code SET} 一次并随帧刷新 TTL=session-ttl（与归档同寿）。delta 帧跳过归档后
 * "归档末帧"不再是可靠的续号锚点，跨进程续号改由本键接管（候选 = max(归档末帧, ai:seq) + 1）；</li>
 * <li>{@code ai:session:current:<sessionId>} STRING（value=runId，T2b-D#1）——
 * session → 当前挂起轮的索引：挂起写入、轮终态清除、TTL 对齐 session-ttl（与会话/快照同寿）；
 * 仅供 {@code GET /api/ai/runs/current} 重建挂起态，<b>不承诺同 session 多页签共享</b>。</li>
 * </ul>
 *
 * <h2>硬规范①（外置先于阻塞）</h2>
 * 调用方必须在进入挂起等待<b>之前</b>调用 {@link #saveSuspended} 把
 * {@code assistant(tool_calls)} 与待决条目落进 Redis；进程内的等待对象（
 * {@code gate/ConfirmGate} 的闸门）只承担"唤醒"职责，不承载状态。
 */
@Slf4j
@Component
public class RunStore {

	public static final String RUN_PREFIX = "ai:run:";

	public static final String RUN_INDEX = "ai:runs";

	public static final String PENDING_PREFIX = "ai:pending:";

	public static final String CLAIM_PREFIX = "ai:claim:";

	public static final String LEDGER_PREFIX = "ai:ledger:";

	public static final String EVENTS_PREFIX = "ai:events:";

	/** 心跳活动戳键前缀（T7：心跳不入归档，但活动证据必须留痕）。 */
	public static final String BEAT_PREFIX = "ai:beat:";

	/**
	 * session → 当前挂起轮的索引键前缀（T2b-D#1）：{@code ai:session:current:<sessionId>}
	 * STRING（value = runId）。挂起写入、轮终态清除、TTL 对齐 {@code app.ai.session-ttl}
	 * （与会话/快照同寿，进程死后索引不会先于挂起态消失），O(1) 读写。
	 */
	public static final String SESSION_CURRENT_PREFIX = "ai:session:current:";

	/** 心跳帧的类型名（T7：唯一不落归档的帧类型）。 */
	public static final String HEARTBEAT_TYPE = "heartbeat";

	/** delta 帧的类型名（T3-2：与心跳同待遇，跳过归档）。 */
	public static final String DELTA_TYPE = "delta";

	/** 已发放最大业务序号的键前缀（T3-2 的续号锚点，TTL 与归档同寿）。 */
	public static final String SEQ_PREFIX = "ai:seq:";

	/**
	 * 每轮最多留存的外置帧数（长回答的 delta 会挤掉挂起期帧，故与 SP-02 同取 3000）。
	 *
	 * <p>
	 * 公开口径（S3-1 修复批）：实时队列容量 &lt; 本窗口是 T3-1 的不变量，单测直接锚定本常量
	 * （值改坏即红），故不留私有可见性。
	 */
	public static final int EVENT_WINDOW = 3000;

	/** 本实例标识：区分"崩溃前的旧实例"与"续跑的新实例"。 */
	private final String instanceId;

	private final StringRedisTemplate redis;

	private final ObjectMapper mapper;

	private final AiProperties properties;

	public RunStore(StringRedisTemplate redis, ObjectMapper mapper, AiProperties properties) {
		this.redis = redis;
		this.mapper = mapper;
		this.properties = properties;
		this.instanceId = "instance-" + host() + "-pid" + ProcessHandle.current().pid() + "-"
				+ UUID.randomUUID().toString().substring(0, 6);
	}

	private static String host() {
		try {
			return InetAddress.getLocalHost().getHostName();
		}
		catch (Exception ex) {
			return "unknown";
		}
	}

	public String instanceId() {
		return this.instanceId;
	}

	public String runKey(String runId) {
		return RUN_PREFIX + runId;
	}

	public String pendingKey(String runId) {
		return PENDING_PREFIX + runId;
	}

	public String claimKey(String runId) {
		return CLAIM_PREFIX + runId;
	}

	public String ledgerKey(String runId) {
		return LEDGER_PREFIX + runId;
	}

	public String eventsKey(String runId) {
		return EVENTS_PREFIX + runId;
	}

	/** 已发放最大业务序号的键名（诊断/取证可直读）。 */
	public String seqKey(String runId) {
		return SEQ_PREFIX + runId;
	}

	// ── 轮次快照 ────────────────────────────────────────────────────────────

	public RunSnapshot create(String runId, String sessionId, AiContext context, List<Message> history,
			MessageJsonCodec codec) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(sessionId);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setContext(context == null ? AiContext.empty().toMap() : context.toMap());
		snapshot.setMessageJson(codec.serializeAll(history));
		snapshot.setCreatedBy(this.instanceId);
		snapshot.setCreatedAtMs(System.currentTimeMillis());
		save(snapshot);
		this.redis.opsForSet().add(RUN_INDEX, runId);
		return snapshot;
	}

	public void save(RunSnapshot snapshot) {
		snapshot.setUpdatedAtMs(System.currentTimeMillis());
		try {
			this.redis.opsForValue().set(runKey(snapshot.getRunId()), this.mapper.writeValueAsString(snapshot),
					ttlFor(snapshot.getRunId()));
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法写入轮次快照 " + snapshot.getRunId(), ex);
		}
		// T2b-D#1：快照持久化成功后联动 session → current 轮索引（全部走本方法这一唯一通道，
		// 不会漏分支）。RUNNING 不动 —— settle 期间轮仍是 current，索引必须保留。
		// T3-7（A14）：终态分支改调统一判定函数（不再散点比对字面量）。
		String status = snapshot.getStatus();
		if (RunSnapshot.SUSPENDED.equals(status)) {
			markSessionCurrent(snapshot.getSessionId(), snapshot.getRunId());
		}
		else if (RunSnapshot.isTerminal(status)) {
			clearSessionCurrent(snapshot.getSessionId(), snapshot.getRunId());
		}
	}

	// ── session → current 轮索引（T2b-D#1；体验优化，不是正确性依赖） ─────────

	/**
	 * 记录"该 session 当前挂起可重建的轮"：{@code SET ai:session:current:<sessionId> <runId>}，
	 * TTL 对齐 {@code app.ai.session-ttl}（默认 24h）—— 挂起期由 {@code ConfirmGate} 心跳续期
	 * （与快照/归档同寿）。<b>P2-3</b>：不再用会话锁的 10m —— 进程崩溃后索引与挂起态同寿，
	 * 重启后仍能定位到那一轮；自然过期（24h 无活动）后才由
	 * {@code GET /api/ai/runs/current} 返回明确空态（三分支语义之一）。
	 *
	 * <p>
	 * <b>约束（红队 B2）</b>：不承诺同 session 多页签共享 —— 索引只覆盖
	 * "挂起可重建"场景，且以"最后一次挂起"为准。
	 */
	public void markSessionCurrent(String sessionId, String runId) {
		if (sessionId == null || runId == null) {
			return;
		}
		try {
			this.redis.opsForValue().set(sessionCurrentKey(sessionId), runId, sessionCurrentTtl());
		}
		catch (Exception ex) {
			log.warn("写入 session→current 索引失败 sessionId={} runId={}：{}", sessionId, runId, ex.getMessage());
		}
	}

	/** 索引键名（诊断/取证可直读）。 */
	public String sessionCurrentKey(String sessionId) {
		return SESSION_CURRENT_PREFIX + sessionId;
	}

	/** 续期：仅当值仍是自己这一轮的 runId 才 {@code EXPIRE}（同会话锁 watchdog 语义）。 */
	public void renewSessionCurrent(String sessionId, String runId) {
		if (sessionId == null || runId == null) {
			return;
		}
		try {
			String value = this.redis.opsForValue().get(sessionCurrentKey(sessionId));
			if (runId.equals(value)) {
				this.redis.expire(sessionCurrentKey(sessionId), sessionCurrentTtl());
			}
		}
		catch (Exception ex) {
			log.debug("续期 session→current 索引失败 sessionId={} runId={}：{}", sessionId, runId, ex.getMessage());
		}
	}

	/** 清除：仅当值仍是自己这一轮的 runId 才 {@code DEL}（同 {@code SessionGate.release} 口径）。 */
	public void clearSessionCurrent(String sessionId, String runId) {
		if (sessionId == null || runId == null) {
			return;
		}
		try {
			String value = this.redis.opsForValue().get(sessionCurrentKey(sessionId));
			if (runId.equals(value)) {
				this.redis.delete(sessionCurrentKey(sessionId));
			}
		}
		catch (Exception ex) {
			log.debug("清除 session→current 索引失败 sessionId={} runId={}：{}", sessionId, runId, ex.getMessage());
		}
	}

	/** 该 session 当前挂起轮的 runId（null = 无索引：从未挂起 / 已终态清除 / TTL 过期）。 */
	public String sessionCurrentRunId(String sessionId) {
		if (sessionId == null) {
			return null;
		}
		try {
			return this.redis.opsForValue().get(sessionCurrentKey(sessionId));
		}
		catch (Exception ex) {
			log.debug("读取 session→current 索引失败 sessionId={}：{}", sessionId, ex.getMessage());
			return null;
		}
	}

	/**
	 * 索引 TTL = <b>会话 TTL</b>（{@code app.ai.session-ttl}，默认 24h）—— 与快照/归档/待决同寿。
	 *
	 * <p>红队 P2-3（收尾批）：取值原先复用了会话锁 watchdog 的 {@code app.ai.session.lock.ttl}
	 * （10m），但锁 TTL 只约束"一轮的互斥窗口"，不是"进程死后索引该活多久"。进程在挂起期崩溃后，
	 * 心跳随进程一起停摆；索引若只活 10m，就会比同轮的快照/归档（24h）先消失，
	 * 重启后的 {@code GET /api/ai/runs/current} 于是报"无当前轮"——正是"索引孤儿"。
	 * 现在取值与会话同寿，<b>续期逻辑不变</b>：确认门挂起等待循环每拍调用
	 * {@link #renewSessionCurrent(String, String)} 续期，轮落到终态由
	 * {@link #clearSessionCurrent(String, String)} 主动删除。
	 */
	private Duration sessionCurrentTtl() {
		return this.properties.getSessionTtl();
	}

	public RunSnapshot get(String runId) {
		String raw = this.redis.opsForValue().get(runKey(runId));
		if (raw == null) {
			return null;
		}
		try {
			return this.mapper.readValue(raw, RunSnapshot.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法读取轮次快照 " + runId, ex);
		}
	}

	/** 池饱和回滚：删掉本轮的全部键（不留半截状态）。 */
	public void deleteRun(String runId) {
		this.redis.delete(List.of(runKey(runId), pendingKey(runId), claimKey(runId), ledgerKey(runId),
				eventsKey(runId), seqKey(runId)));
		this.redis.opsForSet().remove(RUN_INDEX, runId);
	}

	/** 全部仍存活的 runId（索引里已过期的惰性剔除）。 */
	public List<String> allRunIds() {
		Set<String> ids = this.redis.opsForSet().members(RUN_INDEX);
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		List<String> live = new ArrayList<>();
		List<String> dead = new ArrayList<>();
		for (String id : ids) {
			if (Boolean.TRUE.equals(this.redis.hasKey(runKey(id)))) {
				live.add(id);
			}
			else {
				dead.add(id);
			}
		}
		if (!dead.isEmpty()) {
			this.redis.opsForSet().remove(RUN_INDEX, dead.toArray());
		}
		live.sort(String::compareTo);
		return live;
	}

	// ── 挂起（硬规范①的落点） ──────────────────────────────────────────────

	/**
	 * 硬规范①：把挂起中的 {@code assistant(tool_calls)} 与每个待决条目先写进 Redis，
	 * 然后调用方才能进入阻塞等待。写失败必须抛出去 —— 宁可不挂起（回落官方执行），
	 * 也不能出现"阻塞住了但 Redis 里没有状态"。
	 */
	public void saveSuspended(String runId, String assistantContent, List<PendingToolCall> pendings,
			List<Message> history, MessageJsonCodec codec) {
		for (PendingToolCall pending : pendings) {
			// 只给真正挂起的条目打"请求时刻"：预检失败等已结算条目（非 PENDING）在挂起前就写了
			// requestedAtMs/resolvedAtMs，这里再覆写会造出 resolvedAtMs < requestedAtMs 的时间倒挂。
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
		snapshot.setInFlightToolCalls(pendings);
		snapshot.setMessageJson(codec.serializeAll(history));
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		save(snapshot);
	}

	public void putPending(String runId, PendingToolCall pending) {
		try {
			this.redis.opsForHash().put(pendingKey(runId), pending.getToolCallId(),
					this.mapper.writeValueAsString(pending));
			this.redis.expire(pendingKey(runId), ttlFor(runId));
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法写入待决条目 " + pending.getToolCallId(), ex);
		}
	}

	public List<PendingToolCall> pendings(String runId) {
		Map<Object, Object> all = this.redis.opsForHash().entries(pendingKey(runId));
		List<PendingToolCall> out = new ArrayList<>();
		for (Object value : all.values()) {
			try {
				out.add(this.mapper.readValue(String.valueOf(value), PendingToolCall.class));
			}
			catch (Exception ex) {
				throw new IllegalStateException("无法读取待决条目：" + value, ex);
			}
		}
		return out;
	}

	public PendingToolCall pending(String runId, String toolCallId) {
		String raw = (String) this.redis.opsForHash().get(pendingKey(runId), toolCallId);
		if (raw == null) {
			return null;
		}
		try {
			return this.mapper.readValue(raw, PendingToolCall.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法读取待决条目 " + toolCallId, ex);
		}
	}

	/**
	 * L0（2026-10-21 裁决 A）：给待决条目置「请求帧已发」位（{@link PendingToolCall#getReq()}）。
	 *
	 * <p>时机 = {@code ConfirmGate#await} <b>发射请求帧之前</b>（先置位、后发帧）：反过来先发帧再置位，
	 * "帧已到、位未落"的窗口里刷新会拿不到这张卡；先置位最坏只是"位已落、帧紧跟着发出"，
	 * 刷新重建出的卡与随后到达的帧同形，无副作用。
	 *
	 * <p>写入是"读 → 判 → 写"：<b>只对仍 {@code PENDING} 的条目置位</b> —— 已决条目的位没有消费者
	 * （前端只为 PENDING 条目建卡），而把已决条目回写成未决会丢决策。同进程内的决策写入与本次置位
	 * 由 {@code ConfirmGate} 的提交锁串行化（见 {@code ConfirmGate#reserveRequestSlot}）。
	 *
	 * @param atMs 置位时刻（调用方单点取钟，便于与帧内时间对账）
	 * @return 置位后的条目；条目不存在返回 null
	 */
	public PendingToolCall markRequestEmitted(String runId, String toolCallId, long atMs) {
		PendingToolCall pending = pending(runId, toolCallId);
		if (pending == null || !PendingToolCall.PENDING.equals(pending.getStatus())) {
			return pending;
		}
		pending.setReq(Boolean.TRUE);
		pending.setReqAtMs(atMs);
		putPending(runId, pending);
		return pending;
	}

	// ── 执行认领 / 台账（跨进程不重放） ────────────────────────────────────

	/**
	 * 原子认领一次真实执行（SP-02 E2 语义的补强）。
	 *
	 * <p>
	 * {@code HSETNX} 语义：同一个 {@code toolCallId} 全集群只可能有一个实例认领成功，
	 * 且认领发生在<b>执行之前</b>。认领成功即把 {@code claimedAtMs / executed=true / executedBy}
	 * 写进待决条目 —— 于是"放行后进程死亡 → 新实例续跑"这一窗口里，新实例只会看到
	 * {@code APPROVED + executed=false}（此时可安全补执行），而"已在执行中"的状态
	 * 永远不会被第二个实例当作"未执行"再执行一次。
	 *
	 * <p>
	 * <b>N5（本棒）</b>：时间戳分列 —— {@code claimedAtMs} = 认领时刻（执行<b>前</b>），
	 * {@code executedAtMs} = 执行完成时刻（由调用方在执行返回后写），两者不再互相覆写。
	 *
	 * @return true = 本次由当前实例认领（可以执行）；false = 已被他实例认领（<b>不得</b>执行）
	 */
	public boolean claimExecution(String runId, String toolCallId, String executedBy) {
		Boolean claimed = this.redis.opsForHash().putIfAbsent(claimKey(runId), toolCallId, executedBy);
		this.redis.expire(claimKey(runId), ttlFor(runId));
		boolean won = Boolean.TRUE.equals(claimed);
		if (won) {
			PendingToolCall pending = pending(runId, toolCallId);
			if (pending != null) {
				pending.setClaimedAtMs(System.currentTimeMillis());
				pending.setExecuted(true);
				pending.setExecutedBy(executedBy);
				putPending(runId, pending);
			}
		}
		return won;
	}

	/** 认领者实例标识（null=未认领）。 */
	public String claimedBy(String runId, String toolCallId) {
		Object value = this.redis.opsForHash().get(claimKey(runId), toolCallId);
		return value == null ? null : String.valueOf(value);
	}

	public void appendLedger(String runId, PendingToolCall pending, String resultText) {
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("atMs", System.currentTimeMillis());
		record.put("at", Instant.now().toString());
		record.put("executedBy", pending.getExecutedBy() == null ? this.instanceId : pending.getExecutedBy());
		record.put("toolCallId", pending.getToolCallId());
		record.put("name", pending.getName());
		record.put("kind", pending.getKind());
		record.put("outcome", pending.effectiveOutcome().wire());
		record.put("code", pending.effectiveCode());
		// F5 批准绑定：批准时所见的那份预检指纹随执行台账留档（null = 未经预检/旧数据）
		record.put("preflightHash", pending.getPreflight() == null ? null
				: pending.getPreflight().get("reportHash"));
		record.put("resultText", resultText);
		try {
			this.redis.opsForList().rightPush(ledgerKey(runId), this.mapper.writeValueAsString(record));
			this.redis.expire(ledgerKey(runId), ttlFor(runId));
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法写入执行台账 " + pending.getToolCallId(), ex);
		}
	}

	public List<Map<String, Object>> ledger(String runId) {
		List<String> raw = this.redis.opsForList().range(ledgerKey(runId), 0, -1);
		List<Map<String, Object>> out = new ArrayList<>();
		if (raw == null) {
			return out;
		}
		for (String item : raw) {
			try {
				out.add(this.mapper.readValue(item, new TypeReference<Map<String, Object>>() {
				}));
			}
			catch (Exception ex) {
				throw new IllegalStateException("无法读取执行台账：" + item, ex);
			}
		}
		return out;
	}

	/** 该 toolCallId 是否已经真实执行完成（跨进程口径，判据 = 台账）。 */
	public Map<String, Object> ledgerFor(String runId, String toolCallId) {
		if (toolCallId == null) {
			return null;
		}
		for (Map<String, Object> record : ledger(runId)) {
			if (toolCallId.equals(record.get("toolCallId"))) {
				return record;
			}
		}
		return null;
	}

	/** 工具名 → 执行次数（"恰好一次"的对账口径）。 */
	public Map<String, Integer> ledgerCounts(String runId) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Map<String, Object> record : ledger(runId)) {
			out.merge(String.valueOf(record.get("name")), 1, Integer::sum);
		}
		return out;
	}

	/**
	 * 自 {@code sinceMs} 起新增的台账条数（韧性棒的重试副作用判定口径：<b>本次尝试</b>内是否有
	 * 真实执行完成）。用"执行完成时刻"（{@code atMs}）而非认领时刻，是因为"执行已完成"才是
	 * 不可撤销的副作用；仍在执行中的窗口由 {@link #claimedBy} 与
	 * {@code ToolActivityBeacon} 覆盖。
	 */
	public int ledgerCountSince(String runId, long sinceMs) {
		int count = 0;
		for (Map<String, Object> record : ledger(runId)) {
			Object atMs = record.get("atMs");
			if (atMs instanceof Number number && number.longValue() >= sinceMs) {
				count++;
			}
		}
		return count;
	}

	/**
	 * 取消落地：把该轮仍未决（{@code PENDING}）的挂起条目一次性标为
	 * {@link PendingToolCall#CANCELLED} 且 {@code executed=false}。
	 *
	 * <p>
	 * 为什么重要：取消之后若进程死亡、又被启动续跑拾起，未决条目仍会躺在 Redis 里 ——
	 * 若状态还是 {@code PENDING} 而续跑把它们当"待外部输入"，就会永久卡住（或更糟：
	 * 被误当"未执行的后端工具"补执行）。取消即落库，跨进程也生效。
	 *
	 * @return 被改写的条目数
	 */
	public int cancelPendings(String runId) {
		int changed = 0;
		for (PendingToolCall pending : pendings(runId)) {
			if (!PendingToolCall.PENDING.equals(pending.getStatus())) {
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

	// ── 帧外置（重挂回放） ──────────────────────────────────────────────────

	/**
	 * 帧落档。写入时给帧补一个 <b>atMs</b> 时间戳（帧里没有才补）——
	 * 它让"这一轮最近一次活动是什么时候"有了**可跨进程**的判据：
	 * 挂起心跳、工具帧全都经这里落档，于是 R3 的僵尸判据不必只看快照
	 * （快照只在工具落定/挂起时刷新，长流式轮次会显得"很久没动"）。
	 *
	 * <p>
	 * <b>T4-3 后半起 atMs 主赋值已前移到发射器帧构造点</b>（{@code SseChatEmitter#frame}）——
	 * 实时投递原文与归档副本因此同值；这里的 {@code putIfAbsent} 退化为<b>兜底</b>，
	 * 只对"绕过发射器直塞的帧"（旧格式帧、测试/工具直接落档）生效，其 {@code atMs} 仍会
	 * 比投递原文晚取（若真有直塞方，需自行按发射器口径补）。
	 *
	 * <p>
	 * <b>T7（DC-14 / R8-5）：心跳帧<b>不</b>入归档</b> —— 挂起期每 2s 一帧、流式期每 5s 一帧的
	 * 心跳会挤占 {@value #EVENT_WINDOW} 的归档窗口，把真正有信息量的状态帧挤出窗口
	 * （回放跟着变瘦）。心跳的"在线推送"语义不受影响（仍在 {@code SseChatEmitter#emit} 里
	 * 广播给订阅者），只是改由 {@link #touchActivity(String)} 记一个轻量活动戳 ——
	 * 于是"心跳提供活动证据"这一用途照旧，归档却不被它占位。
	 *
	 * <p>
	 * <b>B1（2026-10-20）：{@code delta} 改为入档</b> —— 挂起即断（流派 B）之后，用户决策 →
	 * 后端续跑这段窗口内没有实时订阅者，正文帧只能靠归档差量补回（前端带 {@code ?lastSeq}
	 * 重连）。代价：长轮次的 delta 会加速挤出 {@value #EVENT_WINDOW} 窗口内的早段帧
	 * （极端长生成的重连补帧可能出现尾部缺口，已在设计稿 §6 留痕，B2 换 Redis Stream 时重估）。
	 * delta 照旧 {@link #touchActivity(String)} 留活动戳（"只吐 delta 的长轮"僵尸判据不变）。
	 */
	public void appendEvent(String runId, Map<String, Object> frame) {
		String type = String.valueOf(frame.get("type"));
		if (HEARTBEAT_TYPE.equals(type)) {
			// T7：心跳跳过归档，只留活动戳。
			touchActivity(runId);
			return;
		}
		if (DELTA_TYPE.equals(type)) {
			// B1：delta 入档（差量补帧的真值），活动戳照旧 —— 窗口/TTL 口径不变。
			touchActivity(runId);
		}
		frame.putIfAbsent("atMs", System.currentTimeMillis());
		try {
			this.redis.opsForList().rightPush(eventsKey(runId), this.mapper.writeValueAsString(frame));
			this.redis.opsForList().trim(eventsKey(runId), -EVENT_WINDOW, -1);
			this.redis.expire(eventsKey(runId), ttlFor(runId));
		}
		catch (Exception ex) {
			// 帧留档失败不影响本轮运行（外置是"更耐久"，不是"更正确"的前提）
			log.debug("外置 SSE 帧失败 runId={} type={}: {}", runId, frame.get("type"), ex.getMessage());
		}
	}

	// ── 已发放序号的外置（T3-2：ai:seq 键） ─────────────────────────────────

	/**
	 * 已发放的最大业务 seq（{@code ai:seq:<runId>}；无键/坏值返回 0）。
	 *
	 * <p>
	 * 为什么是独立键：T3-2 起 delta 帧跳过归档，"归档末帧"可能<b>落后</b>于实际发放过的
	 * 最大号（长流式轮次几乎必然如此）。跨进程续号的候选因此必须取
	 * {@code max(归档末帧, ai:seq)}，本键就是后者的读取口。
	 */
	public long lastIssuedSeq(String runId) {
		try {
			String raw = this.redis.opsForValue().get(seqKey(runId));
			return raw == null ? 0L : Long.parseLong(raw);
		}
		catch (Exception ex) {
			log.debug("读取已发放序号失败 runId={}：{}", runId, ex.getMessage());
			return 0L;
		}
	}

	/**
	 * 发放即登记（T3-2 的 {@code SET} 口径）：每个业务帧取号后立刻把号写进
	 * {@code ai:seq:<runId>} 并刷新 TTL=session-ttl（与 {@code ai:events:<runId>} 同刻过期）。
	 *
	 * <p>
	 * <b>SET 失败口径（S2-4）</b>：仅 WARN + 继续出帧（韧性优先）——本轮的序号发放已由
	 * 进程内计数器推进，单帧登记失败不阻塞流；承诺收窄为"归档部分失败场景"，整体 Redis
	 * 宕机下重号风险登记为观察项。
	 */
	public void recordIssuedSeq(String runId, long seq) {
		try {
			this.redis.opsForValue().set(seqKey(runId), String.valueOf(seq), ttlFor(runId));
		}
		catch (Exception ex) {
			log.warn("登记已发放序号失败 runId={} seq={}（继续出帧，跨进程续号可能回退）：{}", runId, seq,
					ex.getMessage());
		}
	}

	/**
	 * 心跳的活动戳（T7 的配套）：{@code ai:beat:<runId>} = 毫秒时刻，TTL 随心跳续期。
	 *
	 * <p>
	 * 为什么不干脆"心跳什么都不写"：僵尸判据（{@code ResumeService#activeAtMs}）靠"最近一次活动"
	 * 区分"活着的长轮"与"上一进程死掉的僵尸轮"。心跳不入归档后，<b>只吐思考不吐正文</b>的
	 * 长轮会长时间没有归档帧 —— 若不留下任何活动证据，它会被误判成僵尸并被强行接管
	 * （同一轮被执行两次）。一个 STRING 键就把这条证据链补回来了，代价远低于归档一帧。
	 */
	public void touchActivity(String runId) {
		try {
			this.redis.opsForValue().set(beatKey(runId), String.valueOf(System.currentTimeMillis()), ttlFor(runId));
		}
		catch (Exception ex) {
			log.debug("心跳活动戳写入失败 runId={}：{}", runId, ex.getMessage());
		}
	}

	/** 心跳活动戳键名（诊断/取证可直读）。 */
	public String beatKey(String runId) {
		return BEAT_PREFIX + runId;
	}

	/**
	 * 挂起期心跳对<b>卡的后台数据</b>的滑动续期（用户裁决 2026-10-20 第 4 条）。
	 *
	 * <p>确认门 / 表单卡的等待没有上限，而这张卡的可重建性依赖四类键：轮快照
	 * （{@code ai:run}，含挂起中的 assistant(tool_calls) 与历史）、待决条目
	 * （{@code ai:pending}，人的决策落点）、帧归档（{@code ai:events}，重挂回放）、
	 * 已发放序号（{@code ai:seq}，差量补发锚点）。若只续心跳键，一次超过 session-ttl 的长等
	 * 会让"卡还在、后台已过期" —— 人点批准时反而找不到挂起态。
	 * 故 {@code ConfirmGate} 的 2s 心跳每拍把这些键（存在才生效）整额续期：
	 * <b>会话活着，卡的后台数据就活着</b>；不计入预算的等待也因此可以放心地长。
	 *
	 * <p>Redis {@code EXPIRE} 对不存在的键是无操作（不报错），故本方法对"尚未写出"的键安全。
	 */
	public void renewSuspensionTtl(String runId) {
		if (runId == null) {
			return;
		}
		Duration ttl = ttlFor(runId);
		try {
			this.redis.expire(runKey(runId), ttl);
			this.redis.expire(pendingKey(runId), ttl);
			this.redis.expire(eventsKey(runId), ttl);
			this.redis.expire(seqKey(runId), ttl);
		}
		catch (Exception ex) {
			log.debug("挂起期续期轮键失败 runId={}：{}", runId, ex.getMessage());
		}
	}

	/**
	 * 该轮最近一次活动时刻（毫秒）：取 {@code ai:events:<runId>} 最后一帧的 {@code atMs}
	 * 与心跳活动戳（{@link #touchActivity}）的<b>较新者</b>。
	 *
	 * <p>
	 * 只读最后一帧（{@code LRANGE key -1 -1}）与一个 STRING，不整表回放 —— 这个方法会在
	 * "每轮的可续跑性判定"里被调用。两者都没有（或旧格式帧没有 atMs）返回 0，
	 * 调用方据此回落到快照的 {@code updatedAtMs}。
	 */
	public long lastEventAtMs(String runId) {
		long last = 0L;
		try {
			List<String> tail = this.redis.opsForList().range(eventsKey(runId), -1, -1);
			if (tail != null && !tail.isEmpty()) {
				JsonNode node = this.mapper.readTree(tail.get(0));
				JsonNode atMs = node.get("atMs");
				last = atMs == null ? 0L : atMs.asLong();
			}
		}
		catch (Exception ex) {
			log.debug("读取最近活动时刻失败 runId={}：{}", runId, ex.getMessage());
		}
		return Math.max(last, lastBeatAtMs(runId));
	}

	/** 心跳活动戳的时刻（无则 0）。 */
	public long lastBeatAtMs(String runId) {
		try {
			String raw = this.redis.opsForValue().get(beatKey(runId));
			return raw == null ? 0L : Long.parseLong(raw);
		}
		catch (Exception ex) {
			return 0L;
		}
	}

	/**
	 * 该轮已落档帧的最大序号（T6）：序号单调，故只读最后一帧；
	 * 无帧（全新轮次/旧格式帧）返回 0，写出器据此从 1 起号。
	 */
	public long lastEventSeq(String runId) {
		try {
			List<String> tail = this.redis.opsForList().range(eventsKey(runId), -1, -1);
			if (tail == null || tail.isEmpty()) {
				return 0L;
			}
			JsonNode node = this.mapper.readTree(tail.get(0));
			JsonNode seq = node.get("seq");
			return seq == null ? 0L : seq.asLong();
		}
		catch (Exception ex) {
			log.debug("读取末帧序号失败 runId={}：{}", runId, ex.getMessage());
			return 0L;
		}
	}

	public List<Map<String, Object>> events(String runId) {
		List<String> raw = this.redis.opsForList().range(eventsKey(runId), 0, -1);
		List<Map<String, Object>> out = new ArrayList<>();
		if (raw == null) {
			return out;
		}
		for (String item : raw) {
			try {
				out.add(this.mapper.readValue(item, new TypeReference<Map<String, Object>>() {
				}));
			}
			catch (Exception ignored) {
				// 单帧坏损不阻断回放
			}
		}
		return out;
	}

	// ── TTL（滑动续期：活跃即续，无绝对上限） ─────────────────────────────────────

	/**
	 * 本次写入/续期应使用的 TTL：<b>整额 {@code app.ai.session-ttl}（默认 24h）</b>。
	 *
	 * <p>滑动续期（用户裁决 2026-10-20 第 4 条）：每一次写键（业务帧、心跳、台账、待决条目、
	 * 序号、轮快照）都把该键重新养满一个 TTL —— 挂起等待期间 2s 的心跳照常续期 run 键，
	 * 于是"会话活着，卡的后台数据就活着"。
	 * <b>不再有"创建时刻 + session-ttl"的绝对封顶</b>：那张 240s 的确认卡可以一直挂着，
	 * 它的外置态不会被 6h 的创建时刻封顶掐断。整条会话的寿命由会话记忆键自己的滑动 TTL 承载。
	 */
	private Duration ttlFor(String runId) {
		return this.properties.getSessionTtl();
	}

}
