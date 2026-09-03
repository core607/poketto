#!/usr/bin/env bash
# Poketto deployment entrance. Runs on the production host inside the deployment root, which
# holds compose.yaml and .env. It replaces containers only after validating configuration,
# images, directories, and disk space, and it succeeds only when the real health entrance passes.
# It never removes, recreates, or rolls back a data volume, and it never guesses an older image.
#
# Usage: deploy.sh [--root DIR] [--app-image REF --app-revision COMMIT] [--db-image REF] [--set-stdin]
#   Options record their values into .env before deploying, so a rerun without options
#   redeploys the recorded pins. --set-stdin reads KEY=VALUE lines from standard input and
#   records them the same way, which keeps a secret out of the command line and process list.
# Exit codes: 0 deployed and healthy, 1 validation or deployment failure, 75 another
#   deployment holds the lock.

set -euo pipefail

DOCKER="${POKETTO_DOCKER:-docker}"
CURL="${POKETTO_CURL:-curl}"
ROOT="${POKETTO_DEPLOY_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
APP_IMAGE_ARG=""
APP_REVISION_ARG=""
DB_IMAGE_ARG=""
SET_STDIN=0

usage() {
    sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

fail() {
    echo "deploy: $*" >&2
    exit 1
}

while [ $# -gt 0 ]; do
    case "$1" in
        --root) ROOT="$2"; shift 2 ;;
        --app-image) APP_IMAGE_ARG="$2"; shift 2 ;;
        --app-revision) APP_REVISION_ARG="$2"; shift 2 ;;
        --db-image) DB_IMAGE_ARG="$2"; shift 2 ;;
        --set-stdin) SET_STDIN=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

if { [ -n "$APP_IMAGE_ARG" ] && [ -z "$APP_REVISION_ARG" ]; } || { [ -z "$APP_IMAGE_ARG" ] && [ -n "$APP_REVISION_ARG" ]; }; then
    fail "--app-image and --app-revision must be given together"
fi

ENV_FILE="$ROOT/.env"
COMPOSE_FILE="$ROOT/compose.yaml"
LOCK_DIR="$ROOT/.deploy.lock"

compose() {
    "$DOCKER" compose --project-directory "$ROOT" -f "$COMPOSE_FILE" "$@"
}

# ---- lock ------------------------------------------------------------------------------------

acquire_lock() {
    local attempt holder
    for attempt in 1 2; do
        if mkdir "$LOCK_DIR" 2>/dev/null; then
            echo "$$" > "$LOCK_DIR/pid"
            trap 'rm -rf "$LOCK_DIR"' EXIT
            return 0
        fi
        holder="$(cat "$LOCK_DIR/pid" 2>/dev/null || true)"
        if [ -n "$holder" ] && kill -0 "$holder" 2>/dev/null; then
            echo "deploy: another deployment (pid $holder) holds $LOCK_DIR" >&2
            exit 75
        fi
        # The recorded process is gone, so the lock is residue of an interrupted run.
        rm -rf "$LOCK_DIR"
    done
    fail "cannot acquire $LOCK_DIR"
}

# ---- configuration ---------------------------------------------------------------------------

set_env_value() {
    local key="$1" value="$2" tmp
    tmp="$(mktemp "$ROOT/.env.XXXXXX")"
    if grep -q "^${key}=" "$ENV_FILE"; then
        awk -v k="$key" -v v="$value" 'index($0, k "=") == 1 { print k "=" v; next } { print }' "$ENV_FILE" > "$tmp"
    else
        cat "$ENV_FILE" > "$tmp"
        printf '%s=%s\n' "$key" "$value" >> "$tmp"
    fi
    chmod 600 "$tmp"
    mv "$tmp" "$ENV_FILE"
}

