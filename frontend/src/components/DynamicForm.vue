<template>
  <div class="dynamic-form">
    <template v-if="mode === 'conditions'">
      <div v-for="f in def.fields" :key="f.code" class="cond-row">
        <span class="cond-label" :class="{ required: f.required }">{{ f.label }}</span>
        <el-select v-model="condOf(f.code).op" class="cond-op" size="small">
          <el-option v-for="op in opsOf(f)" :key="op.value" :label="op.label" :value="op.value" />
        </el-select>
        <template v-if="condOf(f.code).op === 'between'">
          <el-input v-model="condOf(f.code).value" size="small" class="cond-value" :placeholder="'下限'" />
          <span class="text-muted">~</span>
          <el-input v-model="condOf(f.code).value2" size="small" class="cond-value" :placeholder="'上限'" />
        </template>
        <el-select v-else-if="f.type === 'SELECT'" v-model="condOf(f.code).value"
                   :multiple="condOf(f.code).op === 'in'" size="small" class="cond-value" clearable>
          <el-option v-for="o in f.options" :key="o" :label="o" :value="o" />
        </el-select>
        <el-date-picker v-else-if="f.type === 'DATE'" v-model="condOf(f.code).value" type="date"
                        value-format="YYYY-MM-DD" size="small" class="cond-value" placeholder="选择日期" />
        <el-select v-else-if="f.type === 'BOOLEAN'" v-model="condOf(f.code).value" size="small"
                   class="cond-value" clearable>
          <el-option label="是(true)" :value="true" />
          <el-option label="否(false)" :value="false" />
        </el-select>
        <el-input v-else v-model="condOf(f.code).value" size="small" class="cond-value" clearable
                  :placeholder="f.type === 'NUMBER' ? '数字' : '值'" />
        <el-button v-if="condOf(f.code).op" size="small" text type="danger" @click="clearCond(f.code)">清除</el-button>
      </div>
    </template>

    <template v-else>
      <el-form label-width="110px" label-position="left">
        <el-form-item v-if="def.level !== 'GLOBAL'" :label="scopeLabel" :required="true">
          <el-input v-model="model.__scope__" :placeholder="'填写' + scopeLabel + '，如 华东区'" />
        </el-form-item>
        <el-form-item v-for="f in def.fields" :key="f.code" :label="f.label" :required="f.required">
          <el-select v-if="f.type === 'SELECT'" v-model="model[f.code]" clearable>
            <el-option v-for="o in f.options" :key="o" :label="o" :value="o" />
          </el-select>
          <el-select v-else-if="f.type === 'REFERENCE'" v-model="model[f.code]" clearable filterable
                     :placeholder="'选择 ' + (refDefName(f) || '') + ' 的值'">
            <el-option v-for="o in refOptions[f.code] || []" :key="o" :label="o" :value="o" />
          </el-select>
          <el-date-picker v-else-if="f.type === 'DATE'" v-model="model[f.code]" type="date"
                          value-format="YYYY-MM-DD" placeholder="选择日期" />
          <el-input-number v-else-if="f.type === 'NUMBER'" v-model="model[f.code]" :min="f.min ?? undefined"
                           :max="f.max ?? undefined" controls-position="right" />
          <el-switch v-else-if="f.type === 'BOOLEAN'" v-model="model[f.code]" />
          <el-input v-else-if="f.type === 'TEXTAREA'" v-model="model[f.code]" type="textarea" :rows="2" />
          <el-input v-else v-model="model[f.code]" :placeholder="f.defaultValue ? '默认：' + f.defaultValue : ''" />
        </el-form-item>
      </el-form>
    </template>
  </div>
</template>

<script setup>
import { reactive, ref, watch, computed, onMounted } from 'vue'
import { api } from '../api'

const props = defineProps({
  def: { type: Object, required: true },
  mode: { type: String, default: 'row' }, // conditions | row
  model: { type: Object, required: true }
})

const refOptions = reactive({})

const scopeLabel = computed(() => props.def.level === 'REGION' ? '地区' : '项目')

function opsOf(f) {
  const t = f.type
  if (t === 'TEXT' || t === 'TEXTAREA') return [
    { value: 'eq', label: '等于' }, { value: 'contains', label: '包含' }]
  if (t === 'NUMBER' || t === 'DATE') return [
    { value: 'eq', label: '等于' }, { value: 'gt', label: '大于' },
    { value: 'lt', label: '小于' }, { value: 'between', label: '介于' }]
  if (t === 'BOOLEAN') return [{ value: 'eq', label: '等于' }]
  if (t === 'SELECT') return [{ value: 'eq', label: '等于' }, { value: 'in', label: '包含于' }]
  return [{ value: 'eq', label: '等于' }, { value: 'contains', label: '包含' }]
}

function condOf(code) {
  if (!props.model[code]) {
    props.model[code] = { op: 'eq', value: undefined, value2: undefined }
  }
  return props.model[code]
}

function clearCond(code) {
  delete props.model[code]
}

function refDefName(f) {
  return f.refDefCode
}

async function loadRefOptions(f) {
  try {
    const data = await api.get(`/api/data/rows?defCode=${f.refDefCode}&published=true&page=1&size=500`)
    const set = new Set()
    ;(data.rows || []).forEach(r => {
      const v = r.data && r.data[f.refFieldCode]
      if (v !== undefined && v !== null && v !== '') set.add(String(v))
    })
    refOptions[f.code] = [...set]
  } catch (e) {
    refOptions[f.code] = []
  }
}

watch(() => props.def, (d) => {
  if (d && d.fields) d.fields.filter(f => f.type === 'REFERENCE').forEach(loadRefOptions)
}, { immediate: true })
</script>

<style scoped>
.cond-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.cond-label {
  width: 110px;
  text-align: right;
  font-size: 13px;
  color: #606266;
}
.cond-label.required::before {
  content: '* ';
  color: #f56c6c;
}
.cond-op { width: 100px; }
.cond-value { flex: 1; }
</style>
