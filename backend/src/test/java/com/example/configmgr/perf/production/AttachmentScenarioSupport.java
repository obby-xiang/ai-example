package com.example.configmgr.perf.production;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.configmgr.ai.form.FormAttachmentStore;
import com.example.configmgr.ai.form.GenerativeFormAttachmentController;
import com.example.configmgr.ai.form.GenerativeFormGuard;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.form.RedisFormAttachmentStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.AiSessionPurgeService;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.session.SessionPurgeDatabase;
import com.example.configmgr.ai.session.SessionPurgeGuard;
import com.example.configmgr.ai.session.SessionPurgePendingStore;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.config.AppProperties;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.file.StoredFile;
import com.example.configmgr.perf.redis.Manifest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class AttachmentScenarioSupport {

	private AttachmentScenarioSupport() {
	}

	static Fixture fixture(ProductionSupport support, String runId, String toolCallId, String sessionId) throws IOException {
		return new Fixture(support, runId, toolCallId, sessionId, null);
	}

	static Fixture fixture(ProductionSupport support, String runId, String toolCallId, String sessionId,
			String fixtureRoot) throws IOException {
		return new Fixture(support, runId, toolCallId, sessionId, fixtureRoot);
	}

	static final class Fixture {

		final ProductionSupport support;
		final String runId;
		final String toolCallId;
		final String sessionId;
		final Path root;
		final Path storage;
		final Path retainedFile;
		final FileStorageService fileStorage;
		final RedisFormAttachmentStore store;
		final GenerativeFormAttachmentController controller;
		final GenerativeFormGuard guard;
		final RunStore runStore;
		final AiSessionPurgeService purge;
		final SessionPurgeGuard purgeGuard;
		final SessionPurgePendingStore pending;
		final RedisChatMemoryRepository memory;

		Fixture(ProductionSupport support, String runId, String toolCallId, String sessionId, String fixtureRoot)
				throws IOException {
			this.support = support;
			this.runId = runId;
			this.toolCallId = toolCallId;
			this.sessionId = sessionId;
			this.root = fixtureRoot == null ? Files.createTempDirectory("attachment-production-perf-")
					: Path.of(fixtureRoot);
			try {
				this.storage = this.root.resolve("storage");
				Files.createDirectories(this.storage);
				this.retainedFile = this.root.resolve("retained-control.txt");
				Files.writeString(this.retainedFile, "retained control sample; do not delete");
				AppProperties appProperties = new AppProperties();
				appProperties.getFile().setStoragePath(this.storage.toString());
				this.fileStorage = new FileStorageService(appProperties);
				org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisProvider = mock(
						org.springframework.beans.factory.ObjectProvider.class);
				when(redisProvider.getIfAvailable()).thenReturn(support.redis);
				this.store = new RedisFormAttachmentStore(redisProvider, support.mapper, support.aiProperties);
				LeaseService leases = new LeaseService(support.redis, support.aiProperties, (task, interval) -> () -> { },
						"attachment-perf");
				this.runStore = new RunStore(support.redis, support.mapper, support.aiProperties, leases);
				this.controller = new GenerativeFormAttachmentController(this.fileStorage, this.store, this.runStore, null,
						support.mapper);
				org.springframework.beans.factory.ObjectProvider<FormAttachmentStore> storeProvider = mock(
						org.springframework.beans.factory.ObjectProvider.class);
				when(storeProvider.getIfAvailable()).thenReturn(this.store);
				org.springframework.beans.factory.ObjectProvider<FileStorageService> fileProvider = mock(
						org.springframework.beans.factory.ObjectProvider.class);
				when(fileProvider.getIfAvailable()).thenReturn(this.fileStorage);
				this.guard = new GenerativeFormGuard(support.mapper, storeProvider, fileProvider);
				this.purgeGuard = new SessionPurgeGuard(support.redis);
				this.pending = new SessionPurgePendingStore(support.redis, support.mapper, support.aiProperties);
				this.memory = new RedisChatMemoryRepository(support.redis, new MessageJsonCodec(support.mapper),
						support.ttl(), this.purgeGuard);
				SessionPurgeDatabase database = mock(SessionPurgeDatabase.class);
				when(database.snapshot(anyString(), anyLong(), anyLong())).thenReturn(new SessionPurgeDatabase.Snapshot(0));
				when(database.delete(anyString(), anyLong(), anyLong())).thenReturn(new SessionPurgeDatabase.Deleted(0));
				this.purge = new AiSessionPurgeService(support.redis, this.runStore,
						new SessionGate(support.redis, support.aiProperties), new CancellationRegistry(support.redis,
								support.aiProperties), this.memory, this.store, this.fileStorage, database, this.purgeGuard,
						this.pending, support.aiProperties, Duration.ZERO);
			}
			catch (IOException | RuntimeException | Error ex) {
				try {
					deleteTree(this.root);
				}
				catch (Throwable cleanupError) {
					ex.addSuppressed(cleanupError);
				}
				throw ex;
			}
		}

		void seedRun(Manifest.Dataset dataset) {
			dataset.runIds.add(this.runId);
			this.support.identity(() -> {
				RunSnapshot snapshot = new RunSnapshot();
				snapshot.setRunId(this.runId);
				snapshot.setSessionId(this.sessionId);
				snapshot.setTenantId(this.support.cfg.tenantId);
				snapshot.setUserId(this.support.cfg.userId);
				snapshot.setStatus(RunSnapshot.DONE);
				snapshot.setCreatedAtMs(System.currentTimeMillis());
				snapshot.setUpdatedAtMs(System.currentTimeMillis());
				snapshot.setMessageJson(List.of());
				snapshot.freezeParams(new RunSnapshot.FrozenParams(null, null, null, true));
				this.runStore.save(snapshot);
				PendingToolCall pending = new PendingToolCall();
				pending.setToolCallId(this.toolCallId);
				pending.setName(GenerativeFormRules.TOOL_NAME);
				pending.setKind(PendingToolCall.KIND_FRONTEND);
				pending.setStatus(PendingToolCall.PENDING);
				pending.setArguments("{\"form\":{\"title\":\"perf\",\"fields\":["
						+ "{\"key\":\"first\",\"type\":\"attachment\"},{\"key\":\"second\",\"type\":\"attachment\"}]}}");
				this.runStore.putPending(this.runId, pending);
			});
			for (String prefix : List.of("ai:run:", "ai:runstate:", "ai:pending:", "ai:claim:", "ai:ledger:",
					"ai:idem:", "ai:seq:", "ai:xevents:", "ai:lease:run:", "ai:fence:run:", "ai:req:",
					"ai:decided:", "ai:lease:epoch:")) {
				this.support.own(dataset, RunStore.runScopedKey(prefix, this.support.cfg.tenantId,
						this.support.cfg.userId, this.runId));
			}
			this.support.ownHashField(dataset, RunStore.runScopedKey("ai:pending:", this.support.cfg.tenantId,
					this.support.cfg.userId, this.runId), this.toolCallId);
			this.support.own(dataset, com.example.configmgr.perf.redis.KeyShapes.sessionRunsKey(
					this.support.cfg.tenantId, this.support.cfg.userId, this.sessionId));
			this.support.own(dataset, com.example.configmgr.perf.redis.KeyShapes.sessionActiveKey(
					this.support.cfg.tenantId, this.support.cfg.userId, this.sessionId));
			this.support.own(dataset, com.example.configmgr.perf.redis.KeyShapes.sessionCurrentKey(
					this.support.cfg.tenantId, this.support.cfg.userId, this.sessionId));
			this.support.own(dataset, "ai:session:lock:" + this.support.cfg.tenantId + ":" + this.support.cfg.userId
					+ ":" + this.sessionId);
			this.support.own(dataset, this.purgeGuard.keyFor(this.sessionId));
			this.support.own(dataset, SessionPurgePendingStore.pendingKey(this.sessionId, this.support.cfg.tenantId,
					this.support.cfg.userId));
			this.support.own(dataset, this.memory.keyFor(this.sessionId));
			this.support.own(dataset, this.memory.keyFor(this.sessionId) + ":ver");
			this.support.own(dataset, this.memory.keyFor(this.sessionId) + ":gen");
			this.support.own(dataset, RedisChatMemoryRepository.stagingIndexKey(this.sessionId));
		}

		void seedAdditionalForm(Manifest.Dataset dataset, String runId, String toolCallId) {
			dataset.runIds.add(runId);
			this.support.identity(() -> {
				RunSnapshot snapshot = new RunSnapshot();
				snapshot.setRunId(runId);
				snapshot.setSessionId(this.sessionId);
				snapshot.setTenantId(this.support.cfg.tenantId);
				snapshot.setUserId(this.support.cfg.userId);
				snapshot.setStatus(RunSnapshot.DONE);
				snapshot.setCreatedAtMs(System.currentTimeMillis());
				snapshot.setUpdatedAtMs(System.currentTimeMillis());
				snapshot.setMessageJson(List.of());
				snapshot.freezeParams(new RunSnapshot.FrozenParams(null, null, null, true));
				this.runStore.save(snapshot);
				PendingToolCall pending = new PendingToolCall();
				pending.setToolCallId(toolCallId);
				pending.setName(GenerativeFormRules.TOOL_NAME);
				pending.setKind(PendingToolCall.KIND_FRONTEND);
				pending.setStatus(PendingToolCall.PENDING);
				pending.setArguments("{\"form\":{\"title\":\"perf-b\",\"fields\":[{\"key\":\"third\",\"type\":\"attachment\"}]}}");
				this.runStore.putPending(runId, pending);
			});
			for (String prefix : List.of("ai:run:", "ai:runstate:", "ai:pending:", "ai:claim:", "ai:ledger:",
					"ai:idem:", "ai:seq:", "ai:xevents:", "ai:lease:run:", "ai:fence:run:", "ai:req:",
					"ai:decided:", "ai:lease:epoch:")) {
				this.support.own(dataset, RunStore.runScopedKey(prefix, this.support.cfg.tenantId,
						this.support.cfg.userId, runId));
			}
			this.support.ownHashField(dataset, RunStore.runScopedKey("ai:pending:", this.support.cfg.tenantId,
					this.support.cfg.userId, runId), toolCallId);
		}

		FormAttachmentStore.Attachment upload(Manifest.Dataset dataset, String name, int bytes) {
			return uploadFor(dataset, this.runId, this.toolCallId, name, bytes);
		}

		FormAttachmentStore.Attachment uploadFor(Manifest.Dataset dataset, String runId, String toolCallId,
				String name, int bytes) {
			byte[] content = new byte[bytes];
			for (int i = 0; i < bytes; i++) {
				content[i] = (byte) ('a' + i % 26);
			}
			Set<String> before = new HashSet<>(this.support.identity(() -> this.store.findByForm(runId, toolCallId))
					.stream().map(FormAttachmentStore.Attachment::fileId)
					.collect(java.util.stream.Collectors.toSet()));
			MockMultipartFile file = new MockMultipartFile("file", name, "text/plain", content);
			this.support.identity(() -> {
				try {
					this.controller.upload(file, runId, toolCallId, this.sessionId);
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			});
			List<FormAttachmentStore.Attachment> after = this.support.identity(() -> this.store.findByForm(runId,
					toolCallId));
			FormAttachmentStore.Attachment uploaded = after.stream().filter(item -> !before.contains(item.fileId()))
					.findFirst().orElseThrow(() -> new IllegalStateException("upload did not add a registration"));
			own(dataset, uploaded);
			return uploaded;
		}

		FormAttachmentStore.Attachment registerControl(Manifest.Dataset dataset, String runId, String toolCallId,
				String sessionId, String name, int size) {
			StoredFile allocated = this.fileStorage.allocate(".txt");
			FormAttachmentStore.Attachment attachment = this.support.identity(() -> this.store.register(runId, toolCallId,
					sessionId, name, size, "text/plain", allocated.key()));
			own(dataset, attachment);
			return attachment;
		}

		FormAttachmentStore.Attachment register(Manifest.Dataset dataset, String runId, String toolCallId,
				String sessionId, String name, int size) {
			StoredFile stored;
			try {
				stored = this.fileStorage.store(java.io.InputStream.nullInputStream(), name);
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
			FormAttachmentStore.Attachment attachment = this.support.identity(() -> this.store.register(runId, toolCallId,
					sessionId, name, size, "text/plain", stored.key()));
			own(dataset, attachment);
			return attachment;
		}

		void own(Manifest.Dataset dataset, FormAttachmentStore.Attachment attachment) {
			this.support.own(dataset, registrationKey(attachment.fileId()));
			this.support.own(dataset, consumedKey(attachment.fileId()));
			this.support.own(dataset, sessionIndexKey(attachment.sessionId()));
			this.support.own(dataset, formIndexKey(attachment.runId(), attachment.toolCallId()));
		}

		void cleanupAndClose(Manifest.Dataset dataset, FormAttachmentStore.Attachment attachment) throws IOException {
			Throwable first = null;
			try {
				if (attachment != null) {
					try {
						this.support.identity(() -> this.store.deleteRegistration(attachment));
					}
					catch (Throwable ex) {
						first = ex;
					}
					try {
						this.support.identity(() -> this.store.deleteSessionIndex(attachment.sessionId()));
					}
					catch (Throwable ex) {
						if (first == null) {
							first = ex;
						}
						else {
							first.addSuppressed(ex);
						}
					}
					try {
						this.support.redis.delete(formIndexKey(attachment.runId(), attachment.toolCallId()));
					}
					catch (Throwable ex) {
						if (first == null) {
							first = ex;
						}
						else {
							first.addSuppressed(ex);
						}
					}
					try {
						if (attachment.storagePath() != null && !attachment.storagePath().isBlank()) {
							this.fileStorage.delete(attachment.storagePath());
						}
					}
					catch (Throwable ex) {
						if (first == null) {
							first = ex;
						}
						else {
							first.addSuppressed(ex);
						}
					}
				}
			}
			finally {
				try {
					close();
				}
				catch (Throwable ex) {
					if (first == null) {
						first = ex;
					}
					else {
						first.addSuppressed(ex);
					}
				}
			}
			if (first instanceof IOException ex) {
				throw ex;
			}
			if (first instanceof RuntimeException ex) {
				throw ex;
			}
			if (first instanceof Error ex) {
				throw ex;
			}
			if (first != null) {
				throw new IllegalStateException(first);
			}
		}

		String registrationKey(String fileId) {
			return "ai:form:att:" + identitySegment() + fileId;
		}

		String consumedKey(String fileId) {
			return "ai:form:att:used:" + identitySegment() + fileId;
		}

		String sessionIndexKey(String sessionId) {
			return "ai:form:att:sess:" + identitySegment() + sessionId;
		}

		String formIndexKey(String runId, String toolCallId) {
			return "ai:form:att:form:" + identitySegment() + runId + ":" + toolCallId;
		}

		private String identitySegment() {
			return TenantKeyScope.segmentOf(new TenantUser(this.support.cfg.tenantId, this.support.cfg.userId));
		}

		boolean fileExists(FormAttachmentStore.Attachment attachment) {
			return Files.exists(this.fileStorage.resolve(attachment.storagePath()));
		}

		void addIndexMember(Manifest.Dataset dataset, String indexKey, String fileId) {
			this.support.redis.opsForSet().add(indexKey, fileId);
			this.support.own(dataset, indexKey);
		}

		void close() throws IOException {
			deleteTree(this.root);
		}

		private static void deleteTree(Path path) throws IOException {
			if (!Files.exists(path)) {
				return;
			}
			try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
				List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
				for (Path item : paths) {
					Files.deleteIfExists(item);
				}
			}
		}
	}
}
