#!/usr/bin/env node
/**
 * codemod（一次性工具，**不进 CI**）：把前端源码里的中文文案抽成 `t('key')` 调用。
 *
 * 三段式（勘察 §4.3 建议）：
 *   node scripts/i18n/codemod.mjs extract   # 只读：产出待迁移清单 JSON（文件:行 → 建议 key → 原文）
 *   node scripts/i18n/codemod.mjs dry-run   # 只读：打印每个文件的 unified diff 与统计
 *   node scripts/i18n/codemod.mjs apply     # 落盘（仅两类最高置信度模式，其余保持人工）
 *
 * 支持的模式（高置信度，机械可判）：
 *   A) 模板纯文本节点：`<span>中文</span>`（同一行、无插值、无嵌套标签）
 *   B) 静态属性值：`label="中文"` / `placeholder="中文"` / `title="中文"`（同一行）
 *
 * **不支持（一律留给人）**：
 *   - 含 `${}` / `{{ }}` 插值的串（key 需要参数）；
 *   - 多行文本节点、含标签的节点；
 *   - TS 里的模板字符串、枚举/状态词表、`reason`/日志串；
 *   - 双受众字符串（`stores/workspace.ts#touch`、`PAGE_LABELS`、`types/condition.ts` 的 label 等）
 *     —— 它们进模型，迁进 i18n 会静默改 prompt（C5 红线）。
 *
 * 键名规则：`<area>.<fileStem>.<seq>`（area 取 `src/` 后的顶层目录：components/views/utils/stores/…）。
 * 机械键只保证"唯一且稳定"，**语义命名请在评审时改**；zh 译文作为值写入 locales，故渲染结果逐字不变。
 *
 * 用法补充：`--root <dir>` 指定前端根（默认脚本上两级），`--out <file>` 指定 extract 输出。
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const DEFAULT_ROOT = path.resolve(HERE, '..', '..')

const CJK = /[\u4e00-\u9fff]/
const TEMPLATE_TEXT = /(>)([^<>{}]{1,80}?[\u4e00-\u9fff][^<>{}]{0,80}?)(<)/g
const STATIC_ATTR = /\b(label|placeholder|title)\s*=\s*"([^"{}]{1,120}?[\u4e00-\u9fff][^"{}]{0,120}?)"/g

const args = process.argv.slice(2)
const mode = args[0] ?? 'extract'
const rootArg = args.indexOf('--root')
const ROOT = rootArg >= 0 ? path.resolve(args[rootArg + 1]) : DEFAULT_ROOT
const outArg = args.indexOf('--out')
const OUT = outArg >= 0 ? path.resolve(args[outArg + 1]) : null

const SKIP_DIRS = new Set(['node_modules', 'dist', 'coverage', '.git', 'tests', 'locales'])

function walk(dir, acc = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) walk(full, acc)
      continue
    }
    if (/\.(vue|ts)$/.test(entry.name) && !entry.name.endsWith('.d.ts')) acc.push(full)
  }
  return acc
}

/** 模板区（`<template>` 内）行号判定：足够用的一阶近似（scoped 子组件模板内必然在 <template> 之后）。 */
function isInsideTemplate(lines, index) {
  const upTo = lines.slice(0, index + 1).join('\n')
  const open = upTo.lastIndexOf('<template')
  const close = upTo.lastIndexOf('</template>')
  return open > close
}

function isCommentLine(line) {
  const trimmed = line.trim()
  return trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*') || trimmed.startsWith('<!--')
}

function areaOf(relative) {
  const parts = relative.split(/[\\/]/)
  const srcIndex = parts.indexOf('src')
  return srcIndex >= 0 && parts[srcIndex + 1] ? parts[srcIndex + 1] : 'app'
}

function stemOf(file) {
  return path.basename(file).replace(/\.(vue|ts)$/, '').replace(/[^A-Za-z0-9]/g, '')
}

