# 前端数字规格清点（frontend）

> 只读审计产物。本文件是本批唯一新增文件，未改动任何生产/测试代码，未做 git 写操作，未运行测试/服务/浏览器。

## 0. 元信息

| 项 | 值 |
|---|---|
| 仓库根 | `<REPO_ROOT>`（主仓 `main`） |
| 排查范围 | `frontend/src/**` 的 TS/Vue/JS（66 个 `.ts` + 24 个 `.vue`，共 52,648 行）；`frontend/vite.config.ts`；`frontend/.env.example`；`frontend/package.json` 仅运行脚本；**补扫** `frontend/scripts/build.mjs`（27 行）；**补扫** 9 份 `frontend/playwright*.config.ts`（调度四字段 / 端口段 / Redis DB / `--app.*` 命令行覆盖 / 视口，共 32 条） |
| HEAD | 首轮扫描基线 `405e74ebf96b3340c9443c6e3087708814e996f6`（`2026-10-31 01:11:19 +0800 refactor(ai): 删除未上线Redis迁移工具链`）。补扫 `build.mjs` 时工作树已前进到 `c7e2497d`（`2026-10-31 01:32:39 +0800 fix(ai): 固化决策身份头与前端类型契约`）；该提交的 `frontend/src` 内容 = 首轮扫描时的索引态（同样 3 文件 75+/151−），行号不受影响。 |
| 工作树状态（`git status --porcelain`） | 首轮扫描时：后端多文件 M/D；前端 `frontend/src/api/ai.ts`、`AiPanel.vue`、`stores/ai.ts` 为**已暂存** M，`frontend/tests/*` 多条 M/A。补扫时：上述前端改动已提交为 `c7e2497d`，`git status --porcelain -- frontend` 现为**空**（前端工作树干净）；后端仍有多文件 M/D。本报告的行号按首轮磁盘现态（= 已提交内容）给出。 |
| 排查时点 | 2026-10-31 |
| 敏感面 | `frontend/.env.local` 存在（键 `VITE_API_BASE` / `VITE_DEV_PORT` / `VITE_SPREADJS_KEY`），**按任务要求未读取其值**，故不登记本地覆盖值 |
| 旧基线 | `docs/evidence/补档-NUMSPEC-清单总账.md`（HEAD `6ee96c3`、排查时点 2026-10-10）**仅用于对账**，本表逐行按当前磁盘现值重取，不照抄 |
| 登记条数 | **212 条**（`N-FE-001`…`N-FE-212`，同值不同语义分行；同义多站点合并为一行并在「作用与消费点」列全部列出；9 份 Playwright 配置的调度字段与端口/DB 同值也分行） |

### 字段口径

- **类型**：`配置` = 环境变量/可覆盖配置；`硬编码` = 源码字面量或 `static const`，改值须改代码；`浏览器缺省` = 本仓未配置，取运行时默认。
- **依据**：`有` = 注释/文档给出数值论证、实测或正式裁决；`部分` = 只说明用途/同源关系，无数值论证；`裸数字` = 无任何说明。
- **审视建议**：只描述方向，不代表本批已改。
- 排除：纯 CSS 布局 px/width/max-height/字号/圆角/动画时长；纯测试断言；依赖版本；HTTP 状态码；枚举序号；算法下标/循环步长；格式化展示（相对时间分档、`toFixed` 小数位等）。**JS 常量若控制布局/滚动/虚拟化行为**（如跟随闸、折叠判据）仍登记。

---

## 1. HTTP / SSE / AI 通道

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-001 | 超时 | `frontend/src/utils/time-constants.ts:21`（消费 `frontend/src/api/http.ts:136`） | `HTTP_TIMEOUT_MS` | 60000 ms | 全部 axios 请求单次上限（业务 REST、前端工具回灌 POST、上传/下载 blob）；`axios.create({timeout})` | 硬编码 | 有（注释引 axios 缺省 `timeout:0` 不超时 + 内网 60–120s 区间取下限） | 整条超时链的根；若上调须同步复核 SSE 45s 与工具预算折算 | F1-01 |
| N-FE-002 | 超时 | `frontend/src/api/sse.ts:34` | `DEFAULT_IDLE_TIMEOUT_MS` | 45000 ms | `readStream` 帧间静默看门狗（chat 与 reattach 共用） | 硬编码 | 有（<60s 代理空闲死区、刻意落后端 90s） | 与后端 `inter-event-timeout` 的错层关系已写明，保留 | F1-02 |
| N-FE-003 | 重试 | `frontend/src/api/sse.ts:42` | `DEFAULT_MAX_RECONNECTS` | 4 次 | reattach 断流后自动重挂上限（`scheduleReconnect` 到 `attempts >= maxReconnects`） | 硬编码 | 有（一次代理重建 + 跨实例续跑的窗口论证） | 保留；与建连 15s 共同决定 90s 恢复窗口 | F1-03 |
| N-FE-004 | 退避 | `frontend/src/api/sse.ts:45` | `DEFAULT_RECONNECT_DELAY_MS` | 1000 ms | 第 1 次重连等待；后续指数 1/2/4/8s | 硬编码 | 部分（只注「后续指数退避」，基数无独立论证） | 若要调，须连同 4 次重挂的总窗口一起复核 | F1-04 |
| N-FE-005 | 退避 | `frontend/src/api/sse.ts:48` | `RECONNECT_JITTER_RATIO` | 0.25（±25%） | `reconnectDelayMs` 的抖动幅度，乘 `[0.75,1.25]` | 硬编码 | 部分（只给「防雷群」目的，未给幅度依据） | 保留；测试已注入随机源锁边界 | F1-05（部分） |
| N-FE-006 | 退避 | `frontend/src/api/sse.ts:56-59` | `base * 2 ** (attempt-1) * (0.75+0.5·rand)` | 1/2/4/8s × [0.75,1.25] | 重挂退避算式（含 `attempt>=1` 下限夹取） | 硬编码 | 有（决策看板 + 指数退避反模式论证） | 保留；算式是 F1-04/05 的合成，调整时三处同改 | F1-05 |
| N-FE-007 | 超时 | `frontend/src/api/sse.ts:76`（消费 `:852`、`:1087`） | `DEFAULT_CONNECT_TIMEOUT_MS` | 15000 ms | chat 建连看门狗 + reattach 每条连接 `onopen` 前看门狗 | 硬编码 | 有（审计 10~15s 上界；4 次重挂整段 90s 的算式） | 保留；与帧间 45s 分维度 | F1-06 |
| N-FE-008 | 超时 | `frontend/src/api/sse.ts:641` | 复用 `DEFAULT_CONNECT_TIMEOUT_MS` | 15000 ms | `probeReattach` 一次性 `?probe=1` GET 的 abort 上限 | 硬编码 | 部分（复用建连值，未独立论证一次探测 15s 是否过长） | 探测不该推迟退避；若要收紧可单列常量 | —（新） |
| N-FE-009 | 超时 | `frontend/src/api/sse.ts:86`（消费 `:900`） | `DEFAULT_FIRST_FRAME_TIMEOUT_MS` | 30000 ms | `POST /api/ai/chat` 首帧（响应已建立、零帧）看门狗；收首帧即让位 45s | 硬编码 | 部分（只论证「可短于 45s」，30s 本身无来源） | 建议补「压测/真机首帧分布」或与 45s 建立派生关系 | —（新） |
| N-FE-010 | 重试 | `frontend/src/api/tasks.ts:123-128`（`openTaskEvents`，无 retry 参数）→ 消费 `frontend/src/utils/job-watch.ts:180-187` | 未设 `EventSource` retry | 浏览器缺省（Chrome 约 3s，随 5xx/网络退避） | 任务级 SSE 通道自动重连仍由浏览器兜底；业务侧 `onerror` 只降级为 3s 轮询 | 浏览器缺省 | 裸数字（仓内无显式配置，注释只说明「按 retry 间隔重连」） | 与 AI 通道显式 1/2/4/8s 策略不对称；是否接管需单独裁决 | F1-09 |

---

## 2. 前端工具执行器与页面等待

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-011 | 超时 | `frontend/src/utils/frontend-tools.ts:323` | `FRONTEND_TOOL_TIMEOUT_BUDGET_MS` | 60000 ms | 前端工具执行器预算的回落值（帧未带 `timeoutSeconds` 时） | 硬编码 | 有（`预算+60000 ≤ 服务端上限` 的数学保证 + issue#6 实测） | 该值 = 默认 120s − 余量；改后端默认上限时同步复核 | F1-19 |
| N-FE-012 | 超时 | `frontend/src/utils/frontend-tools.ts:334` | `FRONTEND_TOOL_RESULT_HEADROOM_MS = HTTP_TIMEOUT_MS` | 60000 ms | 折算时让给回灌 POST 的最坏耗时；与 axios 上限**代码同源** | 硬编码（同源别名） | 有（同源改注释为代码引用） | 保留；同源后改 60s 一次生效三处 | F1-20 |
| N-FE-013 | 超时 | `frontend/src/utils/frontend-tools.ts:342` | `MIN_FRONTEND_TOOL_TIMEOUT_BUDGET_MS` | 5000 ms | 服务端上限被配得过小时的预算下限；与上限冲突时上限优先 | 硬编码 | 部分（只注「防预算过短」） | 保留；≤65s 上限属病态配置分支 | F1-21 |
| N-FE-014 | 超时 | `frontend/src/utils/frontend-tools.ts:412` | `TIMER_MAX_DELAY_MS` | 2147483647 ms（2^31−1） | `normalizeBudgetMs` 上界，防 `setTimeout` 32 位溢出退化成 ~1ms 假超时 | 硬编码 | 有（MDN + Node v22 实测） | 保留；引擎硬约束 | F1-22 |
| N-FE-015 | 超时 | `frontend/src/utils/frontend-tools.ts:369-370`（消费 `stores/ai.ts:9711`） | `timeoutSeconds * 1000` | 无固定值（默认 120s → 60s） | 由帧内服务端挂起上限折算每次工具预算：`min(max(上限−60s,5s), 上限)` | 配置（值来自服务端帧） | 有（折算保证链 + 病态区登记） | 保留；服务端改键即平移 | F1-27（语义已改） |
| N-FE-016 | 超时 | `frontend/src/utils/frontend-tools.ts:580`（消费 `views/ExportWizardView.vue:2441`、`:2786`） | `DOWNLOAD_PREFLIGHT_TIMEOUT_MS` | 30000 ms | 下载类前端工具「前置校验」显式收口（任务加载/结果清单串行 HTTP） | 硬编码 | 有（L-82 真机 >90s 悬空实测；取外层默认 60s 的一半） | 保留；与 F1-19 取先到者 | —（新） |
| N-FE-017 | 超时 | `frontend/src/utils/frontend-tools.ts:836` | `PAGE_READY_TIMEOUT_MS` | 8000 ms | `waitForPageReady` / `waitForTaskDetailApplied` / `waitForTaskBound` / `waitForTaskOpened` 的上限 | 硬编码 | 裸数字（无取值论证；旧基线为 3s，本批未见变更说明） | **重点待裁**：3s→8s 的漂移没有任何注释背书，须补依据或回归 | F1-23（旧 3s） |
| N-FE-018 | 轮询 | `frontend/src/utils/frontend-tools.ts:861 / :922 / :971 / :1007` | `await delay(50)` | 50 ms | 上述四类「页面/任务就绪」探测的轮询间隔 | 硬编码 | 裸数字 | 可提取为常量并说明与 8s 上限的节拍关系 | F1-18 |
| N-FE-019 | 超时 | `frontend/src/stores/workspace.ts:630`（消费 `:1011`、文案 `:1021`） | `DOWNLOAD_FETCH_TIMEOUT_MS = HTTP_TIMEOUT_MS` | 60000 ms | `ui_event download` 的 `fetch` 拉字节上限；到点 abort 并如实回 fail | 硬编码（同源别名） | 部分（同源关系已写；60s 本身沿用 N-FE-001） | 保留 | F1-15 |

---

