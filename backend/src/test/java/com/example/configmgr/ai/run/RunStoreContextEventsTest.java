package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.context.RedisContextStateStore;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话上下文事件（{@code ai:ctx:events:<sessionId>}）：<b>一次 Lua 内 LPUSH + LTRIM + PEXPIRE</b>，
 * 按 {@code eventId} 去重，读口按窗口上限解析。
 *
 * <p>归属迁移：事件日志的写/读口已从 {@link RunStore} 迁到 {@link RedisContextStateStore}
 * （键仍由 {@code RunStore.contextEventsKey} 拼；会话压缩的“压缩序号”计数器已被
 * {@code compactionId} + {@code pendingCompaction} 取代，故不再有 {@code nextCompactionSeq}）。
 */
class RunStoreContextEventsTest {

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ListOperations<String, String> listOps = mock(ListOperations.class);

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final RedisContextStateStore store = new RedisContextStateStore(this.redis, this.mapper, this.properties);

	@Test
	void contextEventAppendIsOneAtomicScriptWithBoundTtlAndEventIdDedupe() throws Exception {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			this.store.appendEvent("s-1", Map.of("eventId", "r:c1", "runId", "r", "atMs", 1));

			@SuppressWarnings("rawtypes")
			ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
			ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
			verify(this.redis).execute(script.capture(), anyList(), args.capture());

			assertThat(script.getValue().getScriptAsString())
				.as("push / LTRIM / PEXPIRE 同一脚本，消除无 TTL 窗口")
				.contains("LPUSH").contains("LTRIM").contains("PEXPIRE")
				.as("按 eventId 去重：重放不得追加重复事件")
				.contains("eventId");

			Map<?, ?> payload = this.mapper.readValue(String.valueOf(args.getValue()[0]), Map.class);
			assertThat(payload.get("eventId")).isEqualTo("r:c1");
			assertThat(args.getValue()[1]).as("窗口上限固定（有界事件列表）").isEqualTo("200");
			assertThat(args.getValue()[2])
				.as("TTL 与会话同滑动")
				.isEqualTo(String.valueOf(this.properties.getEffectiveSessionTtl().toMillis()));
		}
	}

	@Test
	void eventsReadBackParsesStoredJsonWithTheBoundedWindow() throws Exception {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			when(this.redis.opsForList()).thenReturn(this.listOps);
			when(this.listOps.range(anyString(), anyLong(), anyLong())).thenReturn(List.of(
					this.mapper.writeValueAsString(Map.of("eventId", "r:c1", "runId", "r", "atMs", 1))));

			List<Map<String, Object>> events = this.store.events("s-1");

			assertThat(events).as("读口解析 LIST 里的 JSON 原文").hasSize(1);
			assertThat(events.get(0)).containsEntry("eventId", "r:c1");
			verify(this.listOps).range(anyString(), eq(0L), eq(199L));
		}
	}

}
