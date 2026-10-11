# E2E 桩模式校准后配方

> 本文档为操作配方沉淀，来源：交接存档，2026-10-11 入库。全文按存档原文转录（脱敏后，占位符原样保留），**本轮未在仓内复跑**——文中条目未另行标注者按【假设】级对待，需据其判断时先按第 5 条本地起停纪律复跑取证。

事实源：ci.yml；fa72 会话校准。

1. 起上游桩：`node scripts/ci/stub-upstream.mjs --port 18399 --dir <SCRATCH> --violate-form-type`
2. 自起后端：环境变量 AI_BASE_URL=http://127.0.0.1:18399、AI_MODEL=ci-stub、AI_API_KEY=ci-stub-placeholder；JVM 参数 --server.port=18330 --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150；H2 库落 <SCRATCH>（避免写 backend/data/）。
3. HTTP 就绪轮询后再跑 E2E：`E2E_STUB_MODE=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 -EnableGf6 -BackendLog <后端日志路径>`。
4. 基线口径：桩模式 29 用例。
5. 本地增强纪律：启动前必 netstat 查端口；只停自起进程、按 PID 停。
6. 已知坑：后台 bash 里带空格路径的 mvn.cmd 直启可能失败，用 Maven 包装脚本；E2E 日志/artifact 匿名访问 403，PASS=29 按 step 成功+退出码反推；check-runs/<job_id>/annotations 匿名可读。
7. 端口纪律：后端 18330 起、桩 18399、CDP 9334-9337、前端 dev 实际 5202（strictPort）、探针 5211-5213。
