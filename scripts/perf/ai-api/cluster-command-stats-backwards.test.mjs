import test from 'node:test'
import assert from 'node:assert/strict'
import {diffRedisCommandStats} from './load-support.mjs'

test('command stats rejects a counter moving backwards', () => {
  const before = 'cmdstat_get:calls=100,usec=1000,usec_per_call=10.00\n'
  const after = 'cmdstat_get:calls=99,usec=1600,usec_per_call=16.16\n'
  assert.throws(() => diffRedisCommandStats(before, after), /counter moved backwards/)
})
