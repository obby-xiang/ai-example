#!/usr/bin/env node
/**
 * forbid-redis-cluster-unsafe.mjs —— Redis Cluster / 5.0 兼容面的 grep 级守卫。
 *
 * 设计依据：docs/research/排查-A4-Cluster与5.0代码级兼容穷尽-2026-10-24.md §十「批次 4」
 * 与 §五「零 SELECT / 零多库」：生产是原生 Redis Cluster（3 主 3 从、16384 槽）。
 * 禁止进入 backend/src/main 的面：
 *   - 多库：`database:` / `spring.redis.database` / URL 库号（`redis://host/1`）/ `setDatabase`
 *   - 单机全量命令：`KEYS` / `FLUSHDB` / `FLUSHALL` / 跨键 `SCAN`
 *   - 5.0 不支持的排他流 ID：`Range.Bound.exclusive` / 字符串 `(123-0`
 *
 * 为什么不是一条裸 grep：
 *   - 后端有 JPA 原生 SQL（`"SELECT * FROM ..."`）与业务方法 `runner.scan(true)`；
 *     裸匹配 `SELECT` / `scan(` 会把它们误报成红线。
 *   - 因此 keys/scan 规则只认 Redis 侧接收者（含 lambda 别名 `c` / `rt` / `conn` / `client`），
 *     配置规则要求文件处于 Redis 上下文（同文件出现 `redis:` 或显式 redis 键）。
 *   - 原生命令字面量同样按接收者判定：同文件、同作用域内声明为 `Statement` /
 *     `PreparedStatement` / `CallableStatement` 的变量是 JDBC 语句
 *     （`statement.execute("SELECT pg_advisory_lock(...)")` 是 PostgreSQL SQL），
 *     其 `execute` 只跑 SQL、发不出 Redis 协议命令。豁免 fail-closed：先剥离注释与字符串
 *     字面量再找声明，声明必须包住调用点，同名另有其它类型声明（遮蔽）则不豁免，
 *     Redis 侧别名（`conn` / `client` / `connection` …）永不豁免；判定见 analyzeDeclarations。
 *
 * 用法：
 *   node scripts/ci/forbid-redis-cluster-unsafe.mjs              # 扫 backend/src/main
 *   node scripts/ci/forbid-redis-cluster-unsafe.mjs --self-check # 规则自证（命中/不命中 + 命中数）
 *   node scripts/ci/forbid-redis-cluster-unsafe.mjs --root <dir> # 扫描指定目录（排障用）
 *
 * 退出码：0 = 干净；1 = 命中红线或自证失败。
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO = path.resolve(HERE, '..', '..')
const argv = process.argv.slice(2)

function arg(name, fallback = '') {
  const i = argv.indexOf(`--${name}`)
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : fallback
}

// Redis 侧接收者白名单（含 lambda 形参常见别名）。刻意不写 `redis\w*`：
// 否则 `redisKeys.keys()` 这类业务变量会误报。
// 同一份名单也是"永不豁免"名单：别名即使被声明成 Statement 也按 Redis 通道看待。
const REDIS_RECEIVER_NAMES = [
  'redis', 'redisTemplate', 'redisConnection', 'connection', 'conn', 'client', 'c', 'rt', 'template', 'jedis', 'lettuce',
]
const REDIS_RECEIVER = String.raw`(?:${REDIS_RECEIVER_NAMES.join('|')})`
const REDIS_RECEIVER_SET = new Set(REDIS_RECEIVER_NAMES)

// ── 规则表（唯一取数点）────────────────────────────────────────────────────
// kind: config = 只扫 *.yml / *.yaml / *.properties；java = 只扫 Java 源码。
const RULES = [
  {
    id: 'redis-database-config',
    kind: 'config',
    regex: /^\s*(?:spring\.(?:data\.)?redis\.database|database)\s*:/,
    // 进一步要求"同文件有 Redis 上下文"（见 scanText 的 config 分支），避免命中 JPA 的 database。
    why: 'Redis Cluster 只有 DB0；database 多库语义不可用',
  },
  {
    id: 'redis-url-database',
    kind: 'config',
    regex: /rediss?:\/\/[^\s"']+\/\d+/,
    why: 'URL 里的库号（redis://host/1）等价于 SELECT，Cluster 下不可用',
  },
  {
    id: 'redis-set-database',
    kind: 'java',
    regex: /\bsetDatabase\s*\(/,
    why: '禁止切库（Cluster 只有 DB0）',
  },
  {
    id: 'redis-keys-command',
    kind: 'java',
    regex: new RegExp(String.raw`\b${REDIS_RECEIVER}\.keys\s*\(`),
    why: 'KEYS 在 Cluster 下逐节点全量扫描，生产禁用',
  },
  {
    id: 'redis-scan-command',
    kind: 'java',
    regex: new RegExp(String.raw`\b${REDIS_RECEIVER}\.scan\s*\(`),
    why: 'SCAN 必须逐节点执行；跨节点扫描在 Cluster 下语义不完整',
  },
  {
    id: 'redis-flush-command',
    kind: 'java',
    regex: /\.(?:flushDb|flushAll)\s*\(/,
    why: 'FLUSHDB/FLUSHALL 在 Cluster 下需逐节点执行，生产禁用',
  },
  {
    id: 'redis-typed-command',
    kind: 'java',
    // execute(RedisCommand.SELECT) / connection.execute(CommandType.KEYS)
    regex: /\.execute\s*\(\s*(?:RedisCommand|CommandType)\.(?:SELECT|KEYS|FLUSHDB|FLUSHALL|SCAN)\b/,
    why: '类型化命令枚举绕过字面量规则，同样属于禁止项',
  },
  {
    id: 'redis-raw-command',
    kind: 'java',
    // connection.execute("FLUSHDB") —— 只认 Redis 侧接收者上的命令字面量，不认 SQL 字符串。
    // 接收者落进第 1 个捕获组，供 analyzeDeclarations 判定 JDBC（见该函数注释）。
    regex: /(\b[A-Za-z_$][\w$]*)?\.execute\s*\(\s*["'](?:SELECT|KEYS|FLUSHDB|FLUSHALL|SCAN)\b/i,
    receiverGroup: 1,
    why: '原生命令字面量绕过类型化 API，同样属于禁止项',
  },
  {
    id: 'redis-exclusive-range',
    kind: 'java',
    // Range.Bound.exclusive(...)（5.0 不支持的 `(id` 语法）
    regex: /\bRange\.Bound\.exclusive\s*\(/,
    why: '排他区间 `(id` 需 Redis 6.2+；5.0.14 会报错并被吞成空结果',
  },
  {
    id: 'redis-exclusive-stream-id-literal',
    kind: 'java',
    // 字符串拼出的排他流 ID：`"(" + seq + "-0"` / `"(123-0"`
    regex: /["']\(\s*(?:\d+-\d+|["']\s*\+[^\n]*?-\d+)/,
    why: '排他流 ID 字面量（`(id`）在 5.0.14 非法；必须用闭区间 (from+1)-0',
  },
]

const TEXT_EXTENSIONS = new Set(['.java', '.yml', '.yaml', '.properties'])

function isConfigFile(file) {
  return /\.(?:ya?ml|properties)$/.test(file)
}

function configHasRedisContext(text) {
  return /\bredis\s*:/.test(text) || /spring\.(?:data\.)?redis\./.test(text)
}

function applicability(rule, file, text) {
  if (rule.kind === 'config') return isConfigFile(file) && configHasRedisContext(text)
  if (rule.kind === 'java') return file.endsWith('.java')
  return true
}

/** 把注释、字符串/字符字面量的内容换成空格（保留长度与换行），供声明与作用域分析用。 */
function stripCommentsAndLiterals(text) {
  const blank = (s) => s.replace(/[^\n]/g, ' ')
  let out = ''
  let i = 0
  while (i < text.length) {
    const ch = text[i]
    const next = text[i + 1]
    if (ch === '/' && next === '/') {
      const nl = text.indexOf('\n', i)
      const end = nl < 0 ? text.length : nl
      out += blank(text.slice(i, end))
      i = end
      continue
    }
    if (ch === '/' && next === '*') {
      const close = text.indexOf('*/', i + 2)
      const end = close < 0 ? text.length : close + 2
      out += blank(text.slice(i, end))
      i = end
      continue
    }
    // 文本块 """..."""（Java 15+）与普通 "..." / '...'
    const quote = text.startsWith('"""', i) ? '"""' : ch
    if (quote === '"' || quote === "'") {
      let j = i + quote.length
      while (j < text.length) {
        if (text[j] === '\\' && quote !== '"""') {
          j += 2
          continue
        }
        if (text.startsWith(quote, j)) {
          j += quote.length
          break
        }
        if (quote !== '"""' && text[j] === '\n') break
        j++
      }
      out += blank(text.slice(i, j))
      i = j
      continue
    }
    out += ch
    i++
  }
  return out
}

