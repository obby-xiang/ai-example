import request from './request'

export const taskApi = {
  list: () => request.get('/tasks').then(r => r.data),
  get: (id) => request.get(`/tasks/${id}`).then(r => r.data),
  create: (name) => request.post('/tasks', { name }).then(r => r.data),
  selectScenario: (taskId, scenario) =>
    request.post('/tasks/select-scenario', { taskId, scenario }).then(r => r.data),
  updateStep: (taskId, step, stepData) =>
    request.post('/tasks/update-step', { taskId, step, stepData }).then(r => r.data),
  updateSelected: (taskId, defIds) =>
    request.post('/tasks/update-selected', { taskId, defIds }).then(r => r.data),
  gotoStep: (taskId, step) =>
    request.post('/tasks/goto-step', { taskId, step }).then(r => r.data),
  complete: (id) => request.post(`/tasks/${id}/complete`).then(r => r.data),
  updateChanges: (id, changes) =>
    request.post(`/tasks/${id}/changes`, changes).then(r => r.data),
  remove: (id) => request.delete(`/tasks/${id}`),
  scenarios: () => request.get('/tasks/meta/scenarios').then(r => r.data)
}
