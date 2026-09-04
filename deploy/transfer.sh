#!/usr/bin/env bash
# Restricted-network delivery: moves the application image to the host as a docker-save archive
# over SSH, verifies the archive checksum there, loads it, and invokes the same deployment
# entrance the registry path uses. The database image is still pulled by digest on the host.
#
# Usage: transfer.sh --target USER@HOST --root DIR --image REF --revision COMMIT [--sync] [--set-stdin]
#   --image      application image present locally, or pullable, e.g. ghcr.io/core607/poketto@sha256:...
#   --revision   full commit id the image must carry in its revision label
#   --sync       also copy compose.yaml and deploy.sh from this directory into the remote root
#   --set-stdin  forward KEY=VALUE lines from standard input to the entrance's --set-stdin
# Environment: POKETTO_SSH overrides the ssh command (default: ssh), for example to add -i.
# Exit codes: 0 deployed and healthy, 1 transfer or deployment failure.

set -euo pipefail

DOCKER="${POKETTO_DOCKER:-docker}"
SSH="${POKETTO_SSH:-ssh}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="" ROOT="" IMAGE="" REVISION="" SYNC=0 SET_STDIN=0 SETTINGS=""

fail() {
    echo "transfer: $*" >&2
    exit 1
}

while [ $# -gt 0 ]; do
    case "$1" in
        --target) TARGET="$2"; shift 2 ;;
        --root) ROOT="$2"; shift 2 ;;
        --image) IMAGE="$2"; shift 2 ;;
        --revision) REVISION="$2"; shift 2 ;;
        --sync) SYNC=1; shift ;;
        --set-stdin) SET_STDIN=1; shift ;;
        -h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$TARGET" ] && [ -n "$ROOT" ] && [ -n "$IMAGE" ] && [ -n "$REVISION" ] \
    || fail "--target, --root, --image, and --revision are required"
[[ "$REVISION" =~ ^[0-9a-f]{40}$ ]] || fail "--revision must be a full lowercase commit id"
[[ "$ROOT" = /* ]] || fail "--root must be absolute"
# Settings are read before any other command touches standard input.
[ "$SET_STDIN" = 1 ] && SETTINGS="$(cat)"

remote() {
    # shellcheck disable=SC2086
    $SSH "$TARGET" "$@"
}

if ! "$DOCKER" image inspect "$IMAGE" >/dev/null 2>&1; then
    "$DOCKER" pull "$IMAGE" >/dev/null || fail "cannot pull $IMAGE"
fi
local_revision="$("$DOCKER" image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$IMAGE")"
[ "$local_revision" = "$REVISION" ] \
    || fail "$IMAGE carries revision '${local_revision:-none}', expected $REVISION"

# The archive travels under an immutable per-commit tag, because a digest reference does not
# survive docker save on every image store while a tag does.
TAG="ghcr.io/core607/poketto:sha-$REVISION"
"$DOCKER" tag "$IMAGE" "$TAG"

SYNC_ARGS=""
if [ "$SYNC" = 1 ]; then
    # Files are staged beside the root and verified there; the entrance moves them into place
    # under its own lock, so a broken transfer or a concurrent deployment never sees a
    # half-written compose.yaml or deploy.sh.
    incoming="$ROOT/.incoming"
    remote "mkdir -p '$incoming'" < /dev/null || fail "cannot create $incoming on $TARGET"
    for file in compose.yaml deploy.sh; do
        checksum="$(sha256sum "$HERE/$file" | cut -d ' ' -f 1)"
        remote "set -e; cat > '$incoming/$file.tmp';             echo '$checksum' '$incoming/$file.tmp' | sha256sum --check --status                 || { rm -f '$incoming/$file.tmp'; echo 'sync checksum mismatch' >&2; exit 1; };             mv -f '$incoming/$file.tmp' '$incoming/$file'" < "$HERE/$file"             || fail "cannot sync $file to $TARGET"
    done
    if remote "[ -e '$ROOT/deploy.sh' ]" < /dev/null; then
        SYNC_ARGS="--sync-from '$incoming'"
    else
        # No entrance exists yet, so there is no lock to respect; install the first copy directly.
        remote "set -e; mv -f '$incoming/compose.yaml' '$ROOT/compose.yaml';             mv -f '$incoming/deploy.sh' '$ROOT/deploy.sh'; chmod 755 '$ROOT/deploy.sh'; rmdir '$incoming'" < /dev/null             || fail "cannot install the entrance on $TARGET"
    fi
fi

if remote "docker image inspect '$TAG' >/dev/null 2>&1"; then
    echo "transfer: $TAG is already loaded on $TARGET"
else
    archive="$(mktemp -t poketto-image.XXXXXX)"
    trap 'rm -f "$archive"' EXIT
    "$DOCKER" save "$TAG" | gzip -1 > "$archive" || fail "cannot save $TAG"
    checksum="$(sha256sum "$archive" | cut -d ' ' -f 1)"
    size="$(wc -c < "$archive" | tr -d ' ')"
    echo "transfer: sending $TAG ($size bytes, sha256 $checksum)"
    remote "set -e; f=\$(mktemp -t poketto-image.XXXXXX); cat > \"\$f\"; \
        echo '$checksum' \"\$f\" | sha256sum --check --status || { rm -f \"\$f\"; echo 'archive checksum mismatch' >&2; exit 1; }; \
        gzip -dc \"\$f\" | docker load >/dev/null; rm -f \"\$f\"" < "$archive" \
        || fail "archive transfer or load failed on $TARGET"
fi

if [ "$SET_STDIN" = 1 ]; then
    printf '%s\n' "$SETTINGS" \
        | remote "'$ROOT/deploy.sh' $SYNC_ARGS --set-stdin --app-image '$TAG' --app-revision '$REVISION'"
else
    remote "'$ROOT/deploy.sh' $SYNC_ARGS --app-image '$TAG' --app-revision '$REVISION'" < /dev/null
fi
