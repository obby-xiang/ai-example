<!-- 第 19 期补档入库 · 手动刷新批（MANUALREFRESH） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\收口修报告-手动刷新批.md`（1918 B，sha256 `2697c8a0dbdc658e83c7acd5c4bb4ed7c493f7c65252242e952a53deffa226bb`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `a2d9d708c8101fde11e4e2a434cd753c85ee193665c8b86322de4b451bb28fa7`（复核 / 红队据其出报告）；本期**入库锚** = `336f03f41769f3f7605aac405cb222b290374e10429e6cbdb38a7e0c4fdec4a3`（收口修后重锚，合流提交字节据其）。两 SHA **不可混用**。

# 收口修报告 · 手动刷新批（C1~C4，禁扩面）

## 署名

- 实际运行模型：`kimi-code/kimi-for-coding`（profile=agent，thinkingEffort=high）。
- CU 会话 id：`session_856bb412-5a36-4c00-b41b-610cd406fe01`。
- wire 实证：本会话 `agents/main/wire.jsonl` 累计 154 条 `"modelAlias":"kimi-code/kimi-for-coding"`（12:51 复核，首条 profile.bind，agentId=main）。
- 收口修卡 sha256：`5824b7e5…153c9`，与卡面一致。

## C1（MAJOR-1，必修）：AbortSignal 死代码 → dispose 唯一取消链路

按卡「以既有实现形态为准（watchJob 返回 dispose）、不得两套并存」：摘除 `watchPublishOutcome` 的 `AbortSignal` 参数与 abort 监听/提示分支（S4 自加、超出 M4 原口径、生产不可达）；取消链路唯一化为 dispose——workspace run_job 持有 dispose、新一轮 publish 发起时收旧跟踪（轮次收尾，静默）。spec `abort-upfront-no-unhandled` 改为 `dispose-cleanup-no-unhandled`，直锁生产可达路径。**18 spec / 202 基数不动**。

## 注释级改动（逐项）

- **C2** `frontend/src/utils/job-watch.ts` REALTIME_IDLE_MS 注释：「溢出回绕 ~1ms」修正为「现代规范（HTML Timers）为钳制非回绕」；功能不动。
- **C3** `scripts/verify-e2e.ps1:857`：帧类型注释删 TASK_CHANGED，改为「JOB_PROGRESS / JOB_DONE / HEARTBEAT（TASK_CHANGED 发布链已随 S5 摘除）」。
- **C4** `docs/technical-design.md` :185/:278（重锚）：事件流帧类型改为 JOB_PROGRESS/JOB_DONE/HEARTBEAT 并注明 S5 摘除面与唯一消费方（job-watch）；状态同步条目改为「落库 + 结构化操作流水，事件链已摘除」。

## 验收（复跑实测）

- vitest：**18 文件 / 202 测试全绿**；typecheck：绿。基数与施工批一致。
- 未 commit（HEAD 仍 45b2c6a）。已在施工报告偏差节补登 C1 原替换未披露声明。
