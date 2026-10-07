/** 数据面 client（/api/data）。 */

import { get } from './http'
import type { PageResult } from '@/types/api'
import type { ConfigDataRow, DataQuery } from '@/types/data'

/** 已发布数据分页（scopeType 缺省时后端按定义层级推导）。 */
export function listData(defCode: string, params: DataQuery = {}): Promise<PageResult<ConfigDataRow>> {
  return get<PageResult<ConfigDataRow>>(`/data/${encodeURIComponent(defCode)}`, { params })
}

/** 预估行数（可带查询条件，与导出作业同口径）。 */
export function countData(
  defCode: string,
  params: { scopeType?: string; scopeKey?: string; conditions?: string } = {}
): Promise<{ count: number }> {
  return get<{ count: number }>(`/data/${encodeURIComponent(defCode)}/count`, { params })
}

export const dataApi = {
  listData,
  countData
}
