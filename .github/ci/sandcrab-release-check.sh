#!/usr/bin/env bash
# Run on a disposable systemd host. Keep the account fixture and release archive off public artifacts.
set -euo pipefail

: "${SANDCRAB_BUNDLE:?Supply the pinned release ZIP}"
: "${SANDCRAB_EVIDENCE:?Supply an empty results directory}"
if [[ ${GITHUB_ACTIONS:-} != true && ${SANDCRAB_INSTALL_DISPOSABLE:-} != 1 ]]; then
  echo 'Run on a disposable Linux systemd host with SANDCRAB_INSTALL_DISPOSABLE=1.' >&2
  exit 2
fi
bundle=$(realpath -- "$SANDCRAB_BUNDLE")
evidence=$(realpath -- "$SANDCRAB_EVIDENCE")
test -f "$bundle" && test -d "$evidence" && test -z "$(find "$evidence" -mindepth 1 -print -quit)"
cd "$(git rev-parse --show-toplevel)"
as_root() {
  if (( EUID == 0 )); then "$@"; else sudo -- "$@"; fi
}
stage=$(as_root mktemp -d /var/lib/poketto-release-check.XXXXXXXX)
cleanup() {
  local verdict=$?
  trap - EXIT
  [[ "$stage" == /var/lib/poketto-release-check.* && "$(realpath -- "$stage")" == "$stage" ]] || return 1
  # Root may use umask 0077. Extract as the caller so both passing and failed receipts remain readable.
  if ! as_root tar -C "$stage/results" -cf - . | tar -C "$evidence" --no-same-owner --no-same-permissions -xf -; then
    echo "Evidence transfer failed. Inspect $stage/results." >&2
    exit 1
  fi
  as_root rm -rf --one-file-system -- "$stage"
  exit "$verdict"
}
as_root mkdir -m 700 "$stage/results"
trap cleanup EXIT
as_root chmod 0755 "$stage"

jdk=${JAVA_HOME_26_X64:-${JAVA_HOME:-}}
if [[ -z "$jdk" || ! -x "$jdk/bin/javac" || ! "$("$jdk/bin/javac" -version)" =~ ^javac\ 26([.+-]|$) ]]; then
  as_root bash executor-native/prepare-jdk.sh "$stage/download"
  as_root mv "$stage/download/jdk-26.0.2+10" "$stage/jdk"
  jdk="$stage/jdk"
fi
export JAVA_HOME="$jdk" PATH="$jdk/bin:$PATH"
gradle_args=()
if [[ ${GITHUB_ACTIONS:-} == true || "$jdk" == "$stage/jdk" ]]; then gradle_args+=(--no-daemon); fi
./gradlew "${gradle_args[@]}" -PsandcrabBundle="$bundle" stageGameWorker stageExecutorNativeTest stageAcceptanceRuntime
command -v mkfs.xfs >/dev/null || { as_root apt-get update -qq && as_root apt-get install -y --no-install-recommends xfsprogs; }
if [[ "$(cat /proc/sys/kernel/apparmor_restrict_unprivileged_userns 2>/dev/null || true)" == 1 ]]; then
  as_root apparmor_parser -r executor-service/rust/sandcrab/install/sandcrab.apparmor
fi
if [[ "$jdk" != "$stage/jdk" ]]; then as_root cp -RL "$jdk" "$stage/jdk"; fi
as_root cp -R build/executor-native/runtime "$stage/runtime"
as_root chmod -R a+rX,go-w "$stage/jdk" "$stage/runtime"
as_root env SANDCRAB_INSTALL_DISPOSABLE=1 python3 executor-native/overlay_copy_probe.py \
  --uid "$(id -u daemon)" --gid "$(id -g daemon)" --java "$stage/jdk/bin/java" \
  --binaries build/game-worker --runtime "$stage/runtime" --output "$stage/results/workspaces"

as_root python3 - "$stage/results/workspaces/summary.json" <<'PY'
import json
import sys
from pathlib import Path
pin = dict(line.split('=', 1) for line in Path('executor-service/sandcrab-release.properties').read_text().splitlines())
summary = json.loads(Path(sys.argv[1]).read_text())
if summary['result'] != 'PASS' or summary['cleanup'] != 'PASS' or summary['runningSandcrabSha256'] != pin['sandcrabSha256']:
    sys.exit('The workspace verdict or the running Sandcrab executable differs from the release pin')
PY

as_root cp -R build/acceptance/runtime "$stage/account-runtime"
as_root chmod -R a+rX,go-w "$stage/account-runtime"
as_root bash executor-service/prepare-tools.sh "$stage/tools"
as_root bash executor-service/prepare-node.sh "$stage/tools"
as_root python3 acceptance/clients/native-load.py --runtime "$stage/account-runtime" \
  --binary build/game-worker/sandcrab --tools "$stage/tools" --java "$stage/jdk/bin/java" \
  --verify-clients --evidence "$stage/results/native-load.json"
