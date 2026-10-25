package com.example.configmgr.ai.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库样例播种的并发互斥（多实例专项）：同一知识目录、两个播种器同时启动时，
 * <b>恰好一个真正播种</b>，另一个跳过；结果目录是完整的一份语料（manifest + 全部文档），
 * 不会出现"两份 staging 互相覆盖/半份语料"。
 *
 * <p>知识目录是文件系统共享面（不是库），所以互斥用父目录上的跨进程文件锁
 * （{@link KbSampleSeeder} 类注）；本用例在同一 JVM 的两个线程里验证
 * {@code tryLock} 的 {@link java.nio.channels.OverlappingFileLockException} 分支与
 * "抢锁后重判"分支。
 */
class KbSampleSeederConcurrencyTest {

	@TempDir
	Path tempDir;

	@Test
	void twoSeedersProduceOneCompleteCopy() throws Exception {
		Path target = this.tempDir.resolve("kb");
		KbManifest manifest = KbManifest.parse(readClasspathManifest(), new ObjectMapper());
		manifest.validate();

		List<Boolean> results = runConcurrently(target, 2);

		assertThat(results).as("恰好一个播种器真正写入").containsExactlyInAnyOrder(true, false);
		assertThat(Files.isRegularFile(target.resolve("manifest.json"))).isTrue();
		for (KbDocMeta doc : manifest.docs()) {
			assertThat(Files.isRegularFile(target.resolve(doc.effectiveFile())))
					.as("样例文档 %s 应完整落盘", doc.effectiveFile())
					.isTrue();
		}
		assertThat(Files.exists(this.tempDir.resolve(".kb-samples-seed.lock")))
				.as("锁文件保留（删掉会让等待方各自打开新 inode）")
				.isTrue();
	}

	@Test
	void existingContentIsNeverOverwritten() throws Exception {
		Path target = this.tempDir.resolve("kb-existing");
		Files.createDirectories(target);
		Path userDoc = target.resolve("user-notes.md");
		Files.writeString(userDoc, "# 用户语料\n");

		List<Boolean> results = runConcurrently(target, 2);

		assertThat(results).containsExactly(false, false);
		assertThat(Files.readString(userDoc)).isEqualTo("# 用户语料\n");
		assertThat(Files.exists(target.resolve("manifest.json"))).isFalse();
	}

	private static byte[] readClasspathManifest() throws Exception {
		try (var in = KbSampleSeederConcurrencyTest.class.getClassLoader()
				.getResourceAsStream("kb-samples/manifest.json")) {
			if (in == null) {
				throw new IllegalStateException("测试类路径缺少 kb-samples/manifest.json"
						+ "（构建期由 maven-resources-plugin 从 docs/kb-samples 拷入）");
			}
			return in.readAllBytes();
		}
	}

	private List<Boolean> runConcurrently(Path target, int instances) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		ExecutorService pool = Executors.newFixedThreadPool(instances);
		CyclicBarrier barrier = new CyclicBarrier(instances);
		try {
			List<Future<Boolean>> futures = new ArrayList<>();
			for (int i = 0; i < instances; i++) {
				Callable<Boolean> task = () -> {
					barrier.await(30, TimeUnit.SECONDS);
					return new KbSampleSeeder(target, mapper).seedIfAbsent();
				};
				futures.add(pool.submit(task));
			}
			List<Boolean> results = new ArrayList<>();
			for (Future<Boolean> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

}
