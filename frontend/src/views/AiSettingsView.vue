<script setup lang="ts">
import { useCopy } from '@/composables/useCopy'
/**
 * 「AI 设置」页（模型配置专项 §3.7，批②）——三段式：
 * ① provider 卡片（名称/协议/Base URL/路径/密钥/启停/测试连接/来源标签）
 * ② 模型清单（拉取或手填 + 能力位 + 窗口）
 * ③ 全局默认参数（temperature / top_p / max_tokens / 允许用户调整）
 *
 * <h2>交互口径</h2>
 * <ul>
 * <li>三态仿 `AiFeedbackView`：loading / error / empty（错误文案按 <b>code</b> 查表，不显示后端中文）；</li>
 * <li>密钥输入 `type=password` + `autocomplete="new-password"`，<b>不做</b>显示明文；输入框留空 =
 * 不修改（提交体里<b>根本不带</b> `apiKey` 键）；</li>
 * <li>删除 / 停用 / 清除密钥都要二次确认；</li>
 * <li>env 兜底提示（批① 复审登记 M5）：`/health.providers` 里有合成行（`synthetic=true`，
 * `id=null`）时，明示"当前配置来自环境变量兜底、还没落库"—— 否则用户看到"库是空的"却仍能用 AI，
 * 完全不知道自己在改哪一层。</li>
 * </ul>
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiApi } from '@/api/ai'
import type { AiHealth } from '@/types/ai'
import { aiConfigApi, type AiDefaults, type AiModelRow, type AiProbeResult, type AiProviderView } from '@/api/aiConfig'
import { useAiSettingsCopy, describeAiConfigError, errorCodeOf, softDeleteHint } from '@/constants/ai-settings-copy'
import { useWorkspaceStore } from '@/stores/workspace'

const { t } = useCopy()
/** 文案对象：属性是 getter（取值时按当前语言翻译），模板里照旧写 `T.title`。 */
const T = useAiSettingsCopy()

const workspace = useWorkspaceStore()

const loading = ref(true)
const loadError = ref<unknown>(null)
const providers = ref<AiProviderView[]>([])
const defaults = ref<AiDefaults | null>(null)
const health = ref<AiHealth | null>(null)

/** 每个 provider 的编辑态（key 单独放：留空 = 不改）。 */
interface CardForm {
  displayName: string
  baseUrl: string
  completionsPath: string
  modelsPath: string
  key: string
}
const cardForms = reactive<Record<number, CardForm>>({})
const probeResults = reactive<Record<number, AiProbeResult | null | undefined>>({})
const busy = reactive<Record<string, boolean>>({})

const selectedProviderId = ref<number | null>(null)
const fetchedModels = ref<string[]>([])
const fetchedCached = ref(false)

const createForm = reactive({ code: '', displayName: '', baseUrl: '', apiKey: '', modelsPath: '/models' })
const manualForm = reactive({ modelId: '', maxContext: '', maxOutput: '' })

const defaultsForm = reactive({
  /** 默认模型下拉的选中值 = `${providerId}::${modelId}`（同名模型靠 providerId 区分） */
  target: '',
  temperature: '',
  topP: '',
  maxTokens: '',
  allowUserParams: true
})

/** env 兜底合成行（M5）：表为空但 env 有 key 时 /health 会多出这一行。 */
const syntheticProviders = computed(() => (health.value?.providers ?? []).filter((p) => p.synthetic === true))

const selectedProvider = computed(
  () => providers.value.find((p) => p.id === selectedProviderId.value) ?? providers.value[0] ?? null
)

const allModels = computed(() =>
  providers.value
    .filter((p) => p.enabled)
    .flatMap((p) => p.models.filter((m) => m.enabled).map((m) => ({ provider: p, model: m })))
)

const isEmpty = computed(() => providers.value.length === 0)

function isBusy(key: string): boolean {
  return busy[key] === true
}

async function withBusy(key: string, action: () => Promise<void>): Promise<void> {
  busy[key] = true
  try {
    await action()
  } finally {
    busy[key] = false
  }
}

function fillForms(): void {
  for (const provider of providers.value) {
    cardForms[provider.id] = {
      displayName: provider.displayName,
      baseUrl: provider.baseUrl,
      completionsPath: provider.completionsPath,
      modelsPath: provider.modelsPath,
      key: ''
    }
  }
}

/** 默认模型选项的值：providerId + modelId 的二元组（同名模型不会串台）。 */
function modelTargetKey(providerId: number, modelId: string): string {
  return `${providerId}::${modelId}`
}

/** 旧配置（只有 modelId、无法唯一归属）的临时选项前缀：保留原值，保存时按未绑定提交。 */
const LEGACY_TARGET_PREFIX = 'legacy::'

/** 已失效绑定（provider 停用/不存在或模型停用/删除）的临时选项前缀：显式展示，不空选中。 */
const INVALID_TARGET_PREFIX = 'invalid::'

