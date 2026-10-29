package com.example.configmgr.ai.tenant;

/**
 * run 级 Redis 键的<b>身份段 / hash tag</b>唯一拼接口（租户任务 2 核心组）。
 *
 * <h2>键形</h2>
 * <ul>
 * <li>{@code <prefix>{<tenantId>:<userId>:run:<runId>}}，例如
 * {@code ai:run:{1:101:run:9f…}}；</li>
 * <li>无身份上下文时退回<b>旧键形</b> {@code <prefix>{run:<runId>}} —— 与
 * {@link TenantKeyScope} 同一口径：没有默认租户/默认用户，只有一个<b>独立的 legacy 命名空间</b>。
 * 带身份的新路径永远不会落到旧键上，旧数据也就不会被新身份读到。</li>
 * </ul>
 *
 * <h2>为什么 tag 必须同时含身份与 runId</h2>
 * <ul>
 * <li><b>隔离</b>：两个租户各自生成同形 runId 是合法且必然的（runId 不是跨租户唯一性证明）。
 * tag 进键之后，"同 id 不同身份"落到两个键上，读写删不再依赖"每次都记得查归属"；</li>
 * <li><b>同槽</b>：一轮的 13 个键共享同一 tag ⇒ {@code deleteRun} 的多键 {@code DEL} 是一条原子命令；
 * {@code FENCED_SET}（快照+fence）、{@code IDEM_PUSH}（台账+索引）、{@code ACQUIRE}（租约+取号计数器
 * +fence）三条多键 Lua 不会 {@code CROSSSLOT}。取号计数器必须与租约键同槽（号与占用同序）。</li>
 * </ul>
 *
 * <h2>解析口径</h2>
 * {@link #parseScopedKey(String, String)} 只认<b>带身份</b>的 tag：无 tag 的旧裸键、以及
 * legacy tag {@code {run:<runId>}} 都返回 {@code null}（"无归属"）—— 判据退化比猜一个身份安全。
 * 需要按记录恢复身份的路径一律先取复合索引成员（{@link #parseIndexMember(String)}）再 {@code useContext}。
 */
public final class RunKeyScope {

	/** 全局索引成员的分隔符（设计稿：{@code tenantId/userId/recordId}）。 */
	public static final String INDEX_SEPARATOR = "/";

	private static final String RUN_MARK = "run:";

	private RunKeyScope() {
	}

	/** 当前线程身份的 run tag（无上下文 = legacy {@code {run:<runId>}}）。 */
	public static String tag(String runId) {
		return tagContent(TenantKeyScope.currentSegment(), runId);
	}

	/** 显式身份的 run tag。 */
	public static String tag(long tenantId, long userId, String runId) {
		return tagContent(TenantKeyScope.segmentOf(tenantId, userId), runId);
	}

	public static String tag(TenantUser identity, String runId) {
		return tagContent(TenantKeyScope.segmentOf(identity), runId);
	}

	private static String tagContent(String identitySegment, String runId) {
		if (runId == null || runId.isBlank()) {
			throw new IllegalArgumentException("runId 不能为空：run 级键的 hash tag 由身份 + runId 组成");
		}
		if (runId.indexOf('{') >= 0 || runId.indexOf('}') >= 0) {
			throw new IllegalArgumentException("runId 不得包含花括号（会破坏 hash tag 边界）：" + runId);
		}
		return "{" + identitySegment + RUN_MARK + runId + "}";
	}

	/** 当前线程身份的 run 级键。 */
	public static String scoped(String prefix, String runId) {
		return prefix + tag(runId);
	}

	/** 显式身份的 run 级键。 */
	public static String scoped(String prefix, long tenantId, long userId, String runId) {
		return prefix + tag(tenantId, userId, runId);
	}

	public static String scoped(String prefix, TenantUser identity, String runId) {
		return prefix + tag(identity, runId);
	}

