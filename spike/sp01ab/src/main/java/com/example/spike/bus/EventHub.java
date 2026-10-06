package com.example.spike.bus;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.example.spike.hitl.SessionState;

@Component
public class EventHub {

	private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

	public SessionState session(String sessionId) {
		return this.sessions.computeIfAbsent(sessionId, SessionState::new);
	}

	public SessionState find(String sessionId) {
		return this.sessions.get(sessionId);
	}

	public Collection<SessionState> all() {
		return this.sessions.values();
	}

}
