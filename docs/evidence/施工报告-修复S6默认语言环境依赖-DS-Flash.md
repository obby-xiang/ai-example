> 施工报告（worktree 内产出，**未 commit**）。施工面：`fix/s6-default-locale`，worktree `wt-fix-s6-locale`。
> 纪律：禁 commit/push；未改 `docs/任务看板.md` / `docs/决策看板.md` / `.gitignore`；未起任何服务；断言只升不降。

# 施工报告 · 修复 S6 默认语言环境依赖（DS-Flash，2026-10-21）

## 0 自证与对象（DC-19）

| 项 | 值 |
| --- | --- |
| 执行者模型 | **DeepSeek/deepseek-flash**（施工官，实施角色） |
| 授权来源 | CI 纪律「**红了再修**」：run **39613094682** 等三笔 failure 同因（`GlobalExceptionHandlerTest` 5 红，`Tests run: 1279, Failures: 5`） |
| wire 自证行 | `agents/agent-1426/wire.jsonl`：`agentId=agent-1426` · `modelAlias=DeepSeek/deepseek-flash` · `profileName=coder` · `thinkingEffort=high` |
| 施工基点 | `origin/main` = **`a9eae06`**（后端本机 1417 例绿） |
| 工作树 | `wt-fix-s6-locale`（主仓同级），分支 `fix/s6-default-locale`，基点 `a9eae06` |
| 设计口径 | `docs/evidence/施工报告-i18n工具可配置-DS-Flash.md` §7 S6：「`AcceptHeaderLocaleResolver` 默认 `zh_CN`」「无该头 = 默认 zh，与迁移前逐字一致」 |

## 1 缺陷与根因链

**现象**：CI（Ubuntu，JVM 默认 en-US）`GlobalExceptionHandlerTest` 5 红——期望中文（`字段编码不能为空` / `任务 #7 已终态` / `状态不允许` / `目标步骤不在该任务类型的步骤序列内：PUBLISH`），实际英文（`Invalid argument` / `The task is finalized and can no longer change` / `Operation not allowed in the current state` / `Target step is not in this task type's sequence`）；本机（zh-CN）全绿。

**根因链**（两处同源，都让"默认 zh"退化成"JVM 默认 locale"）：

1. `common/GlobalExceptionHandler#respond` 取 `LocaleContextHolder.getLocale()`。该静态方法在**没有 LocaleContext** 时返回 `Locale.getDefault()`——单测直接调 handler（不经 DispatcherServlet），上下文为空 ⇒ 落到 JVM 默认 locale。CI = en-US ⇒ `ErrorBodyFactory` 走"非中文查 messages_en"分支 ⇒ 5 红；本机 zh-CN ⇒ 走"中文用调用方原文"分支 ⇒ 绿。
2. Spring 原装 `AcceptHeaderLocaleResolver#resolveLocale` 在`Accept-Language` 缺失或**畸形**时会经 `request.getLocale()` 兜底，而 `getLocale()` 的兜底同样是 JVM 默认 locale；本机默认 zh 恰好被 supported 列表接住，CI 的 en-US 也会被接住 ⇒ 同一口子在真实请求路径上同样会漂。

即：设计口径"默认 zh"只是**写了一行 `setDefaultLocale`**，并未真正与 JVM 默认 locale 解耦。

## 2 红测先行（留痕）

**方法学修正（重要）**：`MAVEN_OPTS="-Duser.language=en -Duser.country=US"` 只作用于 **Maven 自身 JVM**，surefire 的 fork JVM 不继承。按原样跑得到 `Tests run: 11, Failures: 0`（假阴性，已实测）。必须用 `-DargLine` 注入 fork JVM 才能复现 CI：

```
mvn -o test -Dtest=GlobalExceptionHandlerTest -DargLine="-Duser.language=en -Duser.country=US"
```

| 项 | 结果 |
| --- | --- |
| 命令（红证） | 上句；日志 `docs/evidence/fix-s6-locale-red.log` |
| 结果 | **Tests run: 11, Failures: 5, Errors: 0, Skipped: 0**，BUILD FAILURE |
| 5 例 | `plainIllegalArgumentStillCarriesStableCode` / `plainIllegalStateCarriesStableCode` / `codedExceptionKeepsItsOwnCodeAndType` / `problemJsonNegotiationOnlyWhenAccepted` / `defaultShapeStaysEnvelope` |
| 与 CI 同型 | ✅ 例名、期望值、英文实得值逐条一致（`expected: <字段编码不能为空> but was: <Invalid argument>` 等） |
| 新回归用例先红 | ✅ 修复前单独跑 `-Dtest='GlobalExceptionHandlerTest#jvmDefaultLocaleDoesNotLeakIntoErrorMessages'`（同 `-DargLine` 参数）：`Tests run: 1, Failures: 1`，`expected: <字段编码不能为空> but was: <Invalid argument>`。该次为终端留痕；入库红证为上表 5 例（同一根因） |