/** 绑定的目标当前是否真的可选（provider 已启用 + 模型已启用）。 */
function isSelectableTarget(providerId: number, modelId: string): boolean {
  const provider = providers.value.find((item) => item.id === providerId)
  return provider?.enabled === true && provider.models.some((model) => model.enabled && model.modelId === modelId)
}

/**
 * 回显时把 `(providerId?, modelId?)` 还原成下拉值。
 *
 * - 绑定了 provider：目标不可选（provider 停用/不存在、模型停用/删除）⇒ 显式的"失效"选项；
 * - 旧配置只有 modelId：唯一所有者 ⇒ 直接映射；两家同名/已不存在 ⇒ 显式的"未绑定"选项。
 * 两种情况都不替用户猜边，也不在只改温度时把默认模型静默清掉。
 */
function defaultTargetKeyOf(providerId: number | null | undefined, modelId: string | null | undefined): string {
  if (!modelId) {
    return ''
  }
  if (providerId != null) {
    return isSelectableTarget(providerId, modelId)
      ? modelTargetKey(providerId, modelId)
      : `${INVALID_TARGET_PREFIX}${providerId}::${modelId}`
  }
  const owners = providers.value.filter((provider) =>
    provider.enabled && provider.models.some((model) => model.enabled && model.modelId === modelId)
  )
  return owners.length === 1
    ? modelTargetKey(owners[0].id, modelId)
    : `${LEGACY_TARGET_PREFIX}${modelId}`
}

/** 当前回显是否停在"旧配置、未绑定"的临时选项上（模板据此渲染那一行）。 */
const legacyDefaultModelId = computed(() => {
  if (defaultsForm.target.startsWith(LEGACY_TARGET_PREFIX)) {
    return defaultsForm.target.slice(LEGACY_TARGET_PREFIX.length)
  }
  return ''
})

/** 当前回显是否停在"绑定已失效"的临时选项上；失效项的保存由后端 fail-closed 拒绝。 */
const invalidDefaultTarget = computed(() => {
  if (!defaultsForm.target.startsWith(INVALID_TARGET_PREFIX)) {
    return null
  }
  const rest = defaultsForm.target.slice(INVALID_TARGET_PREFIX.length)
  const separator = rest.indexOf('::')
  const providerId = Number(rest.slice(0, separator))
  const modelId = rest.slice(separator + 2)
  const provider = providers.value.find((item) => item.id === providerId)
  const providerName = provider?.displayName ?? `#${providerId}`
  return { key: defaultsForm.target, modelId, providerName }
})

/** 下拉值 → 提交二元组（空 = 两项都清掉）。 */
function splitDefaultTarget(target: string): { providerId: number | null; modelId: string | null } {
  if (target === '') {
    return { providerId: null, modelId: null }
  }
  if (target.startsWith(LEGACY_TARGET_PREFIX)) {
    return { providerId: null, modelId: target.slice(LEGACY_TARGET_PREFIX.length) }
  }
  const raw = target.startsWith(INVALID_TARGET_PREFIX)
    ? target.slice(INVALID_TARGET_PREFIX.length)
    : target
  const separator = raw.indexOf('::')
  if (separator < 0) {
    return { providerId: null, modelId: raw }
  }
  const providerId = Number(raw.slice(0, separator))
  return {
    providerId: Number.isFinite(providerId) ? providerId : null,
    modelId: raw.slice(separator + 2)
  }
}

function fillDefaults(): void {
  const current = defaults.value
  defaultsForm.target = defaultTargetKeyOf(current?.providerId, current?.modelId)
  defaultsForm.temperature = current?.temperature == null ? '' : String(current.temperature)
  defaultsForm.topP = current?.topP == null ? '' : String(current.topP)
  defaultsForm.maxTokens = current?.maxTokens == null ? '' : String(current.maxTokens)
  defaultsForm.allowUserParams = current?.allowUserParams !== false
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = null
  try {
    providers.value = await aiConfigApi.listProviders()
    defaults.value = await aiConfigApi.getDefaults()
    fillForms()
    fillDefaults()
    if (selectedProviderId.value == null && providers.value.length > 0) {
      selectedProviderId.value = providers.value[0].id
    }
    // /health 只用于"env 兜底未落库"的提示（M5）：它 503 也是正常语义，失败不影响本页
    try {
      health.value = await aiApi.health()
    } catch {
      health.value = null
    }
  } catch (error) {
    loadError.value = error
  } finally {
    loading.value = false
  }
}

