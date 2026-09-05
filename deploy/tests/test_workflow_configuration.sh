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

grep -Fq 'run: bash deploy/tests/validate_gateway.sh' "$workflow"
grep -Fq 'python-version: "3.12.14"' "$workflow"
