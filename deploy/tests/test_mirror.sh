#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"
mkdir -p "$FAKE_STATE"
cat > "$PWD/skopeo" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
echo "$*" >> "$FAKE_STATE/skopeo.log"
verb="$1"; shift
case "$verb" in
    login)
        [ "$1" = --authfile ]
        echo "$2" > "$FAKE_STATE/auth-path"
        [ -f "$2" ]
        cat > "$FAKE_STATE/login-${*: -1}"
        [ "${FAIL_AT:-}" != login ] ;;
    copy)
        [ "${FAIL_AT:-}" != copy ] || exit 1
        digest_file=""
        while [ "$#" -gt 0 ]; do
            if [ "$1" = --digestfile ]; then digest_file="$2"; shift 2; else shift; fi
        done
        if [ "${FAIL_AT:-}" = digest ]; then echo sha256:bad > "$digest_file";
        else echo "$TEST_DIGEST" > "$digest_file"; fi ;;
    inspect)
        if [ "${FAIL_AT:-}" = manifest ]; then printf altered; else printf '{"schemaVersion":2}'; fi ;;
    *) exit 1 ;;
esac
STUB
chmod +x "$PWD/skopeo"
export POKETTO_SKOPEO="$PWD/skopeo"
export TEST_DIGEST="sha256:$(printf '{"schemaVersion":2}' | sha256sum | cut -d ' ' -f 1)"
export IMAGE="ghcr.io/example/app@$TEST_DIGEST" FRONTEND_IMAGE="ghcr.io/example/frontend@$TEST_DIGEST"
export MIRROR_REPOSITORY=docker.cnb.cool/example/images MIRROR_USERNAME=cnb MIRROR_PASSWORD=mirror-secret
export GHCR_USERNAME=bot GHCR_PASSWORD=source-secret REVISION
export GITHUB_OUTPUT="$PWD/output" GITHUB_STEP_SUMMARY="$PWD/summary"
bash "$DEPLOY_DIR/mirror.sh"
assert_contains "$(cat "$GITHUB_OUTPUT")" "image=$MIRROR_REPOSITORY/poketto@$TEST_DIGEST"
assert_contains "$(cat "$GITHUB_OUTPUT")" "frontend_image=$MIRROR_REPOSITORY/poketto-frontend@$TEST_DIGEST"
assert_contains "$(cat "$FAKE_STATE/skopeo.log")" '--all --preserve-digests'
assert_contains "$(cat "$FAKE_STATE/skopeo.log")" "docker://$IMAGE docker://$MIRROR_REPOSITORY/poketto:sha-$REVISION"
assert_not_contains "$(cat "$FAKE_STATE/skopeo.log" "$GITHUB_OUTPUT" "$GITHUB_STEP_SUMMARY")" 'mirror-secret'
assert_not_contains "$(cat "$FAKE_STATE/skopeo.log" "$GITHUB_OUTPUT" "$GITHUB_STEP_SUMMARY")" 'source-secret'
[ ! -e "$(cat "$FAKE_STATE/auth-path")" ]
for failure in login copy digest manifest; do
    rm -f "$GITHUB_OUTPUT" "$GITHUB_STEP_SUMMARY"
    if FAIL_AT="$failure" bash "$DEPLOY_DIR/mirror.sh" > "$PWD/stdout" 2> "$PWD/stderr"; then
        echo "mirror accepted $failure failure"; exit 1
    fi
    [ ! -e "$GITHUB_OUTPUT" ]
    [ ! -e "$(cat "$FAKE_STATE/auth-path")" ]
done
for prefix in 'https://docker.cnb.cool/example/images' 'docker.cnb.cool/example/../images' 'docker.cnb.cool/example/images;id'; do
    rm -f "$FAKE_STATE/skopeo.log"
    if MIRROR_REPOSITORY="$prefix" bash "$DEPLOY_DIR/mirror.sh" > /dev/null 2>&1; then exit 1; fi
    [ ! -e "$FAKE_STATE/skopeo.log" ]
done
