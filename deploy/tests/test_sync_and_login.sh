#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"

# Staged files replace the root copies only inside the lock. The staging directory of this
# revision is gone afterwards; another revision staged beside it is untouched.
setup_root
have_image "$DIGEST_IMAGE"
staged="$ROOT/.incoming/$REVISION"
other="$ROOT/.incoming/89abcdef0123456789abcdef0123456789abcdef"
mkdir -p "$staged" "$other"
{ cat "$DEPLOY_DIR/compose.yaml"; echo "# synced"; } > "$staged/compose.yaml"
cp "$DEPLOY_DIR/deploy.sh" "$staged/deploy.sh"
echo "# other revision" > "$other/compose.yaml"
hold_deploy_lock
run_deploy --sync-from "$staged"
assert_status 75
grep -q "# synced" "$ROOT/compose.yaml" && { echo "compose.yaml was replaced while the lock was held"; exit 1; }
release_deploy_lock
run_deploy --sync-from "$staged"
assert_status 0
grep -q "# synced" "$ROOT/compose.yaml" || { echo "compose.yaml was not replaced"; exit 1; }
[ ! -d "$staged" ] || { echo "staging directory remained"; exit 1; }
grep -q "# other revision" "$other/compose.yaml" || { echo "another revision's staged files were touched"; exit 1; }
[ -x "$ROOT/deploy.sh" ] || { echo "deploy.sh lost its executable bit"; exit 1; }

# A staged deploy.sh that differs from the running script is installed and then re-executed
# with the same arguments minus --sync-from, under the lock the run already holds and before
# standard input is read, so the settings reach the new entrance and are recorded after health.
setup_root
have_image "$DIGEST_IMAGE"
mkdir -p "$staged"
{ cat "$DEPLOY_DIR/deploy.sh"; echo 'echo "deploy: synced entrance ran"'; } > "$staged/deploy.sh"
set +e
OUT="$(printf 'POKETTO_HTTP_PORT=8081\n' | bash "$ROOT/deploy.sh" --sync-from "$staged" --set-stdin 2> "$PWD/stderr")"
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 0
assert_contains "$OUT" "synced entrance ran"
grep -q "synced entrance ran" "$ROOT/deploy.sh" || { echo "deploy.sh was not replaced"; exit 1; }
[ ! -d "$staged" ] || { echo "staging directory remained"; exit 1; }
assert_contains "$(cat "$FAKE_STATE/curl.log")" ":8081/"
grep -qx "POKETTO_HTTP_PORT=8081" "$ROOT/.env" || { echo "the setting did not reach the re-executed entrance"; exit 1; }
[ "$(up_count)" = 1 ] || { echo "the stack was started more than once"; exit 1; }
[ ! -d "$ROOT/.deploy.lock.d" ] || { echo "the lock was not released"; exit 1; }
run_deploy
assert_status 0

# A missing staging directory is an error before anything else happens.
run_deploy --sync-from "$ROOT/nowhere"
assert_status 1
assert_contains "$ERR" "does not exist"

# Registry credentials on standard input log in inside a Docker configuration directory that
# exists only for this run. It starts as a copy of the user's config.json with the cli-plugins
# directory linked in, every later docker command uses it, it is deleted at exit, the user's
# own configuration stays byte-identical, and nothing is recorded.
setup_root
echo "$REVISION" > "$FAKE_STATE/pull-revision"
mkdir -p "$PWD/home/.docker/cli-plugins"
echo '{"currentContext":"remote","proxies":{"default":{"httpProxy":"http://proxy.example.invalid:3128"}}}' > "$PWD/home/.docker/config.json"
touch "$PWD/home/.docker/cli-plugins/docker-compose"
cp "$PWD/home/.docker/config.json" "$PWD/config.before"
set +e
OUT="$(printf 'REGISTRY_USERNAME=bot\nREGISTRY_PASSWORD=ghs_token\n' | HOME="$PWD/home" bash "$ROOT/deploy.sh" --set-stdin 2> "$PWD/stderr")"
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 0
assert_contains "$(docker_log)" "login ghcr.io --username bot --password-stdin"
assert_contains "$(cat "$FAKE_STATE/login-stdin")" "ghs_token"
assert_not_contains "$(docker_log)" "ghs_token"
cmp -s "$PWD/home/.docker/config.json" "$PWD/config.before" || { echo "the user's Docker configuration was modified"; exit 1; }
cmp -s "$FAKE_STATE/login-config-before" "$PWD/config.before" || { echo "the login did not start from a copy of the user's configuration"; exit 1; }
[ -e "$FAKE_STATE/login-saw-plugins" ] || { echo "the user's cli-plugins were not available to the run"; exit 1; }
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
