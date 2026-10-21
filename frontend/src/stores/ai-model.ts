/**
 * 模型/参数的选择态（模型配置专项批③）。
 *
 * <h2>为什么独立成 store，不进 `stores/ai.ts`</h2>
 * `stores/ai.ts` 是与<b>表格专项</b>共享的文件（对方的改动落在 state 邻近 hunk 与 `handleFrame`）。
 * 本批把模型状态整体放进这个模块，对 `stores/ai.ts` 的改动只剩 `send()` 里展开一次投影，
 * 于是两批合并时共同文件从两个降到一个、hunk 也不相邻。
 *
 * <h2>三条持久化口径（设计稿 §3.6）</h2>
 * <ol>
 * <li>选择：sessionStorage `ai-model-id`，与 `ai-session-id` <b>并列</b>。切换模型不新建会话 ——
 *     会话身份是记忆窗口的键，换模型不该换记忆；</li>
 * <li>参数：`ai-model-params:<sessionId>`（按会话隔离；换会话不串味）；</li>
 * <li>两者都<b>不进消息镜像</b>（`MIRROR_VERSION` 不动）。</li>
 * </ol>
 *
 * <h2>请求投影只带变化字段</h2>
 * {@link useAiModelStore#patch} 是唯一的对外出口：没选模型 ⇒ 不带 `modelId`（回落后端默认解析链，
 * 行为与批③ 之前等价）；参数与全局默认相同 ⇒ 不带；`allowedParams=false` ⇒ 一项都不带
 * （"锁定"在客户端就是"不发"，而不是"发了等后端忽略"）。
 */
import { defineStore } from 'pinia'
import { aiConfigApi, type AiModelView } from '@/api/aiConfig'
import type { AiHealth } from '@/types/ai'

/** 选中模型（sessionStorage 键）：与 `ai-session-id` 并列，页签级。 */
export const MODEL_ID_KEY = 'ai-model-id'

/** 参数（sessionStorage 前缀 + sessionId）：按会话隔离。 */
export const MODEL_PARAMS_PREFIX = 'ai-model-params:'

/** 三项可覆盖参数（wire 键名与后端 `AiChatRequest.Params` 的 record 组件名逐字一致）。 */
export interface AiModelParams {
  temperature?: number | null
  topP?: number | null
  maxTokens?: number | null
}

/** 投影到 `POST /api/ai/chat` 请求体的部分（空对象 = 两个块都不带）。 */
export interface AiModelRequestPatch {
  modelId?: string
  params?: AiModelParams
}

const PARAM_KEYS = ['temperature', 'topP', 'maxTokens'] as const

function readModelId(): string | null {
  try {
    return sessionStorage.getItem(MODEL_ID_KEY) || null
  } catch {
    return null
  }
}

function writeModelId(modelId: string | null): void {
  try {
    if (modelId) {
      sessionStorage.setItem(MODEL_ID_KEY, modelId)
    } else {
      sessionStorage.removeItem(MODEL_ID_KEY)
    }
  } catch {
    // 隐私模式/配额：写不进去不影响本页签的当前值
  }
}

function readParams(sessionId: string): AiModelParams {
  try {
    const raw = sessionStorage.getItem(`${MODEL_PARAMS_PREFIX}${sessionId}`)
    if (!raw) {
      return {}
    }
    const parsed = JSON.parse(raw) as Record<string, unknown>
    const out: AiModelParams = {}
    for (const key of PARAM_KEYS) {
      const value = parsed[key]
      if (typeof value === 'number' && Number.isFinite(value)) {
        out[key] = value
      }
    }
    return out
  } catch {
    return {}
  }
}

function writeParams(sessionId: string, params: AiModelParams): void {
  try {
    const key = `${MODEL_PARAMS_PREFIX}${sessionId}`
    if (Object.keys(params).length === 0) {
      sessionStorage.removeItem(key)
      return
    }
    sessionStorage.setItem(key, JSON.stringify(params))
  } catch {
    // 同上：持久化失败不影响本页签
  }
}

