import request from './request.js'

export const createSession = () => request.post('/ai/sessions')
export const getSession = (sid) => request.get(`/ai/sessions/${sid}`)
export const resetSession = (sid) => request.delete(`/ai/sessions/${sid}`)
export const updateContext = (sid, ctx) => request.put(`/ai/sessions/${sid}/context`, ctx)
export const cancelRun = (sid) => request.delete(`/ai/sessions/${sid}/runs/current`)
export const submitInteraction = (sid, iid, approved, reason, data) =>
  request.post(`/ai/sessions/${sid}/interactions/${iid}`, { approved, reason, data })
export const getHealth = () => request.get('/ai/health')
