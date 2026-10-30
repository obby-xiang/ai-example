#!/usr/bin/env node
/**
 * check-d6-sync 门禁用例（零依赖，node --test 运行）。
 *
 * 逐条覆盖：
 *   ① block 面无确认阻断（d6-review.json 未随批 / reviews 为空）
 *   ② synced 必须同批更新 D6
 *   ③ not-affected 必须有具体非占位理由
 *   ④ 指纹变化（blob 变 / block 集合变）使旧确认失效
 *   ⑤ 上一次提交里的同指纹确认不得复用（必须本次新增）
 *   ⑥ d6-review.json 坏 JSON 返回 rc2
 *   ⑦ 业务文件不进入门禁
 *   ⑧ 测试与模拟设施只告警不阻断
 *   ⑨ doc / infra 不阻断
 *   ⑩ 核心路径分类自检：租户、发号、SSE、前端 store、工具、多语言
 *   ⑪ 删除 block 文件同样要求本次确认
 *
 * 单条运行（禁止批量）：
 *   node --test --test-name-pattern="<用例名>" scripts/ci/check-d6-sync.test.mjs
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'

import {
  D6_DOC_PATH,
  REVIEW_PATH,
  classify,
  computeFingerprint,
  evaluate,
  isConcreteReason,
} from './check-d6-sync.mjs'

const AI_CONTROLLER = 'backend/src/main/java/com/example/configmgr/ai/web/AiChatController.java'
const AI_STORE = 'frontend/src/stores/ai.ts'
const AI_PANEL = 'frontend/src/components/AiPanel/AiPanel.vue'
const AI_API = 'frontend/src/api/ai.ts'
const ID_GENERATOR = 'backend/src/main/java/com/example/configmgr/common/id/TimestampIdGenerator.java'
const SSE_BUDGET = 'backend/src/main/java/com/example/configmgr/common/sse/SseWriteBudget.java'
const LOCALE_ZH = 'frontend/src/locales/zh-CN.json'
const BUSINESS = 'backend/src/main/java/com/example/configmgr/task/TaskService.java'

function change(filePath, status = 'M', blob = `blob-${filePath}`) {
  return { path: filePath, status, blob }
}

function reviewJson(entries) {
  return JSON.stringify({ version: 1, reviews: entries }, null, 2)
}

function confirmation(blockChanges, patch = {}) {
  return {
    fingerprint: computeFingerprint(blockChanges),
    status: 'synced',
    reason: '同批更新 D6 §6.7 的 SSE 帧契约与工具披露表，代码与正文一致',
    reviewedAt: '2026-10-30',
    reviewer: 'tester',
    ...patch,
  }
}

function stagedWithReview(block, entries, { d6 = true, extra = [] } = {}) {
  const changes = [...block, change(REVIEW_PATH, 'A'), ...extra]
  if (d6) changes.push(change(D6_DOC_PATH))
  return { changes, reviewText: reviewJson(entries) }
}

test('核心 AI 文件未带 d6-review.json 变更时阻断', () => {
  const result = evaluate({ changes: [change(AI_CONTROLLER)] })
  assert.equal(result.exitCode, 1)
  assert.equal(result.kind, 'review-not-staged')
  assert.equal(result.block.length, 1)
  assert.match(result.fingerprint, /^sha256:[0-9a-f]{64}$/)
})

test('核心 AI 文件带空 reviews 时阻断', () => {
  const changes = [change(AI_CONTROLLER), change(REVIEW_PATH, 'A')]
  const result = evaluate({ changes, reviewText: reviewJson([]) })
  assert.equal(result.exitCode, 1)
  assert.equal(result.kind, 'no-confirmation')
  assert.equal(result.problems.length, 1)
})

test('synced 确认且同批含 D6 时通过', () => {
  const block = [change(AI_STORE)]
  const { changes, reviewText } = stagedWithReview(block, [confirmation(block)])
  const result = evaluate({ changes, reviewText })
  assert.equal(result.exitCode, 0)
  assert.equal(result.kind, 'ok')
  assert.equal(result.d6Updated, true)
  assert.equal(result.entry.status, 'synced')
})

test('synced 确认缺少同批 D6 变更时阻断', () => {
  const block = [change(AI_PANEL)]
  const { changes, reviewText } = stagedWithReview(block, [confirmation(block)], { d6: false })
  const result = evaluate({ changes, reviewText })
  assert.equal(result.exitCode, 1)
  assert.equal(result.kind, 'invalid-confirmation')
  assert.ok(result.problems.some((problem) => problem.includes('同批暂存')), result.problems.join(' / '))
})

test('not-affected 必须给出具体非占位理由', () => {
  const block = [change(ID_GENERATOR)]
  for (const reason of ['', '   ', 'n/a', '无', '待补']) {
    const { changes, reviewText } = stagedWithReview(block, [
      confirmation(block, { status: 'not-affected', reason }),
    ])
    const result = evaluate({ changes, reviewText })
    assert.equal(result.exitCode, 1, `reason=${JSON.stringify(reason)} 应阻断`)
    assert.equal(result.kind, 'invalid-confirmation')
  }

  assert.equal(isConcreteReason('n/a'), false)
  assert.equal(isConcreteReason('仅调整撞号分支日志级别，撞号语义与 D6 §6.3 描述一致'), true)

  // not-affected 不要求同批改 D6
  const entry = confirmation(block, {
    status: 'not-affected',
    reason: '仅调整撞号分支日志级别，撞号语义与 D6 §6.3 描述一致',
  })
  const result = evaluate({
    changes: [block[0], change(REVIEW_PATH, 'A')],
    reviewText: reviewJson([entry]),
  })
  assert.equal(result.exitCode, 0)
  assert.equal(result.kind, 'ok')
})

test('指纹变化使旧确认失效', () => {
  const block = [change(SSE_BUDGET, 'M', 'blob-before')]
  const entries = [confirmation(block)]
  const before = stagedWithReview(block, entries)
  const beforeResult = evaluate({ changes: before.changes, reviewText: before.reviewText })
  assert.equal(beforeResult.exitCode, 0)

  // 同一文件内容再改：blob 变 ⇒ 指纹变
  const edited = [change(SSE_BUDGET, 'M', 'blob-after')]
  const afterEdit = stagedWithReview(edited, entries)
  const editedResult = evaluate({ changes: afterEdit.changes, reviewText: afterEdit.reviewText })
  assert.equal(editedResult.exitCode, 1)
  assert.equal(editedResult.kind, 'no-confirmation')

  // 新增一个 block 文件：集合变 ⇒ 指纹变
  const plusOne = [block[0], change('frontend/src/api/sse.ts', 'A', 'blob-new')]
  const afterAdd = stagedWithReview(plusOne, entries)
  const addedResult = evaluate({ changes: afterAdd.changes, reviewText: afterAdd.reviewText })
  assert.equal(addedResult.exitCode, 1)
  assert.equal(addedResult.kind, 'no-confirmation')
  assert.notEqual(editedResult.fingerprint, beforeResult.fingerprint)
})

test('上一次提交里的同指纹确认不得复用', () => {
  const block = [change(LOCALE_ZH)]
  const entry = confirmation(block)
  const { changes, reviewText } = stagedWithReview(block, [entry])

  const reused = evaluate({ changes, reviewText, headReviewText: reviewJson([entry]) })
  assert.equal(reused.exitCode, 1)
  assert.equal(reused.kind, 'reused-confirmation')

  // 旧记录之外再新增一条（哪怕指纹相同）⇒ 通过
  const fresh = {
    ...entry,
    reason: '本次补充 AI 面板文案键，D6 §6.8 的键集与语言切换口径已同批核对',
  }
  const ok = evaluate({ changes, reviewText: reviewJson([entry, fresh]), headReviewText: reviewJson([entry]) })
  assert.equal(ok.exitCode, 0)
  assert.equal(ok.kind, 'ok')
})

test('d6-review.json 非法 JSON 返回 rc2', () => {
  const block = [change(AI_API)]
  const changes = [...block, change(REVIEW_PATH, 'A')]
  const brokenTexts = [
    '{ "reviews": [ ',
    '',
    '[]',
    '{"version":2,"reviews":[]}',
    '{"reviews":[]}',
    '{"reviews":{}}',
  ]
  for (const text of brokenTexts) {
    const result = evaluate({ changes, reviewText: text })
    assert.equal(result.exitCode, 2, `text=${JSON.stringify(text)} 应 rc2`)
    assert.equal(result.kind, 'bad-json')
  }
  // version 缺失必须单独断言：不能因为“reviews 为空”而落到 rc1
  const missingVersion = evaluate({ changes, reviewText: '{"reviews":[]}' })
  assert.equal(missingVersion.exitCode, 2)
  assert.ok(missingVersion.problems[0].includes('version'), missingVersion.problems.join(' / '))
})

test('业务文件不进入门禁', () => {
  const changes = [change(BUSINESS), change('frontend/src/views/TaskListView.vue')]
  const result = evaluate({ changes })
  assert.equal(result.exitCode, 0)
  assert.equal(result.kind, 'no-block')
  assert.equal(result.block.length, 0)
  assert.equal(result.other.length, 2)
})

test('测试与模拟设施只告警不阻断', () => {
  const changes = [
    change('backend/src/test/java/com/example/configmgr/ai/web/AiChatControllerTest.java'),
    change('backend/src/test/java/com/example/configmgr/common/id/TimestampIdGeneratorTest.java'),
    change('frontend/e2e/03-hitl.spec.ts'),
    change('frontend/src/utils/ai-identity.test.ts'),
    change('scripts/ci/stub-upstream.mjs'),
    change('scripts/ci/stub-routes.json'),
    change('scripts/verify-e2e.ps1'),
    change('scripts/b23l1-dual-instance.ps1'),
    change('backend/src/main/resources/messages_zh.properties'),
    change('frontend/src/stores/locale.ts'),
  ]
  const result = evaluate({ changes })
  assert.equal(result.exitCode, 0)
  assert.equal(result.kind, 'no-block')
  assert.equal(result.block.length, 0)
  assert.equal(result.warn.length, changes.length)
  for (const file of result.warn) assert.ok(file.label.length > 0, file.path)
})

test('doc 与 infra 变更不阻断', () => {
  const changes = [
    change(D6_DOC_PATH),
    change(REVIEW_PATH),
    change('scripts/README.md'),
    change('scripts/ci/check-d6-sync.mjs'),
    change('.github/workflows/ci.yml'),
    change('frontend/package.json'),
    change('backend/src/main/resources/application.yml'),
  ]
  const result = evaluate({ changes })
  assert.equal(result.exitCode, 0)
  assert.equal(result.kind, 'no-block')
  assert.equal(result.block.length, 0)
  assert.equal(result.doc.length, 3)
  assert.equal(result.infra.length, 4)
})

test('核心路径分类自检覆盖租户发号SSE前端store工具多语言', () => {
  const blockPaths = [
    'backend/src/main/java/com/example/configmgr/ai/tenant/RunIdentityScope.java',
    'backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java',
    'backend/src/main/java/com/example/configmgr/ai/tool/ToolRegistry.java',
    'backend/src/main/java/com/example/configmgr/ai/session/SessionStore.java',
    'backend/src/main/resources/ai/tools.yaml',
    ID_GENERATOR,
    SSE_BUDGET,
    'frontend/src/components/AiPanel/AiPanel.vue',
    AI_STORE,
    AI_API,
    'frontend/src/api/sse.ts',
    'frontend/src/api/http.ts',
    'frontend/src/utils/ai-identity.ts',
    'frontend/src/utils/frontend-tools.ts',
    'frontend/src/types/sse.ts',
    'frontend/src/locales/zh-CN.json',
    'frontend/src/locales/en-US.json',
  ]
  for (const filePath of blockPaths) {
    const result = classify(filePath)
    assert.equal(result.category, 'block', `${filePath} 应为 block（实际 ${result.category}）`)
    assert.ok(result.label.length > 0, filePath)
  }

  const warnPaths = [
    'backend/src/test/java/com/example/configmgr/ai/tenant/RunIdentityScopeTest.java',
    'frontend/e2e/08-session.spec.ts',
    'frontend/e2e/support/harness.ts',
    'frontend/src/components/AiPanel/AiPanel.test.ts',
    'scripts/ci/stub-upstream.mjs',
    'scripts/chat-panel-smoke.mjs',
  ]
  for (const filePath of warnPaths) {
    assert.equal(classify(filePath).category, 'warn', `${filePath} 应为 warn`)
  }

  const otherPaths = [
    BUSINESS,
    'backend/src/main/java/com/example/configmgr/common/ErrorCodes.java',
    'frontend/src/views/TaskListView.vue',
  ]
  for (const filePath of otherPaths) {
    assert.equal(classify(filePath).category, 'other', `${filePath} 应为 other`)
  }
})

test('删除 block 文件同样要求本次确认', () => {
  const deleted = change('backend/src/main/java/com/example/configmgr/ai/web/OldController.java', 'D', '0'.repeat(40))
  const ok = stagedWithReview([deleted], [confirmation([deleted])])
  assert.equal(evaluate({ changes: ok.changes, reviewText: ok.reviewText }).exitCode, 0)

  const staleEntries = [confirmation([change(deleted.path, 'M', 'blob-old')])]
  const stale = stagedWithReview([deleted], staleEntries)
  const result = evaluate({ changes: stale.changes, reviewText: stale.reviewText })
  assert.equal(result.exitCode, 1)
  assert.equal(result.kind, 'no-confirmation')
})
