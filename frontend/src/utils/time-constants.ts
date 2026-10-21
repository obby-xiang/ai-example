/**
 * 前端超时常量的单一事实源（超时面治理批① 第 4 项）。
 *
 * <h2>为什么要有这个文件</h2>
 * 同一个数（60s）此前在前端出现三次、各写各的字面量，只在注释里互相"对齐"：
 * `api/http.ts`（axios 单请求上限）、`utils/frontend-tools.ts`（回灌 POST 的最坏耗时 = 预算要让出的
 * 余量）、`stores/workspace.ts`（`ui_event download` 的拉字节上限）。改一处不会带动另外两处，
 * 而这三处在语义上<b>必须同值</b>：
 * - 回灌 POST 走的就是 axios 实例 ⇒ 余量必须等于 axios 上限，否则"预算 + 余量 ≤ 服务端上限"这个
 *   保证链（见 `deriveFrontendToolBudgetMs`）当场失真；
 * - 下载也是"拉字节"类请求，与 axios 的单请求上限同分布。
 *
 * <h2>取值依据</h2>
 * 见 `api/http.ts` 的 `HTTP_TIMEOUT_MS` 注（axios 官方缺省 `timeout: 0` = 不超时；本仓取内网
 * 企业应用推荐区间 60–120s 的<b>下限 60s</b>，长响应走 SSE 通道，不占本上限）。
 *
 * <h2>本模块零依赖</h2>
 * 刻意不放在 `api/http.ts` 里：那个模块会建 axios 实例、挂拦截器、引 locales —— 工具函数层
 * （`utils/*`）不该为了一个数把它们全套拖进来（也会制造 utils ↔ api 的循环）。
 */
export const HTTP_TIMEOUT_MS = 60_000
