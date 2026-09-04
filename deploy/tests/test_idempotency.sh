#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"
setup_root
have_image "$DIGEST_IMAGE"

# Options are recorded into .env, and the file keeps its restricted mode.
new_revision="89abcdef0123456789abcdef0123456789abcdef"
new_image="ghcr.io/core607/poketto@sha256:3333333333333333333333333333333333333333333333333333333333333333"
have_image "$new_image" "$new_revision"
run_deploy --app-image "$new_image" --app-revision "$new_revision"
assert_status 0
grep -qx "POKETTO_APP_IMAGE=$new_image" "$ROOT/.env"
grep -qx "POKETTO_APP_REVISION=$new_revision" "$ROOT/.env"
# Windows filesystems under Git Bash report Unix modes only approximately, so the mode check
# runs where chmod is authoritative.
probe="$PWD/mode-probe"
touch "$probe" && chmod 600 "$probe"
if [ "$(stat -c %a "$probe" 2>/dev/null || stat -f %Lp "$probe")" = 600 ]; then
    [ "$(stat -c %a "$ROOT/.env" 2>/dev/null || stat -f %Lp "$ROOT/.env")" = 600 ] || { echo ".env is not mode 600"; exit 1; }
fi

# A rerun without options redeploys the recorded pins with identical arguments and leaves .env
# byte-identical.
cp "$ROOT/.env" "$PWD/env.before"
run_deploy
assert_status 0
cmp -s "$ROOT/.env" "$PWD/env.before" || { echo ".env changed on rerun"; exit 1; }
[ "$(up_count)" = 2 ]
[ "$(sort -u "$FAKE_STATE/up.log" | wc -l | tr -d ' ')" = 1 ] || { echo "up arguments differed between runs"; exit 1; }
assert_contains "$OUT" "revision=$new_revision"

# Settings arrive on standard input and are recorded without appearing in the command line.
setup_root
have_image "$DIGEST_IMAGE"
sed -i.bak 's/^POKETTO_REPOSITORY_PASSWORD=.*/POKETTO_REPOSITORY_PASSWORD=/' "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "POKETTO_REPOSITORY_PASSWORD"
set +e
OUT="$(printf 'POKETTO_REPOSITORY_PASSWORD=from-stdin\nPOKETTO_HTTP_PORT=8081\n' | bash "$ROOT/deploy.sh" --set-stdin 2> "$PWD/stderr")"
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 0
grep -qx "POKETTO_REPOSITORY_PASSWORD=from-stdin" "$ROOT/.env" || { echo "stdin setting was not recorded"; exit 1; }
grep -qx "POKETTO_HTTP_PORT=8081" "$ROOT/.env" || { echo "appended setting was not recorded"; exit 1; }
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
