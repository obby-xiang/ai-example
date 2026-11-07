package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.history.ConversationTranscriptStore;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.memory.SessionKeyCodec;
import com.example.configmgr.ai.observe.LogSampling;
import com.example.configmgr.ai.session.SessionPurgeGuard;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Slf4j
@Component
public class SessionActivityRenewal {

    public static final Duration THROTTLE_WINDOW = Duration.ofSeconds(60);

    private static final int INDEX_PAGE_SIZE = 64;

    private static final int MAX_SESSION_RUNS = 128;

    private static final int MAX_ENUMERATED_ITEMS = 1024;

    private static final int MAX_RENEWAL_KEYS = 4096;

    private static final RedisScript<Long> RELEASE_WINDOW = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);

    public enum Result {
        RENEWED,
        SKIPPED,
        FAILED
    }

    private final StringRedisTemplate redis;

    private final AiProperties properties;

    private SessionPurgeGuard purgeGuard;

    public SessionActivityRenewal(StringRedisTemplate redis, AiProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Autowired(required = false)
    void setPurgeGuard(SessionPurgeGuard purgeGuard) {
        this.purgeGuard = purgeGuard;
    }

    public boolean isActivityRenewed(long tenantId, long userId, String sessionId, String runId, String dataKey) {
        return renewActivity(tenantId, userId, sessionId, runId, dataKey) == Result.RENEWED;
    }

    public Result renewActivity(long tenantId, long userId, String sessionId, String runId, String dataKey) {
        if (!TenantKeyScope.hasIdentity(tenantId, userId) || StringUtils.isBlank(sessionId)
                || StringUtils.isBlank(dataKey)) {
            return Result.FAILED;
        }
        try {
            if (this.purgeGuard != null && this.purgeGuard.isArmed(sessionId, tenantId, userId)) {
                return Result.SKIPPED;
            }
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("session-activity-purge-guard")) {
                log.warn("session activity purge guard read failed sessionId={}", sessionId, ex);
            }
            return Result.FAILED;
        }
        String throttleKey = TenantKeyScope.scoped("ai:session:user-activity:", tenantId, userId, sessionId);
        String ownerToken = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = this.redis.opsForValue().setIfAbsent(throttleKey, ownerToken, THROTTLE_WINDOW);
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("session-activity-window")) {
                log.warn("session activity renewal window claim failed sessionId={} runId={}", sessionId, runId, ex);
            }
            return Result.FAILED;
        }
        if (acquired == null) {
            return Result.FAILED;
        }
        if (Boolean.FALSE.equals(acquired)) {
            return Result.SKIPPED;
        }
        if (renewAll(tenantId, userId, sessionId, runId, dataKey)) {
            return Result.RENEWED;
        }
        releaseWindow(throttleKey, ownerToken, sessionId);
        return Result.FAILED;
    }

    private boolean renewAll(long tenantId, long userId, String sessionId, String runId, String dataKey) {
        try {
            Duration ttl = this.properties.getEffectiveSessionTtl();
            Set<String> runIds = new LinkedHashSet<>();
            Set<String> keys = new LinkedHashSet<>();
            EnumerationBudget budget = new EnumerationBudget();
            boolean complete = addRunIds(tenantId, userId, sessionId, runId, runIds, budget);
            try (RunIdentityScope.Scope ignored = RunIdentityScope.open(tenantId, userId)) {
                keys.addAll(sessionKeys(tenantId, userId, sessionId, dataKey));
                for (String historicalRunId : runIds) {
                    addRunKeys(keys, SessionKeyRegistry.Renewal.HEARTBEAT, historicalRunId);
                    addRunKeys(keys, SessionKeyRegistry.Renewal.READ, historicalRunId);
                    addRunKeys(keys, SessionKeyRegistry.Renewal.WRITE, historicalRunId);
                    complete &= addFormIndexKeys(tenantId, userId, historicalRunId, keys, budget);
                }
                complete &= addAttachmentKeys(tenantId, userId, sessionId, keys, budget);
                complete &= addStagingKeys(tenantId, userId, sessionId, keys, budget);
            }
            if (keys.size() > MAX_RENEWAL_KEYS) {
                return false;
            }
            boolean renewed = renewKeys(keys, ttl, sessionId, dataKey);
            return complete && renewed;
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("session-activity-renew-all")) {
                log.warn("session activity renewal assembly failed sessionId={} runId={}", sessionId, runId, ex);
            }
            return false;
        }
    }

    private void releaseWindow(String throttleKey, String ownerToken, String sessionId) {
        try {
            this.redis.execute(RELEASE_WINDOW, List.of(throttleKey), ownerToken);
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("session-activity-window-release")) {
                log.warn("session activity renewal window release failed sessionId={}", sessionId, ex);
            }
        }
    }

    private boolean addRunIds(long tenantId, long userId, String sessionId, String runId, Set<String> runIds,
                              EnumerationBudget budget) {
        boolean complete = budget.addRun(runIds, runId);
        complete &= addCurrentRunId(tenantId, userId, sessionId, runIds, budget);
        complete &= addZsetRunIds(TenantKeyScope.scoped(RunStore.SESSION_ACTIVE_PREFIX, tenantId, userId, sessionId),
                sessionId, "session-active", runIds, budget);
        complete &= addSetRunIds(TenantKeyScope.scoped(RunStore.SESSION_RUNS_PREFIX, tenantId, userId, sessionId),
                sessionId, "session-runs", runIds, budget);
        return complete;
    }

    private boolean addCurrentRunId(long tenantId, long userId, String sessionId, Set<String> runIds,
                                    EnumerationBudget budget) {
        try {
            String current = this.redis.opsForValue()
                    .get(TenantKeyScope.scoped(RunStore.SESSION_CURRENT_PREFIX, tenantId, userId, sessionId));
            return budget.addRun(runIds, current);
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("session-activity-current-run")) {
                log.warn("session activity current-run index read failed sessionId={}", sessionId, ex);
            }
            return false;
        }
    }

    private boolean addZsetRunIds(String key, String sessionId, String index, Set<String> runIds,
                                  EnumerationBudget budget) {
        try {
            for (long from = 0L; !budget.isOverLimit(); from += INDEX_PAGE_SIZE) {
                Set<String> page = this.redis.opsForZSet().range(key, from, from + INDEX_PAGE_SIZE - 1L);
                if (page == null) {
                    return false;
                }
                if (page.isEmpty()) {
                    break;
                }
                for (String member : page) {
                    if (member == null || !budget.addItem() || !budget.addRun(runIds, member)) {
                        return false;
                    }
                }
                if (page.size() < INDEX_PAGE_SIZE) {
                    break;
                }
            }
            return !budget.isOverLimit();
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("session-activity-run-index")) {
                log.warn("session activity run index read failed index={} sessionId={}", index, sessionId, ex);
            }
            return false;
        }
    }

    private boolean addSetRunIds(String key, String sessionId, String index, Set<String> runIds,
                                 EnumerationBudget budget) {
        try (Cursor<String> cursor = this.redis.opsForSet().scan(key, scanOptions())) {
            if (cursor == null) {
                return false;
            }
            while (!budget.isOverLimit() && cursor.hasNext()) {
                String member = cursor.next();
                if (member == null || !budget.addItem() || !budget.addRun(runIds, member)) {
                    return false;
                }
            }
            return !budget.isOverLimit();
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("session-activity-run-index")) {
                log.warn("session activity run index read failed index={} sessionId={}", index, sessionId, ex);
            }
            return false;
        }
    }

    private static ScanOptions scanOptions() {
        return ScanOptions.scanOptions().count(INDEX_PAGE_SIZE).build();
    }

    private static final class EnumerationBudget {

        private int runs;

        private int items;

        private boolean isOverLimit() {
            return this.runs > MAX_SESSION_RUNS || this.items > MAX_ENUMERATED_ITEMS;
        }

        private boolean addRun(Set<String> runIds, String runId) {
            if (StringUtils.isBlank(runId)) {
                return true;
            }
            if (runIds.add(runId)) {
                this.runs++;
            }
            return this.runs <= MAX_SESSION_RUNS;
        }

        private boolean addItem() {
            this.items++;
            return this.items <= MAX_ENUMERATED_ITEMS;
        }
    }

    private Set<String> sessionKeys(long tenantId, long userId, String sessionId, String dataKey) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(dataKey);
        keys.add(TenantKeyScope.scoped("ai:session:", tenantId, userId, sessionId));
        keys.add(TenantKeyScope.scoped(RunStore.SESSION_CURRENT_PREFIX, tenantId, userId, sessionId));
        keys.add(TenantKeyScope.scoped(RunStore.SESSION_RUNS_PREFIX, tenantId, userId, sessionId));
        keys.add(TenantKeyScope.scoped(RunStore.SESSION_ACTIVE_PREFIX, tenantId, userId, sessionId));
        keys.add(TenantKeyScope.scoped(RunStore.CONTEXT_PREFIX, tenantId, userId, sessionId));
        keys.add(TenantKeyScope.scoped(RunStore.CONTEXT_LEDGER_PREFIX, tenantId, userId, sessionId));
        keys.add(TenantKeyScope.scoped("ai:ctx:events:", tenantId, userId, sessionId));
        keys.add(TenantKeyScope.scoped(RunStore.SAFETY_PREFIX, tenantId, userId, sessionId));
        keys.add(TenantKeyScope.scoped(ClientRequestRegistry.KEY_PREFIX, tenantId, userId, sessionId));
        keys.add(TenantKeyScope.scoped("ai:form:att:sess:", tenantId, userId, sessionId));
        keys.add(CreateTaskDraftStore.KEY_PREFIX + "{" + TenantKeyScope.segmentOf(tenantId, userId) + "session:"
                + SessionKeyCodec.escapedId(sessionId) + "}");
        String memoryKey = "chat:mem:" + TenantKeyScope.segmentOf(tenantId, userId)
                + SessionKeyCodec.hashTag(sessionId);
        keys.add(memoryKey);
        keys.add(memoryKey + ":ver");
        keys.add(memoryKey + ":gen");
        String transcriptKey = ConversationTranscriptStore.dataKey(sessionId, tenantId, userId);
        keys.add(transcriptKey);
        keys.add(ConversationTranscriptStore.orderKey(sessionId, tenantId, userId));
        keys.add(ConversationTranscriptStore.metaKey(sessionId, tenantId, userId));
        keys.add(ConversationTranscriptStore.truncatedKey(sessionId, tenantId, userId));
        keys.add(RedisChatMemoryRepository.stagingIndexKey(sessionId));
        keys.add(RunStore.RECOVERY_DUE_INDEX);
        keys.add(RunStore.RECOVERY_DUE_IDENTITY_PREFIX + tenantId + ":" + userId);
        keys.add(RunStore.RUN_LIST_INDEX_PREFIX + tenantId + ":" + userId);
        return keys;
    }

    private static void addRunKeys(Set<String> keys, SessionKeyRegistry.Renewal renewal, String runId) {
        for (Function<String, String> builder : SessionKeyRegistry.renewalKeys(renewal)) {
            keys.add(builder.apply(runId));
        }
    }

    private boolean addFormIndexKeys(long tenantId, long userId, String runId, Set<String> keys,
                                     EnumerationBudget budget) {
        String pendingKey = RunStore.runScopedKey(RunStore.PENDING_PREFIX, runId);
        try (Cursor<Map.Entry<Object, Object>> cursor = this.redis.opsForHash().scan(pendingKey, scanOptions())) {
            if (cursor == null) {
                return false;
            }
            while (!budget.isOverLimit() && cursor.hasNext()) {
                Map.Entry<Object, Object> entry = cursor.next();
                if (entry == null || entry.getKey() == null || !budget.addItem()) {
                    return false;
                }
                keys.add("ai:form:att:form:" + TenantKeyScope.segmentOf(tenantId, userId) + runId + ":"
                        + entry.getKey());
            }
            return !budget.isOverLimit();
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("session-activity-form-index")) {
                log.warn("session activity form index enumeration failed runId={}", runId, ex);
            }
            return false;
        }
    }

    private boolean addAttachmentKeys(long tenantId, long userId, String sessionId, Set<String> keys,
                                      EnumerationBudget budget) {
        String indexKey = TenantKeyScope.scoped("ai:form:att:sess:", tenantId, userId, sessionId);
        try (Cursor<String> cursor = this.redis.opsForSet().scan(indexKey, scanOptions())) {
            if (cursor == null) {
                return false;
            }
            while (!budget.isOverLimit() && cursor.hasNext()) {
                String fileId = cursor.next();
                if (fileId == null || !budget.addItem()) {
                    return false;
                }
                if (StringUtils.isNotBlank(fileId)) {
                    keys.add("ai:form:att:" + TenantKeyScope.segmentOf(tenantId, userId) + fileId);
                    keys.add("ai:form:att:used:" + TenantKeyScope.segmentOf(tenantId, userId) + fileId);
                }
            }
            return !budget.isOverLimit();
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("session-activity-attachments")) {
                log.warn("session activity attachment enumeration failed sessionId={}", sessionId, ex);
            }
            return false;
        }
    }

    private boolean addStagingKeys(long tenantId, long userId, String sessionId, Set<String> keys,
                                   EnumerationBudget budget) {
        String indexKey = RedisChatMemoryRepository.stagingIndexKey(sessionId);
        String stagingPrefix = "chat:mem:staging:" + TenantKeyScope.segmentOf(tenantId, userId)
                + SessionKeyCodec.hashTag(sessionId) + ":";
        try (Cursor<String> cursor = this.redis.opsForSet().scan(indexKey, scanOptions())) {
            if (cursor == null) {
                return false;
            }
            while (!budget.isOverLimit() && cursor.hasNext()) {
                String token = cursor.next();
                if (token == null || !budget.addItem()) {
                    return false;
                }
                if (StringUtils.isNotBlank(token)) {
                    keys.add(stagingPrefix + token);
                }
            }
            return !budget.isOverLimit();
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("session-activity-staging")) {
                log.warn("session activity staging enumeration failed sessionId={}", sessionId, ex);
            }
            return false;
        }
    }

    private boolean renewKeys(Set<String> keys, Duration ttl, String sessionId, String dataKey) {
        boolean success = true;
        boolean dataKeyRenewed = false;
        int missingKeys = 0;
        List<String> ordered = new ArrayList<>(keys);
        for (int from = 0; from < ordered.size(); from += INDEX_PAGE_SIZE) {
            List<String> batch = ordered.subList(from, Math.min(ordered.size(), from + INDEX_PAGE_SIZE));
            try {
                List<Object> results = this.redis.executePipelined((RedisCallback<Object>) connection -> {
                    for (String key : batch) {
                        connection.pExpire(key.getBytes(StandardCharsets.UTF_8), ttl.toMillis());
                    }
                    return null;
                });
                if (results == null || results.size() != batch.size()) {
                    success = false;
                    continue;
                }
                for (int index = 0; index < batch.size(); index++) {
                    Object result = results.get(index);
                    String key = batch.get(index);
                    if (!(result instanceof Boolean)) {
                        success = false;
                    } else if (Boolean.TRUE.equals(result)) {
                        if (dataKey.equals(key)) {
                            dataKeyRenewed = true;
                        }
                    } else {
                        missingKeys++;
                        if (dataKey.equals(key)) {
                            success = false;
                        }
                    }
                }
            } catch (RuntimeException ex) {
                success = false;
                if (LogSampling.canAcquire("session-activity-renew-batch")) {
                    log.warn("session activity renewal batch failed sessionId={} keys={}", sessionId, batch.size(), ex);
                }
            }
        }
        if (missingKeys > 0 && log.isDebugEnabled()) {
            log.debug("session activity renewal skipped missing keys sessionId={} count={}", sessionId, missingKeys);
        }
        return success && dataKeyRenewed;
    }
}
