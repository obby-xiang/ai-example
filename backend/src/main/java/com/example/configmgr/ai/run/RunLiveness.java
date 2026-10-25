package com.example.configmgr.ai.run;

/**
 * 会话门占位轮是否已经死亡（批G · G6「僵尸轮自动放行」的唯一判据）。
 *
 * <p>
 * 现场：上一轮所在进程死在模型调用中间，{@code ai:session:lock:<sessionId>} 与进程内单值门都
 * 还留着它的 runId —— 用户每次发送都 409，只能刷新。会话锁的 TTL 很长，等它过期不现实。
 *
 * <p>
 * 判死需要**三条同时成立**，缺一条都不放行（放错方向的代价是同一会话并发两轮）：
 * <ol>
 * <li>快照已终态或状态不可能是"在跑"（null / 未知）；</li>
 * <li>不是活轮：没有续跑租约（跨实例续跑权）、本进程也没有它的<b>真实订阅者</b>（活连接）——
 * 只有写出器对象不算（park 的轮没有驱动方，写出器却还在）；</li>
 * <li>最近活动（快照写入时刻 / 业务活动时刻取较新）早已超出僵尸窗口 —— 安静但活着的长轮
 * （只吐思考、或挂起等人）靠<b>业务活动锚点</b>网兜住。两种状态统一认<b>业务</b>活动：
 * RUNNING 的心跳不得掩盖卡死；SUSPENDED（park）后没有心跳，读侧 touch（{@code /runs/current}、
 * 重挂、决策）就是"还有人管这一轮"的证据，且它只对 SUSPENDED 刷新该锚点
 * （{@link RunStore#touchActiveRun}）。</li>
 * </ol>
 *
 * <p>
 * 判据必须与 {@code ResumeService} 的僵尸接管同源（都是"无活动 + 无租约"），否则会出现
 * "会话门放行、续跑器又接管"的双跑。
 */
public final class RunLiveness {

	private RunLiveness() {
	}

	/**
	 * @param status 快照状态（null = 快照已消失）
	 * @param updatedAtMs 快照最后写入时刻（epoch 毫秒，0 = 未知）
	 * @param lastActivityAtMs 最近<b>业务</b>活动时刻（epoch 毫秒，0 = 无活动记录）。RUNNING 与
	 * SUSPENDED 都传业务活动（不含心跳）；SUSPENDED 的锚点由读侧 touch 刷新
	 * @param leaseHeld 续跑租约是否仍被持有（跨实例"还有人负责这一轮"的最强证据）
	 * @param liveInProcess 本进程是否仍有该轮的<b>真实订阅者</b>（活连接；空写出器不算）
	 * @param thresholdMs 僵尸窗口（无活动超过本值才判死）
	 * @param nowMs 判定时刻
	 */
	public static boolean isDead(String status, long updatedAtMs, long lastActivityAtMs,
			boolean leaseHeld, boolean liveInProcess, long thresholdMs, long nowMs) {
		if (status == null) {
			return true;
		}
		if (RunSnapshot.isTerminal(status)) {
			return true;
		}
		boolean activeStatus = RunSnapshot.RUNNING.equals(status) || RunSnapshot.SUSPENDED.equals(status);
		if (!activeStatus) {
			return true;
		}
		if (leaseHeld || liveInProcess) {
			return false;
		}
		long activeAt = Math.max(updatedAtMs, lastActivityAtMs);
		if (activeAt <= 0L) {
			return true;
		}
		return nowMs - activeAt > thresholdMs;
	}
}
