#!/usr/bin/env node
/**
 * AI run 键族离线迁移：旧裸键 → tagged 键。
 *
 * **键形（租户任务2 起）**：`<prefix>{<tenantId>:<userId>:run:<runId>}`。
 * 旧数据（`ai:run:<runId>` 之类）**没有身份**，而新路径的键必须带身份 —— 因此正向迁移
 * 必须由操作者**显式声明目标身份**（`--tenant-id/--user-id`）；未声明时工具只产出旧 tagged 形
 * `{run:<runId>}`，那是 **legacy 命名空间**：带身份的新路径永远不会读到它。
 * 不声明身份就得不到隔离（同 runId 的两个租户仍会落到同一个键上），这一点写入 dry-run 报告。
 *
 * **ai:runs 索引（正向带身份必做）**：`ai:runs` SET 的成员是复合成员
 * `tenantId/userId/runId`（与 `RunKeyScope.indexMember` 同一口径）。旧成员的裸 `runId`
 * 没有归属，`allRunRecords()` 不会返回它（启动恢复因此发现不了迁移后的轮次）。所以正向带身份
 * 迁移在数据键落位后，把本次 run 的成员重写为 `t/u/runId`（SREM 旧形 + SADD 新形，同一 MULTI）；
 * 只动本次 run 的成员，不扫描租户列表、不碰其他身份的成员。rollback 声明身份时反向恢复为裸
 * `runId`。未声明身份时（legacy 命名空间）索引成员保持裸 `runId`，不做重写。
 *
 * **快照身份（正向带身份必做）**：`ai:run` 的值是快照 JSON，旧数据没有 `tenantId/userId`。
 * 写目标前必须解析 JSON 并把记录身份置为声明值，写后回读校验“记录身份 = 声明值、其余字段不变”；
 * 校验不通过即失败，也不得进入 delete-source。未声明身份时逐字节保留（含 0/0），迁移结果仅具
 * 归档价值、不属于任何新身份（dry-run/apply 都会告警）。
 *
 * 迁移 10 族（精确集合）：
 *   STRING  ai:run: / ai:seq:
 *   HASH    ai:pending: / ai:claim: / ai:idem: / ai:req: / ai:decided:
 *   LIST    ai:ledger: / ai:events:
 *   STREAM  ai:xevents:
 * 集合口径：`deleteRun` 的 13 键 = `SessionKeyRegistry` 的 run 级 13 键（run/pending/
 * events/seq/xevents/req/decided/fence/lease:epoch(per-run)/cancel/beat/ledger/idem）
 * − beat/cancel + claim/lease:run。本工具再减去 fence/lease:run/per-run epoch，剩
 * 9 个 Registry 键 + claim = 10 族。`claim` 不在 Registry 13 键里，不能混写。
 *
 * 明确不搬（数据键）：ai:fence:run: / ai:lease:run: / ai:lease:epoch:（含旧全局
 * ai:lease:epoch）、ai:runs 的 10 族数据、ai:session:current:、ai:session:runs:、ai:purge:guard:、
 * ai:purge:pending:、ai:purge:retry:queue、ai:purge:retry:lock、ai:beat:、ai:cancel:、ai:lock:、
 * ai:activity:、ai:resume:lock:（历史键）、ai:ctx:、ai:safety:、ai:cfg:ver、ai:rate:；
 * chat:mem legacy 由新仓储回退读 + saveAll 自然迁移，本工具不碰。
 * `ai:runs` 键本身不参与 SCAN 发现/值复制；只有上面的成员重写。
 *
 * 安全口径：
 * - 默认 dry-run；--apply 才写，--rollback 反向（tagged → 旧裸键）。
 * - 节点只从 AI_REDIS_CLUSTER_NODES 读，无默认值；Cluster 的 master 列表为空直接失败。
 * - 目标不存在才写；已存在则逐值对账，一致跳过，不一致即失败且绝不覆盖。
 * - seq 与 xevents 必须成对；任一缺失则整 run 跳过并报告（不留下活快照无帧）。
 * - 按类型保留全部值 / 字段 / 顺序 / 显式 stream ID / PTTL。
 * - 源默认保留；--delete-source 先做“全部候选值 + PTTL 充分性”校验：校验阶段失败
 *   不删任何源；DEL 阶段失败可能已删部分源（每个已删项在删除前均已复验），重跑会收敛。
 * - 发现 key 只用 SCAN（逐 master），禁止 KEYS。
 * - apply 用 `SET ai:migrate:lock <token> NX PX` 串行化（全局单锁，跨 run 也互斥），
 *   按 token 续租/释放；锁丢失后在下一个 item/chunk 检查点停止，不承诺当前命令回滚。
 * - 任一错误即停并以非 0 退出，不打印成功结论。
 *
 * 离线窗口与边界：
 * - 写入前停止业务写入；同一时间只允许一个迁移进程（工具锁 + 临时键共同约束）。
 * - 临时键的每个 chunk 与 1h 安全 TTL 用同一 MULTI/EXEC 原子提交，后续批次继续原子重续；
 *   全部写完后按源 PTTL 覆盖，源无 TTL 时 PERSIST。正常失败/崩溃不会留下“已写但无 TTL”的
 *   临时键；重跑只清理当次涉及的临时键，不保证清理所有历史残留。
 * - 只按 UTF-8 文本复制（本仓值是 JSON 字符串），不做二进制保真。
 * - 不迁移 Stream 消费组（XGROUP，本仓未使用）；`last-generated-id` 只随显式 entry ID
 *   重建，若源历史上做过尾部 XDEL，内部 last-generated-id 可能大于末条 entry ID，不保留。
 * - rollback 的旧裸键目标无法与临时键同槽，退回“先查后写”，依赖离线窗口。
 *
 * 用法：
 *   node scripts/redis-run-migration.mjs [--run-id <id>[,<id>]] [--apply] [--delete-source]
 *   node scripts/redis-run-migration.mjs --rollback --run-id <id>[,<id>] [--apply] [--delete-source]
 */

