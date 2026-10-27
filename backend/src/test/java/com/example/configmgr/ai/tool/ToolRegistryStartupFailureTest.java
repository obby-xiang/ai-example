package com.example.configmgr.ai.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>L-94</b>：工具 bean <b>创建失败</b>不得静默跳过（启动必须失败并点名该 bean）。
 *
 * <p>
 * 缺陷形态（2026-10-23 实证）：{@code ToolRegistry#init} 在扫描期对每个 bean 调
 * {@code getBean}，任何创建异常一律 {@code catch (Exception e) { continue; }}。构造期依赖环让
 * KB 三个工具 bean 整批创建失败时，注册表从 37 掉到 34，而应用「正常启动」——症状只是"模型
 * 少了一组工具"，比启动失败难查得多。类注自述「少工具比启动失败更难查」正是本条依据。
 *
 * <p>
 * 判据：<b>声明了 {@code @Tool} 方法的 bean</b>创建失败 ⇒ 收集全部失败 bean 名后启动失败
 * （{@link IllegalStateException}，消息点名 bean）；非工具 bean 的创建失败仍按扫描期噪音跳过
 * （Spring 自身在真正需要它时照旧会失败，不是本类要改变的语义）。
 *
 * <p><b>证红</b>：把 {@code init()} 的 catch 分支改回裸 {@code continue} ⇒ 前两条用例红
 * （"Expecting code to raise a throwable"）；只认"已实例化 bean 上有 @Tool"而不用 BeanDefinition
 * 声明类型 ⇒ 第三条（两个失败 bean 都要点名）红。
 */
class ToolRegistryStartupFailureTest {

    @Test
    void toolBeanCreationFailureAbortsStartup() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(FailingToolConfig.class)) {
            ToolRegistry registry = registryOn(context);

            assertThatThrownBy(registry::init)
                    .as("工具 bean 创建失败必须启动失败（否则整批工具静默消失）")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("failingTool")
                    .hasMessageContaining("工具 bean");
        }
    }

    @Test
    void everyFailedToolBeanIsNamedInOneFailure() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(TwoFailingToolConfig.class)) {
            ToolRegistry registry = registryOn(context);

            assertThatThrownBy(registry::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("firstFailingTool")
                    .hasMessageContaining("secondFailingTool");
        }
    }

    @Test
    void nonToolBeanCreationFailureStillJustNoise() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(NonToolFailureConfig.class)) {
            ToolRegistry registry = registryOn(context);

            assertThatCode(registry::init)
                    .as("非工具 bean 的创建失败仍是扫描期噪音：MethodToolCallbackProvider 对无 @Tool 的 bean 本身就抛错")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void emptyToolScopeFailsStartupInsteadOfDividingDisclosureAndExecution() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(EmptyScopeToolConfig.class)) {
            ToolRegistry registry = registryOn(context);

            assertThatThrownBy(registry::init)
                    .as("空 scope 在披露面=不披露、执行面=放行，是静默扩大授权面 ⇒ 必须启动失败")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("工具 scope 为空")
                    .hasMessageContaining("empty_scope_tool");
        }
    }

    @Test
    void riskChannelInvariantFailsStartup() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(DangerousFrontendToolConfig.class)) {
            ToolRegistry registry = registryOn(context);

            assertThatThrownBy(registry::init)
                    .as("DANGER+FRONTEND 会让后端等确认、前端等回灌 ⇒ 死卡，必须启动失败")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("风险/通道不变量")
                    .hasMessageContaining("dangerous_frontend_tool");
        }
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(BackendWriteToolConfig.class)) {
            ToolRegistry registry = registryOn(context);

            assertThatThrownBy(registry::init)
                    .as("WRITE+BACKEND 会被 kindOf 判成直执行 ⇒ 可逆写绕过确认门，必须启动失败")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("风险/通道不变量")
                    .hasMessageContaining("backend_write_tool");
        }
    }

    private static ToolRegistry registryOn(AnnotationConfigApplicationContext context) {
        ToolRegistry registry = new ToolRegistry();
        ReflectionTestUtils.setField(registry, "applicationContext", context);
        return registry;
    }

    /** 惰性注册：刷新时不会创建，失败只在 {@code ToolRegistry#init} 的 getBean 里发生。 */
    @Configuration(proxyBeanMethods = false)
    static class FailingToolConfig {

        @Bean
        @Lazy
        FailingTool failingTool() {
            throw new IllegalStateException("模拟构造期环：failingTool 依赖尚未就绪的 bean");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoFailingToolConfig {

        @Bean
        @Lazy
        FirstFailingTool firstFailingTool() {
            throw new IllegalStateException("模拟构造期环：first");
        }

        @Bean
        @Lazy
        SecondFailingTool secondFailingTool() {
            throw new IllegalStateException("模拟构造期环：second");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonToolFailureConfig {

        @Bean
        @Lazy
        PlainClient plainClient() {
            throw new IllegalStateException("非工具 bean 的构造失败（扫描期噪音）");
        }
    }

    static class FailingTool {

        @Tool(name = "failing_tool", description = "受控替身：创建即失败的工具 bean")
        public String failing(@ToolParam(description = "占位参数") String value) {
            return value;
        }
    }

    static class FirstFailingTool {

        @Tool(name = "first_failing_tool", description = "受控替身：第一个创建失败的工具 bean")
        public String first(@ToolParam(description = "占位参数") String value) {
            return value;
        }
    }

    static class SecondFailingTool {

        @Tool(name = "second_failing_tool", description = "受控替身：第二个创建失败的工具 bean")
        public String second(@ToolParam(description = "占位参数") String value) {
            return value;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyScopeToolConfig {

        @Bean
        EmptyScopeTool emptyScopeTool() {
            return new EmptyScopeTool();
        }
    }

    static class EmptyScopeTool {

        @Tool(name = "empty_scope_tool", description = "受控替身：显式空 scope")
        @ToolScope({})
        public String empty(@ToolParam(description = "占位参数") String value) {
            return value;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DangerousFrontendToolConfig {

        @Bean
        DangerousFrontendTool dangerousFrontendTool() {
            return new DangerousFrontendTool();
        }
    }

    static class DangerousFrontendTool {

        @Tool(name = "dangerous_frontend_tool", description = "受控替身：DANGER+FRONTEND 的非法组合")
        @ToolScope({ "*" })
        @ToolRisk(ToolMeta.RiskLevel.DANGER)
        @ToolChannel(ToolMeta.Channel.FRONTEND)
        public String run(@ToolParam(description = "占位参数") String value) {
            return value;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BackendWriteToolConfig {

        @Bean
        BackendWriteTool backendWriteTool() {
            return new BackendWriteTool();
        }
    }

    static class BackendWriteTool {

        @Tool(name = "backend_write_tool", description = "受控替身：WRITE+BACKEND 的非法组合")
        @ToolScope({ "*" })
        @ToolRisk(ToolMeta.RiskLevel.WRITE)
        @ToolChannel(ToolMeta.Channel.BACKEND)
        public String run(@ToolParam(description = "占位参数") String value) {
            return value;
        }
    }

    static class PlainClient {

        public String call() {
            return "no @Tool here";
        }
    }
}
