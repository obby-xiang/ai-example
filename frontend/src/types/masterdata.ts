/**
 * 主数据契约（地区 / 项目）。
 *
 * 核对源：backend/.../masterdata/entity/Region.java、Project.java
 *         masterdata/controller/MasterDataController.java（/api/master/regions、/api/master/projects）
 */

export interface Region {
  id?: number
  code: string
  name: string
  createdAt?: string
}

export interface Project {
  id?: number
  code: string
  name: string
  regionCode: string
  createdAt?: string
}