import { randomUUID } from 'node:crypto';
import process from 'node:process';
import { pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

export const NODES_ENV = 'AI_REDIS_CLUSTER_NODES';

/** 迁移工具自己的串行锁（不属于产品键族）。 */
export const MIGRATION_LOCK_KEY = 'ai:migrate:lock';

/** 全局轮次索引（SET；成员 = `tenantId/userId/runId`，无身份过渡期是裸 `runId`）。 */
export const RUN_INDEX_KEY = 'ai:runs';

/** 迁移工具自己的串行锁 TTL。 */
export const MIGRATION_LOCK_TTL_MS = 60_000;
const MIGRATION_LOCK_RENEW_MS = 20_000;

/** 参与迁移的 run 级键族（顺序 = 报告顺序）。 */
export const MIGRATION_FAMILIES = Object.freeze([
	Object.freeze({ name: 'run', prefix: 'ai:run:', type: 'string' }),
	Object.freeze({ name: 'pending', prefix: 'ai:pending:', type: 'hash' }),
	Object.freeze({ name: 'claim', prefix: 'ai:claim:', type: 'hash' }),
	Object.freeze({ name: 'ledger', prefix: 'ai:ledger:', type: 'list' }),
	Object.freeze({ name: 'idem', prefix: 'ai:idem:', type: 'hash' }),
	Object.freeze({ name: 'events', prefix: 'ai:events:', type: 'list' }),
	Object.freeze({ name: 'seq', prefix: 'ai:seq:', type: 'string' }),
	Object.freeze({ name: 'xevents', prefix: 'ai:xevents:', type: 'stream' }),
	Object.freeze({ name: 'req', prefix: 'ai:req:', type: 'hash' }),
	Object.freeze({ name: 'decided', prefix: 'ai:decided:', type: 'hash' }),
]);

/** 明确不参与迁移的键族（仅用于报告/自检，不会被扫描或写入）。 */
export const EXCLUDED_KEY_PREFIXES = Object.freeze([
	'ai:fence:run:',
	'ai:lease:run:',
	'ai:lease:epoch:',
	'ai:lease:epoch',
	// 全局轮次索引：不是 10 族数据键（不按族 SCAN/复制）；带身份迁移时单独重写成员
	// （SREM 裸 runId + SADD tenantId/userId/runId），见 planRunIndex/applyRunIndex。
	'ai:runs',
	'ai:session:current:',
	'ai:session:runs:',
	'ai:purge:guard:',
	'ai:purge:pending:',
	'ai:purge:retry:queue',
	'ai:purge:retry:lock',
	'ai:beat:',
	'ai:cancel:',
	'ai:lock:',
	'ai:activity:',
	// 结算观测位（park 结算审计面）：一次性仲裁位，跨世代没有要保留的事实 ⇒ 不搬。
	// 生命周期 = 会话销毁时由销毁方 DEL（SettlementObservationStore#purge / observedKey），TTL 仅兜底。
	'ai:observed:',
	'ai:resume:lock:',
	'ai:ctx:',
	'ai:safety:',
	'ai:cfg:ver',
	'ai:rate:',
	MIGRATION_LOCK_KEY,
	'chat:mem:',
]);

const SCAN_BATCH = 1000;
const STREAM_PAGE = 500;
const HASH_BATCH = 500;
const LIST_BATCH = 1000;
/** 临时键每个 MULTI 最多打包多少条 stream entry（保持显式 ID，同 key 同槽）。 */
const STREAM_TX_BATCH = 100;

/** 删除源前允许目标 PTTL 比源短的容差（时钟/续期抖动）。 */
export const TTL_TOLERANCE_MS = 1_000;

/** 临时键首次写后的安全 TTL（防崩溃永久残留；成功后按源 PTTL 覆盖或 PERSIST）。 */
export const TEMP_SAFETY_TTL_MS = 60 * 60 * 1000;

/**
 * 临时键后缀：写入先落在与目标同槽的临时键，再用 RENAMENX 原子认领。
 * 正向前缀带 `{run:<runId>}`，临时键与目标同槽；rollback 的目标是旧裸键（无法同槽），
 * 退回"先查后写"，仅依赖离线窗口。
 */
const TEMP_SUFFIX = ':__migrate_tmp__';

/** 迁移期错误：消息可直接给运维看。 */
export class MigrationError extends Error {
	constructor(message, details = {}) {
		super(message);
		this.name = 'MigrationError';
		this.details = details;
	}
}

// ── 配置 ───────────────────────────────────────────────────────────────────

/**
 * 解析 AI_REDIS_CLUSTER_NODES（逗号分隔 host:port）。
 * 缺失/空白/格式错误一律失败 —— 绝不回落 localhost 或任何默认端口。
 */
export function nodesFromEnv(env = process.env) {
	const raw = env[NODES_ENV];
	if (typeof raw !== 'string' || raw.trim() === '') {
		throw new MigrationError(
			`${NODES_ENV} 未配置：本工具没有默认节点，请显式提供 host:port[,host:port...]`,
		);
	}
	const parts = raw.split(',').map((s) => s.trim()).filter(Boolean);
	if (parts.length === 0) {
		throw new MigrationError(`${NODES_ENV} 为空：本工具没有默认节点`);
	}
	return parts.map((part) => {
		const match = /^\[([^\]]+)\]:(\d+)$/.exec(part) || /^([^:]+):(\d+)$/.exec(part);
		if (!match) {
			throw new MigrationError(`${NODES_ENV} 节点格式非法（要求 host:port）：${part}`);
		}
		const port = Number(match[2]);
		if (!Number.isInteger(port) || port < 1 || port > 65535) {
			throw new MigrationError(`${NODES_ENV} 节点端口非法：${part}`);
		}
		return { host: match[1], port };
	});
}

export function isValidRunId(runId) {
	return typeof runId === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]*$/.test(runId);
}

export function oldKeyFor(prefix, runId) {
	return prefix + runId;
}

/**
 * tagged 键：{@code <prefix>{[<tenantId>:<userId>:]run:<runId>}}。
 *
 * @param identity {tenantId, userId} 或 null/undefined（null = legacy tagged 形，无身份）
 */
export function taggedKeyFor(prefix, runId, identity = null) {
	const segment = identity ? `${identity.tenantId}:${identity.userId}:` : '';
	return `${prefix}{${segment}run:${runId}}`;
}

/**
 * `ai:runs` 复合成员：带身份 = `<tenantId>/<userId>/<runId>`，无身份 = 旧形裸 `runId`。
 * 与 `RunKeyScope.indexMember` / `parseIndexMember` 同一口径（allRunRecords 的唯一发现面）。
 */
export function indexMemberFor(runId, identity = null) {
	return identity ? `${identity.tenantId}/${identity.userId}/${runId}` : runId;
}

/**
 * 解析 run 快照（`ai:run:` 的 STRING 值）。
 * 身份迁移必须真正读记录，不能按字节搬：坏 JSON 直接失败，避免把不可读快照写进新身份键。
 */
function parseRunSnapshot(raw, key) {
	if (raw === null || raw === undefined) {
		throw new MigrationError(`快照缺失：${key}`);
	}
	let snapshot;
	try {
		snapshot = JSON.parse(raw);
	} catch (error) {
		throw new MigrationError(`快照不是合法 JSON：${key}（${error.message}）`);
	}
	if (snapshot === null || typeof snapshot !== 'object' || Array.isArray(snapshot)) {
		throw new MigrationError(`快照不是 JSON 对象：${key}`);
	}
	return snapshot;
}

/** 记录里的身份（缺失按 0/0）；与 `RunStore` 的 `recordIdentityOf` 同口径。 */
function snapshotIdentity(snapshot) {
	return {
		tenantId: Number(snapshot.tenantId ?? 0),
		userId: Number(snapshot.userId ?? 0),
	};
}

/** 除 tenantId/userId 外的全部字段（用于“其他字段不变”的对账）。 */
function snapshotBody(snapshot) {
	const { tenantId, userId, ...body } = snapshot;
	return body;
}

/**
 * 正向带身份迁移的快照改写：把记录身份置为命令行声明值。
 *
 * 源记录允许 0/0（旧数据无身份）或已是同一声明身份（重跑）；已是其他身份时拒绝，
 * 避免把一个真实身份的轮次“改嫁”给另一个身份。
 */
function rewriteSnapshotIdentity(raw, identity, key) {
	const snapshot = parseRunSnapshot(raw, key);
	const current = snapshotIdentity(snapshot);
	if ((current.tenantId !== 0 || current.userId !== 0)
		&& (current.tenantId !== identity.tenantId || current.userId !== identity.userId)) {
		throw new MigrationError(
			`源快照已带其他身份（${current.tenantId}:${current.userId}），拒绝改写：${key}`,
		);
	}
	return JSON.stringify({ ...snapshot, tenantId: identity.tenantId, userId: identity.userId });
}

/**
 * 带身份快照的对账：目标记录身份必须等于声明值，且除身份外其他字段与源逐值相同。
 * 这是 `RunStore.readSnapshot` 的“键 + 记录身份复核”前置条件，也是 delete-source 的门。
 */
async function verifyIdentitySnapshot(client, sourceKey, targetKey, identity) {
	const sourceRaw = await client.get(sourceKey);
	const targetRaw = await client.get(targetKey);
	if (sourceRaw === null || targetRaw === null) {
		return { equal: false, reason: 'source/target missing' };
	}
	let source;
	let target;
	try {
		source = parseRunSnapshot(sourceRaw, sourceKey);
		target = parseRunSnapshot(targetRaw, targetKey);
	} catch (error) {
		return { equal: false, reason: error.message };
	}
	const targetIdentity = snapshotIdentity(target);
	if (targetIdentity.tenantId !== identity.tenantId || targetIdentity.userId !== identity.userId) {
		return {
			equal: false,
			reason: `target snapshot identity ${targetIdentity.tenantId}:${targetIdentity.userId}`
				+ ` != declared ${identity.tenantId}:${identity.userId}`,
		};
	}
	const sourceIdentity = snapshotIdentity(source);
	if ((sourceIdentity.tenantId !== 0 || sourceIdentity.userId !== 0)
		&& (sourceIdentity.tenantId !== identity.tenantId || sourceIdentity.userId !== identity.userId)) {
		return {
			equal: false,
			reason: `source snapshot identity ${sourceIdentity.tenantId}:${sourceIdentity.userId}`
				+ ` != declared ${identity.tenantId}:${identity.userId}`,
		};
	}
	return isDeepStrictEqual(snapshotBody(source), snapshotBody(target))
		? { equal: true, reason: 'snapshot identity and body match' }
		: { equal: false, reason: 'snapshot body mismatch (non-identity fields)' };
}

/** 单个 item 的对账：身份改写族走快照语义，其余族仍是逐值比较。 */
function verifyItemEqual(client, item) {
	return item.identityRewrite
		? verifyIdentitySnapshot(client, item.sourceKey, item.targetKey, item.identityRewrite)
		: verifyEqual(client, item.sourceKey, item.targetKey, item.type);
}

