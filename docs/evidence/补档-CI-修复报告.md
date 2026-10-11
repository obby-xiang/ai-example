> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# CI 修复报告（仓库 `<owner>/<repo>` · 分支 main · 2026-10-09）

- 依据诊断：`ci-reports/ci-失败诊断-2026-10-09.md` §9「选项 B（保留口径·语义正确）」。
- 工作仓库：`<MAIN_REPO>`（本地检出路径在本报告中一律记作 `<MAIN_REPO>`，不使用盘符绝对路径）。
- 修复起点：HEAD = origin/main = `03e6cfc`；修复终点：`56883f6`（已推送，HEAD == origin/main）。
- 处置口径：**选项 B**（`--maxmemory-policy noeviction` 从 `services.redis.options` 移至 `services.redis.command`）；**未触发回退**（选项 A 未启用）。
- 数据来源：本地 git 实测 + GitHub 匿名 REST API（`api.github.com`，无 token）。时间为 UTC（括号内北京时间）。

---

## 0. 结论速览

| 项 | 结论 |
| --- | --- |
| 修复提交（笔1） | `78aa1923a2431ef98830db4aa4e853c95d859158`（`78aa192`） |
| 登记提交（笔2） | `56883f668ba422af7c457f08dce5e56983462c4d`（`56883f6`） |
| 推送核验 | `git push origin main` 成功：`03e6cfc..56883f6`；`HEAD == origin/main == 56883f6`【实测】 |
| 触发 run | run `37928327340`，head_sha `56883f6`，created_at 2026-10-09T12:10:39Z（北京 20:10:39） |
| run conclusion | **success**（completed 12:14:01Z）【实测】 |
| E2E job | job `113813109929`，**success**；「Initialize containers」**success**（12:12:15 → 12:12:25，10s）【实测】 |
| 是否回退到选项 A | **否**。E2E 已过 `Initialize containers` 且全绿，回退条件未成立 |
| 遗留 | ① CI 内**未**断言 `maxmemory-policy` 实际取值（选项 B 依赖官方 redis 镜像 entrypoint 的 `-` 兜底；建议后续按选项 C 加 `CONFIG GET` 断言）；② `ubuntu-latest → Ubuntu 26`（2026-10-19 起）观察位已登记；③ 既有 warning：`actions/upload-artifact@v5` 被强制跑在 Node 24（与本次故障无关） |

---

## 1. 笔1：`.github/workflows/ci.yml`（选项 B）

**改动意图单一**【实测，`git diff .github/workflows/ci.yml`】：仅 redis service 一处，含 1 行功能性改动 + 注释同步。

```diff
@@ -97,8 +102,8 @@ jobs:
       redis:
         image: redis:7
         ports: ['6379:6379']
+        command: --maxmemory-policy noeviction
         options: >-
-          --maxmemory-policy noeviction
           --health-cmd "redis-cli ping"
           --health-interval 5s
           --health-timeout 3s
           --health-retries 12
```

注释同步（`ci.yml:85-92`）：在原 T3-9 注记后追加 5 行，说明该参数是 **redis-server 服务端参数**、只应写在 `services.redis.command`（runner 追加在镜像名之后 ⇒ `docker create redis:7 --maxmemory-policy noeviction`，由官方镜像 entrypoint 兜底成 redis-server 参数），而 `services.redis.options` 只接受 `docker create` 参数，混入即以 exit 125 使「Initialize containers」直接失败（2026-10-09 实证）。注释中原有的 T3-9 口径（AI 键"宁报错不丢帧"）**保留未改**。

**语法自查**【实测】：`yaml.safe_load` 解析结果

```
{'redis': {'image': 'redis:7', 'ports': ['6379:6379'],
           'command': '--maxmemory-policy noeviction',
           'options': '--health-cmd "redis-cli ping" --health-interval 5s --health-timeout 3s --health-retries 12'}}
```

即 `command` 与 `options` 同级、均为 service 字段，缩进 8 空格与 `image`/`ports` 对齐；`options` 已不含非法项。

**行尾一致性**【实测】：工作区两文件均为全 CRLF（`cr == lines`），仓库 blob 为全 LF（blob 内 CR 计数 = 0），`core.autocrlf=true`；diff 无整文件行尾抖动，仅有意图内改动。

