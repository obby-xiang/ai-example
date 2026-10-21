# 提交报告 · 修复 S6 默认语言环境依赖 合流（提交官 · DS-Flash · 2026-10-21）

## 0 自证行（DC-19 · wire 实测）

- 提交官 agentId = **`agent-1427`**；wire = `<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_ced79706-4f65-44a8-a80c-eb2eb2542928/agents/agent-1427/wire.jsonl`
- 取数方式：`ls -lat` 该 session 的 `agents/` 目录，最新新建目录 = `agent-1427`（23:55）；`grep` 其 wire 的 `profile.bind` 单行，原文首部（逐字）：

```json
{"type":"profile.bind","agentId":"agent-1427","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",...}
```

- 实测三字段：`modelAlias` = **`DeepSeek/deepseek-flash`**、`profileName` = **`coder`**、`thinkingEffort` = **`high`**
- **身份核对（与卡面口径的差异，照实记）**：该目录内**只有 `wire.jsonl`，没有 `turn.prompt` 文件**（`ls -la` 实证），故无法按「turn.prompt 首句」比对；改以 ①目录名与 wire `profile.bind.agentId` 严格相等（`agent-1427`）②wire 内 `profileName=coder` + 系统提示首段为「You are a subagent…提交官」两条互证，与本卡身份一致。
- 授权 = CI 纪律「**红了再修**」（run `39613094682` 等三笔 failure 同因）；施工官双 locale 门禁已绿（en-US 与 zh-CN 各 1419/1419 · 0F/0E）。

## 1 合流对象与 sha

| 项 | 值 |
| --- | --- |
| 主仓开基 | **`a9eae06`**（= `main` = `origin/main`，`git fetch` 后三者逐值相同） |
| 施工 worktree | `wt-fix-s6-locale`（主仓同级），分支 `fix/s6-default-locale`，基点 `a9eae06` |
| 分支提交 | **`abcf962`** `fix(i18n): S6 默认语言解耦 JVM locale + 数字格式化 Locale.ROOT + Kb缓存替身原子化`（9 文件，+20966 / −13） |
| 合并笔 | **`f6640d1`** `merge: 修复S6默认语言环境依赖（CI红修）`（`--no-ff`，Merge: `a9eae06` `abcf962`，2026-10-21 23:57） |
| 合流 diff | 9 文件，+20966 / −13（与分支清单逐值一致） |

**清单核对**：`git status --porcelain` 9 路径 / `git diff --cached --stat` 9 文件，与施工报告 §7 清单逐项对应（5 改 + 4 增），**无多出、无缺件**。

**冲突处置**：5 个源文件（`GlobalExceptionHandler` / `WebI18nConfig` / `ContextCompactor` / `GlobalExceptionHandlerTest` / `InMemoryKbCacheStore`）与 main 在基点后**零交集**，`git merge --no-ff` **零冲突**；语义冲突 **0**（合流后双 locale 全量门禁复跑即语义闸，见 §3）。

## 2 提交前脱敏处置（DC-08 · 必读）

分支提交首跑被仓库 `pre-commit`（`core.hooksPath=scripts/githooks`）**阻断 188 处**，命中全部为**门禁日志里的本机绝对路径与用户名**（`mvn` 输出，不含任何密钥）：

| 动作 | 范围 |
| --- | --- |
| 占位符替换 | worktree 绝对路径 → `<worktree>`；用户主目录 → `<home>`；个人用户名 → `<user>`（共 **186 处**：`gate-en` 92 / `gate-zh` 92 / `red` 2） |
| 保留 | 测试汇总行（`Tests run: 1419 …` / `Tests run: 11, Failures: 5`）与失败例名**逐字未动** |
| 复扫 | `scan_engine_blob` 逐件 0 命中；`git commit` 钩子输出「✓ 敏感信息扫描通过（暂存区无命中）」 |
| 留痕 | 施工报告 §7 末追加一条脱敏说明（本笔随分支提交入库） |

