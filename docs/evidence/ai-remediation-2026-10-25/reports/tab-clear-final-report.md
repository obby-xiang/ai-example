> 来源=<仓外证据>/tab-clear-final-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 复制页签 = 全新工作区（确认复制后一次性清空 sessionStorage）最终实施报告

- 日期：2026-10-25
- 结论：**DONE**
- 工作副本（隔离，未改主树）：`<验证副本>/_verify-20261025-tab-clear-final/frontend`
- 主树基线：`<主仓>/frontend`（复制时点：2026-10-25 16:29）
- 依赖：未安装任何包；副本 `node_modules` 是指向主树已装依赖的 **NTFS Junction**
  （`<验证副本>\_verify-20261025-tab-clear-final\frontend\node_modules` → `<主仓>\frontend\node_modules`）
- 上一轮 `_verify-20261025-tabs` 的产物**未读取/未复用/未拷贝**；本批实现与测试均从当前主树独立重做

---

## 1. 产品口径落地

**确认的复制页签 = 全新工作区**：新页签先对自己的整份 `sessionStorage` 执行**一次** `clear()`，
再重建页签身份（pageId）与 AI 会话（sessionId）。

| 约束 | 实现 |
|---|---|
| 先证明判据，再清空 | 新增判据守卫用例；确认既有 Web Locks 判据可用，**未改判据、未加时间启发式** |
| 只在复制确认后清空 | 清空仅在 `role === 'duplicate' && !returning` 分支 |
| 只清一次 | `tabOwnership` 幂等闸门 + 分支内单次调用；后续刷新走 owner，不再清 |
| 只清本页签副本 | `sessionStorage.clear()` 天然按页签隔离；原页签的锁/广播/存储不动 |
| 不碰 localStorage | 代码只在 `sessionStorage` 上调用 `clear()`；单测与真浏览器都断言 localStorage 原值 |
| 普通刷新 / bfcache 不清 | 锁空闲判 owner；bfcache 只重认领，均不进入清空分支 |
| 慢重载回来的原页签不清 | 一次性返回意向（同 session + 同 pageId + 60s）使 `returning=true`，只降级 |
| 未提交草稿有意不继承 | 整份 `clear()` 覆盖 `cm.ai.form.draft.<id>`；用例显式断言为 null |
| 原页签 / 服务端业务数据不动 | 不调用任何删除接口；`api.deleteHistory` 断言零调用；真浏览器验证原页签现场完整 |

清空与重建的**顺序**（不可颠倒）：

```
clearDuplicatedTabStorage()   // 1) 整份 sessionStorage.clear()（判定依据此时均已消费/在内存）
this.adoptFreshSession()      // 2) 内存态清场 + 写新 ai-session-id
rotatePageId()                // 3) 写新 ai-tab-page-id
await this.claimSessionOwnership(...)  // 4) 广播 claim（携带新 pageId）
```

先 adopt/rotate 再 clear 会把刚写下的新身份抹掉；先 clear 后 adopt 才能得到"只含新身份键"的干净副本。

---

## 2. 既有判据可靠性证明（未修改判据）

现主树已有 RC-4 三层握手（`src/stores/ai.ts` 模块级）：pageId + **Web Locks 探活** + BroadcastChannel。
判定唯一可靠来源是 Web Locks：会话锁 `ai-session-<sid>` 是否被**另一个文档**持有。

| 现场 | 判据（全部在清空前完成） | 角色 | 是否清空 | 证据 |
|---|---|---|---|---|
| 原页签 / 普通刷新 / 快重载 | `navigator.locks.request(..., {ifAvailable})` 拿到锁 | `owner` | 否 | 单测「普通刷新 / 快重载…一次都不清」；真浏览器 reload |
| 复制页签 | 同一把锁被占（`3 × 150ms` 限次重试）且无返回意向 | `duplicate` | **是，一次** | 单测 2 例 + 真 Chromium |
| 慢重载回来的原页签 | 锁被占，但带着 `pagehide` 写下的一次性返回意向（同 sid + 同 pageId + 60s） | `duplicate`（降级） | 否 | 单测「慢重载…整份现场还在」 |
| 环境无 Web Locks | 无法确认 | `unsupported` | 否（保守：宁可残留也不误伤刷新） | 单测 + 既有 `r166b-fixes` |
| bfcache 恢复 | `pagehide.persisted=true` 不广播/不释放；`pageshow` 重探活 | `owner` 或降级 | 否 | 单测「bfcache 恢复…」+ 既有生命周期套件 |

