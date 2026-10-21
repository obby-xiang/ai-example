# 施工报告 · 业务知识问答 v1（Lucene 内嵌 BM25 + agentic 检索）（施工官 · DS-Flash · 2026-10-21）

## 0 自证行（DC-19 · wire 实测，非照抄卡片）

- 施工官 agentId = `agent-1418`；wire = `<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_ced79706-4f65-44a8-a80c-eb2eb2542928/agents/agent-1418/wire.jsonl`
- 取数方式：`ls -lat` 该 session 的 `agents/` 目录，**最新新建目录** = `agent-1418`（22:34）；再 `grep -m1 '"profile.bind"'` 命中单行，原文首部（逐字）：

```json
{"type":"profile.bind","agentId":"agent-1418","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",...}
```

- 实测三字段：`modelAlias` = **`DeepSeek/deepseek-flash`**、`profileName` = **`coder`**、`thinkingEffort` = **`high`**
- 身份核对：同目录 `turn.prompt` 首句 = 「你是施工官，负责「业务知识问答 v1（Lucene 内嵌 BM25 + agentic 检索）」施工。」—— 与本卡一致。
- 授权来源 = 用户 2026-10-21「那就按 Lucene 方案来」（乙案）+ 工具契约定稿 + 指挥官 2026-10-21 追加两项（20 篇样例上库、KbSource 生产 HTTP 源）。
- 施工分支 = `feature/kb-v1`（worktree `../wt-kb-v1`），基于 `main@74aaf96e2bb5bd738e97b81d4b08aa2812ff029e`；**未 commit / 未 push**。
- 并行关系：超时批② 在 `wt-timeout-b2` 施工，本批只碰 `AiProperties`（新增 `Kb` 嵌套段）、`ResilientChatService`（一处字段注入 + 一处 systemText 合成）、`application.yml` 的 `app.ai.kb` 段与 `ai/kb/**`、`AiTools` 未改（新工具落在独立 `KbTools`，由 ToolRegistry 自动扫描）；合流时批②先合、本批 rebase。

## 1 范围逐项落地

| # | 定稿范围 | 落点 | 状态 |
| --- | --- | --- | --- |
| 1 | 知识库载体 `./data/kb/*.md` + `manifest.json`（id/title/tags/aliases/sha/updated_at） | `KbManifest`/`KbDocMeta`；样例仓 `docs/kb-samples/`（20 篇）；原子写入与 sha 校验流程见手册 §3.2 | 完成 |
| 2 | 索引层 `KbService`：启动异步建索引、smartcn + BM25、`##` 切块 300–800 + 路径前缀、标题加权、FSDirectory、sha 增量、手动热重载原子切 reader | `ai/kb/KbService`、`MarkdownChunker`、`KbConfig` | 完成 |
| 3 | 三工具 `kb_search`/`kb_read_doc`/`kb_lookup_term`（top_k 5/10、三态空结果、match 三档、不给原始分、分页、精确/别名、无静默模糊、8000 字符闸） | `ai/kb/KbTools`、`KbText` | 完成 |
| 4 | `SYSTEM_PROMPT` 追加稳定后缀段（目录注入 + 七条规则） | `KbPromptSection` + `ResilientChatService` 两行合成（既有人设原文零改动） | 完成 |
| 5 | UI 零定制（默认工具卡） | 前端零改动（仅跑门禁） | 完成 |
| 6 | 数据安全：样例无真实敏感信息；`<knowledge>` 定界 + 声明 + 定界符剥离 | `KbText`（NOTICE / sanitize / 8000 闸） | 完成 |
| 7 | 手册 `docs/知识库维护手册.md` | 新增（含生产 HTTP 源接入） | 完成 |
| 追1 | 20 篇差异化样例上库 | `docs/kb-samples/`（20 md + manifest.json，显式路径待 add） | 完成 |
| 追2 | 知识源抽象 `KbSource`（local/http） | `KbSource`/`LocalDirKbSource`/`HttpKbSource`/`KbConfig` | 完成 |
| 追3 | Redis cache-aside 缓存层（手写，禁 Spring Cache）：键族/TTL/单飞/版本传播/stale-if-error | `KbCache`/`KbCacheStore`/`RedisKbCacheStore`/`CachedKbSource` | 完成 |
| 追4 | i18n 桥接（不建翻译副本）：`aliases_en` 字段 + 英文规则 + 样例外语别名 | `KbDocMeta.aliasesEn`、`KbService` 索引、`KbPromptSection` | 完成 |

