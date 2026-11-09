package com.example.configmgr.ai.run;

import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.history.TranscriptEntries;
import com.example.configmgr.ai.history.TranscriptEntry;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.observe.LogSampling;
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
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
 * Redis Stream {@code ai:xevents:<runId>}），
 * 于是"进程在挂起期死亡"不再连 SSE 时序一起丢，重挂时先回放外置帧再续接实时帧。</li>
 * </ol>
 *
 * <p>
 * <b>B23L1 批①（2026-10-21）多实例</b>：出帧 = 原子落档（{@link FrameStore#appendAndAllocateSeq}：
 * 定最终号 + 追加 Stream 记录同一次 Redis 脚本，截断在号占位下完成）→
 * {@code PUBLISH ai:wake:<runId>（仅上界 seq）} → 本地扇出（同实例快路径）。
 * 其它实例的连接由 {@link FrameWakeListener} 收到唤醒后从事件日志补差量，
 * 去重按<b>每订阅者</b>的已投递游标（{@link Subscription#wakeFloor}，回放/实时/唤醒共同推进）：
 * 读取下界取各订阅者游标的最小值，逐订阅者只投其未见的帧；
 * 连接层因此无状态 —— 任何实例都能 serve 任意 run 的 reattach。
 *
 * <p>
 * <b>Task 2（2026-10-30）落档号收口：号与归档同一拍</b>：出帧只用
 * {@link FrameStore#appendAndAllocateSeq(String, java.util.Map, FrameStore.PayloadFactory)} ——
 * 实现在<b>一次 Redis 脚本</b>里定最终号并追加 {@code <seq>-0}，于是
 * <b>分配序 == 归档序 == 交付序</b>，不再有"先取低号、被越过后改高号"的中间态（那一形态会让
 * 实时流与回放两个号，并让"低号后到"的帧被所有已越过它的游标永久跳过）。
 * <b>没有归档就没有交付</b>：落档返回"未归档"时本帧不扇出、不推进游标、不发唤醒。
 * <b>跳号是正常形态</b>（降级重试/遗留墙钟 ID 都会跳）；单实例无竞争时号看起来连续，
 * 那<b>只是单实例形态</b> —— conformance 的 "+1 连续"断言不等于跨实例连续保证，
 * 跨实例只保证"每轮唯一、单调、按归档序交付"。
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
 * {@code emitLock} 内只保留：取号、落归档、<b>activity 的 1s 节流判定</b>（P3-04：
 * Redis 写已移到锁外；判定留在出帧点，僵尸判定的输入不随投递延迟漂移）、<b>帧入各订阅者队列</b>。
 * 网络写出全部走
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
 * 仍留在锁内的 I/O 只有三类，且都是<b>正确性必需</b>：①取号/落档（与入队同锁才有
 * "队列顺序 = seq 顺序"）；②{@link #onWake} 的差量读（读取下界必须与入队同拍计算，
 * 否则"读到 5..7、其间本地已投 7"会让 5/6 排在 7 之后 = 乱序）；③{@link #fanOutToSubscribers}
 * 交付前的<b>低号补齐读</b>（{@link #catchUpFramesLocked}：按各订阅者游标最小值一次读出缺口，
 * 与入队同锁才能保证"先补低号、再投本帧"）。三者都是"读 + 入队"的同一原子拍，移出锁会重开乱序窗口。
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
 * <li><b>drain 每拍有界（P1-07 / I3）</b>：一个 drain 任务不再"清空整条队列"才退出 ——
 * 直执模式每拍最多写 {@link #DRAIN_BATCH_FRAMES} 帧或占用 {@link #DRAIN_SLICE_MILLIS}；
 * 生产装配的单帧写出改成<b>异步</b>（{@link SseWriteBudget#writeAsync}）：投递线程不等
 * {@code future.get(15s)}，写出完成/超时回调继续下一帧。15s 预算、FIFO、CAS 独占权与
 * "正常慢客户端不提前驱逐"不变；</li>
 * <li><b>回放分页（P1-06 / I1 / I4）</b>：{@code replayAndAttach} 不再整窗读取 —— 首页用
 * {@link FrameStore#replayMeta}（廉价元数据）+ {@code readRawFrom}（XRANGE + COUNT）真实分页，
 * 后续页同样 COUNT 读取；读在 {@code emitLock} <b>锁外</b>完成，回锁校验 closed/游标后才按
 * {@link #REPLAY_ENQUEUE_FRAMES} 帧 / {@link #REPLAY_ENQUEUE_BYTES} 字节（UTF-8）分批入队。
 * 剩余页由 drain 在队空时续读，<b>读到底（{@code replayActive=false}）才允许终态 complete</b>。
 * 回放未读到底期间实时业务帧不走直接扇出（由分页通道按序列补齐），因此不丢帧、不重复、不乱序；</li>
 * <li><b>回放批豁免（R1 裁决）</b>：回放入队不受实时容量 256 约束，上界 = Stream 归档窗口
 * （{@link RedisFrameStore#STREAM_WINDOW}，写侧 {@code MAXLEN ~} 天然裁剪）——
 * "队列容量 &lt; 归档窗口"不变量只约束实时段；分页只改变"一次放多少"，不改变豁免口径；</li>
 * <li><b>重挂收尾口径（L-143）</b>：快照终态的写入早于 {@code done} 落档，故 {@code terminal}
 * 参数不决定是否订阅 —— <b>归档里有没有终帧</b>才决定"回放完收尾"还是"留挂等实时终帧"；
 * 差量空回放但 {@code lastSeq == done.seq} 由尾部探测关闭。保留挂接的边界：进程若死在
 * "快照终态、done 未落档"之间，<b>离线重挂</b>在回放读到底后进入终帧宽限 —— 每
 * {@link #UNARCHIVED_TERMINAL_GRACE_SECONDS} 秒复查一次（本实例驱动代次 + 快照仍为终态），
 * 队列/在飞 emit 忙时最多重排 {@link #UNARCHIVED_TERMINAL_GRACE_RETRIES} 次；仍无终帧则复用
 * {@code terminalUnarchived} 链路给当前订阅补合成 typed error 并收流（不置 emitter 级终态），
 * 不再依赖前端看门狗。反向窗口（快照非终态、回放已含终帧即收尾）的前提是<b>终帧之后不再追加业务帧</b> ——
 * 该前提在协议侧由"恰好一个 done/error 且它是最后一帧"保证。</li>
 * </ul>
 *
 * <h2>F1：非驱动方 attach 连接的本地保活（2026-10-31）</h2>
 * 驱动在 A、客户端重挂到 B（非粘滞代理下任意实例都可 serve reattach）时，心跳<b>不落档、不发布唤醒</b>
 * ⇒ A 处于请求前压缩（L2 摘要单次最长 60s）、慢工具或上游静默时，B 的 attach 连接一帧都收不到，
 * 前端 40s 首帧 / 45s 帧间看门狗于是把正常轮次判死。<b>本实例不是该 run 驱动方</b>（{@link #beginDrive()}
 * 从未在本写出器上调用）时，本类为该写出器起<b>一个</b>本地保活节拍（共享单线程调度器，见
 * {@link #sharedAttachKeepaliveScheduler()}），发无业务 {@code seq} 的 {@code heartbeat}：
 * 不入档、不取业务号、不写 {@code ai:beat}/{@code ai:activity}、不发唤醒 —— 归档、序号、有序交付
 * 与真实驱动路径全部不变。终帧投出（含回放/唤醒补到的终帧）、{@link #complete()}、最后一个订阅者
 * 离开、或本实例转为驱动方都会停机；节拍体还会自检条件并自我取消（不漏停，也不"每 attach 一个定时器"）。
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
 * <li><b>写出移出投递池</b>：{@link #beginBudgetedWrite} 把单帧 {@code send} 提到弹性写出池
 * （{@link SseWriteBudget#writeAsync}），<b>投递线程立即交还</b>，不等 {@code future.get}；
 * 本线程最多只做"提交 + 交还"，完成/超时（{@code app.ai.sse.write-timeout}，缺省 15s，
 * 取值论证见 {@link SseWriteBudget#DEFAULT_TIMEOUT_MILLIS}）由回调继续下一帧或摘除
 * ⇒ 投递池不再因慢连接而饱和；</li>
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
     * 进程级共享资源的实例所有者；池本体只在所有者构造器中创建并随所有者关闭。
     */
    private static volatile SharedResources sharedResources;

    private static final class SharedResources {

        private final ThreadPoolExecutor deliveryPool;

        private final ScheduledExecutorService attachKeepaliveScheduler;

        SharedResources(int poolSize, int poolQueueCapacity, ScheduledExecutorService existingAttachScheduler) {
            if (poolSize > 0) {
                this.deliveryPool = new ThreadPoolExecutor(poolSize, poolSize, 60L, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(poolQueueCapacity), Executors.defaultThreadFactory(),
                        new ThreadPoolExecutor.AbortPolicy());
                this.deliveryPool.allowCoreThreadTimeOut(true);
            } else {
                this.deliveryPool = null;
            }
            if (existingAttachScheduler != null) {
                this.attachKeepaliveScheduler = existingAttachScheduler;
            } else {
                ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
                        runnable -> Executors.defaultThreadFactory().newThread(() -> {
                            // 共享调度线程不得继承首个触发者的身份信息。
                            try (RunIdentityScope.Scope ignored = RunIdentityScope.denied()) {
                                runnable.run();
                            }
                        }));
                scheduler.setKeepAliveTime(60L, TimeUnit.SECONDS);
                scheduler.allowCoreThreadTimeOut(true);
                scheduler.setRemoveOnCancelPolicy(true);
                scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
                this.attachKeepaliveScheduler = scheduler;
            }
        }

    }

    /**
     * 写侧单帧预算的<b>实际值</b>（毫秒，装配时由 {@code app.ai.sse.write-timeout} 注入）。
     *
     * <p>超时面治理批① 第 3 项：{@code /api/ai/health} 的 {@code writeTimeoutMs} 必须读配置面的真实值，
     * 不能恒报编译期缺省 15000（否则改配置后可观测面说谎）。未装配 ⇒ 缺省值。
     */
    private static volatile long sharedWriteTimeoutMs = SseWriteBudget.DEFAULT_TIMEOUT_MILLIS;

    /**
     * 投递池指标：累计摘除的订阅者数（溢出 / 写出失败 / 池拒绝共用，供 /api/ai/health）。
     */
    private static final AtomicLong EVICTED_TOTAL = new AtomicLong();

    // ── 背压与回放分页的显式有界参数（P1-06 / P1-07） ─────────────────────────

    /**
     * 回放<b>每次归档读取</b>的帧数上限（锁内一次只读一页，读入后暂存在订阅者上下文里）。
     *
     * <p>旧实现一次 {@code readFrom(..., 0)} 读整窗（最多 6000 帧）并逐帧入队，读 + 序列化 +
     * 入队全程持 {@code emitLock}：同 run 的实时帧/终帧被回放时长堵住，内存也随重挂连接数乘性放大。
     * 现在读取按页、入队按"帧数 + 字节"双预算分批，每页读完即释放锁。
     */
    static final int REPLAY_PAGE_FRAMES = 256;

    /**
     * 回放<b>每次持锁入队</b>的帧数上限（与字节预算共同约束一次临界区的工作量）。
     */
    static final int REPLAY_ENQUEUE_FRAMES = 64;

    /**
     * 回放<b>每次持锁入队</b>的字节预算（UTF-8 编码后的字节数）。
     */
    static final long REPLAY_ENQUEUE_BYTES = 512L * 1024L;

    /**
     * 归档只读探测的帧预算：{@link FrameStore#WAKE_BATCH_LIMIT} 是归档窗口（6000），
     * {@code MAXLEN ~} 是<b>近似</b>裁剪（可能多留约 1%），这里加冗余覆盖“窗口 + 冗余”，
     * 保证从“末号 − 预算”起扫到末号能覆盖整个幸存窗口。
     */
    static final int ARCHIVE_SCAN_BUDGET = FrameStore.WAKE_BATCH_LIMIT + 256;

    /**
     * 单拍 drain 允许读取的回放页数上限：只读不投（全被过滤）时也不会在一拍里空转全窗。
     */
    static final int REPLAY_PAGES_PER_BEAT = 4;

    /**
     * 单拍 drain 的帧数上限（P1-07）：到点后经 {@link #finishDrain} 的接管分支重新排队。
     *
     * <p>旧实现一个 drain 任务就是"清空整条队列"的循环：少量慢连接即可长期占满投递池线程，
     * 健康 run 的帧被饿死。现在每拍只处理有界帧数/时间片，剩余帧由新的 drain 任务继续
     * （FIFO 与 CAS 独占权不变，"到点"不会插队、不会丢帧）。
     */
    static final int DRAIN_BATCH_FRAMES = 64;

    /**
     * 单拍 drain 的时间片（毫秒）：与帧数上限先到者生效。
     */
    static final long DRAIN_SLICE_MILLIS = 25L;

    /**
     * 业务活动锚点（{@code ai:activity}）与心跳活动戳（{@code ai:beat}）的写侧节流窗口。
     *
     * <p>旧实现每个非心跳帧都写一次 {@code ai:activity}（单键 Lua，一次 Redis 往返）；流式正文
     * 每片都写等于把出帧热路径变成 Redis 热路径。僵尸判据的窗口是分钟级（缺省 180s），
     * 约 1s 的写精度远高于它所需 —— 节流后"业务前进"语义不变，只是同一秒内的重复写合并。
     */
    static final long ACTIVITY_TOUCH_INTERVAL_MS = 1_000L;

    /**
     * M6：离线重挂（快照终态、归档暂无终帧）的终帧宽限（秒）。
     *
     * <p>正常顺序是 {@code save(终态) → done 落档}，同线程毫秒级；宽限窗口只为"写方已死/已离开"
     * 的离线重挂兜底。窗口内 {@code done} 落档即可取消合成终帧（{@code terminalFrameSeen}）。
     */
    static final long UNARCHIVED_TERMINAL_GRACE_SECONDS = 3L;

    /**
     * 宽限到点若队列/在飞 emit 仍忙：有界重排次数（每次再等一个宽限窗口，总上限 ≈ 12s）。
     */
    static final int UNARCHIVED_TERMINAL_GRACE_RETRIES = 3;

    private final String runId;

    private final RunStore store;

    /**
     * B23L1 批①：事件日志（取号 + 落档 + 按号回读）。
     *
     * <p>生产装配恒为 {@link RedisFrameStore}（单/多实例同一实现）；写出器没有第二套事件日志实现。
     */
    private final FrameStore frames;

    /**
     * B23L1 批①：唤醒发布面（null = 不跨实例广播：单实例/单测路径）。
     */
    private final FrameWakeBus wakeBus;

    /**
     * B23L1 批①：唤醒订阅面（null = 不订阅；重挂仍可由事件日志回放补帧）。
     */
    private final FrameWakeListener wakeListener;

    private final ObjectMapper objectMapper;

    /**
     * T3-1：本写出器的投递执行器。生产 = 共享有界投递池；轻装配（测试）= 同步直执。
     */
    private final Executor deliveryExecutor;

    /**
     * 本轮的投递身份（投递池线程没有请求身份）。
     *
     * <p><b>唯一来源 = 本轮记录</b>（{@code ai:run:{<t>:<u>:run:<id>}} 的 {@code tenantId/userId}）：
     * 跨实例唤醒创建的写出器从来没有过请求身份，重挂线程的身份也不保证等于记录身份。构造线程的
     * 当前身份只作<b>取键提示</b>（记录键由身份拼出，没有它就无从读起），<b>绝不</b>当成权威 ——
     * 记录身份与它不同时以记录为准（"请求身份覆盖记录"会让帧写进另一个租户的键）。
     *
     * <p>解析不到（没有取键提示 / 记录 0/0 / 读记录失败且无提示）⇒ {@code null} =
     * <b>身份不可确认</b>：本拍不落档、不投递、不发布唤醒，按故障恢复处理（断连/重挂回放），
     * 绝不退回无身份键或裸 runId 频道。
     */
    private volatile TenantUser deliveryIdentity;

    /**
     * 记录存在但归属不可用（0/0 旧快照）⇒ 身份永久不可确认：不缓存提示身份、不回退任何键族。
     */
    private volatile boolean identityInvalid;

    /**
     * 构造线程的显式请求身份（只用于"按身份拼键去读记录"，不作权威身份；null = 无法拼键）。
     */
    private final TenantUser requestIdentityHint;

    /**
     * 首次解析的落定锁：并发调用方必须共享<b>同一次</b>已完成的解析结果。
     *
     * <p>"CAS 抢首次、失败方直接读字段"的写法有窄窗竞态：首次解析者还阻塞在记录读取里、
     * 尚未把结果写回时，失败方会拿到<b>尚未落定</b>的 {@code null}，首批帧/seq/events 因此
     * 无法判定身份。解析整段在锁内完成，落定前其他调用方在锁上等待。
     */
    private final Object identityLock = new Object();

    /**
     * 身份记录读失败的基础退避（有界：1s 起，按连续失败指数增长）。
     */
    static final long IDENTITY_READ_BACKOFF_BASE_MS = 1_000L;

    /**
     * 身份记录读失败的最大退避（30s）：Redis 抖动期每个写出器最多约 2 次/分钟的记录读。
     */
    static final long IDENTITY_READ_BACKOFF_MAX_MS = 30_000L;

    /**
     * <b>C-63：身份读异常的有界退避</b>（值 = 下一次允许读记录的时刻，epoch 毫秒；0 = 不退避）。
     *
     * <p>为什么需要：首次解析在 {@link #identityLock} 内做一次 Redis 读；读异常<b>不缓存</b>
     * （下一拍重试），于是 Redis 抖动时<b>每一帧</b>都会带着锁再读一次 —— 出帧热路径被一次
     * 高延迟读放大成"帧率 × 一个往返"，还把同轮其它出帧串行化在锁上。
     *
     * <p>退避期间的行为与"这一拍读失败"一致：有显式取键提示就用提示（身份键形与本轮一致），
     * 没有提示则返回 {@code null} 让调用方明确失败；退避到期后再试，解析成功即清零
     * （{@link #clearIdentityBackoff()}）。
     *
     * <p>只在锁内写，锁外读：因此退避判定本身不进锁。
     */
    private final AtomicLong identityRetryAtMs = new AtomicLong(0L);

    /**
     * 连续记录读失败次数（退避指数）；解析成功/落定时清零。只在 {@link #identityLock} 内写。
     */
    private final AtomicInteger identityReadFailures = new AtomicInteger();

    /**
     * 包②：单帧写出的<b>超时预算</b>（{@link SseWriteBudget}）。
     *
     * <p>
     * 生产装配（{@link RunRegistry}）给的是"弹性写出池 + {@code app.ai.sse.write-timeout}"；
     * 未显式给出时回落 {@link SseWriteBudget#direct()}（调用线程内写出、无预算），
     * 与修复前的行为逐字等价 —— 既有单测的确定性不受影响。
     */
    private final SseWriteBudget writeBudget;

    /**
     * T3-1：每订阅者实时队列容量（回放批豁免，见类注）。
     */
    private final int queueCapacity;

    /**
     * T3-10：单帧 JSON 的 UTF-8 字节上限
     * （{@code app.ai.result-budget.ui.max-frame-bytes}，缺省 64KB）。
     *
     * <p>出帧路径在"取号 → 落档 → 入队"之前调用 {@link FrameSizeLimiter#apply}：
     * 超限帧按<b>字段级</b>降采样并挂统一截断字段（{@code truncated/noticeCode/strategy/
     * originalChars/returnedChars}），归档与投递拿到的是同一份截断后帧
     * （重挂回放与实时流不会分叉）。
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

    /**
     * T3-1：订阅者 = emitter + 有界队列 + CAS drain 独占标志。
     */
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

    /**
     * 本写出器是否已在该 run 的唤醒频道上登记（首个订阅者登记、最后一个退订）。
     */
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

    // ── F1：非驱动方 attach 连接的本地保活 ─────────────────────────────────────

    /**
     * F1：本地保活节拍（与驱动侧 {@code TurnKeepalive} 的流式心跳同节拍）。
     *
     * <p>取值：前端首帧看门狗 30s / 帧间 45s（{@code frontend/src/api/sse.ts}），驱动侧心跳 5s。
     * 本地保活必须<b>明显早于</b>两条看门狗，又不必比驱动侧更密 —— 取同一个 5s。
     */
    public static final long DEFAULT_ATTACH_KEEPALIVE_INTERVAL_MILLIS = 5_000L;

    /**
     * F1：进程级<b>共享</b>保活调度器由 {@link #sharedResources} 的实例所有者持有。
     * 每个写出器最多一个定时任务；网络写出和归档读取走既有有界投递池。
     */

    /**
     * 装配进来的保活调度器（{@code null} = 不启用本地保活：单测/非 Spring 路径）。
     */
    private volatile ScheduledExecutorService attachKeepaliveScheduler;

    /**
     * 本写出器的保活节拍（毫秒）。
     */
    private volatile long attachKeepaliveIntervalMs = DEFAULT_ATTACH_KEEPALIVE_INTERVAL_MILLIS;

    /**
     * 本写出器当前唯一的保活定时任务（{@code null} = 未运行）。
     */
    private final AtomicReference<ScheduledFuture<?>> attachKeepaliveFuture = new AtomicReference<>();

    /**
     * 丢失 wake 的归档对账间隔；复用保活节拍，不新增定时器。
     */
    static final long ARCHIVE_RECONCILE_INTERVAL_MILLIS = 30_000L;

    private final AtomicLong nextArchiveReconcileAtMs = new AtomicLong(0L);

    /**
     * 对账在既有有界投递池中单飞，避免慢 Redis 读叠加。
     */
    private final AtomicBoolean archiveReconcileInFlight = new AtomicBoolean(false);

    /**
     * F1：<b>本实例驱动过这一轮</b>（{@link #beginDrive()} 在驱动入口置位）。
     *
     * <p>这是"本实例不是该 run 驱动方"的本地判据：只有驱动入口会调 {@code beginDrive}，
     * 纯 attach/重挂（跨实例落在非驱动方）不会。为真 ⇒ 不启动本地保活（真实驱动路径的
     * {@code TurnKeepalive} 已在发心跳，再加一份只会重复）。
     */
    private final AtomicBoolean drivingHere = new AtomicBoolean(false);

    /**
     * <b>驱动代次</b>：每次 {@link #beginDrive()} 自增。离线终帧宽限在排程时记下代次，
     * 到点若代次已变/本实例已是驱动方，就放弃合成终帧（重驱动已开始，不能再把连接判死）。
     */
    private final AtomicLong driveGeneration = new AtomicLong(0L);

    /**
     * F1：本轮<b>终帧已经投给订阅者</b>（done/error，含回放与唤醒补帧）。
     *
     * <p>终帧必须是最后一帧 ⇒ 之后不得再有本地保活；同时它也是保活任务的停机信号
     * （非驱动方实例没有 {@code complete()} 调用点，终帧只能从归档/唤醒得知）。
     */
    private final AtomicBoolean terminalFrameSeen = new AtomicBoolean(false);

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
     * 本写出器实际归档的展示段；只在 emitLock 内读写，不从聚合 AssistantMessage 反推。
     */
    private final List<TranscriptSegment> transcriptTimeline = new ArrayList<>();

    /**
     * 每个 emitter 至多一份归档重建缓存；实时帧推进代次时失效。均在 emitLock 内访问。
     */
    private long transcriptFrameGeneration;
    private long archivedTranscriptGeneration = -1L;
    private List<TranscriptSegment> archivedTranscriptTimeline;

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
    private final Object wakePublishLock = new Object();
    private boolean pendingWakeTerminal;

    /**
     * T5：本轮 assistant 正文消息的身份（结果铸为消息 / 消息级渲染去重的判据）。
     */
    private final String assistantMessageId;

    /**
     * T5：正文消息边界是否已开（start/content/end 三段式的 start 只发一次）。
     */
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

    /**
     * "续跑权读取失败（真实失败）"的首次告警标记；重复异常按稳定类别采样 WARN。
     * 与只读丢帧分开计数，避免两类故障互相吞掉首条 WARN。
     */
    private final AtomicBoolean authorityReadFailureWarned = new AtomicBoolean();

    /**
     * "续跑权只读 ⇒ 已丢弃业务帧"的告警节流（每轮一条，首次 WARN、其后 debug，避免逐帧刷屏）。
     */
    private final AtomicBoolean readOnlyDropWarned = new AtomicBoolean();

    /**
     * "本帧没有归档 ⇒ 不交付"的告警节流（每轮一条）：Redis 抖动期可能连丢多帧，
     * 逐帧 WARN 会刷屏，但"整轮都丢了"必须至少留一条痕（之后降到 debug）。
     */
    private final AtomicBoolean archiveFailureWarned = new AtomicBoolean();

    /**
     * "身份读不出来 ⇒ 本帧明确失败"的首次告警标记；重复故障按稳定类别采样 WARN。
     */
    private final AtomicBoolean identityFailureWarned = new AtomicBoolean();

    /**
     * 回放帧序列化/解析失败的首次告警标记：每一条丢弃都会在回放序列上留下 seq 洞，
     * 所以<b>首次必须 WARN</b>（带 type/seq），重复异常按稳定类别采样 WARN。
     */
    private final AtomicBoolean replayFrameDropWarned = new AtomicBoolean();

    private final AtomicBoolean replayReadFailureWarned = new AtomicBoolean();
    private final AtomicBoolean replayDegradedWarned = new AtomicBoolean();
    private final AtomicBoolean wakeSubscriptionWarned = new AtomicBoolean();
    private final AtomicBoolean wakeReadFailureWarned = new AtomicBoolean();
    private final AtomicBoolean asyncWriteSubmitWarned = new AtomicBoolean();

    /**
     * P3-04：业务活动锚点 / 心跳活动戳的<b>写侧节流</b>时钟（每写出器 = 每 run 一份）。
     *
     * <p>值为"最近一次登记合流写的活动时刻"（帧的 {@code atMs}/${@code now}；0 = 尚未登记）。
     * 同一秒内的重复登记直接跳过，在飞写再由单飞任务取最大业务戳合并 —— 僵尸判据
     * （{@code ai:activity}，窗口分钟级）与心跳 TTL 续期都不受影响。
     */
    private final AtomicLong lastBusinessActivityTouchMs = new AtomicLong(0L);

    private final AtomicLong lastHeartbeatActivityTouchMs = new AtomicLong(0L);
    private final Object activityTouchLock = new Object();
    private boolean activityTouchInFlight;
    private boolean pendingHeartbeatActivity;
    private long pendingBusinessActivityStamp;

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

    /**
     * 回收钩子只跑一次（终态排空可能被多条路径并发观察到）。
     */
    private final AtomicBoolean recycled = new AtomicBoolean();

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
            throw new IllegalStateException("app.ai.result-budget.ui.max-frame-bytes must be >= "
                    + FrameSizeLimiter.MIN_FRAME_BYTES + " (actual=" + maxFrameBytes
                    + "): below this limit, a delta frame skeleton cannot hold even one multibyte code point, so content cannot be split safely (truncation and empty-string fallbacks are refused)");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.writeBudget = ObjectUtils.getIfNull(writeBudget, SseWriteBudget::direct);
        // 构造线程（请求/重挂）的身份只作"取键提示"：本轮身份唯一权威来源是记录本身，
        // 解析见 {@link #deliveryIdentity()}（记录身份优先，请求身份不得覆盖记录）。
        this.requestIdentityHint = RequestContextUtils.currentOrNull();
        this.assistantMessageId = "assistant-" + runId;
    }

    /**
     * 本写出器的单帧字节上限（供断言与诊断；单一事实源 = {@code app.ai.result-budget.ui.max-frame-bytes}）。
     */
    int maxFrameBytes() {
        return this.maxFrameBytes;
    }

    /**
     * 本写出器每订阅者的实时队列容量（单一事实源 = {@link AiProperties.Sse#DEFAULT_DELIVERY_QUEUE_CAPACITY}，
     * 生产装配取它）。
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
     *                       {@link SseWriteBudget#DEFAULT_TIMEOUT_MILLIS}，与启动期 fail-fast 守卫同口径）
     */
    public static synchronized void configureSharedDeliveryPool(int poolSize, int poolQueueCapacity,
                                                                long writeTimeoutMs) {
        sharedWriteTimeoutMs = writeTimeoutMs > 0 ? writeTimeoutMs : SseWriteBudget.DEFAULT_TIMEOUT_MILLIS;
        SharedResources current = sharedResources;
        if (current == null || current.deliveryPool == null) {
            sharedResources = new SharedResources(poolSize, poolQueueCapacity,
                    current == null ? null : current.attachKeepaliveScheduler);
        }
    }

    /**
     * 共享投递池（未装配时回落同步直执，测试与非 Spring 场景的默认）。
     */
    public static Executor sharedDeliveryExecutorOrDirect() {
        SharedResources current = sharedResources;
        ThreadPoolExecutor pool = current == null ? null : current.deliveryPool;
        return pool != null ? pool : Runnable::run;
    }

    /**
     * 共享资源的<b>装配 owner 计数</b>（I5）：实例资源所有者只在<b>最后一个</b> Spring Context 关闭时
     * 才 shutdown 其持有的池。多上下文/嵌入式场景下，一个上下文关闭不得打断兄弟上下文的投递。
     */
    private static final AtomicInteger SHARED_RESOURCE_OWNERS = new AtomicInteger();

    /**
     * 注册一个 owner（{@link RunRegistry} 构造时调用；幂等按次数配对 {@link #releaseSharedResourceOwner()}）。
     */
    public static synchronized void registerSharedResourceOwner() {
        SHARED_RESOURCE_OWNERS.incrementAndGet();
    }

    /**
     * 释放一个 owner；<b>只有最后一个 owner</b>离开时才真正关闭共享池（含共享写出池）。
     * 这样多 Context 并存时先关闭的上下文不会让兄弟上下文停投。
     */
    public static synchronized void releaseSharedResourceOwner() {
        if (SHARED_RESOURCE_OWNERS.decrementAndGet() > 0) {
            return;
        }
        SHARED_RESOURCE_OWNERS.set(0);
        try {
            shutdownSharedResources();
        } finally {
            try {
                SseWriteBudget.shutdownSharedWriterPool();
            } catch (RuntimeException ex) {
                if (LogSampling.canAcquire("sse-writer-pool-close")) log.warn("shared writer pool close failed", ex);
            }
        }
    }

    /**
     * <b>优雅停机</b>：关闭进程级共享投递池与 attach 保活调度器（幂等，可由
     * {@link #releaseSharedResourceOwner()} 在最后一个 owner 离开时调用；关闭后仍可被下一次装配重新创建）。
     *
     * <p>资源所有者是实例，两个池均由它持有并在这里关闭，避免多 Spring Context / 嵌入式部署下
     * 旧上下文遗留线程与内存引用。先 {@code shutdown()} 让在途写出收尾，有界等待后再
     * {@code shutdownNow()}。
     *
     * <p>不改变正常投递语义：关闭只发生在应用停机；运行期的装配/取用路径逐字不变。
     */
    public static synchronized void shutdownSharedResources() {
        SharedResources current = sharedResources;
        sharedResources = null;
        if (current != null) {
            try {
                shutdownGracefully(current.deliveryPool, 2_000L);
            } catch (RuntimeException ex) {
                if (LogSampling.canAcquire("sse-delivery-close")) log.warn("shared delivery pool close failed", ex);
            }
            try {
                shutdownGracefully(current.attachKeepaliveScheduler, 1_000L);
            } catch (RuntimeException ex) {
                if (LogSampling.canAcquire("sse-attach-scheduler-close"))
                    log.warn("attach keepalive scheduler close failed", ex);
            }
        }
    }

    /**
     * 有界优雅关闭（{@code null} = no-op）：先 shutdown，等不到再 shutdownNow。
     */
    private static void shutdownGracefully(ExecutorService executor, long waitMillis) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(waitMillis, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 投递池指标（供 {@code GET /api/ai/health}）：poolSize / active / queueDepth / evicted / 写超时预算。
     */
    public static Map<String, Object> deliveryPoolMetrics() {
        SharedResources current = sharedResources;
        ThreadPoolExecutor pool = current == null ? null : current.deliveryPool;
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
        // P2-06（纯增量键）：唤醒派发执行器饱和次数（饱和时改为调用线程内联，不丢订阅通知）。
        metrics.put("wakeDispatchSaturations", RedisFrameWakeBus.dispatchSaturations());
        return metrics;
    }

    /**
     * F1：进程级<b>共享</b>保活调度器（资源所有者懒建、幂等；单线程，随所有者关闭）。
     *
     * <p>它只服务"本实例不是该 run 驱动方"的 attach 连接：每写出器最多一个定时任务，
     * 且只在有订阅者且未终态期间存在（见 {@link #syncAttachKeepalive()} / {@link #stopAttachKeepalive()}）。
     */
    public static ScheduledExecutorService sharedAttachKeepaliveScheduler() {
        SharedResources current = sharedResources;
        if (current != null) {
            return current.attachKeepaliveScheduler;
        }
        synchronized (SseChatEmitter.class) {
            if (sharedResources == null) {
                sharedResources = new SharedResources(0, 0, null);
            }
            return sharedResources.attachKeepaliveScheduler;
        }
    }

    /**
     * F1：装配"非驱动方 attach 连接"的本地保活（{@link RunRegistry} 的生产装配调用一次；幂等）。
     *
     * <p>不传调度器（单测/非 Spring 装配）⇒ 本地保活不启用，行为与修复前逐字一致。
     */
    public void enableAttachKeepalive(ScheduledExecutorService scheduler) {
        enableAttachKeepalive(scheduler, DEFAULT_ATTACH_KEEPALIVE_INTERVAL_MILLIS);
    }

    /**
     * 同 {@link #enableAttachKeepalive(ScheduledExecutorService)}，显式节拍（用例把它压到毫秒级）。
     */
    public void enableAttachKeepalive(ScheduledExecutorService scheduler, long intervalMs) {
        this.attachKeepaliveIntervalMs = Math.max(1L, intervalMs);
        this.attachKeepaliveScheduler = scheduler;
        syncAttachKeepalive();
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

    /**
     * 挂一个订阅者（首轮响应 / reattach 重挂共用）。
     */
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
        sub.emitter.onCompletion(() -> closeSubscription(sub, "lifecycle onCompletion"));
        sub.emitter.onTimeout(() -> closeSubscription(sub, "lifecycle onTimeout"));
        sub.emitter.onError(ex -> closeSubscription(sub, "lifecycle onError"));
        // B23L1：本实例的第一个订阅者出现 ⇒ 建立该 run 的唤醒订阅（按连接，不是按实例全量）。
        syncWakeSubscription();
        // F1：本实例不是驱动方时，这条 attach 连接在驱动侧静默阶段收不到任何帧 —— 起本地保活。
        syncAttachKeepalive();
    }

    /**
     * F1：<b>本实例不是该 run 驱动方</b>的 attach 连接，由本实例本地保活（无 {@code seq} 心跳）。
     *
     * <h2>缺陷形态</h2>
     * 驱动在 A、客户端重挂到 B（非粘滞代理下任意实例都可 serve reattach）。心跳<b>不发布唤醒、不入档</b>
     * （见 {@code emitInternal} 的心跳分支），于是 A 处于请求前压缩（L2 摘要单次最长 60s）、慢工具或
     * 上游静默时，B 的 attach 连接<b>一个帧都收不到</b>：回放是空的（A 还没产出新帧），实时帧在 A 上。
     * 前端 40s 首帧 / 45s 帧间看门狗因此把正常的一轮判死，重连 4 次后报错。
     *
     * <h2>本实现的边界（心跳保活 + 低频归档对账）</h2>
     * <ul>
     * <li><b>心跳本地</b>：只投给本写出器当前的订阅者；不落档、不取业务号、不写 {@code ai:beat}/
     * {@code ai:activity}、不发唤醒 —— 归档、序号、有序交付、真实驱动路径全部不变；</li>
     * <li><b>无 seq</b>：走既有 {@code heartbeat} 帧形态（独立 {@code heartbeatSeq}，帧类型集不变 16）；
     * 入队与业务帧共用 {@code emitLock}，因此"到达顺序 = 入队顺序"不变；</li>
     * <li><b>仅非驱动方</b>：{@link #beginDrive()} 置 {@link #drivingHere}；驱动侧由
     * {@code TurnKeepalive} 发真实心跳，不再叠加本地保活；</li>
     * <li><b>终态/退订即停</b>：终帧投出（{@link #terminalFrameSeen}）、{@link #complete()}、
     * 最后一个订阅者离开、或本实例转为驱动方，都取消唯一那个定时任务；</li>
     * <li><b>不泄漏</b>：进程级共享调度器单线程；每写出器最多<b>一个</b> {@link ScheduledFuture}，
     * 不是"每 attach 一个"。节拍体还会自检条件并自我取消（兜住漏停的路径）。</li>
     * </ul>
     */
    private void syncAttachKeepalive() {
        if (!isAttachKeepaliveNeeded()) {
            return;
        }
        ScheduledExecutorService scheduler = this.attachKeepaliveScheduler;
        if (scheduler == null) {
            return;
        }
        if (this.attachKeepaliveFuture.get() != null) {
            return;
        }
        ScheduledFuture<?> scheduled;
        try {
            scheduled = scheduler.scheduleAtFixedRate(AiTrace.wrap(this::attachKeepaliveBeat),
                    this.attachKeepaliveIntervalMs, this.attachKeepaliveIntervalMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            // 调度器已关闭（停机/单测提前 shutdown）：保活缺席不影响轮次正确性
            if (LogSampling.canAcquire("sse-attach-keepalive-schedule")) {
                log.warn("attach keepalive timer not scheduled runId={} (scheduler shut down)", this.runId, ex);
            }
            return;
        }
        if (!this.attachKeepaliveFuture.compareAndSet(null, scheduled)) {
            scheduled.cancel(false);
            return;
        }
        if (!isAttachKeepaliveNeeded()) {
            // 排上与条件翻转的窄窗（终态/最后一个订阅者已走）：立即收掉，不留空转任务。
            stopAttachKeepalive();
        }
    }

    /**
     * 保活条件：装配了调度器 + 有订阅者 + 非终态 + 本实例不是驱动方。
     */
    private boolean isAttachKeepaliveNeeded() {
        return this.attachKeepaliveScheduler != null
                && !this.drivingHere.get()
                && !this.terminating.get()
                && !this.terminalFrameSeen.get()
                && !this.subscribers.isEmpty();
    }

    /**
     * 取消本写出器唯一的保活任务（幂等）。
     */
    private void stopAttachKeepalive() {
        ScheduledFuture<?> future = this.attachKeepaliveFuture.getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 本写出器当前是否有保活任务在跑（包内可见：用例锚定"一个写出器一个定时器"与停机）。
     */
    boolean attachKeepaliveActive() {
        return this.attachKeepaliveFuture.get() != null;
    }

    /**
     * F1 保活节拍：一帧本地 {@code heartbeat}（无业务 {@code seq}、不入档、不发唤醒）。
     *
     * <p><b>取号、序列化、入队全在 {@code emitLock} 内</b>：心跳有两个生产者（驱动侧
     * {@link #heartbeat(Map)} + 本节的保活节拍），而"心跳序号随到达顺序单调"是可观测面的事实。
     * 旧实现把 {@code heartbeatSeq.incrementAndGet()} 留在锁外，取号与入队之间就有一段窗口 ——
     * 另一个生产者能在这段窗口里拿到更大的号并先入队，订阅者收到的号因此<b>回退</b>（2 之后再来 1）。
     * 与业务帧同一口径（{@code emitInternal} 的取号也在锁内），这条窗口就不存在了。
     *
     * <p>顺带保留既有的两条不变量：① 心跳与业务帧共享同一把出帧锁 ⇒ "终帧之后再入队一帧心跳"的
     * 窗口不存在（终帧的落档/入队会先把 {@link #terminalFrameSeen} 置位，本节拍看到即停）；
     * ② {@code liveSeq = 0} 的"心跳扇出分支"照旧：不补齐、不推进游标、不发布唤醒。
     *
     * <p>锁内只有一次 JSON 序列化与入队（无网络 I/O、无 Redis）：锁外补做的仍是
     * 被让位的唤醒同步（{@link #flushWakeSyncIfUnlocked()}），因此不引入锁序倒置。
     */
    private void attachKeepaliveBeat() {
        if (!isAttachKeepaliveNeeded()) {
            stopAttachKeepalive();
            return;
        }
        try {
            synchronized (this.emitLock) {
                if (!isAttachKeepaliveNeeded()) {
                    return;
                }
                Map<String, Object> frame = frame(RunStore.HEARTBEAT_TYPE);
                frame.put("runId", this.runId);
                frame.put("heartbeatSeq", this.heartbeatSeq.incrementAndGet());
                final byte[] payload;
                try {
                    payload = this.objectMapper.writeValueAsBytes(frame);
                } catch (Exception ex) {
                    if (LogSampling.canAcquire("sse-chat-exception-catch"))
                        log.warn("attach keepalive frame serialization failed runId={}", this.runId, ex);
                    return;
                }
                // liveSeq = 0 ⇒ 既有心跳扇出分支：不补齐、不推进游标、不发布唤醒。
                fanOutToSubscribers(payload, 0L);
            }
        } finally {
            flushWakeSyncIfUnlocked();
        }
        reconcileArchiveIfDue();
        if (!isAttachKeepaliveNeeded()) {
            stopAttachKeepalive();
        }
    }

    /**
     * 非驱动方按各订阅者的 {@code wakeFloor}（重挂时即客户端 lastSeq）低频读归档。
     * pub/sub 只负责加速，丢失最后一条业务/终帧 wake 也能在这里确定恢复；心跳本身不推进业务游标。
     */
    private void reconcileArchiveIfDue() {
        if (!isAttachKeepaliveNeeded()) {
            return;
        }
        long now = System.currentTimeMillis();
        long next = this.nextArchiveReconcileAtMs.get();
        if (now < next || !this.nextArchiveReconcileAtMs.compareAndSet(next,
                now + ARCHIVE_RECONCILE_INTERVAL_MILLIS)) {
            return;
        }
        if (!this.archiveReconcileInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            this.deliveryExecutor.execute(AiTrace.wrap(() -> {
                try {
                    if (isAttachKeepaliveNeeded()) {
                        reconcileArchive();
                    }
                } finally {
                    this.archiveReconcileInFlight.set(false);
                }
            }));
        } catch (RejectedExecutionException ex) {
            this.archiveReconcileInFlight.set(false);
            if (LogSampling.canAcquire("sse-archive-reconcile-schedule")) {
                log.warn("archive reconcile not scheduled runId={} (next reconcile window retries)", this.runId, ex);
            }
        }
    }

    /**
     * 对账只在锁内取游标与入队；归档 Redis 读在两次临界区之间完成。
     */
    private void reconcileArchive() {
        TenantUser identity = deliveryIdentity();
        if (identity == null) {
            warnOnce(this.identityFailureWarned, "archive reconcile skipped runId={} (identity unconfirmed, retry next window)", this.runId);
            return;
        }
        try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
            reconcileArchiveScoped();
        }
    }

    private void deliverReconciledPage(long from, WakePage page) {
        try {
            synchronized (this.emitLock) {
                // 读取期间最小游标若已前进，直接投旧页可能把低号排到高号之后；下个对账窗口重读。
                if (!isAttachKeepaliveNeeded() || minDeliveredSeq() != from) {
                    return;
                }
                for (WakeFrame next : page.frames()) {
                    long seq = next.seq();
                    if (seq <= from) {
                        continue;
                    }
                    if ("done".equals(next.type()) || "error".equals(next.type())) {
                        this.terminalFrameSeen.set(true);
                    }
                    for (Subscription sub : this.subscribers) {
                        if (sub.closed || sub.terminating) {
                            continue;
                        }
                        if (sub.replayActive) {
                            kickDrain(sub);
                            continue;
                        }
                        if (seq <= sub.wakeFloor) {
                            continue;
                        }
                        if (!isEnqueueLive(sub, next.payload())) {
                            evict(sub, "archive reconcile exceeds live queue capacity (capacity " + this.queueCapacity + ")");
                            continue;
                        }
                        sub.wakeFloor = seq;
                        kickDrain(sub);
                    }
                    this.lastFanoutSeq.accumulateAndGet(seq, Math::max);
                }
            }
        } finally {
            flushWakeSyncIfUnlocked();
        }
        if (this.terminalFrameSeen.get()) {
            stopAttachKeepalive();
        }
    }

    private void reconcileArchiveScoped() {
        final long from;
        synchronized (this.emitLock) {
            if (!isAttachKeepaliveNeeded()) {
                return;
            }
            from = minDeliveredSeq();
            if (from < 0L) {
                return;
            }
        }
        deliverReconciledPage(from, loadWakeFrames(from));
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
        // P1-06：分页回放的暂存也一并释放（否则关闭的连接会留下最多一页的帧引用）。
        synchronized (this.emitLock) {
            abortReplayLocked(sub);
        }
        sub.liveQueued.set(0);
        sub.identityDegradedQueued.set(0);
        sub.draining.set(false);
        // B23L1：本实例最后一个订阅者消失 ⇒ 释放该 run 的唤醒订阅（断连即 unsubscribe）。
        syncWakeSubscription();
        // F1：最后一个订阅者离开 ⇒ 停掉本地保活（否则定时器随断开的连接一直空转）。
        if (this.subscribers.isEmpty()) {
            stopAttachKeepalive();
        }
        // 资源修复批：轮已终态且订阅者排空 ⇒ 通知注册表摘掉本条目（"最后一个离开的人关灯"）。
        fireRecycleIfTerminal();
        if (log.isDebugEnabled())
            log.debug("subscriber delivery context closed runId={} reason={}", this.runId, reason);
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
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("sse-chat-runtime-catch"))
                log.warn("emitter reclaim hook failed runId={}", this.runId, ex);
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
        } else {
            // <b>无条件退订</b>（返修第 1 条/四轮）：不能只看 {@code wakeRegistered} 的 CAS ——
            // 被"提升"的客人从未拿到 true（{@code register} 当时回的是 false，登记位已回退），
            // 但它在监听器的桶里；若只在 CAS 成功时退订，客人的连接关闭后这个 target 与登记句柄
            // 会永久留在监听器里（桶不空 ⇒ 订阅永不释放，写出器也被 map 引用）。
            // 监听器按 target 精确匹配，<b>没登记过时是 no-op</b>（幂等），因此这里不需要 CAS。
            this.wakeRegistered.set(false);
            try {
                listener.unregister(this.runId, this.wakeTarget);
            } catch (Exception ex) {
                // 退订失败不改变语义（唤醒只是"补帧优化"，读侧仍按 ?lastSeq 重挂兜底），只留痕。
                warnOnce(this.wakeSubscriptionWarned, "frame wake unsubscribe failed runId={}", this.runId, ex);
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
     * 按调用线程的当前身份拼频道，因此这里显式按记录身份建立作用域；身份不可确认就不登记
     * （没有可判定的身份频道，不存在裸 runId 旁路），由下一拍/重挂重试。
     */
    private void ensureWakeSubscribed() {
        FrameWakeListener listener = this.wakeListener;
        if (listener == null) {
            return;
        }
        if (!this.wakeRegistered.compareAndSet(false, true)) {
            return;
        }
        TenantUser identity = deliveryIdentity();
        if (identity == null) {
            // 身份不可确认 ⇒ 不登记（没有可判定的身份频道，调用方下一拍/重挂重试）。
            this.wakeRegistered.set(false);
            warnOnce(this.identityFailureWarned, "frame wake subscribe skipped runId={} (identity unconfirmed, retry next tick)", this.runId);
            return;
        }
        boolean registered;
        try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
            registered = listener.isRegistered(this.runId, this.wakeTarget);
        } catch (Exception ex) {
            warnOnce(this.wakeSubscriptionWarned, "frame wake subscribe failed runId={}", this.runId, ex);
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

    /**
     * 当前线程是否正持有 {@code emitLock}（单测锚点：锁内不得有 Redis 退订 / 发布）。
     */
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
     *                 归档里有没有 {@code done}/{@code error}，见 {@link #replayAndAttachWithStatus}）
     * @return 首拍入队的回放帧数（P1-06 分页后为一页内的一批；异步写出前的入队口径）
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
     * <b>边界与兜底（当前契约）</b>：保留挂接保证"写方还活着并会发终帧"的正常收尾窗口。进程若死在
     * 快照终态与 {@code done} 落档之间，离线重挂在回放读到底后进入<b>终帧宽限</b>：
     * 每 {@link #UNARCHIVED_TERMINAL_GRACE_SECONDS} 秒复查（本实例驱动代次 + 快照仍为终态）；
     * 队列/在飞 emit 忙时最多重排 {@link #UNARCHIVED_TERMINAL_GRACE_RETRIES} 次；仍未等到终帧则
     * 复用 {@code terminalUnarchived} 链路给当前订阅补合成 typed error 并收流（不置 emitter 级终态，
     * 后续重挂仍可继续）。前端看门狗只作为最后兜底，不再是唯一收敛手段。
     */
    private ReplayResult attachReplay(Subscription sub, ReplayStart start, long startCursor, Long lastSeq,
                                      boolean tailTerminal) {
        ReplayResult plan;
        try {
            synchronized (this.emitLock) {
                // I-1：**先挂订阅（含唤醒订阅）再入队首页**。旧顺序（读档 → 挂订阅）之间落档的帧
                // 谁也接不住；现在唤醒订阅早已建立，且游标分页会继续读到它们。
                attachSubscription(sub);
                try {
                    plan = applyReplayStartLocked(sub, start, startCursor);
                } catch (RuntimeException ex) {
                    // M-2：回放入队抛异常时，刚挂上的订阅必须摘掉：它留在订阅表里会挡住唤醒订阅的释放，
                    // 而这条连接根本不会交给客户端（异常让 controller 走错误响应）。
                    closeSubscription(sub, "replay enqueue failed: " + ex.getClass().getSimpleName());
                    throw ex;
                }
                // 日志口径（minor）：staged 是<b>首批</b>入队帧数，不是整轮回放总数（分页续读在 drain 里）。
                if (log.isDebugEnabled())
                    log.debug("reattach replay first page enqueued runId={} lastSeq={} staged={} morePages={}", this.runId, lastSeq,
                            plan.staged(), sub.replayActive());
                if (plan.degraded()) {
                    warnOnce(this.replayDegradedWarned, "replay degraded runId={} terminal={} lastSeq={} staged={} (typed error then close, not normal empty replay)",
                            this.runId, sub.terminalExpected, lastSeq, plan.staged());
                    finishDegradedReplay(sub, plan);
                } else {
                    boolean archivedTerminal = plan.terminalFrame()
                            || (!sub.replayActive() && plan.staged() == 0 && tailTerminal);
                    // 归档没有终帧且本轮还没收尾 ⇒ 后面还有（或即将有）实时帧，订阅必须留着。
                    boolean waitForLiveTerminalFrame = !archivedTerminal && !this.terminating.get();
                    if (waitForLiveTerminalFrame) {
                        if (plan.staged() > 0 || sub.replayActive()) {
                            // P1-06：回放已按页读入 —— 即使第一页全部被过滤（staged=0），也要让 drain
                            // 继续把剩余页面读完；"队空才收尾"的判据里已经带上 replayActive。
                            kickDrain(sub);
                        }
                    } else {
                        // 回放已含终帧（或本轮已 complete，不会再有新帧）：排空回放后收尾，不悬挂。
                        // P1-06：只有"回放读到底且队列排空"才允许 complete —— drain 的 ready 判据含
                        // {@code !replayActive()}，因此这里只置收尾意图 + 起 drain。
                        sub.terminating = true;
                        if (!sub.replayActive() && sub.queue.isEmpty() && this.emitting.get() == 0) {
                            completeOne(sub);
                        } else {
                            // 8D/L-143：在飞 emit 尚未落档/入队时不能据"空回放"关闭；kickDrain 排空后由
                            // finishDrain / 最后一个 emit 的 finally 收尾。
                            kickDrain(sub);
                        }
                    }
                }
            }
        } finally {
            flushWakeSyncIfUnlocked();
        }
        return plan;
    }

    public ReplayResult replayAndAttachWithStatus(SseEmitter emitter, Long lastSeq, boolean terminal) {
        Subscription sub = new Subscription(emitter, false);
        // 本订阅者的补帧下界 = 客户端已收到的最大 seq（?lastSeq）：唤醒补帧只投 > 它的帧。
        sub.wakeFloor = Math.max(0L, ObjectUtils.defaultIfNull(lastSeq, 0L));
        sub.terminalExpected = terminal;
        long startCursor = Math.max(0L, ObjectUtils.defaultIfNull(lastSeq, 0L));
        // I-1 + 资源修复批：唤醒订阅<b>先于回放读</b>建立，且在<b>锁外</b>（订阅是 Redis 容器级 I/O）。
        // I4：分页 Redis 读也在<b>锁外</b>完成 —— 读之后才回锁挂订阅并入队；回放游标覆盖读取期间
        // 新归档的帧，唤醒订阅覆盖"读之后、入队之前"的实时帧。
        ReplayStart start;
        boolean tailTerminal;
        ReplayResult plan;
        try {
            ensureWakeSubscribed();
            start = readReplayStart(sub, lastSeq);
            // 差量读为空时探一格归档尾部（一次单帧读；放在锁外）。lastSeq 恰好等于 done.seq 时，
            // 差量里看不到终帧，不能据此又挂上去等一个永远不会再发的 done。
            tailTerminal = lastSeq != null && isTailFrameIsTerminal(lastSeq);
            plan = attachReplay(sub, start, startCursor, lastSeq, tailTerminal);
        } finally {
            flushWakeSyncIfUnlocked();
        }
        if (this.terminalFrameSeen.get()) {
            // F1：回放已含终帧 ⇒ 本轮不会再有心跳，停本地保活（其余订阅者也不再需要它）。
            stopAttachKeepalive();
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
        // 锁外尾部复查（末号探测不占 emitLock）：首屏读/挂接之间新增的帧由分页续读补齐。
        // <b>必须在 onWake 之前</b>：复查把 replayActive 置真后，onWake 会跳过本订阅者的实时补帧，
        // 避免"onWake 先投递、分页又从旧游标重读"的重复。
        recheckReplayTailAfterAttach(sub);
        applyWakeSync();
        if (!sub.closed && !sub.terminating) {
            onWake(this.runId, Long.MAX_VALUE);
        }
        if (!sub.closed && !sub.terminating && !sub.replayActive()) {
            // M6：首屏与尾部复查都读完（空/短回放）且没有终帧时，离线重挂进入终帧宽限。
            scheduleUnarchivedTerminalGraceIfNeeded(sub);
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
    private boolean isTailFrameIsTerminal(Long lastSeq) {
        if (lastSeq == null || lastSeq <= 0L) {
            return false;
        }
        List<FrameStore.RawFrame> tail;
        TenantUser identity = deliveryIdentity();
        if (identity == null) {
            return false;
        }
        try {
            try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
                // 真实 COUNT=1（XRANGE + COUNT），不做整窗读后裁剪。
                tail = this.frames.readRawFrom(this.runId, lastSeq - 1L, 1);
            }
        } catch (Exception ex) {
            if (LogSampling.canAcquire("sse-chat-exception-catch"))
                log.warn("replay terminal probe failed runId={} lastSeq={}", this.runId, lastSeq, ex);
            return false;
        }
        if (CollectionUtils.isEmpty(tail)) {
            return false;
        }
        Map<String, Object> parsed = parseRawFrame(tail.getFirst());
        if (parsed == null) {
            return false;
        }
        String type = String.valueOf(parsed.get("type"));
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
     * （有 ⇒ 不再补取消终帧）。失败出口<b>不能</b>用它 —— 同一 runId 会被续跑/重挂驱动多次，
     * 见 {@link #terminalFrameArchivedSinceDriveStart()}。
     *
     * @return {@code TRUE} 已落档 / {@code FALSE} 确认没有 / {@code null} 读不出来（读降级或未装配
     * 帧存储）。调用方<b>不得</b>把 {@code null} 折叠成任何一侧：取消收尾按"继续收尾"处理，
     * 交棒侧按"未落定"重试。
     */
    public Boolean terminalFrameArchived() {
        if (this.frames == null) {
            return null;
        }
        ArchiveScan scan = scanArchive(terminalScanStart(0L), ARCHIVE_SCAN_BUDGET,
                frame -> "done".equals(String.valueOf(frame.get("type")))
                        || "error".equals(String.valueOf(frame.get("type"))));
        return switch (scan) {
            case FOUND -> Boolean.TRUE;
            case NOT_FOUND -> Boolean.FALSE;
            case UNKNOWN -> null;
        };
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
     * 归档是 per-runId 的，而同一 runId 会被续跑/重挂入口驱动<b>多次</b>：失败轮可能被改回 RUNNING
     * 再驱动一次，归档<b>不清理</b>也不追加代次。只问"归档里有没有任何终帧"会把
     * <b>上一次</b>驱动的 error 当成"本次已收尾" —— 本次失败的 {@code textEnd}/{@code error} 被整条吞掉
     * （用户看不到本次失败，且已落档的 delta 段没有 {@code message_end}）。
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
        // F1：本实例成为这一轮的驱动方 ⇒ 真实心跳由 TurnKeepalive 发；停掉（并禁止再起）本地保活。
        // 同时复位终帧标记：同一 runId 会被续跑/重挂驱动多次，上一次驱动的 done/error 不代表本次。
        // 与离线终帧宽限的"复查+合成"同锁：driveGeneration 的变化对宽限任务是原子的。
        synchronized (this.emitLock) {
            this.drivingHere.set(true);
            // 驱动代次自增：已排程的"离线终帧宽限"到点看到代次变化即放弃（重驱动进行中）。
            this.driveGeneration.incrementAndGet();
            this.terminalFrameSeen.set(false);
        }
        stopAttachKeepalive();
        long floor = -1L;
        TenantUser identity = deliveryIdentity();
        if (this.frames != null && identity != null) {
            try {
                try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
                    OptionalLong last = this.frames.lastSeqIfKnown(this.runId);
                    if (last != null && last.isPresent()) {
                        floor = Math.max(0L, last.getAsLong());
                    }
                }
            } catch (RuntimeException ex) {
                if (LogSampling.canAcquire("sse-chat-runtime-catch"))
                    log.warn("driver floor read failed runId={} (unknown; failure exit still emits)", this.runId, ex);
                floor = -1L;
            }
        }
        this.driveFloorSeq = floor;
        if (log.isDebugEnabled()) log.debug("driver start runId={} archive floor={}", this.runId, floor);
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
     * {@code null} 下界或回读不可知（未调 {@link #beginDrive()}、读降级、未装配帧存储）
     */
    public Boolean terminalFrameArchivedSinceDriveStart() {
        if (this.frames == null) {
            return null;
        }
        long floor = this.driveFloorSeq;
        if (floor < 0L) {
            return null;
        }
        ArchiveScan scan = scanArchive(terminalScanStart(floor), ARCHIVE_SCAN_BUDGET, frame -> {
            Object seq = frame.get("seq");
            if (!(seq instanceof Number number) || number.longValue() <= floor) {
                return false;
            }
            String type = String.valueOf(frame.get("type"));
            return "done".equals(type) || "error".equals(type);
        });
        return switch (scan) {
            case FOUND -> Boolean.TRUE;
            case NOT_FOUND -> Boolean.FALSE;
            case UNKNOWN -> null;
        };
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
     * <p><b>P1-06</b>：回放分页后返回的是<b>首拍入队</b>的帧数（≤ 一批）；剩余页由 drain 续读并
     * 写出，读到底才 complete。帧数很小的回放（用例/短轮次）仍等于总帧数。
     *
     * @param lastSeq 前端已知的最大 seq；null = 全量回放
     * @return 首拍入队的回放帧数（异步写出前的入队口径）
     */
    public int replayTo(SseEmitter emitter, Long lastSeq) {
        return replayToWithStatus(emitter, lastSeq).staged();
    }

    /**
     * 同 {@link #replayTo}，带降级标记；降级空结果不 {@code completeOne}（I-1）。
     */
    public ReplayResult replayToWithStatus(SseEmitter emitter, Long lastSeq) {
        Subscription sub = new Subscription(emitter, true);
        long startCursor = Math.max(0L, ObjectUtils.defaultIfNull(lastSeq, 0L));
        // I4：首页读在锁外，回锁只做校验 + 有界入队。
        ReplayStart start = readReplayStart(sub, lastSeq);
        ReplayResult plan;
        try {
            synchronized (this.emitLock) {
                plan = applyReplayStartLocked(sub, start, startCursor);
                if (plan.degraded()) {
                    warnOnce(this.replayDegradedWarned, "replay degraded runId={} lastSeq={} staged={} (typed error then close, not normal empty replay)", this.runId,
                            lastSeq, plan.staged());
                    finishDegradedReplay(sub, plan);
                    return plan;
                }
                if (plan.staged() > 0 || sub.replayActive()) {
                    // P1-06：还有页未读（replayActive）时也起 drain —— 一次性回放同样"读到底才收尾"。
                    kickDrain(sub);
                }
                // 空回放不在这里立刻 complete：先出锁做尾部复查（首屏读之后可能又落档了帧）。
            }
        } finally {
            flushWakeSyncIfUnlocked();
        }
        // 锁外尾部复查：首屏读之后新增的帧（含终帧）在这里补一次"是否还有页"的判定。
        recheckReplayTailAfterAttach(sub);
        if (!sub.replayActive() && sub.queue.isEmpty()) {
            completeOne(sub);
        } else {
            kickDrain(sub);
        }
        return plan;
    }

    /**
     * 回放结果：{@code staged} = <b>首拍</b>实际入队帧数（P1-06 分页后为一页内的一批；剩余页由
     * drain 续读，诊断/日志口径）；{@code degraded} = 本次回放有读失败；
     * {@code terminalFrame} = 已读到 done/error 终帧（不得再补 degraded error）；
     * {@code truncated} = <b>后端明确告知</b>"游标早于幸存窗口，确实少了一段"。
     *
     * <p>{@code truncated} 与 {@code degraded} 的分工（Task 2）：{@code degraded} 是"这次读失败，
     * 结果可信度未知"（前端应重试）；{@code truncated} 是"读成功了，但归档窗口已经翻过去"
     * （前端应提示"不可完整恢复"，不必重试）。消费方<b>不得</b>再用
     * "首帧号 &gt; lastSeq + 1" 推断缺口 —— 生产上跳号是正常形态（降级/失败重试都会跳），
     * 那样猜会把跳号误报成丢帧。
     *
     * <p>{@code trimmedFromSeq}/{@code trimmedToSeq} = <b>缺口区间</b>（含端点：
     * {@code afterSeq+1} 到 {@code 裁剪低水位-1}；未截断为 {@code 0/0}），由
     * {@code GET /api/ai/events/{runId}} 作为响应头 {@code archive-from}/{@code archive-to} 暴露
     * （与 {@code archive-trimmed} 一起构成前端唯一可信的「归档是否被裁」信号；业务帧形态不变）。
     * <b>不是</b>回放区间 —— 前端用它告诉用户"哪一段不可完整恢复"。
     */
    public record ReplayResult(int staged, boolean degraded, boolean terminalFrame, boolean truncated,
                               long trimmedFromSeq, long trimmedToSeq) {

        /**
         * 兼容构造（未截断、无区间）：不需要区分这些位的调用方/替身用它。
         */
        public ReplayResult(int staged, boolean degraded, boolean terminalFrame) {
            this(staged, degraded, terminalFrame, false, 0L, 0L);
        }

        /**
         * 兼容构造（只有截断位、无缺口区间）。
         */
        public ReplayResult(int staged, boolean degraded, boolean terminalFrame, boolean truncated) {
            this(staged, degraded, terminalFrame, truncated, 0L, 0L);
        }
    }

    /**
     * N-2：降级回放必须给终态。
     *
     * <p>若已读到 done/error 终帧则不再补（避免重复终帧）；否则补一帧 typed error
     * （{@code AI_REDIS_UNAVAILABLE / retryable=true / degraded=true}），再让 drain 把队列写完后
     * {@code completeOne}。终态与非终态都走这条出口 —— 非终态也不能悬挂。
     *
     * <p><b>P1-06（分页）</b>：这里同时收口分页状态 —— 已读到的回放帧先按归档顺序全部入队，
     * 放弃后续页（读降级后继续读只会得到不可信结果），typed error 因此仍是最后一帧。
     */
    private void finishDegradedReplay(Subscription sub, ReplayResult plan) {
        flushReplayPendingLocked(sub);
        sub.replayMorePages = false;
        sub.replayActive = false;
        if (!plan.terminalFrame() && !sub.replayTerminalSeen) {
            Map<String, Object> error = frame("error");
            error.put("runId", this.runId);
            error.put("code", ErrorCodes.AI_REDIS_UNAVAILABLE);
            error.put("message", "AI 服务暂不可用，请稍后重试。");
            error.put("retryable", true);
            error.put("degraded", true);
            if (plan.truncated()) {
                // additive：把"回放期间归档被裁剪"的缺口区间显式带给前端（不新增帧型）。
                error.put("truncated", true);
                error.put("archiveFrom", plan.trimmedFromSeq());
                error.put("archiveTo", plan.trimmedToSeq());
            }
            try {
                sub.queue.add(new QueuedFrame(this.objectMapper.writeValueAsString(error), false));
            } catch (Exception ex) {
                log.error("degraded replay error frame serialization failed runId={}", this.runId, ex);
            }
        }
        // terminating 让回放订阅在队列排空后收尾（无论快照终态与否；L-143 起回放订阅一律挂在
        // 订阅表里，收尾判据统一走 drain 的队空分支）。
        sub.terminating = true;
        kickDrain(sub);
    }

    /**
     * P1-06/I1：回放<b>第一页的读取</b>（<b>不持</b> {@link #emitLock}，I4）。
     *
     * <p>生产路径 = {@code replayMeta}（末号 + 裁剪低水位，两次廉价探测）+
     * {@code readRawFrom}（XRANGE + COUNT，真实分页）—— 不再"整窗读取 + 解析 + 读后裁剪"。
     * 原文口的空结果用末号区分"确实到底 / 读失败 / 兼容实现不支持原文读口"；后两者才回落到
     * 类型化读口（整窗语义，仅兼容实现/故障路径会走到）。
     *
     * <p>读在锁外完成：帧被归档在"读之后、回锁入队之前"时，由后续分页（游标从本页末尾继续）
     * 或唤醒补帧接住，不存在丢帧窗口；回锁后再校验 {@code closed/cursor} 才入队。
     */
    private ReplayStart readReplayStart(Subscription sub, Long lastSeq) {
        TenantUser identity = deliveryIdentity();
        if (identity == null) {
            return new ReplayStart(List.of(), null, true, new FrameStore.ReadResult(List.of(), true, false, 0L, 0L));
        }
        try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
            return readReplayStartScoped(sub, lastSeq);
        }
    }

    private ReplayStart readTypedReplayStart(long afterSeq, OptionalLong last, FrameStore.ReadResult meta) {
        // 原文口没有帧，但末号说"后面还有"（或末号不可知）：兼容实现不支持原文口 / 本页读失败。
        FrameStore.ReadResult typed;
        try {
            typed = this.frames.readFromWithStatus(this.runId, afterSeq, REPLAY_PAGE_FRAMES);
        } catch (RuntimeException ex) {
            warnOnce(this.replayReadFailureWarned, "replay compat reader failed runId={} afterSeq={} (closing stream degraded)", this.runId, afterSeq, ex);
            return new ReplayStart(List.of(), null, true, meta);
        }
        if (typed == null) {
            return new ReplayStart(List.of(), null, true, meta);
        }
        List<Map<String, Object>> typedPage = firstReplayPage(typed.frames());
        boolean degraded = typed.degraded();
        if (!degraded && typedPage.isEmpty() && (last == null || last.isEmpty())) {
            // 读口说"没有下一页"、末号又不可知 ⇒ 不能宣称读到底。
            degraded = true;
        }
        return new ReplayStart(List.of(), typedPage, degraded, meta);
    }

    private ReplayStart readReplayStartScoped(Subscription sub, Long lastSeq) {
        long afterSeq = Math.max(0L, ObjectUtils.defaultIfNull(lastSeq, 0L));
        sub.replayOrphanFloor = lastSeq;
        sub.replayCursor = afterSeq;
        sub.wakeFloor = Math.max(sub.wakeFloor, afterSeq);
        FrameStore.ReadResult meta;
        try {
            meta = this.frames.replayMeta(this.runId, afterSeq);
        } catch (RuntimeException ex) {
            warnOnce(this.replayReadFailureWarned, "replay metadata probe failed runId={} afterSeq={} (treating as degraded)", this.runId, afterSeq, ex);
            meta = new FrameStore.ReadResult(List.of(), true, false, 0L, 0L);
        }
        if (meta == null) {
            meta = new FrameStore.ReadResult(List.of(), false, false, 0L, 0L);
        }
        List<FrameStore.RawFrame> raw;
        try {
            raw = this.frames.readRawFrom(this.runId, afterSeq, REPLAY_PAGE_FRAMES);
        } catch (RuntimeException ex) {
            warnOnce(this.replayReadFailureWarned, "replay raw page read failed runId={} afterSeq={} (falling back to typed reader)", this.runId, afterSeq, ex);
            raw = List.of();
        }
        if (raw == null) {
            raw = List.of();
        }
        if (!raw.isEmpty()) {
            return new ReplayStart(raw, null, meta.degraded(), meta);
        }
        if (meta.degraded()) {
            // I-1：末号/元数据不可读时不得宣称"空回放"。
            return new ReplayStart(List.of(), null, true, meta);
        }
        OptionalLong last = lastSeqIfKnownQuietly();
        if (last != null && last.isPresent() && last.getAsLong() <= afterSeq) {
            // 末号确认到底：真空回放（不是降级）。
            return new ReplayStart(List.of(), null, false, meta);
        }
        return readTypedReplayStart(afterSeq, last, meta);
    }

    /**
     * 回放首页的<b>入队</b>（调用方持 {@link #emitLock}）：回锁后先校验订阅未关闭、游标仍是读取时
     * 的位置，再按有界批入队（I4）。
     */
    private ReplayResult applyReplayStartLocked(Subscription sub, ReplayStart start, long expectedCursor) {
        if (sub.closed || sub.replayCursor != expectedCursor) {
            // 读期间订阅被关闭/放弃：这次读作废（不投递，也不补 typed error）。
            return new ReplayResult(0, false, false, false, 0L, 0L);
        }
        boolean progressed;
        if (!start.raw().isEmpty()) {
            progressed = isStageRawReplayPageLocked(sub, start.raw());
            sub.replayMorePages = progressed;
        } else if (start.typedPage() != null) {
            progressed = isStageReplayPageLocked(sub, start.typedPage());
            // 兼容读口也"可能还有下一页"：多读一次由末号确认收敛，避免"读之后、挂订阅之前"归档的帧
            // 只靠唤醒兜底（原文口本来就是这样收敛的）。
            sub.replayMorePages = progressed;
        } else {
            progressed = false;
            sub.replayMorePages = false;
        }
        // 首屏读在锁外完成；"读之后到挂接之间"新增的帧由调用方在<b>锁外</b>做尾部复查
        // （{@link #recheckReplayTailAfterAttach}），不把末号探测放进 emitLock。
        int staged = enqueueReplayPendingLocked(sub);
        refreshReplayActive(sub);
        // 本订阅者的补帧下界 = max(读到的最大号, 它的 ?lastSeq)。后者必须计入：重挂落在归档尾部时
        // 差量读为空（暂存 0 帧），若只按 maxReadSeq 记下界，下一次跨实例唤醒会从 0 补起，
        // 把客户端已见的 1..N 重发一遍（三实例 streaming-kill 实测：lastSeq=2 的重挂先收到 seq=1）。
        // 只推进<b>本订阅者</b>的下界；emitter 级上界仍按实际读到的号走 —— 别人带一个荒谬的 lastSeq
        // 只会让它自己少收，不会饿死同一 emitter 上的其它连接。
        sub.wakeFloor = Math.max(sub.wakeFloor, sub.replayCursor);
        if (sub.replayCursor > 0L) {
            this.lastFanoutSeq.accumulateAndGet(sub.replayCursor, Math::max);
        }
        // M6 宽限不在这里排：首屏之后还要做锁外尾部复查，可能还有分页要读；宽限由
        // replayAndAttach 的收尾处（或 advanceReplay 读到底时）触发，避免刚排程就遇到 replayActive。
        FrameStore.ReadResult meta = start.meta();
        return new ReplayResult(staged, start.degraded(), sub.replayTerminalSeen, meta.truncated(),
                meta.trimmedFromSeq(), meta.trimmedToSeq());
    }

    /**
     * 首屏读/挂接之后的<b>锁外尾部复查</b>（I4 返修）：
     * 读在锁外完成，期间新归档的帧（订阅当时还不在 {@code subscribers}，唤醒看不到它）在这里补一次
     * "是否还有页"的判定。条件用 {@code max(replayCursor, wakeFloor)}：实时扇出/唤醒已经投递并推进
     * 游标的帧不会因此被回放重发；未覆盖的尾部（含终帧）由分页续读接住。
     */
    private void recheckReplayTailAfterAttach(Subscription sub) {
        if (sub.closed || sub.terminating) {
            return;
        }
        OptionalLong last = lastSeqIfKnownQuietly();
        if (last == null || last.isEmpty()) {
            return;
        }
        boolean needMore;
        synchronized (this.emitLock) {
            if (sub.closed || sub.terminating) {
                return;
            }
            long delivered = Math.max(sub.replayCursor, sub.wakeFloor);
            needMore = last.getAsLong() > delivered;
            if (needMore) {
                // 从"已投递/已读到的最大号"继续，跳过早前实时扇出/唤醒已投递的前缀（防重复）。
                sub.replayCursor = delivered;
                sub.replayMorePages = true;
                refreshReplayActive(sub);
            }
        }
        if (needMore) {
            kickDrain(sub);
        }
    }

    /**
     * 首页读取结果（锁外读、锁内入队的两段式载体）。
     */
    private record ReplayStart(List<FrameStore.RawFrame> raw, List<Map<String, Object>> typedPage, boolean degraded,
                               FrameStore.ReadResult meta) {
    }

    /**
     * 兼容读口返回的一页（生产原文口已 COUNT 有界；兼容实现可能忽略 limit，这里再截断一层）。
     */
    private static List<Map<String, Object>> firstReplayPage(List<Map<String, Object>> frames) {
        if (CollectionUtils.isEmpty(frames)) {
            return List.of();
        }
        return frames.size() <= REPLAY_PAGE_FRAMES ? frames : frames.subList(0, REPLAY_PAGE_FRAMES);
    }

    /**
     * 把一页归档帧放进订阅者的分页暂存（调用方持 {@link #emitLock}）。
     *
     * <p>只做"识别 + 排队"：终帧位、最大已读号、心跳/孤儿过滤；序列化与入队在
     * {@link #enqueueReplayPendingLocked} 里按预算分批做。
     *
     * @return 游标是否有前进（无进展的页不得让分页自旋，见调用方对 {@code replayMorePages} 的收敛）
     */
    private boolean isStageReplayPageLocked(Subscription sub, List<Map<String, Object>> frames) {
        long before = sub.replayCursor;
        long maxReadSeq = before;
        for (Map<String, Object> frame : frames) {
            String type = String.valueOf(frame.get("type"));
            if ("done".equals(type) || "error".equals(type)) {
                sub.replayTerminalSeen = true;
                // F1：归档里已有终帧 ⇒ 本轮不会再有心跳（本地保活据此停机；调用方持 emitLock）。
                this.terminalFrameSeen.set(true);
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
            if (isOrphan(frame, sub.replayOrphanFloor)) {
                continue;
            }
            sub.replayPending.addLast(new ReplayFrame(frame, null));
        }
        if (maxReadSeq > before) {
            sub.replayCursor = maxReadSeq;
        }
        return maxReadSeq > before;
    }

    /**
     * 按"帧数 + 字节"双预算把暂存帧入队（调用方持 {@link #emitLock}），返回本次入队帧数。
     *
     * <p>字节预算按<b>UTF-8 编码后</b>的字节数计算（与传输/归档口径一致；不是字符数近似）。
     * 一页的暂存本身有 {@link #REPLAY_PAGE_FRAMES} 帧的硬界，未入队的部分在队列排空后继续。
     */
    private int enqueueReplayPendingLocked(Subscription sub) {
        int staged = 0;
        long bytes = 0L;
        while (!sub.replayPending.isEmpty() && staged < REPLAY_ENQUEUE_FRAMES) {
            ReplayFrame next = sub.replayPending.peekFirst();
            String payload;
            try {
                payload = replayPayload(next);
            } catch (Exception ex) {
                sub.replayPending.removeFirst();
                logReplayFrameDropped("serialization", next.frame().get("type"), next.frame().get("seq"), ex);
                continue;
            }
            int payloadBytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (staged > 0 && bytes + payloadBytes > REPLAY_ENQUEUE_BYTES) {
                break;
            }
            sub.replayPending.removeFirst();
            sub.queue.add(new QueuedFrame(payload, false));
            Object seqValue = next.frame().get("seq");
            if (seqValue instanceof Number number) {
                sub.wakeFloor = Math.max(sub.wakeFloor, number.longValue());
            }
            bytes += payloadBytes;
            staged++;
        }
        refreshReplayActive(sub);
        return staged;
    }

    /**
     * 把暂存帧全部入队（只用于降级收口：一页之内，不再受批预算约束）。
     */
    private void flushReplayPendingLocked(Subscription sub) {
        while (!sub.replayPending.isEmpty()) {
            ReplayFrame next = sub.replayPending.removeFirst();
            try {
                sub.queue.add(new QueuedFrame(replayPayload(next), false));
                Object seqValue = next.frame().get("seq");
                if (seqValue instanceof Number number) {
                    sub.wakeFloor = Math.max(sub.wakeFloor, number.longValue());
                }
            } catch (Exception ex) {
                logReplayFrameDropped("serialization", next.frame().get("type"), next.frame().get("seq"), ex);
            }
        }
        refreshReplayActive(sub);
    }

    /**
     * 回放帧被丢弃时的级别选择：<b>每写出器首次 WARN、其后有界采样 WARN</b>。
     *
     * <p>被丢掉的回放帧会在 seq 序列上留下洞，客户端会看到"跳号"；首次必须留一条 WARN
     * （带 type/seq 与"seq hole possible"），后续同类丢弃按稳定类别限频，避免逐帧刷屏。
     */
    private void warnOnce(AtomicBoolean flag, String message, Object... arguments) {
        if (flag.compareAndSet(false, true)) {
            log.warn(message, arguments);
        } else if (LogSampling.canAcquire("sse-warn-once-repeat")) {
            log.warn(message + " (already warned)", arguments);
        }
    }

    private void logReplayFrameDropped(String stage, Object type, Object seq, Exception ex) {
        if (this.replayFrameDropWarned.compareAndSet(false, true)) {
            log.warn("replay frame {} failed, frame dropped (seq hole possible; first warning per emitter, repeats sampled) "
                    + "runId={} type={} seq={}", stage, this.runId, type, seq, ex);
        } else if (LogSampling.canAcquire("sse-replay-frame-drop")) {
            log.warn("replay frame {} failed again, frame dropped runId={} type={} seq={}", stage, this.runId, type, seq, ex);
        }
    }

    /**
     * 回放帧的交付字节：原文口读到的帧直接用<b>归档原文</b>（逐字节一致，省一次序列化）；
     * 类型化读口/兼容实现没有原文时按帧体重序列化（语义等价）。
     */
    private String replayPayload(ReplayFrame replayFrame) throws Exception {
        if (replayFrame.rawJson() != null) {
            return replayFrame.rawJson();
        }
        return this.objectMapper.writeValueAsString(replayFrame.frame());
    }

    /**
     * 分页态的统一收敛：还有页可读、或暂存未入队完 ⇒ 回放仍在进行。
     */
    private static void refreshReplayActive(Subscription sub) {
        sub.replayActive = sub.replayMorePages || !sub.replayPending.isEmpty();
    }

    /**
     * 放弃分页回放（关闭/摘除/降级收口）：暂存与游标不再推进。
     */
    private static void abortReplayLocked(Subscription sub) {
        sub.replayPending.clear();
        sub.replayMorePages = false;
        sub.replayActive = false;
        sub.replayTerminalSeen = false;
    }

    /**
     * P1-06：drain 的<b>页空续读</b>入口：队列空但回放未读到底时，读下一页/入下一批（调用方在
     * {@link #drain} 里，不持 {@code emitLock}）。
     *
     * <p>有界：一次调用最多读一页、入一批；调用方还按 {@link #REPLAY_PAGES_PER_BEAT} 限制单拍页数。
     * 返回 {@code true} = 队列里已有新帧可写。
     */
    private record ReplayPage(List<FrameStore.RawFrame> rawPage, FrameStore.ReadResult typed, OptionalLong last,
                              boolean degraded, boolean tailConfirmed, boolean trimmed, long trimFrom, long trimTo) {
    }

    private ReplayPage readReplayPage(long from) {
        List<FrameStore.RawFrame> rawPage = List.of();
        FrameStore.ReadResult typed = null;
        OptionalLong last = null;
        boolean degraded = false;
        boolean tailConfirmed = false;
        boolean trimmed = false;
        long trimFrom = 0L;
        long trimTo = 0L;
        try {
            // 多页回放期间归档可能被裁剪：每页复核一次 xtrim 水位（廉价：tail + watermark）。
            // <b>meta.degraded 在此有意不直接降级</b>：它只表示"元数据探测失败"（末号/水位不可读），
            // 不是本页数据失败。本页以紧随其后的 {@code readRawFrom} 结果为准 —— 读失败/为空时
            // 下面的末号+类型化兜底会把它收口为 UNKNOWN/降级；读成功时，游标之后的裁剪会由下一页的
            // replayMeta 再探一次（水位恢复可读时给出 truncated）。因此这里只消费 truncated，不因
            // 元数据抖动把一条仍可正常回放的连接提前判死。
            FrameStore.ReadResult meta = this.frames.replayMeta(this.runId, from);
            if (meta != null && meta.truncated()) {
                trimmed = true;
                trimFrom = meta.trimmedFromSeq();
                trimTo = meta.trimmedToSeq();
            }
            rawPage = this.frames.readRawFrom(this.runId, from, REPLAY_PAGE_FRAMES);
            if (rawPage == null) {
                rawPage = List.of();
            }
            if (rawPage.isEmpty()) {
                last = lastSeqIfKnownQuietly();
                if (last != null && last.isPresent() && last.getAsLong() <= from) {
                    tailConfirmed = true;
                } else {
                    typed = this.frames.readFromWithStatus(this.runId, from, REPLAY_PAGE_FRAMES);
                    degraded = typed == null || typed.degraded();
                }
            }
        } catch (RuntimeException ex) {
            // I-minor：续读异常不得逃出 drain（那会静默停在半途）；按读降级收流。
            warnOnce(this.replayReadFailureWarned, "replay continuation failed runId={} from={} (closing stream degraded)", this.runId, from, ex);
            degraded = true;
        }
        return new ReplayPage(rawPage, typed, last, degraded, tailConfirmed, trimmed, trimFrom, trimTo);
    }

    private record ReplayProgress(boolean progressed, int readCount, boolean degraded) {
    }

    private ReplayProgress stageReplayPage(Subscription sub, ReplayPage page) {
        List<Map<String, Object>> typedPage = page.typed() == null ? null : firstReplayPage(page.typed().frames());
        boolean progressed = false;
        int readCount = 0;
        boolean degraded = page.degraded();
        if (!page.rawPage().isEmpty()) {
            readCount = page.rawPage().size();
            progressed = isStageRawReplayPageLocked(sub, page.rawPage());
        } else if (typedPage != null) {
            readCount = typedPage.size();
            progressed = isStageReplayPageLocked(sub, typedPage);
            if (!degraded && typedPage.isEmpty() && (page.last() == null || page.last().isEmpty())) {
                // 读口说"没有下一页"、末号又不可知 ⇒ 不能宣称读到底。
                degraded = true;
            }
        }
        return new ReplayProgress(progressed, readCount, degraded);
    }

    private boolean isAdvanceReplay(Subscription sub) {
        if (deliveryIdentity() == null) {
            // 身份不可确认：不读任何帧键；终止本订阅的回放（客户端按失败/重挂恢复）。
            synchronized (this.emitLock) {
                abortReplayLocked(sub);
            }
            return false;
        }
        long from;
        synchronized (this.emitLock) {
            if (sub.closed) {
                abortReplayLocked(sub);
                return false;
            }
            if (!sub.replayPending.isEmpty()) {
                return enqueueReplayPendingLocked(sub) > 0;
            }
            if (!sub.replayMorePages) {
                refreshReplayActive(sub);
                return false;
            }
            from = sub.replayCursor;
        }
        // I4：分页 Redis 读在锁外（XRANGE + COUNT，一次一页）。读期间订阅可能被关闭/放弃，
        // 回锁后必须校验 closed / 游标未变 / 仍在分页，才允许把这一页并入暂存。
        ReplayPage page = readReplayPage(from);
        return isMergeReplayPageLocked(sub, from, page);
    }

    private boolean isMergeReplayPageLocked(Subscription sub, long from, ReplayPage page) {
        synchronized (this.emitLock) {
            if (sub.closed || sub.replayCursor != from || !sub.replayMorePages) {
                // 读期间已关闭/已被放弃（unresolvedEnding 的 abort）/游标已推进：丢弃这次读。
                return false;
            }
            if (page.trimmed()) {
                // 回放期间水位推进（MAXLEN 裁剪越过了游标）：不能静默跳过，按既有 N-2 口径降级收流。
                log.warn("archive trimmed during replay runId={} from={} gap={}..{} (closing degraded, not silently skipping)",
                        this.runId, from, page.trimFrom(), page.trimTo());
                finishDegradedReplay(sub, new ReplayResult(0, true, sub.replayTerminalSeen, true,
                        page.trimFrom(), page.trimTo()));
                return !sub.queue.isEmpty();
            }
            if (page.tailConfirmed()) {
                sub.replayMorePages = false;
                refreshReplayActive(sub);
                if (sub.replayTerminalSeen) {
                    sub.terminating = true;
                } else {
                    // M6：离线重挂读到尾却没有终帧 ⇒ 进入终帧宽限（复用 terminalUnarchived）。
                    scheduleUnarchivedTerminalGraceIfNeeded(sub);
                }
                return false;
            }
            ReplayProgress progress = stageReplayPage(sub, page);
            if (progress.degraded()) {
                // 读降级：已读到的帧照常交付，随后按 N-2 补 typed error 并收尾（不再猜剩余页）。
                finishDegradedReplay(sub, new ReplayResult(0, true, sub.replayTerminalSeen));
                return !sub.queue.isEmpty();
            }
            // 原文口非空 ⇒ 可能还有下一页（记录数可能 ≠ 可解析帧数，坏帧时不能按 size 判尾），
            // 多读一次"空页 + 末号确认"才收敛；类型化口按"读满一页"判可能还有下一页。
            sub.replayMorePages = progress.progressed()
                    && (!page.rawPage().isEmpty() || progress.readCount() >= REPLAY_PAGE_FRAMES);
            refreshReplayActive(sub);
            if (!sub.replayMorePages && sub.replayTerminalSeen) {
                // 回放读到了终帧且没有更多页 ⇒ 队列排空后收尾（与终态回放同一口径）。
                sub.terminating = true;
            }
            if (!sub.replayMorePages) {
                // M6：离线重挂（快照终态、归档无终帧）在此排空后走既有 terminalUnarchived 恢复链。
                scheduleUnarchivedTerminalGraceIfNeeded(sub);
            }
            if (sub.replayCursor > 0L) {
                this.lastFanoutSeq.accumulateAndGet(sub.replayCursor, Math::max);
            }
            return enqueueReplayPendingLocked(sub) > 0;
        }

    }

    /**
     * 归档末号读取（读失败一律返回 {@code null}，调用方按"不可知"处理）。
     */
    private OptionalLong lastSeqIfKnownQuietly() {
        try {
            return this.frames.lastSeqIfKnown(this.runId);
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("sse-chat-runtime-catch"))
                log.warn("replay continuation tail probe failed runId={}", this.runId, ex);
            return null;
        }
    }

    /**
     * 归档扫描结果（只读探测用；{@code UNKNOWN} = 读失败/不支持原文口，调用方按"不可知"处理）。
     */
    private enum ArchiveScan {
        FOUND, NOT_FOUND, UNKNOWN
    }

    /**
     * <b>COUNT 分页</b>扫描归档：生产实现优先按真实 entry ID 物理分页，预算只扣实际条目数；
     * 兼容实现不支持物理读口时才回退原 seq 原文/类型化读口。
     *
     * <p>预算耗尽时用<b>末号确认</b>：末号 ≤ 游标 ⇒ 已到尾部，可以返回 {@code NOT_FOUND}；
     * 否则返回 {@code UNKNOWN}（保守，不把"没扫完"说成"没有"）。
     *
     * @param afterSeq  起始游标（排他）
     * @param maxFrames 扫描帧预算
     * @param match     命中判据（入参为解析后的帧体）
     */
    private ArchiveScan scanArchive(long afterSeq, int maxFrames, java.util.function.Predicate<Map<String, Object>> match) {
        TenantUser identity = deliveryIdentity();
        if (identity == null) {
            return ArchiveScan.UNKNOWN;
        }
        try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
            return scanArchiveScoped(afterSeq, maxFrames, match);
        }
    }

    private record ArchivePage(ArchiveScan outcome, long cursor, int readCount) {
    }

    private ArchivePage scanRawArchivePage(long cursor, int pageLimit,
                                           java.util.function.Predicate<Map<String, Object>> match) {
        List<FrameStore.RawFrame> raw;
        try {
            raw = this.frames.readRawFrom(this.runId, cursor, pageLimit);
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("sse-chat-runtime-catch"))
                log.warn("archive probe raw read failed runId={} cursor={}", this.runId, cursor, ex);
            return new ArchivePage(ArchiveScan.UNKNOWN, cursor, 0);
        }
        if (CollectionUtils.isEmpty(raw)) {
            return new ArchivePage(null, cursor, 0);
        }
        long next = cursor;
        for (FrameStore.RawFrame item : raw) {
            if (item.seq() > next) {
                next = item.seq();
            }
            Map<String, Object> parsed = parseRawFrame(item);
            if (parsed != null && match.test(parsed)) {
                return new ArchivePage(ArchiveScan.FOUND, next, raw.size());
            }
        }
        if (next <= cursor) {
            return new ArchivePage(ArchiveScan.UNKNOWN, next, raw.size());
        }
        return new ArchivePage(null, next, raw.size());
    }

    private ArchivePage scanTypedArchivePage(long cursor, int pageLimit,
                                             java.util.function.Predicate<Map<String, Object>> match) {
        FrameStore.ReadResult typed;
        try {
            typed = this.frames.readFromWithStatus(this.runId, cursor, pageLimit);
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("sse-chat-runtime-catch"))
                log.warn("archive probe compat reader failed runId={} cursor={}", this.runId, cursor, ex);
            return new ArchivePage(ArchiveScan.UNKNOWN, cursor, 0);
        }
        if (typed == null || typed.degraded()) {
            return new ArchivePage(ArchiveScan.UNKNOWN, cursor, 0);
        }
        List<Map<String, Object>> page = firstReplayPage(typed.frames());
        if (page.isEmpty()) {
            OptionalLong last = lastSeqIfKnownQuietly();
            if (last == null || last.isEmpty()) {
                return new ArchivePage(ArchiveScan.UNKNOWN, cursor, 0);
            }
            return new ArchivePage(last.getAsLong() <= cursor ? ArchiveScan.NOT_FOUND : ArchiveScan.UNKNOWN, cursor, 0);
        }
        long next = cursor;
        for (Map<String, Object> frame : page) {
            Object seq = frame.get("seq");
            if (seq instanceof Number number && number.longValue() > next) {
                next = number.longValue();
            }
            if (match.test(frame)) {
                return new ArchivePage(ArchiveScan.FOUND, next, page.size());
            }
        }
        if (next <= cursor) {
            return new ArchivePage(ArchiveScan.UNKNOWN, next, page.size());
        }
        if (page.size() < pageLimit) {
            OptionalLong last = lastSeqIfKnownQuietly();
            if (last != null && last.isPresent() && last.getAsLong() <= next) {
                return new ArchivePage(ArchiveScan.NOT_FOUND, next, page.size());
            }
            if (last == null || last.isEmpty()) {
                return new ArchivePage(ArchiveScan.UNKNOWN, next, page.size());
            }
        }
        return new ArchivePage(null, next, page.size());
    }

    private ArchiveScan scanArchiveScoped(long afterSeq, int maxFrames,
                                          java.util.function.Predicate<Map<String, Object>> match) {
        ArchiveScan physical = scanArchiveByEntry(afterSeq, maxFrames, match);
        if (physical != null) {
            return physical;
        }
        long cursor = Math.max(0L, afterSeq);
        int scanned = 0;
        boolean typedFallback = false;
        while (scanned < maxFrames) {
            int pageLimit = Math.min(REPLAY_PAGE_FRAMES, maxFrames - scanned);
            if (!typedFallback) {
                ArchivePage page = scanRawArchivePage(cursor, pageLimit, match);
                if (page.outcome() != null) {
                    return page.outcome();
                }
                if (page.readCount() > 0) {
                    scanned += page.readCount();
                    cursor = page.cursor();
                    continue;
                }
                // 契约：空列表 = 原文口不支持（或读失败/到底）；回退类型化读口，避免把"不支持"当"没有"。
                typedFallback = true;
            }
            ArchivePage page = scanTypedArchivePage(cursor, pageLimit, match);
            if (page.outcome() != null) {
                return page.outcome();
            }
            scanned += page.readCount();
            cursor = page.cursor();
        }
        // 预算耗尽：只有末号确认已到尾部才能说"确实没有"。
        OptionalLong last = lastSeqIfKnownQuietly();
        if (last != null && last.isPresent() && last.getAsLong() <= cursor) {
            return ArchiveScan.NOT_FOUND;
        }
        return ArchiveScan.UNKNOWN;
    }

    /**
     * 生产读口按真实 entry ID 分页；null = 兼容实现不支持，交回原 seq 读口。
     */
    private ArchiveScan scanArchiveByEntry(long afterSeq, int maxFrames,
                                           java.util.function.Predicate<Map<String, Object>> match) {
        String cursor = afterSeq > 0L ? afterSeq + "-0" : null;
        int scanned = 0;
        while (scanned < maxFrames) {
            int pageLimit = Math.min(REPLAY_PAGE_FRAMES, maxFrames - scanned);
            FrameStore.RawEntryPage page;
            try {
                page = this.frames.readRawAfterEntry(this.runId, cursor, pageLimit);
            } catch (RuntimeException ex) {
                if (LogSampling.canAcquire("sse-chat-runtime-catch"))
                    log.warn("archive physical read failed runId={} cursor={}", this.runId, cursor, ex);
                return ArchiveScan.UNKNOWN;
            }
            if (page == null) {
                return scanned == 0 ? null : ArchiveScan.UNKNOWN;
            }
            if (page.degraded()) {
                return ArchiveScan.UNKNOWN;
            }
            List<FrameStore.RawEntryFrame> entries = ObjectUtils.getIfNull(page.frames(), List::of);
            if (entries.isEmpty()) {
                return ArchiveScan.NOT_FOUND;
            }
            String previous = cursor;
            for (FrameStore.RawEntryFrame item : entries) {
                Map<String, Object> parsed = parseRawFrame(new FrameStore.RawFrame(item.seq(), item.json()));
                if (parsed != null && match.test(parsed)) {
                    return ArchiveScan.FOUND;
                }
            }
            scanned += entries.size();
            cursor = entries.getLast().entryId();
            if (cursor == null || cursor.equals(previous)) {
                return ArchiveScan.UNKNOWN;
            }
            if (entries.size() < pageLimit) {
                return ArchiveScan.NOT_FOUND;
            }
        }
        return ArchiveScan.UNKNOWN;
    }

    /**
     * 物理分页从调用方下界开始；条数预算不再换算成 seq 差值。
     */
    private long terminalScanStart(long floor) {
        return Math.max(0L, floor);
    }

    /**
     * 把一页<b>归档原文</b>放进分页暂存（调用方持 {@link #emitLock}）。
     *
     * <p>只解析一次（type/seq 用于终帧与孤儿判定），入队仍复用队列元素；游标按原文记录的
     * {@code seq} 推进 —— 即使某条帧体坏损（解析失败被跳过），也不会让分页在同一位置自旋。
     */
    private boolean isStageRawReplayPageLocked(Subscription sub, List<FrameStore.RawFrame> page) {
        long before = sub.replayCursor;
        long maxRead = before;
        for (FrameStore.RawFrame raw : page) {
            long seq = raw.seq();
            if (seq > maxRead) {
                maxRead = seq;
            }
            Map<String, Object> frame;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = this.objectMapper.readValue(raw.json(), Map.class);
                frame = parsed;
            } catch (Exception ex) {
                logReplayFrameDropped("parse", null, seq, ex);
                continue;
            }
            String type = String.valueOf(frame.get("type"));
            if ("done".equals(type) || "error".equals(type)) {
                sub.replayTerminalSeen = true;
                this.terminalFrameSeen.set(true);
            }
            if (RunStore.HEARTBEAT_TYPE.equals(type)) {
                continue;
            }
            if (sub.replayOrphanFloor != null && seq <= sub.replayOrphanFloor) {
                continue;
            }
            boolean hadSeq = frame.get("seq") instanceof Number;
            frame.putIfAbsent("seq", seq);
            // 归档帧自带 seq ⇒ 直接交付归档原文（逐字节一致）；缺号的老帧补号后只能重序列化。
            sub.replayPending.addLast(new ReplayFrame(frame, hadSeq ? raw.json() : null));
        }
        if (maxRead > before) {
            sub.replayCursor = maxRead;
        }
        return maxRead > before;
    }

    /**
     * 孤儿判定：帧序号不大于前端已知的最大序号 ⇒ 该帧前端已有，不重发。
     */
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

    /**
     * 是否已进入<b>终态收尾</b>（{@link #complete()} 之后）。
     *
     * <p>包内可见：空闲回收（{@code RunRegistry#sweepIdle}）的判据之一 —— 收尾中的条目由写出器
     * 自己的终态钩子收，抢在它前面摘会让仍在排空的尾帧失去归属。
     */
    boolean isTerminating() {
        return this.terminating.get();
    }

    /**
     * 正在 {@link #emit} 内的线程数（含已进入方法、还在等 {@code emitLock} 的那一段）。
     *
     * <p>包内可见：空闲回收的判据之一 —— 非零表示"已宣告出帧、尚未落档/入队"，这时摘掉条目会让
     * 同一轮在进程内出现第二个写出器（帧号与扇出分家）。
     */
    int inFlightEmits() {
        return this.emitting.get();
    }

    // ── 帧 ──────────────────────────────────────────────────────────────────

    /**
     * 首包：runId 由服务端生成，前端据此做 reattach。
     */
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
        if (StringUtils.isEmpty(text)) {
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
        } finally {
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
        } finally {
            flushWakeSyncIfUnlocked();
        }
    }

    /**
     * 单条 delta 帧（键集合与截断专项之前逐字一致；思考段只多一个 {@code kind}）。
     */
    private void emitDeltaFrame(String kind, String text) {
        Map<String, Object> frame = frame("delta");
        frame.put("messageId", this.assistantMessageId);
        if (kind != null) {
            frame.put("kind", kind);
        }
        frame.put("text", text);
        emit(frame);
    }

    public boolean reasoningEnd() {
        return isReasoningEnded();
    }

    public boolean textStart() {
        return isTextStarted();
    }

    public boolean textEnd(int chars) {
        return isTextEnded(chars);
    }

    /**
     * <b>L-91</b>：闭合思考段（{@code message_end(kind=reasoning, chars)}）；未开过段则是幂等空操作。
     *
     * <p>
     * 调用点由"段互斥"隐式保证：{@link #isTextStarted()}（正文段开始前）与 {@link #isTextEnded(int)}
     * （本轮收尾 / 纯思考轮）都会先收口思考段，因此<b>不存在</b>"正文段开了、思考段还开着"的形态。
     *
     * <p>
     * <b>复审 I4</b>：CAS 关段、{@code chars} 的读取清零、{@code message_end} 的落档/扇出
     * <b>必须同处 {@code emitLock}</b>。否则并发的 {@link #reasoningDelta} 可以落在"关段之后、
     * message_end 之前"（前端看到 end 后还有 delta）或"chars 已读、delta 还没算进 chars"
     * （{@code message_end.chars} 小于实际拼接长度）。锁与 {@link #emit} 可重入。
     */
    public boolean isReasoningEnded() {
        try {
            synchronized (this.emitLock) {
                if (!this.reasoningSegmentOpen.compareAndSet(true, false)) {
                    return false;
                }
                messageBoundary("end", "reasoning", Map.of("chars", this.reasoningChars.getAndSet(0)));
                return true;
            }
        } finally {
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

    /**
     * 正文段 start（同一条消息只开一次；重复调用无副作用）。
     */
    public boolean isTextStarted() {
        // L-91：段互斥 —— 正文开始前思考段必须先闭合（前端按段分流 delta，两段不得交叠）。
        // 复审 I4：关思考段与开正文段必须同锁，否则并发的 reasoningDelta 可以插在
        // message_end(reasoning) 与 message_start(text) 之间（前端看到"两段交叠"）。
        try {
            synchronized (this.emitLock) {
                isReasoningEnded();
                if (!this.textSegmentOpen.compareAndSet(false, true)) {
                    return false;
                }
                messageBoundary("start", "text", null);
                return true;
            }
        } finally {
            flushWakeSyncIfUnlocked();
        }
    }

    /**
     * 正文段 end（带本段字符数，便于前端与 done 帧对账）；未开过段则不发（幂等）。
     */
    public boolean isTextEnded(int chars) {
        // L-91：收尾同样先闭合思考段 —— 纯思考轮（一个字正文都没出）若只靠 textEnd 收尾，
        // 思考段会永远停在"开着"的状态（前端会看到一段永不封口的段）。
        // 复审 I4：与 reasoningEnd 同锁，避免并发的思考 delta 落在正文收尾之后。
        try {
            synchronized (this.emitLock) {
                isReasoningEnded();
                if (!this.textSegmentOpen.compareAndSet(true, false)) {
                    return false;
                }
                messageBoundary("end", "text", Map.of("chars", chars));
                return true;
            }
        } finally {
            flushWakeSyncIfUnlocked();
        }
    }

    /**
     * 挂起公告：快照已外置（带 Redis 键名与写入者实例，供前端"可重挂"提示）。
     */
    public void suspended(List<PendingToolCall> pendings, String stateKey, String pendingKey, String writtenBy) {
        Map<String, Object> frame = frame("suspended");
        frame.put("runId", this.runId);
        frame.put("toolCalls", pendings.stream().map(PendingToolCall::asMap).toList());
        frame.put("stateKey", stateKey);
        frame.put("pendingKey", pendingKey);
        frame.put("writtenBy", writtenBy);
        emit(frame);
    }

    /**
     * 挂起期心跳：证明"挂起期间连接仍然活着"，也是前端展示倒计时的节拍。
     */
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
     * @param phase     {@link TurnKeepalive#PHASE_COMPACTING}（真实 L2 模型摘要进行中）/
     *                  {@link TurnKeepalive#PHASE_STREAMING}（模型生成/准备：含首包等待、包间静默、慢工具）
     * @param elapsedMs <b>本阶段</b>已用毫秒（服务端钟；不伪造进度百分比）
     * @param attempt   本尝试序号（0 = 尚未进入尝试循环，例如压缩阶段）
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
        return isToolStartEmitted(pending);
    }

    public boolean isToolStartEnsured(PendingToolCall pending) {
        return isToolStartEmitted(pending);
    }

    public boolean isToolStartEmitted(PendingToolCall pending) {
        String toolCallId = pending == null ? null : pending.getToolCallId();
        if (toolCallId == null) {
            return false;
        }
        // COUNT 分页扫描（不整窗读）：从"末号 − 归档窗口冗余"起扫（幸存归档必在窗口内），
        // 命中即停；预算耗尽且末号确认到尾部才判"确实没有"。
        ArchiveScan scan = scanArchive(terminalScanStart(0L), ARCHIVE_SCAN_BUDGET, frame -> "tool_start"
                .equals(String.valueOf(frame.get("type"))) && toolCallId.equals(frame.get("toolCallId")));
        if (scan == ArchiveScan.FOUND) {
            return false;
        }
        if (scan == ArchiveScan.UNKNOWN) {
            warnOnce(this.replayReadFailureWarned, "resume tool_start replay skipped: event log paging probe incomplete/degraded "
                            + "runId={} toolCallId={} (incomplete read != absent, not replaying)",
                    this.runId, toolCallId);
            return false;
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
     * {@code target} 从工具参数 JSON 提取 {@code {taskId?, defCode?, step?, page?}}，提取不到不写该键。
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
        toolResult(pending, ok, reused, result, resultView, null);
    }

    /**
     * 带<b>结构化用户文案参数</b>的工具结局帧（additive；帧类型不变）。
     *
     * <p>{@code userTextArgs} 供前端 {@code t(code, args)} 插值（如 create_task 单轮上限的
     * {@code taskId}）；缺省 null = 帧键集合与旧版逐字一致。
     */
    public void toolResult(PendingToolCall pending, boolean ok, boolean reused, String result,
                           ResultView resultView, java.util.Map<String, Object> userTextArgs) {
        ToolOutcome outcome = pending.effectiveOutcome();
        // M4：ok 保留在签名里（不改调用面），但契约上它只能是 outcome 的派生值。
        // 传错即 fail-fast —— 否则"帧 ok 与信封 outcome 互相矛盾"会被前端按任一字段误读。
        if (ok != outcome.ok()) {
            throw new CodedIllegalArgumentException(ErrorCodes.TOOL_RESULT_OUTCOME_MISMATCH, "tool_result frame ok is inconsistent with the derived outcome: ok=" + ok
                    + ", outcome=" + outcome.wire() + " (toolCallId=" + pending.getToolCallId() + ")");
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
        Map<String, Object> target = ToolResultEnvelope.targetOf(pending.getArguments());
        if (MapUtils.isNotEmpty(target)) {
            frame.put("target", target);
        }
        frame.put("messageId", toolMessageId(pending));
        // N9/S9：{@code result} 保留中文用户摘要回退，{@code userTextCode} 供前端按原码本地化；
        // {@code outcome}/{@code code}/{@code retryable} 不动，模型侧信封与台账原文也不动。
        // markModelView 仍用<b>原文</b>判定模型视图是否被截断，显示摘要不改变机器可读事实。
        frame.put("result", ToolResultUserText.forUser(code, result));
        frame.put("userTextCode", ToolResultUserText.userTextCode(code));
        if (MapUtils.isNotEmpty(userTextArgs)) {
            frame.put("userTextArgs", userTextArgs);
        }
        if (resultView != null) {
            frame.put("resultView", resultView.toMap());
        }
        // 预检展示摘要（additive 可选键，白名单 target/impact/conditionSummary/publishMode）：
        // 刷新恢复走全量帧重放时，已决确认工具的 suspended/confirm_request 帧会被"已决"闸跳过，
        // 只有结局帧能把卡头业务标题与条件/影响面重建回来。无预检的工具不产该键 —— 帧形状不变。
        Map<String, Object> preflight = TranscriptEntries.displayPreflight(pending.getPreflight());
        if (preflight != null) {
            frame.put("preflight", preflight);
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
        Map<String, Object> target = ToolResultEnvelope.targetOf(pending.getArguments());
        if (MapUtils.isNotEmpty(target)) {
            frame.put("target", target);
        }
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
        if (this.resultLimiter == null || StringUtils.isEmpty(result)) {
            return;
        }
        ToolResultLimiter.Truncation view = this.resultLimiter.forModel(toolName, result);
        if (!view.truncated()) {
            return;
        }
        frame.put(TruncationContract.TRUNCATED_FIELD, true);
        frame.put(TruncationContract.NOTICE_CODE_FIELD, TruncationContract.NOTICE_CODE_MODEL_VIEW_TRUNCATED);
        frame.put(TruncationContract.STRATEGY_FIELD, view.strategy());
        frame.put(TruncationContract.ORIGINAL_CHARS_FIELD, view.originalChars());
        frame.put(TruncationContract.RETURNED_CHARS_FIELD, view.returnedChars());
        if (log.isDebugEnabled())
            log.debug("model-side view truncated runId={} tool={} originalChars={} returnedChars={} strategy={}", this.runId,
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
     *              {@code upstreamEvents} / {@code terminalSignal}）；取消终态为 {@code cancelled=true}，
     *              与"正常完成"共用同一个终帧类型（前端的终态处理不必分两套）。
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
        if (MapUtils.isNotEmpty(usage)) {
            frame.put("usage", usage);
        }
        if (model != null) {
            frame.put("model", model);
        }
        if (extra != null) {
            frame.putAll(extra);
        }
        if (MapUtils.isNotEmpty(context)) {
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
        // B3：事件归属必须由发帧侧显式给出（不靠前端 activeRunId 猜；跨实例/重挂时后者可能为空）
        frame.put("runId", this.runId);
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
        if (MapUtils.isNotEmpty(extra)) {
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
     * @param code    复用既有机器码（本路径 = {@code AI_REDIS_UNAVAILABLE}）
     * @param message 中性用户文案（不声称成功/失败/取消）
     * @param extra   additive 键（{@code null}/空 = 只有 code/message）
     * @return 收到本帧的订阅者数（{@code 0} = 此刻没有连接，也没有需要解开的等待）
     */
    public int unresolvedEnding(String code, String message, Map<String, Object> extra) {
        return unresolvedEnding(code, message, extra, false);
    }

    /**
     * 同 {@link #unresolvedEnding(String, String, Map)}，额外声明"本结局是在身份不可确认/设施不可用
     * 时投递的"：把该帧标记为 {@link QueuedFrame#identityDegraded}（帧级许可），投递链只对这一帧
     * 跳过身份闸，投递完即失效（P1-09 minor）。
     */
    int unresolvedEnding(String code, String message, Map<String, Object> extra, boolean identityDegraded) {
        List<Subscription> targets = new ArrayList<>();
        final String payload;
        try {
            synchronized (this.emitLock) {
                // 锁内复查：真实终帧（done/error）刚落档/刚被唤醒补到 ⇒ 绝不再追加合成 error
                // （否则前端先收 done 再收 error，轮次被改写）。
                if (this.terminating.get() || this.terminalFrameSeen.get() || this.subscribers.isEmpty()) {
                    return 0;
                }
                Map<String, Object> frame = frame("error");
                frame.put("runId", this.runId);
                frame.put("code", code);
                frame.put("message", message);
                if (MapUtils.isNotEmpty(extra)) {
                    frame.putAll(extra);
                }
                try {
                    payload = this.objectMapper.writeValueAsString(frame);
                } catch (Exception ex) {
                    log.error("resume degraded outcome frame serialization failed runId={} (frame skipped)", this.runId, ex);
                    return 0;
                }
                for (Subscription sub : this.subscribers) {
                    if (sub.closed || sub.evicted.get()) {
                        continue;
                    }
                    // P1-06：结局帧必须是这条连接上的最后一帧 —— 若该订阅者还在分页回放，
                    // 先放弃后续页（暂存里是更早的归档帧，排在结局帧之后会造成乱序）。
                    abortReplayLocked(sub);
                    // 与入队同锁置位：并发出帧在该订阅者上不再入队新帧（结局帧之后不得再有帧）。
                    sub.terminating = true;
                    // 回放批标记：结局帧不受实时队列容量限制（与 finishDegradedReplay 同口径）。
                    // 身份降级是<b>帧级</b>许可：只这一帧跳过身份闸。
                    sub.queue.add(new QueuedFrame(payload, false, identityDegraded));
                    if (identityDegraded) {
                        sub.identityDegradedQueued.incrementAndGet();
                    }
                    targets.add(sub);
                }
                // F1：合成终帧也是本连接的终点 ⇒ 本地保活此后不得再入队。
                this.terminalFrameSeen.set(true);
            }
        } finally {
            flushWakeSyncIfUnlocked();
        }
        // 收尾动作在锁外做（与 completeSubscriptions 同一理由：emitter.complete() 与 send 同为
        // synchronized，持锁调用会把出帧线程一起拖住）。
        for (Subscription sub : targets) {
            completeWhenDrained(sub);
        }
        if (this.terminalFrameSeen.get()) {
            // F1：合成终帧已投出 ⇒ 停本地保活。
            stopAttachKeepalive();
        }
        log.info("resume degraded outcome delivered runId={} subscribers={} code={}", this.runId, targets.size(), code);
        return targets.size();
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 返回该 transcript 条目的完整帧段序。挂起续跑会换掉进程内 emitter，本地时间线不完整时，
     * 从同一份归档帧重建；读降级、归档截断或内容对不上仍返回 null。
     */
    public List<TranscriptEntry.SegmentView> transcriptSegments(String entryId,
                                                                List<TranscriptEntry.ToolCallView> calls, String text, String reasoning) {
        List<TranscriptEntry.ToolCallView> expectedCalls = ObjectUtils.defaultIfNull(calls, List.of());
        synchronized (this.emitLock) {
            List<TranscriptEntry.SegmentView> local = transcriptSegments(this.transcriptTimeline, entryId,
                    expectedCalls, text, reasoning);
            if (local != null) {
                return local;
            }
        }
        return archivedTranscriptSegments(entryId, expectedCalls, text, reasoning);
    }

    private List<TranscriptEntry.SegmentView> archivedTranscriptSegments(String entryId,
                                                                         List<TranscriptEntry.ToolCallView> calls, String text, String reasoning) {
        for (int attempt = 0; attempt < 2; attempt++) {
            long generation;
            synchronized (this.emitLock) {
                generation = this.transcriptFrameGeneration;
                if (this.archivedTranscriptTimeline != null && this.archivedTranscriptGeneration == generation) {
                    return transcriptSegments(this.archivedTranscriptTimeline, entryId, calls, text, reasoning);
                }
            }
            FrameStore.ReadResult archived;
            try {
                archived = this.frames.readFromWithStatus(this.runId, 0L, 0);
            } catch (RuntimeException ex) {
                if (LogSampling.canAcquire("sse-chat-runtime-catch"))
                    log.warn("transcript timeline archive read failed runId={}", this.runId, ex);
                return null;
            }
            if (archived == null || archived.degraded() || archived.truncated() || archived.frames() == null) {
                if (log.isDebugEnabled())
                    log.debug("transcript timeline archive incomplete runId={} degraded={} truncated={}", this.runId,
                            archived == null || archived.degraded(), archived == null || archived.truncated());
                return null;
            }
            List<TranscriptSegment> timeline = new ArrayList<>();
            for (Map<String, Object> frame : archived.frames()) {
                if (frame == null || !(frame.get("seq") instanceof Number number) || number.longValue() <= 0L) {
                    return null;
                }
                recordTranscriptFrame(timeline, frame, number.longValue());
            }
            synchronized (this.emitLock) {
                if (generation == this.transcriptFrameGeneration) {
                    this.archivedTranscriptTimeline = timeline;
                    this.archivedTranscriptGeneration = generation;
                    return transcriptSegments(timeline, entryId, calls, text, reasoning);
                }
                if (this.archivedTranscriptTimeline != null
                        && this.archivedTranscriptGeneration == this.transcriptFrameGeneration) {
                    return transcriptSegments(this.archivedTranscriptTimeline, entryId, calls, text, reasoning);
                }
            }
        }
        return null;
    }

    private static List<TranscriptEntry.SegmentView> transcriptSegments(List<TranscriptSegment> timeline,
                                                                        String entryId, List<TranscriptEntry.ToolCallView> expectedCalls, String text, String reasoning) {
        List<TranscriptSegment> existing = timeline.stream()
                .filter(segment -> entryId.equals(segment.entryId)).toList();
        if (!existing.isEmpty()) {
            return isCompleteTranscriptSegments(existing, expectedCalls, text, reasoning, entryId)
                    ? transcriptSegmentViews(existing) : null;
        }
        List<TranscriptSegment> candidate = expectedCalls.isEmpty()
                ? transcriptPlainTextCandidate(timeline, expectedCalls, text, reasoning)
                : transcriptToolCandidate(timeline, expectedCalls, text, reasoning);
        if (candidate == null || !isCompleteTranscriptSegments(candidate, expectedCalls, text, reasoning, null)) {
            return null;
        }
        for (TranscriptSegment segment : candidate) {
            segment.entryId = entryId;
        }
        return transcriptSegmentViews(candidate);
    }

    private static List<TranscriptSegment> transcriptPlainTextCandidate(List<TranscriptSegment> timeline,
                                                                        List<TranscriptEntry.ToolCallView> calls, String text, String reasoning) {
        List<TranscriptSegment> candidate = new ArrayList<>();
        for (TranscriptSegment segment : timeline) {
            if (segment.entryId != null || "tool".equals(segment.kind)) {
                if (isCompleteTranscriptSegments(candidate, calls, text, reasoning, null)) {
                    return candidate;
                }
                candidate.clear();
            } else {
                candidate.add(segment);
            }
        }
        return isCompleteTranscriptSegments(candidate, calls, text, reasoning, null) ? candidate : null;
    }

    private static List<TranscriptSegment> transcriptToolCandidate(List<TranscriptSegment> timeline,
                                                                   List<TranscriptEntry.ToolCallView> calls, String text, String reasoning) {
        int first = -1;
        int last = -1;
        for (TranscriptEntry.ToolCallView call : calls) {
            int found = transcriptToolSegment(timeline, call == null ? null : call.id());
            if (found < 0) {
                return null;
            }
            first = first < 0 ? found : Math.min(first, found);
            last = Math.max(last, found);
        }
        if (StringUtils.isEmpty(text) && StringUtils.isEmpty(reasoning)) {
            return new ArrayList<>(timeline.subList(first, last + 1));
        }
        int start = 0;
        for (int i = 0; i < first; i++) {
            TranscriptSegment segment = timeline.get(i);
            if (segment.entryId != null || "tool".equals(segment.kind)) {
                start = i + 1;
            }
        }
        return new ArrayList<>(timeline.subList(start, last + 1));
    }

    private static boolean isCompleteTranscriptSegments(List<TranscriptSegment> candidate,
                                                        List<TranscriptEntry.ToolCallView> calls, String text, String reasoning, String entryId) {
        List<String> expectedIds = new ArrayList<>(calls.size());
        for (TranscriptEntry.ToolCallView call : calls) {
            if (call == null || StringUtils.isBlank(call.id())) {
                return false;
            }
            expectedIds.add(call.id());
        }
        StringBuilder actualText = new StringBuilder();
        StringBuilder actualReasoning = new StringBuilder();
        List<String> actualCalls = new ArrayList<>();
        for (TranscriptSegment segment : candidate) {
            if (segment.entryId != null && !segment.entryId.equals(entryId)) {
                return false;
            }
            switch (segment.kind) {
                case "text" -> actualText.append(segment.text);
                case "reasoning" -> actualReasoning.append(segment.text);
                case "tool" -> actualCalls.add(segment.toolCallId);
                default -> {
                    return false;
                }
            }
        }
        if (!actualText.toString().equals(StringUtils.defaultString(text))
                || !actualReasoning.toString().equals(StringUtils.defaultString(reasoning))) {
            return false;
        }
        return actualCalls.equals(expectedIds);
    }

    private static List<TranscriptEntry.SegmentView> transcriptSegmentViews(List<TranscriptSegment> segments) {
        return segments.stream()
                .map(segment -> new TranscriptEntry.SegmentView(segment.kind,
                        segment.text == null ? null : segment.text.toString(), segment.toolCallId, segment.seq))
                .toList();
    }

    private static int transcriptToolSegment(List<TranscriptSegment> timeline, String toolCallId) {
        if (StringUtils.isBlank(toolCallId)) {
            return -1;
        }
        for (int i = 0; i < timeline.size(); i++) {
            TranscriptSegment segment = timeline.get(i);
            if ("tool".equals(segment.kind) && toolCallId.equals(segment.toolCallId)) {
                return i;
            }
        }
        return -1;
    }

    private void recordTranscriptFrame(Map<String, Object> frame, long seq) {
        this.transcriptFrameGeneration++;
        this.archivedTranscriptTimeline = null;
        recordTranscriptFrame(this.transcriptTimeline, frame, seq);
    }

    private static void recordTranscriptFrame(List<TranscriptSegment> timeline, Map<String, Object> frame, long seq) {
        String type = String.valueOf(frame.get("type"));
        if ("delta".equals(type)) {
            if (!(frame.get("text") instanceof String text) || text.isEmpty()) {
                return;
            }
            String kind = "reasoning".equals(frame.get("kind")) ? "reasoning" : "text";
            TranscriptSegment last = timeline.isEmpty() ? null : timeline.getLast();
            if (last != null && kind.equals(last.kind)) {
                last.text.append(text);
            } else {
                timeline.add(new TranscriptSegment(kind, text, null, seq, null));
            }
            return;
        }
        if ("suspended".equals(type) && frame.get("toolCalls") instanceof List<?> calls) {
            for (Object raw : calls) {
                if (raw instanceof Map<?, ?> call) {
                    addTranscriptToolSegment(timeline, call.get("toolCallId"), seq);
                }
            }
            return;
        }
        if (switch (type) {
            case "tool_start", "tool_result", "confirm_request", "confirm_decision",
                 "frontend_tool_request", "frontend_tool_result" -> true;
            default -> false;
        }) {
            addTranscriptToolSegment(timeline, frame.get("toolCallId"), seq);
        }
    }

    private static void addTranscriptToolSegment(List<TranscriptSegment> timeline, Object rawToolCallId, long seq) {
        if (!(rawToolCallId instanceof String toolCallId) || toolCallId.isBlank()
                || transcriptToolSegment(timeline, toolCallId) >= 0) {
            return;
        }
        timeline.add(new TranscriptSegment("tool", null, toolCallId, seq, null));
    }

    private static final class TranscriptSegment {

        private final String kind;

        private final StringBuilder text;

        private final String toolCallId;

        private final long seq;

        private String entryId;

        private TranscriptSegment(String kind, String text, String toolCallId, long seq, String entryId) {
            this.kind = kind;
            this.text = text == null ? null : new StringBuilder(text);
            this.toolCallId = toolCallId;
            this.seq = seq;
            this.entryId = entryId;
        }

    }

    /**
     * 帧骨架：{@code type} + {@code atMs}（T4-3 后半）。
     *
     * <p>
     * {@code atMs} 在<b>帧构造时刻</b>取值 —— 帧一出生就带，故实时投递（{@link #emit} 里的
     * 序列化）与归档（{@link FrameStore#append}）拿到的是同一个值；与 {@code expiresAt}
     * 同一取钟口径（见 {@link #expiresAtEpochMs}），二者之差恒等于 {@code timeoutSeconds}。
     */
    private Map<String, Object> frame(String type) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("atMs", System.currentTimeMillis());
        return frame;
    }

    /**
     * 业务活动锚点（{@code ai:activity}）按 run 约 1s 节流（P3-04）。
     *
     * <p>节流判定留在出帧路径；实际写由 {@link #scheduleActivityTouch} 合流后投递到既有
     * delivery 执行器，不在 scheduler 或 {@code emitLock} 内做 Redis I/O。
     * 锚点只存在有界调度延迟；僵尸接管读的是分钟级窗口（缺省 180s），语义不变。
     */
    private boolean isBusinessActivityDue(long atMs) {
        long stamp = atMs > 0L ? atMs : System.currentTimeMillis();
        long last = this.lastBusinessActivityTouchMs.get();
        if (stamp - last < ACTIVITY_TOUCH_INTERVAL_MS) {
            return false;
        }
        return this.lastBusinessActivityTouchMs.compareAndSet(last, stamp);
    }

    /**
     * 心跳活动戳（{@code ai:beat}）的同口径节流：只证明"进程还活着"，不推进业务锚点。
     */
    private boolean isHeartbeatActivityDue() {
        long now = System.currentTimeMillis();
        long last = this.lastHeartbeatActivityTouchMs.get();
        if (now - last < ACTIVITY_TOUCH_INTERVAL_MS) {
            return false;
        }
        return this.lastHeartbeatActivityTouchMs.compareAndSet(last, now);
    }

    private void scheduleActivityTouch(boolean heartbeat, boolean business, long businessStamp) {
        if (!heartbeat && !business) {
            return;
        }
        synchronized (this.activityTouchLock) {
            this.pendingHeartbeatActivity |= heartbeat;
            if (business) {
                this.pendingBusinessActivityStamp = Math.max(this.pendingBusinessActivityStamp, businessStamp);
            }
            if (this.activityTouchInFlight) {
                return;
            }
            this.activityTouchInFlight = true;
        }
        try {
            this.deliveryExecutor.execute(AiTrace.wrap(this::drainActivityTouch));
        } catch (RejectedExecutionException ex) {
            synchronized (this.activityTouchLock) {
                this.activityTouchInFlight = false;
                this.pendingHeartbeatActivity = false;
                this.pendingBusinessActivityStamp = 0L;
            }
            if (LogSampling.canAcquire("sse-activity-touch-schedule")) {
                log.warn("activity touch not scheduled runId={}", this.runId, ex);
            }
        }
    }

    private void drainActivityTouch() {
        while (true) {
            final boolean heartbeat;
            final long businessStamp;
            synchronized (this.activityTouchLock) {
                heartbeat = this.pendingHeartbeatActivity;
                businessStamp = this.pendingBusinessActivityStamp;
                this.pendingHeartbeatActivity = false;
                this.pendingBusinessActivityStamp = 0L;
            }
            TenantUser identity = deliveryIdentity();
            if (identity != null) {
                try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
                    if (heartbeat) {
                        this.store.touchActivity(this.runId);
                    }
                    if (businessStamp > 0L) {
                        this.store.touchBusinessActivity(this.runId, businessStamp);
                    }
                } catch (RuntimeException ex) {
                    if (LogSampling.canAcquire("sse-activity-touch")) {
                        log.warn("activity touch failed runId={}", this.runId, ex);
                    }
                }
            }
            synchronized (this.activityTouchLock) {
                if (!this.pendingHeartbeatActivity && this.pendingBusinessActivityStamp <= 0L) {
                    this.activityTouchInFlight = false;
                    return;
                }
            }
        }
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
     * 作用域（定时线程、池化线程、未来新增入口都没有请求身份），漏开就会把帧写进错误键形。
     * 这里统一兜底：与投递路径同源、同一份已解析身份，嵌套时由作用域关闭还原调用方原身份
     * （{@link RunIdentityScope}）。
     *
     * <p><b>身份不可确认 ⇒ 这一拍明确失败</b>：本帧不落档、不投递（跳过），不写任何身份键；
     * 终帧改走"终帧未归档"恢复链。
     *
     * <p><b>P1-09：这里没有"下一拍重试"。</b>帧是即时的，跳过后不会再被同一拍重发；
     * 普通帧因此确实会缺席（只能靠前端重挂）—— 但<b>终帧（done/error）不得静默丢</b>：
     * 它没归档就等于这一轮在连接视角永远没结束（前端要等看门狗超时）。终帧命中本分支时改走
     * 既有的"终帧未归档"恢复链（{@link #closeSubscribersAfterUnarchivedTerminal}）：给在线订阅者
     * 补一帧 typed error 并收流，让客户端拿到明确失败、按既有路径重挂/停止等待。
     */
    private void emit(Map<String, Object> frame) {
        this.emitting.incrementAndGet();
        try {
            TenantUser identity = deliveryIdentity();
            if (identity == null) {
                String frameType = String.valueOf(frame.get("type"));
                boolean terminal = "done".equals(frameType) || "error".equals(frameType);
                if (this.identityFailureWarned.compareAndSet(false, true)) {
                    log.warn("SSE delivery identity unconfirmed, frame not "
                            + "archived/delivered runId={} type={} terminal={}", this.runId, frameType, terminal);
                } else {
                    if (log.isDebugEnabled())
                        log.debug("SSE delivery identity unconfirmed, frame skipped runId={} type={} terminal={}", this.runId, frameType,
                                terminal);
                }
                if (terminal) {
                    Map<String, Object> extra = new LinkedHashMap<>();
                    extra.put("unarchivedTerminal", true);
                    extra.put("identityUnconfirmed", true);
                    closeSubscribersAfterUnarchivedTerminal(extra, "terminal frame not archived: delivery identity unconfirmed");
                }
                return;
            }
            try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
                emitInternal(frame);
            }
        } finally {
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
     * <b>锁内顺序不可调换</b>：原子落档（定号 + 归档；截断在号占位下完成）→ 入队。
     * 落档与入队必须拿到同一份字节（否则事件日志里躺着未截断帧、或实时与回放分叉）。
     * <b>Task 2</b>：截断做成负载工厂交给
     * {@link FrameStore#appendAndAllocateSeq(String, java.util.Map, FrameStore.PayloadFactory)}，
     * 实现在<b>最宽号占位</b>下跑工厂、归档前把真号拼进去 —— 实时字节 == 归档字节，
     * 且号比占位更窄，贴上限的帧也不会被号宽顶出去。
     * <b>只有归档成功才交付</b>：返回"未归档"时本帧不扇出、不推进游标、不发唤醒。
     * <b>资源修复批</b>：唤醒发布（PUBLISH）已挪到<b>锁外</b>，但"发布必须在落档之后"不变 ——
     * 锁内只累计上界（{@link #requestWakePublish}），最外层锁出口才真正发。
     */
    private boolean canEmitBusinessFrame(String frameType) {
        LeaseService.LeaseAuthority authority;
        try {
            authority = this.store.runLeaseAuthority(this.runId);
        } catch (RuntimeException ex) {
            authority = LeaseService.LeaseAuthority.UNAVAILABLE;
            if (this.authorityReadFailureWarned.compareAndSet(false, true)) {
                log.warn("resume authority read failed runId={} (UNAVAILABLE, read-only recovery; business frames not archived)", this.runId, ex);
            } else if (LogSampling.canAcquire("sse-authority-read-failure")) {
                log.warn("resume authority read failed again runId={} (already warned)", this.runId, ex);
            }
        }
        if (authority == null || !authority.isReadOnly() && this.store.isWritePermitted(this.runId, "event frame")) {
            return true;
        }
        if (this.readOnlyDropWarned.compareAndSet(false, true)) {
            log.warn("run authority {}: dropping business frame (incl. terminal) "
                    + "runId={} type={} (run taken over by new owner, read-only here)", authority, this.runId, frameType);
        } else {
            if (log.isDebugEnabled())
                log.debug("run authority {}: dropping business frame (incl. terminal) runId={} type={}", authority,
                        this.runId, frameType);
        }
        return false;
    }

    private record ArchiveOutcome(byte[] payloadBytes, long liveSeq, boolean archivedOk,
                                  boolean terminalUnarchived, boolean touchHeartbeatActivity,
                                  boolean touchBusinessActivity,
                                  long businessActivityStamp) {
    }

    private ArchiveOutcome archiveHeartbeat(Map<String, Object> frame) {
        frame.put("heartbeatSeq", this.heartbeatSeq.incrementAndGet());
        boolean touch = isHeartbeatActivityDue();
        try {
            return new ArchiveOutcome(this.objectMapper.writeValueAsBytes(frame), 0L, true, false, touch, false, 0L);
        } catch (Exception ex) {
            log.error("SSE frame serialization failed runId={} type={}", this.runId, frame.get("type"), ex);
            return new ArchiveOutcome(null, 0L, false, false, false, false, 0L);
        }
    }

    private ArchiveOutcome archiveBusinessFrame(Map<String, Object> frame, String frameType) {
        FrameStore.PayloadFactory payloadFactory = f ->
                FrameSizeLimiter.apply(f, this.objectMapper, this.maxFrameBytes);
        FrameStore.AppendResult archived = this.frames.appendAndAllocateSeq(this.runId, frame,
                payloadFactory, this.store.heldRunEpoch(this.runId));
        if (archived.seq() <= 0L || archived.payload() == null) {
            if (this.archiveFailureWarned.compareAndSet(false, true)) {
                log.warn("event frame not archived, not delivered runId={} type={} "
                        + "(multi-instance ordering requires archive; further drops not re-warned)", this.runId, frame.get("type"));
            } else {
                if (log.isDebugEnabled())
                    log.debug("event frame not archived, not delivered runId={} type={}", this.runId, frame.get("type"));
            }
            boolean terminal = "done".equals(frameType) || "error".equals(frameType);
            return new ArchiveOutcome(null, 0L, false, terminal, false, false, 0L);
        }
        long frameSeq = archived.seq();
        frame.put("seq", frameSeq);
        recordTranscriptFrame(frame, frameSeq);
        long stamp = epochMs(frame.get("atMs"));
        boolean touch = isBusinessActivityDue(stamp);
        this.lastFanoutSeq.accumulateAndGet(frameSeq, Math::max);
        if ("done".equals(frameType) || "error".equals(frameType)) {
            this.terminalFrameSeen.set(true);
        }
        return new ArchiveOutcome(archived.payload(), frameSeq, true, false, false, touch, stamp);
    }

    private ArchiveOutcome archiveFrame(Map<String, Object> frame, String frameType) {
        if (RunStore.HEARTBEAT_TYPE.equals(frameType)) {
            return archiveHeartbeat(frame);
        }
        return archiveBusinessFrame(frame, frameType);
    }

    private record EmissionOutcome(long wakeSeq, boolean archivedOk, boolean terminalUnarchived,
                                   boolean touchHeartbeatActivity, boolean touchBusinessActivity,
                                   long businessActivityStamp) {
    }

    private EmissionOutcome archiveAndFanOut(Map<String, Object> frame, String frameType) {
        ArchiveOutcome archived = archiveFrame(frame, frameType);
        long wakeSeq;
        if (!archived.archivedOk()) {
            wakeSeq = 0L;
        } else if (this.subscribers.isEmpty() || this.terminating.get()) {
            wakeSeq = archived.liveSeq();
        } else {
            wakeSeq = fanOutToSubscribers(archived.payloadBytes(), archived.liveSeq());
        }
        return new EmissionOutcome(wakeSeq, archived.archivedOk(), archived.terminalUnarchived(),
                archived.touchHeartbeatActivity(), archived.touchBusinessActivity(), archived.businessActivityStamp());
    }

    private void emitInternal(Map<String, Object> frame) {
        frame.putIfAbsent("runId", this.runId);
        String frameType = String.valueOf(frame.get("type"));
        if (!RunStore.HEARTBEAT_TYPE.equals(frameType) && !canEmitBusinessFrame(frameType)) {
            return;
        }
        EmissionOutcome outcome;
        try {
            synchronized (this.emitLock) {
                if (RunStore.HEARTBEAT_TYPE.equals(frameType)
                        && (this.terminalFrameSeen.get() || this.terminating.get())) {
                    return;
                }
                outcome = archiveAndFanOut(frame, frameType);
            }
        } finally {
            flushWakeSyncIfUnlocked();
        }
        if (RunStore.HEARTBEAT_TYPE.equals(frameType) && !outcome.archivedOk()) {
            return;
        }
        scheduleActivityTouch(outcome.touchHeartbeatActivity(), outcome.touchBusinessActivity(),
                outcome.businessActivityStamp());
        if (outcome.terminalUnarchived()) {
            closeSubscribersAfterUnarchivedTerminal();
        }
        boolean terminal = "done".equals(frameType) || "error".equals(frameType);
        if (terminal) {
            stopAttachKeepalive();
        }
        requestWakePublish(outcome.wakeSeq(), terminal);
    }

    /**
     * 终帧未归档时的收口（I-5）：<b>不投未归档终帧</b>，改走<b>既有恢复失败链</b>。
     *
     * <p>两条路都不能接受：① 什么都不做 ⇒ 连接挂着一个永远不会再发的终帧，用户看到转圈到超时；
     * ② 投递一个未归档的终帧 ⇒ 重挂回放里没有它（归档视角这一轮永远没结束），
     * {@code terminalFrameArchived} 也会一直说"没有终帧"，取消收尾会重复发终帧。
     *
     * <p>因此复用 N-2 / AR-05 的中性结局：给每个仍挂着的订阅者补一帧 typed error
     * （{@code AI_REDIS_UNAVAILABLE} + {@code retryable} + {@code degraded}），排空后收流 ——
     * 客户端拿到<b>明确失败</b>并断开，重挂/看门狗按既有路径收敛。
     */
    private void closeSubscribersAfterUnarchivedTerminal() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("degraded", true);
        extra.put("retryable", true);
        extra.put("unarchivedTerminal", true);
        closeSubscribersAfterUnarchivedTerminal(extra, "terminal frame not archived");
    }

    /**
     * 同 {@link #closeSubscribersAfterUnarchivedTerminal()}，显式给出 additive 标记与日志原因
     * （P1-09：身份不可确认导致的终帧未归档走同一条恢复链，但要在帧/日志里留下可区分的原因）。
     */
    private void closeSubscribersAfterUnarchivedTerminal(Map<String, Object> extra, String reason) {
        Map<String, Object> markers = new LinkedHashMap<>();
        markers.put("degraded", true);
        markers.put("retryable", true);
        if (extra != null) {
            markers.putAll(extra);
        }
        int delivered = unresolvedEnding(ErrorCodes.AI_REDIS_UNAVAILABLE, "AI 服务暂不可用，请稍后重试。", markers,
                true);
        // <b>收口</b>（返修第 5 条）：合成终帧已表达"这一轮在这个连接上的终点"。
        // 置 terminating ⇒ 之后的心跳/业务帧不再扇出（否则前端会在已终结的流上收到迟到内容，
        // 把终态又拉回进行中）；新订阅者进来也只会回放 + 立即收尾。
        this.terminating.set(true);
        // 收口后补一次终态回收判定：常规回收是"最后一个订阅者离开时由 closeSubscription 触发"，
        // 而那条路径刚刚已经跑过（当时 terminating 还没置）—— 不在这里补，这条"已终态 + 无订阅者"
        // 的写出器会一直留在注册表里（持有订阅表/队列/唤醒目标）。recycled 的 CAS 保证只跑一次。
        fireRecycleIfTerminal();
        log.warn("{}: closed via recovery failure chain runId={} subscribers={} "
                        + "(client got typed error instead of waiting for a terminal frame)",
                reason, this.runId, delivered);
    }

    /**
     * M6：<b>离线重挂</b>的终帧宽限（复用既有 terminalUnarchived 机制，不新造设施）。
     *
     * <p>触发面：重挂时快照已终态（{@code terminalExpected}）且<b>本实例不是驱动方</b>，回放已读到底
     * 却没有终帧。正常写方的 {@code done} 与快照同线程、毫秒级落档；给一个有界宽限（
     * {@link #UNARCHIVED_TERMINAL_GRACE_SECONDS}）后仍无终帧，就按"终帧未归档"既有链路补 typed error
     * 并收流，避免连接永远 loading。宽限任务复用 attach 保活调度器（生产装配才有；轻装配保持旧行为）。
     *
     * <p><b>到点复查</b>：必须再看 {@code drivingHere} 与驱动代次 —— 宽限期间若本实例开始（或重新开始）
     * 驱动，说明 {@code done} 很快会正常落档，<b>放弃</b>合成终帧；只收当前订阅，不把 emitter 级
     * {@code terminating} 置死（之后的重挂仍能正常回放/挂接）。
     */
    private void scheduleUnarchivedTerminalGraceIfNeeded(Subscription sub) {
        if (!sub.terminalExpected || this.drivingHere.get()) {
            return;
        }
        if (this.terminalFrameSeen.get() || this.terminating.get() || sub.terminating) {
            return;
        }
        if (this.attachKeepaliveScheduler == null) {
            return;
        }
        if (!sub.unarchivedTerminalGrace.compareAndSet(false, true)) {
            return;
        }
        scheduleUnarchivedTerminalGraceTask(sub, this.driveGeneration.get());
    }

    /**
     * 宽限任务本体（可被"忙"重排，最多 {@link #UNARCHIVED_TERMINAL_GRACE_RETRIES} 次）。
     *
     * <p>到点先复查驱动代次/驱动方/关闭/终帧；仍有效但队列仍在排空或在飞 emit 未归零时，
     * <b>有界重排</b>而不是永久放弃 —— 否则慢连接恰好忙过这一拍就再也等不到收流。
     */
    private void scheduleUnarchivedTerminalGraceTask(Subscription sub, long scheduledGeneration) {
        ScheduledExecutorService scheduler = this.attachKeepaliveScheduler;
        if (scheduler == null) {
            sub.unarchivedTerminalGrace.set(false);
            return;
        }
        try {
            scheduler.schedule(AiTrace.wrap(() -> submitUnarchivedTerminalGrace(sub, scheduledGeneration)),
                    UNARCHIVED_TERMINAL_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ex) {
            sub.unarchivedTerminalGrace.set(false);
            if (LogSampling.canAcquire("sse-offline-terminal-grace"))
                log.warn("offline reattach terminal grace not scheduled runId={} (scheduler shut down)", this.runId, ex);
        }
    }

    private void submitUnarchivedTerminalGrace(Subscription sub, long scheduledGeneration) {
        try {
            this.deliveryExecutor.execute(AiTrace.wrap(() -> runUnarchivedTerminalGrace(sub, scheduledGeneration)));
        } catch (RejectedExecutionException ex) {
            sub.unarchivedTerminalGrace.set(false);
            if (LogSampling.canAcquire("sse-offline-terminal-grace"))
                log.warn("offline reattach terminal grace not dispatched runId={}", this.runId, ex);
        }
    }

    private void runUnarchivedTerminalGrace(Subscription sub, long scheduledGeneration) {
        boolean synthesize;
        boolean busy;
        boolean stillValid;
        synchronized (this.emitLock) {
            stillValid = !this.drivingHere.get()
                    && this.driveGeneration.get() == scheduledGeneration
                    && !sub.closed && !sub.terminating && !this.terminating.get()
                    && !this.terminalFrameSeen.get();
            synthesize = stillValid && !sub.replayActive && sub.queue.isEmpty()
                    && this.emitting.get() == 0;
            busy = stillValid && !synthesize;
        }
        if (!stillValid) {
            return;
        }
        if (!isSnapshotStillTerminal()) {
            if (log.isDebugEnabled()) log.debug("offline reattach terminal grace abandoned runId={} (snapshot "
                    + "non-terminal/unreadable; may be redriven by another instance)", this.runId);
            return;
        }
        if (synthesize) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("unarchivedTerminal", true);
            extra.put("terminalUnconfirmed", true);
            extra.put("replayReattach", true);
            closeCurrentSubscriptionsWithUnarchivedTerminal(extra, "terminal frame not seen within offline reattach grace");
            return;
        }
        if (busy && sub.unarchivedTerminalGraceAttempts.incrementAndGet()
                <= UNARCHIVED_TERMINAL_GRACE_RETRIES) {
            scheduleUnarchivedTerminalGraceTask(sub, scheduledGeneration);
        }
    }

    /**
     * 宽限到点的<b>快照复核</b>：返回 {@code true} 仅当本轮快照仍存在且仍是终态。
     *
     * <p>为什么要读快照：本实例的 {@code driveGeneration} 只能看到"本实例重新驱动"；
     * 跨实例重驱动会把快照改回 RUNNING —— 此时合成终帧会把一条活着的连接判死。
     * 读失败/快照缺失一律按"不可确认"处理（返回 {@code false} = 放弃合成）。
     */
    private boolean isSnapshotStillTerminal() {
        try {
            TenantUser identity = deliveryIdentity();
            if (identity == null) {
                return false;
            }
            try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
                RunSnapshot snapshot = this.store.get(this.runId, identity.tenantId(), identity.userId());
                return snapshot != null && RunSnapshot.isTerminal(snapshot.getStatus());
            }
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("sse-chat-runtime-catch"))
                log.warn("offline reattach terminal grace snapshot read failed runId={} (synthesis abandoned)", this.runId, ex);
            return false;
        }
    }

    /**
     * <b>只收当前订阅</b>的合成终结局（M6）：与 {@link #closeSubscribersAfterUnarchivedTerminal}
     * 的区别是<b>不置 emitter 级 {@code terminating}/{@code terminalFrameSeen}</b> ——
     * 这是"离线重挂没等到终帧"的连接级收尾，轮次本身仍可由后续重挂/驱动继续。
     */
    private void closeCurrentSubscriptionsWithUnarchivedTerminal(Map<String, Object> extra, String reason) {
        List<Subscription> targets = new ArrayList<>();
        final String payload;
        try {
            synchronized (this.emitLock) {
                // 与 beginDrive 同锁：若此刻已开始（或重新开始）驱动，放弃合成终帧，交给真实 done。
                if (this.drivingHere.get() || this.terminating.get() || this.terminalFrameSeen.get()
                        || this.subscribers.isEmpty()) {
                    return;
                }
                Map<String, Object> frame = frame("error");
                frame.put("runId", this.runId);
                frame.put("code", ErrorCodes.AI_REDIS_UNAVAILABLE);
                frame.put("message", "AI 服务暂不可用，请稍后重试。");
                frame.put("degraded", true);
                frame.put("retryable", true);
                if (extra != null) {
                    frame.putAll(extra);
                }
                try {
                    payload = this.objectMapper.writeValueAsString(frame);
                } catch (Exception ex) {
                    log.error("offline reattach outcome frame serialization failed runId={}", this.runId, ex);
                    return;
                }
                for (Subscription sub : this.subscribers) {
                    if (sub.closed || sub.evicted.get()) {
                        continue;
                    }
                    abortReplayLocked(sub);
                    sub.terminating = true;
                    sub.queue.add(new QueuedFrame(payload, false, true));
                    sub.identityDegradedQueued.incrementAndGet();
                    targets.add(sub);
                }
            }
        } finally {
            flushWakeSyncIfUnlocked();
        }
        for (Subscription sub : targets) {
            completeWhenDrained(sub);
        }
        log.warn("{}: delivering to current subscribers only (emitter state "
                        + "unchanged, reattach can continue) runId={} subscribers={}", reason, this.runId,
                targets.size());
    }

    /**
     * 把本帧入队给全部订阅者（调用方持 {@code emitLock}），返回该帧的业务号（心跳 = 0）。
     *
     * <p>从 {@link #emitInternal} 拆出来只为让"锁内"与"锁外发布"的边界在源码上可见：
     * 入队必须与取号/落档同锁（FIFO 与 seq 同序），PUBLISH 不必。
     */
    private long fanOutToSubscribers(byte[] payloadBytes, long liveSeq) {
        final String payload = org.apache.commons.codec.binary.StringUtils.newStringUtf8(payloadBytes);
        // <b>交付前补齐更低号</b>（Task 2 返修第 1 条）：原子分配保证"归档序 == 分配序"，
        // 但<b>不</b>保证唤醒先到 —— 实例 A 归档 1、实例 B 归档 2；若 B 直接把 2 投给订阅者，
        // 游标推到 2 之后 A 的唤醒（上界 1）就被 onWake 的"上界 ≤ 最小游标 ⇒ 无差量"短路，
        // 1 号<b>永久缺帧</b>。
        //
        // 因此扇出前先从归档补出"各订阅者游标与本帧号之间"的帧（升序、按游标去重），再投本帧。
        // 读一次覆盖全部订阅者（取最小游标作下界），逐订阅者只投自己没见过的号。
        List<FrameStore.RawFrame> catchUp = catchUpFramesLocked(liveSeq);
        for (Subscription sub : this.subscribers) {
            if (sub.closed || sub.terminating) {
                // S3-1：生命周期回调已清理该订阅者（COW 快照与摘除的窄窗）—— 不再入队、不再抢 drain。
                // 已置收尾意图（B1 收流 / 降级回放 / 合成终帧）的订阅者同理：终帧之后不得再入队新帧。
                continue;
            }
            if (liveSeq <= 0L) {
                // 心跳：没有业务号，直接投（补齐只对业务号有意义）。
                // 回放期间也照投：心跳不带 seq，插在回放帧之间不破坏 seq 顺序（保活不能停）。
                if (isEnqueueLive(sub, payload)) {
                    kickDrain(sub);
                } else {
                    evict(sub, "live queue overflow (capacity " + this.queueCapacity + ")");
                }
                continue;
            }
            if (sub.replayActive) {
                // P1-06：该订阅者还在分页回放 —— 本帧已在归档里，回放通道会按序补到；
                // 此刻直接入队会让更大的号排到尚未入队的回放帧之前（seq 乱序）。
                // 只保证回放继续推进（kickDrain 幂等，已持权时 no-op）。
                kickDrain(sub);
                continue;
            }
            if (liveSeq <= sub.wakeFloor) {
                // 自唤醒（同步总线 / 进程内分发 / 本实例锁外发布）已经把这一帧投给本订阅者：
                // 游标只在成功入队之后推进（onWake 与本分支同一口径），故"游标已过该号"等价于
                // "这一帧已交付"；此处再投就会让订阅者收到同一个 seq 两次。
                continue;
            }
            if (!isDeliverCatchUpLocked(sub, catchUp, liveSeq)) {
                // 补不完整（批上限 / 队列容量）⇒ 本帧先不投：游标停在已交付处，
                // 下一次唤醒或下一次扇出从这里继续 —— 绝不能越过中间号。
                continue;
            }
            if (liveSeq <= sub.wakeFloor) {
                // 补齐过程本身已经把本帧投出去了（归档里已有它）。
                continue;
            }
            if (!isEnqueueLive(sub, payload)) {
                // S2-1：溢出摘除 = 主动断连 —— 显式 complete 触发前端 onerror/onclose，
                // 前端带 lastSeq 重挂走差量补发（seq 无洞），胜过静默丢帧。
                evict(sub, "live queue overflow (capacity " + this.queueCapacity + ")");
                continue;
            }
            // 本地已投递 ⇒ 本订阅者的游标推进（唤醒补帧的读下界取各订阅者最小值）。
            sub.wakeFloor = Math.max(sub.wakeFloor, liveSeq);
            kickDrain(sub);
        }
        return liveSeq;
    }

    /**
     * 读"各订阅者游标与本帧号之间"的归档帧（调用方持 {@code emitLock}）。
     *
     * <p>只在<b>确实有订阅者落后</b>时读（{@code 游标 + 1 < 本帧号}）：正常单实例出帧时
     * 游标总是"紧跟上一次扇出"，这里不会产生额外往返。
     *
     * <p><b>上限 = min(实时队列容量, 唤醒批上限)</b>（返修第 3 条）：补齐是"补历史"，不是"搬整个归档"——
     * 一次读 6000 条再逐帧序列化，既吃 CPU/延迟，也会把"落后很远"这件事掩盖成一次巨型突发。
     * 一批补不完就交给下一次唤醒/下一次扇出继续（游标只推进到已交付处，不丢帧）。
     *
     * <p><b>拿归档原文</b>：优先 {@link FrameStore#readRawFrom}（生产实现返回落档时写进 Stream 的
     * 那段字节）；不支持时回落到 {@code readFrom} + 本地序列化 —— 后者只是<b>语义等价</b>，
     * 不承诺与归档逐字节一致（接口注释已写明边界）。
     */
    private List<FrameStore.RawFrame> catchUpFramesLocked(long liveSeq) {
        if (liveSeq <= 1L || this.subscribers.isEmpty()) {
            return List.of();
        }
        long from = Long.MAX_VALUE;
        boolean lagging = false;
        for (Subscription sub : this.subscribers) {
            if (sub.closed || sub.replayActive) {
                // P1-06：回放中的订阅者由分页通道按归档顺序补齐，不参与实时补齐的读取下界
                // （否则每次实时出帧都会按"落后很多"的游标多读一批，且在回放通道之外重复入队）。
                continue;
            }
            if (sub.wakeFloor < liveSeq - 1L) {
                lagging = true;
                from = Math.min(from, sub.wakeFloor);
            }
        }
        if (!lagging || from == Long.MAX_VALUE) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(this.queueCapacity, FrameStore.WAKE_BATCH_LIMIT));
        List<FrameStore.RawFrame> raw = this.frames.readRawFrom(this.runId, from, limit);
        if (!raw.isEmpty()) {
            return raw;
        }
        List<Map<String, Object>> fallback = this.frames.readFrom(this.runId, from, limit);
        if (fallback.isEmpty()) {
            return List.of();
        }
        List<FrameStore.RawFrame> converted = new ArrayList<>(fallback.size());
        for (Map<String, Object> frame : fallback) {
            Object seqValue = frame.get("seq");
            if (!(seqValue instanceof Number number)) {
                continue;
            }
            try {
                converted.add(new FrameStore.RawFrame(number.longValue(), this.objectMapper.writeValueAsString(frame)));
            } catch (Exception ex) {
                log.error("padding frame serialization failed runId={} seq={}", this.runId, number.longValue(), ex);
            }
        }
        return converted;
    }

    /**
     * 把补齐帧按序投给一个订阅者（调用方持 {@code emitLock}），返回"现在是否可以安全投本帧"。
     *
     * <p>安全条件：补到 {@code 本帧号 - 1} 为止（游标到达 {@code liveSeq - 1}，或归档里本帧已存在
     * ⇒ 游标到达本帧号）。批上限/容量不足时返回 {@code false}，本帧推迟到下一次唤醒 ——
     * 游标只推进到已交付处，中间号不会被跳过。
     *
     * <p><b>统一容量闸</b>（返修第 3 条）：补齐帧同样走 {@link #enqueueLive} —— 队列满就
     * <b>摘除订阅者</b>（前端带 lastSeq 重挂走差量补发），既不静默灌爆无界队列，也不把
     * "落后"伪装成一次不受控的突发。整段在 {@code emitLock} 内完成，<b>不得</b>挪到锁外
     * （那会让补齐与本帧和其它线程的扇出交错，重新打开"高号先交付"的乱序窗口）。
     */
    private boolean isDeliverCatchUpLocked(Subscription sub, List<FrameStore.RawFrame> catchUp, long liveSeq) {
        if (catchUp.isEmpty()) {
            // 读不到补齐帧：若订阅者确实落后，无法证明"没有中间号" ⇒ 这一拍不投本帧。
            return sub.wakeFloor >= liveSeq - 1L;
        }
        boolean covered = false;
        boolean enqueued = false;
        for (FrameStore.RawFrame frame : catchUp) {
            if (sub.closed) {
                // 生命周期回调已摘除该订阅者（COW 快照与摘除的窄窗）：不再往死队列里塞帧。
                return false;
            }
            long seq = frame.seq();
            if (seq <= sub.wakeFloor) {
                continue;
            }
            if (seq > liveSeq) {
                break;
            }
            if (!isEnqueueLive(sub, frame.json())) {
                // S2-1 同口径：溢出 = 主动断连（重挂补帧），绝不绕开容量闸硬塞。
                evict(sub, "padding frame exceeds live queue capacity (capacity " + this.queueCapacity + ")");
                return false;
            }
            sub.wakeFloor = seq;
            enqueued = true;
            if (seq >= liveSeq - 1L) {
                covered = true;
            }
        }
        if (sub.wakeFloor >= liveSeq - 1L) {
            covered = true;
        }
        if (enqueued) {
            kickDrain(sub);
        }
        return covered;
    }

    /**
     * 请求发布一次唤醒（锁内只登记，锁外真正发）。
     *
     * @param seq 本帧的业务号（{@code <= 0} = 心跳，不发布）
     */
    private void requestWakePublish(long seq, boolean terminal) {
        if (seq <= 0L) {
            return;
        }
        synchronized (this.wakePublishLock) {
            this.pendingWakeSeq.accumulateAndGet(seq, Math::max);
            this.pendingWakeTerminal |= terminal;
        }
        if (Thread.holdsLock(this.emitLock)) {
            return;
        }
        flushWakePublish();
    }

    /**
     * 锁外投递到总线合流器；终帧标记随同一批上界一起取走。
     */
    private void flushWakePublish() {
        if (Thread.holdsLock(this.emitLock)) {
            return;
        }
        final long seq;
        final boolean terminal;
        synchronized (this.wakePublishLock) {
            seq = this.pendingWakeSeq.getAndSet(0L);
            terminal = this.pendingWakeTerminal;
            this.pendingWakeTerminal = false;
        }
        if (seq > 0L) {
            publishWake(seq, terminal);
        }
    }

    private void publishWake(long seq, boolean terminal) {
        FrameWakeBus bus = this.wakeBus;
        if (bus == null) {
            return;
        }
        TenantUser identity = deliveryIdentity();
        if (identity == null) {
            warnOnce(this.identityFailureWarned, "frame wake publish skipped runId={} seq={} (identity unconfirmed; no bare-run channel)", this.runId, seq);
            return;
        }
        try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
            bus.publish(this.runId, seq, terminal);
        } catch (Exception ex) {
            if (LogSampling.canAcquire("sse-chat-exception-catch"))
                log.warn("frame wake publish failed runId={} seq={}", this.runId, seq, ex);
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
     * 唤醒载荷只带上界，中间帧由 {@link FrameStore#readFrom} 给齐；丢失的 wake 由下一次唤醒、
     * 非驱动方 30s 对账或客户端 {@code ?lastSeq} 重连补齐，包括最后一条业务帧/终帧。投递顺序仍是归档顺序，
     * 与本地实时帧共用同一把 {@link #emitLock} 串行入队（FIFO、seq 单调）。
     */
    void onWake(String runId, long upperBoundSeq) {
        if (!this.runId.equals(runId)) {
            return;
        }
        // 唤醒回调跑在 Redis 订阅线程上，没有请求身份：按本写出器的本轮身份建立作用域
        // （补帧读归档/入队与出帧同一身份口径）。
        // 身份不可确认 ⇒ 本拍不读归档：下一次唤醒或客户端重挂会补上。
        TenantUser identity = deliveryIdentity();
        if (identity == null) {
            warnOnce(this.identityFailureWarned, "frame wake padding skipped runId={} (identity unconfirmed, retry next tick)", this.runId);
            return;
        }
        try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
            onWakeScoped(upperBoundSeq);
        }
    }

    private void onWakeScoped(long upperBoundSeq) {
        onWakeScoped(upperBoundSeq, -1L);
    }

    /**
     * 唤醒补帧（{@code fromOverride >= 0} = 本地续读的<b>真实游标</b>，I2）。
     *
     * <p>每次回调只补 {@link #wakeBatchLimit()} 帧（{@code readRawFrom} 的 COUNT 分页）；
     * 补不完时<b>不再 PUBLISH</b>（那会让 N 个实例各广播一次 = N×B 放大），而是把"从已处理游标继续"
     * 交给本地投递执行器（{@link #scheduleWakeContinuation}）。正常投递语义不变：仍按归档序、
     * 仍以每订阅者 {@code wakeFloor} 去重。
     */
    private void processWakePage(long upperBoundSeq, long from, WakePage page) {
        List<WakeFrame> frames = page.frames();
        final long readFloor = from;
        long processedUpTo = from;
        for (WakeFrame next : frames) {
            long seq = next.seq();
            if (seq <= from) {
                // 没有 seq 的老帧只走回放（唤醒是新帧语义）；不大于上界的帧是重复帧，跳过
                continue;
            }
            if ("done".equals(next.type()) || "error".equals(next.type())) {
                // F1：终帧经唤醒补到本实例 ⇒ 本地保活此后不得再入队（心跳不许排在终帧之后）。
                // 非驱动方实例没有 complete() 调用点，终帧只能从这里得知。
                this.terminalFrameSeen.set(true);
            }
            for (Subscription sub : this.subscribers) {
                if (sub.closed || sub.terminating) {
                    // 已置收尾意图：终帧之后不得再由唤醒补入新帧。
                    continue;
                }
                if (sub.replayActive) {
                    // P1-06：该订阅者由分页回放按归档顺序补齐；这里只保证回放继续推进，
                    // 不把更大的号插到尚未入队的回放帧之前。
                    kickDrain(sub);
                    continue;
                }
                if (seq <= sub.wakeFloor) {
                    // 该订阅者已经见过这一帧（?lastSeq 重挂的客户端）：补帧不得变成重发。
                    continue;
                }
                if (!isEnqueueLive(sub, next.payload())) {
                    evict(sub, "wake padding exceeds live queue capacity (capacity " + this.queueCapacity + ")");
                    continue;
                }
                sub.wakeFloor = seq;
                kickDrain(sub);
            }
            this.lastFanoutSeq.accumulateAndGet(seq, Math::max);
            from = Math.max(from, seq);
            processedUpTo = from;
        }
        boolean maybeMore = upperBoundSeq <= 0L
                ? page.readCount() >= wakeBatchLimit()
                : upperBoundSeq > processedUpTo;
        if (maybeMore && processedUpTo > readFloor) {
            // I2：本地续读（不用 PUBLISH 制造 N×B 广播）。游标 = 已处理上界，下一拍从断点继续。
            scheduleWakeContinuation(upperBoundSeq, processedUpTo);
        }
    }

    private void onWakeScoped(long upperBoundSeq, long fromOverride) {
        try {
            synchronized (this.emitLock) {
                if (this.subscribers.isEmpty() || this.terminating.get()) {
                    return;
                }
                long from = fromOverride >= 0L ? fromOverride : minDeliveredSeq();
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
                processWakePage(upperBoundSeq, from, loadWakeFrames(from));
            }
        } finally {
            flushWakeSyncIfUnlocked();
        }
        if (this.terminalFrameSeen.get()) {
            // F1：终帧已经投出（回放/唤醒两条路径）⇒ 停本地保活。
            stopAttachKeepalive();
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
            if (sub.closed || sub.replayActive) {
                // P1-06：回放中的订阅者由分页通道负责，不把它的游标当作唤醒读取下界
                // （它可能停在"已读到但尚未入队"的位置，参与 min 只会放大每次唤醒的读取量）。
                continue;
            }
            if (min < 0L || sub.wakeFloor < min) {
                min = sub.wakeFloor;
            }
        }
        return min;
    }

    /**
     * 读一批唤醒补帧（真实 COUNT 分页）：优先 {@code readRawFrom}（原文直接交付，避免二次序列化）；
     * 兼容实现不支持原文口时回落到类型化读口。
     */
    private WakePage loadRawWakeFrames(List<FrameStore.RawFrame> raw) {
        List<WakeFrame> out = new ArrayList<>(raw.size());
        for (FrameStore.RawFrame item : raw) {
            Map<String, Object> parsed = parseRawFrame(item);
            if (parsed == null) {
                continue;
            }
            String type = String.valueOf(parsed.get("type"));
            if (RunStore.HEARTBEAT_TYPE.equals(type)) {
                continue;
            }
            out.add(new WakeFrame(item.seq(), type, item.json()));
        }
        // readCount 用<b>原始记录数</b>（过滤掉心跳/坏帧后仍要能判断"还有下一页"）。
        return new WakePage(out, raw.size());
    }

    private WakePage loadWakeFrames(long from) {
        int limit = wakeBatchLimit();
        List<FrameStore.RawFrame> raw;
        try {
            raw = this.frames.readRawFrom(this.runId, from, limit);
        } catch (RuntimeException ex) {
            warnOnce(this.wakeReadFailureWarned, "wake padding raw read failed runId={} from={} (not padded this tick; next wake/reattach covers)", this.runId, from, ex);
            raw = List.of();
        }
        if (CollectionUtils.isNotEmpty(raw)) {
            return loadRawWakeFrames(raw);
        }
        List<Map<String, Object>> typed;
        try {
            typed = this.frames.readFrom(this.runId, from, limit);
        } catch (RuntimeException ex) {
            warnOnce(this.wakeReadFailureWarned, "wake padding delta read failed runId={} from={} (not padded this tick)", this.runId, from, ex);
            return new WakePage(List.of(), 0);
        }
        List<WakeFrame> out = new ArrayList<>(typed.size());
        for (Map<String, Object> frame : typed) {
            Object seqValue = frame.get("seq");
            if (!(seqValue instanceof Number number)) {
                continue;
            }
            String type = String.valueOf(frame.get("type"));
            if (RunStore.HEARTBEAT_TYPE.equals(type)) {
                continue;
            }
            try {
                out.add(new WakeFrame(number.longValue(), type, this.objectMapper.writeValueAsString(frame)));
            } catch (Exception ex) {
                log.error("wake padding serialization failed runId={} seq={}", this.runId, number.longValue(), ex);
            }
        }
        return new WakePage(out, typed.size());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRawFrame(FrameStore.RawFrame raw) {
        try {
            return this.objectMapper.readValue(raw.json(), Map.class);
        } catch (Exception ex) {
            log.error("wake padding parse failed runId={} seq={}", this.runId, raw.seq(), ex);
            return null;
        }
    }

    /**
     * I2：唤醒批次补不完时的<b>本地续读</b>（不用 PUBLISH 制造 N×B 广播）。
     *
     * <p>用"已处理上界"作为真实游标，在投递执行器上再跑一拍；每拍仍有 {@link #wakeBatchLimit()} 上界。
     * 身份不可确认时直接放弃本拍（下一次唤醒/重挂会补，不读任何身份键）。
     */
    private void scheduleWakeContinuation(long upperBoundSeq, long cursor) {
        try {
            this.deliveryExecutor.execute(AiTrace.wrap(() -> {
                AiTrace.bind(this.runId, null);
                TenantUser identity = deliveryIdentity();
                if (identity == null) {
                    return;
                }
                try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
                    onWakeScoped(upperBoundSeq, cursor);
                }
            }));
        } catch (RejectedExecutionException ex) {
            // 池拒绝：本拍不续（帧仍在归档，下一次唤醒/重挂兜底），不丢已入队帧。
            if (LogSampling.canAcquire("sse-wake-continuation-schedule")) {
                log.warn("wake local continuation not scheduled runId={} cursor={}", this.runId, cursor, ex);
            }
        }
    }

    private record WakeFrame(long seq, String type, String payload) {
    }

    /**
     * 一批唤醒补帧 + <b>原始读取条数</b>（判"还有下一页"不能用过滤后的条数）。
     */
    private record WakePage(List<WakeFrame> frames, int readCount) {
    }

    /**
     * 单次唤醒回调最多补多少帧（P2-06：唤醒派发显式有界）。
     *
     * <p>取"一条实时队列的容量"为一拍：既覆盖正常突发，又不让一次 Redis 订阅回调在
     * {@code emitLock} 内搬整个归档窗口（旧值 6000）。补不完时由本实例<b>本地续读</b>
     * （真实游标），不再 PUBLISH 广播。
     */
    private int wakeBatchLimit() {
        return Math.max(1, Math.min(FrameStore.WAKE_BATCH_LIMIT, this.queueCapacity));
    }

    /**
     * 实时段入队（T3-1 不变量：队列容量 &lt; 归档窗口 3000）。
     *
     * <p>
     * 队列本体无界、容量闸由本方法显式把守：所有生产者都在 {@code emitLock} 内串行入队、
     * 消费者只摘除，判定因此是精确的。闸的判据是 {@code Subscription#liveQueued}
     * （队列中<b>实时帧</b>的条数，S1 修复批第 2 轮）而<b>不是</b>队列绝对长度 —— 回放批
     * 走 {@link #readReplayStart}/{@link #applyReplayStartLocked} 的豁免口径（R1 裁决）且不计入该计数，两类入队互不挤占：
     * 慢重挂者排空 3000 帧回放期间到来的实时帧，不会因队列里压着回放帧而被误判成实时溢出。
     *
     * <p>
     * <b>递增必须先于入队</b>：反序时并发 drain 可在 {@code add} 与 {@code incrementAndGet}
     * 之间弹出该帧并递减，随后递增落地 ⇒ 计数永久 +1，容量闸逐帧泄漏直至误摘健康订阅者。
     * 反方向（递增后才入队）不会产生幽灵：{@code LinkedBlockingQueue} 无界，{@code add} 不会失败。
     */
    private boolean isEnqueueLive(Subscription sub, String payload) {
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

    /**
     * 入队后 CAS 抢占 drain 权：抢不到说明在跑的 drain 会带走新帧（单订阅者 FIFO）。
     */
    private void kickDrain(Subscription sub) {
        if (sub.draining.compareAndSet(false, true)) {
            submitDrain(sub);
        }
    }

    /**
     * 本轮的投递身份：<b>只按记录解析</b>，构造线程身份仅用于拼读记录的键。
     *
     * <p>解析顺序：
     * <ol>
     * <li>已解析过的记录身份 ⇒ 直接用（结果缓存，后续拍不再读）；</li>
     * <li>首次解析：用显式请求身份走 {@link RunStore#get(String, long, long)} 读本轮记录；
     *     记录有可用身份 ⇒ 用记录里的 {@code tenantId/userId}；</li>
     * <li>记录<b>不存在</b>（首轮快照还没建）⇒ 用显式请求身份（即即将创建这条记录的身份）；</li>
     * <li>记录存在但归属是 0/0 ⇒ {@link #identityInvalid}，永久不可确认：不 adopt 请求身份、
     *     不读写任何身份键（重挂/新写出器重新解析）。</li>
     * </ol>
     *
     * <p><b>读取异常 ≠ 无身份</b>：读记录抛异常时不缓存，排有界退避后重试；这一拍用显式请求身份
     * （键形与本轮一致），没有请求身份则由调用方按 UNKNOWN 跳过本拍。
     *
     * <p><b>并发契约</b>：首次解析在 {@link #identityLock} 内完成，落定后才写回
     * {@link #deliveryIdentity}；落定前并发的调用方在锁上等待，避免"尚未落定"的 null 被当成身份结论。
     *
     * <p><b>C-63：读异常退避（有界）</b>：失败后按 1s→30s 指数退避排下一次读，窗口内不进锁、
     * 不读记录；成功解析即清零。退避只影响"什么时候再试"，不改变身份结论。
     */
    private TenantUser resolveDeliveryIdentityLocked() {
        TenantUser known = this.deliveryIdentity;
        if (known != null) {
            return known;
        }
        if (this.identityInvalid) {
            return null;
        }
        if (System.currentTimeMillis() < this.identityRetryAtMs.get()) {
            return hintIdentity();
        }
        TenantUser hint = hintIdentity();
        if (hint == null) {
            // 没有显式请求身份 ⇒ 拼不出记录键。生产装配下这不该发生：调用方必须按
            // 本轮记录/请求身份建立作用域；这里按"身份不可确认"处理，绝不猜身份。
            return null;
        }
        if (this.store == null) {
            warnOnce(this.identityFailureWarned, "SSE delivery identity unconfirmed runId={} (no run store; lightweight legacy is disabled)", this.runId);
            return null;
        }
        try {
            RunSnapshot snapshot = this.store.get(this.runId, hint.tenantId(), hint.userId());
            if (snapshot != null) {
                if (!RunIdentityScope.usable(snapshot.getTenantId(), snapshot.getUserId())) {
                    // 0/0 旧快照没有可继承的归属：本写出器不做任何身份键读写（不 adopt 提示身份）。
                    this.identityInvalid = true;
                    warnOnce(this.identityFailureWarned, "SSE delivery identity invalid runId={} (record has no usable tenant/user; "
                            + "frames are neither archived nor delivered; client reattach/failure recovery)", this.runId);
                    return null;
                }
                TenantUser resolved = new TenantUser(snapshot.getTenantId(), snapshot.getUserId());
                this.deliveryIdentity = resolved;
                clearIdentityBackoff();
                return resolved;
            }
            // 记录还没建：首轮的帧写在"即将创建这一轮"的显式请求身份下（不是覆盖记录）。
            this.deliveryIdentity = hint;
            clearIdentityBackoff();
            return hint;
        } catch (RuntimeException ex) {
            // 读异常 = 不可知：不缓存、排有界退避；这一拍用显式提示身份（键形与本轮一致），
            // 没有提示则由调用方按身份不可确认处理（不写任何身份键）。
            long backoffMs = recordIdentityReadFailure();
            warnOnce(this.identityFailureWarned, "SSE delivery identity read failed runId={} (no cache, retry in {}ms; "
                    + "using explicit request identity this tick)", this.runId, backoffMs, ex);
            return hint;
        }
    }

    private TenantUser deliveryIdentity() {
        if (this.identityInvalid) {
            return null;
        }
        TenantUser known = this.deliveryIdentity;
        if (known != null) {
            return known;
        }
        long now = System.currentTimeMillis();
        if (now < this.identityRetryAtMs.get()) {
            return hintIdentity();
        }
        synchronized (this.identityLock) {
            return resolveDeliveryIdentityLocked();
        }
    }

    /**
     * 构造线程的显式请求身份（可读记录键的那一份）；不可用返回 {@code null}。
     */
    private TenantUser hintIdentity() {
        TenantUser hint = this.requestIdentityHint;
        return hint != null && RunIdentityScope.usable(hint.tenantId(), hint.userId()) ? hint : null;
    }

    /**
     * 记一次记录读失败并排下次重试时刻（指数封顶）；返回本次退避毫秒数（调用方用于日志）。
     */
    private long recordIdentityReadFailure() {
        int failures = this.identityReadFailures.incrementAndGet();
        long backoffMs = identityReadBackoffMs(failures);
        this.identityRetryAtMs.set(System.currentTimeMillis() + backoffMs);
        return backoffMs;
    }

    /**
     * 身份恢复/落定：清退避与连续失败计数（"身份恢复后清退避"）。
     */
    private void clearIdentityBackoff() {
        if (this.identityReadFailures.get() != 0) {
            this.identityReadFailures.set(0);
        }
        if (this.identityRetryAtMs.get() != 0L) {
            this.identityRetryAtMs.set(0L);
        }
    }

    /**
     * 第 {@code failures} 次连续失败后的退避（1s 起，指数翻倍，30s 封顶）。
     */
    static long identityReadBackoffMs(int failures) {
        long shift = Math.min(Math.max(0, failures - 1), 5);
        return Math.min(IDENTITY_READ_BACKOFF_MAX_MS, IDENTITY_READ_BACKOFF_BASE_MS << shift);
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
                // 身份不可确认 ⇒ 本拍不投（绝不带着无身份进投递链）；主动摘除让前端带 lastSeq 重挂，
                // 重挂会重新解析身份并回放归档（既有恢复链）。
                TenantUser identity = deliveryIdentity();
                if (identity == null && !sub.hasIdentityDegradedFrame()) {
                    // 队列里若还有"无法归档的终结局"（帧级 identityDegraded），放行写出链，
                    // 让客户端至少拿到 typed error 而不是无说明的断连。
                    warnOnce(this.identityFailureWarned, "identity unconfirmed before delivery runId={} (disconnect; client reattach replays)", this.runId);
                    evict(sub, "identity unconfirmed before delivery");
                    return;
                }
                try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
                    drain(sub);
                }
            }));
        } catch (RejectedExecutionException ex) {
            sub.draining.set(false);
            evict(sub, "delivery pool rejected (queue full)");
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
     * （生产者 kick 或上一轮接管），它可能刚 `poll` 走尾帧、还没进写出阶段
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
     *
     * <p><b>P1-07：每拍有界（帧数 + 时间片）。</b>旧实现一个 drain 任务就是"清空整条队列"的循环，
     * 少量慢连接即可长期占满投递池线程。现在到点后直接返回，由 {@link #finishDrain} 的接管分支
     * 把剩余帧交给新的 drain 任务（CAS 独占权不释放给其它生产者插队，FIFO 与收尾语义不变）。
     * 队列空但回放未读到底时，按页续读（{@link #advanceReplay}，单拍页数也有界）。
     */
    private boolean drainFrames(Subscription sub, long sliceDeadline, int frameBudget, int replayPages) {
        while (true) {
            if (sub.closed) {
                break;
            }
            if (this.writeBudget.budgeted() && isConsumeInterruptAndEvict(sub)) {
                // 中断只在<b>帧边界</b>处理（drain 拍首、尚未取帧/提交写）：摘除 + 收尾。
                return true;
            }
            if (frameBudget <= 0 || System.nanoTime() - sliceDeadline >= 0L) {
                // 本拍到点：return 走 finally → finishDrain 的 canTakeOverDrain 分支重新排队。
                return false;
            }
            QueuedFrame frame = sub.queue.poll();
            if (frame == null) {
                // P1-06：队空才续读回放；一页/一批都有界，页数也按单拍上限收敛。
                if (!sub.replayActive || replayPages >= REPLAY_PAGES_PER_BEAT) {
                    break;
                }
                replayPages++;
                if (isAdvanceReplay(sub)) {
                    continue;
                }
                if (sub.replayActive) {
                    // 本页全部被过滤（心跳/孤儿）等无入队情形：继续读下一页，页数仍受本拍上限约束。
                    continue;
                }
                break;
            }
            if (frame.live) {
                liveDequeued(sub);
            }
            // 注意：identityDegradedQueued <b>不在此递减</b> —— 该帧可能正在异步写（writeInFlight），
            // 期间其他身份闸（resume/submitDrain）仍须看到"降级终帧许可"；订阅者收尾时统一归零。
            if (this.writeBudget.budgeted()) {
                // I3：异步写出 —— 本线程立即交还（不 future.get 等 15s），
                // 由写出完成回调继续下一帧；drain 独占权保持（suspended 时不走 finishDrain）。
                beginBudgetedWrite(sub, frame);
                return true;
            }
            if (!isWriteFrameDirect(sub, frame.payload)) {
                return false;
            }
            frameBudget--;
        }
        return false;
    }

    private void drain(Subscription sub) {
        long sliceDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_SLICE_MILLIS);
        int frameBudget = DRAIN_BATCH_FRAMES;
        int replayPages = 0;
        boolean suspended = false;
        try {
            suspended = drainFrames(sub, sliceDeadline, frameBudget, replayPages);
        } finally {
            if (!suspended) {
                finishDrain(sub);
            }
        }
    }

    /**
     * I3：提交一次<b>异步</b>预算写出（调用方在 drain 里；返回后 drain 挂起、CAS 投递权保持）。
     *
     * <p>写完成/超时/饱和都经回调继续：{@code SENT} 继续 drain 下一帧；其余按既有摘除语义收尾。
     * 15s 写预算、FIFO（写完成才取下一帧）与"不提前驱逐正常慢客户端"（预算未到不摘）都保持不变。
     */
    private void submitBudgetedWrite(Subscription sub, QueuedFrame frame, TenantUser writeIdentity,
                                     AtomicBoolean started) {
        try {
            this.writeBudget.writeAsync(() -> {
                started.set(true);
                try (RunIdentityScope.Scope ignored = RunIdentityScope.open(writeIdentity)) {
                    sub.emitter.send(frame.payload);
                } finally {
                    sub.writeInFlight.set(false);
                    if (sub.completePending) {
                        // 写在飞期间出现的收尾请求：由写出线程在 send 返回后补做。
                        completeOne(sub);
                    }
                }
            }, result -> {
                switch (result.outcome()) {
                    case SENT -> resumeDrainAfterWrite(sub);
                    case FAILED -> {
                        logWriteFailure(result.failure());
                        evict(sub, "write failed (client disconnected)");
                        finishDrain(sub);
                    }
                    case TIMED_OUT -> {
                        releaseWriteSlotIfNeverStarted(sub, started);
                        evictOnWriteTimeout(sub);
                        finishDrain(sub);
                    }
                    case SATURATED -> {
                        releaseWriteSlotIfNeverStarted(sub, started);
                        evict(sub, "write pool saturated (concurrent writes at hard limit " + SseWriteBudget.sharedWriterPoolMaxThreads() + ")");
                        finishDrain(sub);
                    }
                    // 异步写路径不产出 INTERRUPTED（没有阻塞 get）；真实线程中断由
                    // consumeInterruptAndEvict 在 drain/beginBudgetedWrite 入口消费并走同一摘除出口。
                }
            });
        } catch (RuntimeException ex) {
            // 提交阶段异常（非池拒绝）：本次写不会发生，必须复位写在飞标志并摘除，避免"永不 complete"。
            releaseWriteSlotIfNeverStarted(sub, started);
            warnOnce(this.asyncWriteSubmitWarned, "async write submit failed runId={} (subscriber evicted)", this.runId, ex);
            evict(sub, "async write submit failed");
            finishDrain(sub);
        }
    }

    private void beginBudgetedWrite(Subscription sub, QueuedFrame frame) {
        if (sub.closed) {
            finishDrain(sub);
            return;
        }
        // 帧边界（提交写池之前）检查并消费线程中断：命中即摘除 + 收尾；不把中断归因于"正在飞的写"。
        if (isConsumeInterruptAndEvict(sub)) {
            return;
        }
        TenantUser writeIdentity = deliveryIdentity();
        if (writeIdentity == null && !frame.identityDegraded && !sub.hasIdentityDegradedFrame()) {
            warnOnce(this.identityFailureWarned, "identity unconfirmed before write runId={} (disconnect; client reattach replays)", this.runId);
            evict(sub, "identity unconfirmed before write");
            finishDrain(sub);
            return;
        }
        sub.completePending = false;
        sub.writeInFlight.set(true);
        AtomicBoolean started = new AtomicBoolean();
        submitBudgetedWrite(sub, frame, writeIdentity, started);
    }

    /**
     * 写出完成后继续 drain（CAS 投递权仍由本订阅者的 drain 链持有）。
     */
    private void resumeDrainAfterWrite(Subscription sub) {
        // 写池回调里<b>不得直接 drain / submit 下一帧</b>（那会让写线程继续占着 drain 链）。
        // 统一走 finishDrain 的 handoff：释放 CAS 独占权，剩余帧由 submitDrain 交回
        // deliveryExecutor；终态且队列空时在这里完成收尾。FIFO/CAS/收尾语义不变。
        finishDrain(sub);
    }

    /**
     * 异步写路径的<b>帧边界线程中断契约</b>。
     *
     * <p>异步写没有 {@code future.get}，因此中断<b>不是</b>"实时写在飞时立即摘除"：本方法只在
     * <b>帧边界</b>调用（drain 每拍开头、{@link #beginBudgetedWrite} 提交写池之前），检查并
     * <b>消费</b>中断位；命中即摘除订阅者并完成收尾（前端按 onerror/onclose 带 lastSeq 重挂）。
     * 正常路径（无中断）不产生任何副作用。
     *
     * <p>若中断发生在<b>某次写正在飞</b>期间，本方法不会在写线程里被调用、也不把中断归因于那次写：
     * 该次写由 15s 单帧预算（{@link SseWriteBudget#writeAsync} 的超时回调）与停机时关闭写池收敛。
     */
    private boolean isConsumeInterruptAndEvict(Subscription sub) {
        if (!Thread.interrupted()) {
            return false;
        }
        log.warn("write interrupted (caller thread) runId={} (subscriber evicted; client reattach replays delta)", this.runId);
        evict(sub, "write interrupted (caller thread interrupted)");
        finishDrain(sub);
        return true;
    }

    /**
     * drain 收尾：先清投递权，再按"帧面 + 终态收尾意图"决定 handoff / complete / 退出。
     *
     * <p>
     * <b>L-143/W-01：收尾前必须重新持权。</b>抢不到标志 ⇒ 另一个 drain 可能已经 `poll` 走尾帧、
     * 还没进写出阶段（{@code writeInFlight} 尚未置位）；此时若按 {@code queue.isEmpty()}
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
                            && !sub.replayActive && this.emitting.get() == 0;
                    if (ready) {
                        // S1-1：终态只在队列<b>真正空了</b>才收尾。判空与 emit 的落档/入队同锁，
                        // 因此"已落档的帧不会被收尾抢跑"。
                        completeOne(sub);
                    }
                }
            } finally {
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
     * <b>直执</b>单帧写出（{@link SseWriteBudget#direct()}：单测/非 Spring 路径）。返回 false =
     * 本订阅者已终结，drain 立即退出。
     *
     * <p>生产装配走 {@link #beginBudgetedWrite}（异步写出 + 15s 预算回调），本方法只保留原来的
     * "调用线程内 send、客户端断开只摘除该订阅者"语义，测试与时序用例的确定性不变。
     */
    private boolean isWriteFrameDirect(Subscription sub, String payload) {
        try {
            sub.emitter.send(payload);
            return true;
        } catch (Exception ex) {
            logWriteFailure(ex);
            evict(sub, "write failed (client disconnected)");
            return false;
        }
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
     * <p><b>三处调用点（同一缺陷族）</b>：{@code SATURATED}（提交被拒，λ 必然未跑）、
     * {@code TIMED_OUT}（超时后 cancel 掉"已接单但从未执行"的那次写）、以及
     * {@code writeAsync} 提交阶段抛 {@code RuntimeException} 的收口。三处的共同后果都是
     * "收尾交接给一个不存在的写线程"。真实线程中断不在这里处理 —— 异步写不产生
     * {@code INTERRUPTED}，由 {@link #consumeInterruptAndEvict} 在入口消费。
     */
    private static void releaseWriteSlotIfNeverStarted(Subscription sub, AtomicBoolean started) {
        if (!started.get()) {
            sub.writeInFlight.set(false);
        }
    }

    /**
     * 写出失败的统一日志口径（Q8②：它是"这一个订阅者没了"，不是本轮失败）。
     */
    private void logWriteFailure(Exception ex) {
        if (LogSampling.canAcquire("sse-write-failure")) {
            log.warn("SSE write failed (client disconnected?) runId={}", this.runId, ex);
        }
    }

    /**
     * 包②：写超时 ⇒ 摘除该订阅者（主动断连），<b>但不在此处 complete</b>。
     *
     * <p>
     * 为什么不能顺手 complete：写线程仍卡在这次写出里并<b>持有 emitter 监视器</b>
     * （{@code ResponseBodyEmitter#send} 与 {@code #complete} 同为 {@code synchronized}），
     * 从本线程调用只会把自己一起挂住 —— 那正是本包要消除的形态。于是这里只做三件事：
     * 计数、日志、关闭投递上下文（摘表 + 清队列 + 计数归零 + 复位标志）；emitter 的收尾交给
     * "卡住的那次写出返回时"的写出线程（{@link #beginBudgetedWrite} 的 SendTask finally 分支）。
     *
     * <p>
     * 与 {@link #evict} 的分工：本方法不动 {@code evicted} 去重标志，也不碰 emitter ——
     * 若随后既有的失败/溢出路径再来一次 {@code evict}，其 {@code completeOne} 会因
     * {@code completed} CAS 而去重，语义不变（前端只看到一次断连）。
     */
    private void evictOnWriteTimeout(Subscription sub) {
        boolean first = sub.writeTimedOut.compareAndSet(false, true);
        closeSubscription(sub, "write timeout (>" + this.writeBudget.timeoutMillis() + "ms)");
        if (first) {
            EVICTED_TOTAL.incrementAndGet();
            log.warn("write timeout, subscriber evicted runId={} budget={}ms writeTimeouts={} "
                            + "(client reconnects via onerror/onclose and replays delta)",
                    this.runId, this.writeBudget.timeoutMillis(), SseWriteBudget.writeTimeouts());
            // 结构化出口（AI 日志最小补齐批）：把"谁被摘了、为什么"从一行 WARN 升成可聚合事件
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
     * 还有活要干（队列非空，<b>或分页回放未读到底</b>）且本订阅者未被关闭、且（终态回放 ∨ 轮终态 ∨
     * 仍在订阅表）。守卫<b>先</b>判、CAS<b>后</b>抢：条件不成立时标志保持"无人持有"，不会留下
     * {@code draining=true} 的残影（残影会让该订阅者永远不再被投递）。
     */
    private boolean canTakeOverDrain(Subscription sub) {
        return !sub.closed && (!sub.queue.isEmpty() || sub.replayActive)
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
        log.warn("subscriber evicted runId={} reason={}", this.runId, reason);
        AiTrace.eventOn(this.runId, null, "sse_detach", "kind", "evicted", "reason", reason,
                "subscribers", this.subscribers.size());
        closeSubscription(sub, "evicted: " + reason);
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
     * （{@link #beginBudgetedWrite} 的 SendTask finally），由它在 {@code send} 返回/抛出之后补做。
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
        closeSubscription(sub, "completing subscriber");
        try {
            sub.emitter.complete();
        } catch (Exception ex) {
            if (LogSampling.canAcquire("sse-chat-exception-catch"))
                log.warn("SSE complete failed runId={}", this.runId, ex);
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
        return epochMs(frame.get("atMs")) + Math.max(0, timeoutSeconds) * 1000L;
    }

    private static long epochMs(Object atMs) {
        return atMs instanceof Number number ? number.longValue()
                : NumberUtils.toLong(String.valueOf(atMs), System.currentTimeMillis());
    }

    /**
     * T5：工具结果消息的身份（结果铸为消息）—— 由 toolCallId 确定性派生，重放/重挂不变。
     */
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
        // F1：终态 ⇒ 本地保活立即停（终帧之后不得再有心跳；冷路径上也没有订阅者需要它了）。
        this.terminalFrameSeen.set(true);
        stopAttachKeepalive();
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
        } finally {
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
                    // P1-06：分页回放未读到底时不得收尾（"读到底才允许终态 complete"）。
                    ready = sub.queue.isEmpty() && !sub.replayActive && this.emitting.get() == 0;
                    if (ready) {
                        completeOne(sub);
                    }
                }
            } finally {
                flushWakeSyncIfUnlocked();
            }
            if (ready) {
                return;
            }
            if (!sub.queue.isEmpty() || sub.replayActive) {
                // 还有帧/还有页：交给 drain（它排空回放后按队空分支收尾）。
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
     * 回放暂存元素：帧体 + 可选的<b>归档原文</b>（原文口读到时为 {@code json}，交付时直接复用，
     * 不再序列化一次；类型化读口/兼容实现为 {@code null}）。
     */
    private record ReplayFrame(Map<String, Object> frame, String rawJson) {
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

        /**
         * CAS drain 独占标志：true = 有（或正抢）一个 drain 任务负责清空本队列。
         */
        final AtomicBoolean draining = new AtomicBoolean(false);

        /**
         * 摘除去重（溢出 / 写出失败 / 池拒绝 / 轮终态 complete 可能并发命中同一个）。
         */
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

        /**
         * 收尾去重（轮终态排空 / drain 队空 / 摘除三条路径可能并发命中同一个订阅者）。
         */
        final AtomicBoolean completed = new AtomicBoolean(false);

        /**
         * 包②：本订阅者有<b>一次写出正在别的线程里飞</b>（{@link #beginBudgetedWrite} 的预算模式）。
         *
         * <p>
         * 它是"能否就地 {@code emitter.complete()}"的判据：写线程卡在 {@code send} 时持有
         * emitter 监视器（{@code send}/{@code complete} 同为 {@code synchronized}），
         * 此时任何别的线程 complete 都会挂在监视器上 —— 见 {@link #completeOne} 的交接逻辑。
         * 直执模式（无预算）不置位：写出就是本线程，无跨线程交叠。
         */
        final AtomicBoolean writeInFlight = new AtomicBoolean(false);

        /**
         * 写在飞期间产生的收尾请求：由写出线程在 {@code send} 返回后补做（见 {@link #completeOne}）。
         */
        volatile boolean completePending;

        /**
         * 包②：本订阅者是否因<b>写超时</b>被摘除（去重日志/计数用；与 {@link #evicted} 分开口径）。
         */
        final AtomicBoolean writeTimedOut = new AtomicBoolean(false);

        /**
         * <b>已排入但尚未收尾</b>的身份降级终结局条数（P1-09 minor：许可位在
         * {@link QueuedFrame#identityDegraded} 上）。计入"仍在队列"与"已被 drain 弹出、正在异步写"
         * 两种情况 —— 因此写进行中其他身份闸（submitDrain/resume）仍能看到许可，不会误摘；
         * 订阅者收尾（{@link #closeSubscription}）时统一归零，不会永久放行。
         */
        final AtomicInteger identityDegradedQueued = new AtomicInteger();

        boolean hasIdentityDegradedFrame() {
            return this.identityDegradedQueued.get() > 0;
        }

        /**
         * 本订阅者的<b>已投递游标</b>（收到的最大 seq；0 = 全量）。三条投递路径共同推进：
         * 回放（{@code readReplayStart}/{@code advanceReplay}）、本地实时扇出（{@code emit}）、
         * 唤醒补帧（{@code onWake}）。
         *
         * <p>作用有两个：① 客户端带 {@code ?lastSeq=N} 重挂、而归档此刻没有新帧时，回放暂存 0 帧
         * ⇒ 游标仍须是 N（否则下一次唤醒把 1..N 重发一遍）；② 唤醒补帧的<b>读取下界</b>取所有活订阅者
         * 游标的<b>最小值</b>（{@link SseChatEmitter#minDeliveredSeq()}）—— 别的订阅者回放推进共享上界
         * 不会让落后的订阅者永久缺帧（独立复审 F3）。
         */
        volatile long wakeFloor;

        /**
         * <b>P1-06：分页回放游标与暂存</b>（guarded by {@link SseChatEmitter#emitLock}）。
         *
         * <p>{@code replayCursor} = 已从归档读到的最大 seq（下一页从它继续读）；
         * {@code replayPending} = 已读入但<b>尚未入队</b>的帧（按归档顺序，最多一页）；
         * {@code replayMorePages} = 上一次读满一页（可能还有下一页）；
         * {@code replayTerminalSeen} = 分页过程中已读到 done/error 终帧（决定"读到底即可收尾"）。
         */
        long replayCursor;

        final ArrayDeque<ReplayFrame> replayPending = new ArrayDeque<>();

        /**
         * {@code ?lastSeq}（孤儿过滤口径），随订阅固定；null = 全量回放。
         */
        Long replayOrphanFloor;

        boolean replayMorePages;

        boolean replayTerminalSeen;

        /**
         * 回放<b>尚未读到底 / 暂存尚未入队完</b>（跨锁可见的快速判据）。
         *
         * <p>置位期间实时业务帧不走直接扇出（由分页通道按归档顺序补齐），收尾判据也必须等它归零
         * —— 于是"回放读到底"成为终态 {@code complete} 的前置条件，不会丢正文或终帧。
         */
        volatile boolean replayActive;

        /**
         * 跨锁只读视图（语义见 {@code replayActive}）；写入恒在 {@code emitLock} 内。
         */
        boolean replayActive() {
            return this.replayActive;
        }

        /**
         * M6：重挂时<b>快照已终态</b>（{@code terminal=true}）。归档若在宽限窗口内仍没有终帧，
         * 由既有 {@code terminalUnarchived} 恢复链收流，而不是让连接一直 loading 到前端看门狗。
         */
        volatile boolean terminalExpected;

        /**
         * 终帧宽限只排一次（一次重挂一条连接）；"忙"重排受 {@link #UNARCHIVED_TERMINAL_GRACE_RETRIES} 上限约束。
         */
        final AtomicBoolean unarchivedTerminalGrace = new AtomicBoolean();

        /**
         * 宽限"忙"重排次数（队列/在飞 emit 未排空，不是永久放弃）。
         */
        final AtomicInteger unarchivedTerminalGraceAttempts = new AtomicInteger();

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

        /**
         * true = 实时段（计入 {@link Subscription#liveQueued}）；false = 回放批（豁免实时容量）。
         */
        final boolean live;

        /**
         * 帧级"身份降级终结局"许可（P1-09 minor：放行不再挂在订阅者上）。
         *
         * <p>只有 {@code unresolvedEnding} 在身份不可确认/终帧未归档时排入的<b>合成终帧</b>为 true；
         * 写出链对这一帧跳过身份闸；队列计数在该帧被弹出后仍保持到订阅者收尾（异步写进行中
         * 也允许其他身份闸通过），投递完/关闭后统一归零。
         */
        final boolean identityDegraded;

        QueuedFrame(String payload, boolean live) {
            this(payload, live, false);
        }

        QueuedFrame(String payload, boolean live, boolean identityDegraded) {
            this.payload = payload;
            this.live = live;
            this.identityDegraded = identityDegraded;
        }

    }

}
