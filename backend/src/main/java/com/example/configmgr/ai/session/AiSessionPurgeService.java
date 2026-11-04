package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.form.FormAttachmentStore;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ClientRequestRegistry;
import com.example.configmgr.ai.run.CreateTaskDraftStore;
import com.example.configmgr.ai.run.FrameStore;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.common.AiStateUnavailableException;
import com.example.configmgr.file.FileStorageService;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 会话销毁（{@code DELETE /api/ai/session/{sessionId}} 的本体）。
 *
 * <h2>身份隔离（2026-10-30，租户任务 2）</h2>
 * 销毁是<b>身份内</b>的操作：run/session 数据键都带 {@code tenantId/userId}（{@link TenantKeyScope}，
 * run 级键是带身份的 hash tag），跨租户/跨用户即使 sessionId 相同也互不可见、互不删除。
 * 销毁<b>不读</b>全局 {@code ai:runs}：轮次候选只来自本身份的会话索引
 * （{@code ai:session:runs:<t>:<u>:<sid>}，键面已带身份）与会话门/current 点名，
 * 因此既不需要扫描租户列表，也没有跨身份过滤面。
 * <ul>
 * <li><b>入口身份</b>：{@link #purge(String, String)} 取当前 {@link RequestContextUtils} 上下文
 * （HTTP 边界已校验）；显式重载供调用方/用例直接给身份；无上下文 = legacy 命名空间（独立过渡面）；</li>
 * <li><b>记录身份</b>：pending 台账登记时固化 {@code tenantId/userId}；
 * {@link SessionPurgeRetryRunner} 重试时按<b>记录</b> {@code useContext} 恢复后再调
 * {@link #retry}/{@link #verifyMemoryOnly} —— 不扫描租户列表；</li>
 * <li><b>run 归属</b>：只带 sessionId 的索引/快照不足以归属 —— 删除前用快照的
 * {@code tenantId/userId} 再核对一次；不一致或<b>无归属 legacy</b> 的 run 一律不删（只记 warning）；</li>
 * <li><b>浏览器清理</b>：{@code clientCleanup} 给出与前端一致的带身份键形
 * （{@code ai-mirror:<t>:<u>:<sid>}、{@code cm.ai.form.draft.<t>:<u>.}），旧键形只给 legacy 身份。</li>
 * </ul>
 *
 * <h2>删除面（只删 AI 数据）</h2>
 * <ol>
 * <li>{@code chat:mem:*}（当前身份键 + <b>本会话的 staging 中间态</b>）；staging 由同槽会话索引精确枚举，
 * 删除与索引摘成员走同槽 Lua 的有界 pipeline；残留或结果不可知一律 {@code complete=false}；</li>
 * <li>该会话每一轮的<b>13 个 run 级键</b>（委托 {@link RunStore#deleteRun(String)}，按 run 逐个删，
 * 不使用 KEYS/通配删除）；观测/取消键与 xtrim/固定 xack 以逐 run 单键 DEL pipeline 精确销毁；</li>
 * <li><b>会话→run 索引</b>：{@code ai:session:runs:<sid>} 是轮次候选的唯一发现面（首轮创建时
 * 原子登记、写失败 fail-closed；删除轮次 SREM）。本类只读本会话候选，并对每个候选核对快照的
 * {@code sessionId} 与租户身份；索引读失败 ⇒ 枚举整次中止（一个数据键都还没删）；</li>
 * <li>{@code ai:ctx:<sid>} / {@code ai:ctx:ledger:<sid>} / {@code ai:ctx:archive:<sid>:<turnNo>} /
 * {@code ai:safety:<sid>}；</li>
 * <li>表单附件：该会话的登记、登记索引成员、<b>且只按登记里的 storagePath 删文件</b>（不扫目录）；</li>
 * <li>{@code ai_feedback} 表<b>该身份 + 该会话</b>的行（H2 先行；按 {@code sessionId + tenantId + userId}
 * 三元组定位，跨租户同 sessionId 不误删；无身份上下文 = legacy {@code (0,0)} 命名空间）；</li>
 * <li>{@code ai:session:<sid>} 会话记录；<b>会话级 {@code create_task} 候选草案</b>
 * （{@code ai:ctdraft:{<t>:<u>:session:<sid>}}，单键 HASH，字段 = taskId）：按同一份键拼接口精确
 * {@code DEL}，<b>不扫描</b>；失败进 {@code errors} ⇒ {@code complete=false} + pending 保留；</li>
 * <li><b>发现键最后删</b>：{@code ai:session:current:<sid>} / {@code ai:session:runs:<sid>} /
 * {@code ai:session:active:<sid>} 只在本次删除主体<b>无 errors 且无 deferred</b> 时才删。
 * 提前删会让 deferred/失败的轮次重试时无法再被枚举，pending/guard 却仍在（永久 410）；</li>
 * <li>浏览器本地镜像/草稿：服务端无法访问 sessionStorage，结果里给出精确的
 * {@code clientCleanup}（镜像键 + 草稿键前缀 + 本轮 toolCallId 清单），由前端执行删除。</li>
 * </ol>
 *
 * <h2>轮次候选（会话索引 + 点名）</h2>
 * 只读 {@code ai:session:runs:<sid>}（create 原子 SADD+PEXPIRE / deleteRun SREM）拿本会话候选，
 * 再补上会话门与 {@code ai:session:current:<sid>} 点名的轮次；<b>不再回退</b>全局 {@code ai:runs}
 * （那需要对海量快照逐个反序列化，且会把他会话的轮次带进归属判定）。索引读失败按 fail-closed
 * 中止枚举；索引为空只表示本会话没有轮次。
 *
 * <h2>绝不碰（负向清单）</h2>
 * 业务作业（{@code jobs}/{@code job_items}）、{@code task_files}、{@code config_staging_rows}、
 * 任务/配置/provider/model/setting/KB/审计、资源锁 {@code ai:lock:*}、业务文件目录，
 * 以及<b>其它会话</b>的任何键/行/文件。本类没有任何通配删除、没有目录清空、没有 {@code FLUSHDB}。
 *
 * <h2>顺序（用户裁决）</h2>
 * <b>先枚举 → 登记 durable pending → 装守卫 → H2 删 → 取消活跃 run → Redis 按 run 删 →
 * 上下文/附件/会话记录/草稿 →（仅主体无 errors/deferred 时）发现键 → 守卫保留期 + 移除 pending</b>。
 * 枚举失败<b>一个键都不删</b>；H2 删失败也不进入 Redis 删除（反馈表 DELETE 在自己的本地事务里）。
 * 重复 DELETE <b>复用已有 pending</b>（同一 purgeId/owner，不重置 attempts）；中止回滚只撤销
 * "本次新建"的台账，不会抹掉上一轮留下的 deferred 义务。
 * Redis/H2 的后续失败或活跃 run 延后 → {@code complete=false}、pending 保留，
 * 由 {@link SessionPurgeRetryRunner} 有界自动重试（或再次 DELETE 手动重试）。
 * <b>不新建分布式事务</b>：H2 本地事务 + Redis 单键/多键命令，顺序由本类保证。
 *
 * <h2>活跃 run</h2>
 * 非终态快照（或会话门仍指向它）= 活跃：先 {@link CancellationRegistry#cancel}（已取消则跳过，
 * 不重复副作用），再在短窗口内等它落终态。落不了终态的 run <b>不删 13 键</b>（会被正在收尾的
 * 写回/重启续跑复活），列入 {@code deferred}；结果 {@code complete=false}，pending 保留由调度
 * 在终态后补删。记忆写回由 {@link SessionPurgeGuard} 单独拦截（终局 {@code saveAll} 不再复活历史）。
 *
 * <h2>前缀碰撞 / 特殊 sessionId</h2>
 * 会话级键全部按<b>精确键名</b>删除（{@code ai:ctx:<sid>} 不会命中 {@code ai:ctx:<sid>X}）。
 * 归档键必须扫后缀，故：① 对 sessionId 做 Redis glob 转义；② 只接受<b>纯数字</b>的 turnNo
 * 后缀 —— {@code a} 与 {@code a:1} 这种"冒号嵌套"的 id 不会互相误删。
 */
@Slf4j
@Service
public class AiSessionPurgeService {

	/** 浏览器会话镜像键前缀（前端 {@code MIRROR_PREFIX}，服务端只回报、不伪造删除）。 */
	static final String CLIENT_MIRROR_PREFIX = "ai-mirror:";

	/** 浏览器表单草稿键前缀（前端 {@code cm.ai.form.draft.<toolCallId>}）。 */
	static final String CLIENT_DRAFT_PREFIX = "cm.ai.form.draft.";

	/**
	 * 结算观测位键前缀（{@code ai:observed:<tenantId>:<userId>:<runId>}，HASH，首写即置 session-ttl）。
	 *
	 * <p>键形与 {@code SettlementObservationStore#observedKey} 同一拼接口（{@link TenantKeyScope}，
	 * 与 {@code ai:beat:}/{@code ai:activity:} 同族）：<b>store 拥有写入，本类只负责
	 * "销毁时按当前身份的 runId 删掉同一把键"</b>。此处只引用前缀常量而不引用 store 类 —— 单键 DEL
	 * 一个不存在的键是 no-op，前向兼容；但身份段必须从同一处算，否则会删错（跨租户同 runId）。
	 */
	static final String OBSERVED_PREFIX = "ai:observed:";

	private static final Duration DEFAULT_ACTIVE_WAIT = Duration.ofMillis(1500);

	private static final Duration EXECUTION_LEASE = Duration.ofSeconds(30);

	private static final Duration EXECUTION_BUDGET = Duration.ofSeconds(10);

	private static final int REDIS_BATCH_SIZE = 64;

	private static final RedisScript<Long> TYPE_DEL = new DefaultRedisScript<>("""
			-- PURGE_TYPE_DEL
			if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
			local actual = redis.call('TYPE', KEYS[1])
			if type(actual) == 'table' then actual = actual['ok'] end
			if actual ~= ARGV[1] then return -1 end
			return redis.call('DEL', KEYS[1])
			""", Long.class);

	/**
	 * <b>fix4：complete 之后"一次性记忆复核"的延迟</b>（静态常量，刻意不新增配置面）。
	 *
	 * <p>为什么需要：写者在 RENAME 已生效、{@code saveAll} 的 finally 复检完成前 crash 时，那份额外
	 * 落地到 {@code chat:mem:*} 的历史没有任何写侧补偿（进程已死），而销毁已报 {@code complete=true}。
	 * 复核窗口取 60s：远短于 guard 保留期（{@code session-ttl}），足够覆盖"最后一段在飞写回的落地"，
	 * 又短到不会把待复核台账长期积压；且到点只做一次记忆面删除+复核，不做全量 retry。
	 */
	static final long MEMORY_VERIFY_DELAY_MS = 60_000L;

	private static final long ACTIVE_POLL_MILLIS = 50L;

	/** 单次执行的 archive 批上限；剩余范围由 durable cursor 接续。 */
	private static final int MAX_DERIVED_ARCHIVE_TURNS = 2000;

	private final StringRedisTemplate redis;

	private final RunStore runStore;

	private final SessionGate sessionGate;

	private final CancellationRegistry cancellations;

	/**
	 * 取消收尾的窄入口（F3）：取消旗标写完之后，驱动进程已死的轮次没有任何人写终态 ——
	 * 只等 {@code awaitTerminal} 必然超时，13 键被无限延后（{@code ai:purge:pending} 无 TTL
	 * ⇒ 会话永久 410 且删不掉）。**字段注入 + ObjectProvider** 保持构造器签名（多份既有用例
	 * 直接 {@code new}），也不把销毁流程与 AI 运行面的创建顺序耦合；未装配（轻装配/单测）时
	 * 保持旧行为：只写旗标 + 等一个短窗口。
	 */
	@Autowired(required = false)
	private org.springframework.beans.factory.ObjectProvider<ResumeService> resumeServiceProvider;

	@Autowired(required = false)
	private org.springframework.beans.factory.ObjectProvider<FrameStore> frameStoreProvider;

	private final ChatMemoryRepository chatMemoryRepository;

	private final FormAttachmentStore attachments;

	private final FileStorageService fileStorage;

	private final SessionPurgeDatabase database;

	private final SessionPurgeGuard guard;

	private final SessionPurgePendingStore pendingStore;

	private final AiProperties properties;

	private final Duration activeWait;

	@Autowired
	public AiSessionPurgeService(StringRedisTemplate redis, RunStore runStore, SessionGate sessionGate,
			CancellationRegistry cancellations, ChatMemoryRepository chatMemoryRepository,
			FormAttachmentStore attachments, FileStorageService fileStorage, SessionPurgeDatabase database,
			SessionPurgeGuard guard, SessionPurgePendingStore pendingStore, AiProperties properties) {
		this(redis, runStore, sessionGate, cancellations, chatMemoryRepository, attachments, fileStorage, database,
				guard, pendingStore, properties, DEFAULT_ACTIVE_WAIT);
	}

	/** 测试/特殊装配：可把活跃 run 的等待窗口设为 0（不 sleep）。 */
	public AiSessionPurgeService(StringRedisTemplate redis, RunStore runStore, SessionGate sessionGate,
			CancellationRegistry cancellations, ChatMemoryRepository chatMemoryRepository,
			FormAttachmentStore attachments, FileStorageService fileStorage, SessionPurgeDatabase database,
			SessionPurgeGuard guard, SessionPurgePendingStore pendingStore, AiProperties properties,
			Duration activeWait) {
		this.redis = redis;
		this.runStore = runStore;
		this.sessionGate = sessionGate;
		this.cancellations = cancellations;
		this.chatMemoryRepository = chatMemoryRepository;
		this.attachments = attachments;
		this.fileStorage = fileStorage;
		this.database = database;
		this.guard = guard;
		this.pendingStore = pendingStore;
		this.properties = properties == null ? new AiProperties() : properties;
		this.activeWait = activeWait == null || activeWait.isNegative() ? Duration.ZERO : activeWait;
	}

	// ── 对外结果 ────────────────────────────────────────────────────────────

	/** 一轮 run 的销毁视图。 */
	public record RunView(String runId, String status, boolean active, boolean deleted, boolean deferred) {
	}

	/** 延后删除的活跃 run（终态后重试同一端点即可补删 13 键）。 */
	public record DeferredView(String runId, String status, String reason) {
	}

	/** 记忆面。 */
	public record MemoryView(boolean existed, int messages, boolean deleted) {
	}

	/** 上下文键族。 */
	public record ContextView(boolean context, boolean ledger, int archives, boolean safety, int deletedKeys) {
	}

	/** 附件面（文件只按登记路径删）。 */
	public record AttachmentView(int registered, int registrationsDeleted, int filesDeleted,
			List<String> failedFileIds) {
	}

	/** 会话键面。 */
	public record SessionView(boolean currentDeleted, boolean recordDeleted) {
	}

	/** 用户展示历史面（B2）：{@code entries} 为枚举时的条目数；{@code deleted} = 任一关联键已删。 */
	public record TranscriptView(boolean existed, long entries, boolean deleted) {
	}

	/** 浏览器本地镜像/草稿的清理指令（服务端无法直接删 sessionStorage）。 */
	public record ClientCleanup(String mirrorKey, String draftPrefix, List<String> draftToolCallIds) {
	}

	/** 一次销毁的完整结果（{@code complete=false} 时看 {@code errors}/{@code deferred}）。 */
	public record PurgeResult(String sessionId, String purgeId, boolean complete, boolean found, String phase,
			SessionPurgeDatabase.Snapshot databaseSnapshot, SessionPurgeDatabase.Deleted databaseDeleted,
			MemoryView memory, TranscriptView transcript, List<RunView> runs, List<DeferredView> deferred,
			ContextView context, AttachmentView attachments, SessionView session,
			ClientCleanup clientCleanup, List<String> errors, List<String> warnings) {

		/** 供 HTTP 端点直接序列化的裸对象（与 {@code /history}、{@code /cancel} 同姿态）。 */
		public Map<String, Object> toBody() {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("sessionId", this.sessionId);
			body.put("purgeId", this.purgeId);
			body.put("complete", this.complete);
			body.put("found", this.found);
			body.put("aborted", this.phase != null);
			body.put("phase", this.phase);
			Map<String, Object> db = new LinkedHashMap<>();
			db.put("feedbackRows", this.databaseSnapshot == null ? 0 : this.databaseSnapshot.feedbackRows());
			db.put("feedbackDeleted", this.databaseDeleted == null ? 0 : this.databaseDeleted.feedbackRows());
			body.put("database", db);
			body.put("memory", Map.of("existed", this.memory.existed(), "messages", this.memory.messages(),
					"deleted", this.memory.deleted()));
			body.put("transcript", Map.of("existed", this.transcript.existed(), "entries", this.transcript.entries(),
					"deleted", this.transcript.deleted()));
			body.put("runs", this.runs.stream().map(run -> {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("runId", run.runId());
				item.put("status", run.status());
				item.put("active", run.active());
				item.put("deleted", run.deleted());
				item.put("deferred", run.deferred());
				return item;
			}).toList());
			body.put("deferred", this.deferred.stream().map(item -> {
				Map<String, Object> view = new LinkedHashMap<>();
				view.put("runId", item.runId());
				view.put("status", item.status());
				view.put("reason", item.reason());
				return view;
			}).toList());
			body.put("context", Map.of("context", this.context.context(), "ledger", this.context.ledger(),
					"archives", this.context.archives(), "safety", this.context.safety(),
					"deletedKeys", this.context.deletedKeys()));
			body.put("attachments", Map.of("registered", this.attachments.registered(),
					"registrationsDeleted", this.attachments.registrationsDeleted(),
					"filesDeleted", this.attachments.filesDeleted(),
					"failedFileIds", this.attachments.failedFileIds()));
			body.put("session", Map.of("currentDeleted", this.session.currentDeleted(), "recordDeleted",
					this.session.recordDeleted()));
			body.put("clientCleanup", Map.of("mirrorKey", this.clientCleanup.mirrorKey(), "draftPrefix",
					this.clientCleanup.draftPrefix(), "draftToolCallIds", this.clientCleanup.draftToolCallIds()));
			body.put("errors", this.errors);
			body.put("warnings", this.warnings);
			return body;
		}

	}

	// ── 主流程 ──────────────────────────────────────────────────────────────

	/**
	 * 销毁一个会话的全部 AI 数据（身份取当前上下文；无上下文 = legacy 命名空间）。
	 *
	 * @param sessionId 会话 id（前端页签级；允许特殊字符，所有目标均按精确键/索引处理）
	 * @param requestedBy 发起方（写进取消标志与守卫，便于取证；null = unknown）
	 * @return 幂等结果；{@code complete=false} 表示有延后/部分失败，可重试
	 */
	public PurgeResult purge(String sessionId, String requestedBy) {
		return purge(sessionId, requestedBy, RequestContextUtils.currentOrNull());
	}

	/**
	 * 销毁一个会话的全部 AI 数据，<b>显式指定身份</b>（HTTP 边界/用例用；不读线程上下文）。
	 *
	 * <p>身份在入口固定：pending 台账写入 {@code tenantId/userId}，删除主体全程在该身份的
	 * {@link RequestContextUtils#useContext} 作用域内执行 —— 于是 RunStore/记忆/附件等下游
	 * 按上下文算出的键必然是本身份的键。
	 *
	 * @return 幂等结果；{@code complete=false} 表示有延后/部分失败，可重试
	 */
	public PurgeResult purge(String sessionId, String requestedBy, long tenantId, long userId) {
		return purge(sessionId, requestedBy, new TenantUser(tenantId, userId));
	}

	private PurgeResult purge(String sessionId, String requestedBy, TenantUser identity) {
		if (sessionId == null || sessionId.isBlank()) {
			throw new IllegalArgumentException("sessionId 不得为空白");
		}
		try (RequestContextUtils.SafeCloseable ignored = scopeOf(identity)) {
			return purgeInternal(sessionId, requestedBy, identity);
		}
	}

	private PurgeResult purgeInternal(String sessionId, String requestedBy, TenantUser identity) {
		String requestedPurgeId = UUID.randomUUID().toString();
		String actor = requestedBy == null || requestedBy.isBlank() ? "unknown" : requestedBy;
		SessionPurgePendingStore.Registration registration;
		try {
			registration = this.pendingStore.register(identity, sessionId, requestedPurgeId, actor,
					System.currentTimeMillis());
		}
		catch (RuntimeException ex) {
			log.warn("Session purge: pending registration failed, nothing deleted sessionId={}: {}", sessionId,
					rootMessage(ex));
			return aborted(sessionId, requestedPurgeId, "pending", enumerationForFailure(sessionId, identity), ex);
		}
		String purgeId = registration.pending().purgeId();
		String owner = registration.pending().requestedBy() == null || registration.pending().requestedBy().isBlank()
				? actor : registration.pending().requestedBy();
		SessionPurgePendingStore.AcquireResult claim = this.pendingStore.acquire(sessionId, purgeId,
				UUID.randomUUID().toString(), System.currentTimeMillis(), EXECUTION_LEASE.toMillis());
		if (!claim.acquired()) {
			return inProgress(sessionId, purgeId, claim.pending(), identity);
		}
		PurgeExecution execution = new PurgeExecution(claim.execution());

		Enumeration enumeration;
		try {
			execution.checkpoint("ENUMERATING");
			enumeration = enumerate(sessionId, registration.pending().archiveCursor());
		}
		catch (RuntimeException ex) {
			recordFailureQuietly(execution, "enumerate: " + rootMessage(ex));
			log.warn("Session purge: enumeration failed, nothing deleted sessionId={} purgeId={}: {}", sessionId,
					purgeId, rootMessage(ex));
			return aborted(sessionId, purgeId, "enumerate", enumerationForFailure(sessionId, identity), ex);
		}

		try {
			execution.checkpoint("GUARD");
			this.guard.arm(sessionId, purgeId + "|" + owner, guardTtl());
		}
		catch (RuntimeException ex) {
			recordFailureQuietly(execution, "guard: " + rootMessage(ex));
			log.warn("Session purge: guard arm failed, nothing deleted sessionId={} purgeId={}: {}", sessionId,
					purgeId, rootMessage(ex));
			return aborted(sessionId, purgeId, "guard", enumeration, ex);
		}

		SessionPurgeDatabase.Deleted dbDeleted;
		try {
			execution.checkpoint("DATABASE");
			dbDeleted = deleteFeedback(sessionId);
		}
		catch (RuntimeException ex) {
			recordFailureQuietly(execution, "database: " + rootMessage(ex));
			log.warn("Session purge: H2 delete failed, Redis untouched sessionId={} purgeId={}: {}", sessionId,
					purgeId, rootMessage(ex));
			return aborted(sessionId, purgeId, "database", enumeration, ex);
		}

		return executeDeletion(execution, purgeId, owner, enumeration, dbDeleted);
	}

	/**
	 * 自动/手动重试入口：复用已有 pending 的 purgeId，绝不重新登记 pending 或改写新守卫。
	 *
	 * <p><b>身份必须已按记录恢复</b>：本方法按"当前身份"定位台账与全部键。调用方
	 * （{@link SessionPurgeRetryRunner}）先读台账、再用台账里的 {@code tenantId/userId}
	 * {@code useContext}，然后才调这里 —— 不猜身份、不扫租户列表。
	 *
	 * @return null = pending 不存在、正在执行或已换主（旧重试应停止）；否则与 {@link #purge} 同形结果
	 */
	public PurgeResult retry(String sessionId, String purgeId, String requestedBy) {
		if (sessionId == null || sessionId.isBlank() || purgeId == null || purgeId.isBlank()) {
			return null;
		}
		SessionPurgePendingStore.Pending pending = this.pendingStore.current(sessionId);
		if (pending == null || !purgeId.equals(pending.purgeId())) {
			return null;
		}
		String actor = requestedBy == null || requestedBy.isBlank() ? "unknown" : requestedBy;
		SessionPurgePendingStore.AcquireResult claim = this.pendingStore.acquire(sessionId, purgeId,
				UUID.randomUUID().toString(), System.currentTimeMillis(), EXECUTION_LEASE.toMillis());
		if (!claim.acquired()) {
			return null;
		}
		PurgeExecution execution = new PurgeExecution(claim.execution());

		Enumeration enumeration;
		try {
			execution.checkpoint("ENUMERATING");
			enumeration = enumerate(sessionId, pending.archiveCursor());
		}
		catch (RuntimeException ex) {
			recordFailureQuietly(execution, "enumerate: " + rootMessage(ex));
			return aborted(sessionId, purgeId, "enumerate",
					enumerationForFailure(sessionId, pending.identityOrNull()), ex);
		}

		SessionPurgeDatabase.Deleted dbDeleted;
		try {
			execution.checkpoint("DATABASE");
			dbDeleted = deleteFeedback(sessionId);
		}
		catch (RuntimeException ex) {
			recordFailureQuietly(execution, "database: " + rootMessage(ex));
			return aborted(sessionId, purgeId, "database", enumeration, ex);
		}

		return executeDeletion(execution, purgeId, actor, enumeration, dbDeleted);
	}

	private PurgeResult executeDeletion(PurgeExecution execution, String purgeId, String actor,
			Enumeration enumeration, SessionPurgeDatabase.Deleted dbDeleted) {
		try {
			return executeDeletionChecked(execution, purgeId, actor, enumeration, dbDeleted);
		}
		catch (RuntimeException ex) {
			recordFailureQuietly(execution, "delete: " + rootMessage(ex));
			return aborted(execution.sessionId(), purgeId, "delete", enumeration, ex);
		}
	}

	/** 删除主体（首轮与自动重试共用）；complete 仅在全部删除 + 守卫收尾 + pending 移除都成功时为 true。 */
	private PurgeResult executeDeletionChecked(PurgeExecution execution, String purgeId, String actor,
			Enumeration enumeration, SessionPurgeDatabase.Deleted dbDeleted) {
		String sessionId = execution.sessionId();
		execution.checkpoint("CANCELLING");
		// 活跃 run 先取消（已取消不重复取消），并给收尾一个短窗口。
		List<String> errors = new ArrayList<>();
		// 枚举阶段已经发现的"跳过非本身份"等结论随结果返回，不能只留在日志里。
		List<String> warnings = new ArrayList<>(enumeration.warnings());
		cancelActiveRuns(enumeration.runs(), actor, warnings);
		awaitTerminal(enumeration.runs(), warnings);
		Enumeration current = reloadRunStatuses(enumeration, warnings);

		// Redis 面：记忆 → 按 run 删 13 键 → 上下文 → 会话记录/草稿 → 附件文件
		// → 最后才删 sessionRuns/sessionActive/sessionCurrent 三把发现键（仅当主体无错误且无 defer）。
		// 状态刷新阶段读到的终态快照随枚举结果带下来：删除阶段对同一把键不再整份重读
		// （活跃轮不受影响 —— 它们仍要重新读一次判活，见 deleteOrDefer）。
		execution.checkpoint("DELETE_MEMORY");
		MemoryView memory = deleteMemory(execution, current, errors);
		TranscriptView transcriptView = deleteTranscript(execution, current.transcriptExisted(),
				current.transcriptEntries(), errors, warnings);
		List<RunView> runViews = new ArrayList<>();
		List<DeferredView> deferred = new ArrayList<>();
		execution.checkpoint("DELETE_RUNS");
		for (RunTarget target : current.runs()) {
			execution.checkDeadline();
			deleteOrDefer(execution, target, current.settledSnapshots(), runViews, deferred, errors, warnings);
		}
		execution.checkpoint("DELETE_CONTEXT");
		ContextView context = deleteContext(execution, current.context(), errors, warnings);
		execution.checkpoint("DELETE_SESSION");
		SessionView session = deleteSessionRecordKeys(execution, errors, warnings);
		execution.checkpoint("DELETE_ATTACHMENTS");
		AttachmentView attachmentView = deleteAttachments(execution, current.attachments(), errors, warnings);

		boolean deletionComplete = errors.isEmpty() && deferred.isEmpty();
		if (deletionComplete) {
			execution.checkpoint("DELETE_DISCOVERY");
			// 只有"本次删除主体确实删净"才允许拆掉发现面。提前删会让 deferred/失败的轮次在
			// 重试时无法再被枚举到，pending/guard 却仍在 —— 会话永久 410 且删不掉。
			session = deleteDiscoveryKeys(execution, session, errors, warnings);
			deletionComplete = errors.isEmpty() && deferred.isEmpty();
		}
		if (!deletionComplete) {
			// 保留 pending + 守卫，交给调度/手动 DELETE 续清；失败/延后不是 complete。
			String reason = !errors.isEmpty() ? errors.get(0)
					: "deferred runs: " + deferred.stream().map(DeferredView::runId).toList();
			if (!recordFailureQuietly(execution, reason)) {
				errors.add("pending: 已被另一轮销毁接管或无法更新（旧重试停止）");
			}
		}
		else {
			execution.checkpoint("FINALIZE");
			boolean retained;
			try {
				retained = this.guard.retainOrArmIfOwner(sessionId, purgeId, actor, guardTtl());
			}
			catch (RuntimeException ex) {
				retained = false;
				errors.add("guard: 收尾失败（fail-closed，保留 pending）：" + rootMessage(ex));
			}
			if (retained) {
				try {
					if (!this.pendingStore.scheduleVerify(execution.claim(),
							System.currentTimeMillis() + MEMORY_VERIFY_DELAY_MS)) {
						errors.add("pending: 已被另一轮销毁接管，未排程记忆复核");
					}
				}
				catch (RuntimeException ex) {
					errors.add("pending: 记忆复核排程失败（保留 pending 以便重试）：" + rootMessage(ex));
				}
			}
			else if (errors.isEmpty()) {
				errors.add("guard: 未被当前 purgeId 持有（owner 已变更），保留 pending");
			}
		}

		boolean complete = errors.isEmpty() && deferred.isEmpty();
		PurgeResult result = new PurgeResult(sessionId, purgeId, complete, current.found(), null,
				current.database(), dbDeleted, memory, transcriptView, runViews, deferred, context,
				attachmentView, session, current.clientCleanup(), errors, warnings);
		log.info("Session purge completed sessionId={} purgeId={} complete={} runs={} deferred={} errors={} "
				+ "memoryExisted={} feedbackDeleted={} attachments={}/{}",
				sessionId, purgeId, complete, runViews.size(), deferred.size(), errors.size(),
				memory.existed(), dbDeleted.feedbackRows(),
				attachmentView.registrationsDeleted(), attachmentView.registered());
		return result;
	}

	private boolean recordFailureQuietly(PurgeExecution execution, String reason) {
		try {
			return this.pendingStore.recordFailure(execution.claim(), reason);
		}
		catch (RuntimeException ex) {
			log.warn("Session purge: failed to record failure on pending sessionId={} purgeId={} (pending kept, not disarmed): {}", execution.sessionId(),
					execution.purgeId(), rootMessage(ex));
			return false;
		}
	}

	// ── 枚举（只读；失败即中止） ─────────────────────────────────────────────

	private Enumeration enumerate(String sessionId, Long archiveCursor) {
		String gateRunId = this.sessionGate.currentRunIdStrict(sessionId);
		String currentRunId = this.redis.opsForValue().get(this.runStore.sessionCurrentKey(sessionId));
		List<String> warnings = new ArrayList<>();
		if (currentRunId != null && currentRunId.trim().startsWith("{")) {
			// 前缀碰撞：ai:session:current:<sid> 可能是会话 "current:<sid>" 的会话记录（JSON）。
			// 它不是 runId，不能当候选轮次；会话键删除阶段会按值再判一次并跳过。
			currentRunId = null;
		}

		Set<String> candidates = new LinkedHashSet<>();
		// 会话→run 索引是唯一发现面：读失败整次中止（此刻一个数据键都还没删），
		// 不能把"读不出来"当成"没有轮次"而报 complete=true。
		try {
			candidates.addAll(this.runStore.sessionRunIds(sessionId));
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException(
					"会话销毁枚举：无法读取会话→run 索引（fail-closed，本次中止）", ex);
		}
		// 会话门/当前轮索引点名：索引可能因历史原因缺成员，但这两个名字本身就是本会话的轮次。
		if (gateRunId != null) {
			candidates.add(gateRunId);
		}
		if (currentRunId != null) {
			candidates.add(currentRunId);
		}

		List<RunTarget> runs = new ArrayList<>();
		List<String> toolCallIds = new ArrayList<>();
		for (String runId : candidates) {
			boolean named = runId.equals(gateRunId) || runId.equals(currentRunId);
			// 候选来自本身份的会话索引或 gate/current 点名：归属已由键面确定，只需读快照核对
			// sessionId 与租户身份（防索引成员被污染/旧数据串会话）。
			RunSnapshot snapshot = this.runStore.get(runId);
			if (snapshot == null) {
				// 索引里的死成员无法归属到会话；只有"会话门/当前轮索引"点名的才收进来。
				if (!named) {
					continue;
				}
				runs.add(new RunTarget(runId, "UNKNOWN", true));
			}
			else {
				if (!sessionId.equals(snapshot.getSessionId())) {
					continue;
				}
				if (!belongsToCurrentIdentity(snapshot)) {
					// 快照带的是别的身份 / 无归属 legacy：sessionId 相同但不是"我的这一轮"，
					// 绝不删它的 13 键。
					warnings.add("run[" + runId + "]: 跳过非本身份的快照（snapshotIdentity="
							+ snapshot.getTenantId() + ":" + snapshot.getUserId() + "，不删）");
					log.warn("Session purge: skip run of another identity runId={} sessionId={} snapshotIdentity={}:{} (not deleted)",
							runId, sessionId, snapshot.getTenantId(), snapshot.getUserId());
					continue;
				}
				boolean active = !isTerminal(snapshot.getStatus()) || runId.equals(gateRunId);
				runs.add(new RunTarget(runId, snapshot.getStatus(), active));
			}
			for (var pending : this.runStore.pendingsLenient(runId)) {
				if (pending.getToolCallId() != null && !pending.getToolCallId().isBlank()) {
					toolCallIds.add(pending.getToolCallId());
				}
			}
		}

		boolean memoryExisted;
		int memoryMessages = 0;
		// 在飞写回（saveAll / 按轮合并 upsert）的中间态也是本会话的 AI 数据
		// （正常路径 RENAME 后消失，清理失败/进程中断时按 TTL 自清）。
		// 只带 staging 的会话今天会被误报 found=false + complete=true 后残留，故这里一并枚举。
		// 精确索引成员随枚举结果带到删除阶段复用。
		List<String> staging = stagingKeys(sessionId);
		boolean memoryStaged = !staging.isEmpty();
		if (this.chatMemoryRepository instanceof RedisChatMemoryRepository redisRepository) {
			memoryExisted = redisRepository.hasLiveMemory(sessionId) || memoryStaged;
		}
		else {
			List<?> memory = this.chatMemoryRepository.findByConversationId(sessionId);
			memoryMessages = memory == null ? 0 : memory.size();
			memoryExisted = memoryMessages > 0 || memoryStaged;
		}
		// B2：用户展示历史独立于记忆；存在即计入 found（只删精确键，不扫描）。
		// 计数键也算"展示历史残留"：body 已删但 :bytes 还在时，found 必须如实为 true。
		String transcriptKey = com.example.configmgr.ai.history.ConversationTranscriptStore.dataKey(sessionId);
		String transcriptBytesKey = com.example.configmgr.ai.history.ConversationTranscriptStore.bytesKey(sessionId);
		boolean transcriptListExisted = Boolean.TRUE.equals(this.redis.hasKey(transcriptKey));
		boolean transcriptExisted = transcriptListExisted
				|| Boolean.TRUE.equals(this.redis.hasKey(transcriptBytesKey));
		long transcriptEntries = 0L;
		if (transcriptListExisted) {
			Long size = this.redis.opsForList().size(transcriptKey);
			transcriptEntries = size == null ? 0L : size;
		}

		ArchiveBatch archives = archiveKeys(sessionId, archiveCursor);
		ContextTargets context = new ContextTargets(
				Boolean.TRUE.equals(this.redis.hasKey(this.runStore.contextKey(sessionId))),
				Boolean.TRUE.equals(this.redis.hasKey(this.runStore.contextLedgerKey(sessionId))), archives.keys(),
				Boolean.TRUE.equals(this.redis.hasKey(TenantKeyScope.scoped(RunStore.SAFETY_PREFIX, sessionId))),
				Boolean.TRUE.equals(this.redis.hasKey(this.runStore.contextEventsKey(sessionId))),
				archives.nextCursor(), archives.hasMore());

		boolean sessionCurrentExisted = Boolean.TRUE.equals(
				this.redis.hasKey(this.runStore.sessionCurrentKey(sessionId)));
		boolean sessionRecordExisted = Boolean.TRUE.equals(
				this.redis.hasKey(AiSessionService.keyFor(sessionId)));
		// 会话级 create_task 候选草案（ai:ctdraft，字段 = taskId）：没有独立索引，存在即计入 found
		boolean draftExisted = Boolean.TRUE.equals(this.redis.hasKey(CreateTaskDraftStore.sessionKey(sessionId)));

		List<FormAttachmentStore.Attachment> attachmentList = this.attachments.findBySessionId(sessionId);
		SessionPurgeDatabase.Snapshot db = snapshotFeedback(sessionId);

		boolean found = memoryExisted || transcriptExisted || !runs.isEmpty() || context.any()
				|| !attachmentList.isEmpty() || db.feedbackRows() > 0 || sessionCurrentExisted
				|| sessionRecordExisted || draftExisted;

		ClientCleanup cleanup = clientCleanup(sessionId, toolCallIds, RequestContextUtils.currentOrNull());
		return new Enumeration(runs, Map.of(), attachmentList, context, memoryExisted, memoryMessages,
				transcriptExisted, transcriptEntries, List.copyOf(staging), db, sessionCurrentExisted,
				sessionRecordExisted, found, cleanup, List.copyOf(warnings));
	}

	/**
	 * 浏览器本地清理指令（<b>键形与前端一致</b>）。
	 *
	 * <ul>
	 * <li>镜像：{@code ai-mirror:<tenantId>:<userId>:<sessionId>}（前端 {@code mirrorStorageKey}）；</li>
	 * <li>草稿前缀：{@code cm.ai.form.draft.<tenantId>:<userId>.}（前端 {@code formDraftPrefix}，
	 * toolCallId 拼在其后）；</li>
	 * <li>legacy 身份（无上下文）给出旧键形 {@code ai-mirror:<sid>} / {@code cm.ai.form.draft.}。</li>
	 * </ul>
	 *
	 * <p>身份段只从 {@link TenantKeyScope#segmentOf(TenantUser)} 取（与所有服务端键同一拼接口）；
	 * 这里不再手写第二套 {@code tenantId + ":" + userId}。草稿前缀的尾部 {@code "."} 是
	 * {@code <t>:<u>} 与 toolCallId 的分隔符，所以把段末尾的冒号换成点，而不是另拼身份。
	 */
	static ClientCleanup clientCleanup(String sessionId, List<String> toolCallIds, TenantUser identity) {
		String segment = TenantKeyScope.segmentOf(identity);
		String mirrorKey = CLIENT_MIRROR_PREFIX + segment + sessionId;
		String draftPrefix = segment.isEmpty()
				? CLIENT_DRAFT_PREFIX
				: CLIENT_DRAFT_PREFIX + segment.substring(0, segment.length() - 1) + ".";
		return new ClientCleanup(mirrorKey, draftPrefix, List.copyOf(new LinkedHashSet<>(toolCallIds)));
	}

	/**
	 * 当前身份的作用域（{@code null} = legacy，不改变上下文）。
	 */
	private static RequestContextUtils.SafeCloseable scopeOf(TenantUser identity) {
		return identity == null ? null : RequestContextUtils.useContext(identity.tenantId(), identity.userId());
	}

	/**
	 * run 快照是否属于<b>当前销毁身份</b>（只带 sessionId 的归属不够：sessionId 可跨身份重复）。
	 *
	 * <p>无身份（legacy 销毁）时保持旧口径（不额外限制）；带身份时要求快照身份完全一致，
	 * 快照无身份（{@code 0/0}，本功能上线前的旧数据）视为<b>不可证明属于我</b> ⇒ 不删。
	 */
	private static boolean belongsToCurrentIdentity(RunSnapshot snapshot) {
		TenantUser identity = RequestContextUtils.currentOrNull();
		if (identity == null || snapshot == null) {
			return true;
		}
		return snapshot.getTenantId() == identity.tenantId() && snapshot.getUserId() == identity.userId();
	}

	private ArchiveBatch archiveKeys(String sessionId, Long archiveCursor) {
		Object raw = this.redis.opsForHash().get(this.runStore.contextKey(sessionId), "turnNo");
		if (raw == null) {
			return new ArchiveBatch(List.of(), 0L, false);
		}
		final int turnNo;
		try {
			turnNo = Integer.parseInt(String.valueOf(raw));
		}
		catch (NumberFormatException ex) {
			throw new AiStateUnavailableException("会话销毁：turnNo 非数字，无法精确枚举 archive", ex);
		}
		if (turnNo < 0) {
			throw new AiStateUnavailableException("会话销毁：turnNo 为负数，无法精确枚举 archive");
		}
		long start = archiveCursor == null ? 0L : archiveCursor;
		if (start < 0L || start > (long) turnNo + 1L) {
			throw new AiStateUnavailableException("会话销毁：archive 接续游标越界 cursor=" + start);
		}
		if (start > turnNo) {
			return new ArchiveBatch(List.of(), start, false);
		}
		long end = Math.min((long) turnNo, start + MAX_DERIVED_ARCHIVE_TURNS - 1L);
		List<String> keys = new ArrayList<>((int) (end - start + 1L));
		for (long turn = start; turn <= end; turn++) {
			keys.add(this.runStore.contextArchiveKey(sessionId, (int) turn));
		}
		return new ArchiveBatch(List.copyOf(keys), end + 1L, end < turnNo);
	}

	private List<String> stagingKeys(String sessionId) {
		if (this.chatMemoryRepository instanceof RedisChatMemoryRepository redisRepository) {
			return redisRepository.stagingKeys(sessionId);
		}
		return List.of();
	}

	// ── 活跃 run：取消 + 等终态 ──────────────────────────────────────────────

	private void cancelActiveRuns(List<RunTarget> runs, String actor, List<String> warnings) {
		for (RunTarget run : runs) {
			if (!run.active()) {
				continue;
			}
			TerminalConvergence convergence = convergeTerminalSnapshot(run.runId(), warnings);
			if (convergence == TerminalConvergence.CONVERGED) {
				continue;
			}
			boolean cancelled = false;
			try {
				// 已经确定取消的轮不重复调 cancel（重复取消会再写一次权威键；虽然语义幂等，
				// 但"活跃 run 不重复副作用"要求我们不做无意义的第二次动作）。
				if (this.cancellations.cancelState(run.runId()) != CancellationRegistry.CancelState.CANCELLED) {
					CancellationRegistry.CancelResult result = this.cancellations.cancel(run.runId(), actor);
					if (!result.redisWritten()) {
						warnings.add("run[" + run.runId() + "]: 取消标志未能写入 Redis（仅进程内生效）");
					}
					cancelled = result.redisWritten();
				}
				else {
					cancelled = true;
				}
			}
			catch (RuntimeException ex) {
				warnings.add("run[" + run.runId() + "]: 取消失败（" + rootMessage(ex) + "）");
			}
			// 终帧读不可知时不能把"没读到"当成"没有终帧"，留给 deferred 重试。
			if (cancelled && convergence != TerminalConvergence.UNKNOWN) {
				finalizeCancelledRun(run, warnings);
			}
		}
	}

	private TerminalConvergence convergeTerminalSnapshot(String runId, List<String> warnings) {
		FrameStore frames = this.frameStoreProvider == null ? null : this.frameStoreProvider.getIfAvailable();
		if (frames == null) {
			return TerminalConvergence.ABSENT;
		}
		try {
			FrameStore.ReadResult archived = frames.readFromWithStatus(runId, 0L, FrameStore.WAKE_BATCH_LIMIT);
			if (archived.degraded()) {
				warnings.add("run[" + runId + "]: 终帧读取不可知（保守 deferred，不重复收尾探测）");
				return TerminalConvergence.UNKNOWN;
			}
			Map<String, Object> terminal = null;
			for (int index = archived.frames().size() - 1; index >= 0; index--) {
				Map<String, Object> frame = archived.frames().get(index);
				Object type = frame.get("type");
				if ("done".equals(type) || "error".equals(type)) {
					terminal = frame;
					break;
				}
			}
			if (terminal == null) {
				return TerminalConvergence.ABSENT;
			}
			RunSnapshot snapshot = this.runStore.get(runId);
			if (snapshot == null || isTerminal(snapshot.getStatus())) {
				return TerminalConvergence.CONVERGED;
			}
			if ("error".equals(terminal.get("type"))) {
				snapshot.setStatus(RunSnapshot.FAILED);
				snapshot.setCancelled(false);
			}
			else {
				boolean cancelled = Boolean.parseBoolean(String.valueOf(terminal.get("cancelled")));
				snapshot.setStatus(cancelled ? RunSnapshot.CANCELLED : RunSnapshot.DONE);
				snapshot.setCancelled(cancelled);
			}
			if (!this.runStore.saveTerminalUnlessCancelled(snapshot)) {
				warnings.add("run[" + runId + "]: 已有权威终帧，但快照条件写被拒绝（deferred）");
				return TerminalConvergence.UNKNOWN;
			}
			return TerminalConvergence.CONVERGED;
		}
		catch (RuntimeException ex) {
			warnings.add("run[" + runId + "]: 终帧/快照收敛不可知（" + rootMessage(ex) + "）");
			return TerminalConvergence.UNKNOWN;
		}
	}

	/**
	 * 对"已确认取消"的活跃轮执行一次取消收尾（F3）：走既有
	 * {@link ResumeService#cancelConfirmed(String)}（重取租约 + 校验取消事实 + 按归档终帧幂等），
	 * <b>不驱动模型/工具</b>。
	 *
	 * <p>没在本次落定（租约在别处 / 会话门被占 / 这拍不可确认）不是错误：随后的
	 * {@link #awaitTerminal} 与 {@code deferred} 重试照旧兜底；这里只负责把"无人收尾"这一种
	 * 永久卡死消掉。
	 */
	private void finalizeCancelledRun(RunTarget run, List<String> warnings) {
		ResumeService resumeService = this.resumeServiceProvider == null ? null
				: this.resumeServiceProvider.getIfAvailable();
		if (resumeService == null) {
			return;
		}
		try {
			ResumeService.CancelFinalize outcome = resumeService.cancelConfirmed(run.runId());
			if (outcome != ResumeService.CancelFinalize.FINALIZED
					&& outcome != ResumeService.CancelFinalize.ALREADY_TERMINAL
					&& outcome != ResumeService.CancelFinalize.NO_RUN) {
				log.info("Session purge: cancel finalize not settled this time runId={} outcome={} (left to awaitTerminal/deferred retry)",
						run.runId(), outcome);
			}
		}
		catch (RuntimeException ex) {
			warnings.add("run[" + run.runId() + "]: 取消收尾失败（" + rootMessage(ex) + "）");
		}
	}

	/**
	 * 在短窗口内等活跃 run 落终态；等不到就留给 {@code deferred}，由下次重试补删。
	 *
	 * <p><b>仍有界，且不改等待语义</b>：窗口与轮询间隔都保持原值（{@value #ACTIVE_POLL_MILLIS}ms），
	 * 只让"已经读到终态"的轮次退出后续轮询 —— 终态单调，重读同一把键不会有新结论。
	 * 于是这段等待的快照读次数从"活跃轮数 × 轮询次数"收敛为"每轮读到自己落终态为止"。
	 */
	private void awaitTerminal(List<RunTarget> runs, List<String> warnings) {
		if (this.activeWait.isZero() || runs.stream().noneMatch(RunTarget::active)) {
			return;
		}
		long deadline = System.nanoTime() + this.activeWait.toNanos();
		Set<String> settled = new LinkedHashSet<>();
		while (System.nanoTime() < deadline) {
			boolean anyActive = false;
			for (RunTarget run : runs) {
				if (!run.active() || settled.contains(run.runId())) {
					continue;
				}
				try {
					RunSnapshot snapshot = this.runStore.get(run.runId());
					if (snapshot != null && !isTerminal(snapshot.getStatus())) {
						anyActive = true;
					}
					else {
						settled.add(run.runId());
					}
				}
				catch (RuntimeException ex) {
					anyActive = true;
					warnings.add("run[" + run.runId() + "]: 等待终态时读快照失败（" + rootMessage(ex) + "）");
				}
			}
			if (!anyActive) {
				return;
			}
			try {
				Thread.sleep(ACTIVE_POLL_MILLIS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	/**
	 * 等待窗口结束后按最新快照重算 active（取消可能在窗口内生效）。
	 *
	 * <p>刷新阶段这一读是<b>必需的</b>（取消/收尾后果只有重读才能看到），但读到的<b>终态</b>
	 * 快照不必在下一步再读一遍：随枚举结果带回删除阶段（{@code settledSnapshots}）直接复用。
	 * 活跃/读失败/已消失的轮次不入表，{@link #deleteOrDefer} 仍会自己重读判活 ——
	 * 这里的复用只省"同一次调用里对同一把键的第二次完整读取"，不绕过任何复核。
	 */
	private Enumeration reloadRunStatuses(Enumeration enumeration, List<String> warnings) {
		List<RunTarget> refreshed = new ArrayList<>(enumeration.runs().size());
		Map<String, RunSnapshot> settled = new LinkedHashMap<>();
		for (RunTarget run : enumeration.runs()) {
			try {
				RunSnapshot snapshot = this.runStore.get(run.runId());
				if (snapshot == null) {
					refreshed.add(new RunTarget(run.runId(), run.status(), false));
				}
				else {
					boolean active = !isTerminal(snapshot.getStatus());
					refreshed.add(new RunTarget(run.runId(), snapshot.getStatus(), active));
					if (!active) {
						settled.put(run.runId(), snapshot);
					}
				}
			}
			catch (RuntimeException ex) {
				warnings.add("run[" + run.runId() + "]: 重读状态失败（保守按活跃处理）：" + rootMessage(ex));
				refreshed.add(run);
			}
		}
		return enumeration.withRuns(refreshed, Map.copyOf(settled));
	}

	// ── 删除：记忆 / run / 上下文 / 会话 / 附件 ─────────────────────────────

	/**
	 * 记忆面删除：当前身份键（仓储的同槽脚本）+ 本会话 staging 键（在飞写回的中间态）。
	 *
	 * <p><b>删完必须复核</b>：销毁与写回交错时，删完之后仍可能有 staging 落地/最终的 RENAME 落地；
	 * 全局会话索引已退役（不再有 SREM 这一步），所以"不在索引"根本不能当"已删净"的证明。残留一律记成可重试错误
	 * （{@code complete=false}，pending 保留），由调度/再次 DELETE 补删 —— 绝不带着残留报成功。
	 *
	 * <p>staging 键集合来自枚举阶段读取的会话精确索引（见 {@code Enumeration#stagingKeys()}）。
	 */
	private MemoryView deleteMemory(PurgeExecution execution, Enumeration enumeration, List<String> errors) {
		MemoryCleanup cleanup = deleteMemoryKeys(execution, enumeration.stagingKeys(), errors);
		return new MemoryView(enumeration.memoryExisted(), enumeration.memoryMessages(), cleanup.deleted());
	}

	/**
	 * <b>只碰记忆面</b>的删除 + 删后复核（销毁主体与 fix4 的一次性复核共用）。
	 *
	 * <p>范围严格限定 {@code chat:mem:*}（当前身份键）+ 本会话 staging 键；不碰 DB、附件、上下文、
	 * 会话键、run 级键与任何业务数据 —— 到点复核绝不能"顺手再删一遍"其它面。
	 *
	 * @param knownStaging 枚举阶段按精确索引读出的 staging 键；{@code null} = 复核时重新读索引
	 * @return {@code deleted=true} 仅当"确认删净"（复核不可知/有残留都为 false）
	 */
	private MemoryCleanup deleteMemoryKeys(PurgeExecution execution, List<String> knownStaging,
			List<String> errors) {
		String sessionId = execution.sessionId();
		boolean deleted = true;
		try {
			this.chatMemoryRepository.deleteByConversationId(sessionId);
		}
		catch (RuntimeException ex) {
			errors.add("memory: " + rootMessage(ex));
			deleted = false;
		}
		StagingClear staging = clearStagingKeys(execution, knownStaging, errors);
		List<String> residue = new ArrayList<>(staging.remaining());
		boolean known = staging.known();
		if (this.chatMemoryRepository instanceof RedisChatMemoryRepository redisRepository) {
			try {
				if (redisRepository.hasLiveMemory(sessionId)) {
					residue.add(redisRepository.keyFor(sessionId));
				}
			}
			catch (RuntimeException ex) {
				// 判净这一步读不出来：不能当"已删净"（D3 的复核本身就是"不靠索引、看数据键"）。
				errors.add("memory: 删后残留复核失败（无法确认已删净，可重试）：" + rootMessage(ex));
				known = false;
			}
		}
		if (!residue.isEmpty()) {
			errors.add("memory: 删除后仍有残留 " + residue + "（销毁与写回交错/中间态未清净，重试补删）");
		}
		if (!known || !residue.isEmpty()) {
			// 复核不可知 ≠ 已删净：deleted 只能表示"确认删净"（否则 API 字段自相矛盾）。
			deleted = false;
		}
		return new MemoryCleanup(deleted, List.copyOf(residue));
	}

	/** 记忆面删除结论（fix4：供销毁主体与到点复核共用）。 */
	private record MemoryCleanup(boolean deleted, List<String> residue) {
	}

	/**
	 * <b>fix4 到点复核结论</b>（runner 消费）。
	 *
	 * @param verified 是否"已确认删净并移除台账"
	 * @param errors 失败原因（可重试；本类已按当前 owner/epoch 记录退避）
	 */
	public record MemoryVerify(boolean verified, List<String> errors) {
	}

	/**
	 * <b>fix4：一次有界的"记忆面"复核</b>（由 {@link SessionPurgeRetryRunner} 在
	 * {@code verifyAtMs} 到点时调用）。
	 *
	 * <h2>为什么是"复核"而不是重跑销毁</h2>
	 * 台账此时代表"删除主体已完成，只等一次崩溃残留补偿"。重跑 {@link #retry} 会再删 DB/附件/上下文
	 * —— 既超出本意，也把"只读复核"变成有副作用的整轮操作。因此本方法<b>只</b>做：
	 * 守卫复核 → 记忆面 delete（当前身份键/staging）→ 删后复核 → 成功才移除台账。
	 *
	 * <h2>守卫口径（fail-closed）</h2>
	 * 删除前必须 {@code guard.isArmed} 为真；<b>读失败一律不删</b>（抛给调用方记失败、保留台账）。
	 * 守卫不在（台账已被别的路径清掉/换主）则什么都不做，返回 null。
	 *
	 * @return null = 台账不存在/已换主/不是复核态（本轮跳过）；否则为复核结论
	 */
	public MemoryVerify verifyMemoryOnly(String sessionId, String purgeId) {
		if (sessionId == null || sessionId.isBlank() || purgeId == null || purgeId.isBlank()) {
			return null;
		}
		SessionPurgePendingStore.Pending pending = this.pendingStore.current(sessionId);
		if (pending == null || !purgeId.equals(pending.purgeId()) || pending.verifyAtMs() == null) {
			return null;
		}
		SessionPurgePendingStore.AcquireResult claim = this.pendingStore.acquire(sessionId, purgeId,
				UUID.randomUUID().toString(), System.currentTimeMillis(), EXECUTION_LEASE.toMillis());
		if (!claim.acquired()) {
			return null;
		}
		PurgeExecution execution = new PurgeExecution(claim.execution());
		boolean armed;
		try {
			execution.checkpoint("VERIFY_GUARD");
			armed = this.guard.isArmed(sessionId);
		}
		catch (RuntimeException ex) {
			recordFailureQuietly(execution, "verify guard: " + rootMessage(ex));
			return new MemoryVerify(false, List.of("guard: 状态不可知（fail-closed，不删）：" + rootMessage(ex)));
		}
		if (!armed) {
			recordFailureQuietly(execution, "verify guard: not armed");
			return new MemoryVerify(false, List.of("guard: 未装（未确认已销毁，不删）"));
		}
		List<String> errors = new ArrayList<>();
		try {
			execution.checkpoint("VERIFY_MEMORY");
			MemoryCleanup cleanup = deleteMemoryKeys(execution, null, errors);
			if (!cleanup.deleted() || !errors.isEmpty()) {
				recordFailureQuietly(execution, "memory verify: " + String.join("; ", errors));
				return new MemoryVerify(false, List.copyOf(errors));
			}
			execution.checkpoint("VERIFY_COMPLETE");
			if (!this.pendingStore.complete(execution.claim())) {
				return new MemoryVerify(false, List.of("pending: 已被另一轮销毁接管，未移除旧台账"));
			}
		}
		catch (RuntimeException ex) {
			recordFailureQuietly(execution, "memory verify: " + rootMessage(ex));
			return new MemoryVerify(false, List.of("pending: 复核失败（保留 pending 以便重试）：" + rootMessage(ex)));
		}
		log.info("Session purge: memory re-verify completed (crash residue compensation) sessionId={} purgeId={}", sessionId, purgeId);
		return new MemoryVerify(true, List.of());
	}

	/**
	 * staging 清理结论。
	 *
	 * @param known {@code false} = 复核读不出来，"有没有残留"<b>不可知</b>（不得当成"没有残留"）
	 * @param remaining 确认仍存在的 staging 键（删除失败或删后复核仍在）
	 */
	private record StagingClear(boolean known, List<String> remaining) {
	}

	/**
	 * 按精确索引清理 staging；每条同槽 Lua 同时删数据并摘索引成员，批间有界。
	 * 索引读取、pipeline 或回包不可知均保留 durable pending，由重试与 60 秒复核接续。
	 *
	 * @param knownStaging {@code null} = 没有前置枚举，重新读取精确索引
	 */
	private StagingClear clearStagingKeys(PurgeExecution execution, List<String> knownStaging,
			List<String> errors) {
		String sessionId = execution.sessionId();
		List<String> targets;
		try {
			targets = knownStaging == null ? stagingKeys(sessionId) : knownStaging;
		}
		catch (RuntimeException ex) {
			errors.add("memory: staging 索引读取失败（未删净，可重试）：" + rootMessage(ex));
			return new StagingClear(false, List.of());
		}
		if (targets.isEmpty()) {
			return new StagingClear(true, List.of());
		}
		if (!(this.chatMemoryRepository instanceof RedisChatMemoryRepository redisRepository)) {
			return new StagingClear(false, targets);
		}
		List<String> remaining = new ArrayList<>();
		for (int from = 0; from < targets.size(); from += REDIS_BATCH_SIZE) {
			execution.checkpoint("STAGING_DELETE_BATCH");
			List<String> batch = targets.subList(from, Math.min(targets.size(), from + REDIS_BATCH_SIZE));
			try {
				List<Long> outcomes = redisRepository.deleteStagingKeys(sessionId, batch);
				execution.checkDeadline();
				for (int index = 0; index < batch.size(); index++) {
					if (outcomes.get(index) < 0L) {
						remaining.add(batch.get(index));
					}
				}
			}
			catch (PurgeBudgetExceededException | IllegalStateException ex) {
				throw ex;
			}
			catch (RuntimeException ex) {
				errors.add("memory: staging 批量删除失败（结果不可知，可重试）：" + rootMessage(ex));
				remaining.addAll(batch);
			}
		}
		if (!remaining.isEmpty()) {
			errors.add("memory: staging 删除回包不可知 " + remaining + "（可重试）");
		}
		return new StagingClear(remaining.isEmpty(), List.copyOf(remaining));
	}

	/**
	 * 删除或延后一轮。删除前的状态/归属复核<b>不可省</b>，但可以复用"刚读过且仍然有效"的快照：
	 * <ul>
	 * <li>活跃轮：<b>总是重读</b>快照 —— 取消/收尾的后果只有重读才看得到，不能拿旧读判"已经停了"；</li>
	 * <li>刷新阶段已读到终态的轮：直接复用那份快照（终态单调，身份字段也在其中），
	 *     不再对同一把键做第二次完整读取；</li>
	 * <li>其余（刷新读失败/刷新时快照已消失）：按旧口径重读，读不出来一律延后。</li>
	 * </ul>
	 * 归属复核（{@link #belongsToCurrentIdentity}）对"复用的快照"同样执行 —— 省读不等于跳过复核。
	 * {@link RunStore#deleteRun(String)} 内部仍按身份读一次快照（"错身份拒绝删除"的语义由
	 * RunStore 拥有，本类不能替它决定）；这里省掉的是本类在调用前那次重复读取。
	 */
	private void deleteOrDefer(PurgeExecution execution, RunTarget target, Map<String, RunSnapshot> settledSnapshots,
			List<RunView> runViews, List<DeferredView> deferred, List<String> errors, List<String> warnings) {
		RunSnapshot latest;
		RunSnapshot reused = target.active() ? null : settledSnapshots.get(target.runId());
		if (reused != null) {
			latest = reused;
		}
		else {
			try {
				latest = this.runStore.get(target.runId());
			}
			catch (RuntimeException ex) {
				// 读不出来就保守地不删（13 键删一半比延后更糟）；重试时再判。
				deferred.add(new DeferredView(target.runId(), target.status(), "STATUS_UNREADABLE"));
				runViews.add(new RunView(target.runId(), target.status(), true, false, true));
				errors.add("run[" + target.runId() + "]: 读状态失败（" + rootMessage(ex) + "）");
				return;
			}
		}
		String status = latest == null ? null : latest.getStatus();
		if (latest != null && !belongsToCurrentIdentity(latest)) {
			// 归属复核（快照带身份才可信）：别的身份/无归属 legacy 的 run 一律不动；warning 进结果可取证。
			warnings.add("run[" + target.runId() + "]: 跳过非本身份（复核，snapshotIdentity="
					+ latest.getTenantId() + ":" + latest.getUserId() + "）");
			log.warn("Session purge: skip run of another identity (re-verify) runId={} snapshotIdentity={}:{}", target.runId(),
					latest.getTenantId(), latest.getUserId());
			return;
		}
		boolean active = latest != null && !isTerminal(status);
		if (active) {
			deferred.add(new DeferredView(target.runId(), status, "RUN_ACTIVE"));
			runViews.add(new RunView(target.runId(), status, true, false, true));
			return;
		}
		int errorCount = errors.size();
		deleteObservabilityKeys(execution, target.runId(), errors);
		if (errors.size() != errorCount) {
			runViews.add(new RunView(target.runId(), status, false, false, false));
			return;
		}
		try {
			this.runStore.deleteRun(target.runId());
			runViews.add(new RunView(target.runId(), status, false, true, false));
		}
		catch (RuntimeException ex) {
			errors.add("run[" + target.runId() + "]: 13 键删除失败（可重试）：" + rootMessage(ex));
			runViews.add(new RunView(target.runId(), status, false, false, false));
		}
	}

	/**
	 * 13 键之外的观测/取消键与帧附加键。单键 DEL 在受管连接的 Lettuce async 回调内统一等待；
	 * xack 的固定 run HASH 在此精确删除，旧动态 token 键仍按原短 TTL 退出。
	 */
	private void deleteObservabilityKeys(PurgeExecution execution, String runId, List<String> errors) {
		execution.checkpoint("DELETE_RUN_ANCHORS");
		List<String> keys = List.of(this.runStore.beatKey(runId), this.runStore.activityKey(runId),
				this.cancellations.keyFor(runId), TenantKeyScope.scoped(OBSERVED_PREFIX, runId),
				RunStore.runScopedKey("ai:xtrim:", runId), RunStore.runScopedKey("ai:xack:", runId));
		try {
			List<Long> results = this.redis.execute((RedisCallback<List<Long>>) connection -> {
				Object nativeConnection = connection.getNativeConnection();
				if (!(nativeConnection instanceof RedisClusterAsyncCommands<?, ?>)) {
					throw new IllegalStateException("Lettuce cluster async commands unavailable");
				}
				RedisClusterAsyncCommands<byte[], byte[]> async =
						(RedisClusterAsyncCommands<byte[], byte[]>) nativeConnection;
				List<RedisFuture<Long>> futures = new ArrayList<>(keys.size());
				for (String key : keys) {
					futures.add(async.del(key.getBytes(StandardCharsets.UTF_8)));
				}
				CompletableFuture<?>[] waits = futures.stream().map(RedisFuture::toCompletableFuture)
						.toArray(CompletableFuture<?>[]::new);
				CompletableFuture.allOf(waits).join();
				execution.checkDeadline();
				return futures.stream().map(future -> future.toCompletableFuture().join()).toList();
			});
			if (results == null || results.size() < keys.size()) {
				errors.add("run[" + runId + "]: 观测/帧附加键删除回包不完整（可重试）");
			}
		}
		catch (RuntimeException ex) {
			errors.add("run[" + runId + "]: 观测/帧附加键删除结果不可知（可重试）：" + rootMessage(ex));
		}
	}

	/**
	 * 枚举 H2 面的反馈行：<b>当前身份</b>（销毁主体在入口身份的上下文里），无身份 = legacy {@code (0,0)}。
	 *
	 * <p>与键面同一口径：没有身份上下文时不猜默认身份，只落 legacy 命名空间。
	 */
	private SessionPurgeDatabase.Snapshot snapshotFeedback(String sessionId) {
		TenantUser identity = RequestContextUtils.currentOrNull();
		return identity == null ? this.database.snapshot(sessionId)
				: this.database.snapshot(sessionId, identity.tenantId(), identity.userId());
	}

	/** 删除 H2 面的反馈行：按 sessionId + 身份三元组（见 {@link SessionPurgeDatabase}）。 */
	private SessionPurgeDatabase.Deleted deleteFeedback(String sessionId) {
		TenantUser identity = RequestContextUtils.currentOrNull();
		return identity == null ? this.database.delete(sessionId)
				: this.database.delete(sessionId, identity.tenantId(), identity.userId());
	}

	/**
	 * B2：删除用户展示历史（精确键 + 类型校验）。
	 *
	 * <p>只删本会话的数据键、字节计数键、baseline 标记键与截断标记键；<b>不扫描</b>、不跨槽。
	 * 类型不符说明前缀碰撞（别的会话/别的结构），按既有 {@link #deleteKeyIfType} 口径记 warning
	 * 而不硬删。
	 */
	TranscriptView deleteTranscript(String sessionId, boolean existed, long entries, List<String> errors,
			List<String> warnings) {
		return deleteTranscript(null, sessionId, existed, entries, errors, warnings);
	}

	private TranscriptView deleteTranscript(PurgeExecution execution, boolean existed, long entries,
			List<String> errors, List<String> warnings) {
		return deleteTranscript(execution, execution.sessionId(), existed, entries, errors, warnings);
	}

	private TranscriptView deleteTranscript(PurgeExecution execution, String sessionId, boolean existed, long entries,
			List<String> errors, List<String> warnings) {
		List<TypedKey> keys = List.of(
				new TypedKey(com.example.configmgr.ai.history.ConversationTranscriptStore.dataKey(sessionId),
						DataType.LIST, "transcript"),
				new TypedKey(com.example.configmgr.ai.history.ConversationTranscriptStore.bytesKey(sessionId),
						DataType.STRING, "transcriptBytes"),
				new TypedKey(com.example.configmgr.ai.history.ConversationTranscriptStore.readyKey(sessionId),
						DataType.STRING, "transcriptReady"),
				new TypedKey(com.example.configmgr.ai.history.ConversationTranscriptStore.truncatedKey(sessionId),
						DataType.STRING, "transcriptTruncated"));
		return new TranscriptView(existed, entries, deleteTypedKeys(execution, keys, errors, warnings) > 0);
	}

	private ContextView deleteContext(PurgeExecution execution, ContextTargets targets, List<String> errors,
			List<String> warnings) {
		String sessionId = execution.sessionId();
		List<TypedKey> archives = new ArrayList<>();
		for (String archive : targets.archives()) {
			archives.add(new TypedKey(archive, DataType.STRING, "ctxArchive"));
		}
		int errorCount = errors.size();
		int deleted = 0;
		long archiveCursor = targets.nextArchiveCursor() - archives.size();
		for (int from = 0; from < archives.size(); from += REDIS_BATCH_SIZE) {
			List<TypedKey> batch = archives.subList(from, Math.min(archives.size(), from + REDIS_BATCH_SIZE));
			int batchErrorCount = errors.size();
			deleted += deleteTypedKeys(execution, batch, errors, warnings);
			if (errors.size() != batchErrorCount) {
				break;
			}
			archiveCursor += batch.size();
			if (!this.pendingStore.checkpointArchive(execution.claim(), archiveCursor,
					System.currentTimeMillis(), EXECUTION_LEASE.toMillis())) {
				throw new IllegalStateException("archive checkpoint rejected (execution lease lost)");
			}
		}
		if (errors.size() == errorCount && targets.hasMoreArchives()) {
			throw new PurgeContinuationException("archive batch committed, continuation required cursor="
					+ archiveCursor);
		}
		if (errors.size() == errorCount) {
			List<TypedKey> sessionKeys = List.of(
					new TypedKey(this.runStore.contextKey(sessionId), DataType.HASH, "ctx"),
					new TypedKey(this.runStore.contextLedgerKey(sessionId), DataType.LIST, "ctxLedger"),
					new TypedKey(TenantKeyScope.scoped(RunStore.SAFETY_PREFIX, sessionId), DataType.LIST, "safety"),
					new TypedKey(this.runStore.contextEventsKey(sessionId), DataType.LIST, "ctxEvents"));
			deleted += deleteTypedKeys(execution, sessionKeys, errors, warnings);
		}
		return new ContextView(targets.context(), targets.ledger(), targets.archives().size(), targets.safety(),
				deleted);
	}

	private int deleteKeyIfType(PurgeExecution execution, String key, DataType expected, List<String> errors,
			List<String> warnings, String label) {
		return deleteTypedKeys(execution, List.of(new TypedKey(key, expected, label)), errors, warnings);
	}

	private int deleteTypedKeys(PurgeExecution execution, List<TypedKey> keys, List<String> errors,
			List<String> warnings) {
		int deleted = 0;
		byte[] script = TYPE_DEL.getScriptAsString().getBytes(StandardCharsets.UTF_8);
		for (int from = 0; from < keys.size(); from += REDIS_BATCH_SIZE) {
			if (execution != null) {
				execution.checkpoint("TYPE_DEL_BATCH");
			}
			List<TypedKey> batch = keys.subList(from, Math.min(keys.size(), from + REDIS_BATCH_SIZE));
			final List<Object> results;
			try {
				results = this.redis.executePipelined((RedisCallback<Object>) connection -> {
					for (TypedKey target : batch) {
						connection.eval(script, ReturnType.INTEGER, 1, target.key().getBytes(StandardCharsets.UTF_8),
								target.expected().code().getBytes(StandardCharsets.UTF_8));
					}
					return null;
				});
			}
			catch (RuntimeException ex) {
				for (TypedKey target : batch) {
					errors.add(target.label() + "[" + target.key() + "]: TYPE+DEL 结果不可知（可重试）："
							+ rootMessage(ex));
				}
				continue;
			}
			if (execution != null) {
				execution.checkDeadline();
			}
			if (results == null || results.size() < batch.size()) {
				for (TypedKey target : batch) {
					errors.add(target.label() + "[" + target.key() + "]: TYPE+DEL 回包缺失（可重试）");
				}
				continue;
			}
			for (int index = 0; index < batch.size(); index++) {
				TypedKey target = batch.get(index);
				Object raw = results.get(index);
				if (!(raw instanceof Number number)) {
					errors.add(target.label() + "[" + target.key() + "]: TYPE+DEL 回包不可知（可重试）");
					continue;
				}
				long outcome = number.longValue();
				if (outcome == -1L) {
					if ("ctxArchive".equals(target.label())) {
						errors.add(target.label() + "[" + target.key() + "]: 键类型不符（期望 "
								+ target.expected() + "），保留 cursor/ctx 并重试");
					}
					else {
						warnings.add(target.label() + "[" + target.key() + "]: 键类型不符（期望 "
								+ target.expected() + "），判定为前缀碰撞并跳过");
					}
				}
				else if (outcome > 0L) {
					deleted += (int) outcome;
				}
				else if (outcome < 0L) {
					errors.add(target.label() + "[" + target.key() + "]: TYPE+DEL 失败码=" + outcome);
				}
			}
		}
		return deleted;
	}

	private int deleteKey(String key, List<String> errors, String label) {
		try {
			return Boolean.TRUE.equals(this.redis.delete(key)) ? 1 : 0;
		}
		catch (RuntimeException ex) {
			errors.add(label + "[" + key + "]: " + rootMessage(ex));
			return 0;
		}
	}

	/**
	 * 会话记录 / 草稿的值感知删除（<b>不是</b>发现键）：
	 * <ul>
	 * <li>{@code ai:session:<sid>} 的值应是会话记录 JSON；若读到非 JSON（runId），说明它实际是
	 * 另一个会话（id = {@code current:<sid>} 之类）的 current 索引，跳过。</li>
	 * </ul>
	 *
	 * <p>{@code sessionCurrent}/{@code sessionRuns}/{@code sessionActive} 三把发现键不在这里删，
	 * 只在 {@link #deleteDiscoveryKeys} 里、本次删除主体无错误且无 defer 时才删。
	 */
	private SessionView deleteSessionRecordKeys(PurgeExecution execution, List<String> errors,
			List<String> warnings) {
		String sessionId = execution.sessionId();
		boolean recordDeleted = false;
		// 只删会话记录键；ai:session:lock:<sid> 是协调键（在跑轮次可能还持有），不在这里动。
		String recordKey = AiSessionService.keyFor(sessionId);
		try {
			String value = this.redis.opsForValue().get(recordKey);
			if (value == null) {
				recordDeleted = false;
			}
			else if (!value.trim().startsWith("{")) {
				warnings.add("session[" + recordKey + "]: 键实际是另一会话的 current 索引（前缀碰撞），跳过");
			}
			else {
				recordDeleted = Boolean.TRUE.equals(this.redis.delete(recordKey));
			}
		}
		catch (RuntimeException ex) {
			errors.add("session: " + rootMessage(ex));
		}
		deleteKeyIfType(execution, CreateTaskDraftStore.sessionKey(sessionId), DataType.HASH, errors, warnings,
				"ctdraft");
		deleteKeyIfType(execution, ClientRequestRegistry.sessionKey(sessionId), DataType.HASH, errors, warnings,
				"clientreq");
		return new SessionView(false, recordDeleted);
	}

	/**
	 * 删除三把<b>发现键</b>：{@code sessionCurrent} / {@code sessionRuns} / {@code sessionActive}。
	 *
	 * <p>调用前提（由 {@link #executeDeletion} 保证）：本次删除主体<b>无 errors 且无 deferred</b>。
	 * 只要有活跃 run 被 deferred 或任一删除失败，保留它们，重试才能继续枚举剩余轮次；
	 * 提前删除会让 pending/guard 永久驻留（会话 410 且删不掉）。
	 */
	private SessionView deleteDiscoveryKeys(PurgeExecution execution, SessionView session, List<String> errors,
			List<String> warnings) {
		String sessionId = execution.sessionId();
		boolean currentDeleted = false;
		String currentKey = this.runStore.sessionCurrentKey(sessionId);
		try {
			String value = this.redis.opsForValue().get(currentKey);
			if (value == null) {
				currentDeleted = false;
			}
			else if (value.trim().startsWith("{")) {
				warnings.add("sessionCurrent[" + currentKey + "]: 键被另一会话的会话记录占用（前缀碰撞），跳过");
			}
			else {
				currentDeleted = Boolean.TRUE.equals(this.redis.delete(currentKey));
			}
		}
		catch (RuntimeException ex) {
			errors.add("sessionCurrent: " + rootMessage(ex));
		}
		deleteKeyIfType(execution, this.runStore.sessionRunsKey(sessionId), DataType.SET, errors, warnings,
				"sessionRuns");
		deleteKeyIfType(execution, this.runStore.sessionActiveKey(sessionId), DataType.ZSET, errors, warnings,
				"sessionActive");
		return new SessionView(currentDeleted, session.recordDeleted());
	}

	private AttachmentView deleteAttachments(PurgeExecution execution, List<FormAttachmentStore.Attachment> list,
			List<String> errors, List<String> warnings) {
		String sessionId = execution.sessionId();
		int registrations = 0;
		int files = 0;
		List<String> failed = new ArrayList<>();
		for (FormAttachmentStore.Attachment attachment : list) {
			execution.checkDeadline();
			if (!sessionId.equals(attachment.sessionId())) {
				warnings.add("attachment[" + attachment.fileId() + "]: 登记归属与请求会话不一致，跳过（不删他会话文件）");
				errors.add("attachment[" + attachment.fileId() + "]: 会话索引归属污染（保留索引，fail-closed）");
				failed.add(attachment.fileId());
				continue;
			}
			String storagePath = attachment.storagePath();
			if (storagePath != null && !storagePath.isBlank()) {
				Path path;
				try {
					path = this.fileStorage.resolve(storagePath);
				}
				catch (IllegalArgumentException ex) {
					errors.add("attachment[" + attachment.fileId() + "]: 登记路径非法（" + ex.getMessage() + "）");
					failed.add(attachment.fileId());
					continue;
				}
				boolean existed = Files.exists(path);
				try {
					this.fileStorage.delete(storagePath);
				}
				catch (RuntimeException ex) {
					errors.add("attachment[" + attachment.fileId() + "]: 文件删除失败（登记保留以便重试）："
							+ storagePath + "（" + ex.getMessage() + "）");
					failed.add(attachment.fileId());
					continue;
				}
				if (Files.exists(path)) {
					errors.add("attachment[" + attachment.fileId() + "]: 文件删除失败（登记保留以便重试）："
							+ storagePath);
					failed.add(attachment.fileId());
					continue;
				}
				if (existed) {
					files++;
				}
			}
			try {
				FormAttachmentStore.Attachment removed = this.attachments.deleteRegistration(attachment);
				if (removed != null) {
					registrations++;
				}
			}
			catch (RuntimeException ex) {
				errors.add("attachment[" + attachment.fileId() + "]: 登记删除失败（可重试）：" + rootMessage(ex));
				failed.add(attachment.fileId());
			}
		}
		if (failed.isEmpty() && errors.isEmpty()) {
			try {
				this.attachments.deleteSessionIndex(sessionId);
			}
			catch (RuntimeException ex) {
				errors.add("attachments: 会话索引删除失败（可重试）：" + rootMessage(ex));
			}
		}
		return new AttachmentView(list.size(), registrations, files, List.copyOf(failed));
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private PurgeResult inProgress(String sessionId, String purgeId, SessionPurgePendingStore.Pending pending,
			TenantUser identity) {
		Enumeration enumeration = enumerationForFailure(sessionId, identity);
		String phase = pending == null || pending.phase() == null ? "inProgress" : "inProgress:" + pending.phase();
		return new PurgeResult(sessionId, purgeId, false, pending != null, phase, enumeration.database(), null,
				new MemoryView(false, 0, false), new TranscriptView(false, 0L, false), List.of(), List.of(),
				new ContextView(false, false, 0, false, 0), new AttachmentView(0, 0, 0, List.of()),
				new SessionView(false, false), enumeration.clientCleanup(), List.of(), List.of());
	}

	private Duration guardTtl() {
		Duration ttl = this.properties.getSessionTtl();
		return ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofHours(24) : ttl;
	}

	private static boolean isTerminal(String status) {
		return status != null && RunSnapshot.isTerminal(status);
	}

	private static String rootMessage(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		String message = current.getMessage();
		return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
	}

	/** 枚举失败时的占位结果（不读取任何依赖，保证"失败不半删"可判定）。 */
	private static Enumeration enumerationForFailure(String sessionId, TenantUser identity) {
		return new Enumeration(List.of(), Map.of(), List.of(), new ContextTargets(false, false, List.of(), false, false),
				false, 0, false, 0L, List.of(), new SessionPurgeDatabase.Snapshot(0), false, false, false,
				clientCleanup(sessionId, List.of(), identity), List.of());
	}

	private static PurgeResult aborted(String sessionId, String purgeId, String phase, Enumeration enumeration,
			RuntimeException error) {
		return new PurgeResult(sessionId, purgeId, false, enumeration.found(), phase, enumeration.database(), null,
				new MemoryView(enumeration.memoryExisted(), enumeration.memoryMessages(), false),
				new TranscriptView(enumeration.transcriptExisted(), enumeration.transcriptEntries(), false),
				List.of(), List.of(),
				new ContextView(enumeration.context().context(), enumeration.context().ledger(),
						enumeration.context().archives().size(), enumeration.context().safety(), 0),
				new AttachmentView(enumeration.attachments().size(), 0, 0, List.of()),
				new SessionView(false, false), enumeration.clientCleanup(),
				List.of(phase + ": " + rootMessage(error)), enumeration.warnings());
	}

	// ── 内部数据结构 ─────────────────────────────────────────────────────────

	private record TypedKey(String key, DataType expected, String label) {
	}

	private enum TerminalConvergence {
		CONVERGED, ABSENT, UNKNOWN
	}

	private static final class PurgeContinuationException extends RuntimeException {
		private PurgeContinuationException(String message) {
			super(message);
		}
	}

	private static final class PurgeBudgetExceededException extends RuntimeException {
		private PurgeBudgetExceededException() {
			super("session purge execution budget exhausted");
		}
	}

	private final class PurgeExecution {
		private final SessionPurgePendingStore.Execution claim;
		private final long deadlineNanos = System.nanoTime() + EXECUTION_BUDGET.toNanos();

		private PurgeExecution(SessionPurgePendingStore.Execution claim) {
			this.claim = claim;
		}

		private SessionPurgePendingStore.Execution claim() {
			return this.claim;
		}

		private String sessionId() {
			return this.claim.sessionId();
		}

		private String purgeId() {
			return this.claim.purgeId();
		}

		private void checkpoint(String phase) {
			checkDeadline();
			if (!AiSessionPurgeService.this.pendingStore.renew(this.claim, phase, System.currentTimeMillis(),
					EXECUTION_LEASE.toMillis())) {
				throw new IllegalStateException("purge execution lease lost at phase " + phase);
			}
		}

		private void checkDeadline() {
			if (System.nanoTime() >= this.deadlineNanos) {
				throw new PurgeBudgetExceededException();
			}
		}
	}

	private record RunTarget(String runId, String status, boolean active) {
	}

	private record ArchiveBatch(List<String> keys, long nextCursor, boolean hasMore) {
	}

	private record ContextTargets(boolean context, boolean ledger, List<String> archives, boolean safety,
			boolean events, long nextArchiveCursor, boolean hasMoreArchives) {

		ContextTargets(boolean context, boolean ledger, List<String> archives, boolean safety, boolean events) {
			this(context, ledger, archives, safety, events, 0L, false);
		}

		boolean any() {
			return this.context || this.ledger || !this.archives.isEmpty() || this.safety || this.events
					|| this.hasMoreArchives;
		}

	}

	/**
	 * 枚举结果（一次销毁过程中"已经读到的东西"的唯一载体）。
	 *
	 * @param settledSnapshots 状态刷新阶段读到的<b>终态</b>快照（runId → 快照）：删除阶段直接复用，
	 *                         不再对同一把键重复完整读取；为空 = 没有可复用的读，删除前照旧重读
	 * @param stagingKeys 枚举阶段按会话精确索引读出的 staging 键
	 */
	private record Enumeration(List<RunTarget> runs, Map<String, RunSnapshot> settledSnapshots,
			List<FormAttachmentStore.Attachment> attachments, ContextTargets context, boolean memoryExisted,
			int memoryMessages, boolean transcriptExisted, long transcriptEntries, List<String> stagingKeys,
			SessionPurgeDatabase.Snapshot database, boolean sessionCurrentExisted, boolean sessionRecordExisted,
			boolean found, ClientCleanup clientCleanup, List<String> warnings) {

		Enumeration withRuns(List<RunTarget> refreshed, Map<String, RunSnapshot> settled) {
			return new Enumeration(refreshed, settled, this.attachments, this.context, this.memoryExisted,
					this.memoryMessages, this.transcriptExisted, this.transcriptEntries, this.stagingKeys,
					this.database, this.sessionCurrentExisted, this.sessionRecordExisted, this.found,
					this.clientCleanup, this.warnings);
		}

	}

}
