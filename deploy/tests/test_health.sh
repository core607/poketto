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

# Initial certificate issuance and later API readiness can fail independently. The same
# deployment must wait for both entrances before recording its first confirmed pins.
setup_root
have_image "$DIGEST_IMAGE"
sed -i.bak -e 's/^POKETTO_APP_IMAGE=.*/POKETTO_APP_IMAGE=/' -e 's/^POKETTO_APP_REVISION=.*/POKETTO_APP_REVISION=/' "$ROOT/.env"
echo 2 > "$FAKE_STATE/https-site-transient-failures"
echo 2 > "$FAKE_STATE/https-api-transient-failures"
POKETTO_HEALTH_TIMEOUT=10 run_deploy --app-image "$DIGEST_IMAGE" --app-revision "$REVISION"
assert_status 0
[ "$(cat "$FAKE_STATE/https-site-attempts")" = 3 ]
[ "$(cat "$FAKE_STATE/https-api-attempts")" = 3 ]
[ "$(up_count)" = 1 ]
grep -qx "POKETTO_APP_IMAGE=$DIGEST_IMAGE" "$ROOT/.env"
grep -qx "POKETTO_APP_REVISION=$REVISION" "$ROOT/.env"
[ ! -e "$ROOT/.env.previous" ]
assert_not_contains "$(cat "$FAKE_STATE/curl.log")" '--insecure'