/** 所有成对 `{}` 的偏移区间（用于近似作用域）。 */
function braceSpans(code) {
  const spans = []
  const stack = []
  for (let i = 0; i < code.length; i++) {
    if (code[i] === '{') stack.push(i)
    else if (code[i] === '}') {
      const open = stack.pop()
      if (open != null) spans.push({ open, close: i })
    }
  }
  return spans
}

/** 包住该偏移的最内层 `{}`；不在任何块内返回 null。 */
function enclosingSpan(spans, offset) {
  let best = null
  for (const s of spans) {
    if (s.open < offset && offset < s.close && (best == null || s.open > best.open)) best = s
  }
  return best
}

/**
 * 声明的作用域块：
 *   - 形参（`f(RedisConnection statement) {`）/ try-with-resources（`try (Statement s = ...) {`）
 *     这类未以 `;` 结束的声明，作用域是紧随其后的 `{}` 块，不是外层类体；
 *   - 其余（字段、局部变量）作用域就是声明所在的块。
 * 这样同名的 Redis 形参只遮蔽它自己方法体里的调用，不会把同类里其它方法的 JDBC 调用一起拖下水。
 */
function declarationScope(code, spans, offset) {
  const braceAfter = code.indexOf('{', offset)
  const semiAfter = code.indexOf(';', offset)
  if (braceAfter >= 0 && (semiAfter < 0 || braceAfter < semiAfter)) {
    const body = spans.find((s) => s.open === braceAfter)
    if (body) return body
  }
  return enclosingSpan(spans, offset)
}

