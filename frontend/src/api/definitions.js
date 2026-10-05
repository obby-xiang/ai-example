import request from './request.js'

export const listDefinitions = (params) => request.get('/definitions', { params })
export const getDefinition = (code) => request.get(`/definitions/${code}`)
export const createDefinition = (data) => request.post('/definitions', data)
export const updateDefinition = (code, data) => request.put(`/definitions/${code}`, data)
export const downloadTemplate = (code) =>
  request.get(`/definitions/${code}/template`, { responseType: 'blob' })
