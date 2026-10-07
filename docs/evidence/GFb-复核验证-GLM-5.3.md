# GF-B 前端棒独立复核验证（GLM-5.3）

> 角色：复核与测试（GLM-5.3）。对 GF-B 前端棒做**独立复现验证**：不采信作者（Kimi K2.8）与验收者的结论，一切自己重跑。只验证、不改代码（本文档为唯一新增文件）。
> 仓库：`<REPO_ROOT>`，分支 `main`，HEAD `b41784a`。
> 复核日期：2026-10-07。
> 脱敏纪律：不出现真实盘符（统一 `<REPO_ROOT>`）、密钥（统一 `***`）、用户名。
> 证据等级标注：【实测】= 本机重跑命令/逐行读代码直接确认；【推断】= 由代码路径推演、未跑 UI；【假设】= 需进一步验证才能确认。

---

## 1. 复现环境

| 项 | 值【实测】 |
|---|---|
| 操作系统 | Windows（Git Bash 执行命令） |
| node / yarn | v22.23.2 / 1.22.22 |
| 仓库与 HEAD | `<REPO_ROOT>`，分支 `main`，`b41784a`（`git rev-parse HEAD` 实测一致） |
| 验证方式 | `git status/diff`、`yarn typecheck`、`yarn build`、仓库外临时目录 `node --experimental-strip-types` 断言、逐行读前后端源码 |
| 约束遵守 | 全程无 git 写操作；除本文档与仓库外临时目录（用完即删）外零文件改动；零新增依赖 |

**环境事实（必须记录）**：复核开始时 `git status --porcelain` 为题目所述 6 项；复核进行中（约 17:55）第三方红队（DS-V4-Pro）向 `docs/evidence/GFb-红队审查-DS-V4-Pro.md` 写入了审查报告，工作区未提交项由 6 变 7（新增该未跟踪 .md）。该文件非本复核产物，属并行流程写入；除它之外工作区与题目描述一致。【实测】

---

## 2. 逐项复现结论

### 项 1 —— git 状态与改动范围：**PASS**

命令与输出摘录【实测】：

```
$ git rev-parse HEAD && git branch --show-current
b41784a6e8eb1c809b442dad3e4d2052ecc2f385
main

$ git status --porcelain
 M frontend/src/components/AiPanel/AiPanel.vue
 M frontend/src/stores/ai.ts
 M frontend/src/types/ai.ts
 M frontend/src/types/api.ts
?? frontend/src/components/AiPanel/FormRenderer.vue
?? frontend/src/types/form-schema.ts
```

- 【实测】恰好 6 项：4 改 + 2 新增，与题目描述逐项一致。
- 【实测】`git diff --stat` 全部落子 `frontend/src/`（4 文件，+255/−10）；`git ls-files --others --exclude-standard` 仅上列 2 个新增文件。**未触碰** `backend/`、`.github/`、`scripts/githooks`、`package.json`、`yarn.lock`。
- 【实测】复核窗口内出现的第 7 项（红队报告 .md）是并行第三方写入，见 §1 环境事实，不计入作者改动。

### 项 2 —— typecheck 与 build：**PASS**

命令与输出摘录【实测】（在 `<REPO_ROOT>/frontend` 下执行）：

```
$ yarn typecheck
$ vue-tsc --noEmit
Done in 3.84s.
EXIT_CODE=0（总耗时约 4s）

$ yarn build
$ vue-tsc --noEmit && node scripts/build.mjs
vite v6.4.4 building for production...
✓ 1746 modules transformed.
✓ built in 30.02s
Done in 34.43s.
EXIT_CODE=0（总耗时约 35s）
```

- 【实测】typecheck 绿（exit 0，3.84s）；build 绿（exit 0，34.43s，其中 vite 构建 30.02s，1746 模块）。
- 【实测】build 产物含全部预期 chunk（element/spreadjs/vendor 等，无报错无警告中断）。

### 项 3 —— `types/form-schema.ts` 动态断言复测：**PASS（40/40）**

方法【实测】：将 `frontend/src/types/form-schema.ts` 原样复制到仓库外临时目录，自写断言脚本 `assertions.mts`（仅 import 该文件的两个导出），以 `node --experimental-strip-types assertions.mts` 运行；跑完即 `rm -rf` 临时目录（已确认删除），不入仓、零依赖。

