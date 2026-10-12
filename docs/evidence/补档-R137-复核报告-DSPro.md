<!-- 第 21 期补档入库 · R-137（后端 AI 工具终态守卫批） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\复核报告-DSPro-R137.md`（5721 B，sha256 `c6679bf6eb51b24ff345d3025642e61ab99cfe1519e0da1898b343953d106b3f`）；除本说明块外，正文与源件逐字一致（零改写）；本件源件**零盘符路径 / 零用户名 / 零内网 IP / 零组织名字面 / 零密钥**，无替换项（脱敏终扫硬命中 0）。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本件为 **R-137** 复核报告-DSPro 补档（第 21 期入库）；基线 HEAD = `4260651`。本期**审查锚** = `dd74d6166007881f102fbfa4a62d6d6af9cf3bb8768c193a8489718d808b3195`（51982 B / 11 段，复核与红队据其出报告）；本期**入库锚** = `ce1b133120bb10cf524eadbd9e8d52ccdba60c5745fe4e3727e700871d5d1832`（53274 B / 11 段，收口修 C-3/R-2 + R-1 后重锚）。两 SHA **不可混用**。
# 复核报告 · R-137 后端 AI 工具终态守卫批（DSPro 复核）

- 复核卡 sha256 `6a62cfd8…876b1` ✓；审查锚 `dd74d616…b3195` ✓。**零夹带核验 PASS**：按口径（4 个未跟踪逐个 `git add -N` → `git diff HEAD -- .` → `git reset -q`）重生成 diff = 51982B，与锚 sha256 逐字节 EQUAL。
- 署名（实测）：实际运行模型 **DeepSeek/deepseek-v4-pro（经 Kimi Code 主代理）** · 会话 id **session_45401319-a996-41b5-bac5-c2efa213e438** · wire modelAlias = 本会话 `agents/main/wire.jsonl` 首条 `profile.bind{modelAlias:"DeepSeek/deepseek-v4-pro"}`（grep 去重，非照抄卡头）。
- 只复核不改文件；HEAD=4260651 未动，工作树 11 路径（7M+4??）原态。机械复跑下沉 agent-638（显式钉 DeepSeek/deepseek-flash）。

## 独立复跑（实测数）

| 项 | 实测 | 与施工报告 |
|---|---|---|
| 后端 mvn test | **BUILD SUCCESS，466 用例 0F 0E 0S，73 XML** | 一致 |
| 前端 vitest | **216 用例全绿，19 spec 文件（18 tracked + 1 未跟踪新 spec）** | 用例一致；spec 数 18→19 见 F-3 |
| typecheck | exit 0 零报错 | 一致 |
| build | exit 0（31.3s） | 一致 |
| check-docs | 6/6 exit 0 | 一致 |
| E2E 桩语法 | pwsh 不可用；PS 5.1 同款 `Parser::ParseFile` → PARSE_OK | 约束如实记（不算打回） |

## T1~T8 对照（全部命中）

- **T1** ✓ `JobService.createAndStart` 首道 `assertTaskNotFinalized`，落库前抛。
- **T2** ✓ CANCELLED 恒拒 / COMPLETED 核最终作业（IMPORT→PUBLISH、EXPORT→EXPORT 成功）/ FAILED 放行。回归锁①②③（FAILED 放行、预检查后 precheck 重跑、COMPLETED 无 PUBLISH 放行）三正向 + ACTIVE 回归均在 TaskFinalizedGuardTest 落位。
- **T3** ✓ `ConflictException("TASK_FINALIZED")` → REST 409；AI 通道经 executeOnce 呈 ok=false（与 T6 自洽）。
- **T4** ✓ goToStep 双守卫：终态（同口径）+ step 非空/格式 `^[A-Z][A-Z0-9_]*$`/集中白名单（DONE 通用），未降级（O-d S2-1）。
- **T5** ✓ workspace.ts goto_step 分支前插 `isCurrentTaskReadOnly()` 守卫（与 F6 同构）。
- **T6** ✓ catch 9 处分级：5 重抛（createTask Exception→IllegalStateException + 4 件 start_* 删 try/catch 直透传）+ 4 保留（:126/:181/:196/:253 读路径与 IAE），与定稿一致。
- **T7** ✓ N-6 收窄：m2t2a-smoke.mjs 收编 `FRAME_TIMEOUT_SECONDS`（env 可覆盖）+ `CONVERGE_POLLS/INTERVAL`；生产面确认无硬编码（ConfirmGate 经配置单点）；ConfirmGate:491 ok 未碰（R-147 另批）。
- **T8** ✓ 后端 24 例（13 红→绿）+ 前端 4 例（2 红→绿）TDD 声明；四回归锁全覆盖。

## C-1~C-4 裁决

- **C-1（MAJOR-3 锁跨棒基线源文本）**：**接受**。SpreadGrid.vue 不在 diff 属预期（F2 前棒已修）；源断言 `watch(readOnly)→applyReadOnly→isProtected` 是静态兜底，非 CDP 运行期真锁，施工报告已如实申报"未跑真 E2E"。运行期 MAJOR-3 仍待 E2E 真跑面补，登记即可。
- **C-2（MAJOR-2 ③ FAILED 放行仅清场未断言）**：**接受**。FAILED 放行已由后端单测 `failedTaskStillAcceptsNewJob` 锁定；E2E 桩因 FAILED 需作业失败联动难构造而跳过并清场，注释自证，无欺骗面。遗留死片段（建 $m2fail 仅清场）属 O 级。
- **C-3（新增块 Ok/No 未注明「不计入主用例计数」）**：**须修（minor）**。R-137 MAJOR-2/3 块用 Ok/No 但未像 Q8 块注明「不计入主用例计数，单独计数」，会并入主 PASS 计数、基数口径漂（29→31 与"主/单"分账混）。修法 = 补一行口径注释或纳入单独计数。
- **C-4（FRAME_TIMEOUT_SECONDS 脚本侧双写）**：**接受（附注）**。脚本默认 120 与生产 `app.ai.hitl.frontend-tool-timeout` 同值但不同源，改配置须同改脚本；env `SMOKE_FRAME_TIMEOUT_SECONDS` 已给逃生口，O-d 已登记。可接受，漂移风险留观察。

## O-a~O-d 登记裁决（全部成立，无漏登）

- **O-a TOCTOU** ✓ 成立（与 assertNoJobInFlight 同基线，登记不改）。
- **O-b FAILED 放行下 start_publish 部分暂存** ✓ 成立（既有面，assertPrecheckPassed 兜底）。
- **O-c 行号漂移族** ✓ 成立（开工已全量重取，含 :255 createTask Exception 第 9 处）。
- **O-d S4-1 收缩 / S2-1 未降级** ✓ 成立（N-6 收缩为脚本侧收编已落地；白名单单处新建未降级）。

## 附加发现（O 级，非阻断）

- **F-1 前后端"最终作业"语义微差**：后端 `findFirst…StatusIn(COMPLETED)` = **存在** COMPLETED 最终作业即终态；前端 `isCurrentTaskReadOnly` 取 `filter(finalJobType)[0]` = **最新**最终作业须 COMPLETED。在「先成功后又失败」的陈旧数据下二者分叉（后端拒/前端放）。T2 措辞「存在成功作业」与前端"最新"未完全同构，建议登记观察项。
- **F-2 终态守卫逻辑双份实现**：JobService.assertTaskNotFinalized(taskId) 与 TaskService.assertTaskNotFinalized(Task) 同逻辑复制，`Set.of(COMPLETED)` 亦两处（JobService 常量 + TaskService 内联），漂移风险，建议抽公共判据。
- **F-3 spec 计数**：施工报告写「18 spec / 216」，实测 19 spec 文件（新 goto-step-readonly-guard.spec.ts 未计入 spec 数，用例数 216 正确）。纯计数口径，不改判。

## 结论

**带条件 PASS。** 零夹带通过、独立复跑全绿（466 后端 0F0E0S / 216 前端 / typecheck / build / check-docs 6/6）、T1~T8 全命中、O-a~O-d 登记成立。唯一须修项 **C-3**（E2E 新块补「不计入主用例计数」口径注释，minor）；F-1~F-3 为 O 级登记项。均不构成打回。