---

## 2. 笔2：`docs/M2-排期计划.md`（遗留去向表登记）

追加 2 行至「b) 遗留去向（T3a 未闭环部分）」表尾（原表 23 行 → 25 行，`+2 -0`）：

| 追加行（摘要） | 去向登记要点 |
| --- | --- |
| CI 排障口径：job 内 `services` 的步骤「Initialize containers」失败 ⇒ 先查 `services.<id>.options` 是否为合法 `docker create` 参数 | 观察项（CI 排障口径，2026-10-09，本批实证）：服务端参数混入 `options` ⇒ docker CLI 未知 flag ⇒ **exit 125** ⇒ 容器创建在镜像拉取前即被拒；对比健康检查失败最坏 ≥ 60s 的时长形态可区分；该失败发生在 checkout 之前，**不要按代码回归排查**；作业日志匿名 403 时用 `GET /repos/<owner>/<repo>/check-runs/<job_id>/annotations` 匿名取失败命令行，step 时间线走 `GET /actions/runs/<run_id>/jobs`；正确落点是 `services.<id>.command` |
| `ubuntu-latest` 自 **2026-10-19** 起迁移至 Ubuntu 26 —— E2E runner（pwsh / JDK 21 / docker 版本）潜在风险点 | 观察项（CI 风险预警，2026-10-09）：到期前在隔离 run 验证本仓 E2E；切换后首个红 run **优先按「镜像变更」而非「代码回归」定位**（来源：平台注解 notice，诊断报告 §7） |

未改动文件头版本注记（v1.6 等），避免与去向表登记无关的正文变更。

---

## 3. 提交与推送【实测】

```
78aa192  fix(ci): redis services options 误写 docker create 参数——--maxmemory-policy 移至 command 字段（exit 125 修复）
56883f6  docs: CI 排障口径与 ubuntu-latest 迁移预警登记 M2 排期去向表
```

- 均使用**显式路径 add**（`git add .github/workflows/ci.yml` / `git add docs/M2-排期计划.md`）+ 提交时同路径 pathspec；**未使用** `git add -A`、**未使用** `--no-verify`。
- 提交前钩子放行：`✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`（两笔均实测）。
- 每笔 `git diff --cached --name-only` 仅列出该笔目标文件（笔1 = 1 文件 6 增 1 删；笔2 = 1 文件 2 增）。
- `git push origin main`：`03e6cfc..56883f6  main -> main`。
- push 后 `git rev-parse HEAD origin/main` 两值相同（`56883f6…2c4d`）；`git status --short` **仅剩他人在制品**（属预期，未触碰任何一项）：`backend/.../ContextBuilder.java`、`frontend/src/stores/workspace.ts`、`frontend/src/views/ExportWizardView.vue`、`frontend/src/views/ImportWizardView.vue`（M）与 `backend/.../ContextBuilderSelectedDefsTest.java`（??）。
- **旁路异常（非本卡点动因，仅记录）**：本卡点作业期间，工作区中他人的两个未跟踪探针文件 `frontend/__issue3_probe.html` / `frontend/__issue3_probe.ts` 由**并行施工棒自行删除**（卡点开始时 `git status` 可见、收尾时已不存在）。本卡点**未**对这两个文件做任何操作，其消失不影响本次两笔提交（两笔提交只含各自点名文件，`git log` 无第三方提交混入）。

---

## 4. 终扫结论【实测】

范围：两笔提交的完整 `git show` 文本（含 diff 与元信息）。

| 模式 | 命中数 | 说明 |
| --- | --- | --- |
| SECRET（`gh[pousr]_*` / `github_pat_*` / `sk-*` / `AKIA*` / `-----BEGIN` / `xox[baprs]-` / `password|secret|api_key|token =`） | **0**（内容行） | 上传件与 workflow 内亦无仓库密钥引用（`AI_API_KEY` 为固定占位串） |
| PATH（盘符绝对路径形 / 用户主目录形路径） | **0** | 提交内容中不含任何本地路径；本报告自身亦不使用盘符绝对路径（仓库与报告位置分别记作 `<MAIN_REPO>` 与 `ci-reports/`） |
| USER（本机用户名 / 邮箱 / 用户主目录形路径） | 内容行 **0** | 仅命中 git 元信息中的 `Author:` 行（2 处，为仓库既有身份信息，非本次新增内容） |
| 命名纪律（`main` + 分隔符 + `v2` 任意大小写组合） | **0** | 两笔提交内容零命中 |

