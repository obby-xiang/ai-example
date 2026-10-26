<!-- 历史分析 · 过程证据 · 非现行基线 · 归档批次 ai-remediation-2026-10-25 / final-followup · Q-08 源稿 -->
# Q-08 根因原始分析（源稿）：409 期望只在「卡送达 → park 释放会话门」之间约 5–15 ms 的窗口内成立

> **性质**：**历史分析 / 过程证据 — 非现行基线，非裁决**。正文是 2026-10-26 对 `AIE2E-Q-08` 唯一失败用例做的只读归因当时写下的结论；其中的日志行号、时刻（UTC）、run / artifact ID 都是**当时值**，随 artifact 过期即不可回取。**未改测试、未改产品、未做裁决。**
> **来源**：`<KIMI_SESSIONS>/<WORKSPACE>/session_<KIMI_SESSION_A>/agents/main/tasks/agent-oodtnoya/output.log`（派发层产物 = 该子 agent 最终结论全文）。**本批唯一一处以 `output.log` 为源取件**：该 agent 的 wire 末条文本未再单独比对（其余 4 份审计源稿取自 wire，其中 2 份已实测与 `output.log` 逐字相同）；差异风险已在此登记。
> **抽取口径**：只取该件全文；**不含**工具调用与回显、用户消息、原始运行产物（`boot.log`、artifact 压缩包、job 日志）本身——本件只留**行号与时刻锚点**。正文**未删段、未改写结论**，仅做占位符替换。
> **源体量**：4,232 字符 / 54 行；本件脱敏替换 **4 处**（仓外盘符根 → `<LOCAL_WORK>`）。
> **用途**：它是同目录摘要件 `q08-premise-timing-analysis.md` 的**源稿**。摘要件把「断言行号 / 409 的唯一来源 / 两种处置方向的代价」抽成结论层；**本件保留摘要件覆盖不到的细节**：attempt 2 的逐毫秒事件表（`turn_accepted` → `tool_call_start` → `approval_park` → `turn_parked` → POST#2 → `turn_accepted run2` = 200，该会话 `turn_denied` 计数 0）、同 run 内 Q-05 的 4 ms 对照、**跨 6 次历史 run 的 park↔deny 余量表（0–1 ms，本次因多一次 `GET /api/ai/history` 而变成 12 ms）**、以及「前端不产生该请求，Q-08 是协议层探针」的 UI 侧排除。引用细节以本件为准，引用结论可与摘要件互证。
> **三条不得被本件改变的口径**：
> 1. **这是测试前提 / 时序问题，不是「已修」**：`scripts/verify-e2e.ps1` 与 `AiController.java` 在失败后**均无针对 Q-08 的改动**；`ci.yml` 仍为红，`L-149` 不销记。
> 2. **文档 / 实现语义冲突仍待裁决**：`docs/03-技术方案文档-v2.1.md:313`（挂起中再发一轮返回 409）与 2026-10-25 的 park 裁决（`ConfirmGate.java:653-661`）及现行实现相反。属**意图未定**，不是本次失败的直接原因。
> 3. **不把 Q-08 当产品已确认缺陷**：200 是现行 park 语义下的确定性行为；「挂起是否应继续占会话门」是产品意图问题，两条处置方向（改断言 / 改产品持锁）**都要用户裁决**，本件与摘要件均**不自裁**。
> **残留取证缺口**：attempt 1 同 SHA 上该用例为 PASS，但其 `boot.log` 已被 attempt 2 覆盖，**attempt 1 的确切余量不可测**；因此「窗口存在」由跨 run 余量表与 attempt 2 的逐毫秒链支撑，不是由 attempt 1 反推。
> **不含**：原始 `boot.log` / artifact / job 日志正文、原始 wire、原始对话、用户消息原文。

---

## 结论（一句话）

**不是产品竞态，也不是负载偶发，而是 Q-08 的 409 期望只在"卡已送达 → park 释放会话门"之间约 5–15 ms 的窗口内成立**：attempt 2 里第二个请求到达时首轮**已经 park 完成、会话门已被释放**，因此 200 是当前实现的确定性行为；attempt 1 及历史各 run 只是恰好在这个刀锋窗口内撞上了 409。

## 实际证据（attempt 2，SHA 61de820，全部来自 artifact 12622693425）

`<LOCAL_WORK>/_p2-review/q08-artifacts/e2e-evidence/boot.log:3818-3865`，session `aie2e-q08-89c9f6…`：

| 时刻 (UTC) | 事件 | 证据 |
|---|---|---|
| 02:08:57.501 | turn_accepted **run1=50e7a14c** | boot.log:3820 |
| 02:08:57.531 | tool_call_start `start_publish` | :3825 |
| 02:08:57.534/.535 | 订阅者投递上下文关闭（confirm_request 帧已投递，流派 B 收流） | :3827-3828 |
| 02:08:57.537 | `approval_park` + ConfirmGate「人工等待 park」 | :3829-3830 |
| 02:08:57.538/.539 | `tool_call_park` / **`turn_parked`**（run1 落 SUSPENDED） | :3831,3853 |
| 02:08:57.551 | POST#2 归一化生效，长度 12→12（`（点建议）换成另一个问题`） | :3855 |
| 02:08:57.554 | **turn_accepted run2=a81eef83 = HTTP 200**，该会话**零 `turn_denied`** | :3857；`grep -c turn_denied` = 0 |
| 02:08:57.563→.592 | run2 真跑完：turn_start `会话已存在=true` → first_byte → 历史 4 → `turn_end status=done gateWaitMs=0` | :3859-3865 |
| 02:08:57.598 | CI 判 FAIL「并发点发 HTTP 200，期望 409」 | `<LOCAL_WORK>/_p2-review/final-acceptance/ci-2.failed.log:167` |

