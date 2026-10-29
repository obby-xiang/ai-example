# Task 4 施工报告：前端身份切换与存储隔离

- 状态：**前端部分完成并提交**（后端改动未纳入、未推送）。
- 实现提交：`a2652b44abe92cfba03ad4d339c9058ec3f603c4`（frontend only，10 files）
- 报告提交：本文件（`.superpowers/sdd/2026-10-30-ai-tenant-user/task-4-report.md`）
- 基线：`55ca4a0e5ad53782e3a3588c60e99a624c1d596c`

## 红绿证据

### 红（实现前，精确单点）

```
cd frontend
npx vitest run tests/ai-tenant-identity-switch.spec.ts -t "切换取新号"
```

结果：`1 failed`

```
TypeError: store.loadTenantDirectory is not a function
 ❯ tests/ai-tenant-identity-switch.spec.ts:97:17
```

失败原因与预期一致：身份目录/会话发号/`sessionReady` 引导门尚未实现（前端仍在本地造 sessionId）。

### 绿（实现后，只重跑同一精确用例）

```
cd frontend
npx vitest run tests/ai-tenant-identity-switch.spec.ts -t "切换取新号"
```

结果：

```
✓ tests/ai-tenant-identity-switch.spec.ts (1 test)
Test Files  1 passed (1)
     Tests  1 passed (1)
```

### 类型检查

```
cd frontend
npx vue-tsc --noEmit
```

结果：无输出、退出码 0。

### 空白检查与提交

```
git diff --check          # 退出码 0（仅 autocrlf 的 LF→CRLF 提示）
git commit -m "feat(frontend): 租户/用户身份切换与会话统一后端发号（Task 4）"
# [main a2652b44] ... 10 files changed, 1142 insertions(+), 112 deletions(-)
```

未推送。

## 单点覆盖内容（`frontend/tests/ai-tenant-identity-switch.spec.ts`）

一条用例、顺序断言：

1. `loadTenantDirectory()` 默认落到**目录第一项**（tenant=7/user=71），`POST /api/ai/session` 只发一次，会话号 = 后端返回值；
2. 旧身份写入镜像 `ai-mirror:7:71:1001`；
3. 旧身份在途一轮：`chat` 请求体的 sessionId 为 `1001`（非空、非前端生成）；
4. `switchIdentity(8, 81)`：后端第二次发号 `1002`、展示清空、旧镜像仍在旧身份键下、新身份键下无镜像、`ai-identity` 已落新身份；
5. 释放旧响应（delta+done）：新会话 `messages` 仍为空、新身份镜像未生成 —— 旧响应不写入新会话；
6. 复制页签/410 路径：`adoptFreshSession()` 只清场（sessionId 置空、**不发号**），随后 `ensureSession()` 才向后端取号（`1003`）；
7. 全程 `chat` 请求的 sessionId 均非空字符串。

## 实现要点

- **身份层** `frontend/src/utils/ai-identity.ts`（新增）：`AiIdentity`、目录/会话号收窄、指纹
  `tenantId:userId`、键形与请求头统一出口、`isAiConversationPath`（对话域判定与模型配置排除）。
- **API**：`POST /api/ai/session`、`GET /api/ai/tenant-users`（404 时回落后端并行实现的
  `/api/ai/tenants` 别名）；`createSession()` 用原始文本 + 正则取数字 —— 后端发号量级约 1.7e18，
  超出 JS 安全整数，先 `JSON.parse` 会静默丢位，故全程按十进制字符串保存/发送。
- **请求头**：axios 请求拦截器对 AI 对话域统一加 `X-Tenant-Id` / `X-User-Id`（provider/models/
  settings/health/tools/租户目录排除，与后端 `TenantIdentityFilter` 排除表对齐）；`chat`、
  `events` reattach、`?probe=1` 三条 SSE 走裸 `fetch` 并带同一组头；附件上传走 axios 同样覆盖。
  原生 `EventSource` 不能设请求头，reattach 因此改为 `fetch + ReadableStream` 自解 SSE 帧，
  看门狗/退避/202 探测语义保持不变。
- **引导门**：新增 getter `sessionReady` 与 `ensureSession()`；`history` / `context` /
  `runs/current` / `chat` / `feedback` / `delete`（销毁）/ reattach / 续收均在取会话号前过闸；
  无号直接不发请求（不产生空 id 请求）。同身份并发只发一次号；切换身份期间在途发号回包按令牌丢弃。
