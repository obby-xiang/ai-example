package com.example.configmgr.perf.production;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import com.example.configmgr.perf.redis.CountingRedis;
import io.lettuce.core.event.command.CommandFailedEvent;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.RedisCommand;

/** Counts real commands emitted by production classes on the shared Lettuce client. */
final class ProductionCommandMetrics implements CommandListener {

	private final AtomicLong commands = new AtomicLong();

	private final AtomicLong scanPages = new AtomicLong();

	private final AtomicLong keyspaceScanPages = new AtomicLong();

	private final AtomicLong hashScanPages = new AtomicLong();

	private final AtomicLong requestBytes = new AtomicLong();

	private final AtomicLong failedCommands = new AtomicLong();

	@Override
	public void commandStarted(CommandStartedEvent event) {
		this.commands.incrementAndGet();
		RedisCommand<?, ?, ?> command = event.getCommand();
		String type = String.valueOf(command.getType());
		if ("SCAN".equals(type) || "HSCAN".equals(type) || "SSCAN".equals(type) || "ZSCAN".equals(type)) {
			this.scanPages.incrementAndGet();
		}
		if ("SCAN".equals(type)) {
			this.keyspaceScanPages.incrementAndGet();
		}
		if ("HSCAN".equals(type)) {
			this.hashScanPages.incrementAndGet();
		}
		CommandArgs<?, ?> args = command.getArgs();
		if (args != null) {
			this.requestBytes.addAndGet(args.toCommandString().getBytes(StandardCharsets.UTF_8).length);
		}
	}

	@Override
	public void commandFailed(CommandFailedEvent event) {
		this.failedCommands.incrementAndGet();
	}

	CountingRedis.Snapshot snapshot() {
		return new CountingRedis.Snapshot(this.commands.get(), 0L, this.scanPages.get(), 0L,
				this.requestBytes.get(), 0L);
	}

	long failedCommands() {
		return this.failedCommands.get();
	}

	long keyspaceScanPages() {
		return this.keyspaceScanPages.get();
	}

	long hashScanPages() {
		return this.hashScanPages.get();
	}

}
