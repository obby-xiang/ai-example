<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 上传交接卡（设计稿 §3.3.7）—— 本专项**唯一**新增的"人围栏"外观。
 *
 * 不新造卡片壳：外层容器由 `AiPanel` 的统一工具卡承担（与本文件的 `flat` 同族口径），
 * 这里只有内容与两个动作。动作语义不是"批准/拒绝"：
 * - 「选择文件」= 用户手势（浏览器只认它给的文件句柄）；
 * - 「放弃」= `cancelled=true` 收口（走既有 FRONTEND_CANCELLED，不产生新码）。
 *
 * 三态（waiting / degraded / missing）与 unavailable 共用一套外观，差别只在
 * **主按钮是否可点**与一句可行动说明 —— 禁用态永远伴随"下一步该做什么"。
 */
import { computed } from 'vue'

const { t } = useI18n()

const props = defineProps<{
  status: 'waiting' | 'degraded' | 'missing' | 'unavailable'
  /** 页面允许的扩展名（提示文案用；数组或 `".xlsx,.zip"` 形态都接受） */
  accept?: string[] | string | null
  /** 上传入口的标签（"请点左侧的「…」"） */
  label?: string | null
  /** 状态原因（degraded / missing 时展示） */
  reason?: string | null
}>()

const emit = defineEmits<{
  (event: 'choose'): void
  (event: 'cancel'): void
}>()

const entryLabel = computed(() => props.label ?? '上传 xlsx / zip')

const chooseDisabled = computed(() => props.status !== 'waiting')

const acceptText = computed(() => {
  const raw = props.accept
  const list = Array.isArray(raw)
    ? raw
    : typeof raw === 'string' && raw !== ''
      ? raw.split(',').map((item) => item.trim()).filter((item) => item !== '')
      : ['xlsx', 'zip']
  const effective = list.length > 0 ? list : ['xlsx', 'zip']
  return effective.map((item) => `.${item.replace(/^\./, '')}`).join(' / ')
})

function onChoose(): void {
  if (chooseDisabled.value) {
    return
  }
  // 同步 emit：把"用户手势"原样交给上层（picker 必须在这一栈里被调用）
  emit('choose')
}

const hint = computed(() => {
  if (props.status === 'degraded') {
    return props.reason ?? '当前浏览器不支持在对话内直接选择文件'
  }
  if (props.status === 'missing') {
    return '请先打开导入向导第 1 步（上传入口已不在当前页面）'
  }
  if (props.status === 'unavailable') {
    return props.reason ?? '任务已结束，仅供查看；上传入口不可用'
  }
  return null
})
</script>

<template>
  <div class="flex flex-col gap-2" data-testid="upload-handoff">
    <div class="flex items-center gap-2 text-[12.6px] text-[#1f2328]">
      <span class="inline-flex items-center text-[#2563eb]" aria-hidden="true">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor">
          <path d="M8 1.5l1.2 3.2 3.2 1.2-3.2 1.2L8 10.3 6.8 7.1 3.6 5.9l3.2-1.2z" />
          <path d="M12.4 10l.6 1.6 1.6.6-1.6.6-.6 1.6-.6-1.6-1.6-.6 1.6-.6z" opacity="0.75" />
        </svg>
      </span>
      <span class="font-semibold">{{ t('ai.uploadHandoff.pickFile') }}</span>
    </div>
    <p class="m-0 text-[12.2px] leading-[1.55] text-[#57606a]">
      {{ t('ai.uploadHandoff.acceptHint', { accept: acceptText }) }}
    </p>
    <p v-if="hint" class="m-0 text-[12.2px] leading-[1.55] text-[#57606a]" data-testid="upload-handoff-hint">
      {{ hint }}
    </p>
    <p class="m-0 text-[12.2px] leading-[1.55] text-[#57606a]">
      {{ t('ai.uploadHandoff.orClick', { label: entryLabel }) }}
    </p>
    <div class="flex items-center gap-[9px] pt-1">
      <button
        v-if="status !== 'unavailable'"
        type="button"
        class="flex-none whitespace-nowrap min-w-[84px] rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-transparent transition-all duration-[150ms] bg-[#2563eb] text-white font-semibold hover:brightness-[1.08] disabled:opacity-50 disabled:cursor-default disabled:bg-[#8b949e] disabled:font-normal"
        data-testid="upload-handoff-choose"
        :disabled="chooseDisabled"
        @click="onChoose"
      >
        {{ t('ai.uploadHandoff.choose') }}
      </button>
      <button
        type="button"
        class="flex-none whitespace-nowrap min-w-[56px] rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-[#e3e6ea] transition-all duration-[150ms] bg-white text-[#57606a] hover:border-[#8b949e] hover:text-[#1f2328]"
        data-testid="upload-handoff-cancel"
        @click="emit('cancel')"
      >
        {{ t('ai.uploadHandoff.discard') }}
      </button>
    </div>
  </div>
</template>
