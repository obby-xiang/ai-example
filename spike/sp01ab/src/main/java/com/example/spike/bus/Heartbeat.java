package com.example.spike.bus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.example.spike.config.SpikeProperties;
import com.example.spike.hitl.SessionState;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 心跳：挂起期间（模型循环阻塞在工具执行上）SSE 无业务事件，
 * 由定时器周期下发 ping 保活，并在 data 中带上当前挂起类型，便于事后证明
 * “连接在挂起窗口内未断开”。
 */
@Component
public class Heartbeat {

	private final EventHub hub;

	private final AtomicLong ticks = new AtomicLong();

	public Heartbeat(EventHub hub, SpikeProperties properties) {
		this.hub = hub;
	}

	@Scheduled(fixedRateString = "${spike.heartbeat-ms:2000}")
	public void tick() {
		long n = this.ticks.incrementAndGet();
		for (SessionState session : this.hub.all()) {
			int subscribers = session.events().subscriberCount();
			if (subscribers == 0) {
				continue;
			}
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("tick", n);
			data.put("pending", session.pendingKind());
			data.put("subscribers", subscribers);
			session.events().emit("ping", data);
		}
	}

}
