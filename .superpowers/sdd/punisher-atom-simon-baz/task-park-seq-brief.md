# Park frame ordering and replay lower bound review

Scope: current uncommitted seven-file park/SSE patch in the integration tree, based on ae883fbae52be36e45a635ee276cf8298ac24bd2.

Requirements from approved plan and observed failures:
- Before the model loop, an already-emitted gate request must publish its tool_start before a suspended frame, without duplicate tool_start on resume. Preserve rejection behavior and ordinary streaming order.
- A frontend part with requiresAction=true must not be changed back to a read-only tool call by a late tool_start. Other part merge semantics remain unchanged.
- A reattach with ?lastSeq=N must not receive wake-driven replay of frames at or below N. A new subscriber without a cursor must not be starved by another subscriber's cursor. Persisted replay and ordered live delivery must retain their existing contracts.
- No blocking human-wait thread is restored. No session affinity or provider-specific behavior is introduced. No secrets, local real topology, or changes to ports 8081/5202.
- Tests must be deterministic for suspend/tool_start order and subscriber wake floors, plus the frontend pending-card regression. Real three-instance and @real validation remain separate later gates; unit tests alone do not prove them.
