package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.form.GenerativeFormGuard;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiToolsController;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DC-15 用例⑤：{@code generative_form} 的<b>披露上下文</b>与元数据（FR-5.2 防线①）。
 *
 * <p>披露口径（决策与理由见 {@code AiTools#generativeForm}）：
 * <ul>
 * <li>场景①（收集筛选条件）的现场 = <b>任务中心</b>（{@code page:tasks}）与<b>导出向导各步骤</b>
 * （{@code task:EXPORT}，含「查询条件」步 —— 条件不必跳转向导就能在对话里填）；</li>
 * <li>场景②（询问澄清）与任务类型无关 ⇒ {@code task:*}（任意任务上下文，与 {@code check_job_status} 同标签）；</li>
 * <li>刻意不用 {@code *}：{@code definitions/data} 等"只看不改"的页面不披露
 * （与 DC-14 渐进披露的"最小必要工具集"同向），空上下文同样不披露。</li>
 * </ul>
 *
 * <p>三道防线同源：这里同时断言"披露集"（防线①）与 {@code matchesContext}（防线③执行兜底）
 * 对同一工具给出<b>一致</b>的判定 —— 与 {@code ToolRegistryDisclosureTest#scopeGuardMatchesDisclosedSet}
 * 同一方法论。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:gfa-form-disclosure;DB_CLOSE_DELAY=-1"
})
class GenerativeFormDisclosureTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AiTools aiTools;

    @Autowired
    private GenerativeFormGuard guard;

    @Test
    void toolIsRegisteredAsReadOnlyFrontendChannelTool() {
        assertThat(toolRegistry.getMeta(GenerativeFormRules.TOOL_NAME)).isNotNull();
        assertThat(toolRegistry.channelOf(GenerativeFormRules.TOOL_NAME))
                .as("副作用（渲染表单/收集值）在前端，后端只挂起与回灌")
                .isEqualTo(ToolMeta.Channel.FRONTEND);
        assertThat(toolRegistry.riskOf(GenerativeFormRules.TOOL_NAME))
                .as("表单本身不产生后端副作用；用户的提交动作本身就是人的参与（不进确认门）")
                .isEqualTo(ToolMeta.RiskLevel.READ);
    }

    @Test
    void disclosedAtTaskCenterAndInsideTaskWizards() {
        // 场景①：任务中心（AI 面板的主现场）
        assertThat(namesFor(AiContext.of("tasks", null, null, null, null))).contains(GenerativeFormRules.TOOL_NAME);
        // 场景①：导出向导（含「查询条件」步 —— 条件填写不必跳转向导）
        for (String step : List.of("SELECT_DEFS", "QUERY_COND", "EXPORT")) {
            assertThat(namesFor(AiContext.of("export", "EXPORT", step, 1L, null)))
                    .as("EXPORT/%s 应披露 generative_form", step)
                    .contains(GenerativeFormRules.TOOL_NAME);
        }
        // 场景②：任意任务上下文（导入向导的各步骤同样可能信息不足）
        for (String step : List.of("SELECT_DEFS", "UPLOAD", "PRECHECK", "IMPORT", "PUBLISH")) {
            assertThat(namesFor(AiContext.of("import", "IMPORT", step, 1L, null)))
                    .as("IMPORT/%s 应披露 generative_form", step)
                    .contains(GenerativeFormRules.TOOL_NAME);
        }
    }

    @Test
    void notDisclosedOnBrowseOnlyPagesOrWithoutContext() {
        for (String page : List.of("definitions", "data")) {
            assertThat(namesFor(AiContext.of(page, null, null, null, null)))
                    .as("页面 %s 只在看/改配置，没有要收集的条件或澄清动作", page)
                    .doesNotContain(GenerativeFormRules.TOOL_NAME);
        }
        assertThat(namesFor(AiContext.empty())).doesNotContain(GenerativeFormRules.TOOL_NAME);
    }

    @Test
    void disclosureAndScopeGuardAgreeOnEveryContext() {
        List<AiContext> contexts = List.of(
                AiContext.empty(),
                AiContext.of("tasks", null, null, null, null),
                AiContext.of("definitions", null, null, null, null),
                AiContext.of("data", null, null, null, null),
                AiContext.of("export", "EXPORT", "SELECT_DEFS", 1L, null),
                AiContext.of("export", "EXPORT", "QUERY_COND", 1L, null),
                AiContext.of("export", "EXPORT", "EXPORT", 1L, null),
                AiContext.of("import", "IMPORT", "UPLOAD", 1L, null),
                AiContext.of("import", "IMPORT", "PRECHECK", 1L, null),
                AiContext.of("import", "IMPORT", "IMPORT", 1L, null),
                AiContext.of("import", "IMPORT", "PUBLISH", 1L, null));
        for (AiContext context : contexts) {
            boolean disclosed = namesFor(context).contains(GenerativeFormRules.TOOL_NAME);
            assertThat(toolRegistry.matchesContext(GenerativeFormRules.TOOL_NAME, context))
                    .as("上下文 %s：防线③判定必须与防线①披露集一致", context.getContextKey())
                    .isEqualTo(disclosed);
        }
    }

    @Test
    void toolBodyIsSentinelStub() {
        AiTools.FormSpec form = new AiTools.FormSpec("FILTER", "筛选条件",
                List.of(new AiTools.FormField("keyword", "关键字", "text", true, "CNY", null, "如 CNY")));
        assertThat(aiTools.generativeForm(form))
                .as("后端桩体永不执行（副作用在前端）")
                .isEqualTo(AiTools.FRONTEND_STUB);
    }

    @Test
    void guardBeanIsWiredForTheTool() {
        assertThat(guard.supports(GenerativeFormRules.TOOL_NAME)).isTrue();
    }

    /** 与 TC15 同一取证面：只读端点 {@code GET /api/ai/tools} 必须与披露集同源。 */
    @Test
    void readOnlyEndpointShowsTheSameDisclosure() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AiToolsController(this.toolRegistry)).build();
        mockMvc.perform(get("/api/ai/tools").param("page", "tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.context").value("page:tasks"))
                .andExpect(jsonPath("$.data.toolNames").value(hasItem(GenerativeFormRules.TOOL_NAME)));
        mockMvc.perform(get("/api/ai/tools").param("page", "data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolNames").value(not(hasItem(GenerativeFormRules.TOOL_NAME))));
        mockMvc.perform(get("/api/ai/tools").param("taskType", "EXPORT").param("step", "QUERY_COND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.context").value("task:EXPORT/QUERY_COND"))
                .andExpect(jsonPath("$.data.toolNames").value(hasItem(GenerativeFormRules.TOOL_NAME)));
    }

    private List<String> namesFor(AiContext context) {
        return this.toolRegistry.forContext(context).stream()
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .toList();
    }

}