/**
 * JDBC-vs-Redis 声明分析（`Statement.execute(String)` 只有 SQL 语义；Redis 的 execute 在
 * RedisConnection / RedisTemplate 上，没有 Statement 这种类型）。
 *
 * 为了让豁免"有据可依、失败即命中"（fail-closed）：
 *   - 只在剥离注释/字符串字面量后的代码里找声明，散文与文档串不会造出 JDBC 变量；
 *   - 每条声明记录其作用域块（形参/资源声明取紧随其后的方法体/try 体，见 declarationScope）：
 *     调用点必须被该块包住才可能豁免，跨方法/跨 try 的同名声明不互相外溢；同名若还有
 *     别的类型声明也包住调用点（遮蔽），则宁可不豁免；
 *   - `REDIS_RECEIVER_NAMES` 里的接收者别名一律不豁免（按其 Redis 语义处理）。
 * 局限（刻意保守）：只认同文件、同块可见的声明；`var`/跨文件/链式 `createStatement().execute(...)` 不豁免。
 */
function analyzeDeclarations(text) {
  const code = stripCommentsAndLiterals(text)
  const spans = braceSpans(code)
  const jdbc = new Map()
  const other = new Map()
  const record = (map, name, offset) => {
    if (!map.has(name)) map.set(name, [])
    map.get(name).push(declarationScope(code, spans, offset))
  }
  const jdbcDecl = /\b(PreparedStatement|CallableStatement|Statement)\s+([A-Za-z_$][\w$]*)/g
  let m
  while ((m = jdbcDecl.exec(code))) record(jdbc, m[2], m.index)
  // 其它类型声明：`Type name` / `var name`（只看首字母大写的类型与 var，避免把 return/throw 当类型）。
  const otherDecl = /\b((?:[a-z][\w$]*\.)*[A-Z][\w$]*(?:\s*<[^;{}()]*>)?|var)\s+([a-z_$][\w$]*)\s*(?=[=;,:)])/g
  while ((m = otherDecl.exec(code))) {
    if (/^(?:PreparedStatement|CallableStatement|Statement)$/.test(m[1])) continue
    record(other, m[2], m.index)
  }
  return { jdbc, other }
}

/** 该接收者在该偏移处是否可按 JDBC 语句豁免（任一条不成立就照常命中）。 */
function isJdbcExempt(decls, receiver, offset) {
  if (!receiver || REDIS_RECEIVER_SET.has(receiver)) return false
  const jdbcBlocks = decls.jdbc.get(receiver)
  if (!jdbcBlocks) return false
  const contains = (span) => span != null && span.open < offset && offset < span.close
  if (!jdbcBlocks.some(contains)) return false
  const shadows = decls.other.get(receiver)
  return !(shadows != null && shadows.some(contains))
}

