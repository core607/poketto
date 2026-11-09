#!/usr/bin/env bash
# Complete public-corpus acceptance using a verified distribution on an isolated runner.
set -euo pipefail

: "${SANDCRAB_BUNDLE:?Supply the pinned release ZIP}"
: "${SANDCRAB_EVIDENCE:?Supply an empty results directory}"
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || {
  echo 'Complete-corpus provisioning requires a disposable hosted runner.' >&2; exit 2;
}
bundle=$(realpath -e -- "$SANDCRAB_BUNDLE")
evidence=$(realpath -e -- "$SANDCRAB_EVIDENCE")
[[ -f "$bundle" && -r "$bundle" && -d "$evidence" && -w "$evidence" && -x "$evidence" ]]
[[ -z "$(find "$evidence" -mindepth 1 -maxdepth 1 -print -quit)" ]]
[[ "$(node --version)" == v24.19.0 && "$(python --version)" == 'Python 3.12.14' ]]
cd "$(git rev-parse --show-toplevel)"
as_root() {
  if (( EUID == 0 )); then "$@"; else sudo -- "$@"; fi
}
stage=$(as_root mktemp -d /var/lib/poketto-corpus-check.XXXXXXXX)
as_root chmod 0755 "$stage"
as_root mkdir -m 711 "$stage/results"
data=''
cleanup() {
  local verdict=$?
  trap - EXIT
  [[ "$stage" == /var/lib/poketto-corpus-check.* && "$(realpath -- "$stage")" == "$stage" ]] || exit 1
  if [[ -n "$data" ]]; then
    for name in audit.json downloads.json; do
      [[ ! -f "$data/$name" ]] || as_root cp "$data/$name" "$stage/results/$name"
    done
  fi
  if ! as_root tar -C "$stage/results" -cf - . | tar -C "$evidence" --no-same-owner --no-same-permissions -xf -; then
    echo "Evidence transfer failed. Inspect $stage/results." >&2
    exit 1
  fi
  as_root rm -rf --one-file-system -- "$stage"
  exit "$verdict"
}
trap cleanup EXIT
as_root python3 - "$stage/results/storage-preparation.json" <<'PY'
import json
from pathlib import Path
import shutil
import subprocess
import sys

before = shutil.disk_usage('/var/lib').free
removed = []
for name in ('/usr/share/dotnet', '/usr/local/lib/android'):
    path = Path(name)
    if path.exists():
        if path.resolve() != path or not path.is_dir():
            raise RuntimeError('The hosted runner SDK directory is not canonical: ' + name)
        subprocess.run(['rm', '-rf', '--one-file-system', '--', name], check=True, timeout=180)
        removed.append(name)
Path(sys.argv[1]).write_text(json.dumps({'freeBytesBefore': before,
    'freeBytesAfter': shutil.disk_usage('/var/lib').free, 'removedUnusedHostedSdks': removed}, indent=2) + '\n')
PY
jdk=${JAVA_HOME_26_X64:-${JAVA_HOME:-}}
if [[ -z "$jdk" || ! -x "$jdk/bin/javac" || ! "$("$jdk/bin/javac" -version)" =~ ^javac\ 26([.+-]|$) ]]; then
  as_root bash executor-native/prepare-jdk.sh "$stage/download"
  as_root mv "$stage/download/jdk-26.0.2+10" "$stage/jdk"
  as_root chmod -R a+rX,go-w "$stage/jdk"
  jdk="$stage/jdk"
fi
export JAVA_HOME="$jdk" PATH="$jdk/bin:$PATH"
./gradlew --no-daemon -PsandcrabBundle="$bundle" stageGameWorker
npm install --global npm@12.0.2
npm --prefix experiments/retrieval-lab ci --ignore-scripts --no-audit --no-fund
npm --prefix experiments/retrieval-lab run build
python -m pip install --disable-pip-version-check --no-cache-dir -r experiments/retrieval-lab/python/requirements.txt
data=$(mktemp -d "$RUNNER_TEMP/fiqa-data.XXXXXXXX")
for step in fetch audit organize; do
  python experiments/retrieval-lab/python/prepare.py "$step" --data "$data"
done
as_root apt-get update -qq
as_root apt-get install --yes --no-install-recommends xfsprogs ripgrep
if [[ "$(cat /proc/sys/kernel/apparmor_restrict_unprivileged_userns 2>/dev/null || true)" == 1 ]]; then
  as_root apparmor_parser -r deploy/sandcrab.apparmor
fi
as_root env RUNNER_ENVIRONMENT="$RUNNER_ENVIRONMENT" /usr/bin/python3 experiments/retrieval-lab/python/ci_service.py \
  --uid "$(id -u)" --gid "$(id -g)" --binaries build/game-worker \
  --node "$(command -v node)" --data "$data" --output "$stage/results/corpus"
