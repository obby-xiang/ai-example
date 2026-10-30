package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>判死强释放不得跨身份</b>。
 *
 * <h2>被钉住的缺陷</h2>
 * {@link SessionGate#releaseDeadHeld(String, String)} 在"当前线程没有身份"时会按记录
 * （{@code sessionId + runId}）在进程内表里找持有者 —— 但旧实现<b>不看身份</b>。于是：
 * <ul>
 * <li>身份 {@code 9001/7} 的持有一轮挂在 {@code ai:session:lock:9001:7:<sid>} 上；</li>
 * <li>无身份（legacy 视图）的判死路径用同一个 {@code sessionId + runId} 找到了它，
 *     摘掉进程内门、取消 watchdog，并把锁键换成对方那把 ⇒ 脚本把<b>别的身份</b>的会话锁删掉。</li>
 * </ul>
 * 后果是同 sessionId/runId 跨身份误释放：那一轮在"无锁"状态下继续跑，同会话可并发第二轮。
 * 修法 = 匹配持有者时同时核对身份（{@code null} 只匹配 legacy 持有者）。
 */
class SessionGateDeadHolderIdentityTest {

	private static final String SESSION_ID = "s-dead-holder-identity";

	private static final String RUN_ID = "run-dead-holder-identity";

	private static final TenantUser HOLDER_IDENTITY = new TenantUser(9001L, 7L);

	private final SessionGateLeaseTokenTest.FakeSessionRedis fake = new SessionGateLeaseTokenTest.FakeSessionRedis();

	private final AiProperties properties = new AiProperties();

	private final SessionGate gate = new SessionGate(this.fake.template, this.properties);

	@Test
	void deadReleaseWithoutIdentityDoesNotTouchAnotherIdentitysHolder() {
		String holderLockKey = SessionGate.gateKey(SESSION_ID, HOLDER_IDENTITY.tenantId(),
				HOLDER_IDENTITY.userId());
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(
				HOLDER_IDENTITY.tenantId(), HOLDER_IDENTITY.userId())) {
			assertThat(this.gate.acquire(SESSION_ID, RUN_ID)).as("该身份下取门成功").isNull();
		}
		assertThat(this.fake.value(holderLockKey)).as("前置：锁落在带身份的键上").isNotNull();
		assertThat(this.gate.heldSessions()).isEqualTo(1);

		// 无身份（legacy 视图）的判死强释放：sessionId/runId 相同，但持有者属于另一个身份。
		assertThat(RequestContextUtils.currentOrNull()).isNull();
		this.gate.releaseDeadHeld(SESSION_ID, RUN_ID);

		assertThat(this.gate.heldSessions()).as("不得摘掉别的身份的进程内门（也不得取消它的 watchdog）")
			.isEqualTo(1);
		assertThat(this.fake.value(holderLockKey)).as("不得删掉别的身份的会话锁").isNotNull();

		// 相邻对照：同身份下的判死强释放仍然有效（修复不得把这条路径一起关掉）。
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(
				HOLDER_IDENTITY.tenantId(), HOLDER_IDENTITY.userId())) {
			this.gate.releaseDeadHeld(SESSION_ID, RUN_ID);
		}
		assertThat(this.gate.heldSessions()).isZero();
		assertThat(this.fake.value(holderLockKey)).isNull();
	}

}
