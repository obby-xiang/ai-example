package com.example.configmgr.job.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4.4b 缺陷 3（P3）用例：作业行级进度的分母下限。
 *
 * <p>缺陷形态：分母只累计"已开工配置项的行数"，于是<b>每个配置项收尾的瞬间</b>
 * {@code processed == total} → 进度条闪现 100%。下面用逐帧模拟断言这个瞬态不再出现。
 */
class JobProgressTotalsTest {

    @Test
    void denominatorReservesUnstartedItemsSoNoItemCompletionShowsFull() {
        // 导入/预检形态：开工前没有便宜的预估（行数来自 Excel），按"每个未开工配置项预留 1 行"兜底
        int[] rows = {100, 50, 30};
        int itemCount = rows.length;

        assertThat(JobProgressTotals.denominator(0, 0, itemCount))
                .as("开工第一帧：分母 = 配置项条数（旧实现为 0，故首配置收尾即 100%%）").isEqualTo(3);

        int discovered = 0;
        int finishedRows = 0;
        int peakBeforeLastItem = 0;

        for (int i = 0; i < itemCount; i++) {
            discovered += rows[i];
            int unstarted = itemCount - i - 1;
            int total = JobProgressTotals.denominator(0, discovered, unstarted);

            for (int seen = 1; seen <= rows[i]; seen++) {
                int processed = finishedRows + seen;
                assertThat(processed).as("分子恒不超分母（百分比不会溢出 100）").isLessThanOrEqualTo(total);
                if (unstarted > 0) {
                    peakBeforeLastItem = Math.max(peakBeforeLastItem, percent(processed, total));
                }
            }

            finishedRows += rows[i];
            if (unstarted > 0) {
                assertThat(finishedRows)
                        .as("第 " + (i + 1) + " 个配置项收尾时不得 progress==total（旧实现此处闪现 100%%）")
                        .isLessThan(total);
                peakBeforeLastItem = Math.max(peakBeforeLastItem, percent(finishedRows, total));
            }
        }

        assertThat(peakBeforeLastItem).as("最后一项收尾前的峰值 < 100%").isLessThan(100);
        assertThat(finishedRows)
                .as("全部收尾后分母回到真实工作量 → 恰好 100%")
                .isEqualTo(JobProgressTotals.denominator(0, discovered, 0));
    }

    @Test
    void estimatedRowsKeepsDenominatorStableForExport() {
        // 导出/发布形态：可便宜预估（各配置已发布行数 / 暂存行数合计 1200）
        int estimated = 1200;
        assertThat(JobProgressTotals.denominator(estimated, 0, 3))
                .as("开工第一帧即写终值 → 曲线全程单调").isEqualTo(1200);

        int[] rows = {1000, 100, 100};
        int discovered = 0;
        for (int i = 0; i < rows.length; i++) {
            discovered += rows[i];
            assertThat(JobProgressTotals.denominator(estimated, discovered, rows.length - i - 1))
                    .as("有预估时分母不随发现而变").isEqualTo(1200);
        }
    }

    @Test
    void denominatorRaisesWhenMoreRowsDiscoveredThanEstimated() {
        assertThat(JobProgressTotals.denominator(100, 0, 2)).isEqualTo(100);
        assertThat(JobProgressTotals.denominator(100, 180, 1))
                .as("已发现超过预估 → 分母上调（不小于真实工作量）").isEqualTo(181);
        assertThat(JobProgressTotals.denominator(100, 180, 0)).isEqualTo(180);
        assertThat(JobProgressTotals.denominator(0, 0, 0)).as("空作业不产生分母").isZero();
    }

    private static int percent(int processed, int total) {
        return total > 0 ? processed * 100 / total : 0;
    }
}
