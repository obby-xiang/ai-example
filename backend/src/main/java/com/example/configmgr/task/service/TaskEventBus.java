package com.example.configmgr.task.service;

import java.util.function.Consumer;

/**
 * 任务级 SSE 事件的<b>跨实例</b>传输面（多实例 job 接管批）。
 *
 * <h2>为什么需要它</h2>
 * 作业跑在 A 实例、浏览器连在 B 实例的 {@code GET /api/tasks/{id}/events} 上是多实例部署的常态。
 * 修复前 {@code TaskSseService.publish} 只扇出到<b>本进程</b>的 emitter 表，A 上的
 * {@code JOB_PROGRESS}/{@code JOB_DONE} 永远到不了 B 的订阅者（只能靠前端 3s 快照轮询兜底）。
 *
 * <h2>载荷口径（与状态不丢的边界）</h2>
 * 传输的只是<b>已序列化好的帧原文</b>（{@code {"type":..,"data":..}}）—— 帧是"状态投影"，
 * 权威状态在数据库（{@code GET /api/jobs/{jobId}}）。因此本总线是 <b>at-most-once 通知</b>：
 * Redis 抖动丢一帧不丢状态，前端 EventSource 重连 + 快照轮询自愈。
 *
 * <h2>实现契约</h2>
 * <ul>
 * <li><b>频道粒度 = 任务</b>（{@code task:events:<taskId>}），不做"每实例订阅全量频道"；</li>
 * <li>订阅生命周期由 {@link TaskSseService} 管理：<b>首订阅注册、末订阅退订</b>；</li>
 * <li>实现必须<b>忽略本实例自己发布的回声</b>（本地已直投一次），否则同一条连接收到两帧；</li>
 * <li>失败不抛：返回 {@code false} 表示已降级（Redis 不可用/容器起不来），调用方据此记降级
 *     并继续本机投递 —— <b>降级不等于丢状态</b>。</li>
 * </ul>
 */
public interface TaskEventBus {

    /** 频道前缀：{@code task:events:<taskId>}。 */
    String CHANNEL_PREFIX = "task:events:";

    /** 发布一条已序列化的任务帧。@return true = 已交给传输；false = 降级（本机投递不受影响）。 */
    boolean publish(Long taskId, String frameJson);

    /** 订阅某个任务（同一任务重复订阅由实现替换旧监听器）。@return false = 降级。 */
    boolean subscribe(Long taskId, Consumer<String> onFrame);

    /** 取消该任务的订阅（最后一个本地订阅者断开时调用；幂等）。 */
    void unsubscribe(Long taskId);

    /**
     * 单实例/单测语义的空实现：本机扇出已由 {@link TaskSseService} 直接完成，跨实例无传输。
     * 只在直接 {@code new TaskSseService(...)} 的场合使用；Spring 装配走
     * {@link RedisTaskEventBus}（{@code app.task.sse.cross-instance-store=redis|memory}）。
     */
    static TaskEventBus inProcess() {
        return new TaskEventBus() {
            @Override
            public boolean publish(Long taskId, String frameJson) {
                return true;
            }

            @Override
            public boolean subscribe(Long taskId, Consumer<String> onFrame) {
                return true;
            }

            @Override
            public void unsubscribe(Long taskId) {
            }
        };
    }
}
