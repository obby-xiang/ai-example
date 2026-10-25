> 来源=<仓外证据>/frontend-final-validation.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 前端集成终验（含 park 帧序修复，原样测试）

- 日期：2026-10-25
- 仓库：`<主仓>`，工作目录 `frontend`
- HEAD：`ae883fb fix: 修复集群扫描与挂起续跑`
- 本次验证未修改任何文件；未安装依赖；未占用 8081/5202 端口；未派发子代理。

## 结论速览

| 项 | 结果 |
| --- | --- |
| 全量单测 `npm test -- --no-file-parallelism` | **通过**：134/134 文件，1572/1572 用例，0 失败，0 跳过，退出码 0 |
| 新增 park 帧序用例 | **通过**：`tests/park-request-tool-start-order.spec.ts` 4/4 |
| 构建 `npm run build` | **失败**：退出码 2，`vue-tsc --noEmit` 报 5 个 TS2345，全部位于新增测试文件；`node scripts/build.mjs` 未执行，`dist/` 未被重建 |
| 源码运行期是否变更 | **未变更**（md5 与运行前一致；`git status --short -- frontend` 前后完全相同） |

测试为绿、构建为红。失败点在**新增测试文件的类型标注**，不在 `src/utils/parts.ts` 的修复逻辑（该文件 typecheck 零报错）。

## 1. 运行前基线

`git status --short -- frontend`：

```
 M frontend/src/utils/parts.ts
 A frontend/tests/park-request-tool-start-order.spec.ts
```

md5 基线（运行前后比对用）：

```
08f4a4550df8703ebb3015cda3eb10c9 *frontend/src/utils/parts.ts
1e0aa7256784b023d9d91b7a2b106023 *frontend/tests/park-request-tool-start-order.spec.ts
```

被改动的集成面：

- `frontend/src/utils/parts.ts`：+12/−1（`applyFrameToParts` 的 `tool_start` 分支增加帧序防御：同 `toolCallId` 已存在 `pending && requiresAction` 卡时直接 `break`，不复位为只读 running）
- `frontend/tests/park-request-tool-start-order.spec.ts`：新增 111 行，4 个用例

测试配置：`vitest.config.ts`（jsdom，`include: tests/**/*.spec.ts`，`setupFiles: tests/setup.ts`）；`tests/` 下 spec 文件 134 个。

## 2. 单测

命令（在 `frontend` 下）：

```
npm test -- --no-file-parallelism > <仓外证据>/frontend-final-premerge.log 2>&1
```

日志尾部实读（`frontend-final-premerge.log`，4003 行）：

```
 ✓ tests/app-shell-source.spec.ts (1 test) 6ms
 ✓ tests/ai-tool-registry.spec.ts (8 tests) 7ms
 ✓ tests/time-constants.spec.ts (5 tests) 5ms

 Test Files  134 passed (134)
      Tests  1572 passed (1572)
   Start at  16:09:00
   Duration  352.36s (transform 8.45s, setup 13.65s, import 119.95s, tests 77.56s, environment 114.49s)
```

- 退出码：`0`（由包装命令回写，`EXIT_CODE=0`）
- 失败用例名：**无**（日志中 `FAIL`/`×` 标记数 0；`skipped`/`todo` 计数 0）
- 按文件统计：`^ ✓ tests/` 行 134 条，与汇总 134/134 一致
- 新增 park 用例：`frontend-final-premerge.log:3988` → `✓ tests/park-request-tool-start-order.spec.ts (4 tests) 6ms`，4 个用例全绿（请求帧先到 / 契约顺序 / 普通工具仍落 running / 迟到帧幂等不改写卡片）
- 基线对比：改动前全量运行 `frontend-full-final3.log` 为 133 文件 / 1568 用例 / 283.21s；本次多出 1 文件、4 用例（+4 = 1572），与新增 spec 完全吻合，无既有用例丢失
- 日志中的非失败噪声：`[ai-region-audit] missing-name` 告警（本次 32 处，基线 `frontend-full-final3.log` 33 处）与 `at <VTUROOT>` 堆栈片段，属既有输出，非用例失败

## 3. 构建

命令（在 `frontend` 下，单测通过后执行）：

```
npm run build > <仓外证据>/frontend-final-build.log 2>&1
```

`build` 脚本为 `vue-tsc --noEmit && node scripts/build.mjs`。结果：**退出码 2**，在 `vue-tsc` 阶段中止。

