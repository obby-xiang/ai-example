import { appendFile, mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { createHash, randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const repoRoot = fileURLToPath(new URL('../..', import.meta.url))
const frontendRequire = createRequire(new URL('../../frontend/package.json', import.meta.url))
const supportedScenarios = new Set(['C-111', 'C-112', 'C-113', 'C-114', 'C-115'])
const knownScenarios = new Set(Array.from({ length: 16 }, (_, index) => `C-${111 + index}`))
const startTools = new Set(['start_export', 'start_precheck', 'start_import', 'start_publish'])
const jobTypeByTool = {
  start_export: 'EXPORT',
  start_precheck: 'PRECHECK',
  start_import: 'IMPORT',
  start_publish: 'PUBLISH'
}
const conditionValues = {
  empty: '',
  space: ' ',
  spaces: '   ',
  newline: '\n',
  'ideographic-space': '　',
  null: null,
  none: '无',
  'no-filter': '不筛选',
  all: '全部',
  cny: 'code=CNY'
}
const notRunReasons = {
  'C-116': '单步/多步骤按钮、草稿和步骤可见状态需要浏览器；多步骤存在性不能由独立表单拼接证明',
  'C-117': '全字段类型、取消按钮和提交可见状态需要真实生成式表单与浏览器；本脚本不把附件端点覆盖外推为全场景通过',
  'C-118': '表格、上传、新建对话、任务中心及弹窗按钮需要浏览器 DOM 与真实点击',
  'C-119': '中英文按钮与错误恢复入口需要 locale 下的浏览器证据',
  'C-120': '用户可见工具卡是否隐藏或展示需要浏览器；SSE 工具名不能单独证明卡面结论',
  'C-121': '最终回复是否重复需要逐阶段可见文本与浏览器对照；本脚本仅保存 API 证据',
  'C-122': '取消条件表、取消附件和停止当前轮需要独立交互路径；不得与 C-115 的 HITL 拒绝拼接为通过',
  'C-123': '上游参数偏差、HTTP 500 或 Redis 故障需要仓外真实代理/故障注入；本脚本不伪造故障',
  'C-124': '不是独立执行轮次；请运行 C-111～C-114，派生检查保存在各轮报告的 derivedChecks.C-124',
  'C-125': '焦点光环、键盘/鼠标、裁切矩形、滚动和高对比度只能由浏览器证明',
  'C-126': '不是独立执行轮次；后端日志、Redis 与数据库对照是附着证据，缺少时保持 NOT_RUN'
}

class ScenarioFailure extends Error {}
class ScenarioNotRun extends Error {
  constructor(code, message) {
    super(message)
    this.code = code
  }
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isInside(parent, candidate) {
  const relative = path.relative(path.resolve(parent), path.resolve(candidate))
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))
}

function requiredOutsidePath(name, optional = false) {
  const raw = process.env[name]?.trim() ?? ''
  if (raw === '') {
    if (optional) return null
    throw new Error(`${name} must be an absolute path outside the repository`)
  }
  if (!path.isAbsolute(raw)) throw new Error(`${name} must be an absolute path outside the repository`)
  const resolved = path.resolve(raw)
  if (isInside(repoRoot, resolved)) throw new Error(`${name} must point outside the repository`)
  return resolved
}

function integerEnv(name, fallback, minimum) {
  const parsed = Number.parseInt(process.env[name] ?? String(fallback), 10)
  if (!Number.isSafeInteger(parsed) || parsed < minimum) throw new Error(`${name} must be an integer >= ${minimum}`)
  return parsed
}

function parseArgs(raw) {
  if (isRecord(raw)) return raw
  if (typeof raw !== 'string' || raw.trim() === '') throw new ScenarioFailure('a JSON object argument is required')
  let parsed
  try { parsed = JSON.parse(raw) } catch { throw new ScenarioFailure('argument is not valid JSON') }
  if (!isRecord(parsed)) throw new ScenarioFailure('argument JSON must be an object')
  return parsed
}

function dataOf(response) {
  return response.body?.data ?? response.body
}

function idOf(value) {
  if (value === null || value === undefined || !/^\d+$/.test(String(value))) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

function idsOf(jobs) {
  return jobs.map((job) => idOf(job?.id)).filter((id) => id !== null).sort((left, right) => left - right)
}

function sameIds(left, right) {
  return JSON.stringify(idsOf(left)) === JSON.stringify(idsOf(right))
}

function safeText(value, maximum = 32_768) {
  const text = String(value ?? '')
  return text.length <= maximum ? text : `${text.slice(0, maximum)}…[truncated ${text.length - maximum} chars]`
}

function codeHead() {
  if (process.env.CODE_HEAD?.trim()) return process.env.CODE_HEAD.trim()
  try {
    return execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  } catch {
    return 'UNKNOWN'
  }
}

class Evidence {
  constructor(directory) {
    this.directory = directory
    this.sequence = 0
  }

  async init() {
    await mkdir(this.directory, { recursive: true })
    await mkdir(path.join(this.directory, 'downloads'), { recursive: true })
  }

  async writeJson(name, value) {
    await writeFile(path.join(this.directory, name), `${JSON.stringify(value, null, 2)}\n`, 'utf8')
  }

  async appendJsonl(name, value) {
    this.sequence += 1
    await appendFile(path.join(this.directory, name), `${JSON.stringify({ sequence: this.sequence, at: new Date().toISOString(), ...value })}\n`, 'utf8')
  }

  async request(entry) {
    await this.appendJsonl('requests.jsonl', entry)
  }

  async frame(channel, frame) {
    await this.appendJsonl('frames.jsonl', { channel, frame })
  }

  async event(kind, value) {
    await this.appendJsonl('events.jsonl', { kind, ...value })
  }
}

class Checks {
  constructor() {
    this.items = []
  }

  add(id, pass, expected, actual) {
    const item = { id, pass: pass === true, expected, actual }
    this.items.push(item)
    return item.pass
  }

  require(id, pass, expected, actual) {
    if (!this.add(id, pass, expected, actual)) {
      throw new ScenarioFailure(`${id}: expected ${JSON.stringify(expected)}, received ${JSON.stringify(actual)}`)
    }
  }

  get failures() {
    return this.items.filter((item) => !item.pass)
  }
}

class ApiClient {
  constructor(base, tenantId, userId, timeoutMs, evidence, tracked) {
    this.base = base
    this.tenantId = tenantId
    this.userId = userId
    this.timeoutMs = timeoutMs
    this.evidence = evidence
    this.tracked = tracked
    this.requestSequence = 0
  }

  headers(json = false) {
    return {
      'X-Tenant-Id': this.tenantId,
      'X-User-Id': this.userId,
      ...(json ? { 'Content-Type': 'application/json' } : {})
    }
  }

  async request(label, urlPath, options = {}) {
    this.requestSequence += 1
    const id = this.requestSequence
    const method = options.method ?? 'GET'
    const requestEvidence = options.multipart ?? (options.body === undefined ? null : parseArgs(options.body))
    const started = Date.now()
    try {
      const response = await fetch(`${this.base}${urlPath}`, {
        method,
        headers: { ...this.headers(options.json === true), ...(options.headers ?? {}) },
        body: options.body,
        signal: AbortSignal.timeout(this.timeoutMs)
      })
      const text = await response.text()
      let body = null
      try { body = text === '' ? null : JSON.parse(text) } catch { body = text }
      const result = {
        status: response.status,
        ok: response.ok,
        body,
        text,
        contentType: response.headers.get('content-type') ?? '',
        contentDisposition: response.headers.get('content-disposition') ?? ''
      }
      await this.evidence.request({
        id, label, method, url: `${this.base}${urlPath}`, request: requestEvidence,
        response: {
          status: result.status,
          contentType: result.contentType,
          contentDisposition: result.contentDisposition,
          body: typeof body === 'string' ? safeText(body) : body,
          durationMs: Date.now() - started
        }
      })
      return result
    } catch (error) {
      await this.evidence.request({
        id, label, method, url: `${this.base}${urlPath}`, request: requestEvidence,
        error: safeText(error?.message ?? error), durationMs: Date.now() - started
      })
      throw error
    }
  }

  async expect(label, urlPath, options = {}, statuses = [200]) {
    const response = await this.request(label, urlPath, options)
    if (!statuses.includes(response.status)) {
      throw new ScenarioFailure(`${label} returned HTTP ${response.status}: ${safeText(response.text, 1000)}`)
    }
    return response
  }

  async openSse(label, urlPath, options = {}) {
    this.requestSequence += 1
    const id = this.requestSequence
    const method = options.method ?? 'GET'
    const started = Date.now()
    let response
    try {
      response = await fetch(`${this.base}${urlPath}`, {
        method,
        headers: { ...this.headers(options.json === true), Accept: 'text/event-stream' },
        body: options.body,
        signal: options.signal
      })
    } catch (error) {
      await this.evidence.request({
        id, label, method, url: `${this.base}${urlPath}`,
        request: options.body === undefined ? null : parseArgs(options.body),
        error: safeText(error?.message ?? error), durationMs: Date.now() - started
      })
      throw error
    }
    await this.evidence.request({
      id, label, method, url: `${this.base}${urlPath}`,
      request: options.body === undefined ? null : parseArgs(options.body),
      response: {
        status: response.status,
        contentType: response.headers.get('content-type') ?? '',
        archiveTrimmed: response.headers.get('archive-trimmed'),
        archiveFrom: response.headers.get('archive-from'),
        archiveTo: response.headers.get('archive-to'),
        durationMs: Date.now() - started
      }
    })
    if (!response.ok || !(response.headers.get('content-type') ?? '').includes('text/event-stream')) {
      const text = await response.text()
      await this.evidence.event('sse_open_error', { id, status: response.status, body: safeText(text) })
      throw new ScenarioFailure(`${label} did not return SSE (HTTP ${response.status}): ${safeText(text, 1000)}`)
    }
    this.tracked.archiveStreams.push({
      label,
      url: `${this.base}${urlPath}`,
      archiveTrimmed: response.headers.get('archive-trimmed') === 'true',
      archiveFrom: response.headers.get('archive-from'),
      archiveTo: response.headers.get('archive-to')
    })
    return response
  }

  async task(taskId) {
    const response = await this.expect('task.get', `/api/tasks/${taskId}`)
    const task = dataOf(response)
    if (!isRecord(task) || idOf(task.id) !== idOf(taskId)) throw new ScenarioFailure(`task ${taskId} response is not authoritative`)
    return task
  }

  async jobs(taskId) {
    const response = await this.expect('task.jobs.list', `/api/tasks/${taskId}/jobs`)
    const jobs = dataOf(response)
    if (!Array.isArray(jobs)) throw new ScenarioFailure(`task ${taskId} jobs response is not an array`)
    return jobs
  }

  async taskBaseline() {
    const pageSize = 100
    const maxPages = 1000
    const taskIds = []
    const seen = new Set()
    let total = null
    let totalPages = null
    let pages = 0
    for (let pageNumber = 0; pageNumber < maxPages; pageNumber += 1) {
      const response = await this.expect('tasks.baseline', `/api/tasks?page=${pageNumber}&size=${pageSize}`)
      const page = dataOf(response)
      if (!isRecord(page) || !Array.isArray(page.content)) throw new ScenarioFailure('task baseline page has no content array')
      const pageTotal = Number(page.totalElements)
      if (!Number.isSafeInteger(pageTotal) || pageTotal < 0) throw new ScenarioFailure('task baseline totalElements is invalid')
      if (total === null) total = pageTotal
      if (total !== pageTotal) throw new ScenarioFailure(`task baseline changed during pagination: ${total} -> ${pageTotal}`)
      const pageTotalPages = Number(page.totalPages)
      if (Number.isSafeInteger(pageTotalPages) && pageTotalPages >= 0) {
        if (totalPages !== null && totalPages !== pageTotalPages) throw new ScenarioFailure('task baseline totalPages changed during pagination')
        totalPages = pageTotalPages
      }
      for (const entry of page.content) {
        const taskId = idOf(entry?.task?.id ?? entry?.id)
        if (taskId === null) throw new ScenarioFailure('task baseline contains an invalid task id')
        if (seen.has(taskId)) throw new ScenarioFailure(`task baseline contains duplicate task id ${taskId}`)
        seen.add(taskId)
        taskIds.push(taskId)
      }
      pages += 1
      if (taskIds.length >= total) break
      if (page.content.length === 0) throw new ScenarioFailure(`task baseline ended early at ${taskIds.length}/${total}`)
      if (totalPages !== null && pageNumber + 1 >= totalPages) break
      if (pageNumber + 1 >= maxPages) throw new ScenarioFailure(`task baseline exceeded ${maxPages} pages`)
    }
    if (total === null || taskIds.length !== total) {
      throw new ScenarioFailure(`task baseline is truncated: ${taskIds.length}/${total ?? 'unknown'}`)
    }
    return { total, ids: taskIds.sort((left, right) => left - right), truncated: false, complete: true, pages }
  }

  async createTask(type, title) {
    const before = await this.taskBaseline()
    const response = await this.expect('task.create.fixture', '/api/tasks', {
      method: 'POST', json: true, body: JSON.stringify({ type, title })
    }, [201])
    const task = dataOf(response)
    const taskId = idOf(task?.id)
    if (taskId === null) throw new ScenarioFailure('fixture task creation returned no task id')
    if (before.ids.includes(taskId)) throw new ScenarioFailure(`fixture create returned pre-existing task id ${taskId}; ownership is uncertain`)
    this.tracked.tasks.add(taskId)
    this.tracked.taskOrigins.set(taskId, 'public-fixture-create')
    return task
  }

  async selectDefs(taskId, defCodes) {
    const response = await this.expect('task.select-defs', `/api/tasks/${taskId}/select-defs`, {
      method: 'POST', json: true, body: JSON.stringify({ defCodes })
    })
    return dataOf(response)
  }

  async setCondition(taskId, defCode, condition) {
    await this.expect('task.condition.set', `/api/tasks/${taskId}/items/${encodeURIComponent(defCode)}/condition`, {
      method: 'PUT', json: true, body: JSON.stringify({ condition })
    })
  }

  async goToStep(taskId, step) {
    const response = await this.expect('task.step.set', `/api/tasks/${taskId}/step`, {
      method: 'PUT', json: true, body: JSON.stringify({ step })
    })
    return dataOf(response)
  }

  async setImportMode(taskId, mode) {
    await this.expect('task.import-mode.set', `/api/tasks/${taskId}/import-mode`, {
      method: 'PUT', json: true, body: JSON.stringify({ mode })
    })
  }

  async createJob(taskId, jobType) {
    const before = await this.jobs(taskId)
    const response = await this.expect('job.create.fixture', `/api/tasks/${taskId}/jobs`, {
      method: 'POST', json: true, body: JSON.stringify({ jobType })
    }, [201])
    const job = dataOf(response)
    const jobId = idOf(job?.id)
    if (jobId === null) throw new ScenarioFailure('fixture job creation returned no job id')
    if (idsOf(before).includes(jobId)) throw new ScenarioFailure(`fixture create returned pre-existing job id ${jobId}; ownership is uncertain`)
    this.tracked.jobs.add(jobId)
    this.tracked.jobOrigins.set(jobId, 'public-fixture-create')
    return job
  }

  async job(jobId) {
    const response = await this.expect('job.get', `/api/jobs/${jobId}`)
    const job = dataOf(response)
    if (!isRecord(job) || idOf(job.id) !== idOf(jobId)) throw new ScenarioFailure(`job ${jobId} response is not authoritative`)
    return job
  }

  async uploadTaskFile(taskId, fixturePath) {
    const bytes = await readFile(fixturePath)
    const filename = `CURRENCY_${path.basename(fixturePath).replace(/[^A-Za-z0-9_.-]/g, '_')}`
    const form = new FormData()
    form.append('file', new Blob([bytes], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    }), filename)
    form.append('defCodes', 'CURRENCY')
    const response = await this.expect('task.file.upload.fixture', `/api/tasks/${taskId}/files/upload`, {
      method: 'POST', body: form, multipart: { fileName: filename, size: bytes.length, defCodes: ['CURRENCY'] }
    })
    return dataOf(response)
  }

  async uploadFormAttachment(runId, toolCallId, sessionId, fieldKey, fixturePath) {
    const bytes = await readFile(fixturePath)
    const filename = path.basename(fixturePath)
    const extension = path.extname(filename).toLowerCase()
    const contentType = {
      '.png': 'image/png',
      '.jpg': 'image/jpeg',
      '.jpeg': 'image/jpeg',
      '.webp': 'image/webp',
      '.pdf': 'application/pdf',
      '.txt': 'text/plain',
      '.csv': 'text/csv',
      '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    }[extension]
    if (!contentType) throw new ScenarioNotRun('ATTACHMENT_TYPE_UNSUPPORTED', `attachment extension is not in the public whitelist: ${extension}`)
    const form = new FormData()
    form.append('file', new Blob([bytes], { type: contentType }), filename)
    form.append('runId', runId)
    form.append('toolCallId', toolCallId)
    form.append('sessionId', sessionId)
    form.append('fieldKey', fieldKey)
    const response = await this.expect('ai.form.attachment.upload', '/api/ai/form/attachments', {
      method: 'POST', body: form,
      multipart: { runId, toolCallId, sessionId, fieldKey, fileName: filename, size: bytes.length }
    })
    const data = dataOf(response)
    if (typeof data?.fileId !== 'string' || data.fileId === '') throw new ScenarioFailure('attachment upload returned no fileId')
    return data
  }

  async files(taskId) {
    const response = await this.expect('task.files.list', `/api/tasks/${taskId}/files`)
    const files = dataOf(response)
    if (!Array.isArray(files)) throw new ScenarioFailure(`task ${taskId} files response is not an array`)
    return files
  }

  async download(taskId, defCode, outputName) {
    const urlPath = `/api/tasks/${taskId}/files/download?codes=${encodeURIComponent(defCode)}`
    this.requestSequence += 1
    const id = this.requestSequence
    const started = Date.now()
    const response = await fetch(`${this.base}${urlPath}`, {
      headers: this.headers(false), signal: AbortSignal.timeout(this.timeoutMs)
    })
    if (!response.ok) {
      const text = await response.text()
      await this.evidence.request({ id, label: 'task.file.download', method: 'GET', url: `${this.base}${urlPath}`, response: { status: response.status, body: safeText(text) } })
      throw new ScenarioFailure(`download ${defCode} returned HTTP ${response.status}: ${safeText(text, 1000)}`)
    }
    const bytes = Buffer.from(await response.arrayBuffer())
    const outputPath = path.join(this.evidence.directory, 'downloads', outputName)
    await writeFile(outputPath, bytes)
    const evidence = {
      url: `${this.base}${urlPath}`,
      urlSource: 'frontend/src/api/tasks.ts#downloadSelected; task files and completed job read back before download',
      status: response.status,
      contentType: response.headers.get('content-type') ?? '',
      contentDisposition: response.headers.get('content-disposition') ?? '',
      fileName: outputName,
      bytes: bytes.length,
      sha256: createHash('sha256').update(bytes).digest('hex'),
      durationMs: Date.now() - started
    }
    await this.evidence.request({ id, label: 'task.file.download', method: 'GET', url: evidence.url, response: evidence })
    return { bytes, outputPath, evidence }
  }
}

class Workspace {
  constructor() {
    this.page = null
    this.taskType = null
    this.step = null
    this.taskId = null
    this.taskStatus = null
    this.selectedDefs = []
    this.conditionDrafts = {}
    this.conditionFeedback = null
    this.selectionSynced = false
  }

  bind(task) {
    this.page = task.type === 'EXPORT' ? 'export' : 'import'
    this.taskType = task.type
    this.step = task.currentStep
    this.taskId = idOf(task.id)
    this.taskStatus = task.status
    this.selectedDefs = (task.items ?? []).map((item) => item.defCode).filter(Boolean)
    this.conditionDrafts = {}
    for (const item of task.items ?? []) {
      if (item.conditionJson) this.conditionDrafts[item.defCode] = parseArgs(item.conditionJson)
    }
    this.selectionSynced = true
  }

  context() {
    return this.taskId === null ? null : {
      page: this.page,
      taskType: this.taskType,
      step: this.step,
      taskId: this.taskId,
      taskStatus: this.taskStatus,
      extra: {
        pageId: this.page,
        selectedDefs: [...this.selectedDefs],
        selectionSynced: this.selectionSynced,
        importMode: null
      }
    }
  }
}

function parseSseBlock(block) {
  const data = []
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (data.length === 0) return null
  try { return JSON.parse(data.join('\n')) } catch { return { type: 'invalid', raw: data.join('\n') } }
}

class StreamTransportError extends Error {
  constructor(channel, cause) {
    super(`SSE transport failed on ${channel}: ${cause?.message ?? cause}`)
    this.channel = channel
    this.cause = cause
  }
}

class RunDriver {
  constructor(context, message, confirmHandler, frontendHandler, toolResultHandler) {
    this.context = context
    this.message = message
    this.confirmHandler = confirmHandler
    this.frontendHandler = frontendHandler
    this.toolResultHandler = toolResultHandler
    this.runId = null
    this.lastSeq = 0
    this.terminal = null
    this.handled = new Set()
    this.error = null
  }

  async start() {
    const body = {
      sessionId: this.context.sessionId,
      message: this.message,
      context: this.context.workspace.context(),
      clientRequestId: this.context.clientRequestId
    }
    let stream = {
      channel: 'chat',
      response: await this.context.api.openSse('ai.chat', '/api/ai/chat', {
        method: 'POST', json: true, body: JSON.stringify(body), signal: AbortSignal.timeout(this.context.runTimeoutMs)
      })
    }
    let emptyReconnections = 0
    let transportReconnections = 0
    let totalReconnections = 0
    while (!this.terminal && !this.error) {
      const iterationStartSeq = this.lastSeq
      if (stream) {
        try {
          await this.pump(stream.response, stream.channel)
        } catch (error) {
          if (!(error instanceof StreamTransportError)) throw error
          transportReconnections += 1
          await this.context.evidence.event('stream_transport_error', {
            channel: error.channel,
            lastSeq: this.lastSeq,
            attempt: transportReconnections,
            error: safeText(error.cause?.message ?? error.cause)
          })
          if (!this.runId) throw new ScenarioFailure('transport failed before a runId was observed; reattach is impossible')
          if (transportReconnections > 3) throw new ScenarioFailure('SSE transport failed more than three times')
        }
      }
      if (this.terminal || this.error) break
      emptyReconnections = this.lastSeq === iterationStartSeq ? emptyReconnections + 1 : 0
      if (emptyReconnections >= 3) throw new ScenarioFailure('events reattach made no seq progress three times without a terminal frame')
      if (!this.runId) throw new ScenarioFailure('stream ended before a runId was observed')
      totalReconnections += 1
      if (totalReconnections > 32) throw new ScenarioFailure('SSE reattach exceeded 32 attempts')
      const suffix = this.lastSeq > 0 ? `?lastSeq=${this.lastSeq}` : ''
      stream = null
      try {
        stream = {
          channel: 'events',
          response: await this.context.api.openSse('ai.events.reattach',
            `/api/ai/events/${encodeURIComponent(this.runId)}${suffix}`,
            { signal: AbortSignal.timeout(this.context.runTimeoutMs) })
        }
      } catch (error) {
        transportReconnections += 1
        await this.context.evidence.event('stream_reattach_open_error', {
          lastSeq: this.lastSeq,
          attempt: transportReconnections,
          error: safeText(error?.message ?? error)
        })
        if (transportReconnections > 3) throw new ScenarioFailure('SSE reattach failed more than three times')
      }
    }
    if (this.error) throw this.error
    return this.terminal
  }

  async pump(response, channel) {
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffered = ''
    let reachedEof = false
    try {
      while (!this.terminal && !this.error) {
        let readResult
        try { readResult = await reader.read() } catch (error) { throw new StreamTransportError(channel, error) }
        const { done, value } = readResult
        if (done) {
          reachedEof = true
          buffered += decoder.decode()
          if (buffered.trim() !== '') await this.processBlock(buffered, channel)
          break
        }
        buffered += decoder.decode(value, { stream: true })
        if (buffered.length > 256 * 1024 && !/\r?\n\r?\n/.test(buffered)) {
          throw new ScenarioFailure('single SSE event exceeded 256 KiB')
        }
        let separator
        while (!this.terminal && !this.error && (separator = /\r?\n\r?\n/.exec(buffered)) !== null) {
          const block = buffered.slice(0, separator.index)
          buffered = buffered.slice(separator.index + separator[0].length)
          await this.processBlock(block, channel)
        }
      }
    } finally {
      if (!reachedEof) await reader.cancel().catch(() => {})
    }
  }

  async processBlock(block, channel) {
    const frame = parseSseBlock(block)
    if (!frame) return
    this.context.frameCount += 1
    await this.context.evidence.frame(channel, frame)
    if (frame.type === 'invalid') {
      this.error = new ScenarioFailure('invalid JSON SSE frame')
      return
    }
    const frameRunId = frame.runId ?? this.runId
    if (frame.type === 'start') this.runId = frame.runId
    if (this.runId && frame.runId && frame.runId !== this.runId) {
      this.error = new ScenarioFailure(`cross-run frame observed: ${frame.runId} != ${this.runId}`)
      return
    }
    if (frame.seq !== undefined && frame.seq !== null) {
      const seq = Number(frame.seq)
      if (!Number.isSafeInteger(seq) || seq < 0) {
        this.error = new ScenarioFailure(`invalid SSE seq: ${frame.seq}`)
        return
      }
      if (seq <= this.lastSeq) {
        await this.context.evidence.event('stream_replay_skipped', { channel, seq, lastSeq: this.lastSeq, type: frame.type })
        return
      }
      this.lastSeq = seq
    }
    if (frame.type === 'done' || frame.type === 'error') {
      this.terminal = frame
      return
    }
    if (!frameRunId || !frame.toolCallId) return
    const actionKey = `${frame.type}:${frame.toolCallId}`
    if (this.handled.has(actionKey)) return
    try {
      if (frame.type === 'confirm_request') {
        this.handled.add(actionKey)
        await this.confirmHandler(frame, this.runId)
      } else if (frame.type === 'frontend_tool_request') {
        this.handled.add(actionKey)
        await this.frontendHandler(frame, this.runId)
      } else if (frame.type === 'tool_result' || frame.type === 'frontend_tool_result') {
        this.handled.add(actionKey)
        await this.toolResultHandler(frame, this.runId)
      }
    } catch (error) {
      this.error = error
    }
  }
}

function formOf(frame) {
  return parseArgs(frame.args).form ?? null
}

function formPurpose(frame) {
  const form = formOf(frame)
  if (form?.purpose) return form.purpose
  return form?.scenario === 'FILTER' ? 'SET_CONDITIONS' : null
}

function isCnyCondition(value) {
  if (!isRecord(value)) return false
  const entries = Array.isArray(value.fields) ? value.fields : []
  return entries.some((entry) => {
    const field = entry?.fieldCode ?? entry?.field ?? entry?.key
    const operator = entry?.operator ?? entry?.op
    return field === 'code' && operator === 'EQ' && (entry?.value === 'CNY' || entry?.values === 'CNY')
  }) || Object.values(value).some((nested) => isCnyCondition(nested))
}

function taskCondition(task, defCode) {
  const item = (task.items ?? []).find((entry) => entry.defCode === defCode)
  return item?.conditionJson ? parseArgs(item.conditionJson) : null
}

function toolNameForAction(action) {
  return {
    create: 'create_task',
    export: 'start_export',
    precheck: 'start_precheck',
    import: 'start_import',
    publish: 'start_publish'
  }[action] ?? null
}

function messageFor(config, targetTaskId) {
  if (config.scenario === 'C-111') return '创建导出任务'
  if (config.scenario === 'C-112') return '导出cny货币字典'
  if (config.scenario === 'C-113') return '导出货币字典'
  if (config.scenario === 'C-114') {
    return config.taskPath === 'existing'
      ? `启动已有导出任务 #${targetTaskId}，完成后下载导出文件`
      : '新建 CURRENCY 货币字典导出任务并启动导出，完成后下载文件'
  }
  if (config.action === 'create') return '只创建导出任务，创建后停止，不打开向导，不启动后续流程'
  if (config.action === 'export') return `启动已有导出任务 #${targetTaskId}，完成后下载导出文件`
  if (config.action === 'precheck') return `对已有导入任务 #${targetTaskId} 启动预检查`
  if (config.action === 'import') return `对已有导入任务 #${targetTaskId} 启动导入`
  return `对已有导入任务 #${targetTaskId} 按 ${config.publishMode} 模式发布`
}

function expectedConfirmTools(config) {
  if (config.scenario === 'C-114') return new Set(config.taskPath === 'existing' ? ['start_export'] : ['create_task', 'start_export'])
  if (config.scenario === 'C-115') return new Set([toolNameForAction(config.action)])
  return new Set(['create_task', 'start_export'])
}

async function prepareExportTask(context, title) {
  const task = await context.api.createTask('EXPORT', title)
  const taskId = idOf(task.id)
  await context.api.selectDefs(taskId, ['CURRENCY'])
  await context.api.setCondition(taskId, 'CURRENCY', {})
  await context.api.goToStep(taskId, 'QUERY_COND')
  const ready = await context.api.goToStep(taskId, 'EXPORT')
  context.workspace.bind(ready)
  context.fixtureTaskId = taskId
  return taskId
}

async function prepareImportTask(context) {
  if (!context.config.fixtureXlsx) {
    throw new ScenarioNotRun('FIXTURE_REQUIRED', 'C-115 precheck/import/publish requires FIXTURE_XLSX with a real valid CURRENCY workbook')
  }
  const task = await context.api.createTask('IMPORT', `verification-import-${context.config.action}-${randomUUID()}`)
  const taskId = idOf(task.id)
  await context.api.selectDefs(taskId, ['CURRENCY'])
  await context.api.uploadTaskFile(taskId, context.config.fixtureXlsx)
  await context.api.goToStep(taskId, 'PRECHECK')
  if (context.config.action === 'precheck') {
    context.workspace.bind(await context.api.task(taskId))
    context.fixtureTaskId = taskId
    return taskId
  }
  const precheck = await context.api.createJob(taskId, 'PRECHECK')
  const precheckFinal = await waitForJob(context, idOf(precheck.id), 'PRECHECK')
  context.checks.require('fixture.precheck.completed', precheckFinal.status === 'COMPLETED' && precheckFinal.errorCount === 0,
    'COMPLETED with zero errors', { status: precheckFinal.status, errorCount: precheckFinal.errorCount })
  await context.api.goToStep(taskId, 'IMPORT')
  if (context.config.action === 'import') {
    context.workspace.bind(await context.api.task(taskId))
    context.fixtureTaskId = taskId
    return taskId
  }
  const imported = await context.api.createJob(taskId, 'IMPORT')
  const importFinal = await waitForJob(context, idOf(imported.id), 'IMPORT')
  context.checks.require('fixture.import.completed', importFinal.status === 'COMPLETED', 'COMPLETED', importFinal.status)
  await context.api.setImportMode(taskId, context.config.publishMode)
  const ready = await context.api.goToStep(taskId, 'PUBLISH')
  context.workspace.bind(ready)
  context.fixtureTaskId = taskId
  return taskId
}

async function waitForJob(context, jobId, expectedType) {
  const deadline = Date.now() + context.config.jobTimeoutMs
  let job = null
  while (Date.now() < deadline) {
    job = await context.api.job(jobId)
    context.checks.add(`job.${jobId}.type`, job.jobType === expectedType, expectedType, job.jobType)
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.status)) {
      await context.evidence.writeJson(`job-${jobId}-final.json`, job)
      return job
    }
    await new Promise((resolve) => setTimeout(resolve, context.config.pollMs))
  }
  throw new ScenarioFailure(`job ${jobId} did not reach a terminal status before timeout`)
}

