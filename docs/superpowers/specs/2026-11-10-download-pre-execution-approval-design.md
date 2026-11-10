# 下载执行前确认与 UI 撤销清理设计

## 目标

- `download_export_files` 在任何切页、预检、文件生成或浏览器下载前，必须经过独立 HITL 拒绝/批准确认。
- 普通下载工具卡始终只读，只展示参数、执行状态和结果，不承载用户决策。
- 实现必须面向所有 `DANGER + FRONTEND` 工具，禁止按工具名、参数、任务或场景拟合。
- 普通 UI 操作撤销入口直接清退；表格业务撤销 `undo_last_sheet_edit` 及其预览确认保持独立。

## 方案

采用已确认的通用两阶段模型：独立审批记录 A 与原前端执行记录 E 双向链接、分别持久化、分别出卡。

- A 为 `CONFIRM`，使用独立 approvalId，不进入模型工具调用历史。
- E 保留原 `toolCallId` 与 `FRONTEND`，模型只看到一次调用和一次结果。
- 触发条件仅为 `risk=DANGER && channel=FRONTEND`，不判断工具名。
- 待审批时不发送 E 的前端执行请求，不产生任何业务副作用。
- 批准后由后端权威状态驱动 E 的前端执行；拒绝则 E 不执行并形成真实拒绝结果。
- 停止、刷新、跨实例、重复回包和恢复分别使用 A/E 的权威状态与现有 req、journal、lifetime、guard 机制。

## 复用边界

复用 `ToolRisk`、`ToolRegistry`、`SpToolCallingManager`、`ConfirmGate`、`RunStore`、现有帧和前端 `ToolPart` 分派。参考表格写预览的“计划与执行分离”，但不复用浏览器局部 diff 作为审批权威；不把下载改造成上传交接卡。

禁止 `DANGER + FRONTEND` 同一条目直接切换 kind、伪造 APPROVED+FRONTEND、普通工具卡内嵌按钮、普通工具段写入 decision、下载专用确认 Map，以及绕过 ConfirmGate 的前端假批准。

## 验收

- 批准前零切页、预检、文件生成、Blob/锚点下载和业务写入。
- 批准后仅一次前端执行；拒绝后零执行且刷新不复活。
- A/E 在批准前、批准后、执行中、完成后、停止和刷新时分别保持真实状态。
- 通用声明覆盖其他 DANGER+FRONTEND 工具，无工具名判断；既有 BACKEND 风险约束不变。
- 普通 UI 工具卡不再出现撤销入口；表格写预览和 undo_last_sheet_edit 行为不变。
- 下载和打开编辑器只读 viewStep，不隐写配置、条件或步骤。