- **切换语义**：`switchIdentity`（面板下拉）→ 旧身份现场保留（镜像 `keepMirror`、草稿不 prune）→
  清屏重置 → 落新身份 → 后端发号 → 认领新会话锁。旧响应由 `roundEpoch` + 会话身份守卫丢弃。
- **存储指纹**：会话键 `ai-session-id:<t>:<u>`、镜像 `ai-mirror:<t>:<u>:<sid>`、草稿
  `cm.ai.form.draft.<t>:<u>.<toolCallId>`、页签锁 `ai-session-<t>:<u>:<sid>`；所有权消息与
  `pagehide` 返回意向均带身份指纹，不同身份不认领/不让渡/不接手（`takeOverSession` 探活后再核对一次）。
  `clientCleanup`：认本前端的身份键与后端返回键（兼容旧 `ai-mirror:<sid>`），草稿同时认后端
  `draftPrefix`，避免并行期键形不一致导致删不净。
- **面板**：context bar 增加租户/用户两个原生 `<select>`（目录为空不渲染），切换走
  `switchIdentity`；目录加载失败在「诊断信息」给一行提示。
- **边界**：不做登录、不做租户列表循环处理、未改业务数据与 AI 模型配置/健康探针。

## 顾虑 / 待跟进

1. **后端改动在同一工作区出现，未提交**：`backend/pom.xml`、`ai/tenant/*`、`AiSessionController`、
   `TenantDirectoryController`、`SessionIdGenerator` 等由并行任务产生，本提交严格只含 frontend。
2. **路由与字段与并行后端不同名**：任务书契约是 `GET /api/ai/tenant-users` + `displayName`，当前
   后端实现是 `GET /api/ai/tenants` + `userName`（并带 `tenantName`）。前端已做别名/字段兼容
   （契约路径优先，404 回落 `/ai/tenants`；`displayName ?? userName ?? userId`），但**两边最终应统一**。
3. **clientCleanup 键形尚未在后端落地**：后端 `AiSessionPurgeService` 仍返回
   `ai-mirror:<sid>` / `cm.ai.form.draft.`。前端按新键形 `ai-mirror:<t>:<u>:<sid>` /
   `cm.ai.form.draft.<t>:<u>.` 生成并同时兼容旧键；需后端 Task 2/3 同步后才能真正对齐。
4. **未接身份目录时保留旧的本地页签会话 id**：身份目录加载成功（生产路径）后会话号一律由后端发；
   目录不可用（旧后端/单测桩）时退回旧的单用户键形与本地 id，保证降级可用。若验收要求"任何环境
   都不得出现前端生成会话 id"，需在 Task 5 把该兼容分支连同依赖它的单测桩一起移除。
5. **未按指令跑其它测试**：仅跑了同一精确单点用例与 `vue-tsc`。以下套件因 reattach 从
   `EventSource` 改为 `fetch` 而预期需要改写（未运行、未修改）：
   `tests/sse-watchdog.spec.ts`、`tests/m08-probe-reconnect.spec.ts`（以 `FakeEventSource`
   计数/驱动 reattach）。其余套件在"无身份"路径上保持原有键形与本地会话 id，理论上不受影响，
   但未逐一验证。
6. `sessionId` 在 store/类型层仍是字符串（后端 long 的十进制文本）：`AiChatRequest.sessionId`
   仍为 `string`，Jackson 可把十进制字符串反序列化为 long；若后端改为严格数值类型校验需同批复核。

---

# Task 4 返修 round 1（审查 C1/C2/C3 + I1–I8）

- 返修提交：`0fc6c38a`（frontend only，17 files，+1109/−260）
- 审查对象：`task-4-review.md`（对 `a2652b44` 的只读审查）
- 未推送；后端并行改动仍未纳入本提交。

## 红 → 绿（先修最关键失败用例）

### 红（C1，真实复制页签路径）

```
cd frontend
npx vitest run tests/ai-tenant-duplicate-tab-identity.spec.ts -t "复制页签清空"
```

实现前结果（1 failed）：

