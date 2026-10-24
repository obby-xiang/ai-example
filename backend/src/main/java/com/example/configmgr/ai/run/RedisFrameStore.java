package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件日志的 Redis Stream 实现（B23L1 批① / 设计稿 §2.1）——<b>生产唯一实现</b>，
 * 单实例与多实例同一份代码。
 *
 * <h2>键族</h2>
 * <p>三族键名都带 {@code {run:<runId>}} hash tag（{@code <prefix>{run:<runId>}}，见
 * {@link RunStore#runScopedKey(String, String)}）：它们与快照/台账等同属 {@code deleteRun} 的
 * "一轮 13 键"，同 tag 才能让轮次回收是一条原子 {@code DEL}。下列键形为可读性省去 tag。</p>
 * <ul>
 * <li>{@code ai:xevents:<runId>} Stream —— 帧事件日志：{@code XADD key MAXLEN ~ 6000 <seq>-0
 * seq <n> frame <json>}（近似裁剪 {@link #STREAM_WINDOW}；<b>entry ID = {@code seq}-0</b>），
 * 字段 {@code seq} + {@code frame}（帧 JSON 原文，已含截断结果，与实时投递同一份）；
 * 每次追加后 {@code EXPIRE}= session-ttl（滑动续期，与 {@code ai:run:} / {@code ai:events:} 同寿）；</li>
 * <li>{@code ai:seq:<runId>} STRING —— 业务序号发放口（{@code INCR}，跨实例原子）；
 * 键缺失/过期/降级后恢复时按"已落档末号"自修复抬回（{@code repairRollback}），
 * 既保证从 LIST 兼容期迁移不断档，也保证 24h 后键过期不会让号从 1 重来；</li>
 * <li>{@code ai:events:<runId>} LIST —— <b>只读兼容期</b>：新写不再进 LIST，{@link #readFrom}
 * 仍把它串在 Stream 前面（按 {@code seq} 拼接去重），TTL 到期自然消亡，不做搬迁。</li>
 * </ul>
 *
 * <h2>顺序口径（I-2 收口）</h2>
 * entry ID 用<b>显式 {@code <seq>-0}</b> ⇒ <b>物理序 = seq 序</b>（自动 ID 兜底态除外），{@link #readFrom} 因此可以用
 * {@code XRANGE key <afterSeq+1>-0 +}（<b>闭区间</b>，见 {@code streamFramesAbove}）精确取差量
 * （O(差量)，且不依赖"低号帧一定先落"）。<b>不用排他下限 {@code (afterSeq-0}：那是 Redis 6.2 的语法，
 * 目标部署 5.0.14 会直接报错</b>，报错被 catch 折成空 ⇒ 差量读 0 帧。
 * 跨实例并发出帧时显式 ID 可能被 Redis 拒（新 ID 必须大于表尾）⇒ 落档改用**自动 ID 兜底**并 WARN 一次：
 * 该帧物理位次靠后但 {@code seq} 字段仍是原号，读者从 {@code <afterSeq+1>-0} 起读时仍会看到它
 * （自动 ID 远大于任何 {@code seq-0}），再按 {@code seq} 过滤。
 *
 * <p>另：{@link #readFrom} 的输出一律按 {@code seq} 升序（同号以 Stream 为准），
 * 于是"跨实例取号不重不回退"由 {@code INCR} 保证，"订阅者看到的顺序"由排序保证，两者互不牵连。
 *
 * <h2>降级</h2>
 * Redis 抖动时：落档失败只记日志（帧照常实时投递）；取号失败降级为进程内单调计数（WARN 一次/轮），
 * 与既有 S2-4"韧性优先"同口径 —— 该降级<b>不是</b>续号锚点，只是保证流不断。
 */
@Slf4j
@Component
public class RedisFrameStore implements FrameStore {

	/** 事件日志数据键前缀（设计稿 §3 新增键族）。 */
	public static final String STREAM_PREFIX = "ai:xevents:";

	/** 事件日志窗口（近似裁剪；B1 登记的风险从 LIST 的 3000 翻倍缓解，仍非根治）。 */
	public static final int STREAM_WINDOW = 6000;

	/** 末号探测读取的末尾条数（见 {@code streamTailSeq}：乱序兜底态下物理末位可能是低号帧）。 */
	static final int TAIL_PROBE = 16;

	/** Stream 记录里的两个字段名。 */
	static final String FIELD_SEQ = "seq";

	static final String FIELD_FRAME = "frame";

	private final StringRedisTemplate redis;

	private final ObjectMapper mapper;

	private final AiProperties properties;

	/** 窗口（生产 = {@link #STREAM_WINDOW}；包内构造器给单测一个小窗口，避免 6000 帧语料）。 */
	private final int streamWindow;

	/** 取号降级计数器（Redis 取号失败期间的进程内单调兜底）。 */
	private final Map<String, AtomicLong> fallbackSeqs = new ConcurrentHashMap<>();

	/** 已就"显式 ID 落档被拒"告警过的轮（同一轮只吼一次，避免乱序期刷日志）。 */
	private final Set<String> outOfOrderWarned = ConcurrentHashMap.newKeySet();

	@Autowired
	public RedisFrameStore(StringRedisTemplate redis, ObjectMapper mapper, AiProperties properties) {
		this(redis, mapper, properties, STREAM_WINDOW);
	}

	RedisFrameStore(StringRedisTemplate redis, ObjectMapper mapper, AiProperties properties, int streamWindow) {
		this.redis = redis;
		this.mapper = mapper;
		this.properties = properties;
		this.streamWindow = streamWindow > 0 ? streamWindow : STREAM_WINDOW;
	}

	// ── 键名 ────────────────────────────────────────────────────────────────
	// B23L1 批①（收口版）：三族键名一律走 {@link RunStore#runScopedKey}（{@code <prefix>{run:<runId>}}），
	// 与 RunStore 的续期/删除口**逐字同键**。这里加 tag 的目的不是本类需要同槽（本类的读都是单键命令），
	// 而是"一轮 13 键同槽"—— 写方在旧键名、删方在新键名，就等于事件日志永不回收、也永不续期。

	public String streamKey(String runId) {
		return RunStore.runScopedKey(STREAM_PREFIX, runId);
	}

	public String seqKey(String runId) {
		return RunStore.runScopedKey(RunStore.SEQ_PREFIX, runId);
	}

	/** 兼容期 LIST 键（只读）。 */
	public String legacyListKey(String runId) {
		return RunStore.runScopedKey(RunStore.EVENTS_PREFIX, runId);
	}

	// ── FrameStore ──────────────────────────────────────────────────────────

	@Override
	public long nextSeq(String runId) {
		String key = seqKey(runId);
		try {
			long issued = increment(key);
			long repaired = repairRollback(runId, key, issued);
			this.redis.expire(key, ttl());
			return repaired;
		}
		catch (Exception ex) {
			long fallback = fallbackSeq(runId).incrementAndGet();
			log.warn("序号发放降级为进程内计数 runId={} seq={}（Redis INCR 失败，跨实例不重承诺暂不可用）：{}",
					runId, fallback, ex.getMessage());
			return fallback;
		}
	}

	private long increment(String key) {
		Long issued = this.redis.opsForValue().increment(key);
		if (issued == null) {
			throw new IllegalStateException("INCR 返回空");
		}
		return issued;
	}

	/**
	 * 号回退自修复（M-1）：{@code ai:seq} 键可能因 24h 无写而过期、被 {@code deleteRun} 删掉、
	 * 或 Redis 抖动期改走了进程内降级计数 —— 三种情形都会让 {@code INCR} 从 1 重来。
	 *
	 * <p>判据只用<b>廉价的信号</b>（不引入每帧额外往返）：{@code INCR} 返回 1（键原本不存在）
	 * 或返回值不大于本进程已降级发出的号。命中才去读一次事件日志末号，把 {@code ai:seq}
	 * 抬到 {@code max(Stream 末号, LIST 末号, 降级末号)} 再 {@code INCR} 一次。
	 *
	 * <p>不修的话：新帧与历史帧同号 ⇒ {@code mergeBySeq} 同号合并会丢一帧、前端 {@code lastSeq} 倒退。
	 */
	private long repairRollback(String runId, String key, long issued) {
		long fallbackLast = this.fallbackSeqs.containsKey(runId) ? this.fallbackSeqs.get(runId).get() : 0L;
		if (issued > 1L && issued > fallbackLast) {
			return issued;
		}
		StreamTail streamTail = streamTailSeq(runId);
		if (!streamTail.ok()) {
			// A4：末号未知时不得拿 0 当锚点修复（会把号抬不到位）；本次不修复，等下一拍/下一帧再试。
			log.debug("号回退修复跳过 runId={} issued={}（Stream 末号未知，不能按 0 锚定）", runId, issued);
			return issued;
		}
		long anchor = Math.max(Math.max(legacyTailSeq(runId), streamTail.seq()), fallbackLast);
		if (anchor < issued) {
			return issued;
		}
		log.warn("检测到 ai:seq 号回退 runId={} issued={} anchor={}（键过期/被删/降级后恢复），按已落档末号抬回",
				runId, issued, anchor);
		this.redis.opsForValue().set(key, String.valueOf(anchor), ttl());
		return increment(key);
	}

	@Override
	public void append(String runId, Map<String, Object> frame) {
		String type = String.valueOf(frame.get("type"));
		if (RunStore.HEARTBEAT_TYPE.equals(type)) {
			// T7 原样：心跳不入事件日志（活动戳由调用方 touchActivity 留痕）。
			return;
		}
		long seq = seqOf(frame);
		if (seq <= 0L) {
			// N-4：无 seq 的帧不进 Stream（否则会写入一条 seq=0 记录，被 streamTailSeq 当成末号）。
			log.debug("事件日志跳过无 seq 帧 runId={} type={}", runId, type);
			return;
		}
		byte[] key = bytes(streamKey(runId));
		byte[] payload;
		try {
			payload = this.mapper.writeValueAsBytes(frame);
		}
		catch (Exception ex) {
			log.warn("事件日志序列化失败 runId={} type={}（该帧未落档，跨实例不可见）：{}", runId, type, ex.getMessage());
			return;
		}
		long ttlSeconds = Math.max(1L, ttl().toSeconds());
		try {
			xadd(key, seq + "-0", seq, payload, ttlSeconds);
		}
		catch (Exception ex) {
			// 显式 ID 被拒 = 物理序已经越过这个号（跨实例并发出帧 / 降级号回退）。帧不能丢：
			// 改用自动 ID 追加（物理位次靠后，但 seq 字段仍在，读侧按 <afterSeq+1>-0 起读 + 按 seq 过滤仍可读到）。
			if (this.outOfOrderWarned.add(runId)) {
				log.warn("事件日志显式 ID 落档被拒 runId={} seq={}（改用自动 ID 兜底；物理序与 seq 序将不再一致）：{}",
						runId, seq, ex.getMessage());
			}
			try {
				xadd(key, "*", seq, payload, ttlSeconds);
			}
			catch (Exception retryEx) {
				// 帧留档失败不影响本轮运行（外置是"更耐久"，不是"更正确"的前提）
				log.warn("事件日志追加失败 runId={} type={} seq={}（该帧跨实例不可见）：{}", runId, type, seq,
						retryEx.getMessage());
			}
		}
	}

	/**
	 * 追加一条 Stream 记录：{@code XADD key MAXLEN ~ <窗口> <id> seq <n> frame <json>}，随后续期 TTL。
	 *
	 * <p>
	 * <b>为什么用原始命令而不是 {@code StreamOperations} / {@code RedisConnection#xAdd(record, options)}</b>：
	 * 后者无法指定 entry ID（一律自动），而本批的正确性依赖 <b>ID = {@code seq}-0</b>：
	 * 物理序恒等于 seq 序（自动 ID 兜底态除外），于是"按 {@code XRANGE <afterSeq+1>-0 +} 读差量"既完整又只要 O(差量) ——
	 * 这是 I-2（物理序 ≠ seq 序时低号帧永久不可读）的根治口径。{@code MAXLEN ~} 仍走近似裁剪。
	 *
	 * @param idToken 显式 ID（{@code "<seq>-0"}）或 {@code "*"}（乱序兜底）
	 */
	private void xadd(byte[] key, String idToken, long seq, byte[] payload, long ttlSeconds) {
		this.redis.execute((RedisCallback<Object>) connection -> {
			connection.execute("XADD", key,
					bytes("MAXLEN"), bytes("~"), bytes(String.valueOf(this.streamWindow)),
					bytes(idToken),
					bytes(FIELD_SEQ), bytes(String.valueOf(seq)),
					bytes(FIELD_FRAME), payload);
			connection.expire(key, ttlSeconds);
			return null;
		});
	}

	/**
	 * 按序号回读（兼容期双读）。
	 *
	 * <p>
	 * <b>Stream 侧按 entry ID 读差量</b>：落档用的是显式 ID {@code <seq>-0}，所以
	 * {@code XRANGE key <afterSeq+1>-0 + COUNT 窗口} 拿到的<b>恰好</b>是 {@code seq > afterSeq} 的记录
	 * ——完整（不依赖物理序与 seq 序一致）且代价 O(差量)。这是 I-2 的根治：旧实现按
	 * {@code 末号 − afterSeq} 估条数取尾，一旦低号帧后到（跨实例并发出帧 / 降级号回退），
	 * 它会被所有读者永久漏掉。下限用<b>闭区间 {@code afterSeq+1}</b>（不是 6.2 才支持的排他
	 * {@code (afterSeq}）：目标部署 5.0.14 会拒绝排他区间，而拒绝会被折成空结果 ⇒ 0 帧。
	 *
	 * <p>
	 * <b>乱序兜底</b>：显式 ID 被拒时落档会改用自动 ID（见 {@code append}），这类记录的 ID
	 * 远大于任何 {@code seq-0}，因此同样落在 {@code <afterSeq+1>-0} 的读取范围内，再按 {@code seq}
	 * 字段过滤；代价是这种降级态下读取会退化为整窗（≤ 窗口条），并在 {@code append} 处 WARN 一次。
	 *
	 * <p>
	 * <b>空流与读失败</b>：两者返回值都是空列表（调用链只把"空"当"暂无差量"，抛异常会让
	 * 挂起重连与唤醒补帧整体失败），但读失败一律 WARN 留痕（带 runId/区间/异常），
	 * 不静默折成空 —— 否则"日志读不出来"会伪装成"没有新帧"。
	 */
	@Override
	public List<Map<String, Object>> readFrom(String runId, long afterSeq, int limit) {
		return readFromWithStatus(runId, afterSeq, limit).frames();
	}

	@Override
	public FrameStore.ReadResult readFromWithStatus(String runId, long afterSeq, int limit) {
		List<Map<String, Object>> legacy = legacyFramesAbove(runId, afterSeq);
		FrameStore.ReadResult stream = streamFramesAbove(runId, afterSeq);
		boolean degraded = stream.degraded();
		if (degraded) {
			// 结果可能仍非空（例如末号探测失败但 XRANGE 成功）——但它是"降级读"，不是完整差量。
			// 计数已在 streamTailSeq / streamFramesAbove 落；这里把信号继续传给调用方（API 层可拒绝）。
			log.debug("事件日志差量读降级 runId={} afterSeq={}（读失败已计数，结果不保证完整）", runId, afterSeq);
		}
		List<Map<String, Object>> result = mergeBySeq(legacy, stream.frames(), afterSeq);
		if (limit > 0 && result.size() > limit) {
			result = new ArrayList<>(result.subList(0, limit));
		}
		return new FrameStore.ReadResult(result, degraded);
	}

	/**
	 * 兼容期 LIST 读口：<b>键空即跳过</b>（迁移完成后恒为空，这里是 O(1) 空读）；
	 * 有内容就整窗读 —— 不能只按"末帧号 ≤ afterSeq"短路：LIST 里可能夹着升级期写入的
	 * 无 {@code seq} 老帧，而这类帧的规则是"无法判定 ⇒ 一律保留"（宁可不发，不可丢帧），
	 * 按号短路会把它们静默丢掉（用例 {@code legacyFramesWithoutSeqAreKeptAtTheHead} 即此形态）。
	 */
	private List<Map<String, Object>> legacyFramesAbove(String runId, long afterSeq) {
		if (legacyTailRaw(runId) == null) {
			return List.of();
		}
		return readLegacyList(runId);
	}

	/**
	 * Stream 差量读：{@code XRANGE key <from+1>-0 + COUNT 窗口}（闭区间 ID 区间，O(差量)）。
	 *
	 * <p>
	 * <b>为什么是 {@code from+1} 而不是排他下限</b>：排他区间（{@code (id}）是 Redis 6.2 才有的语法，
	 * 目标部署 <b>5.0.14 会把 {@code (123-0} 当非法 stream ID 直接报错</b>（真实响应
	 * {@code ERR Invalid stream ID specified as stream command argument}），而报错会被本方法的
	 * catch 折成空结果 —— 表现就是<b>差量读回 0 帧</b>。长 run 首次回放同样中招：{@code afterSeq=0}
	 * 但下限已被夹到 {@code tail − 窗口 > 0}。改用 {@code inclusive (from+1)-0} 等价：
	 * 记录 ID 只可能是 {@code <seq>-0} 或远大于任何 {@code seq-0} 的自动 ID，两者都
	 * {@code ≥ (from+1)-0 ⇔ seq > from}，语义与排他下限完全一致，且 5.0 可用。
	 *
	 * <p>
	 * <b>读取下限夹到窗口头</b>（M′，复核确认轮）：{@code from = max(afterSeq, tailSeq − 窗口)}。
	 * 为什么必须夹：{@code MAXLEN ~} 是<b>近似</b>裁剪，流内实际可留 窗口 ~ 窗口+99 条；而
	 * {@code XRANGE} 是<b>升序</b>读、{@code COUNT} 只截断尾部 —— 若按 {@code afterSeq=0}
	 * （全量回放的最典型形态）直接读，拿到的是<b>最旧</b>的窗口条，被丢掉的反而是<b>最新</b>的
	 * 那几条（可能含终帧 {@code done}）。夹到 {@code tail − 窗口} 后，任何读者拿到的都是
	 * 「最新窗口 ∩ {@code >afterSeq}」：全量回放补回最新窗口，落后读者也不会被旧窗口挤掉新帧。
	 *
	 * <p>{@code afterSeq >= tail − 窗口} 时夹取不改变下限（走差量），因此热路径的 O(差量) 不变；
	 * 代价是每次读多一次 {@code XREVRANGE COUNT 1}（取 {@code tailSeq}）。
	 */
	private FrameStore.ReadResult streamFramesAbove(String runId, long afterSeq) {
		StreamTail tail = streamTailSeq(runId);
		boolean degraded = !tail.ok();
		long from;
		if (tail.ok()) {
			if (tail.seq() <= afterSeq) {
				return new FrameStore.ReadResult(List.of(), false);
			}
			from = Math.max(afterSeq, tail.seq() - this.streamWindow);
		}
		else if (afterSeq <= 0L) {
			// I-1：afterSeq=0 是"全量回放"最典型的形态。末号未知时无界 XRANGE 会拿**最旧**窗口
			// （升序 + COUNT 截尾），正好丢掉含 done 的最新帧 —— 宁可返回空 + degraded，
			// 让上层回 503/下一拍重试，也不回错窗。
			log.warn("事件日志末号未知且要求全量回放 runId={}（拒绝退回最旧窗口，返回降级空结果）", runId);
			return new FrameStore.ReadResult(List.of(), true);
		}
		else {
			// 差量读：afterSeq 是可靠下界，无需末号夹窗口；可能被窗口 COUNT 截掉最新几帧，
			// 但不会回错窗，且 degraded 会让上层知道结果不完整。
			from = afterSeq;
		}
		List<Map<String, Object>> out = new ArrayList<>();
		Range<String> range = from <= 0L
				? Range.unbounded()
				: Range.from(Range.Bound.inclusive((from + 1) + "-0")).to(Range.Bound.unbounded());
		try {
			byte[] key = bytes(streamKey(runId));
			this.redis.execute((RedisCallback<Object>) connection -> {
				List<ByteRecord> records = connection.xRange(key, range,
						org.springframework.data.redis.connection.Limit.limit().count(this.streamWindow));
				if (records == null) {
					return null;
				}
				for (ByteRecord record : records) {
					Map<String, Object> frame = frameOf(record);
					if (frame != null) {
						out.add(frame);
					}
				}
				return null;
			});
		}
		catch (Exception ex) {
			// 读失败 ≠ 空流：返回值对调用链仍是"暂无差量"（挂起重连 / 唤醒补帧不能被一次抖动打断，
			// 抛异常会让整条订阅路径失败），但必须 WARN + 计数 —— 静默折成空会把"日志读不出来"
			// 伪装成"没有新帧"，正是本批要消灭的形态。
			RedisDegradation.record(RedisDegradation.A3_FRAME_RANGE_READ, ex);
			log.warn("事件日志读取失败 runId={} afterSeq={} from={}（本次按无差量处理，不重试）：{}",
					runId, afterSeq, from, ex.toString());
			return new FrameStore.ReadResult(out, true);
		}
		return new FrameStore.ReadResult(out, degraded);
	}

	@Override
	public long lastSeq(String runId) {
		return lastSeqIfKnown(runId).orElse(-1L);
	}

	@Override
	public OptionalLong lastSeqIfKnown(String runId) {
		StreamTail tail = streamTailSeq(runId);
		if (!tail.ok()) {
			// A4：末号探测失败不得折成 0（前端会把 0 当"全量回放"的锚点）；返回 empty 让端点回 503/degraded。
			return OptionalLong.empty();
		}
		return OptionalLong.of(Math.max(legacyTailSeq(runId), tail.seq()));
	}

	// ── 兼容读：LIST + Stream 按 seq 拼接去重 ────────────────────────────────

	/**
	 * 拼接口径（设计稿 §4）：老 LIST 帧与 Stream 帧按 {@code seq} 排序去重，
	 * <b>同号以 Stream 为准</b>（新写只进 Stream，LIST 同号必是迁移前的旧副本）；
	 * <b>没有 {@code seq} 的老帧排在前面且一律保留</b>（升级期帧无法判孤儿，宁可不发不可丢帧）。
	 */
	private List<Map<String, Object>> mergeBySeq(List<Map<String, Object>> legacy,
			List<Map<String, Object>> stream, long afterSeq) {
		List<Map<String, Object>> noSeq = new ArrayList<>();
		Map<Long, Map<String, Object>> bySeq = new TreeMap<>();
		collect(legacy, noSeq, bySeq, false);
		collect(stream, noSeq, bySeq, true);
		List<Map<String, Object>> out = new ArrayList<>(noSeq.size() + bySeq.size());
		out.addAll(noSeq);
		for (Map.Entry<Long, Map<String, Object>> entry : bySeq.entrySet()) {
			if (entry.getKey() > afterSeq) {
				out.add(entry.getValue());
			}
		}
		return out;
	}

	private static void collect(List<Map<String, Object>> frames, List<Map<String, Object>> noSeq,
			Map<Long, Map<String, Object>> bySeq, boolean override) {
		for (Map<String, Object> frame : frames) {
			Object seq = frame.get("seq");
			if (seq instanceof Number number) {
				if (override) {
					bySeq.put(number.longValue(), frame);
				}
				else {
					bySeq.putIfAbsent(number.longValue(), frame);
				}
			}
			else {
				noSeq.add(frame);
			}
		}
	}

	/** 遗留 LIST（只读）：只取窗口内的尾巴，整表 O(N) 读取不划算（窗口 3000）。 */
	private List<Map<String, Object>> readLegacyList(String runId) {
		List<Map<String, Object>> out = new ArrayList<>();
		try {
			List<String> raw = this.redis.opsForList().range(legacyListKey(runId), -RunStore.EVENT_WINDOW, -1);
			if (raw == null) {
				return out;
			}
			for (String item : raw) {
				try {
					out.add(this.mapper.readValue(item, new TypeReference<Map<String, Object>>() {
					}));
				}
				catch (Exception ignored) {
					// 单帧坏损不阻断回放（与 RunStore#events 同口径）
				}
			}
		}
		catch (Exception ex) {
			log.warn("兼容期 LIST 读取失败 runId={}（整段不可读，本批 LIST 帧缺失）：{}", runId, ex.toString());
		}
		return out;
	}

	/** LIST 末帧号（兼容期锚点与回放末号的一半）。 */
	private long legacyTailSeq(String runId) {
		String raw = legacyTailRaw(runId);
		if (raw == null) {
			return 0L;
		}
		try {
			return seqOf(this.mapper.readValue(raw, new TypeReference<Map<String, Object>>() {
			}));
		}
		catch (Exception ex) {
			return 0L;
		}
	}

	/** LIST 末帧原文（null = 键不存在/空表；只读一个元素，用于"键空即跳过"的短路）。 */
	private String legacyTailRaw(String runId) {
		try {
			List<String> tail = this.redis.opsForList().range(legacyListKey(runId), -1, -1);
			return tail == null || tail.isEmpty() ? null : tail.get(0);
		}
		catch (Exception ex) {
			// 探测失败若按"键空"处理，整批老帧会被跳过 —— 必须留痕，不能伪装成空表。
			log.warn("兼容期 LIST 读取失败 runId={}（末帧探测，按空表处理）：{}", runId, ex.toString());
			return null;
		}
	}

	/**
	 * Stream 末号 = 末尾 {@value #TAIL_PROBE} 条的<b>最大</b> {@code seq}（一次 {@code XREVRANGE COUNT n}）。
	 *
	 * <p>
	 * 为什么不只读物理最后一条：乱序兜底态下（显式 ID 被拒 ⇒ 自动 ID）物理末位可能是<b>低号</b>帧，
	 * 只读一条会把末号判小 —— 而末号是"读取下限夹取"（M′）与"号回退修复"（M-1）的共同锚点。
	 * 同一 run 的 seq 由 {@code INCR} 单调发放，乱序只会发生在个别晚到帧上，因此末尾 16 条取 max
	 * 在工程上足够（且仍是一次往返、只解析 {@code seq} 字段、不做 JSON 反序列化）。
	 */
	private StreamTail streamTailSeq(String runId) {
		try {
			byte[] key = bytes(streamKey(runId));
			Long tail = this.redis.execute((RedisCallback<Long>) connection -> {
				List<ByteRecord> records = connection.xRevRange(key, Range.unbounded(),
						org.springframework.data.redis.connection.Limit.limit().count(TAIL_PROBE));
				if (records == null || records.isEmpty()) {
					return 0L;
				}
				long max = 0L;
				for (ByteRecord record : records) {
					max = Math.max(max, seqFieldOf(record));
				}
				return max;
			});
			return new StreamTail(tail == null ? 0L : tail, true);
		}
		catch (Exception ex) {
			// A4：末号探测失败按"未知"表达（ok=false），不再伪装成"末号 0"。
			// 调用方据此区分"空流"与"读失败"；readFrom 会退化为不夹窗口的 XRANGE，而不是直接空。
			RedisDegradation.record(RedisDegradation.A4_FRAME_TAIL_READ, ex);
			log.warn("事件日志末号探测失败 runId={}（末号未知，本次回读退化为不夹窗口）：{}", runId, ex.toString());
			return new StreamTail(0L, false);
		}
	}

	/** 末号探测结果：{@code ok=false} = 读失败（末号未知），与"确实没有记录（seq=0, ok=true）"区分。 */
	private record StreamTail(long seq, boolean ok) {
	}

	/** 只取一条 Stream 记录的 {@code seq} 字段（不反序列化帧体）。 */
	private static long seqFieldOf(MapRecord<byte[], byte[], byte[]> record) {
		for (Map.Entry<byte[], byte[]> entry : record.getValue().entrySet()) {
			if (FIELD_SEQ.equals(new String(entry.getKey(), StandardCharsets.UTF_8))) {
				try {
					return Long.parseLong(new String(entry.getValue(), StandardCharsets.UTF_8));
				}
				catch (NumberFormatException ex) {
					return 0L;
				}
			}
		}
		return 0L;
	}

	/** 从一条 Stream 记录还原帧（字段 {@code frame} 是帧 JSON 原文；{@code seq} 兜底补齐）。 */
	private Map<String, Object> frameOf(MapRecord<byte[], byte[], byte[]> record) {
		byte[] raw = null;
		byte[] seq = null;
		for (Map.Entry<byte[], byte[]> entry : record.getValue().entrySet()) {
			String field = new String(entry.getKey(), StandardCharsets.UTF_8);
			if (FIELD_FRAME.equals(field)) {
				raw = entry.getValue();
			}
			else if (FIELD_SEQ.equals(field)) {
				seq = entry.getValue();
			}
		}
		if (raw == null) {
			return null;
		}
		try {
			Map<String, Object> frame = this.mapper.readValue(raw, new TypeReference<Map<String, Object>>() {
			});
			if (seq != null) {
				frame.putIfAbsent("seq", Long.parseLong(new String(seq, StandardCharsets.UTF_8)));
			}
			return frame;
		}
		catch (Exception ex) {
			log.debug("事件日志单条坏损（跳过）：{}", ex.getMessage());
			return null;
		}
	}

	// ── 内部 ────────────────────────────────────────────────────────────────

	private AtomicLong fallbackSeq(String runId) {
		return this.fallbackSeqs.computeIfAbsent(runId, id -> new AtomicLong(Math.max(0L, lastSeq(id))));
	}

	private Duration ttl() {
		return this.properties.getSessionTtl();
	}

	private static long seqOf(Map<String, Object> frame) {
		Object seq = frame.get("seq");
		return seq instanceof Number number ? number.longValue() : 0L;
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

}
