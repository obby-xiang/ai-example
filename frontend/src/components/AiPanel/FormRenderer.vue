<template>
  <div
    ref="rootRef"
    :data-testid="`gf-form-${scope}`"
    :class="readonly || flat ? 'min-w-0' : 'border border-solid border-[#e3e6ea] rounded-[10px] bg-white overflow-hidden mb-2.5'"
  >
    <!-- R-165 S3a：GF 表单卡 = demo v7 `.gf` 结构（v7.html:255-272）
         容器细描边 + 圆角 10 + overflow-hidden ｜ 卡头 ▤+标题+胶囊（9px 11px / 底细线 / #f6f7f9 / 12.8px/650）
         ｜ 字段体 p-[11px]。2026-10-20 去超时裁决：时限脚注整块退役（表单一直可填）；
         嵌入态（flat）单框 —— 描边/底色/外边距/内边距交给宿主卡容器。
         EP 控件全退（原生 input / select / checkbox / button + Tailwind 工具类）。
         GF 优化批（G-G）：全部 chrome 文案走 locales（zh-CN / en-US），控件与错误位带 a11y 关联。 -->
    <div
      v-if="!readonly && showHeader"
      class="flex items-center gap-[7px] px-[11px] py-[9px] border-0 border-b border-solid border-[#eef0f3] bg-[#f6f7f9] text-[12.8px] [font-weight:650]"
    >
      <span class="flex-none text-[#8b949e] text-[11px]" aria-hidden="true">▤</span>
      <span class="min-w-0 overflow-hidden text-ellipsis whitespace-nowrap">{{ schema.title ?? t('ai.gf.title') }}</span>
      <span class="flex-none ml-auto text-[9.5px] leading-[1.7] px-[5px] rounded-[5px] whitespace-nowrap font-normal text-[#2563eb] bg-[#eff4ff]">{{ scenarioLabel }}</span>
    </div>

    <div :class="readonly || flat ? '' : 'p-[11px]'">
      <div :class="['min-w-0 max-h-[min(320px,45vh)] overflow-y-auto', readonly ? '' : 'pr-[4px]', SCROLLBAR_CLASS]" data-testid="gf-fields">
      <div v-for="field in visibleFields" :key="field.key" :class="isFieldOpen(field) ? 'mb-[11px]' : 'mb-0'">
        <!-- 高级条件渐进披露（AR-07）：长尾可选条件折叠，按钮只出现一次（高级区第一张之前） -->
        <div v-if="!readonly && isAdvancedGroupStart(field)" class="mb-[11px]">
          <button
            type="button"
            data-testid="gf-advanced-toggle"
            :aria-expanded="advancedOpen"
            class="rounded-[6px] border border-solid border-[#e3e6ea] bg-white px-[9px] py-[3px] text-[12px] text-[#57606a] transition-colors duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328]"
            @click="advancedOpen = !advancedOpen"
          >{{ advancedOpen ? t('ai.gf.advancedCollapse') : t('ai.gf.advancedExpand', { count: advancedCount }) }}</button>
        </div>
        <template v-if="isFieldOpen(field)">
        <label
          :for="controlId(field)"
          class="block text-[12.2px] mb-[4px] break-words [overflow-wrap:anywhere]"
          :class="fieldIssues(field).length > 0 ? 'text-[#c0392b]' : 'text-[#57606a]'"
        >
          {{ field.label }}<span v-if="isRequired(field)" class="text-[#c0392b]" aria-hidden="true"> *</span>
        </label>
        <span :id="hintId(field)" class="sr-only">{{ hintOf(field) }}</span>

        <!-- text：format 决定 input type；longText 走 textarea（上限 2000） -->
        <textarea
          v-if="field.type === 'text' && field.variant === 'longText'"
          :id="controlId(field)"
          :data-testid="controlTestId(field)"
          :class="fieldClass(field)"
          :value="textValue(field.key)"
          :placeholder="field.placeholder"
          :maxlength="MAX_LONGTEXT_VALUE_CHARS"
          :disabled="isDisabled(field)"
          :aria-invalid="fieldIssues(field).length > 0"
          :aria-required="isRequired(field)"
          :aria-describedby="describedBy(field)"
          rows="4"
          @input="setValue(field.key, ($event.target as HTMLTextAreaElement).value)"
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
        <!-- boolean：原生 checkbox 自绘双态滑块（28×28 门卡同口径：不引第三方控件、不用 peer 变体）。
             批G · G7：外层用 <label> 包裹 —— 装饰层（轨道 + 圆点）绝对定位盖在 input 上，
             `pointer-events-none` 保证真实点击落到 input；即便装饰层将来改样式，label 的
             原生激活行为也能把点击转发给控件（实测缺陷：真实鼠标点不动、只有 DOM .click() 生效）。 -->
        <label v-else-if="field.type === 'boolean'" class="relative inline-flex w-[34px] h-[18px] align-middle">
          <input
            :id="controlId(field)"
            :data-testid="controlTestId(field)"
            type="checkbox"
            class="absolute inset-0 m-0 w-full h-full appearance-none rounded-[999px] cursor-pointer disabled:cursor-default [outline:none]"
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
            class="pointer-events-none absolute top-[2px] left-[2px] w-[14px] h-[14px] rounded-[50%] bg-white transition-transform duration-[150ms]"
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
          <option v-for="option in optionsOf(field)" :key="option.value" :value="option.value">
            {{ option.label ?? option.value }}
          </option>
        </select>
        <!-- multi_select：原生 checkbox 胶囊组（同 el-select multiple 的多值语义，可点可取消） -->
        <span v-else-if="field.type === 'multi_select'" class="flex flex-wrap gap-[6px]">
          <label
            v-for="option in optionsOf(field)"
            :key="option.value"
            :for="`${controlId(field)}-${option.value}`"
            class="inline-flex items-center gap-[5px] rounded-[999px] border border-solid px-[11px] py-[4px] text-[12.2px] transition-colors duration-[150ms]"
            :class="[
              multiChecked(field, option.value)
                ? 'border-[#cddcff] bg-[#eff4ff] text-[#2563eb]'
                : 'border-[#e3e6ea] bg-white text-[#57606a]',
              readonly ? 'cursor-default' : 'cursor-pointer'
            ]"
          >
            <input
              :id="`${controlId(field)}-${option.value}`"
              :data-testid="`${controlTestId(field)}-${option.value}`"
              type="checkbox"
              class="m-0"
              :checked="multiChecked(field, option.value)"
              :disabled="isDisabled(field)"
              @change="onMultiToggle(field.key, option.value, ($event.target as HTMLInputElement).checked)"
            />
            {{ option.label ?? option.value }}
          </label>
        </span>
        <!-- attachment（G-E）：固定上传口直传，表单值 = fileId；文件内容永不进模型上下文 -->
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
              class="rounded-[6px] border border-solid border-[#e3e6ea] bg-white px-[7px] py-[2px] text-[11.8px] text-[#57606a] hover:border-[#8b949e] hover:text-[#1f2328]"
              @click="removeAttachment(field.key)"
            >{{ t('ai.gf.attachment.remove') }}</button>
          </span>
          <span v-else-if="!readonly" class="mt-[4px] block text-[11.4px] text-[#8b949e]">
            {{ t('ai.gf.attachment.choose') }} · {{ t('ai.gf.attachment.hint') }}
          </span>
        </div>
        <!-- 防御兜底：parseFormSchema 已保证七型，走到这里说明 schema 异常，拒渲染该字段 -->
        <span v-else :class="FMSG">
          {{ t('ai.gf.unsupportedType', { label: field.label, type: String(field.type) }) }}
        </span>

        <span v-if="fieldIssues(field).length > 0" :id="errorId(field)" :class="[FMSG, 'mt-[5px]']">
          {{ fieldIssues(field).map((issue) => issueText(field, issue)).join('；') }}
        </span>
        </template>
      </div>
      </div>

      <!-- 错误 live region（G-G）：新增错误时读屏播报；视觉上由各字段错误条承担 -->
      <span class="sr-only" role="alert" aria-live="polite" data-testid="gf-live-region">{{ liveSummary }}</span>

      <!-- AR-07：级联清值的一次性告知（父值变了 ⇒ 哪些子值被清掉），常驻在字段区下方 -->
      <span
        v-if="!readonly && cascadeNotice"
        class="mt-[11px] block text-[11.8px] leading-[1.5] text-[#57606a] bg-[#f6f7f9] border border-solid border-[#e3e6ea] rounded-[6px] px-[8px] py-[5px]"
        data-testid="gf-cascade-notice"
      >{{ cascadeNotice }}</span>

      <span v-if="!readonly && cardError" :class="[FMSG, 'mt-[11px]']" data-testid="gf-error">{{ cardError }}</span>

      <div v-if="!readonly" class="flex flex-wrap items-center justify-end gap-2 mt-[10px] pt-[10px] border-0 border-t border-solid border-[#eef0f3]">
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
            class="w-[11px] h-[11px] border-solid border-[1.5px] border-[rgba(255,255,255,.4)] border-t-white rounded-[50%] inline-block animate-spin mr-[6px] align-[-1px] motion-reduce:animate-none"
            aria-hidden="true"
          ></span>{{ submitting ? t('ai.gf.submitting') : t('ai.gf.submit') }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 生成式表单渲染器（GF-B / DC-15 + GF 优化批）。
 *
 * 组件纪律（S4.4 §1.1）：
 * - 取值与提交前复核只走 `types/form-schema.ts` 的 buildInitialValues / checkFormIssues /
 *   isFieldVisible / resolveFieldOptions（schema 已由 store 层的 parseFormSchema 防御性解析，
 *   非法即取消收敛，不到这里）；文案一律文本插值，永不 v-html；
 * - **零 EP 承载** —— 原生 input / select / textarea / checkbox / button + Tailwind 工具类；
 *   无 scoped 样式块、无行内 style；
 * - GF 优化批（G-A/G-D/G-E/G-F/G-G）：结构化错误按 key 点亮、联动五原语、附件上传、长文本域、
 *   `<label for>`/`aria-invalid`/`aria-describedby`/live region/首错聚焦，chrome 文案全走 locales。
 * - AR-07（批次4 前端C）：① 常用筛选条件**一屏**（不拆成一屏一问），长尾可选条件走「更多条件」
 *   渐进披露 —— 必填 / 已填值 / 有错误的字段永不折叠；② 父值变化清掉子值时给**一句**聚合提示
 *   （`gf-cascade-notice`），不逐字段弹条。
 */
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
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
import { nextReadonlyFormScope } from '@/utils/form-scope'
import { uploadFormAttachment, formOptionSource } from '@/api/ai'

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
}

