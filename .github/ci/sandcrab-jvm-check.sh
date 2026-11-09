#!/usr/bin/env bash
# Bounded JVM comparison on a disposable systemd host; distribution credentials stay elsewhere.
set -euo pipefail
: "${SANDCRAB_BUNDLE:?Supply the pinned release ZIP}"
: "${SANDCRAB_EVIDENCE:?Supply an empty results directory}"
if [[ ${GITHUB_ACTIONS:-} != true && ${SANDCRAB_INSTALL_DISPOSABLE:-} != 1 ]]; then
  echo 'Use a disposable Linux systemd host with SANDCRAB_INSTALL_DISPOSABLE=1.' >&2
  exit 2
fi
bundle=$(realpath -e -- "$SANDCRAB_BUNDLE")
evidence=$(realpath -e -- "$SANDCRAB_EVIDENCE")
[[ -f "$bundle" && -r "$bundle" && -d "$evidence" && -w "$evidence" ]]
[[ -z "$(find "$evidence" -mindepth 1 -maxdepth 1 -print -quit)" ]]
cd "$(git rev-parse --show-toplevel)"
as_root() { if (( EUID == 0 )); then "$@"; else sudo -- "$@"; fi; }
stage=$(as_root mktemp -d /var/lib/poketto-jvm-check.XXXXXXXX)
as_root chmod 0755 "$stage"
as_root mkdir -m 700 "$stage/results"
cleanup() {
  local verdict=$?
  trap - EXIT
  [[ "$stage" == /var/lib/poketto-jvm-check.* && "$(realpath -- "$stage")" == "$stage" ]] || exit 1
  if ! as_root tar -C "$stage/results" -cf - . | tar -C "$evidence" --no-same-owner --no-same-permissions -xf -; then
    echo "Evidence transfer failed; retain $stage/results." >&2
    exit 1
  fi
  # Retain the inputs after failures so the operator can reconcile an unfinished fixture.
  if (( verdict == 0 )); then
    as_root rm -rf --one-file-system -- "$stage"
  else
    echo "Retained failed JVM inputs at $stage for reconciliation." >&2
  fi
  exit "$verdict"
}
trap cleanup EXIT
jdk=${JAVA_HOME_26_X64:-${JAVA_HOME:-}}
if [[ -z "$jdk" || ! -x "$jdk/bin/javac" || ! "$("$jdk/bin/javac" -version)" =~ ^javac\ 26([.+-]|$) ]]; then
  as_root bash executor-native/prepare-jdk.sh "$stage/download"
  as_root mv "$stage/download/jdk-26.0.2+10" "$stage/jdk"
  jdk="$stage/jdk"
fi
export JAVA_HOME="$jdk" PATH="$jdk/bin:$PATH"
gradle_args=()
if [[ ${GITHUB_ACTIONS:-} == true || "$jdk" == "$stage/jdk" ]]; then gradle_args+=(--no-daemon); fi
./gradlew "${gradle_args[@]}" -PsandcrabBundle="$bundle" stageGameWorker stageAcceptanceRuntime
command -v mkfs.xfs >/dev/null || { as_root apt-get update -qq && as_root apt-get install -y --no-install-recommends xfsprogs; }
if [[ "$(cat /proc/sys/kernel/apparmor_restrict_unprivileged_userns 2>/dev/null || true)" == 1 ]]; then
  as_root apparmor_parser -r deploy/sandcrab.apparmor
fi
if [[ "$jdk" != "$stage/jdk" ]]; then as_root cp -RL "$jdk" "$stage/jdk"; fi
as_root cp -R build/acceptance/runtime "$stage/runtime"
as_root chmod -R a+rX,go-w "$stage/jdk" "$stage/runtime"
as_root bash executor-service/prepare-tools.sh "$stage/tools"
python3 -B -m unittest discover -s acceptance/clients -p test_native_jvm.py -v
as_root python3 -B -u acceptance/clients/jvm-footprint.py --runtime "$stage/runtime" \
  --binary build/game-worker/sandcrab --tools "$stage/tools" --java "$stage/jdk/bin/java" \
  --work "$stage/work" --evidence "$stage/results/jvm"
