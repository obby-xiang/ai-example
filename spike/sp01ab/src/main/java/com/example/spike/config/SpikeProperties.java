package com.example.spike.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spike")
public class SpikeProperties {

	private int frontendToolTimeoutSeconds = 60;

	private int confirmTimeoutSeconds = 30;

	private long heartbeatMs = 2000;

	public int getFrontendToolTimeoutSeconds() {
		return this.frontendToolTimeoutSeconds;
	}

	public void setFrontendToolTimeoutSeconds(int frontendToolTimeoutSeconds) {
		this.frontendToolTimeoutSeconds = frontendToolTimeoutSeconds;
	}

	public int getConfirmTimeoutSeconds() {
		return this.confirmTimeoutSeconds;
	}

	public void setConfirmTimeoutSeconds(int confirmTimeoutSeconds) {
		this.confirmTimeoutSeconds = confirmTimeoutSeconds;
	}

	public long getHeartbeatMs() {
		return this.heartbeatMs;
	}

	public void setHeartbeatMs(long heartbeatMs) {
		this.heartbeatMs = heartbeatMs;
	}

}