/**
 * 进页即声明工作区上下文（批次5A：此前本页**完全没有** enterPage）。
 *
 * <p>为什么必须声明：从导出/导入向导点进设置页时，工作区仍停在上一页 ——
 * `taskType + step` 让后端按 `task:*` 披露工具（模型以为用户还在向导里），旧 `taskId` /
 * `taskStatus` 还会让只读守卫拿别的任务的终态误拒写事件。设置页不属于五页工作区，
 * 这里把工作区**复位到默认页（任务中心，同时也是后端 `page` 白名单的默认口径）**并清空全部任务态。
 *
 * <p>为什么是「任务中心」而不是一个 `settings` 页 id：后端 page 是**闭集**（`ContextFieldGate` 的
 * 白名单 = `ContextBuilder.PAGE_LABELS` 的 tasks / export / import / definitions / data，两侧须同改），
 * 未知 id 会被降级成 `unknown`（每轮一条降级日志、提示词里变成「未知页面」）。故设置类页面
 * （AI 设置 / AI 反馈）统一复位到默认页 —— **不虚增页面 id**；要真加 `settings`，须后端白名单、
 * `PAGE_LABELS` 与 ToolScope 同批改，并同步 `types/workspace.ts#WorkspaceSnapshot.page` 的登记。
 *
 * <p>边界：只复位页面级事实；`dataVersion` / `recentActions` 时间线与契约版本由 store 保留
 * （见 `stores/workspace.ts#enterPage`）。
 */
function declareWorkspaceContext(): void {
  workspace.enterPage({
    pageId: 'tasks',
    page: 'tasks',
    taskType: null,
    step: null,
    taskId: null,
    taskStatus: null,
    selectedDefs: [],
    queryConditions: {},
    importMode: null
  })
}

onMounted(() => {
  declareWorkspaceContext()
  void load()
})

// ── provider 卡片 ──────────────────────────────────────────────────────────

function keyLabel(provider: AiProviderView): string {
  if (!provider.enabled) {
    return T.keyDisabled
  }
  if (provider.keyStatus === 'UNREADABLE') {
    return T.keyUnreadable
  }
  if (!provider.keyConfigured) {
    return T.keyMissing
  }
  return provider.keyLast4 ? `${T.keyConfigured} · ${T.keyLast4} ${provider.keyLast4}` : T.keyConfigured
}

function sourceLabel(provider: AiProviderView): string {
  return provider.source === 'ENV_SEED' ? T.sourceEnvSeed : T.sourceDb
}

/**
 * 提交体：<b>只带变化字段</b>（批③ I-3 的前端半边）。
 *
 * <h2>为什么不能整张卡片回传</h2>
 * 一次"只改显示名"的保存若也带上 `baseUrl`，后端就要对<b>没变过</b>的地址重跑 URL 闸 ——
 * env 种子的本地桩（http://127.0.0.1:…）因此永远 400 `BLOCKED_PRIVATE_ENDPOINT`，设置页表现为
 * "改不动"。后端已同步改成"地址没变就跳过闸"（双保险）；前端这一侧按变化字段提交，
 * 从源头就不产生"复审既有地址"的请求。
 *
 * <p>key 仍按既有口径：留空 ⇒ 不出现 `apiKey` 键（F2）；填了 ⇒ 只带 `apiKey`。
 */
function providerBody(provider: AiProviderView) {
  const form = cardForms[provider.id]
  const body: {
    displayName?: string
    baseUrl?: string
    completionsPath?: string
    modelsPath?: string
    apiKey?: string
  } = {}
  const displayName = form?.displayName ?? provider.displayName
  const baseUrl = form?.baseUrl ?? provider.baseUrl
  const completionsPath = form?.completionsPath ?? provider.completionsPath
  const modelsPath = form?.modelsPath ?? provider.modelsPath
  if (displayName !== provider.displayName) {
    body.displayName = displayName
  }
  if (baseUrl !== provider.baseUrl) {
    body.baseUrl = baseUrl
  }
  if (completionsPath !== provider.completionsPath) {
    body.completionsPath = completionsPath
  }
  if (modelsPath !== provider.modelsPath) {
    body.modelsPath = modelsPath
  }
  const key = (form?.key ?? '').trim()
  if (key !== '') {
    body.apiKey = key
  }
  return body
}

async function saveProvider(provider: AiProviderView): Promise<void> {
  await withBusy(`save-${provider.id}`, async () => {
    try {
      await aiConfigApi.updateProvider(provider.id, providerBody(provider))
      ElMessage.success(T.saved)
      await load()
    } catch (error) {
      ElMessage.error(describeAiConfigError(error))
    }
  })
}

async function clearKey(provider: AiProviderView): Promise<void> {
  const confirmed = await confirm(T.clearKeyConfirm)
  if (!confirmed) {
    return
  }
  await withBusy(`clear-${provider.id}`, async () => {
    try {
      await aiConfigApi.updateProvider(provider.id, { clearKey: true })
      ElMessage.success(T.saved)
      await load()
    } catch (error) {
      ElMessage.error(describeAiConfigError(error))
    }
  })
}

