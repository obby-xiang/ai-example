<!-- 第 18 期补档入库 · 轮询治理一期（R-88/R-89）· 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\复核报告-轮询一期.md`（6013 B，sha256 `74877946305554849d7f4ed6c21a6441ce4d625d1650cfa90935e4efcb697073`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `e97df56e…782f016`（复核/红队据其出报告）；本期**入库锚** = `320b3c09…b541c7`（收口修后重锚，合流提交字节据其）。两 SHA 不可混用。

# 复核报告 · 轮询治理一期（K3 派单 · GLM 复核棒）

日期 2026-10-12。复核卡 sha256 核验通过（`FDE7661D…241559`），据卡执行。

## 0. 输入核验（全部相符，未停手）

| 输入 | sha256（实测） | 判定 |
| --- | --- | --- |
| 施工卡-定稿.md | `BBA860E6…BCFD1FA` | 符 |
| 决策卡-轮询一期.md | `FA06045E…6E1B298` | 符 |
| 施工报告-轮询一期.md | `3F972434…C71ABD27` | 符 |
| diff-轮询一期.sorted.patch | `E97DF56E…782F016` | 符 |
| 主仓库 HEAD | `6c9c699`，17 处变更全在 frontend/，backend 干净 | 与卡预期一致 |

## 1. 结论：**带条件 PASS**（B=0 / M=0 / m=4 / O=4）

复核卡六项要求全部通过；条件 = 施工侧已声明的运行时未闭合面（m-1/m-2）须在后续批次补验或正式豁免。

## 2. 门禁复跑（真跑留证，主循环亲跑）

- `yarn test`（vitest run）：**19 spec / 197 tests 全 passed，exit 0**（两次复跑一致；7 个新增 spec 全部在跑：polling-controller 11、task-events 7、job-watch-channel 8、wait-job-final 5、import-watch-recycle 4、task-events-lifecycle 3、polling-predicate 5）。
- `yarn typecheck`（vue-tsc --noEmit）：exit 0。
- `yarn build`：exit 0（built in 26.59s，dist 产物正常）。
- 门禁后 `git status --porcelain`：仍为 17 处 frontend 变更，`-- backend/` 空。
- `git diff d60c4a6 --` 输出 **0 字节** ⇒ 工作树与步3终态提交逐字节一致。

## 3. 对题性与 T1~T8（子智能体机械读取，锚定 16 段 diff）

- 施工卡 §2 全部 **17 条施工项在 diff 中均有对应改动**（含步1 条件轮询/refreshNow/超时判别/同 kind 回收、步2 polling.ts 封装+三消费点接入、步3 task-events.ts 订阅池+通道绑定），hunk 位置逐条对上。
- T1~T8 断言**真实存在且锁得住语义**：T1 polling-predicate（hasActiveJob 以 items 为准）、T2 polling-controller（单 interval/保底恰好 1 次/幂等/dirty）、T3 task-events（上限 3 稳定排序）、T4 三路径 dirty 收尾恰 1 次补发、T5 job-watch-channel（降级/权威快照 2 次/无新鲜度阈值）、T6 wait-job-final（超时可判别+abort 可识别+无 unhandled rejection）、T7 import-watch-recycle（同 kind 回收+在途不写回）、T8 task-events-lifecycle（卸载归 0+close-gate）。
- 基线数字（要求 4）：报告 §2 静态实测**逐条与 HEAD 亲验对上**——`setInterval` 3 处（task.ts:162 / job-watch.ts:106 / AiPanel.vue:839）、`new EventSource` 2 处（sse.ts:552 / tasks.ts:106）、`visibilitychange` 0、`hasRunningJob|hasActiveJob` 0；改动后工作树原生 `setInterval` 仅剩 polling.ts:147（controller 本体）1 处。"≈7~8 次/30s"推断与 4s 恒等轮询算术一致且已标注非实测。基线 12 spec/154 tests 未独立复跑（只读仓库，需 checkout），静态口径自洽（19−7=12，197−43=154）。
- 红线（要求 5）：diff 16 段全为 frontend/src（9 文件）+ frontend/tests（7 spec），**零段**涉及 backend/**、api/sse.ts、stores/ai.ts、SpreadGrid/spreadjs、utils/excel、frontend-tools 定时器；git status 亦无 backend 条目。
- 锚点抽验（要求 6）：子智能体 18 处 + 主循环亲验 4 处（polling.ts:37 `CATCHUP_JITTER_MS=250`、:147 `setInterval(onTick,…)`、task-events.ts:31 `SUBSCRIPTION_LIMIT=3`、task.ts:49/:153）= **22/22 MATCH，零 MISMATCH**。

## 4. 编号发现

**m 级（4）**
- m-1 D14 运行时三项请求数基线未实测——报告 §2/§8 已如实声明为偏差、未编造数字；复核确认其静态替代口径成立。（带条件 PASS 条件之一）
- m-2 施工卡 §4.3-7「B1 判死链反查」等必做运行时验收 + §4.4/E2E 未执行——报告 §5 显式声明未判 PASS，语义由 spec 锁定；属一期未闭合必做面。（带条件 PASS 条件之一）
- m-3 步1-5「停表谓词纳入 filterStatus 口径」未逐字实现——`hasActiveJob` 只读 `state.items`（后端过滤后等价承载），T1③ 锁定语义，偏离未在报告中披露。
- m-4 施工卡 §3 注记「新增 5 spec/总数 17」与自身表格 7 文件矛盾——卡文数字滞后；实际 7 新/19，报告口径正确。

**O 级（4）**
- O-1 提交链 `2030c24→ca84ec9→d60c4a6` 后被 soft reset 回 `6c9c699`（变更 staged）——reflog 完整留痕三 commit 真实存在，且复核卡预告"HEAD 应为施工后工作树（未提交态）"，非造假；唯"每步可按步回退"现仅存于 reflog。
- O-2 `wait-job-final.spec.ts` abort 用例走真实 20ms 等待非假时钟，轻微 flaky 面。
- O-3 `workspace.ts:452` abort 触发源未接（E6 红线约束改动面），报告已声明二期/R-94 项。
- O-4 §4.0 要求 DevTools/HAR 留档，报告以 WebBridge 抓包替代并披露口径差异。

另：子智能体静态清点 196 vs 实跑 197 用例，以本复核两次真跑 197 为准，差异属静态清点误差。报告 §7 三处对卡偏差经 diff 核实均已落地为所声明形态，未发现未披露偏离。

## 5. 复核纪律自证

机械读取（4 份材料全文 + diff 16 段锚定）一次性下沉子智能体 `agent-573`（explore，只读），主循环 ≤6 轮（Read/Hash→门禁复跑→红线与基线亲验→wire 取证→落盘）。仓库零写入；未读 .env；未触 ai-example 与 ai-example-main（trae 线）；唯一写权限=本报告。

## 6. DC-19 署名自证

| 行 | 项 | 实证 |
| --- | --- | --- |
| 1 | 实际模型 | `Zhipu AI/glm-5.3`（主棒） |
| 2 | 会话 id | `session_b305f0f5-775a-4bdb-b77d-510c7e265ed5` |
| 3 | wire modelAlias（主棒） | `agents/main/wire.jsonl` 175 行中 17 × `"Zhipu AI/glm-5.3"`，无混杂 |
| 4 | wire modelAlias（子智能体钉模） | `agents/agent-573/wire.jsonl` 357 行中 30 × `"DeepSeek/deepseek-flash"`，无混杂——符合"显式钉 DeepSeek V4 Flash"纪律 |

—— K3 复核棒（GLM-5.3）· 2026-10-12 · 结论：带条件 PASS（B0/M0/m4/O4）