## 3. 轮询 / 作业跟踪 / 列表分页

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-020 | 轮询 | `frontend/src/utils/job-watch.ts:38`（消费 `:74`、`:140`） | `JOB_FALLBACK_POLL_MS` | 3000 ms | 作业跟踪 SSE 判死/断线后的兜底快照轮询间隔 | 硬编码 | 部分（注释只说明「3s 自愈」） | 保留；可注入测试值 | F1-10 |
| N-FE-021 | 阈值 | `frontend/src/utils/job-watch.ts:44`（消费 `:75`、`:127`） | `FRAME_IDLE_WATCHDOG_MS` | 90000 ms | SSE 连上后连续无帧判通道死亡 → 降 3s polling；只由帧/onopen 复位 | 硬编码 | 部分（反转「无帧正常」口径，90s 本身无数据） | 建议与后端任务通道心跳/`emitter-timeout` 对齐说明 | —（新） |
| N-FE-022 | 轮询 | `frontend/src/utils/job-watch.ts:98`（消费 `:106`） | `REALTIME_IDLE_MS` | 2147483647 ms（≈24.86 天） | realtime 期间把兜底表推到「等价停表」，保留 `refreshNow` | 硬编码 | 有（32 位定时器上界 + 看门狗 90s 远早触发） | 保留 | —（新） |
| N-FE-023 | 防抖 | `frontend/src/utils/polling.ts:26`（消费 `:167`） | `CATCHUP_JITTER_MS` | 250 ms | 页面回可见时补刷一次的 0~250ms 随机错峰（多轮询点打散） | 硬编码 | 部分（只说明错峰目的） | 保留；内部常量不导出 | —（新） |
| N-FE-024 | 超时 | `frontend/src/stores/task.ts:100`（消费 `:518-522`） | `PUBLISH_WATCH_WALL_CLOCK_MS` | 300000 ms | 发布跟踪独立墙钟；非终态到达即提示 + 收摊（终态到达清表） | 硬编码 | 裸数字（300s 无来源，与后端 total-budget 同值但无关联说明） | 建议给出「作业最长预期」依据或改为与作业看门狗派生 | —（新） |
| N-FE-025 | 退避 | `frontend/src/stores/task.ts:624`（循环 `:625-629`） | `backoffMs = [200, 400]` | 首发 + 200ms + 400ms = 3 次 | `ensureTaskType` 瞬时读取失败的有界退避（404 等确定性 4xx 立即收口） | 硬编码 | 部分（注释说明 3 次/不轮询，数值无独立依据） | 保留；可提取常量 | —（新） |
| N-FE-026 | 分页 | `frontend/src/api/jobs.ts:29`；`frontend/src/stores/task.ts:546`；`frontend/src/views/ExportWizardView.vue:793 / :1519`（走默认） | `page = 0, size = 50` | page 0、size 50 | 作业校验问题分页默认值；发布冲突明细拉取用同一页大小 | 硬编码（函数默认参数 + 显式实参） | 裸数字（与后端 `JobController` 默认 50 对齐，无本地论证） | 建议集中为常量并与后端默认值同源说明 | F1-13 |
| N-FE-027 | 分页 | `frontend/src/stores/task.ts:214` | `size: 10` | 10 条/页 | 任务中心列表默认页大小（`setSize` 可改） | 硬编码 | 裸数字（与后端 `TaskController` 默认 10 一致） | 保留 | F1-12 |
| N-FE-028 | 分页 | `frontend/src/views/TaskCenterView.vue:115` | `[10, 20, 50]` | 10/20/50 | 任务中心页大小选项表（与 store 默认 10 一致） | 硬编码 | 裸数字 | 选项表与后端上限是否一致待核 | F2-03 |
| N-FE-029 | 上限 | `frontend/src/views/TaskCenterView.vue:136` | `maxlength="60"` | 60 字符 | 创建任务标题输入上限（前端软限制） | 硬编码 | 裸数字（后端是否有同值校验未在本次前端面确认） | 建议与后端 DTO 校验同源 | F2-04 |
| N-FE-030 | 分页 | `frontend/src/views/DataBrowserView.vue:193` | `size = ref(20)` | 20 条/页 | 数据浏览默认页大小 | 硬编码 | 有（注释引业界默认 + 后端 `DataController` 已统一为 20） | 保留 | F2-01 |
| N-FE-031 | 分页 | `frontend/src/views/DataBrowserView.vue:129` | `[20, 50, 100]` | 20/50/100 | 数据浏览页大小选项表 | 硬编码 | 部分（注释要求改默认值须连带改选项表） | 保留 | F2-02 |
| N-FE-032 | 分页 | `frontend/src/views/ImportWizardView.vue:2083` | `jobsApi.getIssues(jobId, 0, 200)` | page 0、size 200 | 导入发布冲突明细一次拉取上限（超 200 条只显示前 200） | 硬编码 | 裸数字（与 N-FE-026 的 50、`tool-result.max-rows` 200 均无关联说明） | **重点待裁**：同资源三套页大小（50 / 200 / 后端默认） | F2-07 |
| N-FE-033 | 上限 | `frontend/src/views/ImportWizardView.vue:189` | `group.rows.slice(0, 100)` + `:207` 文案 | 100 行 | 发布差异暂存区预览行数上限，超出显示「仅展示前 100 行」 | 硬编码 | 部分（有配套文案，100 无来源） | 可考虑与虚拟滚动/分页统一 | F2-05 |
| N-FE-034 | 上限 | `frontend/src/views/ImportWizardView.vue:229` | `issueKeys.slice(0, 8)` + `> 8` | 8 条 | 发布冲突卡片列出的冲突键条数 | 硬编码 | 裸数字 | 与 N-FE-035/036/119 的 5 条口径并存，建议统一 | F2-06 |
| N-FE-035 | 上限 | `frontend/src/stores/task.ts:178`（文案 `:180-182`） | `issues.slice(0, 5)` | 5 条 | 发布冲突明细摘要（回灌模型 + 卡面 reason 文案）列出条数 | 硬编码 | 部分（注释说明与 resultView 口径一致） | 保留；与 N-FE-036 同源 | —（新） |
| N-FE-036 | 上限 | `frontend/src/utils/resultview.ts:47`（消费 `:364`） | `PUBLISH_CONFLICT_SHOWN_ROWS` | 5 行 | 发布冲突结构化表格只展示前 5 条 | 硬编码 | 部分（注释称与旧摘要及模型侧一致） | 保留 | —（新） |
| N-FE-037 | 上限 | `frontend/src/utils/resultview.ts:30`（消费 `:302`、`:307`） | `RESULTVIEW_LIMITS.maxRows` | 200 行 | 结果视图（`resultView` table）渲染行数上限，超出置 `partial` 并记 truncated | 硬编码 | 有（与后端 `ResultViews` 同值） | 保留 | —（新） |
| N-FE-038 | 上限 | `frontend/src/utils/resultview.ts:30`（消费 `:284`） | `RESULTVIEW_LIMITS.maxColumns` | 50 列 | 结果视图列数上限（超出裁列） | 硬编码 | 有（与后端同值） | 保留 | —（新） |
| N-FE-039 | 上限 | `frontend/src/utils/resultview.ts:30`（消费 `:289`、`:326`） | `RESULTVIEW_LIMITS.maxBytes` | 262144 字节（256 KiB） | 结果视图序列化体字节上限，超出整卡回退 fallback 文本 | 硬编码 | 有（与后端同值） | 保留 | —（新） |

---

## 4. AI store（会话 / 轮次 / 镜像 / 所有权）

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-040 | 缓存 | `frontend/src/stores/ai.ts:265`（消费 `:270-275`） | `RUN_SEQ_MAX_RUNS` | 16 个 runId | 每会话「已见最大帧序号」台账的 runId 保留窗口，超出丢最旧 | 硬编码 | 部分（只说明「长会话不回收」问题） | 保留；可观测是否够覆盖重挂窗口 | —（新） |
| N-FE-041 | 超时 | `frontend/src/stores/ai.ts:295`（消费 `:5857-5860`） | `GATE_SYNC_GRACE_MS` | 3000 ms | 409 幂等拒绝后等真实裁决的首次宽限，到点拉 `/runs/current` 校正 | 硬编码 | 有（对照挂起心跳 2s + 同锁发帧的投递上界） | 保留 | —（新） |
| N-FE-042 | 重试 | `frontend/src/stores/ai.ts:304`（消费 `:5860`、`:5916`） | `GATE_SYNC_MAX_ATTEMPTS` + `2 ** attempt` | 3 次 / 3s·6s·12s（合计 21s） | 409 后权威校正的有界退避；用尽即解锁并如实提示 | 硬编码 | 有（注释给出 3/6/12 合计 21s 的算式） | 保留；退避算式与次数耦合，改一处须复核总量 | —（新） |
| N-FE-043 | 节流 | `frontend/src/stores/ai.ts:335`（消费 `:3127`、`:364`） | `MIRROR_THROTTLE_MS` | 300 ms | 会话镜像写 sessionStorage 的节流窗口（兼 `requestIdleCallback` timeout） | 硬编码 | 裸数字（单值双用，注释只述机制） | 建议拆「节流窗口」与「idle 兜底超时」两义 | F1-26 |
| N-FE-044 | 重试 | `frontend/src/stores/ai.ts:375`（消费 `:5325`） | `PRE_STREAM_RETRY_MAX` | 1 次（共 2 次尝试） | 开流前零帧瞬时失败的重试次数 | 硬编码 | 部分（判据写得很细，次数 1 无独立论证） | 保留 | —（新） |
| N-FE-045 | 退避 | `frontend/src/stores/ai.ts:377`（消费 `:5335`） | `PRE_STREAM_RETRY_DELAY_MS` | 400 ms | 开流前重试等待 | 硬编码 | 部分（「给抖动恢复窗口、不让用户觉得卡住」） | 保留 | —（新） |
| N-FE-046 | 重试 | `frontend/src/stores/ai.ts:386`（消费 `:3018-3083`） | `AUTO_RECOVERY_MAX_ATTEMPTS` | 2 次/类 | 四类恢复（preparing / first-frame / history / purged）共用的自动尝试预算 | 硬编码 | 部分（只说明「可证明零副作用」才自动） | 保留；四类共用同一预算的可调性偏弱 | —（新） |
| N-FE-047 | 退避 | `frontend/src/stores/ai.ts:387`（消费 `:3045`、`:3084`） | `AUTO_RECOVERY_DELAY_MS` | 400 ms | 自动恢复两次尝试的间隔 | 硬编码 | 部分（同上） | 保留 | —（新） |
| N-FE-048 | 超时 | `frontend/src/stores/ai.ts:461`（消费 `:493`） | `STOP_SETTLE_MAX_WAIT_MS` | 2000 ms | 同 tick「停止 + 发送」时，新发送等 cancel 送达的上限 | 硬编码 | 部分（有界性论证充分，2s 无数据） | 保留 | —（新） |
| N-FE-049 | TTL | `frontend/src/stores/ai.ts:469`（消费 `:7782`） | `STOP_INTENT_TTL_MS` | 60000 ms | start 帧之前发出的「停止意向」有效期；超时作废 | 硬编码 | 部分（只说明「不拿旧意向取消新轮」） | 与 `RETURN_INTENT_TTL_MS` 同值不同义，勿合并 | —（新） |
| N-FE-050 | 上限 | `frontend/src/stores/ai.ts:511`（消费 `:4517`） | `RUN_STREAM_DRIVE_BUDGET` | 1 个生命周期 | 同一 runId 的续流驱动总预算（每次实连接内部自带 4 次重连） | 硬编码 | 有（M08-R2 真机 trace：两份预算=10 次请求的实测） | 保留；它把 SSE 重连预算「按 runId 而非连接」记账 | —（新） |
| N-FE-051 | 重试 | `frontend/src/stores/ai.ts:834`（消费 `:1181-1190`） | `LOCK_PROBE_ATTEMPTS` | 3 次 | 会话所有权锁的刷新探活次数 | 硬编码 | 部分（只说明「旧文档锁可能未释放」） | 保留 | —（新） |
| N-FE-052 | 退避 | `frontend/src/stores/ai.ts:835`（消费 `:1190`） | `LOCK_PROBE_RETRY_MS` | 150 ms | 锁探活两次之间等待（3×150ms=450ms 总量） | 硬编码 | 裸数字 | 与次数一起构成 450ms 窗口，建议写明 | —（新） |
| N-FE-053 | 超时 | `frontend/src/stores/ai.ts:841`（消费 `:1228`、`:8733`） | `OWNERSHIP_RELEASE_GRACE_MS` | 1500 ms | 副本收到 `released` 后接手宽限；`pagehide` 后延迟释放锁 | 硬编码 | 部分（注释明确「只是常见刷新时长折中，不是正确性证明」） | 保留；正确性由 Web Locks 裁决 | —（新） |
| N-FE-054 | TTL | `frontend/src/stores/ai.ts:845`（消费 `:1164`） | `RETURN_INTENT_TTL_MS` | 60000 ms | 慢重载「返回意向」的有效期，过期不再区分原页签/复制页签 | 硬编码 | 部分 | 同上，勿与 STOP_INTENT 合并 | —（新） |
| N-FE-055 | 缓存 | `frontend/src/stores/ai.ts:1582`（消费 `:1734`） | `MIRROR_MAX_MESSAGES` | 120 条 | 会话镜像保留的最近消息条数 | 硬编码 | 部分（有界目标明确，120 无来源） | **重点待裁**：与后端记忆窗口 40 条的关系未说明 | —（新） |
| N-FE-056 | 上限 | `frontend/src/stores/ai.ts:1584`（消费 `:1742`） | `MIRROR_MAX_CHARS` | 524288 字符（512K UTF-16） | 镜像消息 JSON 总量预算（从最旧丢，末条不受限） | 硬编码 | 部分（与 sessionStorage 配额同单位，未给配额余量算式） | 建议核对浏览器配额与「末条不受限」的最坏溢出 | —（新） |
| N-FE-057 | 上限 | `frontend/src/types/api.ts:251`（消费 `stores/ai.ts:7598`） | `STRUCTURED_RESULT_MAX_CHARS` | 8000 字符 | 生成式表单结构化回灌体发送前预检；超限零请求、表单保持可编辑 | 硬编码 | 有（与后端 `GenerativeFormRules.MAX_RESULT_CHARS`、`callback.max-chars` 同尺） | 保留 | —（新） |
| N-FE-058 | 版本 | `frontend/src/types/ai.ts:946` | `MIRROR_VERSION` | 2 | 会话镜像结构版本（读侧据此判兼容） | 硬编码 | 裸数字（版本号，无演进说明在常量处） | 版本号不属行为阈值，登记备查 | —（新） |
| N-FE-059 | 版本 | `frontend/src/types/tools.ts:488` | `WORKSPACE_CONTRACT_VERSION` | 1 | AI 面板 ↔ 工作区 `ui_event` 契约版本 | 硬编码 | 裸数字 | 保留；与后端契约版本同改 | F1-17 |
| N-FE-060 | 上限 | `frontend/src/types/tools.ts:586`（消费 `stores/workspace.ts:366`） | `WORKSPACE_ACTION_LIMIT` | 5 条 | 每轮对话携带的最近工作区动作条数（`slice(-5)`） | 硬编码 | 部分（只说明「避免上下文膨胀」） | 保留；可考虑与 token 预算挂钩 | F1-16 |

