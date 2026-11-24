import type { AiRunCurrentContext, AiRunCurrentEntry, ToolPart } from '@/types/ai'

export interface WorkspaceOwnerEvidence {
  known: boolean
  taskId: number | null
}

interface PendingOwnerEvidence {
  name?: string
  arguments?: string | null
  args?: string
  preflight?: { target?: { taskId?: unknown } | null } | null
  target?: Record<string, unknown> | null
}

function numericTaskId(value: unknown): number | null {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) {
    return value
  }
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
  }
  return null
}

function pendingTaskId(candidate: PendingOwnerEvidence | null | undefined): number | null {
  if (!candidate) {
    return null
  }
  const direct = numericTaskId(candidate.preflight?.target?.taskId) ?? numericTaskId(candidate.target?.taskId)
  if (direct !== null) {
    return direct
  }
  const raw = candidate.arguments ?? candidate.args
  if (typeof raw === 'string' && raw.trim() !== '') {
    try {
      const parsed = JSON.parse(raw) as Record<string, unknown>
      return numericTaskId(parsed.taskId) ?? numericTaskId(parsed.draftTaskId)
    } catch {
      return null
    }
  }
  return null
}

export function workspaceOwnerEvidence(
  snapshot: AiRunCurrentContext | undefined,
  ...candidates: Array<PendingOwnerEvidence | ToolPart | AiRunCurrentEntry | null | undefined>
): WorkspaceOwnerEvidence {
  const snapshotTaskId = numericTaskId(snapshot?.taskId)
  if (snapshotTaskId !== null) {
    return { known: true, taskId: snapshotTaskId }
  }
  for (const candidate of candidates) {
    const taskId = pendingTaskId(candidate)
    if (taskId !== null) {
      return { known: true, taskId }
    }
    if (candidate?.name === 'create_task') {
      return { known: true, taskId: null }
    }
  }
  return { known: false, taskId: null }
}
