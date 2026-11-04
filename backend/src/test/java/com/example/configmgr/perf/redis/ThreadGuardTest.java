package com.example.configmgr.perf.redis;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadGuardTest {

	@Test
	void aliveThreadAfterJoinIsAnExplicitFailure() {
		AtomicReference<Throwable> error = new AtomicReference<>();
		Thread thread = new Thread(() -> {
			try {
				Thread.sleep(500L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		thread.start();
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> ThreadGuard.joinOrFail(thread, 20L, "dual-executor", error));
		assertTrue(ex.getMessage().contains("still alive"));
		thread.interrupt();
	}

	@Test
	void threadExceptionIsPropagated() {
		AtomicReference<Throwable> error = new AtomicReference<>();
		Thread thread = new Thread(() -> error.set(new IllegalStateException("writer failed")));
		thread.start();
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> ThreadGuard.joinOrFail(thread, 1_000L, "dual-executor", error));
		assertTrue(ex.getMessage().contains("writer failed") || ex.getCause() != null);
	}

	@Test
	void normalThreadJoins() {
		AtomicReference<Throwable> error = new AtomicReference<>();
		Thread thread = new Thread(() -> {
		});
		thread.start();
		assertDoesNotThrow(() -> ThreadGuard.joinOrFail(thread, 1_000L, "dual-executor", error));
	}

}
