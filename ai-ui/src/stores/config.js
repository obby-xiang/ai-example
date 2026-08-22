import { defineStore } from 'pinia'
import { configApi } from '@/api/config'

export const useConfigStore = defineStore('config', {
  state: () => ({
    definitions: [],
    loading: false,
    // defId -> { total, page, rows: {id:rowData}, columns, name, code }
    dataCache: {}
  }),
  getters: {
    enabledDefinitions: (s) => s.definitions.filter(d => d.enabled !== false),
    defById: (s) => (id) => s.definitions.find(d => d.id === id),
    defByCode: (s) => (code) => s.definitions.find(d => d.code === code),
    // 返回未被当前任务选择的定义ID列表
    unselectedIds: (s) => (selectedIds = []) => {
      return s.definitions.filter(d => !selectedIds.includes(d.id)).map(d => d.id)
    }
  },
  actions: {
    async loadDefinitions() {
      this.loading = true
      try {
        this.definitions = await configApi.definitions()
      } finally {
        this.loading = false
      }
    },
    async count(defId) {
      return await configApi.count(defId)
    },
    async pageData(defId, page = 1, size = 100) {
      return await configApi.page(defId, page, size)
    },
    async allData(defId, limit = 100000) {
      return await configApi.all(defId, limit)
    },
    async batchSave(configDefId, rows, mode = 'merge') {
      return await configApi.batchSave({ configDefId, mode, rows })
    },
    async applyOperation(configDefId, operation, params) {
      return await configApi.applyOperation({ configDefId, operation, params })
    }
  }
})
