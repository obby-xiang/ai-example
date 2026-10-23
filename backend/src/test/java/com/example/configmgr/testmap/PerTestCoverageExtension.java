package com.example.configmgr.testmap;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 逐测试类的覆盖录制扩展（设计稿 §3.3）。
 *
 * <p>经 {@code META-INF/services/org.junit.jupiter.api.extension.Extension} + {@code junit.jupiter.extensions
 * .autodetection.enabled=true} 自动挂到全部测试类上，不改 256 个测试类。未加 {@code -Ptestmap} 时
 * 自动挂载开关不打开，本类根本不加载；即便加载，所有回调也因 {@code testmap.enabled} 为假立即返回。
 *
 * <p>两个 dump 点：{@code afterTestExecution} 取增量（不 reset，迟到但未越过类边界的异步触达也能收）；
 * {@code afterAll} 再取一次余量并 reset，把本类区间关掉。最后一个类之后才完成的异步触达由收尾窗口
 * （JVM 关闭钩子 / LauncherSession 关闭）并入最后一个类，宁可多归不可丢失（M4）。
 *
 * <p>另在 {@code beforeTestExecution}/{@code afterTestExecution} 之间累计逐测试类耗时（L-101：选择器要
 * 把「不稳定类拖慢」量化成秒数，只有录制期能免费拿到这份数据）。
 */
public final class PerTestCoverageExtension
        implements BeforeTestExecutionCallback, AfterTestExecutionCallback, AfterAllCallback, TestWatcher {

    /** 用例唯一 id → 起始纳秒（录制期单 fork 串行执行，无需考虑并发隔离）。 */
    private static final Map<String, Long> STARTED = new ConcurrentHashMap<>();

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        if (TestmapRecorder.enabled()) {
            STARTED.put(context.getUniqueId(), System.nanoTime());
        }
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        if (!TestmapRecorder.enabled()) {
            return;
        }
        Long t0 = STARTED.remove(context.getUniqueId());
        if (t0 != null) {
            TestmapRecorder.addDuration(testClassOf(context), (System.nanoTime() - t0) / 1_000_000L);
        }
        TestmapRecorder.record(testClassOf(context), false);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (TestmapRecorder.enabled()) {
            TestmapRecorder.record(testClassOf(context), true);
        }
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        if (TestmapRecorder.enabled()) {
            TestmapRecorder.markFailed(testClassOf(context));
        }
    }

    private static String testClassOf(ExtensionContext context) {
        return context.getTestClass().map(Class::getName).orElse(null);
    }
}
