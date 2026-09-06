#!/usr/bin/env bash
# Poketto deployment entrance. Runs on the production host inside the deployment root, which
# holds compose.yaml and .env. It replaces containers only after validating configuration,
# images, directories, and disk space, and it succeeds only when the real health entrance passes.
# It never removes, recreates, or rolls back a data volume, and it never guesses an older image.
#
# Usage: deploy.sh [--root DIR] [--app-image REF --app-revision COMMIT --frontend-image REF]
#                  [--db-image REF] [--gateway-image REF] [--set-stdin] [--sync-from DIR]
# Candidate pins and literal KEY=VALUE stdin settings reach .env only after all services and
# local HTTPS checks succeed. Confirmed application/frontend, database and gateway pins are
# retained in .env.previous when replaced. Registry credentials supplied on stdin are used
# only in a temporary Docker configuration and are never recorded.
# --sync-from installs staged Compose files, Caddyfile and this script under the deployment
# lock. A changed script re-executes before reading stdin, retaining the lock and arguments.
# Exit codes: 0 deployed and healthy, 1 validation or deployment failure, 75 another
#   deployment holds the lock.

set -euo pipefail

DOCKER="${POKETTO_DOCKER:-docker}"
CURL="${POKETTO_CURL:-curl}"
ROOT="${POKETTO_DEPLOY_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
ORIGINAL_ARGS=("$@")
APP_IMAGE_ARG=""
APP_REVISION_ARG=""
DB_IMAGE_ARG=""
FRONTEND_IMAGE_ARG=""
GATEWAY_IMAGE_ARG=""
SET_STDIN=0
SYNC_FROM=""
REGISTRY_USERNAME=""
REGISTRY_PASSWORD=""
DOCKER_CONFIG_DIR=""
REGISTRY_CONTEXT=""
LOCK_KIND=""
LOCK_FD=""
PENDING_KEYS=()
PENDING_VALUES=()

CONFIG_KEYS=(
    POKETTO_APP_IMAGE POKETTO_APP_REVISION POKETTO_DB_IMAGE POKETTO_FRONTEND_IMAGE POKETTO_GATEWAY_IMAGE
    POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD
    POKETTO_REPOSITORY_REMOTE_URI POKETTO_REPOSITORY_USERNAME POKETTO_REPOSITORY_PASSWORD
    POKETTO_DATA_DIR_HOST POKETTO_DB_DIR_HOST
    POKETTO_HTTP_PORT POKETTO_APP_MEMORY POKETTO_DB_MEMORY
    POKETTO_PUBLIC_DOMAIN POKETTO_AUTH_INITIALIZATION_TOKEN POKETTO_GATEWAY_DIR_HOST POKETTO_GATEWAY_UID
    POKETTO_NETWORK_SUBNET POKETTO_NETWORK_DYNAMIC_RANGE POKETTO_GATEWAY_INTERNAL_IP
    POKETTO_FRONTEND_MEMORY POKETTO_GATEWAY_MEMORY POKETTO_APP_CPUS POKETTO_DB_CPUS POKETTO_FRONTEND_CPUS POKETTO_GATEWAY_CPUS
    POKETTO_ASSETS_CACHE_MAX_BYTES POKETTO_ASSETS_MAX_GRANTS
    POKETTO_EXECUTOR_ENABLED POKETTO_EXECUTOR_RUNTIME_DIR_HOST POKETTO_EXECUTOR_STAGING_DIR_HOST POKETTO_EXECUTOR_SIGNING_KEY_HOST
    POKETTO_EXECUTOR_MAX_SESSIONS POKETTO_EXECUTOR_OPEN_TIMEOUT_SECONDS POKETTO_EXECUTOR_CLOSE_TIMEOUT_SECONDS
    POKETTO_EXECUTOR_MAX_BUNDLE_BYTES POKETTO_EXECUTOR_EXPORT_TIMEOUT_SECONDS
    POKETTO_HEALTH_TIMEOUT POKETTO_MIN_FREE_MB POKETTO_APP_UID
)
PIN_KEYS=(POKETTO_APP_IMAGE POKETTO_APP_REVISION POKETTO_DB_IMAGE POKETTO_FRONTEND_IMAGE POKETTO_GATEWAY_IMAGE)

