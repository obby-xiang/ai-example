<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-327 · 过程回执 · M2-T1.2「E2E 进 CI 门禁」实施完成（fc21d1da / agent-105）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`scripts/ci`、`scripts/githooks`、`scan-lib`。
> **稿态**：历史中间稿 —— 该 wire 第 `m117` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt4` 条有正文的 assistant 消息（共 32 条有正文），末条有正文消息为 `m406`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `fc21d1da` / agent `agent-105`（`main` = 主会话）；原字符 3,097（链内 1 段、合计 3,097）。
> **链号 / 类别**：`MT-327` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 7 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `3%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `3%`。
> **与末条归档（138 件）的关系**：末条 `m406`（2,465 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

## M2-T1.2「E2E 进 CI 门禁」实施完成

### 改动文件清单（全部未提交，零 git 写操作）

| 文件 | 类型 | 对应步 |
|---|---|---|
| `scripts/ci/stub-upstream.mjs` | 新增 626 行 | S1+S2+S3+S4 |
| `scripts/ci/stub-routes.json` | 新增 148 行 | S2/S3/S4 |
| `scripts/verify-e2e.ps1` | 修改 **2 行**（417/422：`$env:TEMP` → `[IO.Path]::GetTempPath()`，diff hunk 只落这两行） | S5 |
| `.github/workflows/ci.yml` | 修改 **+131/−1**（头部注释改写 + 新增 `e2e` job，67→197 行） | S6+S8 |
| `docs/evidence/M2-T1.2-E2E门禁实施-DS-V4-Flash.md` | 新增 347 行 | S8 |

`git status --porcelain` 只有这 4 项；产品代码（`backend/**`、`frontend/**`）、`.gitignore`、脚本断言与 SKIP 记账逻辑**零改动**。

### 实测结论摘要

- **27 用例全绿，4 次连跑**：手工 3 次 `PASS=27 FAIL=0 GFSKIP=0`、`Q8 项：PASS=2 FAIL=0`、退出码 0，耗时 **47s / 43s / 42s**；第 4 次按 workflow e2e job **逐步同构**的编排整链复现（自检→起桩→打包→起后端→就绪门→跑用例→归档→收尾→工作区洁净检查），**45s、退出码 0**（真模型基线 85–91s ⇒ 桩更快）。三次的 `[PASS]` 明细行逐字一致，桩判定台账一致（每轮 23 次上游调用）。
- **桩自检 8 项全过**；后端 `/health` 自报 `available=true / model=ci-stub / baseUrl=http://127.0.0.1:18399 / redis.available=true`；干净跑法后端 ` ERROR ` = **0**；`gfc-GF1/2/3=1、GF4=2、GF5=2` 与设计期望逐项相符；**零密钥**（workflow 内 `secrets.` 命中数 0，只用固定占位串）。
- **YAML 自检通过**（PyYAML 解析：3 job、`needs: backend`、`services: redis:7`、artifact 保留 7 天）。
- 脱敏：用仓库自带 `scripts/githooks/scan-lib.sh` 引擎扫过 5 个产出文件，**SECRET/PATH/USER 零命中**；文档内无盘符样式字面量。

### S5 / S6 验证结果

- **S5（脚本跨平台）**：2 行补丁在 Windows PowerShell 5.1 下取值等价并 4 次跑通【实测】；**Linux + pwsh 7 未实测**（本机无 pwsh 且零 git 写操作禁止触发真实 CI run）⇒ 仍属【假设】，R3 预案已写入文档。反斜杠路径残留做了静态对账（上传件文件名全部显式给出、`$tmp` 内路径成对闭合、`-ArtifactDir` 走 `Join-Path`），判定"退化不改语义"【推断】。
- **S6**：就绪门、`127.0.0.1:6379` 端口对接（后端宿主进程 ↔ 独立 Redis）、`needs` 链、artifact 路径与保留期、收尾命令均已本地等价实测；`services:` 容器本身未实测【推断】。**另：TC18 的 `409 SESSION_BUSY` 由确认门挂起维持 —— 本棒实测证伪了"依赖模型慢"的假设**（桩是毫秒级响应仍稳定 409）。
- 一处本机差异（如实记录）：Git Bash 的 `$!` 是 MSYS PID，本地收尾步骤需用 `netstat` 取真实 Windows PID 才杀得掉；CI（Linux bash）不存在该差异。

### 【待裁决】清单

1. **桩缺陷的"假绿窗口"（最重要）**：硬底线 `gfskip14 >= 4` 只覆盖"GF1–GF4 全 SKIP"，而桩的路由缺陷通常只打掉部分用例。实测 `--violate-form-type` 轮：GF1/GF3/GF4/GF5 SKIP、`gfskip14=3`、`FAIL=0`、**退出码 0（门禁绿）**；`--violate-form-keys` 轮则因 GF5 误判 `FAIL=1` 报红（文案像产品缺陷、真因在桩）。建议：(i) 收紧为 `GFSKIP>=1` 即 FAIL（改脚本 1 行，超本棒授权）；或 (ii) 保持现状 + 把"CI 绿≠桩健康"写进运营口径 + 桩自检扩展到"路由表 vs `AiTools` 签名对账"。
2. **S7-① 产品侧缺陷注入未执行**：需改 `backend/**`，在"零 git 写操作/无法干净回退"约束下不可做（请授权一次性 worktree/分支，或接受用 B 轮 9 次 `FORM_SCHEMA_REJECTED` 侧证产品闸门）。
3. **S6 的真实 CI run 未执行**（零 git 写操作禁止 push）⇒ §8.3 的 pwsh/Linux 假设未能实测，需首个真实 PR/push 时确认，失败按 R3 回退。
4. **GF 走 SKIP 分支时脚本不落 `gfc-*`**（`Gf-Dump` 只在 FAIL/catch 路径），SKIP 时归因只能靠 `boot.log` + 桩 `req-*.decision.json`；补档需改脚本逻辑，超出授权。
5. 一处施工决策（非越权，供知悉）：证据目录取 `<WS>/backend/target/e2e-artifacts`（`target` 已被 `.gitignore` 排除 ⇒ job 后 `git status` 为空，满足验收 #5；同时仍在 workspace 内满足 upload-artifact 限制）。若要求改回 `<WS>/e2e-artifacts`，需加一行 `.gitignore`（超出授权文件清单）。
6. actions 版本核对：本仓其余 actions 均 `@v5`，故 `upload-artifact` 取 `@v5`（其自身最新 major 为 v7，v1–v3 已弃用）；已把核对结论写进 workflow 注释。

### 证据文档

`docs/evidence/M2-T1.2-E2E门禁实施-DS-V4-Flash.md`（含 §0 速览、§1 逐文件 × S1–S8 对账、§2 环境、§3 实测记录与 §7 验收十项逐条对账、§4 S5/S6 假设逐条【实测】/【推断】/【假设】、§5 workflow diff 与门禁行为、§6 S7 两轮缺陷注入、§7 R3 回退预案、§8 风险与【待裁决】、§9 留空的「裁决结论（指挥官 K3）」）。

所有由本棒启动的进程已停止（18330/18399 空闲），未触碰历史占用端口段。
