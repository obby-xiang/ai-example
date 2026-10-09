/*
 * M2-T3a 随证探针（仓库外）—— 攻击 R1「双口径不变量」缺陷：回放批与实时帧共用同一条订阅者队列，
 * 而实时容量闸按队列绝对长度判定，慢重挂者回放排空期被一次心跳确定性摘除（与 R1 裁决意图相悖）。
 *
 * 执行者：DS-V4-Pro 红队棒
 * 日期：2026-10-09
 * 运行方式：仓库外另存为 `ProbeR1.java` 后编译运行（`java @run.args ProbeR1`），classpath 参照 backend/target 的 classes / test-classes 与依赖 jar；本文件仅为随证留存源码（文件名带随证前缀，public 类名不变），不参与仓库构建与测试计数。
 * 结论指针：docs/evidence/M2-T3a-红队攻击-DS-V4-Pro.md §1 判断点 2 与 §2 S1；修复批留痕 docs/evidence/M2-T3a-施工与验证-K2.8.md §8.1；修复后复核 docs/evidence/M2-T3a-复现与复核-GLM-5.3.md。
 */
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * M2-T3a 独立验证探针（仓库外）—— R1 双口径不变量攻击假设。
 *
 * 假设：回放初始批与实时帧共用同一条订阅者队列，而 enqueueLive 的容量闸判据是
 * {@code sub.queue.size() >= queueCapacity}（绝对队列长度）。慢重挂者刚 stage 完 3000 帧回放、
 * 还在被 drain 慢慢写出时，任一实时帧（生产上挂起期每 2s 一次心跳）落到该订阅者，
 * 会看到「队列里仍压着上千条回放帧」，从而把容量闸误判为溢出，摘除（complete）一个
 * 健康的慢订阅者 —— 与 R1 裁决「回放批豁免有界容量」的意图相悖。
 */
public class ProbeR1 {

	static class SlowEmitter extends SseEmitter {
		final List<String> sent = Collections.synchronizedList(new ArrayList<>());
		final CountDownLatch firstSendStarted = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		volatile boolean completed;
		volatile int deliveredAtComplete = -1;

		@Override
		public void send(Object object) throws IOException {
			if (this.completed) {
				throw new IllegalStateException("ResponseBodyEmitter has already completed");
			}
			this.firstSendStarted.countDown();
			try {
				this.release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			this.sent.add(String.valueOf(object));
		}

		@Override
		public void complete() {
			this.deliveredAtComplete = this.sent.size();
			this.completed = true;
		}
	}

	static Map<String, Object> frame(String type, long seq) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("type", type);
		f.put("seq", seq);
		return f;
	}

	public static void main(String[] args) throws Exception {
		ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(), r -> {
					Thread t = new Thread(r, "probe-r1");
					t.setDaemon(true);
					return t;
				});

		// 场景 1：慢重挂者回放 3000 帧期间，一条实时心跳是否把它摘除？
		{
			InMemoryRunStore store = new InMemoryRunStore();
			Map<String, Object>[] archive = new Map[3000];
			for (long seq = 1; seq <= 3000; seq++) {
				archive[(int) (seq - 1)] = frame("tool_start", seq);
			}
			store.seedArchive("run-r1", archive);
			SseChatEmitter out = new SseChatEmitter("run-r1", store, new ObjectMapper(), pool, 256);

			SlowEmitter slow = new SlowEmitter();
			int staged = out.replayAndAttach(slow, null, false);
			slow.firstSendStarted.await(10, TimeUnit.SECONDS);
			int before = slow.sent.size();

			// 生产上挂起期每 2s 一次心跳；这里用一次心跳即可触发
			out.heartbeat(Map.of("phase", "streaming"));
			Thread.sleep(300L);

			System.out.printf(
					"[场景1 回放中混入实时帧] staged=%d 首帧阻塞中已收=%d 实时心跳后 complete=%s 最终收到=%d（回放剩余被摘除）%n",
					staged, before, slow.completed, slow.deliveredAtComplete);
			slow.release.countDown();
		}

		// 场景 2：摘除后重挂（带 lastSeq），下一次心跳是否再次摘除 → 重挂饥饿。
		{
			InMemoryRunStore store = new InMemoryRunStore();
			Map<String, Object>[] archive = new Map[3000];
			for (long seq = 1; seq <= 3000; seq++) {
				archive[(int) (seq - 1)] = frame("tool_start", seq);
			}
			store.seedArchive("run-r1b", archive);
			SseChatEmitter out = new SseChatEmitter("run-r1b", store, new ObjectMapper(), pool, 256);

			// 客户端已收到 seq=1（lastSeq=1），重挂补 seq 2..3000
			SlowEmitter re = new SlowEmitter();
			int staged = out.replayAndAttach(re, 1L, false);
			re.firstSendStarted.await(10, TimeUnit.SECONDS);
			out.heartbeat(Map.of("phase", "streaming"));
			Thread.sleep(300L);
			System.out.printf(
					"[场景2 断点续收重挂] staged=%d（seq2..3000） 实时心跳后 complete=%s 本次只收到=%d 帧即被摘除%n",
					staged, re.completed, re.deliveredAtComplete);
			re.release.countDown();
		}

		pool.shutdownNow();
	}
}
