> 来源=agent-219/wire.jsonl（末条 assistant 消息）（2026-10-26 归档）· 规格复核补正（撤回初稿「无授权」结论；空值/IN[] 语义反转完整链）
> 性质：本次只读分析/调研的**脱敏副本**，属历史证据；为**分析稿**，结论以 `docs/research/` 总册与「规格复核补正」为准。仅做本机路径/仓库名/个人用户名占位与空白清理，**技术结论、计数、状态词一字未改**；另按入库口径把两处用户原话改为**脱敏转述**（保留 `wire` 行号锚点，结论不变）。

## 1）全量历史落库的用户授权

**判定：有用户授权（功能级）；但“无 TTL / 永久保存”的保留期未见明确表述，属授权范围不明（用户把设计委给“按业界主流 / 最佳裁决”）。**

当前会话证据（`wire.jsonl` 完整路径：`<KIMI_SESSION>/agents/main/wire.jsonl`；**用户原话不入库，以下仅作脱敏转述并保留行号锚点**）：

- `:2390` 助手列出待批 5 项，其中第 2 项原文为：**“完整历史独立存储与分页：第 41 条起会从用户历史真正消失。需把完整历史与 40 条模型记忆分开，不是简单调大窗口。”**
- `:2438` 用户对该批的答复（**脱敏转述**）：第 1、2、3、5 项由助手按业界主流作最佳裁决并直接实施，第 4 项挂起、先登记、暂不实施。
  —— 第 2 项即“完整历史独立存储与分页”，因此**有直接用户授权**。
- `:2492` 助手记录：**“收到：1、2、3、5 按主流最佳实践直接裁决实施；4 只登记挂起。”**
- `:4695` 用户就**删除范围**的约定（**脱敏转述**）：会话销毁时应把该会话相关的 AI 数据一并删除，业务数据不受影响。
  —— 这是**删除范围**的约定，不是对“永久保存 / 不设 TTL”的明确批准。

补充边界：

- 检索到的用户表述中，**没有**出现“永久保存 / 不做 24h 过期 / 保留期 N 天 / 不设 TTL”这类明确内容；保留期是用户交给助手按“业界主流 / 最佳裁决”决定的，**授权范围到此为止**。
- 代码 `V8__ai_chat_message.sql:3` 的“边界（2026-10-25 裁决）”是**代码自述**，能对应到上述功能级授权，但**不能单独证明用户批准了“无 TTL”**。
- 上一轮我把“看板没有登记”写成“没有用户授权”，该结论**撤回**：看板缺席只能证明没有回写，不能证明未授权。
- 当前会话已找到直接授权表述，因此未再检索上一会话；时间上 `:2438`（2026-10-25 08:42）早于 `20c56bb`（同日 15:11），授权在实现之前。

---

## 2）`set_condition` 空值 / 重复字段是否变成无条件全量导出

**判定：存在完整静态链路，代码可证、未运行验证；但机制不是“`EQ ""` 本身等于全量”，而是“后端闸门接受 → 前端草案把空值当未填 → `persistStepData` 用 `{}` 覆盖 → 后端空条件对每行返回 true”。**

### 完整链路

1. **后端闸门接受空值**
   `backend/src/main/java/com/example/configmgr/ai/tool/WizardActionGuard.java:327-340`：
   - 只拒绝 `value == null`；`""` 不是 null ⇒ 通过；
   - `IN` 只拒绝“非数组”；`[]` 是数组 ⇒ 通过；
   - `EMPTY/NOT_EMPTY` 直接通过。
   `:302-322` 对 `fieldCode` 只查“是否属于该定义”，**没有重复检测**；`:249-252` 只在“没有任何被接受的字段且无 scopeKeys”时才拒绝。
   因此 `EQ ""`、`CONTAINS ""`、`IN []` 都能过闸。

2. **页面 handler 先按原样落库**
   `frontend/src/views/ExportWizardView.vue:1663-1686`：
   - `:1672` 解析 AI 传来的 `conditions`；
   - `:1673` 用 `draftModelFromCondition(condition)` 生成表单草案；
   - `:1684` **把原始 condition 再 `setCondition` 落库**（不是压平后的结果）。
   所以此刻服务端可能是 `EQ ""`，不是 `{}`。

3. **前端压平丢弃空值**
   `frontend/src/types/condition.ts:169-196`：`value-bearing` 算子的值 `trim()` 后为空 ⇒ `continue`，该字段被丢弃；`IN []` 经 `draftValue`（`:397-404`）变成 `""`，同样被丢弃。
   `:200-210`：没有 scopeKeys、没有 fields ⇒ 返回 `null`。
   `:295-296`：`stringifyCondition(null)` 返回 **`"{}"`**。

4. **`startExport` / `goStep` 用 `{}` 覆盖**
   `ExportWizardView.vue:778-779`：`persistStepData` 对每个已选配置重新 `buildQueryCondition(model)` 并 `setCondition(..., stringifyCondition(condition))`。
   调用点：`startExport` 的 `:887`、`goStep` 的 `:668`。
   因此只要 AI 先 `set_condition`、随后 `start_export` 或用户点“下一步/开始导出”，服务端条件会被覆盖成 `"{}"`。

5. **后端把 `{}` 当“无条件”**
   `backend/src/main/java/com/example/configmgr/data/service/ConfigDataService.java:83-94`：`"{}"` 不是 blank，解析为非 null 的空 `QueryCondition`。
   `backend/src/main/java/com/example/configmgr/data/service/ConditionEvaluator.java:60-85`：无 scopeKeys、无 fields ⇒ `matches` 返回 **true**，逐行全通过。
   `ExportJobRunner.java:166` 使用同一 `parseCondition`，因此导出全量。

