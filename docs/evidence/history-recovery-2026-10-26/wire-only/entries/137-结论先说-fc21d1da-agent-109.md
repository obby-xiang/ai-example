> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-109` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：9027 B / 6051 字符 / 105 行；行指纹覆盖率（仓内 + 落盘语料）6.9%
> - 类别：**过程核验**
> - 脱敏：路径 1 / 会话标识 0 / 身份 2 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 结论先说

失败是**单条用例 FAIL（TC8）**，不是 GF SKIP 零容忍（`GFSKIP=0`），也不是脚本崩溃。根因是 `scripts/verify-e2e.ps1` 的 **Windows 反斜杠路径拼接在 Linux/pwsh 7 下与 PowerShell cmdlet 的路径归一不一致**——脚本唯一一处 `Copy-Item` 成了全脚本唯一被打穿的点，且只在 Linux 上打穿。三次 E2E 运行（含本次）报错完全相同，属确定性缺陷，非 flaky。

---

## 一、取证路径（重要：匿名网页这条路走不通）

前提与任务描述不符，先记录实测：

- 匿名 curl 的日志端点**全部失败**：`/actions/runs/<run>/logs`→404、`/actions/runs/<run>/job/<job>/logs`→404、`/commit/<sha>/checks/<job>/logs`→404、`.../logs/12`→404；API `jobs/<id>/logs`→403（与任务前提一致）。
- 匿名打开 job 页（`mcp__desktop_browser__run`）时 DOM 里只有 **"Sign in to view logs"** —— 未登录浏览器拿不到日志正文，任务给的入口假设不成立。
- 该桌面浏览器工具随后返回 `BROWSER_UNAVAILABLE: Browser control requires a running turn`，中途不可用，改用 **`kimi-webbridge` skill（用户真实浏览器，已登录 GitHub）** 完成取证。
- 可行链路（可复现）：job 页渲染日志时请求
  `https://github.com/<USER>/ai-example/commit/56a24f8a07d9b93dc5abf0fba8170122e1ddeebd/checks/113234536314/logs/12`，
  该请求被 302/代理到 **Azure Blob 签名 URL**：
  `https://productionresultssa13.blob.core.windows.net/actions-results/…/workflow-job-run-…/logs/steps/step-logs-<guid>.txt?…sig=…`（带 SAS 读令牌，有效期至 `se=2026-10-08T09:24:25Z`）。**直接 curl 这个 blob URL 即可匿名取到 step 日志纯文本（HTTP 200，9697 字节，91 行）**。这是绕过登录墙的关键：每个 step 一个独立 blob，页面加载时会出现在 `performance.getEntriesByType('resource')` 里，也可用 webbridge 的 `network` 工具抓。
- 匿名仍可用的公开 API（未登录即可读，用于定位 job/step）：`/repos/<USER>/ai-example/actions/runs/<run>/jobs`（含每个 step 的 conclusion）、`/repos/…/check-runs/<job>/annotations`。

日志原文已落盘（未改任何仓库文件、零 git 写操作）：
- `<LOCAL_WORK>\gh-actions-log-37754062183\step12-run-37754062183-job-113234536314.txt`（清洗 ANSI，91 行）
- 同目录 `...raw.txt`（原始带 `##[group]`/`##[error]` 与 BOM）

---

## 二、关键日志原文摘录（step 12「运行 E2E（27 用例）」，job 113234536314）

命令头（证明运行环境是 Linux + pwsh 7）：

```
2026-10-08T09:05:59.7411765Z shell: /usr/bin/pwsh -command ". '{0}'"
2026-10-08T09:05:59.7412542Z   E2E_STUB_MODE: 1
2026-10-08T09:06:05.4282393Z  E2E 验证开始  base=http://127.0.0.1:18330  tmp=/tmp/s5b-e2e-164d69874e984f018d9ceb52518b25bd
```

失败点（也是全场唯一一条 `[FAIL]`）：

