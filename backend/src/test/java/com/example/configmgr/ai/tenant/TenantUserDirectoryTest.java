package com.example.configmgr.ai.tenant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户用户目录（{@link TenantUserDirectory}）的身份校验单点。
 *
 * <p>
 * 目录是"哪些 (tenantId,userId) 组合真实存在"的唯一事实源：HTTP 边界与系统任务都只认这里。
 * 校验必须<b>组合成立</b>才算通过 —— 租户存在、用户存在但组合不成立（跨租户拼装）同样拒绝。
 */
class TenantUserDirectoryTest {

	@Test
	void validatesTheTenantUserCombinationNotJustTheTwoNumbers() {
		assertThat(TenantUserDirectory.exists(1L, 101L)).as("tenant1/user101 预置").isTrue();
		assertThat(TenantUserDirectory.exists(2L, 202L)).as("tenant2/user202 预置").isTrue();

		assertThat(TenantUserDirectory.exists(1L, 201L))
			.as("tenant1 存在、user201 存在，但组合不成立 ⇒ 必须拒绝（防跨租户拼装）")
			.isFalse();
		assertThatThrownBy(() -> TenantUserDirectory.require(1L, 201L)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> TenantUserDirectory.require(9L, 101L)).isInstanceOf(IllegalArgumentException.class);
		assertThat(TenantUserDirectory.require(1L, 102L)).isEqualTo(new TenantUser(1L, 102L));

		List<TenantUserDirectory.TenantView> tenants = TenantUserDirectory.tenants();
		assertThat(tenants).hasSize(2);
		assertThat(tenants.get(0).tenantId()).isEqualTo(1L);
		assertThat(tenants.get(0).users()).extracting(TenantUserDirectory.UserView::userId)
			.containsExactly(101L, 102L);
		assertThat(tenants.get(1).users()).extracting(TenantUserDirectory.UserView::userId)
			.containsExactly(201L, 202L);
	}

}