**parseFormSchema 非法输入一律 null（20 例全过）**【实测】：

| 断言 | 结果 |
|---|---|
| 非对象：null / 数组 / JSON 字符串 / 数字 | PASS（4 例全 null） |
| 缺 scenario / scenario 空串 | PASS |
| fields 缺失 / fields 空数组 | PASS |
| 非法 type（`richtext`）/ 缺 type | PASS |
| 重复 key（同 key 两字段） | PASS |
| enum 无 options / options 空数组 | PASS |
| 裸字符串 options（`['a','b']`） | PASS |
| multi_select 无 options | PASS |
| 字段非对象 / key 空串 / title 非字符串 / options 含重复 value / 选项缺 value | PASS（5 例全 null） |

**合法 schema 通过（7 例全过）**【实测】：六型字段全量 schema 解析成功，scenario/title/六字段/选项 `label` 可选省略/非 enum 字段不强求 options 均符合预期；无 title 时通过且产物不含 title 键。

**checkFormValues（13 例全过）**【实测】：

| 场景 | 结果（问题清单含预期文案） |
|---|---|
| 必填缺失（缺字段 / 空串） | PASS（`「关键词」为必填项` 等） |
| 未知字段 `extra` | PASS（`存在 schema 之外的字段：extra`） |
| enum 越界（`lvl:'mid'`） | PASS（`取值不在选项内`） |
| date 坏格式（`2026/01/01` / `2026-13-45`） | PASS（`应为 yyyy-MM-dd 格式的合法日期`，形似但日历非法也被 `Date.parse` 拦截） |
| multi_select 越界（`['a','z']`）/ 重复项（`['a','a']`） | PASS（`应在选项内且不重复`） |
| number/boolean/text 类型不符 + 合法全量提交 0 问题 | PASS |

过程说明【实测】：首轮 39/40，唯一 FAIL（C09）经核查为**本复核断言用例自身设计缺陷**（用例值对象漏带必填字段 `lvl`，源码行为正确）；修正用例后 40/40，`EXIT_CODE=0`。非源码问题。

### 项 4 —— 契约静态核对：**PASS（红队复核出的 2 个中等问题除外，见 §3）**

逐条【实测】（读 `frontend/src/stores/ai.ts`、`frontend/src/types/*`、`backend/.../ai/` 源码）：

1. **generative_form 特判不落通用执行器兜底**：`handleFrame` 的 `frontend_tool_request` 分支中 `frame.name === 'generative_form'` → `openGenerativeForm(message, frame)` 后立即 `break`，**不落** `runFrontendTool`（通用执行器）。同时 `submitGenerativeForm`/`cancelGenerativeForm` 独立于执行器通道。【实测】
2. **取消与 schema 解析失败路径均带 `cancelled:true`**：
   - schema 解析失败：`openGenerativeForm` 中 `!spec || !runId` → `submitFrontendToolResult(frame.toolCallId, '', 'frontend-executor', true)`（第 4 参 cancelled=true），且**不回灌任何说明文本**（result 传空串）；【实测】
   - 显式取消：`cancelGenerativeForm()` → `submitFrontendToolResult(..., true)`；【实测】
   - 本地超时：`formExpiryTimer` 到点 → `cancelGenerativeForm(true)`（auto）→ 同样带 `cancelled:true` 回灌；【实测】
   - `submitFrontendToolResult` 内 `cancelled ? {runId, toolCallId, cancelled:true, source} : {runId, toolCallId, result, source}`，两种载荷互斥构造。【实测】
3. **400/409 分支区分**：
   - 前端 `ErrorCode` 登记 `FORM_RESULT_REJECTED`（400）、`FORM_SCHEMA_REJECTED`（400）、`FRONTEND_CANCELLED`（终态口径）、`DUPLICATE_TOOL_CALL_ID`（409）、`UNKNOWN_TOOL_CALL`（409）；【实测】
   - catch 内先判 `DUPLICATE_TOOL_CALL_ID`（409，幂等拒绝，`duplicate:true` 返回）再判 `FORM_RESULT_REJECTED`（400，`rejected:原因清单` 返回，不置 notice 打断填写），其余落通用失败分支；`submitGenerativeForm` 对 `rejected` 回 `filling` 态可同 `toolCallId` 重发或取消；【实测】
   - 与后端 `AiController.frontendToolResult` 对齐：NOT_FOUND/DUPLICATE → 409，REJECTED → 400（`rejectedFrontendResult` 带 `reasons` 清单 + note「挂起仍在等待，修正后可重试；用户放弃请带 cancelled=true」）。【实测】
