package com.example.configadmin.service;

import com.example.configadmin.dto.ProgressEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 长任务进度中心：
 * - 任务执行线程 publish 进度事件（状态/百分比/阶段消息/明细）；
 * - 前端通过 SSE 订阅，收到 progress 事件与完成事件；断线重连后由快照接口恢复。
 */
@Component
public class ProgressHub {

    private static final Logger log = LoggerFactory.getLogger(ProgressHub.class);

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final ScheduledExecutorService heartbeat;

    public ProgressHub(ObjectMapper mapper,
                       @org.springframework.beans.factory.annotation.Qualifier("heartbeatExecutor")
                       ScheduledExecutorService heartbeat) {
        this.mapper = mapper;
        this.heartbeat = heartbeat;
    }

    /** 订阅任务进度。onOpen 提供当前快照（重连恢复场景）。 */
    public SseEmitter subscribe(String topic, java.util.function.Supplier<ProgressEvent> snapshot) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(emitter);
        // 连接打开后先推快照
        emitter.onCompletion(() -> remove(topic, emitter));
        emitter.onTimeout(() -> remove(topic, emitter));
        emitter.onError(e -> remove(topic, emitter));

        var heartbeatTask = heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name("ping").data(""));
            } catch (Exception ignored) {
                // 客户端已断开
            }
        }, 15, 15, java.util.concurrent.TimeUnit.SECONDS);

        emitter.onCompletion(() -> heartbeatTask.cancel(true));
        emitter.onTimeout(() -> heartbeatTask.cancel(true));
        emitter.onError(e -> heartbeatTask.cancel(true));

        try {
            emitter.send(SseEmitter.event().name("snapshot").data(json(snapshot.get())));
        } catch (IOException e) {
            remove(topic, emitter);
        }
        return emitter;
    }

    /** 推送进度事件。 */
    public void publish(String topic, ProgressEvent event) {
        String data = json(event);
        List<SseEmitter> list = emitters.get(topic);
        if (list != null) {
            for (SseEmitter e : list) {
                try {
                    e.send(SseEmitter.event().name("progress").data(data));
                } catch (IOException | IllegalStateException ex) {
                    remove(topic, e);
                }
            }
        }
    }

    /** 任务结束：推送 done 事件并关闭全部订阅。 */
    public void complete(String topic, ProgressEvent finalEvent) {
        String data = json(finalEvent);
        List<SseEmitter> list = emitters.remove(topic);
        if (list != null) {
            for (SseEmitter e : list) {
                try {
                    e.send(SseEmitter.event().name("done").data(data));
                } catch (IOException | IllegalStateException ignored) {
                    // 客户端已断开
                }
                try {
                    e.complete();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void remove(String topic, SseEmitter e) {
        List<SseEmitter> list = emitters.get(topic);
        if (list != null) {
            list.remove(e);
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"ERROR\",\"message\":\"序列化失败\"}";
        }
    }
}
