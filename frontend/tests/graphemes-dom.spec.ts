import { beforeEach, describe, expect, it } from 'vitest'
import { aiSnapshot, collectRegionText, uploadFileNameOf } from '@/utils/ai-dom-tools'
import { listAiRegions } from '@/utils/ai-regions'
import { beginUiTurn } from '@/utils/ai-dom-tools'
import { enterImportPage, makeRoot, registerRegion, resetAiTestState } from './ai-fixtures'

const FAMILY = '👨‍👩‍👧‍👦'

beforeEach(() => {
  resetAiTestState()
  beginUiTurn('graphemes')
  enterImportPage()
})

describe('grapheme-safe DOM text', () => {
  it('truncates labels without cutting the family sequence', () => {
    registerRegion(makeRoot(`<button data-ai-ref="label" data-ai-label="ab${FAMILY.repeat(30)}">x</button>`))

    const result = aiSnapshot({}).result

    expect(result).toContain(`"ab${FAMILY}${FAMILY}…"`)
    expect(result).not.toContain(`"ab${FAMILY}${FAMILY}${FAMILY}`)
  })

  it('truncates control rows without cutting the family sequence', () => {
    const ref = 'r'.repeat(85)
    registerRegion(makeRoot(`<button data-ai-ref="${ref}" data-ai-label="ab${FAMILY.repeat(30)}">x</button>`))

    const result = aiSnapshot({}).result

    expect(result).toContain('"ab…"')
    expect(result).not.toContain(FAMILY)
  })

  it('chunks region text at grapheme boundaries', () => {
    const text = `${'a'.repeat(119)}${FAMILY}`
    registerRegion(makeRoot(`<p>${text}</p>`))

    const section = collectRegionText(listAiRegions())

    expect(section.lines).toEqual(['a'.repeat(119), FAMILY])
  })

  it('truncates upload filenames without cutting the family sequence', () => {
    const name = `${'a'.repeat(57)}${FAMILY}.xlsx`

    const result = uploadFileNameOf(new File(['x'], name))

    expect(result).toBe(`${'a'.repeat(57)}….xlsx`)
    expect(result.length).toBeLessThanOrEqual(64)
  })
})
