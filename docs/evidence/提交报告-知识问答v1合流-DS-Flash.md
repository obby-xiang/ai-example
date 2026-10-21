# 提交报告 · 业务知识问答 v1 合流（提交官 · DS-Flash · 2026-10-21）

## 0 自证行（DC-19 · wire 实测）

- 提交官 agentId = `agent-1425`；wire = `<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_ced79706-4f65-44a8-a80c-eb2eb2542928/agents/agent-1425/wire.jsonl`
- 取数方式：`ls -lat` 该 session 的 `agents/` 目录，最新新建目录 = `agent-1425`（23:26）；`grep` 其 wire 的 `profile.bind` 单行，原文首部（逐字）：

```json
{"type":"profile.bind","agentId":"agent-1425","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",...}
```

- 实测三字段：`modelAlias` = **`DeepSeek/deepseek-flash`**、`profileName` = **`coder`**、`thinkingEffort` = **`high`**
- 身份核对：同目录 wire 的 `turn.prompt` 首句 = 「你是提交官，执行「业务知识问答 v1（Lucene 内嵌 + agentic 检索 + Redis 缓存 + i18n 桥接）」合流 main 全流程。」—— 与本卡一致。
- 授权 = 用户 2026-10-21「那就按Lucene方案来」+ 追加 1–4 裁决；施工官门禁已绿（分支面后端 1400 / 前端 819 / tsc 0 / build ✓ / check-docs 6/6）。

## 1 合流对象与 sha

| 项 | 值 |
| --- | --- |
| 主仓开基 | `c84b534`（= `origin/main`，超时批② 已合流） |
| 施工分支 | `feature/kb-v1` @ `6ed9ee2`，基点 `74aaf96` |
| 分支提交 | `6ed9ee2 feat(kb): 知识问答v1 Lucene内嵌BM25+三工具+Redis缓存+i18n桥接+20篇样例`（83 路径，+34774 / −1） |
| 合并笔 | `72fc809 merge: 业务知识问答v1合流`（`--no-ff`，Merge: `c84b534` `6ed9ee2`） |
| 合流 diff | 83 文件，+34774 / −1（与分支清单逐值一致） |

**清单核对**：`git status --porcelain` 83 路径 / `git diff --cached --stat` 83 文件，与施工报告 §8 逐项对应（28 生产 `ai/kb` + 4 修改 + 17 测试 + 20 篇样例 + `manifest.json` + 维护手册 + 施工报告 + 11 份门禁日志），**无多出、无缺件**。

**冲突处置**：预判三处（`ResilientChatService` / `AiProperties` / `application.yml`）**均未触发文本冲突**，git 自动合；逐面复核语义共存：

- `ResilientChatService`：KB 的 `KbPromptSection` 字段注入（:208）与 `systemText` 合成（:255）在批② 段预算/看门狗代码之外，互不引用；
- `AiProperties`：`Kb` 嵌套段（:880）与批②/防护的 `Lock`(:540)/`Lock`(:710)/`Input`(:753)/`Limit`(:776) 四段并列，字段名零交集；
- `application.yml`：`app.ai.kb.*`（:245 起）与 `app.ai.resilience.total-budget`(:209)、`app.ai.tool.exec-timeout` 各自成段。

语义冲突 **0**。

## 2 提交前脱敏处置（DC-08 · 必读）

分支提交首跑被仓库 pre-commit 钩子阻断（暂存面 **219 处**），命中全部为**施工日志里的本机路径与用户名**（`mvn` 输出），不含任何密钥。处置：

| 动作 | 范围 |
| --- | --- |
| 占位符替换 | 工作树绝对路径 → `<WORKTREE>`；构建临时目录 → `<TMP_DIR>`；其它盘符路径 → `<LOCAL_PATH>`；用户主目录 → `<USER_HOME>`；个人用户名 → `<USER>` |
| 文件面 | 10 份 `docs/evidence/kb-v1-*.log` + `docs/evidence/施工报告-知识问答v1-DS-Flash.md`（§7 临时目录路径 1 处） |
| 提交修正 | `git commit --amend`（分支笔仍为 83 路径；首轮 2 份前端日志漏暂存，第二轮补齐后 amend） |

修正后复跑钩子同一引擎（`scripts/githooks/scan-lib.sh`）扫分支提交全量 diff = **0 命中**；`git status` 净。日志内容仅路径/用户名替换，用例结果行逐字保留。

## 3 门禁（合流后主仓 · 提交官复跑）

| 面 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `mvn -o test` | **1417 / 1417 · 216 个 `TEST-*.xml` · 0F/0E/0S**，`BUILD SUCCESS`（2:37） |
| 后端求和校验 | surefire XML 逐文件累加 | tests **1417** / failures 0 / errors 0 / skipped 0，与 Maven 汇总行**逐值一致** |
| 既有 flake | `ResumeServiceLeaseTest` | **11/11**，本次无 flake（未触发单例重跑） |
| 前端 | `vitest run` | **822 / 822 · 56 文件**，`exit 0` |
| 类型 | `vue-tsc --noEmit` | **0** |
| 构建 | `vite build`（`TMP/TEMP` 指向临时目录） | ✓ `built in 31.99s` |
| 文档 | `node scripts/ci/check-docs.mjs` | **6/6**（命名纪律实扫 1722 / 1725，豁免 3） |
| 脱敏四面独立正则 | 合流 diff 加行 + 新增件全文 + 提交官笔新增行 + 暂存面 | **0 命中**（独立正则，不复用钩子规则：盘符路径 / Git Bash 挂载路径 / 用户名 / 密钥·令牌·私钥头）；变更件全文仅 1 处**既有**正对照样例（`docs/任务看板.md` 上下文统计行内的盘符省略写法，基点 `c84b534` 已存在、非本批引入，未改） |

