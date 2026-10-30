package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>正常释放不得因"当拍身份 ≠ acquire 身份"而永久残留</b>。
 *
 * <h2>被钉住的缺陷</h2>
 * {@link SessionGate#release(String, String, String)} 只按<b>当前线程身份</b>算锁键去进程内表里找
 * holder，且"按记录兜底"只对<b>无身份</b>线程生效。于是：
 * <ul>
 * <li>身份 {@code 1/101} 下取到门（holder 记在 {@code ai:session:lock:1:101:<sid>} 桶里）；</li>
 * <li>这一轮终态的 finally 恰好跑在<b>另一个身份</b>的线程上（异步收尾/池化线程带着别的上下文），
 *     释放按那个身份算键 ⇒ 找不到 holder，兜底也不触发；</li>
 * <li>holder 与它的 watchdog 永久留在进程内表：Redis 锁被一直续期，后续同会话请求永远 409
 *     （只能重启进程）。</li>
 * </ul>
 *
 * <h2>修法口径</h2>
 * 直接桶没命中时，一律按<b>完整持有记录</b>定位 holder：sessionId + runId + token
 * （token 是每次 acquire 的一次性值，只属于那一轮）<b>+ 记录里的身份</b>（它必须能拼出该 holder
 * 的锁键，即记录自洽），且只有<b>唯一命中</b>才释放。释放落在<b>持有者的</b>锁键上 ——
 * 既能收回那条泄漏的门，又绝不会误碰别的身份正持有的大门或锁。
 */
class SessionGateReleaseIdentityTest {

	private static final String SESSION = "s-release-identity";

	private static final String RUN = "run-release-identity";

	private static final String TOKEN = "token-release-identity";

	private static final String OTHER_RUN = "run-other-identity";

	private static final String OTHER_TOKEN = "token-other-identity";

	private static final long TENANT = 1L;

	private static final long USER = 101L;

	private static final long OTHER_TENANT = 2L;

	private static final long OTHER_USER = 202L;

	private final SessionGateLeaseTokenTest.FakeSessionRedis fake = new SessionGateLeaseTokenTest.FakeSessionRedis();

	private final AiProperties properties = new AiProperties();

	private final SessionGate gate = new SessionGate(this.fake.template, this.properties);

	@Test
	void releaseUnderADifferentIdentityFreesTheHolderWithoutTouchingThatIdentitysOwnGate() {
		String holderKey = SessionGate.gateKey(SESSION, TENANT, USER);
		String otherKey = SessionGate.gateKey(SESSION, OTHER_TENANT, OTHER_USER);
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT, USER)) {
			assertThat(this.gate.acquire(SESSION, RUN, TOKEN)).as("本身份取门").isNull();
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(OTHER_TENANT, OTHER_USER)) {
			assertThat(this.gate.acquire(SESSION, OTHER_RUN, OTHER_TOKEN))
				.as("另一身份在自己的桶里也有一轮（同 sessionId 不是同一把门）")
				.isNull();
		}
		assertThat(this.gate.heldSessions()).isEqualTo(2);

		// 那条 run 的终态 finally 落在带着"别身份"上下文的线程上
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(OTHER_TENANT, OTHER_USER)) {
			assertThat(this.gate.release(SESSION, RUN, TOKEN)).as("按完整持有记录精确匹配后仍然释放").isTrue();
		}

		assertThat(this.gate.heldSessions()).as("泄漏的进程内门必须被摘掉（不得永久 409）").isEqualTo(1);
		assertThat(this.fake.value(holderKey)).as("按持有者自己的身份键释放它的锁").isNull();
		assertThat(this.fake.value(otherKey)).as("另一身份自己的锁一个字节都不动")
			.isEqualTo(OTHER_RUN + "|" + OTHER_TOKEN);

		// 同会话在原身份下可以重新取门 —— 这就是"不永久残留"的对外事实
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT, USER)) {
			assertThat(this.gate.acquire(SESSION, "run-next", "token-next")).as("门已收回，可开下一轮").isNull();
		}
	}

	/**
	 * 同一 {@code (sessionId, runId, token)} 出现在<b>两个身份桶</b>里（正常调用下不可能是同一次
	 * acquire），按记录兜底必须拒绝猜：宁可让它们各自按身份释放，也不跨身份误摘。
	 */
	@Test
	void releaseRefusesToGuessWhenTheSameTripleExistsInTwoIdentityBuckets() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT, USER)) {
			assertThat(this.gate.acquire(SESSION, RUN, TOKEN)).isNull();
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(OTHER_TENANT, OTHER_USER)) {
			assertThat(this.gate.acquire(SESSION, RUN, TOKEN)).isNull();
		}
		assertThat(RequestContextUtils.currentOrNull()).as("前置：当前线程无身份（legacy 视图）").isNull();

		assertThat(this.gate.release(SESSION, RUN, TOKEN)).as("三元组命中两条记录 ⇒ 不猜、不释放").isFalse();

		assertThat(this.gate.heldSessions()).isEqualTo(2);
		assertThat(this.fake.value(SessionGate.gateKey(SESSION, TENANT, USER))).isNotNull();
		assertThat(this.fake.value(SessionGate.gateKey(SESSION, OTHER_TENANT, OTHER_USER))).isNotNull();
	}

}