4. **本地超时自动 cancelled:true 回灌**：`openGenerativeForm` 按 `expiresAt`（优先）或 `timeoutSeconds` 折算 deadline，武装 `setTimeout(max(0, deadline-now))`；到点自动取消并回灌 `cancelled:true`；`disarmFormExpiry` 在 done/error/stop/resetSession/结局帧/新表单六处清理。【实测】
5. **与后端 DTO/Rules 对齐**：
   - `FrontendToolResultRequest`（后端 record）：`runId/toolCallId/result/source/cancelled`，`cancelled` 为 `Boolean` 可空（缺省 false，老客户端零改动）；前端 `types/ai.ts` 的 `FrontendToolResultRequest` 同形（`result?` 与 `cancelled?` 均可选）——**契约字段级一致**。【实测】
   - `GenerativeFormRules`：`FORM_SCHEMA_REJECTED`（schema 级）/`FORM_RESULT_REJECTED`（回灌级）两码与前端 `ErrorCode` 逐字一致；字段类型白名单六型（text/number/boolean/date/enum/multi_select）与前端 `FIELD_TYPES` 一致；后端 options 只收 `{value,label?}` 对象（裸字符串拒绝）与前端 `parseOptions` 一致；后端日期严格 ISO 与前端 `DATE_PATTERN + Date.parse` 同口径。【实测】
   - 前端注释明示"只做防御性兜底、不重复后端校验"（不查长度上限、不数选项个数、不查 scenario 白名单 FILTER/CLARIFY）——属**设计上的前端宽松**，非遗漏（后端闸门已挡）。【实测】

### 项 5 —— 视觉纪律抽查（FormRenderer.vue）：**PASS**

【实测】（`grep` + 逐行读全文 161 行）：

- **无真实 `<style>` 块**：`grep '<style'` 零命中（文件仅 `<template>` + `<script setup>` 两段）。
- **无 v-html**：`grep 'v-html'` 命中 2 处，均为注释（L12 `<!-- …文案一律文本插值，永不 v-html -->`、L111 头注释），**非代码命中**。
- **模板全 el-\* 组件**：`<el-tag>` / `<el-input>` / `<el-input-number>` / `<el-switch>` / `<el-date-picker>` / `<el-select>`+`<el-option>` / `<el-alert>` / `<el-button>` 共 13 处 el-* 用法；六型字段各配一个 EP 控件 + 异常类型走 `el-alert` 拒渲染兜底；文案全部 `{{ }}` 文本插值或 prop 绑定（`:placeholder`/`:label`/`:title`）。【实测】
- 唯一内联样式 `el-input-number` 的 `style="width: 100%"`（L29）——功能必要（该组件默认不自适应宽度），无安全影响；与红队 A 项裁定一致。【实测】

### 项 6 —— 作者完工报告"手动冒烟说明（纯前端构造帧路径）"可行性审查：**PASS（附两点说明）**

**前提说明【实测】**：本复核在仓库与 `<TEMP>` 工作区（`gfb-scratch/`、`gfb-e2e/`、`gfb-*.log` 等）均**未定位到作者完工报告正文文件**（仅有作者的 typecheck/build 日志、断言草稿与一份不含 generative_form 用例的 S5b 22 用例 E2E 回归日志）。故无法逐字核对作者原文；以下为对"纯前端构造帧路径"这一冒烟思路**独立审查的逻辑可行性结论**：

- 【推断】**路径成立**：`handleFrame` 与 `openGenerativeForm` 均为 Pinia store 公开 action，可在浏览器 console 直接调用。构造 `frontend_tool_request` 帧（`name:'generative_form'`、`toolCallId`、`runId`、`timeoutSeconds`/`expiresAt`、`args` 为含 form schema 的 JSON 字符串）即可驱动 `openGenerativeForm → parseFormSchema → activeForm 挂起 → AiPanel 渲染 FormRenderer` 全链路；空会话时 `currentAssistantMessage()` 自动 `appendAssistantMessage()`，无需前置造消息。【实测（代码路径）+推断（未跑浏览器）】
- 【推断】**两个注意点**（不构成不可执行，但冒烟者需知道）：
  1. 帧必须自带 `runId`（或先构造 `start` 帧置 `activeRunId`）——两者皆缺时 `openGenerativeForm` 走"缺 runId"分支，表单不渲染且不发回灌请求（`submitFrontendToolResult` 在 `activeRunId` 为空时本地返回 `{ok:false}`）。
  2. 前端 `parseFormSchema` **不校验 scenario 白名单**（只查非空串），冒烟构造任意非空 scenario 均能渲染（FormRenderer 仅 `FILTER` 显示"筛选条件"，其余显示"信息确认"）；若要对齐后端口径应构造 `FILTER`/`CLARIFY`。