function xmlDecode(value) {
  return value.replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos);/gi, (whole, entity) => {
    const lower = entity.toLowerCase()
    if (lower.startsWith('#x')) return String.fromCodePoint(Number.parseInt(lower.slice(2), 16))
    if (lower.startsWith('#')) return String.fromCodePoint(Number.parseInt(lower.slice(1), 10))
    return { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" }[lower] ?? whole
  })
}

function worksheetRows(xml, sharedStrings) {
  const rows = []
  for (const rowMatch of xml.matchAll(/<row\b[^>]*(?:r="(\d+)")?[^>]*>([\s\S]*?)<\/row>/g)) {
    const values = {}
    for (const cell of rowMatch[2].matchAll(/<c\b([^>]*)>([\s\S]*?)<\/c>|<c\b([^>]*)\/>/g)) {
      const attributes = cell[1] ?? cell[3] ?? ''
      const reference = /\br="([A-Z]+)\d+"/.exec(attributes)?.[1]
      if (!reference) continue
      const body = cell[2] ?? ''
      const type = /\bt="([^"]+)"/.exec(attributes)?.[1] ?? 'n'
      const rawValue = /<v[^>]*>([\s\S]*?)<\/v>/.exec(body)?.[1] ?? ''
      const inline = [...body.matchAll(/<t\b[^>]*>([\s\S]*?)<\/t>/g)].map((match) => xmlDecode(match[1])).join('')
      const value = type === 'inlineStr' || type === 'str'
        ? inline || xmlDecode(rawValue)
        : type === 's'
          ? sharedStrings[Number.parseInt(rawValue, 10)] ?? ''
          : xmlDecode(rawValue)
      values[reference] = value
    }
    rows.push({ number: Number.parseInt(rowMatch[1] ?? String(rows.length + 1), 10), values })
  }
  return rows
}

