# 复核确认 R-163 B+C+D · DSPro 确认轮

- **结论：通过**（三点核对全绿，无安全/回归漏洞；门禁新鲜复跑全绿）。

## 三点核对实证

1. **D1 表格方案安全/回归 ✓** `markdown.ts:42` ALLOWED_TAGS 加 `div`、`:44` ALLOWED_ATTR 仅 `class/href/src/alt/type`（无 style/on*/id/iframe）；hook `:138-144` 收窄 DIV class 仅保 `md-table-scroll`（非此值一律 `removeAttribute`）；`wrapTables :196-200` 只包 markdown-it 产出的裸 `<table>`；渲染层断言 `ai-panel-parts-render.spec.ts:1873-1878` 锁 `<div class="md-table-scroll"><table>` 净化后存活。无 XSS 面（div 惰性、无事件/样式/表单属性可注），无任意断行回归（`.md-body` 根零 overflow-wrap/word-break，G1 `:1775-1777` 负断言锁）。

2. **五条偏离可接受 ✓** ①td `word-break:normal;overflow-wrap:anywhere`（`ai-chat.css:169-171`，长 token 兜底，有意偏离 demo break-word）；②令牌挂 `.ai-chat` 非 `:root`（`:10-30`，17 令牌值逐字，防污染 EP/SpreadJS 域）；③D1 wrapper 超 demo（`:143-155`，6 列宽表必需，映射表 §五「超 demo」）；④C5 无值项定标（h5/h6 `:109-110`、ol 同 ul `:115`、a/hr/img `:138-140`）；⑤圆角统一 `--r:10px`（`:27` + 全量 `var(--r)`）。均已在施工报告/映射表留痕。

3. **spec 断言口径正确 ✓** G1 `styleBlocks()` 取样源改读 `ai-chat.css`（`:1749-1761`，scoped 零 `.md-body` + global 含 16.5px/max-content/md-table-scroll/figure.code）；H2→D1 wrapper 断言（`:1863-1871`，`.md-table-scroll`/`overflow-x:auto`/`width:max-content`/`min-width:100%`/th nowrap）；新增 renderMarkdown 渲染层断言（`:1873-1878`）。

## 门禁（新鲜复跑，非照抄）
前端 **313/313** ✓｜typecheck **0** ✓｜build **✓**（exit 0）｜check-docs **6/6** ✓。

## 残留（不阻断）
- 浏览器实测 `mdRootOW="break-word"`（K2.8 CDP 值）与「.md-body 根已撤 wrap」字面有出入，但该值是 `overflow-wrap:break-word`（仅溢出才断，非「任意断开」的 `anywhere`），源层面 G1 负断言已锁；实测 `tableDisplay=table`、`wrapperScrollable=true`、`wrapSW===wrapCW(396/396)` 均证 D1 生效。
- raw HTML `<table 带属性>` 不匹配 `wrapTables` 的 `/<table>/` 正则（仅 markdown 表格被包 wrapper），用户手写 HTML 表边缘场景，无安全影响。