- 【推断】**交互闭环的限制**：提交/取消会真实 `POST /api/ai/frontend-tool-result`，纯前端（无后端在线）时只能验证到"渲染 + 客户端复核拦截（checkFormValues）+ 失败分支文案"，提交成功/400 拒绝/409 幂等等回灌闭环需后端在线。这是该冒烟路径的固有边界，非步骤错误。
- 【实测】未发现明显不可执行的步骤——但因作者报告原文缺失，此项结论以代码路径可行性为限，标注【推断】。

---

## 3. 与作者报告 / 验收报告不一致处

1. **工作区状态 6 项 → 7 项（环境事实差异）**【实测】：复核进行中，第三方红队（DS-V4-Pro）写入 `docs/evidence/GFb-红队审查-DS-V4-Pro.md`（未跟踪文件，写入时间约 17:55，晚于本复核开始）。若后续验收以"6 项"为口径，应以本节说明为准：第 7 项非作者改动。
2. **作者完工报告正文未找到**【实测】：`gfb-*` 工作目录只留有构建/断言/E2E 产物，无报告正文。项 6 的冒烟审查因此降级为"代码路径可行性"级别（见 §2 项 6），**无法对作者原文逐字核对**——这一点与"作者报告里写了手动冒烟说明"的前提不一致，如实记录。
3. **对红队报告两个中等问题（问题 1/2）的独立验证：均属实**【实测】——本复核不采信红队结论、自行 grep/读码复现：
   - **问题 1（属实）**：`activeForm.runId` / `activeForm.messageId` 全仓**零读点**（`grep activeForm\.(runId|messageId)` 无命中），写入后从未使用；`submitGenerativeForm`/`cancelGenerativeForm` 回灌实际用的都是 `this.activeRunId`。断流残留 + 新轮启动的组合下最坏产生跨 run 错配回灌（后端 `(runId, toolCallId)` 键控封顶为 409，无数据污染）。
   - **问题 2（属实）**：`send` 的 `onClose` 非终态分支（断流）只置 `streamInterrupted`/`notice`，**未** `disarmFormExpiry`、**未**清 `activeForm`；而 done/error/stop/resetSession 四路径均清理。断流是第五个收尾出口，确实遗漏。
   - 本复核对项 4 的 PASS 判定不受影响：上述两问题不违反本复核清单的任何一条契约断言（取消仍带 cancelled:true、400/409 仍区分、特判仍不落兜底），属健壮性缺陷而非契约违背——与红队"放行带修复项"的定级相容。
4. 其余各项（typecheck/build 结果、断言行为、视觉纪律、契约字段对齐）未发现与既有证据材料相矛盾之处。【实测】

---

## 4. 总结论

**6 项验证全部 PASS**：改动范围干净（仅前端 6 文件）、typecheck/build 双绿（exit 0，3.84s / 34.43s）、`form-schema.ts` 防御性解析与复核 40/40 断言全过（非法 schema 一律 null + cancelled:true 收敛、六型取值复核齐备）、前后端契约字段级对齐（cancelled 可选字段、FORM_*/DUPLICATE 错误码、400/409 语义）、视觉纪律达标（无真实 style 块、无 v-html 代码命中、全 el-* 组件）、纯前端构造帧冒烟路径逻辑可行。附带两点如实记录：作者完工报告正文未找到（项 6 以代码可行性为限）；红队两个中等问题（`activeForm.runId` 死字段 + 断流路径不清表单态）经本复核独立验证属实，属健壮性缺陷、不违反本清单契约，建议随红队意见在下个补丁棒修复。

（本复核未执行任何 git 写操作；除本文档与仓库外临时断言目录（已删除）外未改动任何文件；未引入任何依赖。）
