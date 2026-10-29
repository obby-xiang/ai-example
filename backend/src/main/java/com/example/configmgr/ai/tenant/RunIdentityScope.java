package com.example.configmgr.ai.tenant;

/**
 * 按<b>记录身份</b>建立可关闭的作用域（恢复型异步入口的唯一入口点）。
 *
 * <h2>为什么需要它而不是直接调 {@link RequestContextUtils#useContext(long, long)}</h2>
 * 恢复型入口（启动/运行期扫描、续跑、取消收尾、确认门重试、看门狗、SSE 投递、官方工具线程）
 * 拿到的是一份 {@code RunSnapshot} 或执行信封，身份来自<b>那条记录</b>而不是当前线程：
 * <ul>
 * <li>定时线程上没有请求身份 —— 直接 {@code useContext} 会因"没有值"而无从恢复；</li>
 * <li>池化线程上可能残留上一位调用者的身份 —— 不覆盖就会串身份；</li>
 * <li>旧快照（{@code tenantId=0} / {@code userId=0}，本字段引入前写入）<b>没有身份可恢复</b>：
 *     此时返回空作用域，<b>不伪造默认租户/用户</b>。真正需要身份的下游读取仍按 fail-closed 抛错。</li>
 * </ul>
 *
 * <p>作用域只能在建立它的线程关闭（同 {@link RequestContextUtils.SafeCloseable}）；
 * 跨线程的任务必须在执行线程里用同一份 {@code tenantId/userId} 重新 {@link #open(long, long)}，
 * 不能把句柄传过去关闭。
 */
public final class RunIdentityScope {

	private static final Scope NONE = () -> {
	};

	private RunIdentityScope() {
	}

	/** 可关闭的作用域（{@code close()} 不抛受检异常，可直接用于 try-with-resources）。 */
	@FunctionalInterface
	public interface Scope extends AutoCloseable {

		@Override
		void close();

	}

	/**
	 * 按记录里的身份建立作用域；记录缺失或身份不可用时返回空作用域（{@link #NONE}）。
	 *
	 * @param tenantId 记录里的租户（{@code <=0} = 无身份/旧记录）
	 * @param userId   记录里的用户（{@code 0} = 无身份/旧记录；{@code -1} = 系统账号）
	 */
	public static Scope open(long tenantId, long userId) {
		if (!usable(tenantId, userId)) {
			return NONE;
		}
		RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(tenantId, userId);
		return scope::close;
	}

	/** 按已解析的身份建立作用域；{@code null} = 空作用域。 */
	public static Scope open(TenantUser identity) {
		if (identity == null) {
			return NONE;
		}
		return open(identity.tenantId(), identity.userId());
	}

	/**
	 * 记录身份是否可用于建立作用域。
	 *
	 * <p>{@code userId=-1}（系统账号）可用；{@code 0} 与负于 -1 的值都视为"无身份"，
	 * 与 {@link TenantUser} 的构造约束一致 —— 但这里<b>不抛异常</b>：旧快照是合法数据，
	 * 只是没有身份。
	 */
	public static boolean usable(long tenantId, long userId) {
		return tenantId > 0L && (userId > 0L || userId == TenantUser.SYSTEM_USER_ID);
	}

}
