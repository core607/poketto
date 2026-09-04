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

# Values are literal data: shell metacharacters survive unchanged and command substitutions never
# execute. The exported values take precedence over Compose's disabled automatic .env parser.
setup_root
have_image "$DIGEST_IMAGE"
set +e
OUT="$(printf '%s\n' \
    'POKETTO_REPOSITORY_REMOTE_URI=https://git.example.invalid/content.git?token=x&expiry=y' \
    'POKETTO_REPOSITORY_PASSWORD=$(touch env-was-executed)' \
    | POKETTO_CAPTURE_ENV=1 bash "$ROOT/deploy.sh" --set-stdin 2> "$PWD/stderr")"
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 0
[ ! -e "$PWD/env-was-executed" ] || { echo "an .env value executed as shell code"; exit 1; }
assert_contains "$(cat "$FAKE_STATE/repository-uri")" "?token=x&expiry=y"
assert_contains "$(cat "$FAKE_STATE/repository-password")" '$(touch env-was-executed)'
grep -qFx 'POKETTO_REPOSITORY_PASSWORD=$(touch env-was-executed)' "$ROOT/.env" \
    || { echo "the literal password was not recorded"; exit 1; }

# Unknown keys cannot alter the deployment process environment.
setup_root
printf '%s\n' 'PATH=/tmp/untrusted' >> "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "unsupported configuration key"
assert_contains "$ERR" "PATH"

# Persistent directories are compared as canonical paths: they must differ, must not nest, and
# must not be or contain the deployment root.
setup_root
sed -i.bak "s#^POKETTO_DB_DIR_HOST=.*#POKETTO_DB_DIR_HOST=$ROOT/db/../data#" "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "must be different directories"
setup_root
sed -i.bak "s#^POKETTO_DB_DIR_HOST=.*#POKETTO_DB_DIR_HOST=$ROOT/data/db#" "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "lies inside POKETTO_DATA_DIR_HOST"
setup_root
sed -i.bak "s#^POKETTO_DATA_DIR_HOST=.*#POKETTO_DATA_DIR_HOST=$ROOT/db/blobs#" "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "lies inside POKETTO_DB_DIR_HOST"
setup_root
sed -i.bak -e "s#^POKETTO_DATA_DIR_HOST=.*#POKETTO_DATA_DIR_HOST=$ROOT#" -e "s#^POKETTO_DB_DIR_HOST=.*#POKETTO_DB_DIR_HOST=$PWD/db#" "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "is or contains the deployment root"
setup_root
sed -i.bak -e "s#^POKETTO_DATA_DIR_HOST=.*#POKETTO_DATA_DIR_HOST=$PWD/../outside-data#" -e "s#^POKETTO_DB_DIR_HOST=.*#POKETTO_DB_DIR_HOST=$PWD#" "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "is or contains the deployment root"
[ "$(up_count)" = 0 ] || { echo "the stack started with invalid directories"; exit 1; }
