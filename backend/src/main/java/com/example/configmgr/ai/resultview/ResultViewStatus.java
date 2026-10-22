package com.example.configmgr.ai.resultview;

/**
 * 内容完整度（设计稿 §3.2 / §0-6）：与工具结局 {@code outcome} 是<b>两把尺</b>。
 *
 * <p>{@code outcome} 答"工具是否执行成功"，本枚举答"这份视图的内容是否完整"。
 * 截断 = {@link #PARTIAL} + {@code meta.truncated}，<b>不叫失败</b>。
 */
public enum ResultViewStatus {

	OK("ok"),

	EMPTY("empty"),

	PARTIAL("partial"),

	ERROR("error");

	private final String wire;

	ResultViewStatus(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return this.wire;
	}
}