```
AssertionError: the given combination of arguments (null and string) is invalid ...
 ❯ tests/ai-tenant-duplicate-tab-identity.spec.ts:109
   expect(sessionStorage.getItem('ai-identity')).toContain('"tenantId":7')
```

`sessionStorage.clear()` 后 `ai-identity` 为 null —— 与审查 C1 的静态推导一致。

### 绿（同一精确用例）

```
npx vitest run tests/ai-tenant-duplicate-tab-identity.spec.ts -t "复制页签清空"
# ✓ 1 passed
```

## 本轮逐条修复

| 项 | 处理 |
| --- | --- |
| C1 | 身份改为**模块级缓存权威**（`currentAiIdentity()`），storage 只是刷新副本；`clearDuplicatedTabStorage(identity)` 清空后按内存身份 `writeAiIdentity()` 回写。请求头/镜像/草稿/锁/所有权消息全部改读缓存，不再存在"内存有身份、storage 没有"的分叉。 |
| C2 | 删除两处本地造号：`initSessionId()` 无身份一律返回 `''`；`adoptFreshSession()` 恒置 `''` 等后端发号（`createPageIdToken()` 仅用于 pageId）。目录失败/空目录 ⇒ `directoryStatus='unavailable'`：面板红条 + 重试按钮 + 发送禁用（按钮 disabled + 回车路径兜底），**不再**静默切到无身份/本地 id。`createSession()` 用原始文本 + `data` 段正则取号，19 位 long 不经过 `JSON.parse`。 |
| C3 | `openLiveStream` 只对 `200 + text/event-stream` 调 `onOpen`；`202`/非事件流按"连接失败"收口（`connectionEstablished` 保持 false），既有 `?probe=1` 探测链继续产出 `onPreparing`。失败原因恢复为既有通用文案（不把 HTTP 码/英文异常上屏）。 |
| I1 | `syncGateFromAuthority` 先过 `ensureSession()`，空号早退（有界重试/解锁口径不变），不再发 `sessionId=` 空请求。 |
| I2 | `ensureSession()` 发号成功后，若 `tabOwnership==='unknown'` 立即补判 `ensureTabOwnership()`（单飞 `ownershipProbe`，避免面板挂载与补判重复探活）。目录加载也不再阻塞启动（见 I6）。 |
| I3 | 目录路径统一为后端实际路由 `GET /api/ai/tenants`，**去掉 404 回落**；排除表与 `TenantIdentityFilter` 对齐：providers/models/settings/health/tools/tenants/startup-resume。字段继续兼容 `displayName ?? userName ?? userId`。 |
| I4 | 已核对并行后端的 `AiSessionPurgeService`（工作区改动）：`clientCleanup.mirrorKey = ai-mirror:<t>:<u>:<sid>`、`draftPrefix = cm.ai.form.draft.<t>:<u>.` —— 与前端键形**已一致**；旧键形仍双删兼容。契约写入 `types/ai.ts` 的 `SessionPurgeClientCleanup` 文档。 |
| I5 | 见下"新增/改写单点"。 |
| I6 | 面板 `onMounted` 不再 `await` 目录：`loadTenantDirectory()` 单飞后后台执行，`ensureSession()` 在需要身份时等待它；所有权判定/历史恢复不再被目录请求阻塞。 |
| I7 | 身份唯一入口 = `currentAiIdentity()`（缓存）/`writeAiIdentity()`（缓存+storage）；键形与请求头都走它，判定侧仍用 `this.identity`，但两者由同一写入口更新。 |
| I8 | `enterIdentity()` 以 `keepMirror + keepSessionPointer` 清场：离开身份时保留其 `ai-session-id:<t>:<u>` 指针；切回时先按所有权探活，锁可用即恢复原会话号 + 镜像/历史（`loadHistory()`），不再重新发号。 |
| M1 | `sessionReady` 接到面板：未就绪/目录不可用 ⇒ 发送闸关闭（`identityBlocked`）。 |
| M2 | `extractSessionId` 只从 `"data"` 段起点往后取第一个 `sessionId`，避免回显请求体命中第二处。 |
| M3 | `ownMirrorKey` 去掉与 `expectedMirror` 恒等的冗余分支；注释说明双删只为兼容旧后端。 |
| M5 | 租户/用户两个 `<select>` 使用不同 `aria-label`（新增 `tenantAria`/`userAria`）。 |
| M6 | `takeOverSession` 回写会话号/镜像删除显式绑定 `this.identity`，不走默认参数。 |
| M7 | 原报告第 6 条更正：后端 `AiChatRequest.sessionId`、`/history/{sessionId}`、`/runs/current` 均为 String，不存在 Jackson 精度路径；精度风险只在**发号响应**，已按原始文本规避。 |
| M4 | **未做**（旧键一次性清理属升级卫生，风险 > 本轮收益）：无身份时代的裸键不会被读取，也不会被 prune；建议 Task 5 统一清理。 |

