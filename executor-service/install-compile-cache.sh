#!/usr/bin/env bash
set -euo pipefail
umask 022

# Installs the V8 compile cache that launcher.py names in NODE_COMPILE_CACHE; execute on the worker
# host as root after prepare-tools.sh for each execution account sharing the tools, and again after
# changing Node or SRT. A code cache is executable, so no lease may write it: Node keys entries by
# uid, so the account fills a fresh private directory, and root installs that uid's part read-only.
if [[ $EUID -ne 0 ]]; then
    printf 'Run as root on the worker host.\n' >&2
    exit 1
fi
usage='Usage: install-compile-cache.sh EXISTING_TOOLS_DIRECTORY EXECUTION_ACCOUNT'
tools=$(realpath -e -- "${1:?$usage}")
cd /
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
# Node names the cache directory <version tag>-<uid>; other accounts' directories stay.
generated=("$staging"/*-"$uid")
[[ ${#generated[@]} -eq 1 && -d ${generated[0]} ]]
install -d -m 755 -- "$tools/compile-cache"
rm -rf -- "$tools/compile-cache/"*-"$uid"
cp -R -- "${generated[0]}" "$tools/compile-cache/"
# Node skips a cache it cannot read without a word, so read it as the account that will use it.
installed=$tools/compile-cache/${generated[0]##*/}
unreadable=$(setpriv --reuid="$uid" --regid="$gid" --clear-groups -- find "$installed" ! -readable -print -quit)
if [[ -n $unreadable ]]; then
    printf 'The execution account cannot read %s.\n' "$unreadable" >&2
    exit 1
fi
printf 'Installed the compile cache for uid %s in %s.\n' "$uid" "$tools/compile-cache"
