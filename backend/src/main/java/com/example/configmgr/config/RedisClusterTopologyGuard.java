package com.example.configmgr.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code cluster} profile 的<b>启动前闸门</b>（B23L1 批① Cluster 硬阻断 / 评审 Minor 4）。
 *
 * <h2>为什么需要它</h2>
 * {@code application-cluster.yml} 把种子节点写成无默认值的 {@code ${AI_REDIS_CLUSTER_NODES}}，
 * 本意是"漏配即失败"。但 Spring Boot 的 {@code Binder} 用的是
 * {@code PropertySourcesPlaceholdersResolver}，它<b>吞掉无法解析的占位符</b>（保留原文），
 * 于是漏配时 {@code spring.data.redis.cluster.nodes} 会绑成<b>一个字面量</b>
 * {@code "${AI_REDIS_CLUSTER_NODES}"} —— 启动照常成功，第一个 Redis 命令才炸，且报错是一句
 * "unknown host ${AI_REDIS_CLUSTER_NODES}"，运维看不出是漏了哪个变量。
 *
 * <p>所以这里做一次显式校验：cluster profile 下节点清单必须非空、且不含占位符残文；
 * 否则<b>启动直接失败</b>并点名 {@code AI_REDIS_CLUSTER_NODES}。默认（standalone）profile 下本类
 * 不装配，行为与改造前完全一致。
 *
 * <h2>边界</h2>
 * 只读属性、不建连接、不改任何 Redis 配置；判定失败抛 {@link IllegalStateException}
 * （配置错误，不是运行时降级面 —— 这类错误必须让实例起不来，而不是带病上线）。
 */
@Component
@Profile("cluster")
public class RedisClusterTopologyGuard {

	/** 种子节点属性名（与 {@code application-cluster.yml} 同键）。 */
	public static final String NODES_PROPERTY = "spring.data.redis.cluster.nodes";

	/** 本 profile 期望的 env 变量（报错文案里点名它，运维才知道该配什么）。 */
	public static final String NODES_ENV = "AI_REDIS_CLUSTER_NODES";

	/** {@code host:port} 形状（host 非空白、port 为纯数字；不做 DNS/端口范围校验 —— 那是建连的事）。 */
	static final java.util.regex.Pattern HOST_PORT = java.util.regex.Pattern.compile("^\\S+:\\d+$");

	public RedisClusterTopologyGuard(Environment environment) {
		List<String> nodes = Binder.get(environment)
			.bind(NODES_PROPERTY, org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
			.orElse(List.of());
		if (nodes.isEmpty()) {
			throw new IllegalStateException("cluster profile 缺少 Redis 种子节点：请配置环境变量 " + NODES_ENV
					+ "（逗号分隔的 host:port 列表，" + NODES_PROPERTY + "）");
		}
		for (String node : nodes) {
			if (node == null || node.isBlank() || node.contains("${")) {
				throw new IllegalStateException("cluster profile 的 Redis 种子节点未解析（当前 = " + node
						+ "）：请配置环境变量 " + NODES_ENV + "（逗号分隔的 host:port 列表）");
			}
			// host:port 形状校验（评审 Minor 5）：漏端口的条目（如 "10.0.0.11"）也是"配置错"，
			// 但如果不在这里挡，它要到建连时才炸，报错是一句 "Unable to connect"，运维看不出是漏写端口。
			if (!HOST_PORT.matcher(node.trim()).matches()) {
				throw new IllegalStateException("cluster profile 的 Redis 种子节点形状不合法：'" + node
						+ "'，期望 host:port（例：10.0.0.11:6379）；请检查环境变量 " + NODES_ENV);
			}
		}
	}

}
