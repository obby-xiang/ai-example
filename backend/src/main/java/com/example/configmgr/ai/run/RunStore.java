package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * <li>{@code ai:claim:<runId>} HASH（field=toolCallId，value=<b>{@code <epoch>|<instanceId>}</b>）——
 * <b>执行认领</b>：SP-02 §6 遗留的"executed 标记在执行返回后才写 ⇒ 最坏情况重复执行"窗口，
 * 本实现把标记<b>前置</b>为一次原子认领（{@code HSETNX} 语义），执行动作只能由认领成功的实例发起；
 * B23L1 批②a 起值前置了租约 epoch（认领能与续跑权对账），读口 {@link #claimedBy} 剥前缀，
 * 对外返回值形态不变；</li>
 * <li>{@code ai:req:<runId>} HASH（field=toolCallId，value=置位时刻毫秒）—— <b>请求帧已发</b>位
 * （L0 的 {@code req}）：{@code HSETNX} 首写者胜，<b>不再回写条目本身</b>（回写会把并发落库的
 * 决策覆盖回 PENDING，见 M-2）；读侧 {@link #pending}/{@link #pendings} 读出后合并该位；</li>
 * <li>{@code ai:decided:<runId>} HASH（field=toolCallId，value={@code <status>|<atMs>|<instanceId>}）——
 * <b>决策 guard / WAL</b>（B23L1 批②a 建设施，ConfirmGate 接入归批②b）：{@code HSETNX} 保证
 * 同一条目全集群只有一次决策能落库；条目未写而 guard 已写时，读侧可据它收敛出结论；</li>
 * <li>{@code ai:lease:run:<runId>} STRING（value={@code <epoch>|<instanceId>}，TTL 30s）——
 * <b>续跑权租约</b>（B23L1 批②a；{@link LeaseService}）：持租者才是本轮的续跑主人，
 * 续租失败即被别的实例接管；</li>
 * <li>{@code ai:fence:run:<runId>} STRING —— <b>fence</b>：已落库的最大 epoch。
 * {@link #save} 在持租时走 Lua fence 写（{@code fence ≤ epoch} 才写，并把 fence 推到该 epoch）；
 * TTL 随快照。无租约（降级/单实例）时保持 plain {@code SET}（不拒写）；</li>
 * <li>{@code ai:ledger:<runId>} LIST（JSON）—— 工具执行台账：每次<b>真实执行完成</b>一条，
 * 含 {@code executedBy} 实例标识，是跨进程"不重放"的判据；</li>
 * <li>{@code ai:events:<runId>} LIST（JSON）—— SSE 帧外置副本（崩溃前的帧不随进程消失，
 * 供 {@code GET /api/ai/events/{runId}} 重挂时回放）；每帧带单调 {@code seq}（T6 的差量补发判据），
 * <b>心跳帧不入本归档</b>（T7，改记 {@code ai:beat:<runId>}）。
 * <b>B23L1 批① 起本键进只读兼容期</b>：新写只进 {@code ai:xevents:<runId>} Stream（{@link RedisFrameStore}），
 * 本类只保留"读侧兼容"与"键的删除/TTL 续期"；</li>
 * <li>{@code ai:xevents:<runId>} Stream —— <b>事件日志真值</b>（B23L1 批①）：{@code XADD ... MAXLEN ~ 6000}，
 * 字段 {@code seq} + 帧 JSON；单/多实例同一实现，回放 = 按 {@code seq} 双读拼接（见 {@link FrameStore}）；</li>
 * <li>{@code ai:beat:<runId>} STRING —— 心跳活动戳（毫秒时刻，TTL 随心跳续期）：
 * 心跳不占归档窗口，但"这一轮最近有过活动"仍可跨进程判定（僵尸判据的输入之一）；</li>
 * <li>{@code ai:seq:<runId>} STRING —— <b>已发放的最大业务 seq</b>（T3-2）：每个业务帧发放
 * 序号时 {@code SET} 一次并随帧刷新 TTL=session-ttl（与归档同寿）。delta 帧跳过归档后
 * "归档末帧"不再是可靠的续号锚点，跨进程续号改由本键接管（候选 = max(归档末帧, ai:seq) + 1）。
 * <b>B23L1 批① 起本键是取号口</b>（{@code INCR}，跨实例原子），不再是"发放后登记"；</li>
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

	/** 请求帧已发位（L0 的 {@code req}）的 HASH 键前缀（HSETNX 首写者胜）。 */
	public static final String REQ_PREFIX = "ai:req:";

	/** 决策 guard / WAL 的 HASH 键前缀。 */
	public static final String DECIDED_PREFIX = "ai:decided:";

	/** 续跑权租约键前缀（值 = {@code <epoch>|<instanceId>}）。 */
	public static final String LEASE_RUN_PREFIX = "ai:lease:run:";

	/** fence 键前缀（值 = 已落库的最大 epoch）。 */
	public static final String FENCE_RUN_PREFIX = "ai:fence:run:";

	public static final String LEDGER_PREFIX = "ai:ledger:";

	public static final String EVENTS_PREFIX = "ai:events:";

	/** 心跳活动戳键前缀（T7：心跳不入归档，但活动证据必须留痕）。 */
	public static final String BEAT_PREFIX = "ai:beat:";

	/**
	 * 事件日志的 Stream 键前缀（B23L1 批①）：{@code ai:xevents:<runId>}。
	 *
	 * <p>写入/读取都在 {@link RedisFrameStore}；本类只持有键名，用于"删除轮次"与
	 * "挂起期 TTL 续期"两处 —— 事件日志的寿命必须与会话同规格（否则一次长时间挂起会让
	 * 卡还在、回放源已过期）。
	 */
	public static final String XEVENTS_PREFIX = "ai:xevents:";

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

	/**
	 * "无租约写回但 fence 已在前面"的<b>已告警</b> runId 集合（R6 节流）。
	 *
	 * <p>有界：超过 {@value #FENCE_AHEAD_WARNED_CAP} 个 runId 就整体清空（宁可多几条 WARN，
	 * 也不让一个纯日志用途的集合无界增长）。
	 */
	private final Set<String> fenceAheadWarned = ConcurrentHashMap.newKeySet();

	private static final int FENCE_AHEAD_WARNED_CAP = 512;

	/**
	 * 租约设施（B23L1 批②a）。<b>可为 null</b>：非 Spring 路径（单测、{@code InMemoryRunStore}）
	 * 不提供它 —— 此时 {@code heldRunEpoch} 恒 0 ⇒ 写快照走 plain {@code SET}、
	 * 认领值兜底为 {@code 0|<instanceId>}，与本批之前的语义完全一致（E3 红线）。
	 */
	private final LeaseService leaseService;

	/** fence 写：{@code fence ≤ 本次 epoch} 才写快照并把 fence 推到该 epoch（否则 0 = 拒写）。 */
	private static final RedisScript<Long> FENCED_SET_SCRIPT = new DefaultRedisScript<>("""
			-- LEASE_FENCE_SET
			local fence = redis.call('GET', KEYS[2])
			if fence and tonumber(fence) > tonumber(ARGV[2]) then
			  return 0
			end
			redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
			redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
			return 1
			""", Long.class);

	@Autowired
	public RunStore(StringRedisTemplate redis, ObjectMapper mapper, AiProperties properties, LeaseService leaseService) {
		this.redis = redis;
		this.mapper = mapper;
		this.properties = properties;
		this.leaseService = leaseService;
		this.instanceId = leaseService == null ? InstanceId.get() : leaseService.instanceId();
	}

	/** 无租约设施的构造（单测/进程内替身）：语义与本批之前一致。 */
	public RunStore(StringRedisTemplate redis, ObjectMapper mapper, AiProperties properties) {
		this(redis, mapper, properties, null);
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

	/** 请求帧已发位的 HASH 键名。 */
	public String reqKey(String runId) {
		return REQ_PREFIX + runId;
	}

	/** 决策 guard / WAL 的 HASH 键名。 */
	public String decidedKey(String runId) {
		return DECIDED_PREFIX + runId;
	}

	/** 续跑权租约键名（批②b 的 ResumeService 接线用同一个入口，不另拼字符串）。 */
	public String runLeaseKey(String runId) {
		return LEASE_RUN_PREFIX + runId;
	}

	/** fence 键名。 */
	public String fenceKey(String runId) {
		return FENCE_RUN_PREFIX + runId;
	}

	// ── 轮次快照 ────────────────────────────────────────────────────────────

	/**
	 * 建立一轮的外置快照（不写模型身份；模型身份见
	 * {@link #create(String, String, AiContext, List, MessageJsonCodec, Long, String)}）。
	 */
	public RunSnapshot create(String runId, String sessionId, AiContext context, List<Message> history,
			MessageJsonCodec codec) {
		return create(runId, sessionId, context, history, codec, null, null);
	}

	/**
	 * 建立一轮的外置快照，并固化<b>模型身份</b>（模型配置专项批①；设计稿 §3.4"续跑一致性"）。
	 *
	 * <p>
	 * {@code providerId}/{@code modelId} 是 additive 字段：旧快照没有它们（反序列化为 null），
	 * 新快照从第一笔写就带上。批① 只<b>写</b>（取证面 + 为批③ 的"按快照续跑"备好数据），
	 * 续跑取用口径的切换在批③。
	 *
	 * @param providerId 库内 provider id；{@code null} = env 合成行（种子尚未落库/无 DB 行）
	 * @param modelId 本轮使用的模型名（请求体 {@code model} 的原值）
	 */
	public RunSnapshot create(String runId, String sessionId, AiContext context, List<Message> history,
			MessageJsonCodec codec, Long providerId, String modelId) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(sessionId);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setContext(context == null ? AiContext.empty().toMap() : context.toMap());
		snapshot.setMessageJson(codec.serializeAll(history));
		snapshot.setProviderId(providerId);
		snapshot.setModelId(modelId);
		snapshot.setCreatedBy(this.instanceId);
		snapshot.setCreatedAtMs(System.currentTimeMillis());
		save(snapshot);
		this.redis.opsForSet().add(RUN_INDEX, runId);
		return snapshot;
	}

	/**
	 * 写入轮次快照（唯一写口）。
	 *
	 * <h2>fence 写（B23L1 批②a）</h2>
	 * 持租（{@code heldRunEpoch > 0}）时走 Lua fence 写：{@code ai:fence:run:<runId>} 记"已落库的最大
	 * epoch"，本次 epoch 必须 {@code ≥ fence} 才允许落库，并在同一步把 fence 推到本次 epoch。
	 * 陈旧 epoch（换主后的旧主人）因此写不进去 —— 拒绝只记 {@code STALE_EPOCH} 日志、不抛异常：
	 * 这个线程已经不再是本轮的续跑主人，抛出去只会把它正在收尾的流程打断，而它<b>没有</b>写权限
	 * 这件事本就是预期结果。
	 *
	 * <p><b>红线 E3</b>：{@code heldRunEpoch == 0}（无租约设施 / 未持租 / Redis 降级）时保持原样
	 * plain {@code SET}，TTL 仍是 {@code app.ai.session-ttl}（24h）。"无租约不拒写"是刻意的：
	 * 租约设施故障不能让单实例/降级路径全面停写。
	 *
	 * <p>写终态时 best-effort 释放本实例持有的续跑租约（{@link #releaseRunLease}）—— 正常收尾的轮
	 * 不该让下一个实例等 TTL。
	 */
	public void save(RunSnapshot snapshot) {
		snapshot.setUpdatedAtMs(System.currentTimeMillis());
		String runId = snapshot.getRunId();
		long epoch = heldRunEpoch(runId);
		if (epoch > 0L) {
			snapshot.setRunEpoch(epoch);
		}
		String json;
		try {
			json = this.mapper.writeValueAsString(snapshot);
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法序列化轮次快照 " + runId, ex);
		}
		if (epoch > 0L) {
			if (!fencedSet(runId, json, epoch)) {
				log.warn("STALE_EPOCH 拒写轮次快照 runId={} epoch={}（fence 已推进到更高 epoch："
						+ "另一实例已接管本轮的续跑权，本实例的写回作废）", runId, epoch);
				return;
			}
		}
		else {
			warnIfFenceIsAhead(runId);
			try {
				this.redis.opsForValue().set(runKey(runId), json, ttlFor(runId));
			}
			catch (Exception ex) {
				throw new IllegalStateException("无法写入轮次快照 " + runId, ex);
			}
		}
		// T2b-D#1：快照持久化成功后联动 session → current 轮索引（全部走本方法这一唯一通道，
		// 不会漏分支）。RUNNING 不动 —— settle 期间轮仍是 current，索引必须保留。
		// T3-7（A14）：终态分支改调统一判定函数（不再散点比对字面量）。
		String status = snapshot.getStatus();
		if (RunSnapshot.SUSPENDED.equals(status)) {
			markSessionCurrent(snapshot.getSessionId(), runId);
		}
		else if (RunSnapshot.isTerminal(status)) {
			clearSessionCurrent(snapshot.getSessionId(), runId);
			releaseRunLease(runId);
		}
	}

	/**
	 * E3 分支（{@code heldRunEpoch == 0}，plain SET）的旁路观测（M2）。
	 *
	 * <p>不持租却要写、而 {@code ai:fence:run:<runId>} 已经推进到更高 epoch —— 典型成因是
	 * "本实例曾持租、租约已被别人接管，但进程内视图还没被续租失败清掉"或"降级路径上位"。
	 * 按红线 E3 <b>仍然照写</b>（"无租约不拒写"是刻意选择：租约设施故障不能让单实例/降级路径全面停写），
	 * 但这种情况值得 WARN：它意味着这一笔写回在语义上属于"没有续跑权的写"。
	 */
	private void warnIfFenceIsAhead(String runId) {
		try {
			String fence = this.redis.opsForValue().get(fenceKey(runId));
			if (fence == null || Long.parseLong(fence) <= 0L) {
				return;
			}
			// R6：按 runId 节流 —— 被推进过 fence 的轮在降级路径上可能每笔写回都走到这里，
			// 逐笔 WARN 会把日志淹掉。首次 WARN（现场只需要知道"这个轮有过一次"），其后 DEBUG。
			if (fenceAheadWarned.size() > FENCE_AHEAD_WARNED_CAP) {
				fenceAheadWarned.clear();
			}
			if (fenceAheadWarned.add(runId)) {
				log.warn("无租约写回 runId={}：fence 已推进到 epoch={}（本实例未持租，写回按 E3 放行，"
						+ "但它不具备续跑权语义；若属换主后的陈旧写，请排查租约视图）", runId, fence);
			}
			else {
				log.debug("无租约写回 runId={}：fence 仍为 epoch={}（本 run 已告警过，降为 debug）", runId, fence);
			}
		}
		catch (Exception ex) {
			// 旁路观测失败不影响写回（E3 的可用性优先）
			log.debug("读取 fence 失败 runId={}：{}", runId, ex.getMessage());
		}
	}

	/** fence 写：{@code fence ≤ epoch} 才写（并把 fence 推到 epoch）；返回 false = 陈旧写被拒。 */
	private boolean fencedSet(String runId, String json, long epoch) {
		Duration ttl = ttlFor(runId);
		try {
			Long result = this.redis.execute(FENCED_SET_SCRIPT, List.of(runKey(runId), fenceKey(runId)), json,
					String.valueOf(epoch), String.valueOf(ttl.toMillis()));
			return result != null && result > 0L;
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法写入轮次快照 " + runId, ex);
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
		// 先放掉本进程持有的租约：删键之后脚本再比对就会扑空，进程内视图会成为幽灵所有权
		releaseRunLease(runId);
		// B23L1 批②a：租约 / fence / req 位 / decided guard 同属"本轮的全部键"——
		// 留着它们会让下一轮同 id 的运行继承前任的 fence 与决策（且 fence 只增不减 ⇒ 新租约写不进去）。
		this.redis.delete(List.of(runKey(runId), pendingKey(runId), claimKey(runId), ledgerKey(runId),
				eventsKey(runId), seqKey(runId), xeventsKey(runId), runLeaseKey(runId), fenceKey(runId),
				reqKey(runId), decidedKey(runId)));
		this.redis.opsForSet().remove(RUN_INDEX, runId);
	}

	// ── 续跑权租约（B23L1 批②a；ResumeService 接线归批②b） ──────────────────

	/**
	 * 抢本轮的续跑权租约（{@code ai:lease:run:<runId>}，TTL = {@code app.ai.lease.ttl}）。
	 *
	 * <p>批②a 只建设施：先由 {@link RunStore#save} 的 fence 写与 {@link #claimExecution} 的 epoch 消费；
	 * 批②b 把 {@code ConfirmGate.maybeTriggerResume} / {@code ResumeService} 的进程内锁与
	 * {@code ai:resume:lock:} 归并到这里（同一个键、同一套续租）。
	 *
	 * @return 拿到（含可重入）返回租约；被占用 / 无租约设施 / Redis 降级返回 empty
	 */
	public Optional<LeaseService.Lease> tryAcquireRunLease(String runId) {
		return Optional.ofNullable(acquireRunLease(runId).lease());
	}

	/**
	 * 抢本轮的续跑权租约（<b>三态</b>，I4）：无租约设施时按 {@code UNAVAILABLE} 报
	 * （"没有设施"与"设施坏了"对调用方同义：走进程内互斥，而不是判成"别人持有"）。
	 */
	public LeaseService.AcquireResult acquireRunLease(String runId) {
		// 走访问器而不是字段：子类/替身可以覆写 leaseService()（如 InMemoryRunStore）
		LeaseService facility = leaseService();
		if (facility == null) {
			return new LeaseService.AcquireResult(LeaseService.AcquireOutcome.UNAVAILABLE, null);
		}
		return facility.acquire(runLeaseKey(runId), this.properties.getLease().getTtl());
	}

	/** 释放本实例持有的续跑租约（未持有则无操作）。 */
	public void releaseRunLease(String runId) {
		if (this.leaseService == null) {
			return;
		}
		try {
			this.leaseService.releaseIfHeld(runLeaseKey(runId));
		}
		catch (Exception ex) {
			log.debug("释放续跑租约失败 runId={}：{}", runId, ex.getMessage());
		}
	}

	/** 本进程持有的续跑租约 epoch（0 = 未持有；fence 写判据）。 */
	public long heldRunEpoch(String runId) {
		return this.leaseService == null ? 0L : this.leaseService.heldEpoch(runLeaseKey(runId));
	}

	/** 租约设施（可观测/批②b 接线用；无租约设施时为 null）。 */
	public LeaseService leaseService() {
		return this.leaseService;
	}

	/** 事件日志 Stream 键名（B23L1 批①；写入/读取归 {@link RedisFrameStore}，本类只管键名）。 */
	public String xeventsKey(String runId) {
		return XEVENTS_PREFIX + runId;
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
		Map<Object, Object> markers = requestMarkers(runId);
		Map<Object, Object> decisions = decisionMarkers(runId);
		List<PendingToolCall> out = new ArrayList<>();
		for (Map.Entry<Object, Object> entry : all.entrySet()) {
			try {
				PendingToolCall pending = this.mapper.readValue(String.valueOf(entry.getValue()),
						PendingToolCall.class);
				applyRequestMarker(pending, markers.get(entry.getKey()));
				applyDecisionMarker(pending, decisions.get(entry.getKey()));
				out.add(pending);
			}
			catch (Exception ex) {
				throw new IllegalStateException("无法读取待决条目：" + entry.getValue(), ex);
			}
		}
		return out;
	}

	public PendingToolCall pending(String runId, String toolCallId) {
		String raw = (String) this.redis.opsForHash().get(pendingKey(runId), toolCallId);
		if (raw == null) {
			return null;
		}
		PendingToolCall pending;
		try {
			pending = this.mapper.readValue(raw, PendingToolCall.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法读取待决条目 " + toolCallId, ex);
		}
		applyRequestMarker(pending, requestMarker(runId, toolCallId));
		// I2①：WAL 的读侧收敛（另一次往返）。单条读多一次 HGET 是刻意的 —— 这里回答的是
		// "这张卡现在到底算不算已决"，判错的代价是"已决的卡又被当成待人工输入"。
		applyDecisionMarker(pending, decisionMarker(runId, toolCallId));
		return pending;
	}

	/**
	 * 未做任何读侧合并的<b>原始</b>条目（诊断 / WAL 补齐用）。
	 *
	 * <p>为什么需要它：{@link #pending} 会把 req 位与 guard 结论合进返回值（读侧收敛），
	 * 而 WAL 补齐要判断的是"<b>存储里</b>还是不是 PENDING"—— 用合并后的视图去判断，
	 * 永远看不到"该补"的那一态。
	 *
	 * @return 条目原样；不存在返回 null
	 */
	public PendingToolCall pendingRaw(String runId, String toolCallId) {
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

	/** 单条目的 guard 值（null = 未决/读不出来）。 */
	private Object decisionMarker(String runId, String toolCallId) {
		try {
			return this.redis.opsForHash().get(decidedKey(runId), toolCallId);
		}
		catch (Exception ex) {
			log.debug("读取决策 guard 失败 runId={} toolCallId={}：{}", runId, toolCallId, ex.getMessage());
			return null;
		}
	}

	/** 整批 guard 值（{@link #pendings} 一次读完，避免 N 次往返）。 */
	private Map<Object, Object> decisionMarkers(String runId) {
		try {
			Map<Object, Object> markers = this.redis.opsForHash().entries(decidedKey(runId));
			return markers == null ? Map.of() : markers;
		}
		catch (Exception ex) {
			log.debug("读取决策 guard 失败 runId={}：{}", runId, ex.getMessage());
			return Map.of();
		}
	}

	/**
	 * 把 guard 里的结论合到读出的条目上（I2①：WAL 的<b>读侧</b>收敛，只读不写）。
	 *
	 * <p>与 {@link #applyRequestMarker} 同一手法（grant-only）：条目说 PENDING、guard 说已决 ⇒
	 * 以 guard 为准 —— 这正是"决策已落、条目未跟"（上一写者死在两步之间）的收敛点。
	 *
	 * <h2>为什么只合并"可重建"的三种结论（I1）</h2>
	 * {@link PendingToolCall#REJECTED} / {@link PendingToolCall#FRONTEND_CANCELLED} / {@code CANCELLED}
	 * 的语义是<b>纯终态</b>：结论本身就是全部信息，不需要载荷，读侧据 guard 就能完整重建。
	 * {@link PendingToolCall#FRONTEND_RESULT} 不行 —— 它必须带 {@code resultText}（用户填的值），
	 * 裸状态会让卡"已决但没有结果"；{@link PendingToolCall#APPROVED} 同理不合并（放行的含义是
	 * "可以执行"，条目仍需保留 {@code executed=false} 等执行侧字段，由驱动方按 E2 语义补）。
	 * 所以这两类在 guard 有值、条目仍 PENDING 时<b>保持 PENDING</b>（宁可让卡继续显示为待处理，
	 * 也不让用户看到一张没有内容的已决卡）。
	 */
	private static void applyDecisionMarker(PendingToolCall pending, Object guardValue) {
		if (pending == null || guardValue == null || !PendingToolCall.PENDING.equals(pending.getStatus())) {
			return;
		}
		String raw = String.valueOf(guardValue);
		int sep = raw.indexOf('|');
		String status = sep < 0 ? raw : raw.substring(0, sep);
		if (!mergeableDecisionStatus(status)) {
			return;
		}
		pending.setStatus(status);
		pending.setExecuted(false);
		if (PendingToolCall.REJECTED.equals(status)) {
			pending.setOutcome(com.example.configmgr.ai.tool.ToolOutcome.REJECTED.wire());
			pending.setCode(null);
			if (pending.getResultText() == null) {
				pending.setResultText("用户拒绝了该操作，工具未执行。");
			}
		}
		else if (PendingToolCall.FRONTEND_CANCELLED.equals(status)) {
			pending.setOutcome(com.example.configmgr.ai.tool.ToolOutcome.CANCELLED.wire());
			pending.setCode(com.example.configmgr.ai.tool.ToolOutcome.CODE_FRONTEND_CANCELLED);
		}
		else if (PendingToolCall.CANCELLED.equals(status)) {
			pending.setOutcome(com.example.configmgr.ai.tool.ToolOutcome.CANCELLED.wire());
			pending.setCode(com.example.configmgr.ai.tool.ToolOutcome.CODE_RUN_CANCELLED);
		}
		// 结算时刻取 guard 里记的 atMs（有的话）：它是"决策真正发生的时刻"，比读侧此刻更准
		try {
			long atMs = Long.parseLong(raw.substring(sep + 1, raw.indexOf('|', sep + 1)));
			if (atMs > 0L) {
				pending.setResolvedAtMs(atMs);
			}
		}
		catch (RuntimeException ex) {
			// 坏值/旧形态：结论成立，时刻缺席
		}
	}

	/** 读侧可据 guard 完整重建的结论集合（I1；见 {@link #applyDecisionMarker}）。 */
	public static boolean mergeableDecisionStatus(String status) {
		return PendingToolCall.REJECTED.equals(status) || PendingToolCall.FRONTEND_CANCELLED.equals(status)
				|| PendingToolCall.CANCELLED.equals(status);
	}

	/** 单个条目的 req 位（{@code ai:req:} 的 field；null = 未发射/旧数据）。 */
	private Object requestMarker(String runId, String toolCallId) {
		try {
			return this.redis.opsForHash().get(reqKey(runId), toolCallId);
		}
		catch (Exception ex) {
			// 位读不出来不影响条目本身（宁可按"未发射"处理：少一张卡，不让用户点到未生效的卡）
			log.debug("读取请求帧标记失败 runId={} toolCallId={}：{}", runId, toolCallId, ex.getMessage());
			return null;
		}
	}

	/** 整批 req 位（{@link #pendings} 一次读完，避免 N 次往返）。 */
	private Map<Object, Object> requestMarkers(String runId) {
		try {
			Map<Object, Object> markers = this.redis.opsForHash().entries(reqKey(runId));
			return markers == null ? Map.of() : markers;
		}
		catch (Exception ex) {
			log.debug("读取请求帧标记失败 runId={}：{}", runId, ex.getMessage());
			return Map.of();
		}
	}

	/**
	 * 把独立 HASH 里的 req 位合到读出的条目上（B23L1 批②a / 方案第 5 条）。
	 *
	 * <p>只<b>授予</b>不<b>清除</b>：HASH 里没有该 field 时保持条目 JSON 自带的 {@code req}
	 * （旧数据/本批之前的写入形态），有则以 HASH 为准（首写者的时刻）。
	 */
	private static void applyRequestMarker(PendingToolCall pending, Object marker) {
		if (pending == null || marker == null) {
			return;
		}
		pending.setReq(Boolean.TRUE);
		try {
			long atMs = Long.parseLong(String.valueOf(marker));
			if (atMs > 0L) {
				pending.setReqAtMs(atMs);
			}
		}
		catch (NumberFormatException ex) {
			// 坏值：位仍然成立，只是时刻缺席
		}
	}

	/**
	 * L0（2026-10-21 裁决 A）：给待决条目置「请求帧已发」位（{@link PendingToolCall#getReq()}）。
	 *
	 * <p>时机 = {@code ConfirmGate#await} <b>发射请求帧之前</b>（先置位、后发帧）：反过来先发帧再置位，
	 * "帧已到、位未落"的窗口里刷新会拿不到这张卡；先置位最坏只是"位已落、帧紧跟着发出"，
	 * 刷新重建出的卡与随后到达的帧同形，无副作用。
	 *
	 * <h2>B23L1 批②a：位搬到独立 HASH（{@code ai:req:<runId>}，HSETNX）</h2>
	 * 原实现是"读整条 JSON → 改 req → 整条写回"，它与并发落库的决策是<b>互相覆盖</b>关系：
	 * 读时 {@code PENDING}、写前他方落了 {@code APPROVED}，回写的旧副本会把决策覆盖回
	 * {@code PENDING}（M-2：请求帧还在发、决策却丢了，条目重新变成"待人工输入"）。
	 * 拆成 HSETNX 之后：<b>首写者胜</b>（重复置位不改首次时刻），置位路径<b>不再触碰条目</b>
	 * ⇒ 决策永不丢、取消不会被复活。
	 *
	 * <p>写后重读一次再返回：调用方（{@code ConfirmGate#reserveRequestSlot} 的 M-1 复判）要据
	 * 返回值的 {@code resolved()} 判断"读与写之间是否已被决"，返回陈旧副本就复判不出来。
	 *
	 * <p><b>代价 = 3 次往返</b>（读条目 → {@code HSETNX} + {@code EXPIRE} → 读回），换的是"置位永不
	 * 覆盖并发决策"的一致性：这条路径每张卡只走一次（请求帧发射前），不是热路径；用一次
	 * {@code WATCH/MULTI} 或 Lua 省掉读回也可以，但会把"条目不回写"这条不变量埋进脚本里，
	 * 排障时看不见（L0 修复的正是"回写把决策覆盖回 PENDING"）。
	 *
	 * @param atMs 置位时刻（调用方单点取钟，便于与帧内时间对账）
	 * @return 置位后的条目（已并入 req 位）；条目不存在返回 null
	 */
	public PendingToolCall markRequestEmitted(String runId, String toolCallId, long atMs) {
		PendingToolCall pending = pending(runId, toolCallId);
		if (pending == null || !PendingToolCall.PENDING.equals(pending.getStatus())) {
			return pending;
		}
		try {
			this.redis.opsForHash().putIfAbsent(reqKey(runId), toolCallId, String.valueOf(atMs));
			this.redis.expire(reqKey(runId), ttlFor(runId));
		}
		catch (Exception ex) {
			// 位没落不影响本次请求帧发射（刷新恢复会少一张卡，但不阻断本轮）
			log.warn("写入请求帧标记失败 runId={} toolCallId={}（本次请求帧仍会发出）：{}", runId, toolCallId,
					ex.getMessage());
		}
		PendingToolCall fresh = pending(runId, toolCallId);
		return fresh == null ? pending : fresh;
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
	 * <p>
	 * <b>B23L1 批②a</b>：值升级为 {@code <epoch>|<instanceId>}（认领能与续跑权对账），
	 * {@code HSETNX} 的原子性不变；读口 {@link #claimedBy} 剥前缀，对调用方返回的仍是实例标识。
	 * 认领所用 epoch 同时写进条目的 {@link PendingToolCall#getClaimedEpoch()}（additive）。
	 *
	 * @return true = 本次由当前实例认领（可以执行）；false = 已被他实例认领（<b>不得</b>执行）
	 */
	public boolean claimExecution(String runId, String toolCallId, String executedBy) {
		long epoch = heldRunEpoch(runId);
		if (epoch > 0L) {
			// I5：持租不等于"仍是主人" —— TTL 到期被别人接管后，本进程的视图要等续租失败才会清。
			// 认领是"执行一个副作用"的入口，必须对着 fence 复核：fence 已推进到更高 epoch ⇒ 本实例的
			// 执行权已失效，拒绝认领（返回 false 的语义与"被他实例认领"一致：不得执行）。
			long fence = fenceEpoch(runId);
			if (fence > epoch) {
				log.warn("STALE_EPOCH 拒绝认领 runId={} toolCallId={} epoch={}（fence 已推进到 {}："
						+ "本实例的续跑权已被接管，不得执行该工具）", runId, toolCallId, epoch, fence);
				return false;
			}
		}
		String claimValue = epoch + "|" + executedBy;
		Boolean claimed = this.redis.opsForHash().putIfAbsent(claimKey(runId), toolCallId, claimValue);
		this.redis.expire(claimKey(runId), ttlFor(runId));
		boolean won = Boolean.TRUE.equals(claimed);
		if (won) {
			PendingToolCall pending = pending(runId, toolCallId);
			if (pending != null) {
				pending.setClaimedAtMs(System.currentTimeMillis());
				pending.setClaimedEpoch(epoch);
				pending.setExecuted(true);
				pending.setExecutedBy(executedBy);
				putPending(runId, pending);
			}
		}
		return won;
	}

	/** 已落库的最大 epoch（{@code ai:fence:run:<runId>}；0 = 无键/坏值/读不出来）。 */
	public long fenceEpoch(String runId) {
		try {
			String raw = this.redis.opsForValue().get(fenceKey(runId));
			return raw == null ? 0L : Long.parseLong(raw);
		}
		catch (Exception ex) {
			// R7：fence 读失败 ⇒ 认领侧按 0 处理（fail-open：不拒认领）。这是<b>放宽</b>而不是收紧，
			// 所以必须 WARN —— 否则"Redis 读不出来"会让 fencing 静默失效，现场只会看到"什么都没发生"。
			// （TOCTOU：GET 与 HSETNX 之间 fence 仍可能推进；合成一条脚本可以消掉，挂账由提交官登记。）
			log.warn("读取 fence 失败 runId={}（认领侧按 fence=0 放行，fencing 本拍失效）：{}", runId,
					ex.getMessage());
			return 0L;
		}
	}

	/** 认领者实例标识（null=未认领）。值里的 {@code <epoch>|} 前缀在此剥掉（旧裸值原样返回）。 */
	public String claimedBy(String runId, String toolCallId) {
		Object value = this.redis.opsForHash().get(claimKey(runId), toolCallId);
		return value == null ? null : stripEpochPrefix(String.valueOf(value));
	}

	/**
	 * 剥 {@code <epoch>|} 前缀：前缀 = 全数字 + 第一个 {@code |}（实例标识不可能长这样）。
	 * 旧数据（裸 instanceId）原样返回 —— 既有断言 {@code claimedBy(...) == instanceId()} 因此零改动。
	 */
	public static String stripEpochPrefix(String raw) {
		int sep = raw.indexOf('|');
		if (sep <= 0) {
			return raw;
		}
		for (int i = 0; i < sep; i++) {
			if (!Character.isDigit(raw.charAt(i))) {
				return raw;
			}
		}
		return raw.substring(sep + 1);
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
		// I5：执行发生在哪一任续跑权之下（additive；旧记录没有本字段 ⇒ 读侧按缺失处理）。
		// 取值顺序：认领时的 epoch（执行权的依据）→ 当前持租 epoch（旧条目/兼容路径）。
		record.put("epoch", pending.getClaimedEpoch() > 0L ? pending.getClaimedEpoch() : heldRunEpoch(runId));
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
	 *
	 * <p>
	 * <b>B23L1 批①（2026-10-21）：本方法已退居兼容写口</b> —— 生产出帧路径改经
	 * {@link FrameStore#append}（{@code ai:xevents:} Stream），本方法只服务
	 * {@link RunStoreFrameStore}（非 Spring/单测路径）与历史调用方；语义（心跳跳过、delta 入档、
	 * 窗口裁剪、滑动 TTL）保持不动。
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
	 * <p>确认门 / 表单卡的等待没有上限，而这张卡的可重建性依赖五类键：轮快照
	 * （{@code ai:run}，含挂起中的 assistant(tool_calls) 与历史）、待决条目
	 * （{@code ai:pending}，人的决策落点）、帧归档（{@code ai:events}，兼容期只读）、
	 * 事件日志 Stream（{@code ai:xevents}，B23L1 批① 起的回放真值）、
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
			// B23L1 批①：事件日志 Stream 与会话同寿（挂起期没有 append，靠心跳续期）
			this.redis.expire(xeventsKey(runId), ttl);
		}
		catch (Exception ex) {
			log.debug("挂起期续期轮键失败 runId={}：{}", runId, ex.getMessage());
		}
	}

	// ── 挂起态的读侧续期唯一入口（B23L1 批②a / 方案第 10 条） ────────────────

	/**
	 * 读侧续期：<b>唯一入口</b>（设计稿 §2.2 "挂起态 TTL 续期改由读侧触发"）。
	 *
	 * <p>批②b 起挂起线程不再被假设一定活着（挂起在 A、读取/决策落在 B），因此"谁养着卡的后台数据"
	 * 从"挂起线程的心跳"改为"任何实例的读/决策都 touch 一次"。本方法自带<b>终态判据</b>：
	 * 快照不存在或已终态 ⇒ <b>不续期</b>（读侧不得把已收尾/已删除的轮重新养出孤儿键），返回 false。
	 *
	 * <p>续期面 = 心跳同款的五类键（{@link #renewSuspensionTtl}，本体不动）+ session → current 索引
	 * + 本批新增的两个契约位键（{@code ai:req} / {@code ai:decided}）：req 位若先于待决条目过期，
	 * 刷新恢复会拿不到卡（L0 修复的那个洞会换个形式复发）。
	 *
	 * @return true = 已续期（挂起态）；false = 缺失/终态，未做任何续期
	 */
	public boolean touchActiveRun(String runId) {
		if (runId == null) {
			return false;
		}
		RunSnapshot snapshot;
		try {
			snapshot = get(runId);
		}
		catch (Exception ex) {
			log.debug("读侧续期读不到快照 runId={}：{}", runId, ex.getMessage());
			return false;
		}
		return touchActiveRun(snapshot);
	}

	/** 读侧续期的省一次读重载（调用方刚从 Redis 读到快照）。 */
	public boolean touchActiveRun(RunSnapshot snapshot) {
		if (snapshot == null || snapshot.getRunId() == null) {
			return false;
		}
		if (RunSnapshot.isTerminal(snapshot.getStatus())) {
			return false;
		}
		String runId = snapshot.getRunId();
		renewSuspensionTtl(runId);
		renewSessionCurrent(snapshot.getSessionId(), runId);
		Duration ttl = ttlFor(runId);
		try {
			this.redis.expire(reqKey(runId), ttl);
			this.redis.expire(decidedKey(runId), ttl);
			// M1：fence 键与快照同寿。它若先过期，下一次 fence 写会把号"推回去"（新写的 epoch 记在
			// 一个空键上，陈旧写反而可能通过），fencing 的单调前提就被打破了。
			this.redis.expire(fenceKey(runId), ttl);
		}
		catch (Exception ex) {
			log.debug("读侧续期契约位键失败 runId={}：{}", runId, ex.getMessage());
		}
		return true;
	}

	// ── 决策 guard / WAL（B23L1 批②a 建设施；ConfirmGate 接入归批②b） ────────

	/** 一次判定的结论：{@code won} = 本条由本次调用抢到；{@code recorded} = guard 里现在的值。 */
	public record DecisionClaim(boolean won, String recorded) {
	}

	/**
	 * 抢一条决策（{@code ai:decided:<runId>} 的 {@code HSETNX}）。
	 *
	 * <p>两层用途：
	 * <ul>
	 * <li><b>guard</b>：多实例同时收到同一张卡的两个决策（双击 / 重发 / 双页签）时，只有一次能落库 ——
	 * {@code won=false} 的一方读 {@code recorded} 就知道"已经有结论了"，按幂等拒绝；</li>
	 * <li><b>WAL</b>：先写 guard、再改待决条目。进程死在两步之间时，条目仍是 {@code PENDING}，
	 * 但读侧可据 {@link #decidedStatus} 把结论收敛出来（"决策已落、回执未走完"）。</li>
	 * </ul>
	 *
	 * <p>不做 Lua：单条 {@code HSETNX} 本身就是原子的，额外脚本只会多一次往返（方案第 6 条）。
	 *
	 * @param status 决策后的条目状态（{@link PendingToolCall#APPROVED} / {@code REJECTED} / ...）
	 */
	public DecisionClaim tryRecordDecision(String runId, String toolCallId, String status) {
		String value = status + "|" + System.currentTimeMillis() + "|" + this.instanceId;
		try {
			Boolean first = this.redis.opsForHash().putIfAbsent(decidedKey(runId), toolCallId, value);
			this.redis.expire(decidedKey(runId), ttlFor(runId));
			if (Boolean.TRUE.equals(first)) {
				return new DecisionClaim(true, value);
			}
			Object current = this.redis.opsForHash().get(decidedKey(runId), toolCallId);
			return new DecisionClaim(false, current == null ? null : String.valueOf(current));
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法写入决策 guard " + toolCallId, ex);
		}
	}

	/** 决策 guard 原文（{@code <status>|<atMs>|<instanceId>}；null = 未决策/旧数据）。 */
	public String decisionOf(String runId, String toolCallId) {
		try {
			Object value = this.redis.opsForHash().get(decidedKey(runId), toolCallId);
			return value == null ? null : String.valueOf(value);
		}
		catch (Exception ex) {
			log.debug("读取决策 guard 失败 runId={} toolCallId={}：{}", runId, toolCallId, ex.getMessage());
			return null;
		}
	}

	/** 决策 guard 里的结论状态（null = 未决策）。 */
	public String decidedStatus(String runId, String toolCallId) {
		String raw = decisionOf(runId, toolCallId);
		if (raw == null) {
			return null;
		}
		int sep = raw.indexOf('|');
		return sep < 0 ? raw : raw.substring(0, sep);
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
