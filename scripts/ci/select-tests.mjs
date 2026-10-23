#!/usr/bin/env node
/**
 * 选择性测试选择器（B 期，设计稿 §2、§3.5、§7.2）。
 *
 * 输入：变更集（暂存树 ∪ 未跟踪 ∪ 未暂存，K1）、倒排表 backend/target/testmap/map.json。
 * 输出：`includes.txt`（一行一个测试类 FQN，或单行 `ALL`）+ 审计 json（§2.5）。
 *
 * 规则顺序有意如此：**兜底判定先于查表**，任何「不确定」在查表之前就退化成全量（F1~F13）。
 * 零第三方依赖，纯函数部分导出给单测（scripts/ci/select-tests.test.mjs）。
 *
 * 用法：
 *   node scripts/ci/select-tests.mjs --base <merge-base> \
 *     --map backend/target/testmap/map.json \
 *     --out backend/target/selection/includes.txt \
 *     [--audit backend/target/testmap/selection/<treeSha>.json] [--all] [--verify-map]
 *
 * `--verify-map` 核对倒排表键覆盖，分母分两口径：全量源码 FQN 与「可记录类」（剔除顶层声明为
 * interface / @interface 的生产类——JaCoCo 对它们结构上不产生 execution data）。两数都打印，
 * 硬闸只看后者，与录制器 TestmapRecorder.assertCoverageFloor 的 B0 闸同口径（L-100）。
 */

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

export const JACOCO_PINNED = '0.8.15';
export const MIN_KEY_OVERLAP = 0.95;
export const MAX_TABLE_AGE_MS = 7 * 24 * 3600 * 1000;
export const MAX_TABLE_COMMITS = 200;
export const FANOUT_LIMIT = 50;
export const SIZE_CAP_RATIO = 0.6;

const MAIN_PREFIX = 'backend/src/main/java/';
const TEST_PREFIX = 'backend/src/test/java/';

// ---------------------------------------------------------------- 纯函数面

export function isMainJava(path) {
  return path.startsWith(MAIN_PREFIX) && path.endsWith('.java') && !/\/(package|module)-info\.java$/.test(path);
}

export function isTestJava(path) {
  return path.startsWith(TEST_PREFIX) && /Test\.java$/.test(path);
}

