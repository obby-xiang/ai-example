# R-150 复核报告（复核方：GLM-5.3 · 厂商互异于施工方 K2.8）

- 实际运行模型：**Zhipu AI/glm-5.3**；CU 会话 id：`863d25b9-bab3-44d8-8edf-728f702d82c7`
- wire modelAlias 实证（DC-19）：本会话 main wire 39 条 `Zhipu AI/glm-5.3`，无他值
- 机械活下沉：3 名子代理均钉 `model=DeepSeek/deepseek-flash`（读卡+锚 / 零夹带 / 复跑）
- 输入核验：施工报告 6792B、施工卡 4489B、决策卡定稿 5027B、diff-anchor-r150.json 均读毕；`diff-r150-core.diff` sha256=`7c4df53c…03382`/19859B ✅

## 1. 独立复跑（全绿 ✅）

| 命令 | 结果 |
|---|---|
| yarn test | **19 spec / 225 用例全绿**（0 失败 0 跳过），与期望 225/225·+9 精确一致 |
| yarn typecheck | vue-tsc --noEmit 绿 |
| yarn build | 绿（26.4s；dist/ 8.4M 为构建产物，`.gitignore:17` 覆盖，不入版本控制） |
| node scripts/ci/check-docs.mjs | **6/6** PASS |

唯一噪音：task-center-view.spec 的 `v-loading` 指令未注册 warn，属存量警告、不涉本批。

## 2. 零夹带核验（PASS ✅）

- `git diff HEAD -- <两路径>` sha256 = `7c4df53c…03382`，与锚 **cmp 逐字节一致**；numstat 68/0 与 301/12 与锚吻合；复位后 porcelain 与核验前逐字节相同，索引零扰动。
- **HEAD 口径更新（两层过时，均不影响哈希证据）**：报告载 b1f8f22 → 复核卡载 921da76 → 实测 **9211adc**（其间 2 笔：a0042c5 docs(kanban)、9211adc fix(e2e)——后者已把 verify-e2e.ps1 热修**提交入库**，故该「在途面」现已消失）。中间提交不触及两目标文件，`diff HEAD` 与 `diff 921da76` 逐字节相同——哈希证据对漂移免疫。
- 当前工作树 = 2 M（两目标文件）+ `?? data/`（运行期 .xlsx，锚 A-1 已登记），**无意外第三面**。

## 3. 施工卡验收标准与决策卡八条逐项核（全部落地 ✅）

- **后置 pass 位置（GLM C1）**：hunk @@ -773,6 +773,74 @@ 钉在 R-149 配对+序位兜底+C1 重派生 forEach 之后，与卡定「兜底回填完成后再判」一致 ✅。
- **结构性闸（DSPro m-1+GLM C1 裁合）**：`hasPairableResponse` 按「id 相等；call.id 缺失时 name 相等」在原始 TOOL 窗口内判可配性，不看前端配对启发成败、不看 responseData 值——与卡文逐字吻合；无 toolCalls 恒真（纯文本可并，C3-1 用例锁定）✅。
- **保留空段 + seg 全局重排（DSPro m-2 裁合）**：`flatMap(parts).map((part,seg)=>({...part,seg}))` 空段经 spread 保留、seg 0..N 全局单调；②与真形状基准用例锁 `[0,1,2,3]`/`[0,1,2]` 及空段 `text===''` ✅。
- **兜底 toolCallId 用自身原始 index（DSPro O-1/GLM C4）**：flatMap 时钉 `history-{index}-{name}`，pass 不触碰；专测锁定 `history-1-alpha` 不漂首条 ✅。
- **游标对齐**：pass 按 history.messages 驱动、TOOL 跳过不断窗、每非 TOOL 条目 builtCursor++（含被 drop 与悬空条目），与 flatMap 产出序严格对齐——drop 正例 id `[history-0,history-2,history-3]` 空洞锁定 ✅。
- **镜像分支/实时路径零改动**：diff 单 hunk 仅插后端历史分支尾部；handleFrame/applyFrameToParts/镜像读分支未触 ✅（GLM C4 收窄面：仅 copy 对齐+两处 id 断言+⑦只比 parts——均在测试内消化）。
- **O-2 锚点重取**：报告载开工 b1f8f22 重取、关键锚零漂移 ✅。
- **「一轮一头像」降级 store 层条数锁**：②/基准/夹 USER 三用例锁 assistant 条数 ✅。

