> 来源=<仓外证据>/redis-guard-scope-fix-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# Redis 守卫豁免范围修复报告（评审 I-1，2026-10-25）

## 1. 结论

评审 `final-frontend-guard-review.md` 的 Important 项 **I-1**（JDBC 豁免按"文件名"生效、fail-open）已修复：

- 采纳的判定仍只有一条 —— **同文件、同作用域内、声明类型是 `Statement` / `PreparedStatement` / `CallableStatement` 的接收者**才豁免；
- 新增的四条约束（剥离注释/字面量、作用域包含、同名遮蔽否决、别名永不豁免）落地后，评审给出的三处假阴性全部变成命中（RED→GREEN 有日志）；
- 真实 JDBC SQL 豁免（`FlywayStartupLock.java:157-165`）保持，main 扫描仍 **311 文件 / 0 命中**；
- 自证样例 29 → 34（正向 19 → 22），原有全部 Redis 正例/反例逐条保留；
- 反向控制集（`fixture-root`，16 处真实违规）与当前 main 守卫**逐条一致**，无检出力损失。

- 状态：**修复完成，待应用**（main 未改动；补丁为 `scripts/` 两文件的增量）
- 未做：未碰前端 Minor（M-1…M-7 含 M-5 的 12 条下限）、未改 Java、未关守卫、未放宽 `SELECT`

## 2. 评审项的复现（修复前，current main 守卫）

评审用 6 个合成探针，3 处漏报。我在隔离副本中先把它变成确定性自证样例，跑 current main 的实现（`git apply` 前的守卫）得到同一结论：

| 探针 | 内容 | 修复前 | 应为 |
| --- | --- | --- | --- |
| 跨作用域同名不同型 | A 方法 `Statement statement` + B 方法 `RedisConnection statement` → `statement.execute("FLUSHDB")` | 0 命中（漏） | 1 |
| 注释污染 | `// The JDBC Statement conn above …` + `conn.execute("FLUSHDB")` | 0 命中（漏） | 1 |
| 字符串污染 | `String doc = "legacy PreparedStatement client";` + `client.execute("SCAN 0")` | 0 命中（漏） | 1 |
| 同名不同型（原样例被评审指出"空转"） | 类里同时有 `private final Statement statement;` 与 `RedisConnection statement = …` | 0 命中（漏） | 1 |

根因三条，全部来自"按**名字**对整文件生效"：

1. `jdbcStatementVars(text)` 扫**原始文本**，注释与字符串字面量里的 "Statement xxx" 也会被当成声明；
2. 收集到的是名字集合，与调用点无作用域关系 —— 别的方法/别的 try 块里的同名声明也会豁免；
3. 收集时只看"像不像 JDBC 类型"，不看是不是 Redis 侧别名（`conn` / `client` / `connection` …）。

## 3. 方案（只动守卫，不扩范围）

### 3.1 三道硬化（都遵循"证明不了就照常命中"）

| 硬化 | 实现 |
| --- | --- |
| 剥离注释与字面量 | 新增 `stripCommentsAndLiterals()`：`//`、`/* */`、`"..."`、`'...'`、文本块 `"""..."""` 的内容替换成空格（保留长度与换行），声明只在代码面提取 |
| 作用域内才豁免 | 新增 `braceSpans()` / `enclosingSpan()` / `declarationScope()`：每条声明记录其作用域块，调用点必须**被该块包住**才可能豁免；形参与 try-with-resources 声明取紧随其后的方法体 / try 体，不会把整个类体当成作用域 |
| 同名遮蔽否决 | 另收集"非 JDBC 类型声明"（`Type name` / `var name`）；同名的其它类型声明若也包住调用点，则**不豁免** |
| Redis 别名永不豁免 | `REDIS_RECEIVER_NAMES` 数组成为白名单正则与"永不豁免"名单的**同一来源**；`conn` / `client` / `connection` / `c` / `rt` / `template` / `redis*` / `jedis` / `lettuce` 无论声明如何都按 Redis 处理 |

判定函数 `isJdbcExempt(receiver, offset)` 四个条件全真才豁免：非别名 → 有同名 JDBC 声明 → 该声明块包住调用点 → 无其它类型声明包住调用点。

### 3.2 自证样例的配套调整（保持断言不变）

