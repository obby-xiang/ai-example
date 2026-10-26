> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-491` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：14637 B / 10067 字符 / 232 行；行指纹覆盖率（仓内 + 落盘语料）1.8%
> - 类别：**施工复核**
> - 脱敏：路径 1 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# 只读核验报告（HEAD `3edaeb9`，仓库 `<REPO_ROOT>`）

## 1. `frontend/src/views/ExportWizardView.vue`

**1.1 `goStep` —— 【确认】**（行号完全吻合 :545-554）

```ts
545  function goStep(next: number): void {
546    if (next < 0 || next >= STEP_KEYS.length) {
547      return
548    }
549    stepIndex.value = next
550    workspace.setStep(STEP_KEYS[next] ?? null)
551    void persistStepData()
552    void syncStepToBackend()
553    void ensureStepLoaded(next)
554  }
```
签名 `function goStep(next: number): void` 确认；:551/:552/:553 三处 `void` 发后不管 确认。

**1.2 `viewStep` 注释 —— 【需修正（行号区间）】**
草案给 :556-565，实际注释块为 :556-563、函数体 :564-571。注释原文：

```ts
556  /**
557   * 只读态点节点查看（施工卡 §S3b）：只切本地步骤 + 补齐该步数据，**零服务端写入**。
558   *
559   * 与 `goStep` 的分工（终案"点节点 = 查看，不是编辑"）：不调 `persistStepData`（不落库选中集与条件）、
560   * 不调 `workspace.setStep`（不把浏览动作写进 AI 渐进披露集）、不调 `syncStepToBackend`（不发 `PUT /step`）
561   * —— 刷新后浏览位置仍以后端 `currentStep` 为准。
562   * 第 3 步的标签懒加载仍走 `onTabChange → ensureGridLoaded`（`ensureStepLoaded(2)` 只负责刷结果与首个标签）。
563   */
564  async function viewStep(index: number): Promise<void> {
```
「零服务端写入 / 零写」自述成立；但**「不阻塞界面」不在本注释里**（该措辞只出现在 :590 的 catch 注释）。签名是 `async ...: Promise<void>`（非草案暗示的同步）。

**1.3 `syncStepToBackend` —— 【确认】**（:579-592 逐字吻合，注释在 :590）

```ts
579  async function syncStepToBackend(): Promise<void> {
580    if (taskId.value === null) {
581      return
582    }
583    const step = STEP_KEYS[stepIndex.value]
584    if (!step) {
585      return
586    }
587    try {
588      task.value = await tasksApi.goToStep(taskId.value, step)
589    } catch {
590      // 步骤同步失败不阻塞界面操作（下次切步会重试）
591    }
592  }
```
catch 体为空（仅注释）确认；注释自述「不阻塞界面操作（下次切步会重试）」逐个字符吻合。

**1.4 `ensureGridLoaded` 结构化 / `throw` —— 【确认，但草案行号错位】**
- `ensureGridLoaded` 定义实际在 **:776-804**（草案 E14 给 :1207-1240，那是**消费它的 handler** 的位段）。
- 它返回结构化结局 `Promise<{ ok: boolean; error: string }>`：早退分支 :778/:784/:788，成功 :797，失败 :798-803：

```ts
798    } catch (error) {
799      loadedGrids.delete(code)
800      const reason = error instanceof Error ? error.message : `${code} 结果文件加载失败`
801      ElMessage.error(reason)
802      return { ok: false, error: reason }
803    }
```
- 消费 handler `PAGE_HANDLER_OPEN_EXPORT_EDITOR` 在 **:1207-1241**，两处已改为 `throw`：:1215 `throw new Error(\`${gate.message}。${defCode} 的在线编辑器未打开\`)`、:1218 `throw new Error(\`${noResultMessage(defCode)}。…；本次未打开在线编辑器\`)`。
- **:1236 文案**（`loaded.ok === false` 时）：

```ts
1235    if (!loaded.ok) {
1236      return `${defCode} 的在线编辑器表格未就绪（任务 #${taskId.value}）：${loaded.error}。`
1237        + '导出结果步骤与该配置标签已切到前台，但表格位置是加载错误提示，没有可编辑的数据；'
1238        + '请提示用户核对网络后重试，在此之前不要假设编辑器可用。'
1239    }
```
- 草案提到的 :1259 `throw` 对应下载执行器的前置校验失败：`throw new Error(preflight.message)`（:1258-1260）。

**1.5 registerPageHandler 注册点 —— 【确认】**（5 点与草案一致）
| 行 | handler 名 | 用途 |
|---|---|---|
| :1136 | `'export.nextStep'` | 推进/跳步（:1146 `goStep(index)`；:1138 只读态拦截说"不能推进步骤"） |
| :1150 | `'export.selectDefs'` | 设置所选配置项（:1153 只读态拦截；:1163 `syncSelectedDefsToWorkspace('AI')`） |
| :1167 | `'export.setConditions'` | 设置查询条件并落库（:1169 只读态拦截；:1187-1195 try/catch，**失败时如实回灌「但落库失败：<reason>」**，非空吞） |
| :1198 | `'export.start'` | 启动导出作业（:1200 只读态拦截；:1204 未启动时回"导出未启动"） |
| :1207 | `PAGE_HANDLER_OPEN_EXPORT_EDITOR` = `'export.openEditor'`（常量定义于 `frontend/src/utils/frontend-tools.ts:72`） | 打开在线编辑器（切页 + `ensureGridLoaded` + :1235 未就绪如实回灌） |

**1.6 `download_export_file`（:1256-1277）—— 【确认】** 三场景全是「在途/未发起」说明，**无「失败」字样**、显式禁止失败暗示：
- :1264-1268 在途就是本次编码 ⇒「页面上已有一次下载在进行中…本次未重复发起…无需重试」；
- :1269-1272 在途是别的编码 ⇒「本次未发起 ${defCode} 的下载…可稍后重试本项」；
- :1273-1276 新发起 ⇒「已发起下载…本次调用到此结束，无需重试」。
唯一「失败」语义走前置校验 `throw`（:1258-1260）。注释 :1251-1255 明确写「**禁止失败暗示**（不得读作「下载成功」或「下载失败」）」。

## 2. `frontend/src/views/ImportWizardView.vue`

**2.1 `goStep` —— 【确认】**（:714-730，签名 `async function goStep(next: number): Promise<boolean>`）

```ts
714  async function goStep(next: number): Promise<boolean> {
715    if (next < 0 || next >= STEP_KEYS.length) {
716      return false
717    }
718    if (next > stepIndex.value && stepIndex.value === 0) {
719      const saved = await flushEditors()
720      if (!saved) {
721        ElMessage.warning('存在未写回的编辑内容，请先修正后再继续')
722        return false
723      }
724    }
725    stepIndex.value = next
726    workspace.setStep(STEP_KEYS[next] ?? null)
727    await syncStepToBackend()
728    await ensureStepLoaded(next)
729    return true
730  }
```
注释 :706-713 明确「**F2（DC-14）：本方法必须把结局回给调用方**」。方法内 `await syncStepToBackend()`（:727，不 `void`）与导出侧 `void syncStepToBackend()`（:552）形式不同。

**2.2 调用方分叉 —— 【确认】**（:1018-1025，与草案一致）

```ts
1016    // F2（DC-14）：回灌文本必须与真实结局一致 —— 未推进就如实说"未推进 + 为什么"，
1017    // 否则模型会基于假的成功语义继续往下走（例如直接 start_import，而用户还停在第一步）。
1018    const advanced = await goStep(index)
1019    if (!advanced) {
1020      return `步骤未推进：当前仍在「${stepLabel(STEP_KEYS[stepIndex.value] ?? '')}」。`
1021        + '第 1 步离开前会把在线编辑内容写回服务端文件，存在本地校验问题或保存失败时会中止。'
1022        + '请提示用户修正编辑区数据（或先点「保存草稿到服务端」）后重试。'
1023    }
1024    return `已推进到导入向导步骤「${stepLabel(STEP_KEYS[index])}」`
```

**2.3 `syncStepToBackend`（:753-766）—— 【确认：与导出侧逐字同款】**

```ts
753  async function syncStepToBackend(): Promise<void> {
754    if (taskId.value === null) {
755      return
756    }
757    const step = STEP_KEYS[stepIndex.value]
758    if (!step) {
759      return
760    }
761    try {
762      task.value = await tasksApi.goToStep(taskId.value, step)
763    } catch {
764      // 步骤同步失败不阻塞界面操作（下次切步会重试）
765    }
766  }
```
与 `ExportWizardView.vue:579-592` **逐字节同形**（含注释与缩进）。

**2.4 registerPageHandler 注册点 —— 【确认】**（6 点与草案一致）：`:1006 import.nextStep`（只读拦截 + F2 分叉）、`:1027 import.selectDefs`、`:1041 import.upload`（只读回灌"上传入口不可用，本次未执行上传"）、`:1050 import.startPrecheck`、`:1059 import.startImport`、`:1068 import.startPublish`。

## 3. `frontend/src/components/AiPanel/AiPanel.vue`

**3.1 `toolTagType`（:537-561）—— 【需修正】九态**未**全部显式覆盖**

```ts
537  function toolTagType(status: ToolRunStatus): 'info' | 'warning' | 'success' | 'danger' | 'primary' {
538    switch (status) {
539      case 'running':
540      case 'succeeded':
541        return 'success'
542      case 'failed':
543        return 'danger'
544      case 'rejected':
545      // DC-14 T1：被防线③拦下（越 scope 未执行）与"人拒绝"同色（warning）——
546      // 两者都是"没有执行"，与后端执行失败（danger）语义不同
547      case 'blocked':
548        return 'warning'
549      case 'expired':
550      // 对话时序包 批1：取消终态与"会话已失效"同族（info）—— 都不是执行成功/失败，
551      // 只是这一轮没走完（用户取消 / 渲染器不可用 / 整轮取消）
552      case 'cancelled':
553        return 'info'
554      case 'unknown':
555      // 对话时序包 批1：结局未知（未确认成功/失败，如前端执行器超时）—— 提示级 warning，
556      // 与"执行失败/已拒绝"同色的待人工判断，不落 danger
557        return 'warning'
558      default:
559        return 'primary'
560    }
561  }
```
`ToolRunStatus` 九态为 `'pending' | 'running' | 'succeeded' | 'failed' | 'rejected' | 'expired' | 'blocked' | 'unknown' | 'cancelled'`（`frontend/src/types/ai.ts:165`）。函数显式 `case` 覆盖 **8** 态；**`pending` 无显式 case，靠 `default: return 'primary'`（:558-559）兜底** —— 行为正确（等待确认=primary），但「九态已全部显式覆盖」的说法不成立，应表述为「八态显式 + `pending` 走 default」。:549-557 确实显式落了 `expired`/`cancelled`/`unknown`（确认）。

**3.2 `toolStatusLabel`（:563-579）—— 【确认】** `Record<ToolRunStatus, string>` 九键全覆盖（`pending` 在 :566），无遗漏键，TS 下不存在 `undefined` 返回路径。

## 4. 空 catch{} 全量扫描（D9 完整性）

扫描范围 `frontend/src/**/*.{ts,vue,js}`（脚本按大括号配对求 body、剥注释后判空）。**"真·空吞"共 18 处，全部为"仅注释"型；不存在 body 完全空白（零内容）的真空 `catch {}`**；`.catch(() => {})` 空箭头 0 处。

**4.1 两个向导文件内（8 处）**
| 文件:行 | 所属函数 | catch 体 |
|---|---|---|
| `ExportWizardView.vue:413` | `resumeJobs()` | `// 问题清单拉取失败不阻断主流程（同导入页 refreshIssues 的兜底口径）` |
| `ExportWizardView.vue:589` | `syncStepToBackend()` | `// 步骤同步失败不阻塞界面操作（下次切步会重试）` |
| `ExportWizardView.vue:723` | `beginWatchJob(...)` 的 `onFinal` 回调 | `// 任务快照刷新失败不阻断作业结果的展示（同导入页 refreshTaskSnapshot 口径）` |
| `ImportWizardView.vue:477` | `loadUploadedFiles()` | `// 文件列表失败不影响向导` |
| `ImportWizardView.vue:763` | `syncStepToBackend()` | `// 步骤同步失败不阻塞界面操作（下次切步会重试）` |
| `ImportWizardView.vue:875` | `refreshTaskSnapshot()` | `// 任务快照刷新失败不阻断作业结果的展示` |
| `ImportWizardView.vue:915` | `refreshIssues()` | `// 问题清单拉取失败不阻断主流程` |

