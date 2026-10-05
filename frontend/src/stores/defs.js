import { defineStore } from 'pinia'
import { api } from '../api'

/** 配置定义（全局共享，动态建模的数据源）。 */
export const useDefsStore = defineStore('defs', {
  state: () => ({
    defs: [],
    loading: false
  }),
  getters: {
    byCode: (state) => (code) => state.defs.find(d => d.code === code),
    enabled: (state) => state.defs.filter(d => d.enabled)
  },
  actions: {
    async load() {
      this.loading = true
      try {
        this.defs = await api.get('/api/defs')
      } finally {
        this.loading = false
      }
    }
  }
})
