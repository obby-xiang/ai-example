package com.example.configmgr.perf.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按 Stream <b>物理 entry ID</b> 分页（XRANGE 的排他游标 {@code (id}）。
 *
 * <p>预算按真实条目数扣减，不按 {@code seq} 差值；跳号时不会把 900000 当成"超出预算"。
 * 预算耗尽且可能还有条目时返回 {@code truncated=true}，此时未找到的终帧/tool_start 属于
 * unknown，不得写成"不存在"。
 */
public final class StreamPager {

	public record Entry(String id, Map<String, String> fields) {
	}

	public interface PageSource {

		/** {@code exclusiveStartId} 为 {@code -} 或 {@code (lastId)}，直接传给 XRANGE。 */
		List<Entry> fetch(String exclusiveStartId, int count);

	}

	public record Result(List<Entry> entries, int pages, boolean truncated, String lastEntryId,
			boolean terminalFound, boolean toolStartFound, boolean terminalUnknown, boolean toolStartUnknown) {
	}

	private StreamPager() {
	}

	public static Result scan(PageSource source, int pageSize, int maxEntries) {
		if (pageSize <= 0 || maxEntries <= 0) {
			throw new IllegalArgumentException("pageSize/maxEntries 必须为正");
		}
		List<Entry> all = new ArrayList<>();
		String cursor = "-";
		int pages = 0;
		boolean truncated = false;
		while (true) {
			int remaining = maxEntries - all.size();
			if (remaining <= 0) {
				truncated = true;
				break;
			}
			int count = Math.min(pageSize, remaining);
			List<Entry> page = source.fetch(cursor, count);
			pages++;
			if (page == null || page.isEmpty()) {
				break;
			}
			all.addAll(page);
			cursor = "(" + page.get(page.size() - 1).id();
			if (page.size() < count) {
				break;
			}
			if (all.size() >= maxEntries) {
				truncated = true;
				break;
			}
		}
		boolean terminal = false;
		boolean toolStart = false;
		for (Entry entry : all) {
			String frame = entry.fields() == null ? null : entry.fields().get("frame");
			if (isTerminal(frame)) {
				terminal = true;
			}
			if (isToolStart(frame)) {
				toolStart = true;
			}
		}
		String lastId = all.isEmpty() ? null : all.get(all.size() - 1).id();
		return new Result(List.copyOf(all), pages, truncated, lastId, terminal, toolStart,
				truncated && !terminal, truncated && !toolStart);
	}

	public static boolean isTerminal(String frameJson) {
		return containsType(frameJson, "done") || containsType(frameJson, "error");
	}

	public static boolean isToolStart(String frameJson) {
		return containsType(frameJson, "tool_start");
	}

	private static boolean containsType(String frameJson, String type) {
		if (frameJson == null) {
			return false;
		}
		return frameJson.replace(" ", "").contains("\"type\":\"" + type + "\"");
	}

}
