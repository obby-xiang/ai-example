# Pressure Verification Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使压测与独立验证脚本匹配通用 reasoning、终态历史降级、交互卡新规格和真实 Redis/业务结果证据要求。

**Architecture:** 各脚本按文件独立修改，复用现有公开 API、仓外只读 Redis/业务快照与证据目录。正常路径遇到任何 transcript 降级一律 FAIL；专用故障场景单独分类。C-127～C-129 仅登记 NOT_RUN，不伪造可执行 API 覆盖。

**Tech Stack:** Node.js 内置模块、现有脚本与 Redis CLI/公开 API。

**Spec:** agent-1111 压测规格漂移报告；`docs/verification/AI对话独立复测清单-2026-11-05.md`；`docs/delivery/development-standards.md`。

## Global Constraints

- 只修改压测/独立验证脚本和指定文档，普通测试代码与生产代码不得修改，施工阶段不执行测试或压测。
- 正常 done/error 必须拒绝 transcriptDegraded、historyComplete=false及两个 transcript 错误码；专用故障证据不得混入健康 PASS。
- history 完整性必须包含同 run assistant、工具/表单 parts、顺序和最终结局，不能只数 USER 或查非空。
- 真实 Redis/业务对照未执行时 verdict 保持 NOT_RUN；不得把延迟、HTTP200、单次成功或 Mockito 外推为内容通过。
- 每次 SCENARIO 仅一个变体，不批量循环单元测试；OUT_DIR 必须仓外，禁止伪造应用内帧或 AI 数据入库。
- 每批独立关联审查、`node --check`、规范门禁后提交推送。

## Tasks

### Task 1: 定向一轮交互脚本
- Modify `scripts/verification/ai-one-turn-interaction-api.mjs`
- 登记 C-127～C-129 NOT_RUN；健康终态拒绝降级；history/parts/reasoning/结局与真实 Redis/业务证据对齐。

### Task 2: Progressive disclosure 脚本
- Modify `scripts/verification/ai-progressive-disclosure-eval.mjs`
- transcript 独立分类，保留独立 reasoning/history 证据，done/error 两路识别降级。

### Task 3: 核心 API 压测脚本
- Modify `scripts/perf/ai-api/core-coverage.mjs`
- 健康终态拒绝降级，history/run/真实 Redis 内容对照，保留未直验声明直到真实执行。

### Task 4: 当前协议脚本与范围文档
- Modify `scripts/verification/ai-current-protocol-api.mjs`
- Modify `docs/verification/AI对话独立复测清单-2026-11-05.md`
- health 定位明确，补隔离/写门禁和 OUT_DIR 证据；纠正“只读”描述，保留 latest=1 和精确数量断言。

### Task 5: Redis harness 边界文档
- Modify `scripts/perf/redis-protocol/README.md`
- Modify `scripts/perf/redis-production/README.md`
- 区分普通 error 与 DONE+transcriptDegraded；无 HTTP/Mockito 不得外推新行为通过。

### Task 6: 独立审查与设备提示词
- 逐文件静态审查并 `node --check`，提交推送后输出完整设备执行提示词。
- 真实压测、Cluster、多实例、故障注入和浏览器结果在设备返回前均 NOT_RUN。
