package com.example.configmgr.common;

/**
 * 携带机器可读码的异常（{@code getCode()} 返回 {@link ErrorCodes} 常量）。
 *
 * <p>
 * 为什么不是"新建一个业务异常基类"：本仓大量既有代码与用例按
 * {@code IllegalArgumentException} / {@code IllegalStateException} 的类型分支（工具执行器把
 * "参数非法"与"内部故障"分开处置），直接换父类会静默改语义。因此采用
 * <b>子类 + 接口</b>：类型仍是原来的（is-a 关系不变），只是多带一个码。
 */
public interface CodedException {

    /** {@link ErrorCodes} 里的码。 */
    String getCode();
}
