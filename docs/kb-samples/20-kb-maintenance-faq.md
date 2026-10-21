# 知识库维护 FAQ（样例）

> 本文为**样例文档**，仅用于知识库功能验证，不含真实业务数据。

## 1 如何新增一篇知识文档

1. 在知识目录新增 `md` 文件（文件名建议编号 + 英文 slug）；
2. 在 `manifest.json` 的 `docs` 中登记 id、title、tags、aliases 与 file；
3. 计算文件 sha 并写入 manifest 的 `sha`，更新 `updated_at`；
4. 调管理端点 `POST /api/ai/kb/reload` 触发重建；
5. 用 `GET /api/ai/kb/status` 确认 doc_count 增加。

## 2 如何让口头叫法被召回

在 manifest 的 `aliases` 里登记同义词。例如"费率表"登记为区域费率的别名，检索"费率表"即可命中。

## 3 如何提供术语的预制答案

在 manifest 该文档的 `terms` 里登记 `name`、`aliases`、`section`、`answer`。`kb_lookup_term` 只做精确/别名命中，不做模糊兜底。

## 4 修改文档后索引没变

常见原因：manifest 的 sha 没更新（local 源按文件实算 sha，会自动发现），或刷新间隔未到。可手动调 reload。

## 5 热重载会不会影响正在进行的问答

不会中断。新索引在临时目录构建，成功后原子切换；进行中的检索要么用旧索引，要么用新索引，不会读到半成品。

## 6 文档写多长合适

单篇建议不超过一万字；超长文档请用多级标题分节，便于切块与按 section 读取。表格与 mermaid 会原样保留。

> 本文为样例文档，仅用于知识库功能验证，不含真实业务数据。
