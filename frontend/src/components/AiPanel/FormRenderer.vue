<template>
  <div
    ref="rootRef"
    :data-testid="`gf-form-${scope}`"
    class="min-w-0"
  >
    <div :class="['min-w-0 max-h-[min(320px,45vh)] overflow-y-auto p-1', SCROLLBAR_CLASS]" data-testid="gf-fields">
      <div class="flex flex-col gap-[8px]">
        <p v-if="fullTitle" class="m-0 break-words text-[12.6px] font-semibold leading-[1.55] text-[#1f2328] [overflow-wrap:anywhere]" data-testid="gf-full-title">{{ fullTitle }}</p>
        <div v-for="field in visibleFields" :key="field.key">
          <div v-if="!readonly && isAdvancedGroupStart(field)">
            <button
              type="button"
              data-testid="gf-advanced-toggle"
              :aria-expanded="advancedOpen"
              class="rounded-[6px] border border-solid border-[#e3e6ea] bg-white px-[9px] py-[3px] text-[12px] text-[#57606a] transition-colors duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#2563eb]"
              @click="toggleAdvanced"
            >{{ advancedOpen ? t('ai.gf.advancedCollapse') : t('ai.gf.advancedExpand', { count: advancedCount }) }}</button>
          </div>
          <template v-if="isFieldOpen(field)">
            <label
              v-if="field.type !== 'multi_select'"
              :for="controlId(field)"
              class="mb-[4px] block break-words text-[12.2px] [overflow-wrap:anywhere]"
              :class="fieldIssues(field).length > 0 ? 'text-[#c0392b]' : 'text-[#57606a]'"
            >
              {{ field.label }}<span v-if="isRequired(field)" class="text-[#c0392b]" aria-hidden="true"> *</span>
            </label>
            <span :id="hintId(field)" class="sr-only">{{ hintOf(field) }}</span>

            <span v-if="readonly && isSensitiveField(field) && field.type !== 'multi_select'" class="block text-[13px] leading-[normal] text-[#57606a]">{{ MASKED_VALUE }}</span>
            <textarea
              v-else-if="field.type === 'text' && field.variant === 'longText'"
              :id="controlId(field)"
              :data-testid="controlTestId(field)"
              :data-long-text="field.key"
              :class="[fieldClass(field), 'resize-none overflow-y-hidden']"
              :value="textValue(field.key)"
              :placeholder="field.placeholder"
              :maxlength="MAX_LONGTEXT_VALUE_CHARS"
              :disabled="isDisabled(field)"
              :aria-invalid="fieldIssues(field).length > 0"
              :aria-required="isRequired(field)"
              :aria-describedby="describedBy(field)"
              rows="4"
              @input="onLongTextInput(field.key, $event)"
            ></textarea>
            <input
              v-else-if="field.type === 'text'"
              :id="controlId(field)"
              :data-testid="controlTestId(field)"
              :type="inputTypeOf(field)"
              :class="fieldClass(field)"
              :value="textValue(field.key)"
              :placeholder="field.placeholder"
              :maxlength="MAX_TEXT_VALUE_CHARS"
              :disabled="isDisabled(field)"
              :aria-invalid="fieldIssues(field).length > 0"
              :aria-required="isRequired(field)"
              :aria-describedby="describedBy(field)"
              @input="setValue(field.key, ($event.target as HTMLInputElement).value)"
            />
            <input
              v-else-if="field.type === 'number'"
              :id="controlId(field)"
              :data-testid="controlTestId(field)"
              type="number"
              :class="fieldClass(field)"
              :value="numberValue(field.key)"
              :placeholder="field.placeholder"
              :min="numberBound(field.min)"
              :max="numberBound(field.max)"
              :step="field.step"
              :disabled="isDisabled(field)"
              :aria-invalid="fieldIssues(field).length > 0"
              :aria-required="isRequired(field)"
              :aria-describedby="describedBy(field)"
              @input="onNumberInput(field.key, $event)"
            />
            <label v-else-if="field.type === 'boolean'" class="relative inline-flex h-[18px] w-[34px] align-middle">
              <input
                :id="controlId(field)"
                :data-testid="controlTestId(field)"
                type="checkbox"
                class="absolute inset-0 m-0 h-full w-full cursor-pointer appearance-none rounded-[999px] [outline:none] focus-visible:ring-2 focus-visible:ring-[#2563eb] focus-visible:ring-offset-2 disabled:cursor-default"
                :checked="model[field.key] === true"
                :disabled="isDisabled(field)"
                :aria-invalid="fieldIssues(field).length > 0"
                :aria-describedby="describedBy(field)"
                @change="setValue(field.key, ($event.target as HTMLInputElement).checked)"
              />
              <span
                class="pointer-events-none absolute inset-0 rounded-[999px] transition-colors duration-[150ms]"
                :class="model[field.key] === true ? 'bg-[#2563eb]' : 'bg-[#e3e6ea]'"
                aria-hidden="true"
              ></span>
              <span
                class="pointer-events-none absolute left-[2px] top-[2px] h-[14px] w-[14px] rounded-[50%] bg-white transition-transform duration-[150ms] motion-reduce:transition-none"
                :class="model[field.key] === true ? 'translate-x-[16px]' : ''"
                aria-hidden="true"
              ></span>
            </label>
            <input
              v-else-if="field.type === 'date'"
              :id="controlId(field)"
              :data-testid="controlTestId(field)"
              type="date"
              :class="fieldClass(field)"
              :value="textValue(field.key)"
              :min="dateBound(field.min)"
              :max="dateBound(field.max)"
              :disabled="isDisabled(field)"
              :aria-invalid="fieldIssues(field).length > 0"
              :aria-required="isRequired(field)"
              :aria-describedby="describedBy(field)"
              @input="setValue(field.key, ($event.target as HTMLInputElement).value)"
            />
            <select
              v-else-if="field.type === 'enum'"
              :id="controlId(field)"
              :data-testid="controlTestId(field)"
              :class="fieldClass(field)"
              :value="textValue(field.key)"
              :disabled="isDisabled(field)"
              :aria-invalid="fieldIssues(field).length > 0"
              :aria-required="isRequired(field)"
              :aria-describedby="describedBy(field)"
              @change="setValue(field.key, ($event.target as HTMLSelectElement).value)"
            >
              <option value="">{{ t('ai.gf.selectPlaceholder', { label: field.label }) }}</option>
              <template v-if="optionStatusOf(field) === null">
                <option v-for="option in optionsOf(field)" :key="option.value" :value="option.value">
                  {{ option.label ?? option.value }}
                </option>
              </template>
            </select>
            <fieldset
              v-else-if="field.type === 'multi_select'"
              :id="controlId(field)"
              class="m-0 min-w-0 border-0 p-0"
              tabindex="-1"
              :aria-invalid="fieldIssues(field).length > 0"
              :aria-describedby="describedBy(field)"
            >
              <legend
                class="mb-[4px] block break-words text-[12.2px] [overflow-wrap:anywhere]"
                :class="fieldIssues(field).length > 0 ? 'text-[#c0392b]' : 'text-[#57606a]'"
              >
                {{ field.label }}<span v-if="isRequired(field)" class="text-[#c0392b]" aria-hidden="true"> *</span>
              </legend>
              <div v-if="showMultiTools(field)" class="mb-[6px] flex flex-wrap items-center gap-x-[10px] gap-y-[4px] text-[12px] text-[#57606a]">
                <label :for="`${controlId(field)}-select-all`" class="inline-flex min-h-[24px] cursor-pointer items-center gap-[5px]">
                  <input
                    :id="`${controlId(field)}-select-all`"
                    :data-testid="`${controlTestId(field)}-select-all`"
                    type="checkbox"
                    class="m-0 accent-[#2563eb]"
                    :checked="isMultiAllSelected(field)"
                    :indeterminate="isMultiPartiallySelected(field)"
                    :disabled="isDisabled(field)"
                    :aria-describedby="describedBy(field)"
                    @change="onMultiSelectAll(field)"
                  />
                  {{ t('ai.gf.multiSelect.selectAll') }}
                </label>
                <span :data-testid="`${controlTestId(field)}-selected-count`">
                  {{ t('ai.gf.multiSelect.selectedCount', { selected: multiSelectedCount(field), total: optionsOf(field).length }) }}
                </span>
              </div>
              <span v-if="readonly && isSensitiveField(field)" class="block text-[13px] leading-[normal] text-[#57606a]">{{ MASKED_VALUE }}</span>
              <span v-else-if="optionStatusOf(field) === null" class="flex flex-wrap gap-[6px]">
                <label
                  v-for="option in optionsOf(field)"
                  :key="option.value"
                  :for="`${controlId(field)}-${option.value}`"
                  class="inline-flex items-center gap-[5px] rounded-[999px] border border-solid px-[11px] py-[4px] text-[12.2px] transition-colors duration-[150ms]"
                  :class="[
                    isMultiChecked(field, option.value)
                      ? 'border-[#cddcff] bg-[#eff4ff] text-[#2563eb]'
                      : 'border-[#e3e6ea] bg-white text-[#57606a]',
                    readonly ? 'cursor-default' : 'cursor-pointer'
                  ]"
                >
                  <input
                    :id="`${controlId(field)}-${option.value}`"
                    :data-testid="`${controlTestId(field)}-${option.value}`"
                    data-multi-option
                    type="checkbox"
                    class="m-0 accent-[#2563eb]"
                    :checked="isMultiChecked(field, option.value)"
                    :disabled="isDisabled(field)"
                    :aria-invalid="fieldIssues(field).length > 0"
                    :aria-required="isRequired(field)"
                    :aria-describedby="describedBy(field)"
                    @change="onMultiToggle(field.key, option.value, ($event.target as HTMLInputElement).checked)"
                  />
                  {{ option.label ?? option.value }}
                </label>
              </span>
            </fieldset>
            <div v-else-if="field.type === 'attachment'" class="min-w-0">
              <input
                :id="controlId(field)"
                :data-testid="controlTestId(field)"
                type="file"
                :accept="ATTACHMENT_ACCEPT"
                :class="['block w-full text-[12.2px] text-[#57606a] file:mr-[8px] file:rounded-[6px] file:border file:border-solid file:border-[#e3e6ea] file:bg-white file:px-[9px] file:py-[3px] file:text-[12px] file:text-[#57606a] disabled:cursor-default', fieldIssues(field).length > 0 ? 'rounded-[6px] border border-solid border-[#c0392b] p-[4px]' : '']"
                :disabled="isDisabled(field) || !uploadContext || uploading[field.key] === true"
                :aria-invalid="fieldIssues(field).length > 0"
                :aria-required="isRequired(field)"
                :aria-describedby="describedBy(field)"
                @change="onAttachmentChange(field, $event)"
              />
              <span v-if="uploading[field.key]" class="mt-[4px] block text-[11.8px] text-[#8b949e]">{{ t('ai.gf.attachment.uploading') }}</span>
              <span v-else-if="attachmentOf(field)" class="mt-[5px] flex flex-wrap items-center gap-[8px] text-[11.8px] text-[#57606a]">
                <span class="break-all">{{ attachmentOf(field)?.fileName }}</span>
                <span class="text-[#8b949e]">{{ formatBytes(attachmentOf(field)?.size) }}</span>
                <button
                  v-if="!readonly"
                  type="button"
                  :data-testid="`gf-attach-remove-${field.key}`"
                  class="rounded-[6px] border border-solid border-[#e3e6ea] bg-white px-[7px] py-[2px] text-[11.8px] text-[#57606a] hover:border-[#8b949e] hover:text-[#1f2328] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#2563eb]"
                  @click="removeAttachment(field.key)"
                >{{ t('ai.gf.attachment.remove') }}</button>
              </span>
              <span v-else-if="!readonly" class="mt-[4px] block text-[11.4px] text-[#8b949e]">
                {{ t('ai.gf.attachment.choose') }} · {{ t('ai.gf.attachment.hint') }}
              </span>
            </div>
            <span v-else :class="FMSG">
              {{ t('ai.gf.unsupportedType', { label: field.label, type: formFieldTypeText(field.type) }) }}
            </span>

            <div
              v-if="optionStatusOf(field) !== null"
              class="mt-[5px] flex flex-wrap items-center gap-[8px] text-[11.8px] leading-[1.5]"
              :class="optionStatusOf(field) === 'failed' ? 'text-[#c0392b]' : 'text-[#57606a]'"
            >
              <span
                :id="optionStatusId(field)"
                :role="optionStatusOf(field) === 'failed' ? 'alert' : undefined"
                :data-testid="`${controlTestId(field)}-options-${optionStatusOf(field)}`"
              >{{ optionStatusText(field) }}</span>
              <button
                v-if="optionStatusOf(field) === 'failed' && !readonly"
                type="button"
                :data-testid="`${controlTestId(field)}-options-retry`"
                :class="['min-h-[24px] rounded-[6px] border border-solid border-[#e3e6ea] bg-white px-[8px] py-[2px] text-[11.8px] text-[#57606a] hover:border-[#8b949e] hover:text-[#1f2328] disabled:cursor-default disabled:opacity-50', GF_FOCUS]"
                :disabled="isDisabled(field) || isSourceLoading(field)"
                @click="refreshSourceOptions(field)"
              >{{ t('ai.gf.options.retry') }}</button>
            </div>
            <span v-if="fieldIssues(field).length > 0" :id="errorId(field)" :class="[FMSG, 'mt-[5px]']">
              {{ fieldIssues(field).map((issue) => issueText(field, issue)).join('；') }}
            </span>
          </template>
        </div>
        <div
          v-if="readonly && readOnlyReason"
          class="whitespace-pre-wrap break-words rounded-[8px] border border-solid px-[10px] py-[7px] text-[11.9px] leading-[1.6]"
          :class="readOnlyReasonTone === 'neutral'
            ? 'border-[#e3e6ea] bg-[#f6f7f9] text-[#57606a]'
            : 'border-[#f0d5d0] bg-[#fdf1ef] text-[#c0392b]'"
          data-testid="gf-readonly-reason"
        >{{ readOnlyReason }}</div>
        <span v-if="!readonly && cardError" :class="FMSG" data-testid="gf-error">{{ cardError }}</span>
      </div>
    </div>

    <span class="sr-only" role="alert" aria-live="polite" data-testid="gf-live-region">{{ liveSummary }}</span>

    <span
      v-if="!readonly && cascadeNotice"
      class="mt-[8px] block rounded-[6px] border border-solid border-[#e3e6ea] bg-[#f6f7f9] px-[8px] py-[5px] text-[11.8px] leading-[1.5] text-[#57606a]"
      data-testid="gf-cascade-notice"
    >{{ cascadeNotice }}</span>

    <div v-if="!readonly" class="mt-[8px] flex flex-wrap items-center justify-end gap-2">
      <span v-if="!canRetry" class="mr-auto text-[11.8px] text-[#57606a]">{{ t('ai.gf.notRetryable') }}</span>
      <button
        type="button"
        data-testid="gf-cancel"
        :class="GF_CANCEL"
        :disabled="submitting"
        @click="emit('cancel')"
      >{{ t('ai.gf.cancel') }}</button>
      <button
        v-if="canRetry"
        type="button"
        data-testid="gf-submit"
        :class="GF_SUBMIT"
        :disabled="disabled"
        @click="onSubmit"
      >
        <span
          v-if="submitting"
          class="mr-[6px] inline-block h-[11px] w-[11px] rounded-[50%] border-[1.5px] border-solid border-[rgba(255,255,255,.4)] border-t-white align-[-1px] animate-spin motion-reduce:animate-none"
          aria-hidden="true"
        ></span>{{ submitting ? t('ai.gf.submitting') : t('ai.gf.submit') }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 生成式表单卡体。宿主统一提供业务卡头与卡片外壳。
 * 取值、校验、级联、草稿和附件提交沿用 form-schema 与 store 契约；
 * 控件使用原生元素，字段区是唯一纵向滚动区。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  EMPTY_FORM_FIELD_OPTIONS,
  MAX_LONGTEXT_VALUE_CHARS,
  MAX_TEXT_VALUE_CHARS,
  applyDefaultFrom,
  buildInitialValues,
  checkFormIssues,
  clearedCascadeFields,
  isFieldDisabled,
  isFieldRequired,
  isFieldVisible,
  isRetryableIssue,
  pruneCascadeValues,
  resolveFieldOptions,
  visibleValues,
  type FormFieldOption,
  type FormFieldSpec,
  type FormSpec,
  type FormValueIssue
} from '@/types/form-schema'
import { SCROLLBAR_CLASS } from '@/utils/markdown'
import { head } from 'lodash'
import { MASKED_VALUE } from '@/utils/blocks'
import { nextReadonlyFormScope } from '@/utils/form-scope'
import { flushFormDraft } from '@/utils/form-draft'
import { uploadFormAttachment, formOptionSource } from '@/api/ai'
import { useAiStore } from '@/stores/ai'
import { formFieldTypeText, textFormatText } from '@/utils/card-content'
import { logCaughtError } from '@/utils/log-caught-error'

