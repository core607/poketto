#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"

# The pulled image carries a different revision than the one requested.
setup_root
have_image "$DIGEST_IMAGE" "ffffffffffffffffffffffffffffffffffffffff"
run_deploy
assert_status 1
assert_contains "$ERR" "expected $REVISION"
[ "$(up_count)" = 0 ]

# A tag is never pulled; it must already have been loaded from a transferred archive.
setup_root
sed -i.bak "s#^POKETTO_APP_IMAGE=.*#POKETTO_APP_IMAGE=$TAG_IMAGE#" "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "not loaded on this host"
assert_not_contains "$(docker_log)" "pull $TAG_IMAGE"

# Once loaded with the right revision, the tag deploys without any application pull.
have_image "$TAG_IMAGE"
run_deploy
assert_status 0
assert_not_contains "$(docker_log)" "pull $TAG_IMAGE"
