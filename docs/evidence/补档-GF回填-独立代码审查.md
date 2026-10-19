> 归档来源：仓外正式报告（已脱敏）；归档日期：2026-10-19；本件为 2026-10-19 仓外文档补档批入库件，正文结论与数据未改。

# GF 只读回填修复 · 独立代码审查报告

- 审查者：`agent-1131`（独立审查子智能体，未参与施工）
- 自证：自身 wire `profile.bind`（第 5 行）—— `agentId=agent-1131`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`；第 7 行 `turn.prompt` 即本审查任务。
- 仓库：`<REPO_ROOT>`
- BASE：`527441936715eaa6bc395f1a1ced9cc0314b2604`；HEAD = 未提交工作树（`git rev-parse HEAD` 仍等于 BASE）
- 审查 diff：`<SCRATCH>/_cu-gf-fix-r26/review-full.diff`（455 行）
- 约束：未修改/提交/起停服务；未重跑测试；只写本审查包。

## 0. 范围核验

`git status --porcelain`：

```
 M frontend/src/components/AiPanel/FormRenderer.vue
 M frontend/src/stores/ai.ts
 M frontend/src/utils/parts.ts
 M frontend/tests/ai-panel-parts-render.spec.ts
 M frontend/tests/r166b-fixes.spec.ts
