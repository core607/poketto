#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"

# Staged files replace the root copies only inside the lock, and the staged directory is gone.
setup_root
have_image "$DIGEST_IMAGE"
mkdir -p "$ROOT/.incoming"
{ cat "$DEPLOY_DIR/compose.yaml"; echo "# synced"; } > "$ROOT/.incoming/compose.yaml"
{ cat "$DEPLOY_DIR/deploy.sh"; echo "# synced"; } > "$ROOT/.incoming/deploy.sh"
hold_deploy_lock
run_deploy --sync-from "$ROOT/.incoming"
assert_status 75
grep -q "# synced" "$ROOT/compose.yaml" && { echo "compose.yaml was replaced while the lock was held"; exit 1; }
release_deploy_lock
run_deploy --sync-from "$ROOT/.incoming"
assert_status 0
grep -q "# synced" "$ROOT/compose.yaml" || { echo "compose.yaml was not replaced"; exit 1; }
grep -q "# synced" "$ROOT/deploy.sh" || { echo "deploy.sh was not replaced"; exit 1; }
[ ! -d "$ROOT/.incoming" ] || { echo "staging directory remained"; exit 1; }
[ -x "$ROOT/deploy.sh" ] || { echo "deploy.sh lost its executable bit"; exit 1; }

# A missing staging directory is an error before anything else happens.
run_deploy --sync-from "$ROOT/nowhere"
assert_status 1
assert_contains "$ERR" "does not exist"

# Registry credentials on standard input log in inside a Docker configuration directory that
# exists only for this run: every later docker command uses it, it is deleted at exit, the
# deployment user's own configuration is never written, and nothing is recorded.
setup_root
echo "$REVISION" > "$FAKE_STATE/pull-revision"
mkdir -p "$PWD/home"
set +e
OUT="$(printf 'REGISTRY_USERNAME=bot\nREGISTRY_PASSWORD=ghs_token\n' | HOME="$PWD/home" bash "$ROOT/deploy.sh" --set-stdin 2> "$PWD/stderr")"
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 0
assert_contains "$(docker_log)" "login ghcr.io --username bot --password-stdin"
assert_contains "$(cat "$FAKE_STATE/login-stdin")" "ghs_token"
assert_not_contains "$(docker_log)" "ghs_token"
[ ! -e "$PWD/home/.docker/config.json" ] || { echo "the user's Docker configuration was written"; exit 1; }
config="$(grep '^login ' "$FAKE_STATE/docker-config.log" | cut -d ' ' -f 2-)"
[ -n "$config" ] || { echo "the login ran without a temporary DOCKER_CONFIG"; exit 1; }
grep -qx "pull $config" "$FAKE_STATE/docker-config.log" || { echo "the pull did not use the login's DOCKER_CONFIG"; exit 1; }
grep -qx "compose $config" "$FAKE_STATE/docker-config.log" || { echo "compose did not use the login's DOCKER_CONFIG"; exit 1; }
[ ! -d "$config" ] || { echo "the temporary Docker configuration remained"; exit 1; }
grep -q "REGISTRY" "$ROOT/.env" && { echo "registry credentials were recorded"; exit 1; }
assert_contains "$(docker_log)" "pull $DIGEST_IMAGE"

# Without streamed credentials the deployment user's own Docker configuration stays in effect.
setup_root
have_image "$DIGEST_IMAGE"
run_deploy
assert_status 0
grep -q '^compose [^ ]' "$FAKE_STATE/docker-config.log" && { echo "DOCKER_CONFIG was redirected without a login"; exit 1; }

# A password without a user name is refused; a failed login stops before any pull.
setup_root
set +e
printf 'REGISTRY_PASSWORD=x\n' | bash "$ROOT/deploy.sh" --set-stdin 2> "$PWD/stderr"
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 1
assert_contains "$ERR" "requires REGISTRY_USERNAME"
setup_root
set +e
FAKE_LOGIN_EXIT=1 bash "$ROOT/deploy.sh" --set-stdin 2> "$PWD/stderr" <<< $'REGISTRY_USERNAME=bot\nREGISTRY_PASSWORD=x'
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 1
assert_contains "$ERR" "registry login to ghcr.io failed"
assert_not_contains "$(docker_log)" "pull"
