#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"
setup_root
echo "$REVISION" > "$FAKE_STATE/pull-revision"
printf 'starting\nstarting\nhealthy\n' > "$FAKE_STATE/health"

run_deploy
assert_status 0
assert_contains "$OUT" "deploy: healthy"
assert_contains "$(docker_log)" "pull $DIGEST_IMAGE"
assert_contains "$(cat "$FAKE_STATE/up.log")" "--pull never"
assert_contains "$(cat "$FAKE_STATE/curl.log")" "http://127.0.0.1:8080/actuator/health"
assert_not_contains "$(docker_log)" "down"
assert_not_contains "$(docker_log)" "volume"
[ -d "$ROOT/data" ] && [ -d "$ROOT/db" ] || { echo "persistent directories were not created"; exit 1; }

# The database image was already present, so it was not pulled again.
assert_not_contains "$(docker_log)" "pull $DB_IMAGE"

# A pull failure stops before anything starts.
setup_root
touch "$FAKE_STATE/pull-fails"
run_deploy
assert_status 1
assert_contains "$ERR" "cannot pull $DIGEST_IMAGE"
[ "$(up_count)" = 0 ]
