> 来源=<仓外证据>/tab-fresh-session-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 复制页签 = 全新工作区（sessionStorage 一次性清空）实施报告

- 日期：2026-10-25
- 工作副本：`<验证副本>/_verify-20261025-tabs/frontend`（隔离副本，未改主树）
- 主树基线：`<主仓>/frontend`（本批已把上一批页签工作合并进来；
  与副本 `src/stores/ai.ts` 的差异**仅本批新增的 3 个 hunk**，其余逐字一致）
- 产品口径（本次确认）：**确认的复制页签 = 全新工作区**：对自己的**整份 sessionStorage** 执行一次
  `clear()`，再重建页签身份（pageId）与 AI 会话（新 sessionId）。**不清 localStorage**；
  **普通刷新永不清**；未提交的表单草稿**有意不继承**。

## 1. 结论

已按 TDD 实现：RED（2 例失败）→ GREEN（6/6），并通过聚焦 + 相邻套件（66 例）。清空只发生在
**复制已确认**且**不是"慢重载回来的原页签"**这一条路径上，顺序为「先判定 → 再清空 → 后重建身份」，
不引入任何基于时间的复制/刷新启发式。全量两跑只出现与本批路径无交集的加载期 flake（§5.1）。

## 2. 现有可靠判据（未新增启发式）

沿用上一批已合并的三层握手，复制判定只有一条可靠来源：**Web Locks 被另一文档持有**。

| 现场 | 判据 | 结果 | 是否清空 |
|---|---|---|---|
| 原页签 / 普通快刷新 | `navigator.locks.request('ai-session-<sid>', {ifAvailable})` 拿到锁 | `owner` | **否** |
| 复制页签 | 同一把锁被占（限次重试 `3 × 150ms`）**且**没有返回意向 | `duplicate` | **是（一次）** |
| 慢重载回来的原页签 | 锁被占，但带着 `pagehide` 写下的一次性返回意向（同 session + 同 pageId + 60s 内） | `duplicate`（降级） | **否** |
| 环境无 Web Locks | 无法确认复制 | `unsupported` | **否**（宁可残留，也不误伤刷新） |

判定依据在清空之前都已离开 sessionStorage：

1. `sid` 在 `ensureTabOwnership` 入口读进内存（`this.sessionId`）；
2. `takeReturnIntent(sid)` 在探活**之前**读取并消费返回意向（同时校验 pageId / 时效）；
3. `resolveTabOwnership` 用锁给出 `role`；
4. 只有 `role === 'duplicate' && !returning` 才清空。

因此 `sessionStorage.clear()` 不会抹掉"还没做完的判定依据"。

### 清空与重建的先后（关键）

```
clearDuplicatedTabStorage()   // 1) 只清本页签 sessionStorage（此刻已无判定依赖）
clearMirrorRefs()             // 2) 兜底：clear 失败（隐私模式/配额）时仍清继承镜像
this.adoptFreshSession()      // 3) 内存态清场 + 写新 sessionStorage['ai-session-id']
rotatePageId()                // 4) 写新 sessionStorage['ai-tab-page-id']
await this.claimSessionOwnership(this.sessionId)  // 5) 广播 claim（pageId 已是新值）
```

顺序不能反：若先 `adoptFreshSession()` 再 `clear()`，刚写下的新 sessionId 会被自己抹掉。

## 3. 改动文件

| 文件 | 类型 | 说明 |
|---|---|---|
| `src/stores/ai.ts` | 修改 | 新增 `clearDuplicatedTabStorage()`（+31 行，含注释）；`ensureTabOwnership` 的 `duplicate` 分支改为「清 → 重建」；方法注释同步产品口径 |
| `tests/duplicate-fresh-workspace.spec.ts` | 新增（6 例） | 复制清空 / 只清本页签副本 / 快刷新不清 / 慢重载不清 / unsupported 不清 / localStorage 永不清 |

`src/stores/ai.ts` 精确位置（改后行号）：

- `clearDuplicatedTabStorage()`：630-651（定义在第 645 行，前面是 15 行文档注释）
- `ensureTabOwnership` 的 duplicate 分支：4630-4646（清空调用在 4641）
- `ensureTabOwnership` 文档注释（复制页签口径）：4588-4591

