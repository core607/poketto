#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"
setup_root
have_image "$DIGEST_IMAGE"

# A live holder keeps the lock: exit 75 and nothing else happens.
hold_deploy_lock
holder="$TEST_LOCK_HOLDER"
run_deploy
assert_status 75
assert_contains "$ERR" "another deployment (pid $holder)"
[ "$(up_count)" = 0 ]
release_deploy_lock

# flock releases automatically when its process exits, so stale PID text never blocks a retry.
# The Windows directory fallback refuses an abandoned lock until an operator removes it.
if command -v flock >/dev/null 2>&1; then
    printf '%s\n' 999999 > "$ROOT/.deploy.lock"
else
    mkdir "$ROOT/.deploy.lock.d"
    printf '%s\n' 999999 > "$ROOT/.deploy.lock.d/pid"
    run_deploy
    assert_status 75
    assert_contains "$ERR" "interrupted run"
    rm -rf "$ROOT/.deploy.lock.d"
fi
run_deploy
assert_status 0
