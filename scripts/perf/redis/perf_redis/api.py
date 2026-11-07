"""HTTP access to the real project API (httpx only).

The tool never re-implements a deletion path: DELETE goes to
``/api/ai/session/{sessionId}`` and everything else is a real read endpoint.
Responses are recorded with status, wall-clock duration and a compact business
summary (complete/deferred/errors/found/phase/...).
"""

from __future__ import annotations

import re
import time
from typing import Dict, List, Optional, Tuple
from urllib.parse import quote

from .envcfg import Config


def _quote(value: str) -> str:
    return quote(str(value), safe="")


class ApiClient:
    def __init__(self, cfg: Config) -> None:
        import httpx

        self.cfg = cfg
        timeout = httpx.Timeout(cfg.http_timeout, connect=min(10.0, cfg.http_timeout))
        self._client = httpx.Client(
            base_url=cfg.app_base_url,
            timeout=timeout,
            verify=cfg.http_verify_tls,
            follow_redirects=False,
            trust_env=False,
            headers={"User-Agent": cfg.user_agent, "Accept": "application/json"},
        )

    def close(self) -> None:
        try:
            self._client.close()
        except Exception:
            pass

    # ------------------------------------------------------------------ headers
    def _identity_headers(self) -> Dict[str, str]:
        return {
            self.cfg.tenant_header: str(self.cfg.tenant_id),
            self.cfg.user_header: str(self.cfg.user_id),
        }

    # ------------------------------------------------------------------ core
    def _request(self, operation: str, method: str, path: str, *, with_identity: bool = True,
                 json_body: Optional[object] = None, params: Optional[Dict[str, object]] = None,
                 files: Optional[Dict[str, object]] = None, data: Optional[Dict[str, object]] = None,
                 headers: Optional[Dict[str, str]] = None,
                 timeout: Optional[float] = None) -> Dict[str, object]:
        request_headers: Dict[str, str] = {}
        if with_identity:
            request_headers.update(self._identity_headers())
        if headers:
            request_headers.update(headers)
        timeout_seconds: Optional[float] = None
        if timeout is not None:
            timeout_seconds = max(0.001, float(timeout))
        start = time.perf_counter()
        status: Optional[int] = None
        text = ""
        body: Optional[object] = None
        error = ""
        timed_out = False
        response_headers: Dict[str, str] = {}
        try:
            kwargs: Dict[str, object] = {
                "headers": request_headers,
                "json": json_body,
                "params": params,
                "files": files,
                "data": data,
            }
            if timeout_seconds is not None:
                import httpx

                kwargs["timeout"] = httpx.Timeout(
                    timeout_seconds, connect=min(10.0, timeout_seconds))
            response = self._client.request(method, path, **kwargs)
            status = int(response.status_code)
            text = response.text
            response_headers = {k.lower(): v for k, v in response.headers.items()}
            content_type = response_headers.get("content-type", "")
            if "json" in content_type or text.strip().startswith("{") or text.strip().startswith("["):
                try:
                    body = response.json()
                except Exception:
                    body = None
            else:
                body = None
        except Exception as exc:  # transport errors are measurement facts
            error = type(exc).__name__
            try:
                import httpx

                timed_out = isinstance(exc, (httpx.TimeoutException, TimeoutError))
            except Exception:
                timed_out = isinstance(exc, TimeoutError) or "timed out" in str(exc).lower() or "timeout" in str(exc).lower()
        duration_ms = (time.perf_counter() - start) * 1000.0
        ok = status is not None and 200 <= status < 300 and not error
        result: Dict[str, object] = {
            "operation": operation,
            "method": method,
            "path": path,
            "status": status,
            "ok": ok,
            "duration_ms": round(duration_ms, 3),
            "transport_error": error,
            "timed_out": timed_out,
            "request_timeout_s": timeout_seconds,
            "server_timing": response_headers.get("server-timing", ""),
            "content_type": response_headers.get("content-type", ""),
            "business": self._business(operation, body),
            "body_keys": sorted(body.keys()) if isinstance(body, dict) else [],
            "text_prefix": "",
        }
        return result

    # --------------------------------------------------------------- business
    def _unwrap(self, body: object) -> object:
        if isinstance(body, dict) and isinstance(body.get("success"), bool):
            return body.get("data")
        return body

    def _business(self, operation: str, body: object) -> Dict[str, object]:
        data = self._unwrap(body)
        out: Dict[str, object] = {}
        if operation.startswith("delete"):
            if not isinstance(data, dict):
                return out
            out["complete"] = data.get("complete")
            out["found"] = data.get("found")
            out["aborted"] = data.get("aborted")
            out["phase"] = data.get("phase")
            out["purgeId"] = data.get("purgeId")
            deferred = data.get("deferred")
            errors = data.get("errors")
            warnings = data.get("warnings")
            out["deferred"] = [item.get("runId") for item in deferred if isinstance(item, dict)] if isinstance(deferred, list) else []
            out["errors"] = list(errors) if isinstance(errors, list) else []
            out["warnings"] = list(warnings) if isinstance(warnings, list) else []
            runs = data.get("runs")
            out["runs"] = [
                {
                    "runId": item.get("runId"),
                    "status": item.get("status"),
                    "active": item.get("active"),
                    "deleted": item.get("deleted"),
                    "deferred": item.get("deferred"),
                }
                for item in runs if isinstance(item, dict)
            ] if isinstance(runs, list) else []
            for key in ("database", "memory", "transcript", "context", "attachments", "session"):
                value = data.get(key)
                if isinstance(value, dict):
                    out[key] = value
            return out
        if operation in ("history", "history_before"):
            if isinstance(data, dict):
                out["total"] = data.get("total")
                out["count"] = data.get("count")
                out["hasMore"] = data.get("hasMore")
                out["nextCursor"] = data.get("nextCursor")
                out["revision"] = data.get("revision")
                out["truncated"] = data.get("truncated")
                messages = data.get("messages")
                out["messages"] = len(messages) if isinstance(messages, list) else None
                entry_ids: List[str] = []
                entry_indexes: List[int] = []
                if isinstance(messages, list):
                    for item in messages:
                        if not isinstance(item, dict):
                            continue
                        entry_id = item.get("entryId")
                        if entry_id is not None and str(entry_id):
                            entry_ids.append(str(entry_id))
                        text = str(item.get("text") or "")
                        match = re.match(r"perf transcript entry (\d+) for ", text)
                        if match:
                            entry_indexes.append(int(match.group(1)))
                out["entryIds"] = entry_ids
                out["entryIndexes"] = entry_indexes
            return out
        if operation == "context":
            if isinstance(data, dict):
                for key in ("usedTokens", "limitTokens", "measured", "level"):
                    if key in data:
                        out[key] = data.get(key)
                out["keys"] = sorted(data.keys())
            return out
        if operation == "runs_current":
            if isinstance(data, dict):
                out["found"] = data.get("found")
                out["snapshotStatus"] = data.get("snapshotStatus")
                out["archiveMaxSeq"] = data.get("archiveMaxSeq")
                entries = data.get("pendingEntries")
                out["pendingEntries"] = len(entries) if isinstance(entries, list) else None
            return out
        if operation == "runs_list":
            if isinstance(data, list):
                out["entries"] = len(data)
            return out
        if operation == "startup_resume":
            if isinstance(data, dict):
                out["keys"] = sorted(data.keys())
                for key in ("scanned", "reason"):
                    if key in data:
                        out[key] = data.get(key)
            return out
        if operation == "run_detail":
            if isinstance(data, dict):
                out["exists"] = data.get("exists")
                out["status"] = data.get("status")
                out["keys"] = sorted(data.keys())
            return out
        if operation == "events_probe":
            if isinstance(data, dict):
                out["keys"] = sorted(data.keys())
                for key in ("ready", "runId", "reason", "code"):
                    if key in data:
                        out[key] = data.get(key)
            return out
        if operation == "pending":
            if isinstance(data, list):
                out["entries"] = len(data)
            return out
        if operation == "cancel_status":
            if isinstance(data, dict):
                out["cancelled"] = data.get("cancelled") or data.get("cancelState")
                out["keys"] = sorted(data.keys())
            return out
        if operation == "health":
            if isinstance(data, dict):
                out["available"] = data.get("available")
                out["code"] = data.get("code")
                out["model"] = data.get("model")
            return out
        if operation in ("kb_status", "kb_reload"):
            if isinstance(data, dict):
                out["enabled"] = data.get("enabled")
                out["ready"] = data.get("ready")
                out["doc_count"] = data.get("doc_count")
                out["rebuilt"] = data.get("rebuilt")
            return out
        if operation in ("feedback_create", "feedback_cancel"):
            if isinstance(data, dict):
                out["saved"] = data.get("saved")
                out["cancelled"] = data.get("cancelled")
            return out
        if operation == "feedback_list":
            if isinstance(data, list):
                out["entries"] = len(data)
                out["messageIds"] = [item.get("messageId") for item in data if isinstance(item, dict)]
            return out
        if operation == "upload_attachment":
            if isinstance(data, dict):
                out["fileId"] = data.get("fileId")
                out["fileName"] = data.get("fileName")
                out["size"] = data.get("size")
            return out
        if operation == "tenants":
            if isinstance(data, list):
                out["tenants"] = [
                    {
                        "tenantId": item.get("tenantId"),
                        "users": [user.get("userId") for user in item.get("users", []) if isinstance(user, dict)],
                    }
                    for item in data if isinstance(item, dict)
                ]
            return out
        if isinstance(data, dict):
            out["keys"] = sorted(data.keys())
        return out

    # ------------------------------------------------------------------ APIs
    # Measurement passes an explicit timeout so a single request can never
    # outlive the scenario/total budget. Other callers keep the client default.
    def tenants(self) -> Dict[str, object]:
        return self._request("tenants", "GET", "/api/ai/tenants", with_identity=False)

    def health(self, timeout: Optional[float] = None) -> Dict[str, object]:
        return self._request("health", "GET", "/api/ai/health", with_identity=False, timeout=timeout)

    def delete_session(self, session_id: str, operation: str = "delete",
                       timeout: Optional[float] = None) -> Dict[str, object]:
        return self._request(operation, "DELETE", "/api/ai/session/" + _quote(session_id),
                             timeout=timeout)

    def history(self, session_id: str, limit: int = 20, before: Optional[int] = None,
                timeout: Optional[float] = None, operation: str = "history") -> Dict[str, object]:
        params: Dict[str, object] = {"latest": limit}
        if before is not None:
            params["before"] = before
        return self._request(operation, "GET", "/api/ai/history/" + _quote(session_id),
                             params=params, timeout=timeout)

    def context(self, session_id: str, timeout: Optional[float] = None) -> Dict[str, object]:
        return self._request("context", "GET", "/api/ai/context/" + _quote(session_id),
                             timeout=timeout)

    def runs_current(self, session_id: str, timeout: Optional[float] = None) -> Dict[str, object]:
        return self._request("runs_current", "GET", "/api/ai/runs/current",
                             params={"sessionId": session_id}, timeout=timeout)

    def runs_list(self, timeout: Optional[float] = None) -> Dict[str, object]:
        return self._request("runs_list", "GET", "/api/ai/runs", timeout=timeout)

    def startup_resume(self, timeout: Optional[float] = None) -> Dict[str, object]:
        return self._request("startup_resume", "GET", "/api/ai/startup-resume", timeout=timeout)

    def run_detail(self, run_id: str, timeout: Optional[float] = None) -> Dict[str, object]:
        return self._request("run_detail", "GET", "/api/ai/runs/" + _quote(run_id),
                             timeout=timeout)

    def events_probe(self, run_id: str, timeout: Optional[float] = None) -> Dict[str, object]:
        return self._request("events_probe", "GET", "/api/ai/events/" + _quote(run_id),
                             params={"probe": 1}, headers={"Accept": "application/json"},
                             timeout=timeout)

    def cancel_status(self, run_id: str, timeout: Optional[float] = None) -> Dict[str, object]:
        return self._request("cancel_status", "GET", "/api/ai/cancel/" + _quote(run_id),
                             timeout=timeout)

    def pending(self, run_id: str, timeout: Optional[float] = None) -> Dict[str, object]:
        return self._request("pending", "GET", "/api/ai/pending/" + _quote(run_id),
                             timeout=timeout)

    def kb_status(self, timeout: Optional[float] = None) -> Dict[str, object]:
        return self._request("kb_status", "GET", "/api/ai/kb/status", timeout=timeout)

    def kb_reload(self, timeout: Optional[float] = None) -> Dict[str, object]:
        return self._request("kb_reload", "POST", "/api/ai/kb/reload", timeout=timeout)

    def upload_attachment(self, run_id: str, tool_call_id: str, session_id: str,
                          field_key: str, filename: str, content: bytes,
                          content_type: str = "text/plain") -> Dict[str, object]:
        files = {"file": (filename, content, content_type)}
        data = {
            "runId": run_id,
            "toolCallId": tool_call_id,
            "sessionId": session_id,
            "fieldKey": field_key,
        }
        return self._request("upload_attachment", "POST", "/api/ai/form/attachments",
                             files=files, data=data)

    def feedback_create(self, message_id: str, session_id: str, rating: int = 1) -> Dict[str, object]:
        return self._request("feedback_create", "POST", "/api/ai/feedback",
                             json_body={"messageId": message_id, "sessionId": session_id, "rating": rating})

    def feedback_cancel(self, message_id: str, session_id: str) -> Dict[str, object]:
        return self._request("feedback_cancel", "POST", "/api/ai/feedback",
                             json_body={"messageId": message_id, "sessionId": session_id, "rating": 0})

    def feedback_list(self) -> Dict[str, object]:
        return self._request("feedback_list", "GET", "/api/ai/feedback")
