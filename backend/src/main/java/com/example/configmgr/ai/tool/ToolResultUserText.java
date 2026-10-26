package com.example.configmgr.ai.tool;

import com.example.configmgr.common.ErrorCodes;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具结果的两份人读文案（2026-10-26 批次 6C / N9）。
 *
 * <p><b>模型面</b>是 {@code ToolResponseMessage} 信封里的 {@code message}：保留可行动指令与排障码，
 * 模型据此收尾或改道。<b>用户面</b>是 {@code tool_result} 帧的 {@code result} 与 {@code /history}
 * 的 {@code responseData}：只回答"发生了什么、接下来怎么办"，不出现 {@code app.ai.*} 配置键、
 * 内部接口路径、实例/轮次标识，也不出现"请直接用已经获得的信息…"这类说给模型的指令。
 *
 * <p>机器可读真值（{@code outcome}/{@code code}/{@code retryable}）不经过本类，仍由
 * {@link ToolResultEnvelope} 原样下发；本类只按 {@code code} 选择用户摘要，并把命中摘要的原码
 * 作为 {@code userTextCode} 供前端本地化；中文摘要继续作为旧路径与无本地化时的回退。
 * <b>不认识的码一律回落到原文</b>（成功结果、业务失败原因不做改写）。唯一的动态摘要是
 * 单轮 {@code create_task} 上限里的<b>业务</b>任务号（{@code 本轮已创建任务 #99}）——
 * 用户需要它去打开刚建的任务，它不是内部标识。
 *
 * <p><b>诊断面不丢</b>：原文仍在模型记忆、执行台账与服务端日志里；用户通道不额外挂
 * {@code detail} 字段 —— 内部路径与实例标识不因为"换个字段"就变成可以外发的信息。
 */
public final class ToolResultUserText {

	/**
	 * 单轮 {@code create_task} 上限的模型文案里带一个<b>业务</b>任务号（{@code 本轮已创建任务 #99}）。
	 * 它不是内部标识，用户需要它去打开刚建的任务，故这一处摘要按原号回填，其余部分仍走通用措辞。
	 */
	private static final Pattern CREATED_TASK_ID = Pattern.compile("本轮已创建任务 #(\\d+)");

	/**
	 * 内部/降级码 → 用户摘要。措辞只讲事实与下一步，不复述配置键、接口路径或模型指令。
	 */
	private static final Map<String, String> SUMMARIES = Map.ofEntries(
			Map.entry(ErrorCodes.TOOL_STEP_LIMIT,
					"本轮工具调用次数已用完，这一步没有执行；AI 会用已有信息继续回答。"),
			Map.entry(ErrorCodes.TOOL_BUDGET_EXHAUSTED,
					"本轮对话上下文已满，这一步没有执行；AI 会用已有信息继续回答。"),
			Map.entry(ErrorCodes.UPSTREAM_FAILED,
					"上一个步骤没有成功，这一步没有执行；AI 会用已有信息继续回答。"),
			Map.entry(ErrorCodes.CREATE_TASK_TURN_LIMIT,
					"同一轮只允许创建一次任务，这一次没有再创建；需要新任务请在下一轮提出。"),
			Map.entry(ErrorCodes.RESOURCE_LOCKED,
					"该任务已有作业在执行，本次未执行；请等待作业结束后再试。"),
			Map.entry(ErrorCodes.TOOL_KIND_MISMATCH,
					"这一步没有执行；该操作需要由当前页面完成。"),
			Map.entry(ErrorCodes.SHEET_DISABLED,
					"表格功能已被管理员关闭，这一步没有执行。"),
			Map.entry(ErrorCodes.SCOPE_NOT_DISCLOSED,
					"当前页面或步骤不支持这个操作，这一步没有执行。"),
			Map.entry(ErrorCodes.TOOL_TIMEOUT,
					"这一步没有在时限内完成，结果尚未确认；该操作可能已产生部分变化，请核对后再继续。"),
			Map.entry(ErrorCodes.TOOL_ALREADY_CLAIMED,
					"这一步已由其他执行实例接管，本次没有重复执行。"));

	private ToolResultUserText() {
	}

	/** 命中用户摘要时复用原机器码作为本地化键；原文透传时没有摘要码。 */
	public static String userTextCode(String code) {
		return code != null && SUMMARIES.containsKey(code) ? code : null;
	}

	/**
	 * 用户面文案：命中内部码时返回摘要，否则原样返回（{@code null} 也原样）。
	 *
	 * @param code      信封里的机器可读码（可为 null = 成功/无码）
	 * @param modelText 模型面原文（{@code resultText} / 信封 {@code message}）
	 */
	public static String forUser(String code, String modelText) {
		if (code == null || code.isBlank()) {
			return modelText;
		}
		if (ErrorCodes.CREATE_TASK_TURN_LIMIT.equals(code)) {
			Matcher matcher = CREATED_TASK_ID.matcher(modelText == null ? "" : modelText);
			if (matcher.find()) {
				return "本轮已创建任务 #" + matcher.group(1)
						+ "；同一轮只允许创建一次任务，这一次没有再创建。";
			}
		}
		String summary = SUMMARIES.get(code);
		return summary == null ? modelText : summary;
	}

}
