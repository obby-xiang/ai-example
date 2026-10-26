<!-- 历史分析 · 过程证据 · 非现行基线 · 归档批次 history-recovery-2026-10-26 · 审计源稿 9 件之 8 -->
# 审计源稿 239 · 非 Markdown 成果审计（demo / 数据 / 网页原件 / 非标题末条，2026-10-26）

> **性质**：**历史分析 / 过程证据 — 非现行基线，非验收结论**。正文是 2026-10-26 一次“除 Markdown 报告之外还有没有漏项”的只读审计；其中的落点去重数、排除计数与覆盖率都是**当时值**。
> **来源**：`<KIMI_SESSIONS>/<WORKSPACE>/session_<KIMI_SESSION_A>/agents/main/tasks/agent-hu2wnf9m/output.log` —— 派发层产物，即该子 agent（`agent-239`）的**最终结论全文**；与同 agent 的 wire 末条 assistant 文本**逐字相同**，本次已实测（9,227 字符、sha256 前 16 位 `545803627ebbf8d1` 两边一致）。
> **抽取口径**：只取该件全文；**不含** `think`、工具调用与回显、用户消息、原始 `wire.jsonl` 副本。正文**未删段、未改写结论**，仅做占位符替换。
> **源体量**：9,227 字符 / 146 行；本件脱敏替换 **6 处**（仓外盘符根 5 → `<LOCAL_WORK>`；Git Bash 挂载形 1 → `/<DRIVE>/temp`，与盘符形态（`<DRIVE>:/…`）**并列保留**，以维持“三种路径写法都扫过”的证据语义）。
> **用途**：**`wire-tail/` 那一面的发现源**。三点价值：① 在“非 Markdown 正式成果”上给出**否定性结论**（demo 13 对逐字节/逐行等价、正式调研与裁决数据未发现漏项、`docs/` 无 orphan），从而把唯一系统性缺口定位到“**首行不以 `#` 开头的 wire 末条终稿**”；② 给出被“首行必须是标题”这条机械筛选筛掉的 267 件 / 项目相关 215 件 / 覆盖率 <60% 198 件的**三级漏斗**；③ 给出“候选不能仅凭存在于 wire 就判定应入库”的边界（5,910 个落点已不存在、Edit 改写、二进制、密钥面未读）。
> **纠正 / 关联关系**：① **198 是候选，不是缺口清单**——本件自己已写明“其余未逐件判定，不等于 198 件都是缺口”；逐件甄别见 [`241-非标题开头项目报告甄别-215到183.md`](241-非标题开头项目报告甄别-215到183.md)（215 → 183），实际归档见 [`../wire-tail/README.md`](../wire-tail/README.md)（**188 件**，含 5 件边界件经实际 diff 后改判入库）。**198 → 183 → 188 是本批保留的口径差，不强行对齐**。② 本件“上一批 `wire-only/` 138 件”是**成文时点值**：该批现在库 **135 件**（3 件因属非项目会话在收口复核中撤档，见 `wire-only/README.md` 页首订正条与父索引 §15.5），与审计 233 的“100 份候选”属**两条不同漏斗**，差异原因见父索引 §14.1 / §15.4。③ 本件 §4.2 的 4 条低优先候选（`R154` 复核锚补丁等）**至今未执行**，仍是建议、不是欠账已清。
> **脱敏登记**：路径 6（仓外盘符根 5 → `<LOCAL_WORK>`；挂载形 1 → `/<DRIVE>/temp`）· 会话标识 —— **8 位十六进制短代号与 4 位会话前缀原样保留**（不可反推身份；且是“源 → 会话”映射索引）· 身份 0 · 密钥 0 —— 正文只出现 `.env` / `token` / 私钥文件的**文件名类别**，并明确“未读取、未评估内容”，**无任何密钥值**。
> **不含**：原始 wire、原始对话、用户消息原文、被跳过未读的密钥面文件内容、任何密钥或凭证片段。

---

## 结论先说

在「非 Markdown 正式成果」这一面，**演示原型、正式调研、裁决与证据数据都未发现确定漏入库的正式件**；唯一系统性缺口是另一类：**只存在于子 agent wire 里、首行不以 `#` 开头的终稿**——上一批 wire-only 抽取用「首行必须是 Markdown 标题」做机械筛选，正好把它们整批漏掉了（项目相关 215 件，其中 198 件对仓内语料覆盖率 <60%）。这一面我只能完成定性和定量筛选，**不能仅凭「存在于 wire」就判定应入库**，详见第五节。

