#!/usr/bin/env node
/**
 * redis-run-migration 的 node:test 单测（内存 fake cluster，不连真实 Redis）。
 *
 * 覆盖：SCAN 发现（跨 master/单 master 真游标跨页、不碰 KEYS）、四类型全值/顺序/显式
 * stream ID/PTTL、幂等第二遍 0 写、目标冲突不覆盖、缺 seq/xevents 整 run 跳过、
 * --delete-source 两阶段（校验阶段 all-or-none；DEL 阶段部分删除 + 重跑收敛）、
 * --rollback 反向、排除键族不搬、节点环境变量无默认、SET NX/RENAMENX 并发返回、
 * apply 中途失败 partial results、临时键同槽/原子安全 TTL/重建、迁移锁续租/丢失、
 * rollback+delete 作用域、stream/hash/list/SCAN 分页、ttlShort/targetPttl 输出。
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';

import * as migration from './redis-run-migration.mjs';

const {
	EXCLUDED_KEY_PREFIXES,
	MIGRATION_FAMILIES,
	NODES_ENV,
	formatApply,
	formatPlan,
	nodesFromEnv,
	oldKeyFor,
	parseArgs,
	parseTaggedIdentity,
	parseTaggedKey,
	rollbackHint,
	runMigration,
	taggedKeyFor,
} = migration;

const LOCK_KEY = 'ai:migrate:lock';
const SAFETY_TTL_MS = 60 * 60 * 1000;

const TTL_LONG = 600_000;
const TTL_MEDIUM = 300_000;

// ── 内存 fake cluster ──────────────────────────────────────────────────────

function shardOf(key, count) {
	let hash = 0;
	for (let i = 0; i < key.length; i += 1) {
		hash = (hash * 31 + key.charCodeAt(i)) >>> 0;
	}
	return hash % count;
}

function globToRegExp(pattern) {
	let out = '^';
	for (const ch of pattern) {
		if (ch === '*') {
			out += '.*';
		} else if ('\\^$.|?+()[]{}'.includes(ch)) {
			out += `\\${ch}`;
		} else {
			out += ch;
		}
	}
	return new RegExp(`${out}$`);
}

function parseStreamId(token) {
	if (token === '-') {
		return [-Infinity, -Infinity];
	}
	if (token === '+') {
		return [Infinity, Infinity];
	}
	const [ms, seq] = String(token).split('-').map(Number);
	return [ms, seq];
}

function compareStreamId(left, right) {
	if (left[0] !== right[0]) {
		return left[0] < right[0] ? -1 : 1;
	}
	if (left[1] !== right[1]) {
		return left[1] < right[1] ? -1 : 1;
	}
	return 0;
}

function cloneEntry(entry) {
	if (!entry) {
		return null;
	}
	if (entry.type === 'string') {
		return { type: 'string', value: entry.value, expiresAt: entry.expiresAt };
	}
	if (entry.type === 'hash') {
		return { type: 'hash', value: new Map(entry.value), expiresAt: entry.expiresAt };
	}
	if (entry.type === 'list') {
		return { type: 'list', value: [...entry.value], expiresAt: entry.expiresAt };
	}
	if (entry.type === 'stream') {
		return {
			type: 'stream',
			value: entry.value.map(([id, fields]) => [id, [...fields]]),
			expiresAt: entry.expiresAt,
		};
	}
	if (entry.type === 'set') {
		return { type: 'set', value: new Set(entry.value), expiresAt: entry.expiresAt };
	}
	throw new Error(`unknown fake type ${entry.type}`);
}

/** 单 key 的简化 MULTI/EXEC：失败时回滚快照，用来模拟 Redis 事务原子性。 */
class FakeMulti {
	constructor(redis) {
		this.redis = redis;
		this.commands = [];
	}

	_queue(name, args) {
		this.commands.push({ name, args });
		return this;
	}

	hset(...args) {
		return this._queue('hset', args);
	}

	rpush(...args) {
		return this._queue('rpush', args);
	}

	xadd(...args) {
		return this._queue('xadd', args);
	}

	sadd(...args) {
		return this._queue('sadd', args);
	}

	srem(...args) {
		return this._queue('srem', args);
	}

	pexpire(...args) {
		return this._queue('pexpire', args);
	}

	async exec() {
		const keys = new Set(this.commands.map(({ args }) => String(args[0])));
		const snapshot = new Map();
		for (const key of keys) {
			snapshot.set(key, cloneEntry(this.redis.store.get(key)));
		}
		const results = [];
		try {
			for (const { name, args } of this.commands) {
				results.push([null, await this.redis[name](...args)]);
			}
			return results;
		} catch (error) {
			for (const key of keys) {
				const saved = snapshot.get(key);
				if (saved) {
					this.redis.store.set(key, saved);
				} else {
					this.redis.store.delete(key);
				}
			}
			throw error;
		}
	}
}

class FakeRedis {
	constructor(store, stats, shardIndex = 0, shardCount = 1) {
		this.store = store;
		this.stats = stats;
		this.shardIndex = shardIndex;
		this.shardCount = shardCount;
	}

	async _hook(name, payload) {
		const hook = this.stats.hooks?.[name];
		if (hook) {
			await hook(payload);
		}
	}

	_entry(key) {
		const entry = this.store.get(key);
		if (!entry) {
			return null;
		}
		if (entry.expiresAt !== null && entry.expiresAt <= Date.now()) {
			this.store.delete(key);
			return null;
		}
		return entry;
	}

	_owns(key) {
		return shardOf(key, this.shardCount) === this.shardIndex;
	}

	async type(key) {
		return this._entry(key)?.type ?? 'none';
	}

	async exists(key) {
		return this._entry(key) ? 1 : 0;
	}

	async get(key) {
		const entry = this._entry(key);
		return entry?.type === 'string' ? entry.value : null;
	}

	async set(key, value, ...args) {
		let px = null;
		let nx = false;
		for (let i = 0; i < args.length; i += 1) {
			const arg = String(args[i]).toUpperCase();
			if (arg === 'PX') {
				px = Number(args[++i]);
			} else if (arg === 'EX') {
				px = Number(args[++i]) * 1000;
			} else if (arg === 'NX') {
				nx = true;
			} else {
				throw new Error(`fake SET does not support ${arg}`);
			}
		}
		await this._hook('beforeSet', { key, value, args, store: this.store });
		if (nx && this._entry(key)) {
			return null;
		}
		this.store.set(key, {
			type: 'string',
			value: String(value),
			expiresAt: px === null ? null : Date.now() + px,
		});
		await this._hook('afterSet', { key, value: String(value), store: this.store });
		return 'OK';
	}

	async pttl(key) {
		await this._hook('beforePttl', { key, store: this.store });
		const entry = this._entry(key);
		if (!entry) {
			return -2;
		}
		if (entry.expiresAt === null) {
			return -1;
		}
		return Math.max(0, entry.expiresAt - Date.now());
	}

	async pexpire(key, ms) {
		await this._hook('beforePexpire', { key, ms, store: this.store });
		const entry = this._entry(key);
		if (!entry) {
			return 0;
		}
		entry.expiresAt = Date.now() + Number(ms);
		return 1;
	}

	async persist(key) {
		const entry = this._entry(key);
		if (!entry) {
			return 0;
		}
		entry.expiresAt = null;
		return 1;
	}

	async strlen(key) {
		const entry = this._entry(key);
		return entry?.type === 'string' ? entry.value.length : 0;
	}

	async hset(key, ...args) {
		let pairs;
		if (args.length === 1 && args[0] && typeof args[0] === 'object') {
			pairs = Object.entries(args[0]).flat();
		} else {
			if (args.length % 2 !== 0) {
				throw new Error('HSET requires field/value pairs');
			}
			pairs = args.map(String);
		}
		await this._hook('beforeHset', { key, args, store: this.store });
		let entry = this._entry(key);
		if (!entry) {
			entry = { type: 'hash', value: new Map(), expiresAt: null };
			this.store.set(key, entry);
		}
		if (entry.type !== 'hash') {
			throw new Error('WRONGTYPE');
		}
		let added = 0;
		for (let i = 0; i < pairs.length; i += 2) {
			if (!entry.value.has(pairs[i])) {
				added += 1;
			}
			entry.value.set(pairs[i], pairs[i + 1]);
		}
		return added;
	}

	async hgetall(key) {
		const entry = this._entry(key);
		if (!entry) {
			return null;
		}
		if (entry.type !== 'hash') {
			throw new Error('WRONGTYPE');
		}
		return Object.fromEntries(entry.value.entries());
	}

	async hlen(key) {
		const entry = this._entry(key);
		return entry?.type === 'hash' ? entry.value.size : 0;
	}

	async rpush(key, ...values) {
		let entry = this._entry(key);
		if (!entry) {
			entry = { type: 'list', value: [], expiresAt: null };
			this.store.set(key, entry);
		}
		if (entry.type !== 'list') {
			throw new Error('WRONGTYPE');
		}
		entry.value.push(...values.map(String));
		return entry.value.length;
	}

	async lrange(key, start, stop) {
		const entry = this._entry(key);
		if (!entry) {
			return [];
		}
		if (entry.type !== 'list') {
			throw new Error('WRONGTYPE');
		}
		const data = entry.value;
		let from = Number(start);
		let to = Number(stop);
		if (from < 0) {
			from = Math.max(data.length + from, 0);
		}
		if (to < 0) {
			to = data.length + to;
		}
		return data.slice(from, to + 1).map(String);
	}