function spreadsheetColumnIndex(reference) {
  if (typeof reference !== 'string' || !/^[A-Z]+$/.test(reference)) {
    throw new ScenarioFailure(`invalid spreadsheet column reference: ${reference}`)
  }
  let index = 0
  for (const character of reference) index = index * 26 + character.charCodeAt(0) - 64
  return index
}

function spreadsheetColumnName(index) {
  if (!Number.isSafeInteger(index) || index <= 0) throw new ScenarioFailure(`invalid spreadsheet column index: ${index}`)
  let remaining = index
  let name = ''
  while (remaining > 0) {
    const digit = (remaining - 1) % 26
    name = String.fromCharCode(65 + digit) + name
    remaining = Math.floor((remaining - 1) / 26)
  }
  return name
}

async function inspectXlsx(bytes) {
  let JSZip
  try { JSZip = frontendRequire('jszip') } catch (error) {
    throw new ScenarioNotRun('XLSX_READER_UNAVAILABLE', `existing frontend jszip dependency is unavailable: ${error.message}`)
  }
  const zip = await JSZip.loadAsync(bytes)
  const readXml = async (name) => {
    const file = zip.file(name)
    if (!file) throw new ScenarioFailure(`xlsx entry missing: ${name}`)
    return file.async('string')
  }
  const sharedXml = zip.file('xl/sharedStrings.xml') ? await readXml('xl/sharedStrings.xml') : ''
  const sharedStrings = [...sharedXml.matchAll(/<si\b[^>]*>([\s\S]*?)<\/si>/g)].map((match) =>
    [...match[1].matchAll(/<t\b[^>]*>([\s\S]*?)<\/t>/g)].map((text) => xmlDecode(text[1])).join(''))
  const dataRows = worksheetRows(await readXml('xl/worksheets/sheet1.xml'), sharedStrings)
  const metaRows = worksheetRows(await readXml('xl/worksheets/sheet2.xml'), sharedStrings)
  const labelRow = metaRows.find((row) => row.number === 1) ?? metaRows[0]
  const codeRow = metaRows.find((row) => row.number === 2) ?? metaRows[1]
  const labelEntries = Object.entries(labelRow?.values ?? {})
  const codeEntries = Object.entries(codeRow?.values ?? {})
  const defCodeColumns = labelEntries.filter(([, value]) => value === 'def_code').map(([column]) => column)
  const metaCodeColumns = codeEntries.filter(([, value]) => value === 'code').map(([column]) => column)
  if (defCodeColumns.length !== 1 || metaCodeColumns.length !== 1) {
    throw new ScenarioFailure('xlsx _meta labelRow/codeRow must contain exactly one def_code label and one business code mapping')
  }
  const defCodeColumn = defCodeColumns[0]
  const metaCodeColumn = metaCodeColumns[0]
  const defCode = String(codeRow.values[defCodeColumn] ?? '').trim()
  if (defCode === '') {
    throw new ScenarioFailure('xlsx _meta codeRow def_code value is empty')
  }
  const defCodeIndex = spreadsheetColumnIndex(defCodeColumn)
  const metaCodeIndex = spreadsheetColumnIndex(metaCodeColumn)
  const dataCodeColumn = spreadsheetColumnName(metaCodeIndex - defCodeIndex)
  const headerRow = dataRows.find((row) => row.number === 1)
  if (!headerRow || !Object.prototype.hasOwnProperty.call(headerRow.values, dataCodeColumn)
    || String(headerRow.values[dataCodeColumn] ?? '').trim() === '') {
    throw new ScenarioFailure(`xlsx sheet1 has no valid header at mapped dataCodeColumn ${dataCodeColumn}`)
  }
  const populated = dataRows.filter((row) => row.number > 1 && Object.values(row.values).some((value) => value !== ''))
  const codes = populated.map((row) => row.values[dataCodeColumn] ?? '')
  return {
    sheet: 'sheet1',
    metaSheet: 'sheet2',
    defCode,
    defCodeColumn,
    metaCodeColumn,
    dataCodeColumn,
    columnOffset: defCodeIndex,
    dataRowCount: populated.length,
    codes,
    rows: populated.slice(0, 100).map((row) => ({ row: row.number, cells: row.values })),
    truncatedRows: Math.max(0, populated.length - 100)
  }
}

