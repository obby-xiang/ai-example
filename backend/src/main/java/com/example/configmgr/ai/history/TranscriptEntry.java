package com.example.configmgr.ai.history;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** 用户展示历史的一条规范条目；entryId 与 toolCallId 在更新中保持稳定。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranscriptEntry(String entryId, String kind, String runId, long turnStartedAtMs, long atMs,
		String text, String reasoning, Boolean stopped, Boolean truncated, List<ToolCallView> toolCalls,
		Map<String, Object> toolCallStates, String toolCallId, String name, String kind2, String status, String result,
		String code, Boolean toolTruncated, String toolStrategy, Integer toolOriginalChars, Integer toolReturnedChars,
		String errorCode, String errorMessage, String outcome, String errorCardKey, Boolean unconfirmed,
		List<SegmentView> segments) {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallView(String id, String type, String name, String arguments) {
	}

	/** 按成功归档帧顺序记录的可展示段；seq 是该段首帧的归档序号。 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SegmentView(String kind, String text, String toolCallId, long seq) {
	}

	public TranscriptEntry withToolCallStates(Map<String, Object> toolCallStates) {
		return new TranscriptEntry(this.entryId, this.kind, this.runId, this.turnStartedAtMs, this.atMs,
				this.text, this.reasoning, this.stopped, this.truncated, this.toolCalls, toolCallStates,
				this.toolCallId, this.name, this.kind2, this.status, this.result, this.code,
				this.toolTruncated, this.toolStrategy, this.toolOriginalChars, this.toolReturnedChars,
				this.errorCode, this.errorMessage, this.outcome, this.errorCardKey, this.unconfirmed, this.segments);
	}

	public TranscriptEntry withSegments(List<SegmentView> segments) {
		return new TranscriptEntry(this.entryId, this.kind, this.runId, this.turnStartedAtMs, this.atMs,
				this.text, this.reasoning, this.stopped, this.truncated, this.toolCalls, this.toolCallStates,
				this.toolCallId, this.name, this.kind2, this.status, this.result, this.code,
				this.toolTruncated, this.toolStrategy, this.toolOriginalChars, this.toolReturnedChars,
				this.errorCode, this.errorMessage, this.outcome, this.errorCardKey, this.unconfirmed, segments);
	}

	public TranscriptEntry withOutcome(String outcome, String errorCardKey, Boolean unconfirmed, String errorCode,
			String errorMessage) {
		return new TranscriptEntry(this.entryId, this.kind, this.runId, this.turnStartedAtMs, this.atMs,
				this.text, this.reasoning, this.stopped, this.truncated, this.toolCalls, this.toolCallStates,
				this.toolCallId, this.name, this.kind2, this.status, this.result, this.code,
				this.toolTruncated, this.toolStrategy, this.toolOriginalChars, this.toolReturnedChars,
				errorCode == null ? this.errorCode : errorCode, errorMessage == null ? this.errorMessage : errorMessage,
				outcome, errorCardKey, unconfirmed, this.segments);
	}

}
