// 构建包装：本机杀毒软件会锁定 esbuild 写在系统 %TEMP% 下的临时文件导致构建失败，
// 这里把 TMP/TEMP 指到项目内目录（.tmp-esbuild 已在 .gitignore 中排除）再调 vite build。
// R-153 卡④：wrapper 为唯一构建入口（Windows 禁裸 `npx vite build`，见 docs/03-技术方案文档-v2.1.md）。
import { mkdirSync, rmSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const tmpDir = join(dirname(fileURLToPath(import.meta.url)), '..', '.tmp-esbuild')

async function buildOnce() {
  mkdirSync(tmpDir, { recursive: true })
  process.env.TMP = tmpDir
  process.env.TEMP = tmpDir
  process.env.TMPDIR = tmpDir
  const { build } = await import('vite')
  await build()
}

try {
  await buildOnce()
} catch (first) {
  // R-153 卡④：杀毒软件偶发锁 esbuild 临时文件导致首次失败 —— 清 .tmp-esbuild 后**重试恰好一次**。
  // 重试仍失败则原样上抛：保持非零退出码，绝不静默吞错。
  console.warn('[build.mjs] 首次构建失败，清 .tmp-esbuild 后重试一次：', first)
  rmSync(tmpDir, { recursive: true, force: true })
  await buildOnce()
}
