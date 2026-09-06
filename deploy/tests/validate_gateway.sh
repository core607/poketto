#!/usr/bin/env bash
# Real Caddy provisioning validation, separate from the fake CLI script suite. Requires Docker.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCKER="${POKETTO_DOCKER:-docker}"
image="$(sed -n 's/^POKETTO_GATEWAY_IMAGE=//p' "$HERE/.env.example" | tr -d '\r')"
[[ "$image" =~ ^[A-Za-z0-9._:/-]+@sha256:[0-9a-f]{64}$ ]] || { echo 'gateway validation requires the exact example digest' >&2; exit 1; }
config="$HERE/Caddyfile"
case "$(uname -s)" in MINGW*|MSYS*) config="$(cygpath -w "$config")" ;; esac
# Match the gateway's unprivileged, read-only production profile. No real domain,
# credentials or persistent host state enter validation.
work="$(mktemp -d)"
cleanup() {
    if [ -s "$work/container.id" ]; then "$DOCKER" rm -f "$(cat "$work/container.id")" >/dev/null 2>&1 || true; fi
    rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
cidfile="$work/container.id"
case "$(uname -s)" in MINGW*|MSYS*) cidfile="$(cygpath -w "$cidfile")" ;; esac
MSYS_NO_PATHCONV=1 "$DOCKER" run --rm --cidfile "$cidfile" --network none --read-only \
    --user 10002:10002 --cap-drop ALL --cap-add NET_BIND_SERVICE --security-opt no-new-privileges:true \
    --memory 128m --cpus 0.5 --pids-limit 128 \
    --tmpfs /data:size=16m,uid=10002,gid=10002,mode=0700 \
    --tmpfs /config:size=16m,uid=10002,gid=10002,mode=0700 --tmpfs /tmp:size=16m,mode=1777 \
    --env POKETTO_PUBLIC_DOMAIN=site.example.invalid \
    --mount "type=bind,source=$config,target=/etc/caddy/Caddyfile,readonly" \
    "$image" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
