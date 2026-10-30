package com.example.configmgr.ai.tenant;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.RunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>会话作用域三键（current / active / runs）必须按身份分开</b>。
 *
 * <h2>被钉住的缺陷</h2>
 * 三个索引键原先只带 sessionId：同 sessionId 的两个身份共享"当前挂起轮"与"本会话轮次集合"。
 * 用户可见后果 = B 的终态清理把 A 的 {@code /runs/current} 指针删掉/改指（A 刷新后看不到自己的轮），
 * 或者 A 的会话销毁把 B 的轮次一起收走。
 *
 * <h2>本组口径</h2>
 * <ul>
 * <li>三键键名各带身份段，A/B 两两不同；</li>
 * <li>B 用 A 的 runId 调 {@code clearSessionCurrent} <b>不得</b>动到 A 的指针与活跃索引；</li>
 * <li>A/B 各自的 current 与 runs 集合互不可见（同 sessionId 下并存）。</li>
 * </ul>
 */
class SessionScopeKeyIdentityTest {

	private static final TenantUser IDENTITY_A = new TenantUser(1L, 101L);

	private static final TenantUser IDENTITY_B = new TenantUser(2L, 202L);

	private static final String SESSION_ID = "sess-shared-scope";

	private static final String RUN_A = "run-shared-scope-a";

	private static final String RUN_B = "run-shared-scope-b";

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final IdentityKeyedSessionRedis redis = new IdentityKeyedSessionRedis();

	private final RunStore store = new RunStore(this.redis.template(), this.mapper, this.properties);

	@Test
	void sessionCurrentActiveAndRunsIndexesAreIsolatedAndClearingWithAnotherIdentityLeavesTheOwnerIntact() {
		String currentA;
		String currentB;
		String activeA;
		String activeB;
		String runsA;
		String runsB;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(IDENTITY_A.tenantId(),
				IDENTITY_A.userId())) {
			currentA = this.store.sessionCurrentKey(SESSION_ID);
			activeA = this.store.sessionActiveKey(SESSION_ID);
			runsA = this.store.sessionRunsKey(SESSION_ID);
			this.store.markSessionCurrent(SESSION_ID, RUN_A);
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(IDENTITY_B.tenantId(),
				IDENTITY_B.userId())) {
			currentB = this.store.sessionCurrentKey(SESSION_ID);
			activeB = this.store.sessionActiveKey(SESSION_ID);
			runsB = this.store.sessionRunsKey(SESSION_ID);
			this.redis.addToSet(runsB, RUN_B);
			this.store.clearSessionCurrent(SESSION_ID, RUN_A);
		}

