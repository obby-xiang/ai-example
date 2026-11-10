import type { ToolPart } from '@/types/ai'

export type BusinessCardTone = 'neutral' | 'blue' | 'green' | 'amber' | 'red' | 'purple' | 'gray'
export type BusinessCardFamily = 'tool' | 'hitl' | 'form'

export function hasUnknownReviewRequirement(part: ToolPart): boolean {
  return part.status === 'unknown' && (typeof part.outcome === 'string' && part.outcome.trim() !== ''
    || typeof part.userTextCode === 'string' && part.userTextCode.trim() !== ''
    || typeof part.result === 'string' && part.result.trim() !== ''
    || typeof part.reason === 'string' && part.reason.trim() !== '')
}

export function isUnknownUnrecoverable(part: ToolPart): boolean {
  return part.userTextCode === 'UNRECOVERABLE' || part.userTextCode === 'RECOVERY_UNAVAILABLE'
    || String(part.outcome ?? '').toLowerCase() === 'unrecoverable'
}

const WRENCH = 'M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76Z'

const FAMILY_ICONS: Record<string, string[]> = {
  tool: [WRENCH],
  hitl: ['M22 12c0 5.523-4.477 10-10 10S2 17.523 2 12 6.477 2 12 2s10 4.477 10 10Z', 'M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3', 'M12 17h.01'],
  form: ['M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2', 'M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1Z', 'M8 11h8M8 15h5'],
  upload: ['M12 16V3', 'm7 8 5-5 5 5', 'M5 15v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4'],
  sheet: ['M3 3h18v18H3z', 'M3 9h18M9 3v18M3 15h18'],
  reasoning: ['m12 3 1.7 5.3L19 10l-5.3 1.7L12 17l-1.7-5.3L5 10l5.3-1.7L12 3Z', 'm18.5 16 .7 2.3 2.3.7-2.3.7-.7 2.3-.7-2.3-2.3-.7 2.3-.7.7-2.3Z'],
  error: ['M22 12c0 5.523-4.477 10-10 10S2 17.523 2 12 6.477 2 12 2s10 4.477 10 10Z', 'M12 8v5', 'M12 17h.01'],
  stage: ['M22 12c0 5.523-4.477 10-10 10S2 17.523 2 12 6.477 2 12 2s10 4.477 10 10Z', 'M12 6v6l4 2']
}

const TOOL_ICONS: Record<string, string[]> = {
  create_task: ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z', 'M14 2v6h6', 'M12 12v6M9 15h6'],
  start_export: ['M12 3v13', 'm7 11 5 5 5-5', 'M5 21h14'],
  start_import: ['M12 16V3', 'm7 8 5-5 5 5', 'M5 21h14'],
  start_publish: ['M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 0 0-2.91-.09Z', 'm12 15-3-3a22 22 0 0 1 2-3.95A12.87 12.87 0 0 1 22 2c0 2.72-.78 7.5-6.05 11a22.35 22.35 0 0 1-3.95 2Z', 'M9 12H4s.55-3.03 2-4c1.62-1.08 5 0 5 0', 'M12 15v5s3.03-.55 4-2c1.08-1.62 0-5 0-5'],
  navigate_to: ['m3 11 19-9-9 19-2-8-8-2Z'],
  ui_snapshot: ['M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3Z', 'M18 14a6 6 0 1 1-12 0 6 6 0 0 1 12 0Z'],
  kb_search: ['M4 19.5A2.5 2.5 0 0 1 6.5 17H20', 'M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z', 'm16 14 2 2', 'M15 10a2 2 0 1 1-4 0 2 2 0 0 1 4 0Z']
}

const SEARCH_ICON = ['M21 21l-4.35-4.35', 'M19 11a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z']
const EDIT_ICON = ['M12 20h9', 'M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z']
const DELETE_ICON = ['M3 6h18', 'M8 6V4h8v2', 'M19 6l-1 15H6L5 6', 'M10 11v6M14 11v6']

export function cardIconPaths(icon: string): string[] {
  const key = icon.trim().toLowerCase()
  if (key === 'generative_form') {
    return FAMILY_ICONS.form
  }
  if (key === 'ui_request_upload') {
    return FAMILY_ICONS.upload
  }
  const exact = TOOL_ICONS[key] ?? FAMILY_ICONS[key]
  if (exact) {
    return exact
  }
  if (/(?:^|_)(?:query|list|search|find|retrieve|lookup|get)(?:_|$)/.test(key)) {
    return SEARCH_ICON
  }
  if (/(?:^|_)(?:update|edit|modify|replace|rename|write|set|normalize)(?:_|$)/.test(key)) {
    return EDIT_ICON
  }
  if (/(?:^|_)(?:delete|remove|drop)(?:_|$)/.test(key)) {
    return DELETE_ICON
  }
  return FAMILY_ICONS.tool
}

export function businessCardStatusTone(key: string, family: BusinessCardFamily): BusinessCardTone {
  if (key === 'succeeded' || key === 'approved' || key === 'submitted') {
    return 'green'
  }
  if (key === 'failed' || key === 'rejected') {
    return 'red'
  }
  if (key === 'pending') {
    return family === 'tool' ? 'blue' : 'amber'
  }
  if (key === 'running' || key === 'syncing' || key === 'submitting' || key === 'queued') {
    return 'blue'
  }
  if (key === 'unknown' || key === 'expired' || key === 'notApproved' || key === 'notExecuted') {
    return 'amber'
  }
  return 'neutral'
}

export function isBusinessCardDefaultExpanded(
  key: string,
  family: BusinessCardFamily,
  needsReview = false
): boolean {
  if (key === 'unknown') {
    return needsReview
  }
  if (key === 'pending') {
    return family !== 'tool'
  }
  if (key === 'running' || key === 'syncing' || key === 'submitting' || key === 'failed'
    || key === 'blocked' || key === 'expired' || key === 'notApproved' || key === 'notExecuted') {
    return true
  }
  return family === 'form' && key === 'rejected'
}
