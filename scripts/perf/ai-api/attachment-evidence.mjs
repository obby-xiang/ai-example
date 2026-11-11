import {randomUUID} from 'node:crypto'
import {mkdir, rename, writeFile} from 'node:fs/promises'
import path from 'node:path'

function limitedText(value) {
  const text = String(value ?? '')
  return text.length > 500 ? `${text.slice(0, 500)}...` : text
}

function errorMessage(error) {
  if (error instanceof Error) return limitedText(error.message || error.name)
  return limitedText(error)
}

export function frameEvidence(frame) {
  if (frame === null || frame === undefined) return null
  return {
    type: frame.type ?? null,
    name: frame.name ?? null,
    runId: frame.runId ?? null,
    toolCallId: frame.toolCallId ?? null,
    seq: frame.seq ?? null,
    code: frame.code ?? null,
    errorCode: frame.errorCode ?? null,
    cancelled: frame.cancelled ?? null,
    status: frame.status ?? null,
    transcriptDegraded: frame.transcriptDegraded ?? null,
    historyComplete: frame.historyComplete ?? null,
    turnEndCode: frame.turnEnd?.code ?? null
  }
}

export function createAttachmentEvidence(sessionId, scope) {
  return {
    schemaVersion: 1,
    id: randomUUID(),
    sessionId,
    scope,
    runId: null,
    status: 'COLLECTING',
    classification: 'IN_PROGRESS',
    waitKind: null,
    lastSeq: 0,
    realCode: null,
    lastSse: null,
    pending: {beforeCancel: null, afterCancel: null},
    cancellation: {source: null, response: null},
    timeline: [],
    log: [],
    evidencePath: null,
    persisted: false,
    evidenceComplete: false,
    cleanupWithheld: false,
    persistError: null
  }
}

export function recordAttachmentPhase(evidence, phase, details = {}) {
  evidence.status = phase
  if (details.runId !== undefined && details.runId !== null) evidence.runId = details.runId
  if (Number.isSafeInteger(details.lastSeq)) evidence.lastSeq = details.lastSeq
  if (details.frame !== undefined) evidence.lastSse = frameEvidence(details.frame)
  if (phase.includes('HITL') || phase.includes('UPLOAD')) evidence.waitKind = 'HITL_RECEIPT'
  if (phase.includes('MODEL') || phase.includes('CHAT')) evidence.waitKind = 'MODEL_RESPONSE'
  evidence.timeline.push({
    at: new Date().toISOString(),
    phase,
    runId: evidence.runId,
    lastSeq: evidence.lastSeq
  })
}

export function observeAttachmentFrame(evidence, runId, lastSeq, frame) {
  const firstRunFrame = evidence.runId === null && runId !== null
  evidence.runId = runId ?? evidence.runId
  evidence.lastSeq = Number.isSafeInteger(lastSeq) ? lastSeq : evidence.lastSeq
  evidence.lastSse = frameEvidence(frame) ?? evidence.lastSse
  if (firstRunFrame) recordAttachmentPhase(evidence, 'RUN_ID_OBSERVED', {runId, lastSeq})
  if (frame?.type === 'done' || frame?.type === 'error') {
    recordAttachmentPhase(evidence, 'TERMINAL_FRAME_OBSERVED', {runId, lastSeq, frame})
  }
}

