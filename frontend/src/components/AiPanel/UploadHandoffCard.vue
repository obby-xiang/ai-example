<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import BusinessCardHeader from '@/components/AiPanel/BusinessCardHeader.vue'
import { UPLOAD_ACCEPT, UPLOAD_MAX_BYTES } from '@/constants/upload'
import { unicodeCharacterCount } from '@/utils/display-text'

type UploadHandoffStatus = 'waiting' | 'uploading' | 'uploaded' | 'failed' | 'unsupported' | 'missing' | 'cancelled'
type HeaderTone = 'neutral' | 'blue' | 'green' | 'amber' | 'red' | 'purple' | 'gray'

const props = withDefaults(defineProps<{
  status: UploadHandoffStatus
  accept?: string[] | string | null
  label?: string | null
  reason?: string | null
  fileName?: string | null
  title?: string | null
  interactive?: boolean
}>(), {
  accept: null,
  label: null,
  reason: null,
  fileName: null,
  title: null,
  interactive: true
})

const emit = defineEmits<{
  (event: 'choose'): void
  (event: 'cancel'): void
}>()

const { t } = useI18n()
const bodyId = `upload-handoff-body-${useId()}`
const expandedOverride = ref<boolean | null>(null)

const headerTitle = computed(() => props.title?.trim() || t('ai.uploadHandoff.title'))
const hasLongTitle = computed(() => unicodeCharacterCount(headerTitle.value) > 32)
const expanded = computed({
  get: () => expandedOverride.value ?? (hasLongTitle.value || (props.status !== 'uploaded' && props.status !== 'cancelled')),
  set: (value: boolean) => {
    expandedOverride.value = value
  }
})

const statusLabel = computed(() => t(`ai.uploadHandoff.status.${props.status}`))
const statusTone = computed<HeaderTone>(() => {
  switch (props.status) {
    case 'uploaded':
      return 'green'
    case 'failed':
    case 'missing':
      return 'red'
    case 'unsupported':
      return 'amber'
    case 'cancelled':
      return 'gray'
    default:
      return 'blue'
  }
})

const entryLabel = computed(() => props.label ?? t('ai.uploadHandoff.fallbackEntry'))
const displayFileName = computed(() => {
  const raw = props.fileName?.trim()
  if (!raw) {
    return ''
  }
  return raw.split(/[\\/]/).pop() ?? ''
})
const subtitle = computed(() => displayFileName.value || undefined)

const acceptText = computed(() => {
  const raw = props.accept
  const list = Array.isArray(raw)
    ? raw
    : typeof raw === 'string' && raw !== ''
      ? raw.split(',').map((item) => item.trim()).filter((item) => item !== '')
      : UPLOAD_ACCEPT.split(',')
  const effective = list.length > 0 ? list : UPLOAD_ACCEPT.split(',')
  return effective.map((item) => `.${item.replace(/^\./, '')}`).join(' / ')
})
const maxSizeText = `${UPLOAD_MAX_BYTES / 1024 / 1024}MB`
const maxFilesPerSelection = 1

const hasReason = computed(() => Boolean(props.reason?.trim()))
const isRetry = computed(() => props.status === 'failed' || (props.status === 'waiting' && hasReason.value))
const chooseDisabled = computed(() => props.status === 'unsupported' || props.status === 'missing')
const showChoose = computed(() => props.interactive && ['waiting', 'failed', 'unsupported', 'missing'].includes(props.status))
const showCancel = computed(() => props.interactive && props.status !== 'uploaded' && props.status !== 'cancelled')
const showEntryHint = computed(() => props.interactive && ['waiting', 'failed', 'unsupported'].includes(props.status))

const hint = computed(() => {
  if (props.status === 'unsupported') {
    return hasReason.value ? props.reason : t('ai.uploadHandoff.hintDegraded')
  }
  if (props.status === 'missing') {
    return t('ai.uploadHandoff.hintMissing')
  }
  if (props.status === 'failed' || (props.status === 'waiting' && hasReason.value)) {
    return hasReason.value ? props.reason : t('ai.uploadHandoff.hintFailed')
  }
  if (props.status === 'cancelled') {
    return t('ai.uploadHandoff.hintCancelled')
  }
  if (props.status === 'uploaded' && displayFileName.value === '') {
    return t('ai.uploadHandoff.hintUploaded')
  }
  return null
})

