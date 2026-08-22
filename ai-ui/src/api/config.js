import request from './request'

export const configApi = {
  definitions: () => request.get('/configs/definitions').then(r => r.data),
  definition: (id) => request.get(`/configs/definitions/${id}`).then(r => r.data),
  createDefinition: (def) => request.post('/configs/definitions', def).then(r => r.data),

  count: (defId) => request.get('/configs/data/count', { params: { defId } }).then(r => r.data),
  page: (defId, page = 1, size = 50) =>
    request.get('/configs/data/page', { params: { defId, page, size } }).then(r => r.data),
  all: (defId, limit = 100000) =>
    request.get('/configs/data/all', { params: { defId, limit } }).then(r => r.data),

  batchSave: (data) => request.post('/configs/data/batch-save', data).then(r => r.data),
  applyOperation: (data) => request.post('/configs/data/operations', data).then(r => r.data)
}