排除项：全日志唯一的「会话门被死轮占着，放行新发送」是 **W-23 的 session**（boot.log:6045），与 q08 无关；q08 是正常空门路径。park 后 run1 再无任何日志（测试在第 4003 行就抛了，`Ai-Cancel` 未执行），说明挂起轮没被打扰，只是没被收尾。

**同 run 的 Q-05（结构相同、只是第二发送前没有 history GET）**，boot.log:3783-3815：
`.639 tool_call_start → .642 POST#2 → .646 turn_denied 409 SESSION_BUSY → .646 approval_park → .648 tool_call_park`。它赢在同 4 ms 的窗口内。

## 代码链（为什么 park 之后必然 200）

- 会话门在 HTTP 线程取：`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:412`，409 分支 `:423-445`。
- 门的释放在**执行任务 finally**：`AiController.java:480-484` → `sessionGate.release(sessionId, runId)`；`service.chat()` 一返回就释放。
- park 让 `service.chat()` 立即返回：`ConfirmGate.java:628`（发 confirm_request 帧）→ `:649 completeSubscriptions()` → `:662` park 分支 → `:721 throw RunParkedException` → `run/ResilientChatService.java:813-816` → `parkTurn()`（`:2468-2473`，日志即"线程已交还"）。
- 所以**卡送达后 ~2-5 ms 门就空了**；park 后 chat 路径**不再读快照状态**（唯一例外是 `AiController.java:417-421` 的死轮放行，与 SUSPENDED 无关）。
- 这是**设计口径**，不是漏洞：`test/.../session/SessionGateLeaseTokenTest.java:29-32`「park 时首轮任务结束、释放会话门；决策后 ResumeService 用同一个 runId 重新 acquire」；`run/ResumeService.java:1091-1094, 1116-1121` 同口径。
- 前端也不产生这个请求：`components/AiPanel/AiPanel.vue:1580-1587`（`composerHardDisabled` 含 `ai.suspended`）、`AiPanel.vue:1535`（挂起时不出建议 chip）、`stores/ai.ts:2705-2710`（挂起收流时 `loading=false` 但 `suspended` 保持）。Q-08 是**协议层探针**，不是 UI 可达路径。

## 跨 run 对照：余量一直是 0–1 ms

历史 boot.log 里 Q-08 的 409 全部紧贴 park：`ci-2eb2539` park .182 / deny .182；`ci-40211303032` .404/.404；`main-ci-34fd8f4` .202/.202；`main-e2e` .791/.792；`ci-40214177582` deny .705 / park .706（deny 甚至早 1 ms）。本次是 park .539 → 请求 .551（**晚 12 ms**），差异来自 Q-08 比 Q-05 多一次 `GET /api/ai/history`：`scripts/verify-e2e.ps1:4001`（Q-05 无此行）。

attempt 1 同 SHA Q-08 于 01:58:06.0715Z PASS（`<LOCAL_WORK>/_p2-review/q08-artifacts/att1/.../14_Run E2E...txt`）；其 boot.log 已不可取（artifact 被 attempt2 覆盖），故 attempt1 的确切余量无法量测——这是唯一残留取证缺口，但不影响机制判定。

**152b3a9 与本失败无因果关系**：它没改 `AiController`，ConfirmGate 的 hunk 是续跑派发有界重试（`@@ -219/-1931/-2019…`），park→释放门的顺序由更早的 `bc1437d`（AiController release）/`20c56bb` 引入。

## 判定与最小方向

**判定 = 测试前置/时序问题（断言目标态未被钉住）**，且触发条件是 product 明确定义的 park 语义；Q-08 的 409 只是"仍在流式"这一瞬时态的副产物。**同时存在一处口径冲突需要裁决**：`docs/03-技术方案文档-v2.1.md:313`「挂起中再发一轮返回 409 且原轮不受影响」 vs 2026-10-25 park 裁决（`ConfirmGate.java:653-661`）与实现。这一点属于文档/意图待定，不是本次失败的直接原因。

最小安全方向（都不含弱化断言/加 sleep/重试）：

1. 若以 park 裁决为准（现状实现）：Q-08 应先钉住状态再断言——第二发送前读一次该会话 run1 的快照/`runs/current`，把断言对象改成"挂起轮不受影响（仍 SUSPENDED、pending 条目在、无终帧）+ 新轮不产生幻影"，而不是 409。**注意 Q-05（`:3976-3978`）同样只有 4 ms 余量，需一并处理**，否则同类假红会转移到 Q-05。
2. 若 409 才是产品要求：park 时不得释放会话门（`AiController.java:482` 需按 "parked" 分支保留），改由 ConfirmGate/心跳跨挂起持有并续期，直到决策/取消终态；影响面大（锁 owner 跨挂起、TTL、边界测试），W-30/W-34 因先 cancel 仍兼容。

**若仍需一次判别**（请控制器决定，我不自行执行）：同一 Q-08 构型，第二发送前把 `GET /api/ai/runs/{runId}` 读到的首轮 status 打进失败信息，并连续采样 20 次记录 (park 时刻, 第二请求到达时刻, HTTP)；若"是否早于 park"与结果 0 例外相关，即闭环证明这是窗口竞态而非产品偶发。

证据目录：`<LOCAL_WORK>/_p2-review/q08-artifacts/`（`e2e-evidence.zip`、`e2e-evidence/boot.log`、`att1/`、`job-121361501463.log` 下载失败——`gh run view --job` 在非 git 目录报 base repo 错误，日志改由 artifact 取得）。