usage() {
    sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

fail() {
    echo "deploy: $*" >&2
    exit 1
}

is_config_key() {
    local candidate="$1" key
    for key in "${CONFIG_KEYS[@]}"; do
        [ "$candidate" != "$key" ] || return 0
    done
    return 1
}

# A candidate value is held in memory for this run and reaches .env only after the deployment
# is healthy.
stage_value() {
    local key="$1" value="$2" i
    is_config_key "$key" || fail "unsupported configuration key: $key"
    [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] \
        || fail "$key must be a single-line value"
    for i in "${!PENDING_KEYS[@]}"; do
        if [ "${PENDING_KEYS[$i]}" = "$key" ]; then
            PENDING_VALUES[$i]="$value"
            return 0
        fi
    done
    PENDING_KEYS+=("$key")
    PENDING_VALUES+=("$value")
}

while [ $# -gt 0 ]; do
    case "$1" in
        --root) ROOT="$2"; shift 2 ;;
        --app-image) APP_IMAGE_ARG="$2"; shift 2 ;;
        --app-revision) APP_REVISION_ARG="$2"; shift 2 ;;
        --db-image) DB_IMAGE_ARG="$2"; shift 2 ;;
        --frontend-image) FRONTEND_IMAGE_ARG="$2"; shift 2 ;;
        --gateway-image) GATEWAY_IMAGE_ARG="$2"; shift 2 ;;
        --set-stdin) SET_STDIN=1; shift ;;
        --sync-from) SYNC_FROM="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

if { [ -n "$APP_IMAGE_ARG" ] && [ -z "$APP_REVISION_ARG" ]; } || { [ -z "$APP_IMAGE_ARG" ] && [ -n "$APP_REVISION_ARG" ]; }; then
    fail "--app-image and --app-revision must be given together"
fi
[ -z "$APP_IMAGE_ARG" ] || stage_value POKETTO_APP_IMAGE "$APP_IMAGE_ARG"
[ -z "$APP_REVISION_ARG" ] || stage_value POKETTO_APP_REVISION "$APP_REVISION_ARG"
[ -z "$DB_IMAGE_ARG" ] || stage_value POKETTO_DB_IMAGE "$DB_IMAGE_ARG"
[ -z "$FRONTEND_IMAGE_ARG" ] || stage_value POKETTO_FRONTEND_IMAGE "$FRONTEND_IMAGE_ARG"
[ -z "$GATEWAY_IMAGE_ARG" ] || stage_value POKETTO_GATEWAY_IMAGE "$GATEWAY_IMAGE_ARG"

ENV_FILE="$ROOT/.env"
PREVIOUS_ENV_FILE="$ROOT/.env.previous"
COMPOSE_FILE="$ROOT/compose.yaml"
LOCK_FILE="$ROOT/.deploy.lock"
LOCK_DIR="$ROOT/.deploy.lock.d"

compose() {
    local files=(-f "$COMPOSE_FILE")
    if [ "${POKETTO_EXECUTOR_ENABLED:-false}" = true ]; then files+=(-f "$ROOT/compose.executor.yaml"); fi
    "$DOCKER" compose --env-file /dev/null --project-directory "$ROOT" "${files[@]}" "$@"
}

cleanup() {
    [ -z "$DOCKER_CONFIG_DIR" ] || rm -rf "$DOCKER_CONFIG_DIR"
    [ "$LOCK_KIND" != directory ] || rm -rf "$LOCK_DIR"
}

# ---- lock ------------------------------------------------------------------------------------

# A process re-executed by apply_sync inherits the lock it already holds: the flock lives on the
# inherited descriptor named by POKETTO_DEPLOY_LOCK_FD, and the directory lock is marked by
# POKETTO_DEPLOY_LOCK_HELD. Acquiring again would deadlock against itself.
acquire_lock() {
    local holder
    if [ -n "${POKETTO_DEPLOY_LOCK_FD:-}" ]; then
        LOCK_FD="$POKETTO_DEPLOY_LOCK_FD"
        unset POKETTO_DEPLOY_LOCK_FD
        [[ "$LOCK_FD" =~ ^[0-9]+$ ]] && { true >&"$LOCK_FD"; } 2>/dev/null \
            || fail "POKETTO_DEPLOY_LOCK_FD does not name an open descriptor"
        printf '%s\n' "$$" > "$LOCK_FILE"
        LOCK_KIND=flock
        trap cleanup EXIT
        return 0
    fi
    if [ "${POKETTO_DEPLOY_LOCK_HELD:-}" = 1 ]; then
        unset POKETTO_DEPLOY_LOCK_HELD
        [ -d "$LOCK_DIR" ] || fail "POKETTO_DEPLOY_LOCK_HELD is set but $LOCK_DIR does not exist"
        printf '%s\n' "$$" > "$LOCK_DIR/pid"
        LOCK_KIND=directory
        trap cleanup EXIT
        return 0
    fi

    if command -v flock >/dev/null 2>&1; then
        exec {LOCK_FD}<>"$LOCK_FILE"
        if ! flock -n "$LOCK_FD"; then
            holder="$(cat "$LOCK_FILE" 2>/dev/null || true)"
            echo "deploy: another deployment${holder:+ (pid $holder)} holds $LOCK_FILE" >&2
            exit 75
        fi
        printf '%s\n' "$$" > "$LOCK_FILE"
        LOCK_KIND=flock
        trap cleanup EXIT
        return 0
    fi

    # Git for Windows has no flock. Its safe fallback never reclaims an existing directory:
    # an interrupted lock may need manual removal, but it cannot open a second deployment.
    if mkdir "$LOCK_DIR" 2>/dev/null; then
        printf '%s\n' "$$" > "$LOCK_DIR/pid"
        LOCK_KIND=directory
        trap cleanup EXIT
        return 0
    fi
    holder="$(cat "$LOCK_DIR/pid" 2>/dev/null || true)"
    echo "deploy: another deployment${holder:+ (pid $holder)} or an interrupted run holds $LOCK_DIR" >&2
    exit 75
}