		assertThat(currentA).as("session→current 键必须带身份段").isEqualTo("ai:session:current:1:101:"
				+ SESSION_ID);
		assertThat(currentB).isEqualTo("ai:session:current:2:202:" + SESSION_ID);
		assertThat(activeA).as("session→active 键必须带身份段").isEqualTo("ai:session:active:1:101:" + SESSION_ID);
		assertThat(activeB).isEqualTo("ai:session:active:2:202:" + SESSION_ID);
		assertThat(runsA).as("session→runs 键必须带身份段").isEqualTo("ai:session:runs:1:101:" + SESSION_ID);
		assertThat(runsB).isEqualTo("ai:session:runs:2:202:" + SESSION_ID);

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(IDENTITY_A.tenantId(),
				IDENTITY_A.userId())) {
			assertThat(this.store.sessionCurrentRunId(SESSION_ID))
				.as("B 用 A 的 runId 清指针不得删掉 A 的 current").isEqualTo(RUN_A);
			this.redis.addToSet(runsA, RUN_A);
			assertThat(this.store.sessionRunIds(SESSION_ID)).as("A 的会话轮次索引只含自己的轮")
				.containsExactly(RUN_A);
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(IDENTITY_B.tenantId(),
				IDENTITY_B.userId())) {
			assertThat(this.store.sessionCurrentRunId(SESSION_ID)).as("B 的 current 不因 A 的写入而出现")
				.isNull();
			assertThat(this.store.sessionRunIds(SESSION_ID)).as("B 的会话轮次索引只含自己的轮")
				.containsExactly(RUN_B);
			this.store.markSessionCurrent(SESSION_ID, RUN_B);
		}

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(IDENTITY_A.tenantId(),
				IDENTITY_A.userId())) {
			assertThat(this.store.sessionCurrentRunId(SESSION_ID)).as("B 建立自己的 current 后 A 仍是自己的轮")
				.isEqualTo(RUN_A);
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(IDENTITY_B.tenantId(),
				IDENTITY_B.userId())) {
			assertThat(this.store.sessionCurrentRunId(SESSION_ID)).isEqualTo(RUN_B);
		}

		assertThat(this.redis.zMembers(activeA)).as("A 的活跃索引只含自己的轮（B 的清理不得摘掉它）")
			.containsExactly(RUN_A);
		assertThat(this.redis.zMembers(activeB)).as("B 的活跃索引只含自己的轮").containsExactly(RUN_B);
	}

	/**
	 * 只建模本组会触达的命令面（STRING {@code get/set}、SET、ZSET、{@code EXPIRE}）：
	 * 键名原样作为 Map 的 key，因此隔离性完全由产品代码拼出的键名决定。
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static final class IdentityKeyedSessionRedis {

		private final Map<String, String> values = new ConcurrentHashMap<>();

		private final Map<String, Set<String>> sets = new ConcurrentHashMap<>();

		private final Map<String, Map<String, Double>> sortedSets = new ConcurrentHashMap<>();

		private final StringRedisTemplate template = mock(StringRedisTemplate.class);

		private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

		private final SetOperations<String, String> setOps = mock(SetOperations.class);

		private final ZSetOperations<String, String> zsetOps = mock(ZSetOperations.class);

		private IdentityKeyedSessionRedis() {
			when(this.template.opsForValue()).thenReturn(this.valueOps);
			when(this.template.opsForSet()).thenReturn(this.setOps);
			when(this.template.opsForZSet()).thenReturn(this.zsetOps);
			when(this.template.expire(anyString(), any(Duration.class))).thenReturn(true);
			when(this.valueOps.get(anyString()))
				.thenAnswer(invocation -> this.values.get((String) invocation.getArgument(0)));
			doAnswer(invocation -> {
				this.values.put((String) invocation.getArgument(0), (String) invocation.getArgument(1));
				return null;
			}).when(this.valueOps).set(anyString(), anyString(), any(Duration.class));
			when(this.setOps.add(anyString(), any(String[].class))).thenAnswer(invocation -> {
				Set<String> members = this.sets.computeIfAbsent((String) invocation.getArgument(0),
						ignored -> new LinkedHashSet<>());
				long added = 0L;
				for (Object value : trailing(invocation.getArguments())) {
					if (members.add(String.valueOf(value))) {
						added++;
					}
				}
				return added;
			});
			when(this.setOps.members(anyString())).thenAnswer(invocation -> {
				Set<String> members = this.sets.get((String) invocation.getArgument(0));
				return members == null ? Set.of() : new LinkedHashSet<>(members);
			});
			when(this.zsetOps.add(anyString(), anyString(), anyDouble())).thenAnswer(invocation -> {
				this.sortedSets.computeIfAbsent((String) invocation.getArgument(0),
						ignored -> new LinkedHashMap<>()).put((String) invocation.getArgument(1),
								(Double) invocation.getArgument(2));
				return true;
			});
			when(this.zsetOps.remove(anyString(), any(Object[].class))).thenAnswer(invocation -> {
				Map<String, Double> members = this.sortedSets.get((String) invocation.getArgument(0));
				if (members == null) {
					return 0L;
				}
				long removed = 0L;
				for (Object value : trailing(invocation.getArguments())) {
					if (members.remove(String.valueOf(value)) != null) {
						removed++;
					}
				}
				return removed;
			});
		}

		private StringRedisTemplate template() {
			return this.template;
		}

		private void addToSet(String key, String member) {
			this.sets.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(member);
		}

		private Set<String> zMembers(String key) {
			Map<String, Double> members = this.sortedSets.get(key);
			return members == null ? Set.of() : Set.copyOf(members.keySet());
		}

		/** 变长实参（第 0 个是 key，其余是成员）。 */
		private static Object[] trailing(Object[] arguments) {
			Object[] values = new Object[arguments.length - 1];
			System.arraycopy(arguments, 1, values, 0, values.length);
			return values;
		}

	}

}
