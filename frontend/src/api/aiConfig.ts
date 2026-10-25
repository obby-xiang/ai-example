/**
 * 模型配置端点的 client（批②；后端 `AiConfigController`）。
 *
 * <p>
 * 与 `api/ai.ts` 的差别：这些端点走**业务信封** `{success,code,message,data}`，`http.ts` 的拦截器
 * 已经拆过信封（失败时 reject 成 {@link ApiError}，`code` 保留后端稳定码）。所以这里只负责
 * "拼路径 + 类型"，不做任何 code → 文案的映射（那是 `constants/ai-settings-copy.ts` 的事，
 * 页面只管把 code 交给它 —— i18n 口径：后端不参与文案）。
 */

import { del, get, post, put } from './http'

/** `GET /api/ai/providers` 的一行（响应体永不含 key 的明文/密文/指纹）。 */
export interface AiProviderView {
  id: number
  code: string
  displayName: string
  protocol: string
  baseUrl: string
  completionsPath: string
  modelsPath: string
  keyConfigured: boolean
  keyLast4: string | null
  keyRef: 'DB' | 'ENV' | 'NONE' | string
  /** OK / MISSING / UNREADABLE / DISABLED（运行时结论） */
  keyStatus: string
  /** ENV_SEED / DB（设置页"来源"标签） */
  source: string
  enabled: boolean
  models: AiModelRow[]
}

export interface AiModelRow {
  id: number
  modelId: string
  displayName: string
  supportsToolCalling: boolean
  supportsVision: boolean
  supportsReasoning: boolean
  maxContext: number | null
  maxOutput: number | null
  enabled: boolean
  isDefault: boolean
  sortOrder: number
}

/** `POST /api/ai/providers/{id}/test` 的结果（永远 HTTP 200，判成功只看 ok）。 */
export interface AiProbeResult {
  ok: boolean
  status: number | null
  latencyMs: number
  code: string | null
  message: string | null
  modelCount: number
}

/** `POST /api/ai/providers/{id}/models/fetch` 的结果。 */
export interface AiFetchModelsResult {
  ok: boolean
  models: string[]
  cached: boolean
  code: string | null
  message: string | null
}

/** `GET/PUT /api/ai/settings/defaults`（null = 未设置；PUT 是整体替换）。 */
export interface AiDefaults {
  modelId: string | null
  /** 与 modelId 成对的默认目标 provider（旧配置可能为 null/缺省） */
  providerId: number | null
  temperature: number | null
  topP: number | null
  maxTokens: number | null
  allowUserParams: boolean | null
  version: number
}

export interface AiProviderWriteBody {
  code?: string
  displayName?: string
  protocol?: string
  baseUrl?: string
  completionsPath?: string
  modelsPath?: string
  /** 省略/空串 = 不修改（新建时 = 不配置 key） */
  apiKey?: string
  /** true = 清空 key（优先于 apiKey） */
  clearKey?: boolean
  enabled?: boolean
  sortOrder?: number
}

export interface AiModelWriteBody {
  modelId?: string
  displayName?: string
  maxContext?: number | null
  maxOutput?: number | null
  supportsToolCalling?: boolean
  supportsVision?: boolean
  supportsReasoning?: boolean
  enabled?: boolean
  isDefault?: boolean
  sortOrder?: number
}

export interface AiDefaultsWriteBody {
  modelId?: string | null
  /** 默认目标所属 provider（与 modelId 成对提交；同名模型靠它区分） */
  providerId?: number | null
  temperature?: number | null
  topP?: number | null
  maxTokens?: number | null
  allowUserParams?: boolean | null
}

const base = '/ai'

/** GET /api/ai/providers */
export function listProviders(): Promise<AiProviderView[]> {
  return get<AiProviderView[]>(`${base}/providers`)
}

/** POST /api/ai/providers */
export function createProvider(body: AiProviderWriteBody): Promise<AiProviderView> {
  return post<AiProviderView>(`${base}/providers`, body)
}

/** PUT /api/ai/providers/{id} */
export function updateProvider(id: number, body: AiProviderWriteBody): Promise<AiProviderView> {
  return put<AiProviderView>(`${base}/providers/${id}`, body)
}

/** DELETE /api/ai/providers/{id}（默认软删；hard=true 仅在无模型引用时可用） */
export function deleteProvider(id: number, hard = false): Promise<AiProviderView> {
  return del<AiProviderView>(`${base}/providers/${id}${hard ? '?hard=true' : ''}`)
}

/** POST /api/ai/providers/{id}/test */
export function testProvider(id: number): Promise<AiProbeResult> {
  return post<AiProbeResult>(`${base}/providers/${id}/test`)
}

/** POST /api/ai/providers/{id}/models/fetch?refresh= */
export function fetchModels(id: number, refresh = true): Promise<AiFetchModelsResult> {
  return post<AiFetchModelsResult>(`${base}/providers/${id}/models/fetch?refresh=${refresh ? 'true' : 'false'}`)
}

/** GET /api/ai/providers/{id}/models */
export function listModels(id: number): Promise<AiModelRow[]> {
  return get<AiModelRow[]>(`${base}/providers/${id}/models`)
}

/** POST /api/ai/providers/{id}/models */
export function addModel(id: number, body: AiModelWriteBody): Promise<AiModelRow> {
  return post<AiModelRow>(`${base}/providers/${id}/models`, body)
}

/** PUT /api/ai/providers/{id}/models/{modelId} */
export function updateModel(id: number, modelId: string, body: AiModelWriteBody): Promise<AiModelRow> {
  return put<AiModelRow>(`${base}/providers/${id}/models/${encodeURIComponent(modelId)}`, body)
}

/** DELETE /api/ai/providers/{id}/models/{modelId} */
export function deleteModel(id: number, modelId: string): Promise<AiModelRow> {
  return del<AiModelRow>(`${base}/providers/${id}/models/${encodeURIComponent(modelId)}`)
}

/** `GET /api/ai/models` 的一行（批③ 面板的选模型数据源）。
 *
 * 批 E · E1（2026-10-22 用户裁决）：对话面板的模型选择 UI 已退场，`/api/ai/models` 在
 * 前端暂无消费者 —— 后端端点保留（「后续再做模型选择」），前端接口封装随选择器一并删除，
 * 重做时按后端契约重新加回（本类型留作契约参照，不在运行期使用）。
 */
export interface AiModelView {
  /** 库内 id；null = env 合成行（尚未落库） */
  providerId: number | null
  providerCode: string
  providerName: string
  modelId: string
  displayName: string
  supportsToolCalling: boolean
  supportsVision: boolean
  supportsReasoning: boolean
  maxContext: number | null
  maxOutput: number | null
  isDefault: boolean
}

/** GET /api/ai/settings/defaults */
export function getDefaults(): Promise<AiDefaults> {
  return get<AiDefaults>(`${base}/settings/defaults`)
}

/** PUT /api/ai/settings/defaults */
export function updateDefaults(body: AiDefaultsWriteBody): Promise<AiDefaults> {
  return put<AiDefaults>(`${base}/settings/defaults`, body)
}

export const aiConfigApi = {
  listProviders,
  createProvider,
  updateProvider,
  deleteProvider,
  testProvider,
  fetchModels,
  listModels,
  addModel,
  updateModel,
  deleteModel,
  getDefaults,
  updateDefaults
}