interface AttachmentMeta {
  fileId: string
  fileName: string
  size: number
}

const props = withDefaults(defineProps<{
  schema: FormSpec
  submitting: boolean
  /** 卡级错误（后端 FORM_RESULT_REJECTED 的人读说明；字段级见 fieldErrors） */
  error: string | null
  /**
   * 已决只读态 —— 同一张表单原样锁定：控件全部原生 `disabled`、值回填、
   * 操作按钮行整块不渲染、卡级错误条不渲染。
   */
  readonly?: boolean
  /** 只读态回填值 / 待填态草稿值（形状不符的键回落初始态，不把脏数据塞进受控控件）。 */
  initialValues?: Record<string, unknown> | null
  /** 卡头开关（默认 true）。 */
  showHeader?: boolean
  /** 嵌入态（默认 false）：单框，外框由宿主卡容器承担。 */
  flat?: boolean
  /**
   * 后端结构化错误（G-A）：`{key, code, message}` 列表 —— 前端按 key 点亮对应字段。
   * 与本地复核结果合并渲染（本地在前，后端在后）。
   */
  fieldErrors?: FormValueIssue[] | null
  /** 附件上传归属（G-E）：runId + toolCallId 绑定"本次挂起的这张表单"；缺省时上传控件禁用。 */
  uploadContext?: UploadContext | null
}>(), { showHeader: true, initialValues: null, fieldErrors: null, uploadContext: null })

