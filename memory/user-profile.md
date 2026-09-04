# 用户画像（跨项目）

> 同步自 Trae 本地记忆 `user_profile.md`（2026-09-04）。项目级规则见同目录 [project-memory.md](project-memory.md)。

## Preferences
- Communication language: Chinese
- Development approach: Provide business scenarios and non-negotiable constraints, leaving technical implementation details to the assistant
- Values reliability in instruction parsing and structured tool calling
- Tool definition location: All tool definitions must be placed in the backend, even for frontend tools; frontend only传递 tool names
- AI workflow architecture: Prefers backend runtime with agent loop, frontend tools use pause-resume mechanism
- Implementation priority: Focus on end-to-end verification and UI interaction validation

## Tech Stack
- Backend: Spring Boot, JDK
- Frontend: Vue3 (Composition API)
- AI: OpenAI specification compatible interfaces

## Trae 会话数据恢复方法论（对话归档/考古，跨项目通用）
用户要求归档完整对话（含思考、回答）时，按以下路径恢复（Windows，用户主目录 `C:\Users\<user>`）：

| 数据 | 路径 | 保真度 |
|---|---|---|
| 用户消息逐字原文 | `AppData\Roaming\Trae CN\User\workspaceStorage\<workspaceHash>\state.vscdb` → ItemTable 表 → key `icube-ai-agent-storage-input-history` → JSON 数组 `inputText` 字段 | ✅ 逐字 |
| 轮次级记录（intent/actions/outcome/learned） | `.trae-cn\memory\projects\<projectHash>\<YYYYMMDD>\session_memory_<sessionId>.jsonl`（每行 JSON） | ✅ 系统逐字 |
| 话题摘要 | 同日期目录 `topics.md` | 摘要 |
| AI 回答/思考正文 | `AppData\Roaming\Trae CN\ModularData\ai-agent\database.db`（**加密库，非标准 SQLite 头，无法解密**；或仅在 Trae 云端） | ❌ 明文不可恢复，只能让用户从界面复制 |

操作要点：
- state.vscdb 是明文 SQLite；用 Node 22 读取：`node --experimental-sqlite --no-warnings script.mjs`（无 sqlite3 CLI 时的方案）
- 先把 db/vscdb **复制到 temp 再读**（避免 Trae 进程锁）；用完必须删除临时副本（含会话敏感数据）
- 定位数据用 Trae 自带 ripgrep 搜二进制：`"D:\Program Files\Trae CN\resources\app\node_modules\@byted-fe\ripgrep-win32-x64\bin\rg.exe" -uuu -l "<锚点串>" <目录>`；锚点用会话 ID 或用户原话
- 排除项（已验证无对话正文）：`snapshot/<sessionId>/` 是工作区 git 快照（回滚用）；`logs/`、`Partitions`、`Local Storage`、`aha/` 均无正文
- 生成归档：脚本直读源文件→写 markdown→只看统计输出（省上下文）；用户消息用 4 反引号 code fence（消息内可能含三反引号）
- 归档后必做：敏感扫描（`sk-[A-Za-z0-9_-]{16,}`、`api[_-]?key`、`Bearer`、`DEEPSEEK_API_KEY=` 替换为 [REDACTED]；实测曾抓到用户粘贴过的真实 key）+ code fence 偶数配对校验
- 上下文被压缩后写归档会"摘要的摘要"——先走上述恢复路径拿原文，不要直接拿压缩摘要当归档