## 3 双 locale 门禁（合流后主仓复跑）

| 面 | 命令 | 结果 |
| --- | --- | --- |
| 后端 · 默认 env（本机 zh-CN） | `cd backend && mvn -o test` | **Tests run: 1419, Failures: 0, Errors: 0, Skipped: 0** · BUILD SUCCESS（`<TMP>/gate-main-zh.log`，仓外留档） |
| 后端 · **JVM en-US** | `cd backend && mvn -o test -DargLine="-Duser.language=en -Duser.country=US"` | **Tests run: 1419, Failures: 0, Errors: 0, Skipped: 0** · BUILD SUCCESS（`<TMP>/gate-main-en.log`） |
| 求和交叉核对 | `TEST-*.xml` 的 `tests/failures/errors/skipped` 逐文件求和 | **files=216 · tests=1419 · failures=0 · errors=0 · skipped=0**（与两跑汇总行逐值一致） |

> 口径提示（施工报告 §2 已实证）：`MAVEN_OPTS="-Duser.language=en"` **只作用于 Maven 自身 JVM**，surefire fork JVM 不继承，按它跑会得到假阴性；必须用 `-DargLine` 注入。本笔已把该跑法写成 en-US 全量门禁口径（看板 L-61）。

## 4 其它门禁

| 面 | 命令 | 结果 |
| --- | --- | --- |
| 前端单测 | `cd frontend && node node_modules/vitest/vitest.mjs run` | **822 passed (822) · 56 files**，exit 0（17.46s） |
| typecheck | `cd frontend && node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **exit 0**（0 错） |
| build | `cd frontend && TMP=/… TEMP=/… node node_modules/vite/bin/vite.js build` | **✓ built in 31.87s**，exit 0 |
| 文档一致性 | `node scripts/ci/check-docs.mjs` | **6/6 断言绿**（exit 0） |
| 脱敏四面 | ①合流 diff 加行（`git diff a9eae06 f6640d1` + `SCAN_DIFF_AWK` → `scan_engine_stream`）②4 新增件全文 ③5 变更件全文 ④暂存面（`pre-commit` 钩子） | **四 面 0 命中** |

## 5 根因与修复（两处同源 + 格式化一处 + 替身一处）

| # | 落点 | 问题 | 修法 | 性质 |
| --- | --- | --- | --- | --- |
| 1 | `common/GlobalExceptionHandler#respond` | `LocaleContextHolder.getLocale()` 在**无 LocaleContext** 时返回 `Locale.getDefault()` ⇒ 单测直调 handler 时落到 JVM 默认（CI en-US） | 新增 `requestLocale()`：显式取 `getLocaleContext()`，无上下文/`getLocale()==null` ⇒ 硬编码 `Locale.SIMPLIFIED_CHINESE` | 根因①（CI 5 红主因） |
| 2 | `common/WebI18nConfig` | Spring 原装 `AcceptHeaderLocaleResolver` 在 `Accept-Language` **缺失/畸形**时经 `request.getLocale()` 兜底 = JVM 默认 ⇒ 真实请求路径同源口子 | 新增内嵌 `ZhDefaultAcceptHeaderLocaleResolver`：头缺失/空白、`LanguageRange.parse` 抛异常、`Locale.lookup` 无匹配 ⇒ 一律 `zh_CN`；仅 `en-US`/`en` 落 `Locale.US` | 根因②（同源） |
| 3 | `ai/context/ContextCompactor` | 两处 `String.format("%.3f", ratio)` 的小数分隔符随默认 locale（`de_DE` 会出 `0,123`） | 固定 `Locale.ROOT` | 格式化面 |
| 4 | `test/.../ai/kb/InMemoryKbCacheStore#setIfAbsent` | **测试替身** `containsKey` 后再 `put` = check-then-act 非原子，并发两线程可同时获"刷新权" ⇒ `CachedKbSourceTest#twoConcurrentColdStartsDoOnlyOneRemotePull` 双 locale 各红 1 次（与 locale 无因果） | 改 `values.putIfAbsent(...)`（等价 Redis SETNX），**未削任何断言** | 替身保真（消门禁 flake） |