const emit = defineEmits<{
  (event: 'submit', values: Record<string, unknown>): void
  (event: 'cancel'): void
  /** 草稿镜像（G-B）：每次改动都 emit，由 store 写 sessionStorage（永不进模型上下文）。 */
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
const interactive = props.readonly !== true

/**
 * v7 控件配方 `.fi`（w-full + 细描边 + 圆角 8 + padding 6px 9px + 13px）。
 * preflight 关闭（tailwind.config.js corePlugins.preflight=false）⇒ 描边样式一律显式 `border-solid`。
 * focus 态走 `focus:` 变体（伪类抬升特异度，稳定压过静/错两态的底色）。
 */
const FI_BASE = 'w-full border rounded-[8px] px-[9px] py-[6px] font-[inherit] text-[13px] leading-[normal] text-[#1f2328] [outline:none] transition-colors duration-[150ms] focus:border-[#2563eb] disabled:cursor-default'
const FI_DISABLED = 'disabled:bg-[#f6f7f9] disabled:text-[#8b949e]'
const FI_READONLY = 'disabled:bg-white disabled:text-[#57606a]'
const FI_IDLE = 'border-solid border-[#e3e6ea] bg-white'
const FI_BAD = 'border-solid border-[#c0392b] bg-[#fdf1ef]'
/** v7 校验错误条 `.fmsg`（11.8px 红字 + 红底 + 浅红描边 + 圆角 6 + padding 5px 8px）。 */
const FMSG = 'block text-[11.8px] leading-[1.5] text-[#c0392b] bg-[#fdf1ef] border border-solid border-[#f0d5d0] rounded-[6px] px-[8px] py-[5px]'
const GF_CANCEL = 'flex-none whitespace-nowrap min-w-[56px] rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-[#e3e6ea] transition-all duration-[150ms] bg-white text-[#57606a] hover:border-[#8b949e] hover:text-[#1f2328] disabled:opacity-50 disabled:cursor-default'
const GF_SUBMIT = 'flex-none whitespace-nowrap min-w-[56px] rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-transparent transition-all duration-[150ms] bg-[#2563eb] text-white font-semibold hover:brightness-[1.08] disabled:opacity-50 disabled:cursor-default disabled:bg-[#8b949e] disabled:font-normal'

/**
 * 初始模型：待填态走 `buildInitialValues`（枚举初值已合法默认）；
 * 只读态 / 草稿恢复额外合并 `initialValues` —— 只接受与字段类型形状相符的值。
 */
function initialModel(): Record<string, FormModelValue> {
  const base = buildInitialValues(props.schema) as Record<string, FormModelValue>
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
/**
 * 建卡时的初始模型：判断"用户是不是真的填过这一格"的对照基线（与它相同 = 还是默认态）。
 * 披露判据用它 —— 枚举初值恒有值（首个合法选项），把它当"已填"会让高级条件永远摊开。
 */
const defaultModel = initialModel()
/** 本卡根元素（首错聚焦只在本子树里找，同页多卡不串）。 */
const rootRef = ref<HTMLElement | null>(null)
/** 本次提交前复核的问题（结构化：按 key 归属字段）。 */
const localIssues = ref<FormValueIssue[]>([])
/** 草稿默认值联动的"用户已碰过"集合：被碰过的字段不再被 defaultFrom 覆盖。 */
const touched = reactive(new Set<string>())
/** 附件元数据（文件名/大小只在浏览器里用于展示；值仍是 fileId）。 */
const attachments = ref<Record<string, AttachmentMeta>>({})
const uploading = reactive<Record<string, boolean>>({})
const uploadErrors = reactive<Record<string, string>>({})
/** 浏览器侧超限/类型拒绝（与后端同口径；不发请求，只点亮字段错误）。 */
const attachmentRejections = reactive<Record<string, AttachmentRejection>>({})
/** 数据源级联的结果（optionsFrom.sourceId）：按字段 key 缓存，父值一变就重取。 */
const externalOptions = ref<Record<string, FormFieldOption[]>>({})
/**
 * 数据源请求序号（E：last-wins）。父值每变一次（含清空）就推进一格；
 * 响应回来时序号或父值不再是当前值 ⇒ 丢弃，防止慢请求覆盖新选项。
 */
const sourceRequestSeq = new Map<string, number>()

/**
 * 只读态值回填必须跟随 `initialValues` 后到（结局帧先切只读时卡已挂载）。
 * 编辑态不重灌（不覆盖本地输入）。
 */
function rehydrateReadonlyModel(): void {
  if (props.readonly !== true) {
    return
  }
  model.value = initialModel()
  localIssues.value = []
}

watch(
  () => [props.readonly, props.initialValues] as const,
  rehydrateReadonlyModel,
  { deep: true, immediate: true }
)

/** 草稿来源的附件：只有 fileId（无元数据时展示 id 尾部，避免空白）。 */
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
  },
  { immediate: true }
)

