> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# 对话面板包（苞 A / 苞 B）GLM 红队对抗性审计报告

- 审计日期：2026-10-11
- 审计角色：GLM 红队（只读审计；未编辑任何文件、未执行 git add/commit/push）
- 审计对象：主仓库（分支 main，HEAD=03a6cbe）工作树全部未提交改动 = `git diff HEAD` 全量（9 个已改文件，+405/−20）+ 2 个未跟踪新文件
- 验证命令：仅运行了后端测试（新测试类单类实跑，见 §3）；其余基线（399 全量、E2E、typecheck/build、check-docs、CDP 冒烟）**未复跑、未采信**，仅作为对照口径记录

---

## 1. 锚定防夹带（提交侧核对凭据）

| 锚定项 | 值 |
|---|---|
| HEAD | `03a6cbec79cc4d9a4a589abc9d9ef93d2df9b03b` |
| `git diff HEAD` 全量 SHA-256 | `ee1e68fd35f65032372ce74606ac9e7b9fca87f75bb30318da73bd90dbfaabab` |
| `git status --porcelain` 条目数 | **11**（9 × `M` + 2 × `??`），与任务书一致 |
| diff 统计 | 9 files changed, 405 insertions(+), 20 deletions(-) |
| 未跟踪① `backend/src/test/java/com/example/configmgr/ai/web/AiControllerHistoryDeleteTest.java`（286 行）SHA-256 | `2e437c2148b958499691ecd3bd2d43a5dd00da3e42698f7a7c85713b5fce6235` |
| 未跟踪② `scripts/chat-panel-smoke.mjs`（823 行）SHA-256 | `33aad0a2e2fe6cf19838a6e874345c642671a88cab58179efdd2549e58ef63d7` |

已改 9 文件清单（与任务书逐一相符）：根 `README.md`、`backend/.../memory/RedisChatMemoryRepository.java`、`backend/.../web/AiController.java`、`docs/M2-排期计划.md`、`docs/README.md`、`docs/adr/DECISION-REGISTER.md`、`frontend/src/api/ai.ts`、`frontend/src/components/AiPanel/AiPanel.vue`、`frontend/src/stores/ai.ts`。

口径注记：diff 哈希在当前工作树行尾状态下计算（git 报 LF→CRLF 归一化警告）；提交侧复算请以同口径（`git diff HEAD | sha256sum`）执行。审计期间工作树文件集合未发生变化（锚定在审计第一步完成，其后所有读取均针对同一快照）。

---

## 2. 范围核实与量纲核对

- 后端端点：`DELETE /api/ai/history/{sessionId}`（`AiController.java:562` 起），裸 `Map` 响应 **9 键**（sessionId / deleted / existed / memoryKey / removedFromIndex / runActive / activeRunId / storedBy / note）——逐一清点相符；幂等 200、Redis 不可用 503 `AI_REDIS_UNAVAILABLE`、会话门只报告不 409、判存走单次 `SISMEMBER`——与代码实读一致。
- `RedisChatMemoryRepository.isConversationIndexed()`：+17/−2（javadoc + 6 行实现 + 类头键结构注释更新），实读相符。
- 前端：`api/ai.ts` 新增 `deleteHistory`（复用既有 `del`，`http.ts:144` 实存，且拦截器对裸对象原样放行，通路成立）；`stores/ai.ts` `resetSession` 重写（`this.sessionId` 双写在 `:1119`，与任务书"约 :1114"相符）；`AiPanel.vue` 新建对话按钮（`:23`）、空态小字（`:137`）、hover 复制（`:600` legacyCopy / `:629` copyMessage）、骨架屏（`:122`）。
- 测试：`AiControllerHistoryDeleteTest` 10 用例；冒烟脚本 13 断言（①~⑪、⑬ 为 PASS/FAIL，⑫ 可 SKIP）。任务书所述"CDP 冒烟 12 PASS + ⑫ SKIP"基线中 12+1=13 与脚本断言总数相符。
- 文档：`DECISION-REGISTER.md` 新增 DC-17/DC-18（实读 `+2 行新增 + 2 处行内 DC-01~16→DC-01~18 同步`，与任务书"+4/−1"为同一事实的不同计数口径）；`M2-排期计划.md` 追加 R-107/R-108/R-109 三行去向 + 四个登记小节。

---

## 3. 本审计独立验证记录（不采信既有基线）

