#!/usr/bin/env node
/**
 * 独立验收：创建入口优先（generative_form 闸门）
 *
 * 单进程、单次执行；不直接写 Redis/DB/history/Pinia，只走公开 API 与独立上游桩。
 * 覆盖：
 *   - 三个创建入口页（tasks/export/import）与 EXPORT/IMPORT 指引分型
 *   - SELECT_DEFINITIONS 显式用途（静态选项 / 非标准键）
 *   - GENERAL_CLARIFY 的 definitions 数据源与 defCodes（level/conditions 不作为判据，含非配置 level 负向）
 *   - 拒绝后纯文本（不依赖用户原文正则）→ 既有一次性纠正 → create_task
 *   - 拒绝后模型仍不创建 → 如实结束（不自动创建）
 *   - 连续重复表单 → 既有 max-tool-steps 预算收敛
 *   - 非配置项缺口 / TASK_TYPE 未知 / 已有任务 / 只读页不受影响
 *
 * 用法：
 *   node scripts/verification/ai-create-entry-guard.mjs --expect=green|red [--scenario=...]
 * 环境：
 *   BACKEND_JAR  必填；BACKEND_PORT/STUB_PORT/REDIS_PORT/OUT_DIR 可选。
 */
import http from 'node:http'
import { execFileSync, spawn } from 'node:child_process'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { randomUUID } from 'node:crypto'

const args = Object.fromEntries(process.argv.slice(2).map((item) => {
  const [key, value = 'true'] = item.replace(/^--/, '').split('=')
  return [key, value]
}))
const expect = args.expect ?? 'green'
const only = args.scenario ?? null
const backendJar = process.env.BACKEND_JAR
if (!backendJar) {
  console.error('BACKEND_JAR is required')
  process.exit(2)
}
const repoRoot = path.resolve('.')
const backendPort = Number(process.env.BACKEND_PORT ?? 18662)
const stubPort = Number(process.env.STUB_PORT ?? 18652)
const redisPort = Number(process.env.REDIS_PORT ?? 18679)
const maxToolSteps = Number(process.env.MAX_TOOL_STEPS ?? 4)
const outDir = process.env.OUT_DIR ?? path.resolve('test-results', `ai-create-entry-guard-${expect}-${Date.now()}`)
const base = `http://127.0.0.1:${backendPort}`
const tenantId = '1'
const userId = '101'
const rejectionCode = 'FORM_CREATE_ENTRY_REQUIRED'
const legacyCode = 'FORM_SLOTS_ALREADY_KNOWN'

const runtime = {
  scenario: null,
  requests: [],
  stubServer: null,
  backend: null,
  backendLog: []
}

const createScenarios = new Set([
  'green-direct',
  'green-text-then-create',
  'select-definitions-static',
  'definitions-source-only',
  'defcodes-key',
  'export-page',
  'import-page'
])
const negativeScenarios = new Set(['non-definition', 'non-config-level', 'task-type', 'existing-task', 'readonly-page'])
const allGreenScenarios = [
  'green-direct',
  'green-text-then-create',
  'select-definitions-static',
  'definitions-source-only',
  'defcodes-key',
  'export-page',
  'import-page',
  'non-definition',
  'non-config-level',
  'task-type',
  'existing-task',
  'readonly-page',
  'reject-then-text-no-create',
  'repeat-form',
  'mixed-tools'
]

function sseChunk(payload) {
  return `data: ${JSON.stringify(payload)}\n\n`
}

function chatDelta(content) {
  return sseChunk({
    id: 'stub', object: 'chat.completion.chunk', created: Date.now(), model: 'deepseek-flash',
    choices: [{ index: 0, delta: { content }, logprobs: null, finish_reason: null }]
  })
}

function toolStart(callId, name, toolIndex = 0) {
  return sseChunk({
    id: 'stub', object: 'chat.completion.chunk', created: Date.now(), model: 'deepseek-flash',
    choices: [{
      index: 0,
      delta: {
        role: 'assistant',
        tool_calls: [{ index: toolIndex, id: callId, type: 'function', function: { name, arguments: '' } }]
      },
      logprobs: null,
      finish_reason: null
    }]
  })
}

function toolArguments(chunk, toolIndex = 0) {
  return sseChunk({
    id: 'stub', object: 'chat.completion.chunk', created: Date.now(), model: 'deepseek-flash',
    choices: [{
      index: 0,
      delta: { tool_calls: [{ index: toolIndex, function: { arguments: chunk } }] },
      logprobs: null,
      finish_reason: null
    }]
  })
}

