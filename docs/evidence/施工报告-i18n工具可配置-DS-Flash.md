> 施工报告（worktree 内产出，**未 commit**）。施工面：`feature/i18n` 分支，worktree `wt-i18n`。
> 纪律：禁 commit/push；未改 `docs/任务看板.md` / `docs/决策看板.md` / `.gitignore`；未起任何服务；
> 界面无 emoji；全 Tailwind、无手写 style；证据件内无本机绝对路径字面量。

# 施工报告 · i18n + 工具可配置专项（DS-Flash，2026-10-21）

## 0 自证与对象（DC-19）

| 项 | 值 |
| --- | --- |
| 执行者模型 | **DeepSeek/deepseek-flash**（施工官，实施角色） |
| 授权来源 | 用户 2026-10-21 指令「前序的任务直接实施」 |
| wire 自证行 | `agents/agent-1385/wire.jsonl`：`agentId=agent-1385` · `modelAlias=DeepSeek/deepseek-flash` · `profileName=coder` · `thinkingEffort=high`；turn 首句与本任务卡逐字一致（`你是施工官，负责「i18n + 工具可配置」专项施工`） |
| 施工基点 | 主仓 `origin/main` = **`1cf6eab`**（`git fetch` 后实测；「模型配置批③合流证据」笔已在） |
| 工作树 | `wt-i18n`（主仓同级目录），分支 `feature/i18n`，基点 `1cf6eab` |
| 必读备料 | `勘察-i18n工具可配置-DS-Flash.md`（505 行，全文读毕）+ `docs/research/设计-i18n与工具可配置专项-2026-10-21.md`（552 行，§2/§3/§4/§6/§10/§11 读毕）+ `docs/research/调研-i18n与工具可配置业界惯例-2026-10-21.md`（附录 A 结论经设计稿转述引用） |

## 1 门禁基线（开工实测，空载）

| 面 | 命令 | 开工结果 |
| --- | --- | --- |
| 前端单测 | `node node_modules/vitest/vitest.mjs run` | **38 文件 / 623 例全绿**，16.01s（`TMP/TEMP=<SCRATCH>`） |
| 后端单测 | `mvn -o test` | **1144 例 / 0F / 0E / 0S**，2:59（BUILD SUCCESS） |
| 类型检查 | `vue-tsc --noEmit` | 0 错（施工前未单跑，施工后为 0；见 §8） |
| 构建 | `vite build` | 施工后成功（见 §8） |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | 6/6 绿 |

> 勘察件记的 30 文件 / 559 例已过时（本仓在基点已长到 **38 / 623**）。**本批验收基数 = 38 / 623（前端）、1144（后端）**，断言只升不降。

## 2 测试矩阵（先行落表，按此验收）

### M-A 底座与切换（S1）

| # | 用例 | 期望 | 落点 | 结果 |
| --- | --- | --- | --- | --- |
| A1 | bootstrap | `createI18n({legacy:false})` 后 locale=zh-CN，`t('common.app.title')`='配置管理平台'；组件内 `useI18n` 同实例 | `tests/i18n-bootstrap.spec.ts` | ✅ |
| A2 | 默认不嗅探 | 伪造 `navigator.language='en-US'` 仍 zh-CN；支持语言恰两枚 | 同上 | ✅ |
| A3 | 切换四件事 | `setLocale('en-US')` ⇒ i18n / `<html lang>` / `localStorage['cm.locale']` / 下一请求 `Accept-Language` 同变（用自定义 adapter 截获请求头） | `tests/locale-store.spec.ts` | ✅ |
| A4 | 持久化优先序 | localStorage 有值 > 默认；`ko-KR` 回落 zh-CN 且不写脏值；`init()` 能恢复 | 同上 | ✅ |
| A5 | Element Plus 联动 | `elLocale.name`：zh→`zh-cn`、en→`en`（根级 `<el-config-provider>`） | 同上 | ✅ |
| A6 | Day.js 联动 | en→`dayjs.locale()='en'`、zh→`'zh-cn'`；toggle 往返 | 同上 | ✅ |
| A7 | 路由标题 | 7 条 `meta.titleKey`、无遗留 `meta.title`；同一路由中英标题不同；无 key 不改标题 | `tests/router-title.spec.ts` | ✅ |
| A8 | 测试注入 | `mount()` 不传 i18n 也能渲染（`tests/setup.ts` 一处 `config.global.plugins`） | 全量 694 例 | ✅ |