	async llen(key) {
		const entry = this._entry(key);
		return entry?.type === 'list' ? entry.value.length : 0;
	}

	async xadd(key, id, ...args) {
		let entry = this._entry(key);
		if (!entry) {
			entry = { type: 'stream', value: [], expiresAt: null };
			this.store.set(key, entry);
		}
		if (entry.type !== 'stream') {
			throw new Error('WRONGTYPE');
		}
		if (args.length % 2 !== 0) {
			throw new Error('XADD requires field/value pairs');
		}
		let nextId = String(id);
		if (nextId === '*') {
			nextId = `${Date.now()}-0`;
		}
		const next = parseStreamId(nextId);
		const last = entry.value.length > 0
			? parseStreamId(entry.value[entry.value.length - 1][0])
			: [0, 0];
		if (compareStreamId(next, last) <= 0) {
			throw new Error('The ID specified in XADD is equal or smaller than the target stream top item');
		}
		entry.value.push([nextId, args.map(String)]);
		return nextId;
	}

	async xrange(key, start, stop, ...args) {
		const entry = this._entry(key);
		if (!entry) {
			return [];
		}
		if (entry.type !== 'stream') {
			throw new Error('WRONGTYPE');
		}
		const from = parseStreamId(start);
		const to = parseStreamId(stop);
		const countIndex = args.findIndex((arg) => String(arg).toUpperCase() === 'COUNT');
		const limit = countIndex >= 0 ? Number(args[countIndex + 1]) : Infinity;
		const rows = entry.value
			.filter((row) => {
				const id = parseStreamId(row[0]);
				return compareStreamId(id, from) >= 0 && compareStreamId(id, to) <= 0;
			})
			.slice(0, limit);
		return rows.map((row) => [row[0], [...row[1]]]);
	}

	async xlen(key) {
		const entry = this._entry(key);
		return entry?.type === 'stream' ? entry.value.length : 0;
	}

	async sadd(key, ...members) {
		let entry = this._entry(key);
		if (!entry) {
			entry = { type: 'set', value: new Set(), expiresAt: null };
			this.store.set(key, entry);
		}
		if (entry.type !== 'set') {
			throw new Error('WRONGTYPE');
		}
		let added = 0;
		for (const member of members.map(String)) {
			if (!entry.value.has(member)) {
				entry.value.add(member);
				added += 1;
			}
		}
		return added;
	}

	async srem(key, ...members) {
		const entry = this._entry(key);
		if (!entry) {
			return 0;
		}
		if (entry.type !== 'set') {
			throw new Error('WRONGTYPE');
		}
		let removed = 0;
		for (const member of members.map(String)) {
			if (entry.value.delete(member)) {
				removed += 1;
			}
		}
		return removed;
	}

	async smembers(key) {
		const entry = this._entry(key);
		if (!entry) {
			return [];
		}
		if (entry.type !== 'set') {
			throw new Error('WRONGTYPE');
		}
		return [...entry.value].sort();
	}

	async del(key) {
		if (this.stats.ignoreDelete) {
			return 0;
		}
		await this._hook('beforeDel', { key, store: this.store });
		return this.store.delete(key) ? 1 : 0;
	}

	multi() {
		return new FakeMulti(this);
	}

	async renamenx(source, destination) {
		this.stats.renamenxCalls += 1;
		await this._hook('beforeRenamenx', { source, destination, store: this.store });
		const entry = this._entry(source);
		if (!entry) {
			throw new Error('ERR no such key');
		}
		if (this._entry(destination)) {
			return 0;
		}
		this.store.set(destination, entry);
		this.store.delete(source);
		return 1;
	}

	async eval(script, numKeys, ...args) {
		if (Number(numKeys) !== 1) {
			throw new Error('fake EVAL only supports one key');
		}
		const [key, token, ttl] = args;
		const entry = this._entry(key);
		if (!entry || entry.type !== 'string' || entry.value !== String(token)) {
			return 0;
		}
		if (/pexpire/i.test(script)) {
			entry.expiresAt = Date.now() + Number(ttl);
			return 1;
		}
		if (/del/i.test(script)) {
			this.store.delete(key);
			return 1;
		}
		throw new Error('fake EVAL does not support this script');
	}

	async scan(cursor, ...args) {
		this.stats.scanCalls += 1;
		let pattern = '*';
		let count = 10;
		for (let i = 0; i < args.length; i += 1) {
			const arg = String(args[i]).toUpperCase();
			if (arg === 'MATCH') {
				pattern = String(args[++i]);
			} else if (arg === 'COUNT') {
				count = Number(args[++i]);
			}
		}
		this.stats.scanByPattern[pattern] = (this.stats.scanByPattern[pattern] ?? 0) + 1;
		const matcher = globToRegExp(pattern);
		const keys = [...this.store.keys()]
			.filter((key) => this._owns(key) && matcher.test(key))
			.sort();
		const start = Number(cursor) || 0;
		const page = keys.slice(start, start + count);
		const next = start + count >= keys.length ? '0' : String(start + count);
		if (next !== '0') {
			this.stats.cursorAdvances[pattern] = (this.stats.cursorAdvances[pattern] ?? 0) + 1;
		}
		return [next, page];
	}

	async keys() {
		this.stats.keysCalls += 1;
		throw new Error('KEYS is forbidden; use SCAN');
	}

	async ping() {
		return 'PONG';
	}

	disconnect() {
		// no-op
	}
}

function makeFakeCluster() {
	const store = new Map();
	const stats = {
		scanCalls: 0,
		keysCalls: 0,
		renamenxCalls: 0,
		scanByPattern: {},
		cursorAdvances: {},
		ignoreDelete: false,
		hooks: {},
	};
	const cluster = new FakeRedis(store, stats, 0, 1);
	const masters = [
		new FakeRedis(store, stats, 0, 2),
		new FakeRedis(store, stats, 1, 2),
	];
	cluster.nodes = (role) => (role === 'master' ? masters : []);
	cluster.stats = stats;
	cluster.store = store;
	cluster.masters = masters;
	cluster.hooks = stats.hooks;
	return cluster;
}

// ── 种子 / 断言工具 ────────────────────────────────────────────────────────

async function seedFullRun(cluster, runId, options = {}) {
	const seedSeq = options.seedSeq !== false;
	const seedXevents = options.seedXevents !== false;
	await cluster.set(oldKeyFor('ai:run:', runId), JSON.stringify({ runId, state: 'SUSPENDED', text: '你好' }), 'PX', TTL_LONG);
	await cluster.hset(
		oldKeyFor('ai:pending:', runId),
		'tc-1', JSON.stringify({ toolName: 'sheet.read', args: { a: 1 } }),
		'tc-2', JSON.stringify({ toolName: 'sheet.write', args: { b: 2 } }),
	);
	await cluster.pexpire(oldKeyFor('ai:pending:', runId), TTL_LONG);
	await cluster.hset(oldKeyFor('ai:claim:', runId), 'tc-1', '3|inst-a');
	await cluster.pexpire(oldKeyFor('ai:claim:', runId), TTL_MEDIUM);
	await cluster.rpush(oldKeyFor('ai:ledger:', runId), '{"i":0}', '{"i":1}', '{"i":2}');
	await cluster.pexpire(oldKeyFor('ai:ledger:', runId), TTL_LONG);
	await cluster.hset(oldKeyFor('ai:idem:', runId), `${runId}:tc-1`, '0|OK||inst-a|1');
	await cluster.pexpire(oldKeyFor('ai:idem:', runId), TTL_LONG);
	await cluster.rpush(oldKeyFor('ai:events:', runId), '{"seq":1}', '{"seq":2}');
	await cluster.pexpire(oldKeyFor('ai:events:', runId), TTL_LONG);
	if (seedSeq) {
		await cluster.set(oldKeyFor('ai:seq:', runId), '9', 'PX', TTL_LONG);
	}
	if (seedXevents) {
		await cluster.xadd(oldKeyFor('ai:xevents:', runId), '1-0', 'seq', '1', 'frame', '{"seq":1}');
		await cluster.xadd(oldKeyFor('ai:xevents:', runId), '5-0', 'seq', '5', 'frame', '{"seq":5}');
		await cluster.xadd(oldKeyFor('ai:xevents:', runId), '9-0', 'seq', '9', 'frame', '{"seq":9}');
		await cluster.pexpire(oldKeyFor('ai:xevents:', runId), TTL_LONG);
	}
	await cluster.hset(oldKeyFor('ai:req:', runId), 'tc-1', '1700000000000');
	await cluster.pexpire(oldKeyFor('ai:req:', runId), TTL_LONG);
	await cluster.hset(oldKeyFor('ai:decided:', runId), 'tc-1', 'APPROVED|1700000000001|inst-a');
	await cluster.pexpire(oldKeyFor('ai:decided:', runId), TTL_LONG);
}

function tagged(runId, family) {
	return taggedKeyFor(family.prefix, runId);
}

function family(name) {
	return MIGRATION_FAMILIES.find((entry) => entry.name === name);
}

function hashTag(key) {
	const open = key.indexOf('{');
	const close = key.indexOf('}', open);
	return open >= 0 && close > open ? key.slice(open + 1, close) : null;
}

