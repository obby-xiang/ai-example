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

	/**
	 * 扁平化成可存进 Redis 快照的 Map（{@code ai:run:<runId>} 的 {@code context} 段）。
	 * 续跑时据此重建同一份上下文 —— 披露的候选工具子集必须与挂起前一致，
	 * 否则续跑会拿着"另一套工具"继续跑循环。
	 */
	public Map<String, Object> toMap() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("page", page);
		out.put("taskId", taskId);
		out.put("taskType", taskType);
		out.put("step", step);
		out.put("extra", extra);
		return out;
	}

	/** {@link #toMap()} 的逆操作；容忍缺字段与 null。 */
	@SuppressWarnings("unchecked")
	public static AiContext fromMap(Map<String, Object> map) {
		if (map == null) {
			return empty();
		}
		Object taskId = map.get("taskId");
		return of((String) map.get("page"), (String) map.get("taskType"), (String) map.get("step"),
				taskId == null ? null : Long.valueOf(String.valueOf(taskId)),
				map.get("extra") instanceof Map<?, ?> extra ? (Map<String, Object>) extra : null);
	}

}
