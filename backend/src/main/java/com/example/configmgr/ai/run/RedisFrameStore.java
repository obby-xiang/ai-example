package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.example.configmgr.ai.tenant.TenantKeyScope;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 事件日志的 Redis Stream 实现（B23L1 批① / 设计稿 §2.1）——<b>生产唯一实现</b>，
 * 单实例与多实例同一份代码。
 *
 * <h2>键族</h2>
 * <p>三族键名都带身份化的 {@code {<tenantId>:<userId>:run:<runId>}} hash tag
 * （{@code <prefix>{[<t>:<u>:]run:<runId>}}，见 {@link RunStore#runScopedKey(String, String)}）：
 * 它们与快照/台账等同属 {@code deleteRun} 的
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
 * 仍把它串在 Stream 前面（按 {@code seq} 拼接去重），TTL 到期自然消亡，不做搬迁；</li>
 * <li>{@code ai:xack:<token>:<runId>} STRING —— <b>一次出帧的幂等标记</b>（{@code token → seq}，
 * 短 TTL {@link #ACK_TTL_SECONDS}，同 tag 同槽）。它让"XADD 成功但回包丢失"的重试能按
 * <b>精确 ID + 本次帧体</b>认出"这一帧已经落档"，而不是靠查表尾猜（表尾已是别人的帧）；</li>
 * <li>{@code ai:xtrim:<runId>} STRING —— <b>真实裁剪低水位</b>（值 = 最近一次 MAXLEN 裁剪后
 * 幸存首条的业务 {@code seq}，<b>只升不降</b>，随会话 TTL；见 {@link #XTRIM_PREFIX}）。
 * 只有原子落档脚本/精确号写口在<b>写前写后比对幸存首条、确实被推高</b>时才写它 ——
 * 因此"跳号、遗留墙钟 ID、从未存在的号"都不会被误报成归档被裁；读侧只按
 * {@code afterSeq + 1 < 低水位} 判截断，老流没有该键就不下结论（不臆测）；</li>
 * </ul>
 *
 * <h2>顺序口径（I-2 收口 + 显式 ID 恒定批）</h2>
 * entry ID 用<b>显式 {@code <seq>-0}</b>，且<b>只可能是显式 ID</b> ⇒ <b>物理序恒等于 seq 序</b>，
 * {@link #readFrom} 因此可以用
 * {@code XRANGE key <afterSeq+1>-0 +}（<b>闭区间</b>，见 {@code streamFramesAbove}）精确取差量
 * （O(差量)，且不依赖"低号帧一定先落"）。<b>不用排他下限 {@code (afterSeq-0}：那是 Redis 6.2 的语法，
 * 目标部署 5.0.14 会直接报错</b>，报错被 catch 折成空 ⇒ 差量读 0 帧。
 *
 * <p><b>2026-10-30（Task 2）：号与归档同一拍，落档不再改号</b>。旧口径是两步式：先用 {@code INCR}
 * 取号（可能拿到 5），落档时表尾已被别的实例的 {@code 6-0} 越过，于是<b>改号</b>成 7 重写 ——
 * "先取低号、落档改高号"。它有两条硬伤：① 实时流用旧号、回放用新号（交付号 ≠ 归档号）；
 * ② 低号帧只能挤到表尾，游标一旦越过原号，所有已越过它的订阅者就永久缺那一帧。
 * 现在出帧路径走 {@link #appendAndAllocateSeq}：<b>一次脚本</b>里完成「按计数器/表尾定最终号 →
 * 追加显式 ID {@code <seq>-0} → 续期 → 幂等标记」，脚本参数里带号占位
 * （{@link #newSeqPlaceholder()}：每次出帧随机的 18 位数字）的前后半段，脚本把它替换成最终号再写 —— 于是
 * <b>分配序 == 归档序 == 交付序</b>，归档字节就是交付字节。
 *
 * <p><b>跳号正常，连续不是保证</b>：{@code seq} 每轮唯一、单调、严格递增，但<b>允许跳号</b>
 * （降级重试、遗留墙钟 ID、失败后重试都会跳）。单实例无竞争时号看起来连续，<b>那只是单实例形态</b>；
 * 单实例 conformance 的 "+1 连续"断言<b>不等于</b>跨实例连续保证。
 *
 * <p><b>显式号写口（{@link #append}）被表尾越过时不再改号</b>：该帧<b>不落档</b>（只续期/幂等命中或 WARN），
 * 因为"归档序严格递增"是唤醒差量读与订阅者游标的前提 —— 未来落档一个更低号的新帧会让所有
 * 已越过该号的读者永久缺帧。
 *
 * <h2>降级</h2>
 * Redis 抖动时：<b>落档失败即不交付</b>（帧留在出帧侧丢弃，WARN + 计数），绝不"先发出去再说" ——
 * 没有归档就没有可交付的顺序事实。取号脚本失败仍降级为进程内单调计数（WARN 一次/轮），
 * 但那个号<b>只服务非出帧调用方</b>，<b>不</b>用于实时交付（出帧只用归档成功的号）。
 * <b>不得</b>在 Redis 不可用时宣称多实例顺序成立。
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

	/** 末号探测读取的末尾条数（见 {@code streamTailSeq}：升级期残留的自动 ID 记录会打乱物理位次）。 */
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

	/**
	 * 号占位的<b>位宽</b>（数字位数）：18 位，恒 ≥ 任何真实号（当前最大是遗留墙钟 ID ≈13 位，
	 * 计数器号更短）⇒ 在占位下做的截断/序列化是<b>保守</b>的，最终号只会更窄，绝不超过单帧上限。
	 */
	static final int SEQ_PLACEHOLDER_DIGITS = 18;

	/** 号占位下界（含）：18 位十进制数的最小值。 */
	private static final long SEQ_PLACEHOLDER_MIN = 100_000_000_000_000_000L;

	/** 号占位：<b>每次出帧随机</b>的 18 位数字（见 {@link #newSeqPlaceholder()}）。 */
	static long newSeqPlaceholder() {
		// 随机而不是常量：占位只需"在本次负载里唯一"。常量（旧口径是 Long.MAX_VALUE）
		// 会被帧体里任何一处同值/同串（嵌套 seq 字段、工具参数投影）撞上，逼出"非原子退化"。
		return ThreadLocalRandom.current().nextLong(SEQ_PLACEHOLDER_MIN, 1_000_000_000_000_000_000L);
	}

	/** 原子落档脚本的标记（单测替身按它分派语义，正文改动必须同步改替身）。 */
	static final String APPEND_MARK = "AI_FRAME_APPEND";

	/** 幂等标记键前缀（{@code ai:xack:<token>:<tag>}；同槽，短 TTL）。 */
	static final String ACK_PREFIX = "ai:xack:";

	/**
	 * <b>归档裁剪低水位</b>键前缀（{@code ai:xtrim:<runId>}，同槽）：值 = 最近一次真实裁剪后
	 * 幸存的<b>首条序号</b>，只升不降，随会话 TTL。
	 *
	 * <p>它不是"末号/窗口大小"的派生值，而是<b>事实记录</b>：只有原子落档脚本在 XADD 前后
	 * 观察到"幸存首条被推高"（= MAXLEN 真的丢了条目）时才写入。读侧据此判定
	 * {@code afterSeq + 1 < 低水位} 为截断 —— 于是<b>跳号、遗留墙钟 ID、从未存在的号</b>
	 * 都不会被误报成"归档被裁"（那是本批要消灭的误报）。老流没有该键 ⇒ 不臆测。
	 */
	static final String XTRIM_PREFIX = "ai:xtrim:";

	/**
	 * 幂等标记的存活时长（秒）：只需覆盖"这次出帧的重试窗口"（秒级），
	 * 不随会话 TTL 留存 —— 否则每帧多一个长期键。
	 */
	static final long ACK_TTL_SECONDS = 120L;

	/**
	 * <b>原子落档脚本</b>（{@code EVAL}；{@code KEYS} <b>五键</b>同槽 —— seq / xevents / events / ack / xtrim，
	 * 全部带同一 {@code {run:<runId>}} hash tag，Cluster 合法）。
	 *
	 * <p>{@code KEYS}（<b>五键</b>，全部带同一 {@code {run:<runId>}} hash tag ⇒ 同槽，Cluster 合法）：
	 * {@code [ai:seq, ai:xevents, ai:events, ai:xack:<token>:, ai:xtrim]}。
	 * {@code ARGV}：{@code [session-ttl, 本进程降级号, 末号探测条数, 窗口, forceRepair, 幂等标记 TTL,
	 * token, 负载前半段, 负载后半段]}。返回 {@code {seq, degraded, idempotent}}：
	 * {@code seq == 0} = 拒绝交出低号（刚重建 + 末号不可读），{@code idempotent == 1} = 这一帧<b>已经</b>归档。
	 *
	 * <p><b>一次脚本里完成四件事</b>（顺序不可拆）：
	 * <ol>
	 * <li><b>幂等判定</b>：同 token 已记过号 ⇒ 按<b>精确 ID</b>（{@code <seq>-0}）读出那条记录，
	 *     并把它与<b>本次出帧的帧体</b>逐字节比对；一致才算"已归档"。<b>不看表尾</b>
	 *     —— 表尾这时可能已经是别的实例的帧，按它判会把"我的帧已成功"误判成"没成功"，
	 *     于是重复归档同一个逻辑帧（前端按号去重也救不回：两个号两帧）；</li>
	 * <li><b>定最终号</b>：{@code INCR}（跨实例唯一）+ 单调保险（表尾已被越过 ⇒ {@code INCRBY} 抬到表尾之后）；
	 *     计数器刚重建/上一拍降级时按归档末号与兼容期 LIST 末帧重锚（只抬不压）；</li>
	 * <li><b>归档</b>：把号替换进负载（{@code head .. seq .. tail}）后
	 *     {@code XADD MAXLEN ~ 窗口 <seq>-0 seq <n> frame <body>}，并落幂等标记、续期两族键；</li>
	 * <li><b>拒绝不可证的号</b>：末号读不出且计数器刚重建时返回 0（调用方带 forceRepair 重试），
	 *     绝不把一个"可能与归档同号"的号发出去。</li>
	 * </ol>
	 *
	 * <p><b>为什么必须在脚本里</b>：Redis 串行执行脚本 ⇒ "定号"与"归档"之间没有别的实例能插入，
	 * 于是<b>分配序 == 归档序</b>：不会出现"取了 5 号却只能落到 7 号"，也就不会出现"低号帧后到、
	 * 被所有已越过它的游标永久跳过"。XADD 自身还会兜底拒绝"ID ≤ 表尾"，所以即便降级也不会乱序。
	 */
	private static final String APPEND_BODY = """
			-- AI_FRAME_APPEND v1: 幂等判定 -> 定最终号 -> XADD(<seq>-0) -> 幂等标记 -> 续期，一次脚本一次往返
			local ttl = tonumber(ARGV[1]) or 1
			local fallbackLast = tonumber(ARGV[2]) or 0
			local probe = tonumber(ARGV[3]) or 16
			local window = tonumber(ARGV[4]) or 6000
			local forceRepair = tonumber(ARGV[5]) or 0
			local ackTtl = tonumber(ARGV[6]) or 120
			local token = ARGV[7]
			local head = ARGV[8]
			local tail = ARGV[9]
			-- 归档裁剪低水位（KEYS[5]）：只有"XADD 前后幸存首条被推高"（MAXLEN 真的丢了条目）才写。
			-- 幸存首条的<b>业务 seq</b>（读者按业务号比较；升级期遗留记录也有 seq 字段）。
			local function headSeq()
			  local ok, entries = pcall(redis.call, 'XRANGE', KEYS[2], '-', '+', 'COUNT', 1)
			  if not ok or type(entries) ~= 'table' or not entries[1] then return nil end
			  local fields = entries[1][2]
			  if type(fields) == 'table' then
			    for j = 1, #fields, 2 do
			      if fields[j] == 'seq' then
			        local v = tonumber(fields[j + 1])
			        if v then return v end
			      end
			    end
			  end
			  -- 没有业务 seq 字段 ⇒ <b>无法确认</b>：不得拿 ID 毫秒段顶替（那会把墙钟量级写进水位、
			  -- 让所有读者都被判成"被裁过"）。返回 nil ⇒ 本次不记水位（读侧也就不报截断）。
			  return nil
			end
			local headBefore = headSeq()
			-- KEYS[4] 已是完整键（含本次出帧的 token）：脚本不得再拼一次（否则键名里 token 出现两次，
			-- 且 tag 之后多出一段，Cluster 槽与"按 token 复用"的语义都会漂移）。
			local ackKey = KEYS[4]
			local prev = redis.call('GET', ackKey)
			if prev then
			  local prevSeq = tonumber(prev)
			  if prevSeq then
			    local body = head .. prev .. tail
			    local okEntry, entry = pcall(redis.call, 'XRANGE', KEYS[2], prev .. '-0', prev .. '-0', 'COUNT', 1)
			    if okEntry and type(entry) == 'table' and entry[1] then
			      local fields = entry[1][2]
			      local same = false
			      if type(fields) == 'table' then
			        for j = 1, #fields, 2 do
			          if fields[j] == 'frame' then
			            same = (fields[j + 1] == body)
			          end
			        end
			      end
			      if same then
			        -- 幂等命中：这一拍没有写新记录，但"这一帧仍在窗口里"必须由续期维持
			        -- （否则重试期间 stream/seq 的滑动 TTL 会倒退，归档可能先于会话过期）。
			        redis.call('EXPIRE', ackKey, ackTtl)
			        redis.call('EXPIRE', KEYS[1], ttl)
			        redis.call('EXPIRE', KEYS[2], ttl)
			        return {prevSeq, 0, 1}
			      end
			    end
			  end
			end
			local cur = redis.call('INCR', KEYS[1])
			local degraded = 0
			local tailMs = 0
			local okTail, lastEntries = pcall(redis.call, 'XREVRANGE', KEYS[2], '+', '-', 'COUNT', 1)
			if not okTail then
			  degraded = 1
			elseif type(lastEntries) == 'table' and lastEntries[1] then
			  local idText = tostring(lastEntries[1][1])
			  tailMs = tonumber(string.match(idText, '^(%d+)') or '0') or 0
			end
			if cur == 1 or forceRepair == 1 then
			  local anchor = math.max(fallbackLast, tailMs)
			  if degraded == 0 then
			    local okProbe, entries = pcall(redis.call, 'XREVRANGE', KEYS[2], '+', '-', 'COUNT', probe)
			    if okProbe and type(entries) == 'table' then
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
			    elseif not okProbe then
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
			  end
			  if anchor >= cur then
			    -- INCRBY 的增量必须显式格式化成整数：Lua 的 number→string 在 ≥1e14 时是科学计数法
			    -- （"1e+12"），Redis 会直接拒（value is not an integer or out of range）——
			    -- 而遗留墙钟 ID ≈9e12 恰恰把号推到那个量级。
			    cur = redis.call('INCRBY', KEYS[1], string.format('%.0f', anchor + 1 - cur))
			  end
			elseif tailMs >= cur then
			  -- 单调保险：表尾已被越过（遗留墙钟自动 ID / 降级号回退）⇒ 原子抬到表尾之后，绝不压号
			  cur = redis.call('INCRBY', KEYS[1], string.format('%.0f', tailMs + 1 - cur))
			end
			if degraded == 1 and cur == 1 then
			  -- 刚重建的计数器 + 末号读不出：这个 1 无法证明未被用过（归档里可能已有 1..N），不交出去。
			  -- 但 INCR 已经建了这个键 —— 拒绝返回前必须补上 TTL，否则它会永久驻留（泄漏）。
			  redis.call('EXPIRE', KEYS[1], ttl)
			  return {0, 1, 0}
			end
			local seqText = string.format('%.0f', cur)
			local body = head .. seqText .. tail
			redis.call('XADD', KEYS[2], 'MAXLEN', '~', window, seqText .. '-0', 'seq', seqText, 'frame', body)
			redis.call('SET', ackKey, seqText, 'EX', ackTtl)
			redis.call('EXPIRE', KEYS[1], ttl)
			redis.call('EXPIRE', KEYS[2], ttl)
			-- 真实裁剪检测：首条从 nil/旧值被推到更大的号 ⇒ 更早的条目已经被 MAXLEN 丢掉。
			-- 记下新的首条号作为低水位（只升不降），读侧只按它判截断（不按"号是否连续"猜）。
			local headAfter = headSeq()
			if headBefore and headAfter and headAfter > headBefore then
			  local known = tonumber(redis.call('GET', KEYS[5]) or '0') or 0
			  if headAfter > known then
			    redis.call('SET', KEYS[5], string.format('%.0f', headAfter), 'EX', ttl)
			  else
			    redis.call('EXPIRE', KEYS[5], ttl)
			  end
			end
			return {cur, degraded, 0}
			""";

	/** 裁剪低水位标记脚本的标记（替身按它分派）。 */
	static final String XTRIM_MARK = "AI_XTRIM_MARK";

	/**
	 * <b>只升不降</b>地记录裁剪低水位（{@code GET → SET max → EXPIRE}），一次脚本一次往返。
	 *
	 * <p>为什么必须原子：并行的多个写口（原子落档 / 精确号落档 / 遗留写入方）都会各自观察
	 * "自己那次 XADD 之后的幸存首条"；用 {@code GET}+{@code SET} 两步会互相覆盖，低水位可能被
	 * <b>压回</b>更小的号 —— 于是"确实被裁过"的读者反而读不到截断。
	 */
	private static final String XTRIM_BODY = """
			-- AI_XTRIM_MARK v1: 裁剪低水位只升不降（KEYS[1] = ai:xtrim，ARGV = [新低水位, ttl 秒]）
			local next = tonumber(ARGV[1]) or 0
			local ttl = tonumber(ARGV[2]) or 1
			local known = tonumber(redis.call('GET', KEYS[1]) or '0') or 0
			if next > known then
			  redis.call('SET', KEYS[1], string.format('%.0f', next), 'EX', ttl)
			  return {next}
			end
			redis.call('EXPIRE', KEYS[1], ttl)
			return {known}
			""";

	private static final RedisScript<List> XTRIM_SCRIPT = xtrimScript();

	private static RedisScript<List> xtrimScript() {
		org.springframework.data.redis.core.script.DefaultRedisScript<List> script =
				new org.springframework.data.redis.core.script.DefaultRedisScript<>();
		script.setScriptText(XTRIM_BODY);
		script.setResultType(List.class);
		return script;
	}

	/** 原子落档脚本（结果 = 三元素数组）。 */
	private static final RedisScript<List> APPEND_SCRIPT = appendScript();

	private static RedisScript<List> appendScript() {
		org.springframework.data.redis.core.script.DefaultRedisScript<List> script =
				new org.springframework.data.redis.core.script.DefaultRedisScript<>();
		script.setScriptText(APPEND_BODY);
		script.setResultType(List.class);
		return script;
	}

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

	/**
	 * 原子落档的<b>最多尝试次数</b>（Task 2）：一次"原样 + 回包未知/号不可证时的重试"。
	 *
	 * <p>重试的两种触发都<b>不会</b>产生第二条记录：① 回包未知（{@code XADD} 可能已成功）——
	 * 下一拍用<b>同一个 token</b>，脚本按精确 ID + 本次帧体判幂等；② 脚本拒绝交出"刚重建且末号
	 * 读不出"的低号（返回 0）—— 下一拍带 {@code forceRepair} 重锚。取 3 见既有经验：并发竞争下
	 * 第二拍通常就落定。不收敛就<b>不交付</b>该帧（WARN），绝不退回"先发出去再说"。
	 */
	static final int APPEND_ATTEMPTS = 3;

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

		/**
		 * 已就"原子落档降级（归档末号读不出）"告警过（同一轮只吼一次，避免并发竞争期刷日志）。
		 *
		 * <p>它记录的是<b>号源降级</b>这件事：末号读不出时无法证明"本号未被用过"，
		 * 因此这一拍不宣称多实例顺序成立（帧仍会归档，但可观测面必须留痕）。
		 */
		final AtomicBoolean repairDegradedWarned = new AtomicBoolean();

		/** 已就"字节预算成为约束"告警过。 */
		final AtomicBoolean byteCapWarned = new AtomicBoolean();

		/** 已就"预算不足以下限窗口"告警过。 */
		final AtomicBoolean byteFloorWarned = new AtomicBoolean();

		/**
		 * 已就"原子落档失败（该帧未进事件日志）"告警过（每轮一次）。
		 *
		 * <p>为什么按轮节流：Redis 抖动期可能连续丢很多帧，逐帧 WARN 会把日志刷满；量级由
		 * {@link RedisDegradation#A7_FRAME_APPEND} 的计数负责（事件粒度 = 帧），日志只负责"有这回事"。
		 */
		final AtomicBoolean appendFailureWarned = new AtomicBoolean();

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

	/** 裁剪低水位键（{@code ai:xtrim:{run:<runId>}}；不存在 = 从未裁剪过）。 */
	public String trimKey(String runId) {
		return RunStore.runScopedKey(XTRIM_PREFIX, runId);
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
		appendAndGetSeq(runId, frame);
	}

	/**
	 * 落档并返回<b>实际落档号 + 归档的帧体原文</b>。
	 *
	 * <p>号就是帧体里的 {@code seq}（本实现<b>不改号</b>）：被表尾越过时该帧<b>不落档</b>
	 * （{@link AppendResult#skipped()}），因为"归档序严格递增"是唤醒差量与订阅者游标的前提。
	 * 回传的 {@code payload} 是<b>写进 Stream 的那一份字节</b>，出帧方据此让实时字节与归档逐字节一致。
	 */
	@Override
	public AppendResult appendAndGetSeq(String runId, Map<String, Object> frame) {
		return appendAndGetSeq(runId, frame, null);
	}

	/**
	 * 按<b>帧体里的号</b>精确落档（显式 ID {@code <seq>-0}），带工厂时负载在落档前生成。
	 *
	 * <p>这条口<b>不分配号</b>也不改号：{@code <seq>-0} 已被表尾越过 ⇒ 不落档（WARN）。
	 * 出帧路径用 {@link #appendAndAllocateSeq}（定号与归档同一拍）；本口服务兼容写口/单测与
	 * "号已经确定"的调用方。
	 *
	 * <p><b>幂等按精确 ID + 帧体内容判</b>（不看表尾）：{@code XADD} 抛错后只读 {@code <seq>-0}
	 * 那一条记录，帧体逐字节相同 ⇒ 视为已落档（只续期）。这条口径同时覆盖
	 * "XADD 成功但回包丢失"的重试：同一号 + 同一份字节不会再写第二条。
	 */
	@Override
	public AppendResult appendAndGetSeq(String runId, Map<String, Object> frame, PayloadFactory payloadFactory) {
		String type = String.valueOf(frame.get("type"));
		if (RunStore.HEARTBEAT_TYPE.equals(type)) {
			// T7 原样：心跳不入事件日志（活动戳由调用方 touchActivity 留痕）。
			return AppendResult.skipped();
		}
		long seq = seqOf(frame);
		if (seq <= 0L) {
			// N-4：无 seq 的帧不进 Stream（否则会写入一条 seq=0 记录，被 streamTailSeq 当成末号）。
			log.debug("事件日志跳过无 seq 帧 runId={} type={}", runId, type);
			return AppendResult.skipped();
		}
		byte[] payload = producePayload(runId, type, frame, payloadFactory);
		if (payload == null) {
			return AppendResult.skipped();
		}
		// 容量治理（Redis 容量专项 A）：先落"本轮见过的最大载荷"，再据它算本条落档用的窗口 ——
		// 于是本条自身也落在裁剪后的窗口内，不会留下"先写大帧、下一帧才收窄"的超预算窗口。
		RunState state = state(runId);
		int window = windowForPayload(state.observedPayloadBytes.accumulateAndGet(payload.length, Math::max));
		noteByteGovernance(runId, state, window);
		return appendExact(runId, type, seq, payload, Math.max(1L, ttl().toSeconds()), window);
	}

	/**
	 * <b>原子落档</b>：最终号分配与 Stream 归档在同一次 Redis 脚本里完成（见 {@link #APPEND_BODY}）。
	 *
	 * <p>口径（Task 2 的核心，缺一条即错）：
	 * <ol>
	 * <li><b>一次脚本</b>：定号（{@code INCR} + 表尾单调保险 + 计数器重建重锚）与 {@code XADD} 同一拍，
	 *     于是"分配序 == 归档序"，不再有"先取低号、被越过后改高号"的窗口；</li>
	 * <li><b>负载与最终号同源</b>：先在<b>每次出帧唯一</b>的号占位（{@link #newSeqPlaceholder()}，
	 *     18 位数字 = 恒定宽度，≥ 任何真实号）下调用工厂（截断/序列化因此是保守的），
	 *     再切出占位前后两段交给脚本，脚本把真号拼回去归档 —— 回传的 {@code payload} 是
	 *     <b>拼好的归档字节</b>，实时投递原样使用。<b>占位不唯一时明确失败</b>（该帧不落档），
	 *     绝不退化到"先取号再落档"的两步式（那会重新打开低号被越过的窗口）；</li>
	 * <li><b>未知回包按精确 ID + 本次帧体判幂等</b>：每次调用生成一个 token，脚本在归档时把它记成
	 *     {@code token → seq}（短 TTL 同槽键）。重试命中 token ⇒ 读 {@code <seq>-0} 那一条并逐字节比对
	 *     本次帧体，一致才算"已归档"。<b>不看表尾</b>（表尾那时可能已是别人的帧）；</li>
	 * <li><b>失败即不交付</b>：{@code seq <= 0} ⇒ 出帧方不扇出、不推进游标、不发唤醒；
	 *     降级（末号读不出）继续 WARN + 计数，但<b>不</b>宣称多实例顺序成立。</li>
	 * </ol>
	 *
	 * @return 归档号 + 归档字节；{@link AppendResult#skipped()} = 本次没有归档（不得交付）
	 */
	@Override
	public AppendResult appendAndAllocateSeq(String runId, Map<String, Object> frame, PayloadFactory payloadFactory) {
		String type = String.valueOf(frame.get("type"));
		if (RunStore.HEARTBEAT_TYPE.equals(type)) {
			return AppendResult.skipped();
		}
		// 1) 在"每次出帧唯一"的号占位下产负载：占位是 18 位数字，恒 ≥ 任何真实号 ⇒
		//    这里的截断判定对任何最终号都成立（最终号只会更窄）。
		long placeholder = newSeqPlaceholder();
		frame.put(FIELD_SEQ, placeholder);
		byte[] placeholderPayload = producePayload(runId, type, frame, payloadFactory);
		if (placeholderPayload == null) {
			frame.remove(FIELD_SEQ);
			return AppendResult.skipped();
		}
		SeqSplit split = SeqSplit.of(placeholderPayload, placeholder);
		if (split == null) {
			// 占位在负载里定位不到/不唯一（随机 18 位数字，正常不可能命中）：**明确失败**。
			// 绝不退化成"先取号、再按精确号落档"的两步式 —— 那条路会重新打开"取到低号却被表尾越过"
			// 的窗口，正是本批要消灭的形态。
			frame.remove(FIELD_SEQ);
			log.error("事件日志无法唯一定位号占位 runId={} type={}（本帧不落档：保持「定号与归档同一拍」，绝不降级）",
					runId, type);
			return AppendResult.skipped();
		}
		RunState state = state(runId);
		int window = windowForPayload(
				state.observedPayloadBytes.accumulateAndGet(placeholderPayload.length, Math::max));
		noteByteGovernance(runId, state, window);
		long ttlSeconds = Math.max(1L, ttl().toSeconds());
		String token = UUID.randomUUID().toString();
		boolean forceRepair = state.repairDegraded.get();
		// 回包未知的重试<b>最多一次</b>（返修第 6 条）：每次重试都是持出帧锁跑一遍脚本
		// （INCR + XADD），回包持续丢失时多试只会把锁占住更久；而同 token 的幂等判定是确定性的
		// —— 第二次认不出，第三次也不会认出。
		int unknownReplyRetries = 0;
		for (int attempt = 1; attempt <= APPEND_ATTEMPTS; attempt++) {
			AppendAllocation allocation;
			try {
				allocation = allocateAndAppend(runId, state, token, split, ttlSeconds, window, forceRepair);
			}
			catch (Exception ex) {
				// 回包未知：本拍可能已经归档（XADD 成功、回包丢了）。下一拍用<b>同一个 token</b> 重试，
				// 脚本据此按精确 ID + 本次帧体判幂等 —— 不会写出第二条。
				unknownReplyRetries++;
				if (unknownReplyRetries > 1) {
					log.warn("事件日志原子落档回包持续未知 runId={} type={}（已重试一次，不再持锁重试）：{}",
							runId, type, ex.getMessage());
					break;
				}
				log.warn("事件日志原子落档回包未知 runId={} type={} attempt={}（同 token 重试一次，按精确 ID 判幂等）：{}",
						runId, type, attempt, ex.getMessage());
				forceRepair = true;
				continue;
			}
			if (allocation.seq() > 0L) {
				byte[] body = split.render(allocation.seq());
				frame.put(FIELD_SEQ, allocation.seq());
				return new AppendResult(allocation.seq(), body);
			}
			// seq == 0：脚本拒绝交出"刚重建且无法证明未被用过"的低号 ⇒ 带 forceRepair 再试一次。
			forceRepair = true;
		}
		frame.remove(FIELD_SEQ);
		// I-4：一帧没进事件日志 = 谁也不会再看到它（重挂回放也补不回来）⇒ 必须可聚合观测。
		RedisDegradation.record(RedisDegradation.A7_FRAME_APPEND);
		if (state.appendFailureWarned.compareAndSet(false, true)) {
			log.warn("事件日志原子落档失败 runId={} type={}（该帧不落档、不交付、不推进游标：没有归档号就没有可交付的顺序；"
					+ "后续同类失败只计数不再重复告警）", runId, type);
		}
		else {
			log.debug("事件日志原子落档失败 runId={} type={}（已计数，本轮不再重复告警）", runId, type);
		}
		return AppendResult.skipped();
	}

	/** 执行一次原子落档脚本，并把回包解码成 {@code {seq, degraded, idempotent}}。 */
	private AppendAllocation allocateAndAppend(String runId, RunState state, String token, SeqSplit split,
			long ttlSeconds, int window, boolean forceRepair) {
		List<?> result = this.redis.execute(APPEND_SCRIPT,
				List.of(seqKey(runId), streamKey(runId), legacyListKey(runId), ackKey(runId, token),
						trimKey(runId)),
				String.valueOf(ttlSeconds), String.valueOf(Math.max(0L, state.fallbackSeq.get())),
				String.valueOf(TAIL_PROBE), String.valueOf(window), forceRepair ? "1" : "0",
				String.valueOf(ACK_TTL_SECONDS), token, split.headText(), split.tailText());
		if (result == null || result.isEmpty()) {
			throw new IllegalStateException("原子落档脚本返回空");
		}
		long seq = scriptSeq(result.get(0));
		boolean degraded = scriptSeq(result.get(1)) != 0L;
		boolean idempotent = result.size() > 2 && scriptSeq(result.get(2)) != 0L;
		if (degraded) {
			state.repairDegraded.set(true);
			// I-4：降级必须进统一计数（/api/ai/health 的 redis.degradations）—— 脚本里读不出的是
			// 归档末号，与 A4 的语义一致；日志每轮一次。
			RedisDegradation.record(RedisDegradation.A4_FRAME_TAIL_READ);
			if (state.repairDegradedWarned.compareAndSet(false, true)) {
				log.warn("原子落档降级 runId={}（归档末号读不出，本次按计数器号落档；下一拍带 forceRepair 重锚）", runId);
			}
		}
		else if (seq > 0L) {
			state.repairDegraded.set(false);
		}
		return new AppendAllocation(seq, degraded, idempotent);
	}

	/** 幂等标记键：{@code ai:xack:<token>:{...run:<runId>}}（与其余 run 级键同槽）。 */
	private static String ackKey(String runId, String token) {
		return RunStore.runScopedKey(ACK_PREFIX + token + ":", runId);
	}

	/** 一次原子落档的结果：{@code seq <= 0} = 没有归档；{@code idempotent} = 命中"已归档"（重试）。 */
	private record AppendAllocation(long seq, boolean degraded, boolean idempotent) {
	}

	/**
	 * 产出本条落档用的负载：带工厂时由工厂就地截断/序列化（帧体已含号占位或最终号），
	 * 否则由本类序列化帧体。失败只 WARN（该帧不落档，不抛给出帧线程）。
	 */
	private byte[] producePayload(String runId, String type, Map<String, Object> frame,
			PayloadFactory payloadFactory) {
		if (payloadFactory != null) {
			byte[] payload = payloadFactory.payload(frame);
			if (payload == null) {
				log.warn("事件日志负载生成失败 runId={} type={}（该帧未落档，跨实例不可见）", runId, type);
			}
			return payload;
		}
		try {
			return this.mapper.writeValueAsBytes(frame);
		}
		catch (Exception ex) {
			log.warn("事件日志序列化失败 runId={} type={}（该帧未落档，跨实例不可见）：{}", runId, type, ex.getMessage());
			return null;
		}
	}

	/**
	 * 以<b>显式 ID {@code <seq>-0}</b> 落档；<b>不改号、不重分配</b>（Task 2）。
	 *
	 * <p>被拒只有两种可能，收口各不相同：
	 * <ol>
	 * <li><b>同一帧的重试</b>（上一拍其实已成功 / 回包丢失）：<b>精确 ID</b> {@code <seq>-0} 那条记录
	 *     存在且帧体逐字节相同 ⇒ 幂等，只续期、不写第二条；</li>
	 * <li><b>号被更高的帧越过</b>（另一实例已用更高号归档 / 遗留墙钟自动 ID）：{@code <seq>-0} 物理上
	 *     写不进去，本帧<b>不落档</b>（WARN）—— 改号会让交付号 ≠ 归档号，挤到表尾会让归档序与分配序分叉，
	 *     两者都违反"不允许未来出现更低号的新归档帧"。</li>
	 * </ol>
	 *
	 * <p><b>绝不使用自动 ID（{@code XADD *}）</b>：自动 ID 的毫秒段是写入时刻的墙钟，一条落下就会让
	 * 此后每条显式 ID 都被拒，物理序与 seq 序永久分叉（见类注）。
	 *
	 * @return 落档号 + 归档字节；{@link AppendResult#skipped()} = 本次未落档
	 */
	private AppendResult appendExact(String runId, String type, long seq, byte[] payload, long ttlSeconds, int window) {
		byte[] key = bytes(streamKey(runId));
		// 裁剪低水位同样适用于这条写口（它也是真实生产路径）：先记下幸存首条，写后再比一次。
		Long headBefore = survivingHeadSeq(runId);
		try {
			xadd(key, seq, payload, ttlSeconds, window);
			markTrimIfHeadAdvanced(runId, headBefore, ttlSeconds);
			return new AppendResult(seq, payload);
		}
		catch (Exception ex) {
			ArchivedEntry exact = entryAt(runId, seq);
			if (exact.exists() && Arrays.equals(exact.frame(), payload)) {
				// 幂等：同一号 + 同一份帧体已在归档里（重试 / 回包丢失）—— 不写第二条，只续期。
				refreshTtl(key, ttlSeconds);
				return new AppendResult(seq, payload);
			}
			if (exact.exists()) {
				log.warn("事件日志显式号已被另一份帧占用 runId={} type={} seq={}（不覆盖、不改号：该帧不落档）", runId,
						type, seq);
			}
			else {
				log.warn("事件日志显式号落档被表尾越过 runId={} type={} seq={}（不重分配、不用自动 ID：该帧不落档）",
						runId, type, seq);
			}
			return AppendResult.skipped();
		}
	}

	/**
	 * 写后比对：幸存首条被推高 = 这次 XADD 的 {@code MAXLEN} 真的丢了条目 ⇒ 记录低水位。
	 *
	 * <p>读不出（抖动）就不记：<b>不要</b>把"读不到"写成"被裁过"。
	 */
	private void markTrimIfHeadAdvanced(String runId, Long headBefore, long ttlSeconds) {
		if (headBefore == null) {
			return;
		}
		Long headAfter = survivingHeadSeq(runId);
		if (headAfter == null || headAfter <= headBefore) {
			return;
		}
		try {
			this.redis.execute(XTRIM_SCRIPT, List.of(trimKey(runId)), String.valueOf(headAfter),
					String.valueOf(ttlSeconds));
		}
		catch (Exception ex) {
			log.debug("归档裁剪低水位记录失败 runId={} head={}（读侧本次不报截断）：{}", runId, headAfter,
					ex.getMessage());
		}
	}

	/** 幸存首条的业务 {@code seq}（空流/读不出 = {@code null}）。 */
	private Long survivingHeadSeq(String runId) {
		try {
			byte[] key = bytes(streamKey(runId));
			return this.redis.execute((RedisCallback<Long>) connection -> {
				List<ByteRecord> records = connection.xRange(key, Range.unbounded(),
						org.springframework.data.redis.connection.Limit.limit().count(1));
				if (records == null || records.isEmpty()) {
					return null;
				}
				long seq = seqFieldOf(records.get(0));
				return seq > 0L ? seq : null;
			});
		}
		catch (Exception ex) {
			log.debug("事件日志幸存首条读取失败 runId={}（本次不记录裁剪低水位）：{}", runId, ex.toString());
			return null;
		}
	}

	/** 精确 ID 上那一条记录的存在性与 {@code frame} 字段原文（幂等判定的唯一输入）。 */
	private record ArchivedEntry(boolean exists, byte[] frame) {
	}

	/**
	 * 读<b>精确 ID {@code <seq>-0}</b> 那一条记录（{@code XRANGE key <seq>-0 <seq>-0 COUNT 1}）。
	 *
	 * <p>为什么不是"查表尾"（Task 2 要求）：表尾是"最后一条"，而幂等要回答的是
	 * "我这一帧（这个号、这份字节）是否已经落档" —— 并发出帧时表尾很可能已经是别人的帧，
	 * 按表尾判定会把"我的帧已成功"误判成失败（于是重复归档）或反过来。
	 *
	 * <p>读不出来（连接抖动 / 键类型错）返回 {@code exists=false} 并 WARN + 计入 A4：
	 * 调用方据此<b>不</b>把该帧当成功（宁可这一帧不交付，也不重复归档）。
	 */
	private ArchivedEntry entryAt(String runId, long seq) {
		try {
			byte[] key = bytes(streamKey(runId));
			ArchivedEntry entry = this.redis.execute((RedisCallback<ArchivedEntry>) connection -> {
				List<ByteRecord> records = connection.xRange(key,
						Range.closed(seq + "-0", seq + "-0"),
						org.springframework.data.redis.connection.Limit.limit().count(1));
				if (records == null || records.isEmpty()) {
					return new ArchivedEntry(false, null);
				}
				return new ArchivedEntry(true, frameFieldOf(records.get(0)));
			});
			return entry == null ? new ArchivedEntry(false, null) : entry;
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.A4_FRAME_TAIL_READ, ex);
			log.warn("事件日志精确 ID 读取失败 runId={} seq={}（无法判定幂等，按未落档处理）：{}", runId, seq,
					ex.toString());
			return new ArchivedEntry(false, null);
		}
	}

	/** 号占位处的切分：{@code head} 到 {@code "seq":} 之后，{@code tail} 从占位数字之后开始。 */
	record SeqSplit(byte[] head, byte[] tail) {

		/**
		 * 按<b>本次占位</b>切分负载；{@code null} = 定位不到<b>唯一</b>一处占位。
		 *
		 * <p>占位是每次出帧随机生成的 18 位数字，因此"同一帧里出现两次"只可能来自工厂产出的
		 * 不是这份帧体（或负载被替换）。调用方据此<b>明确失败</b>，不退化。
		 *
		 * <p>要求唯一：多于一处的切分点会让"替换号"改错地方（帧体内容被破坏），
		 * 因此调用方<b>明确失败</b>（该帧不落档），也不猜、不退化。
		 */
		static SeqSplit of(byte[] payload, long placeholder) {
			byte[] marker = bytes("\"" + FIELD_SEQ + "\":" + placeholder);
			int index = indexOf(payload, marker, 0);
			if (index < 0 || indexOf(payload, marker, index + marker.length) >= 0) {
				return null;
			}
			int headEnd = index + FIELD_SEQ.length() + 3;   // "seq": 的 5 个字符（含引号与冒号）
			return new SeqSplit(Arrays.copyOfRange(payload, 0, headEnd),
					Arrays.copyOfRange(payload, index + marker.length, payload.length));
		}

		/** 把真号拼回去（归档字节 == 交付字节）。 */
		byte[] render(long seq) {
			byte[] digits = bytes(String.valueOf(seq));
			byte[] body = new byte[this.head.length + digits.length + this.tail.length];
			System.arraycopy(this.head, 0, body, 0, this.head.length);
			System.arraycopy(digits, 0, body, this.head.length, digits.length);
			System.arraycopy(this.tail, 0, body, this.head.length + digits.length, this.tail.length);
			return body;
		}

		String headText() {
			return new String(this.head, StandardCharsets.UTF_8);
		}

		String tailText() {
			return new String(this.tail, StandardCharsets.UTF_8);
		}

	}

	/** 字节级子串查找（{@code from} 起步；{@code -1} = 未找到）。 */
	private static int indexOf(byte[] haystack, byte[] needle, int from) {
		if (needle.length == 0 || haystack.length - needle.length < from) {
			return -1;
		}
		for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
			boolean match = true;
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					match = false;
					break;
				}
			}
			if (match) {
				return i;
			}
		}
		return -1;
	}

	/** 幂等命中时补一次续期（该拍没有 {@code XADD}，滑动 TTL 仍要前进）。 */
	private void refreshTtl(byte[] key, long ttlSeconds) {
		try {
			this.redis.execute((RedisCallback<Object>) connection -> {
				connection.expire(key, ttlSeconds);
				return null;
			});
		}
		catch (Exception ex) {
			log.debug("事件日志续期失败（幂等命中，按已有记录继续）：{}", ex.getMessage());
		}
	}

	/** 只取一条 Stream 记录的 {@code frame} 字段原文（幂等比对用；不反序列化帧体）。 */
	private static byte[] frameFieldOf(MapRecord<byte[], byte[], byte[]> record) {
		for (Map.Entry<byte[], byte[]> entry : record.getValue().entrySet()) {
			if (FIELD_FRAME.equals(new String(entry.getKey(), StandardCharsets.UTF_8))) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * 追加一条 Stream 记录：{@code XADD key MAXLEN ~ <窗口> <seq>-0 seq <n> frame <json>}，随后续期 TTL。
	 *
	 * <p>
	 * <b>为什么用原始命令而不是 {@code StreamOperations} / {@code RedisConnection#xAdd(record, options)}</b>：
	 * 后者无法指定 entry ID（一律自动），而本批的正确性依赖 <b>ID = {@code seq}-0</b>：
	 * 物理序恒等于 seq 序，于是"按 {@code XRANGE <afterSeq+1>-0 +} 读差量"既完整又只要 O(差量) ——
	 * 这是 I-2（物理序 ≠ seq 序时低号帧永久不可读）的根治口径。{@code MAXLEN ~} 仍走近似裁剪。
	 *
	 * <p><b>entry ID 只由本方法构造</b>（不接收调用方传入的 ID 串）：自动 ID（{@code *}）在类型上就被排除，
	 * 想退回"自动 ID 兜底"必须先改签名 —— 见 {@link #appendExact} 的"不改号"口径。
	 * <b>Task 2 起这条原始命令只服务"号已确定"的写口</b>（{@link #append} / 幂等退化路径）；
	 * 出帧路径走 {@link #APPEND_BODY}（定号与 XADD 同一次脚本，绝无"先取低号"的窗口）。
	 *
	 * <p><b>MAXLEN 的窗口</b>由 {@link #windowForPayload(long)} 给出（Redis 容量专项 A）：条数窗口
	 * {@link #STREAM_WINDOW} 与字节预算推导出的等效窗口取小；命令行仍是 {@code MAXLEN ~ n}（近似裁剪），
	 * 语义与 5.0 / Cluster 完全兼容 —— 没有引入新命令，也没有按字节裁剪（那需要 {@code MEMORY USAGE}
	 * 之类的额外协议）。
	 *
	 * @param window 本条落档用的 MAXLEN 窗口（&gt; 0）
	 */
	private void xadd(byte[] key, long seq, byte[] payload, long ttlSeconds, int window) {
		this.redis.execute((RedisCallback<Object>) connection -> {
			connection.execute("XADD", key,
					bytes("MAXLEN"), bytes("~"), bytes(String.valueOf(window)),
					bytes(seq + "-0"),
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
	 * <b>ID 与 seq 一一对应</b>（显式 ID 恒定批）：落档只用 {@code <seq>-0}，被表尾越过时改的是
	 * <b>号</b>而不是 ID 形态（见 {@link #append}）。因此流里不存在"ID 远大于 seq 的自动 ID 记录"，
	 * 读取不必为此退化成整窗：下限 {@code <afterSeq+1>-0} 就是精确的差量边界。
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
		// truncated = "本次回放确实跳过了不可回放的区间"（来自真实裁剪低水位，不看号是否连续）。
		// 缺口区间 [trimmedFromSeq, trimmedToSeq] 一并带出：reattach 用 archive-from/archive-to 暴露。
		return new FrameStore.ReadResult(result, degraded, stream.truncated(), stream.trimmedFromSeq(),
				stream.trimmedToSeq());
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
	 * Stream 差量读：<b>按条目</b>取 {@code seq > afterSeq} 的最近 {@code 窗口} 条（升序）。
	 *
	 * <p><b>为什么是闭区间 {@code (afterSeq+1)-0}</b>：排他下限（{@code (id}）是 Redis 6.2 的语法，
	 * 目标部署 <b>5.0.14 会把 {@code (123-0} 当非法 stream ID 直接报错</b>（真实响应
	 * {@code ERR Invalid stream ID specified as stream command argument}），报错被 catch 折成空 ⇒
	 * 差量读 0 帧。记录 ID 恒为 {@code <seq>-0}（显式 ID，没有自动 ID），故
	 * {@code ≥ (afterSeq+1)-0 ⇔ seq > afterSeq}，语义等价且 5.0 可用。
	 *
	 * <p><b>窗口按条目、不按 seq 区间</b>（Task 2 / I-2）：旧口径
	 * {@code from = max(afterSeq, tailSeq − 窗口)} 把"条数窗口"当成"seq 区间" ——
	 * 生产上<b>跳号是正常形态</b>（降级重试、遗留墙钟 ID、失败后重试），跳号时
	 * {@code tailSeq − 窗口} 可能<b>越过仍然存在</b>的帧（读者永久缺帧），也可能落进空洞里
	 * 误报"截断"。现在：
	 * <ul>
	 * <li><b>全量回放</b>（{@code afterSeq <= 0}）且读满一个窗口、窗口之外还有更晚的条目 ⇒
	 *     给<b>最新窗口</b>（含终帧，M′ 口径）并置 {@code truncated}；</li>
	 * <li><b>差量回放</b>（{@code afterSeq > 0}）⇒ 给<b>最近的</b>窗口（升序），剩下的下一拍继续
	 *     （游标只推进到已交付处，因此不算截断）；</li>
	 * <li>{@code truncated} 只在"游标早于<b>幸存窗口的第一条</b>（那一段确实不可回放）"或
	 *     "全量回放被窗口截住"时为真 —— 都由<b>条目</b>判定，不看号是否连续。</li>
	 * </ul>
	 *
	 * <p><b>窗口口径与写侧同源</b>（Redis 容量专项 A）：{@code 窗口}取自
	 * {@link #effectiveWindow(String)}（条数窗口与字节预算推导出的等效窗口取小）。
	 */
	private FrameStore.ReadResult streamFramesAbove(String runId, long afterSeq) {
		StreamTail tail = streamTailSeq(runId);
		int window = effectiveWindow(runId);
		// 截断只由<b>真实裁剪低水位</b>决定（见 {@link #XTRIM_PREFIX}）：没有水位 ⇒ 不下结论。
		Long watermark = trimWatermark(runId);
		boolean truncated = watermark != null && afterSeq + 1L < watermark;
		long gapFrom = truncated ? afterSeq + 1L : 0L;
		long gapTo = truncated ? watermark - 1L : 0L;
		if (!tail.ok()) {
			if (afterSeq <= 0L) {
				// I-1：全量回放时末号未知且没有可读帧 —— 不能用"最旧窗口"冒充最新帧，宁可这一拍不回放。
				log.warn("事件日志末号未知且要求全量回放 runId={}（拒绝退回最旧窗口，返回降级空结果）", runId);
				return new FrameStore.ReadResult(List.of(), true, truncated, gapFrom, gapTo);
			}
			return new FrameStore.ReadResult(readRange(runId, afterSeq, window, true), true, truncated, gapFrom, gapTo);
		}
		if (tail.seq() <= afterSeq) {
			return new FrameStore.ReadResult(List.of(), false, false, 0L, 0L);
		}
		// <b>只读一次、不做 seq 减法</b>（Task 2 / I-2）：
		// 全量回放 ⇒ 直接读"最新窗口"（M′：含终帧；一次 XREVRANGE COUNT 窗口，不先 materialize 一份升序窗口）；
		// 差量读   ⇒ 读"最近的窗口"（升序），剩下的下一拍继续（游标只推进到已交付处）。
		List<Map<String, Object>> frames;
		if (afterSeq > 0L) {
			frames = readRange(runId, afterSeq, window, false);
		}
		else {
			// 全量回放：给最新窗口（M′），并按 <b>XLEN</b> 判断"更早的条目是否没返回"——
			// {@code MAXLEN ~} 是近似裁剪，流里可以比窗口多留 ~99 条：那 1..N 条既不在返回里、
			// 也没被物理裁掉（低水位不会记），只看水位就会把"最老几条静默缺"说成"完整"。
			NewestWindow newest = readNewestWindow(runId, window);
			frames = newest.frames();
			// 与 XLEN 比的是<b>读到的记录条数</b>（含坏损条目）：坏帧不得被算成"缺口"。
			long[] windowGap = newest.recordCount() == 0 ? null
					: windowGapBelowHead(runId, newest.headId(), newest.recordCount());
			if (windowGap != null) {
				// 缺口取两个信号的<b>并集</b>：窗口外那段（windowGap）与更低的水位段
				// （"从 afterSeq+1 起就不可恢复"）。只报 windowGap 会把 archive-from 低估成
				// "窗口外首条"，前端据此少报一段本来也不可恢复的历史。
				truncated = true;
				gapFrom = gapFrom == 0L ? windowGap[0] : Math.min(gapFrom, windowGap[0]);
				gapTo = Math.max(gapTo, windowGap[1]);
			}
		}
		if (truncated) {
			// 可聚合观测：本次回放确实跳过了不可回放的区间（每次读只计一次；含义 = 有缺口，不是"读失败"）。
			RedisDegradation.record(RedisDegradation.C1_STREAM_REPLAY_GAP);
			log.debug("事件日志回放跳过不可回放区间 runId={} afterSeq={} 缺口={}..{} 有效窗口={}", runId, afterSeq,
					gapFrom, gapTo, window);
		}
		return new FrameStore.ReadResult(frames, false, truncated, gapFrom, gapTo);
	}

	/**
	 * 按序号读<b>归档原文</b>（{@code XRANGE <afterSeq+1>-0 + COUNT limit}，升序）。
	 *
	 * <p>返回的 {@code json} 就是落档时写入的 {@code frame} 字段原文（逐字节一致，不反序列化）；
	 * {@code seq} 取记录的 {@code seq} 字段（缺失时回落 ID 毫秒段）。读失败返回空列表并 WARN + 计 A3：
	 * 调用方按"这次补不到"处理（下一拍再补），不阻塞投递链。
	 */
	@Override
	public List<FrameStore.RawFrame> readRawFrom(String runId, long afterSeq, int limit) {
		List<FrameStore.RawFrame> out = new ArrayList<>();
		Range<String> range = afterSeq <= 0L
				? Range.unbounded()
				: Range.from(Range.Bound.inclusive((afterSeq + 1) + "-0")).to(Range.Bound.unbounded());
		try {
			byte[] key = bytes(streamKey(runId));
			this.redis.execute((RedisCallback<Object>) connection -> {
				List<ByteRecord> records = connection.xRange(key, range,
						org.springframework.data.redis.connection.Limit.limit().count(Math.max(1, limit)));
				if (records == null) {
					return null;
				}
				for (ByteRecord record : records) {
					byte[] raw = frameFieldOf(record);
					if (raw != null) {
						out.add(new FrameStore.RawFrame(seqFieldOf(record), new String(raw, StandardCharsets.UTF_8)));
					}
				}
				return null;
			});
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.A3_FRAME_RANGE_READ, ex);
			log.warn("事件日志原文读取失败 runId={} afterSeq={}（本次按无差量处理，不重试）：{}", runId, afterSeq,
					ex.toString());
		}
		return out;
	}

	/**
	 * 归档裁剪低水位（{@code ai:xtrim}）：返回幸存首条序号，{@code null} = 从未裁剪过/读不出。
	 *
	 * <p>读不出（抖动/键类型错）按"没有水位"处理：<b>不</b>把"读不到"说成"被裁过"，
	 * 也不据此报截断（宁可不报，也不误报）。
	 */
	private Long trimWatermark(String runId) {
		try {
			String raw = this.redis.opsForValue().get(trimKey(runId));
			if (raw == null || raw.isBlank()) {
				return null;
			}
			long value = Long.parseLong(raw.trim());
			return value > 0L ? value : null;
		}
		catch (Exception ex) {
			log.debug("归档裁剪低水位读取失败 runId={}（按'从未裁剪'处理，不臆测截断）：{}", runId, ex.getMessage());
			return null;
		}
	}

	/**
	 * 升序取 {@code seq > afterSeq} 的最近 {@code window} 条（{@code XRANGE <afterSeq+1>-0 + COUNT 窗口}）。
	 *
	 * <p>用<b>闭区间 {@code afterSeq+1}</b> 而不是排他下限：{@code (id} 是 Redis 6.2 的语法，
	 * 目标部署 5.0.14 会直接报错（报错被 catch 折成空 ⇒ 差量读 0 帧）。
	 *
	 * <p>读失败不抛（挂起重连 / 唤醒补帧不能被一次抖动打断），但 WARN + 计 A3：
	 * 空结果 ≠ 读失败，两者必须可区分。
	 *
	 * @param degraded 读失败时返回给调用方的 degraded 位（末号未知路径为 true）
	 */
	private List<Map<String, Object>> readRange(String runId, long afterSeq, int window, boolean degraded) {
		List<Map<String, Object>> out = new ArrayList<>();
		Range<String> range = afterSeq <= 0L
				? Range.unbounded()
				: Range.from(Range.Bound.inclusive((afterSeq + 1) + "-0")).to(Range.Bound.unbounded());
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
			// 读失败 ≠ 空流：返回值对调用链仍是"暂无差量"（抛异常会让整条订阅路径失败），
			// 但必须 WARN + 计数 —— 静默折成空会把"日志读不出来"伪装成"没有新帧"。
			RedisDegradation.record(RedisDegradation.A3_FRAME_RANGE_READ, ex);
			log.warn("事件日志读取失败 runId={} afterSeq={}（本次按无差量处理，不重试）：{}", runId, afterSeq,
					ex.toString());
			return out;
		}
		return out;
	}

	/**
	 * 最新窗口：升序帧 + 窗口头那条记录的 ID（算缺口终点要用它往前看一条）+ <b>读到的记录条数</b>。
	 *
	 * <p>为什么要"记录条数"而不是 {@code frames.size()}：单条帧体坏损时 {@code frameOf} 会跳过它，
	 * 于是"可解析帧数 &lt; 条目数"。拿可解析帧数去比 {@code XLEN} 会把"一条坏帧"误报成"归档被裁、
	 * 有缺口"（假截断）。与 XLEN 比的必须是<b>读到的记录</b>。
	 */
	private record NewestWindow(List<Map<String, Object>> frames, String headId, int recordCount) {
	}

	/** 倒序取"最新 {@code window} 条"再翻成升序（全量回放要含末帧，M′）。 */
	private NewestWindow readNewestWindow(String runId, int window) {
		List<Map<String, Object>> out = new ArrayList<>();
		String headId = null;
		int[] recordCount = new int[1];
		try {
			byte[] key = bytes(streamKey(runId));
			String[] holder = new String[1];
			this.redis.execute((RedisCallback<Object>) connection -> {
				List<ByteRecord> records = connection.xRevRange(key, Range.unbounded(),
						org.springframework.data.redis.connection.Limit.limit().count(window));
				if (records == null) {
					return null;
				}
				for (int i = records.size() - 1; i >= 0; i--) {
					ByteRecord record = records.get(i);
					recordCount[0]++;
					Map<String, Object> frame = frameOf(record);
					if (frame != null) {
						if (holder[0] == null) {
							holder[0] = record.getId() == null ? null : record.getId().getValue();
						}
						out.add(frame);
					}
				}
				return null;
			});
			headId = holder[0];
		}
		catch (Exception ex) {
			RedisDegradation.record(RedisDegradation.A3_FRAME_RANGE_READ, ex);
			log.warn("事件日志最新窗口读取失败 runId={}（本次按无差量处理，不重试）：{}", runId, ex.toString());
		}
		return new NewestWindow(out, headId, recordCount[0]);
	}

	/**
	 * 全量回放"还有条目没返回"的判定（<b>用 XLEN，不用 seq 猜</b>）：{@code XLEN > 返回条数}
	 * ⇒ 最老的那些条目既不在返回里、也没被物理裁掉，必须如实报截断。
	 *
	 * <p>返回 {@code long[]{from, to}}（缺口含端点）：{@code from} = 最早未返回条目（流首条）的 seq，
	 * {@code to} = 窗口头<b>之前那一条</b>的 seq（{@code XREVRANGE <headId> - COUNT 2} 的末位，O(1)）。
	 * 读不出（抖动/无 ID）返回 {@code null}：不臆测、不报截断。
	 */
	private long[] windowGapBelowHead(String runId, String headId, int returnedCount) {
		try {
			byte[] key = bytes(streamKey(runId));
			Long length = this.redis.execute((RedisCallback<Long>) connection -> connection.xLen(key));
			if (length == null || length <= returnedCount) {
				return null;
			}
			Long oldest = this.redis.execute((RedisCallback<Long>) connection -> {
				List<ByteRecord> records = connection.xRange(key, Range.unbounded(),
						org.springframework.data.redis.connection.Limit.limit().count(1));
				return records == null || records.isEmpty() ? null : seqFieldOf(records.get(0));
			});
			if (oldest == null || oldest <= 0L) {
				return null;
			}
			Long beforeHead = null;
			if (headId != null) {
				beforeHead = this.redis.execute((RedisCallback<Long>) connection -> {
					List<ByteRecord> records = connection.xRevRange(key,
							Range.<String>from(Range.Bound.unbounded()).to(Range.Bound.inclusive(headId)),
							org.springframework.data.redis.connection.Limit.limit().count(2));
					if (records == null || records.size() < 2) {
						return null;
					}
					return seqFieldOf(records.get(1));
				});
			}
			long to = beforeHead != null && beforeHead > 0L ? beforeHead : oldest;
			return new long[] { oldest, Math.max(oldest, to) };
		}
		catch (Exception ex) {
			log.debug("事件日志全量回放缺口探测失败 runId={}（本次不报截断）：{}", runId, ex.toString());
			return null;
		}
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
	 * 为什么不只读物理最后一条：本批起 ID == seq，物理末位就是最大号；但<b>升级期</b>流里可能残留
	 * 旧实现落的自动 ID 记录（ID 是墙钟，物理位次与号无关），此时末位未必是最大号。只读一条会把末号判小
	 * —— 而末号是"读取下限夹取"（M′）与"号回退修复"（M-1）的共同锚点。取末尾
	 * {@value #TAIL_PROBE} 条的最大 {@code seq} 仍是一次往返、只解析 {@code seq} 字段、不做 JSON 反序列化。
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
		RunState state = this.runStates.get(bookkeepingId(runId));
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
		this.runStates.remove(bookkeepingId(runId));
	}

	/**
	 * 进程内簿记的键：<b>身份段 + runId</b>。
	 *
	 * <p>簿记（降级号 / 观察到的最大帧载荷 / 告警位）是<b>按轮</b>的进程内加速态，
	 * 两个租户的同名 runId 必须是两条：否则一方的字节预算、降级号会污染另一方
	 * （同进程内最坏情况 = 另一租户的帧按本租户的窗口被裁）。
	 */
	private static String bookkeepingId(String runId) {
		return TenantKeyScope.currentSegment() + runId;
	}

	/** 当前进程内仍持有簿记的轮数（诊断/断言用）。 */
	int trackedRuns() {
		return this.runStates.size();
	}

	/** 取（必要时新建）本轮簿记并推进"最后触达"，顺带做一次到期清理（有节流）。 */
	private RunState state(String runId) {
		long now = this.clock.getAsLong();
		RunState state = this.runStates.computeIfAbsent(bookkeepingId(runId), id -> new RunState(now));
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
