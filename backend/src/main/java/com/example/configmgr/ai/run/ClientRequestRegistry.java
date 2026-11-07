package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code clientRequestId} → run 的精确关联、取消墓碑与终态回收（停止闭环的最后一段）。
 *
 * <h2>为什么需要</h2>
 * start 帧之前停止、随后刷新时，前端从未拿到 runId；只凭"会话里当前有一条 run"无法证明它就是
 * 被停的那条请求（可能是另一页签/另一轮），猜 runId 会误杀。这里让<b>客户端请求 id</b>成为
 * 可证明的关联键：{@code /chat} 发送前由前端生成并持久化，后端在创建 run 之前把它与本轮
 * 绑定；取消可只凭这个 id 精确落到这一轮，或在 run 尚未创建时留下墓碑，让随后到达的
 * {@code /chat} 在压缩/建快照/模型调用/工具副作用之前收敛为已取消。
 *
 * <h2>键与隔离</h2>
 * <b>一个会话一个 HASH</b>：{@code ai:creq:<tenantId>:<userId>:<sessionId>}
 * （{@link TenantKeyScope}，与 {@code ai:ctx:} 等同族身份段）。field = {@code clientRequestId}，
 * value 是<b>有限状态机</b>：
 * <ul>
 * <li>{@code RUN:<runId>} —— 本轮仍在驱动/挂起中（活跃，永不回收）；</li>
 * <li>{@code DONE:<runId>:<完成时刻ms>} —— 轮次已到终态（DONE/FAILED/CANCELLED 任一），
 *     <b>带时刻</b>；重复请求仍按 runId 重挂，超过 {@link #COMPLETED_RETENTION} 后由后续
 *     claim/cancel 有界回收；</li>
 * <li>{@code CANCELLED} / {@code CANCELLED:<runId>} —— 取消墓碑（取消早于 run 创建 / 已绑定
 *     run 被取消）：<b>不参与回收</b>，只随整键 TTL 或会话销毁一起消失。</li>
 * </ul>
 * <ul>
 * <li><b>不以 runId 为键</b>，也不扫描任何租户/run 集合：只按会话 + 客户端 id 精确读写；
 *     回收用的 {@code HSCAN} 始终落在<b>同一个</b>会话 HASH 上（单键，不是跨键 SCAN）；</li>
 * <li>所有操作都是<b>单键</b> HGET/HSET/HDEL/PEXPIRE/HSCAN 或单键 Lua（KEYS 恰好一个）—— Redis
 *     Cluster 下天然同槽，不存在跨键脚本；取消旗标 {@code ai:cancel:{<t>:<u>:run:<runId>}} 由调用方另做
 *     一次单键写，不放进本脚本（避免 CROSSSLOT）；</li>
 * <li>TTL = {@code app.ai.session-ttl}（缺省 24h），每次 claim/cancel/complete 整键续期；会话销毁时由
 *     {@code AiSessionPurgeService} 按同一键拼接口精确 DEL（见 {@link #sessionKey(String)}）；</li>
 * <li><b>没有新键族、没有后台任务</b>：终态由 {@code ResilientChatService#clearTerminalRunState}
 *     （统一终态出口）用 {@link RunSnapshot#getClientRequestId()} / {@link RunSnapshot#getSessionId()}
 *     写一次；过期字段的回收搭在后续 claim/cancel 的同一次脚本里，按"每次至多检查 256 个字段、
 *     删除 32 个"封顶，<b>扫描游标保存在同一个 HASH 的保留字段</b> {@value #SWEEP_CURSOR_FIELD}
 *     里，下一次 claim/cancel 从该游标续扫（走完一圈归零并删掉该字段）。所有按 field 的判读都跳过
 *     保留字段；{@code clientRequestId} 的字符集不允许 {@code #} 开头，因此不会与之冲突。</li>
 * </ul>
 *
 * <h2>取消早于 run 创建</h2>
 * 取消脚本在没有 field 时直接写 {@code CANCELLED}（带 TTL）而不是 404。随后 {@code /chat} 的
 * claim 脚本原子地看到 {@code CANCELLED}，不会覆盖它；调用方据此写本轮取消旗标，服务在
 * 压缩/建快照/模型调用之前收敛为 {@code done{cancelled:true}}。
 *
 * <p>不作为 runId 使用：本类产出的 runId 只来自 {@code RUN:} / {@code DONE:} 分支，客户端无法用
 * {@code clientRequestId} 指定任意 runId；同一身份下重复取消幂等，不产生重复副作用。
 */
@Slf4j
@Component
public class ClientRequestRegistry {

	/** 会话级 HASH 键前缀（后接身份段 + sessionId）。 */
	public static final String KEY_PREFIX = "ai:creq:";

	/** {@code clientRequestId} 上限（UUID 远小于此；防御超长 field）。 */
	public static final int MAX_ID_LENGTH = 128;

	/**
	 * 已完成字段的<b>短保留期</b>：这段时间内重复到达的同一请求仍按 runId 重挂（网络抖动重发、
	 * 刷新后补发），超过即视为"旧请求"，在后续 claim/cancel 时回收。
	 *
	 * <p>必须远小于整键 TTL（会话 24h）：否则每次发送都在这张 HASH 里留一条永久字段。
	 * 取消墓碑（{@code CANCELLED*}）与活跃绑定（{@code RUN:*}）<b>不受</b>本保留期约束。
	 */
	public static final Duration COMPLETED_RETENTION = Duration.ofMinutes(10);

	private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

	private static final String RUN_PREFIX = "RUN:";

	private static final String DONE_PREFIX = "DONE:";

	private static final String CANCELLED = "CANCELLED";

	private static final String CANCELLED_RUN_PREFIX = "CANCELLED:";

	/**
	 * 回收游标的<b>保留字段</b>：与 {@code clientRequestId} 同 HASH，field 名以 {@code #} 开头。
	 *
	 * <p>为什么放在同一个 HASH 而不是新键：Cluster 下才能与 {@code HSCAN} 同槽、与整键同 TTL、
	 * 会话销毁时随键一起删；不引入新键族。{@link #ID_PATTERN} 要求首字符是字母/数字，因此
	 * {@code clientRequestId} 永远不会与它冲突 —— 所有按 field 的判读（扫描、claim/cancel 的
	 * HGET 之外的全量读）都必须跳过它。
	 */
	public static final String SWEEP_CURSOR_FIELD = "#sweep";

	/**
	 * 有界回收（claim/cancel 共用）：只删<b>已超过保留期</b>的 {@code DONE:} 字段。
	 *
	 * <p>为什么有界：会话 HASH 的规模由"一次会话发出过多少条请求"决定，脚本不得做无上限扫描。
	 * 每次调用至多检查 {@code 256} 个字段、删除 {@code 32} 个；HSCAN 只在<b>本会话这一个键</b>上走
	 * 游标（Cluster 下同槽）。{@code RUN:*}（活跃）、{@code CANCELLED*}（墓碑/防重）与未到期的
	 * {@code DONE:*} 一律不动。
	 *
	 * <p><b>游标必须续扫</b>：单次调用受上限约束，不可能扫完整张大 HASH，因此游标存在
	 * {@link #SWEEP_CURSOR_FIELD} 里，下一次 claim/cancel 从该游标继续；只有真正走完一圈
	 * （HSCAN 返回 {@code 0}）才归零并删掉保留字段。旧实现每轮都从 {@code 0} 开始，字段多到
	 * 一次扫不完时<b>后面的字段永远轮不到</b>（饿死）。
	 *
	 * <p>游标只在<b>本页处理完</b>时前移到 HSCAN 返回的下一页；因上限在一页中途停下时，
	 * 保留字段仍记这一页的起点，下一轮重扫该页（已删字段不会重复出现，未删字段最多再检查一次）。
	 *
	 * <p>ARGV 约定（调用方）：{@code [4]=now(ms)}、{@code [5]=保留期(ms)}（claim）；
	 * {@code [3]=now(ms)}、{@code [4]=保留期(ms)}（cancel）。
	 */
	private static final String SWEEP_LUA = """
			-- 保留字段名必须与 SWEEP_CURSOR_FIELD 一致（# 开头，clientRequestId 的字符集不允许）
			local SWEEP_FIELD = '#sweep'
			local function sweepCompleted(selfField, now, retention)
			  local examined = 0
			  local removed = 0
			  local cursor = redis.call('HGET', KEYS[1], SWEEP_FIELD)
			  if type(cursor) ~= 'string' or not string.match(cursor, '^%d+$') then
			    cursor = '0'
			  end
			  local finished = false
			  while not finished do
			    local pageCursor = cursor
			    local page = redis.call('HSCAN', KEYS[1], pageCursor, 'COUNT', 32)
			    local nextCursor = tostring(page[1])
			    local flat = page[2]
			    local pageCompleted = true
			    local i = 1
			    while i <= #flat do
			      local field = flat[i]
			      local value = flat[i + 1]
			      if field ~= selfField and field ~= SWEEP_FIELD then
			        examined = examined + 1
			        if string.sub(value, 1, 5) == 'DONE:' then
			          local completedAt = string.match(value, ':(%d+)$')
			          if completedAt and (now - tonumber(completedAt)) >= retention then
			            redis.call('HDEL', KEYS[1], field)
			            removed = removed + 1
			          end
			        end
			        if examined >= 256 or removed >= 32 then
			          pageCompleted = false
			          break
			        end
			      end
			      i = i + 2
			    end
			    if pageCompleted then
			      cursor = nextCursor
			      finished = cursor == '0'
			    else
			      cursor = pageCursor
			      finished = true
			    end
			  end
			  if cursor == '0' then
			    redis.call('HDEL', KEYS[1], SWEEP_FIELD)
			  else
			    redis.call('HSET', KEYS[1], SWEEP_FIELD, cursor)
			  end
			end
			""";

	/**
	 * claim（单键原子）：不存在（或只剩<b>已过期</b>的 {@code DONE:}）⇒ 写 {@code RUN:<runId>}；
	 * 已取消 ⇒ 保留墓碑并返回 {@code CANCELLED}；已绑定/近期完成 ⇒ 返回 {@code EXISTS:<value>}
	 * （调用方按 runId 重挂既有轮，绝不覆盖、绝不取消另一轮）。
	 *
	 * <p>KEYS[1] = 会话 HASH；ARGV[1] = clientRequestId；ARGV[2] = runId；ARGV[3] = TTL(ms)；
	 * ARGV[4] = now(ms)；ARGV[5] = 已完成保留期(ms)。
	 */
	private static final RedisScript<String> CLAIM_SCRIPT = new DefaultRedisScript<>(SWEEP_LUA + """
			-- CLIENT_REQUEST_CLAIM
			local v = redis.call('HGET', KEYS[1], ARGV[1])
			if v and string.sub(v, 1, 5) == 'DONE:' then
			  local completedAt = string.match(v, ':(%d+)$')
			  if completedAt and (tonumber(ARGV[4]) - tonumber(completedAt)) >= tonumber(ARGV[5]) then
			    v = nil
			  end
			end
			if not v then
			  redis.call('HSET', KEYS[1], ARGV[1], 'RUN:' .. ARGV[2])
			  redis.call('PEXPIRE', KEYS[1], ARGV[3])
			  sweepCompleted(ARGV[1], tonumber(ARGV[4]), tonumber(ARGV[5]))
			  return 'CLAIMED'
			end
			redis.call('PEXPIRE', KEYS[1], ARGV[3])
			if v == 'CANCELLED' or string.sub(v, 1, 10) == 'CANCELLED:' then
			  sweepCompleted(ARGV[1], tonumber(ARGV[4]), tonumber(ARGV[5]))
			  return 'CANCELLED'
			end
			sweepCompleted(ARGV[1], tonumber(ARGV[4]), tonumber(ARGV[5]))
			return 'EXISTS:' .. v
			""", String.class);

	/**
	 * 取消（单键原子）：无 field ⇒ 写 {@code CANCELLED} 墓碑；{@code RUN:<runId>} ⇒ 转成
	 * {@code CANCELLED:<runId>} 并把它回给调用方（由调用方另写单键取消旗标）；
	 * 已取消 ⇒ 幂等返回；已到终态（{@code DONE:}）⇒ <b>不改写完成态</b>（空操作幂等），
	 * 只把 runId 回给调用方，重复刷新/重复取消不再产生副作用。
	 *
	 * <p>返回 {@code NEW[:runId]} / {@code ALREADY[:runId]}。
	 *
	 * <p>KEYS[1] = 会话 HASH；ARGV[1] = clientRequestId；ARGV[2] = TTL(ms)；
	 * ARGV[3] = now(ms)；ARGV[4] = 已完成保留期(ms)。
	 */
	private static final RedisScript<String> CANCEL_SCRIPT = new DefaultRedisScript<>(SWEEP_LUA + """
			-- CLIENT_REQUEST_CANCEL
			local v = redis.call('HGET', KEYS[1], ARGV[1])
			if not v then
			  redis.call('HSET', KEYS[1], ARGV[1], 'CANCELLED')
			  redis.call('PEXPIRE', KEYS[1], ARGV[2])
			  sweepCompleted(ARGV[1], tonumber(ARGV[3]), tonumber(ARGV[4]))
			  return 'NEW:'
			end
			if v == 'CANCELLED' then
			  redis.call('PEXPIRE', KEYS[1], ARGV[2])
			  sweepCompleted(ARGV[1], tonumber(ARGV[3]), tonumber(ARGV[4]))
			  return 'ALREADY:'
			end
			if string.sub(v, 1, 10) == 'CANCELLED:' then
			  redis.call('PEXPIRE', KEYS[1], ARGV[2])
			  sweepCompleted(ARGV[1], tonumber(ARGV[3]), tonumber(ARGV[4]))
			  return 'ALREADY:' .. string.sub(v, 11)
			end
			if string.sub(v, 1, 5) == 'DONE:' then
			  redis.call('PEXPIRE', KEYS[1], ARGV[2])
			  sweepCompleted(ARGV[1], tonumber(ARGV[3]), tonumber(ARGV[4]))
			  local completedRunId = string.match(v, '^DONE:([^:]*):%d+$')
			  return 'ALREADY:' .. (completedRunId or '')
			end
			if string.sub(v, 1, 4) == 'RUN:' then
			  local runId = string.sub(v, 5)
			  redis.call('HSET', KEYS[1], ARGV[1], 'CANCELLED:' .. runId)
			  redis.call('PEXPIRE', KEYS[1], ARGV[2])
			  sweepCompleted(ARGV[1], tonumber(ARGV[3]), tonumber(ARGV[4]))
			  return 'NEW:' .. runId
			end
			redis.call('HSET', KEYS[1], ARGV[1], 'CANCELLED')
			redis.call('PEXPIRE', KEYS[1], ARGV[2])
			sweepCompleted(ARGV[1], tonumber(ARGV[3]), tonumber(ARGV[4]))
			return 'NEW:'
			""", String.class);

	/**
	 * 完成（单键条件写）：只有当前值仍是<b>本次</b>绑定的 {@code RUN:<runId>} 才转成
	 * {@code DONE:<runId>:<now>} 并续期整键 TTL。
	 *
	 * <p>为什么必须带 runId 条件：同一 clientRequestId 在"过期回收后重发"时会绑定到<b>新</b> run；
	 * 旧驱动迟到的终态收尾若无条件写，就会把新绑定的活跃轮标成已完成。取消墓碑
	 * （{@code CANCELLED*}）同样不覆盖：取消是更强的终局事实，且墓碑的防重语义不得被完成态取代。
	 *
	 * <p>KEYS[1] = 会话 HASH；ARGV[1] = clientRequestId；ARGV[2] = runId；ARGV[3] = now(ms)；
	 * ARGV[4] = TTL(ms)。
	 */
	private static final RedisScript<String> COMPLETE_SCRIPT = new DefaultRedisScript<>("""
			-- CLIENT_REQUEST_COMPLETE
			local v = redis.call('HGET', KEYS[1], ARGV[1])
			if v == ('RUN:' .. ARGV[2]) then
			  redis.call('HSET', KEYS[1], ARGV[1], 'DONE:' .. ARGV[2] .. ':' .. ARGV[3])
			  redis.call('PEXPIRE', KEYS[1], ARGV[4])
			  return 'COMPLETED'
			end
			return 'SKIPPED'
			""", String.class);

	/**
	 * 释放绑定（单键条件 HDEL）：只有当前值仍是本次写的 {@code RUN:<runId>} 才删。
	 *
	 * <p>用于"run 明确**没有**开始"的失败出口（如挂起池饱和拒绝执行）：让同一 clientRequestId
	 * 的前端瞬时重试能重新 claim，而不是被一条从未启动的映射卡成重复请求。
	 *
	 * <p>KEYS[1] = 会话 HASH；ARGV[1] = clientRequestId；ARGV[2] = runId。
	 */
	private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
			-- CLIENT_REQUEST_RELEASE
			if redis.call('HGET', KEYS[1], ARGV[1]) == ('RUN:' .. ARGV[2]) then
			  return redis.call('HDEL', KEYS[1], ARGV[1])
			end
			return 0
			""", Long.class);

	private final StringRedisTemplate redis;

	private final AiProperties properties;

	public ClientRequestRegistry(StringRedisTemplate redis, AiProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/** 会话级键（当前身份；无身份上下文时落 legacy 键形，与其它会话级键同口径）。 */
	public static String sessionKey(String sessionId) {
		return TenantKeyScope.scoped(KEY_PREFIX, sessionId);
	}

	/** {@code clientRequestId} 形状校验（additive 字段；非法值在入口 400，不进 Redis）。 */
	public static boolean isValid(String clientRequestId) {
		return clientRequestId != null && clientRequestId.length() <= MAX_ID_LENGTH
				&& ID_PATTERN.matcher(clientRequestId).matches();
	}

	public Claim claim(String sessionId, String clientRequestId, String runId) {
		if (!isValid(clientRequestId)) {
			throw new IllegalArgumentException("Invalid clientRequestId");
		}
		long now = System.currentTimeMillis();
		String result = this.redis.execute(CLAIM_SCRIPT, List.of(sessionKey(sessionId)),
				clientRequestId, runId, String.valueOf(ttl().toMillis()), String.valueOf(now),
				String.valueOf(COMPLETED_RETENTION.toMillis()));
		if (result == null) {
			throw new IllegalStateException("clientRequestId claim outcome is unknown");
		}
		if ("CLAIMED".equals(result)) {
			return new Claim(ClaimState.CLAIMED, runId);
		}
		if ("CANCELLED".equals(result)) {
			return new Claim(ClaimState.CANCELLED, null);
		}
		String existing = result.startsWith("EXISTS:") ? result.substring("EXISTS:".length()) : result;
		String boundRunId = boundRunIdOf(existing, now, COMPLETED_RETENTION.toMillis());
		log.warn("clientRequestId already bound to another run, reuse rejected clientRequestId={} existing={}", clientRequestId, existing);
		return new Claim(ClaimState.ALREADY_BOUND, boundRunId);
	}

	/**
	 * 按 clientRequestId 精确取消（幂等）。返回需要写取消旗标的 runId（可能已知、也可能为空）。
	 *
	 * <p>对已完成（{@code DONE:}）的请求是空操作：不改写完成态，只把已知 runId 回给调用方
	 * （调用方对终态轮写取消旗标是无害的，终态守卫会拒绝改写）。
	 */
	public CancelOutcome cancel(String sessionId, String clientRequestId) {
		if (!isValid(clientRequestId)) {
			throw new IllegalArgumentException("Invalid clientRequestId");
		}
		long now = System.currentTimeMillis();
		String result = this.redis.execute(CANCEL_SCRIPT, List.of(sessionKey(sessionId)),
				clientRequestId, String.valueOf(ttl().toMillis()), String.valueOf(now),
				String.valueOf(COMPLETED_RETENTION.toMillis()));
		if (result == null) {
			throw new IllegalStateException("clientRequestId cancellation outcome is unknown");
		}
		boolean already = result.startsWith("ALREADY:");
		String runId = result.substring(result.indexOf(':') + 1);
		return new CancelOutcome(true, already, runId.isEmpty() ? null : runId);
	}

	/**
	 * 把<b>已到终态</b>的这一轮从 {@code RUN:<runId>} 转成带时刻的完成态（{@code DONE:<runId>:<now>}）。
	 *
	 * <p>由统一终态出口（{@code ResilientChatService#clearTerminalRunState}）用快照里的
	 * {@code clientRequestId/sessionId/tenantId/userId} 调用 —— 不依赖线程身份，也不引入后台任务。
	 * 只有仍绑定<b>本条 run</b> 时才写：过期回收后重发的同一 id 可能已绑到新 run，旧驱动的迟到收尾
	 * 不得把它标成完成；取消墓碑同样保持不变。
	 *
	 * @return true = 本次写入完成态；false = 无需写（未绑定/已取消/已是完成态/runId 不匹配）
	 */
	public boolean complete(long tenantId, long userId, String sessionId, String clientRequestId, String runId) {
		if (!isValid(clientRequestId) || sessionId == null || sessionId.isBlank() || runId == null
				|| runId.isBlank()) {
			return false;
		}
		String key = TenantKeyScope.hasIdentity(tenantId, userId)
				? TenantKeyScope.scoped(KEY_PREFIX, tenantId, userId, sessionId)
				: sessionKey(sessionId);
		String result = this.redis.execute(COMPLETE_SCRIPT, List.of(key), clientRequestId, runId,
				String.valueOf(System.currentTimeMillis()), String.valueOf(ttl().toMillis()));
		return "COMPLETED".equals(result);
	}

	/**
	 * 释放一次**从未开始**的绑定（条件 HDEL）：不会覆盖已取消墓碑，也不会删除别的 run 的绑定。
	 *
	 * @return true = 绑定已释放，前端可用同一 id 重新 claim（等价于一次全新发送）
	 */
	public boolean releaseBinding(String sessionId, String clientRequestId, String runId) {
		if (!isValid(clientRequestId) || runId == null || runId.isBlank()) {
			return false;
		}
		Long removed = this.redis.execute(RELEASE_SCRIPT, List.of(sessionKey(sessionId)),
				clientRequestId, runId);
		return removed != null && removed > 0L;
	}

	/** 本会话内该 clientRequestId 是否已被取消（服务在压缩/建快照前复查用）。 */
	public boolean isCancelled(String sessionId, String clientRequestId) {
		if (!isValid(clientRequestId)) {
			return false;
		}
		Object value = this.redis.opsForHash().get(sessionKey(sessionId), clientRequestId);
		if (value == null) {
			return false;
		}
		String text = String.valueOf(value);
		return CANCELLED.equals(text) || text.startsWith(CANCELLED_RUN_PREFIX);
	}

	/** 当前绑定值（诊断/取证用；不续期）。 */
	public String binding(String sessionId, String clientRequestId) {
		if (!isValid(clientRequestId)) {
			return null;
		}
		Object value = this.redis.opsForHash().get(sessionKey(sessionId), clientRequestId);
		return value == null ? null : String.valueOf(value);
	}

	/**
	 * 该 clientRequestId 已绑定的 runId（{@code RUN:<runId>} 或<b>仍在保留期内</b>的
	 * {@code DONE:<runId>:<ts>}；未绑定/已取消/已完成且超过保留期 ⇒ null）。
	 *
	 * <p>供 /chat 的**重复到达**识别：同一个 id 已经启动过一轮时，调用方应走既有
	 * events/reattach 收帧，而不是再启动第二条驱动。超过短保留期的完成态视为"旧请求"，
	 * 交给 claim 回收后重新开始。
	 */
	public String boundRunId(String sessionId, String clientRequestId) {
		String value = binding(sessionId, clientRequestId);
		return boundRunIdOf(value, System.currentTimeMillis(), COMPLETED_RETENTION.toMillis());
	}

	/**
	 * 从绑定值解析 runId：{@code RUN:<runId>} 恒有效；{@code DONE:<runId>:<ts>} 只在保留期内有效
	 * （过期 ⇒ null，让 claim 把它当成未绑定回收）。
	 */
	private static String boundRunIdOf(String value, long now, long retentionMs) {
		if (value == null) {
			return null;
		}
		if (value.startsWith(RUN_PREFIX)) {
			String runId = value.substring(RUN_PREFIX.length());
			return runId.isBlank() ? null : runId;
		}
		if (!value.startsWith(DONE_PREFIX)) {
			return null;
		}
		int separator = value.lastIndexOf(':');
		if (separator <= DONE_PREFIX.length() - 1) {
			return null;
		}
		long completedAt;
		try {
			completedAt = Long.parseLong(value.substring(separator + 1));
		}
		catch (NumberFormatException ex) {
			return null;
		}
		if (now - completedAt >= retentionMs) {
			return null;
		}
		String runId = value.substring(DONE_PREFIX.length(), separator);
		return runId.isBlank() ? null : runId;
	}

	private Duration ttl() {
		Duration sessionTtl = this.properties.getSessionTtl();
		return sessionTtl != null && !sessionTtl.isZero() && !sessionTtl.isNegative()
				? sessionTtl : Duration.ofHours(24);
	}

	/** claim 的结果：只有 {@code CLAIMED} 可以继续；{@code CANCELLED} 走取消收敛；其余拒绝/重挂。 */
	public record Claim(ClaimState state, String runId) {
	}

	public enum ClaimState {
		CLAIMED, CANCELLED, ALREADY_BOUND
	}

	/**
	 * 一次按 clientRequestId 的取消结果。
	 *
	 * @param cancelled 已生成/确认取消墓碑
	 * @param alreadyCancelled 之前已经取消过（重复刷新/重复取消），或该请求已完成（空操作）
	 * @param runId 已知的本轮 runId（无则 null：run 尚未创建，墓碑待后续 /chat 识别）
	 */
	public record CancelOutcome(boolean cancelled, boolean alreadyCancelled, String runId) {
	}

}
