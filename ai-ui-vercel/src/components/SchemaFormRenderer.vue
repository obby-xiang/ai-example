<template>
  <div class="schema-form">
    <div v-if="formTitle" class="sf-title">
      <el-icon style="vertical-align:-2px;color:#409EFF;"><EditPen /></el-icon>
      {{ formTitle }}
    </div>
    <el-form label-position="top" :model="formData" @submit.prevent="handleSubmit">
      <el-form-item
        v-for="f in fields"
        :key="f.key"
        :label="f.label + (f.required ? ' *' : '')"
        :error="errors[f.key]"
      >
        <div v-if="f.description" class="sf-desc">{{ f.description }}</div>

        <!-- text 单行输入 -->
        <el-input
          v-if="f.type === 'text'"
          v-model="formData[f.key]"
          :placeholder="f.placeholder || ''"
          :maxlength="f.max || undefined"
          clearable
        />

        <!-- textarea 多行输入 -->
        <el-input
          v-else-if="f.type === 'textarea'"
          v-model="formData[f.key]"
          type="textarea"
          :rows="3"
          :placeholder="f.placeholder || ''"
          :maxlength="f.max || undefined"
          show-word-limit
        />

        <!-- number 数字输入 -->
        <el-input-number
          v-else-if="f.type === 'number'"
          v-model="formData[f.key]"
          :min="f.min !== undefined ? f.min : undefined"
          :max="f.max !== undefined ? f.max : undefined"
          :placeholder="f.placeholder || ''"
          controls-position="right"
          style="width:100%;"
        />

        <!-- boolean 开关 -->
        <el-switch
          v-else-if="f.type === 'boolean'"
          v-model="formData[f.key]"
        />

        <!-- single_select 下拉单选 -->
        <el-select
          v-else-if="f.type === 'single_select'"
          v-model="formData[f.key]"
          :placeholder="f.placeholder || '请选择'"
          clearable
          style="width:100%;"
        >
          <el-option
            v-for="opt in (f.options || [])"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>

        <!-- multi_select 下拉多选 -->
        <el-select
          v-else-if="f.type === 'multi_select'"
          v-model="formData[f.key]"
          multiple
          :placeholder="f.placeholder || '请选择'"
          clearable
          style="width:100%;"
        >
          <el-option
            v-for="opt in (f.options || [])"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>

        <!-- button_group 按钮组单选 -->
        <el-radio-group
          v-else-if="f.type === 'button_group'"
          v-model="formData[f.key]"
        >
          <el-radio-button
            v-for="opt in (f.options || [])"
            :key="opt.value"
            :value="opt.value"
          >{{ opt.label }}</el-radio-button>
        </el-radio-group>

        <!-- date 日期 -->
        <el-date-picker
          v-else-if="f.type === 'date'"
          v-model="formData[f.key]"
          type="date"
          value-format="YYYY-MM-DD"
          :placeholder="f.placeholder || '选择日期'"
          style="width:100%;"
        />

        <!-- file 文件上传(excel_import 用) -->
        <el-upload
          v-else-if="f.type === 'file'"
          :accept="f.accept || '.xlsx,.xls'"
          :auto-upload="false"
          :show-file-list="true"
          :file-list="fileListMap[f.key] || []"
          :on-change="(file) => handleFileChange(f.key, file)"
          :on-remove="() => handleFileRemove(f.key)"
          :limit="1"
          :on-exceed="() => ElMessage.warning('只能上传 1 个文件,已替换')"
          drag
          style="width:100%;"
        >
          <el-icon class="el-icon--upload"><Upload /></el-icon>
          <div class="el-upload__text">把文件拖到此处,或<em>点击上传</em></div>
          <template #tip>
            <div class="sf-desc">{{ f.description || ('支持 ' + (f.accept || '.xlsx,.xls')) }}</div>
          </template>
        </el-upload>

        <!-- 未知类型兜底 -->
        <span v-else class="sf-unknown">不支持的类型: {{ f.type }}</span>
      </el-form-item>

      <div class="sf-actions">
        <el-button size="small" :disabled="disabled" @click="handleCancel">取消</el-button>
        <el-button size="small" type="primary" :loading="disabled" @click="handleSubmit">
          {{ submitLabel || '提交' }}
        </el-button>
      </div>
    </el-form>
  </div>
