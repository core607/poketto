#!/usr/bin/env bash
# Real Caddy provisioning validation, separate from the fake CLI script suite. Requires Docker.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCKER="${POKETTO_DOCKER:-docker}"
image="$(sed -n 's/^POKETTO_GATEWAY_IMAGE=//p' "$HERE/.env.example" | tr -d '\r')"
[[ "$image" =~ ^[A-Za-z0-9._:/-]+@sha256:[0-9a-f]{64}$ ]] || { echo 'gateway validation requires the exact example digest' >&2; exit 1; }
config="$HERE/Caddyfile"
case "$(uname -s)" in MINGW*|MSYS*) config="$(cygpath -w "$config")" ;; esac
# No network can be used during validation; no real domain, credentials or host state enter it.
MSYS_NO_PATHCONV=1 "$DOCKER" run --rm --network none --read-only \
    --tmpfs /data:size=16m --tmpfs /config:size=16m --tmpfs /tmp:size=16m \
    --env POKETTO_PUBLIC_DOMAIN=site.example.invalid \
    --mount "type=bind,source=$config,target=/etc/caddy/Caddyfile,readonly" \
    "$image" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