### M-B 键与插值（S1/S2）

| # | 用例 | 期望 | 结果 |
| --- | --- | --- | --- |
| B1 | 键集**双向**对齐 | zh ⊆ en 且 en ⊆ zh，差集为空（含 `tool.*` 全量键） | ✅ |
| B2 | 缺 key 行为 | 渲染 key 原文、不空白、不抛 | ✅ |
| B3 | 插值完整性 | 每个 key 的 `{占位符}` 集合 zh/en 同名同数 | ✅ |

### M-C 语义等价（本批核心：zh 逐字不变）

| # | 用例 | 期望 | 结果 |
| --- | --- | --- | --- |
| C1 | 工具摘要 zh 逐字等于迁移前 | **45 条模板快照**（含 `#{taskId}`/`{count}` 变体、无参数变体、别名 `open_page`/`select_defs`） | ✅ |
| C2 | 工具标题 zh 逐字等于迁移前 `TOOL_LABELS` | **35 条**（26 后端名 + 7 页面别名 + `download_templates` + 8 条 `export.*`/`import.*`） | ✅ |
| **C5** | **双受众红线** | 切 en 后 `recentActions` 送模型的串**仍是中文**（逐字等于 zh 下同串）；`buildContext().extra.recentActions` 仍带中文摘要与中文来源；`PAGE_LABELS` 五页中文名不变 | ✅ |
| C6 | `PAGE_LABELS` 前后端一致 | 前端镜像仍为中文常量（模型侧文案，不随 UI 语言） | ✅ |

### M-D `error.code` 契约（S0/S6）

| # | 用例 | 期望 | 结果 |
| --- | --- | --- | --- |
| D1 | 单一源 | 码**声明**只在 `ErrorCodes`；其它类为别名；唯一例外 `PendingToolCall`（状态字面量同名不同义）显式白名单 | ✅ `ErrorCodesSingleSourceTest` |
| D2 | **三方对账** | `ErrorCodes`(87) ↔ 前端 `ErrorCode`(87+2 前端独有) ↔ `messages_zh/en` 键集，三向差集为空 | ✅ `ErrorCodesContractTest` |
| D3 | 无码面清零 | `IllegalArgument/IllegalState` 响应体必带 `code`；请求路径 30 处显式码（定义/数据/Excel/文件/步骤/预检/租约/信封/摘要），其余按类型缺省码 | ✅ `GlobalExceptionHandlerTest` |
| D4 | messages 覆盖 | 87 码 × 2 locale 全覆盖；无键回落原文，绝不回显裸 `error.` key | ✅ |
| D5 | locale 协商 | 无 `Accept-Language` ⇒ zh（逐字不变）；`en-US` ⇒ 英文文案；`zh-TW` 回落 `zh` | ✅ |
| D7 | ProblemDetail 双形态 | `Accept: application/problem+json` ⇒ RFC 9457（`type=urn:cfg:error:<CODE>`/`status`/`detail`/`code`/`traceId`）；其它 Accept ⇒ 信封字节不变 | ✅ |
| D8 | `args`/`traceId` 非破坏 | 两者 `NON_NULL`，无值时不新增键 | ✅ |

### M-E 工具注册表（S2）

| # | 用例 | 期望 | 结果 |
| --- | --- | --- | --- |
| E1 | 覆盖生效 | YAML description 覆盖送模型的 `ToolDefinition.description()`，`call()` 行为不变 | ✅ |
| E2 | 缺省回落 | YAML 无该条 / 文件缺失 ⇒ 注解值（**放宽契约**：注解缺省、YAML 可选覆盖） | ✅ |
| E3 | schema 对账 | 提供 `inputSchema` 时与反射 schema 比对（property:type + required 集合，键序无关），漂移即启动失败并打印 diff | ✅ |
| E4 | 装配期只 load 一次 | 计数来源断言 `loads == 1`（请求路径零 IO） | ✅ |
| **E8** | **ToolContext 不丢** | 装饰器**两个** `call` 重载都委托；两参重载调用后工具侧 `runId` 仍在 | ✅ |
| E9 | `displayName` 退役 | 展示名来自 `title`（不再取 description 前 20 字）；`GET /api/ai/tools` 增 `titleKey`/`title`/`lang`/`definitionsVersion` | ✅ |
| E10 | 迁移号 | 本批未建表；`V5` 为后续 DB 版本化预留（现仅 V1–V4，无争号） | 登记 |

