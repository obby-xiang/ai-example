> 来源=<仓外证据>/redis-guard-pg-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# Redis 守卫 PostgreSQL JDBC 误报修复报告（2026-10-25）

## 1. 结论

`node scripts/ci/forbid-redis-cluster-unsafe.mjs` 在 main 工作树上的 2 处命中是**守卫误报**，不是代码问题：

```
backend/src/main/java/com/example/configmgr/config/FlywayStartupLock.java:159  statement.execute("SELECT pg_advisory_lock(" + PG_LOCK_KEY + ")");
backend/src/main/java/com/example/configmgr/config/FlywayStartupLock.java:165  statement.execute("SELECT pg_advisory_unlock(" + PG_LOCK_KEY + ")");
```

两行都是 `java.sql.Statement` 上的 PostgreSQL SQL，与 Redis `SELECT` 无关。已在隔离副本中按 TDD（先加失败自证样例 → 再改守卫）修复：
**main 扫描 0 命中，原有全部正向/反向自证样例仍通过，且注入的真实 Redis 违规一条不少地被抓住。**

- 状态：**修复完成，待应用**（main 未改动，补丁待评审后 apply）
- 未做：未改 Java、未关守卫、未隐藏文件、未拆分/混淆字符串、未放宽 `SELECT` 通配

## 2. 复现（main 现状，原始守卫）

```sh
cd <主仓>
node scripts/ci/forbid-redis-cluster-unsafe.mjs            # exit 1，2 处命中
node scripts/ci/forbid-redis-cluster-unsafe.mjs --self-check  # exit 0（旧样例全绿，说明自证没覆盖这个面）
```

日志：`evidence-redis-guard-pg/guard-on-main-RED-original.log`

## 3. 根因

`RULES` 里 `redis-raw-command` 的旧正则与同一脚本里 keys/scan 规则的判定口径不一致：

```js
// 旧：不看接收者，只看 .execute("字面量")
regex: /\.execute\s*\(\s*["'](?:SELECT|KEYS|FLUSHDB|FLUSHALL|SCAN)\b/i,
```

- keys/scan 规则（`redis-keys-command` / `redis-scan-command`）走 `REDIS_RECEIVER` 白名单，这是脚本自己的"Redis 侧上下文"机制；
- 而 `redis-raw-command` 没有任何接收者约束，注释写着"只认命令字面量，不认 SQL 字符串"，但 `SELECT` 恰好是 PostgreSQL SQL 与 Redis `SELECT <db>` 的共同前缀 —— 契约与实现不符。

## 4. 方案

### 4.1 采用的判定：按**声明类型**区分 JDBC 与 Redis（不是按变量名）

新增 `jdbcStatementVars(text)`：扫同一文件文本，收集声明为 `Statement` / `PreparedStatement` / `CallableStatement` 的变量名；`ruleHits()` 对带接收者的规则**逐次调用**判定，接收者若落在该集合内，该次调用不算 Redis 原生命令。

`redis-raw-command` 的正则改为 `(\b[A-Za-z_$][\w$]*)?\.execute\s*\(\s*["'](?:SELECT|KEYS|FLUSHDB|FLUSHALL|SCAN)\b`（接收者进第 1 捕获组，`receiverGroup: 1`）；接收者为空（链式调用）时照旧命中，保持修复前口径。

选择理由（与"最窄修改"一致）：

- **Java 语义上成立，而非命名猜测**：`execute(String)` 是 `java.sql.Statement`/`PreparedStatement` 的方法，只能跑 SQL；Redis 侧的 `execute` 在 `RedisConnection`/`RedisTemplate` 上，JDK/Spring Data Redis 里不存在叫 `Statement` 的 Redis 类型。所以"声明类型是 Statement ⇒ 发不出 Redis 协议命令"是硬约束。
- **复用脚本已有的上下文机制**：配置规则本来就靠"同文件是否处于 Redis 上下文"（`configHasRedisContext`）免误报，这里把同一思路用在 Java 侧，没有引入新范式。
- **默认仍是"命中"**：豁免是**白名单式的类型证据**，未知接收者（`this.execute(...)`、`cache.execute(...)`、链式调用）照旧命中，不损失原有检出力。
- 豁免**不按行外溢**：`ruleHits` 用逐匹配循环，同一行 `statement.execute("SELECT 1"); connection.execute("FLUSHDB");` 仍报 1 处（有自证样例钉住）。