```
2026-10-08T09:06:16.1767253Z --- TC8 上传文件名匹配 ---
2026-10-08T09:06:16.2343848Z   [FAIL] TC8 => Cannot find path '/tmp/s5b-e2e-164d69874e984f018d9ceb52518b25bd/CURRENCY_full.xlsx' because it does not exist.
2026-10-08T09:06:16.2345225Z --- TC9 检查通过 + 依赖拓扑 ---
```

尾部（日志第 85–91 行，全文 91 行见落盘文件）：

```
 结果：PASS=26  FAIL=1  （22 既有 + GF 新增）
 GFSKIP=0  （桩模式零容忍：GFSKIP≥1 即整棒 FAIL（上游是确定性替身，任何 SKIP 都属桩路由或产品链路异常））
 Q8 项：PASS=2  FAIL=0  （不计入 22 用例，见 docs/evidence/S5b-deepseek-E2E移植验证.md）
============================================================
2026-10-08T09:06:46.5334173Z ##[error]Process completed with exit code 1.
```

无 `[SKIP] GF*` 行（GFSKIP=0）。TC1–TC7、TC9–TC22、GF1–GF5、Q8①② 全 PASS，其中 TC4/TC6/TC7 这类同样读写 `$tmp\…xlsx` 的用例**全部通过**——这正是问题的关键线索。

---

## 三、根因（证据链闭合）

事实 A（脚本侧）：`scripts/verify-e2e.ps1:417` 定义 `$tmp = Join-Path ([IO.Path]::GetTempPath()) ('s5b-e2e-' + [Guid]::NewGuid().ToString('N'))`，Linux 下即 `/tmp/s5b-e2e-<guid>`（无尾斜杠，与日志一致）。全脚本有 **35 行 / 36 处** 用反斜杠拼接该目录，例如
- `scripts/verify-e2e.ps1:633`　`[IO.File]::WriteAllBytes("$tmp\CURRENCY_full.xlsx", $bytes)`
- `scripts/verify-e2e.ps1:737`　`[IO.File]::ReadAllBytes("$tmp\CURRENCY_full.xlsx")`
- `scripts/verify-e2e.ps1:749`　`Copy-Item "$tmp\CURRENCY_full.xlsx" "$tmp\SOMETHING.xlsx" -Force`　← **全脚本唯一一处对 `$tmp` 使用 PowerShell cmdlet 的文件操作**

事实 B（.NET 侧）：在 Linux 上 `System.IO`（`File.WriteAllBytes/ReadAllBytes`、`ZipFile`）把 `\` 当**普通字符**。于是 TC4 那一次写盘实际创建的是 `/tmp/s5b-e2e-<guid>\CURRENCY_full.xlsx` —— 即 **/tmp 下一个名字里带反斜杠的兄弟文件，而不是 `$tmp` 目录内的文件**。因为后续所有读写都用同一字面量，TC4/TC5/TC6/TC7 以及 TC8(a)(b) 全都能读到它，所以一路 PASS（这也解释了为什么缺陷能藏这么久）。

事实 C（cmdlet 侧）：第 749 行的 `Copy-Item` 走 PowerShell provider 解析，路径被归一为 `/tmp/s5b-e2e-<guid>/CURRENCY_full.xlsx`，该文件不存在 → 抛 `Cannot find path … because it does not exist.` → 被 TC8 的 `catch { No 'TC8' $_.Exception.Message }` 打印为该 `[FAIL]` 行 → `$failed=1` → 脚本末尾 `if ($script:failed -gt 0 -or $script:gfskip14 -ge 4) { exit 1 }` → step 退出码 1。

闭环佐证：报错文本里的路径是**正斜杠**形式，而脚本里 `"$tmp/"` 出现次数为 **0**（grep 实证），因此该字符串只能来自 cmdlet 的归一；反过来，若 .NET 也认 `\` 为分隔符，或 cmdlet 不做归一，这条报错都不可能产生——两个方向的事实被同一行日志同时钉死。

仓库自证其假设错了：`docs/evidence/M2-T1.2-E2E门禁实施-DS-V4-Flash.md:404`（假设 #8）与 `docs/evidence/M2-E2E门禁设计草案-DS-V4-Flash.md:97` 都写着"反斜杠残留只是退化为文件名带反斜杠，不影响语义【假设/未实测】……`$tmp` 内所有读写/复制/打包路径同一写法、成对闭合"。本次 CI 实测**证伪**：那 35 行里的 `Copy-Item` 是例外，读写不成对闭合。

---

## 四、可复现性（三次运行同一失败）

| run | commit | E2E job | 结果 |
|---|---|---|---|
| 37753917432 | 7ad8e9e「feat(ci): E2E 进 CI 门禁」 | 113234089988 | FAIL，`[FAIL] TC8 => Cannot find path '/tmp/s5b-e2e-b798e24c…/CURRENCY_full.xlsx'…` |
| 37753934256 | a0e2c32（docs） | 113234140158 | FAIL，`[FAIL] TC8 => …/tmp/s5b-e2e-99bed054…/CURRENCY_full.xlsx'…` |
| 37754062183（目标） | 56a24f8（docs） | 113234536314 | FAIL，`[FAIL] TC8 => …/tmp/s5b-e2e-164d6987…/CURRENCY_full.xlsx'…` |

