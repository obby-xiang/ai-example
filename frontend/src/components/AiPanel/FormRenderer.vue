<template>
  <div :class="readonly ? 'min-w-0' : 'border border-solid border-[#e3e6ea] rounded-[10px] bg-white overflow-hidden mb-2.5'">
    <!-- R-165 S3a：GF 表单卡 = demo v7 `.gf` 结构（v7.html:255-272）
         容器细描边 + 圆角 10 + overflow-hidden ｜ 卡头 ▤+标题+胶囊（9px 11px / 底细线 / #f6f7f9 / 12.8px/650）
         ｜ 字段体 p-[11px] ｜ footer 时间行（9px 11px / 顶细线 / 11.8px #57606a / #f6f7f9）。
         EP 控件全退（el-input / el-input-number / el-switch / el-date-picker / el-select / el-button /
         el-tag / el-alert ⇒ 原生 input / select / checkbox / button + Tailwind 工具类）。
         行为面逐字保留：setValue / checkFormValues 复核 / emit('submit'|'cancel') /
         data-testid="gf-cancel|gf-submit" / :disabled="submitting|disabled" 的禁用语义。 -->
    <!-- 卡头（v7 `.gf-hd`）：▤ + 标题 + 场景胶囊（右贴）。
         2A 批（裁决 2026-10-17 §9 K1）：已决只读态**不渲染本卡头** —— 外层统一卡头
         （左类型图标 + 中性标题/摘要 + 右状态标签 + chevron）承担辨识信息，
         卡体 = 同一张表单（标题 + 字段）原样锁定。
         showHeader=false：AiPanel 待填写 GF 的标题/状态已上提到统一卡头（一卡一头）。 -->
    <div
      v-if="!readonly && showHeader"
      class="flex items-center gap-[7px] px-[11px] py-[9px] border-0 border-b border-solid border-[#eef0f3] bg-[#f6f7f9] text-[12.8px] [font-weight:650]"
    >
      <span class="flex-none text-[#8b949e] text-[11px]" aria-hidden="true">▤</span>
      <span class="min-w-0 overflow-hidden text-ellipsis whitespace-nowrap">{{ schema.title ?? '请填写以下信息' }}</span>
      <span class="flex-none ml-auto text-[9.5px] leading-[1.7] px-[5px] rounded-[5px] whitespace-nowrap font-normal text-[#2563eb] bg-[#eff4ff]">{{ scenarioLabel }}</span>
    </div>

    <div :class="readonly ? '' : 'p-[11px]'">
      <!-- 已决只读态：同一张表单的业务标题（无 ▤ / 无场景胶囊，与 demo 只读样张一致） -->
      <div v-if="readonly" class="px-[11px] pt-[10px] text-[13.2px] [font-weight:650] text-[#1f2328]">
        {{ schema.title ?? '请填写以下信息' }}
      </div>
      <!-- 字段区：六型白名单控件，文案一律文本插值，永不 v-html。
           R-165 S5a 项 4：字段区 = **纯限高内滚**，按钮行与卡级错误条移出滚动区常驻
           （v7 `.gf` 口径：max-h + overflow-y-auto + 操作行在其下）。
           2A 批（裁决 §14.4）：表单 / 组合族限高改**双约束** `min(320px,45vh)` —— 正常窗口取
           320px 上限，矮窗口按视口比例收紧。 -->
      <div :class="['min-w-0 max-h-[min(320px,45vh)] overflow-y-auto', readonly ? 'mx-[11px] mt-[9px]' : 'pr-[4px]', SCROLLBAR_CLASS]" data-testid="gf-fields">
      <div v-for="field in schema.fields" :key="field.key" class="mb-[11px]">
        <!-- S6 批1（长内容审计 #12）：字段 label 由模型生成，可长且无空格 ⇒ 补 break-words
             （原无断词类，长 label 靠字段区 `overflow-y-auto` 溢出，横条或被卡 overflow-hidden 裁）。 -->
        <span class="block text-[12.2px] mb-[4px] break-words [overflow-wrap:anywhere]" :class="fieldProblem(field) ? 'text-[#c0392b]' : 'text-[#57606a]'">
          {{ field.label }}<span v-if="field.required" class="text-[#c0392b]"> *</span>
        </span>
        <input
          v-if="field.type === 'text'"
          type="text"
          :class="fieldClass(field)"
          :value="textValue(field.key)"
          :placeholder="field.placeholder"
          :disabled="disabled"
          @input="setValue(field.key, ($event.target as HTMLInputElement).value)"
        />
        <input
          v-else-if="field.type === 'number'"
          type="number"
          :class="fieldClass(field)"
          :value="numberValue(field.key)"
          :placeholder="field.placeholder"
          :disabled="disabled"
          @input="onNumberInput(field.key, $event)"
        />
        <!-- boolean：原生 checkbox 自绘双态滑块（28×28 门卡同口径：不引第三方控件、不用 peer 变体） -->
        <span v-else-if="field.type === 'boolean'" class="relative inline-flex w-[34px] h-[18px] align-middle">
          <input
            type="checkbox"
            class="absolute inset-0 m-0 w-full h-full appearance-none rounded-[999px] cursor-pointer disabled:cursor-default [outline:none]"
            :checked="model[field.key] === true"
            :disabled="disabled"
            @change="setValue(field.key, ($event.target as HTMLInputElement).checked)"
          />
          <span
            class="absolute inset-0 rounded-[999px] transition-colors duration-[150ms]"
            :class="model[field.key] === true ? 'bg-[#2563eb]' : 'bg-[#e3e6ea]'"
            aria-hidden="true"
          ></span>
          <span
            class="absolute top-[2px] left-[2px] w-[14px] h-[14px] rounded-[50%] bg-white transition-transform duration-[150ms]"
            :class="model[field.key] === true ? 'translate-x-[16px]' : ''"
            aria-hidden="true"
          ></span>
        </span>
        <input
          v-else-if="field.type === 'date'"
          type="date"
          :class="fieldClass(field)"
          :value="textValue(field.key)"
          :disabled="disabled"
          @input="setValue(field.key, ($event.target as HTMLInputElement).value)"
        />
        <select
          v-else-if="field.type === 'enum'"
          :class="fieldClass(field)"
          :value="textValue(field.key)"
          :disabled="disabled"
          @change="setValue(field.key, ($event.target as HTMLSelectElement).value)"
        >
          <option value="">{{ `请选择${field.label}` }}</option>
          <option v-for="option in field.options ?? []" :key="option.value" :value="option.value">
            {{ option.label ?? option.value }}
          </option>
        </select>
        <!-- multi_select：原生 checkbox 胶囊组（同 el-select multiple 的多值语义，可点可取消） -->
        <span v-else-if="field.type === 'multi_select'" class="flex flex-wrap gap-[6px]">
          <label
            v-for="option in field.options ?? []"
            :key="option.value"
            class="inline-flex items-center gap-[5px] rounded-[999px] border border-solid px-[11px] py-[4px] text-[12.2px] transition-colors duration-[150ms]"
            :class="[
              multiChecked(field, option.value)
                ? 'border-[#cddcff] bg-[#eff4ff] text-[#2563eb]'
                : 'border-[#e3e6ea] bg-white text-[#57606a]',
              readonly ? 'cursor-default' : 'cursor-pointer'
            ]"
          >
            <input
              type="checkbox"
              class="m-0"
              :checked="multiChecked(field, option.value)"
              :disabled="disabled"
              @change="onMultiToggle(field.key, option.value, ($event.target as HTMLInputElement).checked)"
            />
            {{ option.label ?? option.value }}
          </label>
        </span>
        <!-- 防御兜底：parseFormSchema 已保证六型，走到这里说明 schema 异常，拒渲染该字段 -->
        <span v-else :class="FMSG">
          {{ `字段「${field.label}」类型不支持（${String(field.type)}），未渲染` }}
        </span>

        <span v-if="fieldProblem(field)" :class="[FMSG, 'mt-[5px]']">{{ fieldProblem(field) }}</span>
      </div>
      </div>

      <!-- 卡级错误条：schema 之外字段等无法归属到单字段的问题 + 后端 FORM_RESULT_REJECTED 的拒绝说明
           （R-165 S5a：移出字段滚动区，与操作行一同常驻）。已决只读态不渲染（无提交动作）。 -->
      <span v-if="!readonly && cardError" :class="[FMSG, 'mt-[11px]']" data-testid="gf-error">{{ cardError }}</span>

      <!-- 操作区：显式取消入口 + 提交（R-165 S5a 项 10：取消 + 提交**相邻右对齐** gap-2，
           不再左右分置；v7 `.gf` 操作行口径）。
           2A 批（裁决 §3）：按钮防折行 —— 按钮 `whitespace-nowrap + flex-none`，行 `flex-wrap`
           （空间不足时按钮组整行下移）；已决只读态**整块不渲染**（K1：零操作按钮）。 -->
      <div v-if="!readonly" class="flex flex-wrap items-center justify-end gap-2 mt-[10px] pt-[10px] border-0 border-t border-solid border-[#eef0f3]">
        <button
          type="button"
          data-testid="gf-cancel"
          :class="GF_CANCEL"
          :disabled="submitting"
          @click="emit('cancel')"
        >取消</button>
        <button
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
          ></span>{{ submitting ? '提交中…' : '提交' }}
        </button>
      </div>
    </div>

    <!-- footer 时间行（形制照 v7.html:685 的表单脚注行：顶细线 + 9px 11px + 11.8px #57606a + #f6f7f9 底）
         已决只读态不渲染（时限已结束、表单已锁定）。 -->
    <p
      v-if="!readonly && remainingSeconds !== null"
      data-testid="gf-deadline"
      class="m-0 px-[11px] py-[9px] border-0 border-t border-solid border-[#eef0f3] text-[11.8px] text-[#57606a] bg-[#f6f7f9]"
    >
      {{ expired ? '已超时，表单不可再提交' : `剩余填写时间 ${formatCountdown(remainingSeconds)}` }}
    </p>
  </div>
