package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移工具（`scripts/redis-run-migration.mjs`）的键族契约：<b>10 族前缀 / 四类型 / 同槽</b>。
 *
 * <h2>为什么要这一例</h2>
 * 迁移脚本自带 52 例（{@code node --test scripts/redis-run-migration.test.mjs}），但它们只和
 * <b>脚本内硬编码的 10 族</b>对账 —— 产品侧改了键名（{@link RunStore} 的前缀常量）或换了存储类型，
 * 脚本的用例仍会全绿，而真到离线迁移时才发现"扫不到 / 类型对不上"。
 * 本用例把迁移清单锚到<b>产品事实源</b>上：
 * <ul>
 * <li>前缀 = 直接调用 {@link RunStore} 的公开键方法（{@code runKey}/{@code pendingKey}/…）
 * 与 {@link SessionKeyRegistry} 的 run 级登记，不在用例里抄常量；</li>
 * <li>类型 = 每种键都真的走一次产品写路径（{@code save}/{@code putPending}/{@code appendLedger}/…，
 * xevents 走 {@link RedisFrameStore#append}），看它落进 STRING / HASH / LIST / STREAM 哪一面；</li>
 * <li>漏族 = 脚本清单必须恰好覆盖<b>已登记在 {@link SessionKeyRegistry} 的 run 级键族</b>——
 * 这些族若既不迁移也未显式排除，本用例红。未登记进该表的键族不在本用例覆盖范围
 * （不靠反射扩大断言面；新增键族应先登记，再由本用例强制归类）。</li>
 * </ul>
 *
 * <h2>文件位置依赖属期望行为</h2>
 * 本用例以仓库相对路径读 {@code ../scripts/redis-run-migration.mjs}（工作目录 = {@code backend}，
 * 本地与 CI 一致；与 {@code GenerativeFormContractTest} 读前端/桩副本同口径）。
 * 只解析两段数据字面量（{@code MIGRATION_FAMILIES} / {@code EXCLUDED_KEY_PREFIXES}）的
 * {@code name/prefix/type} 与引号项，不解析脚本逻辑。
 */
class RunMigrationFamilyContractTest {

	private static final String RUN_ID = "run-migration-contract";

	private static final String CALL_ID = "call-contract-1";

	private static final Path SCRIPT = Path.of("..", "scripts", "redis-run-migration.mjs");

	/** 三字段的家族条目（脚本里的对象字面量，字段顺序与引号风格不参与契约）。 */
	private record Family(String name, String prefix, String type) {
	}

	/** 单字段提取：键名可带/不带引号，值允许单/双引号，任意空白；不依赖字段顺序。 */
	private static final String FIELD_TEMPLATE = "['\"]?%s['\"]?\\s*:\\s*(?:'([^']*)'|\"([^\"]*)\")";

	private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final FakeFrameRedis frames = new FakeFrameRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	private final RedisFrameStore frameStore = new RedisFrameStore(this.frames.redis, this.mapper, this.properties);

	@Test
	void migrationFamiliesMatchProductKeysAndTypes() throws IOException {
		List<Family> families = readFamilies();

		// 反例守卫：清单被清空/只读出一半时不得"空集对空集"恒真。
		assertThat(families).as("预期 10 族；合法扩族需同步更新产品映射/契约，脚本被改窄或漏族在这里先红")
			.hasSize(10);
		assertThat(families).extracting(Family::name).doesNotHaveDuplicates();
		assertThat(families).extracting(Family::prefix).doesNotHaveDuplicates();

		Map<String, String> productKeys = productKeysByFamily();
		assertThat(productKeys.keySet()).as("族名 = 产品键方法集合（Registry 9 族 + claim）")
			.containsExactlyInAnyOrderElementsOf(families.stream().map(Family::name).toList());

		writeEveryFamilyOnce();

		for (Family family : families) {
			String key = productKeys.get(family.name);
			assertThat(key).as("产品侧没有对应键方法：%s", family.name).isNotNull();
			assertThat(key).as("前缀漂移（迁移脚本 vs 产品键方法）：%s", family.name)
				.isEqualTo(family.prefix + RunStore.runTag(RUN_ID));
			assertThat(FakeLeaseRedis.hashTagOf(key)).as("run 级键必须带 {run:<runId>} tag：%s", family.name)
				.isEqualTo("run:" + RUN_ID);
			assertThat(observedType(key)).as("存储类型漂移（脚本声明 %s）：%s", family.type, family.name)
				.isEqualTo(family.type);
		}
	}

	@Test
	void everyRunScopedProductFamilyIsEitherMigratedOrExplicitlyExcluded() throws IOException {
		Set<String> migrated = new LinkedHashSet<>();
		for (Family family : readFamilies()) {
			migrated.add(family.prefix);
		}
		Set<String> excluded = readExcludedPrefixes();

		// 新增 run 级键族（SessionKeyRegistry 的 run 登记）必须显式归类：迁移或排除，二选一。
		List<String> uncovered = new ArrayList<>();
		for (SessionKeyRegistry.Entry entry : SessionKeyRegistry.entries()) {
			if (entry.scope() != SessionKeyRegistry.Scope.RUN) {
				continue;
			}
			if (!migrated.contains(entry.prefix()) && !excluded.contains(entry.prefix())) {
				uncovered.add(entry.prefix());
			}
		}
		assertThat(uncovered).as("run 级键族既不在迁移清单也不在排除清单（新增键族漏归类）").isEmpty();

		// claim 不在 SessionKeyRegistry 的 14 条 run 级登记里（它是 deleteRun 13 键中的认领位），
		// 但仍必须迁移 —— 漏了它，迁移后旧认领位会留在无 tag 的键上。
		assertThat(migrated).contains(RunStore.CLAIM_PREFIX);

		// 不搬的 run 级键族必须在排除清单里明说不搬（含 Registry 不登记的租约键）。
		assertThat(excluded).contains(RunStore.FENCE_RUN_PREFIX, RunStore.LEASE_RUN_PREFIX,
				LeaseService.EPOCH_PREFIX, RunStore.BEAT_PREFIX, RunStore.ACTIVITY_PREFIX,
				CancellationRegistry.CANCEL_PREFIX);
		assertThat(excluded).as("同一前缀不得既迁移又排除").doesNotContainAnyElementsOf(migrated);
	}

	// ── 产品事实源：键名 ────────────────────────────────────────────────────

	/** 迁移 10 族 → 产品公开键方法（不抄常量：前缀由方法返回值给出）。 */
	private Map<String, String> productKeysByFamily() {
		Map<String, String> keys = new LinkedHashMap<>();
		keys.put("run", this.store.runKey(RUN_ID));
		keys.put("pending", this.store.pendingKey(RUN_ID));
		keys.put("claim", this.store.claimKey(RUN_ID));
		keys.put("ledger", this.store.ledgerKey(RUN_ID));
		keys.put("idem", this.store.idemKey(RUN_ID));
		keys.put("events", this.store.eventsKey(RUN_ID));
		keys.put("seq", this.store.seqKey(RUN_ID));
		keys.put("xevents", this.store.xeventsKey(RUN_ID));
		keys.put("req", this.store.reqKey(RUN_ID));
		keys.put("decided", this.store.decidedKey(RUN_ID));
		return keys;
	}

	// ── 产品事实源：类型（每种键真的写一次，看落在哪一面） ─────────────────

	/**
	 * 每种键走一次<b>产品写路径</b>：类型不是"测出来的常量"，而是产品实际写入的存储面。
	 * 产品把某一族换类型（例如 events 从 LIST 换 STREAM），这里就会落到另一面而让断言红。
	 */
	private void writeEveryFamilyOnce() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId("session-contract");
		snapshot.setStatus(RunSnapshot.RUNNING);
		this.store.save(snapshot);

		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_BACKEND);
		this.store.putPending(RUN_ID, pending);

		this.store.markRequestEmitted(RUN_ID, CALL_ID, 1L);
		this.store.claimExecution(RUN_ID, CALL_ID, "instance-contract");
		this.store.tryRecordDecision(RUN_ID, CALL_ID, PendingToolCall.APPROVED);

		PendingToolCall executed = new PendingToolCall();
		executed.setToolCallId(CALL_ID);
		executed.setName("start_import");
		executed.setKind(PendingToolCall.KIND_BACKEND);
		executed.setStatus(PendingToolCall.EXECUTED);
		executed.setOutcome(ToolOutcome.OK.wire());
		executed.setExecuted(true);
		executed.setExecutedBy("instance-contract");
		this.store.appendLedger(RUN_ID, executed, "ok");

		this.store.appendEvent(RUN_ID, new LinkedHashMap<>(Map.of("type", "delta", "seq", 1)));
		this.store.recordIssuedSeq(RUN_ID, 1L);

		this.frameStore.append(RUN_ID,
				new LinkedHashMap<>(Map.of("type", "delta", "seq", 1, "text", "contract")));
	}

	/** 产品写完后该键所在的存储面（null = 没有任何一条产品写路径碰过它）。 */
	private String observedType(String key) {
		if (this.frames.streams.containsKey(key)) {
			return "stream";
		}
		if (this.fake.strings.containsKey(key)) {
			return "string";
		}
		if (!this.fake.hashTables.getOrDefault(key, Map.of()).isEmpty()) {
			return "hash";
		}
		if (!this.fake.lists.getOrDefault(key, List.of()).isEmpty()) {
			return "list";
		}
		return null;
	}

	// ── 脚本侧：只读两段数据字面量 ─────────────────────────────────────────

	private List<Family> readFamilies() throws IOException {
		String source = readScript();
		String block = declarationBlock(source, "export const MIGRATION_FAMILIES");

		List<Family> families = new ArrayList<>();
		// 按对象切分（每个族 = 一个 `{...}` 字面量，无嵌套），再逐字段取值：
		// 字段顺序/引号风格都不参与契约；缺字段或解析不足则下面断言红（保守方向）。
		for (String chunk : block.split("\\{")) {
			if (!chunk.contains("prefix")) {
				continue;
			}
			String name = field(chunk, "name");
			String prefix = field(chunk, "prefix");
			String type = field(chunk, "type");
			assertThat(name).as("族条目的 name 字段缺失/无法解析：%s", chunk.strip()).isNotNull();
			assertThat(prefix).as("族条目的 prefix 字段缺失/无法解析：%s", chunk.strip()).isNotNull();
			assertThat(type).as("族条目的 type 字段缺失/无法解析：%s", chunk.strip()).isNotNull();
			families.add(new Family(name, prefix, type));
		}
		return families;
	}

	/** 单字段取值（单/双引号均可，字段在对象内的位置不限）。 */
	private static String field(String chunk, String key) {
		Matcher matcher = Pattern.compile(FIELD_TEMPLATE.formatted(key)).matcher(chunk);
		if (!matcher.find()) {
			return null;
		}
		return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
	}

	private Set<String> readExcludedPrefixes() throws IOException {
		String block = declarationBlock(readScript(), "export const EXCLUDED_KEY_PREFIXES");
		Set<String> excluded = new LinkedHashSet<>();
		Matcher matcher = QUOTED.matcher(block);
		while (matcher.find()) {
			excluded.add(matcher.group(1));
		}
		return excluded;
	}

	private static String declarationBlock(String source, String marker) {
		int start = source.indexOf(marker);
		assertThat(start).as("脚本里找不到声明：%s（迁移脚本被改名/删声明）", marker).isGreaterThanOrEqualTo(0);
		int end = source.indexOf("]);", start);
		assertThat(end).as("声明没有闭合的 `]);`：%s", marker).isGreaterThan(start);
		return source.substring(start, end);
	}

	private static String readScript() throws IOException {
		assertThat(Files.isRegularFile(SCRIPT)).as("迁移脚本不在期望位置：%s（工作目录 = backend）", SCRIPT)
			.isTrue();
		return Files.readString(SCRIPT, StandardCharsets.UTF_8);
	}

}