async function toggleEnabled(provider: AiProviderView): Promise<void> {
  if (provider.enabled) {
    const confirmed = await confirm(T.disableConfirm)
    if (!confirmed) {
      return
    }
  }
  await withBusy(`toggle-${provider.id}`, async () => {
    try {
      await aiConfigApi.updateProvider(provider.id, { enabled: !provider.enabled })
      await load()
    } catch (error) {
      ElMessage.error(describeAiConfigError(error))
    }
  })
}

/**
 * 删除端点：**先试硬删**（{@code hard=true}），被"仍有模型引用"挡住（409 {@code PROVIDER_IN_USE}）
 * 时回落软删（{@code enabled=false}）并明说走了哪一步（复审 M-2）。
 *
 * <p>
 * 为什么不是"一律软删"：用户的删除意图是"这个端点不该再出现"；只留一行 disabled 的孤儿配置
 * 会让人以为删失败了。硬删优先 + 明确回落，既尊重意图又不会悄悄丢掉模型清单。
 */
async function removeProvider(provider: AiProviderView): Promise<void> {
  const confirmed = await confirm(T.removeConfirm)
  if (!confirmed) {
    return
  }
  await withBusy(`remove-${provider.id}`, async () => {
    try {
      await aiConfigApi.deleteProvider(provider.id, true)
      await load()
    } catch (error) {
      if (errorCodeOf(error) !== 'PROVIDER_IN_USE') {
        ElMessage.error(describeAiConfigError(error))
        return
      }
      try {
        await aiConfigApi.deleteProvider(provider.id, false)
        ElMessage.warning(softDeleteHint(provider.models.length))
        await load()
      } catch (fallbackError) {
        ElMessage.error(describeAiConfigError(fallbackError))
      }
    }
  })
}

async function testConnection(provider: AiProviderView): Promise<void> {
  await withBusy(`test-${provider.id}`, async () => {
    try {
      probeResults[provider.id] = await aiConfigApi.testProvider(provider.id)
    } catch (error) {
      probeResults[provider.id] = null
      ElMessage.error(describeAiConfigError(error))
    }
  })
}

function probeText(result: AiProbeResult | null | undefined): string {
  if (!result) {
    return ''
  }
  if (result.ok) {
    return `${T.probeOk} ${result.modelCount}（${result.latencyMs}ms）`
  }
  return `${T.probeFailed}：${describeAiConfigError({ code: result.code ?? undefined })}`
}

async function createProvider(): Promise<void> {
  await withBusy('create', async () => {
    try {
      const body: Record<string, unknown> = {
        code: createForm.code.trim(),
        displayName: createForm.displayName.trim(),
        baseUrl: createForm.baseUrl.trim()
      }
      if (createForm.modelsPath.trim() !== '') {
        body.modelsPath = createForm.modelsPath.trim()
      }
      if (createForm.apiKey.trim() !== '') {
        body.apiKey = createForm.apiKey.trim()
      }
      const created = await aiConfigApi.createProvider(body)
      selectedProviderId.value = created.id
      createForm.code = ''
      createForm.displayName = ''
      createForm.baseUrl = ''
      createForm.apiKey = ''
      await load()
    } catch (error) {
      ElMessage.error(describeAiConfigError(error))
    }
  })
}

// ── 模型清单 ───────────────────────────────────────────────────────────────

/**
 * 拉取模型清单（按 **provider 归属**）。
 *
 * <p>为什么必须钉住归属：`/models` 是慢请求（外网端点），用户点完「拉取」再切到另一个
 * provider 是常态。旧实现把响应无条件写进全局 `fetchedModels` —— A 的模型清单会挂在 B 的
 * 卡片下，用户接着点「添加」就把 A 的模型名加到了 B 上（"provider 切换串模型"）。
 * 现在请求前记住目标 provider，响应回来时该 provider 仍是当前选中的才落地；busy 也按
 * provider 分键（A 在飞不挡 B 的按钮，A 回来也不会替 B 解除 loading）。
 */
async function pullModels(): Promise<void> {
  const provider = selectedProvider.value
  if (!provider) {
    return
  }
  const forProviderId = provider.id
  await withBusy(pullBusyKey(forProviderId), async () => {
    try {
      const result = await aiConfigApi.fetchModels(forProviderId, true)
      if (selectedProvider.value?.id !== forProviderId) {
        return // 已切到别的 provider：这份清单属于旧 provider，不得挂到当前卡片下
      }
      fetchedModels.value = result.models
      fetchedCached.value = result.cached
      if (!result.ok) {
        ElMessage.error(describeAiConfigError({ code: result.code ?? undefined }))
      } else if (result.models.length === 0) {
        ElMessage.warning(T.pulledEmpty)
      }
    } catch (error) {
      if (selectedProvider.value?.id !== forProviderId) {
        return // 旧 provider 的失败不弹在当前卡片上
      }
      ElMessage.error(describeAiConfigError(error))
    }
  })
}

/** 「拉取模型」的 busy 键（按 provider 分键，见 {@link pullModels}）。 */
function pullBusyKey(providerId: number): string {
  return `pull-${providerId}`
}

