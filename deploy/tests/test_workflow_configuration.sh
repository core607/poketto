#!/usr/bin/env bash
. "$DEPLOY_DIR/tests/lib.sh"

workflow="$DEPLOY_DIR/../.github/workflows/ci.yml"

# POKETTO_SSH is expanded as a command string, so every path inside it must already be absolute.
grep -Fq 'SSH_DIR: ${{ runner.temp }}/poketto-ssh' "$workflow" \
    || { echo "the workflow does not stage SSH material below runner.temp"; exit 1; }
grep -Fq 'POKETTO_SSH: ssh -i ${{ runner.temp }}/poketto-ssh/deploy_key -o UserKnownHostsFile=${{ runner.temp }}/poketto-ssh/known_hosts' "$workflow" \
    || { echo "POKETTO_SSH does not use the staged absolute paths"; exit 1; }
if grep -Eq 'POKETTO_SSH:.*~/' "$workflow"; then
    echo "POKETTO_SSH contains a tilde that parameter expansion will not expand"
    exit 1
fi

# Publication and deployment carry two independently pinned images from the same source commit.
grep -Fq 'file: frontend/Dockerfile' "$workflow"
grep -Fq 'frontend_image: ${{ steps.summary.outputs.frontend_image }}' "$workflow"
grep -Fq 'FRONTEND_IMAGE: ${{ needs.publish.outputs.frontend_image }}' "$workflow"
grep -Fq -- '--frontend-image "$FRONTEND_IMAGE" --revision "$REVISION" --sync --set-stdin' "$workflow"
grep -Fq 'bash deploy/transfer.sh "${args[@]}" --pull' "$workflow"
grep -Fq "if: vars.POKETTO_DEPLOY_LAYOUT == 'existing'" "$workflow"
grep -Fq "if: vars.POKETTO_DEPLOY_LAYOUT != 'existing'" "$workflow"
grep -Fq -- '--frontend-image "$FRONTEND_IMAGE" --revision "$REVISION" --existing' "$workflow"
existing_step="$(sed -n '/- name: Update existing installation/,/- name: Deploy/p' "$workflow")"
assert_not_contains "$existing_step" 'POKETTO_REPOSITORY_PASSWORD'
assert_not_contains "$existing_step" '--sync'
assert_contains "$existing_step" '--pull --set-stdin'
assert_contains "$existing_step" 'POKETTO_MIRROR_PULL_PASSWORD'
# Existing installations accept all three delivery modes. Pull reaches the canonical registry with
# the job's own package-read token, so the archive path is no longer the only credential-free route.
assert_contains "$existing_step" 'secrets.GITHUB_TOKEN'
assert_contains "$existing_step" 'transfer)'
grep -Fq 'standard|existing) ;;' "$workflow" \
    || { echo "the workflow still restricts which modes an existing installation accepts"; exit 1; }

publication="$(sed -n '/^  publish:/,/^  mirror:/p' "$workflow")"
assert_not_contains "$publication" 'MIRROR_PASSWORD'
assert_not_contains "$publication" 'deploy/mirror.sh'
grep -Fq 'needs: [publish, mirror]' "$workflow"
grep -Fq "needs.publish.result == 'success'" "$workflow"
grep -Fq 'mirror mode requires a successful configured mirror job' "$workflow"
mirror_step="$(sed -n '/^  mirror:/,/^  # Production deployment/p' "$workflow")"
assert_contains "$mirror_step" 'quay.io/skopeo/stable@sha256:'
assert_not_contains "$mirror_step" 'apt-get'

grep -Fq 'dependsOn(gatewayConfigCheck)' "$DEPLOY_DIR/../build.gradle.kts"
grep -Fq 'deploy/tests/validate_gateway.sh' "$DEPLOY_DIR/../build.gradle.kts"
grep -Fq 'python-version: "3.12.14"' "$workflow"

grep -Fq 'dependsOn(appImageIdentityCheck)' "$DEPLOY_DIR/../build.gradle.kts"

# Execute each actual deployment step with a capturing transfer boundary. Removing a GitHub
# setting must emit KEY=; otherwise an installation silently retains its previous credential.
for step in 'Update existing installation' 'Deploy'; do
    awk -v step="$step" '
        $0 == "      - name: " step { selected=1; next }
        selected && /^      - name:/ { exit }
        selected && /^        run: \|/ { body=1; next }
        body { sub(/^          /, ""); print }
    ' "$workflow" > workflow-step.sh
    [ -s workflow-step.sh ]
    for mode in transfer pull mirror; do
        for values in empty configured; do
            (
                for key in POKETTO_RESEND_API_KEY POKETTO_EMAIL_FROM POKETTO_GOOGLE_CLIENT_ID POKETTO_GOOGLE_CLIENT_SECRET POKETTO_SUPPORT_EMAIL; do
                    printf -v "$key" '%s' ''
                    [ "$values" = empty ] || printf -v "$key" '%s' 'synthetic-$literal'
                done
                POKETTO_EMAIL_DAILY_LIMIT=''
                [ "$values" = empty ] || POKETTO_EMAIL_DAILY_LIMIT=250
                DEPLOY_TARGET=ops@host DEPLOY_ROOT=/srv/poketto DEPLOY_MODE="$mode"
                IMAGE="$DIGEST_IMAGE" REPOSITORY_PASSWORD=''
                MIRROR_USERNAME=mirror MIRROR_PULL_PASSWORD=synthetic-mirror
                GITHUB_ACTOR=actor GITHUB_TOKEN=synthetic-registry
                bash() {
                    [ "$1" = deploy/transfer.sh ] || exit 1
                    cat > workflow-stdin
                }
                . ./workflow-step.sh
            ) > workflow-output
            assert_not_contains "$(cat workflow-output)" 'synthetic-'
            for key in POKETTO_RESEND_API_KEY POKETTO_EMAIL_FROM POKETTO_GOOGLE_CLIENT_ID POKETTO_GOOGLE_CLIENT_SECRET POKETTO_SUPPORT_EMAIL; do
                expected="$key="
                [ "$values" = empty ] || expected+='synthetic-$literal'
                grep -qFx "$expected" workflow-stdin
                if [ "$values" = empty ]; then
                    grep -qFx "Clearing identity setting: $key" workflow-output
                else
                    assert_not_contains "$(cat workflow-output)" "Clearing identity setting: $key"
                fi
            done
            limit=100
            [ "$values" = empty ] || limit=250
            grep -qFx "POKETTO_EMAIL_DAILY_LIMIT=$limit" workflow-stdin
        done
    done
done
