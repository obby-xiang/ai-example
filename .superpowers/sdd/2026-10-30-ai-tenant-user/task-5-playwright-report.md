# Task 5 · Playwright 辅助面身份适配报告（`frontend/e2e`）

- 状态：**完成**（仅静态检查；按要求**未跑**任何 Playwright / E2E / 批量测试，未起停服务）
- 改动面：**只改 `frontend/e2e/**`**（23 文件 = 1 新增 + 22 修改；`frontend/src` / `scripts` / `backend` / 各 `playwright*.config.ts` / `support/harness*.ts` 未动）
- 未推送。

## 0. 一句话结论

`frontend/e2e` 里**所有绕过页面直连后端**的 AI 对话数据面请求（原本全部缺身份头 → 400）现在都由同一个模块
`support/ai-identity.ts` 统一判面并加 `X-Tenant-Id` / `X-User-Id`；新会话一律 `POST /api/ai/session` 并且
**只从原始响应文本取 `sessionIdText`**（位数 ≥ 16，禁止 JSON number）；镜像/会话键断言改成身份键形；
旧的"本地造 sessionId"（`mdo-*` / `kqc-*` / `e2e-*` / `srp-*` / `vl-*` / `x02-*`……）**全部删除**。

## 1. 新增：`frontend/e2e/support/ai-identity.ts`（唯一事实源）

| 导出 | 作用 |
| --- | --- |
| `AI_IDENTITY` | 直连身份，默认 **1 / 101**（= 后端 `TenantUserDirectory` 首项，也是前端 `loadTenantDirectory()` 的落点）；可用 `E2E_TENANT_ID` / `E2E_USER_ID` 覆盖（必须是目录内组合，否则后端 400） |
| `isAiConversationPath(url)` | 与 `TenantIdentityFilter#shouldNotFilter` **逐条同构**：`/api/ai` 与 `/api/ai/` 下，精确排除 `health` / `tools` / `tenants` / `startup-resume`(+`/scan`)，配置子树 `providers` / `models` / `settings`；其余一律"受保护"。先剥 query 再判（后端用 `getRequestURI()`） |
| `aiHeaders(url)` | 受保护面返回两个头，排除面返回 `{}`（**业务与模型配置不加头**） |
| `aiFetch(url, init)` | `fetch` 封装：按 URL 自动合并身份头 |
| `aiRequestOptions(url, options)` | Playwright `request.*` 的选项封装（`multipart` / `data` / `timeout` 原样透传，只合并头） |
| `newAiSession(base)` | `POST /api/ai/session` → `parseSessionIdText()`：**原始文本正则**取 `"sessionIdText":"…"`，位数 < 16 或只有 JSON number 即抛错 |
| `parseSessionIdText(text)` | 纯函数（可静态自检） |
| `seedSessionStorage(page, sid)` | 新页签预置现场：同时写 `ai-identity` + `ai-session-id:<t>:<u>`（只写裸键会既没身份也没号） |
| `pageSessionKey` / `pageMirrorKey` / `pageDraftPrefix` | 身份键形：`ai-session-id:<t>:<u>` / `ai-mirror:<t>:<u>:<sid>` / `cm.ai.form.draft.<t>:<u>.` |
| `pageIdentity` / `pageSessionId` / `expectSameIdentity` | 页面身份与号；**`pageSessionId()` 强制"页面身份 == 直连身份"**，不一致直接抛错（跨身份取证会读别人的历史） |
| `pageSessionKeyOf` / `pageMirrorKeyOf` | 浏览器上下文按 `ai-identity` 现场求键（spec 里 `page.evaluate` 用） |

## 2. 逐文件改动

### 2.1 共享助手（改一处，覆盖全族）

| 文件 | 改动 |
| --- | --- |
| `support/helpers.ts` | `data()` 按路径加身份头（`api.get/post/put` 全族受益）；`sessionId(page)` 改为 `pageSessionId()`（身份键 + 同身份校验） |
| `support/helpers-srp.ts` | `chatFrames`/`eventsRaw` 走 `aiFetch`；`readMirror()` 改按页面身份求镜像键 |
| `support/helpers-kqc.ts` | `httpRaw`（含 `/context`、`/kb/*`、`/feedback`）与 `chatRound` 走 `aiFetch`；**`newSessionId()` 改为 `await` 版后端发号**（`caseId` 只留调用点可读性） |
| `support/helpers-nfezui.ts` | `apiGet`/`apiRaw` 走 `aiRequestOptions`；`sessionIdOf` → `pageSessionId`；`storageState()` 的会话键/镜像键改身份键形 |
| `support/mdo.ts` | `postChat`/`openSse`/`eventsRaw`/`runDetail`/`confirmOn`/`frontendResultOn`/`cancelOn`/`chatError` 全部走 `aiFetch`//`aiRequestOptions`；`activeRunId` 改 `pageSessionId` |
| `support/unlock.ts` | `chatStream` 走 `aiFetch` |
| `support/hgta.ts` | `apiGet`/`apiPost`/`apiPostRaw` 走 `aiRequestOptions`；`chatFrames` 加头；`sidOf` → `pageSessionId` |
| `support/helpers-mdxy.ts` / `support/helpers-vl.ts` | **未改**：只访问 `/api/ai/health`（排除面，按口径**不加头**） |

