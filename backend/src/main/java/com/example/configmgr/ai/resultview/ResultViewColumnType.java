package com.example.configmgr.ai.resultview;

import java.util.Arrays;
import java.util.Optional;

/**
 * 列类型白名单（设计稿 §3.4）：九种封闭枚举。
 *
 * <p>未知值 ⇒ 整条视图降级为 {@code fallback}（前端 {@code normalizeResultView} 同口径），
 * 绝不"猜一个类型硬渲"——那会让工具结果重新获得自由格式的表现力。
 */
public enum ResultViewColumnType {

	STRING("string"),

	NUMBER("number"),

	INTEGER("integer"),

	BOOLEAN("boolean"),

	DATE("date"),

	DATETIME("datetime"),

	ENUM("enum"),

	LINK("link"),

	BADGE("badge");

	private final String wire;

	ResultViewColumnType(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return this.wire;
	}

	public static Optional<ResultViewColumnType> fromWire(String wire) {
		if (wire == null) {
			return Optional.empty();
		}
		return Arrays.stream(values()).filter(type -> type.wire.equals(wire)).findFirst();
	}
}
