package com.example.configmgr.ai.session;

import com.example.configmgr.common.AiStateUnavailableException;
import org.apache.commons.codec.binary.StringUtils;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Cluster 安全的通用有界 SCAN：standalone 单连接，Cluster 逐 master；任何失败都不返回部分结果。
 *
 * <p>每次调用同时受三项固定预算约束：全局唯一结果键数、{@code hasNext()} 游标推进次数、
 * 包含空页遍历在内的总工作时间。{@code ScanOptions.count()} 只是 Redis 单页提示，不能代替这些上限。
 */
public final class RedisClusterScanSupport {

	private static final int DEFAULT_MAX_KEYS = 1024;

	private static final int DEFAULT_MAX_CURSOR_ADVANCES = 4096;

	private static final Duration DEFAULT_MAX_WORK = Duration.ofSeconds(2);

	private RedisClusterScanSupport() {
	}

	/**
	 * 兼容原 {@code maxKeys} 调用形：非正值不再表示无界，而是使用有界缺省。
	 */
	public static List<String> scan(StringRedisTemplate template, ScanOptions options, int maxKeys) {
		return scan(template, options, ScanBudget.of(maxKeys > 0 ? maxKeys : DEFAULT_MAX_KEYS,
				DEFAULT_MAX_CURSOR_ADVANCES, DEFAULT_MAX_WORK));
	}

	/**
	 * 扫描匹配 {@code options.pattern()} 的键并全局去重。
	 *
	 * @throws AiStateUnavailableException 连接、拓扑、任一 master、游标关闭失败，或任一预算超限
	 */
	public static List<String> scan(StringRedisTemplate template, ScanOptions options, ScanBudget budget) {
		Objects.requireNonNull(budget, "budget");
		if (template == null) {
			throw new AiStateUnavailableException("SCAN failed: StringRedisTemplate is not configured", null);
		}
		ScanWork work = new ScanWork(budget);
		RedisConnectionFactory factory;
		try {
			factory = template.getConnectionFactory();
			work.checkDeadline();
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("SCAN failed: unable to obtain Redis connection factory", ex);
		}
		if (factory == null) {
			throw new AiStateUnavailableException("SCAN failed: Redis connection factory is not configured", null);
		}
		try (RedisConnection connection = factory.getConnection()) {
			work.checkDeadline();
			if (connection instanceof RedisClusterConnection cluster) {
				return scanCluster(cluster, options, budget, work);
			}
			return scanSingle(connection, options, budget, work);
		}
		catch (AiStateUnavailableException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("SCAN failed (connection/node unavailable)", ex);
		}
	}

	private static List<String> scanSingle(RedisConnection connection, ScanOptions options, ScanBudget budget,
			ScanWork work) {
		Set<String> keys = new LinkedHashSet<>();
		try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
			if (cursor == null) {
				work.checkDeadline();
				return List.of();
			}
			drain(cursor, keys, budget, work);
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("SCAN failed (standalone)", ex);
		}
		work.checkDeadline();
		return List.copyOf(keys);
	}

	private static List<String> scanCluster(RedisClusterConnection cluster, ScanOptions options, ScanBudget budget,
			ScanWork work) {
		Iterable<RedisClusterNode> nodes;
		try {
			nodes = cluster.clusterGetNodes();
			work.checkDeadline();
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("SCAN failed: unable to read cluster topology", ex);
		}
		if (nodes == null) {
			throw new AiStateUnavailableException("SCAN failed: cluster topology is empty", null);
		}
		Set<String> keys = new LinkedHashSet<>();
		boolean anyMaster = false;
		Iterator<RedisClusterNode> iterator = nodes.iterator();
		while (iterator.hasNext()) {
			RedisClusterNode node = iterator.next();
			if (node == null || !node.isMaster()) {
				continue;
			}
			anyMaster = true;
			try (Cursor<byte[]> cursor = cluster.scan(node, options)) {
				if (cursor != null) {
					drain(cursor, keys, budget, work);
				}
				work.checkDeadline();
			}
			catch (RuntimeException ex) {
				throw new AiStateUnavailableException("SCAN failed: cluster master " + node.getHost() + ":"
						+ node.getPort(), ex);
			}
			work.checkDeadline();
		}
		if (!anyMaster) {
			throw new AiStateUnavailableException("SCAN failed: no available cluster master", null);
		}
		work.checkDeadline();
		return List.copyOf(keys);
	}

	private static void drain(Cursor<byte[]> cursor, Set<String> keys, ScanBudget budget, ScanWork work) {
		while (true) {
			work.checkDeadline();
			boolean hasNext = cursor.hasNext();
			work.noteCursorAdvance();
			if (!hasNext) {
				return;
			}
			byte[] raw = cursor.next();
			if (raw != null) {
				keys.add(StringUtils.newStringUtf8(raw));
			}
			if (keys.size() > budget.maxKeys()) {
				throw new AiStateUnavailableException("SCAN result limit exceeded: " + budget.maxKeys(), null);
			}
		}
	}

	/** 单次扫描的三重硬预算；所有维度都必须为正，不提供无界形态。 */
	public record ScanBudget(int maxKeys, int maxCursorAdvances, long maxWorkNanos) {

		public ScanBudget {
			if (maxKeys <= 0 || maxCursorAdvances <= 0 || maxWorkNanos <= 0L) {
				throw new IllegalArgumentException("SCAN budgets must be positive");
			}
		}

		public static ScanBudget of(int maxKeys, int maxCursorAdvances, Duration maxWork) {
			Objects.requireNonNull(maxWork, "maxWork");
			if (maxWork.isZero() || maxWork.isNegative()) {
				throw new IllegalArgumentException("maxWork must be positive");
			}
			return new ScanBudget(maxKeys, maxCursorAdvances, maxWork.toNanos());
		}

	}

	private static final class ScanWork {

		private final ScanBudget budget;

		private final long startedAtNanos;

		private int cursorAdvances;

		private ScanWork(ScanBudget budget) {
			this.budget = budget;
			this.startedAtNanos = System.nanoTime();
		}

		private void noteCursorAdvance() {
			this.cursorAdvances++;
			if (this.cursorAdvances > this.budget.maxCursorAdvances()) {
				throw new AiStateUnavailableException("SCAN cursor advance limit exceeded: "
						+ this.budget.maxCursorAdvances(), null);
			}
			checkDeadline();
		}

		private void checkDeadline() {
			if (System.nanoTime() - this.startedAtNanos > this.budget.maxWorkNanos()) {
				throw new AiStateUnavailableException("SCAN work budget exceeded: " + this.budget.maxWorkNanos()
						+ "ns", null);
			}
		}

	}

}
