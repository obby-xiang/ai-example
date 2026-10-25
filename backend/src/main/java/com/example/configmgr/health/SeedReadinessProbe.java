package com.example.configmgr.health;

import com.example.configmgr.seed.SeedStatus;
import org.springframework.stereotype.Component;

/**
 * {@code seed} 维度：本实例的种子播种是否走完（{@link SeedStatus} 的判据见该类类注）。
 *
 * <p>为什么播种中的实例不能接流量：种子写入的是主数据与样例配置（regions/projects/15 个定义
 * 与数千行发布数据）。播种中接请求会出现"目录里一半定义、价格表缺项目"的半成品视图，
 * 比直接 503 更难排查；而多实例形态下第一个实例播完提交后，其余实例在锁内判重跳过，
 * 这个窗口通常只有几秒。
 *
 * <p>GLM 失败仍算 ready（fail-open 的既有设计），失败事实在 {@code detail.failures} 里；
 * BASE 失败会直接导致启动失败，走不到 ready 判定。
 */@Component
class SeedReadinessProbe implements ReadinessProbe {

	static final String NAME = "seed";

	private final SeedStatus seedStatus;

	SeedReadinessProbe(SeedStatus seedStatus) {
		this.seedStatus = seedStatus;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public ProbeResult check() {
		boolean ready = this.seedStatus.isReady();
		return new ProbeResult(ready, this.seedStatus.describe());
	}

}
