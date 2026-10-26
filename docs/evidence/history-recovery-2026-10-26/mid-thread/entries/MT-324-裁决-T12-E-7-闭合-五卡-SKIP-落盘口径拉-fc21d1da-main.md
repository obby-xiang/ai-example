<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-324 · 裁决 · T12-E#7 闭合，五卡 SKIP 落盘口径拉齐，施工棒还主动修正了自己此前"字节数相等"的不可靠判据（改用归一化比对）——施工质量可信。至此 M2-T1.2 施工侧全部完成，无遗留【待裁决】。（fc21d1da / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`scripts/ci`、`ai-example-<OLD_REPO_DIR>`、`scripts/githooks`、`scan-lib`。
> **稿态**：历史中间稿 —— 该 wire 第 `m8` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt5` 条有正文的 assistant 消息（共 31 条有正文），末条有正文消息为 `m40`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `fc21d1da` / agent `main`（`main` = 主会话）；原字符 4,133（链内 1 段、合计 4,133）。
> **链号 / 类别**：`MT-324` / **裁决**；判定：助手裁决意见——**不构成用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `6%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `6%`。
> **与末条归档（138 件）的关系**：末条 `m40`（837 字符）未入末条归档。
> **脱敏**：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 2 处。

---

T12-E#7 闭合，五卡 SKIP 落盘口径拉齐，施工棒还主动修正了自己此前"字节数相等"的不可靠判据（改用归一化比对）——施工质量可信。至此 M2-T1.2 施工侧全部完成，无遗留【待裁决】。

**当前未提交改动（4 项，等红队+复核后统一入库）**

| 文件 | 改动 |
|---|---|
| `scripts/ci/stub-upstream.mjs` | 新增 626 行（零依赖桩 + `--self-check` 8 项） |
| `scripts/ci/stub-routes.json` | 新增 148 行（tools[] 清单驱动） |
| `scripts/verify-e2e.ps1` | +9/−6（$env:TEMP 兜底 2 行 + E2E_STUB_MODE 零容忍 2 行 + 五卡 SKIP 落盘 5 处） |
| `.github/workflows/ci.yml` | +133/−1（e2e 门禁 job：needs backend、PR+push 阻塞、artifact 7 天） |

按纪律，作者为 DS 系，红队与独立复现均派 **GLM-5.3**。以下两张卡可直接转发，建议并行开两个对话跑（省轮次）：

**任务卡一：红队审查（GLM-5.3）**

```text
你是 GLM-5.3，担任 ai-example 合流项目 M2-T1.2「E2E 进 CI 门禁」的红队（推翻式审查）。
作者是 DeepSeek 系，你的职责是证伪，不是验收：默认设计有洞，找不到洞要说明排查过哪些攻击面。

【仓库】<REPO>（分支 main，HEAD=7045c06 + 未提交改动）。
【审查对象（全部未提交，git status 可见）】
1. scripts/ci/stub-upstream.mjs（新增 626 行，零依赖桩上游，含 --self-check）
2. scripts/ci/stub-routes.json（新增 148 行，路由表）
3. scripts/verify-e2e.ps1（diff +9/−6：$env:TEMP 兜底 2 行、E2E_STUB_MODE=1 时 GFSKIP≥1 即 exit 1 分支 2 行、GF1–GF5 五处 SKIP 分支补 Gf-Dump）
4. .github/workflows/ci.yml（diff +133/−1：新增 e2e 门禁 job）
5. 证据文档 docs/evidence/M2-T1.2-E2E门禁实施-DS-V4-Flash.md（含 §8/§9 裁决留痕，先读它建立上下文）

【设计基线】docs/evidence/M2-E2E门禁设计草案-DS-V4-Flash.md（10 项裁决是已定边界，审查实现是否忠实，不重新裁决设计本身）。

【必查攻击面（不限于此）】
a. 桩：模板/路由是否真无 __proto__/constructor/prototype 键；路由是否真由 tools[] 披露清单驱动（对照 backend 的 AiTools 工具定义逐签名对账）；--self-check 8 项是否覆盖关键失效模式，有没有"自检过但桩仍错"的盲区；JSON 解析/并发连接/端口占用/异常输入下的行为；桩自证收敛（后端无迭代上限时桩能否终止）声明是否属实。
b. 门禁语义：E2E_STUB_MODE=1 分支是否真的只在桩模式生效、真 key 本地路径语义是否一字未变；GFSKIP≥1→exit 1 是否存在绕过路径（如脚本在 gfskip 统计前崩溃时退出码是什么、SKIP 统计本身是否可能漏计）；gfskip14>=4 旧底线与新分支的相互作用。
c. workflow：e2e job 的 needs 链、PR+push 双触发阻塞是否真正成立；artifact 保留 7 天配置正确性；actions/upload-artifact@v5 版本是否真实存在且可用（核对项 T12-E#6）；services: redis:7 配置正确性；有无 secrets 泄漏面（设计为零密钥）；workflow 语法与 GitHub Actions 语义错误。
d. verify-e2e.ps1 两处 $env:TEMP→[IO.Path]::GetTempPath() 改写在 PowerShell 5.1/7 下的等价性与反斜杠路径退化风险。
e. 证据文档中的实测结论与代码实际行为有无出入（抽查即可，全量复现有另一张卡负责）。

【纪律】只读+可运行验证（起桩/跑脚本可以，端口从 18330 起、启动前 netstat、只停自己起的进程）；零 git 写操作（不 add/commit/push/stash）；不修改任何被审文件；AI key 禁止写入任何文件。
【产出】把审查结论直接写入 docs/evidence/M2-T1.2-红队审查-GLM-5.3.md（保持未提交）：问题清单逐条编号，标注严重级（阻断/严重/建议）+ 证据（文件:行号/复现步骤）+ 每条【实测】/【推断】/【假设】；无问题的攻击面也要列出并说明排查方法。文末留空「裁决结论（指挥官 K3）」一节。
【脱敏】文档中路径用 <REPO_ROOT> 占位、key 用 ***；不得出现盘符路径样式字面量。
【汇报】完成后回复：问题总数、阻断/严重/建议各几条、最关键的 3 条摘要。
```

