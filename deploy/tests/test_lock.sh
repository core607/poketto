#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"
setup_root
have_image "$DIGEST_IMAGE"

# A live holder keeps the lock: exit 75 and nothing else happens.
sleep 30 &
holder=$!
mkdir "$ROOT/.deploy.lock"
echo "$holder" > "$ROOT/.deploy.lock/pid"
run_deploy
kill "$holder"
assert_status 75
assert_contains "$ERR" "another deployment (pid $holder)"
[ "$(up_count)" = 0 ]

# A dead holder is residue: the lock is taken over and the deployment proceeds.
echo 999999 > "$ROOT/.deploy.lock/pid"
run_deploy
assert_status 0
[ ! -d "$ROOT/.deploy.lock" ] || { echo "lock was not released"; exit 1; }
