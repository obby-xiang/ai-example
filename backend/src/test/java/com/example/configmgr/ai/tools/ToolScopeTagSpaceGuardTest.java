package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.tool.ToolScope;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标签空间守卫（issue #4 / 方案 B 的配套用例）。
 *
 * <p>{@code page:*} 的合法值域是<b>路由页 id 镜像</b>（{@code tasks / export / import / definitions / data}），
 * 其中 {@code page:export} / {@code page:import} 是<b>保留粒度</b>：当前无任何工具使用它们，
 * 向导页的披露权威是 {@code task:*}（见 {@link ToolScope} 的标签空间说明）。
 *
 * <p>为什么要钉这一条：如果将来有人把某个向导专用工具挂到 {@code page:export} 上，
 * {@code page} 只是 {@code pageId} 的镜像、且 {@code enterPage} 对任何未显式传 {@code page} 的页面
 * 都会回退成 {@code pageId}，于是"某页面能看到它、某页面看不到它"会静默取决于调用点是否记得传参 ——
 * 这正是 issue #4 想避免的形态。守卫在此处<b>强制作者显式裁决</b>：新增该标签即用例红。
 *
 * <p>扫描口径 = 全仓主/测字节码（{@code com/example/configmgr/**}），用 ASM 级
 * {@link MetadataReader} 读注解，<b>不加载类</b>（不触发静态初始化，也不受缺依赖影响）。
 */
class ToolScopeTagSpaceGuardTest {

    /** 全仓类的字节码位置（主代码 target/classes 与测试 target/test-classes 都会命中）。 */
    private static final String CLASS_PATTERN = "classpath*:com/example/configmgr/**/*.class";

    /**
     * 保留粒度：{@code page} 与 {@code pageId} 同值时向导页会产出这两个标签，
     * 但它们<b>不得被任何工具声明为 scope</b>（否则就是"页面级披露"的隐式引入）。
     */
    private static final List<String> RESERVED_PAGE_TAGS = List.of("page:export", "page:import");

    /** 扫描下限：防止"扫描自身坏掉"被当成"没有违规"（当前 18 个 @ToolScope 声明）。 */
    private static final int MIN_DECLARATIONS = 15;

    @Test
    void noToolScopeUsesReservedWizardPageTags() {
        Map<String, List<String>> declarations = scanToolScopes();

        List<String> offenders = new ArrayList<>();
        declarations.forEach((method, patterns) -> {
            for (String pattern : patterns) {
                if (RESERVED_PAGE_TAGS.contains(pattern)) {
                    offenders.add(method + " -> " + pattern);
                }
            }
        });

        assertThat(offenders)
                .as("page:export / page:import 是保留粒度（向导披露由 task:* 承担）；"
                        + "确需页面级细粒度披露时请先裁决标签空间语义，再改本条守卫")
                .isEmpty();
    }

    @Test
    void scanCoversWholeSourceTree() {
        Map<String, List<String>> declarations = scanToolScopes();
        List<String> allPatterns = declarations.values().stream().flatMap(List::stream).distinct().toList();

        // 覆盖自检：扫到的声明数够多，且确实读到了已知标签（否则守卫形同虚设）。
        // 注：全仓 page:* 实际只用到 page:tasks —— 真实前端上下文里 tasks/definitions/data 三页
        // 也只有任务中心这一档被工具使用（守卫只保证"读到了东西 + 没有保留粒度"）。
        assertThat(declarations).as("全仓 @ToolScope 声明数").hasSizeGreaterThanOrEqualTo(MIN_DECLARATIONS);
        assertThat(allPatterns).contains("page:tasks", "task:*", "*");
        // 保留粒度未被使用（与上一条同源，此处从"标签集合"角度再钉一次）
        assertThat(allPatterns).doesNotContainAnyElementsOf(RESERVED_PAGE_TAGS);
    }

    /**
     * 全仓 {@code @ToolScope} 声明：{@code 类名#方法名 -> 标签数组}。
     */
    private Map<String, List<String>> scanToolScopes() {
        Map<String, List<String>> found = new LinkedHashMap<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readerFactory = new CachingMetadataReaderFactory(resolver);
        Resource[] resources;
        try {
            resources = resolver.getResources(CLASS_PATTERN);
        }
        catch (Exception e) {
            throw new IllegalStateException("扫描字节码失败：" + CLASS_PATTERN, e);
        }
        for (Resource resource : resources) {
            String className = classNameOf(resource);
            if (className == null) {
                continue;
            }
            MetadataReader reader;
            try {
                reader = readerFactory.getMetadataReader(resource);
            }
            catch (Exception e) {
                // 读不动的字节码（非本包类 / 版本不符）跳过，不影响"能读到的声明"这条断言
                continue;
            }
            for (MethodMetadata method : reader.getAnnotationMetadata().getAnnotatedMethods(ToolScope.class.getName())) {
                Map<String, Object> attributes = method.getAnnotationAttributes(ToolScope.class.getName());
                if (attributes == null) {
                    continue;
                }
                Object value = attributes.get("value");
                List<String> patterns = value instanceof String[] array ? List.of(array) : List.of();
                found.put(className + "#" + method.getMethodName(), patterns);
            }
        }
        return found;
    }

    /** {@code file:/…/com/example/configmgr/ai/tools/AiTools.class} → {@code com.example.configmgr.ai.tools.AiTools}。 */
    private static String classNameOf(Resource resource) {
        String path;
        try {
            path = resource.getURL().getPath();
        }
        catch (Exception e) {
            return null;
        }
        String marker = "/com/example/configmgr/";
        int index = path.lastIndexOf(marker);
        if (index < 0 || !path.endsWith(".class")) {
            return null;
        }
        return (path.substring(index + 1, path.length() - ".class".length())).replace('/', '.');
    }
}