function finish(reason) {
  return sseChunk({
    id: 'stub', object: 'chat.completion.chunk', created: Date.now(), model: 'deepseek-flash',
    choices: [{ index: 0, delta: {}, logprobs: null, finish_reason: reason }],
    usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 }
  })
}

const DONE = 'data: [DONE]\n\n'

function definitionForm() {
  return {
    form: {
      purpose: 'GENERAL_CLARIFY',
      scenario: 'CLARIFY',
      title: '创建导出任务',
      fields: [
        {
          key: 'defCodes',
          label: '要导出的配置项（可多选，留空则由向导中选择）',
          type: 'multi_select',
          optionsFrom: { dependsOn: 'level', sourceId: 'definitions' },
          required: false
        },
        {
          key: 'level',
          label: '配置层级',
          type: 'enum',
          options: [
            { value: 'GLOBAL', label: '全局' },
            { value: 'REGION', label: '地区' },
            { value: 'PROJECT', label: '项目' }
          ],
          required: false
        },
        {
          key: 'conditions',
          label: '筛选条件说明（可选，留空表示不设筛选）',
          type: 'text',
          variant: 'longText',
          required: false
        }
      ]
    }
  }
}

function staticSelectForm() {
  return {
    form: {
      purpose: 'SELECT_DEFINITIONS',
      scenario: 'CLARIFY',
      title: '选择配置项',
      fields: [{
        key: 'defs',
        label: '配置项',
        type: 'multi_select',
        options: [{ value: 'CURRENCY', label: '货币字典' }],
        required: false
      }]
    }
  }
}

function definitionsSourceForm() {
  return {
    form: {
      purpose: 'GENERAL_CLARIFY',
      scenario: 'CLARIFY',
      title: '选择配置项',
      fields: [
        {
          key: 'defs',
          label: '配置项',
          type: 'multi_select',
          optionsFrom: { dependsOn: 'level', sourceId: 'definitions' },
          required: false
        },
        {
          key: 'level',
          label: '配置层级',
          type: 'enum',
          options: [
            { value: 'GLOBAL', label: '全局' },
            { value: 'REGION', label: '地区' },
            { value: 'PROJECT', label: '项目' }
          ],
          required: false
        }
      ]
    }
  }
}

function nonConfigLevelForm() {
  return {
    form: {
      purpose: 'GENERAL_CLARIFY',
      scenario: 'CLARIFY',
      title: '补充熟练程度',
      fields: [{
        key: 'level',
        label: '熟练程度',
        type: 'enum',
        options: [{ value: 'BEGINNER', label: '入门' }, { value: 'EXPERT', label: '专家' }],
        required: false
      }]
    }
  }
}

function defcodesKeyForm() {
  return {
    form: {
      purpose: 'GENERAL_CLARIFY',
      scenario: 'CLARIFY',
      title: '补充配置项',
      fields: [{ key: 'defCodes', label: '配置项编码', type: 'text', required: false }]
    }
  }
}

function nonDefinitionForm() {
  return {
    form: {
      purpose: 'GENERAL_CLARIFY',
      scenario: 'CLARIFY',
      title: '补充创建信息',
      fields: [{ key: 'note', label: '补充说明（可选）', type: 'text', required: false }]
    }
  }
}

function taskTypeForm() {
  return {
    form: {
      purpose: 'TASK_TYPE',
      scenario: 'CLARIFY',
      title: '选择任务类型',
      fields: [{
        key: 'type',
        label: '任务类型',
        type: 'enum',
        options: [{ value: 'EXPORT', label: '导出' }, { value: 'IMPORT', label: '导入' }],
        required: true
      }]
    }
  }
}

function messagesOf(body) {
  return Array.isArray(body?.messages) ? body.messages : []
}

function toolTexts(body) {
  return messagesOf(body)
    .filter((message) => message?.role === 'tool')
    .map((message) => String(message?.content ?? ''))
}

function userTexts(body) {
  return messagesOf(body)
    .filter((message) => message?.role === 'user')
    .map((message) => String(message?.content ?? ''))
}

function hasRejection(body) {
  return toolTexts(body).some((text) => text.includes(rejectionCode))
}

function hasCorrectionPrompt(body) {
  return userTexts(body).some((text) => /要求新建任务|请调用一次 create_task/.test(text))
}

