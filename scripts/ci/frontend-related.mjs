#!/usr/bin/env node
/**
 * frontend-related.mjs —— 前端 `vitest related` 的跨平台入口（C 期，设计稿 §4.2）。
 *
 * 设计稿 §4.2 的命令形态依赖 `xargs -r -d '\n'`：`-r`（空输入不跑）与 `-d '\n'`
 * （按换行切分，文件名含空格/中文不拆词）都是 GNU 扩展，Windows 的 Git Bash 与
 * macOS 自带的 BSD xargs 都没有 `-d`。本脚本用 Node 内置模块复刻同一语义：
 * `git diff` 的路径按行切分（不按空格），空变更集直接转全量，不经任何 shell 词分割。
 *
 * 定位（§4.1）：**前端不做选择化**。`related` 只作本地迭代加速，提交级门禁里前端仍是全量
 * （`frontend/package.json` 的 `test`），因此本脚本不参与 `scripts/ci/gate.mjs`。
 *
 * 用法（在 frontend/ 下）：
 *   yarn test:related                       # 按暂存树 ∪ 未跟踪 ∪ 未暂存（K1 单调放大）
 *   yarn test:related --base <sha>          # 指定 diff 基准
 *   yarn test:related src/utils/ai.ts ...   # 显式给文件（等价于 vitest related <files>）
 *
 * 直接转全量的三条（§4.2 末段）：
 *   ① 变更集为空；② 命中高扇出模块；③ `related` 一个测试文件都没选中。
 *   第 ③ 条无法在跑之前得知，故跑完读输出判（`No test files found` ⇒ 立即全量重跑）。
 */

import { spawnSync, execFileSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO = path.resolve(HERE, '..', '..')
const FRONTEND = path.join(REPO, 'frontend')
const VITEST = path.join(FRONTEND, 'node_modules', 'vitest', 'vitest.mjs')

// §4.2 末段：命中任一即放弃 related，直接全量。
const HIGH_FANOUT = [
  /^src\/locales\//,
  /^src\/api\/http\.ts$/,
  /^src\/types\//,
  /^src\/stores\/ai\.ts$/,
  /^src\/stores\/workspace\.ts$/,
]

const CODE = /\.(ts|tsx|js|mjs|vue)$/

const argv = process.argv.slice(2)
const baseIdx = argv.indexOf('--base')
const base = baseIdx >= 0 ? argv[baseIdx + 1] : null
const explicit = argv.filter((a, i) => !a.startsWith('--') && (baseIdx < 0 || i !== baseIdx + 1))
  .map((p) => p.replace(/^frontend\//, ''))

function git(args) {
  return execFileSync('git', args, {
    cwd: REPO, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'],
  })
}

/** 变更集（K1 口径：暂存 ∪ 未跟踪 ∪ 未暂存；后两者只放大不缩小）→ frontend 相对路径。 */
function changedFrontendFiles() {
  const from = base || git(['merge-base', 'origin/main', 'HEAD']).trim()
  const tracked = git(['diff', '--name-only', from, '--', 'frontend/'])
  const untracked = git(['ls-files', '--others', '--exclude-standard', '--', 'frontend/'])
  const files = new Set()
  for (const line of `${tracked}\n${untracked}`.split('\n')) {
    const p = line.trim()
    if (!p.startsWith('frontend/')) continue
    const rel = p.slice('frontend/'.length)
    if (!CODE.test(rel)) continue
    // related 收到的必须是磁盘上存在的模块；删除/改名后的旧路径传进去只会报错。
    if (!existsSync(path.join(FRONTEND, rel))) continue
    files.add(rel)
  }
  return [...files].sort()
}

/**
 * @param {string[]} args
 * @param {boolean} [capture] 捕获输出（需要读"零命中"判据时用），否则直通终端
 * @returns {{code:number, out:string, err:string}}
 */
function runVitest(args, capture = false) {
  const r = spawnSync(process.execPath, [VITEST, ...args], {
    cwd: FRONTEND,
    encoding: 'utf8',
    stdio: capture ? 'pipe' : 'inherit',
    maxBuffer: 64 * 1024 * 1024,
  })
  const out = r.stdout ?? ''
  const err = r.stderr ?? ''
  if (capture) {
    process.stdout.write(out)
    process.stderr.write(err)
  }
  return { code: r.status ?? 1, out, err }
}

function full(reason) {
  console.log(`[related] 转全量：${reason}`)
  return runVitest(['run']).code
}

function main() {
  if (!existsSync(VITEST)) {
    console.error(`[related] 找不到 vitest：${VITEST}（先在 frontend/ 下 yarn install）`)
    return 3
  }

  let files = explicit
  if (files.length === 0) {
    files = changedFrontendFiles()
  }
  if (files.length === 0) return full('变更集为空')

  const fanout = files.filter((f) => HIGH_FANOUT.some((re) => re.test(f)))
  if (fanout.length > 0) return full(`命中高扇出模块：${fanout.join(', ')}`)

  console.log(`[related] ${files.length} 个变更文件 → vitest related`)
  for (const f of files) console.log(`  ${f}`)

  const r = runVitest(['related', ...files, '--run'], true)
  // 无任何测试文件被选中（改的是没人 import 的模块）⇒ 按 §4.2 口径退回全量，不静默跑 0 个。
  if (/No test files found/i.test(`${r.out}\n${r.err}`)) return full('related 零命中')
  return r.code
}

process.exitCode = main()
