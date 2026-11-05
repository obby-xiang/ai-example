package com.example.configmgr.perf.redis;

import java.util.ArrayList;
import java.util.List;

/** Bounded background-key generation with exact single-key cleanup on failure. */
public final class BackgroundBatchWriter {

    public static final int BATCH_SIZE = 500;

    private BackgroundBatchWriter() {
    }

    public static void write(CountingRedis redis, String namespace, String bucket, int count, long ttlSeconds) {
        if (count < 0) {
            throw new IllegalArgumentException("background key count must be non-negative");
        }
        int written = 0;
        int attempted = 0;
        try {
            for (int start = 0; start < count; start += BATCH_SIZE) {
                int end = Math.min(count, start + BATCH_SIZE);
                attempted = end;
                List<String[]> commands = new ArrayList<>(end - start);
                for (int i = start; i < end; i++) {
                    commands.add(new String[] { "SET", KeyShapes.backgroundKey(namespace, bucket, i), "bg", "EX",
                            String.valueOf(ttlSeconds) });
                }
                redis.pipelineRaw(commands);
                written = end;
            }
        }
        catch (RuntimeException failure) {
            try {
                delete(redis, namespace, bucket, attempted);
            }
            catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void delete(CountingRedis redis, String namespace, String bucket, int count) {
        for (int start = 0; start < count; start += BATCH_SIZE) {
            int end = Math.min(count, start + BATCH_SIZE);
            List<String[]> commands = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                commands.add(new String[] { "DEL", KeyShapes.backgroundKey(namespace, bucket, i) });
            }
            redis.pipelineRaw(commands);
        }
    }
}