# ---- staged files ----------------------------------------------------------------------------

# A rename replaces each file atomically. The running copy of this script keeps its old inode,
# so when the staged deploy.sh differs it re-executes the installed one with the same
# arguments minus --sync-from, passing the held lock along; this happens before --set-stdin
# reads standard input, so the settings reach the new entrance intact.
apply_sync() {
    [ -n "$SYNC_FROM" ] || return 0
    [ -d "$SYNC_FROM" ] || fail "--sync-from directory does not exist: $SYNC_FROM"
    local file reexec=0 args=()
    if [ -f "$SYNC_FROM/deploy.sh" ] && ! cmp -s "$SYNC_FROM/deploy.sh" "${BASH_SOURCE[0]}"; then
        reexec=1
    fi
    for file in compose.yaml compose.executor.yaml Caddyfile deploy.sh; do
        [ -f "$SYNC_FROM/$file" ] || continue
        mv -f "$SYNC_FROM/$file" "$ROOT/$file"
    done
    [ ! -f "$ROOT/deploy.sh" ] || chmod 755 "$ROOT/deploy.sh"
    rmdir "$SYNC_FROM" 2>/dev/null || true
    [ "$reexec" = 1 ] || return 0

    set -- "${ORIGINAL_ARGS[@]}"
    while [ $# -gt 0 ]; do
        case "$1" in
            --sync-from) shift 2 ;;
            *) args+=("$1"); shift ;;
        esac
    done
    if [ "$LOCK_KIND" = flock ]; then
        export POKETTO_DEPLOY_LOCK_FD="$LOCK_FD"
    else
        export POKETTO_DEPLOY_LOCK_HELD=1
    fi
    exec "$BASH" "$ROOT/deploy.sh" "${args[@]}"
}

# ---- configuration ---------------------------------------------------------------------------

read_stdin_settings() {
    local line key value
    while IFS= read -r line || [ -n "$line" ]; do
        [ -n "$line" ] || continue
        key="${line%%=*}"
        value="${line#*=}"
        [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] && [ "$key" != "$line" ] \
            || fail "--set-stdin expects KEY=VALUE lines with an uppercase key"
        case "$key" in
            REGISTRY_USERNAME) REGISTRY_USERNAME="$value" ;;
            REGISTRY_PASSWORD) REGISTRY_PASSWORD="$value" ;;
            *) stage_value "$key" "$value" ;;
        esac
    done
}

load_env_file() {
    local line key value
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%$'\r'}"
        [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]] && continue
        key="${line%%=*}"
        value="${line#*=}"
        [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] && [ "$key" != "$line" ] \
            || fail "$ENV_FILE expects literal KEY=VALUE lines with an uppercase key"
        is_config_key "$key" || fail "unsupported configuration key in $ENV_FILE: $key"
        printf -v "$key" '%s' "$value"
        export "$key"
    done < "$ENV_FILE"
}

canonical_path() {
    realpath -m -- "$1" 2>/dev/null || fail "cannot resolve the path $1"
}

