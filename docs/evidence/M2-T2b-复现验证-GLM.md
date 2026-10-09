# M2-T2b 复现验证报告（GLM-5.3 独立复现验证官）

- 验证日期：2026-10-09
- 验证对象：<REPO_ROOT>（HEAD = `2354721e14e21162a2b01265bfd93f3df828d1be`）工作区未提交的 T2b 成果
- 验证纪律：全程零 git 写操作、未修改任何既有文件（唯一写为本报告新建 + 仓库外 scratch 运行产物）；AI key 仅 shell 环境变量注入（未写入任何文件，本文中记 `***`）
- 运行产物目录（仓库外）：<SCRATCH>（mvn-test.log / fe-typecheck.log / fe-build.log / backend.log / frontend-dev.log / smoke-run.log / smoke-out/result-20261009T011313Z.json 等）
- 环境要点：Maven = <MAVEN_HOME>/bin/mvn.cmd（IntelliJ 内置 maven3）；Node v22.23.2；yarn 1.22.22；Redis(127.0.0.1:6379) 在线；Chrome 154.0.8037.98；后端端口 18330（启动前 netstat 确认空闲）
- 改动清单（git status 实测，11 修改 + 3 新增）：`ai/form/GenerativeFormRules.java`、`ai/gate/ConfirmGate.java`、`ai/run/RunStore.java`、`ai/web/AiController.java`、`ai/conformance/InMemoryRunStore.java`、`frontend/src/api/ai.ts`、`AiPanel/AiPanel.vue`、`AiPanel/FormRenderer.vue`、`frontend/src/stores/ai.ts`、`frontend/src/types/ai.ts`、`scripts/m2t2a-smoke.mjs`（M）；新增 `ai/run/RunStoreSessionCurrentTest.java`、`ai/web/AiControllerRunsCurrentTest.java`、`docs/evidence/M2-T2b-施工与验证-K2.8.md`

---

## 第 1 项：后端测试【实测】

**执行命令**（<REPO_ROOT>/backend）：

```
<MAVEN_HOME>/bin/mvn.cmd test
```

**原始关键输出**（完整日志：<SCRATCH>/mvn-test.log）：

```
[INFO] Tests run: 263, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

退出码 0。

**surefire XML 独立重算**（<REPO_ROOT>/backend/target/surefire-reports/TEST-\*.xml，用 Python `xml.etree` 逐文件解析 `testsuite` 根属性求和，不依赖 mvn 汇总行）：

```
XML sum: files=40 tests=263 failures=0 errors=0 skipped=0
TEST-com.example.configmgr.ai.run.RunStoreSessionCurrentTest.xml   -> tests=7 failures=0 errors=0 skipped=0
TEST-com.example.configmgr.ai.web.AiControllerRunsCurrentTest.xml  -> tests=7 failures=0 errors=0 skipped=0
```

**与预期比对**：

| 项 | 预期 | 实测 | 一致性 |
|---|---|---|---|
| XML 口径总执行数 | 263 | 263 | 一致 |
| XML 口径失败/错误/跳过 | 0/0/0 | 0/0/0 | 一致 |
| mvn 汇总行 | — | 263, 0 失败 | 与 XML 口径互相印证 |
| RunStoreSessionCurrentTest | 7 个 | 7 个（0 失败） | 一致 |
| AiControllerRunsCurrentTest | 7 个 | 7 个（0 失败） | 一致 |

**结论：一致。** 双口径（mvn 汇总 / XML 求和）均 263 执行 0 失败，两个新增测试类各 7 个用例全绿。

---

## 第 2 项：前端 typecheck + build【实测】

**执行命令**（<REPO_ROOT>/frontend）：

```
yarn typecheck    # 实际执行 vue-tsc --noEmit
yarn build
```

**原始关键输出**（<SCRATCH>/fe-typecheck.log、fe-build.log）：

```
yarn run v1.22.22
$ vue-tsc --noEmit
Done in 8.52s.            # typecheck 退出码 0

