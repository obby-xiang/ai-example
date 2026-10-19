> 归档来源：仓外正式报告（已脱敏）；归档日期：2026-10-19；本件为 2026-10-19 仓外文档补档批入库件，正文结论与数据未改。**历史件（非现行）**：记录被后续批次报告覆盖的中间态，现行口径以最新现势件为准。

# 真实项目 composer 浏览器验收报告

## 环境与自证

- 自身 `profile.bind`：`agentId=agent-1084`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`。
- 前端 `http://localhost:5202/tasks` → 200；后端 8081 / 桩 18399 / demo 18330 均在跑（未重启）。
- 项目源码：`<REPO_ROOT>/frontend/`（只读）。
- 未修改代码/demo/docs/服务，未提交；证据只写 `<SCRATCH>/_cu-composer-project-r26/browser/`。
- 内置浏览器为主证据（1/4/6 行、app 初始态）；补充 Chromium 用于精确 DOM、caret、网络故障注入与 360/400 精确量测。未通过修改 DOM 伪造结果。

## 逐项结论

| # | 项目 | 结论 |
|---|---|---|
| 1 | 视觉/几何：桌面 | **PASS** |
| 1 | 视觉/几何：360/400 无横向溢出 | **FAIL** |
| 2 | 流式可编辑 / Enter 无响应 / Shift+Enter / 停止 / 停止后单发 | **PASS** |
| 3 | 空闲 Enter / 空空白 / repeat / 合成 IME | **PASS**（真实 IME **BLOCKED**） |
| 4 | 失败保稿 / 主动 stop / 重发不重复 / 不覆盖新内容 | **PASS** |
| 5 | hard-disabled / tip hover+focus | **PASS** |
| 5 | tip Esc 关闭 | **FAIL** |
| 5 | 360/400 无内溢 | **FAIL** |
| 5 | #3 tool/GF 图标与右状态 | **PASS** |
| 5 | #3 HITL 圆圈问号 live | **BLOCKED** |
| 5 | JS pageerror / console | **PASS**（仅注入故障产生的预期网络错误） |
| — | **总体** | **FAIL（2 个显式验收项失败 + 2 个 BLOCKED）** |

## 1) 视觉/几何

### 桌面 1280（PASS）

- 初始 textarea：`height 40`、`rows=1`、`min-height 40`、`max-height 101`、`field-sizing:content`。
- 1 行：`40px`；4 行：`101px`；6 行：`101px`。
- 6 行：`scrollHeight 143`、`clientHeight 101`（内滚成立）；`scrollTop 32 → 42`；工具栏 `top` 前后均 **727**，不动。
- 白底 shell：`rgb(255,255,255)`；focus-within 蓝色环（`rgba(37,99,235,0.4) 2px`）；左侧“工具位（预留·留空）”虚线占位；发送钮 28×28、圆角 9999px。
- 真实 app 停止钮（流式 held 请求 + 多行 + focus）：`display:flex`、28×28、圆角 9999px、内部白方块 9×9、中心偏移 **dx 0 / dy 0**、`squareInside=true`、与 textarea 无交叠（`verticalGap 0`）、与发送钮同 rect。
- 放大截图：`shots/real-stream-stop-button.png`、`shots/real-stream-composer.png`。

### 360 / 400（FAIL）

- 360：`innerWidth/clientWidth 360`，`document.scrollWidth 493`；400：`innerWidth/clientWidth 400`，`scrollWidth 493`。
- AI 面板 `aside` 固定 **400px**，位于 `x 32..432`；composer `x 48..414`。
- 头部“隐藏 AI 栏”按钮 `x 424..493`，菜单/标题同样越界；可横向滚动。
- 将 AI 面板收起后，`scrollWidth` 仍 **493**（头部/导航仍溢出），不是面板单一原因。
- 截图：`shots/project-360.png`、`shots/project-400.png`、`shots/project-360-collapsed.png`。
- 这是真实项目页面级响应式缺陷；demo v2 在 360/400 无此问题。

## 2) 流式行为（PASS）

