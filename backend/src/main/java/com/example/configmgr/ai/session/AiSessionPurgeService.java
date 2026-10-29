package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.form.FormAttachmentStore;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunKeyScope;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.common.AiStateUnavailableException;
import com.example.configmgr.file.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
import java.util.regex.Pattern;

/**
 * 会话销毁（{@code DELETE /api/ai/session/{sessionId}} 的本体）。
 *
 * <h2>身份隔离（2026-10-30，租户任务 2）</h2>
 * 销毁是<b>身份内</b>的操作：run/session 数据键都带 {@code tenantId/userId}（{@link TenantKeyScope}，
 * run 级键是带身份的 hash tag），跨租户/跨用户即使 sessionId 相同也互不可见、互不删除。
 * 唯一的全局面是 {@code ai:runs} 索引：成员是复合成员 {@code tenantId/userId/runId}，
 * 按成员身份过滤，<b>不扫描租户列表</b>。
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
 * <li>{@code chat:mem:*}（tagged + 迁移期 legacy + <b>本会话的 staging 中间态</b>）+ 会话 id 索引成员
 * （SREM，<b>不删索引键</b>）；删完复核数据键与 staging 是否真的都不在了，残留一律
 * {@code complete=false}（索引成员已被 SREM，不能拿"不在索引"当"已删净"）；</li>
 * <li>该会话每一轮的<b>13 个 run 级键</b>（委托 {@link RunStore#deleteRun(String)}，按 run 逐个删，
 * 不使用 KEYS/通配删除）；外加 {@code ai:beat:} / {@code ai:activity:} / {@code ai:cancel:} /
 * {@code ai:observed:} 四个观测/取消键（<b>不靠 TTL 自清</b>，逐 run 单键 DEL；四族都带身份段
 * {@code <t>:<u>:}，与写入方同一个键形）；</li>
 * <li><b>会话→run 索引复核（RT-28）</b>：{@code ai:session:runs:<sid>} 的 {@code complete} 只是加速标记
 * （新 run 入索引是 best-effort），删除尾部按全局 {@code ai:runs} 补发现"哨兵在但成员缺"的本会话 run：
 * 终态即删、活跃即 {@code deferred} ⇒ {@code complete=false} 可重试；复核本身失败也记可重试错误。
 * 归属判断只做<b>轻量读</b>（{@link RunStore#snapshotOwner(String)}：原始 JSON + 流式顶层
 * {@code sessionId}），无关 run 不物化快照；<b>不做条数截断</b>（截断会让 complete 永远为假 ⇒ 每次自动
 * 重试都从头扫、永远删不完）；</li>
 * <li>{@code ai:ctx:<sid>} / {@code ai:ctx:ledger:<sid>} / {@code ai:ctx:archive:<sid>:<turnNo>} /
 * {@code ai:safety:<sid>}；</li>
 * <li>表单附件：该会话的登记、登记索引成员、<b>且只按登记里的 storagePath 删文件</b>（不扫目录）；</li>
 * <li>{@code ai_feedback} 表<b>该身份 + 该会话</b>的行（H2 先行；按 {@code sessionId + tenantId + userId}
 * 三元组定位，跨租户同 sessionId 不误删；无身份上下文 = legacy {@code (0,0)} 命名空间）；</li>
 * <li>{@code ai:session:current:<sid>} 与 {@code ai:session:<sid>}；</li>
 * <li>浏览器本地镜像/草稿：服务端无法访问 sessionStorage，结果里给出精确的
 * {@code clientCleanup}（镜像键 + 草稿键前缀 + 本轮 toolCallId 清单），由前端执行删除。</li>
 * </ol>
 *
 * <h2>轮次候选（轻量索引 + 回退）</h2>
 * 优先读 {@code ai:session:runs:<sid>}（create SADD / deleteRun SREM）只拿本会话候选，避免对
 * {@code ai:runs} 全量快照逐个反序列化；索引为空（老数据/已过期）时回退全局 {@code ai:runs}，
 * 再按快照的 {@code sessionId} 过滤 —— 索引是加速面，不是唯一事实源，漏删/误删都不允许。
 *
 * <h2>绝不碰（负向清单）</h2>
 * 业务作业（{@code jobs}/{@code job_items}）、{@code task_files}、{@code config_staging_rows}、
 * 任务/配置/provider/model/setting/KB/审计、资源锁 {@code ai:lock:*}、业务文件目录，
 * 以及<b>其它会话</b>的任何键/行/文件。本类没有任何通配删除、没有目录清空、没有 {@code FLUSHDB}。
 *
 * <h2>顺序（用户裁决）</h2>
 * <b>先枚举 → 登记 durable pending → 装守卫 → H2 删 → 取消活跃 run → Redis 按 run 删 →
 * 附件文件 → 会话键 → 守卫保留期 + 移除 pending</b>。
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
	 * 与 {@code ai:beat:}/{@code ai:activity:} 同族）：<b>store 拥有写入与迁移排除清单，本类只负责
	 * "销毁时按当前身份的 runId 删掉同一把键"</b>。此处只引用前缀常量而不引用 store 类 —— 单键 DEL
	 * 一个不存在的键是 no-op，前向兼容；但身份段必须从同一处算，否则会删错（跨租户同 runId）。
	 */
	static final String OBSERVED_PREFIX = "ai:observed:";

	private static final Pattern TURN_NO = Pattern.compile("\\d+");

	private static final Duration DEFAULT_ACTIVE_WAIT = Duration.ofMillis(1500);

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

	/**
	 * 由 {@code turnNo} 推导归档键的上限（防坏值造成一次百万次 DEL）。
	 * 正常会话远小于此值；超出时只依赖扫描面。
	 */
	private static final int MAX_DERIVED_ARCHIVE_TURNS = 2000;

	private final StringRedisTemplate redis;

	private final RunStore runStore;

	private final SessionGate sessionGate;

	private final CancellationRegistry cancellations;

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

	/** 浏览器本地镜像/草稿的清理指令（服务端无法直接删 sessionStorage）。 */
	public record ClientCleanup(String mirrorKey, String draftPrefix, List<String> draftToolCallIds) {
	}

	/** 一次销毁的完整结果（{@code complete=false} 时看 {@code errors}/{@code deferred}）。 */
	public record PurgeResult(String sessionId, String purgeId, boolean complete, boolean found, String phase,
			SessionPurgeDatabase.Snapshot databaseSnapshot, SessionPurgeDatabase.Deleted databaseDeleted,
			MemoryView memory, String memoryKey, List<RunView> runs, List<DeferredView> deferred,
			ContextView context, AttachmentView attachments, SessionView session, boolean removedFromIndex,
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
			body.put("memoryKey", this.memoryKey);
			body.put("removedFromIndex", this.removedFromIndex);
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
			body.put("note", "只删本会话的 AI 数据；业务作业/任务文件/staging/配置/provider/model/settings/KB/审计/"
					+ "资源锁/业务文件一概不动。complete=false 时按 errors/deferred 重试（全部删除幂等）。"
					+ "浏览器 sessionStorage 的镜像与草稿由 clientCleanup 交给前端删除。");
			return body;
		}

	}

	// ── 主流程 ──────────────────────────────────────────────────────────────

	/**
	 * 销毁一个会话的全部 AI 数据（身份取当前上下文；无上下文 = legacy 命名空间）。
	 *
	 * @param sessionId 会话 id（前端页签级；允许特殊字符，键名/扫描均做了精确化处理）
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

		// ① durable pending 先登记（无 TTL）：从请求一开始就挡住同 id 的 /chat（guard 会看 pending），
		// 失败/延后由调度自动续清；未完成期间 guard 不会到期。
		// <b>已有 pending = 上一轮销毁没做完</b>：复用它的 purgeId/owner/attempts，不重开一轮。
		// 否则"本次前置失败 → 回滚删除 pending/disarm 守卫"会把上一轮的 deferred 义务一起抹掉。
		SessionPurgePendingStore.Registration registration;
		try {
			registration = this.pendingStore.register(identity, sessionId, requestedPurgeId, actor,
					System.currentTimeMillis());
		}
		catch (RuntimeException ex) {
			log.warn("会话销毁 pending 登记失败，未删除任何数据 sessionId={}：{}", sessionId, rootMessage(ex));
			return aborted(sessionId, requestedPurgeId, "pending", enumerationForFailure(sessionId, identity), ex);
		}
		String purgeId = registration.pending().purgeId();
		// owner 口径与守卫值（purgeId|actor）一致：复用时沿用原 owner，避免无意义的改写。
		String owner = registration.pending().requestedBy() == null || registration.pending().requestedBy().isBlank()
				? actor : registration.pending().requestedBy();
		// 回滚只允许撤销"本次新建"的台账；复用的那条属于上一轮，保留给调度/手动重试。
		boolean ownedByThisCall = registration.created();

		// ② 再枚举：任何读失败都在这里中止；本次新建的 pending 回滚，保证"一个数据键都不删"。
		Enumeration enumeration;
		try {
			enumeration = enumerate(sessionId);
		}
		catch (RuntimeException ex) {
			if (ownedByThisCall) {
				completePendingQuietly(sessionId, purgeId, "enumerate failed");
			}
			log.warn("会话销毁枚举失败，未删除任何数据 sessionId={} purgeId={}（复用既有 pending 时保留台账）"
					+ "：{}", sessionId, purgeId, rootMessage(ex));
			return aborted(sessionId, purgeId, "enumerate", enumerationForFailure(sessionId, identity), ex);
		}

		// ③ 装写回守卫（在删除任何数据之前；失败则整体中止并撤销本次新建的 pending）。
		try {
			this.guard.arm(sessionId, purgeId + "|" + owner, guardTtl());
		}
		catch (RuntimeException ex) {
			if (ownedByThisCall) {
				completePendingQuietly(sessionId, purgeId, "guard arm failed");
			}
			log.warn("会话销毁守卫写入失败，未删除任何数据 sessionId={} purgeId={}：{}", sessionId, purgeId,
					rootMessage(ex));
			return aborted(sessionId, purgeId, "guard", enumeration, ex);
		}

		// ④ H2 先删（反馈行在独立本地事务里；失败不进入 Redis 删除，并撤销本次新建的 pending/守卫）。
		SessionPurgeDatabase.Deleted dbDeleted;
		try {
			dbDeleted = deleteFeedback(sessionId);
		}
		catch (RuntimeException ex) {
			if (ownedByThisCall) {
				completePendingQuietly(sessionId, purgeId, "database failed");
				this.guard.disarmQuietly(sessionId, purgeId);
			}
			log.warn("会话销毁 H2 删除失败，Redis 面未动 sessionId={} purgeId={}（复用既有 pending 时保留台账/守卫）"
					+ "：{}", sessionId, purgeId, rootMessage(ex));
			return aborted(sessionId, purgeId, "database", enumeration, ex);
		}

		return executeDeletion(sessionId, purgeId, owner, enumeration, dbDeleted);
	}

	/**
	 * 自动/手动重试入口：复用已有 pending 的 purgeId，绝不重新登记 pending 或改写新守卫。
	 *
	 * <p><b>身份必须已按记录恢复</b>：本方法按"当前身份"定位台账与全部键。调用方
	 * （{@link SessionPurgeRetryRunner}）先读台账、再用台账里的 {@code tenantId/userId}
	 * {@code useContext}，然后才调这里 —— 不猜身份、不扫租户列表。
	 *
	 * @return null = pending 不存在或已换主（旧重试应停止）；否则与 {@link #purge} 同形结果
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

		Enumeration enumeration;
		try {
			enumeration = enumerate(sessionId);
		}
		catch (RuntimeException ex) {
			recordFailureQuietly(sessionId, purgeId, "enumerate: " + rootMessage(ex));
			return aborted(sessionId, purgeId, "enumerate",
					enumerationForFailure(sessionId, pending.identityOrNull()), ex);
		}

		SessionPurgeDatabase.Deleted dbDeleted;
		try {
			dbDeleted = deleteFeedback(sessionId);
		}
		catch (RuntimeException ex) {
			recordFailureQuietly(sessionId, purgeId, "database: " + rootMessage(ex));
			return aborted(sessionId, purgeId, "database", enumeration, ex);
		}

		return executeDeletion(sessionId, purgeId, actor, enumeration, dbDeleted);
	}

	/** 删除主体（首轮与自动重试共用）；complete 仅在全部删除 + 守卫收尾 + pending 移除都成功时为 true。 */
	private PurgeResult executeDeletion(String sessionId, String purgeId, String actor, Enumeration enumeration,
			SessionPurgeDatabase.Deleted dbDeleted) {
		// 活跃 run 先取消（已取消不重复取消），并给收尾一个短窗口。
		List<String> errors = new ArrayList<>();
		// 枚举阶段已经发现的"跳过非本身份"等结论随结果返回，不能只留在日志里。
		List<String> warnings = new ArrayList<>(enumeration.warnings());
		cancelActiveRuns(enumeration.runs(), actor, warnings);
		awaitTerminal(enumeration.runs(), warnings);
		Enumeration current = reloadRunStatuses(enumeration, warnings);

		// Redis 面：记忆 → 按 run 删 13 键 → 会话→run 索引残留复核 → 上下文 → 会话键 → 附件文件。
		MemoryView memory = deleteMemory(sessionId, current, errors);
		List<RunView> runViews = new ArrayList<>();
		List<DeferredView> deferred = new ArrayList<>();
		for (RunTarget target : current.runs()) {
			deleteOrDefer(target, runViews, deferred, errors, warnings);
		}
		// RT-28：会话→run 索引的 complete 哨兵只是"上次回填覆盖到哪"的加速标记，SADD 是 best-effort
		// （RunStore.create 失败只 WARN）。哨兵在、成员缺时，上面的候选集就漏了这一轮的 13 键/工具入参，
		// 而销毁会照旧报 complete=true。这里补一次"按全局索引复核本会话的 run 残留"：发现即按同一
		// 终态/延后口径处理（终态删掉、活跃 deferred ⇒ complete=false 可重试），并计入结果视图。
		for (RunTarget target : residualRuns(sessionId, current, errors)) {
			deleteOrDefer(target, runViews, deferred, errors, warnings);
		}
		ContextView context = deleteContext(sessionId, current.context(), errors, warnings);
		SessionView session = deleteSessionKeys(sessionId, errors, warnings);
		AttachmentView attachmentView = deleteAttachments(sessionId, current.attachments(), errors, warnings);
		boolean removedFromIndex = memoryRemovedFromIndex(sessionId, warnings);

		boolean deletionComplete = errors.isEmpty() && deferred.isEmpty();
		if (!deletionComplete) {
			// 保留 pending + 守卫，交给调度/手动 DELETE 续清；失败/延后不是 complete。
			String reason = !errors.isEmpty() ? errors.get(0)
					: "deferred runs: " + deferred.stream().map(DeferredView::runId).toList();
			if (!recordFailureQuietly(sessionId, purgeId, reason)) {
				errors.add("pending: 已被另一轮销毁接管或无法更新（旧重试停止）");
			}
		}
		else {
			boolean retained;
			try {
				retained = this.guard.retainOrArmIfOwner(sessionId, purgeId, actor, guardTtl());
			}
			catch (RuntimeException ex) {
				retained = false;
				errors.add("guard: 收尾失败（fail-closed，保留 pending）：" + rootMessage(ex));
			}
			if (retained) {
				// fix4：不再立刻移除台账 —— 保留到一次短期记忆复核（崩溃残留补偿）。
				// API 语义不变：complete=true 仍表示"本次删除主体已完成"；台账只是多背一次到点复核
				// （fix5：复核阶段在自己的 attempts 预算里跑，与主体阶段是否已耗尽无关）。
				try {
					if (!this.pendingStore.scheduleVerify(sessionId, purgeId,
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
				current.database(), dbDeleted, memory, current.memoryKey(), runViews, deferred, context,
				attachmentView, session, removedFromIndex, current.clientCleanup(), errors, warnings);
		log.info("会话销毁完成 sessionId={} purgeId={} complete={} runs={} deferred={} errors={} "
				+ "memoryExisted={} feedbackDeleted={} attachments={}/{}",
				sessionId, purgeId, complete, runViews.size(), deferred.size(), errors.size(),
				memory.existed(), dbDeleted.feedbackRows(),
				attachmentView.registrationsDeleted(), attachmentView.registered());
		return result;
	}

	private boolean recordFailureQuietly(String sessionId, String purgeId, String reason) {
		try {
			return this.pendingStore.recordFailure(sessionId, purgeId, reason);
		}
		catch (RuntimeException ex) {
			log.warn("记录会话销毁 pending 失败 sessionId={} purgeId={}（保留 pending，不 disarm）：{}", sessionId,
					purgeId, rootMessage(ex));
			return false;
		}
	}

	private void completePendingQuietly(String sessionId, String purgeId, String reason) {
		try {
			this.pendingStore.complete(sessionId, purgeId);
		}
		catch (RuntimeException ex) {
			log.warn("撤销会话销毁 pending 失败 sessionId={} purgeId={}（{}）：{}", sessionId, purgeId, reason,
					rootMessage(ex));
		}
	}

	// ── 枚举（只读；失败即中止） ─────────────────────────────────────────────

	private Enumeration enumerate(String sessionId) {
		String gateRunId = this.sessionGate.currentRunId(sessionId);
		String currentRunId = this.runStore.sessionCurrentRunId(sessionId);
		List<String> warnings = new ArrayList<>();
		if (currentRunId != null && currentRunId.trim().startsWith("{")) {
			// 前缀碰撞：ai:session:current:<sid> 可能是会话 "current:<sid>" 的会话记录（JSON）。
			// 它不是 runId，不能当候选轮次；会话键删除阶段会按值再判一次并跳过。
			currentRunId = null;
		}

		Set<String> candidates = new LinkedHashSet<>();
		// 已知属于本会话的候选（来自本会话索引）：无需再判归属，直接完整读取。
		Set<String> knownOwn = new LinkedHashSet<>();
		RunStore.SessionRunIndex sessionIndex = this.runStore.sessionRunIndex(sessionId);
		boolean fastIndex = sessionIndex.readable() && sessionIndex.complete()
				&& !sessionIndex.runIds().isEmpty();
		if (fastIndex) {
			// 已完整回填：只读本会话候选（不遍历 ai:runs 的全局成员，也不反序列化他会话快照）。
			candidates.addAll(sessionIndex.runIds());
			knownOwn.addAll(sessionIndex.runIds());
		}
		else {
			// 空索引 / 未回填 / 读失败：回退全局索引。“非空”不等于“完整”。
			Set<String> indexMembers = this.redis.opsForSet().members(RunStore.RUN_INDEX);
			if (indexMembers != null) {
				for (String member : indexMembers) {
					String runId = indexRunId(member);
					if (runId != null) {
						candidates.add(runId);
					}
				}
			}
		}
		if (gateRunId != null) {
			candidates.add(gateRunId);
		}
		if (currentRunId != null) {
			candidates.add(currentRunId);
		}

		List<RunTarget> runs = new ArrayList<>();
		List<String> toolCallIds = new ArrayList<>();
		Set<String> foundSessionRunIds = new LinkedHashSet<>();
		for (String runId : candidates) {
			boolean named = runId.equals(gateRunId) || runId.equals(currentRunId);
			if (!named && !knownOwn.contains(runId)) {
				// 回退分支（首次销毁没有 __session_runs_complete__ 哨兵）：全局索引成员可能是海量无关轮次，
				// 判归属只走轻量口（原始 JSON + 流式顶层 sessionId）—— 完整 RunSnapshot 只在确认属于
				// 本会话之后才读（下面的 get）。与 residualRuns 同一成本口径。
				RunStore.SnapshotOwner owner = this.runStore.snapshotOwner(runId);
				if (owner.state() == RunStore.SnapshotOwner.State.UNREADABLE) {
					// 归属不可知：既不能当"他会话"跳过（可能漏删本会话），也不能当本会话（可能误删）。
					// 枚举必须整次中止：此刻还在"一个数据键都没删"的阶段。
					throw new AiStateUnavailableException(
							"会话销毁枚举：无法确认 run[" + runId + "] 的归属（快照读失败/坏值），本次中止", null);
				}
				if (owner.state() == RunStore.SnapshotOwner.State.MISSING
						|| owner.state() == RunStore.SnapshotOwner.State.ABSENT) {
					// MISSING = 键已消失（死成员）；ABSENT = 键在但拿不到 sessionId（旧快照）。
					// 两者都无法归属，不猜：不为省成本删任何键（旧快照可能属于本会话，也可能属于他会话）。
					continue;
				}
				if (!sessionId.equals(owner.sessionId())) {
					continue;
				}
			}
			// 只有"点名（会话门/当前轮索引）"或"确认属于本会话"的 run 才完整反序列化。
			RunSnapshot snapshot = this.runStore.get(runId);
			if (snapshot == null) {
				// 索引里的死成员无法归属到会话；只有"会话门/当前轮索引"点名的才收进来。
				if (!runId.equals(gateRunId) && !runId.equals(currentRunId)) {
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
					// 绝不删它的 13 键（也不把它写进本会话的 run 索引）。
					warnings.add("run[" + runId + "]: 跳过非本身份的快照（snapshotIdentity="
							+ snapshot.getTenantId() + ":" + snapshot.getUserId() + "，不删、不计入索引）");
					log.warn("会话销毁跳过非本身份的 run runId={} sessionId={} snapshotIdentity={}:{}（不删、不计入索引）",
							runId, sessionId, snapshot.getTenantId(), snapshot.getUserId());
					continue;
				}
				foundSessionRunIds.add(runId);
				boolean active = !isTerminal(snapshot.getStatus()) || runId.equals(gateRunId);
				runs.add(new RunTarget(runId, snapshot.getStatus(), active));
			}
			for (var pending : this.runStore.pendingsLenient(runId)) {
				if (pending.getToolCallId() != null && !pending.getToolCallId().isBlank()) {
					toolCallIds.add(pending.getToolCallId());
				}
			}
		}
		if (!fastIndex && sessionIndex.readable()) {
			// 本次已完成一次按 sessionId 过滤的全局枚举：回填 + 打完整标记，下一轮才可走快路径。
			this.runStore.backfillSessionRuns(sessionId, foundSessionRunIds);
		}

		boolean memoryExisted;
		int memoryMessages = 0;
		String memoryKey;
		// 在飞 saveAll 的中间态也是本会话的 AI 数据（正常路径 RENAME 后消失，清理失败/进程中断时按 TTL 自清）。
		// 只带 staging 的会话今天会被误报 found=false + complete=true 后残留，故这里一并枚举。
		boolean memoryStaged = !stagingKeys(sessionId).isEmpty();
		if (this.chatMemoryRepository instanceof RedisChatMemoryRepository redisRepository) {
			memoryKey = redisRepository.readableKeyFor(sessionId);
			memoryExisted = Boolean.TRUE.equals(this.redis.hasKey(memoryKey)) || memoryStaged;
		}
		else {
			memoryKey = TenantKeyScope.scoped(RedisChatMemoryRepository.KEY_PREFIX, sessionId);
			List<?> memory = this.chatMemoryRepository.findByConversationId(sessionId);
			memoryMessages = memory == null ? 0 : memory.size();
			memoryExisted = memoryMessages > 0 || memoryStaged;
		}

		ContextTargets context = new ContextTargets(
				Boolean.TRUE.equals(this.redis.hasKey(this.runStore.contextKey(sessionId))),
				Boolean.TRUE.equals(this.redis.hasKey(this.runStore.contextLedgerKey(sessionId))),
				archiveKeys(sessionId),
				Boolean.TRUE.equals(this.redis.hasKey(TenantKeyScope.scoped(RunStore.SAFETY_PREFIX, sessionId))));

		boolean sessionCurrentExisted = Boolean.TRUE.equals(
				this.redis.hasKey(this.runStore.sessionCurrentKey(sessionId)));
		boolean sessionRecordExisted = Boolean.TRUE.equals(
				this.redis.hasKey(AiSessionService.keyFor(sessionId)));

		List<FormAttachmentStore.Attachment> attachmentList = this.attachments.findBySessionId(sessionId);
		SessionPurgeDatabase.Snapshot db = snapshotFeedback(sessionId);

		boolean found = memoryExisted || !runs.isEmpty() || context.any() || !attachmentList.isEmpty()
				|| db.feedbackRows() > 0 || sessionCurrentExisted || sessionRecordExisted;

		ClientCleanup cleanup = clientCleanup(sessionId, toolCallIds, RequestContextUtils.currentOrNull());
		return new Enumeration(runs, attachmentList, context, memoryExisted, memoryMessages, memoryKey, db,
				sessionCurrentExisted, sessionRecordExisted, found, cleanup, List.copyOf(warnings));
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

	/**
	 * 归档键集合 = 扫描结果 ∪ 由上下文 {@code turnNo} 推导的 0..turnNo。
	 *
	 * <p>为什么还要推导：{@code SCAN} 在 Redis Cluster 下只作用于<b>一条连接所在的节点</b>，
	 * 跨槽/跨节点的归档键可能扫不全。{@code ai:ctx:<sid>} 的 {@code turnNo} 是"已完成的轮数"，
	 * 归档键按 {@code turnNo} 命名，0..turnNo 的<b>单键 DEL</b> 是 Cluster 安全的补充面
	 * （扫描仍保留：它覆盖旧数据/异常重置后留下的更高 turnNo）。
	 */
	private List<String> archiveKeys(String sessionId) {
		Set<String> keys = new LinkedHashSet<>(scanArchives(sessionId));
		keys.addAll(archivesFromTurnNo(sessionId));
		return List.copyOf(keys);
	}

	/** 由 {@code ai:ctx:<sid>} 的 {@code turnNo} 推导归档键（读不到/类型不符时返回空）。 */
	private List<String> archivesFromTurnNo(String sessionId) {
		try {
			Object raw = this.redis.opsForHash().get(this.runStore.contextKey(sessionId), "turnNo");
			if (raw == null) {
				return List.of();
			}
			int turnNo = Integer.parseInt(String.valueOf(raw));
			if (turnNo < 0 || turnNo > MAX_DERIVED_ARCHIVE_TURNS) {
				return List.of();
			}
			List<String> keys = new ArrayList<>(turnNo + 1);
			for (int turn = 0; turn <= turnNo; turn++) {
				keys.add(this.runStore.contextArchiveKey(sessionId, turn));
			}
			return keys;
		}
		catch (RuntimeException ex) {
			// 值坏/类型不符（前缀碰撞时 ai:ctx:<sid> 可能是他会话的台账 LIST）：不推导，扫描面仍然生效。
			log.debug("推导归档 turnNo 失败 sessionId={}（按无推导处理）：{}", sessionId, ex.getMessage());
			return List.of();
		}
	}

	/**
	 * 归档键 <b>只按需扫描</b>，并做两道防误删：
	 * <ol>
	 * <li>sessionId 里的 Redis glob 元字符全部转义；</li>
	 * <li>键必须逐字以 {@code ai:ctx:archive:<tenantId>:<userId>:<sessionId>:} 开头，且后缀是
	 * <b>纯数字</b> turnNo。于是 {@code a} 的扫描不会把 {@code a:1:2}（属于会话 {@code a:1}）当成自己的
	 * {@code turnNo=1:2}；{@code abc} 也不会命中 {@code abcd}；身份段不同则前缀本身就不同。</li>
	 * </ol>
	 */
	private List<String> scanArchives(String sessionId) {
		String scopedPrefix = TenantKeyScope.scoped(RunStore.CONTEXT_ARCHIVE_PREFIX, sessionId);
		String prefix = scopedPrefix + ":";
		String pattern = escapeGlob(scopedPrefix) + ":*";
		List<String> archives = new ArrayList<>();
		// 逐 master 扫描（standalone 走单连接）；任一节点失败上抛，枚举阶段整体中止。
		List<String> scanned = RedisClusterScanSupport.scan(this.redis,
				ScanOptions.scanOptions().match(pattern).count(256).build(), 0);
		for (String key : scanned) {
			if (key == null || !key.startsWith(prefix)) {
				continue;
			}
			String turnNo = key.substring(prefix.length());
			if (TURN_NO.matcher(turnNo).matches()) {
				archives.add(key);
			}
		}
		return archives;
	}

	/**
	 * 本会话的记忆 staging 键（{@code chat:mem:staging:{<escapedId>}:<uuid>}）。
	 *
	 * <p>为什么销毁必须管它们：staging 是<b>在飞 {@code saveAll} 的中间态</b>，正常路径 RENAME 后消失，
	 * 清理失败/进程中断时靠 TTL 自清。销毁只删 tagged/legacy 时，这类键既让"只带 staging 的会话"被
	 * 误报 {@code found=false}，也会在 {@code complete=true} 之后因 RENAME 落地而变成残留
	 * （此时索引成员已被 SREM，{@code isConversationIndexed} 根本看不见它）。
	 *
	 * <p>两道防误删与归档扫描同口径：① sessionId 用 {@code escapedId} 转成 tag 内容（无花括号 ⇒ 无碰撞），
	 * ② <b>pattern 与判定前缀都过 {@link #escapeGlob}</b> —— {@code escapedId} 只转义 {@code %{} }，
	 * 不管 glob 元字符，所以 id 自带 {@code * ? [ ] \} 时必须由这里转义：否则 Redis 侧 pattern 语义
	 * 改变（{@code [} 开字符类、{@code \} 转义下一字符）会<b>连自己的键都匹配不到</b>（漏删 + 误报
	 * complete）。转义后仍逐字前缀过滤，也不会命中别的会话。扫描失败上抛（枚举阶段不得用部分结果
	 * 冒充完整）。
	 */
	private List<String> stagingKeys(String sessionId) {
		String prefix = RedisChatMemoryRepository.stagingKeyPrefix(sessionId);
		String pattern = escapeGlob(prefix) + "*";
		List<String> staging = new ArrayList<>();
		List<String> scanned = RedisClusterScanSupport.scan(this.redis,
				ScanOptions.scanOptions().match(pattern).count(256).build(), 0);
		for (String key : scanned) {
			if (key != null && key.startsWith(prefix)) {
				staging.add(key);
			}
		}
		return staging;
	}

	private static String escapeGlob(String value) {
		StringBuilder escaped = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char current = value.charAt(i);
			if (current == '*' || current == '?' || current == '[' || current == ']' || current == '\\') {
				escaped.append('\\');
			}
			escaped.append(current);
		}
		return escaped.toString();
	}

	// ── 活跃 run：取消 + 等终态 ──────────────────────────────────────────────

	private void cancelActiveRuns(List<RunTarget> runs, String actor, List<String> warnings) {
		for (RunTarget run : runs) {
			if (!run.active()) {
				continue;
			}
			try {
				// 已经确定取消的轮不重复调 cancel（重复取消会再写一次权威键；虽然语义幂等，
				// 但"活跃 run 不重复副作用"要求我们不做无意义的第二次动作）。
				if (this.cancellations.cancelState(run.runId()) != CancellationRegistry.CancelState.CANCELLED) {
					CancellationRegistry.CancelResult result = this.cancellations.cancel(run.runId(), actor);
					if (!result.redisWritten()) {
						warnings.add("run[" + run.runId() + "]: 取消标志未能写入 Redis（仅进程内生效）");
					}
				}
			}
			catch (RuntimeException ex) {
				warnings.add("run[" + run.runId() + "]: 取消失败（" + rootMessage(ex) + "）");
			}
		}
	}

	/** 在短窗口内等活跃 run 落终态；等不到就留给 {@code deferred}，由下次重试补删。 */
	private void awaitTerminal(List<RunTarget> runs, List<String> warnings) {
		if (this.activeWait.isZero() || runs.stream().noneMatch(RunTarget::active)) {
			return;
		}
		long deadline = System.nanoTime() + this.activeWait.toNanos();
		while (System.nanoTime() < deadline) {
			boolean anyActive = false;
			for (RunTarget run : runs) {
				if (!run.active()) {
					continue;
				}
				try {
					RunSnapshot snapshot = this.runStore.get(run.runId());
					if (snapshot != null && !isTerminal(snapshot.getStatus())) {
						anyActive = true;
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

	/** 等待窗口结束后按最新快照重算 active（取消可能在窗口内生效）。 */
	private Enumeration reloadRunStatuses(Enumeration enumeration, List<String> warnings) {
		List<RunTarget> refreshed = new ArrayList<>(enumeration.runs().size());
		for (RunTarget run : enumeration.runs()) {
			try {
				RunSnapshot snapshot = this.runStore.get(run.runId());
				if (snapshot == null) {
					refreshed.add(new RunTarget(run.runId(), run.status(), false));
				}
				else {
					boolean active = !isTerminal(snapshot.getStatus());
					refreshed.add(new RunTarget(run.runId(), snapshot.getStatus(), active));
				}
			}
			catch (RuntimeException ex) {
				warnings.add("run[" + run.runId() + "]: 重读状态失败（保守按活跃处理）：" + rootMessage(ex));
				refreshed.add(run);
			}
		}
		return enumeration.withRuns(refreshed);
	}

	// ── 删除：记忆 / run / 上下文 / 会话 / 附件 ─────────────────────────────

	/**
	 * 记忆面删除：tagged/legacy（仓储的单键命令）+ 本会话 staging 键（在飞写回的中间态）。
	 *
	 * <p><b>删完必须复核</b>：销毁与写回交错时，删完之后仍可能有 staging 落地/最终的 RENAME 落地；
	 * 索引成员此时已被 SREM，所以"不在索引"不能当"已删净"的证明。残留一律记成可重试错误
	 * （{@code complete=false}，pending 保留），由调度/再次 DELETE 补删 —— 绝不带着残留报成功。
	 */
	private MemoryView deleteMemory(String sessionId, Enumeration enumeration, List<String> errors) {
		MemoryCleanup cleanup = deleteMemoryKeys(sessionId, errors);
		return new MemoryView(enumeration.memoryExisted(), enumeration.memoryMessages(), cleanup.deleted());
	}

	/**
	 * <b>只碰记忆面</b>的删除 + 删后复核（销毁主体与 fix4 的一次性复核共用）。
	 *
	 * <p>范围严格限定 {@code chat:mem:*}（tagged/legacy）+ 本会话 staging 键；不碰 DB、附件、上下文、
	 * 会话键、run 级键与任何业务数据 —— 到点复核绝不能"顺手再删一遍"其它面。
	 *
	 * @return {@code deleted=true} 仅当"确认删净"（复核不可知/有残留都为 false）
	 */
	private MemoryCleanup deleteMemoryKeys(String sessionId, List<String> errors) {
		boolean deleted = true;
		try {
			this.chatMemoryRepository.deleteByConversationId(sessionId);
		}
		catch (RuntimeException ex) {
			errors.add("memory: " + rootMessage(ex));
			deleted = false;
		}
		StagingClear staging = clearStagingKeys(sessionId, errors);
		List<String> residue = new ArrayList<>(staging.remaining());
		boolean known = staging.known();
		if (this.chatMemoryRepository instanceof RedisChatMemoryRepository redisRepository) {
			try {
				if (redisRepository.hasLiveMemory(sessionId)) {
					residue.add(redisRepository.readableKeyFor(sessionId));
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
	 * @param errors 失败原因（可重试；由 runner 折算成 {@code recordFailure} 的退避）
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
	 * 守卫复核 → 记忆面 delete（tagged/legacy/staging）→ 删后复核 → 成功才移除台账。
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
		boolean armed;
		try {
			armed = this.guard.isArmed(sessionId);
		}
		catch (RuntimeException ex) {
			// 守卫不可知 ⇒ 不删（否则可能删掉"同名新会话"的活数据），记失败保留台账。
			return new MemoryVerify(false, List.of("guard: 状态不可知（fail-closed，不删）：" + rootMessage(ex)));
		}
		if (!armed) {
			return new MemoryVerify(false, List.of("guard: 未装（未确认已销毁，不删）"));
		}
		List<String> errors = new ArrayList<>();
		MemoryCleanup cleanup = deleteMemoryKeys(sessionId, errors);
		if (!cleanup.deleted() || !errors.isEmpty()) {
			return new MemoryVerify(false, List.copyOf(errors));
		}
		try {
			if (!this.pendingStore.complete(sessionId, purgeId)) {
				return new MemoryVerify(false, List.of("pending: 已被另一轮销毁接管，未移除旧台账"));
			}
		}
		catch (RuntimeException ex) {
			return new MemoryVerify(false, List.of("pending: 移除失败（保留 pending 以便重试）：" + rootMessage(ex)));
		}
		log.info("会话销毁记忆复核完成（crash 残留补偿）sessionId={} purgeId={}", sessionId, purgeId);
		return new MemoryVerify(true, List.of());
	}

	/**
	 * staging 清理结论。
	 *
	 * @param known {@code false} = 复核扫描失败，"有没有残留"<b>不可知</b>（不得当成"没有残留"）
	 * @param remaining 确认仍存在的 staging 键（含清理期间新落地的中间态）
	 */
	private record StagingClear(boolean known, List<String> remaining) {
	}

	/**
	 * 清理本会话的 staging 键并复核。
	 *
	 * <p>逐个单键 {@code DEL}（staging 键与任何其它键不同槽，多键 DEL 在 Cluster 下会 {@code CROSSSLOT}）。
	 * 扫描/删除失败都收敛成可重试错误，不抛出去 —— 记忆面失败不能把已经删掉的 run/上下文面变成
	 * "半路异常"（{@code complete=false} 已如实表达"没删净"）。
	 *
	 * <p><b>复核扫描失败不得被当成"空"</b>（I3）：返回 {@code known=false}，由调用方把
	 * {@code memory.deleted} 置假 —— "查不出来"与"确认没有"必须可区分。
	 */
	private StagingClear clearStagingKeys(String sessionId, List<String> errors) {
		try {
			for (String key : stagingKeys(sessionId)) {
				try {
					this.redis.delete(key);
				}
				catch (RuntimeException ex) {
					errors.add("memory: staging 键删除失败 " + key + "（可重试）：" + rootMessage(ex));
				}
			}
			return new StagingClear(true, stagingKeys(sessionId));
		}
		catch (RuntimeException ex) {
			errors.add("memory: staging 键扫描失败（未删净，可重试）：" + rootMessage(ex));
			return new StagingClear(false, List.of());
		}
	}

	/** 删除后该会话是否已不在记忆索引（compat 字段 {@code removedFromIndex}；查不出来按未移除）。 */
	private boolean memoryRemovedFromIndex(String sessionId, List<String> warnings) {
		try {
			if (this.chatMemoryRepository instanceof RedisChatMemoryRepository redisRepository) {
				return !redisRepository.isConversationIndexed(sessionId);
			}
			return !this.chatMemoryRepository.findConversationIds().contains(sessionId);
		}
		catch (RuntimeException ex) {
			warnings.add("memoryIndex: 读取会话索引失败（按未移出报告）：" + rootMessage(ex));
			return false;
		}
	}

	/**
	 * <b>RT-28：会话→run 索引的哨兵完整性复核</b> —— 销毁尾部按全局索引找"漏在候选集外"的本会话 run。
	 *
	 * <h2>为什么哨兵不够</h2>
	 * {@code ai:session:runs:<sid>} 的 {@code complete} 标记只说明"上次按 sessionId 全量回填覆盖过"，
	 * 而新 run 入索引是 best-effort（{@code RunStore#create} 的 SADD 失败只 WARN）。一次失败 + 已存在
	 * 哨兵 ⇒ 该 run 永远进不了候选集 ⇒ 13 键/工具入参/草稿键残留，而销毁照旧报 {@code complete=true}。
	 * 哨兵是<b>加速面</b>，不是唯一事实源 —— 所以销毁端点（非热路径）再做一次按全局索引的复核。
	 *
	 * <h2>成本（复审 Important 收口）</h2>
	 * 归属判断只走 {@link RunStore#snapshotOwner(String)}：原始 JSON + 流式取<b>顶层</b> {@code sessionId}，
	 * <b>不</b>物化 {@link RunSnapshot}。无关 run 因此只付一次单键 GET（不解析整窗 {@code messageJson}）；
	 * 完整快照只在"确实属于本会话"的 {@link #deleteOrDefer} 里读 —— 那部分成本上界 = 本会话的 run 数。
	 *
	 * <h2>为什么不设"扫描条数上限"</h2>
	 * 旧实现的 {@code residual.size()} 上限限制的是"发现了多少条本会话 run"，
	 * <b>对扫描量毫无作用</b>（无关 run 再多也照扫照解析）。而真正的"扫描条数上限"必须同时满足
	 * "不会卡死正确 purge"：一旦按条数截断，未扫到的成员就永远确认不了 ⇒ {@code complete=false} 永远成立
	 * ⇒ 调度每次自动重试都从头再扫，大会员集合上变成"永远删不完 + 重试放大"。故本批<b>不截断</b>：
	 * 成本由"轻量归属读"把常数压到一次 GET，完整性优先（索引本身由 {@code pruneRunIndex} 惰性收敛、
	 * 快照带 TTL）。
	 *
	 * <p>复核口径：{@link RunStore.SnapshotOwner.State#ATTRIBUTED} 且 {@code sessionId} 相同才算本会话；
	 * {@link RunStore.SnapshotOwner.State#ABSENT}（死成员/旧快照无该字段）<b>无法归属</b>，不猜（与
	 * {@link #enumerate} 同口径）；{@link RunStore.SnapshotOwner.State#UNREADABLE} <b>fail-closed</b>：
	 * 记可重试错误（{@code complete=false}），既不当"他会话"也不删它的键。
	 * <b>活跃 run 不在这里擅自删 13 键</b>：按 {@link #deleteOrDefer} 的终态/延后口径处理。
	 *
	 * <p>复核本身失败（索引读不出来）⇒ 记可重试错误：不能拿"查不出来"当"没有残留"。
	 */
	private List<RunTarget> residualRuns(String sessionId, Enumeration enumeration, List<String> errors) {
		Set<String> known = new LinkedHashSet<>();
		for (RunTarget target : enumeration.runs()) {
			known.add(target.runId());
		}
		List<RunTarget> residual = new ArrayList<>();
		Set<String> members;
		try {
			members = this.redis.opsForSet().members(RunStore.RUN_INDEX);
		}
		catch (RuntimeException ex) {
			errors.add("runs: 会话→run 索引复核失败（无法确认无残留，可重试）：" + rootMessage(ex));
			return residual;
		}
		if (members == null || members.isEmpty()) {
			return residual;
		}
		for (String member : members) {
			String runId = indexRunId(member);
			if (runId == null || runId.isBlank() || known.contains(runId)) {
				continue;
			}
			RunStore.SnapshotOwner owner = this.runStore.snapshotOwner(runId);
			if (owner.state() == RunStore.SnapshotOwner.State.UNREADABLE) {
				// 读失败/坏值：归属不可知。既不能当"他会话"（漏删本会话键），也不能删（可能删他会话）。
				errors.add("runs: 复核 run[" + runId + "] 无法确认归属（快照读失败/坏值，可重试）");
				continue;
			}
			// MISSING（死成员）与 ABSENT（旧快照无 sessionId）都无法归属：跳过，不猜、不删。
			if (owner.state() != RunStore.SnapshotOwner.State.ATTRIBUTED
					|| !sessionId.equals(owner.sessionId())) {
				continue;
			}
			known.add(runId);
			// 状态/活跃由 deleteOrDefer 用完整快照判定（这里只做归属，不物化快照）。
			residual.add(new RunTarget(runId, "UNKNOWN", true));
		}
		if (!residual.isEmpty()) {
			log.warn("会话→run 索引缺口：销毁时按全局索引补发现 {} 个本会话 run（哨兵 complete 但缺成员）sessionId={} "
					+ "runIds={}", residual.size(), sessionId,
					residual.stream().map(RunTarget::runId).toList());
		}
		return residual;
	}

	private void deleteOrDefer(RunTarget target, List<RunView> runViews, List<DeferredView> deferred,
			List<String> errors, List<String> warnings) {
		RunSnapshot latest = null;
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
		String status = latest == null ? null : latest.getStatus();
		if (latest != null && !belongsToCurrentIdentity(latest)) {
			// 归属复核（快照带身份才可信）：别的身份/无归属 legacy 的 run 一律不动；warning 进结果可取证。
			warnings.add("run[" + target.runId() + "]: 跳过非本身份（复核，snapshotIdentity="
					+ latest.getTenantId() + ":" + latest.getUserId() + "）");
			log.warn("会话销毁跳过非本身份的 run（复核）runId={} snapshotIdentity={}:{}", target.runId(),
					latest.getTenantId(), latest.getUserId());
			return;
		}
		boolean active = latest != null && !isTerminal(status);
		if (active) {
			deferred.add(new DeferredView(target.runId(), status, "RUN_ACTIVE"));
			runViews.add(new RunView(target.runId(), status, true, false, true));
			return;
		}
		try {
			this.runStore.deleteRun(target.runId());
			deleteObservabilityKeys(target.runId(), errors);
			runViews.add(new RunView(target.runId(), status, false, true, false));
		}
		catch (RuntimeException ex) {
			errors.add("run[" + target.runId() + "]: 13 键删除失败（可重试）：" + rootMessage(ex));
			runViews.add(new RunView(target.runId(), status, false, false, false));
		}
	}

	/**
	 * 13 键之外的四个"每人可见"的 run 级键（不进 {@code deleteRun}，本来按 TTL 自清；销毁要一并清掉）。
	 *
	 * <p>{@code ai:observed:<tenantId>:<userId>:<runId>} 是 Task 2 的<b>结算观测位</b>（HASH，首写即置
	 * session-ttl，自清即可）。用户裁定"所有 AI 数据在会话销毁时删除，不只靠 TTL"，而
	 * {@code ai:observed:} 与 {@code ai:beat:}/{@code ai:activity:} 同列 TTL 型 run 锚点 ⇒ 这里按
	 * runId 单键 DEL 一次，立即收敛。<b>钥匙必须与写入方同一个键</b>：身份段取自当前上下文
	 * （{@link TenantKeyScope}）—— 销毁主体全程在入口身份的作用域内执行，且 run 归属已按快照复核，
	 * 因此这里算出的就是"本轮在本身份下"的那把键；跨租户同 runId 的观测位不会被误删。
	 *
	 * <p><b>四把键逐把单键 DEL</b>（不做多键 {@code DEL} 合并）：这四族都<b>没有 hash tag</b>，
	 * 真集群下彼此不同槽 —— 合并成一条多键删除会在 Redis 侧 {@code CROSSSLOT}，反而整批删不掉。
	 * 单键命令天然安全，失败仍逐把进 {@code errors}（可重试语义不变）。
	 */
	private void deleteObservabilityKeys(String runId, List<String> errors) {
		deleteSingleRunAnchor(runId, this.runStore.beatKey(runId), "观测/取消", errors);
		deleteSingleRunAnchor(runId, this.runStore.activityKey(runId), "观测/取消", errors);
		deleteSingleRunAnchor(runId, this.cancellations.keyFor(runId), "观测/取消", errors);
		deleteSingleRunAnchor(runId, TenantKeyScope.scoped(OBSERVED_PREFIX, runId), "结算观测位", errors);
	}

	/** 单键 DEL 一把 run 锚点；失败只记可重试错误，不影响其余三把。 */
	private void deleteSingleRunAnchor(String runId, String key, String label, List<String> errors) {
		try {
			this.redis.delete(key);
		}
		catch (RuntimeException ex) {
			errors.add("run[" + runId + "]: " + label + "键删除失败（可重试，key=" + key + "）："
					+ rootMessage(ex));
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

	private ContextView deleteContext(String sessionId, ContextTargets targets, List<String> errors,
			List<String> warnings) {
		int deleted = 0;
		deleted += deleteKeyIfType(this.runStore.contextKey(sessionId), DataType.HASH, errors, warnings, "ctx");
		deleted += deleteKeyIfType(this.runStore.contextLedgerKey(sessionId), DataType.LIST, errors, warnings,
				"ctxLedger");
		for (String archive : targets.archives()) {
			deleted += deleteKeyIfType(archive, DataType.STRING, errors, warnings, "ctxArchive");
		}
		deleted += deleteKeyIfType(TenantKeyScope.scoped(RunStore.SAFETY_PREFIX, sessionId), DataType.LIST, errors,
				warnings, "safety");
		return new ContextView(targets.context(), targets.ledger(), targets.archives().size(), targets.safety(),
				deleted);
	}

	/**
	 * 按<b>类型</b>删除上下文类键。
	 *
	 * <p>前缀碰撞的硬防线：会话 {@code x} 的 {@code ai:ctx:ledger:x} 与会话 {@code ledger:x} 的
	 * {@code ai:ctx:ledger:x} 是同一个字符串，但两者类型不同（LIST vs HASH）。类型不匹配说明
	 * 这个键属于<b>另一个会话的另一个键族</b>，跳过并留 warning，绝不按"反正名字对上了"删掉。
	 */
	private int deleteKeyIfType(String key, DataType expected, List<String> errors, List<String> warnings,
			String label) {
		DataType actual;
		try {
			actual = this.redis.type(key);
		}
		catch (RuntimeException ex) {
			errors.add(label + "[" + key + "]: 类型读取失败（不删，避免误删他会话键）：" + rootMessage(ex));
			return 0;
		}
		if (actual == null || actual == DataType.NONE) {
			return 0;
		}
		if (actual != expected) {
			warnings.add(label + "[" + key + "]: 键类型为 " + actual + "（期望 " + expected
					+ "），判定为前缀碰撞的他会话键，跳过");
			return 0;
		}
		return deleteKey(key, errors, label);
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
	 * 会话键的值感知删除（前缀碰撞的第二道防线）：
	 * <ul>
	 * <li>{@code ai:session:current:<sid>} 的值应是 runId；若读到 JSON 对象，说明这个键实际是
	 * 另一个会话（id = {@code current:<sid>}）的会话记录，跳过；</li>
	 * <li>{@code ai:session:<sid>} 的值应是会话记录 JSON；若读到非 JSON（runId），说明它实际是
	 * 另一个会话（id = {@code current:<sid>} 之类）的 current 索引，跳过。</li>
	 * </ul>
	 */
	private SessionView deleteSessionKeys(String sessionId, List<String> errors, List<String> warnings) {
		boolean currentDeleted = false;
		boolean recordDeleted = false;
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
		// ai:session:runs:<sid> 与 id="runs:<sid>" 的会话记录是同一个键（"ai:session:"+id）。
		// 只删 SET（本会话的 run 索引）；其它类型判定为前缀碰撞的他会话键，跳过并留 warning。
		deleteKeyIfType(this.runStore.sessionRunsKey(sessionId), DataType.SET, errors, warnings, "sessionRuns");
		// 会话→活跃轮有序索引（ZSET，带 TTL）：销毁后成员指向已删的轮，留着只会让"活跃轮"的
		// 加速面指向空快照；按类型删除，别的身份的键前缀里带自己的身份段，不会命中。
		deleteKeyIfType(this.runStore.sessionActiveKey(sessionId), DataType.ZSET, errors, warnings, "sessionActive");
		return new SessionView(currentDeleted, recordDeleted);
	}

	private AttachmentView deleteAttachments(String sessionId, List<FormAttachmentStore.Attachment> list,
			List<String> errors, List<String> warnings) {
		int registrations = 0;
		int files = 0;
		List<String> failed = new ArrayList<>();
		for (FormAttachmentStore.Attachment attachment : list) {
			if (!sessionId.equals(attachment.sessionId())) {
				warnings.add("attachment[" + attachment.fileId() + "]: 登记归属与请求会话不一致，跳过（不删他会话文件）");
				continue;
			}
			String storagePath = attachment.storagePath();
			if (storagePath != null && !storagePath.isBlank()) {
				Path path;
				try {
					// 存储键可能是 UUID 相对键（新口径）或历史绝对路径；删前/删后必须同一条真实路径。
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
					// 删除抛错本身即失败事实：保留登记，重试时按同一路径再删。
					errors.add("attachment[" + attachment.fileId() + "]: 文件删除失败（登记保留以便重试）："
							+ storagePath + "（" + ex.getMessage() + "）");
					failed.add(attachment.fileId());
					continue;
				}
				// FileStorageService.delete 吞 IOException：以"同一个 resolve 后的路径删后是否还在"为唯一事实。
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
				FormAttachmentStore.Attachment removed = this.attachments.deleteRegistration(attachment.fileId());
				if (removed != null) {
					registrations++;
				}
			}
			catch (RuntimeException ex) {
				errors.add("attachment[" + attachment.fileId() + "]: 登记删除失败（可重试）：" + rootMessage(ex));
				failed.add(attachment.fileId());
			}
		}
		return new AttachmentView(list.size(), registrations, files, List.copyOf(failed));
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

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

	/**
	 * 全局索引成员 → 本销毁身份的 runId（不匹配返回 {@code null}）。
	 *
	 * <p>索引成员是<b>复合身份</b> {@code <tenantId>/<userId>/<runId>}
	 * （{@link com.example.configmgr.ai.tenant.RunKeyScope}）。只带 sessionId 无法归属，
	 * 所以这里先按成员身份过滤：别的身份 / 无归属 legacy 成员一律不参与本次销毁。
	 * 无身份（legacy 销毁）只认旧的裸 runId 成员 —— legacy 不做"跨身份清扫"。
	 */
	private static String indexRunId(String member) {
		TenantUser identity = RequestContextUtils.currentOrNull();
		RunKeyScope.IndexRef ref = RunKeyScope.parseIndexMember(member);
		if (identity == null) {
			return ref == null ? member : null;
		}
		if (ref == null) {
			return null;
		}
		return ref.tenantId() == identity.tenantId() && ref.userId() == identity.userId() ? ref.runId() : null;
	}

	/** 枚举失败时的占位结果（不读取任何依赖，保证"失败不半删"可判定）。 */
	private static Enumeration enumerationForFailure(String sessionId, TenantUser identity) {
		return new Enumeration(List.of(), List.of(), new ContextTargets(false, false, List.of(), false), false, 0,
				RedisChatMemoryRepository.KEY_PREFIX + TenantKeyScope.segmentOf(identity) + sessionId,
				new SessionPurgeDatabase.Snapshot(0), false, false, false,
				clientCleanup(sessionId, List.of(), identity), List.of());
	}

	private static PurgeResult aborted(String sessionId, String purgeId, String phase, Enumeration enumeration,
			RuntimeException error) {
		return new PurgeResult(sessionId, purgeId, false, enumeration.found(), phase, enumeration.database(), null,
				new MemoryView(enumeration.memoryExisted(), enumeration.memoryMessages(), false),
				enumeration.memoryKey(), List.of(), List.of(),
				new ContextView(enumeration.context().context(), enumeration.context().ledger(),
						enumeration.context().archives().size(), enumeration.context().safety(), 0),
				new AttachmentView(enumeration.attachments().size(), 0, 0, List.of()),
				new SessionView(false, false), false, enumeration.clientCleanup(),
				List.of(phase + ": " + rootMessage(error)), enumeration.warnings());
	}

	// ── 内部数据结构 ─────────────────────────────────────────────────────────

	private record RunTarget(String runId, String status, boolean active) {
	}

	private record ContextTargets(boolean context, boolean ledger, List<String> archives, boolean safety) {

		boolean any() {
			return this.context || this.ledger || !this.archives.isEmpty() || this.safety;
		}

	}

	private record Enumeration(List<RunTarget> runs, List<FormAttachmentStore.Attachment> attachments,
			ContextTargets context, boolean memoryExisted, int memoryMessages, String memoryKey,
			SessionPurgeDatabase.Snapshot database, boolean sessionCurrentExisted, boolean sessionRecordExisted,
			boolean found, ClientCleanup clientCleanup, List<String> warnings) {

		Enumeration withRuns(List<RunTarget> refreshed) {
			return new Enumeration(refreshed, this.attachments, this.context, this.memoryExisted,
					this.memoryMessages, this.memoryKey, this.database, this.sessionCurrentExisted,
					this.sessionRecordExisted, this.found, this.clientCleanup, this.warnings);
		}

	}

}