---

## 5. AiPanel（对话面板）行为阈值

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-061 | 上限 | `frontend/src/components/AiPanel/AiPanel.vue:1816`（消费 `:1247`、`:2297`、`:2306`） | `MESSAGE_MAX_CHARS` | 8192 字符（UTF-16 码元） | 输入框 `maxlength`、发送闸、剩余字数提示；与后端 `app.ai.input.max-chars` 同尺 | 硬编码 | 有（注释写明判据与后端同值同尺） | 保留；后端改键须同改 | —（新） |
| N-FE-062 | 阈值 | `frontend/src/components/AiPanel/AiPanel.vue:1838`（消费 `:1898`、`:1905`） | `FOLLOW_THRESHOLD_PX` | 32 px | 流式内容「钉底跟随」的解除/恢复闸（离底小于它视为贴底） | 硬编码 | 部分（R-161 拆阈值有说明，32px 本身无来源） | 保留；与浮钮滞回带（N-FE-063/064）联动 | —（新） |
| N-FE-063 | 阈值 | `frontend/src/components/AiPanel/AiPanel.vue:1840` | `SHOW_BACK_TO_BOTTOM_PX` | 160 px | 「回到底部」浮钮显示阈值（用户 2026 直裁由 64 放宽） | 硬编码 | 有（注释记录裁决与理由：一屏内轻微上滑不弹钮） | 保留 | —（新） |
| N-FE-064 | 阈值 | `frontend/src/components/AiPanel/AiPanel.vue:1842` | `HIDE_BACK_TO_BOTTOM_PX` | 32 px | 浮钮隐藏阈值（回到贴底才收，与跟随闸同值） | 硬编码 | 有（同上） | 保留；同值不同义于 N-FE-062，勿合并 | —（新） |
| N-FE-065 | 防抖 | `frontend/src/components/AiPanel/AiPanel.vue:1856`（消费 `:4598`） | `PIN_SETTLE_MS` | 150 ms | 钉底后 scroll 事件只允许恢复跟随的静默窗，防「自动跟随把自己停掉」 | 硬编码 | 部分（场景论证详细，150ms 无来源） | 保留 | —（新） |
| N-FE-066 | 阈值 | `frontend/src/components/AiPanel/AiPanel.vue:1858` | `USER_INTENT_MS` | 300 ms | wheel/touch/键盘滚动意图的保鲜期 | 硬编码 | 部分 | 与 PIN_SETTLE_MS 同屏协作，建议成对说明 | —（新） |
| N-FE-067 | 防抖 | `frontend/src/components/AiPanel/AiPanel.vue:1922`（消费 `:1948`） | `SMOOTH_BACK_MS` | 600 ms | 「回到底部」平滑滚动类挂载时长（到点移除类） | 硬编码 | 部分（demo 口径） | 保留 | —（新） |
| N-FE-068 | 阈值 | `frontend/src/components/AiPanel/AiPanel.vue:1966`（消费 `:2107`） | `USER_MSG_MAX_HEIGHT_PX` | 184 px | 用户长消息折叠判据（自然高 >184px 出现展开/收起），同时是折叠限高 | 硬编码 | 有（8 行 × 13.5px × 1.7 ≈ 23px/行 的算式 + 用户终裁 200→184） | 保留；布局型行为阈值，按口径纳入 | —（新） |
| N-FE-069 | 防抖 | `frontend/src/components/AiPanel/AiPanel.vue:2363` | `COMPOSITION_END_GUARD_MS` | 30 ms | compositionend 后仍视为 IME 确认窗口（Safari 先 end 后 Enter） | 硬编码 | 部分（只说明浏览器差异） | 保留；可加 Safari 版本实测 | —（新） |
| N-FE-070 | 缓存 | `frontend/src/components/AiPanel/AiPanel.vue:2753`（消费 `:2820`） | `MD_HISTORY_MAX_BLOCKS` | 256 块 | 历史消息 Markdown 渲染缓存块上限（FIFO） | 硬编码 | 部分（有界目标明确） | 保留 | —（新） |
| N-FE-071 | 缓存 | `frontend/src/components/AiPanel/AiPanel.vue:2754`（消费 `:2820`） | `MD_HISTORY_MAX_BYTES` | 8388608 字符（8M UTF-16） | 历史 Markdown 缓存字节预算 | 硬编码 | 部分 | 与 N-FE-072/073 成对；建议说明与内存目标 | —（新） |
| N-FE-072 | 缓存 | `frontend/src/components/AiPanel/AiPanel.vue:2757`（消费 `:2767`） | `MD_STREAM_MAX_BLOCKS` | 64 块 | 流式 Markdown 缓存块上限 | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-073 | 缓存 | `frontend/src/components/AiPanel/AiPanel.vue:2758`（消费 `:2767`） | `MD_STREAM_MAX_BYTES` | 2097152 字符（2M UTF-16） | 流式 Markdown 缓存字节预算 | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-074 | 阈值 | `frontend/src/components/AiPanel/AiPanel.vue:3099`（消费 `:3256`、`:3398`） | `MMD_Z_MIN` | 0.5× | mermaid 预览/全屏缩放下限 | 硬编码 | 部分（demo 同口径） | 保留 | —（新） |
| N-FE-075 | 阈值 | `frontend/src/components/AiPanel/AiPanel.vue:3100`（消费 `:3256`、`:3398`） | `MMD_Z_MAX` | 4× | mermaid 预览/全屏缩放上限 | 硬编码 | 部分（demo 同口径） | 保留 | —（新） |
| N-FE-076 | 其他 | `frontend/src/components/AiPanel/AiPanel.vue:1325 / :1336` | `1 / 1.25`、`1.25` | 每档 ±25%（×0.8 / ×1.25） | mermaid 缩放按钮步长 | 硬编码 | 裸数字 | 建议提取常量，与 0.5/4 范围同处 | —（新） |
| N-FE-077 | 其他 | `frontend/src/components/AiPanel/AiPanel.vue:3081 / :3493 / :4911` | `2000`（三处） | 2000 ms | 复制成功绿勾/「已复制」反馈的展示时长（DOM 钮 / mermaid 查看器 / 消息级） | 硬编码 | 裸数字 | 三处同义重复，建议收敛为一个常量 | —（新） |
| N-FE-078 | 其他 | `frontend/src/components/AiPanel/AiPanel.vue:2658-2661` | `300` | 300 ms | 助手消息流式结束后的淡出时长；新 delta 到达可打断 | 硬编码 | 裸数字 | 保留；与 CSS 过渡时长无绑定说明 | —（新） |
| N-FE-079 | 上限 | `frontend/src/components/AiPanel/AiPanel.vue:4719` | `m.content.slice(0, 200)` | 200 字符 | 反馈上报携带被评价消息正文摘要（人工复盘） | 硬编码 | 部分（注释说明用途） | 建议与后端反馈表字段长度对齐说明 | —（新） |
| N-FE-080 | 上限 | `frontend/src/components/AiPanel/AiPanel.vue:4737` | `rows.slice(0, 3)` | 3 行 | 生成式表单「已提交」摘要最多列出的字段数 | 硬编码 | 裸数字 | 保留 | —（新） |
| N-FE-081 | 截断 | `frontend/src/components/AiPanel/AiPanel.vue:2159`；`frontend/src/views/AiFeedbackView.vue:83-84` | `slice(0, 8)` | 8 字符 | 会话 id / 反馈条目 sessionId+messageId 的显示片段（非数据截断） | 硬编码 | 裸数字 | 仅展示用；不需要与后端 id 格式耦合 | —（新） |

---

## 6. UI 操作原语（`utils/ai-dom-tools.ts`）

> 这些常量同时是发给模型的工具描述里的硬顶（`types/tools.ts` 的 description 逐字复述），属于「模型可见预算」。

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-082 | 上限 | `frontend/src/utils/ai-dom-tools.ts:53`（消费 `:571`） | `UI_SNAPSHOT_DEFAULT_ROWS` | 20 行 | `ui_snapshot` 缺省回几行 | 硬编码 | 部分（设计稿 §3.7；R3 同值） | 与 N-FE-083 的 40 形成「默认/硬顶」对 | —（新） |
| N-FE-083 | 上限 | `frontend/src/utils/ai-dom-tools.ts:55`（消费 `:574-577`、`:948`） | `UI_SNAPSHOT_MAX_ROWS` | 40 行 | `ui_snapshot` 的 `limit` 硬顶（>40 直接参数不合法） | 硬编码 | 部分（设计稿 §3.7） | 保留 | —（新） |
| N-FE-084 | 上限 | `frontend/src/utils/ai-dom-tools.ts:57`（消费 `:957-973`） | `UI_SNAPSHOT_MAX_CHARS` | 2000 字符 | 快照整段硬顶（控件行优先，文本段共享余量） | 硬编码 | 部分（「不可破」但 2000 无来源） | 保留；与 sheet 工具 2000 同值不同义 | —（新） |
| N-FE-085 | 上限 | `frontend/src/utils/ai-dom-tools.ts:59`（消费 `:709-764`） | `UI_SNAPSHOT_LINE_MAX_CHARS` | 120 字符 | 快照单行字符上限（含 ref/role/state 前缀预算） | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-086 | 上限 | `frontend/src/utils/ai-dom-tools.ts:61`（消费 `:542`、`:1517-1518`） | `UI_SNAPSHOT_VALUE_MAX_CHARS` | 24 字符 | 快照回显值截断、填值回读比对文本截断 | 硬编码 | 部分 | 与 UI_LABEL_MAX_CHARS 同值不同义 | —（新） |
| N-FE-087 | 上限 | `frontend/src/utils/ai-dom-tools.ts:63`（消费 `:1170-1171`） | `UI_WRITE_VERBS_PER_TURN` | 12 次 | 单轮写动词配额；超出返回「请拆成多轮」（前端侧闸） | 硬编码 | 部分（与后端 Guard 同源口径） | 保留；与后端配额是否同值需后端面确认 | —（新） |
| N-FE-088 | 上限 | `frontend/src/utils/ai-dom-tools.ts:65` | `UI_UNDO_DEPTH` | 1 层 | 注释称「撤销深度」，但**全仓无消费点**（实际撤销栈为 1 的硬实现） | 硬编码 | 裸数字 | 建议删除或接线，避免「声明 1 层」成为死配置 | —（新） |
| N-FE-089 | 节流 | `frontend/src/utils/ai-dom-tools.ts:67`（消费 `:1159`） | `UI_WRITE_THROTTLE_MS` | 80 ms | 两次写动词之间的最小间隔（前端侧节流） | 硬编码 | 裸数字 | 建议与后端 Guard 节流值对照 | —（新） |
| N-FE-090 | 上限 | `frontend/src/utils/ai-dom-tools.ts:69`（消费 `:316-327`） | `UI_LABEL_MAX_CHARS` | 24 字符 | 快照 label 截断（data-ai-label/aria/title/text 回退） | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-091 | 上限 | `frontend/src/utils/ai-dom-tools.ts:71`（消费 `:1487-1488`） | `UI_FILL_VALUE_MAX_CHARS` | 200 字符 | `ui_fill` 写入值上限（超限拒绝执行） | 硬编码 | 部分（工具描述同步） | 保留；与表单 TEXT 200 同值不同义 | —（新） |
| N-FE-092 | 上限 | `frontend/src/utils/ai-dom-tools.ts:73`（消费 `:1640-1641`） | `UI_SCROLL_DELTA_MAX` | ±2000 px | `ui_scroll` 单次像素增量上界 | 硬编码 | 部分（工具描述同步） | 保留 | —（新） |
| N-FE-093 | 上限 | `frontend/src/utils/ai-dom-tools.ts:75`（消费 `:1664-1665`、`:1709`） | `UI_HIGHLIGHT_NOTE_MAX_CHARS` | 60 字符 | `ui_highlight` note 上限与徽标文本截断 | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-094 | 上限 | `frontend/src/utils/ai-dom-tools.ts:77`（消费 `:1855-1861`） | `UI_UPLOAD_NAME_MAX_CHARS` | 64 字符 | 上传交接卡文件名回显上限（保留扩展名截断中段） | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-095 | 上限 | `frontend/src/utils/ai-dom-tools.ts:84`（消费 `:896`、`:969`） | `UI_SNAPSHOT_TEXT_MAX_CHARS` | 600 字符 | 区域内只读文本段整段硬顶（头行+正文+截断标记） | 硬编码 | 部分（有「可逐字复核」的口径说明） | 保留 | —（新） |
| N-FE-096 | 上限 | `frontend/src/utils/ai-dom-tools.ts:86`（消费 `:900-910`） | `UI_SNAPSHOT_TEXT_MAX_LINES` | 12 行 | 文本段正文行数硬顶 | 硬编码 | 部分 | 保留；与 600 字符双约束 | —（新） |
| N-FE-097 | 上限 | `frontend/src/utils/ai-dom-tools.ts:88`（消费 `:757`） | `UI_SNAPSHOT_TEXT_SCAN_MAX_CHARS` | 20000 字符 | 文本扫描累计上限（超大区域停止采集，防拖垮遍历） | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-098 | 防抖 | `frontend/src/utils/ai-dom-tools.ts:90`（消费 `:1217`、`:1266`、`:1613` 文案） | `UI_ACTION_SETTLE_MS` | 500 ms | 动作后短等待上限（等 DOM 稳定 / 等下拉选项出现），也是 `waitUntil` 缺省超时 | 硬编码 | 部分（批 C / D11-② 有口径说明） | 保留；单值三用（等待上限/缺省/文案） | —（新） |
| N-FE-099 | 防抖 | `frontend/src/utils/ai-dom-tools.ts:92`（消费 `:1218`、`:1251`） | `UI_ACTION_SETTLE_QUIET_MS` | 30 ms | DOM 变更后静默多久视为稳定（MutationObserver 口径） | 硬编码 | 裸数字 | 建议说明 30ms 与一帧（16.7ms）的关系 | —（新） |
| N-FE-100 | 轮询 | `frontend/src/utils/ai-dom-tools.ts:94`（消费 `:1302`） | `UI_ACTION_POLL_MS` | 25 ms | 短等待内的兜底轮询间隔（观察器之外的第二道保证） | 硬编码 | 裸数字 | 保留 | —（新） |
| N-FE-101 | 上限 | `frontend/src/utils/ai-dom-tools.ts:1612` | `candidates.slice(0, 10)` | 10 项 | `ui_select` 失败时回列出的可选下拉项条数 | 硬编码 | 裸数字 | 保留；仅文案体积控制 | —（新） |
| N-FE-102 | 其他 | `frontend/src/utils/ai-dom-tools.ts:1719` | `2500` | 2500 ms | AI 高亮徽标（`ui_highlight`）的自动清除延时 | 硬编码 | 裸数字 | 建议与 UI_ACTION_SETTLE_MS 族同处声明 | —（新） |