</template>

<script setup lang="ts">
/**
 * 生成式表单渲染器（GF-B / DC-15）。
 *
 * 组件纪律（S4.4 §1.1）：
 * - 取值与提交前复核只走 buildInitialValues / checkFormValues —— schema 已由 store 层的
 *   parseFormSchema 防御性解析（非法即取消收敛，不到这里）；文案一律文本插值，永不 v-html；
 * - **R-165 S3a：零 EP 承载** —— el-input / el-input-number / el-switch / el-date-picker /
 *   el-select / el-tag / el-alert / el-button 全退，改原生 input / select / checkbox / button
 *   + 内联 Tailwind 工具类（demo v7 `.gf` / `.fi` / `.fmsg` 配方照搬，v7.html:255-272）；
 *   无 scoped 样式块、无行内 style。
 *
 * 与 ConditionForm.vue（视觉基线）同款的"标签在上、控件在下、每字段一列"密度。
 */
import { computed, ref, watch } from 'vue'
import { buildInitialValues, checkFormValues, type FormFieldSpec, type FormSpec } from '@/types/form-schema'
import { SCROLLBAR_CLASS } from '@/utils/markdown'
import { formatCountdown } from '@/utils/format'

/** 六型取值并集（number 的"未填"用 undefined 表示）。 */
type FormModelValue = string | number | boolean | undefined | string[]

