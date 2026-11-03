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
			String key = keys.get(0);
			String bytesKey = keys.get(1);
			String readyKey = keys.get(2);
			String truncatedKey = keys.get(3);
			Duration ttl = Duration.ofMillis(Long.parseLong(String.valueOf(raw[2])));
			int maxEntries = Integer.parseInt(String.valueOf(raw[3]));
			long maxBytes = Long.parseLong(String.valueOf(raw[4]));
			boolean existed = this.lists.containsKey(key);
			List<String> list = this.lists.computeIfAbsent(key, ignored -> new ArrayList<>());
			for (int i = 6; i < raw.length; i++) {
				list.add(String.valueOf(raw[i]));
			}
			long bytes;
			boolean recount = false;
			if (!existed) {
				bytes = Long.parseLong(String.valueOf(raw[5]));
			}
			else if (this.values.containsKey(bytesKey)) {
				long prev = Long.parseLong(this.values.get(bytesKey));
				if (prev > 0) {
					bytes = prev + Long.parseLong(String.valueOf(raw[5]));
				}
				else {
					bytes = 0;
					recount = true;
				}
			}
			else {
				bytes = 0;
				recount = true;
			}
			boolean trimmed = false;
			if (list.size() > maxEntries) {
				int drop = list.size() - maxEntries;
				bytes -= byteSum(list.subList(0, drop));
				this.lists.put(key, new ArrayList<>(list.subList(drop, list.size())));
				list = this.lists.get(key);
				trimmed = true;
			}
			if (!list.isEmpty() && (recount || bytes <= 0)) {
				bytes = byteSum(list);
			}
			while (bytes > maxBytes && list.size() > 1) {
				int chunk = Math.min(list.size() - 1, 200);
				bytes -= byteSum(list.subList(0, chunk));
				this.lists.put(key, new ArrayList<>(list.subList(chunk, list.size())));
				list = this.lists.get(key);
				trimmed = true;
			}
			this.values.put(bytesKey, String.valueOf(Math.max(0L, bytes)));
			this.expiries.put(key, ttl);
			this.expiries.put(bytesKey, ttl);
			this.expiries.put(readyKey, ttl);
			this.expiries.put(truncatedKey, ttl);
			if (trimmed) {
				this.values.put(truncatedKey, "1");
			}
			return 1L;
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

		this.store.append("s-1", TENANT, USER, List.of(pending));
		this.store.append("s-1", TENANT, USER, List.of(executed));

		ConversationTranscriptStore.ReadResult read = this.store.read("s-1", TENANT, USER);
		assertThat(read.entries()).hasSize(1);
		assertThat(read.entries().get(0).status()).isEqualTo(PendingToolCall.EXECUTED);
		assertThat(read.entries().get(0).result()).isEqualTo("任务列表");
	}

	@Test
	void lastWinsOnToolStatusUpdate() {
		this.store.append("s-1", TENANT, USER, List.of(TranscriptEntries.tool("run-a", 100L, 1L, "c-1", "x",
				PendingToolCall.KIND_BACKEND, PendingToolCall.PENDING, null)));
		this.store.append("s-1", TENANT, USER, List.of(TranscriptEntries.tool("run-a", 100L, 2L, "c-1", "x",
				PendingToolCall.KIND_BACKEND, PendingToolCall.CANCELLED, "已取消")));

		assertThat(this.store.read("s-1", TENANT, USER).entries())
			.singleElement()
			.extracting(TranscriptEntry::status, TranscriptEntry::result)
			.containsExactly(PendingToolCall.CANCELLED, "已取消");
	}

	/** 挂起轮晚收尾：写序 B→A，但按 turnStartedAtMs 读成 A→B。 */
	@Test
	void ordersParkedAndNewRunByTurnStart() {
		this.store.append("s-1", TENANT, USER,
				List.of(TranscriptEntries.user("run-new", 200L, 20L, "新轮")));
		this.store.append("s-1", TENANT, USER,
				List.of(TranscriptEntries.user("run-old", 100L, 30L, "旧轮")));

		assertThat(this.store.read("s-1", TENANT, USER).entries())
			.extracting(TranscriptEntry::text)
			.containsExactly("旧轮", "新轮");
	}

	@Test
	void tenantScopedKeysDiffer() {
		this.store.append("s-1", 1L, 101L, List.of(TranscriptEntries.user("run-a", 1L, 1L, "租户一")));
		this.store.append("s-1", 2L, 202L, List.of(TranscriptEntries.user("run-a", 1L, 1L, "租户二")));

		assertThat(ConversationTranscriptStore.dataKey("s-1", 1L, 101L))
			.isNotEqualTo(ConversationTranscriptStore.dataKey("s-1", 2L, 202L));
		assertThat(this.store.read("s-1", 1L, 101L).entries()).extracting(TranscriptEntry::text)
			.containsExactly("租户一");
		assertThat(this.store.read("s-1", 2L, 202L).entries()).extracting(TranscriptEntry::text)
			.containsExactly("租户二");
	}

	@Test
	void ttlRefreshedOnReadAndWrite() {
		this.store.append("s-1", TENANT, USER, List.of(TranscriptEntries.user("run-a", 1L, 1L, "问题")));
		String key = ConversationTranscriptStore.dataKey("s-1", TENANT, USER);

		assertThat(this.expiries).containsEntry(key, TTL);
		this.expiries.remove(key);
		this.store.read("s-1", TENANT, USER);

		assertThat(this.expiries).containsEntry(key, TTL);
	}

	/** 容量上限：最多 MAX_ENTRIES 条，超出从最旧裁并留下中性 truncated 标记。 */
	@Test
	void boundedTrimMarksTruncation() {
		List<TranscriptEntry> entries = new ArrayList<>();
		for (int i = 0; i < ConversationTranscriptStore.MAX_ENTRIES + 1; i++) {
			entries.add(TranscriptEntries.user("run-" + i, i, i, "m" + i));
		}

		this.store.append("s-1", TENANT, USER, entries);

		ConversationTranscriptStore.ReadResult read = this.store.read("s-1", TENANT, USER);
		assertThat(read.entries()).hasSize(ConversationTranscriptStore.MAX_ENTRIES);
		assertThat(read.entries().get(0).text()).as("最旧一条被裁").isEqualTo("m1");
		assertThat(read.truncated()).isTrue();
	}

	/** 追加与 TTL 同一个脚本原子完成：不存在"已 push、无 TTL"的窗口。 */
	@Test
	void appendSetsTtlInTheSameScriptAsPush() {
		this.store.append("s-1", TENANT, USER, List.of(TranscriptEntries.user("run-a", 1L, 1L, "问题")));

		@SuppressWarnings("rawtypes")
		org.mockito.ArgumentCaptor<RedisScript> script = org.mockito.ArgumentCaptor.forClass(RedisScript.class);
		org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
		verify(this.redis).execute(script.capture(), anyList(), args.capture());

		assertThat(script.getValue().getScriptAsString()).contains("RPUSH").contains("PEXPIRE");
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

		small.append("s-1", TENANT, USER, entries);
		List<TranscriptEntry> read = small.read("s-1", TENANT, USER).entries();

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
		ConversationTranscriptStore noTtl = new ConversationTranscriptStore(this.redis, new ObjectMapper(),
				Duration.ZERO);

		boolean written = noTtl.append("s-1", TENANT, USER,
				List.of(TranscriptEntries.user("run-a", 1L, 1L, "问题")));

		assertThat(written).isFalse();
		assertThat(this.lists).isEmpty();
		assertThat(this.values).isEmpty();
	}

	/** 计数键缺失但 LIST 已在：一次全量核算，且不重复加本次 added。 */
	@Test
	void missingCounterIsRecomputedFromTheExistingList() {
		String data = ConversationTranscriptStore.dataKey("s-1", TENANT, USER);
		this.lists.put(data, new ArrayList<>(List.of("a".repeat(30), "b".repeat(20))));

		this.store.append("s-1", TENANT, USER, List.of(TranscriptEntries.user("run-a", 1L, 1L, "m0")));

		assertThat(this.values.get(ConversationTranscriptStore.bytesKey("s-1", TENANT, USER)))
			.isEqualTo(String.valueOf(byteSum(this.lists.get(data))));
	}

	/** 计数为 0/负值（非正数）⇒ 视为不可信并全量核算，避免永久少算。 */
	@Test
	void nonPositiveCounterIsRecomputed() {
		String data = ConversationTranscriptStore.dataKey("s-1", TENANT, USER);
		String bytesKey = ConversationTranscriptStore.bytesKey("s-1", TENANT, USER);
		this.lists.put(data, new ArrayList<>(List.of("a".repeat(30))));
		this.values.put(bytesKey, "-7");

		this.store.append("s-1", TENANT, USER, List.of(TranscriptEntries.user("run-a", 1L, 1L, "m0")));
		assertThat(this.values.get(bytesKey)).isEqualTo(String.valueOf(byteSum(this.lists.get(data))));

		this.values.put(bytesKey, "0");
		this.store.append("s-1", TENANT, USER, List.of(TranscriptEntries.user("run-b", 2L, 2L, "m1")));
		assertThat(this.values.get(bytesKey)).isEqualTo(String.valueOf(byteSum(this.lists.get(data))));
	}

	/** purge 后只剩计数键：LIST 重建时丢弃旧计数，只按本次 payload 计。 */
	@Test
	void counterOnlyResidueIsResetWhenTheListIsGone() {
		String data = ConversationTranscriptStore.dataKey("s-1", TENANT, USER);
		String bytesKey = ConversationTranscriptStore.bytesKey("s-1", TENANT, USER);
		this.values.put(bytesKey, "999999");

		this.store.append("s-1", TENANT, USER, List.of(TranscriptEntries.user("run-a", 1L, 1L, "m0")));

		assertThat(this.values.get(bytesKey)).isEqualTo(String.valueOf(byteSum(this.lists.get(data))));
		assertThat(this.values.get(bytesKey)).isNotEqualTo("999999");
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
		boolean written = this.store.append("s-1", 0L, 0L,
				List.of(TranscriptEntries.user("run-a", 1L, 1L, "问题")));

		assertThat(written).isFalse();
		assertThat(this.lists).isEmpty();
		assertThat(this.values).isEmpty();
		assertThat(this.store.read("s-1", 0L, 0L).isEmpty()).isTrue();
	}

	/** baseline 只复制一次：第二次 ensureBaseline 不重复追加。 */
	@Test
	void baselineCopiedOnce() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-1");
		pending.setName("list_tasks");
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setResultText("原文结果");
		List<Message> memory = List.of(
				new UserMessage("旧问题"),
				AssistantMessage.builder()
					.content("")
					.toolCalls(List.of(new AssistantMessage.ToolCall("c-1", "function", "list_tasks", "{}")))
					.build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "list_tasks",
							com.example.configmgr.ai.context.ToolResultClearing.placeholder("list_tasks", 900, "c-1"))))
					.build());

		this.store.ensureBaseline("s-1", TENANT, USER, memory);
		this.store.ensureBaseline("s-1", TENANT, USER, memory);

		List<TranscriptEntry> entries = this.store.read("s-1", TENANT, USER).entries();
		// 工具结果是 L1 技术占位符 ⇒ baseline 不写它（宁可缺一条，不把技术串当用户原文）
		assertThat(entries).filteredOn(entry -> "baseline".equals(entry.kind())).hasSize(2);
		assertThat(entries).filteredOn(entry -> "TOOL".equals(entry.role())).isEmpty();
	}

	@Test
	void readConvergesLegacyBaselineAndRunDoubleWrites() {
		List<TranscriptEntry> duplicated = new ArrayList<>(
				TranscriptEntries.baseline(List.of(new UserMessage("旧问"), new AssistantMessage("旧答"))));
		duplicated.add(TranscriptEntries.user("run-a", 100L, 100L, "旧问"));
		duplicated.add(TranscriptEntries.assistant("run-a", 100L, 101L, "旧答", null, null));
		this.store.append("s-1", TENANT, USER, duplicated);

		assertThat(this.store.read("s-1", TENANT, USER).entries())
			.extracting(TranscriptEntry::entryId)
			.containsExactly("run-a:user", "run-a:assistant");
	}

}
