#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"

# A first deployment fills blank pins and has no previous version to save.
setup_root
have_image "$DIGEST_IMAGE"
sed -i.bak -e 's/^POKETTO_APP_IMAGE=.*/POKETTO_APP_IMAGE=/' -e 's/^POKETTO_APP_REVISION=.*/POKETTO_APP_REVISION=/' "$ROOT/.env"
run_deploy --app-image "$DIGEST_IMAGE" --app-revision "$REVISION"
assert_status 0
grep -qx "POKETTO_APP_IMAGE=$DIGEST_IMAGE" "$ROOT/.env"
[ ! -e "$ROOT/.env.previous" ] || { echo ".env.previous was written on the first deployment"; exit 1; }

# Options are recorded into .env once the deployment is healthy, the pins they replace land in
# .env.previous, and both files keep the restricted mode.
new_revision="89abcdef0123456789abcdef0123456789abcdef"
new_image="ghcr.io/core607/poketto@sha256:3333333333333333333333333333333333333333333333333333333333333333"
have_image "$new_image" "$new_revision"
new_frontend="ghcr.io/core607/poketto-frontend:sha-$new_revision"
have_image "$new_frontend" "$new_revision"
run_deploy --app-image "$new_image" --app-revision "$new_revision" --frontend-image "$new_frontend"
assert_status 0
grep -qx "POKETTO_APP_IMAGE=$new_image" "$ROOT/.env"
grep -qx "POKETTO_APP_REVISION=$new_revision" "$ROOT/.env"
grep -qx "POKETTO_APP_IMAGE=$DIGEST_IMAGE" "$ROOT/.env.previous"
grep -qx "POKETTO_APP_REVISION=$REVISION" "$ROOT/.env.previous"
grep -qx "POKETTO_DB_IMAGE=$DB_IMAGE" "$ROOT/.env.previous"
# Windows filesystems under Git Bash report Unix modes only approximately, so the mode check
# runs where chmod is authoritative.
probe="$PWD/mode-probe"
touch "$probe" && chmod 600 "$probe"
if [ "$(stat -c %a "$probe" 2>/dev/null || stat -f %Lp "$probe")" = 600 ]; then
    for file in .env .env.previous; do
        [ "$(stat -c %a "$ROOT/$file" 2>/dev/null || stat -f %Lp "$ROOT/$file")" = 600 ] || { echo "$file is not mode 600"; exit 1; }
    done
fi

# A rerun without options redeploys the recorded pins with identical arguments and leaves both
# files byte-identical.
cp "$ROOT/.env" "$PWD/env.before"
cp "$ROOT/.env.previous" "$PWD/previous.before"
run_deploy
assert_status 0
cmp -s "$ROOT/.env" "$PWD/env.before" || { echo ".env changed on rerun"; exit 1; }
cmp -s "$ROOT/.env.previous" "$PWD/previous.before" || { echo ".env.previous changed on rerun"; exit 1; }
[ "$(up_count)" = 3 ]
[ "$(sort -u "$FAKE_STATE/up.log" | wc -l | tr -d ' ')" = 1 ] || { echo "up arguments differed between runs"; exit 1; }
assert_contains "$OUT" "revision=$new_revision"

# A candidate whose deployment fails never becomes the recorded pin: both files stay
# byte-identical and the next rerun without options deploys the confirmed pins again.
bad_revision="5555555555555555555555555555555555555555"
bad_image="ghcr.io/core607/poketto@sha256:5555555555555555555555555555555555555555555555555555555555555555"
have_image "$bad_image" "$bad_revision"
bad_frontend="ghcr.io/core607/poketto-frontend:sha-$bad_revision"
have_image "$bad_frontend" "$bad_revision"
echo unhealthy > "$FAKE_STATE/health"
run_deploy --app-image "$bad_image" --app-revision "$bad_revision" --frontend-image "$bad_frontend"
assert_status 1
assert_contains "$ERR" "reported unhealthy"
cmp -s "$ROOT/.env" "$PWD/env.before" || { echo ".env recorded a failed candidate"; exit 1; }
cmp -s "$ROOT/.env.previous" "$PWD/previous.before" || { echo ".env.previous changed on a failed candidate"; exit 1; }
rm -f "$FAKE_STATE/health"
run_deploy
assert_status 0
assert_contains "$OUT" "revision=$new_revision"

# The pins saved in .env.previous redeploy the last healthy version when passed as options.
previous_image="$(grep '^POKETTO_APP_IMAGE=' "$ROOT/.env.previous" | cut -d = -f 2-)"
previous_revision="$(grep '^POKETTO_APP_REVISION=' "$ROOT/.env.previous" | cut -d = -f 2-)"
previous_frontend="$(grep '^POKETTO_FRONTEND_IMAGE=' "$ROOT/.env.previous" | cut -d = -f 2-)"
run_deploy --app-image "$previous_image" --app-revision "$previous_revision" --frontend-image "$previous_frontend"
assert_status 0
assert_contains "$OUT" "revision=$REVISION"
grep -qx "POKETTO_APP_IMAGE=$DIGEST_IMAGE" "$ROOT/.env"
grep -qx "POKETTO_APP_IMAGE=$new_image" "$ROOT/.env.previous"

# Settings arrive on standard input and are recorded without appearing in the command line.
# They are recorded only after health as well, and unchanged pins write no .env.previous.
setup_root
have_image "$DIGEST_IMAGE"
sed -i.bak 's/^POKETTO_REPOSITORY_PASSWORD=.*/POKETTO_REPOSITORY_PASSWORD=/' "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "POKETTO_REPOSITORY_PASSWORD"
cp "$ROOT/.env" "$PWD/env.before"
echo unhealthy > "$FAKE_STATE/health"
set +e
OUT="$(printf 'POKETTO_REPOSITORY_PASSWORD=from-stdin\nPOKETTO_HTTP_PORT=8081\n' | bash "$ROOT/deploy.sh" --set-stdin 2> "$PWD/stderr")"
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 1
cmp -s "$ROOT/.env" "$PWD/env.before" || { echo "a failed run recorded stdin settings"; exit 1; }
rm -f "$FAKE_STATE/health"
set +e
OUT="$(printf 'POKETTO_REPOSITORY_PASSWORD=from-stdin\nPOKETTO_HTTP_PORT=8081\n' | bash "$ROOT/deploy.sh" --set-stdin 2> "$PWD/stderr")"
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 0
grep -qx "POKETTO_REPOSITORY_PASSWORD=from-stdin" "$ROOT/.env" || { echo "stdin setting was not recorded"; exit 1; }
grep -qx "POKETTO_HTTP_PORT=8081" "$ROOT/.env" || { echo "appended setting was not recorded"; exit 1; }
[ ! -e "$ROOT/.env.previous" ] || { echo ".env.previous was written although no pin changed"; exit 1; }
assert_contains "$(cat "$FAKE_STATE/curl.log")" ":8081/"
assert_not_contains "$OUT$ERR" "from-stdin"

# A malformed line is refused before anything is recorded.
set +e
OUT="$(printf 'lowercase=1\n' | bash "$ROOT/deploy.sh" --set-stdin 2> "$PWD/stderr")"
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 1
assert_contains "$ERR" "uppercase key"