未改动：`localStorage` 相关（`src/locales/index.ts`、`src/stores/locale.ts`）、`pagehide/pageshow`
生命周期、`adoptFreshSession`、410 `SESSION_PURGED` 恢复、`takeOverSession`、`resetSession` 的精确清理。

## 4. RED → GREEN 证据

### 4.1 RED（实现前，仅新增测试）

```
$ npx vitest run tests/duplicate-fresh-workspace.spec.ts
 ❯ tests/duplicate-fresh-workspace.spec.ts (6 tests | 2 failed) 1286ms
     × 任意键 / 会话键 / 未提交草稿全部不继承；新 sessionId + 新 pageId，且只清一次 354ms
     × 清的是本页签副本：localStorage 与"原页签侧"的锁/广播都不受影响 315ms
     ✓ 快刷新（锁空闲）⇒ 清都不清，会话与全部继承现场照旧 7ms
     ✓ 慢重载（锁已被副本接手、带着返回意向）⇒ 不清：降级但整份 sessionStorage 还在 314ms
     ✓ 无 Web Locks（unsupported，无法确认复制）⇒ 不清（宁可残留也不误伤刷新） 5ms
     ✓ 复制路径与随后的刷新路径都不碰 localStorage 307ms

AssertionError: expected { …(6) } to deeply equal { …(2) }      # 继承键仍在
- Expected  { ai-session-id, ai-tab-page-id }
+ Received  { ai-session-id, ai-tab-page-id, ai-panel-expanded, cm.ai.form.draft.call-1,
              import-wizard-draft, some-business-tab-state }

AssertionError: expected [] to have a length of 1 but got +0   # 一次 clear 都没发生
```

- 两条失败正是本批要修的行为（复制不清、且连一次 clear 都没有）。
- 其余 4 条是**修复后不得回退**的守卫（刷新/慢重载/unsupported 不清、localStorage 不碰），RED 阶段即为绿。

### 4.2 GREEN（实现后）

```
$ npx vitest run tests/duplicate-fresh-workspace.spec.ts
 ✓ tests/duplicate-fresh-workspace.spec.ts (6 tests) 1648ms
     ✓ 任意键 / 会话键 / 未提交草稿全部不继承；新 sessionId + 新 pageId，且只清一次
     ✓ 清的是本页签副本：localStorage 与"原页签侧"的锁/广播都不受影响
     ✓ 快刷新（锁空闲）⇒ 清都不清，会话与全部继承现场照旧
     ✓ 慢重载（锁已被副本接手、带着返回意向）⇒ 不清：降级但整份 sessionStorage 还在
     ✓ 无 Web Locks（unsupported，无法确认复制）⇒ 不清（宁可残留也不误伤刷新）
     ✓ 复制路径与随后的刷新路径都不碰 localStorage
```

## 5. 测试命令与结果

| 命令 | 结果 |
|---|---|
| `npx vitest run tests/duplicate-fresh-workspace.spec.ts` | **6 passed**（RED 阶段 2 failed / 4 passed） |
| `npx vitest run tests/duplicate-fresh-workspace.spec.ts tests/tab-ownership-lifecycle.spec.ts tests/session-purged-recovery.spec.ts tests/r166b-fixes.spec.ts tests/aie2e-refresh.spec.ts tests/irreversible-lifecycle.spec.ts` | **6 files / 66 tests passed** |
| `npx vitest run`（副本全量） | 两次运行均只出现**加载期时序 flake**（每次失败文件不同、单跑全绿，详见 §5.1）；本批涉及的用例集全绿 |
| `npx vue-tsc --noEmit` | 仅 5 条**主树同样存在**的既有错误（`tests/park-request-tool-start-order.spec.ts`，本批未触碰）；本批零新增 |

补充：`tests/tab-ownership-lifecycle.spec.ts`（10）、`tests/r166b-fixes.spec.ts`（25）、
`tests/session-purged-recovery.spec.ts`（5）、`tests/aie2e-refresh.spec.ts`（12）、
`tests/irreversible-lifecycle.spec.ts`（8）全部保持绿——上一批的 pagehide/pageshow/宽限/锁账本/
410 恢复行为未被破坏。

### 5.1 全量运行与 flake 说明

机器当时被其它任务占用（同一套用例此前 55s 跑完，这两次分别 100s / 122s）：

