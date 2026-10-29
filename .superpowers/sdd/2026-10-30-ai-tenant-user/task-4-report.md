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
