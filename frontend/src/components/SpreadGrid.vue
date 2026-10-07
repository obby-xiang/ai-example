<template>
  <div ref="host" class="spread-grid-host" :style="{ height }" />
</template>

<script setup lang="ts">
/**
 * SpreadJS 表格宿主（Excel 工具层的渲染基座）。
 *
 * 中文 Culture 在 main.ts 里统一设置（zh-cn），这里只负责挂载/销毁工作簿。
 * 模板生成、动态校验、JSZip 打包等属 b 棒（utils/excel.ts），本棒只保证
 * SpreadJS 基座可用（评估模式仅水印）。
 */
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import GC from '@grapecity/spread-sheets'

const props = withDefaults(
  defineProps<{
    height?: string
    /** 初始 JSON（workbook.toJSON() 的结果），有值则加载 */
    initialJson?: string | null
  }>(),
  {
    height: '360px',
    initialJson: null
  }
)

const host = ref<HTMLDivElement | null>(null)
const workbook = shallowRef<GC.Spread.Sheets.Workbook | null>(null)

onMounted(() => {
  if (!host.value) {
    return
  }
  const instance = new GC.Spread.Sheets.Workbook(host.value, { sheetCount: 1 })
  if (props.initialJson) {
    instance.fromJSON(props.initialJson)
  }
  workbook.value = instance
})

onBeforeUnmount(() => {
  workbook.value?.destroy()
  workbook.value = null
})

/** 导出当前工作簿 JSON（保存回后端 / 本地缓存用）。 */
function toJson(): string | null {
  return workbook.value ? JSON.stringify(workbook.value.toJSON()) : null
}

defineExpose({ toJson })
</script>

<style scoped>
.spread-grid-host {
  width: 100%;
  border: 1px solid #dcdfe6;
  border-radius: 2px;
}
</style>
