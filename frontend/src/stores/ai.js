import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'
import * as aiApi from '@/api/ai.js'
import router from '@/router/index.js'

export const useAiStore = defineStore('ai', () => {
  const sessionId = ref(null)
  const messages = ref([])
  const runActive = ref(false)
  const pendingInteraction = ref(null)
  const currentAbortController = ref(null)

  // Initialize session from sessionStorage or create new
  async function initSession() {
    let sid = sessionStorage.getItem('ai_session_id')
    // 防御：历史版本可能存入过 "undefined" 字符串
    if (sid && (sid === 'undefined' || sid === 'null')) {
      sessionStorage.removeItem('ai_session_id')
      sid = null
    }
    if (sid) {
      // Verify it's still alive + 恢复对话显示（同一页签刷新后历史不丢）
      try {
        const res = await aiApi.getSession(sid)
        sessionId.value = sid
        const state = res.data.data || {}
        const items = state.messages || []
        messages.value = items.map((it, i) => ({
          id: 'hist-' + i,
          role: it.role,
          text: it.text || '',
          toolName: it.toolName,
          summary: it.summary,
          success: it.role === 'tool' ? true : undefined,
          ts: new Date()
        }))
        if (state.runActive) {
          addSystemMessage('上一轮对话仍在后台进行中，完成后回复会补充显示；如需立即对话可点右上角「新对话」')
        }
        if (state.pendingInteraction && state.pendingInteraction.iid) {
          pendingInteraction.value = state.pendingInteraction
        }
        const usage = state.usage || {}
        if (usage.promptTokens || usage.completionTokens) {
          addSystemMessage(`上一轮消耗 token：输入 ${usage.promptTokens || 0} / 输出 ${usage.completionTokens || 0}`)
        }
        return
      } catch (e) {
        sessionStorage.removeItem('ai_session_id')
      }
    }
    // Create new session
    try {
      const res = await aiApi.createSession()
      // ApiResponse 信封：sid 位于 data.data.sid（此前误取 data.sid，导致存入 "undefined"）
      sessionId.value = res.data.data.sid
      sessionStorage.setItem('ai_session_id', res.data.data.sid)
    } catch (e) {
      console.error('Failed to create AI session:', e)
    }
  }

  async function resetSession() {
    if (sessionId.value) {
      try { await aiApi.resetSession(sessionId.value) } catch (e) {}
    }
    sessionStorage.removeItem('ai_session_id')
    sessionId.value = null
    messages.value = []
    runActive.value = false
    pendingInteraction.value = null
    await initSession()
  }

  function addUserMessage(text) {
    messages.value.push({ id: Date.now(), role: 'user', text, ts: new Date() })
  }

  function addAssistantMessage(text) {
    const id = Date.now()
    messages.value.push({ id, role: 'assistant', text: '', ts: new Date(), streaming: true })
    return id
  }

  function appendAssistantText(id, delta) {
    const msg = messages.value.find(m => m.id === id)
    if (msg) msg.text += delta
  }

  function finalizeAssistantMessage(id) {
    const msg = messages.value.find(m => m.id === id)
    if (msg) msg.streaming = false
  }

  function addToolMessage(toolCallId, toolName, title, success, summary) {
    messages.value.push({
      id: Date.now(),
      role: 'tool',
      toolCallId, toolName, title, success, summary,
      ts: new Date()
    })
  }

  function addSystemMessage(text) {
    messages.value.push({ id: Date.now(), role: 'system', text, ts: new Date() })
  }

  async function sendMessage(text) {
    if (!sessionId.value) await initSession()
    if (!sessionId.value) {
      addSystemMessage('AI 服务不可用，请刷新页面重试')
      return
    }

    addUserMessage(text)
    runActive.value = true
    let assistantMsgId = null
    let currentToolCallId = null

    const controller = new AbortController()
    currentAbortController.value = controller

    try {
      const response = await fetch(`/api/ai/sessions/${sessionId.value}/runs`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
        body: JSON.stringify({ message: text }),
        signal: controller.signal
      })

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() // keep incomplete line

        for (const line of lines) {
          if (!line.startsWith('data:')) continue
          const data = line.slice(5).trim()
          if (!data) continue

          try {
            const event = JSON.parse(data)
            handleEvent(event, {
              onAssistantStart: () => { assistantMsgId = addAssistantMessage('') },
              onTextDelta: (delta) => {
                if (assistantMsgId === null) assistantMsgId = addAssistantMessage('')
                appendAssistantText(assistantMsgId, delta)
              },
              onToolStart: (tc) => {
                if (assistantMsgId !== null) {
                  finalizeAssistantMessage(assistantMsgId)
                  assistantMsgId = null
                }
                currentToolCallId = tc.toolCallId
              },
              onInteraction: (req) => { pendingInteraction.value = req },
              onError: (msg) => addSystemMessage('⚠️ ' + msg),
              onDone: () => {
                if (assistantMsgId !== null) {
                  finalizeAssistantMessage(assistantMsgId)
                  assistantMsgId = null
                }
              }
            })
          } catch (e) {
            // ignore parse errors
          }
        }
      }
    } catch (e) {
      if (e.name !== 'AbortError') {
        addSystemMessage('连接出错: ' + e.message)
      }
    } finally {
      runActive.value = false
      currentAbortController.value = null
      if (assistantMsgId !== null) finalizeAssistantMessage(assistantMsgId)
    }
  }

  function handleEvent(event, handlers) {
    switch (event.type) {
      case 'RUN_STARTED':
        handlers.onAssistantStart?.()
        break
      case 'TEXT_DELTA':
        handlers.onTextDelta?.(event.delta)
        break
      case 'TOOL_START':
        addToolMessage(event.toolCallId, event.toolName, event.title, null, '执行中…')
        handlers.onToolStart?.(event)
        break
      case 'TOOL_DONE': {
        const msg = messages.value.find(m => m.role === 'tool' && m.toolCallId === event.toolCallId)
        if (msg) { msg.success = event.success; msg.summary = event.summary }
        break
      }
      case 'INTERACTION_REQUEST':
        pendingInteraction.value = event
        handlers.onInteraction?.(event)
        break
      case 'UI_COMMAND':
        handleUiCommand(event)
        break
      case 'ERROR':
        handlers.onError?.(event.message)
        break
      case 'RUN_COMPLETED':
        handlers.onDone?.()
        if (event.usage?.promptTokens || event.usage?.completionTokens) {
          addSystemMessage(`本轮消耗 token：输入 ${event.usage.promptTokens} / 输出 ${event.usage.completionTokens}`)
        }
        break
    }
  }

  async function cancelRun() {
    if (currentAbortController.value) {
      currentAbortController.value.abort()
    }
    if (sessionId.value) {
      try { await aiApi.cancelRun(sessionId.value) } catch (e) {}
    }
  }

  async function submitInteraction(iid, approved, reason, data) {
    if (!sessionId.value) return
    await aiApi.submitInteraction(sessionId.value, iid, approved, reason, data)
    pendingInteraction.value = null
  }

  /**
   * 处理 AI 下发的 UI 指令：
   * - navigate：路由跳转
   * - download：触发浏览器下载
   * - 其他（open_editor 等）：通过 window 事件广播给业务组件处理
   */
  function handleUiCommand(event) {
    const { command, payload } = event
    if (command === 'navigate' && payload?.route) {
      try {
        router.push(payload.route)
      } catch (e) { /* ignore */ }
      return
    }
    if (command === 'download' && payload?.url) {
      const a = document.createElement('a')
      a.href = payload.url
      if (payload.fileName) a.download = payload.fileName
      a.click()
      return
    }
    window.dispatchEvent(new CustomEvent('ui-command', { detail: { command, payload } }))
  }

  return {
    sessionId, messages, runActive, pendingInteraction,
    initSession, resetSession, sendMessage, cancelRun, submitInteraction
  }
})
