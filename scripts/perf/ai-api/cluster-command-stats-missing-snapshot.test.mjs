import test from 'node:test'
import assert from 'node:assert/strict'
import {diffRedisCommandStats} from './load-support.mjs'

test('command stats rejects a missing before snapshot instead of treating it as zero', () => {
  const after = 'cmdstat_get:calls=125,usec=1600,usec_per_call=12.80\n'
  assert.throws(() => diffRedisCommandStats(null, after), /snapshot missing/)
})
