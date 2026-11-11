package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>台账里的"坏身份"只能按无身份隔离，不能把整批到期项打断</b>。
 *
 * <h2>被钉住的缺陷</h2>
 * {@link TenantKeyScope#hasIdentity(Long, Long)} 旧口径是"租户正数 + 用户非 0"，于是
 * {@code userId=-5} 这类<b>非法负值</b>被当成"有身份"。下游构造 {@link TenantUser} 时
 * （只接受正数或系统账号 {@code -1}）直接抛 {@link IllegalArgumentException} ——
 * 旧实现里这会把发现循环<b>整批中断</b>，排在后面的合法台账一条都回填不了。
 *
 * <h2>当前口径</h2>
 * 发现面已从"SCAN 全量回填"改为到期 ZSET（{@code ai:purge:retry:due}）+ {@code listDue}：
 * 坏身份成员<b>不摘除、不改台账</b>，只把到期分推到 1h + 确定性抖动的未来并 WARN（保留手工
 * DELETE 的恢复入口），同批的合法台账照常返回。
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
		String badKey = SessionPurgePendingStore.PENDING_PREFIX + "1:-5:" + BAD_SESSION;
		this.fake.strings.put(badKey, this.mapper.writeValueAsString(new SessionPurgePendingStore.Pending(BAD_SESSION,
				"purge-bad", "tester", 0, NOW_MS, NOW_MS, null, null, 1L, -5L, null, null, null, null)));
		// 坏成员同样直接注入到期索引（坏数据是直接写键面的，索引也按同一形态铺）。
		this.fake.zsets.computeIfAbsent(SessionPurgePendingStore.DUE_KEY, ignored -> new ConcurrentHashMap<>())
			.put(badKey, (double) NOW_MS);
		// 合法台账：走正常登记路径（身份 2:201），register 会把到期项写进 DUE_KEY。
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(2L, 201L)) {
			this.store.register(new TenantUser(2L, 201L), GOOD_SESSION, "purge-good", "tester", NOW_MS);
		}

		List<SessionPurgePendingStore.Pending> due = this.store.listDue(NOW_MS, 10);

		assertThat(due).extracting(SessionPurgePendingStore.Pending::sessionId)
			.as("坏身份台账不得打断到期批：合法台账必须照常进入待重试集合")
			.containsExactly(GOOD_SESSION);
		Double badScore = this.fake.zsets.getOrDefault(SessionPurgePendingStore.DUE_KEY, Map.of()).get(badKey);
		assertThat(badScore).as("坏身份成员不摘除、只隔离到未来（保留手工 DELETE 的恢复入口）")
			.isNotNull()
			.isGreaterThan((double) NOW_MS);

		// 合同本身：只接受正数或系统账号 -1（与 TenantUser / RunIdentityScope 同一口径）。
		assertThat(TenantKeyScope.hasIdentity(1L, -5L)).as("userId=-5 不是可用身份").isFalse();
		assertThat(TenantKeyScope.hasIdentity(1L, -2L)).as("userId<-1 一律不可用").isFalse();
		assertThat(TenantKeyScope.hasIdentity(1L, 0L)).as("userId=0 = 旧记录").isFalse();
		assertThat(TenantKeyScope.hasIdentity(1L, TenantUser.SYSTEM_USER_ID)).as("系统账号 -1 可用").isTrue();
		assertThat(TenantKeyScope.hasIdentity(1L, 201L)).as("正数用户可用").isTrue();
	}

}
