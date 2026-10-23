/**
 * @deprecated 薄壳（L-81 收口后）：H/G/T/A 域不再有自己的 harness 副本。
 *
 * 并行隔离现在**只靠环境变量**（见 `support/harness.ts` 抬头表格）：本域由
 * `playwright.hgta.config.ts` 在加载期补 `E2E_RUN_DIR` / `E2E_*_PORT` / `E2E_REDIS_DB` 的默认值，
 * 起停与 pid 台账全部走统一 harness。这里只做转出，防止还有旧引用落到空模块。
 */
export * from './harness'
