package com.example.configmgr.perf.redis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 本地 manifest：记录每个场景/copy 的精确键面与背景键公式，cleanup/verify 不依赖 SCAN。
 */
public final class Manifest {

	public String namespace;

	public long tenantId;

	public long userId;

	public String profile;

	public List<Dataset> datasets = new ArrayList<>();

	public static final class Dataset {

		public String scenario;

		public int copy;

		public String sessionId;

		public List<String> runIds = new ArrayList<>();

		public List<String> keys = new ArrayList<>();

		/** 实际生成并属于本命名空间、可清理的背景键数量；外部既有键空间必须为 0。 */
		public int background;

		/** 场景声明的背景规模（bg_existing 可很大，但不代表本工具生成了这些键）。 */
		public int declaredBackground;

		/** true = 背景键空间是外部既有数据，harness 不生成、不遍历、不删除。 */
		public boolean externalBackground;

		public String backgroundBucket;

		public boolean protocol;

		public String state = "generated";

		public Dataset() {
		}

		public Dataset(String scenario, int copy, String sessionId) {
			this.scenario = scenario;
			this.copy = copy;
			this.sessionId = sessionId;
		}

	}

	public static Manifest empty(String namespace, long tenantId, long userId, String profile) {
		Manifest manifest = new Manifest();
		manifest.namespace = namespace;
		manifest.tenantId = tenantId;
		manifest.userId = userId;
		manifest.profile = profile;
		return manifest;
	}

	public Dataset find(String scenario, int copy) {
		for (Dataset dataset : this.datasets) {
			if (dataset.scenario.equals(scenario) && dataset.copy == copy) {
				return dataset;
			}
		}
		return null;
	}

	public Dataset require(String scenario, int copy) {
		Dataset dataset = find(scenario, copy);
		if (dataset == null) {
			throw new IllegalStateException("manifest 缺少场景数据：" + scenario + "/" + copy);
		}
		return dataset;
	}

	public void add(Dataset dataset) {
		Dataset existing = find(dataset.scenario, dataset.copy);
		if (existing != null) {
			this.datasets.remove(existing);
		}
		this.datasets.add(dataset);
	}

	public List<Dataset> forScenario(String scenario) {
		List<Dataset> out = new ArrayList<>();
		for (Dataset dataset : this.datasets) {
			if (dataset.scenario.equals(scenario)) {
				out.add(dataset);
			}
		}
		return out;
	}

	public void addKey(String scenario, int copy, String key) {
		Dataset dataset = require(scenario, copy);
		if (!dataset.keys.contains(key)) {
			dataset.keys.add(key);
		}
	}

	public void addKeyForRun(String runId, String key) {
		for (Dataset dataset : this.datasets) {
			if (dataset.runIds.contains(runId)) {
				if (!dataset.keys.contains(key)) {
					dataset.keys.add(key);
				}
				return;
			}
		}
	}

	/** 显式 namespace 的背景键生成（cleanup/verify 用；不把 10 万条键写进 JSON）。 */
	public static List<String> backgroundKeys(String namespace, Dataset dataset) {
		if (dataset.externalBackground || dataset.background <= 0
				|| "bg_existing_runs_1".equals(dataset.scenario)) {
			return List.of();
		}
		List<String> keys = new ArrayList<>(dataset.background);
		for (int i = 0; i < dataset.background; i++) {
			keys.add(KeyShapes.backgroundKey(namespace, dataset.backgroundBucket, i));
		}
		return keys;
	}

	public Set<String> allKeys() {
		Set<String> keys = new LinkedHashSet<>();
		for (Dataset dataset : this.datasets) {
			keys.addAll(dataset.keys);
			keys.addAll(backgroundKeys(this.namespace, dataset));
		}
		return keys;
	}

	public void save(Path path) {
		try {
			Files.createDirectories(path.getParent());
			mapper().writeValue(path.toFile(), this);
		}
		catch (IOException ex) {
			throw new IllegalStateException("无法写入 manifest " + path, ex);
		}
	}

	public static Manifest load(Path path) {
		try {
			return mapper().readValue(path.toFile(), Manifest.class);
		}
		catch (IOException ex) {
			throw new IllegalStateException("无法读取 manifest " + path, ex);
		}
	}

	public static ObjectMapper mapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.enable(SerializationFeature.INDENT_OUTPUT);
		return mapper;
	}

}
