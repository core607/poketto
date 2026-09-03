# Shared helpers for the deployment script tests. Sourced by each test_*.sh, which runs inside a
# fresh temporary directory with the fake commands first on PATH and $FAKE_STATE set.
set -euo pipefail

ROOT="$PWD/root"
REVISION="0123456789abcdef0123456789abcdef01234567"
DIGEST_IMAGE="ghcr.io/core607/poketto@sha256:1111111111111111111111111111111111111111111111111111111111111111"
TAG_IMAGE="ghcr.io/core607/poketto:sha-$REVISION"
DB_IMAGE="postgres:17@sha256:2222222222222222222222222222222222222222222222222222222222222222"

export POKETTO_DOCKER=docker POKETTO_CURL=curl POKETTO_SSH=ssh
export POKETTO_HEALTH_INTERVAL=0 POKETTO_MIN_FREE_MB=1 POKETTO_APP_UID="$(id -u)"

# Starts one scenario from a clean root and clean fake state.
setup_root() {
    rm -rf "$ROOT" "$FAKE_STATE"
    mkdir -p "$ROOT" "$FAKE_STATE"
    cp "$DEPLOY_DIR/compose.yaml" "$DEPLOY_DIR/deploy.sh" "$ROOT/"
    cat > "$ROOT/.env" <<EOF
POKETTO_APP_IMAGE=$DIGEST_IMAGE
POKETTO_APP_REVISION=$REVISION
POKETTO_DB_IMAGE=$DB_IMAGE
POSTGRES_DB=poketto
POSTGRES_USER=poketto
POSTGRES_PASSWORD=secret
POKETTO_REPOSITORY_REMOTE_URI=https://git.example.invalid/owner/content.git
POKETTO_REPOSITORY_USERNAME=operator
POKETTO_REPOSITORY_PASSWORD=token
POKETTO_DATA_DIR_HOST=$ROOT/data
POKETTO_DB_DIR_HOST=$ROOT/db
EOF
    echo "$DB_IMAGE" >> "$FAKE_STATE/images"
}

have_image() {
    echo "$1" >> "$FAKE_STATE/images"
    echo "$1 ${2:-$REVISION}" >> "$FAKE_STATE/revisions"
}

run_deploy() {
    set +e
    OUT="$(bash "$ROOT/deploy.sh" "$@" 2> "$PWD/stderr")"
    STATUS=$?
    set -e
    ERR="$(cat "$PWD/stderr")"
}

run_transfer() {
    set +e
    OUT="$(bash "$DEPLOY_DIR/transfer.sh" "$@" 2> "$PWD/stderr")"
    STATUS=$?
    set -e
    ERR="$(cat "$PWD/stderr")"
}

assert_status() {
    [ "$STATUS" = "$1" ] || { echo "expected exit $1, got $STATUS"; echo "stdout: $OUT"; echo "stderr: $ERR"; exit 1; }
}

assert_contains() {
    case "$1" in *"$2"*) ;; *) echo "expected to find: $2"; echo "in: $1"; exit 1 ;; esac
}

assert_not_contains() {
    case "$1" in *"$2"*) echo "did not expect: $2"; echo "in: $1"; exit 1 ;; esac
}

docker_log() { cat "$FAKE_STATE/docker.log" 2>/dev/null || true; }
ssh_log() { cat "$FAKE_STATE/ssh.log" 2>/dev/null || true; }
up_count() { wc -l < "$FAKE_STATE/up.log" 2>/dev/null | tr -d ' ' || echo 0; }
