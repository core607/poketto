#!/usr/bin/env bash
set -euo pipefail

# Provision shared, distribution-maintained tools; execute on the worker host as root.
if [[ $EUID -ne 0 ]]; then
    printf 'Run as root on the worker host.\n' >&2
    exit 1
fi
tools=$(realpath -e -- "${1:?Usage: install-sandbox-tools.sh EXISTING_TOOLS_DIRECTORY}")
test -x "$tools/extracted/usr/bin/bwrap"
for directory in "$tools" "$tools/extracted" "$tools/extracted/usr" "$tools/extracted/usr/bin"; do
    test ! -L "$directory"
    if [[ -e $directory ]]; then
        test -d "$directory"
        test "$(stat -c %u -- "$directory")" = 0
        mode=$(stat -c %a -- "$directory")
        (( (8#$mode & 0022) == 0 ))
    fi
done
for alias in python awk; do
    target="$tools/extracted/usr/bin/$alias"
    if [[ -e $target && ! -L $target ]]; then
        printf 'Refusing to replace a non-symlink: %s\n' "$target" >&2
        exit 1
    fi
done

apt-get update
apt-get install --yes --no-install-recommends \
    curl ca-certificates xz-utils \
    python3-pil python3-bs4 python3-lxml python3-pypdf \
    python3-openpyxl python3-docx mawk zip poppler-utils fonts-noto-cjk

if [[ ! -e $tools/node ]]; then
    bash "$(dirname -- "$0")/prepare-node.sh" "$tools"
fi

# /etc/alternatives is outside the sandbox read allowlist.
test -x /usr/bin/python3
test -x /usr/bin/mawk
install -d -m 755 -- "$tools/extracted/usr/bin"
ln -sfn /usr/bin/mawk "$tools/extracted/usr/bin/awk"
ln -sfn /usr/bin/python3 "$tools/extracted/usr/bin/python"
printf 'Installed shared image, HTML, PDF, Office and archive tools.\n'
