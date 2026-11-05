package com.example.configmgr.perf.production;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.FrameStore;
import com.example.configmgr.ai.run.FrameWakeBus;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.RedisFrameStore;
import com.example.configmgr.ai.run.RedisFrameWakeBus;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.perf.redis.Manifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

final class FrameWakeScenario {

	private FrameWakeScenario() {
	}

	static final class AppendHotLarge implements ProductionScenario {
		@Override
		public String id() {
			return "frame_append_hot_large";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			FrameScenarioSupport.ownRun(support, dataset, dataset.sessionId + "-run");
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			String runId = dataset.sessionId + "-run";
			RedisFrameStore store = new RedisFrameStore(support.redis, support.mapper, support.aiProperties);
			List<Long> seqs = new ArrayList<>();
			boolean fault = support.faultEnabled("AI_FRAME_APPEND");
			if (!fault) {
				support.faultSkipped(dataset, "AI_FRAME_APPEND");
			}
			support.stage(dataset, "frame_hot_append_128", () -> support.identity(() -> {
				long previous = 0L;
				for (int i = 0; i < 128; i++) {
					if (fault && i == 64) {
						support.faults.failNext("AI_FRAME_APPEND", runId);
					}
					FrameStore.AppendResult result = store.appendAndAllocateSeq(runId,
							new java.util.LinkedHashMap<>(Map.of("type", "delta", "text", "hot-" + i)),
							FrameScenarioSupport.payload(support.mapper));
					if (fault && i == 64) {
						support.faults.clear();
						support.require(dataset, "frame_injected_append_not_delivered", result.seq() <= 0L,
								"injected append failure returns no deliverable seq");
						continue;
					}
					support.require(dataset, "frame_hot_seq_increases_" + i, result.seq() > previous,
							"atomic append remains strictly increasing at index " + i);
					previous = result.seq();
					seqs.add(result.seq());
				}
			}));
			support.require(dataset, "frame_hot_exact_success_count", seqs.size() == (fault ? 127 : 128),
					"success count matches whether append fault injection is enabled");
			support.require(dataset, "frame_hot_no_duplicate_seq", seqs.stream().distinct().count() == seqs.size(),
					"no seq is reused after an append failure");
			support.require(dataset, "frame_hot_tail_authoritative", support.identity(() -> store.lastSeq(runId)) == seqs.get(seqs.size() - 1),
					"tail seq equals the last successful append");
			dataset.state = "measured";
		}
	}

	static final class ReplayGapAndTrim implements ProductionScenario {
		@Override
		public String id() {
			return "frame_replay_gap_and_trim";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			FrameScenarioSupport.ownRun(support, dataset, dataset.sessionId + "-run");
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			String runId = dataset.sessionId + "-run";
			RedisFrameStore store = FrameScenarioStore.smallWindow(support, 16);
			support.stage(dataset, "frame_trim_seed_40", () -> support.identity(() -> {
				for (int i = 0; i < 40; i++) {
					store.appendAndAllocateSeq(runId, new java.util.LinkedHashMap<>(Map.of("type", "delta", "text", "trim-" + i)),
							FrameScenarioSupport.payload(support.mapper));
				}
			}));
			support.require(dataset, "frame_trim_watermark_exists", support.redis.hasKey(support.identity(() -> store.trimKey(runId))),
					"real MAXLEN trimming records the low watermark");
			support.require(dataset, "frame_replay_reports_trim", support.identity(() -> store.replayMeta(runId, 0L)).truncated(),
					"replay metadata reports the real trimmed interval");
			List<FrameStore.RawEntryFrame> all = support.identity(() -> FrameScenarioSupport.readAll(store, runId, 7));
			support.require(dataset, "frame_physical_pages_exact_window", all.size() == 16,
					"seven-entry physical pages return exactly the surviving small window");
			support.require(dataset, "frame_physical_seq_strict", FrameScenarioSupport.strictlyIncreasing(all),
					"physical pages preserve entry/seq order without assuming contiguous seq");

			FrameScenarioSupport.xadd(support, support.identity(() -> store.streamKey(runId)), 10_000_000L, "{bad-json");
			FrameScenarioSupport.xadd(support, support.identity(() -> store.streamKey(runId)), 10_000_001L, "{\"type\":\"delta\",\"text\":\"after-bad\"}");
			String cursor = all.get(all.size() - 1).entryId();
			FrameStore.RawEntryPage bad = support.identity(() -> store.readRawAfterEntry(runId, cursor, 1));
			support.require(dataset, "frame_bad_page_observed", bad.frames().size() == 1
					&& "{bad-json".equals(bad.frames().get(0).json()), "the corrupt physical entry is explicit evidence");
			FrameStore.RawEntryPage after = support.identity(() -> store.readRawAfterEntry(runId, bad.frames().get(0).entryId(), 1));
			support.require(dataset, "frame_bad_cursor_advances", after.frames().size() == 1
					&& after.frames().get(0).seq() == 10_000_001L,
					"the physical cursor advances past the corrupt entry to the next good frame");
			support.require(dataset, "frame_invalid_cursor_degraded", support.identity(() -> store.readRawAfterEntry(runId, "bad-cursor", 1)).degraded(),
					"an invalid physical cursor is degraded, not a false empty page");

			LeaseService oldLease = FrameScenarioSupport.lease(support, "frame-old-owner");
			LeaseService newLease = FrameScenarioSupport.lease(support, "frame-new-owner");
			RunStore runStore = new RunStore(support.redis, support.mapper, support.aiProperties, oldLease);
			LeaseService.AcquireResult first = FrameScenarioSupport.acquire(support, runStore, oldLease, runId);
			long oldEpoch = first.lease().epoch();
			oldLease.release(first.lease());
			LeaseService.AcquireResult second = FrameScenarioSupport.acquire(support, runStore, newLease, runId);
			long beforeStale = support.identity(() -> store.lastSeq(runId));
			support.require(dataset, "frame_new_epoch_advanced", second.lease().epoch() > oldEpoch,
					"new owner advances the run fence");
			FrameStore.AppendResult stale = support.identity(() -> store.appendAndAllocateSeq(runId,
					new java.util.LinkedHashMap<>(Map.of("type", "delta", "text", "stale")),
					FrameScenarioSupport.payload(support.mapper), oldEpoch));
			support.require(dataset, "frame_old_epoch_rejected", stale.seq() < 0L && support.identity(() -> store.lastSeq(runId)) == beforeStale,
					"stale epoch cannot append or advance the authoritative stream");
			newLease.release(second.lease());
			dataset.state = "measured";
		}
	}

