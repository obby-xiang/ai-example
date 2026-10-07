/**
 * 数据面契约（已发布行 / 导入暂存行）。
 *
 * 核对源：backend/.../data/entity/ConfigDataRow.java、ConfigStagingRow.java
 *         data/controller/DataController.java
 */

/** 范围类型（ConfigDataRow.scopeType，GLOBAL/REGION/PROJECT 字符串）。 */
export type ScopeType = 'GLOBAL' | 'REGION' | 'PROJECT'

/** 已发布配置数据行。 */
export interface ConfigDataRow {
  id?: number
  defCode: string
  scopeType: ScopeType | string
  scopeKey?: string | null
  rowKey: string
  /** 行数据 JSON **字符串**（后端为 String/CLOB，前端按需求自行 parse） */
  dataJson: string
  /** 乐观锁版本（发布冲突检测依据） */
  version?: number
  createdAt?: string
  updatedAt?: string
}

/** 导入暂存行状态。 */
export type StagingStatus = 'STAGED' | 'PUBLISHED' | 'FAILED'

/** 暂存行操作类型。 */
export type StagingOpType = 'UPSERT' | 'DELETE'

/** 导入暂存行（发布前的工作副本）。 */
export interface ConfigStagingRow {
  id?: number
  taskId: number
  defCode: string
  opType: StagingOpType | string
  rowKey: string
  scopeType: ScopeType | string
  scopeKey?: string | null
  dataJson: string
  status: StagingStatus | string
  /** 导入时刻对应已发布行的版本号；null 表示当时为新增行（发布并发冲突检测） */
  baseVersion?: number | null
  createdAt?: string
}

/** 数据浏览查询参数（后端 DataController /api/data/{defCode}）。 */
export interface DataQuery {
  scopeType?: ScopeType
  scopeKey?: string
  page?: number
  size?: number
}

/** 把 dataJson 安全解析为对象。 */
export function parseRowData(row: Pick<ConfigDataRow | ConfigStagingRow, 'dataJson'>): Record<string, unknown> {
  try {
    const parsed: unknown = JSON.parse(row.dataJson)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>
    }
    return {}
  } catch {
    return {}
  }
}
