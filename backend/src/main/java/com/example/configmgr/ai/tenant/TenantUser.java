package com.example.configmgr.ai.tenant;

/**
 * 一次 AI 对话的身份（租户 + 用户），不可变。
 *
 * <p>
 * <b>为什么不是两个独立的 ThreadLocal</b>：拆分存放时任何一次只改其一的调用都会造出
 * "租户 A 的用户 B"这种从未被校验过的组合。组合校验只在一个地方（HTTP 边界 / 目录）发生，
 * 上下文里携带的必须是已经通过校验的那一对。
 *
 * <p>
 * {@code userId = -1} 是<b>系统账号</b>（定时任务、启动恢复等无 HTTP 请求的执行体），
 * HTTP 请求一律不得使用。
 */
public record TenantUser(long tenantId, long userId) {

	/** 系统账号的用户 id（仅系统任务；HTTP 禁用）。 */
	public static final long SYSTEM_USER_ID = -1L;

	public TenantUser {
		if (tenantId <= 0L) {
			throw new IllegalArgumentException("tenantId 必须是正数：" + tenantId);
		}
		if (userId <= 0L && userId != SYSTEM_USER_ID) {
			throw new IllegalArgumentException("userId 必须是正数或系统账号 -1：" + userId);
		}
	}

	public boolean isSystemUser() {
		return this.userId == SYSTEM_USER_ID;
	}

}
