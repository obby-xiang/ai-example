> 来源=<仓外证据>/old-workflow-triage/README.md（2026-10-26 归档，入库改名 `github-workflow-previous-runs.md`）· 既有 GitHub workflow 失败归因（历史 run 证据；不能当作当前通过）；属历史证据，不代替终稿/现行基线
# 既有 GitHub workflow 失败归因（最终验收前置）

- 核验对象：`ci` 之外的既有失败，以及当前主 CI 的真实红项。
- 当前代码基准：`1d7b1f0872769987c89a5083ef1aa3f15fb33183`。
- 性质：历史日志、当前代码与回归用例的只读核对；**不是新环境验收**。
- 原始证据：同目录 `cluster3-40182796512.log`、`cluster3-40174291244.log`、`e2e-real-40171918873.log`、`nightly-40131155913.log`、`ci-40211303032.log`；`e2e-real-artifacts/` 保存该次 trace。
- 所有密钥字段均以平台掩码为准；报告不摘录密钥值或真实请求正文。

## 1. cluster-3node run 40182796512

- 代码：`34fd8f405d0a7c57cb2b7bae387d04249fce5ba1`。
- 实际结果：15 PASS / 1 FAIL；失败为 `suspend-cross-instance`，决策后跨实例差量重挂 120 秒无帧。
- 归因：种子写路径未发配置版本，其他实例的 provider 快照为空，续跑精确绑定失败；失败派发的观测也不足。详见 `docs/evidence/ai-remediation-2026-10-25/audit/ai-full-audit/FINAL-AUDIT.md`。
- 当前基准已有补丁：种子事务提交后的版本发布、续跑失败终态处理、探针冷启动身份硬闸。
- 已有回归：`AiConfigSeederConvergenceTest`、`AiConfigVersionPublisherTest`、`AiResumeExactProviderBindingTest`、`ResumeProviderGoneTerminalTest`。
- 更早的 40174291244 还覆盖共享文件字节、挂起早杀补帧、流式接管序号、作业接管与 readiness 等问题；对应修复与回归已合流。

**未证明边界**：真实三实例从未在上述当前基准运行；`suspend-cross-instance` 尚无成功记录，新冷启动硬闸也未经真实三实例执行。必须重跑，不能由单测推定 16/16。

## 2. e2e-real run 40171918873

- 代码：`20c56bb7d39d2e3a99d6586037145ff43d82e7d2`；输入 `suite=real`。
- 实际结果：桩 job 按条件未运行；真模型 job 确实执行 `@real`，9 次采样中 8 次通过。不是缺 key 或真模型未执行。
- 唯一失败：`frontend/e2e/11b-hgta.spec.ts` 的 G-01，10 次采样均未出现带 `gf-submit` 的活卡。
- trace DOM 显示：模型确实发起 `generative_form`，但出现的是 `ro*` 只读回放卡，提交按钮未出现。不能把该次失败归因成模型未调用工具。
- 当前基准已有迟到 `tool_start` 不复位等待卡、挂起投影旁路、park 请求帧前置等修复；对应纯函数/store/conformance 回归已存在。

**未证明边界**：该基准之后没有真模型复跑。最终必须用 `suite=both` 同时验证桩和真模型；G-01 当前计数仍混合“无工具帧”和“卡只读”，即使通过也只能按该用例实际断言的形状报告，不能夸大为任意模型、任意输入下必然可提交。

## 3. nightly run 40131155913

- 代码：`d66ab7c631cb138ca3f9c1acf5c75ba55bbd4b23`。
- 后端双 locale、前端、testmap 成功；E2E 与汇总失败。
- 唯一失败用例为 W-21：回灌后重挂缺少 `done`。后续 `bbc2089`、`ae883fb` 的终帧重连、park 收流与自愈派发补丁覆盖该族时序。
- 当前主 CI 的同一套 E2E 中 W-21 已通过；但 nightly 的双 locale、两遍 testmap 和全量 E2E 仍需在当前最终代码上实际成功。

## 4. 当前主 CI run 40211303032

- 代码：上述 `1d7b1f0`。
- 后端全量、前端类型检查/单测/构建、文档、Redis 守卫通过；E2E 为 **165 PASS / 1 FAIL**，门禁汇总相应失败。
- 新失败为 W-30：前端工具请求刚到便取消，与 park 退出重叠，取消收尾没有持久化 `CANCELLED`，快照仍为 `SUSPENDED`。日志同时出现 Redis command interrupted 与续跑权拒写；不能靠延大超时、删断言或绕过 fencing 消除。
- 这不是上述旧 W-21、种子身份或只读卡问题。最终须保留确定性回归，并重跑真实 E2E；不能以已有单元测试替代真实验收。

## 5. 最终验收口径

1. 主 CI 修复后实际 success。
2. `cluster-3node.yml` 实际运行三 JVM、共享基础设施、无粘滞路由、跨实例与 kill 接管，并核对 `suspend-cross-instance` 等重点用例。
3. `redis-5-smoke.yml` 实际成功；其环境级结论不冒充生产验证。
4. `nightly.yml` 的后端双 locale、前端、E2E、testmap、汇总均实际 success。
5. `e2e-real.yml` 用 `suite=both`、`sample=10`，桩和真模型 job 都执行且 success。
6. 只有上述事实齐全后才完成当前开发验收；任何未执行、skipped 或尚未覆盖的边界单独登记，九册正文不得提前开工。
