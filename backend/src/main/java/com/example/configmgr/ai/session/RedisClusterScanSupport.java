package com.example.configmgr.ai.session;

import com.example.configmgr.common.AiStateUnavailableException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Cluster 安全的 SCAN</b>（会话销毁专用；不依赖 {@code StringRedisTemplate#scan}）。
 *
 * <h2>为什么必须逐 master</h2>
 * {@code RedisTemplate#scan} 只作用于当前连接所在节点；Redis Cluster 的键分布在多个 master，
 * 用它扫 {@code ai:ctx:archive:*} / {@code ai:purge:pending:*} 会<b>静默漏键</b>。
 * 本类改用 Spring Data Redis 3.5.11 的实际 API：
 * <ul>
 * <li>standalone：{@link RedisConnection#scan(ScanOptions)}；</li>
 * <li>cluster：{@link RedisClusterConnection#clusterGetNodes()} 取拓扑，只遍历
 * {@link RedisClusterNode#isMaster()} 的节点，逐节点调用
 * {@link RedisClusterConnection#scan(RedisClusterNode, ScanOptions)}；</li>
 * <li>每节点 cursor 必须 close；cluster 连接 try-with-resources close；key 全局去重。</li>
 * </ul>
 *
 * <p><b>API 事实（3.5.11 javap 实测）</b>：{@code RedisClusterConnection} 没有
 * {@code getConnection(RedisNode)}，{@code RedisClusterNode} 也没有 {@code getRedisNode()}；
 * 逐节点扫描的官方入口就是 {@code scan(RedisClusterNode, ScanOptions)}。不要为了“按节点取连接”
 * 去 cast 某个实现类（Lettuce/Jedis 不同），那会把实现细节变成生产依赖。
 *
 * <h2>失败姿态</h2>
 * 任一步（取连接、取拓扑、某 master 的 scan、cursor close）失败都抛
 * {@link AiStateUnavailableException}。调用方不得把部分结果当完整：
 * archive 扫描失败会让销毁枚举中止（complete=false）；pending 恢复失败会上抛，runner 不猜状态。
 */
final class RedisClusterScanSupport {

	private RedisClusterScanSupport() {
	}

	/**
	 * 扫描匹配 {@code options.pattern()} 的键（跨 cluster master；standalone 单连接）。
	 *
	 * @param maxKeys 全局最多返回键数；{@code <=0} = 不按结果数截断（仍使用 options 的 count）
	 * @return 去重后的键，顺序为节点遍历顺序
	 * @throws AiStateUnavailableException 任一节点/连接失败（fail-closed）
	 */
	static List<String> scan(StringRedisTemplate template, ScanOptions options, int maxKeys) {
		if (template == null) {
			throw new AiStateUnavailableException("SCAN 失败：StringRedisTemplate 未装配", null);
		}
		RedisConnectionFactory factory;
		try {
			factory = template.getConnectionFactory();
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("SCAN 失败：无法获取 Redis 连接工厂", ex);
		}
		if (factory == null) {
			throw new AiStateUnavailableException("SCAN 失败：Redis 连接工厂未装配", null);
		}
		try (RedisConnection connection = factory.getConnection()) {
			if (connection instanceof RedisClusterConnection cluster) {
				return scanCluster(cluster, options, maxKeys);
			}
			return scanSingle(connection, options, maxKeys);
		}
		catch (AiStateUnavailableException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("SCAN 失败（连接/节点不可用）", ex);
		}
	}

	private static List<String> scanSingle(RedisConnection connection, ScanOptions options, int maxKeys) {
		Set<String> keys = new LinkedHashSet<>();
		// keyCommands() 仍是同一条 standalone 连接；不使用 RedisTemplate.scan（单连接语义）。
		try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
			if (cursor == null) {
				return List.of();
			}
			drain(cursor, keys, maxKeys);
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("SCAN 失败（standalone）", ex);
		}
		return List.copyOf(keys);
	}

	private static List<String> scanCluster(RedisClusterConnection cluster, ScanOptions options, int maxKeys) {
		Iterable<RedisClusterNode> nodes;
		try {
			nodes = cluster.clusterGetNodes();
		}
		catch (RuntimeException ex) {
			throw new AiStateUnavailableException("SCAN 失败：无法读取 cluster 拓扑", ex);
		}
		if (nodes == null) {
			throw new AiStateUnavailableException("SCAN 失败：cluster 拓扑为空", null);
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
					drain(cursor, keys, maxKeys);
				}
			}
			catch (RuntimeException ex) {
				// 某 master 失败 = 本次扫描不完整：上抛，调用方不得用部分结果冒充完整。
				throw new AiStateUnavailableException("SCAN 失败：cluster master " + node.getHost() + ":"
						+ node.getPort(), ex);
			}
			if (maxKeys > 0 && keys.size() >= maxKeys) {
				break;
			}
		}
		if (!anyMaster) {
			throw new AiStateUnavailableException("SCAN 失败：cluster 没有可用 master", null);
		}
		return List.copyOf(keys);
	}

	private static void drain(Cursor<byte[]> cursor, Set<String> keys, int maxKeys) {
		while (cursor.hasNext()) {
			if (maxKeys > 0 && keys.size() >= maxKeys) {
				return;
			}
			byte[] raw = cursor.next();
			if (raw != null) {
				keys.add(new String(raw, StandardCharsets.UTF_8));
			}
		}
	}

}