## 新增/改写单点（全部逐条精确运行）

| 用例 | 命令（`-t` 精确名） | 结果 |
| --- | --- | --- |
| 真实复制页签保留身份（C1） | `tests/ai-tenant-duplicate-tab-identity.spec.ts` / `复制页签清空` | 红→绿 |
| 切换取新号 + 旧镜像/旧响应（原单点） | `tests/ai-tenant-identity-switch.spec.ts` / `切换取新号` | ✓ |
| 切回旧身份恢复原会话（I8） | 同上 / `切回旧身份恢复` | ✓ |
| 身份头 + 19 位长号（C2/I3） | `tests/ai-tenant-identity-headers.spec.ts` / `AI 对话请求带身份头`、`19 位 long` | ✓ ✓ |
| 目录失败→不可用→重试（C2） | `tests/ai-tenant-identity-guards.spec.ts` / `目录失败` | ✓ |
| 目录与历史并行、只发一次号（I6） | 同上 / `目录加载与历史恢复并行` | ✓ |
| 发号成功补判所有权（I2） | 同上 / `发号成功后补判所有权` | ✓ |
| 跨身份接管拒绝（I5） | 同上 / `跨身份接管被拒绝` | ✓ |
| 锁/草稿键指纹隔离（I5） | 同上 / `锁名/草稿键` | ✓ |
| 410 恢复重新取号（I5） | 同上 / `410 会话已销毁` | ✓ |
| reattach 202 准备中（C3） | `tests/sse-reattach-202.spec.ts` / `202 application/json` | ✓ |
| 看门狗/退避（改写为 fetch 真实路径） | `tests/sse-watchdog.spec.ts` 各用例 | 逐个 ✓ |
| 探测/世代/202/404（改写） | `tests/m08-probe-reconnect.spec.ts` 各用例 | 逐个 ✓ |
| 挂起收流不重挂 / 普通断流重挂 | `tests/b1-suspend-disconnect.spec.ts` 两条 | ✓ ✓ |

另：`npx vue-tsc --noEmit` 通过；`git diff --check` 退出码 0。

## 顾虑

1. **既有套件会因 C2 变红（未运行、未改，按“精确单点”约束）**：所有依赖"无身份本地生成 sessionId / 裸 `ai-session-id` 种子"的用例（如 `duplicated-tab-clear.spec.ts`、`b6b-session-handoff.spec.ts`、`aie2e-refresh.spec.ts` 及部分 panel 用例）现在拿到 `sessionId=''`，发送/历史会被引导门拦住。需要在 Task 5 给这些套件补身份与 `tenantUsers/createSession` 桩，或统一迁移到身份模式夹具。本轮只同步了审查点名的 SSE 套件（`sse-watchdog` / `m08` / `b1`）。
2. **路径以并行后端为准**：任务书写的 `GET /api/ai/tenant-users` 与后端实际路由 `GET /api/ai/tenants` 不一致；按"统一前后端、去掉 404 回落"改为后者。若最终契约要 `tenant-users`，需后端同时改路由 + Filter 排除表，前端只需一行。
3. **I4 依赖并行后端落地**：当前工作区后端已产出新 `clientCleanup` 键形（未由本提交引入）；若后端回退旧形，前端仍能靠旧键双删工作，但"后端认识新前端"不成立。
4. **M4 未做**：升级后旧键形（裸 `ai-session-id`、`ai-mirror:<uuid>`、`cm.ai.form.draft.<id>`）成为永不读取的孤儿；不做清理不会串身份（新路径不读它们）。
5. 未跑全量测试（按指令）；`sse-watchdog` 的 3 条纯常量断言未单独 `-t` 运行（本轮未改动其代码），其余改写用例均逐条运行通过。
