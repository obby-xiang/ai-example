import { translate } from '@/composables/useCopy'

/**
 * 「AI 设置」页的**文案键表**（模型配置专项 §6.3 的收敛口径；i18n L-69 起这里存 locale key，
 * 中文/英文实体在 `src/locales/*.json` 的 `ai.settings.copy.*`）。
 *
 * <p>
 * 为什么集中在一个模块：i18n 专项已经把「后端只给稳定 code、文案由前端查表」定死（设计稿 §3.5）；
 * 键表收敛在一处，加一条文案只需动「键表 + 两个 locale 文件」，零散度最低 —— <b>不要</b>把文案散写进模板。
 *
 * <p>
 * 纪律：键表里没有的 code 走 {@link describeAiConfigError} 的兜底文案，<b>绝不</b>把后端的
 * `message`（中文兜底，可能含内部细节）直接显示给用户（F3 的用例锁这一条）。
 */
const COPY_KEYS = {
  title: 'ai.settings.copy.title',
  subtitle: 'ai.settings.copy.subtitle',
  retry: 'ai.settings.copy.retry',
  loading: 'ai.settings.copy.loading',
  loadFailed: 'ai.settings.copy.loadFailed',
  empty: 'ai.settings.copy.empty',
  envFallbackTitle: 'ai.settings.copy.envFallbackTitle',
  envFallbackBody: 'ai.settings.copy.envFallbackBody',
  sourceEnvSeed: 'ai.settings.copy.sourceEnvSeed',
  sourceDb: 'ai.settings.copy.sourceDb',
  keyConfigured: 'ai.settings.copy.keyConfigured',
  keyMissing: 'ai.settings.copy.keyMissing',
  keyUnreadable: 'ai.settings.copy.keyUnreadable',
  keyDisabled: 'ai.settings.copy.keyDisabled',
  keyLast4: 'ai.settings.copy.keyLast4',
  keyInputPlaceholder: 'ai.settings.copy.keyInputPlaceholder',
  save: 'ai.settings.copy.save',
  saving: 'ai.settings.copy.saving',
  updateKey: 'ai.settings.copy.updateKey',
  clearKey: 'ai.settings.copy.clearKey',
  testConnection: 'ai.settings.copy.testConnection',
  testing: 'ai.settings.copy.testing',
  disable: 'ai.settings.copy.disable',
  enable: 'ai.settings.copy.enable',
  remove: 'ai.settings.copy.remove',
  removeConfirm: 'ai.settings.copy.removeConfirm',
  disableConfirm: 'ai.settings.copy.disableConfirm',
  clearKeyConfirm: 'ai.settings.copy.clearKeyConfirm',
  createTitle: 'ai.settings.copy.createTitle',
  create: 'ai.settings.copy.create',
  code: 'ai.settings.copy.code',
  displayName: 'ai.settings.copy.displayName',
  baseUrl: 'ai.settings.copy.baseUrl',
  completionsPath: 'ai.settings.copy.completionsPath',
  modelsPath: 'ai.settings.copy.modelsPath',
  apiKey: 'ai.settings.copy.apiKey',
  modelsTitle: 'ai.settings.copy.modelsTitle',
  modelsEmpty: 'ai.settings.copy.modelsEmpty',
  pullModels: 'ai.settings.copy.pullModels',
  pulling: 'ai.settings.copy.pulling',
  pulledEmpty: 'ai.settings.copy.pulledEmpty',
  pulledCached: 'ai.settings.copy.pulledCached',
  addModel: 'ai.settings.copy.addModel',
  manualAdd: 'ai.settings.copy.manualAdd',
  modelId: 'ai.settings.copy.modelId',
  maxContext: 'ai.settings.copy.maxContext',
  maxOutput: 'ai.settings.copy.maxOutput',
  toolCalling: 'ai.settings.copy.toolCalling',
  vision: 'ai.settings.copy.vision',
  reasoning: 'ai.settings.copy.reasoning',
  enabled: 'ai.settings.copy.enabled',
  isDefault: 'ai.settings.copy.isDefault',
  defaultsTitle: 'ai.settings.copy.defaultsTitle',
  defaultsHint: 'ai.settings.copy.defaultsHint',
  temperature: 'ai.settings.copy.temperature',
  topP: 'ai.settings.copy.topP',
  maxTokens: 'ai.settings.copy.maxTokens',
  allowUserParams: 'ai.settings.copy.allowUserParams',
  defaultValueModel: 'ai.settings.copy.defaultValueModel',
  defaultValueModelNone: 'ai.settings.copy.defaultValueModelNone',
  defaultValueModelUnbound: 'ai.settings.copy.defaultValueModelUnbound',
  defaultValueModelUnavailable: 'ai.settings.copy.defaultValueModelUnavailable',
  saved: 'ai.settings.copy.saved',
  probeOk: 'ai.settings.copy.probeOk',
  probeFailed: 'ai.settings.copy.probeFailed'
} as const

export type AiSettingsCopyKey = keyof typeof COPY_KEYS

/**
 * 「AI 设置」页的文案对象（模板里照旧写 `T.title`）。
 *
 * <p>
 * 每个属性都是 **getter**：取值时才翻译 ⇒ 模板渲染期访问会订阅 locale（切语言即重算），
 * 脚本里 `T.saved` 则取调用时刻的语言（一次性 toast 的正确语义）。这样模板侧不用逐处改写成
 * `t(T.xxx)`，键表仍是唯一事实源。
 */
