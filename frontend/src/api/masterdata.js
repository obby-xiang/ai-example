import request from './request.js'

export const getRegions = () => request.get('/master/regions')
export const getProjects = (regionCode) =>
  request.get('/master/projects', { params: regionCode ? { regionCode } : {} })
