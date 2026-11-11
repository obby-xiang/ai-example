package com.example.configmgr.ai.history;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.PendingToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户展示历史（B2）：键/TTL/last-wins/排序/容量/无身份 fail-closed。
 *
 * <p>用进程内 Redis 替身（只建模产品实际发出的 LIST/STRING/EXPIRE 命令），不起真实 Redis。
 */
class ConversationTranscriptStoreTest {

	private static final long TENANT = 1L;

	private static final long USER = 101L;

	private static final Duration TTL = Duration.ofHours(24);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final ListOperations<String, String> listOps = mock(ListOperations.class);

	private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

	private final Map<String, List<String>> lists = new LinkedHashMap<>();

	private final Map<String, String> values = new LinkedHashMap<>();

	private final Map<String, Duration> expiries = new LinkedHashMap<>();

	private final Map<String, Map<String, String>> hashes = new LinkedHashMap<>();

	private final Map<String, Map<String, Long>> zsets = new LinkedHashMap<>();

	private static boolean trimOldest(Map<String, String> hash, Map<String, Long> zset, int count) {
		List<String> oldest = zset.entrySet().stream()
				.sorted(Map.Entry.comparingByValue())
				.limit(Math.max(0, count))
				.map(Map.Entry::getKey).toList();
		for (String id : oldest) {
			hash.remove(id);
			zset.remove(id);
		}
		return !oldest.isEmpty();
	}

