<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-267 · 过程回执 · **自证**：`agent-1418` · `DeepSeek/deepseek-flash` · profile `coder`（wire `profile.bind` 单行实测；turn.prompt 首句与本卡一致）。分支 `feat（ced79706 / agent-1418）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`check-docs`、`backend/src/main/java`、`ResilientChatService.java`。
> **稿态**：历史中间稿 —— 该 wire 第 `m223` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt15` 条有正文的 assistant 消息（共 18 条有正文），末条有正文消息为 `m270`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `ced79706` / agent `agent-1418`（`main` = 主会话）；原字符 2,479（链内 1 段、合计 2,479）。
> **链号 / 类别**：`MT-267` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 5 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `0%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m270`（1,938 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

**自证**：`agent-1418` · `DeepSeek/deepseek-flash` · profile `coder`（wire `profile.bind` 单行实测；turn.prompt 首句与本卡一致）。分支 `feature/kb-v1`（worktree `../wt-kb-v1` @74aaf96），**未 commit/push**。

**范围逐项**
- 载体：`manifest.json`（id/title/tags/aliases/sha/updated_at/summary/terms）+ `*.md`；local 源逐篇实算 sha 对账，原子写入与 sha 校验流程写入手册 §3.2。
- 索引：`KbService` 启动异步建索引；smartcn + BM25；`##` 切块（300–800，路径前缀，标题 3/章节 2/别名 4 加权）；FSDirectory；sha 增量；reload 在 `build-*` 建新索引后原子切 reader（引用计数延迟关闭）。
- 工具：`kb_search`（top_k 5/10 强截、三态、match 三档、无原始分）、`kb_read_doc`（首屏目录+1200 字、默认 4000/上限 8000、`truncated/next_offset`、候选不抛）、`kb_lookup_term`（NFKC 归一 + 精确/别名、未命中 `next=use kb_search`、零模糊）。
- 提示词：`SYSTEM_PROMPT` 原文零改动，追加目录 + 七条规则；未就绪只注入规则不注入空目录。
- 安全：`<knowledge>` 定界 + 「数据非指令」声明 + 定界符/零宽字符剥离 + 8000 字符闸，覆盖 search/read/lookup 与提示词目录。
- 手册：`docs/知识库维护手册.md`（含生产 HTTP 源接入）。

**追加 1 落地**：`docs/kb-samples/` 20 篇（总 29,749 字）：8029 字 1 篇、5061 字 1 篇、1–3K 6 篇、300–800 字 9 篇；覆盖 13 列宽表、mermaid flowchart×2 + sequenceDiagram、JSON/Java 代码块、`####` 深层标题、`- [ ]` 长清单、FAQ、中英混排、数字密集、aliases/terms；每篇标注样例，manifest sha 与文件逐篇一致；测试直接以该目录建索引（表格检索/mermaid 标题/长文分页/别名召回）。

**追加 2 落地**：`KbSource` 抽象 + `LocalDirKbSource`/`HttpKbSource`，`app.ai.kb.source=local|http`；HTTP 连接/读超时 5s/10s、`If-None-Match`/304 短路、按 sha 增量、10m 定时刷新、`KB_HTTP_AUTH` 环境变量鉴权（不进配置/日志/错误面）、5xx/超时/坏 JSON fail-open 保留旧索引；local/http 同 corpus 的 doc/chunk/top 命中一致（用例锁定）。

**依赖引入**：Lucene **9.12.2**（core / analysis-common / analysis-smartcn），公司库镜像 `mirrors.huaweicloud.com` 可达，按授权在线 `dependency:get` 一次入 m2；Apache-2.0；sha256 `3a5426c7…` / `a472f61b…` / `30b4bf85…`；之后 `mvn -o` 编译通过。

**门禁**
- 后端 `mvn -o test`：**1384 / 0失败 / 0错误 / BUILD SUCCESS**（基线 1307 + 本批 77）。
- 前端 vitest：**819 / 819**（56 文件）；`vue-tsc --noEmit` EXIT=0；vite build ✓（49.95s）。
- `check-docs`：**6/6 绿**（含已入索引的新文件，git ls-files 1703）。
- 红测留痕：`docs/evidence/kb-v1-red.log`（71 用例 / 1 失败 / 53 错误，全指向骨架 TODO）。

**文件**：新增 `backend/src/main/java/com/example/configmgr/ai/kb/**`（23 文件）、测试 11 类 + 脚手架、`docs/kb-samples/`、手册、报告与 6 份门禁日志；修改 `pom.xml`、`AiProperties.java`、`ResilientChatService.java`、`application.yml`。68 个路径已**显式 `git add`**（64 A / 4 M，无未暂存改动；看板与 `.gitignore` 未动）。

**待裁**
1. 增量是"内容级复用 + 全量重建索引"，万篇级需改段级 `updateDocument`/`SearcherManager`。
2. HTTP 源 sha 以 manifest 为准；服务端需保证一致（不一致只 WARN）。
3. 首屏 1200 字为定稿口径；若要首屏给满 4000 需改契约。
4. 超宽表在 800 硬上限下按行切分且**每块重复表头**（"永远整块"不可兼得，已选可检索）。
5. `/api/ai/kb/reload` 未加鉴权（与既有无鉴权 AI 管理面一致），生产由网关收口。

完整报告：`docs/evidence/施工报告-知识问答v1-DS-Flash.md`（DC-19 首页、27 项矩阵、红测、依赖、门禁、文件清单）。
