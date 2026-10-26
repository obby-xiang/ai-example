<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-117 · 设计 · ① E2E-G01（`11b-hgta.spec.ts:487`）— 回灌 400（566a3c57 / agent-1642）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`generative_form`。
> **稿态**：历史中间稿 —— 该 wire 第 `m215` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt6` 条有正文的 assistant 消息（共 9 条有正文），末条有正文消息为 `m219`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `566a3c57` / agent `agent-1642`（`main` = 主会话）；原字符 4,770（链内 1 段、合计 4,770）。
> **链号 / 类别**：`MT-117` / **设计**；判定：助手设计方案/计划（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 34 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `3%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m219`（331 字符）未入末条归档。
> **脱敏**：路径 2 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

两处失败的测试都已修好，并在本地真模型 @real 下复跑通过。交接如下。

## ① E2E-G01（`11b-hgta.spec.ts:487`）— 回灌 400

**根因（证据链）**：问题不在产品侧，而在于用例的**填写面不完整**。
- CI trace 里的回灌请求体：`{"keyword":"真模型-1","scope":[]}`；响应 400 原文：`FORM_RESULT_REJECTED` / `reasons:["字段 scope 至少选 1 项（实际 0）"]` / `issues:[{key:"scope",code:"VALUE_TOO_FEW_ITEMS"}]` / `retryable:true`。
- 也就是说，真模型把「范围」生成成 `multi_select` + `minItems 1`（用例提示词自己点名了「范围（XN/HD）」），用例只填了 `keyword` 就提交，后端按 schema 复核拒绝；卡片还把 `issues[].key` 点亮成字段错误（页面快照里的「范围」选择过少（至少 1 项）就是这个）。
- 桩模式碰不到这一层：`support/gf2-upstream.mjs` 的 `scope` 是**可选 enum**，只填 keyword 也能通过。产品行为本身有专门的 @stub 用例守着（`E2E-G04` 就是「400 FORM_RESULT_REJECTED → 按 key 点亮 → 改值重发成功」），不是缺陷。

**修法**：
- `frontend/e2e/support/hgta.ts:256` 新增 `pendingFormFields(request, sid)`：从 `GET /api/ai/runs/current` 的挂起条目取 `generative_form` 入参原文（`{form:{scenario,title,fields}}`，与卡片渲染同源），得到本轮 schema。
- `frontend/e2e/11b-hgta.spec.ts:413` 新增 `fillFormBySchema`：按 schema 逐字段给合法值（多选按 `minItems` 勾选、优先 XN；enum 选首个合法值；text/number 按 `format`/`min`/`maxLength` 给值；联动隐藏字段跳过；`date`/`attachment` 不猜）。原「只填 keyword」保留在能定位到时，否则由 schema 的文本字段兜底。
- 断言口径**一字未改**（仍是 `回灌 200 → 本轮落定 → 提交后表单退出可填态 → 无卡级错误 → 有续跑内容`），只在非 200 时把拒绝体带进断言消息。

**顺带修掉的三个真模型现场缺陷（都在允许范围内）**：
1. **只读回放卡选错**（`:503`）：`[data-testid^="gf-form-"].last()` 会选中上一张「未提交 ✗ 已取消」的只读回放卡（scope 形如 `gf-form-ro11`，字段 `disabled`）。改为 `filter({ has: gf-submit })` 钉死 live 卡。
2. **无卡轮的残留挂起**（`:516`）：模型本轮先被入参闸门拒绝、再挂起一次时，卡没渲染就没有提交/取消入口，下一轮 composer 落不了键、`sendMessage.fill` 会一直等到 15 分钟测试上限。现在先点「停止」把本轮收干净。
3. **动作无超时**：Playwright 默认 `actionTimeout=0`，helper 内的 `fill/check/selectOption` 与关键字填写、提交、new-chat 全部加了 10–15s 显式超时（失败信息可读，不再挂死）。

**本地 @real 复跑数字**（真 key 只经内联 env 传入，未打印/未落盘，日志已核无 `sk-`）：
- `E2E_REAL_SAMPLING=2`：`触发=2/2`，2 轮回灌均 200（trace 证据：第 1 轮模型生成 **enum** `scope` → 回灌 `{"keyword":"真模型-1","scope":"XN"}`；第 2 轮生成 **multi_select** `scope_keys` → `{"keyword":"真模型-2","scope_keys":["XN"]}`，两种字段型都被覆盖）。
- `E2E_REAL_SAMPLING=3`：`3/3` 通过。
- **`E2E_REAL_SAMPLING=10`：`触发=10/10 ≥7`，通过（2.1m）**。

## ② @real E2E-C13（`16-kqc.spec.ts:849`）— 6 轮内压缩从未触发

**根因（证据链）**：阈值和窗口没问题，缺的是**可清的对象**。
- CI 日志：`档位 none×6`、`占用 74.9%→81.3%→87.4%→93.7%→99.8%→106.5%`、`工具调用 1/1/1/1/1/1`。占用早已越 L1 线（`(15000−13000)×70% = 1400`），且用例每轮断言 `limitTokens === 15000` 全过 ⇒ `withModelWindow` 写接口在 CI **是生效的**（"写接口没生效"的疑点可排除）。
- 真正的结构原因：L1 只清 **pin 窗口之外**的工具结果，`app.ai.context.keep-tool-pairs` 默认 **5**（`ToolResultClearing.plan` 里 `pinnedFrom = slots.size() - 5`）。每轮 1 次工具调用 ⇒ 第 N 轮判定时历史里只有 N−1 ≤ 5 条结果，**全在 pin 窗口内** ⇒ `ContextCompactor` 走 `REASON_NOTHING_TO_CLEAR` 的 noop ⇒ 档位恒 `none`、台账空。与 @stub 版 `16-kqc-c.spec.ts` 自己的口径一致（它跑 **10 轮**，注释写明"第 7 轮出现第 6 对工具结果那一刻才触发 L1"）。

**修法（只改前置与判据结构，不动阈值/窗口）**：
- `16-kqc.spec.ts:873` 提示词改成每轮分别查 `CURRENCY / DOC_TYPE / APPROVE_ROLE` 三个已有定义（"三个编码各调用一次，不要合并"）⇒ 每轮 3 条工具结果，第 3 轮起总数 > 5，pin 窗口外的旧结果有得清。
- 判据结构补强：新增每轮 `tool_result` 计数与快照 `compaction.reason` 采集，日志与失败信息都带上（`nothing_to_clear` = 前置不足 / `under_threshold` = 占比没到 / `hysteresis_locked` = 滞回锁），红的时候能直接归因。断言本身（档位离开 none、台账非空、L1 的 turns/lostTokens=0、末条降占用、快照≡台账、context 帧≡台账）**一条没放宽**，`REAL_LINEAGE_WINDOW=15000` 与 6 轮成本档也没动。

**本地 @real 复跑数字（两次一致）**：
- `档位 none → none → L1 → L1 → L1 → L1`；`占用 78.3% → 93.1% → 105.4% → 121.1% → 137.1% → 152.6%`；`工具调用 3/3/3/3/3/3`、`工具结果 3/3/3/3/3/3`；`台账=1 条（L1:1629→1492）`，末条 `turns=0 lostTokens=0`；**通过（24.7s）**。
- 说明：台账只有 1 条是预期——首压后真实占用仍 150% 左右，`hysteresis-percent=60` 把后续轮锁住（`hysteresis_locked`），档位保持在 L1；6 轮内也到不了 L2（`keep-recent-turns=6` 需要 >6 轮才折叠），这与 @stub 版用 10 轮才能爬到 L2 同源。

## 建议的 CI 复跑时机

1. 等并行 agent 的 12a/12b/15-mdo/playwright.config.ts/harness.ts 改动全部落地并提交后（我本地跑的时候工作树里有他们的在途改动，`vite` HMR 会在用例执行中热更页面，造成过假红/挂起）。
2. 提交本次 e2e 改动后手工触发 `e2e-real.yml`（suite=real, sample=10）。预期：`E2E-G01` 触发 ≥7/10 且逐条 A/B 断言全绿；`E2E-C13` 第 3 轮起出现 L1 并走完台账/三面同源。
3. 真模型对 `generative_form` 的入参闸门拒绝是**随机**的（本地 10 轮里出现过 3 次，但都因模型同轮重发而仍计入触发）：若某次 CI 采样恰好 <7，先看 `problems` 里的「模型未触发」条数与后端 `前端工具入参被安全闸拒绝` 日志，那是 C 级概率面问题，不是新缺陷。

## 遗留 / 需要 follow-up 的点

- **`harness`/本地环境**：本机 Redis 只有 DB 0–15（我误用 16/17 时后端报 `Unable to connect to Redis`，`/health` 永远不 `redis.available`）——不是代码问题，但值得在 harness 里对 `E2E_REDIS_DB > 15` 直接 fail-fast 报清楚。
- **T 域两条桩用例在整文件串跑时失败、单跑通过**：`E2E-T13`（`11b-hgta.spec.ts:584`）与 `E2E-T05`（`:650`）在一次全量 @stub 串跑里红（sheet 预览卡没出 / 聚合分组数为 0，页面快照显示"当前页面没有可操作的表格"），单跑同一实例两分钟后又全绿。与我的改动无关（我没碰 T 域，且我的 3 个文件只新增了 @real 路径的代码），怀疑是工作树里在途的 i18n/AiPanel 改动 + 串跑状态串扰，建议由 T 域 owner 复核（那次的 `19` 张截图/trace 在 `<LOCAL_WORK>/_ci-real/out-stub11b/`）。
- **未做的事**：没跑 `vue-tsc`（e2e 不在 `tsconfig.json` 的 include 里，CI 也只对 `src` 做类型检查）；没跑全量 @stub 套（只跑了 `11b` 全文件的 20 条：18 绿 / 上述 2 条），`16-kqc` 的 @stub 部分未动、也未跑。

**改动文件（仅这三个）**：`frontend/e2e/11b-hgta.spec.ts`、`frontend/e2e/16-kqc.spec.ts`、`frontend/e2e/support/hgta.ts`。全程无 git 写操作，未起停 8081/5202，本地实跑用自带 harness + 独立端口段/Redis DB/运行目录（`<LOCAL_WORK>/_ci-real/run-*`），已确认无残留进程与端口占用。
