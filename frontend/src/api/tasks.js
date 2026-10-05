import request from './request.js'

export const listTasks = () => request.get('/tasks')
export const getTask = (id) => request.get(`/tasks/${id}`)
export const createTask = (data) => request.post('/tasks', data)
export const deleteTask = (id) => request.delete(`/tasks/${id}`)
export const selectDefs = (id, defCodes) => request.post(`/tasks/${id}/select-defs`, { defCodes })
export const setCondition = (id, defCode, condition) =>
  request.put(`/tasks/${id}/items/${defCode}/condition`, { condition })
export const goToStep = (id, step) => request.put(`/tasks/${id}/step`, { step })
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
export const downloadTemplates = (id, codes) =>
  request.get(`/tasks/${id}/files/templates`, { params: { codes }, responseType: 'blob' })
