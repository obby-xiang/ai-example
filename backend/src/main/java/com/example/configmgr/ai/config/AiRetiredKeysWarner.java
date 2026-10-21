package com.example.configmgr.ai.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 退役配置键的启动告警（超时面治理批① 第 7 项）。
 *
 * <h2>为什么要告警而不是静默忽略</h2>
 * 这四个键已从绑定类里删除（{@link AiProperties} 类注逐条说明），但 {@code application.yml}、
 * 部署脚本、运维的 {@code --app.ai.xxx=} 命令行参数里可能还留着它们。Spring Boot 对"没人绑定的
 * 属性"<b>不报错也不提示</b> —— 于是"我把 confirm-timeout 调成 600s 了"会得到"其实什么都没发生"，
 * 而现场看到的仍然是旧行为。与 {@link AiProperties#verifySseWriteTimeout()} 同一姿态：
 * <b>配置面不能对运行面说谎</b>；区别只在于这几项没有"配错就崩"的必要（旧键留着不影响正确性），
 * 故取 WARN。
 *
 * <h2>为什么放在独立类</h2>
 * {@code @ConfigurationProperties} 类要拿到 {@link Environment} 只能改构造绑定（会动绑定契约），
 * 而本项只是"读一次启动参数"——独立组件 + {@code @PostConstruct} 面最小。
 */
@Slf4j
@Component
public class AiRetiredKeysWarner {

	/** 退役键 → 替代口径（顺序稳定，便于日志与用例逐条断言）。 */
	static final Map<String, String> RETIRED_KEYS = retiredKeys();

	private final Environment environment;

	public AiRetiredKeysWarner(Environment environment) {
		this.environment = environment;
	}

	private static Map<String, String> retiredKeys() {
		Map<String, String> keys = new LinkedHashMap<>();
		keys.put("app.ai.hitl.confirm-timeout",
				"确认门已无等待上限（2026-10-20 裁决：等人不设超时）；等机器回执的上限改用 "
						+ "app.ai.hitl.frontend-tool-timeout（缺省 120s）");
		keys.put("app.ai.session-ttl-minutes",
				"改用 app.ai.session-ttl（Duration 形态，缺省 24h；滑动续期、无绝对上限）");
		keys.put("app.ai.max-iterations",
				"官方工具循环的退出条件已取代迭代上限（无替代键：上限由 total-budget 与工具循环自身判）");
		keys.put("app.ai.thinking-disabled",
				"reasoning 补丁不再下发 extraBody.thinking（无替代键：思考开关由模型侧参数决定）");
		return keys;
	}

	/**
	 * 命中即逐键 WARN（一次启动最多 4 条），并在末尾给一条"共 n 项"的汇总，便于日志检索。
	 *
	 * <p>刻意不 fail-fast：这几个键留着不影响正确性（它们只是不再被读），为此拒绝启动会让
	 * "升级到本版"变成需要先改配置的动作。
	 */
	@PostConstruct
	void warnOnRetiredKeys() {
		int hits = 0;
		for (Map.Entry<String, String> entry : RETIRED_KEYS.entrySet()) {
			String value = this.environment.getProperty(entry.getKey());
			if (value == null) {
				continue;
			}
			hits++;
			log.warn("配置键 {} 已退役（当前配置值 = {}），本值不会被读取：{}", entry.getKey(), value.trim(),
					entry.getValue());
		}
		if (hits > 0) {
			log.warn("启动期发现 {} 个已退役的 app.ai 配置键：请从配置面删除或改用上述新键（旧键不会生效，"
					+ "也不会让启动失败）", hits);
		}
	}

}
