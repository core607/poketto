#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"
mkdir -p "$FAKE_STATE"
have_image "$DIGEST_IMAGE"
have_image "$FRONTEND_IMAGE"

# Missing arguments and a short revision are refused before anything is touched.
run_transfer --target ops@host --root /srv/poketto
assert_status 1
assert_contains "$ERR" "are required"
run_transfer --target ops@host --root /srv/poketto --image "$DIGEST_IMAGE" --frontend-image "$FRONTEND_IMAGE" --revision abc
assert_status 1
assert_contains "$ERR" "full lowercase commit id"
[ ! -f "$FAKE_STATE/ssh.log" ]

# Happy path: sync files, save the per-commit tag, load it remotely, then run the entrance.
run_transfer --target ops@host --root /srv/poketto --image "$DIGEST_IMAGE" --frontend-image "$FRONTEND_IMAGE" --revision "$REVISION" --sync
assert_status 0
assert_contains "$(docker_log)" "tag $DIGEST_IMAGE $TAG_IMAGE"
assert_contains "$(docker_log)" "save $TAG_IMAGE $FRONTEND_TAG"
assert_contains "$OUT" "sending $TAG_IMAGE"
assert_contains "$(cat "$FAKE_STATE/sync.log")" "cat > '/srv/poketto/.incoming/$REVISION/compose.yaml.tmp'"
assert_contains "$(cat "$FAKE_STATE/sync.log")" "cat > '/srv/poketto/.incoming/$REVISION/deploy.sh.tmp'"
assert_contains "$(cat "$FAKE_STATE/sync.log")" "sha256sum --check"
assert_not_contains "$(cat "$FAKE_STATE/sync.log")" "cat > '/srv/poketto/deploy.sh'"
# No entrance exists remotely yet, so the staged copies are installed directly.
assert_contains "$(cat "$FAKE_STATE/sync.log")" "mv -f '/srv/poketto/.incoming/$REVISION/deploy.sh' '/srv/poketto/deploy.sh'"
[ -s "$FAKE_STATE/received.archive" ] || { echo "no archive reached the remote"; exit 1; }
assert_contains "$(cat "$FAKE_STATE/deploy-calls")" "'/srv/poketto/deploy.sh'"
assert_contains "$(cat "$FAKE_STATE/deploy-calls")" "--app-image '$TAG_IMAGE' --app-revision '$REVISION'"
assert_not_contains "$(cat "$FAKE_STATE/deploy-calls")" "--sync-from"

# With an entrance already installed, the staged files are handed to it for the locked swap.
rm -f "$FAKE_STATE/sync.log" "$FAKE_STATE/deploy-calls"
touch "$FAKE_STATE/remote-has-entrance" "$FAKE_STATE/remote-has-image"
run_transfer --target ops@host --root /srv/poketto --image "$DIGEST_IMAGE" --frontend-image "$FRONTEND_IMAGE" --revision "$REVISION" --sync
assert_status 0
assert_not_contains "$(cat "$FAKE_STATE/sync.log")" "mv -f '/srv/poketto/.incoming/$REVISION/deploy.sh' '/srv/poketto/deploy.sh'"
assert_contains "$(cat "$FAKE_STATE/deploy-calls")" "--sync-from '/srv/poketto/.incoming/$REVISION'"
assert_contains "$(ssh_log)" "sha256sum --check"

# A transfer of another commit stages below its own directory, so two transfers running at the
# same time never overwrite each other's staged files.
second_revision="89abcdef0123456789abcdef0123456789abcdef"
second_image="ghcr.io/core607/poketto@sha256:3333333333333333333333333333333333333333333333333333333333333333"
have_image "$second_image" "$second_revision"
second_frontend="ghcr.io/core607/poketto-frontend:sha-$second_revision"
have_image "$second_frontend" "$second_revision"
rm -f "$FAKE_STATE/sync.log" "$FAKE_STATE/deploy-calls"
run_transfer --target ops@host --root /srv/poketto --image "$second_image" --frontend-image "$second_frontend" --revision "$second_revision" --sync
assert_status 0
assert_contains "$(cat "$FAKE_STATE/sync.log")" "cat > '/srv/poketto/.incoming/$second_revision/deploy.sh.tmp'"
assert_not_contains "$(cat "$FAKE_STATE/sync.log")" "/.incoming/$REVISION/"
assert_contains "$(cat "$FAKE_STATE/deploy-calls")" "--sync-from '/srv/poketto/.incoming/$second_revision'"
# An entrance that fails after staging (here: another deployment holds the lock) leaves no
# orphaned staging directory behind; a successful run issues no removal.
assert_not_contains "$(ssh_log)" "rm -rf"
rm -f "$FAKE_STATE/ssh.log" "$FAKE_STATE/deploy-calls"
FAKE_REMOTE_DEPLOY_EXIT=75 run_transfer --target ops@host --root /srv/poketto --image "$second_image" --frontend-image "$second_frontend" --revision "$second_revision" --sync
assert_status 75
assert_contains "$(ssh_log)" "rm -rf '/srv/poketto/.incoming/$second_revision'"
rm -f "$FAKE_STATE/remote-has-entrance" "$FAKE_STATE/remote-has-image"