### M-G 浏览器双语验收（第 7 组，本批**未执行**）

| # | 用例 | 期望 | 状态 |
| --- | --- | --- | --- |
| G1 | zh/en 逐页截图（7 路由 + AI 面板 4 种卡态） | chrome 层零中文（模型正文/数据值除外） | ⏸ 未执行（禁起服务；需下一批 `verify-e2e` 环境） |
| G2 | 切换语言只影响后续渲染 | 历史消息正文与工具结果原文不回溯 | ⏸ 未执行 |
| G3 | Element Plus 内建文案跟随 | 分页/日期面板在 en 下英文 | ⏸ 未执行（单测 A5 已覆盖 locale 对象，非渲染） |

> 静态扫描不能替代浏览器验收：本批只承诺"管道通 + zh 逐字不变 + 单测层联动正确"，不承诺"en 下全界面无中文"（S3–S5 未做，业务页面在 en 下仍是中文）。

### M-F 回归面

| # | 面 | 结果 |
| --- | --- | --- |
| F1 | 前端单测 | **43 文件 / 694 例全绿**（基线 38/623，+5 文件 +71 例） |
| F2 | 后端单测 | **1171 例 / 0F / 0E / 0S 全绿**（基线 1144，本批 +27 例） |
| F3 | E2E 脚本 | **零改动**；14 处中文耦合逐条登记（§10） |
| F4 | typecheck / build | 0 错；`vue-i18n` 进 `vendor` chunk |
| F5 | 文档门禁 | 6/6 |

## 3 完工片与自决清单

| 片 | 状态 | 说明 |
| --- | --- | --- |
| **S0** `error.code` 收口 | ✅ 完成 | 单一源 + 三方对账 + 无码面清零 + 双形态 |
| **S1** i18n 底座 | ✅ 完成 | vue-i18n 11.4.16 + lock 同笔 + 四件事 + EP/dayjs 联动 |
| **S2** 工具文案外置 | ✅ 完成 | 四源收口：`TOOL_LABELS`→`tool.*.title`、`tool-summaries`→`tool.*.<variant>`、`FRONTEND_TOOLS.displayName` 保留为诊断、后端 `displayName` 不再截断 description |
| **S6** 后端 messages | ✅ 完成（异常面） | `messages_zh/en` 87 键 + `AcceptHeaderLocaleResolver` + `ErrorBodyFactory` + 双形态；**AI 端点裸对象 message 未接 locale**（见 §11-3） |
| **S3/S4/S5** 文案搬迁 | ⏸ 未施工（挂账） | codemod 三段式**已就绪并实跑 extract/dry-run**（227 处可机械替换 / 17 文件），迁移本身未执行 |
| **S7** DB 版本化 + 守卫 | ⏸ 未施工（挂账） | V5 预留；残留扫描器未开闸 |

### 自决清单（无待裁项上报）

