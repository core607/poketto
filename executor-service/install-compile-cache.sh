#!/usr/bin/env bash
set -euo pipefail

# Installs the V8 compile cache that launcher.py names in NODE_COMPILE_CACHE; execute on the worker
# host as root after prepare-tools.sh, and again after changing Node, SRT or the execution account.
# A code cache is executable, so no lease may write it: Node keys entries by uid, so the execution
# account fills a fresh private directory, and root installs the result read-only in the tools.
if [[ $EUID -ne 0 ]]; then
    printf 'Run as root on the worker host.\n' >&2
    exit 1
fi
usage='Usage: install-compile-cache.sh EXISTING_TOOLS_DIRECTORY EXECUTION_ACCOUNT'
tools=$(realpath -e -- "${1:?$usage}")
uid=$(id -u -- "${2:?$usage}")
gid=$(id -g -- "$2")
if (( uid == 0 )); then
    printf 'The execution account must be unprivileged.\n' >&2
    exit 1
fi
test "$(stat -c %u -- "$tools")" = 0
test ! -L "$tools/compile-cache"
srt=$tools/node_modules/@anthropic-ai/sandbox-runtime/dist/cli.js
test -f "$srt"

staging=$(mktemp -d)
trap 'rm -rf -- "$staging"' EXIT
chown -- "$uid:$gid" "$staging"
# Loading the CLI compiles every module a sandbox start loads; --help then exits without a sandbox.
setpriv --reuid="$uid" --regid="$gid" --clear-groups -- \
    env -i NODE_COMPILE_CACHE="$staging" "$tools/node" "$srt" --help >/dev/null
chown -R root:root -- "$staging"
chmod -R u=rwX,go=rX -- "$staging"
rm -rf -- "$tools/compile-cache"
cp -R -- "$staging" "$tools/compile-cache"
printf 'Installed the compile cache for uid %s in %s.\n' "$uid" "$tools/compile-cache"
