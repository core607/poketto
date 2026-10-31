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

"$DOCKER" compose --project-directory "$DIR" -f "$DIR/compose.yaml" config --format json | python3 -c '
import json, re, sys
command = json.load(sys.stdin)["services"]["node-exporter"]["command"]
pattern = next(value.split("=", 1)[1] for value in command if value.startswith("--collector.systemd.unit-include="))
for unit in ("poketto-executor.service", "poketto-exec-example.service", "sandcrab-games.service", "sandcrab-games-example.service"):
    assert re.fullmatch(pattern, unit), "missing monitored unit: " + unit
for unit in ("sshd.service", "sandcrab-other.service", "other-sandcrab-games.service"):
    assert not re.fullmatch(pattern, unit), "unrelated monitored unit: " + unit
'
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
probe="poketto-observability-probe-$$"
cleanup() {
    "$DOCKER" rm -f "$probe-shipper" "$probe-logs" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT
# Replay the host-networked examples on one isolated loopback namespace.
"$DOCKER" run -d --name "$probe-logs" --network none \
    "$victorialogs" -httpListenAddr=127.0.0.1:9428 -storageDataPath=/tmp/victoria-logs >/dev/null
sed -e '/^\[INPUT\]/,/^$/c\
[INPUT]\
    Name     dummy\
    Tag      poketto\
    Dummy    {"MESSAGE":"{\\"log\\":{\\"level\\":\\"WARN\\"},\\"message\\":\\"probe record\\"}","SYSLOG_IDENTIFIER":"poketto-app-1"}\
    Samples  3\

' "$DIR/fluent-bit.conf" > "$work/shipper.conf"
printf '%s\n' '' '[INPUT]' '    Name   dummy' '    Tag    poketto' \
    '    Dummy  {"MESSAGE":"unrelated host line","SYSLOG_IDENTIFIER":"sshd"}' '    Samples 3' >> "$work/shipper.conf"
printf '%s\n' '' '[INPUT]' '    Name   dummy' '    Tag    poketto' \
    '    Dummy  {"MESSAGE":"{\"message\":\"game probe record\"}","SYSLOG_IDENTIFIER":"sandcrab","_SYSTEMD_UNIT":"sandcrab-games.service","_SYSTEMD_SLICE":"pokettogames.slice"}' \
    '    Samples 3' '' '[INPUT]' '    Name   dummy' '    Tag    poketto' \
    '    Dummy  {"MESSAGE":"unrelated sandbox line","SYSLOG_IDENTIFIER":"sandcrab","_SYSTEMD_UNIT":"sandcrab-other.service","_SYSTEMD_SLICE":"sandcrab.slice"}' \
    '    Samples 3' >> "$work/shipper.conf"
"$DOCKER" run -d --name "$probe-shipper" --network "container:$probe-logs" \
    --mount "type=bind,source=$work/shipper.conf,target=/fluent-bit/etc/poketto.conf,readonly" \
    "$fluentbit" /fluent-bit/bin/fluent-bit -c /fluent-bit/etc/poketto.conf >/dev/null
query() {
    "$DOCKER" run --rm --network "container:$probe-logs" --entrypoint wget "$prometheus" -T 5 -qO- \
        "http://127.0.0.1:9428/select/logsql/query?query=$1" 2>"$work/query-error"
}
for _ in $(seq 1 30); do
    found="$(query 'SYSLOG_IDENTIFIER:poketto-app-1%20%7C%20unpack_json')" || found=''
    game="$(query '_SYSTEMD_UNIT:sandcrab-games.service%20%7C%20unpack_json')" || game=''
    case "$found:$game" in *'"message":"probe record"'*'"message":"game probe record"'*) break ;; esac
    sleep 2
done
case "$found:$game" in
    *'"message":"probe record"'*'"message":"game probe record"'*) ;;
    *) echo 'the shipped application or game record never reached VictoriaLogs' >&2
       cat "$work/query-error" >&2
       "$DOCKER" logs "$probe-logs" >&2 || true
       "$DOCKER" logs "$probe-shipper" >&2 || true
       exit 1 ;;
esac
unrelated="$(query 'SYSLOG_IDENTIFIER:sshd')"
case "$unrelated" in
    *'unrelated host line'*) echo 'a non-Poketto journal line passed the shipper filter' >&2; exit 1 ;;
esac
unrelated="$(query '_SYSTEMD_UNIT:sandcrab-other.service')"
case "$unrelated" in
    *'unrelated sandbox line'*) echo 'another Sandcrab consumer passed the shipper filter' >&2; exit 1 ;;
esac
echo "monitoring examples load, and only Poketto records reach VictoriaLogs as structured fields"
