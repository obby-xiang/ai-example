package com.example.configmgr.ai.tenant;

import com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 身份上下文（{@link RequestContextUtils}）的单点契约。
 *
 * <p>
 * 三条硬口径（缺一即串身份）：
 * <ul>
 * <li><b>嵌套还原</b>：内层 {@code useContext} 关闭后必须回到外层身份；最外层关闭后必须
 * <b>清空</b>而不是留下任何值（"无旧值清理"）—— 线程池复用线程时残留身份比没有身份更坏；</li>
 * <li><b>跨线程关闭拒绝</b>：作用域只能由开它的线程关闭，别的线程关它必须失败且不改动当前线程；</li>
 * <li><b>缺失 fail-closed</b>：没有上下文时读取身份抛异常，绝不给默认租户/默认用户。</li>
 * </ul>
 */
class RequestContextUtilsTest {

	@Test
	void nestedUseContextRestoresOuterIdentityAndClearsAfterOutermost() {
		try (SafeCloseable outer = RequestContextUtils.useContext(1L, 101L)) {
			assertThat(RequestContextUtils.getTenantId()).isEqualTo(1L);
			assertThat(RequestContextUtils.getUserId()).isEqualTo(101L);

			try (SafeCloseable inner = RequestContextUtils.useContext(2L, 201L)) {
				assertThat(RequestContextUtils.getTenantId()).isEqualTo(2L);
				assertThat(RequestContextUtils.getUserId()).isEqualTo(201L);
			}

			assertThat(RequestContextUtils.getTenantId()).as("内层关闭后回到外层身份").isEqualTo(1L);
			assertThat(RequestContextUtils.getUserId()).isEqualTo(101L);
		}

		assertThatThrownBy(RequestContextUtils::getTenantId)
			.as("最外层关闭后必须清空，不得留下刚关闭的身份")
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(RequestContextUtils::getUserId).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void closeIsIdempotentAndNeverTouchesAnotherScope() {
		SafeCloseable stale = RequestContextUtils.useContext(1L, 101L);
		stale.close();

		try (SafeCloseable fresh = RequestContextUtils.useContext(2L, 201L)) {
			stale.close();

			assertThat(RequestContextUtils.getTenantId())
				.as("已关闭的作用域再次关闭必须是空操作，不得清掉后来开的身份")
				.isEqualTo(2L);
			assertThat(RequestContextUtils.getUserId()).isEqualTo(201L);
		}

		assertThatThrownBy(RequestContextUtils::getTenantId).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void closeFromAnotherThreadIsRejected() throws Exception {
		SafeCloseable scope = RequestContextUtils.useContext(1L, 101L);
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		Thread other = new Thread(() -> {
			try {
				scope.close();
			}
			catch (Throwable ex) {
				thrown.set(ex);
			}
		});
		other.start();
		other.join(5_000L);

		assertThat(thrown.get()).as("作用域只能在开它的线程关闭").isInstanceOf(IllegalStateException.class);
		assertThat(RequestContextUtils.getTenantId()).as("跨线程关闭被拒后当前身份不得被改动").isEqualTo(1L);
		assertThat(RequestContextUtils.getUserId()).isEqualTo(101L);
		scope.close();
		assertThatThrownBy(RequestContextUtils::getUserId).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void systemUserIsTheOnlyNonPositiveUserAllowed() {
		try (SafeCloseable ignored = RequestContextUtils.useContext(1L, RequestContextUtils.SYSTEM_USER_ID)) {
			assertThat(RequestContextUtils.getUserId()).isEqualTo(-1L);
			assertThat(RequestContextUtils.isSystemUser()).isTrue();
		}
		assertThatThrownBy(() -> RequestContextUtils.useContext(1L, 0L))
			.as("userId=0 不是系统账号")
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RequestContextUtils.useContext(0L, 101L))
			.as("tenantId 必须是正数")
			.isInstanceOf(IllegalArgumentException.class);
	}

}