---

## 7. 表格工具（`utils/sheet-tools.ts`）与表单 schema

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-103 | 上限 | `frontend/src/utils/sheet-tools.ts:27`（消费 `:236`、`:256`、`:881`、`:1305`、`:1379`） | `SHEET_TOOL_MAX_SAMPLE_ROWS` | 20 行 | 读表/检索/校验/归一化等工具的采样行硬顶（`limit` 1..20） | 硬编码 | 部分（设计稿 §3.3；工具描述同步） | 保留；与 UI 快照 40 行是两套预算 | —（新） |
| N-FE-104 | 上限 | `frontend/src/utils/sheet-tools.ts:29`（消费 `:256`、`:852`、`:889`） | `SHEET_TOOL_MAX_COLUMNS` | 5 列 | 投影列数硬顶（缺省投影 = 主键 + 前 4 列；选区路径不适用） | 硬编码 | 部分（方案 §8 K-11 裁决） | 保留；注意 N-FE-124 的裸字面量重复 | —（新） |
| N-FE-105 | 上限 | `frontend/src/utils/sheet-tools.ts:30`（消费 `:1303`、`:1377`） | `SHEET_TOOL_DEFAULT_SAMPLE_ROWS` | 5 行 | `sheet_search` / `sheet_validate` 的缺省采样行数 | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-106 | 上限 | `frontend/src/utils/sheet-tools.ts:31`（消费 `:1102`、`:1828`） | `SHEET_AGGREGATE_MAX_GROUPS` | 20 组 | 分组聚合的统计组数上限（超出折叠为「其余 N 组」） | 硬编码 | 部分 | 保留；与 20 行采样同值不同义 | —（新） |
| N-FE-107 | 上限 | `frontend/src/utils/sheet-tools.ts:32`（消费 `:1673`、`:2015`、`:2150`、`:2439`） | `SHEET_PREVIEW_SAMPLE_PAIRS` | 3 对 | 写工具预览卡的「旧值→新值」样例对数；也作小改动的判定门 | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-108 | 上限 | `frontend/src/utils/sheet-tools.ts:33`（消费 `:1661`） | `SHEET_WRITE_SMALL_DIFF_MAX_CELLS` | 2000 格 | 「小改动」档的格数上界；与 200 行阈值共同决定预览档 `small/large` | 硬编码 | 部分（治理批口径；2000 无来源） | **重点待裁**：2000 格 / 200 行双阈值无推导 | —（新） |
| N-FE-109 | 阈值 | `frontend/src/utils/sheet-tools.ts:34`（消费 `:1661`、`:1745`） | `SHEET_WRITE_LARGE_ROW_THRESHOLD` | 200 行 | 超过即判 `large`，卡片要求勾选「我了解影响范围」 | 硬编码 | 部分 | 与 N-FE-108 成对复核 | —（新） |
| N-FE-110 | 上限 | `frontend/src/utils/sheet-tools.ts:28`（消费 `:296`、`:313-329`） | `SHEET_TOOL_MAX_RESULT_CHARS` | 2000 字符 | 表格工具回灌文本的字符闸（与行闸取先触发者，末尾恒带「共 N」） | 硬编码 | 部分（有「行优先于字符」的口径，2000 无来源） | 保留；与 UI_SNAPSHOT_MAX_CHARS 同值不同义 | —（新） |
| N-FE-111 | 上限 | `frontend/src/utils/sheet-tools.ts:35`（消费 `:1833`、`:2030-2036`、`:2255-2295`、`:2564-2570`） | `SHEET_WRITE_HARD_MAX_CELLS` | 100000 格 | 写工具绝对硬上限，超过即拒绝（扫描中途也中止） | 硬编码 | 部分（有拒绝文案与中止口径） | 保留；与前端工具 60s 预算的关系未说明 | —（新） |
| N-FE-112 | 上限 | `frontend/src/utils/sheet-tools.ts:36`（消费 `:760`） | `SHEET_FINGERPRINT_COLUMNS` | 2 列 | 表格指纹取前 N 个键列 | 硬编码 | 裸数字 | 保留 | —（新） |
| N-FE-113 | 上限 | `frontend/src/utils/sheet-tools.ts:47`（消费撤销栈） | `SHEET_UNDO_DEPTH` | 3 层 | AI 表格改动的撤销栈深度（FIFO 丢栈底，回执照实报剩余层数） | 硬编码 | 有（2026-10-22 裁决：3 覆盖三步、5 会语义失真） | 保留 | —（新） |
| N-FE-114 | 缓冲 | `frontend/src/utils/sheet-tools.ts:48`（消费 `:804`） | `SHEET_WRITE_CHUNK_ROWS` | 500 行 | 批量写按行切块（一次 `suspendPaint` 内批量落值） | 硬编码 | 部分（只说明切块目的） | 保留；建议给单块耗时实测 | —（新） |
| N-FE-115 | 上限 | `frontend/src/utils/sheet-tools.ts:50`（消费 `:1873`） | `SHEET_DISTRIBUTION_SHOW_GROUPS` | 5 组 | 计数档分布显示组数（超出折叠「其余 N 组」） | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-116 | 缓冲 | `frontend/src/utils/sheet-tools.ts:52`（消费 `:562-563`） | `SHEET_READ_BATCH_ROWS` | 500 行 | 批量读一段一次 `getArray` 的行数（中止粒度） | 硬编码 | 部分（对齐业界 batchGet） | 保留 | —（新） |
| N-FE-117 | 上限 | `frontend/src/utils/sheet-tools.ts:1974`（同类字面量 `:2090`、`:2357`） | `columnCodes.length > 5` / `set.length > 5` | 5 列 / 5 条 | 替换/更新/归一化工具的参数列数上限——**裸字面量重复**，未引用 `SHEET_TOOL_MAX_COLUMNS` | 硬编码 | 裸数字（与 N-FE-104 同值，但有漂移风险） | 建议改用常量，避免两处不同步 | —（新） |
| N-FE-118 | 上限 | `frontend/src/utils/sheet-tools.ts:2369-2372` | `args.rules.length > 3` | 3 条 | 列归一化一次最多接受的规则条数 | 硬编码 | 裸数字 | 建议与列上限一起常量化 | —（新） |
| N-FE-119 | 上限 | `frontend/src/utils/sheet-tools.ts:1288-1290`（另 `:2478`） | `suggestions.slice(0, 5)` / `diff.changes.slice(0, 5)` | 5 条 | 表头映射建议只列前 5 条；归一化预览样例 5 条 | 硬编码 | 裸数字 | 与 N-FE-036/035 的 5 条并存，建议统一「样例条数」口径 | —（新） |
| N-FE-120 | 上限 | `frontend/src/utils/sheet-tools.ts:916-920` | `columns.slice(0, 8)` / `Math.min(8, …)` | 8 列 | 表结构摘要里列清单与空值统计只列前 8 列（文案注明余量） | 硬编码 | 裸数字 | 保留；仅文案体积控制 | —（新） |
| N-FE-121 | 上限 | `frontend/src/types/form-schema.ts:98`（消费 `:344`） | `MAX_FORM_FIELDS` | 20 个 | 生成式表单字段数上限（前端先校验，与后端同名同值） | 硬编码 | 有（注释与后端同源） | 保留 | —（新） |
| N-FE-122 | 上限 | `frontend/src/types/form-schema.ts:99`（消费 `:143`） | `MAX_FORM_OPTIONS` | 50 个 | 单字段枚举选项数上限 | 硬编码 | 有（同上） | 保留 | —（新） |
| N-FE-123 | 上限 | `frontend/src/types/form-schema.ts:100`（消费 `FormRenderer.vue:69`、`:885`） | `MAX_TEXT_VALUE_CHARS` | 200 字符 | 短文本字段 `maxlength` 与校验上限 | 硬编码 | 有（与后端同值） | 保留 | —（新） |
| N-FE-124 | 上限 | `frontend/src/types/form-schema.ts:101`（消费 `FormRenderer.vue:53`、`:885`） | `MAX_LONGTEXT_VALUE_CHARS` | 2000 字符 | 长文本字段 `maxlength` 与校验上限 | 硬编码 | 有（同上） | 保留 | —（新） |
| N-FE-125 | 上限 | `frontend/src/types/form-schema.ts:103`（消费 `:351`） | `KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_]{0,39}$/` | 1+39 = 40 字符 | 表单字段键长度与形态（与后端 `KEY_PATTERN` 同口径） | 硬编码 | 有（注释同源） | 保留 | —（新） |
| N-FE-126 | 上限 | `frontend/src/types/form-schema.ts:104` | `ATTACHMENT_ID_PATTERN = /^att_[A-Za-z0-9_-]{8,64}$/` | 8..64 字符 | 附件 fileId 形态校验 | 硬编码 | 部分（与后端契约同源未注明） | 建议注明与后端附件 id 发号规则的关系 | —（新） |

---

