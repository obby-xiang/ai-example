package com.example.configmgr.ai.tenant;

import java.util.List;

/**
 * 预置的租户/用户目录（极少量演示身份）—— 身份组合校验的唯一事实源。
 *
 * <h2>为什么要有目录而不是"任意正数都收"</h2>
 * 身份头由客户端携带。若只做"是正数"的格式校验，任何人都能自称 tenant=2/user=999 并读写
 * 一个从未登记过的身份：隔离形同虚设，且事后无法判断某份数据是否真有归属。这里维护一份
 * <b>显式登记</b>的组合清单，HTTP 边界只放行清单内的组合。
 *
 * <p>
 * 目录只用于<b>校验</b>与前端选项，<b>不得</b>成为"遍历租户找任务/恢复执行"的来源
 * （恢复一律按记录里固定的身份，见设计稿）。
 */
public final class TenantUserDirectory {

	/** 用户选项（前端身份切换用）。 */
	public record UserView(long userId, String userName) {
	}

	/** 租户选项（含该租户下的用户）。 */
	public record TenantView(long tenantId, String tenantName, List<UserView> users) {

		public TenantView {
			users = List.copyOf(users);
		}

	}

	private static final List<TenantView> TENANTS = List.of(
			new TenantView(1L, "租户一", List.of(new UserView(101L, "用户101"), new UserView(102L, "用户102"))),
			new TenantView(2L, "租户二", List.of(new UserView(201L, "用户201"), new UserView(202L, "用户202"))));

	private TenantUserDirectory() {
	}

	/** 全部预置租户（只读；供选项接口）。 */
	public static List<TenantView> tenants() {
		return TENANTS;
	}

	/** 该 (tenantId, userId) 组合是否已登记。 */
	public static boolean exists(long tenantId, long userId) {
		for (TenantView tenant : TENANTS) {
			if (tenant.tenantId() != tenantId) {
				continue;
			}
			for (UserView user : tenant.users()) {
				if (user.userId() == userId) {
					return true;
				}
			}
			return false;
		}
		return false;
	}

	/**
	 * 校验并返回身份。
	 *
	 * @throws IllegalArgumentException 组合未登记（跨租户拼装同样是未登记）
	 */
	public static TenantUser require(long tenantId, long userId) {
		if (!exists(tenantId, userId)) {
			throw new IllegalArgumentException("未登记的租户/用户组合：tenantId=" + tenantId + " userId=" + userId);
		}
		return new TenantUser(tenantId, userId);
	}

}
