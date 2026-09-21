#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"

github_settings() {
    printf '%s\n' 'POKETTO_GITHUB_APP_ID=12345' 'POKETTO_GITHUB_CLIENT_ID=Iv1.synthetic' \
        'POKETTO_GITHUB_CLIENT_SECRET=synthetic-client-secret' 'POKETTO_GITHUB_PRIVATE_KEY=c3ludGhldGlj' \
        'POKETTO_GITHUB_WEBHOOK_SECRET=synthetic-webhook-$literal-${NO_EXPANSION}'
}

setup_root
have_image "$DIGEST_IMAGE"
printf '%s\n' 'POKETTO_REPOSITORY_CREDENTIAL_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=' >> "$ROOT/.env"
set +e
OUT="$(github_settings | POKETTO_CAPTURE_ENV=1 bash "$ROOT/deploy.sh" --set-stdin 2> "$PWD/stderr")"
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 0
while IFS='=' read -r key value; do
    [ "$(cat "$FAKE_STATE/$key")" = "$value" ]
    grep -qFx "$key=$value" "$ROOT/.env"
done < <(github_settings)
assert_not_contains "$(docker_log)$OUT$ERR" 'synthetic-webhook-'
assert_not_contains "$(docker_log)$OUT$ERR" 'synthetic-client-secret'
assert_not_contains "$(docker_log)$OUT$ERR" 'c3ludGhldGlj'

# Omitted fields survive an image-only update; clearing both signing credentials keeps revocations.
POKETTO_CAPTURE_ENV=1 run_deploy
assert_status 0
[ "$(cat "$FAKE_STATE/POKETTO_GITHUB_PRIVATE_KEY")" = c3ludGhldGlj ]
printf '%s\n' 'POKETTO_GITHUB_CLIENT_SECRET=' 'POKETTO_GITHUB_PRIVATE_KEY=' > settings
set +e
OUT="$(POKETTO_CAPTURE_ENV=1 bash "$ROOT/deploy.sh" --set-stdin < settings 2> "$PWD/stderr")"
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 0
[ ! -s "$FAKE_STATE/POKETTO_GITHUB_PRIVATE_KEY" ]
[ -s "$FAKE_STATE/POKETTO_GITHUB_WEBHOOK_SECRET" ]
github_settings | cut -d= -f1 | sed 's/$/=/' > settings
set +e
OUT="$(POKETTO_CAPTURE_ENV=1 bash "$ROOT/deploy.sh" --set-stdin < settings 2> "$PWD/stderr")"
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 0
while IFS='=' read -r key value; do
    [ ! -s "$FAKE_STATE/$key" ]
    grep -qFx "$key=" "$ROOT/.env"
done < settings

# Every incomplete group fails before the service replacement boundary.
for missing in APP_ID CLIENT_ID CLIENT_SECRET PRIVATE_KEY WEBHOOK_SECRET REPOSITORY_CREDENTIAL_KEY; do
    setup_root
    have_image "$DIGEST_IMAGE"
    github_settings > settings
    printf '%s\n' 'POKETTO_REPOSITORY_CREDENTIAL_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=' >> settings
    key="POKETTO_GITHUB_$missing"
    [ "$missing" != REPOSITORY_CREDENTIAL_KEY ] || key="POKETTO_$missing"
    sed "/^$key=/d" settings >> "$ROOT/.env"
    run_deploy
    assert_status 1
    [ "$(up_count)" = 0 ]
done
for invalid in 'APP_ID=0' 'CLIENT_ID=invalid client' 'WEBHOOK_SECRET=short' 'PRIVATE_KEY=-----BEGIN PRIVATE KEY-----'; do
    setup_root
    github_settings | grep -v "^POKETTO_GITHUB_${invalid%%=*}=" >> "$ROOT/.env"
    printf 'POKETTO_GITHUB_%s\n' "$invalid" >> "$ROOT/.env"
    printf '%s\n' 'POKETTO_REPOSITORY_CREDENTIAL_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=' >> "$ROOT/.env"
    run_deploy
    assert_status 1
    [ "$(up_count)" = 0 ]
done
