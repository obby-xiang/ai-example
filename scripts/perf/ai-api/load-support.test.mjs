import test from 'node:test'
import assert from 'node:assert/strict'
import { createLoadRunner, parseApiLoadConfig, planPairedDeletes, summarizeLatencies } from './load-support.mjs'

test('parseApiLoadConfig defaults to disabled with concurrency 4 and 8 requests per endpoint', () => {
  assert.deepEqual(parseApiLoadConfig({}), {
    enabled: false,
    concurrency: 4,
    requestsPerEndpoint: 8
  })
})

test('parseApiLoadConfig enables only on the literal flag true', () => {
  assert.equal(parseApiLoadConfig({ EVAL_ENABLE_API_LOAD: 'true' }).enabled, true)
  assert.equal(parseApiLoadConfig({ EVAL_ENABLE_API_LOAD: 'TRUE' }).enabled, true)
  assert.equal(parseApiLoadConfig({ EVAL_ENABLE_API_LOAD: '1' }).enabled, false)
  assert.equal(parseApiLoadConfig({ EVAL_ENABLE_API_LOAD: '' }).enabled, false)
  assert.equal(parseApiLoadConfig({ EVAL_ENABLE_API_LOAD: 'false' }).enabled, false)
})

test('parseApiLoadConfig accepts the inclusive bounds', () => {
  assert.deepEqual(parseApiLoadConfig({
    EVAL_ENABLE_API_LOAD: 'true',
    EVAL_API_CONCURRENCY: '1',
    EVAL_API_LOAD_REQUESTS: '1'
  }), { enabled: true, concurrency: 1, requestsPerEndpoint: 1 })
  assert.deepEqual(parseApiLoadConfig({
    EVAL_ENABLE_API_LOAD: 'true',
    EVAL_API_CONCURRENCY: '16',
    EVAL_API_LOAD_REQUESTS: '100'
  }), { enabled: true, concurrency: 16, requestsPerEndpoint: 100 })
})

test('parseApiLoadConfig rejects invalid concurrency values even when load is off', () => {
  for (const value of ['0', '17', '-1', '2.5', 'abc', '']) {
    assert.throws(() => parseApiLoadConfig({ EVAL_API_CONCURRENCY: value }),
      /EVAL_API_CONCURRENCY must be an integer between 1 and 16/,
      `expected ${JSON.stringify(value)} to be rejected`)
  }
})

test('parseApiLoadConfig rejects invalid request counts even when load is off', () => {
  for (const value of ['0', '101', '-1', '1.5', 'abc', '']) {
    assert.throws(() => parseApiLoadConfig({ EVAL_API_LOAD_REQUESTS: value }),
      /EVAL_API_LOAD_REQUESTS must be an integer between 1 and 100/,
      `expected ${JSON.stringify(value)} to be rejected`)
  }
})

test('createLoadRunner caps concurrency, runs every task, and reports maxObserved', async () => {
  const runner = createLoadRunner({ concurrency: 3 })
  let active = 0
  let peak = 0
  const tasks = Array.from({ length: 9 }, (_, index) => runner.run(async () => {
    active += 1
    peak = Math.max(peak, active)
    await new Promise((resolve) => setTimeout(resolve, 10))
    active -= 1
    return index
  }))
  const results = await Promise.all(tasks)
  assert.deepEqual(results, [0, 1, 2, 3, 4, 5, 6, 7, 8])
  assert.equal(peak, 3)
  assert.equal(runner.snapshot().maxObserved, 3)
  assert.equal(runner.snapshot().inFlight, 0)
})

test('createLoadRunner releases slots when tasks fail', async () => {
  const runner = createLoadRunner({ concurrency: 2 })
  const results = await Promise.allSettled([
    runner.run(async () => 'a'),
    runner.run(async () => {
      await new Promise((resolve) => setTimeout(resolve, 5))
      throw new Error('boom')
    }),
    runner.run(async () => 'c')
  ])
  assert.deepEqual(results.map((result) => result.status), ['fulfilled', 'rejected', 'fulfilled'])
  assert.equal(results[1].reason.message, 'boom')
  assert.equal(runner.snapshot().inFlight, 0)
})

test('summarizeLatencies returns nulls for an empty sample set', () => {
  assert.deepEqual(summarizeLatencies([]), {
    count: 0,
    min: null,
    max: null,
    mean: null,
    p50: null,
    p95: null
  })
  assert.deepEqual(summarizeLatencies([Number.NaN, Number.POSITIVE_INFINITY, '20']), {
    count: 0,
    min: null,
    max: null,
    mean: null,
    p50: null,
    p95: null
  })
})

test('summarizeLatencies uses nearest-rank percentiles over sorted samples', () => {
  assert.deepEqual(summarizeLatencies([40, 10, 30, 20]), {
    count: 4,
    min: 10,
    max: 40,
    mean: 25,
    p50: 20,
    p95: 40
  })
  assert.deepEqual(summarizeLatencies([5]), {
    count: 1,
    min: 5,
    max: 5,
    mean: 5,
    p50: 5,
    p95: 5
  })
  const twenty = Array.from({ length: 20 }, (_, index) => index + 1)
  assert.equal(summarizeLatencies(twenty).p50, 10)
  assert.equal(summarizeLatencies(twenty).p95, 19)
})

test('summarizeLatencies rounds only the mean', () => {
  const stats = summarizeLatencies([1, 2, 2])
  assert.equal(stats.mean, 1.67)
  assert.equal(stats.p50, 2)
  assert.equal(stats.p95, 2)
})

test('planPairedDeletes keeps every created id when all creations succeeded', () => {
  assert.deepEqual(planPairedDeletes(['a', 'b', 'c'], 3), {
    targets: ['a', 'b', 'c'],
    configured: 3,
    skipped: 0,
    status: 'READY'
  })
})

test('planPairedDeletes deletes only the successful creations and counts the rest as skipped', () => {
  assert.deepEqual(planPairedDeletes(['a'], 3), {
    targets: ['a'],
    configured: 1,
    skipped: 2,
    status: 'READY'
  })
})

test('planPairedDeletes marks zero targets as SKIPPED, not PASS or FAIL', () => {
  assert.deepEqual(planPairedDeletes([], 3), {
    targets: [],
    configured: 0,
    skipped: 3,
    status: 'SKIPPED'
  })
})

test('planPairedDeletes ignores empty or non-string ids', () => {
  assert.deepEqual(planPairedDeletes(['a', '', null, 'b'], 4), {
    targets: ['a', 'b'],
    configured: 2,
    skipped: 2,
    status: 'READY'
  })
})

test('planPairedDeletes rejects an invalid requested count', () => {
  for (const value of [-1, 1.5, Number.NaN]) {
    assert.throws(() => planPairedDeletes([], value), /requested count must be a non-negative integer/)
  }
})
