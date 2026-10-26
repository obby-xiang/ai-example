> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-429` 的 wire 末条 assistant 文本（消息序号 m=27；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-11 13:55 → 2026-10-11 13:58（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：8294 B / 5851 字符 / 102 行；行指纹覆盖率（本批复算）14.3%（最高单文件 7%：`docs/evidence/history-recovery-2026-10-26/wire-only/entries/013-起始-git-status-porcelain-快照-16a39d55-agent-286.md`）
> - 类别：**施工复核**
> - 与既有件关系：build-report；报告口径覆盖率 0.07
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
S3c + S3d 施工完成，两文件（视图）与两共享组件已改，`typecheck` + `build` 在**冻结后的最终态**上双绿。

## 1. 基线与改动面

起始 `git status --porcelain`：`M TaskWizard.vue`、`M ExportWizardView.vue`、`M ImportWizardView.vue`（前序 S1a/S1b/S2/S3a/S3b 在制品，未回退、未覆盖）；本步新增 `M DefSelector.vue`、`M ConditionForm.vue`。全程无 git 写操作，未动 `TaskWizard.vue` 与 `ExportWizardView` 的 onFinal 任务快照刷新。

`git diff --numstat`（4 文件，含前序步骤增量）：

```
13	7	frontend/src/components/wizard/ConditionForm.vue
7	4	frontend/src/components/wizard/DefSelector.vue
102	7	frontend/src/views/ExportWizardView.vue
78	16	frontend/src/views/ImportWizardView.vue
```

## 2. S3c 逐项处置清单（行号=完工后现态，均以 Grep 定位后复读）

**`frontend/src/views/ImportWizardView.vue`**

| 元素 | 现行号 | 处置 | 实施 |
|---|---|---|---|
| 下载模板 | `:37` | 隐藏 | `v-if="!isReadOnly"`（该按钮前置 `persistSelection()`=POST select-defs，`:541` 实证，不能当纯读放过） |
| 上传 xlsx/zip | `:40-50`（`v-if` 在 `:41`） | 隐藏 | `<el-upload v-if="!isReadOnly">` 整块隐藏 |
| 保存草稿到服务端 | `:52` | 隐藏 | `v-if="!isReadOnly"`（`PUT files`，`:670`） |
| 启动预检查 | `:85` | 隐藏 | `v-if="!isReadOnly"` |
| 启动导入 | `:118` | 隐藏 | `v-if="!isReadOnly"` |
| 确认发布 | `:178` | 隐藏 | `v-if="!isReadOnly"` |
| 导入模式 radio | `:139` | 禁用 | `:disabled="isReadOnly"`（`@change="onImportModeChange"` → `PUT import-mode` `:976` 变为不可达；布局与可见状态保留） |
| 暂存「刷新」 | `:143` | 保留 | 未改（`GET /jobs/{id}/diff` 纯读） |
| 取消作业 | `:95` `:128` `:202` | 未改 | 遵卡；见 §5 残留风险 |

**`frontend/src/views/ExportWizardView.vue`**

| 元素 | 现行号 | 处置 | 实施 |
|---|---|---|---|
| 预览行数 | `:46` | 隐藏（口径选择，登记） | `v-if="!isReadOnly"`；`命中 N 行` `el-tag :47` 保留 |
| 开始导出 / 重新导出 | `:62` | 隐藏 | `v-if="!isReadOnly"` |
| 保存编辑到服务端 | `:74` | 隐藏 | `v-if="!isReadOnly"`（`PUT files` `:976`） |
| 下载当前项 xlsx | `:67` | **保留** | 未改（纯读） |
| 打包下载勾选 | `:70` | **保留** | 未改 |
| 全部打包 zip | `:73` | **保留** | 未改 |
| 勾选 checkbox | `:94-98` | **保留** | 未改 |
| 条件表单 | `:50-54`（`:disabled` 在 `:53`） | 控件只读 | `:disabled="isReadOnly"` |
| 取消作业 | `:83` | 未改 | 同导入 |

写侧 API 调用点全部映射完毕（Import `:525/:567/:670/:759/:788/:934/:976`；Export `:587/:608/:615/:671/:741/:976/:1155`）：前者六项+radio 已由本步封闭 UI 入口，`cancelJob` 遵卡不改，`Export:1155`（AI handler）属 S3e。

## 3. S3d 只读控件化（prop 合成式原文）

**`<SpreadGrid>`**：两处均传 `:read-only="isReadOnly"`（Import `:71`、Export `:109`，另 `TaskWizard` 上的 `:read-only` 见 `:8`）。实读确认组件侧能力为真：`SpreadGrid.vue:216-221 applyReadOnly() → sheet.options.isProtected = props.readOnly`，在 `:165/:248/:291` 各调一次（引擎就绪、载入后各一次）。

**`DefSelector.vue`**（新增 `disabled?: boolean`，默认 `false`）：

```
<el-table-column type="selection" width="48" :selectable="() => !props.disabled" />   // :21
<el-radio-group v-model="levelFilter" size="small" :disabled="props.disabled">        // :4
<el-input v-model="keyword" size="small" ... class="w-[220px]" :disabled="props.disabled" />  // :10
```
两视图传 `:disabled="isReadOnly"`（Import `:34`、Export `:33`）。

**`ConditionForm.vue`**（新增 `disabled?: boolean`，默认 `false`）——既有 disabled 条件一律**或上**、不覆盖：

```
:disabled="props.disabled"                                   // 字段 el-select :6、操作符 el-select :17
:disabled="props.disabled"                                   // ENUM el-select :28、BOOLEAN el-select :38
:disabled="props.disabled"                                   // el-date-picker :46
:disabled="props.disabled || !operatorNeedsValue(row.operator)"   // el-input（原 :54 条件保留为右操作数）
:disabled="props.disabled"                                   // BETWEEN el-input :65
:disabled="props.disabled"                                   // 删除按钮 :72
:disabled="props.disabled || fields.length === 0"            // 「添加条件」（原条件保留为右操作数）:76
```
未使用 `<fieldset disabled>` 方案。`DataBrowserView.vue:55` 的 `<ConditionForm>` 未传新 prop（默认 false，行为不变）。

## 4. 验证（原始输出尾部，跑在最终冻结态上）

```
$ yarn typecheck
yarn run v1.22.22
$ vue-tsc --noEmit
Done in 4.01s.
```
```
$ yarn build
$ vue-tsc --noEmit && node scripts/build.mjs
vite v6.4.4 building for production...
✓ 1747 modules transformed.
dist/assets/ImportWizardView-Dd6gmFze.js      20.75 kB │ gzip:     7.77 kB
dist/assets/ExportWizardView-D0Rc2AR3.js      20.88 kB │ gzip:     7.92 kB
✓ built in 25.59s
Done in 30.09s.
```
（`yarn build` 首次与冻结态复跑各一次，均绿；构建产物 `dist/` 未被 git 跟踪，`git status` 仅 5 个 `M`，无新增未跟踪文件。）

## 5. 适配判断 / 差异与残留风险登记

1. **行号漂移（必读）**：卡 §2.3 的"模板区行号与卡一致"前提**不成立**。前序步骤（S3a）在 `<TaskWizard>` 标签上新增了 4 行（`:step-statuses` `:read-only` `:show-prev/:show-next` `@step-click`），两视图模板区整体后移 4 行（卡 Import `:33-35`→实际 `:37`、`:47-49`→`:52`、`:79-81`→`:85`、`:112-114`→`:118`、`:172-174`→`:178`、`:133-136`→`:139`、`:137`→`:143`、SpreadGrid `:63-69`→`:68-75`；Export `:42`→`:46`、`:57-59`→`:62`、`:69`→`:74`、`:62-68`→`:67/:70/:73`、`:89-93`→`:94-98`、`:46-50`→`:50-54`、`:101-107`→`:106-113`）。**所有锚点形态与卡描述一致，仅行号偏移，无形态不符项**；实施全程以 Grep 元素定位。
2. **未做的"自然排除"项**：`Import:29` / `Export:29` 的「创建任务并进入」（`createAndBindTask`，POST 建任务）未加 `v-if`——它被外层 `el-alert v-if="!taskId"` 包着，而 `isReadOnly` 依赖 `task.value` 存在（`:339`/`:264`），两者互斥 ⇒ 只读态下必然不渲染。登记为逻辑排除，未超范围改动。
3. **取消作业残留风险**：实读 `JobProgress.vue:8 v-if="running"`，`running = job !== null && ['PENDING','RUNNING'].includes(job.status)`（`:103`）。只读态判据使该类型**最终作业**为 COMPLETED，但若任务为 `CANCELLED` 而某作业仍为 PENDING/RUNNING（异常/竞态），「取消作业」仍会显示——按卡口径"不改，仅实读确认"处理，登记。
4. **预览行数隐藏属口径选择**（卡已要求登记）：它是纯读 `GET /data/{defCode}/count`；隐藏后只读态失去"命中 N 行"的唯一生成入口（`el-tag` 保留但不再有新值；只读态 `counts` 本就不持久化，与卡 §6-⑤ 同源）。
5. **只读态残留文案（显示层瑕疵，非写入口）**：Import 第 1 步在只读且未选中时仍会显示 `el-empty` 的"请先勾选配置项，再下载模板 / 上传文件 / 在线编辑"（`:78`）与说明 span（`:54-56`），指向已隐藏的动作。不在卡清单内，为避免超范围未改，登记。
6. **本步未覆盖、留给 S3e 的面**：只读态下 AI 通道仍可写——Export `PAGE_HANDLER_OPEN_EXPORT_EDITOR` / `preflightDownload` / `export.nextStep`，Import `import.nextStep/selectDefs/startPrecheck/startImport/startPublish`，以及 `Export:1155` 的 `setCondition`。本步只封 UI 入口，不做 S3e。
7. **未验证项（明确说明）**：未跑 §4.D 的运行时断言（Network 零写、`version` 前后比对、只读表格敲键盘改不进、只读态点选不改变选中集）——本棒无 dev server/后端会话，父级要求的验证口径为 typecheck+build 双绿；`isProtected` 生效、`DefSelector` 勾选被拦、`ConditionForm` disabled 只做了静态确认（读代码 + 类型检查 + 构建）。运行期证据需 §4.D 的 CDP 脚本或手测补齐。
