package com.example.configmgr.perf.production;

import com.example.configmgr.ai.form.FormAttachmentStore;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.AiStateUnavailableException;
import com.example.configmgr.perf.redis.Manifest;

final class AttachmentFailureScenario implements ProductionScenario {

	@Override
	public String id() {
		return "attachment_failure_rollback";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) throws Exception {
		AttachmentScenarioSupport.Fixture fixture = AttachmentScenarioSupport.fixture(support, dataset.sessionId + "-run-f",
				"call-f", dataset.sessionId);
		dataset.attributes.put("fixtureRoot", fixture.root.toString());
		try {
			fixture.seedRun(dataset);
			FormAttachmentStore.Attachment retained = fixture.registerControl(dataset, "control-run-f", "control-call-f",
					"control-session-f", "retained-failure.txt", 1);
			dataset.attributes.put("retained", retained.fileId());
			dataset.attributes.put("retainedAttachmentJson", controlJson(fixture, retained));
			return dataset;
		}
		catch (Exception ex) {
			FormAttachmentStore.Attachment retained = controlAttachment(fixture, dataset, "control-run-f", "control-call-f", "control-session-f");
			fixture.cleanupAndClose(dataset, retained);
			support.check(dataset, "generate_failure_root_removed", !java.nio.file.Files.exists(fixture.root),
					"generate failure removes the unique fixture root and control registration");
			throw ex;
		}
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) throws Exception {
		AttachmentScenarioSupport.Fixture fixture = AttachmentScenarioSupport.fixture(support, dataset.sessionId + "-run-f",
				"call-f", dataset.sessionId, String.valueOf(dataset.attributes.get("fixtureRoot")));
		long scanBefore = support.commandMetrics.keyspaceScanPages();
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(support.cfg.tenantId,
				support.cfg.userId)) {
			support.stage(dataset, "index_write_failure_rolls_back_registration_and_file", () -> {
				if (!support.faultEnabled("index_eval")) {
					support.faultSkipped(dataset, "index_eval");
					return;
				}
				dataset.attributes.put("fault_index_eval", "first form index EVAL rejected once");
				support.faults.failNext("eval", fixture.formIndexKey(fixture.runId, fixture.toolCallId));
				boolean rejected = false;
				try {
					fixture.upload(dataset, "index-failure.txt", 16);
				}
				catch (RuntimeException ex) {
					rejected = true;
				}
				support.require(dataset, "index_failure_rejects_upload", rejected,
						"an index write failure fails the upload instead of degrading to an in-memory registry");
				support.require(dataset, "index_failure_registration_rolled_back",
						fixture.store.findByForm(fixture.runId, fixture.toolCallId).isEmpty()
								&& fixture.store.findBySessionId(fixture.sessionId).isEmpty(),
						"registration and both indexes have no residual member");
				support.require(dataset, "index_failure_physical_file_rolled_back",
						countFiles(fixture.storage) == 0L,
						"controller removes the just-stored file after registration failure");
			});

			support.stage(dataset, "registration_rollback_failure_is_retained_for_recovery", () -> {
				if (!support.faultEnabled("registration_delete")) {
					support.faultSkipped(dataset, "registration_delete");
					return;
				}
				dataset.attributes.put("fault_registration_delete", "rollback DEL and SREM fail after index EVAL failure");
				support.faults.failNext("eval", fixture.formIndexKey(fixture.runId, fixture.toolCallId));
				support.faults.failNext("del", "att_");
				support.faults.failNext("srem", fixture.sessionIndexKey(fixture.sessionId));
				boolean rejected = false;
				try {
					fixture.upload(dataset, "rollback-failure.txt", 24);
				}
				catch (RuntimeException ex) {
					rejected = true;
				}
				support.require(dataset, "rollback_failure_rejects_upload", rejected,
						"upload fails even when best-effort registration rollback also fails");
				java.util.List<FormAttachmentStore.Attachment> leftovers = fixture.store.findBySessionId(fixture.sessionId);
				support.require(dataset, "rollback_failure_leaves_recovery_metadata", leftovers.size() == 1,
						"registration remains for TTL/retry evidence instead of being falsely reported clean");
				fixture.own(dataset, leftovers.get(0));
				dataset.attributes.put("rollbackFailureRetained", true);
			});

			FormAttachmentStore.Attachment consumed = fixture.upload(dataset, "consume.txt", 32);
			support.stage(dataset, "tombstone_failure_preserves_registration_and_file", () -> {
				if (!support.faultEnabled("tombstone_set")) {
					support.faultSkipped(dataset, "tombstone_set");
					return;
				}
				dataset.attributes.put("fault_tombstone_set", "consume tombstone SET rejected once");
				support.faults.failNext("set", fixture.consumedKey(consumed.fileId()));
				boolean failed = false;
				try {
					fixture.store.consume(consumed.fileId());
				}
				catch (AiStateUnavailableException ex) {
					failed = true;
				}
				support.require(dataset, "tombstone_failure_is_fail_closed", failed,
						"tombstone write failure is surfaced, not converted into consumed=false");
				support.require(dataset, "tombstone_failure_preserves_registration_and_file",
						fixture.store.find(consumed.fileId()) != null && fixture.fileExists(consumed)
								&& !fixture.store.wasConsumed(consumed.fileId()),
						"registration and physical file remain available for retry; no tombstone exists");
			});
			if (support.faultEnabled("tombstone_set")) {
				support.stage(dataset, "consume_retry_after_tombstone_recovery", () -> {
					fixture.guard.settleAcceptedResult(fixture.runId, fixture.toolCallId,
							"{\"form\":{\"fields\":[{\"key\":\"first\",\"type\":\"attachment\"}]}}",
							"{\"first\":\"" + consumed.fileId() + "\"}");
					support.require(dataset, "consume_retry_writes_tombstone_and_removes_file",
							fixture.store.wasConsumed(consumed.fileId()) && !fixture.fileExists(consumed),
							"retry creates the tombstone and the guard removes the physical file");
				});
			}

			support.stage(dataset, "stale_and_polluted_index_fail_closed", () -> {
				FormAttachmentStore.Attachment other = fixture.register(dataset, "other-run", "other-call",
						"other-session", "other.txt", 8);
				fixture.addIndexMember(dataset, fixture.sessionIndexKey(fixture.sessionId), "stale-only");
				fixture.addIndexMember(dataset, fixture.sessionIndexKey(fixture.sessionId), other.fileId());
				fixture.addIndexMember(dataset, fixture.formIndexKey(fixture.runId, fixture.toolCallId), other.fileId());
				support.require(dataset, "stale_index_member_filtered",
						fixture.store.findByForm(fixture.runId, fixture.toolCallId).size() == 1,
						"accepted-result closeout reclaimed the rollback leftover; only other.txt remains readable");
				boolean sessionPollution = false;
				try {
					fixture.store.findBySessionId(fixture.sessionId);
				}
				catch (AiStateUnavailableException ex) {
					sessionPollution = true;
				}
				boolean formPollution = false;
				try {
					fixture.store.findByForm(fixture.runId, fixture.toolCallId);
				}
				catch (AiStateUnavailableException ex) {
					formPollution = true;
				}
				support.require(dataset, "polluted_indexes_fail_closed", sessionPollution && formPollution,
						"wrong-session and wrong-form ownership are never folded into a partial enumeration");
			});

			support.stage(dataset, "exact_session_index_cleanup", () -> {
				fixture.store.deleteSessionIndex(fixture.sessionId);
				support.require(dataset, "only_target_session_index_deleted",
						!support.redis.hasKey(fixture.sessionIndexKey(fixture.sessionId))
								&& support.redis.hasKey(fixture.sessionIndexKey("control-session-f")),
						"exact DEL removes only the target session index and preserves the control index");
				support.require(dataset, "retained_control_sample_and_file", support.redis.hasKey(
						fixture.registrationKey(String.valueOf(dataset.attributes.get("retained"))))
								&& java.nio.file.Files.exists(fixture.retainedFile),
						"retained control registration and physical sample survive all fault paths");
				support.require(dataset, "failure_paths_no_keyspace_scan",
						support.commandMetrics.keyspaceScanPages() == scanBefore,
						"fault and cleanup paths use exact keys only");
			});
			dataset.state = "measured";
		}
		finally {
			FormAttachmentStore.Attachment retained = controlAttachment(fixture, dataset, "control-run-f", "control-call-f", "control-session-f");
			fixture.cleanupAndClose(dataset, retained);
			support.require(dataset, "scenario_control_registration_indexes_and_root_removed",
					!support.redis.hasKey(fixture.registrationKey(String.valueOf(dataset.attributes.get("retained"))))
							&& !support.redis.hasKey(fixture.consumedKey(String.valueOf(dataset.attributes.get("retained"))))
							&& !support.redis.hasKey(fixture.sessionIndexKey("control-session-f"))
							&& !support.redis.hasKey(fixture.formIndexKey("control-run-f", "control-call-f"))
							&& !java.nio.file.Files.exists(fixture.root),
					"scenario-owned control registration, tombstone, indexes and temporary root are removed");
		}
	}

	private static String controlJson(AttachmentScenarioSupport.Fixture fixture, FormAttachmentStore.Attachment attachment) {
		try {
			return fixture.support.mapper.writeValueAsString(attachment);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static FormAttachmentStore.Attachment controlAttachment(AttachmentScenarioSupport.Fixture fixture,
			Manifest.Dataset dataset, String runId, String toolCallId, String sessionId) {
		try {
			String json = String.valueOf(dataset.attributes.get("retainedAttachmentJson"));
			return fixture.support.mapper.readValue(json, FormAttachmentStore.Attachment.class);
		}
		catch (Throwable ex) {
			FormAttachmentStore.Attachment fallback = new FormAttachmentStore.Attachment(
					String.valueOf(dataset.attributes.get("retained")), runId, toolCallId, sessionId,
					"retained-failure.txt", 1L, "text/plain", null);
			try {
				fixture.cleanupAndClose(dataset, fallback);
			}
			catch (Throwable cleanupError) {
				ex.addSuppressed(cleanupError);
			}
			if (ex instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException(ex);
		}
	}

	private static long countFiles(java.nio.file.Path directory) {
		try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(directory)) {
			return files.count();
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