**数字对账**：后端 1417 = 主仓基线 **1324** + KB 测试 **93**（施工分支面 1400 = 基点 1307 + 93；卡片预估「1400 上下 / +76」偏低，实测增量为 93）；前端 822 = 主仓基线 **819** + 超时批② 合流后新增 **3**（施工分支面 819，本批零前端改动）。两口径均 ≥ 预期且 0F/0E。

## 4 依赖引入留痕复核（Lucene）

|  artifact | 版本 | license | sha256 | sha1（`.sha1` 文件） |
| --- | --- | --- | --- | --- |
| `lucene-core` | 9.12.2 | Apache-2.0（jar 内 `META-INF/LICENSE.txt`） | `3a5426c7082a16adfe39a55502eecf4e69547e62d3dcf292c730e228fbaad2fd` | `1369d425657294be298a7e128a562f5acd0a1dca` |
| `lucene-analysis-common` | 9.12.2 | Apache-2.0（同上） | `a472f61b4d7738787bf1a637beac0ebf134d785c37aa069fb071e9715568c8b8` | `cdc24b721435268fb7e9344c05f70466674ba5b6` |
| `lucene-analysis-smartcn` | 9.12.2 | Apache-2.0（同上） | `30b4bf85ad0f27f9c5c49e3cd1b3cbcd923f24b8a722f462e5f3e96dba9568c4` | `d7c7b9b2e9d21e791dc4cd19fece2abdf4c94b2d` |

- pom 变更仅新增 `lucene.version=9.12.2` 属性 + 上述三依赖（注释标明「公司库版本，禁升版」）；无其它新依赖（Redis 复用既有 starter）。
- 构建全程 `mvn -o` 通过 ⇒ 三 jar 已在本地库，离线可复现；`9.12.2` 与公司库版本一致（未见版本漂移）。
- `check-docs` 断言② 技术栈版本一致：**PASS**。

## 5 待裁处置（按 2026-10-21 裁决）

| 原待裁（施工报告 §11） | 处置 |
| --- | --- |
| ① 增量「内容级复用 + 全量重建」 | 接受；万篇级再议 → 挂账 **L-58** |
| ② HTTP sha 以 manifest 为准 | 接受（不一致只 WARN，按声明值判增量） |
| ③ read_doc 首屏 1200 字 | 接受（定稿口径；分页 4000/8000） |
| ④ 超宽表按行切 + 重复表头 | 接受（800 硬上限下不可兼得） |
| ⑤ `/api/ai/kb/reload` 无鉴权 | 接受（与既有 AI 管理面一致，生产网关收口）→ 挂账 **L-59** |
| 施工报告拟登记号 | **更正**：施工报告 §11 写「登记 L-56」，但 L-56 已被超时批② 占用 ⇒ 本批按现序号登记 **L-59** |

新增挂账 **L-58 / L-59 / L-60**（明细见 `docs/任务看板.md` 挂账小节；LLM 查询翻译 CLIR 归 v2 = **L-60**）。

## 6 看板与台账落点

- `docs/任务看板.md`：10-21 组首行新增「批·业务知识问答 v1（Lucene 内嵌 BM25 + agentic 检索）」→ ✅；挂账小节新增 L-58/L-59/L-60 三条单列（118 → **121**）；速览行同步（批次 69 → **70** / ✅55 → **56** / 📋9 不变）。
- `docs/决策看板.md`：10-21 组追加「知识问答技术路线裁决」1 条（总条数 125 → **126**）。
- 仓外服务台账 `services.state`（本机共享目录，不入库）：追加本批一行。

## 7 文件清单（本合流笔）

**分支提交 `6ed9ee2`（83 路径）**：生产 28（`backend/src/main/java/com/example/configmgr/ai/kb/*`）+ 修改 4（`pom.xml` / `AiProperties.java` / `ResilientChatService.java` / `application.yml`）+ 测试 17（14 测试类 + 3 脚手架）+ `docs/kb-samples/`（20 md + `manifest.json`）+ `docs/知识库维护手册.md` + 施工报告 1 + 门禁日志 11。

**本提交（提交官笔）**：
- `docs/evidence/提交报告-知识问答v1合流-DS-Flash.md`（本文件）
- `docs/任务看板.md`（专项行 + 挂账 3 条 + 速览）
- `docs/决策看板.md`（技术路线裁决 1 条）

## 8 净态

合流后门禁全绿、脱敏 0 命中；收尾执行 `git worktree remove ../wt-kb-v1` + `git branch -d feature/kb-v1`，主仓 `git status --porcelain` 应仅剩 `?? data/`，HEAD = `origin/main`。
