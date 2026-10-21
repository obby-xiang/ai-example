/**
 * axios 实例与统一错误拦截（形态参照 glm-5.3 `api/index.js:9-16`，TS 化重写）。
 *
 * 两个后端响应族必须都容得下：
 * 1. 业务端点：`{ success, message?, code?, data? }`（common/ApiResponse）→ 拦截器拆信封取 data；
 * 2. AI 端点：**裸对象**（如 409 SESSION_BUSY 的 {code,message,runId,reattach}、
 *    /api/ai/confirm 的 {accepted,duplicate,...}）→ 原样放行，由调用方按类型收窄。
 */

import axios, { AxiosError, type AxiosInstance, type AxiosRequestConfig } from 'axios'
import type { ApiErrorBody, ApiResponse } from '@/types/api'
import { acceptLanguage } from '@/locales'
import { HTTP_TIMEOUT_MS } from '@/utils/time-constants'

/** 前端侧补充的错误码（后端未定义，仅用于网络层异常）。 */
export const FRONTEND_ERROR_CODES = {
  /** 请求未到达后端（网络/代理不可达） */
  NETWORK_ERROR: 'NETWORK_ERROR',
  /** 非预期的响应形态或未知异常 */
  UNKNOWN_ERROR: 'UNKNOWN_ERROR'
} as const

/** 归一化后的接口错误：带 HTTP 状态 + 机器可读码 + 原始响应体。 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly body: unknown

  constructor(message: string, status: number, code: string, body: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.body = body
  }

  /** 响应体收窄为对象（供读取 runId/reattach 等附加字段）。 */
  bodyAs<T>(): T | null {
    return this.body && typeof this.body === 'object' ? (this.body as T) : null
  }
}

/** 判断是否为业务信封（AI 裸对象没有 success 字段）。 */
function isApiResponse(body: unknown): body is ApiResponse<unknown> {
  return !!body && typeof body === 'object' && typeof (body as ApiResponse<unknown>).success === 'boolean'
}

function readBodyField(body: unknown, key: 'code' | 'message'): string | null {
  if (!body || typeof body !== 'object') {
    return null
  }
  const value = (body as ApiErrorBody)[key]
  return typeof value === 'string' && value.length > 0 ? value : null
}

/** 从任意响应体/异常中抽出可读错误文案与码。 */
function normalizeError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }
  if (error instanceof AxiosError) {
    const status = error.response?.status ?? 0
    const body: unknown = error.response?.data
    if (status === 0) {
      return new ApiError(
        '后端不可达，请确认服务已启动（/api 代理目标见 VITE_API_BASE）',
        0,
        FRONTEND_ERROR_CODES.NETWORK_ERROR,
        body
      )
    }
    const code = readBodyField(body, 'code') ?? String(status)
    const bodyMessage = readBodyField(body, 'message')
    // 代理/网关类失败（如 vite 代理目标不可达 → 500 且无 JSON 体）不该把 axios 的英文原文抛给用户
    const message = bodyMessage
      ?? (status >= 500 ? `后端服务不可用或未启动（HTTP ${status}）` : error.message || '请求失败')
    return new ApiError(message, status, code, body)
  }
  if (error instanceof Error) {
    return new ApiError(error.message, 0, FRONTEND_ERROR_CODES.UNKNOWN_ERROR, null)
  }
  return new ApiError('未知错误', 0, FRONTEND_ERROR_CODES.UNKNOWN_ERROR, error)
}