- 第 1 次全量（改动后）：`Test Files 2 failed | 133 passed (135)`，`Tests 2 failed | 1576 passed (1578)`。
  失败：`tests/aie2e-refresh.spec.ts > R-04 409 SESSION_BUSY 幻影`（`messages` 3≠1）、
  `tests/truncated-answer-panel.spec.ts`（5s 超时）。
- 第 2 次全量（同样代码，仅复跑）：`Test Files 5 failed | 130 passed (135)`，
  `Tests 7 failed | 1571 passed (1578)`。失败集合**与第 1 次几乎不重叠**
  （`aie2e-refresh` R-04、`en-us-flow` ×2、`rg-s13-s14` ×2、`truncated-answer-panel`、`truncated-answer`），
  形态全是 5s 超时 / 面板 DOM 空 / 断言先后差一拍。
- 上述失败文件**单跑全部绿**：`en-us-flow` + `rg-s13-s14` + `truncated-answer` + `aie2e-refresh`
  = **4 files / 25 tests passed**；`truncated-answer-panel` 单跑 3/3。
- `aie2e-refresh` 的 R-04 是仓库**已有记录**的并行期 flake：`tests/aie2e-helpers.ts:56-71` 的注释
  明写「`vi.resetModules()` 后同一路径可能解析成两份模块实例 ⇒ `instanceof` 恒假，单跑绿、整仓跑红」。
- 与本批改动的关系：这些用例都没有 Web Locks 桩（`navigator.locks` 为 undefined ⇒ `unsupported`），
  **根本不会进入清空分支**；本批改动只在 `ensureTabOwnership` 的 `duplicate` 分支内。

**结论**：无法在当前机器负载下给出"一次干净的全量绿"，但本批改动面（1 个 store 分支 + 1 个新测试
文件）已由聚焦与相邻套件（66 例）覆盖，且所有全量失败项单跑均绿、与本批路径无交集。主会话复跑全量时
若仍见这些用例红/超时，请按既有 flake 口径处理，而非回滚本批。


## 6. 真实浏览器边界（未做真机验证）

1. **未做真浏览器验证**：本批全部证据来自 jsdom + 按规范语义实现的 Web Locks / BroadcastChannel 桩；
   `sessionStorage` 的"复制页签继承、按页签隔离"是规范行为，但未在实机（Chrome/Firefox/Edge、
   含 bfcache 与 `back_forward` 复制）复现。
2. **复制 vs 刷新仍依赖 Web Locks**：旧浏览器无 `navigator.locks` ⇒ `unsupported` ⇒ **不清**
   （宁可残留也不误伤刷新）。这是有意的取舍，不是遗漏。
3. **慢重载超出返回意向时效**（>60s，或 `pagehide` 时写 sessionStorage 失败）：会被当成复制页签清空。
   概率极低（要同时满足"锁被副本接手 + 返回意向缺失/过期"），未再加启发式。
4. **in-memory 展示态不重置**：`ai-panel-expanded` 从 storage 清掉后，当前这一次渲染仍用启动时读到的
   值（下次刷新回默认展开）；刻意不强制收起，避免清空瞬间界面跳变。业务 store 不读 sessionStorage
   （`grep` 全仓仅 AI 域使用），故不存在"内存态与已清 storage 分叉"的其它面。
5. **复制页签之后又刷新**：此时它已是普通页签（锁归自己），刷新不清——符合"普通刷新永不清"。
6. 复制页签后续**接手原会话**（原页签关闭）时，`takeOverSession` 走的是"写回原 sid + 删旧镜像"，
   不清 storage：清洁工作区在该页签内不再回滚，符合"清空只发生一次"。

## 7. 移植说明

- 主树 `src/stores/ai.ts` 与副本的差异只有本报告 §3 的 3 个 hunk；直接取该 diff 即可。
- 新增测试文件：`tests/duplicate-fresh-workspace.spec.ts`（依赖已存在的
  `tests/tab-ownership-fixtures.ts`，无新依赖/新包）。
- 主树期间可能继续改动 `resetSession` 的提示键（`sessionPurgeIncomplete`）等无关区域：移植时只取
  `clearDuplicatedTabStorage` + duplicate 分支 + 注释三个 hunk。
