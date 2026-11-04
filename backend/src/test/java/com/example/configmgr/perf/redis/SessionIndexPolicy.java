package com.example.configmgr.perf.redis;

import java.util.List;

/**
 * 会话 run 索引的"写入方 → 键类型 → 删除命令"契约。
 *
 * <p>{@link DatasetGenerator#writeSession} 用 SADD 写 runs 索引（SET）、用 ZADD 写 active 索引
 * （ZSET）。删除必须使用与写入类型一致的命令：对 ZSET 执行 SREM 会直接得到 WRONGTYPE。
 * 两个索引键不带 hash tag，簇内不同槽，只能按批 pipeline，不能合并成一条 Lua。
 */
public final class SessionIndexPolicy {

	public enum Index {

		/** {@code ai:session:runs:<t>:<u>:<sid>}：SET，SADD / SREM。 */
		RUNS("ai:session:runs:", "set", "SADD", "SREM"),

		/** {@code ai:session:active:<t>:<u>:<sid>}：ZSET，ZADD / ZREM。 */
		ACTIVE("ai:session:active:", "zset", "ZADD", "ZREM");

		private final String prefix;

		private final String dataType;

		private final String writeCommand;

		private final String removeCommand;

		Index(String prefix, String dataType, String writeCommand, String removeCommand) {
			this.prefix = prefix;
			this.dataType = dataType;
			this.writeCommand = writeCommand;
			this.removeCommand = removeCommand;
		}

		public String prefix() {
			return this.prefix;
		}

		public String dataType() {
			return this.dataType;
		}

		public String writeCommand() {
			return this.writeCommand;
		}

		public String removeCommand() {
			return this.removeCommand;
		}

		public boolean matches(String key) {
			return key != null && key.startsWith(this.prefix);
		}

	}

	private SessionIndexPolicy() {
	}

	public static Index indexOf(String key) {
		for (Index index : Index.values()) {
			if (index.matches(key)) {
				return index;
			}
		}
		throw new IllegalArgumentException("未知会话索引键：" + key);
	}

	/** 生成方声明索引归属；键形漂移时立即失败，避免写入方与删除方各写一套。 */
	public static String requireKey(Index index, String key) {
		if (!index.matches(key)) {
			throw new IllegalArgumentException("索引键形不匹配：" + index + " vs " + key);
		}
		return key;
	}

	/** 构造成员删除命令；键必须属于该索引，否则失败而不是把 SREM 打到 ZSET 上。 */
	public static String[] remove(Index index, String key, List<String> members) {
		requireKey(index, key);
		if (members == null || members.isEmpty()) {
			throw new IllegalArgumentException("删除成员不能为空");
		}
		String[] command = new String[2 + members.size()];
		command[0] = index.removeCommand();
		command[1] = key;
		for (int i = 0; i < members.size(); i++) {
			command[2 + i] = members.get(i);
		}
		return command;
	}

}
