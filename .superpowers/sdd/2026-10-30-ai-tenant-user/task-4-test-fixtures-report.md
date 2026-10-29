# Task 4 旧测试桩迁移报告（`ai-identity-fixture`，返修 I-3）

- 范围：**只改 `frontend/tests`**（`frontend/src` 一个字未改；`tests/ai-tenant-identity-race-recovery.spec.ts` 未触碰）
- 基线：`91a62771`（工作区含 round2/round3 的 `frontend/src` 现状）
- 命令口径：按指令**不跑整文件/批量/循环**，每个领域只精确跑 1 条 `-t` 单点；全量绿需在后续批次另跑

## 一、结论

| 维度 | 结果 |
| --- | --- |
| 统一夹具 | 新增 `frontend/tests/ai-identity-fixture.ts`（`installIdentityFixture({tenantId,userId,sessionId})`） |
| 迁移面 | **71 个文件修改 + 1 个新增**（68 个 spec + 2 个共享夹具 + 1 个夹具本体） |
| 裸键 | `ai-session-id` / `ai-mirror:<sid>` / `ai-session-<sid>` / 草稿前缀全部改为身份键形（`tenantId:userId` 指纹） |
| 本地造号 | 没有任何用例恢复"前端造号"：换会话/410/复制页签一律走后端发号 |
| 负控制 | 保留：`ai-tenant-identity-guards`（目录失败 ⇒ 不发请求）、`r166-gate-link`（无存量 ⇒ 后端发号）、`tab-ownership-*`（锁被占不接管） |
| 类型检查 | `npx vue-tsc --noEmit` **通过**（tsconfig 含 `tests/**/*.ts`） |
| 生产代码 | 0 处修改（复审 I-1/I-2 的 src 改动由别的批处理，不在本批） |

## 二、夹具口径（`tests/ai-identity-fixture.ts`）

`installIdentityFixture(options)` 在 `sessionStorage.clear()` 之后、创建 store 之前调用一次：

1. `resetAiIdentityCache()` + `writeAiIdentity(identity)`：身份进内存单例与 `ai-identity`；
2. 写身份会话指针 `ai-session-id:<t>:<u>`：store 初始化即"有身份、已发号"，与刷新恢复同一条
   `initSessionId()` / `storedSessionPointer()` 路径（不 mock store）；
3. 补 `tenantUsers` / `createSession` 桩（**只补缺的**：用例自己给了就不覆盖，避免抹平"目录失败/发号失败"的负控制）：
   - 同一身份**重复取号稳定**（指针仍在 ⇒ 返回同一号）；
   - 指针已被 store 抹掉（新建对话 / 410 / 复制页签换会话）⇒ 自动发**新**号（递增）；
   - 切换身份 ⇒ 下一个号（`tenantId:userId` 维度各记一个号）；
   - 要钉死具体号：`queueNextSessionId(id)` 或夹具上的 `queueSession(id)`（一次性）；
4. 返回 `mirrorKey()` / `lockName()` / `sessionIdKey` / `writeMirror()` / `readMirror()` / `issuedSessionId()`，
   用例不再手拼裸键。

缺省身份 `7:71`（与 `ai-tenant-identity-*` 同一组预置租户/用户）；每次 install 分配新会话号，
避免上一例镜像尾写污染下一例（沿用 `aie2e-helpers.nextSession()` 的既有理由）。

## 三、迁移面

### 3.1 共享夹具（影响所有 spec）

- `tests/tab-ownership-fixtures.ts`：`seedTab()` 改用 `sessionIdStorageKey()` / `mirrorStorageKey()`
  （装了身份 ⇒ 身份键，未装 ⇒ 旧裸键，两种现场都对得上）。
- `tests/aie2e-helpers.ts`：`MIRROR_PREFIX` 改为 `AI_MIRROR_PREFIX` 再导出；`writeMirrorFixture()` /
  `readMirrorFixture()` 走 `mirrorStorageKey()`。