- 四条既有的 JDBC 样例（`pg_advisory_lock`、`pg_advisory_unlock`、`JDBC Statement 上任意 SQL SELECT`、`JDBC 豁免不外溢`）原本是**没有外层块的片段**（`try (Statement statement = …) {` 起头）。加入作用域约束后，片段里"声明不属于任何块"会被 fail-closed 拒绝——这正是不完整代码，真实 Java 文件不存在该形态（main 扫描 0 命中可证）。因此把它们补成 `class X { void m(...) { … } }` 的完整单元，**断言与语义都不变**。
- 评审指出的"空转"样例（"变量名叫 statement 但类型是 RedisConnection"）补上真实的 `private final Statement statement;` 字段，使其真正检验"不按名豁免"。
- 新增评审三探针各一条，共 +3 例。
- 补 2 条“保留性”样例（`CallableStatement` 豁免、JDBC 语句字段在方法内使用仍豁免），防止日后收紧作用域时回退成误报。

### 3.3 改动文件

| 文件 | 改动 |
| --- | --- |
| `scripts/ci/forbid-redis-cluster-unsafe.mjs` | +227 / −30：`REDIS_RECEIVER_NAMES` 单一来源、`stripCommentsAndLiterals` / `braceSpans` / `enclosingSpan` / `declarationScope` / `analyzeDeclarations` / `isJdbcExempt`，`ruleHits` 传递行内偏移，头部注释同步；自证新增 5 例（3 探针 + CallableStatement + 字段作用域）、强化 1 例、真实化 4 例 |
| `scripts/README.md` | 1 行：§5.3 说明改为"剥离注释/字符串 + 同文件同作用域 + 无同名遮蔽 + 别名永不豁免" |

## 4. TDD 证据

### 4.1 RED —— current main 的守卫实现 + 最终自证样例

RED 文件由 **current main 的 `forbid-redis-cluster-unsafe.mjs`** 与其 **最终版 `selfCheck()`** 拼成（`evidence/red-run/scripts/ci/forbid-redis-cluster-unsafe.mjs`），即"未硬化实现 + 新断言"：

```
$ node evidence/red-run/scripts/ci/forbid-redis-cluster-unsafe.mjs --self-check
[self-check] FAIL 变量名叫 statement 但类型是 RedisConnection（反例：不得按名豁免）：期望命中 1，实际 0
[self-check] FAIL 注释污染：// Statement conn 不得豁免 conn.execute（反例）：期望命中 1，实际 0
[self-check] FAIL 字符串污染："PreparedStatement client" 不得豁免 client.execute（反例）：期望命中 1，实际 0
[self-check] FAIL 跨作用域同名不同型：A 方法 JDBC statement 不豁免 B 方法 Redis statement（反例）：期望命中 1，实际 0
→ exit 1（其余 30 例全 ok，含全部 JDBC 豁免与 Redis 正例）
```

日志：`evidence-redis-guard-scope/selfcheck-scope-RED-final.log`。
（另有一份更早的 RED 日志 `selfcheck-scope-RED.log`，同样是这 4 条失败；那份用的是真实化之前的片段型 fixture。）

### 4.2 GREEN —— 硬化后

```
$ node scripts/ci/forbid-redis-cluster-unsafe.mjs --self-check
[self-check] 正向样例 22 条，数量断言通过。
规则自证通过。                       → exit 0（34 例全过）

$ node scripts/ci/forbid-redis-cluster-unsafe.mjs --root <main>/backend/src/main
Redis Cluster 安全守卫：扫描 311 个文件，0 命中。   → exit 0
```

日志：`evidence-redis-guard-scope/selfcheck-scope-GREEN-final.log`、`guard-main-scope-GREEN-final.log`。

自证规模：29 → 34 例；正向 19 → 22 条（下限断言仍为 12，未改）。

## 5. 验证矩阵

### 5.1 真实 main 扫描

| 命令 | 结果 |
| --- | --- |
| `node scripts/ci/forbid-redis-cluster-unsafe.mjs --root <main>/backend/src/main` | exit 0，311 文件，0 命中 |
| `node scripts/ci/forbid-redis-cluster-unsafe.mjs`（副本内默认根） | exit 0，311 文件，0 命中 |

### 5.2 反向控制集（真实违规注入，逐条比对）

同一棵 `evidence/fixture-root`（覆盖全部 10 条规则的 16 处违规 + 1 个 JDBC/Redis 混排文件）：

