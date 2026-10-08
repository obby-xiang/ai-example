# M2-T2a 施工复现 —— GLM-5.3（独立复核）

> 角色：复核员。独立复现验证，不信任施工方结论，一切以亲手跑出的结果为准。零 git 写操作。
> 仓库：`<REPO_ROOT>`，分支 `main`，复现 HEAD=`1e2edbb`（git pull 确认 Already up to date，与施工声明一致）。
> 复现对象：`docs/evidence/M2-T2a-施工与验证-K2.8.md`（K2.8 施工验证声明）四项。
> 证据等级：**【实测】** = 本次真实执行输出；**【推断】** = 基于 diff 的静态反证推演（零 git 写操作约束下无法回滚代码实跑旧形态）。
> 脱敏：路径用 `<REPO_ROOT>`/`<MAVEN_HOME>`/`<SCRATCH>`，密钥统一 `***`，后端端口以实际复现端口 18330 记录。
> 复现日期：2026-10-08。本报告未提交（untracked）。

---

## 0. 结论速览

| 项 | 施工声称 | 复现实测 | 对账 |
|---|---|---|---|
| 后端 mvn test | 249 执行 / 0 失败 | `Tests run: 249, Failures: 0, Errors: 0, Skipped: 0` + BUILD SUCCESS【实测】 | ✅ 一致 |
| 前端 typecheck | 绿 | EXIT=0【实测】 | ✅ 一致 |
| 前端 build | 绿 | EXIT=0【实测】 | ✅ 一致 |
| CDP 冒烟三断言 | 两轮全 PASS EXIT=0 | 两轮全 PASS EXIT=0，各阶段计数与施工文档**逐字一致**【实测】 | ✅ 一致 |
| 断言有效性 | 三断言可判别缺陷 | ②③判别有效；①在本构造下为回归守卫而非缺陷判别（详见 §4，与决策卡勘误 5 边界一致，不构成严重问题）【推断】 | ⚠️ 有边界说明 |

**总体结论：复现通过。** 四项验证声明全部独立复现成功；偏差 2 项（§5），均不阻断。

---

## 1. 复现环境【实测】

| 项 | 值 |
|---|---|
| HEAD | `1e2edbb`（`git pull --ff-only` → Already up to date；工作区除他人未跟踪文件外干净） |
| Memurai | 127.0.0.1:6379 LISTENING（PID 3596，系统既有服务，复核未启停） |
| 端口预检 | 启动前 netstat：18330 / 5202 / 9333 均空闲 |
| 后端 | `java -jar target/config-mgr.jar --server.port=18330`（backend/ 目录；AI key `***` 经环境变量 `AI_API_KEY` 注入，未写入任何文件；base-url/model 走配置默认 `https://api.deepseek.com` / `deepseek-flash`） |
| 后端探活 | `GET /api/ai/health` → `{"available":true,"code":"AI_AVAILABLE","model":"deepseek-flash","baseUrl":"https://api.deepseek.com",...,"redis":{"available":true,...}}` |
| 前端 | `yarn dev`（5202；`VITE_API_BASE=http://localhost:18330` 以**进程环境变量**注入覆盖 `.env.local` 的 18318 —— vite `loadEnv` 中 `process.env` 的 `VITE_*` 优先于 env 文件，`.env.local` 未改动；`vite.config.ts:14` `env.VITE_API_BASE || DEFAULT_API_BASE` 实读） |
| 冒烟 | `node scripts/m2t2a-smoke.mjs`，Node 内置 WebSocket+fetch 直连 CDP 9333，独立 Chrome（`<SCRATCH>/m2t2a-smoke-out-glm/chrome-profile`），产物落仓库外 `<SCRATCH>/m2t2a-smoke-out-glm/` |

与施工文档环境差异说明：施工用后端 18318（改 `.env.local`），复现用 18330（环境变量覆盖）。两者等效——均满足"后端从 18330 起分配端口、前端代理指向实际后端端口"的复现要求，且复现方式未触碰任何仓库文件。

---

## 2. 四项复现原始输出摘录

### 2.1 后端 mvn test【实测】

命令：`<MAVEN_HOME>/bin/mvn.cmd -B test`（backend/ 目录，施工声明的同一全路径 Maven）

Tests run 行原文（汇总行，另逐测试类 35 行 `Tests run: ... -- in com.example.configmgr.*` 均为 0 失败）：