## 8. Markdown 渲染、缓存与结果视图

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-127 | 阈值 | `frontend/src/utils/markdown.ts:108`（消费 `:1042`） | `DEGRADE_AT_CHARS` | 10000 字符 | 单条正文超过即降级（关高亮与 mermaid，结构照渲）；按条短路、不粘滞 | 硬编码 | 部分（决策卡 §三.2「>10k」，10k 本身无推导） | 保留；降级提示按条只出一次 | —（新） |
| N-FE-128 | 超时 | `frontend/src/utils/markdown.ts:110`（消费 `:1223`） | `MERMAID_TIMEOUT_MS` | 3000 ms | 单次 mermaid 渲染的等待预算；到点只释放等待方，不中断同步渲染 | 硬编码 | 部分（失败兜底口径清楚，3s 无实测） | 建议补一次「大图渲染耗时」实测 | —（新） |
| N-FE-129 | 上限 | `frontend/src/utils/markdown.ts:112`（消费 `:1164`） | `MERMAID_MAX_TEXT_SIZE` | 30000 字符 | mermaid 图源硬上限（超出回源码卡，防主线程卡死） | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-130 | 上限 | `frontend/src/utils/markdown.ts:114`（消费 `:1165`） | `MERMAID_MAX_EDGES` | 300 条边 | mermaid 边数硬上限 | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-131 | 缓存 | `frontend/src/utils/markdown.ts:116`（消费 `:1228`） | `MERMAID_CACHE_MAX` | 64 条 | mermaid SVG 渲染缓存上限（源码指纹→净化 SVG） | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-132 | 缓存 | `frontend/src/utils/markdown.ts:219`（消费 `:237`） | `HIGHLIGHT_CACHE_MAX` | 200 条 | 代码高亮结果 memo 上限 | 硬编码 | 裸数字 | 建议与流式块预算一起说明内存影响 | —（新） |
| N-FE-133 | 防抖 | `frontend/src/utils/markdown.ts:534`（消费 `:804-806`） | `STREAM_DEBOUNCE_MS` | 75 ms | 流式同一正文块增量的合并重绘窗口（源码注释口径：≤14 次/秒上限） | 硬编码 | 有（注释给出 75ms → ≤14 次/秒的算式） | 保留 | —（新） |
| N-FE-134 | 缓存 | `frontend/src/utils/markdown.ts:536`（消费 `:813`） | `STREAM_RENDER_MAX` | 64 块 | 流式防抖记账的块数上限（FIFO） | 硬编码 | 部分 | 保留；与 N-FE-072 同值不同义 | —（新） |
| N-FE-135 | 缓存 | `frontend/src/utils/markdown.ts:753`（消费 `:775`） | `DEGRADE_NOTICE_MAX` | 256 条 | 降级提示「按条只出一次」的记账上限 | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-136 | 缓存 | `frontend/src/utils/markdown.ts:846`（消费 `:1118`） | `PREFIX_CACHE_MAX` | 16 条 | 已冻结前缀渲染缓存上限 | 硬编码 | 裸数字 | 保留 | —（新） |
| N-FE-137 | 超时 | `frontend/src/utils/markdown.ts:1182`（消费 `:1191`） | `MERMAID_IDLE_TIMEOUT_MS` | 1000 ms | `requestIdleCallback` 的 timeout 兜底（浏览器迟迟不空闲也要开工） | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-138 | 缓存 | `frontend/src/utils/blocks.ts:222`（消费 `:231`） | `RAW_ARGS_CACHE_MAX_ENTRIES` | 128 条 | 工具原始参数 JSON 缓存条数上限 | 硬编码 | 部分（有界目标明确） | 保留 | —（新） |
| N-FE-139 | 缓存 | `frontend/src/utils/blocks.ts:223`（消费 `:231`） | `RAW_ARGS_CACHE_MAX_CHARS` | 262144 字符（256K） | 工具原始参数缓存字符预算 | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-140 | 缓存 | `frontend/src/utils/parts.ts:1157`（消费 `:1170`） | `APPLIED_SEQ_WINDOW` | 64 个序号 | 消息级「已应用帧序号」去重台账窗口（避免 O(n²)） | 硬编码 | 有（重投窗口远小于 64 的论证） | 保留；超出窗口的旧帧重投可能重复应用，登记 | —（新） |
| N-FE-141 | 阈值 | `frontend/src/utils/context-usage.ts:23`（消费 `:57`） | `CONTEXT_TIER_WARN` | 70% | 上下文占用换色第一档（warn） | 硬编码 | 有（设计稿 §3.4(c) 逐字；与后端压缩阈值刻意解耦） | 保留；三档宜同表维护 | —（新） |
| N-FE-142 | 阈值 | `frontend/src/utils/context-usage.ts:25`（消费 `:54`） | `CONTEXT_TIER_HIGH` | 85% | 上下文占用第二档（high） | 硬编码 | 有（同上） | 保留 | —（新） |
| N-FE-143 | 阈值 | `frontend/src/utils/context-usage.ts:27`（消费 `:51`） | `CONTEXT_TIER_DANGER` | 95% | 上下文占用第三档（danger） | 硬编码 | 有（同上） | 保留 | —（新） |
| N-FE-144 | 上限 | `frontend/src/utils/ai-suggestions.ts:24` | `MAX_SUGGESTION_CHARS` | 16 字符 | 单条快捷建议文案上限（窄面板一行一条） | 硬编码 | 部分（只说明「窄面板」） | **全仓仅定义处出现、运行时无消费点**（建议文案并未按它截断）；建议接线或删除 | —（新） |
| N-FE-145 | 上限 | `frontend/src/utils/ai-suggestions.ts:111` | `out.slice(0, 3)` | 3 条 | 每轮建议最多返回条数（保底 2 条） | 硬编码 | 部分（注释说明布局理由） | 保留 | —（新） |
| N-FE-146 | 分页 | `frontend/src/components/AiPanel/SheetPreviewCard.vue:184`（消费 `:190-195`） | `PAGE_SIZE` | 50 条 | 表格改动预览卡的分页大小 | 硬编码 | 裸数字 | 建议与 `SHEET_WRITE_CHUNK_ROWS`/预览档一起说明 | —（新） |
| N-FE-147 | 截断 | `frontend/src/components/AiPanel/SheetPreviewCard.vue:174` | `key.length > 12` / `slice(0, 12)` | 12 字符 | 计数档分布长 key 的显示截断（title 保留全量） | 硬编码 | 裸数字 | 保留；展示层 | —（新） |
| N-FE-148 | 轮询 | `frontend/src/components/AiPanel/CompactionStatus.vue:48` | `setInterval(..., 1000)` | 1000 ms | 压缩状态已用秒数的本地节拍器 | 硬编码 | 部分（只说明「每秒推进」） | 保留 | —（新） |
| N-FE-149 | 轮询 | `frontend/src/components/AiPanel/ReasoningHeader.vue:53` | `setInterval(..., 1000)` | 1000 ms | 思考段秒表的本地节拍器 | 硬编码 | 部分 | 保留 | —（新） |
| N-FE-150 | 超时 | `frontend/src/components/SpreadGrid.vue:171`（消费 `:239-245`） | `READY_TIMEOUT_MS` | 60000 ms | 表格引擎（chunk 拉取 + 初始化）就绪看门狗；到点显示错误并可重试 | 硬编码 | 有（与 axios 上限同族、< 后端 120s 的论证） | 保留；与前端工具预算同值但语义独立 | F1-25 |
| N-FE-151 | 防抖 | `frontend/src/components/SpreadGrid.vue:318-320` | `setTimeout(..., 50)` | 50 ms | `ResizeObserver` 宿主尺寸变化的 `refresh` 防抖 | 硬编码 | 裸数字 | 保留 | F1-24 |
| N-FE-152 | 阈值 | `frontend/src/components/AiPanel/FormRenderer.vue:528`（消费 `:559`） | `FILTER_COMMON_FIELD_COUNT` | 6 个字段 | 生成式表单「常用条件」同屏字段数，其余折叠进高级区 | 硬编码 | 部分（AR-07 / Task 7 Step 3 口径，6 无推导） | 保留 | —（新） |

---

## 9. 上传 / Excel / 时间显示

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-153 | 上限 | `frontend/src/constants/upload.ts:30`（消费 `:72`） | `UPLOAD_MAX_BYTES` | 5242880 字节（5 MiB） | 导入上传单文件预检上限；与后端 `multipart.max-file-size: 5MB` 同口径 | 硬编码 | 有（注释写明 5×1024×1024 与后端对齐） | 保留；后端仍是权威 | —（新） |
| N-FE-154 | 上限 | `frontend/src/components/AiPanel/FormRenderer.vue:320`（消费 `:780`） | `ATTACHMENT_MAX_BYTES` | 5242880 字节（5 MiB） | 生成式表单附件单件上限（本地先拒，后端同口径） | 硬编码 | 有（与后端 `MAX_ATTACHMENT_BYTES` 同口径） | 保留；与 N-FE-153 **同值不同义**（表单附件 vs 导入文件） | —（新） |
| N-FE-155 | 缓冲 | `frontend/src/utils/excel.ts:27`（消费 `:240`、`:345`） | `DEFAULT_SHEET_ROWS` | 200 行 | 新工作簿/模板的默认视口行数（预留空行供录入） | 硬编码 | 裸数字（只注「与 glm 蓝本一致」） | 保留；200 与导入上限无关，建议注明 | F1-30 |
| N-FE-156 | 上限 | `frontend/src/utils/excel.ts:48` | `cleaned.slice(0, 31)` | 31 字符 | 工作表名安全化（Excel 规范限制） | 硬编码 | 有（注释写明 Excel 限制 31 字符） | 保留；平台硬约束 | —（新） |
| N-FE-157 | 阈值 | `frontend/src/utils/excel.ts:315` | `-1e15, 1e15` | ±10^15 | SpreadJS 数值校验器范围（NUMBER 字段） | 硬编码 | 裸数字 | 建议说明为何不是 `Number.MAX_SAFE_INTEGER`（≈9.007e15） | —（新） |
| N-FE-158 | 阈值 | `frontend/src/utils/excel.ts:321-322` | `new Date(2000,0,1)` ~ `new Date(2099,11,31)` | 2000-01-01 ~ 2099-12-31 | SpreadJS 日期校验器可选范围 | 硬编码 | 裸数字 | 建议与业务日期范围（条件/字段定义）对齐说明 | F1-32 |
| N-FE-159 | 其他 | `frontend/src/utils/excel.ts:857` | `setTimeout(..., 3000)` | 3000 ms | 导出 blob 触发下载后延迟 `revokeObjectURL`（给浏览器落盘时间） | 硬编码 | 裸数字 | 保留；与 N-FE-161 的 0ms 行为不一致，建议统一口径 | F1-31 |
| N-FE-160 | 截断 | `frontend/src/utils/excel.ts:156` | `allowed.slice(0, 6)` | 6 个 | 枚举值不合法错误文案里列出的可选值个数 | 硬编码 | 裸数字 | 保留；仅文案体积 | —（新） |
| N-FE-161 | 其他 | `frontend/src/components/AiPanel/AiPanel.vue:3526-3529` | `setTimeout(..., 0)` | 0 ms（下一宏任务） | mermaid SVG `Blob` 下载后立即 revoke | 硬编码 | 裸数字 | 与 N-FE-159 的 3s 延迟策略不一致，建议明确「链接 click 后多久可 revoke」口径 | —（新） |
| N-FE-162 | 超时 | `frontend/src/utils/run-expiry.ts:36`（消费 `AiPanel.vue:1593`） | `RUN_EXPIRY_COUNTDOWN_WINDOW_MS` | 600000 ms（10 分钟） | 进入「读秒」窗口的剩余寿命阈值；之外按 30s 粗粒度刷新 | 硬编码 | 部分（业界口径「<10 分钟读秒」，10min 无本地推导） | 保留 | —（新） |
| N-FE-163 | 阈值 | `frontend/src/utils/run-expiry.ts:96`（消费 `:105`） | `HOUR_MS`（1h） | 3600000 ms | 寿命文案分档：<1h 报分钟、≥1h 报小时（`Math.ceil`） | 硬编码 | 部分（只说明「超过 1 小时只给粗粒度」） | 保留；显示分档，非判定阈值 | —（新） |
| N-FE-164 | 轮询 | `frontend/src/components/AiPanel/AiPanel.vue:1577`（消费 `:1596-1600`） | `RUN_EXPIRY_TICK_MS` | 30000 ms | 长寿命（>10min）运行态时钟的粗粒度刷新节拍 | 硬编码 | 部分（注释说明 24h 量级不逐秒重渲染） | 保留 | F1-28（部分） |
| N-FE-165 | 轮询 | `frontend/src/components/AiPanel/AiPanel.vue:1578`（消费 `:1593-1597`） | `RUN_EXPIRY_COUNTDOWN_TICK_MS` | 1000 ms | 读秒窗口内的本地节拍 | 硬编码 | 部分 | 保留 | F1-28（部分） |

---

## 10. 前端构建 / dev server / 环境样例 / 验证调度

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-166 | 其他 | `frontend/vite.config.ts:10`（消费 `:15`、`:40`） | `DEFAULT_API_BASE = 'http://localhost:8081'` | 端口 8081 | dev server `/api` 代理目标默认（可被 `VITE_API_BASE` 覆盖） | 配置（可覆盖） | 有（注释三条口径：对齐后端端口、必须可覆盖、禁止源码内硬编码端口） | 保留；端口随后端 `server.port` | V-01 |
| N-FE-167 | 其他 | `frontend/vite.config.ts:11`（消费 `:16`、`:36`） | `DEFAULT_DEV_PORT = 5200` | 5200 | 前端 dev server 端口默认（`strictPort`） | 配置（可覆盖） | 部分（只写「默认」） | E2E/本地可能另设端口（`.env.local` 未读，本批未核），登记即可 | V-02 |
| N-FE-168 | 超时 | `frontend/vite.config.ts:45-46` | `timeout: 300000` / `proxyTimeout: 300000` | 300000 ms（各） | dev 代理的 socket / 上游响应上限；SSE 长连接经代理不被提前掐断（生产不继承） | 硬编码 | 裸数字（300s 与后端段预算同值但无关联说明；且 SSE 空闲 45s 远小于它） | 建议写明「只为 dev 长连接，与运行时预算无关」 | V-03 |
| N-FE-169 | 阈值 | `frontend/vite.config.ts:59` | `chunkSizeWarningLimit: 8192` | 8192 kB（Vite 的 chunk 体积单位） | 构建 chunk 体积告警阈值（抬高以避免 SpreadJS ~4.7MB 噪音） | 硬编码 | 部分（注释解释 4096 不够、SpreadJS 单包 ~4.7MB） | 保留；仅构建告警 | V-04 |
| N-FE-170 | 其他 | `frontend/.env.example:4` | `VITE_API_BASE=http://localhost:8081` | 端口 8081 | 示例环境（入库）的代理目标 | 配置 | 部分（注释说明与后端对齐） | 保留 | V-05 |
| N-FE-171 | 其他 | `frontend/.env.example:7` | `VITE_DEV_PORT=5200` | 5200 | 示例环境的 dev 端口 | 配置 | 部分 | 保留 | V-05 |
| N-FE-172 | 重试 | `frontend/scripts/build.mjs:19-27` | 失败后 `rmSync(tmpDir)` → `buildOnce()` 再调一次（由控制流表达，无常量） | 重试 1 次（总尝试 2 次），**无退避（立即重试）** | 唯一构建入口（Windows 禁裸 `vite build`）：首次构建因杀毒软件锁 esbuild 临时文件失败时，清 `.tmp-esbuild` 后重试一次；再失败原样上抛、保持非零退出 | 硬编码 | 有（R-153 卡④ 注释明确「重试恰好一次」且「绝不静默吞错」；杀毒锁文件为真机成因） | 保留；次数与「无等待」都没有常量/注释外的可调面，若后续要加退避需同时定义次数上限 | —（新） |

> `frontend/package.json` 的 `scripts` 仅含命令（`vite` / `vue-tsc --noEmit` / `node scripts/build.mjs` / `vitest run`），**无数字常量**；依赖版本按任务口径排除。`frontend/scripts/build.mjs` 已补扫：除 N-FE-172 的「失败重试恰好一次」外，无等待时长、退避、缓冲、端口、大小上限数字（临时目录 `.tmp-esbuild` 只是路径名；`TMP/TEMP/TMPDIR` 指向它不涉及数值）。`frontend/.env.local` 未读取（敏感）。

