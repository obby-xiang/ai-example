package com.example.configmgr.ai.tool;

import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.lang.Nullable;

import java.lang.reflect.Type;

/**
 * String 返回型工具的文本结果转换器（L-88）。
 *
 * <h2>为什么需要它</h2>
 * 官方 {@link DefaultToolCallResultConverter} 只特判 {@code void} 与图片，其余一律
 * {@code JsonParser.toJson(result)} —— 对 {@code String} 返回值会<b>再套一层 JSON 字符串</b>：
 * 正文首尾多一对引号、换行变字面 {@code \n}，模型看到的 {@code role:tool} 消息是
 * {@code "\"<knowledge>\\n…"}（需再 parse 一次才是正文）。后果是 token 成本翻倍，
 * 且与"工具结果是文本不是 JSON"的契约不符。
 *
 * <p>
 * 本类把该契约落到<b>结果序列化点</b>：{@link CharSequence} 原样返回（不套
 * {@code writeValueAsString}）；其余类型仍委托官方的 JSON 编码 —— 结构化对象
 * （Map/record 等）的线上形态一字不变，只有"本来就是文本"的结果不再被二次编码。
 *
 * <h2>用法</h2>
 * {@code @Tool(resultConverter = PlainTextToolResultConverter.class)}。官方
 * {@code ToolUtils#getToolCallResultConverter} 反射调用<b>公共无参构造</b>，故本类不得改成
 * 有参构造/单例工具类。只给 String 返回型的工具挂它；其余工具维持官方默认（口径不变）。
 */
public class PlainTextToolResultConverter implements ToolCallResultConverter {

	/** 非文本返回型的官方编码器（结构化对象仍然走 JSON）。 */
	private static final DefaultToolCallResultConverter JSON = new DefaultToolCallResultConverter();

	@Override
	public String convert(@Nullable Object result, @Nullable Type returnType) {
		if (result instanceof CharSequence text) {
			return text.toString();
		}
		return JSON.convert(result, returnType);
	}

}
