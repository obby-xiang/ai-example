package com.example.configmgr.ai.tenant;

/**
 * 会话域 Redis key 的<b>身份段</b>唯一拼接口。
 *
 * <h2>键形</h2>
 * {@code <prefix><tenantId>:<userId>:<suffix>}，例如：
 * <ul>
 * <li>{@code ai:session:1:101:<sessionId>}（会话记录）</li>
 * <li>{@code ai:session:lock:1:101:<sessionId>}（会话门）</li>
 * <li>{@code ai:purge:pending:1:101:<sessionId>}（销毁台账）</li>
 * <li>{@code ai:form:att:sess:1:101:<sessionId>}（附件会话索引）</li>
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
 * <h2>没有身份 = 失败，而不是另一个命名空间</h2>
 * 本类<b>不</b>提供默认租户/默认用户，也<b>不</b>生成不含身份段的键：没有上下文时
 * {@link #currentSegment()} 直接抛 {@link IllegalStateException}。身份缺失时继续拼键只会写出
 * 一套谁都读不到、销毁也删不掉的平行数据；唯一正确的姿态是让调用方在进入关键路径前建立身份
 * （HTTP 边界、记录恢复）或失败关闭。生产 HTTP 入口经 {@code TenantIdentityFilter} 必有身份；
 * 异步入口按记录身份 {@code useContext}。
 */
public final class TenantKeyScope {

	private TenantKeyScope() {
	}

	/**
	 * 当前线程身份段（含结尾冒号）。
	 *
	 * @throws IllegalStateException 没有身份上下文（fail-closed，不生成无身份键）
	 */
	public static String currentSegment() {
		return segmentOf(RequestContextUtils.require());
	}

	/**
	 * 给定身份的身份段（含结尾冒号）。
	 *
	 * @throws IllegalArgumentException 身份为空或不可用
	 */
	public static String segmentOf(TenantUser identity) {
		if (identity == null) {
			throw new IllegalArgumentException("Tenant/user identity is required; identity-less keys are forbidden");
		}
		return segmentOf(identity.tenantId(), identity.userId());
	}

	/**
	 * 显式身份的身份段（{@code tenantId/userId} 由记录提供时用这个，不读线程上下文）。
	 *
	 * @throws IllegalArgumentException 身份不可用（租户非正、用户非正且不是系统账号）
	 */
	public static String segmentOf(long tenantId, long userId) {
		if (!hasIdentity(tenantId, userId)) {
			throw new IllegalArgumentException(
					"Invalid tenant/user identity (tenantId > 0 and userId > 0 or system -1): " + tenantId + "/" + userId);
		}
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
	 */
	public static boolean hasIdentity(Long tenantId, Long userId) {
		return tenantId != null && userId != null && tenantId > 0
				&& (userId > 0 || userId == TenantUser.SYSTEM_USER_ID);
	}

}