### 按算子区分

| 输入 | 闸门 | 原样落库后的后端语义 | 经前端压平后 |
|---|---|---|---|
| `EQ ""` | 接受 | `s.equals("")`，只匹配**值为空字符串**的行（`:98-99`） | 字段被丢弃 → `{}` → 全量 |
| `CONTAINS ""` / `LIKE ""` / `STARTS_WITH ""` | 接受 | 对非 null 值通常恒真（`contains("")`/`startsWith("")`） | 字段被丢弃 → 全量 |
| `GT/GTE/LT/LTE ""` | 接受 | 字符串比较，结果依数据而定 | 字段被丢弃 → 全量 |
| `IN []` | 接受（数组） | `anyMatch` 恒 false ⇒ **0 行**（`:116-124`） | 被丢弃 → `{}` → **全量**（0 行反转为全量） |
| `IN` 非数组 | 拒绝（`:336-339`） | — | — |
| `BETWEEN` | 拒绝（不在 `OPERATORS`，`:313-317`） | 后端无此算子 | 表单层展开为 GTE+LTE，AI 直接传会被拒 |
| 同字段重复 `fieldCode` | 接受（无去重） | 原样落库时两条都在 | `draftModelFromCondition` 按字段分组：GTE+LTE 合并为 BETWEEN，否则**只取第一条**（`:413-442`）；后续 `persistStepData` 覆盖 |

### 精确结论

- **`EQ ""` 本身不是“全量”**：后端 `EQ` 只匹配空字符串值，不是所有行；全量来自“前端把它当未填 → 丢弃 → 覆盖为 `{}`”。
- **`IN []` 更直接**：原样落库是 0 行，压平后反而变成全量，属于语义反转。
- **重复 `fieldCode` 单独不必然全量**：通常表现为“后一条被静默丢弃 / 只保留第一条”；只有“保留下来的第一条也是空值”时才落到全量。
- **无 `scopeKeys` 时才是全量**；若 AI 同时给了非空 `scopeKeys`，结果是“该范围内不筛字段”，不是整表。
- 该路径**静态代码完整**，标“确定（代码可证）”，不是“仅函数名或测试名”；但本次未运行，不能写成“已实测复现”。

---

## 3）“对话不落库 vs 永久历史”的规格冲突（与授权分开）

**判定：规格冲突成立；它与“有没有用户授权”是两件事。授权存在（见第 1 节），冲突仍然成立。**

### 裁决原文

`docs/决策看板.md:89`：

> **“AI 对话数据不落库（2026-10-21 用户直裁）”** ｜ 🏛️ ｜ **“对话内容 / 工具入出参 / 决策元数据 / 取消记录只在 Redis、随会话共亡，不落任何持久层”**；业务数据不受此限；可观测性的持久层只许“指标与元数据”，不许正文、不许工具入出参。

### 表结构与实现原文

`backend/src/main/resources/db/migration/V8__ai_chat_message.sql`：

- `:3-5`：**“边界（2026-10-25 裁决）：……用户可见的完整历史落本表，不随窗口裁剪；本表不复用 `app.ai.session-ttl` 的运行态 TTL，不做 24h 过期。”**
- `:19`：**“删除会话 = 物理 `DELETE FROM ai_chat_message WHERE session_id = ?`”**（唯一的清理口，未见定时清理）。
- `:23-33`：表列含 `text CLOB`、`tool_calls_json CLOB`、`tool_responses_json CLOB`、`metadata_json CLOB`；`:35` 只有 `created_at`，**没有过期列/TTL 列**。

`backend/src/main/java/com/example/configmgr/ai/history/AiChatMessage.java:21-22`：

> **“本表保存同一会话的全部消息，不随窗口消失，也不复用运行态 TTL。”**

`:33-35`：

> **“`toolCallsJson` / `toolResponsesJson` / `metadataJson` 按原文 JSON 保存……`metadataJson` 保留 `reasoningContent` 等供应商元数据，未知键也原样保存。”**

`backend/src/main/java/com/example/configmgr/ai/history/AiChatHistoryService.java:41-42`：

> **“`ChatMemory`（Redis）只负责‘给模型看最近 40 条’；本服务负责‘给用户看全部’。……本表不设 TTL，删会话才物理删除。”**

`:235-240`：`deleteSession` 只做 `deleteBySessionId`，是唯一删除入口。
`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:51`：会话销毁时才删 `ai_chat_message` 的该会话行。

### 分开表述

- **规格冲突**：10-21 的 🏛️ 裁决仍有效、看板没有 supersede 行；`V8:3-5` 却自称“2026-10-25 裁决”并实现“不随窗口、不做 24h 过期”的持久面。冲突在“旧裁决未废止 / 新裁决未登记”这一层，**不取决于用户是否授权功能**。
- **授权**：`wire:2438` 批准的是“完整历史独立存储 + 游标分页”；`wire:4695` 只约定“会话销毁删除全部 AI 相关数据”。后者**不等于**批准“永久保存 / 无 TTL”；保留期属于“授权范围不明（委由助手按业界主流裁决）”。
- **附带基线冲突**：`docs/02-需求设计文档-v2.2-冻结版.md:117` 仍写“消息历史存 Redis、默认 6 小时、无长期留存”，`docs/03-技术方案文档-v2.1.md:291` 仍写“6h、到期键自行消失”；与 `V8` 的“无 TTL、删会话才删”也不一致。