本轮全部只读：未改文件、未 commit、未起停服务、未再委派；未读取也不输出任何 `.env`、凭据、token、SSH 私钥（`/tmp/tok.json`、`_p2-review/runtime/model.env`、`rustdesk-deploy/.frp-token`、`handoff-archive/交接提示词-2026-10-13-当前态.md` 等均按前例跳过）。

---

## 一、扫描口径与计数（可复核）

| 项 | 实测 |
|---|---|
| wire 文件 | **2,256** 个（`agents/main` 200 + 子 agent 2,056），来自 **200** 个 session 目录（含 `m1-verify-glm`） |
| 工具调用 | **213,002** 次；其中 Write 6,442 / Edit 23,616 / Bash 127,041 / Agent 2,950 / WebSearch+FetchURL 4,879 |
| 文件落点去重 | **12,629** 个唯一目标（Write/Edit 的 `path` + Bash 的 `>`/`>>`/`tee` 目标） |
| 剔除无关项目 | 1,377（LocalScreenShareAndroid、RustDesk、Guacamole、Subnautica、xray、`ai-example-kimi*`/`-glm-5.3`/`-claude`/`-deepseek`/`-trae` 等分支仓） |
| 项目域落点 | **11,248** |
| 本会话 | 已排除 `session_c5a94a62-…`（本次新施工） |

项目域 11,248 个落点的分类（去重，不按调用次数）：

| 类别 | 件数 | 处置 |
|---|---:|---|
| 源码 / 样式（.java/.ts/.vue/.mjs/.py/.sh/.css/.xml…） | 3,768 | 排除（代码，非成果件） |
| 运行日志（.log/.out/.err） | 3,423 | 排除 |
| Markdown | 1,128 | 属前序审计面，不重复 |
| 数据 / 文本（.txt/.json/.tsv/.svg） | 1,257 | 逐类核查，见第四节 |
| 临时目录（`/tmp`、`_scratch`） | 1,030 | 排除 |
| diff / patch | 380 | 排除（除既有「复核锚」体例例外） |
| 截图 .png | 142 | 排除（原始截图） |
| 配置 / 部署（.yml/.yaml/.ini/.conf） | 65 | 排除 |
| HTML | 50 | 全部核查，见第三节 |
| 依赖 / 构建产物 | 5 | 排除 |

`.csv` 全库 **0 个落点**；`.yaml/.yml/.xml` 全部是代码/配置（`application*.yml`、`ci.yml`、`tools.yaml`、`pom.xml`、Android 资源），**没有一份 yaml 形态的调研/设计/裁决文档**。

---

## 二、演示原型（demo 类）：无实质遗漏，且有逐字节等价证据

`docs/demo/README.md` 记载「用户直裁 2026-10-14：近期 demo 全部合入，后续 demo 亦必合入」，并按「定稿进主目录、过程稿进 `archive/`」落位。我按仓外源路径做了逐对比对（`diff` 行数）：

| 仓外源件 | 仓内件 | 差异行 |
|---|---|---:|
| `r163-demo/index.html` | `chat-ux/r163-chat-ui-v6.html` | 4（脱敏+页首） |
| `r163-demo/v7.3.html` | `archive/r165-chat-ui-v7.3.html` | 6 |
| `r163-demo/v7.9.html` | `chat-ux/r165-chat-ui-v7.9.html` | 4 |
| `r163-demo/v7.12.html` | `archive/r165-chat-ui-v7.12.html` | 6 |
| `r163-demo/hitl-readonly.html` | `chat-ux/hitl-readonly.html` | **0** |
| `_cu-hitl-final/old-demo-bug/hitl-interrupts.html` | `chat-ux/hitl-interrupts.html` | 2 |
| `r159-stream/demo-issue13.html` | `archive/r159-stream-issue13-v1.html` | **0** |
| `r160-hitl/demo-issue14.html` | `hitl/r160-hitl-issue14.html` | **0** |
| `r161-scroll/demo-scroll.html` | `scroll/r161-scroll-demo.html` | **0** |
| `r157-ux2/指示器预览.html` | `chat-ux/r157-generating-indicator.html` | **0** |
| `_cu-hitl-icon-r26-e/page.html` | `chat-ux/hitl-icon-candidates.html` | **0** |
| `_cu-composer-decisions-r26/v2/page-v2.html` | `chat-ux/composer-decisions.html` | 20（pending→approved） |
| `r163-demo/INDEX.md` | `r163-demo-INDEX.md` | 2 |