/** 从 tagged 键读身份段；legacy tagged 形（`{run:<id>}`）返回 null（无归属）。 */
export function parseTaggedIdentity(key, prefix) {
	if (typeof key !== 'string' || !key.startsWith(prefix)) {
		return null;
	}
	const match = /^\{([^}]*?)run:(.+)\}$/.exec(key.slice(prefix.length));
	if (!match || match[1] === '') {
		return null;
	}
	const parts = match[1].replace(/:$/, '').split(':');
	if (parts.length !== 2) {
		return null;
	}
	const tenantId = Number(parts[0]);
	const userId = Number(parts[1]);
	if (!Number.isInteger(tenantId) || !Number.isInteger(userId) || tenantId <= 0) {
		return null;
	}
	if (userId <= 0 && userId !== -1) {
		return null;
	}
	return { tenantId, userId };
}

export function parseOldKey(key, prefix) {
	if (typeof key !== 'string' || !key.startsWith(prefix)) {
		return null;
	}
	const tail = key.slice(prefix.length);
	if (tail === '' || tail.startsWith('{run:')) {
		return null;
	}
	return isValidRunId(tail) ? tail : null;
}

export function parseTaggedKey(key, prefix) {
	if (typeof key !== 'string' || !key.startsWith(prefix)) {
		return null;
	}
	const tail = key.slice(prefix.length);
	// 认两种 tagged 形：legacy `{run:<id>}` 与带身份 `{<t>:<u>:run:<id>}`（runId 里允许冒号）
	const match = /^\{(?:[^}]*?:)?run:(.+)\}$/.exec(tail);
	if (!match || !isValidRunId(match[1])) {
		return null;
	}
	return match[1];
}

// ── 发现（SCAN，逐 master） ────────────────────────────────────────────────

function masterNodes(client) {
	if (client && typeof client.nodes === 'function') {
		const nodes = client.nodes('master');
		if (!Array.isArray(nodes) || nodes.length === 0) {
			throw new MigrationError(
				'Cluster 未返回任何 master 节点：拒绝静默回落单节点扫描（可能漏 run）',
			);
		}
		return nodes;
	}
	return [client];
}

async function scanPattern(client, pattern, onKey) {
	for (const node of masterNodes(client)) {
		let cursor = '0';
		do {
			const [next, keys] = await node.scan(cursor, 'MATCH', pattern, 'COUNT', SCAN_BATCH);
			for (const key of keys ?? []) {
				onKey(key);
			}
			cursor = String(next);
		} while (cursor !== '0');
	}
}

/**
 * 发现待迁移 runId。
 * @param {{rollback?: boolean, runIds?: string[]}} options rollback=true 时从 tagged 键发现
 * @returns {Promise<Map<string, Set<string>>>} runId → 出现过的 family name
 */
/**
 * 归一目标身份：{tenantId, userId} 或 null。
 *
 * 旧数据没有身份，正向迁移必须由操作者声明目标身份；只给一半直接失败（猜一个用户 = 串身份）。
 */
export function normalizeIdentity(identity) {
	if (!identity) {
		return null;
	}
	const tenantId = Number(identity.tenantId);
	const userId = Number(identity.userId);
	if (!Number.isInteger(tenantId) || tenantId <= 0) {
		throw new MigrationError(`目标 tenantId 必须是正整数：${identity.tenantId}`);
	}
	if (!Number.isInteger(userId) || (userId <= 0 && userId !== -1)) {
		throw new MigrationError(`目标 userId 必须是正整数或 -1（系统账号）：${identity.userId}`);
	}
	return { tenantId, userId };
}

export async function discoverRunIds(client, options = {}) {
	const rollback = options.rollback === true;
	const explicit = options.runIds ?? [];
	if (explicit.length > 0) {
		const found = new Map();
		for (const runId of explicit) {
			if (!isValidRunId(runId)) {
				throw new MigrationError(`--run-id 非法：${runId}`);
			}
			found.set(runId, new Set());
		}
		return found;
	}

	const found = new Map();
	const identity = normalizeIdentity(options.identity);
	for (const family of MIGRATION_FAMILIES) {
		await scanPattern(client, `${family.prefix}*`, (key) => {
			if (key.endsWith(TEMP_SUFFIX)) {
				return;
			}
			if (rollback && identity) {
				// 声明了身份：只回滚"该身份"的键；但 legacy tagged 形没有身份，是明确的双形回退目标
				// （先试带身份、缺失再回退 legacy），所以 owner=null 的成员仍然放行。
				const owner = parseTaggedIdentity(key, family.prefix);
				if (owner && (owner.tenantId !== identity.tenantId || owner.userId !== identity.userId)) {
					return;
				}
			}
			const runId = rollback
				? parseTaggedKey(key, family.prefix)
				: parseOldKey(key, family.prefix);
			if (!runId) {
				return;
			}
			if (!found.has(runId)) {
				found.set(runId, new Set());
			}
			found.get(runId).add(family.name);
		});
	}
	return found;
}

// ── 读取 / 比较 ────────────────────────────────────────────────────────────

async function inspectKey(client, key, expectedType) {
	const actual = await client.type(key);
	if (actual === 'none') {
		return { exists: false, type: 'none', pttl: -2, size: 0 };
	}
	if (actual !== expectedType) {
		return {
			exists: true,
			type: actual,
			pttl: await client.pttl(key),
			size: 0,
			typeMismatch: true,
			expectedType,
		};
	}
	const pttl = await client.pttl(key);
	const size = await sizeOf(client, key, expectedType);
	return { exists: true, type: actual, pttl, size };
}

async function sizeOf(client, key, type) {
	switch (type) {
		case 'string':
			return Number(await client.strlen(key));
		case 'hash':
			return Number(await client.hlen(key));
		case 'list':
			return Number(await client.llen(key));
		case 'stream':
			return Number(await client.xlen(key));
		default:
			throw new MigrationError(`未知类型：${type}`);
	}
}

async function readStreamAll(client, key) {
	const entries = [];
	let start = '-';
	let guard = 0;
	for (;;) {
		const page = await client.xrange(key, start, '+', 'COUNT', STREAM_PAGE);
		const rows = page ?? [];
		for (const row of rows) {
			entries.push([String(row[0]), Array.from(row[1] ?? [], (v) => String(v))]);
		}
		if (rows.length < STREAM_PAGE) {
			break;
		}
		if (++guard > 1_000_000) {
			throw new MigrationError(`stream 分页异常（超过 ${guard} 页）：${key}`);
		}
		const lastId = String(rows[rows.length - 1][0]);
		const [ms, seq] = lastId.split('-');
		start = `${ms}-${Number(seq) + 1}`;
	}
	return entries;
}

async function readValue(client, key, type) {
	switch (type) {
		case 'string':
			return await client.get(key);
		case 'hash': {
			const obj = await client.hgetall(key);
			return obj ?? {};
		}
		case 'list':
			return (await client.lrange(key, 0, -1)) ?? [];
		case 'stream':
			return await readStreamAll(client, key);
		default:
			throw new MigrationError(`未知类型：${type}`);
	}
}

function valuesEqual(type, left, right) {
	if (type === 'string') {
		return left === right;
	}
	if (type === 'hash') {
		const lk = Object.keys(left).sort();
		const rk = Object.keys(right).sort();
		if (lk.length !== rk.length || lk.some((k, i) => k !== rk[i])) {
			return false;
		}
		return lk.every((k) => left[k] === right[k]);
	}
	if (type === 'list') {
		if (left.length !== right.length) {
			return false;
		}
		return left.every((v, i) => v === right[i]);
	}
	if (type === 'stream') {
		if (left.length !== right.length) {
			return false;
		}
		return left.every((entry, i) => {
			const other = right[i];
			return entry[0] === other[0] && entry[1].length === other[1].length
				&& entry[1].every((v, j) => v === other[1][j]);
		});
	}
	throw new MigrationError(`未知类型：${type}`);
}

async function verifyEqual(client, sourceKey, targetKey, type) {
	const sourceType = await client.type(sourceKey);
	const targetType = await client.type(targetKey);
	if (sourceType !== type || targetType !== type) {
		return {
			equal: false,
			reason: `type mismatch (source=${sourceType}, target=${targetType}, expected=${type})`,
		};
	}
	const source = await readValue(client, sourceKey, type);
	const target = await readValue(client, targetKey, type);
	return { equal: valuesEqual(type, source, target), reason: 'value mismatch' };
}