export function useAiSettingsCopy(): Readonly<Record<AiSettingsCopyKey, string>> {
  const copy = {} as Record<AiSettingsCopyKey, string>
  for (const key of Object.keys(COPY_KEYS) as AiSettingsCopyKey[]) {
    Object.defineProperty(copy, key, {
      enumerable: true,
      get: () => translate(COPY_KEYS[key])
    })
  }
  return copy as Readonly<Record<AiSettingsCopyKey, string>>
}

/**
 * 稳定错误码 → **locale key**（`ai.settings.errors.*`；设计稿 §3.5 的封闭表 + 批② 新增的校验码）。
 *
 * 每一行文案都写成"用户下一步该做什么"，不写内部机制（例如不要写 AAD/HMAC/Redis 键名）。
 */
const ERROR_KEYS: Record<string, string> = {
  AI_SECRET_KEK_MISSING: 'ai.settings.errors.AI_SECRET_KEK_MISSING',
  KEY_EMPTY: 'ai.settings.errors.KEY_EMPTY',
  // /test 与 /models/fetch 也会回这个码（M-1）：探活前就短路，不再拿 null key 打上游
  KEY_UNREADABLE: 'ai.settings.errors.KEY_UNREADABLE',
  // I-3：可用性 503 / 探测 / 模型拉取的真实码是 AI_KEY_UNREADABLE，与配置路径同义
  // （同一句可操作文案；旧码保留兼容）。
  AI_KEY_UNREADABLE: 'ai.settings.errors.KEY_UNREADABLE',
  INVALID_BASE_URL: 'ai.settings.errors.INVALID_BASE_URL',
  BLOCKED_PRIVATE_ENDPOINT: 'ai.settings.errors.BLOCKED_PRIVATE_ENDPOINT',
  PROVIDER_NOT_FOUND: 'ai.settings.errors.PROVIDER_NOT_FOUND',
  PROVIDER_IN_USE: 'ai.settings.errors.PROVIDER_IN_USE',
  PROVIDER_EXISTS: 'ai.settings.errors.PROVIDER_EXISTS',
  CODE_IMMUTABLE: 'ai.settings.errors.CODE_IMMUTABLE',
  MODEL_NOT_FOUND: 'ai.settings.errors.MODEL_NOT_FOUND',
  MODEL_EXISTS: 'ai.settings.errors.MODEL_EXISTS',
  MODEL_TOOLCALL_UNSUPPORTED: 'ai.settings.errors.MODEL_TOOLCALL_UNSUPPORTED',
  MODELS_UNSUPPORTED: 'ai.settings.errors.MODELS_UNSUPPORTED',
  MODELS_INVALID: 'ai.settings.errors.MODELS_INVALID',
  AUTH_FAILED: 'ai.settings.errors.AUTH_FAILED',
  UNREACHABLE: 'ai.settings.errors.UNREACHABLE',
  PROVIDER_DISABLED: 'ai.settings.errors.PROVIDER_DISABLED',
  PROVIDER_GONE: 'ai.settings.errors.PROVIDER_GONE',
  PARAMS_LOCKED: 'ai.settings.errors.PARAMS_LOCKED',
  INVALID_PARAM: 'ai.settings.errors.INVALID_PARAM',
  INVALID_MODEL: 'ai.settings.errors.INVALID_MODEL',
  NETWORK_ERROR: 'ai.settings.errors.NETWORK_ERROR',
  UNKNOWN_ERROR: 'ai.settings.errors.UNKNOWN_ERROR'
}

/** 兜底文案键（未知 code / 没有 code 时使用）。 */
const ERROR_FALLBACK_KEY = 'ai.settings.errors.UNKNOWN_ERROR'

/** 已知 code 的文案键；未知 code 返回 null（调用方回落到后端 `message` 原文）。 */
export function aiConfigErrorKey(code: string | null | undefined): string | null {
  return code ? ERROR_KEYS[code] ?? null : null
}

/** 已知 code 的用户可读文案（未知 code 返回兜底文案）。 */
export function aiConfigErrorText(code: string | null | undefined): string {
  const key = aiConfigErrorKey(code)
  return translate(key ?? ERROR_FALLBACK_KEY)
}

/**
 * 删除被"仍有模型引用"挡住后的回落提示（复审 M-2）：明确告诉用户**做了哪一步**。
 *
 * <p>
 * 只写"删除失败"会让人以为端点还在、再点一次；写"已停用"又不说原因会让人以为删成功了。
 * 两者都写：为什么不能删 + 现在是什么状态。
 */
export function softDeleteHint(modelCount: number): string {
  return translate('ai.settings.softDeleteHint', { count: modelCount })
}

/** 读一个错误的稳定 code（`ApiError` 有 code；普通 Error 没有）。 */
export function errorCodeOf(error: unknown): string | null {
  if (error && typeof error === 'object' && 'code' in error) {
    const code = (error as { code?: unknown }).code
    if (typeof code === 'string' && code.length > 0) {
      return code
    }
  }
  return null
}

/**
 * 把一个错误翻成用户文案。
 *
 * <p>
 * <b>绝不</b>回显后端 `message`：它是中文兜底（设计稿 §3.5），可能带内部细节；这里只认 code。
 */
export function describeAiConfigError(error: unknown): string {
  return aiConfigErrorText(errorCodeOf(error))
}
