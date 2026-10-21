package com.example.configmgr.ai.gate;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 输入侧 DLP（防护批③ B3-1）：<b>高置信凭据检测 → 脱敏后继续</b>。
 *
 * <h2>为什么不是"拒绝"</h2>
 * 用户给这个 AI 面板粘配置片段、连接串本来就是主场景（{@code 调研…} §3.3）：拒绝会打断主流程。
 * 业界（Bedrock Mask / NeMo input rail）在输入侧的主流处置也是 mask 后继续；block 只留给
 * "高置信 + 高危"（本批 = 完整私钥块），而输入侧的"完整私钥块"也<b>只脱敏</b>——
 * 用户可能正是在问"这个私钥报错怎么修"，把它整条拒掉是误伤。
 *
 * <h2>位置</h2>
 * 调用点在 {@code AiController#chat} 的长度闸<b>之后</b>、会话门之前：长度按<b>原文</b>量
 * （用户占的额度是原文的额度），脱敏只改"送进模型的那一份"。归一排在其前（{@code InputSanitizer}），
 * 于是零宽字符切开的 {@code s\u200Bk-} 同样会被抹平后再命中（B3-T06）。
 */
@Component
public class InputDlp {

	/** 脱敏结果：{@code text} 是唯一下游可见产物。 */
	public record Result(String text, int redacted, List<String> rules, boolean blocked, List<SafetyRules.Hit> hits) {

		/** 是否命中（前端提示"已隐去 N 处"的判据）。 */
		public boolean hit() {
			return this.redacted > 0;
		}

	}

	/** 脱敏（{@code null} 按空串）。 */
	public Result mask(String text) {
		SafetyRules.Masked masked = SafetyRules.mask(text);
		return new Result(masked.text(), masked.redacted(), masked.rules(), masked.blocked(), SafetyRules.hits(text));
	}

}
