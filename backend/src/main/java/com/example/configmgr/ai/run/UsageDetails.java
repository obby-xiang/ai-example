package com.example.configmgr.ai.run;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * {@code done} 帧 usage 的可选维度扩展（DC-14 T8 / R11-1）：{@code cachedTokens} 与
 * {@code reasoningTokens} —— 为成本核算留口（缓存命中量直接对应折扣计价，推理 token
 * 是推理型模型的独立计费维度）。
 *
 * <h2>为什么走反射而不是强类型</h2>
 * 官方 {@code org.springframework.ai.chat.metadata.Usage} 接口（1.1.8 实测）只有
 * {@code promptTokens / completionTokens / totalTokens / getNativeUsage()} —— 缓存与推理维度
 * 在<b>各家实现的 native usage</b> 里（OpenAI 兼容：{@code OpenAiApi.Usage} 的
 * {@code promptTokensDetails().cachedTokens()} 与 {@code completionTokenDetails().reasoningTokens()}；
 * DeepSeek 另在顶层给 {@code prompt_cache_hit_tokens}）。走反射 + 多路兜底，
 * 模型换厂商/换版本时不会因"某个 getter 不存在"而整轮失败。
 *
 * <h2>无则省略（T8 的字段契约）</h2>
 * 拿不到就不写该键 —— 前端按"可选字段"解析，写 {@code null} 反而会让展示层纠结
 * "是 0 还是未知"。本类任何异常都不外抛：用量维度是<b>观测</b>，不能影响轮次本身。
 */
@Slf4j
public final class UsageDetails {

	/** 缓存命中 token 数（键名与 T8 约定一致）。 */
	public static final String CACHED_TOKENS = "cachedTokens";

	/** 推理 token 数。 */
	public static final String REASONING_TOKENS = "reasoningTokens";

	private UsageDetails() {
	}

	/**
	 * 把 native usage 里的可选维度补进 usage Map（取不到则不加键）。
	 *
	 * @param usage       已装好基础三维度的可变 Map（null 时什么也不做）
	 * @param nativeUsage {@code Usage#getNativeUsage()} 的返回值（可能是任意厂商类型或 Map）
	 */
	public static void enrich(Map<String, Object> usage, Object nativeUsage) {
		if (usage == null || nativeUsage == null) {
			return;
		}
		try {
			Integer cached = firstInt(nativeUsage,
					// OpenAI 兼容（Spring AI 的 OpenAiApi.Usage$PromptTokensDetails）
					new String[] { "promptTokensDetails", "cachedTokens" },
					// DeepSeek 顶层字段（各家命名不一，逐路兜底）
					new String[] { "promptCacheHitTokens" },
					new String[] { "promptCacheHitTokens", "value" },
					// 已是 Map 的 native usage
					new String[] { "prompt_tokens_details", "cached_tokens" },
					new String[] { "prompt_cache_hit_tokens" });
			if (cached != null) {
				usage.put(CACHED_TOKENS, cached);
			}
			Integer reasoning = firstInt(nativeUsage,
					// 注意官方 record 的字段名是 completionTokenDetails（无 Tokens 的 s）
					new String[] { "completionTokenDetails", "reasoningTokens" },
					new String[] { "completionTokensDetails", "reasoningTokens" },
					new String[] { "completion_tokens_details", "reasoning_tokens" },
					new String[] { "reasoningTokens" });
			if (reasoning != null) {
				usage.put(REASONING_TOKENS, reasoning);
			}
		}
		catch (Exception ex) {
			log.debug("usage 可选维度提取失败（不影响轮次）：{}", ex.getMessage());
		}
	}

	/**
	 * 按"路径"逐跳取值：每一跳若是对象则找同名 getter/record 访问器，若是 Map 则按键取；
	 * 末跳转成 int。任一跳取不到即返回 null（继续试下一条路径）。
	 */
	private static Integer firstInt(Object root, String[]... paths) {
		for (String[] path : paths) {
			Object current = root;
			boolean ok = true;
			for (String hop : path) {
				current = hop(current, hop);
				if (current == null) {
					ok = false;
					break;
				}
			}
			Integer value = ok ? asInt(current) : null;
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private static Object hop(Object source, String name) {
		if (source == null) {
			return null;
		}
		if (source instanceof Map<?, ?> map) {
			Object direct = map.get(name);
			if (direct != null) {
				return direct;
			}
			// Map 里没有原样键名时，再试下划线/驼峰互转（跨厂商 JSON 命名差异）
			Object snake = map.get(camelToSnake(name));
			return snake != null ? snake : map.get(snakeToCamel(name));
		}
		// record 访问器与 getter 同名（cachedTokens() / getCachedTokens()）
		for (String methodName : new String[] { name, "get" + capitalize(name) }) {
			try {
				Method method = source.getClass().getMethod(methodName);
				return method.invoke(source);
			}
			catch (NoSuchMethodException ignored) {
				// 试下一个命名
			}
			catch (Exception ex) {
				return null;
			}
		}
		return null;
	}

	private static Integer asInt(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value instanceof String text && !text.isBlank()) {
			try {
				return Integer.valueOf(text.trim());
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}
		return null;
	}

	private static String capitalize(String name) {
		return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	private static String camelToSnake(String name) {
		StringBuilder sb = new StringBuilder();
		for (char c : name.toCharArray()) {
			if (Character.isUpperCase(c)) {
				sb.append('_').append(Character.toLowerCase(c));
			}
			else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static String snakeToCamel(String name) {
		StringBuilder sb = new StringBuilder();
		boolean upper = false;
		for (char c : name.toCharArray()) {
			if (c == '_') {
				upper = true;
				continue;
			}
			sb.append(upper ? Character.toUpperCase(c) : c);
			upper = false;
		}
		return sb.toString();
	}

}
