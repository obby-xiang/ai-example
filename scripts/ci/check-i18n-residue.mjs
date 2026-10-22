#!/usr/bin/env node
/**
 * check-i18n-residue.mjs —— 前端残留中文扫描（i18n S7-③）。
 *
 * 零第三方依赖（只用 Node 内置模块，与 `scripts/ci/check-docs.mjs` 同口径）。
 *
 * 用法：
 *   node scripts/ci/check-i18n-residue.mjs                    # 报告制：打印计数，恒 exit 0
 *   node scripts/ci/check-i18n-residue.mjs --strict           # 闸门制：超基线即红（棘轮）
 *   node scripts/ci/check-i18n-residue.mjs --json             # 机器可读输出
 *   node scripts/ci/check-i18n-residue.mjs --update-baseline  # 重写基线（只许下调，见下）
 *   node scripts/ci/check-i18n-residue.mjs --root <dir>       # 指定前端根（对比历史工作树用）
 *
 * 口径（唯一、可复现）：
 *   - 范围 = `src/**\/*.{vue,ts}`，排除 `src/locales/**`（语言包本身）与 `*.d.ts`；
 *   - 计数单位 = **含中文的非注释行**（块注释 `/* *\/`、HTML 注释 `<!-- -->`、`//` 行整行剔除）；
 *     一行多处中文仍计 1 —— 行是最稳定的比较单位，字符数会被无关排版改动抖动；
 *   - 双受众白名单**不内置**：模型侧单语串（工具描述 / 状态词表 / 页面标签）必须留在源码里，
 *     它们计入本指标但不得迁入 locales（C5 红线）。基线里逐文件登记增量即可区分是谁涨的。
 *
 * 闸门纪律（L-49「只能最后一个开闸」）：
 *   - 默认报告制 —— CI 每跑一次都留数，但**不阻断**；
 *   - `--strict` 只做**棘轮**：总量或任一文件计数**高于**基线即红，下降不报错（只提示可 `--update-baseline`）；
 *   - 迁完最后一处后把 CI 步骤改成 `--strict`，即完成「进 CI 阻断」的收口。
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO = path.resolve(HERE, '..', '..')
const BASELINE = path.join(HERE, 'i18n-residue-baseline.json')
const CJK = /[\u4e00-\u9fff]/

const args = process.argv.slice(2)
const STRICT = args.includes('--strict')
const JSON_OUT = args.includes('--json')
const UPDATE = args.includes('--update-baseline')
const rootArg = args.indexOf('--root')
const ROOT = rootArg >= 0 ? path.resolve(args[rootArg + 1]) : path.join(REPO, 'frontend')

function walk(dir, acc = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name !== 'node_modules' && entry.name !== 'locales') walk(full, acc)
      continue
    }
    if (!/\.(vue|ts)$/.test(entry.name) || entry.name.endsWith('.d.ts')) continue
    acc.push(full)
  }
  return acc
}

/** 块注释 / HTML 注释整段剔除，行注释整行剔除（只做计数，不需要精确 AST）。 */
function stripComments(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .split(/\r?\n/)
    .filter((line) => {
      const trimmed = line.trim()
      return !(trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*'))
    })
    .join('\n')
}

function scan() {
  const src = path.join(ROOT, 'src')
  if (!fs.existsSync(src)) {
    throw new Error(`找不到前端源码目录：${src}`)
  }
  const files = {}
  let total = 0
  for (const file of walk(src)) {
    const relative = path.relative(ROOT, file).split(path.sep).join('/')
    const lines = stripComments(fs.readFileSync(file, 'utf8')).split(/\r?\n/)
    const count = lines.filter((line) => CJK.test(line)).length
    if (count > 0) {
      files[relative] = count
      total += count
    }
  }
  return { total, files }
}

const current = scan()

if (UPDATE) {
  fs.writeFileSync(BASELINE, `${JSON.stringify(current, null, 2)}\n`, 'utf8')
  console.log(`[i18n-residue] 基线已更新：${current.total} 行 / ${Object.keys(current.files).length} 文件`)
  process.exit(0)
}

let baseline = null
if (fs.existsSync(BASELINE)) {
  baseline = JSON.parse(fs.readFileSync(BASELINE, 'utf8'))
}

if (JSON_OUT) {
  console.log(JSON.stringify({ current, baseline }, null, 2))
} else {
  const top = Object.entries(current.files)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 15)
  console.log('[i18n-residue] 残留中文（非注释行 / 不含 locales）：')
  for (const [file, count] of top) {
    console.log(`  ${String(count).padStart(5)}  ${file}`)
  }
  if (Object.keys(current.files).length > top.length) {
    console.log(`  … 其余 ${Object.keys(current.files).length - top.length} 个文件`)
  }
  console.log(`[i18n-residue] 合计 ${current.total} 行 / ${Object.keys(current.files).length} 文件`)
  if (baseline) {
    const delta = current.total - baseline.total
    console.log(`[i18n-residue] 基线 ${baseline.total} 行 ⇒ ${delta === 0 ? '持平' : delta > 0 ? `+${delta}` : `${delta}`}`)
  }
}

if (!STRICT) {
  process.exit(0)
}

const problems = []
if (!baseline) {
  problems.push('缺少基线文件 scripts/ci/i18n-residue-baseline.json（先跑 --update-baseline 固化）')
} else {
  if (current.total > baseline.total) {
    problems.push(`总量 ${current.total} > 基线 ${baseline.total}（只许下调）`)
  }
  for (const [file, count] of Object.entries(current.files)) {
    const allowed = baseline.files[file] ?? 0
    if (count > allowed) {
      problems.push(`${file}: ${count} > 基线 ${allowed}`)
    }
  }
}
if (problems.length > 0) {
  console.error('[i18n-residue] 闸门红：')
  for (const problem of problems) console.error(`  - ${problem}`)
  process.exit(1)
}
console.log('[i18n-residue] 闸门绿（未超基线）')
