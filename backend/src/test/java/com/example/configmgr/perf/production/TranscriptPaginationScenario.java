package com.example.configmgr.perf.production;

import com.example.configmgr.ai.history.ConversationTranscriptStore;
import com.example.configmgr.ai.history.TranscriptEntries;
import com.example.configmgr.ai.history.TranscriptEntry;
import com.example.configmgr.perf.redis.Manifest;
import com.example.configmgr.ai.tenant.RequestContextUtils;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scaled proof of the real 5000-entry/8MiB trimming and cursor-pagination scripts. */
final class TranscriptPaginationScenario implements ProductionScenario {

	@Override
	public String id() {
		return "transcript_large_pagination";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		for (String sessionId : List.of(dataset.sessionId, dataset.sessionId + "-bytes")) {
			support.own(dataset, ConversationTranscriptStore.dataKey(sessionId, support.cfg.tenantId, support.cfg.userId));
			support.own(dataset, ConversationTranscriptStore.orderKey(sessionId, support.cfg.tenantId, support.cfg.userId));
			support.own(dataset, ConversationTranscriptStore.metaKey(sessionId, support.cfg.tenantId, support.cfg.userId));
			support.own(dataset, ConversationTranscriptStore.truncatedKey(sessionId, support.cfg.tenantId, support.cfg.userId));
		}
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) {
		long scanBefore = support.commandMetrics.keyspaceScanPages();
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(support.cfg.tenantId,
				support.cfg.userId)) {
			boolean valid = true;
			try {
			valid &= support.stage(dataset, "transcript_count_trim_and_pagination", () -> {
				ConversationTranscriptStore store = store(support, 7, 1_000_000L);
				List<Boolean> appendResults = new ArrayList<>();
				for (int i = 0; i < 9; i++) {
					final int entryIndex = i;
					appendResults.add(support.identity(() -> store.append(dataset.sessionId, support.cfg.tenantId,
							support.cfg.userId, List.of(entry("count", entryIndex, 32)), 1L)));
				}
				ConversationTranscriptStore.ReadPage latest = store.readPage(dataset.sessionId, support.cfg.tenantId,
						support.cfg.userId, null, 3);
				ConversationTranscriptStore.ReadPage middle = store.readPage(dataset.sessionId, support.cfg.tenantId,
						support.cfg.userId, 7L, 3);
				ConversationTranscriptStore.ReadPage last = store.readPage(dataset.sessionId, support.cfg.tenantId,
						support.cfg.userId, 4L, 3);
				dataset.attributes.put("transcriptCountAppendResults", appendResults);
				recordPage(dataset, "transcriptLatestPage", latest);
				recordPage(dataset, "transcriptMiddlePage", middle);
				recordPage(dataset, "transcriptLastPage", last);
				boolean pages = support.check(dataset, "transcript_count_trim_boundary",
						latest.total() == 7 && latest.truncated() && ids(latest).equals(List.of("6", "7", "8")),
						"9 entries at maxEntries=7 retain entries 2..8 and expose the truncation marker");
				pages &= support.check(dataset, "transcript_latest_cursor",
						latest.hasMore() && "7".equals(latest.nextCursor()),
						"latest page 6,7,8 points to cursor 7");
				pages &= support.check(dataset, "transcript_before_cursor",
						ids(middle).equals(List.of("3", "4", "5")) && middle.hasMore() && "4".equals(middle.nextCursor()),
						"before=7 returns 3,4,5 and cursor 4");
				pages &= support.check(dataset, "transcript_last_page",
						ids(last).equals(List.of("2")) && !last.hasMore() && last.nextCursor() == null,
						"before=4 returns only retained entry 2 and closes pagination");
				Set<String> union = new LinkedHashSet<>();
				union.addAll(ids(latest));
				union.addAll(ids(middle));
				union.addAll(ids(last));
				pages &= support.check(dataset, "transcript_pages_complete_nonoverlap",
						union.equals(Set.of("2", "3", "4", "5", "6", "7", "8")),
						"three pages cover every retained entry once with no overlap");
				return pages;
			});

			valid &= support.stage(dataset, "transcript_byte_trim_boundary", () -> {
				String sessionId = dataset.sessionId + "-bytes";
				ConversationTranscriptStore store = store(support, 100, 700L);
				for (int i = 0; i < 6; i++) {
					final int entryIndex = i;
					support.identity(() -> store.append(sessionId, support.cfg.tenantId, support.cfg.userId,
							List.of(entry("bytes", entryIndex, 180)), 1L));
				}
				ConversationTranscriptStore.ReadPage page = store.readPage(sessionId, support.cfg.tenantId,
						support.cfg.userId, null, 100);
				boolean trimmed = support.check(dataset, "transcript_byte_trim_boundary",
						page.total() < 6 && page.total() > 0 && page.truncated(),
						"the real maxBytes loop drops oldest entries without emptying the session");
				trimmed &= support.check(dataset, "transcript_byte_trim_keeps_newest",
						ids(page).get(ids(page).size() - 1).equals("5"),
						"the newest entry survives the byte-budget trim");
				return trimmed;
			});
		}
		catch (RuntimeException ex) {
			support.faults.clear();
			throw ex;
		}
		valid &= support.check(dataset, "transcript_no_keyspace_scan",
				scanBefore == support.commandMetrics.keyspaceScanPages(),
				"transcript append/read uses only bounded Lua/HMGET paths, no SCAN");
		support.require(dataset, "transcript_large_pagination_contract", valid,
				"trim and pagination assertions failed");
		dataset.state = "measured";
		}
	}

	private static ConversationTranscriptStore store(ProductionSupport support, int maxEntries, long maxBytes) {
		try {
			Constructor<ConversationTranscriptStore> constructor = ConversationTranscriptStore.class.getDeclaredConstructor(
					org.springframework.data.redis.core.StringRedisTemplate.class,
					com.fasterxml.jackson.databind.ObjectMapper.class, Duration.class, int.class, long.class);
			constructor.setAccessible(true);
			return constructor.newInstance(support.redis, support.mapper, Duration.ofHours(1), maxEntries, maxBytes);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("cannot construct production transcript store with scaled bounds", ex);
		}
	}

	private static TranscriptEntry entry(String runPrefix, int index, int payloadChars) {
		long now = System.currentTimeMillis();
		return TranscriptEntries.user(runPrefix + "-" + index, now, now, "x".repeat(payloadChars));
	}

	private static void recordPage(Manifest.Dataset dataset, String name, ConversationTranscriptStore.ReadPage page) {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("ids", ids(page));
		evidence.put("total", page.total());
		evidence.put("nextCursor", page.nextCursor());
		evidence.put("hasMore", page.hasMore());
		evidence.put("truncated", page.truncated());
		dataset.attributes.put(name, evidence);
	}

	private static List<String> ids(ConversationTranscriptStore.ReadPage page) {
		List<String> ids = new ArrayList<>();
		for (TranscriptEntry entry : page.entries()) {
			String runId = entry.runId();
			ids.add(runId.substring(runId.lastIndexOf('-') + 1));
		}
		return ids;
	}

}