✓ built in 38.48s
Done in 46.12s.           # build 退出码 0
```

**与预期比对**：typecheck 退出码 0 ✓；build 退出码 0 ✓（产物 dist/ 正常生成，含 index/element/spreadjs 等 chunk）。

**结论：一致。**

---

## 第 3 项：冒烟脚本实跑（CDP 驱动真实 Chrome）【实测】

**语法检查**：

```
node --check scripts/m2t2a-smoke.mjs   →  SYNTAX OK（退出码 0）
```

**环境搭建**（均本验证官自起、结束后清理）：

- 后端（18330）：`SERVER_PORT=18330 AI_API_KEY=*** AI_BASE_URL=https://api.deepseek.com AI_MODEL=deepseek-flash <MAVEN_HOME>/bin/mvn.cmd spring-boot:run -DskipTests`，key 仅环境变量注入，未写入任何文件。启动前 `netstat -ano | findstr :18330` 确认空闲；轮询 `GET /api/ai/tools` 返回 200 确认就绪。Redis 6379 已在线（未动）。
- 前端 dev：`VITE_API_BASE=http://localhost:18330 VITE_DEV_PORT=5202 yarn dev`（vite /api 代理到 18330，与脚本默认 SMOKE_APP=http://localhost:5202/ 对齐）。
- 冒烟脚本环境变量：`SMOKE_API=http://localhost:18330 SMOKE_APP=http://localhost:5202/ SMOKE_OUT=<SCRATCH>/smoke-out`。

**执行命令**：<REPO_ROOT> 下 `node scripts/m2t2a-smoke.mjs`

**原始关键输出**（完整日志：<SCRATCH>/smoke-run.log；结果 JSON：<SCRATCH>/smoke-out/result-20261009T011313Z.json）：

```
[chrome] pid = 7304
[cdp] browser = Chrome/154.0.8037.98
[app] ready
[阶段1 双消息] {"formRendererCount":1,"cancelButtons":1,"pendingCards":2,"pendingStatuses":["pending","pending"],"activeFormStatus":"filling","cancelledTags":0,"toolRunCount":0}
[断言②] PASS {"formRendererCount":1,"cancelButtons":1,"expect":1}
[阶段2 取消] {"formRendererCount":0,"cancelButtons":0,"pendingCards":2,"pendingStatuses":["cancelled","cancelled"],"activeFormStatus":"cancelled","cancelledTags":2,"toolRunCount":0}
[断言③] PASS {"pendingStatuses":["cancelled","cancelled"]}
[断言③b] PASS {"formRendererCount":0,"cancelledTags":2}
[阶段3] 真实挂起轮 session = m2t2b-5179e95d-8f3c-4e4f-9cbe-e79920b78c6f api = http://localhost:18330
[阶段3a] await stopped = true seen = ["start","suspended","tool_start","frontend_tool_request"]
[阶段3b 刷新重建] {"formRendererCount":1,"cancelButtons":1,"pendingCards":1,"pendingStatuses":["pending"],"activeFormStatus":"filling","cancelledTags":0,"toolRunCount":1}
[阶段3d 收敛] {"path":"submit","values":{"keyword":"smoke","minRows":1,"effectiveDate":"2026-10-08","scope":"XN"},"finalFormStatus":null}
[阶段3d 结局帧] {"ftr":true,"done":true}
[断言①] PASS {"pendingCards":1,"formRendererCount":1,"toolRunCount":1,"pendingStatuses":["pending"],"activeFormStatus":"filling","convergePath":"submit","frontendToolResult":true,"done":true}
[done] result written to <SCRATCH>/smoke-out/result-20261009T011313Z.json
SMOKE_EXIT=0
```

**与预期比对**：

| 项 | 预期 | 实测 | 一致性 |
|---|---|---|---|
| 断言① | PASS，detail 含 formRendererCount:1、toolRunCount:1、convergePath:"submit" | PASS，三项全中（另 pendingCards:1、frontendToolResult:true、done:true） | 一致 |
| 断言② | PASS | PASS | 一致 |
| 断言③ | PASS | PASS | 一致 |
| 断言③b | PASS | PASS | 一致 |
| 脚本退出码 | 0 | 0 | 一致 |

