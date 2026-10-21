package com.example.configmgr.ai.conformance;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 帧类型集合的<b>冻结守卫</b>（批③ 裁决：不新增帧型；本批补上"偷偷加帧"的自动红）。
 *
 * <h2>为什么需要它</h2>
 * 帧契约的"16 种"一直只活在文档与人的记忆里：
 * <ul>
 * <li>后端没有任何"帧类型全集"断言，新加一个 {@code frame("xxx")} 只会多一种帧，测试全绿；</li>
 * <li>前端 {@code types/sse.ts} 的 {@code SseFrameTypes} 是白名单，未知 type 被
 *     {@code parseSseFrame} <b>静默丢弃</b> —— 客户端也不会因为"后端多发了帧"而红。</li>
 * </ul>
 * 于是"两端一致"没有任何机械保证。本类用<b>源码扫描 + 双端比对</b>补上这条：
 * <ol>
 * <li>扫后端 {@code src/main/java} 里所有 {@code frame("...")} 字面量；</li>
 * <li>{@code message_start}/{@code message_end} 由 {@code frame("message_" + phase)} 动态拼出，
 *     故先断言"该拼接存在"，再把它展开成两型；</li>
 * <li>与前端白名单 {@code ../frontend/src/types/sse.ts} 逐元素比对；</li>
 * <li>两侧都必须<b>恰好</b>等于本类的冻结集。</li>
 * </ol>
 *
 * <h2>文件位置依赖属期望行为</h2>
 * {@code ../frontend/...} 是仓库相对路径（工作目录 = {@code backend}，本地与 CI 一致），
 * 与 {@code GenerativeFormContractTest} 同一手法：副本被移动/改名导致本用例红，正是
 * "跨端契约漂移可见"的设计意图。
 */
class FrameTypeSetFrozenTest {

	/**
	 * 冻结集（批③ 起）：15 → 16 的唯一一次扩容是 {@code context}（上下文统计专项批①）。
	 *
	 * <p>顺序无关（比对用集合）：这里按"生命周期"排列，便于人读。
	 */
	private static final Set<String> FROZEN = new TreeSet<>(List.of("start", "context", "message_start", "delta",
			"message_end", "tool_start", "tool_result", "confirm_request", "confirm_decision",
			"frontend_tool_request", "frontend_tool_result", "suspended", "heartbeat", "retry", "done", "error"));

	@Test
	void frameTypeSetIsExactlyTheFrozenSixteen() throws IOException {
		Set<String> backend = backendFrameTypes();
		Set<String> frontend = frontendFrameTypes();

		assertThat(FROZEN).as("冻结集本身是 16 种（这条防'空集对空集'式恒真）").hasSize(16);
		assertThat(backend).as("后端发射点集合漂移了（新增/改名帧型）").isEqualTo(FROZEN);
		assertThat(frontend).as("前端白名单漂移了（未同步的帧会被静默丢弃）").isEqualTo(FROZEN);
	}

	@Test
	void falsificationTwinProvesTheGuardCanGoRed() {
		// 反例守卫：多一种帧必须被判不等 —— 否则上面的"等于"只是同义反复
		Set<String> drifted = new TreeSet<>(FROZEN);
		drifted.add("reasoning");

		assertThat(drifted).isNotEqualTo(FROZEN);
		assertThat(FROZEN).doesNotContain("reasoning");
	}

	// ── 扫描 ────────────────────────────────────────────────────────────────

	/** {@code frame("start")} 之类的字面量发射点（扫描 {@code ai/} 下的实现代码）。 */
	private static Set<String> backendFrameTypes() throws IOException {
		Path root = Path.of("src", "main", "java", "com", "example", "configmgr", "ai");
		Pattern literal = Pattern.compile("frame\\(\\s*\"([a-z_]+)\"\\s*\\)");
		Set<String> types = new TreeSet<>();
		boolean dynamicMessagePhases = false;
		try (Stream<Path> files = Files.walk(root)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
				String source = Files.readString(file, StandardCharsets.UTF_8);
				Matcher matcher = literal.matcher(source);
				while (matcher.find()) {
					types.add(matcher.group(1));
				}
				if (source.contains("frame(\"message_\" + phase)")) {
					dynamicMessagePhases = true;
				}
			}
		}
		assertThat(dynamicMessagePhases).as("message_start/message_end 由 phase 拼出：拼接点消失即说明帧型变了")
			.isTrue();
		types.add("message_start");
		types.add("message_end");
		return types;
	}

	/** 前端 {@code SseFrameTypes} 数组里的字符串字面量。 */
	private static Set<String> frontendFrameTypes() throws IOException {
		Path file = Path.of("..", "frontend", "src", "types", "sse.ts");
		String source = Files.readString(file, StandardCharsets.UTF_8);
		int start = source.indexOf("export const SseFrameTypes = [");
		assertThat(start).as("找不到前端白名单 SseFrameTypes").isGreaterThanOrEqualTo(0);
		int end = source.indexOf("] as const", start);
		assertThat(end).as("白名单没有 ] as const 收尾").isGreaterThan(start);
		Matcher matcher = Pattern.compile("'([a-z_]+)'").matcher(source.substring(start, end));
		Set<String> types = new LinkedHashSet<>();
		while (matcher.find()) {
			types.add(matcher.group(1));
		}
		return new TreeSet<>(types);
	}

}
