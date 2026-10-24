package com.example.configmgr.common;

/**
 * AI 运行时依赖（Redis / 决策 guard / fence / 附件登记）<b>暂时不可用</b>。
 *
 * <p>与普通 {@link IllegalStateException} 的区别是<b>对外口径</b>：前者是客户端请求状态不对
 * （400），本类是基础设施故障，必须映射成 <b>503 + {@code AI_REDIS_UNAVAILABLE}</b>
 * —— 否则同一次 Redis 抖动会在不同入口变成 400 / 500 / 静默三种口径，监控还会把故障统计成 4xx。
 *
 * <p>继承 {@link CodedIllegalStateException}：既有 catch {@code IllegalStateException} 的分支
 * 语义不变（仍然是"这次做不了"），但全局出口按更具体的类型优先映射为 503。
 */
public class AiStateUnavailableException extends CodedIllegalStateException {

    public AiStateUnavailableException(String message) {
        super(ErrorCodes.AI_REDIS_UNAVAILABLE, message);
    }

    public AiStateUnavailableException(String message, Throwable cause) {
        super(ErrorCodes.AI_REDIS_UNAVAILABLE, message, cause);
    }
}