## 2 施工矩阵

> `红` = 实现前跑红并留痕（`docs/evidence/kb-v1-red.log`）；`绿` = 实现后通过（`docs/evidence/kb-v1-green4.log` / `kb-v1-backend-full3.log`）。

| # | 矩阵项 | 断言要点 | 用例 | 状态 |
| --- | --- | --- | --- | --- |
| M1 | 索引切块 | `##` 为界；≤800 硬上限；超长段落硬切；代码块/mermaid 整块保留且不被内部空行切断；宽表按行切分并**重复表头** | `MarkdownChunkerTest` | 绿 |
| M2 | 标题/章节路径前缀 | `#/##/###/####` 路径进入 `sectionPath` 与 `indexText` 前缀 | `MarkdownChunkerTest`、`KbServiceIndexTest` | 绿 |
| M3 | smartcn 中文召回 | 无空格中文整句命中费率文档 | `KbServiceIndexTest#chineseSentenceQueryHitsRelevantDocWithoutSpaces` | 绿 |
| M4 | aliases 命主 | 「费率表」召回 `region-tariff` | `KbServiceIndexTest`、`KbLookupTermTest` | 绿 |
| M5 | top_k 截断 | 默认 5、999→10、≤0→默认 | `KbPropertiesTest`、`KbToolsTest` | 绿 |
| M6 | 三态空结果 | 0 条 `no_match` + searched_terms + 2–3 近似标题；≤2 条 `low_confidence` 全标 low；异常 `error` 可行动不堆栈 | `KbServiceIndexTest`、`KbToolsTest` | 绿 |
| M7 | 原始分不外泄 | `KbSearchHit` 结构无分数字段；渲染无 score/BM25/工具名 | `KbServiceIndexTest`、`KbToolsTest` | 绿 |
| M8 | read_doc 分页连续性 | 8029 字长文按 `next_offset` 逐页读完，拼接 == 原文 | `KbReadDocTest#paginationByNextOffsetReconstructsWholeDocument` | 绿 |
| M9 | read_doc 候选 | 错 id/section 给候选、不抛异常 | `KbReadDocTest`、`KbToolsTest` | 绿 |
| M10 | read_doc 上限 | 首屏 ≤1200 字 + 目录；max_chars 默认 4000/上限 8000 | `KbReadDocTest`、`KbToolsTest` | 绿 |
| M11 | lookup_term 精确/别名 | 归一化（空白/大小写/全半角）后精确或 alias 命中，带 source_doc/section/updated_at | `KbLookupTermTest`、`KbToolsTest` | 绿 |
| M12 | lookup_term 不模糊 | 「标准费」「标准费用」均 `found=false`，回执 `next=use kb_search` | `KbLookupTermTest`、`KbToolsTest` | 绿 |
| M13 | 热重载原子性 | 4 线程持续检索 + 3 次重建，零空结果/零异常 | `KbServiceIncrementalTest#concurrentSearchesNeverSeePartialIndexDuringReload` | 绿 |
| M14 | sha 增量 | sha 不变不重取正文、实例级复用；变则只重取该篇；删条目即出索引 | `KbServiceIncrementalTest` | 绿 |
| M15 | 未就绪 fail-open | 未建索引时三工具回「知识库加载中」 | `KbToolsTest`、`KbServiceIndexTest` | 绿 |
| M16 | 注入定界剥离 | 内容中的 `<knowledge>`/`</knowledge>`/零宽字符被中和；每次结果带声明 | `KbToolsTest#injectedDelimitersAndZeroWidthCharsAreNeutralized` | 绿 |
| M17 | 工具披露三防线 | `@ToolScope("*")` ⇒ 任意上下文披露；`matchesContext` 放行；READ/BACKEND/R | `KbDisclosurePromptTest` | 绿 |
| M18 | 8192 闸兼容 | 12000 字文档读取结果 ≤8000 字符 | `KbToolsTest#toolResultNeverExceeds8000Chars` | 绿 |
| M19 | local/http 两源等价 | 同 corpus 的 doc/chunk 数与 top 命中一致 | `KbHttpSourceTest#localAndHttpSourcesIndexTheSameCorpus` | 绿 |
| M20 | http 超时/5xx fail-open | 旧索引保留、`last_error` 可行动 | `KbHttpSourceTest` | 绿 |
| M21 | ETag 不变不重建 | 304 ⇒ `rebuilt=false`、`built_at` 不变 | `KbHttpSourceTest#unchangedEtagSkipsRebuild` | 绿 |
| M22 | sha 变增量 | 只重拉变更文档 | `KbHttpSourceTest#changedShaFetchesOnlyThatDocument` | 绿 |
| M23 | 鉴权头不泄漏 | 头会发送；401 的 `last_error` 不含 token | `KbHttpSourceTest#authHeaderIsSentButNeverLeaksInErrors` | 绿 |
| M24 | 配置校验 | top_k/字符上限/切块/阈值/刷新间隔倒挂 fail-fast；http 无 base-url fail-fast | `KbPropertiesTest` | 绿 |
| M25 | 样例 20 篇差异化 | 篇数/长度分布/复杂度元素/样例标注/manifest sha 与文件一致 | `KbSampleCorpusTest`、`KbLocalSourceTest` | 绿 |
| M26 | 提示词后缀 | 目录 id+title+summary、七条规则、未就绪只注入规则、目录注入被中和 | `KbDisclosurePromptTest` | 绿 |
| M27 | 管理端点 | status/reload/docs；失败 reload 保留旧索引并回 `last_error` | `KbDisclosurePromptTest#adminEndpointsExposeStatusDocsAndReload` | 绿 |
| M28 | 缓存键族 | `kb:cache:doc:<id>` / `kb:cache:manifest` / `...:version` / `...:refresh:lock` 逐字稳定 | `KbCacheTest#keyFamilyIsStable` | 绿 |
| M29 | 缓存 TTL | 文档/清单键无 TTL（-1）；锁 30s 且过期可被重抢 | `KbCacheTest`、`CachedKbSourceTest` | 绿 |
| M30 | 冷启动从缓存重建 | 远端不可用时仍从缓存建出完整索引（0 次远端清单调用） | `CachedKbSourceTest#coldStartRebuildsFromCacheWhenDelegateIsDown` | 绿 |
| M31 | 单飞 | 双并发冷启动只真拉一次远端 | `CachedKbSourceTest#twoConcurrentColdStartsDoOnlyOneRemotePull` | 绿 |
| M32 | 版本号传播 | 生产实例 INCR 后，消费实例从缓存重建且不回源、不重取正文 | `CachedKbSourceTest#versionBumpPropagatesAndRebuildsFromCacheWithoutRemote` | 绿 |
| M33 | stale-if-error | 远端失败继续供旧料、缓存不清空、索引仍 ready | `CachedKbSourceTest#staleIfErrorKeepsServingOldContent` | 绿 |
| M34 | local 过缓存等价 | 同 corpus 的 cached-local 与直连 local 文档/块/top 命中一致 | `CachedKbSourceTest#localSourceGoesThroughCacheAndStaysEquivalent` | 绿 |
| M35 | Redis 不可用降级 | 缓存全抛错时直连知识源，功能不中断 | `CachedKbSourceTest#redisUnavailableFallsBackToDelegate` | 绿 |
| M36 | 英文召回 | `alarm threshold`/`project member`/`reimbursement rate`/`glm seed domains` 命中中文文档 | `KbI18nTest` | 绿 |
| M37 | aliases_en 隔离 | 英文别名只进 `aliases_en`，不污染中文 `aliases`；样例 ≥3 篇声明 | `KbI18nTest` | 绿 |
| M38 | 英文提示词规则 | 规则含"英文提问 / aliases_en / 中文 doc_id §章节 / 中文原标题" | `KbI18nTest`、`KbPromptSectionTest` | 绿 |