	/** 全局索引成员：有身份 = {@code tenantId/userId/runId}；无身份 = 旧形 {@code runId}。 */
	public static String indexMember(String runId) {
		return indexMember(TenantKeyScope.currentSegment(), runId);
	}

	public static String indexMember(long tenantId, long userId, String runId) {
		return indexMember(TenantKeyScope.segmentOf(tenantId, userId), runId);
	}

	public static String indexMember(TenantUser identity, String runId) {
		return indexMember(identity.tenantId(), identity.userId(), runId);
	}

	private static String indexMember(String identitySegment, String runId) {
		if (runId == null || runId.isBlank()) {
			throw new IllegalArgumentException("runId 不能为空：全局索引成员由身份 + runId 组成");
		}
		if (identitySegment.isEmpty()) {
			return runId;
		}
		// segment 形如 "<tenantId>:<userId>:" → 成员形如 "<tenantId>/<userId>/<runId>"
		return identitySegment.replace(':', INDEX_SEPARATOR.charAt(0)) + runId;
	}

	/**
	 * 解析全局索引成员。
	 *
	 * @return 复合成员；不是 {@code tenantId/userId/runId} 形态（含无归属的老成员）返回 {@code null}
	 *         —— 调用方必须把它当"无归属"，不得猜成任何真实身份
	 */
	public static IndexRef parseIndexMember(String member) {
		if (member == null || member.isBlank()) {
			return null;
		}
		int first = member.indexOf(INDEX_SEPARATOR);
		int second = first < 0 ? -1 : member.indexOf(INDEX_SEPARATOR, first + 1);
		if (first <= 0 || second <= first + 1) {
			return null;
		}
		String runId = member.substring(second + 1);
		if (runId.isBlank()) {
			return null;
		}
		try {
			long tenantId = Long.parseLong(member.substring(0, first));
			long userId = Long.parseLong(member.substring(first + 1, second));
			if (tenantId <= 0L || (userId <= 0L && userId != TenantUser.SYSTEM_USER_ID)) {
				return null;
			}
			return new IndexRef(tenantId, userId, runId);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * 从带 tag 的 run 级键反解身份与 runId。
	 *
	 * <p>只认新键形 {@code <prefix>{<tenantId>:<userId>:run:<runId>}}；旧裸键
	 * （{@code ai:lease:run:<runId>}）与 legacy tag 没有身份，返回 {@code null}。
	 */
	public static RunRef parseScopedKey(String prefix, String key) {
		if (key == null || prefix == null || !key.startsWith(prefix)) {
			return null;
		}
		String tail = key.substring(prefix.length());
		if (!tail.startsWith("{") || !tail.endsWith("}")) {
			return null;
		}
		String inner = tail.substring(1, tail.length() - 1);
		int runMark = inner.indexOf(RUN_MARK);
		if (runMark <= 0) {
			return null;
		}
		String identity = inner.substring(0, runMark);
		if (!identity.endsWith(":")) {
			return null;
		}
		String runId = inner.substring(runMark + RUN_MARK.length());
		if (runId.isEmpty()) {
			return null;
		}
		String[] parts = identity.substring(0, identity.length() - 1).split(":", 2);
		if (parts.length != 2) {
			return null;
		}
		try {
			long tenantId = Long.parseLong(parts[0]);
			long userId = Long.parseLong(parts[1]);
			if (tenantId <= 0L || (userId <= 0L && userId != TenantUser.SYSTEM_USER_ID)) {
				return null;
			}
			return new RunRef(tenantId, userId, runId);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/** 解析出的 run 级键归属。 */
	public record RunRef(long tenantId, long userId, String runId) {

		public TenantUser identity() {
			return new TenantUser(this.tenantId, this.userId);
		}

	}

	/** 解析出的全局索引成员归属。 */
	public record IndexRef(long tenantId, long userId, String runId) {

		public TenantUser identity() {
			return new TenantUser(this.tenantId, this.userId);
		}

		public String member() {
			return RunKeyScope.indexMember(this.tenantId, this.userId, this.runId);
		}

	}

}