⇒ **D9 遗漏面确认**：除 `syncStepToBackend` 一对（Export:589 / Import:763，逐字同形）外，这两文件还有 **5 处** 仅注释 catch：`Export:413`、`Export:723`、`Import:477`、`Import:875`、`Import:915`。它们均属"非关键路径降级"（无回灌义务，因为不在 AI handler 的成败语义链上），危害低于 `syncStepToBackend` 那对；但若 D9 判据是"catch 体为空即算"，则这两文件的同族面共 **7 处**，草案只提 1 处。

**4.2 `frontend/src` 全仓其余仅注释 catch（10 处）**
| 文件:行 | 所属函数 | catch 体 |
|---|---|---|
| `api/sse.ts:314` | `readSseStream` 的 `finally` | `// releaseLock 在已关闭的流上可能抛错，忽略` |
| `api/tasks.ts:110` | `openTaskEvents` 的 `onmessage` | `// 非 JSON 帧（心跳注释等）忽略` |
| `components/SpreadGrid.vue:206` | `onBeforeUnmount` 销毁 | `// 销毁失败无需上抛（宿主即将移除）` |
| `utils/excel.ts:432` | `createHiddenWorkbook().dispose` | `// 销毁失败无需上抛（宿主即将移除）` |
| `stores/ai.ts:72` | `createSessionId()` | `// 落回退实现` |
| `stores/ai.ts:140` | `persistExpanded()` | `// 隐私模式忽略：展开态只是展示偏好` |
| `stores/ai.ts:294` | 会话镜像落 `sessionStorage` | `// 配额/隐私模式等场景忽略：镜像是刷新体验优化，不是正确性依赖` |
| `stores/ai.ts:1321` | `run.stop()`（`currentAbort?.abort()`） | `// 忽略：中止失败不影响后续取消请求` |
| `stores/ai.ts:1369` | `resetSession()`（abort） | `// 忽略：中止失败不影响重置` |
| `stores/ai.ts:1382` | `resetSession()`（sessionStorage 换 id） | `// 隐私模式/配额：持久化失败不阻断本次重置（本页签内照常换 id）` |
| `stores/ai.ts:1402` | `resetSession()`（`aiApi.cancel(oldRunId)`） | `// 忽略：取消请求失败只意味着后端可能多跑一轮，不影响新建对话` |

