# Task 5 报告：验收脚本与工作流全量梳理（身份头 / 后端发号 / 实例位）

- 状态：**完成**（仅静态检查；按要求**未**运行 E2E / 集群 / 批量测试）
- 改动面：`scripts/**`、`.github/workflows/**`（业务数据与模型配置、生产代码、`frontend/src`、backend 测试**未触碰**）
- 未推送。

## 0. 一句话结论

AI 对话数据面的每个脚本请求现在都由**统一入口**附加 `X-Tenant-Id` / `X-User-Id`（业务与模型配置不加），
会话号一律由 `POST /api/ai/session` 发号并只读权威字段 `sessionIdText`（十进制字符串），
多实例 harness（双实例 / 三实例）补齐**互不相同**的 `--app.instance-slot`，
并在 `verify-e2e.ps1` 末尾新增 4 条**租户验收**用例（身份边界 / 发号契约 / 会话归属 / 对话隔离）。

## 1. 逐文件改动

| 文件 | 改动 |
| --- | --- |
| `scripts/verify-e2e.ps1` | ① 身份层：`Test-AiDataPath` + `Add-AiIdentityHeaders`（与后端 `TenantIdentityFilter#shouldNotFilter` 同口径：`/api/ai` 下除 `health`/`tools`/`tenants`/`startup-resume(+scan)` 精确路径与 `providers`/`models`/`settings` 子树），接入 `Invoke-Api` / `GetJson` / `PostJson` / `PutJson` / `DeleteJson` / `GetBytes` / `Read-SseCore` / `Read-Sse-Brief` / `Read-Sse-Brief-Post` / `Ai-FormUpload` / `Ai-Headers`；`Invoke-Api` 与四个 JSON 帮助函数新增可选 `$identity`（供跨身份取证）。② 发号：`Ai-NewSid` 改为 `POST /api/ai/session`，新增 `Get-SessionIdText`（**原始响应文本正则**取 `sessionIdText`，绝不读 `data.sessionId` 数字），并锁 `位数 ≥ 16`（>2^53）；15 处本地造号全部替换（`aiSession15` / `session16` / `session16b` / `gf1..gf6` / CTX 循环 / `session17` / `session17b` / `sa` / `sb` / `sc` / `session23`）。③ 新增末尾「租户/用户身份验收 TENANT-AI-1..4」，计入主 PASS/FAIL 与退出码（不进入 AIE2E manifest 的 131 条）。④ 头部注释同步口径。 |
| `scripts/b23l1-dual-instance.ps1` | ① 身份层同上（`GetJson` / `PostJson` / `Read-Sse` 自动加头）；后台作业也带身份：`Start-PostJob`（confirm/resume 并发发令枪）与 wake 长连接订阅作业把 tenant/user 作为启动参数传入并在作业内加头。② 新增 `New-AiSession`（`POST /api/ai/session` + `sessionIdText`），4 处本地 session id 替换（`New-SuspendedExportRun` / `full` / `diff` / `rate-limit`）。③ `Get-RunTag`：run 级键 tag 改为 `{<tenant>:<user>:run:<runId>}`，幂等索引 / 13 键同槽 / per-run epoch 计数器三处直接取证同步（旧 `{run:<id>}` 会读到 legacy 命名空间 → 假红）。④ 实例位 A=`0` / B=`1`（`--app.instance-slot`），`-NoStart` 文档写明两边必须不同。 |
| `scripts/ci/cluster3-probe.mjs` | ① `httpRequest` / `openSse` 统一按 `aiDataPath()` 加身份头（含经 HAProxy 的代理路径）。② 新增 `newSession(key, tag)`（读 `sessionIdText`，位数 ≥16），替换 8 处会话号（`createSuspendedRun` / `runPlainChat` / `seq` / `stream-kill` / `readiness` / `draining` / `draining-takeover` / `purge-active` / `redis-outage`（`pauseRedis` 前先取号）/ `redis-recovery`（每次重试取新号））。③ `INSTANCES` 增 `slot`，`instanceArgs` 加 `--app.instance-slot=<0|1|2>`（h2tcp 属多实例 profile，缺配置后端启动即失败），`selfTestInstanceArgs` 断言三实例实例位互不相同且在 `0..999`。④ 直接 Redis 取证的键形同步：幂等索引 `ai:idem:{<t>:<u>:run:<id>}`、purge durable pending `ai:purge:pending:<t>:<u>:<sid>`、认领表 `ai:claim:{<t>:<u>:run:<id>}`；L-150 自证的源码断言由 `return "{run:" + runId + "}"` 改为 `RunKeyScope` 事实源（`tag` / `tagContent` / `RUN_MARK`）。 |
| `scripts/chat-panel-smoke.mjs` | ① 直连后端的取证请求加身份头：`historyCount` / `historyPayload` / `runStatus` / `runCurrentFound`；身份取值 = `SMOKE_TENANT_ID`/`SMOKE_USER_ID` > 页面 store `ai.identity` > `ai-identity` 存储 > 目录首项（1/101）。② 浏览器键形改为**身份无关匹配**（`ai-session-id[:<t>:<u>]`、`ai-mirror:[<t>:<u>:]<sid>`）：快照、清键循环、③ 的旧镜像断言、⑭ 的镜像读取一并适配（断言强度不变：仍要求「旧镜像已删 / store 与 storage 同步 / 新 id 记忆记在新 id 上」）。 |
| `scripts/m2t2a-smoke.mjs` | ① 新增 `pageAiIdentity` / `aiHeaders` / `identityFingerprint` / `newAiSession`（`sessionIdText`，位数 ≥16）。② 阶段 3 会话号改为后端发号；`POST /api/ai/chat` 加身份头。③ 阶段 3b 注入改为带身份指纹的键：写 `ai-identity`、`ai-session-id:<t>:<u>`、删 `ai-mirror:<t>:<u>:<sid>`。 |
| `.github/workflows/ci.yml`、`nightly.yml` | 单实例 E2E 后端启动补 `APP_INSTANCE_SLOT=0`（单进程默认 0，显式写出便于启动日志审计；避免以后改多实例时遗漏）。业务/模型配置参数未改。 |
| `scripts/README.md` | 口径同步：脚本表、1.1 前置（身份头 + `sessionIdText`）、1.4 新增 TENANT-AI-1..4 行、3.2（实例位 A=0/B=1）与 b23l1 的 AI 身份/发号段。 |