/** 七型取值并集（number 的"未填"用 undefined 表示；attachment 的值是 fileId 字符串）。 */
type FormModelValue = string | number | boolean | undefined | string[]

/** 附件扩展名白名单（与后端 `GenerativeFormAttachmentController` 白名单同源，单一事实源）。 */
const ATTACHMENT_EXTENSIONS = ['png', 'jpg', 'jpeg', 'webp', 'pdf', 'txt', 'csv', 'xlsx'] as const

/** 附件选择器的可接受类型（由白名单派生；浏览器 `accept` 只是软过滤，硬拦在 `onAttachmentChange`）。 */
const ATTACHMENT_ACCEPT = ATTACHMENT_EXTENSIONS.map((ext) => `.${ext}`).join(',')

/** 单件上限（与后端 `MAX_ATTACHMENT_BYTES` / `spring.servlet.multipart.max-file-size` 同口径）。 */
const ATTACHMENT_MAX_BYTES = 5 * 1024 * 1024

/** 本地拒绝码（与 `ai.gf.errors.*` 键尾同名，文案走 locales）。 */
type AttachmentRejection = 'ATTACHMENT_TOO_LARGE' | 'ATTACHMENT_TYPE_UNSUPPORTED'

interface UploadContext {
  runId: string
  toolCallId: string
  sessionId?: string
  signal?: AbortSignal
}