function tempKey(targetKey) {
	return `${targetKey}:__migrate_tmp__`;
}

async function seedMinimalRun(cluster, runId) {
	await cluster.set(oldKeyFor('ai:run:', runId), JSON.stringify({ runId }), 'PX', TTL_LONG);
	await cluster.set(oldKeyFor('ai:seq:', runId), '3', 'PX', TTL_LONG);
	await cluster.xadd(
		oldKeyFor('ai:xevents:', runId),
		'1-0',
		'seq', '1',
		'frame', '{"seq":1}',
	);
	await cluster.pexpire(oldKeyFor('ai:xevents:', runId), TTL_LONG);
}

async function seedPagedRun(cluster, runId) {
	await cluster.set(oldKeyFor('ai:run:', runId), JSON.stringify({ runId, state: 'SUSPENDED' }), 'PX', TTL_LONG);
	await cluster.set(oldKeyFor('ai:seq:', runId), '1200', 'PX', TTL_LONG);
	const hashArgs = [];
	for (let i = 0; i < 1200; i += 1) {
		hashArgs.push(`f${i}`, `v${i}`);
	}
	for (let i = 0; i < hashArgs.length; i += 1000) {
		await cluster.hset(oldKeyFor('ai:pending:', runId), ...hashArgs.slice(i, i + 1000));
	}
	await cluster.pexpire(oldKeyFor('ai:pending:', runId), TTL_LONG);
	const list = [];
	for (let i = 0; i < 2500; i += 1) {
		list.push(`item-${i}`);
	}
	for (let i = 0; i < list.length; i += 1000) {
		await cluster.rpush(oldKeyFor('ai:ledger:', runId), ...list.slice(i, i + 1000));
	}
	await cluster.pexpire(oldKeyFor('ai:ledger:', runId), TTL_LONG);
	for (let i = 1; i <= 1200; i += 1) {
		await cluster.xadd(
			oldKeyFor('ai:xevents:', runId),
			`${i}-0`,
			'seq', String(i),
			'frame', `{"seq":${i}}`,
		);
	}
	await cluster.pexpire(oldKeyFor('ai:xevents:', runId), TTL_LONG);
}

async function assertRunCopied(cluster, runId) {
	assert.equal(
		await cluster.get(tagged(runId, family('run'))),
		await cluster.get(oldKeyFor('ai:run:', runId)),
	);
	assert.deepEqual(
		await cluster.hgetall(tagged(runId, family('pending'))),
		await cluster.hgetall(oldKeyFor('ai:pending:', runId)),
	);
	assert.deepEqual(
		await cluster.hgetall(tagged(runId, family('claim'))),
		await cluster.hgetall(oldKeyFor('ai:claim:', runId)),
	);
	assert.deepEqual(
		await cluster.lrange(tagged(runId, family('ledger')), 0, -1),
		await cluster.lrange(oldKeyFor('ai:ledger:', runId), 0, -1),
	);
	assert.deepEqual(
		await cluster.hgetall(tagged(runId, family('idem'))),
		await cluster.hgetall(oldKeyFor('ai:idem:', runId)),
	);
	assert.deepEqual(
		await cluster.lrange(tagged(runId, family('events')), 0, -1),
		await cluster.lrange(oldKeyFor('ai:events:', runId), 0, -1),
	);
	assert.equal(
		await cluster.get(tagged(runId, family('seq'))),
		await cluster.get(oldKeyFor('ai:seq:', runId)),
	);
	assert.deepEqual(
		await cluster.xrange(tagged(runId, family('xevents')), '-', '+'),
		await cluster.xrange(oldKeyFor('ai:xevents:', runId), '-', '+'),
	);
	assert.deepEqual(
		await cluster.hgetall(tagged(runId, family('req'))),
		await cluster.hgetall(oldKeyFor('ai:req:', runId)),
	);
	assert.deepEqual(
		await cluster.hgetall(tagged(runId, family('decided'))),
		await cluster.hgetall(oldKeyFor('ai:decided:', runId)),
	);
}

// ── 用例 ───────────────────────────────────────────────────────────────────

test('节点只从 AI_REDIS_CLUSTER_NODES 读，缺失/空白/格式错一律失败（无默认值）', () => {
	assert.throws(() => nodesFromEnv({}), /AI_REDIS_CLUSTER_NODES/);
	assert.throws(() => nodesFromEnv({ [NODES_ENV]: '   ' }), /没有默认节点/);
	assert.throws(() => nodesFromEnv({ [NODES_ENV]: '10.0.0.11' }), /host:port/);
	assert.throws(() => nodesFromEnv({ [NODES_ENV]: '10.0.0.11:0' }), /端口非法/);
	assert.deepEqual(
		nodesFromEnv({ [NODES_ENV]: '10.0.0.11:6379, 10.0.0.12:6380' }),
		[{ host: '10.0.0.11', port: 6379 }, { host: '10.0.0.12', port: 6380 }],
	);
});

test('发现走 SCAN（逐 master），不调用 KEYS', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-a');
	await seedFullRun(cluster, 'run-b');
	// 崩溃残留的临时键不得被当成 run（后缀与实现约定一致）
	await cluster.set('ai:run:run-stale:__migrate_tmp__', 'leftover', 'PX', TTL_LONG);
	const result = await runMigration(cluster, {});
	assert.equal(result.ok, true);
	assert.deepEqual(
		result.plan.runs.map((run) => run.runId).sort(),
		['run-a', 'run-b'],
	);
	assert.ok(cluster.stats.scanCalls > 0, '必须调用 SCAN');
	assert.equal(cluster.stats.keysCalls, 0, '禁止 KEYS');
	// 两个 master 都参与：把两个 run 的键分别落到不同 shard 上
	const shardA = shardOf(oldKeyFor('ai:run:', 'run-a'), 2);
	const shardB = shardOf(oldKeyFor('ai:run:', 'run-b'), 2);
	assert.notEqual(shardA, shardB, '测试种子应分布在两个 master 上');
});

test('四类型全量搬运：值/字段/顺序/显式 stream ID/PTTL 全部保留', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	const result = await runMigration(cluster, { apply: true });
	assert.equal(result.ok, true);
	assert.ok(result.totals.writes > 0);
	await assertRunCopied(cluster, 'run-1');

	const stream = await cluster.xrange(tagged('run-1', family('xevents')), '-', '+');
	assert.deepEqual(stream.map((row) => row[0]), ['1-0', '5-0', '9-0']);
	assert.deepEqual(stream.map((row) => row[1]), [
		['seq', '1', 'frame', '{"seq":1}'],
		['seq', '5', 'frame', '{"seq":5}'],
		['seq', '9', 'frame', '{"seq":9}'],
	]);
	assert.deepEqual(
		await cluster.lrange(tagged('run-1', family('ledger')), 0, -1),
		['{"i":0}', '{"i":1}', '{"i":2}'],
	);
	const runPttl = await cluster.pttl(tagged('run-1', family('run')));
	assert.ok(runPttl > TTL_LONG - 5_000 && runPttl <= TTL_LONG, `run PTTL=${runPttl}`);
	const claimPttl = await cluster.pttl(tagged('run-1', family('claim')));
	assert.ok(claimPttl > TTL_MEDIUM - 5_000 && claimPttl <= TTL_MEDIUM, `claim PTTL=${claimPttl}`);
	assert.ok((await cluster.pttl(tagged('run-1', family('xevents')))) > TTL_LONG - 5_000);
	assert.ok(cluster.stats.renamenxCalls > 0, 'tagged 目标应走同槽临时键 + RENAMENX');
	assert.equal(
		await cluster.type(`${tagged('run-1', family('pending'))}:__migrate_tmp__`),
		'none',
		'临时键必须清理',
	);
});

test('apply 幂等：第二遍 0 写，且不覆盖已存在的正确目标', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	const first = await runMigration(cluster, { apply: true });
	assert.ok(first.totals.writes > 0);
	const second = await runMigration(cluster, { apply: true });
	assert.equal(second.ok, true);
	assert.equal(second.totals.writes, 0);
	assert.equal(second.totals.deleted, 0);
	for (const item of second.results[0].items) {
		assert.notEqual(item.action, 'copied');
		assert.equal(item.writes, 0);
	}
	assert.ok(formatApply(second).includes('verify=value-equal'));
});

test('目标已存在且逐值一致 → 跳过（0 写）', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	// 预先写一个只含部分族的正确目标
	await cluster.set(
		tagged('run-1', family('seq')),
		await cluster.get(oldKeyFor('ai:seq:', 'run-1')),
		'PX', TTL_MEDIUM,
	);
	const result = await runMigration(cluster, { apply: true });
	assert.equal(result.ok, true);
	const seqItem = result.results[0].items.find((item) => item.family === 'seq');
	assert.equal(seqItem.action, 'identical');
	assert.equal(seqItem.writes, 0);
	assert.equal(
		await cluster.get(tagged('run-1', family('seq'))),
		'9',
		'已存在的目标值不得被重写',
	);
});

