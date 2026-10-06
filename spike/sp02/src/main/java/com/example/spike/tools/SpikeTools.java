package com.example.spike.tools;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * PoC 工具集（三个，覆盖三类工具）：
 * - calculate        ：后端直接执行；
 * - get_user_profile ：前端工具，方法体是“哨兵桩”，正常路径下永不被执行
 *                      （SpikeToolCallingManager 在委托前拦截，改为挂起等前端回灌）；
 * - delete_config    ：敏感工具，执行前经 HITL 确认门。
 * 计数器用于事后证明“工具执行与否”，避免只靠模型自述。
 */
public class SpikeTools {

	public static final String FRONTEND_STUB_MARKER = "BACKEND_STUB_SHOULD_NOT_RUN";

	private final AtomicInteger calculateCalls = new AtomicInteger();

	private final AtomicInteger userProfileBodyCalls = new AtomicInteger();

	private final AtomicInteger deleteConfigCalls = new AtomicInteger();

	@Tool(name = "calculate", description = "后端计算器：对两个整数做四则运算（operator 取 add/subtract/multiply/divide）")
	public String calculate(@ToolParam(description = "左操作数") int a,
			@ToolParam(description = "运算符：add/subtract/multiply/divide") String operator,
			@ToolParam(description = "右操作数") int b) {
		this.calculateCalls.incrementAndGet();
		int result = switch (operator == null ? "" : operator) {
			case "add" -> a + b;
			case "subtract" -> a - b;
			case "multiply" -> a * b;
			case "divide" -> b == 0 ? Integer.MIN_VALUE : a / b;
			default -> throw new IllegalArgumentException("unsupported operator: " + operator);
		};
		return "后端计算器结果：" + a + " " + operator + " " + b + " = " + result;
	}

	@Tool(name = "get_user_profile", description = "获取用户的会员资料（姓名/等级/积分）。该数据只存在前端浏览器本地缓存中，后端不执行，"
			+ "调用后由前端回传结果")
	public String getUserProfile(@ToolParam(description = "用户 ID，例如 u-1024") String userId) {
		this.userProfileBodyCalls.incrementAndGet();
		return FRONTEND_STUB_MARKER + ":" + userId;
	}

	@Tool(name = "delete_config", description = "删除指定的配置定义（破坏性操作，会连带删除生效数据行）")
	public String deleteConfig(@ToolParam(description = "配置定义编码，例如 def-777") String code) {
		this.deleteConfigCalls.incrementAndGet();
		return "后端已执行删除：配置 " + code + " 及其 3 行生效数据已删除。";
	}

	public int calculateCalls() {
		return this.calculateCalls.get();
	}

	public int userProfileBodyCalls() {
		return this.userProfileBodyCalls.get();
	}

	public int deleteConfigCalls() {
		return this.deleteConfigCalls.get();
	}

}
