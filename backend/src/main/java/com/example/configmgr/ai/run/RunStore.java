package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.LogSampling;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.RunKeyScope;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.common.AiStateUnavailableException;
import com.example.configmgr.common.ConflictException;
import com.example.configmgr.common.ResourceNotFoundException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.NumberUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 挂起态与台账的 Redis 外置存储（S4.2 §2 {@code run/RunStore}，移植自 SP-02 同名实现）。
 *
 * <h2>键结构（run 级键<b>全部带滑动 TTL</b> = {@code app.ai.session-ttl}，默认 24h，前缀 {@code ai:}）</h2>
 * <p>
 * <b>无绝对上限</b>（用户裁决 2026-10-20 第 4 条）：每次写键/心跳都把该键重新养满一个 TTL，
 * 挂起等待期间的心跳按<b>约 60s 的资源档</b>续期（2s 那一拍只管心跳帧/取消发现/租约）——
 * 会话活着，卡的后台数据就活着。原 T3-8 的
 * "创建时刻 + session-ttl 绝对封顶"已退役。
 * <p><b>hash tag（B23L1 批① Cluster 硬阻断；租户任务2 起 tag 含身份）</b>：run 级键的"一轮 13 键"<b>全部</b>带
 * {@code {<tenantId>:<userId>:run:<runId>}} tag（如 {@code ai:run:{1:101:run:<runId>}}，
 * 无身份上下文的过渡期路径退回旧形 {@code {run:<runId>}}），见 {@link #runTag(String)} ——
 * 既是 {@code FENCED_SET} / {@code IDEM_PUSH} 两条多键脚本的同槽前提，也让 {@link #deleteRun}
 * 的 13 键 {@code DEL} 回到"一条命令、原子生效"。{@code ai:cancel:} 与认领共用同一 tag，
 * 供取消检查与 claim 原子推进；{@code ai:beat:} 只进单键命令、不加 tag。下文的键形为可读性省去 tag。</p>
 * <ul>
 * <li>{@code ai:run:<runId>} STRING（JSON）—— {@link RunSnapshot}：完整请求历史 +
 * 挂起中的 {@code assistant(tool_calls)} + 状态 + 上下文；</li>
 * <li>{@code ai:runstate:<runId>} STRING —— <b>轻量状态键</b>（值 = 状态串，如 {@code RUNNING}）：
 * 与快照<b>同 hash tag、同 TTL</b>，在每一条状态写 Lua 里与快照一起落库（创建 / 条件创建 /
 * fence 写 / 取消优先守卫），{@code deleteRun} 一并删除、心跳与读侧续期面一并续期。
 * <b>存在的意义</b>：判活（{@code activeRunIds} / {@code snapshotNotTerminal}）只需要一个状态串，
 * 不必把整份快照（含整窗 {@code messageJson}）反序列化成对象图。取消优先守卫也先读它，
 * <b>缺失时回退 cjson 解析现存快照</b>（本键引入前的旧数据），因此不是正确性依赖面；</li>
 * <li>{@code ai:recovery:due} ZSET —— 全局恢复候选，成员为 {@code tenantId/userId/runId}，
 * score 为下一次恢复检查时刻；按身份另有同形窄 due 索引供手工扫描使用。列表使用按身份
 * {@code ai:runs:<tenant>:<user>} ZSET，不再读取全局 SET 或预做逐成员 EXISTS。
 * 快照写后物化这些索引，终态只移出恢复面；创建路径不清理索引，恢复扫描间以最多 64 个
 * 轻量状态键做低频有界对账。全局与身份索引只分别执行单键命令，不做跨槽多键操作；</li>
 * <li>{@code ai:pending:<runId>} HASH（field=toolCallId）—— {@link PendingToolCall}
 * （确认门/前端工具的输入落点，重启后仍可读）；</li>
 * <li>{@code ai:claim:<runId>} HASH（field=toolCallId，value=<b>{@code <epoch>|<instanceId>}</b>）——
 * <b>执行认领</b>：SP-02 §6 遗留的"executed 标记在执行返回后才写 ⇒ 最坏情况重复执行"窗口，
 * 本实现把 fence、状态、标记和完整条目收敛到一条同槽 Lua，执行动作只能由认领成功的实例发起；
 * B23L1 批②a 起值前置了租约 epoch（认领能与续跑权对账），读口 {@link #claimedBy} 剥前缀，
 * 对外返回值形态不变；</li>
 * <li>{@code ai:req:<runId>} HASH（field=toolCallId，value=置位时刻毫秒）—— <b>请求帧已发</b>位
 * （L0 的 {@code req}）：{@code HSETNX} 首写者胜，<b>不再回写条目本身</b>（回写会把并发落库的
 * 决策覆盖回 PENDING，见 M-2）；读侧 {@link #pending}/{@link #pendings} 读出后合并该位；</li>
 * <li>{@code ai:decided:<runId>} HASH（field=toolCallId，value={@code <status>|<atMs>|<instanceId>}）——
 * <b>决策 guard / WAL</b>：新决策与完整 pending 由 {@code DECIDE_PENDING} 同槽原子写入；
 * 旧版遗留的半状态仍可由读侧和同值条件修补收敛；</li>
 * <li>{@code ai:lease:run:<runId>} STRING（value={@code <epoch>|<instanceId>}，TTL 30s；带 {@code {run:<runId>}} tag）——
 * <b>续跑权租约</b>（B23L1 批②a；{@link LeaseService}）：持租者才是本轮的续跑主人，
 * 续租失败即被别的实例接管；</li>
 * <li>{@code ai:fence:run:<runId>} STRING —— <b>fence</b>：已落库的最大 epoch。
 * {@link #save} 在持租时走 Lua fence 写（{@code fence ≤ epoch} 才写，并把 fence 推到该 epoch）；
 * TTL 随快照。无租约（降级/单实例）时保持 plain {@code SET}（不拒写）；</li>
 * <li>{@code ai:ledger:<runId>} LIST（JSON）—— 工具执行台账：每次<b>真实执行完成</b>一条，
 * 含 {@code executedBy} 实例标识，是跨进程"不重放"的判据。<b>只追加、永不 {@code LTRIM}</b>：
 * 台账下标是 {@code ai:idem} 索引的指针，裁掉旧条目会让索引指向错记录；</li>
 * <li>{@code ai:idem:<runId>} HASH（B23L1 批③a）—— 工具执行的<b>幂等索引</b>：
 * field={@code <runId>:<toolCallId>}，value={@code <listIndex>|<outcome>|<code>|<executedBy>|<atMs>}，
 * 与台账记录在<b>同一条 Lua</b> 里写入（{@code RPUSH} + {@code HSETNX}）；读口 {@link #idemFor}
 * 命中即 {@code LINDEX} 取回原记录（O(1)），未命中回落 {@link #ledgerFor} 扫描（旧数据兼容）。
 * 同一个 HASH 里还以保留字段 {@code ~meta:<listIndex>} 存<b>元数据投影</b>
 * （{@code name/atMs/toolCallId}，不含 {@code resultText}），供 {@link #ledgerCounts}/{@link #ledgerCountSince}
 * 免读正文；它与索引同槽、同 TTL、同一次 {@code DEL}，不改变索引 field/value 任何一位；</li>
 * <li>{@code ai:lock:task:<taskId>} / {@code ai:lock:job:<taskId>:<JOBTYPE>} STRING
 * （B23L1 批③a；{@code com.example.configmgr.ai.run.LeaseKeys}）—— <b>L1 资源互斥</b>：
 * 按 taskId 记（不是 runId），值 = {@code <epoch>|<owner>}，TTL = {@code app.ai.lease.resource-ttl}；
 * 轮次回收（{@link #deleteRun}）<b>不</b>清这两键（见该方法注）；</li>
 * <li>{@code ai:xevents:<runId>} Stream —— <b>事件日志真值</b>（B23L1 批①）：{@code XADD ... MAXLEN ~ 6000}，
 * 字段 {@code seq} + 帧 JSON（崩溃前的帧不随进程消失，供 {@code GET /api/ai/events/{runId}} 重挂时回放）；
 * 每帧带单调 {@code seq}（T6 的差量补发判据），<b>心跳帧不入本归档</b>（T7，改记 {@code ai:beat:<runId>}）。
 * 单/多实例同一实现，回放 = 按 {@code seq} 读 Stream（见 {@link FrameStore}）；</li>
 * <li>{@code ai:beat:<runId>} STRING —— 心跳活动戳（毫秒时刻，TTL 随心跳续期）：
 * 心跳不占归档窗口，但"这一轮最近有过活动"仍可跨进程判定（<b>只证明进程还活着</b>，
 * 不是业务前进的证据 —— 僵尸判据看 {@code ai:activity}）；</li>
 * <li>{@code ai:activity:<runId>} STRING —— <b>业务活动锚点</b>（R1-09）：最近一条非心跳业务帧的
 * {@code atMs}，出帧点刷新；与事件日志 Stream 尾部取 max 后作为
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
public class RunStore {

	public static final String RUN_PREFIX = "ai:run:";

	/** 全局恢复候选：member=tenant/user/runId，score=下一次允许恢复检查的时刻。 */
	public static final String RECOVERY_DUE_INDEX = "ai:recovery:due";

	public static final String RECOVERY_DUE_IDENTITY_PREFIX = "ai:recovery:due:";

	/** 请求面列表的按身份窄索引；只进单键 ZSET 命令，不与全局索引做多键操作。 */
	public static final String RUN_LIST_INDEX_PREFIX = "ai:runs:";

	private static final int RECOVERY_RECONCILE_BATCH = 64;

	private static final RedisScript<Long> RECOVERY_DEFER_SCRIPT = new DefaultRedisScript<>("""
			local current = redis.call('ZSCORE', KEYS[1], ARGV[3])
			if not current or tonumber(current) ~= tonumber(ARGV[1]) then return 0 end
			redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
			if tonumber(ARGV[4]) > 0 then redis.call('PEXPIRE', KEYS[1], ARGV[4]) end
			return 1
			""", Long.class);

	private static final RedisScript<Long> RECOVERY_REMOVE_SCRIPT = new DefaultRedisScript<>("""
			local current = redis.call('ZSCORE', KEYS[1], ARGV[2])
			if not current or tonumber(current) ~= tonumber(ARGV[1]) then return 0 end
			redis.call('ZREM', KEYS[1], ARGV[2])
			return 1
			""", Long.class);

	private final AtomicLong recoveryReconcileOffset = new AtomicLong();

	/**
	 * 轻量 run 状态键前缀（{@code ai:runstate:{<tenantId>:<userId>:run:<runId>}}）。
	 *
	 * <p>与快照/待决/台账等 run 级键<b>同 hash tag</b>：因此能进同一条状态写 Lua（同槽，不
	 * {@code CROSSSLOT}），也能随 {@link #deleteRun} 的原子 {@code DEL} 族一起消失。
	 * 值与快照 {@code status} 同步维护（{@code CANCELLED} 亦覆盖 {@code cancelled=true} 形态）；
	 * <b>缺失是合法状态</b>（本键引入前的旧快照、或状态键被淘汰）—— 读侧/守卫回退解析快照。
	 */
	public static final String RUN_STATE_PREFIX = "ai:runstate:";

	public static final String PENDING_PREFIX = "ai:pending:";

	/** 上下文真值 HASH 前缀（上下文统计与压缩专项批①；{@code ai:ctx:<sessionId>}）。 */
	public static final String CONTEXT_PREFIX = "ai:ctx:";

	/** 上下文台账 LIST 前缀（{@code ai:ctx:ledger:<sessionId>}，压缩事件，最近 20 条）。 */
	public static final String CONTEXT_LEDGER_PREFIX = "ai:ctx:ledger:";

	/** 已提交压缩事实（B3）：{@code ai:ctx:events:<tenantId>:<userId>:<sessionId>} LIST，新→旧。 */
	public static final String CONTEXT_EVENTS_PREFIX = "ai:ctx:events:";

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
	 *
	 * <p>同一个 HASH 还承载<b>元数据投影</b>（{@link #IDEM_META_FIELD_PREFIX}）：每个台账下标一个
	 * {@code ~meta:<index>} 字段，只含 {@code name/atMs/toolCallId}。放在这里（而不是另起一个键）是刻意的：
	 * 同槽（同一 HASH）、同一条 {@code IDEM_PUSH} 原子写、同 TTL、{@link #deleteRun} 同一把键删净，
	 * 且不参与索引的 field 命名空间（UUID runId 不会以 {@code ~} 开头）。
	 */
	public static final String IDEM_PREFIX = "ai:idem:";

	/**
	 * 幂等索引 HASH 里元数据投影字段的保留前缀（{@code ~meta:<listIndex>}）。
	 *
	 * <p>值 = 小型 JSON {@code {"name":...,"atMs":...,"toolCallId":...}}，<b>不含 resultText</b>。
	 * field 的下标与台账位置一一对应，因此投影完整时可按下标顺序还原台账的计数口径；
	 * 旧数据/投影不完整时读侧回落到台账全表扫描（见 {@link #ledgerCounts}）。
	 */
	public static final String IDEM_META_FIELD_PREFIX = "~meta:";

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
	 * 活动锚点读取时探测的尾部帧数（事件 Stream 探测这么多条）。
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
	 * 会话 → 该会话全部 runId 的索引（{@code ai:session:runs:<sessionId>}，SET，TTL 与会话同寿）。
	 *
	 * <p>为什么要它：会话销毁要枚举"本会话的轮次"，而 {@code ai:runs} 是全局集合；
	 * 只靠它就得对所有快照逐个反序列化再比对 sessionId（生产上可能是成百上千轮）。
	 * 本索引是销毁的<b>唯一发现面</b>：首轮创建必须在<b>首笔快照之前</b>用同一原子步完成
	 * SADD + 设 TTL，写不进去就 fail-closed（此时没有快照，不会留下不可发现的轮）。
	 *
	 * <p>生命周期：{@link #create} 用单键脚本 SADD + PEXPIRE，快照写入后的联动段
	 * （{@link #afterSnapshotWrite}）再幂等补写/续期，{@link #deleteRun} SREM；
	 * 空 SET 由 Redis 自动消失。
	 */
	public static final String SESSION_RUNS_PREFIX = "ai:session:runs:";

	/**
	 * 会话 → <b>活跃</b>轮的有序索引（{@code ZSET}，score = 最近一次活跃写入的毫秒，成员 = runId）。
	 *
	 * <p>为什么要它（N1）：终态后 {@link #clearSessionCurrent(String, String)} 要回指"同会话仍活跃的最新
	 * 一轮"。旧口径先 {@code SMEMBERS ai:session:runs:<sessionId>} 再逐个 {@link #get(String)} 反序列化
	 * <b>整份快照</b> —— 每轮终态都要付 O(N) 次大 JSON 解析。本索引按更新时间倒序，正常情况下只需对
	 * 队首做一次快照确认；索引成员是否真的仍活跃<b>仍以快照复读为准</b>（stale 成员摘掉后看下一名）。
	 *
	 * <p>生命周期：{@link #markSessionCurrent(String, String)}（RUNNING/SUSPENDED 的每次快照写与确认门
	 * 心跳）写分数；终态 {@link #clearSessionCurrent(String, String)} / 回收 {@link #deleteRun(String)}
	 * 摘成员；TTL 与会话同寿。<b>老会话没有本索引</b> ⇒ 一次性回退旧口径（SMEMBERS + 快照），
	 * 命中后回填本索引。
	 */
	public static final String SESSION_ACTIVE_PREFIX = "ai:session:active:";

	/** 心跳帧的类型名（T7：唯一不落归档的帧类型）。 */
	public static final String HEARTBEAT_TYPE = "heartbeat";

	/** delta 帧的类型名（T3-2：与心跳同待遇，跳过归档）。 */
	public static final String DELTA_TYPE = "delta";

	/** 已发放最大业务序号的键前缀（T3-2 的续号锚点，TTL 与归档同寿）。 */
	public static final String SEQ_PREFIX = "ai:seq:";

	/** 本实例标识：区分"崩溃前的旧实例"与"续跑的新实例"。 */
	private final String instanceId;

	private final StringRedisTemplate redis;

	private final ObjectMapper mapper;

	private final AiProperties properties;

	private SessionActivityRenewal activityRenewal;

	@Autowired(required = false)
	void setActivityRenewal(SessionActivityRenewal activityRenewal) {
		this.activityRenewal = activityRenewal;
	}

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
	 * {@code ai:runs} 的<b>有界节流自愈</b>状态：run 索引成员（{@code tenantId/userId/runId}）→
	 * 上次补写尝试的毫秒（复审修复）。
	 *
	 * <h2>为什么需要它</h2>
	 * 首笔 {@code create} 的 {@code SADD} 失败只 WARN；若该轮此后一直是 {@code RUNNING}（长流式回答）
	 * 并中途崩溃，它就永远不在恢复发现面里 —— 只靠"创建节点补写"无法自愈。
	 *
	 * <h2>为什么不能退回"每次 save 都写"</h2>
	 * {@code RUNNING} 的写是每帧/每拍的热路径，每次 {@code SADD} 都多一次往返。
	 *
	 * <h2>口径</h2>
	 * <ul>
	 * <li>只对<b>有记录身份</b>的轮次记账（无归属 legacy 快照本来就不进恢复发现面）；</li>
	 * <li>同一 run 的补写尝试至少间隔 {@value #RUN_INDEX_REPAIR_INTERVAL_MS}ms；</li>
	 * <li><b>有界</b>：终态写与 {@link #deleteRun} 会移除自己的条目；超过
	 *     {@value #RUN_INDEX_REPAIR_CAP} 条时整体清空（宁可多补几次，也不让纯加速表无界增长）；</li>
	 * <li>键是索引成员本身（含身份）：不同租户/用户的同 runId 互不挤占节流位。</li>
	 * </ul>
	 */
	private final java.util.concurrent.ConcurrentMap<String, Long> runIndexRepairAtMs = new ConcurrentHashMap<>();

	/** 同一 run 的 {@code ai:runs} 周期补写最小间隔（60s；热路径每拍写不可接受）。 */
	static final long RUN_INDEX_REPAIR_INTERVAL_MS = 60_000L;

	/** 节流表的条数上限（只服务自愈，满了整体清空；不是业务数据）。 */
	static final int RUN_INDEX_REPAIR_CAP = 1024;

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
			redis.call('SET', KEYS[3], ARGV[4], 'PX', ARGV[3])
			return 1
			""", Long.class);

	/**
	 * <b>取消优先状态守卫</b>（无租约分支）：先读轻量状态键（{@code ai:runstate:}，缺失才解析快照原文），
	 * 阻止取消态被任何<b>非取消</b>状态覆盖（不只终态：RUNNING / SUSPENDED 的写同样不得从 CANCELLED 转出）；
	 * 写入时快照与状态键在<b>同一步</b>落库（同 tag、同 TTL）。
	 *
	 * <p>批次2 / AR-05 起守卫从"仅 DONE"先扩到 <b>DONE + FAILED</b>（三条失败出口
	 * {@code fail()} / {@code emptyReply()} / {@code AiController} 环外异常共用
	 * {@link #saveTerminalUnlessCancelled(RunSnapshot)}），再扩到<b>所有非取消状态</b>
	 * （重跑/续跑的 RUNNING、池饱和回落的 SUSPENDED 同样不得把 {@code CANCELLED} 翻案）。
	 *
	 * <p><b>轻量状态键优先</b>（性能专项）：先读 {@code KEYS[2]}（{@code ai:runstate:<runId>}）——
	 * 它只装一个状态串，比 cjson 解析整份快照便宜得多。只有<b>状态键缺失</b>（本键引入前的旧快照 /
	 * 已被淘汰）才回退解析快照原文，判定口径与旧实现逐字一致；两者都不可读时按"可用性优先"照常写。
	 * 写入时快照与状态键在同一脚本步里落库（同 TTL），状态键因此不会与快照分叉。
	 *
	 * @param KEYS[1] 快照键
	 * @param KEYS[2] 轻量状态键（与快照同槽）
	 * @param ARGV[1] 快照 JSON
	 * @param ARGV[2] TTL（毫秒）
	 * @param ARGV[3] 本次要落的状态串
	 */
	private static final RedisScript<Long> TERMINAL_SET_IF_NOT_CANCELLED_SCRIPT = new DefaultRedisScript<>("""
			-- TERMINAL_SET_IF_NOT_CANCELLED
			local state = redis.call('GET', KEYS[2])
			if state == 'CANCELLED' then
			  return 0
			end
			if not state then
			  local current = redis.call('GET', KEYS[1])
			  if current then
			    local ok, snapshot = pcall(cjson.decode, current)
			    -- 解不开的旧值不冒充取消态（本进程写出的快照恒为合法 JSON）：按可用性优先照常写
			    if ok and type(snapshot) == 'table'
			        and (snapshot.status == 'CANCELLED' or snapshot.cancelled == true) then
			      return 0
			    end
			  end
			end
			redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
			redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[2])
			return 1
			""", Long.class);

	/**
	 * 取消优先状态守卫（持租分支）：同一原子段内同时校验原 fence 与取消态；非取消状态都走本脚本。
	 *
	 * <p>取消判据与无租约分支同源（{@link #TERMINAL_SET_IF_NOT_CANCELLED_SCRIPT}）：状态键优先、
	 * 缺失才 cjson 解析快照；写快照的同时把状态键一并刷新（同 TTL）。
	 *
	 * @param KEYS[1] 快照键 / {@code KEYS[2]} fence / {@code KEYS[3]} 轻量状态键
	 */
	private static final RedisScript<Long> FENCED_TERMINAL_SET_SCRIPT = new DefaultRedisScript<>("""
			-- FENCED_TERMINAL_SET
			local fence = redis.call('GET', KEYS[2])
			if fence and tonumber(fence) > tonumber(ARGV[2]) then
			  return 0
			end
			local state = redis.call('GET', KEYS[3])
			if state == 'CANCELLED' then
			  return 0
			end
			if not state then
			  local current = redis.call('GET', KEYS[1])
			  if current then
			    local ok, snapshot = pcall(cjson.decode, current)
			    if ok and type(snapshot) == 'table'
			        and (snapshot.status == 'CANCELLED' or snapshot.cancelled == true) then
			      return 0
			    end
			  end
			end
			redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
			redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
			redis.call('SET', KEYS[3], ARGV[4], 'PX', ARGV[3])
			return 1
			""", Long.class);

	/**
	 * <b>仅当快照不存在时创建</b>（无租约分支）：恢复路径的条件写口。
	 *
	 * <p>为什么必须是原子步："读到不存在 ⇒ 用执行信封重建"之间若有并发创建者（另一实例刚恢复、
	 * 或这一轮刚被别处重建），无条件 {@code SET} 会把那份更新的运行事实覆盖掉。存在性判定与写
	 * 在同一 {@code EVAL} 内：已存在 ⇒ 0（调用方改读并发那一份），不存在 ⇒ 写并返回 1。
	 *
	 * <p>轻量状态键与快照同一步落库（同 TTL）：判活/取消优先读它，不必反序列化整份快照。
	 *
	 * @param KEYS[1] 快照键 / {@code KEYS[2]} 轻量状态键
	 * @param ARGV[1] 快照 JSON / {@code ARGV[2]} TTL / {@code ARGV[3]} 状态串
	 */
	private static final RedisScript<Long> CREATE_IF_ABSENT_SCRIPT = new DefaultRedisScript<>("""
			-- RUN_CREATE_IF_ABSENT
			if redis.call('EXISTS', KEYS[1]) == 1 then
			  return 0
			end
			redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
			redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[2])
			return 1
			""", Long.class);

	/**
	 * 持租分支的<b>仅当不存在时创建</b>：同一原子段内先判存在性，再判 fence（陈旧 epoch 不建），
	 * 创建成功才把 fence 推到本次 epoch —— fencing 语义与 {@link #FENCED_SET_SCRIPT} 一致。
	 *
	 * @param KEYS[1] 快照键 / {@code KEYS[2]} fence / {@code KEYS[3]} 轻量状态键
	 */
	private static final RedisScript<Long> FENCED_CREATE_IF_ABSENT_SCRIPT = new DefaultRedisScript<>("""
			-- RUN_FENCED_CREATE_IF_ABSENT
			if redis.call('EXISTS', KEYS[1]) == 1 then
			  return 0
			end
			local fence = redis.call('GET', KEYS[2])
			if fence and tonumber(fence) > tonumber(ARGV[2]) then
			  return 0
			end
			redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
			redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
			redis.call('SET', KEYS[3], ARGV[4], 'PX', ARGV[3])
			return 1
			""", Long.class);

	private static final RedisScript<List> DECIDE_PENDING_SCRIPT = new DefaultRedisScript<>("""
			-- DECIDE_PENDING: decided guard 与完整 pending 同槽原子写
			-- KEYS: pending, decided, claim
			-- ARGV: field, fullPendingJson, decidedValue, ttlMs, expectedGuard
			local field = ARGV[1]
			local raw = redis.call('HGET', KEYS[1], field)
			if not raw then return {0, '', 'MISSING'} end
			local ok, obj = pcall(cjson.decode, raw)
			if not ok or type(obj) ~= 'table' then return {0, '', 'BAD_PENDING'} end
			if obj['status'] ~= 'PENDING' then return {0, '', 'CONFLICT_STATUS'} end
			local requested = ARGV[3]:match('^([^|]+)') or ''
			local prev = redis.call('HGET', KEYS[2], field)
			local repair = ARGV[5] ~= '' and prev == ARGV[5] and requested == (prev:match('^([^|]+)') or '')
			if prev and not repair then return {0, prev, 'CONFLICT_DECIDED'} end
			if not prev and ARGV[5] ~= '' then return {0, '', 'CONFLICT_DECIDED'} end
			local evidence = obj['executed'] == true or (tonumber(obj['claimedAtMs']) or 0) > 0
			    or (tonumber(obj['claimedEpoch']) or 0) > 0 or (tonumber(obj['executedAtMs']) or 0) > 0
			if obj['executedBy'] and obj['executedBy'] ~= cjson.null then evidence = true end
			if redis.call('HEXISTS', KEYS[3], field) == 1 or evidence then
			  return {0, prev or '', 'CONFLICT_CLAIMED'}
			end
			redis.call('HSET', KEYS[1], field, ARGV[2])
			if not repair then redis.call('HSET', KEYS[2], field, ARGV[3]) end
			redis.call('PEXPIRE', KEYS[1], ARGV[4])
			redis.call('PEXPIRE', KEYS[2], ARGV[4])
			redis.call('PEXPIRE', KEYS[3], ARGV[4])
			return {1, repair and prev or ARGV[3], repair and 'REPAIRED' or 'APPLIED'}
			""", List.class);

	private static final RedisScript<List> CLAIM_EXECUTION_SCRIPT = new DefaultRedisScript<>("""
			-- CLAIM_EXECUTION: 取消、fence、状态、claim 与完整 pending 原子推进
			-- KEYS: claim, pending, fence, cancel
			-- ARGV: field, epoch, instance, claimedAtMs, ttlMs
			local field = ARGV[1]
			local epoch = tonumber(ARGV[2]) or 0
			local fence = tonumber(redis.call('GET', KEYS[3]) or '0') or 0
			if epoch > 0 and fence > epoch then return {0, 'STALE_EPOCH', fence} end
			if redis.call('EXISTS', KEYS[4]) == 1 then return {0, 'CANCELLED', fence} end
			local raw = redis.call('HGET', KEYS[2], field)
			if not raw then return {0, 'MISSING', fence} end
			local ok, obj = pcall(cjson.decode, raw)
			if not ok or type(obj) ~= 'table' then return {0, 'BAD_PENDING', fence} end
			local status = obj['status']
			if status ~= 'APPROVED' and status ~= 'PENDING' and status ~= 'EXECUTED' then
			  return {0, 'CONFLICT_STATUS', fence}
			end
			local evidence = obj['executed'] == true or (tonumber(obj['claimedAtMs']) or 0) > 0
			    or (tonumber(obj['claimedEpoch']) or 0) > 0 or (tonumber(obj['executedAtMs']) or 0) > 0
			if obj['executedBy'] and obj['executedBy'] ~= cjson.null then evidence = true end
			if evidence or redis.call('HEXISTS', KEYS[1], field) == 1 then
			  return {0, 'ALREADY_CLAIMED', fence}
			end
			obj['claimedAtMs'] = tonumber(ARGV[4])
			obj['claimedEpoch'] = epoch
			obj['executed'] = true
			obj['executedBy'] = ARGV[3]
			redis.call('HSET', KEYS[1], field, tostring(epoch) .. '|' .. ARGV[3])
			redis.call('HSET', KEYS[2], field, cjson.encode(obj))
			redis.call('PEXPIRE', KEYS[1], ARGV[5])
			redis.call('PEXPIRE', KEYS[2], ARGV[5])
			return {1, 'WON', fence}
			""", List.class);

	private static final RedisScript<List> CANCEL_PENDING_SCRIPT = new DefaultRedisScript<>("""
			-- CANCEL_PENDING: 只允许原 epoch 内未认领未执行的 PENDING/APPROVED 收敛为取消
			-- KEYS: pending, decided, claim, fence
			-- ARGV: field, fullPendingJson, decidedValue, ttlMs, expectedEpoch
			local field = ARGV[1]
			local epoch = tonumber(ARGV[5]) or 0
			local fence = tonumber(redis.call('GET', KEYS[4]) or '0') or 0
			if epoch > 0 and fence > epoch then return {0, 'STALE_EPOCH'} end
			local raw = redis.call('HGET', KEYS[1], field)
			if not raw then return {0, 'MISSING'} end
			local ok, obj = pcall(cjson.decode, raw)
			if not ok or type(obj) ~= 'table' then return {0, 'BAD_PENDING'} end
			local status = obj['status']
			if status ~= 'PENDING' and status ~= 'APPROVED' then return {0, 'CONFLICT_STATUS'} end
			if redis.call('HEXISTS', KEYS[3], field) == 1 then return {0, 'CONFLICT_CLAIMED'} end
			local evidence = obj['executed'] == true or (tonumber(obj['claimedAtMs']) or 0) > 0
			    or (tonumber(obj['claimedEpoch']) or 0) > 0 or (tonumber(obj['executedAtMs']) or 0) > 0
			if obj['executedBy'] and obj['executedBy'] ~= cjson.null then evidence = true end
			if evidence then return {0, 'CONFLICT_CLAIMED'} end
			local prev = redis.call('HGET', KEYS[2], field)
			if prev and (status == 'PENDING' or prev:match('^([^|]+)') ~= 'APPROVED') then
			  return {0, 'CONFLICT_DECIDED'}
			end
			redis.call('HSET', KEYS[1], field, ARGV[2])
			redis.call('HSET', KEYS[2], field, ARGV[3])
			redis.call('PEXPIRE', KEYS[1], ARGV[4])
			redis.call('PEXPIRE', KEYS[2], ARGV[4])
			redis.call('PEXPIRE', KEYS[3], ARGV[4])
			return {1, 'CANCELLED'}
			""", List.class);

	private static final RedisScript<List> COMPLETE_EXECUTION_SCRIPT = new DefaultRedisScript<>("""
			-- COMPLETE_EXECUTION: 执行回写必须匹配 claim 与 epoch
			-- KEYS: pending, claim, fence
			-- ARGV: field, expectedClaim, fullPendingJson, ttlMs, expectedEpoch
			local field = ARGV[1]
			local epoch = tonumber(ARGV[5]) or 0
			local fence = tonumber(redis.call('GET', KEYS[3]) or '0') or 0
			if epoch > 0 and fence > epoch then return {0, 'STALE_EPOCH'} end
			if redis.call('HGET', KEYS[2], field) ~= ARGV[2] then return {0, 'CONFLICT_CLAIMED'} end
			local raw = redis.call('HGET', KEYS[1], field)
			if not raw then return {0, 'MISSING'} end
			local ok, obj = pcall(cjson.decode, raw)
			if not ok or type(obj) ~= 'table' then return {0, 'BAD_PENDING'} end
			local status = obj['status']
			if status ~= 'APPROVED' and status ~= 'PENDING' and status ~= 'EXECUTED' then
			  return {0, 'CONFLICT_STATUS'}
			end
			redis.call('HSET', KEYS[1], field, ARGV[3])
			redis.call('PEXPIRE', KEYS[1], ARGV[4])
			redis.call('PEXPIRE', KEYS[2], ARGV[4])
			return {1, 'APPLIED'}
			""", List.class);

	private static final RedisScript<List> COMPLETE_EXECUTION_WITH_LEDGER_SCRIPT = new DefaultRedisScript<>("""
			-- COMPLETE_EXECUTION_WITH_LEDGER: 取消条件、完成态、台账与幂等索引原子推进
			-- KEYS: pending, claim, fence, ledger, idem, cancel
			-- ARGV: field, expectedClaim, fullPendingJson, ttlMs, expectedEpoch,
			--       ledgerJson, idemField, indexTail, metaPrefix, metaJson, respectCancel
			local field = ARGV[1]
			local epoch = tonumber(ARGV[5]) or 0
			local fence = tonumber(redis.call('GET', KEYS[3]) or '0') or 0
			if epoch > 0 and fence > epoch then return {0, 'STALE_EPOCH'} end
			if redis.call('HGET', KEYS[2], field) ~= ARGV[2] then return {0, 'CONFLICT_CLAIMED'} end
			if ARGV[11] == '1' and redis.call('EXISTS', KEYS[6]) == 1 then return {0, 'CANCELLED'} end
			local raw = redis.call('HGET', KEYS[1], field)
			if not raw then return {0, 'MISSING'} end
			local ok, obj = pcall(cjson.decode, raw)
			if not ok or type(obj) ~= 'table' then return {0, 'BAD_PENDING'} end
			local status = obj['status']
			if status ~= 'APPROVED' and status ~= 'PENDING' and status ~= 'EXECUTED' then
			  return {0, 'CONFLICT_STATUS'}
			end
			local index = redis.call('LLEN', KEYS[4])
			redis.call('RPUSH', KEYS[4], ARGV[6])
			redis.call('HSETNX', KEYS[5], ARGV[7], tostring(index) .. '|' .. ARGV[8])
			redis.call('HSET', KEYS[5], ARGV[9] .. tostring(index), ARGV[10])
			redis.call('HSET', KEYS[1], field, ARGV[3])
			for i = 1, 5 do redis.call('PEXPIRE', KEYS[i], ARGV[4]) end
			return {1, 'APPLIED'}
			""", List.class);

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
	 * <p><b>元数据投影</b>（C65）：同一条脚本再 {@code HSET} 一个保留字段
	 * {@code ~meta:<index>}（{@code name/atMs/toolCallId}，无 {@code resultText}）—— 计数类读取只需
	 * 这一份小投影，不必把整份台账（含大正文）拉过网络再逐条解析。投影与索引同键同寿，<b>不写索引
	 * 的 field/value</b>，因此"台账有、索引没有"的旧数据回落口径不受影响。
	 *
	 * @param KEYS[1] 台账 LIST 键 {@code ai:ledger:<runId>}
	 * @param KEYS[2] 幂等索引 HASH 键 {@code ai:idem:<runId>}
	 * @param ARGV[1] 台账记录 JSON
	 * @param ARGV[2] 索引 field（{@code <runId>:<toolCallId>}）
	 * @param ARGV[3] 索引 value 的后半段（{@code <outcome>|<code>|<executedBy>|<atMs>}；下标由脚本前插）
	 * @param ARGV[4] TTL（毫秒，两个键同寿 = {@code app.ai.session-ttl}）
	 * @param ARGV[5] 元数据投影 field 前缀（{@link #IDEM_META_FIELD_PREFIX}）
	 * @param ARGV[6] 元数据投影 JSON（{@code {"name":...,"atMs":...,"toolCallId":...}}）
	 */
	private static final RedisScript<Long> IDEM_PUSH_SCRIPT = new DefaultRedisScript<>("""
			-- IDEM_PUSH
			local index = redis.call('LLEN', KEYS[1])
			redis.call('RPUSH', KEYS[1], ARGV[1])
			redis.call('HSETNX', KEYS[2], ARGV[2], tostring(index) .. '|' .. ARGV[3])
			redis.call('HSET', KEYS[2], ARGV[5] .. tostring(index), ARGV[6])
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
	 * 业务序号<b>下界</b>的单键原子 max + 续期：{@code ai:seq:<runId>}（快照/序号同时丢失后的恢复批）。
	 *
	 * <p>为什么必须是 Lua：Java 侧 {@code GET → 比较 → SET} 会把并发发放的新号回拨（与本类活动锚点
	 * 同一类窗口）。恢复只需保证"下一帧 &gt; 信封里的 seqFloor"，且<b>只升不降</b>：
	 * 当前号已经更大 ⇒ 只续期、不改值。
	 *
	 * @param KEYS[1] {@code ai:seq:<runId>}
	 * @param ARGV[1] 下界（信封的 {@code seqFloor}）
	 * @param ARGV[2] TTL（毫秒，{@code app.ai.session-ttl}）
	 */
	private static final RedisScript<Long> RAISE_SEQ_SCRIPT = new DefaultRedisScript<>("""
			-- RAISE_SEQ
			local current = tonumber(redis.call('GET', KEYS[1]) or '0')
			if not current then current = 0 end
			local floor = tonumber(ARGV[1]) or 0
			if floor > current then
			  redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
			  return floor
			end
			redis.call('PEXPIRE', KEYS[1], ARGV[2])
			return current
			""", Long.class);

	/**
	 * session→current 的<b>单键 compare-and-delete</b>（MI1）：{@code ai:session:current:<sessionId>}。
	 *
	 * <p>为什么必须是 Lua：Java 侧 {@code GET → 算回退 → DEL} 之间，同会话的新轮可能已经把
	 * current 改成自己 —— 过期的 GET 会让终态轮把<b>别人的</b> current 删掉。单键脚本在 Redis 的
	 * 串行执行语义下"读-比-删"一次完成：只有 current 仍等于本 runId 才删。
	 *
	 * <p><b>只碰 current 这一个键</b>：活跃 ZSET 不放进同一脚本（键不带同槽 tag，Cluster 下会
	 * {@code CROSSSLOT}）；多实例强一致不在本修范围。
	 *
	 * @param KEYS[1] {@code ai:session:current:<sessionId>}
	 * @param ARGV[1] 期望的 runId（本轮的 runId）
	 * @return 1 = 已删；0 = current 已不是本 runId（不动作）
	 */
	private static final RedisScript<Long> SESSION_CURRENT_CAS_DELETE_SCRIPT = new DefaultRedisScript<>("""
			-- SESSION_CURRENT_CAS_DELETE
			if redis.call('GET', KEYS[1]) == ARGV[1] then
			  return redis.call('DEL', KEYS[1])
			end
			return 0
			""", Long.class);

	/**
	 * session→current 的<b>单键 compare-and-set</b>（MI1）：只有 current 仍等于本 runId 才回填
	 * fallback，且 TTL 保持 {@code app.ai.session-ttl}（与会话/快照同寿）。
	 *
	 * <p>与 {@link #SESSION_CURRENT_CAS_DELETE_SCRIPT} 同因：{@code GET → 算回退 → SET} 的窗口里
	 * current 可能已被新轮接管，无条件 SET 会把新轮的索引覆盖回旧轮。脚本同时承担 TTL 设置
	 * （{@code SET ... PX}），不额外发一次 {@code EXPIRE}。
	 *
	 * @param KEYS[1] {@code ai:session:current:<sessionId>}
	 * @param ARGV[1] 期望的 runId（本轮的 runId）
	 * @param ARGV[2] fallback runId（回填值）
	 * @param ARGV[3] TTL（毫秒，{@code app.ai.session-ttl}）
	 * @return 1 = 已写；0 = current 已不是本 runId（不动作）
	 */
	private static final RedisScript<Long> SESSION_CURRENT_CAS_SET_SCRIPT = new DefaultRedisScript<>("""
			-- SESSION_CURRENT_CAS_SET
			if redis.call('GET', KEYS[1]) == ARGV[1] then
			  redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
			  return 1
			end
			return 0
			""", Long.class);

	/**
	 * session→current 的<b>单键 compare-and-PEXPIRE</b>（MI2）：值仍匹配才续期，不匹配不动作。
	 *
	 * <p>为什么必须是 Lua：确认门等待循环的续期是 {@code GET → 比较 → EXPIRE}，读到旧值之后
	 * current 可能已被同会话的新轮接管 —— 无条件 {@code EXPIRE} 会把<b>新轮的</b> TTL 续掉
	 * （索引寿命被旧轮的心跳拖着走）。脚本把比较与续期合成一个原子步。
	 *
	 * @param KEYS[1] {@code ai:session:current:<sessionId>}
	 * @param ARGV[1] 期望的 runId（本轮的 runId）
	 * @param ARGV[2] TTL（毫秒，{@code app.ai.session-ttl}）
	 * @return 1 = 已续期；0 = 值不匹配/键不存在（不动作）
	 */
	private static final RedisScript<Long> SESSION_CURRENT_CAS_PEXPIRE_SCRIPT = new DefaultRedisScript<>("""
			-- SESSION_CURRENT_CAS_PEXPIRE
			if redis.call('GET', KEYS[1]) == ARGV[1] then
			  return redis.call('PEXPIRE', KEYS[1], ARGV[2])
			end
			return 0
			""", Long.class);

	/**
	 * 会话→run 索引的<b>单键原子登记</b>：SADD 与 PEXPIRE 在同一个脚本步里完成。
	 *
	 * <p>为什么必须原子：索引是会话销毁的唯一发现面（全局 {@code ai:runs} 回退已删除）。
	 * 拆成 {@code SADD → EXPIRE} 时，进程在两步之间死掉会留下<b>无 TTL 的索引</b>；
	 * 反过来先 EXPIRE 后 SADD 则可能"TTL 已设、成员未落"。同一步写入让"有成员就有 TTL"始终成立。
	 *
	 * <p>只是单键脚本（{@code ai:session:runs:<sid>} 不带 hash tag）：Cluster 下仍落在
	 * 同一个 slot，不需要 {@code CROSSSLOT} 处理。
	 *
	 * @param KEYS[1] {@code ai:session:runs:<sessionId>}
	 * @param ARGV[1] runId
	 * @param ARGV[2] TTL（毫秒，{@code app.ai.session-ttl}）
	 * @return 1 = 已登记
	 */
	private static final RedisScript<Long> SESSION_RUNS_REGISTER_SCRIPT = new DefaultRedisScript<>("""
			-- SESSION_RUNS_REGISTER
			redis.call('SADD', KEYS[1], ARGV[1])
			redis.call('PEXPIRE', KEYS[1], ARGV[2])
			return 1
			""", Long.class);

	/**
	 * 轻量状态键的<b>值匹配删除</b>（取消写失败时的回滚）：只有现值仍等于本次写入的状态才删。
	 *
	 * <p>为什么不能直接 {@code DEL}：状态键与快照在同一时刻可能已被并发写者推进（例如本实例的
	 * 取消写失败后，另一实例把轮次收尾成 {@code DONE} 并写了 {@code DONE}）——盲目删除会把
	 * <b>并发 DONE 的状态</b>清掉，守卫随后回退解析快照，若快照读取失败就会放大成"不可知"。
	 * 值匹配保证只回滚<b>自己写的那一份</b>。
	 *
	 * @param KEYS[1] {@code ai:runstate:<runId>} / {@code ARGV[1]} 期望值（本次写入的状态串）
	 */
	private static final RedisScript<Long> RUN_STATE_CAS_DELETE_SCRIPT = new DefaultRedisScript<>("""
			-- RUN_STATE_CAS_DELETE
			if redis.call('GET', KEYS[1]) == ARGV[1] then
			  return redis.call('DEL', KEYS[1])
			end
			return 0
			""", Long.class);

	/**
	 * 会话→活跃轮 ZSET 的<b>单键原子登记</b>：{@code ZADD} 与 {@code PEXPIRE} 同一个脚本步。
	 *
	 * <p>为什么必须原子：拆成 {@code ZADD → EXPIRE} 时，进程在两步之间死掉会留下<b>无 TTL 的活跃索引</b>
	 * —— 它会永久参与"终态后回指"的候选排序（活轮/死轮混在一起，且键永不消失）。
	 * 单键脚本（{@code ai:session:active:<sid>} 不带 hash tag）在 Cluster 下仍落同一 slot，
	 * 不需要 {@code CROSSSLOT} 处理。
	 *
	 * @param KEYS[1] {@code ai:session:active:<sessionId>}
	 * @param ARGV[1] runId / {@code ARGV[2]} score（毫秒）/ {@code ARGV[3]} TTL（毫秒）
	 */
	private static final RedisScript<Long> SESSION_ACTIVE_TOUCH_SCRIPT = new DefaultRedisScript<>("""
			-- SESSION_ACTIVE_TOUCH
			redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
			redis.call('PEXPIRE', KEYS[1], ARGV[3])
			return 1
			""", Long.class);

	/**
	 * <b>同槽批量续期</b>（RTT 收口）：一条 {@code EVAL} 续完整组键，替代逐键 {@code EXPIRE}。
	 *
	 * <p><b>为什么可以合批</b>：run 级续期面的键（含取消标志）都带同一个 hash tag
	 * （{@code {<tenantId>:<userId>:run:<runId>}} ⇒ 同一槽），Cluster 下多键脚本合法；
	 * 其它不带 tag 的读取键仍按槽分组单独续期，不与这些键混用。
	 *
	 * <p><b>逐键隔离</b>：脚本内每个键用 {@code redis.pcall} 包裹，单键失败（WRONGTYPE 等）只把该位标成
	 * {@code -1}，其余键照常续期；返回数组与 KEYS 一一对应。连接类失败由 Java 侧沿 cause 链识别后
	 * 立即收手，并把剩余键计为"未尝试"（I-3：不逐个等 {@code spring.data.redis.timeout}）。
	 *
	 * <p>脚本面不可用（替身/包装层不支持 {@code EVAL}、脚本被禁）时退回逐键 {@code EXPIRE}：
	 * 语义完全不变，只多付 RTT —— 续期是养活性维护，不值得因为执行形态不同就整面放弃。
	 *
	 * <p><b>为什么返回字符串而不是数组</b>：模板是 {@code StringRedisTemplate}，脚本返回表/数组时
	 * Spring 会按返回值类型（MULTI）<b>逐元素反序列化</b>，而 Lua 数字经 String 序列化器不是
	 * {@code byte[]} ⇒ 运行期反序列化失败。用「逗号分隔的数字串」把每个键的结论带回来，走 String
	 * 序列化器（与本模板的其余脚本同一条路径），格式简单且不依赖客户端类型的细节。
	 *
	 * @param KEYS 同一槽的键（顺序即返回串里各段顺序）
	 * @param ARGV[1] TTL（毫秒）
	 * @return 逗号分隔的每键结果（1 = 已续期；0 = 键不存在；-1 = 该键续期失败）
	 */
	private static final RedisScript<String> RENEW_SAME_SLOT_SCRIPT = new DefaultRedisScript<>("""
			-- RENEW_SAME_SLOT
			local results = {}
			for i = 1, #KEYS do
			  local res = redis.pcall('PEXPIRE', KEYS[i], ARGV[1])
			  if type(res) == 'table' and res.err then
			    results[i] = '-1'
			  else
			    results[i] = tostring(res)
			  end
			end
			return table.concat(results, ',')
			""", String.class);

	/** 认领结论；{@code CANCELLED} 与失败/他实例认领分开，供调用方走取消收尾。 */
	public enum ClaimOutcome {

		/** 本次由当前实例认领成功（可以执行）。 */
		WON,

		/** 已被他实例认领（不得执行，等它执行完）。 */
		LOST,

		/** fence 已推进到更高 epoch：本实例的续跑权已被接管（不得执行）。 */
		STALE_FENCE,

		/** 认领脚本已见取消事实：未写入新 claim，不得执行。 */
		CANCELLED

	}

	/**
	 * 认领结论。
	 *
	 * @param outcome 认领结论
	 * @param epoch 本次认领试图使用的租约 epoch（0 = 无租约路径）
	 * @param fence 认领时读到的 fence（0 = 无 fence 键/未持租未读）
	 */
	public record ClaimResult(ClaimOutcome outcome, long epoch, long fence) {

		/** 是否认领成功（{@link ClaimOutcome#WON}）。 */
		@JsonIgnore
		public boolean isWon() {
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
	 * <h2>tag 形态（租户任务2：tag 同时含身份与 runId）</h2>
	 * {@code ai:run:{<tenantId>:<userId>:run:<runId>}} —— tag 放在前缀之后，前缀本身保持可 grep
	 * （{@link SessionKeyRegistry} 的登记锚点、运维面 {@code keys} 段都按前缀看）。
	 *
	 * <p><b>为什么 tag 里必须有身份</b>：runId 不是跨租户唯一性证明 —— 两个租户各自生成同形 runId
	 * 是合法且必然的。tag 只有 runId 时，后建的一方会读到别人的快照、{@link #deleteRun} 会删掉
	 * 别人那一轮的 13 键。身份进 tag 之后，同 id 不同身份落在两个键上，读写删天然分开。
	 *
	 * <p><b>无身份上下文</b>：拼键直接失败（不存在无身份 run 键）。
	 */
	public static String runTag(String runId) {
		return RunKeyScope.tag(runId);
	}

	/** 显式身份的 run tag（按记录恢复身份的路径用；不读线程上下文）。 */
	public static String runTag(long tenantId, long userId, String runId) {
		return RunKeyScope.tag(tenantId, userId, runId);
	}

	/**
	 * run 级键名的<b>唯一拼接口</b>：{@code <prefix>{[<tenantId>:<userId>:]run:<runId>}}。
	 *
	 * <p>所有"多键脚本/多键 DEL 会碰到"的 run 级键都从这里出（见 {@link #runTag(String)} 的清单）；
	 * 只有单键命令使用的键才允许留在裸前缀上。
	 */
	public static String runScopedKey(String prefix, String runId) {
		return RunKeyScope.scoped(prefix, runId);
	}

	/** 显式身份的 run 级键（系统恢复/迁移：身份来自记录，不来自线程）。 */
	public static String runScopedKey(String prefix, long tenantId, long userId, String runId) {
		return RunKeyScope.scoped(prefix, tenantId, userId, runId);
	}

	public String runKey(String runId) {
		return runScopedKey(RUN_PREFIX, runId);
	}

	/**
	 * 轻量状态键名（{@code ai:runstate:{run:<runId>}}；与快照同 tag ⇒ 同槽）。
	 *
	 * <p>只装一个状态串，供判活/取消优先守卫读取：不必反序列化整份快照。读取一律走
	 * {@link #runStatus(String)}（状态键缺失时回退快照），不要在别处直接 {@code GET}。
	 */
	public String runStateKey(String runId) {
		return runScopedKey(RUN_STATE_PREFIX, runId);
	}

	/**
	 * 一次状态写要落到轻量状态键上的值。
	 *
	 * <p>{@code cancelled=true} 时即使 {@code status} 不是 {@code CANCELLED} 也记 {@code CANCELLED}：
	 * 守卫的旧判据是"status=CANCELLED <b>或</b> cancelled=true"，状态键必须保住同一口径，
	 * 否则一条 {@code cancelled=true + status=RUNNING} 的中间态会让后续写把取消翻案。
	 */
	private static String stateOf(RunSnapshot snapshot) {
		if (snapshot == null) {
			return "";
		}
		if (snapshot.isCancelled() || RunSnapshot.CANCELLED.equals(snapshot.getStatus())) {
			return RunSnapshot.CANCELLED;
		}
		String status = snapshot.getStatus();
		return StringUtils.defaultString(status);
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
		return runId + ":" + StringUtils.defaultString(toolCallId);
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
	 * <li>新键形 {@code ai:lease:run:{[<tenantId>:<userId>:]run:<runId>}} —— 取 tag 内层
	 * （{@link RunKeyScope#parseScopedKey}，顺带承认 legacy tagged 形态 {@code {run:<runId>}}）；</li>
	 * <li>旧裸键形 {@code ai:lease:run:<runId>} —— 原样返回前缀之后的部分。
	 * <b>这是纯防御分支，当前进程内不可达</b>（评审 Minor 8）：租约对象不跨进程传递，新进程只会
	 * 拿自己 {@code runLeaseKey()} 拼出来的 tagged 键。留着它是为了"键形再过一次渡"时不至于
	 * 把未识别的键误判成 runId（判据退化比误释放安全）。误解析风险也可忽略：runId 是 UUID，
	 * 不会自带 {@code {run:}} 形状。</li>
	 * </ul>
	 *
	 * <p><b>身份口径</b>：本方法只回答 runId（调用方已经在自己的身份作用域里）。需要"键属于哪个身份"
	 * 的路径走 {@link #runRefFromLeaseKey(String)} —— 无身份 tag 的旧键在那里返回 {@code null}，
	 * 不会猜成任何真实身份。
	 *
	 * @return runId；键不匹配/为空返回 {@code null}（调用方按"没有可靠判据"处理）
	 */
	public static String runIdFromLeaseKey(String key) {
		if (key == null || !key.startsWith(LEASE_RUN_PREFIX)) {
			return null;
		}
		RunKeyScope.RunRef ref = RunKeyScope.parseScopedKey(LEASE_RUN_PREFIX, key);
		if (ref != null) {
			return ref.runId();
		}
		String tail = key.substring(LEASE_RUN_PREFIX.length());
		int open = tail.indexOf("{run:");
		if (open >= 0 && tail.endsWith("}")) {
			String inner = tail.substring(open + "{run:".length(), tail.length() - 1);
			return inner.isEmpty() ? null : inner;
		}
		return tail.isEmpty() ? null : tail;
	}

	/**
	 * 从续跑租约键反解<b>身份 + runId</b>（系统恢复/跨实例接管按记录恢复身份的唯一入口）。
	 *
	 * @return 复合归属；旧裸键或 legacy tag（无身份）返回 {@code null} —— 无归属键不得被猜成任何身份
	 */
	public static RunKeyScope.RunRef runRefFromLeaseKey(String key) {
		return key == null ? null : RunKeyScope.parseScopedKey(LEASE_RUN_PREFIX, key);
	}

	/** fence 键名（{@code ai:fence:run:{run:<runId>}}；与快照同槽 = FENCED_SET 的前提）。 */
	public String fenceKey(String runId) {
		return runScopedKey(FENCE_RUN_PREFIX, runId);
	}

	// ── 轮次快照 ────────────────────────────────────────────────────────────

	/**
	 * 建立一轮的外置快照：<b>身份、模型身份、生效参数与请求原文必须在同一笔里完整冻结</b>。
	 *
	 * <p>为什么冻结在"创建"这一笔：同一 run 的续跑可能落在几分钟甚至几天之后，此间用户可能改了
	 * 全局默认温度/默认模型。续跑重算会让同一轮前后半段跑在不同参数上，而历史与 {@code done.model}
	 * 都说"同一轮"。因此生效值（含 null）随第一笔快照固化，续跑只读不改。
	 *
	 * <p>请求原文同样并进这一笔：终态补写用户条目只认快照里的 {@code requestText}，而快照历史
	 * 会被 {@code prompt.getInstructions()}（请求侧未标记的 user 副本）多次重写 —— 原文单独存一份
	 * 才不会被覆盖。字段随首笔 {@code persist} 落库，不需要建快照之后再来一次整对象 save。
	 *
	 * <h2>失败语义</h2>
	 * 首笔快照是这一轮<b>唯一的持久面</b>：写不进去就没有重挂、没有续跑、没有取消终态可落。
	 * 因此本方法<b>显式判定</b>那一笔写，失败即失败：
	 * <ul>
	 * <li>被写口拒写（当前快照已是取消态 / 本实例对该轮无续跑权）⇒ 抛
	 *     {@link AiStateUnavailableException}；</li>
	 * <li>Redis 写异常（序列化/连接/脚本失败）⇒ 同一异常类型上抛（由 {@link #persist} 抛出）；</li>
	 * <li>首笔快照<b>之前</b>先原子登记会话→run 索引；该写失败 ⇒ 直接抛
	 *     {@link AiStateUnavailableException}，<b>没有快照</b>（不会留下"有快照但销毁发现不了"的轮）；</li>
	 * <li>索引已登记、首笔快照被拒写/写异常 ⇒ 同一异常类型上抛；索引成员可能指向既有取消态快照，
	 *     也可能成为无快照的死成员 —— 枚举按快照缺失/归属跳过，并随 TTL 收敛；</li>
	 * <li>全局 {@code ai:runs} 是恢复发现面：首笔快照落库后<b>在本方法内</b> best-effort 补写，
	 *     失败只 WARN，不升级成请求失败（下一次创建/恢复会再补）。</li>
	 * </ul>
	 *
	 * @param providerId 库内 provider id；{@code null} = env 合成行（种子尚未落库/无 DB 行）
	 * @param modelId 本轮使用的模型名（请求体 {@code model} 的原值）
	 * @param frozen 生效参数（必须非 null；字段可为 null = 当时没有值）
	 * @param clientRequestId 前端在 /chat 发送前生成、并存于本页签镜像的请求 id
	 * @param requestText 本轮用户请求原文（必须非空白）
	 * @param turnStartedAtMs 本轮请求起点（epoch ms，&gt; 0）
	 * @throws AiStateUnavailableException 首笔快照未落库
	 * @throws IllegalArgumentException 缺身份/请求原文/请求起点，或未冻结参数
	 */
	public RunSnapshot create(String runId, String sessionId, AiContext context, List<Message> history,
			MessageJsonCodec codec, Long providerId, String modelId, RunSnapshot.FrozenParams frozen,
			String clientRequestId, String requestText, long turnStartedAtMs) {
		validateInitialSnapshot(runId, modelId, requestText, turnStartedAtMs, frozen);
		TenantUser identity = RequestContextUtils.require();
		RunSnapshot snapshot = newInitialSnapshot(runId, sessionId, context, history, codec, providerId, modelId,
				frozen, clientRequestId, requestText, turnStartedAtMs, identity);
		registerSessionRun(runId, sessionId, identity);
		primeRunIndexRepair(snapshot, runId);
		if (!isPersist(snapshot)) {
			throw new AiStateUnavailableException("Failed to write run snapshot " + runId
					+ " (initial snapshot write refused: current snapshot is cancelled or this instance lacks resume authority)");
		}
		addRunIndexBestEffort(snapshot, runId);
		return snapshot;
	}

	private static void validateInitialSnapshot(String runId, String modelId, String requestText, long turnStartedAtMs,
			RunSnapshot.FrozenParams frozen) {
		if (frozen == null) {
			throw new IllegalArgumentException("Initial snapshot must freeze effective parameters runId=" + runId);
		}
		if (StringUtils.isBlank(modelId)) {
			throw new IllegalArgumentException("Initial snapshot must freeze the model name runId=" + runId);
		}
		if (StringUtils.isBlank(requestText)) {
			throw new IllegalArgumentException("Initial snapshot must record the original request runId=" + runId);
		}
		if (turnStartedAtMs <= 0L) {
			throw new IllegalArgumentException("Initial snapshot must record the request start runId=" + runId);
		}
	}

	private RunSnapshot newInitialSnapshot(String runId, String sessionId, AiContext context, List<Message> history,
			MessageJsonCodec codec, Long providerId, String modelId, RunSnapshot.FrozenParams frozen,
			String clientRequestId, String requestText, long turnStartedAtMs, TenantUser identity) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(sessionId);
		snapshot.setClientRequestId(clientRequestId);
		snapshot.setRequestText(requestText);
		snapshot.setTurnStartedAtMs(turnStartedAtMs);
		snapshot.setTenantId(identity.tenantId());
		snapshot.setUserId(identity.userId());
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setContext(ObjectUtils.getIfNull(context, AiContext::empty).toMap());
		snapshot.setMessageJson(codec.serializeAll(history));
		snapshot.setProviderId(providerId);
		snapshot.setModelId(modelId);
		snapshot.freezeParams(frozen);
		snapshot.setCreatedBy(this.instanceId);
		snapshot.setCreatedAtMs(System.currentTimeMillis());
		return snapshot;
	}

	private void registerSessionRun(String runId, String sessionId, TenantUser identity) {
		if (StringUtils.isBlank(sessionId)) {
			return;
		}
		try {
			this.redis.execute(SESSION_RUNS_REGISTER_SCRIPT,
					List.of(sessionRunsKey(sessionId, identity.tenantId(), identity.userId())), runId,
					String.valueOf(ttlFor(runId).toMillis()));
		}
		catch (Exception ex) {
			throw new AiStateUnavailableException("Failed to register session->run index sessionId=" + sessionId
					+ " runId=" + runId, ex);
		}
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
	 * <h2>取消优先状态 CAS（W-30 + 批次2 / AR-05；性能专项起"状态键优先"）</h2>
	 * <b>所有非取消状态</b>（{@code RUNNING} / {@code SUSPENDED} / {@code DONE} / {@code FAILED}）的写
	 * 都走一次状态 CAS：Lua 在<b>同一个原子步</b>里先读轻量状态键 {@code ai:runstate:<runId>}
	 * （与快照<b>同 hash tag ⇒ 同槽</b>），读到 {@code CANCELLED} ⇒ <b>拒写并直接返回</b>（不覆盖取消终态，
	 * 也不执行后面的会话索引 / 租约副作用）；<b>状态键缺失</b>时才回退 {@code cjson} 解析<b>当前</b>快照
	 * （判 {@code status=CANCELLED} 或 {@code cancelled=true}，旧快照口径不变），两者都不可读则按
	 * "可用性优先"照常写。持租分支用 {@link #FENCED_TERMINAL_SET_SCRIPT}
	 * （原来的 fence 比较逐字保留，且在同一脚本内），无租约分支用
	 * {@link #TERMINAL_SET_IF_NOT_CANCELLED_SCRIPT}；两条脚本都在<b>同一步里把快照与状态键一起写入</b>
	 * （同 TTL），状态键因此不会与快照分叉，并随快照<b>同生同死</b>（{@link #deleteRun} 一并删）。
	 * fence 语义不变；{@code CANCELLED} 自己的写不受限（幂等重写取消态照常落库）。
	 *
	 * <p><b>为什么 RUNNING / SUSPENDED 也在守卫内</b>：续跑入口都是先检查取消、
	 * 再把快照写成 RUNNING —— 检查与写之间取消可能恰好落库。旧口径只守终态，这条
	 * CANCELLED → RUNNING 的翻案会把用户已停止的轮重新驱动起来（比 FAILED 翻案更危险：
	 * 前端会重挂到一条不该再跑的轮上）。<b>不变量：任何状态写入都不得从 CANCELLED 转出。</b>
	 *
	 * <p><b>为什么 FAILED 也要同一守卫</b>：失败出口三条（{@code fail()} /
	 * {@code emptyReply()} / {@code AiController} 的环外异常 catch）都写 FAILED，而 FAILED 与 DONE
	 * 一样是<b>不可逆终态</b>；"取消先到 ⇒ 失败不得翻案"与"成功不得翻案"是同一条口径。
	 * 三条出口共用 {@link #saveTerminalUnlessCancelled(RunSnapshot)}，本方法因此也享受同一判据
	 * （不因为调用方用 {@code save} 还是可判定变体而分叉）。
	 *
	 * <p><b>边界</b>：这是"快照状态不得从取消态进其它状态"的原子守卫，<b>不是</b>分布式事务 ——
	 * 记忆窗口的整窗写回不在这一个原子步内（见 {@code ResilientChatService#finishSuccess} 的落库后复读）。
	 */
	public void save(RunSnapshot snapshot) {
		isPersist(snapshot);
	}

	/**
	 * <b>仅当该轮快照尚不存在时创建</b>（恢复路径的条件写口；<b>绝不覆盖活快照</b>）。
	 *
	 * <h2>为什么需要它</h2>
	 * 恢复的形态是"读到不存在 ⇒ 用执行信封重建一份"。读与写之间若有并发创建者（另一实例刚恢复，
	 * 或这一轮刚被别处重建），无条件 {@link #save} 会把那份更新的运行事实覆盖掉 ——
	 * {@code messageJson} 被清空、{@code round}/{@code toolSteps} 回拨到信封时的旧值。
	 * 本方法把"存在性判定 + 写"放进<b>同一个原子步</b>（{@link #CREATE_IF_ABSENT_SCRIPT} /
	 * {@link #FENCED_CREATE_IF_ABSENT_SCRIPT}）：已存在 ⇒ 不写并返回 {@code false}，
	 * 由调用方改读并发那一份（不得用信封快照覆盖）。
	 *
	 * <h2>与 {@link #save} 同一套守卫</h2>
	 * 身份键由<b>记录身份</b>拼（{@link #openSnapshotWriteScope}：错身份直接拒写，0/0 记录只进 legacy
	 * 命名空间）；续跑权只读（LOST/UNAVAILABLE）、持租时 fence 陈旧、快照已存在 ⇒ 都不写。
	 * 创建成功后与 {@link #save} 走同一段联动（session→current、会话→run 索引原子补登记），
	 * 并<b>在本方法内</b> best-effort 补写全局 {@code ai:runs} 恢复索引（发现面的自愈入口）。
	 *
	 * @return {@code true} = 本次创建并落库；{@code false} = 已有快照（不覆盖）或无写权限
	 */
	public boolean isCreatedIfAbsent(RunSnapshot snapshot) {
		try (RunIdentityScope.Scope ignored = openSnapshotWriteScope(snapshot)) {
			return isCreateIfAbsentInScope(snapshot);
		}
	}

	public boolean createIfAbsent(RunSnapshot snapshot) {
		return isCreatedIfAbsent(snapshot);
	}

	private boolean isCreateIfAbsentInScope(RunSnapshot snapshot) {
		snapshot.setUpdatedAtMs(System.currentTimeMillis());
		String runId = snapshot.getRunId();
		LeaseService.LeaseAuthority authority = runLeaseAuthority(runId);
		if (authority.isReadOnly()) {
			warnReadOnly(runId, authority, "run snapshot (conditional create)");
			return false;
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
			throw new IllegalStateException("Failed to serialize run snapshot " + runId, ex);
		}
		Duration ttl = ttlFor(runId);
		String state = stateOf(snapshot);
		// 条件创建同样预留创建节点的节流位：afterSnapshotWrite 的 RUNNING 节流拍据此跳过，
		// 由下面的创建节点补写统一执行（避免同一笔写两次）。
		primeRunIndexRepair(snapshot, runId);
		Long created;
		try {
			created = epoch > 0L
					? this.redis.execute(FENCED_CREATE_IF_ABSENT_SCRIPT,
							List.of(runKey(runId), fenceKey(runId), runStateKey(runId)), json, String.valueOf(epoch),
							String.valueOf(ttl.toMillis()), state)
					: this.redis.execute(CREATE_IF_ABSENT_SCRIPT, List.of(runKey(runId), runStateKey(runId)), json,
							String.valueOf(ttl.toMillis()), state);
		}
		catch (Exception ex) {
			throw new AiStateUnavailableException("Failed to write run snapshot " + runId, ex);
		}
		if (created == null) {
			// 生产脚本恒回 0/1；null 只可能来自"脚本没被执行"（替身未建模）。条件语义此时不可判定 ⇒
			// 按未创建处理：调用方改读并发那一份，读不到就失败关闭（绝不退回无条件写）。
			log.warn("conditional snapshot create returned no script result runId={} "
					+ "(treating as not created; caller reads existing snapshot)", runId);
			return false;
		}
		if (created <= 0L) {
			// 快照已存在（并发创建者/已恢复过）：不是本方法的写，但恢复发现面可能缺这一条
			// （create 的 SADD 曾失败）。这里补一次 = 恢复路径的自愈入口（低频，不在热路径）。
			addRunIndexBestEffort(snapshot, runId);
			return false;
		}
		afterSnapshotWrite(snapshot, runId);
		// 恢复路径同样要出现在 restart 的发现面里：挂起/终态已由 afterSnapshotWrite 直接补过，
		// 这里只补它没覆盖的状态（RUNNING 的节流拍已被 prime 跳过，避免同一笔写两次 SADD）。
		if (!isRepairRunIndexStatus(snapshot)) {
			addRunIndexBestEffort(snapshot, runId);
		}
		return true;
	}

	/**
	 * 取消优先终态写的<b>可判定变体</b>（失败现场批次2）：与 {@link #save} <b>同一条写路径、同一个
	 * 原子判据</b>，只是把"这一笔到底有没有落库"如实返回给调用方。
	 *
	 * <p>谁需要它：{@code fail()} / {@code emptyReply()} / {@code AiController} 的环外异常出口 ——
	 * 被守卫拒写（当前快照已是取消态）时，它们<b>不得</b>再发自己的失败终帧（取消侧会发
	 * {@code done{cancelled:true}}），否则一轮会出现两条终帧。
	 *
	 * @return {@code true} = 已落库（或本就无需守卫生效）；{@code false} = 这一笔被拒写
	 *         （当前快照是取消态，或本实例已无续跑权；也不改会话索引 / 租约）
	 */
	public boolean saveTerminalUnlessCancelled(RunSnapshot snapshot) {
		return isStatusSavedUnlessCancelled(snapshot);
	}

	/**
	 * <b>任意状态写入</b>的取消优先原子守卫（可判定变体）—— 与 {@link #save} 同一条写路径，
	 * 但把"这一笔到底有没有落库"如实返回。
	 *
	 * <p>适用面 = <b>所有非取消状态</b>（RUNNING / SUSPENDED / DONE / FAILED）。它与
	 * {@link #saveTerminalUnlessCancelled} 是同一个原子判据，区别只在名字与调用意图：
	 * 终态出口用前者（语义是"终态不得翻案取消"），
	 * <b>重跑/续跑的状态转换用本方法</b>（语义是"不得从 CANCELLED 转出"）。
	 *
	 * <p>为什么重跑不能直接用 {@link #save}：入口的 {@code cancelStateOutcome} 检查与这笔记账之间
	 * 没有原子性，而 {@code save} 的返回值是 void —— 即使守卫拒写，调用方也无从知道，仍会继续
	 * 交棒线程池驱动这一轮。本方法把拒写变成可分支的事实：{@code false} ⇒ 取消已先到，
	 * 调用方必须返回取消语义、<b>不得</b>再驱动。
	 *
	 * @return {@code true} = 已落库；{@code false} = 这一笔被拒写（当前快照是取消态，<b>或</b>
	 *         本实例已无续跑权 / fence 已推进到更高 epoch）。调用方必须复核后再决定取消语义
	 *         还是续跑权语义，不能一律当成"已取消"。
	 */
	public boolean isStatusSavedUnlessCancelled(RunSnapshot snapshot) {
		return isPersist(snapshot);
	}

	/**
	 * 快照写入的<b>身份作用域</b>：键以<b>记录归属</b>为准（续跑/恢复/跨实例接管都发生在原请求之后，
	 * 身份唯一来源是这条记录），而不是"当前线程碰巧是谁"。
	 *
	 * <ul>
	 * <li>记录有身份 ⇒ 在记录身份下写入；当前上下文是另一个身份 ⇒ <b>直接拒绝</b>
	 *     （否则一笔记错身份的写会落到别人的同名 runId 上）；</li>
	 * <li>记录<b>没有</b>身份（0/0/不可用）⇒ <b>拒绝</b>：写侧不得 adopt 成任何身份，
	 *     也不存在无身份命名空间可写；</li>
	 * <li>当前上下文是另一个身份 ⇒ <b>拒绝</b>（见上）。</li>
	 * </ul>
	 */
	private RunIdentityScope.Scope openSnapshotWriteScope(RunSnapshot snapshot) {
		TenantUser owner = recordIdentityOf(snapshot);
		TenantUser current = RequestContextUtils.currentOrNull();
		if (owner == null) {
			throw new IllegalStateException("Refusing to write run snapshot without ownership (0/0) runId=" + snapshot.getRunId()
					+ " (currentIdentity=" + current + "): missing record identity fails closed; no anonymous namespace exists");
		}
		if (current != null && !owner.equals(current)) {
			throw new IllegalStateException("Refusing to write a run snapshot owned by another identity runId=" + snapshot.getRunId() + " (recordOwner="
					+ owner + ", currentIdentity=" + current + "): run keys include identity in their hash tag; writing with the wrong identity could overwrite another owner's run");
		}
		return RunIdentityScope.open(owner);
	}

	private boolean isPersist(RunSnapshot snapshot) {
		try (RunIdentityScope.Scope ignored = openSnapshotWriteScope(snapshot)) {
			return isPersistInScope(snapshot);
		}
	}

	private boolean isPersistInScope(RunSnapshot snapshot) {
		snapshot.setUpdatedAtMs(System.currentTimeMillis());
		String runId = snapshot.getRunId();
		// 续租 fencing 收口批：写权限由 authority 判定，不再用 epoch==0 当"可写"。
		// LOST（确定丢租）/ UNAVAILABLE（设施异常，无法确认）⇒ 只读：这一笔不落库。
		LeaseService.LeaseAuthority authority = runLeaseAuthority(runId);
		if (authority.isReadOnly()) {
			warnReadOnly(runId, authority, "run snapshot");
			return false;
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
			throw new IllegalStateException("Failed to serialize run snapshot " + runId, ex);
		}
		// 取消优先状态守卫的适用面：所有非取消状态（RUNNING / SUSPENDED / DONE / FAILED）。
		// CANCELLED 自己不受限（幂等重写取消态照常落库）；不变量 = 任何状态写入不得从 CANCELLED 转出。
		boolean guardedStatus = isGuardedStatus(snapshot.getStatus());
		String state = stateOf(snapshot);
		if (epoch > 0L && !persistFenced(runId, snapshot, guardedStatus, json, epoch, state)) {
			return false;
		}
		else {
			warnIfFenceIsAhead(runId);
			try {
				if (guardedStatus) {
					if (!isTerminalSetIfNotCancelled(runId, json, state)) {
						log.warn("{} refused snapshot write runId={} (snapshot already "
								+ "cancelled: not overwriting CANCELLED, no index/lease change)", snapshot.getStatus(), runId);
						return false;
					}
				}
				else {
					// CANCELLED 自己的写：先落轻量状态键、再落快照。
					// 顺序刻意是"状态键在前"：若快照写失败而状态键已写，残留的是 CANCELLED ——
					// 守卫会继续拒绝所有非取消写（安全方向）；反过来（快照 CANCELLED、状态键还是旧
					// 的非取消值）会让后续写把取消翻案。快照失败时回滚状态键，异常照旧上抛。
					writeCancelStateThenSnapshot(runId, json, state);
				}
			}
			catch (Exception ex) {
				throw new AiStateUnavailableException("Failed to write run snapshot " + runId, ex);
			}
		}
		// T2b-D#1：快照持久化成功后联动 session → current 轮索引（全部走本方法这一唯一通道，
		// 不会漏分支）。批G · G1：**RUNNING 也写** —— 纯文本轮从不 SUSPENDED，旧口径下
		// `/runs/current` 对该轮恒 `found:false`，刷新后前端没有任何"这一轮还在跑"的可发现面
		// （实证 A/A3/B/C2：整条回答静默消失）。终态照旧清除（取消后不留索引）。
		// T3-7（A14）：终态分支改调统一判定函数（不再散点比对字面量）。
		afterSnapshotWrite(snapshot, runId);
		return true;
	}

	private boolean persistFenced(String runId, RunSnapshot snapshot, boolean guardedStatus, String json, long epoch,
			String state) {
		boolean written = guardedStatus ? isFencedTerminalSet(runId, json, epoch, state)
				: isFencedSet(runId, json, epoch, state);
		if (written) {
			return true;
		}
		if (guardedStatus) {
			Long fence = fenceAheadOf(runId, epoch);
			if (fence != null) {
				log.warn("STALE_EPOCH refused snapshot write runId={} status={} epoch={} fence={} "
						+ "(fence advanced to a higher epoch: another instance took over this run)", runId, snapshot.getStatus(),
						epoch, fence);
			}
			else {
				log.warn("{} refused snapshot write runId={} epoch={} (stale fence or snapshot "
						+ "already cancelled: not overwriting CANCELLED, no index/lease change)", snapshot.getStatus(), runId, epoch);
			}
		}
		else {
			log.warn("STALE_EPOCH refused snapshot write runId={} epoch={} (fence "
					+ "advanced to a higher epoch: another instance took over this run)", runId, epoch);
		}
		return false;
	}

	/**
	 * 快照写成功后的<b>唯一联动段</b>（{@link #persistInScope} 与 {@link #createIfAbsentInScope} 共用）：
	 * session → current 轮索引、会话→run 索引原子登记/续期；终态清 current 索引并释放续跑租约。
	 *
	 * <p>刻意抽出来而不是两处各写一遍：这些联动是"快照落库后该发生什么"的单一事实源。
	 * 会话→run 索引<b>幂等补写</b>：首轮漏写、{@code createIfAbsent} 恢复出来的轮、后续任意一次 save
	 * 都能自愈（best-effort：快照已经落库，索引失败不能反过来让写快照失败）。
	 *
	 * <p><b>全局 {@code ai:runs} 的补写口径</b>（性能专项 + 自愈）：{@code SUSPENDED} 与终态属低频
	 * 状态写，这里直接补；{@code RUNNING} 不在每次 save 时写，而是走
	 * {@link #repairRunIndexThrottled} 的<b>每 run 60s 至多一次</b>节流自愈 —— 覆盖"创建节点补写
	 * 失败、轮次却一直 RUNNING 直到崩溃"的缺口，同时不退回热路径写。创建类节点
	 * （{@link #create} / {@link #createIfAbsent}）另有自己的补写入口。
	 */
	private void afterSnapshotWrite(RunSnapshot snapshot, String runId) {
		String status = snapshot.getStatus();
		if (RunSnapshot.SUSPENDED.equals(status) || RunSnapshot.RUNNING.equals(status)) {
			markSessionCurrent(snapshot.getSessionId(), runId);
		}
		else if (RunSnapshot.isTerminal(status)) {
			clearSessionCurrent(snapshot.getSessionId(), runId);
			releaseRunLease(runId);
			// 进程内节流状态必须随终态清理（否则表里留着已收尾的轮，等于慢性增长）。
			forgetRunIndexRepair(snapshot.getTenantId(), snapshot.getUserId(), runId);
		}
		String sessionId = snapshot.getSessionId();
		if (StringUtils.isNotBlank(sessionId)) {
			// 会话→run 索引是销毁的唯一发现面：这里用同一原子脚本补登记 + 续期，
			// 覆盖"首轮 SADD 漏写 / createIfAbsent 恢复 / 后续 save"三种自愈入口。
			registerSessionRunIndexBestEffort(sessionId, snapshot.getTenantId(), snapshot.getUserId(), runId,
					ttlFor(runId));
		}
		if (isRepairRunIndexStatus(snapshot)) {
			// 低频状态写（挂起 / 终态）顺手补恢复发现面：失败只 WARN，下一次低频写或创建节点会再补。
			addRunIndexBestEffort(snapshot, runId);
		}
		else {
			// RUNNING 的热路径：按 run 节流（默认 60s）补一次，覆盖"create 的 SADD 失败后一直 RUNNING
			// 直到崩溃"的自愈缺口，同时不退回"每次 save 都 SADD"。
			repairRunIndexThrottled(snapshot, runId);
		}
	}

	/**
	 * 为<b>创建节点</b>预留节流位（创建类入口在写快照之前调用）。
	 *
	 * <p>目的：创建节点自己做一次补写，同时让 {@link #afterSnapshotWrite} 的 RUNNING 节流拍在同一笔
	 * 写里跳过 —— 既不丢"创建补写"，也不会同一笔快照写触发两次 {@code SADD}。
	 * 创建补写失败后，第一次重试在 {@value #RUN_INDEX_REPAIR_INTERVAL_MS}ms 之后（有界节流）。
	 */
	private void primeRunIndexRepair(RunSnapshot snapshot, String runId) {
		if (snapshot == null || runId == null) {
			return;
		}
		long tenantId = snapshot.getTenantId();
		long userId = snapshot.getUserId();
		if (!TenantKeyScope.hasIdentity(tenantId, userId)) {
			return;
		}
		String member = RunKeyScope.indexMember(tenantId, userId, runId);
		if (this.runIndexRepairAtMs.size() >= RUN_INDEX_REPAIR_CAP && !this.runIndexRepairAtMs.containsKey(member)) {
			this.runIndexRepairAtMs.clear();
		}
		this.runIndexRepairAtMs.put(member, System.currentTimeMillis());
	}

	/**
	 * {@code ai:runs} 的<b>节流自愈</b>：同一 run 至多每 {@value #RUN_INDEX_REPAIR_INTERVAL_MS}ms
	 * 尝试补写一次（{@link #addRunIndexBestEffort} 自身已 best-effort，失败不抛）。
	 *
	 * <p>只对有记录身份的轮次记账（无归属 legacy 快照不进恢复发现面，也不该在表里留条目）。
	 * 表有界：终态/删除会移除条目，超过 {@value #RUN_INDEX_REPAIR_CAP} 条时整体清空。
	 */
	private void repairRunIndexThrottled(RunSnapshot snapshot, String runId) {
		if (snapshot == null || runId == null) {
			return;
		}
		long tenantId = snapshot.getTenantId();
		long userId = snapshot.getUserId();
		if (!TenantKeyScope.hasIdentity(tenantId, userId)) {
			return;
		}
		String member = RunKeyScope.indexMember(tenantId, userId, runId);
		long now = System.currentTimeMillis();
		Long last = this.runIndexRepairAtMs.get(member);
		if (last != null && now - last < RUN_INDEX_REPAIR_INTERVAL_MS) {
			return;
		}
		if (this.runIndexRepairAtMs.size() >= RUN_INDEX_REPAIR_CAP && !this.runIndexRepairAtMs.containsKey(member)) {
			// 有界：整体清空（清空只影响"下一次补写提前"，不影响正确性）。
			this.runIndexRepairAtMs.clear();
		}
		this.runIndexRepairAtMs.put(member, now);
		addRunIndexBestEffort(snapshot, runId);
	}

	/**
	 * 清理节流自愈的进程内状态（终态写 / 删除轮次必须调用）。
	 *
	 * <p>身份不可用（{@code 0/0} legacy）时无事可做：这类轮次从未进入节流表。
	 */
	private void forgetRunIndexRepair(long tenantId, long userId, String runId) {
		if (runId == null || !TenantKeyScope.hasIdentity(tenantId, userId)) {
			return;
		}
		this.runIndexRepairAtMs.remove(RunKeyScope.indexMember(tenantId, userId, runId));
	}

	/**
	 * 该状态写是否属于"低频、可顺手补 {@code ai:runs}"的节点。
	 *
	 * <p>挂起态（{@code SUSPENDED}）与终态都是一轮里至多几次的写；{@code RUNNING} 是每帧/每拍都
	 * 可能发生的热路径，必须排除，否则等于退回"每次 save 都 SADD"。
	 */
	private static boolean isRepairRunIndexStatus(RunSnapshot snapshot) {
		String status = snapshot.getStatus();
		return RunSnapshot.SUSPENDED.equals(status) || RunSnapshot.isTerminal(status);
	}

	/**
	 * {@code CANCELLED} 的普通写（无租约、无守卫分支）：<b>先写轻量状态键、再写快照</b>。
	 *
	 * <p>为什么是"状态键在前"：守卫只信任状态键里的 {@code CANCELLED}（非取消值会继续解析快照）。
	 * 若反序（快照先落地、状态键写失败），残留的旧状态串会让后续非取消写通过守卫 ——
	 * 正是"取消被翻案"；而状态键先落、快照失败时残留的是 {@code CANCELLED}（守卫继续拒绝所有
	 * 非取消写，安全方向），本方法随后按<b>值匹配</b>回滚状态键（不误删并发写者刚落下的 DONE）
	 * 并把异常上抛。
	 */
	private void writeCancelStateThenSnapshot(String runId, String json, String state) {
		Duration ttl = ttlFor(runId);
		this.redis.opsForValue().set(runStateKey(runId), state, ttl);
		try {
			this.redis.opsForValue().set(runKey(runId), json, ttl);
		}
		catch (RuntimeException ex) {
			// 值匹配回滚：只删"本次写的 CANCELLED"，不误删并发写者刚落下的 DONE/其它状态。
			rollbackCancelState(runId, state);
			throw ex;
		}
	}

	/** 回滚取消写留下状态键（值匹配；脚本不可用时宁可不删 —— 残留 CANCELLED 是安全方向）。 */
	private void rollbackCancelState(String runId, String expectedState) {
		try {
			this.redis.execute(RUN_STATE_CAS_DELETE_SCRIPT, List.of(runStateKey(runId)), expectedState);
		}
		catch (RuntimeException rollbackEx) {
			log.warn("lightweight state key rollback failed runId={} (leftover "
					+ "CANCELLED only rejects non-cancel writes more strictly)", runId, rollbackEx);
		}
	}

	/**
	 * 轻量状态键的 best-effort 写（只服务"快照已单独落库"的补偿路径）。
	 *
	 * <p>状态键是判活/守卫的加速面：缺失时读侧回退解析快照（见 {@link #runStatus(String)} 与
	 * Lua 守卫），所以这里失败只记 WARN —— 不能把一次已落库的终态伪报成写失败。
	 */
	private void writeRunStateBestEffort(String runId, String state) {
		try {
			this.redis.opsForValue().set(runStateKey(runId), state, ttlFor(runId));
		}
		catch (RuntimeException ex) {
			log.warn("lightweight state key write failed runId={} state={} (liveness/guards fall back to snapshot)", runId, state, ex);
		}
	}

	/** 会话→run 索引的原子登记/续期（best-effort；快照已落库，失败留给后续 save 自愈）。 */
	private void registerSessionRunIndexBestEffort(String sessionId, long tenantId, long userId, String runId,
			Duration ttl) {
		try {
			this.redis.execute(SESSION_RUNS_REGISTER_SCRIPT, List.of(sessionRunsKey(sessionId, tenantId, userId)),
					runId, String.valueOf(ttl.toMillis()));
		}
		catch (Exception ex) {
			log.warn("session->run index backfill failed sessionId={} runId={} (snapshot stored; later writes self-heal)",
					sessionId, runId, ex);
		}
	}

	/** 快照写后的索引物化：列表走身份窄索引，恢复只保留非终态的 due 候选。 */
	private void addRunIndexBestEffort(RunSnapshot snapshot, String runId) {
		long tenantId = snapshot.getTenantId();
		long userId = snapshot.getUserId();
		if (!TenantKeyScope.hasIdentity(tenantId, userId)) {
			return;
		}
		String member = RunKeyScope.indexMember(tenantId, userId, runId);
		try {
			String listKey = runListIndexKey(tenantId, userId);
			String identityDueKey = identityRecoveryDueKey(tenantId, userId);
			Duration ttl = ttlFor(runId);
			this.redis.opsForZSet().add(listKey, runId, Math.max(0L, snapshot.getUpdatedAtMs()));
			this.redis.expire(listKey, ttl);
			this.redis.expire(identityDueKey, ttl);
			if (RunSnapshot.isTerminal(snapshot.getStatus())) {
				this.redis.opsForZSet().remove(RECOVERY_DUE_INDEX, member);
				this.redis.opsForZSet().remove(identityDueKey, runId);
			}
			else {
				long dueAt = recoveryDueAt(snapshot.getUpdatedAtMs());
				this.redis.opsForZSet().add(RECOVERY_DUE_INDEX, member, dueAt);
				this.redis.opsForZSet().add(identityDueKey, runId, dueAt);
			}
		}
		catch (Exception ex) {
			log.warn("run recovery/list index backfill failed runId={} (later writes self-heal)", runId, ex);
		}
	}

	private long recoveryDueAt(long activeAtMs) {
		return Math.max(0L, activeAtMs) + this.properties.getResume().getZombieAge().toMillis();
	}

	private static String runListIndexKey(long tenantId, long userId) {
		return RUN_LIST_INDEX_PREFIX + tenantId + ":" + userId;
	}

	private static String identityRecoveryDueKey(long tenantId, long userId) {
		return RECOVERY_DUE_IDENTITY_PREFIX + tenantId + ":" + userId;
	}

	/** 只有成员仍以本次读取的 score 存在才推迟；identity-due 在同一 Lua 内续期。 */
	public boolean isRecoveryCandidateDeferred(String runId, long tenantId, long userId, long expectedDueAtMs,
			long nextDueAtMs) {
		try {
			String member = RunKeyScope.indexMember(tenantId, userId, runId);
			Long deferred = this.redis.execute(RECOVERY_DEFER_SCRIPT, List.of(RECOVERY_DUE_INDEX),
					String.valueOf(expectedDueAtMs), String.valueOf(Math.max(0L, nextDueAtMs)), member, "0");
			if (deferred == null || deferred <= 0L) {
				return false;
			}
			Long identityDeferred = this.redis.execute(RECOVERY_DEFER_SCRIPT,
					List.of(identityRecoveryDueKey(tenantId, userId)), String.valueOf(expectedDueAtMs),
					String.valueOf(Math.max(0L, nextDueAtMs)), runId, String.valueOf(ttlFor(runId).toMillis()));
			return identityDeferred != null && identityDeferred > 0L;
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("recovery-candidate-defer")) {
				log.warn("recovery candidate defer failed runId={}", runId, ex);
			}
			return false;
		}
	}

	/** score 已变化表示有更新写者，旧读者不得删除或复活该成员。 */
	public boolean isRecoveryCandidateRemoved(String runId, long tenantId, long userId, long expectedDueAtMs) {
		try {
			Long removed = this.redis.execute(RECOVERY_REMOVE_SCRIPT, List.of(RECOVERY_DUE_INDEX),
					String.valueOf(expectedDueAtMs), RunKeyScope.indexMember(tenantId, userId, runId));
			if (removed == null || removed <= 0L) {
				return false;
			}
			this.redis.execute(RECOVERY_REMOVE_SCRIPT, List.of(identityRecoveryDueKey(tenantId, userId)),
					String.valueOf(expectedDueAtMs), runId);
			return true;
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("stale-recovery-candidate-cleanup")) {
				log.warn("stale recovery candidate cleanup failed runId={}", runId, ex);
			}
			return false;
		}
	}


	/**
	 * 该状态是否走取消优先守卫：<b>一切非取消状态</b>（RUNNING / SUSPENDED / DONE / FAILED，
	 * 含 {@code REJECTED} 归一）。
	 *
	 * <p>{@code CANCELLED} 自己的写不受守卫限制（幂等重写取消态照常落库）；其它状态在
	 * "当前快照已是取消态"时一律拒写 —— 这就是"任何状态写入均不得从 CANCELLED 转出"的判据。
	 */
	private static boolean isGuardedStatus(String status) {
		return !RunSnapshot.CANCELLED.equals(status);
	}

	/**
	 * 拒写后的 fence 复核（只服务诊断）：fence 已推进到 {@code epoch} 之上时返回该值。
	 *
	 * <p>为什么只能在冷路径上复核：{@link #FENCED_TERMINAL_SET_SCRIPT} 把"fence 陈旧"与"当前快照已是
	 * 取消态"合成同一个返回码，拒写原因因此不可分辨。这里用一次单键 {@code GET} 事后区分 ——
	 * fence 大于本次 epoch 时，脚本的第一处判据必然先命中，拒写原因就是陈旧 epoch。
	 *
	 * <p><b>读不出来 ⇒ {@code null}</b>（回退原有的合并日志）：复核只是可观测性增强，不得引入新异常、
	 * 也不得改变"这一笔被拒"的结论。刻意不走 {@link #fenceEpoch}（它在读失败时 fail-closed 抛异常并记
	 * 认领侧的 WARN，语义与这里的纯诊断用途不同）。
	 */
	private Long fenceAheadOf(String runId, long epoch) {
		try {
			String raw = this.redis.opsForValue().get(fenceKey(runId));
			if (raw == null) {
				return null;
			}
			long fence = Long.parseLong(raw);
			return fence > epoch ? Long.valueOf(fence) : null;
		}
		catch (Exception ex) {
			return null;
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
				log.warn("lease-less write runId={}: fence advanced to epoch={} (write allowed without lease; no "
						+ "resume-authority semantics; check lease view if this is a stale write after owner change)", runId, fence);
			}
			else {
				if (log.isDebugEnabled()) log.debug("lease-less write runId={}: fence still epoch={} (already warned for this run; demoted to debug)", runId, fence);
			}
		}
		catch (Exception ex) {
			// 旁路观测失败不影响写回（E3 的可用性优先）
			if (LogSampling.canAcquire("fence-read")) {
				log.warn("fence read failed runId={}", runId, ex);
			}
		}
	}

	/** fence 写：{@code fence ≤ epoch} 才写（并把 fence 推到 epoch）；返回 false = 陈旧写被拒。 */
	private boolean isFencedSet(String runId, String json, long epoch, String state) {
		Duration ttl = ttlFor(runId);
		try {
			Long result = this.redis.execute(FENCED_SET_SCRIPT,
					List.of(runKey(runId), fenceKey(runId), runStateKey(runId)), json, String.valueOf(epoch),
					String.valueOf(ttl.toMillis()), state);
			return result != null && result > 0L;
		}
		catch (Exception ex) {
			throw new AiStateUnavailableException("Failed to write run snapshot " + runId, ex);
		}
	}

	/**
	 * 取消优先终态的持租写：fence 校验与 {@link #fencedSet} 逐字相同，外加"当前快照已是取消态 ⇒ 拒写"
	 * （状态键优先、缺失才 {@code cjson} 解析快照，见 {@link #FENCED_TERMINAL_SET_SCRIPT}）。
	 *
	 * @return false = 陈旧 epoch <b>或</b> 当前快照是取消态（两者都不落库、不改索引）
	 */
	private boolean isFencedTerminalSet(String runId, String json, long epoch, String state) {
		Duration ttl = ttlFor(runId);
		try {
			Long result = this.redis.execute(FENCED_TERMINAL_SET_SCRIPT,
					List.of(runKey(runId), fenceKey(runId), runStateKey(runId)), json, String.valueOf(epoch),
					String.valueOf(ttl.toMillis()), state);
			return result != null && result > 0L;
		}
		catch (Exception ex) {
			throw new AiStateUnavailableException("Failed to write run snapshot " + runId, ex);
		}
	}

	/**
	 * 取消优先终态的无租约写（E3 plain 分支）：同一原子步内"当前快照已是取消态 ⇒ 拒写"，否则写快照
	 * 并把轻量状态键一并刷新（同 TTL）。不持租的路径原先没有任何原状态检查（也是 W-30 现场 wipe 掉
	 * CANCELLED 的那一条）；守卫先读状态键、缺失才解析快照，因此旧数据（没有状态键）仍按原判据收敛。
	 *
	 * <p>批次2 起也服务 FAILED（失败出口三条共用 {@link #saveTerminalUnlessCancelled}）。
	 *
	 * @return false = 当前快照是取消态（不落库、不改索引）
	 */
	private boolean isTerminalSetIfNotCancelled(String runId, String json, String state) {
		Duration ttl = ttlFor(runId);
		Long result = this.redis.execute(TERMINAL_SET_IF_NOT_CANCELLED_SCRIPT,
				List.of(runKey(runId), runStateKey(runId)), json, String.valueOf(ttl.toMillis()), state);
		if (result == null) {
			// 生产脚本恒回 0/1；null 只可能来自"脚本没被执行"（替身未建模 / 包装层无返回值）。
			// 不把"没判"折成"取消"（那会让一次成功轮的终态写凭空消失），按旧口径普通写。
			this.redis.opsForValue().set(runKey(runId), json, ttl);
			writeRunStateBestEffort(runId, state);
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
			markActiveRun(sessionId, runId, System.currentTimeMillis());
		}
		catch (Exception ex) {
			log.warn("session->current index write failed sessionId={} runId={}", sessionId, runId, ex);
		}
	}

	/** 索引键名（诊断/取证可直读）。会话级键带身份段（{@link TenantKeyScope}），单键命令无 tag。 */
	public String sessionCurrentKey(String sessionId) {
		return TenantKeyScope.scoped(SESSION_CURRENT_PREFIX, sessionId);
	}

	/** 会话→活跃轮有序索引键名（{@code ai:session:active:<tenantId>:<userId>:<sessionId>}，ZSET）。 */
	public String sessionActiveKey(String sessionId) {
		return TenantKeyScope.scoped(SESSION_ACTIVE_PREFIX, sessionId);
	}

	/**
	 * 记一次活跃（N1）：{@code ZADD ai:session:active:<sessionId> <nowMs> <runId>} + 续期。
	 *
	 * <p><b>单键 Lua 原子</b>（性能/可靠性专项）：{@code ZADD} 与 {@code PEXPIRE} 拆成两条命令时，
	 * 进程死在两步之间会留下<b>无 TTL 的活跃索引</b>（永久候选 + 永不消失的键）。脚本只碰这一个
	 * 会话级键（不带 hash tag ⇒ 同槽），Cluster 下不引入 {@code CROSSSLOT}。
	 *
	 * <p>索引只是"终态后回指"的候选排序面：写失败/设施不支持时只降级为旧口径（SMEMBERS + 快照），
	 * 不影响任何正确性。
	 */
	private void markActiveRun(String sessionId, String runId, long atMs) {
		if (StringUtils.isBlank(sessionId) || runId == null) {
			return;
		}
		String key = sessionActiveKey(sessionId);
		try {
			Long applied = this.redis.execute(SESSION_ACTIVE_TOUCH_SCRIPT, List.of(key), runId,
					String.valueOf(atMs), String.valueOf(sessionCurrentTtl().toMillis()));
			if (applied == null) {
				// 脚本没被执行（替身/包装层未建模）：退回两步式，语义仍以"成员 + TTL"为准。
				ZSetOperations<String, String> active = this.redis.opsForZSet();
				if (active == null) {
					return;
				}
				active.add(key, runId, atMs);
				this.redis.expire(key, sessionCurrentTtl());
			}
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("active-run-index-maintenance")) {
				log.warn("active run index maintenance failed sessionId={} runId={}", sessionId, runId, ex);
			}
		}
	}

	/** 从活跃索引摘掉一轮（幂等；终态 / 回收都调，失败只降级）。 */
	private void forgetActiveRun(String sessionId, String runId) {
		if (StringUtils.isBlank(sessionId) || runId == null) {
			return;
		}
		try {
			ZSetOperations<String, String> active = this.redis.opsForZSet();
			if (active != null) {
				active.remove(sessionActiveKey(sessionId), runId);
			}
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("active-run-index-cleanup")) {
				log.warn("active run index cleanup failed sessionId={} runId={}", sessionId, runId, ex);
		}
	}

	/**
	 * 活跃索引的倒序候选（纯 runId，不反序列化快照）。
	 *
	 * @return {@code null} = 索引不可用或为空（老会话 / 设施不支持 ⇒ 调用方回退旧口径）
	 */
	private Set<ZSetOperations.TypedTuple<String>> activeRunOrder(String sessionId) {
		try {
			ZSetOperations<String, String> active = this.redis.opsForZSet();
			if (active == null) {
				return null;
			}
			Set<ZSetOperations.TypedTuple<String>> ordered = active.reverseRangeWithScores(sessionActiveKey(sessionId),
					0, -1);
			return CollectionUtils.isEmpty(ordered) ? null : ordered;
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("active-run-index-read")) {
				log.warn("active run index read failed sessionId={} (falling back to legacy semantics)", sessionId, ex);
			}
			return null;
		}
	}

	/**
	 * 本会话所有非终态 runId（记忆窗口/压缩的裁剪 pin）。
	 *
	 * <h2>轻量读取 + 批量（性能/可靠性专项）</h2>
	 * 旧口径对每个候选 {@code get(runId)}：整份快照（含整窗 {@code messageJson}）反序列化成对象图，
	 * 只为回答"是不是终态"。现改为：
	 * <ol>
	 * <li><b>一次 Cluster 安全 pipeline</b> 读候选的轻量状态键 {@code ai:runstate:<runId>}
	 *     （不同 runId 落在不同槽也没关系：Lettuce 集群管道按槽分派，脚本/命令都不跨键，
	 *     <b>不引入 {@code CROSSSLOT}</b>）；</li>
	 * <li>状态键缺失（本键引入前的旧快照 / 被淘汰）的候选，<b>再一段 pipeline</b> 取快照原文，
	 *     只流式读顶层 {@code status}，<b>不</b>物化消息；</li>
	 * <li>终态 / 快照已不存在的候选从活跃 ZSET 摘掉（单键 {@code ZREM}，幂等）；</li>
	 * <li>读不出来（批量面不可用 / 解析失败）一律<b>保守按活跃</b>：这个方法的错误方向是
	 *     "多 pin 几条"，不是"把活轮裁掉"。</li>
	 * </ol>
	 */
	public Set<String> activeRunIds(String sessionId) {
		if (StringUtils.isBlank(sessionId)) {
			return Set.of();
		}
		Set<String> candidates = new java.util.LinkedHashSet<>();
		Set<ZSetOperations.TypedTuple<String>> ordered = activeRunOrder(sessionId);
		boolean fromActiveIndex = ordered != null;
		if (ordered != null) {
			for (ZSetOperations.TypedTuple<String> tuple : ordered) {
				if (tuple.getValue() != null) {
					candidates.add(tuple.getValue());
				}
			}
		}
		else {
			// 活跃轮候选只是裁剪 pin 的加速面：这里读失败不影响正确性，保持"空候选"的旧降级口径
			// （会话销毁面用同一方法时是 fail-closed，见 sessionRunIds）。
			try {
				candidates.addAll(sessionRunIds(sessionId));
			}
			catch (RuntimeException ex) {
				if (LogSampling.canAcquire("session-run-index-read")) {
					log.warn("session->run index read failed sessionId={} (treating active candidates as empty)", sessionId, ex);
				}
			}
		}
		if (candidates.isEmpty()) {
			return Set.of();
		}
		List<String> ids = new ArrayList<>(candidates);
		Map<String, RunLite> statuses = readRunLites(ids);
		if (statuses == null) {
			// 批量面不可用 ⇒ 全部走逐条降级（readRunLite），语义与旧口径一致（读不出来按活跃）。
			statuses = Map.of();
		}
		Set<String> active = new java.util.LinkedHashSet<>();
		List<String> stale = new ArrayList<>();
		for (String runId : ids) {
			RunLite lite = statuses.get(runId);
			if (lite == null) {
				lite = readRunLite(runId);
			}
			if (lite == RunLite.TERMINAL || lite == RunLite.GONE) {
				stale.add(runId);
				continue;
			}
			// ACTIVE / UNKNOWN：判不出来就按活跃（保守方向 = 多留一份历史，不少 pin 活轮）
			active.add(runId);
		}
		if (fromActiveIndex && !stale.isEmpty()) {
			forgetActiveRuns(sessionId, stale);
		}
		return active;
	}

	/** 批量摘除活跃索引成员（单键 {@code ZREM}；stale 清理失败只降级，不影响返回的活跃集合）。 */
	private void forgetActiveRuns(String sessionId, List<String> runIds) {
		if (sessionId == null || CollectionUtils.isEmpty(runIds)) {
			return;
		}
		try {
			ZSetOperations<String, String> active = this.redis.opsForZSet();
			if (active != null) {
				active.remove(sessionActiveKey(sessionId), runIds.toArray());
			}
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("stale-active-run-cleanup")) {
				log.warn("stale active run member cleanup failed sessionId={} (next tick)", sessionId, ex);
			}
		}
	}

	/** 判活用的轻量状态结论（见 {@link #readRunLites(List)}）。 */
	private enum RunLite {

		/** 非终态。 */
		ACTIVE,
		/** 终态（DONE / FAILED / CANCELLED，含 {@code REJECTED} 归一）。 */
		TERMINAL,
		/** 状态键与快照键都不在（索引里的死成员）。 */
		GONE,
		/** 读不出来/解析不了：调用方必须按活跃处理。 */
		UNKNOWN

	}

	/**
	 * <b>批量</b>读轻量状态：两段 Cluster 安全 pipeline（状态键 → 缺失者的快照原文）。
	 *
	 * @return {@code null} = 批量面不可用（替身/包装层未建模 pipeline），调用方回退逐条读
	 */
	private Map<String, RunLite> readRunLites(List<String> runIds) {
		if (CollectionUtils.isEmpty(runIds)) {
			return Map.of();
		}
		List<String> stateKeys = new ArrayList<>(runIds.size());
		for (String runId : runIds) {
			stateKeys.add(runStateKey(runId));
		}
		List<Object> states = pipelineGet(stateKeys);
		if (states == null) {
			return null;
		}
		Map<String, RunLite> out = new java.util.LinkedHashMap<>();
		List<Integer> missing = new ArrayList<>();
		for (int index = 0; index < runIds.size(); index++) {
			String state = index < states.size() ? asString(states.get(index)) : null;
			if (StringUtils.isBlank(state)) {
				missing.add(index);
			}
			else {
				out.put(runIds.get(index), liteOf(state));
			}
		}
		if (missing.isEmpty()) {
			return out;
		}
		List<String> snapshotKeys = new ArrayList<>(missing.size());
		for (int index : missing) {
			snapshotKeys.add(runKey(runIds.get(index)));
		}
		List<Object> snapshots = pipelineGet(snapshotKeys);
		if (snapshots == null) {
			for (int index : missing) {
				out.put(runIds.get(index), RunLite.UNKNOWN);
			}
			return out;
		}
		for (int offset = 0; offset < missing.size(); offset++) {
			String runId = runIds.get(missing.get(offset));
			String raw = offset < snapshots.size() ? asString(snapshots.get(offset)) : null;
			if (raw == null) {
				// 状态键与快照键都不在：索引里的死成员（可安全摘除）
				out.put(runId, RunLite.GONE);
				continue;
			}
			String status = topLevelStatus(raw);
			out.put(runId, status == null ? RunLite.UNKNOWN : liteOf(status));
		}
		return out;
	}

	/** 单条轻量状态（批量面不可用时的降级路径；任何异常都折成 {@link RunLite#UNKNOWN}）。 */
	private RunLite readRunLite(String runId) {
		try {
			String state = this.redis.opsForValue().get(runStateKey(runId));
			if (StringUtils.isNotBlank(state)) {
				return liteOf(state);
			}
			String raw = this.redis.opsForValue().get(runKey(runId));
			if (raw == null) {
				return RunLite.GONE;
			}
			String status = topLevelStatus(raw);
			return status == null ? RunLite.UNKNOWN : liteOf(status);
		}
		catch (RuntimeException ex) {
			return RunLite.UNKNOWN;
		}
	}

	/**
	 * 一次 pipeline GET（字符串值）。不同键可以在不同槽：管道按槽分派、命令本身单键，
	 * 因此 Cluster 安全（不引入 {@code CROSSSLOT}）。
	 *
	 * @return 与 {@code keys} 同序的结果；{@code null} = 管道未被执行（替身/包装层）
	 */
	private List<Object> pipelineGet(List<String> keys) {
		if (keys.isEmpty()) {
			return List.of();
		}
		try {
			List<Object> raw = this.redis.executePipelined((RedisCallback<Object>) connection -> {
				for (String key : keys) {
					connection.get(key.getBytes(StandardCharsets.UTF_8));
				}
				return null;
			});
			return raw == null || raw.size() < keys.size() ? null : raw;
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("batch-run-status-read")) {
				log.warn("batch run status read failed (falling back to per-run reads)", ex);
			}
			return null;
		}
	}

	private static RunLite liteOf(String status) {
		return RunSnapshot.isTerminal(status) ? RunLite.TERMINAL : RunLite.ACTIVE;
	}

	/** pipeline 结果的字符串化（{@code String} / {@code byte[]} 都接受；其它类型按其字符串形式）。 */
	private static String asString(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof String text) {
			return text;
		}
		if (raw instanceof byte[] bytes) {
			return org.apache.commons.codec.binary.StringUtils.newStringUtf8(bytes);
		}
		return String.valueOf(raw);
	}

	/** 会话→全部 runId 的轻量索引键名（{@code ai:session:runs:<tenantId>:<userId>:<sessionId>}）。 */
	public String sessionRunsKey(String sessionId) {
		return TenantKeyScope.scoped(SESSION_RUNS_PREFIX, sessionId);
	}

	/**
	 * 按<b>记录身份</b>拼会话→run 索引键（快照写入/补写用；不依赖当前线程上下文）。
	 *
	 * <p>无身份上下文 = 失败（不生成无身份会话索引）。
	 */
	private String sessionRunsKey(String sessionId, long tenantId, long userId) {
		if (!TenantKeyScope.hasIdentity(tenantId, userId)) {
			throw new IllegalArgumentException("session->run index requires explicit tenant/user; anonymous keys are forbidden");
		}
		return TenantKeyScope.scoped(SESSION_RUNS_PREFIX, tenantId, userId, sessionId);
	}

	/**
	 * 本会话候选 runId（只读，不触发全量快照反序列化）。
	 *
	 * <p><b>fail-closed</b>：读失败抛 {@link AiStateUnavailableException}，调用方（会话销毁）
	 * 必须整次中止 —— 本索引是唯一发现面，把"读不出来"当成"没有轮次"会静默漏删。
	 * 空集只表示确实没有成员（新会话 / 索引已过期）。
	 */
	public Set<String> sessionRunIds(String sessionId) {
		if (StringUtils.isBlank(sessionId)) {
			return Set.of();
		}
		try {
			Set<String> members = this.redis.opsForSet().members(sessionRunsKey(sessionId));
			return CollectionUtils.isEmpty(members) ? Set.of() : Set.copyOf(members);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.C1_RUN_INDEX_PRUNE, ex);
			throw new AiStateUnavailableException("Failed to read session->run index sessionId=" + sessionId, ex);
		}
	}

	/**
	 * 续期：仅当值仍是自己这一轮的 runId 才续期（同会话锁 watchdog 语义）。
	 *
	 * <p><b>MI2</b>：比较与 {@code PEXPIRE} 落在同一条单键 Lua（{@link #SESSION_CURRENT_CAS_PEXPIRE_SCRIPT}）
	 * —— 先前的 {@code GET → 比较 → EXPIRE} 在两者之间被同会话新轮换掉 current 时，会把<b>新轮</b>的
	 * TTL 续掉。前面的 {@code GET} 只作快路径（值明显不是自己就不必发脚本），正确性由脚本内的
	 * 比较兜底；TTL 仍是 {@code app.ai.session-ttl}，异常降级口径不变（debug 后返回）。
	 */
	public void renewSessionCurrent(String sessionId, String runId) {
		if (sessionId == null || runId == null) {
			return;
		}
		try {
			String key = sessionCurrentKey(sessionId);
			String value = this.redis.opsForValue().get(key);
			if (!runId.equals(value)) {
				return;
			}
			this.redis.execute(SESSION_CURRENT_CAS_PEXPIRE_SCRIPT, List.of(key), runId,
					String.valueOf(sessionCurrentTtl().toMillis()));
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("session-current-index-renew")) {
				log.warn("session->current index renew failed sessionId={} runId={}", sessionId, runId, ex);
			}
		}
	}

	/**
	 * 续期"会话 → 全部 runId"索引（{@code ai:session:runs:<...>:<sessionId>}）。
	 *
	 * <p>为什么需要它：这个索引是会话销毁的<b>唯一发现面</b>，它的 TTL 由写入路径和明确用户读侧的
	 * 兼容续期路径推进。ConfirmGate 后台心跳不调用本方法，避免把进程存活冒充成用户活动。
	 *
	 * <p><b>单键 {@code EXPIRE}</b>（键不带 hash tag ⇒ 同槽，Cluster 安全；不引入 {@code CROSSSLOT}）。
	 * 身份取<b>当前线程上下文</b>；恢复型入口没有请求身份时用四参重载把<b>记录身份</b>显式带进来。
	 * 续期失败只 debug：它不是正确性依赖，下一拍/下一次写入会再续。
	 *
	 * @param sessionId 会话 id
	 * @param runId 该会话下的一轮（只用于日志/取证；索引按会话整键续期）
	 */
	public void renewSessionRunsIndex(String sessionId, String runId) {
		TenantUser identity = RequestContextUtils.currentOrNull();
		renewSessionRunsIndex(sessionId, runId, identity == null ? 0L : identity.tenantId(),
				identity == null ? 0L : identity.userId());
	}

	/**
	 * 按<b>记录身份</b>续期会话→run 索引（恢复/续租线程等无请求上下文的路径用）。
	 *
	 * @param tenantId 记录身份租户（{@code 0} = 无身份 legacy 命名空间）
	 * @param userId 记录身份用户
	 */
	public void renewSessionRunsIndex(String sessionId, String runId, long tenantId, long userId) {
		if (StringUtils.isBlank(sessionId) || this.redis == null) {
			return;
		}
		try {
			this.redis.expire(sessionRunsKey(sessionId, tenantId, userId), sessionCurrentTtl());
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("session-run-index-renew")) {
				log.warn("session->run index renew failed sessionId={} runId={}", sessionId, runId, ex);
			}
		}
	}

	/**
	 * 当前轮终态后回指同会话仍活跃的最新一轮；没有才删除，且只处理仍指向自己的索引。
	 *
	 * <p><b>N1</b>：先查活跃有序索引（按最近一次活跃倒序），确认队首那一份快照仍活跃即可回指；
	 * 索引不可用/为空（老会话）才一次性回退旧的 {@code SMEMBERS + 逐个快照} 口径，并把命中的
	 * 活跃轮回填进新索引（"旧会话可一次性回退"）。语义不变：仍是"同会话仍活跃的最新 run"
	 * （{@code updatedAtMs} 最大；同毫秒平手时 SUSPENDED 优先）。
	 *
	 * <p><b>MI1</b>：fast-path 与老会话回退的<b>最终写</b>统一走单键 Lua compare-and-write ——
	 * 只有 current 仍等于本 runId 才删除（无 fallback）或 SET fallback；若已被同会话新轮换成别的
	 * runId 则不写（前面那次 {@code GET} 只用于跳过昂贵的回退计算，不作为写入判据）。
	 * 脚本只碰 current 单键（活跃 ZSET 不进同一 Lua：跨槽），SET 的 TTL 仍是
	 * {@code app.ai.session-ttl}；CAS 失败时也不回填活跃索引。
	 */
	public void clearSessionCurrent(String sessionId, String runId) {
		if (sessionId == null || runId == null) {
			return;
		}
		try {
			String key = sessionCurrentKey(sessionId);
			// 终态轮先摘出活跃索引（幂等）：即使索引当前指向别人，也不该把已终态的轮留在候选里。
			forgetActiveRun(sessionId, runId);
			String value = this.redis.opsForValue().get(key);
			if (!runId.equals(value)) {
				return;
			}
			Set<ZSetOperations.TypedTuple<String>> ordered = activeRunOrder(sessionId);
			if (ordered != null) {
				RunSnapshot fallback = newestActive(sessionId, ordered, runId);
				if (fallback == null) {
					isDeleteSessionCurrentIfOwned(key, runId);
				}
				else {
					isSetSessionCurrentIfOwned(key, runId, fallback.getRunId());
				}
				return;
			}
			RunSnapshot fallback = fallbackActiveRun(sessionId, runId);
			if (fallback == null) {
				isDeleteSessionCurrentIfOwned(key, runId);
			}
			else if (isSetSessionCurrentIfOwned(key, runId, fallback.getRunId())) {
				// 回填：下一次终态就能走单次确认的快路径（不再 SMEMBERS + N 份快照）。
				// <b>只在回填真的写成功后才做</b>（MI1）：CAS 失败说明 current 已归新轮，
				// 这时再把 fallback 塞进活跃索引就等于替一条已经不是 current 的轮记活跃。
				markActiveRun(sessionId, fallback.getRunId(), fallback.getUpdatedAtMs());
			}
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("session-current-index-clear")) {
				log.warn("session->current index clear failed sessionId={} runId={}", sessionId, runId, ex);
			}
		}
	}

	private RunSnapshot fallbackActiveRun(String sessionId, String runId) {
		// ===== 老会话一次性回退：SMEMBERS + 逐个快照（与升级前逐字同形）=====
		Set<String> index;
		try {
			index = sessionRunIds(sessionId);
		}
		catch (RuntimeException ex) {
			log.warn("post-terminal session->current recheck failed sessionId={} runId={} (keeping index; not guessing active run)",
					sessionId, runId, ex);
			return null;
		}
		RunSnapshot fallback = null;
		for (String candidateId : index) {
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
		return fallback;
	}

	/**
	 * 单键 CAS 删除 session→current（MI1）：current 仍等于 {@code expectedRunId} 才删。
	 *
	 * @return true = 本次确实删掉了；false = current 已换成他轮（不动作）
	 */
	private boolean isDeleteSessionCurrentIfOwned(String key, String expectedRunId) {
		Long deleted = this.redis.execute(SESSION_CURRENT_CAS_DELETE_SCRIPT, List.of(key), expectedRunId);
		return deleted != null && deleted > 0L;
	}

	/**
	 * 单键 CAS 回填 session→current（MI1）：current 仍等于 {@code expectedRunId} 才写 fallback，
	 * TTL 与会话同寿（脚本内 {@code SET ... PX}）。
	 *
	 * @return true = 本次确实写入；false = current 已换成他轮（不动作）
	 */
	private boolean isSetSessionCurrentIfOwned(String key, String expectedRunId, String fallbackRunId) {
		Long written = this.redis.execute(SESSION_CURRENT_CAS_SET_SCRIPT, List.of(key), expectedRunId, fallbackRunId,
				String.valueOf(sessionCurrentTtl().toMillis()));
		return written != null && written > 0L;
	}

	/**
	 * 在活跃索引的倒序候选里找"最新且仍活跃"的一轮（N1）。
	 *
	 * <p>按分数分组：同毫秒平手时 SUSPENDED 优先（与旧口径的比较器逐字同形）；只有当前分组确实没有
	 * 活跃轮时才看下一组 —— 快照反序列化只发生在真正被检查到的候选上。快照读失败向上抛，由
	 * {@link #clearSessionCurrent} 保留原索引（与旧口径"读不出来不猜"一致）。
	 */
	private RunSnapshot newestActive(String sessionId, Set<ZSetOperations.TypedTuple<String>> ordered,
			String excludeRunId) {
		List<String> tied = new ArrayList<>();
		Double tieScore = null;
		for (ZSetOperations.TypedTuple<String> entry : ordered) {
			String candidateId = entry == null ? null : entry.getValue();
			if (candidateId == null) {
				continue;
			}
			double score = ObjectUtils.defaultIfNull(entry.getScore(), 0d);
			if (tieScore == null || score != tieScore) {
				RunSnapshot picked = pickActive(sessionId, tied, excludeRunId);
				if (picked != null) {
					return picked;
				}
				tied.clear();
				tieScore = score;
			}
			if (!excludeRunId.equals(candidateId)) {
				tied.add(candidateId);
			}
		}
		return pickActive(sessionId, tied, excludeRunId);
	}

	/** 同一分数分组内挑一个活跃轮：SUSPENDED 优先，否则第一份活跃快照；stale 成员顺手摘掉。 */
	private RunSnapshot pickActive(String sessionId, List<String> candidateIds, String excludeRunId) {
		RunSnapshot running = null;
		for (String candidateId : candidateIds) {
			if (excludeRunId.equals(candidateId)) {
				continue;
			}
			RunSnapshot candidate = get(candidateId);
			if (candidate == null || (!RunSnapshot.SUSPENDED.equals(candidate.getStatus())
					&& !RunSnapshot.RUNNING.equals(candidate.getStatus()))) {
				forgetActiveRun(sessionId, candidateId);
				continue;
			}
			if (RunSnapshot.SUSPENDED.equals(candidate.getStatus())) {
				return candidate;
			}
			if (running == null) {
				running = candidate;
			}
		}
		return running;
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
			if (LogSampling.canAcquire("session-current-index-read")) {
				log.warn("session->current index read failed sessionId={}", sessionId, ex);
			}
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
	 * 现在取值与会话同寿：仅明确用户读侧的兼容续期路径调用
	 * {@link #renewSessionCurrent(String, String)}，ConfirmGate 后台心跳不调用；轮落到终态由
	 * {@link #clearSessionCurrent(String, String)} 主动删除。
	 */
	private Duration sessionCurrentTtl() {
		return this.properties.getEffectiveSessionTtl();
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
		return readSnapshot(runKey(runId), runId, RequestContextUtils.currentOrNull());
	}

	/**
	 * <b>显式身份读口</b>（恢复型入口的唯一正确读法）：键由<b>给定身份</b>拼，不读线程上下文。
	 *
	 * <p>为什么需要它：{@link #get(String)} 的键名由<b>当前线程身份</b>决定，而恢复型入口（定时链、
	 * 启动扫描、取消收尾重试）在无身份线程上只会命中 legacy 命名空间 —— 对任何带身份的轮次恒返回
	 * {@code null}。"先 store.get 再 useContext"因此在那些线程上是死循环的鸡生蛋：读记录要先有身份，
	 * 身份又要从记录里来。显式身份读口把身份变成<b>入参</b>（来自索引成员 / 交棒时的记录身份 /
	 * 信封），读与身份不再互相依赖。
	 *
	 * <p>归属复核与 {@link #get(String)} 同口径（记录身份必须等于给定身份，否则按不存在处理）；
	 * 但给定身份<b>不可用</b>时直接拒绝（{@link IllegalArgumentException}）—— 显式读口没有"无身份"
	 * 这种合法形态，缺身份必须 fail-closed，而不是悄悄退回 legacy 键。
	 */
	public RunSnapshot get(String runId, long tenantId, long userId) {
		if (!RunIdentityScope.usable(tenantId, userId)) {
			throw new IllegalArgumentException("Explicit-identity reads require a valid recorded identity (tenantId>0 and userId>0, or system -1): "
					+ tenantId + "/" + userId + " runId=" + runId);
		}
		return readSnapshot(runScopedKey(RUN_PREFIX, tenantId, userId, runId), runId,
				new TenantUser(tenantId, userId));
	}

	/** 按索引成员读快照（成员自带身份；不是复合成员 ⇒ 无归属，返回 {@code null}）。 */
	public RunSnapshot get(RunKeyScope.IndexRef ref) {
		if (ref == null) {
			return null;
		}
		return get(ref.runId(), ref.tenantId(), ref.userId());
	}

	private RunSnapshot readSnapshot(String key, String runId, TenantUser expected) {
		String raw;
		try {
			raw = this.redis.opsForValue().get(key);
		}
		catch (Exception ex) {
			throw new AiStateUnavailableException("Failed to read run snapshot " + runId + " (Redis unavailable)", ex);
		}
		if (raw == null) {
			return null;
		}
		RunSnapshot snapshot;
		try {
			snapshot = this.mapper.readValue(raw, RunSnapshot.class);
		}
		catch (Exception ex) {
			// 值损坏 = 数据问题，不是依赖不可用：保持 IllegalStateException（不得转成 503）
			throw new IllegalStateException("Failed to read run snapshot " + runId, ex);
		}
		// 归属复核（纵深防御）：键面已经按身份分开，这里再核一次记录里的身份，
		// 拒绝"键对了但记录不是我的"与"无归属旧快照进入新身份读路径"。
		if (expected != null && !expected.equals(recordIdentityOf(snapshot))) {
			log.warn("snapshot owner mismatch runId={} (recorded identity differs from expected): treating "
					+ "as absent; unowned/foreign snapshots must not enter the new-identity read path", runId);
			return null;
		}
		return snapshot;
	}

	/** 记录里的身份；字段缺失/为 0（本字段引入前的旧快照）返回 {@code null}。 */
	private static TenantUser recordIdentityOf(RunSnapshot snapshot) {
		if (snapshot == null || !RunIdentityScope.usable(snapshot.getTenantId(), snapshot.getUserId())) {
			return null;
		}
		return new TenantUser(snapshot.getTenantId(), snapshot.getUserId());
	}

	/**
	 * <b>轻量状态读取</b>：{@code ai:runstate:<runId>} 优先；缺失（旧快照/被淘汰）才读快照原文并
	 * 流式取顶层 {@code status}（不物化 {@link RunSnapshot}）。
	 *
	 * <p>为什么需要它：判活（续租存活判据、活跃轮候选）只关心一个状态串。旧口径
	 * {@link #get(String)} 会把整窗 {@code messageJson} 反序列化成对象图 —— 在续租线程上每 10s
	 * 一拍、在每次记忆合并里对每个候选付一次。状态键与快照在<b>同一条写 Lua</b> 里落库，因此
	 * 两者不会分叉；状态键缺失时回退快照，旧数据与新写路径因此同判据。
	 *
	 * @return 状态串；两者都不在（轮次已删除/已过期）或读不出来返回 {@code null}
	 *         （<b>null 是"不可知/不存在"，调用方不得当成"未取消"或"非终态"的证明</b>）
	 */
	public String runStatus(String runId) {
		if (StringUtils.isBlank(runId)) {
			return null;
		}
		if (this.redis == null) {
			// 进程内替身（InMemoryRunStore）：没有状态键面，退回被覆写的快照读口。
			try {
				RunSnapshot snapshot = get(runId);
				return snapshot == null ? null : snapshot.getStatus();
			}
			catch (RuntimeException ex) {
				return null;
			}
		}
		try {
			String state = this.redis.opsForValue().get(runStateKey(runId));
			if (StringUtils.isNotBlank(state)) {
				return state;
			}
			String raw = this.redis.opsForValue().get(runKey(runId));
			return raw == null ? null : topLevelStatus(raw);
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("run-lite-state-read")) {
				log.warn("run lite state read failed runId={} (treating as unknown)", runId, ex);
			}
			return null;
		}
	}

	/**
	 * 续租/判活用的<b>轻量非终态判据</b>（与 {@code ResilientChatService} / {@code ResumeService}
	 * 的同名私有判据同口径，但不再反序列化整份快照）。
	 *
	 * <ul>
	 * <li>状态键命中 ⇒ 按状态串判终态；</li>
	 * <li>状态键缺失 ⇒ 快照原文的顶层 {@code status}（旧快照没有状态键）；</li>
	 * <li>快照<b>不存在</b>（轮次已被销毁）⇒ {@code false}（按终态处理，别再为空轮续租）；</li>
	 * <li>读失败/解析失败 ⇒ {@code true}（保守：一次 Redis 抖动不得让续租自己放弃租约 ——
	 *     误判为假会提前放掉一个正在跑的轮的续跑权）。</li>
	 * </ul>
	 *
	 * @param identity 本轮记录身份（不可用 = 无法定位这一轮的键，保守视为仍活跃）
	 */
	public boolean isSnapshotNotTerminal(String runId, TenantUser identity) {
		if (StringUtils.isBlank(runId)) {
			return false;
		}
		if (identity == null) {
			// 身份不可确认：没有可读的键，保守回答"仍活跃"（不主动放掉一个可能还在跑的轮）。
			return true;
		}
		if (this.redis == null) {
			// 进程内替身（InMemoryRunStore）：没有状态键面，退回被覆写的快照读口（同旧判据）。
			try {
				RunSnapshot snapshot = get(runId, identity.tenantId(), identity.userId());
				return snapshot != null && !RunSnapshot.isTerminal(snapshot.getStatus());
			}
			catch (RuntimeException ex) {
				return true;
			}
		}
		try {
			String stateKey = runScopedKey(RUN_STATE_PREFIX, identity.tenantId(), identity.userId(), runId);
			String state = this.redis.opsForValue().get(stateKey);
			if (StringUtils.isNotBlank(state)) {
				return !RunSnapshot.isTerminal(state);
			}
			String snapshotKey = runScopedKey(RUN_PREFIX, identity.tenantId(), identity.userId(), runId);
			String raw = this.redis.opsForValue().get(snapshotKey);
			if (raw == null) {
				return false;
			}
			String status = topLevelStatus(raw);
			// status 缺失 = 结构异常（旧快照缺省字段是 RUNNING）：按"仍活跃"保守处理。
			return status == null || !RunSnapshot.isTerminal(status);
		}
		catch (RuntimeException ex) {
			return true;
		}
	}

	/** 当前线程身份下的 {@link #isSnapshotNotTerminal(String, TenantUser)}。 */
	public boolean isSnapshotNotTerminal(String runId) {
		return isSnapshotNotTerminal(runId, RequestContextUtils.currentOrNull());
	}

	public boolean snapshotNotTerminal(String runId, TenantUser identity) {
		return isSnapshotNotTerminal(runId, identity);
	}

	public boolean snapshotNotTerminal(String runId) {
		return isSnapshotNotTerminal(runId);
	}

	/**
	 * 流式读<b>顶层</b> {@code status}（不构造对象图、不读 {@code messageJson}）。
	 *
	 * <p>只认顶层字段名：嵌套对象（{@code context} 等）里的同名字段会被 {@code skipChildren} 跳过。
	 * 解析失败 / 顶层不是对象 / {@code status} 缺失或不是字符串 ⇒ {@code null}（调用方按"不可知"处理）。
	 */
	private String topLevelStatus(String rawJson) {
		try (JsonParser parser = this.mapper.getFactory().createParser(rawJson)) {
			if (parser.nextToken() != JsonToken.START_OBJECT) {
				return null;
			}
			while (parser.nextToken() == JsonToken.FIELD_NAME) {
				String name = parser.currentName();
				JsonToken value = parser.nextToken();
				if ("status".equals(name)) {
					return value == JsonToken.VALUE_STRING ? parser.getText() : null;
				}
				parser.skipChildren();
			}
			return null;
		}
		catch (Exception ex) {
			return null;
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
		if (StringUtils.isBlank(runId)) {
			return SnapshotOwner.missing();
		}
		String raw;
		try {
			raw = this.redis.opsForValue().get(runKey(runId));
		}
		catch (RuntimeException ex) {
			log.warn("snapshot owner read failed runId={} (treating as unreadable; caller must fail closed)", runId, ex);
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
			log.warn("snapshot bad value runId={} (owner unreadable; caller must fail closed)", runId, ex);
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
	 * {@code ai:activity:<runId>}（业务活动锚点）与 {@code ai:cancel:{<tenantId>:<userId>:run:<runId>}}（取消标志）刻意不在其中
	 * —— 前两者是观测/判据面（裸前缀、单键命令）、后者要活到驱动线程看见它，各自按 TTL 走；
	 * {@code ai:lock:* }（taskId 维度）更不在，资源锁的寿命与 run 的生死无关。
	 *
	 * <p><b>删后语义 = 原子性恢复</b>（评审 Minor 3）：13 键全部带 {@code {run:<runId>}} tag ⇒ 同槽 ⇒
	 * 这一条 {@code DEL} 在真集群里是"一条命令、原子生效"。注意这不是"不加 tag 就会 CROSSSLOT"——
	 * 真实 Lettuce 的 {@code del(Iterable)} 会按槽分区、<b>静默拆成 N 条</b>，故障形态是
	 * <b>原子性丢失</b>（删到一半崩 = 半截状态），不是报错；测试替身对多键跨槽抛异常比真客户端更严，
	 * 属刻意选择（把"悄悄不原子"变成可见的红）。
	 *
	 * <p>13 键 = 快照 / 轻量状态 / 待决 / 认领 / 台账 / 幂等索引 / seq / 事件 Stream / 续跑租约 /
	 * fence / req 位 / 决策 guard / <b>取号计数器</b>。最后那个是 per-run 计数器（无业务语义，
	 * 但不删就会随轮次永久留存）；轻量状态键必须与快照同生同死，否则删除后残留的状态串会让
	 * 同 id 的新一轮被判成旧状态。
	 */
	public void deleteRun(String runId) {
		deleteRun(runId, RequestContextUtils.require());
	}

	/**
	 * 按<b>记录身份</b>删除这一轮的键族（系统路径：会话销毁、租户注销）。
	 *
	 * <p>键名由身份拼出（13 键 + 全局索引成员），因此"传给谁"决定"删到谁"：用错身份时旧实现的
	 * {@code DEL} 打在不存在的键上、{@code SREM} 摘不存在的成员，<b>既不抛错也不打日志</b> ——
	 * 销毁报告成功而数据仍在。本重载把身份做成入参（来自记录/索引成员），并对不匹配<b>响亮失败</b>：
	 * <ul>
	 * <li>身份不可用（{@code 0/0}）⇒ {@link IllegalArgumentException}（缺身份 fail-closed，不退回 legacy 删）；</li>
	 * <li>记录存在但归属 ≠ 给定身份 ⇒ {@link IllegalStateException}（不得静默 no-op）；</li>
	 * <li>记录已不在（重复销毁/已过期）⇒ 按给定身份删自己命名空间的残留键（幂等）。</li>
	 * </ul>
	 */
	public void deleteRun(String runId, long tenantId, long userId) {
		if (!RunIdentityScope.usable(tenantId, userId)) {
			throw new IllegalArgumentException("Run deletion requires a valid recorded identity (tenantId>0 and userId>0, or system -1): "
					+ tenantId + "/" + userId + " runId=" + runId);
		}
		deleteRun(runId, new TenantUser(tenantId, userId));
	}

	/** 按索引成员删除这一轮的键族（成员自带身份）。 */
	public void deleteRun(RunKeyScope.IndexRef ref) {
		if (ref == null) {
			throw new IllegalArgumentException("Run deletion requires identity embedded in the index member; identity must not be guessed from runId");
		}
		deleteRun(ref.runId(), ref.tenantId(), ref.userId());
	}

	private void deleteRun(String runId, TenantUser identity) {
		if (identity == null) {
			throw new IllegalArgumentException("Run deletion requires an identity runId=" + runId);
		}
		RunSnapshot snapshot = readForDelete(runId, identity);
		if (snapshot != null && !identity.equals(recordIdentityOf(snapshot))) {
			// 记录在（且是从调用线程身份那一侧读到的）却与给定删除身份不符 ⇒ 拒绝。
			// 静默 no-op 会让销毁报告"成功"而这一轮的数据仍在。
			throw new IllegalStateException("Refusing identity " + identity + " to delete a run it does not own runId=" + runId
					+ " (recordOwner=" + recordIdentityOf(snapshot) + "): keys are derived from identity; deletion with the wrong identity is a silent no-op or deletes the wrong data");
		}
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			deleteRunKeys(runId, snapshot, identity);
		}
	}

	/**
	 * 删除前的归属读取：<b>先按调用线程身份</b>（若有）、再按给定身份读 —— 于是"记录存在但不属于给定身份"
	 * 可被识别并拒绝，而不是落到"删自己命名空间的空键"上。
	 */
	private RunSnapshot readForDelete(String runId, TenantUser identity) {
		RunSnapshot snapshot = null;
		if (RequestContextUtils.currentOrNull() != null) {
			try {
				snapshot = get(runId);
			}
			catch (RuntimeException ex) {
				if (LogSampling.canAcquire("pre-delete-snapshot-read")) {
					log.warn("pre-delete snapshot read failed runId={} (session index members left to TTL/reconcile)", runId, ex);
				}
			}
		}
		if (snapshot == null && identity != null) {
			try {
				snapshot = get(runId, identity.tenantId(), identity.userId());
			}
			catch (RuntimeException ex) {
				if (LogSampling.canAcquire("pre-delete-identity-snapshot-read")) {
					log.warn("pre-delete snapshot read by recorded identity failed runId={} (treating as absent)", runId, ex);
				}
			}
		}
		return snapshot;
	}

	private void deleteRunKeys(String runId, RunSnapshot snapshot, TenantUser identity) {
		// 先放掉本进程持有的租约：删键之后脚本再比对就会扑空，进程内视图会成为幽灵所有权
		releaseRunLease(runId);
		// 节流自愈的进程内状态随删除清理（身份取删除身份；无身份时退回记录身份）。
		long repairTenantId = identity != null ? identity.tenantId()
				: (snapshot == null ? 0L : snapshot.getTenantId());
		long repairUserId = identity != null ? identity.userId() : (snapshot == null ? 0L : snapshot.getUserId());
		forgetRunIndexRepair(repairTenantId, repairUserId, runId);
		// B23L1 批②a：租约 / fence / req 位 / decided guard 同属"本轮的全部键"——
		// 留着它们会让下一轮同 id 的运行继承前任的 fence 与决策（且 fence 只增不减 ⇒ 新租约写不进去）。
		// 批③a：幂等索引随台账一起删（索引是台账的指针，台账没了它只会指向空气）。
		// C65：台账的计数用元数据投影（~meta:<index>）就在这个索引 HASH 里 —— 随同一把键删净，
		// 不需要第 14 个键，也不会破坏 deleteRun 的"一条 DEL、同槽、原子"契约。
		// 注意：ai:lock:*（LeaseKeys，按 taskId 记）**不在这里** —— 资源锁的寿命与 run 的生死无关，
		// 由 app.ai.lease.resource-ttl 自清；在轮次回收里删锁会把别人的业务互斥一起放掉。
		// B23L1 批①（R1-05 + 评审 I-1）：13 键全部带 {run:<runId>} tag ⇒ 同槽 ⇒ 这一条 DEL 在 Cluster 下
		// 仍是"一条命令、原子生效"（跨槽的 DEL 会被客户端拆成 N 条，原子性随之消失）。
		// 取号计数器与 fence 必须一起删：留下计数器而删掉 fence 只是浪费一个键；反过来（删计数器留 fence）
		// 才会让下一任租约从 1 起算、被残留的 fence 拒死 —— 所以两者在同一条命令里。
		// 轻量状态键随快照一起删：残留状态串会让同 id 的新一轮按旧状态被判活/判终态。
		this.redis.delete(List.of(runKey(runId), runStateKey(runId), pendingKey(runId), claimKey(runId),
				ledgerKey(runId), idemKey(runId), seqKey(runId), xeventsKey(runId), runLeaseKey(runId),
				fenceKey(runId), reqKey(runId), decidedKey(runId), runLeaseEpochKey(runId)));
		// 全局索引成员是复合成员（{@code tenantId/userId/runId}）：只摘<b>本身份这一条</b>，
		// 同 runId 的其它租户/用户成员原样保留。
		// 13 键已经删成功时，索引清理失败<b>不得</b>把整次删除报成失败（会误报"13 键删除失败"并
		// 把已删净的轮次重新 deferred）。stale 成员由 prune / 恢复面按快照缺失跳过，best-effort 即可。
		if (!isCleanupRunIndexesPipelined(runId, snapshot, identity)) {
			cleanupRunIndexesSeparately(runId, snapshot, identity);
		}
	}

	private boolean isCleanupRunIndexesPipelined(String runId, RunSnapshot snapshot, TenantUser identity) {
		List<IndexRemoval> zsetRemovals = new ArrayList<>(4);
		if (identity != null) {
			zsetRemovals.add(new IndexRemoval(RECOVERY_DUE_INDEX, RunKeyScope.indexMember(identity, runId)));
			zsetRemovals.add(new IndexRemoval(identityRecoveryDueKey(identity.tenantId(), identity.userId()), runId));
			zsetRemovals.add(new IndexRemoval(runListIndexKey(identity.tenantId(), identity.userId()), runId));
		}
		String sessionId = snapshot == null ? null : snapshot.getSessionId();
		String sessionRunsKey = null;
		if (StringUtils.isNotBlank(sessionId)) {
			zsetRemovals.add(new IndexRemoval(sessionActiveKey(sessionId), runId));
			long tenantId = identity == null ? snapshot.getTenantId() : identity.tenantId();
			long userId = identity == null ? snapshot.getUserId() : identity.userId();
			sessionRunsKey = sessionRunsKey(sessionId, tenantId, userId);
		}
		if (zsetRemovals.isEmpty() && sessionRunsKey == null) {
			return true;
		}
		int operationCount = zsetRemovals.size() + (sessionRunsKey == null ? 0 : 1);
		try {
			String runsKey = sessionRunsKey;
			List<Object> results = this.redis.executePipelined((RedisCallback<Object>) connection -> {
				for (IndexRemoval removal : zsetRemovals) {
					connection.zRem(removal.key().getBytes(StandardCharsets.UTF_8),
							removal.member().getBytes(StandardCharsets.UTF_8));
				}
				if (runsKey != null) {
					connection.sRem(runsKey.getBytes(StandardCharsets.UTF_8), runId.getBytes(StandardCharsets.UTF_8));
				}
				return null;
			});
			return results != null && results.size() >= operationCount;
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("pipelined-run-index-cleanup")) {
				log.warn("pipelined run index cleanup failed runId={} (using separate cleanup)", runId, ex);
			}
			return false;
		}
	}

	private void cleanupRunIndexesSeparately(String runId, RunSnapshot snapshot, TenantUser identity) {
		if (identity != null) {
			try {
				this.redis.opsForZSet().remove(RECOVERY_DUE_INDEX, RunKeyScope.indexMember(identity, runId));
				this.redis.opsForZSet().remove(identityRecoveryDueKey(identity.tenantId(), identity.userId()), runId);
				this.redis.opsForZSet().remove(runListIndexKey(identity.tenantId(), identity.userId()), runId);
			}
			catch (Exception ex) {
				log.warn("run recovery/list index cleanup failed runId={} (stale member left to bounded reconcile)",
						runId, ex);
			}
		}
		if (snapshot != null && StringUtils.isNotBlank(snapshot.getSessionId())) {
			forgetActiveRun(snapshot.getSessionId(), runId);
			long tenantId = identity == null ? snapshot.getTenantId() : identity.tenantId();
			long userId = identity == null ? snapshot.getUserId() : identity.userId();
			try {
				this.redis.opsForSet().remove(sessionRunsKey(snapshot.getSessionId(), tenantId, userId), runId);
			}
			catch (Exception ex) {
				log.warn("session->run index member cleanup failed sessionId={} runId={} (stale member left to TTL/reconcile)",
						snapshot.getSessionId(), runId, ex);
			}
		}
	}

	private record IndexRemoval(String key, String member) {
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
			this.leaseService.isReleasedIfHeld(runLeaseKey(runId));
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("resume-lease-release")) {
				log.warn("resume lease release failed runId={}", runId, ex);
			}
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
		return ObjectUtils.defaultIfNull(authority, LeaseService.LeaseAuthority.NEVER_HELD);
	}

	/** 只读拒绝的节流 WARN（每个 runId 第一条点名动作，其后 debug）。 */
	private void warnReadOnly(String runId, LeaseService.LeaseAuthority authority, String action) {
		if (this.readOnlyWarned.size() > FENCE_AHEAD_WARNED_CAP) {
			this.readOnlyWarned.clear();
		}
		String detail = authority == LeaseService.LeaseAuthority.LOST
				? "lease definitely lost (renew returned 0 / ownership check failed: another instance took over)"
				: "lease facility unavailable (cannot confirm ownership)";
		if (this.readOnlyWarned.add(runId)) {
			log.warn("resume authority read-only: refusing {} runId={} ({}). This instance no "
					+ "longer advances this run; recovery/takeover belongs to the new owner", action,
					runId, detail);
		}
		else {
			if (log.isDebugEnabled()) log.debug("resume authority read-only: refusing {} runId={} ({}; already warned for this run)", action, runId, detail);
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
	 * 的"答案面"写口（快照终态 / 记忆整窗）复用（RT-20）—— 帧、台账、序号、快照、记忆因此共享
	 * 同一套判据，不会出现"快照拒写、但答案已经写回"的半只读状态。
	 */
	public boolean isWritePermitted(String runId, String action) {
		LeaseService.LeaseAuthority authority = runLeaseAuthority(runId);
		if (authority.isReadOnly()) {
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

	public boolean permittedToWrite(String runId, String action) {
		return isWritePermitted(runId, action);
	}

	/** fence 已越过本进程 epoch 的拒写留痕（每个 runId 一条 WARN，其后 debug）。 */
	private void warnFenceStale(String runId, String action, long epoch, long fence) {
		if (this.fenceStaleWarned.size() > FENCE_AHEAD_WARNED_CAP) {
			this.fenceStaleWarned.clear();
		}
		if (this.fenceStaleWarned.add(runId)) {
			log.warn("resume authority stale: refusing {} runId={} (fence={} above local epoch={}: "
					+ "another instance took over; read-only recovery, no frames/ledger/seq)", action, runId, fence, epoch);
		}
		else {
			if (log.isDebugEnabled()) log.debug("resume authority stale: refusing {} runId={} (fence={} > epoch={}; already warned for this run)", action, runId,
					fence, epoch);
		}
	}

	/** fence 读不出来（Redis 抖动/不可用）⇒ 按"无法证明仍是主人"拒写，与认领侧 fail-closed 同口径。 */
	private void warnFenceUnreadable(String runId, String action, RuntimeException ex) {
		if (this.fenceStaleWarned.size() > FENCE_AHEAD_WARNED_CAP) {
			this.fenceStaleWarned.clear();
		}
		if (LogSampling.canAcquire("resume-authority-unconfirmed")) {
			log.warn("resume authority unconfirmed: refusing {} runId={} (fence read failed; treating as unconfirmed)", action, runId, ex);
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

	/** 请求面列表只读当前身份的窄 ZSET，不再先做全局 SMEMBERS 和逐成员 EXISTS。 */
	public List<String> allRunIds() {
		TenantUser current = RequestContextUtils.currentOrNull();
		if (current == null) {
			return List.of();
		}
		Set<String> ids = this.redis.opsForZSet().range(runListIndexKey(current.tenantId(), current.userId()), 0, -1);
		if (CollectionUtils.isEmpty(ids)) {
			return List.of();
		}
		List<String> out = new ArrayList<>(ids);
		out.sort(String::compareTo);
		return out;
	}

	public record RecoveryCandidate(RunKeyScope.IndexRef ref, long dueAtMs) {
	}

	/** 兼容旧调用面，但系统恢复只允许取一页到期候选。 */
	public List<RunKeyScope.IndexRef> allRunRecords() {
		return recoveryDueRecords(System.currentTimeMillis(), RECOVERY_RECONCILE_BATCH).stream()
			.map(RecoveryCandidate::ref).toList();
	}

	/** 候选连同本次 score 返回，后续 defer/remove 必须以它做 CAS。 */
	public List<RecoveryCandidate> recoveryDueRecords(long nowMs, int limit) {
		if (this.redis == null || limit <= 0) {
			return List.of();
		}
		TenantUser current = RequestContextUtils.currentOrNull();
		String key = current == null ? RECOVERY_DUE_INDEX : identityRecoveryDueKey(current.tenantId(), current.userId());
		Set<ZSetOperations.TypedTuple<String>> members = this.redis.opsForZSet().rangeByScoreWithScores(key,
				Double.NEGATIVE_INFINITY, nowMs, 0, limit);
		if (CollectionUtils.isEmpty(members)) {
			return List.of();
		}
		List<RecoveryCandidate> out = new ArrayList<>(members.size());
		for (ZSetOperations.TypedTuple<String> tuple : members) {
			String member = tuple.getValue();
			Double score = tuple.getScore();
			RunKeyScope.IndexRef ref = current == null ? RunKeyScope.parseIndexMember(member)
					: new RunKeyScope.IndexRef(current.tenantId(), current.userId(), member);
			if (ref != null && score != null) {
				out.add(new RecoveryCandidate(ref, score.longValue()));
			}
		}
		return out;
	}

	/** 按到期 score + member 取下一页；同分成员按 ZSET 字典序续接，单次 Redis 读取最多 256 项。 */
	public List<RecoveryCandidate> recoveryDueRecordsAfter(long nowMs, long afterDueAtMs, String afterMember, int limit) {
		if (this.redis == null || limit <= 0) {
			return List.of();
		}
		int pageLimit = Math.min(256, limit);
		TenantUser current = RequestContextUtils.currentOrNull();
		String key = current == null ? RECOVERY_DUE_INDEX : identityRecoveryDueKey(current.tenantId(), current.userId());
		if (afterMember == null) {
			Set<ZSetOperations.TypedTuple<String>> members = this.redis.opsForZSet().rangeByScoreWithScores(key,
					Double.NEGATIVE_INFINITY, nowMs, 0, pageLimit);
			return recoveryCandidates(members, current, pageLimit);
		}
		List<RecoveryCandidate> out = new ArrayList<>(pageLimit);
		long offset = 0L;
		while (out.size() < pageLimit) {
			Set<ZSetOperations.TypedTuple<String>> members = this.redis.opsForZSet().rangeByScoreWithScores(key,
					afterDueAtMs, afterDueAtMs, offset, 256);
			if (CollectionUtils.isEmpty(members)) {
				break;
			}
			offset += members.size();
			for (ZSetOperations.TypedTuple<String> tuple : members) {
				String member = tuple.getValue();
				Double score = tuple.getScore();
				if (member == null || score == null || member.compareTo(afterMember) <= 0) {
					continue;
				}
				RunKeyScope.IndexRef ref = current == null ? RunKeyScope.parseIndexMember(member)
						: new RunKeyScope.IndexRef(current.tenantId(), current.userId(), member);
				if (ref != null) {
					out.add(new RecoveryCandidate(ref, score.longValue()));
					if (out.size() == pageLimit) {
						return out;
					}
				}
			}
			if (members.size() < 256) {
				break;
			}
		}
		if (out.size() < pageLimit && afterDueAtMs < nowMs) {
			Set<ZSetOperations.TypedTuple<String>> members = this.redis.opsForZSet().rangeByScoreWithScores(key,
					Math.nextUp((double) afterDueAtMs), nowMs, 0, pageLimit - out.size());
			out.addAll(recoveryCandidates(members, current, pageLimit - out.size()));
		}
		return out;
	}

	private List<RecoveryCandidate> recoveryCandidates(Set<ZSetOperations.TypedTuple<String>> members,
			TenantUser current, int limit) {
		if (CollectionUtils.isEmpty(members) || limit <= 0) {
			return List.of();
		}
		List<RecoveryCandidate> out = new ArrayList<>(Math.min(members.size(), limit));
		for (ZSetOperations.TypedTuple<String> tuple : members) {
			String member = tuple.getValue();
			Double score = tuple.getScore();
			RunKeyScope.IndexRef ref = current == null ? RunKeyScope.parseIndexMember(member)
					: new RunKeyScope.IndexRef(current.tenantId(), current.userId(), member);
			if (ref != null && score != null) {
				out.add(new RecoveryCandidate(ref, score.longValue()));
				if (out.size() == limit) {
					break;
				}
			}
		}
		return out;
	}

	/** 每拍最多 64 项，并以 ZSET 偏移轮转；state 缺失必须回读快照确认。 */
	public int pruneRunIndex() {
		if (this.redis == null) {
			return 0;
		}
		try {
			Long cardinality = this.redis.opsForZSet().zCard(RECOVERY_DUE_INDEX);
			long total = ObjectUtils.defaultIfNull(cardinality, 0L);
			if (total == 0L) {
				this.recoveryReconcileOffset.set(0L);
				return 0;
			}
			long offset = this.recoveryReconcileOffset.get();
			if (offset >= total) {
				offset = 0L;
			}
			Set<ZSetOperations.TypedTuple<String>> members = this.redis.opsForZSet().rangeWithScores(
					RECOVERY_DUE_INDEX, offset, Math.min(total - 1L, offset + RECOVERY_RECONCILE_BATCH - 1L));
			if (CollectionUtils.isEmpty(members)) {
				this.recoveryReconcileOffset.set(0L);
				return 0;
			}
			long nextOffset = offset + members.size();
			this.recoveryReconcileOffset.set(nextOffset >= total ? 0L : nextOffset);
			return pruneRecoveryMembers(members);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.C1_RUN_INDEX_PRUNE, ex);
			log.warn("recovery due index bounded reconcile failed (retry next tick)", ex);
			return 0;
		}
	}

	private int pruneRecoveryMembers(Set<ZSetOperations.TypedTuple<String>> members) {
		int removed = 0;
		for (ZSetOperations.TypedTuple<String> tuple : members) {
			String member = tuple.getValue();
			Double score = tuple.getScore();
			if (member == null || score == null) {
				continue;
			}
			long expectedScore = score.longValue();
			RunKeyScope.IndexRef ref = RunKeyScope.parseIndexMember(member);
			if (ref == null) {
				Long result = this.redis.execute(RECOVERY_REMOVE_SCRIPT, List.of(RECOVERY_DUE_INDEX),
						String.valueOf(expectedScore), member);
				if (result != null && result > 0L) {
					removed++;
				}
				continue;
			}
			RunSnapshot snapshot = get(ref.runId(), ref.tenantId(), ref.userId());
			if (snapshot == null || RunSnapshot.isTerminal(snapshot.getStatus())) {
				if (isRecoveryCandidateRemoved(ref.runId(), ref.tenantId(), ref.userId(), expectedScore)) {
					if (snapshot == null) {
						this.redis.opsForZSet().remove(runListIndexKey(ref.tenantId(), ref.userId()), ref.runId());
					}
					removed++;
				}
			}
		}
		return removed;
	}

	/** 对账只由恢复扫描触发；创建路径不执行任何全量或逐成员清理。 */
	public void reconcileRecoveryIndexThrottled() {
		Duration interval = this.properties == null || this.properties.getStream() == null ? null
				: this.properties.getStream().getIndexPruneInterval();
		if (interval == null || interval.isZero() || interval.isNegative()) {
			return;
		}
		long now = System.currentTimeMillis();
		long last = this.indexPrunedAtMs.get();
		if (now - last >= interval.toMillis() && this.indexPrunedAtMs.compareAndSet(last, now)) {
			pruneRunIndex();
		}
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
		if (!isWritePermitted(runId, "suspended snapshot")) {
			throw new AiStateUnavailableException("Cannot confirm resume authority; refusing to write parked state " + runId);
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
			throw new IllegalStateException("Run snapshot not found before parking " + runId);
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
			throw new AiStateUnavailableException("Failed to write pending entry " + pending.getToolCallId(), ex);
		}
	}

	/** 执行结果只允许由原 claim owner 在原 epoch 内回写。 */
	public void completeExecution(String runId, PendingToolCall pending, long claimedEpoch, String claimedBy) {
		String expectedClaim = claimedEpoch + "|" + claimedBy;
		try {
			List<?> result = this.redis.execute(COMPLETE_EXECUTION_SCRIPT,
					List.of(pendingKey(runId), claimKey(runId), fenceKey(runId)), pending.getToolCallId(),
					expectedClaim, this.mapper.writeValueAsString(pending), String.valueOf(ttlFor(runId).toMillis()),
					String.valueOf(claimedEpoch));
			if (result == null || result.size() < 2 || ((Number) result.getFirst()).longValue() != 1L) {
				String outcome = result == null || result.size() < 2 ? "UNKNOWN" : String.valueOf(result.get(1));
				throw new AiStateUnavailableException("Execution write-back condition mismatch " + pending.getToolCallId() + ": " + outcome);
			}
		}
		catch (AiStateUnavailableException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new AiStateUnavailableException("Failed to conditionally write execution result " + pending.getToolCallId(), ex);
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
		List<Map<Object, Object>> batch = readPendingHashes(runId);
		if (batch != null) {
			return decodePendings(batch.getFirst(), batch.get(1), batch.get(2));
		}
		return readPendingsSeparately(runId, lenient);
	}

	private List<PendingToolCall> readPendingsSeparately(String runId, boolean lenient) {
		Map<Object, Object> all;
		try {
			all = this.redis.opsForHash().entries(pendingKey(runId));
		}
		catch (Exception ex) {
			if (!lenient) {
				throw new AiStateUnavailableException("Failed to read pending entry batch: " + runId, ex);
			}
			RedisDegradation.record(RedisDegradation.A6_PENDING_READ, ex);
			log.warn("pending entries read failed runId={} (lenient reader degrades to empty; cancel finalize/display continue)", runId, ex);
			return List.of();
		}
		Map<Object, Object> markers = requestMarkers(runId);
		Map<Object, Object> decisions = lenient ? decisionMarkersLenient(runId) : decisionMarkers(runId);
		return decodePendings(all, markers, decisions);
	}

	private List<Map<Object, Object>> readPendingHashes(String runId) {
		List<String> keys = List.of(pendingKey(runId), reqKey(runId), decidedKey(runId));
		try {
			List<Object> results = this.redis.executePipelined((RedisCallback<Object>) connection -> {
				for (String key : keys) {
					connection.hGetAll(key.getBytes(StandardCharsets.UTF_8));
				}
				return null;
			});
			if (results == null || results.size() < keys.size()) {
				return null;
			}
			List<Map<Object, Object>> out = new ArrayList<>(keys.size());
			for (int index = 0; index < keys.size(); index++) {
				if (!(results.get(index) instanceof Map<?, ?> entries)) {
					return null;
				}
				Map<Object, Object> normalized = new LinkedHashMap<>();
				for (Map.Entry<?, ?> entry : entries.entrySet()) {
					normalized.put(asString(entry.getKey()), asString(entry.getValue()));
				}
				out.add(normalized);
			}
			return out;
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("pipelined-pending-marker-read")) {
				log.warn("pipelined pending/marker read failed runId={} (using separate reads)", runId, ex);
			}
			return null;
		}
	}

	private List<PendingToolCall> decodePendings(Map<Object, Object> all, Map<Object, Object> markers,
			Map<Object, Object> decisions) {
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
				throw new IllegalStateException("Failed to read pending entry: " + entry.getValue(), ex);
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
			log.warn("pending entry read failed runId={} toolCallId={} (lenient reader degrades to null)", runId, toolCallId, ex);
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
			throw new AiStateUnavailableException("Failed to read pending entry " + toolCallId, ex);
		}
		if (raw == null) {
			return null;
		}
		try {
			return this.mapper.readValue(raw, PendingToolCall.class);
		}
		catch (Exception ex) {
			// JSON 损坏是数据错误，不是基础设施故障：保持原 IllegalStateException。
			throw new IllegalStateException("Failed to read pending entry " + toolCallId, ex);
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
			log.warn("decision guard read failed runId={} toolCallId={} (read side fail-closed: refusing to rebuild the card as undecided)",
					runId, toolCallId, ex);
			throw new AiStateUnavailableException("Failed to read decision guard " + toolCallId, ex);
		}
	}

	/** 非抛版单条 guard 读口（展示/等待循环/取消收尾）：读失败计为降级并返回 null。 */
	private Object decisionMarkerLenient(String runId, String toolCallId) {
		try {
			return this.redis.opsForHash().get(decidedKey(runId), toolCallId);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.A6_DECISION_GUARD_READ, ex);
			if (LogSampling.canAcquire("decision-guard-read-lenient")) {
				log.warn("decision guard read failed runId={} toolCallId={} (lenient reader continues with unmerged guard)",
						runId, toolCallId, ex);
			}
			return null;
		}
	}

	/** 整批 guard 值（{@link #pendings} 一次读完，避免 N 次往返；读失败抛，不得折成空表）。 */
	private Map<Object, Object> decisionMarkers(String runId) {
		try {
			Map<Object, Object> markers = this.redis.opsForHash().entries(decidedKey(runId));
			return ObjectUtils.defaultIfNull(markers, Map.of());
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.A6_DECISION_GUARD_READ, ex);
			log.warn("decision guard read failed runId={} (batch read side fail-closed: refusing to rebuild the card as undecided)", runId, ex);
			throw new AiStateUnavailableException("Failed to read decision guard batch " + runId, ex);
		}
	}

	/** 非抛版整批 guard 读口（展示/取消收尾）：读失败返回空表并计数。 */
	private Map<Object, Object> decisionMarkersLenient(String runId) {
		try {
			Map<Object, Object> markers = this.redis.opsForHash().entries(decidedKey(runId));
			return ObjectUtils.defaultIfNull(markers, Map.of());
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.A6_DECISION_GUARD_READ, ex);
			if (LogSampling.canAcquire("decision-guard-batch-read-lenient")) {
				log.warn("decision guard read failed runId={} (lenient reader continues with unmerged guard)", runId, ex);
			}
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
		if (!isDecisionStatusMergeable(status)) {
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
		// 结算时刻取 guard 里记的 atMs（有的话）：它是"决策真正发生的时刻"，比读侧此刻更准。
		// 段式兼容：status|atMs|instanceId / status|atMs / 裸 status —— 有第二个 | 取中间段，
		// 否则取余段；空值或非数字只缺时间，不影响已成立的状态。
		if (sep >= 0) {
			try {
				int end = raw.indexOf('|', sep + 1);
				String atMsText = end < 0 ? raw.substring(sep + 1) : raw.substring(sep + 1, end);
				long atMs = Long.parseLong(atMsText);
				if (atMs > 0L) {
					pending.setResolvedAtMs(atMs);
				}
			}
			catch (RuntimeException ex) {
				// 坏值/旧形态：结论成立，时刻缺席
			}
		}
	}

	/** 读侧可据 guard 完整重建的结论集合（I1；见 {@link #applyDecisionMarker}）。 */
	public static boolean isDecisionStatusMergeable(String status) {
		return PendingToolCall.REJECTED.equals(status) || PendingToolCall.FRONTEND_CANCELLED.equals(status)
				|| PendingToolCall.CANCELLED.equals(status);
	}

	public static boolean mergeableDecisionStatus(String status) {
		return isDecisionStatusMergeable(status);
	}

	/** 单个条目的 req 位（{@code ai:req:} 的 field；null = 未发射/旧数据）。 */
	private Object requestMarker(String runId, String toolCallId) {
		try {
			return this.redis.opsForHash().get(reqKey(runId), toolCallId);
		}
		catch (Exception ex) {
			// 位读不出来不影响条目本身（宁可按"未发射"处理：少一张卡，不让用户点到未生效的卡）
			if (LogSampling.canAcquire("request-frame-marker-read")) {
				log.warn("request frame marker read failed runId={} toolCallId={}", runId, toolCallId, ex);
			}
			return null;
		}
	}

	/** 整批 req 位（{@link #pendings} 一次读完，避免 N 次往返）。 */
	private Map<Object, Object> requestMarkers(String runId) {
		try {
			Map<Object, Object> markers = this.redis.opsForHash().entries(reqKey(runId));
			return ObjectUtils.defaultIfNull(markers, Map.of());
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("request-frame-markers-read")) {
				log.warn("request frame marker read failed runId={}", runId, ex);
			}
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
			log.warn("request frame entry read failed runId={} toolCallId={} "
					+ "(degrading to unknown, not blocking this turn)", runId, toolCallId, ex);
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
			log.warn("request frame marker write failed runId={} toolCallId={} (frame still emitted)", runId, toolCallId, ex);
		}
		try {
			PendingToolCall fresh = pending(runId, toolCallId);
			return ObjectUtils.defaultIfNull(fresh, pending);
		}
		catch (AiStateUnavailableException ex) {
			log.warn("post-set request frame recheck failed runId={} toolCallId={} "
					+ "(keeping known entry, not blocking this turn)", runId, toolCallId, ex);
			return pending;
		}
	}

	// ── 执行认领 / 台账（跨进程不重放） ────────────────────────────────────

	/**
	 * 原子认领一次真实执行（SP-02 E2 语义的补强）。
	 *
	 * <p>
	 * 同槽 Lua 保证同一个 {@code toolCallId} 全集群只可能有一个实例认领成功，
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
	 * 原子排他语义不变；读口 {@link #claimedBy} 剥前缀，对调用方返回的仍是实例标识。
	 * 认领所用 epoch 同时写进条目的 {@link PendingToolCall#getClaimedEpoch()}（additive）。
	 *
	 * @return true = 本次由当前实例认领（可以执行）；false = 已被他实例认领或本实例已失去续跑权
	 * （<b>不得</b>执行）。批③a 起两者可用 {@link #tryClaimExecution} 区分（{@code LOST} /
	 * {@code STALE_FENCE}）
	 */
	public boolean isExecutionClaimed(String runId, String toolCallId, String executedBy) {
		// 批③a：布尔口保留（既有调用与用例零改动），三态由 tryClaimExecution 给出；
		// LOST 与 STALE_FENCE 在布尔面上都是 false（"不得执行"这一点相同）。
		return tryClaimExecution(runId, toolCallId, executedBy).won();
	}

	public boolean claimExecution(String runId, String toolCallId, String executedBy) {
		return isExecutionClaimed(runId, toolCallId, executedBy);
	}

	/**
	 * 原子认领并给出结论。与 {@link #claimExecution} 共用同一段 Lua：取消事实、fence、原状态、
	 * claim 和完整 pending 一次推进；取消命中时不写新 claim，见 {@link ClaimOutcome#CANCELLED}。
	 */
	public ClaimResult tryClaimExecution(String runId, String toolCallId, String executedBy) {
		LeaseService.LeaseAuthority authority = runLeaseAuthority(runId);
		if (authority.isReadOnly()) {
			warnReadOnly(runId, authority, "new tool claim");
			return new ClaimResult(ClaimOutcome.STALE_FENCE, heldRunEpoch(runId), 0L);
		}
		long epoch = heldRunEpoch(runId);
		List<?> result = this.redis.execute(CLAIM_EXECUTION_SCRIPT,
				List.of(claimKey(runId), pendingKey(runId), fenceKey(runId), CancellationRegistry.cancelKey(runId)),
				toolCallId, String.valueOf(epoch), executedBy, String.valueOf(System.currentTimeMillis()),
				String.valueOf(ttlFor(runId).toMillis()));
		if (result == null || result.size() < 3) {
			throw new AiStateUnavailableException("Claim script returned null " + toolCallId);
		}
		long fence = ((Number) result.get(2)).longValue();
		String outcome = String.valueOf(result.get(1));
		if ("CANCELLED".equals(outcome)) {
			return new ClaimResult(ClaimOutcome.CANCELLED, epoch, fence);
		}
		if ("STALE_EPOCH".equals(outcome)) {
			log.warn("STALE_EPOCH refused claim runId={} toolCallId={} epoch={} fence={}", runId, toolCallId,
					epoch, fence);
			return new ClaimResult(ClaimOutcome.STALE_FENCE, epoch, fence);
		}
		boolean won = ((Number) result.getFirst()).longValue() == 1L && "WON".equals(outcome);
		return new ClaimResult(won ? ClaimOutcome.WON : ClaimOutcome.LOST, epoch, fence);
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
			return Long.parseLong(ObjectUtils.defaultIfNull(raw, "0"));
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.A1_FENCE_READ, ex);
			log.warn("fence read failed runId={} (claim side fail-closed: refusing claim, no fence=0 pass)", runId, ex);
			throw new AiStateUnavailableException("Failed to read fence " + runId + ": claim side fails closed", ex);
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
	 *
	 * <h2>台账不可裁剪</h2>
	 * 本方法只 {@code RPUSH}，全类<b>没有</b>对 {@code ai:ledger} 的 {@code LTRIM}/{@code LPOP}：
	 * 台账下标是 {@code ai:idem} 索引的指针，裁掉旧条目会让索引指向错记录（重放别人的结果）。
	 * 容量只由 {@code app.ai.session-ttl} 的整键过期收敛。
	 */
	public void appendLedger(String runId, PendingToolCall pending, String resultText) {
		// 续租 fencing 收口批：台账是"已执行"的跨进程判据 —— 丢租后不得追加（B 的台账才是真值），
		// 否则 A 的迟到执行会让续跑回放出一个新主人没有执行过的结果。
		// RT-21：持租但 fence 已被推进（HELD-but-fence-ahead）同样是"没有续跑权"，一并拒写。
		if (!isWritePermitted(runId, "tool execution ledger append")) {
			return;
		}
		LedgerWrite write = ledgerWrite(runId, pending, resultText);
		try {
			this.redis.execute(IDEM_PUSH_SCRIPT, List.of(ledgerKey(runId), idemKey(runId)), write.json(),
					write.idemField(), write.indexTail(), String.valueOf(ttlFor(runId).toMillis()),
					IDEM_META_FIELD_PREFIX, write.metaJson());
			// 台账键的"整额续期"仍是可观测的一次 EXPIRE（批②的滑动 TTL 契约；脚本内已有 PEXPIRE，
			// 这里只是把同一事实再落到模板面上，幂等、不在判据路径上）。
			this.redis.expire(ledgerKey(runId), ttlFor(runId));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to write execution ledger " + pending.getToolCallId(), ex);
		}
	}

	/**
	 * 成功结果的原子落账：取消条件、pending 完成态、台账与幂等索引在同一条 Lua 中推进。
	 * 返回 false 仅表示只读工具在落账前已见取消；其它条件冲突按状态不可知抛出。
	 */
	public boolean isExecutionCompletedWithLedger(String runId, PendingToolCall pending, String resultText,
			long claimedEpoch, String claimedBy, boolean respectCancel) {
		if (!isWritePermitted(runId, "tool execution completion with ledger")) {
			throw new AiStateUnavailableException("Cannot confirm resume authority; refusing to settle execution result " + pending.getToolCallId());
		}
		LedgerWrite write = ledgerWrite(runId, pending, resultText);
		String expectedClaim = claimedEpoch + "|" + claimedBy;
		try {
			List<?> result = this.redis.execute(COMPLETE_EXECUTION_WITH_LEDGER_SCRIPT,
					List.of(pendingKey(runId), claimKey(runId), fenceKey(runId), ledgerKey(runId), idemKey(runId),
							CancellationRegistry.cancelKey(runId)),
					pending.getToolCallId(), expectedClaim, this.mapper.writeValueAsString(pending),
					String.valueOf(ttlFor(runId).toMillis()), String.valueOf(claimedEpoch), write.json(),
					write.idemField(), write.indexTail(), IDEM_META_FIELD_PREFIX, write.metaJson(),
					respectCancel ? "1" : "0");
			if (result != null && result.size() >= 2 && "CANCELLED".equals(String.valueOf(result.get(1)))) {
				return false;
			}
			if (result == null || result.size() < 2 || ((Number) result.getFirst()).longValue() != 1L) {
				String outcome = result == null || result.size() < 2 ? "UNKNOWN" : String.valueOf(result.get(1));
				throw new AiStateUnavailableException("Atomic execution settlement condition mismatch " + pending.getToolCallId() + ": " + outcome);
			}
			return true;
		}
		catch (AiStateUnavailableException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new AiStateUnavailableException("Failed to atomically write execution result " + pending.getToolCallId(), ex);
		}
	}

	public boolean completeExecutionWithLedger(String runId, PendingToolCall pending, String resultText,
			long claimedEpoch, String claimedBy, boolean respectCancel) {
		return isExecutionCompletedWithLedger(runId, pending, resultText, claimedEpoch, claimedBy, respectCancel);
	}

	private record LedgerWrite(String json, String idemField, String indexTail, String metaJson) {
	}

	private LedgerWrite ledgerWrite(String runId, PendingToolCall pending, String resultText) {
		long atMs = System.currentTimeMillis();
		String toolCallId = pending.getToolCallId();
		String idemField = idemField(runId, toolCallId);
		String executedBy = ObjectUtils.defaultIfNull(pending.getExecutedBy(), this.instanceId);
		String outcome = pending.effectiveOutcome().wire();
		String code = pending.effectiveCode();
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("atMs", Long.toString(atMs));
		record.put("at", Instant.now().toString());
		record.put("executedBy", executedBy);
		record.put("toolCallId", toolCallId);
		record.put("name", pending.getName());
		record.put("kind", pending.getKind());
		record.put("outcome", outcome);
		record.put("code", code);
		record.put("preflightHash", pending.getPreflight() == null ? null
				: pending.getPreflight().get("reportHash"));
		record.put("epoch", Long.toString(pending.getClaimedEpoch() > 0L ? pending.getClaimedEpoch() : heldRunEpoch(runId)));
		record.put("idemKey", idemField);
		record.put("resultText", resultText);
		if (pending.getCreatedTaskId() != null) {
			record.put(PendingToolCall.CREATED_TASK_ID, String.valueOf(pending.getCreatedTaskId()));
		}
		try {
			String json = this.mapper.writeValueAsString(record);
			Map<String, Object> meta = new LinkedHashMap<>();
			meta.put("name", pending.getName());
			meta.put("atMs", Long.toString(atMs));
			meta.put("toolCallId", toolCallId);
			String metaJson = this.mapper.writeValueAsString(meta);
			String indexTail = outcome + "|" + StringUtils.defaultString(code) + "|" + executedBy + "|" + atMs;
			return new LedgerWrite(json, idemField, indexTail, metaJson);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to serialize execution ledger " + toolCallId, ex);
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
				throw new IllegalStateException("Failed to read execution ledger: " + item, ex);
			}
		}
		return out;
	}

	/** 该 toolCallId 是否已经真实执行完成（跨进程口径，判据 = 台账）。 */
	public Map<String, Object> ledgerFor(String runId, String toolCallId) {
		if (toolCallId == null) {
			return Map.of();
		}
		for (Map<String, Object> record : ledger(runId)) {
			if (toolCallId.equals(record.get("toolCallId"))) {
				return record;
			}
		}
		return Map.of();
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
			return Map.of();
		}
		String field = idemField(runId, toolCallId);
		String indexed;
		try {
			Object raw = this.redis.opsForHash().get(idemKey(runId), field);
			indexed = ObjectUtils.toString(raw, (String) null);
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("idempotency-index-read")) {
				log.warn("idempotency index read failed runId={} "
						+ "toolCallId={} (falling back to ledger scan)", runId, toolCallId, ex);
			}
			indexed = null;
		}
		if (indexed != null) {
			Long index = listIndexOf(indexed);
			Map<String, Object> record = index == null ? Map.of() : recordAt(runId, index);
			if (MapUtils.isNotEmpty(record) && isMatchesToolCall(record, toolCallId)) {
				return record;
			}
			if (MapUtils.isNotEmpty(record)) {
				// M-1（复审裁决）：索引指向<b>别人的</b>记录（下标错位/索引被污染）时绝不能把它当成本调用的结果 ——
				// 那会把 A 的产物交给 B（重放错了副作用结论比"没查到"更危险）。回落扫描由记录自身定身份。
				log.warn("idempotency index misaligned runId={} toolCallId={} index={} "
						+ "(index belongs to toolCallId={}; falling back to ledger scan)", runId,
						toolCallId, indexed, record.get("toolCallId"));
			}
			else if (LogSampling.canAcquire("idempotency-index-record-missing")) {
				log.warn("idempotency index hit but record not retrievable runId={} "
						+ "toolCallId={} index={} (falling back to ledger scan)", runId, toolCallId, indexed);
			}
		}
		return ledgerFor(runId, toolCallId);
	}

	public String readToolResult(String runId, String sessionId, String toolCallId, Integer offset, Integer maxChars) {
		if (StringUtils.isBlank(runId) || StringUtils.isBlank(sessionId)
				|| StringUtils.isBlank(toolCallId)) {
			throw new ConflictException(ToolOutcome.CODE_ILLEGAL_ARGUMENT, "Run, session, or tool call identifier required for result read-back is missing");
		}
		int from = ObjectUtils.defaultIfNull(offset, 0);
		int limit = ObjectUtils.defaultIfNull(maxChars, 4000);
		if (from < 0 || limit < 2 || limit > 8000) {
			throw new ConflictException(ToolOutcome.CODE_ILLEGAL_ARGUMENT,
					"Invalid page cursor: offset must be >=0 and page size must be between 2 and 8000 characters");
		}
		RequestContextUtils.require();
		try {
			return readToolResultInContext(runId, sessionId, toolCallId, from, limit);
		}
		catch (ConflictException | ResourceNotFoundException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			log.warn("read_tool_result failed runId={} toolCallId={} (returning sanitized public error)",
					runId, toolCallId, ex);
			throw new AiStateUnavailableException("Result is temporarily unreadable; retry later", ex);
		}
	}

	private String readToolResultInContext(String runId, String sessionId, String toolCallId, int from, int limit) {
		SnapshotEvidence evidence = readSnapshotEvidence(runId, toolCallId);
		SnapshotOwner owner = evidence.owner();
		if (owner.state() == SnapshotOwner.State.UNREADABLE) {
			throw new AiStateUnavailableException("Tool result ownership cannot be confirmed; retry later");
		}
		if (owner.state() != SnapshotOwner.State.ATTRIBUTED || !sessionId.equals(owner.sessionId())) {
			throw new ResourceNotFoundException("The run does not exist under the current identity or read access is denied");
		}
		ToolResultSource source = readToolResultSource(runId, toolCallId);
		String name = source.name();
		String text = source.text();
		if (Set.of("kb_search", "kb_read_doc", "kb_lookup_term").contains(name)) {
			throw new ConflictException(ToolOutcome.CODE_ILLEGAL_ARGUMENT,
					"KB results must use kb_read_doc native offset/next_offset pagination instead of generic result read-back");
		}
		if (text == null) {
			throw new ResourceNotFoundException("This tool call has no result content available for read-back");
		}
		if (!evidence.truncated()) {
			throw new ResourceNotFoundException("This result has no model-side truncation evidence and does not support read-back");
		}
		int originalChars = text.length();
		if (from > originalChars || (from > 0 && from < originalChars
				&& Character.isLowSurrogate(text.charAt(from)) && Character.isHighSurrogate(text.charAt(from - 1)))) {
			throw new ConflictException(ToolOutcome.CODE_ILLEGAL_ARGUMENT,
					"Page offset exceeds the result length or splits a surrogate pair: " + from);
		}
		int end = (int) Math.min(originalChars, (long) from + limit);
		if (end < originalChars && end > from && Character.isHighSurrogate(text.charAt(end - 1))) {
			end--;
		}
		String content = text.substring(from, end);
		Map<String, Object> page = new LinkedHashMap<>();
		page.put("toolCallId", toolCallId);
		page.put("name", name);
		page.put("offset", from);
		page.put("maxChars", limit);
		page.put("originalChars", originalChars);
		page.put("returnedChars", content.length());
		page.put("nextOffset", end < originalChars ? end : null);
		page.put("content", content);
		try {
			return this.mapper.writeValueAsString(page);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to serialize tool result page", ex);
		}
	}

	private record ToolResultSource(String name, String text) {
	}

	private ToolResultSource readToolResultSource(String runId, String toolCallId) {
		Map<String, Object> record = idemFor(runId, toolCallId);
		if (MapUtils.isNotEmpty(record)) {
			return new ToolResultSource(ObjectUtils.toString(record.get("name"), ""),
					record.get("resultText") instanceof String value ? value : null);
		}
		PendingToolCall pending = pending(runId, toolCallId);
		if (pending == null || !pending.resolved()) {
			throw new ResourceNotFoundException("No settled tool result exists in the current run");
		}
		return new ToolResultSource(StringUtils.defaultString(pending.getName()), pending.getResultText());
	}

	private record SnapshotEvidence(SnapshotOwner owner, boolean truncated) {
	}

	private record ToolResponseEvidence(String responseData) {
	}

	/** 一次原始 JSON 读取同时完成归属与截断证据校验；不物化整份 RunSnapshot。 */
	private SnapshotEvidence readSnapshotEvidence(String runId, String toolCallId) {
		String raw;
		try {
			raw = this.redis.opsForValue().get(runKey(runId));
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("Tool result ownership cannot be confirmed; retry later", ex);
		}
		if (raw == null) {
			return new SnapshotEvidence(SnapshotOwner.missing(), false);
		}
		String sessionId = null;
		boolean truncated = false;
		try (JsonParser parser = this.mapper.getFactory().createParser(raw)) {
			if (parser.nextToken() != JsonToken.START_OBJECT) {
				return new SnapshotEvidence(SnapshotOwner.unreadable(), false);
			}
			while (parser.nextToken() == JsonToken.FIELD_NAME) {
				String field = parser.currentName();
				JsonToken value = parser.nextToken();
				if ("sessionId".equals(field)) {
					if (value == JsonToken.VALUE_STRING) {
						sessionId = parser.getText();
					}
					else if (value != JsonToken.VALUE_NULL) {
						return new SnapshotEvidence(SnapshotOwner.unreadable(), false);
					}
				}
				else if ("messageJson".equals(field)) {
					truncated = isReadTruncationEvidence(parser, toolCallId);
				}
				else {
					parser.skipChildren();
				}
			}
		}
		catch (Exception ex) {
			log.warn("read_tool_result snapshot evidence unreadable runId={} toolCallId={} (fail closed)",
					runId, toolCallId, ex);
			throw new AiStateUnavailableException("Cannot confirm tool result ownership", ex);
		}
		SnapshotOwner owner = sessionId == null ? SnapshotOwner.absent() : SnapshotOwner.attributed(sessionId);
		return new SnapshotEvidence(owner, truncated);
	}

	/** 顺序流式扫描并保留最后一个目标 TOOL 响应的截断结论；任何损坏证据均失败关闭。 */
	private boolean isReadTruncationEvidence(JsonParser parser, String toolCallId) throws Exception {
		if (parser.currentToken() != JsonToken.START_ARRAY) {
			throw new IllegalStateException("messageJson must be an array");
		}
		boolean truncated = false;
		while (parser.nextToken() != JsonToken.END_ARRAY) {
			if (parser.currentToken() != JsonToken.VALUE_STRING) {
				throw new IllegalStateException("messageJson entry must be a string");
			}
			Boolean candidate = readMessageTruncationEvidence(parser.getText(), toolCallId);
			if (candidate != null) {
				truncated = candidate;
			}
		}
		return truncated;
	}

	private Boolean readMessageTruncationEvidence(String rawMessage, String toolCallId) throws Exception {
		try (JsonParser parser = this.mapper.getFactory().createParser(rawMessage)) {
			if (parser.nextToken() != JsonToken.START_OBJECT) {
				throw new IllegalStateException("messageJson entry must contain an object");
			}
			String type = null;
			ToolResponseEvidence candidate = null;
			while (parser.nextToken() == JsonToken.FIELD_NAME) {
				String field = parser.currentName();
				JsonToken value = parser.nextToken();
				if ("type".equals(field)) {
					if (value != JsonToken.VALUE_STRING) {
						throw new IllegalStateException("message type must be a string");
					}
					type = parser.getText();
				}
				else if ("toolResponses".equals(field)) {
					if (org.springframework.ai.chat.messages.MessageType.TOOL.name().equals(type)) {
						candidate = readToolResponseEvidence(parser, toolCallId);
					}
					else {
						parser.skipChildren();
					}
				}
				else {
					parser.skipChildren();
				}
			}
			if (!org.springframework.ai.chat.messages.MessageType.TOOL.name().equals(type) || candidate == null) {
				return null;
			}
			return isReadEnvelopeTruncated(candidate.responseData());
		}
	}

	private ToolResponseEvidence readToolResponseEvidence(JsonParser parser, String toolCallId) throws Exception {
		if (parser.currentToken() != JsonToken.START_ARRAY) {
			throw new IllegalStateException("toolResponses must be an array");
		}
		ToolResponseEvidence candidate = null;
		while (parser.nextToken() != JsonToken.END_ARRAY) {
			if (parser.currentToken() != JsonToken.START_OBJECT) {
				throw new IllegalStateException("tool response must be an object");
			}
			if (candidate != null) {
				parser.skipChildren();
				continue;
			}
			boolean matches = false;
			String responseData = null;
			while (parser.nextToken() == JsonToken.FIELD_NAME) {
				String field = parser.currentName();
				JsonToken value = parser.nextToken();
				if ("id".equals(field)) {
					if (value != JsonToken.VALUE_STRING) {
						throw new IllegalStateException("tool response id must be a string");
					}
					matches = toolCallId.equals(parser.getText());
				}
				else if ("responseData".equals(field) && matches) {
					if (value == JsonToken.VALUE_STRING) {
						responseData = parser.getText();
					}
					else if (value != JsonToken.VALUE_NULL) {
						throw new IllegalStateException("tool response data must be a string");
					}
				}
				else {
					parser.skipChildren();
				}
			}
			if (matches) {
				candidate = new ToolResponseEvidence(responseData);
			}
		}
		return candidate;
	}

	private boolean isReadEnvelopeTruncated(String responseData) throws Exception {
		String json = ToolResultEnvelope.unwrapModelJson(responseData);
		if (StringUtils.isBlank(json)) {
			return false;
		}
		try (JsonParser parser = this.mapper.getFactory().createParser(json)) {
			if (parser.nextToken() != JsonToken.START_OBJECT) {
				throw new IllegalStateException("tool result envelope must be an object");
			}
			boolean truncated = false;
			while (parser.nextToken() == JsonToken.FIELD_NAME) {
				String field = parser.currentName();
				JsonToken value = parser.nextToken();
				if ("truncated".equals(field)) {
					if (value == JsonToken.VALUE_TRUE) {
						truncated = true;
					}
					else if (value == JsonToken.VALUE_FALSE) {
						truncated = false;
					}
					else {
						return false;
					}
				}
				else {
					parser.skipChildren();
				}
			}
			return truncated;
		}
	}

	/** 索引指向的记录是否<b>就是</b>请求的 {@code toolCallId}（错位/字段缺失一律 false ⇒ 回落扫描）。 */
	private static boolean isMatchesToolCall(Map<String, Object> record, String toolCallId) {
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

	/** 台账第 {@code index} 条（越界/读不出来返回空 Map）。 */
	private Map<String, Object> recordAt(String runId, long index) {
		if (index < 0L) {
			return Map.of();
		}
		try {
			String item = this.redis.opsForList().index(ledgerKey(runId), index);
			if (item == null) {
				return Map.of();
			}
			return this.mapper.readValue(item, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("ledger-read-by-index")) {
				log.warn("ledger read by index failed runId={} index={}", runId, index, ex);
			}
			return Map.of();
		}
	}

	/**
	 * 工具名 → 执行次数（"恰好一次"的对账口径）。
	 *
	 * <p><b>读投影而不是读正文</b>（C65）：正常路径只读 {@code ai:idem} 里的
	 * {@link #IDEM_META_FIELD_PREFIX 元数据投影}（{@code name/atMs/toolCallId}）与台账长度，
	 * <b>不把整份 {@code ai:ledger}（含 resultText 正文）拉过网络、也不重复解析</b>。
	 *
	 * <p><b>回落</b>：旧数据（本投影上线前写入）或投影不完整（下标不连续 / 条数与 {@code LLEN} 对不上 /
	 * 值解析失败）时，按旧口径扫描 {@link #ledger(String)}。回落判据是"投影必须逐下标覆盖整份台账"，
	 * 因此<b>旧条目与新条目混排也不会少计</b>；项目未上线，不做数据迁移。
	 *
	 * <p><b>故障分级</b>：连接/超时等数据访问资源故障<b>直接抛出</b>，不回落 —— 回落只会把同一次故障
	 * 再等一遍（见 {@link #ledgerMetadata}）。
	 */
	public Map<String, Integer> ledgerCounts(String runId) {
		LedgerMetadataRead metadata = ledgerMetadata(runId);
		return ledgerCountsOf(metadata.available() ? metadata.records() : ledger(runId));
	}

	/** 已读出的台账/投影记录按 {@code name} 计数（同一份记录只解析一次，例如 {@code describe}）。 */
	public static Map<String, Integer> ledgerCountsOf(List<Map<String, Object>> records) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Map<String, Object> record : records) {
			out.merge(String.valueOf(record.get("name")), 1, Integer::sum);
		}
		return out;
	}

	/**
	 * 自 {@code sinceMs} 起新增的台账条数（韧性棒的重试副作用判定口径：<b>本次尝试</b>内是否有
	 * 真实执行完成）。用"执行完成时刻"（{@code atMs}）而非认领时刻，是因为"执行已完成"才是
	 * 不可撤销的副作用；仍在执行中的窗口由 {@link #claimedBy} 与
	 * {@code ToolActivityBeacon} 覆盖。
	 *
	 * <p>与 {@link #ledgerCounts(String)} 同一投影/回落口径：正常路径只读小型元数据投影，
	 * 不传输 {@code resultText}；旧数据或不完整投影回落台账全表扫描，连接/超时等资源故障直接抛出。
	 */
	public int ledgerCountSince(String runId, long sinceMs) {
		LedgerMetadataRead metadata = ledgerMetadata(runId);
		return countLedgerSince(metadata.available() ? metadata.records() : ledger(runId), sinceMs);
	}

	private static int countLedgerSince(List<Map<String, Object>> records, long sinceMs) {
		int count = 0;
		for (Map<String, Object> record : records) {
			Object atMs = record.get("atMs");
			if (NumberUtils.toLong(String.valueOf(atMs), Long.MIN_VALUE) >= sinceMs) {
				count++;
			}
		}
		return count;
	}

	/**
	 * 读取台账的<b>小型元数据投影</b>（{@code ai:idem} 里的 {@code ~meta:<index>} 字段）。
	 *
	 * <p><b>故障分级（C65 返修）</b>：只有<b>数据形态异常</b>（旧数据没有投影 / 投影混排 / 值坏 /
	 * 下标错位 / 键类型不对）才以 {@code available=false} 让调用方回落台账扫描；<b>数据访问资源故障</b>
	 * （连接不可用、命令超时，可能被 {@code RedisSystemException} 等包装）<b>直接抛出</b>。
	 * 理由：回落 {@code LRANGE 0 -1} 并不能修复"Redis 读不到"，只会把同一次连接/超时故障
	 * 再等一遍（{@code LLEN} 等一个 {@code spring.data.redis.timeout}，{@code LRANGE} 再等一个），
	 * 把请求线程或启动扫描线程的等待时长翻倍。
	 *
	 * <p><b>投影不完整的留痕</b>：每个回落点只打一条 {@code DEBUG}（含原因），
	 * <b>不新增按 runId 的指标</b> —— "这批记录本来就旧/被外部改坏"是一次性事实，
	 * 按 run 记账会变成无界表。
	 *
	 * @return 显式投影结果；{@code available=false} 才由调用方回落台账扫描。{@code byIndex} 始终非空，
	 *         空 Map 只表示真实空投影（旧数据没有投影 / 投影混排 / 值坏 / 下标错位时不可用）。
	 * @throws RuntimeException 连接/超时等数据访问资源故障（原样上抛，不回落）
	 */
	private ProjectionRead readProjectionByIndex(String runId, Map<Object, Object> entries) {
		Map<Long, Map<String, Object>> byIndex = new TreeMap<>();
		for (Map.Entry<Object, Object> entry : entries.entrySet()) {
			String field = ObjectUtils.toString(entry.getKey(), "");
			// 索引自身的 field（<runId>:<toolCallId>）不参与投影；UUID runId 不会以 '~' 开头。
			if (!field.startsWith(IDEM_META_FIELD_PREFIX)) {
				continue;
			}
			long index;
			try {
				index = Long.parseLong(field.substring(IDEM_META_FIELD_PREFIX.length()));
			}
			catch (NumberFormatException ex) {
				incompleteProjection(runId, "Projection field index is not an integer: " + field);
				return ProjectionRead.unavailable();
			}
			Object raw = entry.getValue();
			if (raw == null) {
				incompleteProjection(runId, "Projection field value is null: " + field);
				return ProjectionRead.unavailable();
			}
			Map<String, Object> meta;
			try {
				meta = this.mapper.readValue(String.valueOf(raw), new TypeReference<Map<String, Object>>() {
				});
			}
			catch (Exception ex) {
				incompleteProjection(runId, "Projection value is not valid JSON: " + field);
				return ProjectionRead.unavailable();
			}
			if (meta.get("atMs") == null || meta.get("toolCallId") == null) {
				incompleteProjection(runId, "Projection lacks atMs/toolCallId: " + field);
				return ProjectionRead.unavailable();
			}
			byIndex.put(index, meta);
		}
		return new ProjectionRead(true, byIndex);
	}

	private record ProjectionRead(boolean available, Map<Long, Map<String, Object>> byIndex) {
		private ProjectionRead {
			byIndex = byIndex == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(byIndex));
		}

		private static ProjectionRead unavailable() {
			return new ProjectionRead(false, Map.of());
		}
	}

	private LedgerMetadataRead ledgerMetadata(String runId) {
		long size;
		try {
			Long rawSize = this.redis.opsForList().size(ledgerKey(runId));
			if (rawSize == null) {
				return incompleteProjection(runId, "LLEN returned null");
			}
			size = rawSize;
		}
		catch (RuntimeException ex) {
			rethrowIfResourceFailure(ex);
			return incompleteProjection(runId, "Failed to read ledger length", ex);
		}
		if (size == 0L) {
			return new LedgerMetadataRead(true, List.of());
		}
		if (size > Integer.MAX_VALUE) {
			return incompleteProjection(runId, "Ledger size exceeds projection range: " + size);
		}
		Map<Object, Object> entries;
		try {
			entries = this.redis.opsForHash().entries(idemKey(runId));
		}
		catch (RuntimeException ex) {
			rethrowIfResourceFailure(ex);
			return incompleteProjection(runId, "Failed to read projection HASH", ex);
		}
		if (MapUtils.isEmpty(entries)) {
			return incompleteProjection(runId, "Projection fields are absent (legacy data)");
		}
		ProjectionRead projection = readProjectionByIndex(runId, entries);
		if (!projection.available()) {
			return incompleteProjection(runId, "Projection fields are invalid");
		}
		Map<Long, Map<String, Object>> byIndex = projection.byIndex();
		if (byIndex.size() != (int) size) {
			return incompleteProjection(runId, "Projection field count " + byIndex.size() + " != ledger length " + size);
		}
		long expected = 0L;
		List<Map<String, Object>> out = new ArrayList<>((int) size);
		for (Map.Entry<Long, Map<String, Object>> entry : byIndex.entrySet()) {
			if (entry.getKey().longValue() != expected) {
				return incompleteProjection(runId, "Projection indexes are not contiguous (missing " + expected + ")");
			}
			out.add(entry.getValue());
			expected++;
		}
		return new LedgerMetadataRead(true, out);
	}

	private record LedgerMetadataRead(boolean available, List<Map<String, Object>> records) {
		private LedgerMetadataRead {
			records = records == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(records));
		}

		private static LedgerMetadataRead unavailable() {
			return new LedgerMetadataRead(false, List.of());
		}
	}

	/**
	 * 台账投影读取的故障分级：数据访问资源故障（连接不可用 / 命令超时）<b>原样上抛</b>；
	 * 其余（数据形态异常）由调用方回落全表扫描。
	 *
	 * <p>判据复用 {@link #isConnectionFailure}（沿 cause 链识别，兼容 {@code RedisSystemException}
	 * 包装）—— 与同槽续期"连接类失败立即收手"同一口径。
	 */
	private static void rethrowIfResourceFailure(RuntimeException ex) {
		if (isConnectionFailure(ex)) {
			throw ex;
		}
	}

	/**
	 * 投影不可用（数据形态异常）⇒ {@code available=false} 触发回落，并留一条 {@code DEBUG} 原因。
	 *
	 * <p>只打日志、不记账：这条路径的事实是"该 run 的投影不完整"，按 run 记指标会无界增长。
	 */
	private static LedgerMetadataRead incompleteProjection(String runId, String reason) {
		if (log.isDebugEnabled()) log.debug("ledger metadata projection incomplete runId={} ({}; falling back to full scan)", runId, reason);
		return LedgerMetadataRead.unavailable();
	}

	private static LedgerMetadataRead incompleteProjection(String runId, String reason, Throwable error) {
		if (log.isDebugEnabled()) log.debug("ledger metadata projection incomplete runId={} ({}; falling back to full scan)", runId, reason, error);
		return LedgerMetadataRead.unavailable();
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
		for (PendingToolCall pending : pendingsLenient(runId)) {
			if (pending == null || (!PendingToolCall.PENDING.equals(pending.getStatus())
					&& !PendingToolCall.APPROVED.equals(pending.getStatus()))) {
				continue;
			}
			pending.setStatus(PendingToolCall.CANCELLED);
			pending.setOutcome(com.example.configmgr.ai.tool.ToolOutcome.CANCELLED.wire());
			pending.setCode(com.example.configmgr.ai.tool.ToolOutcome.CODE_RUN_CANCELLED);
			pending.setReason("RUN_CANCELLED");
			pending.setExecuted(false);
			pending.setResolvedAtMs(System.currentTimeMillis());
			pending.setResultText("本轮对话已被用户取消，该工具未执行。");
			if (cancelPending(runId, pending)) {
				changed++;
			}
		}
		return changed;
	}

	/** 条件取消一条 pending；当前状态、guard、claim、epoch 与执行证据在 Lua 内复核。 */
	public boolean cancelPending(String runId, PendingToolCall pending) {
		String decided = PendingToolCall.CANCELLED + "|" + System.currentTimeMillis() + "|" + this.instanceId;
		try {
			List<?> result = this.redis.execute(CANCEL_PENDING_SCRIPT,
					List.of(pendingKey(runId), decidedKey(runId), claimKey(runId), fenceKey(runId)),
					pending.getToolCallId(), this.mapper.writeValueAsString(pending), decided,
					String.valueOf(ttlFor(runId).toMillis()), String.valueOf(heldRunEpoch(runId)));
			return CollectionUtils.isNotEmpty(result) && ((Number) result.getFirst()).longValue() == 1L;
		}
		catch (Exception ex) {
			throw new AiStateUnavailableException("Failed to conditionally cancel pending entry " + pending.getToolCallId(), ex);
		}
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
			log.warn("claim table read failed, treating as existing execution evidence runId={} toolCallId={}", runId,
					pending.getToolCallId(), ex);
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
		if (!PendingToolCall.PENDING.equals(pending.getStatus())
				&& !PendingToolCall.APPROVED.equals(pending.getStatus())) {
			return false;
		}
		return !hasExecutionEvidence(runId, pending);
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
			return Long.parseLong(ObjectUtils.defaultIfNull(raw, "0"));
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("issued-seq-read")) {
				log.warn("issued seq read failed runId={}", runId, ex);
			}
			return 0L;
		}
	}

	/**
	 * 把 {@code ai:seq:<runId>} 的下界<b>抬到</b> {@code floor}（只升不降；失败只 WARN）。
	 *
	 * <h2>为什么需要它</h2>
	 * 快照与序号键可能同时丢失（键被清 / 同槽整族过期）。此时恢复路径拿到的信封里带着"本实例已处置到的
	 * 最大 seq"（{@code seqFloor}）。若只恢复快照而不抬 {@code ai:seq}，下一帧会从 1 重新发号，
	 * 前端带 {@code lastSeq} 重挂时会把新帧当孤儿丢掉（确认卡正好在这批帧里 ⇒ 卡消失）。
	 *
	 * <p>单键 Lua 做"比较 + 只升 + 续期"：并发的更大号不会被回拨；已有更大号时只续期。
	 * 写权限走 {@link #isWritePermitted(String, String)}（丢租/换主后不得再推进号空间）。
	 * 帧存储的下一号由 {@link RedisFrameStore} 的 {@code INCR ai:seq} 给出，
	 * 因此抬这一个键即可覆盖当前取号路径。
	 *
	 * @param floor 下界（{@code <= 0} = 无下界，直接返回）
	 */
	public void ensureIssuedSeqAtLeast(String runId, long floor) {
		if (runId == null || floor <= 0L) {
			return;
		}
		if (!isWritePermitted(runId, "business seq floor restore")) {
			return;
		}
		try {
			this.redis.execute(RAISE_SEQ_SCRIPT, List.of(seqKey(runId)), String.valueOf(floor),
					String.valueOf(ttlFor(runId).toMillis()));
		}
		catch (Exception ex) {
			log.warn("business seq floor restore failed runId={} floor={}", runId, floor, ex);
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
			if (LogSampling.canAcquire("heartbeat-activity-stamp-write")) {
				log.warn("heartbeat activity stamp write failed runId={}", runId, ex);
			}
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
			if (LogSampling.canAcquire("business-activity-anchor-write")) {
				log.warn("business activity anchor write failed runId={} atMs={}", runId, atMs, ex);
			}
		}
	}

	/** 心跳活动戳键名（诊断/取证可直读）。带身份段（单键命令，无 tag）。 */
	public String beatKey(String runId) {
		return TenantKeyScope.scoped(BEAT_PREFIX, runId);
	}

	/**
	 * <b>轮内预算 armed 的持久化</b>（AR-10 第 3 条；批次2）。
	 *
	 * <p>armed 原先只活在 {@code ToolActivityBeacon}（进程内加速态）：park 退出与续跑入口都会清信标，
	 * 于是"挂起后恢复 = 预算重新开始"，与"本轮预算"（步数类预算一律<b>继续累计</b>、绝不重置）的
	 * 口径相反。这里把两个事实写进<b>已有的</b>轮次快照（同一键族、同一 TTL、不新增键、不碰 DB）：
	 * <ul>
	 * <li>{@code budgetArmedAtMs}：武装时刻（0 = 未武装）；</li>
	 * <li>{@code budgetSkippedBatches}：武装后被拦下的工具批次数（到顶仍按既有上界收尾）。</li>
	 * </ul>
	 *
	 * <p><b>只升不降</b>：{@code Math.max(现值, 新值)} —— 并发/迟到写入不得把已武装改回未武装，
	 * 也不得把已拦下的批次数改小（那等于给模型"再来几批"的额度）。
	 *
	 * <p><b>失败姿态</b>：写不进去只 WARN，不打断工具循环。代价是恢复后按真值/估算重判一次预算
	 * （最坏情况是<b>多判一次</b>，不是漏判），与信标类注"不承载可恢复状态"的原口径一致 ——
	 * 这条持久化只是在能写时把"本轮预算"钉住。
	 */
	public void markTurnBudget(String runId, long armedAtMs, int skippedBatches) {
		if (runId == null) {
			return;
		}
		try {
			RunSnapshot snapshot = get(runId);
			if (snapshot == null || RunSnapshot.isTerminal(snapshot.getStatus())) {
				return;
			}
			int currentSkipped = snapshot.getBudgetSkippedBatches();
			long currentArmed = snapshot.getBudgetArmedAtMs();
			long nextArmed = Math.max(currentArmed, armedAtMs);
			int nextSkipped = Math.max(currentSkipped, skippedBatches);
			if (nextArmed == currentArmed && nextSkipped == currentSkipped) {
				return;
			}
			snapshot.setBudgetArmedAtMs(nextArmed);
			snapshot.setBudgetSkippedBatches(nextSkipped);
			isPersist(snapshot);
		}
		catch (RuntimeException ex) {
			log.warn("in-turn budget armed snapshot write failed runId={} "
					+ "armedAtMs={} skippedBatches={} (tool loop not interrupted)", runId,
					armedAtMs, skippedBatches, ex);
		}
	}

	/**
	 * 挂起数据自有 TTL 面：轮快照、轻量状态、待决条目、事件日志和已发放序号。
	 * 仅供明确用户读侧的兼容续期路径使用；ConfirmGate 后台心跳不得调用本方法续期 24 小时会话数据。
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
			if (LogSampling.canAcquire("suspended-renew-key-build")) {
				log.warn("suspended renew key build/count failed runId={}", runId, ex);
			}
		}
	}

	/**
	 * 按登记表续期一个续期面（超时面治理批① 第 11 项）：键清单来自
	 * {@link SessionKeyRegistry#renewalKeys(SessionKeyRegistry.Renewal)}，本方法只负责发到期时间。
	 *
	 * <p>Redis {@code EXPIRE} 对不存在的键是无操作（不报错），故"该键还没写出"不构成错误。
	 * <b>L-139：逐键隔离</b> —— 一个键失败（Cluster 下 MOVED/TRYAGAIN、单节点故障）不得让排在
	 * 后面的键全部不续期；每次失败计入 {@link RedisDegradation#L139_RENEW_KEY}。
	 *
	 * <p><b>RTT 收口（Cluster 安全）</b>：同槽的键合成一条 {@link #RENEW_SAME_SLOT_SCRIPT}。
	 * 分组键 = hash tag；取消标志与工具认领共用 run tag，可与同轮其它键合批。不带 tag 的键仍各自成组，
	 * 绝不混进同一条多键脚本 —— 那正是 Cluster 的 {@code CROSSSLOT}。脚本不可用时退回逐键 {@code EXPIRE}。
	 *
	 * <p><b>I-3：连接类失败立即收手。</b>连接挂死时每次 Redis 往返都要等到
	 * {@code spring.data.redis.timeout}（2s）；分组后最坏是「组数 × 超时」（心跳面 1 组、
	 * 读取面 2 组），不分组则 10 个键 = 20s，会把确认门心跳和请求线程拖垮。因此识别到连接类异常
	 * （{@link DataAccessResourceFailureException} / {@link QueryTimeoutException}）后不再尝试，
	 * 剩余键计入 {@link RedisDegradation#L139_RENEW_NOT_ATTEMPTED}；其它单键异常仍继续。
	 */
	private void renew(String runId, Duration ttl, SessionKeyRegistry.Renewal renewal) {
		if (this.redis == null) {
			// 进程内替身（InMemoryRunStore）：没有可续期的外置键
			return;
		}
		List<java.util.function.Function<String, String>> builders = SessionKeyRegistry.renewalKeys(renewal);
		if (builders.isEmpty()) {
			return;
		}
		List<String> keys = new ArrayList<>(builders.size());
		for (java.util.function.Function<String, String> builder : builders) {
			keys.add(builder.apply(runId));
		}
		renewKeyGroups(runId, ttl, keys);
	}

	private void renewKeyGroups(String runId, Duration ttl, List<String> keys) {
		int total = keys.size();
		int attempted = 0;
		for (List<String> group : groupBySlot(keys)) {
			if (group.size() > 1) {
				try {
					if (isRenewSameSlot(group, ttl)) {
						attempted += group.size();
						continue;
					}
				}
				catch (RuntimeException ex) {
					// 脚本里的连接类失败：与逐键路径同口径 —— 记一次键失败，剩余键不再逐个等超时。
					// 未尝试计数要按<b>整组</b>算：脚本是一条命令，失败即本组一个键都没尝试成功，
					// 因此未尝试 = 剩余全部键（含本组），不是"剩余减一"（那只对逐键路径成立）。
					RedisDegradation.record(RedisDegradation.L139_RENEW_KEY, ex);
					int notAttempted = total - attempted;
					recordNotAttempted(notAttempted);
					if (LogSampling.canAcquire("same-slot-renew-connection")) {
						log.warn("same-slot renew hit a connection error runId={} keys={} (skipping timeout waits for the remaining {} keys)", runId,
								group.size(), notAttempted, ex);
					}
					return;
				}
			}
			for (int i = 0; i < group.size(); i++) {
				String key = group.get(i);
				try {
					this.redis.expire(key, ttl);
				}
				catch (Exception ex) {
					// 单键失败只影响该键；其余键照常续期（长挂起卡的后台数据不能因一次抖动整面过期）
					RedisDegradation.record(RedisDegradation.L139_RENEW_KEY, ex);
					if (isConnectionFailure(ex)) {
						recordNotAttempted(total - attempted - 1);
						if (LogSampling.canAcquire("renew-key-connection")) {
							log.warn("renew hit a connection error runId={} key={} (skipping "
									+ "individual timeout waits for the remaining {} keys)", runId, key,
									total - attempted - 1, ex);
						}
						return;
					}
					if (LogSampling.canAcquire("renew-key-failure")) {
						log.warn("renew key failed runId={} key={} (continuing with other keys)", runId, key, ex);
					}
				}
				attempted++;
			}
		}
	}

	/**
	 * 按 hash tag 把键分组（同 tag = 同槽，可合进一条多键脚本）。
	 *
	 * <p><b>不带 tag 的键各自成组</b>：Cluster 按整键 CRC16 分槽，两个无 tag 的键是否同槽不可预知；
	 * 把整键当组名（而不是"无 tag 归一组"）保证它们永远走单键命令 —— 宁可多一次往返，
	 * 也不要一条可能被 {@code CROSSSLOT} 整条拒绝的脚本。组顺序 = 首次出现顺序（与登记表一致）。
	 */
	private static List<List<String>> groupBySlot(List<String> keys) {
		Map<String, List<String>> groups = new LinkedHashMap<>();
		for (String key : keys) {
			groups.computeIfAbsent(slotOf(key), ignored -> new ArrayList<>()).add(key);
		}
		return new ArrayList<>(groups.values());
	}

	/** 键的槽标识：有 {@code {...}} hash tag 取 tag，否则取整键（⇒ 无 tag 的键恒自成一槽）。 */
	private static String slotOf(String key) {
		int open = key.indexOf('{');
		if (open < 0) {
			return key;
		}
		int close = key.indexOf('}', open + 1);
		return close <= open + 1 ? key : key.substring(open + 1, close);
	}

	/**
	 * 同槽批量续期（一条脚本 = 一次往返）。
	 *
	 * @return {@code true} = 脚本已执行（逐位结果已按 L-139 计数）；{@code false} = 脚本面不可用，
	 *         调用方退回逐键 {@code EXPIRE}（语义不变，只多 RTT）
	 * @throws RuntimeException 连接类失败（调用方据此立即收手）；其它异常已折成"不可用"
	 */
	private boolean isRenewSameSlot(List<String> keys, Duration ttl) {
		String statuses;
		try {
			statuses = this.redis.execute(RENEW_SAME_SLOT_SCRIPT, keys, String.valueOf(ttl.toMillis()));
		}
		catch (RuntimeException ex) {
			if (isConnectionFailure(ex)) {
				throw ex;
			}
			if (LogSampling.canAcquire("same-slot-renew-script-unavailable")) {
				log.warn("same-slot renew script unavailable (falling back to per-key EXPIRE) keys={}", keys.size(), ex);
			}
			return false;
		}
		if (statuses == null) {
			// 替身/包装层没有真的执行脚本（返回 null）：退回逐键，保持原有可观测形态
			return false;
		}
		String[] parts = statuses.split(",", -1);
		if (parts.length != keys.size()) {
			return false;
		}
		for (int i = 0; i < parts.length; i++) {
			if ("-1".equals(parts[i].trim())) {
				// 单键失败已在脚本内隔离（pcall）：只计数，其余键的续期不受影响
				RedisDegradation.record(RedisDegradation.L139_RENEW_KEY);
				if (LogSampling.canAcquire("renew-key-isolated-failure")) {
					log.warn("renew key failed (isolated within same-slot batch) key={}", keys.get(i));
				}
			}
		}
		return true;
	}

	/** 连接收手后把剩余键计为"未尝试"（与 L-139 的逐键口径一致：不静默丢失）。 */
	private static void recordNotAttempted(int skipped) {
		for (int i = 0; i < skipped; i++) {
			RedisDegradation.record(RedisDegradation.L139_RENEW_NOT_ATTEMPTED);
		}
	}

	// ── 挂起态的读侧续期唯一入口（B23L1 批②a / 方案第 10 条） ────────────────

	/**
	 * 明确用户读/决策入口的续期方法（设计稿 §2.2 "挂起态 TTL 续期改由读侧触发"）。
	 * 后台恢复扫描、压缩与心跳不得调用本方法冒充用户活动。
	 *
	 * <p>批②b 起挂起线程不再被假设一定活着（挂起在 A、读取/决策落在 B），因此"谁养着卡的后台数据"
	 * 从"挂起线程的心跳"改为"任何实例的用户读/决策都 touch 一次"。本方法自带<b>终态判据</b>：
	 * 快照不存在或已终态 ⇒ <b>不续期</b>（读侧不得把已收尾/已删除的轮重新养出孤儿键），返回 false。
	 *
	 * <p>续期面 = 心跳同款的五类键（{@link #renewSuspensionTtl}，本体不动）+ session → current 索引
	 * + 本批新增的两个契约位键（{@code ai:req} / {@code ai:decided}）：req 位若先于待决条目过期，
	 * 刷新恢复会拿不到卡（L0 修复的那个洞会换个形式复发）。
	 *
	 * @return true = 已续期或处于有效节流窗口；false = 缺失/终态或续期失败
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
			if (LogSampling.canAcquire("read-side-renew-snapshot")) {
				log.warn("read-side renew snapshot missing runId={}", runId, ex);
			}
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
		if (this.activityRenewal != null) {
			SessionActivityRenewal.Result result = this.activityRenewal.renewActivity(snapshot.getTenantId(),
					snapshot.getUserId(), snapshot.getSessionId(), runId,
					runScopedKey(RUN_PREFIX, snapshot.getTenantId(), snapshot.getUserId(), runId));
			if (result == SessionActivityRenewal.Result.FAILED) {
				return false;
			}
			if (RunSnapshot.SUSPENDED.equals(snapshot.getStatus())) {
				touchBusinessActivity(runId, System.currentTimeMillis());
			}
			return true;
		}
		renewSuspensionTtl(runId);
		renewSessionCurrent(snapshot.getSessionId(), runId);
		// 会话→run 索引（销毁发现面）也随用户读侧 touch 续期：只续 session:current 不够 —— 索引才是
		// 枚举入口，先过期就会让"卡还在、销毁时看不见这一轮"。身份取记录身份。
		renewSessionRunsIndex(snapshot.getSessionId(), runId, snapshot.getTenantId(), snapshot.getUserId());
		Duration ttl = ttlFor(runId);
		// 停止闭环：clientRequestId 映射与取消墓碑与会话同寿（单键 EXPIRE；身份取记录身份）。
		if (StringUtils.isNotBlank(snapshot.getSessionId())) {
			try {
				this.redis.expire(TenantKeyScope.scoped(ClientRequestRegistry.KEY_PREFIX, snapshot.getTenantId(),
						snapshot.getUserId(), snapshot.getSessionId()), ttl);
			}
			catch (RuntimeException ex) {
				if (LogSampling.canAcquire("stop-closure-mapping-renew")) {
					log.warn("stop-closure mapping renew failed runId={} (read-side renew unaffected)", runId, ex);
				}
			}
		}
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
			return new DecisionClaim(false, ObjectUtils.toString(current, (String) null));
		}
		catch (Exception ex) {
			throw new AiStateUnavailableException("Failed to write decision guard " + toolCallId, ex);
		}
	}

	/** 原子写入决策 guard 与完整条目；原条目必须仍为 PENDING 且没有执行证据。 */
	public DecisionClaim tryApplyDecision(String runId, PendingToolCall pending) {
		return applyDecision(runId, pending, "");
	}

	/** 仅修补旧版遗留的同值 guard 半状态；正常新决策不走此口。 */
	public DecisionClaim tryRepairDecision(String runId, PendingToolCall pending, String expectedGuard) {
		if (StringUtils.isBlank(expectedGuard)) {
			return new DecisionClaim(false, null);
		}
		return applyDecision(runId, pending, expectedGuard);
	}

	private DecisionClaim applyDecision(String runId, PendingToolCall pending, String expectedGuard) {
		String value = pending.getStatus() + "|" + System.currentTimeMillis() + "|" + this.instanceId;
		try {
			List<?> result = this.redis.execute(DECIDE_PENDING_SCRIPT,
					List.of(pendingKey(runId), decidedKey(runId), claimKey(runId)), pending.getToolCallId(),
					this.mapper.writeValueAsString(pending), value, String.valueOf(ttlFor(runId).toMillis()),
					expectedGuard);
			if (result == null || result.size() < 2) {
				throw new AiStateUnavailableException("Decision script returned null " + pending.getToolCallId());
			}
			String recorded = String.valueOf(result.get(1));
			return new DecisionClaim(((Number) result.getFirst()).longValue() == 1L,
					recorded.isEmpty() ? null : recorded);
		}
		catch (AiStateUnavailableException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new AiStateUnavailableException("Failed to atomically write decision " + pending.getToolCallId(), ex);
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
			return ObjectUtils.toString(value, (String) null);
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.A6_DECISION_GUARD_READ, ex);
			log.warn("decision guard read failed runId={} toolCallId={} (fail-closed: must not treat as undecided)", runId,
					toolCallId, ex);
			throw new AiStateUnavailableException("Failed to read decision guard " + toolCallId, ex);
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
	 * 取值 = 两方的<b>较新者</b>：
	 * <ol>
	 * <li>{@code ai:activity:<runId>} —— 出帧点（{@code SseChatEmitter#emit}）直接写入的活动锚点。
	 * 即使事件日志落档失败（Redis 部分可用），只要帧发出去了，这里仍有证据；</li>
	 * <li>事件日志 Stream {@code ai:xevents:<runId>} 尾部 {@value #ACTIVITY_TAIL_PROBE} 帧的
	 * {@code atMs} —— B23L1 批① 起的生产写口，于是<b>重连/唤醒/跨实例</b>都能看到新业务帧。</li>
	 * </ol>
	 *
	 * <p>
	 * <b>心跳不参与</b>：心跳只刷新 {@link #lastBeatAtMs(String)}。把心跳算成业务活动会让
	 * "进程还在心跳、业务已经卡死"的轮永远年轻，僵尸接管失效（心跳掩盖卡死）。
	 *
	 * <p>
	 * 尾部取多条而不是只取最后一条，是为了容忍轻微钟漂与并发落档顺序。
	 * 两者都没有（或帧没有 atMs）返回 0，调用方据此回落到快照的 {@code updatedAtMs}。
	 *
	 * <p>
	 * <b>时钟域（M-2）</b>：{@code atMs} 是出帧节点的墙钟，判死用的是本节点 {@code now}。
	 * 跨节点负偏会让活轮提前"超龄"，因此多实例部署必须保证 NTP 同步，偏差应远小于
	 * {@code app.ai.resume.zombie-margin}。
	 */
	public long lastActivityAtMs(String runId) {
		return Math.max(activityAnchorAtMs(runId), streamActivityTailAtMs(runId));
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

	/** 业务活动锚点键名（{@code ai:activity:<tenantId>:<userId>:<runId>}；诊断/取证可直读）。 */
	public String activityKey(String runId) {
		return TenantKeyScope.scoped(ACTIVITY_PREFIX, runId);
	}

	/** {@code ai:activity} 的当前值（无键/坏值 0）。 */
	private long activityAnchorAtMs(String runId) {
		try {
			String raw = this.redis.opsForValue().get(activityKey(runId));
			return Long.parseLong(ObjectUtils.defaultIfNull(raw, "0"));
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("business-activity-anchor-read")) {
				log.warn("business activity anchor read failed runId={}", runId, ex);
			}
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
				if (CollectionUtils.isEmpty(records)) {
					return 0L;
				}
				long result = 0L;
				for (ByteRecord record : records) {
					result = Math.max(result, businessFrameAtMs(frameFieldOf(record)));
				}
				return result;
			});
			return ObjectUtils.defaultIfNull(max, 0L);
		}
		catch (Exception ex) {
			// 读失败 ≠ 没有活动：调用方还会看业务活动锚点键，这里按 0 容错。
			if (LogSampling.canAcquire("event-log-tail-activity-read")) {
				log.warn("event log tail activity time read failed runId={}", runId, ex);
			}
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
			if ("frame".equals(org.apache.commons.codec.binary.StringUtils.newStringUtf8(entry.getKey()))) {
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
			return Long.parseLong(ObjectUtils.defaultIfNull(raw, "0"));
		}
		catch (Exception ex) {
			return 0L;
		}
	}


	// ── TTL（滑动续期：活跃即续，无绝对上限） ─────────────────────────────────────

	/**
	 * 该轮运行态键（{@code ai:run:<runId>}）的<b>权威剩余毫秒</b>（AR-10：24h 只可见化）。
	 *
	 * <p>
	 * <b>只读</b>：{@code PTTL} 不改该键、不续期、不延长、不缩短 —— AR-10 第 2 条禁止"顺手加续期"。
	 * 值直接取自 Redis 对本键的到期判定，因此前端展示剩余时长时<b>不必</b>再用
	 * {@code app.ai.session-ttl} 常量自算另一套时间（"两套到期时间"是明令禁止的口径）。
	 *
	 * <p>
	 * 只问快照键：它与会话/待决条目/归档同寿，是"这一轮还在不在"的寿命锚点。
	 *
	 * @return 剩余毫秒（&gt;0）；键不存在 / 无过期（-1）/ 读失败 ⇒ {@code null}。
	 *         {@code null} 是"未知"，调用方不得折算成 0 或某个默认 24h。
	 */
	public Long runKeyTtlRemainingMs(String runId) {
		if (StringUtils.isEmpty(runId)) {
			return null;
		}
		try {
			Long remaining = this.redis.getExpire(runKey(runId), TimeUnit.MILLISECONDS);
			return remaining != null && remaining > 0L ? remaining : null;
		}
		catch (Exception ex) {
			// 可见性失败不该把整页打成 500：返回未知，由前端按"不显示时间"处理
			if (LogSampling.canAcquire("snapshot-remaining-ttl-read")) {
				log.warn("snapshot remaining TTL read failed runId={}", runId, ex);
			}
			return null;
		}
	}

	/**
	 * 本次写入/续期应使用的 TTL：<b>整额 {@code app.ai.session-ttl}（默认 24h）</b>。
	 *
	 * <p>滑动续期（用户裁决 2026-10-20 第 4 条）：每一次写键（业务帧、心跳、台账、待决条目、
	 * 序号、轮快照）都把该键重新养满一个 TTL —— 挂起等待期间的心跳按约 60s 的资源档续期 run 键，
	 * 于是"会话活着，卡的后台数据就活着"。
	 * <b>不再有"创建时刻 + session-ttl"的绝对封顶</b>：那张 240s 的确认卡可以一直挂着，
	 * 它的外置态不会被 6h 的创建时刻封顶掐断。整条会话的寿命由会话记忆键自己的滑动 TTL 承载。
	 */
	private Duration ttlFor(String runId) {
		return this.properties.getEffectiveSessionTtl();
	}

	// ── 上下文计量与压缩的三个键族（上下文统计与压缩专项批①） ──────────────────────

	/** {@code ai:ctx:<tenantId>:<userId>:<sessionId>} —— 会话上下文真值（HASH，字段见 {@code ContextState#toFields}）。 */
	public String contextKey(String sessionId) {
		return TenantKeyScope.scoped(CONTEXT_PREFIX, sessionId);
	}

	/** {@code ai:ctx:ledger:<tenantId>:<userId>:<sessionId>} —— 压缩事件台账（LIST，新→旧）。 */
	public String contextLedgerKey(String sessionId) {
		return TenantKeyScope.scoped(CONTEXT_LEDGER_PREFIX, sessionId);
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
		if (StringUtils.isBlank(sessionId) || event == null) {
			return;
		}
		String key = TenantKeyScope.scoped(SAFETY_PREFIX, sessionId);
		try {
			this.redis.opsForList().leftPush(key, this.mapper.writeValueAsString(event));
			this.redis.opsForList().trim(key, 0, SAFETY_LEDGER_MAX - 1);
			touchContextTtl(key);
		}
		catch (Exception ex) {
			log.warn("safety ledger write failed sessionId={} (turn unaffected)", sessionId, ex);
		}
	}

	/** 读安全台账（新→旧，最多 {@value #SAFETY_LEDGER_MAX} 条；Redis 抖动按空台账）。 */
	public List<Map<String, Object>> safetyLedger(String sessionId) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (StringUtils.isBlank(sessionId)) {
			return out;
		}
		try {
			List<String> raw = this.redis.opsForList()
				.range(TenantKeyScope.scoped(SAFETY_PREFIX, sessionId), 0, SAFETY_LEDGER_MAX - 1);
			if (raw == null) {
				return out;
			}
			for (String item : raw) {
				out.add(this.mapper.readValue(item, new TypeReference<Map<String, Object>>() {
				}));
			}
		}
		catch (Exception ex) {
			log.warn("safety ledger read failed sessionId={} (treating as empty)", sessionId, ex);
		}
		return out;
	}

	/** {@code ai:ctx:events:<tenantId>:<userId>:<sessionId>} —— 已提交压缩事实（LIST，新→旧）。 */
	public String contextEventsKey(String sessionId) {
		return TenantKeyScope.scoped(CONTEXT_EVENTS_PREFIX, sessionId);
	}

	private void touchContextTtl(String key) {
		try {
			this.redis.expire(key, this.properties.getEffectiveSessionTtl());
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("context-key-renew")) {
				log.warn("context key renew failed key={}", key, ex);
			}
		}
	}

}