test('目标冲突 → 整体失败、绝不覆盖，且其它干净 run 也不写', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-conflict');
	await seedFullRun(cluster, 'run-clean');
	await cluster.set(tagged('run-conflict', family('run')), 'TARGET-DIFFERENT', 'PX', TTL_MEDIUM);

	const result = await runMigration(cluster, { apply: true });
	assert.equal(result.ok, false);
	assert.match(result.reason, /冲突/);
	assert.equal(result.plan.totals.conflicts, 1);
	assert.equal(result.totals.writes, 0);
	assert.equal(result.results.length, 0);
	assert.equal(
		await cluster.get(tagged('run-conflict', family('run'))),
		'TARGET-DIFFERENT',
		'冲突目标不得被覆盖',
	);
	assert.equal(await cluster.type(tagged('run-clean', family('run'))), 'none');
	assert.equal(await cluster.type(tagged('run-conflict', family('seq'))), 'none');
	assert.ok(formatPlan(result).includes('conflict'));
});

test('seq/xevents 缺一 → 整 run 跳过并报告（不搬任何键、不留活快照无帧）', async (t) => {
	await t.test('缺 xevents', async () => {
		const cluster = makeFakeCluster();
		await seedFullRun(cluster, 'run-1', { seedXevents: false });
		const result = await runMigration(cluster, { apply: true });
		assert.equal(result.ok, true);
		assert.equal(result.plan.runs[0].status, 'skipped');
		assert.match(result.plan.runs[0].reason, /xevents/);
		assert.equal(result.totals.writes, 0);
		assert.equal(await cluster.type(tagged('run-1', family('run'))), 'none');
		assert.equal(await cluster.type(tagged('run-1', family('seq'))), 'none');
		assert.equal(await cluster.get(oldKeyFor('ai:seq:', 'run-1')), '9');
		const text = formatPlan(result);
		assert.match(text, /reason=seq\/xevents/);
		assert.match(text, /skipped/);
	});

	await t.test('缺 seq', async () => {
		const cluster = makeFakeCluster();
		await seedFullRun(cluster, 'run-1', { seedSeq: false });
		const result = await runMigration(cluster, { apply: true });
		assert.equal(result.ok, true);
		assert.equal(result.plan.runs[0].status, 'skipped');
		assert.match(result.plan.runs[0].reason, /seq/);
		assert.equal(result.totals.writes, 0);
		assert.equal(await cluster.type(tagged('run-1', family('xevents'))), 'none');
	});
});

test('dry-run 只读：输出计划/类型/计数/跳过原因，0 写', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	const result = await runMigration(cluster, {});
	assert.equal(result.mode, 'dry-run');
	assert.equal(result.totals.writes, 0);
	assert.equal(await cluster.type(tagged('run-1', family('run'))), 'none');
	const text = formatPlan(result);
	assert.match(text, /mode=dry-run/);
	assert.match(text, /planned_copies=10/);
	assert.match(text, /count=3/);
	// 每个 item 都带类型
	for (const type of ['STRING', 'HASH', 'LIST', 'STREAM']) {
		assert.ok(text.includes(type), `计划应报告 ${type}`);
	}
});

test('--rollback 反向：tagged → 旧裸键，保留 ID/值；源默认保留', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	// 先正向迁移并删源，模拟“只剩新键”的现场
	const forward = await runMigration(cluster, { apply: true, deleteSource: true });
	assert.equal(forward.ok, true);
	assert.equal(forward.totals.deleted, 10);
	for (const entry of MIGRATION_FAMILIES) {
		assert.equal(
			await cluster.type(oldKeyFor(entry.prefix, 'run-1')),
			'none',
			`${entry.name} 源键应已删除`,
		);
	}

	const rollback = await runMigration(cluster, { rollback: true, apply: true });
	assert.equal(rollback.ok, true);
	assert.equal(rollback.direction, 'rollback');
	assert.ok(rollback.totals.writes > 0);
	await assertRunCopied(cluster, 'run-1');
	assert.equal(await cluster.type(tagged('run-1', family('run'))), 'string', 'rollback 默认不删新键');
	assert.match(rollbackHint(rollback), /forward re-run/);
});

test('源默认保留；--delete-source 仅在逐项对账通过后删除', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	const kept = await runMigration(cluster, { apply: true });
	assert.equal(kept.ok, true);
	assert.equal(kept.totals.deleted, 0);
	const sourcePttl = await cluster.pttl(oldKeyFor('ai:run:', 'run-1'));
	assert.ok(
		sourcePttl > TTL_LONG - 5_000 && sourcePttl <= TTL_LONG,
		`源键 PTTL 不应被工具改写：${sourcePttl}`,
	);
	for (const entry of MIGRATION_FAMILIES) {
		assert.notEqual(await cluster.type(oldKeyFor(entry.prefix, 'run-1')), 'none');
	}

	const cleanup = await runMigration(cluster, { apply: true, deleteSource: true });
	assert.equal(cleanup.ok, true);
	assert.equal(cleanup.totals.writes, 0, '源已存在正确目标时删除前不得重写');
	assert.equal(cleanup.totals.deleted, 10);
	for (const entry of MIGRATION_FAMILIES) {
		assert.equal(await cluster.type(oldKeyFor(entry.prefix, 'run-1')), 'none');
		assert.notEqual(await cluster.type(tagged('run-1', entry)), 'none');
	}
	assert.equal(
		await cluster.get(tagged('run-1', family('run'))),
		'{"runId":"run-1","state":"SUSPENDED","text":"你好"}',
	);
	assert.deepEqual(
		(await cluster.xrange(tagged('run-1', family('xevents')), '-', '+')).map((row) => row[0]),
		['1-0', '5-0', '9-0'],
	);
});

test('排除键族一律不搬：fence/lease/epoch/ai:runs/session/beat/cancel/lock', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	const excluded = [
		'ai:fence:run:run-1',
		'ai:lease:run:run-1',
		'ai:lease:epoch:{run:run-1}',
		'ai:runs',
		'ai:session:current:sess-1',
		'ai:session:runs:sess-1',
		'ai:purge:guard:sess-1',
		'ai:purge:pending:sess-1',
		'ai:purge:retry:queue',
		'ai:purge:retry:lock',
		'ai:beat:run-1',
		'ai:cancel:run-1',
		'ai:lock:task:7',
		'ai:lease:epoch',
	];
	for (const key of excluded) {
		await cluster.set(key, `keep:${key}`, 'PX', TTL_LONG);
	}
	const result = await runMigration(cluster, { apply: true });
	assert.equal(result.ok, true);
	for (const key of excluded) {
		assert.equal(await cluster.get(key), `keep:${key}`, `${key} 不得被改动`);
	}
	// 这些族不属于 10 族，不应出现在计划里
	const plannedPrefixes = new Set(
		result.plan.runs.flatMap((run) => run.items.map((item) => item.prefix)),
	);
	for (const prefix of EXCLUDED_KEY_PREFIXES) {
		if (prefix === 'chat:mem:') {
			continue;
		}
		assert.equal(plannedPrefixes.has(prefix), false, `${prefix} 不应参与迁移`);
	}
	assert.equal(await cluster.type('ai:fence:run:{run:run-1}'), 'none');
	assert.equal(await cluster.type('ai:lease:run:{run:run-1}'), 'none');
	assert.equal(
		await cluster.get('ai:lease:epoch:{run:run-1}'),
		'keep:ai:lease:epoch:{run:run-1}',
		'per-run epoch 不搬、不改',
	);
});

test('--run-id 过滤只处理指定 run', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	await seedFullRun(cluster, 'run-2');
	const result = await runMigration(cluster, { apply: true, runIds: ['run-2'] });
	assert.equal(result.ok, true);
	assert.equal(cluster.stats.scanCalls, 0, '--run-id 过滤时无需全库扫描');
	assert.deepEqual(result.plan.runs.map((run) => run.runId), ['run-2']);
	assert.equal(await cluster.type(tagged('run-1', family('run'))), 'none');
	assert.notEqual(await cluster.type(tagged('run-2', family('run'))), 'none');
});

test('源类型与预期不符 → 冲突失败，不写入', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	// 把 pending 换成 string，模拟畸形现场
	await cluster.del(oldKeyFor('ai:pending:', 'run-1'));
	await cluster.set(oldKeyFor('ai:pending:', 'run-1'), 'WRONG-TYPE', 'PX', TTL_LONG);
	const result = await runMigration(cluster, { apply: true });
	assert.equal(result.ok, false);
	assert.match(result.reason, /冲突/);
	assert.equal(result.totals.writes, 0);
	assert.equal(await cluster.type(tagged('run-1', family('run'))), 'none');
	const item = result.plan.runs[0].items.find((entry) => entry.family === 'pending');
	assert.equal(item.action, 'conflict');
});

test('参数解析：默认 dry-run，--apply/--rollback/--delete-source/--run-id', () => {
	assert.deepEqual(parseArgs([]), {
		apply: false,
		rollback: false,
		deleteSource: false,
		help: false,
		runIds: [],
		// 租户任务2：目标身份必须显式声明（旧数据没有身份）
		tenantId: null,
		userId: null,
		identity: null,
	});
	const parsed = parseArgs(['--apply', '--rollback', '--delete-source', '--run-id', 'a,b', '--run-id=c']);
	assert.equal(parsed.apply, true);
	assert.equal(parsed.rollback, true);
	assert.equal(parsed.deleteSource, true);
	assert.deepEqual(parsed.runIds, ['a', 'b', 'c']);
	assert.throws(() => parseArgs(['--run-id']), /缺少值/);
	assert.throws(() => parseArgs(['--nope']), /未知参数/);
	assert.throws(() => parseArgs(['--run-id', 'bad id']), /非法/);
});

