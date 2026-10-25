package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.form.FormAttachmentStore;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.file.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.Cursor;
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
 * <h2>删除面（只删 AI 数据）</h2>
 * <ol>
 * <li>{@code chat:mem:*}（tagged + 迁移期 legacy）+ 会话 id 索引成员（SREM，<b>不删索引键</b>）；</li>
 * <li>该会话每一轮的<b>13 个 run 级键</b>（委托 {@link RunStore#deleteRun(String)}，按 run 逐个删，
 * 不使用 KEYS/通配删除）；外加 {@code ai:beat:} / {@code ai:activity:} / {@code ai:cancel:} 三个
 * 观测/取消键；</li>
 * <li>{@code ai:ctx:<sid>} / {@code ai:ctx:ledger:<sid>} / {@code ai:ctx:archive:<sid>:<turnNo>} /
 * {@code ai:safety:<sid>}；</li>
 * <li>表单附件：该会话的登记、登记索引成员、<b>且只按登记里的 storagePath 删文件</b>（不扫目录）；</li>
 * <li>{@code ai_feedback} / {@code ai_chat_message} 两张表的该会话行（H2 先行）；</li>
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
 * 枚举失败<b>一个键都不删</b>；H2 删失败也不进入 Redis 删除（两张表在同一本地事务里）。
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

	private static final Pattern TURN_NO = Pattern.compile("\\d+");

	private static final Duration DEFAULT_ACTIVE_WAIT = Duration.ofMillis(1500);

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
			db.put("chatMessageTable", this.databaseSnapshot != null && this.databaseSnapshot.chatTablePresent());
			db.put("feedbackRows", this.databaseSnapshot == null ? 0 : this.databaseSnapshot.feedbackRows());
			db.put("chatMessageRows", this.databaseSnapshot == null ? 0 : this.databaseSnapshot.chatMessageRows());
			db.put("feedbackDeleted", this.databaseDeleted == null ? 0 : this.databaseDeleted.feedbackRows());
			db.put("chatMessagesDeleted", this.databaseDeleted == null ? 0 : this.databaseDeleted.chatMessageRows());
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
	 * 销毁一个会话的全部 AI 数据。
	 *
	 * @param sessionId 会话 id（前端页签级；允许特殊字符，键名/扫描均做了精确化处理）
	 * @param requestedBy 发起方（写进取消标志与守卫，便于取证；null = unknown）
	 * @return 幂等结果；{@code complete=false} 表示有延后/部分失败，可重试
	 */
	public PurgeResult purge(String sessionId, String requestedBy) {
		if (sessionId == null || sessionId.isBlank()) {
			throw new IllegalArgumentException("sessionId 不得为空白");
		}
		String requestedPurgeId = UUID.randomUUID().toString();
		String actor = requestedBy == null || requestedBy.isBlank() ? "unknown" : requestedBy;

		// ① durable pending 先登记（无 TTL）：从请求一开始就挡住同 id 的 /chat（guard 会看 pending），
		// 失败/延后由调度自动续清；未完成期间 guard 不会到期。
		// <b>已有 pending = 上一轮销毁没做完</b>：复用它的 purgeId/owner/attempts，不重开一轮。
		// 否则"本次前置失败 → 回滚删除 pending/disarm 守卫"会把上一轮的 deferred 义务一起抹掉。
		SessionPurgePendingStore.Registration registration;
		try {
			registration = this.pendingStore.register(sessionId, requestedPurgeId, actor,
					System.currentTimeMillis());
		}
		catch (RuntimeException ex) {
			log.warn("会话销毁 pending 登记失败，未删除任何数据 sessionId={}：{}", sessionId, rootMessage(ex));
			return aborted(sessionId, requestedPurgeId, "pending", enumerationForFailure(sessionId), ex);
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
			return aborted(sessionId, purgeId, "enumerate", enumerationForFailure(sessionId), ex);
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

		// ④ H2 先删（两张表同一本地事务；失败不进入 Redis 删除，并撤销本次新建的 pending/守卫）。
		SessionPurgeDatabase.Deleted dbDeleted;
		try {
			dbDeleted = this.database.delete(sessionId, enumeration.database());
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
			return aborted(sessionId, purgeId, "enumerate", enumerationForFailure(sessionId), ex);
		}

		SessionPurgeDatabase.Deleted dbDeleted;
		try {
			dbDeleted = this.database.delete(sessionId, enumeration.database());
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
		List<String> warnings = new ArrayList<>();
		cancelActiveRuns(enumeration.runs(), actor, warnings);
		awaitTerminal(enumeration.runs(), warnings);
		Enumeration current = reloadRunStatuses(enumeration, warnings);

		// Redis 面：记忆 → 按 run 删 13 键 → 上下文 → 会话键 → 附件文件。
		MemoryView memory = deleteMemory(sessionId, current, errors);
		List<RunView> runViews = new ArrayList<>();
		List<DeferredView> deferred = new ArrayList<>();
		for (RunTarget target : current.runs()) {
			deleteOrDefer(target, runViews, deferred, errors);
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
				try {
					if (!this.pendingStore.complete(sessionId, purgeId)) {
						errors.add("pending: 已被另一轮销毁接管，未移除旧台账");
					}
				}
				catch (RuntimeException ex) {
					errors.add("pending: 移除失败（保留 pending 以便重试）：" + rootMessage(ex));
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
				+ "memoryExisted={} feedbackDeleted={} chatDeleted={} attachments={}/{}",
				sessionId, purgeId, complete, runViews.size(), deferred.size(), errors.size(),
				memory.existed(), dbDeleted.feedbackRows(), dbDeleted.chatMessageRows(),
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
		if (currentRunId != null && currentRunId.trim().startsWith("{")) {
			// 前缀碰撞：ai:session:current:<sid> 可能是会话 "current:<sid>" 的会话记录（JSON）。
			// 它不是 runId，不能当候选轮次；会话键删除阶段会按值再判一次并跳过。
			currentRunId = null;
		}

		Set<String> candidates = new LinkedHashSet<>();
		RunStore.SessionRunIndex sessionIndex = this.runStore.sessionRunIndex(sessionId);
		boolean fastIndex = sessionIndex.readable() && sessionIndex.complete()
				&& !sessionIndex.runIds().isEmpty();
		if (fastIndex) {
			// 已完整回填：只读本会话候选（不遍历 ai:runs 的全局成员，也不反序列化他会话快照）。
			candidates.addAll(sessionIndex.runIds());
		}
		else {
			// 空索引 / 未回填 / 读失败：回退全局索引。“非空”不等于“完整”。
			Set<String> indexMembers = this.redis.opsForSet().members(RunStore.RUN_INDEX);
			if (indexMembers != null) {
				candidates.addAll(indexMembers);
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
		if (this.chatMemoryRepository instanceof RedisChatMemoryRepository redisRepository) {
			memoryKey = redisRepository.readableKeyFor(sessionId);
			memoryExisted = Boolean.TRUE.equals(this.redis.hasKey(memoryKey));
		}
		else {
			memoryKey = RedisChatMemoryRepository.KEY_PREFIX + sessionId;
			List<?> memory = this.chatMemoryRepository.findByConversationId(sessionId);
			memoryMessages = memory == null ? 0 : memory.size();
			memoryExisted = memoryMessages > 0;
		}

		ContextTargets context = new ContextTargets(
				Boolean.TRUE.equals(this.redis.hasKey(RunStore.CONTEXT_PREFIX + sessionId)),
				Boolean.TRUE.equals(this.redis.hasKey(RunStore.CONTEXT_LEDGER_PREFIX + sessionId)),
				archiveKeys(sessionId),
				Boolean.TRUE.equals(this.redis.hasKey(RunStore.SAFETY_PREFIX + sessionId)));

		boolean sessionCurrentExisted = Boolean.TRUE.equals(
				this.redis.hasKey(RunStore.SESSION_CURRENT_PREFIX + sessionId));
		boolean sessionRecordExisted = Boolean.TRUE.equals(
				this.redis.hasKey(AiSessionService.KEY_PREFIX + sessionId));

		List<FormAttachmentStore.Attachment> attachmentList = this.attachments.findBySessionId(sessionId);
		SessionPurgeDatabase.Snapshot db = this.database.snapshot(sessionId);

		boolean found = memoryExisted || !runs.isEmpty() || context.any() || !attachmentList.isEmpty()
				|| db.feedbackRows() > 0 || db.chatMessageRows() > 0 || sessionCurrentExisted
				|| sessionRecordExisted;

		ClientCleanup cleanup = new ClientCleanup(CLIENT_MIRROR_PREFIX + sessionId, CLIENT_DRAFT_PREFIX,
				List.copyOf(new LinkedHashSet<>(toolCallIds)));
		return new Enumeration(runs, attachmentList, context, memoryExisted, memoryMessages, memoryKey, db,
				sessionCurrentExisted, sessionRecordExisted, found, cleanup);
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
			Object raw = this.redis.opsForHash().get(RunStore.CONTEXT_PREFIX + sessionId, "turnNo");
			if (raw == null) {
				return List.of();
			}
			int turnNo = Integer.parseInt(String.valueOf(raw));
			if (turnNo < 0 || turnNo > MAX_DERIVED_ARCHIVE_TURNS) {
				return List.of();
			}
			List<String> keys = new ArrayList<>(turnNo + 1);
			for (int turn = 0; turn <= turnNo; turn++) {
				keys.add(RunStore.CONTEXT_ARCHIVE_PREFIX + sessionId + ":" + turn);
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
	 * <li>键必须逐字以 {@code ai:ctx:archive:<sessionId>:} 开头，且后缀是<b>纯数字</b> turnNo。
	 * 于是 {@code a} 的扫描不会把 {@code a:1:2}（属于会话 {@code a:1}）当成自己的
	 * {@code turnNo=1:2}；{@code abc} 也不会命中 {@code abcd}。</li>
	 * </ol>
	 */
	private List<String> scanArchives(String sessionId) {
		String prefix = RunStore.CONTEXT_ARCHIVE_PREFIX + sessionId + ":";
		String pattern = RunStore.CONTEXT_ARCHIVE_PREFIX + escapeGlob(sessionId) + ":*";
		List<String> archives = new ArrayList<>();
		try (Cursor<String> cursor = this.redis.scan(ScanOptions.scanOptions().match(pattern).count(256).build())) {
			if (cursor == null) {
				return archives;
			}
			while (cursor.hasNext()) {
				String key = cursor.next();
				if (key == null || !key.startsWith(prefix)) {
					continue;
				}
				String turnNo = key.substring(prefix.length());
				if (TURN_NO.matcher(turnNo).matches()) {
					archives.add(key);
				}
			}
		}
		return archives;
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

	private MemoryView deleteMemory(String sessionId, Enumeration enumeration, List<String> errors) {
		try {
			this.chatMemoryRepository.deleteByConversationId(sessionId);
			return new MemoryView(enumeration.memoryExisted(), enumeration.memoryMessages(), true);
		}
		catch (RuntimeException ex) {
			errors.add("memory: " + rootMessage(ex));
			return new MemoryView(enumeration.memoryExisted(), enumeration.memoryMessages(), false);
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

	private void deleteOrDefer(RunTarget target, List<RunView> runViews, List<DeferredView> deferred,
			List<String> errors) {
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

	/** 13 键之外的三个人可观测/取消键（它们不进 deleteRun，按 TTL 自清；销毁要一并清掉）。 */
	private void deleteObservabilityKeys(String runId, List<String> errors) {
		try {
			this.redis.delete(List.of(this.runStore.beatKey(runId), this.runStore.activityKey(runId),
					this.cancellations.keyFor(runId)));
		}
		catch (RuntimeException ex) {
			errors.add("run[" + runId + "]: 观测/取消键删除失败（可重试）：" + rootMessage(ex));
		}
	}

	private ContextView deleteContext(String sessionId, ContextTargets targets, List<String> errors,
			List<String> warnings) {
		int deleted = 0;
		deleted += deleteKeyIfType(RunStore.CONTEXT_PREFIX + sessionId, DataType.HASH, errors, warnings, "ctx");
		deleted += deleteKeyIfType(RunStore.CONTEXT_LEDGER_PREFIX + sessionId, DataType.LIST, errors, warnings,
				"ctxLedger");
		for (String archive : targets.archives()) {
			deleted += deleteKeyIfType(archive, DataType.STRING, errors, warnings, "ctxArchive");
		}
		deleted += deleteKeyIfType(RunStore.SAFETY_PREFIX + sessionId, DataType.LIST, errors, warnings, "safety");
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
		String recordKey = AiSessionService.KEY_PREFIX + sessionId;
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

	/** 枚举失败时的占位结果（不读取任何依赖，保证"失败不半删"可判定）。 */
	private static Enumeration enumerationForFailure(String sessionId) {
		return new Enumeration(List.of(), List.of(), new ContextTargets(false, false, List.of(), false), false, 0,
				RedisChatMemoryRepository.KEY_PREFIX + sessionId,
				new SessionPurgeDatabase.Snapshot(false, 0, 0), false, false, false,
				new ClientCleanup(CLIENT_MIRROR_PREFIX + sessionId, CLIENT_DRAFT_PREFIX, List.of()));
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
				List.of(phase + ": " + rootMessage(error)), List.of());
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
			boolean found, ClientCleanup clientCleanup) {

		Enumeration withRuns(List<RunTarget> refreshed) {
			return new Enumeration(refreshed, this.attachments, this.context, this.memoryExisted,
					this.memoryMessages, this.memoryKey, this.database, this.sessionCurrentExisted,
					this.sessionRecordExisted, this.found, this.clientCleanup);
		}

	}

}
