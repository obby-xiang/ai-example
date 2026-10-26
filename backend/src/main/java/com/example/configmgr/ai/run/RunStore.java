package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 挂起态与台账的 Redis 外置存储（S4.2 §2 {@code run/RunStore}，移植自 SP-02 同名实现）。
 *
 * <h2>键结构（run 级键<b>全部带滑动 TTL</b> = {@code app.ai.session-ttl}，默认 24h，前缀 {@code ai:}）</h2>
 * <p>
 * <b>无绝对上限</b>（用户裁决 2026-10-20 第 4 条）：每次写键/心跳都把该键重新养满一个 TTL，
 * 挂起等待期间的心跳（2s 一拍）照常续期 —— 会话活着，卡的后台数据就活着。原 T3-8 的
 * "创建时刻 + session-ttl 绝对封顶"已退役。
 * <p><b>hash tag（B23L1 批① Cluster 硬阻断）</b>：run 级键的"一轮 13 键"<b>全部</b>带
 * {@code {run:<runId>}} tag（如 {@code ai:run:{run:<runId>}}），见 {@link #runTag(String)} ——
 * 既是 {@code FENCED_SET} / {@code IDEM_PUSH} 两条多键脚本的同槽前提，也让 {@link #deleteRun}
 * 的 13 键 {@code DEL} 回到"一条命令、原子生效"。{@code ai:cancel:} / {@code ai:beat:} 只进单键命令，
 * 不在这一族里（不加 tag）。下文的键形为可读性省去 tag。</p>
 * <ul>
 * <li>{@code ai:run:<runId>} STRING（JSON）—— {@link RunSnapshot}：完整请求历史 +
 * 挂起中的 {@code assistant(tool_calls)} + 状态 + 上下文；</li>
 * <li>{@code ai:runs} SET —— runId 索引（重启后"发现待续跑轮次"的入口）。
 * <b>不带 TTL、也不给整键 EXPIRE</b>（Redis 容量专项 A）：SET 无法按成员过期，整键过期会把活跃轮的
 * 发现面一起删掉；改为<b>惰性清理</b> —— 快照键已不在的成员由 {@link #pruneRunIndex()} 剔除
 * （启动扫描 {@link #allRunIds()} 与 {@code create} 的节流机会式清理两处触发）；
 * 空 SET 由 Redis 自己消失，因此不会留空壳键；</li>
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
 * <li>{@code ai:lease:run:<runId>} STRING（value={@code <epoch>|<instanceId>}，TTL 30s；带 {@code {run:<runId>}} tag）——
 * <b>续跑权租约</b>（B23L1 批②a；{@link LeaseService}）：持租者才是本轮的续跑主人，
 * 续租失败即被别的实例接管；</li>
 * <li>{@code ai:fence:run:<runId>} STRING —— <b>fence</b>：已落库的最大 epoch。
 * {@link #save} 在持租时走 Lua fence 写（{@code fence ≤ epoch} 才写，并把 fence 推到该 epoch）；
 * TTL 随快照。无租约（降级/单实例）时保持 plain {@code SET}（不拒写）；</li>
 * <li>{@code ai:ledger:<runId>} LIST（JSON）—— 工具执行台账：每次<b>真实执行完成</b>一条，
 * 含 {@code executedBy} 实例标识，是跨进程"不重放"的判据；</li>
 * <li>{@code ai:idem:<runId>} HASH（B23L1 批③a）—— 工具执行的<b>幂等索引</b>：
 * field={@code <runId>:<toolCallId>}，value={@code <listIndex>|<outcome>|<code>|<executedBy>|<atMs>}，
 * 与台账记录在<b>同一条 Lua</b> 里写入（{@code RPUSH} + {@code HSETNX}）；读口 {@link #idemFor}
 * 命中即 {@code LINDEX} 取回原记录（O(1)），未命中回落 {@link #ledgerFor} 扫描（旧数据兼容）；</li>
 * <li>{@code ai:lock:task:<taskId>} / {@code ai:lock:job:<taskId>:<JOBTYPE>} STRING
 * （B23L1 批③a；{@code com.example.configmgr.ai.run.LeaseKeys}）—— <b>L1 资源互斥</b>：
 * 按 taskId 记（不是 runId），值 = {@code <epoch>|<owner>}，TTL = {@code app.ai.lease.resource-ttl}；
 * 轮次回收（{@link #deleteRun}）<b>不</b>清这两键（见该方法注）；</li>
 * <li>{@code ai:events:<runId>} LIST（JSON）—— SSE 帧外置副本（崩溃前的帧不随进程消失，
 * 供 {@code GET /api/ai/events/{runId}} 重挂时回放）；每帧带单调 {@code seq}（T6 的差量补发判据），
 * <b>心跳帧不入本归档</b>（T7，改记 {@code ai:beat:<runId>}）。
 * <b>B23L1 批① 起本键进只读兼容期</b>：新写只进 {@code ai:xevents:<runId>} Stream（{@link RedisFrameStore}），
 * 本类只保留"读侧兼容"与"键的删除/TTL 续期"；</li>
 * <li>{@code ai:xevents:<runId>} Stream —— <b>事件日志真值</b>（B23L1 批①）：{@code XADD ... MAXLEN ~ 6000}，
 * 字段 {@code seq} + 帧 JSON；单/多实例同一实现，回放 = 按 {@code seq} 双读拼接（见 {@link FrameStore}）；</li>
 * <li>{@code ai:beat:<runId>} STRING —— 心跳活动戳（毫秒时刻，TTL 随心跳续期）：
 * 心跳不占归档窗口，但"这一轮最近有过活动"仍可跨进程判定（<b>只证明进程还活着</b>，
 * 不是业务前进的证据 —— 僵尸判据看 {@code ai:activity}）；</li>
 * <li>{@code ai:activity:<runId>} STRING —— <b>业务活动锚点</b>（R1-09）：最近一条非心跳业务帧的
 * {@code atMs}，出帧点与兼容写口都刷新；与事件日志尾部（LIST + Stream）取 max 后作为
 * {@link #lastActivityAtMs(String)} 的返回值（僵尸判据的输入，心跳不参与）；</li>
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
public class RunStore implements com.example.configmgr.ai.context.ContextStateStore {

	public static final String RUN_PREFIX = "ai:run:";

	public static final String RUN_INDEX = "ai:runs";

	public static final String PENDING_PREFIX = "ai:pending:";

	/** 上下文真值 HASH 前缀（上下文统计与压缩专项批①；{@code ai:ctx:<sessionId>}）。 */
	public static final String CONTEXT_PREFIX = "ai:ctx:";

	/** 上下文台账 LIST 前缀（{@code ai:ctx:ledger:<sessionId>}，压缩事件，最近 20 条）。 */
	public static final String CONTEXT_LEDGER_PREFIX = "ai:ctx:ledger:";

	/** 压缩前原文前缀（{@code ai:ctx:archive:<sessionId>:<turnNo>}）。 */
	public static final String CONTEXT_ARCHIVE_PREFIX = "ai:ctx:archive:";

	/** 上下文台账保留条数（设计稿 §3.6：最近 20 条）。 */
	public static final int CONTEXT_LEDGER_MAX = 20;

	/**
	 * 安全事件台账前缀（防护批③ B3-7；{@code ai:safety:<sessionId>}）。
	 *
	 * <p>
	 * 与 {@code ai:ctx:ledger:} 同一形态（LIST、新→旧、TTL 同会话），但<b>键族与写入方法都是新的</b>
	 * —— 上下文批的台账方法一个字都不动（改旧方法会在合流时变成同 hunk 冲突）。
	 * 条目只记 {@code atMs / runId / turnNo / ruleId / offset / length / action}，<b>无原文</b>。
	 */
	public static final String SAFETY_PREFIX = "ai:safety:";

	/** 安全事件台账保留条数（一个新版页面最多几十次命中，20 条够复盘一轮）。 */
	public static final int SAFETY_LEDGER_MAX = 20;

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

	/**
	 * 幂等索引的 HASH 键前缀（B23L1 批③a / 裁决 6）：{@code ai:idem:<runId>}。
	 *
	 * <p>field = {@code <runId>:<toolCallId>}，value = {@code <listIndex>|<outcome>|<code>|<executedBy>|<atMs>}。
	 * <b>不复制 {@code resultText}</b>：结果正文可以很大（帧上限 64KB），索引只是"哪条台账记录"的
	 * 指针；正文留在 LIST 里（{@code LINDEX} 取回原记录，O(1)），两份正文会出现"索引与台账不一致"。
	 */
	public static final String IDEM_PREFIX = "ai:idem:";

	public static final String EVENTS_PREFIX = "ai:events:";

	/** 心跳活动戳键前缀（T7：心跳不入归档，但活动证据必须留痕）。 */
	public static final String BEAT_PREFIX = "ai:beat:";

	/**
	 * <b>业务活动锚点</b>键前缀（R1-09）：{@code ai:activity:<runId>} = 最近一条
	 * <b>非心跳</b>业务帧的 {@code atMs}（毫秒）。
	 *
	 * <p>与 {@link #BEAT_PREFIX} 的分工：心跳只写 {@code ai:beat}，业务帧只写 {@code ai:activity}
	 * （读取侧再看事件日志尾部）—— 于是"进程还在发心跳、业务已卡死"不会被当成活轮。
	 *
	 * <p>键形是<b>裸前缀</b>（不带 {@code {run:<runId>}} tag），与 {@code ai:beat} 同类：只进单键
	 * 命令、不进 {@link #deleteRun} 的原子 {@code DEL} 族，按 session-ttl 自清。
	 */
	public static final String ACTIVITY_PREFIX = "ai:activity:";

	/**
	 * 活动锚点读取时探测的尾部帧数（兼容 LIST 与事件 Stream 各探这么多条）。
	 *
	 * <p>为什么不是只读最后一条：显式 ID 落档被拒时会改用自动 ID（物理位次与 seq 不再一致），
	 * 且跨实例并发出帧时 {@code atMs} 未必严格按物理序递增；尾部取 max 才能在"晚到的旧号帧"
	 * 或轻微钟漂下仍拿到真实的最新活动。
	 */
	static final int ACTIVITY_TAIL_PROBE = 16;

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

	/**
	 * 会话 → 该会话全部 runId 的轻量索引（{@code ai:session:runs:<sessionId>}，SET，TTL 与会话同寿）。
	 *
	 * <p>为什么要它：会话销毁要枚举"本会话的轮次"，而 {@code ai:runs} 是全局集合；
	 * 只靠它就得对所有快照逐个反序列化再比对 sessionId（生产上可能是成百上千轮）。
	 * 本索引让销毁只读本会话候选；<b>老数据没有这个索引时</b>，销毁方必须回退全局扫描
	 * （索引是加速面，不是唯一事实源）。
	 *
	 * <p>生命周期：{@link #create} SADD + 设 TTL，{@link #deleteRun} SREM；空 SET 由 Redis 自动消失。
	 * TTL 续期随快照写（{@link #save}）滑动；索引过期/SADD 失败只影响快路径，不影响回退正确性。
	 */
	public static final String SESSION_RUNS_PREFIX = "ai:session:runs:";

	/**
	 * 会话→run 索引的“已回填”内部标记（同一 SET 内的 sentinel）。
	 *
	 * <p>为什么需要：升级前的老 session 在全局 {@code ai:runs} 有 run，却没有本索引；
	 * 升级后 {@code create} 只把<b>新</b> run SADD 进来 —— 若把“SET 非空”当成“索引完整”，
	 * 老 run 就会被漏删。只有完成一次按 sessionId 过滤的全局枚举并成功回填后，才写入本标记；
	 * 读侧必须同时看到标记才信任快路径。
	 *
	 * <p>本值不是 runId：{@link #sessionRunIndex} 会把它从返回的 runId 集合中剔除，
	 * 任何“按 run 操作”的调用方都不会拿到它（runId 是 UUID，不会与之冲突）。
	 * 与索引同键、同 TTL：索引过期标记一起过期。
	 */
	public static final String SESSION_RUNS_COMPLETE = "__session_runs_complete__";

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

	/** "丢租/不可用 ⇒ 已拒绝推进"的告警节流（每个 runId 一条，避免逐帧逐写刷屏）。 */
	private final Set<String> readOnlyWarned = ConcurrentHashMap.newKeySet();

	/**
	 * "HELD 但 fence 已推进到更高 epoch ⇒ 已拒绝推进"的告警节流（RT-21；每个 runId 一条）。
	 *
	 * <p>与 {@link #fenceAheadWarned} 分开：那一个是"无租约写回的旁路观测"（plain SET 路径），
	 * 本集合是"持租但续跑权已被接管"的拒写留痕，两者的处置完全不同。
	 */
	private final Set<String> fenceStaleWarned = ConcurrentHashMap.newKeySet();

	private static final int FENCE_AHEAD_WARNED_CAP = 512;

	/**
	 * 上次<b>机会式</b>{@code ai:runs} 索引清理的时刻（毫秒；Redis 容量专项 A）。
	 *
	 * <p>0 = 本进程还没清过 ⇒ 重启后的第一次 {@code create} 会清一拍（顺手把崩溃前留下的残留剔掉）。
	 * 用 CAS 抢占"这一拍由我清"，多实例并发创建时不会每个实例都扫一遍全表。
	 */
	private final AtomicLong indexPrunedAtMs = new AtomicLong();

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

	/** DONE 专用：不新增键，只在原快照键上阻止取消态被成功终态覆盖。 */
	private static final RedisScript<Long> DONE_SET_IF_NOT_CANCELLED_SCRIPT = new DefaultRedisScript<>("""
			-- DONE_SET_IF_NOT_CANCELLED
			local current = redis.call('GET', KEYS[1])
			if current then
			  local ok, snapshot = pcall(cjson.decode, current)
			  -- 解不开的旧值不冒充取消态（本进程写出的快照恒为合法 JSON）：按可用性优先照常写
			  if ok and type(snapshot) == 'table'
			      and (snapshot.status == 'CANCELLED' or snapshot.cancelled == true) then
			    return 0
			  end
			end
			redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
			return 1
			""", Long.class);

	/** DONE 专用：同一原子段内同时校验原 fence 与取消态，其它终态仍走原脚本。 */
	private static final RedisScript<Long> FENCED_DONE_SET_SCRIPT = new DefaultRedisScript<>("""
			-- FENCED_DONE_SET
			local fence = redis.call('GET', KEYS[2])
			if fence and tonumber(fence) > tonumber(ARGV[2]) then
			  return 0
			end
			local current = redis.call('GET', KEYS[1])
			if current then
			  local ok, snapshot = pcall(cjson.decode, current)
			  if ok and type(snapshot) == 'table'
			      and (snapshot.status == 'CANCELLED' or snapshot.cancelled == true) then
			    return 0
			  end
			end
			redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
			redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
			return 1
			""", Long.class);

	/** C1 / Minor3：值匹配才删认领字段（避免误删他人重新认领的值）。 */
	private static final RedisScript<Long> CLAIM_ROLLBACK_SCRIPT = new DefaultRedisScript<>("""
			-- CLAIM_ROLLBACK
			if redis.call('HGET', KEYS[1], ARGV[1]) == ARGV[2] then
			  return redis.call('HDEL', KEYS[1], ARGV[1])
			end
			return 0
			""", Long.class);

	/**
	 * 台账落库 + 幂等索引<b>同一个原子步</b>（B23L1 批③a / 裁决 6）。
	 *
	 * <p>为什么必须合成一条脚本：先 {@code RPUSH} 再 {@code HSET}，两步之间崩溃会留下
	 * "台账有记录、索引没有"的洞 —— 下一次续跑按索引查会查不到，于是<b>再执行一遍</b>
	 * （工具是导入/发布这类不可撤销的副作用）；反过来先写索引再推台账，则会留下
	 * "索引指向一条不存在的记录"（读侧只能回落全表扫描，索引白建）。
	 *
	 * <p>索引下标在脚本内用 {@code LLEN} 现取（<b>推入前</b>的列表长度 = 本次记录的 0 基下标）：
	 * Java 侧先读长度再推入会与并发写者竞争，拿到的下标指向别人的记录。
	 *
	 * <p>{@code HSETNX} 而非 {@code HSET}：同一条 {@code (runId, toolCallId)} 首次执行即建索引，
	 * 之后的重放/重试不得改写"第一次是哪条记录"。
	 *
	 * @param KEYS[1] 台账 LIST 键 {@code ai:ledger:<runId>}
	 * @param KEYS[2] 幂等索引 HASH 键 {@code ai:idem:<runId>}
	 * @param ARGV[1] 台账记录 JSON
	 * @param ARGV[2] 索引 field（{@code <runId>:<toolCallId>}）
	 * @param ARGV[3] 索引 value 的后半段（{@code <outcome>|<code>|<executedBy>|<atMs>}；下标由脚本前插）
	 * @param ARGV[4] TTL（毫秒，两个键同寿 = {@code app.ai.session-ttl}）
	 */
	private static final RedisScript<Long> IDEM_PUSH_SCRIPT = new DefaultRedisScript<>("""
			-- IDEM_PUSH
			local index = redis.call('LLEN', KEYS[1])
			redis.call('RPUSH', KEYS[1], ARGV[1])
			redis.call('HSETNX', KEYS[2], ARGV[2], tostring(index) .. '|' .. ARGV[3])
			redis.call('PEXPIRE', KEYS[1], ARGV[4])
			redis.call('PEXPIRE', KEYS[2], ARGV[4])
			return index
			""", Long.class);

	/**
	 * 业务活动锚点的<b>单键原子 max + 续期</b>（R1-09 / I-3）：{@code ai:activity:<runId>}。
	 *
	 * <p>
	 * 为什么必须是 Lua：Java 侧 {@code GET → 比较 → SET} 在多实例并发时存在经典回拨窗口 ——
	 * A（旧帧 1000）读到 0，B（新帧 2000）先 SET，A 再 SET 1000，锚点被旧帧回拨。读取侧虽然还会与
	 * 事件日志尾部取 max 兜底，但尾部只探有限条；落后时钟的实例连续写旧帧时，最新帧会滑出探测窗。
	 * 单键脚本在 Redis 的串行执行语义下"比较 + 写 + 续期"一次完成，既单调又只要 <b>1 次往返</b>
	 * （流式热路径每个 delta 都走这里，原先 2 次 RTT 会翻倍 emitLock 的持锁时间）。
	 *
	 * <p>
	 * <b>坏值覆盖</b>（复审 M-4）：{@code tonumber} 失败按 0 处理，本次 {@code atMs} 正常写入 ——
	 * 否则一个坏值会让该 run 的活动锚点失效到 TTL 到期。
	 *
	 * <p>
	 * <b>时钟域</b>（复审 M-2）：{@code atMs} 是出帧节点的墙钟，判死用的是本节点 {@code now}。
	 * 跨节点负偏会让活轮提前超龄，部署必须保证 NTP 同步；偏差应远小于 {@code zombie-margin}。
	 *
	 * @param KEYS[1] {@code ai:activity:<runId>}
	 * @param ARGV[1] 帧的 {@code atMs}（毫秒）
	 * @param ARGV[2] TTL（毫秒，{@code app.ai.session-ttl}）
	 */
	private static final RedisScript<Long> ACTIVITY_TOUCH_SCRIPT = new DefaultRedisScript<>("""
			-- ACTIVITY_TOUCH
			local current = tonumber(redis.call('GET', KEYS[1]) or '0')
			if not current then current = 0 end
			local incoming = tonumber(ARGV[1]) or 0
			if incoming > current then
			  redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
			else
			  redis.call('PEXPIRE', KEYS[1], ARGV[2])
			end
			return 1
			""", Long.class);

	/**
	 * 认领结论的三态（B23L1 批③a / 裁决 9）。
	 *
	 * <p>为什么要区分后两者：{@code LOST} 的语义是"这个调用已经有主（他实例在执行）"，
	 * {@code STALE_FENCE} 的语义是"<b>我</b>已经没有续跑权了" —— 处理方式不同（前者等/跳过，
	 * 后者要放弃本进程的驱动、可能触发接管），混成一个 false 时现场只能看到"认领失败"。
	 */
	public enum ClaimOutcome {

		/** 本次由当前实例认领成功（可以执行）。 */
		WON,

		/** 已被他实例认领（不得执行，等它执行完）。 */
		LOST,

		/** fence 已推进到更高 epoch：本实例的续跑权已被接管（不得执行）。 */
		STALE_FENCE

	}

	/**
	 * 认领结论。
	 *
	 * @param outcome 三态结论
	 * @param epoch 本次认领试图使用的租约 epoch（0 = 无租约路径）
	 * @param fence 认领时读到的 fence（0 = 无 fence 键/未持租未读）
	 */
	public record ClaimResult(ClaimOutcome outcome, long epoch, long fence) {

		/** 是否认领成功（{@link ClaimOutcome#WON}）。 */
		public boolean won() {
			return this.outcome == ClaimOutcome.WON;
		}

	}

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

	/**
	 * run 级键族的 <b>hash tag</b>（B23L1 批① Cluster 硬阻断 / R1-02·R1-03·R1-05）：
	 * {@code {run:<runId>}}。
	 *
	 * <h2>为什么同一轮的键必须同槽</h2>
	 * 原生 Redis Cluster 下一条 {@code EVAL} 的 KEYS 必须落在同一槽，否则整条命令回
	 * {@code CROSSSLOT}。本仓有两条多键脚本压在"一轮的几个键"上：
	 * <ul>
	 * <li>{@link #FENCED_SET_SCRIPT}（快照 + fence）—— 跨槽就是<b>持租后每次写快照都抛</b>（500）；</li>
	 * <li>{@link #IDEM_PUSH_SCRIPT}（台账 + 幂等索引）—— 跨槽炸在<b>不可撤销的副作用已经发生之后</b>。</li>
	 * </ul>
	 * 两者共用<b>同一个</b> tag，才不会引入第二套键名；{@link #deleteRun} 的多键 {@code DEL} 也随同一
	 * tag 落回一个槽（同槽 ⇒ 一条命令原子生效）。
	 *
	 * <h2>同槽的第三个用处：取号</h2>
	 * run 租约的计数器（{@code ai:lease:epoch:{run:<runId>}}，见 {@link #runLeaseEpochKey(String)}）
	 * 也带这个 tag ⇒ {@code INCR} 与 {@code SET} 能回到同一条 Lua（评审 I-1：号与占用必须同序，
	 * 否则迟到者会用比 fence 更小的号占住租约，让这一轮永久写不进去）。
	 *
	 * <h2>tag 形态</h2>
	 * {@code ai:run:{run:<runId>}} —— tag 放在前缀之后，前缀本身保持可 grep
	 * （{@link SessionKeyRegistry} 的登记锚点、运维面 {@code keys} 段都按前缀看）。
	 */
	public static String runTag(String runId) {
		return "{run:" + runId + "}";
	}

	/**
	 * run 级键名的<b>唯一拼接口</b>：{@code <prefix>{run:<runId>}}。
	 *
	 * <p>所有"多键脚本/多键 DEL 会碰到"的 run 级键都从这里出（见 {@link #runTag(String)} 的清单）；
	 * 只有单键命令使用的键才允许留在裸前缀上。
	 */
	public static String runScopedKey(String prefix, String runId) {
		return prefix + runTag(runId);
	}

	public String runKey(String runId) {
		return runScopedKey(RUN_PREFIX, runId);
	}

	public String pendingKey(String runId) {
		return runScopedKey(PENDING_PREFIX, runId);
	}

	public String claimKey(String runId) {
		return runScopedKey(CLAIM_PREFIX, runId);
	}

	public String ledgerKey(String runId) {
		return runScopedKey(LEDGER_PREFIX, runId);
	}

	/** 幂等索引的 HASH 键名（{@code ai:idem:{run:<runId>}}；与台账同槽）。 */
	public String idemKey(String runId) {
		return runScopedKey(IDEM_PREFIX, runId);
	}

	/**
	 * 幂等索引的 field：{@code <runId>:<toolCallId>}（B23L1 批③a / 裁决 6）。
	 *
	 * <p>为什么把 runId 拼进 field：{@code toolCallId} 由上游模型生成，<b>只在单轮内唯一</b>；
	 * 同一个 HASH 里若只按 toolCallId 记，续跑/重试时换了一轮却撞上同一个 id 就会误判"已执行"。
	 */
	public static String idemField(String runId, String toolCallId) {
		return runId + ":" + (toolCallId == null ? "" : toolCallId);
	}

	/**
	 * 兼容期 LIST 键名（{@code ai:events:{run:<runId>}}）。
	 *
	 * <p>与 {@code RedisFrameStore#legacyListKey} 同一拼接口（那边调 {@link #runScopedKey}）——
	 * "读方（{@code RedisFrameStore#readFrom} 的双读）"与"写方/续期/删除方（本类）"必须是<b>同一个键</b>，
	 * 否则兼容期历史帧既读不到、也永不过期（B23L1 批① 收口：批①首版曾因两侧各自拼名字留下这个缺口）。
	 */
	public String eventsKey(String runId) {
		return runScopedKey(EVENTS_PREFIX, runId);
	}

	/** 已发放最大业务序号的键名（{@code ai:seq:{run:<runId>}}；诊断/取证可直读）。 */
	public String seqKey(String runId) {
		return runScopedKey(SEQ_PREFIX, runId);
	}

	/** 请求帧已发位的 HASH 键名（{@code ai:req:{run:<runId>}}）。 */
	public String reqKey(String runId) {
		return runScopedKey(REQ_PREFIX, runId);
	}

	/** 决策 guard / WAL 的 HASH 键名（{@code ai:decided:{run:<runId>}}）。 */
	public String decidedKey(String runId) {
		return runScopedKey(DECIDED_PREFIX, runId);
	}

	/**
	 * 续跑权租约键名（{@code ai:lease:run:{run:<runId>}}；批②b 的 ResumeService 接线用同一个入口）。
	 *
	 * <p>租约只进<b>单键</b>脚本（ACQUIRE/RENEW/VERIFY/RELEASE），本不需要同槽；带 tag 是因为它属于
	 * {@link #deleteRun} 的"一轮 13 键"—— 同 tag 才能把轮次回收收成<b>一条</b>原子的 {@code DEL}。
	 * 键形里含 runId 之外的字符，反解必须走 {@link #runIdFromLeaseKey(String)}（认新键形，
	 * 迁移期也认旧裸形态），不要在别处 {@code substring} 猜。
	 */
	public String runLeaseKey(String runId) {
		return runScopedKey(LEASE_RUN_PREFIX, runId);
	}

	/**
	 * 本轮<b>取号计数器</b>键名：{@code ai:lease:epoch:{run:<runId>}}（与租约键同槽）。
	 *
	 * <p>为什么需要它（评审 I-1）：fencing 比较的是"这一轮的 epoch 与这一轮的 fence"，
	 * 只有<b>与租约键同槽</b>的计数器才能被塞进抢租脚本的同一个原子步里；号与占用一旦分开，
	 * 迟到者就可能拿到比已落库 fence 更小的号，之后每次写回都被 {@code STALE_EPOCH} 拒 ——
	 * fail-closed 变成永久卡死。
	 *
	 * <p>与 {@link LeaseService#EPOCH_PREFIX} 是同一个键（LeaseService 按租约键的 hash tag 推导），
	 * 本方法只是给"按 runId 取键"的调用方（deleteRun / 续期 / 运维面）一个入口；
	 * 两者一致由 {@code ClusterSlotTest} 直接断言，避免各拼一份。
	 *
	 * <h2>迁移口径（评审 N-1 后）</h2>
	 * 抢租脚本是<b>三键</b>的（租约 / 计数器 / fence）：取号时会与 fence 对账，号 ≤ fence 就抬到
	 * {@code fence+1}。所以<b>搬运方不需要预置任何计数/全局 epoch</b> —— 哪怕只搬 fence 而计数器是空的
	 * （或反之），下一次 acquire 都会当场自愈。想彻底避开这条自愈路径，最省事的做法仍是
	 * "fence 与计数器都不搬"（两者都从 0 起，无跨对象不变量）。
	 */
	public String runLeaseEpochKey(String runId) {
		return runScopedKey(LeaseService.EPOCH_PREFIX, runId);
	}

	/**
	 * 从续跑租约键反解 runId（{@code ConfirmGate#runIdOf} 的唯一取用口）。
	 *
	 * <p>认两种形态：
	 * <ul>
	 * <li>新键形 {@code ai:lease:run:{run:<runId>}} —— 取 tag 内层（去 {@code run:} 前缀）；</li>
	 * <li>旧裸键形 {@code ai:lease:run:<runId>} —— 原样返回前缀之后的部分。
	 * <b>这是纯防御分支，当前进程内不可达</b>（评审 Minor 8）：租约对象不跨进程传递，新进程只会
	 * 拿自己 {@code runLeaseKey()} 拼出来的 tagged 键。留着它是为了"键形再过一次渡"时不至于
	 * 把未识别的键误判成 runId（判据退化比误释放安全）。误解析风险也可忽略：runId 是 UUID，
	 * 不会自带 {@code {run:}} 形状。</li>
	 * </ul>
	 *
	 * @return runId；键不匹配/为空返回 {@code null}（调用方按"没有可靠判据"处理）
	 */
	public static String runIdFromLeaseKey(String key) {
		if (key == null || !key.startsWith(LEASE_RUN_PREFIX)) {
			return null;
		}
		String tail = key.substring(LEASE_RUN_PREFIX.length());
		int open = tail.indexOf("{run:");
		if (open >= 0 && tail.endsWith("}")) {
			String inner = tail.substring(open + "{run:".length(), tail.length() - 1);
			return inner.isEmpty() ? null : inner;
		}
		return tail.isEmpty() ? null : tail;
	}

	/** fence 键名（{@code ai:fence:run:{run:<runId>}}；与快照同槽 = FENCED_SET 的前提）。 */
	public String fenceKey(String runId) {
		return runScopedKey(FENCE_RUN_PREFIX, runId);
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
		return create(runId, sessionId, context, history, codec, providerId, modelId, null);
	}

	/**
	 * 建立一轮的外置快照并<b>冻结本轮的生效参数</b>（L-26）。
	 *
	 * <p>为什么冻结在"创建"这一笔：同一 run 的续跑可能落在几分钟甚至几天之后，此间用户可能改了
	 * 全局默认温度/默认模型。续跑重算会让同一轮前后半段跑在不同参数上，而历史与 {@code done.model}
	 * 都说"同一轮"。因此生效值随第一笔快照固化，续跑只读不改。
	 *
	 * <p><b>{@code frozen = null} = 不冻结</b>（旧签名的行为，写出的快照 {@code paramsVersion=0}）——
	 * 该旧行为供既有用例与"批③ 之前的调用方"使用；生产路径（{@code ResilientChatService#chat}）
	 * 永远传非 null（<b>全 null 的 FrozenParams 也是一次冻结</b>：null 本身就是当时的生效值）。
	 *
	 * @param frozen 生效参数（可为全部 null）
	 */
	public RunSnapshot create(String runId, String sessionId, AiContext context, List<Message> history,
			MessageJsonCodec codec, Long providerId, String modelId, RunSnapshot.FrozenParams frozen) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(sessionId);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setContext(context == null ? AiContext.empty().toMap() : context.toMap());
		snapshot.setMessageJson(codec.serializeAll(history));
		snapshot.setProviderId(providerId);
		snapshot.setModelId(modelId);
		snapshot.freezeParams(frozen);
		snapshot.setCreatedBy(this.instanceId);
		snapshot.setCreatedAtMs(System.currentTimeMillis());
		save(snapshot);
		this.redis.opsForSet().add(RUN_INDEX, runId);
		if (sessionId != null && !sessionId.isBlank()) {
			String sessionRunsKey = sessionRunsKey(sessionId);
			try {
				this.redis.opsForSet().add(sessionRunsKey, runId);
				this.redis.expire(sessionRunsKey, ttlFor(runId));
			}
			catch (Exception ex) {
				// 可选索引只是加速面：写失败不影响本轮，销毁仍回退全局索引。
				log.warn("维护会话→run 索引失败 sessionId={} runId={}：{}", sessionId, runId, ex.getMessage());
			}
		}
		maybePruneRunIndex();
		return snapshot;
	}

	/**
	 * 写入轮次快照（唯一写口）。
	 *
	 * <h2>写权限（续租 fencing 收口批）</h2>
	 * 先问 {@link #runLeaseAuthority(String)}：
	 * <ul>
	 * <li>{@code HELD} ⇒ 走 Lua fence 写（{@code ai:fence:run:<runId>} 记"已落库的最大 epoch"，
	 *     本次 epoch 必须 {@code ≥ fence} 才允许落库，并在同一步把 fence 推到本次 epoch）。
	 *     陈旧 epoch（换主后的旧主人）因此写不进去 —— 拒绝只记 {@code STALE_EPOCH} 日志、不抛异常；</li>
	 * <li>{@code LOST} / {@code UNAVAILABLE} ⇒ <b>只读</b>：这一笔不落库、不释放租约、不动 session
	 *     索引，只留一条 WARN。<b>这才是"丢租后 A 的终态不得覆盖 B 的 DONE"的落点</b> ——
	 *     旧实现把 {@code heldRunEpoch == 0} 当"无租约 ⇒ plain SET"，而丢租后视图正好是 0；</li>
	 * <li>{@code NEVER_HELD} ⇒ 保留红线 E3 的 plain {@code SET}（无租约设施 / 首轮 / 单实例降级），
	 *     TTL 仍是 {@code app.ai.session-ttl}。"从未持租"必须与"持过但已丢"区分开，且只有前者
	 *     才允许普通写。</li>
	 * </ul>
	 *
	 * <p>写终态时 best-effort 释放本实例持有的续跑租约（{@link #releaseRunLease}）—— 正常收尾的轮
	 * 不该让下一个实例等 TTL。
	 *
	 * <h2>DONE 的取消态 CAS（W-30 跨实例窄守卫）</h2>
	 * 只有 {@code status=DONE} 的写多走一次状态 CAS：Lua 在<b>同一个原子步</b>里用 {@code cjson} 解析
	 * <b>当前</b>快照，若已是 {@code status=CANCELLED} 或 {@code cancelled=true} ⇒ <b>拒写并直接返回</b>
	 * （不覆盖取消终态，也不执行后面的会话索引 / 租约副作用）。持租分支用
	 * {@link #FENCED_DONE_SET_SCRIPT}（原来的 fence 比较逐字保留，且在同一脚本内），无租约分支用
	 * {@link #DONE_SET_IF_NOT_CANCELLED_SCRIPT}。键、TTL、fence 语义都不变，不新增键、不需要迁移；
	 * 其它状态（RUNNING / SUSPENDED / CANCELLED / FAILED …）的写路径逐字不变，普通 DONE 照常落库。
	 * <p><b>边界</b>：这是"快照状态不得从取消态进 DONE"的原子守卫，<b>不是</b>分布式事务 ——
	 * DB 历史 append 不在这一个原子步内（见 {@code ResilientChatService#finishSuccess} 的落库后复读）。
	 */
	public void save(RunSnapshot snapshot) {
		snapshot.setUpdatedAtMs(System.currentTimeMillis());
		String runId = snapshot.getRunId();
		// 续租 fencing 收口批：写权限由 authority 判定，不再用 epoch==0 当"可写"。
		// LOST（确定丢租）/ UNAVAILABLE（设施异常，无法确认）⇒ 只读：这一笔不落库。
		LeaseService.LeaseAuthority authority = runLeaseAuthority(runId);
		if (authority.readOnly()) {
			warnReadOnly(runId, authority, "轮次快照");
			return;
		}
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
			boolean done = RunSnapshot.DONE.equals(snapshot.getStatus());
			boolean written = done ? fencedDoneSet(runId, json, epoch) : fencedSet(runId, json, epoch);
			if (!written) {
				if (done) {
					log.warn("DONE 拒写轮次快照 runId={} epoch={}（fence 陈旧或当前快照已是取消态："
							+ "不覆盖 CANCELLED，也不改会话索引/租约）", runId, epoch);
				}
				else {
					log.warn("STALE_EPOCH 拒写轮次快照 runId={} epoch={}（fence 已推进到更高 epoch："
							+ "另一实例已接管本轮的续跑权，本实例的写回作废）", runId, epoch);
				}
				return;
			}
		}
		else {
			warnIfFenceIsAhead(runId);
			try {
				if (RunSnapshot.DONE.equals(snapshot.getStatus())) {
					if (!doneSetIfNotCancelled(runId, json)) {
						log.warn("DONE 拒写轮次快照 runId={}（当前快照已是取消态：不覆盖 CANCELLED，"
								+ "也不改会话索引/租约）", runId);
						return;
					}
				}
				else {
					this.redis.opsForValue().set(runKey(runId), json, ttlFor(runId));
				}
			}
			catch (Exception ex) {
				throw new AiStateUnavailableException("无法写入轮次快照 " + runId, ex);
			}
		}
		// T2b-D#1：快照持久化成功后联动 session → current 轮索引（全部走本方法这一唯一通道，
		// 不会漏分支）。批G · G1：**RUNNING 也写** —— 纯文本轮从不 SUSPENDED，旧口径下
		// `/runs/current` 对该轮恒 `found:false`，刷新后前端没有任何"这一轮还在跑"的可发现面
		// （实证 A/A3/B/C2：整条回答静默消失）。终态照旧清除（取消后不留索引）。
		// T3-7（A14）：终态分支改调统一判定函数（不再散点比对字面量）。
		String status = snapshot.getStatus();
		if (RunSnapshot.SUSPENDED.equals(status) || RunSnapshot.RUNNING.equals(status)) {
			markSessionCurrent(snapshot.getSessionId(), runId);
		}
		else if (RunSnapshot.isTerminal(status)) {
			clearSessionCurrent(snapshot.getSessionId(), runId);
			releaseRunLease(runId);
		}
		// 会话→run 索引随快照滑动续期：挂起可长达一个 session-ttl，索引不能先于快照消失。
		// 失败只影响快路径（销毁仍有全局回退），不阻断本轮。
		String sessionId = snapshot.getSessionId();
		if (sessionId != null && !sessionId.isBlank()) {
			try {
				this.redis.expire(sessionRunsKey(sessionId), ttlFor(runId));
			}
			catch (Exception ex) {
				log.debug("续期会话→run 索引失败 sessionId={} runId={}：{}", sessionId, runId, ex.getMessage());
			}
		}
	}

	/**
	 * plain SET 分支（{@code NEVER_HELD} 且设施自证正常）的旁路观测（M2）。
	 *
	 * <p>不持租却要写、而 {@code ai:fence:run:<runId>} 已经推进到更高 epoch —— 典型成因是
	 * "本实例从未参与这一轮的租约，但轮次已被别处接管过"。按"从未持租 + 设施正常 ⇒ 原普通写"
	 * 仍照写（单实例/无租约语义路径不能被旁路观测挡死），但这种情况值得 WARN：它意味着这一笔写回
	 * 在语义上属于"没有续跑权的写"。
	 *
	 * <p><b>注意</b>：{@code LOST} / {@code UNAVAILABLE} 在 {@link #save} 入口就已返回，到不了这里；
	 * 本方法只服务"确实没有租约语义"的那条分支。
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
			throw new AiStateUnavailableException("无法写入轮次快照 " + runId, ex);
		}
	}

	/**
	 * DONE 的持租写：fence 校验与 {@link #fencedSet} 逐字相同，外加"当前快照已是取消态 ⇒ 拒写"
	 * （同一脚本内 {@code cjson} 解析，见 {@link #FENCED_DONE_SET_SCRIPT}）。
	 *
	 * @return false = 陈旧 epoch <b>或</b> 当前快照是取消态（两者都不落库、不改索引）
	 */
	private boolean fencedDoneSet(String runId, String json, long epoch) {
		Duration ttl = ttlFor(runId);
		try {
			Long result = this.redis.execute(FENCED_DONE_SET_SCRIPT, List.of(runKey(runId), fenceKey(runId)),
					json, String.valueOf(epoch), String.valueOf(ttl.toMillis()));
			return result != null && result > 0L;
		}
		catch (Exception ex) {
			throw new AiStateUnavailableException("无法写入轮次快照 " + runId, ex);
		}
	}

	/**
	 * DONE 的无租约写（E3 plain 分支）：同一键上"当前快照已是取消态 ⇒ 拒写"，否则普通 {@code SET PX}。
	 * 不持租的路径原先没有任何原状态检查（也是 W-30 现场 wipe 掉 CANCELLED 的那一条），这里用一条
	 * 单键脚本把"读-判-写"合成一个原子步，不引入新键。
	 *
	 * @return false = 当前快照是取消态（不落库、不改索引）
	 */
	private boolean doneSetIfNotCancelled(String runId, String json) {
		Duration ttl = ttlFor(runId);
		Long result = this.redis.execute(DONE_SET_IF_NOT_CANCELLED_SCRIPT, List.of(runKey(runId)), json,
				String.valueOf(ttl.toMillis()));
		if (result == null) {
			// 生产脚本恒回 0/1；null 只可能来自"脚本没被执行"（替身未建模 / 包装层无返回值）。
			// 不把"没判"折成"取消"（那会让一次成功轮的终态写凭空消失），按旧口径普通写。
			this.redis.opsForValue().set(runKey(runId), json, ttl);
			return true;
		}
		return result > 0L;
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

	/** 会话→全部 runId 的轻量索引键名（{@code ai:session:runs:<sessionId>}）。 */
	public String sessionRunsKey(String sessionId) {
		return SESSION_RUNS_PREFIX + sessionId;
	}

	/**
	 * 本会话候选 runId（只读，不触发全量快照反序列化）。
	 *
	 * <p>空集 = 没有索引/老数据；调用方（会话销毁）必须回退 {@code ai:runs} 全局扫描，
	 * 不能把"索引为空"当成"没有轮次"。
	 */
	public Set<String> sessionRunIds(String sessionId) {
		return sessionRunIndex(sessionId).runIds();
	}

	/**
	 * 会话→run 索引的快照（含“是否已完整回填”与“本次读取是否成功”）。
	 *
	 * @param runIds    已剔除 sentinel 的 runId 集合；读取失败为空集
	 * @param complete  是否看到 {@link #SESSION_RUNS_COMPLETE} 回填标记
	 * @param readable  本次读取是否成功（false = 调用方必须回退全局，不得按空集处理）
	 */
	public record SessionRunIndex(Set<String> runIds, boolean complete, boolean readable) {
	}

	/**
	 * 读会话→run 索引并区分“空”“未回填”“读失败”三种状态。
	 *
	 * <p>为什么不能只返回集合：老数据索引非空但只含升级后的新 run —— 只按“非空”判断会漏删老 run。
	 * 只有 {@code complete=true} 才允许信任快路径；读取失败必须让调用方回退全局。
	 */
	public SessionRunIndex sessionRunIndex(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			return new SessionRunIndex(Set.of(), false, true);
		}
		try {
			Set<String> members = this.redis.opsForSet().members(sessionRunsKey(sessionId));
			if (members == null || members.isEmpty()) {
				return new SessionRunIndex(Set.of(), false, true);
			}
			boolean complete = members.contains(SESSION_RUNS_COMPLETE);
			Set<String> ids = new java.util.LinkedHashSet<>(members);
			ids.remove(SESSION_RUNS_COMPLETE);
			return new SessionRunIndex(Set.copyOf(ids), complete, true);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.C1_RUN_INDEX_PRUNE, ex);
			log.warn("读取会话→run 索引失败 sessionId={}（调用方必须回退全局扫描）：{}", sessionId, ex.getMessage());
			return new SessionRunIndex(Set.of(), false, false);
		}
	}

	/**
	 * 全局枚举成功后回填本会话索引 + 完整性标记（只在候选非空时调用）。
	 *
	 * <p>顺序刻意是 <b>SADD runIds → EXPIRE → SADD sentinel</b>：标记永远不早于 TTL 出现，
	 * 避免“标记永久存在、索引已过期”的假完整。写失败只 WARN（本次销毁仍用刚枚举出的候选），
	 * 下一轮会再次回退全局，不会误信不完整索引。
	 */
	public void backfillSessionRuns(String sessionId, Set<String> runIds) {
		if (sessionId == null || sessionId.isBlank() || runIds == null || runIds.isEmpty()) {
			return;
		}
		String key = sessionRunsKey(sessionId);
		try {
			String[] values = runIds.stream()
				.filter(id -> id != null && !id.isBlank() && !SESSION_RUNS_COMPLETE.equals(id))
				.toArray(String[]::new);
			if (values.length == 0) {
				return;
			}
			this.redis.opsForSet().add(key, values);
			this.redis.expire(key, this.properties.getSessionTtl());
			this.redis.opsForSet().add(key, SESSION_RUNS_COMPLETE);
		}
		catch (Exception ex) {
			log.warn("回填会话→run 索引失败 sessionId={}（本轮仍用全局枚举候选；下轮继续回退）：{}", sessionId,
					ex.getMessage());
		}
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

	/** 当前轮终态后回指同会话仍活跃的最新一轮；没有才删除，且只处理仍指向自己的索引。 */
	public void clearSessionCurrent(String sessionId, String runId) {
		if (sessionId == null || runId == null) {
			return;
		}
		try {
			String key = sessionCurrentKey(sessionId);
			String value = this.redis.opsForValue().get(key);
			if (!runId.equals(value)) {
				return;
			}
			SessionRunIndex index = sessionRunIndex(sessionId);
			if (!index.readable()) {
				log.warn("终态后复核 session→current 失败 sessionId={} runId={}（保留原索引，不猜活跃轮）",
						sessionId, runId);
				return;
			}
			RunSnapshot fallback = null;
			for (String candidateId : index.runIds()) {
				if (runId.equals(candidateId)) {
					continue;
				}
				RunSnapshot candidate = get(candidateId);
				if (candidate == null || (!RunSnapshot.SUSPENDED.equals(candidate.getStatus())
						&& !RunSnapshot.RUNNING.equals(candidate.getStatus()))) {
					continue;
				}
				if (fallback == null || candidate.getUpdatedAtMs() > fallback.getUpdatedAtMs()
						|| (candidate.getUpdatedAtMs() == fallback.getUpdatedAtMs()
								&& RunSnapshot.SUSPENDED.equals(candidate.getStatus())
								&& !RunSnapshot.SUSPENDED.equals(fallback.getStatus()))) {
					fallback = candidate;
				}
			}
			if (fallback == null) {
				this.redis.delete(key);
			}
			else {
				this.redis.opsForValue().set(key, fallback.getRunId(), sessionCurrentTtl());
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

	/**
	 * 读一轮快照（{@code ai:run:{run:<runId>}}）。
	 *
	 * <h2>异常分类（P1-1）</h2>
	 * <ul>
	 * <li><b>Redis 调用失败</b>（断连/超时/命令被拒）⇒ {@link AiStateUnavailableException}
	 * （{@code AI_REDIS_UNAVAILABLE} ⇒ 全局处理器回 <b>503</b>，客户端可重试）。这条 GET 曾写在
	 * {@code try} 之外，一次 Redis 抖动会让 {@code /events/{runId}} 与 {@code /runs/current} 的第一跳
	 * 直接落到兜底 handler 变成 <b>500</b> —— 服务端故障被报成"不可重试"的错误；</li>
	 * <li><b>值损坏</b>（JSON 解析失败）仍抛 {@link IllegalStateException}：那是数据问题，
	 * 报成"稍后重试"只会让前端无意义地重试。两类必须分开，不能一个 catch 全兜；</li>
	 * <li>键不存在返回 {@code null}（"没有这一轮"不是故障）。</li>
	 * </ul>
	 *
	 * <p>与同类读口（{@code pendingRaw} / {@code decidedStatus} / {@code fenceEpoch}）的分类保持一致；
	 * {@link AiStateUnavailableException} is-a {@code IllegalStateException}，既有 catch 分支不受影响。
	 */
	public RunSnapshot get(String runId) {
		String raw;
		try {
			raw = this.redis.opsForValue().get(runKey(runId));
		}
		catch (Exception ex) {
			throw new AiStateUnavailableException("无法读取轮次快照 " + runId + "（Redis 不可用）", ex);
		}
		if (raw == null) {
			return null;
		}
		try {
			return this.mapper.readValue(raw, RunSnapshot.class);
		}
		catch (Exception ex) {
			// 值损坏 = 数据问题，不是依赖不可用：保持 IllegalStateException（不得转成 503）
			throw new IllegalStateException("无法读取轮次快照 " + runId, ex);
		}
	}

	/**
	 * <b>快照归属的轻量读取（RT-28 成本收口）</b>：只回答"这一轮属于哪个会话"，<b>不</b>物化
	 * {@link RunSnapshot}。
	 *
	 * <h2>为什么不能先 {@link #get(String)} 再读 sessionId</h2>
	 * {@code ai:runs} 的成员可能是<b>海量无关轮次</b>。{@code get()} 会把整份快照（含整窗
	 * {@code messageJson} 的每一条消息）反序列化成对象图 —— 会话销毁的"索引缺口复核"要为每个无关
	 * 轮次付出这份 CPU/内存（O(总轮次 × 快照大小)），而真正要判的只是一个字段。
	 *
	 * <p>本方法只取<b>原始 JSON</b> 并<b>流式</b>扫描<b>顶层</b>字段：找到 {@code sessionId} 立即返回
	 * （它在序列化顺序里紧跟 {@code runId}，通常只解析几十字节），不构造任何消息对象。
	 * 只认顶层字段名（按嵌套深度判断），因此 {@code context} 里同名的键不会被误当成归属。
	 *
	 * <h2>四态与失败口径（调用方必须 fail-closed）</h2>
	 * <ul>
	 * <li>{@link SnapshotOwner.State#ATTRIBUTED}：读到字符串型顶层 {@code sessionId}；</li>
	 * <li>{@link SnapshotOwner.State#MISSING}：<b>键已消失</b>（索引里的死成员）—— 这是"没有快照"，
	 *     与"有快照但读不出归属"必须区分：销毁侧对点名（会话门/当前轮索引）的死成员保留
	 *     {@code UNKNOWN}/active 旧语义，对其余死成员跳过；</li>
	 * <li>{@link SnapshotOwner.State#ABSENT}：键在、JSON 合法，但<b>没有可用的 sessionId</b>
	 *     （旧快照没有该字段，或显式 {@code null}）—— 只能"无法归属"，<b>不得</b>因此把它当死成员
	 *     删掉 13 键（那可能是他会话、甚至本会话的老轮次），也不得断言"属于别的会话"；</li>
	 * <li>{@link SnapshotOwner.State#UNREADABLE}：Redis 读失败、JSON 坏值、或 {@code sessionId}
	 *     存在但不是字符串/不是 null（数字/对象/数组…）—— <b>不得</b>当作"另一个会话"
	 *     （那会漏删本会话的键），也不得当"本会话"（那可能删到他会话）。枚举侧必须中止，复核侧记
	 *     可重试错误。</li>
	 * </ul>
	 *
	 * <p>不抛异常：判定必须总能给出结论（与 {@code authority} 同姿态）；错误事实放进返回值，
	 * 由调用方折算成"中止枚举/可重试错误"。
	 */
	public SnapshotOwner snapshotOwner(String runId) {
		if (runId == null || runId.isBlank()) {
			return SnapshotOwner.missing();
		}
		String raw;
		try {
			raw = this.redis.opsForValue().get(runKey(runId));
		}
		catch (RuntimeException ex) {
			log.warn("读取轮次快照归属失败 runId={}（按不可读处理，调用方须 fail-closed）：{}", runId, ex.getMessage());
			return SnapshotOwner.unreadable();
		}
		if (raw == null) {
			return SnapshotOwner.missing();
		}
		try {
			return topLevelOwner(raw);
		}
		catch (Exception ex) {
			// 坏值 = 数据问题：不得猜成"他会话"，交给调用方中止/记可重试错误。
			log.warn("轮次快照坏值 runId={}（归属不可读，调用方须 fail-closed）：{}", runId, ex.getMessage());
			return SnapshotOwner.unreadable();
		}
	}

	/**
	 * 顶层 {@code sessionId} 的归属判定（流式；只认顶层字段名，因此 {@code context} 里的同名键不会被误读）。
	 *
	 * <p>四态映射：字符串 ⇒ {@code ATTRIBUTED}；字段缺失或 {@code null} ⇒ {@code ABSENT}；
	 * 非字符串非 null（数字/对象/数组/布尔）⇒ {@code UNREADABLE}（结构不符 RunSnapshot，
	 * 旧实现会被 Jackson 强转成字符串后再被当"他会话"静默跳过）；顶层不是 JSON 对象 ⇒ {@code UNREADABLE}。
	 */
	private SnapshotOwner topLevelOwner(String rawJson) throws java.io.IOException {
		try (JsonParser parser = this.mapper.getFactory().createParser(rawJson)) {
			if (parser.nextToken() != JsonToken.START_OBJECT) {
				return SnapshotOwner.unreadable();
			}
			while (parser.nextToken() == JsonToken.FIELD_NAME) {
				String name = parser.currentName();
				JsonToken value = parser.nextToken();
				if ("sessionId".equals(name)) {
					if (value == JsonToken.VALUE_STRING) {
						return SnapshotOwner.attributed(parser.getText());
					}
					if (value == JsonToken.VALUE_NULL) {
						return SnapshotOwner.absent();
					}
					return SnapshotOwner.unreadable();
				}
				// 只跳过当前字段的子树：嵌套对象里的同名键不会被误读成归属。
				parser.skipChildren();
			}
			return SnapshotOwner.absent();
		}
	}

	/**
	 * 快照归属读取结果（四态，见 {@link #snapshotOwner(String)}）。
	 *
	 * @param state 见 {@link State}
	 * @param sessionId 仅 {@link State#ATTRIBUTED} 时非空
	 */
	public record SnapshotOwner(State state, String sessionId) {

		/** 归属读取的四种结论。 */
		public enum State {

			/** 读到字符串型顶层 sessionId。 */
			ATTRIBUTED,
			/** 键在、JSON 合法但没有可用 sessionId（旧快照/显式 null）：无法归属。 */
			ABSENT,
			/** 键已消失（索引里的死成员）。 */
			MISSING,
			/** 读失败、坏值、或 sessionId 不是字符串/不是 null：不可知（调用方必须 fail-closed）。 */
			UNREADABLE

		}

		static SnapshotOwner attributed(String sessionId) {
			return new SnapshotOwner(State.ATTRIBUTED, sessionId);
		}

		static SnapshotOwner absent() {
			return new SnapshotOwner(State.ABSENT, null);
		}

		static SnapshotOwner missing() {
			return new SnapshotOwner(State.MISSING, null);
		}

		static SnapshotOwner unreadable() {
			return new SnapshotOwner(State.UNREADABLE, null);
		}

	}

	/**
	 * 池饱和回滚：删掉本轮<b>需要同生同死</b>的 13 个业务状态键（评审 Minor 1）。
	 *
	 * <p><b>不是"这一轮的全部键"</b>：{@code ai:beat:<runId>}（心跳戳）、
	 * {@code ai:activity:<runId>}（业务活动锚点）与 {@code ai:cancel:<runId>}（取消标志）刻意不在其中
	 * —— 前两者是观测/判据面（裸前缀、单键命令）、后者要活到驱动线程看见它，各自按 TTL 走；
	 * {@code ai:lock:* }（taskId 维度）更不在，资源锁的寿命与 run 的生死无关。
	 *
	 * <p><b>删后语义 = 原子性恢复</b>（评审 Minor 3）：13 键全部带 {@code {run:<runId>}} tag ⇒ 同槽 ⇒
	 * 这一条 {@code DEL} 在真集群里是"一条命令、原子生效"。注意这不是"不加 tag 就会 CROSSSLOT"——
	 * 真实 Lettuce 的 {@code del(Iterable)} 会按槽分区、<b>静默拆成 N 条</b>，故障形态是
	 * <b>原子性丢失</b>（删到一半崩 = 半截状态），不是报错；测试替身对多键跨槽抛异常比真客户端更严，
	 * 属刻意选择（把"悄悄不原子"变成可见的红）。
	 *
	 * <p>13 键 = 快照 / 待决 / 认领 / 台账 / 幂等索引 / 兼容 LIST / seq / 事件 Stream / 续跑租约 /
	 * fence / req 位 / 决策 guard / <b>取号计数器</b>。最后那个是 per-run 计数器（无业务语义，
	 * 但不删就会随轮次永久留存）。
	 */
	public void deleteRun(String runId) {
		// 先放掉本进程持有的租约：删键之后脚本再比对就会扑空，进程内视图会成为幽灵所有权
		releaseRunLease(runId);
		// 会话→run 轻量索引需要 sessionId；删键前读一次快照（读不到只留 stale 成员，销毁方仍可过滤）。
		RunSnapshot snapshot = null;
		try {
			snapshot = get(runId);
		}
		catch (RuntimeException ex) {
			log.debug("删除轮次前读快照失败 runId={}（会话索引成员留待 TTL/对账清理）：{}", runId, ex.getMessage());
		}
		// B23L1 批②a：租约 / fence / req 位 / decided guard 同属"本轮的全部键"——
		// 留着它们会让下一轮同 id 的运行继承前任的 fence 与决策（且 fence 只增不减 ⇒ 新租约写不进去）。
		// 批③a：幂等索引随台账一起删（索引是台账的指针，台账没了它只会指向空气）。
		// 注意：ai:lock:*（LeaseKeys，按 taskId 记）**不在这里** —— 资源锁的寿命与 run 的生死无关，
		// 由 app.ai.lease.resource-ttl 自清；在轮次回收里删锁会把别人的业务互斥一起放掉。
		// B23L1 批①（R1-05 + 评审 I-1）：13 键全部带 {run:<runId>} tag ⇒ 同槽 ⇒ 这一条 DEL 在 Cluster 下
		// 仍是"一条命令、原子生效"（跨槽的 DEL 会被客户端拆成 N 条，原子性随之消失）。
		// 取号计数器与 fence 必须一起删：留下计数器而删掉 fence 只是浪费一个键；反过来（删计数器留 fence）
		// 才会让下一任租约从 1 起算、被残留的 fence 拒死 —— 所以两者在同一条命令里。
		this.redis.delete(List.of(runKey(runId), pendingKey(runId), claimKey(runId), ledgerKey(runId),
				idemKey(runId), eventsKey(runId), seqKey(runId), xeventsKey(runId), runLeaseKey(runId),
				fenceKey(runId), reqKey(runId), decidedKey(runId), runLeaseEpochKey(runId)));
		this.redis.opsForSet().remove(RUN_INDEX, runId);
		if (snapshot != null && snapshot.getSessionId() != null && !snapshot.getSessionId().isBlank()) {
			try {
				this.redis.opsForSet().remove(sessionRunsKey(snapshot.getSessionId()), runId);
			}
			catch (Exception ex) {
				// 只留 stale 成员：会话销毁会按快照归属过滤，不会误删他会话。
				log.warn("清理会话→run 索引成员失败 sessionId={} runId={}（stale 成员待 TTL/对账）：{}",
						snapshot.getSessionId(), runId, ex.getMessage());
			}
		}
	}

	// ── 续跑权租约（B23L1 批②a；ResumeService 接线归批②b） ──────────────────

	/**
	 * 抢本轮的续跑权租约（{@code ai:lease:run:{run:<runId>}}，TTL = {@code app.ai.lease.ttl}）。
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
		// 三键同槽（租约 / 计数器 / fence）：取号时与 fence 对账 ⇒ 计数器先死也能自愈（评审 N-1）。
		return facility.acquireFenced(runLeaseKey(runId), fenceKey(runId), this.properties.getLease().getTtl());
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

	/**
	 * 本轮<b>写权限的权威判定</b>（续租 fencing 收口批）：{@code HELD / LOST / UNAVAILABLE / NEVER_HELD}。
	 *
	 * <p>这是 {@link #save}、出帧（{@code SseChatEmitter}）、认领（{@link #tryClaimExecution}）与
	 * 台账（{@link #appendLedger}）共用的唯一判据 —— 四处各写一套 {@code epoch == 0} 判断，
	 * 就会出现"快照拒写、但帧还在落档"的半只读状态。
	 *
	 * <p>无租约设施（单测/进程内替身/降级装配）⇒ {@code NEVER_HELD}（没有租约语义，保持原普通写）。
	 */
	public LeaseService.LeaseAuthority runLeaseAuthority(String runId) {
		LeaseService facility = leaseService();
		if (facility == null) {
			return LeaseService.LeaseAuthority.NEVER_HELD;
		}
		LeaseService.LeaseAuthority authority = facility.authority(runLeaseKey(runId));
		// 替身/测试子类可能给出 null（"没意见"）：按"无租约语义"放行，不让判定本身 NPE。
		return authority == null ? LeaseService.LeaseAuthority.NEVER_HELD : authority;
	}

	/** 只读拒绝的节流 WARN（每个 runId 第一条点名动作，其后 debug）。 */
	private void warnReadOnly(String runId, LeaseService.LeaseAuthority authority, String action) {
		if (this.readOnlyWarned.size() > FENCE_AHEAD_WARNED_CAP) {
			this.readOnlyWarned.clear();
		}
		String detail = authority == LeaseService.LeaseAuthority.LOST
				? "确定丢租（续租返回 0 / 归属校验失败：另一实例已接管）"
				: "租约设施不可用（无法确认是否仍持有）";
		if (this.readOnlyWarned.add(runId)) {
			log.warn("续跑权只读：拒绝{} runId={}（{}）。本实例不再推进这一轮，恢复/接管由新主人负责", action,
					runId, detail);
		}
		else {
			log.debug("续跑权只读：拒绝{} runId={}（{}，本 run 已告警过）", action, runId, detail);
		}
	}

	/**
	 * <b>写门前置（RT-21）</b>：{@code authority} 回答"本实例的租约视图还在不在"，但回答不了
	 * "<b>fence 是否已被推进到更高 epoch</b>"。后者是第三种形态 —— 租约 TTL 到期、新实例接管并
	 * 写过快照（fence=新 epoch），而本实例的续租还没到点（续租间隔内）时，本实例仍是
	 * {@code HELD}：此时再落帧/写台账/取号，就是"旧驱动与新主人同时推进"。
	 *
	 * <p>判据 = {@code fence <= 本进程 epoch}（与 {@code tryClaimExecution} 的 I5 复核同源）。
	 * {@code epoch == 0}（无租约设施 / 从没持租）保持 E3 的普通写语义，<b>不</b>读 fence。
	 *
	 * <p>fence 读不出来 ⇒ <b>不放行</b>（无法证明"我仍是主人"；与认领侧的 fail-closed 同口径）。
	 *
	 * <p><b>成本</b>：持租时每笔多一次单键 {@code GET}。出帧路径本来就有 XADD/INCR/PUBLISH
	 * 数跳，这里换的是正确性（RT-21）；不为此加进程内缓存 —— 缓存窗口内的复用恰好会放过
	 * 这段"HELD-but-fence-ahead"。
	 *
	 * <p><b>唯一写门</b>：本方法同时被 {@link com.example.configmgr.ai.run.ResilientChatService}
	 * 的"答案面"写口（DB 历史 / 记忆整窗）复用（RT-20）—— 帧、台账、序号、历史、记忆因此共享
	 * 同一套判据，不会出现"快照拒写、但答案已经落库"的半只读状态。
	 */
	public boolean permittedToWrite(String runId, String action) {
		LeaseService.LeaseAuthority authority = runLeaseAuthority(runId);
		if (authority.readOnly()) {
			warnReadOnly(runId, authority, action);
			return false;
		}
		long epoch = heldRunEpoch(runId);
		if (epoch <= 0L) {
			return true;
		}
		long fence;
		try {
			fence = fenceEpoch(runId);
		}
		catch (RuntimeException ex) {
			warnFenceUnreadable(runId, action, ex);
			return false;
		}
		if (fence > epoch) {
			warnFenceStale(runId, action, epoch, fence);
			return false;
		}
		return true;
	}

	/** fence 已越过本进程 epoch 的拒写留痕（每个 runId 一条 WARN，其后 debug）。 */
	private void warnFenceStale(String runId, String action, long epoch, long fence) {
		if (this.fenceStaleWarned.size() > FENCE_AHEAD_WARNED_CAP) {
			this.fenceStaleWarned.clear();
		}
		if (this.fenceStaleWarned.add(runId)) {
			log.warn("续跑权已失效：拒绝{} runId={}（fence={} 已推进到本实例 epoch={} 之上：另一实例已接管本轮的"
					+ "续跑权，本实例只读恢复，不再落帧/写台账/取号）", action, runId, fence, epoch);
		}
		else {
			log.debug("续跑权已失效：拒绝{} runId={}（fence={} > epoch={}，本 run 已告警过）", action, runId,
					fence, epoch);
		}
	}

	/** fence 读不出来（Redis 抖动/不可用）⇒ 按"无法证明仍是主人"拒写，与认领侧 fail-closed 同口径。 */
	private void warnFenceUnreadable(String runId, String action, RuntimeException ex) {
		if (this.fenceStaleWarned.size() > FENCE_AHEAD_WARNED_CAP) {
			this.fenceStaleWarned.clear();
		}
		if (this.fenceStaleWarned.add(runId)) {
			log.warn("无法确认续跑权：拒绝{} runId={}（fence 读取失败，按不可确认处理）：{}", action, runId,
					ex.getMessage());
		}
		else {
			log.debug("无法确认续跑权：拒绝{} runId={}（已告警过）：{}", action, runId, ex.getMessage());
		}
	}

	/** 租约设施（可观测/批②b 接线用；无租约设施时为 null）。 */
	public LeaseService leaseService() {
		return this.leaseService;
	}

	/**
	 * 事件日志 Stream 键名（B23L1 批①；{@code ai:xevents:{run:<runId>}}）。
	 *
	 * <p>写入/读取在 {@link RedisFrameStore}，它调的是同一个拼接口
	 * （{@link #runScopedKey}({@code STREAM_PREFIX}, runId)）—— "写方（XADD/XRANGE）"与
	 * "续期/删除方（本类）"必须逐字同键，否则长挂起时事件日志失去续期、{@code deleteRun} 删不到真键。
	 */
	public String xeventsKey(String runId) {
		return runScopedKey(XEVENTS_PREFIX, runId);
	}

	/**
	 * 全部仍存活的 runId（索引里已过期的惰性剔除）。
	 *
	 * <p>与 {@link #pruneRunIndex()} 共用同一条"活/死"判据（快照键是否存在），差别只是本方法
	 * 还要返回活成员清单：启动扫描用它，因此启动一次就把索引与快照对齐。
	 */
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
		dropFromIndex(dead);
		// 本次已经做过一次全量"活/死"判定：把机会式清理的节流锚点前移，避免启动扫描之后
		// 紧接着的第一次 create 再全表扫一遍（两处判据同源，没必要重复）。
		this.indexPrunedAtMs.set(System.currentTimeMillis());
		live.sort(String::compareTo);
		return live;
	}

	/**
	 * {@code ai:runs} 索引的<b>惰性清理</b>（Redis 容量专项 A）：剔除快照键已不存在的成员，返回剔除条数。
	 *
	 * <h2>为什么不是"给索引一个 TTL"</h2>
	 * {@code ai:runs} 是 SET，<b>无法按成员过期</b>；对整键 {@code EXPIRE} 会把
	 * <b>活跃</b>轮次的发现面一起删掉 —— 挂起等人没有上限（确认门/生成式表单），一个挂起数天的
	 * 活轮在"索引不再被写"的窗口里就会消失，重启后无法自动续跑（比"索引留垃圾"坏得多）。
	 * 故只做按成员的剔除：判据 = 快照键 {@code ai:run:<runId>} 是否还在（与 {@link #allRunIds()} 同源）。
	 * 空 SET 由 Redis 自己删除（不存在"空索引键"），因此索引不会永久留一个空壳。
	 *
	 * <p><b>不做"终态即删"</b>：终态轮次可能还要被 {@code /events} 回放/诊断（索引是
	 * {@code ResumeService.listRuns} 的入口，快照 TTL 到期后才是真正的死亡）。清理只认
	 * "快照已不在"，不猜业务状态。
	 *
	 * <p><b>失败不抛</b>：清理是运维卫生动作，不能让它挡住 {@code create}（调用点在创建路径上）。
	 * 失败计入 {@link RedisDegradation#C1_RUN_INDEX_PRUNE} 并 WARN，下一拍重试。
	 *
	 * <p>成本 = 一次 {@code SMEMBERS} + 逐成员 {@code EXISTS}（O(索引大小)，全部单键命令 ⇒ Cluster 安全）。
	 * 调用方 {@link #create} 路径上带节流（{@code app.ai.stream.index-prune-interval}）。
	 */
	public int pruneRunIndex() {
		if (this.redis == null) {
			// 进程内替身（InMemoryRunStore）没有索引键面；保持"无索引可清"的语义，不记降级。
			return 0;
		}
		try {
			Set<String> ids = this.redis.opsForSet().members(RUN_INDEX);
			if (ids == null || ids.isEmpty()) {
				return 0;
			}
			List<String> dead = new ArrayList<>();
			for (String id : ids) {
				if (!Boolean.TRUE.equals(this.redis.hasKey(runKey(id)))) {
					dead.add(id);
				}
			}
			return dropFromIndex(dead);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.C1_RUN_INDEX_PRUNE, ex);
			log.warn("ai:runs 索引惰性清理失败（本轮跳过，残留项留待下一拍；不影响轮次本身）：{}", ex.toString());
			return 0;
		}
	}

	/** 剔除索引里的死成员（{@code SREM}；空 SET 由 Redis 自动消失）。返回剔除条数。 */
	private int dropFromIndex(List<String> dead) {
		if (dead == null || dead.isEmpty()) {
			return 0;
		}
		this.redis.opsForSet().remove(RUN_INDEX, dead.toArray());
		log.info("ai:runs 索引惰性清理：剔除 {} 条已无快照的 runId（索引项随快照 TTL 到期收敛）", dead.size());
		return dead.size();
	}

	/**
	 * 创建路径上的<b>机会式</b>索引清理（节流）：距上次清理不足 {@code index-prune-interval} 时直接返回。
	 *
	 * <p>为什么节流而不是每次都扫：{@code create} 在每次 {@code /chat} 的热路径上，而清理是
	 * O(索引大小) 的 {@code SMEMBERS + EXISTS}。{@code interval = 0} = 关闭机会式清理（只剩
	 * 启动扫描）；负值由启动期守卫拦下（{@link AiProperties#verifyStream()}）。
	 */
	private void maybePruneRunIndex() {
		Duration interval = this.properties == null || this.properties.getStream() == null ? null
				: this.properties.getStream().getIndexPruneInterval();
		if (interval == null || interval.isZero() || interval.isNegative()) {
			return;
		}
		long now = System.currentTimeMillis();
		long last = this.indexPrunedAtMs.get();
		if (now - last < interval.toMillis()) {
			return;
		}
		// CAS 抢占"这一拍由我来清"：多实例/并发 create 不必每人都扫一遍全表。
		if (!this.indexPrunedAtMs.compareAndSet(last, now)) {
			return;
		}
		pruneRunIndex();
	}

	// ── 挂起（硬规范①的落点） ──────────────────────────────────────────────

	/**
	 * 硬规范①：把挂起中的 {@code assistant(tool_calls)} 与每个待决条目先写进 Redis，
	 * 然后调用方才能进入阻塞等待。写失败必须抛出去 —— 宁可不挂起（回落官方执行），
	 * 也不能出现"阻塞住了但 Redis 里没有状态"。
	 */
	public void saveSuspended(String runId, String assistantContent, List<PendingToolCall> pendings,
			List<Message> history, MessageJsonCodec codec) {
		saveSuspended(runId, assistantContent, pendings, history, codec, null);
	}

	/**
	 * 硬规范①（推理元数据版）：把挂起中那条 assistant 的<b>完整 metadata</b> 一并外置。
	 *
	 * <p>为什么必须带上：续跑侧 {@code ResumeService#rebuildAssistant} 重建的是
	 * {@code assistant(tool_calls)}，只存正文/tool_calls 会让 {@code reasoningContent} 之外的
	 * 推理字段（{@code encrypted_content} / {@code signature} / {@code thinking} / 厂商私有键）
	 * 在这一轮续跑时全部丢失 —— 只认一个键就把协议绑死在某个 provider 上。
	 *
	 * <p>结构<b>不透明</b>：整份 map 原样写 JSON、原样读回，<b>不解析、不截断、不筛选键</b>。
	 *
	 * @param assistantMetadata 上游 assistant 的 metadata（null = 没有；空 map 与 null 不混同）
	 */
	public void saveSuspended(String runId, String assistantContent, List<PendingToolCall> pendings,
			List<Message> history, MessageJsonCodec codec, Map<String, Object> assistantMetadata) {
		if (!permittedToWrite(runId, "挂起快照")) {
			throw new AiStateUnavailableException("无法确认续跑权，拒绝写入挂起状态 " + runId);
		}
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
		snapshot.setAssistantMetadata(assistantMetadata == null ? null : new LinkedHashMap<>(assistantMetadata));
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
			throw new AiStateUnavailableException("无法写入待决条目 " + pending.getToolCallId(), ex);
		}
	}

	public List<PendingToolCall> pendings(String runId) {
		return readPendings(runId, false);
	}

	/**
	 * <b>非抛</b>整批读口（展示 / 诊断 / 取消收尾）：决策 guard 读不出来时返回<b>未合并 guard 的
	 * 原始条目</b>并计数，而不是让调用方整页 500 或缺条目。
	 *
	 * <p>为什么需要它：A6 的 fail-closed 只应作用于"回灌/决策"这类会改变状态的读；列表、诊断、
	 * 取消收尾若也抛，Redis 一次抖动就会让"取消"卡在中间（条目仍 PENDING）或让运维在最需要
	 * 诊断时看不到任何东西。调用方必须把本方法的结果视为<b>可能未收敛</b>的视图。
	 */
	public List<PendingToolCall> pendingsLenient(String runId) {
		return readPendings(runId, true);
	}

	private List<PendingToolCall> readPendings(String runId, boolean lenient) {
		Map<Object, Object> all;
		try {
			all = this.redis.opsForHash().entries(pendingKey(runId));
		}
		catch (Exception ex) {
			if (!lenient) {
				throw new AiStateUnavailableException("无法读取待决条目批次：" + runId, ex);
			}
			RedisDegradation.record(RedisDegradation.A6_PENDING_READ, ex);
			log.warn("读取待决条目失败 runId={}（非抛读口降级为空表，取消收尾/展示继续）：{}", runId,
					ex.getMessage());
			return List.of();
		}
		Map<Object, Object> markers = requestMarkers(runId);
		Map<Object, Object> decisions = lenient ? decisionMarkersLenient(runId) : decisionMarkers(runId);
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
		PendingToolCall pending = pendingRaw(runId, toolCallId);
		if (pending == null) {
			return null;
		}
		applyRequestMarker(pending, requestMarker(runId, toolCallId));
		// I2①：WAL 的读侧收敛（另一次往返）。单条读多一次 HGET 是刻意的 —— 这里回答的是
		// "这张卡现在到底算不算已决"，判错的代价是"已决的卡又被当成待人工输入"。
		applyDecisionMarker(pending, decisionMarker(runId, toolCallId));
		return pending;
	}

	/**
	 * <b>非抛</b>单条读口（确认门等待循环 / 诊断 / 取消收尾）：guard 读不出来时返回原始条目，
	 * 不把一次 Redis 抖动升级成等待线程退出。需要强一致判定的路径（回灌/决策）仍用 {@link #pending}。
	 */
	public PendingToolCall pendingLenient(String runId, String toolCallId) {
		PendingToolCall pending;
		try {
			pending = pendingRaw(runId, toolCallId);
		}
		catch (RuntimeException ex) {
			RedisDegradation.record(RedisDegradation.A6_PENDING_READ, ex);
			log.warn("读取待决条目失败 runId={} toolCallId={}（非抛读口降级为 null）：{}", runId, toolCallId,
					ex.getMessage());
			return null;
		}
		if (pending == null) {
			return null;
		}
		applyRequestMarker(pending, requestMarker(runId, toolCallId));
		applyDecisionMarker(pending, decisionMarkerLenient(runId, toolCallId));
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
		String raw;
		try {
			raw = (String) this.redis.opsForHash().get(pendingKey(runId), toolCallId);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.A6_PENDING_READ, ex);
			throw new AiStateUnavailableException("无法读取待决条目 " + toolCallId, ex);
		}
		if (raw == null) {
			return null;
		}
		try {
			return this.mapper.readValue(raw, PendingToolCall.class);
		}
		catch (Exception ex) {
			// JSON 损坏是数据错误，不是基础设施故障：保持原 IllegalStateException。
			throw new IllegalStateException("无法读取待决条目 " + toolCallId, ex);
		}
	}

	/**
	 * 单条目的 guard 值（null = 未决；读失败 <b>抛</b>，不得折成"未决策"）。
	 *
	 * <p><b>A6 fail-closed</b>：guard 读失败若按 null 处理，已决的卡会被当成"待人工输入"重新挂起
	 * （用户被重复问、审批语义漂移）。读成功且无该 field 才是真正的"未决策"。
	 */
	private Object decisionMarker(String runId, String toolCallId) {
		try {
			return this.redis.opsForHash().get(decidedKey(runId), toolCallId);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.A6_DECISION_GUARD_READ, ex);
			log.warn("读取决策 guard 失败 runId={} toolCallId={}（读侧 fail-closed：拒绝按未决策重建卡）：{}",
					runId, toolCallId, ex.getMessage());
			throw new AiStateUnavailableException("无法读取决策 guard " + toolCallId, ex);
		}
	}

	/** 非抛版单条 guard 读口（展示/等待循环/取消收尾）：读失败计为降级并返回 null。 */
	private Object decisionMarkerLenient(String runId, String toolCallId) {
		try {
			return this.redis.opsForHash().get(decidedKey(runId), toolCallId);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.A6_DECISION_GUARD_READ, ex);
			log.debug("读取决策 guard 失败 runId={} toolCallId={}（非抛读口按未合并 guard 继续）：{}",
					runId, toolCallId, ex.getMessage());
			return null;
		}
	}

	/** 整批 guard 值（{@link #pendings} 一次读完，避免 N 次往返；读失败抛，不得折成空表）。 */
	private Map<Object, Object> decisionMarkers(String runId) {
		try {
			Map<Object, Object> markers = this.redis.opsForHash().entries(decidedKey(runId));
			return markers == null ? Map.of() : markers;
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.A6_DECISION_GUARD_READ, ex);
			log.warn("读取决策 guard 失败 runId={}（整批读侧 fail-closed：拒绝按未决策重建卡）：{}", runId,
					ex.getMessage());
			throw new AiStateUnavailableException("无法读取决策 guard 批次 " + runId, ex);
		}
	}

	/** 非抛版整批 guard 读口（展示/取消收尾）：读失败返回空表并计数。 */
	private Map<Object, Object> decisionMarkersLenient(String runId) {
		try {
			Map<Object, Object> markers = this.redis.opsForHash().entries(decidedKey(runId));
			return markers == null ? Map.of() : markers;
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.A6_DECISION_GUARD_READ, ex);
			log.debug("读取决策 guard 失败 runId={}（非抛读口按未合并 guard 继续）：{}", runId, ex.getMessage());
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
		PendingToolCall pending;
		try {
			pending = pending(runId, toolCallId);
		}
		catch (AiStateUnavailableException ex) {
			log.warn("读取请求帧条目失败 runId={} toolCallId={}（按未知降级，不阻断本轮）：{}", runId, toolCallId,
					ex.getMessage());
			pending = pendingLenient(runId, toolCallId);
		}
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
		try {
			PendingToolCall fresh = pending(runId, toolCallId);
			return fresh == null ? pending : fresh;
		}
		catch (AiStateUnavailableException ex) {
			log.warn("请求帧置位后复核失败 runId={} toolCallId={}（保留已知条目，不阻断本轮）：{}", runId, toolCallId,
					ex.getMessage());
			return pending;
		}
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
	 * @return true = 本次由当前实例认领（可以执行）；false = 已被他实例认领或本实例已失去续跑权
	 * （<b>不得</b>执行）。批③a 起两者可用 {@link #tryClaimExecution} 区分（{@code LOST} /
	 * {@code STALE_FENCE}）
	 */
	public boolean claimExecution(String runId, String toolCallId, String executedBy) {
		// 批③a：布尔口保留（既有调用与用例零改动），三态由 tryClaimExecution 给出；
		// LOST 与 STALE_FENCE 在布尔面上都是 false（"不得执行"这一点相同）。
		return tryClaimExecution(runId, toolCallId, executedBy).won();
	}

	/**
	 * 原子认领并给出<b>三态</b>结论（B23L1 批③a / 裁决 9）。
	 *
	 * <p>与 {@link #claimExecution} 是同一段逻辑：先过 fence（持租时），再 {@code HSETNX} 认领。
	 * 两个失败分支语义不同 —— 见 {@link ClaimOutcome}。
	 */
	public ClaimResult tryClaimExecution(String runId, String toolCallId, String executedBy) {
		// 续租 fencing 收口批：新工具执行是"不可撤销副作用"的入口 —— 丢租/设施不可确认时一律拒。
		LeaseService.LeaseAuthority authority = runLeaseAuthority(runId);
		if (authority.readOnly()) {
			warnReadOnly(runId, authority, "新工具认领");
			return new ClaimResult(ClaimOutcome.STALE_FENCE, heldRunEpoch(runId), 0L);
		}
		long epoch = heldRunEpoch(runId);
		long fence = 0L;
		if (epoch > 0L) {
			// I5：持租不等于"仍是主人" —— TTL 到期被别人接管后，本进程的视图要等续租失败才会清。
			// 认领是"执行一个副作用"的入口，必须对着 fence 复核：fence 已推进到更高 epoch ⇒ 本实例的
			// 执行权已失效，拒绝认领（不得执行）。
			fence = fenceEpoch(runId);
			if (fence > epoch) {
				log.warn("STALE_EPOCH 拒绝认领 runId={} toolCallId={} epoch={}（fence 已推进到 {}："
						+ "本实例的续跑权已被接管，不得执行该工具）", runId, toolCallId, epoch, fence);
				return new ClaimResult(ClaimOutcome.STALE_FENCE, epoch, fence);
			}
		}
		String claimValue = epoch + "|" + executedBy;
		Boolean claimed = null;
		try {
			claimed = this.redis.opsForHash().putIfAbsent(claimKey(runId), toolCallId, claimValue);
			this.redis.expire(claimKey(runId), ttlFor(runId));
		}
		catch (RuntimeException ex) {
			// 认领记录可能已落（putIfAbsent 成功、expire 失败）：回滚，避免"写后异常"变成
			// 24h 假认领（后续重试看到 LOST，用户被告知"可能已执行"）。
			if (Boolean.TRUE.equals(claimed)) {
				rollbackClaim(runId, toolCallId, claimValue);
			}
			throw ex;
		}
		boolean won = Boolean.TRUE.equals(claimed);
		if (!won) {
			return new ClaimResult(ClaimOutcome.LOST, epoch, fence);
		}
		PendingToolCall marked = null;
		try {
			// C1：认领路径只读**原始条目**（pendingRaw），不走 guard 收敛读 —— 否则 A6 读失败会发生在
			// "claim 已写、执行未做"之间，留下 24h 假认领。任何读/写失败都回滚 claim 后上抛，
			// 让调用方看到可重试的基础设施故障，而不是 ALREADY_CLAIMED。
			marked = pendingRaw(runId, toolCallId);
			if (marked != null) {
				marked.setClaimedAtMs(System.currentTimeMillis());
				marked.setClaimedEpoch(epoch);
				marked.setExecuted(true);
				marked.setExecutedBy(executedBy);
				putPending(runId, marked);
			}
		}
		catch (RuntimeException ex) {
			// Minor3：put 已成功、expire 失败时条目会带 executed=true；回滚 claim 的同时
			// best-effort 恢复 executed=false，避免续跑把它误判成"已执行"。
			if (marked != null && marked.isExecuted()) {
				marked.setExecuted(false);
				marked.setExecutedBy(null);
				marked.setClaimedAtMs(0L);
				marked.setClaimedEpoch(0L);
				try {
					putPending(runId, marked);
				}
				catch (RuntimeException restoreEx) {
					log.warn("回滚认领后恢复 executed=false 失败 runId={} toolCallId={}：{}", runId, toolCallId,
							restoreEx.getMessage());
				}
			}
			rollbackClaim(runId, toolCallId, claimValue);
			throw ex;
		}
		return new ClaimResult(ClaimOutcome.WON, epoch, fence);
	}

	/**
	 * 回滚本次认领字段：<b>值匹配 CAS</b>（Lua）才删，避免误删他人重新认领的值（Minor3）。
	 */
	private void rollbackClaim(String runId, String toolCallId, String expectedValue) {
		try {
			Long deleted = this.redis.execute(CLAIM_ROLLBACK_SCRIPT, List.of(claimKey(runId)), toolCallId,
					expectedValue);
			if (deleted != null && deleted > 0L) {
				log.warn("已回滚认领记录 runId={} toolCallId={}（认领后的读/写失败，不得留假认领）", runId,
						toolCallId);
			}
			else {
				log.warn("认领记录值已变化，跳过回滚 runId={} toolCallId={}（避免误删他人认领）", runId, toolCallId);
			}
		}
		catch (Exception ex) {
			log.warn("回滚认领记录失败 runId={} toolCallId={}（仍需人工核对 claim）：{}", runId, toolCallId,
					ex.getMessage());
		}
	}

	/** I-3 / Minor4：连接类失败可能被包装（RedisSystemException 等）——沿 cause 链判断。 */
	private static boolean isConnectionFailure(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof DataAccessResourceFailureException || current instanceof QueryTimeoutException) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

	/**
	 * 已落库的最大 epoch（{@code ai:fence:run:<runId>}；0 = <b>键不存在</b>或值 ≤ 0）。
	 *
	 * <p><b>A1 fail-closed</b>：读失败（Cluster 下 MOVED/TRYAGAIN、节点故障）<b>不再</b>折成
	 * {@code 0} 放行认领 —— "读不出来"不等于"没有 fence"。认领路径会因此拒绝执行（宁可这一拍
	 * 不执行，也不在 fencing 失效时重复执行副作用）；调用方看到的是明确的写入/读取失败，
	 * 不是"什么都没发生"。
	 *
	 * @throws IllegalStateException fence 读不出来（连接异常 / 坏值）
	 */
	public long fenceEpoch(String runId) {
		try {
			String raw = this.redis.opsForValue().get(fenceKey(runId));
			return raw == null ? 0L : Long.parseLong(raw);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.A1_FENCE_READ, ex);
			log.warn("读取 fence 失败 runId={}（认领侧 fail-closed：拒绝认领，不按 fence=0 放行）：{}", runId,
					ex.getMessage());
			throw new AiStateUnavailableException("无法读取 fence " + runId + "：认领侧 fail-closed", ex);
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

	/**
	 * 追加一条执行台账，并把<b>幂等索引</b>与它写在同一个原子步里（B23L1 批③a / 裁决 6）。
	 *
	 * <h2>索引是什么、不是什么</h2>
	 * 索引 {@code ai:idem:<runId>} 只回答"这个 toolCallId 已经在<b>第几条</b>台账记录里执行过"，
	 * <b>不复制 resultText</b>（正文可能很大，且两份正文会出现"索引与台账不一致"）；读侧按
	 * {@link #idemFor} 用 {@code LINDEX} 取回原记录，O(1)。
	 *
	 * <h2>原子性</h2>
	 * {@code RPUSH} 与 {@code HSETNX}（含两个键的 {@code PEXPIRE}）在一条 Lua 里
	 * （{@link #IDEM_PUSH_SCRIPT}）：两步之间崩溃留下的洞正好是"台账有、索引没有 ⇒ 续跑再执行一遍"
	 * 这种不可撤销的重复副作用。脚本之后的那次 {@code EXPIRE} 只重复续期台账键
	 * （批②滑动 TTL 的可观测面，幂等、不参与"记录与索引"的原子性）。
	 *
	 * <h2>旧数据兼容</h2>
	 * 本批之前写入的台账没有索引；{@link #idemFor} 未命中索引时回落 {@link #ledgerFor} 的全表扫描，
	 * 因此索引<b>缺失</b>只影响快慢，不影响"是否已执行"的判定。
	 */
	public void appendLedger(String runId, PendingToolCall pending, String resultText) {
		// 续租 fencing 收口批：台账是"已执行"的跨进程判据 —— 丢租后不得追加（B 的台账才是真值），
		// 否则 A 的迟到执行会让续跑回放出一个新主人没有执行过的结果。
		// RT-21：持租但 fence 已被推进（HELD-but-fence-ahead）同样是"没有续跑权"，一并拒写。
		if (!permittedToWrite(runId, "工具执行台账落档")) {
			return;
		}
		long atMs = System.currentTimeMillis();
		String toolCallId = pending.getToolCallId();
		String idemField = idemField(runId, toolCallId);
		String executedBy = pending.getExecutedBy() == null ? this.instanceId : pending.getExecutedBy();
		String outcome = pending.effectiveOutcome().wire();
		String code = pending.effectiveCode();
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("atMs", atMs);
		record.put("at", Instant.now().toString());
		record.put("executedBy", executedBy);
		record.put("toolCallId", toolCallId);
		record.put("name", pending.getName());
		record.put("kind", pending.getKind());
		record.put("outcome", outcome);
		record.put("code", code);
		// F5 批准绑定：批准时所见的那份预检指纹随执行台账留档（null = 未经预检/旧数据）
		record.put("preflightHash", pending.getPreflight() == null ? null
				: pending.getPreflight().get("reportHash"));
		// I5：执行发生在哪一任续跑权之下（additive；旧记录没有本字段 ⇒ 读侧按缺失处理）。
		// 取值顺序：认领时的 epoch（执行权的依据）→ 当前持租 epoch（旧条目/兼容路径）。
		record.put("epoch", pending.getClaimedEpoch() > 0L ? pending.getClaimedEpoch() : heldRunEpoch(runId));
		// 批③a：本条记录的幂等索引 field（additive；与 ai:idem:<runId> 的 field 同形，便于对账）
		record.put("idemKey", idemField);
		record.put("resultText", resultText);
		String json;
		try {
			json = this.mapper.writeValueAsString(record);
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法序列化执行台账 " + toolCallId, ex);
		}
		String indexTail = outcome + "|" + (code == null ? "" : code) + "|" + executedBy + "|" + atMs;
		Duration ttl = ttlFor(runId);
		try {
			this.redis.execute(IDEM_PUSH_SCRIPT, List.of(ledgerKey(runId), idemKey(runId)), json, idemField,
					indexTail, String.valueOf(ttl.toMillis()));
			// 台账键的"整额续期"仍是可观测的一次 EXPIRE（批②的滑动 TTL 契约；脚本内已有 PEXPIRE，
			// 这里只是把同一事实再落到模板面上，幂等、不在判据路径上）。
			this.redis.expire(ledgerKey(runId), ttl);
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法写入执行台账 " + toolCallId, ex);
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

	/**
	 * 取该 toolCallId 的台账记录：<b>先查幂等索引（O(1)），未命中再回落全表扫描</b>
	 * （B23L1 批③a / 裁决 6）。
	 *
	 * <p>三条路径：
	 * <ol>
	 * <li>索引命中 → {@code LINDEX} 取回原记录（一次 {@code HGET} + 一次 {@code LINDEX}，与台账长度无关）；</li>
	 * <li>索引未命中（本批之前写入的旧数据 / 索引被淘汰）→ {@link #ledgerFor} 扫描（旧行为，
	 * 结果与索引路径同形）；</li>
	 * <li>索引命中但取不回（下标越界/记录被删/JSON 坏）→ 同样回落扫描：索引只是加速面，
	 * <b>台账才是判据</b>，索引坏掉不能把"已执行"报成"没执行"（那会导致重复副作用）；</li>
	 * <li>索引命中但<b>指向别人的记录</b>（下标错位/索引被污染）→ 仍回落扫描（B23L1 批③b 复审 M-1）：
	 * 重放必须"谁的结果回给谁"，把别人的执行结论当成本调用结果，比"没查到"更危险。</li>
	 * </ol>
	 */
	public Map<String, Object> idemFor(String runId, String toolCallId) {
		if (toolCallId == null) {
			return null;
		}
		String field = idemField(runId, toolCallId);
		String indexed;
		try {
			Object raw = this.redis.opsForHash().get(idemKey(runId), field);
			indexed = raw == null ? null : String.valueOf(raw);
		}
		catch (Exception ex) {
			log.debug("读取幂等索引失败 runId={} toolCallId={}（回落台账扫描）：{}", runId, toolCallId, ex.getMessage());
			indexed = null;
		}
		if (indexed != null) {
			Long index = listIndexOf(indexed);
			Map<String, Object> record = index == null ? null : recordAt(runId, index);
			if (record != null && matchesToolCall(record, toolCallId)) {
				return record;
			}
			if (record != null) {
				// M-1（复审裁决）：索引指向<b>别人的</b>记录（下标错位/索引被污染）时绝不能把它当成本调用的结果 ——
				// 那会把 A 的产物交给 B（重放错了副作用结论比"没查到"更危险）。回落扫描由记录自身定身份。
				log.warn("幂等索引错位 runId={} toolCallId={} index={}（该下标属于 toolCallId={}，回落台账扫描）", runId,
						toolCallId, indexed, record.get("toolCallId"));
			}
			else {
				log.debug("幂等索引命中但取不回原记录 runId={} toolCallId={} index={}（回落台账扫描）", runId, toolCallId,
						indexed);
			}
		}
		return ledgerFor(runId, toolCallId);
	}

	/** 索引指向的记录是否<b>就是</b>请求的 {@code toolCallId}（错位/字段缺失一律 false ⇒ 回落扫描）。 */
	private static boolean matchesToolCall(Map<String, Object> record, String toolCallId) {
		Object recordId = record.get("toolCallId");
		return recordId != null && toolCallId.equals(String.valueOf(recordId));
	}

	/** 索引值的下标段（{@code <listIndex>|<outcome>|...}；坏值返回 null）。 */
	private static Long listIndexOf(String indexed) {
		int sep = indexed.indexOf('|');
		String head = sep < 0 ? indexed : indexed.substring(0, sep);
		try {
			return Long.valueOf(head);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/** 台账第 {@code index} 条（越界/读不出来返回 null）。 */
	private Map<String, Object> recordAt(String runId, long index) {
		if (index < 0L) {
			return null;
		}
		try {
			String item = this.redis.opsForList().index(ledgerKey(runId), index);
			if (item == null) {
				return null;
			}
			return this.mapper.readValue(item, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (Exception ex) {
			log.debug("按索引读取台账失败 runId={} index={}：{}", runId, index, ex.getMessage());
			return null;
		}
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
	 * 取消落地：把该轮**可证明未执行**的挂起条目一次性标为 {@link PendingToolCall#CANCELLED}
	 * 且 {@code executed=false}。
	 *
	 * <p>
	 * 为什么重要：取消之后若进程死亡、又被启动续跑拾起，未决条目仍会躺在 Redis 里 ——
	 * 若状态还是 {@code PENDING} 而续跑把它们当"待外部输入"，就会永久卡住（或更糟：
	 * 被误当"未执行的后端工具"补执行）。取消即落库，跨进程也生效。
	 *
	 * <p>
	 * <b>R1：{@code APPROVED} 且未认领未执行也要收敛</b>。批准（{@code ConfirmGate}）只写决策、
	 * 不执行；执行由续跑的认领（{@link #tryClaimExecution}）负责。两步之间用户按停止时，条目
	 * 既不是 {@code PENDING}（旧口径不碰），也不该继续挂着一个"随时可被执行"的批准 ——
	 * 那正是"取消后仍执行不可逆工具"的一半来源。判据见 {@link #cancellableBeforeExecution}。
	 *
	 * @return 被改写的条目数
	 */
	public int cancelPendings(String runId) {
		int changed = 0;
		// C2：取消收尾用非抛读口 —— guard 读失败不能让条目停在 PENDING（用户可见"卡在取消中"）。
		for (PendingToolCall pending : pendingsLenient(runId)) {
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

	/**
	 * <b>取消收尾/认领前复核共用的"是否已有执行证据"判据</b>（R1）。
	 *
	 * <p>证据面 = 条目上的 {@code executed} / {@code claimedAtMs} / {@code claimedEpoch} /
	 * {@code executedBy} / {@code executedAtMs}，<b>加上</b>认领表（{@code ai:claim:<runId>}）：
	 * 后者覆盖跨实例认领的中间窗口 —— 对方 {@code tryClaimExecution} 的 {@code HSETNX} 已落、
	 * 而 {@code ai:pending} 还没回写认领字段。
	 *
	 * <p>方向：<b>证明不了"没有"就当有</b>（认领表读失败、条目为 null 都返回 true）——
	 * 错误方向的代价不对称：把已认领的说成未执行会让取消"抹掉"一次真实副作用。
	 */
	public boolean hasExecutionEvidence(String runId, PendingToolCall pending) {
		if (pending == null) {
			return true;
		}
		if (pending.isExecuted() || pending.getClaimedAtMs() > 0L || pending.getClaimedEpoch() > 0L
				|| pending.getExecutedBy() != null || pending.getExecutedAtMs() > 0L) {
			return true;
		}
		try {
			return claimedBy(runId, pending.getToolCallId()) != null;
		}
		catch (RuntimeException ex) {
			log.warn("读认领表失败，按\"已有执行证据\"处理 runId={} toolCallId={}：{}", runId,
					pending.getToolCallId(), ex.getMessage());
			return true;
		}
	}

	/**
	 * <b>取消收尾的可收敛判据</b>（R1）：{@code PENDING}（原口径）或
	 * <b>{@code APPROVED} 且可证明未认领未执行</b>。
	 *
	 * <p><b>只认"零证据"</b>：证据面见 {@link #hasExecutionEvidence}（含跨实例认领表）——
	 * 有证据一律<b>不碰</b>。取消不得把一次真实发生的副作用说成没发生，也不得抹掉
	 * 正在执行/已完成的结果。
	 *
	 * <p>认领表读失败同样不碰（证明不了"未认领"就不收敛）；本判据不抛，失败只留 WARN。
	 */
	public boolean cancellableBeforeExecution(String runId, PendingToolCall pending) {
		if (pending == null) {
			return false;
		}
		if (PendingToolCall.PENDING.equals(pending.getStatus())) {
			return true;
		}
		if (!PendingToolCall.APPROVED.equals(pending.getStatus())) {
			return false;
		}
		return !hasExecutionEvidence(runId, pending);
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
	 * 广播给订阅者），只是改由 {@link #touchActivity(String)} 记一个轻量心跳戳 ——
	 * <b>R1-09 起它只证明"进程还活着"（会话门用），不推进业务活动锚点</b>（僵尸判据不吃心跳），
	 * 归档也不被它占位。
	 *
	 * <p>
	 * <b>B1（2026-10-20）：{@code delta} 改为入档</b> —— 挂起即断（流派 B）之后，用户决策 →
	 * 后端续跑这段窗口内没有实时订阅者，正文帧只能靠归档差量补回（前端带 {@code ?lastSeq}
	 * 重连）。代价：长轮次的 delta 会加速挤出 {@value #EVENT_WINDOW} 窗口内的早段帧
	 * （极端长生成的重连补帧可能出现尾部缺口，已在设计稿 §6 留痕，B2 换 Redis Stream 时重估）。
	 * 任何非心跳帧都刷新<b>业务活动锚点</b>（{@link #touchBusinessActivity}），"只吐 delta 的长轮"
	 * 因此仍有业务前进证据（心跳不算）。
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
			// T7：心跳跳过归档，只留心戳 —— 它不是业务活动（R1-09：不得掩盖卡死）。
			touchActivity(runId);
			return;
		}
		// 续租 fencing 收口批：兼容写口也要过权威判定 —— 丢租/设施不可确认时不得落档
		// （生产出帧已在 SseChatEmitter#emit 拦截；这里是"绕过发射器直塞帧"的第二道闸）。
		// RT-21：HELD-but-fence-ahead（租约视图还在、fence 已被接管方推进）同样不得落档，
		// 否则旧主人在事件日志里继续写业务帧、唤醒其它实例补到一份作废的流。
		if (!permittedToWrite(runId, "事件帧落档")) {
			return;
		}
		frame.putIfAbsent("atMs", System.currentTimeMillis());
		// R1-09：任何业务帧（不限于 delta）都推进业务活动锚点；读取侧还会与事件日志尾部取 max，
		// 因此这里即使写失败，归档本身仍是可见的活动证据。
		Object atMs = frame.get("atMs");
		touchBusinessActivity(runId, atMs instanceof Number number ? number.longValue()
				: System.currentTimeMillis());
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
		// 续租 fencing 收口批：丢租后不得再取/登记业务号（新主人从自己的号锚续号，
		// 旧主人继续推进会把 seq 空间搅乱，且意味着还在落档）。
		// RT-21：HELD-but-fence-ahead 也不得登记 —— 那一任的号空间已归新主人。
		if (!permittedToWrite(runId, "业务序号登记")) {
			return;
		}
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
	 * 为什么不干脆"心跳什么都不写"：会话门判死（{@code RunLiveness}）与续跑扫描都需要
	 * "这一轮最近还有人动过"的证据。心跳不入归档后，挂起等人（确认门 2s 一拍）的轮会长时间
	 * 没有任何归档帧 —— 一个 STRING 键把"进程还活着"补回来，代价远低于归档一帧。
	 *
	 * <p>
	 * <b>R1-09：心跳只是"进程活着"，不是"业务前进"</b> —— 本方法<b>不</b>推进业务活动锚点
	 * （{@link #lastActivityAtMs}）。僵尸接管只认业务活动，否则心跳会把卡死的轮永远养年轻。
	 */
	public void touchActivity(String runId) {
		try {
			this.redis.opsForValue().set(beatKey(runId), String.valueOf(System.currentTimeMillis()), ttlFor(runId));
		}
		catch (Exception ex) {
			log.debug("心跳活动戳写入失败 runId={}：{}", runId, ex.getMessage());
		}
	}

	/**
	 * <b>业务活动锚点</b>（R1-09）：任何<b>非心跳</b>帧都调用本方法，写
	 * {@code ai:activity:<runId>} = 该帧的 {@code atMs}（TTL = session-ttl）。
	 *
	 * <p>
	 * 与 {@link #touchActivity(String)} 的分工是本修复的核心：业务帧推进"这一轮在前进"，心跳只
	 * 推进"进程还活着"。僵尸判据读 {@link #lastActivityAtMs(String)}，因此"心跳还在、业务卡死"
	 * 仍会被判死并接管。
	 *
	 * <p>
	 * <b>原子单调</b>（I-3）：比较 + 写 + 续期收在一条单键 Lua（{@link #ACTIVITY_TOUCH_SCRIPT}）里，
	 * 因此并发/乱序的 {@code atMs} 不会把锚点回拨，且流式热路径只要 1 次往返（不再 GET+SET 两次）。
	 * 坏锚点值按 0 处理并覆盖（M-4），不会让该轮的活动锚点失效到 TTL 到期。
	 *
	 * <p>
	 * 时钟域（M-2）：{@code atMs} 是出帧节点的墙钟，判死用本节点 {@code now}；部署需保证 NTP，
	 * 偏差应远小于 {@code app.ai.resume.zombie-margin}。写失败只记 debug —— 帧照常投递/落档，
	 * 活动证据的兜底在事件日志。
	 */
	public void touchBusinessActivity(String runId, long atMs) {
		if (runId == null) {
			return;
		}
		long stamp = atMs > 0L ? atMs : System.currentTimeMillis();
		try {
			this.redis.execute(ACTIVITY_TOUCH_SCRIPT, List.of(activityKey(runId)), String.valueOf(stamp),
					String.valueOf(ttlFor(runId).toMillis()));
		}
		catch (Exception ex) {
			log.debug("业务活动锚点写入失败 runId={} atMs={}：{}", runId, atMs, ex.getMessage());
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
	 * 单个键失败已在 {@link #renew} 内逐键隔离（I-3：连接类失败提前收手），本方法不再整面抛出。
	 */
	public void renewSuspensionTtl(String runId) {
		if (runId == null) {
			return;
		}
		Duration ttl = ttlFor(runId);
		try {
			renew(runId, ttl, SessionKeyRegistry.Renewal.HEARTBEAT);
		}
		catch (Exception ex) {
			// 只剩取键/计数等非 Redis 异常（单键 Redis 失败已在下层吞掉并计数）
			log.debug("挂起期续期轮键的取键/计数失败 runId={}：{}", runId, ex.getMessage());
		}
	}

	/**
	 * 按登记表续期一个续期面（超时面治理批① 第 11 项）：键清单来自
	 * {@link SessionKeyRegistry#renewalKeys(SessionKeyRegistry.Renewal)}，本方法只负责发 {@code EXPIRE}。
	 *
	 * <p>Redis {@code EXPIRE} 对不存在的键是无操作（不报错），故"该键还没写出"不构成错误。
	 * <b>L-139：逐键隔离</b> —— 一个键失败（Cluster 下 MOVED/TRYAGAIN、单节点故障）不得让排在
	 * 后面的键全部不续期；每次失败计入 {@link RedisDegradation#L139_RENEW_KEY}。
	 *
	 * <p><b>I-3：连接类失败立即收手。</b>连接挂死时每个键都要等到 {@code spring.data.redis.timeout}
	 * （2s），10 个键 = 20s，会把确认门 2s 心跳和请求线程拖垮。因此识别到连接类异常
	 * （{@link DataAccessResourceFailureException} / {@link QueryTimeoutException}）后不再逐键尝试，
	 * 剩余键计入 {@link RedisDegradation#L139_RENEW_NOT_ATTEMPTED}；其它单键异常仍继续逐键。
	 */
	private void renew(String runId, Duration ttl, SessionKeyRegistry.Renewal renewal) {
		List<java.util.function.Function<String, String>> keys = SessionKeyRegistry.renewalKeys(renewal);
		for (int i = 0; i < keys.size(); i++) {
			String key = keys.get(i).apply(runId);
			try {
				this.redis.expire(key, ttl);
			}
			catch (Exception ex) {
				// 单键失败只影响该键；其余键照常续期（长挂起卡的后台数据不能因一次抖动整面过期）
				RedisDegradation.record(RedisDegradation.L139_RENEW_KEY, ex);
				if (isConnectionFailure(ex)) {
					int notAttempted = keys.size() - i - 1;
					for (int skipped = 0; skipped < notAttempted; skipped++) {
						RedisDegradation.record(RedisDegradation.L139_RENEW_NOT_ATTEMPTED);
					}
					log.debug("续期遇到连接类异常 runId={} key={}（剩余 {} 键不再逐个等待超时）：{}", runId, key,
							notAttempted, ex.getMessage());
					return;
				}
				log.debug("续期键失败 runId={} key={}（其余键继续续期）：{}", runId, key, ex.getMessage());
			}
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

	/**
	 * 读侧续期的省一次读重载（调用方刚从 Redis 读到快照）。
	 *
	 * <p><b>I5：业务活动锚点只在 {@code SUSPENDED} 时刷新。</b>读侧 touch 证明"还有人管这一轮"
	 * 只对<b>等人</b>成立（{@code /runs/current}、决策、重挂都是 park 期的正常动作）。
	 * {@code RUNNING} 轮的活证只能是它自己的业务帧/心跳；让读侧也刷新锚点，等于把"卡死的 RUNNING 轮"
	 * 越读越年轻 —— 僵尸判定（{@link com.example.configmgr.ai.config.AiProperties.Resume#getZombieAge()}）
	 * 永远等不到接管，跨实例的双驱动防线被读流量绕过。TTL 续期不受影响（读侧仍养着挂起数据）。
	 */
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
		// 契约位 / 决策 guard / fence / 取消信号（超时面治理批① 第 11 项：取键走登记表）。
		// I-3 起单键失败已在 renew 内隔离，这里不再需要整面 try/catch。
		renew(runId, ttl, SessionKeyRegistry.Renewal.READ);
		// 读侧 touch 也是"还有人管这一轮"的证据（人工等待 park 批）：会话门判死（RunLiveness）
		// 不得只看自写心跳/空 emitter；有人在读（/runs/current、决策、重挂）就刷新业务活动锚点。
		// 注意写的是 <b>ai:activity</b>（业务活动锚点）而不是 ai:beat —— 后者只证明进程活着；
		// 而且只对 SUSPENDED 写（I5）：RUNNING 轮的活动证据只能来自它自己的业务帧/心跳。
		if (RunSnapshot.SUSPENDED.equals(snapshot.getStatus())) {
			touchBusinessActivity(runId, System.currentTimeMillis());
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
			throw new AiStateUnavailableException("无法写入决策 guard " + toolCallId, ex);
		}
	}

	/**
	 * 决策 guard 原文（{@code <status>|<atMs>|<instanceId>}；null = 未决策）。
	 *
	 * <p><b>A6 fail-closed</b>：读失败抛异常，不返回 null —— null 在这条链上等于"未决策"，
	 * 会把已决的卡重新挂起。读成功且无该 field 才是 null。
	 */
	public String decisionOf(String runId, String toolCallId) {
		try {
			Object value = this.redis.opsForHash().get(decidedKey(runId), toolCallId);
			return value == null ? null : String.valueOf(value);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.A6_DECISION_GUARD_READ, ex);
			log.warn("读取决策 guard 失败 runId={} toolCallId={}（fail-closed：不得当作未决策）：{}", runId,
					toolCallId, ex.getMessage());
			throw new AiStateUnavailableException("无法读取决策 guard " + toolCallId, ex);
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
	 * 该轮最近一次<b>业务活动</b>的时刻（毫秒）：任何<b>非心跳</b>帧都推进它（R1-09）。
	 *
	 * <p>
	 * 取值 = 三方的<b>较新者</b>：
	 * <ol>
	 * <li>{@code ai:activity:<runId>} —— 出帧点（{@code SseChatEmitter#emit}）与兼容写口
	 * （{@link #appendEvent}）直接写入的活动锚点。即使事件日志落档失败（Redis 部分可用），
	 * 只要帧发出去了，这里仍有证据；</li>
	 * <li>兼容期 LIST {@code ai:events:<runId>} 尾部 {@value #ACTIVITY_TAIL_PROBE} 帧的 {@code atMs}；</li>
	 * <li>事件日志 Stream {@code ai:xevents:<runId>} 尾部 {@value #ACTIVITY_TAIL_PROBE} 帧的
	 * {@code atMs} —— B23L1 批① 起的生产写口，于是<b>重连/唤醒/跨实例</b>都能看到新业务帧。</li>
	 * </ol>
	 *
	 * <p>
	 * <b>心跳不参与</b>：心跳只刷新 {@link #lastBeatAtMs(String)}。把心跳算成业务活动会让
	 * "进程还在心跳、业务已经卡死"的轮永远年轻，僵尸接管失效（心跳掩盖卡死）。
	 *
	 * <p>
	 * 两份日志都可能存在升级期数据（LIST 只读、Stream 新写），故取 max 而不是信任"当前写口"；
	 * 尾部取多条而不是只取最后一条，是为了容忍乱序落档（显式 ID 被拒后改自动 ID）与轻微钟漂。
	 * 三者都没有（或旧格式帧没有 atMs）返回 0，调用方据此回落到快照的 {@code updatedAtMs}。
	 *
	 * <p>
	 * <b>时钟域（M-2）</b>：{@code atMs} 是出帧节点的墙钟，判死用的是本节点 {@code now}。
	 * 跨节点负偏会让活轮提前"超龄"，因此多实例部署必须保证 NTP 同步，偏差应远小于
	 * {@code app.ai.resume.zombie-margin}。
	 */
	public long lastActivityAtMs(String runId) {
		return Math.max(Math.max(activityAnchorAtMs(runId), legacyActivityTailAtMs(runId)),
				streamActivityTailAtMs(runId));
	}

	/**
	 * 该轮最近一次<b>任何活动</b>（业务帧或心跳）的时刻（毫秒）。
	 *
	 * <p>
	 * 语义 = {@link #lastActivityAtMs(String)}（业务前进）与 {@link #lastBeatAtMs(String)}
	 * （进程还活着）的较新者。用于"这一轮是否还有人管"的会话门判定（{@code RunLiveness}）——
	 * 心跳能让挂起等人的轮保持"活着"，<b>但不能</b>作为僵尸接管的业务活动依据；僵尸判据只读
	 * {@link #lastActivityAtMs(String)}。
	 */
	public long lastEventAtMs(String runId) {
		return Math.max(lastActivityAtMs(runId), lastBeatAtMs(runId));
	}

	/** 业务活动锚点键名（{@code ai:activity:<runId>}；诊断/取证可直读）。 */
	public String activityKey(String runId) {
		return ACTIVITY_PREFIX + runId;
	}

	/** {@code ai:activity} 的当前值（无键/坏值 0）。 */
	private long activityAnchorAtMs(String runId) {
		try {
			String raw = this.redis.opsForValue().get(activityKey(runId));
			return raw == null ? 0L : Long.parseLong(raw);
		}
		catch (Exception ex) {
			log.debug("读取业务活动锚点失败 runId={}：{}", runId, ex.getMessage());
			return 0L;
		}
	}

	/** 兼容期 LIST 尾部的最大业务帧时刻（心跳帧即使被直塞进 LIST 也不计入）。 */
	private long legacyActivityTailAtMs(String runId) {
		try {
			List<String> tail = this.redis.opsForList().range(eventsKey(runId), -ACTIVITY_TAIL_PROBE, -1);
			if (tail == null || tail.isEmpty()) {
				return 0L;
			}
			long max = 0L;
			for (String raw : tail) {
				max = Math.max(max, businessFrameAtMs(raw == null ? null
						: raw.getBytes(StandardCharsets.UTF_8)));
			}
			return max;
		}
		catch (Exception ex) {
			log.debug("读取兼容 LIST 尾部活动时刻失败 runId={}：{}", runId, ex.getMessage());
			return 0L;
		}
	}

	/** 事件日志 Stream 尾部的最大业务帧时刻（一次 {@code XREVRANGE COUNT n}）。 */
	private long streamActivityTailAtMs(String runId) {
		try {
			byte[] key = xeventsKey(runId).getBytes(StandardCharsets.UTF_8);
			Long max = this.redis.execute((RedisCallback<Long>) connection -> {
				List<ByteRecord> records = connection.xRevRange(key, Range.unbounded(),
						Limit.limit().count(ACTIVITY_TAIL_PROBE));
				if (records == null || records.isEmpty()) {
					return 0L;
				}
				long result = 0L;
				for (ByteRecord record : records) {
					result = Math.max(result, businessFrameAtMs(frameFieldOf(record)));
				}
				return result;
			});
			return max == null ? 0L : max;
		}
		catch (Exception ex) {
			// 读失败 ≠ 没有活动：调用方还会看锚点键与兼容 LIST（取 max），这里按 0 容错。
			log.debug("读取事件日志尾部活动时刻失败 runId={}：{}", runId, ex.getMessage());
			return 0L;
		}
	}

	/** 一帧的 {@code atMs}；心跳帧返回 0（业务活动锚点必须排除心跳）。 */
	private long businessFrameAtMs(byte[] raw) {
		if (raw == null) {
			return 0L;
		}
		try {
			JsonNode node = this.mapper.readTree(raw);
			if (HEARTBEAT_TYPE.equals(node.path("type").asText())) {
				return 0L;
			}
			JsonNode atMs = node.get("atMs");
			return atMs == null ? 0L : atMs.asLong();
		}
		catch (Exception ex) {
			return 0L;
		}
	}

	/** Stream 记录里的 {@code frame} 字段原文（字段缺失返回 null）。 */
	private static byte[] frameFieldOf(ByteRecord record) {
		for (Map.Entry<byte[], byte[]> entry : record.getValue().entrySet()) {
			if ("frame".equals(new String(entry.getKey(), StandardCharsets.UTF_8))) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * 心跳活动戳的时刻（无则 0）。
	 *
	 * <p>只由心跳帧（以及作为"任何活动"兜底的写口）刷新，<b>不</b>代表业务前进。
	 */
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

	// ── 上下文计量与压缩的三个键族（上下文统计与压缩专项批①） ──────────────────────

	/** {@code ai:ctx:<sessionId>} —— 会话上下文真值（HASH，字段见 {@code ContextState#toFields}）。 */
	public String contextKey(String sessionId) {
		return CONTEXT_PREFIX + sessionId;
	}

	/** {@code ai:ctx:ledger:<sessionId>} —— 压缩事件台账（LIST，新→旧）。 */
	public String contextLedgerKey(String sessionId) {
		return CONTEXT_LEDGER_PREFIX + sessionId;
	}

	/** {@code ai:ctx:archive:<sessionId>:<turnNo>} —— 压缩前原文（STRING，JSON 数组）。 */
	public String contextArchiveKey(String sessionId, int turnNo) {
		return CONTEXT_ARCHIVE_PREFIX + sessionId + ":" + turnNo;
	}

	/**
	 * 读会话上下文真值（HASH → {@code ContextState}；键不存在 ⇒ 空态）。
	 *
	 * <p>读也 touch TTL（"与会话同滑动"：只读不写的会话同样按活跃续期）。Redis 抖动<b>不</b>抛错
	 * —— 计量面挂了不该让端点 500，降级为空态由调用方决定怎么展示。
	 */
	@Override
	public com.example.configmgr.ai.context.ContextState read(String sessionId) {
		String key = contextKey(sessionId);
		try {
			Map<Object, Object> raw = this.redis.opsForHash().entries(key);
			touchContextTtl(key);
			if (raw == null || raw.isEmpty()) {
				return com.example.configmgr.ai.context.ContextState.EMPTY;
			}
			Map<String, String> fields = new LinkedHashMap<>();
			for (Map.Entry<Object, Object> entry : raw.entrySet()) {
				fields.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
			}
			return com.example.configmgr.ai.context.ContextState.fromFields(fields);
		}
		catch (RuntimeException ex) {
			log.warn("读上下文真值失败 sessionId={}：{}（按空态处理）", sessionId, ex.getMessage());
			return com.example.configmgr.ai.context.ContextState.EMPTY;
		}
	}

	/**
	 * 整份写会话上下文真值（HSET + EXPIRE）。
	 *
	 * <p>
	 * <b>边界（N-3 如实登记）</b>：底层是 {@code HSET} 的<b>字段级合并</b>，不是"先删后写"——
	 * {@code ContextState#toFields()} 对 null 键<b>不产字段</b>（避免把 null 写成空串），
	 * 于是"上一份有 {@code reason}、这一份 reason=null"时，<b>旧值会残留</b>在该 HASH 里。
	 * 影响面仅限 {@code reason}（唯一的可空字符串字段；其余字段恒写）+ {@code model}（同为空则残留旧模型名）。
	 * 两者都是<b>观测/展示</b>字段：残留表现为"上一次压缩的原因/模型名没被清掉"，
	 * 不会被任何判定消费（判据只看 {@code used/level/turnNo/circuit}）。不做 DEL+HSET 的理由 =
	 * 多一次往返且窗口内 HASH 可能为空（读侧会误判"从未记账"）。
	 */
	@Override
	public void write(String sessionId, com.example.configmgr.ai.context.ContextState state) {
		String key = contextKey(sessionId);
		Map<String, String> fields = state.toFields();
		this.redis.opsForHash().putAll(key, fields);
		touchContextTtl(key);
	}

	/** 追加上下文台账（LPUSH + LTRIM 20 + EXPIRE；写失败只抛给调用方，由压缩的失败路径兜住）。 */
	@Override
	public void appendLedger(String sessionId, Map<String, Object> event) {
		String key = contextLedgerKey(sessionId);
		try {
			this.redis.opsForList().leftPush(key, this.mapper.writeValueAsString(event));
			this.redis.opsForList().trim(key, 0, CONTEXT_LEDGER_MAX - 1);
			touchContextTtl(key);
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法写入上下文台账 sessionId=" + sessionId + "：" + ex.getMessage(), ex);
		}
	}

	/** 读上下文台账（新→旧，最多 {@value #CONTEXT_LEDGER_MAX} 条）。 */
	@Override
	public List<Map<String, Object>> contextLedger(String sessionId) {
		List<Map<String, Object>> out = new ArrayList<>();
		try {
			List<String> raw = this.redis.opsForList().range(contextLedgerKey(sessionId), 0, CONTEXT_LEDGER_MAX - 1);
			if (raw == null) {
				return out;
			}
			for (String item : raw) {
				out.add(this.mapper.readValue(item, new TypeReference<Map<String, Object>>() {
				}));
			}
		}
		catch (Exception ex) {
			log.warn("读上下文台账失败 sessionId={}：{}（按空台账处理）", sessionId, ex.getMessage());
		}
		return out;
	}

	/**
	 * 追加安全事件台账（防护批③ B3-7；{@code ai:safety:<sessionId>}，LPUSH + LTRIM 20 + 同会话 TTL）。
	 *
	 * <p>
	 * 与上下文台账的写入口径一致（新→旧、截断到 {@value #SAFETY_LEDGER_MAX} 条），
	 * 但失败<b>不抛</b>：安全台账是观测面，Redis 抖动不该让已经在跑的一轮失败
	 * （缺一条审计好过整个请求 500）。
	 */
	public void appendSafetyEvent(String sessionId, Map<String, Object> event) {
		if (sessionId == null || sessionId.isBlank() || event == null) {
			return;
		}
		String key = SAFETY_PREFIX + sessionId;
		try {
			this.redis.opsForList().leftPush(key, this.mapper.writeValueAsString(event));
			this.redis.opsForList().trim(key, 0, SAFETY_LEDGER_MAX - 1);
			touchContextTtl(key);
		}
		catch (Exception ex) {
			log.warn("写安全台账失败 sessionId={}：{}（不影响本轮）", sessionId, ex.getMessage());
		}
	}

	/** 读安全台账（新→旧，最多 {@value #SAFETY_LEDGER_MAX} 条；Redis 抖动按空台账）。 */
	public List<Map<String, Object>> safetyLedger(String sessionId) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (sessionId == null || sessionId.isBlank()) {
			return out;
		}
		try {
			List<String> raw = this.redis.opsForList().range(SAFETY_PREFIX + sessionId, 0, SAFETY_LEDGER_MAX - 1);
			if (raw == null) {
				return out;
			}
			for (String item : raw) {
				out.add(this.mapper.readValue(item, new TypeReference<Map<String, Object>>() {
				}));
			}
		}
		catch (Exception ex) {
			log.warn("读安全台账失败 sessionId={}：{}（按空台账处理）", sessionId, ex.getMessage());
		}
		return out;
	}

	/** 归档压缩前原文（STRING，JSON 数组；TTL 同会话）。 */
	@Override
	public void archive(String sessionId, int turnNo, List<String> messageJson) {
		try {
			String json = this.mapper.writeValueAsString(messageJson);
			this.redis.opsForValue().set(contextArchiveKey(sessionId, turnNo), json, this.properties.getSessionTtl());
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法归档压缩前原文 sessionId=" + sessionId + "：", ex);
		}
	}

	private void touchContextTtl(String key) {
		try {
			this.redis.expire(key, this.properties.getSessionTtl());
		}
		catch (RuntimeException ex) {
			log.debug("上下文键续期失败 key={}：{}", key, ex.getMessage());
		}
	}

}