/** 当前选中 provider 是否正在拉取（模板用；选中项为空 = 未在拉取）。 */
const pulling = computed(() => (selectedProvider.value ? isBusy(pullBusyKey(selectedProvider.value.id)) : false))

/**
 * 切换 provider ⇒ 清掉上一个 provider 的「可添加模型」清单。
 *
 * 那份清单是**另一个端点**的事实（`/models` 逐 provider 返回），留在新卡片下再点「添加」
 * 就会把旧 provider 的模型名加到新 provider 上 —— 这正是"切换串模型"的另一半。
 */
watch(selectedProviderId, () => {
  fetchedModels.value = []
  fetchedCached.value = false
})

async function addFetched(modelId: string): Promise<void> {
  const provider = selectedProvider.value
  if (!provider) {
    return
  }
  await withBusy(`add-${modelId}`, async () => {
    try {
      await aiConfigApi.addModel(provider.id, { modelId })
      await load()
    } catch (error) {
      ElMessage.error(describeAiConfigError(error))
    }
  })
}

async function addManualModel(): Promise<void> {
  const provider = selectedProvider.value
  if (!provider) {
    return
  }
  await withBusy('manual', async () => {
    try {
      const body: Record<string, unknown> = { modelId: manualForm.modelId.trim() }
      if (manualForm.maxContext.trim() !== '') {
        body.maxContext = Number(manualForm.maxContext)
      }
      if (manualForm.maxOutput.trim() !== '') {
        body.maxOutput = Number(manualForm.maxOutput)
      }
      await aiConfigApi.addModel(provider.id, body)
      manualForm.modelId = ''
      manualForm.maxContext = ''
      manualForm.maxOutput = ''
      await load()
    } catch (error) {
      ElMessage.error(describeAiConfigError(error))
    }
  })
}

async function saveModelRow(model: AiModelRow): Promise<void> {
  const provider = selectedProvider.value
  if (!provider) {
    return
  }
  await withBusy(`model-${model.id}`, async () => {
    try {
      await aiConfigApi.updateModel(provider.id, model.modelId, {
        maxContext: model.maxContext,
        maxOutput: model.maxOutput,
        supportsToolCalling: model.supportsToolCalling,
        supportsVision: model.supportsVision,
        supportsReasoning: model.supportsReasoning,
        enabled: model.enabled,
        isDefault: model.isDefault
      })
      ElMessage.success(T.saved)
      await load()
    } catch (error) {
      ElMessage.error(describeAiConfigError(error))
    }
  })
}

async function removeModelRow(model: AiModelRow): Promise<void> {
  const provider = selectedProvider.value
  if (!provider) {
    return
  }
  const confirmed = await confirm(T.removeConfirm)
  if (!confirmed) {
    return
  }
  await withBusy(`model-${model.id}`, async () => {
    try {
      await aiConfigApi.deleteModel(provider.id, model.modelId)
      await load()
    } catch (error) {
      ElMessage.error(describeAiConfigError(error))
    }
  })
}

// ── 默认参数 ───────────────────────────────────────────────────────────────

function numberOrNull(raw: string): number | null {
  const trimmed = raw.trim()
  if (trimmed === '') {
    return null
  }
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : null
}

async function saveDefaults(): Promise<void> {
  await withBusy('defaults', async () => {
    try {
      const target = splitDefaultTarget(defaultsForm.target)
      defaults.value = await aiConfigApi.updateDefaults({
        providerId: target.providerId,
        modelId: target.modelId,
        temperature: numberOrNull(defaultsForm.temperature),
        topP: numberOrNull(defaultsForm.topP),
        maxTokens: numberOrNull(defaultsForm.maxTokens),
        allowUserParams: defaultsForm.allowUserParams
      })
      fillDefaults()
      ElMessage.success(T.saved)
    } catch (error) {
      ElMessage.error(describeAiConfigError(error))
    }
  })
}

async function confirm(message: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, T.title, { type: 'warning' })
    return true
  } catch {
    return false
  }
}
</script>

