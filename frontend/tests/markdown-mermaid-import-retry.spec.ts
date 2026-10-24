/**
 * L-127：mermaid 动态 import 失败后的重试回归锁。
 *
 * 纪律（`loadMermaid` 的 catch 分支，与 spreadjs.ts 同源）：失败 Promise **不得**被永久缓存 ——
 * 一次 import 抖动/失败只影响当次调用，下一次调用必须重新走 import 工厂，而不是把一次失败
 * 钉死成「mermaid 不可用」。本用例钉住四件事：
 * ① 同一唯一图源第一次 import reject ⇒ `renderMermaidSvg` 返回 null（失败兜底，不抛）；
 * ② 同一图源第二次调用 ⇒ 重新 import 成功 ⇒ 返回**净化后** SVG（脚本被 SVG profile 剥掉）；
 * ③ import 工厂恰好被调用 2 次（失败那次未驻留，成功那次也未重复 import）；
 * ④ 缓存不串扰：失败不入缓存、成功按图源各自命中，重试后两图源产物不互相覆盖。
 *
 * 桩位：`vi.mock('mermaid')` 的工厂按「第几次 import」决定 reject / 返回假模块；工厂失败的
 * 结果不被 Vitest 缓存（ManualMockedModule 只在 resolve 成功时写 cache）⇒ 复用产品重试语义。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

/** import 工厂与渲染桩的记账（vi.mock 提升到文件顶部 ⇒ 只能经 vi.hoisted 访问）。 */
const mmd = vi.hoisted(() => ({
  importAttempts: 0,
  failAttempts: new Set<number>(),
  renderCalls: 0,
  lastRawSvg: '',
  initConfigs: [] as Array<Record<string, unknown>>
}))

vi.mock('mermaid', () => {
  mmd.importAttempts += 1
  const attempt = mmd.importAttempts
  if (mmd.failAttempts.has(attempt)) {
    // 动态 import 失败（chunk 404 / 网络抖动同构）：工厂回 rejected promise
    return Promise.reject(new Error(`mermaid import failed (attempt ${attempt})`))
  }
  return {
    default: {
      initialize: (config: Record<string, unknown>) => {
        mmd.initConfigs.push(config)
      },
      render: () => {
        mmd.renderCalls += 1
        // 故意混入 <script>：净化必须在渲染出口生效（USE_PROFILES: svg）
        mmd.lastRawSvg =
          '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">' +
          `<g id="mmd-${mmd.renderCalls}"><rect x="1" y="1" width="8" height="8"></rect><text>mmd-${mmd.renderCalls}</text></g>` +
          '<script>window.__mmd_xss = 1</script></svg>'
        return Promise.resolve({ svg: mmd.lastRawSvg })
      }
    }
  }
})

/** 每个用例拿一份全新的 markdown 模块实例（模块级 `mermaidPromise` 归零），模拟「冷启动」。 */
async function freshMarkdown() {
  vi.resetModules()
  return import('@/utils/markdown')
}

beforeEach(() => {
  mmd.importAttempts = 0
  mmd.failAttempts.clear()
  mmd.renderCalls = 0
  mmd.lastRawSvg = ''
  mmd.initConfigs = []
})

describe('L-127：mermaid 动态 import 失败不永久缓存', () => {
  it('同一唯一图源：第一次 import reject 得 null，第二次成功得净化 SVG，import 工厂恰好 2 次', async () => {
    const markdown = await freshMarkdown()
    const source = 'graph TD;\n  L127_UNIQUE_SRC --> RETRY_OK;'
    mmd.failAttempts.add(1)

    const first = await markdown.renderMermaidSvg(source)
    expect(first).toBeNull()
    expect(mmd.importAttempts).toBe(1)

    const second = await markdown.renderMermaidSvg(source)
    expect(second).not.toBeNull()
    expect(second).toContain('<svg')
    expect(second).toContain('mmd-1')
    // 净化在渲染出口生效：脚本与脚本副作用文本均被剥掉
    expect(second).not.toContain('<script')
    expect(second).not.toContain('__mmd_xss')
    expect(second).toBe(markdown.sanitizeSvg(mmd.lastRawSvg))
    expect(mmd.importAttempts).toBe(2)
    // 重试成功后走的是同一条 initialize 配置（securityLevel:'strict' 等硬口径不变）
    expect(mmd.initConfigs).toHaveLength(1)
    expect(mmd.initConfigs[0]).toMatchObject({ securityLevel: 'strict', htmlLabels: false })

    // 成功结果按图源缓存：第三次不再触发 import 工厂、不再重复 render
    expect(await markdown.renderMermaidSvg(source)).toBe(second)
    expect(mmd.importAttempts).toBe(2)
    expect(mmd.renderCalls).toBe(1)
  })

  it('缓存不串扰：成功产物按图源各自命中，两图源不互相覆盖', async () => {
    const markdown = await freshMarkdown()
    const srcA = 'graph TD;\n  L127_CACHE_A --> A2;'
    const srcB = 'graph LR;\n  L127_CACHE_B --> B2;'

    const svgA = await markdown.renderMermaidSvg(srcA)
    const svgB = await markdown.renderMermaidSvg(srcB)
    expect(svgA).not.toBeNull()
    expect(svgB).not.toBeNull()
    expect(svgA).not.toBe(svgB)
    expect(svgA).toContain('<g id="mmd-1"')
    expect(svgB).toContain('<g id="mmd-2"')

    // 两个图源各自命中自家产物：既不复用对方缓存，也不把对方覆盖掉
    expect(await markdown.renderMermaidSvg(srcA)).toBe(svgA)
    expect(await markdown.renderMermaidSvg(srcB)).toBe(svgB)
    // 命中缓存 = 不再重复 render（render 只发生过两次）
    expect(mmd.renderCalls).toBe(2)
  })
})
