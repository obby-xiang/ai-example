<script setup lang="ts">
/**
 * CollapsibleText（R-158 #12）：真溢出才渐隐 + 「显示更多」的截断展示组件。
 *
 * 口径（决策卡方案 B，业界锚 Vercel ai-elements `Tool` / shadcn scroll-fade）：
 * - 收起态限高 `maxHeight`（px，默认 96 = 原 result 区 max-h-24 口径）； ResizeObserver 测
 *   **真溢出**（内容高 > 限高）才渲染底部渐隐（mask-image）+「显示更多」按钮；
 * - 短内容零副作用：无溢出 ⇒ 无渐隐、无按钮（高度自适应，不撑高卡片的反面 = 也不留白）；
 * - 点「显示更多」放开限高（按钮变「收起」）；`pretty` = JSON 解析成功则缩进格式化、失败原样。
 *
 * 溢出判据用 `entry.contentRect.height`（RO 回调携带的内容盒真实高度）与 `scrollHeight`
 * 取大——测试桩可向 RO 回调注入 contentRect，无 RO 环境（旧浏览器/测试）视为不溢出。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = withDefaults(defineProps<{
  text: string
  /** JSON pretty-print（args 区用：解析成功缩进 2 格，失败原样） */
  pretty?: boolean
  /** 收起态限高（px）；思考区传 160（原 max-h-40 口径） */
  maxHeight?: number
  /** 「显示更多」按钮 testid 前缀（调用方传 `show-more-<toolCallId>` 兼容 R-154 断言） */
  testid?: string
}>(), { pretty: false, maxHeight: 96, testid: 'collapsible-show-more' })

const expanded = ref(false)
/** 真溢出才可展开（RO 观测结果；无 RO 环境恒 false） */
const overflowed = ref(false)

const displayText = computed(() => {
  if (!props.pretty) {
    return props.text
  }
  try {
    return JSON.stringify(JSON.parse(props.text), null, 2)
  } catch {
    return props.text
  }
})

const wrapRef = ref<HTMLElement | null>(null)
let observer: ResizeObserver | null = null

function applyObservation(el: HTMLElement): void {
  observer = new ResizeObserver((entries) => {
    const entry = entries[0]
    const contentHeight = entry ? Math.max(entry.contentRect.height, el.scrollHeight) : el.scrollHeight
    overflowed.value = contentHeight > props.maxHeight
  })
  observer.observe(el)
}

onMounted(() => {
  const el = wrapRef.value
  if (!el || typeof ResizeObserver === 'undefined') {
    return
  }
  applyObservation(el)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})

function toggle(): void {
  expanded.value = !expanded.value
  if (expanded.value) {
    // 展开后停止观测（限高已放开，溢出概念不再适用）
    observer?.disconnect()
    observer = null
  } else if (wrapRef.value && typeof ResizeObserver !== 'undefined' && !observer) {
    // 收起：重挂观测（裸 measure 读 scrollHeight 会把 stub/零值环境误判为不溢出）
    applyObservation(wrapRef.value)
  }
}

const collapsed = computed(() => overflowed.value && !expanded.value)
</script>

<template>
  <div>
    <div
      ref="wrapRef"
      class="whitespace-pre-wrap"
      :style="collapsed ? { maxHeight: maxHeight + 'px', maskImage: 'linear-gradient(to bottom, #000 55%, transparent)' } : {}"
    >
      <slot>{{ displayText }}</slot>
    </div>
    <button
      v-if="overflowed"
      type="button"
      class="mt-0.5 text-xs text-[#409eff]"
      :data-testid="testid"
      @click="toggle"
    >
      {{ expanded ? '收起' : '显示更多' }}
    </button>
  </div>
</template>