### 3.2 新增夹具后"零散改动即可"的 spec（54 个）

`a11-batch2-display`、`ai-followup-suggestions`、`ai-history-phantom-slot-form`、`ai-history-user-text-code`、
`ai-multi-card-mixed-resume`、`ai-panel-batch7a`、`ai-panel-composer-limit`、`ai-panel-create-task-card`、
`ai-panel-flash-cny-20261029`、`ai-panel-gf-queue-card`、`ai-panel-model-default`、`ai-panel-params-removed`、
`ai-panel-parts-render`、`ai-panel-scroll-follow`、`ai-panel-send-guard-20261029`、`ai-reasoning-frame`、
`ai-state-autoclose-20261029`、`ai-state-guards-20261029`、`ai-toc-present-20261029`、`ai-ui-event-silent-success`、
`aie2e-panel`、`aie2e-qc-safety`、`aie2e-refresh`、`aie2e-refresh-forms`、`aie2e-refresh-upload-region`、
`b1-suspend-disconnect`、`b3-ar10-run-expiry`、`b3-ar10-run-expiry-notice`、`b3-compaction-panel`、
`b3-compaction-stage`、`concurrency-matrix`、`context-restore`、`empty-panel-matrix`、`en-us-flow`、
`frontend-tool-result-terminal`、`gate-matrix`、`gf-form-queue`、`irreversible-lifecycle`、`kb-tool-cards`、
`limits-matrix`、`m08-o02-single-flight-busy`、`publish-conflict-link`、`restore-orphan-null-req`、
`restore-reconcile`、`restore-request-emitted`、`rg-r4-r5-mirror-dedupe`、`rg-round-panel`、
`rg-s13-s14-stopped-label`、`r2-midstream-refresh`、`sheet-applied-summary-identity`、
`sheet-apply-partial-failure-store`、`store-batch2-recovery`、`tool-outcome-envelope`、
`truncated-answer`、`truncated-answer-panel`、`parts-reducer`。

### 3.3 键形/契约必须跟着改的 spec（14 个）

| 文件 | 迁移内容 |
| --- | --- |
| `duplicated-tab-clear` | 会话号 `tab-copy`→`9101` …；锁名 `sessionLockName()`、镜像 `mirrorStorageKey()`；清场后键集 = `[PAGE_ID_KEY, AI_IDENTITY_KEY, 会话键]`；返回意向补 `fingerprint` |
| `b6b-session-handoff` | 同上；`adoptFreshSession` 后的键集 = `[AI_IDENTITY_KEY, 业务键, 别的会话镜像]` |
| `r166b-fixes` | 锁名/镜像/会话键；claim 广播断言补 `tenantId/userId`（`toMatchObject`） |
| `tab-ownership-lifecycle` | 同上 + 返回意向 `fingerprint` |
| `ai-store-recovery-fix` | 复制页签锁名/镜像键身份化 |
| `ai-mirror-bounded-incremental` | `mirrorOf()` 走 `mirrorStorageKey()` |
| `parts-double-write` / `parts-residue-cleanup` | 镜像键身份化（`MIRROR_PREFIX + sid` → `mirrorStorageKey(sid)`） |
| `publish-conflict-resultview` | 镜像键身份化 |
| `session-purge-reset` | 镜像/草稿/会话键全部身份化；`clientCleanup.mirrorKey`/`draftPrefix` 用身份口径 |
| `session-purged-recovery` | 同上；旧会话号要求数字串（`4101`），换会话号由后端发 |
| `r166-gate-link` | "无存量 id ⇒ 建新 id"改为"无存量 id ⇒ **后端**发号并写到身份键"；存量恢复用 `9001` |
| `rg-round-restore-stop` | `mirrorOf()`/直写镜像身份化 |
| `form-draft-prune` | 哨兵键由 `'ai-session-id'` 改为 `AI_IDENTITY_KEY`（旧哨兵语义已失效） |
| `empty-panel-matrix` | "新建对话换新号"用 `queueNextSessionId()` 显式声明（默认同身份重复取号稳定） |