# True when $1 equals $2 or lies below it; both must be canonical absolute paths.
path_within() {
    local child="$1" parent="${2%/}"
    [ "$child" = "${parent:-/}" ] || [[ "$child" == "$parent"/* ]]
}

check_directories() {
    local root
    [[ "$POKETTO_DATA_DIR_HOST" = /* ]] || fail "POKETTO_DATA_DIR_HOST must be absolute"
    [[ "$POKETTO_DB_DIR_HOST" = /* ]] || fail "POKETTO_DB_DIR_HOST must be absolute"
    DATA_DIR="$(canonical_path "$POKETTO_DATA_DIR_HOST")"
    DB_DIR="$(canonical_path "$POKETTO_DB_DIR_HOST")"
    root="$(canonical_path "$ROOT")"
    [ "$DATA_DIR" != "$DB_DIR" ] \
        || fail "POKETTO_DATA_DIR_HOST and POKETTO_DB_DIR_HOST must be different directories, both resolve to $DATA_DIR"
    ! path_within "$DATA_DIR" "$DB_DIR" \
        || fail "POKETTO_DATA_DIR_HOST $DATA_DIR lies inside POKETTO_DB_DIR_HOST $DB_DIR"
    ! path_within "$DB_DIR" "$DATA_DIR" \
        || fail "POKETTO_DB_DIR_HOST $DB_DIR lies inside POKETTO_DATA_DIR_HOST $DATA_DIR"
    ! path_within "$root" "$DATA_DIR" \
        || fail "POKETTO_DATA_DIR_HOST $DATA_DIR is or contains the deployment root $root"
    ! path_within "$root" "$DB_DIR" \
        || fail "POKETTO_DB_DIR_HOST $DB_DIR is or contains the deployment root $root"
    [[ "$POKETTO_GATEWAY_DIR_HOST" = /* ]] || fail "POKETTO_GATEWAY_DIR_HOST must be absolute"
    GATEWAY_DIR="$(canonical_path "$POKETTO_GATEWAY_DIR_HOST")"
    local other
    for other in "$DATA_DIR" "$DB_DIR"; do
        ! path_within "$GATEWAY_DIR" "$other" && ! path_within "$other" "$GATEWAY_DIR" \
            || fail "gateway certificate data must not overlap application or database data"
    done
    ! path_within "$root" "$GATEWAY_DIR" || fail "gateway data must not contain the deployment root"

}

ipv4_number() {
    local address="$1" a b c d part
    [[ "$address" =~ ^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$ ]] || return 1
    IFS=. read -r a b c d <<< "$address"
    for part in "$a" "$b" "$c" "$d"; do [ "$part" -le 255 ] || return 1; done
    printf '%s' "$(( (a << 24) | (b << 16) | (c << 8) | d ))"
}

check_proxy_network() {
    local cidr="$POKETTO_NETWORK_SUBNET" address prefix network gateway mask last
    local pool pool_prefix pool_mask pool_last
    [[ "$cidr" =~ ^([^/]+)/([1-9]|1[0-9]|2[0-8])$ ]] || fail "POKETTO_NETWORK_SUBNET must be a private IPv4 CIDR with at least 16 addresses (/28 or larger)"
    address="${BASH_REMATCH[1]}"; prefix="${BASH_REMATCH[2]}"
    network="$(ipv4_number "$address")" || fail "POKETTO_NETWORK_SUBNET must contain a canonical IPv4 address"
    gateway="$(ipv4_number "$POKETTO_GATEWAY_INTERNAL_IP")" || fail "POKETTO_GATEWAY_INTERNAL_IP must be one canonical IPv4 address"
    mask=$(( (0xffffffff << (32 - prefix)) & 0xffffffff ))
    [ "$((network & mask))" = "$network" ] || fail "POKETTO_NETWORK_SUBNET must start at its network address"
    if ! { [ "$prefix" -ge 8 ] && [ "$((network >> 24))" = 10 ]; } \
        && ! { [ "$prefix" -ge 12 ] && [ "$((network >> 20))" = 2753 ]; } \
        && ! { [ "$prefix" -ge 16 ] && [ "$((network >> 16))" = 49320 ]; }; then
        fail "POKETTO_NETWORK_SUBNET must lie entirely within RFC1918 private space"
    fi
    last=$(( network | (0xffffffff ^ mask) ))
    [ "$gateway" -gt "$((network + 1))" ] && [ "$gateway" -lt "$last" ] \
        || fail "POKETTO_GATEWAY_INTERNAL_IP must be inside the subnet, excluding network, bridge and broadcast addresses"
    [[ "$POKETTO_NETWORK_DYNAMIC_RANGE" =~ ^([^/]+)/([1-9]|[12][0-9])$ ]] \
        || fail "POKETTO_NETWORK_DYNAMIC_RANGE must be an IPv4 CIDR with at least eight addresses (/29 or larger)"
    address="${BASH_REMATCH[1]}"; pool_prefix="${BASH_REMATCH[2]}"
    pool="$(ipv4_number "$address")" || fail "POKETTO_NETWORK_DYNAMIC_RANGE must contain a canonical IPv4 address"
    pool_mask=$(( (0xffffffff << (32 - pool_prefix)) & 0xffffffff ))
    [ "$((pool & pool_mask))" = "$pool" ] || fail "POKETTO_NETWORK_DYNAMIC_RANGE must start at its network address"
    pool_last=$(( pool | (0xffffffff ^ pool_mask) ))
    [ "$pool_prefix" -gt "$prefix" ] && [ "$pool" -ge "$network" ] && [ "$pool_last" -le "$last" ] \
        || fail "POKETTO_NETWORK_DYNAMIC_RANGE must be a strict subnet of POKETTO_NETWORK_SUBNET"
    { [ "$gateway" -lt "$pool" ] || [ "$gateway" -gt "$pool_last" ]; } \
        || fail "POKETTO_GATEWAY_INTERNAL_IP must be outside POKETTO_NETWORK_DYNAMIC_RANGE"
}

load_configuration() {
    local missing=() key i
    [ -f "$COMPOSE_FILE" ] || fail "missing $COMPOSE_FILE"
    [ -f "$ENV_FILE" ] || fail "missing $ENV_FILE; copy .env.example beside compose.yaml and fill it in"
    if [ "$SET_STDIN" = 1 ]; then
        read_stdin_settings
    fi

    load_env_file
    PREVIOUS_PINS=()
    for key in "${PIN_KEYS[@]}"; do
        PREVIOUS_PINS+=("${!key:-}")
    done
    for i in "${!PENDING_KEYS[@]}"; do
        printf -v "${PENDING_KEYS[$i]}" '%s' "${PENDING_VALUES[$i]}"
        export "${PENDING_KEYS[$i]}"
    done

    for key in POKETTO_APP_IMAGE POKETTO_APP_REVISION POKETTO_DB_IMAGE POSTGRES_DB POSTGRES_USER \
            POSTGRES_PASSWORD POKETTO_REPOSITORY_REMOTE_URI POKETTO_REPOSITORY_USERNAME \
            POKETTO_REPOSITORY_PASSWORD POKETTO_DATA_DIR_HOST POKETTO_DB_DIR_HOST \
            POKETTO_FRONTEND_IMAGE POKETTO_GATEWAY_IMAGE POKETTO_GATEWAY_DIR_HOST \
            POKETTO_PUBLIC_DOMAIN POKETTO_AUTH_INITIALIZATION_TOKEN \
            POKETTO_NETWORK_SUBNET POKETTO_NETWORK_DYNAMIC_RANGE POKETTO_GATEWAY_INTERNAL_IP; do
        [ -n "${!key:-}" ] || missing+=("$key")
    done
    [ ${#missing[@]} -eq 0 ] || fail "missing values in $ENV_FILE: ${missing[*]}"

    [[ "$POKETTO_APP_REVISION" =~ ^[0-9a-f]{40}$ ]] \
        || fail "POKETTO_APP_REVISION must be a full lowercase commit id"
    [[ "$POKETTO_APP_IMAGE" =~ ^[^[:space:]]+$ ]] \
        || fail "POKETTO_APP_IMAGE must be one image reference"
    [[ "$POKETTO_DB_IMAGE" =~ ^[A-Za-z0-9._:/-]+@sha256:[0-9a-f]{64}$ ]] \
        || fail "POKETTO_DB_IMAGE must be pinned as <image>@sha256:<digest>"
    [[ "$POKETTO_FRONTEND_IMAGE" =~ ^[^[:space:]]+$ ]] || fail "POKETTO_FRONTEND_IMAGE must be one image reference"
    for key in POKETTO_APP_IMAGE POKETTO_FRONTEND_IMAGE; do
        if [[ "${!key}" == *@* ]]; then
            [[ "${!key}" =~ ^[A-Za-z0-9._:/-]+@sha256:[0-9a-f]{64}$ ]] || fail "$key must carry a complete registry digest"
        else
            [[ "${!key}" == *":sha-$POKETTO_APP_REVISION" ]] || fail "$key must be a digest or a transferred per-commit tag"
        fi
    done
    [[ "$POKETTO_GATEWAY_IMAGE" =~ ^[A-Za-z0-9._:/-]+@sha256:[0-9a-f]{64}$ ]] || fail "POKETTO_GATEWAY_IMAGE must be pinned as <image>@sha256:<digest>"
    [[ "$POKETTO_PUBLIC_DOMAIN" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$ ]] && [ "${#POKETTO_PUBLIC_DOMAIN}" -le 253 ] \
        || fail "POKETTO_PUBLIC_DOMAIN must be one lowercase DNS name without a scheme, port or path"
    case "${POKETTO_EXECUTOR_ENABLED:-false}" in true|false) ;; *) fail "POKETTO_EXECUTOR_ENABLED must be true or false" ;; esac
    [ -f "$ROOT/Caddyfile" ] || fail "missing $ROOT/Caddyfile"
    check_proxy_network
    check_directories

    HTTP_BIND=127.0.0.1
    HTTP_PORT="${POKETTO_HTTP_PORT:-8080}"
    HEALTH_TIMEOUT="${POKETTO_HEALTH_TIMEOUT:-180}"
    HEALTH_INTERVAL="${POKETTO_HEALTH_INTERVAL:-5}"
    MIN_FREE_MB="${POKETTO_MIN_FREE_MB:-2048}"
    APP_UID="${POKETTO_APP_UID:-10001}"
    GATEWAY_UID="${POKETTO_GATEWAY_UID:-10002}"
}

# Rewrites .env with the pending values in one pass; the temporary file is renamed into place so
# a reader never sees a partial file.
write_env_file() {
    local tmp line key i written=()
    tmp="$(mktemp "$ROOT/.env.XXXXXX")"
    for i in "${!PENDING_KEYS[@]}"; do
        written[$i]=0
    done
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%$'\r'}"
        key="${line%%=*}"
        for i in "${!PENDING_KEYS[@]}"; do
            if [ "$key" != "$line" ] && [ "$key" = "${PENDING_KEYS[$i]}" ]; then
                line="$key=${PENDING_VALUES[$i]}"
                written[$i]=1
                break
            fi
        done
        printf '%s\n' "$line" >> "$tmp"
    done < "$ENV_FILE"
    for i in "${!PENDING_KEYS[@]}"; do
        [ "${written[$i]}" = 1 ] || printf '%s=%s\n' "${PENDING_KEYS[$i]}" "${PENDING_VALUES[$i]}" >> "$tmp"
    done
    chmod 600 "$tmp"
    mv "$tmp" "$ENV_FILE"
}

write_previous_pins() {
    local tmp i
    tmp="$(mktemp "$ROOT/.env.previous.XXXXXX")"
    for i in "${!PIN_KEYS[@]}"; do
        printf '%s=%s\n' "${PIN_KEYS[$i]}" "${PREVIOUS_PINS[$i]}" >> "$tmp"
    done
    chmod 600 "$tmp"
    mv "$tmp" "$PREVIOUS_ENV_FILE"
}

# Runs only after the health check: .env holds confirmed values, never a failed candidate.
# .env.previous is written when a confirmed application pin is replaced; a first deployment
# has no healthy version to return to and writes none.
record_configuration() {
    [ ${#PENDING_KEYS[@]} -gt 0 ] || return 0
    local i key changed=0 complete=1
    for i in "${!PIN_KEYS[@]}"; do
        key="${PIN_KEYS[$i]}"
        [ "${!key}" = "${PREVIOUS_PINS[$i]}" ] || changed=1
        [ -n "${PREVIOUS_PINS[$i]}" ] || complete=0
    done
    if [ "$changed" = 1 ] && [ "$complete" = 1 ]; then
        write_previous_pins
    fi
    write_env_file
}

# ---- host checks -----------------------------------------------------------------------------

owner_uid() {
    stat -c %u "$1" 2>/dev/null || stat -f %u "$1"
}

# Only registry login and pulls from that registry use this configuration. Other Docker
# commands retain the user's plugins, proxies, and credentials. Copying credsStore or credHelpers
# would send the temporary login to the user's external keychain even from a different directory.
prepare_docker_config() {
    [ -n "$REGISTRY_PASSWORD" ] || return 0
    [ -n "$REGISTRY_USERNAME" ] || fail "REGISTRY_PASSWORD requires REGISTRY_USERNAME"
    local source="${DOCKER_CONFIG:-$HOME/.docker}"
    REGISTRY_CONTEXT="$("$DOCKER" context show)" || fail "cannot resolve the Docker context"
    DOCKER_CONFIG_DIR="$(mktemp -d "${TMPDIR:-/tmp}/poketto-docker.XXXXXX")"
    # A nonempty auth map suppresses Docker's automatic native-helper discovery. This empty
    # Docker Hub entry carries no credential; login stores the requested registry only here.
    printf '%s\n' '{"auths":{"https://index.docker.io/v1/":{}}}' > "$DOCKER_CONFIG_DIR/config.json"
    if [ -d "$source/contexts" ]; then
        ln -s "$(canonical_path "$source/contexts")" "$DOCKER_CONFIG_DIR/contexts" 2>/dev/null \
            || cp -R "$source/contexts" "$DOCKER_CONFIG_DIR/contexts"
    fi
}

registry_docker() {
    if [ -n "$DOCKER_CONFIG_DIR" ]; then
        DOCKER_CONFIG="$DOCKER_CONFIG_DIR" DOCKER_CONTEXT="$REGISTRY_CONTEXT" "$DOCKER" "$@"
    else
        "$DOCKER" "$@"
    fi
}

pull_image() {
    if [ -n "$DOCKER_CONFIG_DIR" ] && [ "$(image_registry "$1")" = "$(image_registry "$POKETTO_APP_IMAGE")" ]; then
        registry_docker pull "$1"
    else
        "$DOCKER" pull "$1"
    fi
}

check_free_space() {
    local path="$1" free_mb
    free_mb="$(df -Pm -- "$path" | awk 'NR == 2 { print $4 }')"
    [ "${free_mb:-0}" -ge "$MIN_FREE_MB" ] \
        || fail "only ${free_mb:-0} MB free below $path; at least $MIN_FREE_MB MB is required"
}

check_executor() {
    [ "${POKETTO_EXECUTOR_ENABLED:-false}" = true ] || return 0
    local key runtime staging signing mode
    for key in POKETTO_EXECUTOR_RUNTIME_DIR_HOST POKETTO_EXECUTOR_STAGING_DIR_HOST POKETTO_EXECUTOR_SIGNING_KEY_HOST; do
        [[ "${!key:-}" = /* ]] || fail "$key must be an absolute path to an installed host executor prerequisite"
    done
    runtime="$POKETTO_EXECUTOR_RUNTIME_DIR_HOST"
    staging="$POKETTO_EXECUTOR_STAGING_DIR_HOST"
    signing="$POKETTO_EXECUTOR_SIGNING_KEY_HOST"
    [ -f "$ROOT/compose.executor.yaml" ] || fail "missing executor Compose overlay"
    [ -d "$runtime" ] && [ ! -L "$runtime" ] && [ "$(owner_uid "$runtime")" = 0 ] \
        || fail "executor runtime directory must already exist and be owned by root"
    mode="$(stat -c %a "$runtime")"
    [ "$mode" = 751 ] || fail "executor runtime directory must have mode 0751"
    [ -S "$runtime/control.sock" ] && [ ! -L "$runtime/control.sock" ] \
        || fail "host executor control.sock is unavailable; install/start the isolated worker first"
    [ "$(owner_uid "$runtime/control.sock")" = 0 ] && [ "$(stat -c %a "$runtime/control.sock")" = 660 ] && [ "$(stat -c %g "$runtime/control.sock")" = "$APP_UID" ] \
        || fail "executor socket must be root-owned mode 0660 with the application's group"
    [ -d "$staging" ] && [ ! -L "$staging" ] && [ "$(owner_uid "$staging")" = "$APP_UID" ] \
        || fail "executor staging directory must already exist and be owned by the application uid"
    [ -f "$signing" ] && [ ! -L "$signing" ] && [ "$(owner_uid "$signing")" = "$APP_UID" ] \
        || fail "executor signing key must already exist and be owned by the application uid"
    [ "$(stat -c %a "$signing")" = 600 ] || fail "executor signing key must have mode 0600"
    systemctl is-active --quiet poketto-executor.service \
        || fail "the installed poketto-executor.service must be active before enabling execution"
    check_free_space "$staging"
}

check_host() {
    "$DOCKER" info >/dev/null 2>&1 || fail "docker daemon is not reachable by $(id -un)"
    compose version >/dev/null 2>&1 || fail "docker compose plugin is not installed"

    mkdir -p "$POKETTO_DATA_DIR_HOST" "$POKETTO_DB_DIR_HOST" "$POKETTO_GATEWAY_DIR_HOST"
    if [ "$(owner_uid "$POKETTO_DATA_DIR_HOST")" != "$APP_UID" ]; then
        if [ "$(id -u)" = 0 ]; then
            chown "$APP_UID:$APP_UID" "$POKETTO_DATA_DIR_HOST"
        else
            fail "$POKETTO_DATA_DIR_HOST must be owned by uid $APP_UID; run: sudo chown $APP_UID:$APP_UID $POKETTO_DATA_DIR_HOST"
        fi
    fi

    if [ "$(owner_uid "$POKETTO_GATEWAY_DIR_HOST")" != "$GATEWAY_UID" ]; then
        if [ "$(id -u)" = 0 ]; then chown "$GATEWAY_UID:$GATEWAY_UID" "$POKETTO_GATEWAY_DIR_HOST";
        else fail "gateway certificate directory must be owned by uid $GATEWAY_UID"; fi
    fi
    check_executor

    # Images and container layers land below the daemon's data root, which may be a different
    # filesystem than any directory this script manages.
    local docker_root
    check_free_space "$ROOT"
    check_free_space "$DATA_DIR"
    check_free_space "$DB_DIR"
    check_free_space "$GATEWAY_DIR"
    if docker_root="$("$DOCKER" info --format '{{ .DockerRootDir }}' 2>/dev/null)" && [ -d "$docker_root" ]; then
        check_free_space "$docker_root"
    fi

    compose config --quiet || fail "compose configuration is invalid"
}

# ---- images ----------------------------------------------------------------------------------

image_present() {
    "$DOCKER" image inspect "$1" >/dev/null 2>&1
}

image_revision() {
    "$DOCKER" image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$1"
}

# The registry is the first path component of the application reference when it names a host;
# otherwise Docker Hub. A private GHCR package needs this login on the pull path.
image_registry() {
    local first="${1%%/*}"
    if [[ "$1" == */* ]] && [[ "$first" == *.* || "$first" == *:* || "$first" = localhost ]]; then
        printf '%s\n' "$first"
    else
        printf '%s\n' docker.io
    fi
}