### 10.1 向导「定义列表就绪」等待与冷却（跨册复核补充）

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-173 | 超时 | `frontend/src/views/ExportWizardView.vue:595`（消费 `:637-643`） | `DEFINITIONS_READY_TIMEOUT_MS` | 10000 ms | 导出向导编辑区载入前的「定义列表就绪」等待上限：`awaitDefinitionsReady` 在途请求有界等待，到点返回 false（不按 0 字段载入，fail-closed） | 硬编码 | 部分（注释称「与页面就绪等待同量级（10s）」，但现态 `PAGE_READY_TIMEOUT_MS` 已是 8s（N-FE-017）——**口径已分叉**；10s 本身无独立论证） | 与 N-FE-174 同名同值却是两份独立常量；且注释引用的 10s 页面就绪已变 8s，建议对齐 N-FE-017 并抽共享常量 | —（新） |
| N-FE-174 | 超时 | `frontend/src/views/ImportWizardView.vue:575`（消费 `:637-648`） | `DEFINITIONS_READY_TIMEOUT_MS` | 10000 ms | 导入向导同名等待：按**本次请求**计预算（`remaining = timeout - elapsed`），已挂起请求被多次调用时后续只等剩余额度，到点 fail-closed | 硬编码 | 部分（注释详述「按本次请求计」的口径，10s 无独立论证） | 保留；与 N-FE-173 同步调整，或合并为一个共享常量 | —（新） |
| N-FE-175 | 退避 | `frontend/src/views/ImportWizardView.vue:582`（消费 `:611`、`:700-701`） | `DEFINITIONS_RETRY_COOLDOWN_MS` | 5000 ms | 定义列表读取失败后的补请求冷却：冷却期内编辑区载入快速失败，不再为每个 AI 工具调用各发一次 `GET /api/definitions`（挂起/失败时会把 60s 工具预算吃满）；换任务/显式重载重置冷却 | 硬编码 | 部分（注释说明目的与失败形态，5s 无独立论证） | 建议与 N-FE-011（60s 工具预算）、N-FE-174（10s 等待）并列说明节拍关系 | —（新） |

### 10.2 表单文本格式与入口码正则（与 N-FE-125/126 同口径补登）

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-176 | 上限 | `frontend/src/types/form-schema.ts:470`（消费 `:781`） | `DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/` | 年 4 位、月 2 位、日 2 位 | 表单日期字段形态校验（`isIsoDate` = 正则 + `Date.parse` 非 NaN 双判） | 硬编码 | 部分（段注释称「与后端同源判据」，未注明 DATE 具体正则） | 保留；`{4}/{2}` 是 ISO 8601 固定宽度，建议注明同源后端类/正则 | —（新） |
| N-FE-177 | 上限 | `frontend/src/types/form-schema.ts:756`（消费 `:764`） | `EMAIL_PATTERN = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/` | 本地部分 ≥1 字符；TLD ≥2 字母（`{2,}`） | text 字段 `format=email` 的取值校验（`matchesFormat`） | 硬编码 | 部分（同段注释「与后端同源判据」；`{2,}` 无说明） | 建议与后端邮箱规则同源说明；`{2,}` 是最小 TLD 长度经验值 | —（新） |
| N-FE-178 | 上限 | `frontend/src/types/form-schema.ts:757`（消费 `:766`） | `TEL_PATTERN = /^\+?[0-9][0-9\s\-()]{5,19}$/` | 总长 6..20 字符（首字符 1 + 后续 5..19） | text 字段 `format=tel` 的号码形态校验（允许 `+`、空格、`-`、括号） | 硬编码 | 裸数字（5/19 无注释、无后端对照说明） | 建议注明覆盖的号码制式（国际号 + 分隔符），并给后端同源出处 | —（新） |
| N-FE-179 | 上限 | `frontend/src/types/form-schema.ts:759`（消费 `:770`） | `IDCARD_PATTERN`（完整式见本节表注①） | 15 位或 18 位（18 位末位可为 X/x） | text 字段 `format=idcard` 的身份证号校验 | 硬编码 | 部分（15/18 位是国标长度，但常量处无注释引用） | 建议注明 GB 11643 的 15/18 位口径与后端同源 | —（新） |
| N-FE-180 | 上限 | `frontend/src/utils/ui-receipt.ts:38`（消费 `:41-48`） | `UI_CODE_PATTERN = /^UI_[A-Z_]{2,40}$/` | 后缀 2..40 个大写字母/下划线 | 前端工具回执机器码的入口白名单形态（与后端 `FrontendToolResultRequest` 校验正则同源）；非法值归一为 null（不报 400），再由 `UI_CODES` 闭集二次判定 | 硬编码 | 有（注释写明与后端同源 + 非法值丢弃口径） | 保留；长度 2..40 与 `UI_CODES` 闭集是双重约束，建议注明后者为准 | —（新） |

> 同文件其它正则的一致性：`KEY_PATTERN`（`{0,39}`）与 `ATTACHMENT_ID_PATTERN`（`{8,64}`）已登记为 N-FE-125/126；`URL_PATTERN`（`:758`）只有 `[^\s]+`、**无数值量词**，按口径不单列。
>
> 1. **N-FE-179 完整式**：`IDCARD_PATTERN = /^(\d{15}|\d{17}[\dXx])$/`（Markdown 表格单元格内的裸竖线会被当成列分隔符，故正则整式外置到本条；表格里只留常量名）。

### 10.3 Playwright 验证编排（9 份配置：调度 / 端口 / DB / 产品键覆盖 / 视口）

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-181 | 验证阈值 | `frontend/playwright.config.ts:68-71` | `retries` / `workers` / `timeout` / `expect.timeout` | retries 0 次；workers 1；timeout 120000 ms；expect.timeout 15000 ms | 主配置：全部 E2E 的基线调度（单 worker 串行、用例 120s、断言 15s、不重试） | 硬编码 | 部分（`workers=1` 有注释论证：共享后端/Redis/上游桩并行会互扰，P0 全套 ≤15min；retries/timeout/expect 无说明） | 建议补 `retries=0` 与 `expect=15s` 的依据；`timeout` 应与最慢用例实测对齐 | —（新） |
| N-FE-182 | 验证阈值 | `frontend/playwright.hgta.config.ts:35-38` | 同上四项 | retries 0 次；workers 1；timeout 120000 ms；expect.timeout 15000 ms | H/G/T/A 真机域专用配置；文件头声明「单 worker、Chromium、超时」与主配置同口径 | 硬编码 | 部分（有「与主配置同口径」声明，无独立数值论证） | 保留；与 N-FE-181 同值，若主配置调整须同步复核 | —（新） |
| N-FE-183 | 验证阈值 | `frontend/playwright.kqc-compact.config.ts:38-41` | 同上四项 | retries 0 次；workers 1；timeout **180000 ms**；expect.timeout 15000 ms | KQC 压缩档专用配置（`16-kqc-c.spec.ts`） | 硬编码 | 裸数字（180s 相对主配置 120s 的抬升无注释；retries/workers/expect 沿用主配置） | **重点待裁**：为何压缩档需 180s？建议补实测（压缩期等待更久）或回落 120s | —（新） |
| N-FE-184 | 验证阈值 | `frontend/playwright.kqc-special.config.ts:39-42` | 同上四项 | retries 0 次；workers 1；timeout 120000 ms；expect.timeout 15000 ms | KQC 特殊档专用配置（`--app.ai.kb.dir` / `limit-tokens=0` 场景） | 硬编码 | 部分（沿用主配置口径，无独立论证） | 保留 | —（新） |
| N-FE-185 | 验证阈值 | `frontend/playwright.kqc.config.ts:33-36` | 同上四项 | retries 0 次；workers 1；timeout 120000 ms；expect.timeout 15000 ms | KQC 主档专用配置（`16-kqc.spec.ts`） | 硬编码 | 部分（沿用主配置口径） | 保留；与 N-FE-184 同值不同域，勿合并 | —（新） |
| N-FE-186 | 验证阈值 | `frontend/playwright.mdo.config.ts:37-40` | 同上四项 | retries 0 次；workers 1；timeout 120000 ms；expect.timeout 15000 ms | MDO 多实例/降级域配置（multi/degraded 两组串行） | 硬编码 | 部分（沿用主配置口径；文件头只论证两组串行与端口/DB 隔离） | 保留；同文件 `E2E_EXTRA_BACKEND_ARGS` 另设 `frontend-tool-timeout=15s`（见 N-FE-208） | —（新） |
| N-FE-187 | 验证阈值 | `frontend/playwright.mdxy.config.ts:41-44` | 同上四项 | retries 0 次；workers 1；timeout 120000 ms；expect.timeout 15000 ms | MDXY 域专用配置 | 硬编码 | 部分（沿用主配置口径） | 保留 | —（新） |
| N-FE-188 | 验证阈值 | `frontend/playwright.unlock-budget.config.ts:32-35` | 同上四项 | retries 0 次；workers 1；timeout 120000 ms；expect.timeout 15000 ms | 解锁域缩阈值档配置（压 `tool.exec-timeout` / `total-budget` / 前端工具上限） | 硬编码 | 部分（沿用主配置口径；文件头只论证「为什么要拆档」） | 保留；同文件 `E2E_EXTRA_BACKEND_ARGS` 另设 1ms / 8s / 4s（见 N-FE-209…211） | —（新） |
| N-FE-189 | 验证阈值 | `frontend/playwright.vl.config.ts:29-32` | 同上四项 | retries 0 次；workers 1；timeout **180000 ms**；expect.timeout 15000 ms | VL 域外部实例配置（`E2E_EXTERNAL=1`，`12a/12b-vl`） | 硬编码 | 裸数字（180s 抬升无注释） | **重点待裁**：与 N-FE-183 两处 180s 是否同为「外部实例 + 视觉/长链路」的实测结论，建议统一说明 | —（新） |

> **本节口径**：`N-FE-181…189` 为四类调度字段（每文件一行，同值也分行）；`N-FE-190…198` 为各域端口段；`N-FE-199…205` 为 Redis DB 编号；`N-FE-206…211` 为 `--app.*` 命令行产品键覆盖；`N-FE-212` 为浏览器视口。**9 份配置文件里的可执行行为数字至此全部登记**。不在本节口径内的只剩：spec 选择正则中的用例编号（`12[ab]`/`14[abc]`/`16-kqc(-[bc])?`/`17b` 等，属测试选择标识）、断言/造数值（属测试内容，按任务排除）、以及布尔开关（`E2E_SHARED_AI_STACK` 的 `'1'/'0'`、`E2E_EXTERNAL`、`E2E_KQC_COMPACT/SPECIAL`）；注释中引用的产品键默认值见 §15 观察项（非本文件生效值）。

#### 10.3.1 端口段（每文件一行）

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-190 | 端口 | `frontend/playwright.config.ts:21` | `E2E_APP_PORT ?? '18401'` | 18401 | 主配置前端基址端口（`E2E_APP` 未显式给时）；注释声明三件套默认 18399（桩）/18330（后端）/18401（前端），**绝不占用常开的 8081/5202** | 配置（可覆盖） | 有（注释给出「不占常开端口」的对齐口径；18399/18330 由 harness 缺省，不在本文件） | 保留；改端口段须同步 harness 与 CI | —（新） |
| N-FE-191 | 端口 | `frontend/playwright.hgta.config.ts:19-23` | `E2E_BACKEND_PORT` / `E2E_DEGRADED_BACKEND_PORT` / `E2E_STUB_PORT` / `E2E_APP_PORT` / `E2E_DEGRADED_APP_PORT` | 18730 / 18731 / 18799 / 18801 / 18802 | H/G/T/A 域独占 187xx-188xx 端口段（正常栈 + 降级栈 + 桩 + 前端） | 配置（可覆盖） | 部分（文件头只说明「一组环境变量隔离」，端口段本身无逐值论证） | 保留；域名段编号分配建议集中登记，避免跨域撞号 | —（新） |
| N-FE-192 | 端口 | `frontend/playwright.kqc-compact.config.ts:18-22` | 同上五项 | 22930 / 22931 / 22999 / 22901 / 22902 | KQC 压缩档独占 229xx 端口段 | 配置（可覆盖） | 部分（文件头注「端口段 229xx 独立」） | 保留 | —（新） |
| N-FE-193 | 端口 | `frontend/playwright.kqc-special.config.ts:19-23` | 同上五项 | 19730 / 19731 / 19799 / 19801 / 19802 | KQC 降级档独占 197xx-198xx 端口段 | 配置（可覆盖） | 部分（文件头注「端口段 197xx」） | 保留 | —（新） |
| N-FE-194 | 端口 | `frontend/playwright.kqc.config.ts:16-20` | 同上五项 | 21730 / 21731 / 21799 / 21801 / 21802 | KQC 主档独占 217xx-218xx 端口段 | 配置（可覆盖） | 部分（文件头注「端口段 217xx」） | 保留 | —（新） |
| N-FE-195 | 端口 | `frontend/playwright.mdo.config.ts:20-24` | 同上五项 | 21530 / 21531 / 21599 / 21601 / 21602 | MDO 域独占 215xx-216xx 端口段（multi/degraded 两组共用同一组端口，靠串行 + RUN_DIR 隔离） | 配置（可覆盖） | 部分（文件头论证两组串行与目录隔离，端口段本身无数值论证） | 保留 | —（新） |
| N-FE-196 | 端口 | `frontend/playwright.mdxy.config.ts:17-21` | 同上五项 | 21330 / 21331 / 21399 / 21401 / 21402 | MDXY 域独占 213xx-214xx 端口段 | 配置（可覆盖） | 部分（文件头注「独占端口段 21330/21331/21399/21401/21402」） | 保留 | —（新） |
| N-FE-197 | 端口 | `frontend/playwright.unlock-budget.config.ts:14-18` | 同上五项 | 21930 / 21931 / 21999 / 22001 / 22002 | 解锁缩阈值档独占 219xx-220xx 端口段 | 配置（可覆盖） | 部分（文件头注「独占端口段 219xx」） | 保留 | —（新） |
| N-FE-198 | 端口 | `frontend/playwright.vl.config.ts:17-19` | `VL_BACKEND_PORT ?? '18930'` / `VL_APP_PORT ?? '19001'` | 18930（后端）/ 19001（前端） | VL 域自带三件套的基址端口；`E2E_EXTERNAL=1` 时主 harness 不起 183xx 栈，改由 spec 起/复用 VL 栈 | 配置（可覆盖） | 有（文件头论证「自带三件套 + `assertSingleApiBase()` 强制一致」；注释另记桩 18999 / Redis DB 12 由 `helpers-vl.ts` 管理，不在本文件） | 保留；18999 与 DB 12 的取值点在 `e2e/support/helpers-vl.ts`，如需穷举要扩到该文件 | —（新） |

