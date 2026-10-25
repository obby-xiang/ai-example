> 来源=<仓外证据>/g12-stub-order-report.md（2026-10-26 归档）· G-12 桩跨用例状态回归（测试编排问题，非产品/SSE 缺陷）；属历史证据，不代替终稿/现行基线
# G-12 桩跨用例状态回归报告（g12-stub-order）

隔离树：`<仓外证据>/g12-stub-order`（基线 `2816f66`）。未提交、未推送，未改动主仓或其它工作树。

## 结论

CI run `40214177582` 里 G-12 的失败来自**桩（stub）跨用例共享会话台账 + 异步续跑到来的顺序**：
G-11/G-12 的提示词此前完全相同，G-12 的首请在 G-11 的续跑之前到达，桩按"每提示词只路由一次"的
台账把 G-12 退化成 18 字文本收尾。**不是模型服从率问题，也不是产品或 SSE 缺陷**；产品日志里的
`newSession` + 23 tools 均正确，SSE 只是把桩给的文本传下来。

## 根因（源码实证）

- `scripts/ci/stub-upstream.mjs` 的 `decide`（613–744 行）用**首条 user 文本前 160 字**做会话指纹
  （`fingerprint`），台账 `bucket = {calls, lastLen, used}` 跨请求累积；只有 `messages.length`
  **严格回退**时才清零，`used` 记录已用过的路由（"每提示词只路由一次"）。
- `scripts/verify-e2e.ps1` 的 G-11、G-12 此前都传 `Ai-FormSuspend $sid 'gf-options-from'`，
  生成的首条 user 文本一字不差 ⇒ **同一个 bucket**。
  1. G-11 首请（2 条消息）命中 `GF-OPTIONS-FROM`，`bucket.used` 记下该路由；
  2. G-12 首请（同样 2 条消息）若在 G-11 的 4 条消息续跑之前到达，`2 < lastLen(2)` 不成立 ⇒ 不清零；
  3. 路由已在 `used` 里 ⇒ 循环跳过 ⇒ 落到 `CLOSING_TEXT`（18 字"好的，本轮已完成，请查看上方的结果。"）；
  4. 另一次 CI 顺序相反（G-11 续跑先到，`lastLen` 变 4，G-12 的 2 条消息严格回退 ⇒ 清零）⇒ 通过。
     同一份代码时红时绿，取决于异步续跑的到达顺序。
- G-09/G-10 共用 `gf-linkage`，同型风险（本次 CI 未触发）。

## 改动（严格最小边界）

| 文件 | 改动 |
|---|---|
| `scripts/verify-e2e.ps1:3470` | G-10 标记 `'gf-linkage'` → `'gf-linkage（G-10 requiredWhen）'` |
| `scripts/verify-e2e.ps1:3499` | G-12 标记 `'gf-options-from'` → `'gf-options-from（G-12 defaultFrom）'` |
| `scripts/ci/stub-upstream-order.test.mjs` | **新增**确定性回归（node:test，零新依赖） |

- 路由匹配是 `messageAny` 的 `includes` 包含匹配 ⇒ 加后缀后仍命中同一条 `GF-LINKAGE` /
  `GF-OPTIONS-FROM` 路由；**桩、stub-routes.json、产品、超时一律未改**。
- 两处标记只影响测试编排的第一句提示词，用例断言（字段结构、400/200 结果）一字未动。

## 回归测试设计（证明桩行为，不是"字符串不同"）

`scripts/ci/stub-upstream-order.test.mjs`：

1. **从实际的 `scripts/verify-e2e.ps1` 读取**：`Ai-FormSuspend` 的消息模板
   （`【AIE2E】$marker 请按标记出表单。`）与 G-09/G-10/G-11/G-12 四个 `Ai-FormSuspend` 标记；
   脚本形态变了测试会直接红（不另抄一份常量）。
2. 以随机空闲端口启动**真实的 `stub-upstream.mjs` 进程**（临时 `--dir`，测试结束 `SIGKILL` + 清理目录）。
3. 按产品首请形状构造请求：**2 条消息**（system + user）、`tools[]` 披露 `generative_form`；
   按 **G-09 → G-10 → G-11 → G-12** 连续发，**中间不插入任何 4 消息续跑**——即失败那次 CI 的到达顺序。