/**
 * 删除源前的 PTTL 充分性判定。
 * - 源 -1（持久）⇒ 目标必须也 -1；
 * - 源有限、目标 -1 ⇒ 安全（持久不早于源）；
 * - 源有限、目标有限 ⇒ 目标不得比源短超过容差，且不得已到期。
 */
function ttlSufficient(sourcePttl, targetPttl) {
	if (sourcePttl === -2 || targetPttl === -2) {
		return {
			ok: false,
			reason: `key missing at delete check (source PTTL=${sourcePttl}, target PTTL=${targetPttl})`,
		};
	}
	if (sourcePttl === -1) {
		return targetPttl === -1
			? { ok: true, reason: null }
			: {
				ok: false,
				reason: `source PTTL=-1（持久）但 target PTTL=${targetPttl}（有限）`,
			};
	}
	if (targetPttl === -1) {
		return { ok: true, reason: null };
	}
	if (targetPttl <= 0) {
		return {
			ok: false,
			reason: `target 已到期/即将到期 (source PTTL=${sourcePttl}, target PTTL=${targetPttl})`,
		};
	}
	if (targetPttl + TTL_TOLERANCE_MS < sourcePttl) {
		return {
			ok: false,
			reason: `target PTTL 比源短超过容差 ${TTL_TOLERANCE_MS}ms`
				+ ` (source PTTL=${sourcePttl}, target PTTL=${targetPttl})`,
		};
	}
	return { ok: true, reason: null };
}

// ── 计划 ───────────────────────────────────────────────────────────────────

function newRunPlan(runId, rollback) {
	return {
		runId,
		direction: rollback ? 'rollback' : 'forward',
		status: 'ready',
		reason: null,
		sourceKeys: 0,
		plannedCopies: 0,
		items: [],
	};
}

/**
 * 生成迁移计划（只读）。
 * conflict 会写入 run/plan，调用方必须拒绝 --apply。
 */
export async function buildPlan(client, options = {}) {
	const rollback = options.rollback === true;
	const identity = normalizeIdentity(options.identity);
	const discovered = await discoverRunIds(client, {
		rollback,
		identity,
		runIds: options.runIds ?? [],
	});
	const runs = [];
	for (const runId of discovered.keys()) {
		runs.push(await planRun(client, runId, rollback, identity));
	}
	const totals = {
		runs: runs.length,
		ready: runs.filter((r) => r.status === 'ready').length,
		skipped: runs.filter((r) => r.status === 'skipped').length,
		conflicts: runs.filter((r) => r.status === 'conflict').length,
		conflictItems: runs.reduce(
			(n, r) => n + r.items.filter((item) => item.action === 'conflict').length,
			0,
		),
		plannedCopies: runs.reduce((n, r) => n + r.plannedCopies, 0),
		sourceKeys: runs.reduce((n, r) => n + r.sourceKeys, 0),
	};
	return {
		direction: rollback ? 'rollback' : 'forward',
		identity,
		warnings: identity
			? []
			: ['未声明目标身份（--tenant-id/--user-id）：目标键是 legacy 形 {run:<runId>}，'
				+ '快照身份不会被改写；带身份的新路径读不到它，迁移结果仅具归档价值、不属于任何新身份'
				+ '（不构成租户隔离）'],
		runs,
		totals,
	};
}

/**
 * rollback 的源键：先试带身份 tag，缺失再回退 legacy tag（双形回退）。
 * 两个都不存在时返回首选键（带身份优先），让计划显示 source-missing。
 */
async function rollbackSourceKeyFor(client, prefix, runId, identity) {
	if (identity) {
		const identityKey = taggedKeyFor(prefix, runId, identity);
		if ((await client.type(identityKey)) !== 'none') {
			return identityKey;
		}
	}
	const legacyKey = taggedKeyFor(prefix, runId, null);
	if ((await client.type(legacyKey)) !== 'none') {
		return legacyKey;
	}
	return taggedKeyFor(prefix, runId, identity);
}

/**
 * ai:runs 成员计划（不是 10 族数据键）。
 *
 * - forward + identity：本次 run 的成员由裸 `runId` 重写为 `tenantId/userId/runId`；索引里没有时补一条。
 * - rollback + identity：反向恢复为裸 `runId`；没有成员时不凭空造 legacy 索引。
 * - 无身份：返回 null —— legacy 命名空间保持原样，不猜身份、不扫租户列表。
 */
async function planRunIndex(client, runId, rollback, identity) {
	if (!identity) {
		return null;
	}
	const sourceMember = rollback ? indexMemberFor(runId, identity) : runId;
	const targetMember = rollback ? runId : indexMemberFor(runId, identity);
	const indexType = await client.type(RUN_INDEX_KEY);
	if (indexType === 'none') {
		return rollback
			? { action: 'absent', reason: 'index-missing', sourceMember, targetMember }
			: { action: 'add', reason: 'index-key-missing', sourceMember: null, targetMember };
	}
	if (indexType !== 'set') {
		return {
			action: 'conflict',
			reason: `ai:runs type is ${indexType}, expected set`,
			sourceMember,
			targetMember,
		};
	}
	const members = new Set((await client.smembers(RUN_INDEX_KEY)) ?? []);
	const sourceExists = members.has(sourceMember);
	const targetExists = members.has(targetMember);
	if (sourceExists && targetExists) {
		return { action: 'dedupe', reason: 'both members present; drop source form', sourceMember, targetMember };
	}
	if (sourceExists) {
		return { action: 'rewrite', reason: null, sourceMember, targetMember };
	}
	if (targetExists) {
		return { action: 'identical', reason: 'index member already in target form', sourceMember, targetMember };
	}
	return rollback
		? { action: 'absent', reason: 'index-member-missing', sourceMember, targetMember }
		: { action: 'add', reason: 'index-member-missing', sourceMember: null, targetMember };
}

async function planRun(client, runId, rollback, identity = null) {
	const run = newRunPlan(runId, rollback);
	const states = new Map();
	for (const family of MIGRATION_FAMILIES) {
		// 反向：源逐族先试带身份的新键形，缺失再回退 legacy tagged 形（双形回退）。
		const source = rollback
			? await rollbackSourceKeyFor(client, family.prefix, runId, identity)
			: oldKeyFor(family.prefix, runId);
		const target = rollback
			? oldKeyFor(family.prefix, runId)
			: taggedKeyFor(family.prefix, runId, identity);
		const state = await inspectKey(client, source, family.type);
		states.set(family.name, state);
		const item = {
			family: family.name,
			prefix: family.prefix,
			type: family.type,
			sourceKey: source,
			targetKey: target,
			sourceExists: state.exists,
			size: state.size,
			pttl: state.pttl,
			action: 'absent',
			reason: state.exists ? null : 'source-missing',
		};
		if (!rollback && identity && family.name === 'run') {
			// 正向带身份迁移必须把记录身份写进快照；dry-run 也要先证明源 JSON 可解析且不是别人的身份。
			item.identityRewrite = identity;
		}
		if (state.exists) {
			run.sourceKeys += 1;
			if (state.typeMismatch) {
				item.action = 'conflict';
				item.reason = `source type is ${state.type}, expected ${family.type}`;
				run.status = 'conflict';
				run.reason = run.reason ?? item.reason;
			}
			else if (item.identityRewrite) {
				try {
					rewriteSnapshotIdentity(await client.get(source), identity, source);
				} catch (error) {
					item.action = 'conflict';
					item.reason = error.message;
					run.status = 'conflict';
					run.reason = run.reason ?? item.reason;
				}
			}
		}
		run.items.push(item);
	}

	if (run.status === 'conflict') {
		return run;
	}

	const targetRunKey = rollback
		? oldKeyFor('ai:run:', runId)
		: taggedKeyFor('ai:run:', runId, identity);
	const targetRunExists = (await client.type(targetRunKey)) !== 'none';
	const sourceRun = states.get('run');
	const runWillExist = targetRunExists || (sourceRun.exists && run.status === 'ready');

	const seq = states.get('seq');
	const xevents = states.get('xevents');
	if (run.sourceKeys > 0 && (!seq.exists || !xevents.exists)) {
		const missing = [];
		if (!seq.exists) {
			missing.push('seq');
		}
		if (!xevents.exists) {
			missing.push('xevents');
		}
		run.status = 'skipped';
		run.reason = `seq/xevents 必须成对，缺失 ${missing.join(' + ')}；整 run 不搬`;
		for (const item of run.items) {
			if (item.sourceExists) {
				item.action = 'held';
				item.reason = run.reason;
			}
		}
		return run;
	}

	for (const item of run.items) {
		if (!item.sourceExists || item.action === 'conflict') {
			continue;
		}
		const targetType = await client.type(item.targetKey);
		if (targetType === 'none') {
			item.action = 'copy';
			run.plannedCopies += 1;
			continue;
		}
		const targetState = await inspectKey(client, item.targetKey, item.type);
		if (targetState.typeMismatch) {
			item.action = 'conflict';
			item.reason = `target type is ${targetState.type}, expected ${item.type}`;
			run.status = 'conflict';
			run.reason = run.reason ?? item.reason;
			continue;
		}
		const check = await verifyItemEqual(client, item);
		if (check.equal) {
			item.action = 'identical';
			item.targetPttl = targetState.pttl;
			const ttl = ttlSufficient(item.pttl, targetState.pttl);
			item.ttlShort = !ttl.ok;
			item.reason = ttl.ok
				? 'target exists and all values match'
				: `target exists and all values match; ${ttl.reason}`;
		} else {
			item.action = 'conflict';
			item.reason = `target exists but differs: ${check.reason}`;
			run.status = 'conflict';
			run.reason = run.reason ?? item.reason;
		}
	}
	if (run.status === 'conflict') {
		return run;
	}

	if (run.sourceKeys === 0 && !runWillExist) {
		run.status = 'skipped';
		run.reason = '没有源键（已迁移或已过期）';
		return run;
	}

	const index = runWillExist ? await planRunIndex(client, runId, rollback, identity) : null;
	if (index) {
		run.runIndex = index;
		if (index.action === 'conflict') {
			run.status = 'conflict';
			run.reason = run.reason ?? index.reason;
			return run;
		}
	}

	if (run.sourceKeys === 0) {
		const indexOnly = index
			&& (index.action === 'rewrite' || index.action === 'dedupe' || index.action === 'add');
		if (indexOnly) {
			// 数据键已迁完，只剩索引成员要修：仍算 ready，applyRun 不写数据、只重写 ai:runs。
			run.status = 'ready';
			run.reason = 'index-only';
		} else {
			run.status = 'skipped';
			run.reason = '没有源键（已迁移或已过期）';
		}
	}
	return run;
}

