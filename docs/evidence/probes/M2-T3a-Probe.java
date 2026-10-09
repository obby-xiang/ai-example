/*
 * M2-T3a 随证探针（仓库外，不改动仓库任何代码）—— 攻击 S1-1「轮终态不排空（终态丢帧）」缺陷。
 *
 * 执行者：DS-V4-Flash 验收棒
 * 日期：2026-10-09
 * 运行方式：仓库外另存为 `Probe.java` 后编译运行（`java @run.args Probe`），classpath 参照 backend/target 的 classes / test-classes 与依赖 jar；本文件仅为随证留存源码（文件名带随证前缀，public 类名不变），不参与仓库构建与测试计数。
 * 结论指针：docs/evidence/M2-T3a-验收盘点-DS-V4-Flash.md §「独立发现（探针实测）」S1-1；修复批第 1 轮零丢失复跑旁证 docs/evidence/M2-T3a-红队攻击-DS-V4-Pro.md §0。
 *
 * 四个子探针：A 池饱和队列丢帧 / B 竞态尾帧丢失率 / C 进程静态投递池泄漏至两参 RunRegistry / D 生产形状（4 工作线程占满）。
 */
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * M2-T3a 独立验证探针（仓库外，不改动仓库任何代码）。
 *
 * 目标 1：SseChatEmitter.complete()（= RunRegistry.close 的终态路径）是否排空各订阅者队列？
 * 目标 2：无 AiProperties 的 RunRegistry（两参构造，进程静态投递池已装配时）是否仍同步直执？
 */
public class Probe {

	/** Spring 语义替身：完成后再 send 抛 IllegalStateException（ResponseBodyEmitter#send 的 Assert.state）。 */
	static class RecordingEmitter extends SseEmitter {

		final List<String> sent = Collections.synchronizedList(new ArrayList<>());

		volatile boolean completed;

		volatile boolean blocking;

		CountDownLatch blocked;

		CountDownLatch release;

		volatile int deliveredAtComplete = -1;

		@Override
		public void send(Object object) throws IOException {
			if (this.completed) {
				throw new IllegalStateException("ResponseBodyEmitter has already completed");
			}
			if (this.blocking) {
				this.blocked.countDown();
				try {
					this.release.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
			}
			this.sent.add(String.valueOf(object));
		}

		@Override
		public void complete() {
			this.deliveredAtComplete = this.sent.size();
			this.completed = true;
		}

	}

	public static void main(String[] args) throws Exception {
		probeA();
		probeD();
		probeB();
		probeC();
	}

	/** D：生产形状——4 个工作线程被 4 条慢连接占满，第 5 个正常订阅者的队列在轮终态被丢弃。 */
	static void probeD() throws Exception {
		ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(), r -> {
					Thread t = new Thread(r, "probe-d");
					t.setDaemon(true);
					return t;
				});
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = new SseChatEmitter("run-D", store, new ObjectMapper(), pool, 256);
		CountDownLatch allBlocked = new CountDownLatch(4);
		CountDownLatch release = new CountDownLatch(1);
		for (int i = 0; i < 4; i++) {
			RecordingEmitter slow = new RecordingEmitter();
			slow.blocking = true;
			slow.blocked = allBlocked;
			slow.release = release;
			out.attach(slow);
		}
		RecordingEmitter normal = new RecordingEmitter();
		out.attach(normal);

		out.start("s-1");
		allBlocked.await(5, TimeUnit.SECONDS);
		out.textStart();
		out.textEnd(3);
		out.done(Collections.emptyMap(), "m", Collections.emptyMap());
		// 第 5 个订阅者（非慢连接）的帧全部停在池任务队列里
		out.complete();
		release.countDown();
		TimeUnit.MILLISECONDS.sleep(500L);
		pool.shutdownNow();
		System.out.printf("[D 4 线程占满] 正常订阅者应得 3 帧，complete 前已投递=%d，最终投递=%d（丢失 %d）%n",
				normal.deliveredAtComplete, normal.sent.size(), 3 - normal.sent.size());
	}

