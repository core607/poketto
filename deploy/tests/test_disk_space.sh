#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"

# Free space is required below the deployment root, both persistent directories, and the Docker
# data root. The fake df reports a full filesystem only below the path named in df-low.
for location in root data db docker; do
    setup_root
    have_image "$DIGEST_IMAGE"
    case "$location" in
        root) path="$ROOT" ;;
        data) path="$(realpath -m "$ROOT/data")" ;;
        db) path="$(realpath -m "$ROOT/db")" ;;
        docker) path="$FAKE_STATE/docker-root-dir" ;;
    esac
    echo "$path" > "$FAKE_STATE/df-low"
    run_deploy
    assert_status 1
    assert_contains "$ERR" "only 0 MB free below $path"
    [ "$(up_count)" = 0 ] || { echo "the stack started although $location was full"; exit 1; }
done

# A daemon that cannot report its data root, or reports one that is not a directory on this host
# (a daemon inside a virtual machine), skips only that check.
setup_root
have_image "$DIGEST_IMAGE"
touch "$FAKE_STATE/docker-root-fails"
run_deploy
assert_status 0
setup_root
have_image "$DIGEST_IMAGE"
echo "$PWD/vm/var/lib/docker" > "$FAKE_STATE/docker-root"
run_deploy
assert_status 0