| 守卫 | 命中 | 与硬化版差集 |
| --- | --- | --- |
| current main（上一轮修复后） | 16 | 空 |
| 本报告硬化版 | 16 | 空 |

日志：`guard-fixture-currentmain.log`、`guard-fixture-hardened-final.log`，规范化后的集合文件 `norm-currentmain.txt` / `norm-hardened.txt`（均存于评审目录 `evidence-redis-guard-scope/`）。
（`evidence/fixture-root`、`evidence/probe-root`、`evidence/red-run/` 都在隔离副本 `<验证副本>/_verify-20261025-redis-guard-pg` 内，需在该副本里执行相应命令。）
（更早的"完全未修复"版本是 18 处，差集只有 `FlywayLike.java:7/14` 两条 JDBC SQL，见上一轮报告。）

### 5.3 定向探针（`evidence/probe-root`，5 个文件）

| 探针 | 期望 | 实际 |
| --- | --- | --- |
| `FieldJdbc.java`：`private final Statement statement; private final CallableStatement call;` 在方法里使用 | 0（字段豁免仍在） | 0 ✔ |
| `BlockComment.java`：`/* legacy JDBC Statement conn … */` + `conn.execute("FLUSHDB")` | 1 | 1 ✔ |
| `AliasDeclaredAsStatement.java`：`Statement client = …; client.execute("SCAN 0")` | 1（别名永不豁免） | 1 ✔ |
| `Chained.java`：`f.getConnection().execute("FLUSHDB")` | 1（口径不变） | 1 ✔ |
| `ShadowParam.java`：JDBC 字段 `statement` + 方法形参 `RedisConnection statement` | 1（只有形参所在方法命中，字段方法不误报） | 1 ✔ |

`probe-root` 合计 4 处命中；日志 `guard-probe-hardened-final.log`。
（这一项在实现过程中抓到了我自己引入的一处新误报：形参原先被记到类体作用域，把同类的 JDBC 方法也一起否决了 —— 已用 `declarationScope()` 修正并固化为探针。）

## 6. 补丁

- 路径：`<仓外证据>/redis-guard-scope-fix.patch`
- 基线：**current main**（main 现已应用上一轮 `redis-guard-pg.patch`；两文件 md5 与隔离副本修改前一致）
- 规模：2 文件 `+228 / −31`（`scripts/README.md` 1/1，`scripts/ci/forbid-redis-cluster-unsafe.mjs` 227/30）
- UTF-8 + LF，`git apply -p1` 兼容
- sha256：`082601202524ce0377448da68e2c1a35336889a22c0a829a7c2fe5472f9a5a05`
- 校验：main 内 `git apply --check` 通过；在继承本机 `core.autocrlf=true` 的临时 git 仓库 apply 后 `git status` 仅 2 个 `M`、`git diff --numstat` 为 1/1 与 227/30（无换行翻动），apply 后 mjs 与隔离副本字节一致（README 在该环境按 `core.autocrlf` 落成 CRLF，`git diff` 无换行翻动），`--self-check` 与 main 扫描均通过

```sh
cd <主仓>
git apply --check <仓外证据>/redis-guard-scope-fix.patch
git apply         <仓外证据>/redis-guard-scope-fix.patch
node scripts/ci/forbid-redis-cluster-unsafe.mjs --self-check && node scripts/ci/forbid-redis-cluster-unsafe.mjs
```

<details>
<summary>补丁全文</summary>

```diff
diff --git a/scripts/README.md b/scripts/README.md
index 3b60cca..9368e19 100644
--- a/scripts/README.md
+++ b/scripts/README.md
@@ -309,4 +309,4 @@ node scripts/ci/forbid-redis-cluster-unsafe.mjs --self-check # 规则自证
 node scripts/ci/forbid-redis-cluster-unsafe.mjs --root <dir> # 指定目录（排障）
 ```

