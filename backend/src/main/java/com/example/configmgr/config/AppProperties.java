package com.example.configmgr.config;

import com.example.configmgr.common.sse.SseWriteBudget;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

import java.time.Duration;

/**
 * 基座业务配置（{@code app.*}）。
 *
 * <p>
 * {@code app.ai.*} 不在本类：AI Runtime 的运行参数由 {@code com.example.configmgr.ai.config.AiProperties}
 * 单一绑定（S4.2-P1 遗留 N4 的"双绑定"已消除 —— 原来的 {@code AppProperties.Ai}
 * 带 max-iterations/session-ttl-minutes/hitl-timeout-minutes/thinking-disabled 四个键，
 * 与规格 §4 的键表重复或已失效，本棒一并删除）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Job job = new Job();
    private FileStorage file = new FileStorage();
    private Task task = new Task();

    /**
     * 启动期校验 {@code app.task.sse.emitter-timeout} 必须为正（2026-10-10 独立复核 S4-⑨ 收口）。
     *
     * <p>
     * 读的是<b>绑定后</b>的值：{@code ConfigurationPropertiesBindingPostProcessor} 在
     * {@code @PostConstruct} 之前完成绑定，与 {@link com.example.configmgr.ai.config.AiProperties}
     * 的容量联动校验同口径（同一处失败面：属性 bean 自身的初始化）。{@code 0} ⇒ 该订阅者没有生命周期
     * 超时：卡住的写在 15s 写预算之后只剩容器写超时（{@code server.tomcat.connection-timeout}，
     * 缺省 120s，随连接配置漂移）兜底，滞留在连接上的写线程与投递上下文的清理时刻也随之漂移 ——
     * 正是本包要消除的最弱一档。
     *
     * <p>
     * 这里 <b>fail-fast</b>（不是只告警）：与容量联动校验不同，本项配错没有"性能降级"这种解释空间，
     * 它直接废掉"每条流有终期"这一前提。
     */
    @PostConstruct
    void verifyTaskSseEmitterTimeout() {
        Duration emitterTimeout = this.task.getSse().getEmitterTimeout();
        if (emitterTimeout == null || emitterTimeout.isZero() || emitterTimeout.isNegative()) {
            throw new IllegalStateException("app.task.sse.emitter-timeout 必须 > 0（当前 = " + emitterTimeout
                    + "）：0/负值等于取消订阅者的生命周期超时，卡住的写在写预算（15s）之后只剩容器写超时"
                    + "（connection-timeout，缺省 120s 且随配置漂移）兜底");
        }
    }

    /**
     * 启动期校验 {@code app.task.sse.write-timeout} 必须为正（2026-10-10 复核 S3-1 收口：守卫对称化）。
     *
     * <p>
     * 与 {@link #verifyTaskSseEmitterTimeout()} 同姿态、同失败面：{@code SseWriteBudget.on} 对
     * <b>非正</b>预算会<b>静默回落</b>缺省 15s（{@code timeoutMillis > 0 ? timeoutMillis : DEFAULT}）。
     * 于是运维显式配 {@code write-timeout: 0s}（意图"取消写预算"）、或负值/笔误，
     * <b>没有任何告警</b>就把运行行为挪到了 15s —— 配置文件与运行行为不符，排障时误导；
     * 而同一个属性 bean 里的 {@code emitter-timeout} 却 fail-fast，是同一处两种守卫姿态。
     *
     * <p>
     * 读的同样是<b>绑定后</b>的值，且与 AI 通道（{@link com.example.configmgr.ai.config.AiProperties}
     * 上的 write-timeout 守卫）同款校验、对称覆盖，两条 SSE 通道一道收口。这里也<b>fail-fast</b>
     * （不是只告警）：0/负值没有"性能降级"这种解释空间，它只会让配置面与运行面说两套话。
     */
    @PostConstruct
    void verifyTaskSseWriteTimeout() {
        Duration writeTimeout = this.task.getSse().getWriteTimeout();
        if (writeTimeout == null || writeTimeout.isZero() || writeTimeout.isNegative()) {
            throw new IllegalStateException("app.task.sse.write-timeout 必须 > 0（当前 = " + writeTimeout
                    + "）：0/负值会被 SseWriteBudget 静默回落为缺省 15s，运行行为与配置文件不符"
                    + "（排障误导）；收紧写侧释放只需配一个更小的正值");
        }
    }

    @Data
    public static class Job {
        private int demoBatchDelayMs = 300;
        private int batchSize = 100;
    }

    @Data
    public static class FileStorage {
        private String storagePath = "./data/files";
    }

    /** 任务域配置（{@code app.task.*}）。 */
    @Data
    public static class Task {

        private Sse sse = new Sse();

        /**
         * 任务级 SSE 通道（{@code GET /api/tasks/{id}/events}）的写侧预算（包②：SSE 写侧超时预算）。
         *
         * <p>
         * 与 AI 通道（{@code app.ai.sse.write-timeout}）取同一缺省 —— 两条通道的写侧是同一形态的
         * 阻塞式 servlet 写，预算口径必须一致（判据与实测见
         * {@link com.example.configmgr.common.sse.SseWriteBudget}）。
         */
        @Data
        public static class Sse {

            /**
             * 单帧写出的超时预算（缺省 15s）。超预算即摘除该订阅者并记 WARN，
             * 调用线程（作业线程 / 取消接口的 HTTP 线程）按预算脱身。
             *
             * <p>
             * <b>必须 &gt; 0</b>：非正值会被 {@code SseWriteBudget.on} 静默回落成缺省 15s，
             * 故改由启动期 {@link AppProperties#verifyTaskSseWriteTimeout()} 拦下（与
             * {@code emitter-timeout} 同一 fail-fast 姿态）。
             */
            private Duration writeTimeout = Duration.ofMillis(SseWriteBudget.DEFAULT_TIMEOUT_MILLIS);

            /**
             * 订阅者（{@code SseEmitter}）的<b>生命周期</b>超时，必须 &gt; 0（原为 {@code 0L} = 永不过期；
             * 启动期由 {@link AppProperties#verifyTaskSseEmitterTimeout()} 强制）。
             *
             * <p>
             * 作用不是"限制一帧写多久"（那是 write-timeout），而是给这条流一个<b>终期</b>：到点容器关闭
             * 连接 ⇒ 阻塞写抛 IOException ⇒ 线程退出。注意它<b>不是</b>写侧唯一的释放手段 —— 本配置下
             * 卡住的写通常更早被容器的 120s 写超时（{@code server.tomcat.connection-timeout} 派生）以
             * {@code SocketTimeoutException} 收场；{@code 0L} 的语义因此是"少这一层、释放时刻随连接配置
             * 漂移"，而<b>不是</b>"连接永无释放手段"。取值 5 分钟
             * = <b>与 AI 通道的 emitter 生命周期同量级对齐</b>（AI 通道的 emitter 由
             * {@code AiController} 用轮次总预算 {@code app.ai.resilience.total-budget}（300s）构造），
             * 两条通道的"单条流寿命"因此是同一口径。
             *
             * <p>
             * 前端代价已论证可接受：{@code openTaskEvents} 用 {@code EventSource}（断线按 retry 自动
             * 重连），{@code utils/job-watch.ts} 另有固定间隔的快照轮询兜底（"EventSource 断线/代理抖动时
             * 业务不能停在假进度上"）—— 周期性重连不丢业务状态。
             */
            private Duration emitterTimeout = Duration.ofMinutes(5);
        }
    }
}
