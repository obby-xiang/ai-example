<script setup lang="ts">
/**
 * CollapsibleText（R-158 #12 → R-160 M4/M5）：真溢出才渐隐 + 「显示更多」的截断展示组件。
 *
 * 口径（决策卡方案 B，业界锚 Vercel ai-elements `Tool` / shadcn scroll-fade）：
 * - 收起态限高 `maxHeight`（px，默认 96 = 原 result 区 max-h-24 口径）；**真溢出**（内容高 > 限高）
 *   才渲染底部渐隐（mask-image）+「显示更多」按钮；
 * - 短内容零副作用：无溢出 ⇒ 无渐隐、无按钮（高度自适应，不撑高卡片的反面 = 也不留白）；
 * - 点「显示更多」放开限高（按钮变「收起」）；`pretty` = JSON 解析成功则缩进格式化、失败原样。
 *
 * R-160 M5（显示更多兜底）：抽**单一 measure** —— onMounted 无观测器早退改 `nextTick` 直读
 * `scrollHeight > maxHeight`，toggle（展开/收起）同回落同一 measure；整组件不再依赖
 * 外部尺寸观测（jsdom / 旧环境行为一致，删早退的"无观测器恒不溢出"盲区一并退役）。
 *
 * R-160 M4（issue #14③ 方案 A）：容器 `min-width:0` + `overflow-wrap:anywhere` —— 长串
 * 在容器层可断行，配合 AiPanel 气泡 `overflow:hidden` 不再越界；不铺分块规则。
 *
 * R-165 S4（整改清单）：收起态的**行内 style 退役** —— 改任意值类
 * `max-h-[<n>px] [mask-image:linear-gradient(to_bottom,#000_55%,transparent)]`
 * （铁律：零手写 CSS、零行内 style）。限高只有 96/160 两个既有调用值，故用字面量表 + 联合
 * 类型钉死（JIT 扫不到运行期拼出的类名，值域必须静态可见；新增值 = 编译期报错）。
 */
import { computed, nextTick, onMounted, ref } from 'vue'

const props = withDefaults(defineProps<{
  text: string
  /** JSON pretty-print（args 区用：解析成功缩进 2 格，失败原样） */
  pretty?: boolean
  /** 收起态限高（px）；思考区传 160（原 max-h-40 口径）。值域钉死为字面量表键（S4 任意值类） */
  maxHeight?: 96 | 160
  /** 「显示更多」按钮 testid 前缀（调用方传实义 id：`args-more-<toolCallId>` / `reasoning-more-<blockId>`
   *  / `receipt-more-<toolCallId>`；R-165 S5a 起工具卡**输出**区改纯限高内滚，不再传本参数） */
  testid?: string
}>(), { pretty: false, maxHeight: 96, testid: 'collapsible-show-more' })

const expanded = ref(false)
/** 真溢出才可展开（单一 measure 的读数；jsdom 等零高环境恒 false = 不溢出） */
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

/** R-160 M5：单一 measure —— 直读 scrollHeight 与限高比较（无 RO 早退分支）。 */
function measure(): void {
  const el = wrapRef.value
  if (!el) {
    return
  }
  overflowed.value = el.scrollHeight > props.maxHeight
}

onMounted(() => {
  // nextTick 等首渲落地后读真实 scrollHeight（DOM 挂载即量，无 RO 异步观测窗）
  void nextTick(measure)
})

function toggle(): void {
  expanded.value = !expanded.value
  // 同回落单一 measure：展开/收起都重读 scrollHeight（收起恢复限高后必须重判溢出；
  // 展开后若内容变化同样以读数为准）
  void nextTick(measure)
}

const collapsed = computed(() => overflowed.value && !expanded.value)

/** R-165 S4：收起态任意值类（原 `:style="{maxHeight, maskImage}"` 的逐条等价，见文件头 S4 注）。 */
const COLLAPSED_CLASS: Record<96 | 160, string> = {
  96: 'max-h-[96px] [mask-image:linear-gradient(to_bottom,#000_55%,transparent)]',
  160: 'max-h-[160px] [mask-image:linear-gradient(to_bottom,#000_55%,transparent)]'
}
const collapsedClass = computed(() => (collapsed.value ? COLLAPSED_CLASS[props.maxHeight] : ''))
</script>

<template>
  <!-- R-160 M4：容器层方案 A —— min-width:0 + overflow-wrap:anywhere（长串可断行不越界） -->
  <div class="min-w-0 [overflow-wrap:anywhere]">
    <div
      ref="wrapRef"
      class="whitespace-pre-wrap"
      :class="collapsedClass"
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