# The remote already holds the tag: no archive is sent, the entrance still runs.
rm -f "$FAKE_STATE/docker.log" "$FAKE_STATE/ssh.log" "$FAKE_STATE/received.archive" "$FAKE_STATE/deploy-calls"
touch "$FAKE_STATE/remote-has-image"
run_transfer --target ops@host --root /srv/poketto --image "$DIGEST_IMAGE" --frontend-image "$FRONTEND_IMAGE" --revision "$REVISION"
assert_status 0
assert_contains "$OUT" "already loaded"
assert_not_contains "$(docker_log)" "save"
[ -f "$FAKE_STATE/deploy-calls" ]

# A failed remote load stops before the entrance is invoked.
rm -f "$FAKE_STATE/remote-has-image" "$FAKE_STATE/deploy-calls"
touch "$FAKE_STATE/remote-load-fails"
run_transfer --target ops@host --root /srv/poketto --image "$DIGEST_IMAGE" --frontend-image "$FRONTEND_IMAGE" --revision "$REVISION"
assert_status 1
assert_contains "$ERR" "transfer or load failed"
[ ! -f "$FAKE_STATE/deploy-calls" ]

# A local image with the wrong revision never leaves the machine.
rm -f "$FAKE_STATE/remote-load-fails"
other="ghcr.io/core607/poketto@sha256:4444444444444444444444444444444444444444444444444444444444444444"
have_image "$other" "ffffffffffffffffffffffffffffffffffffffff"
run_transfer --target ops@host --root /srv/poketto --image "$other" --frontend-image "$FRONTEND_IMAGE" --revision "$REVISION"
assert_status 1
assert_contains "$ERR" "expected $REVISION"

# Settings given on standard input reach the entrance through its own standard input, never
# through the command line.
rm -f "$FAKE_STATE/deploy-calls" "$FAKE_STATE/deploy-stdin" "$FAKE_STATE/ssh.log"
touch "$FAKE_STATE/remote-has-image"
set +e
OUT="$(printf 'POKETTO_REPOSITORY_PASSWORD=secret-value\n' | bash "$DEPLOY_DIR/transfer.sh" --target ops@host --root /srv/poketto --image "$DIGEST_IMAGE" --frontend-image "$FRONTEND_IMAGE" --revision "$REVISION" --set-stdin 2> "$PWD/stderr")"
STATUS=$?
set -e
ERR="$(cat "$PWD/stderr")"
assert_status 0
assert_contains "$(cat "$FAKE_STATE/deploy-calls")" "--set-stdin"
assert_contains "$(cat "$FAKE_STATE/deploy-stdin")" "POKETTO_REPOSITORY_PASSWORD=secret-value"
assert_not_contains "$(ssh_log)" "secret-value"


# Registry delivery syncs the same current stack without a local Docker pull or archive.
rm -f "$FAKE_STATE/docker.log" "$FAKE_STATE/ssh.log" "$FAKE_STATE/deploy-calls"
run_transfer --target ops@host --root /srv/poketto --image "$DIGEST_IMAGE" --frontend-image "$FRONTEND_IMAGE" --revision "$REVISION" --sync --pull
assert_status 0
assert_not_contains "$(docker_log)" "save"
assert_not_contains "$(docker_log)" "pull"
assert_contains "$(cat "$FAKE_STATE/deploy-calls")" "--frontend-image '$FRONTEND_IMAGE'"
assert_contains "$(cat "$FAKE_STATE/sync.log")" "Caddyfile.tmp"
assert_contains "$(cat "$FAKE_STATE/sync.log")" "compose.executor.yaml.tmp"
run_transfer --target ops@host --root /srv/poketto --image "$TAG_IMAGE" --frontend-image "$FRONTEND_IMAGE" --revision "$REVISION" --pull
assert_status 1
assert_contains "$ERR" "requires immutable registry digests"

# A mismatched frontend is rejected before any remote command, even with a valid application.
rm -f "$FAKE_STATE/ssh.log"
wrong_frontend="ghcr.io/core607/poketto-frontend:wrong"
have_image "$wrong_frontend" "ffffffffffffffffffffffffffffffffffffffff"
run_transfer --target ops@host --root /srv/poketto --image "$DIGEST_IMAGE" --frontend-image "$wrong_frontend" --revision "$REVISION"
assert_status 1
[ ! -f "$FAKE_STATE/ssh.log" ]


# Values interpolated into remote shell commands reject quote/line-break injection.
run_transfer --target ops@host --root "/srv/poketto'bad" --image "$DIGEST_IMAGE" --frontend-image "$FRONTEND_IMAGE" --revision "$REVISION" --pull
assert_status 1
assert_contains "$ERR" "quotes or line breaks"
