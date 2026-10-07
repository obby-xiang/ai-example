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

export const http: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 60000
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
 * 用途有两处：用户点击下载，以及 AI 前端工具 `download_export_file` 的回灌路径
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
