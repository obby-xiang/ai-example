package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
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

/** B3：压缩序号原子递增 + 事件 LIST 有界/TTL。 */
class RunStoreContextEventsTest {

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

	@SuppressWarnings("unchecked")
	private final ListOperations<String, String> listOps = mock(ListOperations.class);

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunStore store = new RunStore(this.redis, this.mapper, new AiProperties());

	@Test
	void compSeqIsAtomicPerSession() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			when(this.redis.opsForHash()).thenReturn(this.hashOps);
			when(this.hashOps.increment(anyString(), eq("compSeq"), eq(1L))).thenReturn(1L, 2L);

			assertThat(this.store.nextCompactionSeq("s-1")).isEqualTo(1L);
			assertThat(this.store.nextCompactionSeq("s-1")).isEqualTo(2L);
			verify(this.redis, org.mockito.Mockito.atLeast(2)).expire(anyString(),
					org.mockito.ArgumentMatchers.any(Duration.class));
		}
	}

	@Test
	void eventsAreBoundedAndTtlTouched() throws Exception {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			when(this.redis.opsForList()).thenReturn(this.listOps);
			when(this.listOps.range(anyString(), anyLong(), anyLong())).thenReturn(List.of(
					this.mapper.writeValueAsString(Map.of("eventId", "r:c1", "runId", "r", "atMs", 1))));

			this.store.appendEvent("s-1", Map.of("eventId", "r:c1"));

			@SuppressWarnings("rawtypes")
			ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
			ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
			verify(this.redis).execute(script.capture(), anyList(), args.capture());
			assertThat(script.getValue().getScriptAsString())
				.as("push / LTRIM / PEXPIRE 同一脚本，消除无 TTL 窗口")
				.contains("LPUSH").contains("LTRIM").contains("PEXPIRE");
			assertThat(args.getValue())
				.containsExactly(this.mapper.writeValueAsString(Map.of("eventId", "r:c1")), "200",
						String.valueOf(new AiProperties().getSessionTtl().toMillis()));
			assertThat(this.store.events("s-1")).hasSize(1);
			assertThat(this.store.events("s-1").get(0)).containsEntry("eventId", "r:c1");
		}
	}

}
