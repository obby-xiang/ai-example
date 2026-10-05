// 构建包装：本机杀毒软件会锁定 esbuild 写在系统 %TEMP% 下的临时文件导致构建失败，
// 这里把 TMP/TEMP 指到项目内目录再调 vite build。
import { mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const tmpDir = join(dirname(fileURLToPath(import.meta.url)), '..', '.tmp-build')
mkdirSync(tmpDir, { recursive: true })
process.env.TMP = tmpDir
process.env.TEMP = tmpDir
process.env.TMPDIR = tmpDir

const { build } = await import('vite')
await build()