test('--run-id 空值/纯逗号/空白：解析期失败，不进入连接/扫描', async () => {
	assert.throws(() => parseArgs(['--run-id=']), /缺少值/);
	assert.throws(() => parseArgs(['--run-id', ',']), /缺少值/);
	assert.throws(() => parseArgs(['--run-id= , ']), /缺少值/);
	assert.throws(
		() => parseArgs(['--apply', '--delete-source', '--run-id=']),
		/缺少值/,
		'危险组合也不得静默变成全量',
	);
	// main 先解析参数再读环境变量/连接：这里没有设置 AI_REDIS_CLUSTER_NODES，
	// 若错误信息是节点缺失而不是 --run-id，说明作用域校验发生得太晚。
	await assert.rejects(() => migration.main(['--run-id=']), /缺少值/);
});

test('目标值相同但 PTTL 明显短于源 → 拒删且源保留', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	assert.equal((await runMigration(cluster, { apply: true })).ok, true);
	await cluster.pexpire(tagged('run-1', family('run')), 5_000);

	await assert.rejects(
		() => runMigration(cluster, { apply: true, deleteSource: true }),
		/PTTL|TTL/,
	);
	assert.notEqual(await cluster.type(oldKeyFor('ai:run:', 'run-1')), 'none');
	assert.notEqual(await cluster.type(tagged('run-1', family('run'))), 'none');
});

test('source=-1 时 target 必须也 -1；target=-1 对有限源安全', async (t) => {
	await t.test('源持久而目标有限 → 拒删', async () => {
		const cluster = makeFakeCluster();
		await seedFullRun(cluster, 'run-1');
		await runMigration(cluster, { apply: true });
		await cluster.persist(oldKeyFor('ai:run:', 'run-1'));
		await cluster.pexpire(tagged('run-1', family('run')), 5_000);
		await assert.rejects(
			() => runMigration(cluster, { apply: true, deleteSource: true }),
			/PTTL|持久/,
		);
		assert.notEqual(await cluster.type(oldKeyFor('ai:run:', 'run-1')), 'none');
	});

	await t.test('源有限而目标持久 → 安全删除', async () => {
		const cluster = makeFakeCluster();
		await seedFullRun(cluster, 'run-1');
		await runMigration(cluster, { apply: true });
		await cluster.persist(tagged('run-1', family('run')));
		const cleanup = await runMigration(cluster, { apply: true, deleteSource: true });
		assert.equal(cleanup.ok, true);
		assert.equal(await cluster.type(oldKeyFor('ai:run:', 'run-1')), 'none');
	});
});

test('两阶段删除：任一目标 TTL 不足时一个源都不删', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-a');
	await seedFullRun(cluster, 'run-b');
	await runMigration(cluster, { apply: true });
	await cluster.pexpire(tagged('run-b', family('run')), 5_000);

	await assert.rejects(
		() => runMigration(cluster, { apply: true, deleteSource: true }),
		/PTTL|TTL/,
	);
	for (const runId of ['run-a', 'run-b']) {
		assert.notEqual(
			await cluster.type(oldKeyFor('ai:run:', runId)),
			'none',
			`${runId} 的源键不得被部分删除`,
		);
	}
});

test('两阶段删除：delete 前目标被改/过期 → 不删源', async (t) => {
	await t.test('目标值被改', async () => {
		const cluster = makeFakeCluster();
		await seedFullRun(cluster, 'run-1');
		const first = await runMigration(cluster, { apply: true });
		assert.equal(typeof migration.deleteVerifiedSources, 'function');
		await cluster.set(tagged('run-1', family('run')), 'CHANGED', 'PX', TTL_LONG);
		await assert.rejects(
			() => migration.deleteVerifiedSources(cluster, first.results),
			/对账|不一致/,
		);
		assert.notEqual(await cluster.type(oldKeyFor('ai:run:', 'run-1')), 'none');
	});

	await t.test('目标已过期', async () => {
		const cluster = makeFakeCluster();
		await seedFullRun(cluster, 'run-1');
		const first = await runMigration(cluster, { apply: true });
		assert.equal(typeof migration.deleteVerifiedSources, 'function');
		await cluster.del(tagged('run-1', family('run')));
		await assert.rejects(
			() => migration.deleteVerifiedSources(cluster, first.results),
			/对账|不一致|type/,
		);
		assert.notEqual(await cluster.type(oldKeyFor('ai:run:', 'run-1')), 'none');
	});
});

test('临时键首次写后带 1h 安全 TTL；源无 TTL 时目标 PERSIST', async () => {
	const cluster = makeFakeCluster();
	await seedPagedRun(cluster, 'run-big');
	let hsetCalls = 0;
	let observed = null;
	cluster.hooks.beforeHset = async ({ key }) => {
		if (key.endsWith(':__migrate_tmp__') && key.startsWith('ai:pending:')) {
			hsetCalls += 1;
			if (hsetCalls === 2) {
				observed = await cluster.pttl(key);
				throw new Error('stop-after-first-chunk');
			}
		}
	};
	await assert.rejects(
		() => runMigration(cluster, { apply: true }),
		/stop-after-first-chunk/,
	);
	assert.ok(observed > 0, `临时键在首个 chunk 后必须有安全 TTL（实际 ${observed}）`);
	assert.ok(
		observed <= SAFETY_TTL_MS && observed > SAFETY_TTL_MS - 5_000,
		`安全 TTL 应约为 1h（实际 ${observed}）`,
	);
	assert.equal(
		await cluster.type(tempKey(tagged('run-big', family('pending')))),
		'none',
		'失败路径也要清理本次临时键',
	);
	assert.equal(await cluster.type(LOCK_KEY), 'none', '失败后必须释放迁移锁');

	const persistent = makeFakeCluster();
	await seedFullRun(persistent, 'run-persist');
	await persistent.persist(oldKeyFor('ai:pending:', 'run-persist'));
	const result = await runMigration(persistent, { apply: true });
	assert.equal(result.ok, true);
	assert.equal(
		await persistent.pttl(tagged('run-persist', family('pending'))),
		-1,
		'源无 TTL 时目标必须 PERSIST',
	);
});

test('迁移锁：apply 串行化并在 finally 释放；dry-run 不碰锁', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	const result = await runMigration(cluster, { apply: true });
	assert.equal(result.ok, true);
	assert.equal(await cluster.type(LOCK_KEY), 'none', '成功路径必须释放锁');

	const locked = makeFakeCluster();
	await seedFullRun(locked, 'run-1');
	await locked.set(LOCK_KEY, 'other-token', 'PX', TTL_LONG);
	await assert.rejects(
		() => runMigration(locked, { apply: true }),
		/迁移锁/,
	);
	assert.equal(await locked.type(tagged('run-1', family('run'))), 'none');

	const dry = await runMigration(locked, {});
	assert.equal(dry.mode, 'dry-run');
	assert.equal(await locked.get(LOCK_KEY), 'other-token', 'dry-run 不得释放/改写他人的锁');
});

test('rollback + delete-source 必须显式 --run-id', async () => {
	assert.throws(
		() => parseArgs(['--rollback', '--delete-source', '--apply']),
		/必须显式 --run-id/,
	);
	const parsed = parseArgs(['--rollback', '--delete-source', '--apply', '--run-id=r1']);
	assert.deepEqual(parsed.runIds, ['r1']);

	const cluster = makeFakeCluster();
	cluster.nodes = () => [];
	// 若作用域校验晚于扫描，这里会先撞上“master 为空”而不是本错误。
	await assert.rejects(
		() => runMigration(cluster, { rollback: true, deleteSource: true, apply: true }),
		/必须显式 --run-id/,
	);
});

test('delete-source 无 apply：输出 warning，且不删源', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	const result = await runMigration(cluster, { deleteSource: true });
	assert.equal(result.mode, 'dry-run');
	assert.ok(
		result.warnings.some((warning) => /delete-source/.test(warning)),
		`warnings=${JSON.stringify(result.warnings)}`,
	);
	assert.notEqual(await cluster.type(oldKeyFor('ai:run:', 'run-1')), 'none');
});

test('Cluster nodes(master) 为空 → 直接失败，不回落单节点扫描', async () => {
	const cluster = makeFakeCluster();
	cluster.nodes = () => [];
	await assert.rejects(() => migration.discoverRunIds(cluster, {}), /master/i);
});

test('排除审计清单包含运行态索引、销毁墓碑与 activity/resume/ctx/safety/cfg/rate/migrate-lock', () => {
	for (const prefix of [
		'ai:session:runs:',
		'ai:purge:guard:',
		'ai:purge:pending:',
		'ai:purge:retry:queue',
		'ai:purge:retry:lock',
		'ai:activity:',
		'ai:observed:',
		'ai:resume:lock:',
		'ai:ctx:',
		'ai:safety:',
		'ai:cfg:ver',
		'ai:rate:',
		'ai:migrate:lock',
	]) {
		assert.ok(EXCLUDED_KEY_PREFIXES.includes(prefix), `缺少排除项 ${prefix}`);
	}
});

