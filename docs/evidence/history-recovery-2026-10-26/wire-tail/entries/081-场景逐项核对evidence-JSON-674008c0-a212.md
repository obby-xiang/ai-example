> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-212` 的 wire 末条 assistant 文本（消息序号 m=11；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-09 20:19 → 2026-10-09 20:20（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：8638 B / 5953 字符 / 86 行；行指纹覆盖率（本批复算）1.8%（最高单文件 2%：`docs/任务看板.md`）
> - 类别：**施工复核**
> - 与既有件关系：施工报告；报告口径覆盖率 0.02
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
抽查完成。以下为逐字段核对结果，全部结论基于 evidence 目录原始 JSON。

## 目录清单（附加核对 1）

18 个 JSON 与 18 张 PNG **完全配对、文件名齐全**：

```
probe-{before,after}-{s1-manual-check, s2-context, s3-ai-handler, s3b-ui-event,
                     s4-restore-no-double, s5-uncheck, s6-conflict, s7-rebind,
                     s8-import-manual}.json   → 各 9 个，共 18
同名 .png                                     → 各 9 张，共 18
```
辅助文件另有 `probe-src-issue3.ts`、`probe-issue3.html`、`probe-run.mjs`、`gate-*.log`×3、`mvn-test-{baseline,after}.log`、`redteam-workspace.diff`。全部 18 份 JSON 均为 `"ok": true`、`"probe"` 非 null，顶层 `"phase"` 字段与文件名前缀一致，`capturedAt` 与文件 mtime 秒级吻合。

## 场景逐项核对

### s1 手动勾选（probe-before/after-s1-manual-check.json）
| 宣称值 | JSON 实际值 | 结论 |
|---|---|---|
| before 文案「已选 2 个配置项」 | `s1_after_click_doc_type.countText`：`"已选 2 个配置项"` | 吻合 |
| before `ws.selectedDefs=[]` | 三个快照均 `"selectedDefs": []` | 吻合 |
| before dataVersion 恒 3 | 三次快照均 `"dataVersion": 3` | 吻合 |
| before 无勾选 chip | `chips` 仅 v1「进入任务中心」v2/v3「进入导出向导」，无选中 chip | 吻合 |
| before 复选框 checked 有记录 | `"s1_click_currency": "CURRENCY: checked false → true"`、`"s1_click_doc_type": "DOC_TYPE: checked false → true"`；`selection` 数组记录 false→true | 吻合 |
| after `['CURRENCY','DOC_TYPE']` | `s1_after_click_doc_type.workspace.selectedDefs = ["CURRENCY","DOC_TYPE"]` | 吻合 |
| after dataVersion 3→4→5 | 3 → 4 → 5（逐快照） | 吻合 |
| after chip v4 / v5 且来源界面 | `{version:4, summary:"已选择配置项：CURRENCY", source:"界面"}`、`{version:5, summary:"已选择配置项：CURRENCY、DOC_TYPE", source:"界面"}` | 吻合 |

### s3 AI handler（probe-before/after-s3-ai-handler.json）
| 宣称值 | JSON 实际值 | 结论 |
|---|---|---|
| before `ws=[]` | `s3_before`/`s3_exec`/`s3_after` 均 `"selectedDefs": []` | 吻合 |
| before 版本恒 3 | 三次均 `"dataVersion": 3` | 吻合 |
| after `['CURRENCY']` | `s3_exec`/`s3_after` 均 `"selectedDefs": ["CURRENCY"]` | 吻合 |
| after dataVersion 3→4 | `"dataVersion": 4` | 吻合 |
| recentActions 末条来源 AI | `"已选择配置项：CURRENCY（来源：AI）"` | 吻合 |
| newCalls=[] 且无新增 POST /tasks/5/select-defs | `s3_exec.newCalls: []`；`allCalls` 仅 4 个 GET，全局无任何 POST | 吻合 |

### s4 恢复不二次 touch（probe-before/after-s4-restore-no-double.json）
| 宣称值 | JSON 实际值 | 结论 |
|---|---|---|
| before 与 after 增量都是 2 | 两份均 `"versionBefore": 1, "versionDelta": 2`（终值 dataVersion=3） | 吻合 |
| before/after recentActions 均无「已选择配置项」 | 两份均 `"hasSelectedDefsAction": false`；recentActions 只有 3 条进入类动作 | 吻合 |
| （旁证）恢复结果 | 两份均 `selectedDefs=["PROJ_APPROVE","PROJ_PRICE"]`、复选框两行 checked=true | 吻合，且前后一致 |

### s5 减选/复勾（probe-before/after-s5-uncheck.json）
| 宣称值 | JSON 实际值 | 结论 |
|---|---|---|
| after 点选后 `['CURRENCY','DOC_TYPE']`（v5） | `s5_after_two_clicks`：`"selectedDefs": ["CURRENCY","DOC_TYPE"], "dataVersion": 5` | 吻合 |
| after 减选后 `['DOC_TYPE']`（v6） | `s5_after_uncheck`：`["DOC_TYPE"]`, v6 | 吻合 |
| after 复勾后 `['DOC_TYPE','CURRENCY']`（v7） | `s5_after_recheck`：`["DOC_TYPE","CURRENCY"]`, v7 | 吻合 |
| before 全程 ws=[]、版本恒 3 | `s5_after_two_clicks`/`s5_after_uncheck`/`s5_after_recheck` 均 `"selectedDefs": []`, `"dataVersion": 3` | 吻合 |
| 点击记录 | `"s5_uncheck_currency":"CURRENCY: checked true → false"`、`"s5_recheck_currency":"CURRENCY: checked false → true"` | 吻合 |

## 附加核对 2：探针源码通读（probe-src-issue3.ts，469 行）

- **(a) 真实复选框 `.click()`**：`probe-src-issue3.ts:261-273` `clickCheckbox()`，取 `td.el-table-column--selection input[type="checkbox"]`（`:248-250`）后调用 `box.click()`（`:272`），先读 `const before = box.checked` 再读 `box.checked`，返回 `"CODE: checked X → Y"`。走的是真实 `selection-change → onSelectionChange → v-model → watch` 链路，非直接改 ref。**属实。**
- **(b) s6「后端等价文本」在浏览器内就地镜像拼出**：`probe-src-issue3.ts:345-361` `backendEquivalent()`，直接读 `useWorkspaceStore().buildContext().extra` 拼 `oldRule`/`newRule` 字符串；`:341-344` 注释自认「**旁证**，不是后端真值」，并声明真值由 `ContextBuilderSelectedDefsTest`（8 例）与既有 346 例测试给出。**属实。**
- **(c) 替换 axios 适配器为假后端、不连真实 LLM**：`probe-src-issue3.ts:145` `http.defaults.adapter = async (config) => {...}`，全部响应由 `DEFS`/`TASKS` 本地常量与 `envelope()` 构造（`:137-203`）；源码中无任何 LLM/chat/stream 调用。**属实**（假后端，仅 HTTP 层被替换）。
- **(d) before/after 同一份探针源码**：`probe-run.mjs:132-134` 对两相用同一 `BASE_URL` 只改 `?scenario=&phase=`；探针源码中 `phase` 仅两处出现——`:209` 读取参数、`:460` 原样写入 `records['phase']`，**无任何按 phase 分支的逻辑**。故两相共用同一源码与同一执行路径。**属实。**
- **(e) 本机绝对路径/凭据**：对 `probe-src-issue3.ts` 与 `probe-run.mjs` 检索盘符、token/secret/password/apikey/Bearer/用户目录，**零命中**；运行器默认值为 `http://localhost:5211/...` 与相对路径 `./probe-chrome-profile`。`.html` 也只引用 `/__issue3_probe.ts` 相对资源。**属实。**

