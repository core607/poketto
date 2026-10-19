#!/usr/bin/env bash
# Validates the optional monitoring examples in deploy/observability with their pinned images, so
# an example nobody runs in production still loads. Requires Docker; separate from the fake-CLI suite.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../observability" && pwd)"
DOCKER="${POKETTO_DOCKER:-docker}"
image() {
    local found
    found="$(sed -n "s|^ *image: *\\($1:[^ ]*@sha256:[0-9a-f]\\{64\\}\\)\$|\\1|p" "$DIR/compose.yaml")"
    [ -n "$found" ] || { echo "compose.yaml must pin $1 by digest" >&2; exit 1; }
    printf '%s' "$found"
}
prometheus="$(image prom/prometheus)"
victorialogs="$(image victoriametrics/victoria-logs)"
fluentbit="$(image fluent/fluent-bit)"
image prom/node-exporter >/dev/null

"$DOCKER" compose --project-directory "$DIR" -f "$DIR/compose.yaml" config --quiet
"$DOCKER" run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges:true \
    --mount "type=bind,source=$DIR/prometheus.yml,target=/etc/prometheus/prometheus.yml,readonly" \
    --mount "type=bind,source=$DIR/alerts.yml,target=/etc/prometheus/alerts.yml,readonly" \
    --entrypoint promtool "$prometheus" check config /etc/prometheus/prometheus.yml
"$DOCKER" run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges:true \
    --mount "type=bind,source=$DIR/fluent-bit.conf,target=/fluent-bit/etc/poketto.conf,readonly" \
    "$fluentbit" /fluent-bit/bin/fluent-bit --dry-run -c /fluent-bit/etc/poketto.conf >/dev/null

# A dry run accepts any URI, so ship a record through the real filter and output into a real
# VictoriaLogs and read it back. A dummy input stands in for journald, which CI runners lack.
work="$(mktemp -d)"
network="poketto-observability-probe-$$"
cleanup() {
    "$DOCKER" rm -f "$network-logs" "$network-shipper" >/dev/null 2>&1 || true
    "$DOCKER" network rm "$network" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT
sed -e 's/127\.0\.0\.1/logs/' -e '/^\[INPUT\]/,/^$/c\
[INPUT]\
    Name     dummy\
    Tag      poketto\
    Dummy    {"MESSAGE":"{\\"log\\":{\\"level\\":\\"WARN\\"},\\"message\\":\\"probe record\\"}","SYSLOG_IDENTIFIER":"poketto-app-1"}\
    Samples  3\

' "$DIR/fluent-bit.conf" > "$work/shipper.conf"
printf '%s\n' '' '[INPUT]' '    Name   dummy' '    Tag    poketto' \
    '    Dummy  {"MESSAGE":"unrelated host line","SYSLOG_IDENTIFIER":"sshd"}' '    Samples 3' >> "$work/shipper.conf"
"$DOCKER" network create "$network" >/dev/null
"$DOCKER" run -d --name "$network-logs" --network "$network" --network-alias logs \
    "$victorialogs" -httpListenAddr=:9428 -storageDataPath=/tmp/victoria-logs >/dev/null
"$DOCKER" run -d --name "$network-shipper" --network "$network" \
    --mount "type=bind,source=$work/shipper.conf,target=/fluent-bit/etc/poketto.conf,readonly" \
    "$fluentbit" /fluent-bit/bin/fluent-bit -c /fluent-bit/etc/poketto.conf >/dev/null
query() {
    "$DOCKER" run --rm --network "$network" --entrypoint wget "$prometheus" -qO- \
        "http://logs:9428/select/logsql/query?query=$1" 2>/dev/null || true
}
for _ in $(seq 1 30); do
    found="$(query 'SYSLOG_IDENTIFIER:poketto-app-1%20%7C%20unpack_json')"
    case "$found" in *'"message":"probe record"'*) break ;; esac
    sleep 2
done
case "$found" in
    *'"message":"probe record"'*) ;;
    *) echo 'the shipped application record never reached VictoriaLogs' >&2
       "$DOCKER" logs "$network-shipper" >&2 || true
       exit 1 ;;
esac
case "$(query 'SYSLOG_IDENTIFIER:sshd')" in
    *'unrelated host line'*) echo 'a non-Poketto journal line passed the shipper filter' >&2; exit 1 ;;
esac
echo "monitoring examples load, and only Poketto records reach VictoriaLogs as structured fields"
