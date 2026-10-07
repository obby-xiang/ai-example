/** 配置定义域 client（/api/definitions）。 */

import { get, getBlob, post, put } from './http'
import type { ConfigDefinition, DefinitionQuery } from '@/types/definition'

/** 定义列表（level/keyword 过滤由后端实现）。 */
export function listDefinitions(params: DefinitionQuery = {}): Promise<ConfigDefinition[]> {
  return get<ConfigDefinition[]>('/definitions', { params })
}

/** 单个定义（含字段）。 */
export function getDefinition(code: string): Promise<ConfigDefinition> {
  return get<ConfigDefinition>(`/definitions/${encodeURIComponent(code)}`)
}

/** 新建定义（201）。 */
export function createDefinition(definition: ConfigDefinition): Promise<ConfigDefinition> {
  return post<ConfigDefinition>('/definitions', definition)
}

/** 更新定义。 */
export function updateDefinition(code: string, definition: ConfigDefinition): Promise<ConfigDefinition> {
  return put<ConfigDefinition>(`/definitions/${encodeURIComponent(code)}`, definition)
}

/** 下载导入模板（xlsx 字节流）。 */
export function downloadTemplate(code: string): Promise<Blob> {
  return getBlob(`/definitions/${encodeURIComponent(code)}/template`)
}

export const definitionsApi = {
  listDefinitions,
  getDefinition,
  createDefinition,
  updateDefinition,
  downloadTemplate
}
