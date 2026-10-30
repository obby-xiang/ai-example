package com.example.configmgr.ai.run;

import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.resultview.ResultView;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultUserText;
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
import java.util.OptionalLong;
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
 * 去重按<b>每订阅者</b>的已投递游标（{@link Subscription#wakeFloor}，回放/实时/唤醒共同推进）：
 * 读取下界取各订阅者游标的最小值，逐订阅者只投其未见的帧；
 * 连接层因此无状态 —— 任何实例都能 serve 任意 run 的 reattach。
 *
 * <p>
 * <b>L-91（2026-10-23）思考段 = 既有三段式 + 一个可选键</b>：上游 {@code reasoning_content}
 * （Spring AI 1.1.7 已挂进 {@code AssistantMessage} 的 metadata，见
 * {@code ReasoningContentUpstreamProbeTest}）不再被丢掉 —— {@link #reasoningDelta(String)}
 * 发 {@code delta{kind:'reasoning'}}，首片自动带出 {@code message_start(kind=reasoning)}，
 * 段在正文开始/本轮收尾时闭合。帧类型集<b>仍是 16</b>（{@code kind} 是 delta 的 additive 可选键，
 * 正文 delta 的键集合逐字不变）—— 因此双端白名单、conformance、桩协议都不必新增帧型。
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
 *
 * <p><b>资源修复批：锁内不得做"唤醒"侧的 Redis I/O。</b>唤醒订阅的生命周期
 * （{@code (P)SUBSCRIBE}/{@code (P)UNSUBSCRIBE}，容器 + 连接，时长不可控）与唤醒发布
 * （{@code PUBLISH}，一次网络往返）全部让到<b>锁外</b>执行：锁内只置位/累计上界
 * （{@link #wakeSyncPending} / {@link #pendingWakeSeq}），最外层锁出口由
 * {@link #flushWakeSyncIfUnlocked()} 补做，目标状态按"当前订阅表 + 当前最大上界"收敛
 * （{@link #applyWakeSync()}），因此<b>事件顺序不变</b>：登记仍发生在回放读之前、
 * 退订仍只发生在最后一个订阅者离开之后、发布仍在落档之后。
 * 仍留在锁内的 I/O 只有两类，且都是<b>正确性必需</b>：①取号/落档（与入队同锁才有
 * "队列顺序 = seq 顺序"）；②{@link #onWake} 的差量读（读取下界必须与入队同拍计算，
 * 否则"读到 5..7、其间本地已投 7"会让 5/6 排在 7 之后 = 乱序）。
 * <ul>
 * <li>每订阅者一条队列（实时段容量 T3-5 定值 256，{@code app.ai.sse.delivery-queue-capacity}
 * 可配；生产者在 {@code emitLock} 内串行入队、单消费者摘除，故容量判定精确）
 * + 一个 CAS drain 独占标志：入队后 CAS 抢占 drain 权，抢不到说明在跑的 drain 会带走新帧
 * —— 单订阅者 FIFO 且一条慢连接至多占一个工作线程，慢订阅者的 TCP 背压不再阻塞整轮
 * （含心跳与同轮其他订阅者）；</li>
 * <li><b>drain 收尾顺序固定（R2 + L-143）</b>：先清 CAS 独占标志、再<b>抢回</b>投递权后判空 ——
 * 倒序会丢"判空 → 他线程入队 CAS 失败 → 清标志退出"的 drain 窗口；而"抢回"是收尾的前置条件：
 * 抢不到说明另一个 drain 可能已取走尾帧、尚未写出，旧 drain 若此刻 {@code completeOne} 会抢在
 * 写出前关闭连接（W-01）。释放后的那一拍还要同时观察<b>终态收尾意图</b>：{@code completeWhenDrained}
 * 的 CAS 失败（本 drain 仍持权）后，责任回到本线程，只看帧面会漏掉它（I-1）；
 * <b>handoff 与 complete 二选一</b>，不靠无限等待兜底；收尾的"判空即关闭"还要与出帧在飞计数
 * 串行（8D/L-143）：已有 {@code emit} 宣告、尚未落档/入队时让位给它的 {@code finally}，
 * 否则刚归档的挂起请求帧会被清队列丢掉（"同毫秒关闭、请求帧未及交付"）；</li>
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
 * （3000，{@code RunStore#events} 天然裁剪）——"队列容量 &lt; 归档窗口"不变量只约束实时段；</li>
 * <li><b>重挂收尾口径（L-143）</b>：快照终态的写入早于 {@code done} 落档，故 {@code terminal}
 * 参数不决定是否订阅 —— <b>归档里有没有终帧</b>才决定"回放完收尾"还是"留挂等实时终帧"；
 * 差量空回放但 {@code lastSeq == done.seq} 由尾部探测关闭。保留挂接的边界：进程若死在
 * "快照终态、done 未落档"之间，连接会一直 loading，<b>由前端看门狗收敛</b>（本轮不加服务端
 * 定时器）。反向窗口（快照非终态、回放已含终帧即收尾）的前提是<b>终帧之后不再追加业务帧</b> ——
 * 该前提在协议侧由"恰好一个 done/error 且它是最后一帧"保证。</li>
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
	 * 本轮的投递身份（投递池线程没有请求身份）。
	 *
	 * <p><b>唯一来源 = 本轮记录</b>（{@code ai:run:{<t>:<u>:run:<id>}} 的 {@code tenantId/userId}）：
	 * 跨实例唤醒创建的写出器从来没有过请求身份，重挂线程的身份也不保证等于记录身份。构造线程的
	 * 当前身份只作<b>取键提示</b>（记录键由身份拼出，没有它就无从读起），<b>绝不</b>当成权威 ——
	 * 记录身份与它不同时以记录为准（"请求身份覆盖记录"会让帧写进另一个租户的键）。
	 *
	 * <p>解析不到（记录还没建 / 0/0 旧轮 / Redis 抖动）就保持 {@code null}：帧面照常，
	 * 但身份相关的键走 legacy 口径 —— 不伪造默认租户，也不回落到调用线程的残留身份。
	 */
	private volatile TenantUser deliveryIdentity;

	/** 构造线程的当前身份（只用于"按身份拼键去读记录"，不作权威身份）。 */
	private final TenantUser requestIdentityHint;

	/**
	 * 首次解析的落定锁：并发调用方必须共享<b>同一次</b>已完成的解析结果。
	 *
	 * <p>"CAS 抢首次、失败方直接读字段"的写法有窄窗竞态：首次解析者还阻塞在记录读取里、
	 * 尚未把结果写回时，失败方会拿到<b>尚未落定</b>的 {@code null}，首批帧/seq/events 因此
	 * 按 legacy 键落档，与本轮记录身份分叉。解析整段在锁内完成，落定前其他调用方在锁上等待。
	 */
	private final Object identityLock = new Object();

	/**
	 * 首次解析是否已落定（只表示"解析完成"，落定值可以是 {@code null}）。
	 *
	 * <p>只在 {@link #identityLock} 内读写 —— 没有它就无法区分"还没解析"与"解析结果就是无身份"。
	 */
	private boolean identityResolved;

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
	 * 超限帧按<b>字段级</b>降采样并挂统一截断字段（{@code truncated/noticeCode/strategy/
	 * originalChars/returnedChars/notice}，{@code truncatedReason} 为兼容别名），
	 * 归档与投递拿到的是同一份截断后帧（重挂回放与实时流不会分叉）。
	 *
	 * <p><b>截断专项（2026-10-25）</b>：流式正文（{@code delta.text}，含 {@code kind=reasoning}）
	 * <b>不进</b>这条字段级截断 —— 它在 {@link #emitDeltaText} 里先按本上限拆成多条既有
	 * {@code delta}（原文/顺序保留，不挂截断标记）；拆分保证每片 ≤ 本上限，{@code apply}
	 * 对所有帧仍是最终守卫（对本路径是 no-op）。装配期下限见
	 * {@link FrameSizeLimiter#MIN_FRAME_BYTES}。{@code tool_result.result} 等不可跨帧续接的
	 * 字段口径不变。
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
	 * B23L1 批①：<b>本实例这条写出器已处置到的最大 seq</b>（出帧在落档之后登记，回放批、唤醒补帧也会推进）。
	 *
	 * <p>
	 * <b>它只是 emitter 级的高水位（诊断/观测），没有任何判据读它</b> —— 重复帧的去重与唤醒补帧的
	 * 读取下界都<b>不</b>由它决定：
	 * <ul>
	 * <li>去重按<b>每订阅者</b>的已投递游标 {@link Subscription#wakeFloor}：自唤醒与本地扇出都先看
	 * 本订阅者是否已过该号（见 {@link #emit} 的扇出循环与 {@link #onWake}），同一帧因此只投一次。
	 * 它不能在发布唤醒<b>之前</b>推进游标 —— 游标只在成功入队之后推进；</li>
	 * <li>唤醒读取下界取各订阅者游标的最小值（{@link #minDeliveredSeq()}），不是本水位（F3：
	 * 另一个订阅者的回放会把共享水位推高，一旦本次唤醒丢失，落后订阅者会永久缺那一帧）。</li>
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

	/**
	 * <b>锁内被让位</b>的唤醒同步（资源修复批）：{@code emitLock} 内只置位，
	 * 由最外层锁出口（{@link #flushWakeSyncIfUnlocked()}）真正执行订阅 / 退订。
	 *
	 * <p>为什么需要这个中转：{@link FrameWakeListener} 背后的订阅是 Redis 容器级 I/O
	 * （{@code (P)SUBSCRIBE}/{@code (P)UNSUBSCRIBE} + 连接建立，时长不可控），在锁内做会把
	 * "一次退订"的时长摊到同一轮的所有出帧上；而"最后一个订阅者离开"这件事恰恰发生在锁内
	 * （{@link #closeSubscription} 的调用点几乎都在临界区里）。
	 */
	private final AtomicBoolean wakeSyncPending = new AtomicBoolean();

	/**
	 * <b>锁内被让位</b>的唤醒发布上界（资源修复批）：锁内只累计要发布的最大 seq，
	 * 由最外层锁出口真正 {@code PUBLISH}。
	 *
	 * <p>与 {@link #wakeSyncPending} 同因（{@code emitDeltaText} / {@code reasoningDelta} /
	 * {@code textStart} 等外层临界区里也会出帧）：只要线程还持有 {@code emitLock}，PUBLISH 就
	 * 不能发 —— 一次网络往返不值得让同轮其它出帧排队。取最大值而不是排队：唤醒载荷只是
	 * "上界 seq"，后到的更大上界覆盖前者，丢掉的中间上界不影响正确性（差量由读侧补齐）。
	 */
	private final AtomicLong pendingWakeSeq = new AtomicLong();

	/** T5：本轮 assistant 正文消息的身份（结果铸为消息 / 消息级渲染去重的判据）。 */
	private final String assistantMessageId;

	/** T5：正文消息边界是否已开（start/content/end 三段式的 start 只发一次）。 */
	private final AtomicBoolean textSegmentOpen = new AtomicBoolean(false);

	/**
	 * <b>L-91</b>：思考段是否已开（{@code message_start(kind=reasoning)} 只发一次）。
	 *
	 * <p>
	 * 与 {@link #textSegmentOpen} 同构：思考段先于正文段出现（模型先想再答），且一段一轮
	 * （思考被打断后继续想的内容并回同一段，不新开一段 —— 前端语义是"这一轮的思考过程"）。
	 */
	private final AtomicBoolean reasoningSegmentOpen = new AtomicBoolean(false);

	/**
	 * <b>L-91</b>：当前思考段已发出的字符数（{@code message_end(chars)} 的对账来源）。
	 *
	 * <p>
	 * 与正文段同口径：{@code chars} = 本段 {@code delta} 文本长度之和 —— 前端按 delta 拼接渲染，
	 * 二者不相等就意味着"前端看到的思考"与"帧契约声明的思考"分家。
	 */
	private final AtomicLong reasoningChars = new AtomicLong(0);

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

	/**
	 * <b>8D / L-143：正在 {@link #emit} 里的线程数</b>（含已进入方法、还没拿到 {@link #emitLock}
	 * 的那一段）。收尾判定只在它归零时才允许"判空即关闭"：
	 * <ul>
	 * <li>归零 ⇒ 没有"已宣告出帧、尚未落档/入队"的请求帧。判空是稳定的；</li>
	 * <li>非零 ⇒ 把关闭让位给最后一个 emit 的 {@code finally}（{@link #closeDeferredSubscriptions}），
	 * 否则会出现"已落档的请求帧被收尾清队列丢掉"的 W-01 同毫秒形态。</li>
	 * </ul>
	 *
	 * <p>
	 * 计数必须在<b>取 {@link #emitLock} 之前</b>递增：一个已经进入 emit、正在等锁的线程同样算
	 * "在飞"——否则收尾持锁判空时看不到它，随后它落档入队，连接已经被关闭。
	 */
	private final AtomicInteger emitting = new AtomicInteger();

	/** "续跑权只读 ⇒ 已丢弃业务帧"的告警节流（每轮一条，避免逐帧刷屏）。 */
	private final AtomicBoolean readOnlyWarned = new AtomicBoolean();

	/**
	 * <b>终态回收钩子</b>（资源修复批）：轮终态且订阅者排空后调用一次，由注册表把它自己从
	 * {@code runId → emitter} 表里摘掉。
	 *
	 * <p>为什么钩子放在写出器里而不是让注册表"定时清理"：终态是写出器<b>自己</b>才知道的事实
	 * （{@link #terminating} + 订阅者排空），注册表只能靠外部出口（{@code RunRegistry#close}）
	 * 通知 —— 漏一个出口就漏一条记录。让"最后一个离开的人关灯"，表里就不会留着"已终态、
	 * 无订阅者"的空壳（它还持有订阅表 / 队列 / 唤醒目标引用）。
	 *
	 * <p>{@code null} = 不回收（单测 / 非 Spring 装配直接 new 出来的写出器）。
	 */
	private volatile Runnable recycleHook;

	/** 回收钩子只跑一次（终态排空可能被多条路径并发观察到）。 */
	private final AtomicBoolean recycled = new AtomicBoolean();

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
		// 复审 I2：运行期 fail-fast 必须<b>独立于 AiProperties 的启动校验</b>成立 ——
		// 装配方（Spring 或测试/非 Spring 路径）只要把上限配到下限以下，这里立刻失败，
		// 而不是等到某一轮正文超限时才发现"无法安全分片"（那时只能截断/空串，两者都被禁止）。
		if (maxFrameBytes < FrameSizeLimiter.MIN_FRAME_BYTES) {
			throw new IllegalStateException("app.ai.result-budget.ui.max-frame-bytes 必须 >= "
					+ FrameSizeLimiter.MIN_FRAME_BYTES + "（当前 = " + maxFrameBytes
					+ "）：低于下限时 delta 帧骨架加一个多字节码点都放不下，正文无法安全分片（拒绝截断/空串兜底）");
		}
		this.maxFrameBytes = maxFrameBytes;
		this.writeBudget = writeBudget != null ? writeBudget : SseWriteBudget.direct();
		// 构造线程（请求/重挂）的身份只作"取键提示"：本轮身份唯一权威来源是记录本身，
		// 解析见 {@link #deliveryIdentity()}（记录身份优先，请求身份不得覆盖记录）。
		this.requestIdentityHint = RequestContextUtils.currentOrNull();
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
		// 资源修复批（纯增量键）：写出池的硬上限与饱和次数 —— "慢连接把写线程占满"的直接观测面。
		metrics.put("writePoolMaxThreads", SseWriteBudget.sharedWriterPoolMaxThreads());
		metrics.put("writeSaturations", SseWriteBudget.saturations());
		return metrics;
	}

	public String runId() {
		return this.runId;
	}

	/**
	 * 本写出器最近一次<b>已处置</b>的业务序号（未出帧 = 0）。诊断/上下文快照用；
	 * 不参与去重与差量下界（那是各订阅者的游标，见 {@link Subscription#wakeFloor}）。
	 */
	public long lastIssuedSeq() {
		return this.lastFanoutSeq.get();
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
		// 资源修复批：轮已终态且订阅者排空 ⇒ 通知注册表摘掉本条目（"最后一个离开的人关灯"）。
		fireRecycleIfTerminal();
		log.debug("订阅者投递上下文已关闭 runId={} reason={}", this.runId, reason);
	}

	/**
	 * 装配终态回收钩子（注册表在新建写出器之后调用一次）。
	 *
	 * <p><b>挂钩时补一次回收判定（窄窗收口）</b>：{@code RunRegistry#of} 是"先 {@code computeIfAbsent}
	 * 建实例、再 {@code setRecycleHook}"两步。若这段时间里另一线程（或异常路径）已经把该实例
	 * {@code complete()} 掉，只赋值的实现会让它<b>永远留在表里</b>（终态、无订阅、钩子从未被调用）。
	 * 这里赋值后立即 {@link #fireRecycleIfTerminal()}：条件已成立就当场回收（{@link #recycled} 的 CAS
	 * 保证只跑一次，重复挂钩不会误摘新条目）。
	 *
	 * @param hook 终态且订阅者排空时执行一次；{@code null} = 不回收
	 */
	public void setRecycleHook(Runnable hook) {
		this.recycleHook = hook;
		fireRecycleIfTerminal();
	}

	/**
	 * 轮终态 + 订阅者排空 ⇒ 触发一次回收钩子（幂等：{@link #recycled} 的 CAS 去重）。
	 *
	 * <p>只在两个条件同时成立时动作：{@code terminating}（轮已终态，不会再有新订阅者需要这个
	 * 写出器）与订阅表为空（没有连接还挂在这个对象上）。任何一个不成立都保持现状 ——
	 * 回收一个还有连接的写出器会把它的帧丢掉。
	 */
	private void fireRecycleIfTerminal() {
		Runnable hook = this.recycleHook;
		if (hook == null || !this.terminating.get() || !this.subscribers.isEmpty()) {
			return;
		}
		if (!this.recycled.compareAndSet(false, true)) {
			return;
		}
		try {
			hook.run();
		}
		catch (RuntimeException ex) {
			log.debug("写出器回收钩子失败 runId={}：{}", this.runId, ex.getMessage());
		}
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
		if (this.wakeListener == null) {
			return;
		}
		if (Thread.holdsLock(this.emitLock)) {
			// 资源修复批：锁内不做 Redis 侧 I/O（订阅/退订是容器级 I/O，时长不可控）——
			// 置位后由最外层锁出口补做（{@link #flushWakeSyncIfUnlocked}）。
			this.wakeSyncPending.set(true);
			return;
		}
		applyWakeSync();
	}

	/**
	 * 锁外的唤醒同步执行点：按<b>当前</b>订阅表收敛到目标状态（幂等，可重复调用）。
	 *
	 * <p>"锁内多次翻转"因此与"锁外一次收敛"等价：目标状态只看 {@code subscribers} 是否为空，
	 * 不看翻转历史 —— 事件顺序（有连接 ⇒ 已登记；无连接 ⇒ 已退订）不受让位影响。
	 */
	private void applyWakeSync() {
		FrameWakeListener listener = this.wakeListener;
		if (listener == null) {
			return;
		}
		if (!this.subscribers.isEmpty()) {
			ensureWakeSubscribed();
		}
		else if (this.wakeRegistered.compareAndSet(true, false)) {
			try {
				listener.unregister(this.runId, this.wakeTarget);
			}
			catch (Exception ex) {
				// 退订失败不改变语义（唤醒只是"补帧优化"，读侧仍按 ?lastSeq 重挂兜底），只留痕。
				log.warn("帧唤醒退订异常 runId={}：{}", this.runId, ex.getMessage());
			}
		}
	}

	/**
	 * 建立本 run 的唤醒订阅（幂等；<b>必须在锁外调用</b>）。
	 *
	 * <p>{@link #replayAndAttachWithStatus} 在取 {@code emitLock} <b>之前</b>调它：
	 * I-1 要求"唤醒订阅先于回放读"（否则回读与登记之间落档的帧谁也接不住），
	 * 而登记本身是 Redis I/O、不能放进锁里 —— 于是顺序变成
	 * 「登记（锁外）→ 挂订阅 + 回放读（锁内）」，两条要求同时成立。
	 *
	 * <p><b>登记身份 = 本轮记录身份</b>（{@link #deliveryIdentity()}）：调用点既可能是带请求身份的
	 * 挂起/重挂线程，也可能是<b>没有请求身份</b>的容器回调线程（{@code onCompletion/onTimeout/onError}
	 * → {@link #closeSubscription} → {@link #applyWakeSync}）。{@code FrameWakeListener#register}
	 * 按调用线程的当前身份拼频道，回调线程现读会是 {@code null} ⇒ 登记到 legacy 频道
	 * {@code ai:wake:<runId>}，与发布侧的身份频道分叉、跨实例唤醒静默缺席。因此这里显式按记录身份
	 * 建立作用域（无身份时清空，不回落到调用线程的残留身份），与投递路径同源。
	 */
	private void ensureWakeSubscribed() {
		FrameWakeListener listener = this.wakeListener;
		if (listener == null) {
			return;
		}
		if (!this.wakeRegistered.compareAndSet(false, true)) {
			return;
		}
		boolean registered;
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(deliveryIdentity())) {
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

	/**
	 * 最外层锁出口补做被让位的唤醒同步（锁内只置过 {@link #wakeSyncPending}）。
	 *
	 * <p>判据用 {@link Thread#holdsLock(Object)}：还持有锁（嵌套在 {@code emitDeltaText} /
	 * {@code reasoningDelta} 这类外层临界区里）就继续等，直到最外层出口。
	 */
	private void flushWakeSyncIfUnlocked() {
		if (Thread.holdsLock(this.emitLock)) {
			return;
		}
		// 订阅/退订（容器级 I/O）与 PUBLISH 都在这里补做：锁内只置位。
		flushWakePublish();
		if (!this.wakeSyncPending.compareAndSet(true, false)) {
			return;
		}
		applyWakeSync();
	}

	/** 当前线程是否正持有 {@code emitLock}（单测锚点：锁内不得有 Redis 退订 / 发布）。 */
	boolean insideEmitLock() {
		return Thread.holdsLock(this.emitLock);
	}

	/**
	 * <b>S5c-2 + T3-1</b>：重挂的原子交接 —— 回放帧收集（{@code LRANGE} 只读）、回放帧入队、
	 * 挂订阅在<b>同一把出帧锁</b>内完成；网络写出统一走异步队列（回放批豁免实时容量，R1 裁决）。
	 *
	 * <p>
	 * 为什么必须原子：{@code 回放 → attach} 两段之间若有实时帧写出，那一帧既不在回放快照里、
	 * 也还没被这个订阅者接住 = 丢帧窗口（且 SSE 丢帧不报错，只会静默少一段）。
	 *
	 * @param terminal 该轮快照是否已终态（只作诊断口径：真正决定"回放完是否收尾"的是
	 * 归档里有没有 {@code done}/{@code error}，见 {@link #replayAndAttachWithStatus}）
	 * @return 实际入队的回放帧数（异步写出前的入队口径）
	 */
	public int replayAndAttach(SseEmitter emitter, Long lastSeq, boolean terminal) {
		return replayAndAttachWithStatus(emitter, lastSeq, terminal).staged();
	}

	/**
	 * 同 {@link #replayAndAttach}，但把"回放是否降级"一并交给调用方（I-1）。
	 *
	 * <p>降级（末号探测/XRANGE 失败）时<b>不得</b>把空结果当"正常空回放"直接 {@code completeOne}：
	 * 终态轮会以"什么都没发生"收尾，前端拿不到 {@code done}。生产入口（AiController）在调用前
	 * 还会用 {@link FrameStore#lastSeqIfKnown} 预检并回 503；这里保留第二道防线。
	 *
	 * <p>
	 * <b>L-143：收尾判据 = 归档里有没有终帧，而不是快照是否终态。</b>终态快照的写入早于
	 * {@code done} 帧的落档（{@code ResilientChatService#finish} 的顺序是
	 * {@code store.save(DONE)} → 记忆/遥测收敛 → {@code out.done} → {@code out.complete}）。
	 * 若在这里按"快照终态"就不挂订阅，回放排空后立即 EOF，随后才落档的 {@code done}
	 * 永远到不了这条连接 —— 重连者看到的是"整轮没有 done"。
	 * <ul>
	 * <li>回放已读到 {@code done}/{@code error}：回放排空后收尾（客户端已拿到终帧）；</li>
	 * <li>回放没有终帧、本轮也还没 {@code complete}：<b>保留原子挂接</b>，等实时
	 * {@code done}/{@code error} 到达后再由 {@link #complete()} 统一收尾；</li>
	 * <li>反向窗口（快照非终态但回放已含终帧）：同样回放完即收尾 —— 否则连接挂在一个
	 * 已经结束的轮次上，永远等不到下一帧。该分支的前提是<b>终帧之后不再追加业务帧</b>
	 * （协议保证恰好一个 {@code done}/{@code error} 且它是最后一帧）；</li>
	 * </ul>
	 * 差量读为空时还要探一格归档尾部（{@link #tailFrameIsTerminal}）：{@code lastSeq}
	 * 恰好等于 {@code done.seq}（客户端收到 done 后连接才断）时，差量里看不到终帧，
	 * 不能据此又挂上去等一个永远不会再发的 done。
	 *
	 * <p>
	 * <b>边界（如实保留）</b>：保留挂接只保证"写方还活着并会发终帧"的正常收尾窗口。进程若死在
	 * 快照终态与 {@code done} 落档之间，连接会一直 loading —— 由<b>前端看门狗</b>收敛，
	 * 本轮<b>不</b>加服务端定时器（也不把无限等待当作其它竞态的兜底）。
	 */
	public ReplayResult replayAndAttachWithStatus(SseEmitter emitter, Long lastSeq, boolean terminal) {
		Subscription sub = new Subscription(emitter, false);
		// 本订阅者的补帧下界 = 客户端已收到的最大 seq（?lastSeq）：唤醒补帧只投 > 它的帧。
		sub.wakeFloor = lastSeq == null ? 0L : Math.max(0L, lastSeq);
		// I-1 + 资源修复批：唤醒订阅<b>先于回放读</b>建立，且在<b>锁外</b>（订阅是 Redis 容器级 I/O）。
		// 顺序：登记（锁外）→ 挂订阅 + 回放读（锁内）。回读与登记之间落档的帧仍能被唤醒补到，
		// 而"一次订阅建立"的时长不再落在 emitLock 上（同轮其它出帧不必等它）。
		ensureWakeSubscribed();
		ReplayResult plan;
		try {
			synchronized (this.emitLock) {
				// I-1：**先挂订阅（含唤醒订阅）再读回放**。旧顺序（读档 → 挂订阅）之间落档的帧
				// 谁也接不住：回放快照已读完、唤醒订阅还没建立，而挂起/终局帧之后不会再有下一次唤醒
				// ⇒ B 侧连接永远等不到那一帧。两段同在 emitLock 内，先挂不会改变"回放帧先于实时帧入队"
				// 的 FIFO 语义（实时入队同样要求这把锁）。
				attachSubscription(sub);
				try {
					plan = stageReplay(sub, lastSeq);
				}
				catch (RuntimeException ex) {
					// M-2：回放读取抛异常（兼容读口 RunStoreFrameStore 不在 FrameStore#readFrom 的
					// "不抛"承诺内）时，刚挂上的订阅必须摘掉：它留在订阅表里会挡住唤醒订阅的释放，
					// 而这条连接根本不会交给客户端（异常让 controller 走错误响应）。
					// 异常原样上抛，由 API 层决定 5xx。
					closeSubscription(sub, "回放读取异常：" + ex.getClass().getSimpleName());
					throw ex;
				}
				if (plan.degraded()) {
					log.warn("回放降级 runId={} terminal={} lastSeq={} staged={}（发 typed error 后收尾，不按正常空回放）",
							this.runId, terminal, lastSeq, plan.staged());
					finishDegradedReplay(sub, plan);
				}
				else {
					boolean archivedTerminal = plan.terminalFrame()
							|| (plan.staged() == 0 && tailFrameIsTerminal(lastSeq));
					// 归档没有终帧且本轮还没收尾 ⇒ 后面还有（或即将有）实时帧，订阅必须留着。
					boolean waitForLiveTerminalFrame = !archivedTerminal && !this.terminating.get();
					if (waitForLiveTerminalFrame) {
						if (plan.staged() > 0) {
							kickDrain(sub);
						}
					}
					else {
						// 回放已含终帧（或本轮已 complete，不会再有新帧）：排空回放后收尾，不悬挂。
						sub.terminating = true;
						if (sub.queue.isEmpty() && this.emitting.get() == 0) {
							completeOne(sub);
						}
						else {
							// 8D/L-143：在飞 emit 尚未落档/入队时不能据"空回放"关闭；kickDrain 排空后由
							// finishDrain / 最后一个 emit 的 finally 收尾。
							kickDrain(sub);
						}
					}
				}
			}
		}
		finally {
			flushWakeSyncIfUnlocked();
		}
		// I-2（评审）：退订/重挂交错 —— 旧连接的退订可能把这期间建立的唤醒订阅撤掉，
		// 于是"回放读之后、唤醒重新登记之前"落档的那一帧没有接收者。顺序很关键：
		// ①先按<b>当前</b>订阅表把唤醒订阅收敛回来（applyWakeSync，锁外，幂等）；
		// ②订阅仍活着时再从归档做一次<b>差量追赶</b>（读取下界 = 各订阅者游标最小值，
		//   逐订阅者按 wakeFloor 去重）。于是追赶读到的是"已重新登记"之后的归档：
		//   追赶之后落档的帧走唤醒、追赶之前落档的帧被读到 ——
		//   退订/重挂交错下回放后不丢帧，也不重复（先去重后入队，游标只增）。
		// 终态回放（已读到 done/error）与降级回放（已排入 typed error）不再追赶：
		// 它们已在收尾，终帧之后按协议不会再有业务帧。
		applyWakeSync();
		if (!sub.closed && !sub.terminating) {
			onWake(this.runId, Long.MAX_VALUE);
		}
		return plan;
	}

	/**
	 * 探一格归档尾部：{@code lastSeq} 那一帧是否是 {@code done}/{@code error}。
	 *
	 * <p>
	 * 只在"差量回放为空且没读到终帧"时调用。{@code lastSeq} = 客户端已收到的最大号；
	 * 若那恰好就是终帧（客户端收完 done 后连接才断），差量读（{@code seq > lastSeq}）自然是空的，
	 * 但本轮<b>确实已经结束</b>，再挂订阅就会一直等一个不会再发的 done。
	 *
	 * <p>探测失败（读抖动/兼容读口抛异常）一律按"没探到"处理：宁可多挂一次，不误关活连接。
	 */
	private boolean tailFrameIsTerminal(Long lastSeq) {
		if (lastSeq == null || lastSeq <= 0L) {
			return false;
		}
		List<Map<String, Object>> tail;
		try {
			tail = this.frames.readFrom(this.runId, lastSeq - 1L, 1);
		}
		catch (Exception ex) {
			log.debug("回放终帧探测失败 runId={} lastSeq={}：{}", this.runId, lastSeq, ex.getMessage());
			return false;
		}
		if (tail == null || tail.isEmpty()) {
			return false;
		}
		String type = String.valueOf(tail.get(0).get("type"));
		return "done".equals(type) || "error".equals(type);
	}

	/**
	 * 归档里是否已有终帧（{@code done}/{@code error}）—— <b>取消收尾的幂等判据</b>。
	 *
	 * <p><b>为什么不能用快照状态代替</b>（L-143 同一事实）：{@code store.save(CANCELLED)} 与
	 * {@code out.done(...)} 是两个存储、两次写。save 成功而 done 被写门/投递丢弃时，快照已终态、
	 * 归档里却没有终帧 —— "快照终态"因此<b>不能</b>证明"终帧已发"（{@code boot.log:6740/6741}
	 * 正是同一判据同时拒绝了两者）。判据只能是归档本身。
	 *
	 * <p><b>口径 = 整个 runId 的归档，不区分驱动代次</b>：取消收尾要回答的是"这一轮有没有任何终帧"
	 * （有 ⇒ 不再补取消终帧）。失败出口<b>不能</b>用它 —— 同一 runId 会被驱动多次
	 * （{@code retry-model-step}），见 {@link #terminalFrameArchivedSinceDriveStart()}。
	 *
	 * @return {@code TRUE} 已落档 / {@code FALSE} 确认没有 / {@code null} 读不出来（读降级或未装配
	 *         帧存储）。调用方<b>不得</b>把 {@code null} 折叠成任何一侧：取消收尾按"继续收尾"处理，
	 *         交棒侧按"未落定"重试。
	 */
	public Boolean terminalFrameArchived() {
		if (this.frames == null) {
			return null;
		}
		FrameStore.ReadResult read;
		try {
			read = this.frames.readFromWithStatus(this.runId, 0L, 0);
		}
		catch (RuntimeException ex) {
			log.debug("终帧归档探测失败 runId={}：{}", this.runId, ex.getMessage());
			return null;
		}
		if (read == null || read.degraded()) {
			return null;
		}
		for (Map<String, Object> frame : read.frames()) {
			String type = String.valueOf(frame.get("type"));
			if ("done".equals(type) || "error".equals(type)) {
				return Boolean.TRUE;
			}
		}
		return Boolean.FALSE;
	}

	// ── 终帧判据的"每次驱动"口径（2026-10-29 返修；复审 I-2） ────────────────

	/**
	 * 本次驱动的归档序号下界：驱动入口（{@code chat} / {@code driveResumed}）记<b>一次</b>；
	 * {@code -1} = 未设定或读不出来（不可知）。
	 */
	private volatile long driveFloorSeq = -1L;

	/**
	 * 标记"<b>一次驱动</b>从这里开始"：记下此刻归档里<b>已落档</b>的最大序号作为本次驱动的下界。
	 *
	 * <h2>为什么归档判据必须按驱动分段</h2>
	 * 归档是 per-runId 的，而同一 runId 会被驱动<b>多次</b>：{@code retry-model-step} 接受 FAILED 轮、
	 * 改回 RUNNING 再驱动一次，归档<b>不清理</b>也不追加代次。只问"归档里有没有任何终帧"会把
	 * <b>上一次</b>驱动的 error 当成"本次已收尾" —— 本次失败的 {@code textEnd}/{@code error} 被整条吞掉
	 * （用户看不到"重试也失败了"，且已落档的 delta 段没有 {@code message_end}）。
	 * 于是判据改成"<b>seq &gt; 本驱动下界</b>的 done/error"：上一次驱动的终帧天然落在下界之内，
	 * 本次驱动自己落的终帧仍会被认出来（同一驱动的重复发射照旧被拦）。
	 *
	 * <p><b>下界取"归档末号"而不是"取号计数器"</b>（{@link FrameStore#lastSeqIfKnown}）：末号只覆盖
	 * 真正落档的帧，取号计数器可能因落档失败而超前，超前会把本次驱动将要发的帧排除在外。
	 * 下界读不出来（读降级 / 未装配帧存储）记 {@code -1} ⇒ 判据返回 {@code null}（不可知）⇒ 调用方照发，
	 * 绝不把"读不到"当成"已收尾"。
	 *
	 * <p><b>不是分布式协议</b>：本下界只解决"同一驱动/同一实例的顺序重复"与"多次驱动的跨代次误判"；
	 * 跨实例并发下"终帧恰好一条"仍需要原子终帧位（与 fence 同事务的条件写），本批<b>未做</b>。
	 */
	public void beginDrive() {
		long floor = -1L;
		if (this.frames != null) {
			try {
				OptionalLong last = this.frames.lastSeqIfKnown(this.runId);
				if (last != null && last.isPresent()) {
					floor = Math.max(0L, last.getAsLong());
				}
			}
			catch (RuntimeException ex) {
				log.debug("驱动下界读取失败 runId={}（按不可知处理，失败出口照发）：{}", this.runId, ex.getMessage());
				floor = -1L;
			}
		}
		this.driveFloorSeq = floor;
		log.debug("驱动开始 runId={} 归档下界={}", this.runId, floor);
	}

	/**
	 * 归档里是否有<b>本次驱动</b>落的终帧（{@code done}/{@code error} 且 {@code seq > 驱动下界}）。
	 *
	 * <p>与 {@link #terminalFrameArchived()} 的分工：那个问"整轮有没有终帧"（取消收尾幂等），
	 * 本方法问"<b>这一次驱动</b>有没有终帧"（失败出口幂等）。
	 *
	 * <p><b>无 seq 的老帧不计入</b>：无法证明它属于本次驱动 ⇒ 宁可多发一条，也不吞掉本次失败。
	 *
	 * @return {@code TRUE} 本次驱动已有终帧 / {@code FALSE} 确认没有 /
	 *         {@code null} 下界或回读不可知（未调 {@link #beginDrive()}、读降级、未装配帧存储）
	 */
	public Boolean terminalFrameArchivedSinceDriveStart() {
		if (this.frames == null) {
			return null;
		}
		long floor = this.driveFloorSeq;
		if (floor < 0L) {
			return null;
		}
		FrameStore.ReadResult read;
		try {
			read = this.frames.readFromWithStatus(this.runId, floor, 0);
		}
		catch (RuntimeException ex) {
			log.debug("驱动内终帧探测失败 runId={} floor={}：{}", this.runId, floor, ex.getMessage());
			return null;
		}
		if (read == null || read.degraded()) {
			return null;
		}
		for (Map<String, Object> frame : read.frames()) {
			Object seq = frame.get("seq");
			if (!(seq instanceof Number number) || number.longValue() <= floor) {
				continue;
			}
			String type = String.valueOf(frame.get("type"));
			if ("done".equals(type) || "error".equals(type)) {
				return Boolean.TRUE;
			}
		}
		return Boolean.FALSE;
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
		return replayToWithStatus(emitter, lastSeq).staged();
	}

	/** 同 {@link #replayTo}，带降级标记；降级空结果不 {@code completeOne}（I-1）。 */
	public ReplayResult replayToWithStatus(SseEmitter emitter, Long lastSeq) {
		Subscription sub = new Subscription(emitter, true);
		try {
			synchronized (this.emitLock) {
				ReplayResult plan = stageReplay(sub, lastSeq);
				if (plan.degraded()) {
					log.warn("回放降级 runId={} lastSeq={} staged={}（发 typed error 后收尾，不按正常空回放）", this.runId,
							lastSeq, plan.staged());
					finishDegradedReplay(sub, plan);
					return plan;
				}
				if (plan.staged() == 0) {
					completeOne(sub);
				}
				else {
					kickDrain(sub);
				}
				return plan;
			}
		}
		finally {
			flushWakeSyncIfUnlocked();
		}
	}

	/**
	 * 回放结果：{@code staged} = 实际入队帧数；{@code degraded} = 本次回放有读失败/窗口跳过；
	 * {@code terminalFrame} = 已读到 done/error 终帧（不得再补 degraded error）。
	 */
	public record ReplayResult(int staged, boolean degraded, boolean terminalFrame) {
	}

	/**
	 * N-2：降级回放必须给终态。
	 *
	 * <p>若已读到 done/error 终帧则不再补（避免重复终帧）；否则补一帧 typed error
	 * （{@code AI_REDIS_UNAVAILABLE / retryable=true / degraded=true}），再让 drain 把队列写完后
	 * {@code completeOne}。终态与非终态都走这条出口 —— 非终态也不能悬挂。
	 */
	private void finishDegradedReplay(Subscription sub, ReplayResult plan) {
		if (!plan.terminalFrame()) {
			Map<String, Object> error = frame("error");
			error.put("runId", this.runId);
			error.put("code", ErrorCodes.AI_REDIS_UNAVAILABLE);
			error.put("message", "AI 服务暂不可用，请稍后重试。");
			error.put("retryable", true);
			error.put("degraded", true);
			try {
				sub.queue.add(new QueuedFrame(this.objectMapper.writeValueAsString(error), false));
			}
			catch (Exception ex) {
				log.error("降级回放错误帧序列化失败 runId={}：{}", this.runId, ex.getMessage());
			}
		}
		// terminating 让回放订阅在队列排空后收尾（无论快照终态与否；L-143 起回放订阅一律挂在
		// 订阅表里，收尾判据统一走 drain 的队空分支）。
		sub.terminating = true;
		kickDrain(sub);
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
	 * 防"发帧后才落档失败"等错位）。回放同时把<b>本订阅者</b>的已投递游标顶到
	 * {@code max(读到的最大号, lastSeq)} —— 随后的跨实例唤醒不会把这些帧再推一遍，
	 * 且"落在归档尾部、回放暂存 0 帧"时游标仍是 {@code lastSeq}（重复帧按 seq 去重）。
	 */
	private ReplayResult stageReplay(Subscription sub, Long lastSeq) {
		long afterSeq = lastSeq == null ? 0L : lastSeq;
		FrameStore.ReadResult read = this.frames.readFromWithStatus(this.runId, afterSeq, 0);
		if (read.degraded() && afterSeq <= 0L && read.frames().isEmpty()) {
			// I-1：全量回放时末号未知且没有可读帧 —— 不能用"最旧窗口"冒充最新帧，宁可这一拍不回放。
			return new ReplayResult(0, true, false);
		}
		List<Map<String, Object>> frames = read.frames();
		int staged = 0;
		boolean terminalFrame = false;
		long maxReadSeq = 0L;
		for (Map<String, Object> frame : frames) {
			String type = String.valueOf(frame.get("type"));
			if ("done".equals(type) || "error".equals(type)) {
				terminalFrame = true;
			}
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
		// 本订阅者的补帧下界 = max(读到的最大号, 它的 ?lastSeq)。后者必须计入：重挂落在归档尾部时
		// 差量读为空（暂存 0 帧），若只按 maxReadSeq 记下界，下一次跨实例唤醒会从 0 补起，
		// 把客户端已见的 1..N 重发一遍（三实例 streaming-kill 实测：lastSeq=2 的重挂先收到 seq=1）。
		// 只推进<b>本订阅者</b>的下界；emitter 级上界仍按实际读到的号走 —— 别人带一个荒谬的 lastSeq
		// 只会让它自己少收，不会饿死同一 emitter 上的其它连接。
		long deliveredUpTo = Math.max(maxReadSeq, lastSeq == null ? 0L : Math.max(0L, lastSeq));
		sub.wakeFloor = Math.max(sub.wakeFloor, deliveredUpTo);
		if (maxReadSeq > 0L) {
			this.lastFanoutSeq.accumulateAndGet(maxReadSeq, Math::max);
		}
		return new ReplayResult(staged, read.degraded(), terminalFrame);
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
		emitDeltaText(null, text);
	}

	/**
	 * <b>L-91：思考段增量</b>（{@code delta{kind:'reasoning'}}）。
	 *
	 * <p>
	 * 为什么是<b>喂键</b>而不是新帧型：帧类型集冻结在 16（{@code FrameTypeSetFrozenTest} 是双端
	 * 比对守卫），而思考段需要的信息只有"这一段属于哪种段"。既有契约里
	 * {@code message_start/message_end} 早已带 {@code kind}（{@link #messageBoundary}），
	 * 缺的只是 <b>delta 侧的同一位</b>，故本方法只给 delta 加<b>可选</b>键 {@code kind}：
	 * <ul>
	 * <li>{@code kind} 缺省 = 正文（{@link #delta(String)} 一字不动，老客户端/旧归档零影响）；</li>
	 * <li>{@code kind='reasoning'} = 思考段增量，前端据此分流到 reasoning 段。</li>
	 * </ul>
	 * 于是"思考链"从模型到屏幕的通路 = 既有三段式 + 一个可选键，双端白名单、conformance、
	 * 桩协议都不必新增帧型。
	 *
	 * <p>
	 * 首片思考自动带出 {@code message_start(kind=reasoning)}（前端不必自己造段边界）；
	 * 空串/空白片<b>不发帧</b> —— Spring AI 对"本片没有 reasoning"用空串占位
	 * （见 {@code ReasoningContentUpstreamProbeTest}），照发会把每个正文分片
	 * 都变成一个空思考 delta。
	 */
	public void reasoningDelta(String text) {
		if (text == null || text.isEmpty()) {
			return;
		}
		// 段开合与分片同锁：并发出帧时"message_start → 各分片 delta → message_end"的先后不错乱。
		try {
			synchronized (this.emitLock) {
				if (this.reasoningSegmentOpen.compareAndSet(false, true)) {
					this.reasoningChars.set(0L);
					messageBoundary("start", "reasoning", null);
				}
				// chars 口径 = 原文长度（分片只改变帧数，不改变文本）：message_end.chars 因此等于
				// 前端按到达顺序拼接的思考文本长度。
				this.reasoningChars.addAndGet(text.length());
				emitDeltaText("reasoning", text);
			}
		}
		finally {
			flushWakeSyncIfUnlocked();
		}
	}

	/**
	 * 正文/思考 delta 的唯一出口：超单帧字节上限时<b>拆成多条既有 {@code delta}</b>（不新增帧型）。
	 *
	 * <p>
	 * 旧口径（T3-10）把超限 {@code text} 按字段降采样并挂截断标记 —— 那是"内容没给全"的语义，
	 * 对流式正文意味着<b>实时、归档、完成后历史三处都永久少字</b>，且重挂差量补不回来
	 * （少的那段从未被发出）。现在同一 {@code messageId} 下按 {@code maxFrameBytes} 切片，
	 * 每片仍走 {@link #emit} 的完整出帧路径（取号 → 落档 → 扇出），因此：
	 * <ul>
	 * <li>每帧仍满足单帧上限（帧体没有变大，只是变多）；</li>
	 * <li>{@code seq} 逐片递增、归档连续，{@code lastSeq} 落在任一片边界都能差量续收；</li>
	 * <li>前端消费假设不变：它本来就按"顺序拼接 delta.text"渲染；</li>
	 * <li>不挂 {@code truncated/noticeCode} 等标记 —— 拆帧后没有内容缺失，标记只留给真缺失的字段
	 * （如 {@code tool_result.result}）。</li>
	 * </ul>
	 *
	 * <p>
	 * 锁内完成拆分 + 连发：分片之间不得插进别的帧（否则同一段文本在 seq 上被切开，前端拼接顺序
	 * 就不再等于原文）。{@link #emit} 对本锁可重入。
	 *
	 * <p>
	 * <b>失败语义（复审 C1/I1）</b>：拆分器在"上限放不下骨架/单码点"时抛
	 * {@link IllegalStateException}（带 type/key/limit/originalChars），本方法<b>不吞</b>它 ——
	 * 异常沿本轮既有失败面（error 帧/失败快照）收尾。装配期已由
	 * {@link FrameSizeLimiter#MIN_FRAME_BYTES} 拦住坏配置，所以这是"永不静默丢字"的最后一道守卫。
	 *
	 * @param kind {@code null} = 正文（不写 {@code kind} 键，老客户端零影响）；
	 *             {@code "reasoning"} = 思考段
	 */
	private void emitDeltaText(String kind, String text) {
		try {
			synchronized (this.emitLock) {
				if (text == null) {
					emitDeltaFrame(kind, null);
					return;
				}
				// 骨架：除 text 值以外的全部字段（含 emit 阶段才补的 runId/seq），seq 取上界 ——
				// 于是每一片的"整帧字节"判定与真实出帧同口径，且真实帧只会更短。
				Map<String, Object> skeleton = frame("delta");
				skeleton.put("messageId", this.assistantMessageId);
				if (kind != null) {
					skeleton.put("kind", kind);
				}
				skeleton.put("text", text);   // 拆分器内部会用空值重算骨架开销
				skeleton.put("runId", this.runId);
				skeleton.put("seq", Long.MAX_VALUE);
				for (String chunk : FrameSizeLimiter.splitStringValue(skeleton, "text", this.objectMapper,
						this.maxFrameBytes)) {
					emitDeltaFrame(kind, chunk);
				}
			}
		}
		finally {
			flushWakeSyncIfUnlocked();
		}
	}

	/** 单条 delta 帧（键集合与截断专项之前逐字一致；思考段只多一个 {@code kind}）。 */
	private void emitDeltaFrame(String kind, String text) {
		Map<String, Object> frame = frame("delta");
		frame.put("messageId", this.assistantMessageId);
		if (kind != null) {
			frame.put("kind", kind);
		}
		frame.put("text", text);
		emit(frame);
	}

	/**
	 * <b>L-91</b>：闭合思考段（{@code message_end(kind=reasoning, chars)}）；未开过段则是幂等空操作。
	 *
	 * <p>
	 * 调用点由"段互斥"隐式保证：{@link #textStart()}（正文段开始前）与 {@link #textEnd(int)}
	 * （本轮收尾 / 纯思考轮）都会先收口思考段，因此<b>不存在</b>"正文段开了、思考段还开着"的形态。
	 *
	 * <p>
	 * <b>复审 I4</b>：CAS 关段、{@code chars} 的读取清零、{@code message_end} 的落档/扇出
	 * <b>必须同处 {@code emitLock}</b>。否则并发的 {@link #reasoningDelta} 可以落在"关段之后、
	 * message_end 之前"（前端看到 end 后还有 delta）或"chars 已读、delta 还没算进 chars"
	 * （{@code message_end.chars} 小于实际拼接长度）。锁与 {@link #emit} 可重入。
	 */
	public boolean reasoningEnd() {
		try {
			synchronized (this.emitLock) {
				if (!this.reasoningSegmentOpen.compareAndSet(true, false)) {
					return false;
				}
				messageBoundary("end", "reasoning", Map.of("chars", this.reasoningChars.getAndSet(0)));
				return true;
			}
		}
		finally {
			flushWakeSyncIfUnlocked();
		}
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
		// L-91：段互斥 —— 正文开始前思考段必须先闭合（前端按段分流 delta，两段不得交叠）。
		// 复审 I4：关思考段与开正文段必须同锁，否则并发的 reasoningDelta 可以插在
		// message_end(reasoning) 与 message_start(text) 之间（前端看到"两段交叠"）。
		try {
			synchronized (this.emitLock) {
				reasoningEnd();
				if (!this.textSegmentOpen.compareAndSet(false, true)) {
					return false;
				}
				messageBoundary("start", "text", null);
				return true;
			}
		}
		finally {
			flushWakeSyncIfUnlocked();
		}
	}

	/** 正文段 end（带本段字符数，便于前端与 done 帧对账）；未开过段则不发（幂等）。 */
	public boolean textEnd(int chars) {
		// L-91：收尾同样先闭合思考段 —— 纯思考轮（一个字正文都没出）若只靠 textEnd 收尾，
		// 思考段会永远停在"开着"的状态（前端会看到一段永不封口的段）。
		// 复审 I4：与 reasoningEnd 同锁，避免并发的思考 delta 落在正文收尾之后。
		try {
			synchronized (this.emitLock) {
				reasoningEnd();
				if (!this.textSegmentOpen.compareAndSet(true, false)) {
					return false;
				}
				messageBoundary("end", "text", Map.of("chars", chars));
				return true;
			}
		}
		finally {
			flushWakeSyncIfUnlocked();
		}
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

	/**
	 * <b>阶段帧</b>（AR-06 / Task 5）：本轮"准备/生成阶段"的可见性，走<b>心跳通道</b>。
	 *
	 * <h2>为什么是心跳而不是新帧型</h2>
	 * 阶段可见性只需要两件事：这个进程还活着、当前在哪个阶段、这个阶段已经用了多久。前两件
	 * 心跳本来就有（{@code phase}），第三件是一个 additive 键 —— 于是帧类型集仍是 16
	 * （{@code FrameTypeSetFrozenTest} 不动），{@code heartbeatSeq} 独立号空间、不落归档的
	 * 既有口径也原样保留。
	 *
	 * <h2>谁发、什么时候发</h2>
	 * 唯一的发送者是 {@link TurnKeepalive}（独立定时器 + 相位切换时的同步公告）——
	 * <b>同步压缩调用自身不主动发帧</b>。
	 * <ul>
	 * <li><b>首轮/续跑一开始</b>：初始相位是 {@code streaming}，调用线程在进入请求前压缩<b>之前</b>
	 *     发一条 {@code elapsedMs=0} 的阶段帧（普通请求因此只有"工作中"，没有压缩/准备阶段条）；</li>
	 * <li><b>真实 L2 模型摘要</b>：{@code ContextCompactor} 的 {@code onSummaryStart} 在第一次真正
	 *     调用摘要模型之前把相位切到 {@code compacting}（只公告一次）；摘要结束立即切回
	 *     {@code streaming}，不等定时器的下一拍；</li>
	 * <li><b>普通请求 / L1 / L3 本地操作</b>：没有任何摘要调用，因此<b>不会</b>出现
	 *     {@code compacting}（L1 清工具结果、L3 硬截断都不公告）；</li>
	 * <li>各相位期间的后续节拍只来自定时器（"压缩不伪称持续发帧"）。</li>
	 * </ul>
	 *
	 * @param phase {@link TurnKeepalive#PHASE_COMPACTING}（真实 L2 模型摘要进行中）/
	 *            {@link TurnKeepalive#PHASE_STREAMING}（模型生成/准备：含首包等待、包间静默、慢工具）
	 * @param elapsedMs <b>本阶段</b>已用毫秒（服务端钟；不伪造进度百分比）
	 * @param attempt 本尝试序号（0 = 尚未进入尝试循环，例如压缩阶段）
	 */
	public void stage(String phase, long elapsedMs, int attempt) {
		Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("phase", phase);
		data.put("elapsedMs", Math.max(0L, elapsedMs));
		data.put("attempt", Math.max(0, attempt));
		heartbeat(data);
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
	 * <b>幂等补发 {@code tool_start}</b>（续跑/接管侧的帧册收口；真实集群 {@code suspend-kill-a} 的缺口）。
	 *
	 * <p>缺陷形态：首轮是"状态先落、帧后发" —— {@code saveSuspended} 之后快照已是合法恢复态，
	 * 而 {@code tool_start} 还没落档。原实例死在这段窗口里，归档里就<b>永远</b>没有这一帧；
	 * 接管方的结算（{@code resolvePending → claimAndExecute}）只发结局帧，于是帧册永久停在
	 * "有 result、没有 start"，前端无法把工具卡与结果帧配对（重放/审计同受影响）。
	 *
	 * <p>判据是<b>事件日志</b>（跨实例同一份，生产 = Redis Stream）：该 {@code toolCallId} 已有
	 * {@code tool_start} ⇒ 一条都不补；没有才补。读降级时<b>保守不补</b> —— "读不到"不是"不存在"的
	 * 证明，宁可留缺口也不重复建段（重复 start 会让前端把同一工具渲染成两段）。
	 *
	 * <p>不阻塞、不新增锁：只是"读归档 + 一次既有出帧"，取号/落档仍在 {@link #emitLock} 内。
	 *
	 * @return {@code true} = 本次补发了一帧；{@code false} = 归档已有 / 读降级 / 无 toolCallId
	 */
	public boolean ensureToolStart(PendingToolCall pending) {
		String toolCallId = pending == null ? null : pending.getToolCallId();
		if (toolCallId == null) {
			return false;
		}
		FrameStore.ReadResult archived;
		try {
			archived = this.frames.readFromWithStatus(this.runId, 0L, 0);
		}
		catch (RuntimeException ex) {
			log.warn("续跑补发 tool_start 前读事件日志失败 runId={} toolCallId={}：{}（保守不补，避免重复帧）",
					this.runId, toolCallId, ex.getMessage());
			return false;
		}
		if (archived.degraded()) {
			log.warn("续跑补发 tool_start 跳过：事件日志差量读降级 runId={} toolCallId={}（读不全 ≠ 没有，不补）",
					this.runId, toolCallId);
			return false;
		}
		for (Map<String, Object> frame : archived.frames()) {
			if ("tool_start".equals(String.valueOf(frame.get("type")))
					&& toolCallId.equals(frame.get("toolCallId"))) {
				return false;
			}
		}
		toolStart(pending);
		return true;
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
	 *
	 * <p>
	 * <b>resultView（S6 批 2）</b>：{@code tool_result} 的 <b>additive 可选键</b>（帧类型集仍是 16，
	 * 见设计稿 §6）。它是<b>给 UI 的那一份</b>结构化投影，与 {@code result} 同源、不进模型载荷；
	 * 不传（= null）时帧的键集合与基线逐字一致 —— 旧客户端/旧归档零影响。
	 */
	public void toolResult(PendingToolCall pending, boolean ok, boolean reused, String result) {
		toolResult(pending, ok, reused, result, null);
	}

	/**
	 * 带视图投影的工具结局帧（见 {@link #toolResult(PendingToolCall, boolean, boolean, String)}）。
	 *
	 * <p>
	 * {@code resultView} 为<b>不可截字段</b>（{@code FrameSizeLimiter} 只截 {@code text}/{@code result}），
	 * 其规模闸必须由构造方在造视图时完成（设计稿 §6.3-3）—— 这里只管挂键，不做二次裁剪。
	 */
	public void toolResult(PendingToolCall pending, boolean ok, boolean reused, String result,
			ResultView resultView) {
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
		// N9/S9：{@code result} 保留中文用户摘要回退，{@code userTextCode} 供前端按原码本地化；
		// {@code outcome}/{@code code}/{@code retryable} 不动，模型侧信封与台账原文也不动。
		// markModelView 仍用<b>原文</b>判定模型视图是否被截断，显示摘要不改变机器可读事实。
		frame.put("result", ToolResultUserText.forUser(code, result));
		frame.put("userTextCode", ToolResultUserText.userTextCode(code));
		if (resultView != null) {
			frame.put("resultView", resultView.toMap());
		}
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
		// 批4-D（用户 2026-10-23 裁决）：不可撤销位随帧下发（additive；false 也显式给，
		// 前端据此决定是否在卡上叠加复选框 —— 普通确认卡因此一字不动）。
		frame.put("irreversible", pending.isIrreversible());
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
		// 与 tool_result 同口径：内部码换用户摘要（回灌成功/取消的原文不受影响）。
		frame.put("result", ToolResultUserText.forUser(code, result));
		frame.put("userTextCode", ToolResultUserText.userTextCode(code));
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
	 * 那时是"前端这一份被压过"，与模型侧无关，{@code noticeCode} 会切成帧截断码。
	 */
	private void markModelView(Map<String, Object> frame, String toolName, String result) {
		if (this.resultLimiter == null || result == null || result.isEmpty()) {
			return;
		}
		ToolResultLimiter.Truncation view = this.resultLimiter.forModel(toolName, result);
		if (!view.truncated()) {
			return;
		}
		// 旧客户端兼容字段：语言中立短句；数字/strategy 只进结构字段与日志（新客户端按 noticeCode 本地化）。
		String notice = FrameSizeLimiter.legacyNotice(TruncationContract.NOTICE_CODE_MODEL_VIEW_TRUNCATED);
		frame.put(TruncationContract.TRUNCATED_FIELD, true);
		frame.put(TruncationContract.NOTICE_CODE_FIELD, TruncationContract.NOTICE_CODE_MODEL_VIEW_TRUNCATED);
		frame.put(TruncationContract.STRATEGY_FIELD, view.strategy());
		frame.put(TruncationContract.ORIGINAL_CHARS_FIELD, view.originalChars());
		frame.put(TruncationContract.RETURNED_CHARS_FIELD, view.returnedChars());
		frame.put(TruncationContract.NOTICE_FIELD, notice);
		frame.put(TruncationContract.LEGACY_REASON_FIELD, notice);
		log.debug("模型侧视图截断 runId={} tool={} originalChars={} returnedChars={} strategy={}", this.runId,
				toolName, view.originalChars(), view.returnedChars(), view.strategy());
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
		error(code, message, null);
	}

	/**
	 * 带 additive 现场键的 {@code error} 终帧（批次2 / AR-05）。
	 *
	 * <p>用途 = 把"这条 error 不是普通失败"这类事实显式告知前端，而不新增帧型、
	 * 不改既有键的语义：例如终态暂不可确认时附 {@code unconfirmed=true}。
	 * 前端只认<b>显式标记</b>（{@code unconfirmed=true} / {@code turnEnd=unconfirmed}），
	 * 绝不拿 {@code AI_REDIS_UNAVAILABLE} 这类真实故障码去猜 —— 猜错会把普通失败说成"可能成功"。
	 *
	 * @param extra additive 键（{@code null}/空 = 与两参重载逐字节相同）
	 */
	public void error(String code, String message, Map<String, Object> extra) {
		Map<String, Object> frame = frame("error");
		frame.put("runId", this.runId);
		frame.put("code", code);
		frame.put("message", message);
		if (extra != null && !extra.isEmpty()) {
			frame.putAll(extra);
		}
		emit(frame);
	}

	/**
	 * <b>续跑派发预算耗尽的中性降级结局</b>：<b>只</b>投递给当下挂着的订阅，不入事件日志。
	 *
	 * <p>场景：决策/回灌已落库，但续跑的每一拍都被 {@code CANCEL_STATE_UNKNOWN}（取消状态此刻不可
	 * 确认）拒绝，{@code app.ai.park.max-dispatch-retries} 走完仍无驱动方。快照仍是
	 * {@code SUSPENDED}、条目已结算 ⇒ 没有人会再发帧；连接若不收尾，客户端只能等自己的超时
	 * （服务端 emitter 没有传输超时）。
	 *
	 * <p><b>为什么是 {@code error} + {@code unconfirmed=true} 而不是新帧型</b>：帧类型集冻结在 16
	 * （{@code FrameTypeSetFrozenTest}），而这条结局的语义正是既有的中性"未确认"（AR-05）：
	 * 不是成功、不是失败、不是取消，工具是否执行<b>仍然未知</b>。中性文案与
	 * {@code recoverable/retryable/degraded} 等 additive 键由调用方给出。
	 *
	 * <p><b>为什么不走 {@link #emit}（即不落档）</b>：归档里出现终帧会把这一轮的帧流"判死" ——
	 * {@code replayAndAttach} 会据此在回放完即收尾，{@code terminalFrameArchived()} 也会让取消收尾
	 * 跳过 {@code done{cancelled:true}}；而这一轮其实仍可被显式恢复。故本帧只投递给当下的订阅
	 * （与 N-2 的降级回放同一手法），随后由 {@link #completeWhenDrained} 排空并收尾。
	 *
	 * <p><b>不置 {@link #terminating}</b>：轮次没有结束 —— 显式恢复之后的实时帧照常扇出，
	 * 之后的重挂也照常回放/挂订阅。
	 *
	 * @param code 复用既有机器码（本路径 = {@code AI_REDIS_UNAVAILABLE}）
	 * @param message 中性用户文案（不声称成功/失败/取消）
	 * @param extra additive 键（{@code null}/空 = 只有 code/message）
	 * @return 收到本帧的订阅者数（{@code 0} = 此刻没有连接，也没有需要解开的等待）
	 */
	public int unresolvedEnding(String code, String message, Map<String, Object> extra) {
		List<Subscription> targets = new ArrayList<>();
		final String payload;
		try {
			synchronized (this.emitLock) {
				if (this.terminating.get() || this.subscribers.isEmpty()) {
					return 0;
				}
				Map<String, Object> frame = frame("error");
				frame.put("runId", this.runId);
				frame.put("code", code);
				frame.put("message", message);
				if (extra != null && !extra.isEmpty()) {
					frame.putAll(extra);
				}
				try {
					payload = this.objectMapper.writeValueAsString(frame);
				}
				catch (Exception ex) {
					log.error("续跑降级结局帧序列化失败 runId={}（本帧跳过）：{}", this.runId, ex.getMessage());
					return 0;
				}
				for (Subscription sub : this.subscribers) {
					if (sub.closed || sub.evicted.get()) {
						continue;
					}
					// 回放批标记：结局帧不受实时队列容量限制（与 finishDegradedReplay 同口径）
					sub.queue.add(new QueuedFrame(payload, false));
					targets.add(sub);
				}
			}
		}
		finally {
			flushWakeSyncIfUnlocked();
		}
		// 收尾动作在锁外做（与 completeSubscriptions 同一理由：emitter.complete() 与 send 同为
		// synchronized，持锁调用会把出帧线程一起拖住）。
		for (Subscription sub : targets) {
			completeWhenDrained(sub);
		}
		log.info("续跑降级结局已投递 runId={} subscribers={} code={}", this.runId, targets.size(), code);
		return targets.size();
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
	 * <b>8D / L-143：出帧的在飞登记 + 收尾让位。</b>
	 *
	 * <p>
	 * 收尾（{@link #completeWhenDrained} / {@link #finishDrain}）在"队列为空"这一拍还要看本计数：
	 * 非零表示已有 emit 宣告、尚未落档/入队。此刻关闭会把随后落档的帧清出队列（请求帧"已归档、
	 * 未送达"），故收尾让位给最后一个 emit 的 {@code finally}（{@link #closeDeferredSubscriptions}）。
	 *
	 * <p>
	 * 递增早于 {@link #emitLock}：等锁的 emit 也算在飞，收尾持锁判空时必须能看到它。
	 *
	 * <p><b>出帧身份 = 本轮记录身份</b>（{@link #deliveryIdentity()}）：{@link #emitInternal} 下游的
	 * 取号/事件日志/活动锚点（以及锁外发布前的累计）全都按<b>调用线程身份</b>拼键。调用方不一定开过
	 * 作用域（定时线程、池化线程、未来新增入口都没有请求身份），漏开就会把帧写进 legacy 键形 ——
	 * 帧看似发出，重挂回放与跨实例唤醒却读不到。这里统一兜底：与投递路径同源、同一份已解析身份，
	 * 嵌套时由作用域关闭还原调用方原身份（{@link RunIdentityScope}）。
	 */
	private void emit(Map<String, Object> frame) {
		this.emitting.incrementAndGet();
		try {
			try (RunIdentityScope.Scope ignored = RunIdentityScope.open(deliveryIdentity())) {
				emitInternal(frame);
			}
		}
		finally {
			this.emitting.decrementAndGet();
			closeDeferredSubscriptions();
		}
	}

	/**
	 * 最后一个在飞 emit 退出后，补做被让位的收尾（否则那个订阅者会一直悬挂到前端看门狗）。
	 *
	 * <p>只在计数归零时执行：若仍有别的 emit 在飞，由最后退出的那个线程负责。
	 */
	private void closeDeferredSubscriptions() {
		if (this.emitting.get() != 0) {
			return;
		}
		for (Subscription sub : this.subscribers) {
			if (sub.closed || sub.completed.get()) {
				continue;
			}
			if (sub.terminating || sub.terminal) {
				completeWhenDrained(sub);
			}
		}
	}

	/**
	 * B23L1 批①：出帧路径 —— <b>取号（FrameStore）→ 截断 → 落档（FrameStore）→ 本地扇出 → 唤醒发布</b>。
	 *
	 * <p>
	 * <b>资源修复批（I-3 口径更正）</b>：唤醒发布（{@code PUBLISH}）已移到 {@code emitLock} <b>之外</b>，
	 * 因此它排在<b>本地扇出之后</b>（旧顺序是"锁内先发布、再扇出"）。真正不可调换的只有
	 * <b>落档早于发布</b>（否则被唤醒者读不到该帧）；"发布早于本地扇出"不再是契约。
	 * 自唤醒的去重由此改由两条既有判据承担：扇出成功入队后推进本订阅者游标
	 * （{@link Subscription#wakeFloor}），以及 {@link #onWake} 的"上界不高于最小游标 ⇒ 无差量"短路。
	 *
	 * <p>
	 * 与 B1 收官形态的三点差异（口径全部来自设计稿 §2.1）：
	 * <ol>
	 * <li><b>取号收敛到事件日志</b>：生产实现 = {@code INCR ai:seq:<runId>}（跨实例原子），
	 * 进程内 {@code AtomicLong} 号锚退役 —— 多实例同时为一轮出帧不再可能撞号；</li>
	 * <li><b>落档后发唤醒</b>：{@code PUBLISH ai:wake:<runId> <上界seq>}（载荷不含帧体），
	 * 让持有该 run 连接的<b>其它实例</b>去事件日志补差量；</li>
	 * <li><b>本地扇出保留</b>（同实例快路径）：唤醒是"补"，本地扇出是"快"，
	 * 两者按<b>每订阅者</b>的已投递游标（{@link Subscription#wakeFloor}，回放/实时/唤醒共同推进）
	 * 去重，不会把同一帧投两遍。</li>
	 * </ol>
	 *
	 * <p>
	 * <b>锁内顺序不可调换</b>：取号 → 截断（判定含 seq，A19）→ 落档 → 入队。
	 * 落档必须拿到截断后的帧（否则事件日志里躺着未截断帧，回放与实时流分叉）。
	 * <b>资源修复批</b>：唤醒发布（PUBLISH）已挪到<b>锁外</b>，但"发布必须在落档之后"不变 ——
	 * 锁内只累计上界（{@link #requestWakePublish}），最外层锁出口才真正发。
	 */
	private void emitInternal(Map<String, Object> frame) {
		frame.putIfAbsent("runId", this.runId);
		// 续租 fencing 收口批：丢租（LOST）/ 设施不可确认（UNAVAILABLE）⇒ 只读恢复。
		// 业务帧一律不取号、不落档、不发布、不扇出 —— 终帧（done/error）也在其中：这一轮已由
		// 新主人接管，旧主人再发终帧会让前端提前收敛到一份作废的回答。
		// 心跳豁免：它不写业务状态（只留"进程还活着"的戳），断了反而让重挂/判活失去证据。
		// null = 未装配租约设施（测试替身 mock 未打桩），按既有语义放行；<b>异常不是 null</b> ——
		// "判定不可读"属于不可知，必须按 UNAVAILABLE 处理（见下面的 catch）。
		String frameType = String.valueOf(frame.get("type"));
		if (!RunStore.HEARTBEAT_TYPE.equals(frameType)) {
			LeaseService.LeaseAuthority authority;
			try {
				authority = this.store.runLeaseAuthority(this.runId);
			}
			catch (RuntimeException ex) {
				// 判定本身失败（租约视图/Redis 抖动）≠ 持有 ≠ 从未持租：按"不可知 ⇒ 只读"处理。
				// 放行的代价是旧主人在事件日志里继续写业务帧、唤醒其它实例、给本地订阅者发终帧 ——
				// 正是 fencing 要拦的那一类写；宁可这一拍不发，也不能吞成 NEVER_HELD / null 放行。
				authority = LeaseService.LeaseAuthority.UNAVAILABLE;
				if (this.readOnlyWarned.compareAndSet(false, true)) {
					log.warn("续跑权判定不可读 runId={}（按 UNAVAILABLE 只读恢复，业务帧不落档）：{}", this.runId,
							ex.getMessage());
				}
			}
			if (authority != null && (authority.readOnly() || !this.store.permittedToWrite(this.runId, "事件帧"))) {
				if (this.readOnlyWarned.compareAndSet(false, true)) {
					log.warn("续跑权 {}：丢弃本轮业务帧（含终帧）runId={} type={} —— 本轮已由新主人接管，"
							+ "本实例只读恢复", authority, this.runId, frameType);
				}
				return;
			}
		}
		// T4-3 后半：atMs 已在 frame() 里定型（帧构造时刻），故这一份帧的序列化原文与落档副本
		// atMs 同值；EventStore 落档侧的 putIfAbsent 只对"绕过 frame() 的直塞帧"生效（兜底）。
		// S5c-3：取号 → 落档 → 入队在同一把锁内完成 —— 多线程（运行线程的心跳 / HTTP 线程的
		// 决策与回灌）并发出帧时，订阅者队列里的先后与 seq 的先后必然一致；网络写出在锁外
		// 由投递池异步完成（T3-1 慢订阅者隔离），单订阅者 FIFO 由 CAS drain 独占标志保证。
		// 资源修复批：唤醒发布（Redis PUBLISH）挪到锁外 —— 锁内只算号，发布值最后由本方法发出。
		long wakeSeq;
		try {
			synchronized (this.emitLock) {
				final byte[] payloadBytes;
				// 本地实时帧的业务号（心跳为 0）：用于把每个订阅者的已投递游标推进到本帧。
				long liveSeq = 0L;
				if (RunStore.HEARTBEAT_TYPE.equals(String.valueOf(frame.get("type")))) {
					// T7：心跳照常广播（下面的 subscribers），但不进事件日志 —— 只留一个轻量活动戳，
					// 让"这轮最近有过活动"仍可跨进程判定。R1-09：心跳只证明<b>进程还活着</b>，
					// 不推进业务活动锚点（否则心跳会掩盖卡死）；僵尸判据读 RunStore#lastActivityAtMs。
					// 事件日志侧的同类跳过见 FrameStore#append（任何其他调用方直接塞心跳帧也不会占位）。
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
					// 流式正文（delta.text）已在 emitDeltaText 里按同一上限拆成多条 delta，这里对本路径
					// 只会是 no-op（拆分保证每片 ≤ 上限）；非 delta 帧（如 tool_result.result）仍按字段降采样。
					// 心跳帧不在此列：它没有 text/result，负载由常量字段构成（事件载荷固定小）。
					payloadBytes = FrameSizeLimiter.apply(frame, this.objectMapper, this.maxFrameBytes);
					if (payloadBytes == null) {
						// 序列化失败：帧不落档、不发订阅者（与 T3-1"锁内只做取号/落档/入队"同口径）
						log.error("SSE 帧序列化失败 runId={} type={}（本帧已跳过）", this.runId, frame.get("type"));
						return;
					}
					// R1-09：任何业务帧（非纯心跳）都推进真实业务活动锚点。必须留在出帧点/锁内 ——
					// 只靠心跳会掩盖卡死，只靠归档尾部则会漏掉"落档失败但帧已发出"的窗口。
					Object atMs = frame.get("atMs");
					this.store.touchBusinessActivity(this.runId,
							atMs instanceof Number number ? number.longValue() : System.currentTimeMillis());
					this.frames.append(this.runId, frame);
					// B23L1：落档之后才允许发布唤醒（否则别的实例被唤醒后读不到这一帧）。
					// <b>资源修复批</b>：PUBLISH 已挪到锁外（{@link #requestWakePublish}）—— 锁内只记上界。
					// 自唤醒的幂等仍由每订阅者游标保证：onWake 只在成功入队后推进 wakeFloor，
					// 本地扇出按同一游标跳过已交付的号（见 liveSeq <= sub.wakeFloor 分支）。
					liveSeq = seq;
					this.lastFanoutSeq.accumulateAndGet(seq, Math::max);
				}
				if (this.subscribers.isEmpty() || this.terminating.get()) {
					// S1-1：轮终态已置 ⇒ 不再向订阅者收新帧（帧照旧取号落档，重挂回放仍可见）——
					// 否则终态之后的新帧会把各 drain 的"排空"无限推迟，收尾永远等不到队空。
					// 注意这里<b>不能</b> return：唤醒（跨实例补帧）仍须发布 —— 上面的发布已挪到锁外。
					wakeSeq = liveSeq;
				}
				else {
					wakeSeq = fanOutToSubscribers(payloadBytes, liveSeq);
				}
			}
		}
		finally {
			flushWakeSyncIfUnlocked();
		}
		requestWakePublish(wakeSeq);
	}

	/**
	 * 把本帧入队给全部订阅者（调用方持 {@code emitLock}），返回该帧的业务号（心跳 = 0）。
	 *
	 * <p>从 {@link #emitInternal} 拆出来只为让"锁内"与"锁外发布"的边界在源码上可见：
	 * 入队必须与取号/落档同锁（FIFO 与 seq 同序），PUBLISH 不必。
	 */
	private long fanOutToSubscribers(byte[] payloadBytes, long liveSeq) {
		final String payload = new String(payloadBytes, java.nio.charset.StandardCharsets.UTF_8);
		for (Subscription sub : this.subscribers) {
			if (sub.closed) {
				// S3-1：生命周期回调已清理该订阅者（COW 快照与摘除的窄窗）—— 不再入队、不再抢 drain
				continue;
			}
			if (liveSeq > 0L && liveSeq <= sub.wakeFloor) {
				// 自唤醒（同步总线 / 进程内分发 / 本实例锁外发布）已经把这一帧投给本订阅者：
				// 游标只在成功入队之后推进（onWake 与本分支同一口径），故"游标已过该号"等价于
				// "这一帧已交付"；此处再投就会让订阅者收到同一个 seq 两次。
				continue;
			}
			if (!enqueueLive(sub, payload)) {
				// S2-1：溢出摘除 = 主动断连 —— 显式 complete 触发前端 onerror/onclose，
				// 前端带 lastSeq 重挂走差量补发（seq 无洞），胜过静默丢帧。
				evict(sub, "实时队列溢出（容量 " + this.queueCapacity + "）");
				continue;
			}
			if (liveSeq > 0L) {
				// 本地已投递 ⇒ 本订阅者的游标推进（唤醒补帧的读下界取各订阅者最小值）。
				sub.wakeFloor = Math.max(sub.wakeFloor, liveSeq);
			}
			kickDrain(sub);
		}
		return liveSeq;
	}

	/**
	 * 请求发布一次唤醒（锁内只登记，锁外真正发）。
	 *
	 * @param seq 本帧的业务号（{@code <= 0} = 心跳，不发布）
	 */
	private void requestWakePublish(long seq) {
		if (seq <= 0L) {
			return;
		}
		this.pendingWakeSeq.accumulateAndGet(seq, Math::max);
		if (Thread.holdsLock(this.emitLock)) {
			return;
		}
		flushWakePublish();
	}

	/** 锁外发布被让位的唤醒上界（幂等；取走即清零，失败只记日志）。 */
	private void flushWakePublish() {
		if (Thread.holdsLock(this.emitLock)) {
			return;
		}
		long seq = this.pendingWakeSeq.getAndSet(0L);
		if (seq > 0L) {
			publishWake(seq);
		}
	}

	/**
	 * 唤醒发布（载荷仅上界 seq；失败只记日志 —— 唤醒可丢，正确性由 ?lastSeq 重连兜底）。
	 *
	 * <p><b>频道身份 = 本轮记录身份</b>：发布被刻意让位到 {@code emitLock} 之外
	 * （{@link #requestWakePublish} / {@link #flushWakePublish}），{@code delta} 等外层临界区的
	 * 发布因此发生在 {@link #emit} 的身份作用域<b>关闭之后</b> —— 只靠 emit 入口兜底不够。
	 * 发布点再按同一份 {@link #deliveryIdentity()} 建立作用域，保证漏开作用域的调用方也不会把
	 * 唤醒发到 legacy 频道。
	 */
	private void publishWake(long seq) {
		FrameWakeBus bus = this.wakeBus;
		if (bus == null) {
			return;
		}
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(deliveryIdentity())) {
			bus.publish(this.runId, seq);
		}
		catch (Exception ex) {
			log.debug("帧唤醒发布失败 runId={} seq={}：{}", this.runId, seq, ex.getMessage());
		}
	}

	/**
	 * B23L1：收到跨实例唤醒 —— 从事件日志补差量，推给本实例该 run 的连接。
	 *
	 * <p>
	 * 去重口径（设计稿 §2.1 第 3 条 + F3 收口）：每个订阅者各记一个<b>已投递游标</b>
	 * （{@link Subscription#wakeFloor}：回放、本地实时扇出、唤醒补帧三条路径共同推进）。
	 * 读取下界取<b>所有活订阅者的最小游标</b>，而不是 emitter 级的 {@link #lastFanoutSeq} ——
	 * 后者会被"另一个订阅者的回放"推进，一旦本次唤醒丢失，落后订阅者就会永久缺那一帧
	 * （独立复审 F3：无游标订阅者缺 seq=4）。取最小游标后：慢订阅者能补齐，快订阅者按自己的
	 * 游标跳过已见帧，互不影响。
	 *
	 * <p>
	 * 唤醒载荷只带上界，中间帧由 {@link FrameStore#readFrom} 给齐，因此"唤醒丢一条"不会造成永久缺口
	 * （下一次唤醒或客户端 {@code ?lastSeq} 重连都能补齐）；同一订阅者的投递顺序仍是归档顺序，
	 * 与本地实时帧共用同一把 {@link #emitLock} 串行入队（FIFO、seq 单调）。
	 */
	void onWake(String runId, long upperBoundSeq) {
		if (!this.runId.equals(runId)) {
			return;
		}
		// 唤醒回调跑在 Redis 订阅线程上，没有请求身份：按本写出器的本轮身份建立作用域
		// （补帧读归档/入队与出帧同一身份口径）。
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(deliveryIdentity())) {
			onWakeScoped(upperBoundSeq);
		}
	}

	private void onWakeScoped(long upperBoundSeq) {
		try {
			synchronized (this.emitLock) {
				if (this.subscribers.isEmpty() || this.terminating.get()) {
					return;
				}
				long from = minDeliveredSeq();
				if (from < 0L) {
					return;
				}
				if (upperBoundSeq > 0L && upperBoundSeq <= from) {
					// 上界不高于"所有订阅者都已投递到的最小游标" ⇒ 没有差量（重复唤醒幂等）。
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
						if (seq <= sub.wakeFloor) {
							// 该订阅者已经见过这一帧（?lastSeq 重挂的客户端）：补帧不得变成重发。
							continue;
						}
						if (!enqueueLive(sub, payload)) {
							evict(sub, "唤醒补帧超出实时队列容量（容量 " + this.queueCapacity + "）");
							continue;
						}
						sub.wakeFloor = seq;
						kickDrain(sub);
					}
					this.lastFanoutSeq.accumulateAndGet(seq, Math::max);
					from = Math.max(from, seq);
				}
			}
		}
		finally {
			flushWakeSyncIfUnlocked();
		}
	}

	/**
	 * 所有<b>活订阅者</b>里最小的已投递游标（唤醒补帧的读取下界）；没有活订阅者返回 {@code -1}。
	 *
	 * <p>为什么是"最小"而不是 emitter 级上界（F3）：另一个订阅者的回放会把共享上界推高，
	 * 一旦本次唤醒丢失，落后订阅者就会永久缺那一帧；按最小游标读，慢的能补齐、快的按自己的
	 * {@code wakeFloor} 跳过已见帧（见 {@link #onWake}）。
	 */
	private long minDeliveredSeq() {
		long min = -1L;
		for (Subscription sub : this.subscribers) {
			if (sub.closed) {
				continue;
			}
			if (min < 0L || sub.wakeFloor < min) {
				min = sub.wakeFloor;
			}
		}
		return min;
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
	 * 本轮的投递身份：<b>只按记录解析</b>（记录身份优先，构造线程身份只用于拼读记录的键）。
	 *
	 * <p>解析顺序：
	 * <ol>
	 * <li>已解析过的记录身份 ⇒ 直接用（结果缓存，后续拍不再读）；</li>
	 * <li>首次解析：用构造线程身份（提示）走<b>显式身份读口</b> {@link RunStore#get(String, long, long)}
	 *     读这一轮记录；记录有身份 ⇒ 用记录里的 {@code tenantId/userId}（即使与提示不同）；</li>
	 * <li>记录<b>不存在</b>（首轮快照还没建）⇒ 用提示身份（那就是即将创建这条记录的身份，不是"覆盖"）；</li>
	 * <li>记录存在但无归属（0/0）或读不出来 ⇒ {@code null}：不 adopt、不回落线程身份。</li>
	 * </ol>
	 * 解析失败不改变投递行为：返回 {@code null} 即"没有身份可恢复"，绝不回落到默认租户。
	 *
	 * <p><b>并发契约</b>：首次解析在 {@link #identityLock} 内完成，落定之后才写回
	 * {@link #deliveryIdentity}；落定前并发的调用方在锁上等待，因此要么直接读到已落定的值，
	 * 要么等到同一次解析完成 —— <b>不存在</b>"返回尚未落定的 {@code null}"的窗口，也不会重复读记录。
	 * 最终无身份（记录 0/0、记录不存在且无提示、读失败）时所有调用方拿到同一个 {@code null}。
	 */
	private TenantUser deliveryIdentity() {
		TenantUser known = this.deliveryIdentity;
		if (known != null) {
			return known;
		}
		synchronized (this.identityLock) {
			if (this.identityResolved) {
				return this.deliveryIdentity;
			}
			TenantUser hint = this.requestIdentityHint;
			TenantUser resolved = null;
			if (this.store != null && hint != null) {
				try {
					RunSnapshot snapshot = this.store.get(this.runId, hint.tenantId(), hint.userId());
					if (snapshot != null) {
						resolved = RunIdentityScope.usable(snapshot.getTenantId(), snapshot.getUserId())
								? new TenantUser(snapshot.getTenantId(), snapshot.getUserId()) : null;
					}
					else {
						// 记录还没建：首轮的帧写在"即将创建这一轮"的身份下（提示 = 请求身份，不是覆盖记录）
						resolved = hint;
					}
				}
				catch (RuntimeException ex) {
					log.debug("SSE 投递身份读取失败（按无身份投递，不伪造默认租户）runId={}：{}", this.runId,
							ex.getMessage());
				}
			}
			this.deliveryIdentity = resolved;
			this.identityResolved = true;
			return resolved;
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
				// 身份链路：投递池线程同样没有请求身份 —— 按本写出器已解析的本轮身份建立作用域。
				try (RunIdentityScope.Scope ignored = RunIdentityScope.open(deliveryIdentity())) {
					drain(sub);
				}
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
	 * <b>R2 收尾顺序（固定，不可调换）</b>：先清 CAS 独占标志，再<b>抢回</b>并复查 —— 若先复查后清标志，
	 * "判空 → 他线程入队后 CAS 失败（以为有在跑 drain）→ 清标志退出"会让那一帧永远无人投递
	 * （drain 窗口丢失）。
	 *
	 * <p>
	 * <b>L-143/W-01：抢回是收尾的前置条件。</b>抢不到标志 ⇒ 已有另一个 drain 持有投递权
	 * （生产者 kick 或上一轮接管），它可能刚 `poll` 走尾帧、还没进 {@code writeFrame}
	 * （{@code writeInFlight} 尚未置位）。此时旧 drain 若再看 {@code queue.isEmpty()} 就会误判
	 * "队列已空"并 {@code completeOne}，抢在写出前关闭 emitter ⇒ 帧静默丢失。故：
	 * <b>抢不到就只退出</b>（收尾归持权者）；抢到且还有帧 ⇒ 提交接管 drain 后也只退出
	 * （handoff 与 complete 二选一）；抢到且队列真空 ⇒ 判空稳定，才允许收尾。
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
			finishDrain(sub);
		}
	}

	/**
	 * drain 收尾：先清投递权，再按"帧面 + 终态收尾意图"决定 handoff / complete / 退出。
	 *
	 * <p>
	 * <b>L-143/W-01：收尾前必须重新持权。</b>抢不到标志 ⇒ 另一个 drain 可能已经 `poll` 走尾帧、
	 * 还没进 {@code writeFrame}（{@code writeInFlight} 尚未置位）；此时若按 {@code queue.isEmpty()}
	 * 收尾，会抢在写出前关闭 emitter（SSE 完成后写必然失败 = 静默丢帧）。故 handoff 与 complete
	 * 二选一，且判空只在本线程持权时做。
	 *
	 * <p>
	 * <b>L-143/I-1：释放后的观察必须同时看终态收尾意图。</b>{@code completeWhenDrained} 先写
	 * {@code sub.terminating=true}、再 CAS 拿投递权；若因本 drain 持权而失败，那次写一定发生在
	 * <b>本线程本次释放之前</b>。本线程在释放<b>之后</b>重新观察，必然读到它（volatile）并回到
	 * 持权拍完成收尾；若由其他持权者接管，它的 {@code finishDrain} 也会重新观察。
	 * 同一拍还要接住"释放瞬间入队、生产者 kick 失败"的帧（R2 的丢唤醒窗口）。
	 */
	private void finishDrain(Subscription sub) {
		sub.draining.set(false);
		while (true) {
			if (!sub.draining.compareAndSet(false, true)) {
				// 别的 drain（生产者 kick 或上一轮接管）持有投递权：帧与收尾都归它。
				return;
			}
			if (canTakeOverDrain(sub)) {
				// 还有帧没写完：交给新 drain，收尾也只能由它做（handoff 与 complete 二选一）。
				submitDrain(sub);
				return;
			}
			boolean ready;
			try {
				synchronized (this.emitLock) {
					// 8D/L-143：判空必须在 emitLock 内，且同时看在飞 emit。否则"判空为真"之后、
					// completeOne 之前落档入队的请求帧会被随后的清队列丢掉（连接关闭顺序反了）。
					ready = !sub.closed && (sub.terminal || sub.terminating) && sub.queue.isEmpty()
							&& this.emitting.get() == 0;
					if (ready) {
						// S1-1：终态只在队列<b>真正空了</b>才收尾。判空与 emit 的落档/入队同锁，
						// 因此"已落档的帧不会被收尾抢跑"。
						completeOne(sub);
					}
				}
			}
			finally {
				flushWakeSyncIfUnlocked();
			}
			if (ready) {
				return;
			}
			sub.draining.set(false);
			// 释放后再观察一拍：帧面（生产者 kick 失败的帧）与终态收尾意图（completeWhenDrained
			// 的 CAS 失败）都只能由本线程接住；都没有才允许退出。终态意图必须在 canTakeOverDrain
			// <b>之后</b>读：它可能正好落在本次观察期间（那时 CAS 失败，责任在本线程）。
			// 8D/L-143：在飞 emit 让位给它自己的 finally（closeDeferredSubscriptions），
			// 这里不把"等它"变成自旋。
			boolean takeOver = canTakeOverDrain(sub);
			boolean terminalIntent = !sub.closed && (sub.terminal || sub.terminating) && this.emitting.get() == 0;
			if (!takeOver && !terminalIntent) {
				return;
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
		// 评审 C1：记下"这次写到底有没有真的跑"。池饱和（提交被拒）与"未启动的 future 被取消"
		// 两种情形下 λ 从不执行 ⇒ 写在飞标志没有写线程来复位 ⇒ 永远不 complete（前端也不会重挂）。
		AtomicBoolean started = new AtomicBoolean();
		SseWriteBudget.Result result = this.writeBudget.write(() -> {
			started.set(true);
			try {
				// 写出跑了弹性写出池线程（不是投递线程）：同一份本轮身份再建立一次作用域。
				try (RunIdentityScope.Scope ignored = RunIdentityScope.open(deliveryIdentity())) {
					sub.emitter.send(payload);
				}
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
				// I5（复审）：超时后 {@code future.cancel(true)} 只对"还没开始"的任务有效 ——
				// 若这次写还排在池队列里从未执行（工作线程被别人占着），cancel 会让它永不运行，
				// λ 的 finally 也就不会复位写在飞标志 ⇒ 同样"永不 complete"。
				// 已在飞的写出（{@code started == true}）不走这里：由写线程返回后自行复位并补做收尾。
				releaseWriteSlotIfNeverStarted(sub, started);
				evictOnWriteTimeout(sub);
				yield false;
			}
			// 写池饱和（硬上限，资源修复批）：这条写<b>没有发生、也不会发生</b>（池满即拒），
			// 本订阅者就是"最后没拿到写线程"的那一条 ⇒ 摘除它（前端 onerror/onclose 带 lastSeq 重挂差量补发）。
			// <b>被摘者可能是健康订阅者</b>（也可能来自任务通道）：饱和时谈不上"只摘慢连接" ——
			// 代价是一次重挂，而不是丢帧（帧都在归档里）。
			// 与 TIMED_OUT 的分工：超时是"这条连接自己卡住"，饱和是"写线程已被更多连接占满" ——
			// 两者都摘除，但日志/计数分开，排障时才分得清是"一条慢连接"还是"一批慢连接"。
			// C1：λ 从未执行 ⇒ 先复位写在飞标志，否则 completeOne 会把收尾交接给一个不存在的写线程。
			case SATURATED -> {
				releaseWriteSlotIfNeverStarted(sub, started);
				evict(sub, "写出池饱和（并发写出已达硬上限 " + SseWriteBudget.sharedWriterPoolMaxThreads() + "）");
				yield false;
			}
			// 帧已被 drain 从队列弹出：中断后 finally 的补投接不住它（canTakeOverDrain 还要求队列非空），
			// 只"让出 drain"就是静默丢帧 —— 违背 S2-1"不静默丢帧"政策（2026-10-10 复核 S3-② 收口）。
			// 故与 TIMED_OUT 同出口：摘除订阅者（主动断连），前端 onerror/onclose 带 lastSeq 重挂对账
			// （差量补发，seq 无洞）。中断位由 SseWriteBudget.write 复位（interrupt()），语义不变。
			// C1：中断可能落在任务<b>还没开始</b>时（{@code cancel(true)} 直接取消未启动的 future）——
			// 那时写线程的 finally 不会跑，同样必须由本线程复位写在飞标志。
			case INTERRUPTED -> {
				releaseWriteSlotIfNeverStarted(sub, started);
				evict(sub, "写出被中断（调用线程中断）");
				yield false;
			}
		};
	}

	/**
	 * 写出<b>从未真正开始</b>时复位"写在飞"标志（评审 C1）。
	 *
	 * <p>为什么必须：{@link #completeOne} 在 {@code writeInFlight} 为真时把收尾"交接给写线程"
	 * （只置 {@code completePending} 就返回）。若这次写<b>根本没跑</b>（池饱和 ⇒ 提交被拒，
	 * 或未启动的 future 被取消），那个写线程不存在，标志就永远为真 ⇒ <b>永不 complete</b> ⇒
	 * 前端收不到 onerror/onclose，也就不会重挂（帧还在归档里，连接却挂死）。
	 *
	 * <p>判据是"任务是否真的跑过"（λ 入口置位的 {@code started}）：跑过 ⇒ 写线程的 {@code finally}
	 * 会复位标志并补做收尾，本线程<b>不得</b>插手（否则会与正在飞的那次写搅在一起）；
	 * 没跑过 ⇒ 只能由本线程复位。调用方紧接着走摘除/收尾，因此复位后立即 {@code completeOne} 生效。
	 *
	 * <p><b>三条调用点（同一缺陷族）</b>：{@code SATURATED}（提交被拒，λ 必然未跑）、
	 * {@code INTERRUPTED}（未启动的 future 被取消）、{@code TIMED_OUT}（超时后 cancel 掉"已接单但
	 * 从未执行"的那次写，评审 I5）。三处的共同后果都是"收尾交接给一个不存在的写线程"。
	 */
	private static void releaseWriteSlotIfNeverStarted(Subscription sub, AtomicBoolean started) {
		if (!started.get()) {
			sub.writeInFlight.set(false);
		}
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
			// 结构化出口（AI 日志最小补齐批）：把"谁被摘了、为什么"从一行中文 WARN 升成可聚合事件
			AiTrace.eventOn(this.runId, null, "sse_detach", "kind", "write_timeout",
					"reason", "write_timeout", "budgetMs", this.writeBudget.timeoutMillis(),
					"writeTimeouts", SseWriteBudget.writeTimeouts(), "subscribers", this.subscribers.size());
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
		AiTrace.eventOn(this.runId, null, "sse_detach", "kind", "evicted", "reason", reason,
				"subscribers", this.subscribers.size());
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
		// 资源修复批：本来就没有订阅者（帧已落档、连接早断）时不会有 closeSubscription 来收尾，
		// 这里补一次回收判定；有订阅者时由最后一个 closeSubscription 触发（同一次 CAS 去重）。
		fireRecycleIfTerminal();
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
		try {
			synchronized (this.emitLock) {
				snapshot = new ArrayList<>(this.subscribers);
			}
		}
		finally {
			flushWakeSyncIfUnlocked();
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
	 *
	 * <p>
	 * <b>8D / L-143：判空与 emit 串行。</b>判空在 {@link #emitLock} 内做，并且要求
	 * {@link #emitting} 归零；否则"判空为真"之后落档的请求帧会被 {@code completeOne} 的清队列
	 * 丢掉（挂起请求帧与订阅者关闭同毫秒的形态）。在飞 emit 时本方法让位，由最后一个 emit 的
	 * {@code finally}（{@link #closeDeferredSubscriptions}）补做收尾。
	 */
	private void completeWhenDrained(Subscription sub) {
		// 终态标志先落到订阅者上下文上：在跑的 drain 只在"自己的 finally 里看到该标志且队列已空"
		// 时才收尾，标志晚置一步这一轮 drain 就会空转退出而永不再收尾。
		sub.terminating = true;
		if (sub.evicted.get()) {
			return;
		}
		while (true) {
			if (!sub.draining.compareAndSet(false, true)) {
				return;
			}
			boolean ready;
			try {
				synchronized (this.emitLock) {
					ready = sub.queue.isEmpty() && this.emitting.get() == 0;
					if (ready) {
						completeOne(sub);
					}
				}
			}
			finally {
				flushWakeSyncIfUnlocked();
			}
			if (ready) {
				return;
			}
			if (!sub.queue.isEmpty()) {
				submitDrain(sub);
				return;
			}
			sub.draining.set(false);
			// 队列空但仍有 emit 在飞：让位给它的 finally。若它恰好在本线程释放投递权前后退出，
			// 则由本线程重试（emit 的 finally 可能因 CAS 失败而没有接住这一次收尾）。
			if (this.emitting.get() != 0) {
				return;
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

		/**
		 * 本订阅者的<b>已投递游标</b>（收到的最大 seq；0 = 全量）。三条投递路径共同推进：
		 * 回放（{@code stageReplay}）、本地实时扇出（{@code emit}）、唤醒补帧（{@code onWake}）。
		 *
		 * <p>作用有两个：① 客户端带 {@code ?lastSeq=N} 重挂、而归档此刻没有新帧时，回放暂存 0 帧
		 * ⇒ 游标仍须是 N（否则下一次唤醒把 1..N 重发一遍）；② 唤醒补帧的<b>读取下界</b>取所有活订阅者
		 * 游标的<b>最小值</b>（{@link SseChatEmitter#minDeliveredSeq()}）—— 别的订阅者回放推进共享上界
		 * 不会让落后的订阅者永久缺帧（独立复审 F3）。
		 */
		volatile long wakeFloor;

		/**
		 * true = 回放发完即收尾的投递上下文（{@link #replayToWithStatus} 的一次性回放，
		 * 不入订阅表）。{@link #replayAndAttachWithStatus} 自 L-143 起一律以 false 构造 ——
		 * 它是否收尾由"归档里有没有终帧 + 本轮是否已 complete"决定（见该方法）。
		 */
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
