import test from 'node:test'
import assert from 'node:assert/strict'
import * as support from './load-support.mjs'

test('command stats evidence uses per-master before/after increments, not cumulative counters', () => {
  const before = [
    '# Commandstats',
    'cmdstat_get:calls=100,usec=1000,usec_per_call=10.00',
    'cmdstat_set:calls=40,usec=800,usec_per_call=20.00',
    ''
  ].join('\r\n')
  const after = [
    '# Commandstats',
    'cmdstat_get:calls=125,usec=1600,usec_per_call=12.80',
    'cmdstat_set:calls=40,usec=800,usec_per_call=20.00',
    'cmdstat_del:calls=2,usec=50,usec_per_call=25.00',
    ''
  ].join('\r\n')
  assert.equal(typeof support.diffRedisCommandStats, 'function')
  assert.deepEqual(support.diffRedisCommandStats(before, after), {
    commands: {del: 2, get: 25, set: 0},
    totalCalls: 27
  })
})