interface AttachmentMeta {
  fileId: string
  fileName: string
  size: number
}

const props = withDefaults(defineProps<{
  schema: FormSpec
  submitting: boolean
  /** 卡级错误标记（仅触发安全通用文案；字段级见 fieldErrors） */
  error: string | null
  /** 已决只读态：控件原生禁用并回填真实值，不渲染操作区或卡级错误。 */
  readonly?: boolean
  /** 只读态回填值 / 待填态草稿值（形状不符的键回落初始态，不把脏数据塞进受控控件）。 */
  initialValues?: Record<string, unknown> | null
  /** 兼容旧调用；业务卡头始终由宿主承担。 */
  showHeader?: boolean
  /** 兼容旧调用；卡片外壳始终由宿主承担。 */
  flat?: boolean
  /** 长标题在字段主滚动区内完整显示。 */
  fullTitle?: string
  /** 只读结局原因与字段共用主滚动区。 */
  readOnlyReason?: string | null
  readOnlyReasonTone?: 'neutral' | 'error'
  /** 后端结构化错误按字段归属，与本地复核结果合并渲染。 */
  fieldErrors?: FormValueIssue[] | null
  /** 附件上传归属；缺省时上传控件禁用。 */
  uploadContext?: UploadContext | null
}>(), {
  showHeader: false,
  initialValues: null,
  fullTitle: '',
  readOnlyReason: null,
  readOnlyReasonTone: 'error',
  fieldErrors: null,
  uploadContext: null
})