function hasStepWrapUp(body) {
  return userTexts(body).some((text) => text.includes('工具调用步数上限'))
}

function scenarioUserText(scenario) {
  if (scenario === 'green-text-then-create') {
    return '导出这些配置'
  }
  return '创建导出任务'
}

function createTaskArgs(scenario) {
  if (scenario === 'import-page') {
    return { type: 'IMPORT' }
  }
  return { type: 'EXPORT', conditions: {} }
}

function scenarioForm(scenario) {
  if (scenario === 'select-definitions-static') {
    return staticSelectForm()
  }
  if (scenario === 'definitions-source-only') {
    return definitionsSourceForm()
  }

  if (scenario === 'defcodes-key') {
    return defcodesKeyForm()
  }
  if (scenario === 'non-definition') {
    return nonDefinitionForm()
  }
  if (scenario === 'non-config-level') {
    return nonConfigLevelForm()
  }
  if (scenario === 'task-type') {
    return taskTypeForm()
  }
  return definitionForm()
}

function scriptedResponse(scenario, index, body) {
  if (hasStepWrapUp(body)) {
    return { text: '本轮已停止：创建前不收集配置项，请先创建任务。', tool: null }
  }
  const rejected = hasRejection(body)
  const corrected = hasCorrectionPrompt(body)
  if (scenario === 'green-direct' || scenario === 'select-definitions-static'
    || scenario === 'definitions-source-only'
    || scenario === 'defcodes-key' || scenario === 'export-page' || scenario === 'import-page'
    || scenario === 'red') {
    if (index === 1) {
      return { text: '我需要先确认一些信息才能创建。', tool: { name: 'generative_form', args: scenarioForm(scenario) } }
    }
    if (rejected) {
      return { tool: { name: 'create_task', args: createTaskArgs(scenario) } }
    }
    return { text: '未按预期收到闸门拒绝。', tool: null }
  }
  if (scenario === 'green-text-then-create') {
    if (index === 1) {
      return { text: '我需要先确认一些信息才能创建。', tool: { name: 'generative_form', args: definitionForm() } }
    }
    if (corrected) {
      return { tool: { name: 'create_task', args: { type: 'EXPORT', conditions: {} } } }
    }
    if (rejected) {
      return { text: '请告诉我要导出的配置项和筛选条件。', tool: null }
    }
    return { text: '未按预期收到纠正。', tool: null }
  }
  if (scenario === 'reject-then-text-no-create') {
    if (index === 1) {
      return { text: '我需要先确认一些信息才能创建。', tool: { name: 'generative_form', args: definitionForm() } }
    }
    return { text: '请告诉我要导出的配置项和筛选条件。', tool: null }
  }
  if (scenario === 'repeat-form') {
    return { text: '请选择配置项。', tool: { name: 'generative_form', args: definitionForm() } }
  }
  if (scenario === 'mixed-tools') {
    if (index === 1) {
      return {
        text: '我先确认并查询配置目录。',
        tools: [
          { name: 'generative_form', args: definitionForm() },
          { name: 'list_config_defs', args: {} }
        ]
      }
    }
    return { text: '查询完成。', tool: null }
  }
  if (scenario === 'non-definition') {
    return { text: '请补充说明。', tool: { name: 'generative_form', args: nonDefinitionForm() } }
  }
  if (scenario === 'non-config-level') {
    return { text: '请选择熟练程度。', tool: { name: 'generative_form', args: nonConfigLevelForm() } }
  }
  if (scenario === 'task-type') {
    return { text: '请选择任务类型。', tool: { name: 'generative_form', args: taskTypeForm() } }
  }
  if (scenario === 'existing-task' || scenario === 'readonly-page') {
    return { text: '请选择要查看的配置项。', tool: { name: 'generative_form', args: definitionForm() } }
  }
  return { text: 'unscripted scenario', tool: null }
}

