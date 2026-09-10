"""PR conversation persistence in artifacts from the trusted review workflow."""

import copy
import io
import json
import re
import zipfile


SCHEMA = 1
SESSION_BYTES = 8_000_000
HISTORY_BYTES = 180_000


def contract(request, digest, encoded):
    return digest(encoded({k: request[k] for k in ("model", "tools")} | {
        "system": request["messages"][0], "schema": SCHEMA}))


def decode(raw, repository, number):
    if len(raw) > SESSION_BYTES:
        raise ValueError("Review session exceeds its retained byte bound.")
    state = json.loads(raw)
    if (state.get("schema") != SCHEMA or state.get("repository") != repository
            or state.get("pr") != str(number) or state.get("complete") is not True):
        raise ValueError("Review session identity is invalid.")
    revision = state["revision"]
    if not all(re.fullmatch(r"[a-f0-9]{40}", revision[k]) for k in ("base", "head")):
        raise ValueError("Review session commits are invalid.")
    if not isinstance(state.get("reports"), list) or not isinstance(state.get("requests"), dict):
        raise ValueError("Review session data is invalid.")
    return state


def restore(github, command, current_run):
    # Listing by the trusted workflow ID excludes same-named artifacts from ordinary PR CI.
    # Bounded pagination degrades to a fresh review when no retained conversation is found.
    for page in range(1, 6):
        runs = github.api(f"actions/workflows/ai-review.yml/runs?status=success&per_page=100&page={page}")["workflow_runs"]
        for run in runs:
            if (str(run["id"]) == current_run or run["event"] not in {"workflow_run", "workflow_dispatch"}
                    or run["display_title"] != f"AI Review PR #{github.number}"):
                continue
            if run["event"] == "workflow_dispatch" and run["head_branch"] != "main":
                continue
            artifacts = github.api(f"actions/runs/{run['id']}/artifacts?per_page=100")["artifacts"]
            prefix = f"ai-review-session-{github.number}-{run['id']}-"
            for artifact in sorted(artifacts, key=lambda a: a["id"], reverse=True):
                if not artifact["name"].startswith(prefix) or artifact["expired"]:
                    continue
                raw = command(["gh", "api", f"repos/{github.repository}/actions/artifacts/{artifact['id']}/zip"],
                              github.budget, limit=32_000_000)
                with zipfile.ZipFile(io.BytesIO(raw)) as archive:
                    if "session.json" not in archive.namelist():
                        continue
                    info = archive.getinfo("session.json")
                    if info.file_size > SESSION_BYTES:
                        raise ValueError("Review session exceeds its retained byte bound.")
                    return decode(archive.read(info), github.repository, github.number)
        if len(runs) < 100:
            break
    return None


def continuation(previous, fresh, message, encoded, input_limit, framing, previous_tokens=None,
                 transport_limit=4_000_000):
    """Keep the exact old prefix, or return None for a checkpoint-based fresh review."""
    if not previous:
        return None
    result = copy.deepcopy(fresh)
    result["messages"] = copy.deepcopy(previous["messages"]) + [{"role": "user", "content": message}]
    # Old tool_choice controls a request, not a conversation. A new review can use tools again.
    result.pop("tool_choice", None)
    if type(previous_tokens) is int and previous_tokens >= 0:
        upper_bound = previous_tokens + len(encoded(result["messages"][-1])) + framing
    else:
        upper_bound = len(encoded(result)) + framing
    if upper_bound > input_limit or len(encoded(result)) + framing > transport_limit:
        return None
    return result, upper_bound


def checkpoint(state, encoded):
    # Retain every prior final finding instead of silently discarding unresolved findings when
    # dropping bulky reasoning/source transcripts. Historical reports are evidence, not rules.
    value = {"reports": state.get("reports", [])}
    raw = encoded(value)
    if len(raw) > HISTORY_BYTES:
        raise ValueError("The review checkpoint is too large; earlier findings cannot be silently dropped.")
    return raw.decode("utf-8")