export function captureAttachmentError(evidence, error, waitKind = null) {
  const scriptTimeout = error?.classification === 'SCRIPT_TIMEOUT_CANCEL' || error?.name === 'TimeoutError'
  evidence.status = scriptTimeout ? 'SCRIPT_TIMEOUT_CANCEL' : 'FAIL'
  evidence.classification = scriptTimeout ? 'SCRIPT_TIMEOUT_CANCEL' : 'FAIL'
  evidence.waitKind = error?.waitKind ?? waitKind ?? evidence.waitKind ?? 'MODEL_RESPONSE'
  if (error?.terminalEvidence) evidence.realCode = error.realCode ?? null
  else if (error?.realCode !== undefined) evidence.realCode = error.realCode
  evidence.lastSse = error?.lastSseEvidence ?? error?.terminalEvidence ?? evidence.lastSse
  if (Number.isSafeInteger(error?.lastSeq)) evidence.lastSeq = error.lastSeq
  const pending = error?.pendingEvidence ?? {}
  evidence.pending = {
    beforeCancel: pending.beforeCancel ?? evidence.pending.beforeCancel,
    afterCancel: pending.afterCancel ?? evidence.pending.afterCancel,
    beforeCancelHttpStatus: pending.beforeCancelHttpStatus ?? evidence.pending.beforeCancelHttpStatus ?? null,
    afterCancelHttpStatus: pending.afterCancelHttpStatus ?? evidence.pending.afterCancelHttpStatus ?? null,
    beforeCancelError: pending.beforeCancelError ?? evidence.pending.beforeCancelError ?? null,
    afterCancelError: pending.afterCancelError ?? evidence.pending.afterCancelError ?? null
  }
  evidence.cancellation = {
    source: scriptTimeout ? 'SCRIPT_TIMEOUT' : error ? error.cancellationSource ?? 'SSE_ERROR_RECOVERY' : null,
    response: error?.cancelResponse ?? evidence.cancellation.response
  }
  evidence.log.push({at: new Date().toISOString(), kind: 'error', message: errorMessage(error)})
  evidence.timeline.push({
    at: new Date().toISOString(),
    phase: evidence.classification,
    runId: evidence.runId,
    lastSeq: evidence.lastSeq,
    realCode: evidence.realCode,
    cancelSource: evidence.cancellation.source
  })
}

function evidenceFileName(evidence) {
  const raw = evidence.runId ?? `unresolved-${evidence.id}`
  return `${raw.replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 96)}.json`
}

function publicEvidence(evidence) {
  return {
    schemaVersion: evidence.schemaVersion,
    id: evidence.id,
    sessionId: evidence.sessionId,
    scope: evidence.scope,
    runId: evidence.runId,
    status: evidence.status,
    classification: evidence.classification,
    waitKind: evidence.waitKind,
    lastSeq: evidence.lastSeq,
    realCode: evidence.realCode,
    lastSse: evidence.lastSse,
    pending: evidence.pending,
    cancellation: evidence.cancellation,
    timeline: evidence.timeline,
    log: evidence.log,
    evidencePath: evidence.evidencePath,
    persisted: evidence.persisted,
    evidenceComplete: evidence.evidenceComplete,
    cleanupWithheld: evidence.cleanupWithheld,
    persistError: evidence.persistError
  }
}

export async function persistAttachmentEvidence(outDir, evidence, phase, error = null) {
  if (error) captureAttachmentError(evidence, error)
  evidence.evidencePath = path.join(outDir, 'attachment-evidence', evidenceFileName(evidence))
  evidence.timeline.push({at: new Date().toISOString(), phase: `EVIDENCE_WRITE_${phase}`, runId: evidence.runId})
  const previouslyPersisted = evidence.persisted
  const previouslyComplete = evidence.evidenceComplete
  try {
    evidence.persisted = true
    evidence.evidenceComplete = phase === 'FINAL'
    evidence.cleanupWithheld = phase === 'FAILURE'
    evidence.persistError = null
    await mkdir(path.dirname(evidence.evidencePath), {recursive: true})
    const temporary = `${evidence.evidencePath}.${randomUUID()}.tmp`
    await writeFile(temporary, `${JSON.stringify(publicEvidence(evidence), null, 2)}\n`, 'utf8')
    await rename(temporary, evidence.evidencePath)
  } catch (persistError) {
    evidence.persisted = previouslyPersisted
    evidence.evidenceComplete = previouslyComplete
    evidence.cleanupWithheld = true
    evidence.persistError = errorMessage(persistError)
    evidence.status = evidence.persisted ? 'INCOMPLETE' : 'MISSING'
    evidence.log.push({at: new Date().toISOString(), kind: 'evidence-write-failed', message: evidence.persistError})
    throw persistError
  }
}

export function attachmentEvidenceReport(attempts) {
  return {
    complete: attempts.every((evidence) => evidence.evidenceComplete),
    missing: attempts.filter((evidence) => !evidence.evidenceComplete).map((evidence) => ({
      runId: evidence.runId,
      sessionId: evidence.sessionId,
      status: evidence.persisted ? 'INCOMPLETE' : 'MISSING',
      evidencePath: evidence.evidencePath,
      reason: evidence.persistError ?? 'final evidence was not written',
      cleanupWithheld: true,
      timeline: evidence.timeline
    })),
    attempts: attempts.map(publicEvidence)
  }
}