const emit = defineEmits<{
  (event: 'submit', values: Record<string, unknown>): void
  (event: 'cancel'): void
  /** 草稿镜像（G-B）：每次改动都 emit，由 store 合并落 sessionStorage（永不进模型上下文）。 */
  (event: 'draft', values: Record<string, unknown>): void
}>()

const { t, te } = useI18n()

/**
 * 卡作用域（L-92）：同一页面里可以同时出现**上一轮已决的只读卡**与**本轮待填卡**，两卡 schema 同形，
 * 字段 DOM id 必撞车（`label for` / `aria-describedby` / 首错聚焦都是按 id 取第一个命中）。
 * 口径：
 * - 轮次作用域 = 本轮挂起的 `toolCallId`（上传归属已带同一值，无需新增宿主入参）；
 * - 只读回放卡没有挂起归属 ⇒ 模块级自增序号（同页任意多卡、含多 app 实例都互不相同；
 *   非交互面无需可预测）；
 * - **交互卡的 DOM id 保持 `gf-field-<key>` 历史形态**（`#gf-field-*` 的既有用例与外部锚点零变更），
 *   轮次唯一的定位一律走 `data-testid="gf-field-<scope>-<key>"`。
 */
const scope = props.uploadContext?.toolCallId ?? nextReadonlyFormScope()
/** 本卡的草稿归属（只读回放卡没有挂起归属 ⇒ 无草稿可 flush）。 */
const draftToolCallId = props.uploadContext?.toolCallId
const interactive = props.readonly !== true

const FI_BASE = 'w-full border rounded-[8px] px-[9px] py-[6px] font-[inherit] text-[13px] leading-[normal] text-[#1f2328] [outline:none] transition-colors duration-[150ms] motion-reduce:transition-none focus:border-[#2563eb] focus-visible:ring-2 focus-visible:ring-[#2563eb] focus-visible:ring-offset-1 disabled:cursor-default'
const FI_DISABLED = 'disabled:bg-[#f6f7f9] disabled:text-[#8b949e]'
const FI_READONLY = 'disabled:bg-white disabled:text-[#57606a]'
const FI_IDLE = 'border-solid border-[#e3e6ea] bg-white'
const FI_BAD = 'border-solid border-[#c0392b] bg-[#fdf1ef]'
const FMSG = 'block text-[11.8px] leading-[1.5] text-[#c0392b] bg-[#fdf1ef] border border-solid border-[#f0d5d0] rounded-[6px] px-[8px] py-[5px]'
const GF_FOCUS = 'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#2563eb] motion-reduce:transition-none'
const GF_CANCEL = `flex-none whitespace-nowrap min-w-[56px] rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-[#e3e6ea] transition-all duration-[150ms] bg-white text-[#57606a] hover:border-[#8b949e] hover:text-[#1f2328] disabled:opacity-50 disabled:cursor-default ${GF_FOCUS}`
const GF_SUBMIT = `flex-none whitespace-nowrap min-w-[56px] rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-transparent transition-all duration-[150ms] bg-[#2563eb] text-white font-semibold hover:brightness-[1.08] disabled:opacity-50 disabled:cursor-default disabled:bg-[#8b949e] disabled:font-normal ${GF_FOCUS}`

/** schema 默认值（枚举初值合法默认）：不含任何用户数据，是"用户是否填过"的对照基线。 */
function schemaDefaults(): Record<string, FormModelValue> {
  return buildInitialValues(props.schema) as Record<string, FormModelValue>
}

/** 草稿和只读回填只接受与字段类型相符的值。 */
function initialModel(): Record<string, FormModelValue> {
  const base = schemaDefaults()
  const values = props.initialValues
  if (!values) {
    return base
  }
  for (const field of props.schema.fields) {
    if (!Object.prototype.hasOwnProperty.call(values, field.key)) {
      continue
    }
    const value = values[field.key]
    if (field.type === 'text' || field.type === 'date' || field.type === 'attachment') {
      if (typeof value === 'string') {
        base[field.key] = value
      }
    } else if (field.type === 'enum') {
      if (typeof value === 'string' && (field.options ?? []).some((option) => option.value === value)) {
        base[field.key] = value
      }
    } else if (field.type === 'number') {
      if (typeof value === 'number' && Number.isFinite(value)) {
        base[field.key] = value
      }
    } else if (field.type === 'boolean') {
      if (typeof value === 'boolean') {
        base[field.key] = value
      }
    } else if (field.type === 'multi_select') {
      if (Array.isArray(value)) {
        const allowed = new Set((field.options ?? []).map((option) => option.value))
        base[field.key] = value.filter((item): item is string => typeof item === 'string' && allowed.has(item))
      }
    }
  }
  return base
}

const model = ref<Record<string, FormModelValue>>(initialModel())
const defaultModel = schemaDefaults()
const rootRef = ref<HTMLElement | null>(null)
const localIssues = ref<FormValueIssue[]>([])
const touched = reactive(new Set<string>())
const attachments = ref<Record<string, AttachmentMeta>>({})
const uploading = reactive<Record<string, boolean>>({})
const uploadErrors = reactive<Record<string, string>>({})
const attachmentOperationSeq = new Map<string, number>()
const attachmentControllers = new Map<string, AbortController>()
let attachmentsDisposed = false
const attachmentRejections = reactive<Record<string, AttachmentRejection>>({})
const externalOptions = ref<Record<string, readonly FormFieldOption[]>>({})
const sourceRequestSeq = new Map<string, number>()

