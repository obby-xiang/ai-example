package com.example.configmgr.perf.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;

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

	@Test
	void xrangeCursorMapsToExclusiveLowerBound() {
		var exclusive = CountingRedis.xrangeRange("(500000-0");
		assertEquals("500000-0", exclusive.getLowerBound().getValue().orElseThrow());
		assertFalse(exclusive.getLowerBound().isInclusive());
		assertFalse(exclusive.getUpperBound().isBounded());

		var unbounded = CountingRedis.xrangeRange("-");
		assertFalse(unbounded.getLowerBound().isBounded());
		assertFalse(unbounded.getUpperBound().isBounded());

		var inclusive = CountingRedis.xrangeRange("900000-0");
		assertEquals("900000-0", inclusive.getLowerBound().getValue().orElseThrow());
		assertTrue(inclusive.getLowerBound().isInclusive());
	}

	@Test
	void structuredStreamRecordsCarryIdAndFrameFields() {
		ByteRecord done = StreamRecords.rawBytes(bytes("seq", "1", "frame", "{\"type\":\"done\"}"))
				.withId(RecordId.of("1-0"));
		ByteRecord toolStart = StreamRecords.rawBytes(bytes("seq", "500000", "frame",
				"{\"type\":\"tool_start\",\"seq\":500000}")).withId(RecordId.of("500000-0"));

		List<StreamPager.Entry> entries = RedisStreams.toEntries(List.of(done, toolStart));

		assertEquals(2, entries.size());
		assertEquals("1-0", entries.get(0).id());
		assertTrue(StreamPager.isTerminal(entries.get(0).fields().get("frame")));
		assertEquals("500000-0", entries.get(1).id());
		assertTrue(StreamPager.isToolStart(entries.get(1).fields().get("frame")));
	}

	private static Map<byte[], byte[]> bytes(String... pairs) {
		Map<byte[], byte[]> out = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			out.put(pairs[i].getBytes(StandardCharsets.UTF_8), pairs[i + 1].getBytes(StandardCharsets.UTF_8));
		}
		return out;
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