## 四、行为语义迁移（三处，非产品修复，属 Task 4 既有设计）

1. **会话号契约收紧为十进制长整**：`normalizeSessionId()` 只收正整数，旧测试里 `'tab-copy'` / `'old-sess'`
   这类字符串会话号一律换成数字串（`9101`… / `4401`…），否则 store 起来就是空号。
2. **复制页签发号成功即 `owner`**：`ensureTabOwnership()` 的 duplicate 分支在 `ensureSession()` 成功后
   置 `owner` 并认领新会话锁 ⇒ 原断言 `tabOwnership === 'duplicate'` 改为 `owner` + "会话号已换新"，
   并补"新会话锁在本页签手里 / 原会话锁仍在原页签"。涉及 `duplicated-tab-clear`(2)、`b6b-session-handoff`、
   `tab-ownership-lifecycle`(2)、`r166b-fixes`(3)。
3. **"原页签关闭 ⇒ 空白副本接手"在身份模式下不可达**（`onOwnershipMessage` 要求 `tabOwnership === 'duplicate'`，
   而复制页签已成为 owner）：`tab-ownership-lifecycle` 与 `r166b-fixes` 的相关用例改为断言
   **不接手**（会话号不变、不读原会话历史、原会话锁不被冒领）。这是 0fc6c38a 注释里"不提供接回入口"的
   直接后果 —— 若产品仍要"关闭后接手"，需要生产侧改判据，见"剩余问题"。

## 五、精确运行的测试（每个领域 1 条）

| 领域 | 命令（`-t` 单点） | 结果 |
| --- | --- | --- |
| 面板渲染（parts） | `ai-panel-parts-render -t "text → tool → text 的段序在 DOM 里保持一致"` | ✅ |
| 面板空态/新建对话 | `empty-panel-matrix -t "有消息 ⇒ 空态不在"` | ✅ |
| 面板发送闸/编辑重跑 | `ai-panel-send-guard-20261029 -t "编辑重跑"`（6 条命中） | ✅ |
| 面板 park 气泡 | `a11-batch2-display -t "挂起生成式表单时，气泡统一显示"` | ✅ |
| store 恢复 | `store-batch2-recovery -t "history 读取失败"` | ✅ |
| store 守卫 | `ai-state-guards-20261029 -t "M-17 原始：历史在途时开新轮"` | ✅ |
| 并发 | `concurrency-matrix -t "连点发送钮"` | ✅ |
| 410 恢复 | `ai-state-autoclose-20261029 -t "410 已换会话"` / `session-purged-recovery -t "下一次发送带新 sid"` | ✅ |
| 复制页签（新语义） | `duplicated-tab-clear -t "同一会话的锁被另一文档持有"`、`-t "继承来的会话镜像…"`、`-t "慢重载（带一次性返回意向"` | ✅ |
| 复制页签（store 侧） | `ai-store-recovery-fix -t "复制页签换会话但保持静默"` | ✅ |
| 所有权生命周期 | `tab-ownership-lifecycle -t "released 后原页签在宽限内重新认领"`、`-t "真关闭（锁随文档释放）"`、`-t "让渡广播不改变本页签会话"`、`-t "复制页签（没有「我会回来」意向"`、`-t "锁仍归我 ⇒ 有界重认领"` | ✅ |
| 三层握手（r166b） | `r166b-fixes -t "锁被占 ⇒ 复制页签起新会话"`、`-t "原页签关闭广播"`、`-t "锁仍被占 ⇒ 广播不可信"`、`-t "复制页签已产生内容"`、`-t "原页签离开（pagehide）"`、`-t "锁空闲 ⇒ 按恢复处理"` | ✅ |
| 会话销毁 | `session-purge-reset -t "只删该会话 mirrorKey"` | ✅ |
| 无存量发号 | `r166-gate-link -t "无存量 id ⇒ 由后端发号并写到身份键下"` | ✅ |
| 镜像 | `ai-mirror-bounded-incremental -t "超过消息上限只保最近"`、`parts-double-write -t "镜像迁移"`、`parts-residue-cleanup -t "applyFrontendToolTerminal：非 GF FRONTEND 段只收 requiresAction"`、`rg-r4-r5-mirror-dedupe -t "同一句提问（全角/半角标点之差）"`、`-t "真的不同的提问照补"` | ✅ |
| e2e 面板/刷新 | `aie2e-panel -t "stopping=true 时组件渲染 disabled"`、`aie2e-refresh -t "复制页签换新会话"`、`aie2e-refresh-forms -t "一条助手消息同时持有正文"`、`aie2e-refresh-upload-region -t "交接态重建为 waiting"` | ✅ |
| 草稿 | `form-draft-prune -t "空白名单"` | ✅ |
| 表格/上下文/矩阵 | `sheet-applied-summary-identity -t "应用后视图重算摘要"`、`context-restore -t "恢复"`（4 条命中）、`gate-matrix -t "CANCELLED 任务"`、`truncated-answer-panel -t "中文：可见「已截断」"`、`kb-tool-cards -t "读取知识文档"` | ✅ |