- 用 route 持有 `/api/ai/chat`（不修改 DOM，仅延迟网络）保持 loading；发送后 `posts=1`、输入清空、停止钮出现、发送钮隐藏、textarea focus。
- 多行草稿：`第一行\n第二行`，caret 7。
- 普通 Enter 前后：value/caret 完全不变（7/7），不发送（posts 仍 1）、不停止（停止钮仍在）、不换行、无 notice/error、无排队。
- Shift+Enter：`第一行\n第二行\n`、caret 8，流式仍继续。
- `event.repeat=true`：value/caret 不变，无额外发送。
- **停止**必须用真实 SSE 验证：真实 `/api/ai/chat` 返回 200 `text/event-stream` 后点击停止 → 停止钮消失、发送钮出现、notice “已请求取消本轮对话”、无 error card、输入不恢复原草稿、**不自动发送**。
- 停止后输入新消息并 Enter：`posts` 由 1 → 2，新增 1 条用户气泡；再次 Enter（loading 中）不新增气泡、不双发。

## 3) Enter / 焦点 / IME（PASS + 真实 IME BLOCKED）

- 空闲 Enter：`posts=1`，输入清空，焦点回 textarea，停止钮出现。
- 空 Enter：`posts=0`；纯空白 `"   "` Enter：`posts=0`，原值保留，不发送。
- `event.repeat=true` 合成 keydown：`posts=0`；随后普通 Enter 才发送一次（`posts=1`）。
- 合成 IME：
  - `compositionstart` + Enter（`keyCode=229, isComposing=true`）：value `组合草稿`/caret 不变，不发送。
  - `compositionend` + 立即 Enter（`keyCode=13`）：仍不发送（30ms 守卫）。
  - 60ms 后普通 Enter：发送一次；posts=1。
- **真实中文输入法：BLOCKED**。环境无法可靠产生真实 IME 组合；未冒充验收。

## 4) 失败保稿（PASS）

故障注入方式（浏览器层，不改 DOM）：

1. **开流前 error**：`route.fulfill` 对 `POST /api/ai/chat` 返回 `503 application/json`，`{code:"AI_UNAVAILABLE", message:"注入：开流前 503 故障"}`。
2. **SSE 断流**：`route.fulfill` 返回 `200 text/event-stream` 且空 body（无完成帧）。
3. **等待期网络失败**：路由持住请求，用户输入新内容后 `route.abort('failed')`。

结果：

- 开流前 503：原草稿 `失败保稿A` **恢复**、焦点回 textarea、用户气泡 **1**；重发第二次仍 503 → posts=2，但用户气泡仍 **1**（编辑重跑路径，不重复）。
- SSE 断流：原草稿 `断流保稿B` **恢复**、焦点回 textarea、用户气泡 1、notice “连接中断，结果可能不完整”；无 error card。
- 等待期新内容：发送后输入 `等待期间新内容`，再 abort → 输入保持 **等待期间新内容**，未被原始草稿覆盖；焦点回 textarea；用户气泡 1。
- 主动 stop：输入为空（**不恢复**原草稿），无自动发送，无 error card。
- 预期网络日志：503 / `net::ERR_FAILED` 资源错误，不是未捕获 JS 异常。

## 5) 可用性 / tip / 窄栏 / #3 smoke / JS

### hard-disabled：PASS

- `/api/ai/health` 注入 503 `{available:false}`：textarea disabled、placeholder “AI 当前不可用（已降级，可继续使用页面手动操作）”、发送钮 disabled。
- 真实 suspended GF 会话：textarea disabled、placeholder “挂起等待中：请先在上方处理确认/执行，或点「停止」结束本轮”；停止钮存在。GF 卡仍显示，不伪造。

### tip：hover/focus PASS，Esc **FAIL**

- hover：tip `opacity:1; display:block`。
- 键盘 Tab 聚焦发送钮：`activeIsSend=true`、`focus-visible=true`、tip opacity 1。
- **Esc 后 tip 仍 `opacity:1`、无 hidden 类**；app 只在 mermaid 全屏查看器处理 Escape，tip 没有 Esc 关闭逻辑。与 demo v2（Esc 可关）不一致。
- 证据：`shots/tip-hover-project.png`、`shots/tip-keyboard-project.png`。

