package com.example.configmgr.ai.run;

import com.example.configmgr.ai.history.SyntheticMessages;
import com.example.configmgr.ai.tool.AiContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CorrectionPrefixes {

    static final String EXPORT_CORRECTION_PREFIX_KEY = "exportCorrectionPrefix";

    private CorrectionPrefixes() {
    }

    static CorrectionPrefixException normalizationFailure(Throwable cause) {
        if (cause instanceof CorrectionPrefixException exception) {
            return exception;
        }
        return new CorrectionPrefixException(cause);
    }

    static final class CorrectionPrefixException extends StreamViolationException {

        private CorrectionPrefixException(Throwable cause) {
            super(StreamViolationException.STATE_UNAVAILABLE, "correction prefix normalization unavailable", cause);
        }

    }

    public static String correctionPrefix(RunSnapshot snapshot) {
        try {
            if (snapshot == null) {
                return "";
            }
            Map<String, Object> extra = AiContext.fromMap(snapshot.getContext()).getExtra();
            Object value = extra == null ? null : extra.get(EXPORT_CORRECTION_PREFIX_KEY);
            return ObjectUtils.toString(value, "");
        } catch (RuntimeException ex) {
            throw normalizationFailure(ex);
        }
    }

    static final String PARKED_FULL_TEXT_KEY = "correctionParkedFullText";

    static final String PENDING_SEGMENT_KEY = "correctionPendingSegment";

    public static void recordParkedFullText(RunSnapshot snapshot, String fullText) {
        try {
            if (snapshot == null) {
                return;
            }
            AiContext current = AiContext.fromMap(snapshot.getContext());
            Map<String, Object> extra = current.getExtra() == null ? Map.of() : current.getExtra();
            if (!extra.containsKey(EXPORT_CORRECTION_PREFIX_KEY)) {
                return;
            }
            Map<String, Object> updated = new LinkedHashMap<>(extra);
            if (StringUtils.isNotEmpty(fullText)) {
                updated.put(PARKED_FULL_TEXT_KEY, fullText);
            }
            snapshot.setContext(AiContext.of(current.getPage(), current.getTaskType(), current.getStep(),
                    current.getTaskId(), current.getTaskStatus(), updated).toMap());
        } catch (RuntimeException ex) {
            throw normalizationFailure(ex);
        }
    }

    public static String parkedFullText(RunSnapshot snapshot) {
        try {
            if (snapshot == null) {
                return "";
            }
            Map<String, Object> extra = AiContext.fromMap(snapshot.getContext()).getExtra();
            return extra == null ? "" : ObjectUtils.toString(extra.get(PARKED_FULL_TEXT_KEY), "");
        } catch (RuntimeException ex) {
            throw normalizationFailure(ex);
        }
    }

    /** 模型输入专用：当前 run 的 correctionPrefix 恰好一份；快照、原 history 与 UI 前缀均不改写。 */
    public static List<Message> historyWithSingleCorrectionPrefix(List<Message> history, String prefix) {
        try {
            if (history == null || history.isEmpty() || StringUtils.isEmpty(prefix)) {
                return history == null ? List.of() : new ArrayList<>(history);
            }
            int lastRealUser = lastRealUserIndex(history);
            List<Message> sanitized = new ArrayList<>(history.size());
            boolean prefixRetained = false;
            for (int i = 0; i < history.size(); i++) {
                Message message = history.get(i);
                if (!(message instanceof AssistantMessage assistant) || i <= lastRealUser) {
                    sanitized.add(message);
                    continue;
                }
                String text = assistant.getText();
                if (text == null || !text.startsWith(prefix)) {
                    sanitized.add(assistant);
                    continue;
                }
                String suffix = stripLeadingCorrectionPrefix(text, prefix);
                if (!prefixRetained) {
                    sanitized.add(copyAssistantWithText(assistant, withSingleCorrectionPrefix(text, prefix)));
                    prefixRetained = true;
                } else if (assistant.hasToolCalls() || !suffix.isEmpty()) {
                    sanitized.add(copyAssistantWithText(assistant, suffix));
                }
            }
            return sanitized;
        } catch (RuntimeException ex) {
            throw normalizationFailure(ex);
        }
    }

    static String withSingleCorrectionPrefix(String text, String prefix) {
        if (StringUtils.isEmpty(text) || StringUtils.isEmpty(prefix) || !text.startsWith(prefix + prefix)) {
            return text;
        }
        return prefix + stripLeadingCorrectionPrefix(text, prefix);
    }

    static String stripLeadingCorrectionPrefix(String text, String prefix) {
        if (StringUtils.isEmpty(text) || StringUtils.isEmpty(prefix)) {
            return text;
        }
        int suffixStart = 0;
        while (text.startsWith(prefix, suffixStart)) {
            suffixStart += prefix.length();
        }
        return text.substring(suffixStart);
    }

    static final class StreamState {

        private final String prefix;

        private final StringBuilder pending = new StringBuilder();

        private boolean matching;

        StreamState(String prefix) {
            this.prefix = StringUtils.defaultString(prefix);
            this.matching = StringUtils.isNotEmpty(this.prefix);
        }

        String accept(String chunk) {
            if (!this.matching || chunk == null) {
                return chunk;
            }
            StringBuilder emitted = new StringBuilder(chunk.length());
            for (int i = 0; i < chunk.length(); i++) {
                char current = chunk.charAt(i);
                if (!this.matching) {
                    emitted.append(current);
                } else if (current == this.prefix.charAt(this.pending.length())) {
                    this.pending.append(current);
                    if (this.pending.length() == this.prefix.length()) {
                        this.pending.setLength(0);
                        this.matching = false;
                    }
                } else {
                    emitted.append(this.pending).append(current);
                    this.pending.setLength(0);
                    this.matching = false;
                }
            }
            return emitted.toString();
        }

        String close() {
            if (!this.matching) {
                return "";
            }
            this.matching = false;
            String emitted = this.pending.toString();
            this.pending.setLength(0);
            return emitted;
        }

    }

    private static int lastRealUserIndex(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof UserMessage message && !SyntheticMessages.isSynthetic(message)) {
                return i;
            }
        }
        return -1;
    }

    private static AssistantMessage copyAssistantWithText(AssistantMessage source, String text) {
        Map<String, Object> metadata = source.getMetadata() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(source.getMetadata());
        AssistantMessage.Builder builder = AssistantMessage.builder().content(text).properties(metadata);
        if (source.hasToolCalls()) {
            builder.toolCalls(new ArrayList<>(source.getToolCalls()));
        }
        if (CollectionUtils.isNotEmpty(source.getMedia())) {
            builder.media(new ArrayList<>(source.getMedia()));
        }
        return builder.build();
    }

}
