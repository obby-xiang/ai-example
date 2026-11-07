import { ApiError } from '@/api/http'
import { ErrorCode } from '@/types/api'

const SAFE_BUSINESS_MESSAGE_CODES: ReadonlySet<string> = new Set([
  ErrorCode.JOB_ALREADY_RUNNING,
  ErrorCode.DEFINITION_CODE_DUPLICATE,
  ErrorCode.DEFINITION_FIELD_CODE_EMPTY,
  ErrorCode.DEFINITION_FIELD_CODE_INVALID,
  ErrorCode.DEFINITION_FIELD_CODE_DUPLICATE,
  ErrorCode.DEFINITION_FIELD_REFERENCE_INCOMPLETE,
  ErrorCode.DEFINITION_PRIMARY_KEY_REQUIRED
])

export function userFacingErrorText(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    console.error('[ui] request failed', error)
    return SAFE_BUSINESS_MESSAGE_CODES.has(error.code) && error.message !== '' ? error.message : fallback
  }
  return error instanceof Error && error.message !== '' ? error.message : fallback
}
