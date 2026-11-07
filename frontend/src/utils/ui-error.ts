import { ApiError } from '@/api/http'
import { ErrorCode } from '@/types/api'
import { logCaughtError } from '@/utils/log-caught-error'

export { logCaughtError } from '@/utils/log-caught-error'

const SAFE_BUSINESS_MESSAGE_CODES: ReadonlySet<string> = new Set([
  ErrorCode.JOB_ALREADY_RUNNING,
  ErrorCode.DEFINITION_CODE_DUPLICATE,
  ErrorCode.DEFINITION_FIELD_CODE_EMPTY,
  ErrorCode.DEFINITION_FIELD_CODE_INVALID,
  ErrorCode.DEFINITION_FIELD_CODE_DUPLICATE,
  ErrorCode.DEFINITION_FIELD_REFERENCE_INCOMPLETE,
  ErrorCode.DEFINITION_PRIMARY_KEY_REQUIRED
])

export function userFacingErrorText(error: unknown, fallback: string, key?: string): string {
  logCaughtError(error, key)
  if (error instanceof ApiError) {
    return SAFE_BUSINESS_MESSAGE_CODES.has(error.code) && error.message !== '' ? error.message : fallback
  }
  return fallback
}