export const useAiModelStore = defineStore('aiModel', {
  state: () => ({
    /** 选中的模型（null = 不指定，后端走默认解析链）。 */
    modelId: readModelId() as string | null,
    /** 后端返回的可用模型（列表即真值：可用性三条判据在后端判完）。 */
    models: [] as AiModelView[],
    modelsLoaded: false,
    modelsLoading: false,
    modelsError: null as string | null,
    /** `ai_setting['allow.userParams']`；null = 还没探到（按"允许"处理，与后端缺省一致）。 */
    allowedParams: null as boolean | null,
    /** 全局默认三值（popover 初值与"是否改过"的比较基准）。 */
    defaults: {} as AiModelParams,
    /** 按会话的内存副本（真源是 sessionStorage，这里避免每次读都解析 JSON）。 */
    params: {} as Record<string, AiModelParams>
  }),

  getters: {
    /** 当前选中的模型行（列表里找不到 = 该模型已下架/停用 ⇒ 当作未选）。 */
    currentModel(state): AiModelView | null {
      if (!state.modelId) {
        return null
      }
      return state.models.find((model) => model.modelId === state.modelId) ?? null
    },

    /** 参数是否被策略锁住（只有显式 false 才锁）。 */
    paramsLocked(state): boolean {
      return state.allowedParams === false
    },

    /** 能力位：未知（没有选中/列表未到）时按"支持"处理 —— 不凭空吓用户。 */
    supportsToolCalling(): boolean {
      return this.currentModel === null ? true : this.currentModel.supportsToolCalling
    }
  },

  actions: {
    /**
     * 拉取可用模型（挂载一次即可；`force=true` 用于"重新检测"）。
     *
     * <p>失败不抛：面板退化为"只有默认模型"（选择器不出现），输入与发送不受影响。
     */
    async ensureModels(force = false): Promise<void> {
      if (this.modelsLoading || (this.modelsLoaded && !force)) {
        return
      }
      this.modelsLoading = true
      try {
        this.models = await aiConfigApi.listAvailableModels()
        this.modelsLoaded = true
        this.modelsError = null
      } catch (error) {
        this.models = []
        this.modelsLoaded = true
        this.modelsError = error instanceof Error ? error.message : '模型清单加载失败'
      } finally {
        this.modelsLoading = false
      }
    },

    /** 选择模型（null/空串 = 回到"不指定"）。只写 `ai-model-id`，不碰会话身份。 */
    select(modelId: string | null): void {
      const next = modelId && modelId.trim() !== '' ? modelId : null
      this.modelId = next
      writeModelId(next)
    },

    /**
     * 用 `/health` 的两项刷新策略与默认值（`allowedParams` / `defaultParams`）。
     *
     * <p>缺键（旧后端）⇒ 保持原值：策略未知时按"允许"，默认值未知时按空 —— 都不会把界面锁死。
     */
    applyHealth(health: Partial<Pick<AiHealth, 'allowedParams' | 'defaultParams'>> | null): void {
      if (!health) {
        return
      }
      if (typeof health.allowedParams === 'boolean') {
        this.allowedParams = health.allowedParams
      }
      if (health.defaultParams) {
        this.defaults = {
          temperature: health.defaultParams.temperature ?? null,
          topP: health.defaultParams.topP ?? null,
          maxTokens: health.defaultParams.maxTokens ?? null
        }
      }
    },

    /** 某会话的参数（未写过 ⇒ 空对象）。 */
    paramsOf(sessionId: string): AiModelParams {
      const cached = this.params[sessionId]
      if (cached) {
        return cached
      }
      const stored = readParams(sessionId)
      this.params[sessionId] = stored
      return stored
    },

    /** 改一项（`null` = 清掉该项，回到全局默认）。 */
    setParam(sessionId: string, key: keyof AiModelParams, value: number | null): void {
      if (!PARAM_KEYS.includes(key)) {
        return
      }
      const next: AiModelParams = { ...this.paramsOf(sessionId) }
      if (value === null || !Number.isFinite(value)) {
        delete next[key]
      } else {
        next[key] = value
      }
      this.params[sessionId] = next
      writeParams(sessionId, next)
    },

    /** 恢复默认：清空该会话的全部覆盖。 */
    resetParams(sessionId: string): void {
      this.params[sessionId] = {}
      writeParams(sessionId, {})
    },

    /**
     * 请求投影（`send()` 唯一的接入点）：只带<b>变化</b>的字段。
     *
     * <p>与全局默认相等的项<b>不发</b>：发了等于把"今天的默认值"固化进这一轮，
     * 之后管理员改默认，旧会话仍按老值跑（用户没做过的决定不该被冒充）。
     */
    patch(sessionId: string): AiModelRequestPatch {
      const out: AiModelRequestPatch = {}
      if (this.modelId) {
        out.modelId = this.modelId
      }
      if (!this.paramsLocked) {
        const current = this.paramsOf(sessionId)
        const changed: AiModelParams = {}
        for (const key of PARAM_KEYS) {
          const value = current[key]
          if (value === null || value === undefined) {
            continue
          }
          if (value !== this.defaults[key]) {
            changed[key] = value
          }
        }
        if (Object.keys(changed).length > 0) {
          out.params = changed
        }
      }
      return out
    }
  }
})
