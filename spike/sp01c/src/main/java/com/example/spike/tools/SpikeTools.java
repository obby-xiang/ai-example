package com.example.spike.tools;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * SP-01c 的两个示例工具。每次调用都计数，供 /api/dev/tool-stats 输出客观证据
 * （证明 V-c5 的每一轮模型确实发起了工具调用，而非只是回话）。
 */
public class SpikeTools {

	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final AtomicInteger getServerTimeCalls = new AtomicInteger();

	private final AtomicInteger calculateCalls = new AtomicInteger();

	@Tool(name = "getServerTime", description = "获取服务器当前时间，返回 yyyy-MM-dd HH:mm:ss 格式的字符串")
	public String getServerTime() {
		this.getServerTimeCalls.incrementAndGet();
		return LocalDateTime.now(ZoneId.systemDefault()).format(FORMAT);
	}

	@Tool(name = "calculate", description = "四则运算计算器，按给定运算符计算两个数并返回结果")
	public String calculate(@ToolParam(description = "第一个操作数") double a,
			@ToolParam(description = "运算符，取值 add、subtract、multiply 或 divide") String operator,
			@ToolParam(description = "第二个操作数") double b) {
		this.calculateCalls.incrementAndGet();
		double result = switch (operator) {
			case "add" -> a + b;
			case "subtract" -> a - b;
			case "multiply" -> a * b;
			case "divide" -> a / b;
			default -> throw new IllegalArgumentException("不支持的运算符: " + operator);
		};
		return "%s %s %s = %s".formatted(a, operator, b, result);
	}

	public int getServerTimeCalls() {
		return this.getServerTimeCalls.get();
	}

	public int calculateCalls() {
		return this.calculateCalls.get();
	}

	public int totalCalls() {
		return getServerTimeCalls() + calculateCalls();
	}

}
