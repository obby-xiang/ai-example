package com.example.configmgr.ai.tool;

import java.util.List;

/**
 * 前端通道工具的"下发前 / 回灌前"复核扩展点（DC-15 的安全闸）。
 *
 * <h2>为什么需要它</h2>
 * 通道为 {@link ToolMeta.Channel#FRONTEND} 的工具，后端方法体永不执行（哨兵桩），
 * 参数一路直达前端 —— 于是"模型给前端的入参"这条路径上原本<b>没有任何后端校验点</b>：
 * 合法性与安全性全靠前端自己把关。DC-15 的生成式表单把"模型生成的 UI 描述"引入这条路径，
 * 因此必须在<b>下发给前端之前</b>与<b>采用前端回灌之前</b>各设一道后端闸门。
 *
 * <h2>两道闸门的位置（都在挂起机制里，不在工具方法体里）</h2>
 * <ol>
 * <li><b>下发前</b>（{@code SpToolCallingManager#awaitFrontend}）：入参不合法 ⇒
 * <b>不挂起、不发 {@code frontend_tool_request}</b>，直接把结构化错误结果回填模型
 * （前端根本看不到非法表单）；</li>
 * <li><b>回灌前</b>（{@code ConfirmGate#submitFrontendResult}）：回灌结果不合法 ⇒
 * <b>不消费挂起</b>（条目保持 {@code PENDING}，可修正后重试），HTTP 400 带被拒原因清单。</li>
 * </ol>
 *
 * <h2>为什么是接口而不是在挂起机制里写 if</h2>
 * 挂起机制（{@code SpToolCallingManager}/{@code ConfirmGate}）对具体工具名<b>零知识</b>
 * —— 它们只读 {@code @ToolChannel}/{@code @ToolRisk} 元数据（见 {@code AiTools} 的类注释）。
 * 复核属于"某个工具自己的参数契约"，因此做成<b>按工具名自认领</b>的扩展点（{@link #supports}）：
 * 新增需要复核的工具只需再加一个实现 Bean，挂起机制一行不改。
 * <b>未声明复核的工具一律不受影响</b>（既有 6 个前端工具的"任意文本结果"语义原样保留）。
 */
public interface FrontendToolGuard {

	/** 是否由本实现复核该工具（按模型给出的工具名原文判定）。 */
	boolean supports(String toolName);

	/**
	 * 下发前的入参复核。
	 *
	 * @param argumentsJson 模型发出的工具入参原文（{@code AssistantMessage.ToolCall#arguments}，
	 *                      尚未反序列化 —— 校验必须看原文，否则"被静默忽略的未知属性"无从发现）
	 * @return 判定结果；{@code accepted=false} 时调用方不挂起、不回灌，只把结论回填模型
	 */
	Verdict inspectArguments(String argumentsJson);

	/**
	 * 回灌前的结果复核。
	 *
	 * @param argumentsJson 该次调用的入参原文（挂起时外置在待决条目里，即"当初下发的那份 schema"）
	 * @param resultJson    前端回灌的 {@code result} 原文
	 * @return 判定结果；{@code accepted=false} 时调用方拒绝本次回灌（状态不变、可重试）
	 */
	Verdict inspectResult(String argumentsJson, String resultJson);

	/**
	 * 复核结论：放行 / 拒绝 + 机器可读码 + 原因清单。
	 *
	 * @param accepted 是否放行
	 * @param code     机器可读码（拒绝时必填，如 {@code FORM_SCHEMA_REJECTED}）
	 * @param message  人读说明（拒绝时必填）
	 * @param reasons  被拒原因清单（逐条给出"哪一处、期望什么、实际什么"）
	 */
	record Verdict(boolean accepted, String code, String message, List<String> reasons) {

		public static Verdict accept() {
			return new Verdict(true, null, null, List.of());
		}

		public static Verdict reject(String code, String message, List<String> reasons) {
			return new Verdict(false, code, message, reasons == null ? List.of() : List.copyOf(reasons));
		}

		/**
		 * 回填模型 / 写进 HTTP 错误体的说明文本：机器可读码 + 人读说明 + 编号原因清单。
		 *
		 * <p>编号清单是刻意的：它让模型（或前端）知道<b>改哪一处</b>才能过闸，
		 * 而不是收到一句"参数不合法"后反复重试同一种错法。
		 */
		public String render() {
			StringBuilder sb = new StringBuilder();
			sb.append(this.code == null ? "FRONTEND_TOOL_REJECTED" : this.code);
			if (this.message != null && !this.message.isBlank()) {
				sb.append('：').append(this.message);
			}
			if (this.reasons != null && !this.reasons.isEmpty()) {
				sb.append("\n被拒原因：");
				for (int i = 0; i < this.reasons.size(); i++) {
					sb.append("\n").append(i + 1).append(". ").append(this.reasons.get(i));
				}
			}
			return sb.toString();
		}
	}

}