另外核过：`docs/demo` 现有 14 个 `.html/.css` **全部**能在 `docs/demo/README.md` 检索到（无一漏索引）；v1–v12 版本链、`batch-download` 空目录（R-156 无原型）均在 README 有依据行。

**未入库的只有中间稿**，且 10-19 文档普查 JSON 已把它们判为 `stale_do_not_import`（`r163-demo/_hitl5/audit-hitl5.md`、`_v712/audit-v712.md`、`_hitl/audit-hitl.md` 等 4 件，理由「pipeline-audit，对应版本已被后续版本取代」）。同类还有：

- `<LOCAL_WORK>/_cu-composer-decisions-r26/page.html`（39,137 B，v1 待裁决）与 `followup/page-new.html`（40,108 B，「三勾位新裁决 demo 待裁决」）→ 结论已被仓内 v2 批准稿覆盖；
- `<LOCAL_WORK>/r163-demo/hitl-interrupts.html`（89,930 B，`_hitl` 管线 10-16 稿，与仓内终稿 diff 1,382 行）与 `_cu-hitl-final/old-demo/hitl-interrupts.html`（112,124 B）→ 终稿已入库；
- `_v73-adj.html` / `_v73-tail.html` / `_v75/adj-*` / `_v77/src-*` / `_v79/v7.9.before-vote.html` → 拼装碎片。

**判定**：不构成「demo 漏入 docs/demo」，不建议补入；若用户坚持「中间版本一律 archive」，上表 3 组是有名词条，但会与 10-19 普查的排除裁定冲突。

---

## 三、正式调研 / 设计 / 裁决数据：未发现漏项

- `docs/research/` **无任何 .json/.yaml/.csv**；业界 raw 材料已是 `.md`（`raw-ai-sdk.md`、`raw-ag-ui.md`、`raw-copilotkit.md`、各 `*-raw-2026-10-10.md`）。仓外 `ag-ui-intro.html`、`n8n.html`、`lc.html`、`cg.html`、`w.html`、`official-console.html` 是**原始网页快照**（其中 `official-console.html` 是 Subnautica wiki，纯属用户私人抓取），按「原始截图/网页原始产物不入库」排除。
- 仓外「机器可读验收数据」确实存在（`_cu-composer-decisions-r26/acceptance.json` 11,939 B、`measurements.json` 24,198 B、`tip-narrow.json`；`_cu-verify-2a/report.json`；`_cu-card-project/verification/report.json`；`_cu-collapse-fix/verification/report.json`；`_cu-hitl-live/acceptance-v2.json` 等约 260 件），但每件都有对应的 **md 结论件已入库**（`补档-问题4-composer-*`、`补档-2A-*`、`补档-卡片通道-*`、`补档-HITL-G回落-r26回归.md`、`补档-R15-折叠终态-*`、`hitl-live-五场景实机验收-2026-10-20.md`）。它们属「过程/机读副本」，与 10-25/10-26 两批 evidence 目录「101 + 31 件全为 md、raw 不入」的口径一致。
- 证据索引类：`补档-文档普查-附件-最终对账.json`（150,843 B，44 候选 / 23 入库 / 21 排除）、`补档-索引-2026-10-11.md`、`归档清单-仓外文档补档-2026-10-19.md` 均已在仓；其所依赖的 `_cu-docs-census/external-candidates.json`（545,809 B）、`archive-manifest.json`（133,307 B）、`_tracked.tsv`、`_session_writes.txt` 是**生成中间数据**，非另一份成果。
- 仓内 `docs/` 现有非 md 正式件：`35 html`（demo）、`254 json`（其中 226 为 `spike/logs/` 抓包，其余为 evidence 附件与 `kb-samples/manifest.json`、`scripts/ci/*.json`）、`92 txt`（evidence 附件）、`3 patch + 2 diff`（复核锚）、`15 css`。**没有 orphan**（写盘后又被删的只有 `frontend/__issue{1..5}_probe.html` 5 个探针，属调试件）。

