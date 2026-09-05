from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Mapping, Sequence
from typing import Any


MAX_TRACE_SUMMARY_CHARS = 200
MAX_TRACE_COLLECTION_ITEMS = 50

_SENSITIVE_KEY_PARTS = (
    "apikey",
    "api_key",
    "authorization",
    "credential",
    "cookie",
    "password",
    "secret",
    "token",
)
_BODY_KEY_EXACT = {
    "answer",
    "answerdeltas",
    "body",
    "chunktext",
    "content",
    "delta",
    "file",
    "filecontent",
    "goal",
    "input",
    "instructions",
    "markdown",
    "output",
    "payload",
    "prompt",
    "rawinput",
    "rawoutput",
    "rawprompt",
    "promptfragment",
    "skillbody",
    "skillinstructions",
    "text",
    "upload",
    "uploadedfile",
}
_BODY_KEY_EXCEPTIONS = {
    "answermode",
    "answerstatus",
    "answerboundary",
    "answerquality",
    "promptpolicy",
    "promptversion",
    "promptchars",
}
_SUMMARY_KEYS = {"description", "message", "reason", "summary", "title"}
_AUTHORIZATION_BOUNDARY_KEYS = {
    "version",
    "phase",
    "fingerprint",
    "grantFingerprint",
    "planGrantToolNames",
    "localManifestFingerprint",
    "localAvailableToolNames",
    "localEffectiveToolNames",
    "mcpRouteProjections",
    "providerVisibleToolNames",
    "providerSchemaFingerprint",
    "specialistMcpRequested",
    "specialistMcpEffective",
    "specialistMcpDeniedReason",
    "scope",
    "budgetPolicy",
    "reasonCodes",
}
_AUTHORIZATION_ROUTE_KEYS = {
    "route",
    "requestedToolNames",
    "toolNames",
    "fingerprint",
}
_CREDENTIAL_VALUE = re.compile(
    r"(?i)(?:\b(?:sk|rk|pk)-[a-z0-9_-]{8,}\b|\bAKIA[0-9A-Z]{16}\b|\bBearer\s+[A-Za-z0-9._~+/=-]{8,})"
)


def sanitize_trace_for_persistence(payload: Mapping[str, Any] | None) -> dict[str, Any]:
    """Return a bounded Trace-safe projection that contains no raw request/tool body."""
    if not isinstance(payload, Mapping):
        return {}
    return _sanitize_value(payload, depth=0) or {}


def _sanitize_value(value: Any, *, depth: int, key: str | None = None) -> Any | None:
    if depth > 10:
        return None
    if value is None or isinstance(value, bool):
        return value
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return value
    if isinstance(value, str):
        if key in _SUMMARY_KEYS:
            return _safe_summary(value)
        # Keep short metadata tokens/ids; drop long free-form text.
        if len(value) > 256 or _CREDENTIAL_VALUE.search(value):
            return None
        if any(character.isspace() for character in value) and len(value) > 64:
            return _safe_summary(value)
        return _CREDENTIAL_VALUE.sub("[redacted]", value)
    if isinstance(value, Mapping):
        return _sanitize_mapping(value, depth=depth)
    if _is_sequence(value):
        items = []
        for item in list(value)[:MAX_TRACE_COLLECTION_ITEMS]:
            safe = _sanitize_value(item, depth=depth + 1, key=key)
            if safe is not None:
                items.append(safe)
        return items
    return None


def _sanitize_mapping(value: Mapping[str, Any], *, depth: int) -> dict[str, Any]:
    sanitized: dict[str, Any] = {}
    for raw_key, raw_value in value.items():
        if raw_key is None:
            continue
        key = str(raw_key)
        normalized = _normalized_key(key)
        if key == "authorizationBoundary":
            sanitized[key] = _sanitize_authorization_boundary(raw_value, depth=depth + 1)
            continue
        if _is_sensitive_key(normalized):
            continue
        if _is_body_key(normalized):
            if normalized in {"input", "output"}:
                sanitized[f"{normalized}Hash"] = _stable_hash(raw_value)
            continue
        if key == "toolRuns":
            sanitized[key] = _sanitize_tool_runs(raw_value, depth=depth + 1)
            continue
        if key == "projectKnowledge":
            sanitized[key] = _sanitize_project_knowledge(raw_value, depth=depth + 1)
            continue
        safe = _sanitize_value(raw_value, depth=depth + 1, key=key)
        if safe is not None:
            sanitized[key] = safe
        elif raw_value is None:
            sanitized[key] = None
    return sanitized


