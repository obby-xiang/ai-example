<script setup lang="ts">
import { computed } from 'vue'
import { cardIconPaths, type BusinessCardTone } from './cardPresentation'

const props = withDefaults(defineProps<{
  icon: string
  title: string
  status?: string
  tone?: BusinessCardTone
  expanded: boolean
  controls: string
}>(), {
  status: '',
  tone: 'neutral'
})

const emit = defineEmits<{ toggle: [] }>()

const paths = computed(() => cardIconPaths(props.icon))
const statusClass = computed(() => ({
  neutral: 'border-[#e3e6ea] bg-[#f6f7f9] text-[#57606a]',
  blue: 'border-[#cddcff] bg-[#eff4ff] text-[#2563eb]',
  green: 'border-[#bfe3cd] bg-[#edf7f0] text-[#1a7f45]',
  amber: 'border-[#fde68a] bg-[#fffbeb] text-[#b45309]',
  red: 'border-[#f0d5d0] bg-[#fdf1ef] text-[#c0392b]',
  purple: 'border-[#ddd6fe] bg-[#f5f3ff] text-[#7c3aed]',
  gray: 'border-[#e3e6ea] bg-[#f6f7f9] text-[#8b949e]'
}[props.tone]))
</script>

<template>
  <button
    type="button"
    class="@container flex w-full cursor-pointer items-center gap-[6px] whitespace-nowrap border-0 bg-transparent px-[10px] py-[8px] text-left font-[inherit] transition-colors duration-[150ms] hover:bg-[#f6f7f9] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#2563eb]/40 motion-reduce:transition-none"
    :aria-expanded="expanded"
    :aria-controls="controls"
    @click="emit('toggle')"
  >
    <span
      class="flex h-[24px] w-[24px] flex-none items-center justify-center text-[#57606a]"
      :data-testid="$attrs['data-card-icon-testid']"
      aria-hidden="true"
    >
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" focusable="false">
        <path v-for="path in paths" :key="path" :d="path" />
      </svg>
    </span>
    <span
      class="min-w-0 flex-1 truncate text-[12px] leading-[1.7] [font-weight:650] text-[#1f2328]"
      :data-testid="$attrs['data-card-title-testid']"
      :title="title"
    >{{ title }}</span>
    <span
      v-if="status"
      class="ml-auto max-w-[104px] flex-none truncate whitespace-nowrap rounded-[5px] border border-solid px-[5px] text-[9.5px] leading-[1.7]"
      :class="statusClass"
      :data-testid="$attrs['data-card-status-testid']"
      :data-state="$attrs['data-card-state']"
      :title="status"
    >{{ status }}</span>
    <svg
      class="h-[12px] w-[12px] flex-none text-[#8b949e] transition-transform duration-[180ms] motion-reduce:transition-none"
      :class="[expanded ? 'rotate-90' : '', status ? '' : 'ml-auto']"
      viewBox="0 0 12 12"
      fill="none"
      stroke="currentColor"
      stroke-width="1.5"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    ><path d="m4.5 2.5 3.5 3.5-3.5 3.5" /></svg>
  </button>
</template>
