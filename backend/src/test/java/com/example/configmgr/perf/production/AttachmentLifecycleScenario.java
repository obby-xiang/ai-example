package com.example.configmgr.perf.production;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.example.configmgr.ai.form.FormAttachmentStore;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.perf.redis.CountingRedis;
import com.example.configmgr.perf.redis.Manifest;
import org.springframework.mock.web.MockMultipartFile;

final class AttachmentLifecycleScenario implements ProductionScenario {

	@Override
	public String id() {
		return "attachment_full_lifecycle";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) throws Exception {
		AttachmentScenarioSupport.Fixture fixture = AttachmentScenarioSupport.fixture(support, dataset.sessionId + "-run-a",
				"call-a", dataset.sessionId);
		dataset.attributes.put("fixtureRoot", fixture.root.toString());
		try {
			fixture.seedRun(dataset);
			support.stage(dataset, "multi_form_registration", () -> {
				FormAttachmentStore.Attachment first = fixture.upload(dataset, "first.txt", 1);
				FormAttachmentStore.Attachment second = fixture.upload(dataset, "second.txt", 100);
				FormAttachmentStore.Attachment retained = fixture.registerControl(dataset, "control-run", "control-call",
						"control-session", "retained.txt", 1);
				dataset.attributes.put("first", first.fileId());
				dataset.attributes.put("second", second.fileId());
				dataset.attributes.put("retained", retained.fileId());
				dataset.attributes.put("retainedAttachmentJson", controlJson(fixture, retained));
			});
			support.stage(dataset, "second_form_registration", () -> {
				String runB = fixture.runId + "-b";
				String callB = "call-b";
				fixture.seedAdditionalForm(dataset, runB, callB);
				FormAttachmentStore.Attachment third = fixture.uploadFor(dataset, runB, callB, "third.txt", 1_000);
				dataset.attributes.put("third", third.fileId());
				dataset.attributes.put("runB", runB);
				dataset.attributes.put("callB", callB);
			});
			return dataset;
		}
		catch (Exception ex) {
			FormAttachmentStore.Attachment retained = controlAttachment(fixture, dataset, "control-run", "control-call", "control-session");
			fixture.cleanupAndClose(dataset, retained);
			support.check(dataset, "generate_failure_root_removed", !java.nio.file.Files.exists(fixture.root),
					"generate failure removes the unique fixture root and control registration");
			throw ex;
		}
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) throws Exception {
		AttachmentScenarioSupport.Fixture fixture = AttachmentScenarioSupport.fixture(support, dataset.sessionId + "-run-a",
				"call-a", dataset.sessionId, String.valueOf(dataset.attributes.get("fixtureRoot")));
		long scanBefore = support.commandMetrics.keyspaceScanPages();
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(support.cfg.tenantId,
				support.cfg.userId)) {
			FormAttachmentStore.Attachment first = attachment(dataset, fixture, "first");
			FormAttachmentStore.Attachment second = attachment(dataset, fixture, "second");
			FormAttachmentStore.Attachment third = attachment(dataset, fixture, "third");
			support.require(dataset, "two_forms_and_session_index",
					fixture.store.findByForm(fixture.runId, fixture.toolCallId).size() == 2
							&& fixture.store.findBySessionId(fixture.sessionId).size() == 3,
					"two attachments on the first form and three on the session");
			support.require(dataset, "both_index_families_present",
					member(fixture, fixture.formIndexKey(fixture.runId, fixture.toolCallId), first.fileId())
							&& member(fixture, fixture.sessionIndexKey(fixture.sessionId), first.fileId()),
					"form and session indexes both contain the uploaded id");

			support.stage(dataset, "multi_attachment_limit_rejected", () -> {
				boolean rejected = false;
				try {
					fixture.upload(dataset, "over-limit.txt", 8);
				}
				catch (RuntimeException ex) {
					rejected = true;
				}
				support.require(dataset, "multi_attachment_limit_has_no_side_effect", rejected
						&& fixture.store.findByForm(fixture.runId, fixture.toolCallId).size() == 2,
						"the third attachment on a two-field form is rejected without a third registration");
			});

			support.stage(dataset, "zero_byte_rejected", () -> {
				boolean rejected = false;
				try {
					fixture.controller.upload(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]),
							fixture.runId, fixture.toolCallId, fixture.sessionId);
				}
				catch (Exception ex) {
					rejected = true;
				}
				support.require(dataset, "empty_file_rejected_without_write", rejected
						&& fixture.store.findByForm(fixture.runId, fixture.toolCallId).size() == 2,
						"empty upload is rejected before storage or registration");
			});

			support.stage(dataset, "accepted_consume_and_leftover_reclaim", () -> support.identity(() -> {
				fixture.guard.settleAcceptedResult(fixture.runId, fixture.toolCallId,
						"{\"form\":{\"fields\":[{\"key\":\"first\",\"type\":\"attachment\"}]}}",
						"{\"first\":\"" + first.fileId() + "\"}");
			}));
			support.require(dataset, "consume_tombstone_and_metadata_removed",
					fixture.store.wasConsumed(first.fileId()) && fixture.store.find(first.fileId()) == null
							&& fixture.store.find(second.fileId()) == null,
					"accepted id has a tombstone; accepted and uncommitted registrations are reclaimed");
			support.require(dataset, "accepted_and_leftover_files_removed",
					!fixture.fileExists(first) && !fixture.fileExists(second),
					"accepted and uncommitted physical files are both removed");
			support.require(dataset, "third_form_untouched", fixture.store.find(third.fileId()) != null
					&& fixture.fileExists(third), "another form in the same session keeps its registration and file");
			support.require(dataset, "second_consume_is_not_double_delete", fixture.store.wasConsumed(first.fileId())
					&& !fixture.store.consume(first.fileId()), "second consume is rejected by the tombstone");

			String runB = String.valueOf(dataset.attributes.get("runB"));
			String callB = String.valueOf(dataset.attributes.get("callB"));
			fixture.addIndexMember(dataset, fixture.formIndexKey(runB, callB), "stale-form-member");
			fixture.addIndexMember(dataset, fixture.sessionIndexKey(fixture.sessionId), "stale-session-member");
			CountingRedis.Snapshot touchBefore = support.commandMetrics.snapshot();
			long ttlBefore = support.redis.getExpire(fixture.registrationKey(third.fileId()), TimeUnit.MILLISECONDS);
			support.stage(dataset, "batched_touch_pipeline", () -> support.identity(() ->
					fixture.guard.touchSuspension(runB, callB)));
			CountingRedis.Snapshot touchDelta = CountingRedis.delta(touchBefore, support.commandMetrics.snapshot());
			dataset.attributes.put("touchCommands", touchDelta.commands());
			dataset.attributes.put("touchRequestBytes", touchDelta.requestBytes());
			long ttlAfter = support.redis.getExpire(fixture.registrationKey(third.fileId()), TimeUnit.MILLISECONDS);
			support.require(dataset, "touch_pipeline_renews_live_only", ttlAfter > 0L && ttlAfter >= ttlBefore
					&& support.redis.opsForValue().get(fixture.registrationKey("stale-form-member")) == null,
					"touch renews live registration and never recreates stale metadata");
			support.require(dataset, "touch_stage_has_real_io", touchDelta.commands() > 0L
					&& touchDelta.requestBytes() > 0L, "touch stage records actual commands and request bytes");

			support.stage(dataset, "stale_members_filtered_before_purge", () -> {
				List<FormAttachmentStore.Attachment> live = fixture.store.findBySessionId(fixture.sessionId);
				support.require(dataset, "stale_index_members_skipped", live.size() == 1
						&& live.get(0).fileId().equals(third.fileId()),
						"stale form/session ids do not produce attachments or files");
			});
			AiSessionPurgeResultView purge = support.stage(dataset, "session_purge_reuses_metadata", () ->
					new AiSessionPurgeResultView(support.identity(() -> fixture.purge.purge(fixture.sessionId,
							"attachment-lifecycle"))));
			dataset.attributes.put("purgeResult", purge.result.toString());
			support.require(dataset, "purge_completes_and_reuses_single_registration",
					purge.result.complete() && purge.result.attachments().registered() == 1
							&& purge.result.attachments().registrationsDeleted() == 1
							&& purge.result.attachments().filesDeleted() == 1,
					"real purge reuses the enumerated Attachment and deletes one registration/file");
			support.require(dataset, "session_index_exactly_deleted",
					!support.redis.hasKey(fixture.sessionIndexKey(fixture.sessionId)),
					"session index is removed by exact DEL, including stale members");
			support.require(dataset, "third_file_removed_and_control_retained", !fixture.fileExists(third)
					&& support.redis.hasKey(fixture.registrationKey(String.valueOf(dataset.attributes.get("retained")))),
					"purged file is gone while the independent retained registration remains");
			support.require(dataset, "retained_control_file_not_deleted", java.nio.file.Files.exists(fixture.retainedFile),
					"retained physical control sample remains outside the purge target");
			support.require(dataset, "no_keyspace_scan", support.commandMetrics.keyspaceScanPages() == scanBefore,
					"lifecycle uses exact indexes only; no KEYS/FLUSH/full-keyspace SCAN");
			dataset.state = "measured";
		}
		finally {
			FormAttachmentStore.Attachment retained = controlAttachment(fixture, dataset, "control-run", "control-call", "control-session");
			fixture.cleanupAndClose(dataset, retained);
			support.require(dataset, "scenario_control_registration_indexes_and_root_removed",
					!support.redis.hasKey(fixture.registrationKey(String.valueOf(dataset.attributes.get("retained"))))
							&& !support.redis.hasKey(fixture.consumedKey(String.valueOf(dataset.attributes.get("retained"))))
							&& !support.redis.hasKey(fixture.sessionIndexKey("control-session"))
							&& !support.redis.hasKey(fixture.formIndexKey("control-run", "control-call"))
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
					"retained.txt", 1L, "text/plain", null);
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

	private static FormAttachmentStore.Attachment attachment(Manifest.Dataset dataset,
			AttachmentScenarioSupport.Fixture fixture, String attribute) {
		return fixture.store.find(String.valueOf(dataset.attributes.get(attribute)));
	}

	private static boolean member(AttachmentScenarioSupport.Fixture fixture, String key, String value) {
		return Boolean.TRUE.equals(fixture.support.redis.opsForSet().isMember(key, value));
	}

	private record AiSessionPurgeResultView(com.example.configmgr.ai.session.AiSessionPurgeService.PurgeResult result) {
	}
}
