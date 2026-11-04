package com.example.configmgr.ai.memory;

/**
 * 一次记忆写回的<b>轮次归属</b>：哪个 run 写的 + 这一轮从什么时候开始。
 *
 * <h2>为什么写回需要归属</h2>
 * 会话只有一份 Redis 记忆窗口，而同一会话可以同时有多条在跑的 run（挂起轮续跑 + 用户新开一轮是
 * 明确允许的规格）。每个 run 只持有<b>自己的</b>快照历史，终局收敛时若整窗覆盖，后写的那个 run
 * 会把另一条 run 的回答整段删掉（反序则删掉前一条）。因此窗口按 run 分块：写入只替换/追加<b>本 run
 * 的块</b>，其余块原样保留，块间按本轮起点排序。
 *
 * <h2>turnStartedAtMs 的口径</h2>
 * <b>本 run 的第一拍</b>（首轮 = 快照创建时刻；续跑沿用同一块的既有起点，不会被新的续跑时刻顶掉），
 * 用来决定"谁在前"。同毫秒时按 {@code runId} 定序，保证任意写入顺序得到同一个窗口。
 *
 * <p>起点只在<b>块还不存在</b>时使用（见 {@link RunMemoryMerge}）：一个 run 续跑多次，块的位置
 * 不因续跑而漂移。起点必须已知且为正数；不存在"无归属写入"这一形态。
 */
public record MemoryTurn(String runId, long turnStartedAtMs) {

	public MemoryTurn {
		if (runId == null || runId.isBlank()) {
			throw new IllegalArgumentException("runId 不得为空白：记忆写回必须带本轮归属");
		}
		if (turnStartedAtMs <= 0L) {
			throw new IllegalArgumentException("turnStartedAtMs 必须为正数：记不到本轮起点即失败");
		}
	}

	/** 若 runId/起点可用则构造，否则返回 {@code null}（调用方按失败处理，不伪造 run）。 */
	public static MemoryTurn of(String runId, long turnStartedAtMs) {
		return runId == null || runId.isBlank() || turnStartedAtMs <= 0L
				? null : new MemoryTurn(runId, turnStartedAtMs);
	}

}