---

## 四、新增候选（按优先级）

### 4.1 唯一系统性缺口：wire-only「非标题开头」终稿

上一批 `wire-only/` 口径是「末条 assistant 文本 ≥8KB **且首行是 `#` 标题**」。实测被这条筛掉的大文本有 **267 件**，其中项目相关 **215 件**；对仓内语料（6894 个 md/txt/json/html）行指纹覆盖率：**>=85% 11 件（可判重）、60–85% 6 件、<60% 198 件**，≥16KB 的 83 件、≥32KB 的 26 件。抽样读原文，它们多数是「核查完毕。以下为…」+ 正文内自带 `##` 小节的**正式报告**，只是标题行不是第一行。

| 源（wire 位置） | 字节 | 首行 / 主题 | 覆盖率 | 性质判断 |
|---|---:|---|---:|---|
| `session_863d25b9…/agent-672` | 61,471 | 「所有文件均存在，核心 diff 哈希与字节数完全吻合」 | 11% | 施工件逐字节核验，**过程核验** |
| `session_5d3ad2ec…/agent-303` | 59,014 | 「7 文件通读，客观声明清单（按来源分组）」 | 0% | 红队/会审取证，**待逐件比** |
| `session_79d80c9e…/agent-359` | 38,828 | 全仓 `hitl.timeout` 等检索报告（HEAD 基线） | 0% | **正式事实采集**，疑似真缺口 |
| `session_9e750f99…/agent-737` | 34,756 | 「核查完毕。以下为结构化摘要」 | 0% | 结构化核验 |
| `session_26cac31b…/agent-137` | 24,633 | M2-T3 韧性机制包设计卡事实采集（[实测]/[推断] 分级） | 0% | **设计卡素材**，仓内有 `M2-T3-设计卡.md`，需比 |
| `session_484c4656…/agent-52` | 24,211 | CopilotKit 源码级挖掘 9 题（含 runId 语义） | 0% | 仓内有 `调研-CopilotKit-HITL实现-2026-10-17.md`，需比 |
| `session_7f217e6f…/agent-489` | 33,314 | 工作树逐条实读核验（HEAD=3edaeb9） | 1% | 过程核验 |
| `session_0c4dbfa1…/agent-87` | 35,721 | 设计稿逐项对源码核验 | 15% | 过程核验 |
| `session_604b3bc4…/agent-1311` | 37,502 | 批①设施三节方案 | 3% | 方案稿（部分改写自设计件） |
| `session_5d1f4b7b…/agent-5` | 44,042 | 8 份文档通读 + 关键词核查提炼 | 1% | 属「五分支评测」，对应 `docs/merge/01-分支评价报告.md` 口径，**倾向不入** |

**注意三点**：①覆盖率只对仓内语料算，因此低覆盖 ≠ 仓外无等价 md；②11 件 ≥85% 已证实是「落盘件全文在 wire 里复读」；③这批里混着分支仓评测与其他项目的少量内容。

**建议**：单开一次「非标题 wire 终稿」抽取批，口径改为「末条 assistant 正文内**任一行**为 `# ` 级标题 + ≥8KB + 与仓内及仓外 md 双向覆盖率 <60%」，逐件比 `docs/research`、`docs/evidence` 后再定，**不要整批入库**。

### 4.2 低优先、可选（不建议默认入库）

| 本地源 | 类型/主题 | 来源 | 定稿? | 仓内覆盖 | 建议落点 |
|---|---|---|---|---|---|
| `<LOCAL_WORK>/r154-ux/diff-R154-后端-入库锚.patch`（9,141 B） | 复核锚补丁（文件名即「入库锚」） | 10-13 R-154 批 | 定稿 | `补档-R154-后端-施工/复核报告` 有，**锚件无**（R159/R161/R162/R163 均有锚） | 若沿用锚件体例 → `docs/evidence/补档-R154-复核锚.patch`；否则不入（代码可由 git 复现） |
| `<LOCAL_WORK>/chat-timeline-pkg/红队锚定-批{1,2}/锚定说明{,-v2}.txt`（3 件，4.9–11.9KB） | 锚定物元数据（sha/字节/行数/新旧对照） | 10-11 对话时序包 | 定稿 | 结论在 `补档-CHATTIMELINE-*`；patch 本体未入库 | 与 patch 一体处置，单独入说明价值低 |
| `_cu-composer-decisions-r26/{page.html, followup/page-new.html}` | demo 中间态（v1 待裁决 / 三勾位待裁决） | 10-18 问题4 | 过程件 | 仓内 v2 批准稿（diff 20 行） | 不入；如需留痕 → `docs/demo/chat-ux/archive/` 并登 README |
| `_cu-docs-census/{_session_writes.txt, external-candidates.json, archive-manifest.json}` | 普查机器数据 | 10-19 文档普查 | 中间数据 | `补档-文档普查-附件-最终对账.json` 已入库 | 不入 |