结论：**既有判据足以可靠区分复制页签与普通刷新**（在支持 Web Locks 的浏览器上），因此本批
**不需要修判据**；任何清空都发生在判定完成之后。无 Web Locks 时选择"不清"，这是有意的安全兜底。

判定依据在清空前已离开 sessionStorage：

1. `sid` 在 `ensureTabOwnership` 入口读入内存（`this.sessionId`）；
2. `takeReturnIntent(sid)` 在探活**之前**读取并删除返回意向；
3. 探活给出 `role`；
4. 只有 `role === 'duplicate' && !returning` 才调用清空。

---

## 3. 修改文件（精确）

### 3.1 `src/stores/ai.ts`（唯一生产代码改动）

相对主树的精确差异：**1 个文件，30 insertions / 7 deletions**，4 个 hunk：

| 位置（副本改后行号） | 改动 |
|---|---|
| `src/stores/ai.ts:630-648` | 新增 `clearDuplicatedTabStorage()`：`sessionStorage.clear()`；不可用时退回 `clearMirrorRefs()`（15 行文档 + 9 行实现） |
| `src/stores/ai.ts:1184-1192` | `cleanupSessionLocalArtifacts` 的"严禁 clear"注释改为限定于精确清理路径，并指向新的复制页签清空路径（避免文档自相矛盾） |
| `src/stores/ai.ts:4583-4590` | `ensureTabOwnership` 文档：复制页签分支改为"整份清一次 → 新会话 + 新 pageId" |
| `src/stores/ai.ts:4630-4639` | `duplicate` 分支：`clearMirrorRefs()` → `clearDuplicatedTabStorage()`（顺序：清 → adopt → rotate → claim） |

补丁：`<仓外证据>/tab-clear-final.diff`（统一 diff）。

### 3.2 `tests/duplicated-tab-clear.spec.ts`（新增测试，8 例）

补丁：`<仓外证据>/tab-clear-final-tests.diff`。

| 行 | 用例 | 覆盖 |
|---|---|---|
| 108 | 锁被另一文档持有 + 无返回意向 ⇒ duplicate，且只清一次 | 判据 + 一次性 |
| 122 | 普通刷新 / 快重载（锁空闲）⇒ owner，一次都不清，继承现场照旧 | 刷新不清 |
| 140 | 慢重载（返回意向 + 锁被接手）⇒ 降级但不清 | 慢重载不清 |
| 165 | 无 Web Locks ⇒ unsupported，不清 | 保守兜底 |
| 181 | 镜像 / 未提交草稿 / 业务页签键全部不继承；只留新身份键 | 清空范围 + 新身份 |
| 206 | 只清本页签副本：localStorage、原页签的锁/广播、服务端会话不动 | 边界 + 原页签无影响 |
| 231 | 复制页签随后刷新 ⇒ 不再清第二次 | "只清一次" |
| 254 | bfcache 恢复 ⇒ 不清 | bfcache 不清 |

### 3.3 未改动 / 未纳入

- `src/utils/form-draft.ts`、`src/components/**`、`src/types/**`：零改动。
- 主树工作区里其它代理的在途改动（`frontend/src/utils/parts.ts`、`frontend/tests/park-request-tool-start-order.spec.ts`
  及后端文件）**未修改、未纳入本批补丁**。
- 全目录比对（`diff -rq`）：`src/` 仅 `stores/ai.ts` 不同；`tests/` 仅新增本批 spec；`scripts/` 零差异。
- 未在隔离副本里新增/修改任何依赖、lockfile、构建配置。

---

## 4. RED → GREEN 证据

### 4.1 RED（仅加测试、实现未动）