/** 禁用判据：提交中 / 已决只读（联动禁用按字段另判）。 */
const disabled = computed(() => props.readonly === true || props.submitting)

const scenarioLabel = computed(() =>
  props.schema.scenario === 'FILTER' ? t('ai.gf.scenario.filter') : t('ai.gf.scenario.clarify')
)

/** 联动显隐：隐藏字段不渲染、不参与校验、不提交。 */
const visibleFields = computed(() => props.schema.fields.filter((field) => isFieldVisible(field, model.value)))

/**
 * 常用条件一屏能放下的字段数（AR-07 / Task 7 Step 3「常用筛选一屏，高级渐进披露」）。
 * 与 GOV.UK 式「一屏一问」相反：相互影响的筛选条件拆开只会让用户来回翻，
 * 故**常用条件始终同屏**，只有长尾可选条件折叠。
 */
const FILTER_COMMON_FIELD_COUNT = 6

/** 高级条件展开态（纯呈现层；不影响取值、校验与提交）。 */
const advancedOpen = ref(false)

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
    parts.push(t('ai.gf.hint.format', { format: field.format }))
  }
  if (field.type === 'attachment') {
    parts.push(t('ai.gf.attachment.hint'))
  }
  return parts.join(t('ai.gf.hintSeparator'))
}

