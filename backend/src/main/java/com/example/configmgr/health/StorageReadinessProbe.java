package com.example.configmgr.health;

import com.example.configmgr.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code storage} 维度：文件存储目录（{@code app.file.storage-path}）可写。
 *
 * <p>口径与 {@code FileStorageService} 一致：目录不存在时**先创建**（该服务在首次使用时也会
 * 用 {@code Files.createDirectories} 建），之后判"是目录且可写"。
 *
 * <p>为什么不写探针文件：写盘探针会在每次探测留下垃圾文件/触发杀毒扫描；而目录不可写
 * （只读挂载、权限丢失、磁盘满）在 {@code Files.isWritable} 上已经能暴露到需要的一档精度。
 * 磁盘满（写时 ENOSPC）不在本探针覆盖范围 —— 它属于容量告警，不是 ready 判据
 * （否则一次大导出就能把实例整体摘流）。
 *
 * <p><b>阻塞边界</b>：{@code Files.createDirectories/isWritable} 在远程共享卷（NFS/SMB）
 * 或半死挂载上可能长时间阻塞，且属于不可中断的 native I/O。本探针**不自带**超时；
 * 有界性由 {@link ReadinessProbeRunner} 提供 —— 共享总时限到点即记本维度 DOWN、
 * 调用方按期返回；卡住的线程只能等 I/O 返回（Java 无法杀死它），池位耗尽后新探测按
 * saturated 记 DOWN，端点仍不被拖住。
 *
 * <p>多实例前提：三个实例必须挂**同一份**存储（共享卷/对象存储网关）；各挂各的本地盘时，
 * 上传到 A 的文件在 B 上读不到 —— 本探针只能证明"本实例的这一份可写"。
 */
@Component
class StorageReadinessProbe implements ReadinessProbe {

	static final String NAME = "storage";

	private final AppProperties appProperties;

	StorageReadinessProbe(AppProperties appProperties) {
		this.appProperties = appProperties;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public ProbeResult check() {
		String configured = this.appProperties.getFile().getStoragePath();
		if (configured == null || configured.isBlank()) {
			return ProbeResult.down("app.file.storage-path 未配置");
		}
		Path dir = Path.of(configured);
		try {
			if (!Files.isDirectory(dir)) {
				Files.createDirectories(dir);
			}
			if (!Files.isDirectory(dir)) {
				return ProbeResult.down(dir.toAbsolutePath() + " 不是目录");
			}
			if (!Files.isWritable(dir)) {
				return ProbeResult.down(dir.toAbsolutePath() + " 不可写");
			}
			return ProbeResult.up(dir.toAbsolutePath().toString());
		}
		catch (IOException | RuntimeException ex) {
			return ProbeResult.down(ex.getClass().getSimpleName()
					+ (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
		}
	}

}