	private ConversationTranscriptStore store;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		when(this.redis.opsForList()).thenReturn(this.listOps);
		when(this.redis.opsForValue()).thenReturn(this.valueOps);
		when(this.listOps.rightPushAll(anyString(), anyCollection())).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);
			Collection<String> payloads = invocation.getArgument(1);
			List<String> list = this.lists.computeIfAbsent(key, ignored -> new ArrayList<>());
			list.addAll(payloads);
			return (long) list.size();
		});
		// 追加脚本替身（TRANSCRIPT_APPEND_BOUNDED）：push + 精确字节计数 + 条数/字节裁剪 + TTL。
		// Mockito 把 varargs 摊平进 getArguments()：raw[0]=script、raw[1]=keys、
		// raw[2]=TTL、raw[3]=maxEntries、raw[4]=maxBytes、raw[5]=added、raw[6..]=payload。
		when(this.redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
			Object[] raw = invocation.getArguments();
			@SuppressWarnings("unchecked")
			List<String> keys = (List<String>) raw[1];
			String data = keys.get(0);
			String order = keys.get(1);
			String meta = keys.get(2);
			String truncatedKey = keys.get(3);
			String second = String.valueOf(raw[3]);
			if (second.matches("\\d+")) {
				// APPEND_SCRIPT: ttl,maxEntries,maxBytes,(entryId,json,runEpoch)*
				long ttl = Long.parseLong(String.valueOf(raw[2]));
				int maxEntries = Integer.parseInt(second);
				long maxBytes = Long.parseLong(String.valueOf(raw[4]));
				Map<String, String> hash = this.hashes.computeIfAbsent(data, key -> new LinkedHashMap<>());
				Map<String, Long> zset = this.zsets.computeIfAbsent(order, key -> new LinkedHashMap<>());
				Map<String, String> metaHash = this.hashes.computeIfAbsent(meta, key -> new LinkedHashMap<>());
				long seq = metaHash.containsKey("seq") ? Long.parseLong(metaHash.get("seq")) : 0L;
				long rev = metaHash.containsKey("rev") ? Long.parseLong(metaHash.get("rev")) : 0L;
				for (int i = 5; i + 1 < raw.length; i += 3) {
					String id = String.valueOf(raw[i]);
					String payload = String.valueOf(raw[i + 1]);
					if (!hash.containsKey(id)) {
						seq += 1L;
						zset.put(id, seq);
					}
					hash.put(id, payload);
					rev += 1L;
				}
				boolean truncated = false;
				while (zset.size() > maxEntries) {
					truncated |= trimOldest(hash, zset, zset.size() - maxEntries);
				}
				long bytes = byteSum(hash.values());
				while (bytes > maxBytes && zset.size() > 1) {
					truncated |= trimOldest(hash, zset, Math.min(zset.size() - 1, 200));
					bytes = byteSum(hash.values());
				}
				metaHash.put("bytes", String.valueOf(Math.max(0L, bytes)));
				metaHash.put("seq", String.valueOf(seq));
				metaHash.put("rev", String.valueOf(rev));
				if (truncated) {
					this.values.put(truncatedKey, "1");
				}
				for (String key : keys) {
					this.expiries.put(key, Duration.ofMillis(ttl));
				}
				return 1L;
			}
			// READ_PAGE_SCRIPT: ttl,mode,before,limit
			String mode = String.valueOf(raw[3]);
			Long before = String.valueOf(raw[4]).isBlank() ? null : Long.parseLong(String.valueOf(raw[4]));
			int limit = Integer.parseInt(String.valueOf(raw[5]));
			Map<String, String> hash = this.hashes.getOrDefault(data, Map.of());
			Map<String, Long> zset = this.zsets.getOrDefault(order, Map.of());
			Map<String, String> metaHash = this.hashes.getOrDefault(meta, Map.of());
			List<String> ids = zset.entrySet().stream()
					.filter(entry -> before == null || entry.getValue() < before)
					.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
					.map(Map.Entry::getKey).toList();
			int total = ids.size();
			boolean hasMore = ids.size() > limit;
			List<String> page = ids.subList(0, Math.min(limit, ids.size()));
			String nextCursor = hasMore && !page.isEmpty() ? String.valueOf(zset.get(page.get(page.size() - 1))) : "";
			String revision = metaHash.getOrDefault("rev", "0");
			boolean truncated = this.values.containsKey(truncatedKey);
			List<Object> out = new ArrayList<>();
			out.add(revision);
			out.add(String.valueOf(total));
			out.add(hasMore ? "1" : "0");
			out.add(nextCursor);
			out.add(truncated ? "1" : "0");
			for (String id : page) {
				out.add(hash.get(id));
			}
			long ttl = Long.parseLong(String.valueOf(raw[2]));
			for (String key : keys) {
				this.expiries.put(key, Duration.ofMillis(ttl));
			}
			return out;
		});

		when(this.listOps.range(anyString(), anyLong(), anyLong())).thenAnswer(invocation -> {
			List<String> list = this.lists.get(invocation.getArgument(0));
			if (list == null || list.isEmpty()) {
				return List.of();
			}
			int size = list.size();
			long start = invocation.getArgument(1);
			long stop = invocation.getArgument(2);
			int from = (int) (start < 0 ? Math.max(0, size + start) : Math.min(start, size));
			int to = (int) (stop < 0 ? size + stop : Math.min(stop, size - 1));
			return new ArrayList<>(list.subList(from, Math.min(size, to + 1)));
		});
		when(this.listOps.size(anyString())).thenAnswer(invocation -> {
			List<String> list = this.lists.get(invocation.getArgument(0));
			return list == null ? 0L : (long) list.size();
		});
		doAnswer(invocation -> {
			String key = invocation.getArgument(0);
			long start = invocation.getArgument(1);
			long stop = invocation.getArgument(2);
			List<String> list = this.lists.get(key);
			if (list == null) {
				return null;
			}
			int size = list.size();
			int from = (int) (start < 0 ? Math.max(0, size + start) : Math.min(start, size));
			int to = (int) (stop < 0 ? size + stop : Math.min(stop, size - 1));
			List<String> next = from >= size || from > to ? new ArrayList<>()
					: new ArrayList<>(list.subList(from, Math.min(size, to + 1)));
			this.lists.put(key, next);
			return null;
		}).when(this.listOps).trim(anyString(), anyLong(), anyLong());
		when(this.valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);
			if (this.values.containsKey(key) || this.lists.containsKey(key)) {
				return false;
			}
			this.values.put(key, invocation.getArgument(1));
			this.expiries.put(key, invocation.getArgument(2));
			return true;
		});
		doAnswer(invocation -> {
			String key = invocation.getArgument(0);
			this.values.put(key, invocation.getArgument(1));
			this.expiries.put(key, invocation.getArgument(2));
			return null;
		}).when(this.valueOps).set(anyString(), anyString(), any(Duration.class));
		when(this.redis.hasKey(anyString())).thenAnswer(invocation ->
				this.lists.containsKey(invocation.getArgument(0)) || this.values.containsKey(invocation.getArgument(0)));
		when(this.redis.expire(anyString(), any(Duration.class))).thenAnswer(invocation -> {
			this.expiries.put(invocation.getArgument(0), invocation.getArgument(1));
			return true;
		});
		this.store = new ConversationTranscriptStore(this.redis, new ObjectMapper(), TTL);
	}

	@Test
	void appendsIdempotentlyByEntryId() {
		TranscriptEntry pending = TranscriptEntries.tool("run-a", 100L, 1_000L, "c-1", "list_tasks",
				PendingToolCall.KIND_BACKEND, PendingToolCall.PENDING, null);
		TranscriptEntry executed = TranscriptEntries.tool("run-a", 100L, 2_000L, "c-1", "list_tasks",
				PendingToolCall.KIND_BACKEND, PendingToolCall.EXECUTED, "任务列表");

		this.store.append("s-1",  TENANT,  USER,  List.of(pending), 1L);
		this.store.append("s-1",  TENANT,  USER,  List.of(executed), 1L);

		ConversationTranscriptStore.ReadPage read = this.store.readPage("s-1",  TENANT,  USER, null, null);
		assertThat(read.entries()).hasSize(1);
		assertThat(read.entries().get(0).status()).isEqualTo(PendingToolCall.EXECUTED);
		assertThat(read.entries().get(0).result()).isEqualTo("任务列表");
	}

	@Test
	void lastWinsOnToolStatusUpdate() {
		this.store.append("s-1",  TENANT,  USER,  List.of(TranscriptEntries.tool("run-a", 100L, 1L, "c-1", "x",
				PendingToolCall.KIND_BACKEND, PendingToolCall.PENDING, null)), 1L);
		this.store.append("s-1",  TENANT,  USER,  List.of(TranscriptEntries.tool("run-a", 100L, 2L, "c-1", "x",
				PendingToolCall.KIND_BACKEND, PendingToolCall.CANCELLED, "已取消")), 1L);

		assertThat(this.store.readPage("s-1",  TENANT,  USER, null, null).entries())
			.singleElement()
			.extracting(TranscriptEntry::status, TranscriptEntry::result)
			.containsExactly(PendingToolCall.CANCELLED, "已取消");
	}

	/** 挂起轮晚收尾：写序 B→A，但按 turnStartedAtMs 读成 A→B。 */
	@Test
	void ordersParkedAndNewRunByTurnStart() {
		this.store.append("s-1",  TENANT,  USER,
				List.of(TranscriptEntries.user("run-new", 200L, 20L, "新轮")), 1L);
		this.store.append("s-1",  TENANT,  USER,
				List.of(TranscriptEntries.user("run-old", 100L, 30L, "旧轮")), 1L);

		assertThat(this.store.readPage("s-1",  TENANT,  USER, null, null).entries())
			.extracting(TranscriptEntry::text)
			.containsExactly("旧轮", "新轮");
	}

	@Test
	void tenantScopedKeysDiffer() {
		this.store.append("s-1",  1L,  101L,  List.of(TranscriptEntries.user("run-a", 1L, 1L, "租户一")), 1L);
		this.store.append("s-1",  2L,  202L,  List.of(TranscriptEntries.user("run-a", 1L, 1L, "租户二")), 1L);

		assertThat(ConversationTranscriptStore.dataKey("s-1", 1L, 101L))
			.isNotEqualTo(ConversationTranscriptStore.dataKey("s-1", 2L, 202L));
		assertThat(this.store.readPage("s-1",  1L,  101L, null, null).entries()).extracting(TranscriptEntry::text)
			.containsExactly("租户一");
		assertThat(this.store.readPage("s-1",  2L,  202L, null, null).entries()).extracting(TranscriptEntry::text)
			.containsExactly("租户二");
	}

	@Test
	void ttlRefreshedOnReadAndWrite() {
		this.store.append("s-1",  TENANT,  USER,  List.of(TranscriptEntries.user("run-a", 1L, 1L, "问题")), 1L);
		String key = ConversationTranscriptStore.dataKey("s-1", TENANT, USER);

		assertThat(this.expiries).containsEntry(key, TTL);
		this.expiries.remove(key);
		this.store.readPage("s-1",  TENANT,  USER, null, null);

		assertThat(this.expiries).containsEntry(key, TTL);
	}

	/** 容量上限：最多 MAX_ENTRIES 条，超出从最旧裁并留下中性 truncated 标记。 */
	@Test
	void boundedTrimMarksTruncation() {
		List<TranscriptEntry> entries = new ArrayList<>();
		for (int i = 0; i < ConversationTranscriptStore.MAX_ENTRIES + 1; i++) {
			entries.add(TranscriptEntries.user("run-" + i, i, i, "m" + i));
		}

		this.store.append("s-1",  TENANT,  USER,  entries, 1L);

		ConversationTranscriptStore.ReadPage read = this.store.readPage("s-1",  TENANT,  USER, null, null);
		assertThat(read.total()).as("四键存储的总条数按容量上限裁剪").isEqualTo(ConversationTranscriptStore.MAX_ENTRIES);
		assertThat(read.entries()).as("默认页大小 = 200").hasSize(200);
		assertThat(read.truncated()).isTrue();
		String data = ConversationTranscriptStore.dataKey("s-1", TENANT, USER);
		String order = ConversationTranscriptStore.orderKey("s-1", TENANT, USER);
		assertThat(this.zsets.get(order)).hasSize(ConversationTranscriptStore.MAX_ENTRIES);
		assertThat(this.hashes.get(data)).as("最旧一条被裁").doesNotContainKey("run-0:user");
		assertThat(this.hashes.get(data)).containsKey("run-" + ConversationTranscriptStore.MAX_ENTRIES + ":user");
	}

	/** 追加与 TTL 同一个脚本原子完成：不存在"已 push、无 TTL"的窗口。 */
	@Test
	void appendSetsTtlInTheSameScriptAsPush() {
		this.store.append("s-1",  TENANT,  USER,  List.of(TranscriptEntries.user("run-a", 1L, 1L, "问题")), 1L);

		@SuppressWarnings("rawtypes")
		org.mockito.ArgumentCaptor<RedisScript> script = org.mockito.ArgumentCaptor.forClass(RedisScript.class);
		org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
		verify(this.redis).execute(script.capture(), anyList(), args.capture());

		assertThat(script.getValue().getScriptAsString())
			.contains("TRANSCRIPT_CANONICAL_UPSERT_FENCED")
			.contains("HSET")
			.contains("ZADD")
			.contains("PEXPIRE")
			.doesNotContain("RPUSH");
		assertThat(args.getValue()[0]).isEqualTo(String.valueOf(TTL.toMillis()));
		assertThat(this.expiries).containsEntry(ConversationTranscriptStore.dataKey("s-1", TENANT, USER), TTL);
	}

	/** 字节上限按 payload 的 UTF-8 字节裁剪（不依赖 MEMORY USAGE），保留最新、丢掉最旧。 */
	@Test
	void byteCapTrimsOldestPayloadsWithoutMemoryUsage() throws Exception {
		ConversationTranscriptStore small = new ConversationTranscriptStore(this.redis, new ObjectMapper(), TTL, 100,
				400L);
		List<TranscriptEntry> entries = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			entries.add(TranscriptEntries.user("run-" + i, i, i, "m" + i + ":" + "x".repeat(50)));
		}

		small.append("s-1",  TENANT,  USER,  entries, 1L);
		List<TranscriptEntry> read = small.readPage("s-1",  TENANT,  USER, null, null).entries();

		assertThat(read).isNotEmpty().hasSizeLessThan(10);
		// 正文可区分：保留的必须是"最新的一段连续尾部"，首条正好是 10 - size 号。
		assertThat(read.get(read.size() - 1).text()).as("保留最新").startsWith("m9:");
		assertThat(read.get(0).text()).as("最早保留的是连续尾部的第一条")
			.startsWith("m" + (10 - read.size()) + ":");
		long bytes = 0L;
		for (TranscriptEntry entry : read) {
			bytes += new ObjectMapper().writeValueAsString(entry).getBytes(StandardCharsets.UTF_8).length;
		}
		assertThat(bytes).as("裁剪后不得超过字节上限").isLessThanOrEqualTo(400L);
	}

	/** TTL 无效 ⇒ 拒写并如实返回 false（不能把"没写"报成成功）。 */
	@Test
	void appendReturnsFalseWhenSessionTtlIsInvalid() {
		ConversationTranscriptStore noTtl = new ConversationTranscriptStore(this.redis, new ObjectMapper(), TTL);
		// 构造器会拒绝非正 TTL；这里在合法实例上把运行期 TTL 置零，验证 append 的 fail-closed。
		org.springframework.test.util.ReflectionTestUtils.setField(noTtl, "ttl", Duration.ZERO);

		boolean written = noTtl.append("s-1",  TENANT,  USER,
				List.of(TranscriptEntries.user("run-a", 1L, 1L, "问题")), 1L);

		assertThat(written).isFalse();
		assertThat(this.hashes).isEmpty();
		assertThat(this.values).isEmpty();
	}

	/** 计数键缺失但 HASH/ZSET 已在：一次全量核算，且不重复加本次 added。 */
	@Test
	void missingCounterIsRecomputedFromTheExistingList() {
		String data = ConversationTranscriptStore.dataKey("s-1", TENANT, USER);
		String order = ConversationTranscriptStore.orderKey("s-1", TENANT, USER);
		String meta = ConversationTranscriptStore.metaKey("s-1", TENANT, USER);
		this.hashes.put(data, new LinkedHashMap<>(Map.of("id-a", "a".repeat(30), "id-b", "b".repeat(20))));
		this.zsets.put(order, new LinkedHashMap<>(Map.of("id-a", 1L, "id-b", 2L)));

		this.store.append("s-1",  TENANT,  USER,  List.of(TranscriptEntries.user("run-a", 1L, 1L, "m0")), 1L);

		assertThat(this.hashes.get(data)).hasSize(3);
		assertThat(this.zsets.get(order)).hasSize(3);
		assertThat(this.hashes.get(meta)).containsKey("bytes");
	}

	/** 计数为负值 ⇒ 视为不可信并全量核算，避免永久少算。 */
	@Test
	void nonPositiveCounterIsRecomputed() {
		String data = ConversationTranscriptStore.dataKey("s-1", TENANT, USER);
		String order = ConversationTranscriptStore.orderKey("s-1", TENANT, USER);
		String meta = ConversationTranscriptStore.metaKey("s-1", TENANT, USER);
		this.hashes.put(data, new LinkedHashMap<>(Map.of("id-a", "a".repeat(30))));
		this.zsets.put(order, new LinkedHashMap<>(Map.of("id-a", 1L)));
		this.hashes.put(meta, new LinkedHashMap<>(Map.of("bytes", "-1")));

		this.store.append("s-1",  TENANT,  USER,  List.of(TranscriptEntries.user("run-a", 1L, 1L, "m0")), 1L);
		this.store.append("s-1",  TENANT,  USER,  List.of(TranscriptEntries.user("run-b", 2L, 2L, "m1")), 1L);
		assertThat(this.hashes.get(data)).hasSize(3);
		assertThat(this.zsets.get(order)).hasSize(3);
		assertThat(this.hashes.get(meta).get("bytes")).isNotEqualTo("-1");
	}


	private static long byteSum(Collection<String> payloads) {
		long sum = 0L;
		for (String payload : payloads) {
			sum += payload.getBytes(StandardCharsets.UTF_8).length;
		}
		return sum;
	}

	/** 无身份 ⇒ 不写 legacy 命名空间（不同租户同名 sessionId 不得互相读到）。 */
	@Test
	void missingIdentityDoesNotWriteLegacy() {
		boolean written = this.store.append("s-1",  0L,  0L,
				List.of(TranscriptEntries.user("run-a", 1L, 1L, "问题")), 1L);

		assertThat(written).isFalse();
		assertThat(this.hashes).isEmpty();
		assertThat(this.values).isEmpty();
		assertThat(this.store.readPage("s-1", 0L, 0L, null, null).entries().isEmpty()).isTrue();
	}

	/** 追加模型是显式的：一次 append 只写本次条目，不会隐式复制/补写 memory 基线。 */
	@Test
	void appendWritesOnlyTheProvidedEntriesWithoutImplicitBaseline() {
		this.store.append("s-1",  TENANT,  USER,
				List.of(TranscriptEntries.user("run-a", 100L, 100L, "只写这一条")), 1L);

		assertThat(this.store.readPage("s-1",  TENANT,  USER, null, null).entries())
			.extracting(TranscriptEntry::entryId)
			.containsExactly("run-a:user");
	}

	@Test
	void readReturnsOnlyTheRunScopedEntriesThatWereAppended() {
		List<TranscriptEntry> entries = List.of(
				TranscriptEntries.user("run-a", 100L, 100L, "旧问"),
				TranscriptEntries.assistant("run-a", 100L, 101L, "旧答", null, null));
		this.store.append("s-1",  TENANT,  USER,  entries, 1L);

		assertThat(this.store.readPage("s-1",  TENANT,  USER, null, null).entries())
			.extracting(TranscriptEntry::entryId)
			.containsExactlyInAnyOrder("run-a:user", "run-a:assistant");
	}

}