	static final class WakeCoalesceAndReconnect implements ProductionScenario {
		@Override
		public String id() {
			return "wake_coalesce_and_reconnect";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			FrameScenarioSupport.ownRun(support, dataset, dataset.sessionId + "-run");
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			String runId = dataset.sessionId + "-run";
			RedisFrameWakeBus bus = new RedisFrameWakeBus(support.redis);
			Queue<Long> wakes = new ConcurrentLinkedQueue<>();
			try {
				support.stage(dataset, "wake_coalesce_and_reconnect", () -> support.identity(() -> {
					FrameWakeBus.Subscription first = bus.subscribeHandle(runId, 1L, wakes::add);
					for (long seq = 1; seq <= 3; seq++) {
						bus.publish(runId, seq, false);
					}
					bus.publish(runId, 4L, true);
					FrameScenarioSupport.awaitSize(wakes, 1, 3_000L);
					FrameScenarioSupport.sleep(100L);
					support.require(dataset, "wake_terminal_upper_bound", wakes.peek() == 4L,
							"terminal wake flushes the latest upper bound");
					support.require(dataset, "wake_coalesced_not_per_frame", wakes.size() <= 2,
							"nonterminal wakes coalesce instead of publishing once per frame");
					if (first != null) {
						first.close();
					}
					FrameWakeBus.Subscription second = bus.subscribeHandle(runId, 2L, wakes::add);
					bus.publish(runId, 5L, true);
					FrameScenarioSupport.awaitSize(wakes, 2, 3_000L);
					support.require(dataset, "wake_reconnect_latest", wakes.stream().mapToLong(Long::longValue).max().orElse(0L) == 5L,
							"a new subscription generation receives the next terminal wake");
					if (second != null) {
						second.close();
					}
				}));
				dataset.state = "measured";
			}
			finally {
				FrameScenarioSupport.stopBus(bus);
			}
		}
	}

	private static final class FrameScenarioStore {
		private FrameScenarioStore() {
		}

		static RedisFrameStore smallWindow(ProductionSupport support, int window) {
			try {
				java.lang.reflect.Constructor<RedisFrameStore> constructor = RedisFrameStore.class.getDeclaredConstructor(
						StringRedisTemplate.class, ObjectMapper.class, AiProperties.class, int.class);
				constructor.setAccessible(true);
				return constructor.newInstance(support.redis, support.mapper, support.aiProperties, window);
			}
			catch (Exception ex) {
				throw new IllegalStateException("unable to create production frame store with a small window", ex);
			}
		}

		static List<FrameStore.RawEntryFrame> readAll(RedisFrameStore store, String runId, int pageSize) {
			List<FrameStore.RawEntryFrame> out = new ArrayList<>();
			String cursor = null;
			while (true) {
				FrameStore.RawEntryPage page = store.readRawAfterEntry(runId, cursor, pageSize);
				if (page.degraded()) {
					throw new IllegalStateException("physical replay degraded");
				}
				out.addAll(page.frames());
				if (page.frames().size() < pageSize) {
					return out;
				}
				cursor = page.frames().get(page.frames().size() - 1).entryId();
			}
		}

