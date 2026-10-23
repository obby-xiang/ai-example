/**
 * @deprecated 薄壳（L-81 收口后）：NFEZUI 域不再有自己的 harness 副本。
 *
 * 并行隔离现在**只靠环境变量**（见 `support/harness.ts` 抬头表格）：本域由
 * `e2e/playwright.nfezui.config.ts` 在加载期补运行目录 / 六端口 / Redis 库 13-14 /
 * 本域桩脚本（`E2E_STUB_SCRIPT` + `E2E_STUB_UPSTREAM_PORT`）/ 后端附加参数，
 * 起停、降级面、pid 台账全部走统一 harness。这里只做转出，防止还有旧引用落到空模块。
 */
export * from './harness'