#### 10.3.2 Redis DB 编号（每文件一行）

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-199 | 其他 | `frontend/playwright.hgta.config.ts:24` | `E2E_REDIS_DB ?? '11'` | DB 11（降级栈未单设 DB） | H/G/T/A 域 Redis 逻辑库，与其它域隔离 | 配置（可覆盖） | 裸数字（文件头未提 DB 编号） | 建议在文件头补「DB 11」及与降级栈共库的理由 | —（新） |
| N-FE-200 | 其他 | `frontend/playwright.kqc-compact.config.ts:23-24` | `E2E_REDIS_DB` / `E2E_REDIS_DB_DEGRADED` | DB 14 / 15 | KQC 压缩档主/降级栈 Redis 库 | 配置（可覆盖） | 部分（文件头注「DB 14-15」） | 保留 | —（新） |
| N-FE-201 | 其他 | `frontend/playwright.kqc-special.config.ts:24-25` | 同上 | DB 14 / 15 | KQC 降级档主/降级栈 Redis 库 | 配置（可覆盖） | 部分（文件头注「DB 14-15」） | 保留；与 N-FE-200/202 同值不同档，勿合并 | —（新） |
| N-FE-202 | 其他 | `frontend/playwright.kqc.config.ts:21-22` | 同上 | DB 14 / 15 | KQC 主档主/降级栈 Redis 库 | 配置（可覆盖） | 部分（文件头注「DB 14-15」） | 保留 | —（新） |
| N-FE-203 | 其他 | `frontend/playwright.mdo.config.ts:25-26` | 同上 | DB 12 / 12（主与降级同库） | MDO multi 组 A/B 共享 Redis 的前提；degraded 组虽独立 H2 仍用同一 Redis 库 | 配置（可覆盖） | 部分（文件头论证「multi 组共享 Redis」，同库编号 12 无独立推导） | 保留；两值相同是有意为之，建议注释明示「degraded 亦复用 12」 | —（新） |
| N-FE-204 | 其他 | `frontend/playwright.mdxy.config.ts:22-23` | 同上 | DB 13 / 11 | MDXY 域主/降级栈 Redis 库 | 配置（可覆盖） | 部分（文件头注「Redis DB 13 / 11」） | 保留；降级用 11 与 hgta 主库同号，跨域并发时需确认无冲突（各域串行可接受） | —（新） |
| N-FE-205 | 其他 | `frontend/playwright.unlock-budget.config.ts:19-20` | 同上 | DB 8 / 9 | 解锁缩阈值档主/降级栈 Redis 库 | 配置（可覆盖） | 部分（文件头注「DB 8-9」） | 保留 | —（新） |

#### 10.3.3 产品键命令行覆盖（`E2E_EXTRA_BACKEND_ARGS`，整棒生效）

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-206 | 配置覆盖 | `frontend/playwright.kqc-compact.config.ts:28` | `--app.ai.context.limit-tokens=30000` | 30000 tokens | 压缩档把「上下文分母」压小以让桩的几百 token 在几十轮内触发 70% 压缩；注释要求 > `reserve-tokens`(13000) 且与用例三档窗口 28000/26000/22000 都不同 | 配置（可覆盖） | 有（注释给出 13000 下界与「不得等于测试窗口」的理由；30000 取值本身是窗口之上取整） | 保留；产品键默认 128000 见 §15 | —（新） |
| N-FE-207 | 配置覆盖 | `frontend/playwright.kqc-special.config.ts:29` | `--app.ai.context.limit-tokens=0` | 0（=分母未知） | 制造「上下文分母未知」形态：端点 `limitTokens`/`percent` 返回 null（C-02 用例） | 配置（可覆盖） | 有（注释写明 0 ⇒ 分母未知的语义） | 保留；0 是哨兵值而非容量，建议在登记表口径里保持「0=未知」 | —（新） |
| N-FE-208 | 配置覆盖 | `frontend/playwright.mdo.config.ts:27` | `--app.ai.hitl.frontend-tool-timeout=15s` | 15 s | MDO 域把前端工具挂起上限压到 15s，缩短多实例/降级用例的等待 | 配置（可覆盖） | 部分（只说明该棒整棒生效，15s 无独立论证） | 建议补「为什么是 15s」（要覆盖 M/D/O 的哪条等待链） | —（新） |
| N-FE-209 | 配置覆盖 | `frontend/playwright.unlock-budget.config.ts:22` | `--app.ai.tool.exec-timeout=1ms` | 1 ms | 把后端工具执行上限压到最小，稳定触发「工具超时→解锁」路径（S-09/S-11/A-08） | 配置（可覆盖） | 有（注释论证：默认 120s 等不起、1ms 会压死主档故拆档） | 保留；1ms 属刻意的病态值，勿复制到其它档 | —（新） |
| N-FE-210 | 配置覆盖 | `frontend/playwright.unlock-budget.config.ts:22` | `--app.ai.resilience.total-budget=8s` | 8 s | 把段预算压到 8s，使 1ms 工具超时后的解锁/收口在用例可等范围内 | 配置（可覆盖） | 部分（注释只给默认 300s 的对照，8s 无独立推导） | 建议说明 8s 与 120s 用例上限、1ms 工具超时的关系 | —（新） |
| N-FE-211 | 配置覆盖 | `frontend/playwright.unlock-budget.config.ts:22` | `--app.ai.hitl.frontend-tool-timeout=4s` | 4 s | 前端工具挂起上限压到 4s，配合 8s 段预算验证前端超时回灌路径 | 配置（可覆盖） | 部分（4s 无独立论证；与 8s 段预算/1ms 工具超时的先后关系未写明） | 建议补三值（1ms / 4s / 8s）的先后顺序说明，避免互相遮蔽 | —（新） |

#### 10.3.4 浏览器视口

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用与消费点 | 类型 | 依据 | 审视建议（不修改） | 旧NUMSPEC |
|---|---|---|---|---|---|---|---|---|---|
| N-FE-212 | 验证阈值 | `frontend/playwright.config.ts:91`；`playwright.hgta.config.ts:51`；`playwright.kqc-compact.config.ts:57`；`playwright.kqc-special.config.ts:58`；`playwright.kqc.config.ts:52`；`playwright.mdo.config.ts:56`；`playwright.mdxy.config.ts:60`；`playwright.unlock-budget.config.ts:51`；`playwright.vl.config.ts:48` | `viewport: { width, height }` | 1440 × 900 px | 9 份配置统一的浏览器视口；影响响应式布局、滚动/折叠/虚拟化等依赖视口的断言 | 硬编码 | 部分（无取值论证；且项目级 `use: { ...devices['Desktop Chrome'] }` 内含 1280×720，合并时项目级优先，**实际生效值可能是 1280×720**，需实测确认） | **重点待裁**：先确认 1440×900 是否真的生效（`devices` 展开会覆盖顶层 viewport）；若想固定 1440×900，应在 project 级显式写 viewport 或移除 device 的 viewport | —（新） |


---

## 11. 与旧 NUMSPEC 对账（差异摘要）

对账基准：`docs/evidence/补档-NUMSPEC-清单总账.md` §1.3（F1-01…F1-32）、§1.4（F2-01…F2-07）、§1.5（V-01…V-06）。

### 11.1 值/位置变化

| 旧 ID | 现值（本表） | 变化 |
|---|---|---|
| F1-01 / F1-15 / F1-19 / F1-20 | N-FE-001 / 019 / 011 / 012 | 60s 已收敛为**单一事实源** `HTTP_TIMEOUT_MS`（`utils/time-constants.ts:21`），后三者为同源别名；旧基线「注释式对齐」已消失 |
| F1-23 | N-FE-017 | **3000 → 8000 ms**，仓内无变更说明（旧基线 3s 亦为裸数字） |
| F1-27 | N-FE-015 | 语义改变：确认卡本地 deadline 折算已随「去超时裁决」退役，现仅剩帧 `timeoutSeconds` → 工具预算折算 |
| F1-28 | N-FE-164 / 165 | 旧「确认卡倒计时节拍」消失；同值 1000ms 现在是**运行态寿命**读秒节拍（不同语义） |
| F1-30/F1-31/F1-32 | N-FE-155 / 159 / 158 | 值未变（200 行 / 3000ms / 2000-2099） |
| F1-24/F1-25/F1-26 | N-FE-151 / 150 / 043 | 值未变（50ms / 60s / 300ms） |
| F1-10/F1-12/F1-13/F1-16/F1-17/F1-18/F1-19/F1-21/F1-22 | N-FE-020 / 027 / 026 / 060 / 059 / 018 / 011 / 013 / 014 | 值未变，多数位置微调 |
| F2-01…F2-07 | N-FE-030 / 031 / 028 / 029 / 033 / 034 / 032 | 值未变（20 / [20,50,100] / [10,20,50] / 60 / 100 / 8 / 200），行号全部变化 |
| V-01…V-04 | N-FE-166 / 167 / 168 / 169 | 值未变（8081 / 5200 / 300000 / 8192） |
| V-05 | N-FE-170 / 171 | 值未变 |
| V-06（`.env.local`） | —（未读） | 按要求不读取敏感文件，本地覆盖值不登记 |

### 11.2 已退役/找不到消费点的旧条目

| 旧 ID | 结论 |
|---|---|
| F1-07 / F1-08 / F1-14（`waitForJobFinal` 1000ms 轮询 / 300s 上限 / 显式覆写） | `api/jobs.ts` 现无 `waitForJobFinal`，全仓零引用；作业等待改由 `job-watch`（SSE + 3s 兜底 + 90s 帧空闲看门狗 + 发布 300s 墙钟）承担 |
| F1-11（`TASK_AUTO_REFRESH_MS = 4000`） | 任务中心无条件自刷已随 M1 摘除，`stores/task.ts` 无此常量 |
| F1-29（展开面板后 60ms 滚底） | 全仓无 60ms 定时器；滚动跟随改为 32px 闸 + 150ms 钉底静默 + 600ms 平滑 |
| F1-09（EventSource 约 3s 浏览器 retry） | 仍为浏览器缺省、未接管（N-FE-010） |

### 11.3 新增（旧基线未覆盖）的主要族

- SSE 首帧看门狗 30s、探测 15s（N-FE-008/009）；
- 作业通道帧空闲看门狗 90s、realtime 停表 2^31−1、回可见错峰 250ms（N-FE-021/022/023）；
- 发布跟踪 300s 墙钟、`ensureTaskType` 200/400ms 退避（N-FE-024/025）；
- AI store：409 校正 3s×3、开流前重试 1×400ms、自动恢复 2×400ms、停止落闸 2s、停止意向 60s、所有权锁 3×150ms / 1500ms / 返回意向 60s、镜像 120 条 / 512K、结构化回灌 8000（N-FE-040…060）；
- 面板行为：消息 8192、跟随 32/160/32px、钉底 150ms、意图 300ms、平滑 600ms、折叠 184px、IME 30ms、Markdown 缓存四上限、mermaid 缩放 0.5–4×/步长 1.25（N-FE-061…081）；
- UI 原语 21 项（N-FE-082…102）、表格工具 18 项（N-FE-103…120）、表单 schema 6 项（N-FE-121…126）、Markdown/缓存/面板微件 26 项（N-FE-127…152）；
- 上传/Excel/寿命显示 13 项（N-FE-153…165）；
- 构建面 6 项（N-FE-166…171）+ 构建包装器失败重试恰好一次 / 无退避（N-FE-172）；
- 跨册复核补充：向导定义就绪等待与冷却 3 项（N-FE-173…175）、表单文本格式/入口码正则 5 项（N-FE-176…180）、Playwright 调度 9 项（N-FE-181…189，逐文件一行）；
- Playwright 验证编排补充：端口段 9 项（N-FE-190…198）、Redis DB 7 项（N-FE-199…205）、`--app.*` 覆盖 6 项（N-FE-206…211）、浏览器视口 1 项（N-FE-212）。

---

## 12. 重点待裁项（按影响面排序）

