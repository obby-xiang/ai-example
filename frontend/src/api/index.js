import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: '/api',
  timeout: 120000
})

http.interceptors.response.use(
  (resp) => resp.data,
  (err) => {
    const msg = err?.response?.data?.error || err?.message || '请求失败'
    ElMessage.error(msg)
    return Promise.reject(new Error(msg))
  }
)

// ===================== 配置项元数据 =====================
export const configApi = {
  list: () => http.get('/config-defs'),
  get: (code) => http.get(`/config-defs/${encodeURIComponent(code)}`),
  query: (code, conditions = [], page = 0, size = 50) =>
    http.post('/config-defs/query', { code, conditions, page, size }),
  templateInfo: (code) => http.get(`/config-defs/${encodeURIComponent(code)}/template-info`)
}

// ===================== 任务 =====================
export const taskApi = {
  create: (type, name) => http.post('/tasks', { type, name }),
  list: () => http.get('/tasks'),
  get: (id) => http.get(`/tasks/${id}`),
  saveStepData: (id, currentStep, stepData) =>
    http.put(`/tasks/${id}/step-data`, { currentStep, stepData }),
  cancel: (id) => http.post(`/tasks/${id}/cancel`),
  remove: (id) => http.delete(`/tasks/${id}`)
}

// ===================== 作业 =====================
export const jobApi = {
  startExport: (taskId, items) => http.post(`/tasks/${taskId}/jobs/export`, { items }),
  startCheck: (taskId, items) => http.post(`/tasks/${taskId}/jobs/check`, { items }),
  startImport: (taskId, items) => http.post(`/tasks/${taskId}/jobs/import`, { items }),
  startPublish: (taskId) => http.post(`/tasks/${taskId}/jobs/publish`, {}),
  get: (jobId) => http.get(`/jobs/${jobId}`),
  cancel: (jobId) => http.post(`/jobs/${jobId}/cancel`),
  exportResults: (jobId) => http.get(`/jobs/${jobId}/export-results`),
  staging: (taskId) => http.get(`/tasks/${taskId}/staging`)
}

// ===================== AI 对话（非 SSE 部分） =====================
export const aiApi = {
  history: (sessionId) => http.get('/ai/history', { params: { sessionId } }),
  cancel: (sessionId) => http.post('/ai/cancel', { sessionId })
}

export default http