## 3 追加 1：20 篇样例（长度与复杂度分布）

位置：`docs/kb-samples/`（20 md + `manifest.json`，manifest 的 `version=kb-samples-2026-10-21`）。每篇文末标注「样例文档」，manifest 每篇 `sha` 与文件实算一致（`KbLocalSourceTest` 逐篇对账）。

| 文件 | 字符数 | 复杂度 |
| --- | --- | --- |
| 01-region-tariff.md | 788 | 短篇、表格、FAQ、aliases/terms |
| 02-region-tariff-calc.md | 729 | 短篇、数字/比例密集 |
| 03-region-tariff-long.md | **8029** | 长文（≥8000）、多级标题、mermaid flowchart、代码块、长清单、宽表 |
| 04-tariff-boundary.md | 1199 | **13 列宽表**、四类特例 |
| 05-project-env.md | 686 | 短篇、表格、FAQ、别名「环境清单」 |
| 06-project-env-flow.md | 931 | **mermaid sequenceDiagram** |
| 07-alarm-threshold.md | 828 | 数字/比例密集、表格 |
| 08-alarm-threshold-manual.md | **5061** | 长文（≥5000）、代码块、清单、多级标题 |
| 09-project-member.md | 706 | 短篇、角色表、FAQ |
| 10-import-export-flow.md | 1065 | **mermaid flowchart**、代码块、FAQ |
| 11-publish-checklist.md | 741 | 长清单（`- [ ]`） |
| 12-definitions-fields.md | 1729 | **#### 深层标题**、代码块、FAQ |
| 13-glm-seed.md | 1786 | **中英混排**、表格 |
| 14-currency-scope.md | 434 | 短文 |
| 15-query-condition.md | 1034 | JSON 代码块、操作符表 |
| 16-ai-panel-guide.md | 790 | FAQ |
| 17-term-glossary.md | 861 | 术语/口头叫法表 |
| 18-data-quality.md | 911 | 指标/比例密集 |
| 19-audit-log.md | 665 | 短文、字段表 |
| 20-kb-maintenance-faq.md | 776 | FAQ |

