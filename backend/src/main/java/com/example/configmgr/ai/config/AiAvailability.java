package com.example.configmgr.ai.config;

/**
 * AI 可用性判定（Q2 → 模型配置专项 §3.4 动态化）。
 *
 * <h2>判据变更（批① / 默认目标硬绑定复审）</h2>
 * 旧判据 = 构造期把 {@code spring.ai.openai.api-key} 快照进字段（"env 有 key"）；新判据 =
 * 默认目标能解析成一个真实运行的 {@code (providerId, modelId)}（{@link AiConfigService#availability()}，
 * 读内存快照）。四档机器码：{@code AI_UNAVAILABLE}（无 provider，或硬绑定的 provider 已停用/不存在）/
 * {@code AI_KEY_MISSING}（key 缺失）/ {@code AI_KEY_UNREADABLE}（DB 托管 key 解不开）/
 * {@code NO_MODEL_CONFIGURED}（provider 与 key 都在，但没有该 provider 自己的可用模型）。
 *
 * <p>硬绑定（{@code default.providerId} 存在）时不看其他 provider：绑定行不可用即整条 AI 不可用，
 * 不静默换家。未绑定的旧配置才保留"任一 enabled+key 的 provider"的兼容判定。
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
