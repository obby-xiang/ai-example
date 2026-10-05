import request from './request.js'

export const createJob = (taskId, jobType) => request.post(`/tasks/${taskId}/jobs`, { jobType })
export const listJobs = (taskId) => request.get(`/tasks/${taskId}/jobs`)
export const getJob = (jobId) => request.get(`/jobs/${jobId}`)
export const cancelJob = (jobId) => request.delete(`/jobs/${jobId}`)
export const getIssues = (jobId, params) => request.get(`/jobs/${jobId}/issues`, { params })
export const getDiff = (jobId) => request.get(`/jobs/${jobId}/diff`)
