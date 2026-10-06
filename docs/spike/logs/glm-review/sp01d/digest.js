const dir = '<EVIDENCE_DIR>/';
const files = process.argv.slice(2);
for (const f of files) {
  const j = require(dir + f + '.json');
  const out = {
    case: f,
    seq: j.sse && j.sse.eventSequence,
    concat: j.sse && j.sse.concatenatedDeltaText,
    dupPrefixLen: j.sse && j.sse.duplicatedPrefixLen,
    terminal: j.sse && j.sse.terminalData,
    attempts: j.sessionState && j.sessionState.attempts,
    attemptRecords: j.sessionState && (j.sessionState.attemptRecords || []).map((a) => ({ n: a.attempt, outcome: a.outcome, costMs: a.costMs })),
    toolExec: (j.sessionState && j.sessionState.toolExecutionsForRun) || j.toolExecLog,
    refSubs: j.sessionState && j.sessionState.refSubscriptions,
    timeline: j.timeline,
    doneEvent: j.doneEvent,
    cancelResponse: j.cancelResponse,
    registry: j.cancelRegistryState,
    vd2: j.firstSubmit ? {
      start: { status: j.start.status, pending: j.start.pending },
      first: j.firstSubmit,
      second: j.secondSubmitSameId,
      third: j.thirdSubmitUnknownId,
      final: { status: j.finalState.status, round: j.finalState.round, messageCount: j.finalState.messageCount },
    } : undefined,
    spiDup: j.response ? { response: j.response, delta: j.toolExecLogDelta } : undefined,
  };
  console.log('=====', f);
  console.log(JSON.stringify(out, null, 1));
}