关键链路均为真实链路：阶段3a 是真实 DeepSeek 模型轮（帧序列 start→suspended→tool_start→frontend_tool_request，模型真实调起 generative_form 并挂起）；阶段3b 整页刷新后经 `GET /api/ai/runs/current` 快照重建出恰好 1 张挂起卡 + 1 个 FormRenderer + 1 条 toolRun（无重复工具卡）；阶段3d 走 submit 路径收敛，原挂起 SSE 连接收到 `frontend_tool_result` 与 `done` 帧。

**结论：一致。** 无环境卡点，未使用任何降级/桩。

---

## 第 4 项：脱敏与命名纪律独立扫描【实测】

**扫描范围**：工作区全部 14 个改动/新增文件（清单见文首），全文 grep：

```
(a) grep -nE 'sk-[A-Za-z0-9_-]{10,}' <14个文件>
(b) grep -niE 'e:[/\\]temp|d:[/\\]program files|c:[/\\]users' <14个文件>
(c) grep -niE 'main[- _]?v2' <14个文件>
```

**实测结果**：

| 模式 | 命中数 | 说明 |
|---|---|---|
| (a) `sk-` 开头真实 key（≥10 位字符） | 0 | 宽口径补扫任意 `sk-` 字面仅命中 1 处：K2.8 报告 186 行的 `sk-…` —— 为脱敏占位符（省略号），非真实 key，不构成违规 |
| (b) 本机盘符绝对路径（各盘符与正/反斜杠变体，忽略大小写） | 0 | 零违规；字面量按 DC-08 不在正文复述 |
| (c) 含 main+分隔符+v2 的仓库名（任意大小写与分隔符组合） | 0 | 零违规（三支豁免脚本的匹配源正则均不在本次改动清单内，无需启用豁免）；字面量按 DC-08 不在正文复述 |

**结论：一致（零违规）。**

**附带观察（不影响判定，供施工方知悉）**：K2.8 报告第 186 行自述其当时 key 注入方式与施工令存在偏差（从别处 .env.local 提取注入 shell env）。本复现验证严格按施工令以 `export AI_API_KEY=***` 注入、未读取任何 .env 文件，结果同样全绿，故该偏差不影响成果有效性。

---

## 复现过程中的仓库完整性自检【实测】

- 全程零 git 写操作（无 add/commit/stash/checkout 等）。
- 验证结束后 `git status --porcelain` 与开始时逐行一致（11 M + 3 ??，构建产物 target/、dist/ 均被 .gitignore 吸收），HEAD 仍为 `2354721`。
- 自启进程全部清理：后端（mvn spring-boot:run 及其 Java 子进程）、前端 dev（yarn/vite 及残留 node 子进程 PID 16580 已确认命令行后停止）、冒烟脚本自启 Chrome（脚本内 `chrome.kill()` 自清）。最终 `netstat` 确认 18330 / 5202 / 9333 无 LISTENING 残留（仅操作系统 TIME_WAIT 尾迹，自行消散）。Redis(6379) 非我启动，未触碰。

---

## 总体判定

**复现通过。**

四项复现全部与预期一致，均为【实测】原始输出：

1. 后端测试：mvn 汇总 263/0，surefire XML 独立求和 263 执行 0 失败 0 错误 0 跳过；新增 RunStoreSessionCurrentTest 与 AiControllerRunsCurrentTest 各 7 个用例全绿。
2. 前端：`yarn typecheck` 与 `yarn build` 均退出码 0。
3. 冒烟：`node --check` 通过；后端(18330，key 仅环境变量) + 前端 dev 实跑，断言①②③③b 全 PASS、退出码 0；断言① detail 含 formRendererCount:1、toolRunCount:1、convergePath:"submit"（且为真实 DeepSeek 挂起轮 + 刷新快照重建全链路）。
4. 脱敏与命名纪律：真实 key / 本机路径 / 含 main+分隔符+v2 的仓库名三类模式，对本次改动/新增文件扫描零违规；字面量按 DC-08 不在正文复述。（**追加留痕**：提交官终扫按 `git status` 全量复核发现本自查**未含报告自身**，并修正本报告第 6 行真实本机路径与两处旧口径字面量直书，见 `docs/M2-排期计划.md` §f 第 3 条治理留痕。）

无部分通过项，无差异项，无环境卡点。