/** 该规则在该行是否命中；带接收者的规则按调用逐个判定，豁免只作用于被豁免的那次调用。 */
function ruleHits(rule, line, lineStart, decls) {
  if (rule.receiverGroup == null) return rule.regex.test(line)
  const re = new RegExp(rule.regex.source, 'gi')
  let m
  while ((m = re.exec(line))) {
    if (!isJdbcExempt(decls, m[rule.receiverGroup], lineStart + m.index)) return true
  }
  return false
}

function scanText(file, text) {
  const hits = []
  const lines = text.split(/\r?\n/)
  const lineStarts = [0]
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '\n') lineStarts.push(i + 1)
  }
  const decls = analyzeDeclarations(text)
  for (const rule of RULES) {
    if (!applicability(rule, file, text)) continue
    for (let i = 0; i < lines.length; i++) {
      if (ruleHits(rule, lines[i], lineStarts[i] ?? 0, decls)) {
        hits.push({ file, line: i + 1, rule: rule.id, why: rule.why, text: lines[i].trim() })
      }
    }
  }
  return hits
}

function walk(dir) {
  const out = []
  if (!fs.existsSync(dir)) return out
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'target' || entry.name === 'node_modules' || entry.name.startsWith('.')) continue
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) out.push(...walk(full))
    else if (TEXT_EXTENSIONS.has(path.extname(entry.name))) out.push(full)
  }
  return out
}

