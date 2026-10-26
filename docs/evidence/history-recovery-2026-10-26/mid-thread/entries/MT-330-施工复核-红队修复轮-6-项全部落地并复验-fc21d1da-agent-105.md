<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-330 · 施工复核 · 红队修复轮：6 项全部落地并复验（fc21d1da / agent-105）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`scan-lib`。
> **稿态**：历史中间稿 —— 该 wire 第 `m286` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt26` 条有正文的 assistant 消息（共 32 条有正文），末条有正文消息为 `m406`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `fc21d1da` / agent `agent-105`（`main` = 主会话）；原字符 3,082（链内 1 段、合计 3,082）。
> **链号 / 类别**：`MT-330` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 20 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `3%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `3%`。
> **与末条归档（138 件）的关系**：末条 `m406`（2,465 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

## 红队修复轮：6 项全部落地并复验

### 6 项修复逐项 diff 摘要（文件:位置 / 行数）

| 项 | 位置 | 改动 | 行数 |
|---|---|---|---|
| **P1** | `stub-upstream.mjs:294-297` | 清零条件 `messages.length <= bucket.lastLen` → **`< bucket.lastLen`**（等长=后端原样重发的重试形态不得清零 `calls`/`used`；注释 1→3 行） | 1 行改写（+2 净） |
| **P1** | `stub-upstream.mjs:662-684` | 自检新增 **④b 等长重发回归用例**（同 fingerprint、长度不变连发 8 次：首轮 `tool_call`、其余文本、总数 ≤ `maxCalls`）；递增形态 ④ 保留并标注形态 | 新增 23 行 |
| **P2** | `stub-upstream.mjs:461-475` | `crashReport()`：`fs.writeSync(2,…)` **同步**写 stderr + 同步追写 `<dir>/stub-crash.log` → `process.exit(1)` | 新增 15 行 |
| **P2** | `stub-upstream.mjs:480-481 / 485-486 / 497-514 / 516 / 442-451` | `uncaughtException`+`unhandledRejection`；`req`/`res` error 只记日志；请求处理 try/catch（回 500 且进程存活）；`server.on('error')`；响应写出兜底 | 约 +27 行 |
| **P2** | `ci.yml` 桩启动行 | `nohup node … > >(tee -a …/stub.log) 2>&1 &`（**进程替换**而非管道，`$!` 仍是 node 的 PID） | +1 改写 +3 注释 |
| **P4①** | `stub-upstream.mjs:61-85` | 8 组常量 + 5 项上限的"手工副本"义务说明（事实源 `GenerativeFormRules.java:67-115`、⚠ 收紧时须同步对账）+ 5 个上限常量 | 新增 25 行 |
| **P4②** | `stub-upstream.mjs:580-585 / 598-601 / 602-621` | 自检③补齐 5 项上限：title≤100、form 序列化≤8000、placeholder≤100、options≤50、option value≤100 且去重（label≤100）；**先按产品源逐项核对再写** | 约 +30 行 |
| **P4③** | 证据文档 §3.1 | 加"**经桩内副本**"限定（注明对账对象非产品事实源） | +2 行 |
| **P5** | `verify-e2e.ps1:2182-2190` | GFSKIP 摘要按 `E2E_STUB_MODE` 分支措辞（**纯文案**，判定逻辑未动） | +7/−1 |
| **P6** | `ci.yml` E2E step / job | 保留 `shell: pwsh`，去 `pwsh -NoProfile -File` 前缀改直调 `./scripts/verify-e2e.ps1`；增 `actions/setup-node@v5`（node 22） | +2/−1；+7 |
| **P7** | `ci.yml` 就绪门 | `grep '"redis":{"available":true'` → `jq -e '.available == true and .redis.available == true'` | +2/−2 |

文件累计：`stub-upstream.mjs` 626 → **745 行**；`verify-e2e.ps1` **+16/−8**（2194 行）；`ci.yml` **+146/−1**（212 行，11 个 step）。

### 7 项复验结果（全部【实测】）

1. **桩自检 9 项全过、退出码 0**（新增"等长重发自证"；白名单对账含 5 项上限）。
2. **P1 反向验证有牙**：把条件改回 `<=` 的仓库外临时副本 → `SELF-CHECK FAIL：48 项`、退出码 1（各路由 8/8 全 `tool_call`，与红队复现一致）。
3. **P1 专项探针**：等长形态 8 连发 = `tool_call, text×7`（修复前 8/8 `tool_call`）；递增对照仅首轮 `tool_call`。
4. **P2(a) EADDRINUSE（真实交付物）**：退出码 1，`[FATAL] server.error … listen EADDRINUSE` 栈同刻同步落盘（stderr + `stub-crash.log`）。
5. **P2(b) 人为 `uncaughtException`（仓库外注入副本）**：退出码 1，栈同步落盘、端口随即释放（确非静默消失）。
6. **P2(c) 启动行 PID 语义**：`ps` 核对 `$!` 指向 **node**（tee 是另一 PID），`kill "$!"` 成功停桩 ⇒ PID 取法正确。
7. **干净跑**：`PASS=27 FAIL=0 GFSKIP=0`、`Q8 2/2`、**退出码 0**，46s；**违规跑**（violate-form-type + 桩模式）：**退出码 1**、4 个 SKIP、10 份 `gfc-*` 齐全（T12-E#4/#7 未受影响）；**不设 `E2E_STUB_MODE` 的对照**：退出码 0 且打印原文案 ⇒ P5 分支与 T12-E#1 语义均未变。
- **正常路径零影响**：干净轮 27 条 `[PASS]` 行与修复前**逐字一致**；十份 `gfc-*` **归一化内容逐项一致**。
- 另：PowerShell 语法解析 **0 错误**、YAML 解析 OK、`scan-lib.sh` 扫 5 个文件（含文档）**零命中**；端口 18330/18397/18399 用后全释放，本棒进程全停。

### 证据文档更新

`docs/evidence/M2-T1.2-E2E门禁实施-DS-V4-Flash.md`（529 行）：新增 **§3.7 红队修复轮**（6 项 diff 摘要 + 14 条复验记录 + 修复后计数口径）；**§8.1 红队 7 条处置表**（P1/P2 采纳本批修复、P3 复现轮已闭合、P4 部分采纳+长期并 T4、P5/P6/P7 采纳，含 P2 的边界说明）；**§9 末尾追加**裁决原文 + 执行回执；§0/§1/§3.1/附录计数与表述同步更新。

**【待裁决】：无。** 一处边界如实标注：P2 的 handler 注册在 `startServer()` 内（按裁决原文），故"启动前的配置类失败"（如路由表缺失）仍走 Node 默认栈+退出码 1（同样非静默），未越范围去改加载层。
