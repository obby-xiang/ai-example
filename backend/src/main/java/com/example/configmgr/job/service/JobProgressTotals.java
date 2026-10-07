package com.example.configmgr.job.service;

/**
 * 作业行级进度分母（{@code jobs.total}）的计算规则（S4.4b 缺陷 3 修复）。
 *
 * <h2>要修的现象</h2>
 * 分母原先只累计"已开工配置项的行数"，而分子是"已扫描行数"——两者在<b>每个配置项收尾的瞬间相等</b>
 * （已开工的都处理完了，下一个还没被发现），于是第一个配置项跑完时进度条闪现 100%，随后掉回去。
 *
 * <h2>规则</h2>
 * <pre>
 *   total = max( 预估总行数 , 已发现行数 + 未开工配置项数 )
 * </pre>
 * <ul>
 * <li><b>预估总行数</b>：开工前能便宜数出来的就数（导出=该任务各配置的已发布行数合计，
 *     发布=各配置暂存行数合计）。此时 total 从开工第一帧起就是终值，曲线全程单调。</li>
 * <li><b>未开工配置项数</b>：数据来自 Excel（导入/预检）时无法在开工前预读，
 *     此时按"每个未开工配置项至少还要占 1 行"给分母留出下界，保证<b>未全部开工前分子追不上分母</b>；
 *     配置项开工时其真实行数并入"已发现行数"，分母随之单调上调。</li>
 * </ul>
 * <p>
 * 两条性质：{@code total >= processed}（分子恒不超分母，百分比不会溢出 100）与
 * {@code total >= 已发现行数}（分母不小于真实工作量）。已知边界：某配置项真实行数为 0 时，
 * 它收尾瞬间分母回落到"已发现行数"，可能出现一次 100%→下一项开工后的回落 —— 见证据文档【待裁决】。
 */
public final class JobProgressTotals {

    private JobProgressTotals() {
    }

    /**
     * @param estimatedRows 开工前的预估总行数；无便宜来源时传 0
     * @param discoveredRows 已开工配置项的行数合计（分母下界，单调不减）
     * @param unstartedItems 尚未开工的配置项数（各预留 1 行）
     */
    public static int denominator(int estimatedRows, int discoveredRows, int unstartedItems) {
        int reserved = discoveredRows + Math.max(unstartedItems, 0);
        return Math.max(estimatedRows, reserved);
    }
}