// ── 应用 ───────────────────────────────────────────────────────────────────

async function copyString(client, item, pttl, lock) {
	assertLock(lock);
	let value = await client.get(item.sourceKey);
	if (value === null) {
		throw new MigrationError(`源键在复制前消失：${item.sourceKey}`);
	}
	if (item.identityRewrite) {
		// 正向带身份：先把快照记录身份置为声明值，再写目标；不是逐字节复制 0/0。
		value = rewriteSnapshotIdentity(value, item.identityRewrite, item.sourceKey);
	}
	assertLock(lock);
	const args = pttl >= 0 ? ['PX', Math.max(1, pttl), 'NX'] : ['NX'];
	const written = await client.set(item.targetKey, value, ...args);
	if (written === null) {
		return await reconcileAfterRace(client, item);
	}
	return 1;
}

async function reconcileAfterRace(client, item) {
	const check = await verifyItemEqual(client, item);
	if (!check.equal) {
		throw new MigrationError(
			(item.identityRewrite ? '目标键在迁移期间被并发写入且身份/内容不一致（未覆盖）：' : '目标键在迁移期间被并发写入且内容不一致（未覆盖）：')
				+ `${item.targetKey}（${check.reason}）`,
		);
	}
	return 0;
}

function hashPairs(value) {
	return Object.entries(value).flatMap(([field, fieldValue]) => [field, String(fieldValue)]);
}

function tempKeyFor(targetKey) {
	return `${targetKey}${TEMP_SUFFIX}`;
}

/** tagged 目标与临时键同槽，才能用 RENAMENX；旧裸键目标（rollback）只能先查后写。 */
function supportsAtomicClaim(targetKey) {
	// legacy `{run:<id>}` 与带身份 `{<t>:<u>:run:<id>}` 都是 single-tag 键（RENAMENX 同槽）
	return /\{[^}]*run:/.test(targetKey);
}

async function execAtomicChunk(client, build) {
	const tx = client.multi();
	build(tx);
	const replies = await tx.exec();
	if (!replies) {
		throw new MigrationError('MULTI/EXEC 被中止（临时键 chunk 未提交）');
	}
	for (const entry of replies) {
		if (entry && entry[0]) {
			throw entry[0];
		}
	}
}

async function writeHashChunks(client, key, pairs, safetyTtl, lock) {
	for (let i = 0; i < pairs.length; i += HASH_BATCH * 2) {
		assertLock(lock);
		const chunk = pairs.slice(i, i + HASH_BATCH * 2);
		if (safetyTtl) {
			await execAtomicChunk(client, (tx) => {
				tx.hset(key, ...chunk);
				tx.pexpire(key, safetyTtl);
			});
		} else {
			await client.hset(key, ...chunk);
		}
	}
}

async function writeListChunks(client, key, values, safetyTtl, lock) {
	for (let i = 0; i < values.length; i += LIST_BATCH) {
		assertLock(lock);
		const chunk = values.slice(i, i + LIST_BATCH);
		if (safetyTtl) {
			await execAtomicChunk(client, (tx) => {
				tx.rpush(key, ...chunk);
				tx.pexpire(key, safetyTtl);
			});
		} else {
			await client.rpush(key, ...chunk);
		}
	}
}

async function writeStreamChunks(client, key, entries, safetyTtl, lock) {
	for (let i = 0; i < entries.length; i += STREAM_TX_BATCH) {
		assertLock(lock);
		const batch = entries.slice(i, i + STREAM_TX_BATCH);
		if (safetyTtl) {
			await execAtomicChunk(client, (tx) => {
				for (const [id, fields] of batch) {
					tx.xadd(key, id, ...fields);
				}
				tx.pexpire(key, safetyTtl);
			});
		} else {
			for (const [id, fields] of batch) {
				assertLock(lock);
				await client.xadd(key, id, ...fields);
			}
		}
	}
}

async function writeViaTemp(client, item, pttl, write, lock) {
	const temp = tempKeyFor(item.targetKey);
	assertLock(lock);
	await client.del(temp);
	try {
		await write(temp);
		assertLock(lock);
		if ((await client.type(temp)) === 'none') {
			throw new MigrationError(`源键为空（无法建立目标键）：${item.sourceKey}`);
		}
		if (pttl >= 0) {
			await client.pexpire(temp, Math.max(1, pttl));
		} else if (pttl === -1) {
			await client.persist(temp);
		} else {
			throw new MigrationError(`源键在复制前消失：${item.sourceKey}`);
		}
		assertLock(lock);
		const claimed = await client.renamenx(temp, item.targetKey);
		if (claimed === 0) {
			await client.del(temp);
			return 0;
		}
		return 1;
	} catch (error) {
		try {
			await client.del(temp);
		} catch {
			// 清理失败不掩盖原始错误
		}
		throw error;
	}
}

async function copyHash(client, item, pttl, lock) {
	const value = await client.hgetall(item.sourceKey);
	assertLock(lock);
	const pairs = hashPairs(value ?? {});
	if (supportsAtomicClaim(item.targetKey)) {
		return await writeViaTemp(
			client,
			item,
			pttl,
			(key) => writeHashChunks(client, key, pairs, TEMP_SAFETY_TTL_MS, lock),
			lock,
		);
	}
	await writeHashChunks(client, item.targetKey, pairs, null, lock);
	if (pttl >= 0) {
		assertLock(lock);
		await client.pexpire(item.targetKey, Math.max(1, pttl));
	}
	return 1;
}

async function copyList(client, item, pttl, lock) {
	const value = (await client.lrange(item.sourceKey, 0, -1)) ?? [];
	assertLock(lock);
	if (supportsAtomicClaim(item.targetKey)) {
		return await writeViaTemp(
			client,
			item,
			pttl,
			(key) => writeListChunks(client, key, value, TEMP_SAFETY_TTL_MS, lock),
			lock,
		);
	}
	await writeListChunks(client, item.targetKey, value, null, lock);
	if (pttl >= 0) {
		assertLock(lock);
		await client.pexpire(item.targetKey, Math.max(1, pttl));
	}
	return 1;
}

