#!/usr/bin/env bash
# Runs every deploy/tests/test_*.sh in its own temporary directory with the fake docker, curl,
# and ssh commands first on PATH. Exit status is non-zero when any test fails.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export DEPLOY_DIR="$(cd "$HERE/.." && pwd)"
export PATH="$HERE/bin:$PATH"
chmod +x "$HERE"/bin/* 2>/dev/null || true

passed=0
failed=0
for test in "$HERE"/test_*.sh; do
    work="$(mktemp -d)"
    if output="$(cd "$work" && FAKE_STATE="$work/state" bash "$test" 2>&1)"; then
        passed=$((passed + 1))
        echo "ok   $(basename "$test")"
    else
        failed=$((failed + 1))
        echo "FAIL $(basename "$test")"
        printf '%s\n' "$output" | sed 's/^/     /'
    fi
    rm -rf "$work"
done
echo "deploy script tests: $passed passed, $failed failed"
[ "$failed" = 0 ]