| # | 自决 | 理由 | 影响面 |
| --- | --- | --- | --- |
| D-1 | **locale 资源静态 import 两份**（不做 en 懒加载） | 勘察 §5.0.1 明确"不要 `import()` 懒加载进测试路径"：`ai-panel-parts-render`（188 例）与 `ai-panel-composer-limit`（30s 导入预算）是最先超时的两个面；两份 JSON 合计 < 8KB | `locales/index.ts`；M1-2 原"懒加载"断言改为"两枚语言常驻、切换同步" |
| D-2 | **zh 下 message 一律用调用方原文**，`messages_zh` 只作"无原文"兜底与 ProblemDetail title | 29 条 E2E 与 14 处中文断言按文案判定；把动态文案搬进 properties 再回读必然改字节 | `ErrorBodyFactory` |
| D-3 | **工具外置契约放宽**：注解=缺省、YAML=可选覆盖，只有写了 `inputSchema` 才对账 | 表格/可视批次还会 +15 工具；"每个工具必须有 YAML 行"会让新工具直接撞红（勘察 §4.3-4 建议） | `ToolSnapshot`/`ToolRegistry` |
| D-4 | **`displayName` = `title`（中文），`titleKey` 为前端权威** | 设计稿要求"退役派生"；保留 `displayName` 字段维持诊断面兼容，但值不再随 description 变 | `ToolMeta`/`AiToolsController` |
| D-5 | **无码异常用 `Coded*Exception`（子类+接口）而非新基类** | 既有调用方按 `IllegalArgumentException`/`IllegalStateException` 分支（工具执行器区分"参数非法"与"内部故障"），换父类会静默改语义 | `common/Coded*` |
| D-6 | **S2 先落 UI 标题，模型侧 description 保持注解中文单语** | 裁决 A/B：模型侧单语、不随 locale；切英文是独立评测项（会改 18×description + 生成式表单文案） | `ai/tools.yaml` 仅 title/titleKey |
| D-7 | **S3–S5 未开工**，只交付 codemod 与快照夹具 | 见 §11 挂账；在上下文/时间预算内，半迁移（部分页面英文、部分中文且 zh 断言被打红）比不动更糟 | `frontend/scripts/i18n/codemod.mjs` |

## 4 S0 落地（`error.code` 收口）

- 新增 `common/ErrorCodes.java`：**87 个码**，分组 HTTP / 工具结局 / 数据面 / 业务冲突 / 定义校验 / AI 配置 / 作业与 SSE / 内部错误；`ALL` 由反射收集（供对账用例）。
- 新增 `common/CodedException` + `CodedIllegalArgumentException` + `CodedIllegalStateException`：**is-a 关系不变**，多带一个码。
- `GlobalExceptionHandler` 重写出口：任何 `IllegalArgument/State` 都带码（显式码优先，否则 `ILLEGAL_ARGUMENT`/`ILLEGAL_STATE`）；`Accept: application/problem+json` 才切 RFC 9457；其余响应字节不变。
- `ApiResponse` 增 `args` / `traceId`（均 `NON_NULL`，无值不出键）。
- 载体收口：`ToolOutcome` 15 个 `CODE_*` → `ErrorCodes` 别名；3 个 service 的 8 种 `ConflictException` 码 → 常量；`AiController` 18 处裸字面量 → 常量；SSE `AI_RESUME_FAILED`/`AI_RUN_FAILED`/`UPSTREAM_STREAM_ERROR` → 常量；9 个 AI 配置类/表单规则/预检/作业服务的码常量 → 别名。
- 请求路径 **30 处** `IllegalArgument/State` 补显式码（定义 5、数据 2、依赖 1、Excel 2、文件 2、任务 6、预检 2、租约 2、SSE 1、信封 2、工具 1、摘要 5）。
- 启动期配置校验（`AiProperties`/`AppProperties`）与内部存储（`RunStore`/`RedisFrameStore`/`MessageJsonCodec`）**不补码**：它们到不了 HTTP 面，补码只增噪音（登记于 §11-4）。

## 5 S1 落地（前端底座）

- `vue-i18n@11.4.16`（`--exact`）+ `yarn.lock` 同笔（CI `--frozen-lockfile` 要求）。
- `src/locales/{index.ts,zh-CN.json,en-US.json}`：`legacy:false`、`fallbackLocale:'zh-CN'`、`applyLocale()`（i18n + `<html lang>` + `localStorage['cm.locale']`）、`initLocale()`、`acceptLanguage()`。
- `src/stores/locale.ts`：单一源 `locale`；`elLocale` 派生；`setLocale` 联动 dayjs；`init()` 启动恢复。
- `App.vue`：根级 `<el-config-provider :locale>`；标题/菜单/页脚/语言切换钮全部 `t()`（**无 emoji，全 Tailwind**）。
- `main.ts`：`app.use(i18n)` + `useLocaleStore(pinia).init()`；移除入口的静态 `zhCn` locale。
- `router/index.ts`：`meta.title` → `meta.titleKey`（7 条）+ `applyDocumentTitle()`（导出以便用例直调）。
- `api/http.ts`：请求拦截器注入 `Accept-Language`（后端未落地前被忽略，零行为变更）。
- `tests/setup.ts`：`config.global.plugins = [i18n]` **一处注入**；`beforeEach` 归零语言（避免用例间语言串味）。
- `vite.config.ts`：`manualChunks.vendor` 加 `vue-i18n`。

