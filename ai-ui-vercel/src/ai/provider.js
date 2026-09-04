/**
 * Vercel AI SDK 的 DeepSeek 模型 provider —— 前端 AI runtime 的模型入口。
 *
 * 范式要点:
 *   1. baseURL 指向后端透明代理 /api/ai/proxy,SDK 会 POST 到
 *      /api/ai/proxy/chat/completions,后端 AiProxyController 注入真实 api-key 转发给 DeepSeek。
 *   2. apiKey 仅占位('placeholder'),真实 DeepSeek key 永不进入前端代码/网络请求头
 *      (由后端注入),满足「避免 api-key 泄露」约束。
 *   3. compatibility:'compatible' —— OpenAI 兼容模式(DeepSeek 完全遵循 OpenAI 接口规范),
 *      放宽严格校验以适配第三方兼容端点。
 *
 * 版本对齐(已通过 npm registry 核实):
 *   ai@6.0.221 + @ai-sdk/openai@3.0.97(npm dist-tag ai-v6)
 *   两者共享 @ai-sdk/provider@3.x,npm 去重为单一副本,无接口不匹配。
 */
import { createOpenAI } from '@ai-sdk/openai'

// ====== 改造 H2：LLM 单请求超时 180s ======
// 长上下文流式生成可能 60s+ 不出 token,浏览器 fetch 默认无整体超时,弱网挂起会无限等待。
// AbortSignal.any 合并调用方信号(F1 停止按钮的 abort)与超时信号,任一触发即中断。
const LLM_TIMEOUT_MS = 180 * 1000
const fetchWithTimeout = (input, init = {}) => {
  const timeoutSignal = AbortSignal.timeout(LLM_TIMEOUT_MS)
  const combined = init.signal
    ? (typeof AbortSignal.any === 'function' ? AbortSignal.any([init.signal, timeoutSignal]) : init.signal)
    : timeoutSignal
  return fetch(input, { ...init, signal: combined })
}

const openai = createOpenAI({
  baseURL: '/api/ai/proxy',
  apiKey: 'placeholder',
  compatibility: 'compatible',
  fetch: fetchWithTimeout
})

/** DeepSeek 对话模型(经后端代理) */
export const deepseek = openai.chat('deepseek-v4-flash')
