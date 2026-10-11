> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 4④ 收口修报告（K3 裁决 F1~F8 逐条办理后全绿复验），署名信息以文内为准；本件为 DC-19 后产出的仓外过程件，脱敏后入库，源文件未改）

# 收口修报告 · 批4④「对话时序包 · 折叠规则」

> 授权来源 = K3 指挥官派单 · 批4④ 收口修卡（复核 GLM-5.3 = PASS 0B/0M/6m/6O；红队 DS V4 Pro = 带条件 PASS 0B/1M-latent/5m/5O；K3 裁决 F1~F8，本棒只执行不重新裁决）。
> 主仓库 = `<MAIN_REPO>`（唯一），基线 HEAD `296ffe2`，锚定 patch sha256 `b3526474…7467`（收口修前实测一致）。
> 收口修证据目录 = `<SCRATCH>\chat-timeline-pkg\evidence-p17\fix\`。
> **零 git 写**：本棒未执行任何 git 写子命令；工作树保持「4 M + 2 ??」6 项在制品（批4④ 产物 + 本收口修增量），未动 `parts.ts` / `frontend-tools.ts` / `types/sse.ts` / `workspace.ts` / 后端（`git diff --name-only` 逐字核验零命中）。

---

## 0. 收口修棒执行模型自证（DC-19，wire 实证，禁照抄卡头）

```
收口修棒执行模型自证：
 - 执行者 = main（CU 会话 id = session_fdfa69e3-c723-453f-9da3-02d593cf6548）
 - wire 路径 = <KIMI_HOME>\sessions\wd_temp_38dcbf7ced2f\session_fdfa69e3-c723-453f-9da3-02d593cf6548\agents\main\wire.jsonl
 - 统计命令 = grep -o '"modelAlias":"[^"]*"' wire.jsonl | sort | uniq -c
 - 实测命中 = "modelAlias":"kimi-code/kimi-for-coding" × 95 次；"model":"kimi-code/kimi-for-coding" × 94 次
 - 结论 = 实际运行模型 = kimi-code/kimi-for-coding（wire 实证，非配置推定）
 - 机械活下沉申报 = 本棒未做下沉：wire 内 "type":"subagent.spawned" 计数 = 0；F1~F8 为强顺序耦合窄面改动 + 双向留证，
   内联执行（对照施工卡 §11.3-5 申报口径）；无子智能体自证事项。关键锚点与关键结论均由本棒亲读。
