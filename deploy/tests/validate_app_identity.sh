#!/usr/bin/env bash
# Exercise an exact application image against the executor's protected Unix socket layout.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER="${POKETTO_DOCKER:-docker}"
image="${1:?application image is required}"
revision="${2:?source revision is required}"
[[ "$revision" =~ ^[0-9a-f]{40}(-dirty)?$ ]] || { echo 'full source revision (optionally -dirty for local edits) is required' >&2; exit 1; }
actual="$("$DOCKER" image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$image")"
[ "$actual" = "$revision" ] || { echo 'application image revision mismatch' >&2; exit 1; }
fixture="$HERE/app_identity_socket.py"
case "$(uname -s)" in MINGW*|MSYS*) fixture="$(cygpath -w "$fixture")" ;; esac
name="poketto-app-identity-$$-$(date +%s)"
volume=""
server=""
work="$(mktemp -d)"
cleanup() {
    [ -z "$server" ] || "$DOCKER" rm -f "$server" >/dev/null 2>&1 || true
    [ -z "$volume" ] || "$DOCKER" volume rm "$volume" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
volume="$("$DOCKER" volume create "$name")"
server="$name"
MSYS_NO_PATHCONV=1 "$DOCKER" run --detach --name "$server" --network none --read-only \
    --user 0:0 --cap-drop ALL --cap-add CHOWN --security-opt no-new-privileges:true \
    --memory 64m --cpus 0.5 --pids-limit 32 \
    --mount "type=volume,source=$volume,target=/run/poketto-executor" \
    --mount "type=bind,source=$fixture,target=/probe.py,readonly" \
    python:3.12-slim-bookworm@sha256:782412e85d0f0984994c290652577d4018aff08145c85b262bb63dc0c7522254 \
    python -I /probe.py >/dev/null
ready=0
for ((attempt=0; attempt<50; attempt++)); do
    if "$DOCKER" logs "$server" 2>&1 | grep -Fxq socket-ready; then ready=1; break; fi
    sleep 0.2
done
[ "$ready" = 1 ] || { "$DOCKER" logs "$server" >&2; echo 'socket fixture failed to start' >&2; exit 1; }
client=(run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges:true
    --memory 64m --cpus 0.5 --pids-limit 32 --mount "type=volume,source=$volume,target=/run/poketto-executor,readonly")
identity="$(MSYS_NO_PATHCONV=1 "$DOCKER" "${client[@]}" --entrypoint id "$image")"
if ! peer="$(MSYS_NO_PATHCONV=1 "$DOCKER" "${client[@]}" --entrypoint curl "$image" \
    --silent --show-error --fail --noproxy '*' --max-time 5 --unix-socket /run/poketto-executor/control.sock http://localhost/)"; then
    echo "application cannot use the protected executor socket: $identity" >&2
    exit 1
fi
[ "$peer" = '{"uid":10001,"gid":10001}' ] || { echo "unexpected worker peer: $peer" >&2; exit 1; }
set +e
MSYS_NO_PATHCONV=1 "$DOCKER" "${client[@]}" --user 20001:20001 --entrypoint curl "$image" \
    --verbose --silent --show-error --fail --noproxy '*' --max-time 5 --unix-socket /run/poketto-executor/control.sock http://localhost/ \
    >"$work/denied.out" 2>"$work/denied.err"
denied=$?
set -e
[ "$denied" = 7 ] && grep -Fq "Permission denied" "$work/denied.err" || { echo "unrelated identity was not denied by socket permissions (exit $denied)" >&2; exit 1; }
echo "application identity and protected executor socket: PASS ($identity)"
