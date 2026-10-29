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
import org.springframework.data.redis.core.script.RedisScript;
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
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 事件日志的 Redis Stream 实现（B23L1 批① / 设计稿 §2.1）——<b>生产唯一实现</b>，
 * 单实例与多实例同一份代码。
 *
 * <h2>键族</h2>
 * <p>三族键名都带 {@code {run:<runId>}} hash tag（{@code <prefix>{run:<runId>}}，见
 * {@link RunStore#runScopedKey(String, String)}）：它们与快照/台账等同属 {@code deleteRun} 的
 * "一轮 13 键"，同 tag 才能让轮次回收是一条原子 {@code DEL}。下列键形为可读性省去 tag。</p>
 * <ul>
 * <li>{@code ai:xevents:<runId>} Stream —— 帧事件日志：{@code XADD key MAXLEN ~ <窗口> <seq>-0
 * seq <n> frame <json>}（近似裁剪；<b>窗口 = min(条数窗口 {@link #STREAM_WINDOW}, 字节预算推导的等效窗口)</b>，
 * 见 {@link #windowForPayload(long)}；<b>entry ID = {@code seq}-0</b>），
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
 *
 * <p><b>容量治理（Redis 容量专项 A）</b>：条数窗口（6000）在单帧 64KB 上界下并不封顶字节，
 * {@code noeviction} 触顶会让所有 AI 写入一起失败。本类按"字节预算 ÷ 本轮观察到的最大帧载荷"
 * 收窄窗口（{@link #windowForPayload(long)}），并把三种情形落到
 * {@link RedisDegradation}：{@code c1.streamByteCap}（预算成为约束）、
 * {@code c1.streamByteFloor}（预算连下限窗口都撑不住）、{@code c1.streamReplayGap}
 * （回放游标早于幸存窗口）。裁剪仍是 {@code MAXLEN ~}（近似），<b>没有</b>引入新命令或按字节裁剪协议。
 */
@Slf4j
@Component
public class RedisFrameStore implements FrameStore {

	/** 事件日志数据键前缀（设计稿 §3 新增键族）。 */
	public static final String STREAM_PREFIX = "ai:xevents:";

	/** 事件日志窗口（近似裁剪；B1 登记的风险从 LIST 的 3000 翻倍缓解，仍非根治）。 */
	public static final int STREAM_WINDOW = 6000;

	/**
	 * 按字节治理时允许的<b>最窄</b>窗口（条）（Redis 容量专项 A）。
	 *
	 * <p>取值依据：与实时投递队列的缺省容量
	 * ({@link AiProperties.Sse#DEFAULT_DELIVERY_QUEUE_CAPACITY} = 256) 同量级 —— 回放窗口比
	 * 一次重连能吸收的实时队列还短，回放就不再承担"补回断连期内容"的职责。
	 * 当 {@code byte-budget ÷ 每条上界} 低于本值时，窗口取本值（不再继续收窄），
	 * 并计 {@link RedisDegradation#C1_STREAM_BYTE_FLOOR}：<b>保守界兑现不了就必须可见</b>。
	 */
	public static final int MIN_WINDOW = 256;

	/**
	 * 每条 Stream 记录的<b>固定开销</b>估算（字节；Redis 容量专项 A）。
	 *
	 * <p>构成：entry ID（22B 文本 + listpack 头）+ 两个字段名 {@code seq}/{@code frame} 的
	 * 主条目摊薄 + 整数编码/长度前缀的余量。取 <b>256B</b>（明显大于实测，保守方向 = 多裁而非超预算）。
	 *
	 * <p>字节预算的等效口径：{@code 有效窗口 × (本轮最大帧载荷 + 本值) ≤ byte-budget}。
	 * 除 Stream 自身开销外，Redis 的分配器碎片/keyspace 开销不在内 —— 见
	 * {@code AiProperties.Stream#byteBudget} 的取舍段。
	 */
	public static final long ENTRY_OVERHEAD_BYTES = 256L;

	/** 末号探测读取的末尾条数（见 {@code streamTailSeq}：乱序兜底态下物理末位可能是低号帧）。 */
	static final int TAIL_PROBE = 16;

	/** Stream 记录里的两个字段名。 */
	static final String FIELD_SEQ = "seq";

	static final String FIELD_FRAME = "frame";

	/**
	 * 取号脚本的标记（脚本正文第一行的注释；单测替身按它分派语义，正文改动必须同步改替身）。
	 */
	static final String SEQ_NEXT_MARK = "AI_SEQ_NEXT";

	/**
	 * 取号脚本正文（{@code EVAL}，{@code KEYS} 三键同槽）。
	 *
	 * <p>三键：{@code [ai:seq, ai:xevents, ai:events]}（全部带 {@code {run:<runId>}} hash tag ⇒ 同一槽，
	 * Cluster 下多键脚本因此合法）。{@code ARGV}：{@code [session-ttl 秒, 本进程降级号, 末号探测条数]}。
	 * 返回 {@code {seq, degraded}}：{@code degraded=1} = 键刚重建/号落后但归档末号读不出
	 * （脚本不抬号，调用方 WARN 一次）。
	 *
	 * <p>为什么"读末号"必须放进脚本：见 {@link #nextSeq(String)} —— 读与写分开时，
	 * 并发窗口里交出去的低号会被后来的无条件写覆盖，等于<b>复用已发 seq</b>。
	 */
	private static final String SEQ_NEXT_BODY = """
			-- AI_SEQ_NEXT v2: INCR -> (键重建/号落后/强制修复时按归档末号抬号) -> EXPIRE，一次脚本一次往返
			local ttl = tonumber(ARGV[1]) or 1
			local fallbackLast = tonumber(ARGV[2]) or 0
			local probe = tonumber(ARGV[3]) or 16
			local forceRepair = tonumber(ARGV[4]) or 0
			local cur = redis.call('INCR', KEYS[1])
			local degraded = 0
			if cur == 1 or cur <= fallbackLast or forceRepair == 1 then
			  local anchor = fallbackLast
			  local okStream, entries = pcall(redis.call, 'XREVRANGE', KEYS[2], '+', '-', 'COUNT', probe)
			  if okStream and type(entries) == 'table' then
			    for i = 1, #entries do
			      local fields = entries[i][2]
			      if type(fields) == 'table' then
			        for j = 1, #fields, 2 do
			          if fields[j] == 'seq' then
			            local v = tonumber(fields[j + 1])
			            if v and v > anchor then anchor = v end
			          end
			        end
			      end
			    end
			  else
			    degraded = 1
			  end
			  local okLegacy, legacy = pcall(redis.call, 'LRANGE', KEYS[3], -1, -1)
			  if okLegacy and type(legacy) == 'table' and legacy[1] then
			    local okJson, decoded = pcall(cjson.decode, legacy[1])
			    if okJson and type(decoded) == 'table' then
			      local v = tonumber(decoded['seq'])
			      if v and v > anchor then anchor = v end
			    end
			  elseif not okLegacy then
			    degraded = 1
			  end
			  if anchor >= cur then
			    cur = redis.call('INCRBY', KEYS[1], anchor + 1 - cur)
			  end
			end
			redis.call('EXPIRE', KEYS[1], ttl)
			if degraded == 1 and cur == 1 then
			  -- 刚重建的计数器 + 归档末号读不出：这个 1 无法证明未被用过（归档里可能已有 1..N）。
			  -- 不交出去：返回 0，调用方带 forceRepair 重试或走进程内降级计数（绝不发一个"可能是重复"的号）。
			  return {0, 1}
			end
			return {cur, degraded}
			""";

	/** 取号脚本（结果 = 两元素数组，故结果类型是 {@code List}）。 */
	private static final RedisScript<List> SEQ_NEXT_SCRIPT = seqNextScript();

	private static RedisScript<List> seqNextScript() {
		org.springframework.data.redis.core.script.DefaultRedisScript<List> script =
				new org.springframework.data.redis.core.script.DefaultRedisScript<>();
		script.setScriptText(SEQ_NEXT_BODY);
		script.setResultType(List.class);
		return script;
	}

	private final StringRedisTemplate redis;

	private final ObjectMapper mapper;

	private final AiProperties properties;

	/** 窗口（生产 = {@link #STREAM_WINDOW}；包内构造器给单测一个小窗口，避免 6000 帧语料）。 */
	private final int streamWindow;

	/**
	 * 进程内每轮簿记的<b>硬上限</b>（帧序号/资源修复批）。
	 *
	 * <p>取值依据：单实例同时"活着"的 run 数由挂起/并发连接数决定，正常远小于本值
	 * （会话门 + 连接数上界）；本值只是<b>兜底界</b> —— 让"终态回收漏了一轮"不会变成无界增长。
	 */
	static final int MAX_TRACKED_RUNS = 4096;

	/** 空闲簿记的清理检查间隔（毫秒）：每帧都做一次 CAS 太贵，一拍一次足够。 */
	static final long SWEEP_INTERVAL_MS = 60_000L;

	/**
	 * 取号脚本的<b>最多尝试次数</b>（评审 I4）：只在"刚重建的计数器 + 归档末号读不出"（脚本拒绝交出
	 * 低号，返回 0）时重试 —— 瞬时抖动第二拍通常就恢复，因此界取 2；不收敛就交给进程内降级计数
	 * （WARN，跨实例不重号承诺暂停），绝不把"可能是重复"的低号发出去。
	 */
	static final int SEQ_ALLOCATE_ATTEMPTS = 2;

	/** 进程内时钟（单测可注入，让"归档到期"可确定性地推进）。 */
	private final LongSupplier clock;

	/**
	 * 每轮的<b>进程内簿记</b>（帧序号/资源修复批：合并成一条，生命周期只有一处）。
	 *
	 * <p>此前是四张按 runId 无界的 Map/Set（降级号、观察到的最大载荷、三类告警位）：
	 * 每轮各一条，只有进程重启才释放。现在一条 {@link RunState} 承载全部，
	 * 生命周期由两条口径兜住：终态 {@link #forget(String)}（谁结束谁释放）+ 到期/超上限清理
	 * （{@link #sweepIdle(long)}：归档 TTL 之后这些簿记已无意义，且总量恒 ≤ {@link #MAX_TRACKED_RUNS}）。
	 *
	 * <p>清掉簿记不会影响正确性：取号锚点与末号都在 Redis（归档 + {@code ai:seq}），
	 * 进程内簿记只是降级兜底与"每轮一次"的日志节流；重建时按归档末号重锚（见 {@link #nextFallbackSeq}）。
	 */
	private final Map<String, RunState> runStates = new ConcurrentHashMap<>();

	/** 最近一次到期清理的时刻（毫秒），只让一拍真正做清理。 */
	private final AtomicLong lastSweepMs = new AtomicLong();

	/** 每轮簿记：降级号 + 观察到的最大载荷 + 三个"每轮一次"告警位 + 最后触达时刻。 */
	static final class RunState {

		/** 降级号的"尚未初始化"哨兵（首次降级时按归档末号锚定）。 */
		static final long UNINITIALIZED = -1L;

		/** 降级号（Redis 取号失败期间的进程内单调兜底；{@link #UNINITIALIZED} = 未初始化）。 */
		final AtomicLong fallbackSeq = new AtomicLong(UNINITIALIZED);

		/**
		 * 本轮<b>观察到的最大帧载荷字节数</b>（Redis 容量专项 A 的"每条上界"输入）。
		 *
		 * <p>为什么是"观察到的"而不是配置的 64KB 上界：静态按 64KB 折算会把所有轮次的窗口从 6000
		 * 压到 ~1000（正常小帧轮次的白白损失）；按实际见过的最大帧折算，小帧轮次保持既有回放深度，
		 * 只有真出现大帧的轮次才收窄。上界仍是<b>可证</b>的：此后每条记录都 ≤ 本值 + 固定开销。
		 *
		 * <p>进程内口径（不跨实例共享）。界因此是<b>单实例可证</b>的：同一实例的后续落档都按它收窄；
		 * <b>多实例</b>交叉写大帧时，另一个实例（没观察过那个大帧）仍按自己的窗口写，
		 * 全局字节上界退化为"各实例各自观察之和"。
		 */
		final AtomicLong observedPayloadBytes = new AtomicLong();

		/** 已就"显式 ID 落档被拒"告警过（同一轮只吼一次，避免乱序期刷日志）。 */
		final AtomicBoolean outOfOrderWarned = new AtomicBoolean();

		/** 已就"字节预算成为约束"告警过。 */
		final AtomicBoolean byteCapWarned = new AtomicBoolean();

		/** 已就"预算不足以下限窗口"告警过。 */
		final AtomicBoolean byteFloorWarned = new AtomicBoolean();

		/** 已就"号回退修复降级（归档末号读不出）"告警过。 */
		final AtomicBoolean repairDegradedWarned = new AtomicBoolean();

		/**
		 * <b>上一次取号是降级结果</b>（评审 I4）：脚本回包带 {@code degraded=1} 时置位，
		 * 下一次取号据此带 {@code forceRepair} 重读归档末号并原子抬号（成功即清位）。
		 *
		 * <p>为什么必须有这个位：判据 {@code cur == 1 || cur <= 降级号} 只覆盖"计数器刚重建/还没追上"，
		 * 一旦计数器越过 1 就<b>永不重试</b> —— 旧文案承诺的"下一次取号会再试"是空头支票，
		 * 归档恢复可读后号仍停在重建后的低位、持续与归档重叠。
		 */
		final AtomicBoolean repairDegraded = new AtomicBoolean();

		/** 最后触达时刻（毫秒）：到期清理与超上限淘汰都按它排序。 */
		volatile long lastTouchMs;

		RunState(long nowMs) {
			this.lastTouchMs = nowMs;
		}

	}

	@Autowired
	public RedisFrameStore(StringRedisTemplate redis, ObjectMapper mapper, AiProperties properties) {
		this(redis, mapper, properties, STREAM_WINDOW);
	}

	RedisFrameStore(StringRedisTemplate redis, ObjectMapper mapper, AiProperties properties, int streamWindow) {
		this(redis, mapper, properties, streamWindow, System::currentTimeMillis);
	}

	/** 包内构造器（单测）：额外注入时钟，让"归档到期"可确定性推进。 */
	RedisFrameStore(StringRedisTemplate redis, ObjectMapper mapper, AiProperties properties, int streamWindow,
			LongSupplier clock) {
		this.redis = redis;
		this.mapper = mapper;
		this.properties = properties;
		this.streamWindow = streamWindow > 0 ? streamWindow : STREAM_WINDOW;
		this.clock = clock != null ? clock : System::currentTimeMillis;
		this.lastSweepMs.set(this.clock.getAsLong());
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

	/**
	 * 取号（<b>一次原子脚本</b>）：{@code INCR} → 必要时按归档末号抬号 → {@code EXPIRE}。
	 *
	 * <h2>为什么必须是脚本而不是"INCR + 修复"两条命令（资源/帧序号修复批）</h2>
	 * {@code ai:seq} 键可能因 24h 无写而过期、被 {@code deleteRun} 删掉，或 Redis 抖动期改走了
	 * 进程内降级号 —— 三种情形都会让 {@code INCR} 从 1 重来，于是新帧与历史帧同号：
	 * {@code mergeBySeq} 同号合并丢一帧、前端 {@code lastSeq} 倒退（M-1）。
	 *
	 * <p>
	 * 旧修复是"读归档末号 → {@code SET ai:seq = anchor} → {@code INCR}"：读与写<b>不是原子的</b>，
	 * 而且 {@code SET} 是<b>无条件写</b> —— 并发下只有"拿到 1 号"的线程会去修复，
	 * 同一瞬间拿到 2..N 的线程已经把 2..N 交给出帧方并返回：如果归档里正好有 2..N，
	 * 那一轮就复用了已发（已落档）的号。本类把三件事收进<b>同一段脚本</b>：
	 * Redis 串行执行脚本 ⇒ 后到者看到的必然是已抬高的计数器，
	 * <b>任何并发（含跨实例）都不会复用已发号</b>。
	 *
	 * <p>
	 * 脚本只在"键刚重建（{@code INCR} 返回 1）"、"号落后于本进程降级号"或"上一次取号是降级结果
	 * （{@code forceRepair}）"时才读归档末号（{@code XREVRANGE} 取末尾 {@value #TAIL_PROBE} 条的最大
	 * {@code seq}，再并上兼容期 LIST 末帧），常规路径就只是一次 {@code INCR} + {@code EXPIRE} ——
	 * 比"INCR、EXPIRE 两次往返"还少一次。
	 *
	 * <h2>锚点读不出时的三条退让（评审 I4）</h2>
	 * <ol>
	 * <li><b>刚重建 + 锚点读不出 ⇒ 不交出这个 1</b>：归档里可能已有 1..N，交出去等于复用已发号。
	 * 脚本返回 {@code {0, degraded}}，本方法带 {@code forceRepair} <b>立刻重试</b>一次
	 * （瞬时抖动通常第二拍即恢复 ⇒ 号直接跳到归档末号之后）。第二拍仍不可读时，发出的是
	 * 计数器上的下一个号（{@code 2}、{@code 3}…）—— 它与归档**可能**重叠（末号不可读时没有可证明
	 * 唯一的号），但<b>不会</b>再是发放口刚重建的那个低号；同时置降级位 + WARN 留痕，
	 * 归档一旦恢复可读，下一次取号即跳到末号之后。</li>
	 * <li><b>号落后于降级号 + 锚点读不出 ⇒ 至少跳到降级号 + 1</b>：抬号锚点以本进程降级号为底，
	 * 因此绝不与"已经发出去的降级号"重叠。</li>
	 * <li><b>降级结果要能自我修复</b>：回包带 {@code degraded} ⇒ 记 {@link RunState#repairDegraded}，
	 * <b>下一次取号</b>带 {@code forceRepair} 重读末号并原子抬号，成功即清位 ——
	 * 而不是只在文案里承诺"下次再试"。</li>
	 * </ol>
	 *
	 * <p>
	 * <b>可观测</b>：降级/拒绝都计入 {@code /api/ai/health} 的降级面（WARN 每轮一次 + 取号回退到
	 * 进程内计数时另有一条 WARN）；脚本回包的编解码兼容 {@code Number}/{@code String}/{@code byte[]}
	 * （不同客户端与序列化器的整数回复类型不同，只认 {@code Number} 会把正常取号误判成失败）。
	 */
	@Override
	public long nextSeq(String runId) {
		RunState state = state(runId);
		try {
			for (int attempt = 0; attempt < SEQ_ALLOCATE_ATTEMPTS; attempt++) {
				SeqAllocation allocation = allocate(runId, state);
				if (allocation.degraded()) {
					// 记下降级位：下一次取号据此强制重读末号（自我修复），而不是"只承诺不重试"。
					state.repairDegraded.set(true);
					if (state.repairDegradedWarned.compareAndSet(false, true)) {
						log.warn("号回退修复降级 runId={}（归档末号读不出，本次按自增号返回；后续取号会持续重试抬号）",
								runId);
					}
				}
				else {
					state.repairDegraded.set(false);
				}
				if (allocation.seq() > 0L) {
					return allocation.seq();
				}
				// seq == 0 = 脚本拒绝交出"刚重建且无法证明未被用过"的低号 ⇒ 带 forceRepair 再试一次。
			}
			throw new IllegalStateException("取号脚本无法确认唯一性（归档末号连续读失败）");
		}
		catch (Exception ex) {
			long fallback = nextFallbackSeq(runId);
			log.warn("序号发放降级为进程内计数 runId={} seq={}（Redis 取号失败，跨实例不重承诺暂不可用）：{}",
					runId, fallback, ex.getMessage());
			return fallback;
		}
	}

	/**
	 * 执行一次取号脚本（{@code forceRepair} 由上一条结论决定），并把回包解码成
	 * {@code {seq, degraded}}（{@code seq == 0} = 脚本拒绝交出低号）。
	 */
	private SeqAllocation allocate(String runId, RunState state) {
		long fallbackLast = Math.max(0L, state.fallbackSeq.get());
		long ttlSeconds = Math.max(1L, ttl().toSeconds());
		List<?> result = this.redis.execute(SEQ_NEXT_SCRIPT,
				List.of(seqKey(runId), streamKey(runId), legacyListKey(runId)),
				String.valueOf(ttlSeconds), String.valueOf(fallbackLast), String.valueOf(TAIL_PROBE),
				state.repairDegraded.get() ? "1" : "0");
		if (result == null || result.isEmpty()) {
			throw new IllegalStateException("取号脚本返回空");
		}
		long seq = scriptSeq(result.get(0));
		boolean degraded = result.size() > 1 && scriptSeq(result.get(1)) != 0L;
		return new SeqAllocation(seq, degraded);
	}

	/**
	 * 脚本回包元素的解码（评审 I4）：兼容 {@code Number} / {@code String} / {@code byte[]}。
	 *
	 * <p>同一段脚本在不同客户端/序列化器下回来的是不同类型（Lettuce 整数回复 / 字符串编解码 /
	 * 原始字节编解码）。只认 {@code Number} 会把后两种误判成"取号失败"⇒ 静默掉进进程内降级号
	 * （跨实例不重号承诺失效）。解不出来的值一律按 0 处理（调用方会走"拒绝/重试/降级"的既定路径）。
	 */
	private static long scriptSeq(Object value) {
		if (value == null) {
			return 0L;
		}
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value instanceof byte[] bytes) {
			return parseSeq(new String(bytes, StandardCharsets.UTF_8));
		}
		return parseSeq(String.valueOf(value));
	}

	private static long parseSeq(String text) {
		try {
			return Long.parseLong(text.trim());
		}
		catch (RuntimeException ex) {
			return 0L;
		}
	}

	/** 一次脚本取号的结果：{@code seq <= 0} = 脚本拒绝交出低号（需重试/降级）。 */
	private record SeqAllocation(long seq, boolean degraded) {
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
		// 容量治理（Redis 容量专项 A）：先落"本轮见过的最大载荷"，再据它算本条落档用的窗口 ——
		// 于是本条自身也落在裁剪后的窗口内，不会留下"先写大帧、下一帧才收窄"的超预算窗口。
		RunState state = state(runId);
		int window = windowForPayload(state.observedPayloadBytes.accumulateAndGet(payload.length, Math::max));
		noteByteGovernance(runId, state, window);
		try {
			xadd(key, seq + "-0", seq, payload, ttlSeconds, window);
		}
		catch (Exception ex) {
			// 显式 ID 被拒 = 物理序已经越过这个号（跨实例并发出帧 / 降级号回退）。帧不能丢：
			// 改用自动 ID 追加（物理位次靠后，但 seq 字段仍在，读侧按 <afterSeq+1>-0 起读 + 按 seq 过滤仍可读到）。
			if (state.outOfOrderWarned.compareAndSet(false, true)) {
				log.warn("事件日志显式 ID 落档被拒 runId={} seq={}（改用自动 ID 兜底；物理序与 seq 序将不再一致）：{}",
						runId, seq, ex.getMessage());
			}
			try {
				xadd(key, "*", seq, payload, ttlSeconds, window);
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
	 * <p><b>MAXLEN 的窗口</b>由 {@link #windowForPayload(long)} 给出（Redis 容量专项 A）：条数窗口
	 * {@link #STREAM_WINDOW} 与字节预算推导出的等效窗口取小；命令行仍是 {@code MAXLEN ~ n}（近似裁剪），
	 * 语义与 5.0 / Cluster 完全兼容 —— 没有引入新命令，也没有按字节裁剪（那需要 {@code MEMORY USAGE}
	 * 之类的额外协议）。
	 *
	 * @param idToken 显式 ID（{@code "<seq>-0"}）或 {@code "*"}（乱序兜底）
	 * @param window  本条落档用的 MAXLEN 窗口（&gt; 0）
	 */
	private void xadd(byte[] key, String idToken, long seq, byte[] payload, long ttlSeconds, int window) {
		this.redis.execute((RedisCallback<Object>) connection -> {
			connection.execute("XADD", key,
					bytes("MAXLEN"), bytes("~"), bytes(String.valueOf(window)),
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
	 *
	 * <p><b>窗口口径与写侧同源</b>（Redis 容量专项 A）：{@code 窗口}取自
	 * {@link #effectiveWindow(String)}（条数窗口与字节预算推导出的等效窗口取小），不是常量
	 * {@link #STREAM_WINDOW} —— 写侧按字节预算裁得更短时，读侧若仍按 6000 夹取，会把"已经不存在
	 * 的区间"当成应读区间，缺口就伪装成"没有更多帧"。
	 */
	private FrameStore.ReadResult streamFramesAbove(String runId, long afterSeq) {
		StreamTail tail = streamTailSeq(runId);
		int window = effectiveWindow(runId);
		boolean degraded = !tail.ok();
		long from;
		if (tail.ok()) {
			if (tail.seq() <= afterSeq) {
				return new FrameStore.ReadResult(List.of(), false);
			}
			from = Math.max(afterSeq, tail.seq() - window);
			if (afterSeq > 0L && from > afterSeq) {
				// 游标早于幸存窗口：本次回放<B>确实跳过</B>了一段。degraded 的语义是"读失败/不可回 503"，
				// 不能拿来表达"落后了"，故这里只计数 + debug 留痕（客户端可见的缺口标记需要 emitter/协议
				// 配合，本批禁改 SseChatEmitter）；至少不许把"跳过了"说成"没有新帧"。
				RedisDegradation.record(RedisDegradation.C1_STREAM_REPLAY_GAP);
				log.debug("事件日志回放跳过已裁区间 runId={} afterSeq={} from={} 有效窗口={}", runId, afterSeq, from,
						window);
			}
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
						org.springframework.data.redis.connection.Limit.limit().count(window));
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

	// ── 容量治理（Redis 容量专项 A） ──────────────────────────────────────────

	/**
	 * 载荷字节 → 本条落档可用的窗口（条）。<b>纯函数</b>，写侧读侧共用同一口径。
	 *
	 * <p>口径：{@code clamp(byte-budget ÷ (载荷 + 固定开销), 下限, 条数窗口)}。
	 * 证明：此后每条记录 ≤ {@code 载荷 + 固定开销} ⇒ {@code 窗口 × 每条上界 ≤ byte-budget}
	 * （{@code MAXLEN ~} 的近似裁剪还会多留几条，量级 ≤ 宏节点条数；取舍见
	 * {@link #ENTRY_OVERHEAD_BYTES}）。
	 *
	 * <p><b>{@code byte-budget = 0} = 关闭字节治理</b>（只按条数窗口）：显式逃生门，
	 * 不是"静默按 0 裁剪"（0 若参与除法会让窗口恒落下限）。
	 */
	int windowForPayload(long payloadBytes) {
		long budget = byteBudget();
		if (budget <= 0L) {
			return this.streamWindow;
		}
		long perEntry = Math.max(0L, payloadBytes) + ENTRY_OVERHEAD_BYTES;
		long byBudget = budget / perEntry;
		if (byBudget >= this.streamWindow) {
			return this.streamWindow;
		}
		return (int) Math.max(minWindow(), byBudget);
	}

	/** 本轮的读侧窗口（与写侧同源：用同一份"观察到的最大载荷"）；未见过落档 = 只按条数窗口。 */
	private int effectiveWindow(String runId) {
		RunState state = this.runStates.get(runId);
		return windowForPayload(state == null ? 0L : state.observedPayloadBytes.get());
	}

	/**
	 * 字节预算成为实际约束时留痕（降级计数 + 每轮一次 WARN）。
	 *
	 * <p>两类事件分开计：
	 * <ul>
	 * <li>{@link RedisDegradation#C1_STREAM_BYTE_CAP}：预算把窗口压到条数窗口以下（正常治理，
	 * 代价 = 回放深度变小）；</li>
	 * <li>{@link RedisDegradation#C1_STREAM_BYTE_FLOOR}：预算连下限窗口都撑不住（
	 * 单帧 &gt; 预算/下限）—— 保守界<b>兑现不了</b>，必须让运维看见（调 byte-budget 或查单帧超限）。</li>
	 * </ul>
	 * 计数按"被约束的落档次数"累加（量级可读），日志按轮去重（避免一个长轮刷几千行）。
	 */
	private void noteByteGovernance(String runId, RunState state, int window) {
		if (window >= this.streamWindow) {
			return;
		}
		long observedMax = state.observedPayloadBytes.get();
		long byBudget = byteBudget() / Math.max(1L, observedMax + ENTRY_OVERHEAD_BYTES);
		if (byBudget < minWindow()) {
			RedisDegradation.record(RedisDegradation.C1_STREAM_BYTE_FLOOR);
			if (state.byteFloorWarned.compareAndSet(false, true)) {
				log.warn("事件日志容量预算不足以下限窗口 runId={} 预算={}B 本轮最大帧={}B 下限窗口={}（仍保留 {} 条，"
						+ "单轮字节会超出预算；请调大 app.ai.stream.byte-budget 或核查单帧上限）", runId, byteBudget(),
						observedMax, minWindow(), window);
			}
			return;
		}
		RedisDegradation.record(RedisDegradation.C1_STREAM_BYTE_CAP);
		if (state.byteCapWarned.compareAndSet(false, true)) {
			log.warn("事件日志按字节预算裁剪 runId={} 预算={}MiB 本轮最大帧={}B 有效窗口={}（条数窗口 {}）："
					+ "更早的帧不再可回放（增量读取不受影响）", runId, byteBudget() / (1024 * 1024), observedMax, window,
					this.streamWindow);
		}
	}

	private long byteBudget() {
		AiProperties.Stream config = this.properties == null ? null : this.properties.getStream();
		return config == null ? 0L : config.getByteBudget();
	}

	/** 下限窗口：不得把条数窗口放大（测试用的小窗口 8 仍是 8）。 */
	private int minWindow() {
		return Math.min(MIN_WINDOW, this.streamWindow);
	}

	// ── 进程内簿记的生命周期（终态回收 / 到期与超上限清理） ──────────────────

	/**
	 * 终态释放（{@link FrameStore#forget(String)}）：丢掉该轮的进程内簿记。
	 *
	 * <p>只清进程内状态，<b>不动 Redis</b>：归档与 {@code ai:seq} 仍在原有 TTL 下自然到期，
	 * 之后的重挂/续跑按归档回放；簿记重建时按归档末号重锚，因此不会因回收而回退号。
	 */
	@Override
	public void forget(String runId) {
		this.runStates.remove(runId);
	}

	/** 当前进程内仍持有簿记的轮数（诊断/断言用）。 */
	int trackedRuns() {
		return this.runStates.size();
	}

	/** 取（必要时新建）本轮簿记并推进"最后触达"，顺带做一次到期清理（有节流）。 */
	private RunState state(String runId) {
		long now = this.clock.getAsLong();
		RunState state = this.runStates.computeIfAbsent(runId, id -> new RunState(now));
		state.lastTouchMs = now;
		sweepIfDue(now);
		return state;
	}

	/**
	 * 到期/超上限清理（有节流）：距上次清理不足 {@link #SWEEP_INTERVAL_MS} 且未超上限 ⇒ 直接返回。
	 *
	 * <p>清理动作只允许一拍执行（{@code lastSweepMs} 的 CAS），其余线程照常出帧 ——
	 * 清理不是出帧的前置条件，绝不让它把写路径串行化。
	 */
	private void sweepIfDue(long now) {
		long last = this.lastSweepMs.get();
		boolean overCap = this.runStates.size() > MAX_TRACKED_RUNS;
		if (!overCap && now - last < SWEEP_INTERVAL_MS) {
			return;
		}
		if (!this.lastSweepMs.compareAndSet(last, now)) {
			return;
		}
		sweepIdle(now);
	}

	/**
	 * 清掉"归档已到期"的簿记，并把总量压回 {@link #MAX_TRACKED_RUNS}。
	 *
	 * <p>两条界：
	 * <ol>
	 * <li><b>到期</b>：最后触达早于 session TTL（= 归档键的 TTL）⇒ 该轮的帧/序号键已过期，
	 * 进程内簿记（降级号、观察到的最大载荷、告警位）不再有任何读者；</li>
	 * <li><b>硬上限</b>：仍超过 {@link #MAX_TRACKED_RUNS} 时按最后触达时刻从旧到新淘汰 ——
	 * 取号正确性不依赖这些簿记（锚点在 Redis），被淘汰的轮下一帧会按归档末号重锚。</li>
	 * </ol>
	 */
	void sweepIdle(long nowMs) {
		long retentionMs = retentionMs();
		if (retentionMs > 0L) {
			this.runStates.entrySet().removeIf(entry -> nowMs - entry.getValue().lastTouchMs > retentionMs);
		}
		int overflow = this.runStates.size() - MAX_TRACKED_RUNS;
		if (overflow <= 0) {
			return;
		}
		List<Map.Entry<String, RunState>> byAge = new ArrayList<>(this.runStates.entrySet());
		byAge.sort((left, right) -> Long.compare(left.getValue().lastTouchMs, right.getValue().lastTouchMs));
		for (int i = 0; i < overflow && i < byAge.size(); i++) {
			Map.Entry<String, RunState> eldest = byAge.get(i);
			this.runStates.remove(eldest.getKey(), eldest.getValue());
		}
	}

	/** 簿记的保留时长 = 归档键的 TTL（归档没了，簿记也就没有读者）。 */
	private long retentionMs() {
		Duration ttl = this.properties == null ? null : this.properties.getSessionTtl();
		return ttl == null ? 0L : ttl.toMillis();
	}

	// ── 内部 ────────────────────────────────────────────────────────────────

	/**
	 * 降级号（Redis 取号失败期间的进程内单调兜底）：首次使用时按
	 * {@code max(归档末号, ai:seq)} 锚定，之后只增。
	 *
	 * <p>锚定用 CAS({@link RunState#UNINITIALIZED} → 锚点)，并发首次降级不会重复读归档。
	 * <b>{@code ai:seq} 必须计入</b>：它记着"已经发出、还没落档"的号（出帧与落档之间本就不是原子的），
	 * 只按归档末号锚定会把降级期的第一个号发成"已发出但未落档"的那个号 —— 复用已发 seq。
	 * 读 {@code ai:seq} 失败（降级期常见）时退回归档末号，与旧口径一致。
	 * 该锚点也参与 {@link #repairRollback} 的抬号（恢复后的 Redis 号必须跳过降级期发出的号）。
	 */
	private long nextFallbackSeq(String runId) {
		RunState state = state(runId);
		if (state.fallbackSeq.get() == RunState.UNINITIALIZED) {
			long anchor = Math.max(Math.max(0L, lastSeq(runId)), issuedSeqOf(runId));
			state.fallbackSeq.compareAndSet(RunState.UNINITIALIZED, anchor);
		}
		return state.fallbackSeq.incrementAndGet();
	}

	/** {@code ai:seq} 的当前值（已发放的最大号；读不到 = 0，降级期按归档末号锚定）。 */
	private long issuedSeqOf(String runId) {
		try {
			String raw = this.redis.opsForValue().get(seqKey(runId));
			return raw == null ? 0L : Math.max(0L, Long.parseLong(raw));
		}
		catch (Exception ex) {
			return 0L;
		}
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
