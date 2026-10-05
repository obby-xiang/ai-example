/**
 * SSE 消费（POST 版，fetch + ReadableStream 手动解析）：
 * 用于 AI 对话流（EventSource 不支持 POST）。
 */
export async function postSSE(url, body, { signal, onEvent } = {}) {
  const resp = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      'X-Session-Id': sessionStorage.getItem('qk.sessionId') || ''
    },
    body: JSON.stringify(body),
    signal
  })
  if (!resp.ok) {
    let msg = `HTTP ${resp.status}`
    try {
      const txt = await resp.text()
      const data = JSON.parse(txt)
      msg = data.msg || data.message || msg
    } catch { /* ignore */ }
    throw new Error(msg)
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  const dispatch = (chunk) => {
    let event = 'message'
    const dataLines = []
    for (const line of chunk.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, ''))
      }
    }
    if (!dataLines.length) return
    let data = null
    try {
      data = JSON.parse(dataLines.join('\n'))
    } catch {
      data = { text: dataLines.join('\n') }
    }
    onEvent && onEvent(event, data)
  }
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const chunk = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      if (chunk.trim()) dispatch(chunk)
    }
  }
  if (buffer.trim()) dispatch(buffer)
}