| 项 | 结果 |
|---|---|
| 新测试类单类实跑（Maven，本机后端目录，`-Dtest=AiControllerHistoryDeleteTest`） | **Tests run: 10, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS** —— 任务书"10 用例"由我本人实测确认 |
| 其余基线（399/0/0/0、E2E 29/29、typecheck+build、check-docs 6/6、CDP 冒烟） | 未复跑（审计授权面 = 仅后端测试命令；全量耗时与只读约束下取单类定向验证），**不作为本报告任何结论的依据** |
| 脱敏复扫（盘符 / 用户名 / 内网 IP 正则，11 个改动与新增文件逐一扫） | **零命中**（冒烟脚本 Chrome 缺省路径按 `ProgramFiles` 环境变量组装，源码确无盘符字面量） |
| 命名纪律复扫（"main"+分隔符+"v2" 组合，全部文档与新增文件） | **零命中** |
| 表结构核对 | 去向表 R-104~R-109 均 2 列；ADR 表 DC-01~DC-18 均 9 列；编号唯一、无跳号（R-110 按 2026-10-11 降级小节口径"预留不落行"，实查确未落行） |

---

## 4. 发现清单（按级别）

分级口径：BLOCKER = 阻断提交；MAJOR = 须修复或经指挥官明确裁决登记后方可提交；MINOR = 应修，可随下批；观察 = 登记知悉。

### MAJOR-1：`loadHistory` 完成写入无会话身份守卫，本包新增的「新建对话」入口使其首次可达 —— 旧会话历史可迟到写回、污染新会话

- **主锚**：`frontend/src/stores/ai.ts:459`（`this.messages = history.messages.map(...)`，无守卫写入）；触发入口 `frontend/src/components/AiPanel/AiPanel.vue:23`（新建对话按钮）+ `frontend/src/stores/ai.ts:1102`（resetSession）。
- **问题**：`loadHistory()`（`:426`）在入口处同步恢复镜像后 `await aiApi.history(this.sessionId)`，**完成路径没有任何"会话身份仍是发起时会话"的判据**即整体覆写 `this.messages`。store 内其余异步落盘点（send / reattachActive / resumeSnapshotStream / 本包 F5 的 deleteHistory 失败提示）均带 `isCurrentRound` 同族守卫，唯独 `loadHistory` 的完成写入是裸写。前在代码本就无守卫，但 HEAD 处 `resetSession` **没有任何前端调用方**（实查 `git grep resetSession HEAD -- frontend` 仅命中注释与定义）；本包新建对话按钮是它**第一个活的 UI 入口**，该竞态因此在本包变为用户可达。
- **构造路径**：①页签内已有对话（存在 sessionStorage 镜像）；②刷新页面 → `onMounted` → `loadHistory()` 同步恢复镜像（`messages.length > 0` ⇒ 新建对话按钮**可点**），随即 `await GET /api/ai/history`；③在该 HTTP 往返窗口内（本地几十毫秒，代理/后端慢时可达秒级）点「新建对话」→ resetSession 清空 messages、换新 sessionId、`historyLoaded=true`；④旧请求返回 → `:459` 把**旧会话**的整段历史写进**新会话**的 `messages` → 新对话屏幕被旧内容"复活"，且后续 `send()` 会在被污染的列表上追加。核心承诺"点击=立即清屏"被打破。注：无镜像场景按钮因 `!messages.length` 置灰，恰好挡住了该入口——竞态只在"有镜像 + 点得快"时成立，这是它没被冒烟脚本捕获的原因（冒烟各阶段均无"刷新后立即点新建"的时序）。
- **影响面评估**：UI 层污染（显示与后续消息混排），不损毁数据；新会话的后端记忆（新 id 键）不受污染，刷新可自愈。
- **建议修法**（一行级）：`loadHistory` 开头捕获 `const sid = this.sessionId`，两处完成写入前判 `this.sessionId === sid` 不成立即整体丢弃（return），与 store 既有守卫风格一致；顺手在 `restoreSuspendedSnapshot` 调用点同判（其内部已按当前 `this.sessionId` 查询，实际无害，但判据显式化更稳）。

### MINOR-1：`stopped` 只在 `send()` / `resetSession()` 复位，`reattachActive` / `resumeSnapshotStream` 不复位 —— 停止后重挂同一轮，重挂中的真断流被静默吞掉

