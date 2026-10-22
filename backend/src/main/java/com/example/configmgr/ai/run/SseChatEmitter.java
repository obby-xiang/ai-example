package com.example.configmgr.ai.run;

import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.TruncationContract;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单轮对话的 SSE 帧写出器（S4.2 §3 时序的终端契约）。
 *
 * <p>
 * 与第一棒相比的两点变化（M3 挂起态外置 + ADR-5 reattach 的必然结果）：
 * <ol>
 * <li><b>多订阅者扇出</b>：一帧发给本轮全部订阅者 —— 首轮客户端断开重连后，
 * {@code GET /api/ai/events/{runId}} 可以挂到同一个写出器上继续收流
 * （ADR-5 补记：409 携 runId + reattach 重挂进行中轮的流）；</li>
 * <li><b>每帧同时外置</b>：帧原文进事件日志（B23L1 批① 起 = {@link FrameStore}，生产实现是
 * Redis Stream {@code ai:xevents:<runId>}；兼容期还会双读旧的 {@code ai:events:<runId>} LIST），
 * 于是"进程在挂起期死亡"不再连 SSE 时序一起丢，重挂时先回放外置帧再续接实时帧。</li>
 * </ol>
 *
 * <p>
 * <b>B23L1 批①（2026-10-21）多实例</b>：出帧 = 取号（{@link FrameStore#nextSeq}，生产为
 * {@code INCR ai:seq:<runId>}）→ 截断 → 落档（{@link FrameStore#append}）→
 * {@code PUBLISH ai:wake:<runId>（仅上界 seq）} → 本地扇出（同实例快路径）。
 * 其它实例的连接由 {@link FrameWakeListener} 收到唤醒后从事件日志补差量，
 * 重复帧按 {@link #lastFanoutSeq} 去重；连接层因此无状态 —— 任何实例都能 serve 任意 run 的 reattach。
 *
 * <p>
 * 帧类型：{@code start}（首包）→ 若干 {@code delta}/{@code tool_*}/
 * {@code retry}/{@code confirm_*}/{@code frontend_tool_*}/{@code heartbeat}/{@code suspended}/
 * {@code context}（上下文专项批①：压缩事件，仅在真发生压缩时出现）→
 * 恰好一个 {@code done}（正常完成或取消：{@code cancelled=true}/{@code false}）或 {@code error} 终帧。帧体是 JSON 对象且带 {@code type} 字段
 * （无名 SSE 事件 + 帧内 type），与基座 SSE 形态一致，前端不必改用 addEventListener。
 *
 * <p>
 * <b>B1（2026-10-20）挂起即断</b>：确认卡/表单卡请求帧（{@code confirm_request} /
 * {@code frontend_tool_request}）发出后，{@code ConfirmGate#await} 调
 * {@link #completeSubscriptions()} 完成<b>本轮当前全部订阅</b> —— 连接不再挂住等人；
 * 轮次仍在跑（线程驻留等待决策），后续帧照旧取号落档，决策后前端带 {@code ?lastSeq}
 * 重连做差量补帧。为此 {@code delta} 一并入档（{@code heartbeat} 仍不入档）。
 *
 * <p>
 * <b>序号口径（S5c-1 + T3-2）</b>：业务帧带单调 {@code seq}（自 1 起、跨进程续号）。
 * 心跳帧<b>不占</b>业务序号，只在独立空间里带 {@code heartbeatSeq}。T3-2 起每个业务帧
 * 发放序号时同步 {@code SET ai:seq:<runId>}（发放即登记，TTL 与归档同寿）—— 归档写入可能
 * 部分失败（{@code ai:seq} 已推进而归档落后），续号候选 = max(归档末帧, {@code ai:seq}) + 1。
 * <b>出帧串行（S5c-3）</b>：取号、落归档、入队在同一 {@code emitLock} 内完成，
 * 多线程出帧的到达顺序与 seq 顺序一致。
 *
 * <p>
 * <b>帧时间戳口径（T4-3 后半）</b>：每帧在 {@link #frame} 构造时就带 {@code atMs}
 * （服务端钟 epoch 毫秒），因此<b>实时投递帧与归档帧都含</b> {@code atMs}；<b>有时限</b>的挂起类帧
 * （即时执行类前端工具的 {@code frontend_tool_request}）的 {@code expiresAt} 与同一帧的
 * {@code atMs} 同钟单点取值，二者之差恒等于 {@code timeoutSeconds}
 * —— 前端据此按"服务端剩余时长"折算本地倒计时，免疫客户端钟漂移。
 * 确认卡与 {@code generative_form} 自 2026-10-20 起<b>没有到期时刻</b>（帧里不带这两个字段）。
 *
 * <h2>T3-1 慢订阅者隔离（R1 裁决 + 施工注记 R2/R3）</h2>
 * {@code emitLock} 内只保留：取号、落归档、{@code touchActivity}（S3-9：必须留锁内同步，
 * 否则僵尸判定输入随投递延迟漂移）、<b>帧入各订阅者队列</b>。网络写出全部走
 * <b>共享有界投递池</b>（工作线程数 T3-5 定值 8，{@code app.ai.sse.delivery-pool-size} 可配，
 * T3-5 压测定值）：
 * <ul>
 * <li>每订阅者一条队列（实时段容量 T3-5 定值 256，{@code app.ai.sse.delivery-queue-capacity}
 * 可配；生产者在 {@code emitLock} 内串行入队、单消费者摘除，故容量判定精确）
 * + 一个 CAS drain 独占标志：入队后 CAS 抢占 drain 权，抢不到说明在跑的 drain 会带走新帧
 * —— 单订阅者 FIFO 且一条慢连接至多占一个工作线程，慢订阅者的 TCP 背压不再阻塞整轮
 * （含心跳与同轮其他订阅者）；</li>
 * <li><b>drain 收尾顺序固定（R2）</b>：先清 CAS 独占标志、再复查队列非空则重新 CAS 抢回
 * —— 倒序会丢"判空 → 他线程入队 CAS 失败 → 清标志退出"的 drain 窗口；</li>
 * <li><b>池拒绝语义（R3）</b>：池任务提交被拒（池队列满）时摘除触发本次提交的订阅者
 * （显式 {@code emitter.complete()}，前端 onerror/onclose 后带 lastSeq 重挂补帧）；</li>
 * <li><b>溢出摘除 = 主动断连（S2-1）</b>：实时队列满即摘除并 complete，不静默丢帧
 * （前端重挂走差量补发，seq 无洞）；</li>
 * <li><b>实时闸按实时段计数（S1 修复批第 2 轮，红队击破后修）</b>：容量闸判
 * {@code Subscription#liveQueued}（该订阅者<b>队列中的实时帧条数</b>），<b>不再</b>判队列绝对长度
 * —— 回放批与实时帧共用<b>同一条</b>队列（单队列 FIFO 混合序，seq 连续性语义依赖），
 * 若按绝对长度判定，慢重挂者回放 3000 帧入队后，排空期间任一实时帧（挂起期每 2s 心跳）
 * 就会把队列里的回放 backlog 误判成实时溢出而摘除一个健康订阅者；重挂后剩余回放 ≥ 容量
 * 再被摘除 ⇒ 确定性饥饿（与 R1 裁决"回放批豁免有界容量"直接相悖，红队探针已实测复现）。
 * 回放帧入队经 {@link QueuedFrame} 标记为 {@code live=false}，drain 弹出实时帧时递减计数；</li>
 * <li><b>生命周期回调统一清理（S3-1 修复批第 2 轮）</b>：{@code onCompletion/onTimeout/onError}
 * 与 {@code evict/completeOne} 同走 {@link #closeSubscription}（清队列 + 实时段计数归零 +
 * 复位 drain 独占标志 + 标记 closed）—— 否则连接超时/断开后队列里滞留的回放帧无人释放；</li>
 * <li><b>终态收尾（S1-1 修复批）</b>：{@link #complete()}（轮终态）先置终态标志<b>停止收新帧</b>，
 * 再让各订阅者把<b>已入队</b>的帧排空后才收尾 —— "队空才 complete" 直接复用 drain 的收尾分支
 * （与终态回放路径同一口径），异步 drain 因此不会在队列还留着尾帧（{@code done}/{@code error}/
 * {@code suspended}）时被 {@code emitter.complete()} 抢断；收尾不阻塞调用线程（生产调用点是
 * 轮终态线程的 {@code finally}，同步等排空会拖住轮次收尾）；</li>
 * <li><b>回放批豁免（R1 裁决）</b>：{@code replayAndAttach} 锁内收集回放帧（{@code LRANGE}
 * 只读，不做 IO 写）入队不受实时容量 256 约束，上界 = 归档窗口 {@code EVENT_WINDOW}
 * （3000，{@code RunStore#events} 天然裁剪）——"队列容量 &lt; 归档窗口"不变量只约束实时段。</li>
 * </ul>
 *
 * <h2>包②：SSE 写侧超时预算（{@link SseWriteBudget}）</h2>
 * T3-1 解决了"慢订阅者不阻塞整轮"，但没有解决"慢订阅者占住投递池线程" ——
 * {@code SseEmitter.send} 的阻塞式 socket 写，写侧超时只有容器兜底的一条：由
 * {@code server.tomcat.connection-timeout}（本配置 120s）派生（钉住版本 tomcat-embed-core
 * 10.1.54 的 {@code NioEndpoint#setSocketOptions} 会 {@code setWriteTimeout(getConnectionTimeout())}，
 * 字节码实测；判据与实测见 {@link SseWriteBudget}），是 15s 预算的 ≈8 倍宽且随连接配置漂移 ——
 * 于是 <b>M ≥ 池容量</b> 条"不读也不断开"的连接即可让全进程 SSE 停投，且在 120s 兜底到来前不自愈。
 * 本包补齐写侧预算：
 * <ul>
 * <li><b>写出移出投递池</b>：{@link #writeFrame} 把单帧 {@code send} 提到弹性写出池，
 * 本线程（投递池工作线程）最多等 {@code app.ai.sse.write-timeout}（缺省 15s，取值论证见
 * {@link SseWriteBudget#DEFAULT_TIMEOUT_MILLIS}）⇒ 投递池不再因慢连接而饱和；</li>
 * <li><b>超时处置 = 主动断连</b>：超预算即 {@link #evictOnWriteTimeout}（摘表 + 清队列 + WARN + 计数），
 * 不静默丢帧 —— 前端 reattach 看门狗（{@code frontend/src/api/sse.ts} 的 onerror/onclose →
 * 带 lastSeq 重挂，ADR-5 通道）把断点之后的帧补回来；B1 起 delta 也入归档，故静默丢帧
 * 才是补不回来的永久缺失，"丢弃+继续投"因此被排除；</li>
 * <li><b>收尾不跨线程 complete</b>：{@code send}/{@code complete} 同为 {@code synchronized}，
 * 写卡住时从别的线程 complete 只会一起挂住 —— 写在飞期间的收尾请求由写出线程在 {@code send}
 * 返回后补做（{@link #completeOne} 的交接 + {@link Subscription#writeInFlight}）；</li>
 * <li><b>残留</b>：被卡住的那一次写仍占 1 个写出池线程（每条订阅者至多 1 个），最终由客户端断开、
 * 容器写超时（{@code connection-timeout}=120s）或单帧写预算先到者释放。AI 通道的 emitter
 * <b>不设传输层死线</b>（{@code new SseEmitter(-1L)}：确认门/表单等人工输入没有上限），
 * 因此"整条流寿命"不是写侧兜底 —— 机器预算 {@code app.ai.resilience.total-budget}
 * 由 {@code StreamWatchdog} 判（墙钟 − 挂起等待），不在本类。</li>
 * </ul>
 *
 * <p>
 * 写出失败（客户端断开）只记日志、摘除该订阅者，不打断运行线程；终帧与
 * {@code emitter.complete()} 由调用方（{@code AiController} / {@code ResumeService}）保证。
 */
@Slf4j
public class SseChatEmitter {

	/**
	 * T3-1：进程级共享有界投递池（{@link #configureSharedDeliveryPool} 用 AiProperties 装配一次）。
	 */
	private static volatile ThreadPoolExecutor sharedDeliveryPool;

	/**
	 * 写侧单帧预算的<b>实际值</b>（毫秒，装配时由 {@code app.ai.sse.write-timeout} 注入）。
	 *
	 * <p>超时面治理批① 第 3 项：{@code /api/ai/health} 的 {@code writeTimeoutMs} 必须读配置面的真实值，
	 * 不能恒报编译期缺省 15000（否则改配置后可观测面说谎）。未装配 ⇒ 缺省值。
	 */
	private static volatile long sharedWriteTimeoutMs = SseWriteBudget.DEFAULT_TIMEOUT_MILLIS;

	/** 投递池指标：累计摘除的订阅者数（溢出 / 写出失败 / 池拒绝共用，供 /api/ai/health）。 */
	private static final AtomicLong EVICTED_TOTAL = new AtomicLong();

	private final String runId;

	private final RunStore store;

	/**
	 * B23L1 批①：事件日志（取号 + 落档 + 按号回读）。
	 *
	 * <p>生产装配（{@link RunRegistry} 的 Spring 路径）恒为 {@link RedisFrameStore}；
	 * 非 Spring/单测路径由兼容构造器注入 {@code ai:events:} LIST 口径的实现（见构造器注释）。
	 */
	private final FrameStore frames;

	/** B23L1 批①：唤醒发布面（null = 不跨实例广播：单实例/单测路径）。 */
	private final FrameWakeBus wakeBus;

	/** B23L1 批①：唤醒订阅面（null = 不订阅；重挂仍可由事件日志回放补帧）。 */
	private final FrameWakeListener wakeListener;

	private final ObjectMapper objectMapper;

	/** T3-1：本写出器的投递执行器。生产 = 共享有界投递池；三参构造（测试/非 Spring）= 同步直执。 */
	private final Executor deliveryExecutor;

	/**
	 * 包②：单帧写出的<b>超时预算</b>（{@link SseWriteBudget}）。
	 *
	 * <p>
	 * 生产装配（{@link RunRegistry}）给的是"弹性写出池 + {@code app.ai.sse.write-timeout}"；
	 * 未显式给出时回落 {@link SseWriteBudget#direct()}（调用线程内写出、无预算），
	 * 与修复前的行为逐字等价 —— 既有单测的确定性不受影响。
	 */
	private final SseWriteBudget writeBudget;

	/** T3-1：每订阅者实时队列容量（回放批豁免，见类注）。 */
	private final int queueCapacity;

	/**
	 * T3-10：单帧 JSON 的 UTF-8 字节上限
	 * （{@code app.ai.result-budget.ui.max-frame-bytes}，缺省 64KB）。
	 *
	 * <p>出帧路径在"取号 → 落档 → 入队"之前调用 {@link FrameSizeLimiter#apply}：
	 * 超限帧按<b>字段级</b>降采样并挂统一截断字段（{@code truncated/strategy/originalChars/
	 * returnedChars/notice}，{@code truncatedReason} 为兼容别名），
	 * 归档与投递拿到的是同一份截断后帧（重挂回放与实时流不会分叉）。
	 */
	private final int maxFrameBytes;

	/**
	 * G5：模型侧裁剪器（null = 不标注模型侧视图，帧序测试/非 Spring 路径）。
	 *
	 * <p>{@code tool_result} 帧的 {@code result} 仍是全文；这里只用它把"模型侧被裁到多少"
	 * 同步成帧上的同一套截断字段 —— 模型侧 {@code truncated=true} 时帧侧绝不为 false。
	 */
	private final ToolResultLimiter resultLimiter;

	/** T3-1：订阅者 = emitter + 有界队列 + CAS drain 独占标志。 */
	private final List<Subscription> subscribers = new CopyOnWriteArrayList<>();

	/**
	 * B23L1 批①：<b>本地已接手</b>的最大 seq（跨实例唤醒按它取差量，重复帧按它去重）。
	 *
	 * <p>
	 * 语义 = "本实例这条写出器已经处置到哪一帧"：出帧路径在<b>落档之后、发布唤醒之前</b>登记，
	 * 回放批、唤醒补帧也会推进它。三个后果：
	 * <ul>
	 * <li>同一帧不会被"本地扇出 + 唤醒"投递两次（本地先登记，自唤醒的回调看到上界 ≤ 水位即 no-op）；</li>
	 * <li>唤醒载荷只带上界也不漏中间帧（差量由 {@link FrameStore#readFrom} 给齐）；</li>
	 * <li>登记早于本地入队一步 —— 代价是"入队途中被中断"的那一帧不会被唤醒补投，
	 * 但那种中断本身就是订阅者被摘除（前端按 lastSeq 重挂补帧），口径与 S2-1 一致。</li>
	 * </ul>
	 */
	private final AtomicLong lastFanoutSeq = new AtomicLong(0);

	/** 本写出器是否已在该 run 的唤醒频道上登记（首个订阅者登记、最后一个退订）。 */
	private final AtomicBoolean wakeRegistered = new AtomicBoolean(false);

	/**
	 * 唤醒目标（<b>单一稳定实例</b>）：登记与注销必须是同一个对象 ——
	 * 每次 {@code this::onWake} 都会新建一个方法引用对象，按引用去重的目标集会因此注销不掉，
	 * 订阅随连接数泄漏。
	 */
	private final FrameWakeListener.WakeTarget wakeTarget = this::onWake;

	/**
	 * S5c-1：心跳的<b>独立</b>序号空间（自 1 起）。
	 *
	 * <p>
	 * 心跳不取业务 {@code seq}：心跳不落归档（T7），若它占用业务序号，则"归档末帧"这个
	 * 跨进程续号锚点就会落后于实际发放过的号 —— 重启后的新写出器会重用心跳用过的号，
	 * 而该号已在前端的 {@code lastSeq} 覆盖范围内，于是新帧在差量重挂里被判为孤儿而
	 * 永久丢弃（DC-14 §3.6 实测形态）。独立计数保留"挂起期心跳到第几次"的可观测性，
	 * 又与业务时间线彻底解耦。
	 */
	private final AtomicLong heartbeatSeq = new AtomicLong(0);

	/**
	 * 出帧互斥（S5c-3 / S5c-2）：取号 → 落归档 → 入队必须是一次不可分割的动作。
	 *
	 * <p>
	 * 为什么必须有：<b>出帧线程不唯一</b> —— 运行线程在挂起等待里每 2s 发心跳
	 * （{@code ConfirmGate#await}），HTTP 线程在 {@code POST /api/ai/confirm|frontend-tool-result}
	 * 里发决策/回灌帧。不加锁时先取号的线程可能后写出，订阅者看到的到达顺序可以逆序
	 * （实测已复现，见证据文档 S5c-3）。
	 *
	 * <p>
	 * 同一把锁也让 {@link #replayAndAttach} 的"回放收集 + 回放帧入队 + 挂订阅"成为
	 * 原子段落（S5c-2），消掉 reattach 的交接丢帧窗口。T3-1 起锁内不再做网络写出，
	 * 但回放帧的<b>入队</b>仍在锁内先于挂订阅完成，交接原子性不变。
	 */
	private final Object emitLock = new Object();

	/** T5：本轮 assistant 正文消息的身份（结果铸为消息 / 消息级渲染去重的判据）。 */
	private final String assistantMessageId;

	/** T5：正文消息边界是否已开（start/content/end 三段式的 start 只发一次）。 */
	private final AtomicBoolean textSegmentOpen = new AtomicBoolean(false);

	/**
	 * 轮终态标志（S1-1 修复批）：置位后不再向订阅者收新帧，已入队帧由各自 drain 排空后收尾。
	 *
	 * <p>
	 * 为什么不直接清订阅表：{@code complete()} 被调用时队列里可能还压着尾帧（慢订阅者的
	 * {@code done}/{@code error}/{@code suspended}），立刻 {@code emitter.complete()} 会
	 * 确定性地把它们丢掉（真实 {@code SseEmitter} 完成后拒绝再写）。标志与订阅者是"或"关系：
	 * 任一方说"该收尾了"，drain 的队空分支就完成该订阅者。
	 */
	private final AtomicBoolean terminating = new AtomicBoolean(false);

	public SseChatEmitter(String runId, RunStore store, ObjectMapper objectMapper) {
		this(runId, store, objectMapper, Runnable::run, AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY,
				AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);
	}

	public SseChatEmitter(String runId, RunStore store, ObjectMapper objectMapper, Executor deliveryExecutor,
			int queueCapacity) {
		this(runId, store, objectMapper, deliveryExecutor, queueCapacity, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);
	}

	public SseChatEmitter(String runId, RunStore store, ObjectMapper objectMapper, Executor deliveryExecutor,
			int queueCapacity, int maxFrameBytes) {
		this(runId, store, objectMapper, deliveryExecutor, queueCapacity, maxFrameBytes, SseWriteBudget.direct());
	}

	/**
	 * 全参构造（包②：显式注入写超时预算）。
	 *
	 * <p>
	 * 与投递执行器同口径（S2-1）：预算必须<b>显式传入</b>，绝不隐式读进程静态池 ——
	 * 否则同一 JVM 内测试顺序会让"直执"悄悄变异步投递（帧序用例要求"发完即到"）。
	 *
	 * <p>
	 * <b>B23L1 批①：事件日志走兼容口径</b>（{@code ai:events:} LIST，号锚 =
	 * max(归档末帧, ai:seq)）——它的语义逐字等于 B1 收官行为，既有帧序/conformance 用例
	 * （外置状态面是 {@code InMemoryRunStore} 之类 LIST 替身）因此零改动。
	 * 生产装配不经过本构造器：{@link RunRegistry} 的 Spring 路径恒注入
	 * {@link RedisFrameStore}（单/多实例同一实现），见下一个构造器。
	 */
	public SseChatEmitter(String runId, RunStore store, ObjectMapper objectMapper, Executor deliveryExecutor,
			int queueCapacity, int maxFrameBytes, SseWriteBudget writeBudget) {
		this(runId, store, RunStoreFrameStore.of(store), objectMapper, deliveryExecutor, queueCapacity, maxFrameBytes,
				writeBudget, null, null);
	}

	/** 便捷构造（B23L1 测试/非 Spring 装配：显式给事件日志，无跨实例唤醒）。 */
	public SseChatEmitter(String runId, RunStore store, FrameStore frames, ObjectMapper objectMapper) {
		this(runId, store, frames, objectMapper, Runnable::run,
				AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
				SseWriteBudget.direct(), null, null);
	}

	/**
	 * 生产装配构造（B23L1 批①）：事件日志 + 唤醒发布 + 唤醒订阅三件套一次注入。
	 *
	 * @param frames       事件日志（生产 = {@link RedisFrameStore}）
	 * @param wakeBus      唤醒发布面（null = 不跨实例广播）
	 * @param wakeListener 唤醒订阅面（null = 不订阅）
	 */
	public SseChatEmitter(String runId, RunStore store, FrameStore frames, ObjectMapper objectMapper,
			Executor deliveryExecutor, int queueCapacity, int maxFrameBytes, SseWriteBudget writeBudget,
			FrameWakeBus wakeBus, FrameWakeListener wakeListener) {
		this(runId, store, frames, objectMapper, deliveryExecutor, queueCapacity, maxFrameBytes, writeBudget,
				wakeBus, wakeListener, null);
	}

	/**
	 * 全参构造（G5）：额外注入模型侧裁剪器，使 {@code tool_result} 帧能挂上"模型看到的是不是全文"。
	 *
	 * <p>
	 * 为什么帧侧要认识模型侧预算：两条通道的阈值不同（字符 vs 字节），但都是同一份结果的
	 * "没给你全部"信号。只报帧侧预算时，模型侧裁到 20000 而帧侧 {@code truncated=false}，
	 * 人机会各信一半。传 null 表示"本写出器不标注模型侧视图"（帧序/非 Spring 测试路径）。
	 */
	public SseChatEmitter(String runId, RunStore store, FrameStore frames, ObjectMapper objectMapper,
			Executor deliveryExecutor, int queueCapacity, int maxFrameBytes, SseWriteBudget writeBudget,
			FrameWakeBus wakeBus, FrameWakeListener wakeListener, ToolResultLimiter resultLimiter) {
		this.runId = runId;
		this.store = store;
		this.frames = frames;
		this.wakeBus = wakeBus;
		this.wakeListener = wakeListener;
		this.resultLimiter = resultLimiter;
		// 帧面契约要求"字段存在、可为 null"（如 tool_result 的 code/target）：此处用一份
		// "显式写 null"的副本，**防御未来把 primary ObjectMapper 改成 NON_NULL** —— 那会让 null
		// 整键消失，前端无法区分"字段缺失"与"null"；更糟的是同一帧的实时写出与归档回放会按
		// 不同 inclusion 重新序列化，字段面分叉。
		// 现状（P0-1）：JacksonConfig 的自定义 primary mapper 保持 Jackson 默认 ALWAYS，故本行
		// 当前是 no-op；保留它是把这条契约钉在写出器自己身上，而不是依赖全局配置不变。
		this.objectMapper = objectMapper.copy().setSerializationInclusion(JsonInclude.Include.ALWAYS);
		this.deliveryExecutor = deliveryExecutor;
		this.queueCapacity = queueCapacity;
		this.maxFrameBytes = maxFrameBytes;
		this.writeBudget = writeBudget != null ? writeBudget : SseWriteBudget.direct();
		this.assistantMessageId = "assistant-" + runId;
	}

	/** 本写出器的单帧字节上限（供断言与诊断；单一事实源 = {@code app.ai.result-budget.ui.max-frame-bytes}）。 */
	int maxFrameBytes() {
		return this.maxFrameBytes;
	}

	/**
	 * 本写出器每订阅者的实时队列容量（单一事实源 = {@link AiProperties.Sse#DEFAULT_DELIVERY_QUEUE_CAPACITY}，
	 * 三参构造与注册表两条装配路径都取它）。
	 *
	 * <p>
	 * 包内可见：只给"队列容量 &lt; 归档窗口"不变量断言用（S3-1 修复批的测试锚点）——
	 * 值改坏即红，且不允许出现第二处字面量。
	 */
	int queueCapacity() {
		return this.queueCapacity;
	}

	/**
	 * 装配进程级共享投递池（{@link RunRegistry} 用 {@code AiProperties} 调一次；幂等）。
	 *
	 * <p>
	 * 池形参：{@code corePoolSize = maxPoolSize = 池容量（工作线程数，R3 语义）}，
	 * 有界任务队列 + AbortPolicy —— 提交被拒时由 {@link #submitDrain} 摘除
	 * 触发本次提交的订阅者。
	 */
	public static synchronized void configureSharedDeliveryPool(int poolSize, int poolQueueCapacity) {
		configureSharedDeliveryPool(poolSize, poolQueueCapacity, SseWriteBudget.DEFAULT_TIMEOUT_MILLIS);
	}

	/**
	 * 装配进程级共享投递池，并登记<b>写侧超时预算的实际值</b>（超时面治理批① 第 3 项）。
	 *
	 * <p>此前 {@link #deliveryPoolMetrics()} 里 {@code writeTimeoutMs} 恒为编译期缺省 15000 ——
	 * {@code /api/ai/health} 于是对"运维把 {@code app.ai.sse.write-timeout} 改成 8s"这件事
	 * 一律说 15000（配置面与可观测面两套话）。现在由装配方（{@link RunRegistry}）把绑定后的
	 * 实际值传进来，指标恒等于配置面。
	 *
	 * @param writeTimeoutMs 单帧写出预算（毫秒；非正 ⇒ 回落
	 * {@link SseWriteBudget#DEFAULT_TIMEOUT_MILLIS}，与启动期 fail-fast 守卫同口径）
	 */
	public static synchronized void configureSharedDeliveryPool(int poolSize, int poolQueueCapacity,
			long writeTimeoutMs) {
		sharedWriteTimeoutMs = writeTimeoutMs > 0 ? writeTimeoutMs : SseWriteBudget.DEFAULT_TIMEOUT_MILLIS;
		if (sharedDeliveryPool == null) {
			AtomicLong threadNo = new AtomicLong();
			ThreadPoolExecutor pool = new ThreadPoolExecutor(poolSize, poolSize, 60L, TimeUnit.SECONDS,
					new LinkedBlockingQueue<>(poolQueueCapacity), runnable -> {
						Thread thread = new Thread(runnable, "sse-delivery-" + threadNo.incrementAndGet());
						thread.setDaemon(true);
						return thread;
					}, new ThreadPoolExecutor.AbortPolicy());
			sharedDeliveryPool = pool;
		}
	}

	/** 共享投递池（未装配时回落同步直执，测试与非 Spring 场景的默认）。 */
	public static Executor sharedDeliveryExecutorOrDirect() {
		ThreadPoolExecutor pool = sharedDeliveryPool;
		return pool != null ? pool : Runnable::run;
	}

	/** 投递池指标（供 {@code GET /api/ai/health}）：poolSize / active / queueDepth / evicted / 写超时预算。 */
	public static Map<String, Object> deliveryPoolMetrics() {
		ThreadPoolExecutor pool = sharedDeliveryPool;
		Map<String, Object> metrics = new LinkedHashMap<>();
		metrics.put("poolSize", pool == null ? 0 : pool.getCorePoolSize());
		metrics.put("active", pool == null ? 0 : pool.getActiveCount());
		metrics.put("queueDepth", pool == null ? 0 : pool.getQueue().size());
		metrics.put("evicted", EVICTED_TOTAL.get());
		// 包②（纯增量键）：写侧超时预算与其命中次数 —— 写超时是"慢客户端"的直接观测面。
		// 超时面治理批① 第 3 项：报装配进来的实际值（= app.ai.sse.write-timeout），不是编译期缺省。
		metrics.put("writeTimeoutMs", sharedWriteTimeoutMs);
		metrics.put("writeTimeouts", SseWriteBudget.writeTimeouts());
		return metrics;
	}

	public String runId() {
		return this.runId;
	}

	/** 挂一个订阅者（首轮响应 / reattach 重挂共用）。 */
	public void attach(SseEmitter emitter) {
		attachSubscription(new Subscription(emitter, false));
	}

	/**
	 * 挂订阅并登记<b>生命周期回调</b>（S3-1 修复批第 2 轮：三回调统一走清理路径）。
	 *
	 * <p>
	 * 修复前三个回调只做 {@code subscribers.remove(sub)}：慢连接超时/断开（{@code onTimeout} /
	 * {@code onError}）时队列里还压着的帧（生产上是重挂回放的千级状态帧）无人释放 = 内存滞留，
	 * 且 drain 独占标志留在原地。现在与 {@code evict/completeOne} 共用
	 * {@link #closeSubscription}，清理口径只有一处。
	 *
	 * <p>
	 * <b>onTimeout 与无传输死线</b>：AI 通道的 emitter 由 {@code AiController} 以
	 * {@code new SseEmitter(-1L)} 构造（Servlet 语义：超时 ≤ 0 = 永不到期），因此这条回调在
	 * 生产路径上<b>不会</b>被触发。保留它是防御性的：本类接的是调用方给的 emitter，
	 * 将来若有有限超时的接入方（或单测），断开清理仍走同一路径；它不承担"流寿命"职责。
	 */
	private void attachSubscription(Subscription sub) {
		this.subscribers.add(sub);
		sub.emitter.onCompletion(() -> closeSubscription(sub, "生命周期 onCompletion"));
		sub.emitter.onTimeout(() -> closeSubscription(sub, "生命周期 onTimeout"));
		sub.emitter.onError(ex -> closeSubscription(sub, "生命周期 onError"));
		// B23L1：本实例的第一个订阅者出现 ⇒ 建立该 run 的唤醒订阅（按连接，不是按实例全量）。
		syncWakeSubscription();
	}

	/**
	 * 关闭一个订阅者的<b>投递上下文</b>（生命周期回调 / 摘除 / 收尾的统一清理路径）。
	 *
	 * <p>
	 * 顺序有意如此：先摘表（此后 {@link #emit} 的扇出不再触及它），再清队列与计数，
	 * 最后复位 drain 独占标志 —— 这样"在跑的 drain"最多多空转一轮（队列已空，{@code poll} 即退出），
	 * 不会把清理结果改回去。
	 *
	 * <p>
	 * {@code closed} 是"安全取消挂起 drain"的开关：置位后 {@link #emit} 的扇出直接跳过本订阅者，
	 * 于是 CAS 抢到的那个 drain 到点只会看到空队列并退出，不会再把帧写向一个已终态的 emitter
	 * （{@code SseEmitter} 上完成后再写会抛 {@code IllegalStateException}，只留日志噪音）。
	 * 计数归零用 {@code set(0)} 而非递减：清队列丢掉了队列快照，残留计数比轻微少计更危险
	 * （漏摘除 = 队列无界增长）；在跑的 drain 若随后再弹出一个已消失的实时帧，其递减由
	 * {@link #liveDequeued} 兜到 0 为止，不会为负。
	 *
	 * <p>
	 * 本方法<b>不</b>碰 {@code Subscription#completed}：终结 emitter 只有 {@link #completeOne}
	 * 一处（{@code completed} 是它的去重标志），摘除路径 = 本方法 + {@code completeOne}。
	 */
	private void closeSubscription(Subscription sub, String reason) {
		this.subscribers.remove(sub);
		sub.closed = true;
		sub.queue.clear();
		sub.liveQueued.set(0);
		sub.draining.set(false);
		// B23L1：本实例最后一个订阅者消失 ⇒ 释放该 run 的唤醒订阅（断连即 unsubscribe）。
		syncWakeSubscription();
		log.debug("订阅者投递上下文已关闭 runId={} reason={}", this.runId, reason);
	}

	/**
	 * B23L1：唤醒订阅的生命周期 —— 有本地订阅者才订阅，最后一个断开即退订。
	 *
	 * <p>
	 * 幂等开关（{@link #wakeRegistered}）保证重复调用不重复 subscribe；
	 * 判据是订阅表本身（{@code 0 ↔ N} 翻转），不在别处维护计数 —— 订阅者的增删路径
	 * 全仓只有 {@link #attachSubscription} / {@link #closeSubscription} 两处，计数口径因此不会漂移。
	 */
	private void syncWakeSubscription() {
		FrameWakeListener listener = this.wakeListener;
		if (listener == null) {
			return;
		}
		if (!this.subscribers.isEmpty()) {
			if (this.wakeRegistered.compareAndSet(false, true)) {
				boolean registered;
				try {
					registered = listener.register(this.runId, this.wakeTarget);
				}
				catch (Exception ex) {
					log.warn("帧唤醒订阅异常 runId={}：{}", this.runId, ex.getMessage());
					registered = false;
				}
				if (!registered) {
					// I-3：订阅没建立就把登记位回退 —— 否则该写出器在订阅者归零前永不再试，
					// 跨实例唤醒会整段静默缺席且没有任何观测面。
					this.wakeRegistered.set(false);
				}
			}
		}
		else if (this.wakeRegistered.compareAndSet(true, false)) {
			listener.unregister(this.runId, this.wakeTarget);
		}
	}

	/**
	 * <b>S5c-2 + T3-1</b>：重挂的原子交接 —— 回放帧收集（{@code LRANGE} 只读）、回放帧入队、
	 * 挂订阅在<b>同一把出帧锁</b>内完成；网络写出统一走异步队列（回放批豁免实时容量，R1 裁决）。
	 *
	 * <p>
	 * 为什么必须原子：{@code 回放 → attach} 两段之间若有实时帧写出，那一帧既不在回放快照里、
	 * 也还没被这个订阅者接住 = 丢帧窗口（且 SSE 丢帧不报错，只会静默少一段）。
	 *
	 * @param terminal true = 该轮已终态（回放完即收尾，不再订阅）
	 * @return 实际入队的回放帧数（异步写出前的入队口径）
	 */
	public int replayAndAttach(SseEmitter emitter, Long lastSeq, boolean terminal) {
		Subscription sub = new Subscription(emitter, terminal);
		synchronized (this.emitLock) {
			// I-1：**先挂订阅（含唤醒订阅）再读回放**。旧顺序（读档 → 挂订阅）之间落档的帧
			// 谁也接不住：回放快照已读完、唤醒订阅还没建立，而挂起/终局帧之后不会再有下一次唤醒
			// ⇒ B 侧连接永远等不到那一帧。两段同在 emitLock 内，先挂不会改变"回放帧先于实时帧入队"
			// 的 FIFO 语义（实时入队同样要求这把锁）。
			if (!terminal) {
				attachSubscription(sub);
			}
			int staged = stageReplay(sub, lastSeq);
			if (terminal) {
				if (staged == 0) {
					completeOne(sub);
				}
				else {
					kickDrain(sub);
				}
			}
			else if (staged > 0) {
				kickDrain(sub);
			}
			return staged;
		}
	}

	/**
	 * 把外置帧回放给一个新订阅者（不入订阅表，回放发完即 complete）。
	 *
	 * <p>
	 * <b>B1（2026-10-20）起 {@code delta} 也回放</b>：挂起即断（流派 B）让"决策 → 续跑"的
	 * 窗口内产生的正文帧没有实时通道，只能靠归档补回，故 {@code delta} 与状态帧同待遇进归档、
	 * 同待遇回放。{@code heartbeat} 仍跳过（不入档、只作保活）。
	 * {@code start} / {@code message_*} / {@code suspended} / {@code confirm_request} / {@code tool_*} /
	 * {@code confirm_decision} / {@code done} / {@code error} 全部保留 —— 重挂者据此重建
	 * "这一轮发生过什么"，包括已经结束的轮次的终帧。
	 *
	 * <p>
	 * <b>T6 增量补发 + 孤儿过滤</b>：给了 {@code lastSeq}（前端已知的最大帧序号）时，
	 * 只回放 {@code seq > lastSeq} 的帧 —— 孤儿（seq ≤ lastSeq，前端本地已有）不重发，
	 * 于是长轮次的 reattach 代价从"整轮全部帧"降到"差量"。没有 {@code seq} 的老帧
	 * （升级前写进归档的）无法判定，按"照发"处理（宁可重复，不可丢帧）。
	 *
	 * @param lastSeq 前端已知的最大 seq；null = 全量回放
	 * @return 实际入队的回放帧数（异步写出前的入队口径）
	 */
	public int replayTo(SseEmitter emitter, Long lastSeq) {
		Subscription sub = new Subscription(emitter, true);
		synchronized (this.emitLock) {
			int staged = stageReplay(sub, lastSeq);
			if (staged == 0) {
				completeOne(sub);
			}
			else {
				kickDrain(sub);
			}
			return staged;
		}
	}

	/**
	 * 锁内收集回放帧并入队（调用方持 {@link #emitLock}）。
	 *
	 * <p>
	 * <b>R1 裁决</b>：本方法入队<b>不受实时容量 256 约束</b>（回放批豁免）——慢重挂者的
	 * 初始批上界 = 事件日志窗口（{@link FrameStore}，Stream 口径 6000 / 兼容 LIST 口径 3000），
	 * "队列容量 &lt; 事件日志窗口"不变量只约束实时段。
	 *
	 * <p>
	 * <b>B23L1 批①：回放源从 {@code ai:events:} LIST 切到 {@link FrameStore#readFrom}</b>
	 * （兼容期内部仍会把旧 LIST 串在 Stream 前面并按 seq 去重）。差量下推口径不变：
	 * 给了 {@code lastSeq} 就只要 {@code seq > lastSeq}（孤儿过滤仍在本方法兜一层，
	 * 防"发帧后才落档失败"等错位）。回放同时把 {@link #lastFanoutSeq} 顶到已读到的最大号 ——
	 * 这样随后的跨实例唤醒不会把这些帧再推一遍（重复帧按 seq 去重）。
	 */
	private int stageReplay(Subscription sub, Long lastSeq) {
		long afterSeq = lastSeq == null ? 0L : lastSeq;
		List<Map<String, Object>> frames = this.frames.readFrom(this.runId, afterSeq, 0);
		int staged = 0;
		long maxReadSeq = 0L;
		for (Map<String, Object> frame : frames) {
			String type = String.valueOf(frame.get("type"));
			Object seqValue = frame.get("seq");
			if (seqValue instanceof Number number && number.longValue() > maxReadSeq) {
				maxReadSeq = number.longValue();
			}
			if (RunStore.HEARTBEAT_TYPE.equals(type)) {
				// 心跳从不入档（T7），这里只是对"绕过发射器直塞的心跳帧"的防御性跳过。
				// B1：delta 不再跳过 —— 挂起断连窗口内的正文帧只能经归档差量补回。
				continue;
			}
			if (isOrphan(frame, lastSeq)) {
				continue;
			}
			try {
				sub.queue.add(new QueuedFrame(this.objectMapper.writeValueAsString(frame), false));
				staged++;
			}
			catch (Exception ex) {
				log.error("回放帧序列化失败 runId={} type={}: {}", this.runId, type, ex.getMessage());
				break;
			}
		}
		if (maxReadSeq > 0L) {
			this.lastFanoutSeq.accumulateAndGet(maxReadSeq, Math::max);
		}
		return staged;
	}

	/** 孤儿判定：帧序号不大于前端已知的最大序号 ⇒ 该帧前端已有，不重发。 */
	private static boolean isOrphan(Map<String, Object> frame, Long lastSeq) {
		if (lastSeq == null) {
			return false;
		}
		Object seqValue = frame.get("seq");
		if (seqValue instanceof Number number) {
			return number.longValue() <= lastSeq;
		}
		return false;
	}

	public int subscriberCount() {
		return this.subscribers.size();
	}

	// ── 帧 ──────────────────────────────────────────────────────────────────

	/** 首包：runId 由服务端生成，前端据此做 reattach。 */
	public void start(String sessionId) {
		Map<String, Object> frame = frame("start");
		frame.put("runId", this.runId);
		frame.put("sessionId", sessionId);
		emit(frame);
	}

	public void delta(String text) {
		Map<String, Object> frame = frame("delta");
		frame.put("messageId", this.assistantMessageId);
		frame.put("text", text);
		emit(frame);
	}

	/**
	 * 消息边界（T5 / R8-2）：正文段（kind={@code text}）或思考段（kind={@code reasoning}）的
	 * start / end 标记，配合中间的 content 帧（{@code delta}）构成三段式生命周期。
	 *
	 * <p>
	 * 为什么需要它：一条 assistant 消息可能"先吐思考、再吐正文、中间夹工具调用"，
	 * 而前端此前只有 content 帧 —— 无法判断两段文本属于同一条消息还是两条
	 * （SP-01ab §7.3 的混合消息渲染痛点）。边界帧 + {@code messageId} 让"哪几帧属于同一条消息"
	 * 成为协议事实，而不是靠客户端猜。
	 *
	 * <p>
	 * 纯增量：老客户端不认识这两个帧类型会忽略它们，content 帧照旧可独立渲染。
	 */
	public void messageBoundary(String phase, String kind, Map<String, Object> extra) {
		Map<String, Object> frame = frame("message_" + phase);
		frame.put("messageId", this.assistantMessageId);
		frame.put("kind", kind);
		if (extra != null) {
			frame.putAll(extra);
		}
		emit(frame);
	}

	/** 正文段 start（同一条消息只开一次；重复调用无副作用）。 */
	public boolean textStart() {
		if (!this.textSegmentOpen.compareAndSet(false, true)) {
			return false;
		}
		messageBoundary("start", "text", null);
		return true;
	}

	/** 正文段 end（带本段字符数，便于前端与 done 帧对账）；未开过段则不发（幂等）。 */
	public boolean textEnd(int chars) {
		if (!this.textSegmentOpen.compareAndSet(true, false)) {
			return false;
		}
		messageBoundary("end", "text", Map.of("chars", chars));
		return true;
	}

	/** 挂起公告：快照已外置（带 Redis 键名与写入者实例，供前端"可重挂"提示）。 */
	public void suspended(List<PendingToolCall> pendings, String stateKey, String pendingKey, String writtenBy) {
		Map<String, Object> frame = frame("suspended");
		frame.put("runId", this.runId);
		frame.put("toolCalls", pendings.stream().map(PendingToolCall::asMap).toList());
		frame.put("stateKey", stateKey);
		frame.put("pendingKey", pendingKey);
		frame.put("writtenBy", writtenBy);
		emit(frame);
	}

	/** 挂起期心跳：证明"挂起期间连接仍然活着"，也是前端展示倒计时的节拍。 */
	public void heartbeat(Map<String, Object> data) {
		Map<String, Object> frame = frame("heartbeat");
		frame.putAll(data);
		emit(frame);
	}

	public void toolStart(PendingToolCall pending) {
		Map<String, Object> frame = frame("tool_start");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("kind", pending.getKind());
		frame.put("args", pending.getArguments());
		emit(frame);
	}

	/**
	 * 工具结局帧（P0-1 统一信封的帧面）：既有 {@code ok/result/status} 原样保留，
	 * 新增 {@code outcome/code/retryable/target} 四字段。
	 *
	 * <p>
	 * {@code ok} 按契约由 {@code outcome == ok} 推导（不信任调用方传值，避免"帧说成功、结局说失败"）；
	 * {@code outcome}/{@code code} 取自待决条目的持久化信封（旧数据按 status 推导）；
	 * {@code target} 从工具参数 JSON 提取 {@code {taskId?, defCode?, step?, page?}}，提取不到为 null。
	 * {@code result} 仍是<b>全文</b>（人读通道，不受模型侧裁剪影响）。
	 */
	public void toolResult(PendingToolCall pending, boolean ok, boolean reused, String result) {
		ToolOutcome outcome = pending.effectiveOutcome();
		// M4：ok 保留在签名里（不改调用面），但契约上它只能是 outcome 的派生值。
		// 传错即 fail-fast —— 否则"帧 ok 与信封 outcome 互相矛盾"会被前端按任一字段误读。
		if (ok != outcome.ok()) {
			throw new CodedIllegalArgumentException(ErrorCodes.TOOL_RESULT_OUTCOME_MISMATCH, "tool_result 帧的 ok 与 outcome 推导不一致：ok=" + ok
					+ "，outcome=" + outcome.wire() + "（toolCallId=" + pending.getToolCallId() + "）");
		}
		String code = pending.effectiveCode();
		Map<String, Object> frame = frame("tool_result");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("kind", pending.getKind());
		frame.put("ok", outcome.ok());
		frame.put("executed", pending.isExecuted());
		frame.put("executedBy", pending.getExecutedBy());
		frame.put("reused", reused);
		frame.put("status", pending.getStatus());
		frame.put("outcome", outcome.wire());
		frame.put("code", code);
		frame.put("retryable", outcome.retryable(code));
		frame.put("target", ToolResultEnvelope.targetOf(pending.getArguments()));
		frame.put("messageId", toolMessageId(pending));
		frame.put("result", result);
		markModelView(frame, pending.getName(), result);
		emit(frame);
	}

	/**
	 * 确认卡请求帧：<b>不带到期时刻</b>（用户裁决 2026-10-20 第 1 条 —— 确认门不再超时）。
	 *
	 * <p>帧里没有 {@code timeoutSeconds}/{@code expiresAt}：前端据此不武装倒计时、不把按钮置灰，
	 * 一直等到人批准 / 拒绝 / 取消。历史轮的旧帧仍可能带这两个字段，前端按旧口径渲染即可。
	 */
	public void confirmRequest(PendingToolCall pending) {
		Map<String, Object> frame = frame("confirm_request");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("args", pending.getArguments());
		frame.put("summary", pending.getArguments());
		// F5：只读预检报告随请求帧下发（契约新增可选字段；缺省 null = 旧卡面）
		frame.put("preflight", pending.getPreflight());
		frame.put("callback", "POST /api/ai/confirm {runId, toolCallId, approved, reason}");
		emit(frame);
	}

	public void confirmDecision(PendingToolCall pending, String decision, String reason, long waitedMs) {
		Map<String, Object> frame = frame("confirm_decision");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("decision", decision);
		frame.put("approved", PendingToolCall.APPROVED.equals(pending.getStatus()));
		frame.put("status", pending.getStatus());
		frame.put("reason", reason);
		frame.put("waitedMs", waitedMs);
		// F5 批准绑定：决策回执带同一份预检指纹（审计上"批准的是哪一版预检"可自证）
		frame.put("preflightHash", pending.getPreflight() == null ? null
				: pending.getPreflight().get("reportHash"));
		emit(frame);
	}

	/**
	 * 前端工具请求帧。
	 *
	 * @param timeoutSeconds 后端回执等待上限（秒）；<b>null = 不设上限</b>（{@code generative_form}：
	 *                       等的是人填表，用户裁决 2026-10-20 第 2 条）。null 时帧里不带
	 *                       {@code timeoutSeconds}/{@code expiresAt}，前端据此不武装倒计时；
	 *                       有值时两者成对下发，且 {@code expiresAt − atMs} 精确等于
	 *                       {@code timeoutSeconds * 1000L}（同帧单点取钟，见 {@link #expiresAtEpochMs}）。
	 */
	public void frontendToolRequest(PendingToolCall pending, Integer timeoutSeconds) {
		Map<String, Object> frame = frame("frontend_tool_request");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("args", pending.getArguments());
		if (timeoutSeconds != null) {
			frame.put("timeoutSeconds", timeoutSeconds);
			// T4 同口径（纯增量）：有时限的前端工具一并给出绝对时钟，前端不必用本地常量推断到期时刻。
			frame.put("expiresAt", expiresAtEpochMs(frame, timeoutSeconds));
		}
		frame.put("callback", "POST /api/ai/frontend-tool-result {runId, toolCallId, result}");
		emit(frame);
	}

	public void frontendToolResult(PendingToolCall pending, boolean ok, String result) {
		// P0-1：与 tool_result 同契约 —— 结局四字段同一推导（见 toolResult），前端不必再从
		// "ok=false 但 result 文本像是成功"里猜；卡面与帧共用 outcome 这个单一事实源。
		ToolOutcome outcome = pending.effectiveOutcome();
		String code = pending.effectiveCode();
		Map<String, Object> frame = frame("frontend_tool_result");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("ok", ok);
		frame.put("executed", false);
		frame.put("status", pending.getStatus());
		frame.put("outcome", outcome.wire());
		frame.put("code", code);
		frame.put("retryable", outcome.retryable(code));
		frame.put("target", ToolResultEnvelope.targetOf(pending.getArguments()));
		frame.put("messageId", toolMessageId(pending));
		frame.put("result", result);
		markModelView(frame, pending.getName(), result);
		emit(frame);
	}

	/**
	 * G5：把<b>模型侧</b>的截断判定如实映射到帧上（同一套字段名，见 {@code TruncationContract}）。
	 *
	 * <p>
	 * {@code result} 仍是全文 —— 这里只说"模型看到的是不是全文"。为什么必须做：
	 * 模型侧仍存在大结果被裁的时刻；如果帧侧只说 {@code truncated=false}，前端角标不亮、
	 * 用户以为模型看全了，人机对同一份结果形成两种事实。
	 *
	 * <p>
	 * 反向（帧字节超限而模型侧未超）由 {@link FrameSizeLimiter} 在帧上覆盖说明 ——
	 * 那时是"前端这一份被压过"，与模型侧无关，notice 会写明。
	 */
	private void markModelView(Map<String, Object> frame, String toolName, String result) {
		if (this.resultLimiter == null || result == null || result.isEmpty()) {
			return;
		}
		ToolResultLimiter.Truncation view = this.resultLimiter.forModel(toolName, result);
		if (!view.truncated()) {
			return;
		}
		String notice = "模型侧视图已截断：原 " + view.originalChars() + " 字符，模型可见 " + view.returnedChars()
				+ " 字符（strategy=" + view.strategy() + "）；本帧 result 为全文。";
		frame.put(TruncationContract.TRUNCATED_FIELD, true);
		frame.put(TruncationContract.STRATEGY_FIELD, view.strategy());
		frame.put(TruncationContract.ORIGINAL_CHARS_FIELD, view.originalChars());
		frame.put(TruncationContract.RETURNED_CHARS_FIELD, view.returnedChars());
		frame.put(TruncationContract.NOTICE_FIELD, notice);
		frame.put(TruncationContract.LEGACY_REASON_FIELD, notice);
	}


	/**
	 * 断流重试公告（韧性棒）：本轮第 {@code nextAttempt} 次尝试即将开始 ——
	 * 前端据此展示"上游连接中断，正在重试"，并知道本轮<b>没有</b>产出过内容
	 * （重试三条件保证：已产出即不重试）。
	 */
	public void retry(Map<String, Object> data) {
		Map<String, Object> frame = frame("retry");
		frame.putAll(data);
		emit(frame);
	}

	/**
	 * 终帧：{@code done}。
	 *
	 * @param extra 附加信息（韧性棒：{@code cancelled} / {@code attempts} /
	 * {@code upstreamEvents} / {@code terminalSignal}）；取消终态为 {@code cancelled=true}，
	 * 与"正常完成"共用同一个终帧类型（前端的终态处理不必分两套）。
	 */
	public void done(Map<String, Object> usage, String model, Map<String, Object> extra) {
		done(usage, model, extra, null);
	}

	/**
	 * 终帧：{@code done}（带上下文计量子对象）。
	 *
	 * <p>
	 * <b>上下文专项批①（帧增量）</b>：{@code context} 子对象是 {@code done} 帧的<b>可选</b>键
	 * {@code {usedTokens, limitTokens, percent, level, est, circuit}}，每轮都会带（帧类型集不变）。
	 * 旧客户端忽略未知键，前向兼容。为 null/空时<b>不写</b>该键（"无计量设施"与"计量为 0" 必须可区分）。
	 *
	 * @param context 上下文计量快照（{@code ContextTelemetry}）；null = 本进程未装配计量
	 */
	public void done(Map<String, Object> usage, String model, Map<String, Object> extra, Map<String, Object> context) {
		Map<String, Object> frame = frame("done");
		frame.put("runId", this.runId);
		if (usage != null && !usage.isEmpty()) {
			frame.put("usage", usage);
		}
		if (model != null) {
			frame.put("model", model);
		}
		if (extra != null) {
			frame.putAll(extra);
		}
		if (context != null && !context.isEmpty()) {
			frame.put("context", context);
		}
		emit(frame);
	}

	/**
	 * 压缩事件帧（上下文专项批①；帧类型集 15 → 16 的<b>唯一</b>新增）。
	 *
	 * <p>
	 * 与 {@code done.context} 的分工：{@code done.context} 是"本轮结束时的占用"（每轮一次、轻量），
	 * 本帧是"<b>此刻发生了一次压缩</b>"（即时可见，挂起/续跑期间也可能出现）。载荷
	 * {@code {level, before, after, turns, lostTokens, reason, est}} 与日志、台账三处同一组值。
	 *
	 * <p>
	 * 位置约束：压缩发生在请求前，故本帧必然出现在本轮第一个 {@code delta}/{@code tool_start} 之前
	 * （非终帧、非工具帧，不参与 {@code FrameContract} 的任何一条不变量）。
	 */
	public void context(Map<String, Object> data) {
		Map<String, Object> frame = frame("context");
		if (data != null) {
			frame.putAll(data);
		}
		emit(frame);
	}

	public void error(String code, String message) {
		Map<String, Object> frame = frame("error");
		frame.put("runId", this.runId);
		frame.put("code", code);
		frame.put("message", message);
		emit(frame);
	}

	// ── 内部 ────────────────────────────────────────────────────────────────

	/**
	 * 帧骨架：{@code type} + {@code atMs}（T4-3 后半）。
	 *
	 * <p>
	 * {@code atMs} 在<b>帧构造时刻</b>取值 —— 帧一出生就带，故实时投递（{@link #emit} 里的
	 * 序列化）与归档（{@link RunStore#appendEvent}）拿到的是同一个值；与 {@code expiresAt}
	 * 同一取钟口径（见 {@link #expiresAtEpochMs}），二者之差恒等于 {@code timeoutSeconds}。
	 * {@code RunStore#appendEvent} 的 {@code putIfAbsent} 自此只是兜底（旧格式帧/直接落档方）。
	 */
	private Map<String, Object> frame(String type) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", type);
		frame.put("atMs", System.currentTimeMillis());
		return frame;
	}

	/**
	 * B23L1 批①：出帧路径 —— <b>取号（FrameStore）→ 截断 → 落档（FrameStore）→ 唤醒发布 → 本地扇出</b>。
	 *
	 * <p>
	 * 与 B1 收官形态的三点差异（口径全部来自设计稿 §2.1）：
	 * <ol>
	 * <li><b>取号收敛到事件日志</b>：生产实现 = {@code INCR ai:seq:<runId>}（跨实例原子），
	 * 进程内 {@code AtomicLong} 号锚退役 —— 多实例同时为一轮出帧不再可能撞号；</li>
	 * <li><b>落档后发唤醒</b>：{@code PUBLISH ai:wake:<runId> <上界seq>}（载荷不含帧体），
	 * 让持有该 run 连接的<b>其它实例</b>去事件日志补差量；</li>
	 * <li><b>本地扇出保留</b>（同实例快路径）：唤醒是"补"，本地扇出是"快"，
	 * 两者按 {@link #lastFanoutSeq} 去重，不会把同一帧投两遍。</li>
	 * </ol>
	 *
	 * <p>
	 * <b>锁内顺序不可调换</b>：取号 → 截断（判定含 seq，A19）→ 落档 → 发布 → 入队。
	 * 落档必须拿到截断后的帧（否则事件日志里躺着未截断帧，回放与实时流分叉）；
	 * 发布必须在落档之后（否则别的实例被唤醒后读不到这一帧）。
	 */
	private void emit(Map<String, Object> frame) {
		frame.putIfAbsent("runId", this.runId);
		// T4-3 后半：atMs 已在 frame() 里定型（帧构造时刻），故这一份帧的序列化原文与落档副本
		// atMs 同值；EventStore 落档侧的 putIfAbsent 只对"绕过 frame() 的直塞帧"生效（兜底）。
		// S5c-3：取号 → 落档 → 入队在同一把锁内完成 —— 多线程（运行线程的心跳 / HTTP 线程的
		// 决策与回灌）并发出帧时，订阅者队列里的先后与 seq 的先后必然一致；网络写出在锁外
		// 由投递池异步完成（T3-1 慢订阅者隔离），单订阅者 FIFO 由 CAS drain 独占标志保证。
		synchronized (this.emitLock) {
			final byte[] payloadBytes;
			if (RunStore.HEARTBEAT_TYPE.equals(String.valueOf(frame.get("type")))) {
				// T7：心跳照常广播（下面的 subscribers），但不进事件日志 —— 只留一个轻量活动戳，
				// 让"这轮最近有过活动"仍可跨进程判定（僵尸判据的输入）。事件日志侧的同类跳过见
				// FrameStore#append（任何其他调用方直接塞心跳帧也不会占位）。
				// S5c-1：心跳<b>不取业务 seq</b>，只在独立序号空间里计数 —— 于是事件日志末帧号
				// 恒等于已发放过的最大业务号，心跳永远不会把它顶到前面去。
				frame.put("heartbeatSeq", this.heartbeatSeq.incrementAndGet());
				this.store.touchActivity(this.runId);
				try {
					payloadBytes = this.objectMapper.writeValueAsBytes(frame);
				}
				catch (Exception ex) {
					log.error("SSE 帧序列化失败 runId={} type={}: {}", this.runId, frame.get("type"), ex.getMessage());
					return;
				}
			}
			else {
				// B23L1：取号 = 事件日志的取号口（生产 = Redis INCR，跨实例不重不回退）。
				long seq = this.frames.nextSeq(this.runId);
				frame.put("seq", seq);
				// T3-10：单帧上限的字段级截断在"取号<b>之后</b>、落档与入队之前"——
				// 判定口径是<b>整帧</b>（含 seq）的 UTF-8 字节数（A19），故必须先补 seq 再判；
				// 落档与投递据此拿到同一份帧（否则重挂回放与实时流会分叉）。
				// 返回值即最终负载（未超限时复用同一次序列化，不重复算）。
				// 心跳帧不在此列：它没有 text/result，负载由常量字段构成（事件载荷固定小）。
				payloadBytes = FrameSizeLimiter.apply(frame, this.objectMapper, this.maxFrameBytes);
				if (payloadBytes == null) {
					// 序列化失败：帧不落档、不发订阅者（与 T3-1"锁内只做取号/落档/入队"同口径）
					log.error("SSE 帧序列化失败 runId={} type={}（本帧已跳过）", this.runId, frame.get("type"));
					return;
				}
				this.frames.append(this.runId, frame);
				// B23L1：先登记"本地已接手到 seq"，再发唤醒 —— 唤醒分发可能在本线程内同步回调
				// （同步总线/测试替身），若不先登记，自唤醒会把刚落的这一帧再推一遍。
				this.lastFanoutSeq.accumulateAndGet(seq, Math::max);
				publishWake(seq);
			}
			if (this.subscribers.isEmpty() || this.terminating.get()) {
				// S1-1：轮终态已置 ⇒ 不再向订阅者收新帧（帧照旧取号落档，重挂回放仍可见）——
				// 否则终态之后的新帧会把各 drain 的"排空"无限推迟，收尾永远等不到队空。
				return;
			}
			final String payload = new String(payloadBytes, java.nio.charset.StandardCharsets.UTF_8);
			for (Subscription sub : this.subscribers) {
				if (sub.closed) {
					// S3-1：生命周期回调已清理该订阅者（COW 快照与摘除的窄窗）—— 不再入队、不再抢 drain
					continue;
				}
				if (!enqueueLive(sub, payload)) {
					// S2-1：溢出摘除 = 主动断连 —— 显式 complete 触发前端 onerror/onclose，
					// 前端带 lastSeq 重挂走差量补发（seq 无洞），胜过静默丢帧。
					evict(sub, "实时队列溢出（容量 " + this.queueCapacity + "）");
					continue;
				}
				kickDrain(sub);
			}
		}
	}

	/** 唤醒发布（载荷仅上界 seq；失败只记日志 —— 唤醒可丢，正确性由 ?lastSeq 重连兜底）。 */
	private void publishWake(long seq) {
		FrameWakeBus bus = this.wakeBus;
		if (bus == null) {
			return;
		}
		try {
			bus.publish(this.runId, seq);
		}
		catch (Exception ex) {
			log.debug("帧唤醒发布失败 runId={} seq={}：{}", this.runId, seq, ex.getMessage());
		}
	}

	/**
	 * B23L1：收到跨实例唤醒 —— 从事件日志补 {@code > lastFanoutSeq} 的差量，推给本实例该 run 的连接。
	 *
	 * <p>
	 * 去重口径（设计稿 §2.1 第 3 条）：<b>按 seq</b> 与本地扇出快路径共用同一个上界
	 * （{@link #lastFanoutSeq}）—— 本地已经推过的帧不会被唤醒再推一遍；
	 * 唤醒载荷只带上界，中间帧由 {@link FrameStore#readFrom} 给齐，因此"唤醒丢一条"不会造成永久缺口
	 * （下一次唤醒或客户端 {@code ?lastSeq} 重连都能补齐）。
	 *
	 * <p>
	 * 与 {@link #emit} 共用 {@code emitLock}：唤醒补帧与实时出帧在同一把锁下串行入队，
	 * 单订阅者队列保持 FIFO 且 seq 单调。
	 */
	void onWake(String runId, long upperBoundSeq) {
		if (!this.runId.equals(runId)) {
			return;
		}
		synchronized (this.emitLock) {
			if (this.subscribers.isEmpty() || this.terminating.get()) {
				return;
			}
			long from = this.lastFanoutSeq.get();
			if (upperBoundSeq > 0L && upperBoundSeq <= from) {
				// 上界不高于本地已推上界 ⇒ 没有差量（重复唤醒幂等）。
				// 收口注记（I-2）：晚到的**低号**帧（乱序落档兜底）也没有差量 —— 那一帧同样不会被
				// 实时补发，这是协议要求（订阅者已看到更大的 seq，补发会破坏 seq 单调）；
				// 它仍对 lastSeq < 该号的读者可见（重挂回放走 FrameStore#readFrom，按 ID 区间读）。
				return;
			}
			List<Map<String, Object>> frames = this.frames.readFrom(this.runId, from, FrameStore.WAKE_BATCH_LIMIT);
			for (Map<String, Object> frame : frames) {
				if (RunStore.HEARTBEAT_TYPE.equals(String.valueOf(frame.get("type")))) {
					continue;
				}
				Object seqValue = frame.get("seq");
				if (!(seqValue instanceof Number number) || number.longValue() <= from) {
					// 没有 seq 的老帧只走回放（唤醒是新帧语义）；不大于上界的帧是重复帧，跳过
					continue;
				}
				long seq = number.longValue();
				final String payload;
				try {
					payload = this.objectMapper.writeValueAsString(frame);
				}
				catch (Exception ex) {
					log.error("唤醒补帧序列化失败 runId={} seq={}: {}", this.runId, seq, ex.getMessage());
					break;
				}
				for (Subscription sub : this.subscribers) {
					if (sub.closed) {
						continue;
					}
					if (!enqueueLive(sub, payload)) {
						evict(sub, "唤醒补帧超出实时队列容量（容量 " + this.queueCapacity + "）");
						continue;
					}
					kickDrain(sub);
				}
				this.lastFanoutSeq.accumulateAndGet(seq, Math::max);
				from = Math.max(from, seq);
			}
		}
	}

	/**
	 * 实时段入队（T3-1 不变量：队列容量 &lt; 归档窗口 3000）。
	 *
	 * <p>
	 * 队列本体无界、容量闸由本方法显式把守：所有生产者都在 {@code emitLock} 内串行入队、
	 * 消费者只摘除，判定因此是精确的。闸的判据是 {@code Subscription#liveQueued}
	 * （队列中<b>实时帧</b>的条数，S1 修复批第 2 轮）而<b>不是</b>队列绝对长度 —— 回放批
	 * 走 {@link #stageReplay} 的豁免口径（R1 裁决）且不计入该计数，两类入队互不挤占：
	 * 慢重挂者排空 3000 帧回放期间到来的实时帧，不会因队列里压着回放帧而被误判成实时溢出。
	 *
	 * <p>
	 * <b>递增必须先于入队</b>：反序时并发 drain 可在 {@code add} 与 {@code incrementAndGet}
	 * 之间弹出该帧并递减，随后递增落地 ⇒ 计数永久 +1，容量闸逐帧泄漏直至误摘健康订阅者。
	 * 反方向（递增后才入队）不会产生幽灵：{@code LinkedBlockingQueue} 无界，{@code add} 不会失败。
	 */
	private boolean enqueueLive(Subscription sub, String payload) {
		if (sub.liveQueued.get() >= this.queueCapacity) {
			return false;
		}
		sub.liveQueued.incrementAndGet();
		sub.queue.add(new QueuedFrame(payload, true));
		return true;
	}

	/**
	 * 实时段出队（drain 弹出实时帧）：计数减一。
	 *
	 * <p>
	 * 兜底不减到负：唯一的"计数已归零却又弹出实时帧"来源是并发清空（{@link #closeSubscription}
	 * 的 {@code set(0)} 落在本次 {@code poll} 与本次递减之间）——此时该订阅者已被关闭，
	 * 计数保持 0 即正确口径（为负会让容量闸永久失效，比少计更危险）。
	 */
	private static void liveDequeued(Subscription sub) {
		sub.liveQueued.updateAndGet(current -> current > 0 ? current - 1 : 0);
	}

	/** 入队后 CAS 抢占 drain 权：抢不到说明在跑的 drain 会带走新帧（单订阅者 FIFO）。 */
	private void kickDrain(Subscription sub) {
		if (sub.draining.compareAndSet(false, true)) {
			submitDrain(sub);
		}
	}

	/**
	 * 提交 drain 任务（R3：池拒绝 = 摘除触发本次提交的订阅者）。
	 */
	private void submitDrain(Subscription sub) {
		try {
			// 日志链路：投递池是裸 executor（MDC 不继承）—— 包装体带提交时刻快照，
			// 并兜底绑本轮的 runId（跨实例唤醒线程上没有锚点）
			this.deliveryExecutor.execute(AiTrace.wrap(() -> {
				AiTrace.bind(this.runId, null);
				drain(sub);
			}));
		}
		catch (RejectedExecutionException ex) {
			sub.draining.set(false);
			evict(sub, "投递池拒绝（池队列满）");
		}
	}

	/**
	 * 把队列里的帧按 FIFO 写给一个订阅者（T3-1：一条慢连接至多占一个工作线程）。
	 *
	 * <p>
	 * <b>R2 收尾顺序（固定，不可调换）</b>：先清 CAS 独占标志，再复查队列非空则重新 CAS
	 * 抢回并补投 —— 若先复查后清标志，"判空 → 他线程入队后 CAS 失败（以为有在跑 drain）
	 * → 清标志退出"会让那一帧永远无人投递（drain 窗口丢失）。
	 *
	 * <p>
	 * <b>修复批第 2 轮的两点加固</b>：① 复查里的 CAS 移到<b>整条条件链的最后</b>（前置条件
	 * 不满足就不再抢权），于是"抢到标志却因守卫判假而放弃提交"的残留窗口消失 —— 旧形态
	 * {@code CAS(...) && (terminal || terminating || subscribers.contains(sub))} 在订阅者刚被
	 * 摘除时会让 {@code draining} 永远停在 true；② 出队元素带实时/回放标记，弹出实时帧时
	 * 递减实时段计数（容量闸的判据，见 {@link #enqueueLive}）。
	 */
	private void drain(Subscription sub) {
		try {
			while (true) {
				QueuedFrame frame = sub.queue.poll();
				if (frame == null) {
					break;
				}
				if (frame.live) {
					liveDequeued(sub);
				}
				if (!writeFrame(sub, frame.payload)) {
					return;
				}
			}
		}
		finally {
			sub.draining.set(false);
			if (canTakeOverDrain(sub) && sub.draining.compareAndSet(false, true)) {
				submitDrain(sub);
			}
			// S1-1：终态（回放批 sub.terminal / 轮终态 sub.terminating）只在队列<b>真正空了</b>
			// 才收尾 —— drain 可能因补投而多跑一轮，早一步 complete 就丢掉队列里还没写出的尾帧。
			if ((sub.terminal || sub.terminating) && sub.queue.isEmpty()) {
				completeOne(sub);
			}
		}
	}

	/**
	 * <b>包②核心</b>：单帧写出（写超时预算的唯一落点）。返回 false = 本订阅者已终结，drain 立即退出。
	 *
	 * <p>
	 * 预算是<b>直执</b>（{@link SseWriteBudget#direct()}，单测/非 Spring）时逐字等价于修复前：
	 * 调用线程内 {@code send}，异常吞在这里（客户端断开不是本轮失败，见下）。
	 *
	 * <p>
	 * 预算是<b>真实预算</b>时（生产装配），写出挪到弹性写出池，本线程最多等
	 * {@code app.ai.sse.write-timeout}：
	 * <ul>
	 * <li>{@code SENT}：照常续投下一帧；</li>
	 * <li>{@code FAILED}：口径与修复前一致 —— 只记 DEBUG + 摘除该订阅者（前端带 lastSeq 重挂补帧）；</li>
	 * <li>{@code TIMED_OUT}：<b>摘除（主动断连）+ 计数 + WARN</b>，见 {@link #evictOnWriteTimeout}。
	 * 处置选择"断开让前端重连"而不是"丢弃该帧继续投"：与既有 S2-1"溢出摘除=主动断连，不静默丢帧"
	 * 同口径 —— 静默丢一帧正文是<b>重挂也补不回来</b>的缺失（B1 起 delta 虽入档，但"丢弃"
	 * 语义会把该帧从实时与归档两条通道一起抹掉），而断连有 ADR-5/re-attach 通道
	 * （前端 onerror/onclose 带 lastSeq 差量补发，seq 无洞）；</li>
	 * <li>{@code INTERRUPTED}：调用线程被中断（本线程中断位已复位）。与 {@code TIMED_OUT} <b>同走摘除路径</b>
	 * —— 该帧已被 {@code drain} 弹出，中断后不会有补投接住它（{@code canTakeOverDrain} 还要求队列非空），
	 * 只"让出 drain"就是静默丢帧（2026-10-10 复核 S3-② 收口）。</li>
	 * </ul>
	 */
	private boolean writeFrame(Subscription sub, String payload) {
		if (!this.writeBudget.budgeted()) {
			try {
				sub.emitter.send(payload);
				return true;
			}
			catch (Exception ex) {
				logWriteFailure(ex);
				evict(sub, "写出失败（客户端断开）");
				return false;
			}
		}
		// 先置"写在飞"：此后任何线程的 completeOne 都不会去碰 emitter 监视器（写线程正持有它），
		// 而是把收尾交接给本 lambda 的 finally（那时 send 已返回/抛出，监视器已释放）。
		sub.completePending = false;
		sub.writeInFlight.set(true);
		SseWriteBudget.Result result = this.writeBudget.write(() -> {
			try {
				sub.emitter.send(payload);
			}
			finally {
				sub.writeInFlight.set(false);
				if (sub.completePending) {
					// 写在飞期间出现的收尾请求（溢出摘除 / 写超时摘除 / 轮终态）：由写出线程补做。
					// 此刻 send 已返回或抛出 ⇒ emitter 监视器已释放 ⇒ complete 不会挂住。
					completeOne(sub);
				}
			}
		});
		return switch (result.outcome()) {
			case SENT -> true;
			case FAILED -> {
				logWriteFailure(result.failure());
				evict(sub, "写出失败（客户端断开）");
				yield false;
			}
			case TIMED_OUT -> {
				evictOnWriteTimeout(sub);
				yield false;
			}
			// 帧已被 drain 从队列弹出：中断后 finally 的补投接不住它（canTakeOverDrain 还要求队列非空），
			// 只"让出 drain"就是静默丢帧 —— 违背 S2-1"不静默丢帧"政策（2026-10-10 复核 S3-② 收口）。
			// 故与 TIMED_OUT 同出口：摘除订阅者（主动断连），前端 onerror/onclose 带 lastSeq 重挂对账
			// （差量补发，seq 无洞）。中断位由 SseWriteBudget.write 复位（interrupt()），语义不变。
			case INTERRUPTED -> {
				evict(sub, "写出被中断（调用线程中断）");
				yield false;
			}
		};
	}

	/** 写出失败的统一日志口径（修复前与修复后同文本；Q8②：它是"这一个订阅者没了"，不是本轮失败）。 */
	private void logWriteFailure(Exception ex) {
		log.debug("SSE 写出失败（客户端断开？）runId={}: {}", this.runId, ex == null ? "写超时" : ex.getMessage());
	}

	/**
	 * 包②：写超时 ⇒ 摘除该订阅者（主动断连），<b>但不在此处 complete</b>。
	 *
	 * <p>
	 * 为什么不能顺手 complete：写线程仍卡在这次写出里并<b>持有 emitter 监视器</b>
	 * （{@code ResponseBodyEmitter#send} 与 {@code #complete} 同为 {@code synchronized}），
	 * 从本线程调用只会把自己一起挂住 —— 那正是本包要消除的形态。于是这里只做三件事：
	 * 计数、日志、关闭投递上下文（摘表 + 清队列 + 计数归零 + 复位标志）；emitter 的收尾交给
	 * "卡住的那次写出返回时"的写出线程（{@link #writeFrame} 的 finally 分支）。
	 *
	 * <p>
	 * 与 {@link #evict} 的分工：本方法不动 {@code evicted} 去重标志，也不碰 emitter ——
	 * 若随后既有的失败/溢出路径再来一次 {@code evict}，其 {@code completeOne} 会因
	 * {@code completed} CAS 而去重，语义不变（前端只看到一次断连）。
	 */
	private void evictOnWriteTimeout(Subscription sub) {
		boolean first = sub.writeTimedOut.compareAndSet(false, true);
		closeSubscription(sub, "写超时（>" + this.writeBudget.timeoutMillis() + "ms）");
		if (first) {
			EVICTED_TOTAL.incrementAndGet();
			log.warn("写超时摘除订阅者 runId={} budget={}ms writeTimeouts={}（前端按 onerror/onclose 重挂差量补发）",
					this.runId, this.writeBudget.timeoutMillis(), SseWriteBudget.writeTimeouts());
		}
		completeOne(sub);
	}

	/**
	 * drain 收尾"是否还值得抢回"的守卫（R2 的第二半 + 修复批第 2 轮的 CAS 后置）。
	 *
	 * <p>
	 * 队列非空且本订阅者未被关闭、且（终态回放 ∨ 轮终态 ∨ 仍在订阅表）。守卫<b>先</b>判、
	 * CAS<b>后</b>抢：条件不成立时标志保持"无人持有"，不会留下 {@code draining=true} 的残影
	 * （残影会让该订阅者永远不再被投递）。
	 */
	private boolean canTakeOverDrain(Subscription sub) {
		return !sub.closed && !sub.queue.isEmpty()
				&& (sub.terminal || sub.terminating || this.subscribers.contains(sub));
	}

	/**
	 * 摘除订阅者（S2-1/R3 的统一出口）：关闭投递上下文（摘表 + 清队列 + 计数归零 + 复位标志）
	 * + 显式 complete。
	 *
	 * <p>
	 * complete 触发前端 onerror/onclose ⇒ 前端带 lastSeq 重挂，差量补发把断点之后的帧
	 * 补回来（seq 无洞）。并发重复摘除由 {@code evicted} 标志去重。
	 */
	private void evict(Subscription sub, String reason) {
		if (!sub.evicted.compareAndSet(false, true)) {
			return;
		}
		EVICTED_TOTAL.incrementAndGet();
		log.warn("摘除订阅者 runId={} reason={}", this.runId, reason);
		closeSubscription(sub, "摘除：" + reason);
		completeOne(sub);
	}

	/**
	 * 完成单个订阅者（摘除收尾 / 终态回放发完）：显式 complete，触发前端重挂。
	 *
	 * <p>
	 * 与 {@link #closeSubscription} 的分工：本方法只负责"终结 emitter"（{@code completed} 是它的
	 * 去重标志），投递上下文的清理由 {@code closeSubscription} 负责 —— 这里补一次清队列与计数归零，
	 * 使"收尾 ⇒ 队列空、实时段计数 0"成为结构性事实（所有调用点的队列本已空，故不丢帧）。
	 *
	 * <p>
	 * <b>包②：写在飞时不得在此 complete</b>。{@code ResponseBodyEmitter#send} 与 {@code #complete}
	 * 同为 {@code synchronized} ⇒ 写线程卡在 {@code send} 里时持有 emitter 监视器，
	 * 任何别的线程直接 {@code complete()} 都会<b>阻塞在监视器上</b>（慢客户端场景下 = 又挂一个线程：
	 * 修复前反映为"投递池线程卡在 socket 写、出帧线程/运行线程卡在 complete"）。故此处只在
	 * {@link Subscription#writeInFlight} 为真时把收尾<b>交接</b>给写出线程
	 * （{@link #writeFrame} 的 finally），由它在 {@code send} 返回/抛出之后补做。
	 * 两次交接之间用"置位 → 复查"消掉丢唤醒窗口：任一侧判定"写在飞已结束"就由它立即收尾，
	 * 两侧都走到时由 {@code completed} 的 CAS 去重。
	 */
	private void completeOne(Subscription sub) {
		if (sub.writeInFlight.get()) {
			sub.completePending = true;
			if (sub.writeInFlight.get()) {
				// 交接给写出线程（它返回时会看到 completePending）
				return;
			}
			sub.completePending = false;
		}
		// 幂等去重（S1-1）：轮终态排空、drain 队空、摘除三条路径可能并发命中同一个订阅者，
		// 重复 complete 会二次触发前端 onCompletion/onError。
		if (!sub.completed.compareAndSet(false, true)) {
			return;
		}
		closeSubscription(sub, "完成订阅者");
		try {
			sub.emitter.complete();
		}
		catch (Exception ex) {
			log.debug("SSE complete 失败 runId={}: {}", this.runId, ex.getMessage());
		}
	}

	/**
	 * T4/T4-3：<b>有时限</b>的挂起等待的绝对到期时刻 = <b>本帧构造时刻的 {@code atMs}</b> + 等待上限。
	 *
	 * <p>
	 * 只有即时执行类前端工具的 {@code frontend_tool_request} 会调用本方法：确认卡与
	 * {@code generative_form} 自 2026-10-20 起没有到期时刻（等人无上限），帧里不带这两个字段。
	 *
	 * <p>
	 * 与 {@code atMs} 同钟单点取值（不另取 {@code System.currentTimeMillis()}），故对任意
	 * TOCTOU 都恒有 {@code expiresAt - atMs == timeoutSeconds * 1000L}（long 精确等式），
	 * 前端 {@code expiresAt - atMs} 折算的剩余时长不会被帧内两次取钟的毫秒差污染。
	 */
	private static long expiresAtEpochMs(Map<String, Object> frame, int timeoutSeconds) {
		Object atMs = frame.get("atMs");
		long base = atMs instanceof Number number ? number.longValue() : System.currentTimeMillis();
		return base + Math.max(0, timeoutSeconds) * 1000L;
	}

	/** T5：工具结果消息的身份（结果铸为消息）—— 由 toolCallId 确定性派生，重放/重挂不变。 */
	private static String toolMessageId(PendingToolCall pending) {
		return "tool-" + pending.getToolCallId();
	}

	/**
	 * 轮终态：<b>先置终态标志停止收新帧，再让每个订阅者把已入队的帧排空后收尾</b>（S1-1 修复批）。
	 *
	 * <p>
	 * 旧实现对每个订阅者直接 {@code completeOne}（立刻 {@code emitter.complete()}）：慢订阅者
	 * 队列里压着的尾帧（{@code done}/{@code error}/{@code suspended}）被确定性丢弃 ——
	 * 完成后的 write 在真实 {@code SseEmitter} 上还会失败（"已完成的响应"）。新实现把"收尾"
	 * 复用到 drain 的队空分支上（与终态回放路径同一口径）：
	 * <ol>
	 * <li>置 {@code terminating}：此后 {@link #emit} 不再向订阅者收新帧（帧照旧取号落档，
	 * 重挂回放仍可见），于是"排空"不会被新帧无限推迟；</li>
	 * <li>逐个订阅者 {@link #completeWhenDrained}：有 drain 在跑就交给它（它排空后在队空分支
	 * 收尾），没有就跑一个，队列已空则立即收尾；</li>
	 * <li><b>不同步等待</b>：生产调用点是轮终态线程的 {@code finally}（{@code AiController} /
	 * {@code ResumeService}），等排空会拖住轮次收尾；"排空才 complete"的语义已由 drain 的
	 * 队空分支保证。</li>
	 * </ol>
	 */
	public void complete() {
		this.terminating.set(true);
		for (Subscription sub : this.subscribers) {
			completeWhenDrained(sub);
		}
	}

	/**
	 * <b>B1（2026-10-20）挂起即断</b>：完成<b>本 run 当前的全部订阅</b>，连接不再挂住等人。
	 *
	 * <p>
	 * 与 {@link #complete()}（轮终态）的分工：本方法<b>不置</b> {@link #terminating} ——
	 * 轮次还在跑（线程仍驻留在 {@code ConfirmGate#await} 里等人工决策），
	 * 之后的帧照旧取号、落档、{@code touchActivity}；只是"当下这批订阅"被收掉。
	 * 于是：
	 * <ul>
	 * <li><b>后续 emit 无副作用</b>：零订阅者时 {@link #emit} 在取号/截断/落档之后直接返回
	 * （扇出循环不执行），与"还没有客户端连上"的形态逐字相同；</li>
	 * <li><b>新订阅仍可接入</b>：{@code GET /events/{runId}} 照常回放归档 + 挂订阅，
	 * 不会被本方法拦住（{@code terminating} 才是"轮次结束、不再收新帧"的开关）；</li>
	 * <li><b>幂等</b>：重复调用只对"仍在订阅表里且未终结"的订阅者动作，已收尾的由
	 * {@link #completeOne} 的 {@code completed} CAS 去重；</li>
	 * <li><b>零订阅者 no-op</b>：订阅表为空时不取号、不写库、不产生任何归档痕迹
	 * （挂起期反复调用不会污染归档窗口）。</li>
	 * </ul>
	 *
	 * <p>
	 * <b>不截断已出的帧</b>：收尾复用到 {@link #completeWhenDrained}（与
	 * {@link #complete()} 同一路径）—— 已入队但还没写出的帧（正是刚发出的
	 * {@code confirm_request}/{@code frontend_tool_request}）先排空，队列空了才
	 * {@code emitter.complete()}。于是与并发出帧的先后关系是确定的：本方法之前由<b>调用线程</b>
	 * emit 的帧必在队列里、必被投递（本方法的调用点就在这一帧 emit 之后），之后的帧只落档
	 * （重挂差量补回，seq 无洞）。
	 *
	 * <p>
	 * <b>订阅表快照在 {@code emitLock} 内取、收尾动作在锁外做</b>：{@code emitter.complete()}
	 * 与 {@code send} 同为 {@code synchronized}，万一与"已弹出帧但还没置 writeInFlight"的写线程
	 * 擦肩，持锁调用会把出帧线程一起拖住（最坏一个写预算）。收尾的幂等性（{@code closed} /
	 * {@code evicted} / {@code completed} 三重去重 + {@code completeWhenDrained} 的 CAS）
	 * 不依赖这把锁，故锁外调用不改变语义。
	 *
	 * @return 本次要求收尾的订阅者数（诊断/断言用；已终结或已摘除的不计）
	 */
	public int completeSubscriptions() {
		List<Subscription> snapshot;
		synchronized (this.emitLock) {
			snapshot = new ArrayList<>(this.subscribers);
		}
		int completed = 0;
		for (Subscription sub : snapshot) {
			if (sub.closed || sub.evicted.get()) {
				continue;
			}
			completeWhenDrained(sub);
			completed++;
		}
		return completed;
	}

	/**
	 * S1-1：把一个订阅者的收尾交给"排空其队列的那一个 drain"。
	 *
	 * <p>
	 * {@code draining} 抢得到 ⇒ 此刻既没有在跑的、也没有排队的 drain 任务（帧不会正写在半途）：
	 * 队列已空就直接收尾，否则自己跑一个 drain 去排空并收尾。抢不到 ⇒ 交给在跑的那一个 ——
	 * 它会在队空分支看到终态标志并 {@code completeOne}（见 {@link #drain} 的 {@code finally}）。
	 */
	private void completeWhenDrained(Subscription sub) {
		// 终态标志先落到订阅者上下文上：在跑的 drain 只在"自己的 finally 里看到该标志且队列已空"
		// 时才收尾，标志晚置一步这一轮 drain 就会空转退出而永不再收尾。
		sub.terminating = true;
		if (sub.evicted.get()) {
			return;
		}
		if (sub.draining.compareAndSet(false, true)) {
			if (sub.queue.isEmpty()) {
				sub.draining.set(false);
				completeOne(sub);
			}
			else {
				submitDrain(sub);
			}
		}
	}

	/**
	 * T3-1：一个订阅者的投递上下文 —— emitter + 帧队列 + CAS drain 独占标志。
	 *
	 * <p>
	 * {@code queue} 本体无界：实时段容量由 {@link #enqueueLive} 显式把守
	 * （缺省 {@link AiProperties.Sse#DEFAULT_DELIVERY_QUEUE_CAPACITY}），
	 * 回放批按 R1 裁决豁免（上界 = 归档窗口 3000）——两类入队分口径，回放不挤实时。
	 *
	 * <p>
	 * <b>单队列 + 入队标记（S1 修复批第 2 轮）</b>：回放批与实时帧共用这一条队列以保住
	 * FIFO 混合序（seq 连续性语义依赖它），但入队元素带实时/回放标记，于是
	 * {@link Subscription#liveQueued} 只数队列里的<b>实时</b>帧 —— 容量闸据此判定，回放 backlog
	 * 不再挤占实时额度。
	 */
	private final class Subscription {

		final SseEmitter emitter;

		final BlockingQueue<QueuedFrame> queue = new LinkedBlockingQueue<>();

		/**
		 * 队列中<b>实时帧</b>的条数（S1 修复批第 2 轮）：容量闸的判据（{@link #enqueueLive}），
		 * 入队 +1、drain 弹出实时帧 -1、清空/收尾时归零；回放批（{@code live=false}）不计入。
		 */
		final AtomicInteger liveQueued = new AtomicInteger();

		/** CAS drain 独占标志：true = 有（或正抢）一个 drain 任务负责清空本队列。 */
		final AtomicBoolean draining = new AtomicBoolean(false);

		/** 摘除去重（溢出 / 写出失败 / 池拒绝 / 轮终态 complete 可能并发命中同一个）。 */
		final AtomicBoolean evicted = new AtomicBoolean(false);

		/**
		 * 投递上下文已关闭（S3-1 修复批第 2 轮）：生命周期回调 / 摘除 / 收尾置位后，
		 * {@link #emit} 的扇出跳过本订阅者（不再入队、不再抢 drain），视为"安全取消挂起 drain"。
		 */
		volatile boolean closed;

		/**
		 * S1-1：本订阅者已被轮终态 {@link SseChatEmitter#complete()} 置终态 —— 与 {@link #terminal}
		 * 同族（"队列排空后收尾"），区别只在来源：{@code terminal} 是回放批自带的终态属性，
		 * 本字段是轮终态运行时置上的（可变的，故不能合成一个 final 字段）。
		 */
		volatile boolean terminating;

		/** 收尾去重（轮终态排空 / drain 队空 / 摘除三条路径可能并发命中同一个订阅者）。 */
		final AtomicBoolean completed = new AtomicBoolean(false);

		/**
		 * 包②：本订阅者有<b>一次写出正在别的线程里飞</b>（{@link #writeFrame} 的预算模式）。
		 *
		 * <p>
		 * 它是"能否就地 {@code emitter.complete()}"的判据：写线程卡在 {@code send} 时持有
		 * emitter 监视器（{@code send}/{@code complete} 同为 {@code synchronized}），
		 * 此时任何别的线程 complete 都会挂在监视器上 —— 见 {@link #completeOne} 的交接逻辑。
		 * 直执模式（无预算）不置位：写出就是本线程，无跨线程交叠。
		 */
		final AtomicBoolean writeInFlight = new AtomicBoolean(false);

		/** 写在飞期间产生的收尾请求：由写出线程在 {@code send} 返回后补做（见 {@link #completeOne}）。 */
		volatile boolean completePending;

		/** 包②：本订阅者是否因<b>写超时</b>被摘除（去重日志/计数用；与 {@link #evicted} 分开口径）。 */
		final AtomicBoolean writeTimedOut = new AtomicBoolean(false);

		/** true = 终态回放订阅（不入订阅表，回放发完即 complete）。 */
		final boolean terminal;

		Subscription(SseEmitter emitter, boolean terminal) {
			this.emitter = emitter;
			this.terminal = terminal;
		}

	}

	/**
	 * 队列元素：帧原文 + <b>所属段</b>（实时 / 回放）。
	 *
	 * <p>
	 * 为什么要标记而不是拆成两条队列：seq 连续性与"到达顺序 = 取号顺序"依赖单订阅者
	 * <b>单条</b> FIFO（回放批在前、实时帧续在其后的混合序），拆双通道就必须自己实现
	 * 两通道的拼接与排空判定。标记法把"哪条闸管它"这一位信息留在元素上，队列结构不变。
	 */
	static final class QueuedFrame {

		final String payload;

		/** true = 实时段（计入 {@link Subscription#liveQueued}）；false = 回放批（豁免实时容量）。 */
		final boolean live;

		QueuedFrame(String payload, boolean live) {
			this.payload = payload;
			this.live = live;
		}

	}

}