## 2. 需求映射

| 要求 | 落地 |
| --- | --- |
| 1) 所有 AI 对话 HTTP/SSE 请求统一带合法身份头，业务/模型配置不强加 | 各脚本的**唯一** HTTP/SSE 出口集中加头，判据函数与后端 filter 逐条同构（精确排除面 + 配置子树）；`TENANT-AI-1` 反向证明「缺头 400」「模型配置面不因缺头 400」。 |
| 2) 不再本地生成 sessionId；新会话先 `POST /api/ai/session` 且优先 `sessionIdText`，禁止 JSON number 精度丢失 | 全部 20+ 处本地号改为后端发号；三份脚本（PS/Node/Node）都从**原始文本**正则取字符串并断言位数 ≥16；`TENANT-AI-2` 锁「`sessionIdText` 权威 + `sessionId` 兼容同值」。 |
| 3) 多实例补不同 `APP_INSTANCE_SLOT`，避免同号；不改业务/模型配置 | b23l1 A=0/B=1；cluster3 A=0/B=1/C=2（并在 `--self-check` 静态断言互异）；CI/nightly 单实例显式 `APP_INSTANCE_SLOT=0`。 |
| 4) 更新原断言保持强度，不无头降级、不删用例 | 所有断言值不变（只改「取证方式」与「键形/身份」）；`chat-panel-smoke` 的镜像断言改成身份无关匹配而非删除；cluster3 的 L-150 自证改为新事实源，覆盖面未缩小（反而多钉了 `RunKeyScope` 与实例位互异）。 |
| 5) 只做静态检查，禁止跑 E2E/集群/批量测试 | 见 §3；未启动任何服务、未连任何端口（cluster3 只跑 `--self-check`）。 |

## 3. 精确检查（本轮实际执行）

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| PowerShell 语法（verify-e2e） | `powershell.exe -NoProfile -Command "[System.Management.Automation.Language.Parser]::ParseFile('scripts/verify-e2e.ps1',[ref]$null,[ref]$e)"` | `PARSE-OK`（0 error） |
| PowerShell 语法（b23l1） | 同上，目标 `scripts/b23l1-dual-instance.ps1` | `PARSE-OK` |
| Node 语法 ×3 | `node --check scripts/ci/cluster3-probe.mjs`、`node --check scripts/chat-panel-smoke.mjs`、`node --check scripts/m2t2a-smoke.mjs` | 全部 OK |
| 集群探针静态自证 | `node scripts/ci/cluster3-probe.mjs --self-check` | **PASS 29 / FAIL 0 / SELF-CHECK PASS**（含新增「实例位互异」断言与改写后的 L-150 契约） |
| 文档一致性门禁 | `node scripts/ci/check-docs.mjs` | **6/6 全绿**（含命名纪律 ⑤、引用文件存在 ③） |
| 工作流 YAML 可解析 | `python -c "yaml.safe_load(...)"` × 5 个 workflow | 5/5 `OK` |
| 空白/冲突标记 | `git diff --check` | 无输出（仅 core.autocrlf 的 "LF will be replaced by CRLF" 提示） |
| 无残留本地会话号 | `grep -n "'s5b-' + \|'gfc-' + \|sessionId = .*NewGuid" scripts/*.ps1 scripts/*.mjs scripts/ci/*.mjs` | 0 命中（`[Guid]` 仅剩任务标题/消息 token/临时目录） |
| 身份头覆盖面 | `grep -n "Add-AiIdentityHeaders\|aiIdentityHeaders\|apiHeaders()\|aiHeaders()" scripts/**` | 每个 AI 出口各 1 处（见 §1） |