> 该批日志为**修复前**所留：先证"同型 5 红"，再动主代码。

## 3 修法

| # | 落点 | 改动 |
| --- | --- | --- |
| 1 | `common/GlobalExceptionHandler#requestLocale()`（新增私有方法） | 显式读 `LocaleContextHolder.getLocaleContext()`；**无上下文或上下文 locale 为 null ⇒ 硬编码 `Locale.SIMPLIFIED_CHINESE`**，不再经 `Locale.getDefault()`。`respond()` 改为调它 |
| 2 | `common/WebI18nConfig.ZhDefaultAcceptHeaderLocaleResolver`（新增内嵌类，替代裸 `AcceptHeaderLocaleResolver`） | 接管 `resolveLocale`：头缺失/空白 ⇒ zh-CN；`Locale.LanguageRange.parse` 抛 `IllegalArgumentException`（畸形头）⇒ zh-CN；`Locale.lookup` 无匹配（如 `de-DE`、`zh-TW`）⇒ zh-CN。仅 `en-US`（及 `en`）落到 `Locale.US` |

**语义边界（未变）**：有 `Accept-Language: en-US` 仍按头取英文 messages；zh 仍一律用调用方原文（`ErrorBodyFactory` 零改动，"zh 逐字不变"的 E2E 断言面不受影响）；支持列表仍两枚 `zh_CN` / `en_US`（区域变体走 messages 回落链）。

## 4 回归用例（防再犯）

新增 2 例，均在 `backend/src/test/java/com/example/configmgr/common/GlobalExceptionHandlerTest.java`：

| 用例 | 断言 | 修复前 | 修复后 |
| --- | --- | --- | --- |
| `jvmDefaultLocaleDoesNotLeakIntoErrorMessages` | `Locale.setDefault(Locale.ENGLISH)` + 无 `Accept-Language` 头（先断言头确实为 null）+ 无 LocaleContext，错误响应文案为中文（`字段编码不能为空`、`任务 #7 已终态`） | **红**（`Invalid argument`） | 绿 |
| `localeResolverFallsBackToChineseWhenHeaderUnusable` | 畸形头 `en_US;;q=x`、空白头 ` `、不支持语言 `de-DE` ⇒ resolver 一律返回 `zh_CN` | 旧实现下 JVM=en 时会取 JVM 默认（同源口子） | 绿 |

`@AfterEach` 同时 `LocaleContextHolder.resetLocaleContext()` 与 `Locale.setDefault(originalDefaultLocale)`，用例间无残留。

## 5 双 locale 门禁（完工实测）

| 面 | 命令 | 结果 |
| --- | --- | --- |
| 红证（修复前） | `mvn -o test -Dtest=GlobalExceptionHandlerTest -DargLine="-Duser.language=en -Duser.country=US"` | **11 例 / 5 F**（同 CI），BUILD FAILURE |
| 单类 en-US（修复后） | `mvn -o test -Dtest=GlobalExceptionHandlerTest -DargLine="-Duser.language=en -Duser.country=US"` | **13 例 / 0 F / 0 E**，BUILD SUCCESS |
| **全量 · JVM en-US（核心验收）** | `mvn -o test -DargLine="-Duser.language=en -Duser.country=US"` | **1419 例 / 0 F / 0 E / 0 S**，BUILD SUCCESS（日志 `fix-s6-locale-gate-en.log`） |
| **全量 · 默认 env（本机 zh-CN）** | `mvn -o test` | **1419 例 / 0 F / 0 E / 0 S**，BUILD SUCCESS（日志 `fix-s6-locale-gate-zh.log`） |

> 计数口径：基线 1417（`a9eae06`）+ 本批新增 2 例 = **1419**；断言只升不降。

**并行发现的既有 flake（已修，非 locale 面）**：全量两次首跑各出现 1 红 `ai.kb.CachedKbSourceTest#twoConcurrentColdStartsDoOnlyOneRemotePull`（`fake.manifestCalls` expected 1 but was 2）——**en-US 与默认 env 各红一次**，故与 locale 无因果。根因：测试替身 `InMemoryKbCacheStore#setIfAbsent` 是 **check-then-act 非原子**（`containsKey` 后再 `put`；底层虽是 `ConcurrentHashMap`，两步之间仍可交错），并发两线程可同时拿到"刷新权"；锁 TTL 30s 且替身时钟只在用例中手动推进，可排除"过期"路径。处置：改为 `values.putIfAbsent(...)` 原子判存+写入（等价 Redis SETNX 语义，属**测试替身保真修复**，未削任何断言）；修后该类连跑 **10/10 绿**，双 locale 全量复跑见上表。

## 6 同类 JVM-locale 依赖点清点