## 6 S2 落地（工具文案四源收口）

| 源 | 迁移前 | 迁移后 |
| --- | --- | --- |
| ① `AiPanel.vue#TOOL_LABELS`（35 条） | 组件内字面量 map | `tool.<name>.title`（zh 逐字同值）；`toolLabel()` 缺键回落工具名 |
| ② `utils/tool-summaries.ts`（45 模板 + 10 状态词） | 中文裸串 + switch | `tool.<name>.<variant>` + `tool.status.*` + `tool.fallback`；switch 只留"选哪个变体" |
| ③ `types/tools.ts#FRONTEND_TOOLS[].displayName` | 前端工具描述 | 保留为**执行器诊断元数据**（不参与渲染）；渲染侧统一走 ① |
| ④ 后端 `ToolRegistry` displayName | `description[:20]` | `title`（YAML）→ 缺省=工具名；**不再随 description 变** |

- 新增 `ai/tools.yaml`：26 条 `toolKey + title`（description/schema 留注解，契约 D-3）。
- 新增 `ToolDefinitionSource` / `ToolSnapshot` / `ToolDefinitionOverride` / `YamlToolDefinitionSource` / `OverriddenToolCallback` / `ToolSchemaReconciler`。
- `ToolRegistry`：装配后应用覆盖（只换 `getToolDefinition()`，执行原样委托）；覆盖指向未注册工具 ⇒ **启动失败**；`GET /api/ai/tools` 增 `titleKey`/`title`/`lang`/`definitionsVersion`。

## 7 S6 落地（后端 messages + 双形态）

- `messages_zh.properties` / `messages_en.properties`：**87 键**（`error.<CODE>`），键集与 `ErrorCodes` 双向相等。
- `application.yml`：`spring.messages.{basename,encoding,fallback-to-system-locale,cache-duration}`。
- `common/WebI18nConfig`：`AcceptHeaderLocaleResolver`，默认 `zh_CN`，支持 `zh_CN`/`en_US`。
- `common/ErrorBodyFactory`：`envelope()`（默认形态）/ `problem()`（RFC 9457）/ `message()`（zh 原文优先、en 查 messages、缺键回落、绝不裸 key）。
- **池线程纪律**：locale 在请求线程由 `LocaleContextHolder` 取用（handler 在请求线程），未在 `app.ai.suspend` / `sse-write-*` 池上读取；SSE 帧文案未改（帧契约 16 冻结）。

## 8 门禁数字（完工实测）

| 面 | 命令 | 结果 |
| --- | --- | --- |
| 前端单测 | `vitest run` | **43 文件 / 694 例全绿**（基线 38/623；+5 文件 +71 例） |
| 后端单测 | `mvn -o test` | **1171 例 / 0F / 0E / 0S**，BUILD SUCCESS（连跑两遍同结果：1:49 / 2:34；基线 1144；本批 +27） |
| 类型检查 | `vue-tsc --noEmit` | 0 错 |
| 构建 | `vite build` | ✓ 40.23s；`vendor` 222.02 kB / gzip 82.17（含 vue-i18n） |
| 文档门禁 | `check-docs.mjs` | 6/6 绿 |
| E2E | `verify-e2e.ps1` | 未运行（禁起服务）；**脚本零改动** |

## 9 zh 快照比对留痕（迁移前后逐字）

快照以**可执行断言**形式留痕（比文本 diff 更可靠：每次 `vitest run` 都重验）。

