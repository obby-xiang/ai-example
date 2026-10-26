/**
 * 批次 7C：AI 正文渲染链路收口（`utils/markdown.ts`）。
 *
 * 四条产品口径（红测先行）：
 * ① 围栏稳定身份只由渲染器按出现序生成（`f{序号}-{语言}`）；**模型原生 HTML 不得携带/覆盖**
 *    `data-fence-key` 等槽位属性 —— 白名单拒绝该属性，净化后再由渲染器回填，模型写的
 *    `<pre data-fence-key>` / `<figure data-fence-key>` 既不能进产物，也不能挤掉真实围栏的序号，
 *    更不能把自己登记成 mermaid 槽位（FE-42）。
 * ② mermaid 水合两层门控：组件层「可见」（IntersectionObserver）＋ 渲染层「idle」
 *    （`requestIdleCallback`，超时 1s 兜底；无 rIC 环境退回 `setTimeout(0)`）。
 * ③ mermaid 的 3s `Promise.race` 只是**等待预算、不是中断**：同步渲染无法被抢占；超时后迟到的
 *    SVG 直接丢弃（不缓存、不回填），源码块保留，下一次仍会重试。
 * ④ >10000 字符按**条**降级：同一条消息只出一次提示，不逐帧重复，不粘滞到下一条。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  DEGRADE_AT_CHARS,
  highlightLanguages,
  renderMarkdown,
  resetDegradeNotices,
  resetStreamDebounce
} from '@/utils/markdown'

/** mermaid 动态 import 桩（真 mermaid 不在 jsdom 跑；`immediate` 用于 idle 用例，`manual` 用于超时用例）。 */
const mmd = vi.hoisted(() => ({
  renderCalls: 0,
  resolveRender: null as null | ((svg: string) => void),
  mode: 'immediate' as 'immediate' | 'manual'
}))

vi.mock('mermaid', () => ({
  default: {
    initialize: () => {},
    render: () => {
      mmd.renderCalls += 1
      if (mmd.mode === 'immediate') {
        return Promise.resolve({ svg: '<svg xmlns="http://www.w3.org/2000/svg"><rect x="1" y="1" width="2" height="2"></rect></svg>' })
      }
      return new Promise((resolve) => {
        mmd.resolveRender = (svg: string) => resolve({ svg })
      })
    }
  }
}))

beforeEach(() => {
  mmd.renderCalls = 0
  mmd.resolveRender = null
  mmd.mode = 'immediate'
  resetStreamDebounce()
  resetDegradeNotices()
})

describe('批次 7C：围栏稳定身份不可被模型覆盖', () => {
  /** 聚焦用例（唯一跑 RED/GREEN 的一条）。 */
  it('7C-1 围栏 key：模型原生 HTML 不携带/覆盖槽位属性，渲染器按出现序回填', () => {
    const doc = [
      // 模型伪造：自带 key + 内部 token、并抢在真实围栏之前登记成 mermaid 槽位
      '<pre data-fence-key="f9-model" data-fence-token="forged"><code class="language-mermaid">graph TD; M0-->M1;</code></pre>',
      '',
      '```js',
      'const a = 1',
      '```',
      '',
      // 模型伪造：图容器自带 key
      '<figure class="code" data-fence-key="f9-model"><figcaption><span class="lg">evil</span></figcaption><pre><code>const evil = 1</code></pre></figure>',
      '',
      '```mermaid',
      'graph TD; A-->B;',
      '```'
    ].join('\n')

    const result = renderMarkdown(doc, false)

    // ① 模型写的槽位属性一律不出现（白名单拒绝 + 净化后回填只认渲染器 token）
    expect(result.html).not.toContain('f9-model')
    expect(result.html).not.toContain('data-fence-token')

    // ② 渲染器按出现序回填：模型 pre 不占号，真实围栏从 f1 起
    const keys = [...result.html.matchAll(/data-fence-key="([^"]*)"/g)].map((m) => m[1])
    expect(keys).toEqual(['f1-js', 'f2-mermaid'])
    expect(new Set(keys).size).toBe(keys.length)

    // ③ 模型 pre 不获得任何槽位属性（它不是渲染器围栏）；净化器会把文本里的 `>` 序列化成实体
    const modelPre = /<pre([^>]*)><code class="language-mermaid">([\s\S]*?)<\/code><\/pre>/.exec(result.html)
    expect(modelPre).not.toBeNull()
    expect(modelPre![2]).toContain('M0')
    expect(modelPre![1]).not.toContain('data-fence-key')

    // ④ mermaid 登记只认渲染器围栏：模型那份不进清单，真围栏的 key 与产物逐字一致
    expect(result.mermaid).toHaveLength(1)
    expect(result.mermaid[0]!.key).toBe('f2-mermaid')
    expect(result.mermaid[0]!.source).toContain('A-->B')
    expect(result.mermaid[0]!.source).not.toContain('M0-->M1')
  })
})

