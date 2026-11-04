package com.example.configmgr.ai.history;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 用户展示历史（transcript）的一条条目（B2）。
 *
 * <h2>它不是什么</h2>
 * 不是模型工作上下文（{@code chat:mem}），也不是压缩事实/事件表（B3）。它只保存
 * <b>用户看到过的原文</b>：提问、助手正文/思考、工具卡原文与状态；压缩摘要、步数/预算收尾、
 * 工具结果占位符一律不写。
 *
 * <h2>幂等键</h2>
 * {@code entryId} 是唯一身份，读取时按它 <b>last-wins</b>（同一工具卡状态更新、同一助手条目
 * 从 pending 变终态都各写一条新记录，读时取最后一条）。条目本身 append-only，没有更新 API。
 *
 * <p>{@code kind}：{@code user|assistant|tool}；{@code baseline} 为 {@code true} 时表示该条是从
 * 既有 {@code chat:mem} 一次性复制来的历史（此时 {@code role} 给出原消息角色）。
 *
 * <p>{@code turnStartedAtMs} 用于跨轮排序（挂起轮晚收尾 / 新轮先结束）；同轮按追加顺序。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranscriptEntry(String entryId, String kind, String role, String runId, long turnStartedAtMs, long atMs,
		String text, String reasoning, Boolean stopped, Boolean truncated, List<ToolCallView> toolCalls,
		Map<String, Object> toolCallStates, String toolCallId, String name, String kind2, String status, String result,
		Boolean baseline, String code, Boolean toolTruncated, String toolStrategy, Integer toolOriginalChars,
		Integer toolReturnedChars, String errorCode, String errorMessage, List<SegmentView> segments) {

	/** 兼容没有有序段时间线的旧写入形态。 */
	public TranscriptEntry(String entryId, String kind, String role, String runId, long turnStartedAtMs, long atMs,
			String text, String reasoning, Boolean stopped, Boolean truncated, List<ToolCallView> toolCalls,
			Map<String, Object> toolCallStates, String toolCallId, String name, String kind2, String status, String result,
			Boolean baseline, String code, Boolean toolTruncated, String toolStrategy, Integer toolOriginalChars,
			Integer toolReturnedChars, String errorCode, String errorMessage) {
		this(entryId, kind, role, runId, turnStartedAtMs, atMs, text, reasoning, stopped, truncated, toolCalls,
				toolCallStates, toolCallId, name, kind2, status, result, baseline, code, toolTruncated, toolStrategy,
				toolOriginalChars, toolReturnedChars, errorCode, errorMessage, null);
	}

	/** 兼容没有轮次失败事实的旧写入形态。 */
	public TranscriptEntry(String entryId, String kind, String role, String runId, long turnStartedAtMs, long atMs,
			String text, String reasoning, Boolean stopped, Boolean truncated, List<ToolCallView> toolCalls,
			Map<String, Object> toolCallStates, String toolCallId, String name, String kind2, String status,
			String result, Boolean baseline, String code, Boolean toolTruncated, String toolStrategy,
			Integer toolOriginalChars, Integer toolReturnedChars) {
		this(entryId, kind, role, runId, turnStartedAtMs, atMs, text, reasoning, stopped, truncated, toolCalls,
				toolCallStates, toolCallId, name, kind2, status, result, baseline, code, toolTruncated, toolStrategy,
				toolOriginalChars, toolReturnedChars, null, null, null);
	}

	/** 一条 assistant 工具调用（与 Spring AI 的线形一致；展示面按 id/type/name/arguments 读）。 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallView(String id, String type, String name, String arguments) {
	}

	/** 按成功归档帧顺序记录的可展示段；seq 是该段首帧的归档序号。 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SegmentView(String kind, String text, String toolCallId, long seq) {
	}

	/**
	 * 只换段级状态映射、其它字段原样保留（同 entryId 的读取合并用：见
	 * {@code TranscriptEntries#carryPreflight(TranscriptEntry, TranscriptEntry)}）。
	 */
	public TranscriptEntry withToolCallStates(Map<String, Object> toolCallStates) {
		return new TranscriptEntry(this.entryId, this.kind, this.role, this.runId, this.turnStartedAtMs, this.atMs,
				this.text, this.reasoning, this.stopped, this.truncated, this.toolCalls, toolCallStates,
				this.toolCallId, this.name, this.kind2, this.status, this.result, this.baseline, this.code,
				this.toolTruncated, this.toolStrategy, this.toolOriginalChars, this.toolReturnedChars,
				this.errorCode, this.errorMessage, this.segments);
	}

	public TranscriptEntry withSegments(List<SegmentView> segments) {
		return new TranscriptEntry(this.entryId, this.kind, this.role, this.runId, this.turnStartedAtMs, this.atMs,
				this.text, this.reasoning, this.stopped, this.truncated, this.toolCalls, this.toolCallStates,
				this.toolCallId, this.name, this.kind2, this.status, this.result, this.baseline, this.code,
				this.toolTruncated, this.toolStrategy, this.toolOriginalChars, this.toolReturnedChars,
				this.errorCode, this.errorMessage, segments);
	}

	/** 记忆补偿只补终态事实；正文、工具原文、排序与条目身份仍保留展示历史里的版本。 */
	public TranscriptEntry withTerminalFacts(TranscriptEntry terminal) {
		Map<String, Object> states = terminal.toolCallStates == null ? this.toolCallStates : terminal.toolCallStates;
		return new TranscriptEntry(this.entryId, this.kind, this.role, this.runId, this.turnStartedAtMs, this.atMs,
				this.text, terminal.reasoning == null ? this.reasoning : terminal.reasoning,
				terminal.stopped == null ? this.stopped : terminal.stopped,
				terminal.truncated == null ? this.truncated : terminal.truncated, this.toolCalls, states,
				this.toolCallId, this.name, this.kind2, this.status, this.result, this.baseline, this.code,
				this.toolTruncated, this.toolStrategy, this.toolOriginalChars, this.toolReturnedChars,
				terminal.errorCode == null ? this.errorCode : terminal.errorCode,
				terminal.errorMessage == null ? this.errorMessage : terminal.errorMessage, this.segments);
	}

}
