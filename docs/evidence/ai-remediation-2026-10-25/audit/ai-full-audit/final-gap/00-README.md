> 来源=<仓外证据>/ai-full-audit/final-gap/00-README.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# FINAL-GAP｜§8.2 未逐行文件的最终收口分配（FA1–FA6）

> **状态：已分配，未完成。** 本目录只做「把 FINAL-AUDIT.md §8.2 的 54 个文件按子系统分成 6 个均衡作业」；**没有**开始审查，**没有**改动任何源码/测试/配置/工作流，**未**触发 CI。
> 只有在控制器逐份确认 `FA*-report.md` 之后，`FINAL-AUDIT.md` 才可以声称这些文件已覆盖。

## 1. 范围（严格等于 §8.2）

| 侧 | 条目数 | 展开后文件数 | 行数 |
|---|---:|---:|---:|
| 后端（Java） | 18 | 18 | 9,289 |
| 前端（TS/Vue） | 33 条（含 2 个通配：`resultview/*`、`types/*`） | 36 | 15,083 |
| **合计** | — | **54** | **24,372** |

**明确排除**（防止范围蔓延）：
- P1–P5 已逐行读完的 18 个文件（`RunStore`、`ResilientChatService`、`SseChatEmitter`、`ConfirmGate`、`SpToolCallingManager`、`LeaseService`、`AiSessionPurgeService`、`AiPanel.vue`、`stores/ai.ts`、`sheet-tools.ts`、`ai-dom-tools.ts`、`markdown.ts`、`KbService`、`AiConfigService`、`AiConfigWriter`、`GenerativeFormRules`、`AiExecutorConfig`、`HttpKbSource`）。
- 非 AI 业务内部：`TaskService`、`DefinitionService`、`ConfigDataService`、`ConditionEvaluator`、`masterdata/*`、`excel/*` 的读写内核、`SpreadGrid.vue` 表格内核、`DataBrowserView`/`DefinitionsView`、四个 `*JobRunner` 的步骤实现、`seed/*`。
- 不在既有 AI 清单内的新文件：一个都不加（本目录只收录 §8.2 已点名的对象）。

## 2. 分组（6 组，均衡且互斥）

| 组 | 主题 | 归属域 | 文件 | 行数 |
|---|---|---|---:|---:|
| **FA1** | 后端工具契约层（定义/披露/守卫/截断/结果视图） | D2 | 10 | 3,888 |
| **FA2** | 后端运行存储与并发原语 + 属性面 | D1/D3 | 5 | 3,578 |
| **FA3** | 作业与导入导出数据面（跨前后端） | D3/D6 | 6 | 4,471 |
| **FA4** | 前端组合界面（向导/设置/表单/上下文/结果视图） | D5 | 11 | 4,022 |
| **FA5** | 前端工具执行与状态辅助 | D6 | 11 | 3,807 |
| **FA6** | 前端 store / 类型 / 格式化 | D5/D6 | 11 | 4,606 |
| **合计** | — | — | **54** | **24,372** |

均衡度：最大/最小 = 4,606 / 3,578 = **1.29×**；无空组、无跨组重复。

## 3. 无遗漏 / 无重复 / 无重叠 —— 证明（实测输出）

| # | 检查 | 方法 | 结果 |
|---|---|---|---|
| 1 | 文件数守恒 | 解析 6 份 manifest 的表格行 | **54 行** |
| 2 | 行数守恒 | 表中行数求和 | **24,372** = 后端 9,289 + 前端 15,083 |
| 3 | 路径唯一 | 54 个路径去重 | **54 唯一**（0 重复） |
| 4 | 路径真实存在 | 逐个 `os.path.exists` | **0 缺失** |
| 5 | 行数与磁盘一致 | 每个文件重新计数并与表中值比对 | **0 不一致** |
| 6 | 与 §8.2 对齐 | 每个 basename 在 FINAL §8.2 文本中出现 | **47/54 直接命中**；其余 7 个来自两个通配：
  `components/AiPanel/resultview/*`(216 行) → `ResultViewRenderer.vue`(68)、`ResultViewTable.vue`(105)、`ResultViewText.vue`(28)、`registry.ts`(15)，合计 **216** ✓；
  `types/*` → `ai.ts`(984)、`sse.ts`(622)、`tools.ts`(563)、`form-schema.ts`(832)、`resultview.ts`(117)，合计 **3,118** ✓ |
| 7 | 与 P1–P5 零重叠 | 18 个已读文件全路径集合求交 | **交集 = ∅** |
| 8 | 不新增清单外文件 | 全部 54 个 basename 均来自 §8.2（含两个通配展开） | **0 例外** |

### 3.1 复现命令（只读）

```bash
cd <仓外证据>/ai-full-audit
# 1) 表中的 54 行与行数合计
grep -h '^| [0-9]* | `' final-gap/FA*.md | wc -l
grep -h '^| [0-9]* | `' final-gap/FA*.md | awk -F'|' '{gsub(/[ ,]/,"",$4); s+=$4} END{print s}'
# 2) 逐个文件行数复核（示例）
wc -l tree/backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java
```

## 4. 已知的部分阅读（避免重复劳动 / 避免过度信任）

下列文件**曾被某域读过部分内容**，FA 组应视为「补充全读」而非「从零开始」，但仍需逐行：

| 文件 | 已有阅读 | 说明 |
|---|---|---|
| `ai/run/RedisFrameStore.java` | P1 读 337–460 | seq/窗口段已读；`append` 回退、兼容 LIST、字节治理未读 |
| `ai/memory/RedisChatMemoryRepository.java` | L/M 读键推导/读写删主路径 | 索引、legacy 迁移细节、失败姿态未逐行 |
| `ai/session/SessionGate.java` | F 域自称全读 | 本次按「独立复核」列入；若 FA2 确认为全读，可只做交叉验证 |
| `ai/tools/AiTools.java`、`ToolRegistry`、`ContextBuilder`、三个 Guard | G1 为判断自身结论读过 | 断言强度与调用点仍缺完整视图 |
| `JobHeartbeatRegistry`/`JobService`/`TaskSseService` | E 域只读契约面 | 判死/收敛/TTL 的跨实例语义未逐行 |
| `utils/excel.ts`、`ImportWizardView`、`ExportWizardView` | A/I 定点 | 换任务、下载竞态只到接缝 |
| `FormRenderer.vue`、`UploadHandoffCard.vue`、`ContextPill/Popover` | I/G5 部分 | 渲染与交互细节未逐行 |

## 5. 交付与并入规则

1. 各组报告写入 `final-gap/FA<n>-report.md`，首行声明「未运行任何测试」。
2. 新条目必须带 `path:line` + 触发 + 用户可见效果 + 确定性（C/R）。
3. 与既有 canonical ID 重复时**回指**，不新开编号；若否证既有条目，写「否证 + 依据」。
4. 控制器确认后：把新条目并入 `FINAL-AUDIT.md §5`，按 P-adversarial 的口径升/降 V 与 Sev，并更新 §8.1/§8.2 的「已逐行」计数。
5. 未确认前，`FINAL-AUDIT.md` 保持「已分配、尚未完成」的表述。
