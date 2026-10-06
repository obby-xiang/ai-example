package com.example.spike.web;

import com.example.spike.cancel.CancellationRegistry;
import com.example.spike.core.FrontendToolLoop;
import com.example.spike.tools.ToolExecutionLog;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DevController {

    private final ToolExecutionLog execLog;
    private final CancellationRegistry cancelRegistry;
    private final ToolCallingManager toolCallingManager;
    private final FrontendToolLoop frontendToolLoop;
    private final com.example.spike.tools.ToolActivityBeacon beacon;
    private final com.example.spike.tools.SpikeTools spikeTools;

    public DevController(ToolExecutionLog execLog, CancellationRegistry cancelRegistry,
                         ToolCallingManager toolCallingManager, FrontendToolLoop frontendToolLoop,
                         com.example.spike.tools.ToolActivityBeacon beacon,
                         com.example.spike.tools.SpikeTools spikeTools) {
        this.execLog = execLog;
        this.cancelRegistry = cancelRegistry;
        this.toolCallingManager = toolCallingManager;
        this.frontendToolLoop = frontendToolLoop;
        this.beacon = beacon;
        this.spikeTools = spikeTools;
    }

    @GetMapping("/dev/tool-exec-log")
    public List<Map<String, Object>> toolExecLog() {
        return execLog.all();
    }

    @DeleteMapping("/dev/tool-exec-log")
    public Map<String, Object> clearToolExecLog() {
        execLog.clear();
        return Map.of("cleared", true);
    }

    @GetMapping("/dev/cancel-state")
    public Map<String, Object> cancelState() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("registered", cancelRegistry.registered());
        m.put("activeThreads", spikeThreads());
        return m;
    }

    /**
     * V-d2 子实验 A：把"同一个 toolCallId 出现两次"的 AssistantMessage 直接喂给
     * 官方 ToolCallingManager.executeToolCalls（官方循环真正调用的那个方法），
     * 用工具执行台账证明是否重复执行、历史里是否出现两条同 id 的 tool 响应。
     */
    @PostMapping("/dev/spi-duplicate-toolcall")
    public Map<String, Object> spiDuplicateToolCall(@RequestBody(required = false) Map<String, Object> body) {
        String runId = body == null ? null : (String) body.get("runId");
        if (runId == null) {
            runId = "spi-dup-" + System.currentTimeMillis();
        }
        long before = execLog.currentSeq();

        AssistantMessage.ToolCall tc1 = new AssistantMessage.ToolCall("call_dup_same_id", "function", "getServerTime", "{}");
        AssistantMessage.ToolCall tc2 = new AssistantMessage.ToolCall("call_dup_same_id", "function", "getServerTime", "{}");
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(tc1, tc2))
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(true)
                .toolContext(Map.of("runId", runId))
                .build();
        // DefaultToolCallingManager 先按 options.getToolCallbacks() 找回调，找不到再查容器；
        // 这里必须显式带上，否则报 "No ToolCallback found for tool name"。
        options.setToolCallbacks(Arrays.asList(MethodToolCallbackProvider.builder()
                .toolObjects(spikeTools).build().getToolCallbacks()));
        Prompt prompt = new Prompt(new ArrayList<Message>(List.of(new UserMessage("dup-test"))), options);
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("inputAssistantToolCallIds", List.of(tc1.id(), tc2.id()));
        try {
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            List<String> responseIds = new ArrayList<>();
            List<Map<String, Object>> history = new ArrayList<>();
            for (Message m : result.conversationHistory()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("type", m.getMessageType().name());
                e.put("text", m.getText());
                if (m instanceof ToolResponseMessage trm) {
                    e.put("toolResponseIds", trm.getResponses().stream()
                            .map(ToolResponseMessage.ToolResponse::id).toList());
                    responseIds.addAll(trm.getResponses().stream()
                            .map(ToolResponseMessage.ToolResponse::id).toList());
                }
                if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                    e.put("toolCallIds", am.getToolCalls().stream()
                            .map(AssistantMessage.ToolCall::id).toList());
                }
                history.add(e);
            }
            out.put("error", null);
            out.put("toolExecutionResultHistory", history);
            out.put("toolResponseIdsInOrder", responseIds);
            out.put("distinctToolResponseIds", responseIds.stream().distinct().toList());
        } catch (Exception e) {
            out.put("error", e.getClass().getName() + ": " + e.getMessage());
        }
        List<Map<String, Object>> executions = execLog.since(before);
        out.put("executions", executions);
        out.put("executionCount", executions.size());
        out.put("executionCountSameId", execLog.countById("call_dup_same_id"));
        return out;
    }

    // ------------------------------------------------------------------ 前端执行工具路径（V-d2 主实验）

    @PostMapping("/fronttool/start")
    public Map<String, Object> fronttoolStart(@RequestBody Map<String, Object> body) {
        return frontendToolLoop.start(
                String.valueOf(body.getOrDefault("sessionId", "ftl")),
                String.valueOf(body.getOrDefault("message", "现在几点？")),
                body.get("system") == null ? null : String.valueOf(body.get("system")));
    }

    @PostMapping("/fronttool/{runId}/result")
    public Map<String, Object> fronttoolResult(@PathVariable String runId, @RequestBody Map<String, Object> body) {
        return frontendToolLoop.submitResult(runId,
                String.valueOf(body.get("toolCallId")),
                String.valueOf(body.getOrDefault("result", "")));
    }

    @GetMapping("/fronttool/{runId}")
    public Map<String, Object> fronttoolState(@PathVariable String runId) {
        return frontendToolLoop.state(runId);
    }

    @GetMapping("/dev/tool-activity")
    public Map<String, Object> toolActivity() {
        return beacon.snapshot();
    }

    // ------------------------------------------------------------------ 阻塞线程取证（"无限挂起"证据）

    private List<Map<String, Object>> spikeThreads() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            if (!t.getName().startsWith("spike-run-")) {
                continue;
            }
            List<String> frames = new ArrayList<>();
            boolean inRun = false;
            for (StackTraceElement f : e.getValue()) {
                frames.add(f.toString());
                String s = f.toString();
                if (s.contains("com.example.spike.core") || s.contains("blockLast")
                        || s.contains("BlockingSingleSubscriber") || s.contains("blockingGet")) {
                    inRun = true;
                }
                if (frames.size() >= 30) {
                    break;
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("thread", t.getName());
            m.put("state", t.getState().name());
            m.put("blockedInRun", inRun);
            m.put("topFrames", frames);
            out.add(m);
        }
        return out;
    }
}
