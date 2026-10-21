/**
 * 批③ 模型/参数的<b>状态层</b>（`stores/ai-model.ts`）：会话级选择 + 会话级参数 + 请求体投影。
 *
 * <h2>为什么单独一个 store（而不是塞进 `stores/ai.ts`）</h2>
 * `stores/ai.ts` 是表格专项与本批的<b>共享文件</b>（它改 state 邻近 hunk 与 `handleFrame`）。
 * 把模型状态放进独立模块，共同改动面从"两个文件"降到"一个文件的一行"（`send()` 里展开投影），
 * 两批可以机械合并而不互相踩行号。
 *
 * <h2>三条口径（也是证红点）</h2>
 * <ol>
 * <li>选择只写 `ai-model-id`，<b>不碰</b> `ai-session-id`（切换模型 ≠ 新建会话）；</li>
 * <li>参数按会话存 `ai-model-params:<sessionId>`，切会话不串味；</li>
 * <li>请求投影<b>只带变化字段</b>：没改 ⇒ 空对象；改了 ⇒ 只带改过的那几项；
 *     `allowedParams=false` ⇒ 一项都不带（锁定不是"发了被忽略"，是"压根不发"）。</li>
 * </ol>
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({ listAvailableModels: vi.fn() }))

vi.mock('@/api/aiConfig', () => ({ aiConfigApi: api, default: undefined }))

import { MODEL_ID_KEY, MODEL_PARAMS_PREFIX, useAiModelStore } from '@/stores/ai-model'

const MODELS = [
  {
    providerId: 7,
    providerCode: 'deepseek',
    providerName: 'DeepSeek',
    modelId: 'deepseek-flash',
    displayName: 'Flash',
    supportsToolCalling: true,
    supportsVision: false,
    supportsReasoning: false,
    maxContext: 128000,
    maxOutput: 8192,
    isDefault: true
  },
  {
    providerId: 7,
    providerCode: 'deepseek',
    providerName: 'DeepSeek',
    modelId: 'plain-model',
    displayName: 'Plain',
    supportsToolCalling: false,
    supportsVision: false,
    supportsReasoning: false,
    maxContext: 32000,
    maxOutput: 4096,
    isDefault: false
  }
]

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  api.listAvailableModels.mockReset()
  api.listAvailableModels.mockResolvedValue(MODELS)
})

describe('ai-model store：选择与持久化', () => {
  it('select 写 ai-model-id，且不动 ai-session-id', () => {
    sessionStorage.setItem('ai-session-id', 's-existing')
    const store = useAiModelStore()

    store.select('plain-model')

    expect(store.modelId).toBe('plain-model')
    expect(sessionStorage.getItem(MODEL_ID_KEY)).toBe('plain-model')
    // 切换模型不等于新建会话：会话身份一个字节都不动
    expect(sessionStorage.getItem('ai-session-id')).toBe('s-existing')
  })

  it('刷新（重建 store）后从 sessionStorage 恢复选择', () => {
    sessionStorage.setItem(MODEL_ID_KEY, 'deepseek-flash')
    setActivePinia(createPinia())

    expect(useAiModelStore().modelId).toBe('deepseek-flash')
  })

  it('ensureModels 只取一次；force 才重取', async () => {
    const store = useAiModelStore()

    await store.ensureModels()
    await store.ensureModels()
    await store.ensureModels(true)

    expect(api.listAvailableModels).toHaveBeenCalledTimes(2)
    expect(store.models).toHaveLength(2)
  })

  it('取列表失败 ⇒ 空列表 + 错误文案，不抛（面板退化为"默认模型"）', async () => {
    api.listAvailableModels.mockRejectedValue(new Error('后端不可达'))
    const store = useAiModelStore()

    await store.ensureModels()

    expect(store.models).toEqual([])
    expect(store.modelsError).toBeTruthy()
    expect(store.patch('s-1')).toEqual({})
  })
})

describe('ai-model store：参数按会话隔离', () => {
  it('参数写 ai-model-params:<sessionId>，另一个会话读不到', () => {
    const store = useAiModelStore()

    store.setParam('s-1', 'temperature', 0.3)

    expect(sessionStorage.getItem(`${MODEL_PARAMS_PREFIX}s-1`)).toContain('0.3')
    expect(store.paramsOf('s-2')).toEqual({})
  })

  it('恢复默认 ⇒ 清空存储与内存，且投影回到空对象', () => {
    const store = useAiModelStore()
    store.setParam('s-1', 'topP', 0.5)
    expect(store.patch('s-1')).toEqual({ params: { topP: 0.5 } })

    store.resetParams('s-1')

    expect(store.patch('s-1')).toEqual({})
    expect(sessionStorage.getItem(`${MODEL_PARAMS_PREFIX}s-1`)).toBeNull()
  })
})

describe('ai-model store：请求投影只带变化字段', () => {
  it('未选模型、未改参数 ⇒ 空对象（回落后端默认，行为等价）', () => {
    const store = useAiModelStore()

    expect(store.patch('s-1')).toEqual({})
  })

  it('选了模型 ⇒ 只有 modelId 键', () => {
    const store = useAiModelStore()
    store.select('plain-model')

    expect(store.patch('s-1')).toEqual({ modelId: 'plain-model' })
  })

  it('改了某一项参数 ⇒ params 只含那一项（键名与后端 record 逐字一致）', () => {
    const store = useAiModelStore()
    store.applyHealth({ allowedParams: true, defaultParams: { temperature: 0.2, topP: null, maxTokens: 1024 } })
    store.setParam('s-1', 'temperature', 0.7)
    store.setParam('s-1', 'topP', 0.9)

    const patch = store.patch('s-1')

    expect(patch.params).toEqual({ temperature: 0.7, topP: 0.9 })
    expect(Object.keys(patch.params!)).toEqual(['temperature', 'topP'])
  })

  it('参数与全局默认相同 ⇒ 视为"没改"，不进 body', () => {
    const store = useAiModelStore()
    store.applyHealth({ allowedParams: true, defaultParams: { temperature: 0.2, topP: null, maxTokens: 1024 } })
    store.setParam('s-1', 'temperature', 0.2)

    expect(store.patch('s-1')).toEqual({})
  })

  it('allowedParams=false ⇒ 一项参数都不带（锁定即不发）', () => {
    const store = useAiModelStore()
    store.applyHealth({ allowedParams: true, defaultParams: {} })
    store.setParam('s-1', 'temperature', 0.9)
    store.applyHealth({ allowedParams: false })

    expect(store.paramsLocked).toBe(true)
    expect(store.patch('s-1')).toEqual({})
  })
})
