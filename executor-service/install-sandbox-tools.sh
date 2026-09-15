#!/usr/bin/env bash
set -euo pipefail

# Provision shared, distribution-maintained tools; execute on the worker host as root.
if [[ $EUID -ne 0 ]]; then
    printf 'Run as root on the worker host.\n' >&2
    exit 1
fi
tools=$(realpath -e -- "${1:?Usage: install-sandbox-tools.sh EXISTING_TOOLS_DIRECTORY}")
test -f "$tools/node_modules/@anthropic-ai/sandbox-runtime/package.json"
test "$(stat -c %u -- "$tools")" = 0

apt-get update
apt-get install --yes --no-install-recommends \
    python3-pil python3-bs4 python3-lxml python3-pypdf \
    python3-openpyxl python3-docx mawk zip poppler-utils fonts-noto-cjk

# /etc/alternatives is outside the sandbox read allowlist.
install -d -m 755 -- "$tools/extracted/usr/bin"
ln -sfn /usr/bin/mawk "$tools/extracted/usr/bin/awk"
ln -sfn /usr/bin/python3 "$tools/extracted/usr/bin/python"
printf 'Installed shared image, HTML, PDF, Office and archive tools.\n'