export function toFqn(path) {
  return path.replace(/^.*?src\/main\/java\//, '')
    .replace(/^.*?src\/test\/java\//, '')
    .replace(/\.java$/, '')
    .replace(/\//g, '.');
}

/** 运行时产物/本地痕迹不算源码改动（K1：不剔除会把「本地跑过的东西」当改动，直接全量）。 */
export function isSourceRelevant(path) {
  if (path.startsWith('data/') || path.startsWith('.testmap/')) return false;
  if (path.startsWith('backend/target/') || path.startsWith('backend/data/')) return false;
  if (path.startsWith('frontend/node_modules/') || path.startsWith('frontend/dist/')) return false;
  if (/\.(log|db|mv\.db|trace\.db)$/.test(path)) return false;
  return true;
}

/**
 * 顶层类型声明。与录制器 `TestmapRecorder.TOP_LEVEL_TYPE` 逐字对应，改一处必须改另一处：
 * 两边的「可记录类」集合必须相等，否则 --verify-map 与录制器 B0 闸又会各说各话。
 *
 * 认不出时返回 'class'（= 可记录），与录制器同默认：读不到/解析失败按可记录处理，
 * 方向保守（只会把分母算大、覆盖率算低）。
 */
const TOP_LEVEL_TYPE = /^(?:public\s+|final\s+|abstract\s+|sealed\s+|non-sealed\s+|strictfp\s+)*(class|interface|@interface|enum|record)\b/m;

export function topLevelKind(source) {
  const m = TOP_LEVEL_TYPE.exec(source || '');
  return m ? m[1] : 'class';
}

/** 可记录类 = 顶层声明不是 interface / @interface（JaCoCo 结构性不产生 execution data）。 */
export function isRecordableSource(source) {
  const kind = topLevelKind(source);
  return kind !== 'interface' && kind !== '@interface';
}

const ASSEMBLY_ANNOTATION = [
  /@Configuration\b/,
  /@ConfigurationProperties\b/,
  /@Component\b/,
  /@Service\b/,
  /@Repository\b/,
  /@Controller\b/,
  /@RestController\b/,
  /@ConditionalOn\w*/,
];
const ASSEMBLY_SUFFIX = /(Config|Configuration|Service|Repository|Component|Controller|Properties|AutoConfiguration)$/;

/** F10：装配语义类（按注解或类名后缀判定）。 */
export function isAssemblyClass(fqn, source) {
  if (ASSEMBLY_SUFFIX.test(fqn.split('.').pop())) return true;
  if (!source) return false;
  return ASSEMBLY_ANNOTATION.some((re) => re.test(source));
}

/**
 * F1~F4、F6、F10、F11、F13 的静态部分（不查表即可判定）。
 * @returns {Array<{rule:string, reason:string}>}
 */
export function staticFallbacks(changed, sourceOf = () => '') {
  const hits = [];
  for (const item of changed) {
    const p = item.path;
    if (p === 'backend/pom.xml' || /(^|\/)pom\.xml$/.test(p) || p.startsWith('.mvn/')
        || p === 'backend/maven-settings.xml' || p === 'maven-settings.xml') {
      hits.push({ rule: 'F1', reason: `依赖/插件面变更：${p}` });
    }
    if (p.startsWith('backend/src/main/resources/')) {
      hits.push({ rule: 'F2', reason: `配置/i18n/迁移面变更：${p}` });
    }
    if (p.startsWith('backend/src/test/resources/')) {
      hits.push({ rule: 'F3', reason: `测试公共输入变更：${p}` });
    }
    if (p.startsWith('docs/kb-samples/')) {
      hits.push({ rule: 'F4', reason: `首启播种源变更：${p}` });
    }
    if (p.startsWith(TEST_PREFIX) && !isTestJava(p)
        && !p.startsWith(`${TEST_PREFIX}com/example/configmgr/testmap/`)) {
      hits.push({ rule: 'F6', reason: `测试夹具/基类变更（非 *Test.java）：${p}` });
    }
    if (p.startsWith('scripts/ci/select-tests') || p === 'scripts/ci/gate.mjs'
        || p.startsWith('scripts/ci/testmap/') || p.startsWith('backend/src/test/java/com/example/configmgr/testmap/')
        || p.startsWith('backend/src/test/resources/META-INF/') || p.startsWith('.github/workflows/')) {
      hits.push({ rule: 'F11', reason: `选择器/门禁/录制器自身变更：${p}` });
    }
    if (/(^|\/)messages_[^/]*\.properties$/.test(p) || p.startsWith('frontend/src/locales/')
        || /(^|\/)(TimeZone|Locale)[^/]*$/.test(p) || /(^|\/)application[^/]*\.ya?ml$/.test(p)) {
      hits.push({ rule: 'F13', reason: `locale/时区敏感面变更：${p}` });
    }
    if (isMainJava(p) && item.status !== 'D') {
      const fqn = toFqn(p);
      if (isAssemblyClass(fqn, sourceOf(p))) {
        hits.push({ rule: 'F10', reason: `装配语义类变更（Spring 上下文缓存归属不可靠）：${fqn}` });
      }
    }
  }
  return hits;
}

export function isTableStale(map, now = Date.now()) {
  if (!map || !map.meta) return { stale: true, reason: 'map-missing' };
  const meta = map.meta;
  if (meta.jacoco !== JACOCO_PINNED) return { stale: true, reason: `map-jacoco(${meta.jacoco})` };
  if (typeof meta.builtAtEpochMs === 'number' && now - meta.builtAtEpochMs > MAX_TABLE_AGE_MS) {
    return { stale: true, reason: 'map-stale-7d' };
  }
  if (meta.deterministic === false && meta.unionRepaired !== true) {
    return { stale: true, reason: 'map-unstable' };
  }
  return { stale: false, reason: null };
}

/**
 * 倒排表键覆盖核对（纯函数，--verify-map 用）。
 *
 * 两口径同时算：全量源码 FQN（含 interface / @interface）与可记录类 FQN。硬闸只看后者，
 * 与录制器 B0 闸一致；前者保留在输出里做审计（`sourceCount - recordableCount` 即结构性不可记录类数量）。
 *
 * @param {Array<{fqn:string, recordable:boolean}>} mainClasses 生产类清单
 * @param {object|null} map 倒排表
 * @param {number} [min] 硬闸阈值（可记录类口径）
 */
export function verifyMapCoverage(mainClasses, map, min = MIN_KEY_OVERLAP) {
  const keys = new Set(Object.keys(map?.index || {}));
  const covered = mainClasses.filter((c) => keys.has(c.fqn));
  const recordable = mainClasses.filter((c) => c.recordable);
  const coveredRecordable = recordable.filter((c) => keys.has(c.fqn));
  const ratio = (hit, total) => (total === 0 ? 1 : hit / total);
  const keyOverlapRatio = ratio(covered.length, mainClasses.length);
  const keyOverlapRatioConcrete = ratio(coveredRecordable.length, recordable.length);
  return {
    keyCount: keys.size,
    sourceCount: mainClasses.length,
    coveredCount: covered.length,
    keyOverlapRatio,
    recordableCount: recordable.length,
    recordableCoveredCount: coveredRecordable.length,
    keyOverlapRatioConcrete,
    missing: mainClasses.filter((c) => !keys.has(c.fqn)).map((c) => c.fqn),
    missingRecordable: recordable.filter((c) => !keys.has(c.fqn)).map((c) => c.fqn),
    nonRecordable: mainClasses.filter((c) => !c.recordable).map((c) => c.fqn),
    pass: keyOverlapRatioConcrete >= min,
  };
}

/**
 * 核心选择逻辑（纯函数，无 git/IO）。
 *
 * @param {object} input
 * @param {Array<{status:string,path:string}>} input.changed 变更集
 * @param {object|null} input.map 倒排表
 * @param {string[]} input.smoke 固定冒烟集（帧契约 + 上下文 + 无表测试 + 粘性失败）
 * @param {string[]} input.trackedTests 索引里现存的测试类（K4 用途）
 * @param {(p:string)=>string} [input.sourceOf] 取变更类源码（F10）
 * @param {(p:string)=>string|null} [input.blobOf] 取 HEAD 里该路径 blob（F5）
 * @param {number} [input.headDistance] 表头提交到 HEAD 的距离
 */
export function selectTests({
  changed,
  map,
  smoke = [],
  trackedTests = [],
  sourceOf = () => '',
  blobOf = () => null,
  headDistance = 0,
  now = Date.now(),
}) {
  const relevant = changed.filter((c) => isSourceRelevant(c.path));
  const base = {
    changed: relevant.map((c) => c.path),
    rulesHit: [],
    fallback: null,
    reason: null,
    selected: [],
    smoke: [],
    excludedStale: [],
    warnings: [],
  };

  // 空变更集：只跑冒烟集，明确打印「未跑全量」（m5）
  if (relevant.length === 0) {
    const smokeOnly = unique(smoke).sort();
    return {
      ...base,
      selected: smokeOnly,
      smoke: smokeOnly,
      warnings: ['变更集为空：只跑固定冒烟集，未跑全量'],
    };
  }

  const staticHits = staticFallbacks(relevant, sourceOf);
  if (staticHits.length > 0) {
    return full(base, unique(staticHits.map((h) => h.rule)), staticHits[0].reason);
  }

  const stale = isTableStale(map, now);
  if (stale.stale) {
    return full(base, [stale.reason], `倒排表不可用（${stale.reason}）：须 mvn -o -Ptestmap test 重建`);
  }
  const meta = map.meta || {};
  if (headDistance > MAX_TABLE_COMMITS) {
    return full(base, ['map-stale-commits'], `倒排表过旧（距 HEAD ${headDistance} 个提交）`);
  }

  // F5：新增/删除生产类、无键、blob 变更
  const mainClasses = relevant.filter((c) => isMainJava(c.path));
  const f5 = [];
  for (const c of mainClasses) {
    if (c.status === 'A' || c.status === 'D') {
      f5.push(`F5 新增/删除生产类：${c.path}`);
      continue;
    }
    const fqn = toFqn(c.path);
    const entry = (map.index || {})[fqn];
    if (!entry || !Array.isArray(entry.tests) || entry.tests.length === 0) {
      f5.push(`F5 无键：${fqn}`);
      continue;
    }
    if (entry.blobSha) {
      const current = blobOf(c.path);
      if (current && current !== entry.blobSha) {
        f5.push(`F5 blob 变更（表记录的边对应旧版本）：${fqn}`);
      }
    }
  }
  if (f5.length > 0) {
    return full(base, [...new Set(f5.map((s) => s.split(' ')[0]))], f5.join('；'));
  }

  // F7：扇出过大
  const fanoutHits = [];
  for (const c of mainClasses) {
    const entry = (map.index || {})[toFqn(c.path)];
    const n = entry ? entry.tests.length : 0;
    if (n > FANOUT_LIMIT) fanoutHits.push({ rule: 'F7', reason: `${toFqn(c.path)} 扇出 ${n} > ${FANOUT_LIMIT}` });
  }
  if (fanoutHits.length > 0) {
    return full(base, ['F7'], fanoutHits.map((h) => h.reason).join('；'));
  }

  // 倒排查表
  const fromIndex = new Set();
  for (const c of mainClasses) {
    const entry = (map.index || {})[toFqn(c.path)];
    (entry?.tests || []).forEach((t) => fromIndex.add(t));
  }
  const changedTests = relevant.filter((c) => isTestJava(c.path)).map((c) => toFqn(c.path));
  const missingEntries = trackedTests.filter((t) => {
    const deps = (map.tests || {})[t];
    return !Array.isArray(deps) || deps.length === 0;
  });
  // 不稳定类（两次录制不一致）：依赖边不可信 ⇒ 永久常跑（§3.6），不因此把整表退成全量
  const unstable = Array.isArray(meta.unstableTests) ? meta.unstableTests : [];
  const smokeSet = unique([...smoke, ...unstable, ...missingEntries, ...changedTests]);
  const merged = unique([...fromIndex, ...smokeSet]);
  const trackedSet = new Set(trackedTests);
  const excludedStale = merged.filter((t) => trackedSet.size > 0 && !trackedSet.has(t));
  let selected = merged.filter((t) => !excludedStale.includes(t));

  if (selected.length === 0) {
    return full(base, ['F8'], '兜底与冒烟集合并后仍为空');
  }
  const testClassCount = meta.testClassCount || trackedTests.length || selected.length;
  if (selected.length > SIZE_CAP_RATIO * testClassCount) {
    return full(base, ['规模封顶'], `选中 ${selected.length}/${testClassCount} > ${Math.round(SIZE_CAP_RATIO * 100)}%`);
  }

  return {
    ...base,
    rulesHit: [],
    fallback: null,
    reason: null,
    selected: selected.sort(),
    smoke: unique(smokeSet).sort(),
    excludedStale,
    warnings: excludedStale.length > 0 ? [`已剔除表内陈旧条目：${excludedStale.join(', ')}`] : [],
  };
}

function full(base, rulesHit, reason) {
  return { ...base, fallback: rulesHit[0], rulesHit, reason, selected: [], smoke: [] };
}

function unique(list) {
  return [...new Set(list.filter(Boolean))];
}

// ------------------------------------------------------------------- CLI

function git(args, cwd) {
  return execFileSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
}

/** 取源码：工作树优先，回落到索引（新增未落盘/已删除的情形），都拿不到返回 ''。 */
function readSource(root, relPath) {
  try { return readFileSync(resolve(root, relPath), 'utf8'); } catch { /* 走索引 */ }
  try { return git(['show', `:${relPath}`], root); } catch { return ''; }
}

function parseNameStatus(text) {
  const out = [];
  for (const line of text.split('\n')) {
    if (!line.trim()) continue;
    const parts = line.split('\t');
    const status = parts[0][0];
    if (status === 'R' || status === 'C') out.push({ status, path: parts[2] });
    else out.push({ status, path: parts[1] });
  }
  return out;
}

function main(argv) {
  const args = { base: null, map: 'backend/target/testmap/map.json', out: 'backend/target/selection/includes.txt',
    audit: null, all: false, verifyMap: false, quiet: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--base') args.base = argv[++i];
    else if (a === '--map') args.map = argv[++i];
    else if (a === '--out') args.out = argv[++i];
    else if (a === '--audit') args.audit = argv[++i];
    else if (a === '--all') args.all = true;
    else if (a === '--verify-map') args.verifyMap = true;
    else if (a === '--quiet') args.quiet = true;
    else if (a === '--help' || a === '-h') { console.log(readFileSync(new URL(import.meta.url)).toString().split('*/')[0]); return 0; }
    else throw new Error(`未知参数：${a}`);
  }

  const root = resolve(git(['rev-parse', '--show-toplevel'], process.cwd()).trim());
  const log = (...m) => { if (!args.quiet) console.log(...m); };

  let map = null;
  try {
    map = JSON.parse(readFileSync(resolve(root, args.map), 'utf8'));
  } catch (e) {
    log(`[select] 倒排表不可读（${args.map}）：${e.message}`);
  }

  const trackedTests = git(['ls-files', '--', TEST_PREFIX], root)
    .split('\n').map((s) => s.trim()).filter((p) => p && /Test\.java$/.test(p)).map(toFqn).sort();

  if (args.verifyMap) {
    const trackedMain = git(['ls-files', '--', MAIN_PREFIX], root)
      .split('\n').map((s) => s.trim())
      .filter((p) => p && p.endsWith('.java') && !/\/(package|module)-info\.java$/.test(p));
    // 可记录类判定按源码内容（与录制器同一条正则）；文件读不到时 readSource 返回 '' ⇒ 按可记录处理
    const mainClasses = trackedMain.map((p) => ({
      fqn: toFqn(p),
      recordable: isRecordableSource(readSource(root, p)),
    }));
    const c = verifyMapCoverage(mainClasses, map);
    const pct = (x) => `${(x * 100).toFixed(2)}%`;
    console.log(`[verify-map] 键集 ${c.keyCount}；全量口径 ${c.coveredCount}/${c.sourceCount} = ${pct(c.keyOverlapRatio)}` +
      `（含接口/注解，JaCoCo 结构性不可记录）；可记录类口径 ${c.recordableCoveredCount}/${c.recordableCount} = ${pct(c.keyOverlapRatioConcrete)}`);
    if (c.nonRecordable.length) {
      console.log(`[verify-map] 计入全量口径但不计入硬闸（接口/注解 ${c.nonRecordable.length} 个，前 10）：${c.nonRecordable.slice(0, 10).join(', ')}`);
    }
    if (c.missingRecordable.length) {
      console.log(`[verify-map] 未覆盖的可记录类（前 10）：${c.missingRecordable.slice(0, 10).join(', ')}`);
    }
    console.log(`[verify-map] 硬闸：可记录类口径 ≥ ${pct(MIN_KEY_OVERLAP)} ⇒ ${c.pass ? '通过' : '未通过'}`);
    return c.pass ? 0 : 2;
  }

  if (args.all) {
    writeIncludes(resolve(root, args.out), [], true);
    log('[select] --all：全量');
    return 0;
  }

  const base = args.base || git(['merge-base', 'origin/main', 'HEAD'], root).trim();
  // 一次 diff（base..工作树）= 暂存 ∪ 未暂存；未跟踪单列（K1 单调放大）
  const worktree = parseNameStatus(git(['diff', '--name-status', '--find-renames', base], root));
  const untracked = git(['ls-files', '--others', '--exclude-standard'], root)
    .split('\n').map((s) => s.trim()).filter(Boolean).map((path) => ({ status: 'A', path }));
  let unstagedCount = 0;
  try {
    unstagedCount = git(['diff', '--name-only'], root).split('\n').filter(Boolean).length;
  } catch { /* 无未暂存改动 */ }
  const mergedMap = new Map();
  for (const item of [...worktree, ...untracked]) mergedMap.set(item.path, item);
  const changed = [...mergedMap.values()];

  if (untracked.length > 0 || unstagedCount > 0) {
    const touched = [...untracked, ...worktree].some((c) => isSourceRelevant(c.path) && /\.(java|xml|yml|yaml|properties)$/.test(c.path));
    if (touched) log('[select] 警告：有未暂存/未跟踪改动，已计入变更集（单调放大）');
  }

  const conformance = trackedTests.filter((t) => t.includes('.ai.conformance.'));
  let contextTests = Array.isArray(map?.meta?.contextTests) ? map.meta.contextTests : [];
  if (contextTests.length === 0) {
    try {
      contextTests = git(['grep', '-l', '@SpringBootTest', '--', TEST_PREFIX], root)
        .split('\n').map((s) => s.trim()).filter(Boolean).map(toFqn).sort().slice(0, 2);
    } catch { /* git grep 无命中时退出码 1 */ }
  }
  const sticky = readStickyFailures(root);

  const sourceOf = (p) => readSource(root, p);
  const blobOf = (p) => {
    try { return git(['rev-parse', `HEAD:${p}`], root).trim(); } catch { return null; }
  };
  let headDistance = 0;
  if (map?.meta?.headSha && map.meta.headSha !== 'unknown') {
    try { headDistance = Number(git(['rev-list', '--count', `${map.meta.headSha}..HEAD`], root).trim()); } catch { headDistance = 0; }
  }

  const result = selectTests({
    changed,
    map,
    smoke: [...conformance, ...contextTests, ...sticky],
    trackedTests,
    sourceOf,
    blobOf,
    headDistance,
  });

  const includes = resolve(root, args.out);
  writeIncludes(includes, result.selected, Boolean(result.fallback));
  if (args.audit) writeAudit(resolve(root, args.audit), { root, base, args, changed, result, map });

  if (result.fallback) {
    log(`[select] 全量：${result.reason}（规则 ${result.rulesHit.join(', ') || '-'}）`);
  } else {
    log(`[select] 选中 ${result.selected.length} 个测试类 / 共 ${map?.meta?.testClassCount ?? '?'}` +
        `${result.smoke.length ? `（其中冒烟 ${result.smoke.length}）` : ''}`);
    log(`[select] 命中规则：${result.rulesHit.join(', ') || '无（按倒排查表）'}`);
    log(`[select] 清单：${args.out}${args.audit ? `，审计：${args.audit}` : ''}`);
  }
  result.warnings.forEach((w) => log(`[select] ${w}`));
  return 0;
}

function readStickyFailures(root) {
  const p = resolve(root, 'backend/target/selection/last-failures.json');
  if (!existsSync(p)) return [];
  try {
    const data = JSON.parse(readFileSync(p, 'utf8'));
    const ts = Number(data.builtAtEpochMs || data.ts || 0);
    if (ts && Date.now() - ts > MAX_TABLE_AGE_MS) return [];
    return Array.isArray(data.tests) ? data.tests : [];
  } catch { return []; }
}

function writeIncludes(path, tests, all = false) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, all ? 'ALL\n' : `${tests.join('\n')}${tests.length ? '\n' : ''}`, 'utf8');
}

function writeAudit(path, { root, args, base, changed, result, map }) {
  mkdirSync(dirname(path), { recursive: true });
  let treeSha = '';
  try { treeSha = execFileSync('git', ['write-tree'], { cwd: root, encoding: 'utf8' }).trim(); } catch { /* ignore */ }
  const tableSha256 = map ? createHash('sha256').update(JSON.stringify(map)).digest('hex') : null;
  const audit = {
    treeSha,
    base,
    generatedAt: new Date().toISOString(),
    changedCount: result.changed.length,
    changed: result.changed,
    rulesHit: result.rulesHit,
    selected: result.selected,
    smoke: result.smoke,
    excludedStale: result.excludedStale,
    tableSha256,
    tableHeadSha: map?.meta?.headSha ?? null,
    fallback: result.fallback,
    reason: result.reason,
    mapPath: args.map,
  };
  writeFileSync(path, `${JSON.stringify(audit, null, 2)}\n`, 'utf8');
}

if (import.meta.url === `file://${process.argv[1]?.replace(/\\/g, '/')}` || process.argv[1]?.endsWith('select-tests.mjs')) {
  try {
    process.exitCode = main(process.argv.slice(2));
  } catch (e) {
    console.error(`[select] 失败：${e.message}`);
    process.exitCode = 3;
  }
}
