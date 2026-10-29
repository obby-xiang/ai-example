package com.example.configmgr.ai.config;

import com.example.configmgr.common.sse.SseWriteBudget;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI Runtime 运行参数（规格书 S4.2 §4 配置键表，逐键对应）。
 *
 * <p>
 * <b>唯一绑定</b>：{@code app.ai} 前缀只由本类绑定。第一棒时期基座
 * {@code AppProperties.Ai}（max-iterations / session-ttl-minutes / hitl-timeout-minutes /
 * thinking-disabled）与本类并列绑定同一前缀（P1 §5 遗留 N4 双绑定），本棒已删除基座那套：
 * {@code thinking-disabled} 在新运行时早已失效（新路径走"官方循环 + reasoning 补丁"，
 * 不设 {@code extraBody.thinking}），{@code max-iterations} 由官方循环的退出条件取代，
 * 两个 ttl/timeout 键则被本类的 Duration 形态取代。
 *
 * <p>
 * 模型侧参数不在这里，走官方 {@code spring.ai.openai.*}（DC-01 全配置化）。
 */
@Data
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

	/**
	 * 会话记忆滑动 TTL（写入刷新；默认 24h，用户裁决 2026-10-20 第 4 条）。
	 *
	 * <p>滑动续期 = 活跃即续；整个会话维度，<b>无绝对上限</b>。run 级外置键（快照/待决/台账/帧/序号/心跳）
	 * 的 TTL 同取本值且每次写/心跳都整额续期 —— 挂起等人期间卡的背景数据不会先于会话过期。
	 */
	private Duration sessionTtl = Duration.ofHours(24);

	private Memory memory = new Memory();

	private Suspend suspend = new Suspend();

	private Hitl hitl = new Hitl();

	private Resilience resilience = new Resilience();

	private Session session = new Session();

	private Sse sse = new Sse();

	private Resume resume = new Resume();

	/**
	 * 思考（{@code reasoning_content}）的展示与持久化开关。
	 *
	 * <p>展示（帧/历史）与持久化（记忆回填）解耦：默认不展示 —— 上游零思考帧、{@code /history}
	 * 无 {@code reasoningContent}；思考仍按 {@link Reasoning#isPersistContent()} 累计进 assistant
	 * 消息 metadata，供下一轮回填。打开 {@code display-enabled} 即回到逐片出帧、历史透出的行为。
	 */
	private Reasoning reasoning = new Reasoning();

	/**
	 * 人工等待 <b>park</b>（等人/等前端时不驻留线程；用户裁决 2026-10-25）。
	 */
	private Park park = new Park();

	private Tool tool = new Tool();

	private ResultBudget resultBudget = new ResultBudget();

	/**
	 * Redis 事件日志/轮次索引的<b>容量治理</b>（Redis 容量专项 A；{@code app.ai.stream.*}）。
	 */
	private Stream stream = new Stream();

	private Lease lease = new Lease();

	private Lock lock = new Lock();

	private Input input = new Input();

	private Limit limit = new Limit();

	private Config config = new Config();

	/**
	 * 表格工具治理开关（AI 表格工具治理方案 2026-10-22 §2；消费方 = {@code SheetSwitches}）。
	 *
	 * <p>四个键的唯一事实源就在这里：{@code application.yml} 只写注释、不写值（7 键显化纪律）。
	 */
	private Sheet sheet = new Sheet();

	private Context context = new Context();

	private Kb kb = new Kb();

	/**
	 * 模型配置的读写开关（模型配置专项批①；设计稿 §3.3 / §3.4 / §3.5）。
	 *
	 * <p>
	 * 批①：{@link #seedForce}（strict seed 的显式运维逃生门）与 {@link #reloadInterval}
	 * （读路径版本比对的最小间隔）。批②：{@link #allowPrivateEndpoints}（URL 闸的私网开关）、
	 * {@link #probeTimeout}（{@code /test} 与拉取模型的超时）、{@link #modelsCacheTtl}（远端模型清单缓存）。
	 */
	@Data
	public static class Config {

		/**
		 * 强制重灌 env 种子（默认 false = strict seed：表非空时 env 完全不参与）。
		 *
		 * <p>
		 * 默认 false 是<b>刻意</b>的：表非空后改 env 不再改变线上行为（否则运维改 env 会静默
		 * 改掉 DB 真源，与"DB 为真源"自相矛盾）。需要重灌时显式打开本键并重启 —— 动作本身
		 * 就是"我知道会覆盖 seed 行的 base-url/model"。
		 */
		private boolean seedForce = false;

		/**
		 * 读路径版本比对的最小间隔（缺省 1s）。
		 *
		 * <p>
		 * 稳态 = 至多 1 次版本比对/秒/实例（Redis 有 {@code ai:cfg:ver} 时只 GET 一次；Redis 不可用
		 * 时退化为一次 DB 主键查询），<b>不</b>在 chat 热路径上逐轮打表。单测把它设 0 让版本比对
		 * 每次都做（确定性，不靠 sleep）。
		 */
		private Duration reloadInterval = Duration.ofSeconds(1);

		/**
		 * 是否允许把 provider 指向私网/回环地址（默认 <b>false</b>；设计稿 §3.5 URL 闸）。
		 *
		 * <p>
		 * 默认关 = SSRF 面默认关闭：写路径只接受"解析得到公网地址"的 http/https 绝对 URL。
		 * 打开它只该发生在两种场景：① E2E/CI 用 {@code http://127.0.0.1:18399} 的桩；② 内网自建的
		 * OpenAI 兼容网关。打开等于"允许应用去打内网"，是取舍不是根治（设计稿 §9 最后一条）。
		 *
		 * <p>
		 * <b>种子路径不受本开关约束</b>：env 是受信来源（运维已经在进程环境里写了这个地址），
		 * {@code key_ref=ENV} 的种子行探活/拉模型按受信处理 —— 否则 CI 的桩端点会被自己的闸拦掉。
		 */
		private boolean allowPrivateEndpoints = false;

		/**
		 * {@code /test} 与 {@code /models/fetch} 的单次上游超时（缺省 5s；设计稿 §3.5）。
		 *
		 * <p>
		 * 这两个动作是<b>人点按钮</b>等结果的同步操作，不能按聊天那套 30s/90s 的韧性预算取值；
		 * 5s 覆盖"跨公网握手 + /models 列表返回"的常见耗时，超时即给 {@code UNREACHABLE}
		 * （"网络不可达/超时"），不无限等。单测把它调到 150ms 以确定性触发超时档。
		 */
		private Duration probeTimeout = Duration.ofSeconds(5);

		/**
		 * 远端模型清单的缓存 TTL（缺省 10min；设计稿 §3.1「不建表的东西」）。
		 *
		 * <p>
		 * 缓存键 {@code ai:cfg:models:<providerId>}，只缓存"上游实际返回的 id 列表"，<b>不</b>缓存
		 * allowlist（allowlist 在 {@code ai_model} 表里）。Redis 不可用 ⇒ 每次真拉（不降级到"假装成功"）。
		 */
		private Duration modelsCacheTtl = Duration.ofMinutes(10);
	}

	/**
	 * 表格工具治理开关（AI 表格工具治理方案 2026-10-22 §2.1；四键，值唯一事实源 = 本类）。
	 *
	 * <p>
	 * 生效点<b>只有一个</b>：{@code ToolRegistry} 的披露谓词（{@code forContext} 与
	 * {@code matchesContext} 共用同一判定，不新增第四道防线）。关闸的语义是"工具不披露"
	 * （模型看不见），不是"调用了再拒"；旧缓存调用由执行前复查拦下并回 {@code SHEET_DISABLED}。
	 *
	 * <p>
	 * 两个上界键<b>只能收紧、不能放大</b>：值大于前端硬顶时按硬顶计
	 * （有效值 = {@code min(本键, 硬顶)}，由 {@code SheetSwitches} 收敛并经 {@code /health.sheet}
	 * 下发前端）—— 硬顶是 R3 的物理边界，不随配置漂移。
	 */
	@Data
	public static class Sheet {

		/**
		 * 表格读侧总闸（缺省 <b>true</b>）。
		 *
		 * <p>覆盖 5 个纯读工具（{@code read_sheet_summary} / {@code aggregate_sheet} /
		 * {@code inspect_columns} / {@code review_cells} / {@code find_rows}）。缺省 true = 允许把
		 * ≤20 行投影采样送进模型（D2 授权见 {@code docs/evidence} 的授权件）。关掉 = 这 5 个工具
		 * 退出披露面，模型看不到也不会被调用；不是"调用了再拒"。
		 *
		 * <p><b>{@code normalize_column} 不单独归本键</b>：它有 write 档，按"读闸 AND 写闸"披露
		 * （见 {@link #writeEnabled}）。改本键需<b>重启</b>（进程内一次绑定，与
		 * {@code app.ai.config.seed-force} 同口径）。
		 */
		private boolean readEnabled = true;

		/**
		 * 表格写侧总闸（缺省 <b>false</b>）。
		 *
		 * <p>覆盖 {@code replace_values} / {@code update_rows} / {@code undo_last_sheet_edit} 与
		 * {@code normalize_column} 的<b>两档</b>。缺省 false：写会改用户眼前的表格，默认必须由管理员
		 * 显式打开（与"模型配置写端点默认禁私网""AI 可用性动态判空"同姿态 —— 改状态的通道默认关）。
		 * 关掉时这 4 个工具不披露；模型若凭旧缓存调用，回执码 {@code SHEET_DISABLED}
		 * （不是 {@code SCOPE_VIOLATION}：那会让用户以为是自己点错了页面）。
		 *
		 * <p><b>{@code normalize_column} 的双闸规则 = readEnabled AND writeEnabled</b>：它的
		 * {@code commit=false} 档虽是只读建议，但与 {@code commit=true} <b>同名同形参</b> ⇒ 若按读闸
		 * 开放，模型可直接传 {@code commit=true} 绕过写闸。改本键需重启。
		 */
		private boolean writeEnabled = false;

		/**
		 * 单次采样行数上界（缺省 20 = 硬顶）。
		 *
		 * <p><b>只能收紧、不能放大</b>：值 &gt; 20 时按 20 计（硬顶是 R3 的物理边界，不随配置漂移）；
		 * 有效值 = {@code min(本键, 20)}，经 {@code /health.sheet.sampleMaxRows} 下发前端。
		 * 改本键需重启。
		 */
		private int sampleMaxRows = 20;

		/**
		 * 单次投影列数上界（缺省 5 = 硬顶；同 {@code columnCodes} 的 5 列硬校）。
		 *
		 * <p><b>只能收紧</b>：值 &gt; 5 时按 5 计；有效值 = {@code min(本键, 5)}，经
		 * {@code /health.sheet.projectionMaxColumns} 下发前端。改本键需重启。
		 *
		 * <p><b>选区路径不适用</b>：用户亲手框选 = 逐次授权（2026-10-22 裁决，方案 §8 K-11 / §10 O-9），
		 * 故本键只约束 {@code columnCodes} 与缺省投影两条路径。
		 */
		private int projectionMaxColumns = 5;
	}

	/**
	 * 启动期校验 {@code app.ai.sse.write-timeout} 必须为正（2026-10-10 复核 S3-1 收口：守卫对称化）。
	 *
	 * <p>
	 * {@code SseWriteBudget.on} 对<b>非正</b>预算会<b>静默回落</b>缺省 15s：运维显式配
	 * {@code write-timeout: 0s}（意图"取消写预算"）或负值/笔误，都不会有任何告警，运行行为与配置文件
	 * 不符 —— 而本键的取值论证（{@link Sse#getWriteTimeout()} 与
	 * {@link com.example.configmgr.common.sse.SseWriteBudget} 的类注）全是按"真的有一个 15s 预算"
	 * 展开的，静默变值等于让配置面说谎。
	 *
	 * <p>
	 * 读的是<b>绑定后</b>的值（绑定先于 {@code @PostConstruct}），与任务通道
	 * {@code app.task.sse.write-timeout} 的守卫（{@code com.example.configmgr.config.AppProperties}）
	 * 对称：两条通道都是"非正即启动失败"。这里 <b>fail-fast</b>：
	 * 本项配错没有"性能降级"这种解释空间，它只会让配置面与运行面说两套话。
	 */
	@PostConstruct
	void verifySseWriteTimeout() {
		Duration writeTimeout = this.sse.getWriteTimeout();
		if (writeTimeout == null || writeTimeout.isZero() || writeTimeout.isNegative()) {
			throw new IllegalStateException("app.ai.sse.write-timeout 必须 > 0（当前 = " + writeTimeout
					+ "）：0/负值会被 SseWriteBudget 静默回落为缺省 15s，运行行为与配置文件不符"
					+ "（排障误导）；收紧写侧释放只需配一个更小的正值");
		}
	}

	/**
	 * 启动期校验 {@code app.ai.resume.zombie-age} 必须 ≥ "配置允许的合法静默 + 余量"（R1-09 / I-1）。
	 *
	 * <p>
	 * 两类合法静默：工具执行上限 {@code app.ai.tool.exec-timeout}（工具跑着时不出业务帧）与
	 * 上游事件间静默上限 {@code app.ai.resilience.inter-event-timeout}。僵尸阈值一旦 ≤ 它们，
	 * 长工具 / 慢吐字的<b>活轮</b>会被别的实例判死并接管 —— 同一轮执行两遍，且第二遍是新的模型
	 * 请求 + 新的工具副作用（{@code toolCallId} 不同，认领去重救不了）。
	 *
	 * <p>
	 * <b>为什么还要求余量</b>（{@code app.ai.resume.zombie-margin}，缺省 60s）：只做到"大于上限"
	 * 时，工具刚好跑满上限、上游静默刚到线，调度延迟/边界抖动/跨节点时钟偏差就会把活轮推进判死窗。
	 * 余量把"合法静默上界"与"判死阈值"真正分开。出厂 180s = max(120s, 90s) + 60s。
	 *
	 * <p>
	 * <b>两类超时必须 &gt; 0</b>：{@code SpToolCallingManager} 把 {@code exec-timeout <= 0} 解释为
	 * "无超时"，此时"合法静默"没有上界，拿它做僵尸基线没有意义。
	 *
	 * <p>
	 * 与 {@link #verifySseWriteTimeout()} 同一姿态：这不是"性能调参"，配错即语义被静默改写，
	 * 故 <b>fail-fast</b> 并点名具体键与当前值。
	 */
	@PostConstruct
	void verifyZombieAge() {
		Duration zombieAge = this.resume == null ? null : this.resume.getZombieAge();
		Duration margin = this.resume == null ? null : this.resume.getZombieMargin();
		Duration execTimeout = this.tool == null ? null : this.tool.getExecTimeout();
		Duration interEvent = this.resilience == null ? null : this.resilience.getInterEventTimeout();
		if (zombieAge == null || margin == null || execTimeout == null || interEvent == null) {
			throw new IllegalStateException("app.ai.resume.zombie-age / app.ai.resume.zombie-margin / "
					+ "app.ai.tool.exec-timeout / app.ai.resilience.inter-event-timeout 至少一项缺失："
					+ "僵尸判据无法与合法静默对齐");
		}
		if (execTimeout.isZero() || execTimeout.isNegative() || interEvent.isZero() || interEvent.isNegative()) {
			throw new IllegalStateException("app.ai.tool.exec-timeout（当前 = " + execTimeout
					+ "）与 app.ai.resilience.inter-event-timeout（当前 = " + interEvent
					+ "）必须 > 0：0/负值等于永不超时，合法静默没有上界，不能作为僵尸判据的基线");
		}
		if (margin.isNegative()) {
			throw new IllegalStateException("app.ai.resume.zombie-margin 必须 >= 0（当前 = " + margin
					+ "）：负余量会让僵尸阈值落到合法静默之内");
		}
		Duration legalSilence = execTimeout.compareTo(interEvent) >= 0 ? execTimeout : interEvent;
		Duration required = legalSilence.plus(margin);
		if (zombieAge.compareTo(legalSilence) <= 0 || zombieAge.compareTo(required) < 0) {
			throw new IllegalStateException("app.ai.resume.zombie-age（当前 = " + zombieAge
					+ "）必须 >= max(app.ai.tool.exec-timeout = " + execTimeout
					+ ", app.ai.resilience.inter-event-timeout = " + interEvent
					+ ") + app.ai.resume.zombie-margin（当前 = " + margin + "，即 >= " + required
					+ "）：否则正在跑长工具/慢吐字的活轮会被判成僵尸并接管（同一轮双执行）");
		}
	}

	/**
	 * 启动期校验 {@code app.ai.limit.*} 必须为正（复审 M1；与上面 {@link #verifySseWriteTimeout()} 同一姿态）。
	 *
	 * <p>
	 * 两个键配错都不是"性能降级"，而是<b>语义被静默改写</b>：
	 * <ul>
	 * <li>{@code session-per-minute / ip-per-minute ≤ 0}：Lua 判 {@code count < limit} 恒假 ⇒ <b>每条</b>
	 * {@code /chat} 都 429 —— 配置面看起来是"限流"，运行面是"关掉对话"；</li>
	 * <li>{@code window ≤ 0}：{@code PEXPIRE 0} 会立刻删键 ⇒ 计数永远归零、限流<b>静默失效</b>
	 * （运维以为有闸，实际没有 —— 比报错更危险）。</li>
	 * </ul>
	 * 故启动即失败，并点名具体键与当前值。
	 */
	@PostConstruct
	void verifyRateLimit() {
		Limit limit = this.limit;
		if (limit == null) {
			throw new IllegalStateException("app.ai.limit 段缺失（绑定为 null）：限流无法装配，启动失败");
		}
		if (limit.getSessionPerMinute() <= 0) {
			throw new IllegalStateException("app.ai.limit.session-per-minute 必须 > 0（当前 = "
					+ limit.getSessionPerMinute() + "）：0/负值会让每条 /chat 都 429（等于关掉对话）");
		}
		if (limit.getIpPerMinute() <= 0) {
			throw new IllegalStateException("app.ai.limit.ip-per-minute 必须 > 0（当前 = " + limit.getIpPerMinute()
					+ "）：0/负值会让每个来源的每条 /chat 都 429（等于关掉对话）");
		}
		Duration window = limit.getWindow();
		if (window == null || window.isZero() || window.isNegative()) {
			throw new IllegalStateException("app.ai.limit.window 必须 > 0（当前 = " + window
					+ "）：Lua 的 PEXPIRE 0/负值会立刻删键 ⇒ 计数永远归零、限流静默失效");
		}
	}

	/**
	 * 启动期校验 {@code app.ai.result-budget.*}（G5 双通道对齐批）。
	 *
	 * <p>
	 * 这里的坏值都不是"性能降级"，而是<b>静默关闸或两侧口径分叉</b>：
	 * <ul>
	 * <li>{@code ui.max-frame-bytes <= 0} ⇒ {@code FrameSizeLimiter} 把 {@code <= 0} 读成"不限制"，
	 * 单帧上限静默失效（一个超大 {@code result} 直接压垮订阅者）；</li>
	 * <li>{@code callback.max-chars <= 0} ⇒ 回灌端点把每次回灌都"截成空 + 标记"，
	 * 前端工具永远拿不到结果（比拒绝更隐蔽）；</li>
	 * <li>{@code default-max-chars / fallback-max-chars < 0} ⇒ 负值在裁剪器里等价于"不限制"，
	 * 与"0 = 不限制"的显式口径撞车（写错一个符号就把上下文成本控制关掉）；</li>
	 * <li>{@code per-tool} 的负值 ⇒ 只影响单个工具，同样会让该工具的结果整段落进模型上下文。</li>
	 * </ul>
	 * 故启动即失败，并点名具体键与当前值（{@code per-tool} 还点名工具名）。
	 */
	@PostConstruct
	void verifyResultBudget() {
		ResultBudget budget = this.resultBudget;
		if (budget == null) {
			throw new IllegalStateException("app.ai.result-budget 段缺失（绑定为 null）：工具结果预算无法装配，启动失败");
		}
		ResultBudget.Model model = budget.getModel();
		if (model == null) {
			throw new IllegalStateException("app.ai.result-budget.model 段缺失（绑定为 null）：启动失败");
		}
		if (model.getMaxRows() < 0) {
			throw new IllegalStateException("app.ai.result-budget.model.max-rows 不得为负（当前 = "
					+ model.getMaxRows() + "）：0 = 不限制；负值只会让行数闸静默失效");
		}
		if (model.getDefaultMaxChars() < 0) {
			throw new IllegalStateException("app.ai.result-budget.model.default-max-chars 不得为负（当前 = "
					+ model.getDefaultMaxChars() + "）：0 = 不限制；负值在裁剪器里同样等于关掉上限");
		}
		if (model.getFallbackMaxChars() < 0) {
			throw new IllegalStateException("app.ai.result-budget.model.fallback-max-chars 不得为负（当前 = "
					+ model.getFallbackMaxChars() + "）：0 = 不限制；未知工具的兜底档不允许静默关闸");
		}
		if (model.getPerTool() != null) {
			for (Map.Entry<String, Integer> entry : model.getPerTool().entrySet()) {
				if (entry.getValue() == null || entry.getValue() < 0) {
					throw new IllegalStateException("app.ai.result-budget.model.per-tool." + entry.getKey()
							+ " 不得为负（当前 = " + entry.getValue() + "）：0 = 该工具不限制");
				}
			}
		}
		ResultBudget.Ui ui = budget.getUi();
		if (ui == null || ui.getMaxFrameBytes() < ResultBudget.Ui.MIN_FRAME_BYTES) {
			// 拆帧专项：低于 1KiB 不是"更省"，而是帧数爆炸（一条正文切成成百上千片）或正文被清空
			// （连最小合法帧外壳都装不下）。故下限与 >0 合并成同一条 fail-fast，并点名下限值。
			throw new IllegalStateException("app.ai.result-budget.ui.max-frame-bytes 必须 >= "
					+ ResultBudget.Ui.MIN_FRAME_BYTES + "（当前 = "
					+ (ui == null ? "段缺失" : ui.getMaxFrameBytes())
					+ "）：0/负值等于'不限制'（单帧上限静默失效），低于 1KiB 则拆分粒度太细"
					+ "（帧数爆炸）或把正文清空；delta/reasoning 按本值分片，result 仍走字段级降采样");
		}
		ResultBudget.Callback callback = budget.getCallback();
		if (callback == null || callback.getMaxChars() <= 0) {
			throw new IllegalStateException("app.ai.result-budget.callback.max-chars 必须 > 0（当前 = "
					+ (callback == null ? "段缺失" : callback.getMaxChars())
					+ "）：0/负值会把每次前端回灌都截成空（前端工具永远拿不到结果）");
		}
	}

	/**
	 * 启动期校验 {@code app.ai.stream.*}（Redis 容量专项 A；与 {@link #verifyResultBudget()} 同一姿态）。
	 *
	 * <p>两个键的坏值都<b>不是</b>"性能降级"：
	 * <ul>
	 * <li>{@code byte-budget < 0}：{@code 0} 才是"关闭字节治理"；负值只会让"预算 ÷ 每条上界"
	 * 变成负数、窗口静默落到下限 —— 配置面写"按字节"，运行面永远不按字节；</li>
	 * <li>{@code index-prune-interval < 0}：{@code 距上次清理 ≥ 间隔} 恒真 ⇒ 每次 {@code create}
	 * 都全表扫 {@code ai:runs}（{@code SMEMBERS} + 逐成员 {@code EXISTS}），热路径退化成 O(索引大小)。</li>
	 * </ul>
	 * 故启动即失败并点名键与当前值。
	 */
	@PostConstruct
	void verifyStream() {
		Stream stream = this.stream;
		if (stream == null) {
			throw new IllegalStateException("app.ai.stream 段缺失（绑定为 null）：事件日志容量治理无法装配，启动失败");
		}
		if (stream.getByteBudget() < 0L) {
			throw new IllegalStateException("app.ai.stream.byte-budget 不得为负（当前 = "
					+ stream.getByteBudget() + "）：0 = 关闭字节治理（只按条数窗口），负值没有解释空间");
		}
		Duration interval = stream.getIndexPruneInterval();
		if (interval == null || interval.isNegative()) {
			throw new IllegalStateException("app.ai.stream.index-prune-interval 不得为负（当前 = " + interval
					+ "）：负间隔让每次 create 都全表扫 ai:runs；0 = 关闭机会式清理");
		}
	}

	/**
	 * 启动期校验 {@code app.ai.context.*}（上下文统计与压缩专项批①；与上面两个守卫同一姿态）。
	 *
	 * <p>
	 * 这里的每个坏值都不是"性能降级"，而是<b>判定被静默改写</b>：
	 * <ul>
	 * <li>{@code reserve >= limit}（且 limit &gt; 0）⇒ 分母 {@code limit − reserve <= 0} 被钳到 1，
	 * 任何一轮都判"已满"、每轮都触发压缩（前缀每轮重写、缓存全灭）；</li>
	 * <li>{@code l1/l2/l3} 非单调 ⇒ 谱系倒挂（L2 比 L1 先到 = 最轻的一级永远不触发）；</li>
	 * <li>{@code chunk-tokens >= reserve} ⇒ 摘要请求自身就可能越窗（批③ 的失败路径）；</li>
	 * <li>{@code fuse-threshold <= 0} ⇒ 第一次失败即永久熔断（L2/L3 再也不会尝试）。</li>
	 * </ul>
	 * 因此启动即失败并点名键与值。
	 */
	@PostConstruct
	void verifyContext() {
		Context context = this.context;
		if (context == null) {
			throw new IllegalStateException("app.ai.context 段缺失（绑定为 null）：上下文计量无法装配，启动失败");
		}
		if (context.getLimitTokens() < 0) {
			throw new IllegalStateException("app.ai.context.limit-tokens 不得为负（当前 = " + context.getLimitTokens()
					+ "）：0 = 主动声明未知（降级态），负值没有任何解释空间");
		}
		if (context.getReserveTokens() <= 0) {
			throw new IllegalStateException("app.ai.context.reserve-tokens 必须 > 0（当前 = "
					+ context.getReserveTokens() + "）：预留缓冲为 0 会让触发线与上下文上限重合，"
					+ "输出空间被压缩判定吃掉");
		}
		if (context.getLimitTokens() > 0 && context.getReserveTokens() >= context.getLimitTokens()) {
			throw new IllegalStateException("app.ai.context.reserve-tokens（" + context.getReserveTokens()
					+ "）必须小于 limit-tokens（" + context.getLimitTokens()
					+ "）：分母 limit − reserve 会被钳到 1 ⇒ 每轮都判已满、每轮都压缩");
		}
		checkPercent("l1-percent", context.getL1Percent());
		checkPercent("l2-percent", context.getL2Percent());
		checkPercent("l3-percent", context.getL3Percent());
		checkPercent("hysteresis-percent", context.getHysteresisPercent());
		if (!(context.getL1Percent() < context.getL2Percent() && context.getL2Percent() < context.getL3Percent())) {
			throw new IllegalStateException("app.ai.context 的 l1/l2/l3-percent 必须严格递增（当前 = "
					+ context.getL1Percent() + "/" + context.getL2Percent() + "/" + context.getL3Percent()
					+ "）：倒挂会让谱系失效（较轻的一级永远不触发）");
		}
		if (context.getHysteresisPercent() > context.getL1Percent()) {
			throw new IllegalStateException("app.ai.context.hysteresis-percent（" + context.getHysteresisPercent()
					+ "）不得大于 l1-percent（" + context.getL1Percent()
					+ "）：滞回线高于触发线 ⇒ 压缩后立刻满足再次触发条件（抖动链）");
		}
		if (context.getKeepToolPairs() < 0 || context.getKeepRecentTurns() < 0) {
			throw new IllegalStateException("app.ai.context.keep-tool-pairs / keep-recent-turns 不得为负（当前 = "
					+ context.getKeepToolPairs() + " / " + context.getKeepRecentTurns() + "）");
		}
		if (context.getChunkTokens() <= 0) {
			throw new IllegalStateException("app.ai.context.chunk-tokens 必须 > 0（当前 = " + context.getChunkTokens()
					+ "）：摘要分块为 0 会让摘要请求无限递归");
		}
		if (context.getChunkTokens() >= context.getReserveTokens()) {
			// M-1 收口：类注与 NUMSPEC 都承诺"分块 < 预留"，无闸则承诺与实现不一致。
			// 摘要本身也是一次模型请求：分块 ≥ 预留时，摘要请求会挤掉输出空间（越窗 ⇒ 失败 ⇒ 熔断）。
			throw new IllegalStateException("app.ai.context.chunk-tokens（" + context.getChunkTokens()
					+ "）必须小于 reserve-tokens（" + context.getReserveTokens()
					+ "）：摘要请求自身的输入分块不得吃掉预留缓冲（否则摘要请求会越窗）");
		}
		if (context.getSummaryTimeout() == null || context.getSummaryTimeout().isZero()
				|| context.getSummaryTimeout().isNegative()) {
			throw new IllegalStateException("app.ai.context.summary-timeout 必须 > 0（当前 = "
					+ context.getSummaryTimeout() + "）：0/负值等于摘要永不超时（熔断判据失去输入）");
		}
		if (context.getFuseThreshold() <= 0) {
			throw new IllegalStateException("app.ai.context.fuse-threshold 必须 > 0（当前 = " + context.getFuseThreshold()
					+ "）：0 = 第一次失败即熔断，L2/L3 再也不会被尝试");
		}
		if (context.getFuseCooldown() == null || context.getFuseCooldown().isZero()
				|| context.getFuseCooldown().isNegative()) {
			throw new IllegalStateException("app.ai.context.fuse-cooldown 必须 > 0（当前 = " + context.getFuseCooldown()
					+ "）：冷却为 0 = 一直半开，熔断形同虚设");
		}
	}

	/**
	 * 启动期校验 {@code app.ai.memory.*}（记忆窗口批；与 {@link #verifyContext()} 同一姿态）。
	 *
	 * <p>
	 * 这里的每个坏值都不是"性能降级"，而是<b>窗口判定被静默改写</b>：
	 * <ul>
	 * <li>{@code min-messages > max-messages} ⇒ 两套兜底互相打穿：上限想裁到 40、下限要求留 60，
	 * 结果是"配了上限但永远不生效"；</li>
	 * <li>{@code token-budget < 0} ⇒ 每一轮都判"越线"，历史被钉死在最近几条上；</li>
	 * <li>{@code min-messages <= 0} ⇒ 没有下限，预算可以把窗口压到只剩最后一个 turn（失忆）。</li>
	 * </ul>
	 * {@code token-budget = 0} 是<b>合法</b>的逃生门：关闭预算、只按条数（回到记忆窗口批之前的语义）。
	 */
	@PostConstruct
	void verifyMemory() {
		Memory memory = this.memory;
		if (memory == null) {
			throw new IllegalStateException("app.ai.memory 段缺失（绑定为 null）：记忆窗口无法装配，启动失败");
		}
		if (memory.getTokenBudget() < 0) {
			throw new IllegalStateException("app.ai.memory.token-budget 不得为负（当前 = " + memory.getTokenBudget()
					+ "）：0 = 关闭预算（只按条数），负值会让每一轮都判越线");
		}
		if (memory.getMinMessages() <= 0) {
			throw new IllegalStateException("app.ai.memory.min-messages 必须 > 0（当前 = " + memory.getMinMessages()
					+ "）：下限为 0 = 没有下限，预算可以把窗口压到只剩最后一个 turn（失忆）");
		}
		if (memory.getMaxMessages() <= 0) {
			throw new IllegalStateException("app.ai.memory.max-messages 必须 > 0（当前 = " + memory.getMaxMessages()
					+ "）：条数上限为 0 会让官方窗口什么都不留");
		}
		if (memory.getMinMessages() > memory.getMaxMessages()) {
			throw new IllegalStateException("app.ai.memory.min-messages（" + memory.getMinMessages()
					+ "）不得大于 max-messages（" + memory.getMaxMessages()
					+ "）：两套兜底互相打穿（上限永远不生效，窗口只剩预算一个维度）");
		}
	}

	private static void checkPercent(String key, int value) {
		if (value < 0 || value > 100) {
			throw new IllegalStateException("app.ai.context." + key + " 必须在 0–100（当前 = " + value + "）");
		}
	}

	/**
	 * 启动期校验 {@code app.ai.sheet.*}（AI 表格工具治理批；与 {@link #verifyResultBudget()} 同一姿态）。
	 *
	 * <p>
	 * 两个上界键的坏值都不是"性能降级"，而是<b>闸门被静默改写</b>：
	 * <ul>
	 * <li>{@code sample-max-rows <= 0} ⇒ 采样行数上界为 0，{@code capSheetResult} 的
	 * {@code Math.max(1, …)} 会把它救成 1 行 —— 配置面写"0 = 不采样"，运行面却仍回 1 行，
	 * 两个口径对不上；</li>
	 * <li>{@code projection-max-columns <= 0} ⇒ 同上，投影列被救成 1 列，读侧"缺省投影 = 主键列 +
	 * 前 4 列"的承诺与实发列数不符。</li>
	 * </ul>
	 * 故启动即失败并点名键与当前值（与 {@code verifyMemory} 的 {@code min-messages} 同类判据）。
	 *
	 * <p>
	 * <b>大于硬顶不是坏值</b>：本批的口径是"只能收紧、不能放大"（§2.1 / S-04）——
	 * 值 &gt; 20 / 5 时按硬顶计（由 {@code SheetSwitches} 收敛并在 {@code /health.sheet} 回显有效值），
	 * 不在这里拒绝。理由是"配大了"不会造成静默错误，只会被收敛；拒绝反而会让一次笔误把服务挡在门外。
	 */
	@PostConstruct
	void verifySheet() {
		Sheet sheet = this.sheet;
		if (sheet == null) {
			throw new IllegalStateException("app.ai.sheet 段缺失（绑定为 null）：表格工具开关无法装配，启动失败");
		}
		if (sheet.getSampleMaxRows() <= 0) {
			throw new IllegalStateException("app.ai.sheet.sample-max-rows 必须 > 0（当前 = "
					+ sheet.getSampleMaxRows() + "）：0/负值会被 capSheetResult 静默救成 1 行，"
					+ "配置面与运行面说两套话；要收紧请配 1..20 的正值");
		}
		if (sheet.getProjectionMaxColumns() <= 0) {
			throw new IllegalStateException("app.ai.sheet.projection-max-columns 必须 > 0（当前 = "
					+ sheet.getProjectionMaxColumns() + "）：0/负值会让投影列被救成 1 列，"
					+ "与「缺省投影 = 主键列 + 前 4 列」的承诺不符；要收紧请配 1..5 的正值");
		}
	}

	/**
	 * 知识库配置的启动期校验（业务知识问答 v1）。
	 *
	 * <p>
	 * 与 {@link #verifyContext()} 同一姿态：下面的倒挂/非正值都有"语义被静默改写"的后果 ——
	 * {@code top-k-default > top-k-max} 会让默认值在服务端强截后对不上工具描述；
	 * {@code read-max-chars-default > read-max-chars-max} 同理；
	 * 切块 {@code min >= max} 会让"合并到下限"与"超上限就切"互相打架（可能产出空块）；
	 * 匹配阈值区间外会让高/中/低三档永远只剩一档。故启动即失败并点名键与值（不静默纠正）。
	 *
	 * <p>
	 * {@code source=http} 但 {@code base-url} 为空是<b>配置错误</b>（不是"降级为 local"）：
	 * 静默换源等于让生产读到一份完全不同的语料，必须显式失败。
	 */
	@PostConstruct
	public void verifyKb() {
		Kb kb = this.kb;
		if (kb == null) {
			throw new IllegalStateException("app.ai.kb 段缺失（绑定为 null）：知识库装配无法进行，启动失败");
		}
		if (kb.getTopKDefault() <= 0 || kb.getTopKMax() <= 0
				|| kb.getTopKDefault() > kb.getTopKMax()) {
			throw new IllegalStateException("app.ai.kb.top-k-default/top-k-max 必须 > 0 且 default ≤ max（当前 = "
					+ kb.getTopKDefault() + "/" + kb.getTopKMax() + "）：否则服务端强截后的条数与工具描述不一致");
		}
		if (kb.getReadMaxCharsDefault() <= 0 || kb.getReadMaxCharsMax() <= 0
				|| kb.getReadMaxCharsDefault() > kb.getReadMaxCharsMax()) {
			throw new IllegalStateException("app.ai.kb.read-max-chars-default/read-max-chars-max 必须 > 0 且 default ≤ max（当前 = "
					+ kb.getReadMaxCharsDefault() + "/" + kb.getReadMaxCharsMax() + "）");
		}
		if (kb.getChunkMinChars() <= 0 || kb.getChunkMaxChars() <= 0
				|| kb.getChunkMinChars() >= kb.getChunkMaxChars()) {
			throw new IllegalStateException("app.ai.kb.chunk-min-chars/chunk-max-chars 必须 > 0 且 min < max（当前 = "
					+ kb.getChunkMinChars() + "/" + kb.getChunkMaxChars() + "）：倒挂会让切块空转");
		}
		if (!(kb.getMatchHighRatio() > 0 && kb.getMatchHighRatio() <= 1
				&& kb.getMatchMediumRatio() > 0 && kb.getMatchMediumRatio() <= 1
				&& kb.getMatchMediumRatio() < kb.getMatchHighRatio())) {
			throw new IllegalStateException("app.ai.kb.match-high-ratio/match-medium-ratio 必须在 (0,1] 且 medium < high（当前 = "
					+ kb.getMatchHighRatio() + "/" + kb.getMatchMediumRatio()
					+ "）：区间外会让 match 三档退化成单档");
		}
		if (kb.getRefreshInterval() == null || kb.getRefreshInterval().isZero()
				|| kb.getRefreshInterval().isNegative()) {
			throw new IllegalStateException("app.ai.kb.refresh-interval 必须 > 0（当前 = " + kb.getRefreshInterval()
					+ "）：0/负值等于关闭定时刷新，生产 HTTP 源的增量永远不发生");
		}
		String source = kb.getSource();
		if (!"local".equalsIgnoreCase(source) && !"http".equalsIgnoreCase(source)) {
			throw new IllegalStateException("app.ai.kb.source 只支持 local / http（当前 = " + source + "）");
		}
		if ("http".equalsIgnoreCase(source)
				&& (kb.getHttp().getBaseUrl() == null || kb.getHttp().getBaseUrl().isBlank())) {
			throw new IllegalStateException("app.ai.kb.source=http 时必须配置 app.ai.kb.http.base-url"
					+ "（静默回落 local 会让生产读到另一份语料）");
		}
		if (kb.isEnabled() && "local".equalsIgnoreCase(source)
				&& (kb.getDir() == null || kb.getDir().isBlank())) {
			throw new IllegalStateException("app.ai.kb.source=local 时 app.ai.kb.dir 不得为空");
		}
		if (kb.getCache().getLockTtl() == null || kb.getCache().getLockTtl().isZero()
				|| kb.getCache().getLockTtl().isNegative()) {
			throw new IllegalStateException("app.ai.kb.cache.lock-ttl 必须 > 0（当前 = "
					+ kb.getCache().getLockTtl() + "）：0/负值等于刷新权永不过期，一个实例崩溃会永久锁死刷新");
		}
		if (kb.getCache().getVersionPollInterval() == null || kb.getCache().getVersionPollInterval().isZero()
				|| kb.getCache().getVersionPollInterval().isNegative()) {
			throw new IllegalStateException("app.ai.kb.cache.version-poll-interval 必须 > 0（当前 = "
					+ kb.getCache().getVersionPollInterval() + "）：0/负值等于关闭版本号轮询，多实例永远看不到别人刷新");
		}
	}

	@Data
	public static class Memory {

		/**
		 * 条数上限（官方 {@code MessageWindowChatMemory} 的窗口大小；默认 40）。
		 *
		 * <p>记忆窗口批起它退化为<b>兜底</b>：主判据是 {@link #tokenBudget}，本键负责"预算没越线时
		 * 也别无限长"这一半（40 与调研区间 40–60 的下沿一致，取小 = 省 token 优先且不放大既有窗口）。
		 */
		private int maxMessages = 40;

		/**
		 * 历史 token 预算（默认 48000；{@code 0} = 关闭，只按条数）。
		 *
		 * <p>只算<b>记忆窗口里的历史</b>（system 人设/工作区快照与工具 schema 不在内 —— 它们在请求侧
		 * 单列，见 {@code ContextEstimator#estimateMessages} 跳过 SYSTEM 的口径）。取值依据 =
		 * 调研《调研-上下文统计与压缩业界惯例-2026-10-21》：历史预算区间 32k–48k，取上限（本仓工具结果
		 * 偏大、单轮回环多，取下沿会频繁淘汰）；与 {@code app.ai.context.limit-tokens} 不是一个东西：
		 * 后者是整请求上限、压缩谱系的分母，本键是"读进来的历史该有多长"。
		 */
		private int tokenBudget = 48000;

		/**
		 * 条数下限（默认 40，与 {@link #maxMessages} 同值）：token 预算淘汰时至少要留住的消息条数。
		 *
		 * <p>防的是"预算把窗口压到只剩最后一轮"—— 那不是省 token，是失忆。最后一轮本身永不被淘汰
		 * （见 {@code MemoryWindow}），故下限只在"前缀还有可淘汰的轮次"时起作用。
		 *
		 * <p><b>为什么是 40 且与上限同值</b>（2026-10-22 用户裁「业界主流的来吧，40」）：口径 = 条数
		 * 40 条不动、超预算靠<b>清旧工具结果</b>消化；turn 淘汰只在"清完仍超 <b>且</b>条数 &gt; 40"时
		 * 发生（min = max = 40 的实际语义 —— 窗口是"40 条的滑动历史"，预算只决定这 40 条里有多少工具
		 * 结果还留着原文）。业界调研区间"保留最近 3–8 轮"是按轮算，本仓按条计（一轮提问+回答 = 2 条），
		 * 40 条 ≈ 20 轮 ≈ 调研区间上限的 2–6 倍 —— 48k 预算才是真正起作用的那个维度。
		 */
		private int minMessages = 40;

	}

	@Data
	public static class Suspend {

		/**
		 * 挂起专用有界平台线程池大小（DC-12：禁用虚拟线程；ADR-2 重审 N1：池容量即挂起并发上限）。
		 *
		 * <p>
		 * BLOCKING 形态下每次挂起实际占用本池 1 条 + 官方硬编码的 {@code boundedElastic} 1 条；
		 * 容量联动约束为「专用池 × 2 + 投递池 ≤ boundedElastic 容量」（T3-1 投递池
		 * {@link Sse#getDeliveryPoolSize()}，§13.2 #2 压测范围）。
		 */
		private int poolSize = 20;
	}

	@Data
	public static class Hitl {

		/**
		 * 即时执行类前端工具（{@code navigate_to} 等）的回执等待上限（<b>等机器</b>，默认 120s）。
		 *
		 * <p><b>哪些工具读本键</b>（用户裁决 2026-10-20 第 2 条）：只有"下发了就等一个机器回执"的
		 * 即时执行类前端工具 —— 共 <b>6 个</b>：
		 * <ul>
		 * <li>{@code navigate_to}（页面导航）</li>
		 * <li>{@code select_definitions}（选择配置项）</li>
		 * <li>{@code set_condition}（设置查询条件）</li>
		 * <li>{@code confirm_step}（推进向导步骤）</li>
		 * <li>{@code open_export_file_editor}（打开在线编辑器）</li>
		 * <li>{@code download_export_files}（触发浏览器下载）</li>
		 * </ul>
		 * 这些工具都是"请求前端做一件事、等它回一个回执"，回执没回来是<b>机器故障</b>而非人的决定，
		 * 不能无限悬置。
		 *
		 * <p><b>哪些工具不读本键</b>（等的是人 ⇒ 无上限）：{@code generative_form}（人填表）、
		 * 三个表格写工具 {@code replace_values}/{@code update_rows}/{@code normalize_column}、
		 * {@code undo_last_sheet_edit}、{@code ui_request_upload}（等用户手势给文件句柄）——
		 * 共 <b>6 个</b>，按工具名集合 {@code ConfirmGate#HUMAN_WAIT_FRONTEND_TOOLS} 区分。
		 * 确认门（等人决策）同样<b>没有上限</b>，原 {@code app.ai.hitl.confirm-timeout} 键已退役。
		 * 区分点只有一个：{@code ConfirmGate#suspendWaitSeconds}（返回 null = 不设上限）。
		 *
		 * <p>取值依据（业界依据 §6「前端工具调用超时（AI 工具调用执行预算）」）：MCP 规范只要求
		 * SHOULD 设超时、不规定数值；实现层默认常见 <b>60s</b>，Vercel AI SDK 官方 {@code toolMs}
		 * 区间为 5–60s（跨系统工具取 60s）。本键保留现状 120s，理由见下。
		 *
		 * <p>超过本上限即收口（{@code status=TIMEOUT, executed=false}），但结局语义只说明
		 * "回执没回来"、<b>结局未知</b>（副作用可能已经发生，如文件已落盘）——
		 * 见 {@code ConfirmGate#expireFrontend} 与 {@code ConfirmGateFrontendTimeoutTextTest} 的回归锁。
		 *
		 * <p><b>已知约束（为什么暂维 120s）</b>：前端执行器预算由帧内 {@code timeoutSeconds} 折算
		 * （{@code frontend/src/utils/frontend-tools.ts#deriveFrontendToolBudgetMs}，折算式
		 * `预算 = 服务端上限 − 回灌 POST 上限(60s)`）；本键若降到 60s，该式即为 0、预算落 5s 下限 ——
		 * 该文件自己把 ≤65s 列为病态配置，而实测最慢的一次**成功**回灌发生在 30.6s，5s 会掐掉
		 * 已知会成功的路径。故降到 60s 的前置 = 前端折算面（回灌 POST 单独给更短上限）专项裁决
		 * （数字规格清点 P-1 / 等待治理 R-99②），<b>本次不动</b>。
		 */
		private Duration frontendToolTimeout = Duration.ofSeconds(120);
	}

	@Data
	public static class Resilience {

		/**
		 * 首包静默上限：从"发起本轮请求"到"上游第一个事件"之间允许的最长静默。
		 *
		 * <p>
		 * 初值 30s —— <b>待技术方案 §13.2 #2 压测裁决</b>（模型排队/冷启动时的实际首包分布）。
		 */
		private Duration firstByteTimeout = Duration.ofSeconds(30);

		/**
		 * 事件间静默上限：两个上游事件之间允许的最长静默。
		 *
		 * <p>
		 * 初值 90s —— <b>待 §13.2 #2 压测裁决</b>。注意它与硬规范①（阈值 &gt; 最长工具耗时）的
		 * 关系：挂起等待期间工具处于"活跃"，
		 * {@link com.example.configmgr.ai.run.StreamWatchdog} 在工具执行期间<b>不计静默</b>、
		 * 退出后以退出时刻重新计时，因此本值小于等待上限仍正确（即时执行类前端工具 120s &gt; 90s
		 * 同属此理）；确认门与 {@code generative_form} 自 2026-10-20 起<b>无等待上限</b>，
		 * 不再参与本条的"上限关系"。
		 */
		private Duration interEventTimeout = Duration.ofSeconds(90);

		/** 单轮最大尝试次数（首轮 + 重试次数-1）。 */
		private int maxAttempts = 2;

		/**
		 * 单轮总预算（墙钟）：SSE 超时、流式等待上限与韧性看门狗的共同上界。
		 *
		 * <p>
		 * 初值 300s —— <b>待 §13.2 #2 压测裁决</b>（现网最长一轮的真实耗时）。
		 * <b>人机预算分离</b>（用户裁决 2026-10-20 第 3 条）：挂起等待（确认门 / 前端工具 /
		 * 生成式表单）的时长<b>不计入</b>本预算 —— 判据口径是"墙钟 − 挂起等待"，
		 * 见 {@link com.example.configmgr.ai.run.ToolActivityBeacon#gateWaitMs(String)}。
		 */
		private Duration totalBudget = Duration.ofSeconds(300);

		/**
		 * 工具调用步数上限（超时面治理批② 第 2 项；决策看板 {@code :14} 的「步数笼」）。
		 *
		 * <p><b>累计、跨挂起不重置</b>（LangGraph 口径）：段级预算重置式让"每段 300s"成立，
		 * 若同时放开步数，一轮可以靠反复挂起无限续命 —— 故看板把本键定为段预算的<b>强制配套</b>
		 * （"二者缺一即失控"）。计数落在 {@link com.example.configmgr.ai.run.RunSnapshot#getToolSteps()}
		 * （外置：续跑可能落在另一个进程上）。
		 *
		 * <p>到顶后<b>不判整轮失败</b>：降级「部分完成收尾」—— 到顶的调用结算成
		 * {@code skipped/TOOL_STEP_LIMIT}，并在历史末尾追加一条收尾指令，让模型用已有信息作答。
		 */
		private int maxToolSteps = 25;
	}

	/**
	 * 后端工具执行面（超时面治理批② 第 3 项）。
	 */
	@Data
	public static class Tool {

		/**
		 * 单次后端工具执行的独立超时（缺省 120s；决策看板 {@code :16}）。
		 *
		 * <p>修的是"后端工具无执行超时"这一过松项（盘点 §二 ③-1：300s 段预算独自兜底
		 * 「工具无轮数上限 / 工具无执行超时 / 上游无 read timeout」三件事）。到点产出
		 * {@code TOOL_TIMEOUT}（{@code executed=false}），<b>轮继续</b>（同批后续调用照常）——
		 * 一个卡死的工具不该把用户已经看到的正文一并废掉。
		 *
		 * <p>取值 120s 与既有 {@code app.ai.hitl.frontend-tool-timeout} 同量级：前端工具等回执、
		 * 后端工具等执行，两者都不该比"人机等待"更松。长任务（IMPORT/EXPORT 等作业型）的
		 * <b>opt-in 免超时机制本批不做</b>（已登记为批③ 候选），本批先给出统一上限。
		 */
		private Duration execTimeout = Duration.ofSeconds(120);
	}

	/**
	 * 人工等待 <b>park</b>（等人/等前端时结束本次执行、交还线程；用户裁决 2026-10-25）。
	 *
	 * <h2>为什么</h2>
	 * 确认门 / {@code generative_form} / 表格写·撤销 / 上传交接都是"等人或等前端"的等待：
	 * 卡送达后 SSE 已按 B1 收流（{@code completeSubscriptions}），人可能在几秒后、也可能在几天后
	 * 才动。旧实现让一条 {@code aiRunExecutor} 线程<b>驻留</b>在 {@code ConfirmGate#await} 里，
	 * 同时每 2s 心跳、续期卡片数据、续租、看门狗继续判定 —— 挂起并发上限因此等于线程池容量，
	 * 而且"一直有心跳"会让会话门/僵尸判据把**没人管**的轮当活轮。
	 *
	 * <p>本开关打开后：请求帧发出并收流之后，await 立即通过内部挂起控制信号
	 * （{@link com.example.configmgr.ai.run.RunParkedException}）结束本次执行，
	 * 交还驱动租约、不登记 latch、不心跳、不续租、不进看门狗等待；Redis 里的
	 * 快照/待决/req/decided/帧归档就是<b>合法恢复状态</b>。决策/回灌到达后由
	 * {@code ResumeService} 重建执行（与跨实例续跑同一条通路），池满时有界重派发。
	 *
	 * <h2>两个缺省刻意不一致（与 {@code Resume#on-startup} 同一条纪律）</h2>
	 * <b>代码缺省 = false</b>：直接 {@code new AiProperties()} 的单测/非 Spring 装配保持
	 * "阻塞等待"的既有语义（大量 gate/tools 用例以"决策 → 等待方继续执行"为前提）；
	 * <b>出厂 {@code application.yml} = true</b>：生产（Spring 装配）走 park。
	 * 判别方法 = {@code app.ai.park.enabled} 是否被显式配置，而不是看字段初值。
	 */
	@Data
	public static class Park {

		/**
		 * 是否启用人工等待 park（代码缺省 false；出厂 yml true —— 见类注）。
		 *
		 * <p>关闭 = 本批之前的阻塞式等待（线程驻留、2s 心跳、续租、看门狗继续判定）。
		 */
		private boolean enabled = false;

		/**
		 * 派发失败（续跑池饱和 / 会话门被别的轮占着）时的<b>有界</b>重派发次数（缺省 15）。
		 *
		 * <p>为什么必须有：决策一旦落库，"推进这一轮"的责任就从等待方转给了派发方；
		 * 池饱和时如果只记一条日志，用户已经批准的卡会停在"已决 + 无人执行"。
		 *
		 * <p><b>I3：次数必须覆盖一次正常轮的收尾窗口</b>。一轮可持续到
		 * {@code app.ai.resilience.total-budget}（300s），固定 500ms×3 的窗口（1.5s）覆盖不到
		 * 任何一次真实轮次的收尾 ⇒ 等于"批准了但永久不执行"。本批改成<b>指数退避</b>
		 * （{@link #dispatchRetryDelay} 起、按 {@link #dispatchRetryMaxDelay} 封顶），
		 * 缺省 15 次 = 2+4+8+16+30×11 ≈ 360s 窗口（覆盖 300s + 60s 余量；10 次只有 210s，
		 * 短于轮预算本身）；用尽后不再自动重排（死卡不占用调度线程），
		 * 但<b>可被下一次触发重新计数</b>（决策/重挂/启动扫描），并 ERROR 留痕。
		 */
		private int maxDispatchRetries = 15;

		/** 第一次重派发的间隔（缺省 2s；之后按 2 的幂递增，封顶见 {@link #dispatchRetryMaxDelay}）。 */
		private Duration dispatchRetryDelay = Duration.ofSeconds(2);

		/** 重派发间隔的封顶（缺省 30s；避免指数退避把调度器占成高频轮询）。 */
		private Duration dispatchRetryMaxDelay = Duration.ofSeconds(30);

	}

	@Data
	public static class Resume {
		/**
		 * 启动自动续跑（N3/N8）：应用就绪后扫描 {@link com.example.configmgr.ai.run.RunStore}
		 * 里仍存活的轮次，把"已有人类决策/后端工具在飞"的那些自动续跑。
		 *
		 * <p>
		 * <b>两个缺省不一致是刻意的（超时面治理批① 第 8⑤ 项钉明）</b>：
		 * <b>代码缺省 = false</b>（保守：自动补执行破坏性工具需谨慎），而<b>出厂
		 * {@code application.yml} 显式配 true</b>（运维可在部署面关掉）。因此线上行为以 yml 为准：
		 * 未改配置 = 自动续跑<b>开启</b>；单测/非 Spring 装配（只 new 本类）= 关闭。
		 * 判别方法：看 {@code app.ai.resume.on-startup} 是否被显式配置，而不是看本字段初值。
		 *
		 * <p>打开后仍<b>只</b>续跑"已有结论"的条目（{@code APPROVED/EXECUTED/REJECTED/TIMEOUT/}
		 * {@code FRONTEND_RESULT/FRONTEND_CANCELLED/REJECTED_ARGUMENTS/BLOCKED}）—— 仍 {@code PENDING}
		 * （人工/前端未给结论）的一律跳过，因此不会绕开人审。
		 */
		private boolean onStartup = false;

		/** 启动扫描的延迟（等 Web 容器与 Redis 连接就绪，避免与启动路径抢资源）。 */
		private Duration startupDelay = Duration.ofSeconds(8);

		/**
		 * 僵尸判定：状态 {@code RUNNING} 且<b>最后一条非心跳业务帧</b>超过本值 ⇒ 判为僵尸轮并接管（N3）。
		 *
		 * <p>
		 * <b>R1-09：必须大于"配置允许的合法静默"</b> —— 后端工具执行上限
		 * {@code app.ai.tool.exec-timeout}（缺省 120s）与上游事件间静默上限
		 * {@code app.ai.resilience.inter-event-timeout}（缺省 90s）。阈值比它们短时，正在跑长工具 /
		 * 慢吐字的活轮会被跨实例判死并接管（同一轮执行两遍）。出厂值 = 180s
		 * （max(120s, 90s) + {@code zombie-margin} 60s），并由 {@link #verifyZombieAge()} 在启动期
		 * 强制校验 {@code >= max(exec, inter) + margin}。
		 *
		 * <p>
		 * 判据只看业务活动（{@link com.example.configmgr.ai.run.RunStore#lastActivityAtMs}）：
		 * 心跳只证明进程还活着，不能把卡死的轮永远养年轻。
		 */
		private Duration zombieAge = Duration.ofSeconds(180);

		/**
		 * 僵尸阈值相对"配置允许的合法静默"必须留出的余量（缺省 60s）。
		 *
		 * <p>
		 * 启动校验要求 {@code zombie-age >= max(tool.exec-timeout, resilience.inter-event-timeout) + 本值}：
		 * 只做到"大于上限"仍可能在工具刚跑满上限、或上游静默刚到线时判死（边界抖动、调度延迟、
		 * 跨节点时钟偏差都会吃掉那几毫秒）。余量把"合法静默的上界"与"判死阈值"真正分开。
		 *
		 * <p>
		 * 与 {@code zombie-age} 一样由 {@link #verifyZombieAge()} 在启动期校验；负值即配置错误。
		 */
		private Duration zombieMargin = Duration.ofSeconds(60);

		/** 单次启动扫描最多自动续跑多少轮（防止启动瞬间把挂起池占满）。 */
		private int maxOnStartup = 10;

		/**
		 * <b>运行期低频死轮扫描</b>（批次8B / Task 12 Step 3）的开关。
		 *
		 * <p>
		 * 启动扫描每实例只跑一拍；此后"进程死在模型调用中间"、"决策已落库但派发窗口整段错过"、
		 * "重挂自愈那一下 Redis 抖了"都会留下无人推进的 {@code RUNNING}/{@code SUSPENDED} 轮到下一次重启。
		 * 打开后按 {@link #runtimeSweepInterval} 周期把<b>无驾驶方</b>的那些轮次交给与启动扫描同一条
		 * 续跑路径（判据见 {@code StartupResumeRunner#sweep()}：候选 = {@code listRuns()}，
		 * 活/死 = {@code RunLiveness.isDead}，窗口 = {@link #zombieAge}）。
		 *
		 * <p>
		 * <b>缺省不一致是刻意的（同 {@link #onStartup} 的纪律）</b>：代码缺省 {@code false}
		 * （非 Spring 装配/单测不起定时器），出厂 {@code application.yml} 显式 {@code true}；
		 * 单测 {@code src/test/resources/application.properties} 显式关掉，避免周期线程跨测试类续跑
		 * 污染倒排录制的归因窗口（{@code SessionPurgeRetryRunner} 同一处置）。关掉只停"按周期自动扫"，
		 * 同步入口 {@code StartupResumeRunner#sweep()} 仍可直接驱动。
		 */
		private boolean runtimeSweepEnabled = false;

		/**
		 * 运行期死轮扫描的周期（低频，缺省 2 分钟）。
		 *
		 * <p>
		 * <b>它不参与判死</b>：判死窗口永远是 {@link #zombieAge}（180s，由 {@link #verifyZombieAge()}
		 * 在启动期守着，不因本键缩短）。本键只决定"多久看一眼"，因此把间隔配大只增长"死轮无人接管"的
		 * 最长时延（≈ 间隔 + 僵尸窗口），不会把活轮判死。非正值不注册定时器（只 ERROR 留痕）。
		 */
		private Duration runtimeSweepInterval = Duration.ofMinutes(2);

		/**
		 * 续跑触发池（T3-6a / A9 的"轻量执行器"）工作线程数。
		 *
		 * <p>承载的动作很短：一次 {@code ResumeService.resume} 调用（读快照/台账/待决 + 重建历史 +
		 * 转投挂起池）—— 它<b>不</b>跑模型、不阻塞等外部输入，故 2 条线程足够（同一时刻通常只有
		 * 一两次外部输入到来）。<b>不得</b>并入挂起池：{@code resume} 内部还要向挂起池提交一次，
		 * 同池提交即嵌套占用（R4/A9）。
		 */
		private int triggerPoolSize = 2;

		/**
		 * 续跑触发池的任务队列容量（有界）。满即 {@code AbortPolicy} 拒绝 ⇒ 调用方回
		 * {@code EXECUTOR_SATURATED}（外部输入本身已落库成功，绝不因此报错）。
		 */
		private int triggerQueueCapacity = 16;
	}

	@Data
	public static class Session {

		private Lock lock = new Lock();

		@Data
		public static class Lock {

			/**
			 * 会话锁的有效期（缺省 10m）。
			 *
			 * <p>
			 * <b>语义（超时面治理批① 第 8③ 项订正）</b>：这是<b>互斥窗口的上界</b>，不是"一轮的预算上限"。
			 * 早先的自述写作"TTL = 轮上限（ADR-5）"，容易被读成"等于
			 * {@code app.ai.resilience.total-budget} 300s" —— 两者不是一回事：
			 * <ul>
			 * <li>一轮的<b>机器预算</b>是 {@code total-budget}（300s，含流式/工具/重试，扣除挂起等待）；</li>
			 * <li>锁 TTL 约束的是"<b>持锁进程死了以后，别人多久能接管这条会话</b>"：watchdog 每
			 * {@link Lock#getWatchdog()}（30s）续期，轮终态主动释放；进程崩溃时最多等一个 TTL
			 * 就有新实例能拿到锁，不会被一轮的长耗时（挂起等人可无限期）拖成"事实永锁"。</li>
			 * </ul>
			 * 故 TTL 与轮预算<b>无等式关系</b>；挂起等人期间由 ConfirmGate 的心跳续期承担"锁还活着"的证明。
			 */
			private Duration ttl = Duration.ofMinutes(10);

			/** watchdog 续期间隔（ADR-5 补记 CH-P4：锁须带续期）。 */
			private Duration watchdog = Duration.ofSeconds(30);
		}

		/** 会话销毁 pending 的自动重试闭环（{@code app.ai.session.purge-retry.*}）。 */
		private PurgeRetry purgeRetry = new PurgeRetry();

		/**
		 * 自动重试的<b>周期注册</b>开关（缺省 {@code true} = 生产原样）。
		 *
		 * <p>只管"是否按周期（30s）把 pending 收敛到 complete"，<b>不</b>碰启动期 SCAN 恢复
		 * （{@code ApplicationReadyEvent} 先 {@code recoverOrphans}，再由本键决定是否注册周期），
		 * 也不碰同步入口 {@code SessionPurgeRetryRunner#runOnce()}。
		 *
		 * <p>关闭的用途只有一类：单测（{@code src/test/resources/application.properties}）。周期线程在
		 * Spring 上下文缓存后跨测试类续跑，而倒排录制按"测试类墙钟窗口"归因（线程无关）⇒ 清理触达会被
		 * 记到邻类窗口，同 tree 两遍录制出现成对假边（nightly 40219310874，diff=58）。单测直接驱动
		 * {@code runOnce()} 即可覆盖同一批语义。
		 */
		@Data
		public static class PurgeRetry {

			private boolean periodicEnabled = true;
		}
	}

	/**
	 * SSE 扇出投递参数（T3-1 慢订阅者隔离）。
	 *
	 * <p>
	 * 容量联动（S2-8）：{@code 专用池 × 2 + 投递池 ≤ boundedElastic 容量}，纳入 §13.2 #2
	 * 压测（T3-5 场景 E：N 挂起 × M 慢订阅者争抢 boundedElastic 的最坏态）。
	 */
	@Data
	public static class Sse {

		/**
		 * 共享有界投递池的<b>工作线程数</b>（R3 语义：池"容量"即线程数）。
		 *
		 * <p>
		 * 每订阅者一条<b>实时段有界</b>队列（回放初始批豁免，闸按实时段计数判定）+ CAS drain
		 * 独占标志，一条慢连接至多占一个工作线程；
		 * 池任务队列满时提交被拒 ⇒ 摘除触发本次提交的订阅者（显式 complete，前端重挂补帧）。
		 *
		 * <p>
		 * <b>T3-5 压测定值（2026-10-09）：8</b>；依据
		 * {@code docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md} §S1.2/S5；判定等式为
		 * <b>不被饿死 ⟺ 慢连接数 &lt; 池容量</b>（{@code M == 池容量} 时全部工作线程被慢连接占满，
		 * 同轮健康订阅者含心跳一并停投）。原初值 4 的边界：同轮出现 ≥ 4 条慢连接即饿死全部
		 * 健康订阅者；8 在该矩阵（M ∈ {1,4}）全绿，且 20×2 + 8 = 48 ≤ 160 仍成立。
		 */
		private int deliveryPoolSize = 8;

		/**
		 * 每订阅者<b>实时</b>投递队列容量的缺省值（<b>单一事实源</b>）。
		 *
		 * <p>
		 * 写出器与注册表都引用本常量（S3-1 修复批：此前 256 同时写在本类与 {@code SseChatEmitter}
		 * 两处，改坏一处不必然变红）。R1 不变量：本值 &lt; 归档窗口
		 * {@code RunStore#EVENT_WINDOW}（只约束实时段，回放初始批豁免）。
		 *
		 * <p>
		 * <b>T3-5 压测定值（2026-10-09）：维持 256</b>；双向实测依据见
		 * {@code docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md} §S5.2（不误摘：3000 帧回放 backlog
		 * 在队 + 100 条实时心跳不摘除，第 257 条才摘；及时摘：真实 TCP 背压下慢客户端 414 ms 内摘除）。
		 */
		public static final int DEFAULT_DELIVERY_QUEUE_CAPACITY = 256;

		/**
		 * 每订阅者<b>实时</b>投递队列容量（缺省 {@link #DEFAULT_DELIVERY_QUEUE_CAPACITY}）。
		 *
		 * <p>
		 * R1 不变量：队列容量 &lt; 归档窗口 3000（只约束实时段）；回放初始批豁免本容量，
		 * 上界 = 归档窗口 {@code EVENT_WINDOW}。溢出即摘除（主动断连）+ complete。
		 * <b>判据口径（修复批第 2 轮）</b>：闸判「该订阅者队列中的<b>实时</b>帧条数」，
		 * <b>不</b>判队列绝对长度 —— 回放批与实时帧共用同一条队列（单队列 FIFO 混合序），
		 * 回放帧不计入本容量。
		 */
		private int deliveryQueueCapacity = DEFAULT_DELIVERY_QUEUE_CAPACITY;

		/**
		 * 单帧 SSE 写出的<b>超时预算</b>（包②：SSE 写侧超时预算；缺省
		 * {@link com.example.configmgr.common.sse.SseWriteBudget#DEFAULT_TIMEOUT_MILLIS}）。
		 *
		 * <p>
		 * 语义与选型见 {@link com.example.configmgr.common.sse.SseWriteBudget}：它约束的是
		 * <b>单帧写出</b>的时长（写出挪到弹性写出池，投递池工作线程最多等本预算），
		 * 而不是整条流的寿命（AI 通道的 emitter <b>不设传输层死线</b>：确认门/表单等
		 * 人工输入没有上限，{@code AiController} 用 {@code new SseEmitter(-1L)} 建流；
		 * 机器预算 {@code app.ai.resilience.total-budget} 仍由 {@code StreamWatchdog} 判）。
		 * 超预算即摘除该订阅者（主动断连，前端 onerror/onclose 带 lastSeq 重挂差量补发），
		 * 不静默丢帧（与 S2-1 溢出摘除同口径）。
		 *
		 * <p>
		 * <b>必须 &gt; 0</b>：非正值会被 {@code SseWriteBudget.on} 静默回落成缺省 15s，
		 * 故改由启动期 {@link AiProperties#verifySseWriteTimeout()} 拦下（与任务通道
		 * {@code app.task.sse.write-timeout} 同一 fail-fast 姿态）。
		 */
		private Duration writeTimeout = Duration.ofMillis(SseWriteBudget.DEFAULT_TIMEOUT_MILLIS);
	}

	/**
	 * <b>双通道结果预算的单一配置对象</b>（G5 双通道对齐批；{@code app.ai.result-budget.*}）。
	 *
	 * <h2>为什么收敛成一个对象</h2>
	 * 同一份工具结果有两个消费者：模型（{@code role:tool} 消息）与前端（SSE {@code tool_result} 帧 +
	 * Redis 台账）。两者的成本不同 —— 模型侧是<b>字符</b>（上下文窗口），前端侧是<b>单帧字节</b>
	 * （传输与渲染）—— 但阈值此前分居 {@code app.ai.tool-result}、{@code app.ai.frame} 与
	 * {@code AiController} 的类内常量 三处，各改各的：模型侧悄悄停在 8000，
	 * 前端却按"全文"承诺渲染，人机看到的是两份不同的事实。现在两侧阈值
	 * <b>独立可调、同源可查</b>，并由 {@code ToolResultLimiter} / {@code FrameSizeLimiter} 共用
	 * 同一套截断字段（{@code truncated/strategy/originalChars/returnedChars/notice}）。
	 *
	 * <h2>模型侧分档</h2>
	 * <ul>
	 * <li>{@code model.default-max-chars}（20000）：<b>具名工具</b>的默认档。业界常见 10k–25k token
	 * ≈ 4 万字符量级，8000 字符偏保守（一轮两次 {@code list_*} 就把窗口吃掉）；</li>
	 * <li>{@code model.per-tool.*}：逐工具覆盖（长文本类工具可以更小，摘要类可以更大）；</li>
	 * <li>{@code model.fallback-max-chars}（8000）：<b>拿不到工具名 / 未注册工具</b>的兜底档，
	 * 保持旧口径，避免"不知道是什么结果"时放大上下文风险。</li>
	 * </ul>
	 *
	 * <h2>0 的语义</h2>
	 * {@code model.max-rows / default-max-chars / fallback-max-chars} 取 0 表示<b>不限制</b>
	 * （沿用 DC-14 T2 的既有口径，压测对照要用）；负值启动失败。{@code ui.max-frame-bytes} 与
	 * {@code callback.max-chars} 必须 &gt; 0 —— 它们不是"性能参数"，而是"宁可截断也不产坏帧/坏链路"
	 * 的安全阀，配成 0 等于静默关闸。
	 */
	@Data
	public static class ResultBudget {

		private Model model = new Model();

		private Ui ui = new Ui();

		private Callback callback = new Callback();

		/** 模型侧（{@code role:tool} 回填）的预算与分档。 */
		@Data
		public static class Model {

			/** 回填模型的最大行数（0 = 不限；JSON 转义形态的 {@code \n} 也计行）。 */
			private int maxRows = 200;

			/** 具名工具的默认字符上限（0 = 不限；G5 由 8000 提到 20000）。 */
			private int defaultMaxChars = 20_000;

			/** 拿不到工具名 / 未注册工具的兜底字符上限（0 = 不限；保留旧的 8000 口径）。 */
			private int fallbackMaxChars = 8_000;

			/** 逐工具覆盖（工具名 → 字符上限；0 = 该工具不限）。 */
			private Map<String, Integer> perTool = new LinkedHashMap<>();
		}

		/** 前端/SSE 侧的单帧预算。 */
		@Data
		public static class Ui {

			/** 单帧 JSON 的 UTF-8 字节上限的<b>单一事实源</b>（缺省 64KB）。 */
			public static final int DEFAULT_MAX_FRAME_BYTES = 64 * 1024;

			/**
			 * {@code max-frame-bytes} 的<b>启动下限</b>（拆帧专项发现；1KiB）。
			 *
			 * <p>低于本值时：① 拆帧会把一条正常正文切成成百上千片（帧数爆炸、前端逐帧渲染成本失控，
			 * 事件日志窗口也会被碎片帧迅速吃满）；② 极小值（如 64B）连一个最小合法帧的 JSON 外壳
			 * 都装不下 ⇒ 正文被"清空"。两者都不是性能降级，而是运行面事实被配置面改写，故启动即失败。
			 *
			 * <p>拆帧口径（与 {@code SseChatEmitter}/{@code FrameSizeLimiter} 的实现约定）：
			 * <b>delta / reasoning 可分片</b>（按本值切分为多帧），<b>result 仍按字段级降采样</b>
			 * （不跨帧分片，保持既有 {@code truncated/strategy/...} 契约）。
			 */
			public static final int MIN_FRAME_BYTES = 1024;

			/**
			 * 单帧 JSON 的 UTF-8 字节上限（64KB）。
			 *
			 * <p>判定口径（A19）：<b>整帧 JSON 的 UTF-8 字节数</b>；超限时对 {@code text}/{@code result}
			 * 的值做<b>字段级</b>降采样（普通文本头部截断；JSON 整体标记）+ 统一截断字段，
			 * <b>严禁</b>帧字节级截断 —— 后者破坏 JSON，坏帧在回放读路径（{@code RunStore#events}）
			 * 被静默忽略，表现为 seq 洞。
			 *
			 * <p><b>拆帧口径（拆帧专项）</b>：{@code delta} / {@code reasoning} 类正文帧<b>可分片</b>
			 * （按本值切成多帧连续投递，帧型不变），{@code result} 仍<b>只</b>做字段级降采样
			 * （不做跨帧分片/引用句柄）。故本键是"分片粒度/降采样阈值"，下限
			 * {@link #MIN_FRAME_BYTES}（1KiB）—— 再小会把正文切碎或清空（见该常量注）。
			 */
			private int maxFrameBytes = DEFAULT_MAX_FRAME_BYTES;
		}

		/** 前端工具结果回灌端点（{@code POST /api/ai/frontend-tool-result}）的单条上限。 */
		@Data
		public static class Callback {

			/** 回灌载荷的字符上限（超限<b>截断 + 标记</b>，不 400 —— 拒绝回灌会让这一轮永久挂起）。 */
			private int maxChars = 8_000;
		}
	}


	/**
	 * 事件日志与轮次索引的容量治理（Redis 容量专项 A）。
	 *
	 * <h2>为什么需要它</h2>
	 * {@code ai:xevents:<runId>} 此前只有<b>条数</b>预算（{@code XADD MAXLEN ~ 6000}）：单帧上限 64KB
	 * ⇒ 单轮最坏 6000 × 64KB ≈ 384MB。部署用 {@code maxmemory-policy noeviction}（T3-9：AI 键
	 * 宁报错不丢帧），容量触顶时<b>所有</b> AI 写入（含别的 run 与会话记忆）一起失败 —— 一条病态
	 * 长轮可以拖垮整个 AI 面。{@code ai:runs} 索引也没有 TTL，只增不减。
	 *
	 * <h2>口径（保守等效，不引入新协议）</h2>
	 * Redis 5.0 / Cluster 都<b>没有</b>按字节裁剪 Stream（{@code XTRIM} 只按条数/ID），真按字节治理
	 * 要额外协议（{@code MEMORY USAGE} 采样 + 动态裁剪，且采样本身是 O(N) 或近似）。本批改用
	 * <b>可证的等效上界</b>：每条落档记录 ≤ 本轮观察到的最大帧载荷 + 固定开销，
	 * 于是"有效窗口 = clamp(预算 ÷ 每条上界, 下限, 条数窗口)"即可保证"窗口 × 每条上界 ≤ 预算"。
	 * 观察值是<b>本轮实际见过</b>的最大载荷（不是配置的 64KB 上界），所以正常小帧轮次仍保留
	 * 6000 条回放深度，只有真出现大帧的轮次才收窄。
	 *
	 * <p>取舍（如实登记）：① 这是<b>上界</b>而不是实测字节，Redis 自身的分配器/碎片/keyspace 开销
	 * 不在内 ⇒ 预算应显著小于 maxmemory；② 跨实例不共享观察值 —— 另一个实例写过大帧时，本实例
	 * 仍按自己的窗口写，<b>全局上界退化为"各实例观察之和"</b>（要严格全局界需把观察值落共享键 =
	 * 每帧多一次读写 + 新键族，本批按最小实现不做；多实例同时写同一个 run 的概率极低）；
	 * ③ 收窄会让"能补回多远"变小 —— 读侧对"游标早于幸存窗口"单独计数
	 * （{@code c1.streamReplayGap}），不伪装成"没有新帧"。
	 */
	@Data
	public static class Stream {

		/**
		 * 单轮事件日志的<b>字节软上限</b>（默认 64MiB）。
		 *
		 * <p>取值依据：部署 {@code maxmemory} ≈ 16GiB（技术方案 §9）；最坏态 ≈ 挂起池 20 轮
		 * 同时写满 ⇒ 20 × 64MiB ≈ 1.25GiB ≈ 8%，给会话记忆/台账/业务键留出余量。
		 * <b>{@code 0} = 关闭字节治理</b>（只按条数窗口，回到本批之前的语义，供压测对照）。
		 *
		 * <p>单轮最坏占用因此从 384MB 降到本值；下限窗口 {@code RedisFrameStore#MIN_WINDOW} 保证
		 * 收窄后仍有可回放窗口（预算连下限都撑不住时计数 + WARN，见 {@code c1.streamByteFloor}）。
		 */
		private long byteBudget = 64L * 1024 * 1024;

		/**
		 * {@code ai:runs} 索引<b>机会式惰性清理</b>的最小间隔（默认 10m；{@code 0} = 关闭）。
		 *
		 * <p>索引是 SET，无法按成员过期；整键 {@code EXPIRE} 会在长挂起期把<b>活跃</b>轮的发现面
		 * 一起删掉（挂起等人没有上限），故只做"快照键已不存在"的成员剔除。
		 * 清理一次 = 一次 {@code SMEMBERS} + 逐成员 {@code EXISTS}（O(索引大小)），因此必须节流：
		 * 否则每次 {@code /chat} 的 {@code create} 都要扫全表。启动扫描（{@code allRunIds}）不受本键约束。
		 */
		private Duration indexPruneInterval = Duration.ofMinutes(10);

	}

	/**
	 * 租约（fencing）设施参数（B23L1 批②a；设计稿 §2.2）。
	 *
	 * <p>与本专项的 B3（续跑权 fencing）和 L1（目标资源互斥）共用同一组原语：run 租约的 epoch 由
	 * <b>与租约键同槽</b>的 per-run 计数器（{@code ai:lease:epoch:{run:<runId>}}）在抢租脚本内
	 * {@code INCR+SET} 发放（与 fence 同面同寿；号与占用必然同序，评审 I-1）；资源锁仍用旧的全局
	 * {@code ai:lease:epoch}，只作 token 编号、不参与 fencing。租约键带 TTL 并由持有者续租。
	 *
	 * <p>取值口径：TTL 30s = "持租进程卡顿/死亡后，别的实例多快能接管" 的上界；续租 10s = TTL/3，
	 * 容忍两次连续续租失败（网络抖动）而不丢租约；重试 35s = TTL + 5s 余量，抢租失败的一方按它
	 * 退避重试（批②b 的续跑触发路径消费，本批只建键与设施）。{@code resource-ttl} 60s 是
	 * <b>另一条线</b>（L1 资源互斥，批③a），依据见字段注。
	 */
	@Data
	public static class Lease {

		/** 租约有效期（秒级冗余：续租间隔的三倍）。 */
		private Duration ttl = Duration.ofSeconds(30);

		/** 持有者的续租间隔。 */
		private Duration renewInterval = Duration.ofSeconds(10);

		/** 抢租失败后的退避重试间隔（TTL + 5s 余量）。 */
		private Duration retryDelay = Duration.ofSeconds(35);

		/**
		 * 资源互斥锁（L1：{@code ai:lock:task:<taskId>} / {@code ai:lock:job:<taskId>:<JOBTYPE>}，
		 * 键名见 {@link com.example.configmgr.ai.run.LeaseKeys}）的有效期（B23L1 批③a / 裁决 12）。
		 *
		 * <p>为什么是 60s 而不是跟续跑权一样 30s：这把锁包住的是一次<b>业务动作</b>（导入/发布/导出的
		 * 发起与收尾），最长耗时可到分钟级；但如果按动作时长设 TTL，持锁进程崩溃后别的实例要白等
		 * 整段动作时长才能接上。60s = 两倍续跑权 TTL，<b>不续租，TTL 仅作临界区上界</b>
		 * （B23L1 批③b 复审 I-1：动作真超过上界时宁可让锁按 TTL 放开，也不把"事实永锁"续出来；
		 * 崩溃后的白等窗因此恒不超过一分钟）。
		 *
		 * <p>与 {@link #ttl} 分开是刻意的：续跑权的 TTL 约束"多快能接管一轮"，资源锁的 TTL 约束
		 * "多快能重试同一动作"，两者的取值依据不同，不共用一键。
		 */
		private Duration resourceTtl = Duration.ofSeconds(60);
	}

	/**
	 * 资源互斥开关（B23L1 批③a / 裁决 13）：{@code app.ai.lock.enabled}。
	 *
	 * <p>默认 <b>true</b>。关掉的唯一正当场景是"确定只有一个实例、且要排除锁设施本身的干扰"
	 * （单实例冒烟/压测对照）；<b>不是</b>故障开关 —— 多实例下关掉它，任务级/作业级互斥整体失效
	 * （同一作业被两个实例同时发起）。因此它是显式配置项而不是"抢锁失败就降级"的兜底：
	 * 降级会让"锁坏了"与"锁关了"在日志里长得一样。
	 */
	@Data
	public static class Lock {

		/** 是否启用资源互斥（{@code ai:lock:*}）。false ⇒ 使用方不抢锁、不释放（本层不续租，见 {@link Lease#getResourceTtl()}）。 */
		private boolean enabled = true;
	}

	/**
	 * 输入闸门（安全防护专项批①；{@code 调研-AI输入输出安全防护业界惯例-2026-10-21.md} §5.1 #1
	 * + {@code 调研-AI上下文数据最小化业界惯例-2026-10-21.md} R5）。
	 *
	 * <p>
	 * <b>只约束用户输入的那一条 message</b>（不是整包请求体）：判据 = UTF-16 码元数，与前端
	 * {@code <textarea maxlength>} / JS {@code String#length} <b>同一把尺</b>，故中文不会因为
	 * UTF-8 三字节而被提前判死（8192 个汉字 = 24576 字节，仍须放行）。
	 *
	 * <p>
	 * 超限<b>拒绝</b>而非截断（用户裁决 A1 建议 400）：截断会让模型基于半句话下结论。
	 */
	@Data
	public static class Input {

		/** 单条 message 的字符上限的<b>单一事实源</b>（前端 {@code MESSAGE_MAX_CHARS} 与它同值）。 */
		public static final int DEFAULT_MAX_CHARS = 8192;

		/** 单条 message 的字符上限（8192）。 */
		private int maxChars = DEFAULT_MAX_CHARS;
	}

	/**
	 * 限流（安全防护专项批① §5.1 #3）：{@code /api/ai/chat} 的双维度滑动窗口。
	 *
	 * <p>
	 * 计数落在 Redis（{@code ai:rate:session:*} / {@code ai:rate:ip:*}），用 B23L1 已有的 Redis
	 * 原语（单键 Lua 原子），<b>不要</b>另起进程内计数：多实例下进程内计数每实例各算一份，等于把
	 * 额度放大 N 倍（§6.4）。
	 *
	 * <p>
	 * 取值口径（用户裁决 A5 草值）：会话 20 次/分 —— 本仓单用户、一轮对话动辄数秒，
	 * 20 次/分已远超正常手速，只拦"脚本连发"；IP 60 次/分 —— 兜住"换 sessionId 绕会话额度"，
	 * 也是 E2E（21 次 {@code /chat} 全程）不会被误伤的量级。
	 */
	@Data
	public static class Limit {

		/** 同一 sessionId 每窗口允许的对话请求数。 */
		private int sessionPerMinute = 20;

		/** 同一来源 IP 每窗口允许的对话请求数。 */
		private int ipPerMinute = 60;

		/** 窗口长度（滑动窗口：计数按时间戳落 ZSET，窗口滑过即回收额度）。 */
		private Duration window = Duration.ofMinutes(1);
	}

	/**
	 * 上下文计量与压缩（上下文统计与压缩专项批①；设计稿 §3.7 的 11 个键）。
	 *
	 * <p>
	 * <b>键名纪律（NUMSPEC）</b>：本段每个数字都在 {@code docs/evidence/补档-NUMSPEC-清单总账.md}
	 * 逐行登记了依据 —— "为什么是 128000 / 13000 / 70-80-95 / 60"这些结论不能只活在代码注释里。
	 *
	 * <p>
	 * <b>启动期校验</b>见 {@link AiProperties#verifyContext()}：与 limit/SSE 两段同一姿态 ——
	 * 0/负值不是"性能降级"而是语义被静默改写（例如 {@code reserve >= limit} 会让分母变成 1，
	 * 任何一轮都判"已满"、每轮都压缩）。
	 *
	 * <p>
	 * <b>已删键（AR-11）</b>：{@code app.ai.context.summary-model} 的<b>绑定与出厂 yml 空值行都已删除</b>
	 * —— 压缩只使用本轮同一供应商、同一模型（{@code ProviderRuntime}），<b>不存在</b>独立摘要模型分支，
	 * 也不会再加回来。外部配置（部署 yml / 环境变量 / 命令行）仍写该键时，由
	 * {@link AiRetiredKeysWarner} 给<b>一次</b>退役 WARN（经 {@code Environment} 检测，不依赖本类绑定），
	 * 不静默吞掉。
	 */
	@Data
	public static class Context {

		/**
		 * 上下文上限（token）的<b>过渡键</b>（默认 128000；<b>自 L-89 起已弃用</b>）。
		 *
		 * <p>取值依据：本仓当前默认模型（deepseek 系）的 128K 窗口；模型配置专项的
		 * {@code ai_model.max_context} 落地后由它覆盖（唯一消费点 = {@code ContextLimitResolver}）。
		 * {@code 0} = 主动声明"未知"（降级态：界面只显示已用、不显示百分比；压缩不做比例判定）。
		 *
		 * <p><b>现状（L-89 收口）</b>：唯一真源 = {@code ai_model.max_context}；本键<b>只保留为启动期回退</b>
		 * —— 模型行未声明 {@code max_context}（或调用方没有模型身份）时，由
		 * {@code ContextLimitResolver#resolve} 回落到本值。<b>压缩触发判定不得再直接读本键</b>
		 * （{@code ContextTrigger#decide} 的分母由调用方从 resolver 注入）：否则本键与 DB 值漂移时，
		 * "显示的百分比"与"触发百分比"会各用一套分母。启动日志由
		 * {@code ContextLimitResolver#logStartupDeprecation()} 显式标注 deprecation。
		 */
		private int limitTokens = 128000;

		/**
		 * 预留缓冲（默认 13000 token）。
		 *
		 * <p>依据 = 调研 §① Claude Code 的 13K 预留缓冲；覆盖 {@code max-tokens 4096} 输出空间
		 * + 一轮工具回环余量。比例阈值对 {@code limit − reserve} 取比 ⇒ 触发线不会把输出空间吃掉
		 * （128K 下 L3@95% 仍剩 5.75K &gt; 4096）。
		 *
		 * <p><b>C13：实际生效值是"按窗口收口后"的值</b> —— 判定侧一律经
		 * {@code ContextTrigger#effectiveReserve(limit, reserve)} 取 {@code min(本值, 窗口/2)}。
		 * 理由：本值是<b>绝对额度</b>，在窗口只比它大一点的小模型上（如 15000）会把分母压成 2000，
		 * 任何请求都判成越线（退化分母）。128K 下收口前后逐字相同（{@code min(13000, 64000)}）。
		 */
		private int reserveTokens = 13000;

		/** L1（工具结果清除）触发线（对 {@code limit − reserve} 的百分比）。 */
		private int l1Percent = 70;

		/** L2（滑窗 + 递归摘要）触发线（批③ 已消费）。 */
		private int l2Percent = 80;

		/** L3（硬截断兜底）触发线（批③ 已消费）。 */
		private int l3Percent = 95;

		/**
		 * 滞回线（默认 60）：压缩完成后<b>可压缩历史</b>占 {@code limit − 有效预留} 的比例 &lt; 60%
		 * 才允许再次触发（C13：先扣掉不可压缩的 system + tools，且分母与触发同一口径 ——
		 * 否则大固定前缀会把 L2/L3 永久锁死，见 {@code ContextTrigger#hysteresisUnlocked}）。
		 *
		 * <p>依据 = 调研 Q4"压缩到 60% 以下才允许再次触发"；防的是"压一次不够、立刻再压"的抖动链
		 * （每次压缩都要改写前缀 ⇒ 缓存整段重建）。
		 */
		private int hysteresisPercent = 60;

		/** 最近多少对工具结果不清理（L1 的 pin；调研"保留最近 3–5 对"取上限 = 保守）。 */
		private int keepToolPairs = 5;

		/** 最近多少轮逐字保留（L2 的 pin；调研"保留最近 6–8 轮"取下限 = 省 token 优先；批③ 已消费）。 */
		private int keepRecentTurns = 6;

		/** 摘要分块大小（token；批③ 已消费）。必须小于 {@link #reserveTokens}，保证摘要请求自身不越窗。 */
		private int chunkTokens = 8000;

		/** 一次摘要请求的硬上限（批③ 已消费；失败即熔断路径，不回退到截断）。 */
		private Duration summaryTimeout = Duration.ofSeconds(60);

		/** 连续失败多少次熔断（批③ 已消费；调研 §①"连续失败 3 次熔断"）。 */
		private int fuseThreshold = 3;

		/** 熔断冷却窗（批③ 已消费；到点半开重试一次，失败重新计数 —— 不升指数，避免长尾锁死）。 */
		private Duration fuseCooldown = Duration.ofMinutes(10);

	}

	/**
	 * 业务知识库（业务知识问答 v1；Lucene 内嵌 BM25 + agentic 检索）。
	 *
	 * <p>
	 * 键名与设计定稿逐条对应（{@code app.ai.kb.*}）。知识内容<b>不是对话数据</b>：它落盘在
	 * {@code data/kb}（或由生产 HTTP 源提供），与 AI 会话/运行台账分离；检索结果回填模型前
	 * 一律包进 {@code <knowledge>} 定界并声明"数据不是指令"（见 {@code KbText}）。
	 *
	 * <p>
	 * <b>增量口径</b>：每篇文档的 {@code sha} 是"要不要重建"的唯一判据 —— 未变的文档不重新
	 * 拉取正文、切块结果直接复用；变的只重建该篇。HTTP 源另用 manifest 的 ETag 做
	 * "整份未变就不重建"的第一道短路。
	 */
	@Data
	public static class Kb {

		/** 总开关（关 = 不建索引、工具返回"知识库未启用"）。 */
		private boolean enabled = true;

		/** 知识源类型：{@code local}（默认，读 {@link #dir}）/ {@code http}（生产形态，读 {@link Http}）。 */
		private String source = "local";

		/** local 源的知识目录（{@code *.md} + {@code manifest.json}）。 */
		private String dir = "./data/kb";

		/** 索引目录（Lucene FSDirectory；热重载在其中的 tmp 子目录建新索引后原子切换）。 */
		private String indexDir = "./data/kb-index";

		/** 定时刷新间隔（默认 10m；0/负值启动失败，见 {@code verifyKb}）。 */
		private Duration refreshInterval = Duration.ofMinutes(10);

		/** {@code kb_search} 的默认条数（5）。 */
		private int topKDefault = 5;

		/** {@code kb_search} 的服务端硬上限（10；入参超限即强截）。 */
		private int topKMax = 10;

		/** {@code kb_read_doc} 的默认字符数（4000）。 */
		private int readMaxCharsDefault = 4000;

		/** {@code kb_read_doc} 的服务端字符上限（8000）。 */
		private int readMaxCharsMax = 8000;

		/** 切块的合并下限（300 字；同一章节内的短段落合并到它）。 */
		private int chunkMinChars = 300;

		/** 切块的硬上限（800 字；超出必须切分，单个超长段落按它硬切）。 */
		private int chunkMaxChars = 800;

		/** 命中档 high 的相对阈值（对本次最高分取比，0.7）：原始 BM25 分不给模型，只给档位。 */
		private double matchHighRatio = 0.7;

		/** 命中档 medium 的相对阈值（0.4；低于它为 low）。 */
		private double matchMediumRatio = 0.4;

		private Http http = new Http();

		private Cache cache = new Cache();

		/**
		 * Redis 缓存层（追加 3；手写 cache-aside，不用 Spring Cache）。
		 *
		 * <p>缓存是<b>全局知识域</b>（会话无关）：本地源读盘后也写入缓存，索引一律从缓存重建，
		 * 保证多实例看到同一份字节；缓存键不设 TTL（刷新权属显式刷新，版本号兜底）。
		 */
		@Data
		public static class Cache {

			/** 是否启用缓存层（Redis 不可用时自动降级直连源，见 {@code CachedKbSource}）。 */
			private boolean enabled = true;

			/** 刷新权锁的 TTL（{@code SET NX PX}，默认 30s；不续租，过期即让位）。 */
			private Duration lockTtl = Duration.ofSeconds(30);

			/** 版本号轮询间隔（默认 30s）：其它实例 INCR 后，本实例最迟一个轮询周期发现并重建。 */
			private Duration versionPollInterval = Duration.ofSeconds(30);
		}

		/** 生产 HTTP 源（{@code app.ai.kb.http.*}）。 */
		@Data
		public static class Http {

			/** 知识服务基地址（如 {@code https://kb.internal/api/kb}；manifest 与文档都相对它解析）。 */
			private String baseUrl = "";

			/** 连接超时（默认 5s）。 */
			private Duration connectTimeout = Duration.ofSeconds(5);

			/** 读超时（默认 10s）。 */
			private Duration readTimeout = Duration.ofSeconds(10);

			/**
			 * 鉴权头的环境变量名（默认 {@code KB_HTTP_AUTH}）：变量值整段作为 {@code Authorization}
			 * 头的值（如 {@code Bearer xxxxx}）。
			 *
			 * <p><b>只从进程环境读取</b>：不写进配置文件、不落库、不进日志/错误响应
			 * （用例锁定"鉴权头不出现日志/错误响应"）。变量缺失 = 不带鉴权头（由服务端决定是否 401）。
			 */
			private String authEnv = "KB_HTTP_AUTH";
		}
	}

	/**
	 * 思考（{@code reasoning_content}）的展示与持久化（{@code app.ai.reasoning.*}）。
	 *
	 * <p>两键互不替代：展示关掉只影响"发不发 reasoning 帧 / 历史透不透 {@code reasoningContent}"，
	 * 不影响落进 assistant 消息 metadata 的思考全文（{@code persist-content=true} 时）——
	 * 下一轮的上下文回填（{@code ReasoningAwareOpenAiChatModel}）读的正是那份 metadata。
	 */
	@Data
	public static class Reasoning {

		/**
		 * 是否展示思考（默认 <b>false</b>）：{@code false} = {@code consume} 不发
		 * {@code reasoningDelta}、{@code /history} 不透出 {@code reasoningContent}；
		 * 思考仍累计供记忆回填。需要思考帧的用例显式打开本键。
		 */
		private boolean displayEnabled = false;

		/**
		 * 是否把思考全文随 assistant 消息写进记忆（默认 <b>true</b>）。
		 *
		 * <p>与 {@link #displayEnabled} 解耦：展示关掉时仍要回填（"想过"是模型下一轮的输入事实）。
		 */
		private boolean persistContent = true;

	}

}
