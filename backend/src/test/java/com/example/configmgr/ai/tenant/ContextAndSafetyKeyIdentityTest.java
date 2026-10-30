package com.example.configmgr.ai.tenant;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.context.ContextState;
import com.example.configmgr.ai.run.RunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>同一 sessionId 下四类会话域读写必须按身份分开</b>：上下文真值（{@code ai:ctx:}）、上下文台账
 * （{@code ai:ctx:ledger:}）、压缩前原文（{@code ai:ctx:archive:}）、安全事件台账（{@code ai:safety:}）。
 *
 * <h2>被钉住的缺陷</h2>
 * 四个键族原先只按 sessionId 拼名：两个租户/用户各自生成同一个 sessionId 是合法且必然的
 * （前端草稿、导入数据都可能撞 id）。键里没有身份段时，B 的上下文计量会覆盖 A 的真值、
 * B 的压缩台账/原文会混进 A 的复盘面、安全命中记录互相串 —— 而 sessionId 本身<b>不</b>是跨身份唯一性证明。
 *
 * <h2>本组口径</h2>
 * <ul>
 * <li>键名带身份段（{@code <tenantId>:<userId>:}），A/B 的四个键两两不同；</li>
 * <li>在 A 作用域写入的四类数据，在 B 作用域读出来必须<b>看不到</b>（上下文为空态、台账/安全为空表、
 * 归档键不存在），反之亦然；</li>
 * <li>同一身份自己的读回必须逐字段一致（不是"能读到就行"）。</li>
 * </ul>
 */
class ContextAndSafetyKeyIdentityTest {

	private static final TenantUser IDENTITY_A = new TenantUser(1L, 101L);

	private static final TenantUser IDENTITY_B = new TenantUser(2L, 202L);

	private static final String SESSION_ID = "sess-shared-context";

	private static final int TURN_NO = 3;

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final IdentityKeyedRedis redis = new IdentityKeyedRedis();

	private final RunStore store = new RunStore(this.redis.template(), this.mapper, this.properties);

	@Test
	void sameSessionIdAcrossIdentitiesKeepsContextLedgerArchiveAndSafetyInvisibleToEachOther() {
		ContextState stateA = ContextState.EMPTY.withUsage(1111, 11, 4, false, 1_000L, "model-a", 10, 20, 30);
		ContextState stateB = ContextState.EMPTY.withUsage(2222, 22, 9, false, 2_000L, "model-b", 40, 50, 60);

		String contextKeyA;
		String contextKeyB;
		String ledgerKeyA;
		String ledgerKeyB;
		String archiveKeyA;
		String archiveKeyB;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(IDENTITY_A.tenantId(),
				IDENTITY_A.userId())) {
			contextKeyA = this.store.contextKey(SESSION_ID);
			ledgerKeyA = this.store.contextLedgerKey(SESSION_ID);
			archiveKeyA = this.store.contextArchiveKey(SESSION_ID, TURN_NO);
			this.store.write(SESSION_ID, stateA);
			this.store.appendLedger(SESSION_ID, Map.of("kind", "compact-a"));
			this.store.archive(SESSION_ID, TURN_NO, List.of("a-msg"));
			this.store.appendSafetyEvent(SESSION_ID, Map.of("ruleId", "rule-a"));
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(IDENTITY_B.tenantId(),
				IDENTITY_B.userId())) {
			contextKeyB = this.store.contextKey(SESSION_ID);
			ledgerKeyB = this.store.contextLedgerKey(SESSION_ID);
			archiveKeyB = this.store.contextArchiveKey(SESSION_ID, TURN_NO);
			this.store.write(SESSION_ID, stateB);
			this.store.appendLedger(SESSION_ID, Map.of("kind", "compact-b"));
			this.store.archive(SESSION_ID, TURN_NO, List.of("b-msg"));
			this.store.appendSafetyEvent(SESSION_ID, Map.of("ruleId", "rule-b"));
		}

		String safetyKeyA = "ai:safety:1:101:" + SESSION_ID;
		String safetyKeyB = "ai:safety:2:202:" + SESSION_ID;

		assertThat(contextKeyA).as("上下文真值键必须带身份段").isEqualTo("ai:ctx:1:101:" + SESSION_ID);
		assertThat(contextKeyB).isEqualTo("ai:ctx:2:202:" + SESSION_ID);
		assertThat(ledgerKeyA).as("上下文台账键必须带身份段").isEqualTo("ai:ctx:ledger:1:101:" + SESSION_ID);
		assertThat(ledgerKeyB).isEqualTo("ai:ctx:ledger:2:202:" + SESSION_ID);
		assertThat(archiveKeyA).as("压缩前原文键必须带身份段").isEqualTo("ai:ctx:archive:1:101:" + SESSION_ID + ":"
				+ TURN_NO);
		assertThat(archiveKeyB).isEqualTo("ai:ctx:archive:2:202:" + SESSION_ID + ":" + TURN_NO);
		assertThat(this.redis.listKeys()).as("安全台账键必须带身份段").contains(safetyKeyA, safetyKeyB);
		assertThat(Set.of(contextKeyA, contextKeyB)).as("四类键在同 sessionId 下不得重合").hasSize(2);

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(IDENTITY_A.tenantId(),
				IDENTITY_A.userId())) {
			assertThat(this.store.read(SESSION_ID)).as("A 读回自己的上下文真值").isEqualTo(stateA);
			assertThat(this.store.contextLedger(SESSION_ID)).as("A 只看到自己的压缩台账")
				.containsExactly(Map.of("kind", "compact-a"));
			assertThat(this.store.safetyLedger(SESSION_ID)).as("A 只看到自己的安全台账")
				.containsExactly(Map.of("ruleId", "rule-a"));
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(IDENTITY_B.tenantId(),
				IDENTITY_B.userId())) {
			assertThat(this.store.read(SESSION_ID)).as("B 读回自己的上下文真值（不是 A 的）").isEqualTo(stateB);
			assertThat(this.store.contextLedger(SESSION_ID)).as("B 只看到自己的压缩台账")
				.containsExactly(Map.of("kind", "compact-b"));
			assertThat(this.store.safetyLedger(SESSION_ID)).as("B 只看到自己的安全台账")
				.containsExactly(Map.of("ruleId", "rule-b"));
		}
		assertThat(this.redis.value(archiveKeyA)).as("A 的压缩前原文落在 A 的键上").contains("a-msg")
			.doesNotContain("b-msg");
		assertThat(this.redis.value(archiveKeyB)).as("B 的压缩前原文落在 B 的键上").contains("b-msg")
			.doesNotContain("a-msg");
	}

	/**
	 * 只建模本组会触达的命令面（HASH {@code putAll/entries}、LIST {@code leftPush/range/trim}、
	 * STRING {@code get/set}、{@code EXPIRE}）：键名原样作为 Map 的 key，因此"键是否带身份"
	 * 由产品代码拼出来的名字直接决定，读写隔离不依赖任何身份推断。
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
		}

		private StringRedisTemplate template() {
			return this.template;
		}

		private String value(String key) {
			return this.values.get(key);
		}

		private Set<String> listKeys() {
			return Set.copyOf(this.lists.keySet());
		}

	}

}