/** 抽取候选（只读）。 */
export function extract(options = {}) {
  const root = options.root ?? ROOT
  const files = walk(path.join(root, 'src'))
  const found = []
  for (const file of files) {
    const relative = path.relative(root, file).split(path.sep).join('/')
    const text = fs.readFileSync(file, 'utf8')
    const lines = text.split(/\r?\n/)
    let seq = 0
    lines.forEach((line, index) => {
      if (isCommentLine(line)) return
      if (isInsideTemplate(lines, index)) {
        for (const match of line.matchAll(TEMPLATE_TEXT)) {
          const value = match[2].trim()
          if (!CJK.test(value) || /^\s*\{\{/.test(value)) continue
          seq += 1
          found.push({
            file: relative,
            line: index + 1,
            kind: 'text',
            value,
            column: match.index + match[1].length,
            suggestedKey: `${areaOf(relative)}.${stemOf(file)}.${seq}`
          })
        }
      }
      for (const match of line.matchAll(STATIC_ATTR)) {
        const value = match[2]
        if (!CJK.test(value)) continue
        seq += 1
        found.push({
          file: relative,
          line: index + 1,
          kind: 'attr',
          attr: match[1],
          value,
          column: match.index,
          suggestedKey: `${areaOf(relative)}.${stemOf(file)}.${seq}`
        })
      }
    })
  }
  return found
}

/** 生成单行替换结果（apply 与 dry-run 共用；保证 zh 渲染逐字不变）。 */
function rewriteLine(line, candidates) {
  let result = line
  for (const candidate of candidates) {
    if (candidate.kind === 'text') {
      result = result.replace(`>${candidate.raw}<`, `>{{ t('${candidate.suggestedKey}') }}<`)
    } else {
      result = result.replace(
        `${candidate.attr}="${candidate.value}"`,
        `:${candidate.attr}="t('${candidate.suggestedKey}')"`
      )
    }
  }
  return result
}

function groupByFile(candidates) {
  const byFile = new Map()
  for (const candidate of candidates) {
    if (!byFile.has(candidate.file)) byFile.set(candidate.file, [])
    byFile.get(candidate.file).push(candidate)
  }
  return byFile
}

/** 只保留"能机械替换"的候选（替换前后行内容必须真的变了）。 */
function replaceable(candidates) {
  return candidates.filter((candidate) => {
    const line = fs.readFileSync(path.join(ROOT, candidate.file), 'utf8').split(/\r?\n/)[candidate.line - 1]
    if (candidate.kind === 'text') {
      return line.includes(`>${candidate.value}<`)
    }
    return line.includes(`${candidate.attr}="${candidate.value}"`)
  })
}

export function dryRun() {
  const candidates = replaceable(extract())
  const byFile = groupByFile(candidates)
  let total = 0
  for (const [file, list] of byFile) {
    const lines = fs.readFileSync(path.join(ROOT, file), 'utf8').split(/\r?\n/)
    const byLine = new Map()
    for (const candidate of list) {
      if (!byLine.has(candidate.line)) byLine.set(candidate.line, [])
      byLine.get(candidate.line).push({ ...candidate, raw: candidate.value })
    }
    const hunks = []
    for (const [lineNo, lineCandidates] of byLine) {
      const original = lines[lineNo - 1]
      const rewritten = rewriteLine(original, lineCandidates)
      if (rewritten !== original) {
        hunks.push({ lineNo, original: original.trim(), rewritten: rewritten.trim() })
        total += lineCandidates.length
      }
    }
    if (hunks.length === 0) continue
    console.log(`--- a/${file}\n+++ b/${file}`)
    for (const hunk of hunks) {
      console.log(`@@ ${hunk.lineNo} @@`)
      console.log(`- ${hunk.original}`)
      console.log(`+ ${hunk.rewritten}`)
    }
  }
  console.log(`\n[dry-run] 可机械替换 ${total} 处 / ${byFile.size} 文件；其余需人工（插值/多行/双受众）`)
}

export function apply() {
  const candidates = replaceable(extract())
  const byFile = groupByFile(candidates)
  let total = 0
  for (const [file, list] of byFile) {
    const full = path.join(ROOT, file)
    const lines = fs.readFileSync(full, 'utf8').split(/\r?\n/)
    const byLine = new Map()
    for (const candidate of list) {
      if (!byLine.has(candidate.line)) byLine.set(candidate.line, [])
      byLine.get(candidate.line).push({ ...candidate, raw: candidate.value })
    }
    for (const [lineNo, lineCandidates] of byLine) {
      lines[lineNo - 1] = rewriteLine(lines[lineNo - 1], lineCandidates)
      total += lineCandidates.length
    }
    fs.writeFileSync(full, lines.join('\n'), 'utf8')
  }
  console.log(`[apply] 已替换 ${total} 处（zh 译文需同步写入 locales/*.json，否则运行时回落 key 原文）`)
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (mode === 'extract') {
    const found = extract()
    const payload = JSON.stringify(found, null, 2)
    if (OUT) {
      fs.writeFileSync(OUT, payload, 'utf8')
      console.log(`[extract] ${found.length} 条 → ${OUT}`)
    } else {
      console.log(payload)
    }
  } else if (mode === 'dry-run') {
    dryRun()
  } else if (mode === 'apply') {
    apply()
  } else {
    console.error(`未知模式：${mode}（extract | dry-run | apply）`)
    process.exit(2)
  }
}