load_configuration() {
    local missing=() key
    [ -f "$COMPOSE_FILE" ] || fail "missing $COMPOSE_FILE"
    [ -f "$ENV_FILE" ] || fail "missing $ENV_FILE; copy .env.example beside compose.yaml and fill it in"

    [ -n "$APP_IMAGE_ARG" ] && set_env_value POKETTO_APP_IMAGE "$APP_IMAGE_ARG"
    [ -n "$APP_REVISION_ARG" ] && set_env_value POKETTO_APP_REVISION "$APP_REVISION_ARG"
    [ -n "$DB_IMAGE_ARG" ] && set_env_value POKETTO_DB_IMAGE "$DB_IMAGE_ARG"
    if [ "$SET_STDIN" = 1 ]; then
        local line key value
        while IFS= read -r line || [ -n "$line" ]; do
            [ -n "$line" ] || continue
            key="${line%%=*}"
            value="${line#*=}"
            [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] && [ "$key" != "$line" ] \
                || fail "--set-stdin expects KEY=VALUE lines with an uppercase key"
            set_env_value "$key" "$value"
        done
    fi

    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a

    for key in POKETTO_APP_IMAGE POKETTO_APP_REVISION POKETTO_DB_IMAGE POSTGRES_DB POSTGRES_USER \
            POSTGRES_PASSWORD POKETTO_REPOSITORY_REMOTE_URI POKETTO_REPOSITORY_USERNAME \
            POKETTO_REPOSITORY_PASSWORD POKETTO_DATA_DIR_HOST POKETTO_DB_DIR_HOST; do
        [ -n "${!key:-}" ] || missing+=("$key")
    done
    [ ${#missing[@]} -eq 0 ] || fail "missing values in $ENV_FILE: ${missing[*]}"

    [[ "$POKETTO_APP_REVISION" =~ ^[0-9a-f]{40}$ ]] \
        || fail "POKETTO_APP_REVISION must be a full lowercase commit id"
    [[ "$POKETTO_APP_IMAGE" =~ ^[^[:space:]]+$ ]] \
        || fail "POKETTO_APP_IMAGE must be one image reference"
    [[ "$POKETTO_DB_IMAGE" =~ ^[^[:space:]@]+@sha256:[0-9a-f]{64}$ ]] \
        || fail "POKETTO_DB_IMAGE must be pinned as <image>@sha256:<digest>"
    [[ "$POKETTO_DATA_DIR_HOST" = /* ]] || fail "POKETTO_DATA_DIR_HOST must be absolute"
    [[ "$POKETTO_DB_DIR_HOST" = /* ]] || fail "POKETTO_DB_DIR_HOST must be absolute"

    HTTP_BIND="${POKETTO_HTTP_BIND:-127.0.0.1}"
    HTTP_PORT="${POKETTO_HTTP_PORT:-8080}"
    HEALTH_TIMEOUT="${POKETTO_HEALTH_TIMEOUT:-180}"
    HEALTH_INTERVAL="${POKETTO_HEALTH_INTERVAL:-5}"
    MIN_FREE_MB="${POKETTO_MIN_FREE_MB:-2048}"
    APP_UID="${POKETTO_APP_UID:-10001}"
}

# ---- host checks -----------------------------------------------------------------------------

owner_uid() {
    stat -c %u "$1" 2>/dev/null || stat -f %u "$1"
}

check_host() {
    "$DOCKER" info >/dev/null 2>&1 || fail "docker daemon is not reachable by $(id -un)"
    compose version >/dev/null 2>&1 || fail "docker compose plugin is not installed"

    mkdir -p "$POKETTO_DATA_DIR_HOST" "$POKETTO_DB_DIR_HOST"
    if [ "$(owner_uid "$POKETTO_DATA_DIR_HOST")" != "$APP_UID" ]; then
        if [ "$(id -u)" = 0 ]; then
            chown "$APP_UID:$APP_UID" "$POKETTO_DATA_DIR_HOST"
        else
            fail "$POKETTO_DATA_DIR_HOST must be owned by uid $APP_UID; run: sudo chown $APP_UID:$APP_UID $POKETTO_DATA_DIR_HOST"
        fi
    fi

    local free_mb
    free_mb="$(df -Pm "$ROOT" | awk 'NR == 2 { print $4 }')"
    [ "${free_mb:-0}" -ge "$MIN_FREE_MB" ] \
        || fail "only ${free_mb:-0} MB free below $ROOT; at least $MIN_FREE_MB MB is required"

    compose config --quiet || fail "compose configuration is invalid"
}

# ---- images ----------------------------------------------------------------------------------

image_present() {
    "$DOCKER" image inspect "$1" >/dev/null 2>&1
}

image_revision() {
    "$DOCKER" image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$1"
}

acquire_images() {
    if ! image_present "$POKETTO_DB_IMAGE"; then
        "$DOCKER" pull "$POKETTO_DB_IMAGE" >/dev/null || fail "cannot pull $POKETTO_DB_IMAGE"
    fi

    if [[ "$POKETTO_APP_IMAGE" == *@sha256:* ]]; then
        if ! image_present "$POKETTO_APP_IMAGE"; then
            "$DOCKER" pull "$POKETTO_APP_IMAGE" >/dev/null || fail "cannot pull $POKETTO_APP_IMAGE"
        fi
    else
        # A tag is movable, so it is accepted only when the archive that carries it was already
        # loaded on this host; the revision label below binds it to the requested commit.
        image_present "$POKETTO_APP_IMAGE" \
            || fail "$POKETTO_APP_IMAGE is not loaded on this host; pin a digest or transfer the archive first"
    fi

    local revision
    revision="$(image_revision "$POKETTO_APP_IMAGE")"
    [ "$revision" = "$POKETTO_APP_REVISION" ] \
        || fail "$POKETTO_APP_IMAGE carries revision '${revision:-none}', expected $POKETTO_APP_REVISION"
}

# ---- deployment ------------------------------------------------------------------------------

deploy() {
    compose up --detach --remove-orphans --no-build --pull never
}

wait_for_health() {
    local deadline container status
    deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
    container="$(compose ps --quiet app)"
    [ -n "$container" ] || fail "the app container was not created"

    while :; do
        status="$("$DOCKER" inspect --format '{{ .State.Health.Status }}' "$container" 2>/dev/null || echo unknown)"
        case "$status" in
            healthy) break ;;
            unhealthy) report_failure "the app container reported unhealthy" ;;
        esac
        if [ "$(date +%s)" -ge "$deadline" ]; then
            report_failure "the app container did not become healthy within ${HEALTH_TIMEOUT}s (last status: $status)"
        fi
        sleep "$HEALTH_INTERVAL"
    done

    local host="$HTTP_BIND" body
    [ "$host" = 0.0.0.0 ] && host=127.0.0.1
    body="$("$CURL" -fsS --max-time 10 "http://$host:$HTTP_PORT/actuator/health" 2>/dev/null)" \
        || report_failure "the health entrance at $host:$HTTP_PORT did not answer"
    [[ "$body" == *'"UP"'* ]] || report_failure "the health entrance answered without UP: $body"
}

report_failure() {
    {
        echo "deploy: $1"
        echo "deploy: containers:"
        compose ps 2>/dev/null || true
        echo "deploy: recent app log:"
        compose logs --no-color --tail 100 app 2>/dev/null || true
    } >&2
    exit 1
}

main() {
    acquire_lock
    load_configuration
    check_host
    acquire_images
    deploy
    wait_for_health
    echo "deploy: healthy; app=$POKETTO_APP_IMAGE revision=$POKETTO_APP_REVISION db=$POKETTO_DB_IMAGE"
}

main