function startStub() {
  const server = http.createServer((request, response) => {
    if (request.method === 'GET' && request.url.startsWith('/models')) {
      response.writeHead(200, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ object: 'list', data: [{ id: 'deepseek-flash', object: 'model' }] }))
      return
    }
    if (request.method !== 'POST' || !request.url.startsWith('/chat/completions')) {
      response.writeHead(404, { 'Content-Type': 'application/json' })
      response.end('{}')
      return
    }
    let raw = ''
    request.on('data', (chunk) => { raw += chunk })
    request.on('end', () => {
      let body = {}
      try { body = JSON.parse(raw) } catch { body = {} }
      const index = runtime.requests.length + 1
      const scripted = scriptedResponse(runtime.scenario, index, body)
      runtime.requests.push({
        index,
        scenario: runtime.scenario,
        messageCount: messagesOf(body).length,
        rejected: hasRejection(body),
        correctionPrompt: hasCorrectionPrompt(body),
        stepWrapUp: hasStepWrapUp(body),
        tool: scripted.tools?.[0]?.name ?? scripted.tool?.name ?? null,
        tools: (scripted.tools ?? (scripted.tool ? [scripted.tool] : [])).map((item) => item.name),
        toolArgs: scripted.tools?.[0]?.args ?? scripted.tool?.args ?? null,
        text: scripted.text ?? null,
        toolTexts: toolTexts(body)
      })
      response.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive'
      })
      let stream = ''
      if (scripted.text) {
        stream += chatDelta(scripted.text)
      }
      const tools = scripted.tools ?? (scripted.tool ? [scripted.tool] : [])
      if (tools.length > 0) {
        tools.forEach((item, toolIndex) => {
          const callId = `call_00_stub_${index}_${toolIndex}_${item.name}`
          stream += toolStart(callId, item.name, toolIndex)
          const argsText = JSON.stringify(item.args)
          for (let offset = 0; offset < argsText.length; offset += 24) {
            stream += toolArguments(argsText.slice(offset, offset + 24), toolIndex)
          }
        })
        stream += finish('tool_calls')
      } else {
        stream += finish('stop')
      }
      stream += DONE
      response.end(stream)
    })
  })
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(stubPort, '127.0.0.1', () => resolve(server))
  })
}

function startBackend() {
  const dbPath = path.join(outDir, 'db', 'verify').replace(/\\/g, '/')
  const child = spawn(process.env.JAVA_BIN ?? 'java', [
    '-jar', backendJar,
    `--server.port=${backendPort}`,
    `--spring.datasource.url=jdbc:h2:file:${dbPath};DB_CLOSE_DELAY=-1`,
    `--app.ai.resilience.max-tool-steps=${maxToolSteps}`
  ], {
    env: {
      ...process.env,
      AI_API_KEY: 'stub-key',
      AI_BASE_URL: `http://127.0.0.1:${stubPort}`,
      AI_MODEL: 'deepseek-flash',
      SPRING_DATA_REDIS_PORT: String(redisPort)
    },
    windowsHide: true
  })
  const collect = (chunk) => {
    runtime.backendLog.push(chunk.toString())
    if (runtime.backendLog.length > 400) {
      runtime.backendLog.shift()
    }
  }
  child.stdout.on('data', collect)
  child.stderr.on('data', collect)
  return child
}