registry_login() {
    [ -n "$REGISTRY_PASSWORD" ] || return 0
    local registry
    registry="$(image_registry "$POKETTO_APP_IMAGE")"
    printf '%s' "$REGISTRY_PASSWORD" \
        | registry_docker login "$registry" --username "$REGISTRY_USERNAME" --password-stdin >/dev/null \
        || fail "registry login to $registry failed"
}

acquire_images() {
    registry_login
    local image revision
    for image in "$POKETTO_DB_IMAGE" "$POKETTO_GATEWAY_IMAGE"; do
        image_present "$image" || pull_image "$image" >/dev/null || fail "cannot pull $image"
    done
    for image in "$POKETTO_APP_IMAGE" "$POKETTO_FRONTEND_IMAGE"; do
        if [[ "$image" == *@sha256:* ]]; then
            image_present "$image" || pull_image "$image" >/dev/null || fail "cannot pull $image"
        else
            image_present "$image" || fail "$image is not loaded on this host; pin a digest or transfer the archive first"
        fi
        revision="$(image_revision "$image")"
        [ "$revision" = "$POKETTO_APP_REVISION" ] \
            || fail "$image carries revision '${revision:-none}', expected $POKETTO_APP_REVISION"
    done
}

# ---- deployment ------------------------------------------------------------------------------

