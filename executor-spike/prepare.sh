#!/usr/bin/env bash
set -euo pipefail

# Prepare disposable dependencies without changing globally installed packages.
tools=${1:?Usage: prepare.sh NEW_TOOLS_DIRECTORY}
source_dir=$(cd -- "$(dirname -- "$0")" && pwd)
command -v node >/dev/null
command -v npm >/dev/null
command -v apt >/dev/null
mkdir -- "$tools"
tools=$(cd -- "$tools" && pwd)
install -m 755 "$(command -v node)" "$tools/node"
cp -- "$source_dir/package.json" "$source_dir/package-lock.json" "$tools/"
(
    cd -- "$tools"
    npm ci --ignore-scripts --no-audit --no-fund
    apt download bubblewrap socat ripgrep
    for package in ./*.deb; do
        dpkg-deb -x "$package" extracted
    done
)
chmod -R a+rX -- "$tools"
printf 'Prepared disposable tools; run probe.py --tools %q as root.\n' "$tools"