| 快照 | 条数 | 落点 | 迁移前取值来源 |
| --- | --- | --- | --- |
| 工具标题 | 35 | `tests/tool-i18n-coverage.spec.ts#LEGACY_TITLES` | 施工前 `AiPanel.vue:1371` 的 `TOOL_LABELS` 字面量（逐字抄录） |
| 工具摘要模板 | 45 | 同文件 `#LEGACY_SUMMARIES` | 施工前 `utils/tool-summaries.ts#summaryFor` 的输出（按参数分支逐条抄录） |
| 状态词 | 10 | 同文件（`toolStatusWord` 断言） | 施工前 `TOOL_SUMMARY_STATUS_WORDS` |
| 双受众串 | 3 类 | `tests/workspace-audience.spec.ts` | 施工前 `stores/workspace.ts#touch` 的调用串（未迁移，断言"切 en 仍中文"） |
| 路由标题 | 7 | `tests/router-title.spec.ts` | 施工前 `router/index.ts` 的 `meta.title` |
| 页脚/导航 | 8 | `i18n-bootstrap.spec.ts`（`common.app.title`）+ 构建产物 | 施工前 `App.vue` 模板文本 |

比对结果：**全部逐字相等**（694 例中相关断言无一失败）。

## 10 E2E 中文耦合登记（14 处，脚本零改动）

| # | 行 | 形态 | 文案 | 本批是否触及 |
| --- | --- | --- | --- | --- |
| 1 | 624 | 正向 `-like` | `*必填字段*` | 否（校验消息未改） |
| 2 | 625 | 正向 | `*主键重复*` | 否 |
| 3 | 626 | 正向 | `*不存在*` | 否 |
| 4 | 627 | 正向 | `*必填字段*` | 否 |
| 5 | 781 | 正向 | `*无法从文件名匹配配置编码*` | 否 |
| 6 | 787 | 正向 | `*没有可匹配的文件*` | 否 |
| 7 | 851 | 正向 | `*未找到上传文件*` | 否 |
| 8 | 858 | **反断言** `-like`（出现即失败） | `*明细写入失败*` | 否 |
| 9 | 1011 | 正向 | `*未找到上传文件*` | 否 |
| 10 | 1753 | **反断言** `-notlike` | `*前端工具被用户取消*` | 否 |
| 11 | 1833 | **反断言** `-notlike` | `*挂起仍在等待*` | 否（表单回灌文案未改） |
| 12 | 1868 | **反断言** `-notlike` | `*前端工具回灌被安全闸拒绝*` | 否 |
| 13 | 1958 | 反断言（`if -like` 即失败） | `*前端工具入参被安全闸拒绝*` | 否 |
| 14 | 2599 | 反断言（`-like` 即失败） | `*NoSuchFileException*` / `*明细写入失败*` | 否 |

## 11 文件清单

**新增（后端主代码 12 + 资源 3 + 测试 4）**：`common/{ErrorCodes,CodedException,CodedIllegalArgumentException,CodedIllegalStateException,ErrorBodyFactory,WebI18nConfig}.java`、
`ai/tool/{ToolDefinitionSource,ToolSnapshot,ToolDefinitionOverride,YamlToolDefinitionSource,OverriddenToolCallback,ToolSchemaReconciler}.java`、
`resources/{messages_zh,messages_en}.properties`、`resources/ai/tools.yaml`、
`test/.../common/{ErrorCodesContractTest,ErrorCodesSingleSourceTest,GlobalExceptionHandlerTest}.java`、
`test/.../ai/tool/ToolDefinitionOverridesTest.java`。

**新增（前端 8 个路径，含 5 个 spec 与 3 个 locale/目录）**：`src/locales/{index.ts,zh-CN.json,en-US.json}`、`src/stores/locale.ts`、
`tests/{i18n-bootstrap,locale-store,router-title,tool-i18n-coverage,workspace-audience}.spec.ts`、
`scripts/i18n/codemod.mjs`。

**改动（后端主代码 34 + 测试 1）**：`common/{ApiResponse,GlobalExceptionHandler,ResourceNotFoundException}`、`ai/tool/{ToolMeta,ToolOutcome,ToolRegistry,ToolResultEnvelope}`、`ai/web/{AiController,AiToolsController}`、`ai/config/{AiConfigService,AiConfigWriter,AiConnectionProbe,AiEndpointGuard,AiKeyCipher}`、`ai/form/GenerativeFormRules`、`ai/gate/{SpToolCallingManager,ToolPreflightService}`、`ai/run/{LeaseKeys,ResumeService,SseChatEmitter,StreamViolationException}`、`ai/context/ContextSummaryClient`、`ai/tools/AiTools`、`data/service/{ConditionEvaluator,ConfigDataService}`、`definition/service/{DefinitionService,DependencyResolver}`、`excel/ExcelReader`、`file/FileStorageService`、`job/service/JobService`、`task/{controller/FileController,controller/TaskController,service/TaskService}`、`resources/application.yml`、`test/.../common/GlobalExceptionHandlerSseTest.java`。