?? data/
```

- 与派发预期一致：仅 3 个生产文件 + 2 个既有测试文件；无新增测试文件、无后端/API/样式文件。
- `?? data/` 为施工前即存在的未跟踪目录，未进入 diff；提交时应排除。
- `git diff --check` 通过（exit 0，仅 LF→CRLF 提示）。
- 生产文件 mtime 15:16:01 为 red-e2e 后的备份恢复时刻；`sha256` 与 `src-backup/` 三文件逐一相等，且 green-full/typecheck/build（15:16:13 之后）均跑在恢复后的最终内容上。

## 1. 日志证据（只读核对，未重跑）

| 日志 | 结果 | 结论 |
|---|---|---|
| `red.log` | 2 files：**5 failed / 190 passed**（ai-panel 176 tests、r166b 19 tests） | 失败均为新增正向用例：只读先到/值先到/取消回落、帧未写值、多宿主第二宿主缺失 |
| `red-e2e.log` | 回退 3 个生产文件后单跑端到端：**1 failed**，`expected 'HB' to be 'HD'`（176 skipped） | 端到端用例确实锁住“store 已写、UI 不重灌”的缺陷 |
| `green-targeted.log` | **196 passed / 2 files** | 177 + 19，新增 10 例全绿 |
| `green-full.log` | **454 passed / 23 files**，exit 0 | 全量回归无破坏 |
| `green-typecheck.log` | `vue-tsc --noEmit`，exit 0 | 类型安全 |
| `green-build.log` | `✓ built in 31.83s`，exit 0 | 构建通过 |

补充：`red.log` 早于 ai-panel 的第 4 个新用例落盘（176 vs 177），该用例由 `red-e2e.log` 单独给出 RED 证据；10 个新增用例的 RED/GREEN 证据链完整。审查期间出现的 `gate-*.log` 为调用方后续门禁日志（454/23、typecheck/build exit 0），与上述结论一致，非本次审查执行。

## 2. 需求逐项核对

| 审查点 | 实现 | 判断 |
|---|---|---|
| 值后到重灌六型 | `FormRenderer.vue:305-317` 只读 watcher，`initialModel()` 覆盖 text/number/date/enum/boolean/multi_select；测试逐控件断言 DOM value/checked | 通过 |
| editable 不被覆盖 | watcher 在 `readonly !== true` 时直接 return；用例覆盖“编辑中输入 + initialValues 变化” | 通过 |
| cancelled/expired 回落默认 | 不在组件里特判状态；由 `AiPanel.readOnlyFormValues()` 对非 `succeeded` 返回 null，watcher/初始 model 回落默认。既有 F9 用例（ai-panel:1260）用“cancelled + 残留 submittedValues”锁定不保留半成品 | 通过 |
| 结果帧 JSON 对象 first-wins | `parts.ts:317-328` 只收 GF + 合法 JSON **对象**；`parts.ts:483-494` 仅 `status==='succeeded'` 且段上无值才写；坏 JSON/数组/null/标量/非 GF/非成功一律不写 | 通过 |
| 多宿主全匹配写回与投影 | `ai.ts:2220-2248` 遍历全部消息全部匹配段 splice，`decidedAt` 同刻、已有不覆盖，逐消息 `deriveProjections`；不碰其他 toolCallId | POST 路径通过；帧降级路径见 Important 1 |
| 潜在竞态 | 帧/POST 两种到达顺序、重投 first-wins、本地乐观终态保护均保留；409 duplicate 见 Important 2 | 基本通过 |
| 深 watch 性能 | `deep: true` 会遍历 initialValues；GF 值规模小、只在 props 变化时求值，开销可忽略 | 可接受 |
| 异常形状 | `initialModel()` 逐字段形状守卫 + 后端 `GenerativeFormGuard` 复核；helper 拒绝非对象 JSON | 通过 |
| 测试是否真实锁缺陷 | store 级断言段真值、组件级断言 DOM 控件值、端到端走 AiPanel + store；非 mock 内部字段 | 通过（缺口见 Minor 4） |

## 3. 审查结论

### Strengths

- 根因定位准确：同时覆盖“帧先到值后到”和“值先到只读后到”两种顺序，且编辑态严格不重灌。
- 帧内 `result` 只在 `succeeded` + 合法 JSON 对象时落值，失败/取消/超时/坏形状全部不伪造，安全边界清楚。
- first-wins 幂等：重复帧/晚到帧不会覆盖已有提交值；与既有 localTerminal 单调语义一致。
- 多宿主写回同时重派生投影，修掉了旧实现 `pendingCall` 可能残留的问题。
- 改动面克制：模板/样式零改动，无新依赖、无后端协议变更，向后兼容（`submittedValues` 为既有可选字段）。
- 测试质量好：断言的是用户可见控件值与段真值，不是实现细节；RED 证据真实（回退生产文件后端到端确实失败）。

### Issues

#### Critical (Must Fix)

无。

#### Important (Should Fix，均为边缘/降级路径，不阻塞本批提交)

1. **结局帧回填只写当前宿主消息；多宿主场景下 POST 应答丢失时其余宿主仍为空**
   - 位置：`frontend/src/utils/parts.ts:483-494`（帧写点只处理传入的 `message`），对照 `frontend/src/stores/ai.ts:2220-2248`（POST 路径已全量写）。
   - 问题：报告 §3.3 明确承认同一 toolCallId 可能残留在多条消息；当 POST 应答丢失/失败、结局帧成为唯一证据时，帧只会给“宿主消息”落值，其余宿主只读卡仍是 schema 默认值。
   - 影响：修复目标“所有宿主卡面都有值”在该降级路径上只完成一半；常规提交路径不受影响（POST 成功会全量写）。
   - 建议：在 store 的 `frontend_tool_result` 分支里，帧为 GF + succeeded + 解析出对象时，复用与 `writeSubmittedValues` 相同的“全部匹配段 + first-wins”逻辑（可导出解析 helper 或加一个 action），并补“两宿主 + 只来帧”的用例。

2. **409 DUPLICATE 仍以本地值写回，与“帧 first-wins”叠加后可能固化非权威值**
   - 位置：`frontend/src/stores/ai.ts:2284-2289`（`outcome.ok || outcome.duplicate` 都调用 `writeSubmittedValues(local values)`）；`frontend/src/utils/parts.ts:483`（帧 first-wins）。
   - 问题：本地值先落 ⇒ 后到帧因 first-wins 不纠正；帧先落 ⇒ duplicate 分支用本地值覆盖。若两边值不同，只读卡会显示后端并未接受的值。注释里“POST 先落或他端提交的同值”的“同值”没有被任何代码保证。
   - 现实约束：R-166B 页签锁使同一会话通常只有一个写者，同页重试的值也相同，实际触发概率低，故列为非阻塞。
   - 建议：duplicate 分支不要用本地 values 覆盖（依赖帧/history），或让 `writeSubmittedValues` 也 first-wins；补一条 duplicate 两种顺序的用例。

#### Minor (Nice to Have)

1. **直接 `splice` 绕过了 `upsertToolPart`/`writeToolPart` 单一写口** —— `frontend/src/stores/ai.ts:2230-2240`。当前合并语义与 `writeToolPart` 相同，但未来该写口若增加不变量（封口/规范化），此处会漂移；建议抽一个“按索引写段”的共享 helper。
2. **watcher 未包含 `props.schema`** —— `frontend/src/components/AiPanel/FormRenderer.vue:313-317`。当前 schema 变化总是伴随 initialValues 变化（段对象被替换），但“schema 变、值不变”时 model 不会重建；属理论缺口。
3. **`deep: true` 的面比需要更宽** —— 当前所有写路径都是替换对象、不是原地改嵌套字段；若未来 `submittedValues` 承载大结构，深遍历是无谓开销。可用浅 watch（依赖 prop 身份）替代。
4. **测试缺口**：未断言 `deriveProjections` 的投影结果（`pendingCall` 清空 / `toolRuns` 更新）；未覆盖“同一条消息内重复段”；新增帧写值用例只测了 `REJECTED`，未显式覆盖 `TIMEOUT`/`FRONTEND_CANCELLED`（状态映射已有独立测试，风险低）。
5. **注释准确性** —— `frontend/src/utils/parts.ts:481-482` 的“他端提交的同值”是假设，非保证；建议改为“以先落者为准”并指向 Important 2。

## 4. Declined to judge（显式排除）

- 只读视觉（灰底/锁标记/对比度）：用户已明确“暂缓，不一定做”，本批模板与类名零改动。
- `markFormDecided` 仍只写首宿主：`decidedAt` 为数据层记录、卡面不渲染，报告已刻意除外，无用户可见差异。
- 2A 实时超时链路、failed 真实 UI：派发明确不并入本批。
- `applyPendingTerminal` / `applyFrontendToolTerminal` 的 `findIndex` 首段语义：本次未改；在既有写口下同一消息内 toolCallId 唯一。
- history 路 `JSON.parse` 兜底（`stores/ai.ts:1177-1185`）：未改，既有 M1 用例覆盖。
- `data/` 未跟踪目录：与本次改动无关，提交时排除即可。
- 后端/协议：未改，仅只读核对 `ConfirmGate#submitFrontendResult`、`SseChatEmitter#frontendToolResult` 确认 `result` 确为提交值原文。

## 5. Assessment

**Ready to merge? READY — 可提交。**

**Reasoning：** 无 Critical；目标缺陷（只读卡数据回填、帧内值丢弃、多宿主 POST 写回）已被 RED→GREEN 与端到端用例真实锁定，全量 454/23、typecheck、build 均通过，改动面与预期完全一致。Important 1/2 属多宿主降级路径与跨写者重复提交的边缘一致性问题，不构成本次修复的回归，可作为紧邻的跟进项；建议提交时不带 `data/`，并在提交说明中标注“仅 GF 只读数据回填，不含只读视觉”。