<template>
  <div class="p-4 max-w-5xl" data-testid="ai-settings-view">
    <h3 class="text-base font-semibold mb-1">{{ T.title }}</h3>
    <p class="text-xs text-[#909399] mb-3">{{ T.subtitle }}</p>

    <div v-if="loading" class="text-sm text-[#909399]" data-testid="ai-settings-loading">{{ T.loading }}</div>
    <div
      v-else-if="loadError"
      class="text-sm text-[#f56c6c] px-3 py-2 rounded bg-[#fef0f0]"
      data-testid="ai-settings-error"
    >
      {{ T.loadFailed }}：{{ describeAiConfigError(loadError) }}
      <el-button size="small" class="ml-2" data-testid="ai-settings-retry" @click="load">{{ T.retry }}</el-button>
    </div>

    <template v-else>
      <!-- M5：env 兜底未落库 -->
      <div
        v-if="syntheticProviders.length > 0"
        class="mb-3 px-3 py-2 rounded bg-[#fdf6ec] text-[12px] text-[#b88230]"
        data-testid="env-fallback-hint"
      >
        <div class="font-medium">{{ T.envFallbackTitle }}</div>
        <div>{{ T.envFallbackBody }}</div>
        <div class="mt-1">
          <span v-for="item in syntheticProviders" :key="item.code" class="mr-2">
            {{ item.name }}（{{ T.sourceEnvSeed }} · {{ item.keyRef }}）
          </span>
        </div>
      </div>

      <!-- ① provider 卡片 -->
      <section class="mb-5" data-testid="provider-section">
        <h4 class="text-sm font-medium mb-2">{{ t('views.aiSettings.endpoints') }}</h4>
        <p v-if="isEmpty" class="text-sm text-[#909399]" data-testid="ai-settings-empty">{{ T.empty }}</p>
        <div
          v-for="provider in providers"
          :key="provider.id"
          class="border border-solid border-[#ebeef5] rounded p-3 mb-3"
          data-testid="provider-card"
        >
          <div class="flex items-center justify-between mb-2">
            <div class="text-sm font-medium">
              {{ provider.displayName }}
              <span class="ml-2 text-[11px] text-[#909399]">{{ provider.code }} · {{ provider.protocol }}</span>
            </div>
            <div class="text-[11px]">
              <span class="mr-2 px-1.5 py-0.5 rounded bg-[#f4f4f5] text-[#909399]" data-testid="provider-source">
                {{ sourceLabel(provider) }}
              </span>
              <span
                class="px-1.5 py-0.5 rounded"
                :class="provider.keyStatus === 'OK' ? 'bg-[#f0f9eb] text-[#67c23a]' : 'bg-[#fef0f0] text-[#f56c6c]'"
                data-testid="provider-key"
              >
                {{ keyLabel(provider) }}
              </span>
            </div>
          </div>

          <div class="grid grid-cols-2 gap-2 text-[12px]">
            <label class="flex items-center gap-2">
              <span class="text-[#909399] w-20 shrink-0">{{ T.displayName }}</span>
              <input v-model="cardForms[provider.id].displayName" data-testid="provider-display-name" class="flex-1 border border-solid border-[#dcdfe6] rounded px-2 py-1" />
            </label>
            <label class="flex items-center gap-2">
              <span class="text-[#909399] w-20 shrink-0">{{ T.baseUrl }}</span>
              <input v-model="cardForms[provider.id].baseUrl" class="flex-1 border border-solid border-[#dcdfe6] rounded px-2 py-1" data-testid="provider-base-url" />
            </label>
            <label class="flex items-center gap-2">
              <span class="text-[#909399] w-20 shrink-0">{{ T.completionsPath }}</span>
              <input v-model="cardForms[provider.id].completionsPath" class="flex-1 border border-solid border-[#dcdfe6] rounded px-2 py-1" />
            </label>
            <label class="flex items-center gap-2">
              <span class="text-[#909399] w-20 shrink-0">{{ T.modelsPath }}</span>
              <input v-model="cardForms[provider.id].modelsPath" class="flex-1 border border-solid border-[#dcdfe6] rounded px-2 py-1" />
            </label>
            <label class="flex items-center gap-2 col-span-2">
              <span class="text-[#909399] w-20 shrink-0">{{ T.apiKey }}</span>
              <input
                v-model="cardForms[provider.id].key"
                type="password"
                autocomplete="new-password"
                :placeholder="T.keyInputPlaceholder"
                class="flex-1 border border-solid border-[#dcdfe6] rounded px-2 py-1"
                data-testid="key-input"
              />
            </label>
          </div>

          <div class="mt-2 flex items-center gap-2 text-[12px]">
            <button
              class="px-2 py-1 rounded bg-[#409eff] text-white disabled:opacity-50"
              :disabled="isBusy(`save-${provider.id}`)"
              data-testid="save-provider"
              @click="saveProvider(provider)"
            >
              {{ isBusy(`save-${provider.id}`) ? T.saving : T.save }}
            </button>
            <button class="px-2 py-1 rounded border border-solid border-[#dcdfe6]" data-testid="clear-key" @click="clearKey(provider)">
              {{ T.clearKey }}
            </button>
            <button class="px-2 py-1 rounded border border-solid border-[#dcdfe6]" data-testid="test-provider" @click="testConnection(provider)">
              {{ isBusy(`test-${provider.id}`) ? T.testing : T.testConnection }}
            </button>
            <button class="px-2 py-1 rounded border border-solid border-[#dcdfe6]" data-testid="toggle-provider" @click="toggleEnabled(provider)">
              {{ provider.enabled ? T.disable : T.enable }}
            </button>
            <button class="px-2 py-1 rounded border border-solid border-[#fbc4c4] text-[#f56c6c]" data-testid="remove-provider" @click="removeProvider(provider)">
              {{ T.remove }}
            </button>
            <span
              v-if="probeResults[provider.id]"
              class="ml-2"
              :class="probeResults[provider.id]?.ok ? 'text-[#67c23a]' : 'text-[#f56c6c]'"
              data-testid="probe-result"
            >
              {{ probeText(probeResults[provider.id]) }}
            </span>
          </div>
        </div>

        <!-- 新建端点 -->
        <div class="border border-dashed border-[#dcdfe6] rounded p-3" data-testid="create-provider">
          <div class="text-sm font-medium mb-2">{{ T.createTitle }}</div>
          <div class="grid grid-cols-2 gap-2 text-[12px]">
            <input v-model="createForm.code" :placeholder="T.code" class="border border-solid border-[#dcdfe6] rounded px-2 py-1" data-testid="create-code" />
            <input v-model="createForm.displayName" :placeholder="T.displayName" class="border border-solid border-[#dcdfe6] rounded px-2 py-1" data-testid="create-name" />
            <input v-model="createForm.baseUrl" :placeholder="T.baseUrl" class="border border-solid border-[#dcdfe6] rounded px-2 py-1 col-span-2" data-testid="create-base-url" />
            <input
              v-model="createForm.apiKey"
              type="password"
              autocomplete="new-password"
              :placeholder="T.apiKey"
              class="border border-solid border-[#dcdfe6] rounded px-2 py-1 col-span-2"
              data-testid="create-key"
            />
          </div>
          <button
            class="mt-2 px-2 py-1 rounded bg-[#409eff] text-white text-[12px] disabled:opacity-50"
            :disabled="isBusy('create')"
            data-testid="create-provider-submit"
            @click="createProvider"
          >
            {{ T.create }}
          </button>
        </div>
      </section>

      <!-- ② 模型清单 -->
      <section class="mb-5" data-testid="model-section">
        <div class="flex items-center gap-2 mb-2">
          <h4 class="text-sm font-medium">{{ T.modelsTitle }}</h4>
          <select v-model="selectedProviderId" class="text-[12px] border border-solid border-[#dcdfe6] rounded px-2 py-1" data-testid="provider-select">
            <option v-for="provider in providers" :key="provider.id" :value="provider.id">{{ provider.displayName }}</option>
          </select>
          <button
            class="px-2 py-1 rounded border border-solid border-[#dcdfe6] text-[12px]"
            :disabled="!selectedProvider || pulling"
            data-testid="pull-models"
            @click="pullModels"
          >
            {{ pulling ? T.pulling : T.pullModels }}
          </button>
          <span v-if="fetchedModels.length > 0" class="text-[11px] text-[#909399]">
            {{ t('views.aiSettings.modelCount', { count: fetchedModels.length }) }}{{ fetchedCached ? T.pulledCached : '' }}
          </span>
        </div>

        <div v-if="fetchedModels.length > 0" class="mb-2 flex flex-wrap gap-2" data-testid="fetched-models">
          <span v-for="modelId in fetchedModels" :key="modelId" class="text-[12px] px-2 py-1 rounded bg-[#f4f4f5]">
            {{ modelId }}
            <button class="ml-1 text-[#409eff]" data-testid="add-fetched-model" @click="addFetched(modelId)">{{ T.addModel }}</button>
          </span>
        </div>

        <p v-if="!selectedProvider || selectedProvider.models.length === 0" class="text-sm text-[#909399]" data-testid="model-empty">
          {{ T.modelsEmpty }}
        </p>
        <table v-else class="w-full text-[12px] border-collapse" data-testid="model-table">
          <thead>
            <tr class="text-left text-[#909399]">
              <th class="py-1 pr-2 font-normal">{{ T.modelId }}</th>
              <th class="py-1 pr-2 font-normal">{{ T.toolCalling }}</th>
              <th class="py-1 pr-2 font-normal">{{ T.vision }}</th>
              <th class="py-1 pr-2 font-normal">{{ T.reasoning }}</th>
              <th class="py-1 pr-2 font-normal">{{ T.maxContext }}</th>
              <th class="py-1 pr-2 font-normal">{{ T.maxOutput }}</th>
              <th class="py-1 pr-2 font-normal">{{ T.enabled }}</th>
              <th class="py-1 pr-2 font-normal">{{ T.isDefault }}</th>
              <th class="py-1 font-normal"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="model in selectedProvider.models" :key="model.id" class="border-t border-solid border-[#ebeef5]" data-testid="model-row">
              <td class="py-1 pr-2">{{ model.modelId }}</td>
              <td class="py-1 pr-2"><input v-model="model.supportsToolCalling" type="checkbox" /></td>
              <td class="py-1 pr-2"><input v-model="model.supportsVision" type="checkbox" /></td>
              <td class="py-1 pr-2"><input v-model="model.supportsReasoning" type="checkbox" /></td>
              <td class="py-1 pr-2"><input v-model.number="model.maxContext" type="number" class="w-20 border border-solid border-[#dcdfe6] rounded px-1" /></td>
              <td class="py-1 pr-2"><input v-model.number="model.maxOutput" type="number" class="w-20 border border-solid border-[#dcdfe6] rounded px-1" /></td>
              <td class="py-1 pr-2"><input v-model="model.enabled" type="checkbox" /></td>
              <td class="py-1 pr-2"><input v-model="model.isDefault" type="checkbox" /></td>
              <td class="py-1">
                <button class="text-[#409eff] mr-2" data-testid="save-model-row" @click="saveModelRow(model)">{{ T.save }}</button>
                <button class="text-[#f56c6c]" data-testid="remove-model-row" @click="removeModelRow(model)">{{ T.remove }}</button>
              </td>
            </tr>
          </tbody>
        </table>

        <div class="mt-2 flex items-center gap-2 text-[12px]" data-testid="manual-model-form">
          <span class="text-[#909399]">{{ T.manualAdd }}</span>
          <input v-model="manualForm.modelId" :placeholder="T.modelId" class="border border-solid border-[#dcdfe6] rounded px-2 py-1" data-testid="manual-model-id" />
          <input v-model="manualForm.maxContext" :placeholder="T.maxContext" class="w-28 border border-solid border-[#dcdfe6] rounded px-2 py-1" data-testid="manual-model-context" />
          <input v-model="manualForm.maxOutput" :placeholder="T.maxOutput" class="w-28 border border-solid border-[#dcdfe6] rounded px-2 py-1" data-testid="manual-model-output" />
          <button class="px-2 py-1 rounded bg-[#409eff] text-white" :disabled="!selectedProvider" data-testid="manual-model-submit" @click="addManualModel">
            {{ T.addModel }}
          </button>
        </div>
      </section>

      <!-- ③ 默认参数 -->
      <section data-testid="defaults-section">
        <h4 class="text-sm font-medium mb-2">{{ T.defaultsTitle }}</h4>
        <p class="text-[11px] text-[#909399] mb-2">{{ T.defaultsHint }}</p>
        <div class="grid grid-cols-2 gap-2 text-[12px]">
          <label class="flex items-center gap-2">
            <span class="text-[#909399] w-24 shrink-0">{{ T.defaultValueModel }}</span>
            <select v-model="defaultsForm.target" class="flex-1 border border-solid border-[#dcdfe6] rounded px-2 py-1" data-testid="default-model">
              <option value="">{{ T.defaultValueModelNone }}</option>
              <option v-if="legacyDefaultModelId" :value="`${LEGACY_TARGET_PREFIX}${legacyDefaultModelId}`">
                {{ legacyDefaultModelId }}（{{ T.defaultValueModelUnbound }}）
              </option>
              <option v-if="invalidDefaultTarget" :value="invalidDefaultTarget.key">
                {{ invalidDefaultTarget.modelId }}（{{ invalidDefaultTarget.providerName }}·{{ T.defaultValueModelUnavailable }}）
              </option>
              <option
                v-for="item in allModels"
                :key="modelTargetKey(item.provider.id, item.model.modelId)"
                :value="modelTargetKey(item.provider.id, item.model.modelId)"
              >
                {{ item.model.modelId }}（{{ item.provider.displayName }}）
              </option>
            </select>
          </label>
          <label class="flex items-center gap-2">
            <span class="text-[#909399] w-24 shrink-0">{{ T.temperature }}</span>
            <input v-model="defaultsForm.temperature" class="flex-1 border border-solid border-[#dcdfe6] rounded px-2 py-1" data-testid="default-temperature" />
          </label>
          <label class="flex items-center gap-2">
            <span class="text-[#909399] w-24 shrink-0">{{ T.topP }}</span>
            <input v-model="defaultsForm.topP" class="flex-1 border border-solid border-[#dcdfe6] rounded px-2 py-1" data-testid="default-top-p" />
          </label>
          <label class="flex items-center gap-2">
            <span class="text-[#909399] w-24 shrink-0">{{ T.maxTokens }}</span>
            <input v-model="defaultsForm.maxTokens" class="flex-1 border border-solid border-[#dcdfe6] rounded px-2 py-1" data-testid="default-max-tokens" />
          </label>
          <label class="flex items-center gap-2">
            <input v-model="defaultsForm.allowUserParams" type="checkbox" data-testid="default-allow-user-params" />
            <span class="text-[#606266]">{{ T.allowUserParams }}</span>
          </label>
        </div>
        <button
          class="mt-2 px-2 py-1 rounded bg-[#409eff] text-white text-[12px] disabled:opacity-50"
          :disabled="isBusy('defaults')"
          data-testid="save-defaults"
          @click="saveDefaults"
        >
          {{ isBusy('defaults') ? T.saving : T.save }}
        </button>
      </section>
    </template>
  </div>
</template>
