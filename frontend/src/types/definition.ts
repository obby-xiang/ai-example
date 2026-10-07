/**
 * 配置定义契约。
 *
 * 核对源：backend/src/main/java/com/example/configmgr/definition/entity/ConfigDefinition.java
 *         definition/entity/ConfigField.java
 *         definition/controller/DefinitionController.java
 */

/** 配置层级（ConfigDefinition.ConfigLevel）。 */
export type ConfigLevel = 'GLOBAL' | 'REGION' | 'PROJECT'

/** 字段类型（ConfigField.FieldType）。 */
export type FieldType = 'STRING' | 'NUMBER' | 'DATE' | 'ENUM' | 'BOOLEAN' | 'REFERENCE'

/** ENUM 字段的选项（optionsJson 反序列化后的元素）。 */
export interface ConfigFieldOption {
  value: string
  label: string
}

/**
 * 配置字段。
 *
 * Q3 教训：主键标志的 JSON 字段名是 **`key`**，不是 `isKey`。
 * 后端实体字段为 `private boolean isKey`，Lombok 生成 getter `isKey()`，
 * Jackson 去掉 `is` 前缀后属性名即为 `key`（写类型前已核对实体与 S4.2 约定）。
 */
export interface ConfigField {
  id?: number
  /** 所属配置编码（后端字段 defCode，@JoinColumn def_code） */
  defCode: string
  /** 字段编码 */
  code: string
  /** 中文标签 */
  label: string
  fieldType: FieldType
  /** 是否必填 */
  required: boolean
  /** 是否主键 —— 注意 JSON 名是 key */
  key: boolean
  /** 排序号 */
  sortOrder: number
  /** ENUM 选项的 JSON 数组**字符串**（后端为 String/CLOB，不是数组） */
  optionsJson?: string | null
  /** REFERENCE 字段引用的配置编码 */
  refDefCode?: string | null
  /** REFERENCE 字段引用的字段编码 */
  refFieldCode?: string | null
}

/** 配置定义（含字段列表，fields 为 EAGER 且按 sortOrder 升序）。 */
export interface ConfigDefinition {
  id?: number
  code: string
  name: string
  level: ConfigLevel
  description?: string | null
  sortOrder: number
  fields: ConfigField[]
  /** LocalDateTime → 'yyyy-MM-dd HH:mm:ss'（JacksonConfig 固定格式） */
  createdAt?: string
  updatedAt?: string
}

/** 定义列表查询参数。 */
export interface DefinitionQuery {
  /** 层级过滤：GLOBAL/REGION/PROJECT */
  level?: ConfigLevel
  /** 关键词（后端按 code/name contains 过滤） */
  keyword?: string
}

/** 解析 optionsJson 的安全工具：非 JSON 或非数组时返回空数组。 */
export function parseFieldOptions(field: Pick<ConfigField, 'optionsJson'>): ConfigFieldOption[] {
  if (!field.optionsJson) {
    return []
  }
  try {
    const parsed: unknown = JSON.parse(field.optionsJson)
    if (!Array.isArray(parsed)) {
      return []
    }
    return parsed.map((item) => {
      const option = item as Partial<ConfigFieldOption>
      return { value: String(option.value ?? ''), label: String(option.label ?? option.value ?? '') }
    })
  } catch {
    return []
  }
}
