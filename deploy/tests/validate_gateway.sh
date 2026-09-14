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

# Validation proves the configuration loads, not that a credential stays out of the record. Caddy
# keeps two logs, and the process log carrying reverse-proxy failures is a separate logger from
# the site's access log, so a filter on one left the other recording whole request lines. Serve a
# real request with no upstream and read both logs back.
port="${POKETTO_GATEWAY_PROBE_PORT:-18097}"
marker_path="KOHAKUMARKERPATH"
marker_grant="KOHAKUMARKERGRANTAAAAAAAAAAAAAAAAAAAAAAA"
marker_referrer="KOHAKUMARKERREFERRER"
marker_filename="KOHAKUMARKERFILENAME"
# A download names the file in its response header, so the probe needs something behind the
# gateway that sets one. This stands in for the application; nothing else about it is realistic.
network="poketto-gateway-probe-$$"
printf '{
	admin off
}
:8080 {
	log
	header Content-Disposition "attachment; filename=%s.md"
	respond "ok" 200
}
' "$marker_filename" > "$work/upstream"
upstream_config="$work/upstream"
upstream_id="$work/upstream.id"
case "$(uname -s)" in MINGW*|MSYS*) upstream_config="$(cygpath -w "$upstream_config")"; upstream_id="$(cygpath -w "$upstream_id")" ;; esac
"$DOCKER" network create "$network" >/dev/null
MSYS_NO_PATHCONV=1 "$DOCKER" run -d --cidfile "$upstream_id" --read-only --user 10002:10002 --cap-drop ALL --cap-add NET_BIND_SERVICE --security-opt no-new-privileges:true --memory 128m --cpus 0.5 --pids-limit 128 --network "$network" --network-alias app --tmpfs /data:size=16m,uid=10002,gid=10002,mode=0700 --tmpfs /config:size=16m,uid=10002,gid=10002,mode=0700 --tmpfs /tmp:size=16m,mode=1777 --mount "type=bind,source=$upstream_config,target=/etc/caddy/Caddyfile,readonly" "$image" >/dev/null
probe="$work/probe.id"
case "$(uname -s)" in MINGW*|MSYS*) probe="$(cygpath -w "$probe")" ;; esac
MSYS_NO_PATHCONV=1 "$DOCKER" run -d --cidfile "$probe" --read-only     --user 10002:10002 --cap-drop ALL --cap-add NET_BIND_SERVICE --security-opt no-new-privileges:true     --memory 128m --cpus 0.5 --pids-limit 128 --publish "127.0.0.1:$port:8080" --network "$network"     --tmpfs /data:size=16m,uid=10002,gid=10002,mode=0700     --tmpfs /config:size=16m,uid=10002,gid=10002,mode=0700 --tmpfs /tmp:size=16m,mode=1777     --env POKETTO_PUBLIC_DOMAIN=":8080"     --mount "type=bind,source=$config,target=/etc/caddy/Caddyfile,readonly"     "$image" >/dev/null
container="$(cat "$work/probe.id")"
cleanup_probe() {
    "$DOCKER" rm -f "$container" >/dev/null 2>&1 || true
    if [ -s "$work/upstream.id" ]; then "$DOCKER" rm -f "$(cat "$work/upstream.id")" >/dev/null 2>&1 || true; fi
    "$DOCKER" network rm "$network" >/dev/null 2>&1 || true
}
trap 'cleanup_probe; cleanup' EXIT
for _ in 1 2 3 4 5 6 7 8 9 10; do
    "${POKETTO_CURL:-curl}" -sS -o /dev/null --max-time 5 "http://127.0.0.1:$port/" >/dev/null 2>&1 && break
    sleep 1
done
# The application is deliberately absent, which is the redeployment window this must survive.
"${POKETTO_CURL:-curl}" -sS -o /dev/null --max-time 5     "http://127.0.0.1:$port/api/admin/workspaces/11111111-2222-3333-4444-555555555555/media?path=private/$marker_path.md" >/dev/null 2>&1 || true
"${POKETTO_CURL:-curl}" -sS -o /dev/null --max-time 5     "http://127.0.0.1:$port/api/public/assets/$marker_grant" >/dev/null 2>&1 || true
# An administration page puts the open document's path in its own address, which a same-origin
# request then carries in this header.
"${POKETTO_CURL:-curl}" -sS -o /dev/null --max-time 5     -H "Referer: http://site.example.invalid/admin?path=private/$marker_referrer.md"     "http://127.0.0.1:$port/api/public/documents" >/dev/null 2>&1 || true
sleep 2
recorded="$("$DOCKER" logs "$container" 2>&1 || true)"
[ -n "$recorded" ] || { echo 'the gateway recorded nothing at all; the probe proves nothing' >&2; exit 1; }
case "$recorded" in
    *"$marker_path"*) echo 'a repository path from the query string reached a gateway record' >&2; exit 1 ;;
esac
case "$recorded" in
    *"$marker_grant"*) echo 'an image grant reached a gateway record' >&2; exit 1 ;;
esac
case "$recorded" in
    *"$marker_referrer"*) echo 'a referring address reached a gateway record' >&2; exit 1 ;;
esac
# The response-header assertions mean nothing unless something behind the gateway actually
# answered: a stand-in that fails to start turns every one of them into a no-op.
served="$("$DOCKER" logs "$(cat "$work/upstream.id")" 2>&1 || true)"
case "$served" in
    *"handled request"*) ;;
    *) echo 'the stand-in application served nothing, so the response assertions prove nothing' >&2; exit 1 ;;
esac
case "$recorded" in
    *resp_headers*) ;;
    *) echo 'the probe recorded no response headers, so it proves nothing about them' >&2; exit 1 ;;
esac
case "$recorded" in
    *"$marker_filename"*) echo 'a private file name reached a gateway record' >&2; exit 1 ;;
esac
echo "gateway records carry no address, grant, referrer or file name"
