package com.example.configmgr.perf.redis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 场景矩阵。A 的性能/完整性场景与 B 的协议正确性场景共用同一套连接与命令实现。
 */
public final class ScenarioCatalog {

	public record Scenario(String id, String description, int runs, int staging, int archives,
			int frames, int background, boolean large, boolean protocol) {
	}

	private static final Map<String, Scenario> ALL = new LinkedHashMap<>();

	private static final List<String> SMOKE = List.of(
			"empty", "minimal", "runs_1_terminal", "runs_10_terminal",
			"staging_5", "archives_10", "mixed_active", "frames_terminal_tail",
			"duplicate_delete", "b_frame_fence", "b_decision_atomic", "b_claim_cancel",
			"b_stream_gap", "b_slot_consistency", "bg_0_runs_1");

	private static final List<String> FULL_EXTRA = List.of(
			"runs_50_terminal", "staging_500", "archives_2000",
			"frames_terminal_head", "frames_terminal_middle", "frames_terminal_missing",
			"frames_gap_large", "dual_executor", "lease_takeover", "active_unfinalizable",
			"bg_10k_runs_1");

	private static final List<String> STRESS_EXTRA = List.of("bg_100k_runs_1", "bg_existing_runs_1");

	static {
		add("empty", "不存在/空会话（幂等删除）", 0, 0, 0, 0, 0, false, false);
		add("minimal", "最小会话（会话记录 + 会话 run 索引）", 0, 0, 0, 0, 0, false, false);
		add("runs_1_terminal", "1 个终态 run（13 键 + 终帧 + xterminal）", 1, 0, 0, 0, 0, false, false);
		add("runs_10_terminal", "10 个终态 run", 10, 0, 0, 0, 0, false, false);
		add("runs_50_terminal", "50 个终态 run（默认 P95 ≤ 2s 门禁）", 50, 0, 0, 0, 0, false, false);
		add("mixed_active", "终态 + 已有终帧 RUNNING + 无终帧可收尾 + 挂起", 4, 0, 0, 0, 0, false, false);
		add("frames_terminal_tail", "终帧在尾部", 1, 0, 0, 32, 0, false, false);
		add("frames_terminal_head", "终帧在首部", 1, 0, 0, 32, 0, false, false);
		add("frames_terminal_middle", "终帧在中部", 1, 0, 0, 32, 0, false, false);
		add("frames_terminal_missing", "无终帧，收尾补写", 1, 0, 0, 32, 0, false, false);
		add("frames_gap_large", "大跳号 Stream：终帧在首 + tool_start 在 50 万号后", 1, 0, 0, 4, 0, false, false);
		add("staging_5", "5 个 staging", 0, 5, 0, 0, 0, false, false);
		add("staging_500", "500 个 staging", 0, 500, 0, 0, 0, false, false);
		add("archives_10", "10 个 legacy archive", 0, 0, 10, 0, 0, false, false);
		add("archives_2000", "2000 个 legacy archive（默认 P95 ≤ 3s 门禁）", 0, 0, 2_000, 0, 0, true, false);
		add("duplicate_delete", "同会话重复删除", 1, 0, 0, 0, 0, false, false);
		add("dual_executor", "两个执行体并发删除（单飞）", 1, 0, 0, 0, 0, false, false);
		add("lease_takeover", "purge 租约失效后接管，旧 owner 不得推进", 1, 0, 0, 0, 0, false, false);
		add("active_unfinalizable", "无终帧且不可收尾 ⇒ deferred，不得半删", 1, 0, 0, 0, 0, false, false);
		add("bg_0_runs_1", "无背景键 + 1 个终态 run（背景基线）", 1, 0, 0, 0, 0, false, false);
		add("bg_10k_runs_1", "1 万背景键 + 1 个终态 run（独立生成/测量/清理）", 1, 0, 0, 0, 10_000, true, false);
		add("bg_100k_runs_1", "10 万背景键 + 1 个终态 run（独立生成/测量/清理）", 1, 0, 0, 0, 100_000, true, false);
		add("bg_existing_runs_1", "已有大键空间（不重新生成）+ 1 个终态 run", 1, 0, 0, 0, 0, false, false);
		add("b_frame_fence", "B：fence+expected epoch 同槽 APPEND，旧 epoch 拒绝取号/归档/扇出", 1, 0, 0, 0, 0, false, true);
		add("b_decision_atomic", "B：decided+pending 同槽 Lua 原子写；旧实现两键崩溃对照", 1, 0, 0, 0, 0, false, true);
		add("b_claim_cancel", "B：claim/approve/cancel/前端回执条件写，已认领副作用不可被取消覆盖", 1, 0, 0, 0, 0, false, true);
		add("b_stream_gap", "B：大跳号 Stream 物理 ID 分页，不漏终帧/tool_start", 1, 0, 0, 4, 0, false, true);
		add("b_slot_consistency", "B：所有多键 Lua/键面同槽（standalone 与 cluster 业务断言相同）", 1, 0, 0, 0, 0, false, true);
	}

	private ScenarioCatalog() {
	}

	private static void add(String id, String description, int runs, int staging, int archives, int frames,
			int background, boolean large, boolean protocol) {
		ALL.put(id, new Scenario(id, description, runs, staging, archives, frames, background, large, protocol));
	}

	public static List<Scenario> all() {
		return List.copyOf(ALL.values());
	}

	public static Scenario byId(String id) {
		Scenario scenario = ALL.get(id);
		if (scenario == null) {
			throw new IllegalArgumentException("未知场景：" + id);
		}
		return scenario;
	}

	public static List<Scenario> selected(String profile, List<String> explicit) {
		Set<String> ids = new LinkedHashSet<>();
		if (explicit != null && !explicit.isEmpty()) {
			for (String id : explicit) {
				if (!ALL.containsKey(id)) {
					throw new IllegalArgumentException("未知场景：" + id);
				}
				ids.add(id);
			}
		}
		else {
			ids.addAll(SMOKE);
			if ("full".equals(profile) || "stress".equals(profile)) {
				ids.addAll(FULL_EXTRA);
			}
			if ("stress".equals(profile)) {
				ids.addAll(STRESS_EXTRA);
			}
		}
		List<Scenario> out = new ArrayList<>();
		for (String id : ids) {
			out.add(byId(id));
		}
		return List.copyOf(out);
	}

	public static List<String> gateScenarios(List<Scenario> selected) {
		Set<String> ids = new LinkedHashSet<>();
		for (Scenario scenario : selected) {
			ids.add(scenario.id());
		}
		List<String> gates = new ArrayList<>();
		for (String id : List.of("empty", "minimal", "runs_50_terminal", "archives_2000")) {
			if (ids.contains(id)) {
				gates.add(id);
			}
		}
		return List.copyOf(gates);
	}

	public static boolean expectedDeferred(String scenarioId) {
		return "active_unfinalizable".equals(scenarioId);
	}

	public static boolean requiresBackground100k(Scenario scenario) {
		return "bg_100k_runs_1".equals(scenario.id());
	}

	public static String backgroundBucket(Scenario scenario) {
		if (scenario.background() <= 0) {
			return null;
		}
		return "b" + scenario.background();
	}

}