async function copyStream(client, item, pttl, lock) {
	const entries = await readStreamAll(client, item.sourceKey);
	assertLock(lock);
	if (supportsAtomicClaim(item.targetKey)) {
		return await writeViaTemp(
			client,
			item,
			pttl,
			(key) => writeStreamChunks(client, key, entries, TEMP_SAFETY_TTL_MS, lock),
			lock,
		);
	}
	await writeStreamChunks(client, item.targetKey, entries, null, lock);
	if (pttl >= 0) {
		assertLock(lock);
		await client.pexpire(item.targetKey, Math.max(1, pttl));
	}
	return 1;
}

async function copyItem(client, item, lock) {
	assertLock(lock);
	const sourceType = await client.type(item.sourceKey);
	if (sourceType === 'none') {
		throw new MigrationError(`源键在复制前消失：${item.sourceKey}`);
	}
	if (sourceType !== item.type) {
		throw new MigrationError(
			`源键类型漂移：${item.sourceKey} 现在是 ${sourceType}，计划时是 ${item.type}`,
		);
	}
	assertLock(lock);
	const targetType = await client.type(item.targetKey);
	if (targetType !== 'none') {
		const check = await verifyItemEqual(client, item);
		if (!check.equal) {
			throw new MigrationError(
				(item.identityRewrite ? `目标键已存在且身份/内容不一致（未覆盖）：` : `目标键已存在且内容不一致（未覆盖）：`)
					+ `${item.targetKey}（${check.reason}）`,
			);
		}
		return { writes: 0, reconciled: true };
	}

	const pttl = await client.pttl(item.sourceKey);
	if (pttl === -2) {
		throw new MigrationError(`源键在复制前消失：${item.sourceKey}`);
	}

	let writes;
	switch (item.type) {
		case 'string':
			writes = await copyString(client, item, pttl, lock);
			break;
		case 'hash':
			writes = await copyHash(client, item, pttl, lock);
			break;
		case 'list':
			writes = await copyList(client, item, pttl, lock);
			break;
		case 'stream':
			writes = await copyStream(client, item, pttl, lock);
			break;
		default:
			throw new MigrationError(`未知类型：${item.type}`);
	}
	assertLock(lock);
	if (writes === 0) {
		const check = await verifyItemEqual(client, item);
		if (!check.equal) {
			throw new MigrationError(
				`目标键在迁移期间被并发写入且内容不一致（未覆盖）：${item.targetKey}（${check.reason}）`,
			);
		}
		return { writes: 0, reconciled: true };
	}
	// 写后回读：带身份族必须证明“记录身份 === 声明身份 + 其他字段不变”，否则不得进入 delete-source。
	const check = await verifyItemEqual(client, item);
	if (!check.equal) {
		throw new MigrationError(
			item.identityRewrite
				? `写后身份校验失败：${item.targetKey}（${check.reason}）`
				: `写后对账失败：${item.targetKey} 与 ${item.sourceKey} 不一致（${check.reason}）`,
		);
	}
	assertLock(lock);
	if (pttl >= 0) {
		const targetPttl = await client.pttl(item.targetKey);
		if (targetPttl < 0) {
			throw new MigrationError(
				`写后对账失败：${item.targetKey} 未保留 PTTL（source=${pttl}, target=${targetPttl}）`,
			);
		}
		item.targetPttl = targetPttl;
	}
	return { writes, reconciled: true };
}

/**
 * 应用 ai:runs 成员重写。SREM 旧成员 + SADD 新成员放在同一 MULTI：
 * `ai:runs` 是单键，跨成员更新不会 CROSSSLOT；中途失败整批回滚，不会留下两种形态并存。
 */
async function applyRunIndex(client, run, lock) {
	const index = run.runIndex;
	if (!index) {
		return { writes: 0, action: null };
	}
	if (index.action === 'identical' || index.action === 'absent') {
		return { writes: 0, action: index.action };
	}
	assertLock(lock);
	const tx = client.multi();
	if (index.sourceMember) {
		tx.srem(RUN_INDEX_KEY, index.sourceMember);
	}
	tx.sadd(RUN_INDEX_KEY, index.targetMember);
	const replies = await tx.exec();
	if (!replies) {
		throw new MigrationError('MULTI/EXEC 被中止（ai:runs 成员未重写）');
	}
	for (const entry of replies) {
		if (entry && entry[0]) {
			throw entry[0];
		}
	}
	return { writes: 1, action: index.action };
}

/**
 * 应用单个 run。锁只在 item 边界检查：当前 item 允许完成，下一个 item 不再开始。
 * 单 run 内的 chunk 级检查在 copy* / writeViaTemp 中完成。
 */
export async function applyRun(client, run, lock = null) {
	assertLock(lock);
	const result = {
		runId: run.runId,
		status: run.status,
		reason: run.reason,
		items: [],
		deleted: [],
		// 数据键写入数（不含迁移锁与临时键；临时键只用于同槽原子认领）
		writes: 0,
		// ai:runs 成员重写另计：它不是 10 族数据键，但也是真实写
		indexWrites: 0,
		index: run.runIndex ?? null,
	};
	if (run.status !== 'ready') {
		result.items = run.items.map((item) => ({ ...item, writes: 0, verified: false }));
		return result;
	}

	for (const item of run.items) {
		assertLock(lock);
		const entry = { ...item, writes: 0, verified: false };
		if (item.action === 'copy') {
			const outcome = await copyItem(client, item, lock);
			entry.writes = outcome.writes;
			entry.verified = outcome.reconciled;
			entry.action = outcome.writes > 0 ? 'copied' : 'identical';
			result.writes += outcome.writes;
		} else if (item.action === 'identical') {
			assertLock(lock);
			const check = await verifyItemEqual(client, item);
			if (!check.equal) {
				throw new MigrationError(
					`计划后目标键内容/身份变化（拒绝覆盖）：${item.targetKey}（${check.reason}）`,
				);
			}
			entry.verified = true;
		}
		result.items.push(entry);
	}

	// 数据键全部落位后再改索引：失败/中途停止时不会出现“索引指向不存在的目标”。
	const indexOutcome = await applyRunIndex(client, run, lock);
	result.indexWrites = indexOutcome.writes;
	result.index = indexOutcome.action ? { ...run.runIndex, applied: indexOutcome.action } : run.runIndex ?? null;
	return result;
}

async function checkDeleteCandidate(client, item) {
	const check = await verifyItemEqual(client, item);
	if (!check.equal) {
		return {
			ok: false,
			reason: `对账不通过 ${item.sourceKey} vs ${item.targetKey}（${check.reason}）`,
		};
	}
	const sourcePttl = await client.pttl(item.sourceKey);
	const targetPttl = await client.pttl(item.targetKey);
	const ttl = ttlSufficient(sourcePttl, targetPttl);
	if (!ttl.ok) {
		return {
			ok: false,
			reason: `${item.sourceKey} -> ${item.targetKey}：${ttl.reason}`,
		};
	}
	return { ok: true, sourcePttl, targetPttl };
}

/**
 * 删除源键（两阶段）：
 * - 校验阶段：全部候选通过“值 + PTTL 充分性”后才进入删除；任一失败不删任何源。
 * - DEL 阶段：每个 DEL 前再轻量复验一次，缩小“校验后源被写新值”的窗口；若 DEL 报错或锁在
 *   检查点丢失，可能已删除部分源——但每个已删项在删除前均已复验。重跑会收敛。
 *
 * DEL 阶段无法跨槽 all-or-none；这是离线窗口下的明确恢复语义。
 */
export async function deleteVerifiedSources(client, results, lock = null) {
	assertLock(lock);
	const candidates = [];
	for (const result of results) {
		if (result.status !== 'ready') {
			continue;
		}
		for (const item of result.items) {
			if (!item.sourceExists || item.action === 'absent' || item.action === 'held') {
				continue;
			}
			if (item.action === 'conflict') {
				continue;
			}
			assertLock(lock);
			const check = await checkDeleteCandidate(client, item);
			if (!check.ok) {
				throw new MigrationError(
					`拒绝删除源键（校验阶段，未删除任何源键）：${check.reason}`,
				);
			}
			candidates.push({ result, item });
		}
	}

	for (const { result, item } of candidates) {
		assertLock(lock);
		const recheck = await checkDeleteCandidate(client, item);
		if (!recheck.ok) {
			throw new MigrationError(
				'DEL 阶段复验失败（此前已删除的源键均已校验；本次及之后未删）：'
				+ recheck.reason,
			);
		}
		assertLock(lock);
		const deleted = await client.del(item.sourceKey);
		if (deleted > 0) {
			result.deleted.push(item.sourceKey);
		}
	}
}

const LOCK_RENEW_SCRIPT = "if redis.call('get', KEYS[1]) == ARGV[1] then "
	+ "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end";

