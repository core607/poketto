#!/usr/bin/env bash
# Deliver application and frontend images from one verified commit through one SSH entrance.
# Default mode transfers a checksummed docker-save archive. --pull lets the host acquire both
# registry digests instead. Database and Caddy remain digest pulls/caches on the host in both modes.
#
# Usage: transfer.sh --target USER@HOST --root DIR --image REF --frontend-image REF
#                    --revision COMMIT [--sync] [--set-stdin] [--pull]
# --sync stages compose.yaml, compose.executor.yaml, Caddyfile and deploy.sh with checksums.
# --set-stdin forwards literal settings and temporary registry credentials without argv exposure.
# POKETTO_SSH overrides the SSH command; only trusted operator configuration supplies this value.
# Exit codes: 0 deployed and healthy, 1 transfer or deployment failure.

set -euo pipefail

DOCKER="${POKETTO_DOCKER:-docker}"
SSH="${POKETTO_SSH:-ssh}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="" ROOT="" IMAGE="" FRONTEND_IMAGE="" REVISION="" SYNC=0 SET_STDIN=0 SETTINGS="" PULL=0

fail() {
    echo "transfer: $*" >&2
    exit 1
}

while [ $# -gt 0 ]; do
    case "$1" in
        --target) TARGET="$2"; shift 2 ;;
        --root) ROOT="$2"; shift 2 ;;
        --image) IMAGE="$2"; shift 2 ;;
        --frontend-image) FRONTEND_IMAGE="$2"; shift 2 ;;
        --revision) REVISION="$2"; shift 2 ;;
        --sync) SYNC=1; shift ;;
        --pull) PULL=1; shift ;;
        --set-stdin) SET_STDIN=1; shift ;;
        -h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$TARGET" ] && [ -n "$ROOT" ] && [ -n "$IMAGE" ] && [ -n "$FRONTEND_IMAGE" ] && [ -n "$REVISION" ] \
    || fail "--target, --root, --image, --frontend-image, and --revision are required"
