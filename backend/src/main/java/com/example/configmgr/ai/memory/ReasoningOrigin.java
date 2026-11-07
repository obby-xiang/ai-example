package com.example.configmgr.ai.memory;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型生成的 assistant 消息的<b>中性来源标记</b>（跨供应方推理元数据隔离批）。
 *
 * <h2>问题</h2>
 * 推理元数据（{@code reasoningContent} 以及厂商私有的 {@code encrypted_content} /
 * {@code signature} / {@code thinking} 等）是<b>产出它的供应方</b>的协议资产。历史一旦跨供应方复用
 * （用户换了 provider 继续同一会话），把 A 的推理字段原样塞进 B 的请求就是"投毒"：
 * 轻则 400，重则让 B 接受一段不是它产生的加密思考。
 *
 * <h2>本类的口径</h2>
 * <ul>
 * <li><b>只标不洗</b>：来源以两个 additive、无密钥的 metadata 键随消息走
 * （{@value #KEY_PROVIDER_ID} / {@value #KEY_MODEL_ID}）。<b>存储历史一个字都不删</b> ——
 * 隔离只发生在请求构造层（{@code ReasoningAwareOpenAiChatModel#createRequest}）；</li>
 * <li><b>供应方级判定</b>：只比 {@code providerId}（DB 供应方主键；env 合成行为 {@code null}）。
 * {@code modelId} 只作取证，不参与判定 —— 协议层回放的只是<b>纯文本</b> reasoningContent
 * （不是加密/签名字段，那些从不进请求体），它是供应方的约定而非某个模型的密钥；
 * 把 model 纳入判定会把"同供应方换模型"的历史误判成跨供应方，反而触发上游 400；</li>
 * <li><b>不假装知道来源</b>：没有标记的消息就是"未知" —— {@link #replayAllowed} 对未知来源
 * <b>不回放</b>（fail-closed），并且 {@link #stamp} 在身份未知时<b>不写任何键</b>；</li>
 * <li><b>只加不改</b>：已有来源的消息不会被后来者覆盖（provenance 是事实，不是当前值）。</li>
 * </ul>
 *
 * <p><b>范围</b>：本类不判断 {@code supportsReasoning} 之类的展示开关 —— 那是界面能力位，
 * 不是"这段元数据由谁产出"的事实，用它做隔离会在未知供应商上静默失效。
 *
 * @param providerId 产出该消息的供应方主键（{@code null} = env 合成行，部署内唯一）
 * @param modelId 产出该消息的模型名（取证用；同供应方换模型仍回放）
 */
public record ReasoningOrigin(
        @JsonProperty("providerId") @JsonFormat(shape = JsonFormat.Shape.STRING) Long providerId,
        @JsonProperty("modelId") String modelId) {

    /** metadata 键：产出该消息的供应方主键（中性、无密钥）。 */
    public static final String KEY_PROVIDER_ID = "aiOriginProviderId";

    /** metadata 键：产出该消息的模型名（取证用，不参与回放判定）。 */
    public static final String KEY_MODEL_ID = "aiOriginModelId";

    /**
     * 请求层判定：该 assistant 消息的推理元数据能否回放到<b>当前</b>供应方的请求里。
     *
     * <h2>判定规则</h2>
     * <ol>
     * <li><b>无来源标记</b> ⇒ <b>不回放</b>（fail-closed）：来源未知时无法判定"是否跨供应方"，
     * 不把可能属于别家的推理元数据发出去；</li>
     * <li>有标记 ⇒ {@code Objects.equals(当前供应方, origin.providerId)}：
     * 两侧都是 {@code null}（env 合成行 ↔ env 合成行，部署内唯一）算同源；
     * 一侧 {@code null}（env ↔ DB 供应方）或两侧不等 ⇒ <b>不回放</b>。
     * <b>当前供应方未知</b>（未接线的装配/单测）落在这一支 ⇒ 对"有标记"的消息<b>不回放</b>
     * （fail-closed：不知道现在是谁，就不要把别人的推理发出去）。</li>
     * </ol>
     *
     * @param metadata        assistant 消息的 metadata（可 null）
     * @param currentProviderId 当前目标的供应方主键（null = 未知/未接线）
     */
    public static boolean replayAllowed(Map<String, Object> metadata, Long currentProviderId) {
        ReasoningOrigin origin = fromMetadata(metadata);
        if (origin == null) {
            return false;
        }
        return java.util.Objects.equals(currentProviderId, origin.providerId());
    }

    /** 从 metadata 读来源；两个键都不存在 = 来源未知（返回 {@code null}，调用方不得回放）。 */
    public static ReasoningOrigin fromMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object provider = metadata.get(KEY_PROVIDER_ID);
        Object model = metadata.get(KEY_MODEL_ID);
        if (provider == null && model == null) {
            return null;
        }
        Long providerId = providerIdOf(provider);
        return new ReasoningOrigin(providerId, model instanceof String text ? text : null);
    }

    private static Long providerIdOf(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text) {
            try {
                return Long.valueOf(text);
            }
            catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /**
     * 给模型生成的 assistant 消息打来源标记（<b>只加不改</b>，未知身份不加）。
     *
     * <p>返回值可能是同一实例：已有来源、或身份两值皆空时原样返回 —— 不伪造"当前供应方"，
     * 也不因为重复调用而改变 provenance。其余 metadata（推理/未知/业务键）逐键保留，
     * 不解析、不截断。
     *
     * @param message    上游/落定路径产生的 assistant 消息
     * @param providerId 快照冻结的供应方主键（可 null = env 合成行）
     * @param modelId    快照冻结的模型名（可 null）
     */
    public static AssistantMessage stamp(AssistantMessage message, Long providerId, String modelId) {
        if (message == null) {
            return null;
        }
        Map<String, Object> metadata = ObjectUtils.defaultIfNull(message.getMetadata(), Map.of());
        if (metadata.containsKey(KEY_PROVIDER_ID) || metadata.containsKey(KEY_MODEL_ID)) {
            return message;
        }
        if (providerId == null && modelId == null) {
            return message;
        }
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        if (providerId != null) {
            merged.put(KEY_PROVIDER_ID, providerId.toString());
        }
        if (modelId != null) {
            merged.put(KEY_MODEL_ID, modelId);
        }
        AssistantMessage.Builder builder = AssistantMessage.builder()
            .content(message.getText())
            .properties(merged);
        if (message.hasToolCalls()) {
            builder.toolCalls(message.getToolCalls());
        }
        return builder.build();
    }

}
