package com.example.configmgr.ai.slot;

import com.example.configmgr.ai.observe.LogSampling;
import com.example.configmgr.ai.slot.TaskSlots.ConditionSlot;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 槽位解析器（小型、可测试，不引入新框架）。
 *
 * <p><b>自然语言信任边界（2026-10-30 确定性工作流）</b>：任务推进与条件放行不解析用户原话，
 * 只认任务条目、工作区选择和结构化回灌。唯一例外是
 * {@link #completeCreateTaskArguments}：模型已发起创建后，才用 {@link TaskSlotParser}
 * 补齐留空且能完整验证的创建槽位；它不会自行创建任务，也不绕过预检、确认或条件草案链。
 *
 * <p><b>与 {@code ContextBuilder} 同一套两态</b>：页面上报的 {@code selectedDefs} 非空时它是权威值；
 * 显式空列表（任务条目也为空、例如刚建好尚未落条目的新任务）时以本轮结构化
 * {@code create_task(defCodes=…)} 的候选为准；页面<b>从未同步</b>（无该键 / 不在拥有选中集的向导页上）
 * 则回落任务条目。候选是用户原话经模型工具调用产出的结构化事实，宿主不再解析文本。
 *
 * <p>解析结果写入 {@code AiContext.extra[taskSlots]}（{@link TaskSlots#EXTRA_KEY}），
 * 随 run 快照传递，并由 {@link ContextBuilder} 展示给模型。
 */
@Slf4j
@Component
public class TaskSlotResolver {

    /**
     * 结构化 create_task 参数里的候选配置项（服务端写入；不落任务条目）。
     */
    public static final String CREATE_TASK_DEF_CODES_KEY = "createTaskDefCodes";

    /**
     * create_task 候选（defCodes/conditions）所属的任务号：候选只对创建它的任务有效。
     */
    public static final String CREATE_TASK_TASK_ID_KEY = "createTaskTaskId";

    /**
     * 页面选择三态：presence=false 表示未同步，defCodes 始终非空。
     */
    public record PageSelection(boolean isPresent, List<String> defCodes) {

        public PageSelection {
            defCodes = defCodes == null ? List.of() : List.copyOf(defCodes);
        }

        private static PageSelection absent() {
            return new PageSelection(false, List.of());
        }

        private static PageSelection present(List<String> defCodes) {
            return new PageSelection(true, defCodes);
        }
    }

    private final DefinitionCatalog catalog;

    /**
     * 权威任务条目来源（可为 null：纯解析用例不读库）。
     */
    private final TaskService taskService;

    private final ObjectMapper objectMapper;

    public TaskSlotResolver(DefinitionCatalog catalog) {
        this(catalog, null, new ObjectMapper());
    }

    @Autowired
    public TaskSlotResolver(DefinitionCatalog catalog, TaskService taskService, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.taskService = taskService;
        this.objectMapper = ObjectUtils.getIfNull(objectMapper, ObjectMapper::new);
    }

    /**
     * 解析本轮槽位。
     *
     * @param context             请求上下文（taskType/extra 是权威值来源）
     * @param currentMessage      本轮用户原文
     * @param historyUserMessages 历史用户原文（按时间顺序；只看用户说过的话）
     */
    public TaskSlots resolve(AiContext context, String currentMessage, List<String> historyUserMessages) {
        AiContext ctx = context == null ? AiContext.empty() : context;
        Task task = task(ctx);
        // 自然语言不经这里：不解析 currentMessage/historyUserMessages（保留签名兼容）。
        // 可信事实只有任务条目/工作区页面结构化选择；条件事实来自任务 conditionJson。
        PageSelection pageSelection = pageSelection(ctx);
        List<String> pageDefCodes = pageSelection.defCodes();
        List<String> taskDefCodes = taskDefCodes(task);
        List<String> createTaskDefCodes = createTaskDefCodes(ctx);
        // 权威顺序：页面非空勾选 > 任务已存条目（页面未同步）> create_task 候选。
        // 页面同步了空集合 = 用户清空，仍压过任务条目；只有任务还没有任何条目
        // （刚建好、尚未 select_definitions）时，空页面只表示"向导里还没勾"，
        // 以本轮结构化 create_task(defCodes=…) 候选为准 —— 那是用户原话经模型工具调用
        // 给出的已知事实，不是宿主编造。
        List<String> authoritativeDefCodes;
        if (pageSelection.isPresent() && !pageDefCodes.isEmpty()) {
            authoritativeDefCodes = pageDefCodes;
        } else if (!taskDefCodes.isEmpty()) {
            authoritativeDefCodes = pageSelection.isPresent() ? List.of() : taskDefCodes;
        } else {
            authoritativeDefCodes = createTaskDefCodes;
        }
        ConditionAssessment.Assessment conditionAssessment = ConditionAssessment.of(task);
        List<ConditionSlot> conditions = eqConditions(conditionAssessment);
        List<String> defCodes = authoritativeDefCodes;
        if (defCodes.isEmpty()) {
            conditions = List.of();
        } else {
            conditions = conditions.stream().filter(c -> defCodes.contains(c.defCode())).distinct().toList();
        }
        String taskType = StringUtils.defaultIfBlank(ctx.getTaskType(), null);
        boolean atExport = task != null && "EXPORT".equalsIgnoreCase(trimStep(task.getCurrentStep()));
        return withConditionState(taskType, defCodes, conditions, conditionAssessment, task, atExport);
    }

    /**
     * 首轮/续跑交棒前的槽位补齐：只读任务/工作区权威值，<b>不解析自由文本</b>
     * （currentMessage/history 保留形参兼容；自然语言只由模型工具调用输出结构化意图）。
     */
    public AiContext enrich(AiContext context, String currentMessage, List<Message> history) {
        AiContext source = context == null ? AiContext.empty() : context;
        TaskSlots slots = resolve(source, null, List.of());
        Map<String, Object> extra = source.getExtra() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(source.getExtra());
        if (slots.isEmpty()) {
            extra.remove(TaskSlots.EXTRA_KEY);
        } else {
            extra.put(TaskSlots.EXTRA_KEY, slots.toMap());
        }
        return AiContext.of(source.getPage(), source.getTaskType(), source.getStep(), source.getTaskId(),
                source.getTaskStatus(), extra);
    }

    /**
     * 模型已经发起 create_task 后，只补它留空且用户原话能完整验证的创建槽位。
     * 非空模型参数优先，openWizard=false 仅保留给用户明确限定创建后停止的场景；
     * 候选仍须经过创建预检，不能替代确认或业务库条件写入。
     */
    public String completeCreateTaskArguments(String argumentsJson, String currentMessage) {
        if (StringUtils.isBlank(argumentsJson)) {
            return argumentsJson;
        }
        try {
            if (!(this.objectMapper.readTree(argumentsJson) instanceof ObjectNode arguments)) {
                return argumentsJson;
            }
            normalizeOpenWizard(arguments, currentMessage);
            if (StringUtils.isNotBlank(currentMessage)) {
                List<String> targetCodes = targetCodes(arguments);
                TaskSlotParser.Intent intent = TaskSlotParser.parse(currentMessage, definitions(), targetCodes);
                applyTaskTypeAndSelection(arguments, intent, targetCodes);
                applyVerifiedConditions(arguments, intent, targetCodes);
            }
            return this.objectMapper.writeValueAsString(arguments);
        } catch (Exception ex) {
            if (LogSampling.canAcquire("create-task-slot-completion")) {
                log.warn("could not complete create_task slots; original arguments kept", ex);
            }
            return argumentsJson;
        }
    }

    private static void normalizeOpenWizard(ObjectNode arguments, String currentMessage) {
        JsonNode openWizard = arguments.get("openWizard");
        if (openWizard != null && openWizard.isBoolean() && !openWizard.booleanValue()
                && !isCreateOnly(currentMessage)) {
            arguments.remove("openWizard");
        }
    }

    private static boolean isCreateOnly(String currentMessage) {
        return StringUtils.containsAny(currentMessage, "只创建", "仅创建", "不打开向导", "不继续配置", "不启动后续流程");
    }

    private static List<String> targetCodes(ObjectNode arguments) {
        List<String> targetCodes = new ArrayList<>();
        if (arguments.get("defCodes") instanceof ArrayNode codes) {
            for (JsonNode code : codes) {
                if (code.isTextual() && StringUtils.isNotBlank(code.asText())) {
                    targetCodes.add(code.asText());
                }
            }
        }
        return targetCodes;
    }

    private static void applyTaskTypeAndSelection(ObjectNode arguments, TaskSlotParser.Intent intent,
                                                  List<String> targetCodes) {
        if (!arguments.hasNonNull("type") && intent.taskType() != null) {
            arguments.put("type", intent.taskType());
        }
        if (targetCodes.isEmpty() && intent.selection() == TaskSlotParser.SlotState.DETERMINED) {
            ArrayNode codes = arguments.putArray("defCodes");
            intent.defCodes().forEach(codes::add);
            targetCodes.addAll(intent.defCodes());
        }
    }

    private void applyVerifiedConditions(ObjectNode arguments, TaskSlotParser.Intent intent,
                                         List<String> targetCodes) {
        JsonNode existingConditions = arguments.get("conditions");
        boolean conditionsEmpty = existingConditions == null || existingConditions.isNull()
                || existingConditions.isObject() && existingConditions.isEmpty();
        if (!conditionsEmpty || !"EXPORT".equalsIgnoreCase(arguments.path("type").asText())
                || intent.condition() != TaskSlotParser.SlotState.DETERMINED || targetCodes.size() != 1) {
            return;
        }
        List<ConditionSlot> slots = intent.conditions().stream().filter(ConditionSlot::isComplete).distinct().toList();
        if (!isVerified(slots, intent, targetCodes)) {
            return;
        }
        ObjectNode conditions = this.objectMapper.createObjectNode();
        ArrayNode fields = conditions.putArray("fields");
        for (ConditionSlot slot : slots) {
            ObjectNode field = fields.addObject();
            field.put("fieldCode", slot.fieldCode());
            field.put("operator", "EQ");
            field.put("value", slot.value());
        }
        arguments.set("conditions", conditions);
    }

    private static boolean isVerified(List<ConditionSlot> slots, TaskSlotParser.Intent intent,
                                      List<String> targetCodes) {
        if (slots.isEmpty()) {
            return false;
        }
        for (ConditionSlot slot : slots) {
            if (!intent.verifiedConditionDefCodes().contains(slot.defCode()) || !targetCodes.contains(slot.defCode())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 逐项条件三态（禁止"任一配置项有条件就整体放行"）：已设置/明确不设不是缺口，
     * 未决定/无法解析才是缺口；模型已知但未落库的条件也算"已有事实"，放行判定不得再弹表。
     */
    private static TaskSlots withConditionState(String taskType, List<String> defCodes,
                                                List<ConditionSlot> conditions, ConditionAssessment.Assessment assessment, Task task,
                                                boolean atExport) {
        if (defCodes.isEmpty()) {
            return new TaskSlots(taskType, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        List<String> conditionDefCodes = new ArrayList<>();
        List<String> undecidedDefCodes = new ArrayList<>();
        List<String> conditionTexts = new ArrayList<>();
        for (String defCode : defCodes) {
            boolean userKnown = conditions.stream().anyMatch(slot -> defCode.equals(slot.defCode()));
            ConditionAssessment.State state = assessment.stateOf(defCode);
            if (userKnown || state == ConditionAssessment.State.SET) {
                conditionDefCodes.add(defCode);
            } else if (state == ConditionAssessment.State.EXPLICIT_UNSET
                    || (atExport && state == ConditionAssessment.State.UNDECIDED && isConditionBlank(task, defCode))) {
                // 明确不设：已有答案，不再收集。
                // N-2：权威步骤已是 EXPORT 的 null/空白 = 用户留空（与预检同一口径）；
                // 坏结构（unparseable）不在此列，仍是未决定。
                continue;
            } else {
                undecidedDefCodes.add(defCode);
            }
        }
        for (ConditionAssessment.ConditionFact fact : assessment.facts()) {
            if (defCodes.contains(fact.defCode()) && !conditionTexts.contains(fact.render())) {
                conditionTexts.add(fact.render());
            }
        }
        for (ConditionSlot slot : conditions) {
            String rendered = slot.fieldCode() + " = " + slot.value();
            if (!conditionTexts.contains(rendered)) {
                conditionTexts.add(rendered);
            }
        }
        // 文本可信链已删除：verifiedConditionDefCodes 仅为兼容字段，恒为空（不参与生产判断）。
        return new TaskSlots(taskType, defCodes, conditions, conditionDefCodes, undecidedDefCodes, conditionTexts,
                List.of());
    }

    /**
     * 该配置项的任务条件 JSON 是否为空/空白（坏 JSON 不算；只有 null/空串）。
     */
    private static boolean isConditionBlank(Task task, String defCode) {
        if (task == null || task.getItems() == null || defCode == null) {
            return false;
        }
        for (TaskItem item : task.getItems()) {
            if (item != null && defCode.equals(item.getDefCode())) {
                return StringUtils.isBlank(item.getConditionJson());
            }
        }
        return false;
    }

    private static String trimStep(String step) {
        return StringUtils.trimToEmpty(step);
    }

    // ── 权威值（任务/工作区） ────────────────────────────────────────────────

    private List<SlotDefinition> definitions() {
        try {
            List<SlotDefinition> definitions = this.catalog == null ? List.of() : this.catalog.list();
            return definitions == null ? List.of() : definitions;
        } catch (RuntimeException ex) {
            // 目录读失败 = 没有可识别项：槽位保持缺失（表单照常可用），不影响本轮
            if (LogSampling.canAcquire("task-slot-definition-catalog")) {
                log.warn("could not read definition catalog; slots treated as missing this turn", ex);
            }
            return List.of();
        }
    }

    /**
     * 页面实时勾选（三态，与 {@code ContextBuilder#pageSelectedDefs} 同一口径）。
     *
     * <p><b>为什么对外可见</b>：续跑交棒前的槽位重算（{@code ResumeService}）要用"页面有没有给出
     * 权威勾选"来决定能不能用用户原话补初始槽位 —— 这个三态判据只能有一份，否则"页面读法"两处各写
     * 一遍必然漂移（一边当成权威空、另一边当成未同步）。
     *
     * @return presence=false = 页面从未同步（不带 {@code selectedDefs} 键、不在拥有选中集的向导页上，
     * 或列表为空但任务详情还没应用 —— 未水合的 {@code []} 只表示"不知道"）；
     * presence=true 且空列表 = 页面同步了空集合且详情已应用（用户取消了全部勾选，这是权威空值）
     */
    public PageSelection pageSelection(AiContext context) {
        Map<String, Object> extra = context.getExtra();
        if (extra == null) {
            return PageSelection.absent();
        }
        // 类型口径与 ContextBuilder#pageSelectedDefs 统一为 String.valueOf（此前 instanceof String
        // 会让同一份上下文在两侧得出不同结论）。
        Object pageId = extra.get("pageId");
        if (pageId == null || !ContextBuilder.SELECTION_PAGE_IDS.contains(String.valueOf(pageId))) {
            return PageSelection.absent();
        }
        if (!(extra.get("selectedDefs") instanceof List<?> selected)) {
            return PageSelection.absent();
        }
        List<String> codes = new ArrayList<>();
        for (Object element : selected) {
            if (element != null && StringUtils.isNotBlank(String.valueOf(element))) {
                codes.add(String.valueOf(element));
            }
        }
        if (codes.isEmpty() && !Boolean.TRUE.equals(extra.get(ContextBuilder.SELECTION_SYNCED_KEY))) {
            // 未水合 / 旧客户端：[] 不代表"用户清空"，按未同步处理（允许回落任务条目/历史）
            return PageSelection.absent();
        }
        return PageSelection.present(codes);
    }

    /**
     * 旧列表投影不承载 presence；需要区分未同步与权威空的调用方必须使用 {@link #pageSelection}。
     */
    public List<String> pageSelectedDefCodes(AiContext context) {
        return pageSelection(context).defCodes();
    }

    /**
     * 本 run 结构化 create_task 的候选配置项（任务条目尚未落库时使用）。
     *
     * <p><b>只对创建它的任务有效</b>：候选与 {@link #CREATE_TASK_TASK_ID_KEY} 绑定；上下文任务号不匹配
     * （切到别的任务）时视为没有候选 —— 旧任务的配置不得压掉新任务的选择表。
     */
    private static List<String> createTaskDefCodes(AiContext context) {
        if (context == null || context.getExtra() == null
                || !(context.getExtra().get(CREATE_TASK_DEF_CODES_KEY) instanceof List<?> raw)) {
            return List.of();
        }
        Long boundTaskId = longOf(context.getExtra().get(CREATE_TASK_TASK_ID_KEY));
        if (boundTaskId == null || !boundTaskId.equals(context.getTaskId())) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object element : raw) {
            if (element != null && StringUtils.isNotBlank(String.valueOf(element))
                    && !out.contains(String.valueOf(element))) {
                out.add(String.valueOf(element));
            }
        }
        return out.isEmpty() ? List.of() : out;
    }

    /**
     * 服务端 extra 里的任务号（数字或数字文本；其它形态 = 没有绑定）。
     */
    private static Long longOf(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 任务已存条目（页面未同步时的权威来源；没有任务 = 空）。
     */
    private List<String> taskDefCodes(Task task) {
        if (task == null || task.getItems() == null) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (TaskItem item : task.getItems()) {
            if (StringUtils.isNotBlank(item.getDefCode())) {
                codes.add(item.getDefCode());
            }
        }
        return codes.isEmpty() ? List.of() : codes;
    }

    private List<ConditionSlot> eqConditions(ConditionAssessment.Assessment assessment) {
        List<ConditionSlot> out = new ArrayList<>();
        for (ConditionAssessment.ConditionFact fact : assessment.facts()) {
            if (!"EQ".equalsIgnoreCase(fact.operator()) || fact.fieldCode() == null
                    || StringUtils.isBlank(fact.value())) {
                // 范围/IN/比较运算不进"EQ 槽位"这个最小模型；它们仍由 conditionDefCodes 记为已设置
                continue;
            }
            ConditionSlot slot = new ConditionSlot(fact.defCode(), fact.fieldCode(), fact.value());
            if (slot.isComplete() && !out.contains(slot)) {
                out.add(slot);
            }
        }
        return out.isEmpty() ? List.of() : out;
    }

    private Task task(AiContext context) {
        if (this.taskService == null || context.getTaskId() == null) {
            return null;
        }
        try {
            return this.taskService.findById(context.getTaskId());
        } catch (RuntimeException ex) {
            if (LogSampling.canAcquire("task-slot-read")) {
                log.warn("could not read task #{}; authoritative slots treated as missing", context.getTaskId(), ex);
            }
            return null;
        }
    }
}
