> 入库注记（2026-10-12）：来源落点 = 仓外工作目录 <SCRATCH>\r153-flake\复核报告-R153-前端-DSPro.md（3737 B）；源件 sha256 = `29beb97914dbc087ff60f2a96b13f68d1fdb1ce7167717e20bae877e508fa88e`；本件除本注记行外与源件逐字节一致（零改写）。
# 复核报告 · R-153 前端面（卡③④）· 复核方 = DSPro（互异于施工方 K2.8）

## 署名（DC-19 三要素）

- 实际运行模型：`DeepSeek/deepseek-v4-pro`（profileName=agent，thinkingEffort=high）
- 会话 id：`session_ebd21528-e6d6-457d-87a2-74a89b178645`
- wire 实证：`.../session_ebd21528-.../agents/main/wire.jsonl` 首条 `profile.bind` = `{"type":"profile.bind","agentId":"main","modelAlias":"DeepSeek/deepseek-v4-pro","profileName":"agent","thinkingEffort":"high",…}`（亲读单行）

## 一、输入核验（四项全一致，未停手）

- 施工卡 sha256 `9cd0d2ad…fae7c` ✓；施工报告 sha256 `681735b8…21ae` ✓（均现算）
- `git rev-parse HEAD` = `c86f1fe6b362a27e2898dde206d7ab105e16146c` ✓
- `git status --porcelain` 恰 4 行：`M docs/03-技术方案文档-v2.1.md`、`M frontend/scripts/build.mjs`、`M frontend/tests/restore-reconcile.spec.ts`、`?? data/` ✓

## 二、逐项复核（亲读 diff，结论通过）

1. **卡③**：`restore-reconcile.spec.ts:92` `vi.waitFor` 已删，换确定性屏障——`const called = deferred<string>()` + `api.runsCurrent.mockImplementation((sid)=>{called.resolve(sid); return gate.promise})` + `expect(await called.promise).toBe('sess-a')`，复用文件内 `deferred` 体例。原 :98-103 六条断言（messages 长度/activeRunId/suspended/activeForm/reattachRun）逐字零改动；未动生产码、`vi.resetModules()` 契约不变、未提超时/加 retry；负例② `vi.waitFor`（非本卡面）未动。**通过**。
2. **卡④ build.mjs**：`buildOnce()` 内收拢 mkdirSync + TMP/TEMP/TMPDIR 重定向；`try{await buildOnce()}catch(first){console.warn; rmSync(.tmp-esbuild,force); await buildOnce()}`——重试**恰好一次**，二次失败无嵌套 try 直接上抛至顶层，非零退出、不吞错。**通过**。
3. **卡④ 文档钉**：`docs/03-技术方案文档-v2.1.md:133` 仅前端构建行单元格追加「wrapper 唯一入口 = `yarn build` → `scripts/build.mjs`；Windows 禁裸 `npx vite build`」，原「Vite」「随 baseline」保留。README:193 实测为 `yarn build          # 含 vue-tsc + vite build`（无需改）。**通过**。
4. **登记零动作**：`git show HEAD:frontend/scripts/build.mjs | grep -c esbuild-` = 0（基线已无「构建前清 esbuild-* 残留」面）。**通过**。
5. **零超面**：diff 仅上述 3 文件，无卡面外改动。**通过**。

## 三、独立复跑（实测值）

- vitest 全量：**19 spec / 225 用例 全绿**（exit 0；负例① 2641ms 一次通过）
- typecheck（vue-tsc --noEmit）：exit 0
- `yarn build`（wrapper = vue-tsc && node scripts/build.mjs）：exit 0，24.68s，vite 6.4.4，1750 模块
- check-docs（`node scripts/ci/check-docs.mjs`）：**6/6 断言绿**，exit 0

## 四、施工报告自承两项独立判定

1. **负例① 首次 15.1s 抖动失败→连绿：判环境 flake，非回归**。依据：① 我独立全量复跑 225/225 绿（① 2641ms）、隔离连跑 3 次均 ~1.1s 绿，未复现抖动；② diff 已用确定性屏障替换 `vi.waitFor` 轮询，结构上消除该超窗/竞态源；③ 单次不可复现 + 连绿是 flake 签名，回归必可复现。
2. **重试路径未做故障注入：验证充分性可接受**。依据：控制流线性无歧义（catch→warn→rmSync→恰好一次 `await buildOnce()`，无循环/无嵌套 try）；我用同构控制流微型实证（不碰仓库）：首败→重试恰一次(CALLS=2)+exit 0，双败→错误上抛+exit 1（非零）；真实文件故障注入需改生产码，属越权，故静态+正常路径实跑+同构实证已足。

## 结论

**通过**（卡③④ 全项，零超面，独立复跑全绿）。