### 2.2 spec（直连点 + 会话号 + 键形）

| 文件 | 改动 |
| --- | --- |
| `10-srp.spec.ts` | S-05/S-07 本地号 → `newAiSession(API_BASE)`；`chat`/`events`/`context` 直连加头；R-11 重开页签改用 `seedSessionStorage`（身份 + 身份会话键） |
| `12a-vl-vis.spec.ts` | V14 两轮 `chat` → 后端发号 + `aiIdentityHeaders()`（`/tools` 为排除面，不动） |
| `15-mdo.spec.ts` | **13 处** `mdo-*` 本地号 + 1 处内联"另一会话"号 → 后端发号；`runs/current`、`history`、`runs` 直连加头；页面会话号/poll 改 `pageSessionId`（新页签未发号时按"还没换出来"继续等，不误判）；残留镜像断言改 `pageMirrorKeyOf`；"种号后 reload" 改身份会话键 |
| `16-kqc.spec.ts` / `16-kqc-b` / `16-kqc-c` | 23 处 `newSessionId()` → `await`（后端发号）；K08 的并发构造改 `map(async …)`；`/history` 直连加头 |
| `17-unlock.spec.ts` / `17b-unlock-budget.spec.ts` | `/events` 直连加头；页面会话号改 `pageSessionId`（导入用别名避免与局部变量同名） |
| `18-real-model.spec.ts` | `chatProbe` 走 `aiFetch`；会话号由 `newSessionId()`（后端发号）提供 |
| `11a-hgta.spec.ts` / `11b-hgta.spec.ts` | `/events`、`/form/attachments`、`/form/option-sources`（含"伪造 URL 必 400"那条）加头；6+3 处 `e2e-h*/g*` 本地号 → 后端发号 |
| `14b-mdxy-x.spec.ts` | 3 处 `x02/x06/x08` 本地号 → 后端发号（`/health` 是排除面，不动） |
| `13-nfezui.spec.ts` | 3 处"复制页签"预置 → `seedSessionStorage`（并轮询 `sessionIdOf`）；反馈夹具的假会话号 → 后端发号 |
| `03-hitl.spec.ts` / `08-session.spec.ts` | 页面会话号改 `sessionId(page)`；镜像断言改 `pageMirrorKeyOf`（新/旧两个键都按身份算） |
| `00/07/12b/14c` 等 | **未改**：只访问 `/api/ai/health` 或 `/api/ai/tools`（排除面） |

数量：`newAiSession(...)` 32 处、`await newSessionId(...)` 23 处、`aiRequestOptions(...)` 28 处、`aiFetch(...)` 15 处；净变化 +234 / −179（22 文件）。

## 3. 需求映射

| 要求 | 落地 |
| --- | --- |
| 1) 对话数据面带合法身份头，与前端排除面一致；业务/模型配置不加头 | `aiHeaders()` 按后端 filter 同构判定；`data()`/`httpRaw`/`apiGet`/`chat*`/`events*`/`runs*`/`confirm`/`frontend-tool-result`/`cancel`/`form/*`/`context`/`history`/`feedback`/`kb` 全部走它；`/api/ai/health`、`/api/ai/tools`、`/api/tasks`、`/api/jobs` 保持原样（静态核对：排除面 0 处加头） |
| 2) 新会话先 `POST /api/ai/session` + 优先 `sessionIdText`，禁止本地造号与 JSON number | `newAiSession()` 只读原始文本里的 `sessionIdText`，位数 < 16 即抛错；**全部**本地号（`mdo-*`×14、`kqc-*`×23、`e2e-*`×9、`srp-*`×2、`vl-*`×2、`x0*-*`×3、`nfezui-f04-session`×1）已删除 |
| 3) 镜像/草稿/会话键改身份键形；页面生成身份与直连身份一致 | `pageSessionKey/pageMirrorKey/pageDraftPrefix` 统一键形；`pageSessionId()` 内置"页面身份 == 直连身份"校验（不一致即抛错）；`seedSessionStorage()` 同时写 `ai-identity` |
| 4) 保持断言强度、不无头降级、不删用例 | **零用例删除、零 skip 新增**；所有断言值原样保留（只改取证方式/键形/发号来源）。两处语义增强：`pageSessionId()` 失配从"读到空"变为**显式报错**；`newAiSession()` 把 `sessionIdText` 位数下限变成硬断言 |
| 5) 只做静态/精确检查，禁止跑 E2E/批量 | 见 §4 |

