/**
 * API 层出口：按域聚合，业务侧统一 `import { aiApi, tasksApi } from '@/api'`。
 */

export * from './http'
export * from './sse'

export { aiApi } from './ai'
export { definitionsApi } from './definitions'
export { tasksApi } from './tasks'
export { jobsApi } from './jobs'
export { dataApi } from './data'
export { masterDataApi } from './masterdata'