const props = withDefaults(defineProps<{
  schema: FormSpec
  submitting: boolean
  /** 后端 FORM_RESULT_REJECTED 的拒绝说明（挂起仍在等待，可改值重发或取消） */
  error: string | null
  /** 剩余填写秒数（null = 帧未带时限） */
  remainingSeconds: number | null
  /**
   * 2A 批（裁决 2026-10-17 §9 K1 / §10 F9）：**已决只读态** —— 同一张表单原样锁定：
   * 六型控件全部原生 `disabled`、值回填、操作按钮行与时限脚注整块不渲染、卡级错误条不渲染。
   */
  readonly?: boolean
  /**
   * 只读态回填值（GF 提交回执的 `submittedValues`）。`null`/缺省 ⇒ 回落 `buildInitialValues`
   * 的初始态 —— 取消 / 超时收尾按 F9「未提交不记录」不保留任何填写内容。
   */
  initialValues?: Record<string, unknown> | null
  /**
   * 卡头开关（默认 true）：独立使用保留 v7 `.gf-hd` 卡头；
   * AiPanel 待填写 GF 由统一卡头承载标题/状态时传 false（一卡一头，不重复标题）。
   */
  showHeader?: boolean
}>(), { showHeader: true })

const emit = defineEmits<{
  (event: 'submit', values: Record<string, unknown>): void
  (event: 'cancel'): void
}>()