async function waitForBackend(timeoutMs = 90000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${base}/api/ai/providers`, {
        headers: { 'X-Tenant-Id': tenantId, 'X-User-Id': userId },
        signal: AbortSignal.timeout(2000)
      })
      if (response.status === 200) {
        return
      }
    } catch {
      // not ready yet
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`backend did not become ready on ${base}`)
}

async function apiGet(urlPath) {
  const response = await fetch(`${base}${urlPath}`, {
    headers: { 'X-Tenant-Id': tenantId, 'X-User-Id': userId },
    signal: AbortSignal.timeout(10000)
  })
  const text = await response.text()
  let body = null
  try { body = text ? JSON.parse(text) : null } catch { body = text }
  return { status: response.status, body }
}

async function taskIds() {
  const response = await apiGet('/api/tasks?page=0&size=50')
  const content = response.body?.data?.content ?? []
  return content.map((item) => Number(item.task?.id)).filter((id) => Number.isFinite(id))
}

async function createTask(type) {
  const response = await fetch(`${base}/api/tasks`, {
    method: 'POST',
    headers: { 'X-Tenant-Id': tenantId, 'X-User-Id': userId, 'Content-Type': 'application/json' },
    body: JSON.stringify({ type }),
    signal: AbortSignal.timeout(10000)
  })
  const body = await response.json()
  return Number(body?.data?.id)
}

function contextFor(scenario) {
  if (scenario === 'existing-task') {
    return {
      page: 'export',
      taskType: 'EXPORT',
      step: 'SELECT_DEFS',
      taskId: runtime.fixtureTaskId,
      taskStatus: 'ACTIVE',
      extra: { pageId: 'export', selectedDefs: [], selectionSynced: true }
    }
  }
  if (scenario === 'readonly-page') {
    return { page: 'definitions', extra: { pageId: 'definitions', selectedDefs: [], selectionSynced: false } }
  }
  if (scenario === 'export-page') {
    return { page: 'export', extra: { pageId: 'export', selectedDefs: [], selectionSynced: false } }
  }
  if (scenario === 'import-page') {
    return { page: 'import', extra: { pageId: 'import', selectedDefs: [], selectionSynced: false } }
  }
  return { page: 'tasks', extra: { pageId: 'tasks', selectedDefs: [], selectionSynced: false } }
}

async function runScenario(scenario) {
  runtime.scenario = scenario
  runtime.requests = []
  const before = await taskIds()
  const sessionId = `guard-${scenario}-${randomUUID()}`
  const frames = []
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 45000)
  const response = await fetch(`${base}/api/ai/chat`, {
    method: 'POST',
    headers: {
      'X-Tenant-Id': tenantId,
      'X-User-Id': userId,
      'Content-Type': 'application/json',
      Accept: 'text/event-stream'
    },
    body: JSON.stringify({
      sessionId,
      message: scenarioUserText(scenario),
      context: contextFor(scenario),
      clientRequestId: `guard-${randomUUID()}`
    }),
    signal: controller.signal
  })
  if (!response.ok) {
    const text = await response.text()
    clearTimeout(timeout)
    throw new Error(`chat HTTP ${response.status}: ${text.slice(0, 500)}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffered = ''
  let lastDataAt = Date.now()
  try {
    while (Date.now() - lastDataAt < 8000) {
      const read = await Promise.race([
        reader.read(),
        new Promise((resolve) => setTimeout(() => resolve({ timeout: true }), 8000))
      ])
      if (read.timeout) {
        break
      }
      if (read.done) {
        buffered += decoder.decode()
        break
      }
      buffered += decoder.decode(read.value, { stream: true })
      lastDataAt = Date.now()
      let boundary = buffered.indexOf('\n\n')
      while (boundary >= 0) {
        const block = buffered.slice(0, boundary)
        buffered = buffered.slice(boundary + 2)
        const data = block.split(/\r?\n/).filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trimStart()).join('\n')
        if (data && data !== '[DONE]') {
          try {
            frames.push(JSON.parse(data))
          } catch {
            frames.push({ type: 'invalid', raw: data.slice(0, 500) })
          }
        }
        boundary = buffered.indexOf('\n\n')
      }
    }
  } finally {
    clearTimeout(timeout)
    controller.abort()
  }
  await new Promise((resolve) => setTimeout(resolve, 1000))
  const after = await taskIds()
  const history = await historyOf(sessionId)
  return {
    scenario,
    sessionId,
    frames,
    requests: runtime.requests,
    before,
    after,
    newTasks: after.filter((id) => !before.includes(id)),
    history
  }
}

async function historyOf(sessionId) {
  const response = await apiGet(`/api/ai/history/${encodeURIComponent(sessionId)}?latest=50`)
  return response.body ?? null
}

function summarize(result) {
  const frames = result.frames
  const frontendRequests = frames.filter((frame) => frame.type === 'frontend_tool_request')
  const confirmRequests = frames.filter((frame) => frame.type === 'confirm_request')
  const toolFrames = frames.filter((frame) => ['tool_start', 'tool_result', 'frontend_tool_request', 'frontend_tool_result']
    .includes(frame.type))
  const formFrames = toolFrames.filter((frame) => frame.name === 'generative_form')
  const terminals = frames.filter((frame) => ['done', 'error', 'suspended'].includes(frame.type))
  const rejectionTexts = result.requests
    .flatMap((request) => request.toolTexts ?? [])
    .filter((text) => text.includes(rejectionCode))
  return {
    scenario: result.scenario,
    sessionId: result.sessionId,
    frameTypes: frames.map((frame) => frame.type),
    formFrames: formFrames.map((frame) => ({ type: frame.type, name: frame.name, toolCallId: frame.toolCallId })),
    toolFrames: toolFrames.map((frame) => ({ type: frame.type, name: frame.name, toolCallId: frame.toolCallId })),
    frontendToolRequests: frontendRequests.map((frame) => ({ name: frame.name, toolCallId: frame.toolCallId })),
    confirmRequests: confirmRequests.map((frame) => ({
      name: frame.name,
      toolCallId: frame.toolCallId,
      args: parseArgs(frame.args)
    })),
    terminal: terminals.length === 0 ? null
      : { type: terminals[terminals.length - 1].type, status: terminals[terminals.length - 1].status ?? null },
    text: frames.filter((frame) => frame.type === 'delta').map((frame) => frame.text ?? '').join(''),
    requests: result.requests.map((request) => ({
      index: request.index,
      rejected: request.rejected,
      correctionPrompt: request.correctionPrompt,
      stepWrapUp: request.stepWrapUp,
      tool: request.tool,
      toolArgs: request.toolArgs
    })),
    requestCount: result.requests.length,
    newTasks: result.newTasks,
    rejectionTexts,
    history: result.history
  }
}

