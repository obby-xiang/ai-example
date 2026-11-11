package com.example.configmgr.ai.tenant;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.context.ContextState;
import com.example.configmgr.ai.context.RedisContextStateStore;
import com.example.configmgr.ai.run.RunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>同一 sessionId 下四类会话域读写必须按身份分开</b>：上下文真值（{@code ai:ctx:}）、上下文台账
 * （{@code ai:ctx:ledger:}）、已提交压缩事件（{@code ai:ctx:events:}）、安全事件台账（{@code ai:safety:}）。
 *
 * <h2>被钉住的缺陷</h2>
 * 这些键族原先只按 sessionId 拼名：两个租户/用户各自生成同一个 sessionId 是合法且必然的
 * （前端草稿、导入数据都可能撞 id）。键里没有身份段时，B 的上下文计量会覆盖 A 的真值、
 * B 的压缩台账/事件会混进 A 的复盘面、安全命中记录互相串 —— 而 sessionId 本身<b>不</b>是跨身份唯一性证明。
 *
 * <h2>本组口径</h2>
 * <ul>
 * <li>键名带身份段（{@code <tenantId>:<userId>:}），A/B 的四个键两两不同；</li>
 * <li>在 A 作用域写入的四类数据，在 B 作用域读出来必须<b>看不到</b>（上下文为空态、台账/安全/事件为空表）；</li>
 * <li>同一身份自己的读回必须逐字段一致（不是"能读到就行"）；</li>
 * <li>无身份上下文 ⇒ <b>fail-closed</b>（不生成/不读取无身份键，直接抛出）。</li>
 * </ul>
 */
class ContextAndSafetyKeyIdentityTest {

	private static final TenantUser IDENTITY_A = new TenantUser(1L, 101L);

	private static final TenantUser IDENTITY_B = new TenantUser(2L, 202L);

	private static final String SESSION_ID = "sess-shared-context";

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final IdentityKeyedRedis redis = new IdentityKeyedRedis();

	private final RedisContextStateStore contextStore = new RedisContextStateStore(this.redis.template(), this.mapper,
			this.properties);

	private final RunStore runStore = new RunStore(this.redis.template(), this.mapper, this.properties);

	@Test
	void sameSessionIdAcrossIdentitiesKeepsContextLedgerEventsAndSafetyInvisibleToEachOther() {
		ContextState stateA = ContextState.EMPTY.withUsage(1111, 11, 4, false, 1_000L, "model-a", 10, 20, 30);
		ContextState stateB = ContextState.EMPTY.withUsage(2222, 22, 9, false, 2_000L, "model-b", 40, 50, 60);

		String contextKeyA;
		String contextKeyB;
		String ledgerKeyA;
		String ledgerKeyB;
		String eventsKeyA;
		String eventsKeyB;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(IDENTITY_A.tenantId(),
				IDENTITY_A.userId())) {
			contextKeyA = this.runStore.contextKey(SESSION_ID);
			ledgerKeyA = this.runStore.contextLedgerKey(SESSION_ID);
			eventsKeyA = this.runStore.contextEventsKey(SESSION_ID);
			this.contextStore.write(SESSION_ID, stateA);
			this.contextStore.appendLedger(SESSION_ID, Map.of("kind", "compact-a"));
			this.contextStore.appendEvent(SESSION_ID, Map.of("eventId", "event-a"));
			this.runStore.appendSafetyEvent(SESSION_ID, Map.of("ruleId", "rule-a"));
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(IDENTITY_B.tenantId(),
				IDENTITY_B.userId())) {
			contextKeyB = this.runStore.contextKey(SESSION_ID);
			ledgerKeyB = this.runStore.contextLedgerKey(SESSION_ID);
			eventsKeyB = this.runStore.contextEventsKey(SESSION_ID);
			this.contextStore.write(SESSION_ID, stateB);
			this.contextStore.appendLedger(SESSION_ID, Map.of("kind", "compact-b"));
			this.contextStore.appendEvent(SESSION_ID, Map.of("eventId", "event-b"));
			this.runStore.appendSafetyEvent(SESSION_ID, Map.of("ruleId", "rule-b"));
		}

		String safetyKeyA = "ai:safety:1:101:" + SESSION_ID;
		String safetyKeyB = "ai:safety:2:202:" + SESSION_ID;