1. **`PAGE_READY_TIMEOUT_MS` 3s → 8s 的静默漂移**（N-FE-017）。旧基线明确为 3000，现值为 8000，注释只写「超过即如实告知」，没有变更理由。它决定 `navigate_to` 后动作类工具能否等到页面注册 handler，直接影响 AI 回合成功率。
2. **同一「作业校验问题」资源的三套页大小**（N-FE-026 / 032）：函数默认 50、导入发布冲突拉取 200、后端默认（历史 50）。前端两处行为不一致，且 200 条以上会静默截断明细。
3. **`PUBLISH_WATCH_WALL_CLOCK_MS = 300_000`**（N-FE-024）。裸 300s，与后端段预算同值但无关联说明；作业更慢时会在「没有终态」的情况下单次提示并收摊。
4. **SSE 首帧 30s**（N-FE-009）。首帧预算是「模型未开吐」的唯一短判据，30s 无真机分布/压测背书，且与 45s 帧间只差 15s。
5. **表格写工具 2000 格 / 200 行双阈值**（N-FE-108/109）。决定「小改动/大改动」档与确认复选框，2000 与 200 均无推导，且硬上限 100000 与前端 60s 工具预算的吞吐未建立关系。
6. **`SHEET_TOOL_MAX_COLUMNS = 5` 与 4 处裸字面量 `5` / `3` 并存**（N-FE-117/118）。常量与文案、校验混用，存在漂移风险。
7. **复制成功反馈 2000ms 三处重复**（N-FE-077），且 mermaid SVG 下载的 `revokeObjectURL` 用 0ms、Excel 导出用 3000ms（N-FE-159/161）——同族语义两种口径。
8. **`UI_UNDO_DEPTH = 1` 全仓无消费点**（N-FE-088），而 `SHEET_UNDO_DEPTH = 3` 是实际实现的撤销深度；两者并存易被模型/文档误读。
9. **镜像上限 120 条 / 512K 字符**（N-FE-055/056）与后端记忆窗口 40 条无对照说明；「末条不受字符预算约束」的最坏溢出未给算式。
10. **`DOWNLOAD_PREFLIGHT_TIMEOUT_MS` 30s 与外层工具预算 60s 的「先到者」关系**（N-FE-016）只在注释成立；若后端上限下调（如 90s → 30s 预算），30s 前置收口会吃掉全部预算。
11. **定义就绪等待 10s 的注释已与现态分叉**（N-FE-173/174）。导出侧注释写「与页面就绪等待同量级（10s）」，但 `PAGE_READY_TIMEOUT_MS` 现在是 8s（N-FE-017）；导入/导出又是两份独立同名常量。三处（8s / 10s / 10s）应一次裁决：要么统一值，要么写清为何不同。
12. **Playwright 两处 180s 用例上限**（N-FE-183 kqc-compact、N-FE-189 vl）。相对主配置 120s 的抬升没有任何注释；若确为「压缩期/外部实例更慢」的实测结论，应写进配置文件，否则易被当作随手放宽。
13. **Playwright 视口 1440×900 可能未生效**（N-FE-212）。9 份配置顶层写 `viewport: {1440,900}`，但同一配置的 project 级 `use: { ...devices['Desktop Chrome'] }` 内含 1280×720，合并时项目级优先——实际渲染视口很可能是 **1280×720**。依赖视口的滚动/折叠/横滚断言须先确认生效值，再决定是否在 project 级显式固定。

---

## 13. 扫描模式与覆盖

### 13.1 实际执行的扫描（ripgrep / grep，只读）

```text
# 1) 具名常量（全大写 + 数值）
^\s*(export )?const [A-Z][A-Z0-9_]+\s*=.*([0-9]|Number\()
# 2) 定时器与调度
setTimeout|setInterval|requestAnimationFrame|requestIdleCallback
# 3) 所有非注释行中的数值字面量（Python 逐文件扫描，跳过 /* */ 与 // 行）
(?<![A-Za-z0-9_$.\-])(\d[\d_]*(?:\.\d+)?)(?![A-Za-z0-9_])
# 4) 截断/分页/上限语义
\.slice\((0|[a-z]+), *[0-9]+\)|\.slice\(-[0-9]+\)|\.substring
maxlength|page-size|page-sizes|:rows=|rows="
MAX_|_MAX|LIMIT|_LIMIT|CAP|_CAP|THRESHOLD|_WINDOW|TTL
# 5) 超时/轮询/退避消费点
timeoutMs|intervalMs|delay\(|backoffMs|retry|Ms\b|_MS\b
# 6) 构造面 / 脚本面 / 验证编排
vite.config.ts 全部数值、.env.example 全部 VITE_ 键、package.json scripts、scripts/build.mjs（逐行实读）、
playwright*.config.ts 的 retries|workers|timeout|expect + E2E_*_PORT|E2E_REDIS_DB|--app\.\*|viewport（9 份逐文件实读）
```

覆盖：`frontend/src` 下 66 个 `.ts` + 24 个 `.vue`（0 个 `.js`）共 52,648 行，逐文件过了一遍数值行；重点文件（`stores/ai.ts` 9757 行、`AiPanel.vue` 5399 行、`sheet-tools.ts` 3233 行、`markdown.ts` 1253 行、`api/sse.ts` 1235 行）做定点阅读确认语义与消费点。脚本面：`frontend/scripts/build.mjs`（27 行）逐行实读，登记 1 条（N-FE-172）。验证编排面：9 份 `playwright*.config.ts` 全文逐行实读，登记 **32 条**（调度 N-FE-181…189、端口 N-FE-190…198、Redis DB N-FE-199…205、产品键覆盖 N-FE-206…211、视口 N-FE-212）。

### 13.2 排除面（按任务口径，未登记）

- **纯 CSS 布局**：`el-table-column` 的 `width/min-width`、`el-dialog width="440px"`、`el-drawer size="82%"`、`el-table max-height="300"`、`textarea rows`、mermaid 卡 `260px/200px`、代码块 `max-h-[min(280px,35vh)]`、字号/圆角/阴影/过渡时长、`DefSelector.vue:120` 的 `height: 380`（表格容器高）。
  - 边界：`markdown.ts:631` `TABLE_MIN_W`（3~10 列 → 540~1800px，`cols<=2` 不给最小宽度）是**布局阈值**但用 JS 常量表驱动；已按「纯 width/min-width」排除，如需按虚拟化/横滚行为纳入，可补一行。
- **测试断言/夹具与造数**：`frontend/tests/**`、`frontend/e2e/**` 的断言值、测试数据、窗口值（如 kqc 用例的 28000/26000/22000/20000）仍不纳入。**例外**：`playwright*.config.ts` 已全文纳入——调度四字段（N-FE-181…189）、端口段（N-FE-190…198）、Redis DB（N-FE-199…205）、`--app.*` 覆盖（N-FE-206…211）、视口（N-FE-212）。
- **验证配置中的选择/开关**：`testMatch`/`testIgnore` 正则里的用例编号（`12[ab]`、`13[a-z]?`、`14[abc]`、`15-mdo`、`16-kqc(-[bc])?`、`17b`、`11c`）、`grep` 的 `@stub/@real`、`E2E_SHARED_AI_STACK` 的 `'1'/'0'`、`E2E_EXTERNAL`、`E2E_KQC_COMPACT/SPECIAL` 属布尔开关或测试选择标识，不是容量/时序数字，按口径不登记。
- **注释中引用的外部默认值**：主配置注释的 18399/18330/8081/5202、kqc-compact 注释的 128000/13000/28000/26000/22000/20000/70%、unlock-budget 注释的 120s/300s、vl 注释的 18999/DB 12、mdxy 注释的 32B（KEK 长度）——均非本文件生效值（他处配置/产品键默认/前置要求），其取值与出处见 §15 观察项，不占 ID。
- **依赖版本**：`package.json` 的 `dependencies/devDependencies`。
- **状态码**：404/409/429/500/502/503/504、HTTP 201/202、`ErrorCode`。
- **枚举序号/算法下标**：`RISK_ORDER 0/1/2`、`base 36/26` 进制换算、循环 `index += 1`、`slice(2,10)` 随机 id。
- **展示格式化**：`utils/format.ts` 的 60/3600/86400 相对时间分档、`toTimeString().slice(0,8)`、`updatedAt.slice(0,19)`、`formatTokens` 的 1000 分档、`formatBytes` 1024 分档、`AiFeedbackView.summaryOf` 的文本摘要。
- **工具描述中的数字**：`types/tools.ts` 的 description 是常量/后端值的逐字复述（20/40/2000/600/12/200/±2000），不作为独立规格重复登记。
- **无数字量词的正则**：`form-schema.ts:758` `URL_PATTERN`（`[^\s]+`）无形如 `{n}`/`{n,m}` 的数值边界，按口径不单列；同文件其余带量词正则已登记 N-FE-125/126/176…179。
- **后端/服务端下发的动态值**：帧 `timeoutSeconds`、`retryAfterMs`、`elapsedMs`、`poolSize`、`/health.sheet` 的 `sampleMaxRows/projectionMaxColumns`（前端只 `min()` 收紧，硬顶见 N-FE-103/104）。

---

## 14. 可能遗漏 / 不确定性

1. **`frontend/.env.local` 未读**：本机实际 `VITE_API_BASE` / `VITE_DEV_PORT`（旧基线记为 18318/5202）无法确认；本表只登记 `.env.example` 与 `vite.config.ts` 默认值。
2. **浏览器缺省值未实测**：`EventSource` 重连间隔（N-FE-010）、`sessionStorage` 配额（影响 N-FE-056 的 512K 预算）、`requestIdleCallback` 调度抖动；这些依赖运行环境，仓内无配置。
3. **依赖库内部数字未展开**：`element-plus` 的 `el-pagination` 默认页大小、`SpreadJS` 内部行列上限、`mermaid` 自身 `maxTextSize/maxEdges` 之外的守卫、`highlight.js` 超时；本表只登记本项目显式传入/覆盖的值。
4. **后端配额同值性待核**：`UI_WRITE_VERBS_PER_TURN=12`、`UI_WRITE_THROTTLE_MS=80`、`UI_UNDO_DEPTH=1` 注释称「与后端 Guard 同源」，但前端无法自证后端现值；同理 `maxlength=60`、`MAX_FORM_*`、`RESULTVIEW_LIMITS` 的「同值」需后端面复核。
5. **动态生成类名中的数值**：`tailwind` 任意值类（`text-[12.6px]` 等）属 CSS，未登记；但若后续把字号/高度用作行为判据（如 `USER_MSG_MAX_HEIGHT_PX` 与 CSS `max-h-[184px]` 双写），存在两处不同步风险。
6. **工作树行号时效**：首轮扫描的前端 3 文件（`api/ai.ts` / `AiPanel.vue` / `stores/ai.ts`）已在补扫前提交为 `c7e2497d`，内容与首轮索引态一致，本表行号对该提交成立；若后续再提交改动这三个文件，行号需重取。
7. **注释中的数字**：少量注释引用了外部依据的数值（如 nginx 60s、审计 10~15s、issue#6 的 30.6s/120s），已作为「依据」引用而非规格登记。
8. **`frontend/scripts/build.mjs`（本轮已补扫，不再是遗漏）**：该文件 27 行已逐行实读，全部行为数字只有「首次构建失败后重试恰好一次、无退避」（N-FE-172）；其余为路径/环境变量名与 `recursive: true` 布尔量。需要注意的残余不确定性：`vite build` 内部的缺省值（如 chunk 告警、目标浏览器）不在本文件，仓内已在 `vite.config.ts` 覆盖告警阈值（N-FE-169）；重试次数/无退避没有常量，若未来要加退避须同时定义次数上限。

---

## 15. 明确观察项（非遗漏）

> 以下条目是**已知且已核实**的现状，不是扫描遗漏；登记在此避免被误读为「漏查」，也避免与「可能遗漏」混在一起。

1. **`retryAfterMs` 解析但未被消费**（`frontend/src/api/sse.ts:129`、`:657-667`）：`probeReattach` 会把后端 202 响应里的 `retryAfterMs` 解析进 `PreparingProbe`，但 store 侧没有读取点——重挂节奏仍由固定的 1/2/4/8s 退避时间线决定（N-FE-004/005/006）。这是**核实后的现状**而非遗漏：前端从未把服务端建议间隔接入排期。若产品希望尊重该字段，需单独立项并同步改 `scheduleReconnect`；若继续忽略，建议在类型注释里显式写明「保留字段，不参与排期」，避免后续误以为已生效。
2. **Playwright 配置注释中引用的外部默认值（非本文件生效值，故不占 ID）**：主配置注释 `18399`（桩）/`18330`（后端）/`18401`（前端）与「绝不占用 `8081`/`5202`」；kqc-compact 注释 `128000`（默认分母）/`13000`（reserve-tokens）/`28000`·`26000`·`22000`（用例三档窗口）/`20000`（断言不等于的分母）/`70%`（压缩触发比例）；unlock-budget 注释 `120s`（默认 `tool.exec-timeout`）/`300s`（默认 `total-budget`）；vl 注释 `18999`（桩端口）/`DB 12`（由 `e2e/support/helpers-vl.ts` 设置）；mdxy 注释 `32B`（`AI_SECRET_KEK` base64 解码后的长度要求）。这些值要么是他处（harness/helpers/后端配置）的生效值、要么是产品键默认、要么是前置要求，均未在本文件赋值，因此不分配 `N-FE` 行；如需把它们也纳入前端册，应从各自生效文件（`frontend/e2e/support/**` 或后端 `application.yml`）取值登记，以避免把注释当事实源。
3. **Playwright 配置里的布尔开关**：`E2E_SHARED_AI_STACK`（`'1'/'0'` 按 `E2E_MDO_GROUP` 切换）、`E2E_EXTERNAL`、`E2E_KQC_COMPACT`/`E2E_KQC_SPECIAL` 是语义布尔，不是数值规格；登记于此备查，不占 ID。
