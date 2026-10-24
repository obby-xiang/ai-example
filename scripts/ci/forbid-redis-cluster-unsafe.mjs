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
const REDIS_RECEIVER = String.raw`(?:redis|redisTemplate|redisConnection|connection|conn|client|c|rt|template|jedis|lettuce)`

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
    // connection.execute("FLUSHDB") —— 只认命令字面量，不认 SQL 字符串
    regex: /\.execute\s*\(\s*["'](?:SELECT|KEYS|FLUSHDB|FLUSHALL|SCAN)\b/i,
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

function scanText(file, text) {
  const hits = []
  const lines = text.split(/\r?\n/)
  for (const rule of RULES) {
    if (!applicability(rule, file, text)) continue
    for (let i = 0; i < lines.length; i++) {
      if (rule.regex.test(lines[i])) {
        hits.push({ file, line: i + 1, rule: rule.id, why: rule.why, text: lines[i].trim() })
      }
      rule.regex.lastIndex = 0
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