const LOCK_RELEASE_SCRIPT = "if redis.call('get', KEYS[1]) == ARGV[1] then "
	+ "return redis.call('del', KEYS[1]) else return 0 end";

/** 获取迁移锁；已存在则拒绝并发 apply。 */
export async function acquireMigrationLock(client) {
	const token = randomUUID();
	const acquired = await client.set(
		MIGRATION_LOCK_KEY,
		token,
		'PX',
		MIGRATION_LOCK_TTL_MS,
		'NX',
	);
	if (acquired === null) {
		throw new MigrationError(
			`另一个迁移进程持有迁移锁（${MIGRATION_LOCK_KEY} 已存在）：拒绝并发 apply`,
		);
	}
	const lock = { token, lost: null, released: false, timer: null };
	const timer = setInterval(() => {
		void renewMigrationLock(client, lock);
	}, MIGRATION_LOCK_RENEW_MS);
	if (typeof timer.unref === 'function') {
		timer.unref();
	}
	lock.timer = timer;
	return lock;
}

/** 单次续租；token 不匹配或异常时置 `lock.lost`，返回 false。 */
export async function renewMigrationLock(client, lock) {
	if (!lock || lock.lost) {
		return false;
	}
	try {
		const renewed = await client.eval(
			LOCK_RENEW_SCRIPT,
			1,
			MIGRATION_LOCK_KEY,
			lock.token,
			MIGRATION_LOCK_TTL_MS,
		);
		if (Number(renewed) !== 1) {
			lock.lost = '迁移锁已丢失（过期或被其他进程接管）：在下一个 item/chunk 检查点停止';
			return false;
		}
		return true;
	} catch (error) {
		lock.lost = `迁移锁续租失败：${error.message}（在下一个 item/chunk 检查点停止）`;
		return false;
	}
}

/** 按 token 释放迁移锁；释放失败交给 60s TTL 兜底。 */
export async function releaseMigrationLock(client, lock) {
	if (!lock || lock.released) {
		return;
	}
	lock.released = true;
	if (lock.timer) {
		clearInterval(lock.timer);
	}
	try {
		await client.eval(LOCK_RELEASE_SCRIPT, 1, MIGRATION_LOCK_KEY, lock.token);
	} catch {
		// 锁会在 TTL 后自动释放
	}
}

function assertLock(lock) {
	if (lock?.lost) {
		throw new MigrationError(lock.lost);
	}
}

function assertRunIds(runIds) {
	const ids = runIds ?? [];
	if (!Array.isArray(ids)) {
		throw new MigrationError('runIds 必须是数组');
	}
	for (const runId of ids) {
		if (!isValidRunId(runId)) {
			throw new MigrationError(`--run-id 非法：${runId}`);
		}
	}
	return ids;
}

async function runMigrationInner(client, options, lock) {
	const rollback = options.rollback === true;
	const apply = options.apply === true;
	const deleteSource = options.deleteSource === true;
	const runIds = assertRunIds(options.runIds);
	const warnings = [];
	if (deleteSource && !apply) {
		warnings.push('--delete-source 未生效：当前是 dry-run，需同时传 --apply 才会删除源键');
	}

	assertLock(lock);
	const plan = await buildPlan(client, { rollback, runIds, identity: options.identity });
	assertLock(lock);
	warnings.push(...(plan.warnings ?? []));
	const base = {
		ok: true,
		mode: apply ? 'apply' : 'dry-run',
		direction: plan.direction,
		plan,
		results: [],
		warnings,
		totals: {
			runs: plan.totals.runs,
			ready: plan.totals.ready,
			skipped: plan.totals.skipped,
			conflicts: plan.totals.conflicts,
			plannedCopies: plan.totals.plannedCopies,
			writes: 0,
			indexWrites: 0,
			deleted: 0,
		},
	};

	if (plan.totals.conflicts > 0) {
		return {
			...base,
			ok: false,
			reason: `目标键冲突 ${plan.totals.conflictItems} 处（${plan.totals.conflicts} 个 run）：拒绝写入（不覆盖）`,
		};
	}
	if (!apply) {
		return base;
	}

	const results = [];
	try {
		for (const run of plan.runs) {
			assertLock(lock);
			results.push(await applyRun(client, run, lock));
		}
		assertLock(lock);
		if (deleteSource) {
			await deleteVerifiedSources(client, results, lock);
		}
	} catch (error) {
		error.partialResults = results;
		throw error;
	}
	base.results = results;
	base.totals.writes = results.reduce((n, r) => n + r.writes, 0);
	base.totals.indexWrites = results.reduce((n, r) => n + (r.indexWrites ?? 0), 0);
	base.totals.deleted = results.reduce((n, r) => n + r.deleted.length, 0);
	return base;
}

/**
 * 构建计划；有 conflict 时 apply=false（除非仅 dry-run 观察）。
 * apply 会先获取 `ai:migrate:lock`，并在 finally 按 token 释放。
 * @returns {{ok: boolean, reason?: string, direction: string, plan: object, results: object[], warnings: string[], totals: object}}
 */
export async function runMigration(client, options = {}) {
	const rollback = options.rollback === true;
	const apply = options.apply === true;
	const deleteSource = options.deleteSource === true;
	const runIds = assertRunIds(options.runIds);

	// 作用域安全：rollback 的“源”是 tagged 新键，可能是新版本原生 run；
	// 不带过滤 + 删源会误删它们。校验必须在建连/扫描前完成（CLI 在 parseArgs 同口径校验）。
	if (rollback && deleteSource && runIds.length === 0) {
		throw new MigrationError(
			'--rollback --delete-source 必须显式 --run-id（否则会删除新版本原生 tagged run）',
		);
	}

	if (!apply) {
		return await runMigrationInner(client, options, null);
	}
	const lock = await acquireMigrationLock(client);
	try {
		return await runMigrationInner(client, options, lock);
	} finally {
		await releaseMigrationLock(client, lock);
	}
}

// ── 输出 ───────────────────────────────────────────────────────────────────

function itemLine(item) {
	const tag = item.action.padEnd(9);
	const size = item.sourceExists ? item.size : '-';
	const pttl = item.sourceExists ? item.pttl : '-';
	const targetPttl = item.targetPttl ?? '-';
	const ttlShort = item.ttlShort === true;
	const reason = item.reason ? ` reason=${item.reason}` : '';
	const writes = item.writes > 0 ? ` writes=${item.writes}` : '';
	const verify = item.verified ? ' verify=value-equal' : '';
	return `  ${tag} ${item.type.toUpperCase().padEnd(6)} ${item.sourceKey} -> ${item.targetKey}`
		+ ` count=${size} pttl=${pttl} target_pttl=${targetPttl} ttl_short=${ttlShort}`
		+ `${writes}${verify}${reason}`;
}

function indexLine(index) {
	if (!index) {
		return null;
	}
	const action = index.applied ? `${index.action}->${index.applied}` : index.action;
	const source = index.sourceMember ?? '-';
	const target = index.targetMember ?? '-';
	const reason = index.reason ? ` reason=${index.reason}` : '';
	return `  index     SET    ${RUN_INDEX_KEY} ${source} -> ${target} action=${action}${reason}`;
}

export function formatPlan(result) {
	const lines = [
		`mode=${result.mode} direction=${result.direction} nodes_from=${NODES_ENV}`,
	];
	for (const run of result.plan.runs) {
		lines.push(
			`run ${run.runId} status=${run.status} sources=${run.sourceKeys} planned_copies=${run.plannedCopies}`
			+ (run.reason ? ` reason=${run.reason}` : ''),
		);
		for (const item of run.items) {
			lines.push(itemLine(item));
		}
		const index = indexLine(run.runIndex);
		if (index) {
			lines.push(index);
		}
	}
	const t = result.plan.totals;
	lines.push(
		`plan totals runs=${t.runs} ready=${t.ready} skipped=${t.skipped} conflicts=${t.conflicts}`
		+ ` planned_copies=${t.plannedCopies} source_keys=${t.sourceKeys}`,
	);
	return lines.join('\n');
}

