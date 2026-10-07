/** 主数据 client（/api/master）。 */

import { get } from './http'
import type { Project, Region } from '@/types/masterdata'

/** 地区列表。 */
export function listRegions(): Promise<Region[]> {
  return get<Region[]>('/master/regions')
}

/** 项目列表（可按地区过滤）。 */
export function listProjects(regionCode?: string): Promise<Project[]> {
  return get<Project[]>('/master/projects', regionCode ? { params: { regionCode } } : undefined)
}

export const masterDataApi = {
  listRegions,
  listProjects
}
