package com.example.configmgr.ai.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RunIdentityScope} 的三条语义（恢复型入口的身份基座）。
 *
 * <ol>
 * <li><b>嵌套还原</b>：外层（请求/交棒身份）→ 内层（记录身份）→ 关闭内层必须回到外层，
 *     关闭外层才回到进入前状态。若实现退化成"关闭即清空"，内层关闭后外层身份就丢了 ——
 *     之后所有按身份定键的操作都会落到 legacy 命名空间（静默的键分裂）；</li>
 * <li><b>0/0 = 显式无身份</b>：记录没有身份时<b>清空</b>线程身份。池化/定时线程上残留的调用者身份
 *     若被沿用，下游所有按身份定键的读写都会以<b>别人</b>的身份执行（"不伪造默认租户"的另一方面）；</li>
 * <li><b>系统账号（-1）可用</b>：系统恢复/定时任务的身份是合法的，不是"无身份"。</li>
 * </ol>
 */
class RunIdentityScopeTest {

	@Test
	void nestedScopesRestoreTheOuterIdentityInsteadOfClearingIt() {
		try (RequestContextUtils.SafeCloseable outer = RequestContextUtils.useContext(1L, 101L)) {
			try (RunIdentityScope.Scope inner = RunIdentityScope.open(2L, 202L)) {
				assertThat(RequestContextUtils.currentOrNull()).isEqualTo(new TenantUser(2L, 202L));
			}
			assertThat(RequestContextUtils.currentOrNull())
				.as("关闭内层必须回到外层身份（而不是清空）").isEqualTo(new TenantUser(1L, 101L));
		}
		assertThat(RequestContextUtils.currentOrNull()).as("关闭外层才回到进入前（无身份）").isNull();
	}

	@Test
	void zeroIdentityClearsTheCallersIdentityAndRestoresItOnClose() {
		try (RequestContextUtils.SafeCloseable outer = RequestContextUtils.useContext(3L, 303L)) {
			try (RunIdentityScope.Scope none = RunIdentityScope.open(0L, 0L)) {
				assertThat(RequestContextUtils.currentOrNull())
					.as("0/0 记录 ⇒ 显式无身份（不得沿用调用者身份）").isNull();
			}
			assertThat(RequestContextUtils.currentOrNull()).as("关闭后还原调用者身份")
				.isEqualTo(new TenantUser(3L, 303L));
		}
	}

	@Test
	void systemAccountIdentityIsUsableAndNestedCleanupLeavesNoResidue() {
		try (RunIdentityScope.Scope system = RunIdentityScope.open(5L, TenantUser.SYSTEM_USER_ID)) {
			assertThat(RequestContextUtils.currentOrNull())
				.as("系统账号（userId=-1）是合法身份").isEqualTo(new TenantUser(5L, TenantUser.SYSTEM_USER_ID));
		}
		assertThat(RequestContextUtils.currentOrNull()).as("作用域结束不得残留").isNull();
		assertThat(RunIdentityScope.usable(5L, TenantUser.SYSTEM_USER_ID)).isTrue();
		assertThat(RunIdentityScope.usable(0L, 7L)).as("tenantId=0 ⇒ 无身份").isFalse();
		assertThat(RunIdentityScope.usable(9L, 0L)).as("userId=0 ⇒ 无身份").isFalse();
		assertThat(RunIdentityScope.usable(9L, -2L)).as("userId<-1 ⇒ 无身份").isFalse();
	}

}
