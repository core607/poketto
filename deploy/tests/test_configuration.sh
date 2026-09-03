#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"

# No .env: an actionable message, nothing started.
mkdir -p "$ROOT"
cp "$DEPLOY_DIR/compose.yaml" "$DEPLOY_DIR/deploy.sh" "$ROOT/"
run_deploy
assert_status 1
assert_contains "$ERR" "missing $ROOT/.env"
assert_contains "$ERR" ".env.example"

# Blank values: every missing key is listed at once.
setup_root
sed -i.bak -e 's/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=/' -e 's/^POKETTO_REPOSITORY_REMOTE_URI=.*/POKETTO_REPOSITORY_REMOTE_URI=/' "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "missing values"
assert_contains "$ERR" "POSTGRES_PASSWORD"
assert_contains "$ERR" "POKETTO_REPOSITORY_REMOTE_URI"
[ "$(up_count)" = 0 ]

# A database image without a digest is refused.
setup_root
sed -i.bak 's/^POKETTO_DB_IMAGE=.*/POKETTO_DB_IMAGE=postgres:17/' "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "POKETTO_DB_IMAGE must be pinned"

# A short revision is refused.
setup_root
sed -i.bak 's/^POKETTO_APP_REVISION=.*/POKETTO_APP_REVISION=abc123/' "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "POKETTO_APP_REVISION must be a full lowercase commit id"

# Options must travel together.
setup_root
run_deploy --app-image "$DIGEST_IMAGE"
assert_status 1
assert_contains "$ERR" "given together"