### 4.2 明确否决的做法

| 做法 | 否决原因 |
| --- | --- |
| 在正则里放宽/删除 `SELECT` | 等于对 Redis 原生 `SELECT`（切库）开门，直接违反守卫目的（§五「零 SELECT」） |
| 只对 `pg_advisory_lock` 做字符串特判 | 与守卫目的无关的"按业务字面量开口子"，同一类 JDBC SQL（`createStatement()`、`SELECT 1`）下次仍误报 |
| 给文件加 `.guardignore` / 改扫描范围 / 关 CI 步骤 | 属于隐藏问题，不是修复 |
| 按变量名叫 `statement` 豁免 | 同名变量可能是 `RedisConnection statement`；已加自证样例钉住"按名不按类型"会失败 |

### 4.3 改动文件（2 个，均在 `scripts/`，无 Java/无产品代码改动）

| 文件 | 改动 |
| --- | --- |
| `scripts/ci/forbid-redis-cluster-unsafe.mjs` | +106 / −4：`redis-raw-command` 捕获接收者（`receiverGroup`）、新增 `jdbcStatementVars()` 与 `ruleHits()`、`scanText()` 接入、头部"为什么不是裸 grep"补第 3 条、`--self-check` 新增 10 个样例（9 个在 RED 阶段先写；第 10 个"同名字不同型"为实现后补的防过拟合样例） |
| `scripts/README.md` | 1 行：§5.3 守卫说明补一句"原生命令字面量同样按接收者判定，`Statement`/`PreparedStatement` 按 JDBC 处理"（行为变更后文档同步） |

## 5. TDD 证据（RED → GREEN）

隔离副本：`<验证副本>/_verify-20261025-redis-guard-pg`（main 全程只读；main `git status` 条目数修复前后均为 45，`scripts/ci/forbid-redis-cluster-unsafe.mjs` 的 md5 未变）。

### 5.1 RED —— 先只加自证样例，不改实现

```sh
node scripts/ci/forbid-redis-cluster-unsafe.mjs --self-check
```

```
[self-check] FAIL JDBC pg_advisory_lock（反例：PostgreSQL SQL 不是 Redis SELECT）：期望命中 0，实际 1
[self-check] FAIL JDBC pg_advisory_unlock（反例：PostgreSQL SQL 不是 Redis SELECT）：期望命中 0，实际 1
[self-check] FAIL JDBC Statement 上任意 SQL SELECT（反例：不是 Redis 命令）：期望命中 0，实际 1
→ exit 1
```

两个 fixture 的文本直接取 `FlywayStartupLock.java:159/165` 的真实调用形态（含 `try (Statement statement = connection.createStatement())` 声明行）。日志：`evidence-redis-guard-pg/selfcheck-RED.log`。
RED 阶段共 28 例；第 29 例（`RedisConnection statement = ...; statement.execute("FLUSHDB")`，钉住"按类型不按变量名"）是实现后补的防过拟合样例。
同时 RED 阶段其余样例（含全部 Redis 正例）无一失败 —— 说明失败点正是新增的 JDBC 反例。

### 5.2 GREEN —— 实现后

```sh
node scripts/ci/forbid-redis-cluster-unsafe.mjs --self-check   # exit 0，29 例全过（正向 19 条 ≥ 12 数量断言）
node scripts/ci/forbid-redis-cluster-unsafe.mjs --root <main>/backend/src/main   # exit 0，"扫描 311 个文件，0 命中"
node scripts/ci/forbid-redis-cluster-unsafe.mjs               # 副本内默认根，exit 0
```

