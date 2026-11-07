package com.example.configmgr.ai.tool;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;

/**
 * 单个工具的**可选覆盖项**（来自 YAML / 未来的 DB 版本表）。
 *
 * <p>
 * 契约（本批裁决 D-3）：**注解是缺省，YAML 是可选覆盖**。因此除 {@code toolKey} 外全部可空：
 * <ul>
 * <li>{@code description} 为空 ⇒ 用 {@code @Tool(description)}（模型侧文案一字不动）；</li>
 * <li>{@code inputSchema} 为空 ⇒ 用反射生成的 schema，且**不做对账**（没有覆盖就没有漂移面）；</li>
 * <li>{@code title}/{@code titleKey} 为空 ⇒ 展示名回落工具名（不再"取 description 前 20 字"）；</li>
 * </ul>
 *
 * <p>
 * {@code lang} 是**模型侧**语言标签（zh / en），与用户 locale 无关（裁决 A：模型侧单语，
 * 不随界面语言切换）。
 */
public record ToolDefinitionOverride(
        @JsonProperty("toolKey") String toolKey,
        @JsonProperty("modelName") String modelName,
        @JsonProperty("description") String description,
        @JsonProperty("inputSchema") String inputSchema,
        @JsonProperty("title") String title,
        @JsonProperty("titleKey") String titleKey,
        @JsonProperty("lang") String lang,
        @JsonProperty("status") String status) {

    public boolean hasDescription() {
        return StringUtils.isNotBlank(description);
    }

    public boolean hasSchema() {
        return StringUtils.isNotBlank(inputSchema);
    }

    /** 是否已发布（未写 status 视为已发布：YAML 阶段没有草稿工作流）。 */
    @JsonProperty("published")
    public boolean isPublished() {
        return StringUtils.isBlank(status) || "published".equalsIgnoreCase(status);
    }

    @JsonIgnore
    public boolean published() {
        return isPublished();
    }

    /** 模型侧 name：缺省等于 toolKey（新工具不必写两遍）。 */
    public String effectiveModelName() {
        return StringUtils.defaultIfBlank(modelName, toolKey);
    }

    /** UI 用 i18n key：缺省按约定推导 `tool.<toolKey>.title`。 */
    public String effectiveTitleKey() {
        return StringUtils.defaultIfBlank(titleKey, "tool." + toolKey + ".title");
    }
}
