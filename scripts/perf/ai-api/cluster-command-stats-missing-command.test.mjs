import test from 'node:test'
import assert from 'node:assert/strict'
import {diffRedisCommandStats} from './load-support.mjs'

test('command stats rejects an after snapshot missing a command present before load', () => {
  const before = 'cmdstat_get:calls=100,usec=1000,usec_per_call=10.00\ncmdstat_set:calls=4,usec=40,usec_per_call=10.00\n'
  const after = 'cmdstat_get:calls=125,usec=1600,usec_per_call=12.80\n'
  assert.throws(() => diffRedisCommandStats(before, after), /reset or incomplete after snapshot/)
})