日志：`evidence-redis-guard-pg/selfcheck-GREEN-final.log`、`guard-on-main-GREEN-final.log`。
自证样例从 19 → 29；正向样例 12 → 19（数量断言下限 12 未改，仍在守"规则表被删空"）。

新增/保留的自证面（摘要）：

| 期望 | 样例 |
| --- | --- |
| 0 命中（新） | `statement.execute("SELECT pg_advisory_lock(...)")`；`statement.execute("SELECT pg_advisory_unlock(...)")`；`PreparedStatement statement` 上的 `execute("SELECT 1")` |
| 1 命中（新，防豁免过宽） | `RedisConnection statement = ...; statement.execute("FLUSHDB")`（同名字不同型不得豁免） |
| 1 命中（新，防豁免漂到整行） | `statement.execute("SELECT 1"); connection.execute("FLUSHDB");` |
| 1 命中（新，Redis 原生命令保留） | `connection.execute("SELECT 1")`、`redisTemplate.execute("KEYS ai:*")`、`rt.execute("SCAN 0")`、`client.execute("FLUSHALL")`、`statement.execute(CommandType.SCAN)` |
| 原样保留 | `c.keys` / `rt.scan` / `connection.execute("FLUSHDB")` / `RedisCommand.SELECT` / `setDatabase` / `Range.Bound.exclusive` / `"(" + seq + "-0"` / 业务 `runner.scan(true)` / JPA `"SELECT * FROM"` 反例 / 配置 `database:` 与 URL 库号 |

## 6. 端到端"无检出力损失"证据（真实违规注入）

构造 fixture 根 `evidence-redis-guard-pg/fixture-root`（Java 文件 + Redis 配置），覆盖全部 10 条规则的真实违规（共 16 处），并含一个混排文件（JDBC 行 + `redis.execute("FLUSHDB")`）；分别用**原始守卫**和**修复后守卫**扫同一棵树：

```sh
node <main>/scripts/ci/forbid-redis-cluster-unsafe.mjs --root evidence/fixture-root   # 18 处
node scripts/ci/forbid-redis-cluster-unsafe.mjs        --root evidence/fixture-root   # 16 处
```

- 差集（原始有、修复后无）**恰好 2 条**，都是 JDBC SQL：`FlywayLike.java:7`（advisory_lock）、`FlywayLike.java:14`（advisory_unlock）；
- 反向差集为空（修复后没有多报）；
- 仍被抓到：`redis-raw-command`(SELECT/KEYS/SCAN/FLUSHALL/FLUSHDB 各 1 + `redis.execute("FLUSHDB")`) 6 条、`redis-typed-command` 2 条、`keys`/`scan`/`flushDb`/`setDatabase`/`Range.Bound.exclusive`/排他流 ID 各 1 条、配置 `database:` 与 URL 库号各 1 条；
- 纯 JPA/业务反例文件 `BenignJpa.java` 0 命中。

日志：`evidence-redis-guard-pg/guard-fixture-ORIGINAL.log`、`guard-fixture-PATCHED.log`。
（`_p2-review/evidence-redis-guard-pg/fixture-root` 是同一棵 fixture 树的拷贝；上面两条命令要在隔离副本内执行，因为要用副本里那份已修复的 `scripts/ci` 守卫。）

## 7. 补丁

- 文件：`<仓外证据>/redis-guard-pg.patch`（同时放在隔离副本根目录，内容一致）
- 格式：`git apply -p1` 兼容，UTF-8 + LF，2 文件 `+107 / −5`
- sha256：`9653f6d6acf902bb106f155102161ae5d40ffdf54b7cd71725672fb6405217f2`

应用与校验（main 当前该两文件无本地改动，`git apply --check` 通过）：

```sh
cd <主仓>
git apply --check <仓外证据>/redis-guard-pg.patch   # 通过
git apply         <仓外证据>/redis-guard-pg.patch
git status --short                                          # 只应多出这两个 scripts 文件
node scripts/ci/forbid-redis-cluster-unsafe.mjs --self-check && node scripts/ci/forbid-redis-cluster-unsafe.mjs
```

