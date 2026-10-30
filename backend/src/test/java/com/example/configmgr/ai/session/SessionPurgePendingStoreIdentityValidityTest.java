package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>台账里的"坏身份"只能是 legacy，不能把整条恢复链打断</b>。
 *
 * <h2>被钉住的缺陷</h2>
 * {@link TenantKeyScope#hasIdentity(Long, Long)} 旧口径是"租户正数 + 用户非 0"，于是
 * {@code userId=-5} 这类<b>非法负值</b>被当成"有身份"。下游 {@code Pending#identityOrNull()}
 * 会据此 {@code new TenantUser(tenantId, -5)}，而 {@link TenantUser}（与
 * {@code RunIdentityScope.usable}）只接受正数或系统账号 {@code -1} ⇒ 构造即抛
 * {@link IllegalArgumentException}。位置在 {@code recoverOrphans} 的循环体里：
 * SCAN 兜底<b>整批中断</b>，排在后面的合法台账一条都回填不了，守卫/重试链一起停摆。
 *
 * <h2>本用例口径</h2>
 * 一条坏台账（{@code 1:-5}）+ 一条合法台账（{@code 2:201}）同时存在时：SCAN 兜底必须把两条
 * 都回填、{@code listDue} 必须把两条都读出来，坏值只按"无身份"处理（不当成任何新身份）。
 */
class SessionPurgePendingStoreIdentityValidityTest {

	private static final long NOW_MS = 1_000L;

	private static final String BAD_SESSION = "s-bad-negative";

	private static final String GOOD_SESSION = "s-good-identity";

	private final PurgeFakeRedis fake = new PurgeFakeRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final SessionPurgePendingStore store = new SessionPurgePendingStore(this.fake.redis, this.mapper,
			new AiProperties());

	@Test
	void anIllegalNegativeUserIdIsTreatedAsNoIdentityAndDoesNotBlockLaterLegitimateLedgers() throws Exception {
		// 坏台账：userId=-5（TenantUser 拒绝，-1 才是系统账号）。直接写键面 —— 产品代码永远不会写出这种记录。
		String badKey = TenantKeyScope.scoped(SessionPurgePendingStore.PENDING_PREFIX, 1L, -5L, BAD_SESSION);
		this.fake.strings.put(badKey, this.mapper.writeValueAsString(new SessionPurgePendingStore.Pending(BAD_SESSION,
				"purge-bad", "tester", 0, NOW_MS, NOW_MS, null, null, 1L, -5L)));
		// 合法台账：走正常登记路径（身份 2:201）。
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(2L, 201L)) {
			this.store.register(GOOD_SESSION, "purge-good", "tester", NOW_MS);
		}
		// 队列丢失 ⇒ 只能靠 SCAN 兜底发现（坏台账排在发现面里）。
		this.fake.lists.remove(SessionPurgePendingStore.QUEUE_KEY);

		int recovered = this.store.recoverOrphans(20);

		assertThat(recovered).as("坏身份台账不得打断 SCAN 兜底：两条都要回填").isEqualTo(2);
		assertThat(this.store.listDue(NOW_MS, 10)).extracting(SessionPurgePendingStore.Pending::sessionId)
			.as("合法台账必须照常进入待重试集合")
			.containsExactlyInAnyOrder(BAD_SESSION, GOOD_SESSION);
		// 合同本身：只接受正数或系统账号 -1（与 TenantUser / RunIdentityScope 同一口径）。
		assertThat(TenantKeyScope.hasIdentity(1L, -5L)).as("userId=-5 不是可用身份").isFalse();
		assertThat(TenantKeyScope.hasIdentity(1L, -2L)).as("userId<-1 一律不可用").isFalse();
		assertThat(TenantKeyScope.hasIdentity(1L, 0L)).as("userId=0 = 旧记录").isFalse();
		assertThat(TenantKeyScope.hasIdentity(1L, TenantUser.SYSTEM_USER_ID)).as("系统账号 -1 可用").isTrue();
		assertThat(TenantKeyScope.hasIdentity(1L, 201L)).as("正数用户可用").isTrue();
	}

}
