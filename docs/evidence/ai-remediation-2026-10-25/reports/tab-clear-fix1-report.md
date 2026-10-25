> 来源=<仓外证据>/tab-clear-fix1-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 复制页签任务 · Important 评审修复 I1（探活 API 失败不得被判成"确认复制"）

- 日期：2026-10-25
- 结论：**DONE**（仅修 I1 + 直接相关的 'confirmed' 文档措辞；其余 12 条 Minor 未处理）
- 实施副本：`<验证副本>/_verify-20261025-tab-clear-final/frontend`（未改主树、无子代理、未装包）
- 主树基线（生成补丁时点）：`<主仓>`，其
  `frontend/src/stores/ai.ts` 与新 spec 已含上一批复制页签改动；本修复补丁可直接 `git apply`

## 1. 问题与修法

**I1**：`probeSessionLock` 把 `navigator.locks.request(...)` 的 **promise reject** 归为
`'occupied'`；`resolveTabOwnership` 重试 3 次后返回 `'duplicate'`，`ensureTabOwnership` 视其为
"已确认复制"并执行不可恢复的 `sessionStorage.clear()`。即"问不到锁"被当成"别人持有锁"。

**最小 fail-closed 修法（3 处代码行 + 文档）**：

| 位置（修后行号） | 改动 |
|---|---|
| `src/stores/ai.ts:468` | `.catch(() => settle('occupied'))` → `.catch(() => settle('unsupported'))` |
| `src/stores/ai.ts:568` | `verdict === 'unsupported'` 时 `resolveTabOwnership` 返回 `'unsupported'`（原 `'unknown'`）—— 与"无 Web Locks"同一条保守终态 |
| `src/stores/ai.ts:434-436`、`:561-562` | 探活/判定文档：API 失败只说明"无法确认"，**绝不**是 `occupied`；`duplicate` 只能由每次探活都真实收到 `null` 回调得出 |
| `src/stores/ai.ts:641-642` | 新函数上"用 Web Locks **确认**"改为"**证实**"：每一次 `ifAvailable` 探活都真实收到 `null` 回调；API 失败不构成复制证据 |

语义结果：

- 探活 API reject / 同步抛错 → `probeSessionLock='unsupported'` → `resolveTabOwnership='unsupported'`
  → `store.tabOwnership='unsupported'`（终态，`ensureTabOwnership()` 后续返回 `null`）；
  **不换 sessionId/pageId、不清空、不判 duplicate、不重试**。
- **真复制品**（每次探活都收到 `null`）→ `duplicate` → 清空一次、新 pageId + 新 AI 会话，路径逐字未变。
- **刷新竞态**（occuped 1–2 次、第 3 次空闲）→ `owner`，不清空 —— 3×150ms 重试语义未动。
- 普通刷新 / bfcache / localStorage / 原页签 / 服务端行为未动。

> 说明：同步抛错路径此前已 `settle('unsupported')`，但 `resolveTabOwnership` 会把它降成 `'unknown'`；
> 本次把 unsupported 映射为终态 `'unsupported'` 后，该路径与 reject 路径一致地 fail-closed
> （身份不变、不清空、不再重探）。这是同一映射的一行结果，不是额外功能。

## 2. 修改文件（精确）

| 文件 | 类型 | 差异 |
|---|---|---|
| `frontend/src/stores/ai.ts` | 修改 | 4 个 hunk：+11 / −3 行（其中 2 行是代码语义，其余文档） |
| `frontend/tests/duplicated-tab-clear.spec.ts` | 修改 | +73 行：2 个测试桩类、2 个用例，共 10 例（原 8 例） |

未改动：`tests/tab-ownership-fixtures.ts`、`src/utils/form-draft.ts`、任何组件/类型/配置/依赖；
评审其余 Minor（M1、M3–M12）均未动。

## 3. TDD 证据

### 3.1 RED（先加测试，实现未动）

```
$ npx vitest run tests/duplicated-tab-clear.spec.ts
 ❯ tests/duplicated-tab-clear.spec.ts (10 tests | 1 failed) 3225ms
     ✓ 同一会话的锁被另一文档持有 + 无返回意向 ⇒ duplicate，且只清一次
     ✓ 普通刷新 / 快重载（锁空闲）⇒ owner，一次都不清，全部继承现场照旧
     ✓ 慢重载（带一次性返回意向、锁已被副本接手）⇒ 降级但不清：整份现场还在
     ✓ 环境无 Web Locks（无法确认复制）⇒ unsupported，不清
     × 探活 API reject（无法确认）⇒ unsupported，绝不判复制、绝不整份清空
     ✓ 锁占 1–2 次后第 3 次空闲（旧文档锁延迟释放）⇒ owner，绝不清空
     ✓ 继承来的会话镜像、未提交草稿、业务页签键全部不继承；只留新的两个身份键
     ✓ 只清本页签副本：localStorage、原页签的锁与广播、服务端会话都不动
     ✓ 复制页签随后刷新（已是普通页签）⇒ 不再清第二次
     ✓ bfcache 恢复（pagehide.persisted / pageshow）⇒ 不清，现场原样保留

FAIL  … > 探活 API reject（无法确认）⇒ unsupported，绝不判复制、绝不整份清空
AssertionError: expected 'duplicate' to be 'unsupported'
Expected: "unsupported"
Received: "duplicate"
```