- **主锚**：`frontend/src/stores/ai.ts:788`（reattachActive，无复位）、`:585`（resumeSnapshotStream，无复位）；复位点仅 `:657`（send）与 `:1136`（resetSession）；置位点 `:1059`（stop）。
- **问题**：`stopped` 的注释口径是"只由 stop() 置位、只由 send() 开头复位 ⇒ 只影响这一轮"。但**重挂连接的是同一轮**却继承 stale 的 `stopped=true`：stop() 置位后若取消未生效（`POST /cancel` 失败或后端未及收尾，`activeRunId` 仍在），用户点重挂 → 重挂流真断流 → 三处守卫 `!info.terminal && !this.stopped`（`:614/:682/:828`）把红条吞掉，`streamInterrupted` 不置位——与 ADR-6「断流必须显式提示」相悖，恰是苞 B 项 3 想防的**反向假阴性**（该包只验证了"停止不误闪"，未验证"停止后重挂再断流仍提示"）。
- **构造路径**：起一轮挂起轮 → 点停止（取消请求失败或后端慢）→ 立即点重挂 → 断开网络/杀后端 → 无红条、无 `streamInterrupted`。
- **建议修法**：`reattachActive` 与 `resumeSnapshotStream` 建立新连接处开头 `this.stopped = false`（新连接 ≠ 被停止的那条连接，语义自洽）；或在冒烟脚本补一条"停止→重挂→断流仍报红"的回归断言后维持现状登记。

### MINOR-2：新端点无鉴权 / 无会话归属校验 —— 任意 sessionId 可删任意会话记忆（威胁模型评估后定级）

- **主锚**：`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:562`。
- **威胁模型**（独立评估，非复述复核结论）：全仓无鉴权（无 spring-security、无任何归属校验，DC-10 登记在案）为**既有基线**；既有破坏性动词 `POST /api/ai/runs/{runId}/cancel`、`POST /api/ai/chat`（可向任意会话发消息、触发写类工具）同为跨主体可达。本端点的**边际增量**有二：①它是记忆命名空间内的第一个破坏性动词，静默销毁服务端记忆与取证窗口（`ai:run:*` 台账虽保留，但记忆面没了）；②幂等 200 设计使试探性删除不产生 404 噪声，探测成本更低。在 DC-10 的内网受信环境模型下，读侧（GET /history）本就同面暴露全部记忆内容，删除的边际危害未越过既有接受线。
- **定级理由**：不构成「本包引入的越权缺陷」（与全仓姿态一致、有 DC-10 背书），但比复核 F6② 的"观察"更进半步——**它把登记口径变成了带动作项的欠账**：引入鉴权的那个批次**必须**同步补会话归属校验，否则会留下"鉴权后仍可删他人记忆"的隐性洞（鉴权只挡匿名，不挡合法登录用户互删）。
- **建议**：维持"不在无鉴权框架里提前造假归属校验"的既定裁决；在 R-108（轨迹留痕包，含审计日志）重启时**强制联动**：鉴权批与归属校验批不得拆开落地。本条作为该欠账的第二次独立背书登记。

### MINOR-3：`deleted`/`removedFromIndex` 之外的「不删范围」契约（不删 `ai:session:*` / `ai:run:*`）无回归锁

- **主锚**：`AiController.java:562-606`（实现只调 `chatMemory.clear`，行为正确）；`backend/.../AiControllerHistoryDeleteTest.java`（10 用例中无任何"runStore / sessionGate 台账面未被触碰"的 verify）。
- **问题**：`note` 字段把删除范围写进响应供取证，javadoc 亦承诺"不删 ai:session 与 ai:run:*"，但该不变量只由实现现状保证，无测试锁。将来若有人"顺手"在端点里加台账清理，10 用例照绿。
- **建议**：测试类补一例 `verify(this.runStore, never()).<任何台账写方法>()` 形态的负断言（或在测试类 javadoc 显式声明该不变量由仓储层单测覆盖——实查仓储层亦无此负断言，故建议按前者补）。

### 观察（登记知悉，不阻断）