**未执行（按要求）**：`verify-e2e.ps1`、`b23l1-dual-instance.ps1`、`cluster3-probe.mjs --run`、`chat-panel-smoke.mjs`、`m2t2a-smoke.mjs`、Playwright、`mvn`、任何批量 `node --test`。

## 4. 未覆盖 / 已知风险（如实登记）

1. **没有真跑**：以上均为静态检查。新增的 `TENANT-AI-1..4` 与所有身份头/发号路径的**运行期**行为只经代码审查，未在真实后端上验证；首次真实执行在 CI（`ci.yml` / `nightly.yml` 的 e2e job）。
2. **`TENANT-AI-3` 的 `complete=true` 依赖**：该断言假设「只有会话记录、无 run/上下文/附件的纯会话」销毁后 `complete` 为真（依据 `PurgeResult.complete = errors.isEmpty() && deferred.isEmpty()`）。若产品在守卫/复核环节新增可重试错误，这条会红——属**真实信号**（说明销毁没删干净），不是脚本假红。
3. **`TENANT-AI-4` 需要一个收敛轮**：与既有 TC18 同口径（`$script:continueHandler` + 2 次挂起上限）。桩模式下走路 `turn-text` 必然收敛；真模型模式由「请只回复一句确认」诱导文本轮，若模型坚持调用工具，`continueHandler` 会拒绝确认门并最终收敛（与 TC18 相同风险面）。
4. **发号位数下限**：`sessionIdText` 位数 ≥16 的断言依赖编号结构（`ts×10^6 + slot×10^3 + seq`）。当前 ms 级时间戳给出 19 位，下限安全到约公元 2255 年；这不是"未来永真"的断言，属契约强度锁。
5. **`frontend/e2e/**.spec.ts` 未适配身份（**超出本任务允许面**）**：Playwright 套件里直连后端的辅助（`frontend/e2e/support/helpers.ts` 的 `data()`、`helpers-kqc.ts`、`helpers-nfezui.ts`、`helpers-srp.ts`）没有 `X-Tenant-Id`/`X-User-Id`，其 `/api/ai/history`、`/api/ai/session` 等直连调用会被 400。页面驱动的断言不受影响（前端已统一带头）。这一面属 Task 4/frontend，本批**未改**，需后续补齐。
6. **`scripts/redis-run-migration.mjs` 仍是旧键形**：它把裸 run 键迁到 `{run:<runId>}`，而 Task 2 起目标 tag 含身份（`{<t>:<u>:run:<id>}`），且 legacy 键本身**没有**身份可推断。本批未改（不在「redis5/cluster smoke」面内），需要一次显式设计决策（迁移时身份从哪来 / 是否停用该工具）。
7. **实例位唯一性只能审计**：单进程无法校验"各实例不同"，只能靠启动日志（`instanceSlot=…`）与脚本侧的静态互异断言（cluster3 `--self-check`、b23l1 固定 0/1）。用 `-NoStart` 接已就绪实例时，脚本无法强制外部实例的实例位。
8. **`chat-panel-smoke` 身份取值有兜底**：页面 store 未就绪时会退回 `ai-identity` 存储或目录首项（1/101）。若部署的目录首项不是 1/101 且 store 尚未就绪，直连取证会用错身份 → `count>0` 断言**响亮失败**（不会假绿），但会表现为"取证失败"而不是"身份缺失"。
9. **`scripts/redis5-cluster-smoke.sh` 无需改动**：该脚本不发起任何 HTTP 请求（只连 Redis 容器），无 AI 身份面。
10. **业务/模型配置未加身份头**：按产品口径（不隔离）刻意不做；`TENANT-AI-1` 以 `/api/ai/providers` 的反向断言锁定这条分界。

## 5. 未触碰清单

- `backend/src/main/**`、`backend/src/test/**`（其他任务在制改动原样保留）
- `frontend/src/**`、`frontend/tests/**`、`frontend/e2e/**`
- 业务数据接口（`/api/tasks`、`/api/jobs`、`/api/definitions`、`/api/data/**`）与 AI 模型配置（provider/model/setting/KB/audit）的请求参数与设置
- `.github/workflows/e2e-real.yml`、`cluster-3node.yml`、`redis-5-smoke.yml`（后者不涉后端；前两者的后端均为单实例无 profile，实例位默认 0；三实例的实例位由探针参数提供，见 §1）
