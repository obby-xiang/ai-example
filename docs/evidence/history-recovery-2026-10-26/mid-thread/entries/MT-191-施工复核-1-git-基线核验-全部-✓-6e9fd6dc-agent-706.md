<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-191 · 施工复核 · 1. git 基线核验 — 全部 ✓（6e9fd6dc / agent-706）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`ConfirmGate`、`交接提示词`、`ai-example-code/ai-example-main`。
> **稿态**：历史中间稿 —— 该 wire 第 `m6` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt6` 条有正文的 assistant 消息（共 7 条有正文），末条有正文消息为 `m10`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `6e9fd6dc` / agent `agent-706`（`main` = 主会话）；原字符 3,486（链内 1 段、合计 3,486）。
> **链号 / 类别**：`MT-191` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `7%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m10`（888 字符）未入末条归档。
> **脱敏**：路径 6 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 6 处。

---

核验完成。逐项结论如下。

## 1. git 基线核验 — 全部 ✓

| 命令 | 实测值 | 期望值 | 结果 |
|---|---|---|---|
| `git -C ".../<REPO>" rev-parse HEAD` | `1e2a32a724bd61167f5e156f46bdeb26c7ec3709` | 同左 | ✓ |
| `git -C ".../<REPO>" status --porcelain` | `?? data/`（仅 1 行） | 仅 1 行 `?? data/` | ✓ |
| `git -C ".../<REPO>" rev-list --left-right --count origin/main...HEAD` | `0	0` | `0	0` | ✓ |
| `git -C ".../<REPO>" log --oneline -3` | `1e2a32a docs(kanban): ConfirmGate 后端单测时敏抖动挂账（并入 R-153 预留）` / `94d9ec1 docs: R-148 后端活文档同步（契约六键 + M2 登记闭环）` / `69b2476 docs(evidence,kanban): R-148 后端施工/复核报告入库 + 看板全闭环` | 顶部 1e2a32a / 94d9ec1 / 69b2476 | ✓ |

工作区干净度符合预期（仅未跟踪的 `data/`），与 origin/main 无分叉。全程未触碰 `<LOCAL_WORK>\ai-example` 与 `<LOCAL_WORK>\<OLD_REPO>`。

## 2. R-144 施工卡完整性 — ✓

命令：`stat -c %s "<LOCAL_WORK>/r144-history/施工卡-R144-定稿.md"` + `sha256sum`

| 项 | 实测值 | 期望值 | 结果 |
|---|---|---|---|
| 字节数 | 54257 | 54257 | ✓ |
| sha256 | `0c4414adc50ff464eb6955063315b3e30b7c0679c578ff883f49cb47d0571d6d` | 同左 | ✓ |

## 3. 态档完整性 — ✓

命令：`stat -c %s` / `wc -l` / `sha256sum` 于 `<LOCAL_WORK>/handoff-archive/交接提示词-2026-10-12-当前态.md`

| 项 | 实测值 | 期望值 | 结果 |
|---|---|---|---|
| 字节数 | 54016 | 54016 | ✓ |
| 行数 | 188 | 188 | ✓ |
| sha256 | `4e96d4d43ba7b8253d8d05faea93dbce5601bd384c2cf582c8d9473a2569ce82` | 同左 | ✓ |

## 4. CI 末笔 run（38242455601）— ✗

命令（cwd=`<REPO>`）：`gh run view 38242455601 --json status,conclusion,jobs`

- `status` 实测 `completed`，期望 `completed` → ✓
- `conclusion` **实测 `failure`，期望 `success` → ✗**

该 run 确为末笔：`gh run list --limit 5` 显示 `38242455601` 的 `createdAt=2026-10-12T11:24:35Z`、`headSha=1e2a32a724bd61167f5e156f46bdeb26c7ec3709`，为最新一条。

细分：5 个 job 中 4 个 success，仅 **`E2E 全流程（29 用例 · 桩上游 + redis）`** 的步骤 **`运行 E2E（29 用例）`** 为 failure（「文档一致性门禁」「全历史脱敏扫描」「后端编译与单测」「前端类型检查与构建」均 success）。

原样实测失败输出（`gh run view 38242455601 --log-failed`）：

```
[FAIL] MAJOR-2 => MAJOR-2：PUBLISH 响应 HTTP 409/code=PRECHECK_NOT_PASSED，期望 409/TASK_FINALIZED
结果：PASS=30  FAIL=1  （主用例计数，含 TC1–TC22、TC23 与 GF1–GF6（GF6 需 -EnableGf6）；基数不写死，随用例演进）
GFSKIP=0  （桩模式零容忍：GFSKIP≥1 即整棒 FAIL（上游是确定性替身，任何 SKIP 都属桩路由或产品链路异常））
Q8 项：PASS=2  FAIL=0  （不计入主用例计数，见 docs/evidence/S5b-deepseek-E2E移植验证.md）
```

即：E2E 主用例 30 通过 / 1 失败，唯一失败为 `MAJOR-2`（PUBLISH 期望 `409/TASK_FINALIZED`，实际返回 `409/PRECHECK_NOT_PASSED`）。注意该 `MAJOR-2` 在历史记录里已被登记为「间歇抖动挂账」（参 `38238514073` 提交 `docs(kanban): R-152 CI 复绿销记 + MAJOR-2 间歇抖动挂账`），与 HEAD 提交信息中提到的「ConfirmGate 后端单测时敏抖动」属不同的登记项。

**未做的补充核验**：我未读 `docs/evidence/S5b-deepseek-E2E移植验证.md` 或看板全文去比对 MAJOR-2 的挂账口径与本次失败是否同一性质；如需确认「是否为已知间歇抖动而非新回归」，建议下一步查该证据文档与看板 R-153 预留行。

## 5. 模型自证（如实说明，与预期不符）

我**无法证实自己运行在 DeepSeek/deepseek-flash 上**，且现有证据指向相反方向：

- 运行环境为 **Kimi Code CLI**（进程路径 `<DRIVE>/Program Files\Kimi Code`，工具面为 Kimi Code 内置工具集）。
- `<KIMI_HOME>/config.toml` 中 `default_model = "kimi-code/k3"`，管理侧 provider 为 `managed:kimi-code`（`https://api.kimi.com/coding/v1`）。配置里虽另有 `[providers.DeepSeek]` 条目，但并非 default。
- 环境变量中没有暴露本次会话的实际模型标识（`env | grep -i model` 无命中），我也没有对自身权重/服务端模型的可靠内省通道。

因此我不能按「钉为 DeepSeek/deepseek-flash」来自证——那会是不实陈述。我的实际运行面是 Kimi Code（依据为上述 CLI 路径与该 config 的 `default_model`）。若必须确认本次服务端模型，需要由调用方从会话元数据/网关日志侧核实，而非由我自证。

## 汇总

- 项 1、2、3：全部 ✓（git 基线、R-144 卡、态档均逐字节/逐哈希命中期望）。
- 项 4：`status=completed` ✓，但 `conclusion=failure` ✗ —— E2E `MAJOR-2` 用例失败（PASS=30 / FAIL=1）。
- 项 5：模型自证与「DeepSeek/deepseek-flash」预期**不符**，实为 Kimi Code（`default_model=kimi-code/k3`），无法自证为 DeepSeek。

全程只读：未执行任何 `git add/commit/push`，未改动任何文件。