function resizeLongTextarea(textarea: HTMLTextAreaElement): void {
  textarea.style.height = 'auto'
  textarea.style.height = `${textarea.scrollHeight}px`
}

function resizeLongTextareas(): void {
  rootRef.value?.querySelectorAll<HTMLTextAreaElement>('textarea[data-long-text]').forEach(resizeLongTextarea)
}

function onLongTextInput(key: string, event: Event): void {
  const textarea = event.target as HTMLTextAreaElement
  setValue(key, textarea.value)
  resizeLongTextarea(textarea)
}

function rehydrateReadonlyModel(): void {
  if (props.readonly !== true) {
    return
  }
  model.value = initialModel()
  localIssues.value = []
  void nextTick(resizeLongTextareas)
}

watch(
  () => [props.readonly, props.initialValues] as const,
  rehydrateReadonlyModel,
  { deep: true, immediate: true }
)

watch(
  () => model.value,
  (values) => {
    for (const field of props.schema.fields) {
      if (field.type !== 'attachment') {
        continue
      }
      const value = values[field.key]
      if (typeof value === 'string' && value !== '' && !attachments.value[field.key]) {
        attachments.value[field.key] = { fileId: value, fileName: t('ai.gf.attachment.unknownName'), size: 0 }
      }
    }
    void nextTick(resizeLongTextareas)
  },
  { immediate: true }
)

const disabled = computed(() => props.readonly === true || props.submitting)
const visibleFields = computed(() => props.schema.fields.filter((field) => isFieldVisible(field, model.value)))

/**
 * 常用条件一屏能放下的字段数（AR-07 / Task 7 Step 3「常用筛选一屏，高级渐进披露」）。
 * 与 GOV.UK 式「一屏一问」相反：相互影响的筛选条件拆开只会让用户来回翻，
 * 故**常用条件始终同屏**，只有长尾可选条件折叠。
 */
const FILTER_COMMON_FIELD_COUNT = 6

/** 高级条件展开态（纯呈现层；不影响取值、校验与提交）。 */
const advancedOpen = ref(false)

function toggleAdvanced(): void {
  advancedOpen.value = !advancedOpen.value
  void nextTick(resizeLongTextareas)
}

function hasUserValue(field: FormFieldSpec): boolean {
  const value = model.value[field.key]
  const initial = defaultModel[field.key]
  if (Array.isArray(value) || Array.isArray(initial)) {
    const left = Array.isArray(value) ? value : []
    const right = Array.isArray(initial) ? initial : []
    return left.length !== right.length || left.some((item, index) => item !== right[index])
  }
  return value !== initial
}

/**
 * 渐进披露（AR-07）：哪些可见字段属"高级条件"（默认折叠）。
 *
 * <p>schema 没有"高级"标记（字段白名单不含该属性），故按**顺序 + 必填/已填/报错**推导：
 * 前 {@link FILTER_COMMON_FIELD_COUNT} 张可见卡是常用条件，其余**可选**字段是长尾高级条件。
 * 三条不折叠的例外：必填（折叠了表就交不出去）、用户已填（不藏用户自己的数据）、
 * 有错误（错误必须可见）。只读回放不折叠（整张表按提交原样呈现）；CLARIFY（确认型）
 * 也不折叠 —— 确认场景要的就是一眼看全。
 */
const advancedKeys = computed<Set<string>>(() => {
  const keys = new Set<string>()
  if (props.readonly === true || props.schema.scenario !== 'FILTER') {
    return keys
  }
  visibleFields.value.forEach((field, index) => {
    if (index < FILTER_COMMON_FIELD_COUNT) {
      return
    }
    if (isFieldRequired(field, model.value) || hasUserValue(field) || fieldIssues(field).length > 0) {
      return
    }
    keys.add(field.key)
  })
  return keys
})

/** 折叠区的字段数（按钮文案用）。 */
const advancedCount = computed(() => visibleFields.value.filter((field) => advancedKeys.value.has(field.key)).length)

/** 该字段此刻是否展开渲染（常用字段恒 true；高级字段看展开态）。 */
function isFieldOpen(field: FormFieldSpec): boolean {
  return !advancedKeys.value.has(field.key) || advancedOpen.value
}

/** 高级区的第一张（折叠按钮就挂在这里，保证整段只出现一个开关）。 */
function isAdvancedGroupStart(field: FormFieldSpec): boolean {
  const first = visibleFields.value.find((candidate) => advancedKeys.value.has(candidate.key))
  return first !== undefined && first.key === field.key
}

/**
 * 级联清值的一次性提示（AR-07）：本次父值变化清掉了哪些字段（null = 没清）。
 * 一次改动最多一句说明，不逐字段弹条 —— 清值是**一个动作的后果**，不是 N 个独立事件。
 */
const cascadeNotice = ref<string | null>(null)

/** 控件 DOM id：交互卡保持历史形态；只读回放卡按卡作用域分段（同页多卡不撞车）。 */
function controlIdOfKey(key: string): string {
  return interactive ? `gf-field-${key}` : `gf-field-${scope}-${key}`
}

function controlId(field: FormFieldSpec): string {
  return controlIdOfKey(field.key)
}

/** 轮次唯一的 testid（L-92）：同页多卡时定位只看它，不依赖 DOM id 的唯一性。 */
function controlTestId(field: FormFieldSpec): string {
  return `gf-field-${scope}-${field.key}`
}

function hintId(field: FormFieldSpec): string {
  return `${controlId(field)}-hint`
}

function errorId(field: FormFieldSpec): string {
  return `${controlId(field)}-error`
}

/** 说明文本（sr-only）：给出必填/格式/边界的提示，读屏可读。 */
function hintOf(field: FormFieldSpec): string {
  const parts: string[] = []
  if (isFieldRequired(field, model.value)) {
    parts.push(t('ai.gf.hint.required'))
  }
  if (field.format) {
    parts.push(t('ai.gf.hint.format', { format: textFormatText(field.format) }))
  }
  if (field.type === 'attachment') {
    parts.push(t('ai.gf.attachment.hint'))
  }
  return parts.join(t('ai.gf.hintSeparator'))
}

function optionStatusId(field: FormFieldSpec): string {
  return `${controlId(field)}-options-status`
}