describe('批次 7C：mermaid 可见且 idle 水合 / 超时语义', () => {
  it('7C-2 idle 门控：rIC 未回调前不 import、不渲染；回调后恰好渲染一次', async () => {
    vi.resetModules()
    const idleCallbacks: Array<() => void> = []
    vi.stubGlobal('requestIdleCallback', (cb: () => void) => {
      idleCallbacks.push(cb)
      return idleCallbacks.length
    })
    try {
      const markdown = await import('@/utils/markdown')
      const pending = markdown.renderMermaidSvg('graph TD; IDLE-->GATE;')
      // 等一轮微任务：没有 idle 回调 ⇒ 仍不应触碰 mermaid
      await Promise.resolve()
      expect(idleCallbacks).toHaveLength(1)
      expect(mmd.renderCalls).toBe(0)
      idleCallbacks[0]!()
      await expect(pending).resolves.toContain('<svg')
      expect(mmd.renderCalls).toBe(1)
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('7C-3 3s 超时只是等待预算：迟到结果不缓存、不回填，下次仍重试', async () => {
    vi.useFakeTimers()
    vi.resetModules()
    mmd.mode = 'manual'
    try {
      const markdown = await import('@/utils/markdown')
      const pending = markdown.renderMermaidSvg('graph TD; SLOW-->RENDER;')
      // 通过 idle 兜底（jsdom 无 rIC ⇒ setTimeout(0)），再让 mermaid 进入渲染
      await vi.advanceTimersByTimeAsync(0)
      await vi.advanceTimersByTimeAsync(0)
      expect(mmd.renderCalls).toBe(1)
      // 3s 到点：只释放等待方（返回 null），**不能**宣称中断了正在跑的同步渲染
      await vi.advanceTimersByTimeAsync(markdown.MERMAID_TIMEOUT_MS)
      await expect(pending).resolves.toBeNull()
      // 迟到的 SVG 到了：不缓存、也不回填任何槽位
      mmd.resolveRender?.('<svg xmlns="http://www.w3.org/2000/svg"><rect x="1" y="1" width="2" height="2"></rect></svg>')
      await vi.advanceTimersByTimeAsync(0)
      // 失败/超时不入缓存 ⇒ 下一次仍会重新 render（而不是复用迟到产物）
      mmd.mode = 'immediate'
      const retried = markdown.renderMermaidSvg('graph TD; SLOW-->RENDER;')
      await vi.advanceTimersByTimeAsync(0)
      await expect(retried).resolves.toContain('<svg')
      expect(mmd.renderCalls).toBe(2)
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('批次 7C：>10000 字符按条降级提示', () => {
  it('7C-4 同一条消息只提示一次（不逐帧）；下一条不粘滞、下次仍可高亮', () => {
    const long = '# 标题\n\n' + 'x'.repeat(DEGRADE_AT_CHARS + 1)
    expect(long.length).toBeGreaterThan(DEGRADE_AT_CHARS)

    const first = renderMarkdown(long, true, 'msg-1:0')
    expect(first.degraded).toBe(true)
    expect(first.degradeNotice).toBeTruthy()

    // 同一消息的后续帧（换段 / 继续增长）：不再出提示
    resetStreamDebounce()
    const second = renderMarkdown(long + 'y', true, 'msg-1:0')
    expect(second.degraded).toBe(true)
    expect(second.degradeNotice).toBeNull()
    resetStreamDebounce()
    const otherSeg = renderMarkdown(long, true, 'msg-1:1')
    expect(otherSeg.degradeNotice).toBeNull()

    // 另一条长消息：提示重新出现（按条记账，不是全局一次性）
    const nextLong = renderMarkdown(long, false, 'msg-2:0')
    expect(nextLong.degradeNotice).toBeTruthy()

    // 不粘滞：下一条短消息正常高亮、无提示
    const short = renderMarkdown('```js\nconst a = 1\n```', false, 'msg-3:0')
    expect(short.degraded).toBe(false)
    expect(short.degradeNotice).toBeNull()
    expect(short.html).toContain('hljs-')
  })
})

describe('批次 7C：高亮语言白名单（文档口径锁）', () => {
  it('7C-5 实际注册 15 种语言（与 docs/03 §7.5 列表逐字一致）', () => {
    expect(highlightLanguages().sort()).toEqual(
      [
        'javascript',
        'typescript',
        'java',
        'python',
        'sql',
        'json',
        'bash',
        'yaml',
        'xml',
        'css',
        'markdown',
        'diff',
        'go',
        'csharp',
        'kotlin'
      ].sort()
    )
  })
})