test('10 prefix 硬编码契约、临时键同槽、首遍精确计数', async () => {
	const expected = [
		['run', 'ai:run:', 'string'],
		['pending', 'ai:pending:', 'hash'],
		['claim', 'ai:claim:', 'hash'],
		['ledger', 'ai:ledger:', 'list'],
		['idem', 'ai:idem:', 'hash'],
		['events', 'ai:events:', 'list'],
		['seq', 'ai:seq:', 'string'],
		['xevents', 'ai:xevents:', 'stream'],
		['req', 'ai:req:', 'hash'],
		['decided', 'ai:decided:', 'hash'],
	];
	assert.deepEqual(
		MIGRATION_FAMILIES.map(({ name, prefix, type }) => [name, prefix, type]),
		expected,
	);
	for (const [, prefix] of expected) {
		assert.equal(oldKeyFor(prefix, 'r1'), `${prefix}r1`);
		assert.equal(taggedKeyFor(prefix, 'r1'), `${prefix}{run:r1}`);
		assert.equal(hashTag(taggedKeyFor(prefix, 'r1')), 'run:r1');
		assert.equal(hashTag(tempKey(taggedKeyFor(prefix, 'r1'))), 'run:r1');
	}

	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	const first = await runMigration(cluster, { apply: true });
	assert.equal(first.ok, true);
	assert.equal(first.totals.writes, 10);
	assert.equal(cluster.stats.renamenxCalls, 8);
	assert.equal(first.totals.deleted, 0);

	const second = await runMigration(cluster, { apply: true });
	assert.equal(second.totals.writes, 0);
	assert.equal(cluster.stats.renamenxCalls, 8, '第二遍不得再走 RENAMENX');
});

test('SET NX 并发返回 null：同值对账 0 写；异值失败不覆盖', async (t) => {
	await t.test('同值', async () => {
		const cluster = makeFakeCluster();
		await seedFullRun(cluster, 'run-1');
		const target = tagged('run-1', family('run'));
		let fired = false;
		cluster.hooks.beforeSet = async ({ key, value, args }) => {
			if (!fired && key === target && args.includes('NX')) {
				fired = true;
				cluster.store.set(key, {
					type: 'string',
					value: String(value),
					expiresAt: Date.now() + TTL_MEDIUM,
				});
			}
		};
		const result = await runMigration(cluster, { apply: true });
		assert.equal(result.ok, true);
		const item = result.results[0].items.find((entry) => entry.family === 'run');
		assert.equal(item.action, 'identical');
		assert.equal(item.writes, 0);
		assert.equal(await cluster.get(target), await cluster.get(oldKeyFor('ai:run:', 'run-1')));
	});

	await t.test('异值', async () => {
		const cluster = makeFakeCluster();
		await seedFullRun(cluster, 'run-1');
		const target = tagged('run-1', family('run'));
		let fired = false;
		cluster.hooks.beforeSet = async ({ key, args }) => {
			if (!fired && key === target && args.includes('NX')) {
				fired = true;
				cluster.store.set(key, {
					type: 'string',
					value: 'CONCURRENT-OTHER',
					expiresAt: Date.now() + TTL_MEDIUM,
				});
			}
		};
		await assert.rejects(
			() => runMigration(cluster, { apply: true }),
			/并发写入且内容不一致/,
		);
		assert.equal(await cluster.get(target), 'CONCURRENT-OTHER');
		assert.notEqual(await cluster.type(oldKeyFor('ai:run:', 'run-1')), 'none');
	});
});

test('RENAMENX 返回 0：同值跳过；异值失败不覆盖', async (t) => {
	await t.test('同值', async () => {
		const cluster = makeFakeCluster();
		await seedFullRun(cluster, 'run-1');
		const target = tagged('run-1', family('pending'));
		let fired = false;
		cluster.hooks.beforeRenamenx = async ({ source, destination }) => {
			if (!fired && destination === target) {
				fired = true;
				const entry = cluster.store.get(source);
				cluster.store.set(destination, entry);
			}
		};
		const result = await runMigration(cluster, { apply: true });
		assert.equal(result.ok, true);
		const item = result.results[0].items.find((entry) => entry.family === 'pending');
		assert.equal(item.action, 'identical');
		assert.equal(item.writes, 0);
		assert.equal(
			await cluster.hgetall(target).then((value) => value['tc-1']),
			'{"toolName":"sheet.read","args":{"a":1}}',
		);
	});

	await t.test('异值', async () => {
		const cluster = makeFakeCluster();
		await seedFullRun(cluster, 'run-1');
		const target = tagged('run-1', family('pending'));
		let fired = false;
		cluster.hooks.beforeRenamenx = async ({ destination }) => {
			if (!fired && destination === target) {
				fired = true;
				cluster.store.set(destination, {
					type: 'hash',
					value: new Map([['tc-1', 'CONCURRENT-OTHER']]),
					expiresAt: null,
				});
			}
		};
		await assert.rejects(
			() => runMigration(cluster, { apply: true }),
			/并发写入且内容不一致/,
		);
		assert.equal((await cluster.hgetall(target))['tc-1'], 'CONCURRENT-OTHER');
		assert.notEqual(await cluster.type(oldKeyFor('ai:pending:', 'run-1')), 'none');
	});
});

test('apply 中途失败：源全部保留、partial results 正确、锁释放', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-a');
	await seedFullRun(cluster, 'run-b');
	let failed = false;
	cluster.hooks.beforeHset = async ({ key }) => {
		if (!failed && key.startsWith('ai:pending:{run:run-b}')) {
			failed = true;
			throw new Error('injected-HSET-failure');
		}
	};

	let error;
	try {
		await runMigration(cluster, { apply: true, deleteSource: true });
	} catch (caught) {
		error = caught;
	}
	assert.ok(error, '中途失败必须抛出');
	assert.match(error.message, /injected-HSET-failure/);
	assert.equal(error.partialResults.length, 1);
	assert.equal(error.partialResults[0].runId, 'run-a');
	assert.equal(await cluster.type(oldKeyFor('ai:run:', 'run-a')), 'string');
	assert.equal(await cluster.type(tagged('run-a', family('run'))), 'string');
	assert.equal(await cluster.type(oldKeyFor('ai:run:', 'run-b')), 'string');
	assert.equal(
		await cluster.type(tempKey(tagged('run-b', family('pending')))),
		'none',
		'失败写入的临时键必须清理',
	);
	assert.equal(await cluster.type(LOCK_KEY), 'none', '异常路径也必须释放迁移锁');
});

test('分页：stream 1200 / hash 1200 / list 2500 / SCAN 单 master 2500', async () => {
	const cluster = makeFakeCluster();
	await seedPagedRun(cluster, 'run-big');
	const result = await runMigration(cluster, { apply: true });
	assert.equal(result.ok, true);

	assert.equal(await cluster.xlen(tagged('run-big', family('xevents'))), 1200);
	const tail = await cluster.xrange(tagged('run-big', family('xevents')), '1190-0', '+');
	assert.equal(tail.length, 11);
	assert.equal(tail[0][0], '1190-0');
	assert.equal(tail[10][0], '1200-0');

	assert.equal(await cluster.hlen(tagged('run-big', family('pending'))), 1200);
	const hash = await cluster.hgetall(tagged('run-big', family('pending')));
	assert.equal(hash.f0, 'v0');
	assert.equal(hash.f1199, 'v1199');

	assert.equal(await cluster.llen(tagged('run-big', family('ledger'))), 2500);
	const list = await cluster.lrange(tagged('run-big', family('ledger')), 0, -1);
	assert.equal(list[0], 'item-0');
	assert.equal(list[2499], 'item-2499');

	// 真跨页：单 master > SCAN_BATCH(1000)，直接统计 ai:run:* 的游标推进，
	// 不用“10 前缀 × 2 master”的总 scanCalls 冒充翻页。
	const scan = makeFakeCluster();
	scan.nodes = () => [scan];
	for (let i = 0; i < 2500; i += 1) {
		await scan.set(`ai:run:scan-${String(i).padStart(5, '0')}`, 'x', 'PX', TTL_LONG);
	}
	const found = await migration.discoverRunIds(scan, {});
	assert.equal(found.size, 2500);
	const runPrefixCalls = scan.stats.scanByPattern['ai:run:*'] ?? 0;
	const runPrefixAdvances = scan.stats.cursorAdvances['ai:run:*'] ?? 0;
	assert.ok(
		runPrefixCalls >= 3,
		`ai:run:* 单 master 2500 key 至少 3 页（calls=${runPrefixCalls}）`,
	);
	assert.ok(
		runPrefixAdvances >= 2,
		`ai:run:* 游标至少推进 2 次（advances=${runPrefixAdvances}）`,
	);
});

test('DEL 阶段第 2 个失败：允许部分删除，已删项均校验；重跑收敛', async () => {
	const cluster = makeFakeCluster();
	await seedMinimalRun(cluster, 'run-min');
	cluster.hooks.beforeDel = async ({ key }) => {
		if (key === oldKeyFor('ai:seq:', 'run-min')) {
			throw new Error('injected-second-DEL-failure');
		}
	};

	let error;
	try {
		await runMigration(cluster, { apply: true, deleteSource: true });
	} catch (caught) {
		error = caught;
	}
	assert.ok(error);
	assert.match(error.message, /injected-second-DEL-failure/);
	assert.equal(await cluster.type(oldKeyFor('ai:run:', 'run-min')), 'none');
	assert.notEqual(await cluster.type(oldKeyFor('ai:seq:', 'run-min')), 'none');
	assert.notEqual(await cluster.type(oldKeyFor('ai:xevents:', 'run-min')), 'none');
	assert.deepEqual(
		error.partialResults[0].deleted,
		[oldKeyFor('ai:run:', 'run-min')],
		'partialResults 必须记录已删项',
	);
	assert.equal(await cluster.type(LOCK_KEY), 'none', 'DEL 失败也要释放锁');

	delete cluster.hooks.beforeDel;
	const retry = await runMigration(cluster, { apply: true, deleteSource: true });
	assert.equal(retry.ok, true);
	assert.equal(retry.totals.deleted, 2, '重跑只删剩余 2 个源键');
	assert.equal(await cluster.type(oldKeyFor('ai:seq:', 'run-min')), 'none');
	assert.equal(await cluster.type(oldKeyFor('ai:xevents:', 'run-min')), 'none');
});

