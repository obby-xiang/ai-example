> 来源=<仓外证据>/main-ci-cb0761d-evidence/README.md（2026-10-25 归档）· 仓外只读取证说明（审计侧边界件），属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# main CI 40192492193 @ cb0761d · 后端失败证据

本次只读取证。仓库只被读取，未改代码、未重跑 workflow。

- run: 40192492193（push，base=34fd8f4，head=cb0761d）
- 失败 job: 后端编译与单测（job 121166308316），4m29s
- 合并日志: `<仓外证据>/main-ci-cb0761d-failed.log`

## 为什么没有 surefire XML

`gh api repos/<仓库所有者>/ai-example/actions/runs/40192492193/artifacts` 只返回一个产物：

- `scan-history-report`（1472 B，全历史脱敏扫描报告，与后端无关）

`.github/workflows/ci.yml` 的 backend job 只跑 `mvn -B test`，不归档 `backend/target/surefire-reports`；
surefire 归档只存在于 `nightly.yml:95-102`（artifact `nightly-surefire`，与本 run 无关）。
因此可下载的"后端 surefire 报告"不存在，证据只能取自 job 日志的 mvn 控制台输出。

## 本目录文件（均为日志摘录，无密钥）

- `backend-failure-block.txt` — 失败用例断言原文 + 栈（合并日志 17376-17389 行）
- `backend-surefire-summary.txt` — surefire 汇总 + BUILD FAILURE（20479-20492 行）
- `gate-summary.txt` — 门禁汇总 job 的逐 job 记账（20582-20599 行）
