package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class RedisFrameStoreTerminalAtomicityIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LettuceConnectionFactory connectionFactory;

    private StringRedisTemplate redis;

    private RequestContextUtils.SafeCloseable identity;

    private String runId;

    private RedisFrameStore store;

    @BeforeEach
    void setUp() {
        this.identity = RequestContextUtils.useContext(1L, 101L);
        this.runId = "terminal-atomic-" + UUID.randomUUID();
        this.connectionFactory = new LettuceConnectionFactory("127.0.0.1", 6379);
        this.connectionFactory.afterPropertiesSet();
        this.connectionFactory.start();
        this.redis = new StringRedisTemplate(this.connectionFactory);
        this.redis.afterPropertiesSet();
        this.store = new RedisFrameStore(this.redis, MAPPER, new AiProperties());
    }

    @AfterEach
    void tearDown() {
        if (this.redis != null) {
            this.redis.delete(List.of(this.store.seqKey(this.runId), this.store.streamKey(this.runId),
                    this.store.trimKey(this.runId), RunStore.runScopedKey(RedisFrameStore.ACK_PREFIX, this.runId),
                    RunStore.runScopedKey(RunStore.FENCE_RUN_PREFIX, this.runId),
                    RunStore.runScopedKey(RunStore.RUN_STATE_PREFIX, this.runId),
                    RunStore.runScopedKey(RunStore.RUN_PREFIX, this.runId)));
        }
        if (this.connectionFactory != null) {
            this.connectionFactory.stop();
            this.connectionFactory.destroy();
        }
        if (this.identity != null) {
            this.identity.close();
        }
    }

    @Test
    void competingTerminalWritersHaveOneLogicalWinnerAndNoLaterFrames() throws Exception {
        RedisFrameStore other = new RedisFrameStore(this.redis, MAPPER, new AiProperties());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<FrameStore.AppendResult> left = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return this.store.appendAndAllocateSeq(this.runId, terminal("done", "DONE", 100L), payloadFactory());
            });
            Future<FrameStore.AppendResult> right = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return other.appendAndAllocateSeq(this.runId, terminal("done", "DONE", 101L), payloadFactory());
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            FrameStore.AppendResult leftResult = left.get(10, TimeUnit.SECONDS);
            FrameStore.AppendResult rightResult = right.get(10, TimeUnit.SECONDS);

            FrameStore.AppendResult conflict = other.appendAndAllocateSeq(this.runId,
                    terminal("error", "AI_UPSTREAM_ERROR", 102L), payloadFactory());
            FrameStore.AppendResult after = other.appendAndAllocateSeq(this.runId, ordinary("delta", 103L),
                    payloadFactory());

            assertThat(rightResult.seq()).as("重复同终帧回执已胜出的序号，而不是另发一帧")
                    .isEqualTo(leftResult.seq());
            assertThat(rightResult.payload()).as("重复同终帧回执已胜出的归档原文")
                    .isEqualTo(leftResult.payload());
            assertThat(List.of(outcome(leftResult), outcome(rightResult)))
                    .containsExactlyInAnyOrder("APPENDED", "TERMINAL_IDEMPOTENT");
            assertThat(conflict.seq()).as("冲突回执携带 winner 序号，但归档不得新增").isEqualTo(1L);
            assertThat(outcome(conflict)).isEqualTo("TERMINAL_CONFLICT");
            assertThat(after.seq()).as("终后回执携带 winner 序号，但普通帧不得追加").isEqualTo(1L);
            assertThat(outcome(after)).isEqualTo("TERMINAL_AFTER");
            List<Map<String, Object>> archived = this.store.readFrom(this.runId, 0L, 0);
            assertThat(archived).as("四次逻辑调用只能留下一个终帧，且它是最后帧").hasSize(1);
            assertThat(archived.getFirst().get("type")).isEqualTo("done");
        }
        finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void ordinaryFramesKeepAtomicSequenceAndReplay() {
        RedisFrameStore other = new RedisFrameStore(this.redis, MAPPER, new AiProperties());

        FrameStore.AppendResult first = this.store.appendAndAllocateSeq(this.runId, ordinary("delta", 100L),
                payloadFactory());
        FrameStore.AppendResult second = other.appendAndAllocateSeq(this.runId, ordinary("message_end", 101L),
                payloadFactory());

        assertThat(first.seq()).isEqualTo(1L);
        assertThat(second.seq()).isEqualTo(2L);
        assertThat(this.store.readFrom(this.runId, 0L, 0)).extracting(frame -> frame.get("type"))
                .containsExactly("delta", "message_end");
        assertThat(this.store.readFrom(this.runId, 1L, 0))
                .extracting(frame -> ((Number) frame.get("seq")).longValue()).containsExactly(2L);
    }

    @Test
    void staleFenceCannotAppendTerminalOrOrdinaryFrames() {
        String fenceKey = RunStore.runScopedKey(RunStore.FENCE_RUN_PREFIX, this.runId);
        this.redis.opsForValue().set(fenceKey, "2");

        FrameStore.AppendResult staleTerminal = this.store.appendAndAllocateSeq(this.runId,
                terminal("done", "DONE", 100L), payloadFactory(), 1L);
        FrameStore.AppendResult staleOrdinary = this.store.appendAndAllocateSeq(this.runId, ordinary("delta", 101L),
                payloadFactory(), 1L);

        assertThat(staleTerminal.seq()).isZero();
        assertThat(staleOrdinary.seq()).isZero();
        assertThat(this.redis.opsForValue().get(fenceKey)).isEqualTo("2");
        assertThat(this.redis.opsForValue().get(this.store.seqKey(this.runId))).isNull();
        assertThat(this.redis.opsForStream().size(this.store.streamKey(this.runId))).isZero();
    }

    @Test
    void corruptArchiveTailFailsClosedWithoutAllocatingASequence() {
        seedRawTail("1-0", "{not-json");

        FrameStore.AppendResult result = this.store.appendAndAllocateSeq(this.runId,
                terminal("done", "DONE", 100L), payloadFactory());

        assertThat(result.seq()).isZero();
        assertThat(this.redis.opsForValue().get(this.store.seqKey(this.runId))).isNull();
        assertThat(this.redis.opsForStream().size(this.store.streamKey(this.runId))).isEqualTo(1L);
    }

    @Test
    void missingArchivedTerminalKeepsTheExistingSnapshotToTerminalRepairOrder() {
        seedRunState(RunSnapshot.DONE, 0L);

        FrameStore.AppendResult messageEnd = this.store.appendAndAllocateSeq(this.runId,
                ordinary("message_end", 100L), payloadFactory());
        FrameStore.AppendResult transcriptError = this.store.appendAndAllocateSeq(this.runId,
                terminal("error", "AI_TRANSCRIPT_INCOMPLETE", 101L), payloadFactory());
        FrameStore.AppendResult lateDone = this.store.appendAndAllocateSeq(this.runId,
                terminal("done", "DONE", 102L), payloadFactory());
        FrameStore.AppendResult lateOrdinary = this.store.appendAndAllocateSeq(this.runId, ordinary("delta", 103L),
                payloadFactory());

        assertThat(messageEnd.seq()).as("DONE 快照之后的既有 message_end 必须先于终帧保留").isEqualTo(1L);
        assertThat(transcriptError.seq()).as("归档尚无终帧时 transcript 降级 error 仍可补写").isEqualTo(2L);
        assertThat(lateDone.seq()).isEqualTo(2L);
        assertThat(outcome(lateDone)).isEqualTo("TERMINAL_CONFLICT");
        assertThat(lateOrdinary.seq()).isEqualTo(2L);
        assertThat(outcome(lateOrdinary)).isEqualTo("TERMINAL_AFTER");
        assertThat(this.store.readFrom(this.runId, 0L, 0)).extracting(frame -> frame.get("type"))
                .containsExactly("message_end", "error");
    }

    @Test
    void redisTailReadFailureDoesNotFallBackToAnUnverifiedAppend() {
        this.redis.opsForValue().set(this.store.streamKey(this.runId), "not-a-stream");

        FrameStore.AppendResult result = this.store.appendAndAllocateSeq(this.runId,
                terminal("done", "DONE", 100L), payloadFactory());

        assertThat(result.seq()).isZero();
        assertThat(this.redis.opsForValue().get(this.store.seqKey(this.runId))).isNull();
        assertThat(this.redis.opsForValue().get(this.store.streamKey(this.runId))).isEqualTo("not-a-stream");
    }

    @Test
    void higherEpochAndRunningCannotContinueAfterTerminal() {
        FrameStore.AppendResult historical = this.store.appendAndAllocateSeq(this.runId,
                terminal("done", "DONE", 100L), payloadFactory());
        assertThat(historical.seq()).isEqualTo(1L);
        seedRunState(RunSnapshot.RUNNING, 1L);
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.FENCE_RUN_PREFIX, this.runId), "2");

        FrameStore.AppendResult current = this.store.appendAndAllocateSeq(this.runId, ordinary("delta", 101L),
                payloadFactory(), 2L);
        FrameStore.AppendResult lateOldGeneration = this.store.appendAndAllocateSeq(this.runId, ordinary("delta", 102L),
                payloadFactory(), 1L);

        assertThat(current.seq()).isEqualTo(1L);
        assertThat(outcome(current)).isEqualTo("TERMINAL_AFTER");
        assertThat(lateOldGeneration.seq()).isZero();
        assertThat(this.store.readFrom(this.runId, 0L, 0)).extracting(frame -> frame.get("type"))
                .containsExactly("done");
    }

    @Test
    void sameGenerationRunningCompetingTerminalStillHasOnlyOneWinner() throws Exception {
        seedRunState(RunSnapshot.RUNNING, 1L);
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.FENCE_RUN_PREFIX, this.runId), "1");
        RedisFrameStore other = new RedisFrameStore(this.redis, MAPPER, new AiProperties());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<FrameStore.AppendResult> done = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return this.store.appendAndAllocateSeq(this.runId, terminal("done", "DONE", 100L), payloadFactory(), 1L);
            });
            Future<FrameStore.AppendResult> error = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return other.appendAndAllocateSeq(this.runId, terminal("error", "AI_UPSTREAM_ERROR", 101L),
                        payloadFactory(), 1L);
            });
            start.countDown();
            FrameStore.AppendResult doneResult = done.get(10, TimeUnit.SECONDS);
            FrameStore.AppendResult errorResult = error.get(10, TimeUnit.SECONDS);
            FrameStore.AppendResult after = other.appendAndAllocateSeq(this.runId, ordinary("delta", 102L),
                    payloadFactory(), 1L);

            assertThat(List.of(doneResult.seq(), errorResult.seq())).containsExactlyInAnyOrder(1L, 1L);
            assertThat(after.seq()).isEqualTo(1L);
            assertThat(outcome(after)).isEqualTo("TERMINAL_AFTER");
            assertThat(this.store.readFrom(this.runId, 0L, 0)).hasSize(1);
        }
        finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void corruptRunSnapshotCannotReopenTerminal() {
        this.store.appendAndAllocateSeq(this.runId, terminal("done", "DONE", 100L), payloadFactory());
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.RUN_STATE_PREFIX, this.runId), RunSnapshot.RUNNING);
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.RUN_PREFIX, this.runId), "{not-json");
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.FENCE_RUN_PREFIX, this.runId), "2");

        FrameStore.AppendResult result = this.store.appendAndAllocateSeq(this.runId, ordinary("delta", 101L),
                payloadFactory(), 2L);

        assertThat(result.seq()).isEqualTo(1L);
        assertThat(outcome(result)).isEqualTo("TERMINAL_AFTER");
        assertThat(this.store.readFrom(this.runId, 0L, 0)).hasSize(1);
    }

    @Test
    void missingRunEpochCannotReopenTerminal() {
        this.store.appendAndAllocateSeq(this.runId, terminal("done", "DONE", 100L), payloadFactory());
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.RUN_STATE_PREFIX, this.runId), RunSnapshot.RUNNING);
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.RUN_PREFIX, this.runId),
                "{\"status\":\"RUNNING\"}");
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.FENCE_RUN_PREFIX, this.runId), "2");

        FrameStore.AppendResult result = this.store.appendAndAllocateSeq(this.runId, ordinary("delta", 101L),
                payloadFactory(), 2L);

        assertThat(result.seq()).isEqualTo(1L);
        assertThat(outcome(result)).isEqualTo("TERMINAL_AFTER");
        assertThat(this.store.readFrom(this.runId, 0L, 0)).hasSize(1);
    }

    @Test
    void appendRejectsOrdinaryFrameAfterTerminal() {
        this.store.appendAndAllocateSeq(this.runId, terminal("done", "DONE", 100L), payloadFactory());
        seedRunState(RunSnapshot.DONE, 0L);
        Map<String, Object> late = ordinary("delta", 101L);
        late.put("seq", 2L);

        this.store.append(this.runId, late);

        assertThat(this.store.readFrom(this.runId, 0L, 0)).hasSize(1);
    }

    @Test
    void twoArgAppendAndGetSeqRejectsOrdinaryFrameAfterTerminal() {
        this.store.appendAndAllocateSeq(this.runId, terminal("done", "DONE", 100L), payloadFactory());
        seedRunState(RunSnapshot.DONE, 0L);
        Map<String, Object> late = ordinary("delta", 101L);
        late.put("seq", 2L);

        FrameStore.AppendResult result = this.store.appendAndGetSeq(this.runId, late);

        assertThat(result.seq()).isEqualTo(1L);
        assertThat(outcome(result)).isEqualTo("TERMINAL_AFTER");
        assertThat(this.store.readFrom(this.runId, 0L, 0)).hasSize(1);
    }

    @Test
    void threeArgAppendAndGetSeqRejectsOrdinaryFrameAfterTerminal() {
        this.store.appendAndAllocateSeq(this.runId, terminal("done", "DONE", 100L), payloadFactory());
        seedRunState(RunSnapshot.DONE, 0L);
        Map<String, Object> late = ordinary("delta", 101L);
        late.put("seq", 2L);

        FrameStore.AppendResult result = this.store.appendAndGetSeq(this.runId, late, payloadFactory());

        assertThat(result.seq()).isEqualTo(1L);
        assertThat(outcome(result)).isEqualTo("TERMINAL_AFTER");
        assertThat(this.store.readFrom(this.runId, 0L, 0)).hasSize(1);
    }

    @Test
    void appendRejectsStaleFence() {
        seedRunState(RunSnapshot.RUNNING, 1L);
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.FENCE_RUN_PREFIX, this.runId), "2");
        Map<String, Object> stale = ordinary("delta", 101L);
        stale.put("seq", 1L);

        this.store.append(this.runId, stale);

        assertThat(this.redis.opsForStream().size(this.store.streamKey(this.runId))).isZero();
    }

    @Test
    void twoArgAppendAndGetSeqRejectsStaleFence() {
        seedRunState(RunSnapshot.RUNNING, 1L);
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.FENCE_RUN_PREFIX, this.runId), "2");
        Map<String, Object> stale = ordinary("delta", 101L);
        stale.put("seq", 1L);

        FrameStore.AppendResult result = this.store.appendAndGetSeq(this.runId, stale);

        assertThat(result.seq()).isZero();
        assertThat(this.redis.opsForStream().size(this.store.streamKey(this.runId))).isZero();
    }

    @Test
    void threeArgAppendAndGetSeqRejectsStaleFence() {
        seedRunState(RunSnapshot.RUNNING, 1L);
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.FENCE_RUN_PREFIX, this.runId), "2");
        Map<String, Object> stale = ordinary("delta", 101L);
        stale.put("seq", 1L);

        FrameStore.AppendResult result = this.store.appendAndGetSeq(this.runId, stale, payloadFactory());

        assertThat(result.seq()).isZero();
        assertThat(this.redis.opsForStream().size(this.store.streamKey(this.runId))).isZero();
    }

    @Test
    void explicitSeqRetryWithTheSamePayloadStaysIdempotent() {
        seedRunState(RunSnapshot.RUNNING, 0L);
        Map<String, Object> frame = ordinary("delta", 100L);
        frame.put("seq", 7L);

        this.store.append(this.runId, frame);
        this.store.append(this.runId, frame);
        FrameStore.AppendResult twoArg = this.store.appendAndGetSeq(this.runId, frame);
        FrameStore.AppendResult threeArg = this.store.appendAndGetSeq(this.runId, frame, payloadFactory());

        assertThat(twoArg.seq()).isEqualTo(7L);
        assertThat(threeArg.seq()).isEqualTo(7L);
        assertThat(outcome(threeArg)).isEqualTo("IDEMPOTENT");
        assertThat(this.store.readFrom(this.runId, 0L, 0)).hasSize(1);
    }

    @Test
    void explicitEpochOldWriterIsRejectedAfterSnapshotAndFenceAdvance() {
        seedRunState(RunSnapshot.RUNNING, 2L);
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.FENCE_RUN_PREFIX, this.runId), "2");
        Map<String, Object> frame = ordinary("delta", 100L);
        frame.put("seq", 1L);

        FrameStore.AppendResult result = this.store.appendAndGetSeq(this.runId, frame, payloadFactory(), 1L);
        this.store.append(this.runId, frame, 1L);

        assertThat(result.seq()).isZero();
        assertThat(this.redis.opsForStream().size(this.store.streamKey(this.runId))).isZero();
    }

    @Test
    void explicitEpochCurrentWriterIsAccepted() {
        seedRunState(RunSnapshot.RUNNING, 2L);
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.FENCE_RUN_PREFIX, this.runId), "2");
        Map<String, Object> frame = ordinary("delta", 100L);
        frame.put("seq", 1L);

        FrameStore.AppendResult result = this.store.appendAndGetSeq(this.runId, frame, payloadFactory(), 2L);

        assertThat(result.seq()).isEqualTo(1L);
        assertThat(this.redis.opsForStream().size(this.store.streamKey(this.runId))).isEqualTo(1L);
    }

    @Test
    void explicitEpochWithoutSnapshotCannotCreateAStream() {
        Map<String, Object> frame = ordinary("delta", 100L);
        frame.put("seq", 1L);

        FrameStore.AppendResult result = this.store.appendAndGetSeq(this.runId, frame, payloadFactory(), 2L);

        assertThat(result.seq()).isZero();
        assertThat(this.redis.opsForStream().size(this.store.streamKey(this.runId))).isZero();
    }

    @Test
    void legacyExactWriterCannotUseANewSnapshotEpochAsItsOwnAuthority() {
        seedRunState(RunSnapshot.RUNNING, 2L);
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.FENCE_RUN_PREFIX, this.runId), "2");
        Map<String, Object> frame = ordinary("delta", 100L);
        frame.put("seq", 1L);

        FrameStore.AppendResult result = this.store.appendAndGetSeq(this.runId, frame, payloadFactory());

        assertThat(result.seq()).isZero();
        assertThat(this.redis.opsForStream().size(this.store.streamKey(this.runId))).isZero();
    }

    @Test
    void legacyExactWriterWithoutSnapshotCannotCreateAStream(CapturedOutput output) {
        Map<String, Object> frame = ordinary("delta", 100L);
        frame.put("seq", 1L);

        this.store.append(this.runId, frame);

        assertThat(this.redis.opsForStream().size(this.store.streamKey(this.runId))).isZero();
        assertThat(output.getAll()).contains("archive degraded").contains("event log archive failed");
    }

    @Test
    void legacyExactWriterRejectsCorruptSnapshot() {
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.RUN_PREFIX, this.runId), "{not-json");
        Map<String, Object> frame = ordinary("delta", 100L);
        frame.put("seq", 1L);

        FrameStore.AppendResult result = this.store.appendAndGetSeq(this.runId, frame, payloadFactory());

        assertThat(result.seq()).isZero();
        assertThat(this.redis.opsForStream().size(this.store.streamKey(this.runId))).isZero();
    }

    @Test
    void legacyExactWriterRejectsSnapshotMissingRunEpoch() {
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.RUN_PREFIX, this.runId),
                "{\"status\":\"RUNNING\"}");
        Map<String, Object> frame = ordinary("delta", 100L);
        frame.put("seq", 1L);

        FrameStore.AppendResult result = this.store.appendAndGetSeq(this.runId, frame, payloadFactory());

        assertThat(result.seq()).isZero();
        assertThat(this.redis.opsForStream().size(this.store.streamKey(this.runId))).isZero();
    }

    @Test
    void cancelledDoneConflictsWithAnAlreadyWinningSuccessfulDone() {
        FrameStore.AppendResult done = this.store.appendAndAllocateSeq(this.runId,
                terminal("done", "DONE", 100L), payloadFactory());
        Map<String, Object> cancelled = terminal("done", "DONE", 101L);
        cancelled.put("cancelled", true);

        FrameStore.AppendResult conflict = this.store.appendAndAllocateSeq(this.runId, cancelled, payloadFactory());

        assertThat(done.seq()).isEqualTo(1L);
        assertThat(conflict.seq()).isEqualTo(1L);
        assertThat(outcome(conflict)).isEqualTo("TERMINAL_CONFLICT");
        assertThat(this.store.readFrom(this.runId, 0L, 0)).hasSize(1);
    }

    @Test
    void unconfirmedErrorConflictsWithFailureAtTheSameCode() {
        FrameStore.AppendResult failure = this.store.appendAndAllocateSeq(this.runId,
                terminal("error", "AI_REDIS_UNAVAILABLE", 100L), payloadFactory());
        Map<String, Object> unconfirmed = terminal("error", "AI_REDIS_UNAVAILABLE", 101L);
        unconfirmed.put("unconfirmed", true);

        FrameStore.AppendResult conflict = this.store.appendAndAllocateSeq(this.runId, unconfirmed, payloadFactory());

        assertThat(failure.seq()).isEqualTo(1L);
        assertThat(conflict.seq()).isEqualTo(1L);
        assertThat(outcome(conflict)).isEqualTo("TERMINAL_CONFLICT");
        assertThat(this.store.readFrom(this.runId, 0L, 0)).hasSize(1);
    }

    private void seedRunState(String status, long runEpoch) {
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.RUN_STATE_PREFIX, this.runId), status);
        this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.RUN_PREFIX, this.runId),
                "{\"status\":\"" + status + "\",\"runEpoch\":\"" + runEpoch + "\"}");
    }

    private void seedRawTail(String entryId, String body) {
        this.redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.execute("XADD", this.store.streamKey(this.runId).getBytes(StandardCharsets.UTF_8),
                    entryId.getBytes(StandardCharsets.UTF_8), "seq".getBytes(StandardCharsets.UTF_8),
                    "1".getBytes(StandardCharsets.UTF_8), "frame".getBytes(StandardCharsets.UTF_8),
                    body.getBytes(StandardCharsets.UTF_8));
            return null;
        });
    }

    private static String outcome(FrameStore.AppendResult result) {
        return result.outcome().name();
    }

    private static Map<String, Object> terminal(String type, String code, long atMs) {
        Map<String, Object> frame = ordinary(type, atMs);
        if ("done".equals(type)) {
            frame.put("cancelled", false);
        }
        else {
            frame.put("code", code);
            frame.put("message", "terminal failure");
        }
        return frame;
    }

    private static Map<String, Object> ordinary(String type, long atMs) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("atMs", atMs);
        frame.put("runId", "terminal-atomic-test");
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