**4.3 静默吞的"变体族"（非 `catch{}` 文法，但同样丢弃 error，供判断同族面）**
- `ExportWizardView.vue:401` / `ImportWizardView.vue:487`：`await jobsApi.listJobs(...).catch(() => [])`（两处 `resumeJobs`，**同一形态双份**）
- `DataBrowserView.vue:332-333`：`masterDataApi.listRegions().catch(() => [])`、`listProjects().catch(() => [])`
- `stores/workspace.ts:354`：`jobsApi.listJobs(...).catch(() => null)`

⇒ 结论：**"静默吞失败"同族并非只此一家**——仅注释型空 catch 全仓 18 处（跨 7 个文件）；只声明"下次切步会重试"的 `catch{}` 是 `syncStepToBackend` 两侧；更广的"丢 error 取兜底值"变体另有 5 处 `.catch(() => [])/null`。关键区别在于：18 处中只有 `syncStepToBackend` 双侧这处在 **AI 工具/handler 的成败回灌链上**（`goStep` 是 `import.nextStep` 的直接前驱，失败静默 ⇒ AI 会报"已推进"而 `PUT /step` 实际失败），其余均为展示/降级路径。

---
本子智能体实际运行模型名：**Kimi Code** 子智能体（会话未暴露可自证的底层模型 ID，无法给出更精确的模型标识）。
