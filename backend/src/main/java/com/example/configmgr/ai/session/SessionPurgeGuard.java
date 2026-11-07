package com.example.configmgr.ai.session;

import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 会话销毁的<b>写回守卫</b>（{@code ai:purge:guard:<sessionId>}）。
 *
 * <h2>它挡的是什么</h2>
 * 删除会话记忆是一条"先清键、后收尾"的路径：在跑轮次的终局会做整窗记忆收敛
 * （{@code ChatMemoryRepository#saveAll}），把 {@code chat:mem:*} 重新写回来。销毁会话必须
 * 关掉这个窗口 —— 否则用户点了"销毁"，几秒后旧轮收盘又把历史写回同名键。
 *
 * <p>
 * 因此本守卫被<b>所有"会把会话数据写回来"的写口</b>消费：{@code RedisChatMemoryRepository#saveAll}
 * 在写之前问一次 {@link #isArmed(String)}，命中即拒绝写回（WARN 留痕）。它<b>不</b>拦读、不拦删除：
 * 销毁流程本身仍要能删键，重复销毁也必须能继续。
 *
 * <h2>Redis 有限 TTL 与锚点 purging 标记</h2>
 * Redis guard/pending 使用 {@code app.ai.session-ttl}（默认 24h），承担写回拦截窗口和重试调度。
 * arm 先把会话锚点合并为 {@code purging=true}、{@code purgingOwner=<purgeId>|} 并按原剩余 TTL 写回，
 * 再写 guard；无锚点时创建只含身份、sessionId 和这两个标记的有限 TTL 锚点。isArmed 只读取
 * purging 布尔值，正常会话的数据键不再被误判为半删除残留。锚点不存在或没有 purging 一律放行；
 * 非法 JSON、非布尔标记和读取异常均 fail-closed。回滚通过单键 Lua CAS，仅在 purgingOwner 与本次
 * purgeId 加竖线的前缀完全一致时移除两个标记，保留锚点其他字段和剩余 TTL，旧 owner 不得清除新 owner。
 * 正常完成后由 {@link #retainOrArmIfOwner} 进入有限保留期；完成后的保留守卫不被历史查询续期。
 *
 * <h2>身份隔离（2026-10-30）</h2>
 * 守卫键 = {@code ai:purge:guard:<tenantId>:<userId>:<sessionId>}，pending 键同口径
 * （{@link TenantKeyScope}）。跨租户/跨用户即使 sessionId 相同，也<b>不是同一个墓碑</b>：
 * 一个身份的销毁不会让另一个身份的写回被拒，也不会被另一个身份的 pending 误判成"已销毁"。
 * 身份缺失即失败：守卫键/台账键都不会退化成不含身份段的键。
 *
 * <h2>失败口径：销毁场景 fail-closed</h2>
 * {@link #isArmed(String)} 读失败<b>不再</b>返回 {@code false}，而是抛出
 * {@link com.example.configmgr.common.AiStateUnavailableException}：
 * "读不出来"不等于"没装守卫"。若按未装守卫放行，正在收尾的旧轮/历史写口就可能在本会话
 * 刚被销毁后把数据写回来 —— 这正是守卫要防的事。抛出让写口<b>拒绝本次写入</b>，
 * 调用方（记忆收敛会吞掉并 WARN；历史写口按 agent-39 的约定不吞）自行决定可观测性，
 * 但没有任何一条路径能在守卫状态不可知时"悄悄复活"数据。
 * 销毁流程自己的 {@link #arm(String, String, Duration)} 失败仍如实抛出，由服务中止删除
 * （见 {@code AiSessionPurgeService} 的"先装守卫、再删数据"顺序）；{@code disarmQuietly}
 * 只用于"删除任何数据之前就失败"的回滚，且按 purgeId 前缀 CAS（删不到新一轮/另一实例的守卫），
 * 不改变这条语义。
 */
@Slf4j
@Component
public class SessionPurgeGuard {

    /**
     * 守卫键前缀（诊断/取证可直读）。
     */
    public static final String KEY_PREFIX = "ai:purge:guard:";

    /**
     * durable pending 键前缀（未完成期间即使守卫 TTL 到期，isArmed 仍为 true）。
     */
    public static final String PENDING_PREFIX = "ai:purge:pending:";

    /**
     * 完成后的“保留期”守卫：只续 TTL，不改变 owner；若守卫已过期则按当前 purgeId 重新装。
     * 只有当前值以 {@code <purgeId>|} 开头才允许写，旧重试不能覆盖新守卫。
     */
    private static final RedisScript<Long> RETAIN_OR_ARM = new DefaultRedisScript<>("""
            -- PURGE_GUARD_RETAIN
            local current = redis.call('GET', KEYS[1])
            if current and string.sub(current, 1, string.len(ARGV[1])) ~= ARGV[1] then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);

    /**
     * 撤销守卫（<b>owner 条件</b>）：只有当前值仍以 {@code <purgeId>|} 开头才 DEL。
     * 用于"删任何数据之前就失败"的回滚 —— 旧 purgeId 不能删掉新一轮装的守卫。
     */
    private static final RedisScript<Long> DISARM_IF_OWNER = new DefaultRedisScript<>("""
            -- PURGE_GUARD_DISARM
            local current = redis.call('GET', KEYS[1])
            if current and string.sub(current, 1, string.len(ARGV[1])) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    /**
     * 只回滚同一 purgeId 的锚点标记；其他 owner、坏 JSON 或锚点缺失均不修改。
     */
    private static final RedisScript<Long> DISARM_ANCHOR_IF_OWNER = new DefaultRedisScript<>("""
            -- PURGE_ANCHOR_DISARM_OWNER
            local raw = redis.call('GET', KEYS[1])
            if not raw then return 0 end
            local ok, rec = pcall(cjson.decode, raw)
            if not ok or type(rec) ~= 'table' then return 0 end
            if rec['purgingOwner'] ~= ARGV[1] then return 0 end
            rec['purging'] = nil
            rec['purgingOwner'] = nil
            local ttl = redis.call('PTTL', KEYS[1])
            if not ttl or ttl <= 0 then ttl = tonumber(ARGV[2]) end
            redis.call('SET', KEYS[1], cjson.encode(rec), 'PX', ttl)
            return 1
            """, Long.class);


    private final StringRedisTemplate redis;

    private final ObjectMapper objectMapper;

    public SessionPurgeGuard(StringRedisTemplate redis) {
        this(redis, new ObjectMapper());
    }

    @Autowired
    public SessionPurgeGuard(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 守卫键名（<b>带当前身份</b>；无上下文 = 失败）。
     */
    public String keyFor(String sessionId) {
        return TenantKeyScope.scoped(KEY_PREFIX, sessionId);
    }

    /**
     * 守卫键名（显式身份；销毁编排/用例用，不读线程上下文）。
     */
    public static String keyFor(String sessionId, long tenantId, long userId) {
        return TenantKeyScope.scoped(KEY_PREFIX, tenantId, userId, sessionId);
    }

    /**
     * durable pending 键名（与 {@code SessionPurgePendingStore} 同一键形）。
     */
    static String pendingKey(String sessionId) {
        return TenantKeyScope.scoped(PENDING_PREFIX, sessionId);
    }

    static String pendingKey(String sessionId, long tenantId, long userId) {
        return TenantKeyScope.scoped(PENDING_PREFIX, tenantId, userId, sessionId);
    }

    /**
     * 装守卫（销毁流程在删除任何数据之前调用）。先把锚点合并为 purging=true 并记录 purgingOwner，
     * 再写 {@code purgeId|requestedBy}；锚点写失败不会写 guard，guard 写失败时锚点仍 fail-closed。
     */
    public void arm(String sessionId, String purgeId, Duration ttl) {
        Duration effective = AiSessionService.boundedSessionDataTtl(ttl);
        TenantUser identity = RequestContextUtils.require();
        String anchorKey = AiSessionService.keyFor(sessionId, identity);
        ObjectNode anchor = readAnchor(anchorKey, sessionId);
        if (anchor == null) {
            anchor = this.objectMapper.createObjectNode();
            anchor.put("sessionId", sessionId);
            anchor.put("tenantId", identity.tenantId());
            anchor.put("userId", identity.userId());
        }
        anchor.put("purging", true);
        anchor.put("purgingOwner", purgeOwnerPrefix(purgeId));
        writeAnchor(anchorKey, anchor, remainingTtl(anchorKey, effective));
        this.redis.opsForValue().set(keyFor(sessionId), purgeId, effective);
    }

    private static String purgeOwnerPrefix(String purgeId) {
        int separator = purgeId.indexOf('|');
        return separator >= 0 ? purgeId.substring(0, separator + 1) : purgeId + "|";
    }

    private ObjectNode readAnchor(String key, String sessionId) {
        try {
            String raw = this.redis.opsForValue().get(key);
            if (raw == null) {
                return null;
            }
            JsonNode parsed = this.objectMapper.readTree(raw);
            if (!(parsed instanceof ObjectNode anchor)) {
                throw new IllegalArgumentException("Session anchor is not a JSON object");
            }
            return anchor;
        } catch (Exception ex) {
            throw new com.example.configmgr.common.AiStateUnavailableException(
                    "Cannot read session anchor sessionId=" + sessionId, ex);
        }
    }

    private Duration remainingTtl(String key, Duration fallback) {
        try {
            Long remaining = this.redis.getExpire(key, TimeUnit.MILLISECONDS);
            return remaining == null || remaining <= 0L ? fallback : Duration.ofMillis(remaining);
        } catch (RuntimeException ex) {
            throw new com.example.configmgr.common.AiStateUnavailableException("Cannot read session anchor TTL", ex);
        }
    }

    private void writeAnchor(String key, ObjectNode anchor, Duration ttl) {
        try {
            this.redis.opsForValue().set(key, this.objectMapper.writeValueAsString(anchor), ttl);
        } catch (Exception ex) {
            throw new com.example.configmgr.common.AiStateUnavailableException("Cannot write session anchor", ex);
        }
    }

    /**
     * 该会话是否处于"已销毁/正在销毁"状态。
     *
     * @throws com.example.configmgr.common.AiStateUnavailableException 读失败：
     *                                                                  <b>销毁场景 fail-closed</b> —— 不得当作"未装守卫"继续写回（见类注释）。
     */
    public boolean isArmed(String sessionId) {
        TenantUser identity = com.example.configmgr.ai.tenant.RequestContextUtils.require();
        return isArmed(sessionId, identity.tenantId(), identity.userId());
    }

    public boolean isArmed(String sessionId, long tenantId, long userId) {
        if (StringUtils.isBlank(sessionId)) {
            return false;
        }
        try {
            if (Boolean.TRUE.equals(this.redis.hasKey(keyFor(sessionId, tenantId, userId)))
                    || Boolean.TRUE.equals(this.redis.hasKey(pendingKey(sessionId, tenantId, userId)))) {
                return true;
            }
            ObjectNode anchor = readAnchor(AiSessionService.keyFor(sessionId, tenantId, userId), sessionId);
            if (anchor == null) {
                return false;
            }
            JsonNode purging = anchor.get("purging");
            if (purging == null) {
                return false;
            }
            if (!purging.isBoolean()) {
                throw new IllegalArgumentException("Session anchor purging marker is not boolean");
            }
            return purging.booleanValue();
        } catch (RuntimeException ex) {
            log.warn("Failed to read session purge guard sessionId={} (fail-closed)", sessionId, ex);
            throw new com.example.configmgr.common.AiStateUnavailableException(
                    "Cannot confirm session purge guard state (fail-closed) sessionId=" + sessionId, ex);
        }
    }

    /**
     * 删除全部完成后的守卫收尾：续/装有限保留期 TTL，且只允许当前 purgeId 的 owner 操作。
     *
     * @return true = 已续期/已装；false = 守卫已被另一个 purgeId 接管（旧重试不得改新守卫）
     * @throws com.example.configmgr.common.AiStateUnavailableException Redis 不可用（不得 blind 继续）
     */
    public boolean retainOrArmIfOwner(String sessionId, String purgeId, String actor, Duration ttl) {
        return isRetainedOrArmedIfOwner(sessionId, purgeId, actor, ttl);
    }

    public boolean isRetainedOrArmedIfOwner(String sessionId, String purgeId, String actor, Duration ttl) {
        if (StringUtils.isBlank(sessionId)) {
            return false;
        }
        Duration effective = AiSessionService.boundedSessionDataTtl(ttl);
        String ownerPrefix = purgeId + "|";
        String value = ownerPrefix + StringUtils.defaultIfBlank(actor, "unknown");
        try {
            Long result = this.redis.execute(RETAIN_OR_ARM, List.of(keyFor(sessionId)), ownerPrefix, value,
                    String.valueOf(effective.toMillis()));
            return result != null && result > 0L;
        } catch (RuntimeException ex) {
            throw new com.example.configmgr.common.AiStateUnavailableException(
                    "Failed to finalize session purge guard sessionId=" + sessionId, ex);
        }
    }

    /**
     * 仅在"销毁在删任何数据之前就失败"时撤销守卫（避免把正常会话误锁成不可写）。
     *
     * <p><b>owner 条件</b>：只有守卫值仍以 {@code <purgeId>|} 开头才删。重复 DELETE 的中止回滚
     * 因此不会拆掉上一轮（或另一实例）装的守卫 —— 那个守卫还在保护的正是上一轮没删完的数据。
     * owner 校验成功后才执行锚点 CAS：只有 purgingOwner 等于本次 purgeId 加竖线的前缀才移除
     * purging 和 purgingOwner，其他字段和剩余 TTL 原样保留；CAS=0 或 Redis 异常均返回 false，不放行。
     *
     * @return true = guard 与同 owner 锚点均已回滚；false = 非 owner、守卫不存在或锚点 CAS 失败
     */
    public boolean disarmQuietly(String sessionId, String purgeId) {
        return isDisarmedQuietly(sessionId, purgeId);
    }

    public boolean isDisarmedQuietly(String sessionId, String purgeId) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(purgeId)) {
            return false;
        }
        try {
            Long deleted = this.redis.execute(DISARM_IF_OWNER, List.of(keyFor(sessionId)), purgeId + "|");
            if (deleted == null || deleted <= 0L) {
                return false;
            }
            Long rolledBack = this.redis.execute(DISARM_ANCHOR_IF_OWNER, List.of(AiSessionService.keyFor(sessionId)),
                    purgeId + "|", Long.toString(AiSessionService.boundedSessionDataTtl(null).toMillis()));
            return rolledBack != null && rolledBack > 0L;
        } catch (RuntimeException ex) {
            log.warn("Failed to disarm session purge guard sessionId={} purgeId={} (guard self-clears after TTL)", sessionId, purgeId, ex);
            return false;
        }
    }

}