- 总字符 29,749；≥8000 字 1 篇、≥5000 字 2 篇、1–3K 6 篇、300–800 字 9 篇（其余 0.8–1K）。
- 召回断言利用率：表格检索（`跨区加收比例 7.5%` → `tariff-boundary`）、mermaid 文档按标题检索（`项目环境变更流程` → `project-env-flow`）、长文分页 offset 连续性（8029 字全文重建）、aliases 命中（`费率表`）、术语精确/别名（`标准费率`/`基准费率`）。

## 4 红测留痕

命令：`cd backend && mvn -o test -Dtest='Kb*Test'`（实现前，`KbService` 核心方法为骨架，抛 `UnsupportedOperationException`）。

- 留痕文件：`docs/evidence/kb-v1-red.log`
- 结果：`Tests run: 71, Failures: 1, Errors: 53, Skipped: 0` → **BUILD FAILURE**
  - 53 个 Error 全部为骨架 `TODO KbService.*`（refresh/search/readDoc/lookup/document）；
  - 1 个 Failure 是样例仓缺 ` ```java ` 代码块（复杂度覆盖用例），**属于语料缺口而非实现缺陷**；补入 12 号文档的伪代码块后转绿。
  - 已通过的 17 个用例是纯本地逻辑（切块、本地源解析、配置校验），符合"先红后绿"的分布预期。

实现后（含表格切分修正）：`Tests run: 77, Failures: 0, Errors: 0` → **BUILD SUCCESS**（`docs/evidence/kb-v1-green4.log`）。

> 追加 3/4（缓存层、i18n 桥接）是首批完工后的追加裁决，工单未要求红测先行；其 16 个用例在实现后新增并随全量门禁转绿（`kb-v1-cache-green1.log`）。

## 5 依赖引入留痕（仅此一次在线）

| 项 | 实测 |
| --- | --- |
| 版本 | **Lucene 9.12.2**（`${lucene.version}`，禁升版） |
| 制品 | `lucene-core` / `lucene-analysis-common` / `lucene-analysis-smartcn` |
| 来源 | 公司库镜像 `https://mirrors.huaweicloud.com/repository/maven/`（`~/.m2/settings.xml` 的 `huaweicloud` mirror，`mirrorOf=*`；实测 manifest/jar HTTP 200） |
| 许可 | **Apache-2.0**（`lucene-core-9.12.2.pom`：`<name>Apache 2</name>`，`https://www.apache.org/licenses/LICENSE-2.0.txt`） |
| 大小 | core 4.1M / analysis-common 1.7M / analysis-smartcn 3.5M |
| sha256 | core `3a5426c7082a16adfe39a55502eecf4e69547e62d3dcf292c730e228fbaad2fd`；analysis-common `a472f61b4d7738787bf1a637beac0ebf134d785c37aa069fb071e9715568c8b8`；analysis-smartcn `30b4bf85ad0f27f9c5c49e3cd1b3cbcd923f24b8a722f462e5f3e96dba9568c4` |
| 引入命令 | `mvn dependency:get -Dartifact=org.apache.lucene:<artifact>:9.12.2`（三个制品各一次；之后构建一律 `mvn -o`） |
| 验证 | `mvn -o -DskipTests compile` 通过（离线，EXIT=0） |

