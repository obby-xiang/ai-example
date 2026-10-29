package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.run.RunSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本轮<b>权威执行信封</b>：首轮请求边界冻结下来的那份"这一轮是谁、跑在哪个上下文、已经用掉多少预算"
 * 的只读快照，随 {@code ToolCallingChatOptions.toolContext} 一路带到工具执行点。
 *
 * <h2>它解决什么</h2>
 * 工具执行点（{@link SpToolCallingManager#executeToolCalls}）原先只认 {@code ai:run:<runId>} 里
 * 那份外置快照。快照读不到（键被清/TTL 边界/运维误删）而 runId 仍在时，旧实现"回落官方默认执行"——
 * 于是 <b>DANGER 工具会绕过确认门直接执行</b>。本类让执行点在本轮请求里带上一份<b>本实例创建</b>的
 * 权威事实，可以据此把快照原地恢复，继续走正常预检 → 挂起 → 确认卡 → 批准续跑；
 * 恢复不了才按失败关闭处理（见 {@link SpToolCallingManager}）。
 *
 * <h2>信任边界（三条硬口径）</h2>
 * <ol>
 * <li><b>只由本实例创建者消费</b>：{@link #createdBy()} 必须等于 {@code RunStore#instanceId()}。
 * 别的实例写的信封一律不认（它的续跑权、租约与 seq 空间都不在本实例手上）；</li>
 * <li><b>只恢复"运行事实"，不伪造审批/台账/认领</b>：信封里没有、也不承载
 * {@code PendingToolCall} 的任何决策态；恢复出的快照不会产生已批准、已执行或已认领的条目；</li>
 * <li><b>不重置序号 / 预算 / 步数</b>：{@link #toolSteps()}、{@link #round()}、
 * {@link #budgetArmedAtMs()}、{@link #budgetSkippedBatches()} 原值带回；{@link #seqFloor()} 只作
 * 诊断与续号下界证据，执行点<b>不写</b> {@code ai:seq}（序号只由帧存储发放）。</li>
 * </ol>
 *
 * <p>结构是不可变值对象；{@link #fromSnapshot(RunSnapshot, Long)} 是生产构造口，
 * {@link #fromToolContext(Map)} 是执行点的读取口（同时兼容对象与 Map 两种承载形态）。
 */
public final class ToolExecutionEnvelope {

	/** {@code toolContext} 里的键名（写入方与读取方共用这一个常量）。 */
	public static final String TOOL_CONTEXT_KEY = "executionEnvelope";

	private final String runId;

	private final String sessionId;

	/** 本轮归属的租户（{@code 0} = 本字段引入前的旧信封：没有身份可恢复，不伪造默认租户）。 */
	private final long tenantId;

	/** 本轮归属的用户（{@code -1} = 系统账号；{@code 0} = 旧信封）。 */
	private final long userId;

	private final Long providerId;

	private final String modelId;

	/** 本轮冻结的披露上下文（{@code AiContext#toMap()} 的副本）。 */
	private final Map<String, Object> context;

	/** 冻结的生效参数；{@code null} = 该轮未冻结（{@code paramsVersion=0} 的旧轮）。 */
	private final RunSnapshot.FrozenParams frozen;

	private final int round;

	private final int toolSteps;

	private final long budgetArmedAtMs;

	private final int budgetSkippedBatches;

	/** 本轮<b>创建时刻</b>（原快照的 {@code createdAtMs}）：恢复时原值写回，不拿当前钟冒充。 */
	private final long createdAtMs;

	private final String createdBy;

	/** 信封生成时本写出器已处置到的最大业务 seq（只读下界；0 = 未知）。 */
	private final long seqFloor;

	private ToolExecutionEnvelope(String runId, String sessionId, long tenantId, long userId, Long providerId,
			String modelId, Map<String, Object> context, RunSnapshot.FrozenParams frozen, int round, int toolSteps,
			long budgetArmedAtMs, int budgetSkippedBatches, long createdAtMs, String createdBy, long seqFloor) {
		this.runId = runId;
		this.sessionId = sessionId;
		this.tenantId = tenantId;
		this.userId = userId;
		this.providerId = providerId;
		this.modelId = modelId;
		this.context = context;
		this.frozen = frozen;
		this.round = round;
		this.toolSteps = toolSteps;
		this.budgetArmedAtMs = budgetArmedAtMs;
		this.budgetSkippedBatches = budgetSkippedBatches;
		this.createdAtMs = createdAtMs;
		this.createdBy = createdBy;
		this.seqFloor = seqFloor;
	}

	/**
	 * 从轮次快照冻结一份执行信封（<b>生产构造口</b>；调用点 = 请求边界）。
	 *
	 * @param snapshot 本轮快照（{@code null} 或 runId 为空 ⇒ 返回 {@code null}，不造无主信封）
	 * @param seqFloor 本写出器已处置到的最大业务 seq（{@code null} = 未知，归一为 0）
	 */
	public static ToolExecutionEnvelope fromSnapshot(RunSnapshot snapshot, Long seqFloor) {
		if (snapshot == null || isBlank(snapshot.getRunId())) {
			return null;
		}
		return new ToolExecutionEnvelope(snapshot.getRunId(), snapshot.getSessionId(), snapshot.getTenantId(),
				snapshot.getUserId(), snapshot.getProviderId(), snapshot.getModelId(),
				copyOf(snapshot.getContext()),
				snapshot.paramsFrozen() ? snapshot.frozenParams() : null, snapshot.getRound(),
				snapshot.getToolSteps(), snapshot.getBudgetArmedAtMs(), snapshot.getBudgetSkippedBatches(),
				snapshot.getCreatedAtMs(), snapshot.getCreatedBy(), seqFloor == null ? 0L : Math.max(0L, seqFloor));
	}

	/**
	 * 从 {@code toolContext} 读取信封（<b>执行点读取口</b>）。
	 *
	 * <p>承载形态兼容两种：本类实例（生产写入方的形态）或同名键的 Map（跨进程/序列化转手的形态）。
	 * 任何缺字段/类型不可解析 ⇒ 返回 {@code null}（调用方按"不可恢复"失败关闭），
	 * <b>绝不抛异常</b> —— 一个坏信封不能把整轮工具执行打断。
	 */
	public static ToolExecutionEnvelope fromToolContext(Map<String, Object> toolContext) {
		if (toolContext == null) {
			return null;
		}
		Object raw = toolContext.get(TOOL_CONTEXT_KEY);
		if (raw instanceof ToolExecutionEnvelope envelope) {
			return envelope;
		}
		if (raw instanceof Map<?, ?> map) {
			return fromMap(map);
		}
		return null;
	}

	private static ToolExecutionEnvelope fromMap(Map<?, ?> map) {
		String runId = text(map.get("runId"));
		String createdBy = text(map.get("createdBy"));
		if (isBlank(runId) || isBlank(createdBy)) {
			return null;
		}
		String sessionId = text(map.get("sessionId"));
		long tenantId = longOrZero(map.get("tenantId"));
		long userId = longOrZero(map.get("userId"));
		Long providerId = longOrNull(map.get("providerId"));
		String modelId = text(map.get("modelId"));
		Map<String, Object> context = copyOf(asMap(map.get("context")));
		RunSnapshot.FrozenParams frozen = frozenOf(map.get("frozen"));
		int round = intOrZero(map.get("round"));
		int toolSteps = intOrZero(map.get("toolSteps"));
		long budgetArmedAtMs = longOrZero(map.get("budgetArmedAtMs"));
		int budgetSkippedBatches = intOrZero(map.get("budgetSkippedBatches"));
		long createdAtMs = longOrZero(map.get("createdAtMs"));
		long seqFloor = longOrZero(map.get("seqFloor"));
		return new ToolExecutionEnvelope(runId, sessionId, tenantId, userId, providerId, modelId, context, frozen,
				round, toolSteps, budgetArmedAtMs, budgetSkippedBatches, createdAtMs, createdBy,
				Math.max(0L, seqFloor));
	}

	/**
	 * 恢复成一份轮次快照（只含运行事实：身份/上下文/冻结参数/轮次/步数/预算/创建者/创建时刻）。
	 *
	 * <p>{@code createdAtMs}（以及 identity）原值带回 —— 恢复不是"新建一轮"，不能拿当前钟冒充；
	 * {@code updatedAtMs} 才是本次落库时刻。
	 *
	 * <p>刻意<b>不</b>带：{@code messageJson}（挂起时会由 {@code RunStore#saveSuspended} 用模型真正
	 * 看到的完整历史覆盖）、{@code inFlightToolCalls}、任何条目的审批/认领/台账状态 —— 于是"恢复"
	 * 不可能伪造审批。状态写 {@code RUNNING}（调用点正在驱动这一轮），取消标志不置。
	 */
	public RunSnapshot toSnapshot() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(this.runId);
		snapshot.setSessionId(this.sessionId);
		snapshot.setTenantId(this.tenantId);
		snapshot.setUserId(this.userId);
		snapshot.setProviderId(this.providerId);
		snapshot.setModelId(this.modelId);
		snapshot.setContext(copyOf(this.context));
		snapshot.setRound(this.round);
		snapshot.setToolSteps(this.toolSteps);
		snapshot.setBudgetArmedAtMs(this.budgetArmedAtMs);
		snapshot.setBudgetSkippedBatches(this.budgetSkippedBatches);
		snapshot.setCreatedBy(this.createdBy);
		snapshot.setStatus(RunSnapshot.RUNNING);
		// 创建时刻是本轮事实：原值带回（旧信封没有该字段时无从得知，才落到当前钟）。
		long now = System.currentTimeMillis();
		snapshot.setCreatedAtMs(this.createdAtMs > 0L ? this.createdAtMs : now);
		snapshot.setUpdatedAtMs(now);
		if (this.frozen != null) {
			snapshot.freezeParams(this.frozen);
		}
		return snapshot;
	}

	public String runId() {
		return this.runId;
	}

	public String sessionId() {
		return this.sessionId;
	}

	/** 本轮归属的租户（{@code 0} = 旧信封：没有身份可恢复，调用方不得伪造默认租户）。 */
	public long tenantId() {
		return this.tenantId;
	}

	/** 本轮归属的用户（{@code -1} = 系统账号；{@code 0} = 旧信封）。 */
	public long userId() {
		return this.userId;
	}

	public Long providerId() {
		return this.providerId;
	}

	public String modelId() {
		return this.modelId;
	}

	/** 冻结的披露上下文（副本，外部修改不改本对象）。 */
	public Map<String, Object> context() {
		return copyOf(this.context);
	}

	public RunSnapshot.FrozenParams frozen() {
		return this.frozen;
	}

	public int round() {
		return this.round;
	}

	public int toolSteps() {
		return this.toolSteps;
	}

	public long budgetArmedAtMs() {
		return this.budgetArmedAtMs;
	}

	public int budgetSkippedBatches() {
		return this.budgetSkippedBatches;
	}

	/** 本轮创建时刻（原快照 {@code createdAtMs}；0 = 旧信封没带该字段）。 */
	public long createdAtMs() {
		return this.createdAtMs;
	}

	public String createdBy() {
		return this.createdBy;
	}

	public long seqFloor() {
		return this.seqFloor;
	}

	/** 该信封是否可用于恢复指定轮次：runId 对得上，且由本实例创建。 */
	public boolean restorableBy(String expectedRunId, String instanceId) {
		if (this.runId == null || this.createdBy == null) {
			return false;
		}
		if (expectedRunId != null && !expectedRunId.equals(this.runId)) {
			return false;
		}
		return this.createdBy.equals(instanceId);
	}

	private static RunSnapshot.FrozenParams frozenOf(Object raw) {
		if (raw instanceof RunSnapshot.FrozenParams frozen) {
			return frozen;
		}
		if (!(raw instanceof Map<?, ?> map)) {
			return null;
		}
		Double temperature = doubleOrNull(map.get("temperature"));
		Double topP = doubleOrNull(map.get("topP"));
		Integer maxTokens = intOrNull(map.get("maxTokens"));
		boolean paramsLocked = Boolean.TRUE.equals(map.get("paramsLocked"));
		return new RunSnapshot.FrozenParams(temperature, topP, maxTokens, paramsLocked);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object raw) {
		return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
	}

	private static Map<String, Object> copyOf(Map<String, Object> source) {
		return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
	}

	private static String text(Object raw) {
		return raw == null ? null : String.valueOf(raw);
	}

	private static Long longOrNull(Object raw) {
		if (raw instanceof Number number) {
			return number.longValue();
		}
		if (raw == null) {
			return null;
		}
		try {
			return Long.valueOf(String.valueOf(raw));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static long longOrZero(Object raw) {
		Long value = longOrNull(raw);
		return value == null ? 0L : value;
	}

	private static Integer intOrNull(Object raw) {
		Long value = longOrNull(raw);
		return value == null ? null : value.intValue();
	}

	private static int intOrZero(Object raw) {
		Integer value = intOrNull(raw);
		return value == null ? 0 : value;
	}

	private static Double doubleOrNull(Object raw) {
		if (raw instanceof Number number) {
			return number.doubleValue();
		}
		if (raw == null) {
			return null;
		}
		try {
			return Double.valueOf(String.valueOf(raw));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
