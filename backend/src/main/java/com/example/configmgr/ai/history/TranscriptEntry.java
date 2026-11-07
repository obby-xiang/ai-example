package com.example.configmgr.ai.history;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用户展示历史的一条规范条目；entryId 与 toolCallId 在更新中保持稳定。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranscriptEntry(@JsonProperty("entryId") String entryId, @JsonProperty("kind") String kind,
			@JsonProperty("runId") String runId,
			@JsonProperty("turnStartedAtMs") @JsonFormat(shape = JsonFormat.Shape.STRING) long turnStartedAtMs,
			@JsonProperty("atMs") @JsonFormat(shape = JsonFormat.Shape.STRING) long atMs,
		@JsonProperty("text") String text, @JsonProperty("reasoning") String reasoning,
			@JsonProperty("stopped") Boolean stopped, @JsonProperty("truncated") Boolean truncated,
		@JsonProperty("toolCalls") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ToolCallView> toolCalls,
		@JsonProperty("toolCallStates") @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> toolCallStates,
			@JsonProperty("toolCallId") String toolCallId, @JsonProperty("name") String name,
		@JsonProperty("kind2") String kind2, @JsonProperty("status") String status,
			@JsonProperty("result") String result, @JsonProperty("code") String code,
			@JsonProperty("toolTruncated") Boolean toolTruncated, @JsonProperty("toolStrategy") String toolStrategy,
		@JsonProperty("toolOriginalChars") Integer toolOriginalChars,
			@JsonProperty("toolReturnedChars") Integer toolReturnedChars,
			@JsonProperty("errorCode") String errorCode, @JsonProperty("errorMessage") String errorMessage,
			@JsonProperty("outcome") String outcome,
		@JsonProperty("errorCardKey") String errorCardKey, @JsonProperty("unconfirmed") Boolean unconfirmed,
		@JsonProperty("segments") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SegmentView> segments,
			@JsonProperty("toolCallsPresent") boolean toolCallsPresent,
			@JsonProperty("toolCallStatesPresent") boolean toolCallStatesPresent,
			@JsonProperty("segmentsPresent") boolean segmentsPresent) {

	public TranscriptEntry {
		toolCalls = toolCalls == null || toolCalls.isEmpty() ? List.of()
				: Collections.unmodifiableList(new ArrayList<>(toolCalls));
		toolCallStates = toolCallStates == null || toolCallStates.isEmpty() ? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(toolCallStates));
		segments = segments == null || segments.isEmpty() ? List.of()
				: Collections.unmodifiableList(new ArrayList<>(segments));
	}

	@JsonCreator
	public TranscriptEntry(@JsonProperty("entryId") String entryId, @JsonProperty("kind") String kind,
			@JsonProperty("runId") String runId,
			@JsonProperty("turnStartedAtMs") @JsonFormat(shape = JsonFormat.Shape.STRING) long turnStartedAtMs,
			@JsonProperty("atMs") @JsonFormat(shape = JsonFormat.Shape.STRING) long atMs,
			@JsonProperty("text") String text,
			@JsonProperty("reasoning") String reasoning, @JsonProperty("stopped") Boolean stopped,
			@JsonProperty("truncated") Boolean truncated, @JsonProperty("toolCalls") List<ToolCallView> toolCalls,
			@JsonProperty("toolCallStates") Map<String, Object> toolCallStates,
			@JsonProperty("toolCallId") String toolCallId, @JsonProperty("name") String name,
			@JsonProperty("kind2") String kind2, @JsonProperty("status") String status,
			@JsonProperty("result") String result, @JsonProperty("code") String code,
			@JsonProperty("toolTruncated") Boolean toolTruncated, @JsonProperty("toolStrategy") String toolStrategy,
			@JsonProperty("toolOriginalChars") Integer toolOriginalChars,
			@JsonProperty("toolReturnedChars") Integer toolReturnedChars, @JsonProperty("errorCode") String errorCode,
			@JsonProperty("errorMessage") String errorMessage, @JsonProperty("outcome") String outcome,
			@JsonProperty("errorCardKey") String errorCardKey, @JsonProperty("unconfirmed") Boolean unconfirmed,
			@JsonProperty("segments") List<SegmentView> segments,
			@JsonProperty("toolCallsPresent") Boolean toolCallsPresent,
			@JsonProperty("toolCallStatesPresent") Boolean toolCallStatesPresent,
			@JsonProperty("segmentsPresent") Boolean segmentsPresent) {
		this(entryId, kind, runId, turnStartedAtMs, atMs, text, reasoning, stopped, truncated, toolCalls,
				toolCallStates, toolCallId, name, kind2, status, result, code, toolTruncated, toolStrategy,
				toolOriginalChars, toolReturnedChars, errorCode, errorMessage, outcome, errorCardKey, unconfirmed,
				segments, isPresent(toolCallsPresent, toolCalls != null && !toolCalls.isEmpty()),
				isPresent(toolCallStatesPresent, toolCallStates != null && !toolCallStates.isEmpty()),
				isPresent(segmentsPresent, segments != null && !segments.isEmpty()));
	}

	private static boolean isPresent(Boolean explicit, boolean nonEmpty) {
		return Boolean.TRUE.equals(explicit) || (explicit == null && nonEmpty);
	}

	public TranscriptEntry(String entryId, String kind, String runId, long turnStartedAtMs, long atMs,
			String text, String reasoning, Boolean stopped, Boolean truncated, List<ToolCallView> toolCalls,
			Map<String, Object> toolCallStates, String toolCallId, String name, String kind2, String status,
			String result, String code, Boolean toolTruncated, String toolStrategy, Integer toolOriginalChars,
			Integer toolReturnedChars, String errorCode, String errorMessage, String outcome, String errorCardKey,
			Boolean unconfirmed, List<SegmentView> segments) {
		this(entryId, kind, runId, turnStartedAtMs, atMs, text, reasoning, stopped, truncated, toolCalls,
				toolCallStates, toolCallId, name, kind2, status, result, code, toolTruncated, toolStrategy,
				toolOriginalChars, toolReturnedChars, errorCode, errorMessage, outcome, errorCardKey, unconfirmed,
				segments, toolCalls != null, toolCallStates != null, segments != null);
	}

	@Override
	public String toString() {
		return "TranscriptEntry[entryId=" + this.entryId + ", kind=" + this.kind + ", runId=" + this.runId
				+ ", turnStartedAtMs=" + this.turnStartedAtMs + ", atMs=" + this.atMs + ", stopped=" + this.stopped
				+ ", truncated=" + this.truncated + ", toolCallId=" + this.toolCallId + ", name=" + this.name
				+ ", status=" + this.status + ", code=" + this.code + ", outcome=" + this.outcome
				+ ", errorCode=" + this.errorCode + ", toolCallsCount=" + this.toolCalls.size()
				+ ", toolCallStatesCount=" + this.toolCallStates.size() + ", segmentsCount=" + this.segments.size()
				+ ", toolCallsPresent=" + this.toolCallsPresent + ", toolCallStatesPresent=" + this.toolCallStatesPresent
				+ ", segmentsPresent=" + this.segmentsPresent + "]";
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCallView(@JsonProperty("id") String id, @JsonProperty("type") String type,
			@JsonProperty("name") String name, @JsonProperty("arguments") String arguments) {
		@Override
		public String toString() {
			return "ToolCallView[id=" + this.id + ", type=" + this.type + ", name=" + this.name
					+ ", argumentsLength=" + (this.arguments == null ? 0 : this.arguments.length()) + "]";
		}
	}

	/** 按成功归档帧顺序记录的可展示段；seq 是该段首帧的归档序号。 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SegmentView(@JsonProperty("kind") String kind, @JsonProperty("text") String text,
			@JsonProperty("toolCallId") String toolCallId,
			@JsonProperty("seq") @JsonFormat(shape = JsonFormat.Shape.STRING) long seq) {
		@Override
		public String toString() {
			return "SegmentView[kind=" + this.kind + ", toolCallId=" + this.toolCallId + ", seq=" + this.seq
					+ ", textLength=" + (this.text == null ? 0 : this.text.length()) + "]";
		}
	}

	@JsonIgnore
	public SegmentPresence segmentPresence() {
		return new SegmentPresence(this.segmentsPresent, this.segments);
	}

	public record SegmentPresence(@JsonProperty("isPresent") boolean isPresent,
			@JsonProperty("values") List<SegmentView> values) {
		public SegmentPresence {
			values = values == null || values.isEmpty() ? List.of()
				: Collections.unmodifiableList(new ArrayList<>(values));
		}

		@Override
		public String toString() {
			return "SegmentPresence[isPresent=" + this.isPresent + ", valuesCount=" + this.values.size() + "]";
		}
	}

	public TranscriptEntry withToolCallStates(Map<String, Object> toolCallStates) {
		return new TranscriptEntry(this.entryId, this.kind, this.runId, this.turnStartedAtMs, this.atMs,
				this.text, this.reasoning, this.stopped, this.truncated, this.toolCalls, toolCallStates,
				this.toolCallId, this.name, this.kind2, this.status, this.result, this.code,
				this.toolTruncated, this.toolStrategy, this.toolOriginalChars, this.toolReturnedChars,
				this.errorCode, this.errorMessage, this.outcome, this.errorCardKey, this.unconfirmed, this.segments,
				this.toolCallsPresent, toolCallStates != null, this.segmentsPresent);
	}

	public TranscriptEntry withSegments(List<SegmentView> segments) {
		return new TranscriptEntry(this.entryId, this.kind, this.runId, this.turnStartedAtMs, this.atMs,
				this.text, this.reasoning, this.stopped, this.truncated, this.toolCalls, this.toolCallStates,
				this.toolCallId, this.name, this.kind2, this.status, this.result, this.code,
				this.toolTruncated, this.toolStrategy, this.toolOriginalChars, this.toolReturnedChars,
				this.errorCode, this.errorMessage, this.outcome, this.errorCardKey, this.unconfirmed, segments,
				this.toolCallsPresent, this.toolCallStatesPresent, segments != null);
	}

	public TranscriptEntry withOutcome(String outcome, String errorCardKey, Boolean unconfirmed, String errorCode,
			String errorMessage) {
		return new TranscriptEntry(this.entryId, this.kind, this.runId, this.turnStartedAtMs, this.atMs,
				this.text, this.reasoning, this.stopped, this.truncated, this.toolCalls, this.toolCallStates,
				this.toolCallId, this.name, this.kind2, this.status, this.result, this.code,
				this.toolTruncated, this.toolStrategy, this.toolOriginalChars, this.toolReturnedChars,
				errorCode == null ? this.errorCode : errorCode, errorMessage == null ? this.errorMessage : errorMessage,
				outcome, errorCardKey, unconfirmed, this.segments,
				this.toolCallsPresent, this.toolCallStatesPresent, this.segmentsPresent);
	}

}