**注意**：本机同时有其它批处理在跑构建/测试，jsdom 里挂载 `AiPanel` 的用例冷启动约 4.7–6.8s，
默认 5s 超时在负载下会偶发超时（与本次改动无关，见"剩余问题"）；上表用 `--testTimeout=30000` 运行。

## 六、剩余问题 / 风险

1. **生产语义待裁决（未改生产代码）**：`onOwnershipMessage()` 的接手分支要求 `tabOwnership === 'duplicate'`，
   而复制页签在 `ensureTabOwnership()` 里发号成功即变 `owner` ⇒ **"原页签关闭后由复制页签接手原会话"在身份模式下不可达**
   （仍可达的唯一情形：复制页签发号失败，保持 `duplicate` + 空白）。本批按现状迁移了 3 处断言并保留
   "不读原历史 / 不改会话号 / 原锁不被冒领"的负断言；若产品仍要该能力，需生产侧放宽判据。
2. **会话号契约**：任何新用例不得再写非数字 sessionId（`normalizeSessionId()` 会静默变 `''`）。
3. **返回意向键**：`RETURN_INTENT_KEY` 的载荷现在要求 `fingerprint`（`takeReturnIntent()` 校验），
   测试桩必须带身份指纹，否则"慢重载不抢会话"路径静默失效。
4. **未做全量验证**：按指令未跑整文件/批量。风险面 = 未逐个 `-t` 执行的其余用例，尤其是：
   - 依赖"换会话必换号"的断言（夹具已做到"指针被抹掉即发新号"，但未逐条验证）；
   - `aie2e-stop` / `aie2e-stream` / `sse-parser-boundary` 等仍用 `freshStore()` + 手工 `sessionId`
     且不装夹具的文件（本次未改，理论上仍是旧"无身份裸键"现场；若后续要求全仓统一，可一行接入）。
5. **超时**：`frontend/tests` 之外（`vitest.config.ts`）才有 `testTimeout`，本批未改配置；
   面板类用例在本机负载下会顶到 5s。
6. **行尾**：少数文件在本次改写后工作区为 LF（仓库 `core.autocrlf=true`，提交按 LF 归一，
   `git diff` 无空白噪声）。

## 七、未改动

- `frontend/src/**`：0 处改动。
- `tests/ai-tenant-identity-race-recovery.spec.ts`、`ai-tenant-identity-{guards,switch,headers,duplicate-tab-identity}.spec.ts`、
  `sse-reattach-202.spec.ts`：身份/负控制本就自管，保持原样。
- 纯函数 / DOM / 设置页等与"会话号 + 身份键"无关的 spec：不接入夹具（避免无意义副作用）。
