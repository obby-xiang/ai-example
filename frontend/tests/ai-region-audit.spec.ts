/**
 * visop 批 B · 标注治理审计（设计稿 §2.2 批 B：D1 注册面扩面 + D7 标注治理）。
 *
 * 两条互相独立的审计线：
 * 1. **源码级**：扫 `src/**` 里的 `registerAiRegion({...})` 与 `data-ai-ref="..."`，
 *    对账「每个 ref 有归属区域 / 每个注册区域有 ref / risk 必须显式」（B-03/B-04/B-05）；
 * 2. **运行期**：`auditAiRegions()` 对已注册区域做结构审计（root 未挂载 / 空区域 /
 *    ref 重复 / 缺可访问名 / 元素风险低于区域），DEV 期 `warnAiRegionAudit()` 只 console.warn
 *    且同一条只报一次（B-06）。
 *
 * 为什么源码级也要留一份：运行期审计只能看见「已挂载并且已注册」的面，
 * **漏标（压根没写 data-ai-ref）在运行期永远看不见** —— 那正是标注治理要防的主要缺陷。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import {
  auditAiRegions,
  registerAiRegion,
  warnAiRegionAudit
} from '@/utils/ai-regions'
import { aiSnapshot } from '@/utils/ai-dom-tools'
import { enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

const SRC_ROOT = join(process.cwd(), 'src')

/**
 * ref 由组件 prop 前缀拼出来的面（源码里没有字面量，扫不到）。
 *
 * 这里登记的是**契约**：谁在拼前缀、允许拼给哪些区域。视图侧必须把区域 id 原样传进来
 * （`ai-ref-prefix="<regionId>"`），由下面的 `每个动态前缀都有视图侧接线` 用例锁死。
 */
const DYNAMIC_REF_PREFIXES = [
  { file: 'components/wizard/DefSelector.vue', regions: ['import.selectDefs', 'export.selectDefs'] },
  { file: 'components/wizard/ConditionForm.vue', regions: ['export.cond'] }
] as const

function walk(dir: string): string[] {
  const out: string[] = []
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) {
      out.push(...walk(full))
    } else if (name.endsWith('.vue') || name.endsWith('.ts')) {
      out.push(full)
    }
  }
  return out
}

const SOURCES = walk(SRC_ROOT).map((path) => ({
  path: relative(SRC_ROOT, path).split('\\').join('/'),
  text: readFileSync(path, 'utf8')
}))

/** 取 `registerAiRegion({...})` 的实参块（花括号配平，容忍多行与回调）。 */
function regionBlocks(text: string): string[] {
  const blocks: string[] = []
  const marker = 'registerAiRegion({'
  let from = 0
  for (;;) {
    const at = text.indexOf(marker, from)
    if (at < 0) {
      return blocks
    }
    const start = text.indexOf('{', at)
    let depth = 0
    let end = start
    for (; end < text.length; end += 1) {
      const ch = text[end]
      if (ch === '{') {
        depth += 1
      } else if (ch === '}') {
        depth -= 1
        if (depth === 0) {
          break
        }
      }
    }
    blocks.push(text.slice(start, end + 1))
    from = end + 1
  }
}

const REGION_CALLS = SOURCES.flatMap((file) =>
  regionBlocks(file.text).map((block) => ({ file: file.path, block }))
)

function idOf(block: string): string | null {
  return /id:\s*'([^']+)'/.exec(block)?.[1] ?? null
}

function riskOf(block: string): string | null {
  return /risk:\s*'([^']+)'/.exec(block)?.[1] ?? null
}

const REGISTERED_IDS = REGION_CALLS.map((call) => idOf(call.block)).filter((id): id is string => id !== null)

/** 源码里的字面量 ref（只认形如 `page.region.name` 的语义键）。 */
const STATIC_REFS = SOURCES.flatMap((file) =>
  [...file.text.matchAll(/data-ai-ref="([^"]+)"/g)]
    .map((m) => m[1] ?? '')
    .filter((ref) => /^[a-z][a-z0-9-]*(\.[A-Za-z0-9_-]+)+$/.test(ref))
    .map((ref) => ({ ref, file: file.path }))
)

/**
 * 模板里直接拼的 ref 前缀（`:data-ai-ref="`import.grid.tab.${def.code}`"`）——
 * 取插值前的字面前缀（`import.grid.tab`），同样要能归到某个区域。
 */