function parseArgs(raw) {
  if (raw === null || raw === undefined) {
    return null
  }
  if (typeof raw !== 'string') {
    return raw
  }
  try { return JSON.parse(raw) } catch { return raw }
}

function assert(condition, message, details) {
  if (!condition) {
    const error = new Error(message)
    error.details = details
    throw error
  }
}

function assertNoFormUserFrames(summary) {
  assert(summary.formFrames.length === 0,
    `${summary.scenario}: no generative_form user frame (tool_start/tool_result/frontend_*) may reach the user`, summary)
  assert(summary.frontendToolRequests.length === 0,
    `${summary.scenario}: no frontend_tool_request may reach the user`, summary)
}

function assertHistoryNoPhantom(summary) {
  const messages = summary.history?.data?.messages ?? []
  const formCalls = []
  for (const message of messages) {
    for (const call of message.toolCalls ?? []) {
      if (call.name !== 'generative_form') {
        continue
      }
      const state = call.id ? message.toolCallStates?.[call.id] : null
      formCalls.push({ id: call.id, code: state?.code ?? null })
    }
  }
  for (const call of formCalls) {
    assert(call.code === rejectionCode,
      `${summary.scenario}: historical generative_form call must carry the silent rejection code`, { call, messages: undefined })
  }
}

function assertRejectionReasons(summary, mode) {
  const texts = summary.rejectionTexts.join('\n')
  assert(texts.length > 0, `${summary.scenario}: rejection reasons must reach the model`, summary)
  assert(texts.includes('create_task'), `${summary.scenario}: reasons must point to create_task`, summary)
  assert(texts.includes(rejectionCode), `${summary.scenario}: reasons/echo must carry the new code`, summary)
  if (mode === 'EXPORT') {
    assert(texts.includes('type=EXPORT') && texts.includes('conditions={}'),
      `${summary.scenario}: EXPORT guidance must include type=EXPORT and conditions={}`, summary)
  } else if (mode === 'IMPORT') {
    assert(texts.includes('type=IMPORT'), `${summary.scenario}: IMPORT guidance must include type=IMPORT`, summary)
    assert(!texts.includes('conditions={}'), `${summary.scenario}: IMPORT guidance must not ask for conditions={}`, summary)
    assert(texts.includes('不要传 conditions'), `${summary.scenario}: IMPORT guidance must say to omit conditions`, summary)
  } else {
    assert(texts.includes('type=EXPORT') && texts.includes('type=IMPORT'),
      `${summary.scenario}: unknown type must describe both calls separately`, summary)
    assert(texts.includes('conditions={}') && texts.includes('不要传 conditions'),
      `${summary.scenario}: unknown type must keep EXPORT/IMPORT condition rules distinct`, summary)
  }
}

function assertConfirm(summary, expectedType) {
  assert(summary.confirmRequests.length === 1,
    `${summary.scenario}: exactly one create confirm expected`, summary)
  const confirm = summary.confirmRequests[0]
  assert(confirm.name === 'create_task', `${summary.scenario}: first user card must be create_task`, summary)
  const callArgs = parseArgs(confirm.args) ?? {}
  assert(callArgs.type === expectedType, `${summary.scenario}: confirm args type must be ${expectedType}`, confirm)
  if (expectedType === 'EXPORT') {
    assert(callArgs.conditions !== undefined && callArgs.conditions !== null,
      `${summary.scenario}: EXPORT create_task must pass conditions`, confirm)
    assert(Object.keys(callArgs.conditions ?? {}).length === 0,
      `${summary.scenario}: EXPORT conditions must be an empty object when undecided`, confirm)
  } else {
    assert(callArgs.conditions === undefined, `${summary.scenario}: IMPORT create_task must omit conditions`, confirm)
  }
}

