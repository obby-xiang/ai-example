package com.example.configmgr.ai.tenant;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * AI 对话域的<b>租户/用户身份上下文</b>（线程级，可跨线程传播）。
 *
 * <h2>为什么用 {@link TransmittableThreadLocal}</h2>
 * 身份在 HTTP 边界建立，但一轮对话的执行体跑在平台线程池上（挂起池 / 续跑触发池 / 定时器）。
 * 普通 {@code ThreadLocal} 过不了池边界，{@code InheritableThreadLocal} 只在创建线程时复制一次，
 * 池化线程会带着第一次捕获的身份服务后续所有请求 —— 串身份。TTL 允许提交侧抓快照、执行侧回放，
 * 是后续统一执行器装饰（{@code TtlRunnable}/{@code TtlExecutors}）的同一份基座。
 *
 * <h2>作用域语义</h2>
 * {@link #useContext(long, long)} 返回 {@link SafeCloseable}，配合 try-with-resources：
 * <ul>
 * <li><b>嵌套还原</b>：关闭时恢复到进入前的身份（而不是清空）；</li>
 * <li><b>无旧值清理</b>：进入前没有身份时，关闭必须<b>清空</b>线程绑定 —— 池化线程上残留身份
 * 会让下一位使用者以别人的身份执行；</li>
 * <li><b>幂等关闭</b>：重复关闭是空操作，不得第二次回写"旧值"（那会覆盖后来新开的作用域）；</li>
 * <li><b>跨线程关闭拒绝</b>：作用域只能由开它的线程关闭；别的线程关它直接失败且不改动。</li>
 * </ul>
 *
 * <h2>fail-closed</h2>
 * {@link #getTenantId()}/{@link #getUserId()} 在没有上下文时抛 {@link IllegalStateException}。
 * <b>没有默认租户，也没有默认用户</b> —— "取不到就当默认值"是跨身份读写的最短路径。
 */
public final class RequestContextUtils {

	/** 系统账号的 userId（仅系统任务；HTTP 边界拒绝）。 */
	public static final long SYSTEM_USER_ID = TenantUser.SYSTEM_USER_ID;

	private static final TransmittableThreadLocal<TenantUser> HOLDER = new TransmittableThreadLocal<>();

	private RequestContextUtils() {
	}

	/** 当前身份；缺失即失败（不回落默认身份）。 */
	public static TenantUser require() {
		TenantUser identity = HOLDER.get();
		if (identity == null) {
			throw new IllegalStateException("缺少租户/用户身份上下文：该执行路径必须先通过 HTTP 身份头或 useContext 建立身份");
		}
		return identity;
	}

	/** 当前身份；缺失返回 {@code null}（只供"可选登记"类调用方，业务判定必须用 {@link #require()}）。 */
	public static TenantUser currentOrNull() {
		return HOLDER.get();
	}

	public static long getTenantId() {
		return require().tenantId();
	}

	public static long getUserId() {
		return require().userId();
	}

	/** 当前是否为系统账号；没有上下文时为 {@code false}（不假称系统身份）。 */
	public static boolean isSystemUser() {
		TenantUser identity = HOLDER.get();
		return identity != null && identity.isSystemUser();
	}

	/**
	 * 建立身份作用域（必须在同一线程关闭）。
	 *
	 * @param tenantId 租户 id（正数）
	 * @param userId   用户 id（正数；{@code -1} = 系统账号）
	 */
	public static SafeCloseable useContext(long tenantId, long userId) {
		TenantUser identity = new TenantUser(tenantId, userId);
		TenantUser previous = HOLDER.get();
		HOLDER.set(identity);
		return new SafeCloseable(previous);
	}

	/**
	 * 作用域句柄：关闭即恢复进入前的身份（无旧值则清空）。
	 *
	 * <p>
	 * 实现 {@link AutoCloseable} 且 {@link #close()} 不抛受检异常，因此可直接用于
	 * try-with-resources。
	 */
	public static final class SafeCloseable implements AutoCloseable {

		private final TenantUser previous;

		private final long ownerThreadId;

		private volatile boolean closed;

		private SafeCloseable(TenantUser previous) {
			this.previous = previous;
			this.ownerThreadId = Thread.currentThread().threadId();
		}

		@Override
		public void close() {
			if (this.closed) {
				return;
			}
			if (Thread.currentThread().threadId() != this.ownerThreadId) {
				throw new IllegalStateException("身份作用域只能由建立它的线程关闭：owner=" + this.ownerThreadId + " current="
						+ Thread.currentThread().threadId());
			}
			// 先置位再回写：重复关闭不得第二次回写“旧值”（会覆盖后来新开的作用域）。
			this.closed = true;
			if (this.previous == null) {
				HOLDER.remove();
			}
			else {
				HOLDER.set(this.previous);
			}
		}

	}

}