deploy() {
    compose up --detach --remove-orphans --no-build --pull never --wait --wait-timeout "$HEALTH_TIMEOUT" \
        || report_failure "stack startup did not become healthy within ${HEALTH_TIMEOUT}s"
}

wait_for_health() {
    local deadline container status service
    deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
    for service in app frontend gateway; do
        container="$(compose ps --quiet "$service")"
        [ -n "$container" ] || fail "the $service container was not created"
        while :; do
            status="$("$DOCKER" inspect --format '{{ .State.Health.Status }}' "$container" 2>/dev/null || echo unknown)"
            case "$status" in
                healthy) break ;;
                unhealthy) report_failure "the $service container reported unhealthy" ;;
            esac
            if [ "$(date +%s)" -ge "$deadline" ]; then
                report_failure "the $service container did not become healthy within ${HEALTH_TIMEOUT}s (last status: $status)"
            fi
            sleep "$HEALTH_INTERVAL"
        done
    done

    local host="$HTTP_BIND" body
    [ "$host" = 0.0.0.0 ] && host=127.0.0.1
    body="$("$CURL" -fsS --noproxy '*' --max-time 10 "http://$host:$HTTP_PORT/actuator/health" 2>/dev/null)" \
        || report_failure "the health entrance at $host:$HTTP_PORT did not answer"
    [[ "$body" == *'"UP"'* ]] || report_failure "the health entrance answered without UP"
    wait_for_https "$deadline" / "the local HTTPS website did not answer with a valid certificate"
    wait_for_https "$deadline" '/api/public/documents?limit=1' "the same-origin HTTPS API did not answer"
}

