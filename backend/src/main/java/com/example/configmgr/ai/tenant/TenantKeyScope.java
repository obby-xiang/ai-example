package com.example.configmgr.ai.tenant;

/**
 * 会话域 Redis key 的<b>身份段</b>唯一拼接口（租户任务 2）。
 *
 * <h2>键形</h2>
 * {@code <prefix><tenantId>:<userId>:<suffix>}，例如：
 * <ul>
 * <li>{@code ai:session:1:101:<sessionId>}（会话记录）</li>
 * <li>{@code ai:session:lock:1:101:<sessionId>}（会话门）</li>
 * <li>{@code ai:purge:pending:1:101:<sessionId>}（销毁台账）</li>
 * <li>{@code ai:form:att:sess:1:101:<sessionId>}（附件会话索引）</li>
 * <li>{@code ai:rate:session:1:101:<sessionId>}（会话维度限流）</li>
 * </ul>
 * 与前端镜像/草稿键形同一口径：{@code ai-mirror:<t>:<u>:<sid>}、
 * {@code cm.ai.form.draft.<t>:<u>.<toolCallId>}。
 *
 * <h2>为什么 tenantId/userId 是定长十进制</h2>
 * 两个 id 都是 {@code long}，不含冒号 ⇒ 任意两个不同身份拼出的段必不相同；
 * 同一个 sessionId 因此在不同租户/用户下落在不同键上（跨租户相同 sessionId 天然隔离）。
 * 反过来也说明<b>身份段不能从键名反解</b>：sessionId 自身允许含冒号，
 * 需要身份时一律读记录里的 {@code tenantId/userId} 字段（见 {@code SessionPurgePendingStore}）。
 *
 * <h2>没有身份上下文时：legacy 命名空间，而不是"默认身份"</h2>
 * 本类<b>不</b>提供默认租户/默认用户：没有上下文时返回<b>不含身份段</b>的旧键形
 * （{@code ai:session:<sessionId>} 等）。这是给"尚未接线身份的过渡期路径"留的
 * <b>独立命名空间</b> —— 带着身份读写的新路径<b>永远不会</b>落到这些键上，旧数据也就不会
 * 被新身份读到（"无归属 legacy 不得进入新身份读路径"）。
 * 生产 HTTP 入口经 {@code TenantIdentityFilter} 必有身份；异步入口由后续任务逐项 {@code useContext}。
 */
public final class TenantKeyScope {

	private TenantKeyScope() {
	}

	/**
	 * 身份段（含结尾冒号）；无上下文返回 {@code ""}（= legacy 旧键形）。
	 */
	public static String currentSegment() {
		return segmentOf(RequestContextUtils.currentOrNull());
	}

	/**
	 * 给定身份的身份段（含结尾冒号）；{@code null} 返回 {@code ""}。
	 */
	public static String segmentOf(TenantUser identity) {
		return identity == null ? "" : identity.tenantId() + ":" + identity.userId() + ":";
	}

	/** 显式身份的身份段（{@code tenantId/userId} 由记录提供时用这个，不读线程上下文）。 */
	public static String segmentOf(long tenantId, long userId) {
		return tenantId + ":" + userId + ":";
	}

	/** 当前线程身份的键：{@code prefix + 身份段 + suffix}。 */
	public static String scoped(String prefix, String suffix) {
		return prefix + currentSegment() + suffix;
	}

	/** 显式身份的键：{@code prefix + <tenantId>:<userId>: + suffix}。 */
	public static String scoped(String prefix, long tenantId, long userId, String suffix) {
		return prefix + segmentOf(tenantId, userId) + suffix;
	}

	/**
	 * 记录里是否带<b>可用身份</b>（租户正数、用户为正数或系统账号 {@code -1}）。
	 *
	 * <p>判据与 {@link TenantUser} 的构造约束、{@link RunIdentityScope#usable(long, long)} 逐字同源：
	 * 只有 {@code > 0} 与 {@code -1}（系统账号）可用，其余负值都是<b>坏值</b>。
	 * {@code -1} 以外的负值若被当成身份，下游 {@code new TenantUser(...)} 会抛异常，
	 * 恢复循环（如 {@code SessionPurgePendingStore#recoverOrphans}）因此整批中断。
	 *
	 * <p>旧记录没有身份字段（反序列化为 {@code null}/{@code 0}）：它们是 legacy，不能冒充任何新身份。
	 */
	public static boolean hasIdentity(Long tenantId, Long userId) {
		return tenantId != null && userId != null && tenantId > 0
				&& (userId > 0 || userId == TenantUser.SYSTEM_USER_ID);
	}

}