def _sanitize_authorization_boundary(value: Any, *, depth: int) -> dict[str, Any]:
    if not isinstance(value, Mapping):
        return {}
    sanitized: dict[str, Any] = {}
    for key in _AUTHORIZATION_BOUNDARY_KEYS:
        raw_value = value.get(key)
        if key == "mcpRouteProjections":
            projections: list[dict[str, Any]] = []
            if _is_sequence(raw_value):
                for projection in list(raw_value)[:MAX_TRACE_COLLECTION_ITEMS]:
                    if not isinstance(projection, Mapping):
                        continue
                    safe_projection = {
                        route_key: _sanitize_value(
                            projection.get(route_key),
                            depth=depth + 1,
                            key=route_key,
                        )
                        for route_key in _AUTHORIZATION_ROUTE_KEYS
                        if projection.get(route_key) is not None
                    }
                    projections.append({
                        route_key: route_value
                        for route_key, route_value in safe_projection.items()
                        if route_value is not None
                    })
            sanitized[key] = projections
            continue
        if raw_value is None:
            continue
        safe = _sanitize_value(raw_value, depth=depth + 1, key=key)
        if safe is not None:
            sanitized[key] = safe
    return sanitized


def _sanitize_tool_runs(value: Any, *, depth: int) -> list[dict[str, Any]]:
    if not _is_sequence(value):
        return []
    safe_runs: list[dict[str, Any]] = []
    for raw_run in list(value)[:MAX_TRACE_COLLECTION_ITEMS]:
        if not isinstance(raw_run, Mapping):
            continue
        item = _sanitize_mapping(
            {
                k: v
                for k, v in raw_run.items()
                if str(k) not in {"input", "output"}
            },
            depth=depth + 1,
        )
        if "input" in raw_run:
            item["inputHash"] = _stable_hash(raw_run["input"])
        elif isinstance(raw_run.get("inputHash"), str) and raw_run.get("inputHash"):
            item["inputHash"] = raw_run["inputHash"]
        if "output" in raw_run:
            item["outputHash"] = _stable_hash(raw_run["output"])
        elif isinstance(raw_run.get("outputHash"), str) and raw_run.get("outputHash"):
            item["outputHash"] = raw_run["outputHash"]
        if item:
            safe_runs.append(item)
    return safe_runs


def _sanitize_project_knowledge(value: Any, *, depth: int) -> dict[str, Any]:
    if not isinstance(value, Mapping):
        return {}
    sanitized: dict[str, Any] = {}
    for raw_key, raw_value in value.items():
        if raw_key is None:
            continue
        key = str(raw_key)
        if key in {
            "matchedCharacterStates",
            "matchedForeshadowings",
            "matchedTimelineEvents",
            "matchedWorldRules",
            "retrievedChapters",
            "retrievedChunks",
            "retrievedEvidence",
            "resolutionCandidates",
        } and _is_sequence(raw_value):
            evidence = []
            for entry in list(raw_value)[:MAX_TRACE_COLLECTION_ITEMS]:
                if not isinstance(entry, Mapping):
                    continue
                # Drop body fields from evidence rows; keep ids/scores/types.
                safe_entry = _sanitize_mapping(entry, depth=depth + 1)
                if safe_entry:
                    evidence.append(safe_entry)
            if evidence:
                sanitized[key] = evidence
            continue
        safe = _sanitize_value(raw_value, depth=depth + 1, key=key)
        if safe is not None:
            sanitized[key] = safe
    return sanitized


def _safe_summary(value: str) -> str:
    return _CREDENTIAL_VALUE.sub("[redacted]", value.strip())[:MAX_TRACE_SUMMARY_CHARS]


def _stable_hash(value: Any) -> str:
    try:
        encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, default=str, separators=(",", ":"))
    except (TypeError, ValueError):
        encoded = repr(value)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _normalized_key(key: str) -> str:
    return re.sub(r"[^a-z0-9]", "", key.lower())


def _is_sensitive_key(key: str) -> bool:
    # Match credential-bearing keys without treating *Tokens metrics as secrets.
    if key in {"token", "tokens", "accesstoken", "refreshtoken", "idtoken", "authtoken"}:
        return True
    return any(
        part in key
        for part in _SENSITIVE_KEY_PARTS
        if part != "token"
    ) or key.endswith("token") and not key.endswith("tokens")


def _is_body_key(key: str) -> bool:
    if key == "contenthash" or key in {"contextused", "contextbudget", "memorycontext"}:
        return False
    if key in _BODY_KEY_EXCEPTIONS:
        return False
    if key in _BODY_KEY_EXACT:
        return True
    if key in {"payload", "prompt", "upload"} or key.startswith(("payload", "upload")):
        return True
    if key.startswith("prompt") and key not in _BODY_KEY_EXCEPTIONS:
        # Keep metadata like promptPolicy/promptVersion; drop prompt bodies only.
        if key in {"prompt", "prompts", "prompttext", "promptbody", "rawprompt"}:
            return True
    # Avoid treating *context as *text body fields.
    if key.endswith(("body", "content", "markdown")):
        return True
    if key == "text" or key.endswith(("_text", "chunktext", "fulltext", "plaintext")):
        return True
    return False


def _is_sequence(value: Any) -> bool:
    return isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray))
