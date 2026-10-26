package com.example.configmgr.ai.history;

import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.history.AiChatHistoryService.AppendCommand;
import com.example.configmgr.common.AiStateUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 完整对话历史的<b>写入适配器</b>：把聊天链路的原始落定点接到 {@link AiChatHistoryService}。
 *
 * <h2>一条消息 = 一个稳定 messageId</h2>
 * <p>
 * 幂等键是 {@code (sessionId, runId, messageId)}。messageId 必须由内容/身份确定性派生，
 * 这样同一落定点在重复 converge、重启 resume、取消后再次收尾时都会命中同一行：
 * <ul>
 * <li>user：{@code user-<runId>}；</li>
 * <li>assistant(tool_calls)：{@code assistant-<runId>-tool-<排序后的首个 toolCallId>}；</li>
 * <li>tool 响应批次：{@code tool-<排序后的首个 toolCallId>}（与 SSE 的 {@code toolMessageId} 同形）；</li>
 * <li>最终 assistant：{@code assistant-<runId>}；停止半截若带 tool_calls 走
 * {@code assistant-<runId>-tool-<首个 toolCallId>}，否则也用 {@code assistant-<runId>}。</li>
 * </ul>
 * 工具轮的 assistant 与最终 assistant 用不同前缀，<b>不会撞 messageId</b>。
 *
 * <h2>只写用户可见的真实消息</h2>
 * <p>
 * {@link #recordRunTail} 从"最后一个真实 user"之后补齐 assistant/tool 消息：
 * 跳过 system、合成摘要（metadata {@code compactedFrom} / 摘要占位文本）、步数收尾 user。
 * 它不复制上一轮 memory 里的旧消息（那些在各自落定点已写），也不会重复写已存在的行。
 *
 * <h2>失败姿态</h2>
 * <p>
 * 普通历史写入失败只 WARN，不影响聊天/续跑主流程；下一次落定点或 converge 会按同一 messageId 重试。
 * <b>唯一例外</b>：会话销毁守卫读取失败（{@link AiStateUnavailableException}，fail-closed）
 * 不吞、原样上抛 —— 守卫状态不可知时不得把"没写"当成"写成功"，也不能让调用方误以为可继续复活历史。
 */
@Component
public class AiChatHistoryRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiChatHistoryRecorder.class);

    private static final int MAX_MESSAGE_ID_LENGTH = 128;

    private final AiChatHistoryService history;

    public AiChatHistoryRecorder(AiChatHistoryService history) {
        this.history = history;
    }

    /** 用户本轮输入（在 run 创建后、上游调用前写）。 */
    public void recordUser(String sessionId, String runId, Message user) {
        if (user == null) {
            return;
        }
        append(sessionId, runId, bounded("user-", runId), user, false);
    }

    /**
     * 工具轮落定：assistant(tool_calls) + 一条 ToolResponseMessage（可含多个响应）。
     * 在工具全部结算后调用；重复调用按 messageId 幂等。
     */
    public void recordToolRound(String sessionId, String runId, AssistantMessage assistant,
            ToolResponseMessage toolResponse) {
        if (assistant != null && assistant.hasToolCalls()) {
            append(sessionId, runId, toolAssistantId(runId, assistant), assistant, false);
        }
        if (toolResponse != null && toolResponse.getResponses() != null && !toolResponse.getResponses().isEmpty()) {
            append(sessionId, runId, toolResponseId(toolResponse), toolResponse, false);
        }
    }

    /** 正常终局的最终 assistant（messageId 固定 {@code assistant-<runId>}）。 */
    public void recordFinalAssistant(String sessionId, String runId, AssistantMessage assistant) {
        if (assistant == null) {
            return;
        }
        append(sessionId, runId, bounded("assistant-", runId), assistant, false);
    }

    /** 取消/停止轮的半截 assistant 与配对 tool 响应；stopped 由消息 metadata 决定。 */
    public void recordStopped(String sessionId, String runId, List<Message> messages) {
        if (messages == null) {
            return;
        }
        for (Message message : messages) {
            appendDerived(sessionId, runId, message);
        }
    }

    /**
     * 以"最后一个真实 user"为界，幂等补齐当前 run 的 assistant/tool 消息。
     *
     * <p>用于 {@code convergeMemory}（重复调用安全）和测试/重启重放。它也会补写该 user 本身，
     * 因此首轮 user 写入若曾失败，converge 仍有机会补上。
     */
    public void recordRunTail(String sessionId, String runId, List<Message> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return;
        }
        int userIndex = lastRealUserIndex(historyMessages);
        if (userIndex < 0) {
            return;
        }
        append(sessionId, runId, bounded("user-", runId), historyMessages.get(userIndex), false);
        for (int i = userIndex + 1; i < historyMessages.size(); i++) {
            appendDerived(sessionId, runId, historyMessages.get(i));
        }
    }

    private void appendDerived(String sessionId, String runId, Message message) {
        if (message == null || isSynthetic(message)) {
            return;
        }
        MessageType type = message.getMessageType();
        if (type != MessageType.ASSISTANT && type != MessageType.TOOL) {
            return;
        }
        if (message instanceof AssistantMessage assistant) {
            boolean hasText = assistant.getText() != null && !assistant.getText().isBlank();
            if (!hasText && !assistant.hasToolCalls()) {
                return;
            }
            String messageId = assistant.hasToolCalls()
                    ? toolAssistantId(runId, assistant)
                    : bounded("assistant-", runId);
            append(sessionId, runId, messageId, message, false);
            return;
        }
        if (message instanceof ToolResponseMessage toolResponse) {
            if (toolResponse.getResponses() == null || toolResponse.getResponses().isEmpty()) {
                return;
            }
            append(sessionId, runId, toolResponseId(toolResponse), message, false);
        }
    }

    private void append(String sessionId, String runId, String messageId, Message message, boolean stopped) {
        Map<String, Object> metadata = message.getMetadata();
        boolean stoppedFlag = stopped || (metadata != null && Boolean.TRUE.equals(metadata.get("stopped")));
        try {
            AppendCommand command = this.history.commandFor(sessionId, runId, messageId, message, stoppedFlag);
            this.history.append(command);
        }
        catch (AiStateUnavailableException ex) {
            // 守卫状态不可知（fail-closed）：不得当"历史写失败"静默吞掉，必须阻止本次写入并上抛。
            throw ex;
        }
        catch (RuntimeException ex) {
            // 普通历史面失败不得反噬对话/续跑：下一次落定点或 converge 会按同一 messageId 重试。
            log.warn("完整历史写入失败（不影响本轮）sessionId={} runId={} messageId={}：{}", sessionId, runId,
                    messageId, ex.getMessage());
        }
    }

    /**
     * 合成/系统提示的判据 —— <b>委托</b> {@link SyntheticMessages}（唯一来源）。
     *
     * <p>为什么不再各写一份：写侧（本类，"最后一个真实 user"边界）与读侧
     * （{@code AiController#toHistoryItem}，{@code synthetic=true} 展示标记）必须同源，
     * 否则会出现"写侧当合成、读侧当用户原话"的分叉（AR-01 第 5 条：摘要不冒充用户）。
     */
    private static boolean isSynthetic(Message message) {
        return SyntheticMessages.isSynthetic(message);
    }

    private static int lastRealUserIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.getMessageType() != MessageType.USER || isSynthetic(message)) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static String toolAssistantId(String runId, AssistantMessage assistant) {
        List<String> ids = new ArrayList<>(assistant.getToolCalls().size());
        for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
            ids.add(call.id());
        }
        return bounded("assistant-" + runId + "-tool-", firstSorted(ids));
    }

    private static String toolResponseId(ToolResponseMessage toolResponse) {
        List<String> ids = new ArrayList<>(toolResponse.getResponses().size());
        for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
            ids.add(response.id());
        }
        // 与 SSE 的 toolMessageId(pending) 同形：tool-<toolCallId>；
        // runId 已在幂等键 (session, run, messageId) 里，不再重复进 messageId。
        return bounded("tool-", firstSorted(ids));
    }

    /** 排序取首：工具/响应的顺序变化不影响 messageId（同一批次必须命中同一行）。 */
    private static String firstSorted(List<String> ids) {
        return ids.stream().filter(id -> id != null && !id.isBlank()).sorted().findFirst().orElse("empty");
    }

    private static String bounded(String prefix, String stableKey) {
        String id = prefix + stableKey;
        if (id.length() <= MAX_MESSAGE_ID_LENGTH) {
            return id;
        }
        return prefix + sha256(stableKey).substring(0, 32);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
