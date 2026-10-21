/**
 * 「AI 设置」页的全部文案与<b>错误码 → 文案</b>表（模型配置专项 §6.3 的收敛口径）。
 *
 * <p>
 * 为什么集中在一个模块：i18n 专项还没立项，但"后端只给稳定 code、文案由前端查表"这条已经定死
 * （设计稿 §3.5）。把所有新文案收敛到本文件，后置抽取零散度最低 —— <b>不要</b>把文案散写进模板。
 *
 * <p>
 * 纪律：表中没有的 code 走 {@link describeAiConfigError} 的兜底文案，<b>绝不</b>把后端的
 * `message`（中文兜底，可能含内部细节）直接显示给用户（F3 的用例锁这一条）。
 */

export const AI_SETTINGS_COPY = {
  title: 'AI 设置',
  subtitle: '端点、模型清单与全局默认参数。修改即时生效（无需重启）。',
  retry: '重试',
  loading: '加载中…',
  loadFailed: '加载失败',
  empty: '还没有配置任何端点：可以新建一个，或在环境变量里给出 AI_API_KEY 让首次启动自动种子。',
  envFallbackTitle: '当前配置来自环境变量兜底（尚未写入配置库）',
  envFallbackBody: '表里没有 provider 行，AI 仍能用是因为 env 里给了 AI_API_KEY。在这里保存一次即转为数据库配置。',
  sourceEnvSeed: '来源：环境变量种子',
  sourceDb: '来源：数据库',
  keyConfigured: '已配置',
  keyMissing: '未配置',
  keyUnreadable: '密钥不可解（KEK 变更或密文损坏）',
  keyDisabled: '已停用',
  keyLast4: '末 4 位',
  keyInputPlaceholder: '留空 = 不修改',
  save: '保存',
  saving: '保存中…',
  updateKey: '更新密钥',
  clearKey: '清除密钥',
  testConnection: '测试连接',
  testing: '测试中…',
  disable: '停用',
  enable: '启用',
  remove: '删除',
  removeConfirm: '删除后该端点不再出现在对话可选模型里。确认删除？',
  disableConfirm: '停用后该端点的模型不再可选。确认停用？',
  clearKeyConfirm: '清除后需要重新录入密钥才能使用该端点。确认清除？',
  createTitle: '新建端点',
  create: '新建',
  code: '标识（code）',
  displayName: '名称',
  baseUrl: 'Base URL',
  completionsPath: '对话路径',
  modelsPath: '模型列表路径',
  apiKey: 'API Key',
  modelsTitle: '模型清单',
  modelsEmpty: '该端点还没有模型：拉取一次，或手填一行。',
  pullModels: '拉取模型',
  pulling: '拉取中…',
  pulledEmpty: '上游没有返回任何模型，可以手填一行。',
  pulledCached: '（来自缓存）',
  addModel: '加入',
  manualAdd: '手填一行',
  modelId: '模型 ID',
  maxContext: '上下文窗口',
  maxOutput: '最大输出',
  toolCalling: '工具调用',
  vision: '视觉',
  reasoning: '推理',
  enabled: '启用',
  isDefault: '默认',
  defaultsTitle: '默认参数',
  defaultsHint: 'temperature [0,2] / top_p [0,1] / max_tokens ≥1；留空 = 用模型/环境缺省。',
  temperature: 'temperature',
  topP: 'top_p',
  maxTokens: 'max_tokens',
  allowUserParams: '允许普通用户调整',
  defaultValueModel: '默认模型',
  defaultValueModelNone: '（未设置）',
  saved: '已保存',
  probeOk: '连通，模型数',
  probeFailed: '失败'
} as const

/**
 * 稳定错误码 → 用户可读文案（设计稿 §3.5 的封闭表 + 批② 新增的校验码）。
 *
 * 每一行都写成"用户下一步该做什么"，不写内部机制（例如不要写 AAD/HMAC/Redis 键名）。
 */
export const AI_CONFIG_ERROR_TEXT: Record<string, string> = {
  AI_SECRET_KEK_MISSING: '尚未配置加密密钥（AI_SECRET_KEK），无法保存 API Key。请让管理员配置后重试。',
  KEY_EMPTY: 'API Key 为空：请填写密钥，或用「清除密钥」。',
  KEY_UNREADABLE: '库里的密钥无法解密（加密密钥变更或密文损坏）：请重新录入密钥。',
  INVALID_BASE_URL: 'Base URL 不合法：必须是 http:// 或 https:// 开头的完整地址，且不能带账号密码。',
  BLOCKED_PRIVATE_ENDPOINT: '该地址指向私网/回环地址，默认禁止。确为内网网关时请联系管理员开启白名单开关。',
  PROVIDER_NOT_FOUND: '该端点不存在（可能已被删除）：刷新后再试。',
  PROVIDER_IN_USE: '该端点下仍有模型，不能删除；可以先删除模型，或改用停用。',
  PROVIDER_EXISTS: '这个标识（code）已被占用，换一个。',
  CODE_IMMUTABLE: '端点标识创建后不可修改，请另建一个端点。',
  MODEL_NOT_FOUND: '模型不存在：请先拉取模型或手填该模型。',
  MODEL_EXISTS: '该模型已经在清单里了。',
  MODEL_TOOLCALL_UNSUPPORTED: '该模型不支持工具调用。',
  MODELS_UNSUPPORTED: '该端点没有实现 /models，请手填模型 ID。',
  MODELS_INVALID: '该端点的 /models 返回格式不认识，请手填模型 ID。',
  AUTH_FAILED: '鉴权失败，请检查 API Key。',
  UNREACHABLE: '网络不可达或超时，请检查地址与网络。',
  PROVIDER_DISABLED: '该端点已停用。',
  PROVIDER_GONE: '该端点已被删除。',
  PARAMS_LOCKED: '当前配置不允许调整参数。',
  INVALID_PARAM: '参数不在允许范围内，请检查后重试。',
  INVALID_MODEL: '模型 ID 不合法：不能为空、不能含空格。',
  NETWORK_ERROR: '后端不可达，请确认服务已启动。',
  UNKNOWN_ERROR: '操作失败，请重试。'
}

/** 兜底文案（未知 code / 没有 code 时使用）。 */
export const AI_CONFIG_ERROR_FALLBACK = AI_CONFIG_ERROR_TEXT.UNKNOWN_ERROR

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
  const code = errorCodeOf(error)
  if (code && AI_CONFIG_ERROR_TEXT[code]) {
    return AI_CONFIG_ERROR_TEXT[code]
  }
  return AI_CONFIG_ERROR_FALLBACK
}
