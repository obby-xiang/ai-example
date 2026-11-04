package com.example.configmgr.perf.redis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamPagerTest {

	@Test
	void pagesByPhysicalEntryIdAndFindsFramesAcrossHugeSeqGap() {
		List<StreamPager.Entry> all = List.of(
				entry("1-0", "done", "1"),
				entry("500000-0", "tool_start", "500000"),
				entry("900000-0", "delta", "900000"));
		FakeSource source = new FakeSource(all);
		StreamPager.Result result = StreamPager.scan(source, 2, 10);
		assertEquals(3, result.entries().size());
		assertFalse(result.truncated());
		assertTrue(result.terminalFound());
		assertTrue(result.toolStartFound());
		assertEquals(List.of("-", "(500000-0"), source.calls.subList(0, 2));
		assertEquals(2, source.calls.size());
	}

	@Test
	void entryBudgetIsCountedByRealEntriesNotSeqDifference() {
		List<StreamPager.Entry> all = List.of(
				entry("1-0", "done", "1"),
				entry("900000-0", "tool_start", "900000"));
		FakeSource source = new FakeSource(all);
		StreamPager.Result result = StreamPager.scan(source, 1, 1);
		assertEquals(1, result.entries().size());
		assertTrue(result.truncated(), "预算耗尽且还有潜在条目时必须标为不完整");
		assertTrue(result.terminalFound());
		assertFalse(result.toolStartFound());
		assertTrue(result.toolStartUnknown(), "未扫完时 tool_start 不得判成不存在");
	}

	@Test
	void terminalAtFirstStillAllowsLaterPages() {
		List<StreamPager.Entry> all = List.of(
				entry("1-0", "done", "1"),
				entry("2-0", "tool_start", "2"),
				entry("3-0", "delta", "3"),
				entry("900000-0", "error", "900000"));
		FakeSource source = new FakeSource(all);
		StreamPager.Result result = StreamPager.scan(source, 3, 100);
		assertEquals(4, result.entries().size());
		assertTrue(result.terminalFound());
		assertTrue(result.toolStartFound());
		assertFalse(result.truncated());
	}

	@Test
	void fullLastPageNeedsOneEmptyProbeToProveCompletion() {
		List<StreamPager.Entry> all = List.of(
				entry("1-0", "delta", "1"),
				entry("2-0", "done", "2"));
		FakeSource source = new FakeSource(all);
		StreamPager.Result result = StreamPager.scan(source, 2, 10);
		assertFalse(result.truncated());
		assertEquals(2, result.entries().size());
		assertEquals("-", source.calls.get(0));
		assertEquals("(2-0", source.calls.get(1));
	}

	private static StreamPager.Entry entry(String id, String type, String seq) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("seq", seq);
		fields.put("frame", "{\"type\":\"" + type + "\",\"seq\":" + seq + "}");
		return new StreamPager.Entry(id, fields);
	}

	private static final class FakeSource implements StreamPager.PageSource {

		private final List<StreamPager.Entry> all;

		private final List<String> calls = new ArrayList<>();

		private FakeSource(List<StreamPager.Entry> all) {
			this.all = all;
		}

		@Override
		public List<StreamPager.Entry> fetch(String exclusiveStartId, int count) {
			calls.add(exclusiveStartId);
			List<StreamPager.Entry> page = new ArrayList<>();
			boolean after = "-".equals(exclusiveStartId);
			String cursor = exclusiveStartId.startsWith("(") ? exclusiveStartId.substring(1) : null;
			for (StreamPager.Entry e : all) {
				if (!after && cursor != null && compareIds(e.id(), cursor) <= 0) {
					continue;
				}
				page.add(e);
				if (page.size() == count) {
					break;
				}
			}
			return page;
		}

		private static int compareIds(String a, String b) {
			String[] pa = a.split("-");
			String[] pb = b.split("-");
			int c = Long.compare(Long.parseLong(pa[0]), Long.parseLong(pb[0]));
			return c != 0 ? c : Long.compare(Long.parseLong(pa[1]), Long.parseLong(pb[1]));
		}

	}

	private static final class DurationMs {

		private static long toNanos(long ms) {
			return ms * 1_000_000L;
		}

	}

}
