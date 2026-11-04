package com.example.configmgr.perf.redis;

import java.util.ArrayList;
import java.util.List;

/**
 * archive 索引成员的去留：已删除或本来不存在（stale）的成员可摘除；
 * 类型不符的键与成员必须保留，只有索引确实为空才能删除索引。
 */
public final class ArchiveIndexPolicy {

	public record BatchResult(List<String> removableMembers, List<String> retainedMembers,
			long deleted, long absent, long typeMismatch) {
	}

	private ArchiveIndexPolicy() {
	}

	public static BatchResult classifyBatch(List<String> members, List<Long> typeDelResults) {
		List<String> removable = new ArrayList<>();
		List<String> retained = new ArrayList<>();
		long deleted = 0L;
		long absent = 0L;
		long mismatch = 0L;
		int count = Math.min(members.size(), typeDelResults.size());
		for (int i = 0; i < count; i++) {
			String member = members.get(i);
			long result = typeDelResults.get(i) == null ? 0L : typeDelResults.get(i);
			if (result > 0L) {
				deleted += result;
				removable.add(member);
			}
			else if (result == 0L) {
				absent++;
				removable.add(member);
			}
			else {
				mismatch++;
				retained.add(member);
			}
		}
		for (int i = count; i < members.size(); i++) {
			retained.add(members.get(i));
		}
		return new BatchResult(List.copyOf(removable), List.copyOf(retained), deleted, absent, mismatch);
	}

	public static boolean shouldDeleteIndex(long remainingMembers) {
		return remainingMembers == 0L;
	}

}