**改动（前端 11）**：`package.json`、`yarn.lock`、`src/{main.ts,App.vue}`、`src/api/http.ts`、`src/router/index.ts`、`src/components/AiPanel/AiPanel.vue`、`src/utils/tool-summaries.ts`、`src/types/api.ts`、`tests/setup.ts`、`vite.config.ts`。

## 12 剩余片挂账与后续批建议

1. **S3 文案抽取 · AI 面板**（最大面）：`AiPanel.vue` 164 条 + `stores/ai.ts` 57 条（含 37 处 `notice`）——`notice` 是"本地模板 + 后端 message"混合体，需先拆 `code + args` 再 t()，不能机械替换。
2. **S4 五页 + 公共件**：Export 130 / Definitions 99 / Import 94 / TaskCenter 51 / DataBrowser 34 / JobProgress 26 / AiSettingsView 82 / `ai-settings-copy.ts` 82。codemod 已能机械处理其中 **227 处**（84 文本节点 + 143 静态属性 / 17 文件），剩余靠人工（插值/多行/`reason` 串）。
3. **S5 双受众二分**：`stores/workspace.ts#touch`、`PAGE_LABELS`、`types/condition.ts#label`。C5 用例已把"切 en 仍中文"钉死，二分时**必须**保持该断言绿。
4. **S6 尾项**：`AiController` 的 9 个裸 `Map.of(code,message)` 未接 locale（接 `ErrorBodyFactory` 需改其构造签名，会打红 4 个现有测试的 `new AiController(...)`；建议独立小批，顺带把 AI 端点信封也走同一工厂）。
5. **S7 DB 版本化 + 守卫**：`V5__ai_tool_registry.sql`（`ai_tool_def`/`ai_tool_release` + 两个 JPA 实体，`ddl-auto=validate` 要求逐字段对齐）+ 缺 `production` 标签 fail-fast + `check-i18n-residue.mjs` 进 CI（现网 1201 条中文 ⇒ 只能最后开闸）。
6. **E2E 断言口径升级（B 方案）**：把 14 处中文 `-like`/`-notlike` 改成按 `code` 断言，与本脚本同批做（属独立小批，不与 i18n 混）。
7. **`AiSettingsView` 的 `ai-settings-copy.ts`** 已是"code→文案"形态，迁移时把它改为 `i18n` 键即可，不必重写业务逻辑。

## 13 诚实边界

- **未跑 E2E**（需起桩上游 + Redis + 后端，违反"不起服务"）：14 处耦合是**静态登记**，不是运行观测。zh 字节不变由单测快照 + "zh 用调用方原文"的实现口径共同保证。
- **未做浏览器双语截图**：本批无服务，无法验收"en 下 chrome 层无中文"；`en-US.json` 的翻译质量（术语）未经验收，只保证键集齐全与 zh 正确。
- **`tool-summaries` 的 en 输出是直译**（45 模板），未经产品/领域校订。
- **`displayName` 值的变化是可见 API 变更**：26 个后端工具从"description 前 20 字"变为 `title`（中文短名）。这是 E9 要求的行为，但**下游若有按 displayName 文案比对的外部脚本会失效**（本仓无此用例）。
- **`GET /api/ai/tools` 新增 4 个字段**（`titleKey`/`title`/`lang`/`definitionsVersion`）：字段只增，既有按 `name/count` 的断言不受影响。
- **启动期配置类未补码**（`AiProperties`/`AppProperties` 等 30 处）：它们到不了 HTTP 面，D3 的"响应必带码"由 handler 缺省码保证。
- **S3–S5 未开工**：本批的 i18n 只覆盖"管道 + 工具面"，业务页面在 en 下仍是中文（预期，见 §3）。