		assertThat(contextKeyA).as("上下文真值键必须带身份段").isEqualTo("ai:ctx:1:101:" + SESSION_ID);
		assertThat(contextKeyB).isEqualTo("ai:ctx:2:202:" + SESSION_ID);
		assertThat(ledgerKeyA).as("上下文台账键必须带身份段").isEqualTo("ai:ctx:ledger:1:101:" + SESSION_ID);
		assertThat(ledgerKeyB).isEqualTo("ai:ctx:ledger:2:202:" + SESSION_ID);
		assertThat(eventsKeyA).as("压缩事件键必须带身份段").isEqualTo("ai:ctx:events:1:101:" + SESSION_ID);
		assertThat(eventsKeyB).isEqualTo("ai:ctx:events:2:202:" + SESSION_ID);
		assertThat(this.redis.listKeys()).as("安全台账键必须带身份段").contains(safetyKeyA, safetyKeyB);
		assertThat(Set.of(contextKeyA, contextKeyB)).as("四类键在同 sessionId 下不得重合").hasSize(2);

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(IDENTITY_A.tenantId(),
				IDENTITY_A.userId())) {
			assertThat(this.contextStore.read(SESSION_ID)).as("A 读回自己的上下文真值（写入使 version+1）")
				.isEqualTo(stateA.withVersion(1L));
			assertThat(this.contextStore.contextLedger(SESSION_ID)).as("A 只看到自己的压缩台账")
				.containsExactly(Map.of("kind", "compact-a"));
			assertThat(this.contextStore.events(SESSION_ID)).as("A 只看到自己的压缩事件")
				.containsExactly(Map.of("eventId", "event-a"));
			assertThat(this.runStore.safetyLedger(SESSION_ID)).as("A 只看到自己的安全台账")
				.containsExactly(Map.of("ruleId", "rule-a"));
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(IDENTITY_B.tenantId(),
				IDENTITY_B.userId())) {
			assertThat(this.contextStore.read(SESSION_ID)).as("B 读回自己的上下文真值（不是 A 的）")
				.isEqualTo(stateB.withVersion(1L));
			assertThat(this.contextStore.contextLedger(SESSION_ID)).as("B 只看到自己的压缩台账")
				.containsExactly(Map.of("kind", "compact-b"));
			assertThat(this.contextStore.events(SESSION_ID)).as("B 只看到自己的压缩事件")
				.containsExactly(Map.of("eventId", "event-b"));
			assertThat(this.runStore.safetyLedger(SESSION_ID)).as("B 只看到自己的安全台账")
				.containsExactly(Map.of("ruleId", "rule-b"));
		}
		assertThat(this.redis.hash(contextKeyA)).as("A 的计量落在 A 的键上").containsEntry("used", "1111");
		assertThat(this.redis.hash(contextKeyB)).as("B 的计量落在 B 的键上").containsEntry("used", "2222");
	}

	/** 无身份上下文 = fail-closed：不得生成/读取无身份键。 */
	@Test
	void withoutIdentityTheContextKeysFailClosed() {
		assertThat(RequestContextUtils.currentOrNull()).isNull();

		assertThatThrownBy(() -> this.contextStore.read(SESSION_ID)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> this.contextStore.write(SESSION_ID, ContextState.EMPTY))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> this.runStore.contextKey(SESSION_ID)).isInstanceOf(IllegalStateException.class);
		assertThat(this.redis.hashKeys()).as("没有任何键被写出").isEmpty();
	}

	/**
	 * 只建模本组会触达的命令面（HASH {@code entries/putAll}、LIST {@code leftPush/range/trim}、
	 * STRING {@code get/set}、{@code EXPIRE}）与三条单键脚本（CAS 写入 / 台账追加 / 事件追加）。
	 * 键名原样作为 Map 的 key，因此"键是否带身份"由产品代码拼出来的名字直接决定。
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static final class IdentityKeyedRedis {

		private final Map<String, String> values = new ConcurrentHashMap<>();

		private final Map<String, Map<String, String>> hashes = new ConcurrentHashMap<>();

		private final Map<String, List<String>> lists = new ConcurrentHashMap<>();

		private final StringRedisTemplate template = mock(StringRedisTemplate.class);

		private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

		private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

		private final ListOperations<String, String> listOps = mock(ListOperations.class);

		private IdentityKeyedRedis() {
			when(this.template.opsForValue()).thenReturn(this.valueOps);
			when(this.template.opsForHash()).thenReturn(this.hashOps);
			when(this.template.opsForList()).thenReturn(this.listOps);
			when(this.template.expire(anyString(), any(Duration.class))).thenReturn(true);
			when(this.valueOps.get(anyString()))
				.thenAnswer(invocation -> this.values.get((String) invocation.getArgument(0)));
			doAnswer(invocation -> {
				this.values.put((String) invocation.getArgument(0), (String) invocation.getArgument(1));
				return null;
			}).when(this.valueOps).set(anyString(), anyString(), any(Duration.class));
			when(this.hashOps.entries(anyString())).thenAnswer(invocation -> {
				Map<Object, Object> entries = new LinkedHashMap<>();
				this.hashes.getOrDefault((String) invocation.getArgument(0), Map.of()).forEach(entries::put);
				return entries;
			});
			doAnswer(invocation -> {
				Map<String, String> table = this.hashes.computeIfAbsent((String) invocation.getArgument(0),
						ignored -> new LinkedHashMap<>());
				((Map<?, ?>) invocation.getArgument(1))
					.forEach((field, value) -> table.put(String.valueOf(field), String.valueOf(value)));
				return null;
			}).when(this.hashOps).putAll(anyString(), anyMap());
			when(this.listOps.leftPush(anyString(), anyString())).thenAnswer(invocation -> {
				List<String> list = this.lists.computeIfAbsent((String) invocation.getArgument(0),
						ignored -> new ArrayList<>());
				list.add(0, (String) invocation.getArgument(1));
				return (long) list.size();
			});
			when(this.listOps.range(anyString(), anyLong(), anyLong())).thenAnswer(invocation -> {
				List<String> list = this.lists.getOrDefault((String) invocation.getArgument(0), List.of());
				long from = (Long) invocation.getArgument(1);
				long to = (Long) invocation.getArgument(2);
				if (list.isEmpty()) {
					return List.of();
				}
				int start = from < 0L ? (int) Math.max(0L, list.size() + from) : (int) Math.min(from, list.size());
				int end = to < 0L ? (int) (list.size() + to) : (int) Math.min(to, list.size() - 1L);
				return start > end ? List.of() : new ArrayList<>(list.subList(start, end + 1));
			});
			doAnswer(invocation -> {
				List<String> list = this.lists.computeIfAbsent((String) invocation.getArgument(0),
						ignored -> new ArrayList<>());
				long from = (Long) invocation.getArgument(1);
				long to = (Long) invocation.getArgument(2);
				if (list.isEmpty()) {
					return null;
				}
				int start = (int) Math.max(0L, from);
				int end = (int) Math.min(to, list.size() - 1L);
				if (start > end) {
					list.clear();
					return null;
				}
				List<String> kept = new ArrayList<>(list.subList(start, end + 1));
				list.clear();
				list.addAll(kept);
				return null;
			}).when(this.listOps).trim(anyString(), anyLong(), anyLong());
			when(this.template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
				.thenAnswer(invocation -> handleScript(invocation.getArguments()));
		}

		private Object handleScript(Object[] raw) {
			RedisScript<?> script = (RedisScript<?>) raw[0];
			List<?> keys = (List<?>) raw[1];
			Object[] args = new Object[Math.max(0, raw.length - 2)];
			for (int i = 2; i < raw.length; i++) {
				args[i - 2] = raw[i];
			}
			String body = script.getScriptAsString();
			String key = String.valueOf(keys.get(0));
			if (body.contains("lastCompactionId")) {
				return casWrite(key, args);
			}
			if (body.contains("commitEntryId")) {
				return appendList(key, args, "commitEntryId");
			}
			if (body.contains("eventId")) {
				return appendList(key, args, "eventId");
			}
			throw new IllegalStateException("未建模的脚本：" + body);
		}

		private long casWrite(String key, Object[] args) {
			long expected = Long.parseLong(String.valueOf(args[0]));
			long ttl = Long.parseLong(String.valueOf(args[1]));
			Map<String, String> table = this.hashes.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
			long current = Long.parseLong(table.getOrDefault("version", "0"));
			if (current != expected) {
				return 0L;
			}
			for (int i = 4; i + 1 < args.length; i += 2) {
				table.put(String.valueOf(args[i]), String.valueOf(args[i + 1]));
			}
			table.put("version", Long.toString(current + 1L));
			touch(key, ttl);
			return 1L;
		}

		private long appendList(String key, Object[] args, String dedupField) {
			String payload = String.valueOf(args[0]);
			int limit = Integer.parseInt(String.valueOf(args[1]));
			long ttl = Long.parseLong(String.valueOf(args[2]));
			List<String> list = this.lists.computeIfAbsent(key, ignored -> new ArrayList<>());
			if (payload.contains("\"" + dedupField + "\"")) {
				for (String existing : list) {
					if (existing.contains("\"" + dedupField + "\"")) {
						touch(key, ttl);
						return 1L;
					}
				}
			}
			list.add(0, payload);
			while (list.size() > limit) {
				list.remove(list.size() - 1);
			}
			touch(key, ttl);
			return 1L;
		}

		private void touch(String key, long ttlMillis) {
			this.values.put("__ttl__:" + key, Long.toString(ttlMillis));
		}

		private StringRedisTemplate template() {
			return this.template;
		}

		private Map<String, String> hash(String key) {
			return this.hashes.getOrDefault(key, Map.of());
		}

		private Set<String> hashKeys() {
			return Set.copyOf(this.hashes.keySet());
		}

		private Set<String> listKeys() {
			return Set.copyOf(this.lists.keySet());
		}

	}

}