/**
 * v7 控件配方 `.fi`（w-full + 细描边 + 圆角 8 + padding 6px 9px + 13px）。
 * preflight 关闭（tailwind.config.js corePlugins.preflight=false）⇒ 描边样式一律显式 `border-solid`
 * （R-165 S1 §5① 实证：纯 `border-*` 只出 width、style 恒 none）。
 * focus 态走 `focus:` 变体（伪类抬升特异度，稳定压过静/错两态的底色）。
 */
const FI_BASE = 'w-full border rounded-[8px] px-[9px] py-[6px] font-[inherit] text-[13px] leading-[normal] text-[#1f2328] [outline:none] transition-colors duration-[150ms] focus:border-[#2563eb] disabled:cursor-default'
/**
 * 待决态禁用观感（提交中 / 超时）：灰底灰字。
 * 2A 批（裁决 §11 G3 → §12 H2）：已决只读态只保留 `disabled:text-[#57606a]` 一条可读性灰字，
 * 底色与边框与待决态完全一致（「锁定不降对比度」），故与下面一条分开声明。
 */
const FI_DISABLED = 'disabled:bg-[#f6f7f9] disabled:text-[#8b949e]'
const FI_READONLY = 'disabled:bg-white disabled:text-[#57606a]'
const FI_IDLE = 'border-solid border-[#e3e6ea] bg-white'
const FI_BAD = 'border-solid border-[#c0392b] bg-[#fdf1ef]'
/** v7 校验错误条 `.fmsg`（11.8px 红字 + 红底 + 浅红描边 + 圆角 6 + padding 5px 8px）。 */
const FMSG = 'block text-[11.8px] leading-[1.5] text-[#c0392b] bg-[#fdf1ef] border border-solid border-[#f0d5d0] rounded-[6px] px-[8px] py-[5px]'
/** 双钮（同 R-165 S2 门卡口径：取消 = 白底细描边；提交 = 蓝底白字 semibold）。
 *  R-165 S5a 项 10：提交钮撤 `ml-auto` —— 操作行改 `justify-end gap-2`，取消与提交**相邻右对齐**。 */
const GF_CANCEL = 'flex-none whitespace-nowrap min-w-[56px] rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-[#e3e6ea] transition-all duration-[150ms] bg-white text-[#57606a] hover:border-[#8b949e] hover:text-[#1f2328] disabled:opacity-50 disabled:cursor-default'
const GF_SUBMIT = 'flex-none whitespace-nowrap min-w-[56px] rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-transparent transition-all duration-[150ms] bg-[#2563eb] text-white font-semibold hover:brightness-[1.08] disabled:opacity-50 disabled:cursor-default disabled:bg-[#8b949e] disabled:font-normal'