function describedBy(field: FormFieldSpec): string {
  const ids = [hintId(field)]
  if (optionStatusOf(field) !== null) {
    ids.push(optionStatusId(field))
  }
  if (fieldIssues(field).length > 0) {
    ids.push(errorId(field))
  }
  return ids.join(' ')
}

function isRequired(field: FormFieldSpec): boolean {
  return isFieldRequired(field, model.value)
}

/** 字段禁用：只读态 / 提交中 / 联动 disabledWhen。 */
function isDisabled(field: FormFieldSpec): boolean {
  return disabled.value || isFieldDisabled(field, model.value)
}

const SENSITIVE_FIELD_KEY_RE = /pass(word)?|secret|token|api_?key|id_?card/i

function isSensitiveField(field: FormFieldSpec): boolean {
  return field.format === 'idcard' || SENSITIVE_FIELD_KEY_RE.test(field.key)
}

function inputTypeOf(field: FormFieldSpec): string {
  if (field.type !== 'text') {
    return 'text'
  }
  switch (field.format) {
    case 'email':
      return 'email'
    case 'tel':
      return 'tel'
    case 'url':
      return 'url'
    default:
      return 'text'
  }
}

function numberBound(value: number | string | undefined): number | string | undefined {
  return typeof value === 'number' ? value : undefined
}

function dateBound(value: number | string | undefined): string | undefined {
  return typeof value === 'string' ? value : undefined
}

function textValue(key: string): string {
  const field = props.schema.fields.find((candidate) => candidate.key === key)
  if (props.readonly === true && field && isSensitiveField(field)) {
    return MASKED_VALUE
  }
  const value = model.value[key]
  return typeof value === 'string' ? value : ''
}