**语义边界（未变）**：有 `Accept-Language: en-US` 仍按头取英文 messages；zh 仍一律用调用方原文（`ErrorBodyFactory` 零改动）；支持列表仍 `zh_CN`/`en_US` 两枚。回归用例 +2（`jvmDefaultLocaleDoesNotLeakIntoErrorMessages` / `localeResolverFallsBackToChineseWhenHeaderUnusable`），计数 1417 → **1419**。

## 6 文件清单（合流笔 `f6640d1`）

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `backend/src/main/java/com/example/configmgr/common/GlobalExceptionHandler.java` | 改 | 新增 `requestLocale()`，`respond()` 改调（+18 / −3） |
| `backend/src/main/java/com/example/configmgr/common/WebI18nConfig.java` | 改 | 新增 `ZhDefaultAcceptHeaderLocaleResolver` |
| `backend/src/main/java/com/example/configmgr/ai/context/ContextCompactor.java` | 改 | 两处 `%.3f` 固定 `Locale.ROOT` |
| `backend/src/test/java/com/example/configmgr/common/GlobalExceptionHandlerTest.java` | 改 | +2 回归用例；`@AfterEach` 还原 `Locale.setDefault` 与 LocaleContext |
| `backend/src/test/java/com/example/configmgr/ai/kb/InMemoryKbCacheStore.java` | 改 | `setIfAbsent` 改 `putIfAbsent` |
| `docs/evidence/fix-s6-locale-red.log` | 增 | 修复前同型 5 红（`Tests run: 11, Failures: 5`） |
| `docs/evidence/fix-s6-locale-gate-en.log` | 增 | 分支侧全量 en-US 门禁日志（1419） |
| `docs/evidence/fix-s6-locale-gate-zh.log` | 增 | 分支侧全量默认 env 门禁日志（1419） |
| `docs/evidence/施工报告-修复S6默认语言环境依赖-DS-Flash.md` | 增 | 施工报告（含 §6 同类面清点、§7 脱敏说明） |

**本笔另动文档 2 件**（非产品代码）：`docs/任务看板.md`（挂账 L-61 + 速览同步）、本报告 `docs/evidence/提交报告-修复S6默认语言合流-DS-Flash.md`。

## 7 看板与台账落点

| 面 | 落点 |
| --- | --- |
| 挂账小节 | `docs/任务看板.md` 新增 **`L-61`**（L-60 之后、I-1 之前）：曾绑 JVM 默认 locale 已修 + 补 en-US 全量门禁口径；同类低危面 **17 处无 `Locale` 的大小写折叠**列施工报告 §6-5 **未修** |
| 挂账计数 | 速览行 `挂账小节 **121** 项` → **`122`** 项，并追加「10-21 补记第二十批 1 条」（含门禁数字与脱敏处置） |
| 批次计数 | **不变**（未新增批次行：本笔为 CI 红修，不立项） |
| 决策看板 | **未动**（本笔无新裁决，仅执行「红了再修」） |
| 服务台账 | `<SHARED>/services.state` 追加 1 行（合流 + 门禁结论，未起停任何服务） |

## 8 遗留（不在本笔）

1. 施工报告 §6-5 的 **17 处** `toLowerCase()/toUpperCase()` 无 `Locale`（土耳其语 `tr_TR` 面）—— 已入看板 L-61，后续批统一加 `Locale.ROOT`。
2. `InMemoryKbCacheStore#compareAndDelete` 仍是 check-then-act（本次 flake 不涉及它，未动）。
3. CI 侧建议显式固定 `-Duser.language`/`-Duser.country`，让「CI 与开发机同构」成为显式约定。
