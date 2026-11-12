package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 共享锁失败关闭后的恢复：故障不建立本地 holder，恢复后获取与重入均保持非降级。 */
class SessionGateDegradedResetTest {

	private static final String SESSION = "s-degraded-reset";

	private static final String RUN_ID = "run-degraded-reset";

	private final SessionGateLeaseTokenTest.FakeSessionRedis fake = new SessionGateLeaseTokenTest.FakeSessionRedis();

	private final SessionGate gate = new SessionGate(this.fake.template, new AiProperties());

	@Test
	void aConfirmedReacquireClearsTheDegradedFlag() {
		try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
				com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
			this.fake.failScripts = true;
			org.assertj.core.api.Assertions.assertThatThrownBy(
					() -> this.gate.acquire(SESSION, RUN_ID, "token-degraded"))
				.isInstanceOf(com.example.configmgr.common.AiStateUnavailableException.class);
			assertThat(this.gate.heldSessions()).isZero();
			assertThat(this.gate.degradedSessions()).isZero();

			this.fake.failScripts = false;
			assertThat(this.gate.acquire(SESSION, RUN_ID, "token-confirmed")).isNull();
			assertThat(this.gate.acquire(SESSION, RUN_ID, "token-reentrant")).isNull();
			assertThat(this.gate.degradedSessions()).as("恢复并确认锁后不得伪报降级").isZero();

			assertThat(this.gate.release(SESSION, RUN_ID, "token-reentrant")).isTrue();
		}
	}

}