- **观察-1（冒烟 ⑫ 的 SKIP 面与退出码）**：`scripts/chat-panel-smoke.mjs` 的 ⑫（真断流回归对照）依赖 `SMOKE_KILL_BACKEND=1` 授权，未授权时如实 SKIP 且**不影响退出码 0**。SKIP 路径本身诚实（result JSON 与控制台双重留痕、理由落盘），不构成假绿；但若编排层只看退出码，⑫ 将**永久不被测**而流水线持续绿。建议编排层对 `result-*.json` 增加"⑫ 不得为 SKIP"的显式断言（或在授权环境例行开闸）。剪贴板回读旁证（`readClipboardBounded`，5s 上界 + 三形态降级 SKIP）同理：⑧ 的判据仍由 `__copyCalls` 逐字符比对承担，设计正确。
- **观察-2（冒烟 ⑪ 的假红面）**：断言⑪ 要求 `notice === '已请求取消本轮对话'`，而该文案在 `stop()` 内 `await aiApi.cancel()` **之后**才写入；脚本只 `sleep(1200)`。后端取消接口慢于 1.2s 时会假红（fail-loud 方向，非假绿），负载下偶发红需人工识别。建议改条件等待。
- **观察-3（M2 台账一处 hunk 的归属待确认）**：`docs/M2-排期计划.md` 中「AI 对话正文 Markdown / 图表渲染 暂缓登记」小节（15 行，登记时点 23:30）在本 diff 范围内，而两份施工报告声明"未动台账"——即复核 F1 提出的归属问题。其内容本身（门禁、脱敏、命名）经我复扫无瑕疵，但**提交范围是否含该 hunk 须由指挥官裁决**，本报告不代裁。
- **观察-4（check-docs 门禁的提交前盲区）**：门禁按 `git ls-files` 扫描，两个未跟踪新文件提交前不在门禁内。我已按同口径手工复扫（脱敏/命名零命中，见 §3），提交后门禁自然覆盖。仅登记该盲区事实。
- **观察-5（DC-17 与代码现状的关系，非矛盾）**：DC-17 要求"厂商私有响应字段一律经兼容层收敛"。实查后端存在 `ReasoningAwareOpenAiChatModel`（fork 的官方类，处理 `reasoning_content` 回填，javadoc 明言 deepseek-flash 思考模式必须回填否则 400）与 `UsageDetails`（"DeepSeek 顶层字段……走反射 + 多路兜底"）。二者**即 DC-17 所称的兼容层**，字面合规；但 `reasoning_content` 实为多家 OpenAI 兼容厂商的事实标准扩展而非单一厂商私有，R-107 落地时应在特征表文档里把这一定性写明，避免后来者误读为"违反 DC-17"。DC-18 与代码现状无冲突（轨迹留痕尚未实现，纯口径登记）。
- **观察-6（cancel 失败提示的不一致口径）**：`stop()` 对取消请求失败写 notice（`:1079-1082` 一族），`resetSession` 对同类失败静默（catch 空 + 注释声明故意）。两处口径不同且各有理由（新建对话要"点了就干净"），登记知悉；若未来统一，应按 resetSession 的静默版为准（动作语义不同：一个是"停止失败要告知"，一个是"新对话不被旧轮失败打扰"）。
- **观察-7（F5 守卫的正面确认）**：`resetSession` 的 `isCurrentRound = sessionId === nextId && messages.length === 0`（`:1154`）判据成立——`send()` 同步 `recordUserMessage`，故 messages 非空 ⇔ 新会话已起过轮次；再次 resetSession 换 id 亦被第一判据拦住。该守卫**完备覆盖 deleteHistory 失败提示这一个异步落点**；它未覆盖的 loadHistory 完成写入是另一个落点，即 MAJOR-1（两者不是同一问题）。

---

## 5. 十个对抗方向的核查结论

