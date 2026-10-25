package com.example.configmgr.health;

/**
 * 单个就绪维度的探测结果（{@code /health/ready} 的 {@code checks.<name>} 段）。
 *
 * @param up 该维度是否可用
 * @param detail 人读细节（String，或结构化 Map/List）。**不得**含密钥、连接串口令等敏感值；
 * 只写地址/路径/状态这类诊断信息。{@code null} 表示不回 detail 键。
 */
public record ProbeResult(boolean up, Object detail) {

	public static ProbeResult up(Object detail) {
		return new ProbeResult(true, detail);
	}

	public static ProbeResult down(Object detail) {
		return new ProbeResult(false, detail);
	}

}
