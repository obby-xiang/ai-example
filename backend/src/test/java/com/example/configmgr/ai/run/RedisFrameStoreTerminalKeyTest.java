package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisFrameStoreTerminalKeyTest {

    @Test
    void appendScriptUsesSevenKeysInTheSameRunSlot() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AtomicReference<List<String>> scriptKeys = new AtomicReference<>();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            scriptKeys.set(List.copyOf(invocation.getArgument(1)));
            return List.of(1L, 0L, 0L, "APPENDED", "{\"type\":\"delta\",\"seq\":1}");
        });
        try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
            RedisFrameStore store = new RedisFrameStore(redis, new ObjectMapper(), new AiProperties());
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", "delta");

            FrameStore.AppendResult result = store.appendAndAllocateSeq("run-slot", frame,
                    RedisFrameStoreTerminalKeyTest::payload);

            assertThat(result.seq()).isEqualTo(1L);
            assertThat(scriptKeys.get()).containsExactly(store.seqKey("run-slot"), store.streamKey("run-slot"),
                    RunStore.runScopedKey(RedisFrameStore.ACK_PREFIX, "run-slot"), store.trimKey("run-slot"),
                    RunStore.runScopedKey(RunStore.FENCE_RUN_PREFIX, "run-slot"),
                    RunStore.runScopedKey(RunStore.RUN_STATE_PREFIX, "run-slot"),
                    RunStore.runScopedKey(RunStore.RUN_PREFIX, "run-slot"));
            int slot = FakeLeaseRedis.slot(store.streamKey("run-slot"));
            assertThat(scriptKeys.get()).allSatisfy(key -> assertThat(FakeLeaseRedis.slot(key)).isEqualTo(slot));
        }
    }

    private static byte[] payload(Map<String, Object> frame) {
        try {
            return new ObjectMapper().writeValueAsBytes(frame);
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

}