```

---

## 1. F1~F8 逐项处置记录

### F1（红队 M-1，必做）· reasoning 块 id 跨消息碰撞修复 —— **已修**

- **`frontend/src/utils/blocks.ts`**：
  - `stablePartId(part, messageId)`（现 :31-35）：非 tool 支 base 不变，返回统一 `${messageId}:${base}`（toolCallId / `kind-seg` 同加前缀，全量命名空间，text 块同径）。
  - `groupParts(parts: MessagePart[], messageId: string)`（现 :87）：签名扩为二参，内部 3 处 `stablePartId` 调用点全同步。
  - `Block.id` 注释（:19-20）与文件头注（:8-9）同步改口径。
- **`frontend/src/components/AiPanel/AiPanel.vue`**：`blocksOf`（:583-585）改传 `groupParts(message.parts, message.id)`；模板块分派头注（:162-165）补「加消息命名空间前缀，收口修 F1/红队 M-1」。
- **`frontend/src/stores/ai.ts`**：`setBlockExpanded` doc（:1883-1885 区）blockId 口径改命名空间形态。
- **调用点/类型全同步（F1 授权连带）**：`tests/blocks.spec.ts` 全部 17 处 `groupParts` 调用补第二参（TS-1①~⑩、D-C9 配对、组头用例；TS-1⑥ 断言改 `'msg-1:a'` 形态钉稳定性语义、D-C9 配对断言改 `['msg-1:p1','msg-1:p2']`）；`tests/ai-panel-parts-render.spec.ts` 5 例 13 处 testid 断言改 `a-1:<首成员id>` 形态（夹具消息 id 恒 `'a-1'`）；仓外 `batch44-fold-smoke.mjs` 4 处选择器改前后缀匹配（见门禁3）。
- **RED→GREEN 双向留证**：`fix/RED-f2-crossmsg.log`（F2① 红因 = `expected 'reasoning-0' not to be 'reasoning-0'`，夹具就位、断言级红）→ `fix/GREEN-f2-crossmsg.log`（17/17）。

### F2（红队 m-5，必做）· blocks.spec.ts 补测试 —— **已修（①②③全落）**

- **① 跨消息 reasoning 碰撞用例**（新 describe，blocks.spec.ts:169-180）：两消息各持 `reasoning@seg:0` ⇒ ①`block.id` 必不相等；②模拟 `expandedBlocks` 键查找——消息 A 展开后消息 B 同 id 组 `resolveBlockExpanded` 必须回 `false`（串味断言）。**先 RED 后 GREEN**（证据同 F1 两日志）。
- **② requiresAction 翻转键换代用例**（blocks.spec.ts:182-200）：`[call_X(终态), call_A(requiresAction), call_Y(终态)]` ⇒ 3 bare 组（id=`msg-1:call_X/call_A/call_Y`）；`call_A` 决议翻转后 ⇒ 重并为 1 组（id=`msg-1:call_X`、3 成员、defaultExpanded=false）；**钉死语义**：决议前用户对 `call_A` 组的展开记录对重并新组不生效 ⇒ 回默认展开态（收起）。同随 RED→GREEN 两日志双向留证（RED 时 id 断言 `'msg-1:call_X' vs 'call_X'` 红）。
- **③ 行为变异 RED 留证**（循 ③b D14 范式）：变异体 = `stablePartId` 的 reasoning 支绕开 messageId 命名空间（模拟 F1 回退），`fix/RED-f2-mutant.log` = **恰 F2① 1 例红、其余 16 例绿**（证明用例非恒绿伪装，能抓真回归）；变异 diff = `fix/RED-f2-mutant.diff`；恢复后 `fix/GREEN-f2-revert.diff` + 复跑 17/17 绿 + `grep -c MUTANT = 0` 三重核验恢复干净。

### F3（红队 m-1）· 删除 stores/ai.ts 死代码 `toggleBlock` —— **已删**

- **先 grep 确认零消费**：`grep -rn "toggleBlock" frontend/src frontend/tests scripts` 全命中 = 定义点（ai.ts:1880-1882 区）+ AiPanel **组件本地同名函数**（:602-604，走 `setBlockExpanded`，与本 action 无关）；`ai.toggleBlock` / `.toggleBlock(` 消费点 **0 命中**。
- **改动**：删除 store action `toggleBlock`（含其 doc 注释，原 :1876-1882，7 行）；保留 `setBlockExpanded`（现 :1884-1886）。删后全仓 grep 复验零悬空引用（`fix/` 内留 grep 回显）。

### F4（红队 m-2）· A4' 写点补 `deriveProjections(host)` —— **已修**

- **`frontend/src/stores/ai.ts:1386-1388`**：`upsertToolPart(host, {…}, false)` 之后补 `deriveProjections(host)`（注释注明防 `toolRuns[].reason`（parts.ts:199）滞留、守「parts 唯一真值、投影由 deriveProjections 派生」契约）。写点区段现为 `:1358-1391`。

### F5（红队 m-3）· A4' reason 宿主改末条 —— **已修**

- **`frontend/src/stores/ai.ts:1365-1375`**：`messages.find`（首条）改为**倒序扫末条**持该 toolCallId 的消息（`for (let i = this.messages.length - 1; i >= 0; i--)`，ES2022 无 `findLast`，用倒序循环），与 `formHostMessageId`（AiPanel.vue 倒序扫末条）同口径；注释写明「**reason 单落点 = 末条宿主**」及误取首条的双卡/重投错位风险。

### F6（红队 m-4，登记不修）· 台账登记 —— **已登记**

- 登记口径（供提交官入 M2 观察项，**本棒不修代码**）：「**requiresAction 翻转 → 组重并 + expandedBlocks 键集合换代，用户展开态回默认**」为可接受 UX 代价——属 D-C9 隔离规则（HITL 段独立成组）的必然连带：决议后独立卡重归相邻活动组、继承组首 id，旧展开键成孤儿、新键未触碰 ⇒ 回落 defaultExpanded（全终态组 = 折叠）。已由 F2② 用例钉死现语义（回默认展开态 = 可预期行为而非随机抖动）；代价面 = 已决卡可能并入默认折叠组而"消失"（需点开组头）。详见 §5 台账承接。

### F7（复核 m-1~m-6 + 差异表第 1 行）· 施工报告文书订正 7 处 —— **已订正**

落 `<SCRATCH>\chat-timeline-pkg\施工报告-批4④.md`（订正值 = 复核对**锚定树**的实测值）：

| # | 位置 | 原文 → 订正 |
|---|---|---|
| ① m-1 | §1 锚点表 | `blocks.ts`「新建 141 行」→ **131 行** |
| ② m-2 | §1 锚点表 | bare 支「`:243` 起」→ **`:261`**；`textOf` `:586` → **`:587`** |
| ③ m-3 | §1 锚点表 + §5 | A4' 写点 `:1357-1379` → **`:1358-1380`**；`form.error` 保留行 `:1358` → **`:1359`** |
| ④ m-4 | §1 锚点表 + §10 | 面板 spec「21 例」→ **19 例**；静态 `it(`「142」→ **140**（+15 blocks +12 面板净增；运行时 152 正确不动） |
| ⑤ m-5 | 文首 + §7-6 | 「工作树保留 5 文件」→ **6 文件**（两处） |
| ⑥ m-6 | §7-5 | jar 定性重写：`c3f2579` **改了主码** `RedisChatMemoryRepository.java`（saveAll 空列表分支改 Lua 委托）且 **jar 已含该改动**（字节码含 `deleteByConversationId` + `DEL+SREM` Lua 特征串；mtime 20:20 与内容不符，构建时点晚于所示）——保守侧错误，E2E 有效结论不变；不补 mvn |
| ⑦ 差异表#1 | §2 TS-9 行 + §11 V8 行 + §3 截图条 | 截图计数 5 → **6 张**（4-单工具轮为独立文件，「并入 1c」表述删除） |

### F8（红队 O-2）· 滚动指纹区注释订正 —— **已订正（注释级，零逻辑改动）**

- **`frontend/src/components/AiPanel/AiPanel.vue:806-812`**：原注释「无需读旧投影（content/toolRuns）即可触发跟随」与实现残留不符（指纹末项仍读 `last.pendingCall.status` 投影）——按实现如实对齐：末项读点为 D2 明确保留面，挂起段 requiresAction 翻转/收敛只改派生投影、未必改末段自身 status，故保留投影读点兜底触发跟随。逻辑一行未动。

---

## 2. 收口修后重锚（施工卡 §1.5-② 强制；行号 = 收口修后工作树实读）

| 锚点 | 锚定值（复核口径） | 收口修后实值 | 说明 |
|---|---|---|---|
| `utils/blocks.ts` 规模 | 131 行 | **134 行**（+3：stablePartId 扩参/注释） | F1 |
| `blocks.ts` `stablePartId` / `groupParts` | :30 / :84 | **:31 / :87** | F1 |
| `AiPanel.vue` `blocksOf` | :582 | **:583-585**（传 `m.id`） | F1 |
| `AiPanel.vue` 滚动指纹注释 | :806-808 旧表述 | **:806-812** 订正表述 | F8 |
| `stores/ai.ts` A4' 写点区 | :1358-1380 | **:1358-1391**（F5 倒序扫 +7 行、F4 +3 行；`form.error` 仍 :1359） | F4/F5 |
| `stores/ai.ts` `toggleBlock` action | :1880-1882 | **已删除**（`setBlockExpanded` 现 :1884-1886） | F3 |
| `tests/blocks.spec.ts` | 177 行 / 15 例 | **213 行 / 17 例**（+F2①② 两例） | F2 |
| `tests/ai-panel-parts-render.spec.ts` | 19 例 | 19 例（testid 断言改命名空间形态，例数不变） | F1 连带 |

---

## 3. 门禁实测数字（逐项真跑，日志落 `evidence-p17/fix/`）

| # | 门禁 | 命令（实跑） | 实测 | 证据 |
|---|---|---|---|---|
| 1a | typecheck | `npx vue-tsc --noEmit` | **exit 0** | `fix/typecheck.log` |
| 1b | yarn test | `yarn test`（vitest run） | **12 files / 154 passed (154)**（>152 ✓；= 152 基线 +2 新用例） | `fix/vitest.log` |
| 1c | yarn build | `yarn build` | **✓ built in 25.80s / exit 0** | `fix/build.log` |
| 2 | E2E 桩 | `E2E_STUB_MODE=1 powershell -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 -Base http://127.0.0.1:18330 -BackendLog <fix>/backend-e2e.log`（桩 :18399 + 后端 :18330（`--app.job.batch-size=10`）+ dev :5202） | **PASS=28 FAIL=0 GFSKIP=0，Q8 项 2/0，e2e_exit=0** | `fix/E2E-stub-fix.log`（末段）+ `fix/backend-e2e.log` |
| 3 | CDP 仓外冒烟 | 复跑前**已重启 dev server**（全新 PID，留证 `fix/devserver-fix.log`）；`SMOKE_OUT=<fix>/cdp node batch44-fold-smoke.mjs` | **10/10 PASS**，**6 张截图**落 `fix/cdp/20261011T212456Z-*.png`（1a/1b/1c/2/3/4） | `fix/cdp-smoke-fix.log` + `fix/cdp/result-20261011T212456Z.json` |
| 4 | m2t2a | `SMOKE_SKIP_MODEL=1 node scripts/m2t2a-smoke.mjs`（dev :5202 在跑） | **exit 0**；②③③b 全 PASS，数值与施工留证逐字一致（`cancelledSegments:2, hangingSegments:0, cancelledStatusTags:3`）；断言① skipped（设计口径） | `fix/GREEN-m2t2a-fix.log` |

**红线终态核验**：`git diff --name-only HEAD` 仅 4 M（AiPanel.vue / ai.ts / ai-panel-parts-render.spec.ts / m2t2a-smoke.mjs）+ 2 ??（blocks.ts / blocks.spec.ts）；`parts.ts` / `frontend-tools.ts` / `types/sse.ts` / `workspace.ts` / 后端 **零 diff**（命令回显留证）。环境清退：自起进程（桩/后端/dev 及其子进程）全部按 PID 终止、`netstat` 复验 5202/18330/18399 无 LISTENING；仓根 E2E 副作用 `data/` 已删除，`git status` 恢复恰 6 项在制品。

---

## 4. 偏差申报（逐条）

1. **E2E 三轮回合（环境性，非产品回归）**：R1 失败 = ①powershell 执行策略拦截（补 `-ExecutionPolicy Bypass`）②`-BackendLog` 约定文件不存在（6 个日志依赖用例 FAIL）——重启后端并把 stdout 重定向到该路径后 R2 转 **PASS=27 FAIL=1**；R2 唯一失败 = TC11 要求后端带 `--app.job.batch-size=10` 启动（脚本断言文案自带该口径），补参后 R3 = **PASS=28 FAIL=0 GFSKIP=0**。三轮全程 `GFSKIP=0`。该偏差与施工棒 R-9 首跑失败同类（环境未备），如实申报。
2. **CDP 仓外脚本适配（F1 连带，非放宽）**：`batch44-fold-smoke.mjs` 4 处 `block-toggle/block-body` 选择器由全串等值改为 `[data-testid^="block-…"][data-testid$=":<toolCallId>"]` 前后缀匹配（messageId 运行时生成无法写死），脚本头注记适配原因与日期；`pending-card-*` 选择器不受影响。断言强度不降级（仍锚定具体 toolCallId 后缀）。仓外件，不入库。
3. **面板 spec 断言同步（F1 授权连带）**：5 例 13 处 testid 改 `a-1:<首成员id>` 形态——夹具消息 id 恒 `'a-1'`，断言语义不变；blocks.spec 旧例补第二参、TS-1⑥/D-C9 的 id 断言改命名空间形态（稳定性/配对语义原样钉死）。
4. **F7 订正值口径**：订正值 = 复核对**锚定树（b3526474）**的实测值；收口修后行号再次漂移（§2 重锚表）。即施工报告现与「锚定树 + 订正」一致，与「收口修后树」的行号差异以 §2 表为准——照 K3 裁决执行，未自行改订正值。
5. **m2t2a 结果 JSON 落点**：脚本默认输出 `<SCRATCH>/m2t2a-smoke-out\`（仓外、非主仓库工作树、不入库），与施工/复核轮行为一致，如实申报。
6. **未做/未复验**：未跑 mvn（本批零后端改动，F7⑥ 按复核口径文书订正、不补构建）；CDP 形态2/3 仍为页内替身 outcome（与施工同配方，未构造网络级故障）；E2E 未带 `-EnableGf6`（沿用施工/复核口径）。

---

## 5. 台账承接建议（提交官办理）

### 5.1 F6 登记（入 M2 观察项）

> **requiresAction 翻转 → 组重并 + expandedBlocks 键集合换代，用户展开态回默认** = 可接受 UX 代价（红队 m-4 / K3 F6 裁决登记）。
> 机理：D-C9 隔离规则使 HITL 段独立成组；段决议（requiresAction→false）后重归相邻活动组，组 id 变为组首成员 id（键集合整体换代），旧展开/收起键成孤儿，新键未触碰 ⇒ 回落 defaultExpanded（全终态组默认折叠）。已决卡可能并入默认折叠组而需点开组头查看。
> 钉死证据：blocks.spec.ts F2② 用例（RED-f2-crossmsg.log / GREEN-f2-crossmsg.log 双向）。
> 后续可选优化（另批授权）：决议后保留该段独立成组直至轮终，或键换代时迁移同 toolCallId 的显式态。

### 5.2 两报告观察项挂账清单

| # | 出处 | 观察项 | 承接建议 |
|---|---|---|---|
| 1 | 复核 O-1 | `defaultExpandedOf` 第②档（reason 非空）仅扫 tools 成员——reasoning 段无 reason 字段，语义等价 | 挂账备查，无需动作 |
| 2 | 复核 O-3 | 空 text 段不打断组（与渲染真值 `v-if="part.text"` 一致） | 已由施工 R-3 申报，挂账备查 |
| 3 | 复核 O-4 | CDP 形态2/3 回灌失败为页内替身，未构造网络级故障 | 后续可选加固项（网络级替身），非本批评判 |
| 4 | 复核 O-5 | result JSON 部分 DOM 探针字段（cardCount/memberCount=0）为选择器口径问题，实质判据（bodyHidden）不受影响 | 后续修正探针字段口径防误读 |
| 5 | 复核 O-6 | 施工报告 §0 wire 自证计数随追加单调增长（×107→×118），不可回证但方向自洽 | 挂账备查 |
| 6 | 红队 O-1 | `groupParts` 的 `!isActivityPart → continue` 为封闭联合下不可达防御分支 | 保留无害，挂账备查 |
| 7 | 红队 O-3 | `cancelledStatusTags` 全文档取法命中任务中心 JobProgress 常量「已取消」（基线 1） | 已由施工 R-5 申报；与段态判据互补后底线达成，挂账备查 |
| 8 | 红队 O-4 | 施工报告 §1「blocks.ts 141 行」笔误 | **已由 F7① 订正销记（131 行）** |
| 9 | 红队 O-5 | `expandedBlocks` 无消息删除/会话切换（非 resetSession）回收点，键滞留 | 内存态卫生挂账（D4 内存 store 立场所致，量级无害） |

---

*报告完 · 批4④ 收口修（执行模型 wire 实证见 §0，非配置推定）*