| # | 方向 | 结论 |
|---|---|---|
| ① | 鉴权/会话归属 | 定级 MINOR-2（威胁模型见上；DC-10 内网受信基线下不构成本包新增越权，登记"鉴权批必须联动归属校验"动作项） |
| ② | resetSession 竞态与守卫完备性 | **发现 MAJOR-1**（loadHistory 完成写入不在任何守卫覆盖内，本包新 UI 入口使其可达）+ MINOR-1（stopped 复位点缺口）；F5 守卫本身正确（观察-7） |
| ③ | SISMEMBER 改造后响应键语义 | **不变**。`AiController.java:614-619` 的等价论证成立：端点内刚做过 `clear()`（DEL+SREM），此后成员只能被在跑轮次收盘的 `clear+add` 写回（add 同时建数据键），两种判法在此处一致；"索引成员但数据键已过期"的分歧点在本端点不可达。`existed` 取自 clear 前的窗口读取，`removedFromIndex` 为响应时点事实（F3 口径已 javadoc 登记）。测试 F4 两例以 `never()` 锁死 SMEMBERS/EXISTS 回归，**实跑 10/10 绿** |
| ④ | 复制按钮 XSS/注入 + execCommand 回退 | **无注入面**：文本经 `textarea.value` 赋值与 `clipboard.writeText` 传递，全组件无 v-html；`data-testid` 拼接用的 `m.id` 为内部生成（`msg-N`/`history-N`），非用户可控。回退实现正确（readonly + 离屏 + select/setSelectionRange + finally 移除；失败有可见反馈文案）；冒烟⑨⑩分别钉住"clipboard 缺失"与"writeText 被拒"两形态。`focus-within:opacity-100` 保证键盘可达性 |
| ⑤ | onClose 抑制第四处遗漏 | **无遗漏**：全仓 `onClose` 落点恰三处（`:609/:676/:823`）+ sse.ts 单一分发点（`:232`），三处均已加 `&& !this.stopped`。但复位面有缺口（MINOR-1） |
| ⑥ | 骨架屏/空态互斥边界 | **正确**：`v-if="!messages.length && !historyLoaded"` / `v-else-if="!messages.length"` 结构互斥；镜像恢复（messages>0 且 historyLoaded=false）时正确显示消息流而非骨架屏；resetSession 置 `historyLoaded=true` 使新会话免骨架屏；置灰门槛（`!messages.length`）挡住了无镜像期的竞态入口。冒烟⑬用 Fetch 域挂请求稳定复现窗口期并验证放行后互斥翻转 |
| ⑦ | 冒烟断言假绿面 | 主判据无假绿（⑧ 由注入桩逐字符比对承担、⑬ 对 heldId 判空失败即红、waitCount 超时返回 last 值供严格判等）。两个残余面登记为观察-1（⑫ SKIP 不影响退出码）与观察-2（⑪ 依赖 cancel 时序的假红面）。⑫ 的三重杀进程闸（授权 + 端口反查 + 进程名校验）设计扎实 |
| ⑧ | 文档表列数/编号/命名/脱敏 | 全部通过：R-104~109 均 2 列、DC-01~18 均 9 列无重复、R-110 预留未落行、DC-17/18 状态列与降级小节口径互相咬合（登记日 10-11 / 裁决日 10-10 已在小节头显式调和）；脱敏与"main+v2"命名复扫 11 文件零命中 |
| ⑨ | 测试是否真锁行为 | **总体锁得实**：构造"不调 clear 只报 deleted=true"的假实现 → 用例 1/2/3/5/6 的 `verify(clear)` 必红；构造"removedFromIndex 恒 true"→ 用例 7 与 F4-1 必红；构造"回落全量扫描"→ F4 两例的 `never()` 必红；404/409/无 key 503 各负例在位。残余缺口一条（MINOR-3：不删范围无负断言） |
| ⑩ | DC-17/18 与 DC-01~16 口径一致性、与代码矛盾 | 文本口径一致（DC-17 自述是 DC-01 的实现级下沉；DC-18 与 DC-10 的关系已显式声明不冲突且引用 FR-5.6"不携带主体与权限维度"自洽）；与代码现状无硬矛盾，一处定性待 R-107 落地时写明（观察-5） |

另核对既有复核（F1~F10）落点：F2（复活窗口如实口径）、F4（SISMEMBER）、F5（守卫）确已落进代码与注释；F3/F6①/F6②/F8/F9/F10 已在 M2 登记小节如实收录；F1 归属问题维持开放（观察-3）。本报告的 MINOR-2 与 F6② 同源但定级口径不同（观察 → 带动作项的 MINOR），MAJOR-1 与 MINOR-1 为**本轮新增发现**（既有复核未覆盖）。

---

## 6. 总体结论：**带条件 PASS**

- **0 × BLOCKER，1 × MAJOR，3 × MINOR，7 × 观察。**
- 代码主体质量扎实：后端端点契约六条 + F4 两条均被测试真锁（我实跑 10/10 绿），SISMEMBER 等价论证成立，复制链路无注入面且回退两形态被钉死，onClose 三处无遗漏，骨架屏互斥正确，文档台账/ADR/脱敏/命名全部通过复扫。
- **放行条件**：
  1. **MAJOR-1 须修复**（`loadHistory` 完成写入补会话身份守卫，一行级改动 + 顺手补一条"刷新后立即点新建对话"的冒烟时序断言），或由指挥官明确裁决"登记不修"并落 M2 台账后放行；
  2. MINOR-1（stopped 复位）建议同批修（同为 store 内小改动）；不修则登记；
  3. MINOR-2 的"鉴权批必须联动会话归属校验"动作项随本包结论一并登记（建议并入 R-108 联动条目）；
  4. 观察-3 的 M2 hunk 归属由指挥官裁决后方可定提交范围。
- 提交侧请以 §1 哈希清单核对工作树未被夹带改动。