## 4. 精确检查（本轮实际执行）

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 类型检查（全部 e2e spec + support） | `npx tsc --noEmit --skipLibCheck --target es2022 --lib es2022,dom,dom.iterable --module esnext --moduleResolution bundler --types node --strict *.spec.ts support/*.ts` | **0 新增错误**（仅既有噪声：`/src/...` 浏览器侧动态 import 路径 TS2307、`FeedbackEntry.reason`、`liveStream.error` 三处改动前即存在） |
| 关键模块逻辑自检（43 条断言） | `npx tsc support/ai-identity.ts --outDir /tmp/aicheck ...` + `node /tmp/aicheck/check.cjs` | **STATIC-CHECK PASS**：路径分类 33 例（含 `?page=` 查询串、`/api/aihealth`、`/api/aix/*`、`/api/ai/healthz` 边界）+ 头合并 3 例（chat 有头 / health 无头 / providers 无头）+ `sessionIdText` 解析 4 例（只认字符串、位数 < 16 抛、仅 number 抛）+ 键形 3 例 |
| 直连点覆盖扫描 | 脚本遍历 `*.spec.ts`/`support/*.ts`，找所有含 `/api/ai` 的 `request.*`/`fetch`/`aiFetch` 调用 | **26 个对话数据面调用点，0 未加头**；排除面（health/tools/providers/models/settings）**0 处加头** |
| 本地造号残留扫描 | `grep -rn "sessionId: \`…\`\|setItem('ai-session-id'\|newSessionId(" frontend/e2e` | 0 命中（`newSessionId` 只剩 `await` 调用点与定义本身） |
| 裸键残留扫描 | `grep -rn "getItem('ai-session-id')\|ai-mirror:\${" frontend/e2e` | 仅剩 `13-nfezui` 的**反证**断言（`localStorage.getItem('ai-session-id')` 必须为 null）；其余全部走身份键或按前缀过滤（`startsWith('ai-mirror:')` / `cm.ai.form.draft.` 仍成立） |
| 空白/冲突标记 | `git diff --check` | 无输出（仅 `core.autocrlf` 的提示） |

**未执行（按要求）**：`npx playwright test`（任何 config）、`verify-e2e.ps1`、集群探针、任何服务起停与端口连接。

## 5. 未覆盖 / 已知风险（如实登记）

1. **没有真跑**：结论全部来自静态核对 + 类型检查 + 纯函数自检。运行期行为（尤其身份头是否被网关/代理改写、`sessionIdText` 实际位数、多实例下会话登记）只在 CI 的 e2e job 首次真正验证。
2. **身份默认值依赖目录**：`AI_IDENTITY` 默认 `1/101`（后端目录首项）。若某部署改了预置目录，必须用 `E2E_TENANT_ID`/`E2E_USER_ID` 同步，否则全部对话请求 400 —— 这是**响亮失败**（不会假绿）。
3. **多实例假设**：`15-mdo` 的 D/M 域在后端 A 上发号、在 A/B 上消费，依赖两实例**共用同一 Redis DB**（mdo 抬头已声明的前提）；若拆库，需改为"在目标实例上发号"。
4. **`newSessionId(caseId)` 的 `caseId` 只保留可读性**：号值与用例无关（后端发号），函数签名保留是为了调用点自解释；已加"空 caseId 抛错"防误用。
5. **`pageSessionId()` 变严格**：页面尚未发号或身份不符时抛错（此前返回 `''`/`null` 让后续断言能"响亮失败"）。新页签类轮询已按"还没换出来"处理（`15-mdo` M07 显式 catch；`13-nfezui` 用 `expect.poll` 自动重试）。
6. **`support/helpers-mdxy.ts`、`support/helpers-vl.ts` 未改**：只访问 `/api/ai/health`（排除面）；VL 域的 chat 直连在 `12a-vl-vis` 内已加头。
7. **既有类型噪声未修**：`/src/...` 动态 import 路径、`FeedbackEntry.reason`、`liveStream.error` 三处改动前即存在，不属本任务（未改断言/未加 `any` 掩盖）。
8. **未处理**：`13-nfezui` 的 `fulfillTruncatedStream()` 伪造的 `start` 帧里 `sessionId: 'nfezui'` 是**帧载荷字段**（不是请求参数、不参与后端会话校验），保持原样。

## 6. 未触碰清单

- `frontend/src/**`、`frontend/tests/**`、`scripts/**`、`backend/**`
- `frontend/playwright*.config.ts`、`frontend/e2e/support/harness.ts`（含 `URLS`/起停/端口）、`support/harness-*.ts`、`support/*-upstream.mjs`、`support/hgta-global-setup.ts`、`global-setup*.ts`
- 任何 `.spec.ts` 的用例集合（未删、未 skip、未降断言）