**任务卡二：独立复现验证（GLM-5.3）**

```text
你是 GLM-5.3，担任 ai-example 合流项目 M2-T1.2「E2E 进 CI 门禁」的独立复现验证员（Verifier）。
作者（DeepSeek 系）声称的实测结论必须由你独立复现才成立。不要信任证据文档里的数字，全部自己跑。

【仓库】<REPO>（分支 main，HEAD=7045c06 + 未提交改动）。
【环境】JDK 21 在 PATH；Maven 全路径 "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"；node v22 / yarn 1.22；Memurai（Redis 兼容）127.0.0.1:6379。端口纪律：18080/18290-18299/18301-18321 历史占用段禁用，从 18330 起分配，启动前 netstat -ano 确认空闲，只停自己启动的进程。AI key 禁止写入任何文件（本任务不需要真 key）。

【待复现结论清单（逐条复现，逐条判定 成立/不成立/部分成立）】
1. 桩自检：node scripts/ci/stub-upstream.mjs --self-check ⇒ 8 项全过。
2. 干净跑：按 docs/evidence/M2-T1.2-E2E门禁实施-DS-V4-Flash.md §3 记录的起桩+跑法（设 E2E_STUB_MODE=1）⇒ PASS=27 FAIL=0、GFSKIP=0、Q8 2/2、退出码 0，耗时 40–60s 区间。跑 2 次确认稳定。
3. 违规变红：桩加 --violate-form-type ⇒ 整棒退出码 1（红），且 GF1/GF3/GF4/GF5 SKIP 用例均落盘 gfc-GF*.{http,sse}.txt。
4. 真 key 路径语义不变：同一违规桩但不设 E2E_STUB_MODE ⇒ 用例结论相同但退出码 0。
5. 工作区口径：git status --porcelain 恰好 4 项（2 新 2 改）；verify-e2e.ps1 累计 diff +9/−6、ci.yml +133/−1（用 git diff --stat 核对，只读）。
6. 脱敏：用仓库 scripts/githooks/scan-lib.sh 扫 5 个产出文件（stub-upstream.mjs、stub-routes.json、verify-e2e.ps1、ci.yml、证据文档）⇒ SECRET/PATH/USER 零命中。
7. YAML 有效性：解析 .github/workflows/ci.yml，确认 3 个 job、e2e needs backend、artifact retention-days=7、env 含 E2E_STUB_MODE。
8. 桩行为抽查：干净跑期间桩收到约 23 次上游调用（看桩日志/decision.json 计数）；后端 /health 报 model=ci-stub。

【纪律】零 git 写操作；不改任何仓库文件（临时路由表/日志等放仓库外临时目录）；保持所有产出未提交。
【产出】结论直接写入 docs/evidence/M2-T1.2-独立复现-GLM-5.3.md（保持未提交）：8 条逐条 成立/不成立/部分成立 + 你的原始实测输出摘要 + 与作者声称值的偏差；每条标【实测】/【推断】。文末留空「裁决结论（指挥官 K3）」一节。
【脱敏】路径用 <REPO_ROOT> 占位、key 用 ***；不得出现盘符路径样式字面量。
【汇报】完成后回复：8 条中成立几条、不成立/部分成立的明细、总评一句话。
```

**流程预告**：两卡回报后，我逐条处置红队问题清单（修复或显式驳回）+ 核对复现结论；全部闭合后派提交官（DS Flash）做机械验收、脱敏复查、细分提交（预计 3 个 commit：桩+路由表 / 脚本补丁+workflow / 证据文档）并 push，随后匿名轮询 CI 首跑门禁实测（T12-E#3 的验证点，失败按 R3 回退）。
