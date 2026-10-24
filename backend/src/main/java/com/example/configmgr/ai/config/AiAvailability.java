package com.example.configmgr.ai.config;

/**
 * AI 可用性判定（Q2 → 模型配置专项 §3.4 动态化）。
 *
 * <h2>判据变更（批①）</h2>
 * 旧判据 = 构造期把 {@code spring.ai.openai.api-key} 快照进字段（"env 有 key"）；新判据 =
 * <b>存在 ≥1 个 enabled 且 key 可解析的 provider</b>（{@link AiConfigService#availability()}，
 * O(1) 读内存快照）。三档机器码：{@code AI_UNAVAILABLE}（无 provider）/ {@code AI_KEY_MISSING}
 * （有 provider 但都没 key）/ {@code AI_KEY_UNREADABLE}（DB 托管 key 解不开）。
 *
 * <h2>为什么对外行为不变</h2>
 * 端点（{@code AiController}）仍按本类判空后<b>在进入流式管道之前</b>返回 503：不建 SSE 连接、
 * 不触发任何上游调用。{@code isAvailable()/code()/reason()} 三个签名一个字都没动，四处调用点零改动。
 *
 * <p>R-12：无 key / key 解不开的文案都指向「设置 → AI」，不再回落到"未配置环境变量"的旧兼容句
 * （旧 {@code LEGACY_UNAVAILABLE_REASON} 已删除；码 {@code AI_UNAVAILABLE} 保持兼容）。
 */
public class AiAvailability {

	private final AiConfigService configService;

	public AiAvailability(AiConfigService configService) {
		this.configService = configService;
	}

	public boolean isAvailable() {
		return this.configService.availability().available();
	}

	/** 503 响应体的 code 字段（前端据此展示"AI 未启用"）；可用时为 {@code AI_AVAILABLE}。 */
	public String code() {
		return this.configService.availability().code();
	}

	public String reason() {
		return this.configService.availability().reason();
	}

}