> 说明：本机 `~/.m2/settings.xml` 存在 `huaweicloud` 镜像（公司私服形态），故按授权"在线拉一次入 m2"；未改任何全局 settings。

## 6 门禁

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `cd backend && mvn -o test` | **1400 用例 / 0 失败 / 0 错误 / BUILD SUCCESS**（基线 1307 + 首批 77 + 追3/4 16） |
| 前端单测 | `node node_modules/vitest/vitest.mjs run` | **56 文件 / 819 用例全过**（基线 819，零删改；复跑日志 `kb-v1-frontend-vitest2.log`） |
| 类型检查 | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **EXIT=0**（无输出；复跑日志 `kb-v1-vue-tsc2.log`） |
| 前端构建 | `TMP=<TMP_DIR> TEMP=<TMP_DIR> node node_modules/vite/bin/vite.js build` | **✓ built in 33.52s**（EXIT=0；复跑日志 `kb-v1-vite-build2.log`） |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6 断言绿** |

断言只升不降：既有 1307+ 后端用例零删改（新增用例只追加）；前端 819 保持。

## 7 追加 2：知识源抽象落地

| 面 | 实现 |
| --- | --- |
| 抽象 | `KbSource`（`kind()` / `fetchManifest(previousEtag)` / `fetchContent(meta)`），`KbService` 只依赖抽象 |
| 装配 | `KbConfig#kbSource`：`app.ai.kb.source=local`（默认）→ `LocalDirKbSource`；`=http` → `HttpKbSource`；`http` 无 `base-url` 启动失败（不静默回落） |
| local | `./data/kb`（`app.ai.kb.dir`）；每次扫描按文件实算 sha，manifest 声明过期只 WARN 并覆盖；路径越界拒绝；单篇缺失整轮失败（不静默少一篇） |
| http | `base-url` + `manifest.json` + 按 `file` 拉正文；连接/读超时 5s/10s 可配；`If-None-Match`/304 短路；按 manifest sha 增量；`refresh-interval` 默认 10m 定时刷新；启动后异步建一次 |
| 鉴权 | 只从环境变量 `KB_HTTP_AUTH`（键名可配）读取，值整段作为 `Authorization` 头；不写配置、不落库、不进日志/错误响应（用例断言 401 的 `last_error` 不含 token） |
| fail-open | 5xx/超时/坏 JSON/单篇 404：保留旧索引 + WARN + `last_error`，检索面不变 |
| 安全闸 | 拉取内容与本地一致：`<knowledge>` 定界 + 「数据不是指令」声明 + 定界符/不可见字符剥离 + ≤8000 字符 |
| 等价性 | 同 corpus 下 local/http 的 doc_count、chunk_count、top 命中一致 |