---

## 5. CI 核验【实测】

轮询方式：匿名 `GET /actions/runs?branch=main&per_page=3`（1 次）与 `GET /actions/runs/<id>`（3 次，间隔 ≥ 95s；另加 `/jobs` 1 次、`/check-runs/<job_id>/annotations` 1 次）。目标 run 由 `head_sha = 56883f6` 精确匹配，非按时间近似认定。轮询结束后匿名配额剩余 10/60，未触限。

### 5.1 run 级

| 观测到的 `updated_at` (UTC) | 该次轮询读到的 `status` / `conclusion` |
| --- | --- |
| run `created_at` = 12:10:39 | 首次列表查询命中：`queued`（head_sha = `56883f6`） |
| 12:12:13 | 第 1、2 次轮询：`in_progress` / `null`（此时后端 job 已 success、E2E 已起跑） |
| 12:14:01 | 第 3 次轮询：**`completed` / `conclusion = success`** |

同分支前 3 个 run 均为 `failure`（`03e6cfc` / `ee1d3c4` / `2227d02` 系的同一坏配置），本次为本仓**修复后首个 run**；`created_at` 12:10:39 紧接 push（本地 `git push` 完成于 2026-10-09 20:10:29 +08:00 = 12:10:29Z），可确认该 run 由本卡点两笔提交触发。

### 5.2 job / step 级（run `37928327340`，`GET /actions/runs/37928327340/jobs`）

| job | job id | conclusion | 起止 (UTC) |
| --- | --- | --- | --- |
| 后端编译与单测 | 113812576688 | **success** | 12:10:43 → 12:12:09 |
| 前端类型检查与构建 | 113812577220 | **success** | 12:10:43 → 12:11:36 |
| E2E 全流程（28 用例 · 桩上游 + redis） | 113813109929 | **success** | 12:12:13 → 12:14:00 |

E2E job 关键步骤（全 17 个已执行步骤均 success）：

| # | 步骤 | 结论 | 起止 (UTC) |
| --- | --- | --- | --- |
| 1 | Set up job | success | 12:12:14 → 12:12:15 |
| **2** | **Initialize containers** | **success** | **12:12:15 → 12:12:25（10s）** |
| 3 | Run actions/checkout@v5 | success | 12:12:25 → 12:12:26 |
| 6 | 打包后端（跳过单测：单测已由 backend job 全量跑过） | success | 12:12:30 → 12:12:49 |
| 7–9 | 桩上游自检 → 启动桩上游 → 启动后端 | success | 12:12:49 → 12:12:56 |
| 10 | 就绪等待（`/api/ai/health`：available + **redis.available**） | success | 12:12:56 → 12:13:06 |
| 11 | 种子就绪等待（`/api/definitions` 的 data 长度 ≥ 15） | success | 12:13:06 → 12:13:08 |
| **12** | **运行 E2E（28 用例）** | **success** | **12:13:08 → 12:13:54** |
| 13–14 | 归档证据 / 收尾（`if: always()`） | success | 12:13:54 → 12:13:55 |
| 25–28 | Post 步骤 + Stop containers | success | 12:13:55 → 12:13:56 |
| 29 | Complete job | success | 12:13:56 |

**对照（修复前 → 修复后）**【实测】：

| 项 | 修复前（run 37926997881 / 03e6cfc） | 修复后（run 37928327340 / 56883f6） |
| --- | --- | --- |
| Initialize containers | **failure**（11:59:33 → 11:59:39，6s） | **success**（12:12:15 → 12:12:25，10s） |
| 后续步骤 | 全 skipped（checkout 起） | 全 success（含 28 用例） |
| E2E job conclusion | failure | **success** |
| check-runs failure 注解 | 1 条（含 `docker create … --maxmemory-policy noeviction …`，exit 125） | **0 条 failure**（仅 2 条已知 warning/notice） |

