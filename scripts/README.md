# scripts/ 说明

> 本目录放**可执行的验证与门禁脚本**（不是文档目录）。三条内容：E2E 验证脚本、CI 上游桩、提交前敏感信息扫描钩子。
> 文档导航见 `docs/README.md`；如何启动前后端见仓库根 `README.md`。

| 路径 | 作用 |
| --- | --- |
| `scripts/verify-e2e.ps1` | 端到端验证脚本（27 用例，PowerShell）。断言对象是**已启动的后端**，脚本自身不启动任何服务 |
| `scripts/ci/stub-upstream.mjs` | CI E2E 用的**上游替身**（OpenAI 兼容桩），让用例在零密钥、零外网下确定性通过 |
| `scripts/ci/stub-routes.json` | 桩的路由表（提示词 → 工具调用/表单），与 `GenerativeFormRules` 白名单手工对账 |
| `scripts/githooks/` | 提交前敏感信息扫描（DC-08 落地），详见 `scripts/githooks/README.md` |

---

## 1. `verify-e2e.ps1` —— E2E 验证脚本

### 1.1 前置条件（脚本不代管，缺项直接失败）

- **后端已在跑**，且满足：
  - AI key 经环境变量注入（`AI_API_KEY`，兼容 `DEEPSEEK_API_KEY` 兜底）；缺 key 时 AI 类用例判 FAIL；
  - **Redis 可用**（默认 `127.0.0.1:6379`）—— AI 会话记忆 / 确认门 / 会话锁都走它；
  - 建议启动参数 `--app.job.batch-size=10 --app.job.demo-batch-delay-ms=150`（TC11 断言 `JOB_PROGRESS` 事件流；`batch-size` 为默认 100 时小配置项不会跨分片边界）；
  - 日志级别 `com.example.configmgr=DEBUG`（TC10 与 Q8 的日志类断言依赖它）。
- **数据面安全**：脚本只创建/删除自己的 `S5B-*` 任务与 `E2E_TEMP` 定义；`CURRENCY` 演示数据在 TC13 被替换为 3 行，TC19 用全量导出件恢复为 5 行。

### 1.2 参数表

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `-Base` | string | `http://127.0.0.1:18330` | 后端基地址（脚本会在末尾自动去掉 `/`） |
| `-BackendLog` | string | 空 | 后端 stdout 日志文件路径。TC10 的日志断言（Q8①）与 Q8 两项回归需要它；**未提供时日志类断言判 FAIL，不静默跳过** |
| `-EnableGf6` | switch | 关闭 | 启用 GF6 可选观察用例（入参闸门）；**不计入主计数**（GF1–GF5 才是主用例） |
| `-ArtifactDir` | string | 系统临时目录下的 `gfc-artifacts-<guid>`（仓库外） | GF 证据落盘目录；每个 GF 用例写 `gfc-<case>.sse.txt`（实收 SSE 帧）与 `gfc-<case>.http.txt`（回灌 POST 请求/响应原文） |

### 1.3 调用方式

本地手动（真模型白盒验证）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 `
    -Base http://127.0.0.1:18330 `
    -BackendLog <后端 stdout 日志路径> `
    -ArtifactDir <证据落盘目录>
```

CI 内（桩模式）：

```powershell
$env:E2E_STUB_MODE = '1'
./scripts/verify-e2e.ps1 -Base http://127.0.0.1:18330 -BackendLog <boot.log> -ArtifactDir <artifacts/gfc>
```

### 1.4 用例构成（27 用例）

| 分组 | 计数 | 计入主 PASS/FAIL 与退出码 |
| --- | --- | --- |
| TC1–TC22（业务全链路 + AI 交互） | 22 | 是 |
| GF1–GF5（生成式表单端到端：FILTER/CLARIFY 回灌续跑、取消路径、复核拒绝、幂等 409 双向） | 5 | 是 |
| Q8①/Q8②（明细落库、SSE 请求不做二次 JSON 写入） | 2 | **否**，单列计数 |
| GF6（入参闸门观察用例，需 `-EnableGf6`） | 1 | 否 |