```
$ npx vitest run tests/duplicated-tab-clear.spec.ts
 ❯ tests/duplicated-tab-clear.spec.ts (8 tests | 2 failed) 2426ms
     × 同一会话的锁被另一文档持有 + 无返回意向 ⇒ duplicate，且只清一次
     ✓ 普通刷新 / 快重载（锁空闲）⇒ owner，一次都不清，全部继承现场照旧
     ✓ 慢重载（带一次性返回意向、锁已被副本接手）⇒ 降级但不清：整份现场还在
     ✓ 环境无 Web Locks（无法确认复制）⇒ unsupported，不清
     × 继承来的会话镜像、未提交草稿、业务页签键全部不继承；只留新的两个身份键
     ✓ 只清本页签副本：localStorage、原页签的锁与广播、服务端会话都不动
     ✓ 复制页签随后刷新（已是普通页签）⇒ 不再清第二次
     ✓ bfcache 恢复（pagehide.persisted / pageshow）⇒ 不清，现场原样保留

AssertionError: expected "clear" to be called 1 times, but got 0 times
AssertionError: expected '{"name":"未提交草稿"}' to be null
```

- 2 条失败正是待实现行为：**复制确认后没有发生任何整份清空、草稿被继承**。
- 其余 6 条（含判据守卫：刷新/慢重载/无锁/bfcache 不清、原页签无影响、只清一次）在 RED 阶段即绿
  —— 证明判据本身可靠，且清空没有误伤普通刷新。

### 4.2 GREEN（最小实现后）

```
$ npx vitest run tests/duplicated-tab-clear.spec.ts
 ✓ tests/duplicated-tab-clear.spec.ts (8 tests) 2460ms
 Test Files  1 passed (1)
      Tests  8 passed (8)
```

---

## 5. 测试命令与结果

| 命令（cwd = 隔离副本 `frontend`） | 结果 |
|---|---|
| `npx vitest run tests/duplicated-tab-clear.spec.ts` | **8 passed**（RED 阶段 2 failed / 6 passed） |
| `npx vitest run tests/duplicated-tab-clear.spec.ts tests/tab-ownership-lifecycle.spec.ts tests/r166b-fixes.spec.ts tests/session-purged-recovery.spec.ts tests/irreversible-lifecycle.spec.ts tests/gf-draft.spec.ts tests/context-restore.spec.ts` | **7 files / 68 tests passed** |
| `npx vitest run tests/aie2e-refresh.spec.ts`（单跑） | **12 passed**（并入 8 文件组合跑时 R-04 曾 1 例红，单跑绿 —— 见 §5.1） |
| `npx vue-tsc --noEmit` | **exit 0，零错误** |
| `npm run build`（= `vue-tsc --noEmit && node scripts/build.mjs`） | **exit 0**，`✓ built in 1m 28s`，产物 `dist/` |
| `npx vitest run`（全量） | **131/135 files，1574/1580 tests passed**；6 例失败全为并行负载下的超时/时序 flake（§5.1） |

日志：
`<仓外证据>/tab-clear-final-typecheck.log`、`<仓外证据>/tab-clear-final-build.log`、
`<仓外证据>/tab-clear-final-full-suite.log`。

### 5.1 全量失败的定性（与本次改动无交集）

失败集合：

- `tests/en-us-flow.spec.ts` ×2（`expected 'ok' to be 'error'` / 空 DOMWrapper）
- `tests/rg-s13-s14-stopped-label.spec.ts` ×2（5s 超时 / `undefined to be true`）
- `tests/truncated-answer-panel.spec.ts` ×1（5s 超时）
- `tests/truncated-answer.spec.ts` ×1（5s 超时）

排除证据：

1. 4 个失败文件与 `aie2e-refresh` 一起单跑：`15 passed / 1 failed`；唯一失败 `truncated-answer-panel`
   再单跑 **3/3 passed**（该例耗时 4.6s，逼近 5s 上限，纯负载敏感）。
2. 这些用例均不安装 `navigator.locks` 桩、不调用 `ensureTabOwnership`，**结构上无法进入**
   本批唯一改动的 duplicate 分支。
3. 本批聚焦 + 相邻 7 文件 68 例全绿，覆盖页签所有权、410 恢复、409/在途轮、草稿与上下文恢复。
4. `aie2e-refresh` 的加载期双模块实例 flake 在 `tests/aie2e-helpers.ts:56-71` 已有仓库内注释记录
   （`vi.resetModules()` 后 `ApiError instanceof` 恒假，单跑绿、并行红）。

结论：全量无法在当前机器负载下给出一次干净全绿，但所有失败项与本批路径无交集且单跑绿。