function assertGreen(summary) {
  const scenario = summary.scenario
  assert(summary.terminal?.type === 'suspended', `${scenario}: run must park (suspended), got ${summary.terminal?.type}`,
    summary)
  assert(summary.newTasks.length === 0, `${scenario}: no task may be created before approval`, summary.newTasks)
  if (negativeScenarios.has(scenario)) {
    assert(summary.frontendToolRequests.some((item) => item.name === 'generative_form'),
      `${scenario}: real gap form must still be delivered`, summary)
    assert(summary.confirmRequests.length === 0, `${scenario}: no create confirm expected`, summary)
    assert(summary.requests.filter((item) => item.tool === 'create_task').length === 0,
      `${scenario}: create_task must not be called`, summary.requests)
    return
  }
  assertNoFormUserFrames(summary)
  const expectedType = scenario === 'import-page' ? 'IMPORT' : 'EXPORT'
  assertConfirm(summary, expectedType)
  assert(summary.requests.filter((item) => item.tool === 'create_task').length === 1,
    `${scenario}: create_task must be called exactly once`, summary.requests)
  const reasonMode = scenario === 'import-page' ? 'IMPORT' : (scenario === 'export-page' ? 'EXPORT' : 'UNKNOWN')
  assertRejectionReasons(summary, reasonMode)
  assertHistoryNoPhantom(summary)
  if (scenario === 'green-text-then-create') {
    assert(!/任务|作业/.test(scenarioUserText(scenario)),
      `${scenario}: user text must not match the old TaskIntent regex`, scenarioUserText(scenario))
    assert(summary.requests.some((item) => item.correctionPrompt),
      `${scenario}: existing correction channel must be used`, summary.requests)
  }
}

function assertSpecial(summary) {
  const scenario = summary.scenario
  if (scenario === 'reject-then-text-no-create') {
    assert(summary.terminal?.type === 'done', `${scenario}: must end truthfully (done), got ${summary.terminal?.type}`,
      summary)
    const corrections = summary.requests.filter((item) => item.correctionPrompt).length
    assert(corrections === 1, `${scenario}: correction must be one-shot`, summary.requests)
    assert(summary.requests.filter((item) => item.tool === 'create_task').length === 0,
      `${scenario}: must not auto-create`, summary.requests)
    assert(summary.requestCount <= 4, `${scenario}: bounded requests expected`, summary.requestCount)
    assert(summary.text.length > 0, `${scenario}: must end with a truthful text`, summary)
    assertNoFormUserFrames(summary)
    return
  }
  if (scenario === 'repeat-form') {
    assert(summary.terminal?.type === 'done', `${scenario}: bounded run must end, got ${summary.terminal?.type}`, summary)
    const stepLimited = summary.requests.some((item) => item.stepWrapUp)
    assert(stepLimited, `${scenario}: existing step budget must produce the wrap-up instruction`, summary.requests)
    assert(summary.requests.every((item) => !item.correctionPrompt),
      `${scenario}: no create_task correction after step wrap-up`, summary.requests)
    assert(summary.requests.filter((item) => item.tool === 'create_task').length === 0,
      `${scenario}: no create_task expected`, summary.requests)
    assert(summary.toolFrames.every((frame) => frame.name !== 'create_task'),
      `${scenario}: no visible skipped create_task card`, summary.toolFrames)
    assert(summary.requestCount === maxToolSteps + 1,
      `${scenario}: exactly one text turn after the step budget (no extra correction)`, summary.requestCount)
    assertNoFormUserFrames(summary)
    return
  }
  if (scenario === 'mixed-tools') {
    assert(summary.terminal?.type === 'done', `${scenario}: must end truthfully (done)`, summary)
    assertNoFormUserFrames(summary)
    assert(summary.confirmRequests.length === 0, `${scenario}: no create confirm expected`, summary)
    assert(summary.toolFrames.some((frame) => frame.name === 'list_config_defs' && frame.type === 'tool_start'),
      `${scenario}: executed sibling tool must keep its tool_start frame`, summary.toolFrames)
    assert(summary.toolFrames.some((frame) => frame.name === 'list_config_defs' && frame.type === 'tool_result'),
      `${scenario}: executed sibling tool must keep its tool_result frame`, summary.toolFrames)
    const messages = summary.history?.data?.messages ?? []
    let mixedAssistant = null
    let siblingResponse = null
    for (const message of messages) {
      const calls = message.toolCalls ?? []
      if (calls.some((call) => call.name === 'generative_form') && calls.some((call) => call.name === 'list_config_defs')) {
        mixedAssistant = message
      }
      for (const response of message.toolResponses ?? []) {
        if (response.name === 'list_config_defs') {
          siblingResponse = response
        }
      }
    }
    assert(mixedAssistant !== null, `${scenario}: history must keep the mixed assistant tool calls`, messages)
    const formCall = mixedAssistant.toolCalls.find((call) => call.name === 'generative_form')
    const formState = mixedAssistant.toolCallStates?.[formCall.id]
    assert(formState?.code === rejectionCode, `${scenario}: form call must carry the dedicated rejection code`, formState)
    assert(mixedAssistant.toolCalls.some((call) => call.name === 'list_config_defs'),
      `${scenario}: sibling tool call must be preserved`, mixedAssistant.toolCalls)
    assert(siblingResponse !== null, `${scenario}: sibling tool response must be preserved`, messages)
    return
  }
  assertGreen(summary)
}

