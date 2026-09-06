#!/usr/bin/env bash
source "$DEPLOY_DIR/tests/lib.sh"

# Only the fixed installed service/helper boundary is simulated here. The native
# resource_pool_probe.py checks the real unprivileged CLI against systemd/cgroup v2.
cat > "$PWD/host-commands.sh" <<'EOF'
systemctl() {
    [ "$*" = 'is-active --quiet poketto-executor.service' ] || return 91
    [ ! -e "$FAKE_STATE/worker-inactive" ]
}
python3() {
    if [ "${1:-}" = /opt/poketto-executor/resource_pool.py ]; then
        printf '%s\n' "$*" >> "$FAKE_STATE/pool-check.log"
        [ "$*" = '/opt/poketto-executor/resource_pool.py --service poketto-executor.service' ] || return 92
        [ ! -e "$FAKE_STATE/pool-check-fails" ]
    else
        command python3 "$@"
    fi
}
EOF
export BASH_ENV="$PWD/host-commands.sh"

executor_config() {
    printf '%s\n' 'POKETTO_EXECUTOR_ENABLED=true' \
        "POKETTO_EXECUTOR_RUNTIME_DIR_HOST=$ROOT/not-installed" \
        "POKETTO_EXECUTOR_STAGING_DIR_HOST=$ROOT/staging" \
        "POKETTO_EXECUTOR_SIGNING_KEY_HOST=$ROOT/signing.pem" >> "$ROOT/.env"
}

# Unlimited/misplaced pools and a missing or broken helper all return nonzero.
setup_root
executor_config
touch "$FAKE_STATE/pool-check-fails"
run_deploy
assert_status 1
assert_contains "$ERR" 'verified finite aggregate resource pool'
[ "$(wc -l < "$FAKE_STATE/pool-check.log" | tr -d ' ')" = 1 ]
[ "$(up_count)" = 0 ]

# A valid pool does not bypass the existing socket and identity prerequisites.
setup_root
executor_config
run_deploy
assert_status 1
assert_contains "$ERR" 'executor runtime directory must already exist'
[ -s "$FAKE_STATE/pool-check.log" ]
[ "$(up_count)" = 0 ]

setup_root
executor_config
touch "$FAKE_STATE/worker-inactive"
run_deploy
assert_status 1
assert_contains "$ERR" 'poketto-executor.service must be active'
[ ! -e "$FAKE_STATE/pool-check.log" ]
[ "$(up_count)" = 0 ]

# The executor-disabled profile has no systemd prerequisite.
setup_root
have_image "$DIGEST_IMAGE"
touch "$FAKE_STATE/pool-check-fails"
run_deploy
assert_status 0
[ ! -e "$FAKE_STATE/pool-check.log" ]