- 失败项正是 I1：reject 被当作 occupied ⇒ 3 次后判 duplicate。
- 第 2 条新用例（占用 1–2 次、第 3 次空闲 ⇒ owner）在 RED 阶段即为绿：它是**既有重试竞态的回归锁**，
  不是新缺陷；修复后必须继续绿，防止"把重试砍成 1 次"的回归再次把慢刷新推向清空。

### 3.2 GREEN

```
$ npx vitest run tests/duplicated-tab-clear.spec.ts
 ✓ tests/duplicated-tab-clear.spec.ts (10 tests) 2808ms
 Test Files  1 passed (1)
      Tests  10 passed (10)
```

## 4. 测试 / 类型检查 / 构建

| 命令 | 结果 |
|---|---|
| `npx vitest run tests/duplicated-tab-clear.spec.ts` | **10 passed**（RED：1 failed / 9 passed） |
| `npx vitest run tests/duplicated-tab-clear.spec.ts tests/tab-ownership-lifecycle.spec.ts tests/r166b-fixes.spec.ts tests/session-purged-recovery.spec.ts tests/irreversible-lifecycle.spec.ts` | **5 files / 58 tests passed** |
| `npx vue-tsc --noEmit` | **exit 0**，零错误 |
| `npm run build` | **exit 0**，`✓ built in 1m 8s` |
| 真实 Chromium 探针（新 build，14 项断言） | **14/14 passed** |

日志：
`<仓外证据>/tab-clear-fix1-tests.log`、
`<仓外证据>/tab-clear-fix1-typecheck.log`、
`<仓外证据>/tab-clear-fix1-build.log`、
`<仓外证据>/tab-clear-fix1-real-browser.log`。

真实浏览器复跑（端口 5201；`BASE` 环境变量可覆盖探针地址）仍全绿，确认修复没有削弱真复制路径：
复制页签被识别并只保留新身份键 + 新会话镜像、旧镜像/未提交草稿/业务键消失、localStorage 原值保留、
原页签 session/pageId/镜像/草稿/锁与渲染全部原样、复制页签随后普通刷新不丢现场。
探针未覆盖 reject 分支（真实 Chromium 正常不会 reject），该分支由 jsdom 单测覆盖。

## 5. 交付物

均在 `<仓外证据>/`：

| 文件 | 说明 |
|---|---|
| `tab-clear-fix1.diff` | **git-apply 兼容补丁**，相对当前主树（两个文件）。已在主树执行 `git apply --check` → **rc 0** |
| `tab-clear-fix1-ai.ts.full` | 修复后 `frontend/src/stores/ai.ts` 完整文件 |
| `tab-clear-fix1-duplicated-tab-clear.spec.ts.full` | 修复后测试完整文件（10 例） |
| `tab-clear-fix1-*.log` | 上述测试/类型检查/构建/真机日志 |

补丁 hunk（`tab-clear-fix1.diff`）：

```
frontend/src/stores/ai.ts
  @@ -430,6 +430,10 @@   probeSessionLock 文档：API 失败 ≠ occupied
  @@ -461,7 +465,7 @@   .catch(... → unsupported)
  @@ -553,12 +557,15 @@ resolveTabOwnership：unsupported 终态 + 文档
  @@ -631,7 +638,8 @@   clearDuplicatedTabStorage："证实"措辞
frontend/tests/duplicated-tab-clear.spec.ts
  @@ -104,6 +104,38 @@ 两个测试桩类
  @@ -175,6 +207,47 @@ 两个新用例（reject→unsupported；第 3 次空闲→owner）
```

应用（主树当前工作区已含上一批改动）：

```
cd <主仓>
git apply --check <仓外证据>/tab-clear-fix1.diff   # rc 0
git apply <仓外证据>/tab-clear-fix1.diff
```

## 6. 边界与未做事项

- 只处理 I1 + 直接相关的 'confirmed' 文档（M2 语义）；**M1、M3–M12 未处理**，未新增白名单、
  未改 `clear()` 兜底、未动 `ai-panel-expanded` 内存态、未加 in-flight promise 记忆。
- 未重跑全量 `vitest run`（本轮要求为新 spec + 相邻 tab 套件 + typecheck/build）；上一轮全量
  1574/1580 的证据与 flake 定性仍适用于未改动路径，新增 2 例仅在本轮聚焦/相邻套件中执行。
- 未修改主树：主树工作区在修复前后保持一致（上一批 `ai.ts`/新 spec 的在途改动仍在，未由本代理写入）；
  主树 `git status` 中其它代理的 `src/utils/parts.ts`、`tests/park-request-tool-start-order.spec.ts`
  未触碰。
- 真实浏览器仍未验证"浏览器原生复制页签动作"本身（沿用上一轮的状态等价复现与免责声明）。