const INLINE_DYNAMIC_PREFIXES = SOURCES.flatMap((file) =>
  [...file.text.matchAll(/:\s*data-ai-ref="`([^`]+)`"/g)]
    .map((m) => (m[1] ?? '').split('${')[0]?.replace(/\.$/, '') ?? '')
    .filter((prefix) => prefix !== '')
    .map((prefix) => ({ prefix, file: file.path }))
)

/** `ai-ref-prefix` 的视图侧接线（静态值或模板字面量前缀）。 */
const PREFIX_WIRING = SOURCES.flatMap((file) =>
  [...file.text.matchAll(/ai-ref-prefix="([^"]+)"/g)]
    .map((m) => (m[1] ?? '').replace(/^`/, '').split('${')[0]?.replace(/\.$/, '') ?? '')
    .filter((value) => value !== '')
    .map((value) => ({ value, file: file.path }))
)

/** ref 归属区域 = 与 ref 前缀匹配（逐段）的那个已注册区域 id。 */
function regionOf(ref: string): string | null {
  const candidates = REGISTERED_IDS.filter((id) => ref === id || ref.startsWith(`${id}.`))
  if (candidates.length === 0) {
    return null
  }
  return candidates.sort((a, b) => b.length - a.length)[0] ?? null
}

beforeEach(() => {
  resetAiTestState()
})

describe('范围守卫：O-2 裁决明确不扩的页面不得出现区域注册', () => {
  it('任务中心（P2 另行）/ 配置定义 / 数据浏览 三个视图零 registerAiRegion', () => {
    for (const page of ['views/TaskCenterView.vue', 'views/DefinitionsView.vue', 'views/DataBrowserView.vue']) {
      const file = SOURCES.find((item) => item.path === page)
      expect(file, `${page} 不存在`).toBeTruthy()
      expect(file?.text.includes('registerAiRegion'), `${page} 不应注册区域（不在本批范围）`).toBe(false)
    }
  })
})

describe('B-03：注册即授权 —— 每个区域都必须显式声明 risk', () => {  it('源码里每个 registerAiRegion 调用块都有 risk 字面量', () => {
    expect(REGION_CALLS.length).toBeGreaterThanOrEqual(6)
    const missing = REGION_CALLS.filter((call) => riskOf(call.block) === null)
    expect(missing.map((call) => `${call.file}#${idOf(call.block) ?? '?'}`)).toEqual([])
  })

  it('运行期缺 risk 仍按 fail-fast 抛错（既有行为的回归锁）', () => {
    const root = makeRoot('<button data-ai-ref="import.upload.choose">上传</button>')
    expect(() => registerAiRegion({ id: 'import.selectDefs', root } as never)).toThrowError(/risk/)
  })

  it('本批五个新区域全部落地（导入 3 + 导出 2）', () => {
    expect(new Set(REGISTERED_IDS).size).toBe(REGISTERED_IDS.length)
    expect(REGISTERED_IDS).toEqual(
      expect.arrayContaining(['import.selectDefs', 'import.mode', 'import.grid', 'export.selectDefs', 'export.cond'])
    )
  })
})

describe('B-04：每个 ref 都有归属区域（漏标/错标即红）', () => {
  it('源码里的字面量 ref 全部能归到某个已注册区域', () => {
    const orphans = STATIC_REFS.filter((item) => regionOf(item.ref) === null)
    expect(orphans.map((item) => `${item.ref}（${item.file}）`)).toEqual([])
  })

  it('模板里拼出来的 ref 前缀同样要归到已注册区域', () => {
    expect(INLINE_DYNAMIC_PREFIXES.length).toBeGreaterThan(0)
    const orphans = INLINE_DYNAMIC_PREFIXES.filter((item) => regionOf(item.prefix) === null)
    expect(orphans.map((item) => `${item.prefix}（${item.file}）`)).toEqual([])
  })

  it('ref 是语义键，不是可见文案 / 索引（禁中文、禁纯数字段）', () => {
    const bad = STATIC_REFS.filter((item) => !/^[a-z][a-z0-9-]*(\.[A-Za-z0-9_-]+)+$/.test(item.ref)
      || /\.[0-9]+(\.|$)/.test(item.ref))
    expect(bad.map((item) => `${item.ref}（${item.file}）`)).toEqual([])
  })

  it('动态前缀的每一段归属区域都有视图侧接线（ai-ref-prefix 原样传区域 id）', () => {
    for (const entry of DYNAMIC_REF_PREFIXES) {
      for (const regionId of entry.regions) {
        expect(
          PREFIX_WIRING.some((item) => item.value === regionId || item.value.startsWith(`${regionId}.`)),
          `缺少视图侧接线：${entry.file} 的前缀 ${regionId} 没有任何 ai-ref-prefix 传入`
        ).toBe(true)
      }
    }
  })
})

