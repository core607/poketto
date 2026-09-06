# Shared helpers for the deployment script tests. Sourced by each test_*.sh, which runs inside a
# fresh temporary directory with the fake commands first on PATH and $FAKE_STATE set.
set -euo pipefail

ROOT="$PWD/root"
REVISION="0123456789abcdef0123456789abcdef01234567"
DIGEST_IMAGE="ghcr.io/core607/poketto@sha256:1111111111111111111111111111111111111111111111111111111111111111"
TAG_IMAGE="ghcr.io/core607/poketto:sha-$REVISION"
FRONTEND_IMAGE="ghcr.io/core607/poketto-frontend@sha256:2222222222222222222222222222222222222222222222222222222222222222"
FRONTEND_TAG="ghcr.io/core607/poketto-frontend:sha-$REVISION"
GATEWAY_IMAGE="$(sed -n 's/^POKETTO_GATEWAY_IMAGE=//p' "$DEPLOY_DIR/.env.example" | tr -d '\r')"
# Exercise the database reference operators receive, including its full tag and digest.
DB_IMAGE="$(sed -n 's/^POKETTO_DB_IMAGE=//p' "$DEPLOY_DIR/.env.example" | tr -d '\r')"
[ -n "$DB_IMAGE" ] || { echo "deployment example has no database image"; exit 1; }

export POKETTO_DOCKER=docker POKETTO_CURL=curl POKETTO_SSH=ssh
export POKETTO_HEALTH_INTERVAL=0 POKETTO_MIN_FREE_MB=1 POKETTO_APP_UID="$(id -u)" POKETTO_GATEWAY_UID="$(id -u)"
unset DOCKER_CONFIG POKETTO_DEPLOY_LOCK_FD POKETTO_DEPLOY_LOCK_HELD

# Starts one scenario from a clean root and clean fake state.
setup_root() {
    rm -rf "$ROOT" "$FAKE_STATE"
    mkdir -p "$ROOT" "$FAKE_STATE"
    cp "$DEPLOY_DIR/compose.yaml" "$DEPLOY_DIR/compose.executor.yaml" "$DEPLOY_DIR/Caddyfile" "$DEPLOY_DIR/deploy.sh" "$ROOT/"
    cat > "$ROOT/.env" <<EOF
POKETTO_APP_IMAGE=$DIGEST_IMAGE
POKETTO_APP_REVISION=$REVISION
POKETTO_DB_IMAGE=$DB_IMAGE
POKETTO_FRONTEND_IMAGE=$FRONTEND_IMAGE
POKETTO_GATEWAY_IMAGE=$GATEWAY_IMAGE
POKETTO_PUBLIC_DOMAIN=site.example.invalid
POKETTO_AUTH_INITIALIZATION_TOKEN=isolated-fixture-initialization
POKETTO_NETWORK_SUBNET=172.29.230.0/24
POKETTO_NETWORK_DYNAMIC_RANGE=172.29.230.128/25
POKETTO_GATEWAY_INTERNAL_IP=172.29.230.10
POKETTO_GATEWAY_DIR_HOST=$ROOT/gateway
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
    echo "$GATEWAY_IMAGE" >> "$FAKE_STATE/images"
    have_image "$FRONTEND_IMAGE"
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

hold_deploy_lock() {
    if command -v flock >/dev/null 2>&1; then
        exec {TEST_LOCK_FD}<>"$ROOT/.deploy.lock"
        flock -n "$TEST_LOCK_FD"
        TEST_LOCK_HOLDER="$BASHPID"
        printf '%s\n' "$TEST_LOCK_HOLDER" > "$ROOT/.deploy.lock"
        TEST_LOCK_KIND=flock
    else
        sleep 30 &
        TEST_LOCK_HOLDER=$!
        mkdir "$ROOT/.deploy.lock.d"
        printf '%s\n' "$TEST_LOCK_HOLDER" > "$ROOT/.deploy.lock.d/pid"
        TEST_LOCK_KIND=directory
    fi
}

release_deploy_lock() {
    if [ "$TEST_LOCK_KIND" = flock ]; then
        flock -u "$TEST_LOCK_FD"
        exec {TEST_LOCK_FD}>&-
    else
        kill "$TEST_LOCK_HOLDER" 2>/dev/null || true
        rm -rf "$ROOT/.deploy.lock.d"
    fi
}
