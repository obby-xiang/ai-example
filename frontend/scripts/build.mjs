// Windows 构建包装：杀毒软件可能锁定 %TEMP% 下 esbuild 临时文件导致构建失败，
// 将 TMP/TEMP 指到项目内目录再执行 vite build（借鉴参考项目实践）。
import { build } from 'vite'
import { fileURLToPath, URL } from 'node:url'
import { mkdirSync } from 'node:fs'
import path from 'node:path'

const projectRoot = path.dirname(fileURLToPath(new URL('..', import.meta.url)))
const tmpDir = path.join(projectRoot, '.tmp-build')
mkdirSync(tmpDir, { recursive: true })
process.env.TMP = tmpDir
process.env.TEMP = tmpDir
process.env.TMPDIR = tmpDir

await build()
