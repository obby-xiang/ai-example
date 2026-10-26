<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-066 · 施工复核 · Important 修复（45653e92 / agent-1746）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`RedisFrameStore.java`。
> **稿态**：历史中间稿 —— 该 wire 第 `m176` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt13` 条有正文的 assistant 消息（共 57 条有正文），末条有正文消息为 `m330`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `45653e92` / agent `agent-1746`（`main` = 主会话）；原字符 3,283（链内 1 段、合计 3,283）。
> **链号 / 类别**：`MT-066` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `8%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `8%`。
> **与末条归档（138 件）的关系**：末条 `m330`（2,157 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

已按评审 I1~I6 + Minor 逐项修完，只改了两个新增文件；未 commit/push，未碰产品代码及其它文件。真实 5.0.14 集群仍未跑（本机无 Docker），下文所有绿灯都是静态/局部验证，不是集群证据。

## Important 修复

| 项 | 处理 | 位置 |
|---|---|---|
| I1 | A 阶段重排为：kill 后先采带时间戳的 `CLUSTER INFO`（A0）→ 存活主 PONG（A1）→ 最后才做慢死节点探针（A2）。TSV 改为 `stage / at_ms / rel_ms / target / result`，逐行记录 A-cluster-view、A-survivor-ping、A-dead-probe、B-converged、B-master-ping、B-promoted-ping | `scripts/redis5-cluster-smoke.sh:898`、`:907`、`:920`、`:963`、`:1010` |
| I2 | 新增 `is_expected_connect_failure`：RC 必须是 1 或 124，且输出必须命中 `refused / timed out / Could not connect`；空输出、`PONG`、docker 启动失败一律 FAIL。死节点探针改用该函数，失败时带 rc 和原始输出 | `:187`、`:925` |
| I3 | `with_timeout` 改为接收秒数；`redis-cli --cluster create` 用 `with_timeout 240`，并显式处理 rc=124（不再等 job 的 25 分钟）；死节点探针仍用 10s | `:493`、`:503` |
| I4 | 节点 `docker logs` 只落盘备查，新增 `skip` 明示“不设门禁”；`log-scan.txt` 写明 archived only；窄门禁仍只在 `commands.log` 上，白名单仍为 `Invalid stream ID / CROSSSLOT / Unknown subcommand` | `:1040`、`:1050`、workflow `:14-15` |
| I5 | raw DEL 的断言名/证据改为“raw 单命令 DEL 被 CROSSSLOT；产品 Lettuce `del(K...)` 按槽拆分 ⇒ 不报错、失原子性；逐键 12 次即产品等价面”，并加注释 | `:684-689` |
| I6 | summary 顶部新增独立行“产品 RedisAvailability.available（设计文档 §十 批次 0 ⑤）**未在本网验证**”；`check_failover` 开头再记一条 `SKIP`。不再复述旧事实 F 为 true，环境级证据与产品 503 口径分开 | `:292`、`:845`、`:22-23` |

## Minor 修复

| Minor | 处理 | 位置 |
|---|---|---|
| 1 死代码 | 删除 `count_matches`、`final_master_ids`、`slot_map`、`tsum`、未用 `out`；`RCLI_RC` 保留并用于 I2 | `:187` |
| 2 异槽撞槽 | 无 tag 两键改为最多 5 次后缀重试，仍同槽才 FAIL | `:615-632` |
| 3 Pub/Sub 就绪 | 10s 内未出现 `subscribe` 直接 FAIL + 删订阅容器 + return，不再继续 PUBLISH | `:740-747` |
| 4 清理复核 | 删网络后同时复核残留容器和残留网络，输出两者 | `:331-335` |
| 5 候选键 | 600 → 120 | `:1012` |
| 6 静态门收窄 | 只扫含 `.xRange(` 的主代码文件；移除不存在的 `Range.Bound.excluding`；无命中时 SKIP 并提示随实现更新 | `:347-375` |
| 7 卫生检查 | `check_file_hygiene` 移到分支前，real 模式也执行 | `:1156` |
| 8 标签 | `KNOWN-DEFECT` → `KNOWN-LIMIT`（函数、summary、结果行） | `:94`、`:293`、`:308` |
| 9 artifact/空格 | 证据清单补 `pubsub-subscriber.log / slot-map.txt`；去掉尾随空格 | `:12-14` |
| 10 空数组 | `for row in ${RESULTS[@]+"${RESULTS[@]}"}`，空 RESULTS 不再触发 unbound | `:299` |
| 11 CI 成本 | 未改代码：`scripts/**` 与 `.github/workflows/**` 会命中 `changed-files.mjs` 三面规则，PR 会连带跑 backend+frontend+e2e；需知晓这是 30+ 分钟开销 | — |

workflow 只改了边界注释（产品 available/503 未验、节点日志只归档），触发方式仍未变：只 `workflow_dispatch`，不挂 push/PR/schedule。

## 本机验证命令与结果

| 命令 | 结果 |
|---|---|
| `bash -n scripts/redis5-cluster-smoke.sh` | `BASH_SYNTAX_OK` |
| PyYAML 解析 workflow + 断言 `on`/权限/`runs-on`/timeout/env/无 tab/无 CRLF | `YAML_OK` |
| `bash scripts/redis5-cluster-smoke.sh --static`（当前工作树，产品已是 `inclusive`） | `PASS 7 / FAIL 0 / KNOWN-LIMIT 0 / SKIP 0`，exit 0 |
| 临时目录放 HEAD 版 `RedisFrameStore.java` 后跑 `--static` | `FAIL 1`：`Range.Bound.exclusive` 命中 `:299`，exit 1 |
| 辅助函数测试（I2 六种输入） | refused rc1、timed out rc124、空输出 rc1/rc124、PONG rc0、docker error rc125 全部符合预期 |
| `with_timeout 1 sleep 2` | 返回 124（240s 那套使用同一函数） |
| `pick_key_in_slots` | 多区间、单槽、迁移标记、空行、无命中全部 OK |
| `info_field` / `node_addr_ip` / `node_addr_port` / `record` 管道转义 | OK |
| summary 局部测试 | ⑤ 未验行、`KNOWN-LIMIT` 行均存在 |
| `git status` | 仅 `?? .github/workflows/redis-5-smoke.yml`、`?? scripts/redis5-cluster-smoke.sh`；无 local artifact，无尾随空格 |

**边缘仍要明示**：HEAD 上排他区间仍在（`RedisFrameStore.java:299`），所以 HEAD 跑仍红；工作树里那笔 `inclusive` 是并行修复，不是我改的，当前因此绿。真实 5.0.14 集群的首次执行仍以 CI 那次为准。