/**
 * 初始模型：待决态走 `buildInitialValues`（枚举初值已合法默认，见 types/form-schema.ts）；
 * 已决只读态额外合并 `initialValues`（`submittedValues`）—— 只接受与字段类型形状相符的值，
 * 形状不符的键回落初始态（不把脏数据塞进受控控件）。
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
    if (field.type === 'text' || field.type === 'date') {
      if (typeof value === 'string') {
        base[field.key] = value
      }
    } else if (field.type === 'enum') {
      // 枚举只接受落在 options 内的值（非法值回落初值，不把脏值塞进 select）
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
 * 本次提交前复核的问题清单。原实现是单串 `localError` —— 错态要落到**具体字段**
 * （v7 红框 + 字段下 `.fmsg`），故改存数组、按 checkFormValues 的 `「label」` 前缀归属。
 */
const localProblems = ref<string[]>([])

/**
 * 只读态值回填必须跟随 `initialValues` 后到：结局帧先切只读时卡已挂载（v-show 折叠也挂载），
 * `submittedValues` 往往在 `POST /frontend-tool-result` 应答后才写回 —— 只靠 setup 初始化会让
 * model 永久停在 schema 初始态。值再到 / 只读再到两种顺序都重灌；编辑态不重灌（不覆盖本地输入）。
 * `initialValues` 变 null（取消 / 超时收尾，F9 未提交不记录）同口径回落初始态。
 */
function rehydrateReadonlyModel(): void {
  if (props.readonly !== true) {
    return
  }
  model.value = initialModel()
  localProblems.value = []
}

watch(
  () => [props.readonly, props.initialValues] as const,
  rehydrateReadonlyModel,
  { deep: true, immediate: true }
)

const expired = computed(() => props.remainingSeconds !== null && props.remainingSeconds <= 0)
/**
 * 禁用判据：提交中 / 已超时 / **已决只读**（K1：同一张表单控件原生 disabled 锁定）。
 * 只读态下不再有提交动作，置 disabled 纯属控件锁定。
 */
const disabled = computed(() => props.readonly === true || props.submitting || expired.value)

const scenarioLabel = computed(() => (props.schema.scenario === 'FILTER' ? '筛选条件' : '信息确认'))

/** 单字段取值投影（原生控件的 value 只吃 string/number，避免模板里散落 `as` 断言）。 */
function textValue(key: string): string {
  const value = model.value[key]
  return typeof value === 'string' ? value : ''
}

function numberValue(key: string): number | string {
  const value = model.value[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : ''
}

function setValue(key: string, value: FormModelValue): void {
  model.value[key] = value
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

/** multi_select：原生 checkbox 组的勾选/取消（值为选项 value 数组，同 el-select multiple 语义）。 */
function onMultiToggle(key: string, value: string, checked: boolean): void {
  const current = model.value[key]
  const list = Array.isArray(current) ? current : []
  setValue(key, checked ? [...list, value] : list.filter((item) => item !== value))
}

/** 字段级问题归属：checkFormValues 的每条问题都带 `「label」` 前缀（types/form-schema.ts 守恒）。 */
function fieldProblem(field: FormFieldSpec): string | null {
  return localProblems.value.find((problem) => problem.includes(`「${field.label}」`)) ?? null
}

/** 卡级问题 = 无法归属到单字段的问题（如「schema 之外字段」）+ 后端 FORM_RESULT_REJECTED 的说明。 */
const cardError = computed(() => {
  const rest = localProblems.value.filter(
    (problem) => !props.schema.fields.some((field) => problem.includes(`「${field.label}」`))
  )
  if (props.error) {
    rest.push(props.error)
  }
  return rest.join('；')
})

function fieldClass(field: FormFieldSpec): string {
  const disabledTone = props.readonly === true ? FI_READONLY : FI_DISABLED
  return `${FI_BASE} ${disabledTone} ${fieldProblem(field) ? FI_BAD : FI_IDLE}`
}

function onSubmit(): void {
  const values = { ...model.value }
  const problems = checkFormValues(props.schema, values)
  if (problems.length > 0) {
    localProblems.value = problems
    return
  }
  localProblems.value = []
  emit('submit', values)
}
</script>
