#!/usr/bin/env node
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO = path.resolve(HERE, '..', '..')
const FRONTEND = path.join(REPO, 'frontend')
const source = fs.readFileSync(path.join(FRONTEND, 'e2e', '15-mdo.spec.ts'), 'utf8')
const ids = [...source.matchAll(/test\('(E2E-[MDO]\d+[a-z]?)/g)].map((match) => match[1])

function idsIn(group) {
  return ids.filter((id) => id.charAt(4) === group).sort()
}

function listed(group) {
  const result = spawnSync(process.execPath, [
    'node_modules/@playwright/test/cli.js', 'test', '--config', 'playwright.mdo.config.ts',
    '--list', '--reporter=json'
  ], {
    cwd: FRONTEND,
    env: { ...process.env, E2E_MDO_GROUP: group, E2E_MODE: 'stub' },
    encoding: 'utf8',
    maxBuffer: 8 * 1024 * 1024
  })
  assert.equal(result.status, 0, `${group} --list failed: ${result.stderr}\n${result.stdout}`)
  const report = JSON.parse(result.stdout)
  const found = new Set()
  function visit(value) {
    if (Array.isArray(value)) {
      value.forEach(visit)
      return
    }
    if (!value || typeof value !== 'object') return
    if (typeof value.title === 'string') {
      const match = value.title.match(/E2E-[MDO]\d+[a-z]?/)
      if (match) found.add(match[0])
    }
    Object.values(value).forEach(visit)
  }
  visit(report)
  return [...found].sort()
}

test('MDO 两组各自选择正确用例，合并后无遗漏、无重复', () => {
  assert.deepEqual([...new Set(ids)].sort(), [...ids].sort(), '测试编号不得重复')
  assert.equal(ids.length, 22, '当前 M7 + D7 + O8 共 22 条，不得静默缩减')
  const multi = listed('multi')
  const degraded = listed('degraded')
  assert.deepEqual(multi, idsIn('M'), 'M 组必须完整选择多实例用例')
  assert.deepEqual(degraded, [...idsIn('D'), ...idsIn('O')].sort(), '降级组必须完整选择 D/O 用例')
  assert.deepEqual([...multi, ...degraded].sort(), [...ids].sort(), '两组合并必须覆盖全部 MDO 用例')
  assert.equal(new Set([...multi, ...degraded]).size, ids.length, '两组不得重复执行')
})

test('共享 H2 必须先完成 A 的迁移和种子，再启动 B，并给锁等待明确上限', () => {
  const harness = fs.readFileSync(path.join(FRONTEND, 'e2e', 'support', 'harness.ts'), 'utf8').replace(/\r\n/g, '\n')
  const sharedReady = harness.indexOf("if (only.includes('main') && SHARED_AI_STACK)")
  const startB = harness.indexOf("if (only.includes('degraded')) {\n    const backend = spawnLogged(")
  const degradedReady = harness.indexOf("if (only.includes('main') && !SHARED_AI_STACK)")
  assert.ok(sharedReady >= 0, '共享组必须有 A 先就绪的分支')
  assert.ok(startB > sharedReady, 'B 的启动必须在共享组 A 就绪之后')
  assert.ok(degradedReady > startB, 'D/O 保持 A/B 同时启动后的原就绪顺序')
  const sharedBlock = harness.slice(sharedReady, startB)
  assert.match(sharedBlock, /await waitForMainReady\(\)/, '共享分支必须真的等待 A 就绪（不能是空 if）')
  assert.doesNotMatch(sharedBlock, /spawnLogged\('backend-degraded'/, '共享分支内不得启动 B')
  assert.match(harness, /SHARED_AI_STACK \? ';AUTO_SERVER=TRUE;LOCK_TIMEOUT=60000' : ''/)
  assert.match(harness, /async function waitForMainReady\(\)/)
})