## 附加核对 3：时间戳时序

| 批次 | 范围 | 递进 |
|---|---|---|
| before 9 份 | 20:08:57 → 20:09:13（+0800） | s1→s2→s3→s3b→s4→s5→s6→s7→s8 严格递增 |
| after 9 份 | 20:09:50 → 20:10:28（+0800） | 同序严格递增 |

两相之间约 37 秒空档（20:09:13→20:09:50，符合换相重启浏览器的间隔），全部 after 严格晚于全部 before；PNG 与同名 JSON 间隔 <200ms；JSON 内 `capturedAt`(UTC) 换算后与 mtime 一致。**时序合理，无倒挂、无交叉。**

## 发现的问题与总评

**未见任何字段与施工报告宣称不符**——4 个场景、共 24 条宣称值全部在 JSON 原件中找到并吻合，未出现「找不到该字段」。

可信度总评：**高**。证据链自洽（文件名↔phase↔capturedAt↔mtime 四方一致），探针确为真实 DOM 点击 + 真实 store/router + axios 桩，两相共用源码且 phase 不参与逻辑，源码无绝对路径与凭据。

以下三点为**需向上层点明的限定与噪声**，均不构成宣称造假：

1. **s8 两份 JSON 都含 `VUE_ERROR: TypeError: jobs.filter is not a function`**（我抽查的 4 场景之外）。成因是探针桩未覆盖 `GET /tasks/11/jobs`，落到 `:200-202` 的兜底「按空对象放行」。该错误在 before/after **完全同现**，不产生差分偏置，但说明 s8 的导入页在探针环境下带着一个未捕获异常运行，引用 s8 结论时应对此加注。
2. **归档的 `probe-run.mjs` 是 issue#2 运行器原件**（头部注释与 `:30` 的 `ALL` 默认场景列表均为 issue#2 场景，`:20` 默认 URL 指向 `__issue2_probe.html`）。issue#3 的 s1–s8 必须经 `PROBE_URL`/`--scenarios` 覆盖才跑得出；目录内**未归档当次实际的环境变量与命令行**，故本次运行只能靠复算复现，不能直接原样重跑。属溯源缺口，非数据不符。
3. **s5 after 的 `recentActions` 出现首条被挤掉**（uncheck 后从「进入任务中心」开头变为「进入导出向导」开头），应为列表容量截断；与宣称的判定项无关，仅提示引用 recentActions 时应按「尾部窗口」而非「全量历史」解读。

另附：`probe-src-issue3.ts` 自认后端等价文本为旁证，因此报告中凡涉及「后端新增 selectedDefs 规则」的真值，仍只能以 `mvn-test-after.log` 的 354 例（baseline 346 + 新增 8）与 `ContextBuilderSelectedDefsTest` 为准，不能拿 s6 的浏览器内镜像当后端证据——报告若这样引用即属越界。

gate 侧顺带确认：`gate-build.log`/`gate-typecheck-final.log` 均 `BUILD_EXIT=0`、`TYPECHECK_EXIT=0`；`mvn-test-baseline.log` Tests run 346 / Failures 0 / BUILD SUCCESS，`mvn-test-after.log` 354 / Failures 0 / BUILD SUCCESS。
