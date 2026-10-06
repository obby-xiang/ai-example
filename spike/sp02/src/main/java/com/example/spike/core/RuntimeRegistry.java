package com.example.spike.core;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class RuntimeRegistry {

	private final Map<String, RunRuntime> runtimes = new ConcurrentHashMap<>();

	private final RunStore store;

	public RuntimeRegistry(RunStore store) {
		this.store = store;
	}

	public RunRuntime runtime(String runId) {
		return this.runtimes.computeIfAbsent(runId, id -> new RunRuntime(id, this.store));
	}

	public RunRuntime find(String runId) {
		return this.runtimes.get(runId);
	}

	public Collection<RunRuntime> all() {
		return this.runtimes.values();
	}

}