完整错误清单（`frontend-final-build.log` 第 5–24 行，共 5 个，全部 `TS2345`，全部在新增 spec）：

```
tests/park-request-tool-start-order.spec.ts(67,32):  TS2345 ... Types of property 'kind' are incompatible. Type 'string' is not assignable to type 'ToolKind'.
tests/park-request-tool-start-order.spec.ts(80,32):  TS2345 ... 同上
tests/park-request-tool-start-order.spec.ts(93,32):  TS2345 ... 同上
tests/park-request-tool-start-order.spec.ts(94,32):  TS2345 ... 同上
tests/park-request-tool-start-order.spec.ts(106,32): TS2345 ... 同上
```

佐证：

- `grep -c "error TS"` = 5；`grep -c "src/.*error TS"` = 0 → **`src/` 内零类型错误**，park 修复本身通过类型检查
- 日志无 `built in` 行 → bundler 阶段未运行；`frontend/dist/index.html` 与 `dist/assets/` 的 mtime 仍为 `14:54:48`（上次成功构建时间），本次**未产出新产物**
- 上一版构建 `frontend-build-final3.log`（14:54，HEAD 未变）完整成功（`✓ built in 42.63s`）；而两个改动文件 mtime 均为 `16:00:17`，晚于该次成功构建，故该构建证明**既有 133 个测试文件类型干净**；新增 spec 是唯一新入 typecheck 集合的文件，加上本次 `src/` 零报错 → 5 个错误由**新增测试文件引入**，非既有回归，也不是 `parts.ts` 的问题

### 根因（供路由使用，未自行修复）

触发点：`frontend/tests/park-request-tool-start-order.spec.ts:47`

```ts
function toolStartFrame(toolCallId: string, kind = 'FRONTEND') {
  return { type: 'tool_start' as const, toolCallId, name: 'generative_form', kind, args: '...' }
}
```

`kind` 形参未标注类型，默认值 `'FRONTEND'` 使推断结果为 `string`（而不是字面量联合），于是返回对象的 `kind: string`。而落点类型是 `frontend/src/types/sse.ts:244` 的 `SseToolStartFrame`，其 `kind: ToolKind`，`ToolKind = 'CONFIRM' | 'FRONTEND' | 'BACKEND'`（`src/types/sse.ts:21`）。`applyFrameToParts(message: ChatMessage, frame: SseFrame)` 因此拒收。

vitest 走 esbuild 仅剥类型、不做类型检查，所以 5 处调用在运行时全绿、`vue-tsc` 全红 —— 这是"测试过、构建不过"的唯一原因。5 个报错行号正是 5 次 `applyFrameToParts(message, toolStartFrame(...))` 调用（第 94 行传 `'CONFIRM'`，第 93 行传 `'BACKEND'`，其余走默认值）。

最小修复方向（仅建议，本 worker 未改动）：把 `kind` 标注为 `ToolKind`（`import type { ToolKind } from '@/types/sse'` 后写 `kind: ToolKind = 'FRONTEND'`），或对默认值与调用处加字面量断言。

## 4. 源码变更核查

运行结束后：

```
md5sum -c /tmp/p2-baseline-md5.txt
frontend/src/utils/parts.ts: OK
frontend/tests/park-request-tool-start-order.spec.ts: OK
```

`git status --short -- frontend` 与运行前逐字一致：

```
 M frontend/src/utils/parts.ts
 A frontend/tests/park-request-tool-start-order.spec.ts
```

`git diff --stat -- frontend`：`parts.ts` +12/−1，新增 spec 111 行，合计 122 插入 / 1 删除。测试与构建期间源码未被改动（构建未写 `dist/`，vitest 未改源文件）。

### 合并前风险提示（非本次失败项）

该 spec 目前是 **intent-to-add**（`git add -N`）状态，不是真正入索引：`git ls-files -s` 显示索引 blob 为 `e69de29…`（空 blob），`git diff --cached -- frontend` 为空，porcelain v2 为 `1 .A N...`。若控制器用普通 `git commit`（不带 `git add` / `-a`）提交，**该测试文件内容会漏提交**。提交前需 `git add frontend/tests/park-request-tool-start-order.spec.ts`。

## 5. 复现

```bash
cd <主仓>/frontend
npm test -- --no-file-parallelism > <仓外证据>/frontend-final-premerge.log 2>&1   # 绿，134/1572
npm run build > <仓外证据>/frontend-final-build.log 2>&1                          # 红，exit 2，5×TS2345
cd .. && git status --short -- frontend
```