4. 断言：四次判定都必须是 `tool_call` / `generative_form`；同时校验桩确实只收到 4 次请求、
   每次都是 2 条消息（排除"续跑混入导致清零"这种假绿），失败信息会带上桩日志与命中收尾文本标记。
5. 端口随机、请求串行、无外部依赖，重复跑结果一致。

## 红（改标记之前）

命令：`node --test scripts/ci/stub-upstream-order.test.mjs`
日志：`<仓外证据>/g12-stub-order-red.log`

```
ℹ tests 1  ℹ pass 0  ℹ fail 1
+ { case: 'G-10', marker: 'gf-linkage', got: 'text', hitClosingText: true,
    text: '好的，本轮已完成，请查看上方的结果。' }
+ { case: 'G-12', marker: 'gf-options-from', got: 'text', hitClosingText: true,
    text: '好的，本轮已完成，请查看上方的结果。' }
桩日志：
[req-001] tool_call tool=generative_form route=GF-LINKAGE outcome=ok —— 命中路由 GF-LINKAGE
[req-002] text route=- —— 未命中任何路由（文本收尾）
[req-003] tool_call tool=generative_form route=GF-OPTIONS-FROM outcome=ok —— 命中路由 GF-OPTIONS-FROM
```

即：**G-10/G-12 精确重现 18 字文本收尾**（G-09/G-11 正常），与 CI 现象同型。

## 绿（只改两处标记之后）

命令：`node --test scripts/ci/stub-upstream-order.test.mjs`
日志：`<仓外证据>/g12-stub-order-green.log`

```
✔ G-09→G-12 首请连续到达（无续跑插入）：四个标记各自独立命中 generative_form
ℹ tests 1  ℹ pass 1  ℹ fail 0
```

同一测试连跑 3 次均 `pass 1 / fail 0`（约 0.2s/次）。另用一次性探针确认四条落盘判定
（`req-NN.decision.json`）为：

```
req-001: tool_call generative_form route=GF-LINKAGE
req-002: tool_call generative_form route=GF-LINKAGE
req-003: tool_call generative_form route=GF-OPTIONS-FROM
req-004: tool_call generative_form route=GF-OPTIONS-FROM
```

`node scripts/ci/stub-upstream.mjs --self-check` → `SELF-CHECK PASS`（日志
`<仓外证据>/g12-selfcheck.log`）。`verify-e2e.ps1` 仅用 PowerShell `Parser::ParseFile`
做了**语法解析**（未执行）：`parse ok`。未跑完整 ps1/E2E，未使用 Maven，未起后端/集群。

## 交付与 patch 验证

- 补丁：`<仓外证据>/g12-stub-order.patch`（含新增测试文件，`new file mode`）
- 把 `HEAD`（`2816f66`）归档到干净目录 `<仓外证据>/patch-check`，
  `git apply --check` + `git apply` 成功；在该目录跑上述测试 `pass 1 / fail 0`，
  `--self-check` 仍 PASS ⇒ 补丁对基线自洽、不依赖本工作树的其它残留。

## 仍未覆盖 / 待验证

1. **真实 CI 仍待验证**：本次没有跑完整 `verify-e2e.ps1` 或后端，需要在下次 e2e job 观察
   G-09/G-10/G-11/G-12 全绿（最好跨多次运行/两种到达顺序）。
2. **新测试尚未接入 workflow**：批准范围只含"两处标记 + 新增测试"，故未改 `ci.yml`/`nightly.yml`；
   建议在 e2e 前加一步 `node --test scripts/ci/stub-upstream-order.test.mjs`，否则回归不会自动执行。
3. **残余同型标记（超出批准范围，未改）**：`scenario=FILTER` 仍被 G-04、G-16、G-17 三个用例共用，
   与本次同型（首条 user 文本相同 ⇒ 共用一个 bucket）。目前未观察到失败，但结构风险仍在。
4. 桩"按首条 user 文本指纹、仅严格回退才清零"的机制本身未改（属另一决策面，本次只消除测试编排的碰撞）。

## 边界声明

本报告不把 G-12 失败解释为模型服从率不足或产品故障；证据指向测试编排（桩跨用例状态）与
异步续跑顺序的交互。G-12 在桩下的通过只证明"产品链路正确消费了桩给的表单"，不证明真模型行为。

