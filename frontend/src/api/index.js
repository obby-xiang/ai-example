import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 60000 })

http.interceptors.request.use((config) => {
  const sid = sessionStorage.getItem('qk.sessionId')
  if (sid) {
    config.headers['X-Session-Id'] = sid
  }
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'code' in body && body.code !== 0) {
      return Promise.reject(new Error(body.msg || '请求失败'))
    }
    return body ? body.data : body
  },
  (err) => {
    const msg = err.response?.data?.msg || err.message || '网络错误'
    return Promise.reject(new Error(msg))
  }
)

export const api = {
  /* 目录 */
  listConfigs: () => http.get('/catalog/configs'),
  listScopes: (type) => http.get('/catalog/scopes', { params: type ? { type } : {} }),
  taskTypes: () => http.get('/catalog/task-types'),

  /* 任务 */
  createTask: (type) => http.post('/tasks', { type }),
  listTasks: (params) => http.get('/tasks', { params }),
  taskDetail: (id) => http.get(`/tasks/${id}`),
  openTask: (id) => http.post(`/tasks/${id}/open`),
  cancelTask: (id) => http.post(`/tasks/${id}/cancel`),
  setSelection: (id, codes) => http.post(`/tasks/${id}/selection`, { codes }),
  setConditions: (id, perConfig) => http.post(`/tasks/${id}/conditions`, { perConfig }),
  startExport: (id) => http.post(`/tasks/${id}/export/start`),
  exportResults: (id) => http.get(`/tasks/${id}/export/results`),
  submitData: (id, configCode, fileName, rows) =>
    http.post(`/tasks/${id}/data`, { configCode, fileName, rows }),
  startCheck: (id) => http.post(`/tasks/${id}/check/start`),
  startImport: (id) => http.post(`/tasks/${id}/import/start`),
  startPublish: (id) => http.post(`/tasks/${id}/publish/start`),

  /* 会话 */
  sessionState: () => {
    const sid = sessionStorage.getItem('qk.sessionId')
    return http.get('/session/state', { params: sid ? { sessionId: sid } : {} })
  },
  bindTask: (taskId) => http.post('/session/bind', { taskId }),

  /* AI */
  aiHistory: (sessionId) => http.get('/ai/history', { params: { sessionId } }),
  aiClear: (sessionId) => http.delete('/ai/history', { params: { sessionId } })
}
