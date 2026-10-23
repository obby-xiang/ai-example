package com.example.configmgr.testmap;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

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
 */
public final class PerTestCoverageExtension
        implements AfterTestExecutionCallback, AfterAllCallback, TestWatcher {

    @Override
    public void afterTestExecution(ExtensionContext context) {
        if (TestmapRecorder.enabled()) {
            TestmapRecorder.record(testClassOf(context), false);
        }
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