### 360/400 无内溢：FAIL

同第 1 节：document scrollWidth 493（360/400），AI 面板 400px 与头部按钮越界；收起面板后仍 493。

### #3 smoke

- tool 卡（真实“列出全部配置定义”）：左扳手 SVG、右侧状态 **“已成功”**，PASS。
- GF 卡（真实 suspended run）：左 15×15 剪贴板/清单 SVG、右侧状态 **“待填写”**，PASS。
- HITL 圆圈问号：模板仍保留 circle + question path，`data-icon-type="hitl"` 分支存在；但本次未产生 live HITL gate，且为避免触发数据变更未强行制造 → **BLOCKED**。tool/GF 图标与右状态未见回退。

### JS

- 未捕获 **pageerror：0**。
- console error 仅出现在注入的 503 / abort 场景（资源加载失败），不是 app JS 异常；正常场景 consoleErrors=[]。

## 关键证据文件

- `geometry.json`（1/4/6、tip、360/400 溢出）
- `core-tests.json`（空闲 Enter、空空白、repeat、IME、流式 Enter、真实 stop、hard-disabled、tool 卡）
- `failure-tests.json`（503 / SSE 断流 / 等待期新内容）
- `gf-inspect.json`（suspended GF 卡、hard-disabled）
- `stop-real.json`（真实 app 停止钮 28×28 / 方块 9×9 / 中心 0/0）
- `inspect.json` / `stream-hold.json`（真实请求与持流状态）

## 关键截图

- 内置浏览器：`shots/builtin-project-initial.png`、`shots/builtin-project-1line.png`、`shots/builtin-project-4line.png`、`shots/builtin-project-6line.png`
- 真实 app 流式：`shots/real-stream-full.png`、`shots/real-stream-composer.png`、`shots/real-stream-stop-button.png`
- 360/400 FAIL：`shots/project-360.png`、`shots/project-400.png`、`shots/project-360-collapsed.png`
- 真实 stop：`shots/project-real-stop.png`、`shots/project-after-stop-send.png`
- 失败保稿：`shots/failure-pre-stream.png`、`shots/failure-pre-stream-retry.png`、`shots/failure-sse-break.png`、`shots/failure-new-content-kept.png`
- hard-disabled / GF：`shots/project-hard-disabled.png`、`shots/gf-suspended.png`
- tool 卡：`shots/project-tool-cards.png`
- tip：`shots/tip-hover-project.png`、`shots/tip-keyboard-project.png`

## 剩余问题 / 阻断

1. **FAIL：360/400 横向溢出**。真实项目页面 `scrollWidth 493`，AI 面板 400px、头部按钮越界；收起面板仍 493。影响窄栏可用性，是显式验收项失败。
2. **FAIL：tip Esc 不关闭**。Esc 后 tip 仍 opacity 1；app 无 tip Esc 逻辑（仅 mermaid viewer 处理 Escape）。
3. **BLOCKED：真实中文 IME**。环境不能可靠产生真实组合，已用合成事件验证守卫，未冒充。
4. **BLOCKED：live HITL 圆圈问号**。未在本次安全操作中产生 HITL gate；模板图标仍在，tool/GF live smoke 通过。


## 测试副作用（如实记录）

- 真实 AI 发送“项目验收测试”在补充会话中产生了一个 suspended `generative_form` run（`a8af6dba-79ec-46f3-bfe8-d6e8ccec873c`），任务中心出现新任务 **#353「项目验收测试-配置导出」（进行中）**；这是 app/AI 行为，不是通过修改 DOM 伪造。
- 验收结束后已通过 app API 取消该 suspended run（`POST /api/ai/cancel/a8af6dba...`，返回 `cancelled:true`）；#353 未手动删除，避免进一步数据变更。
- 未修改代码/demo/docs/服务，未提交。

未做代码修复；页签已关闭。
