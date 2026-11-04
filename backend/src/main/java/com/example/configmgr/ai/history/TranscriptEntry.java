package com.example.configmgr.ai.history;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 用户展示历史（transcript）的一条条目。
 *
 * <h2>它不是什么</h2>
 * 不是模型工作上下文（{@code chat:mem}），也不是压缩事实/事件表。它只保存
 * <b>用户看到过的原文</b>：提问、助手正文/思考、工具卡原文与状态；压缩摘要、步数/预算收尾、
 * 工具结果占位符一律不写。
 *
 * <h2>幂等键</h2>
 * {@code entryId} 是规范条目身份。同身份更新保留首次顺序，写入按 pending/approved/terminal
 * 分级物化，低等级迟到写不能回退高等级条目。
 *
 * <p>{@code kind}：{@code user|assistant|tool}。
 *
 * <p>展示顺序由存储侧首次写入序号决定；{@code turnStartedAtMs} 保留为条目业务时间。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranscriptEntry(String entryId, String kind, String runId, long turnStartedAtMs, long atMs,
		String text, String reasoning, Boolean stopped, Boolean truncated, List<ToolCallView> toolCalls,
		Map<String, Object> toolCallStates, String toolCallId, String name, String kind2, String status, String result,
		String code, Boolean toolTruncated, String toolStrategy, Integer toolOriginalChars, Integer toolReturnedChars,
		String errorCode, String errorMessage, List<SegmentView> segments) {

	/** 一条 assistant 工具调用（与 Spring AI 的线形一致；展示面按 id/type/name/arguments 读）。 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallView(String id, String type, String name, String arguments) {
	}

	/** 按成功归档帧顺序记录的可展示段；seq 是该段首帧的归档序号。 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SegmentView(String kind, String text, String toolCallId, long seq) {
	}

	/**
	 * 只换段级状态映射、其它字段原样保留（同 entryId 的展示事实合并用：见
	 * {@code TranscriptEntries#carryPreflight(TranscriptEntry, TranscriptEntry)}）。
	 */
	public TranscriptEntry withToolCallStates(Map<String, Object> toolCallStates) {
		return new TranscriptEntry(this.entryId, this.kind, this.runId, this.turnStartedAtMs, this.atMs,
				this.text, this.reasoning, this.stopped, this.truncated, this.toolCalls, toolCallStates,
				this.toolCallId, this.name, this.kind2, this.status, this.result, this.code,
				this.toolTruncated, this.toolStrategy, this.toolOriginalChars, this.toolReturnedChars,
				this.errorCode, this.errorMessage, this.segments);
	}

	public TranscriptEntry withSegments(List<SegmentView> segments) {
		return new TranscriptEntry(this.entryId, this.kind, this.runId, this.turnStartedAtMs, this.atMs,
				this.text, this.reasoning, this.stopped, this.truncated, this.toolCalls, this.toolCallStates,
				this.toolCallId, this.name, this.kind2, this.status, this.result, this.code,
				this.toolTruncated, this.toolStrategy, this.toolOriginalChars, this.toolReturnedChars,
				this.errorCode, this.errorMessage, segments);
	}

}
