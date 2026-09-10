#!/usr/bin/env bash
# Copy canonical image digests without rebuilding or changing their manifests.
set -euo pipefail
umask 077
fail() { echo "mirror: $*" >&2; exit 1; }
SKOPEO="${POKETTO_SKOPEO:-skopeo}"
prefix="${MIRROR_REPOSITORY:-}"
[[ "$prefix" =~ ^[a-z0-9][a-z0-9.:-]*/[a-z0-9][a-z0-9._/-]*[a-z0-9]$ ]] \
    && [[ "$prefix" != *..* && "$prefix" != *//* ]] || fail "invalid MIRROR_REPOSITORY"
[[ "${REVISION:-}" =~ ^[0-9a-f]{40}$ ]] || fail "REVISION must be a full commit id"
for ref in "${IMAGE:-}" "${FRONTEND_IMAGE:-}"; do
    [[ "$ref" =~ ^ghcr.io/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$ ]] || fail "expected canonical GHCR digest references"
done
[ -n "${MIRROR_USERNAME:-}" ] && [ -n "${MIRROR_PASSWORD:-}" ] || fail "mirror credentials are required"
[ -n "${GHCR_USERNAME:-}" ] && [ -n "${GHCR_PASSWORD:-}" ] || fail "GHCR credentials are required"
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
trap 'exit 143' HUP INT TERM
auth="$temporary/auth.json"
printf '%s\n' '{"auths":{}}' > "$auth"
printf '%s' "$GHCR_PASSWORD" | "$SKOPEO" login --authfile "$auth" --username "$GHCR_USERNAME" --password-stdin ghcr.io >/dev/null
printf '%s' "$MIRROR_PASSWORD" | "$SKOPEO" login --authfile "$auth" --username "$MIRROR_USERNAME" --password-stdin "${prefix%%/*}" >/dev/null
unset GHCR_PASSWORD MIRROR_PASSWORD
copy_image() {
    local source="$1" name="$2" digest="${1##*@}"
    "$SKOPEO" copy --authfile "$auth" --all --preserve-digests --digestfile "$temporary/digest" \
        "docker://$source" "docker://$prefix/$name:sha-$REVISION"
    [ "$(cat "$temporary/digest")" = "$digest" ] || fail "copied $name digest differs from canonical publication"
    "$SKOPEO" inspect --authfile "$auth" --raw "docker://$prefix/$name@$digest" > "$temporary/manifest"
    [ "sha256:$(sha256sum "$temporary/manifest" | cut -d ' ' -f 1)" = "$digest" ] \
        || fail "mirror $name manifest differs from canonical publication"
}
copy_image "$IMAGE" poketto
copy_image "$FRONTEND_IMAGE" poketto-frontend
{
    echo "image=$prefix/poketto@${IMAGE##*@}"
    echo "frontend_image=$prefix/poketto-frontend@${FRONTEND_IMAGE##*@}"
} >> "$GITHUB_OUTPUT"
{
    echo '## Mirror delivery images'
    echo
    echo "- Application: \`$prefix/poketto@${IMAGE##*@}\`"
    echo "- Frontend: \`$prefix/poketto-frontend@${FRONTEND_IMAGE##*@}\`"
} >> "$GITHUB_STEP_SUMMARY"
