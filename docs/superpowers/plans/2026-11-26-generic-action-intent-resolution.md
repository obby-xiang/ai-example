# 通用 create_task 引用与确定性证据解析交付记录

**Goal:** 让“导出cny货币字典”及同类单创建输入由真实目录、字段和 rowKey 形成 canonical 参数，同时保留既有 preflight/HITL 与真实失败语义。

**Final architecture:** `CreateTaskReferenceResolver` + 真实主键值归一 + `CreateTaskEvidenceResolver`；当前 agent 单次 `createEntryCorrection` 仅作补全后备。最终没有 `ActionIntentParser`、`ActionPlan(actions[])`、`submit_action_plan` 或 `CREATE_TASK_DETAILS`。

**Final HEAD:** `38e8baabef524eb55ae11571120a95f522ffa2ba`

**Spec:** `docs/superpowers/specs/2026-11-26-generic-action-intent-resolution-design.md`

**真实验收:** `<deepseek-evidence-cny-38e8baab>/report.md`

**施工记录:** `<single-loop-create-completion>/report.md`

## 早期计划与最终取舍

早期计划曾准备新增通用 `ActionIntentParser`，输出 `ActionPlan(actions[])`，再以单个 create_task 接入。该方案未成为交付内容；多动作计划、执行协议和内部规划工具均已否决，不保留为当前架构或待完成承诺。

实际失败和返修推动了最终方案：整体配置引用、小写主键值、corrected 空条件以及帧持久化时点分别由真实主键归一、确定性证据、Prompt 临时标记和首次持久化前写入标志解决。永久 used 仅防止二次补全，初始未决帧抑制不删除 Redis/history，补全失败不重试。最终结果不是把同一问题再次全部交给模型。

## 已交付任务

### Task 1：真实主键值 canonical 化 — DONE

- [x] 保留 `CreateTaskReferenceResolver` 的配置和字段解析。
- [x] 非枚举唯一主键文本 EQ 通过真实业务行核验，精确值优先，upper/lower 有界且只在唯一 rowKey 时归一。
- [x] 非主键、复合主键、数组、null、非文本、范围不唯一、零/多候选和异常不盲目归一。

### Task 2：确定性证据解析 — DONE

- [x] 新增 `CreateTaskEvidenceResolver`，只使用结构化参数和最后一条非 Synthetic 真实用户文本。
- [x] 唯一目录、单主键、合法 scope、单页真实数据和唯一 rowKey 才生成 canonical 条件。
- [x] `conditions={}` 与 `fields=[]` 可补齐，非空 fields 不覆盖；无唯一 rowKey 时 fields=[] 保持无条件。
- [x] 显式 `defCodes=[]` 和明确全量不参与目录推断；REGION/R1 可核验，空/多 scope 不猜测。
- [x] 多目录、多 rowKey、超限和仓储异常返回空证据，不选择第一个或伪造成功。

### Task 3：manager 与 correction 收口 — DONE

- [x] 证据结果再次通过 `CreateTaskReferenceResolver` 后才进入原 preflight/HITL。
- [x] corrected 使用 Prompt 临时标记，永久 used 只防第二次 correction。
- [x] 初始未决用户帧抑制，但 Redis pending、history 和日志保留；corrected 最终失败可见。
- [x] 当前 agent 最多一次 createEntryCorrection，失败不再重试，不新增动作执行通道。

### Task 4：方法级验证与边界返修 — DONE

- [x] 真实主键归一、原始未决和 corrected 空条件成功路径逐方法 RED→GREEN。
- [x] corrected fields=[] 完整链、显式 defCodes=[]、REGION/R1、空/多 scope 和真实双目录命中分别复核。
- [x] 非空 fields、无唯一 rowKey 的明确全量、仓储异常和既有 correction 失败边界保留。
- [x] 生产文件执行 IDEA Reformat，实际编译和规范门禁通过。
- [x] 未以逐方法结果冒充全量测试或真实模型结果。

### Task 5：真实三轮验收 — DONE，3/3 PASS

执行真实上游 `deepseek-v4-pro` 和仓外 Chromium + 公开 HTTP API，未使用 CU，未重启服务。三轮均逐字输入“导出cny货币字典”，使用全新独立 session。

| 轮次 | 判定 | session / run | task / job |
| --- | --- | --- | --- |
| r1 | PASS | `1795703052357` / `05d5bd2b-8913-4e37-94af-7da8c024111f` | #513 / #417 COMPLETED |
| r2 | PASS | `1795703247918` / `2d56d267-4f43-4dc0-975f-655b6b6ff871` | #514 / #418 COMPLETED |
| r3 | PASS | `1795703390286` / `90a37dc3-547f-44cb-931b-1c26258d18ba` | #515 / #419 COMPLETED |

每轮均核对通过：

- [x] 唯一 create_task 参数为 `defCodes=["CURRENCY"]`、`code EQ CNY`。
- [x] 无 CREATE_TASK_DETAILS、选择或条件补卡，无引用解析/precheck 内部错误。
- [x] 创建与 start_export 两个 HITL；批准后任务/作业按边界从 0 到 1，job COMPLETED。
- [x] task item、set_conditions 和 XLSX 仅 CNY，文件一行且非空。
- [x] run DONE；刷新 history 16/16、7 张工具卡一致，无降级、空回复、丢卡或错误“已停止”。

详细截图、run 日志、history/卡片核对和文件检查以 `<deepseek-evidence-cny-38e8baab>/report.md` 及其 `rounds/` 证据为准。

### Task 6：最终文档收尾 — DONE

- [x] 设计和计划更新为最终交付结构，删除早期 actions[] 作为当前方案的表述。
- [x] 保留早期失败、方案否决和最终裁决。
- [x] 登记三轮 PASS 的 run/session、task/job、canonical 参数、HITL、文件与刷新一致性。
- [x] 未验证项明确写 NOT_RUN，独立环境问题单列。

## NOT_RUN

以下项目没有执行或没有验证，不写成 PASS：

- **多动作执行：NOT_RUN。** 最终交付也不包含多动作计划或执行协议。
- **全量测试：NOT_RUN。** 只保留逐方法证据，没有运行测试类或套件。
- **真实 Redis Cluster：NOT_RUN。** 未验证 Cluster 同槽、MOVED/ASK 或故障接管。
- **多实例：NOT_RUN。** 未验证随机路由、跨实例恢复或多实例一致性。
- **CI：NOT_RUN。** 未运行推送后的 GitHub CI 或其他流水线。
- **其他真实端到端场景：NOT_RUN。** 不把三轮句型外推到其他配置、歧义、多配置、明确全量或其他动作。

## 独立环境问题

`/api/ai/runs` 列表接口在当前环境仍返回 `400 ILLEGAL_STATE`，并伴随 `RunStore.readSnapshot` 的 Jackson 反序列化堆栈。三轮执行改用公开 current/runId、history 和 tasks 接口完成核验。该问题属于独立环境/接口问题，未在本功能中修复，不影响也不混入上述 3/3 PASS 判定。

## 最终结论

最终交付以本地确定性解析和真实业务数据为约束，单次 correction 仅为后备。指定 HEAD 的“导出cny货币字典”真实三轮验收 **3/3 PASS**；其他场景和上述 NOT_RUN 项不作通过声明。
