<template>
  <div class="spread-host">
    <div class="spread-toolbar">
      <slot name="toolbar"></slot>
      <el-button size="small" @click="reload" :loading="loading">重新加载</el-button>
      <el-button size="small" type="primary" @click="save" v-if="saveable">保存并下载</el-button>
    </div>
    <div ref="host" class="spread-container" :style="{ height: height }"></div>
    <div v-if="loading" class="spread-mask"><el-icon class="is-loading"><i class="el-icon-loading" /></el-icon>加载中…</div>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import GC from '@grapecity/spread-sheets'
import * as ExcelIO from '@grapecity/spread-excelio'
import { saveBlob } from '../api'

/**
 * SpreadJS 在线编辑器封装：
 * - source: xlsx 的 URL 或 Blob（传入 Blob 时在线编辑）；
 * - 编辑结果经 ExcelIO 导出为 Blob（可下载或上传覆盖，形成闭环）。
 */
const props = defineProps({
  source: { type: [String, Object], default: null },
  height: { type: String, default: '480px' },
  saveable: { type: Boolean, default: true },
  filename: { type: String, default: 'export.xlsx' }
})

const emit = defineEmits(['saved'])

const host = ref(null)
const loading = ref(false)
let spread = null

function createWorkbook() {
  spread = new GC.Spread.Sheets.Workbook(host.value, { sheetCount: 1 })
}

async function open(source) {
  if (!source) {
    createWorkbook()
    return
  }
  loading.value = true
  try {
    let blob = source
    if (typeof source === 'string') {
      const res = await fetch(source)
      if (!res.ok) throw new Error(`加载失败（HTTP ${res.status}）`)
      blob = await res.blob()
    }
    // ExcelIO 17.x：open(file, success, error) 返回 JSON，需 fromJSON 灌入；
    // 传 File 类型（内部 FileReader 严格校验）
    const file = blob instanceof File ? blob
      : new File([blob], props.filename || 'spread.xlsx',
        { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
    if (!spread) createWorkbook()
    else spread.suspendPaint()
    const excelIO = new ExcelIO.IO()
    await new Promise((resolve, reject) => {
      excelIO.open(file, (json) => {
        try {
          spread.fromJSON(json)
          resolve()
        } catch (err) {
          reject(err)
        }
      }, (e) => reject(new Error(e && e.errorMessage || '解析失败')))
    })
    spread.resumePaint()
  } catch (e) {
    console.warn('SpreadJS 打开失败', e)
  } finally {
    loading.value = false
  }
}

function reload() {
  open(props.source)
}

/** 导出当前编辑内容为 Blob（ExcelIO 17.x：save(json, success, error)）。 */
function exportBlob() {
  return new Promise((resolve, reject) => {
    if (!spread) return reject(new Error('表格尚未初始化'))
    const json = spread.toJSON()
    const excelIO = new ExcelIO.IO()
    excelIO.save(json, (blob) => resolve(blob), (e) => reject(new Error(e && e.errorMessage || '导出失败')))
  })
}

async function save() {
  loading.value = true
  try {
    const blob = await exportBlob()
    saveBlob(blob, props.filename)
    emit('saved', blob)
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

defineExpose({ exportBlob, reload, getSpread: () => spread })

onMounted(() => open(props.source))
watch(() => props.source, (v) => open(v))
onBeforeUnmount(() => {
  if (spread) {
    spread.destroy()
    spread = null
  }
})
</script>

<style scoped>
.spread-host { position: relative; }
.spread-toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
.spread-container { border: 1px solid #dcdfe6; border-radius: 4px; }
.spread-mask {
  position: absolute;
  inset: 0;
  background: rgba(255, 255, 255, 0.75);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 13px;
}
</style>