补充验证：在一个继承本机 `core.autocrlf=true` 的临时 git 仓库里 apply 后，`git status` 只有 2 个 `M`，`git diff --numstat` 为 `1/1`（README）与 `106/4`（mjs），无换行风格翻动；apply 后从该仓库跑 `--self-check` 与 `--root <main>/backend/src/main` 均通过。

<details>
<summary>补丁全文</summary>

```diff
diff --git a/scripts/README.md b/scripts/README.md
index cb3fe46..3b60cca 100644
--- a/scripts/README.md
+++ b/scripts/README.md
@@ -309,4 +309,4 @@ node scripts/ci/forbid-redis-cluster-unsafe.mjs --self-check # 规则自证
 node scripts/ci/forbid-redis-cluster-unsafe.mjs --root <dir> # 指定目录（排障）
 ```

-禁止进入 `backend/src/main` 的面：**多库**（`database:` / URL 库号 / `setDatabase`）、**单机全量命令**（`KEYS` / `FLUSHDB` / `FLUSHALL` / 跨键 `SCAN`）、**5.0 不支持的排他流 ID**（`Range.Bound.exclusive` / 字符串 `(123-0`）。keys/scan 规则只认 Redis 侧接收者（避开 JPA 原生 SQL 与业务方法 `scan(` 的误报），配置规则要求文件处于 Redis 上下文；`ci.yml` 里 `--self-check` 与主扫各一步。
+禁止进入 `backend/src/main` 的面：**多库**（`database:` / URL 库号 / `setDatabase`）、**单机全量命令**（`KEYS` / `FLUSHDB` / `FLUSHALL` / 跨键 `SCAN`）、**5.0 不支持的排他流 ID**（`Range.Bound.exclusive` / 字符串 `(123-0`）。keys/scan 规则只认 Redis 侧接收者（避开 JPA 原生 SQL 与业务方法 `scan(` 的误报），配置规则要求文件处于 Redis 上下文；原生命令字面量同样按接收者判定，同文件声明为 `Statement` / `PreparedStatement` 的变量按 JDBC 处理（`statement.execute("SELECT pg_advisory_lock(...)")` 是 PostgreSQL SQL，不是 Redis `SELECT`）。`ci.yml` 里 `--self-check` 与主扫各一步。
diff --git a/scripts/ci/forbid-redis-cluster-unsafe.mjs b/scripts/ci/forbid-redis-cluster-unsafe.mjs
index e65e26c..08f2f87 100644
--- a/scripts/ci/forbid-redis-cluster-unsafe.mjs
+++ b/scripts/ci/forbid-redis-cluster-unsafe.mjs
@@ -14,6 +14,9 @@
  *     裸匹配 `SELECT` / `scan(` 会把它们误报成红线。
  *   - 因此 keys/scan 规则只认 Redis 侧接收者（含 lambda 别名 `c` / `rt` / `conn` / `client`），
  *     配置规则要求文件处于 Redis 上下文（同文件出现 `redis:` 或显式 redis 键）。
+ *   - 原生命令字面量同样按接收者判定：同文件声明为 `Statement` / `PreparedStatement` 的变量
+ *     是 JDBC 语句（`statement.execute("SELECT pg_advisory_lock(...)")` 是 PostgreSQL SQL），
+ *     其 `execute` 只跑 SQL、发不出 Redis 协议命令；判定见 jdbcStatementVars。
  *
  * 用法：
  *   node scripts/ci/forbid-redis-cluster-unsafe.mjs              # 扫 backend/src/main