function selfCheck() {
  // 规则自证：每个样例断言命中<b>数量</b>（0/1/2），规则被改窄（漏报）时也会红。
  const cases = [
    {
      name: 'spring.data.redis.database',
      file: 'backend/src/main/resources/application.yml',
      text: 'spring:\n  data:\n    redis:\n      database: 0\n',
      hits: 1,
    },
    {
      name: 'spring.redis.database（无 .data）',
      file: 'backend/src/main/resources/application.yml',
      text: 'spring:\n  redis:\n    database: 1\n',
      hits: 1,
    },
    {
      name: 'Redis URL 库号',
      file: 'backend/src/main/resources/application.yml',
      text: 'spring:\n  data:\n    redis:\n      url: redis://127.0.0.1:6379/1\n',
      hits: 1,
    },
    {
      name: 'JPA database-platform 不是 Redis 切库',
      file: 'backend/src/main/resources/application.yml',
      text: 'spring:\n  jpa:\n    database-platform: org.hibernate.dialect.H2Dialect\n',
      hits: 0,
    },
    {
      name: 'redisTemplate.keys',
      file: 'backend/src/main/java/x/Y.java',
      text: 'this.redisTemplate.keys("ai:*");',
      hits: 1,
    },
    {
      name: 'lambda 别名 c.keys',
      file: 'backend/src/main/java/x/Y.java',
      text: 'connection.execute((RedisCallback) c -> c.keys("ai:*"));',
      hits: 1,
    },
    {
      name: 'lambda 别名 rt.scan',
      file: 'backend/src/main/java/x/Y.java',
      text: 'rt.scan(ScanOptions.scanOptions().match("ai:*").build());',
      hits: 1,
    },
    {
      name: '原生 FLUSHDB 命令字面量',
      file: 'backend/src/main/java/x/Y.java',
      text: 'connection.execute("FLUSHDB");',
      hits: 1,
    },
    {
      name: '类型化 RedisCommand.SELECT',
      file: 'backend/src/main/java/x/Y.java',
      text: 'connection.execute(RedisCommand.SELECT);',
      hits: 1,
    },
    {
      name: 'setDatabase',
      file: 'backend/src/main/java/x/Y.java',
      text: 'this.redis.setDatabase(1);',
      hits: 1,
    },
    {
      name: 'Range.Bound.exclusive',
      file: 'backend/src/main/java/x/Y.java',
      text: 'Range.from(Range.Bound.exclusive("1-0"));',
      hits: 1,
    },
    {
      name: '排他流 ID 字面量',
      file: 'backend/src/main/java/x/Y.java',
      text: 'String from = "(" + seq + "-0";',
      hits: 1,
    },
    {
      name: '业务 runner.scan(true)（反例：不得误报）',
      file: 'backend/src/main/java/com/example/configmgr/ai/web/AiController.java',
      text: 'return ApiResponse.ok(runner.scan(true));',
      hits: 0,
    },
    {
      name: 'JPA 原生 SQL SELECT（反例：不得误报）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'String sql = "SELECT * FROM config_def";',
      hits: 0,
    },
    {
      name: '纯业务 "SELECT" 字面量（反例：不得误报）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'String op = "SELECT";',
      hits: 0,
    },
    {
      name: 'Map 的 "keys" 文案（反例：不得误报）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'out.put("keys", describeKeys(runId, snapshot));',
      hits: 0,
    },
    {
      name: 'redisKeys.keys 业务变量（反例：不得误报）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'redisKeys.keys().forEach(System.out::println);',
      hits: 0,
    },
    {
      name: '小写 select 控件类型（反例：不得误报）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'Set.of("button", "input", "select", "toggle");',
      hits: 0,
    },
    {
      name: '同一行两个禁用命令（数量断言）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'c.keys("a"); c.scan(ScanOptions.scanOptions().build());',
      hits: 2,
    },
    {
      // 真实行取自 config/FlywayStartupLock.java:159（PostgreSQL 会话级 advisory lock）。
      name: 'JDBC pg_advisory_lock（反例：PostgreSQL SQL 不是 Redis SELECT）',
      file: 'backend/src/main/java/com/example/configmgr/config/FlywayStartupLock.java',
      text: 'class X {\n'
        + '    void acquirePostgres(Connection connection) throws SQLException {\n'
        + '        try (Statement statement = connection.createStatement()) {\n'
        + '            statement.execute("SELECT pg_advisory_lock(" + PG_LOCK_KEY + ")");\n'
        + '        }\n'
        + '    }\n'
        + '}',
      hits: 0,
    },
    {
      // 真实行取自 config/FlywayStartupLock.java:165。
      name: 'JDBC pg_advisory_unlock（反例：PostgreSQL SQL 不是 Redis SELECT）',
      file: 'backend/src/main/java/com/example/configmgr/config/FlywayStartupLock.java',
      text: 'class X {\n'
        + '    void releasePostgres(Connection connection) throws SQLException {\n'
        + '        try (Statement statement = connection.createStatement()) {\n'
        + '            statement.execute("SELECT pg_advisory_unlock(" + PG_LOCK_KEY + ")");\n'
        + '        }\n'
        + '    }\n'
        + '}',
      hits: 0,
    },
    {
      name: 'JDBC Statement 上任意 SQL SELECT（反例：不是 Redis 命令）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'class X {\n'
        + '    void load(Connection connection) throws SQLException {\n'
        + '        PreparedStatement statement = connection.prepareStatement("x");\n'
        + '        statement.execute("SELECT 1");\n'
        + '    }\n'
        + '}',
      hits: 0,
    },
    {
      name: 'JDBC CallableStatement 仍豁免（反例：SQL 存储过程调用不是 Redis 命令）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'class X {\n'
        + '    void run(Connection connection) throws SQLException {\n'
        + '        CallableStatement call = connection.prepareCall("{call p()}");\n'
        + '        call.execute("SELECT p()");\n'
        + '    }\n'
        + '}',
      hits: 0,
    },
    {
      name: 'JDBC 语句字段在方法内使用仍豁免（反例：字段作用域不丢）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'class X {\n'
        + '    private final Statement statement;\n'
        + '    void a() { statement.execute("SELECT 1"); }\n'
        + '}',
      hits: 0,
    },
    {
      name: 'JDBC 豁免不外溢：同行 Redis 原生命令仍命中（数量断言）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'class X {\n'
        + '    void run(Connection connection) throws SQLException {\n'
        + '        Statement statement = connection.createStatement();\n'
        + '        statement.execute("SELECT 1"); connection.execute("FLUSHDB");\n'
        + '    }\n'
        + '}',
      hits: 1,
    },
    {
      name: 'Redis 原生 SELECT 字面量（RedisConnection）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'connection.execute("SELECT 1");',
      hits: 1,
    },
    {
      name: 'Redis 原生 KEYS 字面量（redisTemplate）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'redisTemplate.execute("KEYS ai:*");',
      hits: 1,
    },
    {
      name: 'Redis 原生 SCAN 字面量（lambda 别名 rt）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'rt.execute("SCAN 0");',
      hits: 1,
    },
    {
      name: 'Redis 原生 FLUSHALL 字面量（客户端别名 client）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'client.execute("FLUSHALL");',
      hits: 1,
    },
    {
      name: '类型化 CommandType.SCAN',
      file: 'backend/src/main/java/x/Y.java',
      text: 'statement.execute(CommandType.SCAN);',
      hits: 1,
    },
    {
      name: '变量名叫 statement 但类型是 RedisConnection（反例：不得按名豁免）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'class X {\n'
        + '    private final Statement statement;\n'
        + '    void use(RedisConnection c2) {\n'
        + '        RedisConnection statement = c2.getConnection();\n'
        + '        statement.execute("FLUSHDB");\n'
        + '    }\n'
        + '}',
      hits: 1,
    },
    {
      // 评审 I-1 探针 2：注释里的 "Statement conn" 不得把 Redis 连接别名 conn 变成 JDBC。
      name: '注释污染：// Statement conn 不得豁免 conn.execute（反例）',
      file: 'backend/src/main/java/x/Y.java',
      text: '// The JDBC Statement conn above is unrelated to this Redis connection.\n'
        + 'conn.execute("FLUSHDB");',
      hits: 1,
    },
    {
      // 评审 I-1 探针 3：字符串字面量里的 "PreparedStatement client" 同理。
      name: '字符串污染："PreparedStatement client" 不得豁免 client.execute（反例）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'String doc = "legacy PreparedStatement client";\n'
        + 'client.execute("SCAN 0");',
      hits: 1,
    },
    {
      // 评审 I-1 探针 1：方法 A 的 JDBC 声明不得豁免方法 B 里同名的 RedisConnection。
      name: '跨作用域同名不同型：A 方法 JDBC statement 不豁免 B 方法 Redis statement（反例）',
      file: 'backend/src/main/java/x/Y.java',
      text: 'void sql(Connection connection) {\n'
        + '    Statement statement = connection.createStatement();\n'
        + '    statement.execute("SELECT 1");\n'
        + '}\n'
        + 'void redis(RedisConnection statement) {\n'
        + '    statement.execute("FLUSHDB");\n'
        + '}',
      hits: 1,
    },
  ]

  let failed = 0
  for (const c of cases) {
    const hits = scanText(c.file, c.text)
    if (hits.length !== c.hits) {
      failed++
      console.error(`[self-check] FAIL ${c.name}：期望命中 ${c.hits}，实际 ${hits.length}`)
    }
    else {
      console.log(`[self-check] ok   ${c.name}（命中 ${hits.length}）`)
    }
  }
  if (failed > 0) return false
  // 数量断言：正向样例至少 12 条，防止规则表被整段删空后仍"全绿"。
  const positive = cases.filter((c) => c.hits > 0).length
  if (positive < 12) {
    console.error(`[self-check] FAIL 正向样例只有 ${positive} 条（应 ≥ 12）`)
    return false
  }
  console.log(`[self-check] 正向样例 ${positive} 条，数量断言通过。`)
  return true
}

function main() {
  if (argv.includes('--self-check')) {
    if (!selfCheck()) {
      console.error('规则自证失败：守卫规则与反例漂移，先修脚本再谈扫描。')
      process.exit(1)
    }
    console.log('规则自证通过。')
    return
  }

  const root = path.resolve(arg('root', path.join(REPO, 'backend', 'src', 'main')))
  const files = walk(root)
  const hits = []
  for (const file of files) {
    let text
    try {
      text = fs.readFileSync(file, 'utf8')
    }
    catch {
      continue
    }
    if (text.includes('\u0000')) continue
    hits.push(...scanText(file, text))
  }

  if (hits.length === 0) {
    console.log(`Redis Cluster 安全守卫：扫描 ${files.length} 个文件，0 命中。`)
    return
  }
  for (const hit of hits) {
    const rel = path.relative(REPO, hit.file).split(path.sep).join('/')
    console.error(`::error file=${rel},line=${hit.line}::[${hit.rule}] ${hit.why} —— ${hit.text}`)
  }
  console.error(`Redis Cluster 安全守卫：${hits.length} 处命中（禁止 database/SELECT/KEYS/FLUSHDB/FLUSHALL/SCAN/排他区间进入生产代码）。`)
  process.exit(1)
}

main()
