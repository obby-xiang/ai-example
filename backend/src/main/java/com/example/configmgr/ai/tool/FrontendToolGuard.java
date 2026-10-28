package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.form.FormIssue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
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
	 * 下发前的入参复核（带<b>工具名</b>，工具安全边界批）。
	 *
	 * <p>默认实现忽略工具名、退回单参版本（既有复核器零改动）。但"按入参形状反推工具名"
	 * 是已知窄缝（{@code update_rows} 漏了 {@code set} 会被当成"撤销"校验通过）——
	 * 新/改复核器应覆写本方法并按真名分支；框架侧无条件调用本方法。
	 *
	 * @param toolName      模型给出的工具名原文（与 {@code @Tool(name=...)} 同名）
	 * @param argumentsJson 该次调用的入参原文
	 */
	default Verdict inspectArguments(String toolName, String argumentsJson) {
		return inspectArguments(argumentsJson);
	}

	/**
	 * 下发前的入参复核（带<b>上下文</b>，槽位放行批）。
	 *
	 * <p>默认实现忽略上下文、退回带工具名的两参版本 —— 既有复核器（表格写 / UI 原语 / 向导动作）
	 * 零改动。生成式表单用它判断"这张表要补的槽位是否已经被任务/工作区或用户明确值填满"：
	 * 填满 ⇒ 不下发（零副作用），把模型引回对应工具。
	 *
	 * @param toolName      模型给出的工具名原文
	 * @param argumentsJson 该次调用的入参原文
	 * @param context       本轮工作区上下文（可为 null：兼容调用方）
	 */
	default Verdict inspectArguments(String toolName, String argumentsJson, AiContext context) {
		return inspectArguments(toolName, argumentsJson);
	}

	/**
	 * 回灌前的结果复核。
	 *
	 * @param argumentsJson 该次调用的入参原文（挂起时外置在待决条目里，即"当初下发的那份 schema"）
	 * @param resultJson    前端回灌的 {@code result} 原文
	 * @return 判定结果；{@code accepted=false} 时调用方拒绝本次回灌（状态不变、可重试）
	 */
	Verdict inspectResult(String argumentsJson, String resultJson);

	/**
	 * 回灌前的结果复核（带<b>归属上下文</b>，GF 优化批 G-E）。
	 *
	 * <p>默认实现忽略上下文、退回两参版本 —— 既有复核器（表格写 / UI 原语）零改动。
	 * 生成式表单用它校验 attachment 的"属本表单 + 一次性"（没有 runId/toolCallId 就无从判定归属）。
	 *
	 * @param runId         该次挂起所属轮次（可为 null：兼容调用方）
	 * @param toolCallId    该次挂起的前端调用 id（可为 null）
	 * @param argumentsJson 该次调用的入参原文
	 * @param resultJson    前端回灌的 result 原文
	 */
	default Verdict inspectResult(String runId, String toolCallId, String argumentsJson, String resultJson) {
		return inspectResult(argumentsJson, resultJson);
	}

	/**
	 * 回灌结果是否按<b>结构化</b>整体处置（结构化回灌超限拒绝批）。
	 *
	 * <p>用途只有一个：回灌端点（{@code AiController#frontendToolResult}）在超限时决定
	 * "截断 + 标记"还是"400 拒绝"。两类判据任一为真即结构化：
	 * <ol>
	 * <li><b>结果契约就是结构化文本的工具</b>（{@link SheetWriteGuard#SUPPORTED 表格写四件}）：
	 * 它们的回灌是"已应用…\n共 N 格"这种<b>带尾行的文本</b>，头部截断会切掉尾行；
	 * 随后的结果复核只会报"缺共 N 尾行"，把"结果太大"误诊成"格式不对"；</li>
	 * <li><b>明确的 JSON 形状</b>（能解析成对象/数组）：生成式表单的填写值以及任何以 JSON 回灌的
	 * 前端工具都在此列 —— 字符级截断必然产生坏 JSON。</li>
	 * </ol>
	 *
	 * <p>为什么不按"首字符是 { 或 ["判 JSON：普通文本完全可能以花括号开头（页面结构说明、
	 * 代码片段、日志），只看首字符会把普通文本误伤成结构化结果。<b>必须解析成功</b>才算。
	 *
	 * @param toolName   挂起条目的工具名原文（可空：拿不到条目时只看形状）
	 * @param mapper     JSON 解析器（调用方注入，便于与端点共用同一份配置）
	 * @param resultJson 前端回灌的 {@code result} 原文（可空 = 不是结构化）
	 */
	static boolean structuredResult(String toolName, ObjectMapper mapper, String resultJson) {
		if (toolName != null && SheetWriteGuard.SUPPORTED.contains(toolName)) {
			return true;
		}
		if (mapper == null || resultJson == null) {
			return false;
		}
		int start = 0;
		while (start < resultJson.length() && Character.isWhitespace(resultJson.charAt(start))) {
			start++;
		}
		if (start >= resultJson.length()) {
			return false;
		}
		char first = resultJson.charAt(start);
		if (first != '{' && first != '[') {
			return false;
		}
		try {
			JsonNode root = mapper.readTree(resultJson);
			return root != null && (root.isObject() || root.isArray());
		}
		catch (Exception notJson) {
			// 坏 JSON / 超深嵌套 / 超长字符串：一律按普通文本处理（旧路径仍可用）
			return false;
		}
	}

	/**
	 * 复核结论：放行 / 拒绝 + 机器可读码 + 原因清单。
	 *
	 * <p><b>GF 优化批（G-A）</b>：除人读 {@code reasons} 外，另带<b>结构化清单</b>
	 * {@code issues}（{@code {key, code, message}}）—— 前端据此点亮具体字段、按码决定是否可重试，
	 * 模型据此精准修复。旧的三参 {@link #reject(String, String, List)} 仍可用（结构化清单为空），
	 * 因此既有复核器（表格写 / UI 原语）行为一字不变。
	 *
	 * @param accepted  是否放行
	 * @param code      机器可读码（拒绝时必填，如 {@code FORM_SCHEMA_REJECTED}）
	 * @param message   人读说明（拒绝时必填）
	 * @param reasons   被拒原因清单（逐条给出"哪一处、期望什么、实际什么"）
	 * @param issues    结构化清单（与 {@code reasons} 同源；{@code reasons} 即其 {@code message} 投影）
	 */
	record Verdict(boolean accepted, String code, String message, List<String> reasons,
			List<FormIssue> issues, String resultSuffix) {

		public Verdict {
			reasons = reasons == null ? List.of() : List.copyOf(reasons);
			issues = issues == null ? List.of() : List.copyOf(issues);
			resultSuffix = resultSuffix == null || resultSuffix.isBlank() ? null : resultSuffix;
		}

		public static Verdict accept() {
			return new Verdict(true, null, null, List.of(), List.of(), null);
		}

		public static Verdict reject(String code, String message, List<String> reasons) {
			return new Verdict(false, code, message, reasons, List.of(), null);
		}

		public static Verdict rejectIssues(String code, String message, List<FormIssue> issues) {
			List<String> reasons = new ArrayList<>();
			if (issues != null) {
				for (FormIssue issue : issues) {
					reasons.add(issue.message());
				}
			}
			return new Verdict(false, code, message, reasons, issues, null);
		}

		/**
		 * 放行时给模型结果补一段<b>元数据</b>（G-E：attachment 只暴露 fileName/fileId/size）。
		 *
		 * <p>为什么由复核器给：文件元数据在登记簿里，只有复核器知道；而"文件内容永不进模型上下文"
		 * 这条边界由同一处把关，补元数据与守边界是同一件事的两面。
		 */
		public Verdict withResultSuffix(String suffix) {
			return new Verdict(this.accepted, this.code, this.message, this.reasons, this.issues, suffix);
		}

		/**
		 * 本次拒绝是否"改对后可以重试"：{@code *_RESULT_REJECTED} 语义 = 挂起未消费，可改值重发；
		 * schema 级拒绝不行（表单根本没下发，必须模型改 schema 重新调用）。
		 */
		public boolean retryable() {
			return this.accepted || (this.code != null && this.code.endsWith("_RESULT_REJECTED"));
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
			// G-A：机器可读清单（与上面的人读清单同源）—— 模型可据此精准定位字段与问题类别
			if (this.issues != null && !this.issues.isEmpty()) {
				sb.append("\n机器可读：");
				for (int i = 0; i < this.issues.size(); i++) {
					FormIssue issue = this.issues.get(i);
					sb.append("\n").append(i + 1).append(") key=")
						.append(issue.key().isEmpty() ? "(form)" : issue.key())
						.append(" code=").append(issue.code());
				}
			}
			return sb.toString();
		}
	}

}
