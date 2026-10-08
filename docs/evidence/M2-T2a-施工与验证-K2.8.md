# M2-T2a 施工与验证 —— Kimi K2.8 Preview

> 角色：主力编码。只执行已签字决策卡（docs/M2-T2-返修决策卡.md T2a-D#1~#5），未重新裁决。
> 仓库：`<REPO_ROOT>`，分支 `main`，开工 HEAD=`a71832d`（git pull 确认 Already up to date）。
> 全程零 git 写操作，全部产出未提交。施工日期：2026-10-08。
> 证据等级：**【实测】** = 本次真实执行输出；**【推断】** = 由代码路径推演未实跑。
> 脱敏：路径用 `<REPO_ROOT>`/`<MAVEN_HOME>`/`<MEMURAI_HOME>`，密钥统一 `***`。

---

## 0. 结论速览

| 项 | 值 |
|---|---|
| 施工范围 | T2a-D#1 双投影分离 / D#2 cancelled 终态与终态单调 / D#3 聚焦指针+唯一实例 / D#4 409 中性终态；T2b 零触碰 |
| 后端改动 | **零**（409 DUPLICATE 响应体实测已携 `status`、结局帧实测已下发 `FRONTEND_CANCELLED`，D#4 无需后端微调） |
| 前端改动 | 4 文件（types/sse.ts、types/ai.ts、stores/ai.ts、components/AiPanel/AiPanel.vue）+ 入库冒烟脚本 scripts/m2t2a-smoke.mjs |
| 后端验证 | mvn test **249 执行 / 0 失败**（= 基线 249，269 @Test 静态）【实测】 |
| 前端验证 | yarn typecheck 绿 + yarn build 绿【实测】 |
| CDP 冒烟 | 三断言全 PASS，**连续两轮 EXIT=0**（可重复执行）【实测】 |
| 【待裁决】 | 2 项（见 §5，均不阻断） |

---

## 1. 改动清单（文件:行号）

### 1.1 `frontend/src/types/sse.ts`

- `:29`：`PendingStatus` 联合补 `'FRONTEND_CANCELLED'`（T2a-D#2/C2）。

### 1.2 `frontend/src/types/ai.ts`

- `:218`：`PendingToolCall.status` 联合补 `'cancelled' | 'succeeded' | 'blocked'`。
  - 说明【实测】：卡面原文只写"补 cancelled"，但 D#2 随卡映射表
    FRONTEND_RESULT→succeeded、BLOCKED→blocked 会把这两个值写进该字段，
    不扩联合则 typecheck 无法通过 —— 属执行映射表的机械必要扩充，非变更设计。

### 1.3 `frontend/src/stores/ai.ts`

| 行号 | 内容 | 卡 |
|---|---|---|
| `:165-190` | 模块级 `frameStatusToPendingStatus`（FRONTEND_RESULT→succeeded / FRONTEND_CANCELLED→cancelled / TIMEOUT→expired / REJECTED→rejected / BLOCKED→blocked）与 `isFrameDecidedPendingStatus` | D#2 映射表 |
| `:241-257` | `pendingToolCall` getter 改为遇非 pending 终态 `continue` 继续向前扫（原 `return null` 提前短路删除） | D#2/C3 |
| `:645-656` | `submitFrontendToolResult` 409 DUPLICATE 分支：读响应体 `status` 经映射表收敛 pendingCall 投影（`applyPendingTerminal`），保持"不报错"（不写 form.error、notice 文案不变） | D#4 |
| `:683-692` | `openGenerativeForm` 入口防御守卫：全消息扫描该 toolCallId 已有帧终态（`isFrameDecidedPendingStatus`）则直接 return 不挂起 | D#3/B3 |
| `:770-772` | `cancelGenerativeForm`：本地 cancel 乐观置位扩到 pendingCall 投影 —— `applyPendingTerminal(toolCallId,'cancelled')` | D#2/A2 |
| `:996-1040` | `frontend_tool_result` 帧 handler 重写：①localTerminal 集合补 `'succeeded'`（:998-1000）；②帧 status 只广播 pendingCall 投影（`applyPendingTerminal`，:1011-1026），无 status 旧帧保持原"置 null"语义；③activeForm 收敛前 `status==='filling'` 时置 notice「该表单已在其他窗口提交，本地填写已停止」（:1029-1031） | D#1、D#2 |
| `:1117-1127` | 新增 store 方法 `applyPendingTerminal(toolCallId, status)`：只覆盖 `status==='pending' \|\| status==='cancelled'`（乐观 cancelled 可被后到帧终态覆盖 —— 帧为最终裁决）的消息；**不触碰 toolRun 投影** | D#1 守卫 |

### 1.4 `frontend/src/components/AiPanel/AiPanel.vue`