@@ -90,8 +93,10 @@ const RULES = [
   {
     id: 'redis-raw-command',
     kind: 'java',
-    // connection.execute("FLUSHDB") —— 只认命令字面量，不认 SQL 字符串
-    regex: /\.execute\s*\(\s*["'](?:SELECT|KEYS|FLUSHDB|FLUSHALL|SCAN)\b/i,
+    // connection.execute("FLUSHDB") —— 只认 Redis 侧接收者上的命令字面量，不认 SQL 字符串。
+    // 接收者落进第 1 个捕获组，供 jdbcStatementVars 判定 JDBC（见该函数注释）。
+    regex: /(\b[A-Za-z_$][\w$]*)?\.execute\s*\(\s*["'](?:SELECT|KEYS|FLUSHDB|FLUSHALL|SCAN)\b/i,
+    receiverGroup: 1,
     why: '原生命令字面量绕过类型化 API，同样属于禁止项',
   },
   {
@@ -126,16 +131,45 @@ function applicability(rule, file, text) {
   return true
 }

+/**
+ * 本文件里声明为 `java.sql.Statement` / `PreparedStatement` / `CallableStatement` 的变量名。
+ *
+ * `Statement.execute(String)` 只有 SQL 语义（Flyway 启动锁的
+ * `statement.execute("SELECT pg_advisory_lock(...)")` 即 PostgreSQL SQL）；Redis 的
+ * `execute` 位于 `RedisConnection` / `RedisTemplate`，不存在 Statement 这种类型。
+ * 因此按声明类型（而非接收者命名）做 JDBC-vs-Redis 区分：命中的接收者若是 JDBC 语句变量，
+ * 该次调用不是 Redis 原生命令。只认同文件内声明，跨文件/链式 `createStatement().execute(...)` 不豁免。
+ */
+function jdbcStatementVars(text) {
+  const vars = new Set()
+  const decl = /\b(?:PreparedStatement|CallableStatement|Statement)\s+([A-Za-z_$][\w$]*)/g
+  let m
+  while ((m = decl.exec(text))) vars.add(m[1])
+  return vars
+}
+
+/** 该规则在该行是否命中；带接收者的规则按调用逐个判定，豁免只作用于被豁免的那次调用。 */
+function ruleHits(rule, line, jdbcVars) {
+  if (rule.receiverGroup == null) return rule.regex.test(line)
+  const re = new RegExp(rule.regex.source, 'gi')
+  let m
+  while ((m = re.exec(line))) {
+    const receiver = m[rule.receiverGroup]
+    if (!(receiver && jdbcVars.has(receiver))) return true
+  }
+  return false
+}
+
 function scanText(file, text) {
   const hits = []
   const lines = text.split(/\r?\n/)
+  const jdbcVars = jdbcStatementVars(text)
   for (const rule of RULES) {
     if (!applicability(rule, file, text)) continue
     for (let i = 0; i < lines.length; i++) {
-      if (rule.regex.test(lines[i])) {
+      if (ruleHits(rule, lines[i], jdbcVars)) {
         hits.push({ file, line: i + 1, rule: rule.id, why: rule.why, text: lines[i].trim() })
       }
-      rule.regex.lastIndex = 0
     }
   }
   return hits
@@ -270,6 +304,74 @@ function selfCheck() {
       text: 'c.keys("a"); c.scan(ScanOptions.scanOptions().build());',
       hits: 2,
     },
+    {
+      // 真实行取自 config/FlywayStartupLock.java:159（PostgreSQL 会话级 advisory lock）。
+      name: 'JDBC pg_advisory_lock（反例：PostgreSQL SQL 不是 Redis SELECT）',
+      file: 'backend/src/main/java/com/example/configmgr/config/FlywayStartupLock.java',
+      text: 'try (Statement statement = connection.createStatement()) {\n'
+        + '    statement.execute("SELECT pg_advisory_lock(" + PG_LOCK_KEY + ")");\n'
+        + '}',
+      hits: 0,
+    },
+    {
+      // 真实行取自 config/FlywayStartupLock.java:165。
+      name: 'JDBC pg_advisory_unlock（反例：PostgreSQL SQL 不是 Redis SELECT）',
+      file: 'backend/src/main/java/com/example/configmgr/config/FlywayStartupLock.java',
+      text: 'try (Statement statement = connection.createStatement()) {\n'
+        + '    statement.execute("SELECT pg_advisory_unlock(" + PG_LOCK_KEY + ")");\n'
+        + '}',
+      hits: 0,
+    },
+    {
+      name: 'JDBC Statement 上任意 SQL SELECT（反例：不是 Redis 命令）',
+      file: 'backend/src/main/java/x/Y.java',
+      text: 'PreparedStatement statement = connection.prepareStatement("x");\n'
+        + 'statement.execute("SELECT 1");',
+      hits: 0,
+    },
+    {
+      name: 'JDBC 豁免不外溢：同行 Redis 原生命令仍命中（数量断言）',
+      file: 'backend/src/main/java/x/Y.java',
+      text: 'Statement statement = connection.createStatement();\n'
+        + 'statement.execute("SELECT 1"); connection.execute("FLUSHDB");',
+      hits: 1,
+    },
+    {
+      name: 'Redis 原生 SELECT 字面量（RedisConnection）',
+      file: 'backend/src/main/java/x/Y.java',
+      text: 'connection.execute("SELECT 1");',
+      hits: 1,
+    },
+    {
+      name: 'Redis 原生 KEYS 字面量（redisTemplate）',
+      file: 'backend/src/main/java/x/Y.java',
+      text: 'redisTemplate.execute("KEYS ai:*");',
+      hits: 1,
+    },
+    {
+      name: 'Redis 原生 SCAN 字面量（lambda 别名 rt）',
+      file: 'backend/src/main/java/x/Y.java',
+      text: 'rt.execute("SCAN 0");',
+      hits: 1,
+    },
+    {
+      name: 'Redis 原生 FLUSHALL 字面量（客户端别名 client）',
+      file: 'backend/src/main/java/x/Y.java',
+      text: 'client.execute("FLUSHALL");',
+      hits: 1,
+    },
+    {
+      name: '类型化 CommandType.SCAN',
+      file: 'backend/src/main/java/x/Y.java',
+      text: 'statement.execute(CommandType.SCAN);',
+      hits: 1,
+    },
+    {
+      name: '变量名叫 statement 但类型是 RedisConnection（反例：不得按名豁免）',
+      file: 'backend/src/main/java/x/Y.java',
+      text: 'RedisConnection statement = factory.getConnection();\nstatement.execute("FLUSHDB");',
+      hits: 1,
+    },
   ]

   let failed = 0
```

</details>

## 8. 已知边界（刻意不扩范围）

1. **只认同文件声明**：JDBC 语句变量若声明在别的文件（如工具类把 `Statement` 传来传去、只在调用处出现）则不豁免 → 仍可能误报。当前 main 无此形态。
2. **`var` 推断不认**：`var statement = connection.createStatement()` 不会进集合（需要类型推断），仍会误报。当前 main 统一写显式类型。
3. **链式调用不豁免**：`connection.createStatement().execute("SELECT 1")` 接收者不是标识符，行为与修复前一致（仍命中）。这是保守选择：宁可误报，不放过非标识符接收者上的 Redis 原生命令。
4. **豁免按调用而非按字面量**：`Statement` 上的 `execute("FLUSHDB")` 也一并不报 —— 因为 JDBC 语句发不出 Redis 协议命令，该写法本身不可能构成 Redis 违规；这是"按类型"判定的必然结果，已在自证中显式覆盖同型逻辑（自证用的是 `SELECT` 形态，避免把这个语义藏起来）。
5. 未触碰 `redis-typed-command`（`RedisCommand`/`CommandType` 类型为 Redis 独有，无需按接收者判定）、未改常量 `REDIS_RECEIVER`、未改扫描范围与退出码语义、未改 CI 步骤。

## 9. 复现命令（隔离副本内全部可重放）

```sh
cd <验证副本>/_verify-20261025-redis-guard-pg
node scripts/ci/forbid-redis-cluster-unsafe.mjs --self-check
node scripts/ci/forbid-redis-cluster-unsafe.mjs
node scripts/ci/forbid-redis-cluster-unsafe.mjs --root <主仓>/backend/src/main
node scripts/ci/forbid-redis-cluster-unsafe.mjs --root evidence/fixture-root   # 期望 16 处、exit 1
```