# Container health can precede certificate issuance. Both HTTPS entrances share the
# remaining health deadline; every request retains certificate verification.
wait_for_https() {
    local deadline="$1" path="$2" failure="$3" remaining timeout
    while :; do
        remaining=$(( deadline - $(date +%s) ))
        [ "$remaining" -gt 0 ] || report_failure "$failure within ${HEALTH_TIMEOUT}s"
        timeout=$(( remaining < 15 ? remaining : 15 ))
        if "$CURL" -fsS --noproxy '*' --max-time "$timeout" \
            --resolve "$POKETTO_PUBLIC_DOMAIN:443:127.0.0.1" "https://$POKETTO_PUBLIC_DOMAIN$path" >/dev/null 2>&1; then
            return 0
        fi
        [ "$(date +%s)" -lt "$deadline" ] || report_failure "$failure within ${HEALTH_TIMEOUT}s"
        sleep 1
    done
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
    apply_sync
    load_configuration
    prepare_docker_config
    check_host
    acquire_images
    deploy
    wait_for_health
    record_configuration
    echo "deploy: healthy; app=$POKETTO_APP_IMAGE revision=$POKETTO_APP_REVISION frontend=$POKETTO_FRONTEND_IMAGE db=$POKETTO_DB_IMAGE gateway=$POKETTO_GATEWAY_IMAGE"
}

main
