package com.example.configmgr.perf.redis;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选协议的键形（与生产键形逐字对齐；候选新增键单独标注）。
 *
 * <p>所有 run 级键共享 {@code &#123;<tenantId>:<userId>:run:<runId>&#125;} hash tag；
 * 会话级 staging / 记忆 / purge 租约共享 {@code &#123;<sessionId>&#125;}。
 * legacy archive 键在生产里没有 hash tag（只能按前缀 SCAN 发现），候选协议增加同会话索引
 * {@code ai:ctx:archive:idx:...}，删除时读索引、对存档键走单键 pipeline（不假设同槽）。
 */
public final class KeyShapes {

	public static final int RUN_BASE_KEY_COUNT = 13;

	private static final List<String> RUN_PREFIXES = List.of(
			"ai:run:",
			"ai:runstate:",
			"ai:pending:",
			"ai:claim:",
			"ai:ledger:",
			"ai:idem:",
			"ai:seq:",
			"ai:xevents:",
			"ai:lease:run:",
			"ai:fence:run:",
			"ai:req:",
			"ai:decided:",
			"ai:lease:epoch:");

	private KeyShapes() {
	}

	public static String sessionId(String namespace, String scenario, int copy) {
		requireSafe(namespace, "namespace");
		requireSafe(scenario, "scenario");
		if (copy < 0) {
			throw new IllegalArgumentException("copy 不能为负");
		}
		return namespace + "-" + scenario + "-" + copy;
	}

	public static String runId(String sessionId, int index) {
		requireSafe(sessionId, "sessionId");
		if (index < 0) {
			throw new IllegalArgumentException("run index 不能为负");
		}
		return sessionId + "-r" + index;
	}

	public static String tag(long tenantId, long userId, String runId) {
		requireSafe(runId, "runId");
		return "{" + tenantId + ":" + userId + ":run:" + runId + "}";
	}

	public static String runKey(String prefix, long tenantId, long userId, String runId) {
		return prefix + tag(tenantId, userId, runId);
	}

	public static List<String> runBaseKeys(long tenantId, long userId, String runId) {
		List<String> keys = new ArrayList<>(RUN_BASE_KEY_COUNT);
		for (String prefix : RUN_PREFIXES) {
			keys.add(runKey(prefix, tenantId, userId, runId));
		}
		return List.copyOf(keys);
	}

	/** 候选删除面：生产 13 键 + 同槽 xterminal 事实键。 */
	public static List<String> runDeleteKeys(long tenantId, long userId, String runId) {
		List<String> keys = new ArrayList<>(runBaseKeys(tenantId, userId, runId));
		keys.add(xterminalKey(tenantId, userId, runId));
		return List.copyOf(keys);
	}

	public static String xterminalKey(long tenantId, long userId, String runId) {
		return runKey("ai:xterminal:", tenantId, userId, runId);
	}

	public static String frameAckKey(long tenantId, long userId, String runId, String token) {
		requireSafe(token, "token");
		return "ai:xack:" + token + ":" + tag(tenantId, userId, runId);
	}

	public static String xtrimKey(long tenantId, long userId, String runId) {
		return runKey("ai:xtrim:", tenantId, userId, runId);
	}

	public static String sessionKey(long tenantId, long userId, String sessionId) {
		return "ai:session:" + segment(tenantId, userId) + sessionId;
	}

	public static String sessionRunsKey(long tenantId, long userId, String sessionId) {
		return "ai:session:runs:" + segment(tenantId, userId) + sessionId;
	}

	public static String sessionActiveKey(long tenantId, long userId, String sessionId) {
		return "ai:session:active:" + segment(tenantId, userId) + sessionId;
	}

	public static String sessionCurrentKey(long tenantId, long userId, String sessionId) {
		return "ai:session:current:" + segment(tenantId, userId) + sessionId;
	}

	public static String memoryKey(long tenantId, long userId, String sessionId) {
		requireSafe(sessionId, "sessionId");
		return "chat:mem:" + segment(tenantId, userId) + "{" + sessionId + "}";
	}

	public static String memoryVerKey(long tenantId, long userId, String sessionId) {
		return memoryKey(tenantId, userId, sessionId) + ":ver";
	}

	public static String memoryGenKey(long tenantId, long userId, String sessionId) {
		return memoryKey(tenantId, userId, sessionId) + ":gen";
	}

	/** 候选新增：staging 精确索引（与 staging 键同 tag）。 */
	public static String stagingIndexKey(long tenantId, long userId, String sessionId) {
		requireSafe(sessionId, "sessionId");
		return "chat:mem:staging:idx:" + segment(tenantId, userId) + "{" + sessionId + "}";
	}

	public static String stagingKey(long tenantId, long userId, String sessionId, String token) {
		requireSafe(token, "token");
		return "chat:mem:staging:" + segment(tenantId, userId) + "{" + sessionId + "}:" + token;
	}

	/** 候选新增：legacy archive 精确索引（键带会话 tag；存档键本身无 tag）。 */
	public static String archiveIndexKey(long tenantId, long userId, String sessionId) {
		requireSafe(sessionId, "sessionId");
		return "ai:ctx:archive:idx:" + segment(tenantId, userId) + "{" + sessionId + "}";
	}

	public static String archiveKey(long tenantId, long userId, String sessionId, int turnNo) {
		if (turnNo < 0) {
			throw new IllegalArgumentException("turnNo 不能为负");
		}
		return "ai:ctx:archive:" + segment(tenantId, userId) + sessionId + ":" + turnNo;
	}

	public static String contextKey(long tenantId, long userId, String sessionId) {
		return "ai:ctx:" + segment(tenantId, userId) + sessionId;
	}

	public static String transcriptKey(long tenantId, long userId, String sessionId) {
		requireSafe(sessionId, "sessionId");
		return "ai:tx:" + segment(tenantId, userId) + "{" + sessionId + "}";
	}

	public static String transcriptBytesKey(long tenantId, long userId, String sessionId) {
		return transcriptKey(tenantId, userId, sessionId) + ":bytes";
	}

	public static String transcriptReadyKey(long tenantId, long userId, String sessionId) {
		return transcriptKey(tenantId, userId, sessionId) + ":ready";
	}

	public static String attachmentSessionIndexKey(long tenantId, long userId, String sessionId) {
		return "ai:form:att:sess:" + segment(tenantId, userId) + sessionId;
	}

	public static String attachmentFormIndexKey(long tenantId, long userId, String runId, String toolCallId) {
		requireSafe(toolCallId, "toolCallId");
		return "ai:form:att:form:" + segment(tenantId, userId) + runId + ":" + toolCallId;
	}

	public static String attachmentKey(long tenantId, long userId, String fileId) {
		requireSafe(fileId, "fileId");
		return "ai:form:att:" + segment(tenantId, userId) + fileId;
	}

	/** 候选新增：purge 三键与记忆/守卫共享会话 tag，使"检查守卫 + 拒绝写回"可单脚本原子完成。 */
	public static String purgeGuardKey(long tenantId, long userId, String sessionId) {
		requireSafe(sessionId, "sessionId");
		return "ai:purge:guard:" + segment(tenantId, userId) + "{" + sessionId + "}";
	}

	public static String purgePendingKey(long tenantId, long userId, String sessionId) {
		requireSafe(sessionId, "sessionId");
		return "ai:purge:pending:" + segment(tenantId, userId) + "{" + sessionId + "}";
	}

	public static String purgeLeaseKey(long tenantId, long userId, String sessionId) {
		requireSafe(sessionId, "sessionId");
		return "ai:purge:lease:" + segment(tenantId, userId) + "{" + sessionId + "}";
	}

	public static String purgeDueKey() {
		return "ai:purge:retry:due";
	}

	public static String backgroundKey(String namespace, String bucket, int index) {
		requireSafe(namespace, "namespace");
		requireSafe(bucket, "bucket");
		if (index < 0) {
			throw new IllegalArgumentException("index 不能为负");
		}
		return "perf:bg:" + namespace + ":" + bucket + ":" + index;
	}

	public static String backgroundMarkerKey(String namespace) {
		requireSafe(namespace, "namespace");
		return "perf:rp:" + namespace + ":bg";
	}

	public static String namespaceIndexKey(String namespace) {
		requireSafe(namespace, "namespace");
		return "perf:rp:" + namespace + ":index";
	}

	public static String segment(long tenantId, long userId) {
		if (tenantId <= 0 || (userId <= 0 && userId != -1L)) {
			throw new IllegalArgumentException("身份不合法：" + tenantId + "/" + userId);
		}
		return tenantId + ":" + userId + ":";
	}

	public static String hashTagOf(String key) {
		return RedisSlots.hashTag(key);
	}

	private static void requireSafe(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " 不能为空");
		}
		if (value.indexOf('{') >= 0 || value.indexOf('}') >= 0) {
			throw new IllegalArgumentException(name + " 不得包含花括号（会破坏 hash tag 边界）：" + value);
		}
	}

}
