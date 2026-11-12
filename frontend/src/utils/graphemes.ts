export interface GraphemePrefix {
  /** 完整字素组成的前缀；首个字素超预算时为空串。 */
  text: string
  /** 下一个未消费字素的 UTF-16 code unit 起点；被省略的超长字素也计入。 */
  nextOffset: number
  /** 是否因预算省略了输入内容；不会静默吞掉超长单字素。 */
  omitted: boolean
}

let segmenter: Intl.Segmenter | undefined

function segmenterOf(): Intl.Segmenter {
  segmenter ??= new Intl.Segmenter(undefined, { granularity: 'grapheme' })
  return segmenter
}

/** 按 UTF-16 code unit 预算取完整字素前缀；offset 仍按 code unit 计。 */
export function takeGraphemePrefix(text: string, maxCodeUnits: number): GraphemePrefix {
  if (!Number.isInteger(maxCodeUnits) || maxCodeUnits < 0) {
    throw new RangeError('maxCodeUnits must be a non-negative integer')
  }
  if (text.length <= maxCodeUnits) {
    return { text, nextOffset: text.length, omitted: false }
  }
  let prefixEnd = 0
  for (const item of segmenterOf().segment(text)) {
    const end = item.index + item.segment.length
    if (end > maxCodeUnits) {
      return { text: text.slice(0, prefixEnd), nextOffset: prefixEnd === 0 ? end : item.index, omitted: true }
    }
    prefixEnd = end
  }
  return { text, nextOffset: text.length, omitted: false }
}

/** 按同一预算切块；超长单字素以空文本 + omitted=true 明确标出，后续块继续。 */
export function chunkGraphemes(text: string, maxCodeUnits: number): GraphemePrefix[] {
  const chunks: GraphemePrefix[] = []
  let offset = 0
  while (offset < text.length) {
    const next = takeGraphemePrefix(text.slice(offset), maxCodeUnits)
    chunks.push({
      text: next.text,
      nextOffset: offset + next.nextOffset,
      omitted: next.omitted
    })
    offset += next.nextOffset
  }
  return chunks
}
