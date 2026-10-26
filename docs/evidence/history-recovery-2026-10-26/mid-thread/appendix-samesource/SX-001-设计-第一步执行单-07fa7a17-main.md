<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# SX-001 · 设计 · 第一步执行单（07fa7a17 / main）

> **项目归属**：**同源 ai-example 分支/评测附录**（不计入主仓成果）。
> **归属依据**：会话属 ai-example 分支合流/早期分支评测（非本主仓）。
> **稿态**：历史中间稿 —— 该 wire 第 `m5` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt3` 条有正文的 assistant 消息（共 151 条有正文），末条有正文消息为 `m337`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `07fa7a17` / agent `main`（`main` = 主会话）；原字符 2,674（链内 1 段、合计 2,674）。
> **链号 / 类别**：`SX-001` / **设计**；判定：助手设计方案/计划（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `6%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `6%`。
> **与末条归档（138 件）的关系**：末条 `m337`（617 字符）未入末条归档。
> **脱敏**：路径 6 / 会话标识 0 / 身份 3 / 密钥 0 / 旧仓名与同源仓名 9 处。

---

明白。第一步改由 AI 执行，提示词完全自包含。模型选择上，这一步是纯机械 git 操作，**不需要强模型**——按手册的模型分工，走量执行用 Flash 级即可。

---

## 第一步执行单

| 项 | 内容 |
|---|---|
| **Harness** | OpenCode（或 Kimi Code CLI），开**全新对话** |
| **模型** | **GLM-5.3-Flash**（首选）或 DeepSeek V4 Flash（备选）。纯 git 脚手架操作，用强模型是浪费 |
| **工作目录** | `<LOCAL_WORK>\ai-example-code` |
| **预算** | 10 分钟内应完成 |

### 完整提示词（整段复制，一字不改发给新对话）

```text
你是一个自动化执行代理。请在 Windows 系统（Git Bash 环境）下完成以下仓库脚手架任务。
所有命令通过 bash 执行，Windows 路径在 bash 中用 <LOCAL_WORK>/... 形式表示。

【背景】
项目是 GitHub 仓库 https://github.com/<OWNER>/ai-example（AI 辅助配置快速实施平台，
Spring Boot 3 + Vue 3 全栈演示项目）。该仓库有 7 个分支，是同一命题的 7 套独立实现，
现在要做分支合流。7 个分支的代码已在本地以独立克隆形式存放于 <LOCAL_WORK>\ai-example-code\
下的 7 个子目录（ai-example-main、ai-example-claude-opus-5.5、
ai-example-claude-opus-5.5+deepseek-v4-pro、ai-example-deepseek-v4-pro、
ai-example-glm-5.3、ai-example-kimi-k3、ai-example-trae），每个子目录 checkout 在
与目录名对应的分支上。

【任务】建立合流基线分支 <OLD_REPO_DIR>。规则：<OLD_REPO_DIR> 必须从 claude-opus-5.5+deepseek-v4-pro
分支切出，保留其完整 git 历史，禁止新建空仓库，禁止改动任何业务代码文件。

【执行步骤】
1. cd <LOCAL_WORK>/ai-example-code
2. git clone https://github.com/<OWNER>/ai-example.git <REPO>
   （若网络克隆失败，改用本地克隆兜底：
     git clone ./ai-example-claude-opus-5.5+deepseek-v4-pro <REPO>
     cd <REPO>
     git remote set-url origin https://github.com/<OWNER>/ai-example.git
     然后继续下一步）
3. cd <REPO>
4. git checkout -b <OLD_REPO_DIR> origin/claude-opus-5.5+deepseek-v4-pro
5. 验证：git log --oneline | wc -l 应 ≥ 35；git status 应干净。记录实际数字。
6. 创建目录：mkdir -p docs/merge docs/adr docs/evidence
   注意：仓库已有 docs/ 目录，内含 requirements.md 等产品文档，禁止删除或覆盖它们。
7. 复制参考文档：cp "<LOCAL_WORK>/Kimi_Agent_分支评估与演进建议/"*.md docs/merge/
   应拷入 5 个 md 文件（01 至 05 编号），逐个确认存在。
8. 创建空决策登记表 docs/adr/DECISION-REGISTER.md，内容为：
   # 决策登记表（Decision Register）
   空表头一行：| 编号 | 决策 | 证据等级 | 证据来源 | 提出 | 复核 | 签字 | 推翻条件 | 状态 |
   加分隔行 |---|---|---|---|---|---|---|---|---|
9. git add docs/ && git commit -m "chore: 初始化 <OLD_REPO_DIR> 合流基线（参考文档+决策登记表+证据目录）"
   注意：只提交 docs/ 下的变更，不提交其他任何东西。禁止 push 到远程。
10. 环境侦察（只读，不安装不修改）：依次执行并记录输出第一行：
    java -version
    node -v
    yarn -v
    "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" -v

【纪律】
1. 只做上述步骤，不自行发挥、不"顺手优化"任何东西；
2. 每条结果只记录你实际观察到的输出，禁止"应该成功了"式臆断；
3. 任一步骤失败：停止，如实报告失败命令、完整报错原文、你已做过的尝试，等待指示，
   禁止绕过问题继续；
4. 不确定的取舍停下来问我，不自行决定。

【输出格式】最后给我一份执行报告：
1. 每步的实际结果（命令输出关键行）；
2. git log 提交总数、当前分支名、git status 状态；
3. docs/merge/ 下文件清单；
4. 环境侦察四项的版本信息（Maven 需确认能输出版本号）；
5. 结论：全部成功 / 哪步失败及原因。
```

### 你拿到报告后的验收点

- 提交总数 ≥ 35，分支名 `<OLD_REPO_DIR>`，status 干净
- `docs/merge/` 恰好 5 个 md；产品文档 `requirements.md` 等原样未动
- Maven 能输出版本号（用你给的 IDEA 内嵌路径，**无需另装 Maven**）
- 报告里没有"应该"、"大概"这类词——有就驳回重做

---

报告发我，我核验后给你**第二步（S0.5 分支声明核验 V1–V10）**的模型分工与提示词。