</template>

<script setup>
/**
 * Schema-driven 表单渲染器（业界 Adaptive Cards / react-jsonschema-form 模式）。
 *
 * 设计原则：
 *   1. 由后端 collect_user_input 工具的 args.fields 驱动渲染（不写死业务表单）
 *   2. 按 type 映射 Element Plus 控件：text/textarea/number/boolean/single_select/multi_select/button_group/date
 *   3. 前端做 required/min/max/pattern 最小校验，语义校验交给后端/模型
 *   4. 提交后 emit('submit', values)，由 AiPanel 回灌给后端恢复 agent loop
 */
import { reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'

const props = defineProps({
  fields: { type: Array, default: () => [] },
  formTitle: { type: String, default: '' },
  submitLabel: { type: String, default: '提交' },
  /** 提交中禁用（防重复提交） */
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['submit', 'cancel'])

const formData = reactive({})
const errors = reactive({})
// file 类型字段的 el-upload 受控文件列表({ [key]: [{ name, raw }] })
const fileListMap = reactive({})

function initForm() {
  for (const f of props.fields || []) {
    if (f.type === 'multi_select') {
      formData[f.key] = Array.isArray(f.default) ? [...f.default] : []
    } else if (f.type === 'boolean') {
      formData[f.key] = f.default !== undefined && f.default !== null ? !!f.default : false
    } else if (f.type === 'file') {
      formData[f.key] = null
      fileListMap[f.key] = []
    } else if (f.default !== undefined && f.default !== null) {
      formData[f.key] = f.default
    } else {
      formData[f.key] = ''
    }
    errors[f.key] = ''
  }
}
initForm()
watch(() => props.fields, initForm, { deep: true })

// el-upload on-change: (uploadFile, uploadFiles) - 取最新文件覆盖
function handleFileChange(fkey, uploadFile) {
  fileListMap[fkey] = [uploadFile]
  formData[fkey] = uploadFile?.raw || null
}
function handleFileRemove(fkey) {
  fileListMap[fkey] = []
  formData[fkey] = null
}

function isEmpty(v) {
  return v === undefined || v === null || v === ''
    || (Array.isArray(v) && v.length === 0)
}

function validate() {
  let ok = true
  for (const f of props.fields || []) {
    errors[f.key] = ''
    const v = formData[f.key]
    if (f.required && isEmpty(v)) {
      errors[f.key] = '此项必填'
      ok = false
      continue
    }
    if (isEmpty(v)) continue
    // number 范围
    if (f.type === 'number') {
      const num = Number(v)
      if (isNaN(num)) { errors[f.key] = '必须是数字'; ok = false; continue }
      if (f.min !== undefined && num < f.min) { errors[f.key] = `不能小于 ${f.min}`; ok = false; continue }
      if (f.max !== undefined && num > f.max) { errors[f.key] = `不能大于 ${f.max}`; ok = false; continue }
    }
    // text/textarea 长度
    if (f.type === 'text' || f.type === 'textarea') {
      const s = String(v)
      if (f.min !== undefined && s.length < f.min) { errors[f.key] = `至少 ${f.min} 个字符`; ok = false; continue }
      if (f.max !== undefined && s.length > f.max) { errors[f.key] = `最多 ${f.max} 个字符`; ok = false; continue }
    }
    // pattern 正则
    if (f.pattern && (f.type === 'text' || f.type === 'textarea')) {
      try {
        const re = new RegExp(f.pattern)
        if (!re.test(String(v))) { errors[f.key] = '格式不正确'; ok = false; continue }
      } catch (e) { /* 忽略非法正则 */ }
    }
  }
  return ok
}

function handleSubmit() {
  if (props.disabled) return
  if (!validate()) {
    ElMessage.warning('请完善表单必填项')
    return
  }
  emit('submit', { ...formData })
}
function handleCancel() {
  if (props.disabled) return
  emit('cancel')
}
</script>

<style scoped>
.schema-form { padding: 4px 0; }
.sf-title { font-weight: 600; font-size: 13px; margin-bottom: 8px; color: #303133; }
.sf-desc { font-size: 12px; color: #909399; margin-bottom: 6px; line-height: 1.4; }
.sf-unknown { color: #f56c6c; font-size: 12px; }
.sf-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }
</style>
