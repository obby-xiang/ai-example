/**
 * SSE 解析器与帧边界（测试扩充 V3）：C1 解析器分支 / C2 帧序异常 / C3 未知帧前向兼容 / C12 畸形输入。
 *
 * <p>缺口的来源：`parseSseFrame` 早已导出但**零 spec**，`SseChunkParser` 此前未导出、无单测；
 * 乱码、分片、多字节边界这几类最便宜的高价值分支只被整条 `streamChat` 间接覆盖。
 *
 * <p>分层：
 * - 纯解析层直接驱动 `SseChunkParser` / `parseSseFrame`（逐分支、不经过网络）；
 * - 集成位经 `streamChat`（fetch mock + ReadableStream）验证读循环全链：终帧闸门的
 *   `parser.flush()` 残余路径、乱序帧在 store 的落点、异 runId 帧不串轮。
 *
 * <p>与批1 D1/D2 的分界：批1 已锁「同一 TCP 块里 done 之后仍推帧」与停止轮写侧闸门
 * （`aie2e-stream.spec.ts` / `aie2e-stop.spec.ts`）；本文件只补**不同的到达路径**与**解析器内部**，
 * 不改写既有断言。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SseChunkParser, parseSseFrame, streamChat } from '@/api/sse'
import type { SseStreamOptions } from '@/api/sse'
import { frameSeq, type SseFrame } from '@/types/sse'
import { frame, freshStore, textMessage } from './aie2e-helpers'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  delete (globalThis as unknown as { EventSource?: unknown }).EventSource
  FakeEventSource.urls = []
})

const encode = (text: string): Uint8Array => new TextEncoder().encode(text)

type Chunk = string | Uint8Array

function toBytes(chunks: Chunk[]): Uint8Array[] {
  return chunks.map((chunk) => (typeof chunk === 'string' ? encode(chunk) : chunk))
}

function byteStream(chunks: Chunk[]): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of toBytes(chunks)) {
        controller.enqueue(chunk)
      }
      controller.close()
    }
  })
}

function mockBody(chunks: Chunk[]): void {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: byteStream(chunks) }))
}

function collect(chunks: string[]): string[] {
  const parser = new SseChunkParser()
  const payloads: string[] = []
  for (const chunk of chunks) {
    payloads.push(...parser.push(chunk))
  }
  return payloads
}

interface SeenFrame {
  type: string
  text?: string
  runId?: string
  seq?: number
}

async function runSse(
  chunks: Chunk[],
  options: SseStreamOptions = {}
): Promise<{ seen: SeenFrame[]; info: Awaited<ReturnType<typeof streamChat>> }> {
  mockBody(chunks)
  const seen: SeenFrame[] = []
  const info = await streamChat(
    { sessionId: 's-v3', message: 'q' } as never,
    { onFrame: (incoming: SseFrame) => seen.push(incoming as unknown as SeenFrame) },
    options
  )
  return { seen, info }
}

function sseLine(payload: unknown): string {
  return `data: ${JSON.stringify(payload)}\n\n`
}

class FakeEventSource {
  static urls: string[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onerror: (() => void) | null = null
  constructor(url: string) {
    FakeEventSource.urls.push(url)
  }
  close(): void {}
}

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0))

// ── C1：解析器分支全覆盖 ─────────────────────────────────────────────────────

describe('C1 解析器分支覆盖（SseChunkParser / parseSseFrame）', () => {
  it('C1-01 一帧跨多块：JSON 被切在任意位置，只在收齐 \\n\\n 后产出一次', () => {
    const payloads = collect([
      'da',
      'ta: {"type":"del',
      'ta","seq":1,"text":"hi"}\n',
      '\n'
    ])
    expect(payloads).toEqual(['{"type":"delta","seq":1,"text":"hi"}'])
    expect(parseSseFrame(payloads[0] ?? '')).toMatchObject({ type: 'delta', text: 'hi' })
  })

  it('C1-02 一块多帧：单次 push 产出三个载荷且保持到达顺序', () => {
    const payloads = collect([
      sseLine({ type: 'delta', seq: 1, text: 'A' })
        + sseLine({ type: 'delta', seq: 2, text: 'B' })
        + sseLine({ type: 'done', seq: 3, cancelled: false })
    ])
    expect(payloads).toHaveLength(3)
    expect(payloads.map((item) => (JSON.parse(item) as { text?: string }).text ?? 'done')).toEqual([
      'A', 'B', 'done'
    ])
  })

  it('C1-03 CRLF 归一：\\r\\n\\r\\n 分隔被识别为一个事件', () => {
    const payloads = collect(['data: {"type":"delta","text":"crlf"}\r\n\r\n'])
    expect(payloads).toEqual(['{"type":"delta","text":"crlf"}'])
  })

  it('C1-04 仅 CR 归一：\\r\\r 分隔同样被识别', () => {
    const payloads = collect(['data: {"type":"delta","text":"cr"}\r\r'])
    expect(payloads).toEqual(['{"type":"delta","text":"cr"}'])
  })

  it('C1-05 CRLF 跨块：\\r 与 \\n 分属两块时不得产生伪帧边界（多行 data 仍拼接）', () => {
    const payloads = collect([
      'data: {"type":"delta",\r',
      '\ndata: "text":"跨块"}\r\n\r\n'
    ])
    expect(payloads).toHaveLength(1)
    expect(parseSseFrame(payloads[0] ?? '')).toMatchObject({ type: 'delta', text: '跨块' })
  })

  it('C1-06 多行 data：以 \\n 拼接后再解析', () => {
    const payloads = collect([
      'data: {"type":"delta",\ndata: "seq":2,\ndata: "text":"多行"}\n\n'
    ])
    expect(payloads).toEqual(['{"type":"delta",\n"seq":2,\n"text":"多行"}'])
    expect(parseSseFrame(payloads[0] ?? '')).toMatchObject({ type: 'delta', text: '多行' })
  })

  it('C1-07 data 后仅剥离一个可选空格', () => {
    expect(collect(['data:x\n\n'])).toEqual(['x'])
    expect(collect(['data: x\n\n'])).toEqual(['x'])
    expect(collect(['data:  x\n\n'])).toEqual([' x'])
    expect(collect(['data:\tx\n\n'])).toEqual(['\tx'])
  })

  it('C1-08 注释行（: 开头）忽略：注释块不产载荷，同块内 data 不受影响', () => {
    expect(collect([': keep-alive\n\n'])).toEqual([])
    const payloads = collect(['data: {"type":"delta","text":"a"}\n: 注释\n\n'])
    expect(payloads).toEqual(['{"type":"delta","text":"a"}'])
    expect(parseSseFrame(payloads[0] ?? '')).toMatchObject({ type: 'delta', text: 'a' })
  })

  it('C1-09 帧分隔 \\n\\n 跨块：前块末 \\n + 后块首 \\n 才成帧', () => {
    const parser = new SseChunkParser()
    expect(parser.push('data: {"type":"delta","text":"x"}\n')).toEqual([])
    expect(parser.push('\n')).toEqual(['{"type":"delta","text":"x"}'])
  })

  it('C1-10 流尾 flush：无结尾 \\n\\n 的残余块产出；空白残余与二次 flush 均为空', () => {
    const parser = new SseChunkParser()
    expect(parser.push('data: {"type":"done","seq":9,"cancelled":false}')).toEqual([])
    const flushed = parser.flush()
    expect(flushed).toHaveLength(1)
    expect(parseSseFrame(flushed[0] ?? '')).toMatchObject({ type: 'done', seq: 9 })
    expect(parser.flush()).toEqual([])

    const blank = new SseChunkParser()
    blank.push('   \n\n')
    expect(blank.flush()).toEqual([])
  })

  it('C1-11 BOM：首块的 U+FEFF 不得吃掉首帧（data 行前的 BOM 与 data 值前的 BOM 都要容忍）', () => {
    const payloads = collect(['\ufeffdata: {"type":"start","runId":"run-bom","seq":1}\n\n'])
    expect(payloads).toHaveLength(1)
    expect(parseSseFrame(payloads[0] ?? '')).toMatchObject({ type: 'start', runId: 'run-bom' })

    expect(parseSseFrame('\ufeff{"type":"delta","text":"bom"}')).toMatchObject({
      type: 'delta',
      text: 'bom'
    })
  })

  it('C1-12 非 JSON data：返回 null 不抛异常', () => {
    expect(parseSseFrame('not-json')).toBeNull()
    expect(parseSseFrame('{broken')).toBeNull()
    expect(parseSseFrame('42')).toBeNull()
    expect(parseSseFrame('null')).toBeNull()
    expect(parseSseFrame('[]')).toBeNull()
    expect(parseSseFrame('{"text":"missing type"}')).toBeNull()
  })

  it('C1-13 白名单外 type：解析器返回 null（不透传伪帧）', () => {
    expect(parseSseFrame('{"type":"tool_call","toolCallId":"c1"}')).toBeNull()
    expect(parseSseFrame('{"type":"future_frame","payload":{}}')).toBeNull()
    expect(parseSseFrame('{"type":"","seq":1}')).toBeNull()
  })

  it('C1-14 event / id / retry 字段被忽略，只有 data 成为载荷', () => {
    const payloads = collect([
      'event: delta\nid: 7\nretry: 3000\ndata: {"type":"delta","text":"fields"}\n\n'
    ])
    expect(payloads).toEqual(['{"type":"delta","text":"fields"}'])
    expect(collect(['event: ping\nretry: 3000\n\n'])).toEqual([])
  })

  it('C1-15 [DONE] 控制哨兵与空载荷：不产生帧', () => {
    expect(parseSseFrame('[DONE]')).toBeNull()
    expect(parseSseFrame('')).toBeNull()
    expect(parseSseFrame('   ')).toBeNull()
    expect(parseSseFrame(' \ufeff[DONE]')).toBeNull()
  })
})

// ── C2：帧序异常 ─────────────────────────────────────────────────────────────

describe('C2 帧序异常（跳号 / 回退 / 重复 / 双 runId 交错）', () => {
  it('C2-01 seq 跳号与缺省：解析层不做连续性判定，业务帧全部投递', async () => {
    const { seen, info } = await runSse([
      sseLine({ type: 'start', runId: 'run-j', seq: 1 })
      + sseLine({ type: 'delta', runId: 'run-j', seq: 7, text: '跳' })
      + 'data: {"type":"delta","runId":"run-j","text":"缺seq"}\n\n'
      + sseLine({ type: 'done', runId: 'run-j', seq: 12, cancelled: false })
    ])
    expect(seen.map((item) => item.type)).toEqual(['start', 'delta', 'delta', 'done'])
    expect(seen[1]?.text).toBe('跳')
    expect(seen[2]?.text).toBe('缺seq')
    expect(info.terminal).toBe(true)
    // 缺字段 / 非数字 seq 不参与序号记账
    expect(frameSeq({ type: 'delta' } as SseFrame)).toBeNull()
    expect(frameSeq({ type: 'delta', seq: '5' } as unknown as SseFrame)).toBeNull()
    expect(frameSeq({ type: 'delta', seq: Number.NaN } as SseFrame)).toBeNull()
  })

  it('C2-02 seq 回退：lastSeq 取最大值（重挂只补最大号之后，不因回退重放）', async () => {
    FakeEventSource.urls = []
    ;(globalThis as unknown as { EventSource: unknown }).EventSource = FakeEventSource
    const controller = new AbortController()
    const pending = runSse(
      [
        sseLine({ type: 'start', runId: 'run-r', seq: 5 })
        + sseLine({ type: 'delta', runId: 'run-r', seq: 3, text: '回退' })
      ],
      { signal: controller.signal }
    )
    await tick()
    expect(FakeEventSource.urls).toHaveLength(1)
    expect(FakeEventSource.urls[0]).toBe('/api/ai/events/run-r?lastSeq=5')
    controller.abort()
    await tick()
    const { seen } = await pending
    expect(seen.map((item) => item.type)).toEqual(['start', 'delta'])
  })

  it('C2-03 重复 seq：store 层幂等（文本不重复累加、序号不回退）', async () => {
    const store = await freshStore()
    store.sessionId = 's-dup'
    store.messages = [textMessage('a-dup', 'assistant', '', { streaming: true, done: false })]

    const delta = frame({ type: 'delta', runId: 'run-d', seq: 4, text: '不重复' })
    store.handleFrame(delta)
    store.handleFrame(delta)

    expect(store.messages).toHaveLength(1)
    expect(store.messages[0]?.content).toBe('不重复')
    expect(store.runSeq['run-d']).toBe(4)
    expect(store.messages[0]?.appliedSeqs).toEqual([4])
  })

  it('C2-04 两个 runId 在同一连接交错：异轮帧不投递、异轮 done 不终结本轮', async () => {
    FakeEventSource.urls = []
    ;(globalThis as unknown as { EventSource: unknown }).EventSource = FakeEventSource
    const { seen, info } = await runSse([
      sseLine({ type: 'start', runId: 'run-A', seq: 1 })
      + sseLine({ type: 'delta', runId: 'run-A', seq: 2, text: 'A1' })
      + sseLine({ type: 'start', runId: 'run-B', seq: 1 })
      + sseLine({ type: 'delta', runId: 'run-B', seq: 2, text: 'B' })
      + sseLine({ type: 'done', runId: 'run-B', seq: 3, cancelled: false })
      + sseLine({ type: 'delta', runId: 'run-A', seq: 3, text: 'A2' })
      + sseLine({ type: 'done', runId: 'run-A', seq: 4, cancelled: false })
    ])
    expect(seen.map((item) => item.type)).toEqual(['start', 'delta', 'delta', 'done'])
    expect(seen.map((item) => item.text).filter(Boolean)).toEqual(['A1', 'A2'])
    expect(seen.every((item) => item.runId === 'run-A')).toBe(true)
    expect(info.terminal).toBe(true)
    expect(info.lastFrame?.runId).toBe('run-A')
    expect(FakeEventSource.urls).toHaveLength(0) // 终帧已到：不重挂
  })
})

// ── C3：未知帧前向兼容 ───────────────────────────────────────────────────────

describe('C3 未知帧前向兼容（对称于后端 FrameCompatReadTest）', () => {
  it('C3-01 未知帧类型不崩、不阻断后续帧（streamChat 全链）', async () => {
    const { seen, info } = await runSse([
      sseLine({ type: 'start', runId: 'run-f', seq: 1 })
      + sseLine({ type: 'future_frame', runId: 'run-f', seq: 2, payload: { x: 1 } })
      + 'data: 这不是 JSON\n\n'
      + sseLine({ type: 'delta', runId: 'run-f', seq: 3, text: '后续' })
      + sseLine({ type: 'done', runId: 'run-f', seq: 4, cancelled: false })
    ])
    expect(seen.map((item) => item.type)).toEqual(['start', 'delta', 'done'])
    expect(seen[1]?.text).toBe('后续')
    expect(info.terminal).toBe(true)
  })

  it('C3-02 前向兼容帧 reasoning 被接受，真正未知的 type 被拒', () => {
    expect(parseSseFrame('{"type":"reasoning","text":"思考"}')).toMatchObject({
      type: 'reasoning',
      text: '思考'
    })
    expect(parseSseFrame('{"type":"context","level":"auto","before":1,"after":1}')).toMatchObject({
      type: 'context'
    })
    expect(parseSseFrame('{"type":"reasoning_v2","text":"x"}')).toBeNull()
  })
})

// ── C12：畸形输入 ────────────────────────────────────────────────────────────

describe('C12 畸形输入（超长 / 控制字符 / 多字节边界 / 双向文本）', () => {
  it('C12-01 超长单行：20 万字符的 data 行完整解析', () => {
    const longText = 'x'.repeat(200_000)
    const payload = JSON.stringify({ type: 'delta', runId: 'run-long', seq: 1, text: longText })
    const payloads = collect([`data: ${payload.slice(0, 100_000)}`, `${payload.slice(100_000)}\n\n`])
    expect(payloads).toEqual([payload])
    const parsed = parseSseFrame(payloads[0] ?? '') as { text?: string } | null
    expect(parsed?.text).toHaveLength(200_000)
  })

  it('C12-02 NUL / 裸控制字符：解析返回 null 不抛，流在坏帧之后继续', async () => {
    expect(parseSseFrame('{"type":"delta","text":"a\u0000b"}')).toBeNull()
    expect(parseSseFrame('{"type":"delta","text":"a\u0001b"}')).toBeNull()

    const { seen, info } = await runSse([
      sseLine({ type: 'start', runId: 'run-nul', seq: 1 })
      + 'data: {"type":"delta","text":"坏\u0000帧"}\n\n'
      + sseLine({ type: 'delta', runId: 'run-nul', seq: 2, text: '好帧' })
      + sseLine({ type: 'done', runId: 'run-nul', seq: 3, cancelled: false })
    ])
    expect(seen.map((item) => item.type)).toEqual(['start', 'delta', 'done'])
    expect(seen[1]?.text).toBe('好帧')
    expect(info.terminal).toBe(true)
  })

  it('C12-03 组合 emoji：ZWJ 家族 / 肤色 / 旗帜 / 键帽逐码点保真，无替换符', () => {
    const family = '\u{1F468}\u200D\u{1F469}\u200D\u{1F467}\u200D\u{1F466}'
    const skin = '\u{1F44D}\u{1F3FD}'
    const flag = '\u{1F1E8}\u{1F1F3}'
    const keycap = '1\uFE0F\u20E3'
    const text = `${family}${skin}${flag}${keycap}`
    const parsed = parseSseFrame(JSON.stringify({ type: 'delta', text })) as { text?: string } | null
    expect(parsed?.text).toBe(text)
    expect(parsed?.text).not.toContain('\uFFFD')
    expect(Array.from(parsed?.text ?? '')).toEqual(Array.from(text))
    expect(Array.from(parsed?.text ?? '')).toHaveLength(7 + 2 + 2 + 3)
  })

  it('C12-04 RTL：希伯来 / 阿拉伯文本与 RLO 控制符原样保留', () => {
    const text = '\u05E9\u05DC\u05D5\u05DD \u0645\u0631\u062D\u0628\u0627 \u202E123\u202C'
    const payloads = collect([`data: ${JSON.stringify({ type: 'delta', text })}\n\n`])
    const parsed = parseSseFrame(payloads[0] ?? '') as { text?: string } | null
    expect(parsed?.text).toBe(text)
    expect(parsed?.text).not.toContain('\uFFFD')
  })

  it('C12-05 半截 UTF-8 跨块：4 字节 emoji 被切成多块时不产生替换符、代理项对不碎', async () => {
    const text = '\u524D\u{1F468}\u200D\u{1F469}\u200D\u{1F467}\u200D\u{1F466}\u540E'
    const sse = sseLine({ type: 'start', runId: 'run-u', seq: 1 })
      + sseLine({ type: 'delta', runId: 'run-u', seq: 2, text })
      + sseLine({ type: 'done', runId: 'run-u', seq: 3, cancelled: false })
    const bytes = encode(sse)
    const textStart = encode(sse.slice(0, sse.indexOf(text))).length
    const { seen, info } = await runSse([
      bytes.slice(0, textStart + 1),
      bytes.slice(textStart + 1, textStart + 5),
      bytes.slice(textStart + 5)
    ])
    const delta = seen.find((item) => item.type === 'delta')
    expect(delta?.text).toBe(text)
    expect(delta?.text).not.toContain('\uFFFD')
    expect(info.terminal).toBe(true)
  })
})

// ── 集成位：读循环全链（与批1 D1/D2 闸门衔接，但不重复其断言） ──────────────

describe('集成位（streamChat + fetch mock ReadableStream）', () => {
  it('I-01 终帧闸门的 flush 残余路径：done 之后、无结尾 \\n\\n 的迟到帧不落地', async () => {
    const body = sseLine({ type: 'start', runId: 'run-t', seq: 1 })
      + sseLine({ type: 'delta', runId: 'run-t', seq: 2, text: '正文' })
      + sseLine({ type: 'done', runId: 'run-t', seq: 3, cancelled: false })
      + 'data: {"type":"delta","runId":"run-t","seq":4,"text":"迟到"}'
    const { seen, info } = await runSse([body])
    expect(seen.map((item) => item.type)).toEqual(['start', 'delta', 'done'])
    expect(seen.map((item) => item.text).filter(Boolean)).toEqual(['正文'])
    expect(info.terminal).toBe(true)
  })

  it('I-02 乱序/重复帧在 store 的落点：按到达序应用、重复 seq 幂等、runSeq 取最大', async () => {
    const store = await freshStore()
    store.sessionId = 's-order'
    store.messages = [textMessage('a-order', 'assistant', '', { streaming: true, done: false })]

    store.handleFrame(frame({ type: 'delta', runId: 'run-o', seq: 5, text: 'A' }))
    store.handleFrame(frame({ type: 'delta', runId: 'run-o', seq: 3, text: 'B' }))
    store.handleFrame(frame({ type: 'delta', runId: 'run-o', seq: 5, text: 'A' }))

    // 乱序帧照常落地（解析层不做重排），重复 seq 只应用一次
    expect(store.messages[0]?.content).toBe('AB')
    expect(store.messages[0]?.appliedSeqs).toEqual([5, 3])
    // 重挂锚点取最大序号：回退帧不得把已确认的位点拉低
    expect(store.runSeq['run-o']).toBe(5)
  })

  it('I-03 两个 runId 的序号账本相互独立（同一宿主上交错投递不串号）', async () => {
    const store = await freshStore()
    store.sessionId = 's-two'
    store.messages = [textMessage('a-two', 'assistant', '', { streaming: true, done: false })]

    store.handleFrame(frame({ type: 'delta', runId: 'run-A', seq: 5, text: 'A' }))
    store.handleFrame(frame({ type: 'delta', runId: 'run-B', seq: 9, text: 'B' }))
    store.handleFrame(frame({ type: 'delta', runId: 'run-A', seq: 6, text: 'A2' }))

    expect(store.runSeq).toMatchObject({ 'run-A': 6, 'run-B': 9 })
  })
})