[[ "$REVISION" =~ ^[0-9a-f]{40}$ ]] || fail "--revision must be a full lowercase commit id"
[[ "$ROOT" = /* ]] || fail "--root must be absolute"
[[ "$ROOT" != *"'"* && "$ROOT" != *$'\n'* && "$ROOT" != *$'\r'* ]] || fail "--root must not contain quotes or line breaks"
# Settings are read before any other command touches standard input.
[ "$SET_STDIN" = 1 ] && SETTINGS="$(cat)"

remote() {
    # shellcheck disable=SC2086
    $SSH "$TARGET" "$@"
}

if [ "$PULL" = 1 ]; then
    for image in "$IMAGE" "$FRONTEND_IMAGE"; do
        [[ "$image" =~ ^[A-Za-z0-9._:/-]+@sha256:[0-9a-f]{64}$ ]] || fail "--pull requires immutable registry digests for both images"
    done
    TAG="$IMAGE"
    FRONTEND_TAG="$FRONTEND_IMAGE"
else
    # Verify both source labels before any remote mutation. Both GHCR images are required,
    # even when the destination already has the application tag.
    for image in "$IMAGE" "$FRONTEND_IMAGE"; do
        if ! "$DOCKER" image inspect "$image" >/dev/null 2>&1; then
            "$DOCKER" pull "$image" >/dev/null || fail "cannot pull $image"
        fi
        local_revision="$("$DOCKER" image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$image")"
        [ "$local_revision" = "$REVISION" ] || fail "$image carries revision '${local_revision:-none}', expected $REVISION"
    done

    TAG="ghcr.io/core607/poketto:sha-$REVISION"
    FRONTEND_TAG="ghcr.io/core607/poketto-frontend:sha-$REVISION"
    "$DOCKER" tag "$IMAGE" "$TAG"
    "$DOCKER" tag "$FRONTEND_IMAGE" "$FRONTEND_TAG"
fi

SYNC_ARGS=""
if [ "$SYNC" = 1 ]; then
    # Files are staged below the root in a directory named after the commit and verified there;
    # the entrance moves them into place under its own lock, so a broken transfer, a concurrent
    # transfer of another commit, or a concurrent deployment never sees a half-written
    # compose.yaml or deploy.sh.
    incoming="$ROOT/.incoming/$REVISION"
    remote "mkdir -p '$incoming'" < /dev/null || fail "cannot create $incoming on $TARGET"
    for file in compose.yaml compose.executor.yaml Caddyfile deploy.sh; do
        checksum="$(sha256sum "$HERE/$file" | cut -d ' ' -f 1)"
        remote "set -e; cat > '$incoming/$file.tmp';             echo '$checksum' '$incoming/$file.tmp' | sha256sum --check --status                 || { rm -f '$incoming/$file.tmp'; echo 'sync checksum mismatch' >&2; exit 1; };             mv -f '$incoming/$file.tmp' '$incoming/$file'" < "$HERE/$file"             || fail "cannot sync $file to $TARGET"
    done
    if remote "[ -e '$ROOT/deploy.sh' ]" < /dev/null; then
        SYNC_ARGS="--sync-from '$incoming'"
    else
        # No entrance exists yet, so there is no lock to respect; install the first copy directly.
        remote "set -e; mv -f '$incoming/compose.yaml' '$ROOT/compose.yaml'; mv -f '$incoming/compose.executor.yaml' '$ROOT/compose.executor.yaml'; mv -f '$incoming/Caddyfile' '$ROOT/Caddyfile';             mv -f '$incoming/deploy.sh' '$ROOT/deploy.sh'; chmod 755 '$ROOT/deploy.sh'; rmdir '$incoming'" < /dev/null             || fail "cannot install the entrance on $TARGET"
    fi
fi

if [ "$PULL" = 0 ]; then
    if remote "docker image inspect '$TAG' '$FRONTEND_TAG' >/dev/null 2>&1"; then
        echo "transfer: $TAG and $FRONTEND_TAG are already loaded on $TARGET"
    else
        archive="$(mktemp -t poketto-image.XXXXXX)"
        trap 'rm -f "$archive"' EXIT
        "$DOCKER" save "$TAG" "$FRONTEND_TAG" | gzip -1 > "$archive" || fail "cannot save $TAG"
        checksum="$(sha256sum "$archive" | cut -d ' ' -f 1)"
        size="$(wc -c < "$archive" | tr -d ' ')"
        echo "transfer: sending $TAG and $FRONTEND_TAG ($size bytes, sha256 $checksum)"
        remote "set -e; f=\$(mktemp -t poketto-image.XXXXXX); cat > \"\$f\"; \
            echo '$checksum' \"\$f\" | sha256sum --check --status || { rm -f \"\$f\"; echo 'archive checksum mismatch' >&2; exit 1; }; \
            gzip -dc \"\$f\" | docker load >/dev/null; rm -f \"\$f\"" < "$archive" \
            || fail "archive transfer or load failed on $TARGET"
    fi

fi

status=0
if [ "$SET_STDIN" = 1 ]; then
    printf '%s\n' "$SETTINGS" \
        | remote "'$ROOT/deploy.sh' $SYNC_ARGS --set-stdin --app-image '$TAG' --app-revision '$REVISION' --frontend-image '$FRONTEND_TAG'" \
        || status=$?
else
    remote "'$ROOT/deploy.sh' $SYNC_ARGS --app-image '$TAG' --app-revision '$REVISION' --frontend-image '$FRONTEND_TAG'" < /dev/null \
        || status=$?
fi
if [ "$status" != 0 ] && [ -n "$SYNC_ARGS" ]; then
    # An entrance that never reached the staged files (for example, exit 75 while another
    # deployment holds the lock) leaves them behind; a retry stages afresh.
    remote "rm -rf '$incoming'" < /dev/null || true
fi
exit "$status"