async function downloadAndVerify(context, expected) {
  const taskId = context.targetTaskId
  const files = await context.api.files(taskId)
  const exported = files.filter((file) => file.fileType === 'EXPORT' && file.defCode === 'CURRENCY')
  context.checks.require('download.export-file.present', exported.length === 1, 'one CURRENCY EXPORT file', exported)
  const downloaded = await context.api.download(taskId, 'CURRENCY', `task-${taskId}-job-${context.targetJobId}-CURRENCY.xlsx`)
  context.checks.require('download.xlsx.shape', downloaded.bytes.length > 500 && downloaded.bytes[0] === 0x50 && downloaded.bytes[1] === 0x4b,
    'non-empty XLSX with PK magic', { bytes: downloaded.bytes.length })
  const workbook = await inspectXlsx(downloaded.bytes)
  await context.evidence.writeJson('download-workbook-content.json', {
    download: downloaded.evidence,
    expected,
    workbook
  })
  context.downloads.push({ ...downloaded.evidence, workbook: { dataRowCount: workbook.dataRowCount, codes: workbook.codes } })
  if (expected === 'CNY') {
    context.checks.require('download.content.cny-only', workbook.dataRowCount === 1 && workbook.codes.every((code) => code === 'CNY'),
      'exactly one data row with code=CNY', { dataRowCount: workbook.dataRowCount, codes: workbook.codes })
  } else {
    context.checks.require('download.content.full', workbook.dataRowCount > 1 && workbook.codes.includes('CNY')
      && workbook.codes.some((code) => code !== 'CNY' && code !== ''),
      'multiple data rows including CNY and at least one other currency', { dataRowCount: workbook.dataRowCount, codes: workbook.codes })
  }
}

async function fullHistory(context) {
  const pages = []
  let before = null
  for (let pageNumber = 0; pageNumber < 20; pageNumber += 1) {
    const query = new URLSearchParams({ latest: '100' })
    if (before) query.set('before', before)
    const response = await context.api.expect('ai.history', `/api/ai/history/${encodeURIComponent(context.sessionId)}?${query}`)
    const data = dataOf(response)
    if (!Array.isArray(data?.messages)) throw new ScenarioFailure('history response has no messages array')
    pages.push(data)
    if (!data.hasMore || !data.nextCursor) break
    before = data.nextCursor
    if (pageNumber === 19) throw new ScenarioFailure('history pagination exceeded 20 pages')
  }
  return { pages, messages: pages.flatMap((page) => page.messages) }
}

async function collectRunEvidence(context) {
  if (!context.runId) return
  const runResponse = await context.api.expect('ai.run.get', `/api/ai/runs/${encodeURIComponent(context.runId)}`)
  context.runDetail = dataOf(runResponse)
  await context.evidence.writeJson('run-detail.json', context.runDetail)
  context.archiveEvidence = {
    streams: context.tracked.archiveStreams,
    run: {
      archiveFirstSeq: context.runDetail?.archiveFirstSeq ?? null,
      archiveReadable: context.runDetail?.archiveReadable ?? null,
      archiveTrimmed: context.runDetail?.archiveTrimmed ?? null,
      recoverable: context.runDetail?.recoverable ?? null
    }
  }
  await context.evidence.writeJson('archive-evidence.json', context.archiveEvidence)
  context.checks.add('archive.stream.not-trimmed', context.tracked.archiveStreams.every((stream) => !stream.archiveTrimmed),
    'no reattach archive-trimmed header', context.tracked.archiveStreams.filter((stream) => stream.archiveTrimmed))
  context.checks.add('archive.stream.gap-headers-valid', context.tracked.archiveStreams.every((stream) => {
    const hasGap = stream.archiveFrom !== null || stream.archiveTo !== null
    if (!hasGap) return !stream.archiveTrimmed
    const from = Number(stream.archiveFrom)
    const to = Number(stream.archiveTo)
    return stream.archiveTrimmed && Number.isSafeInteger(from) && Number.isSafeInteger(to) && from > 0 && from <= to
  }), 'archive-from/to only describe a valid trimmed gap', context.tracked.archiveStreams)
  context.checks.add('archive.run.readable', context.runDetail?.archiveReadable === true, true, context.runDetail?.archiveReadable)
  context.checks.add('archive.run.not-trimmed', context.runDetail?.archiveTrimmed === false, false, context.runDetail?.archiveTrimmed)
  context.checks.add('archive.run.recoverable-complete', context.runDetail?.recoverable === 'complete', 'complete', context.runDetail?.recoverable)
  context.checks.add('run.authoritative.exists', context.runDetail?.exists === true, true, context.runDetail?.exists)
  context.checks.add('run.authoritative.terminal', ['DONE', 'CANCELLED', 'FAILED'].includes(context.runDetail?.snapshot?.status),
    'DONE/CANCELLED/FAILED', context.runDetail?.snapshot?.status)
  context.history = await fullHistory(context)
  await context.evidence.writeJson('history.json', context.history)
  const userMessages = context.history.messages.filter((message) => message.type === 'USER' || message.role === 'user')
  context.checks.add('history.user.exactly-one', userMessages.length === 1, 1,
    userMessages.map((message) => ({ text: message.text ?? message.content, runId: message.runId })))
  context.checks.add('history.user.original-message', userMessages.length === 1
    && (userMessages[0].text ?? userMessages[0].content) === context.message, context.message,
    userMessages[0]?.text ?? userMessages[0]?.content)
}

async function submitFrontendResult(context, runId, toolCallId, result, outcome = 'ok') {
  const response = await context.api.expect('ai.frontend-tool-result', '/api/ai/frontend-tool-result', {
    method: 'POST', json: true,
    body: JSON.stringify({
      runId, toolCallId, result, source: 'frontend-executor', outcome,
      context: context.workspace.context()
    })
  })
  const ack = response.body
  context.checks.require(`frontend.${toolCallId}.accepted`, ack?.accepted === true, { accepted: true }, ack)
  return ack
}

async function handleNavigation(context, frame, runId) {
  const args = parseArgs(frame.args)
  const taskId = idOf(args.taskId ?? context.workspace.taskId)
  if (taskId === null) throw new ScenarioFailure('navigate_to has no real task to bind')
  if (context.targetTaskId !== null && taskId !== context.targetTaskId) {
    throw new ScenarioFailure(`navigate_to targets ${taskId}, expected current task ${context.targetTaskId}`)
  }
  const task = await context.api.task(taskId)
  context.workspace.bind(task)
  const result = `API 前端已绑定任务 #${taskId}（${task.type}/${task.currentStep}，已读回 ${context.workspace.selectedDefs.length} 个配置项）`
  await submitFrontendResult(context, runId, frame.toolCallId, result)
}

async function handleSelectDefinitions(context, frame, runId) {
  const args = parseArgs(frame.args)
  const codes = args.codes ?? args.defCodes
  if (!Array.isArray(codes) || codes.length === 0 || !codes.every((code) => typeof code === 'string' && code.length > 0)) {
    throw new ScenarioFailure('select_definitions did not contain a non-empty code list')
  }
  if (context.workspace.taskId === null || context.workspace.taskId !== context.targetTaskId) {
    throw new ScenarioFailure('select_definitions arrived before current target task binding')
  }
  context.workspace.selectedDefs = [...new Set(codes)]
  const result = `API 前端已形成配置选择草案：${context.workspace.selectedDefs.join('、')}（推进步骤时才提交）`
  await submitFrontendResult(context, runId, frame.toolCallId, result)
}

async function handleSetCondition(context, frame, runId) {
  const args = parseArgs(frame.args)
  if (typeof args.defCode !== 'string' || args.defCode === '' || args.conditions === undefined) {
    throw new ScenarioFailure('set_condition requires defCode and explicit conditions')
  }
  if (context.workspace.taskId === null || context.workspace.taskId !== context.targetTaskId
    || context.workspace.step !== 'QUERY_COND' || !context.workspace.selectedDefs.includes(args.defCode)) {
    throw new ScenarioFailure(`set_condition arrived outside the current target QUERY_COND selection: ${args.defCode}`)
  }
  context.workspace.conditionDrafts[args.defCode] = args.conditions
  const result = `API 前端已形成 ${args.defCode} 查询条件草案（推进步骤时才提交）`
  await submitFrontendResult(context, runId, frame.toolCallId, result)
}

async function handleConfirmStep(context, frame, runId) {
  const args = parseArgs(frame.args)
  const taskId = idOf(args.taskId ?? context.workspace.taskId ?? context.targetTaskId)
  if (taskId === null) throw new ScenarioFailure('confirm_step has no current target task')
  if (taskId !== context.targetTaskId) throw new ScenarioFailure(`confirm_step targets a different task: ${taskId}`)
  const currentStep = context.workspace.step
  const nextStep = {
    SELECT_DEFS: 'QUERY_COND',
    QUERY_COND: 'EXPORT',
    UPLOAD: 'PRECHECK',
    PRECHECK: 'IMPORT',
    IMPORT: 'PUBLISH'
  }[currentStep]
  if (!nextStep) throw new ScenarioFailure(`confirm_step has no next step for ${currentStep}`)
  const targetStep = args.step === undefined || args.step === null
    ? nextStep
    : typeof args.step === 'string' ? args.step.trim() : ''
  if (targetStep !== nextStep) throw new ScenarioFailure(`confirm_step must advance ${currentStep} to ${nextStep}, not ${targetStep}`)
  if (context.workspace.selectedDefs.length === 0) throw new ScenarioFailure('confirm_step refuses to persist an empty definition selection')
  if (currentStep === 'QUERY_COND' && context.workspace.conditionFeedback?.nonEmpty
    && !Object.prototype.hasOwnProperty.call(context.workspace.conditionDrafts, 'CURRENCY')) {
    throw new ScenarioFailure('non-empty condition feedback has no set_condition draft; refusing to persist full export')
  }
  await context.api.selectDefs(taskId, context.workspace.selectedDefs)
  if (currentStep === 'QUERY_COND') {
    for (const defCode of context.workspace.selectedDefs) {
      const condition = context.workspace.conditionDrafts[defCode]
        ?? (context.workspace.conditionFeedback?.blank ? {} : undefined)
      if (condition !== undefined) await context.api.setCondition(taskId, defCode, condition)
    }
  }
  await context.api.goToStep(taskId, targetStep)
  const readback = await context.api.task(taskId)
  context.checks.require(`confirm-step.${frame.toolCallId}.readback`, readback.currentStep === targetStep,
    targetStep, readback.currentStep)
  context.workspace.bind(readback)
  await submitFrontendResult(context, runId, frame.toolCallId,
    `API 前端已提交当前步骤数据并读回任务 #${taskId} 步骤 ${targetStep}`)
}

async function handleGenerativeForm(context, frame, runId) {
  const form = formOf(frame)
  const fields = Array.isArray(form?.fields) ? form.fields : []
  const purpose = formPurpose(frame)
  let values
  if (purpose === 'SELECT_DEFINITIONS') {
    const field = fields.find((entry) => entry.type === 'multi_select')
    if (!field) throw new ScenarioFailure('SELECT_DEFINITIONS form has no multi_select field')
    const options = (field.options ?? []).map((option) => option.value)
    context.checks.require('form.currency.option', options.includes('CURRENCY'), 'CURRENCY option', options)
    values = { [field.key]: ['CURRENCY'] }
  } else if (purpose === 'SET_CONDITIONS') {
    const field = fields.find((entry) => entry.type === 'text')
    if (!field) throw new ScenarioFailure('SET_CONDITIONS form has no text field')
    const value = context.config.conditionValue
    values = { [field.key]: value }
    context.workspace.conditionFeedback = {
      raw: value,
      blank: value === null || String(value).trim() === '',
      nonEmpty: value !== null && String(value).trim() !== ''
    }
  } else if (fields.length > 0 && fields.every((field) => field.type === 'attachment')) {
    if (!context.config.attachmentFile) {
      throw new ScenarioNotRun('ATTACHMENT_REQUIRED', 'a real attachment form requires ATTACHMENT_FILE; no fileId or upload success was fabricated')
    }
    values = {}
    for (const field of fields) {
      const uploaded = await context.api.uploadFormAttachment(runId, frame.toolCallId, context.sessionId, field.key,
        context.config.attachmentFile)
      values[field.key] = uploaded.fileId
      context.attachments.push(uploaded)
    }
  } else {
    throw new ScenarioFailure(`unsupported generative form purpose/schema: ${purpose ?? 'unknown'}`)
  }
  await context.evidence.writeJson(`form-${frame.toolCallId}.json`, { runId, toolCallId: frame.toolCallId, form, values })
  await submitFrontendResult(context, runId, frame.toolCallId, JSON.stringify(values))
}