describe('B-05：每个注册区域都有 ref（空注册即红）', () => {
  it('每个区域 id 都能在源码里找到字面量 ref / 模板前缀 / 动态前缀登记', () => {
    const covered = new Set<string>()
    for (const item of STATIC_REFS) {
      const region = regionOf(item.ref)
      if (region) {
        covered.add(region)
      }
    }
    for (const item of INLINE_DYNAMIC_PREFIXES) {
      const region = regionOf(item.prefix)
      if (region) {
        covered.add(region)
      }
    }
    for (const entry of DYNAMIC_REF_PREFIXES) {
      entry.regions.forEach((id) => covered.add(id))
    }
    const uncovered = REGISTERED_IDS.filter((id) => !covered.has(id))
    expect(uncovered).toEqual([])
  })
})

describe('B-06：运行期结构审计（root / 空区域 / ref 重复 / 缺可访问名 / 风险降级）', () => {
  it('健康区域零告警', () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.choose" data-ai-label="上传">上传</button>'))
    expect(auditAiRegions()).toEqual([])
  })

  it('空区域报 region-empty', () => {
    enterImportPage()
    registerRegion(makeRoot('<div>没有标注</div>'), { id: 'import.selectDefs', risk: 'mutate-local' })
    const issues = auditAiRegions()
    expect(issues.map((issue) => issue.code)).toContain('region-empty')
    expect(issues.find((issue) => issue.code === 'region-empty')?.regionId).toBe('import.selectDefs')
  })

  it('同一 ref 出现在两个区域报 ref-duplicate（只报一次）', () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.selectDefs.CURRENCY">币种</button>'), { id: 'import.selectDefs', risk: 'mutate-local' })
    registerRegion(makeRoot('<button data-ai-ref="import.selectDefs.CURRENCY">币种</button>'), { id: 'import.mode', risk: 'mutate-local' })
    const dupes = auditAiRegions().filter((issue) => issue.code === 'ref-duplicate')
    expect(dupes).toHaveLength(1)
    expect(dupes[0]?.ref).toBe('import.selectDefs.CURRENCY')
  })

  it('缺可访问名（无 label / aria-label / title / 文案）报 missing-name', () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.empty"></button>'))
    expect(auditAiRegions().map((issue) => issue.code)).toContain('missing-name')
  })

  it('元素显式声明低于区域风险时报 risk-below-region（降级必须是有意为之）', () => {
    enterImportPage()
    registerRegion(makeRoot('<button data-ai-ref="import.upload.x" data-ai-risk="read">只读</button>'), { risk: 'consequential' })
    const issue = auditAiRegions().find((item) => item.code === 'risk-below-region')
    expect(issue?.ref).toBe('import.upload.x')
  })

  it('root 被摘除后报 root-detached（区域残留 = 脏 ref 的源头）', () => {
    enterImportPage()
    const root = makeRoot('<button data-ai-ref="import.upload.choose">上传</button>')
    registerRegion(root)
    root.remove()
    expect(auditAiRegions().map((issue) => issue.code)).toContain('root-detached')
  })

  it('DEV 告警同一条只报一次（快照连调两次不刷屏）', () => {
    enterImportPage()
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    try {
      registerRegion(makeRoot('<button data-ai-ref="import.upload.empty"></button>'))
      aiSnapshot({})
      aiSnapshot({})
      expect(warn).toHaveBeenCalledTimes(1)
      expect(String(warn.mock.calls[0]?.[0] ?? '')).toContain('missing-name')
    } finally {
      warn.mockRestore()
    }
  })

  it('告警只走 console.warn，不改回执语义（快照仍 ok）', () => {
    enterImportPage()
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    try {
      registerRegion(makeRoot('<button data-ai-ref="import.upload.empty"></button>'))
      const out = aiSnapshot({})
      expect(out.outcome).toBe('ok')
      expect(out.result).toContain('import.upload.empty')
      expect(warnAiRegionAudit().length).toBeGreaterThan(0)
    } finally {
      warn.mockRestore()
    }
  })
})
