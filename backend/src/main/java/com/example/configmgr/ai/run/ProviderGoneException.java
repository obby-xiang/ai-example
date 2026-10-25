package com.example.configmgr.ai.run;

/**
 * <b>续跑精确绑定失败</b>（快照的 provider/model 此刻不可用；人工等待 park 批的 I6）。
 *
 * <p>语义与 409 {@code PROVIDER_GONE} 同源：这一轮<b>不能</b>按原模型继续，也<b>不</b>跨 provider
 * 换模型。区别只在发现时机 —— 409 是续跑入口的预检（{@link ResumeService} 在重建前就能判定），
 * 本异常是<b>驱动阶段</b>（{@code ResilientChatService#runtimeForSnapshot}）才发现的窄窗口
 * （预检后到驱动前配置被改/被删）。
 *
 * <p>捕获方（{@code ResumeService} 的驱动任务）必须把它落成<b>明确终态</b>
 * （快照 FAILED + 机器可读的 {@code PROVIDER_GONE} 前缀），而不是只发一帧 error 就把快照
 * 留在 RUNNING（那会变成"既不是终态、也没有驱动方"的半死轮）。
 */
public class ProviderGoneException extends IllegalStateException {

	private final String runId;

	private final Long providerId;

	private final String modelId;

	public ProviderGoneException(String runId, Long providerId, String modelId) {
		this(runId, providerId, modelId, "该 provider/模型此刻不可用；不跨 provider 续跑");
	}

	public ProviderGoneException(String runId, Long providerId, String modelId, String detail) {
		super("续跑精确绑定失败 runId=" + runId + " providerId=" + providerId + " model=" + modelId
				+ "（" + detail + "）");
		this.runId = runId;
		this.providerId = providerId;
		this.modelId = modelId;
	}

	public String runId() {
		return this.runId;
	}

	public Long providerId() {
		return this.providerId;
	}

	public String modelId() {
		return this.modelId;
	}

}
