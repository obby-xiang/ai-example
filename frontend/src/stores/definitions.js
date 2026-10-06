import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listDefinitions, getDefinition } from '@/api/definitions.js'

export const useDefinitionsStore = defineStore('definitions', () => {
  const all = ref([])
  const byCode = ref({})   // cache: code -> full definition with fields
  const loading = ref(false)

  async function loadAll() {
    if (all.value.length > 0) return all.value
    loading.value = true
    try {
      const res = await listDefinitions()
      all.value = res.data.data || []
    } finally {
      loading.value = false
    }
    return all.value
  }

  async function loadByCode(code) {
    if (byCode.value[code]) return byCode.value[code]
    const res = await getDefinition(code)
    const def = res.data.data
    byCode.value[code] = def
    return def
  }

  function invalidate(code) {
    if (code) delete byCode.value[code]
    else { all.value = []; byCode.value = {} }
  }

  return { all, byCode, loading, loadAll, loadByCode, invalidate }
})
