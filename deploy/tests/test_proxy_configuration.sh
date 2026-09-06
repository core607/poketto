#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"

for setting in \
    'POKETTO_NETWORK_SUBNET=0.0.0.0/0' \
    'POKETTO_NETWORK_SUBNET=172.0.0.0/8' \
    'POKETTO_NETWORK_SUBNET=192.168.1.1/24' \
    'POKETTO_NETWORK_SUBNET=10.0.0.0/31' \
    'POKETTO_GATEWAY_INTERNAL_IP=172.29.231.10' \
    'POKETTO_GATEWAY_INTERNAL_IP=172.29.230.0' \
    'POKETTO_GATEWAY_INTERNAL_IP=172.29.230.1' \
    'POKETTO_GATEWAY_INTERNAL_IP=172.29.230.255' \
    'POKETTO_GATEWAY_INTERNAL_IP=172.29.230.010' \
    'POKETTO_GATEWAY_INTERNAL_IP=172.29.230.10/24' \
    'POKETTO_GATEWAY_INTERNAL_IP=.*'; do
    setup_root
    have_image "$DIGEST_IMAGE"
    printf '%s\n' "$setting" >> "$ROOT/.env"
    run_deploy
    assert_status 1
    assert_contains "$ERR" 'POKETTO_'
    [ "$(up_count)" = 0 ]
done

for pair in '10.20.0.0/16 10.20.0.10' '172.16.0.0/12 172.16.0.10' '192.168.0.0/16 192.168.0.10'; do
    setup_root
    have_image "$DIGEST_IMAGE"
    read -r subnet gateway <<< "$pair"
    printf '%s\n' "POKETTO_NETWORK_SUBNET=$subnet" "POKETTO_GATEWAY_INTERNAL_IP=$gateway" >> "$ROOT/.env"
    run_deploy
    assert_status 0
done
