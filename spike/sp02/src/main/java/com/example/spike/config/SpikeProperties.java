package com.example.spike.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spike")
public class SpikeProperties {

	private int frontendToolTimeoutSeconds = 120;

	private int confirmTimeoutSeconds = 120;

	private long execDelayMsAfterApprove = 0;

	private int runTtlHours = 6;

	private int maxRounds = 5;

	private boolean suppressExternalRounds = false;

	/** 挂起/轮次承载池容量 = 挂起并发上限（DC-12：平台线程 + 有界池，超限快速失败）。 */
	private int suspendPoolSize = 16;

	public int getSuspendPoolSize() {
		return this.suspendPoolSize;
	}

	public void setSuspendPoolSize(int suspendPoolSize) {
		this.suspendPoolSize = suspendPoolSize;
	}

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

	public long getExecDelayMsAfterApprove() {
		return this.execDelayMsAfterApprove;
	}

	public void setExecDelayMsAfterApprove(long execDelayMsAfterApprove) {
		this.execDelayMsAfterApprove = execDelayMsAfterApprove;
	}

	public int getRunTtlHours() {
		return this.runTtlHours;
	}

	public void setRunTtlHours(int runTtlHours) {
		this.runTtlHours = runTtlHours;
	}

	public int getMaxRounds() {
		return this.maxRounds;
	}

	public void setMaxRounds(int maxRounds) {
		this.maxRounds = maxRounds;
	}

	public boolean isSuppressExternalRounds() {
		return this.suppressExternalRounds;
	}

	public void setSuppressExternalRounds(boolean suppressExternalRounds) {
		this.suppressExternalRounds = suppressExternalRounds;
	}

}
