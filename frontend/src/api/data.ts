/** 数据面 client（/api/data）。 */

import { get } from './http'
import type { PageResult } from '@/types/api'
import type { ConfigDataRow, DataQuery } from '@/types/data'

/** 已发布数据分页（scopeType 缺省时后端按定义层级推导）。 */
export function listData(defCode: string, params: DataQuery = {}): Promise<PageResult<ConfigDataRow>> {
  return get<PageResult<ConfigDataRow>>(`/data/${encodeURIComponent(defCode)}`, { params })
}

export interface DataCountResult {
  count: number
}

/** 当前范围与条件下的真实已发布行数。 */
export function countData(defCode: string, params: DataQuery = {}): Promise<DataCountResult> {
  return get<DataCountResult>(`/data/${encodeURIComponent(defCode)}/count`, { params })
}

export const dataApi = {
  listData,
  countData
}