三次均为 step 12 失败、`PASS=26 FAIL=1 GFSKIP=0 Q8 2/0`、同一 TC8 报错（仅临时目录 GUID 不同）。E2E job 自 7ad8e9e 引入以来 **3/3 全红**；此前 run（如 37753903104）无该 job，故不是回归，是**首次进 Linux CI 就暴露的移植缺陷**。Windows 本机（PowerShell 5.1）跑同脚本 3 次全通，所以本地看不到（仓库文档同口径）。

其余 job（后端编译与单测 113234130775、前端类型检查与构建 113234130982）均 success；归档证据（step 13）success。annotations：`failure: Process completed with exit code 1.`（`.github` 第 91 行）、`warning: Node.js 20 is deprecated（actions/upload-artifact@v5 被强制跑在 Node 24）`、`notice: ubuntu-latest 将于 2026-10-19 迁到 Ubuntu 26`——warning/notice 与本次失败无关。

---

## 五、修复方向（未改动任何文件，仅建议）

只改第 749 行**不够**——因为真实文件不在 `$tmp` 内（在 `/tmp/s5b-e2e-<guid>\CURRENCY_full.xlsx`）。正确修法是把 35 行 `"$tmp\X"` 统一改成跨平台拼接，二选一：

1. `(Join-Path $tmp 'X')`（推荐，顺带让 `scripts/verify-e2e.ps1:371` 的 `Test-Path` / `Remove-Item` 恢复语义）；
2. `"$tmp$([IO.Path]::DirectorySeparatorChar)X"`。

或按设计草案 R3 预案回退 `windows-latest` runner（代价：放弃 `services:` 里的 redis 容器）。`$env:TEMP` 残留已为 0 处（417/422 行已改 `[IO.Path]::GetTempPath()`）。

---

## 六、边界与未做项

- 未修改任何仓库文件、未执行任何 git 写操作（仅用 `codeload` 下载 56a24f8 快照到 `/tmp` 读源码）。
- 修复未实施、未验证（不在本任务范围，也未获授权）。
- webbridge 会话 `gh-ci-log` 的标签页仍开在目标 job 页（会话结束后台仍在），需要时可让主 agent 调 `close_session` 关闭；网络抓包已 `stop`。
- 桌面浏览器工具（`mcp__desktop_browser__run`）在首次 `page.text.snapshot` 之后即报 `BROWSER_UNAVAILABLE`，未能继续使用；本条不影响结论（日志正文由 webbridge 链路取全）。