function describedBy(field: FormFieldSpec): string {
  return fieldIssues(field).length > 0 ? `${hintId(field)} ${errorId(field)}` : hintId(field)
}

function isRequired(field: FormFieldSpec): boolean {
  return isFieldRequired(field, model.value)
}

/** 字段禁用：只读态 / 提交中 / 联动 disabledWhen。 */
function isDisabled(field: FormFieldSpec): boolean {
  return disabled.value || isFieldDisabled(field, model.value)
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

/** 单字段取值投影（原生控件的 value 只吃 string/number）。 */
function textValue(key: string): string {
  const value = model.value[key]
  return typeof value === 'string' ? value : ''
}

function numberValue(key: string): number | string {
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

function multiChecked(field: FormFieldSpec, value: string): boolean {
  const current = model.value[field.key]
  return Array.isArray(current) && current.includes(value)
}

function onMultiToggle(key: string, value: string, checked: boolean): void {
  const current = model.value[key]
  const list = Array.isArray(current) ? current : []
  setValue(key, checked ? [...list, value] : list.filter((item) => item !== value))
}

/** 选项级联：静态 options → optionsFrom.map 父值分支 → 数据源解析结果（sourceId 分支）。 */
function optionsOf(field: FormFieldSpec): FormFieldOption[] {
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
    externalOptions.value = { ...externalOptions.value, [field.key]: [] }
    return
  }
  try {
    const resolved = await formOptionSource(from.sourceId, parent)
    if (!isCurrent()) {
      return
    }
    externalOptions.value = { ...externalOptions.value, [field.key]: resolved.options }
  } catch {
    if (!isCurrent()) {
      return
    }
    // 数据源不可用：选项留空（字段自身仍可按"值不在选项内"提示），不阻塞其余字段
    externalOptions.value = { ...externalOptions.value, [field.key]: [] }
  }
}

onMounted(() => {
  for (const field of props.schema.fields) {
    if (field.optionsFrom?.sourceId) {
      void refreshSourceOptions(field)
    }
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

async function onAttachmentChange(field: FormFieldSpec, event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || !props.uploadContext) {
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
  uploading[field.key] = true
  try {
    const uploaded = await uploadFormAttachment(file, props.uploadContext)
    attachments.value = { ...attachments.value, [field.key]: uploaded }
    setValue(field.key, uploaded.fileId)
    localIssues.value = localIssues.value.filter((issue) => issue.key !== field.key)
  } catch (error) {
    uploadErrors[field.key] =
      error instanceof Error ? error.message : t('ai.gf.attachment.failed')
  } finally {
    uploading[field.key] = false
    input.value = ''
  }
}

function removeAttachment(key: string): void {
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

/** 错误文案：优先按 code 查 locales（双语）；缺键回落后端/本地给的人读 message。 */
function issueText(field: FormFieldSpec, issue: FormValueIssue): string {
  const key = `ai.gf.errors.${issue.code}`
  if (te(key)) {
    return t(key, {
      label: field.label,
      limit: field.maxLength ?? (field.variant === 'longText' ? MAX_LONGTEXT_VALUE_CHARS : MAX_TEXT_VALUE_CHARS),
      min: field.minLength ?? field.minItems ?? field.min,
      max: field.maxItems ?? field.max,
      format: field.format
    })
  }
  return issue.message
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

/** 卡级问题 = 无法归属到单字段的问题 + 后端 FORM_RESULT_REJECTED 的说明。 */
const cardError = computed(() => {  const parts = localIssues.value
    .filter((issue) => !props.schema.fields.some((field) => issue.key === field.key))
    .map((issue) => issue.message)
  for (const issue of props.fieldErrors ?? []) {
    if (!props.schema.fields.some((field) => issue.key === field.key)) {
      parts.push(issue.message)
    }
  }
  if (props.error) {
    parts.push(props.error)
  }
  return parts.join('；')
})

function fieldClass(field: FormFieldSpec): string {
  const disabledTone = props.readonly === true ? FI_READONLY : FI_DISABLED
  return `${FI_BASE} ${disabledTone} ${fieldIssues(field).length > 0 ? FI_BAD : FI_IDLE}`
}

/**
 * 提交失败聚焦首个错误字段（a11y 硬要求：错误不能只靠视觉颜色）。
 * 只在本卡子树里找 —— 同页多卡时 `document.getElementById` 会取到**别卡**的同名控件（L-92）。
 */
async function focusFirstIssue(issues: FormValueIssue[]): Promise<void> {
  await nextTick()
  const first = issues[0]
  if (!first) {
    return
  }
  const element = rootRef.value?.querySelector<HTMLElement>(`[id="${controlIdOfKey(first.key)}"]`)
  element?.focus()
}

function onSubmit(): void {
  const issues = checkFormIssues(props.schema, model.value)
  localIssues.value = issues
  if (issues.length > 0) {
    void focusFirstIssue(issues)
    return
  }
  emit('submit', visibleValues(props.schema, { ...model.value }))
}
</script>
