package com.example.quickstart.runtime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * SSE 事件载荷（会话通道）。
 */
public class Events {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StateEvent {
        private String cause;
        private Map<String, Object> task;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgressEvent {
        private String taskId;
        private String op;
        private String phase;
        private int percent;
        private String message;
        private List<Map<String, Object>> items;
        private Boolean done;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityEvent {
        private String source;
        private String tool;
        private String text;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NoticeEvent {
        private String level;
        private String text;
    }
}
