package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.run.RunSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行信封的身份字段（租户任务3）：官方工具线程在快照读不到时要靠信封恢复<b>本轮身份</b>。
 *
 * <p>锁三件事：① 从快照冻结时带上 {@code tenantId/userId}；② 经 Map 承载（跨进程/序列化形态）
 * 不丢；③ 恢复出的快照带着同一份身份（不是当前线程身份，也不是默认租户）。
 */
class ToolExecutionEnvelopeIdentityTest {

	@Test
	void snapshotAndMapRoundTripPreserveTenantIdentity() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId("run-envelope-identity");
		snapshot.setSessionId("s-envelope");
		snapshot.setTenantId(9001L);
		snapshot.setUserId(-1L);
		snapshot.setCreatedBy("instance-a");
		snapshot.setCreatedAtMs(1234L);

		ToolExecutionEnvelope envelope = ToolExecutionEnvelope.fromSnapshot(snapshot, 7L);

		assertThat(envelope).isNotNull();
		assertThat(envelope.tenantId()).isEqualTo(9001L);
		assertThat(envelope.userId()).isEqualTo(-1L);
		assertThat(envelope.toSnapshot().getTenantId()).as("恢复出的快照必须带同一份租户").isEqualTo(9001L);
		assertThat(envelope.toSnapshot().getUserId()).as("系统账号 -1 原样带回，不当成缺失").isEqualTo(-1L);

		Map<String, Object> carried = new LinkedHashMap<>();
		carried.put("runId", envelope.runId());
		carried.put("sessionId", envelope.sessionId());
		carried.put("tenantId", envelope.tenantId());
		carried.put("userId", envelope.userId());
		carried.put("createdBy", envelope.createdBy());
		carried.put("createdAtMs", envelope.createdAtMs());
		Map<String, Object> toolContext = Map.of(ToolExecutionEnvelope.TOOL_CONTEXT_KEY, carried);

		ToolExecutionEnvelope fromMap = ToolExecutionEnvelope.fromToolContext(toolContext);

		assertThat(fromMap).isNotNull();
		assertThat(fromMap.tenantId()).isEqualTo(9001L);
		assertThat(fromMap.userId()).isEqualTo(-1L);
		assertThat(fromMap.toSnapshot().getTenantId()).isEqualTo(9001L);
		assertThat(fromMap.toSnapshot().getUserId()).isEqualTo(-1L);
	}

}
