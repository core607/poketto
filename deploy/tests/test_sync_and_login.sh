#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"

# Staged files replace the root copies only inside the lock, and the staged directory is gone.
setup_root
have_image "$DIGEST_IMAGE"
mkdir -p "$ROOT/.incoming"
{ cat "$DEPLOY_DIR/compose.yaml"; echo "# synced"; } > "$ROOT/.incoming/compose.yaml"
{ cat "$DEPLOY_DIR/deploy.sh"; echo "# synced"; } > "$ROOT/.incoming/deploy.sh"
sleep 30 &
holder=$!
mkdir "$ROOT/.deploy.lock"
echo "$holder" > "$ROOT/.deploy.lock/pid"
run_deploy --sync-from "$ROOT/.incoming"
kill "$holder"
assert_status 75
grep -q "# synced" "$ROOT/compose.yaml" && { echo "compose.yaml was replaced while the lock was held"; exit 1; }
rm -rf "$ROOT/.deploy.lock"
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

# Registry credentials on standard input log in for the pull, are never recorded, and the
# session is logged out again.
setup_root
echo "$REVISION" > "$FAKE_STATE/pull-revision"
set +e
OUT="$(printf 'REGISTRY_USERNAME=bot\nREGISTRY_PASSWORD=ghs_token\n' | bash "$ROOT/deploy.sh" --set-stdin 2> "$PWD/stderr")"
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 0
assert_contains "$(docker_log)" "login ghcr.io --username bot --password-stdin"
assert_contains "$(cat "$FAKE_STATE/login-stdin")" "ghs_token"
assert_not_contains "$(docker_log)" "ghs_token"
assert_contains "$(docker_log)" "logout ghcr.io"
grep -q "REGISTRY" "$ROOT/.env" && { echo "registry credentials were recorded"; exit 1; }
assert_contains "$(docker_log)" "pull $DIGEST_IMAGE"

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