async function handleDownloadTool(context, frame, runId) {
  const args = parseArgs(frame.args)
  const taskId = idOf(args.taskId ?? context.targetTaskId)
  const codes = args.defCodes ?? args.codes
  if (taskId !== context.targetTaskId || !Array.isArray(codes) || codes.length !== 1 || codes[0] !== 'CURRENCY') {
    throw new ScenarioFailure('download_export_files requested an unverified task/code set')
  }
  if (!context.targetJobId) throw new ScenarioFailure('download_export_files arrived before the target job was identified')
  const job = await context.api.job(context.targetJobId)
  if (job.status !== 'COMPLETED') {
    await submitFrontendResult(context, runId, frame.toolCallId, `任务 #${taskId} 的 CURRENCY 作业尚未完成（${job.status}），未下载`, 'failed')
    return
  }
  const files = await context.api.files(taskId)
  if (!files.some((file) => file.fileType === 'EXPORT' && file.defCode === 'CURRENCY')) {
    await submitFrontendResult(context, runId, frame.toolCallId, `任务 #${taskId} 尚无 CURRENCY 导出文件，未下载`, 'failed')
    return
  }
  const downloaded = await context.api.download(taskId, 'CURRENCY', `tool-${frame.toolCallId}-CURRENCY.xlsx`)
  await submitFrontendResult(context, runId, frame.toolCallId,
    `API 前端已真实下载任务 #${taskId} 的 CURRENCY 文件到仓外证据目录（${downloaded.evidence.bytes} bytes，sha256=${downloaded.evidence.sha256}）`)
}

async function handleFrontend(context, frame, runId) {
  const handlers = {
    navigate_to: handleNavigation,
    select_definitions: handleSelectDefinitions,
    set_condition: handleSetCondition,
    confirm_step: handleConfirmStep,
    generative_form: handleGenerativeForm,
    download_export_files: handleDownloadTool
  }
  const handler = handlers[frame.name]
  if (!handler) {
    await submitFrontendResult(context, runId, frame.toolCallId,
      `API 验证前端不支持 ${frame.name}，未执行该动作`, 'failed')
    throw new ScenarioFailure(`unsupported frontend tool in ${context.config.scenario}: ${frame.name}`)
  }
  await handler(context, frame, runId)
}

