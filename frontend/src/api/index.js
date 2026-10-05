/** 基础请求封装。 */

async function request(url, options = {}) {
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options
  })
  const text = await res.text()
  let data
  try {
    data = JSON.parse(text)
  } catch {
    data = { code: res.status, msg: text }
  }
  if (!res.ok || data.code !== 0) {
    const err = new Error(data.msg || `请求失败（HTTP ${res.status}）`)
    err.status = res.status
    throw err
  }
  return data.data
}

export const api = {
  get: (url) => request(url),
  post: (url, body) => request(url, { method: 'POST', body: JSON.stringify(body || {}) }),
  put: (url, body) => request(url, { method: 'PUT', body: JSON.stringify(body || {}) }),
  del: (url) => request(url, { method: 'DELETE' }),
  upload: async (url, formData) => {
    const res = await fetch(url, { method: 'POST', body: formData })
    const data = await res.json()
    if (!res.ok || data.code !== 0) throw new Error(data.msg || '上传失败')
    return data.data
  }
}

/** 触发浏览器下载（GET 流）。 */
export function downloadFile(url, filename) {
  const a = document.createElement('a')
  a.href = url
  if (filename) a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

/** 保存 Blob 为文件（SpreadJS 编辑后导出用）。 */
export function saveBlob(blob, filename) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(a.href), 5000)
}

/**
 * SSE 客户端（fetch 流式解析，兼容后端 event: xxx / data: xxx 帧）。
 * onEvent: (eventName, payload) => void
 */
export async function sseRequest(url, { method = 'GET', body, onEvent, signal } = {}) {
  const res = await fetch(url, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
    signal
  })
  if (!res.ok) {
    const t = await res.text()
    throw new Error(`SSE 请求失败（HTTP ${res.status}）：${t}`)
  }
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  for (; ;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      const parsed = parseFrame(frame)
      if (parsed) onEvent(parsed.event, parsed.data)
    }
  }
  // 尾部残帧
  const tail = parseFrame(buf)
  if (tail) onEvent(tail.event, tail.data)
}

function parseFrame(frame) {
  let event = 'message'
  let dataLines = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }
  if (dataLines.length === 0) return null
  const raw = dataLines.join('\n')
  let data = raw
  if (raw && raw !== '[DONE]') {
    try {
      data = JSON.parse(raw)
    } catch {
      // 保留原文
    }
  }
  return { event, data }
}
