#!/usr/bin/env node
/**
 * 选择器纯函数单测（零依赖，node --test 运行）。
 * 覆盖 §2.4 兜底规则 F1~F13、F5 的三种子条件、K4 陈旧条目、空变更集、规模封顶，
 * 以及 --verify-map 的键覆盖双口径（全量 / 可记录类，L-100）。
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  costEstimate,
  isAssemblyClass,
  isRecordableSource,
  isSourceRelevant,
  isTableStale,
  selectTests,
  staticFallbacks,
  toFqn,
  topLevelKind,
  verifyMapCoverage,
} from './select-tests.mjs';

const MAP = {
  meta: {
    builtAtEpochMs: Date.now() - 60_000,
    headSha: 'deadbeef',
    jacoco: '0.8.15',
    deterministic: true,
    testClassCount: 100,
    unstableTests: [],
  },
  tests: {
    'p.AlphaTest': ['p.Alpha'],
    'p.BetaTest': ['p.Beta', 'p.Shared'],
    'p.SmokeTest': ['p.Smoke'],
  },
  index: {
    'p.Alpha': { tests: ['p.AlphaTest'], blobSha: 'blob-alpha' },
    'p.Beta': { tests: ['p.BetaTest'], blobSha: 'blob-beta' },
    'p.Shared': { tests: ['p.BetaTest'], blobSha: 'blob-shared' },
  },
};

const TRACKED = ['p.AlphaTest', 'p.BetaTest', 'p.SmokeTest'];
const SMOKE = ['p.SmokeTest'];

function run(changed, overrides = {}) {
  return selectTests({
    changed,
    map: MAP,
    smoke: SMOKE,
    trackedTests: TRACKED,
    blobOf: () => null,
    ...overrides,
  });
}

test('路径 → FQN', () => {
  assert.equal(toFqn('backend/src/main/java/p/Alpha.java'), 'p.Alpha');
  assert.equal(toFqn('backend/src/test/java/p/AlphaTest.java'), 'p.AlphaTest');
});

test('运行时产物不算源码改动（K1）', () => {
  assert.equal(isSourceRelevant('data/h2.db'), false);
  assert.equal(isSourceRelevant('backend/target/x.json'), false);
  assert.equal(isSourceRelevant('foo.log'), false);
  assert.equal(isSourceRelevant('backend/src/main/java/p/Alpha.java'), true);
});

test('F1 pom 变更 ⇒ 全量', () => {
  const r = run([{ status: 'M', path: 'backend/pom.xml' }]);
  assert.equal(r.fallback, 'F1');
});

test('F2 配置变更 ⇒ 全量', () => {
  assert.equal(run([{ status: 'M', path: 'backend/src/main/resources/application.yml' }]).fallback, 'F2');
});

test('F3 测试资源变更 ⇒ 全量', () => {
  assert.equal(run([{ status: 'M', path: 'backend/src/test/resources/x.yml' }]).fallback, 'F3');
});

test('F4 kb-samples 变更 ⇒ 全量', () => {
  assert.equal(run([{ status: 'M', path: 'docs/kb-samples/a.md' }]).fallback, 'F4');
});

test('F5 无键 ⇒ 全量', () => {
  const r = run([{ status: 'M', path: 'backend/src/main/java/p/Gamma.java' }]);
  assert.equal(r.fallback, 'F5');
  assert.match(r.reason, /无键/);
});

test('F5 新增/删除生产类 ⇒ 全量', () => {
  assert.equal(run([{ status: 'A', path: 'backend/src/main/java/p/New.java' }]).fallback, 'F5');
  assert.equal(run([{ status: 'D', path: 'backend/src/main/java/p/Alpha.java' }]).fallback, 'F5');
});

test('F5 blob 变更 ⇒ 全量', () => {
  const r = run([{ status: 'M', path: 'backend/src/main/java/p/Alpha.java' }], { blobOf: () => 'blob-new' });
  assert.equal(r.fallback, 'F5');
  assert.match(r.reason, /blob/);
});

test('F6 夹具变更 ⇒ 全量', () => {
  assert.equal(run([{ status: 'M', path: 'backend/src/test/java/p/Fixture.java' }]).fallback, 'F6');
});

test('F7 扇出 > 50 ⇒ 全量', () => {
  const big = { ...MAP, index: { ...MAP.index, 'p.Alpha': { tests: Array.from({ length: 51 }, (_, i) => `p.T${i}Test`), blobSha: 'blob-alpha' } } };
  const r = selectTests({ changed: [{ status: 'M', path: 'backend/src/main/java/p/Alpha.java' }], map: big, smoke: [], trackedTests: [], blobOf: () => null });
  assert.equal(r.fallback, 'F7');
});

test('F8 空结果 ⇒ 全量', () => {
  const r = selectTests({ changed: [{ status: 'M', path: 'backend/src/main/java/p/Alpha.java' }], map: MAP, smoke: [], trackedTests: [], blobOf: () => null });
  assert.notEqual(r.fallback, 'F8'); // 有键时有结果
  const empty = selectTests({ changed: [{ status: 'M', path: 'backend/src/main/java/p/Alpha.java' }], map: { ...MAP, index: { 'p.Alpha': { tests: [], blobSha: null } } }, smoke: [], trackedTests: [], blobOf: () => null });
  assert.equal(empty.fallback, 'F5'); // 空 tests 先按无键处理，比 F8 更早
});

test('F10 装配注解类 ⇒ 全量', () => {
  const r = run([{ status: 'M', path: 'backend/src/main/java/p/Alpha.java' }], {
    sourceOf: () => '@Configuration class Alpha {}',
    blobOf: () => 'blob-alpha',
  });
  assert.equal(r.fallback, 'F10');
});

test('F10 只看注解/后缀，不误伤普通类', () => {
  assert.equal(isAssemblyClass('p.Alpha', 'class Alpha { void x() {} }'), false);
  assert.equal(isAssemblyClass('p.AiProperties', ''), true);
});

test('F11 选择器自身变更 ⇒ 全量', () => {
  assert.equal(run([{ status: 'M', path: 'scripts/ci/select-tests.mjs' }]).fallback, 'F11');
  assert.equal(run([{ status: 'M', path: 'backend/src/test/java/com/example/configmgr/testmap/TestmapRecorder.java' }]).fallback, 'F11');
});

test('F12 表不稳定 ⇒ 全量', () => {
  const unstable = { ...MAP, meta: { ...MAP.meta, deterministic: false } };
  assert.equal(selectTests({ changed: [{ status: 'M', path: 'backend/src/main/java/p/Alpha.java' }], map: unstable, smoke: [], trackedTests: TRACKED, blobOf: () => null }).fallback, 'map-unstable');
});

test('并集修复后：不稳定类进冒烟，不再整表退全量', () => {
  const repaired = { ...MAP, meta: { ...MAP.meta, deterministic: false, unionRepaired: true, unstableTests: ['p.BetaTest'] } };
  const r = selectTests({ changed: [{ status: 'M', path: 'backend/src/main/java/p/Alpha.java' }], map: repaired, smoke: ['p.SmokeTest'], trackedTests: TRACKED, blobOf: () => 'blob-alpha' });
  assert.equal(r.fallback, null);
  assert.ok(r.selected.includes('p.BetaTest'));
  assert.ok(r.selected.includes('p.AlphaTest'));
});

test('F13 i18n/locale 面 ⇒ 全量', () => {
  assert.equal(run([{ status: 'M', path: 'backend/src/main/resources/messages_zh.properties' }]).fallback, 'F2');
  assert.equal(run([{ status: 'M', path: 'frontend/src/locales/zh.ts' }]).fallback, 'F13');
  assert.equal(run([{ status: 'M', path: 'frontend/src/utils/TimeZoneKit.ts' }]).fallback, 'F13');
});

test('表缺失 ⇒ 全量', () => {
  assert.equal(selectTests({ changed: [{ status: 'M', path: 'backend/src/main/java/p/Alpha.java' }], map: null, smoke: [], trackedTests: [] }).fallback, 'map-missing');
});

test('表过期 7 天 ⇒ 全量', () => {
  const stale = { ...MAP, meta: { ...MAP.meta, builtAtEpochMs: Date.now() - 8 * 24 * 3600 * 1000 } };
  assert.equal(isTableStale(stale).stale, true);
});

test('正常单类改动 ⇒ 查表选中 + 固定冒烟', () => {
  const r = run([{ status: 'M', path: 'backend/src/main/java/p/Alpha.java' }], { blobOf: () => 'blob-alpha' });
  assert.equal(r.fallback, null);
  assert.deepEqual(r.selected, ['p.AlphaTest', 'p.SmokeTest']);
});

test('未在表内的测试类自动进冒烟集（B2/§3.6）', () => {
  const r = run([{ status: 'M', path: 'backend/src/main/java/p/Alpha.java' }], {
    blobOf: () => 'blob-alpha',
    trackedTests: [...TRACKED, 'p.NewTest'],
  });
  assert.ok(r.selected.includes('p.NewTest'));
});

test('改动测试类必跑（K1）', () => {
  const r = run([{ status: 'M', path: 'backend/src/test/java/p/BetaTest.java' }, { status: 'M', path: 'backend/src/main/java/p/Alpha.java' }], { blobOf: () => 'blob-alpha' });
  assert.ok(r.selected.includes('p.BetaTest'));
});

test('空变更集 ⇒ 只跑冒烟集', () => {
  const r = run([]);
  assert.equal(r.fallback, null);
  assert.deepEqual(r.selected, ['p.SmokeTest']);
  assert.match(r.warnings[0], /变更集为空/);
});

test('K4 陈旧条目被剔除', () => {
  const stale = {
    ...MAP,
    index: { 'p.Alpha': { tests: ['p.AlphaTest', 'p.GoneTest'], blobSha: 'blob-alpha' } },
  };
  const r = selectTests({
    changed: [{ status: 'M', path: 'backend/src/main/java/p/Alpha.java' }],
    map: stale,
    smoke: [],
    trackedTests: ['p.AlphaTest'],
    blobOf: () => 'blob-alpha',
  });
  assert.deepEqual(r.selected, ['p.AlphaTest']);
  assert.deepEqual(r.excludedStale, ['p.GoneTest']);
});

test('规模封顶 > 60% ⇒ 全量', () => {
  const tiny = { ...MAP, meta: { ...MAP.meta, testClassCount: 1 } };
  const r = selectTests({ changed: [{ status: 'M', path: 'backend/src/main/java/p/Alpha.java' }], map: tiny, smoke: [], trackedTests: TRACKED, blobOf: () => 'blob-alpha' });
  assert.equal(r.fallback, '规模封顶');
});

test('静态兜底可多条命中且全列', () => {
  const hits = staticFallbacks([
    { status: 'M', path: 'backend/pom.xml' },
    { status: 'M', path: 'backend/src/main/resources/application.yml' },
  ]);
  assert.deepEqual(hits.map((h) => h.rule), ['F1', 'F2', 'F13']);
});

// --------------------------------------------------- --verify-map 口径（L-100）

test('顶层类型：class/record/enum 可记录，interface/@interface 不可记录', () => {
  assert.equal(topLevelKind('package p;\n\npublic class A {}\n'), 'class');
  assert.equal(topLevelKind('abstract class A {}'), 'class');
  assert.equal(topLevelKind('public final class A {}'), 'class');
  assert.equal(topLevelKind('public enum A { X }'), 'enum');
  assert.equal(topLevelKind('public record A(int x) {}'), 'record');
  assert.equal(topLevelKind('public interface A {}'), 'interface');
  assert.equal(topLevelKind('@interface A {}'), '@interface');

  assert.equal(isRecordableSource('public interface A {}'), false);
  assert.equal(isRecordableSource('@Deprecated\npublic @interface A {}'), false);
  assert.equal(isRecordableSource('public enum A { X }'), true);
  assert.equal(isRecordableSource('public record A(int x) {}'), true);
});

test('认不出的源码按可记录处理（与录制器同一默认值）', () => {
  assert.equal(topLevelKind(''), 'class');
  assert.equal(isRecordableSource(''), true);
  // 注释/文本块里的 interface 不算顶层声明
  assert.equal(topLevelKind('// interface 只是注释\nclass A {}'), 'class');
  assert.equal(topLevelKind(' * interface 只是注释\npublic interface A {}'), 'interface');
});

test('verify-map：接口/注解拉低全量口径，硬闸按可记录类口径通过（L-100）', () => {
  const mainClasses = [
    ...Array.from({ length: 8 }, (_, i) => ({ fqn: `p.C${i}`, recordable: true })),
    { fqn: 'p.Iface', recordable: false },
    { fqn: 'p.Ann', recordable: false },
  ];
  const index = {};
  for (const c of mainClasses) if (c.recordable) index[c.fqn] = { tests: ['t.T'], blobSha: null };

  const r = verifyMapCoverage(mainClasses, { index });
  assert.equal(r.keyCount, 8);
  assert.equal(r.keyOverlapRatio, 0.8);
  assert.equal(r.keyOverlapRatioConcrete, 1);
  assert.deepEqual(r.missingRecordable, []);
  assert.deepEqual(r.missing, ['p.Iface', 'p.Ann']);
  assert.deepEqual(r.nonRecordable, ['p.Iface', 'p.Ann']);
  assert.equal(r.pass, true);
});

test('verify-map：可记录类口径不足 95% ⇒ 硬闸不过', () => {
  const mainClasses = Array.from({ length: 20 }, (_, i) => ({ fqn: `p.C${i}`, recordable: true }));
  const index = {};
  mainClasses.slice(0, 18).forEach((c) => { index[c.fqn] = { tests: ['t.T'] }; });

  const r = verifyMapCoverage(mainClasses, { index });
  assert.equal(r.keyOverlapRatioConcrete, 0.9);
  assert.deepEqual(r.missingRecordable, ['p.C18', 'p.C19']);
  assert.equal(r.pass, false);
});

test('verify-map：可记录类口径恰好 95% ⇒ 硬闸通过', () => {
  const mainClasses = Array.from({ length: 20 }, (_, i) => ({ fqn: `p.C${i}`, recordable: true }));
  const index = {};
  mainClasses.slice(0, 19).forEach((c) => { index[c.fqn] = { tests: ['t.T'] }; });

  const r = verifyMapCoverage(mainClasses, { index });
  assert.equal(r.keyOverlapRatioConcrete, 0.95);
  assert.equal(r.pass, true);
});

test('verify-map：缺表 ⇒ 键集 0、全部未覆盖、闸不过', () => {
  const r = verifyMapCoverage([{ fqn: 'p.C0', recordable: true }, { fqn: 'p.I0', recordable: false }], null);
  assert.equal(r.keyCount, 0);
  assert.deepEqual(r.missing, ['p.C0', 'p.I0']);
  assert.deepEqual(r.missingRecordable, ['p.C0']);
  assert.equal(r.keyOverlapRatio, 0);
  assert.equal(r.keyOverlapRatioConcrete, 0);
  assert.equal(r.pass, false);
});

test('verify-map：无生产类 ⇒ 两口径都记 100%', () => {
  const r = verifyMapCoverage([], { index: {} });
  assert.equal(r.keyOverlapRatio, 1);
  assert.equal(r.keyOverlapRatioConcrete, 1);
  assert.equal(r.pass, true);
});

// ------------------------------------- L-101：不稳定集合的成本可视化（纯函数面）

test('costEstimate：估算秒数 = 录制期逐类耗时之和（上界），另存墙钟折算值', () => {
  const map = {
    meta: {
      testClassDurationMs: { 'p.AlphaTest': 1000, 'p.BetaTest': 3000, 'p.SmokeTest': 2000 },
      recordedWallMs: 12000,
    },
  };
  const c = costEstimate(map, ['p.AlphaTest', 'p.BetaTest']);
  assert.equal(c.classCount, 2);
  assert.equal(c.knownClassCount, 2);
  assert.equal(c.ownMs, 4000);
  assert.equal(c.share, 4000 / 6000);
  assert.equal(c.estimatedSeconds, 4); // 4s（录制期耗时和，上界；实测标定 ~1.4×）
  assert.equal(c.wallScaledSeconds, 8); // 4/6 × 12s：只作对照，不进结论
  assert.equal(c.fullSeconds, 12);
});

test('costEstimate：无计时数据 ⇒ 估算为 null（不假装知道）', () => {
  const c = costEstimate({ meta: {} }, ['p.AlphaTest']);
  assert.equal(c.estimatedSeconds, null);
  assert.equal(c.share, null);
  assert.equal(c.knownClassCount, 0);
});

test('costEstimate：只有逐类耗时、无墙钟 ⇒ 退化为用例体秒数下界', () => {
  const c = costEstimate({ meta: { testClassDurationMs: { 'p.AlphaTest': 2500 } } }, ['p.AlphaTest']);
  assert.equal(c.estimatedSeconds, 2.5);
  assert.equal(c.wallScaledSeconds, null);
  assert.equal(c.fullSeconds, null);
});

test('costEstimate：表里没有的测试类不计入（不夸大成本）', () => {
  const c = costEstimate({ meta: { testClassDurationMs: { 'p.AlphaTest': 1000 }, recordedWallMs: 5000 } }, ['p.AlphaTest', 'p.GhostTest']);
  assert.equal(c.classCount, 2);
  assert.equal(c.knownClassCount, 1);
  assert.equal(c.ownMs, 1000);
  assert.equal(c.estimatedSeconds, 1);
  assert.equal(c.wallScaledSeconds, 5);
});

test('不稳定类进冒烟集、进成本、进结果（L-101 可视化）', () => {
  const map = {
    ...MAP,
    meta: {
      ...MAP.meta,
      unstableTests: ['p.BetaTest'],
      unstablePolicy: { cleanRuns: 2, maxAgeDays: 7 },
      testClassDurationMs: { 'p.AlphaTest': 1000, 'p.BetaTest': 3000, 'p.SmokeTest': 500 },
      recordedWallMs: 9000,
    },
  };
  const r = selectTests({
    changed: [{ status: 'M', path: 'backend/src/main/java/p/Alpha.java' }],
    map,
    smoke: [],
    trackedTests: TRACKED,
    blobOf: () => 'blob-alpha',
  });
  assert.deepEqual(r.unstable, ['p.BetaTest']);
  assert.ok(r.selected.includes('p.BetaTest'));
  assert.deepEqual(r.unstablePolicy, { cleanRuns: 2, maxAgeDays: 7 });
  assert.equal(r.cost.unstable.classCount, 1);
  assert.ok(r.cost.unstable.estimatedSeconds > 0);
  assert.ok(r.cost.smoke.estimatedSeconds >= r.cost.unstable.estimatedSeconds);
});

test('空变更集：不稳定类并入冒烟集（§3.6「永久常跑」不留缺口）', () => {
  const map = { ...MAP, meta: { ...MAP.meta, unstableTests: ['p.BetaTest'] } };
  const r = selectTests({ changed: [], map, smoke: ['p.SmokeTest'], trackedTests: TRACKED });
  assert.deepEqual(r.selected, ['p.BetaTest', 'p.SmokeTest']);
  assert.match(r.warnings[0], /变更集为空/);
});

test('空变更集也报不稳定成本（「只跑冒烟」同样被拖慢）', () => {
  const map = {
    ...MAP,
    meta: { ...MAP.meta, unstableTests: ['p.BetaTest'], testClassDurationMs: { 'p.BetaTest': 2000 }, recordedWallMs: 4000 },
  };
  const r = selectTests({ changed: [], map, smoke: ['p.SmokeTest'], trackedTests: TRACKED });
  assert.equal(r.cost.unstable.classCount, 1);
  assert.equal(r.cost.unstable.estimatedSeconds, 2);
});