/**
 * 全部 axios 请求的单次上限（毫秒）。
 *
 * 取值依据（业界依据 §1「HTTP 客户端超时（connect / read / write）」）：axios 官方源码缺省
 * `timeout: 0` = **不超时**（`lib/defaults/index.js`，是所有主流客户端里唯一"必须显式设置"的），
 * 故本值不可省；同节对内网企业应用给出的推荐区间是**整体 callTimeout 60–120s**
 * （对照：OkHttp 缺省 10/10/10s、AWS SDK for Java 2.x 官方示例 connect 5s / socket 30s、
 * OpenAI/Anthropic SDK 整体 600s）。本项目取区间**下限 60s**：内网直连、响应短，
 * 长响应走 SSE 通道（`api/sse.ts` 自有 90s 帧间静默 + 15s 建连看门狗），不占本上限。
 *
 * **本值不是新值**：原为 create 配置里的内联字面量 `timeout: 60000`，取值零变化；2026-10-10 数字规格
 * 清点裁决① 对它只做"值保留 + 命名常量化 + 补本注释"，故不属行为变更。
 *
 * **单一事实源（2026-10-21 超时面治理批① 第 4 项）**：本值已提到
 * `utils/time-constants.ts` 的 `HTTP_TIMEOUT_MS`，本文件只做转出 + 使用。原"另外三个前端超时常量
 * 与本值对齐"的注释式约定已改为<b>代码同源</b>：
 * `utils/frontend-tools.ts` 的 `FRONTEND_TOOL_RESULT_HEADROOM_MS`、`stores/workspace.ts` 的
 * `DOWNLOAD_FETCH_TIMEOUT_MS` 都直接 import 这个常量 —— 改值一次生效三处，不再靠"改本值须同批复核"。
 * （`components/SpreadGrid.vue` 的 `READY_TIMEOUT_MS` 是"表格引擎就绪"的独立语义，不并入：
 * 它不是 HTTP 请求上限。）
 *
 * 值本体在 `utils/time-constants.ts`；本文件保留同名转出，既有 `import { HTTP_TIMEOUT_MS } from '@/api/http'`
 * 的调用方零改动。
 */
export { HTTP_TIMEOUT_MS }

export const http: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: HTTP_TIMEOUT_MS
})

/**
 * 语言协商头：随每次请求发送当前 UI 语言。
 *
 * 后端 `AcceptHeaderLocaleResolver` 默认 locale = zh-CN，**不带该头的调用方零变化**
 * （E2E / curl / 桩的既有行为不变）；带 `en-US` 时才取英文 messages。
 */
http.interceptors.request.use((config) => {
  config.headers.set('Accept-Language', acceptLanguage())
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body: unknown = resp.data
    if (isApiResponse(body)) {
      if (!body.success) {
        return Promise.reject(new ApiError(body.message ?? '请求失败', resp.status, body.code ?? String(resp.status), body))
      }
      // 信封已拆：调用方拿到的即 data
      return { ...resp, data: body.data } as unknown as typeof resp
    }
    return resp
  },
  (error: unknown) => Promise.reject(normalizeError(error))
)

/** GET，返回拆信封后的 data。 */
export async function get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const resp = await http.get(url, config)
  return resp.data as T
}

/** POST，返回拆信封后的 data（AI 裸对象端点也走这里）。 */
export async function post<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const resp = await http.post(url, body, config)
  return resp.data as T
}

/** PUT，返回拆信封后的 data。 */
export async function put<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const resp = await http.put(url, body, config)
  return resp.data as T
}

/** DELETE，返回拆信封后的 data。 */
export async function del<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const resp = await http.delete(url, config)
  return resp.data as T
}

/** 下载类请求：返回 Blob（不拆信封，后端返回字节流）。 */
export async function getBlob(url: string, config?: AxiosRequestConfig): Promise<Blob> {
  const resp = await http.get(url, { ...config, responseType: 'blob' })
  return resp.data as Blob
}

/** 上传类请求：multipart/form-data。 */
export async function upload<T>(url: string, form: FormData): Promise<T> {
  const resp = await http.post(url, form, { headers: { 'Content-Type': 'multipart/form-data' } })
  return resp.data as T
}

/**
 * 触发浏览器下载。
 *
 * 用途有两处：用户点击下载，以及 AI 前端工具 `download_export_files` 的回灌路径
 * （副作用发生在浏览器里，故必须由前端工具执行器调用本函数）。
 */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

export { normalizeError }