test('DEL 阶段逐项复验：phase1 后目标被改，不删任何源', async () => {
	const cluster = makeFakeCluster();
	await seedMinimalRun(cluster, 'run-min');
	const first = await runMigration(cluster, { apply: true });
	const targetRun = tagged('run-min', family('run'));
	let fired = false;
	cluster.hooks.beforePttl = async ({ key }) => {
		if (!fired && key === targetRun) {
			fired = true;
			cluster.store.set(targetRun, {
				type: 'string',
				value: 'CHANGED-AFTER-PHASE1',
				expiresAt: Date.now() + TTL_LONG,
			});
		}
	};
	await assert.rejects(
		() => migration.deleteVerifiedSources(cluster, first.results),
		/DEL 阶段复验失败/,
	);
	for (const name of ['run', 'seq', 'xevents']) {
		assert.notEqual(
			await cluster.type(oldKeyFor(family(name).prefix, 'run-min')),
			'none',
			`${name} 源键不得被删除`,
		);
	}
});

test('迁移锁续租：延长 TTL；错 token 置 lost 且不误删', async (t) => {
	await t.test('续租延长 TTL', async () => {
		assert.equal(typeof migration.renewMigrationLock, 'function');
		const cluster = makeFakeCluster();
		const lock = await migration.acquireMigrationLock(cluster);
		await cluster.pexpire(LOCK_KEY, 1_000);
		assert.equal(await migration.renewMigrationLock(cluster, lock), true);
		const pttl = await cluster.pttl(LOCK_KEY);
		assert.ok(pttl > 50_000, `续租后 PTTL 应接近 60s，实际 ${pttl}`);
		await migration.releaseMigrationLock(cluster, lock);
		assert.equal(await cluster.type(LOCK_KEY), 'none');
	});

	await t.test('错 token 置 lost 且释放不误删', async () => {
		assert.equal(typeof migration.renewMigrationLock, 'function');
		const cluster = makeFakeCluster();
		const lock = await migration.acquireMigrationLock(cluster);
		await cluster.set(LOCK_KEY, 'other-token', 'PX', TTL_LONG);
		assert.equal(await migration.renewMigrationLock(cluster, lock), false);
		assert.ok(lock.lost, '错 token 续租必须置 lost');
		await migration.releaseMigrationLock(cluster, lock);
		assert.equal(await cluster.get(LOCK_KEY), 'other-token', '不得删除他人的锁');
		await cluster.del(LOCK_KEY);
	});
});

test('锁丢失：item 检查点停止，下一个 item 不写', async () => {
	assert.equal(typeof migration.applyRun, 'function');
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	const plan = await migration.buildPlan(cluster, { runIds: ['run-1'] });
	const lock = { lost: null };
	cluster.hooks.beforeSet = async ({ key }) => {
		if (key === tagged('run-1', family('run'))) {
			lock.lost = 'lock-lost-after-run-item';
		}
	};
	await assert.rejects(
		() => migration.applyRun(cluster, plan.runs[0], lock),
		/lock-lost-after-run-item/,
	);
	assert.notEqual(
		await cluster.type(tagged('run-1', family('run'))),
		'none',
		'当前 item 允许完成',
	);
	assert.equal(
		await cluster.type(tagged('run-1', family('pending'))),
		'none',
		'下一个 item 不得开始写',
	);
});

test('锁丢失：chunk 检查点停止，临时键不认领', async () => {
	assert.equal(typeof migration.applyRun, 'function');
	const cluster = makeFakeCluster();
	await seedPagedRun(cluster, 'run-big');
	const plan = await migration.buildPlan(cluster, { runIds: ['run-big'] });
	const lock = { lost: null };
	const temp = tempKey(tagged('run-big', family('pending')));
	let calls = 0;
	cluster.hooks.beforeHset = async ({ key }) => {
		if (key === temp) {
			calls += 1;
			if (calls === 2) {
				lock.lost = 'lock-lost-mid-hash';
			}
		}
	};
	await assert.rejects(
		() => migration.applyRun(cluster, plan.runs[0], lock),
		/lock-lost-mid-hash/,
	);
	assert.equal(await cluster.type(temp), 'none', '已写 chunk 的临时键必须清理');
	assert.equal(
		await cluster.type(tagged('run-big', family('pending'))),
		'none',
		'锁丢失后不得认领目标',
	);
});

test('临时键首 chunk + PEXPIRE 原子：崩溃不留无 TTL 孤儿', async () => {
	const cluster = makeFakeCluster();
	await seedPagedRun(cluster, 'run-big');
	const temp = tempKey(tagged('run-big', family('pending')));
	let fired = false;
	cluster.hooks.beforePexpire = async ({ key }) => {
		if (!fired && key === temp) {
			fired = true;
			cluster.stats.ignoreDelete = true; // 模拟异常后进程立即死亡，catch 的 DEL 不生效
			throw new Error('crash-before-first-ttl');
		}
	};
	await assert.rejects(
		() => runMigration(cluster, { apply: true }),
		/crash-before-first-ttl/,
	);
	assert.equal(
		await cluster.type(temp),
		'none',
		'首 chunk 与 PEXPIRE 必须同一 MULTI，崩溃回滚后不得留下无 TTL 孤儿',
	);
});

test('临时键后续批次重续 TTL：重建后仍有安全 TTL', async () => {
	const cluster = makeFakeCluster();
	await seedPagedRun(cluster, 'run-big');
	const temp = tempKey(tagged('run-big', family('pending')));
	let calls = 0;
	cluster.hooks.beforeHset = async ({ key }) => {
		if (key === temp) {
			calls += 1;
			if (calls === 2) {
				cluster.store.delete(key); // 模拟安全 TTL 到期/键被删，后续 chunk 重建
			}
			if (calls === 3) {
				cluster.stats.ignoreDelete = true;
				throw new Error('crash-after-rebuild');
			}
		}
	};
	await assert.rejects(
		() => runMigration(cluster, { apply: true }),
		/crash-after-rebuild/,
	);
	assert.notEqual(await cluster.type(temp), 'none');
	const pttl = await cluster.pttl(temp);
	assert.ok(pttl > 0, `重建后的临时键必须重新带 TTL（实际 ${pttl}）`);
});

test('source=-1 且 target=-1 → 允许删除', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	await runMigration(cluster, { apply: true });
	await cluster.persist(oldKeyFor('ai:run:', 'run-1'));
	await cluster.persist(tagged('run-1', family('run')));
	const cleanup = await runMigration(cluster, { apply: true, deleteSource: true });
	assert.equal(cleanup.ok, true);
	assert.equal(await cluster.type(oldKeyFor('ai:run:', 'run-1')), 'none');
});

test('plan 输出 ttlShort/targetPttl 字段', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	await cluster.set(tagged('run-1', family('seq')), '9', 'PX', TTL_MEDIUM);
	const result = await runMigration(cluster, {});
	const seqItem = result.plan.runs[0].items.find((item) => item.family === 'seq');
	assert.equal(seqItem.ttlShort, true);
	assert.ok(seqItem.targetPttl > 0);
	const text = formatPlan(result);
	assert.match(text, /ttl_short=true/);
	assert.match(text, /target_pttl=\d+/);
	assert.match(text, /target_pttl=-/);
});

test('排除清单包含裸 ai:lease:epoch', () => {
	assert.ok(EXCLUDED_KEY_PREFIXES.includes('ai:lease:epoch'));
});

test('参数解析：--tenant-id/--user-id 必须成对，且产出 {tenantId,userId} 身份', () => {
	const parsed = parseArgs(['--tenant-id', '2', '--user-id=202']);
	assert.deepEqual(parsed.identity, { tenantId: 2, userId: 202 });
	assert.throws(() => parseArgs(['--tenant-id', '2']), /成对/);
	assert.throws(() => parseArgs(['--tenant-id', '0', '--user-id', '1']), /正整数/);
});

test('键形：带身份的目标键 tag 同时含身份与 runId，legacy 形仍无身份', () => {
	assert.equal(taggedKeyFor('ai:run:', 'r1'), 'ai:run:{run:r1}');
	assert.equal(
		taggedKeyFor('ai:run:', 'r1', { tenantId: 2, userId: 202 }),
		'ai:run:{2:202:run:r1}',
	);
	assert.equal(parseTaggedKey('ai:run:{2:202:run:r1}', 'ai:run:'), 'r1');
	assert.deepEqual(parseTaggedIdentity('ai:run:{2:202:run:r1}', 'ai:run:'), {
		tenantId: 2,
		userId: 202,
	});
	assert.equal(parseTaggedIdentity('ai:run:{run:r1}', 'ai:run:'), null);
});

