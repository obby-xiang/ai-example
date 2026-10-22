package com.example.configmgr.ai.resultview;

import java.util.Arrays;
import java.util.Optional;

/**
 * 视图种类（设计稿 §3.3）：<b>封闭枚举</b>，同时是前端渲染器注册表的查表键。
 *
 * <p>{@code chart} 与 {@code form} 在 v1 只占位：枚举值保留（契约先立），渲染走 {@code fallback}
 * （仓内无图表库，见设计稿 §8.2-O2 裁决）。
 */
public enum ResultViewKind {

	TABLE("table"),

	CHART("chart"),

	FORM("form"),

	TEXT("text");

	private final String wire;

	ResultViewKind(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return this.wire;
	}

	/** 未知值返回空 —— 调用方降级为 {@code fallback}，<b>不抛错</b>（§4-规则 1）。 */
	public static Optional<ResultViewKind> fromWire(String wire) {
		if (wire == null) {
			return Optional.empty();
		}
		return Arrays.stream(values()).filter(kind -> kind.wire.equals(wire)).findFirst();
	}
}