| 行号 | 内容 | 卡 |
|---|---|---|
| `:204-208` | FormRenderer `v-if` 增加 `m.id === formHostMessageId` 宿主门控 | D#3 唯一实例 |
| `:407-419` | 新增 `formHostMessageId` computed：最后一条持该 toolCallId 挂起投影的消息为唯一宿主 | D#3 |
| `:216-226` | 生成式表单卡终态 tag 链改为**优先按 `m.pendingCall.status`**（cancelled/succeeded/expired/rejected/blocked），保留 activeForm 态兜底 | D#2/C2 镜像残留按终态展示 |
| `:233` | FRONTEND 通用挂起卡补 `cancelled` →「已取消」tag | D#2 |

### 1.5 `scripts/m2t2a-smoke.mjs`（新文件，入库）

- 头部含用法注释（前置条件 / 环境变量 / 三断言定义 / 退出码约定）。
- Node ≥22 内置 WebSocket+fetch 直连 CDP，独立 Chrome（独立 user-data-dir），
  只驱动自启 Chrome；产物（截图目录 + `result-<UTC 时间戳>.json`，分轮保留不复写）
  默认落仓库外 `m2t2a-smoke-out`；Chrome 路径不写死，`SMOKE_CHROME` 优先、缺省按 `ProgramFiles` 组装。

### 1.6 后端

- **零改动**。D#4 卡片允许"如需后端微调限最小改动"；实测 `<REPO_ROOT>/backend/.../AiController.java:245-248` 的 409 DUPLICATE 响应体（`duplicateToolCall(...)`）已携条目 `status`，`SseChatEmitter.java:282` 结局帧已下发 `FRONTEND_CANCELLED`（`ConfirmGate.java:396` 落库同值），前端收敛所需信息齐全，无微调必要【实测（读码）】。

---

## 2. 验证原始输出摘录

### 2.1 后端 mvn test【实测】

命令：`<MAVEN_HOME>/bin/mvn.cmd test`（backend/ 目录）

