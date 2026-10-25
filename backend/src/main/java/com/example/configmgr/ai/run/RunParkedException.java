package com.example.configmgr.ai.run;

/**
 * <b>内部挂起控制信号</b>（人工等待 park；用户裁决 2026-10-25）。
 *
 * <h2>它不是失败、也不是取消</h2>
 * 确认门 / {@code generative_form} / 表格写·撤销 / 上传交接这类"等人或等前端"的等待，
 * 在请求帧已投递、订阅已收流之后，<b>本进程不再有任何该做的事</b>：
 * 决策会经 HTTP 落到 Redis，届时由 {@link ResumeService} 重建执行。因此 {@code ConfirmGate#await}
 * 用本异常<b>结束本次执行</b>（而不是返回一个"已决结果"让官方循环继续调模型，
 * 也不是阻塞线程）。调用链（{@code SpToolCallingManager}）<b>不捕获</b>它；
 * {@link ResilientChatService} 在尝试循环里识别它并直接退出（不重试、不发终帧、不写失败态）。
 *
 * <p>捕获方只允许做三件事：<b>不动</b>快照（保持 SUSPENDED，pending/req/decided/帧归档即恢复状态）、
 * 释放驱动租约（由 await 出口完成）、结束线程。任何"顺手收尾成 done/error"都会把
 * "合法的挂起态"变成"终态"，让恢复路径失去依据。
 */
public class RunParkedException extends RuntimeException {

	private final String runId;

	private final String toolCallId;

	private final String kind;

	public RunParkedException(String runId, String toolCallId, String kind) {
		super("本轮进入人工等待挂起态（park）：runId=" + runId + " toolCallId=" + toolCallId + " kind=" + kind
				+ "（线程交还，决策后由续跑重建执行）");
		this.runId = runId;
		this.toolCallId = toolCallId;
		this.kind = kind;
	}

	public String runId() {
		return this.runId;
	}

	public String toolCallId() {
		return this.toolCallId;
	}

	/** 挂起种类（{@code CONFIRM} / {@code FRONTEND}；诊断用）。 */
	public String kind() {
		return this.kind;
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		// park 是控制流而不是异常路径：不需要堆栈（每张卡都会走一次，避免无谓开销与日志噪音）
		return this;
	}

}
