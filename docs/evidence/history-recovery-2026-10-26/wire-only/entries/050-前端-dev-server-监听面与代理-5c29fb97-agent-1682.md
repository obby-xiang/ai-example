> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-1682` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：9921 B / 6663 字符 / 79 行；行指纹覆盖率（仓内 + 落盘语料）5.0%
> - 类别：**正式独立报告**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# 1. 前端 dev server 监听面与代理

**结论：`vite.config.ts` 硬编码 `host: '0.0.0.0'`，对所有网卡开放；同时配置了 `/api` → 后端的代理，「借 dev server 代理打后端写接口」在开发形态下成立。**

- 端口：配置里默认 `5200`，`VITE_DEV_PORT` 可覆盖；仓库里没有 5202，5202 来自**不入库的 `frontend/.env.local`**（证据文档明确写了 `VITE_DEV_PORT=5202`）。
  `frontend/vite.config.ts:11,16`：`const DEFAULT_DEV_PORT = 5200` / `Number(env.VITE_DEV_PORT || DEFAULT_DEV_PORT)`
  `docs/evidence/GFd-双FormRenderer冒烟验证.md:50`：「端口由 `frontend/.env.local` 的 `VITE_DEV_PORT=5202` 覆盖（vite.config.ts 默认值为 5200，未生效）」。`frontend/.env.local` 确实存在（74 B，未入库），按纪律未读取其内容。
- host：`frontend/vite.config.ts:24`：`host: '0.0.0.0',`（不是回环，也不是 `host:true` 的等价写法——效果同样是全 0.0.0.0）。
- 代理：`frontend/vite.config.ts:28-30`：`proxy: { '/api': { target: apiBase, ...`；`apiBase` 默认 `http://localhost:8081`（`:10`），可被 `VITE_API_BASE` 覆盖。超时 300s、`changeOrigin:false`、显式 `Accept-Encoding: identity` 保 SSE（`:31-39`）。
- 因此：外部机器访问 `http://<本机IP>:5202/api/...` → Vite 进程在**本机**回环上转发到 `localhost:8081`，可绕过 `2d6ce53` 把后端收回 127.0.0.1 的收窄。该残余面已登记为 **L-112**（`docs/任务看板.md`，修点=一行改 `127.0.0.1` 或 env 可覆盖）。
- 注意：**E2E 栈不受此影响**——harness 起 vite 时显式 `--host 127.0.0.1`：`frontend/e2e/support/harness.ts:564`。

# 2. H2 控制台配置与历史

**结论：`enabled: true` 至今未变；`web-allow-others` 在 `2d6ce53`（2026-10-23）由 `true` 改为 `false`，此前从导入基座起一直是 `true`。**

当前值（`backend/src/main/resources/application.yml:61-69`）：
```yaml
enabled: true
path: /h2-console
web-allow-others: false
```
历史：
- `10a7b26`（2026-10-06，导入合流基座，首个 commit）：`enabled: true` / `path: /h2-console` / `web-allow-others: true`。
- `2d6ce53`（2026-10-23）：唯一一次 h2.console 改动，diff 为 `web-allow-others: true` → `false`（含注释）。其余键、`path`、`enabled` 均未动。
- 没有 `application-*.yml`/profile 覆盖（`backend/src/main/resources` 只有 `application.yml`）。
- 现状有测试锁定：`backend/src/test/java/com/example/configmgr/config/Dc10ExposureSurfaceTest.java:64-69` 断言 `enabled=true`（作为前置）且 `web-allow-others=false`。
- 残余面已登记 **L-113**：生产仍默认 `enabled=true`，一旦显式 `--server.address=0.0.0.0` 或网关反代到回环，控制台仍会暴露（零认证、可执行 SQL）。`docs/任务看板.md` L-113。

# 3. 历史安全/暴露面收窄改动与 vite host 历史

**结论：真正做「监听面收窄」的只有 `2d6ce53` 一笔；此前的安全投入集中在输入/输出防护与 SSRF 闸，不是网络暴露面。vite 的 `host` 自基座引入后从未改过。**

`2d6ce53`（`feat(sec): 监听面收窄 —— 默认只绑回环 + DC-10 暴露口径落地（I-1 / L-30 / L-59）`，合并笔 `9944481`，分支 `w4/sec-csp`）：
- `application.yml` 新增 `server.address: 127.0.0.1`（`application.yml:12`），端口仍 8081；需对外必须显式 `--server.address=0.0.0.0` 或前置反代。
- H2 `web-allow-others` true→false（见第 2 节）。
- 新增红测：`ServerBindAddressConfigTest`（真 Tomcat 起容器验证回环可达、非回环拒绝）、`Dc10ExposureSurfaceTest`（无第二监听面 / H2 不放行其它主机 / DC-10 端点仍在 `/api`）。
- I-1（写端点无鉴权+默认 0.0.0.0）、L-30（`GET /api/ai/history/{sessionId}` 无鉴权）、L-59（`POST /api/ai/kb/reload` 无鉴权）按「监听面收窄 + 生产网关前置鉴权」口径销记，不引 token；同机多用户残余面写进 `AiController`/`AiKbController` javadoc。
- L-28（CSP）**只评估不施工**：前置①不成立（`SecurityHeadersFilter` 只挂 `/api/*`，页面由 Vite 承载，仓内无网关配置）。
- 该 commit message 已显式列出残余面：「`frontend/vite.config.ts` 的 dev server `host: '0.0.0.0'` 且 /api 代理到回环后端 ⇒ 开发形态下网段仍可经 vite 代理打到写端点」→ 即后来的 L-112；同时登记 L-113。

此前相关的安全改动（非网络绑定面）：
- `a13c750 feat(security): 防护批②③ 信任隔离+输出收敛+DLP+观测`（合流 `edc98f5`）：`ContextFieldGate`、`OutputGuard`、`InputDlp`、`SecurityHeadersFilter`（CSP Report-Only + nosniff + no-referrer）、`/health.security`。
- `c87047c`（模型配置批②）：URL 闸 / `allow-private-endpoints: false`（SSRF，`application.yml:152`）。
- 输入闸门批①（合流 `e62d375` + `5a48940`）：8192 字符拒绝式上限 + Redis 滑动窗口限流（20/分 session、60/分 IP）。
- `2d46fdf`：工具 bean 创建失败即启动失败、畸形 JSON 400、reasoning 能力位回填（非暴露面）。

vite host / dev server 绑定历史（`git log -S`）：
- `git log -S "host: '0.0.0.0'" -- frontend/vite.config.ts` → 仅 `2436dc2`（2026-10-07，S4.4a 基座引入），**从未收窄过**。
- 之后仅两笔碰过 `vite.config.ts`，都与 host 无关：
  - `029caf7`：代理默认值 `8080` → `8081`（`DEFAULT_API_BASE`）。
  - `b39f232`：`changeOrigin: true` → `false`（修 LAN 访问下非 GET 被 CORS 白名单 403）。
- 结论：`host: '0.0.0.0'` 是「基座遗留 + 至今未收」，不是近期回归；L-112 仍挂账。

# 4. L-114：E2E Redis 库号配置与 >15 的行为

**结论：库号由 `E2E_REDIS_DB` / `E2E_REDIS_DB_DEGRADED`（VL 域另有 `VL_REDIS_DB`）控制，统一下发给后端 `--spring.data.redis.database`。当前写 >15 不会在 harness 里 fail-fast：清库静默失败，后端能起来，最终卡在 `/api/ai/health` 的 `redis.available=true` 就绪等待上（约 120s 超时，报错信息误导）。**

配置点：
- 默认值：`frontend/e2e/support/harness.ts:68-69`：
  `export const REDIS_DB = Number(process.env.E2E_REDIS_DB ?? '5')` / `DEGRADED_REDIS_DB ... ?? '6'`
- 下发给后端：`harness.ts:332`：`` `--spring.data.redis.database=${redisDb}` ``
- 各域 config 在加载期设默认（全部 ≤15）：主栈 5/6；HGTA 11；MDO 12/12；MDXY 13/11；NFEZUI 13/14；KQC 系列 14/15；unlock-budget 8/9；GF2 3。例：`frontend/playwright.kqc.config.ts:21-22`。
- VL 域自带栈：`frontend/e2e/support/helpers-vl.ts:27` `VL_REDIS_DB = Number(process.env.VL_REDIS_DB ?? '12')`，并在 `:200` 下发给后端。
- 历史脚注已遇到过一次：`frontend/e2e/17-unlock.spec.ts:24` 注释「分配口径是 DB 20，实测本机 Redis 只有 16 个库（`SELECT 20` ⇒ `ERR DB index is out of range`），故改用 8/9」。

写 >15 时的实际链路（按时序）：
1. **清库不报错**：`harness.ts` 的 `flushRedisDb()` 只认 `+OK`，收到 `-ERR` 既不抛错也不发 `FLUSHDB`，靠 `socket.setTimeout(3000, finish)` 静默结束（`harness.ts:169,174-193`）——所以起栈阶段看不到任何错误。
2. **后端照常启动**：Spring Data Redis 不急于建连；`KbService` 的刷新在守护线程里 `refreshQuietly`，`StartupResumeRunner`/scan 也捕获异常（`StartupResumeRunner.java` 的 `catch (Throwable ex)`），不会让启动失败。Lettuce 在首次取连接时发 `SELECT <db>`，越界即 `ERR DB index is out of range`。
3. **首次探活即失败**：`/api/ai/health` 现算 PING（`AiController.java:1421-1423`），Lettuce 抛异常 → `RedisAvailability` 置 `available=false` 并记录 `lastError`（`ai/config/RedisAvailability.java:95-99`）。此时 `/health` 仍 HTTP 200，只是 `redis.available=false`。
4. **卡在就绪闸**：`startServices` 等 `available===true && redis.available===true`（`harness.ts:526-537`），默认 `E2E_READY_TIMEOUT_MS=120000`（`:82`），超时抛「后端 A（available + redis.available）未在 120s 内就绪（…/api/ai/health）：HTTP 200」。VL 路径同款（`helpers-vl.ts:216-225`）。
5. **下游现象**：若该域/外部实例没走这个就绪闸（`E2E_EXTERNAL=1` 在 `harness.ts:412-415` 直接返回），问题会拖到用例里表现为「一簇无关断言红」；所有 Redis 依赖端点返回 503 `AI_REDIS_UNAVAILABLE`（`AiController.java:300-303` 等）。看板 L-114 记录的现象即「后端报 `Unable to connect to Redis`、`/health` 永远 `redis.available=false`」。

建议加前置校验的位置（最小、同面）：
- **首选**：`frontend/e2e/support/harness.ts` 在 `REDIS_DB`/`DEGRADED_REDIS_DB` 定义之后（`:68-69`）加一个导出的 `assertRedisDbRange()`，并在 `startServices()` 里 `E2E_EXTERNAL` 早退之后、端口检查/`flushRedisDb()` 之前调用（`:412-415` 与 `:442-444` 之间）。这样任何域在任何进程被拉起之前就得到可读错误；建议同时校验 `REDIS_DB` 恒校验、`DEGRADED_REDIS_DB` 在 `only.includes('degraded')` 时校验。
- **VL 自建栈**：`frontend/e2e/support/helpers-vl.ts` 的 `ensureVlStack()` 在 `:182` `await flushRedisDb(VL_REDIS_DB)` 之前调用同一校验（VL 不经过 `startServices`）。
- 更稳妥的口径不是硬编码 `0–15`，而是对目标库发一次 `SELECT`/`CONFIG GET databases` 并按 `-ERR DB index is out of range` 判定；顺带可把 `flushRedisDb()` 的「非 `+OK` 静默成功」改成显式失败（这正是第 1 步看不到错误的根因）。

**不确定/未验证点**：未能读取 `frontend/.env.local`（本地未入库、可能含密钥，按纪律跳过），5202 的结论依赖证据文档；未实际启动一套 DB>15 的 E2E 栈复现，时序结论来自代码路径（Lettuce 首连 SELECT）+ 看板 L-114 的实测记录。