---

## 五、排除计数（分类证明）

| 排除类 | 件数（去重） | 例证 |
|---|---:|---|
| 运行日志 / 门禁输出 | 3,423 | `commit-gov/gates-p19-backend.txt`(810KB)、`gates-post-fix.txt`、`boot*.log`、`mvn-*.log` |
| Playwright / test-results / HTML 报告 | 全部 `_final-close/pw-*-html/`、`_ci-real/report-*/`、`page@*.png`（142 截图） | 未计入成果 |
| 临时 diff / patch | 380 | `_anchor_*.diff`、`workspace-diff-redteam.txt`、`*.merged` |
| 提交信息草稿 | 152（其中项目域 12 + 大量 `_b*/msg*.txt`） | `msg1.txt`、`commit-msg.txt` |
| payload / 抓包 | json 约 400（`wb-*.json`、`body*.json`、`snap*.json`、`-rec/*.json`、`_req/*.json`） | `s44b-shots/*.json`、`/tmp/wb-*` |
| 临时目录 | 1,030 | `/tmp/*`（含 `$` 占位变量的伪路径） |
| 依赖 / 构建 | 5 + 全部 `node_modules`/`target`/`dist` 路径 | — |
| 其它项目 | 1,377 | LocalScreenShare（含 30 次 `strings.xml`）、RustDesk、Guacamole、Subnautica |
| 交接稿 / issue-archive / 密钥面 | 按 DC-21 与既有排除表 | 未读未入 |

---

## 六、无法判定范围（不能仅凭存在就推进入库）

1. **落点已不存在**：12,629 个唯一目标中 **5,910 个（47%）现已不存在**（`/tmp` 1,786、`/<DRIVE>/temp` 2,877、`<LOCAL_WORK>` 1,126）——内容无法比对，只能靠 wire 里的写入正文判。
2. **被改写过的文件**：`Edit` 共 23,609 次、同路径多次写入（如 `ci.yml` 59 次、`hitl-interrupts.html` 72 次），仓内外现存版本都不是「当时那版」，版本链只能以 wire 时间序重建。
3. **已删除会话**：前序审计口径的 23 个会话不可证，本轮扫描面内也未见其 wire。
4. **二进制 / 附件**：`agents/*/blobs/*`（无扩展名，sha256 命名）、`.png` 142 件、`.apk`、`.class` 均无法按文本判等价，且按规则本就是原始产物。
5. **密钥面**：`/tmp/tok.json`、`/tmp/auth.xml`、`_p2-review/runtime/model.env`、`rustdesk-deploy/.frp-token`、`handoff-archive/交接提示词-2026-10-13-当前态.md` —— **未读取、未评估内容**，一律不可作为入库候选。
6. **覆盖率是下界**：本轮与上批的「覆盖率」都只对**当前仍存在且可读**的语料计算，删除件、二进制件不入语料。
7. **wire-only 非标题终稿**：215 件中仅抽读 10 余件，**其余未逐件判定**；结论只到「存在一类被机械口径漏掉的大文本」，不等于「198 件都是缺口」。

---

## 七、给主 agent 的建议（本批均未执行）

1. 就 4.1 单独立项抽取，先出「候选清单 + 覆盖率 + 与仓内件 diff」评审表，再由用户裁是否入库；不要直接落 `docs/`。
2. demo 面维持现状即可；若要满足「中间版本全进 archive」的字面要求，需先改 10-19 普查的排除裁定，否则会自相矛盾。
3. `补档-R154-复核锚.patch` 是唯一「同体例缺件」，一件即可补齐链条，成本极低，可单独问用户。
