import { defineStore } from 'pinia'
import { api, sseRequest } from '../api'
import { bumpWorkspace } from '../utils/workspace'

/** 导入配置向导状态（用户操作与 AI ui_event 同源写入）。 */
export const useImportStore = defineStore('importTask', {
  state: () => ({
    step: 1,
    selectedDefs: [],
    batch: null,
    entries: [],
    uploadReport: null,
    controller: null
  }),
  actions: {
    selectDefs(codes) {
      this.selectedDefs = [...codes]
      bumpWorkspace()
    },
    reset() {
      this.step = 1
      this.selectedDefs = []
      this.batch = null
      this.entries = []
      this.uploadReport = null
      this.stopSse()
      bumpWorkspace()
    },
    goStep(s) {
      this.step = s
      bumpWorkspace()
    },
    async ensureBatch(name) {
      const snap = await api.post('/api/import/batches/ensure', {
        name: name || '导入批次',
        defCodes: this.selectedDefs
      })
      // 快照的 id 位于 detail 内，归一化为顶层 id 便于引用
      this.batch = { ...snap, id: snap.detail.id }
      this.entries = snap.detail.files || []
      bumpWorkspace()
      return this.batch
    },
    async upload(files) {
      if (!this.batch) throw new Error('请先创建导入批次')
      const fd = new FormData()
      files.forEach(f => fd.append('files', f))
      this.uploadReport = await api.upload(`/api/import/batches/${this.batch.id}/files`, fd)
      await this.reloadEntries()
      bumpWorkspace()
      return this.uploadReport
    },
    async reloadEntries() {
      if (this.batch) {
        this.entries = await api.get(`/api/import/batches/${this.batch.id}/results`)
      }
    },
    async loadIssues(defCode) {
      return api.get(`/api/import/batches/${this.batch.id}/issues/${defCode}`)
    },
    async loadDrafts(defCode) {
      return api.get(`/api/import/batches/${this.batch.id}/drafts/${defCode}`)
    },
    async runCheck() {
      await api.post(`/api/import/batches/${this.batch.id}/check`)
      this.step = 2
      bumpWorkspace()
      this.subscribe(this.batch.id)
    },
    async runImport() {
      await api.post(`/api/import/batches/${this.batch.id}/import`)
      this.step = 3
      bumpWorkspace()
      this.subscribe(this.batch.id)
    },
    async runPublish() {
      await api.post(`/api/import/batches/${this.batch.id}/publish`)
      this.step = 4
      bumpWorkspace()
      this.subscribe(this.batch.id)
    },
    async refresh() {
      if (this.batch) {
        this.batch = await api.get(`/api/import/batches/${this.batch.id}`)
        await this.reloadEntries()
      }
    },
    subscribe(batchId) {
      this.stopSse()
      this.controller = new AbortController()
      const onEvent = (ev, data) => {
        if (ev === 'snapshot' || ev === 'progress' || ev === 'done') {
          this.batch = { ...data, id: data.detail.id }
          if (data.detail && data.detail.files) this.entries = data.detail.files
          if (ev === 'done') this.reloadEntries()
        }
      }
      sseRequest(`/api/import/batches/${batchId}/events`, {
        signal: this.controller.signal,
        onEvent
      }).catch(() => {
        /* 断开：可手动刷新恢复 */
      }).finally(() => {
        this.controller = null
      })
    },
    stopSse() {
      if (this.controller) {
        this.controller.abort()
        this.controller = null
      }
    }
  }
})
