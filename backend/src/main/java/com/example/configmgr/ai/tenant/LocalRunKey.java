package com.example.configmgr.ai.tenant;

/**
 * <b>进程内表</b>（写出器注册表 / 续跑互斥 / 驱动登记 / 取消交棒 / 闸门 / 工具信标）的本地键。
 *
 * <h2>为什么不能再用裸 runId</h2>
 * runId 只在一轮的生命周期内唯一，<b>不是跨身份的唯一定性</b>：两个租户各自生成同形 runId 是合法
 * 且必然的。进程内表若按裸 runId 定键，A 的写出器/续跑互斥/信标就会与 B 的同名轮次混在一桶 ——
 * A 的 {@code close} 收掉 B 的连接、A 的"正在续跑"让 B 被拒成 {@code IN_PROGRESS}、
 * A 的工具计数让 B 被判成"有副作用"。
 *
 * <h2>键形</h2>
 * 与 Redis run 级键<b>同一个 tag</b>（{@link RunKeyScope#tag(String)}）：
 * <ul>
 * <li>有身份：{@code {<tenantId>:<userId>:run:<runId>}}；</li>
 * <li><b>无身份</b>：{@code {run:<runId>}} —— 这是 <b>legacy 独立命名空间</b>，不是"默认租户"。
 *     本类不提供默认身份，也绝不把别人的身份借给无记录身份的旧轮次。</li>
 * </ul>
 *
 * <h2>键由谁提供</h2>
 * 每个调用点<b>已经</b>在自己的记录身份作用域里（{@link RunIdentityScope}：续跑/交棒/看门狗节拍/
 * 投递线程都显式建立过）。本类只按<b>当前线程</b>身份取键，于是"哪一轮属于谁"与 Redis 侧全局一致，
 * 不需要每个方法多传一个身份参数（公开签名因此保持不变）。
 */
public final class LocalRunKey {

	private LocalRunKey() {
	}

	/** 当前线程身份的本地键（无身份 = legacy {@code {run:<runId>}}）。 */
	public static String of(String runId) {
		return RunKeyScope.tag(RequestContextUtils.currentOrNull(), runId);
	}

	/** 显式身份的本地键；{@code identity == null} = legacy 命名空间。 */
	public static String of(TenantUser identity, String runId) {
		return RunKeyScope.tag(identity, runId);
	}

	/** 显式身份的本地键（记录里的 {@code tenantId/userId}）。 */
	public static String of(long tenantId, long userId, String runId) {
		return RunKeyScope.tag(tenantId, userId, runId);
	}

}
