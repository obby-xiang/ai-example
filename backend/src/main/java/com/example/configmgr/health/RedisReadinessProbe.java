package com.example.configmgr.health;

import com.example.configmgr.ai.config.RedisAvailability;
import org.springframework.stereotype.Component;

/**
 * {@code redis} 维度：复用 AI 链路的共享探活（{@link RedisAvailability#isAvailable()}）。
 *
 * <h2>非硬依赖（required=false）</h2>
 * Redis 故障时本维度如实报 DOWN（含 {@code lastError} 摘要），并让 AI capability 标降级，
 * 但<b>不把整站 ready 拉成 503</b>。理由：
 * <ul>
 * <li>Redis 承载 AI 会话/帧/租约/限流与作业取消标志，AI 端点本来就有 503 快速失败与降级路径
 * （{@code RedisAvailability} 前置探活、限流降级放行）；</li>
 * <li>业务面（配置目录/数据读写/任务与导出作业的非 AI 路径）不依赖 Redis。若把 Redis 算进 ready，
 * 一次 Redis 抖动会让统一代理把**所有实例**一起摘流 —— 可用性故障被放大成全站不可达，
 * 恰是多实例部署最不该出现的效果；</li>
 * <li>实例该不该接业务流量是"它能不能干活"的问题，不是"每一条依赖都完美"的问题。</li>
 * </ul>
 *
 * <h2>为什么复用而不是自己 PING</h2>
 * 两处探活口径必须一致（命令超时 2s、负缓存 5s、失败计数同源）；否则会出现
 * "ready 说 Redis 可用、{@code /chat} 说不可用"的分叉。负缓存对 readiness 是有意的：
 * 掉线时 LB 每 2s 探测一次，不必每次都打一次会超时的 PING。
 */
@Component
class RedisReadinessProbe implements ReadinessProbe {

	static final String NAME = "redis";

	private final RedisAvailability redisAvailability;

	RedisReadinessProbe(RedisAvailability redisAvailability) {
		this.redisAvailability = redisAvailability;
	}

	@Override
	public String name() {
		return NAME;
	}

	/** 降级维度：DOWN 只影响 AI capability 与观测面，不影响 ready（见类注）。 */
	@Override
	public boolean required() {
		return false;
	}

	/** 本维度承载 AI 传输面：DOWN 时只有 {@code capabilities.ai.degraded} 会点亮。 */
	@Override
	public String capability() {
		return CAPABILITY_AI;
	}

	@Override
	public ProbeResult check() {
		boolean available = this.redisAvailability.isAvailable();
		return available ? ProbeResult.up("PING 正常")
				: ProbeResult.down(this.redisAvailability.reason());
	}

}
