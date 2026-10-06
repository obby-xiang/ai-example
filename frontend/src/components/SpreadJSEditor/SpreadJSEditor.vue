<template>
  <div class="relative flex h-full min-h-[500px] flex-col" ref="containerRef">
    <div class="flex shrink-0 items-center justify-between border-b border-slate-200 bg-slate-50 px-3 py-2">
      <el-button-group>
        <el-button size="small" @click="toggleProtect">
          <el-icon><component :is="isProtected ? Lock : Unlock" /></el-icon>
          {{ isProtected ? '只读模式' : '编辑模式' }}
        </el-button>
      </el-button-group>
      <div class="flex gap-2">
        <el-button size="small" type="primary" :loading="saving" @click="handleSave" v-if="!isProtected">
          <el-icon><Check /></el-icon> 保存
        </el-button>
        <el-button size="small" @click="handleDownload">
          <el-icon><Download /></el-icon> 下载
        </el-button>
      </div>
    </div>
    <div class="min-h-0 flex-1" ref="hostRef"></div>
    <div v-if="loading" class="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-white/80 text-sm text-slate-500">
      <el-icon class="animate-spin" size="32"><Loading /></el-icon>
      <span>正在加载…</span>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { Lock, Unlock, Check, Download, Loading } from '@element-plus/icons-vue'
import { downloadFile, saveFile } from '@/api/tasks.js'
import { downloadTemplate } from '@/api/definitions.js'
import { ElMessage } from 'element-plus'

const props = defineProps({
  taskId: Number,
  defCode: String,
  templateMode: { type: Boolean, default: false }
})
const emit = defineEmits(['saved'])

const hostRef = ref(null)
const containerRef = ref(null)
const loading = ref(true)
const saving = ref(false)
const isProtected = ref(true)
let spread = null

onMounted(async () => {
  await initSpread()
  await loadData()
})

onBeforeUnmount(() => {
  if (spread) spread.destroy()
})

async function initSpread() {
  const GC = (await import('@grapecity/spread-sheets')).default
  // 中文语言包（副作用模块，注册 zh-cn 资源）与授权 Key
  await import('@grapecity/spread-sheets-resources-zh')
  GC.Spread.Sheets.LicenseKey = import.meta.env.VITE_SPREADJS_KEY || ''
  GC.Spread.Common.CultureManager.culture('zh-cn')
  spread = new GC.Spread.Sheets.Workbook(hostRef.value, {
    sheetCount: 1,
    allowUserDragFill: true,
    allowUserZoom: true,
    tabNavigationVisible: true
  })
  spread.options.tabNavigationVisible = true
}

async function loadData() {
  loading.value = true
  try {
    let arrayBuffer
    if (props.templateMode || !props.taskId) {
      // Load template from backend
      const res = await downloadTemplate(props.defCode)
      arrayBuffer = await res.data.arrayBuffer()
    } else {
      // Load task file
      const res = await downloadFile(props.taskId, props.defCode)
      arrayBuffer = res.data
    }

    const GC = (await import('@grapecity/spread-sheets')).default
    const IO = (await import('@grapecity/spread-excelio')).IO
    const io = new IO()
    await new Promise((resolve, reject) => {
      io.open(arrayBuffer, (workbook) => {
        spread.fromJSON(workbook)
        // Set protection
        for (let i = 0; i < spread.getSheetCount(); i++) {
          const sheet = spread.getSheet(i)
          sheet.options.isProtected = isProtected.value
        }
        resolve()
      }, reject)
    })
  } catch (e) {
    ElMessage.error('加载文件失败: ' + e.message)
  } finally {
    loading.value = false
  }
}

function toggleProtect() {
  isProtected.value = !isProtected.value
  if (spread) {
    for (let i = 0; i < spread.getSheetCount(); i++) {
      spread.getSheet(i).options.isProtected = isProtected.value
    }
  }
}

async function handleSave() {
  if (!spread || !props.taskId) return
  saving.value = true
  try {
    const GC = (await import('@grapecity/spread-sheets')).default
    const IO = (await import('@grapecity/spread-excelio')).IO
    const io = new IO()
    const blob = await new Promise((resolve, reject) => {
      io.save(spread.toJSON(), resolve, reject, {
        fileType: GC.Spread.Excel.FileType.xlsx
      })
    })
    const arrayBuffer = await blob.arrayBuffer()
    await saveFile(props.taskId, props.defCode, arrayBuffer)
    emit('saved')
    ElMessage.success('文件已保存')
  } catch (e) {
    ElMessage.error('保存失败: ' + e.message)
  } finally {
    saving.value = false
  }
}

async function handleDownload() {
  if (!spread) return
  try {
    const GC = (await import('@grapecity/spread-sheets')).default
    const IO = (await import('@grapecity/spread-excelio')).IO
    const io = new IO()
    const blob = await new Promise((resolve, reject) => {
      io.save(spread.toJSON(), resolve, reject, {
        fileType: GC.Spread.Excel.FileType.xlsx
      })
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = (props.defCode || 'export') + '.xlsx'
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error('下载失败: ' + e.message)
  }
}
</script>