---

# 增量交付（第二轮）：主 CI 与 nightly 接线

上文根因与 RED/GREEN 结论**原样保留**，本节只登记新增接线与重跑输出。
上一节"仍未覆盖"第 2 项（测试未接入 workflow）已由本轮闭环。

## 接线位置

两个 workflow 都只加**一步**，位置在"桩上游自检"之后、"启动桩上游"之前；未新增 job，
未改其它步骤、条件、超时，也未加到不需要这些 ps1 用例的 workflow（`ci.yml` / `nightly.yml`
的 `e2e` job 各一处，`hits=1`）。

| 文件 | 行 | 步骤 |
|---|---|---|
| `.github/workflows/ci.yml` | 280–282 | `桩用例标记隔离回归（G-09…G-12 首请连续到达的请求顺序）` |
| `.github/workflows/nightly.yml` | 188–190 | 同上 |

```yaml
      - name: 桩用例标记隔离回归（G-09…G-12 首请连续到达的请求顺序）
        working-directory: ${{ github.workspace }}
        run: node --test scripts/ci/stub-upstream-order.test.mjs
```

接线的静态校验（PyYAML 6.0.3 为环境既有，未安装任何新依赖）：

```
.github/workflows/ci.yml jobs=8 hits=1
  job=e2e step#5 name='桩用例标记隔离回归（G-09…G-12 首请连续到达的请求顺序）' wd='${{ github.workspace }}'
    prev='桩上游自检（路由契约 / 白名单对账 / 收敛自证）' next='启动桩上游（127.0.0.1:18399）'
.github/workflows/nightly.yml jobs=5 hits=1
  job=e2e step#5 name='桩用例标记隔离回归（G-09…G-12 首请连续到达的请求顺序）' wd='${{ github.workspace }}'
    prev='桩上游自检（路由契约 / 白名单对账 / 收敛自证）' next='启动桩上游（127.0.0.1:18399）'
```

两个 workflow 的 `run` 都是 `node --test scripts/ci/stub-upstream-order.test.mjs`，
该路径在仓库根（`${{ github.workspace }}`）确实存在。

## 重跑输出（本地，2026-10-26）

```
$ node --test scripts/ci/stub-upstream-order.test.mjs
✔ G-09→G-12 首请连续到达（无续跑插入）：四个标记各自独立命中 generative_form
ℹ tests 1  ℹ pass 1  ℹ fail 0
（exit 0；日志 <仓外证据>/g12-stub-order-green.log）

$ node scripts/ci/stub-upstream.mjs --self-check
SELF-CHECK PASS：路由表契约、白名单对账、收敛自证、披露驱动、帧形态、反解、usage 形状、§4.6 故障开关全部通过
（exit 0；日志 <仓外证据>/g12-selfcheck.log）
```

`<仓外证据>/g12-stub-order.patch` 已重新生成，含 4 个文件：
`ci.yml`（+4）、`nightly.yml`（+4）、`scripts/verify-e2e.ps1`（2 行标记）、
新增 `scripts/ci/stub-upstream-order.test.mjs`（`new file mode`）。

补丁复验：从 `HEAD`（`2816f66`）归档到干净目录 `<仓外证据>/patch-check` →
`git apply --check` + `git apply` 成功 → 该目录内测试 `pass 1 / fail 0`、`--self-check` PASS、
两个 workflow 的 `run` 行均指向该测试文件。

## 仍待验证 / 未改

1. **GitHub 上的真实 workflow 运行由主控触发**：本轮只做了 YAML 解析与本地命令复跑，
   未伪造任何 workflow 运行结果；接线后首次 e2e job 仍需观察该步骤与 G-09…G-12 全绿。
2. **残余同型标记（只登记，不扩改）**：`scenario=FILTER` 仍被 G-04、G-16、G-17 三个用例共用，
   结构风险与本次同型；本轮未改。
3. 桩"按首条 user 文本指纹、仅严格回退才清零"的机制本身未改（属另一决策面）。

## 边界声明（本轮补充）

接线只让既有回归**随 CI/nightly 实际执行**，不改变通过判据；它仍不证明真模型服从率，
也不把 G-12 的通过解释为产品修复。
