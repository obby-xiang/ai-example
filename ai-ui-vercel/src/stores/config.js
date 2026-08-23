import { defineStore } from 'pinia'
import { CONFIG_DEFINITIONS, createInitialRows } from '@/mock/configDefs'

/**
 * 配置 store —— 前端 AI runtime 模式下完全本地化(无后端 REST)。
 * 数据来源:src/mock/configDefs.js(从 ai-service DataSeedService 平移)。
 * 行数据在内存中维护,可被表格工具(applyOperation)修改。
 *
 * 保留与旧 ai-ui config store 完全一致的方法签名,使拷贝过来的视图/组件无需改动。
 *   count(defId)            -> { total }
 *   allData(defId, limit)   -> [{ id, rowData }]
 *   pageData(defId, page, size) -> { content: [...], total, page, size }
 *   batchSave(defId, rows, mode) -> { added, updated, deleted, total }
 *   applyOperation(defId, operation, params) -> { affected }
 */
export const useConfigStore = defineStore('config', {
  state: () => ({
    definitions: [],
    loading: false,
    // defId -> [{ id, rowData }]
    rows: {}
  }),
  getters: {
    enabledDefinitions: (s) => s.definitions.filter(d => d.enabled !== false),
    defById: (s) => (id) => s.definitions.find(d => d.id === Number(id)),
    defByCode: (s) => (code) => s.definitions.find(d => d.code === code),
    unselectedIds: (s) => (selectedIds = []) =>
      s.definitions.filter(d => !selectedIds.includes(d.id)).map(d => d.id)
  },
  actions: {
    async loadDefinitions() {
      this.loading = true
      try {
        // 深拷贝,避免外部修改污染常量
        this.definitions = CONFIG_DEFINITIONS.map(d => ({ ...d, columns: d.columns.map(c => ({ ...c })) }))
        this.rows = createInitialRows()
      } finally {
        this.loading = false
      }
    },
    _rowsOf(defId) {
      const id = Number(defId)
      if (!this.rows[id]) this.rows[id] = []
      return this.rows[id]
    },
    async count(defId) {
      return { total: this._rowsOf(defId).length }
    },
    async allData(defId, limit = 100000) {
      const rows = this._rowsOf(defId)
      return rows.slice(0, limit).map(r => ({ ...r, rowData: { ...r.rowData } }))
    },
    async pageData(defId, page = 1, size = 50) {
      const rows = this._rowsOf(defId)
      const start = (page - 1) * size
      const content = rows.slice(start, start + size).map(r => ({ ...r, rowData: { ...r.rowData } }))
      return { content, total: rows.length, page, size }
    },
    /**
     * 批量保存(merge/append)。rows 格式: [{ id, data, mark }]
     *   - append:全部作为新行追加(mark=added 的 id=null)
     *   - merge :按 id 更新已有行,无 id 的追加
     */
    async batchSave(configDefId, rows, mode = 'merge') {
      const store = this._rowsOf(configDefId)
      let added = 0, updated = 0, deleted = 0
      const maxId = store.reduce((m, r) => Math.max(m, r.id || 0), 0)
      let nextId = maxId + 1
      const byId = new Map(store.map(r => [r.id, r]))
      for (const row of (rows || [])) {
        if (row.mark === 'deleted' && row.id) {
          const idx = store.findIndex(r => r.id === row.id)
          if (idx >= 0) { store.splice(idx, 1); deleted++ }
          continue
        }
        if (row.id && mode !== 'append') {
          const target = byId.get(row.id)
          if (target) {
            target.rowData = { ...(row.data || row.rowData || {}) }
            updated++
          } else {
            store.push({ id: row.id, rowData: { ...(row.data || {}) } })
            added++
          }
        } else {
          store.push({ id: nextId++, rowData: { ...(row.data || {}) } })
          added++
        }
      }
      return { added, updated, deleted, total: store.length }
    },
    /**
     * 表格操作: update_where / delete_rows / replace
     * params:
     *   update_where -> { where:{field,op,value}, set:{field:newValue} }
     *   delete_rows  -> { fromRow, toRow, where }
     *   replace      -> { field, search, replace, regex }
     */
    async applyOperation(configDefId, operation, params = {}) {
      const store = this._rowsOf(configDefId)
      let affected = 0
      if (operation === 'update_where') {
        const { where, set } = params
        for (const row of store) {
          if (where && where.field && matchOp(row.rowData[where.field], where.op, where.value)) {
            Object.assign(row.rowData, set || {})
            affected++
          }
        }
        // 无 where 全表更新
        if (!where || !where.field) {
          for (const row of store) Object.assign(row.rowData, set || {})
          affected = store.length
        }
      } else if (operation === 'delete_rows') {
        const { fromRow, toRow, where } = params
        if (where && where.field) {
          const before = store.length
          for (let i = store.length - 1; i >= 0; i--) {
            if (matchOp(store[i].rowData[where.field], where.op, where.value)) store.splice(i, 1)
          }
          affected = before - store.length
        } else if (fromRow != null) {
          const a = Math.max(0, Number(fromRow) - 1)
          const b = toRow != null ? Number(toRow) : store.length
          const cnt = Math.max(0, Math.min(b, store.length) - a)
          store.splice(a, cnt)
          affected = cnt
        }
      } else if (operation === 'replace') {
        const { field, search, replace, regex } = params
        for (const row of store) {
          const v = row.rowData[field]
          if (v == null) continue
          const s = String(v)
          let nv
          if (regex) {
            try { nv = s.replace(new RegExp(search, 'g'), replace) } catch (e) { nv = s }
          } else if (s.includes(search)) {
            nv = s.split(search).join(replace)
          } else continue
          if (nv !== s) { row.rowData[field] = nv; affected++ }
        }
      }
      return { affected }
    }
  }
})

/** where 条件匹配(eq/ne/lt/le/gt/ge/contains/starts_with/ends_with) */
function matchOp(val, op, target) {
  if (val == null || target == null) {
    if (op === 'eq') return val === target
    return false
  }
  switch (op) {
    case 'eq': return String(val) === String(target)
    case 'ne': return String(val) !== String(target)
    case 'lt': return Number(val) < Number(target)
    case 'le': return Number(val) <= Number(target)
    case 'gt': return Number(val) > Number(target)
    case 'ge': return Number(val) >= Number(target)
    case 'contains': return String(val).includes(String(target))
    case 'starts_with': return String(val).startsWith(String(target))
    case 'ends_with': return String(val).endsWith(String(target))
    default: return String(val) === String(target)
  }
}
