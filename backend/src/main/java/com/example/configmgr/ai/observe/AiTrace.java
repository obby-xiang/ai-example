package com.example.configmgr.ai.observe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;

/**
 * 一轮 AI 对话的<b>日志链路锚点</b>（AI 可观测性专项·日志链路追踪批）。
 *
 * <h2>它解决什么</h2>
 * 此前 {@code runId} 只是散落在各条日志自由文本里的一个键值对：既没有固定前缀，也过不了线程边界
 * （挂起心跳、续跑、投递池、摘要旁路各自一条线程），于是"把一轮对话的日志串成一条链"只能靠人工
 * 猜测与时间戳对齐。本类做两件事：
 * <ol>
 * <li><b>MDC</b>：把 {@code runId}/{@code sessionId} 装进 {@link MDC}，由日志 pattern
 * （{@code application.yml} 的 {@code logging.pattern.level}）统一渲染到每一行行首；</li>
 * <li><b>结构化事件</b>：{@link #event(String, Object...)} 产出 {@code event=xxx runId=.. k=v}
 * 的单行 INFO —— 字段固定、值不含换行、可按 {@code event=} 直接 grep 与聚合。</li>
 * </ol>
 *
 * <h2>安全红线</h2>
 * 事件只记<b>元数据</b>（标识、计数、耗时、状态码、工具名、规则名）。<b>对话正文、工具入参/出参
 * 全文一律不进日志</b> —— 与"对话数据不落库"同一裁决：日志也不当第二个正文副本。
 * 值统一经 {@link #clean(Object)} 折成单行并截断，保证"一条事件 = 一行"。
 *
 * <h2>线程边界</h2>
 * 平台线程池不会继承 {@link MDC}。两处补齐：{@link AiMdcTaskDecorator}（{@code ThreadPoolTaskExecutor}
 * 的 {@code TaskDecorator}）与 {@link #wrap(Runnable)}（直接提交到裸 executor 的地方）。
 * 两者都在<b>提交时刻</b>抓取 MDC 快照，任务结束后恢复原状，不污染池线程。
 */
public final class AiTrace {

	/** MDC 键：本轮 id（= {@code AiController#chat} 生成的 runId，也是帧/键族的主键）。 */
	public static final String RUN_ID = "runId";

	/** MDC 键：会话 id。 */
	public static final String SESSION_ID = "sessionId";

	/** 单值最大字符数（超出截断）：一条事件必须能一行读完。 */
	private static final int MAX_VALUE_CHARS = 160;

	private static final Logger log = LoggerFactory.getLogger(AiTrace.class);

	private AiTrace() {
	}

	// ── MDC ────────────────────────────────────────────────────────────────

	/**
	 * 绑定本轮锚点（幂等）。{@code runId} 变化时先摘掉旧的 {@code sessionId}，避免"上一轮的会话
	 * 落在这一轮的行首"——那种行比没有行首更坏。
	 *
	 * @param runId     本轮 id；空 = 不动作（不把空串写进 MDC）
	 * @param sessionId 会话 id；空 = 只绑 runId（跨实例唤醒线程只认 runId）
	 */
	public static void bind(String runId, String sessionId) {
		if (runId == null || runId.isBlank()) {
			return;
		}
		if (!runId.equals(MDC.get(RUN_ID))) {
			MDC.remove(SESSION_ID);
			MDC.put(RUN_ID, runId);
		}
		if (sessionId != null && !sessionId.isBlank()) {
			MDC.put(SESSION_ID, sessionId);
		}
	}

	/** 摘除本轮锚点（线程归还池/协程结束前必须调用）。 */
	public static void clear() {
		MDC.remove(RUN_ID);
		MDC.remove(SESSION_ID);
	}

	/** 当前 MDC 里的 runId（无则 null）。 */
	public static String currentRunId() {
		return MDC.get(RUN_ID);
	}

	/** 当前 MDC 里的 sessionId（无则 null）。 */
	public static String currentSessionId() {
		return MDC.get(SESSION_ID);
	}