```
[INFO] Tests run: 249, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

与基线（249 执行通过 / 269 @Test 静态）一致。后端源码零改动，本轮为回归确认。

### 2.2 前端 yarn typecheck + build【实测】

```
$ vue-tsc --noEmit
Done in 4.64s.
...
✓ built in 38.10s
Done in 44.20s.
```

### 2.3 环境前置【实测】

- `netstat`：5202 / 18318 / 18330 / 9333 均空闲；6379 由 `<MEMURAI_HOME>` Memurai LISTENING。
- 后端 `java -jar target/config-mgr.jar --server.port=18318`（AI key 经环境变量注入，未写入任何文件）；
  `GET /api/ai/health` → `{"available":true,"code":"AI_AVAILABLE","model":"deepseek-flash",...}`。
- 前端 `yarn dev`（5202，代理 18318 由 frontend/.env.local 覆盖）。冒烟结束后两进程已停（taskkill 自启 PID，netstat 复查无残留）。

### 2.4 CDP 三断言（第一轮 + 复跑第二轮，两次输出一致）【实测】

命令：`SMOKE_OUT=<SCRATCH>/m2t2a-smoke-out node scripts/m2t2a-smoke.mjs`（两轮均 EXIT=0）

```
[阶段1 双消息] {"formRendererCount":1,"cancelButtons":1,"pendingCards":2,"pendingStatuses":["pending","pending"],"activeFormStatus":"filling","cancelledTags":0}
[断言②] PASS {"formRendererCount":1,"cancelButtons":1,"expect":1}
[阶段2 取消] {"formRendererCount":0,"cancelButtons":0,"pendingCards":2,"pendingStatuses":["cancelled","cancelled"],"activeFormStatus":"cancelled","cancelledTags":2}
[断言③] PASS {"pendingStatuses":["cancelled","cancelled"]}
[断言③b] PASS {"formRendererCount":0,"cancelledTags":2}
[阶段3 刷新前] {"formRendererCount":1,"cancelButtons":1,"pendingCards":1,"pendingStatuses":["pending"],"activeFormStatus":"filling","cancelledTags":2}
[阶段3 刷新后] {"formRendererCount":0,"cancelButtons":0,"pendingCards":1,"pendingStatuses":["expired"],"activeFormStatus":null,"cancelledTags":2}
[断言①] PASS {"pendingCards":1,"formRendererCount":0,"pendingStatuses":["expired"]}
```

断言口径说明：

- **① 刷新后单卡**：同 toolCallId 挂起卡 = 1 张（pendingStatuses 由 `pending` 按 loadHistory
  空历史既有规则降级 `expired` 展示 —— 旧镜像/空历史降级口径，卡不重复）。刷新后
  `formRendererCount=0` 为 T2a 预期（activeForm 不进镜像、刷新续填属 T2b 范围，本包不承诺）。
- **② 无双份工具卡/挂起卡**：GFd 双消息构造（同一帧投两条消息，复现双卡缺陷形态）下，
  FormRenderer 实例 = 1、提交/取消按钮各 1 —— D#3 宿主门控生效；两条消息各持 pendingCall
  的"卡头"形态按设计保留（卡面只约束 FormRenderer 唯一实例）。
- **③ 取消后消息级收敛**：点唯一取消按钮后，**两条**消息的 `pendingCall.status`
  均收敛为 `cancelled`（乐观置位 + 投影广播），双卡同显「表单已取消」tag、FormRenderer 归零。

方法学说明【实测】：三断言均经 CDP `Runtime.evaluate` 直读 Pinia store 与 DOM 计数，
帧为注入构造（与 GFd 冒烟同法），取消回灌对伪造 toolCallId 返回 409 UNKNOWN_TOOL_CALL
（构造态预期，GFd §四已归因）；断言③ 收敛由本地乐观置位驱动，帧裁决路径（他端先提交
→ succeeded 覆盖乐观 cancelled）由 §3 静态守卫 + typecheck 保障，未在本构造态实跑【推断】。

---

## 3. 关键守卫的自检（typecheck 覆盖点）

- 映射表穷尽：`frameStatusToPendingStatus` 返回类型收窄至 `PendingToolCall['status'] | null`，
  五支映射全部由 D#2 卡面给定【实测（typecheck 绿）】。
- 广播域双守卫：帧 handler 内 toolRun 侧（localTerminal 含 succeeded）+ `applyPendingTerminal`
  只写 pendingCall 投影，两投影无交叉写【实测（typecheck + 冒烟②）】。
- 终态单调：`applyPendingTerminal` 覆盖集 = `pending | cancelled`；帧已落 succeeded 后
  本地 cancel 的乐观写不再命中（cancelGenerativeForm 先置 `form.status='cancelled'` 后经
  `applyPendingTerminal` 只碰 pending/乐观 cancelled 条目）【推断】。

---

## 4. 与卡片的对应核对

| 卡 | 要求 | 落点 | 状态 |
|---|---|---|---|
| D#1 | 广播只作用 pendingCall 投影、仅覆盖 pending、toolRun 终态不改写、localTerminal 补 succeeded | ai.ts:996-1026、1117-1127 | ✅ 实测（冒烟②） |
| D#2 | 联合补 cancelled / FRONTEND_CANCELLED；映射表；乐观置位+帧裁决不降级；镜像兼容；getter 续扫；他窗提示 | types 两文件、ai.ts 全节、AiPanel tag 链 | ✅ 实测（冒烟①③） |
| D#3 | activeForm 聚焦指针、同 toolCallId 一个 FormRenderer；openGenerativeForm 终态守卫 | AiPanel:204-419、ai.ts:683-692 | ✅ 实测（冒烟②） |
| D#4 | 409 DUPLICATE 不报错、中性终态收敛；后端微调最小化（实测为零） | ai.ts:645-656 | ✅ 实测（读码确认后端已携 status） |
| D#5 | 不引 vitest、CDP 三断言脚本化入库可重复 | scripts/m2t2a-smoke.mjs，两轮 EXIT=0 | ✅ 实测 |
| 禁止事项 | runs/current、session→run 索引、reattach 路线、ci.yml、docs/adr、端口分配 | 全部零触碰（git diff 可证） | ✅ |

---

## 5. 偏差与【待裁决】清单

1. **【待裁决】（低）** `PendingToolCall.status` 联合在卡面"补 cancelled"之外扩了
   `'succeeded' | 'blocked'`（types/ai.ts:218）。理由：D#2 随卡映射表把这两个值写进该字段，
   不扩则 typecheck 失败。属映射表的机械推论，但严格按卡面字面只提了 cancelled，故登记待确认。
2. **【待裁决】（低）** 断言② 中"挂起卡"按 D#3 字面收敛到 FormRenderer 唯一实例；
   两条消息各持 pendingCall 的卡头形态（每消息一张卡头 + 终态 tag）保留未去重 ——
   卡面只说"同 toolCallId 至多一个 FormRenderer 实例"。若业务方要求卡头也单份，
   需另行裁决（涉及 pendingCall 投影跨消息合并，超出 T2a 卡面）。
3. 偏差记录（非待裁决）：冒烟阶段 3 未使用 `resetSession()` 做清理 —— 读码发现
   `resetSession` 只写新 sessionId 进 sessionStorage、不改 store.sessionId（既有行为，
   T2a 范围外，未改动）；冒烟改用新 toolCallId 隔离计数，不影响断言有效性。
4. 显式风险承认（随 D#5 签字）：前端态在阻塞 CI 门禁内零自动回归，nightly/非阻塞 job
   与 vitest 评估挂 T4 —— 本棒按原样执行，未新增 CI 配置。

---

## 6. 仓库状态收尾

- 已停：后端 18318、前端 5202（均为本棒启动、本棒 taskkill；netstat 复查无 LISTENING 残留）。
- 已停：冒烟 Chrome（脚本内 chrome.kill()，独立 user-data-dir 在 `<SCRATCH>/m2t2a-smoke-out`）。
- 未触碰用户自有浏览器进程；Memurai 6379 保持运行（本棒未启停）。
- git 状态：未执行任何 add/commit/stash；改动 = §1 五文件（4 改 1 增）+ 本文档。