export function formatApply(result) {
	const lines = [];
	for (const run of result.results) {
		lines.push(
			`run ${run.runId} status=${run.status} writes=${run.writes} index_writes=${run.indexWrites ?? 0}`
			+ ` deleted=${run.deleted.length}` + (run.reason ? ` reason=${run.reason}` : ''),
		);
		for (const item of run.items) {
			lines.push(itemLine(item));
		}
		const index = indexLine(run.index);
		if (index) {
			lines.push(index);
		}
		for (const key of run.deleted) {
			lines.push(`  deleted-source ${key}`);
		}
	}
	const t = result.totals;
	lines.push(
		`apply totals runs=${t.runs} ready=${t.ready} skipped=${t.skipped} conflicts=${t.conflicts}`
		+ ` writes=${t.writes} index_writes=${t.indexWrites ?? 0} deleted=${t.deleted}`,
	);
	return lines.join('\n');
}

export function rollbackHint(result) {
	if (result.direction === 'rollback') {
		return 'rollback complete; forward re-run: node scripts/redis-run-migration.mjs --apply';
	}
	const ids = result.plan.runs.filter((r) => r.status === 'ready').map((r) => r.runId);
	const perRun = ids.length > 0 ? ` (per run: --rollback --run-id ${ids.join(',')})` : '';
	return `rollback: node scripts/redis-run-migration.mjs --rollback${perRun}`
		+ '; sources retained by default (24h window), delete only with --delete-source after reconciliation';
}

// ── CLI ────────────────────────────────────────────────────────────────────

export function parseArgs(argv) {
	const options = {
		apply: false,
		rollback: false,
		deleteSource: false,
		help: false,
		runIds: [],
		tenantId: null,
		userId: null,
	};
	for (let i = 0; i < argv.length; i += 1) {
		const arg = argv[i];
		if (arg === '--apply') {
			options.apply = true;
		} else if (arg === '--rollback') {
			options.rollback = true;
		} else if (arg === '--delete-source') {
			options.deleteSource = true;
		} else if (arg === '--dry-run') {
			options.apply = false;
		} else if (arg === '--help' || arg === '-h') {
			options.help = true;
		} else if (arg === '--tenant-id' || arg === '--user-id') {
			const value = argv[++i];
			if (!/^-?\d+$/.test(String(value ?? ''))) {
				throw new MigrationError(`${arg} 缺少合法整数（旧数据无身份，目标身份必须显式声明）`);
			}
			if (arg === '--tenant-id') {
				options.tenantId = Number(value);
			} else {
				options.userId = Number(value);
			}
		} else if (arg.startsWith('--tenant-id=') || arg.startsWith('--user-id=')) {
			const eq = arg.indexOf('=');
			const name = arg.slice(0, eq);
			const value = arg.slice(eq + 1);
			if (!/^-?\d+$/.test(value)) {
				throw new MigrationError(`${name} 缺少合法整数（旧数据无身份，目标身份必须显式声明）`);
			}
			if (name === '--tenant-id') {
				options.tenantId = Number(value);
			} else {
				options.userId = Number(value);
			}
		} else if (arg === '--run-id') {
			const value = argv[++i];
			const ids = String(value ?? '')
				.split(',')
				.map((s) => s.trim())
				.filter(Boolean);
			if (ids.length === 0) {
				throw new MigrationError('--run-id 缺少值（空值/纯逗号会静默放大为全量，已拒绝）');
			}
			options.runIds.push(...ids);
		} else if (arg.startsWith('--run-id=')) {
			const ids = arg.slice('--run-id='.length)
				.split(',')
				.map((s) => s.trim())
				.filter(Boolean);
			if (ids.length === 0) {
				throw new MigrationError('--run-id= 缺少值（空值/纯逗号会静默放大为全量，已拒绝）');
			}
			options.runIds.push(...ids);
		} else {
			throw new MigrationError(`未知参数：${arg}`);
		}
	}
	if (!options.runIds.every(isValidRunId)) {
		throw new MigrationError(`--run-id 非法：${options.runIds.join(',')}`);
	}
	if ((options.tenantId === null) !== (options.userId === null)) {
		throw new MigrationError('--tenant-id 与 --user-id 必须成对给出（只给一半就是猜一个身份）');
	}
	options.identity = options.tenantId === null
		? null
		: normalizeIdentity({ tenantId: options.tenantId, userId: options.userId });
	if (!options.help && options.rollback && options.deleteSource && options.runIds.length === 0) {
		throw new MigrationError(
			'--rollback --delete-source 必须显式 --run-id（否则会删除新版本原生 tagged run）',
		);
	}
	return options;
}

function usage() {
	return [
		'用法:',
		'  node scripts/redis-run-migration.mjs [--run-id <id>[,<id>]] [--tenant-id <t> --user-id <u>] [--apply] [--delete-source]',
		'  node scripts/redis-run-migration.mjs --rollback [--run-id <id>[,<id>]] [--tenant-id <t> --user-id <u>] [--apply] [--delete-source]',
		'',
		`节点: ${NODES_ENV}=host:port[,host:port...]（必填，无默认值）`,
		'默认 dry-run；--apply 才写；--rollback 反向（tagged -> 旧裸键）。',
		'源默认保留；--delete-source 仅在逐项对账通过后删除源键。',
		'--rollback --delete-source 必须显式 --run-id。',
		'旧数据没有身份：目标身份用 --tenant-id/--user-id 显式声明（否则只得到 legacy {run:<id>}）。',
	].join('\n');
}

async function loadIoredis() {
	try {
		const mod = await import('ioredis');
		return mod.default ?? mod;
	} catch (error) {
		// 仓库未在根目录安装 node_modules：复用 frontend 已安装的 ioredis@6（不新增依赖）。
		const fallback = new URL('../frontend/node_modules/ioredis/built/index.js', import.meta.url);
		try {
			const mod = await import(fallback.href);
			return mod.default ?? mod;
		} catch {
			throw new MigrationError(
				`无法加载 ioredis（先安装 frontend 依赖）：${error.message}`,
			);
		}
	}
}

export async function connectCluster(nodes) {
	const Redis = await loadIoredis();
	const Cluster = Redis.Cluster ?? Redis;
	const client = new Cluster(nodes, {
		clusterRetryStrategy(times) {
			return times >= 3 ? null : 200;
		},
		redisOptions: {
			connectTimeout: 5000,
			maxRetriesPerRequest: 2,
			enableReadyCheck: true,
			// 目标部署含 Redis 5.0.14：ioredis@6 默认 RESP3（HELLO 3）与 CLIENT SETINFO 都不支持。
			protocol: 2,
			disableClientInfo: true,
		},
		slotsRefreshTimeout: 5000,
	});
	// 后台重连失败会以 error 事件出现；吞掉它，命令本身仍会 reject（避免进程被未处理事件打崩）。
	client.on('error', () => {});
	await client.ping();
	return client;
}

export async function main(argv = process.argv.slice(2)) {
	const options = parseArgs(argv);
	if (options.help) {
		console.log(usage());
		return 0;
	}
	const nodes = nodesFromEnv(process.env);
	let client;
	try {
		client = await connectCluster(nodes);
		const result = await runMigration(client, options);
		console.log(formatPlan(result));
		for (const warning of result.warnings ?? []) {
			console.error(`WARNING: ${warning}`);
		}
		if (!result.ok) {
			console.error(`MIGRATION FAILED: ${result.reason}`);
			return 1;
		}
		if (result.mode === 'apply') {
			console.log(formatApply(result));
			if (result.direction === 'rollback' && result.totals.deleted > 0) {
				console.error(
					'WARNING: --delete-source 在 rollback 下删除了 tagged 新键；'
					+ '若产品仍运行新版本，请立即停止或重新迁移。',
				);
			}
			console.log(rollbackHint(result));
		} else {
			console.log('dry-run: no writes performed');
		}
		return 0;
	} catch (error) {
		if (error.partialResults?.length) {
			console.log(formatApply({
				results: error.partialResults,
				totals: {
					runs: error.partialResults.length,
					ready: 0,
					skipped: 0,
					conflicts: 0,
					writes: error.partialResults.reduce((n, r) => n + r.writes, 0),
					indexWrites: error.partialResults.reduce((n, r) => n + (r.indexWrites ?? 0), 0),
					deleted: error.partialResults.reduce((n, r) => n + r.deleted.length, 0),
				},
			}));
		}
		console.error(`MIGRATION FAILED: ${error.message}`);
		return 1;
	} finally {
		if (client) {
			try {
				client.disconnect();
			} catch {
				// 断开失败不改变退出码
			}
		}
	}
}

const isDirectRun = process.argv[1]
	&& pathToFileURL(process.argv[1]).href === import.meta.url;

if (isDirectRun) {
	main().then((code) => {
		process.exitCode = code;
	}).catch((error) => {
		console.error(`MIGRATION FAILED: ${error.message}`);
		process.exitCode = 1;
	});
}
