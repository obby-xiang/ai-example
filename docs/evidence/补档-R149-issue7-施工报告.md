<!-- 第 20 期补档入库 · R-149（issue#7） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\施工报告-R149.md`（4498 B，sha256 `154b99218175e65d5964120ba4fa980fb6d0cef90debf3ab2c9adb890b1b3d7b`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本件为 **R-149（issue#7）** 报告补档（第 20 期入库）；基线 HEAD = `0f46286`。本期**审查锚** = `3f7976448f51a6288139a367a65370a45bfb478344e7e486d90d0d6137a42dee`（18538 B / 2 段 / +331−19，复核与红队据其出报告）；本期**入库锚** = `54c942764ee80f7e1c62fee74e2e770a8c1feabb9083c1174ca770d01b359150`（21915 B / 2 段 / +396−19，收口修 C1~C3 后重锚）。两 SHA **不可混用**。

# 施工报告 · R-149 历史恢复工具结果丢失 + 空白消息块（K2.8 Preview，TDD）

## 署名（DC-19 实证）

- **实际运行模型（施工主 agent）**：`kimi-code/kimi-for-coding`（profile=agent，thinkingEffort=high）。
- **CU 会话 id**：`session_856bb412-5a36-4c00-b41b-610cd406fe01`。
- **wire modelAlias 实证**：本会话 `agents/main/wire.jsonl` 累计 174 条 `"modelAlias":"kimi-code/kimi-for-coding"` 记录（2026-10-12 13:39 复核），首条 `{"type":"profile.bind","agentId":"main","modelAlias":"kimi-code/kimi-for-coding","profileName":"agent","thinkingEffort":"high",…}`。
- **下沉子智能体（显式钉 DeepSeek/deepseek-flash）**：agent-612（行号锚点重取扫描）。
- 施工卡 sha256 核验：`b8aeb29b…4b3437`，与卡面一致。

## 开工基线

- HEAD=**0f46286**（手动刷新批已闭环提交，开工条件满足；工作树干净）。
- 行号锚点按开工时 HEAD 重取（agent-612 扫描）：`loadHistory` = ai.ts:603-716；历史循环旧锚 678-710 **未漂移**；`roleOf` :309-318 未漂移；AiHistoryMessage types/ai.ts:133-143；ToolPart :243-268；夹具 parts-residue-cleanup.spec:577-612 未漂移。
- 旧 vitest 基数：**18 spec / 202**（手动刷新批闭环后）。

## S1 施工面（唯一）：loadHistory 两遍扫描合并（ai.ts:678-710 → 合并块）

- **第一遍**：遍历 history.messages，TOOL 条目响应入 `responsePool` 并按 `response.id` 建 `responseIndex`（**跨轮重复 id 取后出现者**，覆盖式 set，注释注明）。
- **第二遍** `flatMap` 构建 ChatMessage：
  - TOOL 条目 `return []`（零消息产出，消空白块）；
  - ASSISTANT 工具段回填：① call.id 三态为真 → id 匹配，**consume-on-match**（命中即从索引摘除）；未中进 `unmatchedCalls`；
  - `history-${index}` 的 index **锁死原始数组下标**（含 TOOL 空洞，spec⑧断言 history-0/1/4）；
  - 回填后必走 `deriveProjections`；`requiresAction:false`（不复活挂起卡）；合并只在 loadHistory 历史分支，实时增量路径零改动。
- **序位兜底**（循环后统一互配）：未配对 call × 未消费 response 按序互配 + **name 相等闸**；仍不中 result=null 卡片保留；无主 response 静默丢弃不抛错。
- **未动**：`status:'succeeded'` 硬编码（R-141②/R-144 后端面）；`roleOf` 无 TOOL 分支；渲染层（AiPanel v-if="part.result" 自动生效）。

## S2 测试（先写失败测试，RED→GREEN）

- 夹具改写：写点⑥假形状（toolCalls+toolResponses 同条 ASSISTANT）→ **真实双条目**（ASSISTANT 带 toolCalls + 紧随 TOOL 带 toolResponses），断言补"消息条数=2（TOOL 不产生消息）"。
- 新增 describe「R-149 双条目合并」8 用例：①一对多（单 TOOL 多 response 逐个 consume）②连续多轮各吃各份 ③id 缺失序位兜底+name 闸（beta vs gamma 不配、卡片保留）④重复 id consume-on-match（后出现者优先、后来者序位兜底）⑤responseData=null 卡片仍在 ⑥无主 response 静默丢弃 ⑦**镜像路径回归**（后端合并→镜像写盘→再走 loadHistory，parts 深度相等、结果不丢）⑧空白块消除+id 锁死原始下标。
- RED：8 用例全红（TOOL 落成空白 assistant 块、响应被丢弃）；GREEN：**18 spec / 210 全绿**。

## 验收口径（全绿实测）

1. vitest：**18 文件 / 210 测试全绿**（202 → 210，+8；新增全部在 parts-residue-cleanup.spec：22→30）。
2. typecheck（vue-tsc --noEmit）绿；build 绿（24.8s）；`scripts/ci/check-docs.mjs` **6/6**（本批无文件删除，无需 stage 处理）。
3. 逐块一致验证（桩/单测化身）：⑦同一历史"后端合并↔镜像回灌"两遍 parts 深度相等（结果区数据不丢）；⑧无空白块、条数=非 TOOL 条目数、id 空洞锁死；渲染层既有 ai-panel-parts-render（19 锁）+ AiPanel `v-if="part.result"` 未改，结果区可见性由回填后的 result 自动生效。
4. 本报告 ≤8KB 落盘。
- 禁 commit：HEAD 仍 0f46286，仅 2 文件改动（`frontend/src/stores/ai.ts`、`frontend/tests/parts-residue-cleanup.spec.ts`）。

## 说明

- 序位兜底统一在第二遍循环后执行（而非逐条即时）：保证"混排先 id 后序位"的全局序，且 flatMap 内不掺副作用。
- ⑤（responseData=null）在旧实现下即形式通过（同条回填 ?? null），新实现保持语义不变。
