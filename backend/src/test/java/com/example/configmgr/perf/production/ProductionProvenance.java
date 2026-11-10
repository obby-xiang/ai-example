package com.example.configmgr.perf.production;

import com.example.configmgr.config.RedisClusterTopologyGuard;
import com.example.configmgr.ai.context.RedisContextStateStore;
import com.example.configmgr.ai.form.RedisFormAttachmentStore;
import com.example.configmgr.ai.history.ConversationTranscriptStore;
import com.example.configmgr.ai.kb.CachedKbSource;
import com.example.configmgr.ai.kb.KbService;
import com.example.configmgr.ai.kb.RedisKbCacheStore;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.CreateTaskDraftStore;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.RedisFrameStore;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.StartupResumeRunner;
import com.example.configmgr.ai.session.AiSessionPurgeService;
import com.example.configmgr.ai.session.SessionPurgePendingStore;
import com.example.configmgr.ai.config.AiModelCatalog;
import com.example.configmgr.job.service.JobHeartbeatRegistry;
import com.example.configmgr.perf.redis.Manifest;
import com.example.configmgr.task.service.RedisTaskEventBus;
import com.example.configmgr.task.service.TaskSseService;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

final class ProductionProvenance {

	private static final List<Class<?>> PRODUCTION_CLASSES = List.of(
			ConversationTranscriptStore.class, LeaseService.class, RedisFrameStore.class, TaskSseService.class,
			RedisChatMemoryRepository.class, RunStore.class, CachedKbSource.class, RedisClusterTopologyGuard.class,
			AiModelCatalog.class, CreateTaskDraftStore.class, JobHeartbeatRegistry.class, SessionPurgePendingStore.class,
			CancellationRegistry.class, RedisKbCacheStore.class, KbService.class, RedisFormAttachmentStore.class,
			AiSessionPurgeService.class, StartupResumeRunner.class, RedisTaskEventBus.class, RedisContextStateStore.class);

	private static final List<ScriptRef> LUA_SCRIPTS = List.of(
			new ScriptRef(ConversationTranscriptStore.class, "READ_PAGE_SCRIPT"),
			new ScriptRef(LeaseService.class, "RELEASE_SCRIPT"),
			new ScriptRef(RedisFrameStore.class, "APPEND_SCRIPT"),
			new ScriptRef(RedisChatMemoryRepository.class, "MERGE_CAS"),
			new ScriptRef(RedisChatMemoryRepository.class, "MEMORY_CLEAR"),
			new ScriptRef(RunStore.class, "CLAIM_EXECUTION_SCRIPT"),
			new ScriptRef(RunStore.class, "RECOVERY_REMOVE_SCRIPT"),
			new ScriptRef(RedisKbCacheStore.class, "DELETE_INDEX_IF_GENERATION_AT_MOST"));

	private ProductionProvenance() {
	}

	static Map<String, Object> capture(Manifest manifest) {
		Map<String, Object> out = new LinkedHashMap<>();
		Path repoRoot = repoRoot();
		out.put("git", gitEvidence(repoRoot));
		List<Map<String, Object>> harness = new ArrayList<>();
		for (String scenario : new TreeSet<>(manifest.datasets.stream().map(dataset -> dataset.scenario).toList())) {
			harness.add(classEvidence(ProductionScenarioCatalog.byId(scenario).getClass(), repoRoot));
		}
		out.put("harnessClasses", harness);
		List<Map<String, Object>> production = new ArrayList<>();
		for (Class<?> type : PRODUCTION_CLASSES) {
			production.add(classEvidence(type, repoRoot));
		}
		out.put("productionClasses", production);
		List<Map<String, Object>> scripts = new ArrayList<>();
		for (ScriptRef ref : LUA_SCRIPTS) {
			scripts.add(scriptEvidence(ref));
		}
		out.put("luaScripts", scripts);
		return out;
	}

	private static Map<String, Object> gitEvidence(Path repoRoot) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("commit", git(repoRoot, "rev-parse", "HEAD"));
		out.put("branch", git(repoRoot, "branch", "--show-current"));
		Object status = git(repoRoot, "status", "--porcelain=v1");
		out.put("dirty", status instanceof String text && !text.isBlank());
		out.put("status", status);
		return out;
	}

	private static Object git(Path repoRoot, String... args) {
		List<String> command = new ArrayList<>();
		command.add("git");
		command.addAll(List.of(args));
		try {
			Process process = new ProcessBuilder(command).directory(repoRoot.toFile()).start();
			String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			if (!process.waitFor(5L, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return Map.of("error", "timeout");
			}
			int exit = process.exitValue();
			if (exit != 0) {
				return Map.of("error", "exit=" + exit, "detail", stderr);
			}
			return stdout;
		}
		catch (Exception ex) {
			return Map.of("error", ex.getClass().getSimpleName());
		}
	}

	private static Map<String, Object> classEvidence(Class<?> type, Path repoRoot) {
		Map<String, Object> out = new LinkedHashMap<>();
		String binaryName = type.getName();
		String topLevelName = binaryName.contains("$") ? binaryName.substring(0, binaryName.indexOf('$')) : binaryName;
		String relative = topLevelName.replace('.', '/');
		out.put("class", binaryName);
		out.put("classSha256", resourceSha256(type));
		String sourceRoot = topLevelName.startsWith("com.example.configmgr.perf.")
				? "backend/src/test/java/" : "backend/src/main/java/";
		Path source = repoRoot.resolve(sourceRoot + relative + ".java");
		out.put("source", sourceRoot + relative + ".java");
		out.put("sourceSha256", fileSha256(source));
		return out;
	}

	private static Map<String, Object> scriptEvidence(ScriptRef ref) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("class", ref.owner.getName());
		out.put("field", ref.field);
		try {
			java.lang.reflect.Field field = ref.owner.getDeclaredField(ref.field);
			field.setAccessible(true);
			Object value = field.get(null);
			if (value instanceof org.springframework.data.redis.core.script.RedisScript<?> script) {
				out.put("sha256", sha256(script.getScriptAsString().getBytes(StandardCharsets.UTF_8)));
			}
			else {
				out.put("error", "not a RedisScript");
			}
		}
		catch (Exception ex) {
			out.put("error", ex.getClass().getSimpleName());
		}
		return out;
	}

	private static String resourceSha256(Class<?> type) {
		String resource = type.getSimpleName() + ".class";
		URL url = type.getResource(resource);
		if (url == null) {
			return "unavailable";
		}
		try (InputStream input = url.openStream()) {
			return sha256(input.readAllBytes());
		}
		catch (Exception ex) {
			return "error:" + ex.getClass().getSimpleName();
		}
	}

	private static String fileSha256(Path path) {
		if (!Files.isRegularFile(path)) {
			return "unavailable";
		}
		try {
			return sha256(Files.readAllBytes(path));
		}
		catch (IOException ex) {
			return "error:" + ex.getClass().getSimpleName();
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Path repoRoot() {
		Path current = Path.of("").toAbsolutePath().normalize();
		for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
			if (Files.exists(candidate.resolve(".git"))) {
				return candidate;
			}
		}
		return current;
	}

	private record ScriptRef(Class<?> owner, String field) {
	}
}
