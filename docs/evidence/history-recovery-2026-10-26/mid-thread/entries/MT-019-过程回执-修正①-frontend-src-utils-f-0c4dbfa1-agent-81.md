<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-019 · 过程回执 · 修正①：`frontend/src/utils/frontend-tools.ts` 工具计数注释（0c4dbfa1 / agent-81）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`frontend/src/`、`AiTools.java`、`generative_form`、`backend/src/main/java`。
> **稿态**：历史中间稿 —— 该 wire 第 `m17` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt7` 条有正文的 assistant 消息（共 10 条有正文），末条有正文消息为 `m37`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `0c4dbfa1` / agent `agent-81`（`main` = 主会话）；原字符 3,734（链内 1 段、合计 3,734）。
> **链号 / 类别**：`MT-019` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `10%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `4%`。
> **与末条归档（138 件）的关系**：末条 `m37`（905 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

三项修正全部完成并验证通过，改动待验（未做任何 git 写操作）。

## 修正①：`frontend/src/utils/frontend-tools.ts` 工具计数注释

**实测证据**
```
$ grep -c '@Tool(name' backend/.../ai/tools/AiTools.java
18
$ grep -n 'ToolMeta.Channel.FRONTEND' backend/.../ai/tools/AiTools.java
339,349,365,375,403,439,495   → 7 处
```
7 个 FRONTEND 工具（逐个核对 `@Tool(name=` 与注解对位）：`open_export_file_editor`、`download_export_file`、`navigate_to`、`select_definitions`、`set_condition`、`confirm_step`、`generative_form`。与预期 18 / 7 一致，无需【待裁决】。

**改动要点**
- 头部注释块（原 4-8 行）：`12 个 @Tool` → `18 个`；`只有 2 个` → `有 7 个`（列全 7 个工具名）；`其余 10 个是 BACKEND` → `其余 11 个`。
- 路由 1 一条改为如实口径：路由 1 覆盖 `FRONTEND_TOOLS` 登记的 **2 个**（`open_export_file_editor` / `download_export_file`）；**新增一条**说明另 4 个（`navigate_to`/`select_definitions`/`set_condition`/`confirm_step`）走路由 2/3 工作区动作表，`generative_form` 的执行接入待后续棒。
  - 说明：我没有把路由 1 写成"覆盖 7 个"——`FRONTEND_TOOLS`（现 79-104 行）确实只登记 2 个，且 `WORKSPACE_ACTIONS` 里也**没有** `generative_form`（`grep -rn 'generative_form' frontend/src/` 无命中），所以按事实分列。此条新增了一行（原第 8 行 bullet 展开为 3 行），属"重写 4-8 行"范围内的展开，特此披露。
- 原 255-257 行（改后 260-261 行）：`后端当前只披露 2 个 FRONTEND 工具，故…（后端补披露后）` → `后端已披露 7 个 FRONTEND 工具，上述 4 个均在其中，故这些动作今天经「前端工具帧」或「契约层」触发；…后续工具接入时前端零改动。`

**顺带核对 `frontend/src/types/tools.ts`**
- 表内 `grep -c` 行数：改前 17 → 改后 **18**（已补 `| generative_form | \`page:tasks\`, \`task:*\` | READ | FRONTEND |`，同名/同 scope/同风险/同通道取自 `AiTools.java:493-495`）。
- 卡片提到的 29-32 行"工具总数表述"：`grep -n '18\|17' frontend/src/types/tools.ts` **无命中**，全文没有总数语句，故无 18 可改；改为把旧口径②补全：`DC-15 起 generative_form 亦然（后端已披露，前端执行接入见 utils/frontend-tools.ts 头部口径）`。
- 未触碰 `FRONTEND_TOOLS` 执行描述表与 `FrontendToolName` 联合类型（仍只有 2 个成员）。

## 修正②：`frontend/src/types/condition.ts` 顶部过期注释

**实测证据**（`backend/src/main/java/com/example/configmgr/data/service/ConditionEvaluator.java`）
```java
// validate(): 逐行求值前的白名单校验
if (!OPERATORS.contains(op)) {
    throw unknownOperator(op, fc.getFieldCode());     // :47  IllegalArgumentException
}
// matchesOp(): 未知操作符分支
default:
    // M1 收尾守卫③：未知操作符抛异常（原为 return true = 静默放宽，属数据面风险）
    throw unknownOperator(op, fc.getFieldCode());     // :130
```
调用方 `ConfigDataService.java:90` 与 `ExportJobRunner.java:94` 均在求值前调用 `ConditionEvaluator.validate`。实测确认"抛异常"，与卡片一致。

**改动要点**（原 23-27 行 JSDoc）：`后端对未知操作符的处理是"视为通过"（不丢行）` → `后端对未知操作符的处理是**白名单拒绝**：ConditionEvaluator.validate 在逐行求值之前先校验，未登记的操作符抛 IllegalArgumentException（matchesOp 的 default 分支同样抛），端点据此返回 400，不再"视为通过"（M1 收尾守卫③）。前端类型只收窄登记在册的算子。`

## 修正③：GFa 证据文档 GF-C → GF-B

- 改前：`grep -n "GF-C"` 命中 14 行（9、108、286、320、325、347、348、361、385、398、406、408、430、432）——与卡片列出的行号完全一致；`grep -c` = 14。
- 改动：整词替换 14 处为 `GF-B`（Edit `replace_all`，无其他文字变动；`git diff --numstat` = `14 14`）。
- 改后验证：
```
$ grep -n "GF-C" docs/evidence/GFa-生成式表单后端工具验证.md
（无输出，exit=1 → 空）
$ grep -c "GF-B" 同文件
15        # 14 处替换 + 原有 1 处（436 行"已登记转交 GF-B"）
```
- 增益旁证：文档 436 行本就写"…已登记转交 GF-B 处理"，改名后与文档自身口径自洽。

## 验证

```
$ cd frontend && yarn typecheck
yarn run v1.22.22 / $ vue-tsc --noEmit / Done in 3.62s.        EXIT=0

$ git status --short          → 仅 4 个文件 M（卡片枚举的 3 项修正覆盖 4 个文件）
$ git diff --stat
 docs/evidence/GFa-生成式表单后端工具验证.md | 28 +++++----   (14 行，仅 GF-C→GF-B)
 frontend/src/types/condition.ts             |  4 +++-
 frontend/src/types/tools.ts                 |  4 +++-
 frontend/src/utils/frontend-tools.ts        | 16 ++++++----
 4 files changed, 30 insertions(+), 22 deletions(-)
```
未执行 `git add/commit/push`，改动保持待验。

## 无【待裁决】项；两处范围外观察（未改，提请裁决是否补）

1. `frontend/src/utils/frontend-tools.ts` 原 9-12 行（改后 13-16 行）路由 2/3 bullet 内仍写"后端**一旦**把它们标为 FRONTEND 并披露，前端零改动即可生效"——这 4 个今天已确实是 FRONTEND，条件句已成立但与新增 bullet 略有重复。卡片限定改为 4-8 行与 255-257 行，故未动。
2. `docs/evidence/GFa-生成式表单后端工具验证.md:436` 仍写"frontend-tools.ts 顶部工具计数注释过时…已登记转交 GF-B 处理"——本棒已把该注释改对，这句按卡片要求"仅改指代不动其他任何文字"故保留；如需标注"已处理"请另行裁决。
