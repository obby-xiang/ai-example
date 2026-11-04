package com.example.configmgr.perf.redis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 逐阶段/逐请求指标。命令数与批次数来自 {@link CountingRedis} 的真实命令调用；
 * 字节是估算值，统一在输出里标 {@code bytesEstimated=true}。
 */
public final class HarnessMetrics {

	public record StageRecord(String scenario, int round, String stage, long durationNanos,
			long commands, long pipelineBatches, long scanPages, long slotChecks,
			long requestBytes, long responseBytes, boolean bytesEstimated, String status, String detail) {
	}

	public record ProtocolRecord(String scenario, String name, boolean pass, String detail, long durationNanos) {
	}

	public static final class Counter {

		public long commands;

		public long pipelineBatches;

		public long scanPages;

		public long slotChecks;

		public long requestBytes;

		public long responseBytes;

	}

	private static final class Aggregate {

		private final List<Long> latenciesNanos = new ArrayList<>();

		private final Map<Integer, Counter> rounds = new LinkedHashMap<>();

		private String status = "skipped";

		private String detail = "";

	}

	private final List<StageRecord> stages = new ArrayList<>();

	private final List<ProtocolRecord> protocols = new ArrayList<>();

	private final Map<String, Aggregate> aggregates = new LinkedHashMap<>();

	private String currentScenario;

	private int currentRound;

	public void beginRound(String scenario, int round) {
		this.currentScenario = scenario;
		this.currentRound = round;
		aggregate(scenario).rounds.computeIfAbsent(round, ignored -> new Counter());
	}

	public <T> T stage(CountingRedis redis, String scenario, int round, String name, Supplier<T> body) {
		CountingRedis.Snapshot before = redis.snapshot();
		long start = System.nanoTime();
		try {
			T value = body.get();
			long duration = System.nanoTime() - start;
			CountingRedis.Snapshot after = redis.snapshot();
			CountingRedis.Snapshot delta = CountingRedis.delta(before, after);
			record(scenario, round, name, duration, delta, "ok", "");
			return value;
		}
		catch (RuntimeException ex) {
			long duration = System.nanoTime() - start;
			CountingRedis.Snapshot after = redis.snapshot();
			CountingRedis.Snapshot delta = CountingRedis.delta(before, after);
			record(scenario, round, name, duration, delta, "failed", ex.getMessage());
			throw ex;
		}
	}

	private void record(String scenario, int round, String name, long duration, CountingRedis.Snapshot delta,
			String status, String detail) {
		this.stages.add(new StageRecord(scenario, round, name, duration, delta.commands(),
				delta.pipelineBatches(), delta.scanPages(), delta.slotChecks(), delta.requestBytes(),
				delta.responseBytes(), true, status, detail));
		Aggregate aggregate = aggregate(scenario);
		Counter counter = aggregate.rounds.computeIfAbsent(round, ignored -> new Counter());
		counter.commands += delta.commands();
		counter.pipelineBatches += delta.pipelineBatches();
		counter.scanPages += delta.scanPages();
		counter.slotChecks += delta.slotChecks();
		counter.requestBytes += delta.requestBytes();
		counter.responseBytes += delta.responseBytes();
	}

	public void deleteLatency(String scenario, int round, long nanos, boolean warmup) {
		Aggregate aggregate = aggregate(scenario);
		if (!warmup) {
			aggregate.latenciesNanos.add(nanos);
		}
	}

	public void scenarioStatus(String scenario, String status, String detail) {
		Aggregate aggregate = aggregate(scenario);
		aggregate.status = status;
		aggregate.detail = detail == null ? "" : detail;
	}

	public void protocol(String scenario, String name, boolean pass, String detail, long durationNanos) {
		this.protocols.add(new ProtocolRecord(scenario, name, pass, detail, durationNanos));
	}

	public Set<String> scenarios() {
		return new LinkedHashSet<>(this.aggregates.keySet());
	}

	public List<StageRecord> stages() {
		return List.copyOf(this.stages);
	}

	public List<ProtocolRecord> protocols() {
		return List.copyOf(this.protocols);
	}

	public boolean protocolsPassed() {
		return this.protocols.stream().allMatch(ProtocolRecord::pass);
	}

	public GateEvaluator.Outcome outcome(String scenario) {
		Aggregate aggregate = this.aggregates.get(scenario);
		if (aggregate == null) {
			return null;
		}
		LatencyStats stats = LatencyStats.of(aggregate.latenciesNanos);
		Counter last = aggregate.rounds.isEmpty() ? new Counter()
				: aggregate.rounds.get(aggregate.rounds.keySet().stream().reduce((a, b) -> b).orElse(0));
		return new GateEvaluator.Outcome(scenario, aggregate.status, stats.p95Ms(), last.commands,
				last.scanPages);
	}

	public Map<String, Object> summary(String scenario) {
		Aggregate aggregate = aggregate(scenario);
		LatencyStats stats = LatencyStats.of(aggregate.latenciesNanos);
		Counter last = aggregate.rounds.isEmpty() ? new Counter()
				: aggregate.rounds.get(aggregate.rounds.keySet().stream().reduce((a, b) -> b).orElse(0));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("scenario", scenario);
		out.put("status", aggregate.status);
		out.put("detail", aggregate.detail);
		out.put("count", stats.count());
		out.put("minMs", stats.minMs());
		out.put("meanMs", stats.meanMs());
		out.put("p50Ms", stats.p50Ms());
		out.put("p95Ms", stats.p95Ms());
		out.put("p99Ms", stats.p99Ms());
		out.put("maxMs", stats.maxMs());
		out.put("commands", last.commands);
		out.put("pipelineBatches", last.pipelineBatches);
		out.put("scanPages", last.scanPages);
		out.put("slotChecks", last.slotChecks);
		out.put("requestBytesEstimated", last.requestBytes);
		out.put("responseBytesEstimated", last.responseBytes);
		out.put("bytesEstimated", true);
		return out;
	}

	public List<Map<String, Object>> allSummaries() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (String scenario : this.aggregates.keySet()) {
			out.add(summary(scenario));
		}
		return out;
	}

	public Counter lastCounter(String scenario) {
		Aggregate aggregate = this.aggregates.get(scenario);
		if (aggregate == null || aggregate.rounds.isEmpty()) {
			return new Counter();
		}
		return aggregate.rounds.get(aggregate.rounds.keySet().stream().reduce((a, b) -> b).orElse(0));
	}

	private Aggregate aggregate(String scenario) {
		return this.aggregates.computeIfAbsent(scenario, ignored -> new Aggregate());
	}

}