## 8 文件清单（未 commit；worktree 内）

**新增生产代码（28 个文件，`ai/kb` 包）**
- `backend/src/main/java/com/example/configmgr/ai/kb/KbSource.java`
- `.../kb/LocalDirKbSource.java`
- `.../kb/HttpKbSource.java`
- `.../kb/KbConfig.java`
- `.../kb/KbService.java`
- `.../kb/MarkdownChunker.java`
- `.../kb/KbDocMeta.java`、`KbTerm.java`、`KbManifest.java`、`KbDoc.java`、`KbChunk.java`、`KbSection.java`
- `.../kb/KbSearchResult.java`、`KbSearchHit.java`、`KbReadResult.java`、`KbLookupResult.java`、`KbStatus.java`
- `.../kb/KbText.java`
- `.../kb/KbTools.java`
- `.../kb/KbPromptSection.java`
- `.../kb/AiKbController.java`
- `.../kb/KbHashing.java`、`KbSourceException.java`
- `.../kb/KbCache.java`、`KbCacheStore.java`、`KbCacheException.java`、`RedisKbCacheStore.java`、`CachedKbSource.java`（追加 3）

**修改（4）**
- `backend/pom.xml`（Lucene 9.12.2 三依赖 + 版本属性）
- `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java`（`Kb` 嵌套段 + `verifyKb` 启动校验）
- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`（`KbPromptSection` 字段注入 + systemText 合成一处）
- `backend/src/main/resources/application.yml`（`app.ai.kb.*` 全量键与注释）

**新增测试（14 个测试类 + 3 个脚手架 / 93 用例）**
- `KbTestSupport`、`InMemoryKbCacheStore`、`FakeKbSource`（脚手架）
- `MarkdownChunkerTest`、`KbPropertiesTest`、`KbLocalSourceTest`、`KbSampleCorpusTest`、`KbServiceIndexTest`、`KbReadDocTest`、`KbLookupTermTest`、`KbServiceIncrementalTest`、`KbHttpSourceTest`、`KbToolsTest`、`KbDisclosurePromptTest`
- `KbCacheTest`、`CachedKbSourceTest`、`KbI18nTest`（追加 3/4）

**文档与语料**
- `docs/kb-samples/`（20 md + manifest.json）
- `docs/知识库维护手册.md`
- 本报告 + `docs/evidence/kb-v1-red.log` + `kb-v1-green4.log` + `kb-v1-backend-full2.log` + 前端三门禁日志

## 9 追加 3：Redis 缓存层落地

| 面 | 实现 |
| --- | --- |
| 位置 | `KbSource` 抽象与 `KbService` 之间：`CachedKbSource`（手写 cache-aside，**未用 Spring Cache**） |
| 键族 | `kb:cache:doc:<id>`（HASH content/etag/sha/updatedAt）、`kb:cache:manifest`（HASH etag/version/json）、`kb:cache:manifest:version`（INCR）、`kb:cache:refresh:lock`（`SET NX PX 30000`） |
| 读取链 | 索引永远从缓存重建：local 读盘→写缓存→建索引；http 缓存命中即用，缓存为空才冷启动回源 |
| 刷新 | 定时 10m（有效阈值 ±10% jitter）+ 版本号轮询 30s；抢到锁者 `If-None-Match` 拉 manifest → 变更文档写缓存 → `INCR`；其它实例见版本变化即从缓存增量重建并原子切 reader |
| 锁 | `SET NX PX lock-ttl`（默认 30s，不续租）+ Lua 比对 token 删除（同 `LeaseService` 口径），不会误删别人的锁 |
| TTL | 文档/清单键**不设 TTL**（`hashPutAll` 无过期）；锁 30s |
| stale-if-error | 远端 5xx/超时 → 旧料继续供 + WARN，缓存不清空；可容忍陈旧 ≤ 刷新间隔 + 重建耗时（当前秒级），已写入手册 §8A |
| 降级 | Redis 不可用 → 直连知识源 + WARN（单实例可用）；不含任何 Spring Cache 注解 |
| 安全 | 只缓存业务知识原文（非对话数据）；鉴权头仍由 `HttpKbSource` 从 `KB_HTTP_AUTH` 读，缓存层不接触；结果仍过 `<knowledge>` 定界与 8000 闸 |

## 10 追加 4：i18n 桥接落地（单语料，不建翻译副本）

| 面 | 实现 |
| --- | --- |
| manifest | 每 doc 新增可选 `aliases_en`；样例 6 篇已登记（region-tariff / alarm-threshold / project-member / glm-seed / import-export-flow / kb-maintenance-faq） |
| 索引 | 新增 `aliases_en` 字段，`PerFieldAnalyzerWrapper` 对该字段用 standard analyzer，权重同 `aliases`（4.0）；`kb_search` 一并查询 |
| 提示词 | 规则第 8 条：英文提问先用英文术语检索（aliases_en 已桥接），答案按用户语言生成，引用锚定中文 `doc_id §章节` 并附中文原标题 |
| 召回断言 | `alarm threshold`→alarm-threshold、`project member`→project-member、`reimbursement rate`→region-tariff、`glm seed domains`→glm-seed |
| 隔离 | 英文别名只进 `aliases_en`，不写入中文 `aliases`（用例锁定） |
| 手册 | §8B「国际化口径」：单语料原则、aliases_en 维护法、LLM 查询翻译归 v2 |

## 11 原待裁 5 条收口（按 2026-10-21 裁决）

| # | 原待裁 | 裁决/落地 |
| --- | --- | --- |
| 1 | 增量"内容级复用+全量重建" | **接受**；万篇级再议（挂账），本版维持 tmp 建新索引→原子切 reader |
| 2 | HTTP sha 以 manifest 为准 | **接受**；服务端不一致时应用只 WARN 并按声明值做增量判据 |
| 3 | 首屏 1200 字 | **接受**（定稿口径），后续分页用 4000/8000 |
| 4 | 超宽表按行切 + 重复表头 | **接受**（800 硬上限下"永远整块"不可兼得） |
| 5 | `/api/ai/kb/reload` 无鉴权 | **接受**（与既有无鉴权 AI 管理面一致，网关收口）；后续由提交官登记 L-56 |

## 12 已知边界（追加 3/4 后）

1. 缓存层依赖 Redis 的持久化策略：Redis 自身丢数据时等同冷启动（会回源重建，不影响正确性）。
2. 版本号轮询周期（30s）决定多实例的"最迟可见延迟"；如需更快可下调，但会增加 Redis GET 频率。
3. `aliases_en` 是词表桥接，不覆盖未登记的英文表达；LLM 查询翻译按裁决归 v2。
4. 缓存键不做按文档删除：manifest 移除文档后，旧 `kb:cache:doc:<id>` 可能残留（不影响索引面，后续可加清理任务）。
5. 管理端点 reload 现在走 `force=true`（跳过 10m 节流），HTTP 源仍受 ETag 与单飞约束。