// ── ai:runs 索引：正向成员重写 / rollback 双形回退 ─────────────────────────

const IDENTITY = Object.freeze({ tenantId: 2, userId: 202 });

/** 对比“除记录身份外的全部字段”，避免把 JSON 序列化差异当成迁移改动。 */
function snapshotBody(raw) {
	const snapshot = JSON.parse(raw);
	const { tenantId, userId, ...body } = snapshot;
	return body;
}

/**
 * 与 `RunStore.readSnapshot` 相同口径：先按显式身份取键，再复核记录里的
 * `tenantId/userId`；两步都通过才返回快照（0/0 记录在显式身份读口下按不存在处理）。
 */
async function readSnapshotAsRunStoreWould(cluster, runId, identity) {
	const raw = await cluster.get(taggedKeyFor('ai:run:', runId, identity));
	if (raw === null) {
		return null;
	}
	const snapshot = JSON.parse(raw);
	const tenantId = Number(snapshot.tenantId ?? 0);
	const userId = Number(snapshot.userId ?? 0);
	return tenantId === identity.tenantId && userId === identity.userId ? snapshot : null;
}

test('正向带身份迁移把 ai:runs 成员重写为 t/u/runId，且快照身份被写入并可被 RunStore 显式读口命中', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	await cluster.sadd('ai:runs', 'run-1', '9/909/run-other');
	const sourceRaw = await cluster.get(oldKeyFor('ai:run:', 'run-1'));

	const result = await runMigration(cluster, { apply: true, identity: IDENTITY });

	assert.equal(result.ok, true);
	assert.deepEqual(
		await cluster.smembers('ai:runs'),
		['2/202/run-1', '9/909/run-other'],
		'只重写本次迁移的成员，其他身份的成员原样保留',
	);

	const targetRaw = await cluster.get(taggedKeyFor('ai:run:', 'run-1', IDENTITY));
	assert.notEqual(targetRaw, null, '目标快照必须存在');
	const targetSnapshot = JSON.parse(targetRaw);
	assert.equal(targetSnapshot.tenantId, IDENTITY.tenantId, '快照 tenantId 必须写成命令行声明值');
	assert.equal(targetSnapshot.userId, IDENTITY.userId, '快照 userId 必须写成命令行声明值');
	assert.deepEqual(
		snapshotBody(targetRaw),
		snapshotBody(sourceRaw),
		'除 tenantId/userId 外，其他字段必须逐值不变',
	);
	assert.notEqual(targetRaw, sourceRaw, '带身份迁移不得逐字节复制 0/0 快照');

	// RunStore 式显式身份读取：键在 + 记录身份复核通过 ⇒ 可命中（不是只把索引成员 split 一遍）。
	const readable = await readSnapshotAsRunStoreWould(cluster, 'run-1', IDENTITY);
	assert.notEqual(readable, null, '显式身份读口必须命中迁移后的快照');
	assert.equal(readable.runId, 'run-1');
	assert.equal(readable.state, 'SUSPENDED');
	assert.equal(readable.text, '你好');
	// 源键保持原样（0/0），证明身份是写进目标的，不是改了源。
	const sourceSnapshot = JSON.parse(sourceRaw);
	assert.equal(sourceSnapshot.tenantId, undefined);
	assert.equal(sourceSnapshot.userId, undefined);

	const second = await runMigration(cluster, { apply: true, identity: IDENTITY });
	assert.equal(second.ok, true);
	assert.equal(second.totals.writes, 0);
	assert.equal(second.plan.runs[0].runIndex.action, 'identical');
	assert.equal(second.results[0].items[0].action, 'identical', '第二遍必须按身份语义对账为 identical');
});

test('未声明身份的 legacy 迁移不改写快照身份，并明确仅归档、不属于新身份', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	const sourceRaw = await cluster.get(oldKeyFor('ai:run:', 'run-1'));

	const result = await runMigration(cluster, { apply: true });

	assert.equal(result.ok, true);
	assert.ok(
		result.warnings.some((warning) => /仅具归档价值/.test(warning)
			&& /不属于任何新身份/.test(warning)),
		`必须明确 legacy 结果仅归档、不属于新身份：${JSON.stringify(result.warnings)}`,
	);
	const legacyRaw = await cluster.get(taggedKeyFor('ai:run:', 'run-1'));
	assert.equal(legacyRaw, sourceRaw, '未声明身份时必须逐字节保留，不得伪造 tenantId/userId');
	const legacySnapshot = JSON.parse(legacyRaw);
	assert.equal(legacySnapshot.tenantId, undefined);
	assert.equal(legacySnapshot.userId, undefined);
});

test('delete-source 前复核目标快照身份：目标回退成 0/0 时拒绝删除源', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	const sourceRaw = await cluster.get(oldKeyFor('ai:run:', 'run-1'));
	const first = await runMigration(cluster, { apply: true, identity: IDENTITY });
	assert.equal(first.ok, true);
	const targetKey = taggedKeyFor('ai:run:', 'run-1', IDENTITY);
	// 把目标写回旧 0/0 快照：键在，但记录身份不可证明 ⇒ 显式身份读口读不到，delete-source 必须拒绝。
	await cluster.set(targetKey, sourceRaw, 'PX', TTL_LONG);

	await assert.rejects(
		() => migration.deleteVerifiedSources(cluster, first.results),
		/身份|对账|不一致/,
	);
	assert.notEqual(
		await cluster.type(oldKeyFor('ai:run:', 'run-1')),
		'none',
		'身份复核失败时不得删除源快照',
	);
});

test('写后回读校验：目标快照身份被并发改成别的身份时 apply 失败且不删源', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	const targetKey = taggedKeyFor('ai:run:', 'run-1', IDENTITY);
	let fired = false;
	cluster.hooks.afterSet = async ({ key, store }) => {
		if (!fired && key === targetKey) {
			fired = true;
			const entry = store.get(key);
			const snapshot = JSON.parse(entry.value);
			entry.value = JSON.stringify({ ...snapshot, tenantId: 9 });
		}
	};

	await assert.rejects(
		() => runMigration(cluster, { apply: true, deleteSource: true, identity: IDENTITY }),
		/身份/,
	);
	assert.notEqual(
		await cluster.type(oldKeyFor('ai:run:', 'run-1')),
		'none',
		'写后身份校验失败时不得删除源快照',
	);
});

test('已迁移但索引陈旧：--run-id 带身份重跑只修 ai:runs，不重写数据键', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	await cluster.sadd('ai:runs', 'run-1');
	await runMigration(cluster, { apply: true, deleteSource: true, identity: IDENTITY });

	// 模拟“数据已迁完、索引仍是旧成员”（旧版本工具/手工回填留下的现场）。
	await cluster.srem('ai:runs', '2/202/run-1');
	await cluster.sadd('ai:runs', 'run-1');

	const result = await runMigration(cluster, {
		apply: true,
		runIds: ['run-1'],
		identity: IDENTITY,
	});

	assert.equal(result.ok, true);
	assert.equal(result.plan.runs[0].status, 'ready', '仅有索引待修时也必须可 apply');
	assert.equal(result.plan.runs[0].runIndex.action, 'rewrite');
	assert.equal(result.totals.writes, 0, '数据键已迁移，不得重写');
	assert.deepEqual(await cluster.smembers('ai:runs'), ['2/202/run-1']);
	assert.equal(
		JSON.parse(await cluster.get(taggedKeyFor('ai:run:', 'run-1', IDENTITY))).tenantId,
		IDENTITY.tenantId,
		'首次 apply --delete-source 必须写入快照身份并据此通过删除校验',
	);
	assert.equal(
		await cluster.type(oldKeyFor('ai:run:', 'run-1')),
		'none',
		'身份回读通过后 --delete-source 必须真正删除源',
	);
});

test('rollback 声明身份时回退到 legacy tagged 源（带身份/legacy 双形回退）', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	// 先做 legacy 形迁移并删源：现场只有 {run:run-1}，没有带身份的源键。
	const forward = await runMigration(cluster, { apply: true, deleteSource: true });
	assert.equal(forward.ok, true);

	const rollback = await runMigration(cluster, { rollback: true, apply: true, identity: IDENTITY });

	assert.equal(rollback.ok, true);
	assert.equal(rollback.direction, 'rollback');
	assert.ok(rollback.totals.writes > 0);
	await assertRunCopied(cluster, 'run-1');
	assert.notEqual(await cluster.type(oldKeyFor('ai:run:', 'run-1')), 'none');
});

test('rollback 带身份把 ai:runs 成员恢复为裸 runId', async () => {
	const cluster = makeFakeCluster();
	await seedFullRun(cluster, 'run-1');
	await cluster.sadd('ai:runs', 'run-1');
	await runMigration(cluster, { apply: true, deleteSource: true, identity: IDENTITY });
	assert.deepEqual(await cluster.smembers('ai:runs'), ['2/202/run-1']);

	const rollback = await runMigration(cluster, { rollback: true, apply: true, identity: IDENTITY });

	assert.equal(rollback.ok, true);
	assert.equal(await cluster.type(oldKeyFor('ai:run:', 'run-1')), 'string');
	assert.deepEqual(await cluster.smembers('ai:runs'), ['run-1']);
});
