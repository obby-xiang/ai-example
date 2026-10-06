import request from './request.js'

// 历史任务列表：支持 type/status/keyword 筛选 + 分页，返回 Page<TaskSummary>
export const listTasks = (params) => request.get('/tasks', { params })
export const getTask = (id) => request.get(`/tasks/${id}`)
export const getTaskOverview = (id) => request.get(`/tasks/${id}/overview`)
export const createTask = (data) => request.post('/tasks', data)
export const deleteTask = (id) => request.delete(`/tasks/${id}`)
export const selectDefs = (id, defCodes) => request.post(`/tasks/${id}/select-defs`, { defCodes })
export const setCondition = (id, defCode, condition) =>
  request.put(`/tasks/${id}/items/${defCode}/condition`, { condition })
export const goToStep = (id, step) => request.put(`/tasks/${id}/step`, { step })
export const setImportMode = (id, mode) => request.put(`/tasks/${id}/import-mode`, { mode })
export const getFiles = (id) => request.get(`/tasks/${id}/files`)
export const downloadFile = (id, defCode) =>
  request.get(`/tasks/${id}/files/${defCode}`, { responseType: 'arraybuffer' })
export const saveFile = (id, defCode, data) =>
  request.put(`/tasks/${id}/files/${defCode}`, data, {
    headers: { 'Content-Type': 'application/octet-stream' }
  })
export const uploadFile = (id, file) => {
  const form = new FormData()
  form.append('file', file)
  return request.post(`/tasks/${id}/files/upload`, form)
}
export const downloadAllFiles = (id) =>
  request.get(`/tasks/${id}/files/download-all`, { responseType: 'blob' })
// 下载勾选的导出文件（子集打包）
export const downloadFiles = (id, codes) =>
  request.get(`/tasks/${id}/files/download`, { params: { codes }, responseType: 'blob' })
export const downloadTemplates = (id, codes) =>
  request.get(`/tasks/${id}/files/templates`, { params: { codes }, responseType: 'blob' })