```
[INFO] Tests run: 249, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

> 声称 249 执行 / 0 失败 —— **实测一致**。

### 2.2 前端 typecheck + build【实测】

```
$ yarn typecheck   →  vue-tsc --noEmit / Done in 4.86s.          TYPECHECK_EXIT=0
$ yarn build       →  vite v6.4.4 ... ✓ built in 31.06s / Done in 37.65s.  BUILD_EXIT=0
```

> 声称双绿 —— **实测一致**（退出码均 0）。

### 2.3 CDP 冒烟三断言（复跑两轮，均 EXIT=0）【实测】

命令：`SMOKE_OUT=<SCRATCH>/m2t2a-smoke-out-glm node scripts/m2t2a-smoke.mjs`

第一轮（`result-20261008T112944Z.json`）与第二轮（`result-20261008T113020Z.json`）输出**逐字一致**：

```
[chrome] pid = ...（两轮各自独立 pid）
[cdp] browser = Chrome/154.0.8037.98
[app] ready
[阶段1 双消息] {"formRendererCount":1,"cancelButtons":1,"pendingCards":2,"pendingStatuses":["pending","pending"],"activeFormStatus":"filling","cancelledTags":0}
[断言②] PASS {"formRendererCount":1,"cancelButtons":1,"expect":1}
[阶段2 取消] {"formRendererCount":0,"cancelButtons":0,"pendingCards":2,"pendingStatuses":["cancelled","cancelled"],"activeFormStatus":"cancelled","cancelledTags":2}
[断言③] PASS {"pendingStatuses":["cancelled","cancelled"]}
[断言③b] PASS {"formRendererCount":0,"cancelledTags":2}
[阶段3 刷新前] {"formRendererCount":1,"cancelButtons":1,"pendingCards":1,"pendingStatuses":["pending"],"activeFormStatus":"filling","cancelledTags":2}
[阶段3 刷新后] {"formRendererCount":0,"cancelButtons":0,"pendingCards":1,"pendingStatuses":["expired"],"activeFormStatus":null,"cancelledTags":2}
[断言①] PASS {"pendingCards":1,"formRendererCount":0,"pendingStatuses":["expired"]}
[done] result written to <SCRATCH>/m2t2a-smoke-out-glm/result-<时间戳>.json
SMOKE_EXIT=0
```

result-<时间戳>.json 断言明细（第二轮原文，第一轮同构）：

```json
"assertions": {
  "②":  { "pass": true, "detail": { "formRendererCount": 1, "cancelButtons": 1, "expect": 1 } },
  "③":  { "pass": true, "detail": { "pendingStatuses": ["cancelled","cancelled"] } },
  "③b": { "pass": true, "detail": { "formRendererCount": 0, "cancelledTags": 2 } },
  "①":  { "pass": true, "detail": { "pendingCards": 1, "formRendererCount": 0, "pendingStatuses": ["expired"] } }
}
```

> 声称三断言两轮全 PASS —— **实测一致**（含各阶段中间计数与施工文档 §2.4 全部逐字吻合）。

### 2.4 断言有效性反证推演【推断】

方法：读 `scripts/m2t2a-smoke.mjs` 全文 + `git show 22893ba` 前后 diff（零 git 写操作，无法实跑回滚形态，静态推演并如实标注）。"原缺陷形态"取决策卡 T2a-D#2/#3 所修缺陷：双 FormRenderer、取消不收敛。

**断言②（无双份工具卡）—— 会 FAIL，判别有效。**
计数式 `[...document.querySelectorAll('button')].filter(b => b.textContent.trim() === '提交').length`。改动前 `AiPanel.vue` 的 FormRenderer `v-if="liveForm && liveForm.toolCallId === m.pendingCall.toolCallId"` 无宿主门控（`m.id === formHostMessageId` 为 D#3 新增）：双消息各持同 toolCallId 的 pendingCall，两条同时满足条件 → 渲染 2 个 FormRenderer → formRendererCount=2、cancelButtons=2 → `=== 1` 不成立 → FAIL。且若 liveForm 意外为 null 则计数 0 亦 FAIL（不会假阳）。**非永真。**

**断言③（取消后收敛 cancelled）—— 会 FAIL，判别有效。**
断言 `pendingStatuses.length === 2 && every(s => s === 'cancelled')`，前有 12×500ms 轮询等待。改动前 `cancelGenerativeForm` 仅置 `form.status = 'cancelled'`（activeForm 投影），pendingCall 投影不动；对伪造 toolCallId 的回灌走 409 UNKNOWN_TOOL_CALL（非 DUPLICATE），不触碰 pendingCall。→ pendingStatuses 停留 `["pending","pending"]`，轮询耗尽后断言 FAIL。**非永真。**

**断言①（刷新后单卡）—— 非永真，但对 T2a 改动不构成缺陷判别（回归守卫性质），且该边界已被决策卡勘误 5 明示。**
断言 `pendingCards === 1 && formRendererCount === 0`。推演"回滚全部 T2a 改动"的本构造：刷新后走 `loadHistory` 镜像恢复（单份消息、`this.messages = mirror.messages.map(...)` 整体替换，无追加重复）→ 后端空历史把 pending 降级 expired（`ai.ts:394-399`，**既有逻辑**，T2a 未触碰）→ pendingCards=1；activeForm 不进镜像（既有行为）→ formRendererCount=0 → **旧代码下本断言同样 PASS**。它并非永真（若镜像恢复翻倍消息、或 activeForm 进镜像被重建，则 FAIL），但在本构造（纯前端注入 + 空后端历史，无帧重放）下无法区分 T2a 前后——它验证的是"刷新路径未引入重复卡"的回归基线，而非 T2a 所修缺陷本身。此边界与决策卡勘误 5（"T2a 阶段仅验镜像重建路径的单卡形态；完整刷新续填场景待 T2b 落地后复验"）及施工文档 §2.4 口径说明一致，**不构成"断言永真"严重问题**，登记为偏差（见 §5-2）。

补充（脚本强度正面证据）：三断言均直读 Pinia store 投影（`pendingCall.status`）与 DOM 计数，断言②③对"原缺陷形态"的判别路径完整闭环；FAIL 路径下退出码 1（`process.exit(failures.length === 0 ? 0 : 1)`），无吞错。

---

## 3. 声称 vs 实测对账表

| # | 施工文档声称 | 位置 | 复现实测 | 结论 |
|---|---|---|---|---|
| 1 | mvn test 249/0 | K2.8 §2.1 | 249 / 0 / 0 / 0，BUILD SUCCESS【实测】 | ✅ |
| 2 | typecheck 绿 | K2.8 §2.2 | EXIT=0【实测】 | ✅ |
| 3 | build 绿 | K2.8 §2.2 | EXIT=0【实测】 | ✅ |
| 4 | 健康探活 AI_AVAILABLE / deepseek-flash | K2.8 §2.3 | 一致【实测】 | ✅ |
| 5 | 冒烟阶段 1 双消息计数（7 字段） | K2.8 §2.4 | 逐字一致【实测】 | ✅ |
| 6 | 断言② PASS（1/1/1） | K2.8 §2.4 | PASS，两轮【实测】 | ✅ |
| 7 | 断言③ PASS（cancelled×2）+ ③b PASS（0/2） | K2.8 §2.4 | PASS，两轮【实测】 | ✅ |
| 8 | 阶段 3 刷新前后计数 + 断言① PASS（1/0/expired） | K2.8 §2.4 | 逐字一致，PASS 两轮【实测】 | ✅ |
| 9 | 两轮 EXIT=0 可重复 | K2.8 §0 | 两轮 EXIT=0【实测】 | ✅ |
| 10 | result-<时间戳>.json 分轮保留不复写 | K2.8 §1.5 | 两个独立时间戳文件并存【实测】 | ✅ |
| 11 | 冒烟只驱动自启 Chrome、产物落仓库外 | K2.8 §1.5 | 独立 user-data-dir 于 `<SCRATCH>`，用户浏览器未触碰【实测】 | ✅ |
| 12 | 后端零改动（409 响应体携 status / 结局帧下发 FRONTEND_CANCELLED） | K2.8 §1.6 | 复核读码确认同一事实（AiController 409 DUPLICATE 分支 / SseChatEmitter 结局帧）【实测（读码）】 | ✅ |
| 13 | 断言有效性：三断言可判别 | 隐含于 §2.4 | ②③判别有效【推断】；①为回归守卫、对 T2a 前形态不敏感（§2.4） | ⚠️ 边界成立 |

---

## 4. 偏差清单

1. **（低，文档陈述与仓库状态不一致）** 施工文档 §6 称"未执行任何 add/commit，改动未提交"；但复现 HEAD 上，前端改动+冒烟脚本已随 `22893ba` 提交、施工证据文档已随 `1e2edbb` 提交。推断为施工后另行入库（提交者/时间与施工声明不冲突：`22893ba` 2026-10-08 19:22）。不影响验证有效性（复现跑在入库 HEAD 上，内容即施工产物），但"未提交"陈述与当前仓库状态不符，如实登记。
2. **（低，断言强度边界）** 断言①在"回滚 T2a 改动"的原缺陷形态下同样 PASS（§2.4 推演）：其判别力依赖的帧重放/后端历史参与路径未纳入本构造。该边界已被决策卡勘误 5 与施工文档 §2.4 明示（完整刷新续填挂 T2b 复验），不构成隐瞒或永真断言问题；建议 T2b 复验时将断言①升级为含后端历史/重放场景。
3. **（记录，非施工偏差）** 工作区存在他人未跟踪文件 `docs/evidence/M2-T2a-施工复现-DS-V4-Pro.md`（另一位复核员产物），与本复现无关，未触碰。
4. **（记录，非施工偏差）** 复现起服方式与施工不同：后端 18330（非 18318）、前端经环境变量覆盖（非改 `.env.local`）。等效路径，且未改仓库内任何文件。

---

## 5. 复现收尾【实测】

- 已停：后端 18330、前端 dev server 5202（含 TaskStop 后残留的 vite 子进程 PID 已 taskkill）、冒烟 Chrome（脚本内 chrome.kill()，按 user-data-dir 匹配复查无残留进程）。
- netstat 终检：5202 / 18330 / 9333 无 LISTENING；6379 Memurai 保留（系统既有服务，复核未启停）。
- 未触碰用户自有浏览器进程。
- git：零写操作（无 add/commit/checkout/stash）；工作区与本报告外无任何改动。

---

## 6. 总体结论

**复现通过。** 后端 249/0【实测】、前端双绿【实测】、CDP 冒烟三断言两轮全 PASS【实测】均与 K2.8 施工验证声明一致，各阶段中间计数逐字吻合；断言②③经反证推演判别有效，断言①为已声明边界的回归守卫。偏差 2 项（§4-1/§4-2）均不阻断，建议随 T2b 复验闭环。