function extractCreatedTaskId(frame) {
  const structured = idOf(frame.createdTaskId ?? frame.data?.createdTaskId)
  if (structured !== null) return structured
  const result = String(frame.result ?? '')
  const currentFormat = /任务\s*#(\d+)\s*已创建/.exec(result)
  if (currentFormat) return idOf(currentFormat[1])
  return idOf(/已创建(?:导出|导入)任务\s*#(\d+)/.exec(result)?.[1])
}

async function handleConfirm(context, frame, runId) {
  const expectedTools = expectedConfirmTools(context.config)
  context.checks.add('hitl.expected-tool', expectedTools.has(frame.name), [...expectedTools], frame.name)
  const publishModeMatches = frame.name !== 'start_publish' || frame.preflight?.publishMode === context.config.publishMode
  if (frame.name === 'start_publish') {
    context.checks.add('publish.authoritative-mode', publishModeMatches, context.config.publishMode, frame.preflight?.publishMode ?? null)
  }
  let approved = expectedTools.has(frame.name) && publishModeMatches
    && (context.config.scenario !== 'C-115' || context.config.decision === 'approve')
  const before = {
    tasks: await context.api.taskBaseline(),
    jobs: context.targetTaskId ? await context.api.jobs(context.targetTaskId) : []
  }
  if (frame.name === 'create_task') {
    const noTaskSideEffect = before.tasks.total === context.baseline.tasks.total
      && sameIds(before.tasks.ids.map((id) => ({ id })), context.baseline.tasks.ids.map((id) => ({ id })))
    context.checks.add('create.pre-approval.zero-task', noTaskSideEffect,
      'no task-count or id-set change before create approval', { before: context.baseline.tasks, atConfirm: before.tasks })
    if (!noTaskSideEffect) approved = false
  }
  if (startTools.has(frame.name)) {
    const args = parseArgs(frame.args)
    const argTaskId = idOf(args.taskId)
    context.checks.require('start.target.matches', argTaskId !== null && argTaskId === context.targetTaskId,
      context.targetTaskId, argTaskId)
    const noJobSideEffect = sameIds(before.jobs, context.baseline.targetJobs)
    context.checks.add('start.pre-approval.zero-job', noJobSideEffect,
      'no new job before start approval', { baseline: idsOf(context.baseline.targetJobs), atConfirm: idsOf(before.jobs) })
    if (!noJobSideEffect) approved = false
    context.checks.add('start.irreversible.flag', (frame.irreversible === true) === (frame.name === 'start_publish'),
      'only start_publish is irreversible', { name: frame.name, irreversible: frame.irreversible ?? null })
  }
  const response = await context.api.expect('ai.confirm', '/api/ai/confirm', {
    method: 'POST', json: true,
    body: JSON.stringify({
      runId,
      toolCallId: frame.toolCallId,
      approved,
      reason: approved ? 'single-scenario API approval' : 'single-scenario API rejection',
      context: context.workspace.context()
    })
  })
  context.checks.require(`confirm.${frame.toolCallId}.accepted`, response.body?.accepted === true
    && response.body?.approved === approved, { accepted: true, approved }, response.body)
  context.decisions.push({ frame, approved, before, response: response.body })
  context.baseline.targetJobs = before.jobs
}

async function handleToolResult(context, frame) {
  if (frame.name === 'create_task' && frame.outcome === 'ok') {
    context.checks.require('create.executed', frame.executed === true, true, frame.executed)
    const taskId = extractCreatedTaskId(frame)
    context.checks.require('create.task-id.present', taskId !== null, 'structured or textual new task id', frame.result)
    const after = await context.api.taskBaseline()
    context.checks.add('create.post-approval.one-task', after.total === context.baseline.tasks.total + 1,
      context.baseline.tasks.total + 1, after.total)
    context.checks.require('create.new-not-reused', !context.baseline.tasks.ids.includes(taskId),
      'task absent from pre-chat baseline', taskId)
    context.tracked.tasks.add(taskId)
    context.tracked.taskOrigins.set(taskId, `run:${context.runId}:create_task:${frame.toolCallId}`)
    context.targetTaskId = taskId
    const task = await context.api.task(taskId)
    context.workspace.bind(task)
    const jobs = await context.api.jobs(taskId)
    context.checks.add('create.no-job', jobs.length === 0, 0, idsOf(jobs))
    context.baseline.targetJobs = jobs
  }
  if (startTools.has(frame.name) && frame.outcome === 'ok') {
    context.checks.require('start.executed', frame.executed === true, true, frame.executed)
    const jobs = await context.api.jobs(context.targetTaskId)
    const beforeIds = new Set(idsOf(context.baseline.targetJobs))
    const created = jobs.filter((job) => idOf(job.id) !== null && !beforeIds.has(idOf(job.id)))
    context.checks.require('start.post-approval.one-job', created.length === 1, 'exactly one new job', idsOf(created))
    context.targetJobId = idOf(created[0].id)
    context.tracked.jobs.add(context.targetJobId)
    context.tracked.jobOrigins.set(context.targetJobId, `run:${context.runId}:${frame.name}:${frame.toolCallId}`)
    context.checks.add('start.job.type', created[0].jobType === jobTypeByTool[frame.name], jobTypeByTool[frame.name], created[0].jobType)
    context.baseline.targetJobs = jobs
  }
}

function scenarioFrames(context, type, name) {
  const seen = new Set()
  return context.frames.filter((entry) => {
    if (entry.frame.type !== type || (name && entry.frame.name !== name)) return false
    if (!entry.frame.toolCallId) return true
    const key = `${entry.frame.type}:${entry.frame.toolCallId}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

function assertCommonCompletion(context) {
  const terminal = context.terminal
  context.checks.add('run.terminal.done', terminal?.type === 'done', 'done', terminal)
  context.checks.add('run.not-unconfirmed', terminal?.unconfirmed !== true, 'no unconfirmed terminal', terminal?.unconfirmed ?? null)
  const userTexts = context.history?.messages.filter((message) => message.type === 'USER') ?? []
  context.checks.add('run.single-user-turn', userTexts.length === 1, 1, userTexts.length)
  const createCalls = scenarioFrames(context, 'confirm_request', 'create_task')
  const startCalls = scenarioFrames(context, 'confirm_request').filter((entry) => startTools.has(entry.frame.name))
  const expectedCreate = context.config.scenario === 'C-114'
    ? (context.config.taskPath === 'new' ? 1 : 0)
    : context.config.scenario === 'C-115'
      ? (context.config.action === 'create' ? 1 : 0)
      : 1
  const expectedStart = context.config.scenario === 'C-115'
    ? (context.config.action === 'create' ? 0 : 1)
    : 1
  context.checks.add('hitl.create.count', createCalls.length === expectedCreate, expectedCreate, createCalls.length)
  context.checks.add('hitl.start.count', startCalls.length === expectedStart, expectedStart, startCalls.length)
  context.checks.add('run.single-run-id', context.frames.every((entry) => !entry.frame.runId || entry.frame.runId === context.runId),
    `all frames belong to ${context.runId}`, context.frames.filter((entry) => entry.frame.runId && entry.frame.runId !== context.runId)
      .map((entry) => entry.frame.runId))
  if (['C-111', 'C-112', 'C-113', 'C-114'].includes(context.config.scenario)) {
    const forms = scenarioFrames(context, 'frontend_tool_request', 'generative_form')
    const selection = forms.find((entry) => formPurpose(entry.frame) === 'SELECT_DEFINITIONS')
    const condition = forms.find((entry) => formPurpose(entry.frame) === 'SET_CONDITIONS')
    const sequence = context.config.scenario === 'C-111'
      ? [['create', createCalls[0]], ['select-defs', selection], ['conditions', condition], ['start', startCalls[0]]]
      : context.config.scenario === 'C-112'
        ? [['create', createCalls[0]], ['start', startCalls[0]]]
        : context.config.scenario === 'C-113'
          ? [['create', createCalls[0]], ['conditions', condition], ['start', startCalls[0]]]
          : context.config.taskPath === 'new'
            ? [['create', createCalls[0]], ['conditions', condition], ['start', startCalls[0]]]
            : [['start', startCalls[0]]]
    const indices = sequence.map(([, entry]) => entry ? context.frames.indexOf(entry) : -1)
    context.checks.add('run.one-turn.sequence', indices.every((index, position) => index >= 0
      && (position === 0 || indices[position - 1] < index)),
      sequence.map(([label]) => label), indices)
  }
  if (context.config.decision !== 'reject') {
    context.checks.add('run.no-error-frame', !scenarioFrames(context, 'error').length, 'no error frame',
      scenarioFrames(context, 'error').map((entry) => entry.frame.code))
  }
  const terminalIndex = context.frames.findIndex((entry) => entry.frame.type === 'done' || entry.frame.type === 'error')
  const lastRequiredAction = context.frames.map((entry, index) => ({ entry, index }))
    .filter(({ entry }) => entry.frame.type === 'tool_result' && (entry.frame.name === 'create_task' || startTools.has(entry.frame.name)))
    .map(({ index }) => index).pop() ?? -1
  if (expectedCreate + expectedStart > 0) {
    context.checks.add('run.no-premature-done', terminalIndex > lastRequiredAction,
      'terminal after final create/start tool result', { terminalIndex, lastRequiredAction })
  }
}

async function assertScenarioSpecific(context) {
  const forms = scenarioFrames(context, 'frontend_tool_request', 'generative_form')
  const selectionForms = forms.filter((entry) => formPurpose(entry.frame) === 'SELECT_DEFINITIONS')
  const conditionForms = forms.filter((entry) => formPurpose(entry.frame) === 'SET_CONDITIONS')
  if (context.config.scenario === 'C-111') {
    context.checks.add('c111.selection-form.once', selectionForms.length === 1, 1, selectionForms.length)
    context.checks.add('c111.condition-form.once', conditionForms.length === 1, 1, conditionForms.length)
    const createArgs = parseArgs(scenarioFrames(context, 'confirm_request', 'create_task')[0]?.frame.args)
    context.checks.add('c111.create.args', createArgs.type === 'EXPORT' && createArgs.defCodes === undefined
      && isRecord(createArgs.conditions) && Object.keys(createArgs.conditions).length === 0,
      'type=EXPORT, no defCodes, conditions={}', createArgs)
    const task = await context.api.task(context.targetTaskId)
    context.checks.add('c111.selected-currency', JSON.stringify((task.items ?? []).map((item) => item.defCode)) === '["CURRENCY"]',
      ['CURRENCY'], (task.items ?? []).map((item) => item.defCode))
  }
  if (context.config.scenario === 'C-112') {
    context.checks.add('c112.no-forms', forms.length === 0, 0, forms.length)
    const createArgs = parseArgs(scenarioFrames(context, 'confirm_request', 'create_task')[0]?.frame.args)
    context.checks.add('c112.create.defs', JSON.stringify(createArgs.defCodes) === '["CURRENCY"]', ['CURRENCY'], createArgs.defCodes)
    context.checks.add('c112.create.cny', isCnyCondition(createArgs.conditions), 'code EQ CNY', createArgs.conditions)
    const task = await context.api.task(context.targetTaskId)
    context.checks.add('c112.persisted.cny', isCnyCondition(taskCondition(task, 'CURRENCY')), 'code EQ CNY', taskCondition(task, 'CURRENCY'))
  }
  if (context.config.scenario === 'C-113') {
    context.checks.add('c113.selection-form.none', selectionForms.length === 0, 0, selectionForms.length)
    context.checks.add('c113.condition-form.once', conditionForms.length === 1, 1, conditionForms.length)
    const serializedFrames = JSON.stringify(context.frames)
    context.checks.add('c113.no-known-slots-error', !serializedFrames.includes('FORM_SLOTS_ALREADY_KNOWN'),
      'no FORM_SLOTS_ALREADY_KNOWN', serializedFrames.includes('FORM_SLOTS_ALREADY_KNOWN'))
    const task = await context.api.task(context.targetTaskId)
    const defCodes = (task.items ?? []).map((item) => item.defCode)
    context.checks.add('c113.only-currency-item', defCodes.length === 1 && defCodes[0] === 'CURRENCY',
      ['CURRENCY'], defCodes)
    if (context.config.conditionKey === 'cny') {
      context.checks.add('c113.persisted.cny', isCnyCondition(taskCondition(task, 'CURRENCY')), 'code EQ CNY', taskCondition(task, 'CURRENCY'))
    } else {
      const condition = taskCondition(task, 'CURRENCY')
      context.checks.add('c113.persisted.full', isRecord(condition) && Object.keys(condition).length === 0,
        '{} for blank/no-filter input', condition)
    }
  }
  if (context.config.scenario === 'C-114' && context.config.taskPath === 'existing') {
    context.checks.add('c114.existing.no-create', scenarioFrames(context, 'confirm_request', 'create_task').length === 0,
      0, scenarioFrames(context, 'confirm_request', 'create_task').length)
    context.checks.add('c114.existing.same-task', context.targetTaskId === context.fixtureTaskId,
      context.fixtureTaskId, context.targetTaskId)
  }
  if (context.config.scenario === 'C-115') {
    const decisions = context.decisions.filter((decision) => decision.frame.name === toolNameForAction(context.config.action))
    context.checks.add('c115.one-real-decision', decisions.length === 1
      && decisions[0].approved === (context.config.decision === 'approve'),
      `one ${context.config.decision} decision`, decisions.map((decision) => decision.approved))
    if (context.config.action === 'create' && context.config.decision === 'approve') {
      const createRequest = scenarioFrames(context, 'confirm_request', 'create_task')[0]
      const createArgs = parseArgs(createRequest?.frame.args)
      context.checks.add('c115.create.open-wizard-false', createArgs.openWizard === false, false, createArgs.openWizard)
      const createIndex = createRequest ? context.frames.indexOf(createRequest) : -1
      const continuationTools = new Set([
        'navigate_to', 'select_definitions', 'set_condition', 'confirm_step', 'generative_form',
        'start_export', 'start_precheck', 'start_import', 'start_publish'
      ])
      const continuations = context.frames.filter((entry, index) => index > createIndex
        && (entry.frame.type === 'frontend_tool_request'
          || (entry.frame.type === 'confirm_request' && entry.frame.name !== 'create_task')
          || continuationTools.has(entry.frame.name)))
      context.checks.add('c115.create.no-continuation', continuations.length === 0,
        'no navigate/config/condition/start continuation after create-only approval',
        continuations.map((entry) => ({ type: entry.frame.type, name: entry.frame.name, toolCallId: entry.frame.toolCallId })))
    }
    if (context.config.decision === 'reject') {
      context.checks.add('c115.reject.no-new-job', sameIds(context.baseline.targetJobs, context.targetTaskId ? await context.api.jobs(context.targetTaskId) : []),
        'no new job after rejection', idsOf(context.baseline.targetJobs))
      if (context.config.action === 'create') {
        const after = await context.api.taskBaseline()
        context.checks.add('c115.reject-create.no-task', after.total === context.baseline.tasks.total,
          context.baseline.tasks.total, after.total)
      }
    }
  }
}

async function derivedC124(context, executionIssue, notRunIssue, cleanupStatus) {
  const orderedTypes = context.frames.map((entry) => `${entry.frame.type}:${entry.frame.name ?? ''}`)
  const createIndex = context.frames.findIndex((entry) => entry.frame.type === 'confirm_request' && entry.frame.name === 'create_task')
  const startIndex = context.frames.findIndex((entry) => entry.frame.type === 'confirm_request' && startTools.has(entry.frame.name))
  const doneIndex = context.frames.findIndex((entry) => entry.frame.type === 'done')
  return {
    status: cleanupStatus === 'INCOMPLETE' || executionIssue || context.checks.failures.length > 0
      ? 'FAIL' : notRunIssue ? 'NOT_RUN' : 'PASS_API',
    executionIssue,
    notRunIssue,
    cleanupStatus,
    sameRun: context.frames.every((entry) => !entry.frame.runId || entry.frame.runId === context.runId),
    archiveComplete: context.runDetail?.archiveReadable === true && context.runDetail?.archiveTrimmed === false
      && context.runDetail?.recoverable === 'complete' && context.tracked.archiveStreams.every((stream) => !stream.archiveTrimmed),
    archive: context.archiveEvidence ?? null,
    createBeforeStart: createIndex < 0 || startIndex < 0 || createIndex < startIndex,
    terminalAfterStart: startIndex < 0 || doneIndex > startIndex,
    orderedTypes,
    taskId: context.targetTaskId,
    jobId: context.targetJobId,
    userMessageCount: context.history?.messages.filter((message) => message.type === 'USER').length ?? null
  }
}

async function runSupportedScenario(context) {
  context.preRunTaskBaseline = await context.api.taskBaseline()
  if (context.config.scenario === 'C-114' && context.config.taskPath === 'existing') {
    context.targetTaskId = await prepareExportTask(context, `verification-existing-${randomUUID()}`)
  } else if (context.config.scenario === 'C-115' && context.config.action === 'export') {
    context.targetTaskId = await prepareExportTask(context, `verification-export-${randomUUID()}`)
  } else if (context.config.scenario === 'C-115' && ['precheck', 'import', 'publish'].includes(context.config.action)) {
    context.targetTaskId = await prepareImportTask(context)
  }
  const initialTargetJobs = context.targetTaskId ? await context.api.jobs(context.targetTaskId) : []
  context.baseline = {
    tasks: await context.api.taskBaseline(),
    targetJobs: initialTargetJobs,
    initialTargetJobs
  }
  context.message = messageFor(context.config, context.targetTaskId)
  await context.evidence.writeJson('baseline.json', context.baseline)
  const driver = new RunDriver(
    context,
    context.message,
    (frame, runId) => handleConfirm(context, frame, runId),
    (frame, runId) => handleFrontend(context, frame, runId),
    (frame, runId) => handleToolResult(context, frame, runId)
  )
  context.terminal = await driver.start()
  context.runId = driver.runId
  const rejectedCreate = context.config.scenario === 'C-115' && context.config.action === 'create' && context.config.decision === 'reject'
  context.checks.require('task.target.present', rejectedCreate ? context.targetTaskId === null : context.targetTaskId !== null,
    rejectedCreate ? 'no task after create rejection' : 'one target task', context.targetTaskId)
  const expectsJob = context.config.scenario !== 'C-115'
    || (context.config.action !== 'create' && context.config.decision === 'approve')
  context.checks.require('job.target.presence', expectsJob ? context.targetJobId !== null : context.targetJobId === null,
    expectsJob ? 'one target job after start approval' : 'no target job in this path', context.targetJobId)
  if (expectsJob) {
    const expectedType = context.config.scenario === 'C-115' ? jobTypeByTool[toolNameForAction(context.config.action)] : 'EXPORT'
    const final = await waitForJob(context, context.targetJobId, expectedType)
    context.checks.add('job.completed', final.status === 'COMPLETED', 'COMPLETED', final.status)
    if (expectedType === 'EXPORT') await downloadAndVerify(context, context.expectedFile)
  }
  const expectsDownload = context.config.scenario !== 'C-115'
    || (context.config.action === 'export' && context.config.decision === 'approve')
  context.checks.add('download.required-evidence', !expectsDownload || context.downloads.length === 1,
    expectsDownload ? 'one verified real download' : 'download not required', context.downloads.length)
  if (context.config.scenario === 'C-115' && context.config.decision === 'reject') {
    await new Promise((resolve) => setTimeout(resolve, context.config.rejectObserveMs))
  }
  await collectRunEvidence(context)
  assertCommonCompletion(context)
  await assertScenarioSpecific(context)
}

function strictCleanupJobIds(jobs) {
  if (!Array.isArray(jobs)) return { ids: null, error: 'response is not an array' }
  const ids = []
  const seen = new Set()
  for (const [index, job] of jobs.entries()) {
    const jobId = idOf(job?.id)
    if (jobId === null) return { ids: null, error: `invalid job id at index ${index}` }
    if (seen.has(jobId)) return { ids: null, error: `duplicate job id ${jobId}` }
    seen.add(jobId)
    ids.push(jobId)
  }
  if (ids.length !== jobs.length) return { ids: null, error: `job id count ${ids.length} != entry count ${jobs.length}` }
  return { ids, error: null }
}

function cleanupNewJobIds(context, taskId, currentJobs) {
  const current = strictCleanupJobIds(currentJobs)
  if (current.error) return current
  const preRunIds = new Set(context.preRunTaskBaseline.ids)
  if (!preRunIds.has(taskId) || taskId !== context.targetTaskId) {
    return { ids: current.ids, expectedIds: current.ids, error: null }
  }
  const initial = strictCleanupJobIds(context.baseline.initialTargetJobs ?? [])
  if (initial.error) return { ids: null, expectedIds: null, error: `baseline ${initial.error}` }
  const initialIds = new Set(initial.ids)
  return {
    ids: current.ids.filter((jobId) => !initialIds.has(jobId)),
    expectedIds: current.ids,
    error: null
  }
}

async function reconcileResources(context, phase) {
  const tasks = await context.api.taskBaseline()
  const preRunIds = new Set(context.preRunTaskBaseline.ids)
  const newTaskIds = tasks.ids.filter((taskId) => !preRunIds.has(taskId))
  const parentTaskIds = [...new Set([...newTaskIds, ...(context.targetTaskId ? [context.targetTaskId] : [])])]
  const parentJobs = []
  const uncertainJobs = []
  const uncertainParents = []
  for (const taskId of parentTaskIds) {
    const response = await context.api.request(`cleanup.${phase}.task.jobs`, `/api/tasks/${taskId}/jobs`)
    if (response.status === 404) {
      parentJobs.push({ taskId, absent: true, newJobIds: [], expectedJobIds: [], error: null })
      continue
    }
    if (response.status !== 200) {
      parentJobs.push({ taskId, absent: false, newJobIds: null, expectedJobIds: null, error: `HTTP ${response.status}` })
      uncertainParents.push(taskId)
      uncertainJobs.push({ taskId, jobId: null, reason: 'job-list-unavailable', httpStatus: response.status })
      continue
    }
    const currentJobs = dataOf(response)
    const validated = cleanupNewJobIds(context, taskId, currentJobs)
    if (validated.error) {
      parentJobs.push({ taskId, absent: false, newJobIds: null, expectedJobIds: null, error: validated.error })
      uncertainParents.push(taskId)
      uncertainJobs.push({ taskId, jobId: null, reason: 'job-list-invalid-ids', error: validated.error })
      continue
    }
    parentJobs.push({ taskId, absent: false, newJobIds: validated.ids, expectedJobIds: validated.expectedIds, error: null })
    for (const jobId of validated.ids) {
      if (!context.tracked.jobs.has(jobId) || !context.tracked.jobOrigins.has(jobId)) {
        uncertainJobs.push({ taskId, jobId, reason: 'unknown-origin' })
      }
    }
  }
  return {
    tasks,
    newTaskIds,
    parentJobs,
    uncertainJobs,
    uncertainParents,
    missingPreExisting: context.preRunTaskBaseline.ids.filter((taskId) => !tasks.ids.includes(taskId))
  }
}

async function verifyParentBeforeDelete(context, parentEvidence, taskId) {
  const response = await context.api.request('cleanup.task.jobs.pre-delete', `/api/tasks/${taskId}/jobs`)
  if (response.status === 404) {
    return {
      safe: parentEvidence.absent === true,
      httpStatus: 404,
      preAbsent: parentEvidence.absent,
      currentAbsent: true,
      reason: parentEvidence.absent === true ? null : 'parent absence changed'
    }
  }
  if (response.status !== 200) {
    return { safe: false, httpStatus: response.status, reason: 'pre-delete job list read failed' }
  }
  const currentJobs = dataOf(response)
  const validated = cleanupNewJobIds(context, taskId, currentJobs)
  if (validated.error) {
    return { safe: false, httpStatus: 200, reason: validated.error, currentJobIds: null }
  }
  const preJobIds = [...(parentEvidence.newJobIds ?? [])].sort((left, right) => left - right)
  const currentJobIds = [...validated.ids].sort((left, right) => left - right)
  const preExpectedJobIds = [...(parentEvidence.expectedJobIds ?? [])].sort((left, right) => left - right)
  const currentExpectedJobIds = [...validated.expectedIds].sort((left, right) => left - right)
  const sameIds = JSON.stringify(preJobIds) === JSON.stringify(currentJobIds)
  const sameExpectedIds = JSON.stringify(preExpectedJobIds) === JSON.stringify(currentExpectedJobIds)
  const absenceMatches = parentEvidence.absent === false
  const currentIdSet = new Set(currentExpectedJobIds)
  const unsafeJobs = currentJobs.filter((job) => currentIdSet.has(idOf(job?.id))).filter((job) => {
    const jobId = idOf(job.id)
    return !context.tracked.jobs.has(jobId) || !context.tracked.jobOrigins.has(jobId)
      || !['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.status)
  }).map((job) => ({ jobId: idOf(job.id), status: job.status, originKnown: context.tracked.jobOrigins.has(idOf(job.id)) }))
  return {
    safe: absenceMatches && sameIds && sameExpectedIds && unsafeJobs.length === 0,
    httpStatus: 200,
    preAbsent: parentEvidence.absent,
    currentAbsent: false,
    preJobIds,
    currentJobIds,
    preExpectedJobIds,
    currentExpectedJobIds,
    unsafeJobs,
    reason: !absenceMatches ? 'parent absence changed'
      : !sameExpectedIds ? 'full expected job set changed'
        : !sameIds ? 'new job set changed' : unsafeJobs.length > 0 ? 'job origin or terminal state unsafe' : null
  }
}

async function cleanup(context) {
  const result = {
    status: 'NOT_REQUIRED',
    preRunTaskBaseline: context.preRunTaskBaseline ?? null,
    pre: null,
    post: null,
    jobs: [],
    tasks: [],
    uncertain: { tasks: [], jobs: [], parents: [], missingPreExisting: [], postTasks: [], postJobs: [], postParents: [] },
    failures: [],
    businessDataRollback: 'NOT_SUPPORTED; publish/config changes require external comparison'
  }
  const fail = (kind, details) => result.failures.push({ kind, ...details })
  if (!supportedScenarios.has(context.config.scenario) || !context.preRunTaskBaseline) {
    await context.evidence.writeJson('cleanup.json', result)
    return result
  }
  const unsafeParentTaskIds = new Set()
  try {
    result.pre = await reconcileResources(context, 'pre')
    result.uncertain.tasks = result.pre.newTaskIds.filter((taskId) => !context.tracked.tasks.has(taskId)
      || !context.tracked.taskOrigins.has(taskId))
    result.uncertain.jobs = result.pre.uncertainJobs
    result.uncertain.parents = result.pre.uncertainParents
    result.uncertain.missingPreExisting = result.pre.missingPreExisting
    for (const taskId of result.uncertain.tasks) {
      unsafeParentTaskIds.add(taskId)
      fail('uncertain-new-task-not-deleted', { taskId })
    }
    for (const item of result.uncertain.jobs) {
      unsafeParentTaskIds.add(item.taskId)
      fail('uncertain-new-job-not-cancelled', item)
    }
    for (const taskId of result.uncertain.parents) {
      unsafeParentTaskIds.add(taskId)
      fail('parent-jobs-unproven-not-deleted', { taskId })
    }
    for (const taskId of result.pre.missingPreExisting) fail('pre-existing-task-missing', { taskId })
  } catch (error) {
    fail('cleanup-reconcile', { error: safeText(error?.message ?? error) })
    result.status = 'INCOMPLETE'
    await context.evidence.writeJson('cleanup.json', result)
    return result
  }

  const parentByJob = new Map()
  for (const parent of result.pre.parentJobs) {
    for (const jobId of parent.newJobIds ?? []) parentByJob.set(jobId, parent.taskId)
  }
  for (const jobId of [...context.tracked.jobs].reverse()) {
    const entry = {
      jobId,
      taskId: parentByJob.get(jobId) ?? null,
      origin: context.tracked.jobOrigins.get(jobId) ?? null,
      cancelStatus: null,
      postHttpStatus: null,
      postStatus: null
    }
    result.jobs.push(entry)
    if (!entry.origin) {
      unsafeParentTaskIds.add(entry.taskId)
      fail('job-ownership-unknown-not-cancelled', { jobId })
      continue
    }
    try {
      let response = await context.api.request('cleanup.job.get.pre', `/api/jobs/${jobId}`)
      if (response.status === 404) {
        entry.postHttpStatus = 404
        entry.postStatus = 'ABSENT'
        continue
      }
      if (response.status !== 200) {
        unsafeParentTaskIds.add(entry.taskId)
        fail('job-pre-cleanup-read', { jobId, httpStatus: response.status })
        continue
      }
      const job = dataOf(response)
      if (!['COMPLETED', 'FAILED', 'CANCELLED'].includes(job?.status)) {
        const cancelled = await context.api.request('cleanup.job.cancel', `/api/jobs/${jobId}`, { method: 'DELETE' })
        entry.cancelStatus = cancelled.status
        if (!cancelled.ok && cancelled.status !== 409) fail('job-cancel', { jobId, httpStatus: cancelled.status })
      }
      for (let attempt = 0; attempt < 20; attempt += 1) {
        response = await context.api.request('cleanup.job.get.post', `/api/jobs/${jobId}`)
        entry.postHttpStatus = response.status
        if (response.status === 404) {
          entry.postStatus = 'ABSENT'
          break
        }
        if (response.status !== 200) {
          fail('job-post-cleanup-read', { jobId, httpStatus: response.status })
          break
        }
        entry.postStatus = dataOf(response)?.status ?? null
        if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(entry.postStatus)) break
        await new Promise((resolve) => setTimeout(resolve, context.config.pollMs))
      }
      if (!['COMPLETED', 'FAILED', 'CANCELLED', 'ABSENT'].includes(entry.postStatus)) {
        unsafeParentTaskIds.add(entry.taskId)
        fail('job-post-state-not-terminal', { jobId, status: entry.postStatus })
      }
    } catch (error) {
      unsafeParentTaskIds.add(entry.taskId)
      fail('job-cleanup-exception', { jobId, error: safeText(error?.message ?? error) })
    }
  }

  for (const taskId of [...context.tracked.tasks].reverse()) {
    const origin = context.tracked.taskOrigins.get(taskId) ?? null
    const entry = {
      taskId,
      origin,
      preDelete: null,
      conditionalDelete: null,
      deleteStatus: null,
      postHttpStatus: null
    }
    result.tasks.push(entry)
    if (!origin) {
      fail('task-ownership-unknown-not-deleted', { taskId })
      continue
    }
    const parentEvidence = result.pre.parentJobs.find((parent) => parent.taskId === taskId)
    const jobsSafe = parentEvidence && (parentEvidence.absent || (parentEvidence.newJobIds ?? []).every((jobId) => {
      const job = result.jobs.find((item) => item.jobId === jobId)
      return job?.origin && ['COMPLETED', 'FAILED', 'CANCELLED', 'ABSENT'].includes(job.postStatus)
    }))
    if (unsafeParentTaskIds.has(taskId) || !jobsSafe) {
      unsafeParentTaskIds.add(taskId)
      fail('task-parent-jobs-unsafe-not-deleted', { taskId })
      continue
    }
    try {
      entry.preDelete = await verifyParentBeforeDelete(context, parentEvidence, taskId)
      if (!entry.preDelete.safe) {
        unsafeParentTaskIds.add(taskId)
        fail('task-pre-delete-jobs-changed-not-deleted', { taskId, evidence: entry.preDelete })
        continue
      }
      const expectedJobIds = [...(parentEvidence.expectedJobIds ?? [])].sort((left, right) => left - right)
      entry.conditionalDelete = {
        expectedJobIds,
        url: null,
        conflictCode: null,
        forcedRetry: false,
        legacyFallback: false
      }
      let deleted = { status: 404, ok: false, body: null }
      if (parentEvidence.absent) {
        entry.conditionalDelete.skippedReason = 'parent already absent; no DELETE request needed'
      } else {
        const query = new URLSearchParams()
        if (expectedJobIds.length === 0) query.append('expectedJobIds', '')
        else for (const jobId of expectedJobIds) query.append('expectedJobIds', String(jobId))
        const deletePath = `/api/tasks/${taskId}?${query}`
        entry.conditionalDelete.url = `${context.api.base}${deletePath}`
        entry.conditionalDelete.explicitEmptySet = expectedJobIds.length === 0
        deleted = await context.api.request('cleanup.task.delete.conditional', deletePath, { method: 'DELETE' })
      }
      entry.deleteStatus = deleted.status
      if (deleted.status === 409) {
        entry.conditionalDelete.conflictCode = deleted.body?.code ?? deleted.body?.data?.code ?? null
        fail('task-conditional-delete-conflict', {
          taskId,
          httpStatus: 409,
          code: entry.conditionalDelete.conflictCode,
          expectedJobIds,
          forcedRetry: false
        })
        continue
      }
      if (!deleted.ok && deleted.status !== 404) {
        fail('task-delete', { taskId, httpStatus: deleted.status, expectedJobIds, forcedRetry: false })
        continue
      }
      const post = await context.api.request('cleanup.task.get.post', `/api/tasks/${taskId}`)
      entry.postHttpStatus = post.status
      if (post.status !== 404) fail('task-post-state-not-absent', { taskId, httpStatus: post.status })
    } catch (error) {
      fail('task-cleanup-exception', { taskId, error: safeText(error?.message ?? error) })
    }
  }

  try {
    result.post = await reconcileResources(context, 'post')
    result.uncertain.postTasks = result.post.newTaskIds.filter((taskId) => !context.tracked.tasks.has(taskId)
      || !context.tracked.taskOrigins.has(taskId))
    result.uncertain.postJobs = result.post.uncertainJobs
    result.uncertain.postParents = result.post.uncertainParents
    for (const taskId of result.uncertain.postTasks) fail('post-uncertain-new-task', { taskId })
    for (const item of result.uncertain.postJobs) fail('post-uncertain-new-job', item)
    for (const taskId of result.uncertain.postParents) fail('post-parent-jobs-unproven', { taskId })
    const remainingOwned = result.post.newTaskIds.filter((taskId) => context.tracked.taskOrigins.has(taskId))
    for (const taskId of remainingOwned) fail('task-remains-after-cleanup', { taskId })
    for (const taskId of result.post.missingPreExisting) fail('pre-existing-task-missing-post', { taskId })
    result.post.remainingOwned = remainingOwned
  } catch (error) {
    fail('cleanup-post-reconcile', { error: safeText(error?.message ?? error) })
  }
  result.status = result.failures.length === 0 ? 'COMPLETE' : 'INCOMPLETE'
  await context.evidence.writeJson('cleanup.json', result)
  return result
}

async function collectBackendLog(context) {
  if (!context.config.backendLogFile) return { status: 'NOT_RUN', reason: 'BACKEND_LOG_FILE not provided' }
  const text = await readFile(context.config.backendLogFile, 'utf8')
  const needles = [context.runId, context.sessionId, context.targetTaskId, context.targetJobId]
    .filter((value) => value !== null && value !== undefined).map(String)
  const lines = text.split(/\r?\n/).filter((line) => needles.some((needle) => line.includes(needle)))
  const bounded = lines.slice(-2000).join('\n')
  await writeFile(path.join(context.evidence.directory, 'backend-log-excerpt.log'), `${bounded}\n`, 'utf8')
  return {
    status: lines.length === 0 ? 'NOT_RUN' : 'COLLECTED',
    source: context.config.backendLogFile,
    matchedLines: lines.length,
    retainedLines: Math.min(lines.length, 2000),
    analysis: 'NOT_RUN; C-126 requires manual comparison with business outcome, Redis and database evidence'
  }
}

function loadConfig() {
  const scenario = (process.env.SCENARIO ?? '').trim().toUpperCase()
  if (!knownScenarios.has(scenario)) throw new Error(`SCENARIO must select exactly one of ${[...knownScenarios].join(', ')}`)
  if (process.env.ROUNDS && process.env.ROUNDS !== '1') throw new Error('ROUNDS is not supported; invoke once per independent round')
  const baseInput = process.env.BASE_URL?.trim()
  if (!baseInput) throw new Error('BASE_URL must be set explicitly')
  const parsedBase = new URL(baseInput)
  if (!['http:', 'https:'].includes(parsedBase.protocol) || parsedBase.username || parsedBase.password || parsedBase.search || parsedBase.hash) {
    throw new Error('BASE_URL must be an absolute HTTP(S) URL without credentials, query or fragment')
  }
  if (process.env.EVAL_ISOLATED_ENV !== 'true') throw new Error('EVAL_ISOLATED_ENV=true is required')
  const localHosts = new Set(['localhost', '127.0.0.1', '::1', '[::1]'])
  if (!localHosts.has(parsedBase.hostname) && process.env.EVAL_CONFIRM_REMOTE !== 'true') {
    throw new Error('EVAL_CONFIRM_REMOTE=true is required for a non-local BASE_URL')
  }
  if (supportedScenarios.has(scenario) && process.env.EVAL_ALLOW_WRITES !== 'true') {
    throw new Error('EVAL_ALLOW_WRITES=true is required for real HITL/task/job verification')
  }
  const tenantId = process.env.TENANT_ID?.trim()
  const userId = process.env.USER_ID?.trim()
  if (!tenantId || !userId) throw new Error('TENANT_ID and USER_ID must be set explicitly')
  if (supportedScenarios.has(scenario) && !process.env.EXECUTOR_IDENTITY?.trim()) {
    throw new Error('EXECUTOR_IDENTITY must identify the actual executing model')
  }
  const conditionKey = (process.env.CONDITION_VALUE ?? 'empty').trim()
  if (!Object.prototype.hasOwnProperty.call(conditionValues, conditionKey)) {
    throw new Error(`CONDITION_VALUE must be one of ${Object.keys(conditionValues).join(', ')}`)
  }
  const taskPath = (process.env.TASK_PATH ?? '').trim()
  if (scenario === 'C-114' && !['existing', 'new'].includes(taskPath)) throw new Error('TASK_PATH=existing|new is required for C-114')
  const action = (process.env.ACTION ?? '').trim()
  if (scenario === 'C-115' && !['create', 'export', 'precheck', 'import', 'publish'].includes(action)) {
    throw new Error('ACTION=create|export|precheck|import|publish is required for C-115')
  }
  const decision = (process.env.DECISION ?? '').trim()
  if (scenario === 'C-115' && !['approve', 'reject'].includes(decision)) throw new Error('DECISION=approve|reject is required for C-115')
  const publishMode = (process.env.PUBLISH_MODE ?? 'MERGE').trim().toUpperCase()
  if (!['MERGE', 'REPLACE'].includes(publishMode)) throw new Error('PUBLISH_MODE must be MERGE or REPLACE')
  return {
    scenario,
    base: parsedBase.toString().replace(/\/$/, ''),
    tenantId,
    userId,
    outDir: requiredOutsidePath('OUT_DIR'),
    backendLogFile: requiredOutsidePath('BACKEND_LOG_FILE', true),
    fixtureXlsx: requiredOutsidePath('FIXTURE_XLSX', true),
    attachmentFile: requiredOutsidePath('ATTACHMENT_FILE', true),
    timeoutMs: integerEnv('TIMEOUT_MS', 45_000, 1_000),
    runTimeoutMs: integerEnv('RUN_TIMEOUT_MS', 600_000, 10_000),
    jobTimeoutMs: integerEnv('JOB_TIMEOUT_MS', 180_000, 1_000),
    pollMs: integerEnv('POLL_MS', 500, 100),
    rejectObserveMs: integerEnv('REJECT_OBSERVE_MS', 2_000, 0),
    conditionKey,
    conditionValue: conditionValues[conditionKey],
    taskPath,
    action,
    decision,
    publishMode,
    executorIdentity: process.env.EXECUTOR_IDENTITY?.trim() || 'UNSPECIFIED'
  }
}

const config = loadConfig()
const runDirectory = path.join(config.outDir, 'one-turn-interaction', config.scenario,
  `${new Date().toISOString().replace(/[:.]/g, '-')}-${randomUUID()}`)
const evidence = new Evidence(runDirectory)
await evidence.init()
const checks = new Checks()
const context = {
  config,
  evidence,
  checks,
  api: null,
  workspace: new Workspace(),
  sessionId: null,
  clientRequestId: null,
  runId: null,
  message: null,
  terminal: null,
  runDetail: null,
  history: null,
  baseline: { tasks: { total: 0, ids: [] }, targetJobs: [] },
  targetTaskId: null,
  targetJobId: null,
  fixtureTaskId: null,
  decisions: [],
  attachments: [],
  downloads: [],
  tracked: {
    tasks: new Set(),
    jobs: new Set(),
    taskOrigins: new Map(),
    jobOrigins: new Map(),
    archiveStreams: []
  },
  frames: [],
  frameCount: 0,
  runTimeoutMs: config.runTimeoutMs,
  expectedFile: config.scenario === 'C-112' || (config.scenario === 'C-113' && config.conditionKey === 'cny') ? 'CNY' : 'FULL'
}
context.api = new ApiClient(config.base, config.tenantId, config.userId, config.timeoutMs, evidence, context.tracked)
const originalFrame = evidence.frame.bind(evidence)
evidence.frame = async (channel, frame) => {
  if (!context.runId && frame.runId) context.runId = frame.runId
  if (frame.type === 'done' || frame.type === 'error') context.terminal = frame
  context.frames.push({ channel, frame })
  await originalFrame(channel, frame)
}

let fatal = null
let notRun = supportedScenarios.has(config.scenario) ? null : { code: 'API_CANNOT_PROVE', reason: notRunReasons[config.scenario] }
let cleanupResult = { status: 'NOT_REQUIRED', failures: [] }
let backendLog = { status: 'NOT_RUN', reason: 'scenario did not execute' }
const startedAt = new Date().toISOString()
try {
  await evidence.writeJson('input.json', {
    scenario: config.scenario,
    conditionKey: config.scenario === 'C-113' ? config.conditionKey : undefined,
    conditionValue: config.scenario === 'C-113' ? config.conditionValue : undefined,
    taskPath: config.scenario === 'C-114' ? config.taskPath : undefined,
    action: config.scenario === 'C-115' ? config.action : undefined,
    decision: config.scenario === 'C-115' ? config.decision : undefined,
    publishMode: config.scenario === 'C-115' && config.action === 'publish' ? config.publishMode : undefined,
    base: config.base,
    tenantId: config.tenantId,
    userId: config.userId,
    isolationAsserted: true,
    writesAllowed: supportedScenarios.has(config.scenario),
    codeHead: codeHead(),
    executorIdentity: config.executorIdentity,
    startedAt
  })
  if (!notRun) {
    context.sessionId = `one-turn-${config.scenario.toLowerCase()}-${randomUUID()}`
    context.clientRequestId = `one-turn-${randomUUID()}`
    await runSupportedScenario(context)
  } else {
    await evidence.writeJson('not-run.json', notRun)
  }
} catch (error) {
  if (error instanceof ScenarioNotRun) notRun = { code: error.code, reason: error.message }
  else fatal = { message: safeText(error?.message ?? error), type: error?.constructor?.name ?? 'Error' }
  if (context.runId && !context.history) {
    try { await collectRunEvidence(context) } catch (collectionError) {
      await evidence.event('final_collection_error', { error: safeText(collectionError?.message ?? collectionError) })
    }
  }
}

try {
  cleanupResult = await cleanup(context)
} catch (error) {
  cleanupResult = { status: 'INCOMPLETE', failures: [{ error: safeText(error?.message ?? error) }] }
  await evidence.writeJson('cleanup.json', cleanupResult)
}
if (supportedScenarios.has(config.scenario)) {
  try { backendLog = await collectBackendLog(context) } catch (error) {
    backendLog = { status: 'NOT_RUN', reason: safeText(error?.message ?? error) }
  }
}
const derivedChecks = ['C-111', 'C-112', 'C-113', 'C-114'].includes(config.scenario) && context.runId
  ? { 'C-124': await derivedC124(context, fatal, notRun, cleanupResult.status) }
  : { 'C-124': { status: 'NOT_RUN', reason: 'requires an executed C-111～C-114 run' } }
const status = cleanupResult.status === 'INCOMPLETE' || fatal || checks.failures.length > 0
  ? 'FAIL' : notRun ? 'NOT_RUN' : 'PASS'
const summary = {
  schemaVersion: 1,
  scenario: config.scenario,
  status,
  statusScope: 'PUBLIC_API_SSE_ONLY; not a browser or full C-111～C-126 acceptance verdict',
  startedAt,
  finishedAt: new Date().toISOString(),
  codeHead: codeHead(),
  executorIdentity: config.executorIdentity,
  evidenceDirectory: runDirectory,
  sessionId: context.sessionId,
  clientRequestId: context.clientRequestId,
  runId: context.runId,
  targetTaskId: context.targetTaskId,
  targetJobId: context.targetJobId,
  notRun,
  fatal,
  assertions: checks.items,
  failedAssertions: checks.failures,
  decisions: context.decisions,
  attachments: context.attachments,
  downloads: context.downloads,
  archive: context.archiveEvidence ?? { status: 'NOT_RUN' },
  derivedChecks,
  browserVerdict: 'NOT_RUN',
  databaseRedisVerdict: 'NOT_RUN; read-only external snapshots and comparison are required',
  backendLog,
  cleanup: cleanupResult,
  externalEvidenceRequired: {
    database: 'AI data is forbidden except model configuration and user feedback; business task/job/config tables are compared only as business effects, never as AI session storage',
    redis: 'read-only actual keys, TTL, counts and post-state for transcript/run/events/pending/ledger/context/forms/attachments; 24h sliding session TTL and shorter lock/lease/fence TTL',
    logs: 'backend startup and run logs must be compared with HITL, forms, jobs and terminal outcome; HTTP 200 alone is insufficient'
  }
}
await evidence.writeJson('summary.json', summary)
await evidence.writeJson('external-evidence-requirements.json', summary.externalEvidenceRequired)
console.log(JSON.stringify({
  scenario: config.scenario,
  status,
  statusScope: summary.statusScope,
  evidenceDirectory: runDirectory,
  failedAssertions: checks.failures.length,
  cleanup: cleanupResult.status,
  notRun,
  fatal
}, null, 2))
if (cleanupResult.status === 'INCOMPLETE' || status === 'FAIL') process.exitCode = 1
else if (notRun) process.exitCode = 2
