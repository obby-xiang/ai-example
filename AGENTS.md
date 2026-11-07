# 项目开发与审查约定

所有开发、返修和审查人员必须先阅读并遵守 `docs/delivery/development-standards.md`。该规范覆盖生产代码、测试与压测代码、脚本、CI 配置和文档；D6 迁移指南及既有决策文档继续按各自范围生效。

提交前必须完成以下事项：

1. 对**当前暂存区**逐文件、逐 hunk 审查，不以静态审查冒充实测，不把 `NOT_RUN` 写成 `PASS`。
2. 在 `docs/delivery/development-standards-review.json` 仅追加一条有效记录，覆盖全部暂存文件、七个规范域、具体理由、规格变化和真实证据边界，并将该文件同批暂存；父 ledger 必须是当前 ledger 的严格前缀。
3. 确认记录指纹与最终暂存内容一致。审查后导致覆盖暂存状态变化的修改必须重新审查并更新记录，父 ledger 中已有 fingerprint 不得复用。
4. 运行 `node scripts/ci/check-d6-sync.mjs --standards` 核对记录；AI 核心迁移面仍须同时满足原 D6 复核。
5. 不得绕过 pre-commit。缺少有效记录、指纹过期、理由空泛、缺少 Node 或门禁脚本异常均按失败处理。

新增或升级依赖必须取得用户明确授权。优先复用项目已有 Apache Commons、Guava、Spring、Tailwind CSS 和 lodash，不另造重复框架或门禁。
