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
 *
 * <h2>0/0（旧记录）＝ 显式无身份，不是"沿用调用者"</h2>
 * 记录没有身份时本类<b>清空</b>线程身份并返回可关闭句柄（关闭即还原）：
 * 池化/定时线程上残留的身份若被沿用，下游所有按身份定键的读写都会以<b>别人</b>的身份执行。
 * 这正是"不伪造默认租户"的反面要求 —— 不伪造 ≠ 沿用调用者。
 *
 * <h2>嵌套还原</h2>
 * 作用域可嵌套（外层 = 请求/交棒身份，内层 = 记录身份）：关闭内层必须回到外层身份，
 * 关闭外层才回到进入前的状态（无旧值则清空）。句柄关闭幂等。
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
	 * 按记录里的身份建立作用域。
	 *
	 * <p>身份<b>不可用</b>（{@code 0/0}、负值、缺字段）时不是"什么都不做"：清空线程身份并返回可关闭
	 * 句柄（关闭即还原进入前的身份）。语义 = <b>这条记录没有身份</b>，于是这一拍也不得以调用者身份执行。
	 *
	 * @param tenantId 记录里的租户（{@code <=0} = 无身份/旧记录）
	 * @param userId   记录里的用户（{@code 0} = 无身份/旧记录；{@code -1} = 系统账号）
	 */
	public static Scope open(long tenantId, long userId) {
		if (!usable(tenantId, userId)) {
			return denied();
		}
		RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(tenantId, userId);
		return scope::close;
	}

	/** 按已解析的身份建立作用域；{@code null} = 该记录没有身份（清空线程身份）。 */
	public static Scope open(TenantUser identity) {
		if (identity == null) {
			return denied();
		}
		return open(identity.tenantId(), identity.userId());
	}

	/**
	 * <b>显式无身份</b>作用域：清空线程绑定，关闭时还原。
	 *
	 * <p>当前线程本来就没有身份时返回无副作用的句柄（常见路径，不额外分配线程状态）。
	 */
	public static Scope denied() {
		if (RequestContextUtils.currentOrNull() == null) {
			return NONE;
		}
		RequestContextUtils.SafeCloseable scope = RequestContextUtils.clearContext();
		return scope::close;
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