		static boolean strictlyIncreasing(List<FrameStore.RawEntryFrame> frames) {
			long previous = -1L;
			for (FrameStore.RawEntryFrame frame : frames) {
				if (frame.seq() <= previous) {
					return false;
				}
				previous = frame.seq();
			}
			return true;
		}

		static void xadd(ProductionSupport support, String key, long seq, String json) {
			support.identity(() -> support.redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
				connection.execute("XADD", key.getBytes(java.nio.charset.StandardCharsets.UTF_8),
						(seq + "-0").getBytes(java.nio.charset.StandardCharsets.UTF_8),
						"seq".getBytes(java.nio.charset.StandardCharsets.UTF_8),
						String.valueOf(seq).getBytes(java.nio.charset.StandardCharsets.UTF_8),
						"frame".getBytes(java.nio.charset.StandardCharsets.UTF_8),
						json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				return null;
			}));
		}
	}

	private static final class FrameScenarioSupport {
		private FrameScenarioSupport() {
		}

		static void ownRun(ProductionSupport support, Manifest.Dataset dataset, String runId) {
			if (!dataset.runIds.contains(runId)) {
				dataset.runIds.add(runId);
			}
			for (String prefix : List.of("ai:run:", "ai:runstate:", "ai:pending:", "ai:claim:", "ai:ledger:",
					"ai:idem:", "ai:seq:", "ai:xevents:", "ai:lease:run:", "ai:fence:run:", "ai:req:",
					"ai:decided:", "ai:lease:epoch:", "ai:xack:", "ai:xtrim:")) {
				support.own(dataset, RunStore.runScopedKey(prefix, support.cfg.tenantId, support.cfg.userId, runId));
			}
			support.own(dataset, RunStore.runScopedKey(com.example.configmgr.ai.run.CancellationRegistry.CANCEL_PREFIX,
					support.cfg.tenantId, support.cfg.userId, runId));
		}

		static FrameStore.PayloadFactory payload(ObjectMapper mapper) {
			return frame -> {
				try {
					return mapper.writeValueAsBytes(frame);
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			};
		}

		static LeaseService lease(ProductionSupport support, String owner) {
			return new LeaseService(support.redis, support.aiProperties, (task, interval) -> () -> { }, owner);
		}

		static LeaseService.AcquireResult acquire(ProductionSupport support, RunStore store, LeaseService leases,
				String runId) {
			return support.identity(() -> leases.acquireFenced(store.runLeaseKey(runId), store.fenceKey(runId),
					Duration.ofSeconds(30)));
		}

		static List<FrameStore.RawEntryFrame> readAll(RedisFrameStore store, String runId, int pageSize) {
			List<FrameStore.RawEntryFrame> all = new ArrayList<>();
			String cursor = null;
			for (int page = 0; page < 1000; page++) {
				FrameStore.RawEntryPage current = store.readRawAfterEntry(runId, cursor, pageSize);
				if (current.degraded()) {
					throw new IllegalStateException("physical frame page read degraded");
				}
				if (current.frames().isEmpty()) {
					break;
				}
				all.addAll(current.frames());
				cursor = current.frames().get(current.frames().size() - 1).entryId();
				if (current.frames().size() < pageSize) {
					break;
				}
			}
			return all;
		}

		static boolean strictlyIncreasing(List<FrameStore.RawEntryFrame> frames) {
			long previous = Long.MIN_VALUE;
			for (FrameStore.RawEntryFrame frame : frames) {
				if (frame.seq() <= previous) {
					return false;
				}
				previous = frame.seq();
			}
			return true;
		}

		static void xadd(ProductionSupport support, String key, long seq, String json) {
			support.redis.execute((RedisCallback<Object>) connection -> connection.execute("XADD",
					key.getBytes(StandardCharsets.UTF_8), (seq + "-0").getBytes(StandardCharsets.UTF_8),
					"frame".getBytes(StandardCharsets.UTF_8), json.getBytes(StandardCharsets.UTF_8)));
		}

		static void awaitSize(Queue<Long> values, int size, long timeoutMs) {
			long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
			while (values.size() < size && System.nanoTime() < deadline) {
				sleep(5L);
			}
			if (values.size() < size) {
				throw new IllegalStateException("wake did not arrive within " + timeoutMs + "ms");
			}
		}

		static void sleep(long millis) {
			try {
				Thread.sleep(millis);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		}

		static void stopBus(RedisFrameWakeBus bus) {
			try {
				java.lang.reflect.Method method = RedisFrameWakeBus.class.getDeclaredMethod("stop");
				method.setAccessible(true);
				method.invoke(bus);
			}
			catch (Exception ex) {
				throw new IllegalStateException("unable to stop frame wake bus", ex);
			}
		}
	}
}
