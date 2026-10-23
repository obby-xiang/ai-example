package com.example.configmgr.testmap;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不稳定集合重估的纯函数用例（L-101）。
 *
 * <p>被测对象是 {@link TestmapRecorder#reviseUnstable}：把「一次录制即永久」的冒烟集改成
 * 「连续 N 次干净自动移出 + 时间窗兜底」，并修掉「不可比录制顺手清空整批」的口径缺口。
 * 这些规则是成本收敛的唯一闸门，故按边界逐条钉死。
 */
class TestmapUnstableBookTest {

    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final long NOW = 1_800_000_000_000L;
    private static final int CLEAN_RUNS = 2;
    private static final long MAX_AGE = 7 * DAY;

    private static Map<String, Object> history(Object... kv) {
        Map<String, Object> h = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            h.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return h;
    }

    private static Map<String, Object> entry(long firstDiffAt, long lastDiffAt, int cleanStreak) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("firstDiffAt", firstDiffAt);
        e.put("lastDiffAt", lastDiffAt);
        e.put("cleanStreak", cleanStreak);
        return e;
    }

    @SuppressWarnings("unchecked")
    private static List<String> active(Map<String, Object> revised) {
        return (List<String>) revised.get("active");
    }

    @SuppressWarnings("unchecked")
    private static List<String> retired(Map<String, Object> revised) {
        return (List<String>) revised.get("retired");
    }

    @Test
    void firstDifferencePutsClassIntoSmokeSetWithZeroCleanStreak() {
        Map<String, Object> revised = TestmapRecorder.reviseUnstable(
                null, List.of(), List.of("p.FlakeTest"), NOW, CLEAN_RUNS, MAX_AGE);

        assertThat(active(revised)).containsExactly("p.FlakeTest");
        assertThat(retired(revised)).isEmpty();
        Map<String, Object> history = (Map<String, Object>) revised.get("history");
        assertThat(((Map<String, Object>) history.get("p.FlakeTest")).get("cleanStreak")).isEqualTo(0);
        assertThat(((Map<String, Object>) history.get("p.FlakeTest")).get("firstDiffAt")).isEqualTo(NOW);
    }

    @Test
    void nonComparableRecordingKeepsTheSetInsteadOfClearingIt() {
        Map<String, Object> revised = TestmapRecorder.reviseUnstable(
                history("p.FlakeTest", entry(NOW - 2 * DAY, NOW - 2 * DAY, 0)),
                List.of("p.FlakeTest"), null, NOW, CLEAN_RUNS, MAX_AGE);

        assertThat(active(revised)).containsExactly("p.FlakeTest");
        Map<String, Object> history = (Map<String, Object>) revised.get("history");
        assertThat(((Map<String, Object>) history.get("p.FlakeTest")).get("lastDiffAt")).isEqualTo(NOW - 2 * DAY);
        assertThat(((Map<String, Object>) history.get("p.FlakeTest")).get("cleanStreak")).isEqualTo(0);
    }

    @Test
    void retiresOnlyAfterConsecutiveCleanRecordings() {
        Map<String, Object> once = TestmapRecorder.reviseUnstable(
                history("p.FlakeTest", entry(NOW - DAY, NOW - DAY, 0)),
                List.of("p.FlakeTest"), List.of(), NOW, CLEAN_RUNS, MAX_AGE);
        assertThat(active(once)).containsExactly("p.FlakeTest");
        assertThat(retired(once)).isEmpty();

        Map<String, Object> twice = TestmapRecorder.reviseUnstable(
                (Map<String, Object>) once.get("history"),
                active(once), List.of(), NOW + 1000, CLEAN_RUNS, MAX_AGE);
        assertThat(active(twice)).isEmpty();
        assertThat(retired(twice)).containsExactly("p.FlakeTest");
    }

    @Test
    void cleanStreakResetsWhenTheDifferenceComesBack() {
        Map<String, Object> again = TestmapRecorder.reviseUnstable(
                history("p.FlakeTest", entry(NOW - DAY, NOW - DAY, 1)),
                List.of("p.FlakeTest"), List.of("p.FlakeTest"), NOW, CLEAN_RUNS, MAX_AGE);

        assertThat(active(again)).containsExactly("p.FlakeTest");
        Map<String, Object> history = (Map<String, Object>) again.get("history");
        assertThat(((Map<String, Object>) history.get("p.FlakeTest")).get("cleanStreak")).isEqualTo(0);
        assertThat(((Map<String, Object>) history.get("p.FlakeTest")).get("lastDiffAt")).isEqualTo(NOW);
    }

    @Test
    void ageWindowRetiresStaleEntriesEvenWithoutCleanObservations() {
        Map<String, Object> revised = TestmapRecorder.reviseUnstable(
                history("p.OldTest", entry(NOW - 10 * DAY, NOW - 8 * DAY, 0)),
                List.of("p.OldTest"), null, NOW, CLEAN_RUNS, MAX_AGE);

        assertThat(active(revised)).isEmpty();
        assertThat(retired(revised)).containsExactly("p.OldTest");
        Map<String, Object> history = (Map<String, Object>) revised.get("history");
        assertThat(((Map<String, Object>) history.get("p.OldTest")).get("retiredReason")).isEqualTo("age-window");
    }

    @Test
    void policyIsConfigurableAndRetiredClassCanComeBack() {
        Map<String, Object> lenientOnce = TestmapRecorder.reviseUnstable(
                history("p.FlakeTest", entry(NOW - DAY, NOW - DAY, 0)),
                List.of("p.FlakeTest"), List.of(), NOW, 1, MAX_AGE);
        assertThat(active(lenientOnce)).isEmpty();
        assertThat(retired(lenientOnce)).containsExactly("p.FlakeTest");

        Map<String, Object> back = TestmapRecorder.reviseUnstable(
                (Map<String, Object>) lenientOnce.get("history"),
                active(lenientOnce), List.of("p.FlakeTest"), NOW + 1000, 1, MAX_AGE);
        assertThat(active(back)).containsExactly("p.FlakeTest");
    }
}