	/**
	 * 把 <b>调用时刻</b>的 MDC 快照带进异步任务，任务结束后恢复该线程原状。
	 *
	 * @param runnable 实际任务
	 * @return 可直接提交给任意 {@code Executor} 的包装体
	 */
	public static Runnable wrap(Runnable runnable) {
		Map<String, String> captured = MDC.getCopyOfContextMap();
		return () -> {
			Map<String, String> previous = MDC.getCopyOfContextMap();
			apply(captured);
			try {
				runnable.run();
			}
			finally {
				apply(previous);
			}
		};
	}

	/**
	 * {@link #wrap(Runnable)} 的取返回值版本（{@code ExecutorService.submit(Callable)} 用）。
	 *
	 * <p>刻意换个名字而不做重载：一个"返回值的 lambda"同时也是合法的 {@code Runnable}，
	 * 重载会在调用点产生歧义。
	 */
	public static <T> java.util.concurrent.Callable<T> wrapCallable(java.util.concurrent.Callable<T> callable) {
		Map<String, String> captured = MDC.getCopyOfContextMap();
		return () -> {
			Map<String, String> previous = MDC.getCopyOfContextMap();
			apply(captured);
			try {
				return callable.call();
			}
			finally {
				apply(previous);
			}
		};
	}

	private static void apply(Map<String, String> from) {		if (from == null || from.isEmpty()) {
			MDC.clear();
			return;
		}
		MDC.setContextMap(from);
	}

	// ── 结构化事件 ──────────────────────────────────────────────────────────

	/**
	 * 产出一条结构化单行事件：{@code event=<name> runId=<..> sessionId=<..> <k>=<v> ...}。
	 *
	 * <p>{@code runId}/{@code sessionId} 取自 MDC（缺省渲染 {@code -}，便于"这条事件没挂上锚点"
	 * 一眼可见 —— 静默补空值会让 MDC 漏绑变成看不见的缺陷）。
	 *
	 * @param name 事件名（{@code turn_start} / {@code tool_call_end} …）
	 * @param kv   成对的键值（奇数个时最后一个以 {@code -} 补齐，不抛异常：日志不许影响主流程）
	 */
	public static void event(String name, Object... kv) {
		eventOn(null, null, name, kv);
	}

	/**
	 * 显式锚点版：调用方<b>已经知道</b>本行属于哪一轮，而当前线程的 MDC 未必装着它
	 * （HTTP 提交线程、跨实例唤醒线程）。显式值优先，缺省回落 MDC ——
	 * 于是"事件行的 runId 一定对"不依赖调用线程的运气。
	 */
	public static void eventOn(String runId, String sessionId, String name, Object... kv) {
		if (!log.isInfoEnabled()) {
			return;
		}
		StringBuilder line = new StringBuilder(96);
		line.append("event=").append(clean(name)).append(' ');
		appendField(line, RUN_ID, runId == null || runId.isBlank() ? currentRunId() : runId);
		line.append(' ');
		appendField(line, SESSION_ID, sessionId == null || sessionId.isBlank() ? currentSessionId() : sessionId);
		for (int i = 0; i + 1 < kv.length; i += 2) {
			line.append(' ');
			appendField(line, String.valueOf(kv[i]), kv[i + 1]);
		}
		if (kv.length % 2 == 1) {
			line.append(' ').append(clean(kv[kv.length - 1])).append("=-");
		}
		log.info(line.toString());
	}

	private static void appendField(StringBuilder line, String key, Object value) {
		line.append(clean(key)).append('=').append(clean(value));
	}

	/**
	 * 单行化 + 截断。换行/制表符折成空格：一条事件必须只占一行，否则 grep 与日志聚合都会错位。
	 */
	static String clean(Object value) {
		if (value == null) {
			return "-";
		}
		String text = String.valueOf(value);
		StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_VALUE_CHARS));
		for (int i = 0; i < text.length() && out.length() < MAX_VALUE_CHARS; i++) {
			char ch = text.charAt(i);
			out.append(ch == '\r' || ch == '\n' || ch == '\t' ? ' ' : ch);
		}
		if (out.length() == 0) {
			return "-";
		}
		if (text.length() > MAX_VALUE_CHARS) {
			out.append("…");
		}
		return out.toString();
	}

}