## 4. S0 结论复核（证据链闭合 ✅；附带登记裁定：维持登记、暂不立项）

四点证据（saveSuspended :354-369 只落 instructions、A+T 同批仅 settle :198-204、取消在 settle 前整跳不入史、resume 重建 A+T 同批 :296-327）与我本会话早前 R-150 会审/R-144 复核时独立取证的后端事实（setMessageJson 六写点穷举、cancelTerminal 按快照收敛、saveSuspended 字段化）**相互印证**，「悬空 A(T) 后端不可达、闸保留为纵深防御」成立。
**附带登记独立判断**：MessageWindowChatMemory 官方头部裁剪确为 convergeMemory 配对保护不覆盖的面，但 (a) convergeMemory 每轮 clear+add 整窗重写且 windowed≤40 不触发官方裁剪，暴露面仅限 advisor 增量写路径在两轮收敛之间的窗口；(b) 次轮收敛即自愈；(c) 触发需 >40 条长会话且裁点恰落 A/T 对之间且官方无配对保护三重条件。**裁定：登记合理，无需独立立项**，建议后续批（如 R-144 施工期）对 spring-ai 1.1.8 MessageWindowChatMemory 源做一次顺带实证即可。

## 5. 施工报告口径核查（无实质失真）

- 「约 +75 行」vs 实测 +68：锚 A-3 已记账（少记方向、无夹带含义）✅。
- 「HEAD 仍 b1f8f22」过时 + 「git status 仅 2 M」被并行热修线超越：前者系报告落盘后时间差，后者锚 A-2 已登记且热修现已提交自愈——**非施工方违约**。
- 基数 216→225（+9）、RED 10 红（3 迁移+7 新、悬空与夹 USER 两例旧实现形式通过——与用例设计自洽）、TC22/冒烟零改动（backend/scripts 未触，静态保持）：均与实测一致，无其他失真。

## 6. 测试设计质量（无假绿 ✅）

- 3 处迁移断言全部**改锁合并后形态**而非删断言放水：②锁 parts 形状+seg+双 result+content 拼接+条数；④锁双 `dup` 段共存（防撞号回归）；C3-1 锁合并 id=history-1。
- 新 describe 9 用例覆盖卡面全部行为维度，含两个关键负例（悬空不并不吞、drop 断窗）；「有 TOOL 回应但配对失败仍并」精确锁 GLM C1 判据（不看启发成败）。
- ⑦ 式镜像深度相等沿用 R-149 既有回环模式（镜像写→读→后端分支重建后比对），锁的是合并形态二次 loadHistory 的稳定性，与房屋口径一致。
- 一处**登记不扣分**的收窄：卡面「废 hybrid 夹具」经 agent-663 实证收窄为「保留 R-149 已双条目化的 5 处 hybrid（text 非空+toolCalls 同条目）」——该形态不掩盖任何合并行为分支（闸/drop/seg 均不以 text 是否为空分叉），真形状（A(T) 空 text 分立）由新基准用例专测，收窄有据且已在报告申报。

## 结论：**PASS**

八条件全落地、四命令全绿、零夹带双重确认、S0 证据链闭合且与本会话独立取证互证。登记三件随批：①hybrid 夹具收窄（已申报，接受）；②报告 HEAD/「仅2M」书面口径过时（锚已记，随 K3 汇总更正为 9211adc 现状）；③MessageWindowChatMemory 头部裁剪观察项维持登记、暂不立项（建议 R-144 期顺带实证）。

（复核全程未改仓库、未 commit；唯一索引操作为卡授权的 add -N/reset 程序，前后 porcelain 逐字节复原。）
