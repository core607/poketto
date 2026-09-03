#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"

# A container that never becomes healthy fails with diagnostics after the timeout.
setup_root
have_image "$DIGEST_IMAGE"
echo starting > "$FAKE_STATE/health"
POKETTO_HEALTH_TIMEOUT=1 run_deploy
assert_status 1
assert_contains "$ERR" "did not become healthy within 1s"
assert_contains "$ERR" "recent app log"
assert_contains "$ERR" "fake app log line"

# An unhealthy container fails immediately.
setup_root
have_image "$DIGEST_IMAGE"
echo unhealthy > "$FAKE_STATE/health"
run_deploy
assert_status 1
assert_contains "$ERR" "reported unhealthy"

# A healthy container whose real entrance does not answer still fails.
setup_root
have_image "$DIGEST_IMAGE"
touch "$FAKE_STATE/curl-fails"
run_deploy
assert_status 1
assert_contains "$ERR" "health entrance at 127.0.0.1:8080 did not answer"

# An entrance answering without UP fails too.
setup_root
have_image "$DIGEST_IMAGE"
echo '{"status":"DOWN"}' > "$FAKE_STATE/curl-body"
run_deploy
assert_status 1
assert_contains "$ERR" "answered without UP"
