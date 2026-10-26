<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-067 · 设计 · ① 死节点判定：docker inspect 作为决定性事实（45653e92 / agent-1746）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`RedisFrameStore.java`。
> **稿态**：历史中间稿 —— 该 wire 第 `m209` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt16` 条有正文的 assistant 消息（共 57 条有正文），末条有正文消息为 `m330`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `45653e92` / agent `agent-1746`（`main` = 主会话）；原字符 2,175（链内 1 段、合计 2,175）。
> **链号 / 类别**：`MT-067` / **设计**；判定：助手设计方案/计划（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 3 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `3%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `3%`。
> **与末条归档（138 件）的关系**：末条 `m330`（2,157 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

本轮只改了 `scripts/redis5-cluster-smoke.sh`（workflow 无需再改注释），未碰其它文件，未 commit/push。真实 5.0.14 集群仍未跑（本机无 Docker），以下均为静态/局部验证。

## ① 死节点判定：docker inspect 作为决定性事实

- kill 后先做最多 5s 的 `docker inspect -f '{{.State.Running}}'` 轮询（25×0.2s），只有 `false` 才算故障注入成功。
- `State.Running=true` → **FAIL**“docker kill 未生效：容器仍在运行”，写 TSV 后返回。
- inspect 输出空/异常 → **FAIL**“无法确认容器已停止”，写 TSV 后返回。
- `false` → PASS 一条决定性事实，继续 A0/A1/A2。
- A2 探针改为三分类：
  - 强证据：`is_expected_connect_failure`（rc∈{1,124} 且命中 `refused / timed out / Could not connect`），note 注明 **refused 可能是节点释放 IP 后被探针容器占用**。
  - 弱证据：`rc=124` 且输出为空 → PASS“容器已停止但 10s 无响应（弱证据）”，不再误报“探测未执行”。
  - 其余（PONG、docker 启动错误、rc1 空输出等）→ FAIL“探测本身异常”，带 rc 与原文。
- 新增 `write_failover_stages`，早退（容器仍运行/无法确认）也会把 `A-container-state` 写进 TSV，不会丢决定性证据。

位置：`scripts/redis5-cluster-smoke.sh:914`（inspect）、`:925`（case）、`:966`（A2）、`:281`（TSV helper）。

## ② summary 结论句

两条分支都显式写 KNOWN-LIMIT/SKIP 不计通过：

- 有 FAIL：`**结论：有 N 项失败；KNOWN-LIMIT 与 SKIP 均不计入通过（见下表）。**`
- 无 FAIL：`**结论：无 FAIL；KNOWN-LIMIT 与 SKIP 均不计入通过，SKIP 明细见下表。**`

不再使用“全部检查通过”。位置：`:318-320`。

## ③ 契约门失效顶部单列

- 新增全局 `XRANGE_GATE_LABEL`，summary 顶部固定一行：`| 产品 XRANGE 契约门 | PASS / FAIL / 失效... |`。
- 找不到 `.xRange(` 文件时：顶部显示 `**失效：未发现 .xRange( 调用，需人工确认**`，同时保留 SKIP 行；不再只埋在 SKIP 里。
- 目录缺失、命中排他、正常通过也各有明确标签。

位置：`:57`、`:303`、`:362-389`。

## 验证命令与结果

| 命令/场景 | 结果 |
|---|---|
| `bash -n scripts/redis5-cluster-smoke.sh` | `BASH_SYNTAX_OK` |
| 当前工作树 `--static`（产品已是 `inclusive`） | `PASS 7 / FAIL 0 / KNOWN-LIMIT 0 / SKIP 0`，exit 0；顶部契约门 `PASS`；结论句为“无 FAIL；KNOWN-LIMIT 与 SKIP 均不计入通过…” |
| HEAD 版 `RedisFrameStore.java` 临时目录复跑 `--static` | `FAIL 1`（`:299 Range.Bound.exclusive`），exit 1；顶部契约门 `FAIL` |
| 无 `.xRange(` 文件的临时树复跑 `--static` | exit 0；顶部契约门显示 `**失效：未发现 .xRange( 调用，需人工确认**`，并有对应 SKIP |
| `is_expected_connect_failure` 单测 | refused rc1、timed out rc124 → strong；`124+空输出` → 非 strong（走弱证据分支）；PONG → 非 strong |
| `write_failover_stages` 单测 | 表头、`State.Running=false` 行、`rc=124 empty（弱证据）` 行均正确 |
| summary 局部测试 | 结论句同时包含 KNOWN-LIMIT 与 SKIP |
| YAML 解析 | `YAML_OK`（workflow 本轮未改） |
| `git status` / 卫生 | 仅两条 `??`；无 local artifact、无尾随空格；未 commit/push |

真实集群仍未跑：Docker 不存在，A 阶段 inspect/探针、B 阶段收敛等只能在 CI 首次执行时取得真实证据。
