<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-312 · 其它 · 措辞修正闭环（8 处/5 文件，check-docs 改后 6/6 绿，零 git 写）。两条遗留裁定：条目 18 旧"失败即红"属历史留痕**不动**（去向表只追加纪律）；扫描面已在修正措辞中显式化，**不另开章节**。（f625e0a2 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`check-docs`、`ai-example-<OLD_REPO_DIR>`、`pre-commit`、`scripts/ci`。
> **稿态**：历史中间稿 —— 该 wire 第 `m10` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt7` 条有正文的 assistant 消息（共 19 条有正文），末条有正文消息为 `m28`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `f625e0a2` / agent `main`（`main` = 主会话）；原字符 3,099（链内 1 段、合计 3,099）。
> **链号 / 类别**：`MT-312` / **其它**；判定：助手结论/验收记录（标题无主题词）；**不是用户批准**。
> **轮次**：位于该 wire 第 3 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `4%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `8%`。
> **与末条归档（138 件）的关系**：末条 `m28`（7,520 字符）未入末条归档。
> **脱敏**：路径 3 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 2 处。

---

措辞修正闭环（8 处/5 文件，check-docs 改后 6/6 绿，零 git 写）。两条遗留裁定：条目 18 旧"失败即红"属历史留痕**不动**（去向表只追加纪律）；扫描面已在修正措辞中显式化，**不另开章节**。

T4 批次复核关全过，进入红队关。GLM-5.3 红队卡如下，**请转交 GLM-5.3**，报告落盘后回复"已完成"即可：

```text
═══ GLM-5.3 红队任务卡：M2-T4 批次（六分支合流·工具链与文档门禁批）═══

【子智能体分工条款】你可派自己的 deepseek-v4-flash 子智能体执行机械活（跑测试、扫文件、抓 diff、复现验证）；你本体只做关键判断、攻击面分析、结论裁决、报告撰写。本卡结构=关键判断点清单+机械活清单。

【对象与环境】主仓库 <REPO>，分支 main，HEAD=e602c3b。攻击对象=工作区未提交改动 19 M + 2 ??（T4 原批 16 项+补充批+措辞修正批，三批叠加）。零 git 写操作；你的扫描/变异只在 scratch 副本进行。Windows+Git Bash；Maven 全路径 "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"；node v22/yarn 1.22。

【背景口径】本批主旨：8080→8081 端口收敛+README 端口唯一事实源、app.server.* 死键删、Caffeine 依赖删、CI 五 job（backend/frontend/e2e/docs/scan-history）、baseline-combined 回填、check-docs.mjs 六断言门禁、scan-history 全历史扫描【报告制非阻断】（指挥官裁决：命中不红、扫描器故障仍红）、T4-3 重挂/回放帧 atMs 覆盖（实时帧刻意不做，转下批）。已过关：施工报告 <LOCAL_WORK>\t4-reports\M2-T4-施工报告.md、T4-补充施工报告.md、T4-复核报告.md（10/10 PASS 0 阻断）、T4-措辞修正报告.md。你的职责不是复跑复核，而是找复核没想到的攻击面。

【关键判断点清单（你本体逐条裁决）】
J1 scan-history 报告制是否埋了"静默降级"：码 1 命中→::warning:: 不红、码 2 故障→exit 1 红 的映射有无漏径（如 set +e 吞掉别的命令失败、$GITHUB_OUTPUT 写失败、artifact 上传失败导致 job 红的非预期路径）；报告制 job 红了是否真能引起注意（summary 文案）；报告是否只写路径不写命中内容（防密钥二次扩散）。
J2 "既有阻断面不削弱"是否成立：docs job 断言⑤ git ls-files 全量+断言① 4 入口文档+pre-commit，对比本批改动前的阻断面，有无被偷偷削弱的点（如 ci.yml 某 step 顺序变化、check-outs 深度、豁免正则变宽）。
J3 check-docs.mjs 六断言语义是否与改动前等价（git diff HEAD -- scripts/ci/check-docs.mjs 是新增文件——作为新门禁审其断言本身：命名纪律正则能否被绕过——如全角字符、零宽字符、大小写混淆、<OLD_REPO_DIR> 用 Unicode 连字符；豁免注释机制能否被滥用——随便加个注释就豁免？）。
J4 T4-3 foldDeadline 攻击面：atMs 为负数/NaN/未来时/极大值时的行为；expiresAt 与 atMs 不一致时谁优先；回放帧"按帧落档剩余时长重起算"是否会导致 deadline 被人为延长（如旧帧重放续命）；openGenerativeForm/resetPendingWatch/toPendingCall 三处透传有无漏路径（快照重建 deadline:null 路径是否真不受新逻辑影响）。
J5 后端原批：8080→8081 有无漏网（Grep 全仓 8080 残留，含文档/脚本/注释——区分"事实源改口径"与"漏改"）；app.server.* 死键删后有无运行期残留读取；Caffeine 删除后 dependency:tree 与 import 双 0 是否真实。
J6 CI 五 job 接线：e2e 的 -EnableGf6 是否真让 GF6 进主计数 29；docs job 失败是否真阻断合并（required 检查之外的盲区知悉即可）；upload-artifact@v5 Node 20 弃用告警是否本批引入的新债。
J7 文档真实性抽查：README 端口唯一事实源表述 vs 实际残留；docs/evidence/四层一致率总账.md 双分母 92.0%/100% 的分子分母口径是否自洽；M2 去向表新增行是否与代码实况一致。
J8 终扫：改动面 21 文件命名纪律禁词（main+分隔符+v2 任意大小写，豁免仅 3 spike 脚本）、盘符/SECRET/用户名/内网 IP，git 信封行不计。

【已知项登记（不要重复报，除非你证明判断错误）】
K1 复核 8 观察项：scratch 报告数字笔误（application.yml 0/7→实 0/3、yarn.lock 假阳性 5→实 0）属仓库外报告不修；t43b 验证脚本抽取锚对 CRLF 敏感（fail-closed 不致假绿）；全仓 tracked 141 处 10.x 系测试/证据数据口径外既存面。
K2 A-1 裁决：不新增独立工作树口径 CI 阻断 job（首跑即红风险实证成立），登记观察项。
K3 实时 SSE 帧 atMs 覆盖（需改后端 SseChatEmitter 序列化顺序）刻意不做，已登记下批专项。
K4 条目 18 旧"失败即红"留痕为历史记录，去向表只追加纪律不回改。

【机械活清单（派你的子智能体）】
M1 git status/diff 全量清点与 21 文件归属核对；M2 Grep 全仓 8080/app.server./caffeine 残留扫描；M3 check-docs 反向注入（命名纪律绕过尝试：Unicode 连字符/全角/零宽/豁免注释滥用）；M4 foldDeadline 边界值变异测试（scratch 副本，至少覆盖 J4 各形态）；M5 ci.yml YAML 解析+step 级失败路径枚举；M6 文档口径抽查实读（J7 三项）；M7 脱敏/命名终扫。

【分级与产出】 findings 按 S1=提交前必修/S2=应修可本批/S3=下批/S4=知悉 分级；每个 finding 给证据（文件+类名#方法名主锚+行号辅）与复现方法。报告落盘 <LOCAL_WORK>\t4-reports\T4-红队确认.md。结论只有两种：放行（无 S1/S2）或打回（列 S1/S2 清单）。
```

**当前流水线状态**：施工✅ → 复核✅（10/10 PASS 0 阻断）→ 措辞修正✅ → **红队（等你转交 GLM）** → 提交官（细分提交+push+CI 核验闭环，届时新五 job 首次全量上线，scan-history 报告制首跑应绿）。