	/** A：投递池被占满（R3 文档化的最坏态）时，轮终态 complete 与队列中已有帧。 */
	static void probeA() throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "probe-pool");
			t.setDaemon(true);
			return t;
		});
		CountDownLatch occupied = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		pool.execute(() -> {
			occupied.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		});
		occupied.await(5, TimeUnit.SECONDS);

		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = new SseChatEmitter("run-A", store, new ObjectMapper(), pool, 256);
		RecordingEmitter sub = new RecordingEmitter();
		out.attach(sub);

		int emitted = 0;
		out.start("s-1");
		emitted++;
		out.textStart();
		emitted++;
		out.textEnd(5);
		emitted++;
		out.done(Collections.emptyMap(), "probe-model", Collections.emptyMap());
		emitted++;
		// 轮终态：AiController/ResumeService 的 finally 正是这一次调用
		out.complete();

		release.countDown();
		TimeUnit.MILLISECONDS.sleep(500L);
		pool.shutdownNow();
		System.out.printf("[A 池饱和] 出帧=%d 队列已投递=%d 丢失=%d%n", emitted, sub.sent.size(),
				emitted - sub.sent.size());
	}

	/** B：不饱和池下的真实竞态（emit 后即轮终态 complete），反复试验统计尾帧丢失率。 */
	static void probeB() throws Exception {
		int trials = 200;
		int lost = 0;
		for (int i = 0; i < trials; i++) {
			ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 60L, TimeUnit.SECONDS,
					new LinkedBlockingQueue<>(), r -> {
						Thread t = new Thread(r, "probe-b");
						t.setDaemon(true);
						return t;
					});
			InMemoryRunStore store = new InMemoryRunStore();
			SseChatEmitter out = new SseChatEmitter("run-B-" + i, store, new ObjectMapper(), pool, 256);
			RecordingEmitter sub = new RecordingEmitter();
			out.attach(sub);
			out.textStart();
			out.textEnd(3);
			out.done(Collections.emptyMap(), "m", Collections.emptyMap());
			// 与生产路径同构：终帧 emit 之后（sessionGate.release / releaseResumeLock 量级的工作）再收尾
			Thread.sleep(0, 200_000);
			out.complete();
			TimeUnit.MILLISECONDS.sleep(50L);
			if (sub.sent.size() < 3) {
				lost++;
			}
			pool.shutdownNow();
		}
		System.out.printf("[B 竞态] %d 次「3 帧 + 轮终态」试验：尾帧未送达 %d 次（%.0f%%）%n", trials, lost,
				100.0 * lost / trials);
	}

	/** C：进程静态投递池装配后，两参 RunRegistry（单测路径）是否仍同步直执。 */
	static void probeC() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter before = new com.example.configmgr.ai.run.RunRegistry(store, new ObjectMapper())
				.of("run-C-before");
		RecordingEmitter subBefore = new RecordingEmitter();
		before.attach(subBefore);
		before.start("s-1");
		int beforeCount = subBefore.sent.size();

		SseChatEmitter.configureSharedDeliveryPool(4, 256);

		SseChatEmitter after = new com.example.configmgr.ai.run.RunRegistry(store, new ObjectMapper())
				.of("run-C-after");
		RecordingEmitter subAfter = new RecordingEmitter();
		after.attach(subAfter);
		after.start("s-1");
		int afterCountImmediate = subAfter.sent.size();
		TimeUnit.MILLISECONDS.sleep(200L);
		int afterCountLater = subAfter.sent.size();
		System.out.printf("[C 静态池泄漏] 装配前即时到达=%d；装配后即时到达=%d，200ms 后=%d%n", beforeCount,
				afterCountImmediate, afterCountLater);
	}

}
