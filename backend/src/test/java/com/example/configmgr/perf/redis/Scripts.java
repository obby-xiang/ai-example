package com.example.configmgr.perf.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 候选协议的 Lua 脚本。standalone 与 Cluster 共用同一份脚本；只有连接配置不同。
 *
 * <p>多键脚本的 KEYS 全部由 {@link KeyShapes} 生成并带同一 hash tag；任何跨槽键面都在
 * {@link CountingRedis#checkSameSlot} 先失败。单键 Lua（TYPE+DEL、purge 租约）不要求同槽。
 */
public final class Scripts {

	public static final DefaultRedisScript<Long> RUN_DELETE = script("""
			-- PERF_RUN_DELETE: 13 个 run 键 + 同槽 xterminal，一次原子 DEL
			local deleted = 0
			for i = 1, #KEYS do
			  deleted = deleted + redis.call('DEL', KEYS[i])
			end
			return deleted
			""", Long.class);

	public static final DefaultRedisScript<Long> STAGING_DELETE = script("""
			-- PERF_STAGING_DELETE: KEYS[1]=索引，其余=staging 键；ARGV=索引成员（token）
			local deleted = 0
			for i = 2, #KEYS do
			  deleted = deleted + redis.call('DEL', KEYS[i])
			end
			for i = 1, #ARGV do
			  redis.call('SREM', KEYS[1], ARGV[i])
			end
			if redis.call('SCARD', KEYS[1]) == 0 then
			  redis.call('DEL', KEYS[1])
			end
			return deleted
			""", Long.class);

	public static final DefaultRedisScript<Long> TYPE_DEL = script("""
			-- PERF_TYPE_DEL: 单键 TYPE+DEL（有界 pipeline 的单元）
			-- ARGV[1] = 期望类型；类型不符返回 -1 且不删（调用方必须标 skipped/unknown）
			if redis.call('EXISTS', KEYS[1]) == 0 then
			  return 0
			end
			local actual = redis.call('TYPE', KEYS[1])
			if type(actual) == 'table' then
			  actual = actual['ok']
			end
			if actual ~= ARGV[1] then
			  return -1
			end
			return redis.call('DEL', KEYS[1])
			""", Long.class);

	public static final DefaultRedisScript<Long> RUN_CONVERGE = script("""
			-- PERF_RUN_CONVERGE: 仅当快照仍处于 expected status 时，把终态与 xterminal 一起落库
			local raw = redis.call('GET', KEYS[1])
			if not raw then
			  return 0
			end
			local ok, obj = pcall(cjson.decode, raw)
			if not ok or type(obj) ~= 'table' or obj['status'] ~= ARGV[1] then
			  return 0
			end
			local ttl = ARGV[5]
			redis.call('SET', KEYS[1], ARGV[2], 'PX', ttl)
			redis.call('SET', KEYS[2], ARGV[3], 'PX', ttl)
			if ARGV[4] ~= '' then
			  redis.call('SET', KEYS[3], ARGV[4], 'PX', ttl)
			end
			return 1
			""", Long.class);

	public static final DefaultRedisScript<java.util.List> PURGE_ACQUIRE = script("""
			-- PERF_PURGE_ACQUIRE: 会话级单飞（lease/pending/guard 同槽）
			-- ARGV: owner, purgeId, leaseTtlMs, guardTtlMs, nowMs, sessionId, attempts
			local now = tonumber(ARGV[5])
			local cur = redis.call('GET', KEYS[1])
			local curEpoch = 0
			local curExpires = 0
			if cur then
			  local e, o, p, exp = string.match(cur, '^([^|]+)|([^|]+)|([^|]+)|([^|]+)$')
			  if e then curEpoch = tonumber(e) or 0 end
			  if exp then curExpires = tonumber(exp) or 0 end
			  if curExpires > now then
			    return {0, curEpoch, 'BUSY'}
			  end
			end
			local pendingEpoch = 0
			local raw = redis.call('GET', KEYS[2])
			if raw then
			  local ok, obj = pcall(cjson.decode, raw)
			  if ok and type(obj) == 'table' and obj['epoch'] then
			    pendingEpoch = tonumber(obj['epoch']) or 0
			  end
			end
			local nextEpoch = math.max(pendingEpoch, curEpoch) + 1
			local leaseValue = tostring(nextEpoch) .. '|' .. ARGV[1] .. '|' .. ARGV[2] .. '|'
			    .. tostring(now + tonumber(ARGV[3]))
			redis.call('SET', KEYS[1], leaseValue, 'PX', ARGV[3])
			local pending = {
			  sessionId = ARGV[6], purgeId = ARGV[2], owner = ARGV[1], epoch = nextEpoch,
			  stage = 'ACQUIRED', attempts = tonumber(ARGV[7]), verifyAtMs = 0
			}
			redis.call('SET', KEYS[2], cjson.encode(pending))
			redis.call('SET', KEYS[3], ARGV[2] .. '|' .. ARGV[1] .. '|' .. tostring(nextEpoch), 'PX', ARGV[4])
			return {1, nextEpoch, 'ACQUIRED'}
			""", java.util.List.class);

	public static final DefaultRedisScript<java.util.List> PURGE_STAGE = script("""
			-- PERF_PURGE_STAGE: 每个阶段推进前复核 lease epoch/owner，旧 owner 立即被拒
			-- ARGV: owner, epoch, purgeId, pendingJson, leaseTtlMs, nowMs
			local now = tonumber(ARGV[6])
			local lease = redis.call('GET', KEYS[1])
			if not lease then return {0, 'MISSING'} end
			local e, o, p, exp = string.match(lease, '^([^|]+)|([^|]+)|([^|]+)|([^|]+)$')
			if not e or not exp then return {0, 'BAD_LEASE'} end
			if now >= tonumber(exp) then return {0, 'LEASE_EXPIRED'} end
			if tonumber(e) ~= tonumber(ARGV[2]) then return {0, 'STALE_EPOCH'} end
			if o ~= ARGV[1] then return {0, 'WRONG_OWNER'} end
			local raw = redis.call('GET', KEYS[2])
			if not raw then return {0, 'MISSING'} end
			local ok, obj = pcall(cjson.decode, raw)
			if not ok or type(obj) ~= 'table' then return {0, 'BAD_PENDING'} end
			if obj['purgeId'] ~= ARGV[3] or tonumber(obj['epoch']) ~= tonumber(ARGV[2]) then
			  return {0, 'STALE_EPOCH'}
			end
			redis.call('SET', KEYS[2], ARGV[4])
			redis.call('PEXPIRE', KEYS[1], ARGV[5])
			return {1, 'ALLOWED'}
			""", java.util.List.class);

	public static final DefaultRedisScript<java.util.List> PURGE_FAIL = script("""
			-- PERF_PURGE_FAIL: 带 owner/epoch 校验的失败状态；保留 pending/guard，释放 lease 以便重试
			-- KEYS: lease, pending ; ARGV: owner, epoch, purgeId, pendingJson, nowMs
			local now = tonumber(ARGV[5])
			local lease = redis.call('GET', KEYS[1])
			if not lease then return {0, 'MISSING'} end
			local e, o, p, exp = string.match(lease, '^([^|]+)|([^|]+)|([^|]+)|([^|]+)$')
			if not e or not exp then return {0, 'BAD_LEASE'} end
			if now >= tonumber(exp) then return {0, 'LEASE_EXPIRED'} end
			if tonumber(e) ~= tonumber(ARGV[2]) then return {0, 'STALE_EPOCH'} end
			if o ~= ARGV[1] then return {0, 'WRONG_OWNER'} end
			local raw = redis.call('GET', KEYS[2])
			if not raw then return {0, 'MISSING'} end
			local ok, obj = pcall(cjson.decode, raw)
			if not ok or type(obj) ~= 'table' then return {0, 'BAD_PENDING'} end
			if obj['purgeId'] ~= ARGV[3] or tonumber(obj['epoch']) ~= tonumber(ARGV[2]) then
			  return {0, 'STALE_EPOCH'}
			end
			redis.call('SET', KEYS[2], ARGV[4])
			redis.call('DEL', KEYS[1])
			return {1, 'FAILED_RETRYABLE'}
			""", java.util.List.class);

	public static final DefaultRedisScript<java.util.List> PURGE_COMPLETE = script("""
			-- PERF_PURGE_COMPLETE: 完成时保留 durable pending + guard，释放 lease
			-- ARGV: owner, epoch, purgeId, pendingJson, guardTtlMs, nowMs, guardValue
			local now = tonumber(ARGV[6])
			local lease = redis.call('GET', KEYS[1])
			if not lease then return {0, 'MISSING'} end
			local e, o, p, exp = string.match(lease, '^([^|]+)|([^|]+)|([^|]+)|([^|]+)$')
			if not e or not exp then return {0, 'BAD_LEASE'} end
			if now >= tonumber(exp) then return {0, 'LEASE_EXPIRED'} end
			if tonumber(e) ~= tonumber(ARGV[2]) then return {0, 'STALE_EPOCH'} end
			if o ~= ARGV[1] then return {0, 'WRONG_OWNER'} end
			local raw = redis.call('GET', KEYS[2])
			if not raw then return {0, 'MISSING'} end
			local ok, obj = pcall(cjson.decode, raw)
			if not ok or type(obj) ~= 'table' then return {0, 'BAD_PENDING'} end
			if obj['purgeId'] ~= ARGV[3] or tonumber(obj['epoch']) ~= tonumber(ARGV[2]) then
			  return {0, 'STALE_EPOCH'}
			end
			redis.call('SET', KEYS[2], ARGV[4])
			redis.call('DEL', KEYS[1])
			redis.call('SET', KEYS[3], ARGV[7], 'PX', ARGV[5])
			return {1, 'COMPLETE'}
			""", java.util.List.class);

	public static final DefaultRedisScript<java.util.List> PURGE_DEFER = script("""
			-- PERF_PURGE_DEFER: 不可收尾时保留 pending/guard，lease 不主动删（等待自然过期）
			-- ARGV: owner, epoch, purgeId, pendingJson, nowMs
			local now = tonumber(ARGV[5])
			local lease = redis.call('GET', KEYS[1])
			if not lease then return {0, 'MISSING'} end
			local e, o, p, exp = string.match(lease, '^([^|]+)|([^|]+)|([^|]+)|([^|]+)$')
			if not e or not exp then return {0, 'BAD_LEASE'} end
			if tonumber(e) ~= tonumber(ARGV[2]) then return {0, 'STALE_EPOCH'} end
			if o ~= ARGV[1] then return {0, 'WRONG_OWNER'} end
			local raw = redis.call('GET', KEYS[2])
			if not raw then return {0, 'MISSING'} end
			local ok, obj = pcall(cjson.decode, raw)
			if not ok or type(obj) ~= 'table' then return {0, 'BAD_PENDING'} end
			if obj['purgeId'] ~= ARGV[3] or tonumber(obj['epoch']) ~= tonumber(ARGV[2]) then
			  return {0, 'STALE_EPOCH'}
			end
			redis.call('SET', KEYS[2], ARGV[4])
			return {1, 'DEFERRED'}
			""", java.util.List.class);

	public static final DefaultRedisScript<Long> PURGE_GUARDED_WRITE = script("""
			-- PERF_PURGE_GUARDED_WRITE: 守卫/pending 任一存在 ⇒ 拒绝写回；三者同槽
			if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then
			  return 0
			end
			redis.call('SET', KEYS[3], ARGV[1], 'PX', ARGV[2])
			return 1
			""", Long.class);

	public static final DefaultRedisScript<Long> TOMBSTONE = script("""
			-- PERF_TOMBSTONE: 防复活墓碑只增不减
			local gen = redis.call('INCR', KEYS[1])
			redis.call('PEXPIRE', KEYS[1], ARGV[2])
			redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
			return gen
			""", Long.class);

	public static final DefaultRedisScript<java.util.List> FRAME_APPEND = script("""
			-- PERF_FRAME_APPEND: fence/expected epoch 检查先于取号、归档、终帧事实写入
			-- KEYS: seq, xevents, xack, xtrim, fence, xterminal
			-- ARGV: expectedEpoch, ttlMs, window, ackTtlSec, token, head, tail, isTerminal, terminalJson
			local expected = tonumber(ARGV[1]) or 0
			local fence = tonumber(redis.call('GET', KEYS[5]) or '0') or 0
			if expected > 0 and fence > expected then
			  return {-1, fence, 0}
			end
			local ack = redis.call('GET', KEYS[3])
			if ack then
			  local prev = tonumber(ack)
			  if prev then
			    local body = ARGV[6] .. tostring(prev) .. ARGV[7]
			    local okEntry, entry = pcall(redis.call, 'XRANGE', KEYS[2], prev .. '-0', prev .. '-0', 'COUNT', 1)
			    if okEntry and type(entry) == 'table' and entry[1] then
			      local fields = entry[1][2]
			      local same = false
			      if type(fields) == 'table' then
			        for j = 1, #fields, 2 do
			          if fields[j] == 'frame' then same = (fields[j + 1] == body) end
			        end
			      end
			      if same then
			        redis.call('EXPIRE', KEYS[3], ARGV[4])
			        redis.call('PEXPIRE', KEYS[1], ARGV[2])
			        redis.call('PEXPIRE', KEYS[2], ARGV[2])
			        redis.call('PEXPIRE', KEYS[4], ARGV[2])
			        return {prev, 1, 0}
			      end
			    end
			  end
			end
			local cur = redis.call('INCR', KEYS[1])
			local okTail, last = pcall(redis.call, 'XREVRANGE', KEYS[2], '+', '-', 'COUNT', 1)
			if okTail and type(last) == 'table' and last[1] then
			  local tail = tonumber(string.match(last[1][1], '^(%d+)') or '0') or 0
			  if tail >= cur then
			    cur = redis.call('INCRBY', KEYS[1], string.format('%.0f', tail + 1 - cur))
			  end
			end
			local seqText = string.format('%.0f', cur)
			local body = ARGV[6] .. seqText .. ARGV[7]
			redis.call('XADD', KEYS[2], 'MAXLEN', '~', tonumber(ARGV[3]), seqText .. '-0', 'seq', seqText, 'frame', body)
			redis.call('SET', KEYS[3], seqText, 'EX', ARGV[4])
			redis.call('PEXPIRE', KEYS[1], ARGV[2])
			redis.call('PEXPIRE', KEYS[2], ARGV[2])
			redis.call('PEXPIRE', KEYS[4], ARGV[2])
			if ARGV[8] == '1' then
			  redis.call('SET', KEYS[6], ARGV[9], 'PX', ARGV[2])
			end
			return {cur, 0, 0}
			""", java.util.List.class);

	public static final DefaultRedisScript<java.util.List> RUN_LEASE_ACQUIRE = script("""
			-- PERF_RUN_LEASE_ACQUIRE: 续跑权 epoch 推进；fence 同槽原子抬高
			-- KEYS: lease, epochCounter, fence
			-- ARGV: owner, leaseTtlMs, nowMs, fenceTtlMs
			local now = tonumber(ARGV[3])
			local cur = redis.call('GET', KEYS[1])
			local curEpoch = 0
			local curExpires = 0
			if cur then
			  local e, o, exp = string.match(cur, '^([^|]+)|([^|]+)|([^|]+)$')
			  if e then curEpoch = tonumber(e) or 0 end
			  if exp then curExpires = tonumber(exp) or 0 end
			  if curExpires > now then return {0, curEpoch, 'BUSY'} end
			end
			local base = tonumber(redis.call('GET', KEYS[2]) or '0') or 0
			local fence = tonumber(redis.call('GET', KEYS[3]) or '0') or 0
			local nextEpoch = math.max(base, curEpoch, fence) + 1
			redis.call('SET', KEYS[1], tostring(nextEpoch) .. '|' .. ARGV[1] .. '|' .. tostring(now + tonumber(ARGV[2])), 'PX', ARGV[2])
			redis.call('SET', KEYS[2], tostring(nextEpoch), 'PX', ARGV[4])
			redis.call('SET', KEYS[3], tostring(nextEpoch), 'PX', ARGV[4])
			return {1, nextEpoch, 'ACQUIRED'}
			""", java.util.List.class);

	public static final DefaultRedisScript<java.util.List> DECIDE = script("""
			-- PERF_DECIDE: decided guard 与完整 pending 载荷同槽原子写
			-- KEYS: pending, decided, claim
			-- ARGV: field, expectedStatus, requestedStatus, fullPendingJson, decidedValue, cancelLike, ttlMs
			local field = ARGV[1]
			local raw = redis.call('HGET', KEYS[1], field)
			if not raw then return {0, 'MISSING'} end
			local ok, obj = pcall(cjson.decode, raw)
			if not ok or type(obj) ~= 'table' then return {0, 'BAD_PENDING'} end
			if ARGV[2] ~= '' and obj['status'] ~= ARGV[2] then return {0, 'CONFLICT_STATUS'} end
			local prev = redis.call('HGET', KEYS[2], field)
			if prev then
			  local prefix = string.sub(prev, 1, string.len(ARGV[3]) + 1)
			  if prefix == ARGV[3] .. '|' then return {1, 'IDEMPOTENT'} end
			  return {0, 'CONFLICT_DECIDED'}
			end
			if ARGV[6] == '1' and redis.call('HEXISTS', KEYS[3], field) == 1 then
			  return {0, 'CONFLICT_CLAIMED'}
			end
			redis.call('HSET', KEYS[1], field, ARGV[4])
			redis.call('HSET', KEYS[2], field, ARGV[5])
			redis.call('PEXPIRE', KEYS[1], ARGV[7])
			redis.call('PEXPIRE', KEYS[2], ARGV[7])
			redis.call('PEXPIRE', KEYS[3], ARGV[7])
			return {1, 'APPLIED'}
			""", java.util.List.class);

	public static final DefaultRedisScript<java.util.List> CLAIM = script("""
			-- PERF_CLAIM: 先 fence/epoch，再 HSETNX 认领，最后写完整 pending
			-- KEYS: claim, pending, fence
			-- ARGV: field, epoch, instance, fullPendingJson, ttlMs, expectedStatus
			local epoch = tonumber(ARGV[2]) or 0
			local fence = tonumber(redis.call('GET', KEYS[3]) or '0') or 0
			if epoch <= 0 or epoch < fence then return {0, 'STALE_EPOCH'} end
			local raw = redis.call('HGET', KEYS[2], ARGV[1])
			if not raw then return {0, 'MISSING'} end
			local ok, obj = pcall(cjson.decode, raw)
			if not ok or type(obj) ~= 'table' then return {0, 'BAD_PENDING'} end
			if obj['status'] ~= ARGV[6] then return {0, 'CONFLICT_STATUS'} end
			if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then return {0, 'ALREADY_CLAIMED'} end
			redis.call('HSET', KEYS[1], ARGV[1], tostring(epoch) .. '|' .. ARGV[3])
			redis.call('HSET', KEYS[2], ARGV[1], ARGV[4])
			redis.call('PEXPIRE', KEYS[1], ARGV[5])
			redis.call('PEXPIRE', KEYS[2], ARGV[5])
			return {1, 'WON'}
			""", java.util.List.class);

	public static final DefaultRedisScript<java.util.List> CANCEL_PENDING = script("""
			-- PERF_CANCEL_PENDING: 已认领/已决策/已执行的副作用不得被取消覆盖
			-- KEYS: pending, decided, claim
			-- ARGV: field, fullPendingJson, decidedValue, ttlMs
			if redis.call('HEXISTS', KEYS[3], ARGV[1]) == 1 then return {0, 'CONFLICT_CLAIMED'} end
			if redis.call('HEXISTS', KEYS[2], ARGV[1]) == 1 then return {0, 'CONFLICT_DECIDED'} end
			local raw = redis.call('HGET', KEYS[1], ARGV[1])
			if not raw then return {0, 'MISSING'} end
			local ok, obj = pcall(cjson.decode, raw)
			if not ok or type(obj) ~= 'table' then return {0, 'BAD_PENDING'} end
			if obj['status'] ~= 'PENDING' then return {0, 'CONFLICT_STATUS'} end
			redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
			redis.call('HSET', KEYS[2], ARGV[1], ARGV[3])
			redis.call('PEXPIRE', KEYS[1], ARGV[4])
			redis.call('PEXPIRE', KEYS[2], ARGV[4])
			return {1, 'CANCELLED'}
			""", java.util.List.class);

	public static final DefaultRedisScript<java.util.List> FRONTEND_RESULT = script("""
			-- PERF_FRONTEND_RESULT: 回执只能由认领者写，且不得把已认领条目写回未执行
			-- KEYS: pending, decided, claim
			-- ARGV: field, fullPendingJson, decidedValue, ttlMs, expectedClaim
			local claim = redis.call('HGET', KEYS[3], ARGV[1])
			if not claim then return {0, 'MISSING_CLAIM'} end
			if claim ~= ARGV[5] then return {0, 'CONFLICT_CLAIMED'} end
			local prev = redis.call('HGET', KEYS[2], ARGV[1])
			if prev then
			  local approved = string.sub(prev, 1, 9) == 'APPROVED|'
			  local pending = string.sub(prev, 1, 8) == 'PENDING|'
			  if not approved and not pending then
			    return {0, 'CONFLICT_DECIDED'}
			  end
			end
			local raw = redis.call('HGET', KEYS[1], ARGV[1])
			if not raw then return {0, 'MISSING'} end
			local ok, obj = pcall(cjson.decode, raw)
			if not ok or type(obj) ~= 'table' then return {0, 'BAD_PENDING'} end
			if obj['status'] ~= 'APPROVED' and obj['status'] ~= 'PENDING' then
			  return {0, 'CONFLICT_STATUS'}
			end
			redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
			redis.call('HSET', KEYS[2], ARGV[1], ARGV[3])
			redis.call('PEXPIRE', KEYS[1], ARGV[4])
			redis.call('PEXPIRE', KEYS[2], ARGV[4])
			redis.call('PEXPIRE', KEYS[3], ARGV[4])
			return {1, 'APPLIED'}
			""", java.util.List.class);

	private Scripts() {
	}

	private static <T> DefaultRedisScript<T> script(String body, Class<T> type) {
		DefaultRedisScript<T> script = new DefaultRedisScript<>(body, type);
		script.setScriptText(body);
		return script;
	}

}
