package com.example.configmgr.ai.run;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code RunSnapshot.runEpoch} 的兼容性（B23L1 批②a / 方案第 8 条 · E7）。
 *
 * <p>快照是"上一版进程写、这一版进程读"的数据：新增字段必须<b>缺省可读</b>
 * （旧 JSON 没有 {@code runEpoch} ⇒ 0 = 无租约写入），并在取证视图 {@link RunSnapshot#asMap()}
 * 里透出（排障要能回答"这条状态是哪一任租约写的"）。
 */
class RunSnapshotEpochCompatTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void oldJsonWithoutRunEpochDeserializesAsZero() throws Exception {
		String legacy = "{\"runId\":\"r-old\",\"sessionId\":\"s-1\",\"status\":\"SUSPENDED\",\"round\":2}";

		RunSnapshot snapshot = this.mapper.readValue(legacy, RunSnapshot.class);

		assertThat(snapshot.getRunEpoch()).as("旧数据无 runEpoch ⇒ 0（= 无租约写入）").isZero();
		assertThat(snapshot.getRunId()).isEqualTo("r-old");
	}

	@Test
	void runEpochSurvivesARoundTripAndAppearsInTheEvidenceView() throws Exception {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId("r-epoch");
		snapshot.setSessionId("s-1");
		snapshot.setRunEpoch(7L);

		RunSnapshot back = this.mapper.readValue(this.mapper.writeValueAsString(snapshot), RunSnapshot.class);

		assertThat(back.getRunEpoch()).isEqualTo(7L);
		Map<String, Object> map = back.asMap();
		assertThat(map).as("取证视图新增 runEpoch（additive）").containsEntry("runEpoch", 7L);
	}

}
