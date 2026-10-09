<template>
  <div>
    <!-- 表单头：标题 + 场景标签 + 剩余填写时间 -->
    <div class="flex items-center gap-1.5 flex-wrap font-semibold text-[13px]">
      <span>{{ schema.title ?? '请填写以下信息' }}</span>
      <el-tag size="small" effect="plain">{{ scenarioLabel }}</el-tag>
      <span v-if="remainingSeconds !== null" class="font-normal" :class="expired ? 'text-[#c0c4cc]' : 'text-[#e6a23c]'">
        {{ expired ? '已超时，表单不可再提交' : `剩余填写时间 ${formatCountdown(remainingSeconds)}` }}
      </span>
    </div>

    <!-- 字段区：六型白名单控件，文案一律文本插值，永不 v-html -->
    <div v-for="field in schema.fields" :key="field.key" class="mt-2">
      <div class="text-xs text-[#606266] mb-1">
        {{ field.label }}<span v-if="field.required" class="text-[#f56c6c]"> *</span>
      </div>
      <el-input
        v-if="field.type === 'text'"
        :model-value="model[field.key] as string"
        :placeholder="field.placeholder"
        :disabled="disabled"
        @update:model-value="setValue(field.key, $event)"
      />
      <el-input-number
        v-else-if="field.type === 'number'"
        :model-value="model[field.key] as number | undefined"
        :placeholder="field.placeholder"
        :disabled="disabled"
        style="width: 100%"
        @update:model-value="setValue(field.key, $event)"
      />
      <el-switch
        v-else-if="field.type === 'boolean'"
        :model-value="model[field.key] as boolean"
        :disabled="disabled"
        @update:model-value="setValue(field.key, $event)"
      />
      <el-date-picker
        v-else-if="field.type === 'date'"
        :model-value="model[field.key] as string"
        type="date"
        value-format="YYYY-MM-DD"
        :placeholder="field.placeholder ?? '选择日期'"
        :disabled="disabled"
        class="w-full"
        @update:model-value="setValue(field.key, $event)"
      />
      <el-select
        v-else-if="field.type === 'enum'"
        :model-value="model[field.key] as string"
        clearable
        :placeholder="`请选择${field.label}`"
        :disabled="disabled"
        class="w-full"
        @update:model-value="setValue(field.key, $event as string)"
      >
        <el-option
          v-for="option in field.options ?? []"
          :key="option.value"
          :label="option.label ?? option.value"
          :value="option.value"
        />
      </el-select>
      <el-select
        v-else-if="field.type === 'multi_select'"
        :model-value="model[field.key] as string[]"
        multiple
        collapse-tags
        :placeholder="`请选择${field.label}（可多选）`"
        :disabled="disabled"
        class="w-full"
        @update:model-value="setValue(field.key, $event as string[])"
      >
        <el-option
          v-for="option in field.options ?? []"
          :key="option.value"
          :label="option.label ?? option.value"
          :value="option.value"
        />
      </el-select>
      <!-- 防御兜底：parseFormSchema 已保证六型，走到这里说明 schema 异常，拒渲染该字段 -->
      <el-alert
        v-else
        type="error"
        :closable="false"
        show-icon
        :title="`字段「${field.label}」类型不支持（${String(field.type)}），未渲染`"
      />
    </div>

    <!-- 客户端复核 / 后端拒绝（FORM_RESULT_REJECTED）的提示 -->
    <el-alert v-if="localError || error" class="mt-2" type="error" :closable="false" show-icon
      :title="localError || error || ''" />

    <!-- 操作区：显式取消入口 + 提交 -->
    <div class="mt-3 flex justify-end gap-2">
      <el-button size="small" data-testid="gf-cancel" :disabled="submitting" @click="emit('cancel')">取消</el-button>
      <el-button size="small" type="primary" data-testid="gf-submit" :loading="submitting" :disabled="disabled" @click="onSubmit">
        提交
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 生成式表单渲染器（GF-B / DC-15）。
 *
 * 组件纪律（S4.4 §1.1）：
 * - 全部 Element Plus 组件（el-input / el-input-number / el-switch / el-date-picker / el-select）+
 *   内联 Tailwind 工具类；无 <style> 块、无 v-html、无自写 EP 等价物；
 * - schema 已由 store 层的 parseFormSchema 防御性解析（非法即取消收敛，不到这里），
 *   本组件只做取值与提交前复核（checkFormValues）。
 *
 * 与 ConditionForm.vue（视觉基线）同款的"标签在上、控件在下、每字段一列"密度。
 */
import { computed, ref } from 'vue'
import { buildInitialValues, checkFormValues, type FormSpec } from '@/types/form-schema'
import { formatCountdown } from '@/utils/format'

/** 六型取值并集（number 的"未填"用 undefined 表示）。 */
type FormModelValue = string | number | boolean | undefined | string[]

const props = defineProps<{
  schema: FormSpec
  submitting: boolean
  /** 后端 FORM_RESULT_REJECTED 的拒绝说明（挂起仍在等待，可改值重发或取消） */
  error: string | null
  /** 剩余填写秒数（null = 帧未带时限） */
  remainingSeconds: number | null
}>()

const emit = defineEmits<{
  (event: 'submit', values: Record<string, unknown>): void
  (event: 'cancel'): void
}>()

const model = ref<Record<string, FormModelValue>>(buildInitialValues(props.schema) as Record<string, FormModelValue>)
const localError = ref<string | null>(null)

const expired = computed(() => props.remainingSeconds !== null && props.remainingSeconds <= 0)
/** 超时后禁用控件与提交（本地判过期后 store 会收起表单并带 cancelled:true 回灌） */
const disabled = computed(() => props.submitting || expired.value)

const scenarioLabel = computed(() => (props.schema.scenario === 'FILTER' ? '筛选条件' : '信息确认'))

function setValue(key: string, value: FormModelValue): void {
  model.value[key] = value
}

function onSubmit(): void {
  const values = { ...model.value }
  const problems = checkFormValues(props.schema, values)
  if (problems.length > 0) {
    localError.value = problems.join('；')
    return
  }
  localError.value = null
  emit('submit', values)
}
</script>
