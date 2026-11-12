package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedisFrameStoreFakeTerminalAtomicityTest {

    private static final String RUN_ID = "fake-terminal-atomicity";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FakeFrameRedis redis = new FakeFrameRedis();

    private final RedisFrameStore store = new RedisFrameStore(this.redis.redis, MAPPER, new AiProperties());

    private RequestContextUtils.SafeCloseable identity;

    @BeforeEach
    void bindIdentity() {
        this.identity = RequestContextUtils.useContext(1L, 101L);
    }

    @AfterEach
    void closeIdentity() {
        this.identity.close();
    }

    @Test
    void fakeExactWriteKeepsExplicitSeqAndSamePayloadIdempotent() {
        seedSnapshot(RunSnapshot.RUNNING, 0L);
        Map<String, Object> frame = frame("delta", 7L, "same");

        FrameStore.AppendResult first = this.store.appendAndGetSeq(RUN_ID, frame, payloadFactory());
        FrameStore.AppendResult retry = this.store.appendAndGetSeq(RUN_ID, frame, payloadFactory());

        assertThat(first.seq()).isEqualTo(7L);
        assertThat(retry.seq()).isEqualTo(7L);
        assertThat(retry.outcome()).isEqualTo(FrameStore.AppendOutcome.IDEMPOTENT);
        assertThat(this.redis.streamIds(this.store.streamKey(RUN_ID))).containsExactly("7-0");
    }

    @Test
    void fakeExactWriteRejectsDifferentPayloadAtSameSeq() {
        seedSnapshot(RunSnapshot.RUNNING, 0L);
        this.store.appendAndGetSeq(RUN_ID, frame("delta", 7L, "first"), payloadFactory());

        FrameStore.AppendResult conflict = this.store.appendAndGetSeq(RUN_ID, frame("delta", 7L, "different"),
                payloadFactory());

        assertThat(conflict.seq()).isZero();
        assertThat(this.redis.streamIds(this.store.streamKey(RUN_ID))).containsExactly("7-0");
    }

    @Test
    void fakeExactWriteRejectsSeqBehindTail() {
        seedSnapshot(RunSnapshot.RUNNING, 0L);
        this.store.appendAndGetSeq(RUN_ID, frame("delta", 7L, "first"), payloadFactory());

        FrameStore.AppendResult behind = this.store.appendAndGetSeq(RUN_ID, frame("delta", 6L, "behind"),
                payloadFactory());

        assertThat(behind.seq()).isZero();
        assertThat(this.redis.streamIds(this.store.streamKey(RUN_ID))).containsExactly("7-0");
    }

    @Test
    void fakeTerminalGateRejectsOrdinaryFrameAfterTerminal() {
        this.store.appendAndAllocateSeq(RUN_ID, terminal("done", 1L), payloadFactory());
        seedSnapshot(RunSnapshot.DONE, 0L);

        FrameStore.AppendResult after = this.store.appendAndGetSeq(RUN_ID, frame("delta", 2L, "after"),
                payloadFactory());

        assertThat(after.seq()).isEqualTo(1L);
        assertThat(after.outcome()).isEqualTo(FrameStore.AppendOutcome.TERMINAL_AFTER);
        assertThat(this.redis.streamIds(this.store.streamKey(RUN_ID))).containsExactly("1-0");
    }

    @Test
    void fakeLegacyExactWriterWithoutSnapshotCannotCreateAStream() {
        this.store.append(RUN_ID, frame("delta", 1L, "anonymous"));

        assertThat(this.redis.streamIds(this.store.streamKey(RUN_ID))).isEmpty();
    }

    @Test
    void fakeTailWithoutTypeFailsClosed() {
        seedRawTail("{\"seq\":1}");

        FrameStore.AppendResult result = this.store.appendAndAllocateSeq(RUN_ID, frame("delta", 2L, "after"),
                payloadFactory());

        assertThat(result.seq()).isZero();
        assertThat(this.redis.streamIds(this.store.streamKey(RUN_ID))).containsExactly("1-0");
    }

    @Test
    void fakeTailWithNonStringTypeFailsClosed() {
        seedRawTail("{\"type\":1,\"seq\":1}");

        FrameStore.AppendResult result = this.store.appendAndAllocateSeq(RUN_ID, frame("delta", 2L, "after"),
                payloadFactory());

        assertThat(result.seq()).isZero();
        assertThat(this.redis.streamIds(this.store.streamKey(RUN_ID))).containsExactly("1-0");
    }

    private void seedRawTail(String body) {
        this.redis.connection.execute("XADD", this.store.streamKey(RUN_ID).getBytes(), "1-0".getBytes(),
                "seq".getBytes(), "1".getBytes(), "frame".getBytes(), body.getBytes());
    }

    private void seedSnapshot(String status, long epoch) {
        this.redis.rawValues.put(RunStore.runScopedKey(RunStore.RUN_PREFIX, RUN_ID),
                "{\"status\":\"" + status + "\",\"runEpoch\":\"" + epoch + "\"}");
    }

    private static Map<String, Object> frame(String type, long seq, String text) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("seq", seq);
        frame.put("atMs", 100L);
        frame.put("text", text);
        return frame;
    }

    private static Map<String, Object> terminal(String type, long seq) {
        Map<String, Object> frame = frame(type, seq, "terminal");
        frame.put("cancelled", false);
        return frame;
    }

    private static FrameStore.PayloadFactory payloadFactory() {
        return frame -> {
            try {
                return MAPPER.writeValueAsBytes(frame);
            }
            catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        };
    }

}