function numberValue(key: string): number | string {
  const field = props.schema.fields.find((candidate) => candidate.key === key)
  if (props.readonly === true && field && isSensitiveField(field)) {
    return MASKED_VALUE
  }
  const value = model.value[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : ''
}

/**
 * 改值：写模型 → 套用 defaultFrom（父值变化时给未被碰过的子字段补默认）→ 抛 draft。
 */
function setValue(key: string, value: FormModelValue): void {
  // 级联裁剪（I2）：父值一变，依赖它的子值里"按新选项已经不合法"的先清掉 —— 否则多选级联的
  // 复选框整组消失后模型仍带旧值，用户连取消勾选都做不到（提交必被判不在选项内）。
  const before = model.value
  const changed = pruneCascadeValues(props.schema, { ...before, [key]: value }, key)
  model.value = changed as Record<string, FormModelValue>
  // AR-07：清值必须**告知**（否则用户以为值还在）—— 一次改动聚成一句，覆盖本次被清掉的每个字段
  const cleared = clearedCascadeFields(props.schema, before, changed)
  cascadeNotice.value = cleared.length > 0
    ? t('ai.gf.cascadeCleared', { fields: cleared.map((field) => field.label).join(t('ai.gf.hintSeparator')) })
    : null
  touched.add(key)
  const withDefaults = applyDefaultFrom(props.schema, changed, key, { touched })
  model.value = withDefaults as Record<string, FormModelValue>
  delete uploadErrors[key]
  delete attachmentRejections[key]
  // 数据源级联：父值变了就重取（静态 map 分支由 resolveFieldOptions 同步解析）
  for (const field of props.schema.fields) {
    if (field.optionsFrom?.sourceId && field.optionsFrom.dependsOn === key) {
      void refreshSourceOptions(field)
    }
  }
  emit('draft', { ...model.value })
}

/** number：原生 input[type=number] 的字符串值按"空 / 非法 = undefined"折算回数值模型。 */
function onNumberInput(key: string, event: Event): void {
  const raw = (event.target as HTMLInputElement).value
  if (raw === '') {
    setValue(key, undefined)
    return
  }
  const parsed = Number(raw)
  setValue(key, Number.isFinite(parsed) ? parsed : undefined)
}

type OptionStatus = 'loading' | 'failed' | 'empty'

const sourceLoading = reactive<Record<string, boolean>>({})
const sourceFailed = reactive<Record<string, boolean>>({})

function isSourceLoading(field: FormFieldSpec): boolean {
  return sourceLoading[field.key] === true
}

function hasSourceFailed(field: FormFieldSpec): boolean {
  return sourceFailed[field.key] === true
}

function optionStatusOf(field: FormFieldSpec): OptionStatus | null {
  if (field.type !== 'enum' && field.type !== 'multi_select') {
    return null
  }
  if (isSourceLoading(field)) {
    return 'loading'
  }
  if (hasSourceFailed(field)) {
    return 'failed'
  }
  return optionsOf(field).length === 0 ? 'empty' : null
}

function optionStatusText(field: FormFieldSpec): string {
  const status = optionStatusOf(field)
  if (status === 'loading') {
    return t('ai.gf.options.loading')
  }
  if (status === 'failed') {
    return t('ai.gf.options.loadFailed')
  }
  if (status === 'empty') {
    return t('ai.gf.options.empty')
  }
  return ''
}

function isMultiChecked(field: FormFieldSpec, value: string): boolean {
  if (props.readonly === true && isSensitiveField(field)) {
    return false
  }
  const current = model.value[field.key]
  return Array.isArray(current) && current.includes(value)
}

function multiSelectedCount(field: FormFieldSpec): number {
  const current = model.value[field.key]
  if (!Array.isArray(current)) {
    return 0
  }
  const selected = new Set(current)
  return optionsOf(field).filter((option) => selected.has(option.value)).length
}

function isMultiAllSelected(field: FormFieldSpec): boolean {
  const options = optionsOf(field)
  return options.length > 0 && multiSelectedCount(field) === options.length
}

function isMultiPartiallySelected(field: FormFieldSpec): boolean {
  const selected = multiSelectedCount(field)
  return selected > 0 && selected < optionsOf(field).length
}

function showMultiTools(field: FormFieldSpec): boolean {
  const optionCount = optionsOf(field).length
  return optionCount > 6
    && (field.maxItems === undefined || field.maxItems >= optionCount)
    && optionStatusOf(field) === null
    && props.readonly !== true
    && !isDisabled(field)
}

function onMultiSelectAll(field: FormFieldSpec): void {
  if (!showMultiTools(field)) {
    return
  }
  const values = isMultiAllSelected(field) ? [] : optionsOf(field).map((option) => option.value)
  setValue(field.key, values)
}

function onMultiToggle(key: string, value: string, checked: boolean): void {
  const current = model.value[key]
  const list = Array.isArray(current) ? current : []
  setValue(key, checked ? [...list, value] : list.filter((item) => item !== value))
}

/** 选项级联：静态 options → optionsFrom.map 父值分支 → 数据源解析结果（sourceId 分支）。 */
function optionsOf(field: FormFieldSpec): readonly FormFieldOption[] {
  return resolveFieldOptions(field, model.value, externalOptions.value)
}

/**
 * 数据源级联（optionsFrom.sourceId）：父值变化时向后端注册的数据源取选项。
 * `<b>sourceId 是闭集</b>`：这里的 id 已在 schema 闸门校验过，前端不做任何自由请求。
 */
async function refreshSourceOptions(field: FormFieldSpec): Promise<void> {
  const from = field.optionsFrom
  if (!from?.sourceId) {
    return
  }
  // 序号先行推进（含空父值分支）：清空父值同样让在途请求失效，慢响应不得回填
  const seq = (sourceRequestSeq.get(field.key) ?? 0) + 1
  sourceRequestSeq.set(field.key, seq)
  const parent = model.value[from.dependsOn]
  const isCurrent = (): boolean => sourceRequestSeq.get(field.key) === seq
    && model.value[from.dependsOn] === parent
  if (typeof parent !== 'string' || parent === '') {
    externalOptions.value = { ...externalOptions.value, [field.key]: EMPTY_FORM_FIELD_OPTIONS }
    sourceLoading[field.key] = false
    sourceFailed[field.key] = false
    return
  }
  sourceLoading[field.key] = true
  sourceFailed[field.key] = false
  const failureKey = `${field.key}:${parent}`
  try {
    const resolved = await formOptionSource(from.sourceId, parent)
    if (!isCurrent()) {
      return
    }
    externalOptions.value = { ...externalOptions.value, [field.key]: resolved.options }
    sourceLoading[field.key] = false
  } catch (error) {
    if (!isCurrent()) {
      return
    }
    logCaughtError(error, `form-source:${failureKey}`)
    externalOptions.value = { ...externalOptions.value, [field.key]: EMPTY_FORM_FIELD_OPTIONS }
    sourceLoading[field.key] = false
    sourceFailed[field.key] = true
  }
}

onMounted(() => {
  for (const field of props.schema.fields) {
    if (field.optionsFrom?.sourceId) {
      void refreshSourceOptions(field)
    }
  }
  void nextTick(resizeLongTextareas)
})

/**
 * 卸载出口（F-1 草稿）：卡被拆掉之前把合并窗口里的**最终值**写实。
 *
 * <p>提交 / 取消由 store 收口（提交先 flush、成功/取消再清待写值）；这里兜的是
 * "面板关闭 / 路由切换 / 宿主重渲染"这类卸载 —— 不做这一步，用户最后 250ms 内的输入
 * 会随组件一起消失。只读回放卡没有挂起归属（无待写值），无副作用。
 */
onBeforeUnmount(() => {
  attachmentsDisposed = true
  invalidateAllAttachments()
  if (draftToolCallId) {
    flushFormDraft(draftToolCallId)
  }
})

// ── 附件（G-E） ────────────────────────────────────────────────────────────

function attachmentOf(field: FormFieldSpec): AttachmentMeta | null {
  return attachments.value[field.key] ?? null
}

/** 本地拒绝判定：大小先于类型（用户最可能踩的是大小），返回 null = 放行。 */
function rejectAttachment(file: File): AttachmentRejection | null {
  if (file.size > ATTACHMENT_MAX_BYTES) {
    return 'ATTACHMENT_TOO_LARGE'
  }
  const dot = file.name.lastIndexOf('.')
  const ext = dot < 0 ? '' : file.name.slice(dot + 1).toLowerCase()
  return (ATTACHMENT_EXTENSIONS as readonly string[]).includes(ext) ? null : 'ATTACHMENT_TYPE_UNSUPPORTED'
}

/**
 * 会话就绪闸（Task 4）：上传前确保后端已发号，并返回**最新**会话号（可能刚发出/刚恢复）。
 * 无活动 pinia / store 异常时按"未就绪"处理（宁可不传，也不带空号/无身份头发请求）。
 */
async function ensuredUploadSessionId(): Promise<string> {
  try {
    return await useAiStore().ensureSession()
  } catch (error) {
    logCaughtError(error, 'form-attachment:session')
    return ''
  }
}

function nextAttachmentOperation(key: string): number {
  const seq = (attachmentOperationSeq.get(key) ?? 0) + 1
  attachmentOperationSeq.set(key, seq)
  return seq
}

function invalidateAttachment(key: string): number {
  const seq = nextAttachmentOperation(key)
  attachmentControllers.get(key)?.abort()
  attachmentControllers.delete(key)
  uploading[key] = false
  return seq
}

function invalidateAllAttachments(): void {
  const keys = new Set([...attachmentOperationSeq.keys(), ...attachmentControllers.keys()])
  for (const key of keys) {
    invalidateAttachment(key)
  }
}

watch(
  () => [props.uploadContext?.sessionId, props.uploadContext?.signal] as const,
  (current, previous) => {
    if (current[0] !== previous[0] || current[1] !== previous[1]) {
      invalidateAllAttachments()
    }
  }
)

async function uploadAttachment(
  field: FormFieldSpec,
  file: File,
  uploadContext: NonNullable<typeof props.uploadContext>,
  isCurrent: () => boolean,
  controller: AbortController
): Promise<void> {
  const sessionId = await ensuredUploadSessionId()
  if (!isCurrent() || uploadContext.signal?.aborted) {
    return
  }
  if (sessionId === '') {
    uploadErrors[field.key] = t('ai.gf.attachment.failed')
    return
  }
  const uploaded = await uploadFormAttachment(file, {
    ...uploadContext,
    sessionId,
    signal: controller.signal
  })
  if (!isCurrent()) {
    return
  }
  attachments.value = { ...attachments.value, [field.key]: uploaded }
  setValue(field.key, uploaded.fileId)
  localIssues.value = localIssues.value.filter((issue) => issue.key !== field.key)
}

async function onAttachmentChange(field: FormFieldSpec, event: Event): Promise<void> {
  const seq = invalidateAttachment(field.key)
  const input = event.target as HTMLInputElement
  const file = head(input.files)
  const uploadContext = props.uploadContext
  if (!file || !uploadContext || attachmentsDisposed) {
    return
  }
  delete attachmentRejections[field.key]
  delete uploadErrors[field.key]
  const rejection = rejectAttachment(file)
  if (rejection !== null) {
    attachmentRejections[field.key] = rejection
    input.value = ''
    return
  }
  if (uploadContext.signal?.aborted) {
    return
  }
  const controller = new AbortController()
  attachmentControllers.set(field.key, controller)
  const externalSignal = uploadContext.signal
  const isCurrent = (): boolean => !attachmentsDisposed
    && attachmentOperationSeq.get(field.key) === seq
    && attachmentControllers.get(field.key) === controller
  const onExternalAbort = (): void => {
    if (isCurrent()) {
      invalidateAttachment(field.key)
    }
  }
  externalSignal?.addEventListener('abort', onExternalAbort, { once: true })
  uploading[field.key] = true
  try {
    await uploadAttachment(field, file, uploadContext, isCurrent, controller)
  } catch (error) {
    logCaughtError(error, 'components/AiPanel/FormRenderer.vue:647')
    if (isCurrent()) {
      uploadErrors[field.key] = t('ai.gf.attachment.failed')
    }
  } finally {
    externalSignal?.removeEventListener('abort', onExternalAbort)
    if (isCurrent()) {
      attachmentControllers.delete(field.key)
      uploading[field.key] = false
      input.value = ''
    }
  }
}

function removeAttachment(key: string): void {
  invalidateAttachment(key)
  if (attachmentsDisposed) {
    return
  }
  const next = { ...attachments.value }
  delete next[key]
  attachments.value = next
  setValue(key, '')
  delete uploadErrors[key]
  delete attachmentRejections[key]
}

function formatBytes(size: number | undefined): string {
  if (!size || size <= 0) {
    return ''
  }
  if (size < 1024) {
    return `${size} B`
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`
  }
  return `${(size / (1024 * 1024)).toFixed(1)} MB`
}

// ── 错误（G-A / G-G） ──────────────────────────────────────────────────────

/** 合并本地复核 + 后端结构化错误；同一字段多个问题按出现顺序保留。 */
function fieldIssues(field: FormFieldSpec): FormValueIssue[] {
  const issues: FormValueIssue[] = localIssues.value.filter((issue) => issue.key === field.key)
  for (const issue of props.fieldErrors ?? []) {
    if (issue.key === field.key) {
      issues.push(issue)
    }
  }
  if (attachmentRejections[field.key]) {
    const code = attachmentRejections[field.key]
    issues.push({ key: field.key, code, message: t(`ai.gf.errors.${code}`, { label: field.label }) })
  }
  if (uploadErrors[field.key]) {
    issues.push({ key: field.key, code: 'ATTACHMENT_UPLOAD_FAILED', message: uploadErrors[field.key] })
  }
  return issues
}

/** 已识别错误码使用安全命名参数；未识别错误码使用完整通用文案，不回显原始 message。 */
function issueText(field: FormFieldSpec, issue: FormValueIssue): string {
  const key = `ai.gf.errors.${issue.code}`
  if (te(key)) {
    return t(key, {
      label: field.label,
      limit: field.maxLength ?? (field.variant === 'longText' ? MAX_LONGTEXT_VALUE_CHARS : MAX_TEXT_VALUE_CHARS),
      min: field.minLength ?? field.minItems ?? field.min,
      max: field.maxItems ?? field.max,
      format: textFormatText(field.format)
    })
  }
  return t('ai.gf.errors.UNKNOWN_ERROR')
}

const liveSummary = computed(() => {
  const parts: string[] = []
  for (const field of props.schema.fields) {
    for (const issue of fieldIssues(field)) {
      parts.push(issueText(field, issue))
    }
  }
  return parts.join(t('ai.gf.hintSeparator'))
})

/**
 * 可重试判定（G-A）：后端的 `code` 决定 —— 出现不可重试码时只保留"取消"，
 * 避免用户对着同一张注定失败的表反复点提交。
 */
const canRetry = computed(() => (props.fieldErrors ?? []).every((issue) => isRetryableIssue(issue.code)))

/** 卡级问题只触发完整通用文案，不展示后端或本地原始说明。 */
const cardError = computed(() => {
  const hasCardIssue = localIssues.value.some((issue) => !props.schema.fields.some((field) => issue.key === field.key))
    || (props.fieldErrors ?? []).some((issue) => !props.schema.fields.some((field) => issue.key === field.key))
    || Boolean(props.error)
  return hasCardIssue ? t('ai.gf.cardError') : ''
})

function fieldClass(field: FormFieldSpec): string {
  // 已决只读按**只读配色**：字段仍可读、不落"禁用灰"，但原生不可编辑
  const disabledTone = props.readonly === true ? FI_READONLY : FI_DISABLED
  return `${FI_BASE} ${disabledTone} ${fieldIssues(field).length > 0 ? FI_BAD : FI_IDLE}`
}

/**
 * 提交失败聚焦首个错误字段（a11y 硬要求：错误不能只靠视觉颜色）。
 * 只在本卡子树里找 —— 同页多卡时 `document.getElementById` 会取到**别卡**的同名控件（L-92）。
 */
async function focusFirstIssue(issues: FormValueIssue[]): Promise<void> {
  await nextTick()
  const first = head(issues)
  if (!first) {
    return
  }
  const element = rootRef.value?.querySelector<HTMLElement>(`[id="${controlIdOfKey(first.key)}"]`)
  if (!element) {
    return
  }
  const field = props.schema.fields.find((candidate) => candidate.key === first.key)
  if (field?.type === 'multi_select') {
    const option = element.querySelector<HTMLInputElement>('input[data-multi-option]:not(:disabled)')
    ;(option ?? element).focus()
    return
  }
  element.focus()
}

function onSubmit(): void {
  const issues = checkFormIssues(props.schema, model.value)
  localIssues.value = issues
  if (issues.length > 0) {
    void focusFirstIssue(issues)
    return
  }
  // 提交出口（F-1）：先把合并窗口里的最终值写实（请求被拒/失败回到可填态时草稿仍是最新的；
  // 成功路径由 store 的 clearFormDraft 连待写值一起清掉，不会留下"已提交的草稿"）。
  if (draftToolCallId) {
    flushFormDraft(draftToolCallId)
  }
  emit('submit', visibleValues(props.schema, { ...model.value }))
}
</script>
