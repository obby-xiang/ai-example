# V1 核验证据：glm-5.3 分支测试套件声明

## 一、核验目标

被核验声明（glm-5.3 分支文档自述）：

> "6 个测试类 38 个 JUnit 用例全部通过"

分支文档原文（`docs/05-测试验证结果.md:15`）：

```
[INFO] Tests run: 38, Failures: 0, Errors: 0, Skipped: 0
```

分支文档 `docs/04-测试用例.md:48`：

```
**执行命令**：`mvn -s maven-settings.xml test` → **Tests run: 38, Failures: 0, Errors: 0, BUILD SUCCESS**
```

核验内容：(1) 测试类数量与用例数量是否与自述一致；(2) 亲自执行完整测试套件，
记录真实结果。

## 二、执行环境与命令

| 项 | 值 |
|---|---|
| 被核验分支目录 | `/e/temp/ai-example-code/ai-example-glm-5.3` |
| 分支 | `glm-5.3`（`git rev-parse --abbrev-ref HEAD` 实测输出） |
| 工作树状态 | 核验前后 `git status --porcelain` 均无输出（无改动） |
| JDK | `java version "21.0.12" 2026-07-21 LTS` |
| Maven | `Apache Maven 3.9.16`（全路径调用） |
| 测试库 | 测试 profile `application-test.yml` → `jdbc:h2:mem:testdb`（内存库，与生产文件库无关） |

实际执行命令：

```bash
cd /e/temp/ai-example-code/ai-example-glm-5.3/backend
"D:/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test
```

完整原始日志：`/e/temp/glm-v1-test.log`（同目录附件 `V1-附件-mvn-test-raw.log`）。
未加 `-Dtest=`、未跳过任何测试类，未修改任何源码/测试/配置。

## 三、实际观察结果

### 3.1 测试类静态清单（`find backend/src/test -name "*Test.java"` 实测）

仓库共有 6 个 `*Test.java` 文件，逐文件 `@Test` 方法数（实测）：

| # | 文件 | 文件内 `@Test` 方法数 | 说明 |
|---|---|---|---|
| 1 | `BaseIntegrationTest.java` | **0** | `public abstract class`，仅含 `@SpringBootTest`/`@ActiveProfiles("test")`/`@TestMethodOrder`，无任何测试方法 |
| 2 | `ai/AiToolsTest.java` | 8 | |
| 3 | `service/CatalogServiceTest.java` | 5 | |
| 4 | `service/ConfigDataServiceTest.java` | 11 | |
| 5 | `service/ExportFlowTest.java` | 4 | |
| 6 | `service/ImportFlowTest.java` | 10 | |
| | **合计** | **38** | 分布在 5 个可执行测试类中 |

说明：`grep -c "@Test"` 的直接统计为 41，其中 3 处是 `@TestMethodOrder` 被前缀匹配
（`BaseIntegrationTest.java:13`、`ExportFlowTest.java:23`、`ImportFlowTest.java:25`）。
逐行核对 `@Test` 独占行后，实际测试方法数为 **38**。全仓无 `@ParameterizedTest`、
`@RepeatedTest`、`@TestFactory`、`@TestTemplate`、`@Disabled`（grep 实测无输出）。

### 3.2 完整测试执行输出（原文摘录）

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.274 s -- in com.example.quickstart.ai.AiToolsTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.528 s -- in com.example.quickstart.service.CatalogServiceTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.094 s -- in com.example.quickstart.service.ConfigDataServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.080 s -- in com.example.quickstart.service.ExportFlowTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.343 s -- in com.example.quickstart.service.ImportFlowTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 38, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  19.973 s
[INFO] Finished at: 2026-10-06T18:44:57+08:00
```

进程退出码：`TEST_EXIT=0`。

### 3.3 汇总数字

| 指标 | 实测值 |
|---|---|
| Tests run（用例总数） | **38** |
| Failures | **0** |
| Errors | **0** |
| Skipped | **0** |
| 实际执行的测试类数 | **5**（surefire 报告行数） |
| 构建结果 | **BUILD SUCCESS** |
| 总耗时 | 19.973 s |

失败堆栈：**无**（Failures=0、Errors=0，日志中不存在失败用例的 stack trace）。

### 3.4 用例清单（38 个，实测方法名）

`AiToolsTest`（8）：`listConfigItemsReturnsCatalog`、`noTaskToolsReject`、`createTaskBindsSession`、
`selectionMovesToNextStep`、`exportToolsProgressiveDisclosure`、`importToolsFlowWithGuards`、
`stepGuardBlocksPublishBeforeImport`、`wrongConfigCodeRejected`

`CatalogServiceTest`（5）：`seedProvidesEightConfigsAcrossLevels`、`taskCreatePersistsImmediately`、
`taskCreateRejectsUnknownType`、`stepGuardsRejectWrongStep`、`listTasksReturnsCreated`

`ConfigDataServiceTest`（11）：`noConditionReturnsAll`、`textContains`、`numericBetween`、`numericGt`、
`enumEq`、`boolEq`、`scopeEqOnRegionLevel`、`multipleConditionsAnd`、`invalidOperatorRejected`、
`invalidFieldRejected`、`textIn`

`ExportFlowTest`（4）：`createAndSelect`、`runExportWithConditions`、`terminalTaskRejectsFurtherOps`、
`exportTwiceRejectedWhileRunning`

`ImportFlowTest`（10）：`createAndPrepare`、`checkDetectsAllRuleTypes`、`importRejectedWhileCheckHasError`、
`fixDataThenCheckPass`、`importStagesDataWithoutTouchingPublished`、`publishReplacesPublishedData`、
`dependentsImportedAfterDependency`、`duplicateKeyDetected`、`emptyUploadProducesWarning`、
`missingRequiredFieldDetected`

## 四、与声明是否一致

| 声明项 | 实测 | 判定 |
|---|---|---|
| 38 个 JUnit 用例 | 38 | **符合** |
| 全部通过 | Failures 0 / Errors 0 / Skipped 0 / BUILD SUCCESS | **符合** |
| "6 个测试类" | 6 个 `*Test.java` 文件存在；但实际执行测试方法的只有 **5 个**，第 6 个 `BaseIntegrationTest` 是抽象基类、含 0 个测试方法，surefire 也不会将其作为测试类执行 | **部分不符（口径问题）** |

关于第三项的说明：若"6 个测试类"指"6 个以 Test 结尾的 Java 文件"，与事实相符；
若指"6 个含测试用例的测试类"，与实测不符——实际是 5 个测试类 + 1 个抽象基类。
surefire 输出的逐类结果行共 5 行，与"执行了 5 个测试类"一致。此项为**表述口径偏差**，
不影响"38 个用例全部通过"这一核心数字。

## 五、结论

**【实测-符合】**

核心声明"38 个 JUnit 用例全部通过"经亲自执行完整测试套件（未跳过、未筛选）
实测成立：`Tests run: 38, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`。
唯一偏差是"6 个测试类"的口径：仓库有 6 个 `*Test.java` 文件，但含用例的测试类为 5 个，
第 6 个 `BaseIntegrationTest` 是抽象基类（0 个 `@Test`）。

---

附件：`V1-附件-mvn-test-raw.log`（本次执行的完整原始日志）