### 5.2 关于已知外部 type error

复制时点的主树 `tests/park-request-tool-start-order.spec.ts` 已不在报错状态：`vue-tsc --noEmit`
（`tsconfig.json` 的 `include` 覆盖 `tests/**/*.ts`）**exit 0**。该文件属于另一代理的改动，
**未纳入本批补丁、未修改**；本报告不对其修复或历史失败作任何归属声明。

---

## 6. 真实浏览器边界（已做，Chromium 实机）

环境：Playwright 1.64.1 + 已安装 Chromium 1248；被测物 = 本批 `npm run build` 的 `dist/`，
由 `vite preview --port 5199` 提供；后端未起（页面部分接口 500，不影响页签逻辑）。

场景（同源两个真实页面、真实 Web Locks 共享、真实 sessionStorage 隔离）：

1. 页签 A 打开应用：自动生成 sessionId / pageId，并持有 `ai-session-<A>` 锁；
2. 页签 B 打开同源页面后，把 A 的**整份 sessionStorage 快照**注入自己（含镜像、未提交草稿
   `cm.ai.form.draft.call-1`、业务键 `import-wizard-draft` / `some-business-tab-state`），
   再 `reload()` —— 等价于"复制页签带着继承现场启动，原页签仍开着"；
3. 观察 B 的判定与清空；随后 B 再做一次普通刷新；同时检查 A 的存储/锁/渲染。

结果：**14/14 通过**（`<仓外证据>/tab-clear-final-real-browser.log`，脚本
`<仓外证据>/tab-clear-real-browser-probe.mjs`）：

- 复制被识别：B 换成全新 sessionId、pageId 轮换；
- B 的 sessionStorage 只剩 `ai-session-id` / `ai-tab-page-id` / 新会话镜像；
  继承的旧镜像、未提交草稿、业务键全部消失；
- B 的 localStorage 原值保留；
- A 的 sessionId / pageId / 镜像 / 草稿 / 业务键 / localStorage 全部原样，应用正常渲染；
- A 仍持有自己的锁，B 持有自己的新锁（同源锁管理器可见两把）；
- B 之后再普通刷新：sessionId 不变、自设哨兵键保留 —— 普通刷新不清；
- 无未捕获页面错误。

**边界说明（重要）**：

1. Playwright 不能触发浏览器 UI 的"复制标签页"动作；本验证用"同源第二个页面 + 注入继承的
   sessionStorage 快照 + reload"复现复制页签的**可观测状态**，并未验证浏览器原生复制动作本身。
   `sessionStorage` 在原生复制时被整份克隆、Web Locks 按文档归属，均为规范行为。
2. 慢重载 >60s（返回意向过期/写入失败）在真机难以稳定构造，仅有 jsdom 单测覆盖。
3. 无 Web Locks 的旧浏览器走 `unsupported`，不清空 —— 有意取舍。
4. 内存态 `ai-panel-expanded` 从存储清掉后，本次渲染仍沿用启动时读到的值，下次刷新回默认展开；
   刻意不在清空瞬间强制收起，避免界面跳变。

---

## 7. 范围冻结

- 只改 1 个生产文件 + 新增 1 个测试文件；无重构、无 UI 改动、无新依赖、无配置改动。
- 未修改主树 `<主仓>`（`git status --porcelain frontend`
  仍只有其它代理的 `M src/utils/parts.ts`、`A tests/park-request-tool-start-order.spec.ts`）。
- 未触碰 localStorage 相关实现（`src/stores/locale.ts`、`src/locales/**`）、pagehide/pageshow 生命周期、
  `adoptFreshSession`、410 `SESSION_PURGED` 恢复、`takeOverSession`、`resetSession` 精确清理。

## 8. 残余风险 / 后续

1. 隐私模式或存储被禁用导致 `sessionStorage.clear()` 抛错时，只退回镜像前缀清理（草稿可能残留）；
   这是"不打断新会话"的降级，非正常路径。
2. 若未来引入更多 sessionStorage 键并期望复制后保留，需要重新评估"整份清空"口径（当前口径是
   全新工作区，全部不继承）。
3. 判定依赖 Web Locks；Safari 15.4+/Chrome/Firefox 均支持，更旧环境走 `unsupported` 不清。