-禁止进入 `backend/src/main` 的面：**多库**（`database:` / URL 库号 / `setDatabase`）、**单机全量命令**（`KEYS` / `FLUSHDB` / `FLUSHALL` / 跨键 `SCAN`）、**5.0 不支持的排他流 ID**（`Range.Bound.exclusive` / 字符串 `(123-0`）。keys/scan 规则只认 Redis 侧接收者（避开 JPA 原生 SQL 与业务方法 `scan(` 的误报），配置规则要求文件处于 Redis 上下文；原生命令字面量同样按接收者判定，同文件声明为 `Statement` / `PreparedStatement` 的变量按 JDBC 处理（`statement.execute("SELECT pg_advisory_lock(...)")` 是 PostgreSQL SQL，不是 Redis `SELECT`）。`ci.yml` 里 `--self-check` 与主扫各一步。
+禁止进入 `backend/src/main` 的面：**多库**（`database:` / URL 库号 / `setDatabase`）、**单机全量命令**（`KEYS` / `FLUSHDB` / `FLUSHALL` / 跨键 `SCAN`）、**5.0 不支持的排他流 ID**（`Range.Bound.exclusive` / 字符串 `(123-0`）。keys/scan 规则只认 Redis 侧接收者（避开 JPA 原生 SQL 与业务方法 `scan(` 的误报），配置规则要求文件处于 Redis 上下文；原生命令字面量同样按接收者判定，且豁免 fail-closed：先剥离注释与字符串字面量、再按"同文件 + 同作用域包住调用点 + 同名无其它类型遮蔽"认定 `Statement` / `PreparedStatement` / `CallableStatement` 变量为 JDBC，Redis 侧别名（`conn` / `client` / `connection` …）永不豁免 —— 例如 `statement.execute("SELECT pg_advisory_lock(...)")` 是 PostgreSQL SQL，不是 Redis `SELECT`。`ci.yml` 里 `--self-check` 与主扫各一步。
diff --git a/scripts/ci/forbid-redis-cluster-unsafe.mjs b/scripts/ci/forbid-redis-cluster-unsafe.mjs
index 08f2f87..3876dbc 100644
--- a/scripts/ci/forbid-redis-cluster-unsafe.mjs
+++ b/scripts/ci/forbid-redis-cluster-unsafe.mjs
@@ -14,9 +14,12 @@
  *     裸匹配 `SELECT` / `scan(` 会把它们误报成红线。
  *   - 因此 keys/scan 规则只认 Redis 侧接收者（含 lambda 别名 `c` / `rt` / `conn` / `client`），
  *     配置规则要求文件处于 Redis 上下文（同文件出现 `redis:` 或显式 redis 键）。
- *   - 原生命令字面量同样按接收者判定：同文件声明为 `Statement` / `PreparedStatement` 的变量
- *     是 JDBC 语句（`statement.execute("SELECT pg_advisory_lock(...)")` 是 PostgreSQL SQL），
- *     其 `execute` 只跑 SQL、发不出 Redis 协议命令；判定见 jdbcStatementVars。
+ *   - 原生命令字面量同样按接收者判定：同文件、同作用域内声明为 `Statement` /
+ *     `PreparedStatement` / `CallableStatement` 的变量是 JDBC 语句
+ *     （`statement.execute("SELECT pg_advisory_lock(...)")` 是 PostgreSQL SQL），
+ *     其 `execute` 只跑 SQL、发不出 Redis 协议命令。豁免 fail-closed：先剥离注释与字符串
+ *     字面量再找声明，声明必须包住调用点，同名另有其它类型声明（遮蔽）则不豁免，
+ *     Redis 侧别名（`conn` / `client` / `connection` …）永不豁免；判定见 analyzeDeclarations。
  *
  * 用法：
  *   node scripts/ci/forbid-redis-cluster-unsafe.mjs              # 扫 backend/src/main
