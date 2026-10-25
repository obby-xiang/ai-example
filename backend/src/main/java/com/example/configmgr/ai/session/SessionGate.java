package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同 sessionId 单值门（S4.2 §2 {@code session/SessionGate}，ADR-5 会话串行化）。
 *
 * <h2>语义</h2>
 * 一个会话同时只允许一轮在跑：第二轮请求被拒（<b>409</b>，与挂起池饱和的 503
 * {@code SUSPEND_POOL_SATURATED} 语义区分），且响应必须携带<b>进行中那一轮的 runId</b> ——
 * 前端据此改用 {@code GET /api/ai/events/{runId}} 重挂（ADR-5 补记 CH-P4：解决
 * "刷新不丢"与 409 之间的死锁）。
 *
 * <h2>两道锁</h2>
 * <ul>
 * <li><b>进程内单值门</b>（{@link ConcurrentHashMap}）：恒定生效，是 §13.1 降级形态的本体；</li>
 * <li><b>Redis 锁</b>{@code ai:session:lock:<sessionId>}：跨实例串行化，带 <b>watchdog 续期</b>
 * （每 {@code app.ai.session.lock.watchdog} 续一次 {@code app.ai.session.lock.ttl}），
 * 轮终态<b>主动释放</b>（只删自己的锁）。</li>
 * </ul>
 *
 * <h2>锁归属 = 每次 acquire 的 token（I2）</h2>
 * 只按 runId 判归属不够：park 后由 {@code ResumeService} 用<b>同一个 runId</b> 重新取门，
 * 而首轮任务那条迟到的 {@code finally} 会把新持有者的锁删掉、并 cancel 新持有者的 watchdog ⇒
 * 恢复执行在"无锁"状态下跑到终态，同会话可并发第二轮。因此：
 * <ul>
 * <li>每次 {@link #acquire(String, String, String)} 带一个 token，锁值为 {@code runId|token}
 * （{@code token == runId} 时退化为裸 runId，首轮/旧调用点行为不变）；</li>
 * <li>{@link #release(String, String, String)} 按 token <b>精确匹配</b>；旧 token 的迟到释放是 no-op；</li>
 * <li>同一 runId 用新 token 取门 = <b>接管</b>（park 后续跑重新取门），锁值换成新 token；</li>
 * <li>watchdog 续期<b>在脚本内同值校验</b>：锁已换主就停续期（不再替别人的锁续命）。</li>
 * </ul>
 * 判死路径（{@code AiController} 已确认进程内无订阅者）用 {@link #releaseDeadHeld(String, String)}
 * 按 runId 强释放：此时该轮已不可能再推进，无需（也无法）持有原 token。
 *
 * <h2>检查与写入必须是同一条单键 Lua（原子）</h2>
 * 只做"GET 比较 → 再写"是不够的：两个动作之间锁可能换主，于是释放会 DEL 掉别人的锁、
 * 续期会给别人续命、同 run 接管会用 plain SET 覆盖别人的锁。<b>本类所有写路径</b>
 * （取门/同 run 接管、续期、释放、强释放）都收敛到 {@code redis.execute(script, [lockKey], ...)}：
 * <ul>
 * <li><b>KEYS 恰好一个</b>（Cluster 单槽；一条 EVAL 只碰一个键）；</li>
 * <li>脚本内比对锁值后才写；比对不过返回 0，<b>永不</b>退回裸 SET/DEL/EXPIRE；</li>
 * <li>强释放只匹配该 runId（{@code runId} 或 {@code runId|*}），不匹配别的轮；</li>
 * <li>Redis 异常或结果不可知（{@code null}）⇒ 视为<b>未成功</b>：不写兜底、不宣称为
 * "已取门/已接管/已续期"（续期异常直接抛给 watchdog 按降级处理）。</li>
 * </ul>
 *
 * <h2>§13.1 降级</h2>
 * 锁服务不可用（Redis 异常）时降级为进程内单值门：M1 单实例下等价，冲突照样 409 提示重试，
 * 只是失去了跨实例互斥；降级事实写进返回体（{@code degraded=true}）与 WARN 日志，不静默。
 */
@Slf4j
@Component
public class SessionGate {

	public static final String LOCK_PREFIX = "ai:session:lock:";

	/**
	 * 取门 / 同 run 接管（单键原子）。
	 *
	 * <p>KEYS[1] = 锁键；ARGV[1] = 新值（{@code runId|token}）；ARGV[2] = TTL(ms)；ARGV[3] = runId。
	 * 键不存在 ⇒ 取门；当前值就是本 run（{@code runId} 或 {@code runId|*}）⇒ 接管并换新 token；
	 * 其余（别的轮持有）⇒ 返回 0，<b>不写</b>。
	 */
	private static final RedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
			-- SESSION_LOCK_ACQUIRE
			local current = redis.call('GET', KEYS[1])
			if not current then
			  redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
			  return 1
			end
			if current == ARGV[3] then
			  redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
			  return 1
			end
			if string.sub(current, 1, string.len(ARGV[3]) + 1) == ARGV[3] .. '|' then
			  redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
			  return 1
			end
			return 0
			""", Long.class);

	/**
	 * 续期（单键原子）：只有锁值<b>恰好</b>是自己的那个值才 PEXPIRE。
	 *
	 * <p>KEYS[1] = 锁键；ARGV[1] = 期望值；ARGV[2] = TTL(ms)。返回 1 = 续到了自己的锁，0 = 键不存在/已换主。
	 */
	private static final RedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
			-- SESSION_LOCK_RENEW
			if redis.call('GET', KEYS[1]) == ARGV[1] then
			  return redis.call('PEXPIRE', KEYS[1], ARGV[2])
			end
			return 0
			""", Long.class);

	/**
	 * 释放（单键原子）：只有锁值<b>恰好</b>是自己的那个值才 DEL。
	 *
	 * <p>KEYS[1] = 锁键；ARGV[1] = 期望值（精确含 token）。
	 */
	private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
			-- SESSION_LOCK_RELEASE
			if redis.call('GET', KEYS[1]) == ARGV[1] then
			  return redis.call('DEL', KEYS[1])
			end
			return 0
			""", Long.class);

	/**
	 * 判死强释放（单键原子）：只按 runId 匹配（{@code runId} 或 {@code runId|*}），
	 * 别的轮持有的锁一律不动。
	 *
	 * <p>KEYS[1] = 锁键；ARGV[1] = runId。
	 */
	private static final RedisScript<Long> RELEASE_DEAD_SCRIPT = new DefaultRedisScript<>("""
			-- SESSION_LOCK_RELEASE_DEAD
			local current = redis.call('GET', KEYS[1])
			if not current then
			  return 0
			end
			if current == ARGV[1] then
			  return redis.call('DEL', KEYS[1])
			end
			if string.sub(current, 1, string.len(ARGV[1]) + 1) == ARGV[1] .. '|' then
			  return redis.call('DEL', KEYS[1])
			end
			return 0
			""", Long.class);

	private final StringRedisTemplate redis;

	private final AiProperties.Session.Lock lockProperties;

	private final Map<String, Holder> inProcess = new ConcurrentHashMap<>();

	/** 看门狗线程（平台线程，DC-12 禁虚拟线程；仅做续期，无业务阻塞）。 */
	private final ScheduledExecutorService watchdog;

	private final AtomicLong renewals = new AtomicLong();

	public SessionGate(StringRedisTemplate redis, AiProperties properties) {
		this.redis = redis;
		this.lockProperties = properties.getSession().getLock();
		this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "ai-session-lock-watchdog");
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * 占用会话门（首轮/旧调用点：token = runId）。
	 *
	 * @return {@code null} = 取到门（可以开跑）；非 null = 已有一轮在跑，值里带进行中的 runId
	 */
	public Busy acquire(String sessionId, String runId) {
		return acquire(sessionId, runId, runId);
	}

	/**
	 * 占用会话门，并声明本次持有的 token（I2）。
	 *
	 * <p>同一 runId 用新 token 再取门 = 接管（park 后 ResumeService 重新取门），锁值与
	 * watchdog 的期望值都换成新 token；不同 runId 仍然互斥。
	 *
	 * <p><b>取门与接管是同一条脚本</b>（{@link #ACQUIRE_SCRIPT}）：脚本内比对后才写，因此
	 * "检查通过、写入已换主"的窗口不存在。三种结局：
	 * <ul>
	 * <li>脚本返回 1 ⇒ 已持有（取门或同 run 接管），换 token 并重启 watchdog；</li>
	 * <li>脚本返回 0 ⇒ <b>另一轮持有</b>：按对方 runId 返回 {@link Busy}，
	 * <b>不改</b>自己的 holder token、也不宣称接管成功（重入路径下 holder 仍是本轮的）；</li>
	 * <li>异常 / 结果不可知 ⇒ 只保留进程内门（{@code degraded}），不写兜底、不宣称取到 Redis 锁。</li>
	 * </ul>
	 *
	 * @return {@code null} = 取到门（含同轮接管）；非 null = 另一轮在跑
	 */
	public Busy acquire(String sessionId, String runId, String token) {
		Holder candidate = new Holder(runId, token);
		Holder previous = this.inProcess.putIfAbsent(sessionId, candidate);
		if (previous != null) {
			if (!previous.runId.equals(runId)) {
				return new Busy(previous.runId, previous.degraded);
			}
			// 重入（同一轮再次 acquire，例如"首轮 park 后由 ResumeService 重新取门"）
			Boolean taken = takeLock(sessionId, runId, token);
			if (Boolean.TRUE.equals(taken)) {
				previous.token = token;
				previous.lockValue = encode(runId, token);
				startWatchdog(sessionId, runId, previous.lockValue, previous);
				return null;
			}
			if (Boolean.FALSE.equals(taken)) {
				// 锁已被另一轮拿走（park 期间锁到期后别人接管）：不覆盖、不续看门狗，
				// holder 保持原 token（本轮进程内仍是它的门），对外如实报"另一轮在跑"
				return new Busy(observedRunId(sessionId, runId), false);
			}
			// 结果不可知：<b>不宣称 Redis 接管成功</b> —— 不重启 watchdog、不更新 lockValue，
			// 已有 watchdog 仍按上一次确认的值续期（续不上就自停）。
			// 但本地 token 必须跟上：进程内门是本体，调用方随后会用新 token 释放；
			// token 停在旧值会让那次释放摘不掉本地门（会话被永久占住）。
			previous.token = token;
			previous.degraded = true;
			return null;
		}

		Boolean taken = takeLock(sessionId, runId, token);
		if (Boolean.TRUE.equals(taken)) {
			candidate.lockValue = encode(runId, token);
			startWatchdog(sessionId, runId, candidate.lockValue, candidate);
			return null;
		}
		if (Boolean.FALSE.equals(taken)) {
			// 另一实例持有：让出进程内门，按对方 runId 拒绝
			this.inProcess.remove(sessionId, candidate);
			return new Busy(observedRunId(sessionId, runId), false);
		}
		// §13.1：锁服务不可用/结果不可知 → 降级为进程内单值门（M1 单实例下等价）。
		// 刻意不写兜底 SET：若脚本其实已执行而回包丢失，锁按 TTL 自行过期（fencing 由续跑租约兜底）；
		// lockValue 保持 null ⇒ 释放只按协议值 {runId|token} 走脚本验证（不猜、不裸删）。
		candidate.degraded = true;
		return null;
	}

	/**
	 * 原子取门/同 run 接管。
	 *
	 * @return {@code TRUE} = 脚本确认本 run 现在持有；{@code FALSE} = 另一轮持有；
	 * {@code null} = Redis 异常或结果不可知（<b>不得</b>当成功）
	 */
	private Boolean takeLock(String sessionId, String runId, String token) {
		return runLockScript(sessionId, ACQUIRE_SCRIPT, "取门/接管", encode(runId, token), ttlMs(), runId);
	}

	/** 轮终态主动释放（token = runId：首轮/旧调用点）。 */
	public void release(String sessionId, String runId) {
		release(sessionId, runId, runId);
	}

	/**
	 * 轮终态主动释放：只释放<b>自己这个 token</b> 持有的那一轮（I2）。
	 *
	 * <p>旧 token 的迟到 finally 对新持有者是 no-op —— 不会删掉续跑方的锁、
	 * 也不会 cancel 对方刚起的 watchdog。Redis 侧是单键脚本
	 * （{@link #RELEASE_SCRIPT}：锁值<b>精确等于</b>要释放的那个值才 DEL），因此
	 * "GET 时还是自己的、DEL 时已被换主"的窗口不存在。
	 *
	 * <p>要释放的值取<b>本 holder 上一次确认过的锁值</b>（{@code lockValue}）：降级/结果不可知
	 * 之后锁里可能仍是上一个 token 的值，而调用方手里已经是新 token —— 仍按真值释放，
	 * 且脚本会验证它确实属于本轮。没有 holder（跨线程/进程收尾）时按协议值
	 * {@code runId|token} 释放，同样由脚本精确验证。
	 *
	 * @return 是否真的释放了（进程内门或 Redis 锁任一）；Redis 异常/结果不可知时只报进程内事实
	 */
	public boolean release(String sessionId, String runId, String token) {
		boolean released = false;
		String expected = encode(runId, token);
		Holder holder = this.inProcess.get(sessionId);
		if (holder != null && holder.runId.equals(runId) && token.equals(holder.token)) {
			this.inProcess.remove(sessionId, holder);
			cancelWatchdog(holder);
			released = true;
			if (holder.lockValue != null) {
				expected = holder.lockValue;
			}
		}
		Boolean deleted = runLockScript(sessionId, RELEASE_SCRIPT, "释放", expected);
		if (Boolean.TRUE.equals(deleted)) {
			released = true;
		}
		else if (deleted == null) {
			log.warn("会话锁释放结果不可知 sessionId={} runId={}（未做裸 DEL；锁按 TTL 自行过期）", sessionId,
					runId);
		}
		return released;
	}

	/**
	 * 判死路径的强释放：该轮已确认进程内无订阅者、不可能再推进，按 runId 释放
	 * （调用方拿不到原 token）。<b>只给</b> {@code AiController} 的僵尸判定用。
	 *
	 * <p>Redis 侧是单键脚本（{@link #RELEASE_DEAD_SCRIPT}）：只删 {@code runId} 或
	 * {@code runId|*} 形态的锁；另一轮的锁一个字节都不动。
	 */
	public void releaseDeadHeld(String sessionId, String runId) {
		Holder holder = this.inProcess.get(sessionId);
		if (holder != null && holder.runId.equals(runId)) {
			this.inProcess.remove(sessionId, holder);
			cancelWatchdog(holder);
		}
		Boolean deleted = runLockScript(sessionId, RELEASE_DEAD_SCRIPT, "强释放", runId);
		if (deleted == null) {
			log.warn("会话锁强释放结果不可知 sessionId={} runId={}（未做裸 DEL；锁按 TTL 自行过期）", sessionId,
					runId);
		}
	}

	/** 进行中轮的 runId（null = 空闲）。锁值是 {@code runId|token}，取 {@code |} 前段。 */
	public String currentRunId(String sessionId) {
		Holder holder = this.inProcess.get(sessionId);
		if (holder != null) {
			return holder.runId;
		}
		try {
			return runIdOfValue(this.redis.opsForValue().get(LOCK_PREFIX + sessionId));
		}
		catch (Exception ex) {
			return null;
		}
	}

	public int heldSessions() {
		return this.inProcess.size();
	}

	public long renewals() {
		return this.renewals.get();
	}

	/**
	 * 续期一次（单键脚本 {@link #RENEW_SCRIPT}：锁值<b>恰好</b>等于 {@code expectedValue} 才 PEXPIRE）。
	 *
	 * <p><b>异常不吞</b>：Redis 异常向上抛给 watchdog，由它按 §13.1 降级处理 ——
	 * 绝不把异常/不可知当"续期成功"。键不存在或已换主返回 false（watchdog 据此停续期）。
	 *
	 * @return true = 确实续到了自己的锁；false = 键已不存在或已换主
	 */
	boolean renewLockOnce(String sessionId, String expectedValue) {
		Long renewed = this.redis.execute(RENEW_SCRIPT, List.of(LOCK_PREFIX + sessionId), expectedValue, ttlMs());
		if (renewed == null || renewed != 1L) {
			return false;
		}
		this.renewals.incrementAndGet();
		return true;
	}

	/**
	 * 单键脚本的唯一执行口：KEYS 恰好一个（Cluster 单槽），ARGV 逐项透传。
	 *
	 * @return {@code TRUE} = 脚本返回 1；{@code FALSE} = 脚本返回 0；{@code null} = 异常或结果不可知
	 */
	private Boolean runLockScript(String sessionId, RedisScript<Long> script, String action, Object... args) {
		try {
			Long result = this.redis.execute(script, List.of(LOCK_PREFIX + sessionId), args);
			if (result == null) {
				log.warn("会话锁{}结果不可知（脚本未返回）sessionId={}（未做任何写入兜底）", action, sessionId);
				return null;
			}
			return result == 1L;
		}
		catch (Exception ex) {
			log.warn("会话锁{}失败 sessionId={}（Redis 异常，未做任何写入兜底）：{}", action, sessionId, ex.getMessage());
			return null;
		}
	}

	/** 失败的诊断口径：脚本已判定"不是本 run 的锁"，这里只读一次来填 409 的 heldBy（不参与任何写决策）。 */
	private String observedRunId(String sessionId, String fallback) {
		try {
			String observed = runIdOfValue(this.redis.opsForValue().get(LOCK_PREFIX + sessionId));
			return observed == null ? fallback : observed;
		}
		catch (Exception ex) {
			return fallback;
		}
	}

	private String ttlMs() {
		return Long.toString(Math.max(1L, this.lockProperties.getTtl().toMillis()));
	}

	private void startWatchdog(String sessionId, String runId, String expectedValue, Holder holder) {
		Duration interval = this.lockProperties.getWatchdog();
		long period = Math.max(1, interval.toMillis());
		// 重入/重新取门时旧 watchdog 必须先停：同一 holder 上留两个续期任务既浪费也无法在
		// release 时一次性停掉（release 只 cancel 最新那一个）。
		cancelWatchdog(holder);
		holder.watchdog = this.watchdog.scheduleAtFixedRate(() -> {
			try {
				if (!renewLockOnce(sessionId, expectedValue)) {
					log.warn("会话锁已失效或换主，停止续期 sessionId={} runId={}", sessionId, runId);
					cancelWatchdog(holder);
				}
			}
			catch (Exception ex) {
				holder.degraded = true;
				log.warn("会话锁续期异常，降级为进程内单值门 sessionId={}：{}", sessionId, ex.getMessage());
			}
		}, period, period, TimeUnit.MILLISECONDS);
	}

	private static String encode(String runId, String token) {
		return token == null || token.equals(runId) ? runId : runId + "|" + token;
	}

	private static String runIdOfValue(String value) {
		if (value == null) {
			return null;
		}
		int separator = value.indexOf('|');
		return separator < 0 ? value : value.substring(0, separator);
	}

	private void cancelWatchdog(Holder holder) {
		ScheduledFuture<?> future = holder.watchdog;
		if (future != null) {
			future.cancel(false);
		}
	}

	/**
	 * 占用失败的结局。
	 *
	 * @param runId 进行中那一轮的 runId（前端据此 reattach）
	 * @param degraded 是否为锁降级形态（Redis 锁不可用，仅进程内互斥）
	 */
	public record Busy(String runId, boolean degraded) {
	}

	private static final class Holder {

		private final String runId;

		/** 本次持有的 token（可被同轮接管改写；本地释放匹配用）。 */
		private volatile String token;

		/**
		 * <b>最后一次被脚本确认</b>写进 Redis 的锁值（null = 从未确认，如降级/结果不可知）。
		 *
		 * <p>释放与 watchdog 只认这个值，不按调用方手里的 token 去猜 —— 否则
		 * "接管结果不可知但本地已换 token"会让释放指向一个不存在的值（锁白留到 TTL）。
		 */
		private volatile String lockValue;

		private volatile boolean degraded;

		private volatile ScheduledFuture<?> watchdog;

		Holder(String runId, String token) {
			this.runId = runId;
			this.token = token;
		}

	}

}
