> 入库注记（2026-10-13）：来源落点 = 仓外工作目录 `<SCRATCH>/r156-batchdl/施工报告-R156.md`（5100 B）；源件 sha256 = `76a9ad31078243159e79288fb06d4648e7694bbc0e6395a834b4b194379dd148`；入库批次 = R-156 入库批（2026-10-13，单笔提交）；本批**审查锚 = 入库锚** = `diff-r156-final.patch` sha256 `409bb3f15cbeacffb52f808fd3070511fbb8dbb150ef3e7d45cbe093f45f12fe`（提交官实算与复核锚逐字节一致；复核后零代码改动、无重锚）；本件除本注记行（+1 行）、页首空行（+1 行）与**脱敏改写 1 处**外，正文与源件逐字节一致（源件与入库件行尾均 LF）。
> 脱敏改写留痕（1 处，最小化）：源件正文「仓库 …」行内的**盘符绝对路径**（形如 `<盘符>:\<目录A>\<目录B>\<检出目录>`，其中检出目录为本仓目录名——命名纪律禁则形态）→ `<REPO_ROOT>`（按 DC-08 占位符口径 + `docs/README.md` §命名纪律；语义与行内其余字符不变）。
# 施工报告-R156（issue #9 AI 批量下载 · 单工具版）

**自证（DC-19 首轮实测）**：自身 wire `profile.bind` 单行实证 **modelAlias=`DeepSeek/deepseek-flash`**（agentId `agent-833`、profileName `coder`、thinkingEffort `high`）；会话 id `session_f1eabc10-a0e5-424c-9ef6-532f9ad77d81`，wire=`…/session_f1eabc10-…/agents/agent-833/wire.jsonl` 首行即该条。
仓库 `<REPO_ROOT>`，基线 HEAD `984683e`。**git 零写**（无 add/commit）；净面 13 M + 1 新 spec，`?? data/` 未触；`git ls-files` 1100。

## 一、改动清单（+190/−71）

- `backend/…/ai/tools/AiTools.java`：删旧 `@Tool download_export_file`（原 :357-365）→ 新增 `download_export_files(taskId, defCodes String[] 必填非空)`（:357-377，FRONTEND 通道 + 哨兵桩，体例照 select_definitions），description 写明「1 码=直出 xlsx；≥2 码=打包单次 zip」；类注释 :41。`grep -c "@Tool("`=**18**。
- `frontend/src/types/tools.ts`：:23 工具表、:32 旧口径注、`ToolArgSpec.type` 增 `'string[]'`(:61)、联合类型 :79、`FRONTEND_TOOLS` 项 :96-105（**计数 2 不变**，args 改 `defCodes: string[]`）。
- `frontend/src/utils/frontend-tools.ts`：头注 :5/:9（7 / 2 计数不变）、`validateArgs` 增 `string[]` 支 :169-179（空数组=必填非空失败）、:385/:425/:448 注、蓝图注释 :421-433 改为"已登记于路由 1 + 换名留痕"、`DOWNLOAD_TOOL_NAMES`(:434)=`['download_export_files','download']`（**旧名移除**）。
- `frontend/src/views/ExportWizardView.vue`：注册 :1390 / 注销 :1446 换名；`preflightDownload` :1195-1234 逐码化（去重；无效码整体失败「无效码 X、Y；可用码清单 = …」）；**1 码**走既有单文件路径（三场景回灌**逐字沿用** :1397-1409）；**≥2 码**新增 `startBatchDownloadInBackground` :965-999（`buildXlsx`×N → `zipBlobs` 一次 → `downloadBlob` 单次，即发即回不 await）；哨兵 `AI_BATCH_DOWNLOADING='ai-batch'` :234-240。
- 注/展示名换名：`api/http.ts:164`、`AiPanel/AiPanel.vue:540`、`stores/ai.ts:2011`。
- 后端测试：`ToolRegistryDisclosureTest.java` :56/:237 名单换名 + :150 增新工具哨兵桩断言；`ConfirmGateFrontendTimeoutTextTest.java:219`、`AiControllerHistoryPayloadTest.java:68` 换名。
- `scripts/ci/stub-routes.json:17`（productTools 仍 18 项）。
- 前端 spec：`frontend-tool-outcome.spec.ts` T6 三例换名 + args 改 `defCodes:['DEMO']`，**新增**「空数组⇒参数校验期 unavailable（执行器零调用）」；`export-wizard-step-outcome.spec.ts` T7③ 同步；**新增** `tests/export-wizard-batch-download.spec.ts`（203 行）5 例 = ①1 码→单文件 xlsx（`DEMO.xlsx`、不打包）②2 码→字节取 2 次 / `zipBlobs` 1 次（条目 DEMO.xlsx+CURR.xlsx）/ `downloadBlob` 1 次（`export-7.zip`）③即发即回（字节永不结算仍回灌"已发起"）④无效码→整体失败含可用码清单且零下载 ⑤同笔重复投递→"本次未重复发起"且零新下载。

## 二、grep 核销（起手 `git grep -n "download_export_file\b"` 全仓）

- 代码/脚本/测试面**全部换名**；仅 `frontend-tools.ts:428` 保留旧名，位于换名说明注释「R-156 换名（旧 → 新）」内（仓库引旧名留痕体例）。
- `backend/target`、`frontend/dist` 命中=构建产物，不入核销面。
- **docs 未改（25 文件 61 处）**：`docs/evidence/**`（22 文件 58 处）=历史取证/上游报文原文（check-docs ①-④ 显式排除不回改）；`M2-排期计划.md`(4)/`任务看板.md`(3)/`决策看板.md`(1)=只追加台账（批 A/R-95 历史行与 R-156 自身登记文本），施工期不写台账；`requirements.md:108`=legacy 参考件区（勿当现行设计）的只追加注记；`research/…:153`=已裁决历史调研件。

## 三、门禁实测（全绿）

- 前端 `yarn test`：**265 / 265 passed**（基线 259 + 新增 6）；`yarn typecheck` exit 0（**0 错**）；`yarn build` exit 0（✓ 26.63s）。
- 后端 `mvn-run.sh -B test`：**Tests run: 492, Failures: 0, Errors: 0, Skipped: 0**，BUILD SUCCESS；surefire XML **77**（本轮 77 类；目录另存 1 个 10-12 陈旧 XML）。
- `node scripts/ci/check-docs.mjs`：**6/6**（ls-files 1100、实扫 1097、豁免 3）。

## 四、偏差留痕（非零）

1. **单码失败文案微改**（需求驱动）：旧 `缺少 defCode` / `noResultMessage…defCode` → 统一「无效码 X；可用码清单 = …」「缺少 defCodes（必填非空）…核对传入的 taskId 与 defCodes」。**1 码成功回灌三场景逐字未动**（`git diff` 逐字核对）。
2. `frontend-tools.ts:428` 保留一处旧名字面（见二）。
3. `ToolArgSpec.type` 扩 `'string[]'` + `validateArgs` 对应支（卡未列）：`defCodes` 是列表参数，不扩则契约层无法执行"必填非空"；全仓仅 `validateArgs` 消费（属最小必要扩面）。
4. 新增前端 spec 为**新文件**（未跟踪，`git ls-files` 仍 1100）；卡「≥4 例」已满足（5+1）。