**注解复核**【实测】：`GET /check-runs/113813109929/annotations` 返回 2 条，与既往成功 run（job `113753004081`）**逐字同形**，无 failure 级注解：

1. `warning`：`Node.js 20 is deprecated … actions/upload-artifact@v5`（既有，与本次无关）；
2. `notice`：`The ubuntu-latest label will migrate to Ubuntu 26 beginning October 19, 2026 …`（已按第 2 节登记为观察项）。

**附带反证**【实测】：E2E 内「就绪等待（`/api/ai/health`：available + redis.available）」在 10s 内通过、健康检查 `--health-cmd "redis-cli ping"` 未超时（最坏形态 ≥ 60s），说明 redis 容器**成功创建且进入 healthy**，即 `command: --maxmemory-policy noeviction` 未破坏镜像启动路径（官方镜像 entrypoint 的 `-` 兜底路径成立）。

---

## 6. 回退判定

| 触发条件 | 是否成立 | 处置 |
| --- | --- | --- |
| E2E 仍挂在「Initialize containers」 | **否**（success，10s） | 无需改选项 A、无需补 fix 提交 |
| 失败点换位（过了 Initialize containers 但后续步骤红） | **否**（28 用例全绿，无失败/跳过步骤） | 无需停手上报 |

结论：**未触发回退**；选项 B 一次通过，CI 闭环。

---

## 7. 遗留与后续建议（未闭环项，明确列出）

1. **CI 内未断言 `maxmemory-policy` 实际取值**（选项 B 的已知代价）：本 run 只能证明容器创建/健康/28 用例通过，**不能**证明容器内 `CONFIG GET maxmemory-policy` 返回 `noeviction`——仓库内 E2E 脚本（`verify-e2e.ps1` 等）无该断言（已 grep 确认：`noeviction` 仅出现在 workflow 注释/字段与技术文档中，无测试断言）。若要升级为「实测断言」，按诊断报告**选项 C** 在 checkout 之后、运行 E2E 之前加一步 `docker exec … redis-cli CONFIG GET maxmemory-policy | grep -q noeviction`。
2. **`ubuntu-latest → Ubuntu 26`（2026-10-19 起）**：已登记 M2 去向表；到期前建议在隔离 run 上验证本仓 E2E（pwsh / JDK 21 / docker 版本均可能变化）。
3. **既有 warning：`actions/upload-artifact@v5` 被强制跑在 Node 24**（Node 20 弃用）：与本次故障无关，低成本可择机处理。
4. **诊断报告 §10 待补项之一仍未取到**：修复后新 run 的注解中**没有**打印成功的 `docker create` 命令行（注解只在失败时产生），故 docker CLI 侧的 `unknown flag: --maxmemory-policy` stderr 原文仍未能从匿名通道取到；该缺项不影响结论（A/B 对照 + 30 分钟内由红转绿已构成因果闭环）。若需原文，需 owner 权限读作业日志正文。
5. **技术方案文档字面口径无需回灌**【实测】：`docs/03-技术方案文档-v2.1.md:679` 记「`.github/workflows/ci.yml` 的 e2e job `redis` service 参数加 `--maxmemory-policy noeviction`」——本修复仍在「redis service 参数」范围内（`options` → `command`），该句字面仍成立，故未改动该文档；如团队要求点名字段，可另批回灌。

---

## 8. 复核命令（只读，均可重放）

```bash
# 本地
git -C <MAIN_REPO> log --oneline -2
git -C <MAIN_REPO> show --stat 78aa192 56883f6
git -C <MAIN_REPO> rev-parse HEAD origin/main
git -C <MAIN_REPO> status --short          # 预期仅剩他人在制品
git -C <MAIN_REPO> diff HEAD~2 HEAD -- .github/workflows/ci.yml

# 远端（匿名）
curl -s "https://api.github.com/repos/<owner>/<repo>/actions/runs?branch=main&per_page=3"
curl -s "https://api.github.com/repos/<owner>/<repo>/actions/runs/37928327340"
curl -s "https://api.github.com/repos/<owner>/<repo>/actions/runs/37928327340/jobs"
curl -s "https://api.github.com/repos/<owner>/<repo>/check-runs/113813109929/annotations"
```
