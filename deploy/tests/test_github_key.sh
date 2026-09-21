#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"

command -v openssl >/dev/null || { echo "GitHub key conversion tests require OpenSSL"; exit 1; }
if command -v python3 >/dev/null && python3 --version >/dev/null 2>&1; then interpreter=python3; else interpreter=python; fi
"$interpreter" -B "$DEPLOY_DIR/tests/verify_github_key.py"