| # | 点 | 是否 JVM-locale 相关 | 处置 |
| --- | --- | --- | --- |
| 1 | `GlobalExceptionHandler#respond` → `LocaleContextHolder.getLocale()` | 是（本次 5 红主因） | **已修**（§3-1） |
| 2 | `AcceptHeaderLocaleResolver#resolveLocale`（头缺失/畸形经 `request.getLocale()`） | 是（真实请求路径同源口子） | **已修**（§3-2） |
| 3 | `ai/context/ContextCompactor` 两处 `String.format("%.3f", ratio)`（log/台账） | 是（小数分隔符随默认 locale，`de_DE` 会出 `0,123`） | **已修**：`String.format(Locale.ROOT, "%.3f", …)` |
| 4 | `spring.messages.fallback-to-system-locale` | 是（漏配会让缺键回落 JVM 默认） | 核对：`application.yml` 已是 `false`，无需改 |
| 5 | `toLowerCase()` / `toUpperCase()` 无 `Locale`（17 处：`KbText`×3、`KbTools`、`SheetWriteGuard`、`UiPrimitiveGuard`、`AiTools`×2、`ConditionEvaluator`×2、`FileController`×4、`TaskController`×2） | 是（土耳其语 `tr_TR` 的 i/İ 折叠会改变匹配结果） | **列清单未改**：操作对象是 ASCII 字面量/枚举/扩展名，en/zh 下逐字节同形；改动面横跨 8 个业务文件，与本批 CI 缺陷无因果。建议后续批统一加 `Locale.ROOT` |
| 6 | `String.format` 的 `%d` / `%02x`（`AiTools`、`seed/*`、`ToolPreflightService` 等） | 弱（数字分组/本地数字形；`en`/`zh` 同形） | **列清单未改**：无 `,` 分组标志、无小数，en/zh 同形 |
| 7 | Bean Validation `getDefaultMessage()`（`handleValidation`） | 弱（注解缺省文案随 Hibernate 默认 locale） | **列清单未改**：现有字段校验走注解内中文文案，非 S6 面 |

## 7 文件清单（全部改动，均未 commit）

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `backend/src/main/java/com/example/configmgr/common/GlobalExceptionHandler.java` | 改 | 新增 `requestLocale()`，`respond()` 改调；locale 不再经 JVM 默认 |
| `backend/src/main/java/com/example/configmgr/common/WebI18nConfig.java` | 改 | 新增 `ZhDefaultAcceptHeaderLocaleResolver`：头缺失/畸形/无匹配 ⇒ zh-CN |
| `backend/src/main/java/com/example/configmgr/ai/context/ContextCompactor.java` | 改 | 两处 `%.3f` 固定 `Locale.ROOT` |
| `backend/src/test/java/com/example/configmgr/common/GlobalExceptionHandlerTest.java` | 改 | +2 回归用例；`@AfterEach` 恢复 `Locale.setDefault` |
| `backend/src/test/java/com/example/configmgr/ai/kb/InMemoryKbCacheStore.java` | 改 | `setIfAbsent` 改 `putIfAbsent` 原子判定（消门禁 flake，测试替身保真，非 locale 面） |
| `docs/evidence/fix-s6-locale-red.log` | 增 | 修复前同型 5 红（+新用例红） |
| `docs/evidence/fix-s6-locale-gate-en.log` | 增 | 全量 en-US 门禁日志 |
| `docs/evidence/fix-s6-locale-gate-zh.log` | 增 | 全量默认 env 门禁日志 |
| `docs/evidence/施工报告-修复S6默认语言环境依赖-DS-Flash.md` | 增 | 本报告 |

> 入库前脱敏（DC-08，提交官在 commit 时执行）：三份日志中的 worktree 绝对路径替换为 `<worktree>`、`<home>` 等尖括号占位符（en 92 处 / zh 92 处 / red 2 处，共 186 处），测试汇总行与失败例名逐字保留；`scripts/githooks/scan-lib.sh` 复扫 0 命中。原始日志留在提交前本地（未入库）。

## 8 结论与后续

- CI 5 红根因已消除：错误文案的 locale 决策链上不再出现 `Locale.getDefault()`；无 `Accept-Language` 头 = 中文（设计口径逐字不变），`en-US` 头 = 英文（原行为不变）。
- 后续（不在本批）：
  1. §6-5 的 17 处大小写折叠统一 `Locale.ROOT`（土耳其语面）。
  2. CI 侧可考虑显式固定 `-Duser.language`/`-Duser.country` 以让"CI 与开发机同构"成为显式约定，而非依赖 runner 镜像默认值。
  3. `InMemoryKbCacheStore#compareAndDelete` 仍是 check-then-act（本次 flake 不涉及它，未动）；如需同类保真，可改 `values.remove(key, value)`。