function assertRed(summary) {
  assert(summary.frontendToolRequests.some((item) => item.name === 'generative_form'),
    'red: old code must accept the pre-create form', summary)
  assert(summary.confirmRequests.length === 0, 'red: old code must not reach create confirm', summary)
  assert(summary.requests.filter((item) => item.tool === 'create_task').length === 0,
    'red: old code must not call create_task', summary.requests)
  assert(summary.newTasks.length === 0, 'red: no task side effect', summary.newTasks)
}

async function staticFrontendFilterCheck() {
  const parts = await readFile(path.join(repoRoot, 'frontend/src/utils/parts.ts'), 'utf8')
  const apiTypes = await readFile(path.join(repoRoot, 'frontend/src/types/api.ts'), 'utf8')
  assert(parts.includes(`FORM_CREATE_ENTRY_REQUIRED = '${rejectionCode}'`),
    'frontend parts.ts must register the dedicated code')
  assert(/SYSTEM_REJECT_GENERATIVE_FORM_CODES[\s\S]{0,200}FORM_CREATE_ENTRY_REQUIRED/.test(parts.replace(
    /export const FORM_CREATE_ENTRY_REQUIRED = 'FORM_CREATE_ENTRY_REQUIRED'/, '')),
  'frontend generative_form silent set must include the dedicated code')
  assert(apiTypes.includes(`FORM_CREATE_ENTRY_REQUIRED: '${rejectionCode}'`),
    'frontend api types must include the dedicated code')
}

async function main() {
  await mkdir(outDir, { recursive: true })
  runtime.stubServer = await startStub()
  runtime.backend = startBackend()
  const report = { expect, backendJar, backendPort, stubPort, maxToolSteps, startedAt: new Date().toISOString(), scenarios: [] }
  try {
    await waitForBackend()
    await staticFrontendFilterCheck()
    const scenarios = only ? [only] : (expect === 'red' ? ['red'] : allGreenScenarios)
    if (scenarios.includes('existing-task')) {
      runtime.fixtureTaskId = await createTask('EXPORT')
      report.fixtureTaskId = runtime.fixtureTaskId
    }
    for (const scenario of scenarios) {
      const result = await runScenario(scenario)
      const summary = summarize(result)
      report.scenarios.push(summary)
      if (expect === 'green') {
        assertSpecial(summary)
      } else {
        assertRed(summary)
      }
    }
    report.verdict = 'PASS'
  } catch (error) {
    report.verdict = 'FAIL'
    report.error = error.message
    report.errorDetails = error.details ?? null
  } finally {
    await writeFile(path.join(outDir, 'report.json'), JSON.stringify(report, null, 2))
    await writeFile(path.join(outDir, 'backend.log'), runtime.backendLog.join(''))
    stopBackend()
    runtime.stubServer?.close()
  }
  console.log(JSON.stringify(report, null, 2))
  process.exit(report.verdict === 'PASS' ? 0 : 1)
}

function stopBackend() {
  const child = runtime.backend
  if (!child || child.killed) {
    return
  }
  try {
    execFileSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' })
  } catch {
    child.kill()
  }
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
