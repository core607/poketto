#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"
mkdir -p "$FAKE_STATE"
have_image "$DIGEST_IMAGE"

# Missing arguments and a short revision are refused before anything is touched.
run_transfer --target ops@host --root /srv/poketto
assert_status 1
assert_contains "$ERR" "are required"
run_transfer --target ops@host --root /srv/poketto --image "$DIGEST_IMAGE" --revision abc
assert_status 1
assert_contains "$ERR" "full lowercase commit id"
[ ! -f "$FAKE_STATE/ssh.log" ]

# Happy path: sync files, save the per-commit tag, load it remotely, then run the entrance.
run_transfer --target ops@host --root /srv/poketto --image "$DIGEST_IMAGE" --revision "$REVISION" --sync
assert_status 0
assert_contains "$(docker_log)" "tag $DIGEST_IMAGE $TAG_IMAGE"
assert_contains "$(docker_log)" "save $TAG_IMAGE"
assert_contains "$OUT" "sending $TAG_IMAGE"
assert_contains "$(cat "$FAKE_STATE/sync.log")" "/srv/poketto/compose.yaml"
assert_contains "$(cat "$FAKE_STATE/sync.log")" "/srv/poketto/deploy.sh"
[ -s "$FAKE_STATE/received.archive" ] || { echo "no archive reached the remote"; exit 1; }
assert_contains "$(cat "$FAKE_STATE/deploy-calls")" "/srv/poketto/deploy.sh' --app-image '$TAG_IMAGE' --app-revision '$REVISION'"
assert_contains "$(ssh_log)" "sha256sum --check"

# The remote already holds the tag: no archive is sent, the entrance still runs.
rm -f "$FAKE_STATE/docker.log" "$FAKE_STATE/ssh.log" "$FAKE_STATE/received.archive" "$FAKE_STATE/deploy-calls"
touch "$FAKE_STATE/remote-has-image"
run_transfer --target ops@host --root /srv/poketto --image "$DIGEST_IMAGE" --revision "$REVISION"
assert_status 0
assert_contains "$OUT" "already loaded"
assert_not_contains "$(docker_log)" "save"
[ -f "$FAKE_STATE/deploy-calls" ]

# A failed remote load stops before the entrance is invoked.
rm -f "$FAKE_STATE/remote-has-image" "$FAKE_STATE/deploy-calls"
touch "$FAKE_STATE/remote-load-fails"
run_transfer --target ops@host --root /srv/poketto --image "$DIGEST_IMAGE" --revision "$REVISION"
assert_status 1
assert_contains "$ERR" "transfer or load failed"
[ ! -f "$FAKE_STATE/deploy-calls" ]

# A local image with the wrong revision never leaves the machine.
rm -f "$FAKE_STATE/remote-load-fails"
other="ghcr.io/core607/poketto@sha256:4444444444444444444444444444444444444444444444444444444444444444"
have_image "$other" "ffffffffffffffffffffffffffffffffffffffff"
run_transfer --target ops@host --root /srv/poketto --image "$other" --revision "$REVISION"
assert_status 1
assert_contains "$ERR" "expected $REVISION"

# Settings given on standard input reach the entrance through its own standard input, never
# through the command line.
rm -f "$FAKE_STATE/deploy-calls" "$FAKE_STATE/deploy-stdin" "$FAKE_STATE/ssh.log"
touch "$FAKE_STATE/remote-has-image"
set +e
OUT="$(printf 'POKETTO_REPOSITORY_PASSWORD=secret-value\n' | bash "$DEPLOY_DIR/transfer.sh" --target ops@host --root /srv/poketto --image "$DIGEST_IMAGE" --revision "$REVISION" --set-stdin 2> "$PWD/stderr")"
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 0
assert_contains "$(cat "$FAKE_STATE/deploy-calls")" "--set-stdin"
assert_contains "$(cat "$FAKE_STATE/deploy-stdin")" "POKETTO_REPOSITORY_PASSWORD=secret-value"
assert_not_contains "$(ssh_log)" "secret-value"
