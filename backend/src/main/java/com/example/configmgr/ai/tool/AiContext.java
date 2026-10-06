package com.example.configmgr.ai.tool;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单轮对话的工作区上下文（页面/任务/步骤/额外信息）。
 *
 * <p>
 * 两个用途：① 工具渐进披露的标签来源（{@link ToolRegistry#forContext}）；
 * ② 注入系统消息的工作区快照（{@link ContextBuilder#buildContextMessage}）。
 * 由请求体携带，跨轮不落库（会话态见后续棒次的 session 包）。
 */
@Data
public class AiContext {

	private String page = "";

	private Long taskId;

	private String taskType;

	private String step;

	private Map<String, Object> extra = new LinkedHashMap<>();

	public static AiContext of(String page, String taskType, String step, Long taskId, Map<String, Object> extra) {
		AiContext context = new AiContext();
		context.setPage(page != null ? page : "");
		context.setTaskType(taskType);
		context.setStep(step);
		context.setTaskId(taskId);
		if (extra != null) {
			context.getExtra().putAll(extra);
		}
		return context;
	}

	public static AiContext empty() {
		return new AiContext();
	}

	/** 上下文主键，形如 {@code task:EXPORT/EXPORT} / {@code page:tasks} / {@code *}。 */
	public String getContextKey() {
		if (taskType != null && step != null) {
			return "task:" + taskType + "/" + step;
		}
		if (page != null && !page.isBlank()) {
			return "page:" + page;
		}
		return "*";
	}

}