function onChoose(): void {
  if (!showChoose.value || chooseDisabled.value) {
    return
  }
  emit('choose')
}

const PRIMARY_BUTTON = 'min-w-[84px] flex-none whitespace-nowrap rounded-[8px] border border-solid border-transparent bg-[#2563eb] px-[13px] py-[5px] text-[12.6px] font-semibold text-white transition-all duration-[150ms] hover:brightness-[1.08] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#2563eb] disabled:cursor-default disabled:bg-[#8b949e] disabled:font-normal disabled:opacity-50 motion-reduce:transition-none'
const SECONDARY_BUTTON = 'min-w-[56px] flex-none whitespace-nowrap rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[13px] py-[5px] text-[12.6px] text-[#57606a] transition-colors duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#2563eb] motion-reduce:transition-none'
</script>

<template>
  <div class="min-w-0" data-testid="upload-handoff">
    <BusinessCardHeader
      icon="upload"
      :title="headerTitle"
      :subtitle="subtitle"
      :status="statusLabel"
      :tone="statusTone"
      :expanded="expanded"
      :controls="bodyId"
      @toggle="expanded = !expanded"
    />
    <span class="sr-only" role="status" aria-live="polite">{{ statusLabel }}</span>

    <div :id="bodyId" v-show="expanded" class="px-[10px] pb-[10px]">
      <div class="flex flex-col gap-[8px]">
        <p v-if="hasLongTitle" class="m-0 break-words text-[12.6px] font-semibold leading-[1.55] text-[#1f2328] [overflow-wrap:anywhere]">
          {{ headerTitle }}
        </p>

        <div v-if="displayFileName" class="flex min-w-0 items-center gap-[8px] text-[12.2px] leading-[1.55] text-[#57606a]" data-testid="upload-handoff-file">
          <span class="min-w-0 break-all">{{ displayFileName }}</span>
          <span v-if="status === 'uploading'" class="shrink-0 text-[#8b949e]">{{ t('ai.uploadHandoff.status.uploading') }}</span>
        </div>

        <p v-if="status === 'uploading'" class="m-0 flex items-center gap-[6px] text-[12.2px] leading-[1.55] text-[#57606a]">
          <span class="inline-block h-[11px] w-[11px] animate-spin rounded-[50%] border-[1.5px] border-solid border-[#cddcff] border-t-[#2563eb] motion-reduce:animate-none" aria-hidden="true"></span>
          {{ t('ai.uploadHandoff.uploadingHint') }}
        </p>

        <template v-if="status !== 'uploading' && status !== 'uploaded' && status !== 'cancelled'">
          <p class="m-0 text-[12.2px] leading-[1.55] text-[#57606a]">
            {{ t('ai.uploadHandoff.limitsHint', { accept: acceptText, maxSize: maxSizeText, maxFiles: maxFilesPerSelection }) }}
          </p>
          <p v-if="showEntryHint" class="m-0 text-[12.2px] leading-[1.55] text-[#57606a]">
            {{ t('ai.uploadHandoff.orClick', { label: entryLabel }) }}
          </p>
        </template>

        <p v-if="hint" class="m-0 text-[12.2px] leading-[1.55] text-[#57606a]" data-testid="upload-handoff-hint">
          {{ hint }}
        </p>

        <div v-if="showChoose || showCancel" class="flex flex-wrap items-center gap-[8px] pt-[2px]">
          <button
            v-if="showChoose"
            type="button"
            :class="PRIMARY_BUTTON"
            data-testid="upload-handoff-choose"
            :disabled="chooseDisabled"
            @click="onChoose"
          >
            {{ isRetry ? t('ai.uploadHandoff.retry') : t('ai.uploadHandoff.choose') }}
          </button>
          <button
            v-if="showCancel"
            type="button"
            :class="SECONDARY_BUTTON"
            data-testid="upload-handoff-cancel"
            @click="emit('cancel')"
          >
            {{ t('ai.uploadHandoff.discard') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
