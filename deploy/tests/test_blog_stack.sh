#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"

# A frontend source mismatch is refused before any container replacement.
setup_root
have_image "$DIGEST_IMAGE"
sed -i.bak "s#^$FRONTEND_IMAGE .*#$FRONTEND_IMAGE ffffffffffffffffffffffffffffffffffffffff#" "$FAKE_STATE/revisions"
run_deploy
assert_status 1
assert_contains "$ERR" "$FRONTEND_IMAGE carries revision"
[ "$(up_count)" = 0 ]

# A movable frontend tag is not a transferred commit pin.
setup_root
printf '%s\n' 'POKETTO_FRONTEND_IMAGE=ghcr.io/core607/poketto-frontend:latest' >> "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "transferred per-commit tag"

# Gateway pins and domain syntax cannot inject Caddy configuration.
setup_root
sed -i.bak 's#^POKETTO_GATEWAY_IMAGE=.*#POKETTO_GATEWAY_IMAGE=caddy:latest#' "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "POKETTO_GATEWAY_IMAGE must be pinned"
setup_root
printf '%s\n' 'POKETTO_PUBLIC_DOMAIN=https://site.example.invalid/path' >> "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "one lowercase DNS name"

# Both frontend health and a certificate-valid local HTTPS roundtrip are required.
setup_root
have_image "$DIGEST_IMAGE"
touch "$FAKE_STATE/fake-frontend-container-unhealthy"
run_deploy
assert_status 1
assert_contains "$ERR" "frontend container reported unhealthy"
setup_root
have_image "$DIGEST_IMAGE"
touch "$FAKE_STATE/https-fails"
cp "$ROOT/.env" "$PWD/env.before"
POKETTO_HEALTH_TIMEOUT=5 run_deploy
assert_status 1
assert_contains "$ERR" "valid certificate"
[ "$(cat "$FAKE_STATE/https-site-attempts")" -ge 2 ]
cmp -s "$ROOT/.env" "$PWD/env.before"
[ ! -e "$ROOT/.env.previous" ]
setup_root
have_image "$DIGEST_IMAGE"
touch "$FAKE_STATE/https-api-fails"
POKETTO_HEALTH_TIMEOUT=5 run_deploy
assert_status 1
assert_contains "$ERR" "same-origin HTTPS API"
[ "$(cat "$FAKE_STATE/https-api-attempts")" -ge 2 ]
setup_root
have_image "$DIGEST_IMAGE"
run_deploy
assert_status 0
assert_contains "$(cat "$FAKE_STATE/curl.log")" "--resolve site.example.invalid:443:127.0.0.1 https://site.example.invalid/"
assert_contains "$(cat "$FAKE_STATE/curl.log")" "https://site.example.invalid/api/public/documents?limit=1"

# Enabling execution never creates a weaker replacement when host prerequisites are absent.
setup_root
have_image "$DIGEST_IMAGE"
printf '%s\n' 'POKETTO_EXECUTOR_ENABLED=true' >> "$ROOT/.env"
run_deploy
assert_status 1
assert_contains "$ERR" "installed host executor prerequisite"
[ "$(up_count)" = 0 ]

# Exercise the shipped route/config declarations. Actual Caddy container routing is a separate
# native runtime gate; these assertions do not claim an HTTPS/browser acceptance.
grep -Fq '@management path /actuator /actuator/*' "$DEPLOY_DIR/Caddyfile"
grep -Fq '@business path /api /api/* /mcp /mcp/*' "$DEPLOY_DIR/Caddyfile"
grep -Fq 'reverse_proxy app:8080' "$DEPLOY_DIR/Caddyfile"
grep -Fq 'reverse_proxy frontend:3000' "$DEPLOY_DIR/Caddyfile"
grep -Fq 'respond 404' "$DEPLOY_DIR/Caddyfile"
grep -Fq '127.0.0.1:${POKETTO_HTTP_PORT:-8080}:8080' "$DEPLOY_DIR/compose.yaml"
frontend="$(sed -n '/^  frontend:/,/^  gateway:/p' "$DEPLOY_DIR/compose.yaml")"
assert_not_contains "$frontend" 'PASSWORD'
assert_not_contains "$frontend" 'TOKEN'
assert_not_contains "$frontend" 'SIGNING'
assert_not_contains "$frontend" 'volumes:'
grep -Fq 'create_host_path: false' "$DEPLOY_DIR/compose.executor.yaml"