@@ -41,7 +44,12 @@ function arg(name, fallback = '') {

 // Redis 侧接收者白名单（含 lambda 形参常见别名）。刻意不写 `redis\w*`：
 // 否则 `redisKeys.keys()` 这类业务变量会误报。
-const REDIS_RECEIVER = String.raw`(?:redis|redisTemplate|redisConnection|connection|conn|client|c|rt|template|jedis|lettuce)`
+// 同一份名单也是"永不豁免"名单：别名即使被声明成 Statement 也按 Redis 通道看待。
+const REDIS_RECEIVER_NAMES = [
+  'redis', 'redisTemplate', 'redisConnection', 'connection', 'conn', 'client', 'c', 'rt', 'template', 'jedis', 'lettuce',
+]
+const REDIS_RECEIVER = String.raw`(?:${REDIS_RECEIVER_NAMES.join('|')})`
+const REDIS_RECEIVER_SET = new Set(REDIS_RECEIVER_NAMES)

 // ── 规则表（唯一取数点）────────────────────────────────────────────────────
 // kind: config = 只扫 *.yml / *.yaml / *.properties；java = 只扫 Java 源码。
@@ -94,7 +102,7 @@ const RULES = [
     id: 'redis-raw-command',
     kind: 'java',
     // connection.execute("FLUSHDB") —— 只认 Redis 侧接收者上的命令字面量，不认 SQL 字符串。
-    // 接收者落进第 1 个捕获组，供 jdbcStatementVars 判定 JDBC（见该函数注释）。
+    // 接收者落进第 1 个捕获组，供 analyzeDeclarations 判定 JDBC（见该函数注释）。
     regex: /(\b[A-Za-z_$][\w$]*)?\.execute\s*\(\s*["'](?:SELECT|KEYS|FLUSHDB|FLUSHALL|SCAN)\b/i,
     receiverGroup: 1,
     why: '原生命令字面量绕过类型化 API，同样属于禁止项',
@@ -131,31 +139,145 @@ function applicability(rule, file, text) {
   return true
 }

+/** 把注释、字符串/字符字面量的内容换成空格（保留长度与换行），供声明与作用域分析用。 */
+function stripCommentsAndLiterals(text) {
+  const blank = (s) => s.replace(/[^\n]/g, ' ')
+  let out = ''
+  let i = 0
+  while (i < text.length) {
+    const ch = text[i]
+    const next = text[i + 1]
+    if (ch === '/' && next === '/') {
+      const nl = text.indexOf('\n', i)
+      const end = nl < 0 ? text.length : nl
+      out += blank(text.slice(i, end))
+      i = end
+      continue
+    }
+    if (ch === '/' && next === '*') {
+      const close = text.indexOf('*/', i + 2)
+      const end = close < 0 ? text.length : close + 2
+      out += blank(text.slice(i, end))
+      i = end
+      continue
+    }
+    // 文本块 """..."""（Java 15+）与普通 "..." / '...'
+    const quote = text.startsWith('"""', i) ? '"""' : ch
+    if (quote === '"' || quote === "'") {
+      let j = i + quote.length
+      while (j < text.length) {
+        if (text[j] === '\\' && quote !== '"""') {
+          j += 2
+          continue
+        }
+        if (text.startsWith(quote, j)) {
+          j += quote.length
+          break
+        }
+        if (quote !== '"""' && text[j] === '\n') break
+        j++
+      }
+      out += blank(text.slice(i, j))
+      i = j
+      continue
+    }
+    out += ch
+    i++
+  }
+  return out
+}
+
+/** 所有成对 `{}` 的偏移区间（用于近似作用域）。 */
+function braceSpans(code) {
+  const spans = []
+  const stack = []
+  for (let i = 0; i < code.length; i++) {
+    if (code[i] === '{') stack.push(i)
+    else if (code[i] === '}') {
+      const open = stack.pop()
+      if (open != null) spans.push({ open, close: i })
+    }
+  }
+  return spans
+}
+
+/** 包住该偏移的最内层 `{}`；不在任何块内返回 null。 */
+function enclosingSpan(spans, offset) {
+  let best = null
+  for (const s of spans) {
+    if (s.open < offset && offset < s.close && (best == null || s.open > best.open)) best = s
+  }
+  return best
+}
+
+/**
+ * 声明的作用域块：
+ *   - 形参（`f(RedisConnection statement) {`）/ try-with-resources（`try (Statement s = ...) {`）
+ *     这类未以 `;` 结束的声明，作用域是紧随其后的 `{}` 块，不是外层类体；
+ *   - 其余（字段、局部变量）作用域就是声明所在的块。
+ * 这样同名的 Redis 形参只遮蔽它自己方法体里的调用，不会把同类里其它方法的 JDBC 调用一起拖下水。
+ */
+function declarationScope(code, spans, offset) {
+  const braceAfter = code.indexOf('{', offset)
+  const semiAfter = code.indexOf(';', offset)
+  if (braceAfter >= 0 && (semiAfter < 0 || braceAfter < semiAfter)) {
+    const body = spans.find((s) => s.open === braceAfter)
+    if (body) return body
+  }
+  return enclosingSpan(spans, offset)
+}
+
 /**
- * 本文件里声明为 `java.sql.Statement` / `PreparedStatement` / `CallableStatement` 的变量名。
+ * JDBC-vs-Redis 声明分析（`Statement.execute(String)` 只有 SQL 语义；Redis 的 execute 在
+ * RedisConnection / RedisTemplate 上，没有 Statement 这种类型）。
  *
- * `Statement.execute(String)` 只有 SQL 语义（Flyway 启动锁的
- * `statement.execute("SELECT pg_advisory_lock(...)")` 即 PostgreSQL SQL）；Redis 的
- * `execute` 位于 `RedisConnection` / `RedisTemplate`，不存在 Statement 这种类型。
- * 因此按声明类型（而非接收者命名）做 JDBC-vs-Redis 区分：命中的接收者若是 JDBC 语句变量，
- * 该次调用不是 Redis 原生命令。只认同文件内声明，跨文件/链式 `createStatement().execute(...)` 不豁免。
+ * 为了让豁免"有据可依、失败即命中"（fail-closed）：
+ *   - 只在剥离注释/字符串字面量后的代码里找声明，散文与文档串不会造出 JDBC 变量；
+ *   - 每条声明记录其作用域块（形参/资源声明取紧随其后的方法体/try 体，见 declarationScope）：
+ *     调用点必须被该块包住才可能豁免，跨方法/跨 try 的同名声明不互相外溢；同名若还有
+ *     别的类型声明也包住调用点（遮蔽），则宁可不豁免；
+ *   - `REDIS_RECEIVER_NAMES` 里的接收者别名一律不豁免（按其 Redis 语义处理）。
+ * 局限（刻意保守）：只认同文件、同块可见的声明；`var`/跨文件/链式 `createStatement().execute(...)` 不豁免。
  */
-function jdbcStatementVars(text) {
-  const vars = new Set()
-  const decl = /\b(?:PreparedStatement|CallableStatement|Statement)\s+([A-Za-z_$][\w$]*)/g
+function analyzeDeclarations(text) {
+  const code = stripCommentsAndLiterals(text)
+  const spans = braceSpans(code)
+  const jdbc = new Map()
+  const other = new Map()
+  const record = (map, name, offset) => {
+    if (!map.has(name)) map.set(name, [])
+    map.get(name).push(declarationScope(code, spans, offset))
+  }
+  const jdbcDecl = /\b(PreparedStatement|CallableStatement|Statement)\s+([A-Za-z_$][\w$]*)/g
   let m
-  while ((m = decl.exec(text))) vars.add(m[1])
-  return vars
+  while ((m = jdbcDecl.exec(code))) record(jdbc, m[2], m.index)
+  // 其它类型声明：`Type name` / `var name`（只看首字母大写的类型与 var，避免把 return/throw 当类型）。
+  const otherDecl = /\b((?:[a-z][\w$]*\.)*[A-Z][\w$]*(?:\s*<[^;{}()]*>)?|var)\s+([a-z_$][\w$]*)\s*(?=[=;,:)])/g
+  while ((m = otherDecl.exec(code))) {
+    if (/^(?:PreparedStatement|CallableStatement|Statement)$/.test(m[1])) continue
+    record(other, m[2], m.index)
+  }
+  return { jdbc, other }
+}
+
+/** 该接收者在该偏移处是否可按 JDBC 语句豁免（任一条不成立就照常命中）。 */
+function isJdbcExempt(decls, receiver, offset) {
+  if (!receiver || REDIS_RECEIVER_SET.has(receiver)) return false
+  const jdbcBlocks = decls.jdbc.get(receiver)
+  if (!jdbcBlocks) return false
+  const contains = (span) => span != null && span.open < offset && offset < span.close
+  if (!jdbcBlocks.some(contains)) return false
+  const shadows = decls.other.get(receiver)
+  return !(shadows != null && shadows.some(contains))
 }

 /** 该规则在该行是否命中；带接收者的规则按调用逐个判定，豁免只作用于被豁免的那次调用。 */
-function ruleHits(rule, line, jdbcVars) {
+function ruleHits(rule, line, lineStart, decls) {
   if (rule.receiverGroup == null) return rule.regex.test(line)
   const re = new RegExp(rule.regex.source, 'gi')
   let m
   while ((m = re.exec(line))) {
-    const receiver = m[rule.receiverGroup]
-    if (!(receiver && jdbcVars.has(receiver))) return true
+    if (!isJdbcExempt(decls, m[rule.receiverGroup], lineStart + m.index)) return true
   }
   return false
 }
@@ -163,11 +285,15 @@ function ruleHits(rule, line, jdbcVars) {
 function scanText(file, text) {
   const hits = []
   const lines = text.split(/\r?\n/)
-  const jdbcVars = jdbcStatementVars(text)
+  const lineStarts = [0]
+  for (let i = 0; i < text.length; i++) {
+    if (text[i] === '\n') lineStarts.push(i + 1)
+  }
+  const decls = analyzeDeclarations(text)
   for (const rule of RULES) {
     if (!applicability(rule, file, text)) continue
     for (let i = 0; i < lines.length; i++) {
-      if (ruleHits(rule, lines[i], jdbcVars)) {
+      if (ruleHits(rule, lines[i], lineStarts[i] ?? 0, decls)) {
         hits.push({ file, line: i + 1, rule: rule.id, why: rule.why, text: lines[i].trim() })
       }
     }
@@ -308,8 +434,12 @@ function selfCheck() {
       // 真实行取自 config/FlywayStartupLock.java:159（PostgreSQL 会话级 advisory lock）。
       name: 'JDBC pg_advisory_lock（反例：PostgreSQL SQL 不是 Redis SELECT）',
       file: 'backend/src/main/java/com/example/configmgr/config/FlywayStartupLock.java',
-      text: 'try (Statement statement = connection.createStatement()) {\n'
-        + '    statement.execute("SELECT pg_advisory_lock(" + PG_LOCK_KEY + ")");\n'
+      text: 'class X {\n'
+        + '    void acquirePostgres(Connection connection) throws SQLException {\n'
+        + '        try (Statement statement = connection.createStatement()) {\n'
+        + '            statement.execute("SELECT pg_advisory_lock(" + PG_LOCK_KEY + ")");\n'
+        + '        }\n'
+        + '    }\n'
         + '}',
       hits: 0,
     },
@@ -317,23 +447,55 @@ function selfCheck() {
       // 真实行取自 config/FlywayStartupLock.java:165。
       name: 'JDBC pg_advisory_unlock（反例：PostgreSQL SQL 不是 Redis SELECT）',
       file: 'backend/src/main/java/com/example/configmgr/config/FlywayStartupLock.java',
-      text: 'try (Statement statement = connection.createStatement()) {\n'
-        + '    statement.execute("SELECT pg_advisory_unlock(" + PG_LOCK_KEY + ")");\n'
+      text: 'class X {\n'
+        + '    void releasePostgres(Connection connection) throws SQLException {\n'
+        + '        try (Statement statement = connection.createStatement()) {\n'
+        + '            statement.execute("SELECT pg_advisory_unlock(" + PG_LOCK_KEY + ")");\n'
+        + '        }\n'
+        + '    }\n'
         + '}',
       hits: 0,
     },
     {
       name: 'JDBC Statement 上任意 SQL SELECT（反例：不是 Redis 命令）',
       file: 'backend/src/main/java/x/Y.java',
-      text: 'PreparedStatement statement = connection.prepareStatement("x");\n'
-        + 'statement.execute("SELECT 1");',
+      text: 'class X {\n'
+        + '    void load(Connection connection) throws SQLException {\n'
+        + '        PreparedStatement statement = connection.prepareStatement("x");\n'
+        + '        statement.execute("SELECT 1");\n'
+        + '    }\n'
+        + '}',
+      hits: 0,
+    },
+    {
+      name: 'JDBC CallableStatement 仍豁免（反例：SQL 存储过程调用不是 Redis 命令）',
+      file: 'backend/src/main/java/x/Y.java',
+      text: 'class X {\n'
+        + '    void run(Connection connection) throws SQLException {\n'
+        + '        CallableStatement call = connection.prepareCall("{call p()}");\n'
+        + '        call.execute("SELECT p()");\n'
+        + '    }\n'
+        + '}',
+      hits: 0,
+    },
+    {
+      name: 'JDBC 语句字段在方法内使用仍豁免（反例：字段作用域不丢）',
+      file: 'backend/src/main/java/x/Y.java',
+      text: 'class X {\n'
+        + '    private final Statement statement;\n'
+        + '    void a() { statement.execute("SELECT 1"); }\n'
+        + '}',
       hits: 0,
     },
     {
       name: 'JDBC 豁免不外溢：同行 Redis 原生命令仍命中（数量断言）',
       file: 'backend/src/main/java/x/Y.java',
-      text: 'Statement statement = connection.createStatement();\n'
-        + 'statement.execute("SELECT 1"); connection.execute("FLUSHDB");',
+      text: 'class X {\n'
+        + '    void run(Connection connection) throws SQLException {\n'
+        + '        Statement statement = connection.createStatement();\n'
+        + '        statement.execute("SELECT 1"); connection.execute("FLUSHDB");\n'
+        + '    }\n'
+        + '}',
       hits: 1,
     },
     {
@@ -369,7 +531,42 @@ function selfCheck() {
     {
       name: '变量名叫 statement 但类型是 RedisConnection（反例：不得按名豁免）',
       file: 'backend/src/main/java/x/Y.java',
-      text: 'RedisConnection statement = factory.getConnection();\nstatement.execute("FLUSHDB");',
+      text: 'class X {\n'
+        + '    private final Statement statement;\n'
+        + '    void use(RedisConnection c2) {\n'
+        + '        RedisConnection statement = c2.getConnection();\n'
+        + '        statement.execute("FLUSHDB");\n'
+        + '    }\n'
+        + '}',
+      hits: 1,
+    },
+    {
+      // 评审 I-1 探针 2：注释里的 "Statement conn" 不得把 Redis 连接别名 conn 变成 JDBC。
+      name: '注释污染：// Statement conn 不得豁免 conn.execute（反例）',
+      file: 'backend/src/main/java/x/Y.java',
+      text: '// The JDBC Statement conn above is unrelated to this Redis connection.\n'
+        + 'conn.execute("FLUSHDB");',
+      hits: 1,
+    },
+    {
+      // 评审 I-1 探针 3：字符串字面量里的 "PreparedStatement client" 同理。
+      name: '字符串污染："PreparedStatement client" 不得豁免 client.execute（反例）',
+      file: 'backend/src/main/java/x/Y.java',
+      text: 'String doc = "legacy PreparedStatement client";\n'
+        + 'client.execute("SCAN 0");',
+      hits: 1,
+    },
+    {
+      // 评审 I-1 探针 1：方法 A 的 JDBC 声明不得豁免方法 B 里同名的 RedisConnection。
+      name: '跨作用域同名不同型：A 方法 JDBC statement 不豁免 B 方法 Redis statement（反例）',
+      file: 'backend/src/main/java/x/Y.java',
+      text: 'void sql(Connection connection) {\n'
+        + '    Statement statement = connection.createStatement();\n'
+        + '    statement.execute("SELECT 1");\n'
+        + '}\n'
+        + 'void redis(RedisConnection statement) {\n'
+        + '    statement.execute("FLUSHDB");\n'
+        + '}',
       hits: 1,
     },
   ]
```

</details>

## 7. 已知边界（刻意保守，均 fail-closed = 宁可命中）

1. **遮蔽只看块包含，不看先后顺序**：同一块里"先调用、后声明"的同名异型变量也会否决豁免（可能误报，不会漏报）。
2. **形参作用域按"紧随其后的 `{}`"近似**：抽象/接口方法（无方法体）的形参仍记在外层块上，属保守方向。
3. **`var statement = connection.createStatement()` 不认**（需要类型推断）→ 该写法仍旧误报，与上一轮一致。
4. **跨文件声明、链式 `createStatement().execute(...)` 不豁免**（口径不变）。
5. 注释/字符串剥离是逐字符扫描，不解析注解参数语义；Java 以外的文件不参与该规则（`kind: 'java'` 只看 `.java`）。
6. **M-5 未处理**：自证"正向样例 ≥ 12"的下限相对现有 22 条仍偏松，属评审 Minor，按要求不扩范围。

## 8. 复现命令（隔离副本内可重放）

```sh
cd <验证副本>/_verify-20261025-redis-guard-pg
node evidence/red-run/scripts/ci/forbid-redis-cluster-unsafe.mjs --self-check   # RED：4 例失败
node scripts/ci/forbid-redis-cluster-unsafe.mjs --self-check                    # GREEN：34 例
node scripts/ci/forbid-redis-cluster-unsafe.mjs                                 # 副本 311/0
node scripts/ci/forbid-redis-cluster-unsafe.mjs --root <主仓>/backend/src/main
node scripts/ci/forbid-redis-cluster-unsafe.mjs --root evidence/fixture-root    # 16 处，exit 1（与 current main 一致）
node scripts/ci/forbid-redis-cluster-unsafe.mjs --root evidence/probe-root      # 4 处，exit 1
```