### 1.5 退出码与判定口径

| 情形 | 结果 |
| --- | --- |
| 任一主用例 FAIL | `exit 1` |
| GF1–GF4 **全部 SKIP**（本棒零有效验证，硬底线） | `exit 1` |
| `E2E_STUB_MODE=1` 且 `GFSKIP ≥ 1` | `exit 1`（**桩模式零容忍**） |
| 仅 Q8 项 FAIL | 不影响退出码（单列展示） |

**桩模式零容忍（`E2E_STUB_MODE=1`）**：上游是确定性替身，"模型没触发工具"这件事在桩下不可能由模型引起——任何 GF SKIP 都意味着桩路由或产品链路异常，因此 GFSKIP≥1 即整棒 FAIL。真 key 本地手动跑法不设该环境变量，SKIP 语义不变（模型未触发，不计退出码）。

### 1.6 输出与证据

- 逐用例 `[PASS]`/`[FAIL]`/`[SKIP]` 行，末尾摘要打印 `PASS=`/`FAIL=`（实时计数）、`GFSKIP=`、`Q8 项 PASS/FAIL`；
- GF 用例另落盘：`gfc-<case>.sse.txt`（脚本实际收到的 SSE 帧，按到达顺序）与 `gfc-<case>.http.txt`（脚本实际发出的回灌 POST 原文）——失败时用于区分"桩坏 / 产品坏"。

---

## 2. `scripts/ci/stub-upstream.mjs` —— CI 上游替身

### 2.1 用途边界（引用文件头口径，**不得扩大解释**）

- 本桩是**产品链路的替身，不是模型行为的替身**。CI 全绿只证明"桩给出的表单/工具调用被产品链路正确消费"，**不证明模型服从率**；
  桩下 `GFSKIP=0` 由构造保证（桩按路由表下发表单），属恒真式而非观测；
- **真模型的 E2E 维持本地手动跑**（S5d 裁决 #3），不在 CI 内；
- 零依赖：只用 Node 内置模块（`--self-check` 会断言这一点）。

### 2.2 收敛契约（硬要求：后端工具循环无迭代上限，桩必须自证终止）

① 披露清单驱动 —— 目标工具必须出现在本请求 `tools[]` 里才允许发起调用；② 每个会话轮次的上游调用上限（`convergence.maxCallsPerTurn`，默认 6）；③ 续轮只发文本（上下文已有 `role=tool` 消息时不再发起工具调用）；④ 解析失败不猜（一律以文本收尾）。`--self-check` 用"首轮→工具调用、续轮→文本"的模拟循环逐条证明上述四条成立。

### 2.3 命令行

```sh
node scripts/ci/stub-upstream.mjs --port 18399 --dir <落盘目录> [--routes scripts/ci/stub-routes.json]
node scripts/ci/stub-upstream.mjs --self-check [--routes scripts/ci/stub-routes.json]
#      [--violate-form-keys | --violate-form-type]   # 违规模式，默认关闭（仅缺陷注入时开启）
```

### 2.4 记录契约（可归因性）

每请求落 `<dir>/req-NN.json`（请求原文）、`.tools.txt`（本请求实际披露的工具名清单）、`.head.txt`（模型可见消息摘要）、`.decision.json`（桩本轮的判定：路由 id / 工具名 / 理由）——这是"桩坏 / 产品坏"的区分依据。

### 2.5 与产品侧白名单的对账现状（已知窗口）

`stub-upstream.mjs` 的 8 组常量 + 5 项上限目前是 `GenerativeFormRules` 的**手工副本**，自检③只核副本自身。事实源自动对账已挂 `docs/M2-排期计划.md` T4 条目（工程化改造待实施）。

---

## 3. `scripts/githooks/` —— 提交前敏感信息扫描

两条防线（`pre-commit` 扫暂存区新增行、`scan-history.sh` 扫全历史 blob），规则共用 `scan-lib.sh`。启用方式、三条规则、豁免清单与自测方法见 **`scripts/githooks/README.md`**。
